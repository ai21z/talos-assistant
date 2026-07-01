# [T475-done-high] Extract Read Evidence State Accounting

## Status

Done.

## Scope

T475 implements the T474 decision by extracting successful read-evidence and
read-only cache accounting from `ToolCallExecutionStage` into:

```text
dev.talos.runtime.toolcall.ReadEvidenceStateAccounting
```

This is an ownership refactor. It preserves runtime behavior, approval
behavior, protected/private handoff behavior, context-ledger behavior, mutation
accounting, failure classification, repair behavior, trace wording, prompt
wording, outcome wording, and final answer rendering.

## What Moved

`ReadEvidenceStateAccounting` now owns:

- recognizing successful read-file results using the existing
  `ToolAliasPolicy.localCanonicalName(...)` behavior;
- recording successful read-file paths into `state.pathsReadThisTurn`;
- clearing stale edit/read-mutation state for a freshly read path;
- recording turn-level source evidence through
  `TurnSourceEvidenceCapture.recordRead(...)`;
- storing successful read-only tool summaries in `state.successfulReadCalls`;
- storing full successful read-only tool bodies in
  `state.successfulReadCallBodies`;
- explicitly clearing successful read-call caches when the stage requests it.

`ToolCallExecutionStage` still owns:

- when successful read accounting is invoked;
- iteration success/failure counters;
- mutation success accounting and mutation summaries;
- failure classification and denial flags;
- unsupported read-path collection;
- static-web full rewrite recovery planning;
- `ToolOutcome` construction;
- tool-result message formatting.

## Guardrails Preserved

T475 does not move:

- protected/private model-context handoff;
- context-ledger capture;
- mutation evidence construction;
- mutation state accounting;
- stale edit failure classification;
- static-web full rewrite recovery;
- expected-target failure handling;
- approval denial handling;
- final result/summary selection.

## Test Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ReadEvidenceStateAccountingTest" --no-daemon
```

Failed because `ReadEvidenceStateAccounting` did not exist.

GREEN/focused:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ReadEvidenceStateAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.RedundantReadSuppressionGuardTest" --tests "dev.talos.runtime.toolcall.SourceDerivedEvidenceGuardTest" --tests "dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*read*" --no-daemon
```

All focused checks passed locally.

## Next Move

After T475 is merged, inspect the post-T475 `ToolCallExecutionStage` shape
again before choosing T476. Mutation accounting is the obvious remaining
neighbor, but it should not be extracted until source inspection proves a
coherent owner that can preserve mutation summaries, stale-read state, repair
signals, and outcome inputs exactly.
