package dev.talos.runtime.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ContentDraftProposalTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void generationSchemaKeepsClosedFieldsWithoutExpandingTheRuntimeTextBound() throws Exception {
        var schema = JSON.readTree(ContentDraftProposal.SCHEMA);
        assertEquals("object", schema.path("type").textValue());
        assertFalse(schema.path("additionalProperties").booleanValue());
        assertEquals(JSON.readTree("[\"kind\",\"text\"]"), schema.path("required"));
        assertEquals(2, schema.path("properties").size());
        assertEquals(JSON.readTree("{\"type\":\"string\",\"enum\":[\"draft\",\"question\"]}"),
                schema.at("/properties/kind"));
        assertEquals(JSON.readTree("{\"type\":\"string\",\"minLength\":1}"), schema.at("/properties/text"));
        assertFalse(schema.at("/properties/text").has("maxLength"),
                "Long grammar repetition limits are rejected by the pinned backend, runtime bounds remain enforced");
    }

    @Test
    void exactTextIncludingMarkdownWhitespaceAndSupplementaryCharactersIsPreserved() throws Exception {
        String text = "# Lina\n\n```text\nseven minutes slow\n```\n\tThe clock struck. \uD83D\uDD70\uFE0F\n";
        var proposal = ContentDraftProposal.decode(json("draft", text)).orElseThrow();
        assertEquals("draft", proposal.kind());
        assertEquals(text, proposal.text());
        assertEquals("Which character should be used?", ContentDraftProposal.decode(
                json("question", "Which character should be used?")).orElseThrow().text());
    }

    @ParameterizedTest
    @MethodSource("invalidEnvelopes")
    void onlyOneStrictObjectWithTheTwoDeclaredTextFieldsIsAccepted(String raw) {
        assertTrue(ContentDraftProposal.decode(raw).isEmpty(), () -> "Accepted invalid envelope: " + raw);
    }

    static Stream<String> invalidEnvelopes() {
        return Stream.of("", "null", "[]", "true", "{}", "{\"kind\":\"draft\"}",
                "{\"text\":\"story\"}", "{\"kind\":null,\"text\":\"story\"}",
                "{\"kind\":\"draft\",\"text\":12}", "{\"kind\":\"other\",\"text\":\"story\"}",
                "{\"kind\":\"DRAFT\",\"text\":\"story\"}",
                "{\"kind\":\"draft\",\"text\":\"story\",\"path\":\"story.txt\"}",
                "{\"kind\":\"draft\",\"text\":\"first\",\"text\":\"second\"}",
                "{\"kind\":\"draft\",\"kind\":\"question\",\"text\":\"story\"}",
                "{\"kind\":\"draft\",\"text\":\"story\"} {}",
                "{\"kind\":\"draft\",\"text\":\"story\"} trailing",
                "```json\n{\"kind\":\"draft\",\"text\":\"story\"}\n```",
                "{\"kind\":\"draft\",\"text\":\"unfinished");
    }

    @Test
    void nullAndOversizedRawResponsesAreRejected() {
        assertTrue(ContentDraftProposal.decode(null).isEmpty());
        assertTrue(ContentDraftProposal.decode(" ".repeat(65_537)).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidTexts")
    void blankControlsFormatsAndUnpairedSurrogatesCannotBecomeDrafts(String text) throws Exception {
        assertFalse(ContentDraftProposal.validText(text));
        assertTrue(ContentDraftProposal.decode(json("draft", text)).isEmpty());
        assertTrue(ContentDraftProposal.decode(json("question", text)).isEmpty());
    }

    static Stream<String> invalidTexts() {
        return Stream.of("", " \n\t", "a\rb", "a\u0000b", "a\u0007b", "a\u001bb", "a\u007fb",
                "a\u200bb", "a\u202eb", "a\u2066b", "a\uFEFFb", "a\uD800", "a\uDC00",
                "\uD800a", "\uD800\uD800", "x".repeat(8193), "\u4E2D".repeat(5462));
    }

    @Test
    void characterAndUtf8BoundsAreBothEnforcedAtTheirExactLimits() throws Exception {
        String characters = "x".repeat(8192);
        String bytes = "\u00E9".repeat(8192);
        assertTrue(ContentDraftProposal.decode(json("draft", characters)).isPresent());
        assertTrue(ContentDraftProposal.decode(json("draft", bytes)).isPresent());
        assertEquals(ContentDraftProposal.MAX_BYTES, bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(ContentDraftProposal.decode(json("draft", "\u00E9".repeat(8191) + "\u4E2D")).isEmpty());
    }

    private static String json(String kind, String text) throws Exception {
        return JSON.writeValueAsString(Map.of("kind", kind, "text", text));
    }
}
