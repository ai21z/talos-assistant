package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.expectation.BulletListExpectation;
import dev.talos.runtime.expectation.ReplacementExpectation;
import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.outcome.StaticVerificationAnswerRenderer;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A requested replacement whose old text occurred more than once before the change is not asserted (finding F1, P17,
 * D11 option A). Requests go through the real task contract resolver, expectation resolver, obligation compiler, static
 * verifier and outcome selector. The tool outcomes and their mutation evidence are constructed.
 */
class ReplacementOccurrenceScopeVerificationTest {
    private static final String R1 =
            "In config.ini, change only the SERVER port from 8080 to 9000. Keep the CLIENT port at 8080.";
    private static final String R1_REST =
            "In config.ini, change only the SERVER port from 8080 to 9000. Leave everything else unchanged.";
    private static final String PRIOR = "[SERVER]\nport=8080\n[CLIENT]\nport=8080\n";
    private static final String SERVER_ONLY = "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n";
    private static final String CLIENT_ONLY = "[SERVER]\nport=8080\n[CLIENT]\nport=9000\n";
    private static final String BOTH = "[SERVER]\nport=9000\n[CLIENT]\nport=9000\n";
    private static final String ONE_REMAINS =
            "Requested replacement not verified: config.ini held the old text 2 times before the change "
                    + "and 1 remains.";
    private static final String NONE_REMAIN =
            "Requested replacement not verified: config.ini held the old text 2 times before the change "
                    + "and none remain.";
    private static final String R1_EXPECTED_HASH =
            "old:6c237681e70921603a306be9a1a5d9833fce5c1e268f52b1650970eaad0dce21;"
                    + "new:c4fe6b6dbe94790f232013154cb80fc5dd3ec9106d433492f20f038b1ce25656";
    private static final String NOTES_EXPECTED_HASH =
            "old:d398b29d3dbbb9bf201d4c7e1c19ff9d43c15fd45a0cec46fbe9885ec3f6e97f;"
                    + "new:6557739a67283a8de383fc5c0997fbec7c5721a46f28f3235fc9607598d9016b";
    /** Hashes of the observed presence states, as recorded on 8a90ab8e (probe-head.txt). */
    private static final String OLD_PRESENT_NEW_PRESENT =
            "c7513c9a2ecbf523b8807c195405fe69cd303040c03001d919dce1435fb14229";
    private static final String OLD_ABSENT_NEW_PRESENT =
            "f9e8a962dedfbfcb805ad5cc86fdd59510e2424de2fa6c418a7c83b10d1a274a";
    private static final String OLD_PRESENT_NEW_ABSENT =
            "59d0c8991ecf20ff7b2c6c2fc5242d1f69fdf55a943db9028e2ce5dee8e8dfff";

    @TempDir
    Path workspace;

    @Test
    void theSessionEditIsNotFailedAndStatesItsCountsOnAChecksPass() throws Exception {
        Verified verified = verify(R1, "config.ini", SERVER_ONLY,
                exactEdit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9000"));

        assertEquals(TaskVerificationStatus.PASSED, verified.result().status(), verified.result().toString());
        assertEquals(TaskVerificationResult.ProofScope.CHECKS, verified.result().proofScope());
        assertEquals("Exact edit replacement checks passed. " + ONE_REMAINS, verified.result().summary());
        assertTrue(verified.result().problems().isEmpty(), verified.result().problems().toString());
        assertFalse(verified.receiptProves());
        assertEquals(List.of(expectationEvent("config.ini", "NOT_ASSERTED", "replacement-scoped-prefix-change-to",
                R1_EXPECTED_HASH, 4, OLD_PRESENT_NEW_PRESENT)), verified.events("EXPECTATION_VERIFIED"));
        assertEquals(List.of(scopeEvent("config.ini", "replacement-scoped-prefix-change-to", 2, 1, true, "YES")),
                verified.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
    }

    @Test
    void replacingBothPortsIsNoLongerARequestBoundPass() throws Exception {
        Verified written = verify(R1, "config.ini", BOTH, fullWrite("config.ini", PRIOR, BOTH));
        Verified edited = verify(R1, "config.ini", BOTH,
                exactEdit("config.ini", "port=8080\n[CLIENT]\nport=8080", "port=9000\n[CLIENT]\nport=9000"));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, written.result().status(), written.result().toString());
        assertFalse(written.result().requestBound());
        assertEquals("Target/readback checks passed for 1 mutated target(s). " + NONE_REMAIN,
                written.result().summary());
        assertEquals(TaskVerificationStatus.PASSED, edited.result().status(), edited.result().toString());
        assertEquals(TaskVerificationResult.ProofScope.CHECKS, edited.result().proofScope());
        assertEquals("Exact edit replacement checks passed. " + NONE_REMAIN, edited.result().summary());
        assertEquals(List.of(scopeEvent("config.ini", "replacement-scoped-prefix-change-to", 2, 0, true, "YES")),
                written.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
    }

    @Test
    void changingTheOtherOccurrenceGetsTheSameStatedLimit() throws Exception {
        // The runtime cannot tell which occurrence the request meant. The count makes the uncertainty visible.
        Verified verified = verify(R1, "config.ini", CLIENT_ONLY,
                exactEdit("config.ini", "[CLIENT]\nport=8080", "[CLIENT]\nport=9000"));

        assertEquals(TaskVerificationStatus.PASSED, verified.result().status(), verified.result().toString());
        assertFalse(verified.result().requestBound());
        assertEquals("Exact edit replacement checks passed. " + ONE_REMAINS, verified.result().summary());
    }

    @Test
    void theCompleteObligationParaphraseIsNotAssertedWithoutAReceipt() throws Exception {
        TaskContract contract = TaskContractResolver.fromUserRequest(R1_REST);
        assertEquals(RequestObligation.DerivationStatus.COMPLETE,
                RequestObligationCompiler.compile(contract).derivationStatus());

        Verified verified = verify(R1_REST, "config.ini", SERVER_ONLY,
                exactEdit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9000"));

        assertEquals(TaskVerificationStatus.PASSED, verified.result().status(), verified.result().toString());
        assertEquals(TaskVerificationResult.ProofScope.CHECKS, verified.result().proofScope());
        assertEquals("Exact edit replacement checks passed. " + ONE_REMAINS, verified.result().summary());
        assertTrue(verified.result().problems().isEmpty(), verified.result().problems().toString());
        assertFalse(verified.receiptProves());
        assertEquals(List.of(expectationEvent("config.ini", "NOT_ASSERTED", "request-obligation-v1",
                R1_EXPECTED_HASH, 4, OLD_PRESENT_NEW_PRESENT)), verified.events("EXPECTATION_VERIFIED"));
        assertEquals(List.of(scopeEvent("config.ini", "request-obligation-v1", 2, 1, true, "YES")),
                verified.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
    }

    @Test
    void theCompleteObligationStillFailsWhenAnotherLineChanged() throws Exception {
        String extra = SERVER_ONLY + "host=x\n";

        Verified verified = verify(R1_REST, "config.ini", extra, fullWrite("config.ini", PRIOR, extra));

        assertEquals(TaskVerificationStatus.FAILED, verified.result().status(), verified.result().toString());
        assertEquals("Replacement verification failed.", verified.result().summary());
        // Review finding F5: for a repeated prior a remaining occurrence is not a failure cause, so it is not stated
        // and a repair is not steered toward replacing every occurrence.
        assertEquals(List.of(
                        "config.ini: replacement preservation changed content beyond the requested text.",
                        "config.ini: request-obligation postcondition failed: "
                                + "STRUCTURED_MUTATION_EVIDENCE_UNAVAILABLE."),
                verified.result().problems());
        assertEquals(List.of(scopeEvent("config.ini", "request-obligation-v1", 2, 1, true, "NO")),
                verified.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
        assertEquals(List.of(expectationEvent("config.ini", "FAILED", "request-obligation-v1",
                R1_EXPECTED_HASH, 4, OLD_PRESENT_NEW_PRESENT)), verified.events("EXPECTATION_VERIFIED"));
    }

    @Test
    void theCompleteObligationWithEveryOccurrenceReplacedIsNotAsserted() throws Exception {
        Verified verified = verify(R1_REST, "config.ini", BOTH, fullWrite("config.ini", PRIOR, BOTH));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, verified.result().status(), verified.result().toString());
        assertEquals("Target/readback checks passed for 1 mutated target(s). " + NONE_REMAIN,
                verified.result().summary());
        assertFalse(verified.receiptProves());
    }

    @Test
    void anOverlappingNewTextIsHandledExactly() throws Exception {
        Verified verified = verify(
                "In src/App.java, change only the returned greeting from hello to hello atlas.",
                "src/App.java",
                "String a = \"hello\";\nreturn \"hello atlas\";\n",
                exactEdit("src/App.java", "return \"hello\"", "return \"hello atlas\""));

        assertEquals(TaskVerificationStatus.PASSED, verified.result().status(), verified.result().toString());
        assertEquals("Exact edit replacement checks passed. Requested replacement not verified: src/App.java "
                + "held the old text 2 times before the change and 1 remains.", verified.result().summary());
        assertEquals(List.of(scopeEvent("src/App.java", "replacement-scoped-prefix-change-to", 2, 1, true, "YES")),
                verified.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
    }

    @Test
    void anUnscopedReplacementOfOneOfTwoOccurrencesIsTheStatedCostOfOptionA() throws Exception {
        Verified verified = verify("Replace 2023 with 2024 in notes.md.", "notes.md", "a 2024\nb 2023\n",
                fullWrite("notes.md", "a 2023\nb 2023\n", "a 2024\nb 2023\n"));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, verified.result().status(), verified.result().toString());
        assertEquals("Target/readback checks passed for 1 mutated target(s). Requested replacement not verified: "
                + "notes.md held the old text 2 times before the change and 1 remains.", verified.result().summary());
        assertTrue(verified.result().problems().isEmpty());
    }

    @Test
    void aRepeatedChangeThatDidNotLandStaysFailed() throws Exception {
        Verified added = verify("Replace 2023 with 2024 in notes.md.", "notes.md", "a 2023\nb 2023\n2024\n",
                fullWrite("notes.md", "a 2023\nb 2023\n", "a 2023\nb 2023\n2024\n"));
        Verified missing = verify("Replace 2023 with 2024 in notes.md.", "notes.md", "a 2025\nb 2023\n",
                fullWrite("notes.md", "a 2023\nb 2023\n", "a 2025\nb 2023\n"));

        assertEquals(TaskVerificationStatus.FAILED, added.result().status(), added.result().toString());
        assertEquals(List.of("notes.md: replacement new text did not replace a prior occurrence of the old text."),
                added.result().problems());
        assertEquals(List.of(expectationEvent("notes.md", "FAILED", "replacement-replace-with-in-target",
                NOTES_EXPECTED_HASH, 4, OLD_PRESENT_NEW_PRESENT)), added.events("EXPECTATION_VERIFIED"));
        assertEquals(TaskVerificationStatus.FAILED, missing.result().status(), missing.result().toString());
        assertEquals(List.of("notes.md: replacement new text was not observed after apply."),
                missing.result().problems());
    }

    @Test
    void aWrongValueBesidePreExistingNewTextStaysFailed() throws Exception {
        // Review finding F1(a): the new text was already present, and the edited occurrence got another value. The
        // prior file, reconstructed from the exact edit, is the session fixture plus [ADMIN] with port=9000.
        String after = "[SERVER]\nport=9001\n[CLIENT]\nport=8080\n[ADMIN]\nport=9000\n";

        Verified verified = verify(R1, "config.ini", after,
                exactEdit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9001"));

        assertEquals(TaskVerificationStatus.FAILED, verified.result().status(), verified.result().toString());
        assertEquals("Replacement verification failed.", verified.result().summary());
        assertEquals(List.of("config.ini: replacement new text did not replace a prior occurrence of the old text."),
                verified.result().problems());
        assertEquals(List.of(scopeEvent("config.ini", "replacement-scoped-prefix-change-to", 2, 1, false, "NO")),
                verified.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
    }

    @Test
    void oldTextInsidePriorNewTextKeepsTheUniqueResult() throws Exception {
        // Review finding F1(b): the hello inside the existing hello atlas is not an occurrence, so the prior is UNIQUE
        // and the results of 8a90ab8e stand.
        String request = "Replace hello with hello atlas in src/App.java.";
        Verified scoped = verify(request, "src/App.java", "a=\"hello atlas\"; b=\"hello atlas\"; c=1",
                exactEdit("src/App.java", "b=\"hello\"", "b=\"hello atlas\""));
        Verified untouched = verify(request, "src/App.java", "a=\"hello atlas\"; b=\"hello\"; c=2",
                exactEdit("src/App.java", "c=1", "c=2"));

        assertTrue(scoped.result().requestBound(), scoped.result().toString());
        assertEquals("Replacement verification passed.", scoped.result().summary());
        assertEquals(TaskVerificationStatus.FAILED, untouched.result().status(), untouched.result().toString());
        assertEquals(List.of("src/App.java: replacement old text remained after apply."),
                untouched.result().problems());
        assertEquals(List.of(), scoped.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
        assertEquals(List.of(), untouched.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
    }

    @Test
    void aPassingExpectationOnTheSameTargetDoesNotLendItsWordingToTheReplacement() throws Exception {
        // Review finding F2: the replacement summary is selected only by a replacement that was asserted.
        String request = "Replace 2023 with 2024 in notes.md and keep exactly two bullets.";
        List<TaskExpectation> expectations =
                TaskExpectationResolver.resolve(TaskContractResolver.fromUserRequest(request));
        assertTrue(expectations.stream().anyMatch(ReplacementExpectation.class::isInstance), expectations.toString());
        assertTrue(expectations.stream().anyMatch(BulletListExpectation.class::isInstance), expectations.toString());

        Verified verified = verify(request, "notes.md", "- 2024 a\n- 2023 b\n",
                fullWrite("notes.md", "- 2023 a\n- 2023 b\n", "- 2024 a\n- 2023 b\n"));

        assertEquals(TaskVerificationStatus.PASSED, verified.result().status(), verified.result().toString());
        assertEquals(TaskVerificationResult.ProofScope.CHECKS, verified.result().proofScope());
        assertEquals("Bullet count verification passed. Requested replacement not verified: notes.md held the old "
                + "text 2 times before the change and 1 remains.", verified.result().summary());
    }

    @Test
    void theCountsSurviveACommandUpgrade() throws Exception {
        // Review finding F3: the statement is applied after the command upgrade, which replaces the summary.
        Path root = workspace.resolve("command");
        Files.createDirectories(root);
        Files.writeString(root.resolve("config.ini"), BOTH, StandardCharsets.UTF_8);
        TaskContract contract = TaskContractResolver.fromUserRequest(R1);

        TaskVerificationResult result = StaticTaskVerifier.verifyWithEvidence(
                root,
                contract,
                RequestObligationCompiler.compile(contract),
                loopResult(List.of(
                        fullWrite("config.ini", PRIOR, BOTH),
                        new ToolCallLoop.ToolOutcome("talos.run_command", "", true, false, false,
                                "Command succeeded: gradle_check exited with code 0 after 1234ms.", ""))),
                0).compatibilityResult();

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.toString());
        assertEquals(TaskVerificationResult.ProofScope.CHECKS, result.proofScope());
        assertEquals("Command checks passed: gradle_check exited 0. " + NONE_REMAIN, result.summary());
        assertTrue(result.replacementNotAsserted());
    }

    @Test
    void theReadbackAnnotationDoesNotClaimThatNoVerifierApplied() throws Exception {
        // Review finding F6: the reason clause comes from the typed flag, not from summary text.
        Path root = workspace.resolve("render");
        Files.createDirectories(root);
        Files.writeString(root.resolve("config.ini"), SERVER_ONLY, StandardCharsets.UTF_8);
        TaskContract contract = TaskContractResolver.fromUserRequest(R1);
        ToolCallLoop.LoopResult loop = loopResult(List.of(fullWrite("config.ini", PRIOR, SERVER_ONLY)));

        TaskVerificationResult result = StaticTaskVerifier.verifyWithEvidence(
                root, contract, RequestObligationCompiler.compile(contract), loop, 0).compatibilityResult();
        String annotation = StaticVerificationAnswerRenderer.readbackOnlyAnnotation(result, loop);

        assertEquals(TaskVerificationStatus.READBACK_ONLY, result.status(), result.toString());
        assertTrue(result.replacementNotAsserted());
        assertEquals("[File write/readback passed. A requested replacement could not be assessed, so task completion "
                + "was not verified. Target/readback checks passed for 1 mutated target(s). " + ONE_REMAINS + "]\n\n",
                annotation);
        assertFalse(annotation.contains("No task-specific verifier was applicable"), annotation);
    }

    @Test
    void theUniqueAndUnknownScopesKeepTheirResultsAndEventData() throws Exception {
        Verified unique = verify("Replace 2023 with 2024 in notes.md.", "notes.md", "Release 2024\n",
                exactEdit("notes.md", "Release 2023", "Release 2024"));
        Verified absentNew = verify("Replace 2023 with 2024 in notes.md.", "notes.md", "Release 2025\n",
                fullWrite("notes.md", "Release 2023\n", "Release 2025\n"));
        Verified absentBefore = verify("Replace 2023 with 2024 in notes.md.", "notes.md", "Release 2024\n",
                fullWrite("notes.md", "Release 2022\n", "Release 2024\n"));
        Verified unknown = verify(R1, "config.ini", SERVER_ONLY, plainWrite("config.ini"));
        Verified unknownBoth = verify(R1, "config.ini", BOTH, plainWrite("config.ini"));

        assertTrue(unique.result().requestBound(), unique.result().toString());
        assertEquals("Replacement verification passed.", unique.result().summary());
        assertEquals(List.of(expectationEvent("notes.md", "PASSED", "replacement-replace-with-in-target",
                NOTES_EXPECTED_HASH, 4, OLD_ABSENT_NEW_PRESENT)), unique.events("EXPECTATION_VERIFIED"));
        assertEquals(TaskVerificationStatus.FAILED, absentNew.result().status());
        assertEquals(List.of(expectationEvent("notes.md", "FAILED", "replacement-replace-with-in-target",
                NOTES_EXPECTED_HASH, 4, OLD_PRESENT_NEW_ABSENT)), absentNew.events("EXPECTATION_VERIFIED"));
        // ABSENT_BEFORE is recorded, not changed: the new text appeared, so it passes as on 8a90ab8e.
        assertTrue(absentBefore.result().requestBound(), absentBefore.result().toString());
        // A write without full read evidence has no established prior and keeps the global absence check.
        assertEquals(TaskVerificationStatus.FAILED, unknown.result().status());
        assertEquals("Replacement verification failed.", unknown.result().summary());
        assertTrue(unknownBoth.result().requestBound(), unknownBoth.result().toString());
        for (Verified verified : List.of(unique, absentNew, absentBefore, unknown, unknownBoth)) {
            assertEquals(List.of(), verified.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
            assertFalse(verified.result().replacementNotAsserted(), verified.result().toString());
        }
    }

    @Test
    void aNotAssertedReplacementLeavesAnotherTargetsFailureFailed() throws Exception {
        Path root = workspace.resolve("mixed");
        Files.createDirectories(root);
        Files.writeString(root.resolve("config.ini"), SERVER_ONLY, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("more.md"), "status2=old\n", StandardCharsets.UTF_8);
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Use talos.edit_file twice. First replace 8080 with 9000 in config.ini. "
                        + "Then replace status2=old with status2=new in more.md.");

        TaskVerificationResult result = StaticTaskVerifier.verifyWithEvidence(
                root,
                contract,
                RequestObligationCompiler.compile(contract),
                loopResult(List.of(
                        exactEdit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9000"),
                        plainWrite("more.md"))),
                0).compatibilityResult();

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.toString());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.startsWith("more.md: ")),
                result.problems().toString());
        assertTrue(result.problems().stream().noneMatch(problem -> problem.startsWith("config.ini: replacement")),
                result.problems().toString());
        assertFalse(result.summary().contains("Requested replacement not verified"), result.summary());
    }

    private Verified verify(String request, String file, String finalContent, ToolCallLoop.ToolOutcome outcome)
            throws Exception {
        Path root = workspace.resolve("case-" + Math.abs((request + file + finalContent).hashCode()));
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, finalContent, StandardCharsets.UTF_8);
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        LocalTurnTraceCapture.begin("trc-p17-scope", "session-test", 1, "2026-11-04T00:00:00Z",
                "workspace-hash", "auto", "llama_cpp", "test", request);
        try {
            TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                    root, contract, obligation, loopResult(List.of(outcome)), 0);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            return new Verified(
                    evidence.compatibilityResult(),
                    evidence.requestObligationReceipt().proves(obligation),
                    trace.events());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    private record Verified(TaskVerificationResult result, boolean receiptProves, List<TurnTraceEvent> trace) {
        List<Map<String, Object>> events(String type) {
            return trace.stream().filter(event -> type.equals(event.type())).map(TurnTraceEvent::data).toList();
        }
    }

    private static Map<String, Object> expectationEvent(
            String path, String status, String sourcePattern, String expectedHash, int newLength,
            String observedHash) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "TEXT_REPLACEMENT");
        data.put("status", status);
        data.put("pathHint", path);
        data.put("sourcePattern", sourcePattern);
        data.put("expectedHash", expectedHash);
        data.put("expectedBytes", newLength);
        data.put("expectedChars", newLength);
        data.put("expectedLines", 0);
        data.put("observedHash", observedHash);
        data.put("observedBytes", 0);
        data.put("observedChars", 0);
        data.put("observedLines", 0);
        return data;
    }

    private static Map<String, Object> scopeEvent(
            String path, String sourcePattern, int prior, int remaining, boolean landed, String substitution) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pathHint", path);
        data.put("sourcePattern", sourcePattern);
        data.put("scopeKind", "REPEATED");
        data.put("priorOccurrences", prior);
        data.put("remainingOccurrences", remaining);
        data.put("landed", landed);
        data.put("substitution", substitution);
        return data;
    }

    private static ToolCallLoop.ToolOutcome exactEdit(String path, String oldString, String newString) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file", path, true, true, false,
                "edited " + path, "", VerificationStatus.PASS, "",
                null,
                ToolMutationEvidence.exactEdit(oldString, newString),
                ToolFailureReason.NONE,
                List.of(),
                "call-1-1");
    }

    private static ToolCallLoop.ToolOutcome fullWrite(String path, String previous, String content) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file", path, true, true, false,
                "wrote " + path, "", VerificationStatus.PASS, "",
                null,
                ToolMutationEvidence.fullWriteReplacement(previous, content),
                ToolFailureReason.NONE,
                List.of(),
                "call-1-1");
    }

    private static ToolCallLoop.ToolOutcome plainWrite(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file", path, true, true, false,
                "wrote " + path, "", VerificationStatus.PASS);
    }

    private static ToolCallLoop.LoopResult loopResult(List<ToolCallLoop.ToolOutcome> outcomes) {
        int successes = (int) outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(ToolCallLoop.ToolOutcome::success)
                .count();
        return new ToolCallLoop.LoopResult(
                "Done.", 1, outcomes.size(), List.of("talos.edit_file"), List.of(),
                0, 0, false, successes, List.of(),
                0, 0, 0, 0, outcomes);
    }
}
