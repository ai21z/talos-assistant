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
import java.util.concurrent.ExecutionException;
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
        try {
            return dispatchBuffered(ctx, messages, plan, timeoutMs);
        } catch (EngineException.ContextBudgetExceeded budget) {
            Optional<ExactWriteContextFallback.Request> fallback = ExactWriteContextFallback.prepare(
                    ctx,
                    plan,
                    TurnModelDispatcher::chatControlsForTurn);
            if (fallback.isEmpty()) {
                throw budget;
            }
            ExactWriteContextFallback.record(plan, budget);
            return dispatchExactWriteContextFallback(ctx, fallback.get(), timeoutMs);
        }
    }

    static LlmClient.StreamResult dispatchBuffered(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan
    ) {
        return dispatchBuffered(ctx, messages, plan, ctx.nativeToolSpecs(), null);
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
                chatControlsForTurn(ctx, plan, ctx.nativeToolSpecs()));
    }

    private static LlmClient.StreamResult dispatchBuffered(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            long timeoutMs
    ) {
        return dispatchBuffered(ctx, messages, plan, ctx.nativeToolSpecs(), timeoutMs);
    }

    private static LlmClient.StreamResult dispatchBuffered(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            List<ToolSpec> requestToolSpecs
    ) {
        return dispatchBuffered(ctx, messages, plan, requestToolSpecs, null);
    }

    private static LlmClient.StreamResult dispatchBuffered(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            List<ToolSpec> requestToolSpecs,
            Long timeoutMs
    ) {
        ChatRequestControls controls = chatControlsForTurn(
                ctx,
                plan,
                requestToolSpecsForControls(ctx, requestToolSpecs));
        if (timeoutMs != null) {
            return ctx.llm().chatFull(messages, timeoutMs, requestToolSpecs, controls);
        }
        return ctx.llm().chatFull(
                messages,
                requestToolSpecs,
                controls);
    }

    private static LlmClient.StreamResult dispatchExactWriteContextFallback(
            Context ctx,
            ExactWriteContextFallback.Request fallback,
            long timeoutMs
    ) {
        return ctx.llm().chatFull(
                fallback.messages(),
                timeoutMs,
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
