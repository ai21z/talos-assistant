# T734 - Source Evidence Post-Read Mutation Continuation

Status: done
Priority: high
Created: 2026-06-08

## Problem

The committed `0.10.0` release packet failed the Qwen synchronized approval lane in
`t325-python-command-boundary`.

Evidence:

- Audit root:
  `local/manual-testing/current-0.10.0-release-packet-20260608-185949/artifacts/qwen/sync-approval/t325-python-command-boundary`
- Trace status: `FAILED`
- Approval count: `0`
- The first two `talos.write_file` calls were correctly blocked before approval by
  `SOURCE_EVIDENCE_WRITE_BEFORE_READ`.
- Talos then read `problem.md` successfully.
- The loop stopped without re-entering a write continuation for remaining expected
  targets `dijkstra.py` and `test_dijkstra.py`.

Code evidence:

- `SourceEvidenceReadBeforeWriteRepairPlanner` handles the first repair step by
  forcing the missing source read.
- After that read succeeds, no post-read mutation continuation owns the next step.
- `SourceEvidenceExactRepairPlanner` is too narrow for this case: it is designed
  for exact evidence phrase coverage and recognizes the older
  `Source-derived write blocked before approval` failure shape, not the
  read-before-write failure shape.

## Acceptance Criteria

- A source-derived mutation that first attempts to write before reading source
  evidence must:
  1. block before approval;
  2. force the missing source read;
  3. continue with a bounded mutation reprompt for remaining expected targets;
  4. request approval only for the grounded post-read mutation.
- The post-read mutation prompt must include the source readback and exact
  remaining expected target paths.
- It must not require arbitrary exact source phrase inclusion for normal derived
  code outputs such as `dijkstra.py`.
- If no post-read mutation tool call is produced, the pending expected-target
  obligation must fail honestly.
- The synchronized `t325-python-command-boundary` lane must no longer fail with
  "Expected 1 approval prompt(s), observed 0" for this cause.

## Test Plan

- Add RED tests first:
  - `ToolRepromptSourceEvidenceRepairDecisionTest`: after read-before-write
    failures and a successful source readback, Talos builds a post-read mutation
    repair prompt for remaining expected targets.
  - `AssistantTurnExecutorTest`: model writes too early, runtime forces the source
    read, then model writes the requested output targets from the source.
- Focused verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptSourceEvidenceRepairDecisionTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon
.\gradlew.bat check --no-daemon
```

## Notes

This is a follow-up to T730/T732. The source-evidence guard itself is correct:
the bug is the missing continuation after the required source read succeeds.

## Implementation Notes

Implemented:

- Added `SourceEvidencePostReadWriteRepairPlanner` to own the post-read mutation
  continuation after a read-before-write source-evidence block.
- Added a dedicated `LoopState` prompted-key set so the continuation is bounded.
- Wired the planner before exact-evidence phrase repair in
  `ToolRepromptSourceEvidenceRepairDecision`.
- Added planner-level and executor-level regressions for the `problem.md` →
  `dijkstra.py` / `test_dijkstra.py` shape.

Verified:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptSourceEvidenceRepairDecisionTest.sourceEvidenceAfterReadRepromptsForRemainingExpectedWrites" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptSourceEvidenceRepairDecisionTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
```
