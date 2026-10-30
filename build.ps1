#requires -Version 7.2
# Copyright 2026 qiyinxi
# SPDX-License-Identifier: AGPL-3.0-or-later (https://www.gnu.org/licenses/agpl-3.0.html)
#
# 编「宿主输入法」APK（ime\）和测试 APK（testapp\）。工具链：Android SDK 的 build-tools 36.1.0（aapt2、aapt、d8、
# zipalign、apksigner）和 platforms\android-36\android.jar，JDK 17 以上（javac --release 17），不用 Gradle。
# -BuildTools、-AndroidJar 不给时从环境变量 ANDROID_HOME（没有再看 ANDROID_SDK_ROOT）指的 SDK 根目录里找。
#
#   pwsh -NoProfile -File .\build.ps1
#
# 产物：<OutDir>\modavd-ime.apk、<OutDir>\modavd-ime-testapp.apk、<OutDir>\SHA256SUMS.txt；中间文件在 <OutDir>\obj\
# （每次重建）。OutDir 默认是仓库里的 out\。打包前先在宿主 JVM 上跑协议自测 ProtocolTest（-SkipTest 跳过）。
# 签名：-KeyStore（PKCS12，别名 -KeyAlias），口令从 -KeyPassFile 读，默认 <KeyStore>.pass。KeyStore 默认是开发密钥
# out\keys\dev.p12，密钥库不存在时用 JDK 的 keytool 生成，口令随机写进口令文件。out\ 和 *.p12、*.pass 都在
# .gitignore 里：密钥和产物绝不进 git。
[CmdletBinding()]
param(
    [string]$OutDir = (Join-Path $PSScriptRoot 'out'),
    [string]$KeyStore = (Join-Path $PSScriptRoot 'out\keys\dev.p12'),
    [string]$KeyAlias = 'modavd-dev',
    [string]$KeyPassFile,
    # 默认 <SDK 根>\build-tools\36.1.0
    [string]$BuildTools,
    # 默认 <SDK 根>\platforms\android-36\android.jar
    [string]$AndroidJar,
    # 默认用 JAVA_HOME，没有再找 PATH 上的 javac
    [string]$JavaHome,
    [switch]$SkipTest
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
[cultureinfo]::CurrentCulture = [cultureinfo]::InvariantCulture

# 参数用数组传：-encoding、-storepass:file 这类写法原样交给外部程序；输出直接显示，不混进函数返回值
function Invoke-Tool([string]$Exe, [string[]]$ArgList) {
    & $Exe @ArgList | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($Exe)) 失败（退出码 $LASTEXITCODE）" }
}

function Find-File([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "找不到 $Path" }
    return $Path
}

# ---- 工具链 ----
if (-not $BuildTools -or -not $AndroidJar) {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else {
        throw '找不到 Android SDK：设环境变量 ANDROID_HOME（或 ANDROID_SDK_ROOT）指向 SDK 根目录，或用 -BuildTools、-AndroidJar 直接指定'
    }
    $missing = @()
    if (-not $BuildTools) {
        $BuildTools = Join-Path $sdkRoot 'build-tools\36.1.0'
        if (-not (Test-Path -LiteralPath $BuildTools -PathType Container)) { $missing += '"build-tools;36.1.0"' }
    }
    if (-not $AndroidJar) {
        $AndroidJar = Join-Path $sdkRoot 'platforms\android-36\android.jar'
        if (-not (Test-Path -LiteralPath $AndroidJar -PathType Leaf)) { $missing += '"platforms;android-36"' }
    }
    if ($missing) { throw "SDK $sdkRoot 里缺 $($missing -join '、')：用 sdkmanager $($missing -join ' ') 装上，或用 -BuildTools、-AndroidJar 指定" }
}
$aapt2 = Find-File (Join-Path $BuildTools 'aapt2.exe')
$aapt = Find-File (Join-Path $BuildTools 'aapt.exe')
$d8 = Find-File (Join-Path $BuildTools 'd8.bat')
$zipalign = Find-File (Join-Path $BuildTools 'zipalign.exe')
$apksigner = Find-File (Join-Path $BuildTools 'apksigner.bat')
$null = Find-File $AndroidJar
if (-not $JavaHome) {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $JavaHome = $env:JAVA_HOME
    } else {
        $javacCmd = Get-Command javac.exe -ErrorAction SilentlyContinue
        if (-not $javacCmd) { throw '找不到 JDK：设 JAVA_HOME、把 javac 放进 PATH，或用 -JavaHome 指定' }
        $JavaHome = Split-Path (Split-Path $javacCmd.Source)
    }
}
$javac = Find-File (Join-Path $JavaHome 'bin\javac.exe')
$java = Find-File (Join-Path $JavaHome 'bin\java.exe')
$keytool = Find-File (Join-Path $JavaHome 'bin\keytool.exe')
$javacVersion = (& $javac -version 2>&1 | Out-String).Trim()
if ($javacVersion -notmatch '^javac (\d+)' -or [int]$Matches[1] -lt 17) { throw "需要 JDK 17 以上，现在是：$javacVersion" }
# d8.bat、apksigner.bat 靠 JAVA_HOME 找 java
$env:JAVA_HOME = $JavaHome

$OutDir = [IO.Path]::GetFullPath($OutDir)
$objRoot = Join-Path $OutDir 'obj'
$null = New-Item -ItemType Directory -Force $objRoot

# ---- 签名密钥 ----
$KeyStore = [IO.Path]::GetFullPath($KeyStore)
if (-not $KeyPassFile) { $KeyPassFile = "$KeyStore.pass" }
$KeyPassFile = [IO.Path]::GetFullPath($KeyPassFile)
if (-not (Test-Path -LiteralPath $KeyStore)) {
    $null = New-Item -ItemType Directory -Force (Split-Path $KeyStore)
    if (-not (Test-Path -LiteralPath $KeyPassFile)) {
        $pass = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(16)).ToLowerInvariant()
        Set-Content -LiteralPath $KeyPassFile -Value $pass -NoNewline -Encoding ascii
    }
    Invoke-Tool $keytool @('-genkeypair', '-noprompt', '-keystore', $KeyStore, '-storetype', 'PKCS12',
        '-alias', $KeyAlias, '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
        '-dname', 'CN=ModAVD IME Dev, O=qiyinxi', '-storepass:file', $KeyPassFile, '-keypass:file', $KeyPassFile)
    Write-Host "已生成开发签名密钥：$KeyStore（口令在 $KeyPassFile）"
} elseif (-not (Test-Path -LiteralPath $KeyPassFile)) {
    throw "找不到密钥库口令文件 $KeyPassFile（用 -KeyPassFile 指定）"
}

# ---- 协议自测（宿主 JVM） ----
if (-not $SkipTest) {
    $testDir = Join-Path $objRoot 'prototest'
    if (Test-Path -LiteralPath $testDir) { Remove-Item -LiteralPath $testDir -Recurse -Force }
    Invoke-Tool $javac @('--release', '17', '-encoding', 'UTF-8', '-Xlint:all', '-d', $testDir,
        (Join-Path $PSScriptRoot 'ime\src\org\modavd\ime\HostProtocol.java'),
        (Join-Path $PSScriptRoot 'ime\test\ProtocolTest.java'))
    Invoke-Tool $java @('-cp', $testDir, 'org.modavd.ime.ProtocolTest')
}

# ---- 编一个 APK，返回签好的路径 ----
function Build-Apk([string]$Name, [string]$ApkName) {
    $src = Join-Path $PSScriptRoot $Name
    $obj = Join-Path $objRoot $Name
    if (Test-Path -LiteralPath $obj) { Remove-Item -LiteralPath $obj -Recurse -Force }
    $gen = Join-Path $obj 'gen'
    $classes = Join-Path $obj 'classes'
    $dex = Join-Path $obj 'dex'
    $null = New-Item -ItemType Directory -Force $gen, $classes, $dex
    Write-Host "== $Name"

    # 资源和清单；R.java 写进 gen\
    Invoke-Tool $aapt2 @('compile', '--dir', (Join-Path $src 'res'), '-o', (Join-Path $obj 'res.zip'))
    Invoke-Tool $aapt2 @('link', '-o', (Join-Path $obj 'base.apk'), '-I', $AndroidJar,
        '--manifest', (Join-Path $src 'AndroidManifest.xml'), '--java', $gen, (Join-Path $obj 'res.zip'))

    $sources = @(Get-ChildItem -LiteralPath (Join-Path $src 'src'), $gen -Recurse -File -Filter *.java | ForEach-Object FullName)
    Invoke-Tool $javac (@('--release', '17', '-encoding', 'UTF-8', '-Xlint:all', '-classpath', $AndroidJar, '-d', $classes) + $sources)
    $classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -File -Filter *.class | ForEach-Object FullName)
    Invoke-Tool $d8 (@('--release', '--min-api', '30', '--lib', $AndroidJar, '--output', $dex) + $classFiles)

    # aapt2 的包里加 classes.dex。aapt add 用相对路径当包内路径，所以在 dex 目录里跑；已有条目原样保留
    # （resources.arsc 必须不压缩，targetSdk 30 起装不上压缩的）
    $unsigned = Join-Path $obj 'unsigned.apk'
    Copy-Item -LiteralPath (Join-Path $obj 'base.apk') -Destination $unsigned
    Push-Location -LiteralPath $dex
    try { Invoke-Tool $aapt @('add', $unsigned, 'classes.dex') } finally { Pop-Location }

    $aligned = Join-Path $obj 'aligned.apk'
    Invoke-Tool $zipalign @('-f', '-p', '4', $unsigned, $aligned)
    $apk = Join-Path $OutDir $ApkName
    # PKCS12 的密钥口令就是密钥库口令；不另给 --key-pass（同一个文件给两次，apksigner 会去读第二行）。
    # minSdk 30 用不上 v1（JAR 签名）；签 v2 + v3，不出 v4 的 .idsig
    Invoke-Tool $apksigner @('sign', '--ks', $KeyStore, '--ks-type', 'PKCS12', '--ks-key-alias', $KeyAlias,
        '--ks-pass', "file:$KeyPassFile", '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
        '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false', '--out', $apk, $aligned)
    Invoke-Tool $zipalign @('-c', '-p', '4', $apk)
    Invoke-Tool $apksigner @('verify', '--min-sdk-version', '30', $apk)
    return $apk
}

$apks = @(
    Build-Apk 'ime' 'modavd-ime.apk'
    Build-Apk 'testapp' 'modavd-ime-testapp.apk'
)

# ---- 汇总 ----
$sums = foreach ($apk in $apks) {
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($apk))"
}
Set-Content -LiteralPath (Join-Path $OutDir 'SHA256SUMS.txt') -Value $sums -Encoding ascii
Write-Host ''
foreach ($apk in $apks) {
    Write-Host $apk
    & $aapt2 dump badging $apk | Select-String -Pattern '^(package|minSdkVersion|targetSdkVersion|application-label)' |
        ForEach-Object { Write-Host "  $_" }
}
$sums | ForEach-Object { Write-Host $_ }
