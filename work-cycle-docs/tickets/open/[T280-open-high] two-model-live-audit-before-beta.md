# T280 - Two-Model Live Audit Before Beta

Status: still-open - full two-model live prompt-bank audit remains unrun for the current stabilized head
Severity: high / release gate
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Deterministic tests are necessary but do not prove live model/tool/prompt behavior. The two-model prompt-bank audit was not run in this pass.

## Evidence from current code

No code issue by itself. This is a release-process gate.

## Evidence from tests/audits

- `work-cycle-docs/reports/t267-live-two-model-audit.md` records the runbook.
- `work-cycle-docs/reports/t267-live-two-model-audit-results.md` records that full prompt-bank execution was not run in this pass.
- `ollama list` crashed with access violation `0xc0000005`.
- Local Talos config showed one GPT-OSS llama.cpp config. That is expected because managed `llama_cpp` currently has one active `model_path` per config; Qwen/GPT-OSS audit execution must use sequential isolated configs.
- On 2026-05-16 both Qwen and GPT-OSS GGUF files were found locally and both passed a model-forced Talos smoke prompt after stale repo-owned `llama-server.exe` processes were stopped. Latest smoke evidence: `t267-live-audit-20260516-091319`; repo-owned stale server count after the run was 0.

## User impact

Without live evidence, runtime policy and model behavior may interact in untested ways.

## Product risk

High for developer/text beta; blocker for private-document beta.

## Runtime boundary affected

Policy classification, tool visibility, approval gates, provider-body safety, final-answer truthfulness, artifacts.

## Non-goals

- Do not replace deterministic tests with live audit.
- Do not accept final answers without traces/artifacts.

## Required behavior

Run the prompt bank against `qwen2.5-coder:14b` and `gpt-oss:20b` or configured audited local profiles.

## Proposed implementation

Use the runbook in `t267-live-two-model-audit.md` and store artifacts under ignored `local/manual-testing/<audit-id>`.

## Tests

Live audit prompts and artifact canary scan.

## Acceptance criteria

- Report states pass/fail per model.
- No private-document release-ready claim if audit is not run or fails.

## Rollback / migration notes

Raw audit artifacts must not be committed.

## Open questions

- Which local profiles are considered release-audited if Qwen/GPT-OSS are unavailable?

## Related files

- `work-cycle-docs/reports/t267-live-two-model-audit.md`
- `work-cycle-docs/reports/t267-live-two-model-audit-results.md`

## 2026-05-15 final pre-beta update

Added `scripts/run-t267-live-audit.ps1` preflight. Previous preflight was BLOCKED because it expected Qwen and GPT-OSS in one config. Updated preflight checks actual model files and supports the correct sequential isolated-config strategy. Running only smoke prompts must not be counted as prompt-bank completion.

2026-05-16 follow-up: the script now supports `-StopStaleServers` and `-SmokeModels`. This makes the local backend setup reproducible, but the prompt-bank execution/classification is still open.

Follow-up ticket: T286.

## 2026-05-20 lane-labeled evidence update

The release evidence is no longer completely absent:

- Preflight PASS: `lane-bank-preflight-20260520`.
- Two-model smoke PASS: `lane-bank-smoke-models-20260520`.
- Strict `SAFE_REDIRECTED_STDIN` lane PASS for both models:
  - GPT-OSS: 19/19 PASS, summary at `local/manual-testing/lane-bank-safe-20260520/artifacts/gptoss/safe-redirected/20260520-224336/summary.md`.
  - Qwen: 19/19 PASS, summary at `local/manual-testing/lane-bank-safe-20260520/artifacts/qwen/safe-redirected/20260520-224631/summary.md`.
- Strict lane artifact scan PASS over `local/manual-testing/lane-bank-safe-20260520` and `local/manual-workspaces/lane-bank-safe-20260520`.
- `SYNC_APPROVAL` lane PASS through `runSynchronizedApprovalAudit` at `local/manual-testing/lane-bank-sync-20260520/artifacts`.
- `TRUE_PTY_MANUAL` packet prepared at `local/manual-testing/lane-bank-pty-manual-20260520/artifacts`; status remains `MANUAL_REQUIRED`.

## 2026-05-20 true PTY/manual lane update

The true terminal/JLine packet is now completed and validated:

```text
Audit id: true-pty-manual-20260520-r1
Artifacts: local/manual-testing/true-pty-manual-20260520-r1/artifacts
Workspace: local/manual-workspaces/true-pty-manual-20260520-r1/workspace
Model/backend: llama_cpp/gpt-oss-20b / llama.cpp
Validator: validateSynchronizedApprovalPtyManualAudit PASS
Artifact scan: PASS
```

This closes the missing true-terminal evidence lane for this audit wave. This
ticket remains open for final clean-candidate verification and release-level
two-model prompt-bank reconciliation, because the working tree is still dirty
and this pass is not a versioned candidate packet.
