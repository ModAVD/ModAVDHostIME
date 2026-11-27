# ModAVDHostIME 维护规矩

通用参与、分支、提交、隐私和发版规矩见 ModAVD 主仓库的 [AGENTS.md](https://github.com/ModAVD/ModAVD/blob/main/AGENTS.md)。本文件只补充宿主输入法仓库自己的约定。

## 目录

- `ime/` 是正式输入法和协议测试，`testapp/` 是测试 APK，`docs/` 是协议说明。
- `build.ps1` 负责构建，`scripts/` 保存发布和校验脚本，`release/` 保存发布说明。

## 测试

Java 改动运行协议测试。脚本改动至少做 PowerShell 解析检查或运行对应的校验脚本。正式签名密钥只从维护者私有路径读取，不复制到仓库或测试目录。

## 做事

正式 APK 的签名、摘要和发布流程不能改成联网下载或自动生成正式密钥。用户可见改动随 PR 写明中英文说明和验证结果。
