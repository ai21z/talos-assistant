# [T516-done-high] Post Overlay Reprompt Stage Boundary Decision

## Status

Done.

## Scope

T516 reinspects `ToolCallRepromptStage` after T515 extracted generic overlay
reprompt execution into `ToolRepromptOverlayContinuation`.

This is a no-code decision ticket. It does not change runtime behavior,
prompt wording, retry ordering, static-web continuation behavior,
post-mutation skip behavior, pending-obligation behavior, failure-policy
ordering, trace wording, or tool-surface narrowing.

## Source Evidence

Fresh `origin/v0.9.0-beta-dev` after T515:

| Source | Finding |
| --- | --- |
| `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java` | 260 lines after T515. |
| `ToolCallRepromptStage.reprompt(...)` lines 22-34 | Approval-denied and mutating-denied terminal stops remain local. |
| `ToolCallRepromptStage.reprompt(...)` lines 36-63 | Path-policy blocked handling still chooses expected-target scope repair before terminal path-policy stop. |
| `ToolCallRepromptStage.reprompt(...)` lines 65-77 | Stale edit reread terminal stop remains local and owns exact failure reason text. |
| `ToolCallRepromptStage.reprompt(...)` lines 79-86 | Terminal read-only stop selection is already delegated to `TerminalReadOnlyStopAnswer`. |
| `ToolCallRepromptStage.reprompt(...)` lines 100-145 | Successful-mutation continuation selection remains local: verifier-pass stop, static-web continuation, remaining static repair targets, remaining expected targets, P0 all-success skip, and progress logging. |
| `ToolCallRepromptStage.reprompt(...)` lines 147-151 | Partial-success logging remains local and intentionally falls through. |
| `ToolCallRepromptStage.reprompt(...)` lines 154-164 | Repair and mutation-evidence budget gates are already delegated. |
| `ToolCallRepromptStage.reprompt(...)` lines 166-174 | Failure-policy stop selection remains local orchestration. |
| `ToolCallRepromptStage.reprompt(...)` lines 176-220 | Source-evidence and target-readback repair planners are already delegated; the stage chooses their order. |
| `ToolCallRepromptStage.reprompt(...)` lines 222-253 | Pending-obligation selection and final generic overlay delegation remain local. |
| `ToolRepromptOverlayContinuation` | Owns generic overlay execution, transient retry, and overlay context-budget handling after T515. |

## Candidate Assessment

### Terminal Stop Branches

Do not extract next.

Approval-denied, mutating-denied, stale reread, and path-policy terminal stops
are small branches with exact wording and ordering significance. Moving one now
would reduce line count without creating a clearer policy owner.

### Source/Target Repair Planner Ordering

Do not extract next.

`SourceEvidenceExactRepairPlanner` and `TargetReadbackCompactRepairPlanner`
already own their mechanisms. The stage currently owns their order, and that
ordering is still part of high-level reprompt orchestration.

### Pending-Obligation Selection Before Overlay

Do not extract next.

The final obligation/tool-surface selection is coherent, but it is tightly
coupled to the generic overlay handoff and should not move until the
post-mutation branch is separated. Extracting it first would split the tail of
the method while leaving the larger successful-mutation branch in the facade.

### Successful-Mutation Continuation Selection

This is the next coherent implementation boundary.

The branch is one real decision unit:

- if static web verification already passes, stop and surface mutation
  summaries;
- compute remaining static repair and expected mutation targets;
- if no remaining progress targets exist, ask `StaticWebContinuationPlanner`
  whether a directory-only/static-web continuation is still needed;
- if no continuation and no remaining targets exist, preserve the P0
  all-success mutation skip;
- otherwise log the remaining static repair and expected-target progress and
  fall through to the later reprompt path.

This is not a random extraction: it owns exactly the successful-mutation
post-iteration decision before the generic failure-policy and overlay path.

## Decision

The next implementation ticket should be:

```text
[T517] Extract successful mutation reprompt decision
```

Recommended owner:

```text
dev.talos.runtime.toolcall.ToolRepromptSuccessfulMutationDecision
```

Recommended API shape:

```java
static Optional<Boolean> tryHandle(LoopState state, ToolCallExecutionStage.IterationOutcome outcome)
```

`Optional.empty()` means the stage should continue to later budget, failure,
planner, and overlay logic. `Optional.of(true/false)` means the successful
mutation branch made the existing loop decision.

T517 should preserve:

- verifier-pass short-circuit wording and `state.clearPendingActionObligation()`;
- static-web continuation planner behavior and debug wording;
- P0 all-success skip behavior;
- remaining static repair and expected-target debug wording;
- fall-through behavior when remaining targets still require another reprompt.

## Do Not Touch In T517

T517 must not move:

- approval-denied or mutating-denied terminal stops;
- path-policy blocked repair handling;
- stale edit reread terminal stop;
- terminal read-only stop selection;
- repair/mutation-evidence budget gates;
- failure-policy stop ordering;
- source-evidence repair planning;
- target-readback repair planning;
- pending-obligation selection before generic overlay;
- generic overlay execution.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

After T516 is merged and beta push CI is green, start T517 from fresh beta and
extract only the successful-mutation continuation decision described above.
