package dev.talos.spi.types;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestControlsTest {

    @Test
    void defaultsAreAutoTextWithNoSchemaOrTags() {
        ChatRequestControls controls = ChatRequestControls.defaults();

        assertEquals(ToolChoiceMode.AUTO, controls.toolChoice());
        assertEquals("", controls.namedTool());
        assertEquals(ResponseFormatMode.TEXT, controls.responseFormat());
        assertEquals("", controls.jsonSchema());
        assertTrue(controls.debugTags().isEmpty());
    }

    @Test
    void namedToolChoiceRequiresToolName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ChatRequestControls(
                        ToolChoiceMode.NAMED,
                        " ",
                        ResponseFormatMode.TEXT,
                        "",
                        List.of()));

        assertTrue(error.getMessage().contains("namedTool"));
    }

    @Test
    void debugTagsAreTrimmedAndBlankTagsAreDropped() {
        ChatRequestControls controls = new ChatRequestControls(
                ToolChoiceMode.REQUIRED,
                "",
                ResponseFormatMode.JSON_SCHEMA,
                "{\"type\":\"object\"}",
                List.of(" obligation ", "", " turn-7 "));

        assertEquals(List.of("obligation", "turn-7"), controls.debugTags());
        assertEquals("{\"type\":\"object\"}", controls.jsonSchema());
    }

    @Test
    void chatRequestCarriesProviderNeutralControls() {
        ChatRequest request = new ChatRequest(
                "llama_cpp",
                "model.gguf",
                "",
                "",
                List.of(),
                null,
                List.of(ChatMessage.user("hi")),
                List.of(),
                new ChatRequestControls(
                        ToolChoiceMode.REQUIRED,
                        "",
                        ResponseFormatMode.JSON_OBJECT,
                        "",
                        List.of("repair")));

        assertEquals(ToolChoiceMode.REQUIRED, request.controls.toolChoice());
        assertEquals(ResponseFormatMode.JSON_OBJECT, request.controls.responseFormat());
        assertEquals(List.of("repair"), request.controls.debugTags());
    }

    @Test
    void chatRequestDefaultsControlsForExistingConstructorShape() {
        ChatRequest request = new ChatRequest(
                "ollama",
                "qwen2.5-coder:14b",
                "sys",
                "usr",
                List.of(),
                null);

        assertEquals(ChatRequestControls.defaults(), request.controls);
    }
}
