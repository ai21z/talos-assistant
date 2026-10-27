package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import dev.talos.core.security.NoFollowPathPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Derives immutable approval evidence from the ordered true-PTY event stream. */
final class QualificationApprovalFinalizer {
    static final String OUTPUT_FILE = "approvals.json";

    private QualificationApprovalFinalizer() {}

    record ApprovalEvent(String response) {
        ApprovalEvent {
            if (!List.of("APPROVED", "APPROVED_REMEMBER", "DENIED").contains(response)) {
                throw new IllegalArgumentException("approval response is invalid");
            }
        }
    }

    record Document(List<ApprovalEvent> events) {
        Document {
            events = List.copyOf(events);
        }
    }

    static Path deriveAndWrite(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        return QualificationPacketPublisher.withLease(
                packetRoot,
                lease -> deriveAndWriteUnderLease(
                        lease, requestedRunRoot, scenarioId, catalog));
    }

    static Path deriveAndWriteUnderLease(
            QualificationPacketPublisher.Lease lease,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        QualificationArtifactPublication.DeclaredRun declared =
                QualificationArtifactPublication.requireWritableDeclaredRun(
                        lease.packetRoot(), requestedRunRoot, scenarioId, catalog);
        Path runRoot = declared.runRoot();
        QualificationProtocol.Scenario scenario = catalog.scenario(scenarioId);
        Path transcript = runRoot.resolve("transcript.raw.json");
        QualificationPacketPublisher.CommittedSet capture =
                QualificationPacketPublisher.requireCommitted(
                        lease.packetRoot(),
                        QualificationPacketPublisher.PublicationKind.PTY_CAPTURE,
                        List.of(transcript));
        Document document = derive(
                QualificationPtyEvidence.parse(
                        capture.bytes(lease.packetRoot(), transcript),
                        catalog.protocolVersion()),
                scenario.approvalBehavior());
        Path output = runRoot.resolve(OUTPUT_FILE);
        lease.publish(
                QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                List.of(new QualificationPacketPublisher.Member(
                        output, QualificationJson.bytes(document))));
        return output;
    }

    static Document derive(
            Path capturePath,
            QualificationProtocol.ApprovalBehavior approvalBehavior) throws IOException {
        return derive(
                QualificationJson.readTree(NoFollowPathPolicy.readAllBytes(capturePath)),
                approvalBehavior);
    }

    static Document derive(
            JsonNode capture,
            QualificationProtocol.ApprovalBehavior approvalBehavior) {
        JsonNode declaredVersion = capture == null ? null : capture.get("schemaVersion");
        if (declaredVersion != null) {
            if (!declaredVersion.isIntegralNumber() || !declaredVersion.canConvertToInt()) {
                throw new IllegalArgumentException(
                        "PTY capture schemaVersion must be an exact integer");
            }
            return derive(
                    QualificationPtyEvidence.parse(capture, declaredVersion.intValue()),
                    approvalBehavior);
        }
        return deriveEvents(
                QualificationPtyEvidence.parseApprovalEvents(capture),
                approvalBehavior);
    }

    static Document derive(
            QualificationPtyEvidence.Capture capture,
            QualificationProtocol.ApprovalBehavior approvalBehavior) {
        if (capture == null) {
            throw new IllegalArgumentException("PTY capture is required");
        }
        return deriveEvents(capture.events(), approvalBehavior);
    }

    private static Document deriveEvents(
            List<QualificationPtyEvidence.Event> events,
            QualificationProtocol.ApprovalBehavior approvalBehavior) {
        if (approvalBehavior == null) {
            throw new IllegalArgumentException("approval behavior is required");
        }

        List<ApprovalEvent> approvals = new ArrayList<>();
        String pendingLabel = null;
        for (QualificationPtyEvidence.Event event : events) {
            String type = event.type();
            String label = event.data();
            boolean approvalLabel = isApprovalDecisionLabel(label);

            if (pendingLabel != null) {
                if (!type.equals("input") || !label.equals(pendingLabel)) {
                    throw new IllegalArgumentException(
                            "approval terminal event is not followed by its matching input: " + pendingLabel);
                }
                int ordinal = approvals.size() + 1;
                QualificationPtyOutcomeRace.ApprovalAction expected = expectedAction(
                        approvalBehavior, ordinal);
                if (!label.equals(expected.eventLabel())) {
                    throw new IllegalArgumentException(
                            "approval event does not match the scenario behavior at ordinal " + ordinal
                                    + ": " + label);
                }
                approvals.add(new ApprovalEvent(responseName(expected.response())));
                pendingLabel = null;
                continue;
            }

            if (type.equals("terminal-outcome") && approvalLabel) {
                if (approvals.size() >= QualificationPtyOutcomeRace.MAX_APPROVAL_PROMPTS) {
                    throw new IllegalArgumentException(
                            "approval event count exceeds the PTY driver limit: "
                                    + QualificationPtyOutcomeRace.MAX_APPROVAL_PROMPTS);
                }
                pendingLabel = label;
            } else if (type.equals("input") && approvalLabel) {
                throw new IllegalArgumentException(
                        "approval input lacks its preceding terminal event: " + label);
            } else if (label.startsWith("approval-")
                    && !label.equals("approval-prompt-limit-exceeded")) {
                throw new IllegalArgumentException("unknown approval event label: " + label);
            }
        }
        if (pendingLabel != null) {
            throw new IllegalArgumentException(
                    "approval terminal event lacks its matching input: " + pendingLabel);
        }
        return new Document(approvals);
    }

    private static QualificationPtyOutcomeRace.ApprovalAction expectedAction(
            QualificationProtocol.ApprovalBehavior behavior,
            int ordinal) {
        if (behavior == QualificationProtocol.ApprovalBehavior.INTERRUPT_BEFORE_APPROVAL) {
            throw new IllegalArgumentException("interrupted scenarios must not contain approval responses");
        }
        return QualificationPtyOutcomeRace.actionFor(
                behavior.name(),
                new QualificationPtyOutcomeRace.Outcome(
                        QualificationPtyOutcomeRace.Kind.APPROVAL, 0, -1, -1),
                ordinal);
    }

    private static String responseName(String response) {
        return switch (response) {
            case "y" -> "APPROVED";
            case "a" -> "APPROVED_REMEMBER";
            case "" -> "DENIED";
            default -> throw new IllegalArgumentException("unsupported PTY approval response");
        };
    }

    private static boolean isApprovalDecisionLabel(String label) {
        return label.equals("approval-deny")
                || label.matches("approval-deny-repeat-[2-9][0-9]*")
                || label.equals("approval-once")
                || label.matches("approval-extra-deny-[2-9][0-9]*")
                || label.equals("approval-session")
                || label.matches("approval-unexpected-deny-[1-9][0-9]*");
    }
}
