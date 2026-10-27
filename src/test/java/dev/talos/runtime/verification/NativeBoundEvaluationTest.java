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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dev.talos.runtime.task.AnswerResultRequirements.Role;
import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;
import static dev.talos.runtime.verification.ReadOnlyAnswerProposal.FormulaOrigin;
import static org.junit.jupiter.api.Assertions.*;

/** Authored evaluations exercise the public assessor without model generation. */
class NativeBoundEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<Role> FIXED = Set.of(Role.DIAGNOSIS, Role.SELECTOR, Role.SCRIPT_DISTINCTION);
    private static final String READ = "Read guide.md, index.html, engine.js and spare.js. ";
    private static final String DIAGNOSIS = "Explain why the Compute button fails. ";
    private static final String LINKAGE = "Describe how engine.js differs from spare.js in their HTML linkage. ";
    private static final String NUMBERS = "For volume 24 L and elapsed 6 s, give the flow rate according to guide.md "
            + "and the mathematical value of the expression in engine.js separately. Explain any disagreement. ";
    private static final String REQUEST = READ + DIAGNOSIS + "Give the correct selector. " + NUMBERS + LINKAGE
            + "Evaluate total / count for total = 21 and count = 4 to 3 decimal places. "
            + "Do not edit files or claim tests were run.";
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

    @Test
    void onlyBoundNonnumericalRolesHaveAFixedRequiredEvaluation() throws Exception {
        var fixture = fixture(REQUEST + " Explain the security risks.", sources());
        assertEquals(Set.of(Role.values()), new HashSet<>(fixture.catalog().requirements().slots().stream()
                .map(AnswerResultRequirements.Slot::role).toList()));
        assertEquals(9, fixture.catalog().version());
        assertEquals(9, fixture.schema().at("/properties/version/enum/0").intValue());
        assertEquals(fixture.catalog().id(), fixture.schema().at("/properties/catalogId/enum/0").asText());
        JsonNode resultSchema = fixture.schema().at("/properties/results");
        assertFalse(resultSchema.path("additionalProperties").asBoolean());
        assertEquals(fixture.catalog().requirements().slots().size(), resultSchema.path("required").size());
        for (var slot : fixture.catalog().requirements().slots()) {
            assertTrue(contains(resultSchema.path("required"), slot.id()));
            JsonNode choice = resultSchema.path("properties").path(slot.id());
            assertEquals(!FIXED.contains(slot.role()), accepts(choice, JSON.getNodeFactory().textNode("unsupported")), slot.role().name());
            if (FIXED.contains(slot.role())) {
                assertTrue(accepts(choice, evaluation()), slot.role().name());
                assertFalse(accepts(choice, JSON.getNodeFactory().nullNode()));
            }
        }
        assertTrue(accepts(fixture.schema(), fixture.candidate()));
        String instructions = NativeReadOnlyAnswerProposal.evaluationInstructions(fixture.meaning(), fixture.sources());
        assertTrue(instructions.contains("version=9\n"));
        assertTrue(instructions.contains("catalogId=" + fixture.catalog().id()));
    }

    @Test
    void fixedEvaluationsStillProduceEvidenceBoundAnswersAndNumericalResults() throws Exception {
        var fixture = fixture();
        var answer = assess(fixture, fixture.candidate());
        complete(answer);
        assertTrue(answer.answer().contains("lookup would be null"));
        assertTrue(answer.answer().contains("`#missing` should be replaced by `#compute`"));
        assertTrue(answer.answer().contains("`index.html` links `engine.js`"));
        assertTrue(answer.answer().contains("`4.00`"));
        assertTrue(answer.answer().contains("`30.00`"));
        assertTrue(answer.answer().contains("`5.250`"));
        for (Role role : FIXED) assertFalse(component(answer, slot(fixture, role).id()).references().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DIAGNOSIS", "SELECTOR", "SCRIPT_DISTINCTION"})
    void unsupportedFixedEvaluationIsRejectedWithoutDiscardingSiblings(Role role) throws Exception {
        var fixture = fixture();
        var changed = slot(fixture, role);
        var candidate = fixture.candidate().deepCopy();
        results(candidate).put(changed.id(), "unsupported");
        assertFalse(accepts(fixture.schema(), candidate));
        var answer = assess(fixture, candidate);
        unmet(answer, changed.id(), "NATIVE_RESULT_UNSUPPORTED");
        preserved(fixture, assess(fixture, fixture.candidate()), answer, Set.of(changed.id()));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DIAGNOSIS", "SELECTOR", "SCRIPT_DISTINCTION"})
    void missingFixedEvaluationIsNotFilledInByAvailableFacts(Role role) throws Exception {
        var fixture = fixture();
        var changed = slot(fixture, role);
        var candidate = fixture.candidate().deepCopy();
        results(candidate).remove(changed.id());
        assertFalse(accepts(fixture.schema(), candidate));
        var answer = assess(fixture, candidate);
        unmet(answer, changed.id(), "NATIVE_REQUIRED_RESULT_MISSING");
        preserved(fixture, assess(fixture, fixture.candidate()), answer, Set.of(changed.id()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "[]", "{}", "\"evaluate\""})
    void malformedFixedEvaluationsRemainRejectedPerSlot(String value) throws Exception {
        var fixture = fixture();
        var baseline = assess(fixture, fixture.candidate());
        for (Role role : FIXED) {
            var changed = slot(fixture, role);
            var candidate = fixture.candidate().deepCopy();
            results(candidate).set(changed.id(), JSON.readTree(value));
            assertFalse(accepts(fixture.schema(), candidate));
            var answer = assess(fixture, candidate);
            unmet(answer, changed.id(), "NATIVE_FIELDS_INVALID");
            preserved(fixture, baseline, answer, Set.of(changed.id()));
        }
    }

    @Test
    void aFixedEvaluationCannotCarryAFormulaOrAdditionalAuthority() throws Exception {
        var fixture = fixture();
        for (Role role : FIXED) {
            var changed = slot(fixture, role);
            for (String field : List.of("formulaId", "permission")) {
                var candidate = fixture.candidate().deepCopy();
                selection(candidate, changed.id()).put(field, "f0");
                assertFalse(accepts(fixture.schema(), candidate));
                var answer = assess(fixture, candidate);
                unmet(answer, changed.id(), field.equals("formulaId") ? "NATIVE_RESULT_PAYLOAD_INVALID" : "NATIVE_FIELDS_INVALID");
                preserved(fixture, assess(fixture, fixture.candidate()), answer, Set.of(changed.id()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Compute", "compute", "COMPUTE"})
    void duplicateObservedLabelsCannotBeResolvedBySelectionOrDomOrder(String secondLabel) throws Exception {
        for (boolean reverse : List.of(false, true)) {
            var fixture = fixture(REQUEST, duplicateButtons(secondLabel, reverse));
            assertEquals(2, fixture.catalog().targets().size());
            ReadOnlyWebDiagnosticResult previous = null;
            for (var target : fixture.catalog().targets()) {
                var candidate = fixture.candidate().deepCopy().put("targetId", target.id());
                var answer = assess(fixture, candidate);
                unmet(answer, slot(fixture, Role.DIAGNOSIS).id(), "KNOWN_TARGET_AMBIGUOUS");
                unmet(answer, slot(fixture, Role.SELECTOR).id(), "KNOWN_TARGET_AMBIGUOUS");
                assertFalse(answer.answer().contains("should be replaced by"));
                for (var slot : fixture.catalog().requirements().slots()) {
                    if (slot.role() == Role.DIAGNOSIS || slot.role() == Role.SELECTOR) continue;
                    assertTrue(component(answer, slot.id()).met(), slot.role().name());
                    if (previous != null) assertEquals(component(previous, slot.id()), component(answer, slot.id()));
                }
                previous = answer;
            }
        }
    }

    @Test
    void explicitIdsDisambiguateDuplicateLabelsWithoutDependingOnDomOrder() throws Exception {
        for (String id : List.of("compute", "other")) for (boolean reverse : List.of(false, true)) {
            var fixture = fixture(REQUEST.replace("Compute button", "#" + id + " button"), duplicateButtons("compute", reverse));
            assertEquals(List.of(id), fixture.catalog().targets().stream()
                    .map(NativeReadOnlyAnswerProposal.TargetReference::buttonId).toList());
            var answer = assess(fixture, fixture.candidate());
            complete(answer);
            assertTrue(component(answer, slot(fixture, Role.SELECTOR).id()).text().contains("`#" + id + "`"));
        }
    }

    @Test
    void aSoleObservedButtonCannotSupplyAbsentRequestIntentOrAnAbsentId() throws Exception {
        var unbound = fixture(REQUEST.replace(DIAGNOSIS, ""), sources());
        assertEquals("", unbound.meaning().target());
        assertTrue(unbound.catalog().requirements().slots().stream().noneMatch(s -> s.role() == Role.SELECTOR));
        var answer = assess(unbound, unbound.candidate());
        incomplete(answer);
        assertTrue(unbound.catalog().requirements().slots().stream().anyMatch(s -> s.role() == Role.UNKNOWN));
        assertFalse(answer.answer().contains("should be replaced by"));
        assertTrue(component(answer, slot(unbound, Role.SOURCE_DEFINITION).id()).met());
        assertTrue(component(answer, slot(unbound, Role.SCRIPT_DISTINCTION).id()).met());

        var absent = fixture(REQUEST.replace("Compute button", "#absent button"), sources());
        assertTrue(absent.catalog().targets().isEmpty());
        global(assess(absent, absent.candidate()), "NATIVE_TARGET_INVALID");
    }

    @Test
    void mandatoryEvaluationDoesNotEstablishAFailureWhenTheSelectorAlreadyExists() throws Exception {
        var fixture = fixture(REQUEST, replace(sources(), "engine.js", SCRIPT.replace("'#missing'", "'#compute'")));
        var answer = assess(fixture, fixture.candidate());
        unmet(answer, slot(fixture, Role.DIAGNOSIS).id(), "DIAGNOSIS_RELATION_UNAVAILABLE");
        unmet(answer, slot(fixture, Role.SELECTOR).id(), "SELECTOR_RELATION_UNAVAILABLE");
        assertTrue(answer.diagnostics().isEmpty());
        assertFalse(answer.answer().contains("lookup would be null"));
        assertFalse(answer.answer().contains("should be replaced by"));
        for (var slot : fixture.catalog().requirements().slots())
            if (slot.role() != Role.DIAGNOSIS && slot.role() != Role.SELECTOR) assertTrue(component(answer, slot.id()).met());
    }

    @Test
    void availableFactsCannotCompleteAnUnknownRegion() throws Exception {
        var fixture = fixture(REQUEST + " Explain the security risks.", sources());
        var unknown = slot(fixture, Role.UNKNOWN);
        var baseline = assess(fixture, fixture.candidate());
        unmet(baseline, unknown.id(), "NATIVE_RESULT_UNSUPPORTED");
        for (Role role : FIXED) assertTrue(component(baseline, slot(fixture, role).id()).met());
        var candidate = fixture.candidate().deepCopy();
        results(candidate).set(unknown.id(), evaluation());
        assertFalse(accepts(fixture.schema(), candidate));
        var answer = assess(fixture, candidate);
        unmet(answer, unknown.id(), "NATIVE_UNKNOWN_RESULT_UNSUPPORTED");
        preserved(fixture, baseline, answer, Set.of(unknown.id()));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SOURCE_DEFINITION", "IMPLEMENTATION", "REQUEST_EXPRESSION", "COMPARISON"})
    void numericalAndComparisonAbstentionRemainEffective(Role role) throws Exception {
        var fixture = fixture();
        var changed = slot(fixture, role);
        var candidate = fixture.candidate().deepCopy();
        results(candidate).put(changed.id(), "unsupported");
        assertTrue(accepts(fixture.schema(), candidate));
        var answer = assess(fixture, candidate);
        unmet(answer, changed.id(), "NATIVE_RESULT_UNSUPPORTED");
        preserved(fixture, assess(fixture, fixture.candidate()), answer, Set.of(changed.id()));
        if (role == Role.COMPARISON)
            for (String peer : changed.peerSlotIds()) assertTrue(component(answer, peer).met());
        else for (var slot : fixture.catalog().requirements().slots())
            if (slot.role() == Role.COMPARISON && slot.peerSlotIds().contains(changed.id()))
                unmet(answer, slot.id(), "COMPARISON_PEER_UNAVAILABLE");
    }

    @Test
    void numericalRoleAndOperandChecksStillRejectTheIntendedDefects() throws Exception {
        var fixture = fixture();
        var calculation = slot(fixture, Role.SOURCE_DEFINITION);
        var baseline = assess(fixture, fixture.candidate());
        var wrongRole = fixture.candidate().deepCopy();
        selection(wrongRole, calculation.id()).set("formulaId",
                selection(wrongRole, slot(fixture, Role.IMPLEMENTATION).id()).get("formulaId"));
        var answer = assess(fixture, wrongRole);
        unmet(answer, calculation.id(), "NATIVE_FORMULA_ROLE_MISMATCH");
        preserved(fixture, baseline, answer, Set.of(calculation.id()));

        var duplicate = fixture.candidate().deepCopy();
        var bindings = (ArrayNode) selection(duplicate, calculation.id()).path("bindingIds");
        assertEquals(2, bindings.size());
        bindings.set(1, bindings.get(0));
        answer = assess(fixture, duplicate);
        unmet(answer, calculation.id(), "NATIVE_BINDING_DUPLICATE");
        preserved(fixture, baseline, answer, Set.of(calculation.id()));
    }

    @Test
    void repeatedRecognizedRequirementsRetainSeparateRequiredSlots() throws Exception {
        var fixture = fixture(READ + DIAGNOSIS.repeat(2), sources());
        var slots = fixture.catalog().requirements().slots();
        assertEquals(2, slots.size());
        assertNotEquals(slots.getFirst().id(), slots.getLast().id());
        var baseline = assess(fixture, fixture.candidate());
        complete(baseline);
        var candidate = fixture.candidate().deepCopy();
        results(candidate).remove(slots.getFirst().id());
        var answer = assess(fixture, candidate);
        unmet(answer, slots.getFirst().id(), "NATIVE_REQUIRED_RESULT_MISSING");
        assertEquals(component(baseline, slots.getLast().id()), component(answer, slots.getLast().id()));
    }

    @Test
    void malformedEnvelopesStaleIdentitiesAndMissingReadsAreNotRescued() throws Exception {
        var fixture = fixture();
        global(assess(fixture, fixture.candidate().deepCopy().put("catalogId", "0".repeat(64))), "NATIVE_CATALOG_MISMATCH");
        global(assess(fixture, fixture.candidate().deepCopy().put("targetId", "t999")), "NATIVE_TARGET_INVALID");
        global(assess(fixture, fixture.candidate().deepCopy().put("permission", true)), "NATIVE_FIELDS_INVALID");
        var missing = fixture.sources().stream().filter(source -> !source.path().equals("spare.js")).toList();
        global(NativeReadOnlyAnswerProposal.assessEvaluation(fixture.candidate().toString(), fixture.meaning(), missing),
                "NATIVE_REQUIRED_READ_UNAVAILABLE");
        var changed = replace(fixture.sources(), "guide.md", GUIDE + "A further source observation.\n");
        global(NativeReadOnlyAnswerProposal.assessEvaluation(fixture.candidate().toString(), fixture.meaning(), changed),
                "NATIVE_CATALOG_MISMATCH");
        var guarded = replace(fixture.sources(), "engine.js", SCRIPT.replace("button.addEventListener", "button?.addEventListener"));
        global(NativeReadOnlyAnswerProposal.assessEvaluation(fixture.candidate().toString(), fixture.meaning(), guarded),
                "NATIVE_CONTEXT_UNAVAILABLE");
    }

    @Test
    void versionEightReplayRetainsItsUnsupportedDecisionsAndCannotSelectTheNewDecoder() throws Exception {
        var fixture = fixture();
        var eight = NativeReadOnlyAnswerProposal.decisionCatalog(fixture.meaning(), fixture.sources());
        assertEquals(8, eight.version());
        assertNotEquals(eight.id(), fixture.catalog().id());
        var legacy = fixture.candidate().deepCopy().put("version", 8).put("catalogId", eight.id());
        JsonNode oldSchema = JSON.readTree(NativeReadOnlyAnswerProposal.decisionSchema(fixture.meaning(), fixture.sources()));
        for (Role role : FIXED) {
            var candidate = legacy.deepCopy();
            results(candidate).put(slot(fixture, role).id(), "unsupported");
            assertTrue(accepts(oldSchema, candidate));
            unmet(NativeReadOnlyAnswerProposal.assessDecisions(candidate.toString(), fixture.meaning(), fixture.sources()),
                    slot(fixture, role).id(), "NATIVE_RESULT_UNSUPPORTED");
        }
        complete(NativeReadOnlyAnswerProposal.assessDecisions(legacy.toString(), fixture.meaning(), fixture.sources()));
        global(assess(fixture, legacy), "NATIVE_VERSION_INVALID");
        global(assess(fixture, legacy.deepCopy().put("version", 9)), "NATIVE_CATALOG_MISMATCH");
        global(NativeReadOnlyAnswerProposal.assessDecisions(fixture.candidate().toString(), fixture.meaning(), fixture.sources()),
                "NATIVE_VERSION_INVALID");
    }

    @Test
    void duplicateLabelRejectionDoesNotRewriteVersionEightReplay() throws Exception {
        var fixture = fixture(REQUEST, duplicateButtons("Compute", false));
        var eight = NativeReadOnlyAnswerProposal.decisionCatalog(fixture.meaning(), fixture.sources());
        assertEquals(2, eight.targets().size());
        Set<String> legacySelectors = new HashSet<>();
        for (var target : eight.targets()) {
            var candidate = fixture.candidate().deepCopy().put("version", 8).put("catalogId", eight.id()).put("targetId", target.id());
            var answer = NativeReadOnlyAnswerProposal.assessDecisions(candidate.toString(), fixture.meaning(), fixture.sources());
            complete(answer);
            legacySelectors.add(component(answer, slot(fixture, Role.SELECTOR).id()).text());
        }
        assertEquals(2, legacySelectors.size());
    }

    private static Fixture fixture() throws Exception { return fixture(REQUEST, sources()); }

    private static Fixture fixture(String request, List<Source> sources) throws Exception {
        var meaning = ReadOnlyRequestMeaning.compile(request);
        var catalog = NativeReadOnlyAnswerProposal.evaluationCatalog(meaning, sources);
        JsonNode schema = JSON.readTree(NativeReadOnlyAnswerProposal.evaluationSchema(meaning, sources));
        var candidate = JSON.createObjectNode().put("version", 9).put("catalogId", catalog.id())
                .put("targetId", catalog.targets().isEmpty() ? "unavailable" : catalog.targets().getFirst().id());
        var results = candidate.putObject("results");
        var decisions = candidate.putObject("interpretationDecisions");
        for (var slot : catalog.requirements().slots()) {
            if (slot.role() == Role.UNKNOWN) {
                results.put(slot.id(), "unsupported");
                decisions.put(slot.id(), "unsupported");
                continue;
            }
            var evaluation = evaluation();
            results.set(slot.id(), evaluation);
            if (!slot.calculation()) continue;
            var formula = catalog.formulas().stream().filter(value -> switch (slot.role()) {
                case SOURCE_DEFINITION -> value.origin() == FormulaOrigin.SOURCE
                        && sources.get(Integer.parseInt(value.sourceId().substring(1))).path().equals(slot.sourcePath());
                case IMPLEMENTATION -> value.origin() == FormulaOrigin.IMPLEMENTATION;
                case REQUEST_EXPRESSION -> value.origin() == FormulaOrigin.REQUEST
                        && value.span().start() >= slot.start() && value.span().end() <= slot.end();
                default -> false;
            }).findFirst().orElseThrow();
            evaluation.put("formulaId", formula.id());
            var bindings = evaluation.putArray("bindingIds");
            catalog.bindings().stream().filter(binding -> binding.slotId().equals(slot.id()) && binding.formulaId().equals(formula.id()))
                    .forEach(binding -> bindings.add(binding.id()));
            catalog.precisions().stream().filter(precision -> precision.slotId().equals(slot.id())).findFirst()
                    .ifPresent(precision -> evaluation.put("precisionId", precision.id()));
        }
        return new Fixture(meaning, List.copyOf(sources), catalog, schema, candidate);
    }

    private static ObjectNode evaluation() {
        var value = JSON.createObjectNode().putNull("formulaId").putNull("precisionId");
        value.putArray("bindingIds");
        return value;
    }
    private static ObjectNode results(ObjectNode candidate) { return (ObjectNode) candidate.path("results"); }
    private static ObjectNode selection(ObjectNode candidate, String id) { return (ObjectNode) results(candidate).path(id); }
    private static AnswerResultRequirements.Slot slot(Fixture fixture, Role role) {
        return fixture.catalog().requirements().slots().stream().filter(slot -> slot.role() == role).findFirst().orElseThrow();
    }
    private static ReadOnlyWebDiagnosticResult assess(Fixture fixture, JsonNode candidate) {
        return NativeReadOnlyAnswerProposal.assessEvaluation(candidate.toString(), fixture.meaning(), fixture.sources());
    }
    private static ReadOnlyAnswerComponents.Component component(ReadOnlyWebDiagnosticResult answer, String id) {
        return answer.components().components().stream().filter(component -> component.requirementId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing component " + id + "\n" + answer.answer()));
    }
    private static void preserved(Fixture fixture, ReadOnlyWebDiagnosticResult before,
                                  ReadOnlyWebDiagnosticResult after, Set<String> changed) {
        for (var slot : fixture.catalog().requirements().slots()) {
            if (changed.contains(slot.id()) || slot.role() == Role.COMPARISON && slot.peerSlotIds().stream().anyMatch(changed::contains)) continue;
            assertEquals(component(before, slot.id()), component(after, slot.id()), slot.role().name());
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
    }
    private static void unmet(ReadOnlyWebDiagnosticResult answer, String id, String reason) {
        incomplete(answer);
        assertFalse(component(answer, id).met(), answer.answer());
        assertEquals(reason, component(answer, id).reason(), answer.answer());
    }
    private static void global(ReadOnlyWebDiagnosticResult answer, String reason) {
        incomplete(answer);
        assertEquals(1, answer.components().components().size(), answer.answer());
        assertEquals(reason, answer.components().components().getFirst().reason(), answer.answer());
    }
    private static boolean contains(JsonNode values, String expected) {
        for (JsonNode value : values) if (value.asText().equals(expected)) return true;
        return false;
    }

    // Structural schema checking only. Native backend grammar acceptance is a separate boundary.
    private static boolean accepts(JsonNode schema, JsonNode value) {
        if (value == null) return false;
        if (schema.has("oneOf")) {
            int matches = 0;
            for (JsonNode branch : schema.path("oneOf")) if (accepts(branch, value)) matches++;
            return matches == 1;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.path("enum")) found |= option.equals(value);
            if (!found) return false;
        }
        return switch (schema.path("type").asText()) {
            case "object" -> {
                if (!value.isObject()) yield false;
                boolean valid = true;
                for (JsonNode required : schema.path("required")) valid &= value.has(required.asText());
                for (var fields = value.fields(); fields.hasNext();) {
                    var field = fields.next();
                    JsonNode property = schema.path("properties").get(field.getKey());
                    valid &= property == null ? schema.path("additionalProperties").asBoolean(true) : accepts(property, field.getValue());
                }
                yield valid;
            }
            case "array" -> {
                if (!value.isArray() || value.size() < schema.path("minItems").asInt()
                        || value.size() > schema.path("maxItems").asInt()) yield false;
                boolean valid = true;
                for (JsonNode item : value) valid &= accepts(schema.path("items"), item);
                yield valid;
            }
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "null" -> value.isNull();
            default -> throw new AssertionError("Unmodeled schema " + schema);
        };
    }

    private static List<Source> duplicateButtons(String label, boolean reverse) {
        String first = "<button id=\"compute\">Compute</button>";
        String other = "<button id=\"other\">" + label + "</button>";
        return replace(sources(), "index.html", HTML.replace(first, reverse ? other + first : first + other));
    }
    private static List<Source> sources() {
        return List.of(source("guide.md", GUIDE), source("index.html", HTML), source("engine.js", SCRIPT),
                source("spare.js", "console.log('unlinked source');\n"));
    }
    private static Source source(String path, String text) {
        return new Source(path, text, UUID.nameUUIDFromBytes(("bound-evaluation\n" + path).getBytes(StandardCharsets.UTF_8)).toString(),
                1, ReadOnlyWebDiagnosticResult.textHash(text), SOURCE_PROJECTION_VERSION);
    }
    private static List<Source> replace(List<Source> sources, String path, String text) {
        return sources.stream().map(source -> source.path().equals(path) ? source(path, text) : source).toList();
    }
    private record Fixture(ReadOnlyRequestMeaning meaning, List<Source> sources,
                           NativeReadOnlyAnswerProposal.Catalog catalog, JsonNode schema, ObjectNode candidate) {}
}
