# ModAVDHostIME

ModAVDHostIME is the headless input method installed in the Android guest of [ModAVD](https://github.com/ModAVD/ModAVD). It connects to the emulator's `modavd_ime` goldfish-pipe service and sends host text, composition and editing keys through `InputConnection`.

- It has no input UI and does not override ordinary key callbacks, so physical keys and `adb shell input` keep their existing behavior.
- It requests no permissions and stores no data. The minimum Android API is 30 and the target API is 34.
- ModAVD 0.35.0 or newer is required for the guest channel.

## Build

Builds use PowerShell 7.2+, JDK 17+, Android build-tools 36.1.0 and `platforms;android-36`.

```powershell
pwsh -NoProfile -File .\build.ps1
```

The build runs the JVM protocol test first and writes `modavd-ime.apk`, the test APK and `SHA256SUMS.txt` under `out\`. Development signing keys stay under `out\` and must never be committed. The protocol-only test also runs on any host with JDK 17 or newer.

The protocol specification is in [docs/protocol-v1.md](docs/protocol-v1.md). The Chinese guide has the complete installation, logging and test-app details.

## License

GNU Affero General Public License version 3 or later. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
