# ModAVDHostIME：ModAVD 宿主输入法（org.modavd.ime）

装在 [ModAVD](https://github.com/ModAVD/ModAVD) 模拟器客体里的无界面输入法，配合模拟器的 goldfish pipe 服务 `modavd_ime`：
把客体的输入焦点和光标位置告诉模拟器，模拟器窗口在「文字模式」下把 Windows 输入法打出的文字（中文、AltGr 组合键、死键都行）
和编辑键发回来，由它经 `InputConnection` 交给当前编辑框。`adb emu ime commit <文本>` 也走这条路，供自动化打任意语言。

- 不显示任何输入界面；**不覆写** `onKeyDown` / `onKeyUp` / `onKeyMultiple` / `onKeyLongPress`，物理按键和
  `adb shell input` 注入照旧直达应用（游戏和自动化都靠这个）。
- 旧模拟器没有这个服务时不崩、不忙等：连不上就按 1 s、2 s、4 s…封顶 30 s 退避重连。
- 不申请任何权限，不存任何数据。minSdk 30、targetSdk 34。许可 AGPL-3.0-or-later。

使用说明和参与开发的说明在 ModAVD 文档站：[宿主输入法](https://modavd.github.io/components/ime)、[开发：宿主输入法](https://modavd.github.io/develop/ime)。

## 要求

- **ModAVD 0.35.0 或更新**：这一版起模拟器才有 `modavd_ime` 通道。更旧的模拟器上装了也不起作用（只在后台退避重连）。
- 客体 Android 11（API 30）以上；ModAVD 现用的是 Android 14 镜像。

## 版本和独立发布

本仓库是 ModAVD 宿主输入法的公开来源和独立发布仓库。版本 tag 使用 `v1.x.y`，APK 的
`versionName` 与 tag 的数字部分相同；v1.0.0 是历史兼容例外，保持 `versionName 1.0`、
`versionCode 1`。以后 APK 内容改变时 `versionCode` 加一。

ModAVD 整包仍会带着 `components\modavd-ime.apk`，并在发布时钉住它的 SHA-256。管理器的安装规则是：
没有安装、包的 `versionCode` 更新、或版本号相同但 `base.apk` SHA-256 不同就安装；签名不同先卸载再装，
从不降级。读不出客体 APK 哈希时，同版本号视为已是最新。自己编的 APK 必须使用高于官方包的
`versionCode`，否则开机时会被官方版本换回。

第一个独立版本 [v1.0.0](https://github.com/ModAVD/ModAVDHostIME/releases/tag/v1.0.0) 与 ModAVD
0.36.0 包中的 `components\modavd-ime.apk` 逐字节相同。正式发布前由维护者在本机用正式密钥签名，
再手动触发 `verify-release` workflow；CI 用一次性 RSA-4096 密钥重签构建产物，只比较签名块以外的
条目区和中央目录，并核对发布证书。

### CI 和发布

`ci.yml` 只在 Pull Request 或手动触发时运行协议自测、Windows APK 构建、隐私扫描和作者检查。
构建使用固定的 Temurin 25.0.4.1+1、Android SDK build-tools 36.1.0 和 android-36；正式密钥不进
CI。发布流程见 [release/1.0.0.md](release/1.0.0.md)、`scripts/release-local.ps1` 和
`verify-release.yml`。用户本机创建草稿的典型命令是：

```powershell
pwsh -NoProfile -File .\scripts\release-local.ps1 -Tag v1.0.0 `
  -UseExistingApk <冻结的 modavd-ime.apk 路径>
```

首次以后的版本由 tag 源码重新构建，并由脚本生成 Release 附件 `SHA256SUMS.txt`；是否把它事后补进
仓库由维护者决定。核对发布 APK 时，使用 `verify-release` workflow 或仓库里的
`scripts/verify_release_apk.py`，不要直接拿未签名的 `aligned.apk` 比较。

ModAVD 0.36.0 起，发布包里带 `components\modavd-ime.apk`，ModAVD 管理器每次开机自动处理：

- 没装、已装的版本号（`versionCode`）比包里的旧，或版本号相同但内容和包里的不同（`base.apk` 的 SHA-256 不同）时装，不降级；
- 新装后启用，并在同一份客体数据上只决定一次是否设成默认输入法：当时默认的还是镜像自带的键盘（或没设）才设成它，
  之后你切到别的输入法就不再改回来。

用 ModAVD 的发布包就不需要手动装。包里的 APK 用维护者的密钥签名，证书见下一节。
要一直用自己编的版本，把 `ime/AndroidManifest.xml` 的 `versionCode` 设得比发布包里的高；不高于它（包括相同）的话，管理器开机时会换回官方版本。

## 官方 APK 的签名证书

- 证书 SHA-256：`d18cca3c1982149349f164d28e02490b2eb694e44d56834fa02a1062eb42e823`
- 证书主体 `CN=ModAVD`，RSA 4096。
- 核对：`apksigner verify --print-certs <apk>`，输出里的 `certificate SHA-256 digest` 要和上面的值一致。
- 自己编的 APK 用你自己的密钥签名，指纹和上面不同；安卓不允许用不同密钥签的包覆盖安装，换用前要先卸载
  （`adb -s emulator-<端口> uninstall org.modavd.ime`）再装。ModAVD 管理器遇到签名不同会自动先卸再装（这个输入法不存任何数据）。

## 目录

| 路径 | 内容 |
| --- | --- |
| `ime/` | 产品 APK：`HostImeService`（输入法服务）、`HostLink`（连接线程和写线程）、`HostProtocol`（帧编解码） |
| `ime/test/ProtocolTest.java` | 宿主 JVM 上的协议自测，期望值照规格手写成十六进制；`build.ps1` 打包前跑，CI 也跑 |
| `testapp/` | 测试 APK `org.modavd.ime.testapp`，不随产品分发 |
| `docs/protocol-v1.md` | 通道协议 v1 |
| `build.ps1` | 构建脚本（Windows，pwsh 7.2 以上） |

## 构建

需要：

- Windows 和 PowerShell 7.2 以上（`pwsh`）；
- JDK 17 以上（维护者用 Temurin 25，`javac --release 17`）：设 `JAVA_HOME`、把 `javac` 放进 `PATH`，或用 `-JavaHome` 指定；
- Android SDK 的 `build-tools;36.1.0`（aapt2、aapt、d8、zipalign、apksigner）和 `platforms;android-36`（`android.jar`），
  可以用 SDK 自带的 `sdkmanager "build-tools;36.1.0" "platforms;android-36"` 装；设环境变量 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）
  指向 SDK 根目录，或用 `-BuildTools`、`-AndroidJar` 直接指定。

不用 Gradle。在仓库根目录：

```powershell
pwsh -NoProfile -File .\build.ps1
```

- 先在宿主 JVM 上跑协议自测 `ProtocolTest`（`-SkipTest` 跳过），再编两个 APK。
- 产物在 `out\`：`modavd-ime.apk`、`modavd-ime-testapp.apk`、`SHA256SUMS.txt`；中间文件在 `out\obj\`，每次重建。`-OutDir` 可改。
- 签名默认用开发密钥 `out\keys\dev.p12`（口令在同目录 `dev.p12.pass`），不存在就用 JDK 的 keytool 生成。
  `-KeyStore`（PKCS12）、`-KeyAlias`、`-KeyPassFile` 可换成别的密钥。
- 自己打算分发 APK 的话：**APK 一旦发出去，签名密钥就不能换**（安卓拒绝用不同密钥签的包覆盖安装），发之前定好用哪把密钥并备份。
- **密钥绝不进 git**：`out/`、`*.p12`、`*.pass` 都在 `.gitignore` 里。
- 代码只用 API 34 及以下的接口；编译用 android-36 的 `android.jar`，它只在编译时用，不进 APK。

v1.0.0 为了逐字节复现历史 APK，构建时仍依赖本地时区；CI 的 APK job 使用 `TZ=CET-1`。以后改 APK
时要在构建里固定 ZIP 条目时间并去掉这个依赖，同时把 `versionCode` 加一、`versionName` 改成新的
`1.x.y`（例如 `1.1.0`）。

只跑协议自测（任何系统都行，只要 JDK 17 以上）：

```sh
javac --release 17 -encoding UTF-8 -Xlint:all -d out/prototest ime/src/org/modavd/ime/HostProtocol.java ime/test/ProtocolTest.java
java -cp out/prototest org.modavd.ime.ProtocolTest
```

## 手动安装和切换

`<端口>` 是模拟器的控制台端口（`adb devices` 里 `emulator-5554` 这样的名字）。

```powershell
adb -s emulator-<端口> install -r out\modavd-ime.apk
adb -s emulator-<端口> shell ime enable org.modavd.ime/.HostImeService
adb -s emulator-<端口> shell ime set org.modavd.ime/.HostImeService
# 切回 Gboard
adb -s emulator-<端口> shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
```

和 AUTO-MAS 一起用时，adb server 端口的选法见 ModAVD 的 [CONTRIBUTING.md](https://github.com/ModAVD/ModAVD/blob/main/CONTRIBUTING.md)「自己测试时」一节。

## 日志

tag `ModAVDIme`：正常运行只记创建 / 销毁、绑定、`onStartInput`、连上 / 断开，每次按键不记，也不记打出的文字。

```powershell
adb -s emulator-<端口> logcat -s ModAVDIme
# 要看每一帧收发和每次重连（不持久，重启就没）
adb -s emulator-<端口> shell setprop log.tag.ModAVDIme DEBUG
```

## 协议

规格见 [docs/protocol-v1.md](docs/protocol-v1.md)。宿主侧实现在 [ModAVD/ModAVD](https://github.com/ModAVD/ModAVD) 的模拟器补丁里：
`patches/qemu/mas<N>/`（N ≥ 35）中文件名带 `modavd_ime`、`console-command-ime`、`host-input-method` 的三个补丁
（0.35.0 是 `patches/qemu/mas35/` 的 0058–0060）。要点：

- 客体打开 `/dev/goldfish_pipe_dprctd`，写 ASCII `pipe:modavd_ime` 加 1 个 `\0`，之后同一个 fd 双向收发帧。
- 帧 = 4 字节小端无符号负载长度 N（1 ≤ N ≤ 65536）+ N 字节负载；负载第 1 字节是类型字母；整数小端；
  文本 UTF-8、不带 `\0`、占满负载剩余部分。长度越界、类型未知、字段不足：断开重连。
- 客体 → 宿主：`V` 握手（u32 版本 1，连上后第一帧）、`F` 焦点（u8 active、u32 inputType、u32 imeOptions）、
  `R` 光标（4 个 i32，客体 0 号屏物理像素）。
- 宿主 → 客体：`V` 握手回应、`C` 上屏、`P` 组字（i32 光标 + 文本，客体一律放末尾）、`E` 结束组字、
  `K` 按键（u32 keycode、u32 metaState、u8 action 0/1/2）、`D` 删除（i32 before、i32 after）。
- 以后只加新类型，不改已有类型的格式。

## 测试 APK

```text
am start -n org.modavd.ime.testapp/.MainActivity [--es focus text|password|raw] [--ez clear true]
```

竖排三个控件：普通文本框 `text`、密码框 `password`、能拿焦点但不是文本编辑器的 `raw`。日志 tag `ImeTest`，每行一个 JSON：

- 文本框、密码框内容 / 选区 / 组字区变化：`{"id":"text","text":"...","selStart":n,"selEnd":n,"composing":[s,e]}`
  （没组字是 `null`）。非 ASCII 和控制字符一律 `\uXXXX`（小写，和 Python `json.dumps` 默认一致）。
  输入法的一次操作只在批量编辑结束时记一行，和上一行相同不记。
- logd 一条最多约 4 KB：整行超过 4000 字符时 `text` 只留开头一段，`composing` 后面多出
  `"truncated":true,"textLength":n`（仍是合法 JSON）。测长文本时每个用例之间 `--ez clear true`。
- `raw` 每次 `onKeyDown`：`{"id":"raw","key":keycode,"meta":metaState}`。它像游戏画面一样吃掉普通按键（方向键、Tab
  不会把焦点挪到文本框上），返回、音量等系统键照旧交给框架。
- Activity 是 `singleTask`，已在前台时再 `am start` 走 `onNewIntent`，`focus` / `clear` 照样生效。

测试 APK 和产品 APK 用同一把密钥签名，只用于测试，不随 ModAVD 分发。

## 已知限制（v1）

- 应用在触摸模式下对编辑框调 `clearFocus()`（比如聊天框点「发送」后收起），安卓不给输入法任何回调：
  没有 `onFinishInput`，也没有新的 `onStartInput`。客体最后发的 `F` 仍是 active=1，宿主会一直留在文字模式，
  直到下一次窗口 / 编辑框焦点变化、熄屏或断线。客体单方面补不了，要和宿主约定退出办法（例如 Esc、或在最近一个
  `R` 矩形外点鼠标时宿主自己退出文字模式）。
- `P` 帧里的组字光标位置 v1 不用，组字光标一律放在末尾。

## 参与

- 只有一个维护者，回复可能要几天。改动前先开 issue 商量，写清要改什么、为什么；没商量过的大 PR 可能不合。
- 改协议要宿主、客体两边一起改，只能加新类型、不改已有类型的格式，请先在 issue 里讨论。
- 提交署你自己的名字或化名，邮箱用你的 GitHub noreply 地址（`<编号>+<登录名>@users.noreply.github.com`）。
- 新加或修改 workflow：第三方 action 钉到 40 位提交 SHA，不用 `pull_request_target`，不用自托管 runner。
- 其余约定（署名、许可、AI 工具、安全问题怎么报）和 ModAVD 一样，见 ModAVD 的
  [CONTRIBUTING.md](https://github.com/ModAVD/ModAVD/blob/main/CONTRIBUTING.md)。
- 环境、硬件、怎么验证和用 AI 工具时的模型建议，见文档站的[开发：宿主输入法](https://modavd.github.io/develop/ime)。

## 许可证

GNU Affero General Public License 第 3 版或更新版本（AGPL-3.0-or-later），和 AUTO-MAS 相同，见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。分发它（改没改都算），或者把改过的版本放到网上给别人用，都要按同样的许可证提供对应源码。
