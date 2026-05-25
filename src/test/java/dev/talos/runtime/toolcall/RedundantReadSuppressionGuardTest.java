package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
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

    @Test
    void duplicateReadOnlyCallReturnsExactNudgeAndSignature() {
        LoopState state = loopState();
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "README.md"));
        String signature = ToolCallSupport.buildReadCallSignature(read);
        state.successfulReadCalls.put(signature, "1 | # Demo");

        RedundantReadSuppressionGuard.Decision decision =
                RedundantReadSuppressionGuard.decision(read, state, false);

        assertNotNull(decision);
        assertEquals(signature, decision.readSignature());
        assertEquals(
                "You already gathered this information and the workspace has not changed since then. "
                        + "Answer the user's question now using the evidence you already have.",
                decision.diagnostic());
    }

    @Test
    void strictModeAndMutationSinceStartReturnNoDecision() {
        LoopState state = loopState();
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "README.md"));
        state.successfulReadCalls.put(ToolCallSupport.buildReadCallSignature(read), "1 | # Demo");

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
                "src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java"));

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
