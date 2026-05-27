# [T536-done-high] Add LoopState Terminal Response Helpers

Status: done
Priority: high
Date: 2026-05-27
Branch: `T536`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `4c17e6e1`
Predecessor: `T535`

## Scope

T536 implements the narrow terminal response-state slice selected by T535.

The change adds explicit `LoopState` helpers for the repeated invariant:

```text
terminal answer => currentText is the provided answer, currentNativeCalls is empty
terminal failure => failureDecision is the provided decision, terminal answer invariant applies
```

It does not move:

- failure reason selection;
- answer wording;
- trace recording;
- pending-obligation lifecycle decisions;
- retry/continuation setup;
- compact-continuation result application;
- final answer sanitization in `ToolCallLoop.finalizeAnswer(...)`.

## Implementation

Added:

- `LoopState.finishWithAnswer(String answer)`
- `LoopState.stopWithFailure(FailureDecision decision, String answer)`
- `LoopStateTerminalResponseTest`

Migrated terminal stop call sites that already ended with no further native
tool calls:

- pending-obligation failures in `LoopState`;
- static repair write-content failures in `LoopState`;
- approval-denied, mutation-denied, terminal read-only, and failure-policy
  stops in `ToolCallRepromptStage`;
- model/engine/no-answer terminal answers in `ToolRepromptChatExecutor`;
- model/engine/interruption terminal answers in `ToolRepromptOverlayContinuation`;
- context-budget terminal failure in `ToolRepromptContextBudgetHandler`;
- conditional no-change and repair-inspection terminal stops in
  `ToolRepairInspectionBudgetGate`;
- path-policy blocked terminal answer in
  `ToolRepromptPathPolicyBlockedDecision`;
- stale edit reread terminal failure in `ToolRepromptStaleEditRereadStop`;
- successful-mutation terminal summaries in
  `ToolRepromptSuccessfulMutationDecision`.

## Explicit Non-Moves

The following direct assignments intentionally remain:

- `ToolCallLoop` unfinished-continuation and iteration-limit fallback;
- normal reprompt result application in `ToolRepromptChatExecutor`;
- compact mutation continuation result application in
  `ToolRepromptContextBudgetHandler`;
- compact read-only evidence continuation result application in
  `CompactReadOnlyEvidenceContinuation`;
- continuation repair setup in `ToolRepromptPathPolicyBlockedDecision`;
- non-terminal failure signal state in `ToolFailureIterationSignals`.

Those are not simple terminal response-state writes. Moving them would mix
continuation setup and finalization behavior into this ticket.

## Verification

RED/GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.LoopStateTerminalResponseTest" --no-daemon
```

- RED: failed before implementation because `finishWithAnswer(...)` and
  `stopWithFailure(...)` did not exist.
- GREEN: passed after implementation.

Focused regression tests:

```powershell
.\gradlew.bat test `
  --tests "dev.talos.runtime.toolcall.LoopStateTerminalResponseTest" `
  --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" `
  --tests "dev.talos.runtime.toolcall.ToolRepromptChatExecutorTest" `
  --tests "dev.talos.runtime.toolcall.ToolRepromptOverlayContinuationTest" `
  --tests "dev.talos.runtime.toolcall.ToolRepromptContextBudgetHandlerTest" `
  --tests "dev.talos.runtime.toolcall.ToolRepairInspectionBudgetGateTest" `
  --tests "dev.talos.runtime.toolcall.ToolRepromptSuccessfulMutationDecisionTest" `
  --tests "dev.talos.runtime.toolcall.ToolRepromptPathPolicyBlockedDecisionTest" `
  --tests "dev.talos.runtime.toolcall.ToolRepromptStaleEditRereadStopTest" `
  --no-daemon
```

- Passed.

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
```

- Passed.

Final gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

Inspect the post-T536 tool-loop state before selecting T537. Do not assume the
next slice is compact-continuation state or final-answer finalization without
source inspection.
