#requires -Version 7.2
# Copyright 2026 Ji Mao Cai
# SPDX-License-Identifier: AGPL-3.0-or-later
# Run the actual workflow blocks with local ZIP fixtures and a build spy. No network or SDK required.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot
$jarPin = 'd9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a'
$archivePin = '37607369a28c5b640b3a7998868d45898ebcb777565a0e85f9acf36f29631d2e'

function Read-BuildBlock([string]$Workflow) {
    $lines = [Collections.Generic.List[string]]::new()
    $collect = $false
    foreach ($line in Get-Content -LiteralPath (Join-Path $repo ".github/workflows/$Workflow")) {
        if ($line.StartsWith('          $expectedJarHash =')) { $collect = $true }
        if (-not $collect) { continue }
        if ($line.Trim() -and -not $line.StartsWith('          ')) { break }
        $lines.Add($line.Substring([Math]::Min(10, $line.Length)))
    }
    if ($lines.Count -eq 0) { throw "No SDK build block in $Workflow" }
    return ($lines -join "`n").TrimEnd()
}

$ci = Read-BuildBlock 'ci.yml'
$verify = Read-BuildBlock 'verify-release.yml'
if ($ci -cne $verify) { throw 'The workflows must use identical SDK/build blocks' }
foreach ($pin in @($jarPin, $archivePin, 'platform-36_r02.zip', 'https://dl.google.com/android/repository/')) {
    if (-not $ci.Contains($pin)) { throw "Missing workflow pin: $pin" }
}

$work = Join-Path ([IO.Path]::GetTempPath()) ("modavd-sdk-test-" + [guid]::NewGuid().ToString('N'))
$oldAndroid = $env:ANDROID_HOME
$oldRunner = $env:RUNNER_TEMP
New-Item -ItemType Directory -Path $work | Out-Null
try {
    $goodJar = Join-Path $work 'good.jar'
    Set-Content -LiteralPath $goodJar -Value 'verified fixture jar' -NoNewline
    $fixtureJarHash = (Get-FileHash -LiteralPath $goodJar -Algorithm SHA256).Hash.ToLowerInvariant()
    $archives = @{}
    foreach ($kind in @('good', 'bad-jar', 'missing-jar')) {
        $source = Join-Path $work "$kind/android-36"
        New-Item -ItemType Directory -Path $source -Force | Out-Null
        if ($kind -eq 'good') { Copy-Item -LiteralPath $goodJar -Destination (Join-Path $source 'android.jar') }
        elseif ($kind -eq 'bad-jar') { Set-Content -LiteralPath (Join-Path $source 'android.jar') -Value 'wrong jar' }
        else { Set-Content -LiteralPath (Join-Path $source 'NOTICE.txt') -Value 'no jar' }
        $archives[$kind] = Join-Path $work "$kind.zip"
        Compress-Archive -LiteralPath $source -DestinationPath $archives[$kind]
    }
    function Invoke-Case([string]$Name, [string]$Installed, [string]$ArchiveKind,
                         [string]$ExpectedError = '', [switch]$BadDownload, [switch]$BuildFailure,
                         [switch]$DownloadFailure, [switch]$ExtractionFailure) {
        $caseRoot = Join-Path $work $Name
        $env:ANDROID_HOME = Join-Path $caseRoot 'installed sdk'
        $env:RUNNER_TEMP = Join-Path $caseRoot 'runner temp'
        New-Item -ItemType Directory -Path $env:RUNNER_TEMP -Force | Out-Null
        $installedJar = Join-Path $env:ANDROID_HOME 'platforms/android-36/android.jar'
        if ($Installed -ne 'missing') {
            New-Item -ItemType Directory -Path (Split-Path $installedJar) -Force | Out-Null
            if ($Installed -eq 'good') { Copy-Item -LiteralPath $goodJar -Destination $installedJar }
            else { Set-Content -LiteralPath $installedJar -Value 'incorrect preinstalled jar' }
        }
        $fixture = $archives[$ArchiveKind]
        $fixtureArchiveHash = (Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash.ToLowerInvariant()
        # Substitute only fixture hashes; execute the workflow's control flow unchanged.
        $block = [scriptblock]::Create($ci.Replace($jarPin, $fixtureJarHash).Replace($archivePin, $fixtureArchiveHash))
        $state = @{ downloads = 0; builds = 0; expansions = 0; jar = '' }
        function Invoke-WebRequest([string]$Uri, [string]$OutFile) {
            $state.downloads++
            if ($Uri -cne 'https://dl.google.com/android/repository/platform-36_r02.zip') { throw "Wrong URL: $Uri" }
            if ($DownloadFailure) { throw 'simulated download failure' }
            Copy-Item -LiteralPath $fixture -Destination $OutFile
            if ($BadDownload) { Add-Content -LiteralPath $OutFile -Value 'corrupted download' }
        }
        function Expand-Archive([string]$LiteralPath, [string]$DestinationPath) {
            $state.expansions++
            if ($ExtractionFailure) { throw 'simulated extraction failure' }
            Microsoft.PowerShell.Archive\Expand-Archive -LiteralPath $LiteralPath -DestinationPath $DestinationPath
        }
        function pwsh([switch]$NoProfile, [string]$File, [string]$BuildTools, [string]$AndroidJar) {
            $state.builds++
            $state.jar = $AndroidJar
            if ($File -ne '.\build.ps1' -or -not $NoProfile -or
                $BuildTools -ne (Join-Path $env:ANDROID_HOME 'build-tools\36.1.0')) { throw 'Build arguments changed' }
            if ((Get-FileHash -LiteralPath $AndroidJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $fixtureJarHash) {
                throw 'Unverified jar reached the build'
            }
            $global:LASTEXITCODE = if ($BuildFailure) { 31 } else { 0 }
        }
        $failure = ''
        try { & $block } catch { $failure = $_.Exception.Message }
        if ($ExpectedError) {
            if (-not $failure.Contains($ExpectedError)) { throw "${Name}: expected '$ExpectedError', got '$failure'" }
        } elseif ($failure) { throw "${Name}: $failure" }
        $expectedDownloads = if ($Installed -eq 'good') { 0 } else { 1 }
        $expectedBuilds = if (-not $ExpectedError -or $BuildFailure) { 1 } else { 0 }
        if ($state.downloads -ne $expectedDownloads -or $state.builds -ne $expectedBuilds) {
            throw "${Name}: wrong download/build counts: $($state | ConvertTo-Json -Compress)"
        }
        if ($BadDownload -and $state.expansions -ne 0) { throw 'Bad archive reached extraction' }
        if ($Installed -eq 'good' -and $state.jar -ne $installedJar) { throw 'Preinstalled jar was not reused' }
        if ($Installed -eq 'bad' -and $state.jar -eq $installedJar) { throw 'Bad preinstalled jar was reused' }
        if (Get-ChildItem -LiteralPath $env:RUNNER_TEMP -Force) { throw "${Name}: temporary SDK was not removed" }
        if ($Installed -ne 'missing' -and -not (Test-Path -LiteralPath $installedJar)) { throw 'Installed SDK was removed' }
        Write-Host "PASS $Name"
    }

    Invoke-Case 'A-preinstalled' 'good' 'good'
    Invoke-Case 'B-missing' 'missing' 'good'
    Invoke-Case 'C-mismatch' 'bad' 'good'
    Invoke-Case 'D-archive-hash' 'missing' 'good' 'SDK 压缩包 SHA-256 不符' -BadDownload
    Invoke-Case 'E-jar-hash' 'missing' 'bad-jar' 'android.jar SHA-256 不符'
    Invoke-Case 'missing-extracted-jar' 'missing' 'missing-jar' '找不到'
    Invoke-Case 'build-failure' 'missing' 'good' 'APK 构建失败' -BuildFailure
    Invoke-Case 'download-failure' 'missing' 'good' 'simulated download failure' -DownloadFailure
    Invoke-Case 'extraction-failure' 'missing' 'good' 'simulated extraction failure' -ExtractionFailure
    Write-Host 'PASS identical workflow blocks and all 9 scenarios'
} finally {
    $env:ANDROID_HOME = $oldAndroid
    $env:RUNNER_TEMP = $oldRunner
    # $work is the unique directory created above, directly under the system temp directory.
    if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($work)) -ne
        [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)) {
        throw "Refusing to clean outside the temp directory: $work"
    }
    Remove-Item -LiteralPath $work -Recurse -Force
}
