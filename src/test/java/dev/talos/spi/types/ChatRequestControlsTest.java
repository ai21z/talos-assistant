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
        assertEquals(SamplingControls.none(), controls.sampling());
        assertEquals(0, controls.maxOutputTokens());
    }

    @Test
    void fiveArgConstructorLeavesSamplingUnset() {
        ChatRequestControls controls = new ChatRequestControls(
                ToolChoiceMode.REQUIRED,
                "",
                ResponseFormatMode.TEXT,
                "",
                List.of("tag"));

        assertEquals(SamplingControls.none(), controls.sampling());
    }

    @Test
    void withSamplingPreservesAllOtherFields() {
        ChatRequestControls base = new ChatRequestControls(
                ToolChoiceMode.NAMED,
                "talos.read_file",
                ResponseFormatMode.TEXT,
                "",
                List.of("repair")).withMaxOutputTokens(384);

        ChatRequestControls sampled = base.withSampling(SamplingControls.NEAR_GREEDY);

        assertEquals(ToolChoiceMode.NAMED, sampled.toolChoice());
        assertEquals("talos.read_file", sampled.namedTool());
        assertEquals(List.of("repair"), sampled.debugTags());
        assertEquals(SamplingControls.NEAR_GREEDY, sampled.sampling());
        assertEquals(384, sampled.maxOutputTokens());
    }

    @Test
    void maxOutputTokensDropsNonPositiveValuesAndIsPreservedBySampling() {
        ChatRequestControls capped = new ChatRequestControls(
                ToolChoiceMode.REQUIRED,
                "",
                ResponseFormatMode.TEXT,
                "",
                List.of("bounded-first-request")).withMaxOutputTokens(384);

        assertEquals(384, capped.maxOutputTokens());
        assertEquals(384, capped.withSampling(SamplingControls.NEAR_GREEDY).maxOutputTokens());
        assertEquals(0, capped.withMaxOutputTokens(0).maxOutputTokens());
        assertEquals(0, capped.withMaxOutputTokens(-1).maxOutputTokens());
    }

    @Test
    void samplingMergeFillsOnlyUnsetFields() {
        SamplingControls turn = new SamplingControls(0.0, null, null, null);
        SamplingControls config = new SamplingControls(0.7, 0.9, 40, 1234L);

        SamplingControls merged = turn.mergedWithFallback(config);

        assertEquals(0.0, merged.temperature());
        assertEquals(0.9, merged.topP());
        assertEquals(40, merged.topK());
        assertEquals(1234L, merged.seed());
        assertEquals(SamplingControls.none(), SamplingControls.none().mergedWithFallback(SamplingControls.none()));
        assertTrue(SamplingControls.NEAR_GREEDY.anySet());
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
