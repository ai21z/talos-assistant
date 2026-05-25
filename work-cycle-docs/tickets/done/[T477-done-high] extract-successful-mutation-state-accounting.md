# [T477-done-high] Extract Successful Mutation State Accounting

## Status

Done.

## Scope

T477 implements the T476 decision by extracting successful mutation state
bookkeeping from `ToolCallExecutionStage` into:

```text
dev.talos.runtime.toolcall.ToolMutationStateAccounting
```

This is an ownership refactor. It preserves runtime behavior, approval
behavior, protected/private handoff behavior, context-ledger behavior, read
evidence accounting, mutation evidence construction, failure classification,
repair behavior, trace wording, prompt wording, outcome wording, and final
answer rendering.

## What Moved

`ToolMutationStateAccounting` now owns:

- recognizing successful mutating tool results;
- setting `state.mutationSinceStart`;
- incrementing `state.mutatingToolSuccesses`;
- recording normalized mutated paths in `state.pathsMutatedSinceRead`;
- clearing `state.staticWebFullRewriteRequiredTargets` for a successful
  mutation path;
- deriving the existing first-sentence mutation summary;
- appending non-blank mutation summaries to `state.pendingMutationSummaries`;
- clearing successful read-call caches after successful mutation accounting;
- returning the iteration-local mutation summary decision to the stage.

`ToolCallExecutionStage` still owns:

- when mutation accounting is invoked;
- computing `ToolMutationEvidenceFactory.from(...)` before successful mutation
  accounting clears readback caches;
- iteration-local mutation counts and summary collection;
- denial/path-policy flags;
- unsupported read-path collection;
- failure classification;
- static-web full rewrite recovery planning;
- `ToolOutcome` construction;
- tool-result message formatting.

## Guardrails Preserved

T477 does not move:

- mutation evidence construction;
- read evidence accounting;
- protected/private model-context handoff;
- context-ledger capture;
- stale edit failure classification;
- expected-target failure handling;
- static-web full rewrite recovery;
- multi-failure edit retry suggestions;
- final result/summary selection.

## Test Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationStateAccountingTest" --no-daemon
```

Failed because `ToolMutationStateAccounting` did not exist.

GREEN/focused:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationStateAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceFactoryTest" --tests "dev.talos.runtime.toolcall.CompactMutationContinuationPlannerTest" --tests "dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlannerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*write*" --tests "dev.talos.runtime.ToolCallLoopTest.*edit*" --no-daemon
```

All focused checks passed locally.

## Next Move

After T477 is merged, inspect the post-T477 `ToolCallExecutionStage` shape
before choosing T478. Failure accounting is the obvious remaining neighbor, but
it mixes denial flags, expected-target failures, stale-edit state, static-web
rewrite recovery, and user-visible retry wording, so it should start with
source inspection or a decision ticket rather than an automatic extraction.
