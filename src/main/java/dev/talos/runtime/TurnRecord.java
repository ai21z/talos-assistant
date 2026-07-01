package dev.talos.runtime;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Minimal, turn-centric, durable record of a single completed turn.
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
 * @param timestamp               when the turn completed
 * @param durationMs              wall-clock elapsed milliseconds for the turn (may be 0)
 * @param userInput               the raw user prompt
 * @param assistantText           the assistant prose committed to history
 *                                (already stripped of UI chrome)
 * @param toolCalls               per-call summaries recorded during the turn
 * @param approvalsRequired       number of tool calls that reached the approval gate
 * @param approvalsGranted        number of approvals granted (including remembered)
 * @param approvalsDenied         number of approvals denied
 * @param retrievalTraceSummary   short human-readable retrieval trace summary (may be blank)
 * @param status                  compact outcome tag derived from the turn's {@code Result}:
 *                                {@code "ok"} (Ok / Streamed), {@code "error"} (Error),
 *                                {@code "info"} (Info / TrustedInfo / Table), or {@code ""}
 *                                (unknown / not-applicable). Makes errored turns
 *                                distinguishable from silent turns on audit.
 * @param policyTrace             compact task contract / phase / tool-surface trace
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
     * A compact summary of one tool invocation during a turn.
     *
     * @param name      the tool name (e.g. {@code talos.edit_file})
     * @param pathHint  the primary resolved target path, if the tool accepted one (may be blank)
     * @param pathHints all resolved changed paths for multi-path tools; falls back to {@code pathHint}
     * @param success   whether the tool reported success
     * @param reason    compact failure/block reason, if the call did not succeed
     */
    public record ToolCallSummary(
            String name,
            String pathHint,
            List<String> pathHints,
            boolean success,
            String reason
    ) {
        public ToolCallSummary {
            name = (name == null) ? "" : name;
            pathHints = normalizePathHints(pathHint, pathHints);
            pathHint = primaryPathHint(pathHint, pathHints);
            reason = (reason == null) ? "" : reason;
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

