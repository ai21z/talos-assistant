package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationTraceEvidencePublicationTest {
    @TempDir
    Path temp;

    @Test
    void publishesExactContainedSchemaFiveTraceAndExactRetryIsReadOnly() throws Exception {
        Fixture fixture = fixture("exact");
        byte[] sourceBytes = schemaFiveTrace(fixture);
        Path source = writeSource(fixture, "session-one", sourceBytes);
        Files.deleteIfExists(fixture.target());

        QualificationTraceEvidencePublication.Result first =
                QualificationTraceEvidencePublication.publish(
                        fixture.packet().root(),
                        fixture.packet().runRoot(),
                        fixture.scenarioId(),
                        fixture.catalog());
        var targetMtime = Files.getLastModifiedTime(fixture.target());
        var commitMtime = Files.getLastModifiedTime(
                QualificationPacketPublisher.commitPathForTest(
                        fixture.packet().root(),
                        QualificationPacketPublisher.PublicationKind.TRACE_EVIDENCE,
                        List.of(fixture.target())));

        QualificationTraceEvidencePublication.Result second =
                QualificationTraceEvidencePublication.publish(
                        fixture.packet().root(),
                        fixture.packet().runRoot(),
                        fixture.scenarioId(),
                        fixture.catalog());
        Optional<QualificationTraceEvidencePublication.CommittedTrace> committed =
                QualificationTraceEvidencePublication.requireCommittedForSchema(
                        fixture.packet().root(), fixture.packet().runRoot(), 5, sourceBytes);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals("trace-1", first.traceId());
        assertEquals(QualificationScenarioBank.sha256(sourceBytes), first.sha256());
        assertArrayEquals(sourceBytes, Files.readAllBytes(fixture.target()));
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        assertTrue(committed.orElseThrow().matches(sourceBytes));
        assertEquals(targetMtime, Files.getLastModifiedTime(fixture.target()));
        assertEquals(commitMtime, Files.getLastModifiedTime(
                QualificationPacketPublisher.commitPathForTest(
                        fixture.packet().root(),
                        QualificationPacketPublisher.PublicationKind.TRACE_EVIDENCE,
                        List.of(fixture.target()))));
        byte[] differentObservation = sourceBytes.clone();
        differentObservation[differentObservation.length - 1] ^= 1;
        assertThrows(IOException.class,
                () -> QualificationTraceEvidencePublication.requireCommittedForSchema(
                        fixture.packet().root(), fixture.packet().runRoot(), 5,
                        differentObservation));
    }

    @Test
    void schemaFourIsReadableWithoutTraceCommitWhileSchemaFiveFailsClosed() throws Exception {
        Fixture fixture = legacyFixture("version-gate");

        assertTrue(QualificationTraceEvidencePublication.requireCommittedForSchema(
                fixture.packet().root(), fixture.packet().runRoot(), 4,
                Files.readAllBytes(fixture.target())).isEmpty());
        QualificationPacketPublisher.PublicationException missing = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationTraceEvidencePublication.requireCommittedForSchema(
                        fixture.packet().root(), fixture.packet().runRoot(), 5,
                        Files.readAllBytes(fixture.target())));
        assertEquals(QualificationPacketPublisher.FailureReason.INCOMPLETE, missing.reason());
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidencePublication.requireCommittedForSchema(
                        fixture.packet().root(), fixture.packet().runRoot(), 3, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidencePublication.requireCommittedForSchema(
                        fixture.packet().root(), fixture.packet().runRoot(), 6, new byte[0]));
    }

    @Test
    void lastTraceSelectionUsesOnlyOneBoundedCommandWindow() {
        QualificationPtyEvidence.Capture ansi = capture(List.of(
                event("output", "  Local trace: stale\r\n"),
                event("input", "last-trace"),
                event("output", "\r\n  Local trace: trace-1\u001B[0K\r\n"),
                event("input", "exit"),
                event("output", "  Local trace: later\r\n")));
        assertEquals("trace-1", QualificationTraceEvidencePublication.selectedTraceId(ansi));

        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidencePublication.selectedTraceId(capture(List.of(
                        event("input", "last-trace"),
                        event("input", "exit"),
                        event("output", "  Local trace: trace-1\r\n")))));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidencePublication.selectedTraceId(capture(List.of(
                        event("input", "last-trace"),
                        event("output", "  Local trace: trace-1\r\n"
                                + "  Local trace: trace-1\r\n")))));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidencePublication.selectedTraceId(capture(List.of(
                        event("input", "last-trace"),
                        event("output", "  Local trace: trace-1\u001B[31m\r\n")))));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidencePublication.selectedTraceId(capture(List.of(
                        event("input", "last-trace"),
                        event("output", "  Local trace: trace-1\r\n"),
                        event("input", "last-trace"),
                        event("output", "  Local trace: trace-1\r\n")))));
    }

    @Test
    void publicationRejectsMissingDuplicateAndCoordinateMismatchedSelectedTraces()
            throws Exception {
        Fixture missing = fixture("missing-selected");
        Files.delete(sourcePath(missing, "session-one"));
        Files.deleteIfExists(missing.target());
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidencePublication.publish(
                        missing.packet().root(), missing.packet().runRoot(),
                        missing.scenarioId(), missing.catalog()));

        Fixture duplicate = fixture("duplicate-selected");
        byte[] duplicateBytes = schemaFiveTrace(duplicate);
        writeSource(duplicate, "session-one", duplicateBytes);
        writeSource(duplicate, "session-two", duplicateBytes);
        Files.deleteIfExists(duplicate.target());
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidencePublication.publish(
                        duplicate.packet().root(), duplicate.packet().runRoot(),
                        duplicate.scenarioId(), duplicate.catalog()));

        Fixture coordinates = fixture("coordinate-mismatch");
        byte[] coordinateBytes = schemaFiveTrace(coordinates);
        Files.delete(sourcePath(coordinates, "session-one"));
        writeSource(coordinates, "session-two", coordinateBytes);
        Files.deleteIfExists(coordinates.target());
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidencePublication.publish(
                        coordinates.packet().root(), coordinates.packet().runRoot(),
                        coordinates.scenarioId(), coordinates.catalog()));
    }

    @Test
    void malformedProcessReceiptCannotCreateTraceTargetOrCommit() throws Exception {
        Fixture fixture = fixture("malformed-process-receipt");
        ObjectNode trace = (ObjectNode) QualificationJson.readTree(
                schemaFiveTrace(fixture));
        providerProcessReceiptData(trace).put("processId", "42");
        byte[] malformed = QualificationJson.bytes(trace);
        Path source = writeSource(fixture, "session-one", malformed);
        Path commit = QualificationPacketPublisher.commitPathForTest(
                fixture.packet().root(),
                QualificationPacketPublisher.PublicationKind.TRACE_EVIDENCE,
                List.of(fixture.target()));

        assertFalse(Files.exists(fixture.target()));
        assertFalse(Files.exists(commit));
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidencePublication.publish(
                        fixture.packet().root(),
                        fixture.packet().runRoot(),
                        fixture.scenarioId(),
                        fixture.catalog()));
        assertFalse(Files.exists(fixture.target()));
        assertFalse(Files.exists(commit));
        assertArrayEquals(malformed, Files.readAllBytes(source));
    }

    @Test
    void publicationRequiresTheExactCommittedPtyCapture() throws Exception {
        Fixture tampered = fixture("tampered-capture");
        writeSource(tampered, "session-one", schemaFiveTrace(tampered));
        Files.deleteIfExists(tampered.target());
        Files.writeString(
                tampered.packet().runRoot().resolve("transcript.raw.json"),
                "{}",
                java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IOException.class,
                () -> QualificationTraceEvidencePublication.publish(
                        tampered.packet().root(), tampered.packet().runRoot(),
                        tampered.scenarioId(), tampered.catalog()));

        Fixture uncommitted = fixture("uncommitted-capture");
        writeSource(uncommitted, "session-one", schemaFiveTrace(uncommitted));
        Files.deleteIfExists(uncommitted.target());
        Path transcript = uncommitted.packet().runRoot().resolve("transcript.raw.json");
        Files.delete(QualificationPacketPublisher.commitPathForTest(
                uncommitted.packet().root(),
                QualificationPacketPublisher.PublicationKind.PTY_CAPTURE,
                List.of(transcript)));
        assertThrows(QualificationPacketPublisher.PublicationException.class,
                () -> QualificationTraceEvidencePublication.publish(
                        uncommitted.packet().root(), uncommitted.packet().runRoot(),
                        uncommitted.scenarioId(), uncommitted.catalog()));
    }

    @Test
    void selectedSourceMustBeAnOrdinaryCurrentTraceInsideTalosSessionStorage()
            throws Exception {
        Fixture historical = fixture("historical-source");
        ObjectNode historicalTrace = (ObjectNode) QualificationJson.readTree(
                schemaFiveTrace(historical));
        historicalTrace.put("schemaVersion", 4);
        writeSource(historical, "session-one", QualificationJson.bytes(historicalTrace));
        Files.deleteIfExists(historical.target());
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidencePublication.publish(
                        historical.packet().root(), historical.packet().runRoot(),
                        historical.scenarioId(), historical.catalog()));
        assertFalse(Files.exists(historical.target()));

        Fixture linked = fixture("linked-source");
        Path linkedExternal = temp.resolve("linked-external.json");
        Files.write(linkedExternal, schemaFiveTrace(linked));
        Path link = linked.packet().root()
                .resolve("home/.talos/sessions/traces/session-one/000001-trace-1.json");
        Files.delete(link);
        createSymbolicLinkOrAbort(link, linkedExternal);
        Files.deleteIfExists(linked.target());
        assertThrows(IOException.class,
                () -> QualificationTraceEvidencePublication.publish(
                        linked.packet().root(), linked.packet().runRoot(),
                        linked.scenarioId(), linked.catalog()));
        assertFalse(Files.exists(linked.target()));
    }

    @Test
    void interruptedTracePublicationRecoversOnlyTheExactSelectedBytes() throws Exception {
        Fixture fixture = fixture("recovery");
        byte[] bytes = schemaFiveTrace(fixture);
        writeSource(fixture, "session-one", bytes);
        Files.deleteIfExists(fixture.target());

        assertThrows(IOException.class, () -> QualificationPacketPublisher.withLease(
                fixture.packet().root(),
                Duration.ofSeconds(5),
                (stage, ordinal) -> {
                    if (stage == QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION
                            && ordinal == 0) {
                        throw new IOException("injected trace publication interruption");
                    }
                },
                lease -> lease.publish(
                        QualificationPacketPublisher.PublicationKind.TRACE_EVIDENCE,
                        List.of(new QualificationPacketPublisher.Member(
                                fixture.target(), bytes)))));

        QualificationTraceEvidencePublication.Result recovered =
                QualificationTraceEvidencePublication.publish(
                        fixture.packet().root(), fixture.packet().runRoot(),
                        fixture.scenarioId(), fixture.catalog());

        assertTrue(recovered.created());
        assertArrayEquals(bytes, Files.readAllBytes(fixture.target()));
        QualificationPacketPublisher.requireSettledControlState(fixture.packet().root());
        assertTrue(QualificationTraceEvidencePublication.requireCommittedForSchema(
                fixture.packet().root(), fixture.packet().runRoot(), 5, bytes)
                .orElseThrow().matches(bytes));
    }

    @Test
    void tracePublicationIsUnphasedAndDoesNotChangeExistingRunTransactionIdentities()
            throws Exception {
        Fixture fixture = fixture("unphased");
        Path prompt = fixture.packet().runRoot().resolve("prompt-debug.txt");
        Path model = fixture.packet().runRoot().resolve("llama.log");
        Path causal = fixture.packet().runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE);
        Path canary = fixture.packet().runRoot().resolve(QualificationRunFinalizer.CANARY_FILE);
        Path result = fixture.packet().runRoot().resolve(QualificationRunFinalizer.RESULT_FILE);
        QualificationPacketPublisher.CommitRecord providerBefore =
                QualificationPacketPublisher.requireCommitted(
                        fixture.packet().root(),
                        QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                        List.of(prompt, model)).commit();
        QualificationPacketPublisher.CommitRecord finalizationBefore =
                QualificationPacketPublisher.requireCommitted(
                        fixture.packet().root(),
                        QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                        List.of(causal, canary, result)).commit();
        assertEquals(
                "69cc79c3d43a08902805142cad3defa2574e6c1ea63b1ba32609ee776de66b6f",
                providerBefore.transactionId());
        assertEquals(
                "874fe19664d2672c88fd4f55ba2bd7d42ab361467654f42777f50be80219a6bb",
                finalizationBefore.transactionId());
        byte[] bytes = schemaFiveTrace(fixture);
        writeSource(fixture, "session-one", bytes);
        Files.deleteIfExists(fixture.target());

        QualificationTraceEvidencePublication.publish(
                fixture.packet().root(), fixture.packet().runRoot(),
                fixture.scenarioId(), fixture.catalog());
        QualificationPacketPublisher.CommitRecord trace =
                QualificationPacketPublisher.requireCommitted(
                        fixture.packet().root(),
                        QualificationPacketPublisher.PublicationKind.TRACE_EVIDENCE,
                        List.of(fixture.target())).commit();
        QualificationPacketPublisher.CommitRecord providerAfter =
                QualificationPacketPublisher.requireCommitted(
                        fixture.packet().root(),
                        QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                        List.of(prompt, model)).commit();
        QualificationPacketPublisher.CommitRecord finalizationAfter =
                QualificationPacketPublisher.requireCommitted(
                        fixture.packet().root(),
                        QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                        List.of(causal, canary, result)).commit();

        assertEquals(providerBefore.transactionId(), providerAfter.transactionId());
        assertEquals(finalizationBefore.transactionId(), finalizationAfter.transactionId());
        assertEquals(providerBefore, providerAfter);
        assertEquals(finalizationBefore, finalizationAfter);
        assertEquals(0, trace.runPhaseOrdinal());
        assertTrue(trace.predecessorTransactionId().isBlank());
        assertTrue(trace.predecessorCommitSha256().isBlank());
    }

    private Fixture fixture(String name) throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario =
                catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.createProcessBound(
                temp.resolve(name), catalog, scenario);
        Fixture fixture = new Fixture(
                packet,
                catalog,
                scenario.id(),
                packet.runRoot().resolve(QualificationTraceEvidencePublication.OUTPUT_FILE));
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.TRACE_EVIDENCE,
                List.of(fixture.target()));
        return fixture;
    }

    private Fixture legacyFixture(String name) throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve(name), catalog);
        return new Fixture(
                packet,
                catalog,
                catalog.admission().scenarios().getFirst().id(),
                packet.runRoot().resolve(QualificationTraceEvidencePublication.OUTPUT_FILE));
    }

    private static byte[] schemaFiveTrace(Fixture fixture) throws IOException {
        return Files.readAllBytes(sourcePath(fixture, "session-one"));
    }

    private static QualificationPtyEvidence.Event event(String type, String data) {
        return new QualificationPtyEvidence.Event("2026-07-10T00:00:01Z", type, data);
    }

    private static QualificationPtyEvidence.Capture capture(
            List<QualificationPtyEvidence.Event> events) {
        String rawOutput = events.stream()
                .filter(event -> event.type().equals("output"))
                .map(QualificationPtyEvidence.Event::data)
                .collect(java.util.stream.Collectors.joining());
        return new QualificationPtyEvidence.Capture(
                QualificationPtyEvidence.SCHEMA_NAME,
                QualificationProtocol.VERSION,
                "TEST_PTY",
                true,
                "2026-07-10T00:00:00Z",
                "2026-07-10T00:00:02Z",
                "talos",
                "workspace",
                QualificationProtocol.ApprovalBehavior.NONE.name(),
                "fixture-complete",
                "",
                new QualificationPtyEvidence.ProcessCleanup(
                        QualificationProcessCleanup.Outcome.ALREADY_EXITED,
                        false,
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                events,
                rawOutput);
    }

    private static Path writeSource(Fixture fixture, String session, byte[] bytes)
            throws IOException {
        Path source = sourcePath(fixture, session);
        Files.createDirectories(source.getParent());
        Files.write(source, bytes);
        return source;
    }

    private static Path sourcePath(Fixture fixture, String session) {
        return fixture.packet().root()
                .resolve("home/.talos/sessions/traces")
                .resolve(session)
                .resolve("000001-trace-1.json");
    }

    private static ObjectNode providerProcessReceiptData(ObjectNode trace) {
        for (JsonNode event : trace.withArray("events")) {
            String type = QualificationJson.requiredText(
                    event.path("type"), "trace event type");
            if ("PROVIDER_PROCESS_LAUNCH_RECEIPT".equals(type)) {
                return QualificationJson.requiredObject(
                        event.path("data"), "provider process receipt data");
            }
        }
        throw new AssertionError("trace fixture lacks a provider process receipt");
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.abort("symbolic-link fixture is unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    private record Fixture(
            QualificationTestPacket packet,
            QualificationScenarioBank.Catalog catalog,
            String scenarioId,
            Path target) {}
}
