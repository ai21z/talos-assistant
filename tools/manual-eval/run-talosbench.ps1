param(
    [string]$CasesPath = "",
    [string[]]$CaseId = @(),
    [switch]$ListCases,
    [switch]$ValidateOnly,
    [switch]$IncludeManualRequired,
    [string]$TalosPath = "",
    [string]$WorkspaceRoot = "local/manual-workspaces/talosbench",
    [string]$TranscriptRoot = "local/manual-testing/talosbench"
)

$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $script:RepoRoot $PathValue))
}

function Get-NotePropertyNames {
    param($Object)
    if ($null -eq $Object) { return @() }
    return @($Object.PSObject.Properties | Where-Object { $_.MemberType -eq "NoteProperty" } | ForEach-Object { $_.Name })
}

function Write-FixtureFile {
    param(
        [string]$Workspace,
        [string]$RelativePath,
        [string]$Content
    )
    $target = [System.IO.Path]::GetFullPath((Join-Path $Workspace $RelativePath))
    $workspaceFull = [System.IO.Path]::GetFullPath($Workspace)
    if (-not $target.StartsWith($workspaceFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Fixture path escapes workspace: $RelativePath"
    }
    $parent = Split-Path -Parent $target
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    Set-Content -LiteralPath $target -Value $Content -Encoding UTF8 -NoNewline
}

function Initialize-Workspace {
    param($Case, [string]$Workspace)
    $workspaceFull = [System.IO.Path]::GetFullPath($Workspace)
    $rootFull = [System.IO.Path]::GetFullPath($script:WorkspaceRootFull)
    if (-not $workspaceFull.StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to reset workspace outside TalosBench root: $workspace"
    }
    if (Test-Path -LiteralPath $workspaceFull) {
        Remove-Item -LiteralPath $workspaceFull -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $workspaceFull | Out-Null

    $files = $Case.workspaceFixture.files
    foreach ($name in Get-NotePropertyNames $files) {
        Write-FixtureFile -Workspace $workspaceFull -RelativePath $name -Content ([string]$files.$name)
    }
}

function Get-CaseById {
    param($Cases, [string]$Id)
    return $Cases | Where-Object { $_.id -eq $Id } | Select-Object -First 1
}

function Expand-CaseIds {
    param([string[]]$Ids)
    $expanded = @()
    foreach ($raw in @($Ids)) {
        if ([string]::IsNullOrWhiteSpace($raw)) { continue }
        foreach ($part in $raw.Split(",")) {
            if (-not [string]::IsNullOrWhiteSpace($part)) {
                $expanded += $part.Trim()
            }
        }
    }
    return $expanded
}

function Test-Substrings {
    param(
        [string]$Text,
        [string[]]$Required,
        [string[]]$Forbidden
    )
    $missing = @()
    foreach ($item in $Required) {
        if ([string]::IsNullOrWhiteSpace($item)) { continue }
        if ($Text.IndexOf($item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $missing += $item
        }
    }

    $foundForbidden = @()
    foreach ($item in $Forbidden) {
        if ([string]::IsNullOrWhiteSpace($item)) { continue }
        if ($Text.IndexOf($item, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            $foundForbidden += $item
        }
    }

    return [pscustomobject]@{
        MissingRequired = $missing
        FoundForbidden = $foundForbidden
    }
}

function Get-LastRegexValue {
    param([string]$Text, [string]$Pattern)
    $matches = [regex]::Matches($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if ($matches.Count -eq 0) { return "" }
    return $matches[$matches.Count - 1].Groups[1].Value.Trim()
}

function Get-TraceFacts {
    param([string]$Text)
    $contractLine = Get-LastRegexValue -Text $Text -Pattern "(?m)^\s*Contract:\s+(.+)$"
    $contract = ""
    $mutationAllowed = ""
    if (-not [string]::IsNullOrWhiteSpace($contractLine)) {
        $parts = $contractLine -split "\s+"
        if ($parts.Count -gt 0) { $contract = $parts[0] }
        $mutationMatch = [regex]::Match($contractLine, "mutationAllowed=(true|false)", [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($mutationMatch.Success) { $mutationAllowed = $mutationMatch.Groups[1].Value.ToLowerInvariant() }
    }

    return [pscustomobject]@{
        Contract = $contract
        MutationAllowed = $mutationAllowed
        Phase = Get-LastRegexValue -Text $Text -Pattern "(?m)^\s*Phase:\s+(.+)$"
        NativeTools = Get-LastRegexValue -Text $Text -Pattern "(?m)^\s*Native tools:\s+(.+)$"
        Blocked = Get-LastRegexValue -Text $Text -Pattern "(?m)^\s*Blocked:\s+(.+)$"
        Outcome = Get-LastRegexValue -Text $Text -Pattern "(?m)^\s*Outcome:\s+(.+)$"
        Checkpoint = Get-LastRegexValue -Text $Text -Pattern "(?m)^\s*Checkpoint:\s+(.+)$"
        Verification = Get-LastRegexValue -Text $Text -Pattern "(?m)^\s*Verification:\s+(.+)$"
        Repair = Get-LastRegexValue -Text $Text -Pattern "(?m)^\s*Repair:\s+(.+)$"
    }
}

function Get-AssertionArray {
    param($Assertions, [string]$Name)
    if ($null -eq $Assertions) { return @() }
    if (-not ($Assertions.PSObject.Properties.Name -contains $Name)) { return @() }
    return @($Assertions.$Name | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
}

function Test-TraceAssertions {
    param([string]$Text, $Assertions)
    $failures = @()
    if ($null -eq $Assertions) { return $failures }

    $facts = Get-TraceFacts -Text $Text

    if ($Assertions.PSObject.Properties.Name -contains "contract") {
        if ($facts.Contract -ne [string]$Assertions.contract) {
            $failures += "trace contract expected '$($Assertions.contract)' but was '$($facts.Contract)'"
        }
    }
    if ($Assertions.PSObject.Properties.Name -contains "mutationAllowed") {
        $expected = ([bool]$Assertions.mutationAllowed).ToString().ToLowerInvariant()
        if ($facts.MutationAllowed -ne $expected) {
            $failures += "trace mutationAllowed expected '$expected' but was '$($facts.MutationAllowed)'"
        }
    }

    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "phaseIncludes") {
        if ($facts.Phase.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "trace phase missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "nativeToolsContains") {
        if ($facts.NativeTools.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "trace nativeTools missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "nativeToolsExcludes") {
        if ($facts.NativeTools.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            $failures += "trace nativeTools unexpectedly contained '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "blockedContains") {
        if ($facts.Blocked.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "trace blocked missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "outcomeContains") {
        if ($facts.Outcome.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "trace outcome missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "checkpointContains") {
        if ($facts.Checkpoint.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "trace checkpoint missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "verificationContains") {
        if ($facts.Verification.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "trace verification missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "repairContains") {
        if ($facts.Repair.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "trace repair missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "transcriptContains") {
        if ($Text.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "transcript missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "transcriptExcludes") {
        if ($Text.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            $failures += "transcript unexpectedly contained '$item'"
        }
    }

    return $failures
}

function Get-TalosPath {
    if (-not [string]::IsNullOrWhiteSpace($TalosPath)) {
        return [System.IO.Path]::GetFullPath($TalosPath)
    }
    if (-not [string]::IsNullOrWhiteSpace($env:TALOS_PATH)) {
        return [System.IO.Path]::GetFullPath($env:TALOS_PATH)
    }
    $default = Join-Path $env:LOCALAPPDATA "Programs/talos/bin/talos.bat"
    if (Test-Path -LiteralPath $default) {
        return [System.IO.Path]::GetFullPath($default)
    }
    $cmd = Get-Command talos -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    throw "Could not find installed Talos. Set -TalosPath or TALOS_PATH."
}

function Invoke-TalosCase {
    param($Case, [string]$RunRoot)

    $workspace = Join-Path $script:WorkspaceRootFull $Case.id
    Initialize-Workspace -Case $Case -Workspace $workspace

    $manualRequired = $Case.manualRequired -eq $true
    $transcript = Join-Path $RunRoot ($Case.id + ".txt")
    $relativeTranscript = Resolve-Path -LiteralPath $transcript -Relative -ErrorAction SilentlyContinue
    if (-not $relativeTranscript) {
        $relativeTranscript = $transcript
    }

    if ($manualRequired -and -not $IncludeManualRequired) {
        return [pscustomobject]@{
            Id = $Case.id
            Category = $Case.category
            Status = "MANUAL_REQUIRED"
            Blocker = "no"
            Transcript = ""
            Notes = "Skipped approval-sensitive case. Re-run with -IncludeManualRequired or follow README manual steps."
        }
    }

    $inputLines = New-Object System.Collections.Generic.List[string]
    $inputLines.Add("/session clear")
    $inputLines.Add("/debug trace")
    foreach ($prompt in @($Case.prompts)) {
        $inputLines.Add([string]$prompt)
        foreach ($approval in @($Case.approvalInputs)) {
            if (-not [string]::IsNullOrWhiteSpace($approval)) {
                $inputLines.Add([string]$approval)
            }
        }
    }
    $inputLines.Add("/last trace")
    $inputLines.Add("/q")

    $inputText = ($inputLines -join [Environment]::NewLine) + [Environment]::NewLine
    Push-Location $workspace
    try {
        $output = $inputText | & $script:TalosExe 2>&1
    } finally {
        Pop-Location
    }
    $text = ($output | Out-String)
    Set-Content -LiteralPath $transcript -Value $text -Encoding UTF8

    $required = @($Case.requiredOutputSubstrings | ForEach-Object { [string]$_ })
    $forbidden = @($Case.forbiddenOutputSubstrings | ForEach-Object { [string]$_ })
    $check = Test-Substrings -Text $text -Required $required -Forbidden $forbidden
    $traceFailures = @(Test-TraceAssertions -Text $text -Assertions $Case.traceAssertions)

    $status = "PASS"
    $blocker = "no"
    $notes = @()
    if ($check.MissingRequired.Count -gt 0) {
        $status = "FAIL"
        $notes += "Missing required: " + ($check.MissingRequired -join "; ")
    }
    if ($check.FoundForbidden.Count -gt 0) {
        $status = "BLOCKER"
        $blocker = "yes"
        $notes += "Found forbidden: " + ($check.FoundForbidden -join "; ")
    }
    if ($traceFailures.Count -gt 0) {
        if ($status -ne "BLOCKER") {
            $status = "FAIL"
        }
        $notes += "Trace assertion failed: " + ($traceFailures -join "; ")
    }
    if ($notes.Count -eq 0) {
        $notes += $Case.notes
    }

    return [pscustomobject]@{
        Id = $Case.id
        Category = $Case.category
        Status = $status
        Blocker = $blocker
        Transcript = $relativeTranscript
        Notes = ($notes -join " ")
    }
}

function Escape-MarkdownCell {
    param([string]$Value)
    if ($null -eq $Value) { return "" }
    return $Value.Replace("|", "\|").Replace("`r", " ").Replace("`n", " ")
}

$script:RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../.."))
if ([string]::IsNullOrWhiteSpace($CasesPath)) {
    $CasesPath = Join-Path $PSScriptRoot "talosbench-cases.json"
}
$casesFullPath = Resolve-RepoPath $CasesPath
$script:WorkspaceRootFull = Resolve-RepoPath $WorkspaceRoot
$transcriptRootFull = Resolve-RepoPath $TranscriptRoot

if (-not (Test-Path -LiteralPath $casesFullPath)) {
    throw "Cases file not found: $casesFullPath"
}

$caseConfig = Get-Content -LiteralPath $casesFullPath -Raw | ConvertFrom-Json
$cases = @($caseConfig.cases)

if ($ListCases) {
    $cases | Sort-Object id | Select-Object id, category, manualRequired, notes | Format-Table -AutoSize
    exit 0
}

if ($ValidateOnly) {
    $ids = New-Object System.Collections.Generic.HashSet[string]
    foreach ($case in $cases) {
        foreach ($field in @("id", "category", "workspaceFixture", "prompts", "expectedContract", "expectedToolsAllowed", "forbiddenOutputSubstrings", "requiredOutputSubstrings", "blockerConditions", "notes")) {
            if (-not ($case.PSObject.Properties.Name -contains $field)) {
                throw "Case '$($case.id)' is missing required field '$field'."
            }
        }
        if ($case.PSObject.Properties.Name -contains "traceAssertions") {
            $allowedAssertions = @(
                "contract",
                "mutationAllowed",
                "phaseIncludes",
                "nativeToolsContains",
                "nativeToolsExcludes",
                "blockedContains",
                "outcomeContains",
                "checkpointContains",
                "verificationContains",
                "repairContains",
                "transcriptContains",
                "transcriptExcludes"
            )
            foreach ($assertionName in Get-NotePropertyNames $case.traceAssertions) {
                if ($allowedAssertions -notcontains $assertionName) {
                    throw "Case '$($case.id)' has unknown trace assertion '$assertionName'."
                }
            }
        }
        if (-not $ids.Add([string]$case.id)) {
            throw "Duplicate case id: $($case.id)"
        }
    }
    Write-Output "Validated $($cases.Count) TalosBench case(s)."
    exit 0
}

$expandedCaseIds = @(Expand-CaseIds -Ids $CaseId)
$selected = @()
if ($expandedCaseIds.Count -gt 0) {
    foreach ($id in $expandedCaseIds) {
        $case = Get-CaseById -Cases $cases -Id $id
        if ($null -eq $case) {
            throw "Unknown TalosBench case id: $id"
        }
        $selected += $case
    }
} else {
    $selected = $cases
}

$script:TalosExe = Get-TalosPath
New-Item -ItemType Directory -Force -Path $script:WorkspaceRootFull | Out-Null
New-Item -ItemType Directory -Force -Path $transcriptRootFull | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runRoot = Join-Path $transcriptRootFull $timestamp
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null

$results = @()
foreach ($case in $selected) {
    Write-Host "Running TalosBench case: $($case.id)"
    $results += Invoke-TalosCase -Case $case -RunRoot $runRoot
}

$summary = Join-Path $runRoot "summary.md"
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# TalosBench Run Summary")
$lines.Add("")
$lines.Add("- Timestamp: $timestamp")
$lines.Add("- Talos path: $script:TalosExe")
$lines.Add("- Cases file: $casesFullPath")
$lines.Add("- Workspace root: $script:WorkspaceRootFull")
$lines.Add("- Transcript root: $runRoot")
$lines.Add("")
$lines.Add("| Case id | Status | Category | Blocker? | Transcript | Notes |")
$lines.Add("| --- | --- | --- | --- | --- | --- |")
foreach ($result in $results) {
    $lines.Add("| $(Escape-MarkdownCell $result.Id) | $(Escape-MarkdownCell $result.Status) | $(Escape-MarkdownCell $result.Category) | $(Escape-MarkdownCell $result.Blocker) | $(Escape-MarkdownCell $result.Transcript) | $(Escape-MarkdownCell $result.Notes) |")
}
Set-Content -LiteralPath $summary -Value $lines -Encoding UTF8

$results | Format-Table Id, Status, Category, Blocker, Transcript -AutoSize
Write-Output "Summary: $summary"

if ($results | Where-Object { $_.Status -eq "BLOCKER" }) {
    exit 2
}
if ($results | Where-Object { $_.Status -eq "FAIL" }) {
    exit 1
}
