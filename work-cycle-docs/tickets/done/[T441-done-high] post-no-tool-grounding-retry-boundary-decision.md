# [T441-done-high] Post No-Tool Grounding Retry Boundary Decision

## Status

Done.

## Scope

T441 reinspects `AssistantTurnExecutor` after T440 extracted
`NoToolGroundingRetry`.

This is a no-code decision ticket. It does not change runtime behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `ca4f6481`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `AssistantTurnExecutor.java` | 4439 lines |
| Architecture baseline | 0 active entries |

## Current Retry And Handoff Shape

The retry/handoff units already extracted from `AssistantTurnExecutor` are:

- `PostToolSynthesisRetry`;
- `ReadEvidenceHandoff`;
- `ReadOnlyInspectionRetry`;
- `NoToolGroundingRetry`.

The remaining retry/orchestration responsibilities inspected in this ticket are:

| Area | Source | Ownership finding |
|---|---|---|
| Missing-mutation retry | `mutationRequestRetryIfNeeded(...)`, `MutationRetryResult`, mutation retry frame/tool helpers, `mergeMutationRetryEvidence(...)` | Still too broad for the next extraction. It mixes mutation obligation failure, tool-surface narrowing, trace recording, static-repair wrong-tool handling, invalid/denied mutation cases, context-budget wording, retry loop execution, and retry evidence merge. |
| Inspect-completeness retry | `InspectRetryResult`, `missingInspectReads(...)`, `inspectCompletenessRetryIfNeeded(...)`, `mergeReadOnlyInspectRetryEvidence(...)` | The next coherent ownership extraction. It is the post-tool read-only retry path that completes missing primary and linked-script reads, then merges retry evidence back into the loop result. |
| Retry loop evidence merge | `mergeReadOnlyInspectRetryEvidence(...)`, `mergeMutationRetryEvidence(...)`, `mergeReadPaths(...)`, `addNormalizedReadPaths(...)` | Extractable support logic, but not the next standalone ticket. As a ticket by itself it would be a helper move rather than the ownership move. |
| Mutation retry prompt envelope | `mutationRetryToolNames(...)`, `mutationRetryToolSpecs(...)`, compact retry frame/message helpers, `mutationRetryInstruction(...)` | A possible later sub-owner inside missing-mutation retry. It is still inside a high-risk mutation retry state machine and is not the next move while inspect-completeness remains a cleaner whole owner. |

## Findings

### Missing-mutation retry should still not move

`mutationRequestRetryIfNeeded(...)` remains high-risk execution control.

It currently owns or directly coordinates:

- `ResponseObligationVerifier.unsatisfiedNoToolResponse(...)`;
- `LocalTurnTraceCapture.recordActionObligation(...)`;
- mutation retry tool selection through `mutationRetryToolNames(...)`;
- retry tool-surface narrowing through `mutationRetryToolSpecs(...)`;
- compact retry message and frame construction;
- previous mutation request reissue behavior;
- conditional review/fix no-change handling;
- static repair wrong-tool failure handling;
- invalid mutating argument handling;
- denied mutation handling;
- context-budget retry-skip handling;
- retry loop execution through `ctx.toolCallLoop().run(...)`;
- mutation retry evidence merge.

Moving that whole method next would be too much behavior surface for one ticket.
Splitting a random helper out of it would also be weak architecture, because
the hard ownership question is still the retry state machine.

### Inspect-completeness retry is now the next owner

`inspectCompletenessRetryIfNeeded(...)` has a clear product purpose:

```text
When the first tool loop produced an answer for an inspect/evidence turn but
missed obvious primary or linked-script reads, run one corrective read-only
retry and merge the read evidence back into the original loop result.
```

That owner probably belongs in:

```text
dev.talos.cli.modes.InspectCompletenessRetry
```

It should stay in CLI turn-orchestration ownership because it calls the model
and can re-enter the configured `ToolCallLoop`.

This is not the same owner as `ReadOnlyInspectionRetry`.
`ReadOnlyInspectionRetry` handles the no-tool read-only case: no prior
`LoopResult`, generic evidence prompt, optional tool-loop re-entry, and no
evidence merge. Inspect-completeness retry handles the post-tool case: a prior
loop exists, the runtime can identify missed obvious reads, and the retry loop
must be merged back into the original evidence.

The source currently has two related merge paths:

- `mergeReadOnlyInspectRetryEvidence(...)` for read-only inspect retry evidence;
- `mergeMutationRetryEvidence(...)` for mutation retry evidence.

They are related but not identical. T442 should not move mutation retry merge
as a standalone helper unless implementation proves a tiny package-private
support class is needed to avoid duplication. The ownership target is still the
inspect-completeness retry, not "merge all loop results".

### Standalone retry evidence merge is rejected for now

Extracting only `mergeReadOnlyInspectRetryEvidence(...)`,
`mergeMutationRetryEvidence(...)`, and `mergeReadPaths(...)` would be small, but
that is not enough to make it the correct next move. It would reduce private
helper mass inside `AssistantTurnExecutor`, but it would not move a user-visible
or policy-visible owner. It would also risk creating a generic merger before
the post-tool inspect-completeness owner has shown what shape it actually needs.

The merge logic should move only as required by the inspect-completeness
extraction. If T442 needs a tiny support class such as
`RetryLoopEvidenceMerger`, it should be introduced to preserve exact behavior,
not as the main architectural event.

### Mutation retry prompt envelope should wait

The compact mutation retry prompt/tool-surface envelope is a real possible
sub-owner. It owns retry tool names, narrowed tool specs, compact prompt/frame
construction, and prior-request pinning.

It is not the next move because it is still part of the missing-mutation retry
state machine. That state machine owns trace recording, action obligation
failure semantics, retry loop execution, denied/invalid/wrong-tool cases, and
context-budget failure wording. It should not be touched while the cleaner
post-tool inspect-completeness retry remains available.

## Decision

The next implementation ticket should be:

```text
[T442] Extract post-tool inspect-completeness retry
```

Target owner:

```text
dev.talos.cli.modes.InspectCompletenessRetry
```

T442 should move only:

- `InspectRetryResult`, renamed to `InspectCompletenessRetry.Result`;
- `missingInspectReads(...)`, renamed to `InspectCompletenessRetry.missingReads(...)`;
- the plan-aware `inspectCompletenessRetryIfNeeded(...)`, renamed to
  `InspectCompletenessRetry.retryIfNeeded(...)`;
- `mergeReadOnlyInspectRetryEvidence(...)`;
- the corrective prompt construction and one-shot retry execution;
- a supplied `ChatFunction` seam so `AssistantTurnExecutor` still owns the
  existing `chatFull(...)` path.

T442 may introduce a tiny package-private merge helper if needed to avoid
duplicating `mergeReadPaths(...)`, but it must not move mutation retry behavior
or make mutation retry depend on the inspect-completeness owner.

`AssistantTurnExecutor` should keep package-private compatibility wrappers for
existing direct tests, especially `missingInspectReads(...)` and both
`inspectCompletenessRetryIfNeeded(...)` overloads.

## T442 Guardrails

T442 must preserve:

- directory-listing bypass; file listing must not turn into content inspection;
- inspect-first or workspace-evidence eligibility gates;
- missing-read calculation from primary files plus linked-script targets;
- protected-path filtering for linked-script retry targets;
- answer-blank and mutation-success bypasses;
- exact corrective prompt wording;
- model call path through `AssistantTurnExecutor.chatFull(...)`;
- tool-loop re-entry behavior;
- read-only inspect retry merge semantics:
  - return `retry` when original is absent;
  - return `retry` when either side has mutation successes;
  - concatenate original and retry tool names in current order;
  - concatenate original and retry tool outcomes in current order;
  - merge and normalize read paths with original paths first;
  - keep retry messages, retry final answer, retry failure decision, and retry
    mutating success count;
  - sum iteration, tool, failure, retry, and cushion counters;
- visible summary behavior, including not double-printing the original summary
  when the inspect retry produces a merged loop result.

T442 must not change:

- `mutationRequestRetryIfNeeded(...)`;
- mutation retry prompt/tool-surface helpers;
- mutation retry trace recording;
- mutation retry evidence merge unless a small shared read-path helper is
  required without behavior change;
- read-only no-tool inspection retry;
- `ToolCallLoop` execution;
- outcome dominance;
- answer wording;
- static-web diagnostics;
- protected-read or unsupported-document behavior.

## After T442

After T442 is integrated, reinspect before choosing T443.

The likely next inspection question is whether the remaining missing-mutation
retry can safely lose its compact prompt/tool-surface envelope:

```text
[T443] Missing-mutation retry prompt envelope boundary decision
```

But that should be confirmed from post-T442 source before code moves.

## Verification For This Ticket

Run before merge:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
