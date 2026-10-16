package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedundantReadSuppressionGuardTest {
    @TempDir
    Path workspace;

    @AfterEach
    void clearCapture() { TurnTaskContractCapture.clear(); }

    @Test
    void duplicateReadDuringCapturedMutationDoesNotRequestAnImmediateAnswer() {
        LoopState state = loopState();
        var messages = List.of(ChatMessage.user("Change README.md from old title to new title."));
        TurnTaskContractCapture.set(TaskContractResolver.resolveFromMessages(messages), messages);
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "README.md"));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state, read, "README.md", ToolResult.ok("1 | old title"));

        var decision = RedundantReadSuppressionGuard.decision(read, state, false);

        assertNotNull(decision);
        assertTrue(decision.diagnostic().contains("requested mutation remains outstanding"));
        assertTrue(decision.diagnostic().contains("Approval and verification remain controlled by the runtime"));
        assertFalse(decision.diagnostic().contains("Answer the user's question now"));
        assertFalse(decision.diagnostic().contains("workspace has not changed"));
    }

    @Test
    void verificationPhaseDoesNotAdvertiseAnOutstandingMutation() {
        LoopState state = loopState();
        var messages = List.of(ChatMessage.user("Change README.md from old title to new title."));
        TurnTaskContractCapture.set(TaskContractResolver.resolveFromMessages(messages), messages);
        state.ctx.executionPhaseState().moveTo(dev.talos.runtime.phase.ExecutionPhase.VERIFY);
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "README.md"));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state, read, "README.md", ToolResult.ok("1 | new title"));

        var decision = RedundantReadSuppressionGuard.decision(read, state, false);

        assertNotNull(decision);
        assertFalse(decision.diagnostic().contains("requested mutation remains outstanding"));
        assertTrue(decision.diagnostic().contains("Answer the user's question now"));
    }

    @Test
    void duplicateReadOnlyCallReturnsExactNudgeAndSignature() {
        LoopState state = loopState();
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "README.md"));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state, read, "README.md", ToolResult.ok("1 | # Demo"));

        RedundantReadSuppressionGuard.Decision decision =
                RedundantReadSuppressionGuard.decision(read, state, false);

        assertNotNull(decision);
        assertNotNull(decision.readSignature());
        assertTrue(decision.readSignature().contains("talos.read_file"), decision.readSignature());
        assertEquals(
                "You already gathered this information and the workspace has not changed since then. "
                        + "Answer the user's question now using the evidence you already have.",
                decision.diagnostic());
    }

    @Test
    void strictModeAndMutationSinceStartReturnNoDecision() {
        LoopState state = loopState();
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "README.md"));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state, read, "README.md", ToolResult.ok("1 | # Demo"));

        assertNull(RedundantReadSuppressionGuard.decision(read, state, true));

        state.mutationSinceStart = true;
        assertNull(RedundantReadSuppressionGuard.decision(read, state, false));
    }

    @Test
    void firstReadAndMutatingCallsReturnNoDecision() {
        LoopState state = loopState();
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "README.md"));
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "README.md", "content", "# Demo\n"));

        assertNull(RedundantReadSuppressionGuard.decision(read, state, false));
        assertNull(RedundantReadSuppressionGuard.decision(write, state, false));
    }

    @Test
    void executionStageDelegatesRedundantReadSuppressionToGuard() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallPreExecutionGuardChain.java"));

        assertTrue(source.contains("RedundantReadSuppressionGuard.decision"), source);
        assertFalse(source.contains("You already gathered this information and the workspace has not changed since then"),
                source);
    }

    private LoopState loopState() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Read the file.")));
        Context ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(LlmClient.scripted(List.of()))
                .build();
        return new LoopState("", List.of(), messages, workspace, ctx, null, 5, 0);
    }
}
