package dev.talos.runtime.verification;

import dev.talos.runtime.task.AnswerResultRequirements;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;
import static dev.talos.runtime.verification.NativeReadOnlyAnswerProposal.RelationKind;
import static dev.talos.runtime.verification.ReadOnlyAnswerComponents.Support;
import static dev.talos.runtime.verification.ReadOnlyAnswerProposal.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeInterpretationAssessmentTest {
    private static final String REQUEST = "Read guide.md, index.html, engine.js and spare.js. "
            + "Please explain why the Compute button fails.";
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

    @Test
    void aSupplementDoesNotReplaceTheOriginalUnknownComponent() {
        var fixture = fixture(REQUEST);
        var result = assess(fixture, List.of(fixture.interpretation()));

        assertEquals(List.of("UNKNOWN", "OBSERVATION"), result.components().components().stream()
                .map(ReadOnlyAnswerComponents.Component::kind).toList());
        assertFalse(result.components().components().getFirst().met());
        var observation = result.components().components().getLast();
        assertTrue(observation.met());
        assertFalse(observation.references().isEmpty());
        assertEquals("MODEL_INTERPRETED_REGION_SOURCE_RELATION", observation.reason());
        assertEquals(64, observation.derivationHash().length());
        assertFalse(result.components().requestCovered());
        assertFalse(result.components().complete());
        assertEquals(ReadOnlyAnswerComponents.CoverageOrigin.NATIVE_LEDGER_WITH_INTERPRETATIONS,
                result.components().coverageOrigin());
        assertTrue(result.answer().contains("Its relevance to that region was not established."));
        assertTrue(result.answer().contains("not an observed browser result"));
        assertTrue(result.answer().contains("additional requested answer component remains unresolved"));
    }

    @ParameterizedTest
    @EnumSource(RelationKind.class)
    void eachCatalogTypeRendersOnlyItsBoundSourceRelation(RelationKind kind) {
        var fixture = fixture(REQUEST, sources(), kind);
        var result = assess(fixture, List.of(fixture.interpretation()));
        var observation = result.components().components().getLast();

        assertEquals("OBSERVATION", observation.kind());
        assertTrue(observation.met());
        assertFalse(result.components().components().getFirst().met());
        assertFalse(result.components().requestCovered());
        assertFalse(result.components().complete());
        assertTrue(observation.text().contains("Its relevance to that region was not established."));
        switch (kind) {
            case DIAGNOSIS -> {
                assertTrue(observation.text().startsWith("An additional diagnosis source observation"));
                assertTrue(observation.text().contains("lookup would be null"));
                assertFalse(observation.text().contains("should be replaced"));
            }
            case SELECTOR -> {
                assertTrue(observation.text().startsWith("An additional selector correction source observation"));
                assertTrue(observation.text().contains("`#missing` should be replaced by `#compute`"));
                assertFalse(observation.text().contains("lookup would be null"));
            }
            case SCRIPT_DISTINCTION -> {
                assertTrue(observation.text().startsWith("An additional script distinction source observation"));
                assertTrue(observation.text().contains("`index.html` links `engine.js`"));
                assertTrue(observation.text().contains("`spare.js` was also read"));
                assertTrue(observation.text().contains("Its execution elsewhere was not assessed"));
                assertFalse(observation.text().contains("should be replaced"));
            }
        }
        for (var reference : observation.references()) {
            var retained = fixture.sources().stream()
                    .filter(source -> source.observationId().equals(reference.observationId())).findFirst().orElseThrow();
            assertEquals(ReadOnlyWebDiagnosticResult.textHash(retained.text()), reference.textHash());
            assertTrue(reference.start() >= 0 && reference.end() <= retained.text().length());
        }
    }

    @Test
    void multipleSelectionsForOneRegionAreRejected() {
        var fixture = fixture(REQUEST);
        var selected = fixture.catalog().relations().stream()
                .filter(relation -> relation.regionId().equals(fixture.interpretation().regionId()))
                .map(relation -> interpretation(fixture.catalog(), relation)).toList();
        assertEquals(3, selected.size());

        assertEquals("NATIVE_INTERPRETATION_DUPLICATE", assertThrows(Rejected.class,
                () -> assess(fixture, selected)).reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Describe the Compute button appearance.",
            "Please explain the Compute button failure and provide both documented and implementation results."})
    void anInterpretedRelationDoesNotEstablishTheMeaningOfAnUnknownRegion(String wording) {
        var fixture = fixture(wording);
        var result = assess(fixture, List.of(fixture.interpretation()));

        assertFalse(result.components().requestCovered());
        assertFalse(result.components().complete());
        assertTrue(result.components().components().stream().anyMatch(value -> value.kind().equals("UNKNOWN") && !value.met()));
        assertTrue(result.answer().contains("relevance to that region was not established"));
        assertTrue(result.answer().contains("A complete interpretation of the request was not established."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Do not diagnose the Compute button.", "\"Explain why the Compute button fails\".",
            "Please show the documented result and the implementation result, then explain their difference."})
    void excludedRegionsDoNotAcquireRelationsThroughForgedSelections(String request) {
        var sources = sources();
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources);
        assertTrue(catalog.relations().isEmpty());
        var region = catalog.requirements().slots().stream()
                .filter(value -> value.role() == AnswerResultRequirements.Role.UNKNOWN).findFirst().orElseThrow();
        var forged = new ReadOnlyAnswerInterpretation(catalog.requirements().requestSha256(), catalog.id(),
                region.id(), "forged", catalog.targets().getFirst().id());
        var fixture = new Fixture(request, sources, catalog, forged);

        assertRejected(fixture, forged, "NATIVE_INTERPRETATION_RELATION_INVALID");
    }

    @Test
    void absentInterpretationsRemainExplicitMissingDecisions() {
        var fixture = fixture(REQUEST);
        var result = assess(fixture, List.of());

        assertEquals(ReadOnlyAnswerComponents.CoverageOrigin.NATIVE_LEDGER_WITH_INTERPRETATIONS, result.components().coverageOrigin());
        assertEquals(ReadOnlyAnswerInterpretation.Decision.MISSING,
                result.components().components().getFirst().interpretationDecision());
        assertEquals("NATIVE_INTERPRETATION_DECISION_MISSING", result.components().components().getFirst().reason());
        assertFalse(result.components().complete());
        assertFalse(result.components().components().stream().anyMatch(value -> value.kind().equals("OBSERVATION")));
    }

    @ParameterizedTest
    @EnumSource(ReadOnlyAnswerInterpretation.Decision.class)
    void everyDecisionRemainsUnmetAndIsIdentifiedSeparately(ReadOnlyAnswerInterpretation.Decision decision) {
        var fixture = fixture(REQUEST);
        var selected = fixture.interpretation();
        var supplied = new ReadOnlyAnswerInterpretation(selected.requestSha256(), selected.catalogId(), selected.regionId(),
                decision == ReadOnlyAnswerInterpretation.Decision.SELECTED ? selected.relationId() : "", selected.targetId(), decision);
        var result = assess(fixture, List.of(supplied));
        var requirement = result.components().components().getFirst();

        assertEquals(decision, requirement.interpretationDecision());
        assertEquals(Support.UNSUPPORTED, requirement.support());
        assertEquals(64, requirement.derivationHash().length());
        assertFalse(result.components().complete());
        assertFalse(result.components().requestCovered());
        assertEquals(decision == ReadOnlyAnswerInterpretation.Decision.SELECTED ? 1 : 0,
                result.components().components().stream().filter(value -> value.kind().equals("OBSERVATION")).count());
        if (decision != ReadOnlyAnswerInterpretation.Decision.SELECTED)
            assertFalse(result.answer().contains("source observations were selected"));
    }

    @ParameterizedTest
    @EnumSource(value = ReadOnlyAnswerInterpretation.Decision.class, names = {"UNSUPPORTED", "MISSING"})
    void nonselectedDecisionsStillRequireAuthenticCatalogAndOwner(ReadOnlyAnswerInterpretation.Decision decision) {
        var fixture = fixture(REQUEST);
        var original = fixture.interpretation();
        assertRejected(fixture, new ReadOnlyAnswerInterpretation(original.requestSha256(), "0".repeat(64),
                original.regionId(), "", original.targetId(), decision), "NATIVE_INTERPRETATION_IDENTITY_MISMATCH");
        assertRejected(fixture, new ReadOnlyAnswerInterpretation(original.requestSha256(), original.catalogId(),
                original.regionId(), "", "invented", decision), "NATIVE_INTERPRETATION_TARGET_MISMATCH");
    }

    @Test
    void conflictingSelectedAndUnsupportedDecisionsForOneRegionAreRejected() {
        var fixture = fixture(REQUEST);
        var original = fixture.interpretation();
        var unsupported = new ReadOnlyAnswerInterpretation(original.requestSha256(), original.catalogId(),
                original.regionId(), "", original.targetId(), ReadOnlyAnswerInterpretation.Decision.UNSUPPORTED);
        assertEquals("NATIVE_INTERPRETATION_DUPLICATE", assertThrows(Rejected.class,
                () -> assess(fixture, List.of(original, unsupported))).reason);
    }

    @Test
    void identicalCheckedRelationsHaveRegionIndependentDisplayGroupsAndDistinctDerivations() {
        var fixture = fixture("Describe the Compute button appearance. Describe the layout. Describe the styling.");
        var selected = fixture.catalog().relations().stream().filter(value -> value.kind() == RelationKind.DIAGNOSIS)
                .map(value -> interpretation(fixture.catalog(), value)).toList();
        assertEquals(3, selected.size());
        var result = assess(fixture, selected);
        var observations = result.components().components().stream().filter(value -> value.kind().equals("OBSERVATION")).toList();

        assertEquals(3, observations.size());
        assertEquals(1, observations.stream().map(value -> value.observationPresentation().evidenceGroupId()).distinct().count());
        assertEquals(3, observations.stream().map(ReadOnlyAnswerComponents.Component::derivationHash).distinct().count());
        assertEquals(3, result.components().components().stream().filter(value -> !value.met()).count());
        assertEquals(1, result.answer().split("lookup would be null", -1).length - 1);
        assertFalse(result.components().complete());
    }

    @Test
    void aKnownRoleCannotBeReplacedByAnInterpretation() {
        var fixture = fixture("Explain why the Compute button fails. Describe the Compute button appearance.");
        var known = fixture.catalog().requirements().slots().stream()
                .filter(value -> value.role() == AnswerResultRequirements.Role.DIAGNOSIS).findFirst().orElseThrow();
        var original = fixture.interpretation();
        var forged = new ReadOnlyAnswerInterpretation(original.requestSha256(), original.catalogId(), known.id(),
                original.relationId(), original.targetId());

        assertRejected(fixture, forged, "NATIVE_INTERPRETATION_REGION_INVALID");
    }

    @Test
    void duplicateInterpretationsCannotAddRepeatedEvidence() {
        var fixture = fixture(REQUEST);

        var rejected = assertThrows(Rejected.class,
                () -> assess(fixture, List.of(fixture.interpretation(), fixture.interpretation())));
        assertEquals("NATIVE_INTERPRETATION_DUPLICATE", rejected.reason);
    }

    @Test
    void staleRequestAndCatalogIdentitiesAreRejectedIndependently() {
        var fixture = fixture(REQUEST);
        var original = fixture.interpretation();
        assertRejected(fixture, new ReadOnlyAnswerInterpretation("0".repeat(64), original.catalogId(), original.regionId(),
                original.relationId(), original.targetId()), "NATIVE_INTERPRETATION_IDENTITY_MISMATCH");
        assertRejected(fixture, new ReadOnlyAnswerInterpretation(original.requestSha256(), "0".repeat(64), original.regionId(),
                original.relationId(), original.targetId()), "NATIVE_INTERPRETATION_IDENTITY_MISMATCH");
    }

    @Test
    void aRelationAndItsTargetCannotBeSubstituted() {
        var fixture = fixture(REQUEST);
        var original = fixture.interpretation();
        assertRejected(fixture, new ReadOnlyAnswerInterpretation(original.requestSha256(), original.catalogId(), original.regionId(),
                "invented", original.targetId()), "NATIVE_INTERPRETATION_RELATION_INVALID");
        assertRejected(fixture, new ReadOnlyAnswerInterpretation(original.requestSha256(), original.catalogId(), original.regionId(),
                original.relationId(), "invented"), "NATIVE_INTERPRETATION_TARGET_MISMATCH");
    }

    @Test
    void changedRetainedSourcesInvalidateTheInterpretationCatalog() {
        var fixture = fixture(REQUEST);
        var changed = new ArrayList<>(fixture.sources());
        changed.set(0, source("guide.md", changed.getFirst().text() + "Another retained sentence.\n"));
        var binding = binding(fixture, List.of(fixture.interpretation()));

        var rejected = assertThrows(Rejected.class,
                () -> ProposedWebDiagnosticAnswer.assessNative(REQUEST, binding, changed));
        assertEquals("NATIVE_INTERPRETATION_IDENTITY_MISMATCH", rejected.reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "script", "observation", "bodyHash"})
    void changedRelationSourceContentOrObservationInvalidatesEveryType(String changedField) {
        for (var kind : RelationKind.values()) {
            var fixture = fixture(REQUEST, sources(), kind);
            var changed = new ArrayList<>(fixture.sources());
            if (changedField.equals("html")) {
                changed.set(1, source("index.html", HTML.replace("<body>", "<body class=\"changed\">")));
            } else if (changedField.equals("script")) {
                changed.set(2, source("engine.js", SCRIPT.replace("#missing", "#compute")));
            } else {
                var original = changed.get(2);
                changed.set(2, new Source(original.path(), original.text(),
                        changedField.equals("observation") ? UUID.nameUUIDFromBytes("changed observation"
                                .getBytes(StandardCharsets.UTF_8)).toString() : original.originId(),
                        original.ordinal(), changedField.equals("bodyHash") ? "0".repeat(64) : original.bodyHash(),
                        original.projectionVersion()));
            }

            var rejected = assertThrows(Rejected.class, () -> ProposedWebDiagnosticAnswer.assessNative(REQUEST,
                    binding(fixture, List.of(fixture.interpretation())), changed));
            assertEquals("NATIVE_INTERPRETATION_IDENTITY_MISMATCH", rejected.reason, kind.name());
        }
    }

    @Test
    void aRealSecondTargetCannotOwnAnotherTargetsSelectedRelation() {
        String request = "Describe the Compute button appearance. Describe the Reset button appearance.";
        var changed = new ArrayList<>(sources());
        changed.set(1, source("index.html", HTML.replace("<p id=\"result\">",
                "<button id=\"reset\">Reset</button><p id=\"result\">")));
        var fixture = fixture(request, changed, RelationKind.DIAGNOSIS);
        var second = fixture.catalog().targets().stream().filter(target -> target.buttonId().equals("reset"))
                .findFirst().orElseThrow();
        var original = binding(fixture, List.of(fixture.interpretation()));
        var proposal = new Proposal(new Target(second.buttonId(), request.substring(second.requestSpan().start(),
                second.requestSpan().end())), original.proposal().results());
        var rebound = new NativeProposalBinding(proposal, second.requestSpan(), original.results(),
                original.requestCovered(), original.interpretations());

        var rejected = assertThrows(Rejected.class,
                () -> ProposedWebDiagnosticAnswer.assessNative(request, rebound, changed));
        assertEquals("NATIVE_INTERPRETATION_TARGET_MISMATCH", rejected.reason);
    }

    @Test
    void missingOrAmbiguousOtherScriptsCannotBeSelectedAsDistinctionEvidence() {
        var missing = new ArrayList<>(sources());
        missing.removeLast();
        var ambiguous = new ArrayList<>(sources());
        ambiguous.add(source("third.js", "const another = 2;\n"));
        for (var retained : List.of(missing, ambiguous)) {
            var fixture = fixture(REQUEST, retained, RelationKind.DIAGNOSIS);
            assertTrue(fixture.catalog().relations().stream()
                    .noneMatch(relation -> relation.kind() == RelationKind.SCRIPT_DISTINCTION));
            var original = fixture.interpretation();
            var forged = new ReadOnlyAnswerInterpretation(original.requestSha256(), original.catalogId(),
                    original.regionId(), "unavailable-distinction", original.targetId());

            assertRejected(fixture, forged, "NATIVE_INTERPRETATION_RELATION_INVALID");
        }
    }

    @Test
    void theSupplementLimitIsEnforcedBeforeAssessment() {
        var fixture = fixture(REQUEST);
        var selected = java.util.Collections.nCopies(9, fixture.interpretation());

        var rejected = assertThrows(Rejected.class, () -> binding(fixture, selected));
        assertEquals("NATIVE_INTERPRETATION_BOUND", rejected.reason);
    }

    @Test
    void theSelectedRelationMustBelongToTheProposalsTarget() {
        var fixture = fixture(REQUEST);
        var original = binding(fixture, List.of(fixture.interpretation()));
        var proposal = new Proposal(new Target("another-button", original.proposal().target().requestQuote()),
                original.proposal().results());
        var changed = new NativeProposalBinding(proposal, original.targetSpan(), original.results(),
                original.requestCovered(), original.interpretations());

        var rejected = assertThrows(Rejected.class,
                () -> ProposedWebDiagnosticAnswer.assessNative(REQUEST, changed, fixture.sources()));
        assertEquals("NATIVE_INTERPRETATION_TARGET_MISMATCH", rejected.reason);
    }

    @Test
    void aMissingKnownResultRemainsUnmetWhenAdditionalEvidenceIsSupported() {
        var fixture = fixture("Explain why the Compute button fails. Describe the Compute button appearance.");
        var original = binding(fixture, List.of(fixture.interpretation()));
        var bounds = new ArrayList<>(original.results());
        var known = bounds.getFirst();
        assertEquals(AnswerResultRequirements.Role.DIAGNOSIS, known.slot().role());
        bounds.set(0, new NativeProposalBinding.BoundResult(known.slotId(), known.slot(), null, List.of(), null,
                "NATIVE_REQUIRED_RESULT_MISSING"));
        var changed = new NativeProposalBinding(original.proposal(), original.targetSpan(), bounds,
                original.requestCovered(), original.interpretations());

        var result = ProposedWebDiagnosticAnswer.assessNative(fixture.request(), changed, fixture.sources());

        assertFalse(result.components().components().getFirst().met());
        assertEquals("NATIVE_REQUIRED_RESULT_MISSING", result.components().components().getFirst().reason());
        assertTrue(result.components().components().getLast().met());
        assertEquals("OBSERVATION", result.components().components().getLast().kind());
        assertFalse(result.components().requestCovered());
        assertFalse(result.components().complete());
    }

    private static void assertRejected(Fixture fixture, ReadOnlyAnswerInterpretation interpretation, String reason) {
        assertEquals(reason, assertThrows(Rejected.class, () -> assess(fixture, List.of(interpretation))).reason);
    }

    private static ReadOnlyWebDiagnosticResult assess(Fixture fixture, List<ReadOnlyAnswerInterpretation> interpretations) {
        return ProposedWebDiagnosticAnswer.assessNative(fixture.request(), binding(fixture, interpretations), fixture.sources());
    }

    private static NativeProposalBinding binding(Fixture fixture, List<ReadOnlyAnswerInterpretation> interpretations) {
        var target = fixture.catalog().targets().getFirst();
        List<Result> results = new ArrayList<>();
        List<NativeProposalBinding.BoundResult> bounds = new ArrayList<>();
        for (var slot : fixture.catalog().requirements().slots()) {
            Kind kind = slot.role() == AnswerResultRequirements.Role.DIAGNOSIS ? Kind.DIAGNOSIS : Kind.UNKNOWN;
            results.add(new Result(kind, fixture.request().substring(slot.start(), slot.end()), null, List.of()));
            bounds.add(new NativeProposalBinding.BoundResult(slot.id(), slot, null, List.of(), null, ""));
        }
        var proposal = new Proposal(new Target(target.buttonId(), fixture.request().substring(target.requestSpan().start(),
                target.requestSpan().end())), results);
        return new NativeProposalBinding(proposal, target.requestSpan(), bounds, true, interpretations);
    }

    private static Fixture fixture(String request) {
        return fixture(request, sources(), RelationKind.DIAGNOSIS);
    }

    private static Fixture fixture(String request, List<Source> sources, RelationKind kind) {
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources);
        var relation = catalog.relations().stream().filter(value -> value.kind() == kind).findFirst().orElseThrow();
        return new Fixture(request, sources, catalog, interpretation(catalog, relation));
    }

    private static ReadOnlyAnswerInterpretation interpretation(NativeReadOnlyAnswerProposal.Catalog catalog,
                                                               NativeReadOnlyAnswerProposal.SourceRelation relation) {
        return new ReadOnlyAnswerInterpretation(catalog.requirements().requestSha256(), catalog.id(),
                relation.regionId(), relation.id(), relation.targetId());
    }

    private static List<Source> sources() {
        return List.of(source("guide.md", "The Compute button uses volume divided by elapsed. index.html loads engine.js.\n"),
                source("index.html", HTML), source("engine.js", SCRIPT), source("spare.js", "const unused = 1;\n"));
    }

    private static Source source(String path, String text) {
        return new Source(path, text, UUID.nameUUIDFromBytes((path + "\n" + text).getBytes(StandardCharsets.UTF_8)).toString(),
                1, ReadOnlyWebDiagnosticResult.textHash(text), SOURCE_PROJECTION_VERSION);
    }

    private record Fixture(String request, List<Source> sources, NativeReadOnlyAnswerProposal.Catalog catalog,
                           ReadOnlyAnswerInterpretation interpretation) {}
}
