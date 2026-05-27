# [T532-done-high] Extract Pending Action Obligation Breach Guard

Status: done
Priority: high
Date: 2026-05-27
Branch: `T532`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `8893cf05`
Predecessor: `T531`

## Scope

T532 implements the exact boundary selected by T531:

```text
Extract only invalid-tool pending action obligation breach classification and
detail construction.
```

It intentionally does not move pending-obligation state mutation, no-tool
breach application, context-budget explicit-detail breach application, failure
wording, or trace recording.

## What Changed

- Added `dev.talos.runtime.toolcall.PendingActionObligationBreachGuard`.
- Added `PendingActionObligationBreachGuard.Decision` with:
  - `breach`;
  - `deferToPolicy`;
  - exact breach detail text.
- Moved invalid-tool breach classification/detail construction out of
  `LoopState` for:
  - `EXPECTED_TARGETS_REMAINING`;
  - `STATIC_REPAIR_TARGETS_REMAINING`;
  - `OLD_STRING_MISS_TARGET_REPAIR`;
  - `APPEND_LINE_TARGET_REPAIR`;
  - `EXPECTED_TARGET_SCOPE_REPAIR`.
- Kept `LoopState.failPendingActionObligationAfterInvalidToolCalls(...)` as
  the mutable state application point:
  - clears pending obligation only on actual breach;
  - records the breached obligation through `PendingActionObligation`;
  - assigns `FailureDecision.stop(...)`;
  - assigns the existing failure answer;
  - clears native calls.
- Kept static-web expected-target deferral behavior intact: wrong static-web
  paths that should go through normal path policy still return non-breach
  `deferToPolicy`.

## What Did Not Change

- No final-answer wording was intentionally changed.
- No failure-decision wording was intentionally changed.
- No `PENDING_ACTION_OBLIGATION_RAISED` or
  `PENDING_ACTION_OBLIGATION_BREACHED` trace ownership was moved.
- No no-tool pending-obligation failure path was moved.
- No context-budget pending-obligation failure path was moved.
- No static repair write-content guard behavior was moved.
- No static selector repair write guard behavior was moved.

## Tests Added

Added `PendingActionObligationBreachGuardTest` covering:

- expected-target wrong mutation breach detail;
- static-web expected-target policy deferral;
- static repair read-only continuation breach detail;
- compact old-string miss target repair wrong-tool breach detail;
- ownership check proving `LoopState` delegates invalid-tool classification to
  `PendingActionObligationBreachGuard`.
- Updated `StaticRepairWriteContentGuardTest` ownership assertions to reflect
  that `StaticRepairWriteContentGuard.invalidWriteDetail(...)` is now called by
  the pending-obligation breach guard, not directly by `LoopState`.

## RED/GREEN Evidence

- RED: `PendingActionObligationBreachGuardTest` failed at compile time because
  `PendingActionObligationBreachGuard` did not exist.
- GREEN: the focused guard test passed after adding the guard and delegating
  from `LoopState`.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.PendingActionObligationBreachGuardTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.PendingActionObligationBreachGuardTest" --tests "dev.talos.runtime.toolcall.ToolRepromptChatExecutorTest" --tests "dev.talos.runtime.toolcall.ToolRepromptContextBudgetHandlerTest" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetProgressNoToolProseBecomesDeterministicBreach" --tests "dev.talos.runtime.ToolCallLoopTest.staticRepairProgressNoToolProseBecomesDeterministicBreach" --tests "dev.talos.runtime.ToolCallLoopTest.narrowedStaticRepairProgressBreachReportsOnlyVerifierSpecificTarget" --tests "dev.talos.runtime.ToolCallLoopTest.staticWebFullRewriteRequiredRejectsReadOnlyContinuationBeforeSuccessProse" --tests "dev.talos.runtime.ToolCallLoopTest.staticWebFullRewriteRequiredRejectsRepeatedEditContinuationBeforeSuccessProse" --tests "dev.talos.runtime.ToolCallLoopTest.oldStringMissCompactRepairNoToolProseBecomesDeterministicFailure" --tests "dev.talos.runtime.ToolCallLoopTest.oldStringMissCompactRepairRejectsReadOnlyToolBeforeExecution" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticRepairWriteContentGuardTest" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- Focused guard test: passed.
- Wider pending-obligation runtime tests: passed.
- `ExecutionOutcomeTest`: passed.
- `StaticRepairWriteContentGuardTest`: passed after updating the stale
  ownership assertion.
- `git diff --check`: passed with known line-ending warnings only.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- `.\gradlew.bat check --no-daemon`: passed.

## Next Move

After T532 integrates, inspect the post-extraction `LoopState` and
`PendingActionObligation` shape before choosing T533.

Do not assume trace recording or failure wording should move next; those are
separate ownership questions.
