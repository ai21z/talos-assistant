# [T480-done-high] Post-T479 Failure State Accounting Boundary Decision

## Status

Done.

## Scope

T480 inspects the post-T479 `ToolCallExecutionStage` shape and decides whether
the next ticket should extract generic failure state accounting, edit-repair
state accounting, or static-web repair recovery. This is a no-code decision
ticket.

It does not change runtime behavior, approval behavior, tool execution,
protected/private handoff, context-ledger capture, read evidence accounting,
mutation accounting, mutation evidence construction, failure classification,
failure state accounting, repair behavior, trace wording, prompt wording,
outcome wording, or final answer rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `5ba670e5`.

| Item | Measurement |
|---|---:|
| `ToolCallExecutionStage.java` | 658 lines |
| Architecture baseline | 0 |

## Source Evidence

After T479, pure failure classification lives in
`ToolExecutionFailureClassifier`. The remaining post-result failed-tool branch
still owns these distinct responsibilities:

```text
state.failedCalls
iteration-local failuresThisIter
failureCountsByTool
failureCountsByPath
successful read-cache clearing after mutating failures
failed edit signatures
stale edit failure recording
static-web full rewrite recovery planning
empty edit argument failure recording
multi-failure edit_file retry suggestion
```

These split into at least three units:

| Unit | Current source | Decision |
|---|---|---|
| Generic failure state accounting | global failed-call count, failure-count maps, read-cache clearing after mutating failure | Correct next implementation slice. |
| Edit failure repair state | failed edit signatures, stale edit failures, empty edit argument failures, multi-failure suggestion | Defer. It affects repair inputs and user-visible retry wording. |
| Static-web full rewrite recovery | full rewrite target decision, repair target state, trace event | Defer. It depends on task contracts, static-web capability, and repair context. |

## Decision

The next correct implementation ticket is:

```text
[T481] Extract tool failure state accounting
```

Target owner:

```text
dev.talos.runtime.toolcall.ToolFailureStateAccounting
```

Preferred responsibilities:

- record one failed tool execution into `state.failedCalls`;
- update `state.failureCountsByTool`;
- update `state.failureCountsByPath` with the existing normalized path
  behavior;
- decide whether successful read-call caches should be cleared after a
  mutating failure, using the already extracted
  `ToolExecutionFailureClassifier.Classification`;
- clear successful read-call caches through
  `ReadEvidenceStateAccounting.clearSuccessfulReadCaches(...)`;
- return a small result telling the stage that one failure was recorded so the
  stage can still update `failuresThisIter`.

`ToolCallExecutionStage` should keep:

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

## Why This Slice Is Correct

Generic failure state accounting is now safe because the pure classification
logic has already been extracted. It has a coherent owner: tracking failure
counts and invalidating stale read caches after failed mutating attempts.

It should not absorb edit-repair or static-web recovery behavior. Those
features affect repair prompts, trace events, and user-visible retry wording.

## Rejected Immediate Work

### Extract edit failure repair state

Rejected for T481.

Failed edit signatures, stale edit failures, empty edit argument failures, and
multi-failure retry suggestions are repair-policy inputs. They should be
handled after generic failure accounting is separated.

### Extract static-web full rewrite recovery

Rejected for T481.

That logic depends on static-web capability, task contracts, repair context,
expected targets, and trace recording. It is not generic failure accounting.

### Move iteration-local failure counters into the owner

Rejected for T481.

`failuresThisIter` is part of `IterationOutcome` assembly. The accounting owner
can report that one failure was recorded, but the stage should still assemble
the iteration-local outcome.

## Required T481 Tests

Start with RED tests for `ToolFailureStateAccounting`:

- failed mutating result increments `state.failedCalls`, records tool/path
  failure counts, clears successful read caches, and reports one recorded
  failure;
- expected-target scope failure records failure counts but does not clear read
  caches;
- edit `old_string not found` after a same-turn read with no mutation records
  failure counts but preserves read caches;
- failed read-only result records failure counts but preserves read caches;
- `ToolCallExecutionStage` delegates generic failure state accounting and no
  longer owns `recordFailure(...)` or
  `shouldClearSuccessfulReadCallsAfterFailure(...)`.

Focused checks should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolFailureStateAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolExecutionFailureClassifierTest" --tests "dev.talos.runtime.toolcall.RedundantReadSuppressionGuardTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*oldString*" --tests "dev.talos.runtime.ToolCallLoopTest.*expectedTarget*" --no-daemon
```

Then run the normal gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```
