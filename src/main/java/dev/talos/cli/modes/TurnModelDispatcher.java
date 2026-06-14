package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.policy.ProviderRequestControlPolicy;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.ToolSpec;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class TurnModelDispatcher {
    private TurnModelDispatcher() {
    }

    static LlmClient.StreamResult dispatchStreaming(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan
    ) {
        try {
            return chatStreamFull(ctx, messages, plan);
        } catch (EngineException.ContextBudgetExceeded budget) {
            Optional<ExactWriteContextFallback.Request> fallback = ExactWriteContextFallback.prepare(
                    ctx,
                    plan,
                    TurnModelDispatcher::chatControlsForTurn);
            if (fallback.isEmpty()) {
                throw budget;
            }
            ExactWriteContextFallback.record(plan, budget);
            ExactWriteContextFallback.Request request = fallback.get();
            return ctx.llm().chatStreamFull(
                    request.messages(),
                    ctx.streamSink(),
                    request.toolSpecs(),
                    request.controls());
        }
    }

    static LlmClient.StreamResult dispatchBufferedWithTimeout(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            long timeoutMs
    ) throws TimeoutException, ExecutionException, InterruptedException {
        CompletableFuture<LlmClient.StreamResult> fut = CompletableFuture.supplyAsync(
                () -> dispatchBuffered(ctx, messages, plan));
        try {
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (!(cause instanceof EngineException.ContextBudgetExceeded budget)) {
                throw ex;
            }
            Optional<ExactWriteContextFallback.Request> fallback = ExactWriteContextFallback.prepare(
                    ctx,
                    plan,
                    TurnModelDispatcher::chatControlsForTurn);
            if (fallback.isEmpty()) {
                throw ex;
            }
            ExactWriteContextFallback.record(plan, budget);
            CompletableFuture<LlmClient.StreamResult> fallbackFuture = CompletableFuture.supplyAsync(
                    () -> dispatchExactWriteContextFallback(ctx, fallback.get()));
            return fallbackFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    static LlmClient.StreamResult dispatchBuffered(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan
    ) {
        return dispatchBuffered(ctx, messages, plan, ctx.nativeToolSpecs());
    }

    static LlmClient.StreamResult dispatchEscalatedRetry(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            List<ToolSpec> requestToolSpecs
    ) {
        ChatRequestControls controls = chatControlsForTurn(
                ctx,
                plan,
                requestToolSpecsForControls(ctx, requestToolSpecs));
        SamplingControls escalated = new SamplingControls(0.0, null, null, null)
                .mergedWithFallback(controls.sampling());
        return ctx.llm().chatFull(messages, requestToolSpecs, controls.withSampling(escalated));
    }

    static ChatRequestControls chatControlsForTurn(
            Context ctx,
            CurrentTurnPlan plan,
            List<ToolSpec> requestToolSpecs
    ) {
        boolean supportsRequired = ctx != null
                && ctx.llm() != null
                && ctx.llm().supportsRequiredToolChoice();
        boolean supportsNamed = ctx != null
                && ctx.llm() != null
                && ctx.llm().supportsNamedToolChoice();
        return ProviderRequestControlPolicy.forTurn(
                plan,
                requestToolSpecs == null ? List.of() : requestToolSpecs,
                supportsRequired,
                supportsNamed);
    }

    private static LlmClient.StreamResult chatStreamFull(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan
    ) {
        return ctx.llm().chatStreamFull(
                messages,
                ctx.streamSink(),
                ctx.nativeToolSpecs(),
                chatControlsForTurn(ctx, plan, ctx == null ? List.of() : ctx.nativeToolSpecs()));
    }

    private static LlmClient.StreamResult dispatchBuffered(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            List<ToolSpec> requestToolSpecs
    ) {
        return ctx.llm().chatFull(
                messages,
                requestToolSpecs,
                chatControlsForTurn(ctx, plan, requestToolSpecsForControls(ctx, requestToolSpecs)));
    }

    private static LlmClient.StreamResult dispatchExactWriteContextFallback(
            Context ctx,
            ExactWriteContextFallback.Request fallback
    ) {
        return ctx.llm().chatFull(
                fallback.messages(),
                fallback.toolSpecs(),
                fallback.controls());
    }

    private static List<ToolSpec> requestToolSpecsForControls(Context ctx, List<ToolSpec> requestToolSpecs) {
        if (requestToolSpecs != null) return requestToolSpecs;
        if (ctx != null && ctx.nativeToolSpecs() != null) return ctx.nativeToolSpecs();
        if (ctx != null && ctx.llm() != null) return ctx.llm().getToolSpecs();
        return List.of();
    }
}
