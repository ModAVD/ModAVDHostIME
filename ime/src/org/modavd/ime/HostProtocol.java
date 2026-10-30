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

import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

/**
 * 通道协议 v1 的编解码，宿主侧（模拟器的 modavd_ime pipe 服务）和这里逐字节一致。
 *
 * <p>帧 = 4 字节小端无符号负载长度 N（1 ≤ N ≤ 65536）+ N 字节负载；负载第 1 字节是类型字母，整数一律小端，
 * 文本一律 UTF-8、不带结尾 \0、占满负载剩余部分。
 *
 * <p>只用 JDK 的类，宿主 JVM 上的 ProtocolTest 直接测这个类。
 */
final class HostProtocol {
    static final int VERSION = 1;
    static final String DEVICE = "/dev/goldfish_pipe_dprctd";
    /** 打开设备后先写的服务名：ASCII "pipe:modavd_ime" 再加 1 个 \0。 */
    static final byte[] CONNECT = "pipe:modavd_ime\0".getBytes(StandardCharsets.US_ASCII);
    static final int HEADER_SIZE = 4;
    static final int MAX_PAYLOAD = 65536;

    /** 两个方向都有：握手（客体先发）/ 握手回应。 */
    static final byte HELLO = 'V';
    // 客体 → 宿主
    static final byte FOCUS = 'F';
    static final byte CURSOR = 'R';
    // 宿主 → 客体
    static final byte COMMIT = 'C';
    static final byte PREEDIT = 'P';
    static final byte FINISH = 'E';
    static final byte KEY = 'K';
    static final byte DELETE = 'D';

    /** K 帧的 action。 */
    static final int KEY_DOWN = 0;
    static final int KEY_UP = 1;
    static final int KEY_DOWN_UP = 2;

    private HostProtocol() {}

    /** 宿主发来的一帧。 */
    static final class Command {
        final byte type;
        /** V：协议版本；P：组字串里的光标；K：keycode；D：before。 */
        final int arg0;
        /** K：metaState；D：after。 */
        final int arg1;
        /** K：action。 */
        final int arg2;
        /** C / P 的文本，其余为 null。 */
        final String text;

        Command(byte type, int arg0, int arg1, int arg2, String text) {
            this.type = type;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.arg2 = arg2;
            this.text = text;
        }

        /** 日志用；不带文本内容（可能是密码），只给长度。 */
        @Override
        public String toString() {
            switch (type) {
                case HELLO: return "V(version=" + arg0 + ")";
                case COMMIT: return "C(" + text.length() + " chars)";
                case PREEDIT: return "P(cursor=" + arg0 + ", " + text.length() + " chars)";
                case FINISH: return "E";
                case KEY: return "K(keycode=" + arg0 + ", meta=0x" + Integer.toHexString(arg1) + ", action=" + arg2 + ")";
                case DELETE: return "D(before=" + arg0 + ", after=" + arg1 + ")";
                default: return "?(0x" + Integer.toHexString(type & 0xff) + ")";
            }
        }
    }

    // ---- 客体 → 宿主 ----

    /** V：u32 协议版本。 */
    static byte[] hello() {
        byte[] f = frame(HELLO, 4);
        putInt(f, 5, VERSION);
        return f;
    }

    /** F：u8 active、u32 inputType、u32 imeOptions。 */
    static byte[] focus(boolean active, int inputType, int imeOptions) {
        byte[] f = frame(FOCUS, 9);
        f[5] = (byte) (active ? 1 : 0);
        putInt(f, 6, inputType);
        putInt(f, 10, imeOptions);
        return f;
    }

    /** R：i32 left、top、right、bottom（客体 0 号屏的物理像素）。 */
    static byte[] cursor(int left, int top, int right, int bottom) {
        byte[] f = frame(CURSOR, 16);
        putInt(f, 5, left);
        putInt(f, 9, top);
        putInt(f, 13, right);
        putInt(f, 17, bottom);
        return f;
    }

    /** 帧头 + 类型字节，字段留给调用方填；负载从下标 4 开始，字段从下标 5 开始。 */
    private static byte[] frame(byte type, int fieldBytes) {
        byte[] f = new byte[HEADER_SIZE + 1 + fieldBytes];
        putInt(f, 0, 1 + fieldBytes);
        f[HEADER_SIZE] = type;
        return f;
    }

    /** 日志用：一个已编码的客体帧的简述。 */
    static String describe(byte[] frame) {
        if (frame.length <= HEADER_SIZE) return "?";
        switch (frame[HEADER_SIZE]) {
            case HELLO: return "V(version=" + getInt(frame, 5) + ")";
            case FOCUS: return "F(active=" + frame[5] + ", inputType=0x" + Integer.toHexString(getInt(frame, 6))
                    + ", imeOptions=0x" + Integer.toHexString(getInt(frame, 10)) + ")";
            case CURSOR: return "R(" + getInt(frame, 5) + "," + getInt(frame, 9) + "," + getInt(frame, 13) + ","
                    + getInt(frame, 17) + ")";
            default: return "?(0x" + Integer.toHexString(frame[HEADER_SIZE] & 0xff) + ")";
        }
    }

    // ---- 宿主 → 客体 ----

    /** 解析帧头里的负载长度；不在 1..65536 内就是协议错误。 */
    static int payloadLength(byte[] header) throws ProtocolException {
        long n = getInt(header, 0) & 0xffffffffL;
        if (n < 1 || n > MAX_PAYLOAD) throw new ProtocolException("bad frame length " + n);
        return (int) n;
    }

    /**
     * 解析一帧负载。类型未知（含只该客体发的 F、R）或字段不足都是协议错误；固定长度字段之后多出的字节忽略。
     * K 的 action 不在 0..2 时照样返回，由调用方忽略。
     */
    static Command parse(byte[] p) throws ProtocolException {
        if (p.length < 1) throw new ProtocolException("empty payload");
        byte type = p[0];
        switch (type) {
            case HELLO: {
                need(p, 4);
                long version = getInt(p, 1) & 0xffffffffL;
                if (version < 1) throw new ProtocolException("bad protocol version " + version);
                return new Command(type, (int) Math.min(version, Integer.MAX_VALUE), 0, 0, null);
            }
            case COMMIT:
                return new Command(type, 0, 0, 0, utf8(p, 1));
            case PREEDIT:
                need(p, 4);
                return new Command(type, getInt(p, 1), 0, 0, utf8(p, 5));
            case FINISH:
                return new Command(type, 0, 0, 0, null);
            case KEY:
                need(p, 9);
                return new Command(type, getInt(p, 1), getInt(p, 5), p[9] & 0xff, null);
            case DELETE:
                need(p, 8);
                return new Command(type, getInt(p, 1), getInt(p, 5), 0, null);
            default:
                throw new ProtocolException("unknown frame type 0x" + Integer.toHexString(type & 0xff));
        }
    }

    /** 类型字节之后至少要有 fieldBytes 个字节。 */
    private static void need(byte[] p, int fieldBytes) throws ProtocolException {
        if (p.length < 1 + fieldBytes) {
            throw new ProtocolException("short '" + (char) p[0] + "' frame: " + p.length + " bytes");
        }
    }

    /** 坏的 UTF-8 序列换成 U+FFFD，不抛异常。 */
    private static String utf8(byte[] p, int from) {
        return new String(p, from, p.length - from, StandardCharsets.UTF_8);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static int getInt(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }
}
