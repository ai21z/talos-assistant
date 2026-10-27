package dev.talos.runtime;

import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.tools.ToolAliasPolicy;

import java.util.ArrayList;
import java.util.List;

/** Bounded runtime effects. Missing entries do not establish that earlier actions were absent. */
public record HistoricalAttributionEvidence(List<Turn> turns, int evictedTurns, boolean memoryPresent) {
    public static final int MAX_TURNS = 200;
    public static final int MAX_CALLS_PER_TURN = 256;
    public static final int MAX_TARGETS_PER_LIST = 32;
    public static final int MAX_TARGET_CHARS = 1024;
    public static final int MAX_TARGET_CHARS_PER_TURN = 16_384;

    public HistoricalAttributionEvidence {
        List<Turn> supplied = turns == null ? List.of() : turns;
        int omitted = Math.max(0, supplied.size() - MAX_TURNS);
        turns = List.copyOf(supplied.subList(omitted, supplied.size()));
        evictedTurns = addCount(evictedTurns, omitted);
    }

    public record Call(String canonicalToolName, TurnRecord.ToolCallDisposition disposition,
                       boolean reportedSuccess, String declaredTarget,
                       List<String> appliedTargets, List<String> uncertainTargets,
                       int omittedAppliedTargets, int omittedUncertainTargets, boolean declaredTargetOmitted) {
        public Call {
            var alias = ToolAliasPolicy.resolve(canonicalToolName);
            canonicalToolName = alias.accepted() ? alias.canonicalToolName() : "";
            disposition = disposition == null ? TurnRecord.ToolCallDisposition.UNKNOWN : disposition;
            String normalizedTarget = normalizeTarget(declaredTarget);
            declaredTargetOmitted = declaredTargetOmitted
                    || declaredTarget != null && !declaredTarget.isBlank() && normalizedTarget.isBlank();
            declaredTarget = normalizedTarget;
            var applied = targets(appliedTargets, Integer.MAX_VALUE);
            var uncertain = targets(uncertainTargets, Integer.MAX_VALUE);
            appliedTargets = applied.values();
            uncertainTargets = uncertain.values();
            omittedAppliedTargets = addCount(omittedAppliedTargets, applied.omitted());
            omittedUncertainTargets = addCount(omittedUncertainTargets, uncertain.omitted());
        }

        public boolean fileEditProducer() {
            return "talos.edit_file".equals(canonicalToolName)
                    || "talos.write_file".equals(canonicalToolName);
        }

        /** Tool execution is not evidence of subprocess launch or test execution. */
        public boolean commandReachedExecution() {
            return "talos.run_command".equals(canonicalToolName)
                    && disposition == TurnRecord.ToolCallDisposition.EXECUTED;
        }
    }

    public record Turn(int turnNumber, String workspaceHash, List<Call> calls, int omittedCalls) {
        public Turn {
            workspaceHash = knownWorkspaceHash(workspaceHash);
            List<Call> supplied = calls == null ? List.of() : calls;
            int retained = Math.min(supplied.size(), MAX_CALLS_PER_TURN);
            omittedCalls = addCount(omittedCalls, supplied.size() - retained);
            List<Call> bounded = new ArrayList<>(retained);
            int remaining = MAX_TARGET_CHARS_PER_TURN;
            for (int index = 0; index < retained; index++) {
                Call call = supplied.get(index);
                boolean declaredOmitted = call.declaredTargetOmitted() || call.declaredTarget().length() > remaining;
                String declared = declaredOmitted ? "" : call.declaredTarget();
                remaining -= declared.length();
                var applied = targets(call.appliedTargets(), remaining);
                remaining -= applied.chars();
                var uncertain = targets(call.uncertainTargets(), remaining);
                remaining -= uncertain.chars();
                bounded.add(new Call(call.canonicalToolName(), call.disposition(), call.reportedSuccess(),
                        declared, applied.values(), uncertain.values(),
                        addCount(call.omittedAppliedTargets(), applied.omitted()),
                        addCount(call.omittedUncertainTargets(), uncertain.omitted()), declaredOmitted));
            }
            calls = List.copyOf(bounded);
        }

        /** Workspace matching uses the runtime's lexical normalized path scope, not physical file identity. */
        public boolean hasAppliedEdit(String requestedWorkspaceHash, String target) {
            String requested = knownWorkspaceHash(requestedWorkspaceHash);
            String path = normalizeTarget(target);
            if (workspaceHash.isBlank() || !workspaceHash.equals(requested) || path.isBlank()
                    || path.startsWith(ProtectedContentPolicy.REDACTED_PATH + "/")) return false;
            return calls.stream().anyMatch(call -> call.fileEditProducer()
                    && call.disposition() == TurnRecord.ToolCallDisposition.EXECUTED
                    && !call.declaredTargetOmitted() && call.declaredTarget().equals(path)
                    && call.appliedTargets().contains(path));
        }
    }

    static Turn from(TurnResult result) {
        var audit = result.audit();
        // Current runtime edit/write audits preserve the request target as their primary path.
        // Restored or legacy summaries are not reconstructed into this in-memory projection.
        var calls = audit.toolCalls().stream().limit(MAX_CALLS_PER_TURN)
                .map(call -> new Call(call.name(), call.executionDisposition(), call.success(),
                        call.pathHint(), call.mutationEffects().appliedPaths(),
                        call.mutationEffects().uncertainPaths(), 0, 0, false))
                .toList();
        return new Turn(result.turnNumber(), audit.localTrace() == null ? "" : audit.localTrace().workspaceHash(),
                calls, Math.max(0, audit.toolCalls().size() - calls.size()));
    }

    private record Targets(List<String> values, int omitted, int chars) {}

    private static Targets targets(List<String> supplied, int charBudget) {
        if (supplied == null || supplied.isEmpty()) return new Targets(List.of(), 0, 0);
        List<String> retained = new ArrayList<>();
        int chars = 0;
        for (String value : supplied) {
            if (retained.size() >= MAX_TARGETS_PER_LIST) break;
            String path = normalizeTarget(value);
            if (path.isBlank() || path.length() > charBudget - chars) continue;
            retained.add(path);
            chars += path.length();
        }
        return new Targets(List.copyOf(retained), supplied.size() - retained.size(), chars);
    }

    private static String normalizeTarget(String value) {
        if (value == null || value.length() > MAX_TARGET_CHARS) return "";
        String path = value.strip().replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        return path.chars().anyMatch(Character::isISOControl) ? "" : path;
    }

    private static String knownWorkspaceHash(String value) {
        return value != null && value.matches("[0-9a-f]{40}") ? value : "";
    }

    private static int addCount(int first, int second) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, first) + Math.max(0, second));
    }
}
