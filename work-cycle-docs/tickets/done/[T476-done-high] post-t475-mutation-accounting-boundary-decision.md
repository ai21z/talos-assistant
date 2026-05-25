# [T476-done-high] Post-T475 Mutation Accounting Boundary Decision

## Status

Done.

## Scope

T476 inspects the post-T475 `ToolCallExecutionStage` shape and decides whether
the next ticket should extract mutation accounting, failure accounting, or
another decision slice. This is a no-code decision ticket.

It does not change runtime behavior, approval behavior, tool execution,
protected/private handoff, context-ledger capture, read evidence accounting,
mutation accounting, failure classification, repair behavior, trace wording,
prompt wording, outcome wording, or final answer rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `3ef2a73e`.

| Item | Measurement |
|---|---:|
| `ToolCallExecutionStage.java` | 699 lines |
| Architecture baseline | 0 |

## Source Evidence

After T475, `ToolCallExecutionStage` no longer owns successful read-file
tracking or successful read-only result cache writes. The post-result section
still owns these distinct responsibilities:

```text
ReadEvidenceStateAccounting.recordSuccessfulToolResult(...)
ToolMutationEvidenceFactory.from(...)
state.mutationSinceStart / state.mutatingToolSuccesses
recordMutationSuccess(...)
mutation summary accumulation
ReadEvidenceStateAccounting.clearSuccessfulReadCaches(...)
denial and path-policy flags
unsupported read-path collection
ToolOutcome construction
failure counters
failed edit signatures
stale edit failure detection
static-web full rewrite recovery planning
multi-failure edit_file suggestion
```

These are not one owner. They split into at least three units:

| Unit | Current source | Decision |
|---|---|---|
| Successful mutation state accounting | `mutationSinceStart`, `mutatingToolSuccesses`, `recordMutationSuccess(...)`, pending mutation summaries, successful-read cache clearing after a successful mutation | Correct next implementation slice. |
| Mutation evidence construction | `ToolMutationEvidenceFactory.from(...)` and readback-derived full-write replacement evidence | Keep separate in T477. It must run before read caches are cleared. |
| Failure/repair accounting | denial/path-policy flags, unsupported-read list, stale-edit failures, static-web full-rewrite planning, multi-failure suggestion | Defer. It mixes failure policy, repair policy, task contracts, and user-visible diagnostics. |

## Decision

The next correct implementation ticket is:

```text
[T477] Extract successful mutation state accounting
```

Target owner:

```text
dev.talos.runtime.toolcall.ToolMutationStateAccounting
```

Preferred responsibilities:

- decide whether a successful result belongs to a mutating tool;
- update `state.mutationSinceStart`;
- increment `state.mutatingToolSuccesses`;
- record normalized mutated paths into `state.pathsMutatedSinceRead`;
- clear `state.staticWebFullRewriteRequiredTargets` for the mutated path;
- derive the existing first-sentence mutation summary;
- append non-blank summaries to `state.pendingMutationSummaries`;
- clear successful read-call caches after successful mutation accounting;
- return a small result describing whether a mutation was recorded and which
  summary, if any, should be added to the iteration-local summary list.

`ToolCallExecutionStage` should keep:

- when mutation accounting is invoked;
- computing `ToolMutationEvidenceFactory.from(...)` before read caches are
  cleared;
- iteration-local `mutationsThisIter` and `mutationSummariesThisIter`;
- failure classification;
- denial/path-policy flags;
- unsupported read-path collection;
- static-web full rewrite recovery planning;
- `ToolOutcome` construction;
- tool-result message formatting.

## Why This Slice Is Correct

Successful mutation state accounting has a real owner: it maintains the loop
state that says the workspace has changed and that previously cached read
evidence cannot be reused as current content.

This is smaller and safer than failure accounting because it is exercised only
on successful mutating tool results and can be verified with focused state
tests plus existing mutation/repair tests. It is also safer than moving
mutation evidence because full-write replacement evidence depends on readback
bodies that must still exist before mutation accounting clears read caches.

## Rejected Immediate Work

### Extract failure accounting

Rejected for T477.

Failure accounting updates iteration failure counts, denied/path-policy flags,
failure decisions, stale edit state, static-web full rewrite repair planning,
and user-visible retry suggestions. It is too mixed for the next implementation
ticket.

### Move mutation evidence into the same owner

Rejected for T477.

`ToolMutationEvidenceFactory.from(...)` must continue to run before successful
mutation accounting clears `state.successfulReadCallBodies`. Moving it in the
same ticket would couple two different concerns and make review harder.

### Move static-web full rewrite recovery

Rejected for T477.

That logic depends on task contracts, static-web capability classification,
repair context, and trace events. It should stay in the stage unless a later
decision proves a coherent repair-policy owner.

## Required T477 Tests

Start with RED tests for `ToolMutationStateAccounting`:

- successful mutating result sets mutation flags, records normalized mutated
  path state, clears static-web full-rewrite requirement for that path, clears
  successful read caches, and returns the existing summary text;
- blank mutation output records mutation state but returns no iteration
  summary and does not append a pending mutation summary;
- failed mutating result and successful read-only result are no-ops;
- `ToolCallExecutionStage` delegates successful mutation state accounting and
  no longer owns `recordMutationSuccess(...)`.

Focused checks should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationStateAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceFactoryTest" --tests "dev.talos.runtime.toolcall.CompactMutationContinuationPlannerTest" --tests "dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlannerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*write*" --tests "dev.talos.runtime.ToolCallLoopTest.*edit*" --no-daemon
```

Then run the normal gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```
