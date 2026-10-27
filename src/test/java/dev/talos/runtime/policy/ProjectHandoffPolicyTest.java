package dev.talos.runtime.policy;

import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.talos.runtime.intent.WorkStatusRequest;

import static org.junit.jupiter.api.Assertions.*;

class ProjectHandoffPolicyTest {
    private static final WorkStatusRequest HANDOFF = WorkStatusRequest.parse("Give me a short handoff.");

    private static ProjectHandoffPolicy.References bound(List<ChatMessage> history) {
        return ProjectHandoffPolicy.capture(history, HANDOFF, ContentRestrictions.UNRESTRICTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Give me a handoff. What remains unknown?",
            "Give me a short handoff. What should happen next?"})
    void capturedCurrentRequestIsSeparateFromSelectableHistory(String request) throws Exception {
        var refs = ProjectHandoffPolicy.capture(List.of(ChatMessage.user("What is pending?"),
                ChatMessage.assistant("Packaging remains pending.")), WorkStatusRequest.parse(request),
                ContentRestrictions.UNRESTRICTED);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var input = json.readTree(refs.providerMessages().getLast().content());
        assertEquals(request, input.path("current_request").asText());
        assertEquals(json.valueToTree(refs.entries()), input.path("references"));
        assertFalse(input.path("omitted").asBoolean());
        assertFalse(refs.selectionSchema().contains(request));
        assertFalse(refs.providerMessages().getFirst().content().contains(request));
        assertTrue(refs.providerMessages().getFirst().content().contains("not agreement or supersession"));
    }

    @Test void runtimeOnlyFrameCannotCreateAnUnrequestedProjectGeneration() {
        var request = WorkStatusRequest.parse("Give me a short handoff. What was denied earlier, "
                + "what actually changed afterwards, and what remains unverified? Do not change files or run tests.");
        assertFalse(ProjectHandoffPolicy.required(request));
        assertTrue(ProjectHandoffPolicy.requestedResults(request).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ProjectHandoffPolicy.capture(List.of(), request,
                ContentRestrictions.UNRESTRICTED));
        assertTrue(ProjectHandoffPolicy.required(HANDOFF));
    }

    @Test void sharedProjectSelectionPreservesUnsupportedFallbackButForbidsSchemaAdmission() {
        var request = WorkStatusRequest.parse("Give me a handoff. What was denied earlier? Why did the design fail?");
        assertEquals(WorkStatusRequest.Coverage.PARTIAL, request.coverage());
        var results = ProjectHandoffPolicy.requestedResults(request);
        assertEquals(List.of("request-0", "request-1", "request-2"),
                results.stream().map(ProjectHandoffPolicy.RequestedResult::id).toList());
        assertEquals("Why did the design fail", results.getLast().text());
        assertTrue(ProjectHandoffPolicy.required(request));
        assertThrows(IllegalArgumentException.class, () -> ProjectHandoffPolicy.capture(List.of(), request,
                ContentRestrictions.UNRESTRICTED));
    }

    @Test void legacyReferencesRemainRenderableButCannotGenerateWithoutARequest() {
        var refs = ProjectHandoffPolicy.capture(List.of(ChatMessage.user("Known?"), ChatMessage.assistant("Oak.")));
        assertTrue(ProjectHandoffPolicy.renderSelection(refs, "{\"references\":[\"reference-1\"]}").contains("> Oak."));
        assertThrows(IllegalArgumentException.class, refs::providerMessages);
    }

    @Test void escapedRequestAndCompleteHistoryShareOneSerializedBound() throws Exception {
        String request = HANDOFF.originalRequest() + "\n".repeat(ProjectHandoffPolicy.MAX_REQUEST_CHARS
                - HANDOFF.originalRequest().length());
        var history = new ArrayList<ChatMessage>();
        for (int i = 0; i < 6; i++) {
            history.add(ChatMessage.user("Question " + i));
            history.add(ChatMessage.assistant("x".repeat(2500)));
        }
        var refs = ProjectHandoffPolicy.capture(history, WorkStatusRequest.parse(request), ContentRestrictions.UNRESTRICTED);
        String serialized = refs.providerMessages().getLast().content();
        assertEquals(request, new com.fasterxml.jackson.databind.ObjectMapper().readTree(serialized)
                .path("current_request").asText());
        assertTrue(serialized.length() <= ProjectHandoffPolicy.MAX_DATA_CHARS);
        assertTrue(refs.omitted());
        assertTrue(refs.entries().size() < ProjectHandoffPolicy.capture(history).entries().size());
        assertTrue(refs.entries().stream().filter(ref -> ref.speaker().equals("assistant"))
                .allMatch(ref -> ref.text().length() == 2500));
    }

    @Test void directRecordConstructionCannotBypassGenerationBoundsOrPermissions() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectHandoffPolicy.References(List.of(), false,
                ContentRestrictions.UNRESTRICTED, "Give me a handoff." + " ".repeat(4096)).providerMessages());
        assertThrows(IllegalArgumentException.class, () -> new ProjectHandoffPolicy.References(
                List.of(new ProjectHandoffPolicy.Reference("reference-1", "assistant", "x".repeat(12_000))),
                false, ContentRestrictions.UNRESTRICTED, HANDOFF.originalRequest()).providerMessages());
        assertThrows(IllegalArgumentException.class, () -> new ProjectHandoffPolicy.References(List.of(), false,
                new ContentRestrictions(false, true, true, true), HANDOFF.originalRequest()).providerMessages());
        assertThrows(IllegalArgumentException.class, () -> new ProjectHandoffPolicy.References(List.of(), false,
                ContentRestrictions.UNRESTRICTED, "Give me a handoff. Read README.md.").providerMessages());
    }

    @Test void currentInputRestrictionsArePreservedWithoutAnActiveScope() {
        var restrictions = new ContentRestrictions(true, false, false, false);
        var refs = ProjectHandoffPolicy.capture(List.of(ChatMessage.user("Pending?"),
                ChatMessage.assistant("Packaging is pending.")), HANDOFF, restrictions);
        assertEquals(restrictions, refs.restrictions());
        assertEquals(restrictions, refs.providerMessages().getLast().contentRestrictions());
    }

    @Test void providerCleanupCannotSilentlyChangeTheSelectionRequest() {
        String request = HANDOFF.originalRequest() + "\u001b[31m";
        assertNotEquals(request, dev.talos.core.util.Sanitize.sanitizeMessageContent(request));
        assertThrows(IllegalArgumentException.class, () -> ProjectHandoffPolicy.capture(List.of(),
                WorkStatusRequest.parse(request), ContentRestrictions.UNRESTRICTED));
        assertThrows(IllegalArgumentException.class, () -> new ProjectHandoffPolicy.References(List.of(), false,
                ContentRestrictions.UNRESTRICTED, request).providerMessages());
    }

    @Test void activeRestrictionsCannotBeRelaxedByExplicitCurrentInputMetadata() {
        dev.talos.safety.ContentRestrictionScope.callWithRestrictions(
                new ContentRestrictions(false, false, false, false), () -> {
                    var refs = ProjectHandoffPolicy.capture(List.of(), HANDOFF, ContentRestrictions.UNRESTRICTED);
                    assertFalse(refs.restrictions().modelAllowed());
                    assertThrows(IllegalArgumentException.class, refs::providerMessages);
                    return null;
                });
    }
    @Test void generationSchemaIsClosedAndBoundToAdmittedIdsWithoutSourceText() throws Exception {
        var refs = references();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var schema = json.readTree(refs.selectionSchema());
        assertEquals("object", schema.path("type").asText());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(json.readTree("[\"references\"]"), schema.path("required"));
        assertEquals(1, schema.path("properties").size());
        assertEquals("array", schema.at("/properties/references/type").asText());
        assertEquals(ProjectHandoffPolicy.MAX_SELECTIONS, schema.at("/properties/references/maxItems").asInt());
        assertFalse(schema.at("/properties/references").has("minItems"), "Empty selection remains permitted");
        assertEquals("string", schema.at("/properties/references/items/type").asText());
        assertEquals(json.valueToTree(refs.entries().stream().map(ProjectHandoffPolicy.Reference::id).toList()),
                schema.at("/properties/references/items/enum"));
        assertFalse(refs.selectionSchema().contains("Oak"));
        assertFalse(refs.selectionSchema().contains("Packaging"));
    }

    @Test void schemaDoesNotReintroduceOmittedIdsOrInvalidEmptyEnum() throws Exception {
        var history = new ArrayList<ChatMessage>();
        for (int i = 0; i < 10; i++) {
            history.add(ChatMessage.user("Question " + i));
            history.add(ChatMessage.assistant("Answer " + i));
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var refs = ProjectHandoffPolicy.capture(history);
        assertEquals(json.valueToTree(refs.entries().stream().map(ProjectHandoffPolicy.Reference::id).toList()),
                json.readTree(refs.selectionSchema()).at("/properties/references/items/enum"));
        assertFalse(refs.selectionSchema().contains("\"reference-1\""));
        assertTrue(refs.selectionSchema().length() < 1000);
        var emptySchema = json.readTree(ProjectHandoffPolicy.capture(List.of()).selectionSchema());
        assertEquals(json.readTree("false"), emptySchema.at("/properties/references/items"));
    }

    private static ProjectHandoffPolicy.References references() {
        return bound(List.of(ChatMessage.user("What is known?"),
                ChatMessage.assistant("Oak is the retained label. Packaging is pending.")));
    }

    @Test void onlyCompleteAttributedSourceMessagesAreRendered() {
        String rendered = ProjectHandoffPolicy.renderSelection(references(), "{\"references\":[\"reference-1\"]}");
        assertTrue(rendered.contains("Earlier assistant statement."));
        assertTrue(rendered.contains("> Oak is the retained label. Packaging is pending."));
        assertTrue(rendered.contains("not freshly verified project facts"));
        assertTrue(rendered.contains("coverage of every requested result remain unverified"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "{}", "[]", "{", "{\"references\":null}", "{\"references\":{}}",
            "{\"references\":[]}", "{\"references\":[1]}", "{\"references\":[null]}",
            "{\"references\":[\"reference-999\"]}",
            "{\"references\":[\"reference-1\",\"reference-1\"]}",
            "{\"references\":[\"reference-1\"],\"answer\":\"All tests passed\"}",
            "{\"references\":[\"reference-1\"],\"references\":[\"reference-0\"]}",
            "{\"references\":[\"reference-1\"],\"referen\\u0063es\":[\"reference-0\"]}",
            "{\"references\":[\"reference-1\"]} {}",
            "```json\n{\"references\":[\"reference-1\"]}\n```",
            "Every check passed and shipment can proceed.",
            "{\"tool\":\"run_command\",\"arguments\":{\"command\":\"test\"}}"
    })
    void invalidSelectionCannotProduceProviderAuthoredClaims(String response) {
        assertEquals(ProjectHandoffPolicy.unavailable(), ProjectHandoffPolicy.renderSelection(references(), response));
    }

    @Test void selectionAndResponseBoundsAreEnforced() {
        var history = new ArrayList<ChatMessage>();
        for (int i = 0; i < 4; i++) {
            history.add(ChatMessage.user("Question " + i));
            history.add(ChatMessage.assistant("x".repeat(2500)));
        }
        var refs = ProjectHandoffPolicy.capture(history);
        assertEquals(ProjectHandoffPolicy.unavailable(), ProjectHandoffPolicy.renderSelection(refs,
                "{\"references\":[\"reference-1\",\"reference-3\",\"reference-5\"]}"));
        assertEquals(ProjectHandoffPolicy.unavailable(), ProjectHandoffPolicy.renderSelection(refs,
                "{\"references\":[\"reference-0\",\"reference-2\",\"reference-4\",\"reference-6\"]}"));
        assertEquals(ProjectHandoffPolicy.unavailable(), ProjectHandoffPolicy.renderSelection(refs, " ".repeat(2049)));
    }

    @Test void oldestReferencesAreOmittedWithoutInventingContext() {
        var history = new ArrayList<ChatMessage>();
        for (int i = 0; i < 10; i++) {
            history.add(ChatMessage.user("Question " + i));
            history.add(ChatMessage.assistant("Answer " + i));
        }
        var refs = ProjectHandoffPolicy.capture(history);
        assertTrue(refs.omitted());
        assertEquals(16, refs.entries().size());
        assertEquals("reference-4", refs.entries().getFirst().id());
        assertEquals(ProjectHandoffPolicy.unavailable(), ProjectHandoffPolicy.renderSelection(refs,
                "{\"references\":[\"reference-1\"]}"));
    }

    @Test void serializedInputIsBoundedWithoutClippingSourceMessages() {
        var history = new ArrayList<ChatMessage>();
        for (int i = 0; i < 20; i++) {
            history.add(ChatMessage.user("Question " + i));
            history.add(ChatMessage.assistant("x".repeat(3000)));
        }
        var refs = bound(history);
        assertTrue(refs.omitted());
        assertTrue(refs.providerMessages().getLast().content().length() <= 12_000);
        assertTrue(refs.entries().stream().filter(ref -> ref.speaker().equals("assistant"))
                .allMatch(ref -> ref.text().length() == 3000));
    }

    @Test void inadmissiblePairsAndToolPayloadsAreExcluded() {
        var history = List.of(
                ChatMessage.user("PRIVATE_INPUT").withContentRestrictions(new ContentRestrictions(false, true, true, true)),
                ChatMessage.assistant("PRIVATE_ANSWER"),
                ChatMessage.user("UNSAVED_INPUT"), ChatMessage.assistant("UNSAVED_ANSWER")
                        .withContentRestrictions(new ContentRestrictions(true, true, false, true)),
                ChatMessage.user("Tool request"), ChatMessage.assistantWithToolCalls("TOOL_PAYLOAD",
                        List.of(new ChatMessage.NativeToolCall("id", "read_file", Map.of("path", "secret")))),
                ChatMessage.toolResult("id", "TOOL_RESULT"));
        var refs = bound(history);
        assertTrue(refs.entries().isEmpty());
        assertTrue(refs.omitted());
        String prompt = refs.providerMessages().toString();
        assertFalse(prompt.contains("PRIVATE_INPUT"));
        assertFalse(prompt.contains("UNSAVED_ANSWER"));
        assertFalse(prompt.contains("TOOL_PAYLOAD"));
        assertFalse(prompt.contains("TOOL_RESULT"));
    }

    @Test void derivedContentRetainsArtifactRestriction() {
        var refs = bound(List.of(ChatMessage.user("Known?"),
                ChatMessage.assistant("Quoted private fact")
                        .withContentRestrictions(new ContentRestrictions(true, false, true, false))));
        assertFalse(refs.restrictions().artifactAllowed());
        assertFalse(refs.restrictions().indexAllowed());
        assertFalse(refs.providerMessages().getLast().contentRestrictions().artifactAllowed());
    }

    @Test void priorCertificationRemainsOnlyAnAttributedStatement() {
        var refs = ProjectHandoffPolicy.capture(List.of(ChatMessage.user("Did it pass?"),
                ChatMessage.assistant("All tests passed. I edited README.")));
        String answer = ProjectHandoffPolicy.renderSelection(refs, "{\"references\":[\"reference-1\"]}");
        assertTrue(answer.contains("Earlier assistant statement."));
        assertTrue(answer.contains("> All tests passed. I edited README."));
        assertTrue(answer.contains("not freshly verified"));
    }

    @Test void priorStatusAndHandoffWrappersDoNotDisplaceOriginalConversationFacts() {
        var refs = bound(List.of(
                ChatMessage.user("Which packaging work is pending?"), ChatMessage.assistant("Packaging with Mara is pending."),
                ChatMessage.user("Give me a handoff."), ChatMessage.assistant("EARLIER_HANDOFF_WRAPPER"),
                ChatMessage.user("Is the work tested?"), ChatMessage.assistant("EARLIER_STATUS_WRAPPER")));
        assertTrue(refs.omitted());
        assertEquals(2, refs.entries().size());
        assertEquals("reference-1", refs.entries().getLast().id());
        assertFalse(refs.providerMessages().getLast().content().contains("EARLIER_HANDOFF_WRAPPER"));
        assertFalse(refs.providerMessages().getLast().content().contains("EARLIER_STATUS_WRAPPER"));
    }

}
