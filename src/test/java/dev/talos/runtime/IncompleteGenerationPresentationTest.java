package dev.talos.runtime;

import dev.talos.core.llm.LlmClient;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C7: the notice names what was dropped. A generation that held no text dropped no provider prose. */
class IncompleteGenerationPresentationTest {

    @Test
    void aGenerationWithoutTextIsNotDescribedAsWithheldProviderText() {
        var toolCallOnly = new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                "call-1", "talos.read_file", Map.of("path", "README.md"))));
        String notice = IncompleteGenerationPresentation.notice(toolCallOnly);
        assertEquals(IncompleteGenerationPresentation.NOTICE_WITHOUT_TEXT, notice);
        assertTrue(notice.contains("ended without text"), notice);
        assertTrue(notice.contains("nothing was withheld"), notice);
    }

    @Test
    void aGenerationWithTextKeepsTheWithheldNotice() {
        var partial = new LlmClient.StreamResult("Partial provider prose", List.of());
        assertEquals(IncompleteGenerationPresentation.NOTICE, IncompleteGenerationPresentation.notice(partial));
        assertEquals(IncompleteGenerationPresentation.NOTICE, IncompleteGenerationPresentation.notice(null));
    }
}
