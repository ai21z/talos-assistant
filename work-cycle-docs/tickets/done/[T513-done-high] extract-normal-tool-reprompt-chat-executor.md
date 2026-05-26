# [T513-done-high] Extract Normal Tool Reprompt Chat Executor

## Status

Done.

## Scope

T513 extracts normal tool-reprompt chat execution from
`ToolCallRepromptStage` into `ToolRepromptChatExecutor`.

This ticket preserves runtime behavior, prompt wording, reprompt ordering,
overlay lifecycle, transient retry ordering, context-budget fallback behavior,
engine-error wording, pending-obligation behavior, protected-path behavior,
trace wording, and tool-surface narrowing.

## What Changed

- Added `dev.talos.runtime.toolcall.ToolRepromptChatExecutor`.
- Moved normal chat-reprompt execution out of `ToolCallRepromptStage`:
  - `execute(...)` owns the non-overlay chat continuation path;
  - `executeResult(...)` owns the raw `LlmClient.chatFull(...)` result path;
  - `applyResult(...)` owns copying text/native tool calls into `LoopState`.
- Preserved exact empty-response fallbacks:
  - `(no answer from model after tool execution)`;
  - `(no answer from model after retry)`.
- Preserved pending-action-obligation failure precedence before generic
  no-answer fallback.
- Preserved the older transient-retry exception: an empty transient retry
  result uses the retry fallback and does not convert that condition into a
  pending-obligation breach.
- Preserved exact user-visible model-not-found, connection-failed, generic
  engine-error, and generic exception answers.
- Kept the generic overlay transient retry catch block in
  `ToolCallRepromptStage`.
- Kept post-mutation continuation ordering in `ToolCallRepromptStage`.
- Added focused tests for executor behavior and stage ownership.

## RED Verification

The RED test was added before production code:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptChatExecutorTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest.repromptStageDelegatesNormalChatRepromptExecution" --no-daemon
```

It failed at compile time because `ToolRepromptChatExecutor` did not exist:

```text
cannot find symbol
  symbol:   variable ToolRepromptChatExecutor
```

That was the intended failure.

## GREEN Verification

Focused verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptChatExecutorTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest" --no-daemon
```

The focused suite passed after extraction.

Review regression:

```powershell
.\gradlew.bat test --tests "dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest.transientRetryEmptyResultKeepsRetryFallbackDespitePendingObligation" --no-daemon
```

failed before the review fix because the extracted result path breached the
pending action obligation for an empty transient retry. The fix added a
separate retry-result path that preserves the previous retry fallback
semantics.

Full gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Do Not Infer

T513 does not prove the whole `ToolCallRepromptStage` lane is finished.

The stage still owns:

- high-level stop/continue branch ordering;
- approval-denial and path-policy stop behavior;
- stale edit reread stop behavior;
- post-mutation continuation and P0 skip ordering;
- generic overlay transient retry sequencing;
- generic overlay connection/model/engine failure wording;
- pending-obligation selection before the generic overlay request.

## Next Move

Inspect the post-T513 `ToolCallRepromptStage` shape before choosing T514.
Do not assume the next slice is generic overlay transient retry, post-mutation
continuation selection, or lane closeout until source inspection confirms the
next coherent owner.
