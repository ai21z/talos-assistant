package dev.talos.runtime.policy;

import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ProviderPromptSurfaceReconcilerTest {

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
