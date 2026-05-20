param(
    [string]$CasesPath = "",
    [string[]]$CaseId = @(),
    [switch]$ListCases,
    [switch]$ValidateOnly,
    [switch]$SelfTest,
    [switch]$IncludeManualRequired,
    [switch]$AllowPipedApprovalInputs,
    [switch]$StrictEvidence,
    [string]$AuditId = "",
    [string]$ModelLabel = "",
    [string]$Lane = "",
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

function Test-ExpectedFinalFiles {
    param($Case, [string]$Workspace)

    if (-not ($Case.PSObject.Properties.Name -contains "expectedFinalFiles")) {
        return @()
    }
    $workspaceFull = [System.IO.Path]::GetFullPath($Workspace)
    $failures = @()
    foreach ($name in Get-NotePropertyNames $Case.expectedFinalFiles) {
        $target = [System.IO.Path]::GetFullPath((Join-Path $workspaceFull $name))
        if (-not $target.StartsWith($workspaceFull, [System.StringComparison]::OrdinalIgnoreCase)) {
            $failures += "expected final file path escapes workspace: $name"
            continue
        }
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
            $failures += "expected final file missing: $name"
            continue
        }
        $actual = [System.IO.File]::ReadAllText($target)
        $expected = [string]$Case.expectedFinalFiles.$name
        if ($actual -ne $expected) {
            $failures += "expected final file content mismatch: $name"
        }
    }
    return @($failures)
}

function Test-ExpectedFinalFilePaths {
    param($Case, [string]$Workspace)

    if (-not ($Case.PSObject.Properties.Name -contains "expectedFinalFilePaths")) {
        return @()
    }
    $workspaceFull = [System.IO.Path]::GetFullPath($Workspace)
    $failures = @()
    foreach ($raw in @($Case.expectedFinalFilePaths)) {
        $name = [string]$raw
        if ([string]::IsNullOrWhiteSpace($name)) { continue }
        $target = [System.IO.Path]::GetFullPath((Join-Path $workspaceFull $name))
        if (-not $target.StartsWith($workspaceFull, [System.StringComparison]::OrdinalIgnoreCase)) {
            $failures += "expected final file path escapes workspace: $name"
            continue
        }
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
            $failures += "expected final file missing: $name"
        }
    }
    return @($failures)
}

function Get-CaseApprovalInputs {
    param($Case)

    $inputs = New-Object System.Collections.Generic.List[string]
    if ($Case.PSObject.Properties.Name -contains "approvalInputsByPrompt") {
        foreach ($entry in @($Case.approvalInputsByPrompt)) {
            foreach ($approval in @($entry)) {
                if (-not [string]::IsNullOrWhiteSpace($approval)) {
                    [void]$inputs.Add(([string]$approval).Trim())
                }
            }
        }
    }
    if ($Case.PSObject.Properties.Name -contains "approvalInputs") {
        foreach ($approval in @($Case.approvalInputs)) {
            if (-not [string]::IsNullOrWhiteSpace($approval)) {
                [void]$inputs.Add(([string]$approval).Trim())
            }
        }
    }
    return @($inputs | Select-Object -Unique)
}

function Get-TalosBenchManualExecutionGate {
    param(
        $Case,
        [bool]$IncludeManualRequiredFlag,
        [bool]$AllowPipedApprovalInputsFlag
    )

    $manualRequired = $Case.manualRequired -eq $true
    if (-not $manualRequired) {
        return [pscustomobject]@{
            Status = "RUN"
            Notes = ""
        }
    }

    if (-not $IncludeManualRequiredFlag) {
        return [pscustomobject]@{
            Status = "MANUAL_REQUIRED"
            Notes = "Skipped approval-sensitive case. Re-run with -IncludeManualRequired and a synchronized runner, or explicitly opt into piped approval input for exploratory evidence."
        }
    }

    $approvalInputs = @(Get-CaseApprovalInputs -Case $Case)
    if ($approvalInputs.Count -gt 0 -and -not $AllowPipedApprovalInputsFlag) {
        return [pscustomobject]@{
            Status = "SYNC_REQUIRED"
            Notes = "Refusing to pre-feed approval input through redirected stdin. Use the synchronized approval runner for release evidence, or pass -AllowPipedApprovalInputs only for exploratory non-synchronized runs."
        }
    }

    return [pscustomobject]@{
        Status = "RUN"
        Notes = ""
    }
}

function Get-TalosBenchLane {
    param($Case)

    if (-not [string]::IsNullOrWhiteSpace($Lane)) {
        return $Lane
    }
    if ($Case.PSObject.Properties.Name -contains "lane") {
        $configured = [string]$Case.lane
        if (-not [string]::IsNullOrWhiteSpace($configured)) {
            return $configured
        }
    }

    $manualRequired = $Case.manualRequired -eq $true
    $approvalInputs = @(Get-CaseApprovalInputs -Case $Case)
    if ($manualRequired -and $approvalInputs.Count -gt 0) {
        return "SYNC_APPROVAL"
    }
    if ($manualRequired) {
        return "TRUE_PTY_MANUAL"
    }
    return "SAFE_REDIRECTED_STDIN"
}

function Test-ApprovalInputDrift {
    param($Case, [string]$Transcript)

    $approvalInputs = @(Get-CaseApprovalInputs -Case $Case)
    if ($approvalInputs.Count -eq 0) {
        return @()
    }

    $failures = @()
    $clean = Remove-AnsiSequences -Text $Transcript
    foreach ($approval in $approvalInputs) {
        $escaped = [regex]::Escape($approval)
        $pattern = "(?m)^\s*User Request\s*\r?\n\s+$escaped\s*$"
        if ([regex]::IsMatch($clean, $pattern)) {
            $failures += "scripted approval input '$approval' was consumed as a user turn; approval prompt likely did not appear before the runner sent input"
        }
    }
    return @($failures)
}

function Get-LastRegexValue {
    param([string]$Text, [string]$Pattern, [switch]$CaseSensitive)
    $options = if ($CaseSensitive) {
        [System.Text.RegularExpressions.RegexOptions]::None
    } else {
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    }
    $matches = [regex]::Matches($Text, $Pattern, $options)
    if ($matches.Count -eq 0) { return "" }
    return $matches[$matches.Count - 1].Groups[1].Value.Trim()
}

function Get-CheckpointIdFromText {
    param([string]$Text)
    $clean = Remove-AnsiSequences -Text $Text
    $matches = [regex]::Matches(
        $clean,
        "chk-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    if ($matches.Count -eq 0) { return "" }
    return $matches[$matches.Count - 1].Value
}

function Remove-AnsiSequences {
    param([string]$Text)
    if ($null -eq $Text) { return "" }
    return [regex]::Replace($Text, "`e\[[0-?]*[ -/]*[@-~]", "")
}

function Get-TraceSection {
    param(
        [string]$Text,
        [string[]]$HeaderNames
    )

    $clean = Remove-AnsiSequences -Text $Text
    $lines = $clean -split "`r?`n"
    $sectionHeaders = @(
        "Current Turn Trace",
        "Last Turn Trace Detail",
        "Trace Detail",
        "Local Trace",
        "Events"
    )

    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $trimmed = $lines[$i].Trim()
        foreach ($header in $HeaderNames) {
            if ($trimmed -eq $header -or $trimmed.EndsWith("> $header", [System.StringComparison]::OrdinalIgnoreCase)) {
                $start = $i
            }
        }
    }
    if ($start -lt 0) { return "" }

    $buffer = New-Object System.Collections.Generic.List[string]
    for ($i = $start + 1; $i -lt $lines.Count; $i++) {
        $trimmed = $lines[$i].Trim()
        if (($sectionHeaders -contains $trimmed) -and -not ($HeaderNames -contains $trimmed)) {
            break
        }
        [void]$buffer.Add($lines[$i])
    }
    return ($buffer -join "`n")
}

function Get-TraceFacts {
    param([string]$Text)
    $cleanText = Remove-AnsiSequences -Text $Text
    $traceDetail = Get-TraceSection -Text $cleanText -HeaderNames @("Trace Detail", "Last Turn Trace Detail", "Current Turn Trace")
    if ([string]::IsNullOrWhiteSpace($traceDetail)) {
        $traceDetail = $cleanText
    }
    $localTrace = Get-TraceSection -Text $cleanText -HeaderNames @("Local Trace")
    $promptAudit = Get-TraceSection -Text $localTrace -HeaderNames @("Prompt Audit")
    if ([string]::IsNullOrWhiteSpace($promptAudit)) {
        $promptAudit = Get-TraceSection -Text $cleanText -HeaderNames @("Prompt Audit")
    }

    $contractLine = Get-LastRegexValue -Text $traceDetail -Pattern "(?m)^\s*Contract:\s+(.+)$" -CaseSensitive
    $contract = ""
    $mutationAllowed = ""
    if (-not [string]::IsNullOrWhiteSpace($contractLine)) {
        $parts = $contractLine -split "\s+"
        if ($parts.Count -gt 0) { $contract = $parts[0] }
        $mutationMatch = [regex]::Match($contractLine, "mutationAllowed=(true|false)", [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($mutationMatch.Success) { $mutationAllowed = $mutationMatch.Groups[1].Value.ToLowerInvariant() }
    }
    $currentTurnFrame = Get-LastRegexValue -Text $promptAudit -Pattern "(?m)^\s*currentTurnFrame:\s+(.+)$"
    $framePreview = Get-LastRegexValue -Text $promptAudit -Pattern "(?m)^\s*framePreview:\s+(.+)$"
    if (-not [string]::IsNullOrWhiteSpace($framePreview)) {
        $currentTurnFrame = "$currentTurnFrame $framePreview".Trim()
    }
    $classificationReason = Get-LastRegexValue -Text $traceDetail -Pattern "(?m)^\s*(?:Classification reason|classificationReason):\s+(.+)$" -CaseSensitive
    if ([string]::IsNullOrWhiteSpace($classificationReason)) {
        $classificationReason = Get-LastRegexValue -Text $localTrace -Pattern "(?m)^\s*Classification reason:\s+(.+)$" -CaseSensitive
    }

    $traceOutcome = Get-LastRegexValue -Text $traceDetail -Pattern "(?m)^\s*Outcome:\s+(.+)$" -CaseSensitive
    $localTraceOutcome = Get-LastRegexValue -Text $localTrace -Pattern "(?m)^\s*Outcome:\s+(.+)$" -CaseSensitive
    $fallbackOutcome = Get-LastRegexValue -Text $cleanText -Pattern "(?m)^\s*Outcome:\s+(.+)$" -CaseSensitive
    $outcome = $localTraceOutcome
    if ([string]::IsNullOrWhiteSpace($outcome)) { $outcome = $traceOutcome }
    if ([string]::IsNullOrWhiteSpace($outcome)) { $outcome = $fallbackOutcome }

    $traceVerification = Get-LastRegexValue -Text $traceDetail -Pattern "(?m)^\s*Verification:\s+(.+)$" -CaseSensitive
    $localTraceVerification = Get-LastRegexValue -Text $localTrace -Pattern "(?m)^\s*Verification:\s+(.+)$" -CaseSensitive
    $verification = $localTraceVerification
    if ([string]::IsNullOrWhiteSpace($verification)) { $verification = $traceVerification }
    $traceCheckpoint = Get-LastRegexValue -Text $traceDetail -Pattern "(?m)^\s*Checkpoint:\s+(.+)$" -CaseSensitive
    $localTraceCheckpoint = Get-LastRegexValue -Text $localTrace -Pattern "(?m)^\s*Checkpoint:\s+(.+)$" -CaseSensitive
    $checkpoint = $traceCheckpoint
    if ([string]::IsNullOrWhiteSpace($checkpoint)) { $checkpoint = $localTraceCheckpoint }

    return [pscustomobject]@{
        Contract = $contract
        MutationAllowed = $mutationAllowed
        ClassificationReason = $classificationReason
        Phase = Get-LastRegexValue -Text $traceDetail -Pattern "(?m)^\s*Phase:\s+(.+)$" -CaseSensitive
        NativeTools = Get-LastRegexValue -Text $traceDetail -Pattern "(?m)^\s*Native tools:\s+(.+)$" -CaseSensitive
        Blocked = Get-LastRegexValue -Text $traceDetail -Pattern "(?m)^\s*Blocked:\s+(.+)$" -CaseSensitive
        Outcome = $outcome
        LocalTraceOutcome = $localTraceOutcome
        Checkpoint = $checkpoint
        Verification = $verification
        LocalTraceVerification = $localTraceVerification
        Repair = Get-LastRegexValue -Text $traceDetail -Pattern "(?m)^\s*Repair:\s+(.+)$" -CaseSensitive
        PromptAuditTaskType = Get-LastRegexValue -Text $promptAudit -Pattern "(?m)^\s*taskType:\s+([A-Z_]+).*$"
        PromptAuditActionObligation = Get-LastRegexValue -Text $promptAudit -Pattern "(?m)^\s*actionObligation:\s+(.+)$"
        PromptAuditEvidenceObligation = Get-LastRegexValue -Text $promptAudit -Pattern "(?m)^\s*evidenceObligation:\s+(.+)$"
        PromptAuditActiveTaskContext = Get-LastRegexValue -Text $promptAudit -Pattern "(?m)^\s*activeTaskContext:\s+(.+)$"
        PromptAuditArtifactGoal = Get-LastRegexValue -Text $promptAudit -Pattern "(?m)^\s*artifactGoal:\s+(.+)$"
        PromptAuditCurrentTurnFrame = $currentTurnFrame
        PromptAuditHistory = Get-LastRegexValue -Text $promptAudit -Pattern "(?m)^\s*history:\s+(.+)$"
        PromptAuditRedaction = Get-LastRegexValue -Text $promptAudit -Pattern "(?m)^\s*redaction:\s+(.+)$"
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
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "classificationReasonContains") {
        if ($facts.ClassificationReason.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "trace classificationReason missing '$item'"
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
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "outcomeExcludes") {
        if ($facts.Outcome.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            $failures += "trace outcome unexpectedly contained '$item'"
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
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "verificationExcludes") {
        if ($facts.Verification.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            $failures += "trace verification unexpectedly contained '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "localTraceOutcomeContains") {
        if ($facts.LocalTraceOutcome.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "local trace outcome missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "localTraceOutcomeExcludes") {
        if ($facts.LocalTraceOutcome.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            $failures += "local trace outcome unexpectedly contained '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "localTraceVerificationContains") {
        if ($facts.LocalTraceVerification.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "local trace verification missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "localTraceVerificationExcludes") {
        if ($facts.LocalTraceVerification.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            $failures += "local trace verification unexpectedly contained '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "repairContains") {
        if ($facts.Repair.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "trace repair missing '$item'"
        }
    }
    if ($Assertions.PSObject.Properties.Name -contains "promptAuditTaskType") {
        if ($facts.PromptAuditTaskType -ne [string]$Assertions.promptAuditTaskType) {
            $failures += "prompt audit taskType expected '$($Assertions.promptAuditTaskType)' but was '$($facts.PromptAuditTaskType)'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "promptAuditActionObligationContains") {
        if ($facts.PromptAuditActionObligation.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "prompt audit actionObligation missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "promptAuditEvidenceObligationContains") {
        if ($facts.PromptAuditEvidenceObligation.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "prompt audit evidenceObligation missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "promptAuditActiveTaskContextContains") {
        if ($facts.PromptAuditActiveTaskContext.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "prompt audit activeTaskContext missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "promptAuditArtifactGoalContains") {
        if ($facts.PromptAuditArtifactGoal.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "prompt audit artifactGoal missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "promptAuditCurrentTurnFrameContains") {
        if ($facts.PromptAuditCurrentTurnFrame.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "prompt audit currentTurnFrame missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "promptAuditHistoryContains") {
        if ($facts.PromptAuditHistory.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "prompt audit history missing '$item'"
        }
    }
    foreach ($item in Get-AssertionArray -Assertions $Assertions -Name "promptAuditRedactionContains") {
        if ($facts.PromptAuditRedaction.IndexOf([string]$item, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failures += "prompt audit redaction missing '$item'"
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

function Test-TranscriptHasLastTrace {
    param([string]$Transcript)
    $clean = Remove-AnsiSequences -Text $Transcript
    return (
        $clean.Contains("Last Turn Trace Detail") -or
        $clean.Contains("Trace Detail") -or
        $clean.Contains("Current Turn Trace")
    )
}

function Get-LastNaturalTurnBlock {
    param([string]$Text)

    $clean = Remove-AnsiSequences -Text $Text
    if ([string]::IsNullOrWhiteSpace($clean)) { return "" }

    $traceMatches = [regex]::Matches($clean, "(?m)^Current Turn Trace\s*$")
    if ($traceMatches.Count -eq 0) { return "" }
    $lastTraceStart = $traceMatches[$traceMatches.Count - 1].Index

    $promptMatches = [regex]::Matches($clean, "(?m)^talos \[[^\]]+\] >")
    $start = 0
    foreach ($match in $promptMatches) {
        if ($match.Index -lt $lastTraceStart) {
            $start = $match.Index
        } else {
            break
        }
    }

    $end = $clean.Length
    foreach ($match in $promptMatches) {
        if ($match.Index -gt $lastTraceStart) {
            $end = $match.Index
            break
        }
    }

    if ($end -le $start) { return "" }
    return $clean.Substring($start, $end - $start).Trim()
}

function New-TalosBenchInputLines {
    param(
        $Case,
        [int]$StartPromptIndex = 0,
        [int]$EndPromptIndex = -1,
        [hashtable]$Replacements = @{},
        [bool]$IncludeSessionClear = $true,
        [bool]$IncludeLastTrace = $true,
        [bool]$StrictEvidence = $false,
        [string]$CaseArtifactRoot = ""
    )

    if ($StrictEvidence -and [string]::IsNullOrWhiteSpace($CaseArtifactRoot)) {
        throw "Strict evidence input generation requires a case artifact root."
    }

    $inputLines = New-Object System.Collections.Generic.List[string]
    if ($IncludeSessionClear) {
        $inputLines.Add("/session clear")
    }
    if ($StrictEvidence) {
        $inputLines.Add("/debug prompt on")
    } else {
        $inputLines.Add("/debug trace")
    }
    $prompts = @($Case.prompts)
    $hasPromptApprovals = $Case.PSObject.Properties.Name -contains "approvalInputsByPrompt"
    $promptApprovals = if ($hasPromptApprovals) { @($Case.approvalInputsByPrompt) } else { @() }
    if ($EndPromptIndex -lt 0 -or $EndPromptIndex -ge $prompts.Count) {
        $EndPromptIndex = $prompts.Count - 1
    }
    for ($promptIndex = $StartPromptIndex; $promptIndex -le $EndPromptIndex; $promptIndex++) {
        $prompt = [string]$prompts[$promptIndex]
        foreach ($key in $Replacements.Keys) {
            $prompt = $prompt.Replace([string]$key, [string]$Replacements[$key])
        }
        $inputLines.Add($prompt)
        $approvals = if ($hasPromptApprovals) {
            if ($promptIndex -lt $promptApprovals.Count) {
                @($promptApprovals[$promptIndex])
            } else {
                @()
            }
        } else {
            @($Case.approvalInputs)
        }
        foreach ($approval in $approvals) {
            if (-not [string]::IsNullOrWhiteSpace($approval)) {
                $inputLines.Add([string]$approval)
            }
        }
        if ($StrictEvidence -and $IncludeLastTrace) {
            $promptArtifactRoot = Join-Path $CaseArtifactRoot ("prompt-{0:D3}" -f ($promptIndex + 1))
            $promptDebugRoot = Join-Path $promptArtifactRoot "prompt-debug"
            $inputLines.Add("/last trace")
            $inputLines.Add('/prompt-debug save "' + $promptDebugRoot + '"')
            $inputLines.Add("/session save")
        }
    }
    if ((-not $StrictEvidence) -and $IncludeLastTrace) {
        $inputLines.Add("/last trace")
        $inputLines.Add("/last trace")
        $inputLines.Add("/last trace")
    }
    $inputLines.Add("/q")
    return @($inputLines)
}

function Assert-TalosBenchEqual {
    param(
        [string]$Name,
        [object]$Expected,
        [object]$Actual
    )

    if ($Expected -ne $Actual) {
        throw "Self-test failed: $Name expected '$Expected' but got '$Actual'."
    }
}

function Assert-TalosBenchContains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Needle
    )

    if ($Text.IndexOf($Needle, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "Self-test failed: $Name did not contain '$Needle'."
    }
}

function Get-TalosBenchSelfTestCases {
    $path = if ([string]::IsNullOrWhiteSpace($CasesPath)) {
        Join-Path $PSScriptRoot "talosbench-cases.json"
    } else {
        Resolve-RepoPath $CasesPath
    }
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Self-test failed: cases file not found: $path"
    }
    return (Get-Content -LiteralPath $path -Raw | ConvertFrom-Json).cases
}

function Assert-TalosBenchLiteralPromptTransport {
    $literalCase = Get-CaseById -Cases @(Get-TalosBenchSelfTestCases) -Id "t61-literal-readme-write-after-retry"
    if ($null -eq $literalCase) {
        throw "Self-test failed: missing t61-literal-readme-write-after-retry case."
    }

    foreach ($prompt in @($literalCase.prompts)) {
        if (([string]$prompt).Contains("`r") -or ([string]$prompt).Contains("`n")) {
            throw "Self-test failed: literal README audit prompt contains physical newlines and can be split by the REPL."
        }
    }

    $scriptedText = (@(New-TalosBenchInputLines -Case $literalCase) -join [Environment]::NewLine) + [Environment]::NewLine
    $physicalLines = @($scriptedText -split "`r?`n")
    foreach ($payloadLine in @("T61 exact README", "Line two")) {
        if ($physicalLines -contains $payloadLine) {
            throw "Self-test failed: literal README payload line '$payloadLine' would be submitted as an independent REPL turn."
        }
    }

    $payloadPrompts = @($physicalLines | Where-Object {
            $_.IndexOf("T61 exact README", [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and
            $_.IndexOf("Line two", [System.StringComparison]::OrdinalIgnoreCase) -ge 0
        })
    Assert-TalosBenchEqual -Name "literal README payload prompt count" -Expected @($literalCase.prompts).Count -Actual $payloadPrompts.Count
}

function Invoke-TalosBenchSelfTest {
    $traceFixture = @"
Trace Detail
  Contract: FILE_EDIT mutationAllowed=true verificationRequired=true
  Phase: initial=APPLY final=VERIFY
  Native tools: talos.write_file, talos.read_file
  Outcome: MUTATION_APPLIED
  Verification: PASSED

Local Trace
  Local trace: trc-self-test
  Prompt Audit
    taskType: FILE_EDIT mutationAllowed=true verificationRequired=true
    phase: APPLY
    evidenceObligation: FILE_SYSTEM_EVIDENCE_REQUIRED
    currentTurnFrame: injected
    framePreview: README.md
  Checkpoint: CREATED chk-self-test
  Verification: PASSED
  Outcome: OK (TURN_RECORDED)
"@
    $facts = Get-TraceFacts -Text $traceFixture
    Assert-TalosBenchEqual -Name "trace detail contract" -Expected "FILE_EDIT" -Actual $facts.Contract
    Assert-TalosBenchContains -Name "trace detail phase" -Text $facts.Phase -Needle "final=VERIFY"
    Assert-TalosBenchContains -Name "prompt audit evidence" -Text $facts.PromptAuditEvidenceObligation -Needle "FILE_SYSTEM_EVIDENCE_REQUIRED"
    Assert-TalosBenchContains -Name "prompt audit frame" -Text $facts.PromptAuditCurrentTurnFrame -Needle "README.md"
    Assert-TalosBenchContains -Name "local trace checkpoint" -Text $facts.Checkpoint -Needle "CREATED"
    Assert-TalosBenchContains -Name "local trace outcome" -Text $facts.LocalTraceOutcome -Needle "OK"

    $failedLocalTraceFixture = @"
Trace Detail
  Contract: FILE_EDIT mutationAllowed=true verificationRequired=true
  Outcome: MUTATION_APPLIED
  Verification: PASSED

Local Trace
  Outcome: FAILED (TURN_RECORD_FAILED)
"@
    $failedFacts = Get-TraceFacts -Text $failedLocalTraceFixture
    Assert-TalosBenchContains -Name "legacy outcome prefers local trace" -Text $failedFacts.Outcome -Needle "FAILED"
    Assert-TalosBenchContains -Name "failed local trace outcome" -Text $failedFacts.LocalTraceOutcome -Needle "FAILED"

    $approvalDriftCase = [pscustomobject]@{
        prompts = @("Create a folder named audit-output using talos.mkdir.")
        approvalInputsByPrompt = @(@("a"))
    }
    $approvalDriftTranscript = @"
talos [auto] > [Truth check: the model produced an invalid tool-call payload, so no action was taken.]

talos [auto] > The input seems incomplete. Could you please provide more details or clarify your request?

Current Turn Trace
  Contract: READ_ONLY_QA mutationAllowed=false verificationRequired=false

talos [auto] > Last Turn

User Request
  a
"@
    $approvalDriftFailures = @(Test-ApprovalInputDrift -Case $approvalDriftCase -Transcript $approvalDriftTranscript)
    Assert-TalosBenchEqual -Name "approval drift failure count" -Expected 1 -Actual $approvalDriftFailures.Count
    Assert-TalosBenchContains -Name "approval drift failure text" -Text $approvalDriftFailures[0] -Needle "consumed as a user turn"

    $approvalManualCase = [pscustomobject]@{
        id = "approval-sensitive-selftest"
        manualRequired = $true
        approvalInputsByPrompt = @(@("a"))
    }
    $skippedManualGate = Get-TalosBenchManualExecutionGate `
        -Case $approvalManualCase `
        -IncludeManualRequiredFlag:$false `
        -AllowPipedApprovalInputsFlag:$false
    Assert-TalosBenchEqual -Name "manual approval case skipped without include" `
        -Expected "MANUAL_REQUIRED" `
        -Actual $skippedManualGate.Status
    $blockedApprovalGate = Get-TalosBenchManualExecutionGate `
        -Case $approvalManualCase `
        -IncludeManualRequiredFlag:$true `
        -AllowPipedApprovalInputsFlag:$false
    Assert-TalosBenchEqual -Name "manual approval case requires synchronized runner by default" `
        -Expected "SYNC_REQUIRED" `
        -Actual $blockedApprovalGate.Status
    Assert-TalosBenchContains -Name "sync required explains piped approval risk" `
        -Text $blockedApprovalGate.Notes `
        -Needle "refusing to pre-feed approval input"
    $explicitPipedApprovalGate = Get-TalosBenchManualExecutionGate `
        -Case $approvalManualCase `
        -IncludeManualRequiredFlag:$true `
        -AllowPipedApprovalInputsFlag:$true
    Assert-TalosBenchEqual -Name "manual approval case can explicitly opt into piped approvals" `
        -Expected "RUN" `
        -Actual $explicitPipedApprovalGate.Status

    $multiTurnFixture = @"
talos [auto] > First response mentions talos.write_file as a future option.

Current Turn Trace
  contract: READ_ONLY_QA mutationAllowed=false verificationRequired=false

talos [auto] > Final response stays private and uses no workspace tools.

Current Turn Trace
  contract: SMALL_TALK mutationAllowed=false verificationRequired=false
  Native tools: none
  Prompt tools: none

talos [auto] > Last Turn
  Tool calls: 0
"@
    $lastNaturalTurn = Get-LastNaturalTurnBlock -Text $multiTurnFixture
    Assert-TalosBenchContains -Name "last natural turn includes final response" -Text $lastNaturalTurn -Needle "Final response stays private"
    if ($lastNaturalTurn.IndexOf("talos.write_file", [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "Self-test failed: last natural turn included prior-turn output."
    }

    $approvalCase = [pscustomobject]@{
        prompts = @(
            "Propose the smallest README.md edit.",
            "Apply that README.md change now."
        )
        approvalInputsByPrompt = @(
            @(),
            @("a")
        )
    }
    $lines = @(New-TalosBenchInputLines -Case $approvalCase)
    $approvalIndex = [array]::LastIndexOf($lines, "a")
    $lastTraceIndex = [array]::LastIndexOf($lines, "/last trace")
    $lastTraceCount = @($lines | Where-Object { $_ -eq "/last trace" }).Count
    Assert-TalosBenchEqual -Name "input line first" -Expected "/session clear" -Actual $lines[0]
    Assert-TalosBenchEqual -Name "input line second" -Expected "/debug trace" -Actual $lines[1]
    Assert-TalosBenchEqual -Name "approval appears after second prompt" -Expected "Apply that README.md change now." -Actual $lines[$approvalIndex - 1]
    if ($lastTraceIndex -le $approvalIndex) {
        throw "Self-test failed: /last trace appeared before the scripted approval input."
    }
    if ($lastTraceCount -lt 3) {
        throw "Self-test failed: fewer than three /last trace commands were appended."
    }
    Assert-TalosBenchEqual -Name "input line last" -Expected "/q" -Actual $lines[$lines.Count - 1]

    $strictArtifactRoot = Join-Path ([System.IO.Path]::GetTempPath()) "talosbench-strict-selftest"
    $strictLines = @(New-TalosBenchInputLines `
            -Case $approvalCase `
            -StrictEvidence:$true `
            -CaseArtifactRoot $strictArtifactRoot)
    Assert-TalosBenchEqual -Name "strict input line first" -Expected "/session clear" -Actual $strictLines[0]
    Assert-TalosBenchEqual -Name "strict input line second" -Expected "/debug prompt on" -Actual $strictLines[1]
    if (($strictLines | Where-Object { $_ -eq "/debug trace" }).Count -ne 0) {
        throw "Self-test failed: strict evidence mode used legacy /debug trace."
    }
    Assert-TalosBenchEqual -Name "strict last trace count" `
        -Expected @($approvalCase.prompts).Count `
        -Actual @(($strictLines | Where-Object { $_ -eq "/last trace" })).Count
    Assert-TalosBenchContains -Name "strict prompt one debug save" `
        -Text ($strictLines -join "`n") `
        -Needle ('/prompt-debug save "' + (Join-Path (Join-Path $strictArtifactRoot "prompt-001") "prompt-debug") + '"')
    Assert-TalosBenchContains -Name "strict prompt two debug save" `
        -Text ($strictLines -join "`n") `
        -Needle ('/prompt-debug save "' + (Join-Path (Join-Path $strictArtifactRoot "prompt-002") "prompt-debug") + '"')
    Assert-TalosBenchEqual -Name "strict session save count" `
        -Expected @($approvalCase.prompts).Count `
        -Actual @(($strictLines | Where-Object { $_ -eq "/session save" })).Count
    Assert-TalosBenchEqual -Name "strict input line last" -Expected "/q" -Actual $strictLines[$strictLines.Count - 1]
    Assert-TalosBenchLiteralPromptTransport

    $checkpointId = "chk-11111111-2222-3333-4444-555555555555"
    $checkpointText = "Checkpoints:`n  $checkpointId"
    Assert-TalosBenchEqual -Name "checkpoint id extraction" -Expected $checkpointId `
        -Actual (Get-CheckpointIdFromText -Text $checkpointText)

    $checkpointCase = [pscustomobject]@{
        prompts = @(
            "Overwrite index.html with exactly AFTER. Use talos.write_file.",
            "/checkpoint list",
            "/checkpoint restore <checkpoint-id>"
        )
        approvalInputsByPrompt = @(
            @("y"),
            @(),
            @("y")
        )
    }
    $firstPhase = @(New-TalosBenchInputLines -Case $checkpointCase -EndPromptIndex 1)
    Assert-TalosBenchEqual -Name "checkpoint phase one includes first approval" -Expected "y" `
        -Actual $firstPhase[3]
    if (($firstPhase -join "`n").Contains("<checkpoint-id>")) {
        throw "Self-test failed: checkpoint phase one included unresolved restore placeholder."
    }

    $secondPhase = @(New-TalosBenchInputLines -Case $checkpointCase `
            -StartPromptIndex 2 `
            -EndPromptIndex 2 `
            -IncludeSessionClear:$false `
            -IncludeLastTrace:$false `
            -Replacements @{"<checkpoint-id>" = $checkpointId})
    Assert-TalosBenchEqual -Name "checkpoint phase two starts debug" -Expected "/debug trace" `
        -Actual $secondPhase[0]
    Assert-TalosBenchContains -Name "checkpoint phase two substitutes id" `
        -Text ($secondPhase -join "`n") `
        -Needle "/checkpoint restore $checkpointId"
    if (($secondPhase -join "`n").Contains("<checkpoint-id>")) {
        throw "Self-test failed: checkpoint phase two kept unresolved restore placeholder."
    }
    if (($secondPhase | Where-Object { $_ -eq "/last trace" }).Count -ne 0) {
        throw "Self-test failed: checkpoint phase two should not append /last trace."
    }

    $expectedFilesRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("talosbench-selftest-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Force -Path $expectedFilesRoot | Out-Null
    try {
        Set-Content -LiteralPath (Join-Path $expectedFilesRoot "README.md") -Value "expected" -NoNewline
        $expectedFileCase = [pscustomobject]@{
            expectedFinalFiles = [pscustomobject]@{
                "README.md" = "expected"
            }
        }
        $fileFailures = @(Test-ExpectedFinalFiles -Case $expectedFileCase -Workspace $expectedFilesRoot)
        Assert-TalosBenchEqual -Name "expected final file success count" -Expected 0 -Actual $fileFailures.Count

        $wrongFileCase = [pscustomobject]@{
            expectedFinalFiles = [pscustomobject]@{
                "README.md" = "wrong"
            }
        }
        $wrongFailures = @(Test-ExpectedFinalFiles -Case $wrongFileCase -Workspace $expectedFilesRoot)
        Assert-TalosBenchEqual -Name "expected final file failure count" -Expected 1 -Actual $wrongFailures.Count

        $expectedPathCase = [pscustomobject]@{
            expectedFinalFilePaths = @("README.md")
        }
        $pathFailures = @(Test-ExpectedFinalFilePaths -Case $expectedPathCase -Workspace $expectedFilesRoot)
        Assert-TalosBenchEqual -Name "expected final file path success count" -Expected 0 -Actual $pathFailures.Count

        $missingPathCase = [pscustomobject]@{
            expectedFinalFilePaths = @("missing.py")
        }
        $missingPathFailures = @(Test-ExpectedFinalFilePaths -Case $missingPathCase -Workspace $expectedFilesRoot)
        Assert-TalosBenchEqual -Name "expected final file path missing count" -Expected 1 -Actual $missingPathFailures.Count
        Assert-TalosBenchContains -Name "expected final file path missing text" `
            -Text $missingPathFailures[0] `
            -Needle "expected final file missing: missing.py"
    } finally {
        Remove-Item -LiteralPath $expectedFilesRoot -Recurse -Force -ErrorAction SilentlyContinue
    }

    Write-Output "TalosBench self-test passed."
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

function Invoke-TalosProcess {
    param(
        [string[]]$InputLines,
        [string]$Workspace,
        [string]$InputCapturePath = ""
    )

    $inputText = ($InputLines -join [Environment]::NewLine) + [Environment]::NewLine
    if (-not [string]::IsNullOrWhiteSpace($InputCapturePath)) {
        $inputParent = Split-Path -Parent $InputCapturePath
        New-Item -ItemType Directory -Force -Path $inputParent | Out-Null
        Set-Content -LiteralPath $InputCapturePath -Value $inputText -Encoding UTF8 -NoNewline
    }
    Push-Location $Workspace
    try {
        $output = $inputText | & $script:TalosExe 2>&1
    } finally {
        Pop-Location
    }
    return ($output | Out-String)
}

function Invoke-GitText {
    param(
        [string]$Workspace,
        [string[]]$Arguments
    )
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        return "[git unavailable]"
    }
    $output = & git -C $Workspace @Arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        return "[git exit $LASTEXITCODE]`n$output"
    }
    return $output
}

function Initialize-StrictEvidenceGitBaseline {
    param([string]$Workspace, [string]$CaseArtifactRoot)

    $git = Get-Command git -ErrorAction SilentlyContinue
    if (-not $git) {
        Set-Content -LiteralPath (Join-Path $CaseArtifactRoot "git-baseline.txt") `
            -Value "git unavailable; workspace status/diff evidence will be best-effort." `
            -Encoding UTF8
        return
    }

    if (Test-Path -LiteralPath (Join-Path $Workspace ".git")) {
        return
    }

    $baseline = New-Object System.Collections.Generic.List[string]
    [void]$baseline.Add((Invoke-GitText -Workspace $Workspace -Arguments @("init")))
    [void]$baseline.Add((Invoke-GitText -Workspace $Workspace -Arguments @("add", "-A")))
    [void]$baseline.Add((Invoke-GitText -Workspace $Workspace -Arguments @(
                    "-c", "user.name=TalosBench",
                    "-c", "user.email=talosbench@example.invalid",
                    "commit", "-m", "TalosBench fixture baseline"
                )))
    Set-Content -LiteralPath (Join-Path $CaseArtifactRoot "git-baseline.txt") `
        -Value ($baseline -join [Environment]::NewLine) `
        -Encoding UTF8
}

function Save-StrictEvidenceWorkspaceSnapshot {
    param([string]$Workspace, [string]$CaseArtifactRoot)

    if (-not $StrictEvidence) {
        return
    }

    Set-Content -LiteralPath (Join-Path $CaseArtifactRoot "git-status.txt") `
        -Value (Invoke-GitText -Workspace $Workspace -Arguments @("status", "--short")) `
        -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $CaseArtifactRoot "git-diff.txt") `
        -Value (Invoke-GitText -Workspace $Workspace -Arguments @("diff", "--", ".")) `
        -Encoding UTF8
}

function Get-CheckpointPlaceholderPromptIndex {
    param($Case)

    $prompts = @($Case.prompts)
    for ($i = 0; $i -lt $prompts.Count; $i++) {
        if (([string]$prompts[$i]).Contains("<checkpoint-id>")) {
            return $i
        }
    }
    return -1
}

function Invoke-TalosCaseTranscript {
    param($Case, [string]$Workspace, [string]$CaseArtifactRoot = "")

    $checkpointPromptIndex = Get-CheckpointPlaceholderPromptIndex -Case $Case
    if ($checkpointPromptIndex -lt 0) {
        return Invoke-TalosProcess `
            -InputLines @(New-TalosBenchInputLines `
                -Case $Case `
                -StrictEvidence:$StrictEvidence.IsPresent `
                -CaseArtifactRoot $CaseArtifactRoot) `
            -Workspace $Workspace `
            -InputCapturePath $(if ($StrictEvidence) { Join-Path $CaseArtifactRoot "input.txt" } else { "" })
    }
    if ($checkpointPromptIndex -eq 0) {
        throw "Case '$($Case.id)' cannot resolve <checkpoint-id> in the first prompt."
    }

    $firstPhase = @(New-TalosBenchInputLines `
            -Case $Case `
            -EndPromptIndex ($checkpointPromptIndex - 1) `
            -IncludeLastTrace:$true `
            -StrictEvidence:$StrictEvidence.IsPresent `
            -CaseArtifactRoot $CaseArtifactRoot)
    $firstText = Invoke-TalosProcess `
        -InputLines $firstPhase `
        -Workspace $Workspace `
        -InputCapturePath $(if ($StrictEvidence) { Join-Path $CaseArtifactRoot "phase-1-input.txt" } else { "" })
    $checkpointId = Get-CheckpointIdFromText -Text $firstText
    if ([string]::IsNullOrWhiteSpace($checkpointId)) {
        return $firstText + [Environment]::NewLine + "[TalosBench] Dynamic checkpoint id was not found in prior output."
    }

    $secondPhase = @(New-TalosBenchInputLines `
            -Case $Case `
            -StartPromptIndex $checkpointPromptIndex `
            -EndPromptIndex $checkpointPromptIndex `
            -IncludeSessionClear:$false `
            -IncludeLastTrace:$false `
            -StrictEvidence:$StrictEvidence.IsPresent `
            -CaseArtifactRoot $CaseArtifactRoot `
            -Replacements @{"<checkpoint-id>" = $checkpointId})
    $secondText = Invoke-TalosProcess `
        -InputLines $secondPhase `
        -Workspace $Workspace `
        -InputCapturePath $(if ($StrictEvidence) { Join-Path $CaseArtifactRoot "phase-2-input.txt" } else { "" })
    return $firstText + [Environment]::NewLine + $secondText
}

function Invoke-TalosCase {
    param($Case, [string]$RunRoot)

    $workspace = Join-Path $script:WorkspaceRootFull $Case.id
    Initialize-Workspace -Case $Case -Workspace $workspace

    $manualRequired = $Case.manualRequired -eq $true
    $caseArtifactRoot = if ($StrictEvidence) {
        Join-Path $RunRoot $Case.id
    } else {
        $RunRoot
    }
    New-Item -ItemType Directory -Force -Path $caseArtifactRoot | Out-Null
    $transcript = if ($StrictEvidence) {
        Join-Path $caseArtifactRoot "transcript.txt"
    } else {
        Join-Path $RunRoot ($Case.id + ".txt")
    }
    $relativeTranscript = Resolve-Path -LiteralPath $transcript -Relative -ErrorAction SilentlyContinue
    if (-not $relativeTranscript) {
        $relativeTranscript = $transcript
    }

    $executionGate = Get-TalosBenchManualExecutionGate `
        -Case $Case `
        -IncludeManualRequiredFlag:$IncludeManualRequired `
        -AllowPipedApprovalInputsFlag:$AllowPipedApprovalInputs
    if ($executionGate.Status -ne "RUN") {
        return [pscustomobject]@{
            Id = $Case.id
            Category = $Case.category
            Lane = Get-TalosBenchLane -Case $Case
            Status = $executionGate.Status
            Blocker = "no"
            Transcript = ""
            Artifacts = ""
            Notes = $executionGate.Notes
        }
    }

    if ($StrictEvidence) {
        Initialize-StrictEvidenceGitBaseline -Workspace $workspace -CaseArtifactRoot $caseArtifactRoot
    }

    $text = Invoke-TalosCaseTranscript -Case $Case -Workspace $workspace -CaseArtifactRoot $caseArtifactRoot
    Set-Content -LiteralPath $transcript -Value $text -Encoding UTF8
    Save-StrictEvidenceWorkspaceSnapshot -Workspace $workspace -CaseArtifactRoot $caseArtifactRoot

    $required = @($Case.requiredOutputSubstrings | ForEach-Object { [string]$_ })
    $forbidden = @($Case.forbiddenOutputSubstrings | ForEach-Object { [string]$_ })
    $check = Test-Substrings -Text $text -Required $required -Forbidden $forbidden
    $finalRequired = if ($Case.PSObject.Properties.Name -contains "requiredFinalTurnSubstrings") {
        @($Case.requiredFinalTurnSubstrings | ForEach-Object { [string]$_ })
    } else {
        @()
    }
    $finalForbidden = if ($Case.PSObject.Properties.Name -contains "forbiddenFinalTurnSubstrings") {
        @($Case.forbiddenFinalTurnSubstrings | ForEach-Object { [string]$_ })
    } else {
        @()
    }
    $finalTurnBlock = if (($finalRequired.Count + $finalForbidden.Count) -gt 0) {
        Get-LastNaturalTurnBlock -Text $text
    } else {
        ""
    }
    $finalCheck = Test-Substrings -Text $finalTurnBlock -Required $finalRequired -Forbidden $finalForbidden
    $traceFailures = @()
    if ($Case.PSObject.Properties.Name -contains "traceAssertions") {
        if (-not (Test-TranscriptHasLastTrace -Transcript $text)) {
            $traceFailures += "/last trace was not captured; approval input may have consumed a slash command"
        } else {
            $traceFailures = @(Test-TraceAssertions -Text $text -Assertions $Case.traceAssertions)
        }
    }
    $approvalDriftFailures = @(Test-ApprovalInputDrift -Case $Case -Transcript $text)
    $fileFailures = @(Test-ExpectedFinalFiles -Case $Case -Workspace $workspace)
    $fileFailures += @(Test-ExpectedFinalFilePaths -Case $Case -Workspace $workspace)

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
    if ($finalCheck.MissingRequired.Count -gt 0) {
        if ($status -ne "BLOCKER") {
            $status = "FAIL"
        }
        $notes += "Final turn missing required: " + ($finalCheck.MissingRequired -join "; ")
    }
    if ($finalCheck.FoundForbidden.Count -gt 0) {
        $status = "BLOCKER"
        $blocker = "yes"
        $notes += "Final turn found forbidden: " + ($finalCheck.FoundForbidden -join "; ")
    }
    if ($traceFailures.Count -gt 0) {
        if ($status -ne "BLOCKER") {
            $status = "FAIL"
        }
        $notes += "Trace assertion failed: " + ($traceFailures -join "; ")
    }
    if ($approvalDriftFailures.Count -gt 0) {
        if ($status -ne "BLOCKER") {
            $status = "FAIL"
        }
        $notes += "Approval synchronization failed: " + ($approvalDriftFailures -join "; ")
    }
    if ($fileFailures.Count -gt 0) {
        if ($status -ne "BLOCKER") {
            $status = "FAIL"
        }
        $notes += "Final file assertion failed: " + ($fileFailures -join "; ")
    }
    if ($notes.Count -eq 0) {
        $notes += $Case.notes
    }

    return [pscustomobject]@{
        Id = $Case.id
        Category = $Case.category
        Lane = Get-TalosBenchLane -Case $Case
        Status = $status
        Blocker = $blocker
        Transcript = $relativeTranscript
        Artifacts = $(if ($StrictEvidence) { Resolve-Path -LiteralPath $caseArtifactRoot -Relative } else { "" })
        Notes = ($notes -join " ")
    }
}

function Escape-MarkdownCell {
    param([string]$Value)
    if ($null -eq $Value) { return "" }
    return $Value.Replace("|", "\|").Replace("`r", " ").Replace("`n", " ")
}

$script:RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../.."))
if ($SelfTest) {
    Invoke-TalosBenchSelfTest
    exit 0
}
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
    $cases |
        Sort-Object id |
        Select-Object id, category, manualRequired, @{Name = "lane"; Expression = { Get-TalosBenchLane -Case $_ } }, notes |
        Format-Table -AutoSize
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
                "classificationReasonContains",
                "phaseIncludes",
                "nativeToolsContains",
                "nativeToolsExcludes",
                "blockedContains",
                "outcomeContains",
                "outcomeExcludes",
                "checkpointContains",
                "verificationContains",
                "verificationExcludes",
                "localTraceOutcomeContains",
                "localTraceOutcomeExcludes",
                "localTraceVerificationContains",
                "localTraceVerificationExcludes",
                "repairContains",
                "promptAuditTaskType",
                "promptAuditActionObligationContains",
                "promptAuditEvidenceObligationContains",
                "promptAuditActiveTaskContextContains",
                "promptAuditArtifactGoalContains",
                "promptAuditCurrentTurnFrameContains",
                "promptAuditHistoryContains",
                "promptAuditRedactionContains",
                "transcriptContains",
                "transcriptExcludes"
            )
            foreach ($assertionName in Get-NotePropertyNames $case.traceAssertions) {
                if ($allowedAssertions -notcontains $assertionName) {
                    throw "Case '$($case.id)' has unknown trace assertion '$assertionName'."
                }
            }
        }
        if ($case.PSObject.Properties.Name -contains "approvalInputsByPrompt") {
            $promptCount = @($case.prompts).Count
            $approvalCount = @($case.approvalInputsByPrompt).Count
            if ($approvalCount -ne $promptCount) {
                throw "Case '$($case.id)' approvalInputsByPrompt count ($approvalCount) must match prompts count ($promptCount)."
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
$lines.Add("- Audit id: $(if ([string]::IsNullOrWhiteSpace($AuditId)) { "not set" } else { $AuditId })")
$lines.Add("- Model label: $(if ([string]::IsNullOrWhiteSpace($ModelLabel)) { "not set" } else { $ModelLabel })")
$lines.Add("- Strict evidence: $($StrictEvidence.IsPresent)")
$lines.Add("- Lane override: $(if ([string]::IsNullOrWhiteSpace($Lane)) { "none" } else { $Lane })")
$lines.Add("- Piped approval inputs allowed: $($AllowPipedApprovalInputs.IsPresent)")
$lines.Add("")
$lines.Add("| Case id | Status | Lane | Category | Blocker? | Transcript | Artifacts | Notes |")
$lines.Add("| --- | --- | --- | --- | --- | --- | --- | --- |")
foreach ($result in $results) {
    $lines.Add("| $(Escape-MarkdownCell $result.Id) | $(Escape-MarkdownCell $result.Status) | $(Escape-MarkdownCell $result.Lane) | $(Escape-MarkdownCell $result.Category) | $(Escape-MarkdownCell $result.Blocker) | $(Escape-MarkdownCell $result.Transcript) | $(Escape-MarkdownCell $result.Artifacts) | $(Escape-MarkdownCell $result.Notes) |")
}
Set-Content -LiteralPath $summary -Value $lines -Encoding UTF8

$results | Format-Table Id, Status, Lane, Category, Blocker, Transcript, Artifacts -AutoSize
Write-Output "Summary: $summary"

if ($results | Where-Object { $_.Status -eq "BLOCKER" }) {
    exit 2
}
if ($results | Where-Object { $_.Status -eq "SYNC_REQUIRED" }) {
    exit 1
}
if ($results | Where-Object { $_.Status -eq "FAIL" }) {
    exit 1
}
