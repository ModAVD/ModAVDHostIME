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

import android.graphics.Matrix;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.util.SparseLongArray;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;

import java.util.Locale;

/**
 * 无界面的「宿主输入法」：把客体的输入焦点和光标位置告诉模拟器，把宿主（Windows 输入法）打出的文字和编辑键
 * 经 InputConnection 交给当前编辑框。连接和收发见 {@link HostLink}，帧格式见 {@link HostProtocol}。
 *
 * <p>故意不覆写 onKeyDown / onKeyUp / onKeyMultiple / onKeyLongPress：物理按键和 adb shell input 注入
 * 照旧直达应用，游戏和自动化都靠这个。
 */
public final class HostImeService extends InputMethodService implements HostLink.Listener {
    static final String TAG = "ModAVDIme";

    private static final int KEY_FLAGS = KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE;

    private HostLink link;
    /** K 帧按下、抬起分开发时，抬起沿用按下时的 downTime。 */
    private final SparseLongArray keyDownTimes = new SparseLongArray();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "created, protocol v" + HostProtocol.VERSION);
        link = new HostLink(this, new Handler(Looper.getMainLooper()));
        link.start();
    }

    @Override
    public void onDestroy() {
        link.stop();
        Log.i(TAG, "destroyed");
        super.onDestroy();
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        return false;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onBindInput() {
        super.onBindInput();
        InputBinding binding = getCurrentInputBinding();
        Log.i(TAG, "input bound, uid=" + (binding != null ? binding.getUid() : -1));
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        int inputType = attribute != null ? attribute.inputType : InputType.TYPE_NULL;
        int imeOptions = attribute != null ? attribute.imeOptions : 0;
        boolean active = (inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_NULL;
        Log.i(TAG, String.format(Locale.ROOT, "onStartInput pkg=%s inputType=0x%x imeOptions=0x%x active=%d restarting=%b",
                attribute != null ? attribute.packageName : null, inputType, imeOptions, active ? 1 : 0, restarting));
        link.setFocus(active, inputType, imeOptions);
        if (active) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE | InputConnection.CURSOR_UPDATE_MONITOR);
            }
        }
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "onFinishInput");
        link.setFocus(false, 0, 0);
    }

    /** 优先用组字区最后一个字符的边界框，否则用 insertion marker（宽 1）；乘 matrix 换成屏幕坐标。拿不到就不发。 */
    @Override
    public void onUpdateCursorAnchorInfo(CursorAnchorInfo info) {
        RectF rect = null;
        CharSequence composing = info.getComposingText();
        int composingStart = info.getComposingTextStart();
        if (composing != null && composing.length() > 0 && composingStart >= 0) {
            rect = info.getCharacterBounds(composingStart + composing.length() - 1);
        }
        if (rect == null) {
            float x = info.getInsertionMarkerHorizontal();
            float top = info.getInsertionMarkerTop();
            float bottom = info.getInsertionMarkerBottom();
            if (Float.isNaN(x) || Float.isNaN(top) || Float.isNaN(bottom)) return;
            rect = new RectF(x, top, x + 1, bottom);
        }
        Matrix matrix = info.getMatrix();
        if (matrix != null) matrix.mapRect(rect);
        int left = Math.round(rect.left);
        int top = Math.round(rect.top);
        link.setCursor(left, top, Math.max(Math.round(rect.right), left + 1), Math.max(Math.round(rect.bottom), top + 1));
    }

    @Override
    public void onHostCommand(HostProtocol.Command command) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            Log.i(TAG, "no input connection, dropped " + command);
            return;
        }
        try {
            switch (command.type) {
                case HostProtocol.COMMIT:
                    ic.commitText(command.text, 1);
                    break;
                case HostProtocol.PREEDIT:
                    // v1 不用组字串里的光标位置，一律放在末尾；空文本 = 撤销组字
                    ic.setComposingText(command.text, 1);
                    break;
                case HostProtocol.FINISH:
                    ic.finishComposingText();
                    break;
                case HostProtocol.KEY:
                    sendKey(ic, command.arg0, command.arg1, command.arg2);
                    break;
                case HostProtocol.DELETE:
                    if (command.arg0 < 0 || command.arg1 < 0) {
                        Log.w(TAG, "ignored " + command);
                    } else {
                        ic.deleteSurroundingText(command.arg0, command.arg1);
                    }
                    break;
                default:
                    // HostProtocol.parse 只放行上面几种（V 在读线程处理）
                    break;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to apply " + command, e);
        }
    }

    private void sendKey(InputConnection ic, int keyCode, int metaState, int action) {
        long now = SystemClock.uptimeMillis();
        switch (action) {
            case HostProtocol.KEY_DOWN:
                keyDownTimes.put(keyCode, now);
                ic.sendKeyEvent(keyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, metaState));
                break;
            case HostProtocol.KEY_UP: {
                long downTime = keyDownTimes.get(keyCode, now);
                keyDownTimes.delete(keyCode);
                ic.sendKeyEvent(keyEvent(downTime, now, KeyEvent.ACTION_UP, keyCode, metaState));
                break;
            }
            case HostProtocol.KEY_DOWN_UP:
                ic.sendKeyEvent(keyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, metaState));
                ic.sendKeyEvent(keyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, metaState));
                break;
            default:
                Log.w(TAG, "ignored key frame with action " + action);
                break;
        }
    }

    private static KeyEvent keyEvent(long downTime, long eventTime, int action, int keyCode, int metaState) {
        return new KeyEvent(downTime, eventTime, action, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KEY_FLAGS, InputDevice.SOURCE_KEYBOARD);
    }
}
