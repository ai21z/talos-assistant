package dev.talos.runtime;

import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class JsonSessionStoreTraceTest {

    private static void withWriter(
            JsonSessionStore store,
            String sessionId,
            Consumer<SessionWriterLease> action
    ) {
        try (SessionWriterLease writer = store.acquireSessionWriter(sessionId)) {
            action.accept(writer);
        }
    }

    @Test
    void savesLoadsAndDeletesPerTurnLocalTraces(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-trace";
        LocalTurnTrace trace = trace("trc-fixed", sid, 3);

        Path traceDir = dir.resolve("traces").resolve(sid);
        try (SessionWriterLease writer = store.acquireSessionWriter(sid)) {
            writer.saveTrace(sid, trace);

            Optional<LocalTurnTrace> loaded = store.loadTrace(sid, "trc-fixed");
            assertTrue(loaded.isPresent());
            assertEquals("trc-fixed", loaded.get().traceId());
            assertEquals(3, loaded.get().turnNumber());

            Optional<LocalTurnTrace> latest = store.loadLatestTrace(sid);
            assertTrue(latest.isPresent());
            assertEquals("trc-fixed", latest.get().traceId());

            assertTrue(Files.isDirectory(traceDir));
            try (var files = Files.list(traceDir)) {
                assertEquals(1, files.count());
            }

            assertTrue(writer.delete(sid));
        }
        assertFalse(Files.exists(traceDir), "session clear/delete should remove local trace artifacts too");
    }

    @Test
    void latestTraceChoosesNewestTurnThenNewestFile(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-trace-latest";
        withWriter(store, sid, writer -> {
            writer.saveTrace(sid, trace("trc-older", sid, 1));
            writer.saveTrace(sid, trace("trc-newer", sid, 2));
        });

        Optional<LocalTurnTrace> latest = store.loadLatestTrace(sid);

        assertTrue(latest.isPresent());
        assertEquals("trc-newer", latest.get().traceId());
        assertEquals(2, latest.get().turnNumber());
    }

    @Test
    void rolefulTargetMetadataRoundTripsExactly(@TempDir Path dir)
            throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-roleful-trace";
        String request = "Inspect index.html; update style.css.";
        int sourceStart = request.indexOf("update style.css.");
        LocalTurnTrace.RolefulTargetSummary expectedTarget =
                new LocalTurnTrace.RolefulTargetSummary(
                        "style.css",
                        "MUST_MUTATE_AFTER_SOURCE_EVIDENCE",
                        "USER_REQUEST",
                        "explicit-post-read-mutation",
                        "update style.css.",
                        0.875,
                        sourceStart,
                        sourceStart + "update style.css".length(),
                        sourceStart + "update style.css.".length());
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

        TurnRecord record = new TurnRecord(
                4,
                Instant.parse("2026-07-30T00:00:00Z"),
                10,
                request,
                "updated",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                TurnPolicyTrace.empty(),
                trace.traceId());
        try (SessionWriterLease writer = store.acquireSessionWriter(sid)) {
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                    writer.persistTurnEnvelope(sid, record, trace).state());
        }

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
                () -> assertEquals("update style.css.", target.sourceText()),
                () -> assertEquals(0.875, target.confidence()),
                () -> assertEquals(sourceStart, target.startOffset()),
                () -> assertEquals(
                        sourceStart + "update style.css".length(),
                        target.authorityEndOffset()),
                () -> assertEquals(
                        sourceStart + "update style.css.".length(),
                        target.endOffset()));

        Path traceFile;
        try (var files = Files.list(dir.resolve("traces").resolve(sid))) {
            traceFile = files.findFirst().orElseThrow();
        }
        String persisted = Files.readString(traceFile);
        String legacy = persisted.replaceAll(
                "\"authorityEndOffset\"\\s*:\\s*-?\\d+\\s*,",
                "");
        assertFalse(persisted.equals(legacy));
        Files.writeString(traceFile, legacy);
        LocalTurnTrace.RolefulTargetSummary legacyLoaded = store
                .loadTrace(sid, trace.traceId())
                .orElseThrow()
                .taskContract()
                .rolefulTargets()
                .getFirst();
        assertEquals(expectedTarget.startOffset(), legacyLoaded.startOffset());
        assertEquals(-1, legacyLoaded.authorityEndOffset());
        assertEquals(expectedTarget.endOffset(), legacyLoaded.endOffset());
    }

    @Test
    void unboundOrMismatchedLocalCoordinatesPublishNothing(
            @TempDir Path dir
    ) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sourceText = "update style.css.";
        LocalTurnTrace.RolefulTargetSummary target =
                new LocalTurnTrace.RolefulTargetSummary(
                        "style.css",
                        "MUST_MUTATE",
                        "USER_REQUEST",
                        "explicit-mutation-target",
                        sourceText,
                        1.0,
                        0,
                        sourceText.length());
        LocalTurnTrace trace = LocalTurnTrace.builder(
                        "trc-unbound-roleful",
                        "session-unbound-roleful",
                        1,
                        "2026-07-30T00:00:00Z")
                .taskContract(new LocalTurnTrace.TaskContractSummary(
                        "FILE_EDIT",
                        true,
                        true,
                        true,
                        List.of("style.css"),
                        List.of(),
                        "strict-coordinate-binding",
                        List.of(target)))
                .build();

        TurnRecord mismatched = new TurnRecord(
                1,
                Instant.parse("2026-07-30T00:00:00Z"),
                1,
                "Edit index.html.",
                "",
                List.of(),
                0,
                0,
                0,
                "",
                "failed",
                TurnPolicyTrace.empty(),
                trace.traceId());
        withWriter(store, "session-unbound-roleful", writer -> {
            writer.saveTrace("session-unbound-roleful", trace);
            assertFalse(Files.exists(
                    dir.resolve("traces").resolve("session-unbound-roleful")));

            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED,
                    writer.persistTurnEnvelope(
                            "session-unbound-roleful",
                            mismatched,
                            trace).state());
        });
        assertFalse(Files.exists(
                dir.resolve("session-unbound-roleful.turns.jsonl")));
        assertFalse(Files.exists(
                dir.resolve("traces").resolve("session-unbound-roleful")));
    }

    @Test
    void sanitizingEnvelopeRequestInvalidatesBoundLocalCoordinates(
            @TempDir Path dir
    ) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-sanitized-envelope-roleful";
        String request =
                "DO_NOT_LEAK_T1196_TRACE then update style.css.";
        String sourceText = "update style.css.";
        int sourceStart = request.indexOf(sourceText);
        LocalTurnTrace trace = LocalTurnTrace.builder(
                        "trc-sanitized-envelope-roleful",
                        sid,
                        1,
                        "2026-07-30T00:00:00Z")
                .taskContract(new LocalTurnTrace.TaskContractSummary(
                        "FILE_EDIT",
                        true,
                        true,
                        true,
                        List.of("style.css"),
                        List.of(),
                        "sanitized-request-coordinate-test",
                        List.of(new LocalTurnTrace.RolefulTargetSummary(
                                "style.css",
                                "MUST_MUTATE",
                                "USER_REQUEST",
                                "explicit-mutation-target",
                                sourceText,
                                1.0,
                                sourceStart,
                                sourceStart + "update style.css".length(),
                                sourceStart + sourceText.length()))))
                .build();
        TurnRecord record = new TurnRecord(
                1,
                Instant.parse("2026-07-30T00:00:00Z"),
                1,
                request,
                "",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                TurnPolicyTrace.empty(),
                trace.traceId());

        try (SessionWriterLease writer = store.acquireSessionWriter(sid)) {
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                    writer.persistTurnEnvelope(sid, record, trace).state());
        }
        assertFalse(store.loadTurns(sid).getFirst().userInput()
                .contains("DO_NOT_LEAK_T1196_TRACE"));
        LocalTurnTrace.RolefulTargetSummary loaded = store
                .loadTrace(sid, trace.traceId())
                .orElseThrow()
                .taskContract()
                .rolefulTargets()
                .getFirst();
        assertEquals(sourceText, loaded.sourceText());
        assertEquals(-1, loaded.startOffset());
        assertEquals(-1, loaded.authorityEndOffset());
        assertEquals(-1, loaded.endOffset());
    }

    @Test
    void redactableTraceSourceCannotHideCoordinatesAgainstBlankEnvelopeInput(
            @TempDir Path dir
    ) {
        for (String lineEnding : List.of("\n", "\r\n")) {
            String label = "\n".equals(lineEnding) ? "lf" : "crlf";
            String sid = "session-redactable-unbound-" + label;
            String traceId = "trc-redactable-unbound-" + label;
            String sourceText =
                    "update style.css with DO_NOT_LEAK_T1196_TRACE.";
            LocalTurnTrace trace = rolefulTrace(
                    sid,
                    traceId,
                    sourceText,
                    0,
                    sourceText.length());
            TurnRecord record = new TurnRecord(
                    1,
                    Instant.parse("2026-07-30T00:00:00Z"),
                    1,
                    lineEnding,
                    "",
                    List.of(),
                    0,
                    0,
                    0,
                    "",
                    "failed",
                    TurnPolicyTrace.empty(),
                    traceId);

            JsonSessionStore store = new JsonSessionStore(dir);
            try (SessionWriterLease writer = store.acquireSessionWriter(sid)) {
                assertEquals(
                        TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED,
                        writer.persistTurnEnvelope(sid, record, trace).state(),
                        label);
            }
            assertFalse(Files.exists(dir.resolve(sid + ".turns.jsonl")), label);
            assertFalse(Files.exists(dir.resolve("traces").resolve(sid)), label);
        }
    }

    @Test
    void redactableTraceSourceInvalidatesCoordinatesOnlyAfterRawBindingSucceeds(
            @TempDir Path dir
    ) throws Exception {
        for (String lineEnding : List.of("\n", "\r\n")) {
            String label = "\n".equals(lineEnding) ? "lf" : "crlf";
            String sid = "session-redactable-bound-" + label;
            String traceId = "trc-redactable-bound-" + label;
            String sourceText =
                    "update style.css with DO_NOT_LEAK_T1196_TRACE.";
            String request = " \t" + lineEnding
                    + "Edit index.html, then " + sourceText
                    + lineEnding + " ";
            String canonical = request.strip();
            int start = canonical.indexOf(sourceText);
            LocalTurnTrace trace = rolefulTrace(
                    sid,
                    traceId,
                    sourceText,
                    start,
                    start + sourceText.length());
            TurnRecord record = new TurnRecord(
                    1,
                    Instant.parse("2026-07-30T00:00:00Z"),
                    1,
                    request,
                    "",
                    List.of(),
                    0,
                    0,
                    0,
                    "",
                    "ok",
                    TurnPolicyTrace.empty(),
                    traceId);
            JsonSessionStore store = new JsonSessionStore(dir);

            try (SessionWriterLease writer = store.acquireSessionWriter(sid)) {
                assertEquals(
                        TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                        writer.persistTurnEnvelope(sid, record, trace).state(),
                        label);
            }
            LocalTurnTrace.RolefulTargetSummary loaded = store
                    .loadTrace(sid, traceId)
                    .orElseThrow()
                    .taskContract()
                    .rolefulTargets()
                    .getFirst();
            assertEquals(-1, loaded.startOffset(), label);
            assertEquals(-1, loaded.authorityEndOffset(), label);
            assertEquals(-1, loaded.endOffset(), label);
            assertTrue(loaded.sourceText().contains("[redacted-canary]"), label);
            assertFalse(
                    Files.readString(dir.resolve(sid + ".turns.jsonl"))
                            .contains("DO_NOT_LEAK_T1196_TRACE"),
                    label);
            assertFalse(
                    Files.readString(dir.resolve("traces")
                                    .resolve(sid)
                                    .resolve("000001-" + traceId + ".json"))
                            .contains("DO_NOT_LEAK_T1196_TRACE"),
                    label);
        }
    }

    @Test
    void localRolefulEvidenceShapeRejectsMalformedProgrammaticValues() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new LocalTurnTrace.RolefulTargetSummary(
                                "style.css", "MUST_MUTATE", "NOT_A_SOURCE",
                                "", "", 1.0, -1, -1)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new LocalTurnTrace.RolefulTargetSummary(
                                "style.css", "MUST_MUTATE", "RUNTIME_DEFAULT",
                                "", "", Double.NaN, -1, -1)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new LocalTurnTrace.RolefulTargetSummary(
                                "style.css", "MUST_MUTATE", "USER_REQUEST",
                                "", "", 1.0, 0, 0)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new LocalTurnTrace.RolefulTargetSummary(
                                "style.css", "MUST_MUTATE", "MESSAGE_HISTORY",
                                "", "css", 1.0, 0, 3)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new LocalTurnTrace.RolefulTargetSummary(
                                "style.css", "MUST_MUTATE", "USER_REQUEST",
                                "", "update style.css.", 1.0,
                                -1, 16, -1)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new LocalTurnTrace.RolefulTargetSummary(
                                "style.css", "MUST_MUTATE", "USER_REQUEST",
                                "", "update style.css.", 1.0,
                                0, 0, 17)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new LocalTurnTrace.RolefulTargetSummary(
                                "style.css", "MUST_MUTATE", "USER_REQUEST",
                                "", "update style.css.", 1.0,
                                0, 18, 17)));
    }

    @Test
    void redactableUnboundLocalTracePublishesNothing(
            @TempDir Path dir
    ) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-roleful-redacted-trace";
        String rawSource =
                "update style.css with DO_NOT_LEAK_T1196_TRACE.";
        LocalTurnTrace.RolefulTargetSummary target =
                new LocalTurnTrace.RolefulTargetSummary(
                        "style.css",
                        "MUST_MUTATE",
                        "USER_REQUEST",
                        "explicit-mutation-target",
                        rawSource,
                        1.0,
                        10,
                        10 + "update style.css".length(),
                        10 + rawSource.length());
        LocalTurnTrace trace = LocalTurnTrace.builder(
                        "trc-roleful-redacted",
                        sid,
                        1,
                        "2026-07-30T00:00:00Z")
                .taskContract(new LocalTurnTrace.TaskContractSummary(
                        "FILE_EDIT",
                        true,
                        true,
                        true,
                        List.of("style.css"),
                        List.of(),
                        "redacted-coordinate-test",
                        List.of(target)))
                .build();

        withWriter(store, sid, writer -> writer.saveTrace(sid, trace));

        Path traceDir = dir.resolve("traces").resolve(sid);
        assertFalse(
                Files.exists(traceDir),
                "redaction must not turn an unbound known span into a writable trace");
        assertFalse(store.loadTrace(sid, trace.traceId()).isPresent());
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

    private static LocalTurnTrace rolefulTrace(
            String sessionId,
            String traceId,
            String sourceText,
            int startOffset,
            int endOffset
    ) {
        return LocalTurnTrace.builder(
                        traceId,
                        sessionId,
                        1,
                        "2026-07-30T00:00:00Z")
                .taskContract(new LocalTurnTrace.TaskContractSummary(
                        "FILE_EDIT",
                        true,
                        true,
                        true,
                        List.of("style.css"),
                        List.of(),
                        "strict-coordinate-binding",
                        List.of(new LocalTurnTrace.RolefulTargetSummary(
                                "style.css",
                                "MUST_MUTATE",
                                "USER_REQUEST",
                                "explicit-mutation-target",
                                sourceText,
                                1.0,
                                startOffset,
                                startOffset
                                        + sourceText.indexOf("style.css")
                                        + "style.css".length(),
                                endOffset))))
                .build();
    }
}
