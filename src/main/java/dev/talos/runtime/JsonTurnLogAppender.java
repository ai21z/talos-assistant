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
                summarize(result.trace())
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
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(entries.size()).append(" stages, ")
                .append(String.format("%.1fms", trace.totalMs()));
        int finalCount = entries.get(entries.size() - 1).candidatesAfter();
        sb.append(", final=").append(finalCount);
        return sb.toString();
    }
}

