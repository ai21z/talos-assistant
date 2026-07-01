# [T474-done-high] Post-T473 Execution State Accounting Boundary Decision

## Status

Done.

## Scope

T474 inspects the post-T473 `ToolCallExecutionStage` shape and decides whether
the next ticket should extract read/mutation state accounting. This is a
no-code decision ticket.

It does not change runtime behavior, approval behavior, tool execution,
protected/private handoff, context-ledger capture, read/mutation accounting,
repair behavior, trace wording, prompt wording, outcome wording, or final
answer rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `a98eb71d`.

| Item | Measurement |
|---|---:|
| `ToolCallExecutionStage.java` | 732 lines |
| Architecture baseline | 0 |

## Source Evidence

After T473, `ToolCallExecutionStage` no longer owns workspace-operation path
planning. The remaining post-result section still owns several different
state-accounting responsibilities:

```text
recordSuccessfulRead(...)
TurnSourceEvidenceCapture.recordRead(...)
successfulReadCalls / successfulReadCallBodies
ToolMutationEvidenceFactory.from(...)
recordMutationSuccess(...)
mutation summary accumulation
clearSuccessfulReadCalls(...)
failure counters
stale edit failure detection
static-web full rewrite recovery planning
ToolOutcome construction
```

These are related, but they are not one safe extraction. They split into at
least three ownership units:

| Unit | Current source | Decision |
|---|---|---|
| Read evidence/cache accounting | successful `read_file` tracking, `TurnSourceEvidenceCapture.recordRead(...)`, `successfulReadCalls`, `successfulReadCallBodies`, read-cache clearing rules | Correct next implementation slice. |
| Mutation accounting | `mutationSinceStart`, `mutatingToolSuccesses`, iteration mutation count, mutation summaries, `recordMutationSuccess(...)` | Defer. It affects final mutation summaries and repair state. |
| Failure/repair accounting | denied/path-policy flags, unsupported-read list, stale-edit failures, static-web full-rewrite planning, multi-failure suggestion | Defer. It mixes failure policy, repair policy, task contract, and static-web behavior. |

## Decision

Do not extract a broad "post-result accounting" object.

The next correct implementation ticket is:

```text
[T475] Extract read evidence state accounting
```

Target owner:

```text
dev.talos.runtime.toolcall.ReadEvidenceStateAccounting
```

Preferred responsibilities:

- decide whether a successful tool result is a read-file result;
- record successful read paths into `state.pathsReadThisTurn`;
- clear stale-edit/read-mutation state for that path;
- record `TurnSourceEvidenceCapture.recordRead(pathHint)`;
- populate `state.successfulReadCalls`;
- populate `state.successfulReadCallBodies`;
- clear successful read-call caches when mutation/failure policy requests it;
- preserve the existing read-file alias behavior through
  `ToolAliasPolicy.localCanonicalName(...)`.

`ToolCallExecutionStage` should keep:

- when read accounting is invoked;
- the local iteration success/failure counters;
- mutation success accounting;
- failure classification;
- static-web full rewrite recovery planning;
- `ToolOutcome` construction;
- tool-result message formatting.

## Why This Slice Is Correct

Read evidence/cache accounting has a real owner: it maintains what the runtime
knows was read this turn and what readback content can be used by later repair
prompts.

It is smaller and safer than mutation/failure accounting because it can be
verified with direct state tests and existing read/repair tests without moving
outcome dominance or static-web repair policy.

## Rejected Immediate Work

### Extract mutation accounting together with read accounting

Rejected for T475.

Mutation accounting updates iteration counters, pending mutation summaries,
stale read state, mutation evidence, and final outcome inputs. Bundling it with
read accounting would make review harder and blur ownership.

### Extract static-web full rewrite recovery

Rejected for T475.

That block depends on task contracts, static-web capability classification,
trace events, and repair context. It needs a separate decision if attacked.

### Extract failure classification

Rejected for T475.

Failure classification drives iteration-level outcome flags, failure decisions,
retry behavior, and user-facing failure wording. It is not a read-evidence
cache concern.

## Required T475 Tests

Start with RED tests for `ReadEvidenceStateAccounting`:

- successful `talos.read_file` records normalized path, removes the same path
  from mutated/stale state, and clears `staleEditRereadIgnoredPath`;
- read-only non-file tools still populate `successfulReadCalls` and
  `successfulReadCallBodies`;
- failed read results do not record read state or read caches;
- clearing successful read caches remains explicit and behavior-preserving;
- `ToolCallExecutionStage` delegates read evidence/cache accounting and no
  longer owns `recordSuccessfulRead(...)` or direct successful-read-cache writes.

Focused checks should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ReadEvidenceStateAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.RedundantReadSuppressionGuardTest" --tests "dev.talos.runtime.toolcall.SourceDerivedEvidenceGuardTest" --tests "dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*read*" --no-daemon
```

Then run the normal gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```
