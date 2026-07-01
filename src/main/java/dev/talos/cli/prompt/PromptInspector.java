package dev.talos.cli.prompt;

import dev.talos.cli.repl.Context;
import dev.talos.core.CfgUtil;
import dev.talos.core.llm.SystemPromptBuilder;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.CapabilityPosturePolicy;
import dev.talos.runtime.policy.CurrentTurnPromptInstructions;
import dev.talos.runtime.policy.PromptWorkspaceContextPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.toolcall.NativeToolSpecPolicy;
import dev.talos.runtime.toolcall.PromptToolDescriptors;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;

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
        TaskContract rawContract = usesAssistantTaskContract(resolvedMode)
                ? WorkspaceTargetReconciler.reconcile(
                        TaskContractResolver.fromUserRequest(input),
                        workspace)
                : TaskContract.unknown(input);
        CapabilityPosturePolicy.EffectiveTurn effectiveTurn = effectiveTurn(resolvedMode, rawContract);
        TaskContract contract = WorkspaceTargetReconciler.reconcile(effectiveTurn.taskContract(), workspace);
        ExecutionPhase initialPhase = effectiveTurn.phase();
        boolean directoryListing = "agent".equals(resolvedMode)
                && contract.type() == TaskType.DIRECTORY_LISTING;
        List<ToolSpec> effectiveSpecs = effectiveToolSpecs(resolvedMode, contract, initialPhase, ctx);
        List<String> effectiveTools = NativeToolSpecPolicy.names(effectiveSpecs);
        boolean includeWorkspaceContext =
                PromptWorkspaceContextPolicy.includeWorkspaceManifest(effectiveTools);

        SystemPromptBuilder builder = builderFor(resolvedMode)
                .withNativeTools(nativeTools)
                .withHistory(hasHistory)
                .withDirectoryListingToolMode(directoryListing);
        if (usesAssistantTaskContract(resolvedMode)) {
            if (includeWorkspaceContext) {
                builder
                        .withPromptTools(PromptToolDescriptors.fromRegistry(
                                ctx == null ? null : ctx.toolRegistry(),
                                effectiveSpecs))
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
        if (usesAssistantTaskContract(resolvedMode)) {
            CurrentTurnPromptInstructions.injectTaskContractInstruction(
                    messages,
                    contract,
                    initialPhase,
                    effectiveTools);
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
                        includeWorkspaceContext),
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
        String canonicalMode = resolvePromptMode(resolvedMode);
        TaskContract rawContract = WorkspaceTargetReconciler.reconcile(
                TaskContractResolver.fromMessages(messages),
                workspace);
        CapabilityPosturePolicy.EffectiveTurn effectiveTurn = effectiveTurn(canonicalMode, rawContract);
        TaskContract contract = WorkspaceTargetReconciler.reconcile(effectiveTurn.taskContract(), workspace);
        List<String> effectiveTools = NativeToolSpecPolicy.names(
                effectiveToolSpecs(canonicalMode, contract, effectiveTurn.phase(), ctx));
        boolean includeWorkspaceContext =
                PromptWorkspaceContextPolicy.includeWorkspaceManifest(effectiveTools);
        return new PromptRender(
                normalizeMode(requestedMode),
                canonicalMode,
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
                        canonicalMode,
                        workspace,
                        historyMessages > 0,
                        nativeTools,
                        effectiveTools,
                        includeWorkspaceContext),
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
            case "plan" -> "plan";
            default -> "agent";
        };
    }

    private static SystemPromptBuilder builderFor(String resolvedMode) {
        return switch (resolvePromptMode(resolvedMode)) {
            case "rag" -> SystemPromptBuilder.forRag();
            case "ask" -> SystemPromptBuilder.forAsk();
            case "plan" -> SystemPromptBuilder.forPlan();
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

    private static List<ToolSpec> effectiveToolSpecs(
            String resolvedMode,
            TaskContract contract,
            ExecutionPhase phase,
            Context ctx
    ) {
        if (ctx == null || ctx.toolRegistry() == null) return List.of();
        if (ctx.hasNativeToolSpecOverride()) {
            return ctx.nativeToolSpecs();
        }
        if (usesAssistantTaskContract(resolvePromptMode(resolvedMode)) && contract != null) {
            return NativeToolSpecPolicy.select(contract, phase, ctx.toolRegistry(), ctx.cfg());
        }
        return ctx.toolRegistry().descriptors().stream()
                .map(descriptor -> new ToolSpec(
                        descriptor.name(),
                        descriptor.description(),
                        descriptor.parametersSchema()))
                .toList();
    }

    private static CapabilityPosturePolicy.EffectiveTurn effectiveTurn(
            String resolvedMode,
            TaskContract contract
    ) {
        return CapabilityPosturePolicy.apply(postureFor(resolvedMode), contract);
    }

    private static CapabilityPosture postureFor(String resolvedMode) {
        return switch (resolvePromptMode(resolvedMode)) {
            case "ask" -> CapabilityPosture.ASK_READ_ONLY;
            case "plan" -> CapabilityPosture.PLAN_READ_ONLY;
            default -> CapabilityPosture.AGENT;
        };
    }

    private static boolean usesAssistantTaskContract(String resolvedMode) {
        return switch (resolvePromptMode(resolvedMode)) {
            case "agent", "ask", "plan" -> true;
            default -> false;
        };
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
