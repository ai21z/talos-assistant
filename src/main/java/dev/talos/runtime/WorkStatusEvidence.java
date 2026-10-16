package dev.talos.runtime;

import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.core.security.Redactor;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.impl.ExecutedEditDetail;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/** A finite metadata projection, not a source or candidate certification. */
public record WorkStatusEvidence(List<Turn> turns, int evictedTurns, boolean memoryPresent) {
    private static final int MAX_CALLS_PER_TURN = 800;
    public static final int MAX_TARGETS_PER_LIST = 32;
    public static final int MAX_TARGET_CHARS = 1024;
    public static final int MAX_TARGET_CHARS_PER_TURN = 16_384;
    public static final int MAX_EDIT_DETAIL_CHARS_PER_TURN = 4096;
    private static final Redactor TARGET_REDACTOR = new Redactor();
    public enum Category { INSPECTION, MUTATION, COMMAND, OTHER }
    public enum Verification { PASSED, FAILED, UNAVAILABLE, NOT_RUN, READBACK_ONLY, UNKNOWN }
    public enum DetailRetention { NOT_RECORDED, RETAINED, WITHHELD, OMITTED }
    public record Call(Category category, TurnRecord.ToolCallDisposition disposition,
                       boolean reportedSuccess, int appliedEntries, int uncertainEntries,
                       ToolFailureReason failureReason, String canonicalToolName,
                       String declaredTarget, List<String> appliedTargets, List<String> uncertainTargets,
                       int withheldTargets, int omittedTargets, boolean targetMetadataWithheld,
                       @JsonIgnore ExecutedEditDetail executedEditDetail, DetailRetention detailRetention) {
        public Call {
            appliedTargets = List.copyOf(appliedTargets);
            uncertainTargets = List.copyOf(uncertainTargets);
            executedEditDetail = executedEditDetail == null ? ExecutedEditDetail.none() : executedEditDetail;
            detailRetention = detailRetention == null ? DetailRetention.NOT_RECORDED : detailRetention;
        }
        public Call(Category category, TurnRecord.ToolCallDisposition disposition,
                    boolean reportedSuccess, int appliedEntries, int uncertainEntries,
                    ToolFailureReason failureReason, String canonicalToolName,
                    String declaredTarget, List<String> appliedTargets, List<String> uncertainTargets,
                    int withheldTargets, int omittedTargets, boolean targetMetadataWithheld) {
            this(category, disposition, reportedSuccess, appliedEntries, uncertainEntries, failureReason,
                    canonicalToolName, declaredTarget, appliedTargets, uncertainTargets,
                    withheldTargets, omittedTargets, targetMetadataWithheld,
                    ExecutedEditDetail.none(), DetailRetention.NOT_RECORDED);
        }
        public Call(Category category, TurnRecord.ToolCallDisposition disposition,
                    boolean reportedSuccess, int appliedEntries, int uncertainEntries,
                    ToolFailureReason failureReason) {
            this(category, disposition, reportedSuccess, appliedEntries, uncertainEntries, failureReason,
                    "", "", List.of(), List.of(), 0, 0, true);
        }
        public int retainedTargets() {
            return (declaredTarget.isEmpty() ? 0 : 1) + appliedTargets.size() + uncertainTargets.size();
        }
        @Override public String toString() {
            return "Call[category=" + category + ", disposition=" + disposition
                    + ", appliedEntries=" + appliedEntries + ", uncertainEntries=" + uncertainEntries
                    + ", detailRetention=" + detailRetention + "]";
        }
    }
    public record Turn(int turnNumber, List<Call> calls, Verification verification, int omittedCalls,
                       String workspaceHash) {
        public Turn {
            calls = List.copyOf(calls);
            workspaceHash = knownWorkspaceHash(workspaceHash);
        }
        public Turn(int turnNumber, List<Call> calls, Verification verification, int omittedCalls) {
            this(turnNumber, calls, verification, omittedCalls, "");
        }
    }

    public WorkStatusEvidence { turns = List.copyOf(turns); }

    static Turn from(TurnResult result) {
        LocalTurnTrace trace = result.audit().localTrace();
        String workspaceHash = knownWorkspaceHash(trace == null ? "" : trace.workspaceHash());
        var restrictions = result.audit().contentRestrictions();
        boolean targetsAllowed = restrictions.modelAllowed() && restrictions.historyAllowed()
                && restrictions.artifactAllowed() && !workspaceHash.isEmpty();
        var budget = new TargetBudget();
        int[] remainingDetailChars = { MAX_EDIT_DETAIL_CHARS_PER_TURN };
        var calls = result.audit().toolCalls().stream().limit(MAX_CALLS_PER_TURN).map(call -> {
            var alias = ToolAliasPolicy.resolve(call.name());
            String name = alias.accepted() ? alias.canonicalToolName() : "";
            Category category = "talos.run_command".equals(name) ? Category.COMMAND
                    : alias.mutating() ? Category.MUTATION
                    : dev.talos.runtime.toolcall.ToolCallSupport.isReadOnlyTool(name)
                    ? Category.INSPECTION : Category.OTHER;
            boolean admitted = targetsAllowed && alias.accepted();
            // Executed multi-path operations may use their first effect as the primary hint.
            // Only these producers preserve a declared primary independently of the result paths.
            boolean declaredPrimary = "talos.edit_file".equals(name) || "talos.write_file".equals(name)
                    || call.executionDisposition() == TurnRecord.ToolCallDisposition.BLOCKED;
            var primary = call.pathHint().isEmpty() ? List.<String>of() : List.of(call.pathHint());
            var declared = admitted && !declaredPrimary
                    ? new Targets(List.of(), 0, primary.size()) : budget.retain(primary, admitted);
            var applied = budget.retain(call.mutationEffects().appliedPaths(), admitted);
            var uncertain = budget.retain(call.mutationEffects().uncertainPaths(), admitted);
            ExecutedEditDetail detail = call.executedEditDetail();
            DetailRetention retention = DetailRetention.NOT_RECORDED;
            if (detail.available()) {
                if (!admitted || !"talos.edit_file".equals(name)
                        || call.executionDisposition() != TurnRecord.ToolCallDisposition.EXECUTED
                        || !detail.associatedWith(call.mutationEffects())
                        || !declared.values().equals(List.of(detail.target()))
                        || !applied.values().equals(List.of(detail.target())) || !uncertain.values().isEmpty()) {
                    retention = DetailRetention.WITHHELD;
                } else if (detail.before().length() + detail.after().length() > remainingDetailChars[0]) {
                    retention = DetailRetention.OMITTED;
                } else {
                    retention = DetailRetention.RETAINED;
                    remainingDetailChars[0] -= detail.before().length() + detail.after().length();
                }
            }
            if (retention != DetailRetention.RETAINED) detail = ExecutedEditDetail.none();
            return new Call(category, call.executionDisposition(), call.success(),
                    call.mutationEffects().appliedPaths().size(),
                    call.mutationEffects().uncertainPaths().size(), call.failureReason(), name,
                    declared.values().isEmpty() ? "" : declared.values().getFirst(),
                    applied.values(), uncertain.values(),
                    declared.withheld() + applied.withheld() + uncertain.withheld(),
                    declared.omitted() + applied.omitted() + uncertain.omitted(), !admitted, detail, retention);
        }).toList();
        Verification verification = Verification.UNKNOWN;
        if (trace != null) {
            try { verification = Verification.valueOf(trace.verification().status()); }
            catch (IllegalArgumentException ignored) { /* Unknown values remain unavailable. */ }
        }
        return new Turn(result.turnNumber(), calls, verification,
                Math.max(0, result.audit().toolCalls().size() - MAX_CALLS_PER_TURN), workspaceHash);
    }

    private record Targets(List<String> values, int withheld, int omitted) {}

    private static final class TargetBudget {
        private int remaining = MAX_TARGET_CHARS_PER_TURN;

        private Targets retain(List<String> supplied, boolean allowed) {
            if (!allowed) return new Targets(List.of(), supplied.size(), 0);
            var values = new ArrayList<String>();
            int withheld = 0;
            int omitted = Math.max(0, supplied.size() - MAX_TARGETS_PER_LIST);
            for (int index = 0; index < Math.min(supplied.size(), MAX_TARGETS_PER_LIST); index++) {
                String target = supplied.get(index);
                if (target.length() > MAX_TARGET_CHARS) {
                    omitted++;
                } else if (!safeTarget(target)) {
                    withheld++;
                } else if (target.length() > remaining) {
                    omitted++;
                } else {
                    values.add(target);
                    remaining -= target.length();
                }
            }
            return new Targets(List.copyOf(values), withheld, omitted);
        }
    }

    /** Admission rejects unsafe identities intact. It never turns a redacted string into a target. */
    private static boolean safeTarget(String target) {
        if (target.isBlank() || !target.equals(target.strip()) || target.startsWith("/")
                || target.contains("\\") || target.contains(":")
                || target.contains(ProtectedContentPolicy.REDACTED_PATH)
                || target.contains("[path]") || target.contains("[secret]") || target.contains("[ip]")
                || target.contains(dev.talos.safety.ContentRestrictions.WITHHELD)
                || ProtectedContentPolicy.looksProtectedPathString(target)
                || !ProtectedContentPolicy.sanitizeText(target).equals(target)
                || !TARGET_REDACTOR.redactBlock(target).equals(target)) return false;
        for (String segment : target.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || !segment.equals(segment.strip())
                    || segment.matches("(?i)[0-9a-f]{40,64}")) return false;
        }
        for (int index = 0; index < target.length(); index++) {
            char value = target.charAt(index);
            int type = Character.getType(value);
            if (Character.isISOControl(value) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) return false;
            if (Character.isHighSurrogate(value)) {
                if (++index >= target.length() || !Character.isLowSurrogate(target.charAt(index))) return false;
            } else if (Character.isLowSurrogate(value)) return false;
        }
        return true;
    }

    private static String knownWorkspaceHash(String value) {
        return value != null && value.matches("[0-9a-f]{40}") ? value : "";
    }
}
