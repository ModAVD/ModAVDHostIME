#requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^v[0-9]+\.[0-9]+\.[0-9]+$')]
    [string]$Tag,
    [string]$FormalKeyStore,
    [string]$FormalKeyAlias,
    [string]$FormalKeyPassFile,
    [string]$BuildTools,
    [string]$AndroidJar,
    [string]$JavaHome,
    [string]$WorkDir = (Join-Path ([IO.Path]::GetTempPath()) ("modavd-hostime-release-" + $Tag)),
    [string]$UseExistingApk,
    [string]$ExpectedCertificateSha256 = 'd18cca3c1982149349f164d28e02490b2eb694e44d56834fa02a1062eb42e823',
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

function Invoke-Checked([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($Exe)) 失败（退出码 $LASTEXITCODE） / failed (exit code $LASTEXITCODE)" }
}

function Quote-Arg([string]$Value) {
    if ($Value -match '[\s"]') { return '"' + ($Value -replace '"', '\"') + '"' }
    return $Value
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$version = $Tag.Substring(1)
$notes = Join-Path $repoRoot ("release\$version.md")
$knownSums = Join-Path $repoRoot ("release\$version.SHA256SUMS.txt")
if (-not (Test-Path -LiteralPath $notes -PathType Leaf)) { throw "找不到发布说明：$notes / release notes not found: $notes" }
$WorkDir = [IO.Path]::GetFullPath($WorkDir)
if (Test-Path -LiteralPath $WorkDir) {
    if ((Get-ChildItem -LiteralPath $WorkDir -Force | Measure-Object).Count -ne 0) { throw "工作目录不是空目录：$WorkDir / work directory is not empty: $WorkDir" }
} else {
    New-Item -ItemType Directory -Force $WorkDir | Out-Null
}

$releaseApk = Join-Path $WorkDir 'modavd-ime.apk'
if ($UseExistingApk) {
    if (-not (Test-Path -LiteralPath $UseExistingApk -PathType Leaf)) { throw "找不到输入 APK：$UseExistingApk / input APK not found: $UseExistingApk" }
    if (-not (Test-Path -LiteralPath $knownSums -PathType Leaf)) { throw "没有预提交的校验文件：$knownSums / pre-committed checksum file not found: $knownSums" }
    $line = Get-Content -LiteralPath $knownSums | Where-Object { $_ -match '\smodavd-ime\.apk$' } | Select-Object -First 1
    if (-not $line) { throw "$knownSums 没有 modavd-ime.apk 条目 / $knownSums has no modavd-ime.apk entry" }
    $expectedHash = (($line -split '\s+')[0]).ToLowerInvariant()
    $actualHash = (Get-FileHash -LiteralPath $UseExistingApk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) { throw "输入 APK SHA-256 不符：$actualHash != $expectedHash / input APK SHA-256 mismatch: $actualHash != $expectedHash" }
    Copy-Item -LiteralPath $UseExistingApk -Destination $releaseApk
} else {
    foreach ($path in @($FormalKeyStore, $FormalKeyPassFile)) {
        if (-not $path -or -not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "正式密钥或口令文件不存在；脚本绝不生成正式密钥 / the official key store or password file does not exist; this script never generates an official key" }
    }
    if (-not $FormalKeyAlias) { throw '必须用 -FormalKeyAlias 指定正式密钥别名 / -FormalKeyAlias is required: name the official key alias' }
    $archive = Join-Path $WorkDir 'source.tar'
    $source = Join-Path $WorkDir 'source'
    New-Item -ItemType Directory -Force $source | Out-Null
    Invoke-Checked 'git' @('-C', $repoRoot, 'archive', '--format=tar', '--output', $archive, $Tag)
    Invoke-Checked 'tar' @('-xf', $archive, '-C', $source)
    $buildOut = Join-Path $source 'out'
    $buildArgs = @('-NoProfile', '-File', (Join-Path $source 'build.ps1'), '-OutDir', $buildOut)
    if ($BuildTools) { $buildArgs += @('-BuildTools', $BuildTools) }
    if ($AndroidJar) { $buildArgs += @('-AndroidJar', $AndroidJar) }
    if ($JavaHome) { $buildArgs += @('-JavaHome', $JavaHome) }
    # Same TZ as verify-release: aapt add converts the classes.dex entry time with the local time zone.
    $savedTz = $env:TZ
    $env:TZ = 'CET-1'
    try { Invoke-Checked 'pwsh' $buildArgs } finally { $env:TZ = $savedTz }
    $aligned = Join-Path $buildOut 'obj\ime\aligned.apk'
    if (-not (Test-Path -LiteralPath $aligned -PathType Leaf)) { throw "构建没有生成 $aligned / the build did not produce $aligned" }
    if (-not $BuildTools) {
        $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { throw '请用 -BuildTools 或设置 ANDROID_HOME / pass -BuildTools or set ANDROID_HOME' }
        $BuildTools = Join-Path $sdk 'build-tools\36.1.0'
    }
    $apksigner = Join-Path $BuildTools 'apksigner.bat'
    if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) { throw "找不到 $apksigner / not found: $apksigner" }
    Invoke-Checked $apksigner @('sign', '--ks', (Resolve-Path $FormalKeyStore), '--ks-type', 'PKCS12', '--ks-key-alias', $FormalKeyAlias,
        '--ks-pass', "file:$([IO.Path]::GetFullPath($FormalKeyPassFile))", '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
        '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false', '--out', $releaseApk, $aligned)
}

if (-not (Test-Path -LiteralPath $releaseApk -PathType Leaf)) { throw "没有发布 APK：$releaseApk / release APK not found: $releaseApk" }
$hash = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
$sums = Join-Path $WorkDir 'SHA256SUMS.txt'
Set-Content -LiteralPath $sums -Value "$hash  modavd-ime.apk" -Encoding ascii

if (-not $BuildTools) {
    if ($env:ANDROID_HOME) {
        $BuildTools = Join-Path $env:ANDROID_HOME 'build-tools\36.1.0'
    } elseif ($env:ANDROID_SDK_ROOT) {
        $BuildTools = Join-Path $env:ANDROID_SDK_ROOT 'build-tools\36.1.0'
    } else {
        throw '请用 -BuildTools 或设置 ANDROID_HOME 指定 apksigner / pass -BuildTools or set ANDROID_HOME so that apksigner can be found'
    }
}
$apksigner = Join-Path $BuildTools 'apksigner.bat'
if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) { throw "找不到 $apksigner" }
$verifyOutput = (& $apksigner verify '--verbose' '--print-certs' '--min-sdk-version' '30' $releaseApk 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw "apksigner verify 失败 / apksigner verify failed：$verifyOutput" }
if ($verifyOutput -notmatch [regex]::Escape($ExpectedCertificateSha256)) { throw "正式证书摘要不符 / official certificate digest mismatch：$verifyOutput" }

if ($UseExistingApk -and $Tag -ne 'v1.0.0') {
    throw '-UseExistingApk 只适用于 v1.0.0 / -UseExistingApk is only valid for v1.0.0'
}

$ghArgs = @('release', 'create', $Tag, '--repo', 'ModAVD/ModAVDHostIME', '--draft', '--verify-tag', '--notes-file', $notes, $releaseApk, $sums)
if ($DryRun) {
    Write-Host ('DryRun: gh ' + (($ghArgs | ForEach-Object { Quote-Arg $_ }) -join ' '))
} else {
    Invoke-Checked 'gh' $ghArgs
}
