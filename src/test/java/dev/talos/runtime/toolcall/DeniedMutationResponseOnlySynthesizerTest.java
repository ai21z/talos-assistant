package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeniedMutationResponseOnlySynthesizerTest {

    @Test
    void missingLlmReturnsDeterministicPolicyStopMessage() {
        LoopState state = new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(ChatMessage.system("sys"))),
                Path.of("."),
                null,
                null,
                5,
                0);

        String answer = DeniedMutationResponseOnlySynthesizer.synthesize(state);

        assertEquals(DeniedMutationResponseOnlySynthesizer.stopMessage(), answer);
    }

    @Test
    void textOnlySynthesisReturnsStrippedAnswerAndRemovesTemporaryPrompt() {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("  I inspected the available evidence only.  ", List.of())),
                16_384);
        LoopState state = state(recorded.client());
        int initialMessages = state.messages.size();

        String answer = DeniedMutationResponseOnlySynthesizer.synthesize(state);

        assertEquals("I inspected the available evidence only.", answer);
        assertEquals(initialMessages, state.messages.size());
        assertFalse(state.messages.stream().anyMatch(DeniedMutationResponseOnlySynthesizerTest::isPolicyStopPrompt));
        assertEquals(1, recorded.requests().size());
        String prompt = recorded.requests().getFirst().messages.stream()
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("[Tool policy stop]"), prompt);
        assertTrue(prompt.contains("Do not call any more tools in this turn."), prompt);
    }

    @Test
    void nativeToolCallsForceDeterministicPolicyStopMessage() {
        var llm = ScriptedNativeLlmClient.of(List.of(new LlmClient.StreamResult(
                "",
                List.of(new ChatMessage.NativeToolCall(
                        "call-write",
                        "talos.write_file",
                        Map.of("path", "README.md", "content", "changed"))))));
        LoopState state = state(llm);

        String answer = DeniedMutationResponseOnlySynthesizer.synthesize(state);

        assertEquals(DeniedMutationResponseOnlySynthesizer.stopMessage(), answer);
        assertFalse(state.messages.stream().anyMatch(DeniedMutationResponseOnlySynthesizerTest::isPolicyStopPrompt));
    }

    @Test
    void textualToolCallDebrisForcesDeterministicPolicyStopMessage() {
        LoopState state = state(LlmClient.scripted("""
                ```json
                {"name":"talos.write_file","arguments":{"path":"README.md","content":"changed"}}
                ```
                """));

        String answer = DeniedMutationResponseOnlySynthesizer.synthesize(state);

        assertEquals(DeniedMutationResponseOnlySynthesizer.stopMessage(), answer);
        assertFalse(state.messages.stream().anyMatch(DeniedMutationResponseOnlySynthesizerTest::isPolicyStopPrompt));
    }

    @Test
    void synthesisFailureFallsBackAndRemovesTemporaryPrompt() {
        LoopState state = state(LlmClient.scriptedFailure(new RuntimeException("backend unavailable")));
        int initialMessages = state.messages.size();

        String answer = DeniedMutationResponseOnlySynthesizer.synthesize(state);

        assertEquals(DeniedMutationResponseOnlySynthesizer.stopMessage(), answer);
        assertEquals(initialMessages, state.messages.size());
        assertFalse(state.messages.stream().anyMatch(DeniedMutationResponseOnlySynthesizerTest::isPolicyStopPrompt));
    }

    @Test
    void repromptStageDelegatesDeniedMutationSynthesisToOwner() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertTrue(source.contains("DeniedMutationResponseOnlySynthesizer.synthesize"), source);
        assertFalse(source.contains("private static String responseOnlyAfterDeniedMutation"), source);
        assertFalse(source.contains("private static String deniedMutationStopMessage"), source);
    }

    private static LoopState state(LlmClient llm) {
        Context.Builder builder = Context.builder(new Config())
                .nativeToolSpecs(List.of(new ToolSpec("talos.write_file", "Write", "{}")));
        if (llm != null) {
            builder.llm(llm);
        }
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.user("Try to write README.md."))),
                Path.of("."),
                builder.build(),
                null,
                5,
                0);
    }

    private static boolean isPolicyStopPrompt(ChatMessage message) {
        return message != null
                && "system".equals(message.role())
                && message.content() != null
                && message.content().startsWith("[Tool policy stop]");
    }
}
