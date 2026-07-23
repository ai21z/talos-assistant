package dev.talos.qualification;

import dev.talos.runtime.policy.ArtifactCanaryScanner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Sole protocol-v6 authority and publisher for one qualification run result. */
final class QualificationRunFinalizer {
    static final String CANARY_FILE = "canary.json";
    static final String RESULT_FILE = "result.json";
    static final String CANARY_SCHEMA = "talos.modelQualification.canary";
    static final int CANARY_SCHEMA_VERSION = 2;
    static final String CANARY_AUTHORITY = "JAVA_CAPTURED_PRE_RESULT_SCAN";

    private QualificationRunFinalizer() {}

    record CanaryDocument(
            String schemaName,
            int schemaVersion,
            QualificationCausalFinalizer.EvidenceIdentity evidenceIdentity,
            String scanPolicyName,
            int scanPolicyVersion,
            String scanScope,
            String patternEncoding,
            int capturedArtifactCount,
            int allowlistedArtifactCount,
            int scannedArtifactCount,
            List<ArtifactCanaryScanner.CapturedFinding> findings,
            List<ArtifactCanaryScanner.CoverageGap> coverageGaps,
            int findingCount,
            int coverageGapCount,
            boolean passed,
            boolean coverageComplete,
            String authority) {
        CanaryDocument {
            if (!CANARY_SCHEMA.equals(schemaName)
                    || schemaVersion != CANARY_SCHEMA_VERSION) {
                throw new IllegalArgumentException("invalid qualification canary schema");
            }
            evidenceIdentity = Objects.requireNonNull(
                    evidenceIdentity, "canary evidence identity");
            ArtifactCanaryScanner.CapturedRuntimeScan scan =
                    new ArtifactCanaryScanner.CapturedRuntimeScan(
                            scanPolicyName,
                            scanPolicyVersion,
                            scanScope,
                            patternEncoding,
                            capturedArtifactCount,
                            allowlistedArtifactCount,
                            scannedArtifactCount,
                            findings,
                            coverageGaps);
            findings = scan.findings();
            coverageGaps = scan.coverageGaps();
            if (findingCount != findings.size()
                    || coverageGapCount != coverageGaps.size()) {
                throw new IllegalArgumentException(
                        "canary finding and coverage-gap counts must equal their records");
            }
            if (coverageComplete != scan.coverageComplete()
                    || passed != scan.passed()) {
                throw new IllegalArgumentException(
                        "canary verdict must equal the captured scan records");
            }
            if (!CANARY_AUTHORITY.equals(authority)) {
                throw new IllegalArgumentException("invalid canary authority");
            }
        }
    }

    record Publication(
            QualificationCausalFinalizer.Document causal,
            CanaryDocument canary,
            QualificationRecords.RunResult result) {
        Publication {
            causal = Objects.requireNonNull(causal, "causal finalization");
            canary = Objects.requireNonNull(canary, "canary finalization");
            result = Objects.requireNonNull(result, "run result");
            if (!causal.evidenceIdentity().equals(canary.evidenceIdentity())) {
                throw new IllegalArgumentException("causal and canary identities disagree");
            }
            if (canary.passed() != causal.canaryPassed()
                    || causal.canaryPassed() != result.trustGates().protectedContentSafety()) {
                throw new IllegalArgumentException("canary authority fields disagree");
            }
            if (!resultMatchesCausal(result, causal)) {
                throw new IllegalArgumentException("result and causal finalization disagree");
            }
        }
    }

    interface PublicationObserver {
        PublicationObserver NONE = new PublicationObserver() {};

        default void afterInitialCapture(QualificationPreResultEvidence.Snapshot snapshot)
                throws IOException {}

        default void beforeResultPublication(QualificationPreResultEvidence.Snapshot snapshot)
                throws IOException {}
    }

    static Publication derive(QualificationPreResultEvidence.Snapshot evidence) {
        Objects.requireNonNull(evidence, "pre-result evidence");
        QualificationRecords.CampaignManifest campaign = Objects.requireNonNull(
                evidence.campaign(), "campaign identity is unavailable");
        QualificationRecords.RequestRecord request = Objects.requireNonNull(
                evidence.request(), "request identity is unavailable");
        QualificationCausalFinalizer.Document causal =
                QualificationCausalFinalizer.deriveFromSnapshot(evidence);
        QualificationCausalFinalizer.EvidenceIdentity identity = causal.evidenceIdentity();
        if (identity.artifactSha256().isEmpty()
                || "UNBOUND".equals(identity.traceId())) {
            throw new IllegalArgumentException(
                    "causal publication requires bound artifact and trace identity");
        }
        int repetition = repetitionNumber(campaign, request.repetitionId());
        QualificationTraceEvidence.Facts trace = evidence.trace();
        QualificationRecords.TimingEvidence timings = evidence.timings();
        QualificationRecords.ResourceEvidence resources = evidence.resources();
        QualificationRecords.TrustGates trust = causal.trustGates();
        ArtifactCanaryScanner.CapturedRuntimeScan scan = evidence.canaryScan();
        CanaryDocument canary = new CanaryDocument(
                CANARY_SCHEMA,
                CANARY_SCHEMA_VERSION,
                identity,
                scan.policyName(),
                scan.policyVersion(),
                scan.scanScope(),
                scan.patternEncoding(),
                scan.capturedArtifactCount(),
                scan.allowlistedArtifactCount(),
                scan.scannedArtifactCount(),
                scan.findings(),
                scan.coverageGaps(),
                scan.findings().size(),
                scan.coverageGaps().size(),
                causal.canaryPassed(),
                scan.coverageComplete(),
                CANARY_AUTHORITY);

        QualificationRecords.RunResult result = new QualificationRecords.RunResult(
                QualificationRecords.RUN_SCHEMA,
                QualificationRecords.RUN_SCHEMA_VERSION,
                campaign.campaignId(),
                campaign.scenarioBankSha256(),
                campaign.candidateAlias(),
                campaign.machineId(),
                campaign.engineLaneId(),
                campaign.configId(),
                evidence.scenario().id(),
                request.repetitionId(),
                repetition,
                evidence.complete(),
                evidence.complete() ? "" : String.join(";", evidence.findings()),
                causal.failureClass(),
                causal.answerOrigin(),
                causal.responseOwnerContract(),
                causal.causalOwner(),
                causal.reasonCode(),
                causal.comparativeScoreEligible(),
                causal.causalLedger(),
                trace == null
                        ? evidence.incompleteTerminalTrace()
                                ? "EVIDENCE_INCOMPLETE"
                                : "EVIDENCE_UNAVAILABLE"
                        : trace.terminalState(),
                trust,
                timings != null && timings.timedOut(),
                timings != null && timings.outputCapHit(),
                timings == null ? "" : nullToEmpty(timings.backendErrorCode()),
                timings == null ? 0 : timings.malformedToolCalls(),
                timings == null ? 0 : timings.toolEligibleGenerations(),
                trace == null ? 0 : trace.unnecessaryExecutedCalls(),
                timings == null ? 0 : timings.repairAttempts(),
                timings == null ? 0 : timings.outputTokens(),
                timings == null ? 0 : timings.promptTokens(),
                timings == null ? 0 : timings.firstOutputMillis(),
                timings == null ? 0 : timings.watchdogRetries(),
                timings == null ? 0 : timings.totalTurnWallTimeMillis(),
                timings == null ? 0 : timings.promptEvalMillis(),
                timings == null ? 0 : timings.generationMillis(),
                timings == null ? 0.0 : timings.promptTokensPerSecond(),
                timings == null ? 0.0 : timings.generationTokensPerSecond(),
                resources == null ? 0 : resources.processWorkingSetBytes(),
                resources == null ? 0 : resources.systemRamBytes(),
                resources == null ? 0 : resources.gpuVramUsedBytes(),
                resources == null ? 0 : resources.gpuVramBytes(),
                causal.evidenceComplete(),
                trace == null ? List.of() : trace.decisions(),
                trace == null ? List.of() : trace.toolCallResults(),
                causal.requirements());
        return new Publication(causal, canary, result);
    }

    static Path finalizeRun(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        return finalizeRun(
                packetRoot, requestedRunRoot, scenarioId, catalog, PublicationObserver.NONE);
    }

    static Path finalizeRun(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog,
            PublicationObserver observer) throws IOException {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(observer, "publication observer");
        if (catalog.protocolVersion() != QualificationProtocol.VERSION) {
            throw new IllegalArgumentException(
                    "run finalization writes exactly protocol v"
                            + QualificationProtocol.VERSION);
        }
        return QualificationPacketPublisher.withLease(
                packetRoot,
                lease -> finalizeUnderLease(
                        lease, requestedRunRoot, scenarioId, catalog, observer));
    }

    static Path finalizeUnderLease(
            QualificationPacketPublisher.Lease lease,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog,
            PublicationObserver observer) throws IOException {
        QualificationArtifactPublication.DeclaredRun declaredRun =
                QualificationArtifactPublication.requireWritableDeclaredRun(
                        lease.packetRoot(), requestedRunRoot, scenarioId, catalog);
        Path runRoot = declaredRun.runRoot();
        Path resultPath = runRoot.resolve(RESULT_FILE);

        QualificationPreResultEvidence.Snapshot initial =
                QualificationPreResultEvidence.capture(
                        lease.packetRoot(), runRoot, scenarioId, catalog);
        observer.afterInitialCapture(initial);
        QualificationPreResultEvidence.Snapshot stable =
                QualificationPreResultEvidence.capture(
                        lease.packetRoot(), runRoot, scenarioId, catalog);
        requireSameCapturedInputs(initial, stable);
        Publication publication = derive(stable);

        Path causalPath = runRoot.resolve(QualificationCausalFinalizer.OUTPUT_FILE);
        Path canaryPath = runRoot.resolve(CANARY_FILE);
        observer.beforeResultPublication(stable);
        QualificationPreResultEvidence.Snapshot beforeResult =
                QualificationPreResultEvidence.capture(
                        lease.packetRoot(), runRoot, scenarioId, catalog);
        requireSameCapturedInputs(stable, beforeResult);
        Publication confirmed = derive(beforeResult);
        if (!publication.equals(confirmed)) {
            throw new IllegalStateException(
                    "qualification evidence changed before result publication");
        }
        lease.publish(
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                List.of(
                        new QualificationPacketPublisher.Member(
                                causalPath, QualificationJson.bytes(publication.causal())),
                        new QualificationPacketPublisher.Member(
                                canaryPath, QualificationJson.bytes(publication.canary())),
                        new QualificationPacketPublisher.Member(
                                resultPath, QualificationJson.bytes(publication.result()))));
        return resultPath;
    }

    static Publication verifyPublished(
            Path packetRoot,
            Path resultPath,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Objects.requireNonNull(catalog, "catalog");
        if (catalog.protocolVersion() != QualificationProtocol.VERSION) {
            throw new IllegalArgumentException(
                    "published schema-v2 verification requires protocol v"
                            + QualificationProtocol.VERSION);
        }
        Path normalizedResult = resultPath.toAbsolutePath().normalize();
        if (!RESULT_FILE.equals(normalizedResult.getFileName().toString())) {
            throw new IllegalArgumentException("expected a result.json path");
        }
        Path runRoot = normalizedResult.getParent();
        Path causalPath = runRoot.resolve(QualificationCausalFinalizer.OUTPUT_FILE);
        Path canaryPath = runRoot.resolve(CANARY_FILE);
        QualificationPacketPublisher.CommittedSet committed =
                QualificationPacketPublisher.requireCommitted(
                        packetRoot,
                        QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                        List.of(causalPath, canaryPath, normalizedResult));
        return verifyCommittedPublication(
                packetRoot, normalizedResult, catalog, committed);
    }

    static Publication verifyPublished(
            QualificationPacketPublisher.Lease lease,
            Path resultPath,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Objects.requireNonNull(lease, "publication lease");
        Objects.requireNonNull(catalog, "catalog");
        if (catalog.protocolVersion() != QualificationProtocol.VERSION) {
            throw new IllegalArgumentException(
                    "published schema-v2 verification requires protocol v"
                            + QualificationProtocol.VERSION);
        }
        Path normalizedResult = resultPath.toAbsolutePath().normalize();
        if (!RESULT_FILE.equals(normalizedResult.getFileName().toString())) {
            throw new IllegalArgumentException("expected a result.json path");
        }
        Path runRoot = normalizedResult.getParent();
        Path causalPath = runRoot.resolve(QualificationCausalFinalizer.OUTPUT_FILE);
        Path canaryPath = runRoot.resolve(CANARY_FILE);
        QualificationPacketPublisher.CommittedSet committed = lease.requireCommittedSet(
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                List.of(causalPath, canaryPath, normalizedResult));
        return verifyCommittedPublication(
                lease.packetRoot(), normalizedResult, catalog, committed);
    }

    private static Publication verifyCommittedPublication(
            Path packetRoot,
            Path normalizedResult,
            QualificationScenarioBank.Catalog catalog,
            QualificationPacketPublisher.CommittedSet committed) throws IOException {
        Path runRoot = normalizedResult.getParent();
        Path causalPath = runRoot.resolve(QualificationCausalFinalizer.OUTPUT_FILE);
        Path canaryPath = runRoot.resolve(CANARY_FILE);
        byte[] resultBytes = committed.bytes(packetRoot, normalizedResult);
        byte[] causalBytes = committed.bytes(packetRoot, causalPath);
        byte[] canaryBytes = committed.bytes(packetRoot, canaryPath);
        QualificationRecords.RunResult result = QualificationJson.read(
                resultBytes, QualificationRecords.RunResult.class);
        QualificationJson.requireRunSchemaForProtocol(
                catalog.protocolVersion(), result.schemaVersion());
        QualificationCausalFinalizer.Document causal = QualificationJson.read(
                causalBytes, QualificationCausalFinalizer.Document.class);
        CanaryDocument canary = QualificationJson.read(
                canaryBytes, CanaryDocument.class);
        QualificationPreResultEvidence.Snapshot evidence =
                QualificationPreResultEvidence.capture(
                        packetRoot, runRoot, result.scenarioId(), catalog);
        Publication expected = derive(evidence);
        Publication declared = new Publication(causal, canary, result);
        if (!declared.equals(expected)) {
            throw new IllegalStateException(
                    "published qualification result differs from Java mechanical evidence");
        }
        requireCanonicalAuthority(
                causalPath, causalBytes, QualificationJson.bytes(expected.causal()));
        requireCanonicalAuthority(
                canaryPath, canaryBytes, QualificationJson.bytes(expected.canary()));
        requireCanonicalAuthority(
                normalizedResult, resultBytes, QualificationJson.bytes(expected.result()));
        return declared;
    }

    static boolean resultMatchesCausal(
            QualificationRecords.RunResult result,
            QualificationCausalFinalizer.Document causal) {
        if (result == null || causal == null) return false;
        QualificationCausalFinalizer.EvidenceIdentity identity = causal.evidenceIdentity();
        return result.campaignId().equals(identity.campaignId())
                && result.scenarioBankSha256().equals(identity.scenarioBankSha256())
                && result.candidateAlias().equals(identity.candidateAlias())
                && result.machineId().equals(identity.machineId())
                && result.engineLaneId().equals(identity.engineLaneId())
                && result.configId().equals(identity.configId())
                && result.scenarioId().equals(causal.scenarioId())
                && result.repetitionId().equals(causal.repetitionId())
                && result.failureClass() == causal.failureClass()
                && result.answerOrigin() == causal.answerOrigin()
                && result.responseOwnerContract() == causal.responseOwnerContract()
                && result.causalOwner() == causal.causalOwner()
                && result.causalReasonCode() == causal.reasonCode()
                && result.comparativeScoreEligible() == causal.comparativeScoreEligible()
                && result.causalLedger().equals(causal.causalLedger())
                && result.trustGates().equals(causal.trustGates())
                && result.requirements().equals(causal.requirements())
                && result.evidenceComplete() == causal.evidenceComplete();
    }

    private static int repetitionNumber(
            QualificationRecords.CampaignManifest campaign,
            String repetitionId) {
        int first = campaign.repetitions().indexOf(repetitionId);
        int last = campaign.repetitions().lastIndexOf(repetitionId);
        if (first < 0 || first != last) {
            throw new IllegalArgumentException(
                    "request repetition is not uniquely declared by the campaign");
        }
        return first + 1;
    }

    private static void requireSameCapturedInputs(
            QualificationPreResultEvidence.Snapshot expected,
            QualificationPreResultEvidence.Snapshot actual) {
        boolean same = expected.artifactHashes().equals(actual.artifactHashes())
                && expected.findings().equals(actual.findings())
                && Objects.equals(expected.request(), actual.request())
                && Objects.equals(expected.campaign(), actual.campaign())
                && Objects.equals(expected.calibration(), actual.calibration())
                && Objects.equals(expected.machine(), actual.machine())
                && Objects.equals(expected.executionIdentity(), actual.executionIdentity())
                && Objects.equals(expected.trace(), actual.trace())
                && expected.traceId().equals(actual.traceId())
                && expected.incompleteTerminalTrace()
                        == actual.incompleteTerminalTrace()
                && Objects.equals(expected.timings(), actual.timings())
                && Objects.equals(expected.resources(), actual.resources())
                && Objects.equals(expected.resourceSamples(), actual.resourceSamples())
                && expected.requirements().equals(actual.requirements())
                && expected.canaryScan().equals(actual.canaryScan())
                && expected.matchedAnswerOrigin() == actual.matchedAnswerOrigin()
                && expected.operatorInterrupted() == actual.operatorInterrupted()
                && expected.harnessFailureProven() == actual.harnessFailureProven()
                && expected.trustGates().equals(actual.trustGates());
        if (!same) {
            throw new IllegalStateException(
                    "qualification evidence bytes changed during finalization");
        }
    }

    private static void requireCanonicalAuthority(
            Path path,
            byte[] published,
            byte[] expected) {
        if (!java.util.Arrays.equals(published, expected)) {
            throw new IllegalStateException(
                    "published qualification authority bytes are not canonical: " + path);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }
}
