# T813 - AssistantTurnExecutor Model Dispatch Extraction

Status: done
Severity: high
Release gate: no - Wave 5 behavior-preserving extraction ticket
Branch: v0.9.0-beta-dev
Created/updated: 2026-06-14
Owner: Codex

## Problem

T811 extracted ordered turn preparation into `AssistantTurnPreparation`, and
T812 characterized the model-dispatch boundary without production changes. The
next Wave 5 step is the first production extraction of model dispatch from
`AssistantTurnExecutor` while preserving the executor as the public turn
entrypoint.

The generated architecture intelligence report still ranks
`cli.modes.AssistantTurnExecutor` first with point-in-time priority index `401`
and `INFERRED_REVIEW` confidence. That value is review-order evidence only; it
is not a success metric and must not drive cosmetic class movement.

## Required Behavior

1. Preserve public `AssistantTurnExecutor.execute(...)` behavior and signature.
2. Extract dispatch mechanics only into a package-private collaborator.
3. Keep retry decisions, trace ownership, tool-loop/no-tool outcome resolution,
   answer shaping, and truthfulness repair in `AssistantTurnExecutor`.
4. Preserve provider request controls for normal dispatch, fallback dispatch,
   and escalated retry dispatch.
5. Preserve streaming completion ordering before tool-loop execution.
6. Preserve the current non-streaming `CompletableFuture.supplyAsync(...)` plus
   `fut.get(opts.llmTimeoutMs, TimeUnit.MILLISECONDS)` timeout shape.
7. Keep provider-body and prompt-debug capture downstream at the client/engine
   boundary: `LlmClient`, `OllamaChatClient`, and `CompatChatClient`.

## Non-Goals

- No package-cycle cleanup.
- No `ToolCallLoop`, tool-loop outcome, no-tool outcome, or answer-shaping
  extraction.
- No movement of `TurnSourceEvidenceCapture.begin/clear` or
  `TurnTaskContractCapture.set/clear`.
- No public CLI/product behavior change.
- No public Java API change.
- No `SetupCmd.java` edit.
- No `codex/qwen36` branch read, merge, or conflict resolution.
- No Qodana task, config, version, mode, or evidence change.
- No release candidate recut.

## Architecture Metadata

- Capability: Wave 5 model-dispatch ownership extraction.
- Operation(s): behavior-preserving Java refactor guarded by deterministic
  characterization tests.
- Owning package/class:
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`.
- New collaborator:
  `src/main/java/dev/talos/cli/modes/TurnModelDispatcher.java`.
- Related evidence:
  `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorModelDispatchCharacterizationTest.java`
  and
  `build/reports/talos/architecture-intelligence/current/11-wave5-ticket-sequence.md`.
- Risk and approval: high, because dispatch controls provider request shape,
  streaming completion, context-budget fallback, and escalated retry sampling.
- Protected path behavior: no new protected reads or artifact writes.
- Checkpoint behavior: no mutation/checkpoint semantic changes.
- Evidence obligation: T813 must keep T812 characterization tests green before
  and after the extraction.
- Verification profile: focused `dev.talos.cli.modes.*` tests, then `check`;
  run `wikiEvidenceCloseGate` if wiki/report claims are updated.
- Allowed refactor scope: `AssistantTurnExecutor`, new
  `TurnModelDispatcher`, focused tests only if an existing characterization
  proves brittle against a behavior-preserving move.

## Pre-Extraction Source Anchors

- Streaming dispatch starts at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:221` through
  `chatStreamFullWithInitialContextFallback(...)`.
- Streaming completion currently runs at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:236` before
  tool-loop execution.
- Non-streaming dispatch starts at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:279` through
  `CompletableFuture.supplyAsync(...)`.
- Non-streaming timeout waits at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:283`.
- Non-streaming exact-write context fallback dispatch starts at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:289`.
- Streaming fallback helper starts at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:864`.
- Provider request controls start at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:913`.
- Request tool-spec resolution starts at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:948`.
- Escalated retry dispatch starts at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:2460`.

## Move Table

Move these mechanics into package-private `final class TurnModelDispatcher`:

- `chatStreamFull(...)`.
- `chatStreamFullWithInitialContextFallback(...)`.
- `chatFull(...)` overloads used for provider dispatch.
- `chatFullExactWriteContextFallback(...)`.
- `chatFullEscalatedRetry(...)`.
- `chatControlsForTurn(...)`.
- `requestToolSpecsForControls(...)`.
- The non-streaming buffered dispatch execution shape:
  `CompletableFuture.supplyAsync(...)`, `fut.get(timeout, TimeUnit.MILLISECONDS)`,
  context-budget fallback preparation, fallback recording, and fallback provider
  dispatch.

Keep these responsibilities in `AssistantTurnExecutor` during T813:

- `AssistantTurnExecutor.execute(...)`.
- `shouldUseStreaming(...)` and branch selection.
- `TurnSourceEvidenceCapture.begin()` / `clear()`.
- `TurnTaskContractCapture.set(...)` / `clear()`.
- Tool-loop entry, `ctx.toolCallLoop().run(...)`, and loop-result handling.
- `resolveToolLoopAnswer(...)`.
- `resolveNoToolAnswer(...)`.
- `shapeAnswerWithoutTools(...)` and streaming/no-tool correction emission.
- Retry decision ownership, including whether a missing mutation retry,
  inspection retry, grounding retry, or static verification repair retry should
  run.
- `compatibilityPlanFromMessages(...)` and `safePlanFromMessages(...)`.
- `TurnOutput` assembly and `streamed` flag ownership.

## Collaborator Shape

Create:

```java
package dev.talos.cli.modes;

final class TurnModelDispatcher {
    private TurnModelDispatcher() {
    }
}
```

The collaborator should expose package-private static dispatch methods. It is
not a service, registry, thread owner, or DI container. It should not retain
mutable state between turns.

Required method surface for T813:

```java
static LlmClient.StreamResult dispatchStreaming(
        Context ctx,
        List<ChatMessage> messages,
        CurrentTurnPlan plan);

static LlmClient.StreamResult dispatchBufferedWithTimeout(
        Context ctx,
        List<ChatMessage> messages,
        CurrentTurnPlan plan,
        long timeoutMs) throws TimeoutException, ExecutionException, InterruptedException;

static LlmClient.StreamResult dispatchBuffered(
        Context ctx,
        List<ChatMessage> messages,
        CurrentTurnPlan plan);

static LlmClient.StreamResult dispatchEscalatedRetry(
        Context ctx,
        List<ChatMessage> messages,
        CurrentTurnPlan plan,
        List<ToolSpec> requestToolSpecs);
```

If implementation discovers this exact surface causes broader changes, stop and
record the reason in this ticket before changing the boundary. Do not expand the
collaborator into outcome resolution.

## Guard Checklist

- Run T812 characterization before editing production code.
- Keep streaming dispatch synchronous on the executor thread. Do not introduce a
  new asynchronous streaming hop.
- Preserve the existing non-streaming asynchronous timeout shape exactly:
  `CompletableFuture.supplyAsync(...)` and `get(timeoutMs, TimeUnit.MILLISECONDS)`.
- Move the temperature `0.0` escalated-retry pin with dispatch.
- Keep the four ordinary retry callbacks synchronous and no-timeout. They must
  use `dispatchBuffered(...)`, never `dispatchBufferedWithTimeout(...)`.
- Resolve the four ordinary retry plans executor-side from the retry messages:
  `compatibilityPlanFromMessages(retryMessages, ctx)` or equivalent
  `safePlanFromMessages(null, retryMessages, ctx)`.
- Rebind the missing-mutation retry lambda to
  `TurnModelDispatcher.dispatchEscalatedRetry(...)`.
- Rebind both `ExactWriteContextFallback.prepare(...)` method references from
  `AssistantTurnExecutor::chatControlsForTurn` to the dispatcher-owned controls
  method or an equivalent package-private adapter.
- Keep `compatibilityPlanFromMessages(...)` executor-side for this ticket.
- Do not move prompt-debug/provider-body capture into the dispatcher. It remains
  downstream of `ctx.llm()` in the LLM/client layer.
- Do not touch `SetupCmd.java`.

## Test Coverage Map

- `nonStreamingMutationDispatchForwardsRequiredToolChoiceToProvider` protects
  normal buffered dispatch request tool specs and `ToolChoiceMode.REQUIRED`.
- `missingMutationEscalatedRetryUsesZeroTemperatureSampling` protects the
  escalated retry path and the temperature `0.0` sampling pin.
- `streamingAndBufferedNoToolDispatchCharacterizesFinalAnswerShape` protects
  streaming versus buffered no-tool final-answer shape across the dispatch
  boundary.
- `toolOnlyStreamingResponseInvokesOnStreamCompleteOnceBeforeToolLoop` protects
  stream-completion ordering before tool-loop-visible work.
- Existing `dev.talos.cli.modes.*` tests remain the broader behavior gate for
  policy trace, prompt-debug state, project memory, approval blocking, retry,
  verification-status override, and final-answer postconditions.

## Binding Table

| Call site | Current dispatch | T813 dispatch |
|---|---|---|
| Main streaming turn at `AssistantTurnExecutor.java:222` | `chatStreamFullWithInitialContextFallback(ctx, messages, currentTurnPlan)` | `TurnModelDispatcher.dispatchStreaming(ctx, messages, currentTurnPlan)` |
| Main buffered turn at `AssistantTurnExecutor.java:280` | `CompletableFuture.supplyAsync(() -> chatFull(turnContext, llmMessages, currentTurnPlan))` plus `fut.get(opts.llmTimeoutMs, TimeUnit.MILLISECONDS)` | `TurnModelDispatcher.dispatchBufferedWithTimeout(turnContext, llmMessages, currentTurnPlan, opts.llmTimeoutMs)` |
| Read-only inspection retry at `AssistantTurnExecutor.java:711` | `retryMessages -> chatFull(ctx, retryMessages)` | `retryMessages -> TurnModelDispatcher.dispatchBuffered(ctx, retryMessages, compatibilityPlanFromMessages(retryMessages, ctx))` |
| Post-tool synthesis retry at `AssistantTurnExecutor.java:2290` | `retryMessages -> chatFull(ctx, retryMessages)` | `retryMessages -> TurnModelDispatcher.dispatchBuffered(ctx, retryMessages, compatibilityPlanFromMessages(retryMessages, ctx))` |
| Inspect-completeness retry at `AssistantTurnExecutor.java:2544` | `retryMessages -> chatFull(ctx, retryMessages)` | `retryMessages -> TurnModelDispatcher.dispatchBuffered(ctx, retryMessages, compatibilityPlanFromMessages(retryMessages, ctx))` |
| No-tool grounding retry at `AssistantTurnExecutor.java:3005` | `retryMessages -> chatFull(ctx, retryMessages)` | `retryMessages -> TurnModelDispatcher.dispatchBuffered(ctx, retryMessages, compatibilityPlanFromMessages(retryMessages, ctx))` |
| Missing-mutation escalated retry at `AssistantTurnExecutor.java:2451` | `(retryMessages, retryPlan, retryToolSpecs) -> chatFullEscalatedRetry(ctx, retryMessages, retryPlan, retryToolSpecs)` | `(retryMessages, retryPlan, retryToolSpecs) -> TurnModelDispatcher.dispatchEscalatedRetry(ctx, retryMessages, retryPlan, retryToolSpecs)` |

`dispatchBufferedWithTimeout(...)` must surface `TimeoutException`,
`ExecutionException`, and `InterruptedException` with the same observable
catch-ladder behavior as `AssistantTurnExecutor.execute(...)` has today. It may
consume only `EngineException.ContextBudgetExceeded` internally to perform the
current exact-write compact fallback before rethrowing or returning.

## Implementation Steps

1. Run the focused T812 characterization test before production edits.
2. Add package-private `TurnModelDispatcher`.
3. Move provider request-control helpers into `TurnModelDispatcher`.
4. Move streaming dispatch and streaming context-budget fallback helpers.
5. Move buffered main-turn dispatch and buffered context-budget fallback while
   preserving the asynchronous timeout shape.
6. Move synchronous buffered retry dispatch without adding timeout behavior.
7. Move escalated retry dispatch and rebind all five retry dispatch call sites
   according to the binding table.
8. Keep `AssistantTurnExecutor` branch logic and outcome handling unchanged.
9. Run the focused T812 characterization test.
10. Run `dev.talos.cli.modes.*`.
11. Run full `check`.
12. Regenerate architecture intelligence only if the ticket or wiki closeout
    needs updated report evidence.

## Acceptance Criteria

- `TurnModelDispatcher` exists as package-private code in `dev.talos.cli.modes`.
- `AssistantTurnExecutor` no longer owns provider request-control construction
  or raw `ctx.llm().chatFull/chatStreamFull(...)` dispatch helpers.
- `AssistantTurnExecutor.execute(...)` still owns branch selection, trace
  begin/set/clear, tool-loop/no-tool outcome resolution, retry decisions,
  answer shaping, and `TurnOutput`.
- T812 characterization tests pass before and after the extraction.
- Full `dev.talos.cli.modes.*` tests pass.
- Full `check` passes.
- No `site/` files are staged or committed with T813.

## Completion State

- T813 extracted model-dispatch mechanics into package-private
  `TurnModelDispatcher`.
- `AssistantTurnExecutor` now delegates:
  - main streaming dispatch to `TurnModelDispatcher.dispatchStreaming(...)`;
  - main buffered dispatch and exact-write context fallback to
    `TurnModelDispatcher.dispatchBufferedWithTimeout(...)`;
  - four ordinary retry dispatch callbacks to synchronous no-timeout
    `TurnModelDispatcher.dispatchBuffered(...)` with retry-message plan
    resolution kept executor-side;
  - missing-mutation escalated retry dispatch to
    `TurnModelDispatcher.dispatchEscalatedRetry(...)`.
- `AssistantTurnExecutor` still owns branch selection, trace begin/set/clear,
  tool-loop/no-tool outcome resolution, retry decisions, answer shaping, and
  `TurnOutput` assembly.
- Provider-body and prompt-debug capture remain downstream in `LlmClient`,
  `OllamaChatClient`, and `CompatChatClient`.
- Generated architecture evidence after the extraction records
  `cli.modes.AssistantTurnExecutor` with point-in-time priority index `384`
  and `INFERRED_REVIEW` confidence. This is review-order evidence, not a
  success metric.
- T813 did not start package-cycle cleanup and did not extract tool-loop or
  no-tool outcome ownership.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorModelDispatchCharacterizationTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.*" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
git status --short
```

Run this only if wiki/report claims change during closeout:

```powershell
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

## Starting Evidence

- T811 turn preparation extraction committed at
  `0ae6f3084fc3274a7682c73a454b35c952d86639`.
- T812 model-dispatch characterization committed at
  `bde6081bcf57880812ab089a037624473440e0f4`.
- T812 closeout ledger committed at
  `6e7a39655eaa1c18dbefad35894b7f530c69d024`.
- Generated architecture evidence before the T813 extraction recorded
  `cli.modes.AssistantTurnExecutor` first with priority index `401`.
- Generated architecture evidence before the T813 extraction recorded
  `cli.modes.AssistantTurnPreparation` with priority index `136`.
- Provider-body/prompt-debug capture exists downstream of executor dispatch in
  `LlmClient`, `OllamaChatClient`, and `CompatChatClient`; T813 should not
  relocate it.
