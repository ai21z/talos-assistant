# T153 - Changed-Files Summary Must Preserve Failed Verification History

Status: open
Priority: high

## Evidence Summary

- Source: full llama.cpp T61-E audit
- Date: 2026-05-05
- Models/backend: managed llama.cpp with `qwen2.5-coder:14b` and `gpt-oss:20b`
- Findings report:
  - `local/manual-testing/llama-cpp-t61e-full-audit-20260505-235337/FINDINGS-LLAMA-CPP-T61E-FULL-AUDIT.md`

Qwen evidence:

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:6021` sends exact README retry.
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:6059` reports static verification failure.
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:6065` reports exact mismatch: expected 27 bytes/2 lines, observed 28 bytes/3 lines.
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:6069` reports `README.md` was updated to 3 lines, 28 bytes.
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:14450` later reports `Verification status: verified complete (PASSED); outcome=COMPLETED_VERIFIED`.

GPT-OSS evidence:

- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:8289` reports static verification failure.
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:8372` records failed outcome.
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:13786` later reports `Verification status: verified complete (PASSED); outcome=COMPLETED_VERIFIED`.

## Problem

Failure-dominant output works at the failed turn. The later changed-files summary is the problem. It can report a global verified-complete state even though a previously changed target in the session has known failed verification history.

This is runtime-owned output, not model-authored prose. Users should not have to reconstruct truth by searching the transcript.

## Goal

Changed-files summary must preserve failed verification history clearly enough that it cannot imply "everything changed in this session is verified" when that is false.

## Scope

In scope:

- Track failed verification history for changed paths across the session.
- Include unresolved failed verification status in changed-files summary.
- Distinguish latest successful verification from earlier unresolved failures.
- Avoid global `verified complete` wording when any changed target still has unresolved failed verification history.
- Preserve the concise happy-path summary when all changed targets are verified clean.

Out of scope:

- No model-authored session summary rewrite.
- No new static verifier rules.
- No broad transcript UI redesign.

## Acceptance Criteria

- Exact README failure remains visible in the final changed-files summary.
- A final summary does not say only `verified complete (PASSED)` when a changed path has unresolved exact-content failure.
- Static web failure history remains visible even if a later unrelated turn verifies successfully.
- If a later turn repairs and verifies the same target, the summary can mark the failure as resolved and name the resolving turn or latest verified state.
- Runtime-owned changed-files summary remains concise and machine-derived.
- Protected file reads are not exposed in changed-files summary.

## Tests

Required tests:

- Unit test for changed-files summary with one failed exact verification and later unrelated success.
- Unit test for failure resolved by later successful verification of the same target.
- Integration/executor test matching the Qwen exact README trailing-newline shape.
- Integration/executor test matching the GPT-OSS static failure followed by later successful unrelated mutation.

Suggested verification commands:

```powershell
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.outcome.MutationOutcomeTest
.\gradlew.bat --no-daemon test --tests dev.talos.cli.modes.AssistantTurnExecutorTest
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon e2eTest
.\gradlew.bat --no-daemon check
```

## Manual Audit

After implementation:

- Rerun the exact literal write portion and final changed-files summary prompt with both models.
- Confirm failed exact verification history remains visible unless a later turn genuinely repairs `README.md`.
