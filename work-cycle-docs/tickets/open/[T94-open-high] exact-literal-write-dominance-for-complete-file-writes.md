# T94 - Exact Literal Write Dominance For Complete-File Writes

Status: Open
Priority: High
Branch: v0.9.0-beta-dev
Source: Clean Qwen/GPT-OSS audit follow-up

## Evidence Summary

- Source: clean two-model manual audit
- Date: 2026-05-03
- Models:
  - Qwen: `ollama/qwen2.5-coder:14b`
  - GPT-OSS: `ollama/gpt-oss:20b`
- Audit root: `local/manual-testing/qwen-gptoss-clean-audit-20260503-021152`
- Raw transcript: `local/manual-testing/qwen-gptoss-clean-audit-20260503-021152/TEST-OUTPUT-QWEN-14B.txt`
- Findings: `local/manual-testing/qwen-gptoss-clean-audit-20260503-021152/FINDINGS-CLEAN-TWO-MODEL.md`

Observed evidence:

- User requested: overwrite `index.html` with exactly `AFTER`.
- Qwen wrote `<html><body>Line one<br>Line two</body></html>` instead around
  `TEST-OUTPUT-QWEN-14B.txt:1464`.
- Runtime exact verification caught the mismatch around
  `TEST-OUTPUT-QWEN-14B.txt:1472`.
- `/last trace` confirmed exact verification failed around
  `TEST-OUTPUT-QWEN-14B.txt:1541`.

## Classification

Primary taxonomy bucket: `CURRENT_TURN_FRAME`

Secondary buckets:

- `VERIFICATION`
- `OUTCOME_TRUTH`
- `MODEL_COMPETENCE`

Blocker level: release blocker

Why this level:

Exact complete-file writes are user-controlled mutation requests. Current-turn
literal content must dominate stale history and model guesses, especially after
previous unrelated exact-write prompts.

## Architectural Hypothesis

Exact verification containment exists, but the runtime prompt frame or retry
path does not make the current-turn target and literal payload dominant enough
for weaker models. The exact verifier must remain authoritative, and the runtime
should reduce stale-history write mistakes without adding a broad memory system.

Likely code/document areas:

- exact complete-file write task framing
- mutation request/task contract code
- exact write verifier tests
- assistant turn executor or repair/retry framing tests

## Goal

For explicit complete-file exact content requests, current-turn literal content
must dominate over stale history and model guesses. Failed exact verification
must remain failure-dominant.

## Non-Goals

- No broad memory/context feature.
- No acceptance of approximate exact-file writes.
- No full T61-style audit as part of this individual ticket.

## Implementation Notes

Add focused tests for exact complete-file write requests after prior unrelated
exact-write history. If feasible within the scope, adjust runtime framing or
deterministic retry behavior so the exact target and exact payload are harder
for the model to ignore.

## Acceptance Criteria

- Tests cover exact complete-file write requests after prior unrelated exact
  write history.
- Exact mismatch is caught and reported.
- If feasible within scope, runtime makes the exact payload harder for the
  model to ignore.
- Failed exact verification remains failure-dominant.
- No broad memory/context feature.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit/e2e case: after a previous two-line README exact write, overwrite
  `index.html` with exactly `AFTER`.
- Assertion: expected target is `index.html`, expected exact payload is
  `AFTER`, and stale README/two-line content cannot satisfy verification.

Commands:

```powershell
./gradlew.bat test --tests "*Exact*" --no-daemon
./gradlew.bat test --no-daemon
./gradlew.bat e2eTest --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop for T94.
- Do not run the clean two-model milestone audit after this ticket alone.
- Re-run the clean Qwen/GPT-OSS audit after the T93-T95 batch passes normal
  verification.

## Known Risks

- More aggressive framing could bloat prompts if it is applied outside exact
  complete-file writes.
- Deterministic retry must not mask a failed exact verifier with success prose.

## Known Follow-Ups

- Consider narrower retry machinery only if prompt framing cannot reliably
  express the exact-payload invariant.

