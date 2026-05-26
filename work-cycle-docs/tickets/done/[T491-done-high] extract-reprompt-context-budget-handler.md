# [T491-done-high] Extract Reprompt Context Budget Handler

## Status

Done.

## Scope

T491 extracts context-budget and compact-continuation fallback handling from
`ToolCallRepromptStage` into `ToolRepromptContextBudgetHandler`.

This ticket does not change tool execution, approval behavior, protected-read
behavior, request assembly, repair planning, failure-policy stop rendering,
denied-mutation response-only synthesis, trace wording, prompt wording,
outcome wording, or final-answer behavior.

## What Changed

- Added `dev.talos.runtime.toolcall.ToolRepromptContextBudgetHandler`.
- `ToolCallRepromptStage` now delegates:
  - `EngineException.ContextBudgetExceeded` handling from normal reprompts;
  - context-budget handling from transient retry reprompts;
  - context-budget handling from helper `chatReprompt(...)` calls;
  - read-only mutation-evidence budget compact-continuation handling.
- Compact mutation continuation execution and its private outcome enum now live
  inside `ToolRepromptContextBudgetHandler`.
- `ToolCallRepromptStage` still owns high-level stop/continue orchestration and
  the predicate that decides when read-only mutation evidence budget has been
  exhausted.
- `ToolCallRepromptStage.java` moved from 884 lines to 719 lines.

## Behavior Preservation Notes

The handler preserves the existing boolean contract:

- `true` means continue the tool loop;
- `false` means stop the turn.

The extracted code preserves the existing order:

1. record `CONTEXT_BUDGET_RETRY_SKIPPED`;
2. let pending action obligations fail before fallbacks;
3. try compact mutation continuation;
4. fall back to compact read-only evidence continuation;
5. otherwise emit the deterministic context-budget failure answer.

The compact mutation continuation path still records the same trace warning and
action-obligation labels, still retries with narrowed write/edit tools, and
still stops with the deterministic no-action answer when the compact retry
returns no executable tool call.

## Verification

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptContextBudgetHandlerTest" --no-daemon
```

failed before implementation because `ToolRepromptContextBudgetHandler` did not
exist.

Additional RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptContextBudgetHandlerTest.repromptStageDelegatesContextBudgetHandlingToOwner" --no-daemon
```

failed after the first extraction because `ToolCallRepromptStage` still reached
into the handler's compact-continuation enum/method.

Focused GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptContextBudgetHandlerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*ContextBudget*" --tests "dev.talos.runtime.ToolCallLoopTest.*CompactMutationContinuation*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.CompactMutationContinuationPlannerTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
```

Full ticket gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Next Move

Inspect the post-T491 `ToolCallRepromptStage` shape before choosing T492.
Do not assume another extraction. The remaining candidates include
failure-policy stop rendering, denied-mutation response-only synthesis,
expected-target/read-only progress accounting, or a short closeout/retarget
decision.
