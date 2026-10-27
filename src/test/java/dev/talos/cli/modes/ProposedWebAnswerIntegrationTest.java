package dev.talos.cli.modes;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.ReadOnlyRequestMeaning;
import dev.talos.runtime.task.DocumentComparisonRequest;
import dev.talos.runtime.task.AnswerResultRequirements;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidenceTestSupport;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.verification.ReadOnlyAnswerComponents;
import dev.talos.runtime.verification.NativeAnswerProposalTestSupport;
import dev.talos.runtime.verification.NativeReadOnlyAnswerProposal;
import dev.talos.runtime.verification.ReadOnlyWebDiagnosticResult;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real request resolution and selected response binding are checked without live execution. */
class ProposedWebAnswerIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIAGNOSIS = "Explain why the Calculate button fails";
    private static final String BROAD_REQUEST = "Read README.md, index.html, script.js and scripts.js. "
            + DIAGNOSIS + ", give the correct selector and the BMI for 72 kg and 1.8 m, "
            + "and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
    private static final String REQUEST = BROAD_REQUEST.replace("distinguish the two JavaScript files",
            "identify which of script.js and scripts.js is linked by the HTML");
    private static final String README = "# Calculator review fixture\n\n"
            + "The calculator in index.html loads script.js. scripts.js is an unrelated sentinel and must stay unchanged. "
            + "The Calculate button should compute BMI as weight divided by height squared. This fixture is local test data.\n";
    private static final String HTML = """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"><title>BMI fixture</title></head>
            <body>
            <label>Weight (kg)<input id="weight" value="72"></label>
            <label>Height (m)<input id="height" value="1.8"></label>
            <button id="calculate">Calculate</button>
            <p id="result"></p>
            <script src="script.js"></script>
            </body>
            </html>
            """;
    private static final String SCRIPT = """
            const button = document.querySelector('#calc');
            button.addEventListener('click', () => {
              const weight = Number(document.querySelector('#weight').value);
              const height = Number(document.querySelector('#height').value);
              document.querySelector('#result').textContent = (weight / (height * height)).toFixed(1);
            });
            """;
    private static final String SENTINEL = "// Unrelated sentinel. This file is not loaded by index.html.\n"
            + "const sentinel = 'KEEP_UNCHANGED';\n";
    private static final String NOTES = "# Review notes\n\nRetention: local.\n";

    @AfterEach
    void clearCapture() {
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test
    void embeddedSourcesRetainTheOriginalLiveFixtureBytes() throws Exception {
        // These identities were read from the preserved live-001 diagnosis workspace.
        // Tests deliberately do not depend on the ignored local evidence directory.
        assertHash(README, 246, "55e8e208e76fe3d756bfda8ffdd431bb06ef48474c055eb770129a20a950da3e");
        assertHash(HTML, 328, "21aa6fd0a8558bee094d77d790cef8cd297366648905cf43335700b23d3bf08a");
        assertHash(SCRIPT, 316, "1f3c53767806eb0af9b98db72c208b767e2bc3e72c77ce152f4f2d8217402fa7");
        assertHash(SENTINEL, 97, "8ff1e9adbfed71b3ea0c11e688a5008f7180a78c87c6e42ccafb1a7c9d4940f4");
        assertHash(NOTES, 34, "ae0ce1a14253d74adc46db2cd7a863e5911719bd9a079e5b0a27cce776c5ba20");
    }

    @Test
    void scopedLinkageRequestAndProseReadmeCompleteThroughTheRealResolver(@TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, REQUEST, null, sources(false));
        assertEquals(9, JSON.readTree(turn.candidate()).path("version").asInt());
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            assertTrue(components.complete(), answer);
            assertEquals(ReadOnlyAnswerComponents.CoverageOrigin.REQUEST_MEANING, components.coverageOrigin());
            assertEquals(List.of("DIAGNOSIS", "SELECTOR", "CALCULATION", "SCRIPT_DISTINCTION"),
                    components.components().stream().map(ReadOnlyAnswerComponents.Component::kind).toList());
            return components.present(answer, UnaryOperator.identity(), 20_000);
        });
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertComplete(outcome);
        assertUsefulAnswer(outcome.finalAnswer());
        assertNotEquals(turn.candidate(), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("\"formulaReference\""));
        assertEquals(REQUEST, outcome.taskOutcome().contract().originalUserRequest());
        assertEquals("RUNTIME_DERIVED", event(trace, "READ_ONLY_WEB_ANSWER_ASSESSED").data().get("disposition"));
        assertEquals("REQUEST_MEANING", event(trace, "READ_ONLY_ANSWER_COMPONENTS_DERIVED").data().get("coverageOrigin"));
        assertEquals(Boolean.TRUE, event(trace, "READ_ONLY_ANSWER_COMPONENTS_PRESENTED").data().get("deliveredIntact"));
        assertEquals(Boolean.TRUE, event(trace, "READ_ONLY_ANSWER_COMPONENTS_COMMITTED").data().get("deliveredIntact"));
        assertEquals("RUNTIME_DETERMINISTIC", event(trace, "TERMINAL_ANSWER_SELECTED").data().get("origin"));
        assertTraceStatus(trace, "COMPLETE", "READ_ONLY_ANSWERED");
        assertSourcesUnchanged(workspace, sources(false));
    }

    @ParameterizedTest(name = "{0}, source association retained: {1}")
    @MethodSource("explicitLinkageConstructions")
    void explicitLinkageKeepsItsNativeResultThroughFinalComposition(
            String clause, boolean sourceAssociationRetained, @TempDir Path workspace) throws Exception {
        // Synthetic provider selections exercise the real resolver, evidence binding and final consumer.
        Map<String, String> observed = new LinkedHashMap<>();
        observed.put("index.html", HTML.replace("script.js", "engine.js"));
        observed.put("engine.js", SCRIPT);
        observed.put("spare.js", "const spare = 'retained';\n");
        String request = "Read index.html and engine.js. " + clause + ". Do not edit files or claim tests were run.";
        Turn turn = turn(workspace, request, null, observed);
        var evidence = turn.loop().readOnlyFinalizationEvidence();
        assertEquals(List.of(AnswerResultRequirements.Role.SCRIPT_DISTINCTION),
                evidence.requestMeaning().requirements().slots().stream().map(AnswerResultRequirements.Slot::role).toList());
        var nativeResult = NativeReadOnlyAnswerProposal.assessEvaluation(
                turn.candidate(), evidence.requestMeaning(), evidence.sources());
        assertEquals(1, nativeResult.components().components().size());
        var nativeComponent = nativeResult.components().components().getFirst();
        assertEquals("SCRIPT_DISTINCTION", nativeComponent.kind());
        assertTrue(nativeComponent.met(), nativeComponent.reason());
        if (!sourceAssociationRetained) {
            // All observations were captured, but their selected association is no longer admissible.
            var invalidated = evidence.invalidate();
            assertTrue(invalidated.proposalSelected());
            assertFalse(invalidated.available());
            assertTrue(invalidated.sources().isEmpty());
            turn = withEvidence(turn, invalidated, turn.loop().toolOutcomes());
        }

        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            if (sourceAssociationRetained) assertEquals(nativeResult.components().components(), components.components(),
                    "Document comparison composition must preserve the typed linkage component");
            else assertFalse(components.complete(), "Invalidated source evidence cannot establish linkage");
            return components.present(answer, UnaryOperator.identity(), 20_000);
        });
        assertFalse(DocumentComparisonRequest.from(request).requiresAssessment(),
                "A typed HTML linkage role must not create an ordered document comparison obligation");
        assertFalse(outcome.finalAnswer().contains("supported ordered operation"), outcome.finalAnswer());
        if (sourceAssociationRetained) {
            assertComplete(outcome);
            assertTrue(outcome.finalAnswer().contains("`index.html` links `engine.js`"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("`spare.js` was also read"), outcome.finalAnswer());
        } else {
            assertLimited(outcome);
            assertFalse(outcome.finalAnswer().contains("`spare.js` was also read"), outcome.finalAnswer());
        }
        assertSourcesUnchanged(workspace, observed);
    }

    private static Stream<Arguments> explicitLinkageConstructions() {
        return Stream.of(
                "Describe how engine.js differs from spare.js in their HTML linkage",
                "Explain how engine.js differs from spare.js in page linkage",
                "Compare engine.js and spare.js in terms of HTML linkage",
                "Distinguish engine.js and spare.js by their page linkage")
                .flatMap(clause -> Stream.of(Arguments.of(clause, true), Arguments.of(clause, false)));
    }

    @ParameterizedTest
    @MethodSource("orderedAndUnsupportedComparisonConstructions")
    void nativeLinkageCannotCompleteAnAdditionalDocumentComparison(
            String clause, boolean ordered, @TempDir Path workspace) throws Exception {
        String request = REQUEST + " " + clause + ".";
        Turn turn = turn(workspace, request, null, sources(false));
        var comparison = DocumentComparisonRequest.from(request);
        assertTrue(comparison.requiresAssessment());
        assertEquals(ordered, comparison.active());
        if (ordered) {
            assertEquals("script.js", comparison.requirements().getFirst().pair().left());
            assertEquals("scripts.js", comparison.requirements().getFirst().pair().right());
        } else assertEquals(1, comparison.unresolved().size());
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            assertTrue(components.components().stream().anyMatch(component ->
                    component.kind().equals("SCRIPT_DISTINCTION") && component.met()));
            assertTrue(components.components().stream().anyMatch(component ->
                    component.kind().equals("DOCUMENT_COMPARISON") && !component.met()
                            && component.reason().equals(ordered ? "COMPARISON_EVIDENCE_UNAVAILABLE"
                                    : "COMPARISON_MEANING_UNRESOLVED")));
            return answer;
        });
        assertLimited(outcome);
        if (ordered) assertUsefulAnswer(outcome.finalAnswer());
        else assertTrue(outcome.finalAnswer().contains("requested comparison remains incomplete"), outcome.finalAnswer());
        assertSourcesUnchanged(workspace, sources(false));
    }

    private static Stream<Arguments> orderedAndUnsupportedComparisonConstructions() {
        return Stream.of(
                Arguments.of("Compare script.js and scripts.js", true),
                Arguments.of("Describe how script.js differs from scripts.js", true),
                Arguments.of("Distinguish script.js and scripts.js", true),
                Arguments.of("Compare script.js with scripts.js", false));
    }

    @Test
    void fixedEvaluationObjectsCannotEstablishAnAbsentSelectorFailure(@TempDir Path workspace) throws Exception {
        Map<String, String> observed = sources(false);
        observed.put("script.js", SCRIPT.replace("'#calc'", "'#calculate'"));
        Turn turn = turn(workspace, REQUEST, null, observed);
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            assertFalse(components.complete(), answer);
            assertEquals(List.of("CALCULATION", "SCRIPT_DISTINCTION"), components.components().stream()
                    .filter(ReadOnlyAnswerComponents.Component::met).map(ReadOnlyAnswerComponents.Component::kind).toList());
            assertEquals("DIAGNOSIS_RELATION_UNAVAILABLE", components.components().stream()
                    .filter(item -> item.kind().equals("DIAGNOSIS")).findFirst().orElseThrow().reason());
            assertEquals("SELECTOR_RELATION_UNAVAILABLE", components.components().stream()
                    .filter(item -> item.kind().equals("SELECTOR")).findFirst().orElseThrow().reason());
            return answer;
        });
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertLimited(outcome);
        assertTrue(outcome.finalAnswer().contains("`22.2`"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("the lookup would be null"), outcome.finalAnswer());
        assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
        assertNoCompleteOutcome(trace);
        assertSourcesUnchanged(workspace, observed);
    }

    @Test
    void acceptedProposalAtTheToolLimitKeepsTheCheckedRenderedAnswer(@TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, REQUEST, null, sources(false));
        Turn atLimit = atLimit(turn);
        ExecutionOutcome outcome = finish(atLimit, workspace, (answer, components) -> answer);
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertTrue(atLimit.loop().hitIterLimit());
        assertComplete(outcome);
        assertUsefulAnswer(outcome.finalAnswer());
        assertTraceStatus(trace, "COMPLETE", "READ_ONLY_ANSWERED");
    }

    @Test
    void twoIndependentCalculationsCompleteThroughTheRealResolver(@TempDir Path workspace) throws Exception {
        String secondQuote = "Give the BMI for 80 kg and 2 m to 2 decimal places";
        String request = REQUEST + " " + secondQuote + ".";
        Turn turn = turn(workspace, request, null, sources(false));
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            var calculations = components.components().stream().filter(item -> item.kind().equals("CALCULATION")).toList();
            assertTrue(components.complete(), answer);
            assertEquals(2, calculations.size());
            assertTrue(calculations.get(0).text().contains("`22.2`"));
            assertTrue(calculations.get(1).text().contains("`20.00`"));
            assertNotEquals(calculations.get(0).derivationHash(), calculations.get(1).derivationHash());
            return components.present(answer, UnaryOperator.identity(), 20_000);
        });
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertComplete(outcome);
        assertUsefulAnswer(outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("`20.00`"));
        assertTraceStatus(trace, "COMPLETE", "READ_ONLY_ANSWERED");
        assertSourcesUnchanged(workspace, sources(false));
    }

    @Test
    void withholdingOneOfTwoCalculationsRetainsItsSiblingButPreventsCompletion(@TempDir Path workspace) throws Exception {
        String secondQuote = "Give the BMI for 80 kg and 2 m to 2 decimal places";
        String request = REQUEST + " " + secondQuote + ".";
        Turn turn = turn(workspace, request, null, sources(false));
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            var calculations = components.components().stream().filter(item -> item.kind().equals("CALCULATION")).toList();
            assertTrue(components.complete(), answer);
            assertEquals(2, calculations.size());
            return components.present(answer, text -> text.replace(calculations.get(1).text(), "[second calculation withheld]"), 20_000);
        });
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertLimited(outcome);
        assertUsefulAnswer(outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("`20.00`"));
        assertTrue(outcome.finalAnswer().contains("[second calculation withheld]"));
        assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
        assertNoCompleteOutcome(trace);
    }

    @Test
    void invalidCandidateAtTheToolLimitCannotReceiveTheCompleteComponentExemption(@TempDir Path workspace) throws Exception {
        String candidate = "`#calc` is missing from index.html. Use `#calculate`. BMI is 22.19.";
        Turn turn = atLimit(turn(workspace, "Please " + REQUEST, candidate, sources(false)));
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            assertFalse(components.complete());
            return answer;
        });
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertLimited(outcome);
        assertNotEquals(candidate, outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("22.19"));
        assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
        assertNoCompleteOutcome(trace);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedPhrasings")
    void boundedRequestRulesCompleteSupportedPhrasingsWithoutSourceSelectedMeaning(
            String name, String request, boolean withNotes, @TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, request, null, sources(withNotes));
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            assertTrue(components.complete(), name + ": " + answer);
            assertEquals(ReadOnlyAnswerComponents.CoverageOrigin.REQUEST_MEANING, components.coverageOrigin());
            assertEquals(List.of("DIAGNOSIS", "SELECTOR", "CALCULATION", "SCRIPT_DISTINCTION"),
                    components.components().stream().map(ReadOnlyAnswerComponents.Component::kind).toList());
            assertTrue(components.components().stream().allMatch(item -> item.interpretationDecision() == null));
            return answer;
        });
        LocalTurnTrace trace = settle(outcome.finalAnswer());
        assertComplete(outcome);
        assertUsefulAnswer(outcome.finalAnswer());
        assertEquals(request, outcome.taskOutcome().contract().originalUserRequest());
        assertTraceStatus(trace, "COMPLETE", "READ_ONLY_ANSWERED");
        assertSourcesUnchanged(workspace, sources(withNotes));
    }

    private static Stream<Arguments> supportedPhrasings() {
        return Stream.of(
                Arguments.of("Polite read prefix", "Please " + REQUEST, false),
                Arguments.of("Bounded exactly modifier",
                        REQUEST.replace(DIAGNOSIS, "Explain exactly why the Calculate button fails"), false),
                Arguments.of("Bounded broken predicate",
                        REQUEST.replace(DIAGNOSIS, "Explain why the Calculate button is broken"), false),
                Arguments.of("Combined polite fifth-file request",
                        "Please " + REQUEST.replace("Read README.md,", "Read README.md, notes.md,")
                                .replace(DIAGNOSIS, "Explain exactly why the Calculate button is broken"), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedPhrasings")
    void unsupportedPhrasingsRetainAvailableEvidenceButCannotClaimCompleteCoverage(
            String name, String request, boolean withNotes, boolean linkageAvailable, long unknownCount,
            @TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, request, null, sources(withNotes));
        assertTrue(turn.loop().readOnlyFinalizationEvidence().matches(turn.candidate(), workspace, turn.plan().taskContract()));
        boolean comparisonRecognized = request.equals(BROAD_REQUEST);
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            assertFalse(components.complete(), answer);
            assertEquals(comparisonRecognized, components.requestCovered());
            assertEquals(ReadOnlyAnswerComponents.CoverageOrigin.REQUEST_MEANING, components.coverageOrigin());
            assertTrue(components.components().stream().filter(item -> item.kind().equals("UNKNOWN"))
                    .allMatch(item -> item.interpretationDecision()
                            == dev.talos.runtime.verification.ReadOnlyAnswerInterpretation.Decision.UNSUPPORTED));
            List<String> expected = linkageAvailable
                    ? List.of("DIAGNOSIS", "SELECTOR", "CALCULATION", "SCRIPT_DISTINCTION")
                    : List.of("DIAGNOSIS", "SELECTOR", "CALCULATION");
            assertEquals(expected, components.components().stream().filter(ReadOnlyAnswerComponents.Component::met)
                    .map(ReadOnlyAnswerComponents.Component::kind).toList(), name);
            assertEquals(comparisonRecognized ? 0 : unknownCount, components.components().stream()
                    .filter(item -> item.kind().equals("UNKNOWN") && !item.met()).count(), name);
            if (comparisonRecognized) assertEquals(1, components.components().stream()
                    .filter(item -> item.kind().equals("DOCUMENT_COMPARISON") && !item.met()).count());
            return answer;
        });
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertLimited(outcome);
        assertTrue(outcome.finalAnswer().contains("`#calculate`"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("`22.2`"), outcome.finalAnswer());
        if (linkageAvailable) assertUsefulAnswer(outcome.finalAnswer());
        assertEquals(request, outcome.taskOutcome().contract().originalUserRequest());
        assertNotEquals(turn.candidate(), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("\"catalogId\""));
        assertEquals(comparisonRecognized, event(trace, "READ_ONLY_ANSWER_COMPONENTS_DERIVED").data().get("requestCovered"));
        assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
        assertNoCompleteOutcome(trace);
        assertSourcesUnchanged(workspace, sources(withNotes));
    }

    private static Stream<Arguments> unsupportedPhrasings() {
        return Stream.of(
                Arguments.of("Original broad script distinction is not linkage", BROAD_REQUEST, false, false, 1L),
                Arguments.of("Unknown diagnosis modifier remains unsupported",
                        REQUEST + " Explain precisely why the Calculate button fails.", false, true, 1L),
                Arguments.of("Appearance question is not failure diagnosis",
                        REQUEST + " Explain why the Calculate button is blue.", false, true, 1L),
                Arguments.of("Reading does not satisfy a summary request",
                        REQUEST + " Please read notes.md and summarize its contents.", true, true, 1L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedPhrasings")
    void historicalSupplementalDecisionsCannotReplaceTheSelectedRequestMeaningContract(
            String name, String request, boolean withNotes, boolean linkageAvailable, long unknownCount,
            @TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, request, null, sources(withNotes), true);
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            assertFalse(components.complete(), answer);
            assertFalse(components.requestCovered(), answer);
            assertTrue(components.components().stream().noneMatch(ReadOnlyAnswerComponents.Component::met));
            return components.present(answer, UnaryOperator.identity(), 20_000);
        });
        LocalTurnTrace trace = settle(outcome.finalAnswer());
        assertLimited(outcome);
        assertEquals(6, JSON.readTree(turn.candidate()).path("version").asInt());
        assertFalse(outcome.finalAnswer().contains("`22.2`"));
        assertEquals(request, outcome.taskOutcome().contract().originalUserRequest());
        assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
        assertNoCompleteOutcome(trace);
        assertSourcesUnchanged(workspace, sources(withNotes));
    }

    @Test
    void fifthObservedHousekeepingDocumentCannotInvalidateTheSelectedFormula(@TempDir Path workspace) throws Exception {
        String request = REQUEST.replace("Read README.md,", "Read README.md, notes.md,");
        Turn turn = turn(workspace, request, null, sources(true));
        assertEquals(List.of("README.md", "index.html", "notes.md", "script.js", "scripts.js"),
                turn.loop().readOnlyFinalizationEvidence().sources().stream().map(ReadOnlyFinalizationEvidence.Source::path).toList());

        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> answer);
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertComplete(outcome);
        assertUsefulAnswer(outcome.finalAnswer());
        assertTraceStatus(trace, "COMPLETE", "READ_ONLY_ANSWERED");
        assertSourcesUnchanged(workspace, sources(true));
    }

    @Test
    void explicitInputValidationLimitationRetainsTheFourUsefulComponents(@TempDir Path workspace) throws Exception {
        String additional = "Also explain input validation";
        String request = REQUEST + " " + additional + ".";
        Turn turn = atLimit(turn(workspace, request, null, sources(false)));
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            assertFalse(components.complete());
            assertEquals(4, components.components().stream().filter(ReadOnlyAnswerComponents.Component::met).count());
            assertTrue(components.components().stream().anyMatch(item -> item.kind().equals("UNKNOWN") && !item.met()));
            return answer;
        });
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertLimited(outcome);
        assertUsefulAnswer(outcome.finalAnswer());
        assertTrue(turn.loop().hitIterLimit());
        assertTrue(outcome.finalAnswer().contains("unsupported result decision was supplied"), outcome.finalAnswer());
        assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
        assertNoCompleteOutcome(trace);
    }

    @Test
    void selectedRouteNeverFallsBackToSelectorCompatibleIncorrectProviderProse(@TempDir Path workspace) throws Exception {
        for (String request : List.of(REQUEST, "Please " + REQUEST,
                REQUEST.replace(DIAGNOSIS, "Explain exactly why the Calculate button is broken"))) {
            for (String wrong : List.of("22.19", "99")) {
                String candidate = "`#calc` is missing from index.html. The correct selector is `#calculate`. "
                        + "BMI for 72 kg and 1.8 m is " + wrong + ". "
                        + "index.html links script.js; scripts.js is not linked by that document.";
                Turn turn = turn(workspace, request, candidate, sources(false));
                ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> answer);
                LocalTurnTrace trace = settle(outcome.finalAnswer());

                assertLimited(outcome);
                assertNotEquals(candidate, outcome.finalAnswer());
                assertFalse(outcome.finalAnswer().contains("BMI for 72 kg and 1.8 m is " + wrong));
                assertNotEquals("PRESERVED", event(trace, "READ_ONLY_WEB_ANSWER_ASSESSED").data().get("disposition"));
                assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
            }
        }
    }

    @Test
    void transformedCandidateCannotReuseTheOriginalSelectedAssociation(@TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, "Please " + REQUEST, null, sources(false));
        String replacement = "`#calc` is missing from index.html. Use `#calculate`. BMI is 99.";
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(replacement, turn.plan(), turn.messages(),
                turn.loop(), workspace, 0, false, (answer, components) -> answer);
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertLimited(outcome);
        assertNotEquals(replacement, outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("BMI is 99"));
        assertNotEquals("PRESERVED", event(trace, "READ_ONLY_WEB_ANSWER_ASSESSED").data().get("disposition"));
        assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
    }

    @Test
    void lostSourceAssociationKeepsItsSelectedRouteAndCannotRecoverRawProse(@TempDir Path workspace) throws Exception {
        String candidate = "`#calc` is missing from index.html. Use `#calculate`. BMI is 99.";
        Turn turn = turn(workspace, "Please " + REQUEST, candidate, sources(false));
        ReadOnlyFinalizationEvidence invalidated = turn.loop().readOnlyFinalizationEvidence().invalidate();
        assertTrue(invalidated.proposalSelected());
        assertFalse(invalidated.available());
        assertTrue(invalidated.sources().isEmpty());
        Turn invalid = withEvidence(turn, invalidated, turn.loop().toolOutcomes());
        ExecutionOutcome outcome = finish(invalid, workspace, (answer, components) -> answer);
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertLimited(outcome);
        assertFalse(outcome.finalAnswer().contains("BMI is 99"));
        assertNotEquals(candidate, outcome.finalAnswer());
        assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
    }

    @Test
    void deniedReadDominatesAnOtherwiseAdmissibleProposal(@TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, REQUEST, null, sources(false));
        List<ToolCallLoop.ToolOutcome> outcomes = new ArrayList<>(turn.loop().toolOutcomes());
        outcomes.add(new ToolCallLoop.ToolOutcome("talos.read_file", "private.js", false, false, true,
                "Permission was denied.", ""));
        Turn denied = withEvidence(turn, turn.loop().readOnlyFinalizationEvidence(), outcomes);
        ExecutionOutcome outcome = finish(denied, workspace, (answer, components) -> {
            assertFalse(components.active());
            return answer;
        });
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus(), outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertNoExecutionAuthority(outcome);
        assertTrue(outcome.finalAnswer().startsWith("[Read evidence blocked:"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("`22.2`"));
        assertTraceStatus(trace, "BLOCKED", "BLOCKED_BY_POLICY");
    }

    @Test
    void currentDiskReplacementCannotSubstituteForCapturedSourceText(@TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, REQUEST, null, sources(false));
        // Only test-owned temporary files are changed, after source capture.
        Files.writeString(workspace.resolve("README.md"), "The current file contains no formula.\n");
        Files.writeString(workspace.resolve("script.js"), "const unrelated = 99;\n");
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> answer);
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertComplete(outcome);
        assertUsefulAnswer(outcome.finalAnswer());
        assertTraceStatus(trace, "COMPLETE", "READ_ONLY_ANSWERED");
        assertEquals("The current file contains no formula.\n", Files.readString(workspace.resolve("README.md")));
        assertEquals("const unrelated = 99;\n", Files.readString(workspace.resolve("script.js")));
    }

    @Test
    void privacyRemovalDowngradesFinalOutcomeAndCommittedTrace(@TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, REQUEST, null, sources(false));
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            assertTrue(components.complete(), answer);
            String calculation = components.components().stream().filter(item -> item.kind().equals("CALCULATION"))
                    .findFirst().orElseThrow().text();
            return components.present(answer, text -> text.replace(calculation, "[calculation withheld]"), 20_000);
        });
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertLimited(outcome);
        assertTrue(outcome.finalAnswer().contains("[calculation withheld]"));
        assertFalse(outcome.finalAnswer().contains("`22.2`"));
        assertEquals(Boolean.FALSE, event(trace, "READ_ONLY_ANSWER_COMPONENTS_PRESENTED").data().get("deliveredIntact"));
        assertEquals(Boolean.FALSE, event(trace, "READ_ONLY_ANSWER_COMPONENTS_COMMITTED").data().get("deliveredIntact"));
        assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
        assertNoCompleteOutcome(trace);
    }

    @Test
    void executorOutputLimitCannotLeaveCompleteStatusForAnOmittedAnswer(@TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, REQUEST, null, sources(false));
        String answer = shape(turn, workspace, new AssistantTurnExecutor.Options().responseMaxChars(19));
        LocalTurnTrace trace = settle(answer);

        assertEquals("[Answer incomplete]", answer);
        assertEquals(Boolean.FALSE, event(trace, "READ_ONLY_ANSWER_COMPONENTS_PRESENTED").data().get("deliveredIntact"));
        assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
        assertNoCompleteOutcome(trace);
    }

    @Test
    void persistenceRemovalDowngradesPreviouslyIntactProposalComponents(@TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, REQUEST, null, sources(false));
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> answer);
        assertComplete(outcome);
        String committed = outcome.finalAnswer().replace("`22.2`", "[removed during persistence]");
        assertNotEquals(outcome.finalAnswer(), committed);
        LocalTurnTraceCapture.recordAnswerTransformedForRouting(committed,
                LocalTurnTraceCapture.AnswerTransformationReason.SESSION_COMMIT_NORMALIZATION);
        LocalTurnTrace trace = settle(committed);

        assertEquals(Boolean.TRUE, event(trace, "READ_ONLY_ANSWER_COMPONENTS_PRESENTED").data().get("deliveredIntact"));
        assertEquals(Boolean.FALSE, event(trace, "READ_ONLY_ANSWER_COMPONENTS_COMMITTED").data().get("deliveredIntact"));
        assertEquals("sha256:" + ReadOnlyWebDiagnosticResult.textHash(committed),
                event(trace, "READ_ONLY_ANSWER_COMPONENTS_COMMITTED").data().get("answerHash"));
        assertEquals("ADVISORY_ONLY", trace.outcome().status());
        assertEquals("ADVISORY_ONLY", trace.outcome().classification());
        assertEquals("NOT_RUN", trace.outcome().verificationStatus());
    }

    @Test
    void aVersionTwoResponseCannotDowngradeTheSelectedNativeContract(@TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, REQUEST, legacyProposal(DIAGNOSIS, false), sources(false));
        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> answer);
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertLimited(outcome);
        assertFalse(outcome.finalAnswer().contains("`22.2`"));
        assertNotEquals(turn.candidate(), outcome.finalAnswer());
        assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
        assertNoCompleteOutcome(trace);
    }

    @Test
    void aVersionEightDecisionResponseCannotDowngradeTheSelectedEvaluationContract(@TempDir Path workspace) throws Exception {
        Turn turn = turn(workspace, REQUEST, null, sources(false), NativeAnswerProposalTestSupport::decisionCandidate);
        var evidence = turn.loop().readOnlyFinalizationEvidence();
        assertEquals(8, JSON.readTree(turn.candidate()).path("version").asInt());
        assertTrue(NativeReadOnlyAnswerProposal.assessDecisions(turn.candidate(), evidence.requestMeaning(), evidence.sources())
                .components().complete(), "The historical candidate must remain valid under its own v8 contract");

        ExecutionOutcome outcome = finish(turn, workspace, (answer, components) -> {
            assertFalse(components.complete(), answer);
            assertTrue(components.components().stream().noneMatch(ReadOnlyAnswerComponents.Component::met));
            return answer;
        });
        LocalTurnTrace trace = settle(outcome.finalAnswer());

        assertLimited(outcome);
        assertFalse(outcome.finalAnswer().contains("`22.2`"), outcome.finalAnswer());
        assertNotEquals(turn.candidate(), outcome.finalAnswer());
        assertTraceStatus(trace, "ADVISORY_ONLY", "ADVISORY_ONLY");
        assertNoCompleteOutcome(trace);
        assertSourcesUnchanged(workspace, sources(false));
    }

    private static String legacyProposal(String diagnosis, boolean withNotes) throws Exception {
        Map<String, Object> calculation = new LinkedHashMap<>();
        calculation.put("formulaOrigin", "SOURCE");
        calculation.put("formulaReference", Map.of("sourceId", "s0", "uniqueQuote", "weight divided by height squared"));
        calculation.put("operands", List.of(
                Map.of("variable", "weight", "inputId", "weight", "requestQuote", "72 kg"),
                Map.of("variable", "height", "inputId", "height", "requestQuote", "1.8 m")));
        calculation.put("precisionRequestQuote", null);
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("version", 2);
        proposal.put("results", List.of(
                Map.of("kind", "DIAGNOSIS", "requestQuote", diagnosis),
                Map.of("kind", "SELECTOR", "requestQuote", "give the correct selector"),
                Map.of("kind", "CALCULATION", "requestQuote", "the BMI for 72 kg and 1.8 m", "calculation", calculation),
                Map.of("kind", "SCRIPT_DISTINCTION", "requestQuote", "distinguish the two JavaScript files",
                        "sourceIds", withNotes ? List.of("s3", "s4") : List.of("s2", "s3"))));
        proposal.put("target", Map.of("buttonId", "calculate", "requestQuote", "Calculate button"));
        return JSON.writeValueAsString(proposal);
    }

    private static Map<String, String> sources(boolean withNotes) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("README.md", README);
        sources.put("index.html", HTML);
        if (withNotes) sources.put("notes.md", NOTES);
        sources.put("script.js", SCRIPT);
        sources.put("scripts.js", SENTINEL);
        return sources;
    }

    private static Turn turn(Path workspace, String request, String candidate, Map<String, String> sources) throws Exception {
        return turn(workspace, request, candidate, sources, false);
    }

    private static Turn turn(Path workspace, String request, String candidate, Map<String, String> sources,
                             boolean interpreted) throws Exception {
        return turn(workspace, request, candidate, sources, (meaning, observed) -> interpreted
                ? NativeAnswerProposalTestSupport.interpretedCandidate(request, observed, request.indexOf("Explain"))
                : evaluationCandidate(meaning, observed));
    }

    private static Turn turn(Path workspace, String request, String candidate, Map<String, String> sources,
                             BiFunction<ReadOnlyRequestMeaning, List<ReadOnlyFinalizationEvidence.Source>, String> candidateFactory)
            throws Exception {
        for (var source : sources.entrySet()) Files.writeString(workspace.resolve(source.getKey()), source.getValue());
        LocalTurnTraceCapture.begin("proposal-integration", "local-test", 1,
                "2026-09-13T00:00:00Z", "workspace-hash", "ask", "test", "test-model", request);
        // Retain the actual resolver result. In particular, do not force DIAGNOSE_ONLY
        // or add fixture targets: those changes hid the original routing failure.
        var contract = WorkspaceTargetReconciler.reconcile(TaskContractResolver.fromUserRequest(request), workspace);
        assertEquals(request, contract.originalUserRequest());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
        TurnTaskContractCapture.set(contract);
        CurrentTurnPlan plan = CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT,
                List.of("talos.read_file"), List.of("talos.read_file"), List.of());
        List<ChatMessage> messages = List.of(ChatMessage.system("sys"), ChatMessage.user(request));
        String supplied = candidate;
        var captured = ReadOnlyFinalizationEvidenceTestSupport.captured(workspace, contract, sources,
                (meaning, observed) -> supplied != null ? supplied : candidateFactory.apply(meaning, observed));
        candidate = captured.answer();
        ReadOnlyFinalizationEvidence evidence = captured.evidence();
        assertTrue(evidence.available(), evidence.reason().name());
        assertTrue(evidence.proposalSelected(), "Complete eligible observations must select the proposal route before candidate parsing.");
        assertTrue(evidence.matches(candidate, workspace, contract));
        assertEquals(sources.keySet().stream().sorted().toList(),
                evidence.sources().stream().map(ReadOnlyFinalizationEvidence.Source::path).toList());
        var outcomes = new ArrayList<ToolCallLoop.ToolOutcome>();
        for (var source : sources.entrySet()) outcomes.add(new ToolCallLoop.ToolOutcome("talos.read_file", source.getKey(),
                true, false, false, source.getValue(), ""));
        var loop = new ToolCallLoop.LoopResult(candidate, outcomes.size(), outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(), messages,
                0, 0, false, 0, List.copyOf(sources.keySet()), 0, 0, 0, 0, null,
                outcomes, Map.of(), false, ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, null, evidence);
        return new Turn(candidate, plan, messages, loop);
    }

    private static String evaluationCandidate(ReadOnlyRequestMeaning meaning,
                                               List<ReadOnlyFinalizationEvidence.Source> sources) {
        // Authored selections retain the historical fixture helper without changing its v8 contract.
        var candidate = NativeAnswerProposalTestSupport.decisionProposal(meaning, sources);
        try {
            var schema = JSON.readTree(NativeReadOnlyAnswerProposal.evaluationSchema(meaning, sources));
            candidate.set("version", schema.at("/properties/version/enum/0"));
            candidate.set("catalogId", schema.at("/properties/catalogId/enum/0"));
            return candidate.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("The runtime evaluation schema must be valid JSON", invalid);
        }
    }

    private static Turn withEvidence(Turn turn, ReadOnlyFinalizationEvidence evidence, List<ToolCallLoop.ToolOutcome> outcomes) {
        var prior = turn.loop();
        int failed = (int) outcomes.stream().filter(outcome -> !outcome.success()).count();
        var loop = new ToolCallLoop.LoopResult(turn.candidate(), prior.iterations(), outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(), prior.messages(),
                failed, 0, false, 0, prior.readPaths(), 0, 0, 0, 0, null,
                outcomes, Map.of(), false, ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, null, evidence);
        return new Turn(turn.candidate(), turn.plan(), turn.messages(), loop);
    }

    private static Turn atLimit(Turn turn) {
        var prior = turn.loop();
        var loop = new ToolCallLoop.LoopResult(turn.candidate(), prior.iterations(), prior.toolsInvoked(),
                prior.toolNames(), prior.messages(), prior.failedCalls(), prior.retriedCalls(), true, 0,
                prior.readPaths(), 0, 0, 0, 0, prior.failureDecision(), prior.toolOutcomes(), Map.of(), false,
                prior.readOnlySynthesisStatus(), prior.readOnlyEvidenceHandoff(), prior.readOnlyFinalizationEvidence());
        return new Turn(turn.candidate(), turn.plan(), turn.messages(), loop);
    }

    private static ExecutionOutcome finish(Turn turn, Path workspace,
                                            BiFunction<String, ReadOnlyAnswerComponents, String> presentation) {
        return ExecutionOutcome.fromToolLoop(turn.candidate(), turn.plan(), turn.messages(), turn.loop(),
                workspace, 0, false, presentation);
    }

    private static String shape(Turn turn, Path workspace, AssistantTurnExecutor.Options options) throws Exception {
        var method = AssistantTurnExecutor.class.getDeclaredMethod("shapeAnswerAfterToolLoop", String.class,
                List.class, CurrentTurnPlan.class, ToolCallLoop.LoopResult.class, Path.class,
                int.class, boolean.class, AssistantTurnExecutor.Options.class);
        method.setAccessible(true);
        return (String) method.invoke(null, turn.candidate(), turn.messages(), turn.plan(), turn.loop(),
                workspace, 0, false, options);
    }

    private static void assertUsefulAnswer(String answer) {
        assertTrue(answer.contains("#calc"), answer);
        assertTrue(answer.contains("#calculate"), answer);
        assertTrue(answer.contains("200/9"), answer);
        assertTrue(answer.contains("`22.2`"), answer);
        assertTrue(answer.contains("script.js"), answer);
        assertTrue(answer.contains("scripts.js"), answer);
        assertFalse(answer.contains("22.19"), answer);
    }

    private static void assertComplete(ExecutionOutcome outcome) {
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
        assertNoExecutionAuthority(outcome);
    }

    private static void assertLimited(ExecutionOutcome outcome) {
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus(), outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertNoExecutionAuthority(outcome);
    }

    private static void assertNoExecutionAuthority(ExecutionOutcome outcome) {
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        assertEquals(TaskVerificationStatus.NOT_RUN, outcome.taskOutcome().verificationResult().status());
        assertTrue(outcome.verificationReport().authoritativeProofKinds().isEmpty());
        assertFalse(outcome.mutationRequested());
        assertEquals(0, outcome.taskOutcome().mutationOutcome().successCount());
    }

    private static LocalTurnTrace settle(String answer) {
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(answer);
        LocalTurnTraceCapture.recordOutcomeIfAbsent("OK", "NOT_RUN", "UNKNOWN", "UNKNOWN", "FALLBACK_MUST_NOT_REPLACE_RECORDED_OUTCOME");
        return LocalTurnTraceCapture.complete();
    }

    private static void assertTraceStatus(LocalTurnTrace trace, String status, String classification) {
        assertEquals(status, trace.outcome().status());
        assertEquals(classification, trace.outcome().classification());
        assertEquals("NOT_RUN", trace.outcome().verificationStatus());
        assertEquals(status, event(trace, "OUTCOME_EVALUATED").data().get("status"));
        assertEquals(classification, event(trace, "OUTCOME_EVALUATED").data().get("classification"));
        assertEquals(status, event(trace, "OUTCOME_RENDERED").data().get("status"));
    }

    private static TurnTraceEvent event(LocalTurnTrace trace, String type) {
        List<TurnTraceEvent> found = trace.events().stream().filter(event -> event.type().equals(type)).toList();
        assertEquals(1, found.size(), type + ": " + found);
        return found.getFirst();
    }

    private static void assertNoCompleteOutcome(LocalTurnTrace trace) {
        assertTrue(trace.events().stream().filter(event -> event.type().equals("OUTCOME_EVALUATED")
                        || event.type().equals("OUTCOME_RENDERED"))
                .noneMatch(event -> "COMPLETE".equals(event.data().get("status"))));
    }

    private static void assertHash(String text, int length, String expectedHash) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertEquals(length, bytes.length);
        assertEquals(expectedHash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private static void assertSourcesUnchanged(Path workspace, Map<String, String> expected) throws Exception {
        for (var source : expected.entrySet()) assertEquals(source.getValue(), Files.readString(workspace.resolve(source.getKey())), source.getKey());
    }

    private record Turn(String candidate, CurrentTurnPlan plan, List<ChatMessage> messages, ToolCallLoop.LoopResult loop) { }
}
