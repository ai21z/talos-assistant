package dev.talos.spi.types;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.NONE,
                controls.toolAuthorityMode());
        assertFalse(controls.singleAttempt());
    }

    @Test
    void legacyEightArgumentConstructorKeepsRetryPolicyUnchanged() {
        ChatRequestControls controls = new ChatRequestControls(
                ToolChoiceMode.NONE, "", ResponseFormatMode.TEXT, "", List.of("legacy"),
                SamplingControls.NEAR_GREEDY, 128,
                ProviderResponseCapture.ToolAuthorityMode.NONE);

        assertFalse(controls.singleAttempt());
    }

    @Test
    void singleAttemptIsOptInAndPreservedByEveryControlCopy() {
        ChatRequestControls base = new ChatRequestControls(
                ToolChoiceMode.NAMED, "talos.read_file", ResponseFormatMode.JSON_OBJECT,
                "", List.of("bounded"), SamplingControls.NEAR_GREEDY, 128,
                ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE);
        ChatRequestControls single = base.withSingleAttempt(true);

        assertTrue(single.singleAttempt());
        assertEquals(base, single.withSingleAttempt(false));
        assertTrue(single.withSampling(SamplingControls.none()).singleAttempt());
        assertTrue(single.withMaxOutputTokens(64).singleAttempt());
        assertTrue(single.withToolAuthorityMode(
                ProviderResponseCapture.ToolAuthorityMode.NONE).singleAttempt());
        assertEquals(base.toolChoice(), single.toolChoice());
        assertEquals(base.namedTool(), single.namedTool());
        assertEquals(base.responseFormat(), single.responseFormat());
        assertEquals(base.jsonSchema(), single.jsonSchema());
        assertEquals(base.debugTags(), single.debugTags());
        assertEquals(base.sampling(), single.sampling());
        assertEquals(base.maxOutputTokens(), single.maxOutputTokens());
        assertEquals(base.toolAuthorityMode(), single.toolAuthorityMode());
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
    void authorityModeIsExplicitAndPreservedByControlCopies() {
        ChatRequestControls textAuthority = ChatRequestControls.defaults()
                .withToolAuthorityMode(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON);

        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON,
                textAuthority.toolAuthorityMode());
        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON,
                textAuthority.withSampling(SamplingControls.NEAR_GREEDY)
                        .toolAuthorityMode());
        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON,
                textAuthority.withMaxOutputTokens(256).toolAuthorityMode());
        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.NONE,
                textAuthority.withToolAuthorityMode(null).toolAuthorityMode());
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
                "llama_cpp",
                "qwen2.5-coder:14b",
                "sys",
                "usr",
                List.of(),
                null);

        assertEquals(ChatRequestControls.defaults(), request.controls);
    }
}
