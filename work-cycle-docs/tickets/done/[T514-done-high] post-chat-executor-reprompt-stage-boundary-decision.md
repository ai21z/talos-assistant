# [T514-done-high] Post Chat Executor Reprompt Stage Boundary Decision

## Status

Done.

## Scope

T514 reinspects `ToolCallRepromptStage` after T513 extracted normal
tool-reprompt chat execution into `ToolRepromptChatExecutor`.

This is a no-code decision ticket. It does not change runtime behavior,
prompt wording, reprompt ordering, overlay lifecycle, transient retry behavior,
context-budget fallback behavior, engine-error wording, pending-obligation
behavior, protected-path behavior, trace wording, or tool-surface narrowing.

## Source Evidence

Fresh `origin/v0.9.0-beta-dev` after T513:

| Source | Finding |
| --- | --- |
| `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java` | 330 lines after T513. |
| `ToolCallRepromptStage.reprompt(...)` lines 24-320 | Still owns high-level stop/continue ordering. |
| `ToolCallRepromptStage` lines 25-37 | Approval-denied and mutating-denied terminal paths remain local to the stage. |
| `ToolCallRepromptStage` lines 39-66 | Path-policy blocked handling still chooses expected-target scope repair before terminal path-policy stop. |
| `ToolCallRepromptStage` lines 68-80 | Stale edit reread stop remains local and owns exact failure reason text. |
| `ToolCallRepromptStage` lines 82-90 | Terminal read-only stop answer is already delegated to `TerminalReadOnlyStopAnswer`. |
| `ToolCallRepromptStage` lines 103-148 | Post-mutation continuation sequencing remains local and mixes verifier-pass stop, static-web continuation, repair target progress, expected-target progress, P0 skip, and debug wording. |
| `ToolCallRepromptStage` lines 157-167 | Repair and mutation-evidence budget gates are already delegated. |
| `ToolCallRepromptStage` lines 169-179 | Failure-policy stop selection remains local orchestration. |
| `ToolCallRepromptStage` lines 183-222 | Source-evidence and target-readback repair planners are already delegated; the stage chooses their order. |
| `ToolCallRepromptStage` lines 224-247 | Pending action obligation selection before generic overlay remains local. |
| `ToolCallRepromptStage` lines 249-320 | Generic overlay reprompt execution remains local: overlay apply, request snapshot, raw chat result, context-budget handling, connection/model/generic engine errors, transient retry, interrupt handling, and generic exception wording. |
| `ToolRepromptChatExecutor` | Owns normal non-overlay chat execution and shared result application after T513. |
| `ToolRepromptMessageOverlay` | Owns temporary message insertion and cleanup. |
| `ToolRepromptContextBudgetHandler` | Owns context-budget fallback and compact continuation. |

## Candidate Assessment

### Post-Mutation Continuation Selection

Do not extract this next.

The branch is still a sequencing policy, not a single mechanism. It combines:

- verifier-pass short-circuit;
- static-web creation continuation;
- static repair target progress;
- expected-target mutation progress;
- P0 all-success mutation skip behavior;
- debug wording.

Extracting it now would create a broad continuation-policy object before the
owner boundary is proven.

### Stale Edit Reread Stop

Do not extract this next.

It is small, but it is not the highest-value next boundary. It is one terminal
stop branch with exact failure wording and a direct dependency on
`state.staleEditRereadIgnoredPath`. Moving it would reduce the stage by only a
few lines while adding another class with little ownership value.

### Generic Overlay Reprompt Continuation

This is the next coherent implementation boundary, but it needs focused
regressions because T513 already exposed a subtle transient-retry behavior
trap.

The current generic overlay block owns one real mechanism:

- apply temporary repair/progress/current-task overlay messages;
- snapshot request messages while the overlay is active;
- execute the raw chat request;
- preserve overlay cleanup after every path;
- handle context-budget fallback for the normal continuation;
- handle connection, model-not-found, generic engine, and generic exception
  answers with exact existing wording;
- retry once after transient backend errors;
- preserve `(no answer from model after retry)` behavior without pending
  obligation breach;
- preserve transient retry context-budget fallback wording:
  `transient retry continuation`.

That is a cohesive "overlay continuation execution" owner. It is separate from
high-level branch ordering, and it is now small enough to extract with
dedicated tests.

## Decision

Do not implement another extraction in T514.

The next implementation ticket should be:

```text
[T515] Extract generic overlay reprompt continuation
```

Recommended owner:

```text
dev.talos.runtime.toolcall.ToolRepromptOverlayContinuation
```

Recommended responsibility:

- own the generic `ToolRepromptMessageOverlay.apply(...)` try-with-resources
  block;
- own request-message snapshot creation while the overlay is active;
- call `ToolRepromptChatExecutor.executeResult(...)` for the first generic
  overlay request;
- call `ToolRepromptChatExecutor.executeRetryResult(...)` for transient retry;
- preserve exact catch ordering and user-visible answers;
- preserve `Thread.sleep(400)` timing;
- preserve context-budget retry names:
  - `tool-call loop continuation`;
  - `transient retry continuation`;
- return the same boolean loop-continuation result currently returned by the
  stage.

`ToolCallRepromptStage` should still own:

- approval-denial and path-policy branch ordering;
- terminal read-only stop selection;
- post-mutation continuation and P0 skip ordering;
- budget gate ordering;
- failure-policy stop ordering;
- source-evidence and target-readback planner ordering;
- pending action obligation selection before invoking the overlay continuation.

## T515 Test Shape

Start with RED tests that prove the extraction preserves the fragile behavior:

- `ToolCallRepromptStage` delegates generic overlay continuation to
  `ToolRepromptOverlayContinuation`.
- Temporary expected-target progress messages still appear in the transient
  retry request snapshot and are still removed from durable loop history.
- Empty transient retry result with a pending obligation still returns
  `(no answer from model after retry)` and does not breach the obligation.
- Generic overlay context-budget failure still routes through
  `ToolRepromptContextBudgetHandler.handle(state, budget, "tool-call loop continuation")`.
- Transient retry context-budget failure still routes through
  `ToolRepromptContextBudgetHandler.handle(state, budget, "transient retry continuation")`.
- Connection/model/generic engine exception answers remain byte-for-byte
  identical to the current stage answers.

Focused verification should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptOverlayContinuationTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest" --no-daemon
```

Full gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Do Not Touch In T515

T515 must not move:

- post-mutation continuation selection;
- static-web continuation planning;
- expected-target progress accounting;
- static repair target accounting;
- source-evidence repair planning;
- target-readback repair planning;
- budget gate ordering;
- failure-policy stop ordering;
- final outcome rendering.

## Next Move

Start T515 from fresh `origin/v0.9.0-beta-dev` and extract only the generic
overlay reprompt continuation behind the current `ToolCallRepromptStage`
facade.
