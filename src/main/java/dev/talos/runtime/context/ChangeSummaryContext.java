package dev.talos.runtime.context;

import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.PromptAuditRedactor;
import dev.talos.runtime.toolcall.ToolCallSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Compact runtime-owned ledger for "what files changed?" follow-ups.
 *
 * <p>The source of authority is structured tool-call audit data, not model
 * prose. This keeps changed-files answers tool-free and protected-read safe
 * while preserving useful mutation facts after failed verification.
 */
public record ChangeSummaryContext(
        int schemaVersion,
        List<FileChange> changedFiles,
        List<String> unresolvedTargets,
        String verificationStatus,
        String completionStatus,
        List<String> verifierFindings,
        List<VerificationFailure> unresolvedVerificationFailures
) {
    public static final int SCHEMA_VERSION = 2;
    private static final int MAX_CHANGED_FILES = 20;
    private static final int MAX_UNRESOLVED_TARGETS = 10;
    private static final int MAX_FINDINGS = 5;
    private static final int MAX_FAILURES = 10;
    private static final int MAX_FIELD_CHARS = 300;

    public ChangeSummaryContext(
            int schemaVersion,
            List<FileChange> changedFiles,
            List<String> unresolvedTargets,
            String verificationStatus,
            String completionStatus,
            List<String> verifierFindings
    ) {
        this(schemaVersion, changedFiles, unresolvedTargets, verificationStatus, completionStatus,
                verifierFindings, List.of());
    }

    public ChangeSummaryContext {
        schemaVersion = SCHEMA_VERSION;
        changedFiles = normalizeChanges(changedFiles);
        unresolvedTargets = normalizeStrings(unresolvedTargets, MAX_UNRESOLVED_TARGETS);
        verificationStatus = normalizeText(verificationStatus, MAX_FIELD_CHARS);
        completionStatus = normalizeText(completionStatus, MAX_FIELD_CHARS);
        verifierFindings = normalizeStrings(verifierFindings, MAX_FINDINGS);
        unresolvedVerificationFailures = normalizeVerificationFailures(unresolvedVerificationFailures);
    }

    public record FileChange(String path, String toolName, int turnNumber, String traceId) {
        public FileChange {
            path = normalizePath(path);
            toolName = normalizeText(toolName, MAX_FIELD_CHARS);
            traceId = normalizeText(traceId, MAX_FIELD_CHARS);
        }
    }

    public record VerificationFailure(
            List<String> paths,
            int turnNumber,
            String verificationStatus,
            String completionStatus,
            String traceId,
            List<String> findings
    ) {
        public VerificationFailure {
            paths = normalizePaths(paths, MAX_CHANGED_FILES);
            verificationStatus = normalizeText(verificationStatus, MAX_FIELD_CHARS);
            completionStatus = normalizeText(completionStatus, MAX_FIELD_CHARS);
            traceId = normalizeText(traceId, MAX_FIELD_CHARS);
            findings = normalizeStrings(findings, MAX_FINDINGS);
        }

        VerificationFailure withPaths(List<String> paths) {
            return new VerificationFailure(paths, turnNumber, verificationStatus, completionStatus, traceId, findings);
        }
    }

    public static ChangeSummaryContext none() {
        return new ChangeSummaryContext(
                SCHEMA_VERSION,
                List.of(),
                List.of(),
                "",
                "",
                List.of(),
                List.of());
    }

    public static ChangeSummaryContext updateAfterTurn(ChangeSummaryContext previous, TurnResult result) {
        ChangeSummaryContext current = previous == null ? none() : previous;
        if (result == null || result.audit() == null) return current;

        TurnAudit audit = result.audit();
        List<TurnRecord.ToolCallSummary> calls = audit.toolCalls() == null ? List.of() : audit.toolCalls();
        List<TurnRecord.ToolCallSummary> successfulMutations = calls.stream()
                .filter(call -> call != null && call.success())
                .filter(call -> ToolCallSupport.isMutatingTool(call.name()))
                .filter(call -> !normalizePath(call.pathHint()).isBlank())
                .toList();

        if (successfulMutations.isEmpty()) {
            return current;
        }

        List<String> findings = verifierFindings(audit.localTrace());
        String verificationStatus = verificationStatus(audit.localTrace());
        String completionStatus = completionStatus(audit.localTrace());
        LinkedHashMap<String, FileChange> changes = new LinkedHashMap<>();
        for (FileChange change : current.changedFiles()) {
            if (change == null || change.path().isBlank()) continue;
            changes.put(change.path(), change);
        }

        LinkedHashSet<String> changedThisTurn = new LinkedHashSet<>();
        String traceId = traceId(audit.localTrace());
        for (TurnRecord.ToolCallSummary call : successfulMutations) {
            String path = normalizePath(call.pathHint());
            if (path.isBlank()) continue;
            changes.remove(path);
            changes.put(path, new FileChange(path, call.name(), result.turnNumber(), traceId));
            changedThisTurn.add(path);
        }
        while (changes.size() > MAX_CHANGED_FILES) {
            String first = changes.keySet().iterator().next();
            changes.remove(first);
        }

        List<String> unresolved = unresolvedTargets(audit.policyTrace(), audit.localTrace(), changedThisTurn);
        List<VerificationFailure> unresolvedFailures = updateVerificationFailures(
                current.unresolvedVerificationFailures(),
                changedThisTurn,
                result.turnNumber(),
                traceId,
                verificationStatus,
                completionStatus,
                findings);
        return new ChangeSummaryContext(
                SCHEMA_VERSION,
                List.copyOf(changes.values()),
                unresolved,
                verificationStatus,
                completionStatus,
                findings,
                unresolvedFailures);
    }

    public boolean hasRecordedChanges() {
        return !changedFiles.isEmpty();
    }

    public String renderForChangeSummaryQuestion() {
        if (!hasRecordedChanges()) {
            return "No runtime-recorded file changes are available for this session/audit.";
        }

        StringBuilder out = new StringBuilder();
        out.append("Recorded file changes in this session/audit:\n");
        for (FileChange change : changedFiles) {
            out.append("- ").append(change.path());
            if (change.turnNumber() > 0) out.append(" (turn ").append(change.turnNumber()).append(')');
            if (!change.toolName().isBlank()) out.append(" via ").append(change.toolName());
            out.append('\n');
        }

        if (!completionStatus.isBlank() || !verificationStatus.isBlank()) {
            out.append("\nVerification status: ");
            out.append(verifiedComplete() ? "verified complete" : "not verified complete");
            if (!verificationStatus.isBlank()) out.append(" (").append(verificationStatus).append(')');
            if (!completionStatus.isBlank()) out.append("; outcome=").append(completionStatus);
            out.append(".\n");
        }

        if (!unresolvedTargets.isEmpty()) {
            out.append("\nUnresolved expected targets:\n");
            for (String target : unresolvedTargets) {
                out.append("- ").append(target).append('\n');
            }
        }

        if (!verifierFindings.isEmpty()) {
            out.append("\nVerifier findings:\n");
            for (String finding : verifierFindings) {
                out.append("- ").append(finding).append('\n');
            }
        }

        if (!unresolvedVerificationFailures.isEmpty()) {
            out.append("\nUnresolved verification failures:\n");
            for (VerificationFailure failure : unresolvedVerificationFailures) {
                out.append("- ").append(String.join(", ", failure.paths()));
                if (failure.turnNumber() > 0) out.append(" (turn ").append(failure.turnNumber()).append(')');
                if (!failure.verificationStatus().isBlank()) {
                    out.append(": ").append(failure.verificationStatus());
                }
                out.append('\n');
                for (String finding : failure.findings()) {
                    out.append("  - ").append(finding).append('\n');
                }
            }
        }

        return out.toString().stripTrailing();
    }

    private boolean verifiedComplete() {
        if (!unresolvedTargets.isEmpty() || !unresolvedVerificationFailures.isEmpty()) return false;
        return "PASSED".equalsIgnoreCase(verificationStatus)
                || "COMPLETED_VERIFIED".equalsIgnoreCase(completionStatus);
    }

    private static List<FileChange> normalizeChanges(List<FileChange> rawChanges) {
        if (rawChanges == null || rawChanges.isEmpty()) return List.of();
        LinkedHashMap<String, FileChange> out = new LinkedHashMap<>();
        for (FileChange change : rawChanges) {
            if (change == null || change.path().isBlank()) continue;
            out.remove(change.path());
            out.put(change.path(), change);
            while (out.size() > MAX_CHANGED_FILES) {
                String first = out.keySet().iterator().next();
                out.remove(first);
            }
        }
        return List.copyOf(out.values());
    }

    private static List<VerificationFailure> updateVerificationFailures(
            List<VerificationFailure> previous,
            LinkedHashSet<String> changedThisTurn,
            int turnNumber,
            String traceId,
            String verificationStatus,
            String completionStatus,
            List<String> findings
    ) {
        if (changedThisTurn == null || changedThisTurn.isEmpty()) {
            return normalizeVerificationFailures(previous);
        }

        boolean failed = verificationFailed(verificationStatus, completionStatus);
        boolean passed = verificationPassed(verificationStatus, completionStatus);
        if (!failed && !passed) {
            return normalizeVerificationFailures(previous);
        }

        List<VerificationFailure> updated = new ArrayList<>();
        for (VerificationFailure failure : normalizeVerificationFailures(previous)) {
            List<String> remainingPaths = failure.paths().stream()
                    .filter(path -> !changedThisTurn.contains(path))
                    .toList();
            if (!remainingPaths.isEmpty()) {
                updated.add(failure.withPaths(remainingPaths));
            }
        }
        if (failed) {
            updated.add(new VerificationFailure(
                    List.copyOf(changedThisTurn),
                    turnNumber,
                    verificationStatus,
                    completionStatus,
                    traceId,
                    failureFindings(findings, verificationStatus, completionStatus)));
        }
        while (updated.size() > MAX_FAILURES) {
            updated.removeFirst();
        }
        return List.copyOf(updated);
    }

    private static boolean verificationFailed(String verificationStatus, String completionStatus) {
        return "FAILED".equalsIgnoreCase(verificationStatus)
                || "TASK_INCOMPLETE".equalsIgnoreCase(completionStatus);
    }

    private static boolean verificationPassed(String verificationStatus, String completionStatus) {
        return "PASSED".equalsIgnoreCase(verificationStatus)
                || "COMPLETED_VERIFIED".equalsIgnoreCase(completionStatus);
    }

    private static List<String> failureFindings(
            List<String> findings,
            String verificationStatus,
            String completionStatus
    ) {
        List<String> normalized = normalizeStrings(findings, MAX_FINDINGS);
        if (!normalized.isEmpty()) return normalized;
        String fallback = !normalizeText(verificationStatus, MAX_FIELD_CHARS).isBlank()
                ? "Verification status: " + normalizeText(verificationStatus, MAX_FIELD_CHARS)
                : "Completion status: " + normalizeText(completionStatus, MAX_FIELD_CHARS);
        return normalizeStrings(List.of(fallback), MAX_FINDINGS);
    }

    private static List<VerificationFailure> normalizeVerificationFailures(List<VerificationFailure> failures) {
        if (failures == null || failures.isEmpty()) return List.of();
        List<VerificationFailure> out = new ArrayList<>();
        for (VerificationFailure failure : failures) {
            if (failure == null || failure.paths().isEmpty()) continue;
            out.add(failure);
            if (out.size() == MAX_FAILURES) break;
        }
        return List.copyOf(out);
    }

    private static List<String> unresolvedTargets(
            TurnPolicyTrace policyTrace,
            LocalTurnTrace localTrace,
            LinkedHashSet<String> changedThisTurn) {
        if (changedThisTurn == null || changedThisTurn.isEmpty()) return List.of();
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        if (localTrace != null) addAll(expected, localTrace.taskContract().expectedTargets());
        if (policyTrace != null) addAll(expected, policyTrace.expectedTargets());
        if (expected.isEmpty()) return List.of();
        expected.removeAll(changedThisTurn);
        return normalizeStrings(List.copyOf(expected), MAX_UNRESOLVED_TARGETS);
    }

    private static List<String> verifierFindings(LocalTurnTrace localTrace) {
        if (localTrace == null || localTrace.verification() == null) return List.of();
        List<String> problems = localTrace.verification().problems();
        if (problems != null && !problems.isEmpty()) return normalizeStrings(problems, MAX_FINDINGS);
        String summary = localTrace.verification().summary();
        if (summary == null || summary.isBlank()) return List.of();
        return normalizeStrings(List.of(summary), MAX_FINDINGS);
    }

    private static String verificationStatus(LocalTurnTrace localTrace) {
        if (localTrace == null) return "";
        String status = localTrace.verification().status();
        if (status != null && !status.isBlank()) return normalizeText(status, MAX_FIELD_CHARS);
        return normalizeText(localTrace.outcome().verificationStatus(), MAX_FIELD_CHARS);
    }

    private static String completionStatus(LocalTurnTrace localTrace) {
        if (localTrace == null) return "";
        String classification = localTrace.outcome().classification();
        if (classification != null && !classification.isBlank()) {
            return normalizeText(classification, MAX_FIELD_CHARS);
        }
        return normalizeText(localTrace.outcome().status(), MAX_FIELD_CHARS);
    }

    private static String traceId(LocalTurnTrace localTrace) {
        return localTrace == null ? "" : normalizeText(localTrace.traceId(), MAX_FIELD_CHARS);
    }

    private static void addAll(LinkedHashSet<String> out, List<String> values) {
        if (values == null) return;
        for (String value : values) {
            String normalized = normalizePath(value);
            if (!normalized.isBlank()) out.add(normalized);
        }
    }

    private static List<String> normalizeStrings(List<String> raw, int maxItems) {
        if (raw == null || raw.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String item : raw) {
            String normalized = normalizeText(item, MAX_FIELD_CHARS);
            if (!normalized.isBlank()) out.add(normalized);
            if (out.size() == maxItems) break;
        }
        return List.copyOf(out);
    }

    private static List<String> normalizePaths(List<String> raw, int maxItems) {
        if (raw == null || raw.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String item : raw) {
            String normalized = normalizePath(item);
            if (!normalized.isBlank()) out.add(normalized);
            if (out.size() == maxItems) break;
        }
        return List.copyOf(out);
    }

    private static String normalizePath(String value) {
        String normalized = normalizeText(value, MAX_FIELD_CHARS).replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String normalizeText(String value, int maxChars) {
        if (value == null) return "";
        String normalized = PromptAuditRedactor.preview(value.strip(), maxChars);
        if (normalized.isBlank()) return "";
        return normalized.replaceAll("\\s+", " ").strip();
    }
}
