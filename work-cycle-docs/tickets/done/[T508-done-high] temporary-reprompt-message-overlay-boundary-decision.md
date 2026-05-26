# [T508-done-high] Temporary Reprompt Message Overlay Boundary Decision

## Status

Done.

## Scope

T508 reinspects `ToolCallRepromptStage` after T507 removed the remaining dead
task-contract imports.

This is a no-code decision ticket. It does not change runtime behavior,
reprompt ordering, prompt wording, continuation planning, repair wording,
tool-surface narrowing, approval handling, failure policy, trace behavior,
protected-path behavior, or verification behavior.

## Source Evidence

Fresh `origin/v0.9.0-beta-dev` after T507:

| Source | Finding |
| --- | --- |
| `ToolCallRepromptStage.java` | 506 lines |
| `ToolCallRepromptStage` lines 225-239 | inserts stale-edit and empty-edit repair messages and records prompted paths |
| `ToolCallRepromptStage` lines 241-263 | inserts static-repair and expected-target progress messages |
| `ToolCallRepromptStage` lines 265-279 | sets or clears pending action obligation based on remaining targets |
| `ToolCallRepromptStage` lines 285-289 | inserts the current-task anchor message |
| `ToolCallRepromptStage` lines 365-405 | removes temporary messages in reverse insertion order using content-prefix guards |
| `ToolRepromptRequestBuilder.messages(...)` | owns compact static-repair request construction when static-repair obligation is active |
| `ToolCallRepromptStageToolSurfaceTest` | verifies static repair and expected-target reprompt tool surfaces and compact static-repair prompt payload |

The temporary overlay is now a coherent owner because:

- it has a lifecycle: add temporary messages before the continuation call, then
  remove them even when the continuation fails;
- cleanup order matters because the indices are valid only when removed in
  reverse insertion order;
- stale/empty repair message insertion has side effects on prompted-path sets;
- progress message wording must remain exact;
- the current-task anchor uses a bounded 500-character copy and must be cleaned
  after the attempt.

## Decision

Do not extract post-mutation continuation selection or chat reprompt execution
yet.

The next implementation ticket should extract the temporary message overlay
behind the current `ToolCallRepromptStage` facade. This is more coherent than
moving post-mutation selection because it owns a concrete lifecycle boundary
instead of policy branching. It is also less risky than moving chat execution
because it does not change engine-error handling or transient retry behavior.

## Next Coherent Implementation Slice

The next implementation ticket should be:

```text
[T509] Extract tool reprompt message overlay
```

Recommended owner:

```text
dev.talos.runtime.toolcall.ToolRepromptMessageOverlay
```

Recommended responsibility:

- apply stale-edit repair messages from `RepairPolicy.nextStaleEditRepair(...)`;
- apply empty-edit repair messages from `RepairPolicy.nextEmptyEditRepair(...)`;
- apply static-repair progress message;
- apply expected-target progress message;
- apply current-task anchor message with the existing 500-character truncation;
- record the existing prompted-path side effects;
- clean up only those temporary messages, in reverse insertion order, using the
  existing content-prefix guards.

Recommended shape:

```java
try (ToolRepromptMessageOverlay overlay = ToolRepromptMessageOverlay.apply(
        state,
        remainingRepairTargets,
        remainingExpectedTargets,
        userTask)) {
    ...
}
```

The stage should continue to own:

- post-mutation continuation/skip ordering;
- remaining-target calculation;
- pending action obligation selection;
- tool-surface selection;
- chat reprompt execution;
- transient retry and exact error wording.

## T509 Test Shape

Start with RED tests for the new overlay owner:

- applying stale and empty repair instructions adds the same message text and
  updates the same prompted-path sets;
- applying repair and expected-target progress adds the exact existing progress
  messages;
- applying a long current-task anchor truncates at 500 characters and appends
  the same suffix;
- closing the overlay removes temporary messages and leaves pre-existing
  messages intact;
- cleanup still happens if the continuation path throws before normal return;
- `ToolCallRepromptStage` delegates temporary message lifecycle to
  `ToolRepromptMessageOverlay` and no longer contains the five inline cleanup
  prefix checks.

Focused verification should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptMessageOverlayTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.llm.ToolCallRepromptStagePromptDebugTest" --no-daemon
```

Full gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Do Not Touch In T509

T509 must not move:

- post-mutation continuation selection;
- `remainingFullRewriteRepairTargets(...)`;
- `hasStaticRepairContext(...)`;
- pending-obligation decision rules;
- `ToolRepromptRequestBuilder`;
- `chatReprompt(...)`;
- `chatRepromptResult(...)`;
- transient retry, connection, model-not-found, generic engine-error, or no
  answer wording;
- compact mutation continuation;
- static-web diagnostic movement.

## Later Inspection

After T509, inspect again before moving behavior. The likely remaining
candidates will be:

- post-mutation continuation/skip selection;
- chat reprompt execution and engine-error fallback wording;
- static full-rewrite repair-target calculation.
