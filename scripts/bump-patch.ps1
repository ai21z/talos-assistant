[CmdletBinding()]
param(
    [string]$PropertiesPath = "gradle.properties",
    [string]$ChangelogPath = "CHANGELOG.md"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false, $true)

function Get-TalosPathState {
    param(
        [Parameter(Mandatory)]
        [string]$Path,

        [Parameter(Mandatory)]
        [string]$Description
    )

    try {
        $attributes = [System.IO.File]::GetAttributes($Path)
    }
    catch {
        $pathFailure = $_.Exception
        while ($null -ne $pathFailure.InnerException) {
            $pathFailure = $pathFailure.InnerException
        }
        if ($pathFailure -is [System.IO.FileNotFoundException] -or
            $pathFailure -is [System.IO.DirectoryNotFoundException]) {
            return [pscustomobject]@{
                Exists = $false
                Attributes = $null
            }
        }
        throw "$Description path state could not be authenticated at '$Path': $($pathFailure.Message)"
    }

    return [pscustomobject]@{
        Exists = $true
        Attributes = $attributes
    }
}

function Assert-TalosOrdinaryFileState {
    param(
        [Parameter(Mandatory)]
        [object]$State,

        [Parameter(Mandatory)]
        [string]$Path,

        [Parameter(Mandatory)]
        [string]$Description
    )

    if (-not $State.Exists) {
        throw "$Description is absent at '$Path'."
    }
    $forbiddenAttributes = [System.IO.FileAttributes]::Directory -bor
        [System.IO.FileAttributes]::ReparsePoint -bor
        [System.IO.FileAttributes]::Device
    if (($State.Attributes -band $forbiddenAttributes) -ne 0) {
        throw "$Description must be an ordinary non-reparse file at '$Path'; found attributes '$($State.Attributes)'."
    }
}

function ConvertFrom-TalosJavaPropertyKey {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$RawKey
    )

    $decoded = [System.Text.StringBuilder]::new()
    $backslash = [char]92
    for ($index = 0; $index -lt $RawKey.Length; $index++) {
        $character = $RawKey[$index]
        if ($character -ne $backslash) {
            [void]$decoded.Append($character)
            continue
        }
        $index++
        if ($index -ge $RawKey.Length) {
            throw "gradle.properties contains an unterminated escape in a property key."
        }
        $escaped = $RawKey[$index]
        if ($escaped -ceq 'u') {
            if ($index + 4 -ge $RawKey.Length) {
                throw "gradle.properties contains a short Unicode escape in a property key."
            }
            $hex = $RawKey.Substring($index + 1, 4)
            if ($hex -cnotmatch '\A[0-9A-Fa-f]{4}\z') {
                throw "gradle.properties contains a malformed Unicode escape in a property key."
            }
            [void]$decoded.Append([char][System.Convert]::ToInt32($hex, 16))
            $index += 4
            continue
        }
        switch -CaseSensitive ($escaped) {
            't' { [void]$decoded.Append([char]9); continue }
            'n' { [void]$decoded.Append([char]10); continue }
            'r' { [void]$decoded.Append([char]13); continue }
            'f' { [void]$decoded.Append([char]12); continue }
            default { [void]$decoded.Append($escaped); continue }
        }
    }
    return $decoded.ToString()
}

function ConvertTo-TalosJavaPropertyRecord {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$LogicalLine,

        [Parameter(Mandatory)]
        [int]$PhysicalLineCount,

        [Parameter(Mandatory)]
        [bool]$WasContinued,

        [Parameter(Mandatory)]
        [int]$StartIndex,

        [Parameter(Mandatory)]
        [int]$SourceLength
    )

    $space = [char]32
    $tab = [char]9
    $formFeed = [char]12
    $backslash = [char]92
    $cursor = 0
    while ($cursor -lt $LogicalLine.Length -and
        ($LogicalLine[$cursor] -eq $space -or
            $LogicalLine[$cursor] -eq $tab -or
            $LogicalLine[$cursor] -eq $formFeed)) {
        $cursor++
    }
    if ($cursor -ge $LogicalLine.Length -or
        $LogicalLine[$cursor] -eq '#' -or
        $LogicalLine[$cursor] -eq '!') {
        return $null
    }

    $keyStart = $cursor
    $escaped = $false
    while ($cursor -lt $LogicalLine.Length) {
        $character = $LogicalLine[$cursor]
        if ($escaped) {
            $escaped = $false
            $cursor++
            continue
        }
        if ($character -eq $backslash) {
            $escaped = $true
            $cursor++
            continue
        }
        if ($character -eq '=' -or $character -eq ':' -or
            $character -eq $space -or $character -eq $tab -or
            $character -eq $formFeed) {
            break
        }
        $cursor++
    }

    $rawKey = $LogicalLine.Substring($keyStart, $cursor - $keyStart)
    $decodedKey = ConvertFrom-TalosJavaPropertyKey -RawKey $rawKey
    return [pscustomobject]@{
        Key = $decodedKey
        LogicalLine = $LogicalLine
        PhysicalLineCount = $PhysicalLineCount
        WasContinued = $WasContinued
        StartIndex = $StartIndex
        SourceLength = $SourceLength
    }
}

function Get-TalosJavaPropertyRecords {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$Text
    )

    $space = [char]32
    $tab = [char]9
    $formFeed = [char]12
    $backslash = [char]92
    $naturalLines = [regex]::Split($Text, "`n")
    $records = [System.Collections.Generic.List[object]]::new()
    $logicalBuilder = [System.Text.StringBuilder]::new()
    $continuing = $false
    $wasContinued = $false
    $physicalLineCount = 0
    $logicalStart = 0
    $sourceLength = 0
    $naturalLineStart = 0

    for ($naturalIndex = 0; $naturalIndex -lt $naturalLines.Length; $naturalIndex++) {
        $naturalLine = $naturalLines[$naturalIndex]
        if (-not $continuing) {
            $firstContent = 0
            while ($firstContent -lt $naturalLine.Length -and
                ($naturalLine[$firstContent] -eq $space -or
                    $naturalLine[$firstContent] -eq $tab -or
                    $naturalLine[$firstContent] -eq $formFeed)) {
                $firstContent++
            }
            if ($firstContent -ge $naturalLine.Length -or
                $naturalLine[$firstContent] -eq '#' -or
                $naturalLine[$firstContent] -eq '!') {
                $naturalLineStart += $naturalLine.Length
                if ($naturalIndex -lt $naturalLines.Length - 1) {
                    $naturalLineStart++
                }
                continue
            }
            [void]$logicalBuilder.Clear()
            $wasContinued = $false
            $physicalLineCount = 0
            $logicalStart = $naturalLineStart
            $sourceLength = 0
            $piece = $naturalLine
        }
        else {
            $continuationStart = 0
            while ($continuationStart -lt $naturalLine.Length -and
                ($naturalLine[$continuationStart] -eq $space -or
                    $naturalLine[$continuationStart] -eq $tab -or
                    $naturalLine[$continuationStart] -eq $formFeed)) {
                $continuationStart++
            }
            $piece = $naturalLine.Substring($continuationStart)
        }

        $physicalLineCount++
        $sourceLength = ($naturalLineStart + $naturalLine.Length) - $logicalStart
        $terminalBackslashes = 0
        for ($backslashIndex = $piece.Length - 1;
            $backslashIndex -ge 0 -and $piece[$backslashIndex] -eq $backslash;
            $backslashIndex--) {
            $terminalBackslashes++
        }
        $lineContinues = ($terminalBackslashes % 2) -eq 1
        if ($lineContinues) {
            [void]$logicalBuilder.Append($piece.Substring(0, $piece.Length - 1))
            $continuing = $true
            $wasContinued = $true
        }
        else {
            [void]$logicalBuilder.Append($piece)
            $record = ConvertTo-TalosJavaPropertyRecord `
                -LogicalLine $logicalBuilder.ToString() `
                -PhysicalLineCount $physicalLineCount `
                -WasContinued $wasContinued `
                -StartIndex $logicalStart `
                -SourceLength $sourceLength
            if ($null -ne $record) {
                [void]$records.Add($record)
            }
            [void]$logicalBuilder.Clear()
            $continuing = $false
        }

        $naturalLineStart += $naturalLine.Length
        if ($naturalIndex -lt $naturalLines.Length - 1) {
            $naturalLineStart++
        }
    }

    if ($continuing) {
        $record = ConvertTo-TalosJavaPropertyRecord `
            -LogicalLine $logicalBuilder.ToString() `
            -PhysicalLineCount $physicalLineCount `
            -WasContinued $true `
            -StartIndex $logicalStart `
            -SourceLength $sourceLength
        if ($null -ne $record) {
            [void]$records.Add($record)
        }
    }
    return $records.ToArray()
}

function Test-TalosByteArrayEqual {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [byte[]]$Expected,

        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [byte[]]$Actual
    )

    if ($Expected.Length -ne $Actual.Length) {
        return $false
    }
    for ($index = 0; $index -lt $Expected.Length; $index++) {
        if ($Expected[$index] -ne $Actual[$index]) {
            return $false
        }
    }
    return $true
}

function Test-TalosFileBytes {
    param(
        [Parameter(Mandatory)]
        [string]$Path,

        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [byte[]]$Expected
    )

    $pathState = Get-TalosPathState -Path $Path -Description "Candidate-bump file"
    if (-not $pathState.Exists) {
        return $false
    }
    Assert-TalosOrdinaryFileState `
        -State $pathState `
        -Path $Path `
        -Description "Candidate-bump file"
    return Test-TalosByteArrayEqual -Expected $Expected -Actual ([System.IO.File]::ReadAllBytes($Path))
}

function Assert-TalosFileBytes {
    param(
        [Parameter(Mandatory)]
        [string]$Path,

        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [byte[]]$Expected,

        [Parameter(Mandatory)]
        [string]$Description
    )

    if (-not (Test-TalosFileBytes -Path $Path -Expected $Expected)) {
        throw "$Description bytes changed unexpectedly at '$Path'."
    }
}

function New-TalosOwnedPath {
    param(
        [Parameter(Mandatory)]
        [string]$TargetPath,

        [Parameter(Mandatory)]
        [ValidateSet("stage", "backup")]
        [string]$Kind
    )

    $parent = [System.IO.Path]::GetDirectoryName($TargetPath)
    $leaf = [System.IO.Path]::GetFileName($TargetPath)
    $ownedPath = [System.IO.Path]::Combine(
        $parent,
        ".$leaf.talos-bump-$([System.Guid]::NewGuid().ToString('N')).$Kind"
    )
    $ownedState = Get-TalosPathState -Path $ownedPath -Description "Candidate-bump $Kind path"
    if ($ownedState.Exists) {
        throw "Refusing to reuse an existing candidate-bump $Kind path at '$ownedPath'."
    }
    return $ownedPath
}

function New-TalosStagedFile {
    param(
        [Parameter(Mandatory)]
        [string]$TargetPath,

        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [byte[]]$Bytes
    )

    $stagePath = New-TalosOwnedPath -TargetPath $TargetPath -Kind "stage"
    $stream = $null
    try {
        $stream = [System.IO.FileStream]::new(
            $stagePath,
            [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::None,
            4096,
            [System.IO.FileOptions]::WriteThrough
        )
        if ($Bytes.Length -gt 0) {
            $stream.Write($Bytes, 0, $Bytes.Length)
        }
        $stream.Flush($true)
        $stream.Dispose()
        $stream = $null
        Assert-TalosFileBytes -Path $stagePath -Expected $Bytes -Description "Staged candidate-bump file"
        return $stagePath
    }
    catch {
        $originalFailure = $_.Exception.Message
        $cleanupFailures = [System.Collections.Generic.List[string]]::new()
        if ($null -ne $stream) {
            try {
                $stream.Dispose()
            }
            catch {
                $cleanupFailures.Add("stage stream disposal failed: $($_.Exception.Message)")
            }
        }
        $stageState = $null
        try {
            $stageState = Get-TalosPathState -Path $stagePath -Description "Staged candidate-bump file"
        }
        catch {
            $cleanupFailures.Add("stage state authentication failed at '$stagePath': $($_.Exception.Message)")
        }
        if ($null -ne $stageState -and $stageState.Exists) {
            try {
                Remove-TalosOwnedFile `
                    -Path $stagePath `
                    -Expected $Bytes `
                    -Description "Staged candidate-bump file"
            }
            catch {
                $cleanupFailures.Add("stage cleanup failed at '$stagePath': $($_.Exception.Message)")
            }
        }
        if ($cleanupFailures.Count -gt 0) {
            throw "Candidate-bump staging failed: $originalFailure Cleanup was incomplete: $($cleanupFailures -join ' ')"
        }
        throw "Candidate-bump staging failed; the owned stage was absent or removed: $originalFailure"
    }
}

function Remove-TalosOwnedFile {
    param(
        [Parameter(Mandatory)]
        [string]$Path,

        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [byte[]]$Expected,

        [Parameter(Mandatory)]
        [string]$Description
    )

    $pathState = Get-TalosPathState -Path $Path -Description $Description
    if (-not $pathState.Exists) {
        return
    }
    Assert-TalosOrdinaryFileState -State $pathState -Path $Path -Description $Description
    Assert-TalosFileBytes -Path $Path -Expected $Expected -Description $Description
    [System.IO.File]::Delete($Path)
    $postDeleteState = Get-TalosPathState -Path $Path -Description "$Description post-delete"
    if ($postDeleteState.Exists) {
        throw "$Description remains present after cleanup at '$Path'."
    }
}

function Restore-TalosPromotedFile {
    param(
        [Parameter(Mandatory)]
        [string]$TargetPath,

        [Parameter(Mandatory)]
        [string]$BackupPath,

        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [byte[]]$ExpectedPromoted,

        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [byte[]]$ExpectedOriginal,

        [Parameter(Mandatory)]
        [string]$Description,

        [Parameter(Mandatory)]
        [ref]$DiscardPath
    )

    Assert-TalosFileBytes -Path $TargetPath -Expected $ExpectedPromoted -Description "$Description promoted target"
    Assert-TalosFileBytes -Path $BackupPath -Expected $ExpectedOriginal -Description "$Description restoration backup"
    $DiscardPath.Value = New-TalosOwnedPath -TargetPath $TargetPath -Kind "backup"
    [System.IO.File]::Replace($BackupPath, $TargetPath, $DiscardPath.Value, $true)
    Assert-TalosFileBytes -Path $TargetPath -Expected $ExpectedOriginal -Description "$Description restored target"
    Remove-TalosOwnedFile `
        -Path $DiscardPath.Value `
        -Expected $ExpectedPromoted `
        -Description "$Description discarded promoted target"
    $DiscardPath.Value = $null
}

$propertiesFullPath = [System.IO.Path]::GetFullPath($PropertiesPath)
$changelogFullPath = [System.IO.Path]::GetFullPath($ChangelogPath)
$pathComparison = if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
    [System.StringComparison]::OrdinalIgnoreCase
}
else {
    [System.StringComparison]::Ordinal
}
if ([string]::Equals($propertiesFullPath, $changelogFullPath, $pathComparison)) {
    throw "gradle.properties and CHANGELOG.md must be distinct files."
}

$propertiesState = Get-TalosPathState -Path $propertiesFullPath -Description "gradle.properties"
if (-not $propertiesState.Exists) {
    throw "gradle.properties not found at '$PropertiesPath'."
}
Assert-TalosOrdinaryFileState `
    -State $propertiesState `
    -Path $propertiesFullPath `
    -Description "gradle.properties"

$propertiesOriginalBytes = [System.IO.File]::ReadAllBytes($propertiesFullPath)
$propertiesContent = $utf8NoBom.GetString($propertiesOriginalBytes)
$normalizedProperties = $propertiesContent -replace "`r`n", "`n" -replace "`r", "`n"
$propertyRecords = @(Get-TalosJavaPropertyRecords -Text $normalizedProperties)
$propertyAssignments = @($propertyRecords | Where-Object {
    [string]::Equals(
        $_.Key,
        "talosVersion",
        [System.StringComparison]::Ordinal
    )
})
if ($propertyAssignments.Count -eq 0) {
    throw "Could not find a numeric talosVersion entry in '$PropertiesPath'."
}
if ($propertyAssignments.Count -ne 1) {
    throw "gradle.properties must contain exactly one talosVersion assignment; found $($propertyAssignments.Count)."
}
$canonicalVersionPattern = '\AtalosVersion=(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\z'
$propertyAssignment = $propertyAssignments[0]
$propertyMatch = [regex]::Match($propertyAssignment.LogicalLine, $canonicalVersionPattern)
if ($propertyAssignment.PhysicalLineCount -ne 1 -or
    $propertyAssignment.WasContinued -or
    -not $propertyMatch.Success) {
    throw "Could not find a numeric talosVersion entry in '$PropertiesPath'."
}
$currentVersion = "$($propertyMatch.Groups[1].Value).$($propertyMatch.Groups[2].Value).$($propertyMatch.Groups[3].Value)"

$major = [int]$propertyMatch.Groups[1].Value
$minor = [int]$propertyMatch.Groups[2].Value
$patch = [int]$propertyMatch.Groups[3].Value + 1
$newVersion = "$major.$minor.$patch"

$changelogState = Get-TalosPathState -Path $changelogFullPath -Description "CHANGELOG.md"
if (-not $changelogState.Exists) {
    throw "CHANGELOG.md not found at '$ChangelogPath'."
}
Assert-TalosOrdinaryFileState `
    -State $changelogState `
    -Path $changelogFullPath `
    -Description "CHANGELOG.md"

$today = [System.DateTime]::Now.ToString(
    "yyyy-MM-dd",
    [System.Globalization.CultureInfo]::InvariantCulture
)
$changelogOriginalBytes = [System.IO.File]::ReadAllBytes($changelogFullPath)
$changelogContent = $utf8NoBom.GetString($changelogOriginalBytes)
$normalizedChangelog = $changelogContent -replace "`r`n", "`n" -replace "`r", "`n"

if ($normalizedChangelog -match 'pending release notes') {
    throw "CHANGELOG.md contains placeholder text: pending release notes"
}

$bracketedHeadingMatches = [regex]::Matches(
    $normalizedChangelog,
    '(?m)^[ ]{0,3}##[ \t]+\[[^\r\n]*$'
)
$headingMatches = [regex]::Matches(
    $normalizedChangelog,
    '(?m)^## \[([^\]\r\n]+)\](?: - ([0-9]{4}-[0-9]{2}-[0-9]{2}))?[ \t]*$'
)
if ($headingMatches.Count -ne $bracketedHeadingMatches.Count) {
    throw "CHANGELOG.md contains a malformed bracketed top-level release heading."
}
$unreleasedCount = 0
$unreleasedMatch = $null
$releasedVersions = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($headingMatch in $headingMatches) {
    $headingName = $headingMatch.Groups[1].Value
    if ($headingName -ceq "Unreleased") {
        if ($headingMatch.Groups[2].Success) {
            throw "CHANGELOG.md must not attach a release date to '## [Unreleased]'."
        }
        $unreleasedCount++
        if ($null -eq $unreleasedMatch) {
            $unreleasedMatch = $headingMatch
        }
    }
    else {
        if ($headingName -notmatch '^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$') {
            throw "CHANGELOG.md released-version headings must use numeric MAJOR.MINOR.PATCH identities; found '$headingName'."
        }
        if (-not $headingMatch.Groups[2].Success) {
            throw "Released CHANGELOG.md version $headingName must include an ISO release date."
        }
        $parsedReleaseDate = [System.DateTime]::MinValue
        if (-not [System.DateTime]::TryParseExact(
                $headingMatch.Groups[2].Value,
                "yyyy-MM-dd",
                [System.Globalization.CultureInfo]::InvariantCulture,
                [System.Globalization.DateTimeStyles]::None,
                [ref]$parsedReleaseDate
            )) {
            throw "Released CHANGELOG.md version $headingName must include a valid ISO release date; found '$($headingMatch.Groups[2].Value)'."
        }
        if (-not $releasedVersions.Add($headingName)) {
            throw "CHANGELOG.md contains a duplicate released-version heading: '$headingName'."
        }
    }
}
if ($unreleasedCount -eq 0) {
    throw "CHANGELOG.md must contain a top-level '## [Unreleased]' section before bumping a candidate version."
}
if ($unreleasedCount -ne 1) {
    throw "CHANGELOG.md must contain exactly one top-level '## [Unreleased]' section; found $unreleasedCount."
}

$beforeUnreleased = $normalizedChangelog.Substring(0, $unreleasedMatch.Index)
if ($beforeUnreleased -notmatch '(?s)\A# Changelog\s*\n\s*\z') {
    throw "CHANGELOG.md must keep '## [Unreleased]' as the first section after '# Changelog'."
}

$nextHeadingMatch = $null
foreach ($headingMatch in $headingMatches) {
    if ($headingMatch.Index -gt $unreleasedMatch.Index) {
        $nextHeadingMatch = $headingMatch
        break
    }
}
if ($null -eq $nextHeadingMatch) {
    throw "CHANGELOG.md must contain a released version section after '## [Unreleased]'."
}
$topReleasedVersion = $nextHeadingMatch.Groups[1].Value
if ($topReleasedVersion -cne $currentVersion) {
    throw "Top released CHANGELOG.md version $topReleasedVersion does not match talosVersion $currentVersion."
}
if ($releasedVersions.Contains($newVersion)) {
    throw "CHANGELOG.md already contains the target candidate version heading '$newVersion'."
}

$bodyStart = $unreleasedMatch.Index + $unreleasedMatch.Length
$bodyEnd = $nextHeadingMatch.Index
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
$updatedChangelog = $updatedChangelogNormalized.TrimEnd() + "`n"

$updatedProperties = $normalizedProperties.Substring(0, $propertyAssignment.StartIndex) +
    "talosVersion=$newVersion" +
    $normalizedProperties.Substring(
        $propertyAssignment.StartIndex + $propertyAssignment.SourceLength
    )
$updatedProperties = $updatedProperties.TrimEnd("`n") + "`n"

$updatedPropertiesBytes = $utf8NoBom.GetBytes($updatedProperties)
$updatedChangelogBytes = $utf8NoBom.GetBytes($updatedChangelog)
$propertiesStagePath = $null
$changelogStagePath = $null
$propertiesBackupPath = $null
$changelogBackupPath = $null
$propertiesDiscardPath = $null
$changelogDiscardPath = $null
$propertiesPromoted = $false
$changelogPromoted = $false

try {
    $propertiesStagePath = New-TalosStagedFile -TargetPath $propertiesFullPath -Bytes $updatedPropertiesBytes
    $changelogStagePath = New-TalosStagedFile -TargetPath $changelogFullPath -Bytes $updatedChangelogBytes
    $propertiesBackupPath = New-TalosOwnedPath -TargetPath $propertiesFullPath -Kind "backup"
    $changelogBackupPath = New-TalosOwnedPath -TargetPath $changelogFullPath -Kind "backup"

    Assert-TalosFileBytes -Path $propertiesFullPath -Expected $propertiesOriginalBytes -Description "gradle.properties preimage"
    Assert-TalosFileBytes -Path $changelogFullPath -Expected $changelogOriginalBytes -Description "CHANGELOG.md preimage"

    [System.IO.File]::Replace($propertiesStagePath, $propertiesFullPath, $propertiesBackupPath, $true)
    $propertiesPromoted = $true
    $propertiesStagePath = $null
    Assert-TalosFileBytes -Path $propertiesFullPath -Expected $updatedPropertiesBytes -Description "gradle.properties promoted target"
    Assert-TalosFileBytes -Path $propertiesBackupPath -Expected $propertiesOriginalBytes -Description "gradle.properties backup"
    Assert-TalosFileBytes -Path $changelogFullPath -Expected $changelogOriginalBytes -Description "CHANGELOG.md preimage"

    [System.IO.File]::Replace($changelogStagePath, $changelogFullPath, $changelogBackupPath, $true)
    $changelogPromoted = $true
    $changelogStagePath = $null
    Assert-TalosFileBytes -Path $changelogFullPath -Expected $updatedChangelogBytes -Description "CHANGELOG.md promoted target"
    Assert-TalosFileBytes -Path $changelogBackupPath -Expected $changelogOriginalBytes -Description "CHANGELOG.md backup"
    Assert-TalosFileBytes -Path $propertiesFullPath -Expected $updatedPropertiesBytes -Description "gradle.properties final target"
}
catch {
    $originalFailure = $_.Exception.Message
    $recoveryFailures = [System.Collections.Generic.List[string]]::new()

    if ($changelogPromoted) {
        try {
            Restore-TalosPromotedFile `
                -TargetPath $changelogFullPath `
                -BackupPath $changelogBackupPath `
                -ExpectedPromoted $updatedChangelogBytes `
                -ExpectedOriginal $changelogOriginalBytes `
                -Description "CHANGELOG.md" `
                -DiscardPath ([ref]$changelogDiscardPath)
            $changelogPromoted = $false
            $changelogBackupPath = $null
        }
        catch {
            $restorationFailure = $_.Exception.Message
            $discardState = " No restoration discard path is present."
            if ($null -ne $changelogDiscardPath) {
                try {
                    $discardPathState = Get-TalosPathState `
                        -Path $changelogDiscardPath `
                        -Description "CHANGELOG.md restoration discard"
                    if ($discardPathState.Exists) {
                        $discardState = " Preserved restoration evidence at '$changelogDiscardPath'."
                    }
                }
                catch {
                    $discardState = " Restoration evidence state is UNKNOWN at '$changelogDiscardPath': $($_.Exception.Message)"
                }
            }
            $recoveryFailures.Add("CHANGELOG.md restoration failed: $restorationFailure$discardState")
        }
    }
    if ($propertiesPromoted) {
        try {
            Restore-TalosPromotedFile `
                -TargetPath $propertiesFullPath `
                -BackupPath $propertiesBackupPath `
                -ExpectedPromoted $updatedPropertiesBytes `
                -ExpectedOriginal $propertiesOriginalBytes `
                -Description "gradle.properties" `
                -DiscardPath ([ref]$propertiesDiscardPath)
            $propertiesPromoted = $false
            $propertiesBackupPath = $null
        }
        catch {
            $restorationFailure = $_.Exception.Message
            $discardState = " No restoration discard path is present."
            if ($null -ne $propertiesDiscardPath) {
                try {
                    $discardPathState = Get-TalosPathState `
                        -Path $propertiesDiscardPath `
                        -Description "gradle.properties restoration discard"
                    if ($discardPathState.Exists) {
                        $discardState = " Preserved restoration evidence at '$propertiesDiscardPath'."
                    }
                }
                catch {
                    $discardState = " Restoration evidence state is UNKNOWN at '$propertiesDiscardPath': $($_.Exception.Message)"
                }
            }
            $recoveryFailures.Add("gradle.properties restoration failed: $restorationFailure$discardState")
        }
    }

    foreach ($ownedStage in @(
        @($propertiesStagePath, $updatedPropertiesBytes, "gradle.properties stage"),
        @($changelogStagePath, $updatedChangelogBytes, "CHANGELOG.md stage")
    )) {
        if ($null -ne $ownedStage[0]) {
            try {
                Remove-TalosOwnedFile -Path $ownedStage[0] -Expected $ownedStage[1] -Description $ownedStage[2]
            }
            catch {
                $recoveryFailures.Add("$($ownedStage[2]) cleanup failed: $($_.Exception.Message)")
            }
        }
    }

    foreach ($ownedBackup in @(
        @($propertiesBackupPath, $propertiesOriginalBytes, $propertiesFullPath, "gradle.properties backup"),
        @($changelogBackupPath, $changelogOriginalBytes, $changelogFullPath, "CHANGELOG.md backup")
    )) {
        if ($null -ne $ownedBackup[0]) {
            try {
                $ownedBackupState = Get-TalosPathState `
                    -Path $ownedBackup[0] `
                    -Description $ownedBackup[3]
                if ($ownedBackupState.Exists) {
                    if (-not (Test-TalosFileBytes -Path $ownedBackup[2] -Expected $ownedBackup[1])) {
                        throw "target state is not the authenticated original; preserving '$($ownedBackup[0])'."
                    }
                    Remove-TalosOwnedFile -Path $ownedBackup[0] -Expected $ownedBackup[1] -Description $ownedBackup[3]
                }
            }
            catch {
                $recoveryFailures.Add("$($ownedBackup[3]) cleanup failed: $($_.Exception.Message)")
            }
        }
    }

    if ($recoveryFailures.Count -gt 0) {
        throw "Candidate bump failed: $originalFailure Recovery was incomplete: $($recoveryFailures -join ' ')"
    }
    throw "Candidate bump failed before complete promotion; any promoted files were restored to authenticated originals: $originalFailure"
}

$cleanupFailures = [System.Collections.Generic.List[string]]::new()
foreach ($ownedBackup in @(
    @($propertiesBackupPath, $propertiesOriginalBytes, "gradle.properties backup"),
    @($changelogBackupPath, $changelogOriginalBytes, "CHANGELOG.md backup")
)) {
    try {
        Remove-TalosOwnedFile -Path $ownedBackup[0] -Expected $ownedBackup[1] -Description $ownedBackup[2]
    }
    catch {
        $cleanupFailures.Add("$($ownedBackup[2]) cleanup failed: $($_.Exception.Message)")
    }
}
if ($cleanupFailures.Count -gt 0) {
    throw "Candidate bump completed, but owned-backup cleanup failed: $($cleanupFailures -join ' ')"
}

Write-Output "Bumped Talos patch version to $newVersion and moved Unreleased changelog notes into the candidate entry."
