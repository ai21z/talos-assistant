package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.phase.ExecutionPhaseState;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantTurnExecutorPhasePolicyTest {

    @TempDir
    Path workspace;

    @Test
    void explicitMutationTurnStartsInApplyAndMovesToVerifyAfterSuccessfulMutation() {
        var approvals = new AtomicInteger();
        var executions = new AtomicInteger();
        var registry = registryWithWriteTool(executions);
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        var loop = new ToolCallLoop(processor, 3);
        var phaseState = new ExecutionPhaseState(ExecutionPhase.INSPECT);
        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(LlmClient.scripted(List.of(
                        "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"index.html\",\"content\":\"ok\"}}",
                        "Done.")))
                .toolCallLoop(loop)
                .executionPhaseState(phaseState)
                .build();
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Please update index.html."));

        AssistantTurnExecutor.execute(messages, workspace, ctx, new AssistantTurnExecutor.Options());

        assertEquals(1, approvals.get(), "explicit mutation should enter APPLY and reach approval");
        assertEquals(1, executions.get(), "approved APPLY mutation should execute");
        assertEquals(ExecutionPhase.VERIFY, phaseState.phase(),
                "successful mutation should move the turn state toward VERIFY");
    }

    private static ToolRegistry registryWithWriteTool(AtomicInteger executions) {
        var registry = new ToolRegistry();
        registry.register(new WriteTool(executions));
        return registry;
    }

    private record WriteTool(AtomicInteger executions) implements TalosTool {
        @Override public String name() { return "talos.write_file"; }
        @Override public String description() { return "Write file test"; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor(name(), description(), null, ToolRiskLevel.WRITE);
        }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
            executions.incrementAndGet();
            return ToolResult.ok("updated");
        }
    }
}
