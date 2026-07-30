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

    @Test
    void rolefulTargetMetadataRoundTripsExactly(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-roleful-trace";
        LocalTurnTrace.RolefulTargetSummary expectedTarget =
                new LocalTurnTrace.RolefulTargetSummary(
                        "style.css",
                        "MUST_MUTATE_AFTER_SOURCE_EVIDENCE",
                        "USER_REQUEST",
                        "explicit-post-read-mutation",
                        "Read style.css, then update style.css.",
                        0.875);
        LocalTurnTrace trace = LocalTurnTrace.builder(
                        "trc-roleful",
                        sid,
                        4,
                        "2026-07-30T00:00:00Z")
                .workspaceHash("workspace-hash")
                .mode("auto")
                .model("ollama", "gpt-oss:20b")
                .taskContract(new LocalTurnTrace.TaskContractSummary(
                        "FILE_EDIT",
                        true,
                        true,
                        true,
                        List.of("style.css"),
                        List.of(),
                        "required-post-read-mutation",
                        List.of(expectedTarget)))
                .outcome("OK", "PASSED", "APPROVED", "MUTATED", "COMPLETED")
                .build();

        store.saveTrace(sid, trace);

        LocalTurnTrace loaded = store.loadTrace(sid, trace.traceId()).orElseThrow();
        assertEquals(trace, loaded);
        assertEquals(List.of(expectedTarget), loaded.taskContract().rolefulTargets());
        LocalTurnTrace.RolefulTargetSummary target =
                loaded.taskContract().rolefulTargets().getFirst();
        assertAll(
                () -> assertEquals("style.css", target.path()),
                () -> assertEquals("MUST_MUTATE_AFTER_SOURCE_EVIDENCE", target.role()),
                () -> assertEquals("USER_REQUEST", target.source()),
                () -> assertEquals("explicit-post-read-mutation", target.reason()),
                () -> assertEquals("Read style.css, then update style.css.", target.sourceText()),
                () -> assertEquals(0.875, target.confidence()));
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
