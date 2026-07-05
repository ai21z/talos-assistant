# cut-candidate.ps1 - hermetic scripted candidate cut (T747).
#
# Encodes the versioned candidate loop so the cut cannot produce the
# provenance-defect class found on the 0.10.1 cut (hand-typed SHA, stale
# summaries, uncommitted evidence chain, binary built before the commit):
#
#   1. refuse a dirty tree / detached HEAD
#   2. scripts/bump-patch.ps1 (validates Unreleased notes, promotes them)
#   3. commit "Cut <version> candidate"
#   4. gradlew installDist  (built FROM the committed tree)
#   5. cross-check launcher version vs gradle.properties vs git rev-parse HEAD
#   6. mandatory post-bump gradlew check
#   7. gradlew talosQualitySummaries; verify all four summaries report <version>
#   8. emit build/reports/talos/candidate-manifest.json (SHA from git, never typed)
#
# Flags:
#   -DryRun   print the plan and preconditions, mutate nothing
#   -SelfTest run fixture-based assertions of the parsing/manifest logic only

[CmdletBinding()]
param(
    [switch]$DryRun,
    [switch]$SelfTest,
    [string]$PropertiesPath = "gradle.properties",
    [string]$ManifestPath = "build/reports/talos/candidate-manifest.json"
)

$ErrorActionPreference = "Stop"

function Assert-CutEqual {
    param([string]$Name, [object]$Expected, [object]$Actual)
    if ("$Expected" -ne "$Actual") {
        throw "Self-test failed: $Name expected '$Expected' but got '$Actual'."
    }
}

function Assert-CutContains {
    param([string]$Name, [string]$Text, [string]$Needle)
    if ($null -eq $Text -or $Text.IndexOf($Needle, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "Self-test failed: $Name did not contain '$Needle'."
    }
}

function Get-TalosVersionFromProperties {
    param([string]$Content)
    $match = [regex]::Match($Content, '(?m)^talosVersion=(\d+)\.(\d+)\.(\d+)\s*$')
    if (-not $match.Success) { throw "talosVersion=MAJOR.MINOR.PATCH not found in gradle.properties content." }
    return "$($match.Groups[1].Value).$($match.Groups[2].Value).$($match.Groups[3].Value)"
}

function Test-LauncherVersionLine {
    param([string]$Line, [string]$Version)
    return $null -ne $Line -and $Line.StartsWith("Talos $Version ")
}

function New-CandidateManifest {
    param(
        [string]$Version,
        [string]$Sha,
        [string]$Branch,
        [string]$LauncherLine,
        [hashtable]$SummaryVersions,
        [hashtable]$StepTimestamps
    )
    if ($Sha -notmatch '^[0-9a-f]{40}$') { throw "Manifest sha must be a 40-hex git SHA, got '$Sha'." }
    return [ordered]@{
        schema          = "talos.candidateManifest.v1"
        version         = $Version
        sha             = $Sha
        branch          = $Branch
        launcherVersion = $LauncherLine
        summaryVersions = $SummaryVersions
        steps           = $StepTimestamps
        generated       = (Get-Date).ToString("o")
    }
}

function Invoke-CutCandidateSelfTest {
    $props = "org.gradle.jvmargs=-Xmx2g`ntalosVersion=0.10.2`n"
    Assert-CutEqual -Name "version parse" -Expected "0.10.2" -Actual (Get-TalosVersionFromProperties $props)
    $threw = $false
    try { Get-TalosVersionFromProperties "no version here" | Out-Null } catch { $threw = $true }
    Assert-CutEqual -Name "version parse fails on missing key" -Expected $true -Actual $threw

    Assert-CutEqual -Name "launcher line accepts matching version" -Expected $true `
        -Actual (Test-LauncherVersionLine "Talos 0.10.2 - Java 21 - Windows 11" "0.10.2")
    Assert-CutEqual -Name "launcher line rejects stale version" -Expected $false `
        -Actual (Test-LauncherVersionLine "Talos 0.10.1 - Java 21" "0.10.2")

    $manifest = New-CandidateManifest -Version "0.10.2" `
        -Sha ("ab" * 20) -Branch "codex/test" -LauncherLine "Talos 0.10.2 - Java 21" `
        -SummaryVersions @{ version = "0.10.2" } -StepTimestamps @{ bump = "t0" }
    Assert-CutEqual -Name "manifest version" -Expected "0.10.2" -Actual $manifest.version
    Assert-CutContains -Name "manifest json" -Text ($manifest | ConvertTo-Json -Depth 5) -Needle '"schema": "talos.candidateManifest.v1"'
    Assert-CutContains -Name "quality summaries command" -Text (Get-Content $PSCommandPath -Raw) -Needle 'talosQualitySummaries --no-daemon'
    $shaThrew = $false
    try { New-CandidateManifest -Version "x" -Sha "01646794" -Branch "b" -LauncherLine "l" -SummaryVersions @{} -StepTimestamps @{} | Out-Null } catch { $shaThrew = $true }
    Assert-CutEqual -Name "manifest rejects short sha" -Expected $true -Actual $shaThrew

    Write-Host "cut-candidate self-test passed."
}

if ($SelfTest) { Invoke-CutCandidateSelfTest; exit 0 }

function Invoke-Step {
    param([string]$Name, [scriptblock]$Action)
    Write-Host ">> $Name"
    & $Action
    if ($LASTEXITCODE -ne $null -and $LASTEXITCODE -ne 0) {
        throw "Step '$Name' failed with exit code $LASTEXITCODE."
    }
}

# ---- preconditions -------------------------------------------------------
$dirty = git status --porcelain
$branch = git branch --show-current
$currentVersion = Get-TalosVersionFromProperties (Get-Content $PropertiesPath -Raw)

if ($DryRun) {
    $displayBranch = $branch
    if (-not $displayBranch) { $displayBranch = "<detached HEAD>" }
    $dirtyState = "clean"
    if ($dirty) { $dirtyState = "dirty - candidate cut would stop before mutation" }
    Write-Host "DRY RUN - no changes will be made."
    Write-Host "  branch:          $displayBranch"
    Write-Host "  working tree:    $dirtyState"
    Write-Host "  current version: $currentVersion (next cut bumps the patch)"
    Write-Host "  plan: bump-patch -> commit -> installDist -> launcher/SHA cross-check -> gradlew check -> talosQualitySummaries -> $ManifestPath"
    exit 0
}

if ($dirty) {
    throw "Working tree is dirty. Commit or stash before cutting a candidate:`n$($dirty -join "`n")"
}
if (-not $branch) { throw "Detached HEAD. Check out a branch before cutting a candidate." }

$steps = [ordered]@{}

# ---- 1. bump (validates and promotes Unreleased notes) -------------------
Invoke-Step "bump-patch.ps1" { & "$PSScriptRoot\bump-patch.ps1" -PropertiesPath $PropertiesPath }
$version = Get-TalosVersionFromProperties (Get-Content $PropertiesPath -Raw)
$steps.bump = (Get-Date).ToString("o")

# ---- 2. commit the cut ----------------------------------------------------
Invoke-Step "commit cut" {
    git add $PropertiesPath CHANGELOG.md
    git commit -m "Cut $version candidate"
}
$sha = (git rev-parse HEAD).Trim()
$steps.commit = (Get-Date).ToString("o")

# ---- 3. build from the committed tree -------------------------------------
Invoke-Step "installDist" { .\gradlew.bat installDist --no-daemon }
$steps.installDist = (Get-Date).ToString("o")

# ---- 4. launcher cross-check ----------------------------------------------
$launcherLine = (& .\build\install\talos\bin\talos.bat --version 2>$null | Select-Object -First 1)
if (-not (Test-LauncherVersionLine $launcherLine $version)) {
    throw "Launcher version mismatch: expected 'Talos $version ...' but launcher reports '$launcherLine'."
}
$steps.launcherCheck = (Get-Date).ToString("o")

# ---- 5. mandatory post-bump check -----------------------------------------
Invoke-Step "gradlew check (mandatory post-bump)" { .\gradlew.bat check --no-daemon }
$steps.check = (Get-Date).ToString("o")

# ---- 6. quality summaries --------------------------------------------------
Invoke-Step "talosQualitySummaries" { .\gradlew.bat talosQualitySummaries --no-daemon }
$summaryVersions = [ordered]@{}
foreach ($name in 'version-summary', 'coverage-summary', 'e2e-summary', 'qodana-summary') {
    $path = "build/reports/talos/$name.json"
    $summaryVersion = (Get-Content $path -Raw | ConvertFrom-Json).version
    $summaryVersions[$name] = $summaryVersion
    if ($summaryVersion -ne $version) {
        throw "Summary $name reports version '$summaryVersion', expected '$version'."
    }
}
$steps.summaries = (Get-Date).ToString("o")

# ---- 7. manifest ------------------------------------------------------------
$manifest = New-CandidateManifest -Version $version -Sha $sha -Branch $branch `
    -LauncherLine $launcherLine -SummaryVersions $summaryVersions -StepTimestamps $steps
New-Item -ItemType Directory -Force (Split-Path $ManifestPath) | Out-Null
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $ManifestPath -Encoding UTF8
Write-Host "Candidate $version cut at $sha on $branch."
Write-Host "Manifest: $ManifestPath"
