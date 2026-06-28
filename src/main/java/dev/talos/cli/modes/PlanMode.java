package dev.talos.cli.modes;

import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.prompt.PromptInspector;
import dev.talos.cli.repl.Context;
import dev.talos.core.CfgUtil;
import dev.talos.core.llm.SystemPromptBuilder;
import dev.talos.runtime.Result;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.CapabilityPosturePolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.toolcall.NativeToolSpecPolicy;
import dev.talos.runtime.toolcall.PromptToolDescriptors;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Plan mode: read-only assistant planning without applying changes. */
public final class PlanMode implements Mode {
    private static final Logger LOG = LoggerFactory.getLogger(PlanMode.class);

    @Override public String name() { return "plan"; }

    @Override public boolean canHandle(String rawLine) {
        return rawLine != null && !rawLine.isBlank();
    }

    @Override
    @SuppressWarnings("resource") // ctx.llm() is a borrowed REPL-scoped client, not owned by this mode.
    public Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception {
        if (rawLine == null || rawLine.isBlank() || ctx == null || ctx.llm() == null) {
            return Optional.empty();
        }

        var lim = CfgUtil.map(ctx.cfg().data.get("limits"));
        long responseMaxChars = CfgUtil.longAt(lim, "response_max_chars", 10 * 1024 * 1024L);
        long llmTimeoutMs = CfgUtil.longAt(lim, "llm_timeout_ms", 300_000L);

        List<ChatMessage> history = List.of();
        if (ctx.conversationManager() != null) {
            history = ctx.conversationManager().buildHistoryForAssist();
        } else if (ctx.memory() != null) {
            history = ctx.memory().getTurns();
        }
        if (history == null) {
            history = List.of();
        }

        List<ChatMessage> contractMessages = new ArrayList<>();
        if (!history.isEmpty()) {
            contractMessages.addAll(history);
        }
        contractMessages.add(ChatMessage.user(rawLine));

        TaskContract rawContract = WorkspaceTargetReconciler.reconcile(
                TaskContractResolver.fromMessages(contractMessages),
                workspace);
        CapabilityPosturePolicy.EffectiveTurn effectiveTurn =
                CapabilityPosturePolicy.apply(CapabilityPosture.PLAN_READ_ONLY, rawContract);
        TaskContract effectiveContract = WorkspaceTargetReconciler.reconcile(
                effectiveTurn.taskContract(),
                workspace);
        ExecutionPhase effectivePhase = effectiveTurn.phase();
        List<ToolSpec> plannedNativeToolSpecs =
                NativeToolSpecPolicy.select(effectiveContract, effectivePhase, ctx.toolRegistry());
        List<String> plannedNativeToolNames = NativeToolSpecPolicy.names(plannedNativeToolSpecs);

        boolean nativeTools = CfgUtil.boolAt(CfgUtil.map(ctx.cfg().data.get("tools")), "native_calling", true);
        String system = SystemPromptBuilder.forPlan()
                .withPromptTools(PromptToolDescriptors.fromRegistry(ctx.toolRegistry()))
                .withVisibleToolNames(plannedNativeToolNames)
                .withReadOnlyToolMode(true)
                .withWorkspace(workspace)
                .withNativeTools(nativeTools)
                .withHistory(!history.isEmpty())
                .build();

        List<ChatMessage> messages = buildMessages(system, rawLine, history);
        Context turnCtx = ctx.withNativeToolSpecs(plannedNativeToolSpecs);
        LastPromptCapture.record(PromptInspector.fromMessages(
                "plan",
                "plan",
                workspace,
                turnCtx,
                nativeTools,
                history.size(),
                messages));

        var opts = new AssistantTurnExecutor.Options()
                .llmTimeoutMs(llmTimeoutMs)
                .responseMaxChars(responseMaxChars)
                .capabilityPosture(CapabilityPosture.PLAN_READ_ONLY);

        AssistantTurnExecutor.TurnOutput turnOut =
                AssistantTurnExecutor.execute(messages, workspace, turnCtx, opts);

        String body = "\n" + turnOut.text() + "\n\n";

        if (turnOut.streamed()) {
            return Optional.of(new Result.Streamed(body, ""));
        }
        return Optional.of(new Result.Ok(body));
    }

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

        messages.add(ChatMessage.user(rawLine));
        LOG.debug("buildMessages: total {} messages (1 system + {} history + 1 current)",
                messages.size(), (history != null ? history.size() : 0));
        return messages;
    }
}
