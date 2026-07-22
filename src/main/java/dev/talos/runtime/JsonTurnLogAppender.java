package dev.talos.runtime;

import dev.talos.core.retrieval.RetrievalTrace;
import dev.talos.core.util.UiChrome;
import dev.talos.safety.SafeLogFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * Session listener that appends a structured {@link TurnRecord} to the
 * session's per-turn durability log after every terminal settlement.
 *
 * <p>This is the authoritative runtime-truth transcript: turn number,
 * timestamps, duration, and bounded runtime evidence. Ordinary completions
 * retain user input, chrome-stripped assistant text, and the full redacted
 * audit. Failed, cancelled, and no-result settlements persist a safe terminal
 * projection without user text, path-bearing policy/tool rows, or prose.
 * Unlike the full-session snapshot that only flushes on graceful
 * {@code Session.close()}, this listener persists after each turn so a
 * crash between turns does not discard the work already done.
 *
 * <p>The listener is intentionally additive: it does not replace
 * {@link MemoryUpdateListener}. Persistence health is returned as a typed
 * receipt; a disk problem does not replace the turn's primary result/failure.
 */
public final class JsonTurnLogAppender implements SessionListener {

    private static final Logger LOG = LoggerFactory.getLogger(JsonTurnLogAppender.class);

    private final SessionStore store;
    private final String sessionId;

    public JsonTurnLogAppender(SessionStore store, String sessionId) {
        this.store = store;
        this.sessionId = sessionId;
    }

    @Override
    public void onTurnComplete(TurnResult result, String userInput) {
        if (result == null) return;
        persist(TurnSettlement.completed(TurnExecutionStage.TURN_FINALIZATION, result), userInput);
    }

    @Override
    public TurnPublicationReceipt onTurnSettled(
            TurnSettlement settlement,
            String userInput
    ) {
        return persist(settlement, userInput);
    }

    private TurnPublicationReceipt persist(TurnSettlement settlement, String userInput) {
        if (settlement == null || store == null || sessionId == null || sessionId.isBlank()) {
            return TurnPublicationReceipt.skipped();
        }
        TurnResult result = settlement.result();

        // Extract committed-to-history text (chrome-stripped, matching what
        // MemoryUpdateListener persists). Non-text results (Error, Info,
        // streaming lifecycle markers) are not persisted here either.
        String rawText = settlement.ordinaryCompletion()
                ? MemoryUpdateListener.extractText(result.result())
                : null;
        String committed = rawText == null
                ? ""
                : MemoryUpdateListener.assistantTextForPersistence(rawText, userInput);
        String persistedUserInput = settlement.ordinaryCompletion()
                ? (userInput == null ? "" : userInput)
                : "";

        TurnAudit audit = result.audit() == null ? TurnAudit.empty() : result.audit();
        long durationMs = result.elapsed() == null ? 0L : result.elapsed().toMillis();
        String retrievalSummary = summarize(result.trace());
        if (!settlement.ordinaryCompletion()
                && retrievalSummary.isBlank()
                && result.trace() != null
                && !result.trace().evidenceHits().isEmpty()) {
            retrievalSummary = "0 stages, evidence=" + result.trace().evidenceHits().size();
        }

        TurnRecord record = new TurnRecord(
                result.turnNumber(),
                Instant.now(),
                durationMs,
                persistedUserInput,
                committed,
                audit.toolCalls(),
                audit.approvalsRequired(),
                audit.approvalsGranted(),
                audit.approvalsDenied(),
                retrievalSummary,
                statusOf(settlement),
                audit.policyTrace(),
                audit.localTrace() == null ? "" : audit.localTrace().traceId()
        );

        try {
            TurnEnvelopePersistenceReceipt receipt = store.persistTurnEnvelope(
                    sessionId, record, audit.localTrace());
            return publicationReceipt(receipt);
        } catch (Exception e) {
            LOG.warn("Failed to persist structured turn envelope: {}",
                    SafeLogFormatter.throwableMessage(e));
            return TurnPublicationReceipt.degraded(
                    TurnPublicationReceipt.Reason.PERSISTENCE_FAILED);
        }
    }

    private static String statusOf(TurnSettlement settlement) {
        return switch (settlement.kind()) {
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
            case NO_RESULT -> "no_result";
            case COMPLETED -> statusOf(settlement.result().result());
        };
    }

    private static TurnPublicationReceipt publicationReceipt(
            TurnEnvelopePersistenceReceipt receipt
    ) {
        TurnEnvelopePersistenceReceipt.State state = receipt == null
                ? TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED
                : receipt.state();
        return switch (state) {
            case BOTH_WRITES_OBSERVED -> TurnPublicationReceipt.complete();
            case UNOBSERVED -> TurnPublicationReceipt.degraded(
                    TurnPublicationReceipt.Reason.PERSISTENCE_UNOBSERVED);
            case TRACE_WRITE_ONLY, TURN_WRITE_ONLY -> TurnPublicationReceipt.degraded(
                    TurnPublicationReceipt.Reason.PERSISTENCE_PARTIAL);
            case NO_WRITES_OBSERVED -> TurnPublicationReceipt.degraded(
                    TurnPublicationReceipt.Reason.PERSISTENCE_FAILED);
        };
    }

    /** Build a compact one-line summary of a retrieval trace (blank if null/empty). */
    static String summarize(RetrievalTrace trace) {
        if (trace == null) return "";
        List<RetrievalTrace.Entry> entries = trace.entries();
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(entries.size()).append(" stages, ")
                .append(String.format("%.1fms", trace.totalMs()));
        int finalCount = entries.get(entries.size() - 1).candidatesAfter();
        sb.append(", final=").append(finalCount);
        return sb.toString();
    }

    /**
     * Project a {@link Result} into a compact status tag for the turn log.
     *
     * <p>Distinguishes errored turns from silent turns - before this field,
     * a {@code Result.Error} landed on disk with blank assistantText and
     * was audibly indistinguishable from a turn that produced no committed
     * prose (Info, TrustedInfo, Table). One field, one string, no enum
     * gymnastics - forward-compatible as new {@code Result} types are
     * added.
     */
    static String statusOf(Result r) {
        if (r == null) return "";
        return switch (r) {
            // Legacy/direct callers may still supply an abort marker in an Ok
            // result; it must not be tagged "ok" or replayed as conversation.
            case Result.Ok ok                -> isAbortMarker(ok.text) ? "aborted" : "ok";
            // A streamed turn whose fullText is (or starts with) the bracketed
            // "[turn aborted" marker is NOT conversational content - it is the
            // sentinel LlmClient.withWallClockBudget emits on wall-clock
            // expiry, idle-watchdog abort, or interrupt. Tagging it "aborted"
            // here is what lets the reconcile path in TalosBootstrap.replayTurnLog
            // refuse to re-inject a timed-out turn's confabulated body into the
            // next session's SessionMemory. Without this discriminator, a model
            // that fell into a repetition-loop attractor (observed: gemma4:26b,
            // test-output.txt Apr 2026) had its 200+ line garbage body
            // resurrected on the next REPL start as if it were authoritative
            // conversational history.
            case Result.Streamed s           -> statusOfStreamed(s.fullText);
            case Result.Error ignored        -> "error";
            case Result.Info ignored         -> "info";
            case Result.TrustedInfo ignored  -> "info";
            case Result.Table ignored        -> "info";
            case Result.StreamStart ignored    -> "stream";
            case Result.StreamChunk ignored    -> "stream";
            case Result.StreamEnd ignored      -> "stream";
            case Result.ToolProgress ignored   -> "stream";
        };
    }

    /**
     * True when {@code text} carries the bracketed "[turn aborted" sentinel
     * produced by {@link dev.talos.core.llm.LlmClient} when a call exceeds
     * its wall-clock budget, hits the idle watchdog, is interrupted, or
     * loses the transport after partial output. In the partial-output shape
     * the marker follows the partial text, so detection is line-anchored
     * anywhere in the body (see {@code UiChrome.containsTurnAbortMarker}) -
     * a startsWith on the full body tags the turn "ok" and replays the
     * confabulated partial as authoritative history. Still lexical per
     * line, so prose mentioning "aborted" mid-sentence never over-fires.
     */
    static boolean isAbortMarker(String text) {
        return UiChrome.containsTurnAbortMarker(text);
    }

    static String statusOfStreamed(String text) {
        if (text == null || text.isBlank()) return "ok";
        String rawLower = text.stripLeading().toLowerCase();
        if (rawLower.startsWith(UiChrome.ENGINE_ERROR_PREFIX.toLowerCase(java.util.Locale.ROOT))) return "error";
        if (rawLower.startsWith(UiChrome.MODEL_NOT_FOUND_OPEN.toLowerCase(java.util.Locale.ROOT))
                && rawLower.contains(UiChrome.MODEL_NOT_FOUND_MARKER.toLowerCase(java.util.Locale.ROOT))) {
            return "error";
        }
        String stripped = MemoryUpdateListener.stripUiChromeForHistory(text);
        if (isAbortMarker(text)) return "aborted";
        String lower = stripped.stripLeading().toLowerCase();
        if (!MemoryUpdateListener.isMemorizableAssistantReply(new Result.Streamed(text, ""), stripped)) {
            return "info";
        }
        return "ok";
    }
}

