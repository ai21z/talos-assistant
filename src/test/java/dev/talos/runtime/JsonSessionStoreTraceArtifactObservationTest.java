package dev.talos.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JsonSessionStoreTraceArtifactObservationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(20);

    @Test
    void terminalPairIsExactOnlyOutsideTheOwnedWriterCriticalSection(
            @TempDir Path sessions
    ) {
        Path workspace = sessions.resolve("workspace-pair")
                .toAbsolutePath().normalize();
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId;
        try (SessionWriterLease writer = store.reserveNewSession(
                workspace,
                Instant.parse("2026-08-03T12:00:00Z"))) {
            sessionId = writer.sessionId();
            var receipt = writer.persistTurnEnvelope(
                    sessionId,
                    row(1, "trace-pair"),
                    trace(
                            JsonSessionStore.sessionIdFor(workspace),
                            1,
                            "trace-pair"));
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                    receipt.state());
            assertEquals(
                    TerminalTurnPairObservation.State.UNAVAILABLE,
                    store.observeTerminalTurnPair(sessionId).state());
            assertEquals(
                    TerminalTurnPairObservation.State.UNAVAILABLE,
                    writer.observeTerminalTurnPair(sessionId).state());
        }

        TerminalTurnPairObservation observed =
                store.observeTerminalTurnPair(sessionId);
        assertTrue(observed.exact());
        assertEquals(1, observed.rowObservation().appendOrdinal());
        assertEquals(1, observed.rowObservation().turn().turnNumber());
        assertEquals("trace-pair", observed.rowObservation().turn().traceId());
        assertEquals(1, observed.traceObservation().trace().turnNumber());
        assertEquals("trace-pair", observed.traceObservation().trace().traceId());
    }

    @Test
    void missingReservationMarkerMakesTerminalPairUnavailable(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace-missing-reservation")
                .toAbsolutePath().normalize();
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId;
        try (SessionWriterLease writer = store.reserveNewSession(
                workspace,
                Instant.parse("2026-08-03T12:00:00Z"))) {
            sessionId = writer.sessionId();
            var receipt = writer.persistTurnEnvelope(
                    sessionId,
                    row(1, "trace-missing-reservation"),
                    trace(
                            JsonSessionStore.sessionIdFor(workspace),
                            1,
                            "trace-missing-reservation"));
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                    receipt.state());
        }

        Files.delete(sessions.resolve(".ownership")
                .resolve(sessionId + ".session.reserve"));

        TerminalTurnPairObservation observed =
                store.observeTerminalTurnPair(sessionId);
        assertEquals(TerminalTurnPairObservation.State.UNAVAILABLE,
                observed.state());
        assertNull(observed.rowObservation());
        assertNull(observed.traceObservation());
    }

    @Test
    void mismatchedReservationMarkerMakesTerminalPairUnavailable(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace-mismatched-reservation")
                .toAbsolutePath().normalize();
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId;
        try (SessionWriterLease writer = store.reserveNewSession(
                workspace,
                Instant.parse("2026-08-03T12:00:00Z"))) {
            sessionId = writer.sessionId();
            var receipt = writer.persistTurnEnvelope(
                    sessionId,
                    row(1, "trace-mismatched-reservation"),
                    trace(
                            JsonSessionStore.sessionIdFor(workspace),
                            1,
                            "trace-mismatched-reservation"));
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                    receipt.state());
        }

        Files.writeString(
                sessions.resolve(".ownership")
                        .resolve(sessionId + ".session.reserve"),
                "talos-session-reservation-v1\nwrong-session\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);

        TerminalTurnPairObservation observed =
                store.observeTerminalTurnPair(sessionId);
        assertEquals(TerminalTurnPairObservation.State.UNAVAILABLE,
                observed.state());
        assertNull(observed.rowObservation());
        assertNull(observed.traceObservation());
    }

    @Test
    void oneByteOverReservationMarkerMakesTerminalPairUnavailable(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace-one-over-reservation")
                .toAbsolutePath().normalize();
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId;
        try (SessionWriterLease writer = store.reserveNewSession(
                workspace,
                Instant.parse("2026-08-03T12:00:00Z"))) {
            sessionId = writer.sessionId();
            writer.persistTurnEnvelope(
                    sessionId,
                    row(1, "trace-one-over-reservation"),
                    trace(
                            JsonSessionStore.sessionIdFor(workspace),
                            1,
                            "trace-one-over-reservation"));
        }

        Files.write(
                sessions.resolve(".ownership")
                        .resolve(sessionId + ".session.reserve"),
                new byte[]{'x'},
                StandardOpenOption.APPEND);

        TerminalTurnPairObservation observed =
                store.observeTerminalTurnPair(sessionId);
        assertEquals(TerminalTurnPairObservation.State.UNAVAILABLE,
                observed.state());
        assertNull(observed.rowObservation());
        assertNull(observed.traceObservation());
    }

    @Test
    void oversizedReservationMarkerFailsClosedWithinABoundedChildHeap(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace-oversized-reservation")
                .toAbsolutePath().normalize();
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId;
        try (SessionWriterLease writer = store.reserveNewSession(
                workspace,
                Instant.parse("2026-08-03T12:00:00Z"))) {
            sessionId = writer.sessionId();
            writer.persistTurnEnvelope(
                    sessionId,
                    row(1, "trace-oversized-reservation"),
                    trace(
                            JsonSessionStore.sessionIdFor(workspace),
                            1,
                            "trace-oversized-reservation"));
        }

        Path marker = sessions.resolve(".ownership")
                .resolve(sessionId + ".session.reserve");
        try (FileChannel channel = FileChannel.open(
                marker,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(128L * 1024L * 1024L);
            assertEquals(1, channel.write(ByteBuffer.wrap(new byte[]{0})));
        }

        String executable = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Process child = new ProcessBuilder(
                java.toString(),
                "-Xmx64m",
                "-cp",
                System.getProperty("java.class.path"),
                OversizedReservationObservationProcessMain.class.getName(),
                sessions.toString(),
                sessionId)
                .redirectErrorStream(true)
                .start();

        ProcessResult result = await(child);
        assertEquals(0, result.exitCode(), result.output());
    }

    @Test
    void sameCoordinateTailReplacementDuringPairObservationFailsClosed(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace-replaced-pair")
                .toAbsolutePath().normalize();
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId;
        try (SessionWriterLease writer = store.reserveNewSession(
                workspace,
                Instant.parse("2026-08-03T12:00:00Z"))) {
            sessionId = writer.sessionId();
            var receipt = writer.persistTurnEnvelope(
                    sessionId,
                    row(1, "trace-replaced-pair"),
                    trace(
                            JsonSessionStore.sessionIdFor(workspace),
                            1,
                            "trace-replaced-pair"));
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                    receipt.state());
        }

        TurnLogTailObservation frozen = store.observeTurnLogTail(sessionId);
        assertTrue(frozen.exact());
        Path turnLog = sessions.resolve(sessionId + ".turns.jsonl");
        AtomicInteger tailReads = new AtomicInteger();
        SessionStore racingStore = new SessionStore() {
            @Override
            public void save(SessionData data) {
                throw new AssertionError("diagnostic observation must not save");
            }

            @Override
            public java.util.Optional<SessionData> load(String id) {
                return store.load(id);
            }

            @Override
            public boolean delete(String id) {
                throw new AssertionError("diagnostic observation must not delete");
            }

            @Override
            public TurnLogTailObservation observeTurnLogTail(String id) {
                TurnLogTailObservation observed = store.observeTurnLogTail(id);
                if (tailReads.incrementAndGet() == 1) {
                    try {
                        ObjectNode replacement = (ObjectNode) MAPPER.readTree(
                                Files.readString(
                                        turnLog,
                                        StandardCharsets.UTF_8).strip());
                        replacement.put("durationMs", 2L);
                        replacement.put(
                                "assistantText", "replacement assistant");
                        Files.writeString(
                                turnLog,
                                MAPPER.writeValueAsString(replacement) + "\n",
                                StandardCharsets.UTF_8,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                }
                return observed;
            }

            @Override
            public TraceArtifactObservation observeTraceArtifact(
                    String id,
                    int turnNumber,
                    String traceId
            ) {
                return store.observeTraceArtifact(id, turnNumber, traceId);
            }
        };

        TerminalTurnPairObservation pair =
                TerminalTurnPairObservation.observe(racingStore, sessionId);
        assertEquals(TerminalTurnPairObservation.State.UNAVAILABLE, pair.state());
        assertNull(pair.rowObservation());
        assertNull(pair.traceObservation());
        assertEquals(2, tailReads.get());

        TurnLogTailObservation replaced = store.observeTurnLogTail(sessionId);
        assertTrue(replaced.exact());
        assertEquals(1, replaced.appendOrdinal());
        assertEquals(frozen.turn().turnNumber(), replaced.turn().turnNumber());
        assertEquals(frozen.turn().timestamp(), replaced.turn().timestamp());
        assertEquals(frozen.turn().traceId(), replaced.turn().traceId());
        assertEquals(frozen.turn().status(), replaced.turn().status());
        assertEquals(frozen.turn().userInput(), replaced.turn().userInput());
        assertEquals(
                frozen.turn().policyTrace(), replaced.turn().policyTrace());
        assertFalse(frozen.rowSha256().equals(replaced.rowSha256()));
    }

    @Test
    void childJvmWriterMakesTerminalPairUnavailableUntilRelease(
            @TempDir Path temp
    ) throws Exception {
        Path sessions = temp.resolve("sessions-child-writer");
        Path workspace = temp.resolve("workspace-child-writer")
                .toAbsolutePath().normalize();
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId;
        try (SessionWriterLease writer = store.reserveNewSession(
                workspace,
                Instant.parse("2026-08-03T12:00:00Z"))) {
            sessionId = writer.sessionId();
            var receipt = writer.persistTurnEnvelope(
                    sessionId,
                    row(1, "trace-child-writer"),
                    trace(
                            JsonSessionStore.sessionIdFor(workspace),
                            1,
                            "trace-child-writer"));
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                    receipt.state());
        }

        Path controls = temp.resolve("child-writer-controls");
        Files.createDirectories(controls);
        Path ready = controls.resolve("ready");
        Path release = controls.resolve("release");
        Process child = startWriterHolder(
                sessions, sessionId, ready, release);
        try {
            awaitFile(ready, child);
            assertEquals(
                    TerminalTurnPairObservation.State.UNAVAILABLE,
                    store.observeTerminalTurnPair(sessionId).state());

            Files.writeString(
                    release, "release\n", StandardCharsets.UTF_8);
            ProcessResult result = await(child);
            assertEquals(0, result.exitCode(), result.output());

            TerminalTurnPairObservation observed =
                    store.observeTerminalTurnPair(sessionId);
            assertTrue(observed.exact());
            assertEquals(1, observed.rowObservation().turn().turnNumber());
            assertEquals(
                    "trace-child-writer",
                    observed.traceObservation().trace().traceId());
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    @Test
    void exactTraceObservationBindsTheValidatedPhysicalBytes(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace")
                .toAbsolutePath().normalize();
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId;
        TraceArtifactObservation viaLease;
        try (SessionWriterLease writer = store.reserveNewSession(
                workspace,
                Instant.parse("2026-08-03T12:00:00Z"))) {
            sessionId = writer.sessionId();
            writer.saveTrace(
                    sessionId,
                    trace(
                            JsonSessionStore.sessionIdFor(workspace),
                            3,
                            "trace-exact"));
            viaLease = writer.observeTraceArtifact(
                    sessionId, 3, "trace-exact");
        }

        Path artifact = sessions.resolve("traces")
                .resolve(sessionId)
                .resolve("000003-trace-exact.json");
        String expectedSha256 = sha256(Files.readAllBytes(artifact));
        TraceArtifactObservation direct = store.observeTraceArtifact(
                sessionId, 3, "trace-exact");

        for (TraceArtifactObservation observation :
                java.util.List.of(viaLease, direct)) {
            assertEquals(TraceArtifactObservation.State.EXACT, observation.state());
            assertTrue(observation.exact());
            assertEquals(3, observation.trace().turnNumber());
            assertEquals("trace-exact", observation.trace().traceId());
            assertEquals(expectedSha256, observation.artifactSha256());
        }
    }

    @Test
    void diagnosticTraceByteLimitFailsClosedAtOneOver(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace")
                .toAbsolutePath().normalize();
        JsonSessionStore writerStore = new JsonSessionStore(sessions);
        String sessionId;
        try (SessionWriterLease writer = writerStore.reserveNewSession(
                workspace,
                Instant.parse("2026-08-03T12:00:00Z"))) {
            sessionId = writer.sessionId();
            writer.saveTrace(
                    sessionId,
                    trace(
                            JsonSessionStore.sessionIdFor(workspace),
                            1,
                            "trace-bounded"));
        }
        Path artifact = sessions.resolve("traces")
                .resolve(sessionId)
                .resolve("000001-trace-bounded.json");
        int artifactBytes = Math.toIntExact(Files.size(artifact));

        JsonSessionStore exactBoundary = new JsonSessionStore(
                sessions,
                limits(artifactBytes));
        assertEquals(
                TraceArtifactObservation.State.EXACT,
                exactBoundary.observeTraceArtifact(
                        sessionId, 1, "trace-bounded").state());

        JsonSessionStore oneByteOver = new JsonSessionStore(
                sessions,
                limits(artifactBytes - 1));
        assertStateWithoutEvidence(
                TraceArtifactObservation.State.UNAVAILABLE,
                oneByteOver.observeTraceArtifact(
                        sessionId, 1, "trace-bounded"));
    }

    @Test
    void absentInvalidAndUnavailableTraceStatesExposeNoEvidence(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace")
                .toAbsolutePath().normalize();
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId = JsonSessionStore.newSessionInstanceId(
                workspace,
                Instant.parse("2026-08-03T12:00:00Z"));

        assertStateWithoutEvidence(
                TraceArtifactObservation.State.ABSENT,
                store.observeTraceArtifact(sessionId, 1, "missing"));
        assertStateWithoutEvidence(
                TraceArtifactObservation.State.UNAVAILABLE,
                store.observeTraceArtifact("../unsafe", 1, "trace"));
        assertStateWithoutEvidence(
                TraceArtifactObservation.State.UNAVAILABLE,
                store.observeTraceArtifact(sessionId, 0, "trace"));
        assertStateWithoutEvidence(
                TraceArtifactObservation.State.UNAVAILABLE,
                store.observeTraceArtifact(sessionId, 1, "trace/alias"));
        assertStateWithoutEvidence(
                TraceArtifactObservation.State.UNAVAILABLE,
                store.observeTraceArtifact(sessionId, 1, "trace?alias"));
        assertStateWithoutEvidence(
                TraceArtifactObservation.State.UNAVAILABLE,
                store.observeTraceArtifact(sessionId, 1, "t".repeat(129)));

        Path invalidDir = sessions.resolve("traces").resolve(sessionId);
        Files.createDirectories(invalidDir);
        Path invalid = invalidDir.resolve("000001-invalid.json");
        Files.writeString(invalid, "{", StandardCharsets.UTF_8);
        assertStateWithoutEvidence(
                TraceArtifactObservation.State.INVALID,
                store.observeTraceArtifact(sessionId, 1, "invalid"));

        String directoryTraceId = "directory";
        Files.createDirectory(invalidDir.resolve("000002-directory.json"));
        assertStateWithoutEvidence(
                TraceArtifactObservation.State.UNAVAILABLE,
                store.observeTraceArtifact(sessionId, 2, directoryTraceId));

        SessionStore unsupported = new SessionStore() {
            @Override
            public void save(SessionData data) {
            }

            @Override
            public java.util.Optional<SessionData> load(String id) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean delete(String id) {
                return false;
            }
        };
        assertStateWithoutEvidence(
                TraceArtifactObservation.State.UNAVAILABLE,
                unsupported.observeTraceArtifact(sessionId, 1, "trace"));
    }

    @Test
    void linkedPhysicalTraceNeverBecomesDiagnosticEvidence(
            @TempDir Path temp
    ) throws Exception {
        Path externalRoot = temp.resolve("external-root");
        JsonSessionStore externalStore = new JsonSessionStore(externalRoot);
        String externalSessionId = "external-trace-session";
        String traceId = "linked-trace";
        try (SessionWriterLease writer = externalStore.acquireSessionWriter(
                externalSessionId)) {
            writer.saveTrace(
                    externalSessionId,
                    trace(externalSessionId, 1, traceId));
        }
        Path external = externalRoot.resolve("traces")
                .resolve(externalSessionId)
                .resolve("000001-linked-trace.json");
        byte[] before = Files.readAllBytes(external);

        Path sessions = temp.resolve("sessions");
        String sessionId = "linked-trace-session";
        Path traceDir = sessions.resolve("traces").resolve(sessionId);
        Files.createDirectories(traceDir);
        createSymbolicLinkOrAbort(
                traceDir.resolve("000001-linked-trace.json"), external);

        JsonSessionStore store = new JsonSessionStore(sessions);
        assertStateWithoutEvidence(
                TraceArtifactObservation.State.UNAVAILABLE,
                store.observeTraceArtifact(sessionId, 1, traceId));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(external)),
                "diagnostic observation must not alter linked external bytes");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsJunctionSessionRootNeverBecomesTraceDiagnosticEvidence(
            @TempDir Path temp
    ) throws Exception {
        Path externalRoot = temp.resolve("junction-external-root");
        JsonSessionStore externalStore = new JsonSessionStore(externalRoot);
        String sessionId = "junction-trace-session";
        String traceId = "junction-trace";
        try (SessionWriterLease writer = externalStore.acquireSessionWriter(
                sessionId)) {
            writer.saveTrace(sessionId, trace(sessionId, 1, traceId));
        }
        Path external = externalRoot.resolve("traces")
                .resolve(sessionId)
                .resolve("000001-junction-trace.json");
        byte[] before = Files.readAllBytes(external);

        Path linkedRoot = temp.resolve("junction-sessions");
        createJunctionOrAbort(linkedRoot, externalRoot);
        JsonSessionStore store = new JsonSessionStore(linkedRoot);

        assertStateWithoutEvidence(
                TraceArtifactObservation.State.UNAVAILABLE,
                store.observeTraceArtifact(sessionId, 1, traceId));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(external)),
                "junction observation must not alter external bytes");
    }

    @Test
    void validatedTraceReplacementProducesANewPhysicalDigest(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace")
                .toAbsolutePath().normalize();
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId;
        try (SessionWriterLease writer = store.reserveNewSession(
                workspace,
                Instant.parse("2026-08-03T12:00:00Z"))) {
            sessionId = writer.sessionId();
            writer.saveTrace(
                    sessionId,
                    trace(
                            JsonSessionStore.sessionIdFor(workspace),
                            1,
                            "trace-replaced"));
        }
        TraceArtifactObservation before = store.observeTraceArtifact(
                sessionId, 1, "trace-replaced");
        Path artifact = sessions.resolve("traces")
                .resolve(sessionId)
                .resolve("000001-trace-replaced.json");
        byte[] original = Files.readAllBytes(artifact);
        String replacement = new String(original, StandardCharsets.UTF_8) + "\n";
        Files.writeString(
                artifact,
                replacement,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);

        TraceArtifactObservation after = store.observeTraceArtifact(
                sessionId, 1, "trace-replaced");
        assertTrue(before.exact());
        assertTrue(after.exact());
        assertFalse(before.artifactSha256().equals(after.artifactSha256()));
        assertEquals(sha256(Files.readAllBytes(artifact)), after.artifactSha256());
    }

    @Test
    void traceMissingACurrentWriterFieldIsInvalidInsteadOfDefaulted(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace")
                .toAbsolutePath().normalize();
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId;
        try (SessionWriterLease writer = store.reserveNewSession(
                workspace,
                Instant.parse("2026-08-03T12:00:00Z"))) {
            sessionId = writer.sessionId();
            writer.saveTrace(
                    sessionId,
                    trace(
                            JsonSessionStore.sessionIdFor(workspace),
                            1,
                            "trace-incomplete"));
        }
        Path artifact = sessions.resolve("traces")
                .resolve(sessionId)
                .resolve("000001-trace-incomplete.json");
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                Files.readAllBytes(artifact));
        root.remove("warnings");
        Files.write(
                artifact,
                MAPPER.writeValueAsBytes(root),
                StandardOpenOption.TRUNCATE_EXISTING);

        assertStateWithoutEvidence(
                TraceArtifactObservation.State.INVALID,
                store.observeTraceArtifact(sessionId, 1, "trace-incomplete"));
    }

    @Test
    void legacyAndNestedIncompleteTracesStayReadableWithoutExactAuthority(
            @TempDir Path sessions
    ) throws Exception {
        assertLegacyReadableButObservationInvalid(
                sessions.resolve("legacy-schema"),
                "trace-schema-four",
                root -> root.put("schemaVersion", 4));
        assertLegacyReadableButObservationInvalid(
                sessions.resolve("nested-incomplete"),
                "trace-missing-memory-retention",
                root -> ((ObjectNode) root.get("promptAudit"))
                        .remove("memoryRetentionStatus"));
        assertLegacyReadableButObservationInvalid(
                sessions.resolve("null-outcome"),
                "trace-null-outcome",
                root -> root.putNull("outcome"));
    }

    private static void assertLegacyReadableButObservationInvalid(
            Path sessions,
            String traceId,
            TraceMutation mutation
    ) throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId = "session-" + traceId;
        try (SessionWriterLease writer = store.acquireSessionWriter(sessionId)) {
            writer.saveTrace(sessionId, trace(sessionId, 1, traceId));
        }
        Path artifact = sessions.resolve("traces")
                .resolve(sessionId)
                .resolve("000001-" + traceId + ".json");
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                Files.readAllBytes(artifact));
        mutation.apply(root);
        byte[] mutated = MAPPER.writeValueAsBytes(root);
        Files.write(
                artifact,
                mutated,
                StandardOpenOption.TRUNCATE_EXISTING);

        assertTrue(store.loadTrace(sessionId, traceId).isPresent(),
                "ordinary local trace loading remains legacy tolerant");
        assertStateWithoutEvidence(
                TraceArtifactObservation.State.INVALID,
                store.observeTraceArtifact(sessionId, 1, traceId));
        assertArrayEquals(mutated, Files.readAllBytes(artifact),
                "diagnostic observation must not rewrite legacy bytes");
    }

    private static LocalTurnTrace trace(
            String workspaceId,
            int turnNumber,
            String traceId
    ) {
        return LocalTurnTrace.builder(
                        traceId,
                        workspaceId,
                        turnNumber,
                        "2026-08-03T12:00:00Z")
                .mode("ask")
                .build();
    }

    private static TurnRecord row(int turnNumber, String traceId) {
        return new TurnRecord(
                turnNumber,
                Instant.parse("2026-08-03T12:00:01Z"),
                1L,
                "bounded request",
                "",
                java.util.List.of(),
                0,
                0,
                0,
                "",
                "failed",
                TurnPolicyTrace.empty(),
                traceId);
    }

    private static JsonSessionStore.DiagnosticObservationLimits limits(
            int maxTraceBytes
    ) {
        return new JsonSessionStore.DiagnosticObservationLimits(
                1024 * 1024,
                maxTraceBytes,
                16,
                32,
                8);
    }

    private static void assertStateWithoutEvidence(
            TraceArtifactObservation.State expected,
            TraceArtifactObservation observation
    ) {
        assertEquals(expected, observation.state());
        assertFalse(observation.exact());
        assertNull(observation.trace());
        assertEquals("", observation.artifactSha256());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Process startWriterHolder(
            Path sessions,
            String sessionId,
            Path ready,
            Path release
    ) throws Exception {
        String executable = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                JsonSessionStoreWriterLeaseProcessMain.class.getName(),
                "hold",
                sessions.toString(),
                sessionId,
                ready.toString(),
                release.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static void awaitFile(Path file, Process child) throws Exception {
        long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
        while (!Files.exists(file)) {
            if (!child.isAlive()) {
                throw new AssertionError("child exited before ready: "
                        + new String(
                                child.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8));
            }
            if (System.nanoTime() >= deadline) {
                child.destroyForcibly();
                throw new AssertionError("child readiness timed out");
            }
            Thread.sleep(10L);
        }
    }

    private static ProcessResult await(Process child) throws Exception {
        if (!child.waitFor(
                PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("child exit timed out");
        }
        return new ProcessResult(
                child.exitValue(),
                new String(
                        child.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8));
    }

    private record ProcessResult(int exitCode, String output) { }

    public static final class OversizedReservationObservationProcessMain {
        private OversizedReservationObservationProcessMain() {
        }

        public static void main(String[] args) {
            if (args.length != 2) {
                throw new IllegalArgumentException(
                        "expected sessions path and session ID");
            }
            TerminalTurnPairObservation observation = new JsonSessionStore(
                    Path.of(args[0])).observeTerminalTurnPair(args[1]);
            if (observation.state()
                    != TerminalTurnPairObservation.State.UNAVAILABLE) {
                throw new AssertionError(
                        "oversized reservation marker must fail closed: "
                                + observation.state());
            }
        }
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target)
            throws IOException {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath().normalize());
        } catch (UnsupportedOperationException | SecurityException | IOException failure) {
            assumeTrue(false, "symbolic links are unavailable on this host: "
                    + failure.getMessage());
        }
    }

    private static void createJunctionOrAbort(Path junction, Path target)
            throws Exception {
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assumeTrue(exit == 0 && Files.exists(junction),
                "Windows junction fixture unavailable: " + output);
    }

    @FunctionalInterface
    private interface TraceMutation {
        void apply(ObjectNode root);
    }
}
