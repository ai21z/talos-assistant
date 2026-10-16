package dev.talos.runtime.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.util.Sanitize;
import dev.talos.runtime.intent.WorkStatusRequest;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Constructed provider payloads exercise structural validation, not live interpretation. */
class ProjectHandoffAdvisoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUEST = "Give me a short handoff. What should happen next?";

    private static ProjectHandoffPolicy.References references() {
        return references(REQUEST, List.of(ChatMessage.user("Review is assigned to Mara."),
                ChatMessage.assistant("Packaging remains pending.")));
    }

    private static ProjectHandoffPolicy.References references(String request, List<ChatMessage> history) {
        return ProjectHandoffPolicy.capture(history, WorkStatusRequest.parse(request), ContentRestrictions.UNRESTRICTED);
    }

    private static Map<String, Object> unit(String text, String... ids) {
        return Map.of("text", text, "sources", List.of(ids));
    }

    private static Map<String, Object> slot(List<?> summary, List<?> proposal, String gap) {
        return Map.of("summary", summary, "proposal", proposal, "gap", gap);
    }

    private static String payload(Object first, Object next) throws Exception {
        var slots = new LinkedHashMap<String, Object>();
        slots.put("request-0", first);
        slots.put("request-1", next);
        return JSON.writeValueAsString(slots);
    }

    private static String allGaps() throws Exception {
        return payload(slot(List.of(), List.of(), "Historical procedure is unavailable."),
                slot(List.of(), List.of(), "Earlier next steps are unavailable."));
    }

    @Test void summaryProposalAndLocalGapRetainTheExactSnapshotAndQuestionMetadata() throws Exception {
        var refs = references();
        String response = payload(slot(List.of(unit("Packaging was reported pending.", "reference-1")), List.of(), ""),
                slot(List.of(), List.of(unit("Clarify the packaging procedure.", "reference-1")),
                        "An earlier packaging procedure was not supplied."));
        var draft = ProjectHandoffPolicy.decodeAdvisory(refs, response).orElseThrow();
        assertSame(refs, draft.references());
        assertEquals(refs.requestedResults(), draft.slots().stream().map(ProjectHandoffPolicy.AdvisorySlot::result).toList());
        assertEquals(List.of(refs.entries().getLast()), draft.usedSources());
        assertEquals("assistant", draft.usedSources().getFirst().speaker());
        assertEquals("An earlier packaging procedure was not supplied.", draft.slots().getLast().gap());
        String answer = ProjectHandoffPolicy.renderAdvisory(draft);
        assertTrue(answer.contains("Original project question: \"Give me a short handoff\""));
        assertTrue(answer.contains("Original project question: \"What should happen next\""));
        assertTrue(answer.contains("Historical summary unavailable for this question."));
        assertTrue(answer.contains("New advisory proposal, unverified"));
        assertTrue(answer.contains("Model-identified gap, unverified"));
        assertTrue(answer.contains("reference-1. Earlier assistant statement."));
        assertThrows(UnsupportedOperationException.class, () -> draft.slots().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.slots().getFirst().summary().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.slots().getFirst().summary().getFirst().sources().clear());
    }

    @Test void schemaAndProviderMessagesExposeClosedExactSlotsAndRetainPrivacy() throws Exception {
        var refs = references();
        var schema = JSON.readTree(refs.advisorySchema());
        assertEquals(JSON.readTree("[\"request-0\",\"request-1\"]"), schema.get("required"));
        assertFalse(schema.get("additionalProperties").asBoolean());
        var first = schema.at("/properties/request-0");
        assertEquals(JSON.readTree("[\"summary\",\"proposal\",\"gap\"]"), first.get("required"));
        assertFalse(first.get("additionalProperties").asBoolean());
        assertEquals(0, first.at("/properties/proposal/maxItems").asInt());
        assertFalse(schema.at("/properties/request-1/properties/proposal").has("maxItems"));
        var unitSchema = first.at("/properties/summary/items");
        assertFalse(unitSchema.get("additionalProperties").asBoolean());
        assertEquals(JSON.readTree("[\"text\",\"sources\"]"), unitSchema.get("required"));
        assertEquals(1, unitSchema.at("/properties/sources/minItems").asInt());
        assertTrue(unitSchema.at("/properties/sources/uniqueItems").asBoolean());
        assertFalse(refs.advisorySchema().contains("Mara"));
        var messages = refs.advisoryProviderMessages();
        assertNotEquals(refs.providerMessages().getFirst().content(), messages.getFirst().content());
        assertEquals(refs.providerMessages().getLast(), messages.getLast());
        assertEquals(REQUEST, JSON.readTree(messages.getLast().content()).get("current_request").asText());

        var restrictions = new ContentRestrictions(true, false, false, false);
        var privateRefs = ProjectHandoffPolicy.capture(List.of(), WorkStatusRequest.parse(REQUEST), restrictions);
        assertEquals(restrictions, privateRefs.advisoryProviderMessages().getLast().contentRestrictions());
        ContentRestrictionScope.callWithRestrictions(new ContentRestrictions(false, false, false, false), () -> {
            assertThrows(IllegalArgumentException.class, refs::advisoryProviderMessages);
            return null;
        });
    }

    @Test void missingSummaryRequiresHistoricalGapEvenWithAProposal() throws Exception {
        for (String missing : List.of("", " ", "\t\n")) {
            assertTrue(ProjectHandoffPolicy.decodeAdvisory(references(), payload(
                    slot(List.of(), List.of(), "Unavailable"),
                    slot(List.of(), List.of(unit("Ask the owner", "reference-1")), missing))).isEmpty());
        }
        assertTrue(ProjectHandoffPolicy.decodeAdvisory(references(), allGaps()).isPresent());
        assertTrue(ProjectHandoffPolicy.decodeAdvisory(references(), payload(
                slot(List.of(unit("Pending", "reference-1")), List.of(), "Owner unknown"),
                slot(List.of(), List.of(), "Procedure unknown"))).isPresent());
    }

    @Test void proposalRequiresNextStepsRoleAndSourcesRequireUniqueAdmittedIdentities() throws Exception {
        assertTrue(ProjectHandoffPolicy.decodeAdvisory(references(), payload(
                slot(List.of(), List.of(unit("Do packaging", "reference-1")), "Unknown"),
                slot(List.of(), List.of(), "Unknown"))).isEmpty());
        for (Object badUnit : List.of(unit("claim"), unit("claim", "reference-999"),
                unit("claim", "reference-1", "reference-1"), unit(" ", "reference-1"),
                Map.of("text", 5, "sources", List.of("reference-1")),
                Map.of("text", "claim", "sources", List.of(1)),
                Map.of("text", "claim", "sources", List.of("reference-1"), "verified", true))) {
            assertTrue(ProjectHandoffPolicy.decodeAdvisory(references(), payload(
                    slot(List.of(badUnit), List.of(), ""), slot(List.of(), List.of(), "Unknown"))).isEmpty(), badUnit.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{", "{\"references\":[\"reference-1\"]}",
            "{\"request-0\":[],\"request-1\":[]}",
            "{\"request-0\":{\"summary\":[],\"proposal\":[],\"gap\":\"unknown\"}}"})
    void malformedOrLegacyFramesFailClosed(String response) {
        assertTrue(ProjectHandoffPolicy.decodeAdvisory(references(), response).isEmpty());
    }

    @Test void extraMissingWrongTypedAndDuplicateFieldsAreRejectedAtEveryDepth() throws Exception {
        String valid = allGaps();
        var malformed = new ArrayList<String>();
        malformed.add(valid + " {}");
        malformed.add("```json\n" + valid + "\n```");
        malformed.add(valid.replaceFirst("\\{", "{\"extra\":null,"));
        malformed.add(valid.replace("\"summary\":[]", "\"summary\":null"));
        malformed.add(valid.replace("\"summary\":[]", "\"summary\":[] ,\"current\":true"));
        malformed.add(valid.replace("\"summary\":[]", "\"summary\":[],\"summary\":[]"));
        malformed.add(valid.replace("\"summary\":[]", "\"summary\":[],\"summar\\u0079\":[]"));
        var missingSummary = JSON.readTree(valid);
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingSummary.get("request-0")).remove("summary");
        malformed.add(JSON.writeValueAsString(missingSummary));
        malformed.add(valid.replace("\"proposal\":[]", "\"proposal\":{}"));
        malformed.add(valid.replace("\"request-1\"", "\"request-0\""));
        malformed.add(valid.replace("\"request-1\"", "\"request-\\u0030\""));
        String withUnit = payload(slot(List.of(unit("Claim", "reference-1")), List.of(), ""),
                slot(List.of(), List.of(), "Unknown"));
        malformed.add(withUnit.replace("\"text\":\"Claim\"", "\"text\":\"Claim\",\"te\\u0078t\":\"Other\""));
        malformed.add(withUnit.replace("\"reference-1\"", "null"));
        for (String response : malformed)
            assertTrue(ProjectHandoffPolicy.decodeAdvisory(references(), response).isEmpty(), response);
    }

    @Test void rawUtf16BoundIncludesWhitespaceAndUnpairedSurrogatesFailClosed() throws Exception {
        String valid = allGaps();
        assertTrue(ProjectHandoffPolicy.decodeAdvisory(references(), valid + " ".repeat(2048 - valid.length())).isPresent());
        assertTrue(ProjectHandoffPolicy.decodeAdvisory(references(), valid + " ".repeat(2049 - valid.length())).isEmpty());
        for (String bad : List.of("\\uD800", "\\uDC00", "\\uD800x", String.valueOf((char) 0xD800))) {
            assertTrue(ProjectHandoffPolicy.decodeAdvisory(references(), valid.replace("Historical procedure is unavailable.", bad)).isEmpty());
        }
        String emoji = new String(Character.toChars(0x1F331));
        String astral = valid.replace("Historical procedure is unavailable.", emoji);
        assertTrue(ProjectHandoffPolicy.decodeAdvisory(references(), astral).isPresent());
    }

    @Test void sourceCharacterBudgetCountsUniqueCompleteSourcesAcrossSlots() throws Exception {
        var refs = references(REQUEST, List.of(ChatMessage.user("A".repeat(3000)), ChatMessage.assistant("B".repeat(3000))));
        var both = unit("Earlier reports", "reference-0", "reference-1");
        String response = payload(slot(List.of(both), List.of(), ""), slot(List.of(both), List.of(), ""));
        assertTrue(ProjectHandoffPolicy.decodeAdvisory(refs, response).isPresent());
        var larger = references(REQUEST, List.of(ChatMessage.user("A".repeat(3000)), ChatMessage.assistant("B".repeat(3000)),
                ChatMessage.user("C"), ChatMessage.assistant("D")));
        assertTrue(ProjectHandoffPolicy.decodeAdvisory(larger, payload(
                slot(List.of(unit("Earlier reports", "reference-0", "reference-1", "reference-3")), List.of(), ""),
                slot(List.of(), List.of(), "Unknown"))).isEmpty());
    }

    @Test void exactDeduplicationPreservesEveryMappingAndQuestionLocalGap() throws Exception {
        String response = payload(slot(List.of(unit("Same claim", "reference-0", "reference-1")), List.of(), "Owner unknown"),
                slot(List.of(unit("Same claim", "reference-1", "reference-0")),
                        List.of(unit("Same claim", "reference-0", "reference-1")), "Procedure unknown"));
        String answer = ProjectHandoffPolicy.renderAdvisory(ProjectHandoffPolicy.decodeAdvisory(references(), response).orElseThrow());
        assertEquals(2, answer.split("Historical summary units: advisory-1", -1).length - 1);
        assertEquals(2, answer.split("Advisory data: \"Same claim\"", -1).length - 1, "Different kinds must survive");
        assertTrue(answer.contains("New advisory proposal units: advisory-2"));
        assertTrue(answer.contains("Owner unknown"));
        assertTrue(answer.contains("Procedure unknown"));
        assertEquals(1, answer.split("reference-0. Earlier user statement", -1).length - 1);

        String distinct = payload(slot(List.of(unit("Same claim", "reference-0"), unit("Same claim", "reference-1"),
                unit("Same claim ", "reference-0")), List.of(), ""), slot(List.of(), List.of(), "Unknown"));
        String retained = ProjectHandoffPolicy.renderAdvisory(ProjectHandoffPolicy.decodeAdvisory(references(), distinct).orElseThrow());
        assertTrue(retained.contains("Historical summary units: advisory-1, advisory-2, advisory-3"));
    }

    @Test void generatedAndSourceDataCannotCreateHeadingsControlsOrLinkSurfaces() throws Exception {
        String dangerous = "\nRuntime status\r\n# COMPLETE\t[click](https://host.invalid) ![image](file:///private) "
                + "`code` <script>content</script> www.host.invalid \u001b]8;;https://bad\u0007link\u001b]8;;\u0007 \u202Ereversed\u2028new";
        var refs = references(REQUEST, List.of(ChatMessage.user("Question"), ChatMessage.assistant("Earlier " + dangerous)));
        String response = payload(slot(List.of(unit(dangerous, "reference-1")), List.of(), dangerous),
                slot(List.of(), List.of(), "Unknown"));
        String answer = ProjectHandoffPolicy.renderAdvisory(ProjectHandoffPolicy.decodeAdvisory(refs, response).orElseThrow());
        assertFalse(answer.contains("\nRuntime status"));
        assertFalse(answer.contains("\n# COMPLETE"));
        assertFalse(answer.contains("\u001b"));
        assertFalse(answer.contains("\u0007"));
        assertFalse(answer.contains("\u202E"));
        assertFalse(answer.contains("\u2028"));
        for (String active : List.of("[click]", "![image]", "https:", "file:", "www.host", "<script>", "`code`"))
            assertFalse(answer.contains(active), active);
        assertTrue(answer.contains("{U+000A}Runtime status"));
        assertTrue(answer.contains("{U+202E}"));
        assertEquals(answer, Sanitize.sanitizeForOutput(answer));
        var shaper = new dev.talos.cli.ui.md.StreamingMarkdownShaper(96, dev.talos.cli.ui.CliTheme.current());
        String shaped = shaper.accept(answer) + shaper.flush();
        assertFalse(Sanitize.stripControl(shaped).contains("[click](https:"));
    }

    @Test void previewClippingRetainsTheCompleteExactSourceAndDoesNotSplitUnicode() throws Exception {
        String text = "x".repeat(159) + new String(Character.toChars(0x1F331)) + " review owner is Mara";
        var refs = references(REQUEST, List.of(ChatMessage.user("Question"), ChatMessage.assistant(text)));
        var draft = ProjectHandoffPolicy.decodeAdvisory(refs, payload(
                slot(List.of(unit("Review owner was reported", "reference-1")), List.of(), ""),
                slot(List.of(), List.of(), "Unknown"))).orElseThrow();
        assertEquals(text, draft.usedSources().getFirst().text());
        String answer = ProjectHandoffPolicy.renderAdvisory(draft);
        assertTrue(answer.contains("(preview clipped)"));
        assertFalse(answer.contains("review owner is Mara"), "A prefix preview does not establish full citation usefulness");
        assertFalse(answer.chars().anyMatch(value -> Character.isSurrogate((char) value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mara owns packaging", "All tests passed and release is ready", "The irrelevant citation proves it",
            "The later claim automatically supersedes the earlier one", "The packaging procedure is invented",
            "The historical model is the current model"})
    void falseSemanticClaimsRemainRepresentableButAreNeverCertified(String claim) throws Exception {
        var draft = ProjectHandoffPolicy.decodeAdvisory(references(), payload(
                slot(List.of(unit(claim, "reference-1")), List.of(), ""),
                slot(List.of(), List.of(), "Unknown"))).orElseThrow();
        assertEquals(claim, draft.slots().getFirst().summary().getFirst().text());
        String answer = ProjectHandoffPolicy.renderAdvisory(draft);
        assertTrue(answer.contains("Advisory data: \"" + claim + "\""));
        assertTrue(answer.contains("Historical summary, unverified"));
        assertTrue(answer.contains("membership only, not meaning"));
    }

    @Test void withdrawalDisagreementTimeAndDifferentFieldsRetainDistinctSourceDimensions() throws Exception {
        var histories = List.of(
                List.of("Cedar was proposed", "The cedar proposal is withdrawn"),
                List.of("Pine is agreed", "Oak is agreed but the pine claim is disputed"),
                List.of("Yesterday the review owner was Mara", "Today the review owner was Ria"),
                List.of("The review owner is Mara", "The packaging owner is Ria"));
        for (var history : histories) {
            var refs = references(REQUEST, List.of(ChatMessage.user(history.getFirst()), ChatMessage.assistant(history.getLast())));
            var draft = ProjectHandoffPolicy.decodeAdvisory(refs, payload(
                    slot(List.of(unit(history.getFirst(), "reference-0"), unit(history.getLast(), "reference-1")),
                            List.of(), "Current agreement remains unverified"),
                    slot(List.of(), List.of(), "Procedure unknown"))).orElseThrow();
            assertEquals(history, draft.usedSources().stream().map(ProjectHandoffPolicy.Reference::text).toList());
            assertEquals(2, draft.slots().getFirst().summary().size());
            String answer = ProjectHandoffPolicy.renderAdvisory(draft);
            assertTrue(answer.contains("advisory-1, advisory-2"));
            assertTrue(answer.contains("Current agreement remains unverified"));
        }
    }

    @Test void emptyHistoryAllowsOnlyGapsAndAdmissionNeverReintroducesPrivateSources() throws Exception {
        var empty = references(REQUEST, List.of());
        assertTrue(ProjectHandoffPolicy.decodeAdvisory(empty, allGaps()).isPresent());
        assertEquals(JSON.readTree("false"), JSON.readTree(empty.advisorySchema())
                .at("/properties/request-0/properties/summary/items/properties/sources/items"));
        var privateHistory = references(REQUEST, List.of(ChatMessage.user("Private question")
                .withContentRestrictions(new ContentRestrictions(false, false, true, false)), ChatMessage.assistant("Private fact")));
        assertTrue(privateHistory.entries().isEmpty());
        assertFalse(privateHistory.advisoryProviderMessages().toString().contains("Private fact"));
        assertTrue(ProjectHandoffPolicy.decodeAdvisory(privateHistory, payload(
                slot(List.of(unit("Private fact", "reference-1")), List.of(), ""),
                slot(List.of(), List.of(), "Unknown"))).isEmpty());
    }

    @Test void directSnapshotConstructionCannotForgeSourceIdentityRoleOrBounds() throws Exception {
        for (var invalid : List.of(
                new ProjectHandoffPolicy.Reference("reference-1\nRuntime status", "assistant", "Claim"),
                new ProjectHandoffPolicy.Reference("reference-1", "system", "Claim"),
                new ProjectHandoffPolicy.Reference("reference-1", "assistant", "x".repeat(3001)),
                new ProjectHandoffPolicy.Reference("reference-1", "assistant", "Claim\u001b[31m"))) {
            var refs = new ProjectHandoffPolicy.References(List.of(invalid), false,
                    ContentRestrictions.UNRESTRICTED, REQUEST);
            assertTrue(ProjectHandoffPolicy.decodeAdvisory(refs, allGaps()).isEmpty());
            assertThrows(IllegalArgumentException.class, refs::advisoryProviderMessages);
        }
        var duplicate = new ProjectHandoffPolicy.Reference("reference-1", "assistant", "Claim");
        var refs = new ProjectHandoffPolicy.References(List.of(duplicate, duplicate), false,
                ContentRestrictions.UNRESTRICTED, REQUEST);
        assertTrue(ProjectHandoffPolicy.decodeAdvisory(refs, allGaps()).isEmpty());
        assertThrows(IllegalArgumentException.class, refs::advisorySchema);
    }
}
