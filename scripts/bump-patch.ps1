[CmdletBinding()]
param(
    [string]$PropertiesPath = "gradle.properties",
    [string]$ChangelogPath = "CHANGELOG.md"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $PropertiesPath)) {
    throw "gradle.properties not found at '$PropertiesPath'."
}

$propertiesContent = Get-Content -LiteralPath $PropertiesPath -Raw
$match = [regex]::Match($propertiesContent, '(?m)^talosVersion=(\d+)\.(\d+)\.(\d+)$')
if (-not $match.Success) {
    throw "Could not find a numeric talosVersion entry in '$PropertiesPath'."
}

$major = [int]$match.Groups[1].Value
$minor = [int]$match.Groups[2].Value
$patch = [int]$match.Groups[3].Value + 1
$newVersion = "$major.$minor.$patch"

$updatedProperties = [regex]::Replace(
    $propertiesContent,
    '(?m)^talosVersion=\d+\.\d+\.\d+$',
    "talosVersion=$newVersion",
    1
)
Set-Content -LiteralPath $PropertiesPath -Value $updatedProperties -Encoding UTF8

if (-not (Test-Path -LiteralPath $ChangelogPath)) {
    throw "CHANGELOG.md not found at '$ChangelogPath'."
}

$today = Get-Date -Format "yyyy-MM-dd"
$changelogContent = Get-Content -LiteralPath $ChangelogPath -Raw
$newEntry = @"
## [$newVersion] - $today

### Changed
- pending release notes

"@

$updatedChangelog = $changelogContent -replace "(?s)\A# Changelog\s*\r?\n\r?\n", "# Changelog`r`n`r`n$newEntry"
if ($updatedChangelog -eq $changelogContent) {
    $updatedChangelog = $newEntry + $changelogContent
}
Set-Content -LiteralPath $ChangelogPath -Value $updatedChangelog -Encoding UTF8

Write-Output "Bumped Talos patch version to $newVersion and added a changelog stub."
