package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.policy.ProviderPromptSurfaceReconciler;
import dev.talos.runtime.policy.ProviderRequestControlPolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.ToolSpec;

import java.util.ArrayList;
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
        TurnExecutionStage priorStage = enterGenerationStage();
        try {
            return restoreStageAfterSuccess(
                    chatStreamFull(ctx, messages, plan), priorStage);
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
            return restoreStageAfterSuccess(
                    ctx.llm().chatStreamFull(
                            request.messages(),
                            ctx.streamSink(),
                            request.toolSpecs(),
                            request.controls()),
                    priorStage);
        }
    }

    static LlmClient.StreamResult dispatchBufferedWithTimeout(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            long timeoutMs
    ) throws TimeoutException, ExecutionException, InterruptedException {
        TurnExecutionStage priorStage = enterGenerationStage();
        try {
            return restoreStageAfterSuccess(
                    dispatchBuffered(ctx, messages, plan, timeoutMs), priorStage);
        } catch (EngineException.ContextBudgetExceeded budget) {
            Optional<ExactWriteContextFallback.Request> fallback = ExactWriteContextFallback.prepare(
                    ctx,
                    plan,
                    TurnModelDispatcher::chatControlsForTurn);
            if (fallback.isEmpty()) {
                throw budget;
            }
            ExactWriteContextFallback.record(plan, budget);
            return restoreStageAfterSuccess(
                    dispatchExactWriteContextFallback(ctx, fallback.get(), timeoutMs),
                    priorStage);
        }
    }

    static LlmClient.StreamResult dispatchBuffered(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan
    ) {
        TurnExecutionStage priorStage = enterGenerationStage();
        return restoreStageAfterSuccess(
                dispatchBufferedForPlan(
                        ctx, messages, plan, null),
                priorStage);
    }

    static LlmClient.StreamResult dispatchBufferedRetry(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan capturedPlan,
            RetryDispatchPurpose purpose
    ) {
        if (purpose == null) {
            throw new IllegalArgumentException("retry purpose is required");
        }
        if (capturedPlan == null) {
            throw new IllegalArgumentException(
                    "captured retry plan is required");
        }
        if (ctx == null || ctx.llm() == null) {
            throw new IllegalArgumentException(
                    "retry provider context is required");
        }
        CurrentTurnPlan retryPlan = purpose.project(capturedPlan);
        List<ToolSpec> requestToolSpecs =
                retryToolSpecs(
                        availableToolSpecs(ctx),
                        retryPlan.nativeTools(),
                        retryPlan.promptTools(),
                        retryPlan.blockedTools());
        CurrentTurnPlan dispatchPlan =
                dispatchPlan(retryPlan, requestToolSpecs);
        List<ChatMessage> retryMessages =
                messagesForPlan(
                        messages,
                        dispatchPlan,
                        requestToolSpecs);
        ChatRequestControls controls = withDebugTag(
                chatControlsForTurn(
                        ctx, dispatchPlan, requestToolSpecs),
                purpose.debugTag());
        TurnExecutionStage priorStage = enterGenerationStage();
        return restoreStageAfterSuccess(
                ctx.llm().chatFull(
                        retryMessages,
                        requestToolSpecs,
                        controls),
                priorStage);
    }

    static LlmClient.StreamResult dispatchEscalatedRetry(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan capturedPlan,
            List<ToolSpec> requestToolSpecs,
            RetryDispatchPurpose purpose
    ) {
        if (purpose == null) {
            throw new IllegalArgumentException(
                    "escalated retry purpose is required");
        }
        if (capturedPlan == null) {
            throw new IllegalArgumentException(
                    "captured escalated retry plan is required");
        }
        if (ctx == null || ctx.llm() == null) {
            throw new IllegalArgumentException(
                    "escalated retry provider context is required");
        }
        if (requestToolSpecs == null || requestToolSpecs.isEmpty()) {
            throw new IllegalArgumentException(
                    "escalated retry tool surface is required");
        }
        CurrentTurnPlan retryPlan = purpose.project(capturedPlan);
        List<ToolSpec> boundedToolSpecs = retryToolSpecs(
                requestToolSpecs,
                retryPlan.nativeTools(),
                retryPlan.promptTools(),
                retryPlan.blockedTools());
        if (boundedToolSpecs.size() != requestToolSpecs.size()
                || !withinAvailableToolSurface(
                        availableToolSpecs(ctx),
                        requestToolSpecs)) {
            throw new IllegalArgumentException(
                    "escalated retry tool surface exceeds the captured "
                            + "current-turn authority");
        }
        CurrentTurnPlan dispatchPlan =
                dispatchPlan(retryPlan, boundedToolSpecs);
        List<ChatMessage> retryMessages =
                messagesForPlan(
                        messages,
                        dispatchPlan,
                        boundedToolSpecs);
        ChatRequestControls controls = withDebugTag(
                chatControlsForTurn(
                        ctx,
                        dispatchPlan,
                        boundedToolSpecs),
                purpose.debugTag());
        SamplingControls escalated = new SamplingControls(0.0, null, null, null)
                .mergedWithFallback(controls.sampling());
        TurnExecutionStage priorStage = enterGenerationStage();
        return restoreStageAfterSuccess(
                ctx.llm().chatFull(
                        retryMessages,
                        boundedToolSpecs,
                        controls.withSampling(escalated)),
                priorStage);
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
        List<ToolSpec> requestToolSpecs =
                requestToolSpecsForPlan(ctx, plan);
        CurrentTurnPlan dispatchPlan =
                dispatchPlan(plan, requestToolSpecs);
        List<ChatMessage> requestMessages =
                messagesForPlan(
                        messages,
                        dispatchPlan,
                        requestToolSpecs);
        return ctx.llm().chatStreamFull(
                requestMessages,
                ctx.streamSink(),
                requestToolSpecs,
                chatControlsForTurn(
                        ctx, dispatchPlan, requestToolSpecs));
    }

    private static LlmClient.StreamResult dispatchBuffered(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            long timeoutMs
    ) {
        return dispatchBufferedForPlan(
                ctx, messages, plan, timeoutMs);
    }

    private static LlmClient.StreamResult dispatchBufferedForPlan(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Long timeoutMs
    ) {
        List<ToolSpec> requestToolSpecs =
                requestToolSpecsForPlan(ctx, plan);
        CurrentTurnPlan dispatchPlan =
                dispatchPlan(plan, requestToolSpecs);
        return dispatchBuffered(
                ctx,
                messagesForPlan(
                        messages,
                        dispatchPlan,
                        requestToolSpecs),
                dispatchPlan,
                requestToolSpecs,
                timeoutMs);
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
        return availableToolSpecs(ctx);
    }

    private static List<ToolSpec> requestToolSpecsForPlan(
            Context ctx,
            CurrentTurnPlan plan
    ) {
        if (plan == null) return List.of();
        return retryToolSpecs(
                availableToolSpecs(ctx),
                plan.nativeTools(),
                plan.promptTools(),
                plan.blockedTools());
    }

    private static List<ToolSpec> availableToolSpecs(Context ctx) {
        if (ctx == null) return List.of();
        if (ctx.hasNativeToolSpecOverride()) {
            return ctx.nativeToolSpecs();
        }
        if (ctx.llm() != null) {
            return ctx.llm().getToolSpecs();
        }
        return List.of();
    }

    private static CurrentTurnPlan dispatchPlan(
            CurrentTurnPlan plan,
            List<ToolSpec> requestToolSpecs
    ) {
        if (plan == null) return null;
        java.util.Set<String> selectedNames =
                requestToolSpecs == null
                        ? java.util.Set.of()
                        : requestToolSpecs.stream()
                                .filter(java.util.Objects::nonNull)
                                .map(ToolSpec::name)
                                .filter(java.util.Objects::nonNull)
                                .collect(java.util.stream.Collectors
                                        .toUnmodifiableSet());
        List<String> nativeTools = plan.nativeTools().stream()
                .filter(selectedNames::contains)
                .filter(name -> !plan.blockedTools().contains(name))
                .toList();
        List<String> promptTools = plan.promptTools().stream()
                .filter(selectedNames::contains)
                .filter(name -> !plan.blockedTools().contains(name))
                .toList();
        return new CurrentTurnPlan(
                plan.taskContract(),
                plan.originalUserRequest(),
                plan.phaseInitial(),
                plan.phaseFinal(),
                plan.actionObligation(),
                plan.taskExpectations(),
                nativeTools,
                promptTools,
                plan.blockedTools(),
                plan.evidenceObligation(),
                plan.outputObligation(),
                plan.activeTaskContext(),
                plan.artifactGoal(),
                plan.verifierProfile(),
                plan.requestObligation(),
                plan.workspaceOperationPlan(),
                plan.contentMutationPredecessorPaths());
    }

    private static ChatRequestControls withDebugTag(
            ChatRequestControls controls,
            String tag
    ) {
        ChatRequestControls safe =
                controls == null ? ChatRequestControls.defaults() : controls;
        if (tag == null || tag.isBlank() || safe.debugTags().contains(tag)) {
            return safe;
        }
        List<String> tags = new ArrayList<>(safe.debugTags());
        tags.add(tag.strip());
        return new ChatRequestControls(
                safe.toolChoice(),
                safe.namedTool(),
                safe.responseFormat(),
                safe.jsonSchema(),
                tags,
                safe.sampling(),
                safe.maxOutputTokens(),
                safe.toolAuthorityMode(),
                safe.singleAttempt());
    }

    private static List<ToolSpec> retryToolSpecs(
            List<ToolSpec> available,
            List<String> capturedNativeNames,
            List<String> capturedPromptNames,
            List<String> blockedNames
    ) {
        if (available == null
                || available.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
        boolean nativeAuthority =
                capturedNativeNames != null
                        && !capturedNativeNames.isEmpty();
        if (nativeAuthority) {
            allowed.addAll(capturedNativeNames);
        } else if (capturedPromptNames != null) {
            allowed.addAll(capturedPromptNames);
        }
        if (blockedNames != null) {
            allowed.removeAll(blockedNames);
        }
        if (allowed.isEmpty()) return List.of();
        java.util.Map<String, ToolSpec> availableByName =
                new java.util.LinkedHashMap<>();
        for (ToolSpec spec : available) {
            if (spec != null && spec.name() != null) {
                availableByName.putIfAbsent(spec.name(), spec);
            }
        }
        List<ToolSpec> selected = new ArrayList<>();
        for (String name : allowed) {
            ToolSpec spec = availableByName.get(name);
            if (spec != null) selected.add(spec);
        }
        return List.copyOf(selected);
    }

    private static boolean withinAvailableToolSurface(
            List<ToolSpec> available,
            List<ToolSpec> requested
    ) {
        if (available == null
                || available.isEmpty()
                || requested == null
                || requested.isEmpty()) {
            return false;
        }
        java.util.Set<String> availableNames = available.stream()
                .filter(java.util.Objects::nonNull)
                .map(ToolSpec::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return requested.stream()
                .allMatch(spec -> spec != null
                        && availableNames.contains(spec.name()));
    }

    private static List<ChatMessage> messagesForPlan(
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            List<ToolSpec> requestToolSpecs
    ) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException(
                    "retry messages are required");
        }
        if (plan == null) {
            throw new IllegalArgumentException(
                    "captured current-turn plan is required");
        }
        List<ChatMessage> retryMessages = new ArrayList<>(messages);
        ProviderPromptSurfaceReconciler.reconcile(
                retryMessages,
                plan,
                requestToolSpecs);
        return ProviderPromptSurfaceReconciler
                .canonicalizeNormalizedLf(retryMessages);
    }

    private static TurnExecutionStage enterGenerationStage() {
        TurnExecutionStage prior = LocalTurnTraceCapture.currentExecutionStage();
        LocalTurnTraceCapture.enterExecutionStage(
                TurnExecutionStage.GENERATION_COORDINATION);
        return prior;
    }

    private static LlmClient.StreamResult restoreStageAfterSuccess(
            LlmClient.StreamResult result,
            TurnExecutionStage prior
    ) {
        if (!AssistantTurnExecutor.isAbortedGeneration(
                result,
                result == null ? "" : result.text())) {
            LocalTurnTraceCapture.enterExecutionStage(
                    prior == null ? TurnExecutionStage.UNKNOWN : prior);
        }
        return result;
    }
}
