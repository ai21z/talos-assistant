package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.security.NoFollowPathPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationPreResultEvidenceTest {

    @TempDir
    Path temp;

    @Test
    void protocolV6PtyScalarTypesAreNeverCoercedIntoEvidence() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        List<PtyScalarCase> cases = List.of(
                new PtyScalarCase("textual-schema-version", json ->
                        json.put("schemaVersion", Integer.toString(catalog.protocolVersion()))),
                new PtyScalarCase("fractional-schema-version", json ->
                        json.put("schemaVersion", catalog.protocolVersion() + 0.9)),
                new PtyScalarCase("textual-tty", json -> json.put("tty", "true")),
                new PtyScalarCase("numeric-tty", json -> json.put("tty", 1)),
                new PtyScalarCase("numeric-workspace", json -> json.put("workspace", 17)),
                new PtyScalarCase("numeric-raw-output", json -> {
                    ArrayNode events = (ArrayNode) json.path("events");
                    ((ObjectNode) events.get(1)).put("data", 17);
                    json.put("rawOutput", 17);
                }),
                new PtyScalarCase("numeric-event-timestamp", json ->
                        ((ObjectNode) json.path("events").get(0))
                                .put("timestamp", 17)),
                new PtyScalarCase("numeric-ignored-event-type", json ->
                        ((ArrayNode) json.path("events")).addObject()
                                .put("timestamp", "2026-07-10T00:00:02Z")
                                .put("type", 17)
                                .put("data", "ignored-diagnostic")),
                new PtyScalarCase("numeric-ignored-event-data", json ->
                        ((ArrayNode) json.path("events")).addObject()
                                .put("timestamp", "2026-07-10T00:00:02Z")
                                .put("type", "diagnostic")
                                .put("data", 17)));
        List<String> coercionsAccepted = new ArrayList<>();

        for (PtyScalarCase testCase : cases) {
            QualificationTestPacket packet = QualificationTestPacket.create(
                    temp.resolve("pty-scalar-" + testCase.id()), catalog, scenario);
            mutateJson(packet.runRoot().resolve("transcript.raw.json"),
                    testCase.mutation());

            QualificationPreResultEvidence.Snapshot snapshot =
                    QualificationPreResultEvidence.capture(
                            packet.root(), packet.runRoot(), scenario.id(), catalog);
            QualificationCausalFinalizer.Document document =
                    QualificationCausalFinalizer.deriveFromSnapshot(snapshot);

            if (!snapshot.findings().contains("PTY_CAPTURE_INVALID")) {
                coercionsAccepted.add(testCase.id());
            }
            assertFalse(snapshot.complete(), testCase.id());
            assertFalse(snapshot.trustGates().mandatoryEvidencePresent(), testCase.id());
            assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                    document.causalOwner(), testCase.id());
            assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                    document.failureClass(), testCase.id());
            assertFalse(document.comparativeScoreEligible(), testCase.id());
        }

        assertTrue(coercionsAccepted.isEmpty(),
                () -> "PTY scalar coercions accepted as evidence: " + coercionsAccepted);
    }

    @Test
    void protocolV6WithoutAnActiveProcessLaunchReceiptStopsAsUnknownIncompleteEvidence()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("missing-active-process-receipt"), catalog, scenario);

        QualificationPreResultEvidence.Snapshot snapshot =
                QualificationPreResultEvidence.capture(
                        packet.root(), packet.runRoot(), scenario.id(), catalog);
        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.deriveFromSnapshot(snapshot);

        assertTrue(snapshot.findings().contains(
                "ACTIVE_PROCESS_LAUNCH_RECEIPT_UNAVAILABLE"), snapshot.findings()::toString);
        assertFalse(snapshot.complete());
        assertTrue(snapshot.trustGates().approvalIntegrity());
        assertTrue(snapshot.trustGates().mutationScope());
        assertTrue(snapshot.trustGates().completionTruth());
        assertTrue(snapshot.trustGates().protectedContentSafety());
        assertTrue(snapshot.trustGates().readEvidenceBinding());
        assertTrue(snapshot.trustGates().terminalStateDisclosure());
        assertFalse(snapshot.trustGates().modelIdentityAgreement());
        assertFalse(snapshot.trustGates().mandatoryEvidencePresent());
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertFalse(document.comparativeScoreEligible());
        assertFalse(document.evidenceComplete());
        assertTrue(document.causalLedger().stream()
                .noneMatch(QualificationRecords.CausalLedgerEntry::firstCause));
    }

    @Test
    void invalidPreResultMatrixUsesOnlyAnExplicitTypedHarnessCause() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        List<Case> cases = List.of(
                new Case("request-schema", (packet) -> mutateJson(
                        packet.runRoot().resolve("request.json"), json ->
                                json.put("schemaName", "forged.request"))),
                new Case("request-prompt", (packet) -> mutateJson(
                        packet.runRoot().resolve("request.json"), json ->
                                json.put("prompt", "different prompt"))),
                new Case("trace-authority", (packet) -> mutateJson(
                        packet.runRoot().resolve("trace.json"), json -> {
                            ((ObjectNode) json.path("taskContract")).put("mutationAllowed", true)
                                    .put("type", "WORKSPACE_MUTATION");
                            ((ObjectNode) json.path("promptAudit")).put("mutationAllowed", true)
                                    .put("taskType", "WORKSPACE_MUTATION")
                                    .put("actionObligation", "MUTATING_TOOL_REQUIRED");
                        })),
                new Case("wrong-frozen-baseline", (packet) -> {
                    Files.writeString(packet.runRoot().resolve("before-files/README.md"),
                            "self-consistent but not frozen\n", StandardCharsets.UTF_8);
                    QualificationJson.write(packet.runRoot().resolve("before-hashes.json"),
                            QualificationFixtureMaterializer.snapshot(
                                    packet.runRoot().resolve("before-files")).sha256());
                }),
                new Case("declared-before-hash", (packet) -> mutateJson(
                        packet.runRoot().resolve("before-hashes.json"), json ->
                                json.put("README.md", "0".repeat(64)))),
                new Case("declared-before-hash-scalar", (packet) -> mutateJson(
                        packet.runRoot().resolve("before-hashes.json"), json ->
                                json.put("README.md", 17))),
                new Case("after-final-divergence", (packet) -> {
                    Files.writeString(packet.runRoot().resolve("after-files/README.md"),
                            "captured after drift\n", StandardCharsets.UTF_8);
                    QualificationJson.write(packet.runRoot().resolve("after-hashes.json"),
                            QualificationFixtureMaterializer.snapshot(
                                    packet.runRoot().resolve("after-files")).sha256());
                }),
                new Case("approval-pty", (packet) -> QualificationJson.write(
                        packet.runRoot().resolve("approvals.json"),
                        java.util.Map.of("events", List.of(
                                java.util.Map.of("response", "DENIED"))))),
                new Case("invalid-pty", (packet) -> mutateJson(
                        packet.runRoot().resolve("transcript.raw.json"), json ->
                                json.put("tty", false))),
                new Case("timing-schema", (packet) -> mutateJson(
                        packet.runRoot().resolve("timings.json"), json ->
                                json.put("schemaName", "forged.timing"))),
                new Case("resource-schema", (packet) -> mutateJson(
                        packet.runRoot().resolve("resources.json"), json ->
                                json.put("schemaVersion", 99))),
                new Case("provider-not-applicable", (packet) -> {
                    Files.writeString(packet.runRoot().resolve("prompt-debug.txt"),
                            QualificationProviderEvidence.promptNotApplicableText(),
                            StandardCharsets.UTF_8);
                    Files.writeString(packet.runRoot().resolve("llama.log"),
                            QualificationProviderEvidence.modelRuntimeNotApplicableText(),
                            StandardCharsets.UTF_8);
                }),
                new Case("mechanical-requirements", (packet) -> {
                    Path output = QualificationRequirementFinalizer.evaluateAndWrite(
                            packet.root(), packet.runRoot(), scenario.id(), catalog);
                    ObjectNode json = (ObjectNode) QualificationJson.readTree(output);
                    ArrayNode requirements = (ArrayNode) json.path("requirements");
                    ((ObjectNode) requirements.get(0)).put("passed", false);
                    QualificationJson.write(output, json);
                }));

        for (Case testCase : cases) {
            QualificationTestPacket packet = QualificationTestPacket.create(
                    temp.resolve(testCase.id()), catalog, scenario);
            testCase.mutation().apply(packet);

            QualificationPreResultEvidence.Snapshot snapshot =
                    QualificationPreResultEvidence.capture(
                            packet.root(), packet.runRoot(), scenario.id(), catalog);
            QualificationCausalFinalizer.Document document =
                    QualificationCausalFinalizer.deriveFromSnapshot(snapshot);

            assertFalse(snapshot.complete(), testCase.id() + ": " + snapshot.findings());
            QualificationRecords.CausalOwner expectedOwner = "invalid-pty".equals(testCase.id())
                    ? QualificationRecords.CausalOwner.HARNESS
                    : QualificationRecords.CausalOwner.UNKNOWN;
            assertEquals(expectedOwner,
                    document.causalOwner(), testCase.id());
            assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                    document.failureClass(), testCase.id());
            assertFalse(document.comparativeScoreEligible(), testCase.id());
            assertFalse(document.evidenceComplete(), testCase.id());
            assertFalse(document.trustGates().mandatoryEvidencePresent(), testCase.id());
            long firstCauseCount = document.causalLedger().stream()
                    .filter(QualificationRecords.CausalLedgerEntry::firstCause)
                    .count();
            assertEquals(expectedOwner == QualificationRecords.CausalOwner.HARNESS ? 1L : 0L,
                    firstCauseCount, testCase.id());
        }
    }

    @Test
    void contradictoryCampaignBankRefusesThePreResultNamespace() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("campaign-bank"), catalog, scenario);
        mutateJson(packet.root().resolve("campaign.json"), json ->
                json.put("scenarioBankSha256", "c".repeat(64)));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> QualificationPreResultEvidence.capture(
                        packet.root(), packet.runRoot(), scenario.id(), catalog));
    }

    @Test
    void zeroByteOperatorAnswerDoesNotCreateASeparateMissingTextFinding() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.full().scenarios().stream()
                .filter(candidate -> candidate.id().equals("F18-recovery-user-interruption"))
                .findFirst().orElseThrow();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog, scenario);
        Files.write(packet.runRoot().resolve("answer.txt"), new byte[0],
                StandardOpenOption.TRUNCATE_EXISTING);

        QualificationPreResultEvidence.Snapshot snapshot = QualificationPreResultEvidence.capture(
                packet.root(), packet.runRoot(), scenario.id(), catalog);
        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.deriveFromSnapshot(snapshot);

        assertEquals(List.of("ACTIVE_PROCESS_LAUNCH_RECEIPT_UNAVAILABLE"),
                snapshot.findings());
        assertFalse(snapshot.complete());
        assertEquals(QualificationRecords.AnswerOrigin.NONE, document.answerOrigin());
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertFalse(document.evidenceComplete());
        assertFalse(document.comparativeScoreEligible());
    }

    @Test
    void aPowerShellCanaryAssertionCannotOverrideTheCapturedByteScan()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog, scenario);
        Files.writeString(packet.runRoot().resolve("transcript.txt"),
                "\nt275-token-should-not-appear\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        QualificationPreResultEvidence.Snapshot snapshot =
                QualificationPreResultEvidence.capture(
                        packet.root(), packet.runRoot(), scenario.id(), catalog);
        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.deriveFromSnapshot(snapshot);

        assertEquals(List.of("ACTIVE_PROCESS_LAUNCH_RECEIPT_UNAVAILABLE"),
                snapshot.findings());
        assertFalse(snapshot.complete());
        assertFalse(snapshot.canaryPassed());
        assertFalse(snapshot.trustGates().protectedContentSafety());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                document.causalOwner());
        assertEquals(QualificationRecords.CausalReason.MANDATORY_EVIDENCE_INCOMPLETE,
                document.reasonCode());
        assertTrue(document.causalLedger().stream()
                .noneMatch(QualificationRecords.CausalLedgerEntry::firstCause));
    }

    @Test
    void newAndChangedWorkspaceOutputsRemainCanaryScanTargets() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();

        QualificationTestPacket added = QualificationTestPacket.create(
                temp.resolve("added-output"), catalog, scenario);
        for (String directory : List.of("after-files", "final-files")) {
            Files.writeString(
                    added.runRoot().resolve(directory).resolve("leak.txt"),
                    "t275-token-should-not-appear\n",
                    StandardCharsets.UTF_8);
        }
        QualificationPreResultEvidence.Snapshot addedSnapshot =
                QualificationPreResultEvidence.capture(
                        added.root(), added.runRoot(), scenario.id(), catalog);

        QualificationTestPacket changed = QualificationTestPacket.create(
                temp.resolve("changed-output"), catalog, scenario);
        for (String directory : List.of("after-files", "final-files")) {
            Files.writeString(
                    changed.runRoot().resolve(directory).resolve("README.md"),
                    "t275-token-should-not-appear\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        }
        QualificationPreResultEvidence.Snapshot changedSnapshot =
                QualificationPreResultEvidence.capture(
                        changed.root(), changed.runRoot(), scenario.id(), catalog);

        assertFalse(addedSnapshot.canaryPassed(), addedSnapshot.findings().toString());
        assertFalse(changedSnapshot.canaryPassed(), changedSnapshot.findings().toString());
    }

    @Test
    void linkedRunArtifactAbortsBeforeSnapshotOrAuthorityPublication() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("linked-run-artifact"), catalog, scenario);
        List<String> authorities = List.of(
                QualificationCausalFinalizer.OUTPUT_FILE,
                QualificationRunFinalizer.CANARY_FILE,
                QualificationRunFinalizer.RESULT_FILE);
        java.util.Map<String, String> authorityHashesBefore = new java.util.LinkedHashMap<>();
        for (String authority : authorities) {
            Path authorityPath = packet.runRoot().resolve(authority);
            assertTrue(Files.exists(authorityPath),
                    "the protocol-v6 fixture must begin with " + authority);
            authorityHashesBefore.put(
                    authority,
                    QualificationScenarioBank.sha256(Files.readAllBytes(authorityPath)));
        }
        Path external = Files.writeString(temp.resolve("external-pre-result.txt"),
                "T1170_EXTERNAL_PRE_RESULT_CANARY", StandardCharsets.UTF_8);
        createSymbolicLinkOrAbort(
                packet.runRoot().resolve("final-files/linked.txt"), external);

        NoFollowPathPolicy.Violation failure = assertThrows(
                NoFollowPathPolicy.Violation.class,
                () -> QualificationPreResultEvidence.capture(
                        packet.root(), packet.runRoot(), scenario.id(), catalog),
                "a linked run artifact must abort before a snapshot is constructed");
        assertEquals(NoFollowPathPolicy.Reason.LINKED_COMPONENT, failure.reason());
        assertFalse(failure.getMessage().contains(external.toString()));
        assertFalse(failure.getMessage().contains(
                "T1170_EXTERNAL_PRE_RESULT_CANARY"));
        for (String authority : authorities) {
            Path authorityPath = packet.runRoot().resolve(authority);
            assertEquals(
                    authorityHashesBefore.get(authority),
                    QualificationScenarioBank.sha256(Files.readAllBytes(authorityPath)),
                    "containment failure must not replace " + authority);
        }
        try (var paths = Files.walk(packet.runRoot())) {
            for (Path path : paths
                    .filter(candidate -> Files.isRegularFile(
                            candidate,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .toList()) {
                assertFalse(
                        new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                                .contains("T1170_EXTERNAL_PRE_RESULT_CANARY"),
                        "external canary entered " + packet.runRoot().relativize(path));
            }
        }
    }

    @Test
    void unchangedFrozenPrivateSourceRemainsAnExplicitCanaryExemption() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.full().scenarios().stream()
                .filter(candidate -> candidate.id().equals("F13-privacy-protected-read-denial"))
                .findFirst()
                .orElseThrow();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("unchanged-private-source"), catalog, scenario);

        QualificationPreResultEvidence.Snapshot snapshot =
                QualificationPreResultEvidence.capture(
                        packet.root(), packet.runRoot(), scenario.id(), catalog);

        assertTrue(snapshot.canaryCoverageComplete(), snapshot.findings().toString());
        assertTrue(snapshot.canaryPassed(), snapshot.findings().toString());
    }

    @Test
    void binaryLargeAndMalformedCapturedArtifactsRemainCanaryScanTargets() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        byte[] canary = "t275-token-should-not-appear".getBytes(StandardCharsets.UTF_8);
        List<CanaryByteCase> cases = List.of(
                new CanaryByteCase("oversized-unknown", packet -> {
                    byte[] bytes = new byte[2_000_001];
                    java.util.Arrays.fill(bytes, (byte) 0xff);
                    System.arraycopy(canary, 0, bytes, bytes.length - canary.length, canary.length);
                    Files.write(packet.runRoot().resolve("oversized.unknown"), bytes);
                }),
                new CanaryByteCase("malformed-class", packet -> {
                    byte[] bytes = new byte[canary.length + 2];
                    bytes[0] = (byte) 0xc3;
                    bytes[1] = 0x28;
                    System.arraycopy(canary, 0, bytes, 2, canary.length);
                    Files.write(packet.runRoot().resolve("malformed.class"), bytes);
                }));

        for (CanaryByteCase testCase : cases) {
            QualificationTestPacket packet = QualificationTestPacket.create(
                    temp.resolve(testCase.id()), catalog, scenario);
            testCase.mutation().apply(packet);

            QualificationPreResultEvidence.Snapshot snapshot =
                    QualificationPreResultEvidence.capture(
                            packet.root(), packet.runRoot(), scenario.id(), catalog);
            QualificationCausalFinalizer.Document document =
                    QualificationCausalFinalizer.deriveFromSnapshot(snapshot);

            assertEquals(List.of("ACTIVE_PROCESS_LAUNCH_RECEIPT_UNAVAILABLE"),
                    snapshot.findings(), testCase.id());
            assertFalse(snapshot.complete(), testCase.id());
            assertFalse(snapshot.canaryPassed(), testCase.id());
            assertTrue(snapshot.canaryCoverageComplete(), testCase.id());
            assertEquals(1, snapshot.canaryFindingCount(), testCase.id());
            assertFalse(snapshot.trustGates().protectedContentSafety(), testCase.id());
            assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                    document.causalOwner(), testCase.id());
            assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                    document.failureClass(), testCase.id());
            assertFalse(document.comparativeScoreEligible(), testCase.id());
            assertTrue(document.causalLedger().stream()
                    .noneMatch(QualificationRecords.CausalLedgerEntry::firstCause), testCase.id());
        }
    }

    @Test
    void derivationUsesTheCapturedSnapshotRatherThanRereadingLiveFiles() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog, scenario);
        QualificationPreResultEvidence.Snapshot captured = QualificationPreResultEvidence.capture(
                packet.root(), packet.runRoot(), scenario.id(), catalog);

        Files.writeString(packet.runRoot().resolve("before-files/README.md"),
                "changed after snapshot\n", StandardCharsets.UTF_8);

        QualificationCausalFinalizer.Document fromSnapshot =
                QualificationCausalFinalizer.deriveFromSnapshot(captured);
        QualificationPreResultEvidence.Snapshot recaptured =
                QualificationPreResultEvidence.capture(
                        packet.root(), packet.runRoot(), scenario.id(), catalog);
        QualificationCausalFinalizer.Document fromLive =
                QualificationCausalFinalizer.deriveFromSnapshot(recaptured);

        assertEquals(List.of("ACTIVE_PROCESS_LAUNCH_RECEIPT_UNAVAILABLE"),
                captured.findings());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                fromSnapshot.failureClass());
        assertFalse(fromSnapshot.evidenceComplete());
        assertEquals(captured.trustGates(), fromSnapshot.trustGates());
        assertEquals(captured.requirements(), fromSnapshot.requirements());
        assertEquals(captured.canaryPassed(), fromSnapshot.canaryPassed());
        assertTrue(recaptured.findings().contains("BEFORE_FIXTURE_MISMATCH"),
                recaptured.findings()::toString);
        assertTrue(recaptured.findings().contains("BEFORE_HASHES_MISMATCH"),
                recaptured.findings()::toString);
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                fromLive.failureClass());
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, fromLive.causalOwner());
    }

    @Test
    void fullValidatorReportsTheSharedPreResultFinding() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        mutateJson(packet.runRoot().resolve("request.json"), json ->
                json.put("schemaName", "forged.request"));

        QualificationEvidenceValidator.Validation validation =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(validation.valid());
        assertTrue(validation.findings().stream().anyMatch(finding ->
                        finding.contains("publication verification failed")
                                && finding.contains("published qualification result differs")),
                () -> String.join("\n", validation.findings()));
        assertTrue(validation.findings().stream().anyMatch(finding ->
                        finding.contains("Schema mismatch for request")),
                () -> String.join("\n", validation.findings()));
    }

    private static void mutateJson(Path path, JsonMutation mutation) throws Exception {
        ObjectNode json = (ObjectNode) QualificationJson.readTree(path);
        mutation.apply(json);
        QualificationJson.write(path, json);
    }

    private record Case(String id, PacketMutation mutation) {}

    private record CanaryByteCase(String id, PacketMutation mutation) {}

    private record PtyScalarCase(String id, JsonMutation mutation) {}

    private static void createSymbolicLinkOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            Assumptions.abort("symbolic-link fixture is unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface PacketMutation {
        void apply(QualificationTestPacket packet) throws Exception;
    }

    @FunctionalInterface
    private interface JsonMutation {
        void apply(ObjectNode json) throws Exception;
    }
}
