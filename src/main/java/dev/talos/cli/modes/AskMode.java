package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.prompt.PromptInspector;
import dev.talos.core.CfgUtil;
import dev.talos.core.llm.SystemPromptBuilder;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.CapabilityPosturePolicy;
import dev.talos.runtime.policy.PromptWorkspaceContextPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.toolcall.NativeToolSpecPolicy;
import dev.talos.runtime.toolcall.PromptToolDescriptors;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ask mode: plain LLM chat (no RAG context). */
public final class AskMode implements Mode {
    private static final Logger LOG = LoggerFactory.getLogger(AskMode.class);
    static final String READ_ONLY_MUTATION_NUDGE =
            "Ask is read-only; switch to `/mode agent` to make changes.";
    @Override public String name() { return "ask"; }

    @Override public boolean canHandle(String rawLine) {
        return rawLine != null && !rawLine.isBlank();
    }

    // Helpers to catch exact-echo style prompts
    private static final Pattern EXACT_P =
            Pattern.compile("^\\s*Respond\\s+with\\s+exactly:\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern THINK_STRIP_P =
            Pattern.compile("^\\s*Print\\s+this\\s+without\\s+the\\s+think\\s+tags:\\s*<think>(.*?)</think>\\s*(.*)$",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    @SuppressWarnings("resource") // ctx.llm() is a borrowed REPL-scoped client, not owned by this mode.
    public Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception {
        if (rawLine == null || rawLine.isBlank() || ctx == null || ctx.llm() == null) return Optional.empty();

        // Fast-path: exact echo
        Matcher m1 = EXACT_P.matcher(rawLine);
        if (m1.find()) {
            String out = m1.group(1);
            return Optional.of(new Result.Ok(out));
        }
        // Fast-path: <think>…</think> stripping + trailing text preserve
        Matcher m2 = THINK_STRIP_P.matcher(rawLine);
        if (m2.find()) {
            String inner = m2.group(1);
            String tail  = m2.group(2) == null ? "" : m2.group(2);
            String out = (inner + (tail.isBlank() ? "" : " " + tail)).trim();
            return Optional.of(new Result.Ok(out));
        }

        TaskContract askContract = WorkspaceTargetReconciler.reconcile(
                TaskContractResolver.fromUserRequest(rawLine),
                workspace);
        if (ReadOnlyCommandRefusal.matches(askContract)) {
            return Optional.of(ReadOnlyCommandRefusal.resultFor(name()));
        }
        if (askContract.mutationRequested()) {
            return Optional.of(new Result.Ok("\n" + READ_ONLY_MUTATION_NUDGE + "\n\n"));
        }
        CapabilityPosturePolicy.EffectiveTurn effectiveTurn =
                CapabilityPosturePolicy.apply(CapabilityPosture.ASK_READ_ONLY, askContract);
        TaskContract effectiveContract = WorkspaceTargetReconciler.reconcile(
                effectiveTurn.taskContract(),
                workspace);
        ExecutionPhase effectivePhase = effectiveTurn.phase();
        List<String> visibleTools = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(effectiveContract, effectivePhase, ctx.toolRegistry()));

        // Limits
        var lim = CfgUtil.map(ctx.cfg().data.get("limits"));
        long responseMaxChars = CfgUtil.longAt(lim, "response_max_chars", 10 * 1024 * 1024L);
        long llmTimeoutMs     = CfgUtil.longAt(lim, "llm_timeout_ms", 300_000L);

        // System prompt - composed from sections, tool-aware, history-aware
        boolean hasHistory = (ctx.conversationManager() != null && ctx.conversationManager().hasHistory())
                || (ctx.memory() != null && ctx.memory().hasContent());
        boolean nativeTools = CfgUtil.boolAt(CfgUtil.map(ctx.cfg().data.get("tools")), "native_calling", true);
        SystemPromptBuilder promptBuilder = SystemPromptBuilder.forAsk()
                .withPromptTools(PromptToolDescriptors.fromRegistry(ctx.toolRegistry()))
                .withVisibleToolNames(visibleTools)
                .withReadOnlyToolMode(true)
                .withNativeTools(nativeTools)
                .withHistory(hasHistory);
        if (PromptWorkspaceContextPolicy.includeWorkspaceManifest(visibleTools)) {
            promptBuilder.withWorkspace(workspace);
        }
        String system = promptBuilder.build();

        // Build conversation history - AskMode uses a larger budget (55% vs 25%)
        // because there are no RAG snippets competing for context space.
        // This is critical for multi-turn creative tasks.
        List<ChatMessage> history = List.of();
        if (ctx.conversationManager() != null) {
            history = ctx.conversationManager().buildHistoryForAssist();
        } else if (ctx.memory() != null) {
            history = ctx.memory().getTurns();
        }

        // Build structured conversation messages for /api/chat
        List<ChatMessage> messages = buildMessages(system, rawLine, history);
        LastPromptCapture.record(PromptInspector.fromMessages(
                "ask",
                "ask",
                workspace,
                ctx,
                nativeTools,
                history.size(),
                messages));

        // Execute LLM turn via shared executor
        var opts = new AssistantTurnExecutor.Options()
                .llmTimeoutMs(llmTimeoutMs)
                .responseMaxChars(responseMaxChars)
                .capabilityPosture(CapabilityPosture.ASK_READ_ONLY);

        AssistantTurnExecutor.TurnOutput turnOut =
                AssistantTurnExecutor.execute(messages, workspace, ctx, opts);

        String body = "\n" + turnOut.text() + "\n\n";

        if (turnOut.streamed()) {
            return Optional.of(new Result.Streamed(body, ""));
        }
        return Optional.of(new Result.Ok(body));
    }

    /**
     * Builds a structured list of ChatMessages for the /api/chat endpoint.
     *
     * <p>Includes: system prompt → pre-built conversation history → current user message.
     * The caller is responsible for building history (and measuring its token cost)
     * before invoking this method.
     *
     * @param system   the system prompt text
     * @param rawLine  the current user message
     * @param history  pre-built conversation history messages (may be empty)
     * @return mutable list of ChatMessages ready for the LLM
     */
    static List<ChatMessage> buildMessages(String system, String rawLine, List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(system));

        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
            LOG.debug("buildMessages: including {} history turns ({} exchanges)",
                    history.size(), history.size() / 2);
        } else {
            LOG.debug("buildMessages: no history turns (first message in session)");
        }

        // Add current user message
        messages.add(ChatMessage.user(rawLine));
        LOG.debug("buildMessages: total {} messages (1 system + {} history + 1 current)",
                messages.size(), (history != null ? history.size() : 0));
        return messages;
    }


}
