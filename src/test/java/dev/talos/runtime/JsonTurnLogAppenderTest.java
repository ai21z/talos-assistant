package dev.talos.runtime;

import dev.talos.cli.repl.Result;
import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step-2 test: the {@link JsonTurnLogAppender} persists a per-turn record
 * using the {@link TurnAudit} embedded in {@link TurnResult}, the stripped
 * assistant text, and the turn timing.
 */
class JsonTurnLogAppenderTest {

    @Test
    void writesStructuredRecordWithChromeStrippedText(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "sess-listener";
        JsonTurnLogAppender appender = new JsonTurnLogAppender(store, sid);

        TurnAudit audit = new TurnAudit(
                List.of(new TurnRecord.ToolCallSummary(
                        "talos.edit_file", "horror-synth-site/index.html", true)),
                1, 1, 0);

        TurnResult tr = new TurnResult(
                new Result.Streamed(
                        "I updated the title.\n[Used 1 tool(s): talos.edit_file | 1 iteration(s)]", ""),
                null, 1, Duration.ofMillis(1234), audit);

        appender.onTurnComplete(tr, "rename the title");

        List<TurnRecord> loaded = store.loadTurns(sid);
        assertEquals(1, loaded.size());
        TurnRecord rec = loaded.get(0);

        assertEquals(1, rec.turnNumber());
        assertEquals("rename the title", rec.userInput());
        assertEquals("I updated the title.", rec.assistantText(),
                "UI chrome must be stripped before persistence");
        assertEquals(1234, rec.durationMs());
        assertEquals(1, rec.approvalsRequired());
        assertEquals(1, rec.approvalsGranted());
        assertEquals(1, rec.toolCalls().size());
        assertEquals("talos.edit_file", rec.toolCalls().get(0).name());
        assertTrue(rec.toolCalls().get(0).success());
        assertEquals("ok", rec.status(), "Streamed result → status=ok");
    }

    @Test
    void writesStructuredRecordWithProtectedContentRedacted(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "sess-protected";
        JsonTurnLogAppender appender = new JsonTurnLogAppender(store, sid);

        TurnResult tr = new TurnResult(
                new Result.Streamed("""
                        The `.env` file contains:

                        ```
                        TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak
                        ```
                        """, ""),
                null,
                1,
                Duration.ofMillis(100),
                TurnAudit.empty());

        appender.onTurnComplete(tr, "Read .env and tell me the value inside.");

        List<TurnRecord> loaded = store.loadTurns(sid);
        assertEquals(1, loaded.size());
        String stored = loaded.get(0).assistantText();
        assertFalse(stored.contains("TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak"), stored);
        assertFalse(stored.contains("must-not-leak"), stored);
        assertTrue(stored.contains("TALOS_T61E_LLAMA_CPP_SECRET=[redacted]"), stored);
    }

    @Test
    void writesLocalTraceArtifactAndTraceIdWithTurnRecord(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "sess-trace-listener";
        JsonTurnLogAppender appender = new JsonTurnLogAppender(store, sid);
        LocalTurnTrace trace = LocalTurnTrace.builder(
                        "trc-listener",
                        sid,
                        1,
                        "2026-04-28T12:00:00Z")
                .workspaceHash("workspace-hash")
                .mode("auto")
                .model("ollama", "qwen2.5-coder:14b")
                .outcome("OK", "NOT_RUN", "NONE", "NONE", "NO_TOOL_RESPONSE")
                .build();
        TurnAudit audit = TurnAudit.empty().withLocalTrace(trace);

        appender.onTurnComplete(
                new TurnResult(new Result.Ok("done"), null, 1, Duration.ofMillis(100), audit),
                "hello");

        List<TurnRecord> loaded = store.loadTurns(sid);
        assertEquals(1, loaded.size());
        assertEquals("trc-listener", loaded.get(0).traceId());
        assertTrue(store.loadTrace(sid, "trc-listener").isPresent());
    }

    @Test
    void statusDistinguishesErroredFromSilentTurns(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "sid-status";
        JsonTurnLogAppender appender = new JsonTurnLogAppender(store, sid);

        // Error turn — blank assistantText, status must say "error".
        appender.onTurnComplete(
                new TurnResult(new Result.Error("boom", 500), 1), "do thing");
        // Info turn — also blank assistantText, but clearly not an error.
        appender.onTurnComplete(
                new TurnResult(new Result.Info("rebuilt index"), 2), "/reindex");
        // Ok turn — non-streaming success path.
        appender.onTurnComplete(
                new TurnResult(new Result.Ok("done"), 3), "ping");

        List<TurnRecord> recs = store.loadTurns(sid);
        assertEquals(3, recs.size());
        assertEquals("error", recs.get(0).status());
        assertEquals("info",  recs.get(1).status());
        assertEquals("ok",    recs.get(2).status());

        // All three lost assistantText in the blank/extract-null paths;
        // status is now the only reliable discriminator on disk.
        assertEquals("", recs.get(0).assistantText());
        assertEquals("", recs.get(1).assistantText());
        assertEquals("done", recs.get(2).assistantText());
    }

    /**
     * Wall-clock / idle / interrupt abort path: LlmClient returns a
     * {@code Result.Streamed} whose {@code fullText} is the bracketed
     * "[turn aborted ...]" marker. The appender must tag this as
     * {@code "aborted"} (NOT "ok") so the cross-session replay filter in
     * {@code TalosBootstrap.replayTurnLog} refuses to re-inject it on the
     * next REPL start.
     */
    @Test
    void streamedTurnWithAbortMarkerIsTaggedAborted(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "sid-aborted";
        JsonTurnLogAppender appender = new JsonTurnLogAppender(store, sid);

        appender.onTurnComplete(
                new TurnResult(new Result.Streamed(
                        "[turn aborted: streaming chat exceeded 300s wall-clock budget — "
                                + "model is hung or producing tokens too slowly.]", ""),
                        3),
                "describe the repo");

        List<TurnRecord> recs = store.loadTurns(sid);
        assertEquals(1, recs.size());
        assertEquals("aborted", recs.get(0).status());
    }

    /**
     * Lexical-prefix anchoring of the abort marker must not over-fire on
     * real model prose that happens to contain the word "aborted" in the
     * middle of a sentence.
     */
    @Test
    void streamedTurnWithOrganicAbortedWordStaysOk(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "sid-organic";
        JsonTurnLogAppender appender = new JsonTurnLogAppender(store, sid);

        appender.onTurnComplete(
                new TurnResult(new Result.Streamed(
                        "The operation was aborted by the user earlier this week.", ""),
                        1),
                "what happened?");

        List<TurnRecord> recs = store.loadTurns(sid);
        assertEquals(1, recs.size());
        assertEquals("ok", recs.get(0).status());
    }

    @Test
    void streamedEngineAndModelErrorsAreNotTaggedOk(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "sid-errors";
        JsonTurnLogAppender appender = new JsonTurnLogAppender(store, sid);

        appender.onTurnComplete(
                new TurnResult(new Result.Streamed("[Engine error during tool loop: boom]", ""), 1),
                "why failed?");
        appender.onTurnComplete(
                new TurnResult(new Result.Streamed("[Model 'qwen3:8b' not found. Run: ollama pull qwen3:8b]", ""), 2),
                "try again");

        List<TurnRecord> recs = store.loadTurns(sid);
        assertEquals(2, recs.size());
        assertEquals("error", recs.get(0).status());
        assertEquals("error", recs.get(1).status());
    }

    @Test
    void refusalStyleStreamedReplyIsTaggedInfo(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "sid-refusal";
        JsonTurnLogAppender appender = new JsonTurnLogAppender(store, sid);

        appender.onTurnComplete(
                new TurnResult(new Result.Streamed(
                        "I am an AI text-based assistant and cannot directly edit files on your system.", ""),
                        1),
                "please edit it");

        List<TurnRecord> recs = store.loadTurns(sid);
        assertEquals(1, recs.size());
        assertEquals("info", recs.get(0).status());
    }

    @Test
    void legacyRecordsWithoutStatusRoundTripAsEmptyString(@TempDir Path dir) {
        // Simulate a JSONL line written by an older appender (no "status" field).
        // The reader must default to "" rather than fail, so existing logs
        // keep loading after the schema bump.
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "sid-legacy";
        // Use the 10-arg back-compat constructor — status defaults to "".
        store.appendTurn(sid, new TurnRecord(1, java.time.Instant.now(), 10L,
                "u", "a", List.of(), 0, 0, 0, ""));
        List<TurnRecord> recs = store.loadTurns(sid);
        assertEquals(1, recs.size());
        assertEquals("", recs.get(0).status(), "legacy records default to empty status");
    }

    @Test
    void nullResultIsIgnored(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        new JsonTurnLogAppender(store, "sid").onTurnComplete(null, "hi");
        assertTrue(store.loadTurns("sid").isEmpty());
    }

    @Test
    void nonTextResultStillPersistsWithEmptyAssistantText(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "sid-info";
        new JsonTurnLogAppender(store, sid).onTurnComplete(
                new TurnResult(new Result.Info("rebuilt index"), 1),
                "/reindex");

        // Info results aren't tracked in conversation memory — but we still
        // record the turn's runtime truth so the audit log is complete.
        List<TurnRecord> loaded = store.loadTurns(sid);
        assertEquals(1, loaded.size());
        assertEquals("/reindex", loaded.get(0).userInput());
        assertEquals("", loaded.get(0).assistantText(),
                "Info/Error results produce empty assistantText (no history commit)");
    }
}

