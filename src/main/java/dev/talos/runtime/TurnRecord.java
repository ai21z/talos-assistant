package dev.talos.runtime;

import java.time.Instant;
import java.util.List;

/**
 * Minimal, turn-centric, durable record of a single completed turn.
 *
 * <p>Persisted per-turn (append-only, one JSON object per line) alongside
 * the existing session snapshot file. Designed to capture enough runtime
 * truth for auditability and crash recovery without turning the session
 * store into a generic event log.
 *
 * <p>All components are nullable-safe — blank strings and empty lists
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
        String status
) {

    /** Defensive copy + null normalization. */
    public TurnRecord {
        timestamp             = (timestamp == null) ? Instant.now() : timestamp;
        userInput             = (userInput == null) ? "" : userInput;
        assistantText         = (assistantText == null) ? "" : assistantText;
        toolCalls             = (toolCalls == null) ? List.of() : List.copyOf(toolCalls);
        retrievalTraceSummary = (retrievalTraceSummary == null) ? "" : retrievalTraceSummary;
        status                = (status == null) ? "" : status;
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
                retrievalTraceSummary, "");
    }

    /**
     * A compact summary of one tool invocation during a turn.
     *
     * @param name     the tool name (e.g. {@code talos.edit_file})
     * @param pathHint the resolved target path, if the tool accepted one (may be blank)
     * @param success  whether the tool reported success
     */
    public record ToolCallSummary(String name, String pathHint, boolean success) {
        public ToolCallSummary {
            name     = (name == null) ? "" : name;
            pathHint = (pathHint == null) ? "" : pathHint;
        }
    }
}

