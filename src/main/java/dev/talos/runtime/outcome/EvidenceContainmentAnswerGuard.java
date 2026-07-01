package dev.talos.runtime.outcome;

import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.turn.CurrentTurnPlan;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Renders final-answer containment for unsatisfied current-turn evidence obligations. */
public final class EvidenceContainmentAnswerGuard {
    private EvidenceContainmentAnswerGuard() {
    }

    public record AnswerMarkers(
            List<String> dominantContainmentPrefixes,
            String ungroundedAnnotation,
            String localAccessCapabilityCorrection
    ) {
        public AnswerMarkers {
            dominantContainmentPrefixes = dominantContainmentPrefixes == null
                    ? List.of()
                    : List.copyOf(dominantContainmentPrefixes);
            ungroundedAnnotation = ungroundedAnnotation == null ? "" : ungroundedAnnotation;
            localAccessCapabilityCorrection = localAccessCapabilityCorrection == null
                    ? ""
                    : localAccessCapabilityCorrection;
        }
    }

    public static String containMissingEvidence(
            String answer,
            CurrentTurnPlan plan,
            EvidenceObligation obligation,
            EvidenceObligationVerifier.Result evidenceResult,
            AnswerMarkers markers
    ) {
        EvidenceObligation safeObligation = obligation == null ? EvidenceObligation.NONE : obligation;
        if (safeObligation == EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED) {
            return protectedReadMissingEvidenceContainment(plan, evidenceResult);
        }
        if (isRuntimeFailureStatus(answer)) {
            return missingEvidencePrefix(answer);
        }
        if (isDominantRuntimeContainment(answer, markers)) {
            return answer == null ? "" : answer;
        }
        String runtimeSafeBody = runtimeSafeBodyForMissingEvidence(answer, markers);
        if (runtimeSafeBody != null) {
            return missingEvidencePrefix(runtimeSafeBody);
        }
        return missingEvidencePrefix(missingEvidenceContainmentMessage(plan, safeObligation, evidenceResult));
    }

    public static String missingEvidencePrefix(String answer) {
        String current = answer == null ? "" : answer;
        if (current.startsWith(EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX)) {
            return current;
        }
        return EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX + "\n\n" + current;
    }

    private static String missingEvidenceContainmentMessage(
            CurrentTurnPlan plan,
            EvidenceObligation obligation,
            EvidenceObligationVerifier.Result evidenceResult
    ) {
        return switch (obligation) {
            case PROTECTED_READ_APPROVAL_REQUIRED ->
                    "I did not read protected content this turn. A protected read approval "
                            + "path was required before answering from that file, so no protected "
                            + "file content is available from this turn."
                            + targetSentence(plan);
            case READ_TARGET_REQUIRED ->
                    "I did not inspect the required workspace target this turn, so I cannot "
                            + "answer from its contents or propose grounded changes yet."
                            + targetSentence(plan);
            case PATH_EXISTENCE_EVIDENCE_REQUIRED ->
                    "I did not gather directory or target-read evidence for the requested path "
                            + "existence check, so I cannot answer whether those files exist yet."
                            + targetSentence(plan);
            case LIST_DIRECTORY_ONLY ->
                    "I did not complete a directory-list-only evidence path this turn. "
                            + "I cannot answer with file contents or derived file claims from "
                            + "this turn.";
            case WORKSPACE_INSPECTION_REQUIRED ->
                    "I did not inspect the workspace this turn, so I cannot list files, "
                            + "show file contents, or claim changed files from this turn.";
            case STATIC_WEB_DIAGNOSIS_REQUIRED ->
                    "I did not inspect the required static web files this turn, so I cannot "
                            + "diagnose the page from grounded HTML, CSS, or JavaScript evidence."
                            + evidenceDetailSentence(evidenceResult);
            case VERIFY_FROM_TRACE_OR_EVIDENCE ->
                    "I did not gather trace or workspace evidence this turn, so I cannot "
                            + "verify the requested status from this turn.";
            case UNSUPPORTED_CAPABILITY_CHECK_REQUIRED ->
                    "I did not gather the required unsupported-capability evidence this turn, "
                            + "so I cannot answer from unsupported document contents.";
            case NONE -> "";
        };
    }

    private static String evidenceDetailSentence(EvidenceObligationVerifier.Result evidenceResult) {
        if (evidenceResult == null || evidenceResult.message() == null || evidenceResult.message().isBlank()) {
            return "";
        }
        String message = evidenceResult.message().strip();
        return " " + message;
    }

    private static boolean isDominantRuntimeContainment(String answer, AnswerMarkers markers) {
        if (answer == null || answer.isBlank()) return false;
        AnswerMarkers safeMarkers = markers == null ? new AnswerMarkers(List.of(), "", "") : markers;
        for (String prefix : safeMarkers.dominantContainmentPrefixes()) {
            if (prefix != null && !prefix.isBlank() && answer.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String runtimeSafeBodyForMissingEvidence(String answer, AnswerMarkers markers) {
        if (answer == null || answer.isBlank()) return null;
        AnswerMarkers safeMarkers = markers == null ? new AnswerMarkers(List.of(), "", "") : markers;
        if (!safeMarkers.ungroundedAnnotation().isBlank()
                && answer.startsWith(safeMarkers.ungroundedAnnotation())) {
            return safeMarkers.ungroundedAnnotation()
                    + "I did not inspect the required workspace evidence this turn, "
                    + "so I cannot answer from workspace facts yet.";
        }
        if (!safeMarkers.localAccessCapabilityCorrection().isBlank()
                && answer.startsWith(safeMarkers.localAccessCapabilityCorrection())) {
            return safeMarkers.localAccessCapabilityCorrection();
        }
        if (isCapabilityLimitation(answer)) {
            return answer;
        }
        return null;
    }

    private static boolean isCapabilityLimitation(String answer) {
        String lower = answer.toLowerCase(Locale.ROOT);
        return lower.startsWith("talos cannot extract ")
                || lower.startsWith("i cannot extract ")
                || lower.startsWith("i can't extract ")
                || lower.startsWith("unsupported ");
    }

    private static boolean isRuntimeFailureStatus(String answer) {
        if (answer == null || answer.isBlank()) return false;
        return answer.contains("[Tool loop stopped by failure policy:");
    }

    private static String targetSentence(CurrentTurnPlan plan) {
        TaskContract contract = plan == null ? null : plan.taskContract();
        Set<String> targets = evidenceTargets(contract);
        if (targets.isEmpty()) return "";
        return " Required target(s): " + String.join(", ", targets) + ".";
    }

    private static Set<String> evidenceTargets(TaskContract contract) {
        if (contract == null) return Set.of();
        if (!contract.sourceEvidenceTargets().isEmpty()) {
            return contract.sourceEvidenceTargets();
        }
        return contract.expectedTargets();
    }

    private static String protectedReadMissingEvidenceContainment(
            CurrentTurnPlan plan,
            EvidenceObligationVerifier.Result evidenceResult
    ) {
        String message = evidenceResult == null ? "" : evidenceResult.message();
        if (message.contains("not attempted")) {
            return protectedReadNotAttemptedPrefix(protectedReadNotAttemptedMessage(plan));
        }
        return protectedReadIncompletePrefix(protectedReadIncompleteMessage(plan));
    }

    private static String protectedReadNotAttemptedPrefix(String answer) {
        String current = answer == null ? "" : answer;
        String prefix = "[Protected read not attempted: approval-required read_file tool call was not issued.]";
        if (current.startsWith(prefix)) {
            return current;
        }
        return prefix + "\n\n" + current;
    }

    private static String protectedReadNotAttemptedMessage(CurrentTurnPlan plan) {
        return "The model did not call talos.read_file for the protected target, "
                + "so no approval prompt ran and no protected content was read."
                + targetSentence(plan);
    }

    private static String protectedReadIncompletePrefix(String answer) {
        String current = answer == null ? "" : answer;
        String prefix = "[Protected read incomplete: approval-required read_file tool call did not return content.]";
        if (current.startsWith(prefix)) {
            return current;
        }
        return prefix + "\n\n" + current;
    }

    private static String protectedReadIncompleteMessage(CurrentTurnPlan plan) {
        return "talos.read_file was attempted for the protected target, but protected content "
                + "was not returned successfully. No protected content was read from this turn."
                + targetSentence(plan);
    }
}
