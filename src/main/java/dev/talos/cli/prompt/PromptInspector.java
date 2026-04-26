package dev.talos.cli.prompt;

import dev.talos.cli.repl.Context;
import dev.talos.core.CfgUtil;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.llm.SystemPromptBuilder;
import dev.talos.runtime.toolcall.NativeToolSpecPolicy;
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

        String system = builderFor(resolvedMode)
                .withTools(ctx == null ? null : ctx.toolRegistry())
                .withWorkspace(workspace)
                .withNativeTools(nativeTools)
                .withHistory(hasHistory)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(system));
        messages.addAll(history);
        messages.add(ChatMessage.user(input));

        return new PromptRender(
                mode,
                resolvedMode,
                modelName(ctx),
                nativeTools,
                workspace,
                history.size(),
                toolNames(ctx),
                sectionNames(resolvedMode, workspace, ctx, hasHistory, nativeTools),
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
        return new PromptRender(
                normalizeMode(requestedMode),
                resolvePromptMode(resolvedMode),
                modelName(ctx),
                nativeTools,
                workspace,
                historyMessages,
                toolNames(ctx),
                sectionNames(resolvePromptMode(resolvedMode), workspace, ctx, historyMessages > 0, nativeTools),
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
        sb.append("- Tools exposed: ");
        sb.append(render.tools().isEmpty() ? "(none)" : String.join(", ", render.tools()));
        sb.append('\n');
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

    private static String modelName(Context ctx) {
        if (ctx == null || ctx.llm() == null) return "unknown";
        return ctx.llm().getModel();
    }

    private static List<String> toolNames(Context ctx) {
        if (ctx == null || ctx.toolRegistry() == null) return List.of();
        if (ctx.hasNativeToolSpecOverride()) {
            return NativeToolSpecPolicy.names(ctx.nativeToolSpecs());
        }
        return ctx.toolRegistry().descriptors().stream()
                .map(descriptor -> descriptor.name())
                .sorted()
                .toList();
    }

    private static List<String> sectionNames(
            String resolvedMode,
            Path workspace,
            Context ctx,
            boolean hasHistory,
            boolean nativeTools
    ) {
        List<String> sections = new ArrayList<>();
        sections.add("identity");
        if (workspace != null) sections.add("workspace");
        sections.add("mode:" + resolvePromptMode(resolvedMode));
        if (ctx != null && ctx.toolRegistry() != null && !ctx.toolRegistry().isEmpty()) {
            sections.add(nativeTools ? "tools:native" : "tools:text-fallback");
        }
        if (hasHistory) sections.add("conversation");
        return sections;
    }
}
