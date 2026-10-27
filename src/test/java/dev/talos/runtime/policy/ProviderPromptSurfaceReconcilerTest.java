package dev.talos.runtime.policy;

import dev.talos.core.llm.PromptToolDescriptor;
import dev.talos.core.llm.SystemPromptBuilder;
import dev.talos.core.llm.TextToolCallInstructions;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProviderPromptSurfaceReconcilerTest {

    @Test
    void narrowedActualPromptUsesSharedTextContractOnceButNotForNativeOrNoTools() {
        String schema = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";
        for (String mode : List.of("text", "native", "none")) {
            boolean nativeTools = mode.equals("native");
            List<ToolSpec> selected = mode.equals("none") ? List.of()
                    : List.of(new ToolSpec("talos.read_file", "Read", schema));
            List<String> names = selected.stream().map(ToolSpec::name).toList();
            CurrentTurnPlan plan = CurrentTurnPlan.create(
                    TaskContractResolver.fromUserRequest("Read README.md. Do not change files."),
                    ExecutionPhase.INSPECT, nativeTools ? names : List.of(), names, List.of());
            String initial = SystemPromptBuilder.forAsk().withNativeTools(nativeTools).withPromptTools(List.of(
                    new PromptToolDescriptor("talos.read_file", "Read", schema, false),
                    new PromptToolDescriptor("talos.write_file", "Write", schema, true))).build();
            List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system(initial),
                    ChatMessage.user("Read README.md. Do not change files.")));

            ProviderPromptSurfaceReconciler.reconcile(messages, plan, selected);

            String prompt = messages.get(0).content();
            assertTrue(prompt.contains("runtime-bounded continuation"));
            assertFalse(prompt.contains("talos.write_file"));
            if (mode.equals("text")) {
                assertEquals(1, prompt.split("Textual tool-call format:", -1).length - 1);
                assertTrue(prompt.contains(TextToolCallInstructions.formatContract()));
                assertTrue(prompt.contains("```json\n{\"name\":\"tool_name\",\"parameters\":{\"key\":\"value\"}}\n```"));
            } else {
                assertFalse(prompt.contains("Textual tool-call format:"));
                assertFalse(prompt.contains("```json"));
            }
            if (selected.isEmpty()) {
                assertTrue(prompt.contains("No tools are available"));
                assertFalse(prompt.contains("Parameters:"));
            } else {
                assertTrue(prompt.contains("- **talos.read_file**\n  Parameters: `" + schema + "`"));
            }
            ChatMessage once = messages.get(0);
            ProviderPromptSurfaceReconciler.reconcile(messages, plan, selected);
            assertSame(once, messages.get(0), "An unchanged surface must not duplicate format guidance");
        }
    }

    @Test
    void canonicalLineEndingsMatchTheExistingProviderTransport() {
        ChatMessage.NativeToolCall call = new ChatMessage.NativeToolCall(
                "call-1",
                "talos.read_file",
                Map.of("path", "notes.txt"));
        ChatMessage lf = new ChatMessage(
                "assistant",
                "a\nb",
                List.of(call),
                "result-1");
        ChatMessage crlf = new ChatMessage(
                "assistant",
                "a\r\nb",
                List.of(call),
                "result-1");
        ChatMessage loneCr = new ChatMessage(
                "assistant",
                "a\rb",
                List.of(call),
                "result-1");
        ChatMessage controls = new ChatMessage(
                "assistant",
                "a\u0000\u001b[31mb",
                List.of(call),
                "result-1");
        ChatMessage nullContent = new ChatMessage(
                "assistant",
                null,
                List.of(call),
                "result-1");

        List<ChatMessage> normalized =
                ProviderPromptSurfaceReconciler.canonicalizeNormalizedLf(
                        List.of(lf, crlf, loneCr, controls, nullContent));

        assertSame(lf, normalized.get(0));
        assertEquals("a\nb", normalized.get(1).content());
        assertEquals("ab", normalized.get(2).content());
        assertNotEquals("a\nb", normalized.get(2).content());
        assertEquals("ab", normalized.get(3).content());
        assertEquals("", normalized.get(4).content());
        for (ChatMessage message : normalized) {
            assertEquals("assistant", message.role());
            assertEquals(List.of(call), message.toolCalls());
            assertEquals("result-1", message.toolCallId());
        }
    }
}
