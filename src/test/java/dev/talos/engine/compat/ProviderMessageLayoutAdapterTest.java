package dev.talos.engine.compat;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.RequestMessageLayout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderMessageLayoutAdapterTest {

    @Test
    void preserveAsWrittenKeepsEveryMessageAndRoleInPlace() {
        List<ChatMessage> source = messages();

        List<ChatMessage> adapted = ProviderMessageLayoutAdapter.apply(
                source, RequestMessageLayout.PRESERVE_AS_WRITTEN);

        assertEquals(source, adapted);
        for (int i = 0; i < source.size(); i++) {
            assertSame(source.get(i), adapted.get(i));
        }
    }

    @Test
    void singleLeadingSystemMergesOnlySystemFramesAndPreservesNonSystemMessages() {
        List<ChatMessage> source = messages();

        List<ChatMessage> adapted = ProviderMessageLayoutAdapter.apply(
                source, RequestMessageLayout.SINGLE_LEADING_SYSTEM);

        assertEquals(6, adapted.size());
        assertEquals("system", adapted.getFirst().role());
        assertEquals("Talos policy.\n\n[CurrentTurnCapability]\nRead README.md only.",
                adapted.getFirst().content());
        assertEquals(1, adapted.stream().filter(message -> "system".equals(message.role())).count());
        assertSame(source.get(1), adapted.get(1));
        assertSame(source.get(3), adapted.get(2));
        assertSame(source.get(4), adapted.get(3));
        assertSame(source.get(5), adapted.get(4));
        assertSame(source.get(6), adapted.get(5));
        assertFalse(adapted.getFirst().content().contains("PROJECT-MEMORY-BYTES"));
        assertFalse(adapted.getFirst().content().contains("TOOL-RESULT-BYTES"));
        assertFalse(adapted.getFirst().content().contains("PINNED-FILE-BYTES"));
        assertFalse(adapted.getFirst().content().contains("STATIC-REPAIR-BYTES"));
    }

    @Test
    void singleLeadingSystemRefusesARequestWithoutSystemAuthority() {
        assertThrows(IllegalArgumentException.class, () -> ProviderMessageLayoutAdapter.apply(
                List.of(ChatMessage.user("hello")),
                RequestMessageLayout.SINGLE_LEADING_SYSTEM));
    }

    @Test
    void singleLeadingSystemRefusesSystemMessagesWithToolMetadata() {
        ChatMessage malformed = new ChatMessage(
                "system",
                "policy",
                List.of(new ChatMessage.NativeToolCall("call-1", "talos.read_file", Map.of())),
                null);

        assertThrows(IllegalArgumentException.class, () -> ProviderMessageLayoutAdapter.apply(
                List.of(malformed, ChatMessage.user("hello")),
                RequestMessageLayout.SINGLE_LEADING_SYSTEM));
    }

    private static List<ChatMessage> messages() {
        ChatMessage projectMemory = ChatMessage.user(
                "[ProjectMemory]\nPROJECT-MEMORY-BYTES");
        ChatMessage assistant = ChatMessage.assistantWithToolCalls(
                "",
                List.of(new ChatMessage.NativeToolCall(
                        "call-7",
                        "talos.read_file",
                        Map.of("path", "README.md"))));
        ChatMessage toolResult = ChatMessage.toolResult("call-7", "TOOL-RESULT-BYTES");
        ChatMessage pinnedFile = ChatMessage.user("[PinnedFiles]\nPINNED-FILE-BYTES");
        ChatMessage staticRepair = ChatMessage.user(
                "[StaticRepairReadbacks]\nSTATIC-REPAIR-BYTES");
        return List.of(
                ChatMessage.system("Talos policy."),
                projectMemory,
                ChatMessage.system("[CurrentTurnCapability]\nRead README.md only."),
                assistant,
                toolResult,
                pinnedFile,
                staticRepair);
    }
}
