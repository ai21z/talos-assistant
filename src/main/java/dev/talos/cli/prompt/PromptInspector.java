package dev.talos.cli.prompt;

import dev.talos.cli.repl.Context;
import dev.talos.core.CfgUtil;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.llm.SystemPromptBuilder;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.CurrentTurnPromptInstructions;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.toolcall.NativeToolSpecPolicy;
import dev.talos.runtime.toolcall.PromptToolDescriptors;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PromptInspector {
    public static final String DEFAULT_INPUT_PLACEHOLDER = "<next user message>";

    private PromptInspector() {}

    public static PromptRender renderNext(
            String requestedMode,
            String userInput,
            Path workspace,
            Context ctx
    ) {
        String mode = normalizeMode(requestedMode);
        String resolvedMode = resolvePromptMode(mode);
        boolean hasHistory = hasHistory(ctx);
        boolean nativeTools = nativeTools(ctx);
        List<ChatMessage> history = buildHistory(resolvedMode, ctx);
        String input = userInput == null || userInput.isBlank()
                ? DEFAULT_INPUT_PLACEHOLDER
                : userInput;
        TaskContract contract = "unified".equals(resolvedMode)
                ? WorkspaceTargetReconciler.reconcile(
                        TaskContractResolver.fromUserRequest(input),
                        workspace)
                : TaskContract.unknown(input);
        boolean smallTalk = "unified".equals(resolvedMode)
                && contract.type() == TaskType.SMALL_TALK;
        boolean directoryListing = "unified".equals(resolvedMode)
                && contract.type() == TaskType.DIRECTORY_LISTING;
        ExecutionPhase initialPhase = CurrentTurnPlan.defaultPhaseFor(contract);
        List<String> effectiveTools = effectiveToolNames(resolvedMode, contract, ctx);

        SystemPromptBuilder builder = builderFor(resolvedMode)
                .withNativeTools(nativeTools)
                .withHistory(hasHistory)
                .withDirectoryListingToolMode(directoryListing);
        if ("unified".equals(resolvedMode)) {
            if (!smallTalk) {
                builder
                        .withPromptTools(PromptToolDescriptors.fromRegistry(ctx == null ? null : ctx.toolRegistry()))
                        .withVisibleToolNames(effectiveTools)
                        .withWorkspace(workspace)
                        .withReadOnlyToolMode(!contract.mutationAllowed())
                        .withCommandToolMode(initialPhase == ExecutionPhase.VERIFY);
            }
        } else {
            builder
                    .withPromptTools(PromptToolDescriptors.fromRegistry(ctx == null ? null : ctx.toolRegistry()))
                    .withWorkspace(workspace);
        }
        String system = builder.build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(system));
        messages.addAll(history);
        messages.add(ChatMessage.user(input));
        if ("unified".equals(resolvedMode)) {
            CurrentTurnPromptInstructions.injectTaskContractInstruction(messages);
        }

        List<String> registryTools = registryToolNames(ctx);

        return new PromptRender(
                mode,
                resolvedMode,
                modelName(ctx),
                nativeTools,
                workspace,
                history.size(),
                contract.type().name(),
                contract.mutationAllowed(),
                contract.verificationRequired(),
                registryTools,
                effectiveTools,
                sectionNames(
                        resolvedMode,
                        workspace,
                        hasHistory,
                        nativeTools,
                        effectiveTools,
                        !smallTalk),
                messages,
                Instant.now()
        );
    }

    public static PromptRender fromMessages(
            String requestedMode,
            String resolvedMode,
            Path workspace,
            Context ctx,
            boolean nativeTools,
            int historyMessages,
            List<ChatMessage> messages
    ) {
        TaskContract contract = WorkspaceTargetReconciler.reconcile(
                TaskContractResolver.fromMessages(messages),
                workspace);
        List<String> effectiveTools = effectiveToolNames(resolvePromptMode(resolvedMode), contract, ctx);
        return new PromptRender(
                normalizeMode(requestedMode),
                resolvePromptMode(resolvedMode),
                modelName(ctx),
                nativeTools,
                workspace,
                historyMessages,
                contract.type().name(),
                contract.mutationAllowed(),
                contract.verificationRequired(),
                registryToolNames(ctx),
                effectiveTools,
                sectionNames(
                        resolvePromptMode(resolvedMode),
                        workspace,
                        historyMessages > 0,
                        nativeTools,
                        effectiveTools,
                        contract.type() != TaskType.SMALL_TALK),
                messages,
                Instant.now()
        );
    }

    public static String format(PromptRender render) {
        if (render == null) return "No prompt render is available.\n";

        StringBuilder sb = new StringBuilder();
        sb.append("# Talos Prompt Render\n\n");
        sb.append("- Rendered at: ").append(render.renderedAt()).append('\n');
        sb.append("- Requested mode: ").append(render.requestedMode()).append('\n');
        sb.append("- Resolved prompt mode: ").append(render.resolvedMode()).append('\n');
        sb.append("- Model: ").append(render.model()).append('\n');
        sb.append("- Native tools: ").append(render.nativeTools()).append('\n');
        sb.append("- Workspace: ").append(render.workspace().toAbsolutePath().normalize()).append('\n');
        sb.append("- History messages included: ").append(render.historyMessages()).append('\n');
        sb.append("- Task contract: ")
                .append(render.taskType())
                .append(" mutationAllowed=")
                .append(render.mutationAllowed())
                .append(" verificationRequired=")
                .append(render.verificationRequired())
                .append('\n');
        sb.append("- Tools exposed: ");
        sb.append(render.tools().isEmpty() ? "(none)" : String.join(", ", render.tools()));
        sb.append('\n');
        if (!render.registryTools().equals(render.tools())) {
            sb.append("- Registry tools: ");
            sb.append(render.registryTools().isEmpty()
                    ? "(none)"
                    : String.join(", ", render.registryTools()));
            sb.append('\n');
        }
        sb.append("- Sections: ");
        sb.append(render.sections().isEmpty() ? "(unknown)" : String.join(", ", render.sections()));
        sb.append('\n');
        sb.append("- Prompt chars: ").append(render.promptChars()).append('\n');
        sb.append("- Estimated tokens: ").append(render.estimatedTokens()).append("\n\n");

        sb.append("## Messages\n\n");
        for (int i = 0; i < render.messages().size(); i++) {
            ChatMessage message = render.messages().get(i);
            sb.append("### ").append(i + 1).append(". ").append(message.role()).append("\n\n");
            sb.append("```text\n");
            sb.append(message.content() == null ? "" : message.content());
            sb.append("\n```\n\n");
        }
        return sb.toString();
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) return "auto";
        return mode.toLowerCase(Locale.ROOT).trim();
    }

    private static String resolvePromptMode(String mode) {
        String normalized = normalizeMode(mode);
        return switch (normalized) {
            case "rag" -> "rag";
            case "ask" -> "ask";
            default -> "unified";
        };
    }

    private static SystemPromptBuilder builderFor(String resolvedMode) {
        return switch (resolvePromptMode(resolvedMode)) {
            case "rag" -> SystemPromptBuilder.forRag();
            case "ask" -> SystemPromptBuilder.forAsk();
            default -> SystemPromptBuilder.forUnified();
        };
    }

    private static boolean nativeTools(Context ctx) {
        if (ctx == null || ctx.cfg() == null) return true;
        return CfgUtil.boolAt(CfgUtil.map(ctx.cfg().data.get("tools")), "native_calling", true);
    }

    private static boolean hasHistory(Context ctx) {
        return (ctx != null && ctx.conversationManager() != null && ctx.conversationManager().hasHistory())
                || (ctx != null && ctx.memory() != null && ctx.memory().hasContent());
    }

    private static List<ChatMessage> buildHistory(String resolvedMode, Context ctx) {
        if (ctx == null) return List.of();
        if (ctx.conversationManager() != null) {
            return "rag".equals(resolvePromptMode(resolvedMode))
                    ? ctx.conversationManager().buildHistory()
                    : ctx.conversationManager().buildHistoryForAssist();
        }
        if (ctx.memory() != null) return ctx.memory().getTurns();
        return List.of();
    }

    @SuppressWarnings("resource") // ctx.llm() is a borrowed REPL-scoped client.
    private static String modelName(Context ctx) {
        if (ctx == null || ctx.llm() == null) return "unknown";
        return ctx.llm().getModel();
    }

    private static List<String> effectiveToolNames(String resolvedMode, TaskContract contract, Context ctx) {
        if (ctx == null || ctx.toolRegistry() == null) return List.of();
        if (ctx.hasNativeToolSpecOverride()) {
            return NativeToolSpecPolicy.names(ctx.nativeToolSpecs());
        }
        if ("unified".equals(resolvePromptMode(resolvedMode)) && contract != null) {
            ExecutionPhase phase = CurrentTurnPlan.defaultPhaseFor(contract);
            return NativeToolSpecPolicy.names(
                    NativeToolSpecPolicy.select(contract, phase, ctx.toolRegistry()));
        }
        return registryToolNames(ctx);
    }

    private static List<String> registryToolNames(Context ctx) {
        if (ctx == null || ctx.toolRegistry() == null) return List.of();
        return ctx.toolRegistry().descriptors().stream()
                .map(descriptor -> descriptor.name())
                .sorted()
                .toList();
    }

    private static List<String> sectionNames(
            String resolvedMode,
            Path workspace,
            boolean hasHistory,
            boolean nativeTools,
            List<String> effectiveTools,
            boolean includeWorkspaceSection
    ) {
        List<String> sections = new ArrayList<>();
        sections.add("identity");
        if (workspace != null && includeWorkspaceSection) sections.add("workspace");
        sections.add("mode:" + resolvePromptMode(resolvedMode));
        if (effectiveTools != null && !effectiveTools.isEmpty()) {
            sections.add(nativeTools ? "tools:native" : "tools:text-fallback");
        }
        if (hasHistory) sections.add("conversation");
        return sections;
    }
}
