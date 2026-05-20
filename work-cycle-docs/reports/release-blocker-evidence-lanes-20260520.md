# Release Blocker Evidence Lanes - 2026-05-20

Branch: `v0.9.0-beta-dev`
Commit: `ae07ef6daf46602b06eff51623e47b314c2b6949`
Version: `talosVersion=0.9.9`

## Preflight

Fresh focused checks before the evidence lanes:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.command.ProcessCommandRunnerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.audit.FullAuditCoverageDocumentationTest" --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
```

Result: all passed. `run-talosbench.ps1 -ValidateOnly` validated 41 cases.

The installed product was refreshed before the installed-product command-profile
lane:

```powershell
.\gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force
```

Result: both passed. The invoked binary was
`%LOCALAPPDATA%\Programs\talos\bin\talos.bat`.

## Lane 1 - T283 Command-Profile Sink Evidence

Audit id: `t283-command-profile-20260520-220959`

Fresh roots:

```text
local/manual-testing/t283-command-profile-20260520-220959
local/manual-workspaces/t283-command-profile-20260520-220959
```

Runtime identity:

```text
Installed executable: %LOCALAPPDATA%\Programs\talos\bin\talos.bat
Model/backend label: llama_cpp/t283-command-mock
Talos home: local/manual-testing/t283-command-profile-20260520-220959/home
Workspace: local/manual-workspaces/t283-command-profile-20260520-220959/command-fixture
```

Authoritative cases:

| Case | Expected boundary | Observed result |
|---|---|---|
| `missing-gradle-wrapper` | `gradle_test` rejected before approval when no Gradle wrapper exists | Rejected before approval; no process execution |
| `raw-command-shape-injected-r3` | forbidden raw `command` field rejected before approval even when `profile=gradle_test` is present | Rejected before approval; no process execution |
| `cwd-escape` | `cwd=..` rejected before approval | Rejected before approval; no process execution |

Evidence captured per case:

- redirected transcript
- `/last trace`
- prompt-debug Markdown
- provider-body JSON
- isolated `~/.talos/logs`
- session snapshot and turn JSONL
- mock-provider hash/length log
- workspace status and diff

Verification:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t283-command-profile-20260520-220959,local/manual-workspaces/t283-command-profile-20260520-220959" "-PartifactScanAllowlist=local/manual-workspaces/t283-command-profile-20260520-220959/command-fixture/.env" --no-daemon
rg --hidden -n "<body-preview-field>|<fixture-secret-marker>|<fixture-env-key>|<fixture-private-fact>" local\manual-testing\t283-command-profile-20260520-220959 local\manual-workspaces\t283-command-profile-20260520-220959
```

Result: artifact canary scan passed. Hidden raw-string search found the raw
fixture canaries only in the source fixture `.env`; `bodyPreview` had no
matches. All Talos exit codes were `0`; workspace diffs were empty.

## Lane 2 - T306/T313 Synchronized Approval Bundle Rebaseline

Audit id: `t306-t313-sync-rebaseline-20260520-221208`

Fresh roots:

```text
local/manual-testing/t306-t313-sync-rebaseline-20260520-221208
local/manual-workspaces/t306-t313-sync-rebaseline-20260520-221208
```

Command:

```powershell
.\gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=local/manual-testing/t306-t313-sync-rebaseline-20260520-221208/artifacts" "-PapprovalAuditWorkspacesRoot=local/manual-workspaces/t306-t313-sync-rebaseline-20260520-221208" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t306-t313-sync-rebaseline-20260520-221208,local/manual-workspaces/t306-t313-sync-rebaseline-20260520-221208" --no-daemon
```

Result: both passed. Summary:
`local/manual-testing/t306-t313-sync-rebaseline-20260520-221208/artifacts/SYNCHRONIZED-APPROVAL-AUDIT.md`.

The summary records:

```text
Mode: SCRIPTED
Scenarios: 32
Artifact scan: PASS
```

Artifact inventory:

| Artifact type | Count |
|---|---:|
| Scenario bundles | 32 |
| Prompt-debug Markdown files | 32 |
| Provider-body JSON files | 32 |
| Trace JSON files | 32 |
| Trace text files | 32 |
| Session snapshots | 32 |
| Turn JSONL files | 32 |

## Lane 3 - Prompt-Bank Status

The two-model prompt-bank was not rerun in this evidence pass. That is
intentional: T313 now makes approval-sensitive redirected-stdin execution fail
closed unless the operator explicitly opts into exploratory
`-AllowPipedApprovalInputs`, and exploratory piped approval input is not release
evidence.

Current prompt-bank status:

- `run-talosbench.ps1 -ValidateOnly` passed and validated 41 cases.
- `run-talosbench.ps1 -ListCases` shows a mix of safe redirected-stdin cases,
  manual/approval-sensitive cases, and command-boundary cases.
- Historical GPT-OSS/Qwen redirected-stdin full runs remain useful evidence, but
  they predate the current lane discipline and must not be treated as
  synchronized approval or true PTY/JLine proof.

Next release-grade prompt-bank run must be lane-labeled:

- safe redirected-stdin installed-product cases;
- synchronized approval cases;
- manual true PTY/JLine cases;
- known-blocked or deferred cases.

## Current Blockers

Still open:

- `T280` / `T284`: fresh lane-labeled two-model live prompt-bank evidence.
- `T312`: current-head full native-tool prompt-bank evidence under lane labels.
- `T313`: synchronized/full prompt-bank integration remains open even though
  the default redirected-stdin contamination guard is working.
- `T301`: release-claim reconciliation waits for the evidence packet.

Reduced but still open:

- `T283`: provider/backend, command-profile, and synchronized audit-bundle sink
  lanes now pass. The remaining T283 blocker is broad two-model prompt-bank
  artifact evidence.

No broad refactor, new document format, arbitrary shell, browser, MCP, or
cloud-agent capability was added in this pass.

## Post-Update Verification

Fresh verification after ticket/report reconciliation:

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat e2eTest --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=work-cycle-docs/reports,work-cycle-docs/tickets" --no-daemon
git diff --check
```

Results:

- `check` passed, including `checkGeneratedArtifactCanaries` over build reports
  and test results.
- `e2eTest` passed.
- Runtime artifact canary scan over `work-cycle-docs/reports,work-cycle-docs/tickets`
  passed after replacing raw fixture marker names in the evidence commands with
  placeholders.
- `git diff --check` exited 0 with line-ending normalization warnings only.
