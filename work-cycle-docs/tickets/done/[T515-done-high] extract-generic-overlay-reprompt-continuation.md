# [T515-done-high] Extract Generic Overlay Reprompt Continuation

## Status

Done.

## Scope

T515 extracts the generic overlay reprompt continuation out of
`ToolCallRepromptStage` into `ToolRepromptOverlayContinuation`.

The ticket intentionally preserves runtime behavior, prompt wording, overlay
lifecycle, transient retry behavior, context-budget retry names, engine-error
answers, pending-obligation handling, protected-path handling, trace wording,
and tool-surface narrowing.

## What Changed

- Added `ToolRepromptOverlayContinuation`.
- `ToolCallRepromptStage` now delegates only the final generic overlay
  continuation call.
- `ToolRepromptOverlayContinuation` owns:
  - temporary `ToolRepromptMessageOverlay.apply(...)` lifecycle;
  - request-message snapshot creation while temporary overlay messages are
    active;
  - first generic overlay `ToolRepromptChatExecutor.executeResult(...)` call;
  - transient retry `ToolRepromptChatExecutor.executeRetryResult(...)` call;
  - `Thread.sleep(400)` retry delay;
  - context-budget retry names:
    - `tool-call loop continuation`;
    - `transient retry continuation`;
  - connection/model/generic engine exception fallback answers.
- Updated ownership tests so the stage no longer owns overlay execution or raw
  chat-result retry mechanics.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptOverlayContinuationTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest.repromptStageDelegatesGenericOverlayContinuation" --no-daemon
```

The intended RED failure was `cannot find symbol:
ToolRepromptOverlayContinuation`.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptOverlayContinuationTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest.repromptStageDelegatesGenericOverlayContinuation" --no-daemon
```

The focused RED/GREEN command passed after adding the new owner and delegating
from `ToolCallRepromptStage`.

Focused regression pass:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptOverlayContinuationTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.toolcall.ToolRepromptChatExecutorTest" --tests "dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest" --no-daemon
```

This keeps coverage on:

- temporary expected-target progress overlay snapshotting;
- durable history cleanup after overlay close;
- transient retry overlay preservation;
- empty transient retry fallback despite pending obligations;
- expected/static repair tool-surface narrowing.

## Not Changed

T515 does not move:

- approval denial handling;
- path-policy blocked handling;
- stale edit reread stop handling;
- terminal read-only stop selection;
- post-mutation continuation selection;
- static-web continuation planning;
- expected-target progress accounting;
- static repair target accounting;
- source-evidence repair planning;
- target-readback repair planning;
- budget gate ordering;
- failure-policy stop ordering;
- final outcome rendering.

## Verification Passed

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

`git diff --check` passed with line-ending warnings only.

## Next Move

After T515 is merged and beta push CI is green, inspect the post-T515
`ToolCallRepromptStage` shape before choosing T516. Do not assume the next
slice is another extraction without source inspection.
