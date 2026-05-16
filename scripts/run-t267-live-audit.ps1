param(
    [string]$AuditId = "t267-live-audit-$((Get-Date).ToString('yyyyMMdd-HHmmss'))",
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ConfigPath = (Join-Path $env:USERPROFILE ".talos\config.yaml"),
    [string]$ServerPath = "",
    [string]$GptOssModelPath = "",
    [string]$QwenModelPath = "",
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

function Get-QuotedYamlValue {
    param([string]$Text, [string]$Key)
    if ([string]::IsNullOrWhiteSpace($Text)) { return "" }
    $match = [regex]::Match($Text, "(?im)^\s*$([regex]::Escape($Key))\s*:\s*`"?([^`"\r\n]+)`"?\s*$")
    if ($match.Success) { return $match.Groups[1].Value.Trim() }
    return ""
}

function Find-FirstGguf {
    param([string]$Root, [string]$Pattern)
    if ([string]::IsNullOrWhiteSpace($Root) -or -not (Test-Path $Root)) { return "" }
    try {
        $hit = Get-ChildItem -LiteralPath $Root -Recurse -File -Filter $Pattern -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($hit) { return $hit.FullName }
    } catch {
        return ""
    }
    return ""
}

function Test-FilePath {
    param([string]$PathText)
    return (-not [string]::IsNullOrWhiteSpace($PathText)) -and (Test-Path -LiteralPath $PathText -PathType Leaf)
}

function Count-RepoLlamaServers {
    param([string]$ExpectedServerPath)
    if ([string]::IsNullOrWhiteSpace($ExpectedServerPath)) { return 0 }
    try {
        $normalized = [System.IO.Path]::GetFullPath($ExpectedServerPath)
        $servers = Get-CimInstance Win32_Process -Filter "name = 'llama-server.exe'" -ErrorAction SilentlyContinue |
            Where-Object {
                -not [string]::IsNullOrWhiteSpace($_.ExecutablePath) -and
                [System.IO.Path]::GetFullPath($_.ExecutablePath) -eq $normalized
            }
        return @($servers).Count
    } catch {
        return 0
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

$configuredServerPath = Get-QuotedYamlValue $configText "server_path"
$configuredModelPath = Get-QuotedYamlValue $configText "model_path"
if ([string]::IsNullOrWhiteSpace($ServerPath)) { $ServerPath = $configuredServerPath }
if ([string]::IsNullOrWhiteSpace($GptOssModelPath) -and $configuredModelPath -match "(?i)gpt[-_]?oss") {
    $GptOssModelPath = $configuredModelPath
}
if ([string]::IsNullOrWhiteSpace($QwenModelPath) -and $configuredModelPath -match "(?i)qwen") {
    $QwenModelPath = $configuredModelPath
}
if ([string]::IsNullOrWhiteSpace($GptOssModelPath)) {
    $GptOssModelPath = Find-FirstGguf `
        (Join-Path $env:USERPROFILE ".cache\huggingface\hub\models--ggml-org--gpt-oss-20b-GGUF") `
        "gpt-oss-20b*.gguf"
}
if ([string]::IsNullOrWhiteSpace($QwenModelPath)) {
    $QwenModelPath = Find-FirstGguf `
        (Join-Path $env:USERPROFILE ".cache\huggingface\hub\models--Qwen--Qwen2.5-Coder-14B-Instruct-GGUF") `
        "qwen2.5-coder-14b*.gguf"
}

$hasGptOss = Test-FilePath $GptOssModelPath
$hasQwen = Test-FilePath $QwenModelPath
$hasManagedLlama = Test-FilePath $ServerPath
$repoLlamaServerCount = Count-RepoLlamaServers $ServerPath
$ollamaStatus = Test-OllamaList

Add-Line $lines ""
Add-Line $lines "## Model/backend checks"
Add-Line $lines ""
Add-Line $lines "| Check | Result |"
Add-Line $lines "| --- | --- |"
Add-Line $lines "| Managed llama.cpp server path exists | $hasManagedLlama |"
Add-Line $lines "| Managed llama.cpp server path | $ServerPath |"
Add-Line $lines "| GPT-OSS GGUF exists | $hasGptOss |"
Add-Line $lines "| GPT-OSS GGUF path | $GptOssModelPath |"
Add-Line $lines "| Qwen GGUF exists | $hasQwen |"
Add-Line $lines "| Qwen GGUF path | $QwenModelPath |"
Add-Line $lines "| Existing repo-owned llama-server processes | $repoLlamaServerCount |"
Add-Line $lines "| Ollama legacy backend probe | $ollamaStatus |"
Add-Line $lines "| Audit config model strategy | sequential isolated user homes; Talos managed llama.cpp supports one active model_path per config |"

$blockedReasons = [System.Collections.Generic.List[string]]::new()
if (-not $hasManagedLlama) { Add-Line $blockedReasons "Managed llama.cpp server_path missing or not a file." }
if (-not $hasGptOss) { Add-Line $blockedReasons "GPT-OSS GGUF file not found." }
if (-not $hasQwen) { Add-Line $blockedReasons "Qwen GGUF file not found." }
if ($repoLlamaServerCount -gt 0) {
    Add-Line $blockedReasons "Stale repo-owned llama-server process(es) are already running; stop them before audit to avoid port/GPU-memory false failures."
}

Add-Line $lines ""
if ($blockedReasons.Count -eq 0) {
    Add-Line $lines "Preflight verdict: PASS"
    Add-Line $lines ""
    Add-Line $lines "Both required model files and the managed llama.cpp server are available. Run the prompt bank sequentially with isolated temp homes/configs for GPT-OSS and Qwen, then scan artifacts with:"
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
