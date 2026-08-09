package dev.talos.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JsonSessionStoreTurnLogTailObservationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void exactLfTailBindsPhysicalAppendOrdinalAndRawRowDigest(
            @TempDir Path sessions
    ) throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId = "session-exact-tail";

        TurnLogTailObservation viaLease;
        try (SessionWriterLease writer = store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(sessionId, row(
                    1, "first", Instant.parse("2026-08-03T10:00:00Z")));
            writer.appendTurn(sessionId, row(
                    2, "second", Instant.parse("2026-08-03T10:00:01Z")));
            viaLease = writer.observeTurnLogTail(sessionId);
        }

        byte[] bytes = Files.readAllBytes(
                sessions.resolve(sessionId + ".turns.jsonl"));
        assertEquals((byte) '\n', bytes[bytes.length - 1]);
        int priorLf = -1;
        for (int index = bytes.length - 2; index >= 0; index--) {
            if (bytes[index] == (byte) '\n') {
                priorLf = index;
                break;
            }
        }
        byte[] physicalTail = java.util.Arrays.copyOfRange(
                bytes, priorLf + 1, bytes.length - 1);
        String expectedDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(physicalTail));

        TurnLogTailObservation direct = store.observeTurnLogTail(sessionId);
        for (TurnLogTailObservation observation : List.of(viaLease, direct)) {
            assertEquals(TurnLogTailObservation.State.EXACT, observation.state());
            assertTrue(observation.exact());
            assertEquals(2, observation.appendOrdinal());
            assertEquals(2, observation.turn().turnNumber());
            assertEquals("second", observation.turn().userInput());
            assertEquals(expectedDigest, observation.rowSha256());
        }
    }

    @Test
    void diagnosticTurnLogByteAndRowLimitsFailClosedAtOneOver(
            @TempDir Path sessions
    ) throws Exception {
        JsonSessionStore writerStore = new JsonSessionStore(sessions);
        String sessionId = "session-bounded-tail";
        appendOne(writerStore, sessionId);
        Path log = sessions.resolve(sessionId + ".turns.jsonl");
        int oneRowBytes = Math.toIntExact(Files.size(log));

        JsonSessionStore exactBoundary = new JsonSessionStore(
                sessions,
                limits(oneRowBytes, 1));
        assertEquals(
                TurnLogTailObservation.State.EXACT,
                exactBoundary.observeTurnLogTail(sessionId).state());

        JsonSessionStore oneByteOver = new JsonSessionStore(
                sessions,
                limits(oneRowBytes - 1, 1));
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.UNAVAILABLE,
                oneByteOver.observeTurnLogTail(sessionId));

        try (SessionWriterLease writer = writerStore.acquireSessionWriter(
                sessionId)) {
            writer.appendTurn(
                    sessionId,
                    row(2, "second", Instant.parse("2026-08-03T10:00:01Z")));
        }
        int twoRowBytes = Math.toIntExact(Files.size(log));
        JsonSessionStore oneRowOver = new JsonSessionStore(
                sessions,
                limits(twoRowBytes, 1));
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.UNAVAILABLE,
                oneRowOver.observeTurnLogTail(sessionId));
    }

    @Test
    void newlineDenseRowLimitPassesAtTheBoundaryAndFailsAtOneOver(
            @TempDir Path temp
    ) throws Exception {
        int maxRows = 4_096;

        Path boundaryRoot = temp.resolve("newline-boundary");
        JsonSessionStore boundaryStore = new JsonSessionStore(
                boundaryRoot,
                limits(maxRows, maxRows));
        String boundaryId = "session-newline-boundary";
        Files.write(
                boundaryRoot.resolve(boundaryId + ".turns.jsonl"),
                newlineBytes(maxRows));
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.INVALID,
                boundaryStore.observeTurnLogTail(boundaryId));

        Path oneOverRoot = temp.resolve("newline-one-over");
        JsonSessionStore oneOverStore = new JsonSessionStore(
                oneOverRoot,
                limits(maxRows + 1, maxRows));
        String oneOverId = "session-newline-one-over";
        Files.write(
                oneOverRoot.resolve(oneOverId + ".turns.jsonl"),
                newlineBytes(maxRows + 1));
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.UNAVAILABLE,
                oneOverStore.observeTurnLogTail(oneOverId));
    }

    @Test
    void newlineDenseOneOverFailsClosedWithinABoundedChildHeap(
            @TempDir Path sessions
    ) throws Exception {
        String sessionId = "session-newline-bounded-heap";
        byte[] dense = newlineBytes(16 * 1024 * 1024);
        Files.write(sessions.resolve(sessionId + ".turns.jsonl"), dense);

        String executable = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Process child = new ProcessBuilder(
                java.toString(),
                "-Xmx96m",
                "-cp",
                System.getProperty("java.class.path"),
                NewlineDenseObservationProcessMain.class.getName(),
                sessions.toString(),
                sessionId)
                .redirectErrorStream(true)
                .start();
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError(
                    "bounded diagnostic child did not settle within 30 seconds");
        }
        String output = new String(
                child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, child.exitValue(), output);
    }

    @Test
    void missingUnsafeAndNonRegularLogsHaveFailClosedTypedStates(
            @TempDir Path sessions
    ) throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);

        assertStateWithoutEvidence(
                TurnLogTailObservation.State.ABSENT,
                store.observeTurnLogTail("session-missing"));
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.UNAVAILABLE,
                store.observeTurnLogTail("../unsafe"));

        String directoryId = "session-directory-tail";
        Files.createDirectories(sessions.resolve(directoryId + ".turns.jsonl"));
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.UNAVAILABLE,
                store.observeTurnLogTail(directoryId));

        SessionStore unsupported = new SessionStore() {
            @Override
            public void save(SessionData data) {
            }

            @Override
            public java.util.Optional<SessionData> load(String sessionId) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean delete(String sessionId) {
                return false;
            }
        };
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.UNAVAILABLE,
                unsupported.observeTurnLogTail("session-any"));
    }

    @Test
    void indeterminateSessionRootNeverBecomesAnAbsentTurnLog(
            @TempDir Path temp
    ) throws Exception {
        Path sessions = temp.resolve("sessions");
        JsonSessionStore store = new JsonSessionStore(sessions);
        Files.delete(sessions);
        Files.writeString(sessions, "not-a-directory", StandardCharsets.UTF_8);

        assertStateWithoutEvidence(
                TurnLogTailObservation.State.UNAVAILABLE,
                store.observeTurnLogTail("session-indeterminate-root"));
    }

    @Test
    void linkedPhysicalTurnLogNeverBecomesDiagnosticEvidence(
            @TempDir Path temp
    ) throws Exception {
        Path externalRoot = temp.resolve("external-root");
        JsonSessionStore externalStore = new JsonSessionStore(externalRoot);
        String externalId = "external-turn-log";
        appendOne(externalStore, externalId);
        Path external = externalRoot.resolve(externalId + ".turns.jsonl");
        byte[] before = Files.readAllBytes(external);

        Path sessions = temp.resolve("sessions");
        Files.createDirectories(sessions);
        String sessionId = "linked-turn-log";
        createSymbolicLinkOrAbort(
                sessions.resolve(sessionId + ".turns.jsonl"), external);

        JsonSessionStore store = new JsonSessionStore(sessions);
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.UNAVAILABLE,
                store.observeTurnLogTail(sessionId));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(external)),
                "diagnostic observation must not alter linked external bytes");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsJunctionSessionRootNeverBecomesTurnDiagnosticEvidence(
            @TempDir Path temp
    ) throws Exception {
        Path externalRoot = temp.resolve("junction-external-root");
        JsonSessionStore externalStore = new JsonSessionStore(externalRoot);
        String sessionId = "junction-turn-log";
        appendOne(externalStore, sessionId);
        Path external = externalRoot.resolve(sessionId + ".turns.jsonl");
        byte[] before = Files.readAllBytes(external);

        Path linkedRoot = temp.resolve("junction-sessions");
        createJunctionOrAbort(linkedRoot, externalRoot);
        JsonSessionStore store = new JsonSessionStore(linkedRoot);

        assertStateWithoutEvidence(
                TurnLogTailObservation.State.UNAVAILABLE,
                store.observeTurnLogTail(sessionId));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(external)),
                "junction observation must not alter external bytes");
    }

    @Test
    void incompleteOrMalformedPhysicalTailNeverBacktracksToAValidRow(
            @TempDir Path sessions
    ) throws Exception {
        assertInvalidButLenientlyLoadsFirstRow(
                sessions.resolve("torn"),
                bytes -> concat(bytes, "{\"turnNumber\":".getBytes(StandardCharsets.UTF_8)));
        assertInvalidButLenientlyLoadsFirstRow(
                sessions.resolve("malformed"),
                bytes -> concat(bytes, "not-json\n".getBytes(StandardCharsets.UTF_8)));
        assertInvalidButLenientlyLoadsFirstRow(
                sessions.resolve("malformed-utf8"),
                bytes -> concat(bytes, new byte[] {(byte) 0xff, (byte) 0xfe, '\n'}));
    }

    @Test
    void emptyCrLfAndNonExactJsonShapeAreInvalidForDiagnosticSelection(
            @TempDir Path sessions
    ) throws Exception {
        JsonSessionStore emptyStore = new JsonSessionStore(sessions.resolve("empty"));
        String emptyId = "session-empty";
        Files.createDirectories(sessions.resolve("empty"));
        Files.write(sessions.resolve("empty").resolve(emptyId + ".turns.jsonl"),
                new byte[0]);
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.INVALID,
                emptyStore.observeTurnLogTail(emptyId));

        Path crlfRoot = sessions.resolve("crlf");
        JsonSessionStore crlfStore = new JsonSessionStore(crlfRoot);
        String crlfId = "session-crlf";
        appendOne(crlfStore, crlfId);
        Path crlfFile = crlfRoot.resolve(crlfId + ".turns.jsonl");
        String lf = Files.readString(crlfFile, StandardCharsets.UTF_8);
        Files.writeString(
                crlfFile,
                lf.replace("\n", "\r\n"),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        assertEquals(1, crlfStore.loadTurns(crlfId).size());
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.INVALID,
                crlfStore.observeTurnLogTail(crlfId));

        Path shapeRoot = sessions.resolve("shape");
        JsonSessionStore shapeStore = new JsonSessionStore(shapeRoot);
        String shapeId = "session-extra-key";
        appendOne(shapeStore, shapeId);
        Path shapeFile = shapeRoot.resolve(shapeId + ".turns.jsonl");
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                Files.readString(shapeFile, StandardCharsets.UTF_8));
        root.put("unexpected", "field");
        Files.writeString(
                shapeFile,
                MAPPER.writeValueAsString(root) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        assertEquals(1, shapeStore.loadTurns(shapeId).size());
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.INVALID,
                shapeStore.observeTurnLogTail(shapeId));

        Path policyRoot = sessions.resolve("incomplete-policy");
        JsonSessionStore policyStore = new JsonSessionStore(policyRoot);
        String policyId = "session-incomplete-policy";
        appendOne(policyStore, policyId);
        Path policyFile = policyRoot.resolve(policyId + ".turns.jsonl");
        ObjectNode policyRow = (ObjectNode) MAPPER.readTree(
                Files.readString(policyFile, StandardCharsets.UTF_8));
        policyRow.set("policyTrace", MAPPER.createObjectNode());
        Files.writeString(
                policyFile,
                MAPPER.writeValueAsString(policyRow) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        assertEquals(1, policyStore.loadTurns(policyId).size(),
                "ordinary recovery remains backward compatible");
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.INVALID,
                policyStore.observeTurnLogTail(policyId));

        Path identityRoot = sessions.resolve("unsafe-trace-identity");
        JsonSessionStore identityStore = new JsonSessionStore(identityRoot);
        String identityId = "session-unsafe-trace-identity";
        appendOne(identityStore, identityId);
        Path identityFile = identityRoot.resolve(
                identityId + ".turns.jsonl");
        ObjectNode identityRow = (ObjectNode) MAPPER.readTree(
                Files.readString(identityFile, StandardCharsets.UTF_8));
        identityRow.put("traceId", "trace/alias");
        Files.writeString(
                identityFile,
                MAPPER.writeValueAsString(identityRow) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        assertEquals(1, identityStore.loadTurns(identityId).size());
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.INVALID,
                identityStore.observeTurnLogTail(identityId));
    }

    @Test
    void duplicateAndDescendingTurnsInvalidateSelectionWhileGapsRemainExact(
            @TempDir Path sessions
    ) {
        assertChronology(
                sessions.resolve("duplicate"),
                1,
                1,
                TurnLogTailObservation.State.INVALID);
        assertChronology(
                sessions.resolve("descending"),
                2,
                1,
                TurnLogTailObservation.State.INVALID);
        assertChronology(
                sessions.resolve("gap"),
                1,
                3,
                TurnLogTailObservation.State.EXACT);
    }

    @Test
    void legacyPolicyAndNormalizedToolPathRemainReadableButNeverExact(
            @TempDir Path temp
    ) throws Exception {
        Path legacyRoot = temp.resolve("legacy-policy");
        JsonSessionStore legacyStore = new JsonSessionStore(legacyRoot);
        String legacyId = "session-legacy-policy";
        appendOne(legacyStore, legacyId);
        Path legacyFile = legacyRoot.resolve(legacyId + ".turns.jsonl");
        ObjectNode legacyRow = (ObjectNode) MAPPER.readTree(
                Files.readString(legacyFile, StandardCharsets.UTF_8));
        ObjectNode legacyPolicy = (ObjectNode) legacyRow.get("policyTrace");
        legacyPolicy.put("lexicalEvidenceSchemaVersion", 1);
        legacyPolicy.remove("workspaceOperationPlan");
        byte[] legacyBytes = (MAPPER.writeValueAsString(legacyRow) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(
                legacyFile,
                legacyBytes,
                StandardOpenOption.TRUNCATE_EXISTING);
        assertEquals(1, legacyStore.loadTurns(legacyId).size());
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.INVALID,
                legacyStore.observeTurnLogTail(legacyId));
        assertTrue(java.util.Arrays.equals(
                legacyBytes,
                Files.readAllBytes(legacyFile)),
                "observation must not rewrite a legacy row");

        Path normalizedRoot = temp.resolve("normalized-tool-path");
        JsonSessionStore normalizedStore = new JsonSessionStore(normalizedRoot);
        String normalizedId = "session-normalized-tool-path";
        appendOne(normalizedStore, normalizedId);
        Path normalizedFile = normalizedRoot.resolve(
                normalizedId + ".turns.jsonl");
        ObjectNode normalizedRow = (ObjectNode) MAPPER.readTree(
                Files.readString(normalizedFile, StandardCharsets.UTF_8));
        ObjectNode call = MAPPER.createObjectNode();
        call.put("name", "read_file");
        call.put("pathHint", "./a.txt");
        call.put("success", true);
        call.put("reason", "");
        ((com.fasterxml.jackson.databind.node.ArrayNode)
                normalizedRow.get("toolCalls")).add(call);
        byte[] normalizedBytes = (
                MAPPER.writeValueAsString(normalizedRow) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(
                normalizedFile,
                normalizedBytes,
                StandardOpenOption.TRUNCATE_EXISTING);
        assertEquals("a.txt", normalizedStore.loadTurns(normalizedId)
                .getFirst().toolCalls().getFirst().pathHint());
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.INVALID,
                normalizedStore.observeTurnLogTail(normalizedId));
        assertTrue(java.util.Arrays.equals(
                normalizedBytes,
                Files.readAllBytes(normalizedFile)),
                "observation must not rewrite a normalized legacy row");
    }

    private static void assertChronology(
            Path sessions,
            int firstTurn,
            int secondTurn,
            TurnLogTailObservation.State expectedState
    ) {
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId = "session-chronology";
        try (SessionWriterLease writer = store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(sessionId, row(
                    firstTurn,
                    "first",
                    Instant.parse("2026-08-03T10:00:00Z")));
            writer.appendTurn(sessionId, row(
                    secondTurn,
                    "second",
                    Instant.parse("2026-08-03T10:00:01Z")));
        }

        assertEquals(2, store.loadTurns(sessionId).size(),
                "ordinary recovery remains lenient");
        TurnLogTailObservation observation = store.observeTurnLogTail(sessionId);
        assertEquals(expectedState, observation.state());
        if (expectedState == TurnLogTailObservation.State.EXACT) {
            assertTrue(observation.exact());
            assertEquals(secondTurn, observation.turn().turnNumber());
            assertEquals(2, observation.appendOrdinal());
        } else {
            assertStateWithoutEvidence(expectedState, observation);
        }
    }

    private static void assertInvalidButLenientlyLoadsFirstRow(
            Path sessions,
            ByteMutation mutation
    ) throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId = "session-damaged";
        appendOne(store, sessionId);
        Path file = sessions.resolve(sessionId + ".turns.jsonl");
        Files.write(
                file,
                mutation.apply(Files.readAllBytes(file)),
                StandardOpenOption.TRUNCATE_EXISTING);

        List<TurnRecord> recovered = store.loadTurns(sessionId);
        assertEquals(1, recovered.size());
        assertEquals("first", recovered.getFirst().userInput());
        assertStateWithoutEvidence(
                TurnLogTailObservation.State.INVALID,
                store.observeTurnLogTail(sessionId));
    }

    private static void appendOne(JsonSessionStore store, String sessionId) {
        try (SessionWriterLease writer = store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(sessionId, row(
                    1, "first", Instant.parse("2026-08-03T10:00:00Z")));
        }
    }

    private static TurnRecord row(int number, String request, Instant timestamp) {
        return new TurnRecord(
                number,
                timestamp,
                1,
                request,
                "answer-" + number,
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                TurnPolicyTrace.empty(),
                "trace-" + number);
    }

    private static JsonSessionStore.DiagnosticObservationLimits limits(
            int maxTurnLogBytes,
            int maxTurnRows
    ) {
        return new JsonSessionStore.DiagnosticObservationLimits(
                maxTurnLogBytes,
                1024 * 1024,
                maxTurnRows,
                32,
                8);
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] combined = java.util.Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, combined, left.length, right.length);
        return combined;
    }

    private static byte[] newlineBytes(int count) {
        byte[] bytes = new byte[count];
        java.util.Arrays.fill(bytes, (byte) '\n');
        return bytes;
    }

    public static final class NewlineDenseObservationProcessMain {
        private NewlineDenseObservationProcessMain() {
        }

        public static void main(String[] args) {
            if (args.length != 2) {
                throw new IllegalArgumentException(
                        "expected sessions path and session ID");
            }
            TurnLogTailObservation observation = new JsonSessionStore(
                    Path.of(args[0])).observeTurnLogTail(args[1]);
            if (observation.state()
                    != TurnLogTailObservation.State.UNAVAILABLE) {
                throw new AssertionError(
                        "newline-dense observation must fail closed: "
                                + observation.state());
            }
        }
    }

    private static void assertStateWithoutEvidence(
            TurnLogTailObservation.State expected,
            TurnLogTailObservation observation
    ) {
        assertEquals(expected, observation.state());
        assertFalse(observation.exact());
        assertNull(observation.turn());
        assertEquals(0, observation.appendOrdinal());
        assertEquals("", observation.rowSha256());
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
    private interface ByteMutation {
        byte[] apply(byte[] bytes);
    }
}
