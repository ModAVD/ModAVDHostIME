# 给 AI 编码工具

**简体中文** | [English](AGENTS.en.md)

<!-- AGENTS.md 和 AGENTS.en.md 逐节对应：改了其中一份，请在同一个 PR 里把另一份也改了。AI 工具自己只会读 AGENTS.md。 -->

在本仓库干活的 AI 工具（Codex、Claude Code 等）先读这一份。通用的参与、分支、提交、隐私和发版规矩见 ModAVD 主仓库的 [AGENTS.md](https://github.com/ModAVD/ModAVD/blob/main/AGENTS.md)，本文件只补充宿主输入法仓库自己的约定。给人看的说明在 [README.md](README.md) 和 ModAVD 的 [CONTRIBUTING.md](https://github.com/ModAVD/ModAVD/blob/main/CONTRIBUTING.md)。

## 目录

- `ime/` 是正式输入法（`src/` 是源码，`test/ProtocolTest.java` 是宿主 JVM 上的协议自测），`testapp/` 是测试 APK，不随产品发。
- `docs/` 是通道协议说明：`protocol-v1.md`（中文）和 `protocol-v1.en.md`（英文）。
- `build.ps1` 负责构建（Windows、PowerShell 7.2 以上、JDK 17 以上、Android SDK 的 `build-tools;36.1.0` 和 `platforms;android-36`），不用 Gradle。
- `scripts/` 是 CI 检查（`privacy.py`、`repo_checks.py`）、发布脚本 `release-local.ps1` 和校验脚本 `verify_release_apk.py`；`release/` 是各版本的发布说明。
- `changelog.d/` 放更新说明，写法见 `changelog.d/README.md`。

## 改哪里

- `.github/`、`scripts/repo_checks.py`、`scripts/privacy.py`、`scripts/release-local.ps1`、`scripts/verify_release_apk.py`、`release/` 是高风险路径（见 `.github/CODEOWNERS`），动之前先开 issue 商量。
- 协议改动要宿主和客体一起改，只能加新类型，不能改已有类型的格式：先开 issue 商量，并同步改 `docs/` 里的中英两份协议。
- 正式 APK 的签名、摘要和发布流程不能改成联网下载或自动生成正式密钥。正式签名密钥只从维护者私有路径读取，不复制到仓库或测试目录，也不进 CI。
- 不把密钥放进 git：`out/`、`*.p12`、`*.pass` 都在 `.gitignore` 里。
- workflow：第三方 action 钉到 40 位提交 SHA，不用 `pull_request_target`，不用自托管 runner。
- 用户看得见的改动（APK 的行为变了），同一个 PR 里加一个 `changelog.d/<名字>.<feat|fix|change|remove>.md`；只动开发、测试、CI、文档的不加。
- 对应的 issue 在 PR 正文里单独写一行 `Refs ModAVD/ModAVD#<编号>`，不写 `Fixes`、`Closes`（issue 都开在 ModAVD 仓库）。

## 用什么语言写

- issue、PR、评论、提交说明：让你干活的人用中文就写中文，用英文就写英文，一个 PR 里不混着写。这份文件是中文的，不等于你要写中文。
- 成对的文档（`X.md` 中文、`X.en.md` 英文，比如 `README`、`AGENTS`、`docs/protocol-v1`）两份都要改。让你干活的人只会其中一种时，另一种由你照意思写出来，并在 PR 里写明哪一份是你翻的、请人核对。
- 术语照[术语对照表](https://github.com/ModAVD/ModAVDManager/blob/main/internal/i18n/README.md#terms)写；表里没有的词用代码里的名字或原文。产品名在哪种语言里都写 ModAVD。
- 脚本报错写成中英各一半：`中文 / English`，一条消息里两种都有。代码里的标识符用英文。
- 注释跟着所在文件原有的语言，一个文件里不混用：`HostLink.java`、`HostProtocol.java` 是英文的，其余文件原来是中文就保持中文；新文件用中文或英文都可以。

## 交之前自己测

- Java 改动：先跑协议自测（和 CI 的 `protocol-test` 一样，要 JDK 17 以上）：

  ```sh
  javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d out/prototest ime/src/org/modavd/ime/HostProtocol.java ime/test/ProtocolTest.java
  java -cp out/prototest org.modavd.ime.ProtocolTest
  ```

  要编整个 APK 就跑 `pwsh -NoProfile -File .\build.ps1`（需要 Android SDK，细则见 README）。
- 脚本改动：`.ps1` 至少做 PowerShell 解析检查，`.py` 至少 `py_compile`；改了 `scripts/` 里的校验脚本，就跑对应的测试（`scripts/test_verify_release_apk.py`、`scripts/test_repo_checks.py`、`scripts/test_android_sdk_fallback.ps1`）。
- 其余 CI 检查本地也能跑：`python scripts/privacy.py . --allowlist scripts/privacy-allowlist.json`、`python scripts/repo_checks.py`。
- PR 里写明实际运行过的检查、条件和结果，以及已知限制；没测的照实写没测。