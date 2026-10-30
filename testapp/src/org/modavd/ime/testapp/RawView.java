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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;

/**
 * 能拿焦点但不是文本编辑器的控件（像游戏画面）：每次 onKeyDown 打一行
 * {"id":"raw","key":keycode,"meta":metaState}，用来确认输入法没吞按键。
 * 和游戏画面一样吃掉普通按键：不吃的话方向键、Tab 会让框架把焦点挪到文本框上。返回、音量等系统键照旧交给框架。
 */
public final class RawView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String label = "raw";

    public RawView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics()));
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.i(MainActivity.TAG, "{\"id\":\"raw\",\"key\":" + keyCode + ",\"meta\":" + event.getMetaState() + "}");
        label = "raw  key=" + keyCode + "  meta=0x" + Integer.toHexString(event.getMetaState());
        invalidate();
        return !event.isSystem() || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return !event.isSystem() || super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(isFocused() ? 0xffc8e6c9 : 0xffe0e0e0);
        paint.setColor(0xff000000);
        canvas.drawText(isFocused() ? label + "  (focused)" : label, paint.getTextSize(), getHeight() / 2f, paint);
    }
}
