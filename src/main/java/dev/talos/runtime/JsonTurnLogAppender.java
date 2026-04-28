package dev.talos.runtime;

import dev.talos.cli.repl.Result;
import dev.talos.core.retrieval.RetrievalTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * Session listener that appends a structured {@link TurnRecord} to the
 * session's per-turn durability log after every completed turn.
 *
 * <p>This is the authoritative runtime-truth transcript: turn number,
 * timestamps, duration, user input, chrome-stripped assistant text, and
 * (via {@link TurnAudit}) the tool-call list plus approval counters.
 * Unlike the full-session snapshot that only flushes on graceful
 * {@code Session.close()}, this listener persists after each turn so a
 * crash between turns does not discard the work already done.
 *
 * <p>The listener is intentionally additive: it does not replace
 * {@link MemoryUpdateListener}, and its failure modes are swallowed so
 * a disk problem never aborts a live turn.
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
        if (result == null || store == null || sessionId == null || sessionId.isBlank()) return;

        // Extract committed-to-history text (chrome-stripped, matching what
        // MemoryUpdateListener persists). Non-text results (Error, Info,
        // streaming lifecycle markers) are not persisted here either.
        String rawText = MemoryUpdateListener.extractText(result.result());
        String committed = rawText == null ? "" : MemoryUpdateListener.stripUiChromeForHistory(rawText);

        TurnAudit audit = result.audit() == null ? TurnAudit.empty() : result.audit();
        long durationMs = result.elapsed() == null ? 0L : result.elapsed().toMillis();
        if (audit.localTrace() != null) {
            try {
                store.saveTrace(sessionId, audit.localTrace());
            } catch (Exception e) {
                LOG.warn("Failed to persist local turn trace: {}", e.getMessage());
            }
        }

        TurnRecord record = new TurnRecord(
                result.turnNumber(),
                Instant.now(),
                durationMs,
                userInput == null ? "" : userInput,
                committed,
                audit.toolCalls(),
                audit.approvalsRequired(),
                audit.approvalsGranted(),
                audit.approvalsDenied(),
                summarize(result.trace()),
                statusOf(result.result()),
                audit.policyTrace(),
                audit.localTrace() == null ? "" : audit.localTrace().traceId()
        );

        try {
            store.appendTurn(sessionId, record);
        } catch (Exception e) {
            LOG.warn("Failed to append structured turn record: {}", e.getMessage());
        }
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
     * <p>Distinguishes errored turns from silent turns — before this field,
     * a {@code Result.Error} landed on disk with blank assistantText and
     * was audibly indistinguishable from a turn that produced no committed
     * prose (Info, TrustedInfo, Table). One field, one string, no enum
     * gymnastics — forward-compatible as new {@code Result} types are
     * added.
     */
    static String statusOf(Result r) {
        if (r == null) return "";
        return switch (r) {
            case Result.Ok ignored           -> "ok";
            // A streamed turn whose fullText is (or starts with) the bracketed
            // "[turn aborted" marker is NOT conversational content — it is the
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
     * True when {@code text} is the bracketed "[turn aborted" sentinel produced
     * by {@link dev.talos.core.llm.LlmClient} when a call exceeds its
     * wall-clock budget, hits the idle watchdog, or is interrupted. Kept
     * lexical (prefix match after trimming) so it never over-fires on real
     * model prose that happens to contain the word "aborted" mid-sentence.
     */
    static boolean isAbortMarker(String text) {
        if (text == null) return false;
        String t = text.stripLeading();
        return t.startsWith("[turn aborted");
    }

    static String statusOfStreamed(String text) {
        if (text == null || text.isBlank()) return "ok";
        String rawLower = text.stripLeading().toLowerCase();
        if (rawLower.startsWith("[engine error")) return "error";
        if (rawLower.startsWith("[model '") && rawLower.contains("' not found")) return "error";
        String stripped = MemoryUpdateListener.stripUiChromeForHistory(text);
        if (isAbortMarker(text)) return "aborted";
        String lower = stripped.stripLeading().toLowerCase();
        if (!MemoryUpdateListener.isMemorizableAssistantReply(new Result.Streamed(text, ""), stripped)) {
            return "info";
        }
        return "ok";
    }
}

