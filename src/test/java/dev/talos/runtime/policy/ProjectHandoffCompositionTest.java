package dev.talos.runtime.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.intent.WorkStatusRequest;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Constructed selections exercise the real renderer, not live model compliance. */
class ProjectHandoffCompositionTest {
    private static final String REQUEST = "Give me a short handoff. What is known, what remains unverified, "
            + "and what should happen next? Keep proposed work separate from anything actually done in this conversation.";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ProjectHandoffPolicy.References references() {
        return ProjectHandoffPolicy.capture(List.of(
                ChatMessage.user("The settings were changed."), ChatMessage.assistant("Pine was reported."),
                ChatMessage.user("The documented expectation changed."), ChatMessage.assistant("Oak was later reported."),
                ChatMessage.user("What was proposed?"), ChatMessage.assistant("Cedar was proposed, then withdrawn."),
                ChatMessage.user("Who reviews and what remains?"), ChatMessage.assistant("Review owner: Mara. Packaging remains pending.")),
                WorkStatusRequest.parse(REQUEST), ContentRestrictions.UNRESTRICTED);
    }

    @Test void exactQuestionSpansOwnTheSlotsAndRuntimeRolesDoNotCreateExtraSlots() throws Exception {
        var refs = references();
        var results = refs.requestedResults();
        assertEquals(List.of("request-0", "request-1", "request-2", "request-3", "request-4"),
                results.stream().map(ProjectHandoffPolicy.RequestedResult::id).toList());
        assertEquals(List.of("Give me a short handoff", "What is known", "what remains unverified",
                "and what should happen next", "Keep proposed work separate from anything actually done in this conversation"),
                results.stream().map(ProjectHandoffPolicy.RequestedResult::text).toList());
        var input = JSON.readTree(refs.providerMessages().getLast().content());
        assertEquals(REQUEST, input.path("current_request").asText());
        assertEquals(JSON.valueToTree(results), input.path("requested_results"));
        var schema = JSON.readTree(refs.compositionSchema());
        assertEquals(JSON.valueToTree(results.stream().map(ProjectHandoffPolicy.RequestedResult::id).toList()), schema.path("required"));
        assertEquals(5, schema.path("properties").size());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertFalse(refs.compositionSchema().contains("Mara"));
        assertFalse(refs.compositionSchema().contains("what should happen next"));
        assertThrows(IllegalArgumentException.class, () -> ProjectHandoffPolicy.capture(List.of(),
                WorkStatusRequest.parse("Give me a work status."), ContentRestrictions.UNRESTRICTED));
    }

    @Test void fourSourcesAreQuotedOnceAcrossResultSlotsWithoutImpliedMeaning() {
        String response = """
                {"request-0":["reference-0","reference-2","reference-5","reference-7"],
                 "request-1":["reference-0","reference-2","reference-5"],
                 "request-2":["reference-7"],"request-3":["reference-7"],"request-4":["reference-5"]}
                """;
        String answer = ProjectHandoffPolicy.renderComposition(references(), response);
        for (String quote : List.of("The settings were changed.", "The documented expectation changed.",
                "Cedar was proposed, then withdrawn.", "Review owner: Mara. Packaging remains pending."))
            assertEquals(1, answer.split(java.util.regex.Pattern.quote("> " + quote), -1).length - 1, answer);
        assertTrue(answer.contains("New clarification proposal for request-3, using reference-7"), answer);
        assertTrue(answer.contains("not a recorded plan, performed work or execution permission"));
        assertTrue(answer.contains("semantic coverage remain unverified"));
        assertFalse(answer.contains("Mara must"));
    }

    @Test void emptyResultsRemainGapsEvenWhenAnotherResultSelectsMaterial() {
        String answer = ProjectHandoffPolicy.renderComposition(references(), """
                {"request-0":["reference-7"],"request-1":[],"request-2":[],"request-3":[],"request-4":[]}
                """);
        assertEquals(4, answer.split("No historical material was selected for this result", -1).length - 1);
        assertFalse(answer.contains("New clarification proposal"));
        assertTrue(answer.contains("> and what should happen next"));
    }

    @Test void aMissingKeyDiscardsAllSelectionsAndAccountsEveryOriginalQuestion() {
        String answer = ProjectHandoffPolicy.renderComposition(references(), """
                {"request-0":["reference-7"],"request-1":[],"request-2":[],"request-3":[]}
                """);
        assertEquals(5, answer.split("No historical material was selected for this result", -1).length - 1);
        for (String question : List.of("Give me a short handoff", "What is known", "what remains unverified",
                "and what should happen next", "Keep proposed work separate from anything actually done in this conversation"))
            assertTrue(answer.contains("> " + question), answer);
        assertFalse(answer.contains("Review owner: Mara"));
        assertFalse(answer.contains("New clarification proposal"));
    }

    @Test void maximumRequestComponentsAndResponseBoundsRemainExplicit() throws Exception {
        String request = "Give me a handoff. " + "What should happen next? ".repeat(15);
        var refs = ProjectHandoffPolicy.capture(List.of(ChatMessage.user("Earlier question"),
                ChatMessage.assistant("Earlier claim")), WorkStatusRequest.parse(request), ContentRestrictions.UNRESTRICTED);
        assertEquals(16, refs.requestedResults().size());
        var minimal = new java.util.LinkedHashMap<String, List<String>>();
        for (int index = 0; index < 16; index++) minimal.put("request-" + index, List.of());
        String response = JSON.writeValueAsString(minimal);
        assertTrue(response.length() < 2048);
        assertEquals(16, JSON.readTree(refs.compositionSchema()).path("required").size());
        assertEquals(16, ProjectHandoffPolicy.renderComposition(refs, response)
                .split("No historical material was selected for this result", -1).length - 1);
        assertEquals(ProjectHandoffPolicy.unavailable(WorkStatusRequest.parse(request)),
                ProjectHandoffPolicy.renderComposition(refs, response + " ".repeat(2049 - response.length())));
    }

    @Test void irrelevantAndContradictorySelectionsRemainHistoricalNotPendingOrFresh() {
        String answer = ProjectHandoffPolicy.renderComposition(references(), """
                {"request-0":[],"request-1":["reference-1","reference-3"],"request-2":[],
                 "request-3":["reference-1"],"request-4":[]}
                """);
        assertTrue(answer.contains("> Pine was reported."));
        assertTrue(answer.contains("> Oak was later reported."));
        assertTrue(answer.contains("New clarification proposal for request-3, using reference-1"));
        assertFalse(answer.contains("Pine remains pending"));
        assertFalse(answer.contains("Oak supersedes"));
        assertTrue(answer.contains("Relevance is unverified"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"references\":[\"reference-1\"]}", "{}", "null", "[]", "",
            "{\"request-0\":[],\"request-1\":[],\"request-2\":[],\"request-3\":[]}",
            "{\"request-0\":[],\"request-1\":[],\"request-2\":[],\"request-3\":[],\"answer\":\"Ready\"}",
            "{\"request-0\":[],\"request-1\":[],\"request-2\":[],\"request-3\":[],\"request-4\":[],\"extra\":[]}",
            "{\"request-0\":[\"reference-999\"],\"request-1\":[],\"request-2\":[],\"request-3\":[],\"request-4\":[]}",
            "{\"request-0\":[\"reference-1\",\"reference-1\"],\"request-1\":[],\"request-2\":[],\"request-3\":[],\"request-4\":[]}",
            "{\"request-0\":[1],\"request-1\":[],\"request-2\":[],\"request-3\":[],\"request-4\":[]}",
            "{\"request-0\":[],\"request-1\":[],\"request-2\":[],\"request-3\":null,\"request-4\":[]}",
            "{\"request-0\":[],\"request-1\":[],\"request-2\":[],\"request-3\":[],\"request-4\":[],\"request-4\":[]}",
            "{\"request-0\":[],\"request-1\":[],\"request-2\":[],\"request-3\":[],\"request-4\":[],\"request-\\u0034\":[]}",
            "{\"request-0\":[],\"request-1\":[],\"request-2\":[],\"request-3\":[],\"request-4\":[]} {}"
    })
    void invalidCompositionCannotFallBackToLegacyOrInventProse(String response) {
        assertEquals(ProjectHandoffPolicy.unavailable(WorkStatusRequest.parse(REQUEST)),
                ProjectHandoffPolicy.renderComposition(references(), response));
    }

    @Test void sourceCharacterCapCountsUniqueWholeMessagesNotMappingRepetitions() {
        String request = "Give me a handoff. What should happen next?";
        var refs = ProjectHandoffPolicy.capture(List.of(ChatMessage.user("A".repeat(3000)),
                ChatMessage.assistant("B".repeat(3000))), WorkStatusRequest.parse(request), ContentRestrictions.UNRESTRICTED);
        String answer = ProjectHandoffPolicy.renderComposition(refs,
                "{\"request-0\":[\"reference-0\",\"reference-1\"],\"request-1\":[\"reference-0\",\"reference-1\"]}");
        assertNotEquals(ProjectHandoffPolicy.unavailable(WorkStatusRequest.parse(request)), answer);
        assertEquals(1, answer.split("> " + "A".repeat(3000), -1).length - 1);
        var tooLarge = new ProjectHandoffPolicy.References(List.of(
                new ProjectHandoffPolicy.Reference("a", "user", "A".repeat(3000)),
                new ProjectHandoffPolicy.Reference("b", "assistant", "B".repeat(3000)),
                new ProjectHandoffPolicy.Reference("c", "assistant", "C")), false, ContentRestrictions.UNRESTRICTED, request);
        assertEquals(ProjectHandoffPolicy.unavailable(WorkStatusRequest.parse(request)), ProjectHandoffPolicy.renderComposition(tooLarge,
                "{\"request-0\":[\"a\",\"b\",\"c\"],\"request-1\":[]}"));
        assertEquals(ProjectHandoffPolicy.unavailable(WorkStatusRequest.parse(request)),
                ProjectHandoffPolicy.renderComposition(refs, " ".repeat(2049)));
    }

    @Test void privacyAndRetentionCannotBeBypassedThroughResultMapping() throws Exception {
        var history = new ArrayList<ChatMessage>();
        history.add(ChatMessage.user("PRIVATE_QUESTION").withContentRestrictions(new ContentRestrictions(false, false, true, false)));
        history.add(ChatMessage.assistant("PRIVATE_FACT"));
        history.add(ChatMessage.user("Public question"));
        history.add(ChatMessage.assistant("Public old claim"));
        var refs = ProjectHandoffPolicy.capture(history, WorkStatusRequest.parse(REQUEST), ContentRestrictions.UNRESTRICTED);
        assertTrue(refs.omitted());
        assertFalse(refs.providerMessages().toString().contains("PRIVATE_"));
        assertFalse(refs.compositionSchema().contains("reference-1"));
        assertEquals(ProjectHandoffPolicy.unavailable(WorkStatusRequest.parse(REQUEST)), ProjectHandoffPolicy.renderComposition(refs,
                "{\"request-0\":[\"reference-1\"],\"request-1\":[],\"request-2\":[],\"request-3\":[],\"request-4\":[]}"));
        var restricted = ProjectHandoffPolicy.capture(history, WorkStatusRequest.parse(REQUEST),
                new ContentRestrictions(true, false, false, false));
        assertEquals(new ContentRestrictions(true, false, false, false), restricted.providerMessages().getLast().contentRestrictions());
        assertEquals(JSON.valueToTree(refs.requestedResults()),
                JSON.readTree(refs.providerMessages().getLast().content()).path("requested_results"));
    }
}
