/*
 * Copyright 2026 qiyinxi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details. You should have received a copy of it along with this program; if
 * not, see <https://www.gnu.org/licenses/>.
 */

package org.modavd.ime;

import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 和模拟器 modavd_ime pipe 服务的连接。
 *
 * <p>读线程负责打开管道、握手、阻塞读帧；失败或断开后关 fd，按 1 s、2 s、4 s…封顶 30 s 退避重连，
 * 连上后立刻补发当前焦点和光标。写全部交给单线程执行器串行做，主线程从不阻塞。宿主发来的命令切回主线程交给
 * {@link Listener}。
 *
 * <p>管道用 RandomAccessFile 打开：Android 的 RandomAccessFile.close() 会给阻塞在这个 fd 上的线程发信号，
 * 读线程的 read 因此抛异常返回，stop() 才停得下来（单纯 close(2) 叫不醒阻塞在 goldfish pipe 上的 read）。
 */
final class HostLink {
    interface Listener {
        /** 在主线程调用。 */
        void onHostCommand(HostProtocol.Command command);
    }

    private static final String TAG = HostImeService.TAG;
    private static final long FIRST_RETRY_MS = 1000;
    private static final long MAX_RETRY_MS = 30000;
    /** 连续失败超过这么多次后（已到 30 s 封顶），同样的错误每隔这么多次才记一行，旧模拟器上不一直刷日志。 */
    private static final int QUIET_AFTER = 6;
    private static final int QUIET_EVERY = 20;

    /** 要告诉宿主的当前状态，只在主线程替换，写线程重连时整份读。 */
    private static final class State {
        final boolean active;
        final int inputType;
        final int imeOptions;
        /** 最近一次的 R（left, top, right, bottom），没有就是 null。 */
        final int[] cursor;

        State(boolean active, int inputType, int imeOptions, int[] cursor) {
            this.active = active;
            this.inputType = inputType;
            this.imeOptions = imeOptions;
            this.cursor = cursor;
        }
    }

    private final Listener listener;
    private final Handler main;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ModAVDIme-write");
        t.setDaemon(true);
        return t;
    });
    private final Thread reader = new Thread(this::readLoop, "ModAVDIme-read");
    private final Object lock = new Object();
    private volatile boolean stopped;
    /** 已打开并发过握手的管道；只在持 lock 时赋值。 */
    private volatile RandomAccessFile pipe;
    private volatile State state = new State(false, 0, 0, null);

    HostLink(Listener listener, Handler main) {
        this.listener = listener;
        this.main = main;
        reader.setDaemon(true);
    }

    void start() {
        reader.start();
    }

    /** 服务销毁时调用：告诉宿主焦点没了，关管道，停掉两个线程。 */
    void stop() {
        RandomAccessFile p;
        synchronized (lock) {
            if (stopped) return;
            stopped = true;
            p = pipe;
        }
        if (p != null) {
            submit(() -> {
                write(p, HostProtocol.focus(false, 0, 0));
                closeQuietly(p);
            });
        }
        writer.shutdown();
        reader.interrupt();
    }

    /** 主线程：onStartInput / onFinishInput。换了编辑框，旧的光标位置作废。 */
    void setFocus(boolean active, int inputType, int imeOptions) {
        state = new State(active, inputType, imeOptions, null);
        send(HostProtocol.focus(active, inputType, imeOptions));
    }

    /** 主线程：光标位置，和上次相同就不发。 */
    void setCursor(int left, int top, int right, int bottom) {
        State s = state;
        int[] rect = {left, top, right, bottom};
        if (Arrays.equals(rect, s.cursor)) return;
        state = new State(s.active, s.inputType, s.imeOptions, rect);
        send(HostProtocol.cursor(left, top, right, bottom));
    }

    private void readLoop() {
        long retryMs = FIRST_RETRY_MS;
        int failures = 0;
        String lastError = null;
        while (!stopped) {
            RandomAccessFile p = null;
            boolean greeted = false;
            String error = null;
            try {
                p = new RandomAccessFile(HostProtocol.DEVICE, "rw");
                // 服务名和 V 分两次写：宿主的连接器只吃到 \0 为止
                p.write(HostProtocol.CONNECT);
                p.write(HostProtocol.hello());
                synchronized (lock) {
                    if (stopped) break;
                    pipe = p;
                }
                if (debug()) Log.d(TAG, "pipe open, handshake sent");
                submit(this::resendState);
                byte[] header = new byte[HostProtocol.HEADER_SIZE];
                while (true) {
                    p.readFully(header);
                    byte[] payload = new byte[HostProtocol.payloadLength(header)];
                    p.readFully(payload);
                    HostProtocol.Command command = HostProtocol.parse(payload);
                    if (debug()) Log.d(TAG, "recv " + command);
                    if (command.type == HostProtocol.HELLO) {
                        if (!greeted) {
                            greeted = true;
                            retryMs = FIRST_RETRY_MS;
                            failures = 0;
                            lastError = null;
                            Log.i(TAG, "connected to host, protocol v" + command.arg0);
                        }
                        continue;
                    }
                    main.post(() -> {
                        if (!stopped) listener.onHostCommand(command);
                    });
                }
            } catch (IOException | RuntimeException e) {
                error = e.toString();
            } finally {
                synchronized (lock) {
                    if (pipe == p) pipe = null;
                }
                closeQuietly(p);
            }
            if (stopped) break;

            failures++;
            if (failures <= QUIET_AFTER || failures % QUIET_EVERY == 0 || !error.equals(lastError) || debug()) {
                Log.i(TAG, (greeted ? "disconnected: " : "host pipe unavailable: ") + error
                        + "; retry in " + retryMs / 1000 + " s"
                        + (failures > QUIET_AFTER ? " (failure " + failures + ")" : ""));
            }
            lastError = error;
            try {
                Thread.sleep(retryMs);
            } catch (InterruptedException e) {
                // stop() 打断睡眠，回到循环开头退出
            }
            retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
        }
        Log.i(TAG, "pipe thread stopped");
    }

    /** 写线程：刚连上时补发当前 F（以及有的话 R）。 */
    private void resendState() {
        RandomAccessFile p = pipe;
        if (p == null) return;
        State s = state;
        write(p, HostProtocol.focus(s.active, s.inputType, s.imeOptions));
        if (s.cursor != null) write(p, HostProtocol.cursor(s.cursor[0], s.cursor[1], s.cursor[2], s.cursor[3]));
    }

    /** 没连上时直接丢掉：连上后 resendState 会补发最新状态。 */
    private void send(byte[] frame) {
        submit(() -> {
            RandomAccessFile p = pipe;
            if (p != null) write(p, frame);
        });
    }

    private void submit(Runnable task) {
        try {
            writer.execute(task);
        } catch (RejectedExecutionException e) {
            // 已经 stop()
        }
    }

    /** 写线程。写失败就关管道，读线程会在 read 上看到并去重连。 */
    private void write(RandomAccessFile p, byte[] frame) {
        try {
            p.write(frame);
            if (debug()) Log.d(TAG, "send " + HostProtocol.describe(frame));
        } catch (IOException e) {
            if (!stopped) Log.i(TAG, "write failed: " + e);
            closeQuietly(p);
        }
    }

    private static void closeQuietly(RandomAccessFile p) {
        if (p == null) return;
        try {
            p.close();
        } catch (IOException e) {
            // 已经断了，没什么可做
        }
    }

    private static boolean debug() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }
}
