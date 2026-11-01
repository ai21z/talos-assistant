package dev.talos.runtime;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import dev.talos.runtime.intent.WorkspaceOperationObligation;
import dev.talos.tools.ToolMutationEffects;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.impl.ExecutedEditDetail;

/**
 * Minimal, turn-centric, durable record of one terminal turn settlement.
 *
 * <p>Persisted per-turn (append-only, one JSON object per line) alongside
 * the existing session snapshot file. Designed to capture enough runtime
 * truth for auditability and crash recovery without turning the session
 * store into a generic event log.
 *
 * <p>All components are nullable-safe - blank strings and empty lists
 * instead of {@code null}, so JSON round-tripping is lossless.
 *
 * @param turnNumber              1-based turn index within the session
 * @param timestamp               when the turn settled
 * @param durationMs              wall-clock elapsed milliseconds for the turn (may be 0)
 * @param userInput               raw user prompt for ordinary completions;
 *                                blank for non-conversational settlements
 * @param assistantText           assistant prose committed to history for an
 *                                ordinary completion (UI chrome stripped);
 *                                blank otherwise
 * @param toolCalls               per-call summaries recorded during the turn
 * @param approvalsRequired       number of tool calls that reached the approval gate
 * @param approvalsGranted        number of approvals granted (including remembered)
 * @param approvalsDenied         number of approvals denied
 * @param retrievalTraceSummary   short human-readable retrieval trace summary (may be blank)
 * @param status                  compact outcome tag: {@code "failed"},
 *                                {@code "cancelled"}, or {@code "no_result"} for
 *                                abnormal/non-conversational settlements; ordinary
 *                                results retain {@code "ok"}, {@code "error"},
 *                                {@code "aborted"}, {@code "info"}, or {@code ""}.
 *                                This distinguishes terminal state without relying
 *                                on assistant prose.
 * @param policyTrace             compact task contract / phase / tool-surface trace;
 *                                empty for non-conversational settlements
 * @param traceId                 optional id of the richer local turn trace artifact
 */
public record TurnRecord(
        int turnNumber,
        Instant timestamp,
        long durationMs,
        String userInput,
        String assistantText,
        List<ToolCallSummary> toolCalls,
        int approvalsRequired,
        int approvalsGranted,
        int approvalsDenied,
        String retrievalTraceSummary,
        String status,
        TurnPolicyTrace policyTrace,
        String traceId
) {

    /** Defensive copy + null normalization. */
    public TurnRecord {
        timestamp             = (timestamp == null) ? Instant.now() : timestamp;
        userInput             = (userInput == null) ? "" : userInput;
        assistantText         = (assistantText == null) ? "" : assistantText;
        toolCalls             = (toolCalls == null) ? List.of() : List.copyOf(toolCalls);
        retrievalTraceSummary = (retrievalTraceSummary == null) ? "" : retrievalTraceSummary;
        status                = (status == null) ? "" : status;
        policyTrace           = (policyTrace == null) ? TurnPolicyTrace.empty() : policyTrace;
        traceId               = (traceId == null) ? "" : traceId;
    }

    /**
     * Back-compat delegating constructor for call sites that don't yet
     * supply a status. Older records (pre-status JSONL lines) also flow
     * through this on read with status = "".
     */
    public TurnRecord(int turnNumber,
                      Instant timestamp,
                      long durationMs,
                      String userInput,
                      String assistantText,
                      List<ToolCallSummary> toolCalls,
                      int approvalsRequired,
                      int approvalsGranted,
                      int approvalsDenied,
                      String retrievalTraceSummary) {
        this(turnNumber, timestamp, durationMs, userInput, assistantText,
                toolCalls, approvalsRequired, approvalsGranted, approvalsDenied,
                retrievalTraceSummary, "", TurnPolicyTrace.empty(), "");
    }

    public TurnRecord(int turnNumber,
                      Instant timestamp,
                      long durationMs,
                      String userInput,
                      String assistantText,
                      List<ToolCallSummary> toolCalls,
                      int approvalsRequired,
                      int approvalsGranted,
                      int approvalsDenied,
                      String retrievalTraceSummary,
                      String status) {
        this(turnNumber, timestamp, durationMs, userInput, assistantText,
                toolCalls, approvalsRequired, approvalsGranted, approvalsDenied,
                retrievalTraceSummary, status, TurnPolicyTrace.empty(), "");
    }

    public TurnRecord(int turnNumber,
                      Instant timestamp,
                      long durationMs,
                      String userInput,
                      String assistantText,
                      List<ToolCallSummary> toolCalls,
                      int approvalsRequired,
                      int approvalsGranted,
                      int approvalsDenied,
                      String retrievalTraceSummary,
                      String status,
                      TurnPolicyTrace policyTrace) {
        this(turnNumber, timestamp, durationMs, userInput, assistantText,
                toolCalls, approvalsRequired, approvalsGranted, approvalsDenied,
                retrievalTraceSummary, status, policyTrace, "");
    }

    /**
     * Whether a call was blocked or reached tool execution. This does not
     * establish subprocess launch, observed effects, or verification.
     * Legacy summaries without an explicit producer remain unknown.
     */
    public enum ToolCallDisposition {
        UNKNOWN,
        BLOCKED,
        EXECUTED
    }

    /**
     * A compact summary of one tool invocation during a turn.
     *
     * @param name      the tool name (e.g. {@code talos.edit_file})
     * @param pathHint  the primary resolved target path, if the tool accepted one (may be blank)
     * @param pathHints all resolved changed paths for multi-path tools; falls back to {@code pathHint}
     * @param success   whether the tool reported success
     * @param reason    compact failure/block reason, if the call did not succeed
     * @param executionDisposition explicit runtime execution status, independent of success
     * @param failureReason typed producer cause, NONE when unavailable in legacy evidence
     * @param operands  the operands a single workspace operation names, with their roles, at every disposition;
     *                  empty for any other call and for legacy evidence
     */
    public record ToolCallSummary(
            String name,
            String pathHint,
            List<String> pathHints,
            boolean success,
            String reason,
            ToolMutationEffects mutationEffects,
            ToolCallDisposition executionDisposition,
            ToolFailureReason failureReason,
            @com.fasterxml.jackson.annotation.JsonIgnore ExecutedEditDetail executedEditDetail,
            List<OperationOperand> operands
    ) {
        public ToolCallSummary {
            name = (name == null) ? "" : name;
            operands = operands == null ? List.of() : List.copyOf(operands);
            requireOperandRoles(name, operands);
            pathHints = normalizePathHints(pathHint, pathHints);
            pathHint = primaryPathHint(pathHint, pathHints);
            reason = (reason == null) ? "" : reason;
            mutationEffects = mutationEffects == null ? ToolMutationEffects.none() : mutationEffects;
            executionDisposition = executionDisposition == null ? ToolCallDisposition.UNKNOWN : executionDisposition;
            failureReason = failureReason == null ? ToolFailureReason.NONE : failureReason;
            executedEditDetail = executedEditDetail == null ? ExecutedEditDetail.none() : executedEditDetail;
            var tool = dev.talos.tools.ToolAliasPolicy.resolve(name);
            if (executionDisposition != ToolCallDisposition.EXECUTED || !tool.accepted()
                    || !"talos.edit_file".equals(tool.canonicalToolName())
                    || !executedEditDetail.associatedWith(mutationEffects)
                    || !pathHint.equals(executedEditDetail.target())) {
                executedEditDetail = ExecutedEditDetail.none();
            }
            if ((failureReason == ToolFailureReason.USER_APPROVAL_DENIED
                    || failureReason == ToolFailureReason.PERMISSION_POLICY_DENIED
                    || failureReason == ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID)
                    && executionDisposition != ToolCallDisposition.BLOCKED) {
                throw new IllegalArgumentException("A pre-execution denial requires a blocked tool call");
            }
            if (executionDisposition == ToolCallDisposition.BLOCKED
                    && (success || mutationEffects.hasEffects())) {
                throw new IllegalArgumentException("A blocked tool call cannot report success or mutation effects");
            }
        }

        /** A call that is not a single workspace operation, or legacy evidence, names no operands. */
        public ToolCallSummary(String name, String pathHint, List<String> pathHints,
                               boolean success, String reason, ToolMutationEffects mutationEffects,
                               ToolCallDisposition executionDisposition, ToolFailureReason failureReason,
                               ExecutedEditDetail executedEditDetail) {
            this(name, pathHint, pathHints, success, reason, mutationEffects,
                    executionDisposition, failureReason, executedEditDetail, List.of());
        }

        /** Compatibility constructors cannot establish whether execution was reached. */
        public ToolCallSummary(String name, String pathHint, List<String> pathHints,
                               boolean success, String reason, ToolMutationEffects mutationEffects,
                               ToolCallDisposition executionDisposition, ToolFailureReason failureReason) {
            this(name, pathHint, pathHints, success, reason, mutationEffects,
                    executionDisposition, failureReason, ExecutedEditDetail.none());
        }

        public ToolCallSummary(String name, String pathHint, List<String> pathHints,
                               boolean success, String reason, ToolMutationEffects mutationEffects,
                               ToolCallDisposition executionDisposition) {
            this(name, pathHint, pathHints, success, reason, mutationEffects,
                    executionDisposition, ToolFailureReason.NONE);
        }

        public ToolCallSummary(String name, String pathHint, List<String> pathHints,
                               boolean success, String reason, ToolMutationEffects mutationEffects) {
            this(name, pathHint, pathHints, success, reason, mutationEffects, ToolCallDisposition.UNKNOWN);
        }

        public ToolCallSummary(String name, String pathHint, List<String> pathHints,
                               boolean success, String reason) {
            this(name, pathHint, pathHints, success, reason, ToolMutationEffects.none());
        }

        public ToolCallSummary(String name, String pathHint, boolean success, String reason) {
            this(name, pathHint, List.of(), success, reason);
        }

        public ToolCallSummary(String name, String pathHint, boolean success) {
            this(name, pathHint, success, "");
        }

        private static String primaryPathHint(String pathHint, List<String> pathHints) {
            String normalized = normalizePath(pathHint);
            if (!normalized.isBlank()) return normalized;
            return pathHints == null || pathHints.isEmpty() ? "" : pathHints.getFirst();
        }

        private static List<String> normalizePathHints(String pathHint, List<String> pathHints) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            String primary = normalizePath(pathHint);
            if (!primary.isBlank()) out.add(primary);
            if (pathHints != null) {
                for (String path : pathHints) {
                    String normalized = normalizePath(path);
                    if (!normalized.isBlank()) out.add(normalized);
                }
            }
            return List.copyOf(out);
        }

        private static void requireOperandRoles(String name, List<OperationOperand> operands) {
            if (!operandRolesMatch(name, operands)) {
                throw new IllegalArgumentException("The operands do not match the tool call " + name);
            }
        }

        /** Only a single workspace operation names operands, each role at most once and only a role of its kind. */
        private static boolean operandRolesMatch(String name, List<OperationOperand> operands) {
            if (operands == null || operands.isEmpty()) return true;
            var tool = dev.talos.tools.ToolAliasPolicy.resolve(name);
            WorkspaceOperationObligation.Kind kind = null;
            for (WorkspaceOperationObligation.Kind candidate : WorkspaceOperationObligation.Kind.values()) {
                if (tool.accepted() && candidate.toolName().equals(tool.canonicalToolName())) kind = candidate;
            }
            if (kind == null) return false;
            Set<WorkspaceOperationObligation.Role> allowed = switch (kind) {
                case COPY_PATH, MOVE_PATH, RENAME_PATH -> EnumSet.of(
                        WorkspaceOperationObligation.Role.SOURCE, WorkspaceOperationObligation.Role.DESTINATION);
                case MKDIR, DELETE_PATH -> EnumSet.of(WorkspaceOperationObligation.Role.TARGET);
            };
            Set<WorkspaceOperationObligation.Role> seen = EnumSet.noneOf(WorkspaceOperationObligation.Role.class);
            for (OperationOperand operand : operands) {
                if (operand == null || !allowed.contains(operand.role()) || !seen.add(operand.role())) return false;
            }
            return true;
        }

        private static String normalizePath(String value) {
            if (value == null) return "";
            String normalized = value.strip().replace('\\', '/');
            while (normalized.startsWith("./")) {
                normalized = normalized.substring(2);
            }
            return normalized;
        }
    }

    /**
     * One operand of a single workspace operation call and the role the operation gives it. A copy, move or rename
     * names a SOURCE and a DESTINATION, and a mkdir or delete names its TARGET. The role alone does not say whether
     * the path changes, because a copy leaves its source in place while a move or rename relocates it.
     */
    public record OperationOperand(String path, WorkspaceOperationObligation.Role role) {
        public OperationOperand {
            if (role == null) throw new IllegalArgumentException("An operation operand requires a role");
            path = normalizedPath(path);
            if (path.isBlank()) throw new IllegalArgumentException("An operation operand requires a path");
        }

        /**
         * The stored spelling of an operand path, with slashes, without surrounding space and without a leading
         * {@code ./}. It is repeated until it no longer changes, so a stored path reads back as itself.
         */
        static String normalizedPath(String path) {
            String normalized = path == null ? "" : path.replace('\\', '/');
            String previous;
            do {
                previous = normalized;
                normalized = normalized.strip();
                while (normalized.startsWith("./")) normalized = normalized.substring(2);
            } while (!normalized.equals(previous));
            return normalized;
        }
    }
}

