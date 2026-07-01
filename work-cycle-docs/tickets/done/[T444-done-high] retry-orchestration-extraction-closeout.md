# [T444-done-high] Retry Orchestration Extraction Closeout

## Status

Done.

## Scope

T444 reinspects the post-T443 retry/orchestration shape after
`MissingMutationRetry` was extracted from `AssistantTurnExecutor`.

This is a no-code closeout and decision ticket. It does not change runtime
behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `bb36b79c`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `AssistantTurnExecutor.java` | 3572 lines |
| Architecture baseline | 0 |

## Extracted Retry And Handoff Owners

The retry/orchestration lane now has named owners for the coherent retry and
handoff units that were previously concentrated in `AssistantTurnExecutor`:

- `PostToolSynthesisRetry`
- `ReadEvidenceHandoff`
- `ReadOnlyInspectionRetry`
- `NoToolGroundingRetry`
- `InspectCompletenessRetry`
- `MissingMutationRetry`

These are real ownership moves, not line-count theater:

- post-tool synthesis retry owns one-shot deflection recovery after tools have
  already produced evidence;
- read-evidence handoff owns deterministic read-file tool-loop re-entry for
  required evidence targets;
- read-only inspection retry owns the no-tool read-only corrective retry;
- no-tool grounding retry owns the non-streaming evidence-request retry;
- inspect-completeness retry owns post-tool missing-read recovery and evidence
  merge;
- missing-mutation retry owns action-obligation retry enforcement, compact
  mutation retry prompting, retry tool narrowing, retry tool-loop re-entry,
  failure handling, and retry evidence merge.

## Current Source Shape

`AssistantTurnExecutor.resolveToolLoopAnswer(...)` now mainly preserves the
ordering contract:

1. post-tool synthesis retry;
2. missing-mutation retry;
3. post-tool inspect-completeness retry;
4. partial read-evidence recovery;
5. verification phase movement;
6. final tool-loop answer shaping.

`AssistantTurnExecutor.resolveNoToolAnswer(...)` similarly preserves the
no-tool ordering contract:

1. malformed protocol fast path;
2. missing-mutation retry;
3. direct read-evidence handoff;
4. read-only inspection retry;
5. final no-tool answer shaping.

The remaining retry-adjacent methods in `AssistantTurnExecutor` are mostly
compatibility wrappers or high-level composition points. That is acceptable:
the executor is still the CLI turn orchestrator and should retain sequencing
that depends on `Context`, `chatFull(...)`, streaming/non-streaming output
timing, trace timing, and final answer shaping.

## Rejected Next Slices

### Generic Retry Manager

Rejected.

The extracted units do not share one policy owner. They differ in whether they:

- call the model;
- re-enter the tool loop;
- narrow tool specs;
- mutate message history;
- merge evidence;
- render deterministic failure answers;
- touch mutation obligations;
- touch read-evidence obligations.

A generic `RetryManager` would hide these differences and make the code less
honest.

### Standalone Retry Evidence Merger

Rejected for now.

`MissingMutationRetry.mergeEvidence(...)` and
`InspectCompletenessRetry.mergeReadOnlyRetryEvidence(...)` look similar, but
they are not the same owner:

- missing-mutation retry deduplicates tool names and sums mutation successes;
- inspect-completeness retry preserves concatenated tool names, keeps retry
  messages/final answer/failure decision, and returns the retry result if
  either side has mutation successes.

Extracting only normalized read-path merging would be helper churn, not a real
ownership improvement.

### Split `MissingMutationRetry` Envelope Immediately

Rejected.

The compact mutation retry envelope is still coupled to:

- action-obligation trace recording;
- write/edit versus workspace-operation tool narrowing;
- prior mutation request reissue;
- compact retry message construction;
- retry model-call seam;
- retry tool-loop re-entry;
- denied, invalid, wrong-tool, and context-budget failure handling;
- mutation retry evidence merge.

Splitting an envelope helper immediately after T443 would risk weakening the
state-machine boundary that T443 intentionally created.

### Extract Exact-Write Context-Budget Fallback Now

Rejected as the next retry-lane move.

The exact-write context-budget fallback is a real future candidate, because it
also constructs a compact current-turn prompt and narrows to `talos.write_file`.
But it is not part of the just-closed missing-mutation retry owner. It handles
an initial backend context-budget failure before the ordinary backend call can
complete, while `MissingMutationRetry` handles an answered turn that failed to
execute a required mutation.

Moving it now would start a new context-budget continuation lane, not finish
the retry-orchestration lane. That should be selected deliberately after this
closeout, not smuggled in as T444.

## Decision

Close the retry/orchestration extraction lane for now.

Do not extract another random piece from `AssistantTurnExecutor` merely because
there is more code left. The current retry owners are coherent, tested, and
sequenced by the executor. The remaining obvious work is not another retry
extraction; it is a new lane decision.

## Next Correct Move

Start a new inspection/decision ticket before implementation:

```text
[T445] Context-Budget Continuation Boundary Decision
```

T445 should inspect:

- current-turn exact-write context-budget fallback in `AssistantTurnExecutor`;
- compact mutation continuation in `ToolCallRepromptStage`;
- compact read-only evidence continuation in `ToolCallRepromptStage`;
- context-budget skipped retry wording through `ResponseObligationVerifier`;
- existing tests around exact writes, compact continuations, and context-budget
  failures.

T445 should decide whether there is one coherent implementation owner, such as
a CLI-local exact-write fallback owner or a runtime/CLI split for compact
continuation prompt construction. It should not move code until source
inspection proves the boundary.

## Verification

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before merge.
