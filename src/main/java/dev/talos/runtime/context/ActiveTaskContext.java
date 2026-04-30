package dev.talos.runtime.context;

import dev.talos.runtime.trace.PromptAuditRedactor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

public record ActiveTaskContext(
        int schemaVersion,
        State state,
        Kind kind,
        int sourceTurnNumber,
        String sourceTraceId,
        int updatedTurnNumber,
        int expiresAfterTurnNumber,
        List<String> targets,
        Operation operation,
        String proposalSummary,
        String previousOutcomeStatus,
        List<String> verifierFindings,
        String blockedReason,
        String suppressionReason) {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_TARGETS = 5;
    public static final int MAX_PROPOSAL_CHARS = 600;
    public static final int MAX_FINDINGS = 5;
    public static final int MAX_FINDINGS_CHARS = 500;
    public static final int PROMPT_RENDER_CHAR_CAP = 1200;
    public static final String NONE_OR_NOT_DERIVED = "NONE_OR_NOT_DERIVED";

    private static final Pattern API_KEY_TOKEN = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{8,}\\b");

    public ActiveTaskContext {
        schemaVersion = SCHEMA_VERSION;
        state = state == null ? State.NONE : state;
        kind = kind == null ? Kind.NONE : kind;
        sourceTraceId = normalizeText(sourceTraceId, Integer.MAX_VALUE);
        targets = normalizeTargets(targets);
        operation = operation == null ? Operation.NONE : operation;
        proposalSummary = normalizeText(proposalSummary, MAX_PROPOSAL_CHARS);
        previousOutcomeStatus = normalizeText(previousOutcomeStatus, Integer.MAX_VALUE);
        verifierFindings = normalizeFindings(verifierFindings);
        blockedReason = normalizeText(blockedReason, MAX_PROPOSAL_CHARS);
        suppressionReason = normalizeText(suppressionReason, MAX_PROPOSAL_CHARS);
    }

    public enum State { NONE, ACTIVE, SUPPRESSED, CLEARED, EXPIRED }

    public enum Kind { NONE, PROPOSED_CHANGES, VERIFIER_FINDINGS, DENIED_MUTATION, PARTIAL_MUTATION, VERIFIED_MUTATION }

    public enum Operation { NONE, PROPOSE_EDIT, APPLY_EDIT, REPAIR, CREATE, VERIFY, ANSWER_ONLY }

    public static ActiveTaskContext none() {
        return new ActiveTaskContext(
                SCHEMA_VERSION,
                State.NONE,
                Kind.NONE,
                0,
                "",
                0,
                0,
                List.of(),
                Operation.NONE,
                "",
                "",
                List.of(),
                "",
                "");
    }

    public static ActiveTaskContext proposedChanges(
            int turnNumber,
            String traceId,
            List<String> targets,
            String proposalSummary) {
        return new ActiveTaskContext(
                SCHEMA_VERSION,
                State.ACTIVE,
                Kind.PROPOSED_CHANGES,
                turnNumber,
                traceId,
                turnNumber,
                turnNumber + 3,
                targets,
                Operation.APPLY_EDIT,
                proposalSummary,
                "",
                List.of(),
                "",
                "");
    }

    public static ActiveTaskContext verifierFindings(
            int turnNumber,
            String traceId,
            List<String> targets,
            List<String> findings,
            String outcomeStatus) {
        return new ActiveTaskContext(
                SCHEMA_VERSION,
                State.ACTIVE,
                Kind.VERIFIER_FINDINGS,
                turnNumber,
                traceId,
                turnNumber,
                turnNumber + 3,
                targets,
                Operation.REPAIR,
                "",
                outcomeStatus,
                findings,
                "",
                "");
    }

    public static ActiveTaskContext deniedMutation(
            int turnNumber,
            String traceId,
            List<String> targets,
            String blockedReason) {
        return new ActiveTaskContext(
                SCHEMA_VERSION,
                State.ACTIVE,
                Kind.DENIED_MUTATION,
                turnNumber,
                traceId,
                turnNumber,
                turnNumber + 3,
                targets,
                Operation.APPLY_EDIT,
                "",
                "NO_FILES_CHANGED",
                List.of(),
                blockedReason,
                "");
    }

    public ActiveTaskContext suppressed(String reason) {
        return withState(State.SUPPRESSED, reason);
    }

    public ActiveTaskContext cleared(String reason) {
        return withState(State.CLEARED, reason);
    }

    public ActiveTaskContext expired(String reason) {
        return withState(State.EXPIRED, reason);
    }

    public boolean activeAt(int turnNumber) {
        return state == State.ACTIVE && turnNumber <= expiresAfterTurnNumber;
    }

    public boolean hasTargets() {
        return !targets.isEmpty();
    }

    public boolean hasPromptContext() {
        return state != State.NONE;
    }

    public String renderForPlan() {
        if (state == State.NONE) return NONE_OR_NOT_DERIVED;

        StringBuilder sb = new StringBuilder();
        sb.append("activeTaskContext{")
                .append("state=").append(state)
                .append(", kind=").append(kind)
                .append(", operation=").append(operation)
                .append(", sourceTurn=").append(sourceTurnNumber)
                .append(", expiresAfter=").append(expiresAfterTurnNumber);
        if (!sourceTraceId.isBlank()) sb.append(", trace=").append(sourceTraceId);
        if (!targets.isEmpty()) sb.append(", targets=").append(targets);
        if (!proposalSummary.isBlank()) sb.append(", proposal=").append(proposalSummary);
        if (!previousOutcomeStatus.isBlank()) sb.append(", previousOutcome=").append(previousOutcomeStatus);
        if (!verifierFindings.isEmpty()) sb.append(", findings=").append(verifierFindings);
        if (!blockedReason.isBlank()) sb.append(", blocked=").append(blockedReason);
        if (!suppressionReason.isBlank()) sb.append(", reason=").append(suppressionReason);
        sb.append('}');
        return cappedPreview(sb.toString());
    }

    private ActiveTaskContext withState(State newState, String reason) {
        return new ActiveTaskContext(
                schemaVersion,
                newState,
                kind,
                sourceTurnNumber,
                sourceTraceId,
                updatedTurnNumber,
                expiresAfterTurnNumber,
                targets,
                operation,
                proposalSummary,
                previousOutcomeStatus,
                verifierFindings,
                blockedReason,
                reason);
    }

    private static List<String> normalizeTargets(List<String> rawTargets) {
        if (rawTargets == null || rawTargets.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String target : rawTargets) {
            String value = normalizeText(target, Integer.MAX_VALUE);
            if (!value.isBlank()) normalized.add(value);
            if (normalized.size() == MAX_TARGETS) break;
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeFindings(List<String> rawFindings) {
        if (rawFindings == null || rawFindings.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String finding : rawFindings) {
            String value = normalizeText(finding, MAX_FINDINGS_CHARS);
            if (!value.isBlank()) normalized.add(value);
            if (normalized.size() == MAX_FINDINGS) break;
        }
        return List.copyOf(normalized);
    }

    private static String normalizeText(String value, int maxChars) {
        if (value == null) return "";
        String normalized = value.strip();
        if (normalized.length() <= maxChars) return normalized;
        return normalized.substring(0, maxChars);
    }

    private static String cappedPreview(String value) {
        String scrubbed = API_KEY_TOKEN.matcher(value).replaceAll("[redacted]");
        return PromptAuditRedactor.preview(scrubbed, PROMPT_RENDER_CHAR_CAP);
    }
}
