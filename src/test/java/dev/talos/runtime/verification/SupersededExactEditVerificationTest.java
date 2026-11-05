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
 * An exact edit that a later change of the same file follows (open item F12 of P18, P19, D13 option A). Requests go
 * through the real task contract resolver, expectation resolver, obligation compiler, static verifier and outcome
 * selector. The tool outcomes and their producer preimages are constructed as the execution stage now retains them:
 * every successful file mutation carries the file as it found it.
 */
class SupersededExactEditVerificationTest {
    private static final String R1 =
            "In config.ini, change only the SERVER port from 8080 to 9000. Keep the CLIENT port at 8080.";
    private static final String R_UNIQUE = "In config.ini, change the SERVER port from 8080 to 9000.";
    private static final String R_NOTES = "Update notes.md.";
    private static final String PRIOR = "[SERVER]\nport=8080\n[CLIENT]\nport=8080\n";
    private static final String WRONG = "[SERVER]\nport=9001\n[CLIENT]\nport=8080\n";
    private static final String SERVER_ONLY = "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n";
    private static final String UNIQUE_PRIOR = "[SERVER]\nport=8080\n[CLIENT]\nport=7070\n";
    private static final String ONE_REMAINS =
            "Requested replacement not verified: config.ini held the old text 2 times before the change "
                    + "and 1 remains.";

    @TempDir
    Path workspace;

    private final AtomicInteger cases = new AtomicInteger();

    @Test
    void aWrongValueFixedByASecondEditIsStatedWithoutAProblem() throws Exception {
        Verified verified = verify(R1, "config.ini", SERVER_ONLY, List.of(
                edit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9001", existing("config.ini", PRIOR)),
                edit("config.ini", "[SERVER]\nport=9001", "[SERVER]\nport=9000", existing("config.ini", WRONG))));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, verified.result().status(), verified.result().toString());
        assertEquals("Target/readback checks passed for 1 mutated target(s). " + ONE_REMAINS,
                verified.result().summary());
        assertEquals(List.of(), verified.result().problems());
        assertTrue(verified.result().facts().contains(
                "config.ini: exact edit replacement observed before a later change of the same file altered it."),
                verified.result().facts().toString());
        assertEquals("NOT_ASSERTED", onlyEvent(verified, "EXPECTATION_VERIFIED").get("status"));
        assertEquals("PRODUCER_PREIMAGE", onlyEvent(verified, "REPLACEMENT_OCCURRENCE_SCOPE_RECORDED")
                .get("priorSource"));
        assertTrue(verified.result().replacementNotAsserted(), verified.result().toString());
        assertTrue(verified.result().exactEditNotAsserted(), verified.result().toString());
    }

    @Test
    void aWrongValueFixedOnAUniquePriorIsTheRequestBoundReplacementPass() throws Exception {
        Verified verified = verify(R_UNIQUE, "config.ini", "[SERVER]\nport=9000\n[CLIENT]\nport=7070\n", List.of(
                edit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9001", existing("config.ini", UNIQUE_PRIOR)),
                edit("config.ini", "[SERVER]\nport=9001", "[SERVER]\nport=9000",
                        existing("config.ini", "[SERVER]\nport=9001\n[CLIENT]\nport=7070\n"))));

        assertEquals(TaskVerificationStatus.PASSED, verified.result().status(), verified.result().toString());
        assertEquals("Replacement verification passed.", verified.result().summary());
        assertTrue(verified.result().requestBound(), verified.result().toString());
        assertEquals("PASSED", onlyEvent(verified, "EXPECTATION_VERIFIED").get("status"));
    }

    @Test
    void anEditFollowedByAWriteOfTheSameFileIsNotFailedByTheEdit() throws Exception {
        Verified verified = verify(R1, "config.ini", SERVER_ONLY, List.of(
                edit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9001", existing("config.ini", PRIOR)),
                write("config.ini", existing("config.ini", WRONG))));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, verified.result().status(), verified.result().toString());
        assertEquals("Target/readback checks passed for 1 mutated target(s). " + ONE_REMAINS,
                verified.result().summary());
        assertEquals(List.of(), verified.result().problems());
    }

    @Test
    void aPreimageOnALaterOutcomeNeverBecomesThePrior() throws Exception {
        // The earliest outcome that touches the target has no preimage, so the prior comes from the P17 evidence rule,
        // which knows no prior for two mutations. A preimage on the later outcome changes no replacement result.
        List<ToolCallLoop.ToolOutcome> withLater = List.of(
                edit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9001", MutationPreimage.none()),
                edit("config.ini", "[SERVER]\nport=9001", "[SERVER]\nport=9000", existing("config.ini", WRONG)));
        List<ToolCallLoop.ToolOutcome> withoutLater = List.of(
                withLater.get(0),
                edit("config.ini", "[SERVER]\nport=9001", "[SERVER]\nport=9000", MutationPreimage.none()));

        Verified with = verify(R1, "config.ini", SERVER_ONLY, withLater);
        Verified without = verify(R1, "config.ini", SERVER_ONLY, withoutLater);

        assertEquals(without.events("EXPECTATION_VERIFIED"), with.events("EXPECTATION_VERIFIED"));
        assertEquals(List.of(), with.events("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED"));
        assertEquals(without.result().status(), with.result().status());
        assertEquals(without.result().problems(), with.result().problems());
    }

    @Test
    void anUndoChainWithoutARequestExpectationIsAReadbackResult() throws Exception {
        Verified verified = verify(R_NOTES, "notes.md", "status=old\n", List.of(
                edit("notes.md", "status=old", "status=new", existing("notes.md", "status=old\n")),
                edit("notes.md", "status=new", "status=old", existing("notes.md", "status=new\n"))));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, verified.result().status(), verified.result().toString());
        assertEquals("Target/readback checks passed for 1 mutated target(s).", verified.result().summary());
        assertEquals(List.of(), verified.result().problems());
        assertTrue(verified.result().exactEditNotAsserted(), verified.result().toString());
        assertFalse(verified.result().replacementNotAsserted(), verified.result().toString());
    }

    @Test
    void twoEditsOfSeparateRegionsKeepTheChecksPass() throws Exception {
        Verified verified = verify(R_NOTES, "notes.md", "title: Final\nstatus=new\n", List.of(
                edit("notes.md", "title: Draft", "title: Final", existing("notes.md", "title: Draft\nstatus=old\n")),
                edit("notes.md", "status=old", "status=new", existing("notes.md", "title: Final\nstatus=old\n"))));

        assertEquals(TaskVerificationStatus.PASSED, verified.result().status(), verified.result().toString());
        assertEquals("Exact edit replacement checks passed.", verified.result().summary());
        assertFalse(verified.result().requestBound());
    }

    private Verified verify(String request, String path, String finalContent, List<ToolCallLoop.ToolOutcome> outcomes)
            throws Exception {
        Path root = workspace.resolve("case-" + cases.incrementAndGet());
        Files.createDirectories(root);
        Files.writeString(root.resolve(path), finalContent, StandardCharsets.UTF_8);
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        LocalTurnTraceCapture.begin("trc-p19-superseded", "session-test", 1, "2026-11-05T00:00:00Z",
                "workspace-hash", "auto", "llama_cpp", "test", request);
        try {
            TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                    root, contract, obligation, loopResult(outcomes), 0);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            return new Verified(evidence.compatibilityResult(), trace.events());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    private record Verified(TaskVerificationResult result, List<TurnTraceEvent> trace) {
        List<Map<String, Object>> events(String type) {
            return trace.stream().filter(event -> type.equals(event.type())).map(TurnTraceEvent::data).toList();
        }
    }

    private static Map<String, Object> onlyEvent(Verified verified, String type) {
        List<Map<String, Object>> events = verified.events(type);
        assertEquals(1, events.size(), type + " " + events);
        return events.getFirst();
    }

    private static MutationPreimage existing(String path, String text) {
        return MutationPreimage.existing(path, Hash.sha256Hex(text.getBytes(StandardCharsets.UTF_8)), text);
    }

    private static ToolCallLoop.ToolOutcome edit(
            String path, String oldString, String newString, MutationPreimage preimage) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file", path, true, true, false,
                "Edited " + path, "", VerificationStatus.PASS, "", null,
                ToolMutationEvidence.exactEdit(oldString, newString), ToolFailureReason.NONE, List.of(),
                "call-edit-" + oldString.hashCode(), ToolMutationEffects.applied(path), null, preimage);
    }

    private static ToolCallLoop.ToolOutcome write(String path, MutationPreimage preimage) {
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
