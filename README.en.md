# ModAVDHostIME: the ModAVD host IME (org.modavd.ime)

[简体中文](README.md) | **English**

<!-- README.md and README.en.md match section by section: if you change one, change the other in the same PR. -->

A headless input method that you install in the guest of the [ModAVD](https://github.com/ModAVD/ModAVD) emulator. It works with the emulator's goldfish pipe service `modavd_ime`:
it tells the emulator where the guest's input focus and cursor are. When the emulator window is in "text mode", the emulator sends back the text typed with a Windows IME (Chinese, AltGr combinations and dead keys all work)
and the editing keys, and the IME hands them to the current edit field through `InputConnection`. `adb emu ime commit <text>` takes the same path, so automation can type any language.

- It shows no input UI. It does **not override** `onKeyDown` / `onKeyUp` / `onKeyMultiple` / `onKeyLongPress`, so physical keys and
  `adb shell input` injection still go straight to the app as before (games and automation depend on this).
- On an old emulator without this service it neither crashes nor busy-waits: when it cannot connect, it reconnects with backoff at 1 s, 2 s, 4 s... capped at 30 s.
- It requests no permissions and stores no data. minSdk 30, targetSdk 34. License: AGPL-3.0-or-later.

Usage and development notes are on the ModAVD documentation site: [The host IME](https://modavd.github.io/en/components/ime), [Develop: the host IME](https://modavd.github.io/en/develop/ime).

## Requirements

- **ModAVD 0.35.0 or newer**: the emulator has the `modavd_ime` channel only from this version. On an older emulator, installing it has no effect (it only keeps reconnecting with backoff in the background).
- Guest Android 11 (API 30) or newer. ModAVD currently uses an Android 14 image.

## Versions

The host IME follows this repository: it is developed here, numbered on its own, and released on GitHub on its own.

- Tags are `v1.x.y`, and the APK's `versionName` is the same `1.x.y`. Only v1.0.0 is an exception: it keeps `versionName 1.0` and `versionCode 1`.
- Whenever the content of the APK changes, `versionCode` goes up by one.
- The first standalone version, [v1.0.0](https://github.com/ModAVD/ModAVDHostIME/releases/tag/v1.0.0), is byte-for-byte identical to
  `components\modavd-ime.apk` in the ModAVD 0.36.0 package.

## Distribution with ModAVD

The ModAVD release package still carries `components\modavd-ime.apk`, and ModAVD pins its SHA-256 when it releases. From ModAVD 0.36.0, the ModAVD manager handles it automatically on every boot:

- It installs the IME when it is not installed, when the installed version number (`versionCode`) is older than the one in the package, or when the version numbers are equal but the content differs from the package (the SHA-256 of `base.apk` differs). It never downgrades.
  When it cannot read the hash of the APK in the guest, equal version numbers count as already up to date.
- After a fresh install it enables the IME, and it decides only once per guest data whether to make it the default IME: it makes it the default only if the current default is still the keyboard that comes with the image (or none is set).
  After that, if you switch to another IME, it does not change it back.

If you use the ModAVD release package, you do not need to install it by hand. The APK in the package is signed with the maintainer's key; the certificate is in the next section.
To keep using a build of your own, set `versionCode` in `ime/AndroidManifest.xml` higher than the one in the release package. If it is not higher (including equal), the manager swaps the official version back in at boot.

## Releasing and verifying

The official signing is done only on the maintainer's own machine, and the official key never goes into CI. So a release here is not "push a tag and get a package automatically":

1. Merge and tag.
2. On the maintainer's machine, run `scripts/release-local.ps1`. It creates a draft Release with the attachments `modavd-ime.apk` and `SHA256SUMS.txt`.
3. Trigger the `verify-release` workflow by hand (input the tag). It proves that the APK in the draft was built from the source at the tag.
4. Publish once verification passes. After publishing, the attachments cannot be replaced, so verification must be finished before publishing. When you publish, `verify-release` runs once more as a double check.

`release-local.ps1`:

- For a regular version: it runs `git archive` on the tag to get the source, runs `build.ps1` with a throwaway development key, takes only the unsigned `out\obj\ime\aligned.apk`,
  and then signs that one file alone with the official key (`-FormalKeyStore`, `-FormalKeyAlias`, `-FormalKeyPassFile`). If the key store or the password file does not exist, it fails with an error
  and never generates a new key. The test APK is not signed with the official key.
- v1.0.0 is not re-signed. It uses the one from the 0.36.0 package as it is, and the script first checks that it matches [release/1.0.0.SHA256SUMS.txt](release/1.0.0.SHA256SUMS.txt):

  ```powershell
  pwsh -NoProfile -File .\scripts\release-local.ps1 -Tag v1.0.0 `
    -UseExistingApk <modavd-ime.apk from the 0.36.0 package> -BuildTools <SDK root>\build-tools\36.1.0
  ```

- If `ANDROID_HOME` is not set, specify build-tools with `-BuildTools` (both signing and checking the certificate need apksigner). `-WorkDir` must be a new or empty directory.
  `-DryRun` only prints the `gh release create` it would run, and does not call gh.
- `SHA256SUMS.txt` is committed to the repository ahead of time only for v1.0.0 (its APK existed before the tag was created). For later versions it is only a Release attachment and is not added to the repository:
  the hash exists only after the tag and the signing, and the attachments cannot be replaced after publishing, so the Release is the source of truth.

How to verify (`verify-release` does exactly this, and you can follow the same steps yourself): an officially signed APK cannot be byte-for-byte identical to the one CI builds, so what gets compared is the part outside the signature.

- Sign the `aligned.apk` built from the tag's source with a one-time RSA-4096 key (v1 off, v2 and v3 on, v4 off, the same as the official signing).
  The signing block of an RSA-4096 signature is 8192 bytes, the same as the official one. With any other key length the offsets would not match.
- `scripts/verify_release_apk.py` compares all bytes before the signing block on both sides, plus the central directory. It then checks the published APK with `apksigner`:
  exactly one signer, v3 verification passes, and the certificate digest is the value in the next section.
- You cannot compare against the unsigned `aligned.apk` directly: apksigner rewrites the entry alignment when it signs, so the offsets of all later entries change.

CI (`ci.yml`) runs only on pull requests and manual triggers: the protocol self-test, building both APKs on Windows, the privacy scan, and the commit author check.
The build pins Temurin 25.0.4.1+1, build-tools 36.1.0 and android-36 (it checks the SHA-256 of `android.jar`).

## Signing certificate of the official APK

- Certificate SHA-256: `d18cca3c1982149349f164d28e02490b2eb694e44d56834fa02a1062eb42e823`
- Certificate subject `CN=ModAVD`, RSA 4096.
- To verify, run `apksigner verify --print-certs <apk>`. The `certificate SHA-256 digest` in the output must match the value above.
- An APK you build yourself is signed with your own key, so its fingerprint differs from the one above. Android does not allow installing over a package signed with a different key, so uninstall before you switch
  (`adb -s emulator-<port> uninstall org.modavd.ime`) and then install. The ModAVD manager uninstalls and reinstalls automatically when the signature differs (this IME stores no data).

## Layout

| Path | Contents |
| --- | --- |
| `ime/` | The product APK: `HostImeService` (the input method service), `HostLink` (the connection thread and the write thread), `HostProtocol` (frame encoding and decoding) |
| `ime/test/ProtocolTest.java` | The protocol self-test on the host JVM, with the expected values hand-written in hex from the spec; `build.ps1` runs it before packaging, and CI runs it too |
| `testapp/` | The test APK `org.modavd.ime.testapp`, not distributed with the product |
| `docs/protocol-v1.md` | Channel protocol v1 |
| `build.ps1` | The build script (Windows, pwsh 7.2 or newer) |
| `scripts/` | The CI privacy scan and author check, and the release and verification scripts |
| `release/` | The release notes of each version; v1.0.0 also has a pre-committed `SHA256SUMS.txt` |

## Building

You need:

- Windows and PowerShell 7.2 or newer (`pwsh`);
- JDK 17 or newer (the maintainer uses Temurin 25, with `javac --release 17`): set `JAVA_HOME`, put `javac` on `PATH`, or specify it with `-JavaHome`;
- `build-tools;36.1.0` (aapt2, aapt, d8, zipalign, apksigner) and `platforms;android-36` (`android.jar`) from the Android SDK.
  You can install them with the SDK's own `sdkmanager "build-tools;36.1.0" "platforms;android-36"`. Set the environment variable `ANDROID_HOME` (or `ANDROID_SDK_ROOT`)
  to the SDK root, or specify them directly with `-BuildTools` and `-AndroidJar`.

No Gradle. In the repository root:

```powershell
pwsh -NoProfile -File .\build.ps1
```

- It first runs the protocol self-test `ProtocolTest` on the host JVM (`-SkipTest` skips it), then builds both APKs.
- The output goes to `out\`: `modavd-ime.apk`, `modavd-ime-testapp.apk`, `SHA256SUMS.txt`. Intermediate files are in `out\obj\` and are rebuilt every time. `-OutDir` changes the location.
- Signing uses the development key `out\keys\dev.p12` by default (its password is in `dev.p12.pass` in the same folder). If it does not exist, it is generated with the JDK's keytool.
  `-KeyStore` (PKCS12), `-KeyAlias` and `-KeyPassFile` let you use another key.
- If you plan to distribute your own APK: **once an APK is out, the signing key cannot change** (Android refuses to install over a package signed with a different key). Decide which key to use before you release, and back it up.
- **Never put keys into git**: `out/`, `*.p12` and `*.pass` are all in `.gitignore`.
- The code uses only APIs of API 34 and below. It compiles against the android-36 `android.jar`, which is used only at compile time and does not go into the APK.

To reproduce the historical APK byte for byte, v1.0.0 still depends on the local time zone at build time; the CI APK job uses `TZ=CET-1`. When the APK changes later,
pin the ZIP entry times in the build to remove this dependency, and also raise `versionCode` by one and set `versionName` to the new
`1.x.y` (for example `1.1.0`).

To run only the protocol self-test (works on any system with JDK 17 or newer):

```sh
javac --release 17 -encoding UTF-8 -Xlint:all -d out/prototest ime/src/org/modavd/ime/HostProtocol.java ime/test/ProtocolTest.java
java -cp out/prototest org.modavd.ime.ProtocolTest
```

## Manual install and switching

`<port>` is the emulator's console port (the name in `adb devices`, like `emulator-5554`).

```powershell
adb -s emulator-<port> install -r out\modavd-ime.apk
adb -s emulator-<port> shell ime enable org.modavd.ime/.HostImeService
adb -s emulator-<port> shell ime set org.modavd.ime/.HostImeService
# switch back to Gboard
adb -s emulator-<port> shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
```

When you use it together with AUTO-MAS, see the section "Testing on your own" of ModAVD's [CONTRIBUTING.md](https://github.com/ModAVD/ModAVD/blob/main/CONTRIBUTING.en.md#testing-on-your-own) for how to choose the adb server port.

## Logs

Tag `ModAVDIme`. Normal operation logs only create / destroy, bind, `onStartInput`, and connect / disconnect. It does not log each key press or the text typed.

```powershell
adb -s emulator-<port> logcat -s ModAVDIme
# to see every frame sent and received and every reconnect (not persistent; gone after a restart)
adb -s emulator-<port> shell setprop log.tag.ModAVDIme DEBUG
```

## Protocol

The spec is in [docs/protocol-v1.md](docs/protocol-v1.md) (in Chinese). The host side is implemented in the emulator patches of [ModAVD/ModAVD](https://github.com/ModAVD/ModAVD):
three patches under `patches/qemu/mas<N>/` (N ≥ 35) whose file names contain `modavd_ime`, `console-command-ime` and `host-input-method`
(in 0.35.0 these are 0058–0060 in `patches/qemu/mas35/`). The key points:

- The guest opens `/dev/goldfish_pipe_dprctd` and writes the ASCII string `pipe:modavd_ime` followed by one `\0`. After that, frames flow both ways on the same fd.
- A frame is a 4-byte little-endian unsigned payload length N (1 ≤ N ≤ 65536) followed by N bytes of payload. The first byte of the payload is the type letter; integers are little-endian;
  text is UTF-8, has no `\0`, and fills the rest of the payload. Length out of range, unknown type, or missing fields: disconnect and reconnect.
- Guest → host: `V` handshake (u32 version 1, the first frame after connecting), `F` focus (u8 active, u32 inputType, u32 imeOptions),
  `R` cursor (four i32, in physical pixels of the guest's display 0).
- Host → guest: `V` handshake reply, `C` commit, `P` composing (i32 cursor + text; the guest always puts the cursor at the end), `E` end composing,
  `K` key (u32 keycode, u32 metaState, u8 action 0/1/2), `D` delete (i32 before, i32 after).
- Later versions only add new types and never change the format of existing ones.

## Test APK

```text
am start -n org.modavd.ime.testapp/.MainActivity [--es focus text|password|raw] [--ez clear true]
```

It has three controls stacked vertically: an ordinary text field `text`, a password field `password`, and `raw`, which can take focus but is not a text editor. The log tag is `ImeTest`, with one JSON object per line:

- When the content, selection or composing region of the text field or the password field changes: `{"id":"text","text":"...","selStart":n,"selEnd":n,"composing":[s,e]}`
  (`null` when there is no composing text). Non-ASCII and control characters are always written as `\uXXXX` (lowercase, the same as the default of Python `json.dumps`).
  One operation of the IME logs one line, only when the batch edit ends, and a line identical to the previous one is not logged.
- A logd entry holds at most about 4 KB. When a whole line is longer than 4000 characters, `text` keeps only the beginning, and `composing` is followed by
  `"truncated":true,"textLength":n` (still valid JSON). When you test long text, run `--ez clear true` between test cases.
- `raw` logs each `onKeyDown`: `{"id":"raw","key":keycode,"meta":metaState}`. It swallows ordinary keys the way a game screen does (arrow keys and Tab
  do not move the focus to the text fields), while system keys such as back and volume are still handed to the framework as usual.
- The activity is `singleTask`. When it is already in the foreground, another `am start` goes through `onNewIntent`, and `focus` / `clear` still take effect.

The test APK is signed with the same key as the product APK. It is for testing only and is not distributed with ModAVD.

## Known limitations (v1)

- When an app calls `clearFocus()` on an edit field in touch mode (for example, a chat box collapses after you tap "Send"), Android gives the IME no callback:
  there is no `onFinishInput` and no new `onStartInput`. The last `F` the guest sent is still active=1, so the host stays in text mode
  until the next window / edit-field focus change, screen-off, or disconnect. The guest cannot fix this on its own. The two sides have to agree on a way to exit (for example Esc, or the host leaving text mode by itself
  when a mouse click lands outside the most recent `R` rectangle).
- v1 does not use the composing cursor position in `P` frames. The composing cursor is always placed at the end.

## Contributing

- There is only one maintainer, and replies may take a few days. Open an issue to discuss before you change anything, and say what you want to change and why. A big PR that was not discussed first may not be merged.
- A protocol change needs the host and the guest to change together, and it can only add new types, never change the format of existing ones. Please discuss it in an issue first.
- Put your own name or a pseudonym on your commits, and use your GitHub noreply address as the email (`<id>+<username>@users.noreply.github.com`).
- When you add or change a workflow: pin third-party actions to a 40-character commit SHA, do not use `pull_request_target`, and do not use self-hosted runners.
- The README comes in two versions, Chinese (`README.md`) and English (`README.en.md`), which match section by section. If you change one, change the other in the same PR. If you can write only one language, say so in the PR and the maintainer will fill in the other.
- The other conventions (attribution, license, AI tools, how to report security issues) are the same as ModAVD's; see ModAVD's
  [CONTRIBUTING.md](https://github.com/ModAVD/ModAVD/blob/main/CONTRIBUTING.en.md).
- For the environment, hardware, how to verify, and suggested models when you use AI tools, see [Develop: the host IME](https://modavd.github.io/en/develop/ime) on the documentation site.

## License

GNU Affero General Public License version 3 or later (AGPL-3.0-or-later), the same as AUTO-MAS; see [LICENSE](LICENSE) and [NOTICE](NOTICE). If you distribute it (modified or not), or put a modified version online for others to use, you must provide the corresponding source under the same license.
