# [T525-done-high] Extract Tool Reprompt Obligation Selector

Status: done
Priority: high
Date: 2026-05-26
Branch: `T525`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `1ab673c4`
Predecessor: `T524`

## Scope

T525 implements the narrow obligation-selection slice selected by T524.

The goal was to move only this transition out of `ToolCallRepromptStage`:

```text
target progress facts -> pending obligation state + next reprompt tool surface
```

This ticket intentionally does not move pending-obligation breach enforcement,
failure wording, trace wording, prompt construction, chat execution, or target
accounting primitives.

## Changes

- Added `dev.talos.runtime.toolcall.ToolRepromptObligationSelector`.
- Added `ToolRepromptObligationSelector.Selection` as the narrow return value
  consumed by `ToolCallRepromptStage`.
- Moved these calls out of `ToolCallRepromptStage`:
  - `StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(...)`;
  - `ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(...)`;
  - `PendingActionObligation.staticRepairTargets(...)`;
  - `PendingActionObligation.expectedTargets(...)`;
  - `state.clearPendingActionObligation()`;
  - `ToolRepromptRequestBuilder.toolSpecs(...)`.
- Updated `ToolCallRepromptStage` to delegate obligation selection and pass the
  selected values into `ToolRepromptOverlayContinuation`.
- Added focused selector ownership and behavior tests.
- Updated stale ownership assertions to point at the new selector owner.

## Preserved Behavior

- Static full-rewrite repair still narrows to `talos.write_file`.
- Expected-target progress still narrows to `talos.write_file` and
  `talos.edit_file`.
- Expected-target facts before mutation progress do not raise a pending
  obligation or narrow the tool surface.
- No remaining targets still clears an existing pending obligation.
- Pending-obligation failure reasons, final answers, and trace events are
  still owned by `PendingActionObligation` and `LoopState`.
- Static repair target accounting remains in
  `StaticRepairTargetProgressAccounting`.
- Expected target accounting remains in `ExpectedTargetProgressAccounting`.
- Prompt-frame construction and chat execution remain in their existing owners.

## Non-Changes

- No changes to `LoopState.failPendingActionObligationAfterInvalidToolCalls(...)`.
- No changes to `LoopState.failPendingActionObligationAfterNoExecutableToolCalls()`.
- No changes to static repair invalid-write stops.
- No changes to static selector repair invalid-write stops.
- No changes to `PendingActionObligation.failureReason(...)` or
  `failureAnswer(...)`.
- No changes to `PendingActionObligation.recordRaised()` or
  `recordBreached(...)`.
- No changes to `ToolRepromptRequestBuilder.messages(...)`.
- No changes to `ToolRepromptOverlayContinuation`.
- No final-answer wording or behavior changes intended.

## TDD Evidence

- RED: `ToolRepromptObligationSelectorTest` failed before implementation
  because `ToolRepromptObligationSelector` did not exist.
- GREEN: the focused selector test passed after adding the selector and
  delegating from `ToolCallRepromptStage`.
- Wider reprompt/accounting tests initially failed only on stale source
  ownership assertions, then passed after those assertions were updated to the
  new owner.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptObligationSelectorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptObligationSelectorTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest" --tests "dev.talos.runtime.toolcall.ExpectedTargetProgressAccountingTest" --tests "dev.talos.runtime.toolcall.StaticRepairTargetProgressAccountingTest" --tests "dev.talos.runtime.toolcall.ToolRepromptRequestBuilderTest" --tests "dev.talos.runtime.toolcall.ToolRepromptSuccessfulMutationDecisionTest" --tests "dev.talos.runtime.toolcall.ToolRepromptSourceEvidenceRepairDecisionTest" --tests "dev.talos.runtime.toolcall.ToolRepromptTargetReadbackRepairDecisionTest" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- Focused selector test: passed (`BUILD SUCCESSFUL`; 6 actionable tasks: 1
  executed, 5 up-to-date).
- Wider reprompt/accounting tests: passed (`BUILD SUCCESSFUL`; 6 actionable
  tasks: 1 executed, 5 up-to-date).
- `git diff --check`: passed, line-ending warnings only.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task executed).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; 14
  actionable tasks: 8 executed, 6 up-to-date).

## Next Step

Inspect the post-T525 obligation/state shape before choosing T526. Do not
assume the next ticket should move breach enforcement out of `LoopState`; that
area is safety-sensitive and still needs source inspection.
