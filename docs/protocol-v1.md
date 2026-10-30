# 宿主输入法通道协议 v1

ModAVD 模拟器（0.35.0 起）和客体里的输入法 `org.modavd.ime` 之间的通道。两边必须逐字节一致；
本仓库的 `ime/src/org/modavd/ime/HostProtocol.java` 是客体侧的编解码，`ime/test/ProtocolTest.java` 按本文手写了期望字节。

## 两端各做什么

- **客体**（本仓库的输入法）：把当前输入焦点（是不是可编辑文本框、`inputType`、`imeOptions`）和光标在屏幕上的位置告诉宿主；
  把宿主发来的文字和编辑键经 `InputConnection` 交给当前编辑框。
- **宿主**（模拟器）：客体报告焦点在文本框上时，模拟器窗口进入「文字模式」，让宿主系统的输入法在窗口里工作，
  打出的文字、组字和编辑键经这条通道发给客体；不在文字模式时，键盘、鼠标、快捷键和以前完全一样。
  模拟器控制台的 `ime` 命令也走这条通道，供自动化使用（见文末）。

## 连接

- 通道是 goldfish pipe 服务 `modavd_ime`。
- 客体打开 `/dev/goldfish_pipe_dprctd`（读写），先写入 ASCII `pipe:modavd_ime` 再加 1 个 `\0` 字节，之后在同一个 fd 上双向收发帧。
  - 在 Android 14（API 34）google_apis x86_64 镜像上，这个设备节点是 `crw-rw-rw- system system u:object_r:qemu_device:s0`，普通应用可以打开，不需要任何权限。
  - 服务名和第一帧分两次 `write`：宿主的连接器只读到 `\0` 为止。
- 连上后客体发的第一帧必须是 `V`（握手），宿主回一个 `V`。
- 同一时刻只有最新的一条连接有效：新连接会让宿主关掉旧连接。
- 这条通道不进快照：载入快照会断开它，客体照常重连。
- 没有这个服务的模拟器（0.35.0 以前）上打开或写服务名会失败，客体按退避重连，见下文「客体侧约定」。

## 帧格式

```
+------------------+-----------------------------+
| 长度 N (u32, LE) | 负载 (N 字节)                |
+------------------+-----------------------------+
                   | 类型 (1 字节 ASCII 字母) | 字段 ... |
```

- 帧 = 4 字节小端无符号长度 N（只算负载，1 ≤ N ≤ 65536）+ N 字节负载。
- 负载第 1 字节是类型（ASCII 字母），其余按类型解析。
- 整数一律小端：u8 / u32 无符号，i32 有符号（补码）。
- 文本一律 UTF-8，不带结尾 `\0`，占满负载剩余部分。
- 任一方收到长度越界、类型未知或字段不足的帧：断开这条连接（宿主关管道；客体关 fd 后按退避重连），不崩溃、不越界读写。
- 本仓库的客体实现对固定长度字段之后多出的字节一律忽略（`V`、`E`、`K`、`D`）。

## 客体 → 宿主

| 类型 | 字段 | 含义 |
| --- | --- | --- |
| `V` | u32 版本 = 1 | 握手。连上后第一帧必须是它。 |
| `F` | u8 active、u32 inputType、u32 imeOptions | 输入焦点。active = 1 表示当前焦点是可编辑文本框；inputType、imeOptions 是 `EditorInfo` 的原值。 |
| `R` | i32 left、i32 top、i32 right、i32 bottom | 光标位置，单位是客体 0 号显示屏的物理像素（屏幕坐标，已乘过 `CursorAnchorInfo` 的 matrix）。拿不到就不发。 |

- `F`：每次 `onStartInput` / `onFinishInput` 都发；重连成功后立即补发一次当前状态。
- `F`、`R` 只由客体发；宿主发来的 `F`、`R` 对客体来说是未知类型。

## 宿主 → 客体

| 类型 | 字段 | 客体的动作 |
| --- | --- | --- |
| `V` | u32 版本 | 握手回应（v1 宿主回 1）。本仓库的客体把版本 0 当协议错误。 |
| `C` | 文本 | 上屏：`commitText(text, 1)`。有组字区时替换组字区，这正是 Android 的语义。 |
| `P` | i32 光标、文本 | 组字：`setComposingText(text, 1)`；文本为空 = 撤销组字。光标是组字串里的 UTF-16 下标，-1 = 末尾；v1 客体可以忽略，一律按末尾。 |
| `E` | 无 | 结束组字：`finishComposingText()`。 |
| `K` | u32 keycode、u32 metaState、u8 action | 按键：`InputConnection.sendKeyEvent`。keycode 是 Android `KeyEvent.KEYCODE_*`，metaState 是 `KeyEvent.META_*` 位；action 0 = 按下，1 = 抬起，2 = 按下再抬起（同一个 downTime）。 |
| `D` | i32 before、i32 after | 删除光标前后的文字（UTF-16 单位）：`deleteSurroundingText(before, after)`。 |

- `K` 构造的 `KeyEvent`：source = `InputDevice.SOURCE_KEYBOARD`，deviceId = `KeyCharacterMap.VIRTUAL_KEYBOARD`，
  flags = `FLAG_SOFT_KEYBOARD | FLAG_KEEP_TOUCH_MODE`。
- `K` 的 action 不是 0、1、2 时，本仓库的客体忽略这一帧（不断开）；`D` 的 before 或 after 为负时同样忽略。

## 示例（十六进制）

| 帧 | 字节 |
| --- | --- |
| 服务名 | `70 69 70 65 3a 6d 6f 64 61 76 64 5f 69 6d 65 00` |
| 客体 `V` 1 | `05 00 00 00 56 01 00 00 00` |
| 客体 `F` active=1, inputType=0x00020001, imeOptions=0x12000006 | `0a 00 00 00 46 01 01 00 02 00 06 00 00 12` |
| 客体 `R` (-1, 2, 0x01020304, -2147483648) | `11 00 00 00 52 ff ff ff ff 02 00 00 00 04 03 02 01 00 00 00 80` |
| 宿主 `C` "你好a" | `08 00 00 00 43 e4 bd a0 e5 a5 bd 61` |
| 宿主 `P` 光标 -1, "ab" | `07 00 00 00 50 ff ff ff ff 61 62` |
| 宿主 `K` KEYCODE_A(29), META_CTRL_ON\|META_SHIFT_ON(0x1001), action 2 | `0a 00 00 00 4b 1d 00 00 00 01 10 00 00 02` |
| 宿主 `D` before=2, after=3 | `09 00 00 00 44 02 00 00 00 03 00 00 00` |

更多正反例（越界长度、字段不足、未知类型）见 `ime/test/ProtocolTest.java`。

## 扩展规则

以后扩展只加新类型，不改已有类型的格式。

## 客体侧约定

本仓库的输入法照下面做；另写客体实现时也建议照做。

- **不显示任何输入界面**：`onEvaluateInputViewShown()`、`onEvaluateFullscreenMode()` 都返回 false。
- **不覆写 `onKeyDown` / `onKeyUp` / `onKeyMultiple` / `onKeyLongPress`**：物理按键和 `adb shell input` 注入照旧直达应用，游戏和自动化都靠这个。
- **连接线程**：后台守护线程循环：打开设备、写服务名、发 `V`，之后阻塞读帧。失败或断开就关 fd，按 1 s、2 s、4 s…封顶 30 s 退避重连；
  连上后立刻补发当前 `F`（以及有的话 `R`）。服务销毁时停线程。
  宿主没有这个服务（旧模拟器）时不崩、不忙等，日志不刷屏。
- **写**：单独的单线程执行器串行写，主线程不阻塞。没连上时直接丢掉，连上后靠补发同步最新状态。
- **读**：读到的宿主帧切回主线程，对 `getCurrentInputConnection()` 执行；为 null 就丢弃并记一行日志。
- **焦点**：`onStartInput(EditorInfo, restarting)` 里 active = `(inputType & TYPE_MASK_CLASS) != TYPE_NULL`，发 `F`；
  active 时调 `ic.requestCursorUpdates(CURSOR_UPDATE_IMMEDIATE | CURSOR_UPDATE_MONITOR)`。`onFinishInput` 发 `F` active=0。
- **光标**：`onUpdateCursorAnchorInfo` 里优先用组字区最后一个字符的边界框，否则用 insertion marker（horizontal、top、bottom，宽 1），
  用 `info.getMatrix()` 映射成屏幕坐标后发 `R`，和上次相同就不发。换了编辑框，旧的光标位置作废。
- **日志**：正常运行不刷屏，每次按键不记；收发帧只在打开调试日志时记。不记 `C` / `P` 的文本内容（可能是密码），只记长度。

## 宿主侧行为（参考）

以 ModAVD 模拟器的实现为准，这里只列客体需要知道的部分。

- **文字模式**：客体已连上、且最近一条 `F` 是 active=1 时进入；`F` active=0 或管道断开时退出，退出时丢掉没完成的组字。
- 文字模式下，窗口里的按键：
  1. 带可打印文字的按键（含 AltGr 组合、死键合成后的字符）→ `C`；
  2. 编辑键 → `K` action=2：Backspace→`KEYCODE_DEL`(67)、Delete→`KEYCODE_FORWARD_DEL`(112)、Enter→`KEYCODE_ENTER`(66)、
     Tab→`KEYCODE_TAB`(61)、方向键→`KEYCODE_DPAD_LEFT/UP/RIGHT/DOWN`(21/19/22/20)、Home→`KEYCODE_MOVE_HOME`(122)、
     End→`KEYCODE_MOVE_END`(123)、PageUp→`KEYCODE_PAGE_UP`(92)、PageDown→`KEYCODE_PAGE_DOWN`(93)；
     Ctrl（可带 Shift、不带 Alt）+ 字母 A–Z → `KEYCODE_A`(29)…`KEYCODE_Z`(54)。metaState 按当时的 Shift / Ctrl / Alt 填；
  3. 其余按键（Esc、F1–F12、单按修饰键、Alt+字母等）照旧走原始按键路径，客体通过普通的按键事件收到。
- 宿主输入法的组字：组字串 → `P`（空串表示撤销），确认的文字 → `C`，组字结束 → `E`。
- 宿主用 `inputType` 给宿主输入法提示：密码类关掉联想、数字类优先数字；用 `R` 决定宿主输入法候选窗的位置，还没收到 `R` 时放在显示区左下角。

## 控制台命令 `ime`

模拟器控制台（`adb -s emulator-<端口> emu <命令>` 或 telnet 到控制台端口）上的 `ime` 命令供自动化测试，也能用来打任意语言：

| 命令 | 作用 |
| --- | --- |
| `ime status` | 打印 connected、version、textmode、inputType、imeOptions、cursor（没有就是 none）等状态 |
| `ime commit <文本>` | 发 `C`。`<文本>` 是子命令后第一个空格之后的整行剩余部分（原样 UTF-8，含空格） |
| `ime preedit <文本>` | 发 `P`，光标 -1；文本取法同上，空文本撤销组字 |
| `ime finish` | 发 `E` |
| `ime key <keycode> [metaState]` | 发 `K` action=2 |
| `ime delete <before> <after>` | 发 `D` |

`commit`、`preedit` 的文本超过一帧放得下的长度时，宿主分成几帧发，在 UTF-8 字符之间切开。
客体输入法没连上时，这些命令返回 `KO` 和原因。例：`adb -s emulator-5554 emu ime commit 你好`。
