package dev.talos.runtime.verification;

import dev.talos.core.util.Hash;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.tools.MutationPreimage;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolMutationEffects;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The producer preimage as the prior of a requested replacement (open item F8 of P17, P18, D12 option B). Requests go
 * through the real task contract resolver, expectation resolver, obligation compiler, static verifier and outcome
 * selector. The tool outcomes, their mutation evidence and their preimages are constructed. Each case without a
 * repeated prior is compared with the same outcomes without a preimage, which must give the same result and trace data.
 */
class ReplacementPriorPreimageVerificationTest {
    private static final String R1 =
            "In config.ini, change only the SERVER port from 8080 to 9000. Keep the CLIENT port at 8080.";
    private static final String R1_REST =
            "In config.ini, change only the SERVER port from 8080 to 9000. Leave everything else unchanged.";
    private static final String R_UNIQUE = "In config.ini, change the SERVER port from 8080 to 9000.";
    private static final String PRIOR = "[SERVER]\nport=8080\n[CLIENT]\nport=8080\n";
    private static final String UNIQUE_PRIOR = "[SERVER]\nport=8080\n[CLIENT]\nport=7070\n";
    private static final String SERVER_ONLY = "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n";
    private static final String BOTH = "[SERVER]\nport=9000\n[CLIENT]\nport=9000\n";
    private static final String ONE_REMAINS =
            "Requested replacement not verified: config.ini held the old text 2 times before the change "
                    + "and 1 remains.";
    private static final String NONE_REMAIN =
            "Requested replacement not verified: config.ini held the old text 2 times before the change "
                    + "and none remain.";

    @TempDir
    Path workspace;

    private final AtomicInteger cases = new AtomicInteger();

    @Test
    void aWriteWithoutAReadIsStatedWithItsCountsInsteadOfFailed() throws Exception {
        Verified verified = verify(R1, SERVER_ONLY, plainWrite("config.ini", existing(PRIOR)));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, verified.result().status(), verified.result().toString());
        assertEquals("Target/readback checks passed for 1 mutated target(s). " + ONE_REMAINS,
                verified.result().summary());
        assertEquals(List.of(), verified.result().problems());
        assertTrue(verified.result().replacementNotAsserted());
        assertEquals("NOT_ASSERTED", onlyExpectation(verified).get("status"));
        assertEquals(scope(2, 1, true, "YES"), verified.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
    }

    @Test
    void aWriteWithoutAReadThatChangesBothPortsIsNotARequestBoundPass() throws Exception {
        Verified verified = verify(R1, BOTH, plainWrite("config.ini", existing(PRIOR)));

        assertFalse(verified.result().requestBound(), verified.result().toString());
        assertEquals("Target/readback checks passed for 1 mutated target(s). " + NONE_REMAIN,
                verified.result().summary());
        assertEquals(scope(2, 0, true, "YES"), verified.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
    }

    @Test
    void aWriteWithoutAReadThatReplacedNoOccurrenceStaysFailed() throws Exception {
        String after = PRIOR + "port=9000\n";
        Verified verified = verify(R1, after, plainWrite("config.ini", existing(PRIOR)));

        assertEquals(TaskVerificationStatus.FAILED, verified.result().status(), verified.result().toString());
        assertEquals(List.of("config.ini: replacement new text did not replace a prior occurrence of the old text."),
                verified.result().problems());
        assertEquals(scope(2, 2, false, "NO"), verified.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
    }

    @Test
    void twoEditsThatChangeBothPortsAreNotARequestBoundPass() throws Exception {
        Verified verified = verify(R1, BOTH, List.of(
                exactEdit("[SERVER]\nport=8080", "[SERVER]\nport=9000", existing(PRIOR)),
                exactEdit("[CLIENT]\nport=8080", "[CLIENT]\nport=9000", MutationPreimage.none())));

        assertEquals(TaskVerificationStatus.PASSED, verified.result().status(), verified.result().toString());
        assertEquals(TaskVerificationResult.ProofScope.CHECKS, verified.result().proofScope());
        assertEquals("Exact edit replacement checks passed. " + NONE_REMAIN, verified.result().summary());
        assertEquals(scope(2, 0, true, "YES"), verified.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
    }

    @Test
    void aWrongValueFixedByASecondEditNoLongerFailsTheReplacement() throws Exception {
        Verified verified = verify(R1, SERVER_ONLY, List.of(
                exactEdit("[SERVER]\nport=8080", "[SERVER]\nport=9001", existing(PRIOR)),
                exactEdit("[SERVER]\nport=9001", "[SERVER]\nport=9000", MutationPreimage.none())));

        assertEquals("NOT_ASSERTED", onlyExpectation(verified).get("status"));
        assertEquals(scope(2, 1, true, "YES"), verified.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
        // F12, fixed by P19 (D13 option A): the second edit carries no preimage here, so the first edit, which the
        // second one changed, is not observed and not asserted instead of failing the turn.
        assertEquals(TaskVerificationStatus.READBACK_ONLY, verified.result().status(), verified.result().toString());
        assertEquals("Target/readback checks passed for 1 mutated target(s). " + ONE_REMAINS,
                verified.result().summary());
        assertEquals(List.of(), verified.result().problems());
    }

    @Test
    void theProducerPriorAgreesWithTheReconstructedPriorOfASingleEdit() throws Exception {
        ToolCallLoop.ToolOutcome edit = exactEdit("[SERVER]\nport=8080", "[SERVER]\nport=9000", existing(PRIOR));
        Verified produced = verify(R1, SERVER_ONLY, List.of(edit));
        Verified reconstructed = verify(R1, SERVER_ONLY, List.of(withoutPreimage(edit)));

        assertEquals(reconstructed.result(), produced.result());
        assertEquals(onlyExpectation(reconstructed), onlyExpectation(produced));
        assertEquals(scope(2, 1, true, "YES"), produced.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
        assertEquals("EXACT_EDIT_EVIDENCE",
                reconstructed.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED").getFirst().get("priorSource"));
    }

    @Test
    void theCompleteObligationKeepsTheEvidencePrior() throws Exception {
        // Option C of D12 is not taken. The complete obligation uses the evidence prior for its occurrence scope and its
        // final-state before content alike (review finding F5), so a preimage changes none of its results, and a write
        // without a read still cannot prove that the rest was kept.
        Verified verified = verify(R1_REST, SERVER_ONLY, plainWrite("config.ini", existing(PRIOR)));

        assertEquals(TaskVerificationStatus.FAILED, verified.result().status(), verified.result().toString());
        assertTrue(verified.result().problems().contains(
                "config.ini: request-obligation postcondition failed: STRUCTURED_MUTATION_EVIDENCE_UNAVAILABLE."),
                verified.result().problems().toString());
        assertFalse(verified.receiptProves());
        assertSameAsWithoutPreimage(R1_REST, SERVER_ONLY, List.of(plainWrite("config.ini", existing(PRIOR))));
        assertSameAsWithoutPreimage(R1_REST, SERVER_ONLY, List.of(
                exactEdit("[SERVER]\nport=8080", "[SERVER]\nport=9001", existing(PRIOR)),
                exactEdit("[SERVER]\nport=9001", "[SERVER]\nport=9000", MutationPreimage.none())));
        Verified edited = verify(R1_REST, SERVER_ONLY,
                exactEdit("[SERVER]\nport=8080", "[SERVER]\nport=9000", existing(PRIOR)));
        assertEquals("EXACT_EDIT_EVIDENCE",
                edited.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED").getFirst().get("priorSource"));
    }

    @Test
    void thePriorRecordNamesNoContent() {
        TaskExpectationMutationEvidenceVerifier.ReplacementPrior prior =
                new TaskExpectationMutationEvidenceVerifier.ReplacementPrior(
                        PRIOR, TaskExpectationMutationEvidenceVerifier.ReplacementPriorSource.PRODUCER_PREIMAGE);

        assertEquals("ReplacementPrior[known=true, source=PRODUCER_PREIMAGE]", prior.toString());
        assertEquals("ReplacementPrior[known=false, source=NONE]",
                TaskExpectationMutationEvidenceVerifier.ReplacementPrior.UNKNOWN.toString());
    }

    @Test
    void priorsThatAreNotRepeatedKeepTheResultsWithoutAPreimage() throws Exception {
        List<List<ToolCallLoop.ToolOutcome>> sequences = List.of(
                List.of(plainWrite("config.ini", existing(UNIQUE_PRIOR))),
                List.of(exactEdit("[SERVER]\nport=8080", "[SERVER]\nport=9001", existing(UNIQUE_PRIOR)),
                        exactEdit("[SERVER]\nport=9001", "[SERVER]\nport=9000", MutationPreimage.none())));
        for (List<ToolCallLoop.ToolOutcome> sequence : sequences) {
            assertSameAsWithoutPreimage(R_UNIQUE, "[SERVER]\nport=9000\n[CLIENT]\nport=7070\n", sequence);
            assertNoScope(R_UNIQUE, "[SERVER]\nport=9000\n[CLIENT]\nport=7070\n", sequence);
        }
        assertSameAsWithoutPreimage(R1, "[SERVER]\nport=9000\n[CLIENT]\nport=7070\n",
                List.of(plainWrite("config.ini", existing("[SERVER]\nport=7070\n[CLIENT]\nport=7070\n"))));
        assertSameAsWithoutPreimage(R1, SERVER_ONLY,
                List.of(plainWrite("config.ini", MutationPreimage.absent("config.ini"))));
        assertNoScope(R1, SERVER_ONLY, List.of(plainWrite("config.ini", MutationPreimage.absent("config.ini"))));
    }

    private void assertNoScope(String request, String finalContent, List<ToolCallLoop.ToolOutcome> outcomes)
            throws Exception {
        Verified verified = verify(request, finalContent, outcomes);
        assertEquals(List.of(), verified.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
        assertFalse(verified.result().replacementNotAsserted(), verified.result().toString());
    }

    @Test
    void aPreimageOnlyCountsOnTheEarliestOutcomeThatTouchesTheTarget() throws Exception {
        ToolCallLoop.ToolOutcome batch = new ToolCallLoop.ToolOutcome(
                "talos.apply_workspace_batch", "settings", true, true, false,
                "applied batch", "", VerificationStatus.PASS, "", null, ToolMutationEvidence.none(),
                ToolFailureReason.NONE, List.of(), "call-batch", ToolMutationEffects.applied("config.ini"), null,
                MutationPreimage.none());
        assertSameAsWithoutPreimage(R1, SERVER_ONLY, List.of(batch, plainWrite("config.ini", existing(PRIOR))));
        assertNoScope(R1, SERVER_ONLY, List.of(batch, plainWrite("config.ini", existing(PRIOR))));
    }

    @Test
    void aPreimageOfAnotherTargetIsIgnored() throws Exception {
        assertSameAsWithoutPreimage(R1, SERVER_ONLY, List.of(plainWrite("config.ini",
                MutationPreimage.existing("other.ini", sha(PRIOR), PRIOR))));
        assertNoScope(R1, SERVER_ONLY, List.of(plainWrite("config.ini",
                MutationPreimage.existing("other.ini", sha(PRIOR), PRIOR))));
    }

    private void assertSameAsWithoutPreimage(String request, String finalContent,
                                             List<ToolCallLoop.ToolOutcome> outcomes) throws Exception {
        Verified with = verify(request, finalContent, outcomes);
        Verified without = verify(request, finalContent, outcomes.stream().map(this::withoutPreimage).toList());
        assertEquals(without.result(), with.result());
        assertEquals(without.receiptProves(), with.receiptProves());
        assertEquals(without.events("EXPECTATION_VERIFIED"), with.events("EXPECTATION_VERIFIED"));
        assertEquals(without.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"),
                with.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
    }

    private Verified verify(String request, String finalContent, ToolCallLoop.ToolOutcome outcome) throws Exception {
        return verify(request, finalContent, List.of(outcome));
    }

    private Verified verify(String request, String finalContent, List<ToolCallLoop.ToolOutcome> outcomes)
            throws Exception {
        Path root = workspace.resolve("case-" + cases.incrementAndGet());
        Files.createDirectories(root);
        Files.writeString(root.resolve("config.ini"), finalContent, StandardCharsets.UTF_8);
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        LocalTurnTraceCapture.begin("trc-p18-prior", "session-test", 1, "2026-11-04T00:00:00Z",
                "workspace-hash", "auto", "llama_cpp", "test", request);
        try {
            TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                    root, contract, obligation, loopResult(outcomes), 0);
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

    private static Map<String, Object> onlyExpectation(Verified verified) {
        List<Map<String, Object>> events = verified.events("EXPECTATION_VERIFIED");
        assertEquals(1, events.size(), events.toString());
        return events.getFirst();
    }

    private static List<Map<String, Object>> scope(int prior, int remaining, boolean landed, String substitution) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("pathHint", "config.ini");
        data.put("sourcePattern", "replacement-scoped-prefix-change-to");
        data.put("scopeKind", "REPEATED");
        data.put("priorOccurrences", prior);
        data.put("remainingOccurrences", remaining);
        data.put("landed", landed);
        data.put("substitution", substitution);
        data.put("priorSource", "PRODUCER_PREIMAGE");
        return List.of(data);
    }

    private static MutationPreimage existing(String text) {
        return MutationPreimage.existing("config.ini", sha(text), text);
    }

    private static String sha(String text) {
        return Hash.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    private ToolCallLoop.ToolOutcome withoutPreimage(ToolCallLoop.ToolOutcome outcome) {
        return new ToolCallLoop.ToolOutcome(outcome.toolName(), outcome.pathHint(), outcome.success(),
                outcome.mutating(), outcome.denied(), outcome.summary(), outcome.errorMessage(),
                outcome.fileVerificationStatus(), outcome.errorCode(), outcome.workspaceOperationPlan(),
                outcome.mutationEvidence(), outcome.failureReason(), outcome.sourcePaths(), outcome.toolCallId(),
                outcome.mutationEffects(), outcome.directoryObservation());
    }

    private static ToolCallLoop.ToolOutcome exactEdit(String oldString, String newString, MutationPreimage preimage) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file", "config.ini", true, true, false,
                "Edited config.ini", "", VerificationStatus.PASS, "", null,
                ToolMutationEvidence.exactEdit(oldString, newString), ToolFailureReason.NONE, List.of(),
                "call-edit-" + oldString.hashCode(), ToolMutationEffects.applied("config.ini"), null, preimage);
    }

    private static ToolCallLoop.ToolOutcome plainWrite(String path, MutationPreimage preimage) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file", path, true, true, false,
                "Updated " + path, "", VerificationStatus.PASS, "", null,
                ToolMutationEvidence.none(), ToolFailureReason.NONE, List.of(),
                "call-write", ToolMutationEffects.applied(path), null, preimage);
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
