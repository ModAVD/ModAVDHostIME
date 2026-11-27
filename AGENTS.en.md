# For AI coding tools

[简体中文](AGENTS.md) | **English**

<!-- AGENTS.md and AGENTS.en.md match section by section: if you change one, change the other in the same PR. AI tools only read AGENTS.md on their own. -->

AI tools working in this repository (Codex, Claude Code and others) read this first. The general rules for contributing, branches, commits, privacy and releases are in the [AGENTS.en.md](https://github.com/ModAVD/ModAVD/blob/main/AGENTS.en.md) of the main ModAVD repository; this file only adds what is specific to the host IME repository. The guide for people is [README.en.md](README.en.md) and ModAVD's [CONTRIBUTING.en.md](https://github.com/ModAVD/ModAVD/blob/main/CONTRIBUTING.en.md).

## Layout

- `ime/` is the product IME (`src/` is the source, `test/ProtocolTest.java` is the protocol self-test that runs on the host JVM). `testapp/` is the test APK and is not shipped with the product.
- `docs/` holds the channel protocol: `protocol-v1.md` (Chinese) and `protocol-v1.en.md` (English).
- `build.ps1` builds the APKs (Windows, PowerShell 7.2 or newer, JDK 17 or newer, and `build-tools;36.1.0` and `platforms;android-36` from the Android SDK). There is no Gradle.
- `scripts/` has the CI checks (`privacy.py`, `repo_checks.py`), the release script `release-local.ps1` and the verification script `verify_release_apk.py`. `release/` has the release notes of each version.
- `changelog.d/` holds the release note lines; see `changelog.d/README.md`.

## What to change

- `.github/`, `scripts/repo_checks.py`, `scripts/privacy.py`, `scripts/release-local.ps1`, `scripts/verify_release_apk.py` and `release/` are high-risk paths (see `.github/CODEOWNERS`). Open an issue to discuss before you change them.
- A protocol change needs the host and the guest to change together, and it can only add new types, never change the format of existing ones. Open an issue first, and change both the Chinese and the English protocol files in `docs/`.
- The signing, checksum and release flow of the official APK must not be changed to download things from the network or to generate an official key. The official signing key is read only from the maintainer's private path; do not copy it into the repository or a test directory, and it never goes into CI.
- Never put keys into git: `out/`, `*.p12` and `*.pass` are all in `.gitignore`.
- Workflows: pin third-party actions to a full 40-character commit SHA, do not use `pull_request_target`, and do not use self-hosted runners.
- For a change users can see (the behaviour of the APK changes), add a file `changelog.d/<name>.<feat|fix|change|remove>.md` in the same PR; changes that only touch development, tests, CI or documentation need none.
- Name the issue on a line of its own in the PR description, `Refs ModAVD/ModAVD#<number>`, and do not write `Fixes` or `Closes` (issues are all opened in the ModAVD repository).

## Which language to write in

- Issues, PRs, comments and commit messages: write Chinese if the person you work for writes Chinese, and English if they write English; do not mix the two in one PR. This file being in Chinese does not mean you have to write Chinese.
- Change both files of a document pair (`X.md` in Chinese, `X.en.md` in English, for example `README`, `AGENTS` and `docs/protocol-v1`). If the person you work for writes only one of the two languages, write the other one yourself from the meaning, and say in the PR which file you translated so that someone can check it.
- Take terms from the [table of terms](https://github.com/ModAVD/ModAVDManager/blob/main/internal/i18n/README.md#terms); for a word that is not in the table, use the name in the code or the original word. The product is called ModAVD in every language.
- Error messages in scripts are bilingual: `中文 / English` in one message. Identifiers in code are English.
- Comments follow the language the file already uses, and a file does not mix the two: `HostLink.java` and `HostProtocol.java` are in English; the other files stay in Chinese where they are. A new file can use Chinese or English.

## Test it yourself before you hand it in

- Java changes: run the protocol self-test first (the same as the `protocol-test` job in CI; it needs JDK 17 or newer):

  ```sh
  javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d out/prototest ime/src/org/modavd/ime/HostProtocol.java ime/test/ProtocolTest.java
  java -cp out/prototest org.modavd.ime.ProtocolTest
  ```

  To build the whole APK, run `pwsh -NoProfile -File .\build.ps1` (it needs the Android SDK; see the README).
- Script changes: for a `.ps1`, at least run a PowerShell parser check; for a `.py`, at least `py_compile`. If you changed a script in `scripts/`, run its test (`scripts/test_verify_release_apk.py`, `scripts/test_repo_checks.py`, `scripts/test_android_sdk_fallback.ps1`).
- The other CI checks also run locally: `python scripts/privacy.py . --allowlist scripts/privacy-allowlist.json` and `python scripts/repo_checks.py`.
- In the PR, state the checks you actually ran, the conditions and the results, and the known limits; say plainly what was not tested.