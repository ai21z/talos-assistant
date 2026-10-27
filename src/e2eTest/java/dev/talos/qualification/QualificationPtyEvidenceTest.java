package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QualificationPtyEvidenceTest {
    @Test
    void acceptsOnlyTheMaintainedVersionedShapes() throws Exception {
        assertParses(capture(1, false, false));
        assertParses(capture(2, false, false));
        assertParses(capture(2, true, false));
        assertParses(capture(3, true, false));
        assertParses(capture(3, true, true));
        assertParses(capture(4, true, true));
        assertParses(capture(5, true, true));
        assertParses(capture(QualificationProtocol.VERSION, true, true));
    }

    @Test
    void rejectsScalarNullUnknownAndDuplicateV6Fields() throws Exception {
        ObjectNode textualVersion = tree(capture(6, true, true));
        textualVersion.put("schemaVersion", "6");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPtyEvidence.parse(textualVersion, 6));

        ObjectNode fractionalVersion = tree(capture(6, true, true));
        fractionalVersion.put("schemaVersion", 6.9);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPtyEvidence.parse(fractionalVersion, 6));

        ObjectNode nullWorkspace = tree(capture(6, true, true));
        nullWorkspace.putNull("workspace");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPtyEvidence.parse(nullWorkspace, 6));

        ObjectNode unknown = tree(capture(6, true, true));
        unknown.put("unownedClaim", true);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPtyEvidence.parse(unknown, 6));

        byte[] duplicate = """
                {
                  "schemaName":"talos.modelQualification.ptyCapture",
                  "schemaVersion":1,
                  "transport":"TEST_PTY",
                  "tty":true,
                  "tty":true,
                  "captureStartedAt":"2026-07-10T00:00:00Z",
                  "captureEndedAt":"2026-07-10T00:00:02Z",
                  "command":"talos",
                  "workspace":"workspace",
                  "events":[
                    {"timestamp":"2026-07-10T00:00:00Z","type":"input","data":"scenario-prompt"},
                    {"timestamp":"2026-07-10T00:00:01Z","type":"output","data":"answer"},
                    {"timestamp":"2026-07-10T00:00:02Z","type":"input","data":"last-trace"}
                  ],
                  "rawOutput":"answer"
                }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class,
                () -> QualificationPtyEvidence.parse(duplicate, 1));
    }

    @Test
    void rejectsMissingNullAndWrongTypedV6TopLevelFields() throws Exception {
        List<String> requiredFields = List.of(
                "schemaName",
                "schemaVersion",
                "transport",
                "tty",
                "captureStartedAt",
                "captureEndedAt",
                "command",
                "workspace",
                "expectedApprovalBehavior",
                "terminalOutcome",
                "captureFailure",
                "processCleanup",
                "events",
                "rawOutput");
        for (String field : requiredFields) {
            assertRejected(root -> root.remove(field), "missing " + field);
            assertRejected(root -> root.putNull(field), "null " + field);
        }

        List<Consumer<ObjectNode>> wrongTypes = List.of(
                root -> root.put("schemaName", 1),
                root -> root.put("schemaVersion", "6"),
                root -> root.put("transport", false),
                root -> root.put("tty", "true"),
                root -> root.put("captureStartedAt", 1),
                root -> root.putObject("captureEndedAt"),
                root -> root.putArray("command"),
                root -> root.put("workspace", 1),
                root -> root.put("expectedApprovalBehavior", false),
                root -> root.put("terminalOutcome", 1),
                root -> root.put("captureFailure", false),
                root -> root.put("processCleanup", "clean"),
                root -> root.put("events", "events"),
                root -> root.put("rawOutput", true));
        for (int index = 0; index < wrongTypes.size(); index++) {
            assertRejected(wrongTypes.get(index), "wrong top-level type " + index);
        }
    }

    @Test
    void rejectsWrongTypedOrIncompleteProcessCleanupEvidence() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
                root -> cleanup(root).put("outcome", 1),
                root -> cleanup(root).put("outcome", "NOT_AN_OUTCOME"),
                root -> cleanup(root).put("gracefulExitRequested", "false"),
                root -> cleanup(root).put("campaignServerDiscoveryVerified", 1),
                root -> cleanup(root).put("capturedDescendantPids", 1),
                root -> cleanup(root).putArray("campaignServerPids").add("42"),
                root -> cleanup(root).putArray("normalStopPids").add(4.2d),
                root -> cleanup(root).putArray("forcedStopPids").add(0),
                root -> cleanup(root).put("survivingPids", "[]"),
                root -> cleanup(root).put("errors", "failure"),
                root -> cleanup(root).putArray("errors").add(7),
                root -> cleanup(root).remove("outcome"),
                root -> cleanup(root).putNull("outcome"),
                root -> cleanup(root).put("unownedClaim", true));

        for (int index = 0; index < mutations.size(); index++) {
            assertRejected(mutations.get(index), "processCleanup mutation " + index);
        }
    }

    @Test
    void rejectsWrongTypedIncompleteOrContradictoryEventEvidence() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
                root -> root.put("events", "events"),
                root -> root.putArray("events").add("event"),
                root -> firstEvent(root).put("timestamp", 1),
                root -> firstEvent(root).put("timestamp", "not-an-instant"),
                root -> firstEvent(root).put(
                        "timestamp", "2026-07-09T23:59:59Z"),
                root -> firstEvent(root).put(
                        "timestamp", "2026-07-10T00:00:03Z"),
                root -> firstEvent(root).put("type", false),
                root -> firstEvent(root).putObject("data"),
                root -> firstEvent(root).remove("type"),
                root -> firstEvent(root).putNull("data"),
                root -> firstEvent(root).put("unownedClaim", true),
                root -> root.put("rawOutput", "different"));

        for (int index = 0; index < mutations.size(); index++) {
            assertRejected(mutations.get(index), "event mutation " + index);
        }
    }

    @Test
    void preservesEveryMaintainedApprovalAndCleanupEnumValue() throws Exception {
        for (QualificationProtocol.ApprovalBehavior approval
                : QualificationProtocol.ApprovalBehavior.values()) {
            for (QualificationProcessCleanup.Outcome outcome
                    : QualificationProcessCleanup.Outcome.values()) {
                ObjectNode maintained = tree(capture(5, true, true));
                maintained.put("expectedApprovalBehavior", approval.name());
                cleanup(maintained).put("outcome", outcome.name());

                QualificationPtyEvidence.Capture parsed =
                        QualificationPtyEvidence.parse(maintained, 5);

                assertEquals(approval.name(), parsed.expectedApprovalBehavior());
                assertEquals(outcome, parsed.processCleanup().outcome());
                assertEquals(
                        parsed,
                        QualificationPtyEvidence.parse(
                                QualificationJson.bytes(parsed), 5),
                        approval + "/" + outcome);
            }
        }
    }

    @Test
    void canonicalBytesAreIdempotentForEveryMaintainedShape() throws Exception {
        List<QualificationPtyEvidence.Capture> shapes = List.of(
                capture(1, false, false),
                capture(2, false, false),
                capture(2, true, false),
                capture(3, true, false),
                capture(3, true, true),
                capture(4, true, true),
                capture(5, true, true),
                capture(QualificationProtocol.VERSION, true, true));

        for (QualificationPtyEvidence.Capture shape : shapes) {
            byte[] first = QualificationJson.bytes(shape);
            QualificationPtyEvidence.Capture parsed =
                    QualificationPtyEvidence.parse(first, shape.schemaVersion());
            byte[] second = QualificationJson.bytes(parsed);
            byte[] third = QualificationJson.bytes(
                    QualificationPtyEvidence.parse(second, shape.schemaVersion()));
            assertArrayEquals(first, second, "first canonical pass");
            assertArrayEquals(second, third, "second canonical pass");
        }
    }

    @Test
    void rejectsHistoricallyInvalidatedOrPreFinalShapes() throws Exception {
        ObjectNode invalidatedChunks = tree(capture(1, false, false));
        invalidatedChunks.remove(List.of(
                "captureStartedAt", "captureEndedAt", "events"));
        invalidatedChunks.put("startedAt", "2026-07-10T00:00:00Z");
        invalidatedChunks.put("endedAt", "2026-07-10T00:00:02Z");
        invalidatedChunks.putArray("chunks");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPtyEvidence.parse(invalidatedChunks, 1));

        ObjectNode preFinalCleanup = tree(capture(3, true, true));
        ((ObjectNode) preFinalCleanup.get("processCleanup"))
                .remove("campaignServerDiscoveryVerified");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPtyEvidence.parse(preFinalCleanup, 3));
    }

    @Test
    void typedFalseTtyRemainsAvailableForHarnessCausalityButIsNotValidEvidence()
            throws Exception {
        QualificationPtyEvidence.Capture falseTty = capture(6, true, true, false);
        QualificationPtyEvidence.Capture parsed = QualificationPtyEvidence.parse(
                QualificationJson.bytes(falseTty), 6);

        assertFalse(parsed.tty());
        assertFalse(QualificationPtyEvidence.validEvidence(
                parsed, "talos", "TRUE_PTY"));
    }

    private static void assertParses(QualificationPtyEvidence.Capture expected)
            throws Exception {
        QualificationPtyEvidence.Capture actual = QualificationPtyEvidence.parse(
                QualificationJson.bytes(expected), expected.schemaVersion());
        assertEquals(expected, actual);
    }

    private static ObjectNode tree(QualificationPtyEvidence.Capture capture)
            throws Exception {
        return (ObjectNode) QualificationJson.readTree(QualificationJson.bytes(capture));
    }

    private static void assertRejected(
            Consumer<ObjectNode> mutation,
            String label) throws Exception {
        ObjectNode root = tree(capture(6, true, true));
        mutation.accept(root);
        byte[] bytes = QualificationJson.write(root).getBytes(StandardCharsets.UTF_8);
        assertThrows(
                Exception.class,
                () -> QualificationPtyEvidence.parse(bytes, 6),
                label);
    }

    private static ObjectNode cleanup(ObjectNode root) {
        return (ObjectNode) root.get("processCleanup");
    }

    private static ObjectNode firstEvent(ObjectNode root) {
        return (ObjectNode) root.get("events").get(0);
    }

    private static QualificationPtyEvidence.Capture capture(
            int protocol,
            boolean extended,
            boolean cleanup) {
        return capture(protocol, extended, cleanup, true);
    }

    private static QualificationPtyEvidence.Capture capture(
            int protocol,
            boolean extended,
            boolean cleanup,
            boolean tty) {
        List<QualificationPtyEvidence.Event> events = List.of(
                new QualificationPtyEvidence.Event(
                        "2026-07-10T00:00:00Z", "input", "scenario-prompt"),
                new QualificationPtyEvidence.Event(
                        "2026-07-10T00:00:01Z", "output", "answer"),
                new QualificationPtyEvidence.Event(
                        "2026-07-10T00:00:02Z", "input", "last-trace"));
        QualificationPtyEvidence.ProcessCleanup cleanupResult = cleanup
                ? new QualificationPtyEvidence.ProcessCleanup(
                        QualificationProcessCleanup.Outcome.ALREADY_EXITED,
                        false,
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of())
                : null;
        return new QualificationPtyEvidence.Capture(
                QualificationPtyEvidence.SCHEMA_NAME,
                protocol,
                "TEST_PTY",
                tty,
                "2026-07-10T00:00:00Z",
                "2026-07-10T00:00:02Z",
                "talos",
                "workspace",
                extended ? QualificationProtocol.ApprovalBehavior.NONE.name() : null,
                extended ? "fixture-complete" : null,
                extended ? "" : null,
                cleanupResult,
                events,
                "answer");
    }
}
