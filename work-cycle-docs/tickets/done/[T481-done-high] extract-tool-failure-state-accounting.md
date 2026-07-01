# [T481-done-high] Extract Tool Failure State Accounting

## Status

Done.

## Scope

T481 implements the T480 decision by extracting generic failed tool-execution
state bookkeeping from `ToolCallExecutionStage` into:

```text
dev.talos.runtime.toolcall.ToolFailureStateAccounting
```

This is an ownership refactor. It preserves runtime behavior, approval
behavior, protected/private handoff behavior, context-ledger behavior, read
evidence accounting, mutation accounting, mutation evidence construction,
failure classification, edit-repair behavior, static-web repair behavior,
trace wording, prompt wording, outcome wording, and final answer rendering.

## What Moved

`ToolFailureStateAccounting` now owns:

- incrementing `state.failedCalls` for one failed tool execution;
- updating `state.failureCountsByTool`;
- updating `state.failureCountsByPath` with the existing normalized-path
  behavior;
- deciding whether successful read-call caches should be cleared after a
  failed mutating result;
- preserving successful read-call caches for expected-target scope blocks;
- preserving successful read-call caches for `edit_file` `old_string not
  found` failures after a same-turn read when no mutation happened after that
  read;
- clearing successful read-call caches through
  `ReadEvidenceStateAccounting.clearSuccessfulReadCaches(...)`;
- returning whether one failure was recorded so the stage can still assemble
  iteration-local failure counts.

`ToolCallExecutionStage` still owns:

- when failure accounting is invoked;
- iteration-local `failuresThisIter`;
- applying denial/path-policy/approval flags;
- setting expected-target failure decisions;
- `ToolOutcome` construction;
- failed edit signatures;
- stale edit failure recording;
- static-web full rewrite recovery planning;
- empty edit failure recording;
- multi-failure edit retry suggestion;
- tool-result message formatting.

## Guardrails Preserved

T481 does not move:

- failed-result classification;
- source-derived evidence policy;
- append-line preservation policy;
- expected-target failure decision ownership;
- edit repair state;
- static-web full rewrite recovery;
- approval behavior;
- mutation evidence;
- final result/summary selection.

## Test Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolFailureStateAccountingTest" --no-daemon
```

Failed because `ToolFailureStateAccounting` did not exist.

GREEN/focused:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolFailureStateAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolFailureStateAccountingTest" --tests "dev.talos.runtime.toolcall.ToolExecutionFailureClassifierTest" --tests "dev.talos.runtime.toolcall.RedundantReadSuppressionGuardTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*oldString*" --tests "dev.talos.runtime.ToolCallLoopTest.*expectedTarget*" --no-daemon
```

All focused checks passed locally.

Final gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

All final gates passed locally before commit.

## Next Move

After T481 is merged, inspect the post-T481 `ToolCallExecutionStage` shape
before choosing T482. The next likely lane is edit-failure repair state, but
that touches repair prompts, stale-read behavior, static-web full rewrite
recovery, and user-visible retry wording, so it should start with source
inspection or a decision ticket rather than a blind extraction.
