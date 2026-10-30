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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/**
 * 宿主输入法的测试界面：普通文本框 text、密码框 password、能拿焦点但不是文本编辑器的 raw。
 * 每次变化打一行 JSON 日志（tag ImeTest），供自动化测试解析。
 *
 * <pre>
 * am start -n org.modavd.ime.testapp/.MainActivity [--es focus text|password|raw] [--ez clear true]
 * </pre>
 */
public final class MainActivity extends Activity {
    static final String TAG = "ImeTest";

    private LoggingEditText text;
    private LoggingEditText password;
    private RawView raw;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        text = findViewById(R.id.text);
        password = findViewById(R.id.password);
        raw = findViewById(R.id.raw);
        apply(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        apply(intent);
    }

    /** --ez clear true 清空两个文本框；--es focus text|password|raw 把焦点给对应控件。 */
    private void apply(Intent intent) {
        if (intent.getBooleanExtra("clear", false)) {
            text.setText("");
            password.setText("");
        }
        String focus = intent.getStringExtra("focus");
        View target = null;
        if ("text".equals(focus)) {
            target = text;
        } else if ("password".equals(focus)) {
            target = password;
        } else if ("raw".equals(focus)) {
            target = raw;
        }
        if (target != null) target.requestFocus();
    }
}
