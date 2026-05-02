# [T77-done-high] Read Evidence Obligation Recovery

Status: done
Priority: high
Date: 2026-05-02
Closed: 2026-05-02

## Evidence Summary

- Audit report:
  `local/manual-testing/t60-t63-focused-audit-20260502-023320/AUDIT-REPORT-FOCUSED.md`
- Raw transcript:
  `local/manual-testing/t60-t63-focused-audit-20260502-023320/TEST-OUTPUT-FOCUSED.txt`
- Protected retry trace:
  `local/manual-testing/t60-t63-focused-audit-20260502-023320/trace-artifacts/000006-trc-1ab8338c-8608-47ad-a6f5-f02ca31b8af8.json`
- README proposal trace:
  `local/manual-testing/t60-t63-focused-audit-20260502-023320/trace-artifacts/000008-trc-33c62d74-4b58-4d4a-8746-1fa56d742b6b.json`

Observed protected-read prompt:

`Read .env and tell me the value inside.`

Observed behavior:

- Contract: `READ_ONLY_QA`
- Expected target: `.env`
- Evidence obligation: `PROTECTED_READ_APPROVAL_REQUIRED`
- The model did not issue `talos.read_file`, so no approval prompt appeared.
- Talos returned protected-read-not-attempted missing evidence.
- The scripted approval input `y` was consumed as a separate user prompt.

Observed non-protected read prompt:

`Please review README.md and propose concise improvements, but do not edit any files yet.`

Observed behavior:

- Expected target: `README.md`
- Evidence obligation: `READ_TARGET_REQUIRED`
- No read tool call was issued.
- Talos returned missing evidence instead of a grounded proposal.

## Goal

When a turn has a required read evidence obligation and the model fails to issue
the required read, perform one bounded recovery attempt that explicitly tells the
model to gather the missing read evidence before producing the final answer.

## Non-Goals

- Do not bypass approval. Protected reads must still go through the existing
  approval prompt.
- Do not force mutation retry behavior.
- Do not loop indefinitely.
- Do not read files outside the task contract's expected targets.

## Implementation Notes

Likely owners:

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/policy/EvidenceObligationVerifier.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`

Root-cause hypothesis:

The runtime can detect missing read evidence and can safely contain the final
answer, but there is no bounded retry path equivalent to the existing action
obligation retry path.

## Acceptance Criteria

- For an expected non-protected read target, if the first model response does
  not call `talos.read_file`, Talos performs one recovery attempt and the final
  result can use the gathered evidence.
- For an expected protected read target, if the first model response does not
  call `talos.read_file`, the recovery attempt issues the protected read and
  triggers the existing approval prompt.
- If the recovery attempt still fails to gather evidence, Talos keeps the
  existing missing-evidence containment wording.
- Recovery is single-attempt and scoped only to expected targets.

## Required Tests

- Unit: non-protected read-target prompt recovers from first no-tool model
  response and then reads the target.
- Unit: protected read-target prompt recovers from first no-tool model response
  and records approval-required/read-file behavior.
- Regression: missing-evidence containment remains when recovery also fails.

## Closure Notes

- Added runtime-owned read evidence handoff for `READ_TARGET_REQUIRED` and
  `PROTECTED_READ_APPROVAL_REQUIRED` no-tool answers.
- Kept protected reads behind the existing approval gate by routing recovery
  through `talos.read_file` and `ToolCallLoop`.
- Forced read-evidence turns into the buffered path when a stream sink exists,
  so visible no-tool prose cannot consume the user's approval response slot.
- Preserved streaming tool-call filtering coverage for non-evidence turns.

## Verification

- `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon`
