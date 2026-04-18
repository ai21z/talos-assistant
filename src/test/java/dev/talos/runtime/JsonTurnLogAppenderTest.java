package dev.talos.runtime;

import dev.talos.cli.repl.Result;
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

