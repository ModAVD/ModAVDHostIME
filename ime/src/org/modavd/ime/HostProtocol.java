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
 * Encoding and decoding of channel protocol v1. The host side (the emulator's modavd_ime pipe service)
 * matches this byte for byte.
 *
 * <p>A frame is a 4-byte little-endian unsigned payload length N (1 <= N <= 65536) followed by N bytes
 * of payload. The first byte of the payload is the type letter; integers are always little-endian;
 * text is always UTF-8, has no trailing \0, and fills the rest of the payload.
 *
 * <p>It uses only JDK classes, so ProtocolTest on the host JVM tests this class directly.
 */
final class HostProtocol {
    static final int VERSION = 1;
    static final String DEVICE = "/dev/goldfish_pipe_dprctd";
    /** The service name written first after opening the device: ASCII "pipe:modavd_ime" plus one \0. */
    static final byte[] CONNECT = "pipe:modavd_ime\0".getBytes(StandardCharsets.US_ASCII);
    static final int HEADER_SIZE = 4;
    static final int MAX_PAYLOAD = 65536;

    /** Used in both directions: the handshake (the guest sends first) and the handshake reply. */
    static final byte HELLO = 'V';
    // guest -> host
    static final byte FOCUS = 'F';
    static final byte CURSOR = 'R';
    // host -> guest
    static final byte COMMIT = 'C';
    static final byte PREEDIT = 'P';
    static final byte FINISH = 'E';
    static final byte KEY = 'K';
    static final byte DELETE = 'D';

    /** The action of a K frame. */
    static final int KEY_DOWN = 0;
    static final int KEY_UP = 1;
    static final int KEY_DOWN_UP = 2;

    private HostProtocol() {}

    /** One frame from the host. */
    static final class Command {
        final byte type;
        /** V: protocol version; P: cursor in the composing text; K: keycode; D: before. */
        final int arg0;
        /** K: metaState; D: after. */
        final int arg1;
        /** K: action. */
        final int arg2;
        /** The text of C / P; null for the others. */
        final String text;

        Command(byte type, int arg0, int arg1, int arg2, String text) {
            this.type = type;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.arg2 = arg2;
            this.text = text;
        }

        /** For logs. It does not include the text (it may be a password), only the length. */
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

    // ---- guest -> host ----

    /** V: u32 protocol version. */
    static byte[] hello() {
        byte[] f = frame(HELLO, 4);
        putInt(f, 5, VERSION);
        return f;
    }

    /** F: u8 active, u32 inputType, u32 imeOptions. */
    static byte[] focus(boolean active, int inputType, int imeOptions) {
        byte[] f = frame(FOCUS, 9);
        f[5] = (byte) (active ? 1 : 0);
        putInt(f, 6, inputType);
        putInt(f, 10, imeOptions);
        return f;
    }

    /** R: i32 left, top, right, bottom (physical pixels of the guest's display 0). */
    static byte[] cursor(int left, int top, int right, int bottom) {
        byte[] f = frame(CURSOR, 16);
        putInt(f, 5, left);
        putInt(f, 9, top);
        putInt(f, 13, right);
        putInt(f, 17, bottom);
        return f;
    }

    /** Frame header + type byte; the caller fills in the fields. The payload starts at index 4 and the fields at index 5. */
    private static byte[] frame(byte type, int fieldBytes) {
        byte[] f = new byte[HEADER_SIZE + 1 + fieldBytes];
        putInt(f, 0, 1 + fieldBytes);
        f[HEADER_SIZE] = type;
        return f;
    }

    /** For logs: a short description of an encoded guest frame. */
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

    // ---- host -> guest ----

    /** Parses the payload length in the frame header; a length outside 1..65536 is a protocol error. */
    static int payloadLength(byte[] header) throws ProtocolException {
        long n = getInt(header, 0) & 0xffffffffL;
        if (n < 1 || n > MAX_PAYLOAD) throw new ProtocolException("bad frame length " + n);
        return (int) n;
    }

    /**
     * Parses one frame payload. An unknown type (including F and R, which only the guest sends) or missing
     * fields is a protocol error; bytes after the fixed-length fields are ignored.
     * A K frame whose action is not in 0..2 is still returned, and the caller ignores it.
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

    /** There must be at least fieldBytes bytes after the type byte. */
    private static void need(byte[] p, int fieldBytes) throws ProtocolException {
        if (p.length < 1 + fieldBytes) {
            throw new ProtocolException("short '" + (char) p[0] + "' frame: " + p.length + " bytes");
        }
    }

    /** Malformed UTF-8 sequences become U+FFFD; nothing is thrown. */
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
