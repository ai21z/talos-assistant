# [T518] Post successful-mutation reprompt stage boundary decision

## Status

Done.

## Context

T517 extracted the all-success mutation continuation branch into `ToolRepromptSuccessfulMutationDecision`. The next step was not assumed to be another extraction. This ticket inspected the current `ToolCallRepromptStage` shape from fresh `origin/v0.9.0-beta-dev` after T517.

## Current Shape

`ToolCallRepromptStage` is now a compact orchestrator for the ordered reprompt decision chain. It delegates these responsibilities:

- denied mutation answer synthesis to `DeniedMutationResponseOnlySynthesizer`
- terminal read-only answers to `TerminalReadOnlyStopAnswer`
- successful-mutation continuation to `ToolRepromptSuccessfulMutationDecision`
- read-only repair budget handling to `ToolRepairInspectionBudgetGate`
- mutation-evidence budget handling to `ToolMutationEvidenceBudgetGate`
- source-evidence exact repair planning to `SourceEvidenceExactRepairPlanner`
- target-readback compact repair planning to `TargetReadbackCompactRepairPlanner`
- generic overlay continuation to `ToolRepromptOverlayContinuation`

The remaining direct branches are:

- approval-denied terminal stop
- denied-mutation terminal stop delegation
- pre-approval path-policy block handling
- stale-edit reread hard stop
- partial-success diagnostic fall-through
- failure-policy stop
- old message compaction
- final remaining-target obligation selection before generic overlay continuation
- iteration-limit predicate

## Decision

The next implementation ticket should extract the pre-approval path-policy block branch, not a random small branch.

Recommended ticket:

`[T519] Extract path policy block reprompt decision`

Recommended owner:

`dev.talos.runtime.toolcall.ToolRepromptPathPolicyBlockedDecision`

Recommended API:

```java
static Optional<Boolean> tryHandle(
        LoopState state,
        ToolCallExecutionStage.IterationOutcome outcome
)
```

## Why This Is The Correct Next Slice

The path-policy block branch is a coherent policy-recovery owner. It currently combines:

- detecting `outcome.pathPolicyBlockedThisIteration()`
- asking `ExpectedTargetScopeRepairPlanner` for an expected-target repair plan
- setting `FailureDecision.continueLoop()` when repair is available
- setting pending expected-target-scope obligations
- recording exact-replacement repair trace details through `LocalTurnTraceCapture`
- directly scheduling exact replacement repair calls
- executing compact repair chat retries
- rendering the existing stop answer when no repair plan exists

Those steps are not generic reprompt orchestration. They are one specialized response to pre-approval path-policy failure. Keeping them inside the stage leaks recovery policy and trace mechanics into the orchestrator.

## Explicit Non-Goals For T519

Do not combine these with the path-policy extraction:

- approval-denied terminal stop
- denied-mutation response synthesis
- stale-edit reread hard stop
- terminal read-only answer selection
- partial-success fall-through
- default failure-policy stop
- source-evidence repair planning
- target-readback compact repair planning
- remaining-target obligation selection
- generic overlay continuation

Bundling any of those would make T519 a mixed cleanup ticket instead of one ownership move.

## Expected T519 Verification Shape

T519 should use a RED/GREEN ownership test before implementation:

- `ToolCallRepromptStage` delegates to `ToolRepromptPathPolicyBlockedDecision.tryHandle(...)`.
- `ToolCallRepromptStage` no longer directly calls `ExpectedTargetScopeRepairPlanner.nextPlan(...)`.
- `ToolCallRepromptStage` no longer directly calls `LocalTurnTraceCapture.recordRepair(...)`.
- `ToolCallRepromptStage` no longer owns the pre-approval path-policy stop wording.
- The new owner contains those mechanics.

Behavior coverage should preserve:

- no path-policy block returns `Optional.empty()`
- path-policy block without a repair plan preserves the current stop answer and native-call clearing
- path-policy block with an expected-target repair plan preserves compact retry behavior
- path-policy block with exact replacement repair preserves pending obligation, prompted key, trace recording, and direct native call scheduling

Required verification:

- focused owner and behavior tests
- relevant expected-target scope repair tests
- relevant reprompt-stage/tool-surface tests
- `validateArchitectureBoundaries`
- `git diff --check`
- full `.\gradlew.bat check --no-daemon`

## Next Step

Start T519 from fresh beta and extract only the path-policy block reprompt decision.
