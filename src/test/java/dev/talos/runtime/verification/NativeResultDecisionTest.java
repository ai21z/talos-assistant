package dev.talos.runtime.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.task.AnswerRequirements;
import dev.talos.runtime.task.AnswerResultRequirements;
import dev.talos.runtime.task.ReadOnlyRequestMeaning;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dev.talos.runtime.task.AnswerResultRequirements.Role;
import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;
import static dev.talos.runtime.verification.ReadOnlyAnswerProposal.FormulaOrigin;
import static org.junit.jupiter.api.Assertions.*;

/** Authored decisions are assessed against observed fixtures without model generation. */
class NativeResultDecisionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String READ = "Read guide.md, index.html, engine.js and spare.js. ";
    private static final String DIAGNOSIS = "Explain why the Compute button fails. ";
    private static final String LINKAGE = "Describe how engine.js differs from spare.js in their HTML linkage. ";
    private static final String EXPRESSION = "Evaluate total / count for total = 21 and count = 4 to 3 decimal places. ";
    private static final String REQUEST = READ + DIAGNOSIS + "Give the correct selector. "
            + "For volume 24 L and elapsed 6 s, give the flow rate according to guide.md "
            + "and the mathematical value of the expression in engine.js separately. Explain any disagreement. "
            + LINKAGE + EXPRESSION + "Do not edit files or claim tests were run.";
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
    private static final Set<String> SCHEMA_KEYWORDS = Set.of("type", "enum", "oneOf", "properties", "required",
            "additionalProperties", "items", "minItems", "maxItems");

    @Test
    void everyImmutableSlotIsARequiredClosedObjectKeyWithDisjointChoices() throws Exception {
        var fixture = fixture(REQUEST + " Explain the security risks.", sources());
        assertEquals(Set.of(Role.values()), roles(fixture));
        JsonNode results = fixture.schema().at("/properties/results");
        Set<String> ids = new HashSet<>(fixture.catalog().requirements().slots().stream()
                .map(AnswerResultRequirements.Slot::id).toList());
        assertEquals("object", results.path("type").asText());
        assertEquals(ids, names(results.path("properties")));
        assertEquals(ids, strings(results.path("required")));
        assertEquals(ids.size(), results.path("required").size());
        assertFalse(results.path("additionalProperties").booleanValue());
        inspect(fixture.schema());
        assertEquals(8, fixture.schema().at("/properties/version/enum/0").intValue());
        assertEquals(fixture.catalog().id(), fixture.schema().at("/properties/catalogId/enum/0").asText());
        assertTrue(accepts(fixture.schema(), fixture.candidate()));
        for (var slot : fixture.catalog().requirements().slots()) {
            JsonNode choice = results.path("properties").get(slot.id());
            assertTrue(accepts(choice, JSON.getNodeFactory().textNode("unsupported")));
            assertFalse(accepts(choice, JSON.getNodeFactory().nullNode()));
            if (slot.role() == Role.UNKNOWN) {
                assertEquals(Set.of("unsupported"), strings(choice.path("enum")));
                assertFalse(choice.has("oneOf"));
            } else {
                assertEquals(2, choice.path("oneOf").size());
                JsonNode selection = choice.path("oneOf").get(1);
                assertEquals(Set.of("formulaId", "bindingIds", "precisionId"), names(selection.path("properties")));
                assertFalse(selection.path("properties").has("slotId"));
            }
        }
        String instructions = NativeReadOnlyAnswerProposal.decisionInstructions(fixture.meaning(), fixture.sources());
        assertTrue(instructions.contains("version=8\n"));
        assertTrue(instructions.contains("catalogId=" + fixture.catalog().id()));
        assertTrue(instructions.contains("results is an object keyed by every required slot ID"));
        assertTrue(instructions.contains("An unsupported comparison remains unresolved"));
    }

    @Test
    void selectedKnownRolesRetainVerifiedValuesAndComparison() throws Exception {
        var fixture = fixture();
        assertEquals(Set.of(Role.DIAGNOSIS, Role.SELECTOR, Role.SOURCE_DEFINITION, Role.IMPLEMENTATION,
                Role.REQUEST_EXPRESSION, Role.COMPARISON, Role.SCRIPT_DISTINCTION), roles(fixture));
        var answer = assess(fixture, fixture.candidate());
        complete(answer);
        assertTrue(accepts(fixture.schema(), fixture.candidate()));
        assertTrue(answer.answer().contains("`4.00`"));
        assertTrue(answer.answer().contains("`30.00`"));
        assertTrue(answer.answer().contains("`5.250`"));
        assertTrue(answer.answer().contains("`#missing` should be replaced by `#compute`"));
        assertTrue(answer.answer().contains("`index.html` links `engine.js`"));
        assertTrue(component(answer, slot(fixture, Role.COMPARISON).id()).text().contains("disagree"));
    }

    @Test
    void unsupportedIsHonoredForEveryKnownRoleDespiteAvailableEvidence() throws Exception {
        var fixture = fixture();
        var baseline = assess(fixture, fixture.candidate());
        complete(baseline);
        for (var slot : fixture.catalog().requirements().slots()) {
            var candidate = fixture.candidate().deepCopy();
            results(candidate).put(slot.id(), "unsupported");
            assertTrue(accepts(fixture.schema(), candidate), slot.role().name());
            var answer = assess(fixture, candidate);
            unmet(answer, slot.id(), "NATIVE_RESULT_UNSUPPORTED");
            preserved(fixture, baseline, answer, slot);
            if (slot.role() == Role.DIAGNOSIS)
                assertFalse(component(answer, slot.id()).text().contains("lookup would be null"));
            if (slot.role() == Role.SELECTOR)
                assertFalse(component(answer, slot.id()).text().contains("should be replaced by"));
            if (slot.role() == Role.COMPARISON) {
                assertFalse(component(answer, slot.id()).text().contains("disagree"));
                assertTrue(slot.peerSlotIds().stream().allMatch(id -> component(answer, id).met()));
            }
        }
    }

    @Test
    void missingKeysFailTheSchemaAndRemainMissingWithoutDiscardingIndependentSiblings() throws Exception {
        var fixture = fixture();
        var baseline = assess(fixture, fixture.candidate());
        complete(baseline);
        for (var slot : fixture.catalog().requirements().slots()) {
            var candidate = fixture.candidate().deepCopy();
            results(candidate).remove(slot.id());
            assertFalse(accepts(fixture.schema(), candidate), slot.role().name());
            var answer = assess(fixture, candidate);
            unmet(answer, slot.id(), "NATIVE_REQUIRED_RESULT_MISSING");
            preserved(fixture, baseline, answer, slot);
        }
    }

    @Test
    void allUnsupportedDecisionsAccountForEveryResultWithoutEstablishingCoverage() throws Exception {
        var fixture = fixture();
        var candidate = fixture.candidate().deepCopy();
        fixture.catalog().requirements().slots().forEach(slot -> results(candidate).put(slot.id(), "unsupported"));
        assertTrue(accepts(fixture.schema(), candidate));
        var answer = assess(fixture, candidate);
        incomplete(answer);
        assertEquals(fixture.catalog().requirements().slots().size(), answer.components().components().size());
        assertTrue(answer.components().components().stream().allMatch(item -> !item.met()
                && item.reason().equals("NATIVE_RESULT_UNSUPPORTED")));
        assertFalse(answer.answer().contains("`4.00`"));
        assertFalse(answer.answer().contains("should be replaced by"));
    }

    @Test
    void unknownDecisionsRemainSeparateFromRequiredResultAndReadAccounting() throws Exception {
        var fixture = fixture(REQUEST.replace(LINKAGE, "Explain how engine.js differs from spare.js. "), sources());
        var unknown = slot(fixture, Role.UNKNOWN);
        assertEquals(4, fixture.meaning().readReferences().size());
        assertTrue(fixture.catalog().relations().isEmpty());
        assertEquals("unsupported", results(fixture.candidate()).path(unknown.id()).asText());
        assertEquals(Set.of(unknown.id()), names(fixture.candidate().path("interpretationDecisions")));
        var baseline = assess(fixture, fixture.candidate());
        unmet(baseline, unknown.id(), "NATIVE_RESULT_UNSUPPORTED");
        assertEquals(ReadOnlyAnswerInterpretation.Decision.UNSUPPORTED, component(baseline, unknown.id()).interpretationDecision());
        assertTrue(component(baseline, slot(fixture, Role.SOURCE_DEFINITION).id()).met());
        assertTrue(baseline.components().components().stream().noneMatch(item -> item.kind().equals("OBSERVATION")));

        var missingDecision = fixture.candidate().deepCopy();
        ((ObjectNode) missingDecision.path("interpretationDecisions")).remove(unknown.id());
        assertFalse(accepts(fixture.schema(), missingDecision));
        var missingAnswer = assess(fixture, missingDecision);
        unmet(missingAnswer, unknown.id(), "NATIVE_RESULT_UNSUPPORTED");
        assertEquals(ReadOnlyAnswerInterpretation.Decision.MISSING, component(missingAnswer, unknown.id()).interpretationDecision());
        preserved(fixture, baseline, missingAnswer, unknown);

        var missingResult = fixture.candidate().deepCopy();
        results(missingResult).remove(unknown.id());
        unmet(assess(fixture, missingResult), unknown.id(), "NATIVE_REQUIRED_RESULT_MISSING");
        var selectedUnknown = fixture.candidate().deepCopy();
        results(selectedUnknown).set(unknown.id(), nullSelection());
        assertFalse(accepts(fixture.schema(), selectedUnknown));
        unmet(assess(fixture, selectedUnknown), unknown.id(), "NATIVE_UNKNOWN_RESULT_UNSUPPORTED");
        var inventedObservation = fixture.candidate().deepCopy();
        ((ObjectNode) inventedObservation.path("interpretationDecisions")).put(unknown.id(), "d0");
        assertFalse(accepts(fixture.schema(), inventedObservation));
        global(assess(fixture, inventedObservation), "NATIVE_INTERPRETATION_RELATION_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "[]", "\"selected\"", "{}"})
    void malformedValuesCannotBeUsedAsImplicitSelectionOrUnsupported(String value) throws Exception {
        var fixture = fixture();
        var changed = slot(fixture, Role.DIAGNOSIS);
        var candidate = fixture.candidate().deepCopy();
        results(candidate).set(changed.id(), JSON.readTree(value));
        assertFalse(accepts(fixture.schema(), candidate));
        var answer = assess(fixture, candidate);
        unmet(answer, changed.id(), "NATIVE_FIELDS_INVALID");
        preserved(fixture, assess(fixture, fixture.candidate()), answer, changed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"permission", "complete", "answer", "tools", "slotId"})
    void extraFieldsCannotAddAuthorityOrReassignTheOwningSlot(String field) throws Exception {
        var fixture = fixture();
        var root = fixture.candidate().deepCopy();
        root.put(field, "forbidden");
        assertFalse(accepts(fixture.schema(), root));
        global(assess(fixture, root), "NATIVE_FIELDS_INVALID");
        var candidate = fixture.candidate().deepCopy();
        var changed = slot(fixture, Role.SELECTOR);
        selection(candidate, changed.id()).put(field, "forbidden");
        assertFalse(accepts(fixture.schema(), candidate));
        var answer = assess(fixture, candidate);
        unmet(answer, changed.id(), "NATIVE_FIELDS_INVALID");
        preserved(fixture, assess(fixture, fixture.candidate()), answer, changed);
    }

    @Test
    void duplicateRawKeysAreRejectedBeforeJsonCanOverwriteTheOriginalDecision() throws Exception {
        var fixture = fixture();
        String raw = fixture.candidate().toString();
        String resultId = slot(fixture, Role.DIAGNOSIS).id();
        List<String> duplicates = List.of(
                raw.replace("\"results\":{", "\"results\":{\"" + resultId + "\":\"unsupported\","),
                raw.replace("\"version\":8", "\"version\":8,\"version\":8"),
                raw.replace("\"formulaId\":null", "\"formulaId\":null,\"formulaId\":null"));
        for (String duplicate : duplicates) {
            assertNotEquals(raw, duplicate);
            global(assess(fixture, duplicate), "NATIVE_PROPOSAL_FORMAT_INVALID");
        }
    }

    @Test
    void unknownKeysAndOldArrayResultsCannotBeAcceptedUnderVersionEight() throws Exception {
        var fixture = fixture();
        var extra = fixture.candidate().deepCopy();
        results(extra).put("invented-result", "unsupported");
        assertFalse(accepts(fixture.schema(), extra));
        global(assess(fixture, extra), "NATIVE_SLOT_INVALID");
        for (JsonNode value : List.of(JSON.createArrayNode(), JSON.getNodeFactory().nullNode())) {
            var candidate = fixture.candidate().deepCopy();
            candidate.set("results", value);
            assertFalse(accepts(fixture.schema(), candidate));
            global(assess(fixture, candidate), "NATIVE_RESULT_BOUND");
        }
        var missingRoot = fixture.candidate().deepCopy();
        missingRoot.remove("results");
        global(assess(fixture, missingRoot), "NATIVE_FIELDS_INVALID");
    }

    @Test
    void wrongFormulaRolesAndSourcesPreserveIndependentDiagnosisAndSelector() throws Exception {
        var observed = new ArrayList<>(sources());
        observed.add(source("other.md", "The flow rate is volume multiplied by elapsed.\n"));
        var fixture = fixture(REQUEST, observed);
        var baseline = assess(fixture, fixture.candidate());
        complete(baseline);
        for (Role role : List.of(Role.SOURCE_DEFINITION, Role.IMPLEMENTATION)) {
            var changed = slot(fixture, role);
            var other = slot(fixture, role == Role.SOURCE_DEFINITION ? Role.IMPLEMENTATION : Role.SOURCE_DEFINITION);
            var candidate = fixture.candidate().deepCopy();
            selection(candidate, changed.id()).set("formulaId", selection(candidate, other.id()).get("formulaId"));
            assertFalse(accepts(fixture.schema(), candidate));
            var answer = assess(fixture, candidate);
            unmet(answer, changed.id(), "NATIVE_FORMULA_ROLE_MISMATCH");
            preserved(fixture, baseline, answer, changed);
        }
        var wrongSource = fixture.catalog().formulas().stream().filter(formula -> formula.sourceId().equals("s4"))
                .findFirst().orElseThrow();
        assertEquals(FormulaOrigin.SOURCE, wrongSource.origin());
        var changed = slot(fixture, Role.SOURCE_DEFINITION);
        var candidate = fixture.candidate().deepCopy();
        selection(candidate, changed.id()).put("formulaId", wrongSource.id());
        assertFalse(accepts(fixture.schema(), candidate));
        var answer = assess(fixture, candidate);
        unmet(answer, changed.id(), "NATIVE_FORMULA_ROLE_MISMATCH");
        preserved(fixture, baseline, answer, changed);
    }

    @Test
    void duplicateBindingsRemainARequiredRuntimeCheckBeyondTheStructuralSchema() throws Exception {
        var fixture = fixture();
        var changed = slot(fixture, Role.SOURCE_DEFINITION);
        var candidate = fixture.candidate().deepCopy();
        var bindings = (ArrayNode) selection(candidate, changed.id()).path("bindingIds");
        assertEquals(2, bindings.size());
        bindings.set(1, bindings.get(0));
        assertTrue(accepts(fixture.schema(), candidate));
        var answer = assess(fixture, candidate);
        unmet(answer, changed.id(), "NATIVE_BINDING_DUPLICATE");
        preserved(fixture, assess(fixture, fixture.candidate()), answer, changed);
    }

    @Test
    void missingUnknownAndCrossSlotBindingsCannotSupplyRequiredInputs() throws Exception {
        var fixture = fixture();
        var changed = slot(fixture, Role.SOURCE_DEFINITION);
        var baseline = assess(fixture, fixture.candidate());
        for (String mutation : List.of("missing", "unknown", "cross-slot")) {
            var candidate = fixture.candidate().deepCopy();
            var bindings = (ArrayNode) selection(candidate, changed.id()).path("bindingIds");
            String expected;
            switch (mutation) {
                case "missing" -> {
                    bindings.remove(0);
                    expected = "NATIVE_BINDING_INCOMPLETE";
                }
                case "unknown" -> {
                    bindings.set(0, JSON.getNodeFactory().textNode("b999"));
                    expected = "NATIVE_BINDING_INVALID";
                }
                default -> {
                    selection(candidate, changed.id()).set("bindingIds",
                            selection(candidate, slot(fixture, Role.IMPLEMENTATION).id()).get("bindingIds"));
                    expected = "NATIVE_BINDING_SCOPE_MISMATCH";
                }
            }
            assertFalse(accepts(fixture.schema(), candidate), mutation);
            var answer = assess(fixture, candidate);
            unmet(answer, changed.id(), expected);
            preserved(fixture, baseline, answer, changed);
        }
    }

    @Test
    void nullCalculationPayloadCannotReplaceAnExplicitUnsupportedDecision() throws Exception {
        var fixture = fixture();
        var changed = slot(fixture, Role.SOURCE_DEFINITION);
        var candidate = fixture.candidate().deepCopy();
        results(candidate).set(changed.id(), nullSelection());
        assertFalse(accepts(fixture.schema(), candidate));
        var answer = assess(fixture, candidate);
        unmet(answer, changed.id(), "NATIVE_ID_INVALID");
        preserved(fixture, assess(fixture, fixture.candidate()), answer, changed);
    }

    @Test
    void repeatedExpressionOccurrencesKeepFormulaInputAndPrecisionBindingsSeparate() throws Exception {
        var fixture = fixture(READ + DIAGNOSIS + EXPRESSION
                + "Evaluate total / count for total = 18 and count = 4 to 1 decimal place.", sources());
        var expressions = fixture.catalog().requirements().slots().stream().filter(slot -> slot.role() == Role.REQUEST_EXPRESSION).toList();
        assertEquals(2, expressions.size());
        var baseline = assess(fixture, fixture.candidate());
        complete(baseline);
        List<String> fields = List.of("formulaId", "bindingIds", "precisionId");
        List<String> reasons = List.of("NATIVE_FORMULA_ROLE_MISMATCH", "NATIVE_BINDING_SCOPE_MISMATCH", "NATIVE_PRECISION_SCOPE_MISMATCH");
        for (int index = 0; index < fields.size(); index++) {
            var candidate = fixture.candidate().deepCopy();
            String field = fields.get(index);
            JsonNode borrowed = selection(candidate, expressions.getLast().id()).get(field);
            assertNotEquals(selection(candidate, expressions.getFirst().id()).get(field), borrowed);
            selection(candidate, expressions.getFirst().id()).set(field, borrowed);
            assertFalse(accepts(fixture.schema(), candidate), field);
            var answer = assess(fixture, candidate);
            unmet(answer, expressions.getFirst().id(), reasons.get(index));
            preserved(fixture, baseline, answer, expressions.getFirst());
        }
    }

    @Test
    void unsupportedInputUnitsCannotBeReplacedByHtmlDefaults() throws Exception {
        var fixture = fixture(REQUEST.replace("volume 24 L", "volume 24 kg"), sources());
        assertTrue(fixture.catalog().bindings().stream().filter(binding ->
                binding.slotId().equals(slot(fixture, Role.SOURCE_DEFINITION).id())).noneMatch(binding -> binding.variable().equals("volume")));
        assertFalse(accepts(fixture.schema(), fixture.candidate()));
        var answer = assess(fixture, fixture.candidate());
        unmet(answer, slot(fixture, Role.SOURCE_DEFINITION).id(), "NATIVE_BINDING_INCOMPLETE");
        assertTrue(component(answer, slot(fixture, Role.DIAGNOSIS).id()).met());
        assertTrue(component(answer, slot(fixture, Role.SELECTOR).id()).met());
    }

    @Test
    void resultPropertyOrderDoesNotChangeLedgerOrderOrComparisonDependencies() throws Exception {
        var fixture = fixture();
        var candidate = fixture.candidate().deepCopy();
        var ids = new ArrayList<>(fixture.catalog().requirements().slots().stream().map(AnswerResultRequirements.Slot::id).toList());
        Collections.reverse(ids);
        var reversed = JSON.createObjectNode();
        ids.forEach(id -> reversed.set(id, results(candidate).get(id)));
        candidate.set("results", reversed);
        assertTrue(accepts(fixture.schema(), candidate));
        assertEquals(assess(fixture, fixture.candidate()), assess(fixture, candidate));
    }

    @Test
    void targetRequestAndObservedSourceIdentityCannotBeRebound() throws Exception {
        var fixture = fixture();
        var target = fixture.candidate().deepCopy().put("targetId", "t999");
        assertFalse(accepts(fixture.schema(), target));
        global(assess(fixture, target), "NATIVE_TARGET_INVALID");
        var changedMeaning = ReadOnlyRequestMeaning.compile(REQUEST.replace("volume 24 L", "volume 25 L"));
        global(NativeReadOnlyAnswerProposal.assessDecisions(fixture.candidate().toString(), changedMeaning, fixture.sources()),
                "NATIVE_CATALOG_MISMATCH");
        var reordered = new ArrayList<>(fixture.sources());
        Collections.reverse(reordered);
        global(NativeReadOnlyAnswerProposal.assessDecisions(fixture.candidate().toString(), fixture.meaning(), reordered),
                "NATIVE_CATALOG_MISMATCH");
        var changed = replace(fixture.sources(), "guide.md", GUIDE + "An additional retained fact.\n");
        global(NativeReadOnlyAnswerProposal.assessDecisions(fixture.candidate().toString(), fixture.meaning(), changed),
                "NATIVE_CATALOG_MISMATCH");
        var missingRead = fixture.sources().stream().filter(source -> !source.path().equals("spare.js")).toList();
        global(NativeReadOnlyAnswerProposal.assessDecisions(fixture.candidate().toString(), fixture.meaning(), missingRead),
                "NATIVE_REQUIRED_READ_UNAVAILABLE");
    }

    @Test
    void versionSixSevenAndEightKeepSeparateCatalogAndDecoderContracts() throws Exception {
        var fixture = fixture();
        var six = NativeReadOnlyAnswerProposal.catalog(REQUEST, fixture.sources());
        var seven = NativeReadOnlyAnswerProposal.catalog(fixture.meaning(), fixture.sources());
        assertEquals(3, Set.of(six.id(), seven.id(), fixture.catalog().id()).size());
        assertEquals(List.of(6, 7, 8), List.of(six.version(), seven.version(), fixture.catalog().version()));
        assertEquals("array", JSON.readTree(NativeReadOnlyAnswerProposal.responseSchema(REQUEST, fixture.sources()))
                .at("/properties/results/type").asText());
        assertEquals("array", JSON.readTree(NativeReadOnlyAnswerProposal.responseSchema(fixture.meaning(), fixture.sources()))
                .at("/properties/results/type").asText());
        var sixCandidate = NativeAnswerProposalTestSupport.proposal(REQUEST, fixture.sources());
        var sevenCandidate = NativeAnswerProposalTestSupport.proposal(fixture.meaning(), fixture.sources());
        for (ObjectNode legacy : List.of(sixCandidate, sevenCandidate)) {
            global(assess(fixture, legacy), "NATIVE_VERSION_INVALID");
            global(assess(fixture, legacy.deepCopy().put("version", 8)), "NATIVE_CATALOG_MISMATCH");
        }
        global(NativeReadOnlyAnswerProposal.assess(REQUEST, fixture.candidate().toString(), fixture.sources()), "NATIVE_VERSION_INVALID");
        global(NativeReadOnlyAnswerProposal.assess(fixture.candidate().toString(), fixture.meaning(), fixture.sources()), "NATIVE_VERSION_INVALID");
        complete(NativeReadOnlyAnswerProposal.assess(sevenCandidate.toString(), fixture.meaning(), fixture.sources()));
        for (int version : List.of(0, 5, 6, 7, 9)) {
            var changed = fixture.candidate().deepCopy().put("version", version);
            assertFalse(accepts(fixture.schema(), changed));
            global(assess(fixture, changed), "NATIVE_VERSION_INVALID");
        }
    }

    @Test
    void candidateSizeAndResultCountBoundsAreRetained() throws Exception {
        var fixture = fixture();
        assertEquals(16_000, ReadOnlyAnswerProposal.MAX_CANDIDATE_CHARS);
        String exact = fixture.candidate() + " ".repeat(16_000 - fixture.candidate().toString().length());
        assertEquals(16_000, exact.length());
        complete(assess(fixture, exact));
        global(assess(fixture, exact + " "), "PROPOSAL_SIZE_INVALID");
        var maximum = fixture(READ + DIAGNOSIS.repeat(32), sources());
        assertEquals(32, maximum.catalog().requirements().slots().size());
        assertEquals(32, maximum.schema().at("/properties/results/required").size());
        assertTrue(accepts(maximum.schema(), maximum.candidate()));
        var excessive = maximum.candidate().deepCopy();
        results(excessive).put("result-33", "unsupported");
        assertFalse(accepts(maximum.schema(), excessive));
        global(assess(maximum, excessive), "NATIVE_RESULT_BOUND");
        var overflow = fixture(READ + DIAGNOSIS.repeat(33), sources());
        assertFalse(overflow.catalog().requirements().complete());
        assertTrue(overflow.catalog().requirements().slots().size() <= 32);
        incomplete(assess(overflow, overflow.candidate()));
    }

    @Test
    void schemaCharactersAndBranchesRemainIndependentlyBounded() {
        assertEquals(24_000, NativeReadOnlyAnswerProposal.MAX_SCHEMA_CHARS);
        assertEquals(256, NativeReadOnlyAnswerProposal.MAX_SCHEMA_BRANCHES);
        String clause = "Give the flow according to guide.md for volume 24 L and elapsed 6 s. ";
        var manyFormulas = formulaSources(100);
        var meaning = ReadOnlyRequestMeaning.compile(READ + DIAGNOSIS + clause);
        var catalog = NativeReadOnlyAnswerProposal.decisionCatalog(meaning, manyFormulas);
        assertEquals(101, catalog.formulas().size());
        assertEquals(100, catalog.bindings().size());
        assertEquals("NATIVE_SCHEMA_BOUND", assertThrows(ReadOnlyAnswerProposal.Rejected.class,
                () -> NativeReadOnlyAnswerProposal.decisionSchema(meaning, manyFormulas)).reason);
        var manyBranches = formulaSources(32);
        var repeated = ReadOnlyRequestMeaning.compile(READ + DIAGNOSIS + clause.repeat(8));
        var repeatedCatalog = NativeReadOnlyAnswerProposal.decisionCatalog(repeated, manyBranches);
        assertEquals(256, repeatedCatalog.bindings().size());
        assertEquals(8, repeatedCatalog.requirements().slots().stream().filter(slot -> slot.role() == Role.SOURCE_DEFINITION).count());
        assertEquals("NATIVE_SCHEMA_BOUND", assertThrows(ReadOnlyAnswerProposal.Rejected.class,
                () -> NativeReadOnlyAnswerProposal.decisionSchema(repeated, manyBranches)).reason);
    }

    @Test
    void requestAndObservedSourceBoundsRemainRequiredForDecisionCatalogs() {
        var meaning = ReadOnlyRequestMeaning.compile("x".repeat(AnswerRequirements.MAX_REQUEST_CHARS + 1));
        assertEquals("NATIVE_CONTEXT_UNAVAILABLE", assertThrows(ReadOnlyAnswerProposal.Rejected.class,
                () -> NativeReadOnlyAnswerProposal.decisionSchema(meaning, sources())).reason);
        var excessive = new ArrayList<>(sources());
        for (int index = 0; index < 5; index++) excessive.add(source("extra-" + index + ".md", "Observed data.\n"));
        assertEquals(9, excessive.size());
        assertEquals("NATIVE_CONTEXT_UNAVAILABLE", assertThrows(ReadOnlyAnswerProposal.Rejected.class,
                () -> NativeReadOnlyAnswerProposal.decisionSchema(ReadOnlyRequestMeaning.compile(REQUEST), excessive)).reason);
    }

    private static Fixture fixture() throws Exception { return fixture(REQUEST, sources()); }

    private static Fixture fixture(String request, List<Source> observed) throws Exception {
        var meaning = ReadOnlyRequestMeaning.compile(request);
        var catalog = NativeReadOnlyAnswerProposal.decisionCatalog(meaning, observed);
        JsonNode schema = JSON.readTree(NativeReadOnlyAnswerProposal.decisionSchema(meaning, observed));
        var candidate = JSON.createObjectNode().put("version", 8).put("catalogId", catalog.id()).put("targetId", "t0");
        var decisions = candidate.putObject("interpretationDecisions");
        var results = candidate.putObject("results");
        for (var slot : catalog.requirements().slots()) {
            if (slot.role() == Role.UNKNOWN) {
                results.put(slot.id(), "unsupported");
                decisions.put(slot.id(), "unsupported");
                continue;
            }
            var selected = nullSelection();
            results.set(slot.id(), selected);
            if (!slot.calculation()) continue;
            var formula = catalog.formulas().stream().filter(value -> switch (slot.role()) {
                case SOURCE_DEFINITION -> value.origin() == FormulaOrigin.SOURCE
                        && observed.get(Integer.parseInt(value.sourceId().substring(1))).path().equals(slot.sourcePath());
                case IMPLEMENTATION -> value.origin() == FormulaOrigin.IMPLEMENTATION;
                case REQUEST_EXPRESSION -> value.origin() == FormulaOrigin.REQUEST
                        && value.span().start() >= slot.start() && value.span().end() <= slot.end();
                default -> false;
            }).findFirst().orElseThrow();
            selected.put("formulaId", formula.id());
            var bindings = selected.putArray("bindingIds");
            catalog.bindings().stream().filter(binding -> binding.slotId().equals(slot.id()) && binding.formulaId().equals(formula.id()))
                    .forEach(binding -> bindings.add(binding.id()));
            catalog.precisions().stream().filter(precision -> precision.slotId().equals(slot.id())).findFirst()
                    .ifPresent(precision -> selected.put("precisionId", precision.id()));
        }
        return new Fixture(meaning, List.copyOf(observed), catalog, schema, candidate);
    }

    private static ObjectNode nullSelection() {
        var selected = JSON.createObjectNode().putNull("formulaId");
        selected.putArray("bindingIds");
        selected.putNull("precisionId");
        return selected;
    }

    private static Set<Role> roles(Fixture fixture) {
        var roles = new HashSet<Role>();
        fixture.catalog().requirements().slots().forEach(slot -> roles.add(slot.role()));
        return roles;
    }

    private static AnswerResultRequirements.Slot slot(Fixture fixture, Role role) {
        return fixture.catalog().requirements().slots().stream().filter(slot -> slot.role() == role).findFirst().orElseThrow();
    }

    private static ObjectNode results(ObjectNode candidate) { return (ObjectNode) candidate.get("results"); }
    private static ObjectNode selection(ObjectNode candidate, String id) { return (ObjectNode) results(candidate).get(id); }
    private static ReadOnlyWebDiagnosticResult assess(Fixture fixture, JsonNode candidate) { return assess(fixture, candidate.toString()); }
    private static ReadOnlyWebDiagnosticResult assess(Fixture fixture, String candidate) {
        return NativeReadOnlyAnswerProposal.assessDecisions(candidate, fixture.meaning(), fixture.sources());
    }

    private static ReadOnlyAnswerComponents.Component component(ReadOnlyWebDiagnosticResult answer, String id) {
        return answer.components().components().stream().filter(item -> item.requirementId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Required component missing: " + id + "\n" + answer.answer()));
    }

    private static void preserved(Fixture fixture, ReadOnlyWebDiagnosticResult baseline,
                                  ReadOnlyWebDiagnosticResult actual, AnswerResultRequirements.Slot changed) {
        for (var sibling : fixture.catalog().requirements().slots()) {
            if (sibling.id().equals(changed.id()) || sibling.role() == Role.COMPARISON && sibling.peerSlotIds().contains(changed.id())) continue;
            assertEquals(component(baseline, sibling.id()), component(actual, sibling.id()), sibling.role().name());
        }
    }

    private static void complete(ReadOnlyWebDiagnosticResult answer) {
        assertTrue(answer.components().complete(), answer.answer());
        assertTrue(answer.components().requestCovered(), answer.answer());
        assertTrue(answer.components().deliveredIntact(answer.answer()));
        assertTrue(answer.answer().endsWith(ReadOnlyAnswerComponents.FOOTER));
    }

    private static void incomplete(ReadOnlyWebDiagnosticResult answer) {
        assertFalse(answer.components().complete(), answer.answer());
        assertFalse(answer.components().requestCovered(), answer.answer());
        assertTrue(answer.answer().endsWith(ReadOnlyAnswerComponents.FOOTER));
    }

    private static void unmet(ReadOnlyWebDiagnosticResult answer, String id, String reason) {
        incomplete(answer);
        var component = component(answer, id);
        assertFalse(component.met());
        assertEquals(reason, component.reason(), answer.answer());
    }

    private static void global(ReadOnlyWebDiagnosticResult answer, String reason) {
        incomplete(answer);
        assertEquals(1, answer.components().components().size(), answer.answer());
        assertEquals(reason, answer.components().components().getFirst().reason(), answer.answer());
    }

    // The emitted schema subset is checked here. Backend grammar behavior is checked separately.
    private static boolean accepts(JsonNode schema, JsonNode value) {
        if (value == null) return false;
        if (schema.has("oneOf")) {
            int matches = 0;
            for (JsonNode branch : schema.get("oneOf")) if (accepts(branch, value)) matches++;
            return matches == 1;
        }
        if (schema.has("enum")) {
            boolean match = false;
            for (JsonNode option : schema.get("enum")) if (option.equals(value)) match = true;
            if (!match) return false;
        }
        return switch (schema.path("type").asText()) {
            case "object" -> {
                if (!value.isObject()) yield false;
                boolean valid = true;
                for (JsonNode required : schema.path("required")) valid &= value.has(required.asText());
                for (String name : names(value)) {
                    JsonNode property = schema.path("properties").get(name);
                    valid &= property == null ? schema.path("additionalProperties").asBoolean(true) : accepts(property, value.get(name));
                }
                yield valid;
            }
            case "array" -> {
                if (!value.isArray() || value.size() < schema.path("minItems").intValue()
                        || value.size() > schema.path("maxItems").intValue()) yield false;
                boolean valid = true;
                for (JsonNode item : value) valid &= accepts(schema.get("items"), item);
                yield valid;
            }
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "null" -> value.isNull();
            default -> throw new AssertionError("Unmodeled schema type: " + schema);
        };
    }

    private static void inspect(JsonNode schema) {
        assertTrue(SCHEMA_KEYWORDS.containsAll(names(schema)), schema.toString());
        if (schema.has("oneOf")) {
            assertEquals(Set.of("oneOf"), names(schema));
            schema.get("oneOf").forEach(NativeResultDecisionTest::inspect);
        } else if (schema.path("type").asText().equals("object")) {
            assertTrue(schema.path("additionalProperties").isBoolean());
            assertFalse(schema.get("additionalProperties").booleanValue());
            assertEquals(names(schema.path("properties")), strings(schema.path("required")));
            assertEquals(schema.path("properties").size(), schema.path("required").size());
            schema.path("properties").forEach(NativeResultDecisionTest::inspect);
        } else if (schema.path("type").asText().equals("array")) {
            assertTrue(schema.path("minItems").intValue() >= 0);
            assertTrue(schema.path("maxItems").intValue() <= 32);
            inspect(schema.get("items"));
        }
    }

    private static Set<String> names(JsonNode node) {
        var names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> strings(JsonNode node) {
        var values = new HashSet<String>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static List<Source> sources() {
        return List.of(source("guide.md", GUIDE), source("index.html", HTML), source("engine.js", SCRIPT),
                source("spare.js", "console.log('unlinked source');\n"));
    }

    private static Source source(String path, String text) {
        return new Source(path, text, UUID.nameUUIDFromBytes(("result-decisions\n" + path).getBytes(StandardCharsets.UTF_8)).toString(),
                1, ReadOnlyWebDiagnosticResult.textHash(text), SOURCE_PROJECTION_VERSION);
    }

    private static List<Source> replace(List<Source> observed, String path, String text) {
        return observed.stream().map(value -> value.path().equals(path) ? source(path, text) : value).toList();
    }

    private static List<Source> formulaSources(int count) {
        String guide = java.util.stream.IntStream.rangeClosed(1, count).mapToObj(value -> "Flow is volume plus " + value + ".\n")
                .collect(java.util.stream.Collectors.joining());
        return replace(sources(), "guide.md", guide);
    }

    private record Fixture(ReadOnlyRequestMeaning meaning, List<Source> sources,
                           NativeReadOnlyAnswerProposal.Catalog catalog, JsonNode schema, ObjectNode candidate) {}
}
