# Lane-Labeled Two-Model Prompt-Bank Audit - 2026-05-20

## Scope

This pass implemented and exercised the strict evidence lane for the current
TalosBench prompt bank, then completed the manual true-terminal PTY/JLine
packet for the approval UX lane.

- Branch: `v0.9.0-beta-dev`
- Commit inspected: `ae07ef6daf46602b06eff51623e47b314c2b6949`
- Version: `talosVersion=0.9.9`
- Working tree: dirty; evidence is valid for local stabilization, not a clean
  versioned candidate packet.

## Harness Change

`tools/manual-eval/run-talosbench.ps1` now supports strict evidence capture for
safe redirected-stdin cases:

- `-StrictEvidence`
- `-AuditId`
- `-ModelLabel`
- `-Lane`

Strict mode sends `/debug prompt on`, then after every natural-language prompt
sends `/last trace`, `/prompt-debug save <case-artifact-dir>`, and
`/session save`. Each case also records the exact input script, transcript,
workspace git baseline, workspace `git status --short`, and workspace diff.

Default TalosBench behavior is unchanged for non-strict runs.

## Evidence Produced

### Preflight

Command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-t267-live-audit.ps1 -AuditId lane-bank-preflight-20260520 -RepoRoot (Get-Location).Path -StopStaleServers -PreflightOnly
```

Result: PASS.

Both managed `llama.cpp` server and model files were found:

- `gpt-oss-20b-mxfp4.gguf`
- `qwen2.5-coder-14b-instruct-q4_k_m.gguf`

### Two-Model Smoke

Command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-t267-live-audit.ps1 -AuditId lane-bank-smoke-models-20260520 -RepoRoot (Get-Location).Path -StopStaleServers -SmokeModels
```

Result: PASS.

- GPT-OSS smoke: PASS
- Qwen smoke: PASS

### SAFE_REDIRECTED_STDIN Lane

Strict evidence run against 19 non-approval TalosBench cases.

GPT-OSS:

- Model label: `gpt-oss-20b`
- Summary:
  `local/manual-testing/lane-bank-safe-20260520/artifacts/gptoss/safe-redirected/20260520-224336/summary.md`
- Result: 19 PASS, 0 FAIL, 0 BLOCKER

Qwen:

- Model label: `qwen2.5-coder-14b`
- Summary:
  `local/manual-testing/lane-bank-safe-20260520/artifacts/qwen/safe-redirected/20260520-224631/summary.md`
- Result: 19 PASS, 0 FAIL, 0 BLOCKER

Artifact scan:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/lane-bank-safe-20260520,local/manual-workspaces/lane-bank-safe-20260520" "-PartifactScanAllowlist=<fixture-source-canary-files>" --no-daemon
```

Result: PASS.

### SYNC_APPROVAL Lane

Command:

```powershell
.\gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=local/manual-testing/lane-bank-sync-20260520/artifacts" "-PapprovalAuditWorkspacesRoot=local/manual-workspaces/lane-bank-sync-20260520" --no-daemon
```

Result: PASS.

- Scenario count: 32
- Artifact scan in runner summary: PASS
- Follow-up explicit runtime artifact scan: PASS

### TRUE_PTY_MANUAL Lane

Prepared packet command:

```powershell
.\gradlew.bat prepareSynchronizedApprovalPtyManualAudit "-PptyManualArtifactsRoot=local/manual-testing/lane-bank-pty-manual-20260520/artifacts" "-PptyManualWorkspace=local/manual-workspaces/lane-bank-pty-manual-20260520/workspace" --no-daemon
```

Initial result: `MANUAL_REQUIRED`.

Completed manual packet:

```text
Audit id: true-pty-manual-20260520-r1
Artifacts: local/manual-testing/true-pty-manual-20260520-r1/artifacts
Workspace: local/manual-workspaces/true-pty-manual-20260520-r1/workspace
Model/backend: llama_cpp/gpt-oss-20b / llama.cpp
Terminal: Windows PowerShell 5.1 real interactive terminal
```

The operator supplied a real-terminal transcript covering:

- `/session clear`, `/debug prompt on`, and `/show README.md`;
- protected `.env` read denial after the approval prompt was visible;
- `/last trace` showing `BLOCKED_BY_APPROVAL` for the protected read;
- `/privacy private on`;
- private-document model-handoff denial after the approval prompt was visible;
- `/last trace` showing the private-document denial turn with no raw private
  fact in the answer or trace;
- private-document per-turn approval with `y`;
- `/last trace` showing `Approvals: required=1 granted=1 denied=0`;
- `/prompt-debug save` and clean exit.

Validation:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-testing\true-pty-manual-20260520-r1\artifacts,C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\true-pty-manual-20260520-r1\workspace,C:\Users\arisz\Projects\LOQ\loqj-cli\UsersariszProjectsLOQloqj-clilocalmanual-testingtrue-pty-manual-20260520-r1artifactsprompt-debug" "-PartifactScanAllowlist=C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\true-pty-manual-20260520-r1\workspace\.env" --no-daemon
.\gradlew.bat validateSynchronizedApprovalPtyManualAudit "-PptyManualArtifactsRoot=C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-testing\true-pty-manual-20260520-r1\artifacts" "-PptyManualWorkspace=C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\true-pty-manual-20260520-r1\workspace" --no-daemon
```

Result: PASS.

Important caveat: `/prompt-debug save "<absolute Windows path>"` saved to a
mangled repo-relative directory named
`UsersariszProjectsLOQloqj-clilocalmanual-testingtrue-pty-manual-20260520-r1artifactsprompt-debug`.
The prompt-debug Markdown/provider-body JSON were scanned and did not leak raw
canaries, but path handling is now tracked separately as T333.

## Verification

Passed:

```powershell
pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
.\gradlew.bat installDist --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/lane-bank-smoke-20260520,local/manual-workspaces/lane-bank-smoke-20260520" "-PartifactScanAllowlist=local/manual-workspaces/lane-bank-smoke-20260520/local/capability-onboarding/notes.md" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/lane-bank-safe-20260520,local/manual-workspaces/lane-bank-safe-20260520" "-PartifactScanAllowlist=<fixture-source-canary-files>" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/lane-bank-sync-20260520,local/manual-workspaces/lane-bank-sync-20260520" --no-daemon
```

Final full verification still required before committing/release-claiming this
whole dirty stabilization branch:

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat e2eTest --no-daemon
```

## Current Release-Gate Interpretation

- `SAFE_REDIRECTED_STDIN`: current-head two-model strict evidence exists.
- `SYNC_APPROVAL`: current-head synchronized scripted evidence exists.
- `TRUE_PTY_MANUAL`: real-terminal transcript packet validated for
  `true-pty-manual-20260520-r1`.
- `KNOWN_BLOCKED_DEFERRED`: unchanged; no OCR, PowerPoint, PDF generation,
  arbitrary shell, browser, MCP, or cloud-agent claims should be added.

T280/T284/T312 are reduced but not closed, because a full release claim still
requires final clean-candidate verification and any remaining lane reconciliation
against the dirty stabilization tree.
