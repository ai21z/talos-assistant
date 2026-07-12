package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QualificationApprovalFinalizerTest {

    @TempDir
    Path temp;

    @Test
    void derivesEveryOrderedDenialFromPairedPtyEvents() throws Exception {
        Path capture = writeCapture(List.of(
                event("terminal-outcome", "approval-deny"),
                event("input", "approval-deny"),
                event("terminal-outcome", "approval-deny-repeat-2"),
                event("input", "approval-deny-repeat-2")));

        QualificationApprovalFinalizer.Document document = QualificationApprovalFinalizer.derive(
                capture, QualificationProtocol.ApprovalBehavior.DENY);

        assertEquals(List.of("DENIED", "DENIED"), document.events().stream()
                .map(QualificationApprovalFinalizer.ApprovalEvent::response)
                .toList());
    }

    @Test
    void derivesApproveOnceThenConservativeDenial() throws Exception {
        Path capture = writeCapture(List.of(
                event("terminal-outcome", "approval-once"),
                event("input", "approval-once"),
                event("terminal-outcome", "approval-extra-deny-2"),
                event("input", "approval-extra-deny-2")));

        QualificationApprovalFinalizer.Document document = QualificationApprovalFinalizer.derive(
                capture, QualificationProtocol.ApprovalBehavior.APPROVE_ONCE);

        assertEquals(List.of("APPROVED", "DENIED"), document.events().stream()
                .map(QualificationApprovalFinalizer.ApprovalEvent::response)
                .toList());
    }

    @Test
    void derivesSessionApprovalThenUnexpectedDenial() throws Exception {
        Path capture = writeCapture(List.of(
                event("terminal-outcome", "approval-session"),
                event("input", "approval-session"),
                event("terminal-outcome", "approval-unexpected-deny-2"),
                event("input", "approval-unexpected-deny-2")));

        QualificationApprovalFinalizer.Document document = QualificationApprovalFinalizer.derive(
                capture, QualificationProtocol.ApprovalBehavior.SESSION_ALLOW);

        assertEquals(List.of("APPROVED_REMEMBER", "DENIED"), document.events().stream()
                .map(QualificationApprovalFinalizer.ApprovalEvent::response)
                .toList());
    }

    @Test
    void derivesConservativeDenialForUnexpectedApprovalUnderNone() throws Exception {
        Path capture = writeCapture(List.of(
                event("terminal-outcome", "approval-unexpected-deny-1"),
                event("input", "approval-unexpected-deny-1")));

        QualificationApprovalFinalizer.Document document = QualificationApprovalFinalizer.derive(
                capture, QualificationProtocol.ApprovalBehavior.NONE);

        assertEquals(List.of("DENIED"), document.events().stream()
                .map(QualificationApprovalFinalizer.ApprovalEvent::response)
                .toList());
    }

    @Test
    void refusesAnApprovalWithoutItsMatchingInputRecord() throws Exception {
        Path capture = writeCapture(List.of(
                event("terminal-outcome", "approval-deny"),
                event("input", "approval-deny-repeat-2")));

        assertThrows(IllegalArgumentException.class, () -> QualificationApprovalFinalizer.derive(
                capture, QualificationProtocol.ApprovalBehavior.DENY));
    }

    @Test
    void refusesMoreThanTheDriverApprovalLimit() throws Exception {
        java.util.ArrayList<Map<String, String>> events = new java.util.ArrayList<>();
        for (int ordinal = 1; ordinal <= QualificationPtyOutcomeRace.MAX_APPROVAL_PROMPTS + 1; ordinal++) {
            String label = ordinal == 1 ? "approval-deny" : "approval-deny-repeat-" + ordinal;
            events.add(event("terminal-outcome", label));
            events.add(event("input", label));
        }
        Path capture = writeCapture(events);

        assertThrows(IllegalArgumentException.class, () -> QualificationApprovalFinalizer.derive(
                capture, QualificationProtocol.ApprovalBehavior.DENY));
    }

    private Path writeCapture(List<Map<String, String>> events) throws Exception {
        Path capture = temp.resolve("capture-" + java.util.UUID.randomUUID() + ".json");
        QualificationJson.write(capture, Map.of("events", events));
        return capture;
    }

    private static Map<String, String> event(String type, String data) {
        return Map.of("type", type, "data", data);
    }
}
