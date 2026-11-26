# Host IME channel protocol v1

[简体中文](protocol-v1.md) | **English**

The channel between the ModAVD emulator (from 0.35.0) and the input method `org.modavd.ime` in the guest. Both sides must agree byte for byte.
In this repository, `ime/src/org/modavd/ime/HostProtocol.java` is the guest-side encoder and decoder, and `ime/test/ProtocolTest.java` has the expected bytes written by hand from this document.

## What each side does

- **Guest** (the input method in this repository): tells the host the current input focus (whether it is an editable text field, `inputType`, `imeOptions`) and where the cursor is on the screen;
  hands the text and editing keys sent by the host to the current edit field through `InputConnection`.
- **Host** (the emulator): when the guest reports that the focus is on a text field, the emulator window enters "text mode" so the host system's IME works in the window.
  The typed text, the composing text and the editing keys are sent to the guest through this channel. Outside text mode, the keyboard, mouse and shortcuts work exactly as before.
  The `ime` command of the emulator console also uses this channel, for automation (see the end of this document).

## Connection

- The channel is the goldfish pipe service `modavd_ime`.
- The guest opens `/dev/goldfish_pipe_dprctd` (read and write), writes the ASCII string `pipe:modavd_ime` followed by one `\0` byte, and then sends and receives frames both ways on the same fd.
  - On the Android 14 (API 34) google_apis x86_64 image, this device node is `crw-rw-rw- system system u:object_r:qemu_device:s0`. An ordinary app can open it and needs no permission.
  - The service name and the first frame are sent in two separate `write` calls: the host's connector reads only up to the `\0`.
- The first frame the guest sends after connecting must be `V` (handshake). The host replies with a `V`.
- Only the newest connection is valid at any time: a new connection makes the host close the old one.
- The channel is not part of snapshots: loading a snapshot drops it, and the guest reconnects as usual.
- On an emulator without this service (before 0.35.0), opening the device or writing the service name fails, and the guest reconnects with backoff. See "Guest-side conventions" below.

## Frame format

```
+--------------------+-----------------------------+
| length N (u32, LE) | payload (N bytes)           |
+--------------------+-----------------------------+
                     | type (1 ASCII letter) | fields ... |
```

- A frame is a 4-byte little-endian unsigned length N (counting only the payload, 1 ≤ N ≤ 65536) followed by N bytes of payload.
- The first byte of the payload is the type (an ASCII letter). The rest is parsed according to the type.
- All integers are little-endian: u8 and u32 are unsigned, i32 is signed (two's complement).
- Text is always UTF-8, has no trailing `\0`, and fills the rest of the payload.
- If either side receives a frame with a length out of range, an unknown type or missing fields, it drops this connection (the host closes the pipe; the guest closes the fd and reconnects with backoff). It must not crash or read or write out of bounds.
- The guest implementation in this repository ignores any extra bytes after the fixed-length fields (`V`, `E`, `K`, `D`).

## Guest → host

| Type | Fields | Meaning |
| --- | --- | --- |
| `V` | u32 version = 1 | Handshake. It must be the first frame after connecting. |
| `F` | u8 active, u32 inputType, u32 imeOptions | Input focus. active = 1 means the current focus is an editable text field. inputType and imeOptions are the raw values of `EditorInfo`. |
| `R` | i32 left, i32 top, i32 right, i32 bottom | Cursor position, in physical pixels of the guest's display 0 (screen coordinates, already multiplied by the matrix of `CursorAnchorInfo`). Not sent when it is not available. |

- `F`: sent on every `onStartInput` / `onFinishInput`. After a successful reconnect, the current state is sent again right away.
- `F` and `R` are sent only by the guest. An `F` or `R` sent by the host is an unknown type to the guest.

## Host → guest

| Type | Fields | Guest action |
| --- | --- | --- |
| `V` | u32 version | Handshake reply (a v1 host replies with 1). The guest in this repository treats version 0 as a protocol error. |
| `C` | text | Commit: `commitText(text, 1)`. If there is a composing region, it is replaced. This is the Android semantics. |
| `P` | i32 cursor, text | Composing: `setComposingText(text, 1)`. Empty text = cancel composing. The cursor is a UTF-16 index in the composing string, -1 = the end. A v1 guest may ignore it and always use the end. |
| `E` | none | End composing: `finishComposingText()`. |
| `K` | u32 keycode, u32 metaState, u8 action | Key: `InputConnection.sendKeyEvent`. keycode is the Android `KeyEvent.KEYCODE_*`, metaState holds `KeyEvent.META_*` bits. action 0 = down, 1 = up, 2 = down then up (with the same downTime). |
| `D` | i32 before, i32 after | Delete text before and after the cursor (in UTF-16 units): `deleteSurroundingText(before, after)`. |

- The `KeyEvent` built for `K` has source = `InputDevice.SOURCE_KEYBOARD`, deviceId = `KeyCharacterMap.VIRTUAL_KEYBOARD`,
  and flags = `FLAG_SOFT_KEYBOARD | FLAG_KEEP_TOUCH_MODE`.
- When the action of `K` is not 0, 1 or 2, the guest in this repository ignores the frame (it does not disconnect). It does the same when before or after of `D` is negative.

## Examples (hex)

| Frame | Bytes |
| --- | --- |
| Service name | `70 69 70 65 3a 6d 6f 64 61 76 64 5f 69 6d 65 00` |
| Guest `V` 1 | `05 00 00 00 56 01 00 00 00` |
| Guest `F` active=1, inputType=0x00020001, imeOptions=0x12000006 | `0a 00 00 00 46 01 01 00 02 00 06 00 00 12` |
| Guest `R` (-1, 2, 0x01020304, -2147483648) | `11 00 00 00 52 ff ff ff ff 02 00 00 00 04 03 02 01 00 00 00 80` |
| Host `C` "你好a" | `08 00 00 00 43 e4 bd a0 e5 a5 bd 61` |
| Host `P` cursor -1, "ab" | `07 00 00 00 50 ff ff ff ff 61 62` |
| Host `K` KEYCODE_A(29), META_CTRL_ON\|META_SHIFT_ON(0x1001), action 2 | `0a 00 00 00 4b 1d 00 00 00 01 10 00 00 02` |
| Host `D` before=2, after=3 | `09 00 00 00 44 02 00 00 00 03 00 00 00` |

For more positive and negative cases (length out of range, missing fields, unknown type), see `ime/test/ProtocolTest.java`.

## Extension rules

Later versions only add new types and never change the format of existing ones.

## Guest-side conventions

The input method in this repository follows these rules. If you write another guest implementation, we suggest you follow them too.

- **It shows no input UI**: `onEvaluateInputViewShown()` and `onEvaluateFullscreenMode()` both return false.
- **It does not override `onKeyDown` / `onKeyUp` / `onKeyMultiple` / `onKeyLongPress`**: physical keys and keys injected by `adb shell input` still go straight to the app. Games and automation rely on this.
- **Connection thread**: a background daemon thread loops: open the device, write the service name, send `V`, then block reading frames. On failure or disconnect it closes the fd and reconnects with backoff at 1 s, 2 s, 4 s... capped at 30 s.
  After connecting, it sends the current `F` right away (and `R` if there is one). It stops the thread when the service is destroyed.
  When the host has no such service (an old emulator), it does not crash or busy-wait, and it does not flood the log.
- **Writing**: a separate single-thread executor writes in order, so the main thread never blocks. When not connected, writes are dropped, and the resend after connecting brings the host up to date.
- **Reading**: a frame read from the host is switched to the main thread and run on `getCurrentInputConnection()`. If it is null, the frame is dropped and one log line is written.
- **Focus**: in `onStartInput(EditorInfo, restarting)`, active = `(inputType & TYPE_MASK_CLASS) != TYPE_NULL`, and it sends `F`.
  When active, it calls `ic.requestCursorUpdates(CURSOR_UPDATE_IMMEDIATE | CURSOR_UPDATE_MONITOR)`. `onFinishInput` sends `F` with active=0.
- **Cursor**: in `onUpdateCursorAnchorInfo`, it prefers the bounding box of the last character of the composing region, and otherwise uses the insertion marker (horizontal, top, bottom, width 1).
  It maps this to screen coordinates with `info.getMatrix()` and sends `R`, and does not send it when it is the same as the last one. When the edit field changes, the old cursor position is discarded.
- **Log**: normal operation does not flood the log, and each key press is not logged. Frames sent and received are logged only when debug logging is on. The text of `C` / `P` is not logged (it may be a password), only its length.

## Host-side behavior (reference)

The ModAVD emulator's implementation is the reference. Only the parts the guest needs to know are listed here.

- **Text mode**: entered when the guest is connected and the latest `F` has active=1. Left when `F` has active=0 or the pipe drops. When it is left, any unfinished composing is discarded.
- In text mode, the keys in the window are handled like this:
  1. Keys that produce printable text (including AltGr combinations and characters composed from dead keys) → `C`;
  2. Editing keys → `K` action=2: Backspace→`KEYCODE_DEL`(67), Delete→`KEYCODE_FORWARD_DEL`(112), Enter→`KEYCODE_ENTER`(66),
     Tab→`KEYCODE_TAB`(61), arrow keys→`KEYCODE_DPAD_LEFT/UP/RIGHT/DOWN`(21/19/22/20), Home→`KEYCODE_MOVE_HOME`(122),
     End→`KEYCODE_MOVE_END`(123), PageUp→`KEYCODE_PAGE_UP`(92), PageDown→`KEYCODE_PAGE_DOWN`(93);
     Ctrl (with Shift allowed, without Alt) + letter A–Z → `KEYCODE_A`(29)…`KEYCODE_Z`(54). metaState is filled from the Shift / Ctrl / Alt state at that moment;
  3. All other keys (Esc, F1–F12, a modifier key pressed alone, Alt+letter and so on) still take the original key path, and the guest receives them as ordinary key events.
- Composing of the host IME: the composing string → `P` (an empty string means cancel), the confirmed text → `C`, and the end of composing → `E`.
- The host uses `inputType` to give hints to the host IME: for password types it turns off suggestions, and for number types it prefers digits. It uses `R` to place the candidate window of the host IME. Before any `R` is received, it puts the window at the bottom left of the display area.

## Console command `ime`

The `ime` command of the emulator console (`adb -s emulator-<port> emu <command>`, or telnet to the console port) is for automated tests. You can also use it to type in any language:

| Command | Effect |
| --- | --- |
| `ime status` | Prints the state: connected, version, textmode, inputType, imeOptions, cursor (none if there is no cursor) and so on |
| `ime commit <text>` | Sends `C`. `<text>` is the whole rest of the line after the first space following the subcommand (raw UTF-8, spaces included) |
| `ime preedit <text>` | Sends `P` with cursor -1. The text is taken the same way, and empty text cancels composing |
| `ime finish` | Sends `E` |
| `ime key <keycode> [metaState]` | Sends `K` with action=2 |
| `ime delete <before> <after>` | Sends `D` |

When the text of `commit` or `preedit` is longer than one frame can hold, the host splits it into several frames, cutting between UTF-8 characters.
When the guest IME is not connected, these commands return `KO` with a reason. Example: `adb -s emulator-5554 emu ime commit 你好`.
