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
 * The connection to the emulator's modavd_ime pipe service.
 *
 * <p>The read thread opens the pipe, does the handshake and blocks reading frames. After a failure or
 * a disconnect it closes the fd and reconnects with backoff (1 s, 2 s, 4 s ... capped at 30 s); once
 * connected it sends the current focus and cursor again. All writes go through a single-thread executor
 * one after another, so the main thread never blocks. Commands from the host are posted back to the
 * main thread and handed to {@link Listener}.
 *
 * <p>The pipe is opened with RandomAccessFile: on Android, RandomAccessFile.close() signals the threads
 * blocked on this fd, so the read in the read thread throws and returns, and stop() can finish. A plain
 * close(2) does not wake a read blocked on a goldfish pipe.
 */
final class HostLink {
    interface Listener {
        /** Called on the main thread. */
        void onHostCommand(HostProtocol.Command command);
    }

    private static final String TAG = HostImeService.TAG;
    private static final long FIRST_RETRY_MS = 1000;
    private static final long MAX_RETRY_MS = 30000;
    /** After this many failures in a row (the backoff has reached its 30 s cap), the same error is logged only once per QUIET_EVERY failures, so an old emulator does not flood the log. */
    private static final int QUIET_AFTER = 6;
    private static final int QUIET_EVERY = 20;

    /** The current state to tell the host. Replaced only on the main thread; the write thread reads all of it when it reconnects. */
    private static final class State {
        final boolean active;
        final int inputType;
        final int imeOptions;
        /** The latest R (left, top, right, bottom), or null if there is none. */
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
    /** The pipe that is open and has sent the handshake; assigned only while holding lock. */
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

    /** Called when the service is destroyed: tell the host the focus is gone, close the pipe and stop both threads. */
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

    /** Main thread: onStartInput / onFinishInput. When the edit field changes, the old cursor position is dropped. */
    void setFocus(boolean active, int inputType, int imeOptions) {
        state = new State(active, inputType, imeOptions, null);
        send(HostProtocol.focus(active, inputType, imeOptions));
    }

    /** Main thread: the cursor position; it is not sent again if it is the same as last time. */
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
                // Write the service name and V separately: the host connector reads only up to the \0
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
                // stop() interrupted the sleep; go back to the top of the loop and exit
            }
            retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
        }
        Log.i(TAG, "pipe thread stopped");
    }

    /** Write thread: right after connecting, send the current F again (and R if there is one). */
    private void resendState() {
        RandomAccessFile p = pipe;
        if (p == null) return;
        State s = state;
        write(p, HostProtocol.focus(s.active, s.inputType, s.imeOptions));
        if (s.cursor != null) write(p, HostProtocol.cursor(s.cursor[0], s.cursor[1], s.cursor[2], s.cursor[3]));
    }

    /** Dropped while not connected: resendState sends the latest state after connecting. */
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
            // stop() has already been called
        }
    }

    /** Write thread. If a write fails, close the pipe; the read thread sees it on read and reconnects. */
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
            // Already disconnected; nothing to do
        }
    }

    private static boolean debug() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }
}
