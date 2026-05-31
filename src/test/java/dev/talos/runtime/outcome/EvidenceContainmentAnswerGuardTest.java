package dev.talos.runtime.outcome;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceContainmentAnswerGuardTest {
    private static final EvidenceContainmentAnswerGuard.AnswerMarkers MARKERS =
            new EvidenceContainmentAnswerGuard.AnswerMarkers(
                    List.of(
                            "[Read-only denied]",
                            "[Streaming no-tool mutation]",
                            "[Malformed tool protocol]",
                            "[Denied mutation]",
                            "[Policy denied mutation]",
                            "[Mixed denied mutation]",
                            "[Invalid mutation]"),
                    "[Grounding check: ",
                    "[Capability correction: local workspace access available]");

    @Test
    void readTargetMissingEvidenceSuppressesFabricatedAnswerBody() {
        String answer = EvidenceContainmentAnswerGuard.containMissingEvidence(
                "README.md says Talos is complete. Proposed change: add docs.",
                readTargetPlan("README.md"),
                EvidenceObligation.READ_TARGET_REQUIRED,
                EvidenceObligationVerifier.Result.unsatisfied("No tool evidence was gathered."),
                MARKERS);

        assertEquals("""
                [Evidence incomplete: required workspace evidence was not gathered in this turn.]

                I did not inspect the required workspace target this turn, so I cannot answer from its contents or propose grounded changes yet. Required target(s): README.md.""",
                answer);
        assertFalse(answer.contains("Talos is complete"), answer);
        assertFalse(answer.contains("Proposed change"), answer);
    }

    @Test
    void pathExistenceMissingEvidenceSuppressesFabricatedExistenceAnswer() {
        String answer = EvidenceContainmentAnswerGuard.containMissingEvidence(
                "scripts.js does not exist and script.js exists.",
                pathExistencePlan(),
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED,
                EvidenceObligationVerifier.Result.unsatisfied(
                        "Path existence evidence was not gathered for scripts.js."),
                MARKERS);

        assertTrue(answer.startsWith(EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX), answer);
        assertTrue(answer.contains(
                "I did not gather directory or target-read evidence for the requested path existence check"),
                answer);
        assertTrue(answer.contains("Required target(s):"), answer);
        assertTrue(answer.contains("scripts.js"), answer);
        assertTrue(answer.contains("script.js"), answer);
        assertFalse(answer.contains("scripts.js does not exist"), answer);
        assertFalse(answer.contains("script.js exists"), answer);
    }

    @Test
    void protectedReadNotAttemptedSuppressesFabricatedProtectedBody() {
        String answer = EvidenceContainmentAnswerGuard.containMissingEvidence(
                "API_KEY=pretend-secret",
                readTargetPlan(".env"),
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                EvidenceObligationVerifier.Result.unsatisfied(
                        "Protected read was not attempted; no approval prompt ran and no protected content was read."),
                MARKERS);

        assertTrue(answer.startsWith("[Protected read not attempted:"), answer);
        assertTrue(answer.contains("talos.read_file for the protected target"), answer);
        assertTrue(answer.contains("no approval prompt ran"), answer);
        assertTrue(answer.contains("Required target(s): .env."), answer);
        assertFalse(answer.contains("API_KEY"), answer);
    }

    @Test
    void protectedReadIncompleteSuppressesFabricatedProtectedBody() {
        String answer = EvidenceContainmentAnswerGuard.containMissingEvidence(
                "The file says SECRET=original.",
                readTargetPlan(".env"),
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                EvidenceObligationVerifier.Result.unsatisfied(
                        "Required successful read evidence was not gathered."),
                MARKERS);

        assertTrue(answer.startsWith("[Protected read incomplete:"), answer);
        assertTrue(answer.contains("talos.read_file was attempted"), answer);
        assertTrue(answer.contains("No protected content was read from this turn."), answer);
        assertFalse(answer.contains("SECRET=original"), answer);
    }

    @Test
    void dominantRuntimeContainmentPassesThroughWithoutEvidencePrefix() {
        String dominant = "[Denied mutation] No file was changed.";

        String answer = EvidenceContainmentAnswerGuard.containMissingEvidence(
                dominant,
                readTargetPlan("README.md"),
                EvidenceObligation.READ_TARGET_REQUIRED,
                EvidenceObligationVerifier.Result.unsatisfied("No tool evidence was gathered."),
                MARKERS);

        assertEquals(dominant, answer);
    }

    @Test
    void runtimeFailureStatusIsPrefixedButNotReplaced() {
        String failure = "[Tool loop stopped by failure policy: repeated tool failures. "
                + "Review the latest tool errors before retrying.]";

        String answer = EvidenceContainmentAnswerGuard.containMissingEvidence(
                failure,
                readTargetPlan("README.md"),
                EvidenceObligation.READ_TARGET_REQUIRED,
                EvidenceObligationVerifier.Result.unsatisfied("No tool evidence was gathered."),
                MARKERS);

        assertEquals("""
                [Evidence incomplete: required workspace evidence was not gathered in this turn.]

                [Tool loop stopped by failure policy: repeated tool failures. Review the latest tool errors before retrying.]""",
                answer);
    }

    @Test
    void ungroundedAnswerKeepsOnlySafeRuntimeBodyUnderEvidencePrefix() {
        String answer = EvidenceContainmentAnswerGuard.containMissingEvidence(
                "[Grounding check: insufficient evidence]\n\nREADME.md says fabricated facts.",
                readTargetPlan("README.md"),
                EvidenceObligation.READ_TARGET_REQUIRED,
                EvidenceObligationVerifier.Result.unsatisfied("No tool evidence was gathered."),
                MARKERS);

        assertEquals("""
                [Evidence incomplete: required workspace evidence was not gathered in this turn.]

                [Grounding check: I did not inspect the required workspace evidence this turn, so I cannot answer from workspace facts yet.""",
                answer);
        assertFalse(answer.contains("fabricated facts"), answer);
    }

    @Test
    void capabilityLimitationIsPreservedUnderEvidencePrefix() {
        String limitation = "Talos cannot extract PDF contents with the current local text-tool surface.";

        String answer = EvidenceContainmentAnswerGuard.containMissingEvidence(
                limitation,
                readTargetPlan("report.pdf"),
                EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED,
                EvidenceObligationVerifier.Result.unsatisfied("Unsupported capability evidence was not gathered."),
                MARKERS);

        assertEquals("""
                [Evidence incomplete: required workspace evidence was not gathered in this turn.]

                Talos cannot extract PDF contents with the current local text-tool surface.""",
                answer);
    }

    private static CurrentTurnPlan readTargetPlan(String target) {
        TaskContract contract = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of(target),
                Set.of(),
                "Read " + target + ".");
        return CurrentTurnPlan.create(
                contract,
                ExecutionPhase.INSPECT,
                List.of("talos.read_file"),
                List.of("talos.read_file"),
                List.of());
    }

    private static CurrentTurnPlan pathExistencePlan() {
        TaskContract contract = new TaskContract(
                TaskType.DIAGNOSE_ONLY,
                false,
                false,
                false,
                Set.of("scripts.js", "script.js"),
                Set.of(),
                "Check whether scripts.js exists and whether script.js exists. Do not change anything.");
        return CurrentTurnPlan.create(
                contract,
                ExecutionPhase.INSPECT,
                List.of("talos.list_dir", "talos.read_file"),
                List.of("talos.list_dir", "talos.read_file"),
                List.of());
    }
}
