param(
    [string]$AuditId = "t645-synthwave-live-audit-$((Get-Date).ToString('yyyyMMdd-HHmmss'))",
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ConfigPath = (Join-Path $env:USERPROFILE ".talos\config.yaml"),
    [string]$ServerPath = "",
    [string]$GptOssModelPath = "",
    [string]$QwenModelPath = "",
    [switch]$StopStaleServers,
    [switch]$PreflightOnly,
    [switch]$SkipInstallDist,
    [switch]$SkipCanaryScan
)

$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -Scope Global -ErrorAction SilentlyContinue) {
    $global:PSNativeCommandUseErrorActionPreference = $false
}

function Add-Line {
    param([System.Collections.Generic.List[string]]$Lines, [string]$Text)
    [void]$Lines.Add($Text)
}

function Quote-Yaml {
    param([string]$Value)
    return '"' + ($Value -replace '\\', '/' -replace '"', '\"') + '"'
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
    if ([string]::IsNullOrWhiteSpace($Root) -or -not (Test-Path -LiteralPath $Root)) { return "" }
    $hit = Get-ChildItem -LiteralPath $Root -Recurse -File -Filter $Pattern -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return ""
}

function Test-FilePath {
    param([string]$PathText)
    return (-not [string]::IsNullOrWhiteSpace($PathText)) -and (Test-Path -LiteralPath $PathText -PathType Leaf)
}

function Get-TalosBatPath {
    param([string]$Root)
    $candidate = Join-Path $Root "build\install\talos\bin\talos.bat"
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    return ""
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
    foreach ($proc in @($Processes)) {
        try {
            Invoke-CimMethod -InputObject $proc -MethodName Terminate | Out-Null
            $stopped += 1
        } catch {
            try {
                Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
                $stopped += 1
            } catch {
                # Best-effort cleanup for sequential installed-product audit runs.
            }
        }
    }
    if ($stopped -gt 0) { Start-Sleep -Seconds 2 }
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
    $yaml = @"
llm:
  transport: "engine"
  default_backend: "llama_cpp"
  model: "$ModelName"

engines:
  llama_cpp:
    mode: "managed"
    server_path: $(Quote-Yaml $ManagedServerPath)
    model_path: $(Quote-Yaml $ModelPath)
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

function Write-SynthwaveWorkspace {
    param([string]$Workspace, [string]$ProbeKey)
    if (Test-Path -LiteralPath $Workspace) {
        throw "Workspace already exists; refusing to reuse contaminated fixture: $Workspace"
    }
    New-Item -ItemType Directory -Force -Path $Workspace | Out-Null
    Set-Content -LiteralPath (Join-Path $Workspace "index.html") -Encoding UTF8 -Value @'
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Neon Meridian</title>
  <link rel="stylesheet" href="styles.css">
</head>
<body>
  <main class="stage">
    <h1>Neon Meridian</h1>
    <p id="teaser-status">Waiting for the midnight signal.</p>
    <button id="teaser-button" type="button">Play teaser</button>
  </main>
  <script src="scripts.js"></script>
</body>
</html>
'@
    Set-Content -LiteralPath (Join-Path $Workspace "scripts.js") -Encoding UTF8 -Value @'
document.getElementById('teaser-button').addEventListener('click', function() {
  document.getElementById('teaser-status').textC;
});
'@
    Set-Content -LiteralPath (Join-Path $Workspace "styles.css") -Encoding UTF8 -Value @'
body {
  min-height: 100vh;
  margin: 0;
  color: #f8f2ff;
  background: #14061f url("https://assets.example.test/synthwave-stage.jpg") center / cover fixed;
  font-family: Arial, sans-serif;
}

.stage {
  padding: 3rem;
}
'@
    Set-Content -LiteralPath (Join-Path $Workspace "README.md") -Encoding UTF8 -Value @"
# T645 Synthwave Fixture

Probe: $ProbeKey

This workspace intentionally starts with a broken teaser click handler in scripts.js.
The background image is remote on purpose so local verification reports the limitation.
"@
    git -C $Workspace init *> $null
    git -C $Workspace config user.email audit@example.test
    git -C $Workspace config user.name "Talos Audit"
    git -C $Workspace add .
    git -C $Workspace commit -m "fixture" *> $null
}

function Get-ProbePrompt {
    param([string]$ProbeKey)
    if ($ProbeKey -eq "preserve") {
        return "Keep styles.css unchanged. Update index.html and scripts.js so Neon Meridian is a polished synthwave band landing page. Make #teaser-button update #teaser-status with a visible teaser message."
    }
    if ($ProbeKey -eq "optional") {
        return "Update index.html and scripts.js so Neon Meridian is a polished synthwave band landing page. Adjust styles.css as needed. Make #teaser-button update #teaser-status with a visible teaser message."
    }
    throw "Unknown probe key: $ProbeKey"
}

function Test-Transcript {
    param([string]$Text, [string]$ProbeKey)
    $expectedTargetsOk = $Text -match "Expected targets:\s*index\.html,\s*scripts\.js" -or
        $Text -match "Expected targets:\s*scripts\.js,\s*index\.html" -or
        $Text -match "requiredTargets:\s*index\.html,\s*scripts\.js" -or
        $Text -match "requiredTargets:\s*scripts\.js,\s*index\.html"
    $roleRegex = if ($ProbeKey -eq "preserve") {
        "styles\.css\s*=\s*FORBIDDEN\s*\(preserve-unchanged-target\)"
    } else {
        "styles\.css\s*=\s*MAY_MUTATE\s*\(optional-mutation-target\)"
    }
    $roleOk = $Text -match $roleRegex
    $stylesNotRequired = -not ($Text -match "requiredTargets:\s*[^\r\n]*styles\.css") -and
        -not ($Text -match "Expected targets:\s*[^\r\n]*styles\.css")
    $verificationStatusReported = $Text -match "Verification:\s*(PASSED|FAILED|READBACK_ONLY|UNAVAILABLE|NOT_RUN)"
    $postApplyVerifierRan = $Text -match "Verification:\s*(PASSED|FAILED|READBACK_ONLY|UNAVAILABLE)"
    $browserProof = $Text -match "BROWSER_BEHAVIOR"
    $remoteLimitation = $Text -match "Remote static-web asset references"
    $completedVerified = $Text -match "COMPLETED_VERIFIED" -or
        $Text -match "Outcome:\s*COMPLETED_VERIFIED" -or
        $Text -match "Status:\s*COMPLETED_VERIFIED"
    $failedHonestly = $Text -match "Verification:\s*FAILED" -or $Text -match "Status:\s*FAILED"
    $approvalInputDesynced = $Text -match "(?s)User Request\s+a\s+Tools\s+none"
    return [pscustomobject]@{
        ExpectedTargetsOk = $expectedTargetsOk
        RoleOk = $roleOk
        StylesNotRequired = $stylesNotRequired
        VerificationStatusReported = $verificationStatusReported
        PostApplyVerifierRan = $postApplyVerifierRan
        BrowserProof = $browserProof
        RemoteAssetLimitation = $remoteLimitation
        CompletedVerified = $completedVerified
        FailedHonestly = $failedHonestly
        ApprovalInputDesynced = $approvalInputDesynced
    }
}

function Invoke-TalosProbe {
    param(
        [object]$Model,
        [string]$ProbeKey,
        [string]$AuditHome,
        [string]$Workspace,
        [string]$TalosBat,
        [string]$ArtifactRoot
    )
    $artifactDir = Join-Path $ArtifactRoot $ProbeKey
    New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
    $inputPath = Join-Path $artifactDir "input.txt"
    $outputPath = Join-Path $artifactDir "transcript.txt"
    $statusPath = Join-Path $artifactDir "workspace-git-status.txt"
    $diffPath = Join-Path $artifactDir "workspace-git-diff.txt"
    $promptDebugTarget = (Join-Path $artifactDir "prompt-debug").Replace('\', '/')
    New-Item -ItemType Directory -Force -Path (Join-Path $artifactDir "prompt-debug") | Out-Null
    $prompt = Get-ProbePrompt $ProbeKey
    $input = @(
        "/session clear",
        "/debug prompt on",
        "/status --verbose",
        $prompt,
        "a",
        "/last trace",
        "/prompt-debug last",
        "/prompt-debug save $promptDebugTarget",
        "/session save",
        "/q"
    )
    Set-Content -LiteralPath $inputPath -Value $input -Encoding UTF8
    $oldJavaOpts = $env:JAVA_OPTS
    $env:JAVA_OPTS = "-Duser.home=$AuditHome"
    try {
        Get-Content -LiteralPath $inputPath | & $TalosBat run --no-logo --root $Workspace *> $outputPath
        $exitCode = $LASTEXITCODE
    } finally {
        $env:JAVA_OPTS = $oldJavaOpts
    }
    git -C $Workspace status --short *> $statusPath
    git -C $Workspace diff -- . *> $diffPath
    foreach ($name in @("index.html", "scripts.js", "styles.css", "README.md")) {
        $source = Join-Path $Workspace $name
        if (Test-Path -LiteralPath $source -PathType Leaf) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $artifactDir ("final-" + $name)) -Force
        }
    }
    $transcript = if (Test-Path -LiteralPath $outputPath) { Get-Content -LiteralPath $outputPath -Raw } else { "" }
    $promptDebugText = ""
    $promptDebugFiles = @(Get-ChildItem -LiteralPath (Join-Path $artifactDir "prompt-debug") -File -ErrorAction SilentlyContinue)
    foreach ($file in $promptDebugFiles) {
        if ($file.Extension -eq ".md") {
            $promptDebugText += "`n" + (Get-Content -LiteralPath $file.FullName -Raw)
        }
    }
    $analysis = Test-Transcript ($transcript + "`n" + $promptDebugText) $ProbeKey
    return [pscustomobject]@{
        ModelKey = $Model.Key
        ModelName = $Model.Name
        ProbeKey = $ProbeKey
        ExitCode = $exitCode
        ArtifactDir = $artifactDir
        ProviderBodies = @($promptDebugFiles | Where-Object { $_.Name.EndsWith(".provider-body.json") }).Count
        ExpectedTargetsOk = $analysis.ExpectedTargetsOk
        RoleOk = $analysis.RoleOk
        StylesNotRequired = $analysis.StylesNotRequired
        VerificationStatusReported = $analysis.VerificationStatusReported
        PostApplyVerifierRan = $analysis.PostApplyVerifierRan
        BrowserProof = $analysis.BrowserProof
        RemoteAssetLimitation = $analysis.RemoteAssetLimitation
        CompletedVerified = $analysis.CompletedVerified
        FailedHonestly = $analysis.FailedHonestly
        ApprovalInputDesynced = $analysis.ApprovalInputDesynced
    }
}

$manualTesting = Join-Path $RepoRoot "local\manual-testing\$AuditId"
$manualWorkspace = Join-Path $RepoRoot "local\manual-workspaces\$AuditId"
if ((Test-Path -LiteralPath $manualTesting) -or (Test-Path -LiteralPath $manualWorkspace)) {
    throw "Audit directories already exist; choose a new AuditId to avoid stale evidence: $AuditId"
}
New-Item -ItemType Directory -Force -Path $manualTesting, $manualWorkspace | Out-Null

$reportPath = Join-Path $manualTesting "LIVE-AUDIT-SYNTHWAVE-T645.md"
$summaryPath = Join-Path $manualTesting "SUMMARY.csv"
$preflightPath = Join-Path $manualTesting "PREFLIGHT.txt"
$lines = [System.Collections.Generic.List[string]]::new()
Add-Line $lines "# T645 Synthwave Installed-Product Live Audit"
Add-Line $lines ""
Add-Line $lines "Audit ID: $AuditId"
Add-Line $lines "Repository: $RepoRoot"
Add-Line $lines "Generated: $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz'))"
Add-Line $lines ""
Add-Line $lines "Approval input note: this redirected-stdin harness sends ``a`` after each natural-language prompt to approve session-scoped writes when an approval prompt is pending. If no approval prompt is pending, Talos correctly treats ``a`` as a second user turn; this harness detects that as approval-input desynchronization and fails the affected probe. Approval-sensitive release evidence still requires a synchronized PTY/manual runner."
Add-Line $lines ""

Push-Location $RepoRoot
try {
    if (-not $SkipInstallDist) {
        .\gradlew.bat installDist --no-daemon *> (Join-Path $manualTesting "installDist.txt")
        $installExit = $LASTEXITCODE
    } else {
        $installExit = 0
        Set-Content -LiteralPath (Join-Path $manualTesting "installDist.txt") -Value "Skipped by -SkipInstallDist." -Encoding UTF8
    }
} finally {
    Pop-Location
}

$configText = if (Test-Path -LiteralPath $ConfigPath) { Get-Content -LiteralPath $ConfigPath -Raw } else { "" }
if ([string]::IsNullOrWhiteSpace($ServerPath)) { $ServerPath = Get-QuotedYamlValue $configText "server_path" }
$configuredModelPath = Get-QuotedYamlValue $configText "model_path"
if ([string]::IsNullOrWhiteSpace($GptOssModelPath) -and $configuredModelPath -match "(?i)gpt[-_]?oss") {
    $GptOssModelPath = $configuredModelPath
}
if ([string]::IsNullOrWhiteSpace($QwenModelPath) -and $configuredModelPath -match "(?i)qwen") {
    $QwenModelPath = $configuredModelPath
}
if ([string]::IsNullOrWhiteSpace($GptOssModelPath)) {
    $GptOssModelPath = Find-FirstGguf (Join-Path $env:USERPROFILE ".cache\huggingface\hub\models--ggml-org--gpt-oss-20b-GGUF") "gpt-oss-20b*.gguf"
}
if ([string]::IsNullOrWhiteSpace($QwenModelPath)) {
    $QwenModelPath = Find-FirstGguf (Join-Path $env:USERPROFILE ".cache\huggingface\hub\models--Qwen--Qwen2.5-Coder-14B-Instruct-GGUF") "qwen2.5-coder-14b*.gguf"
}

$talosBat = Get-TalosBatPath $RepoRoot
$hasLauncher = Test-FilePath $talosBat
$hasServer = Test-FilePath $ServerPath
$hasGptOss = Test-FilePath $GptOssModelPath
$hasQwen = Test-FilePath $QwenModelPath
$repoLlamaServers = @(Get-RepoLlamaServers $ServerPath)
$stoppedRepoServers = 0
if ($StopStaleServers -and $repoLlamaServers.Count -gt 0) {
    $stoppedRepoServers = Stop-RepoLlamaServers $repoLlamaServers
    $repoLlamaServers = @(Get-RepoLlamaServers $ServerPath)
}

Add-Line $lines "## Preflight"
Add-Line $lines ""
Add-Line $lines "| Check | Result |"
Add-Line $lines "| --- | --- |"
Add-Line $lines "| Branch | $(git -C $RepoRoot branch --show-current) |"
Add-Line $lines "| HEAD | $(git -C $RepoRoot rev-parse --short HEAD) |"
Add-Line $lines "| talosVersion | $((Select-String -Path (Join-Path $RepoRoot 'gradle.properties') -Pattern '^talosVersion=').Line) |"
Add-Line $lines "| installDist exit | $installExit |"
Add-Line $lines "| Talos launcher | $hasLauncher |"
Add-Line $lines "| Managed llama.cpp server | $hasServer |"
Add-Line $lines "| Qwen model | $hasQwen |"
Add-Line $lines "| GPT-OSS model | $hasGptOss |"
Add-Line $lines "| Stale repo-owned llama-server processes stopped | $stoppedRepoServers |"
Add-Line $lines "| Remaining repo-owned llama-server processes | $($repoLlamaServers.Count) |"
Add-Line $lines ""

$blocked = [System.Collections.Generic.List[string]]::new()
if ($installExit -ne 0) { Add-Line $blocked "installDist failed; installed launcher is not current." }
if (-not $hasLauncher) { Add-Line $blocked "Built Talos launcher missing." }
if (-not $hasServer) { Add-Line $blocked "Managed llama.cpp server_path missing or not a file." }
if (-not $hasQwen) { Add-Line $blocked "Qwen GGUF file not found." }
if (-not $hasGptOss) { Add-Line $blocked "GPT-OSS GGUF file not found." }
if ($repoLlamaServers.Count -gt 0) { Add-Line $blocked "Stale repo-owned llama-server process(es) are running. Re-run with -StopStaleServers." }

Set-Content -LiteralPath $preflightPath -Value ($lines -join [Environment]::NewLine) -Encoding UTF8
if ($blocked.Count -gt 0) {
    Add-Line $lines "Verdict: BLOCKED"
    foreach ($reason in $blocked) { Add-Line $lines "- $reason" }
    Set-Content -LiteralPath $reportPath -Value ($lines -join [Environment]::NewLine) -Encoding UTF8
    $lines | ForEach-Object { Write-Output $_ }
    Write-Output ""
    Write-Output "Live audit report: $reportPath"
    exit 2
}

if ($PreflightOnly) {
    Add-Line $lines "Verdict: PREFLIGHT PASS; prompt probes not run."
    Set-Content -LiteralPath $reportPath -Value ($lines -join [Environment]::NewLine) -Encoding UTF8
    $lines | ForEach-Object { Write-Output $_ }
    Write-Output ""
    Write-Output "Live audit report: $reportPath"
    exit 0
}

$models = @(
    [pscustomobject]@{ Key = "qwen"; Name = "qwen2.5-coder-14b"; Path = $QwenModelPath; Port = 18116 },
    [pscustomobject]@{ Key = "gptoss"; Name = "gpt-oss-20b"; Path = $GptOssModelPath; Port = 18115 }
)
$probeKeys = @("preserve", "optional")
$results = [System.Collections.Generic.List[object]]::new()

foreach ($model in $models) {
    $auditHome = Join-Path $manualTesting ("home-" + $model.Key)
    Write-IsolatedConfig $auditHome $model.Name $model.Path $model.Port $ServerPath
    foreach ($probeKey in $probeKeys) {
        $workspace = Join-Path $manualWorkspace (Join-Path $model.Key $probeKey)
        $artifactRoot = Join-Path $manualTesting ("artifacts-" + $model.Key)
        Write-SynthwaveWorkspace $workspace $probeKey
        Write-Output "Running $($model.Key) $probeKey"
        $result = Invoke-TalosProbe $model $probeKey $auditHome $workspace $talosBat $artifactRoot
        [void]$results.Add($result)
        if ($StopStaleServers) { Stop-RepoLlamaServers @(Get-RepoLlamaServers $ServerPath) | Out-Null }
    }
}

$csv = [System.Collections.Generic.List[string]]::new()
Add-Line $csv "model,probe,exit_code,provider_bodies,expected_targets_ok,role_ok,styles_not_required,verification_status_reported,post_apply_verifier_ran,browser_proof,remote_asset_limitation,completed_verified,failed_honestly,approval_input_desynced,artifact_dir"
foreach ($result in $results) {
    Add-Line $csv "$($result.ModelName),$($result.ProbeKey),$($result.ExitCode),$($result.ProviderBodies),$($result.ExpectedTargetsOk),$($result.RoleOk),$($result.StylesNotRequired),$($result.VerificationStatusReported),$($result.PostApplyVerifierRan),$($result.BrowserProof),$($result.RemoteAssetLimitation),$($result.CompletedVerified),$($result.FailedHonestly),$($result.ApprovalInputDesynced),$($result.ArtifactDir)"
}
Set-Content -LiteralPath $summaryPath -Value ($csv -join [Environment]::NewLine) -Encoding UTF8

Add-Line $lines "## Probe Results"
Add-Line $lines ""
Add-Line $lines "Summary CSV: $summaryPath"
Add-Line $lines ""
Add-Line $lines "| Model | Probe | Exit | Provider bodies | Targets OK | Role OK | styles.css not required | Verification status reported | Post-apply verifier ran | Browser proof | Remote asset limitation | Completed verified | Failed honestly | Approval input desynced |"
Add-Line $lines "| --- | --- | ---: | ---: | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"
foreach ($result in $results) {
    Add-Line $lines "| $($result.ModelName) | $($result.ProbeKey) | $($result.ExitCode) | $($result.ProviderBodies) | $($result.ExpectedTargetsOk) | $($result.RoleOk) | $($result.StylesNotRequired) | $($result.VerificationStatusReported) | $($result.PostApplyVerifierRan) | $($result.BrowserProof) | $($result.RemoteAssetLimitation) | $($result.CompletedVerified) | $($result.FailedHonestly) | $($result.ApprovalInputDesynced) |"
}
Add-Line $lines ""

if (-not $SkipCanaryScan) {
    $canaryPath = Join-Path $manualTesting "artifact-canary-scan.txt"
    Push-Location $RepoRoot
    try {
        $scanRoots = "local/manual-testing/$AuditId,local/manual-workspaces/$AuditId"
        .\gradlew.bat checkRuntimeArtifactCanaries -PartifactScanRoots="$scanRoots" --no-daemon *> $canaryPath
        $canaryExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    Add-Line $lines "## Artifact Canary Scan"
    Add-Line $lines ""
    Add-Line $lines "Exit code: $canaryExit"
    Add-Line $lines "Output: $canaryPath"
    Add-Line $lines ""
} else {
    $canaryExit = 0
    Add-Line $lines "## Artifact Canary Scan"
    Add-Line $lines ""
    Add-Line $lines "Skipped by -SkipCanaryScan."
    Add-Line $lines ""
}

$failed = @($results | Where-Object {
    $_.ExitCode -ne 0 -or
    $_.ProviderBodies -lt 1 -or
    -not $_.ExpectedTargetsOk -or
    -not $_.RoleOk -or
    -not $_.StylesNotRequired -or
    -not $_.VerificationStatusReported -or
    $_.ApprovalInputDesynced
})
if ($canaryExit -ne 0) {
    Add-Line $lines "Verdict: FAILED - artifact canary scan failed."
    $overallExit = 1
} elseif ($failed.Count -gt 0) {
    Add-Line $lines "Verdict: FAILED - one or more required harness invariants failed."
    $overallExit = 1
} else {
    Add-Line $lines "Verdict: PASS - required harness invariants held. Browser proof may still depend on model output quality."
    $overallExit = 0
}

Set-Content -LiteralPath $reportPath -Value ($lines -join [Environment]::NewLine) -Encoding UTF8
$lines | ForEach-Object { Write-Output $_ }
Write-Output ""
Write-Output "Live audit report: $reportPath"
exit $overallExit
