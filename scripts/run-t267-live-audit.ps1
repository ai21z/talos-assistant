param(
    [string]$AuditId = "t267-live-audit-$((Get-Date).ToString('yyyyMMdd-HHmmss'))",
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ConfigPath = (Join-Path $env:USERPROFILE ".talos\config.yaml"),
    [string]$ServerPath = "",
    [string]$GptOssModelPath = "",
    [string]$QwenModelPath = "",
    [switch]$StopStaleServers,
    [switch]$SmokeModels,
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

function Get-RepoLlamaServers {
    param([string]$ExpectedServerPath)
    if ([string]::IsNullOrWhiteSpace($ExpectedServerPath)) { return @() }
    try {
        $normalized = [System.IO.Path]::GetFullPath($ExpectedServerPath)
        return @(Get-CimInstance Win32_Process -Filter "name = 'llama-server.exe'" -ErrorAction SilentlyContinue |
            Where-Object {
                -not [string]::IsNullOrWhiteSpace($_.ExecutablePath) -and
                [System.IO.Path]::GetFullPath($_.ExecutablePath) -eq $normalized
            })
    } catch {
        return @()
    }
}

function Stop-RepoLlamaServers {
    param([object[]]$Processes)
    $stopped = 0
    $processIds = @($Processes | ForEach-Object { $_.ProcessId })
    foreach ($proc in @($Processes)) {
        try {
            Invoke-CimMethod -InputObject $proc -MethodName Terminate | Out-Null
            $stopped += 1
        } catch {
            try {
                Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
                $stopped += 1
            } catch {
                # Keep preflight best-effort; remaining processes are counted again below.
            }
        }
    }
    if ($stopped -gt 0) {
        for ($attempt = 0; $attempt -lt 10; $attempt++) {
            $remaining = @($processIds | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
            if ($remaining.Count -eq 0) { break }
            Start-Sleep -Milliseconds 500
        }
    }
    return $stopped
}

function Write-IsolatedConfig {
    param(
        [string]$AuditHome,
        [string]$ModelName,
        [string]$ModelPath,
        [int]$Port,
        [string]$ManagedServerPath
    )
    $talosDir = Join-Path $AuditHome ".talos"
    New-Item -ItemType Directory -Force -Path $talosDir | Out-Null
    $serverYaml = $ManagedServerPath.Replace('\', '/')
    $modelYaml = $ModelPath.Replace('\', '/')
    $yaml = @"
llm:
  transport: "engine"
  default_backend: "llama_cpp"
  model: "$ModelName"

engines:
  llama_cpp:
    mode: "managed"
    server_path: "$serverYaml"
    model_path: "$modelYaml"
    hf_repo: ""
    hf_file: ""
    hf_cache_dir: ""
    model: "$ModelName"
    host: "http://127.0.0.1"
    port: $Port
    context: 8192
    jinja: true
    server_args: []

embed:
  provider: "disabled"
  model: "none"
  host: ""
  allow_remote: false

rag:
  vectors:
    enabled: false
"@
    Set-Content -LiteralPath (Join-Path $talosDir "config.yaml") -Value $yaml -Encoding UTF8
}

function Invoke-ModelSmoke {
    param(
        [string]$ModelKey,
        [string]$ModelName,
        [string]$ExpectedToken,
        [string]$AuditHome,
        [string]$Workspace,
        [string]$TalosBat,
        [string]$ManualTesting
    )
    New-Item -ItemType Directory -Force -Path $Workspace | Out-Null
    Set-Content -LiteralPath (Join-Path $Workspace "README.md") `
        -Value "# Live Audit Smoke`n`nPublic smoke fixture for $ModelName." `
        -Encoding UTF8

    $inputPath = Join-Path $ManualTesting "$ModelKey-smoke-input.txt"
    $outputPath = Join-Path $ManualTesting "$ModelKey-smoke-output.txt"
    Set-Content -LiteralPath $inputPath `
        -Value @("Return exactly $ExpectedToken and no other text.", "/quit") `
        -Encoding UTF8

    $oldJavaOpts = $env:JAVA_OPTS
    $env:JAVA_OPTS = "-Duser.home=$AuditHome"
    try {
        Get-Content -LiteralPath $inputPath | & $TalosBat run --no-logo --root $Workspace *> $outputPath
        $exitCode = $LASTEXITCODE
    } finally {
        $env:JAVA_OPTS = $oldJavaOpts
    }

    $output = if (Test-Path -LiteralPath $outputPath) {
        Get-Content -LiteralPath $outputPath -Raw
    } else {
        ""
    }
    $passed = ($exitCode -eq 0) -and ($output -match [regex]::Escape($ExpectedToken))
    return [pscustomobject]@{
        Model = $ModelName
        Key = $ModelKey
        Passed = $passed
        ExitCode = $exitCode
        OutputPath = $outputPath
    }
}

function Get-TalosBatPath {
    param([string]$Root)
    $candidate = Join-Path $Root "build\install\talos\bin\talos.bat"
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    return ""
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
$repoLlamaServers = @(Get-RepoLlamaServers $ServerPath)
$stoppedRepoServers = 0
if ($StopStaleServers -and $repoLlamaServers.Count -gt 0) {
    $stoppedRepoServers = Stop-RepoLlamaServers $repoLlamaServers
    $repoLlamaServers = @(Get-RepoLlamaServers $ServerPath)
}
$repoLlamaServerCount = $repoLlamaServers.Count
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
Add-Line $lines "| Repo-owned llama-server processes stopped by preflight | $stoppedRepoServers |"
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

    if ($SmokeModels) {
        $talosBat = Get-TalosBatPath $RepoRoot
        Add-Line $lines ""
        Add-Line $lines "## Model smoke"
        Add-Line $lines ""
        if ([string]::IsNullOrWhiteSpace($talosBat)) {
            Add-Line $lines "Smoke verdict: BLOCKED"
            Add-Line $lines ""
            Add-Line $lines "Blocked reason: built Talos launcher not found at `build/install/talos/bin/talos.bat`; run `./gradlew.bat installDist --no-daemon` first."
            Add-Line $blockedReasons "Built Talos launcher not found for smoke run."
        } else {
            $gptHome = Join-Path $manualTesting "home-gptoss"
            $qwenHome = Join-Path $manualTesting "home-qwen"
            Write-IsolatedConfig $gptHome "gpt-oss-20b" $GptOssModelPath 18115 $ServerPath
            Write-IsolatedConfig $qwenHome "qwen2.5-coder-14b" $QwenModelPath 18116 $ServerPath

            $smokeResults = @()
            $smokeResults += Invoke-ModelSmoke "gptoss" "gpt-oss-20b" "GPTOSS_SMOKE_123" `
                $gptHome (Join-Path $manualWorkspace "gptoss") $talosBat $manualTesting
            if ($StopStaleServers) {
                Stop-RepoLlamaServers @(Get-RepoLlamaServers $ServerPath) | Out-Null
            }
            $smokeResults += Invoke-ModelSmoke "qwen" "qwen2.5-coder-14b" "QWEN_SMOKE_123" `
                $qwenHome (Join-Path $manualWorkspace "qwen") $talosBat $manualTesting
            if ($StopStaleServers) {
                Stop-RepoLlamaServers @(Get-RepoLlamaServers $ServerPath) | Out-Null
            }

            Add-Line $lines "| Model | Passed | Exit code | Output |"
            Add-Line $lines "| --- | --- | --- | --- |"
            foreach ($result in $smokeResults) {
                Add-Line $lines "| $($result.Model) | $($result.Passed) | $($result.ExitCode) | $($result.OutputPath) |"
                if (-not $result.Passed) {
                    Add-Line $blockedReasons "Smoke failed for $($result.Model); see $($result.OutputPath)."
                }
            }
            if (($smokeResults | Where-Object { -not $_.Passed }).Count -eq 0) {
                Add-Line $lines ""
                Add-Line $lines "Smoke verdict: PASS"
            } else {
                Add-Line $lines ""
                Add-Line $lines "Smoke verdict: BLOCKED"
            }
        }
    }
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
