package dev.talos.runtime.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.task.AnswerResultRequirements;
import dev.talos.runtime.task.ReadOnlyRequestMeaning;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static dev.talos.runtime.task.AnswerResultRequirements.Role;
import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;
import static org.junit.jupiter.api.Assertions.*;

/** Independent request-meaning acceptance with authored selections, never model generations. */
class NativeRequestMeaningAcceptanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String READ = "Could you please inspect guide.md, index.html, engine.js and spare.js. ";
    private static final String LINKAGE = "Describe how engine.js differs from spare.js in their HTML linkage. ";
    private static final String NUMBERS = "For volume 24 L and elapsed 6 s, give the flow rate according to guide.md "
            + "and the mathematical value of the expression in engine.js separately. Explain any disagreement. ";
    private static final String RESTRICTION = "Do not edit files or claim tests were run.";
    private static final String REQUEST = READ
            + "Please explain exactly why the Compute button is broken. Identify the selector that should be used. "
            + NUMBERS + LINKAGE + RESTRICTION;
    private static final String GUIDE = "# Flow reference\nThe flow rate is volume divided by elapsed.\n";
    private static final String HTML = """
            <!doctype html>
            <html><body>
            <label>Volume (L)<input id="volume" value="100"></label>
            <label>Elapsed (s)<input id="elapsed" value="20"></label>
            <button id="compute">Compute</button><p id="result"></p>
            <script src="engine.js"></script>
            </body></html>
            """;
    private static final String SCRIPT = """
            const button = document.querySelector('#missing');
            button.addEventListener('click', () => {
              const volume = Number(document.querySelector('#volume').value);
              const elapsed = Number(document.querySelector('#elapsed').value);
              document.querySelector('#result').textContent = (volume + elapsed).toFixed(2);
            });
            """;

    @ParameterizedTest
    @ValueSource(strings = {
            "Please explain exactly why the Compute button is broken. Identify the selector that should be used. ",
            "Would you please describe why the Compute button does not work and state the correct selector. ",
            "Give the correct selector for the Compute button. Could you explain why the Compute button fails. "
    })
    void declaredPoliteCoordinatedAndReorderedConstructionsReachCompleteAssessment(String results) throws Exception {
        var fixture = fixture(READ + results + LINKAGE + RESTRICTION, sources());

        assertEquals(List.of(Role.DIAGNOSIS, Role.SELECTOR, Role.SCRIPT_DISTINCTION).stream().sorted().toList(),
                fixture.catalog().requirements().slots().stream().map(AnswerResultRequirements.Slot::role).sorted().toList());
        assertEquals(4, fixture.meaning().readReferences().size());
        assertTrue(fixture.catalog().relations().isEmpty(), "Read directives must not create supplemental choices.");
        assertTrue(fixture.schema().path("properties").path("interpretationDecisions").path("properties").isEmpty());
        assertTrue(NativeReadOnlyAnswerProposal.instructions(fixture.meaning(), fixture.sources()).contains(fixture.catalog().id()));
        assertEquals(fixture.catalog().id(), fixture.schema().path("properties").path("catalogId").path("enum").get(0).asText());

        var answer = assess(fixture);
        complete(answer);
        assertTrue(answer.answer().contains("lookup would be null"));
        assertTrue(answer.answer().contains("`#missing` should be replaced by `#compute`"));
        assertTrue(answer.answer().contains("`index.html` links `engine.js`"));
        assertTrue(answer.answer().contains("`spare.js` was also read"));
    }

    @Test
    void originalC2KeepsTheBroaderDifferenceDemandWhileDeliveringSupportedSiblings() throws Exception {
        JsonNode saved;
        try (var stream = getClass().getResourceAsStream("native-v4-saved-responses.json")) {
            assertNotNull(stream);
            saved = JSON.readTree(stream);
        }
        JsonNode context = saved.path("contexts").path("C2");
        List<Source> observed = new ArrayList<>();
        for (int index = 0; index < context.path("paths").size(); index++) {
            observed.add(source(context.path("paths").get(index).asText(),
                    saved.path("sources").path(context.path("sources").get(index).asText()).path("text").asText()));
        }
        var fixture = fixture(context.path("request").asText(), observed);
        var answer = assess(fixture);

        assertEquals(5, fixture.meaning().readReferences().size());
        assertEquals(1, slots(fixture, Role.DIAGNOSIS).size());
        assertEquals(1, slots(fixture, Role.SELECTOR).size());
        assertEquals(1, slots(fixture, Role.SOURCE_DEFINITION).size());
        assertTrue(slots(fixture, Role.UNKNOWN).stream().anyMatch(slot -> fragment(fixture, slot)
                .equals("explain how script.js differs from scripts.js")));
        incomplete(answer);
        assertTrue(answer.answer().contains("`22.2`"));
        assertTrue(answer.answer().contains("lookup would be null"));
        assertTrue(answer.answer().contains("`#calc` should be replaced by `#calculate`"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Explain how engine.js differs from spare.js.",
            "Compare engine.js and spare.js.",
            "Distinguish engine.js and spare.js.",
            "Compare engine.js and spare.js in their behavior.",
            "Describe how engine.js differs from spare.js in their HTML linkage and performance."
    })
    void linkageEvidenceCannotCompleteBroaderOrPartlyUnsupportedScriptComparison(String demand) throws Exception {
        var fixture = fixture(READ + "Explain why the Compute button fails. " + NUMBERS + demand, sources());

        assertFalse(slots(fixture, Role.UNKNOWN).isEmpty(), demand);
        var answer = assess(fixture);
        incomplete(answer);
        assertTrue(answer.answer().contains("`4.00`"));
        assertTrue(answer.answer().contains("`30.00`"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Please read guide.md, index.html, engine.js and spare.js and summarize their security risks. ",
            "Read guide.md, index.html, engine.js and spare.js, then explain the accessibility problems. ",
            "Could you inspect guide.md, index.html, engine.js and spare.js and compare their performance. "
    })
    void successfulReadsCannotEraseTrailingAnswerDemands(String readAndDemand) throws Exception {
        var fixture = fixture(readAndDemand + "Explain why the Compute button fails. " + RESTRICTION, sources());

        assertFalse(slots(fixture, Role.UNKNOWN).isEmpty());
        incomplete(assess(fixture));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Describe why the Compute button looks broken.",
            "Explain exactly why the Compute button is blue.",
            "Identify the selector that should be used.",
            "If the Compute button is broken, explain why it fails.",
            "The report says 'explain why the Compute button fails'. Summarize the report."
    })
    void availableFailureEvidenceCannotChooseAnUnsupportedMeaningOrMissingTarget(String demand) throws Exception {
        var fixture = fixture(READ + demand + " " + RESTRICTION, sources());

        assertFalse(slots(fixture, Role.UNKNOWN).isEmpty());
        incomplete(assess(fixture));
    }

    @Test
    void requestTargetCannotBeReplacedWithAnotherAuthenticObservedButton() throws Exception {
        var observed = replace(sources(), "index.html", HTML.replace("<button id=\"compute\">",
                "<button id=\"reset\">Reset</button><button id=\"compute\">"));
        var fixture = fixture(REQUEST, observed);
        assertEquals(List.of("compute"), fixture.catalog().targets().stream()
                .map(NativeReadOnlyAnswerProposal.TargetReference::buttonId).toList());
        complete(assess(fixture));

        var candidate = fixture.candidate().deepCopy();
        candidate.put("targetId", "reset");
        incomplete(NativeReadOnlyAnswerProposal.assess(candidate.toString(), fixture.meaning(), observed));
    }

    @Test
    void requestForMissingButtonCannotUseTheOnlyObservedButton() throws Exception {
        var fixture = fixture(REQUEST, sources());
        var other = ReadOnlyRequestMeaning.compile(REQUEST.replace("Compute button", "Reset button"));

        incomplete(NativeReadOnlyAnswerProposal.assess(fixture.candidate().toString(), other, sources()));
    }

    @Test
    void competingRequestTargetsCannotBeResolvedBySourceAvailability() {
        rejectedMeaningBoundary(READ + "Explain why the Compute button fails. Explain why the Reset button fails. "
                + "Identify the selector that should be used. " + RESTRICTION, sources(), "KNOWN_TARGET_AMBIGUOUS");
    }

    @Test
    void numericalSlotsAndComparisonPeersRemainIdenticalToTheOriginalLedger() throws Exception {
        var fixture = fixture(REQUEST, sources());
        var original = AnswerResultRequirements.fromRequest(REQUEST);

        assertEquals(original.slots().stream().filter(NativeRequestMeaningAcceptanceTest::numerical).toList(),
                fixture.meaning().requirements().slots().stream().filter(NativeRequestMeaningAcceptanceTest::numerical).toList());
        assertEquals(3, fixture.meaning().requirements().slots().stream()
                .filter(NativeRequestMeaningAcceptanceTest::numerical).count());
        var answer = assess(fixture);
        complete(answer);
        assertTrue(answer.answer().contains("`4.00`"));
        assertTrue(answer.answer().contains("`30.00`"));
        assertTrue(answer.answer().contains("disagree"));
    }

    @Test
    void versionSixSelectionsCannotBeReinterpretedAgainstTheRetainedMeaningPlan() throws Exception {
        var fixture = fixture(REQUEST, sources());
        var legacy = NativeAnswerProposalTestSupport.proposal(REQUEST, sources());
        assertEquals(6, legacy.path("version").intValue());
        assertEquals(7, fixture.candidate().path("version").intValue());

        incomplete(NativeReadOnlyAnswerProposal.assess(legacy.toString(), fixture.meaning(), fixture.sources()));
        var falseEnvelope = fixture.candidate().deepCopy().put("version", 6);
        incomplete(NativeReadOnlyAnswerProposal.assess(falseEnvelope.toString(), fixture.meaning(), fixture.sources()));
    }

    @Test
    void eachRequiredResultRemainsUnmetWhenOmittedIncludingAcceptedSuccessors() throws Exception {
        var fixture = fixture(REQUEST, sources());
        complete(assess(fixture));

        for (var slot : fixture.catalog().requirements().slots()) {
            var candidate = fixture.candidate().deepCopy();
            var results = (ArrayNode) candidate.path("results");
            for (int index = results.size() - 1; index >= 0; index--) {
                if (results.get(index).path("slotId").asText().equals(slot.id())) results.remove(index);
            }
            var answer = NativeReadOnlyAnswerProposal.assess(candidate.toString(), fixture.meaning(), fixture.sources());
            incomplete(answer);
            assertTrue(answer.components().components().stream().anyMatch(component ->
                    component.requirementId().equals(slot.id()) && !component.met()), slot.id());
        }
    }

    @Test
    void wrongFormulaRoleCannotSatisfyAnOtherwiseAcceptedRequest() throws Exception {
        var fixture = fixture(REQUEST, sources());
        for (Role role : List.of(Role.SOURCE_DEFINITION, Role.IMPLEMENTATION)) {
            var candidate = fixture.candidate().deepCopy();
            var slot = slots(fixture, role).getFirst();
            var wrongOrigin = role == Role.SOURCE_DEFINITION
                    ? ReadOnlyAnswerProposal.FormulaOrigin.IMPLEMENTATION : ReadOnlyAnswerProposal.FormulaOrigin.SOURCE;
            var wrong = fixture.catalog().formulas().stream().filter(formula -> formula.origin() == wrongOrigin)
                    .findFirst().orElseThrow();
            result(candidate, slot.id()).put("formulaId", wrong.id());

            incomplete(NativeReadOnlyAnswerProposal.assess(candidate.toString(), fixture.meaning(), fixture.sources()));
        }
    }

    @Test
    void authenticFormulaFromAnotherSourceCannotReplaceTheRequestNamedDefinition() throws Exception {
        var observed = new ArrayList<>(sources());
        observed.add(source("other.md", "The flow rate is volume multiplied by elapsed.\n"));
        var fixture = fixture(REQUEST, observed);
        var sourceSlot = slots(fixture, Role.SOURCE_DEFINITION).getFirst();
        var wrong = fixture.catalog().formulas().stream().filter(formula -> formula.sourceId().equals("s4"))
                .findFirst().orElseThrow();
        var candidate = fixture.candidate().deepCopy();
        result(candidate, sourceSlot.id()).put("formulaId", wrong.id());

        incomplete(NativeReadOnlyAnswerProposal.assess(candidate.toString(), fixture.meaning(), observed));
    }

    @Test
    void extraObservedScriptDoesNotReplaceOrMakeTheExactRequestNamedPairAmbiguous() throws Exception {
        var observed = new ArrayList<>(sources());
        observed.add(source("decoy.js", "console.log('a third observed script');\n"));
        var fixture = fixture(REQUEST, observed);

        assertEquals(List.of("engine.js", "spare.js"), fixture.meaning().scriptTargets());
        var answer = assess(fixture);
        complete(answer);
        assertTrue(answer.answer().contains("`spare.js` was also read"));
        assertFalse(answer.answer().contains("`decoy.js` was also read"));
    }

    @Test
    void authenticOtherScriptCannotReplaceTheNamedLinkageCounterpart() throws Exception {
        String request = REQUEST.replace(READ, "Read guide.md, index.html and engine.js. ");
        var observed = sources().stream().map(item -> item.path().equals("spare.js")
                ? source("decoy.js", item.text()) : item).toList();
        var fixture = fixture(request, observed);
        assertEquals(List.of("engine.js", "spare.js"), fixture.meaning().scriptTargets());

        var answer = assess(fixture);
        incomplete(answer);
        assertTrue(answer.components().components().stream()
                .anyMatch(component -> component.kind().equals("SCRIPT_DISTINCTION") && !component.met()));
        assertFalse(answer.answer().contains("`decoy.js` was also read"));
    }

    @Test
    void meaningIdentityIsRequestOnlyWhileEvidenceIdentityRejectsReorderedAndChangedSources() throws Exception {
        var fixture = fixture(REQUEST, sources());
        var reordered = new ArrayList<>(fixture.sources());
        Collections.reverse(reordered);
        var fresh = fixture(REQUEST, reordered);

        assertEquals(fixture.meaning().identity(), fresh.meaning().identity());
        assertEquals(fixture.meaning().requirements().slots(), fresh.meaning().requirements().slots());
        complete(assess(fresh));
        incomplete(NativeReadOnlyAnswerProposal.assess(fixture.candidate().toString(), fixture.meaning(), reordered));
        incomplete(NativeReadOnlyAnswerProposal.assess(fixture.candidate().toString(), fixture.meaning(),
                replace(sources(), "guide.md", GUIDE + "An additional source fact.\n")));
    }

    @Test
    void acceptedReadRequirementStillNeedsItsActualSourceObservation() throws Exception {
        String request = REQUEST.replace("engine.js and spare.js. ", "engine.js, spare.js and audit.md. ");
        var completeSources = new ArrayList<>(sources());
        completeSources.add(source("audit.md", "The inspection also requires this separate read.\n"));
        var fixture = fixture(request, completeSources);
        complete(assess(fixture));

        incomplete(NativeReadOnlyAnswerProposal.assess(fixture.candidate().toString(), fixture.meaning(), sources()));
        rejectedMeaningBoundary(request, sources(), "NATIVE_REQUIRED_READ_UNAVAILABLE");
    }

    private static void rejectedMeaningBoundary(String request, List<Source> observed, String reason) {
        var meaning = ReadOnlyRequestMeaning.compile(request);
        assertEquals(reason, assertThrows(ReadOnlyAnswerProposal.Rejected.class,
                () -> NativeReadOnlyAnswerProposal.catalog(meaning, observed)).reason);
        assertEquals(reason, assertThrows(ReadOnlyAnswerProposal.Rejected.class,
                () -> NativeReadOnlyAnswerProposal.responseSchema(meaning, observed)).reason);
        var answer = NativeReadOnlyAnswerProposal.assess("{}", meaning, observed);
        incomplete(answer);
        assertEquals(reason, answer.components().components().getFirst().reason());
    }

    private static Fixture fixture(String request, List<Source> observed) throws Exception {
        var meaning = ReadOnlyRequestMeaning.compile(request);
        var catalog = NativeReadOnlyAnswerProposal.catalog(meaning, observed);
        var schema = JSON.readTree(NativeReadOnlyAnswerProposal.responseSchema(meaning, observed));
        var candidate = JSON.createObjectNode().put("version", schema.path("properties").path("version").path("enum").get(0).intValue())
                .put("catalogId", catalog.id()).put("targetId", catalog.targets().getFirst().id());
        var decisions = candidate.putObject("interpretationDecisions");
        var results = candidate.putArray("results");
        for (var slot : catalog.requirements().slots()) {
            if (slot.role() == Role.UNKNOWN) decisions.put(slot.id(), "unsupported");
            var entry = results.addObject().put("slotId", slot.id());
            entry.putNull("formulaId");
            var bindings = entry.putArray("bindingIds");
            entry.putNull("precisionId");
            if (!slot.calculation()) continue;
            var choices = catalog.formulas().stream().filter(formula -> switch (slot.role()) {
                case SOURCE_DEFINITION -> formula.origin() == ReadOnlyAnswerProposal.FormulaOrigin.SOURCE;
                case IMPLEMENTATION -> formula.origin() == ReadOnlyAnswerProposal.FormulaOrigin.IMPLEMENTATION;
                case REQUEST_EXPRESSION -> formula.origin() == ReadOnlyAnswerProposal.FormulaOrigin.REQUEST
                        && formula.span().start() >= slot.start() && formula.span().end() <= slot.end();
                default -> false;
            }).filter(formula -> slot.sourcePath().isEmpty()
                    || observed.get(Integer.parseInt(formula.sourceId().substring(1))).path().equals(slot.sourcePath())).toList();
            if (choices.isEmpty()) continue;
            var selected = choices.getFirst();
            entry.put("formulaId", selected.id());
            catalog.bindings().stream().filter(binding -> binding.slotId().equals(slot.id())
                            && binding.formulaId().equals(selected.id()))
                    .forEach(binding -> bindings.add(binding.id()));
            catalog.precisions().stream().filter(precision -> precision.slotId().equals(slot.id())).findFirst()
                    .ifPresent(precision -> entry.put("precisionId", precision.id()));
        }
        return new Fixture(meaning, List.copyOf(observed), catalog, schema, candidate);
    }

    private static List<AnswerResultRequirements.Slot> slots(Fixture fixture, Role role) {
        return fixture.catalog().requirements().slots().stream().filter(slot -> slot.role() == role).toList();
    }

    private static boolean numerical(AnswerResultRequirements.Slot slot) {
        return slot.calculation() || slot.role() == Role.COMPARISON;
    }

    private static String fragment(Fixture fixture, AnswerResultRequirements.Slot slot) {
        return fixture.meaning().request().substring(slot.start(), slot.end());
    }

    private static ObjectNode result(ObjectNode candidate, String slotId) {
        for (JsonNode entry : candidate.path("results")) {
            if (entry.path("slotId").asText().equals(slotId)) return (ObjectNode) entry;
        }
        throw new AssertionError("Missing authored result " + slotId);
    }

    private static ReadOnlyWebDiagnosticResult assess(Fixture fixture) {
        return NativeReadOnlyAnswerProposal.assess(fixture.candidate().toString(), fixture.meaning(), fixture.sources());
    }

    private static void complete(ReadOnlyWebDiagnosticResult answer) {
        assertTrue(answer.components().requestCovered(), answer.answer());
        assertTrue(answer.components().complete(), answer.answer());
        assertTrue(answer.components().deliveredIntact(answer.answer()));
        assertTrue(answer.answer().endsWith(ReadOnlyAnswerComponents.FOOTER));
    }

    private static void incomplete(ReadOnlyWebDiagnosticResult answer) {
        assertFalse(answer.components().complete(), answer.answer());
        assertFalse(answer.components().requestCovered(), answer.answer());
    }

    private static List<Source> sources() {
        return List.of(source("guide.md", GUIDE), source("index.html", HTML), source("engine.js", SCRIPT),
                source("spare.js", "console.log('unlinked source with different behavior');\n"));
    }

    private static Source source(String path, String text) {
        return new Source(path, text, UUID.nameUUIDFromBytes(("meaning-acceptance\n" + path).getBytes(StandardCharsets.UTF_8)).toString(),
                1, ReadOnlyWebDiagnosticResult.textHash(text), SOURCE_PROJECTION_VERSION);
    }

    private static List<Source> replace(List<Source> observed, String path, String text) {
        return observed.stream().map(item -> item.path().equals(path) ? source(path, text) : item).toList();
    }

    private record Fixture(ReadOnlyRequestMeaning meaning, List<Source> sources,
                           NativeReadOnlyAnswerProposal.Catalog catalog, JsonNode schema, ObjectNode candidate) {}
}
