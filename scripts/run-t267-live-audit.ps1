param(
    [string]$AuditId = "t267-live-audit-$((Get-Date).ToString('yyyyMMdd-HHmmss'))",
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ConfigPath = (Join-Path $env:USERPROFILE ".talos\config.yaml"),
    [switch]$PreflightOnly
)

$ErrorActionPreference = "Stop"

function Add-Line {
    param([System.Collections.Generic.List[string]]$Lines, [string]$Text)
    [void]$Lines.Add($Text)
}

function Test-OllamaList {
    $ollama = Get-Command "ollama" -ErrorAction SilentlyContinue
    if (-not $ollama) {
        return "missing: ollama executable not found"
    }

    $job = $null
    try {
        $exe = $ollama.Source
        $job = Start-Job -ScriptBlock {
            param($OllamaExe)
            & $OllamaExe list 2>&1
            "__EXIT_CODE__:$LASTEXITCODE"
        } -ArgumentList $exe
        if (-not (Wait-Job -Job $job -Timeout 15)) {
            Stop-Job -Job $job -ErrorAction SilentlyContinue
            return "blocked: ollama list timed out after 15s"
        }
        $received = @(Receive-Job -Job $job -ErrorAction SilentlyContinue)
        $exitLine = $received | Where-Object { $_ -is [string] -and $_.StartsWith("__EXIT_CODE__:") } | Select-Object -Last 1
        $exitCode = if ($exitLine) { [int]($exitLine -replace "__EXIT_CODE__:", "") } else { 1 }
        $detail = ($received | Where-Object { -not ($_ -is [string] -and $_.StartsWith("__EXIT_CODE__:")) }) -join " "
        if ($exitCode -ne 0) {
            if ($detail.Length -gt 300) { $detail = $detail.Substring(0, 300) + "..." }
            return "blocked: ollama list exited ${exitCode}: $detail"
        }
        return "available"
    } catch {
        return "blocked: ollama list failed: $($_.Exception.Message)"
    } finally {
        if ($null -ne $job) {
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        }
    }
}

$manualTesting = Join-Path $RepoRoot "local\manual-testing\$AuditId"
$manualWorkspace = Join-Path $RepoRoot "local\manual-workspaces\$AuditId"
New-Item -ItemType Directory -Force -Path $manualTesting, $manualWorkspace | Out-Null

$lines = [System.Collections.Generic.List[string]]::new()
Add-Line $lines "# T267 Live Two-Model Audit Preflight"
Add-Line $lines ""
Add-Line $lines "Audit ID: $AuditId"
Add-Line $lines "Repository: $RepoRoot"
Add-Line $lines "Config inspected: $ConfigPath"
Add-Line $lines ""

$configText = ""
if (Test-Path $ConfigPath) {
    $configText = Get-Content -Path $ConfigPath -Raw
    Add-Line $lines "Config file: present"
} else {
    Add-Line $lines "Config file: missing"
}

$hasGptOss = $configText -match "(?i)gpt[-_]?oss"
$hasQwen = $configText -match "(?i)qwen2\.5-coder|qwen"
$hasManagedLlama = $configText -match "(?i)llama\.cpp|llamacpp|managed"
$ollamaStatus = Test-OllamaList

Add-Line $lines ""
Add-Line $lines "## Model/backend checks"
Add-Line $lines ""
Add-Line $lines "| Check | Result |"
Add-Line $lines "| --- | --- |"
Add-Line $lines "| GPT-OSS profile configured | $hasGptOss |"
Add-Line $lines "| Qwen profile configured | $hasQwen |"
Add-Line $lines "| Managed llama.cpp signal configured | $hasManagedLlama |"
Add-Line $lines "| Ollama legacy backend probe | $ollamaStatus |"

$blockedReasons = [System.Collections.Generic.List[string]]::new()
if (-not $hasGptOss) { Add-Line $blockedReasons "GPT-OSS profile not found in config." }
if (-not $hasQwen) { Add-Line $blockedReasons "Qwen profile not found in config." }
if (-not $hasManagedLlama -and $ollamaStatus -ne "available") {
    Add-Line $blockedReasons "No usable managed llama.cpp signal and Ollama probe is not available."
}

Add-Line $lines ""
if ($blockedReasons.Count -eq 0) {
    Add-Line $lines "Preflight verdict: PASS"
    Add-Line $lines ""
    Add-Line $lines "Both required model profiles appear configured. Run the 25-prompt bank for both models and then scan artifacts with:"
    Add-Line $lines ""
    Add-Line $lines '```powershell'
    Add-Line $lines "./gradlew.bat checkRuntimeArtifactCanaries -PartifactScanRoots=`"local/manual-testing/$AuditId,local/manual-workspaces/$AuditId`" --no-daemon"
    Add-Line $lines '```'
} else {
    Add-Line $lines "Preflight verdict: BLOCKED"
    Add-Line $lines ""
    Add-Line $lines "Blocked reasons:"
    foreach ($reason in $blockedReasons) {
        Add-Line $lines "- $reason"
    }
}

if ($PreflightOnly) {
    Add-Line $lines ""
    Add-Line $lines "Execution: preflight only; prompt bank was not run."
}

$reportPath = Join-Path $manualTesting "LIVE-AUDIT-PREFLIGHT.md"
Set-Content -Path $reportPath -Value ($lines -join [Environment]::NewLine) -Encoding UTF8
$lines | ForEach-Object { Write-Output $_ }
Write-Output ""
Write-Output "Preflight report: $reportPath"

if ($blockedReasons.Count -gt 0) {
    exit 2
}
