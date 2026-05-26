# [T504-done-high] Remaining Reprompt Stage Boundary Decision

## Status

Done.

## Scope

T504 reinspects `ToolCallRepromptStage` after T503 removed the stale and empty
edit repair-policy wrapper methods.

This is a no-code decision ticket. It does not change runtime behavior,
reprompt ordering, prompt wording, repair wording, continuation prompts, tool
surface narrowing, approval handling, failure policy, trace behavior, protected
path behavior, or verification behavior.

## Source Evidence

Fresh `origin/v0.9.0-beta-dev` after T503:

| Source | Finding |
| --- | --- |
| `ToolCallRepromptStage.java` | 517 lines |
| `ToolCallRepromptStage.reprompt(...)` | still owns high-level continuation ordering |
| `ToolCallRepromptStage` lines 98-153 | owns post-mutation stop, continuation, and expected-target progress ordering |
| `ToolCallRepromptStage` lines 228-409 | owns temporary repair/progress/current-task message insertion and cleanup |
| `ToolCallRepromptStage` lines 412-478 | owns live chat reprompt execution and exact engine-error fallback wording |
| `ToolCallRepromptStage` lines 489-495 | defines `canonicalToolName(...)`, but the helper has no call site in this class |
| `ToolCallRepromptStage` line 18 | imports `dev.talos.tools.ToolAliasPolicy` only for the unused helper |

Relevant existing owners already exist:

- `StaticWebContinuationPlanner` owns static-web continuation planning.
- `ExpectedTargetProgressAccounting` owns remaining expected-target accounting.
- `ToolRepromptRequestBuilder` owns reprompt request assembly, tool narrowing,
  and compact static-repair reprompt messages.
- `ToolRepromptContextBudgetHandler` owns context-budget fallback and compact
  mutation continuation execution.
- `ToolRepairInspectionBudgetGate` owns repair/fix read-only inspection budget
  stops.
- `ToolMutationEvidenceBudgetGate` owns mutation read-only evidence budget
  handoff.
- `RepairPolicy` owns stale and empty edit repair instruction policy.

## Decision

Do not start a broad extraction from `ToolCallRepromptStage` yet.

The three broad candidates remain behavior-sensitive:

- post-mutation continuation/skip selection;
- temporary repair/progress/current-task message overlay and cleanup;
- generic chat reprompt execution and engine-error fallback handling.

Each affects live prompt shape, failure truthfulness, cleanup guarantees, or
exact user-visible wording. Moving any of them before a tighter owner is proven
would be counter-chasing.

The inspection did find one safe implementation cleanup: the unused
`canonicalToolName(...)` helper and its `ToolAliasPolicy` import should be
removed from `ToolCallRepromptStage`.

That is a real ownership fix, not a random extraction:

- canonical tool-name policy is still needed elsewhere, but not by the
  reprompt-stage facade;
- keeping the dead helper makes `ToolCallRepromptStage` appear to own alias
  normalization even though no current branch calls it;
- removing it changes no runtime path and reduces false ownership signal.

## Next Coherent Implementation Slice

The next implementation ticket should be:

```text
[T505] Remove dead reprompt-stage alias helper
```

Scope:

- delete `ToolCallRepromptStage.canonicalToolName(...)`;
- remove the unused `ToolAliasPolicy` import from `ToolCallRepromptStage`;
- add or update a focused source ownership test proving the stage no longer
  imports `ToolAliasPolicy` or declares the helper;
- preserve all behavior and wording.

This ticket should not touch:

- post-mutation continuation selection;
- `remainingFullRewriteRepairTargets(...)`;
- temporary message insertion or cleanup;
- `chatReprompt(...)`;
- `chatRepromptResult(...)`;
- transient retry, connection, model-not-found, or generic engine-error
  wording;
- compact mutation continuation;
- static-web diagnostic movement.

## T505 Test Shape

Start with a RED ownership test in `ToolCallRepromptStageTest` or a nearby
reprompt-stage ownership test:

```java
assertFalse(source.contains("import dev.talos.tools.ToolAliasPolicy;"), source);
assertFalse(source.contains("canonicalToolName("), source);
```

The test should fail before the production deletion because the import and
helper still exist.

Focused verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon
```

Full gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Later Inspection

After T505, inspect again before extracting anything else from the stage.

If the dead alias helper is gone, the next decision must choose between:

- post-mutation continuation/skip selection;
- temporary reprompt message overlay and cleanup;
- chat reprompt execution/error handling;
- or closing this lane again until a behavior-backed owner emerges.
