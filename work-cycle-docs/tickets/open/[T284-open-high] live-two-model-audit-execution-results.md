# T284 - Live Two-Model Audit Execution Results

Status: open
Severity: high / release gate
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

The deterministic tests are necessary but do not replace the live two-model prompt-bank audit against Qwen and GPT-OSS.

## Evidence from current code

The runbook exists at `work-cycle-docs/reports/t267-live-two-model-audit.md`.

## Evidence from tests/audits

This pass did not run the live audit. `ollama list` crashed with access violation `0xc0000005`, and local Talos config showed a GPT-OSS llama.cpp profile but no Qwen profile for the required pair.

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

Fix/confirm local model setup, then execute the runbook into a fresh ignored audit directory.

## Tests

- Live prompt-bank audit, not JUnit.

## Acceptance criteria

- `work-cycle-docs/reports/t267-live-two-model-audit-results.md` contains pass/fail per model and hard-fail evidence.

## Remaining blockers

- Required local two-model backend unavailable in this pass.

## Open questions

- Which audited profiles may substitute for Qwen/GPT-OSS if one model is unavailable?

## Related files

- `work-cycle-docs/reports/t267-live-two-model-audit.md`
- `work-cycle-docs/reports/t267-live-two-model-audit-results.md`

## 2026-05-15 final pre-beta update

The live-audit results report now records the executable preflight command and the current BLOCKED result. No prompt-bank prompts were executed.
