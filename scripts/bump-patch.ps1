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

if (-not (Test-Path -LiteralPath $ChangelogPath)) {
    throw "CHANGELOG.md not found at '$ChangelogPath'."
}

$today = Get-Date -Format "yyyy-MM-dd"
$changelogContent = Get-Content -LiteralPath $ChangelogPath -Raw
$normalizedChangelog = $changelogContent -replace "`r`n", "`n" -replace "`r", "`n"

if ($normalizedChangelog -match 'pending release notes') {
    throw "CHANGELOG.md contains placeholder text: pending release notes"
}

$unreleasedMatch = [regex]::Match($normalizedChangelog, '(?m)^## \[Unreleased\]\s*$')
if (-not $unreleasedMatch.Success) {
    throw "CHANGELOG.md must contain a top-level '## [Unreleased]' section before bumping a candidate version."
}

$beforeUnreleased = $normalizedChangelog.Substring(0, $unreleasedMatch.Index)
if ($beforeUnreleased -notmatch '(?s)\A# Changelog\s*\n\s*\z') {
    throw "CHANGELOG.md must keep '## [Unreleased]' as the first section after '# Changelog'."
}

$bodyStart = $unreleasedMatch.Index + $unreleasedMatch.Length
$remaining = $normalizedChangelog.Substring($bodyStart)
$nextHeadingMatch = [regex]::Match($remaining, '(?m)^## \[')
if (-not $nextHeadingMatch.Success) {
    throw "CHANGELOG.md must contain a released version section after '## [Unreleased]'."
}

$bodyEnd = $bodyStart + $nextHeadingMatch.Index
$unreleasedBody = $normalizedChangelog.Substring($bodyStart, $bodyEnd - $bodyStart).Trim()
$materialLines = @($unreleasedBody -split "`n" | Where-Object {
    $line = $_.Trim()
    $line.Length -gt 0 -and $line -notmatch '^###\s+'
})
if ($materialLines.Count -eq 0) {
    throw "Unreleased section has no material release notes. Add release notes before bumping a candidate version."
}

$tail = $normalizedChangelog.Substring($bodyEnd).TrimStart("`n")
$newEntry = "## [$newVersion] - $today`n`n$unreleasedBody"
$updatedChangelogNormalized = "# Changelog`n`n## [Unreleased]`n`n$newEntry`n`n$tail"
$updatedChangelog = ($updatedChangelogNormalized.TrimEnd() -replace "`n", "`r`n") + "`r`n"

$updatedProperties = [regex]::Replace(
    $propertiesContent,
    '(?m)^talosVersion=\d+\.\d+\.\d+$',
    "talosVersion=$newVersion",
    1
)
Set-Content -LiteralPath $PropertiesPath -Value $updatedProperties -Encoding UTF8
Set-Content -LiteralPath $ChangelogPath -Value $updatedChangelog -Encoding UTF8

Write-Output "Bumped Talos patch version to $newVersion and moved Unreleased changelog notes into the candidate entry."
