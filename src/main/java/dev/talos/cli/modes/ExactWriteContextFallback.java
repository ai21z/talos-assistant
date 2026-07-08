package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.expectation.LiteralContentExpectation;
import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.CurrentTurnCapabilityFrame;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Compact current-turn fallback for exact literal writes that overflow context before the first backend call. */
final class ExactWriteContextFallback {
    private static final String COMPACT_EXACT_WRITE_CONTEXT_FALLBACK_SYSTEM_PROMPT = """
            Talos compact current-turn retry.
            The full conversation exceeded the local context budget before the backend call.
            Ignore prior conversation history. Execute only the current exact file-write request using the available tool.
            Prose/manual snippets do not change files; call the required tool.
            """;

    private static final String DEBUG_TAG = "context-budget-current-turn-fallback";

    private ExactWriteContextFallback() {}

    @FunctionalInterface
    interface ControlsFactory {
        ChatRequestControls controls(
                Context ctx,
                CurrentTurnPlan plan,
                List<ToolSpec> requestToolSpecs);
    }

    record Request(
            List<ChatMessage> messages,
            List<ToolSpec> toolSpecs,
            ChatRequestControls controls
    ) {}

    static Optional<Request> prepare(
            Context ctx,
            CurrentTurnPlan plan,
            ControlsFactory controlsFactory
    ) {
        if (!shouldAttempt(plan)) {
            return Optional.empty();
        }
        List<ToolSpec> toolSpecs = toolSpecs(ctx);
        if (toolSpecs.isEmpty()) {
            return Optional.empty();
        }
        CurrentTurnPlan compactPlan = compactPlan(plan);
        List<ChatMessage> messages = compactMessages(compactPlan);
        ChatRequestControls controls = withDebugTag(
                controlsFactory.controls(ctx, compactPlan, toolSpecs),
                DEBUG_TAG);
        return Optional.of(new Request(messages, toolSpecs, controls));
    }

    static void record(
            CurrentTurnPlan plan,
            EngineException.ContextBudgetExceeded budget
    ) {
        String obligation = plan == null || plan.actionObligation() == null
                ? ActionObligation.UNKNOWN.name()
                : plan.actionObligation().name();
        String reason = "initial request exceeded context budget before backend call; "
                + "retrying current exact write with compact prompt and talos.write_file only. "
                + "estimatedTokens=" + budget.estimatedTokens()
                + ", inputBudgetTokens=" + budget.inputBudgetTokens()
                + ", contextWindowTokens=" + budget.contextWindowTokens();
        LocalTurnTraceCapture.recordActionObligation(
                obligation,
                "RETRIED_COMPACT_CONTEXT",
                reason,
                "CONTEXT_BUDGET_CURRENT_TURN_FALLBACK");
        LocalTurnTraceCapture.warning(
                "CONTEXT_BUDGET_CURRENT_TURN_FALLBACK",
                "Retried the current exact file write with compact prompt after the full turn exceeded context budget.");
    }

    private static boolean shouldAttempt(CurrentTurnPlan plan) {
        if (plan == null || plan.taskContract() == null) return false;
        if (!plan.taskContract().mutationAllowed()) return false;
        if (plan.actionObligation() != ActionObligation.MUTATING_TOOL_REQUIRED) return false;
        if (plan.taskExpectations().isEmpty()) return false;
        return plan.taskExpectations().stream()
                .anyMatch(ExactWriteContextFallback::isExactLiteralContentExpectation);
    }

    private static boolean isExactLiteralContentExpectation(TaskExpectation expectation) {
        return expectation instanceof LiteralContentExpectation literal
                && literal.matchMode() == LiteralContentExpectation.MatchMode.EXACT
                && !literal.targetPath().isBlank();
    }

    private static CurrentTurnPlan compactPlan(CurrentTurnPlan plan) {
        return new CurrentTurnPlan(
                plan.taskContract(),
                plan.originalUserRequest(),
                plan.phaseInitial(),
                plan.phaseFinal(),
                plan.actionObligation(),
                plan.taskExpectations(),
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                plan.blockedTools(),
                plan.evidenceObligation(),
                plan.outputObligation(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                plan.artifactGoal(),
                plan.verifierProfile());
    }

    private static List<ChatMessage> compactMessages(CurrentTurnPlan plan) {
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system(COMPACT_EXACT_WRITE_CONTEXT_FALLBACK_SYSTEM_PROMPT));
        out.add(ChatMessage.system(CurrentTurnCapabilityFrame.render(plan)));
        out.add(ChatMessage.user(Objects.toString(plan.originalUserRequest(), "")));
        return out;
    }

    private static List<ToolSpec> toolSpecs(Context ctx) {
        List<ToolSpec> base = requestToolSpecsForControls(ctx);
        if (base.isEmpty()) return base;
        return base.stream()
                .filter(Objects::nonNull)
                .filter(spec -> "talos.write_file".equals(spec.name()))
                .map(ExactWriteContextFallback::compactWriteFileToolSpec)
                .toList();
    }

    private static List<ToolSpec> requestToolSpecsForControls(Context ctx) {
        if (ctx != null && ctx.nativeToolSpecs() != null) return ctx.nativeToolSpecs();
        if (ctx != null && ctx.llm() != null) return ctx.llm().getToolSpecs();
        return List.of();
    }

    private static ToolSpec compactWriteFileToolSpec(ToolSpec spec) {
        if (spec == null) return null;
        return new ToolSpec(
                "talos.write_file",
                "Write file.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}");
    }

    private static ChatRequestControls withDebugTag(ChatRequestControls controls, String tag) {
        ChatRequestControls safe = controls == null ? ChatRequestControls.defaults() : controls;
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
                safe.maxOutputTokens());
    }
}
