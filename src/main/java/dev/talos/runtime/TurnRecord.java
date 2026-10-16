package dev.talos.runtime;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
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
            @com.fasterxml.jackson.annotation.JsonIgnore ExecutedEditDetail executedEditDetail
    ) {
        public ToolCallSummary {
            name = (name == null) ? "" : name;
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

        private static String normalizePath(String value) {
            if (value == null) return "";
            String normalized = value.strip().replace('\\', '/');
            while (normalized.startsWith("./")) {
                normalized = normalized.substring(2);
            }
            return normalized;
        }
    }
}

