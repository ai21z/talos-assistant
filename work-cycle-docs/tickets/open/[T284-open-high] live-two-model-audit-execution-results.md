# T284 - Live Two-Model Audit Execution Results

Status: still-open - full two-model prompt-bank execution results are still missing for the current stabilized head
Severity: high / release gate
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

The deterministic tests are necessary but do not replace the live two-model prompt-bank audit against Qwen and GPT-OSS.

## Evidence from current code

The runbook exists at `work-cycle-docs/reports/t267-live-two-model-audit.md`.

## Evidence from tests/audits

This pass did not run the full live prompt bank. The local model setup improved:

- Updated preflight now checks actual managed `llama.cpp` server/model files and records the need for sequential isolated configs.
- The preflight script now supports `-StopStaleServers` and `-SmokeModels`.
- GPT-OSS and Qwen GGUF files were found locally.
- 53 stale repo-owned `llama-server.exe` processes were stopped after they caused Qwen startup to fail from GPU memory exhaustion.
- Both Qwen and GPT-OSS passed a minimal model-forced Talos smoke prompt after cleanup; latest smoke evidence is `t267-live-audit-20260516-091319`, which left zero repo-owned stale server processes after cleanup.

The release gate remains open because smoke prompts are not the prompt-bank audit.

## User impact

Without live evidence, release claims remain limited to deterministic developer/text-project behavior.

## Product risk

Policy, prompt construction, model behavior, approval, and artifact capture can fail only in live trajectories.

## Runtime boundary affected

Tool-call loop, prompt-debug, provider-body capture, traces, sessions, RAG, approvals, private mode, unsupported-format final answers.

## Non-goals

- Replacing deterministic tests with live audit.

## Required behavior

- Run prompt bank against `qwen2.5-coder:14b` and `gpt-oss:20b` or explicitly approved local audit profiles.
- Capture final answers, tool calls, traces, prompt-debug artifacts, provider bodies, session/turn logs, workspace diffs, and artifact scan results.

## Proposed implementation

Execute the runbook into a fresh ignored audit directory with sequential isolated configs for Qwen and GPT-OSS.

## Tests

- Live prompt-bank audit, not JUnit.

## Acceptance criteria

- `work-cycle-docs/reports/t267-live-two-model-audit-results.md` contains pass/fail per model and hard-fail evidence.

## Remaining blockers

- Full two-model prompt-bank execution/classification remains unrun.
- Approval-sensitive prompts need synchronized human-operated capture or a purpose-built runner; naive scripted stdin can drift if the model does not request the expected approval.

## Open questions

- Which audited profiles may substitute for Qwen/GPT-OSS if one model is unavailable?

## Related files

- `work-cycle-docs/reports/t267-live-two-model-audit.md`
- `work-cycle-docs/reports/t267-live-two-model-audit-results.md`

## 2026-05-15 final pre-beta update

The live-audit results report now records the executable preflight command and the previous BLOCKED result. No prompt-bank prompts were executed.

## 2026-05-16 update

Backend smoke is now PARTIAL rather than BLOCKED: both required local model files exist and both models answer a model-forced smoke prompt through Talos after stale `llama-server.exe` processes are stopped. The script can now perform cleanup and smoke in one command:

```powershell
./gradlew.bat installDist --no-daemon
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-t267-live-audit.ps1 -SmokeModels -StopStaleServers
```

The full prompt bank still has not run, so this ticket remains open.
