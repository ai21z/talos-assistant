# [T512-done-high] Tool Reprompt Chat Execution Boundary Decision

## Status

Done.

## Scope

T512 reinspects `ToolCallRepromptStage` after T511 extracted static
full-rewrite repair target accounting.

This is a no-code decision ticket. It does not change runtime behavior,
prompt wording, reprompt ordering, transient retry behavior, context-budget
fallback behavior, engine-error wording, static-web repair behavior,
expected-target progress, pending-obligation behavior, protected-path behavior,
trace wording, or tool-surface narrowing.

## Source Evidence

Fresh `origin/v0.9.0-beta-dev` after T511:

| Source | Finding |
| --- | --- |
| `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java` | 401 lines. |
| `ToolCallRepromptStage.reprompt(...)` lines 28-326 | Still owns high-level stop/continue ordering for approval denial, path-policy repair, terminal read-only answers, post-mutation continuation, failure policy, compacting, repair planners, overlay lifecycle, and engine failures in the generic overlay path. |
| `ToolCallRepromptStage` lines 94-149 | Still owns post-mutation stop/continue sequencing. This branch mixes verifier-pass short-circuit, static-web continuation planning, static repair target progress, expected-target progress, P0 skip behavior, and exact debug wording. |
| `ToolCallRepromptStage` lines 247-263 | Applies `ToolRepromptMessageOverlay`, snapshots request messages after overlay insertion, and executes the generic reprompt call while the overlay is still active. |
| `ToolCallRepromptStage` lines 263-323 | The generic overlay path still owns context-budget, connection, model-not-found, transient retry, generic engine-error, and generic exception handling. |
| `ToolCallRepromptStage` lines 328-365 | `chatReprompt(...)` owns the normal non-overlay chat continuation error handling and exact user-visible engine failure wording. |
| `ToolCallRepromptStage` lines 367-392 | `chatRepromptResult(...)` owns the raw `LlmClient.chatFull(...)` call, state update from the stream result, empty-response fallback, and pending-action-obligation failure after no executable tool calls. |
| `ToolRepromptMessageOverlay` | Owns temporary repair/progress/current-task messages and cleanup. |
| `ToolRepromptRequestBuilder` | Owns request message assembly, tool-surface narrowing, and request controls. |
| `ToolRepromptContextBudgetHandler` | Owns context-budget fallback, compact mutation continuation, and compact read-only evidence continuation. |
| `StaticRepairTargetProgressAccounting` | Owns static full-rewrite repair target accounting after T511. |

## Candidate Assessment

### Post-Mutation Continuation Selection

Do not extract this next.

That branch is still a high-order sequencing decision, not one small
mechanism. It combines:

- verifier-pass stop behavior;
- static-web creation continuation;
- static full-rewrite repair progress;
- expected-target progress;
- P0 all-success mutation skip behavior;
- pending obligation state;
- exact debug wording.

Moving it now would create a broad continuation-policy object before the
actual stable boundary is clear.

### Generic Overlay Transient Retry

Do not move this first.

The overlay path has a special transient retry rule: it snapshots
`requestMessages` while temporary overlay messages are still applied, then
reuses that snapshot after cleanup-sensitive failures. That was the fragile
part of the T509 overlay extraction. Moving it without a dedicated regression
packet would risk changing prompt-debug evidence and retry behavior.

### Normal Chat Reprompt Execution

This is the next coherent implementation boundary, but it must be sliced
narrowly.

The current stage has a repeated live execution responsibility:

- call `state.ctx.llm().chatFull(...)`;
- copy returned text and native tool calls back into `LoopState`;
- normalize null text to empty text;
- apply the exact empty-response fallback;
- apply pending-action-obligation failure after no executable tool calls;
- handle context-budget fallback through `ToolRepromptContextBudgetHandler`;
- preserve exact connection, model-not-found, and generic engine-error answers.

That responsibility is not the same as deciding when to continue. The stage
should keep branch ordering. A dedicated executor should own the mechanics of
performing a bounded reprompt request and translating engine results/errors
into `LoopState`.

## Decision

Do not implement a broad continuation extraction in T512.

The next implementation ticket should be:

```text
[T513] Extract normal tool reprompt chat executor
```

Recommended owner:

```text
dev.talos.runtime.toolcall.ToolRepromptChatExecutor
```

Recommended first responsibility:

- move the normal `chatReprompt(...)` path out of `ToolCallRepromptStage`;
- move the shared `chatRepromptResult(...)` state-update behavior into that
  owner;
- preserve exact text, tool-call copying, empty-response fallback, pending
  obligation behavior, and engine-error wording;
- keep the generic overlay transient-retry branch in `ToolCallRepromptStage`
  for this first extraction, except for any shared result-application call that
  can be moved without changing retry order;
- keep `ToolCallRepromptStage` as the orchestrator for branch ordering and
  overlay lifecycle.

## T513 Test Shape

Start with focused RED ownership and behavior tests:

- `ToolCallRepromptStage` delegates normal chat reprompt execution to
  `ToolRepromptChatExecutor`.
- The executor copies text and native tool calls from `LlmClient.StreamResult`
  exactly as the current stage does.
- Null text still becomes empty text.
- Empty text plus no native calls still falls back to pending mutation
  summaries when present.
- Empty text plus no native calls still uses
  `(no answer from model after tool execution)` when no pending mutation
  summary exists.
- Pending action obligation failure after no executable tool calls is still
  checked before the generic no-answer fallback.
- `EngineException.ContextBudgetExceeded` still delegates to
  `ToolRepromptContextBudgetHandler.handle(state, budget, retryName)`.
- `EngineException.ConnectionFailed`, `EngineException.ModelNotFound`, and
  generic `EngineException` still produce byte-for-byte identical
  user-visible answers.

Focused verification should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptChatExecutorTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest" --no-daemon
```

Full gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Do Not Touch In T513

T513 must not move:

- post-mutation continuation selection;
- `StaticWebContinuationPlanner`;
- `ExpectedTargetProgressAccounting`;
- `StaticRepairTargetProgressAccounting`;
- `ToolRepromptRequestBuilder`;
- `ToolRepromptMessageOverlay`;
- generic overlay transient retry ordering;
- `Thread.sleep(400)` retry timing;
- context-budget compact continuation behavior;
- pending-obligation wording or precedence;
- static-web diagnostics;
- final outcome rendering.

## Next Move

Start T513 from fresh `origin/v0.9.0-beta-dev` and extract only normal
tool-reprompt chat execution behind the current `ToolCallRepromptStage`
facade.
