package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRepromptOverlayContinuationTest {

    @Test
    void overlayContinuationOwnsOverlayExecutionAndRetryMechanics() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptOverlayContinuation.java"));

        assertTrue(source.contains("ToolRepromptMessageOverlay.apply("), source);
        assertTrue(source.contains("ToolRepromptChatExecutor.executeResult("), source);
        assertTrue(source.contains("ToolRepromptChatExecutor.executeRetryResult("), source);
        assertTrue(source.contains("\"tool-call loop continuation\""), source);
        assertTrue(source.contains("\"transient retry continuation\""), source);
        assertTrue(source.contains("Thread.sleep(400)"), source);
    }

    @Test
    void successfulOverlayRequestSnapshotsTemporaryMessagesAndCleansDurableHistory() {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Reprompt answer.", List.of())),
                16_384);
        LoopState state = state(recorded.client());

        boolean continueLoop = ToolRepromptOverlayContinuation.execute(
                state,
                List.of(),
                List.of("scripts.js"),
                "Create index.html, styles.css, and scripts.js.",
                false,
                tools());

        assertTrue(continueLoop);
        assertEquals("Reprompt answer.", state.currentText);
        assertEquals(1, recorded.requests().size());
        String payload = messageContents(recorded.requests().getFirst());
        assertTrue(payload.contains("[Expected target progress]"), payload);
        assertTrue(payload.contains("[Current task - stay focused on this]"), payload);
        assertFalse(state.messages.stream()
                        .map(ChatMessage::content)
                        .filter(content -> content != null)
                        .anyMatch(content -> content.startsWith("[Expected target progress]")
                                || content.startsWith("[Current task")),
                "temporary overlay messages must be removed from durable loop history");
    }

    private static LoopState state(LlmClient llm) {
        List<ToolSpec> tools = tools();
        llm.setToolSpecs(tools);
        Context ctx = Context.builder(new Config())
                .llm(llm)
                .nativeToolSpecs(tools)
                .build();
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.user("Create index.html, styles.css, and scripts.js."))),
                Path.of("."),
                ctx,
                null,
                10,
                0);
    }

    private static List<ToolSpec> tools() {
        return List.of(
                tool("talos.read_file"),
                tool("talos.write_file"),
                tool("talos.edit_file"));
    }

    private static ToolSpec tool(String name) {
        return new ToolSpec(name, name, "{}");
    }

    private static String messageContents(ChatRequest request) {
        if (request == null || request.messages == null) return "";
        return request.messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
