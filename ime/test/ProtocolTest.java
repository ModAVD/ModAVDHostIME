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
import java.util.Objects;

/**
 * 在宿主 JVM 上核对 HostProtocol 和协议 v1 逐字节一致（build.ps1 打包前跑）。
 * 期望值是照规格手写的十六进制，不从被测代码推出来。
 */
public final class ProtocolTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        // 服务名：ASCII "pipe:modavd_ime" + 1 个 \0
        bytes("connect", HostProtocol.CONNECT, "70 69 70 65 3a 6d 6f 64 61 76 64 5f 69 6d 65 00");
        bytes("V", HostProtocol.hello(), "05 00 00 00 56 01 00 00 00");
        bytes("F active", HostProtocol.focus(true, 0x00020001, 0x12000006), "0a 00 00 00 46 01 01 00 02 00 06 00 00 12");
        bytes("F inactive", HostProtocol.focus(false, 0, 0), "0a 00 00 00 46 00 00 00 00 00 00 00 00 00");
        bytes("R", HostProtocol.cursor(-1, 2, 0x01020304, Integer.MIN_VALUE),
                "11 00 00 00 52 ff ff ff ff 02 00 00 00 04 03 02 01 00 00 00 80");

        length("00 00 00 00", -1);
        length("01 00 00 00", 1);
        length("00 00 01 00", 65536);
        length("01 00 01 00", -1);
        length("ff ff ff ff", -1);
        length("00 00 00 80", -1);

        command("V", "56 01 00 00 00", 'V', 1, 0, 0, null);
        command("V newer, trailing byte", "56 02 00 00 00 ff", 'V', 2, 0, 0, null);
        bad("V version 0", "56 00 00 00 00");
        bad("V short", "56 01 00 00");
        // 你 = e4 bd a0，好 = e5 a5 bd
        command("C", "43 e4 bd a0 e5 a5 bd 61", 'C', 0, 0, 0, "你好a");
        command("C empty", "43", 'C', 0, 0, 0, "");
        command("C bad utf-8", "43 ff 61", 'C', 0, 0, 0, "�a");
        command("P", "50 ff ff ff ff 61 62", 'P', -1, 0, 0, "ab");
        command("P empty", "50 00 00 00 00", 'P', 0, 0, 0, "");
        bad("P short", "50 ff ff ff");
        command("E", "45", 'E', 0, 0, 0, null);
        command("K", "4b 1d 00 00 00 01 10 00 00 02", 'K', 29, 0x1001, 2, null);
        command("K action 255", "4b 43 00 00 00 00 00 00 00 ff", 'K', 67, 0, 255, null);
        bad("K short", "4b 1d 00 00 00 01 10 00 00");
        command("D", "44 02 00 00 00 03 00 00 00", 'D', 2, 3, 0, null);
        command("D negative", "44 ff ff ff ff 00 00 00 00", 'D', -1, 0, 0, null);
        bad("D short", "44 02 00 00 00 03 00 00");
        // F、R 只该客体发；宿主发来就是未知类型
        bad("F from host", "46 01 00 00 00 00 00 00 00 00");
        bad("R from host", "52 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00");
        bad("unknown Z", "5a");
        bad("lower-case c", "63 61");
        bad("type 0", "00");
        bad("empty", "");

        if (failures > 0) {
            System.out.println("ProtocolTest: " + failures + " of " + checks + " checks FAILED");
            System.exit(1);
        }
        System.out.println("ProtocolTest: all " + checks + " checks passed");
    }

    private static void bytes(String name, byte[] actual, String expectedHex) {
        check(name, hex(actual).equals(expectedHex), hex(actual) + " != " + expectedHex);
    }

    private static void length(String headerHex, int expected) {
        String name = "length " + headerHex;
        try {
            int n = HostProtocol.payloadLength(unhex(headerHex));
            check(name, n == expected, "got " + n + ", want " + expected);
        } catch (ProtocolException e) {
            check(name, expected == -1, "unexpected " + e);
        }
    }

    private static void command(String name, String payloadHex, char type, int arg0, int arg1, int arg2, String text) {
        try {
            HostProtocol.Command c = HostProtocol.parse(unhex(payloadHex));
            boolean ok = c.type == (byte) type && c.arg0 == arg0 && c.arg1 == arg1 && c.arg2 == arg2
                    && Objects.equals(c.text, text);
            check(name, ok, "got " + (char) c.type + " " + c.arg0 + " " + c.arg1 + " " + c.arg2 + " " + c.text);
        } catch (ProtocolException e) {
            check(name, false, "unexpected " + e);
        }
    }

    private static void bad(String name, String payloadHex) {
        try {
            HostProtocol.Command c = HostProtocol.parse(unhex(payloadHex));
            check(name, false, "accepted as " + c);
        } catch (ProtocolException e) {
            check(name, true, null);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            if (s.length() > 0) s.append(' ');
            s.append(String.format("%02x", x & 0xff));
        }
        return s.toString();
    }

    private static byte[] unhex(String s) {
        if (s.isEmpty()) return new byte[0];
        String[] parts = s.split(" ");
        byte[] b = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) b[i] = (byte) Integer.parseInt(parts[i], 16);
        return b;
    }
}
