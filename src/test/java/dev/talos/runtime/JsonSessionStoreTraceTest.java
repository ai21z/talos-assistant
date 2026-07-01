package dev.talos.runtime;

import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JsonSessionStoreTraceTest {

    @Test
    void savesLoadsAndDeletesPerTurnLocalTraces(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-trace";
        LocalTurnTrace trace = trace("trc-fixed", sid, 3);

        store.saveTrace(sid, trace);

        Optional<LocalTurnTrace> loaded = store.loadTrace(sid, "trc-fixed");
        assertTrue(loaded.isPresent());
        assertEquals("trc-fixed", loaded.get().traceId());
        assertEquals(3, loaded.get().turnNumber());

        Optional<LocalTurnTrace> latest = store.loadLatestTrace(sid);
        assertTrue(latest.isPresent());
        assertEquals("trc-fixed", latest.get().traceId());

        Path traceDir = dir.resolve("traces").resolve(sid);
        assertTrue(Files.isDirectory(traceDir));
        try (var files = Files.list(traceDir)) {
            assertEquals(1, files.count());
        }

        assertTrue(store.delete(sid));
        assertFalse(Files.exists(traceDir), "session clear/delete should remove local trace artifacts too");
    }

    @Test
    void latestTraceChoosesNewestTurnThenNewestFile(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-trace-latest";
        store.saveTrace(sid, trace("trc-older", sid, 1));
        store.saveTrace(sid, trace("trc-newer", sid, 2));

        Optional<LocalTurnTrace> latest = store.loadLatestTrace(sid);

        assertTrue(latest.isPresent());
        assertEquals("trc-newer", latest.get().traceId());
        assertEquals(2, latest.get().turnNumber());
    }

    private static LocalTurnTrace trace(String traceId, String sessionId, int turnNumber) {
        return LocalTurnTrace.builder(traceId, sessionId, turnNumber, "2026-04-28T12:00:00Z")
                .workspaceHash("workspace-hash")
                .mode("auto")
                .model("ollama", "qwen2.5-coder:14b")
                .toolSurface(List.of("talos.read_file"), List.of("talos.read_file"), "read-only turn")
                .verification("PASSED", "No task-specific verifier was applicable.", List.of())
                .outcome("OK", "PASSED", "NONE", "NONE", "NO_TOOL_RESPONSE")
                .build();
    }
}
