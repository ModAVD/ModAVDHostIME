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

package org.modavd.ime.testapp;

import android.content.Context;
import android.text.Editable;
import android.text.Selection;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;

import java.util.Locale;

/**
 * 内容、选区或组字区每次变化打一行：
 * {"id":"text","text":"...","selStart":n,"selEnd":n,"composing":[s,e] 或 null}。
 * 输入法的一次操作（commitText、setComposingText…）包在批量编辑里，只在批量编辑结束时打一次；
 * 和上一行相同就不打。
 *
 * <p>logd 一条最多 4068 字节（含优先级和 tag），再长会被截成坏 JSON。超过 {@link #MAX_LINE} 时 text 只留开头一段，
 * 末尾另加 "truncated":true,"textLength":n，整行仍是合法 JSON。
 */
public final class LoggingEditText extends EditText {
    static final int MAX_LINE = 4000;

    // 这几个字段不写初始化式：父类构造期间就会回调 onSelectionChanged，初始化式会在那之后把值冲掉
    private boolean ready;
    private boolean inBatchEdit;
    private String lastLogged;
    private String name;

    public LoggingEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                report();
            }
        });
        ready = true;
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        report();
    }

    @Override
    public void onBeginBatchEdit() {
        super.onBeginBatchEdit();
        inBatchEdit = true;
    }

    @Override
    public void onEndBatchEdit() {
        super.onEndBatchEdit();
        inBatchEdit = false;
        report();
    }

    private void report() {
        if (!ready || inBatchEdit) return;
        Editable t = getText();
        if (t == null) return;
        if (name == null) name = getResources().getResourceEntryName(getId());
        int cs = BaseInputConnection.getComposingSpanStart(t);
        int ce = BaseInputConnection.getComposingSpanEnd(t);
        String json = line(name, t, Selection.getSelectionStart(t), Selection.getSelectionEnd(t), cs, ce, Integer.MAX_VALUE);
        if (json.equals(lastLogged)) return;
        lastLogged = json;
        if (json.length() > MAX_LINE) json = line(name, t, Selection.getSelectionStart(t), Selection.getSelectionEnd(t), cs, ce, MAX_LINE);
        Log.i(MainActivity.TAG, json);
    }

    /** 一行日志；整行（全是 ASCII）超过 maxLength 时截短 text，并加 truncated、textLength。 */
    static String line(String name, CharSequence text, int selStart, int selEnd, int cs, int ce, int maxLength) {
        String head = "{\"id\":" + quote(name, Integer.MAX_VALUE) + ",\"text\":";
        String tail = ",\"selStart\":" + selStart + ",\"selEnd\":" + selEnd
                + ",\"composing\":" + (cs < 0 || ce < 0 ? "null" : "[" + Math.min(cs, ce) + "," + Math.max(cs, ce) + "]");
        String full = head + quote(text, Integer.MAX_VALUE) + tail + "}";
        if (full.length() <= maxLength) return full;
        tail += ",\"truncated\":true,\"textLength\":" + text.length() + "}";
        return head + quote(text, maxLength - head.length() - tail.length()) + tail;
    }

    /**
     * JSON 字符串；非 ASCII 和控制字符一律 \\uXXXX（小写十六进制），和 Python json.dumps 默认输出一致。
     * 结果（含两端引号）超过 maxLength 时在完整的转义处截断。
     */
    static String quote(CharSequence s, int maxLength) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), 4096) + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            int mark = b.length();
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c > 0x7e) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                    break;
            }
            if (b.length() + 1 > maxLength) {
                b.setLength(mark);
                break;
            }
        }
        return b.append('"').toString();
    }
}
