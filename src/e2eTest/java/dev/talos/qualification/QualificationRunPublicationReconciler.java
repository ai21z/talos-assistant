package dev.talos.qualification;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Recovers exactly one mechanically identified protocol-v6 run-publication phase. */
final class QualificationRunPublicationReconciler {
    private QualificationRunPublicationReconciler() {}

    enum State {
        ABSENT,
        RECOVERED,
        COMMITTED
    }

    record Result(State state, QualificationPacketPublisher.PublicationKind publicationKind) {
        Result {
            state = Objects.requireNonNull(state, "reconciliation state");
            if ((state == State.ABSENT) != (publicationKind == null)) {
                throw new IllegalArgumentException(
                        "absent run reconciliation must not declare a publication kind");
            }
        }
    }

    static Result reconcile(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            int serverPort,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Objects.requireNonNull(catalog, "catalog");
        if (catalog.protocolVersion() != QualificationProtocol.VERSION) {
            throw new IllegalArgumentException(
                    "run publication reconciliation requires protocol v"
                            + QualificationProtocol.VERSION);
        }
        return QualificationPacketPublisher.withLease(
                packetRoot,
                lease -> reconcileUnderLease(
                        lease, requestedRunRoot, scenarioId, serverPort, catalog));
    }

    private static Result reconcileUnderLease(
            QualificationPacketPublisher.Lease lease,
            Path requestedRunRoot,
            String scenarioId,
            int serverPort,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        QualificationArtifactPublication.DeclaredRun declared =
                QualificationArtifactPublication.requireWritableDeclaredRun(
                        lease.packetRoot(), requestedRunRoot, scenarioId, catalog);
        Path runRoot = declared.runRoot();
        List<QualificationPacketPublisher.PublicationTarget> targets = List.of(
                new QualificationPacketPublisher.PublicationTarget(
                        QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                        List.of(
                                runRoot.resolve("prompt-debug.txt"),
                                runRoot.resolve("llama.log"))),
                new QualificationPacketPublisher.PublicationTarget(
                        QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                        List.of(runRoot.resolve(QualificationApprovalFinalizer.OUTPUT_FILE))),
                new QualificationPacketPublisher.PublicationTarget(
                        QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                        List.of(runRoot.resolve(QualificationRequirementFinalizer.OUTPUT_FILE))),
                new QualificationPacketPublisher.PublicationTarget(
                        QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                        List.of(
                                runRoot.resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                                runRoot.resolve(QualificationRunFinalizer.CANARY_FILE),
                                runRoot.resolve(QualificationRunFinalizer.RESULT_FILE))));
        QualificationPacketPublisher.PublicationSelection selected =
                lease.selectPublication(targets);
        if (selected.status() == QualificationPacketPublisher.PublicationStatus.ABSENT) {
            return new Result(State.ABSENT, null);
        }

        verifyOrRecover(
                lease,
                runRoot,
                scenarioId,
                serverPort,
                catalog,
                selected.kind(),
                selected.status());
        QualificationPacketPublisher.PublicationSelection settled =
                lease.selectPublication(targets);
        if (settled.status() != QualificationPacketPublisher.PublicationStatus.COMMITTED
                || settled.kind() != selected.kind()) {
            throw new IOException(
                    "run publication reconciliation did not settle the selected phase");
        }
        return new Result(
                selected.status() == QualificationPacketPublisher.PublicationStatus.COMMITTED
                        ? State.COMMITTED : State.RECOVERED,
                selected.kind());
    }

    private static void verifyOrRecover(
            QualificationPacketPublisher.Lease lease,
            Path runRoot,
            String scenarioId,
            int serverPort,
            QualificationScenarioBank.Catalog catalog,
            QualificationPacketPublisher.PublicationKind kind,
            QualificationPacketPublisher.PublicationStatus status) throws IOException {
        switch (kind) {
            case PROVIDER_EVIDENCE -> {
                if (status == QualificationPacketPublisher.PublicationStatus.COMMITTED) {
                    lease.requireCommittedSet(
                            QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                            List.of(
                                    runRoot.resolve("prompt-debug.txt"),
                                    runRoot.resolve("llama.log")));
                    break;
                }
                if (serverPort < 1 || serverPort > 65_535) {
                    throw new IllegalArgumentException(
                            "server port must be between 1 and 65535 for provider recovery");
                }
                QualificationProviderEvidenceFinalizer.finalizeUnderLease(
                        lease, runRoot, scenarioId, serverPort, catalog);
            }
            case APPROVAL_EVIDENCE -> QualificationApprovalFinalizer.deriveAndWriteUnderLease(
                    lease, runRoot, scenarioId, catalog);
            case MECHANICAL_REQUIREMENTS ->
                    QualificationRequirementFinalizer.evaluateAndWriteUnderLease(
                            lease, runRoot, scenarioId, catalog);
            case RUN_FINALIZATION -> {
                QualificationRunFinalizer.finalizeUnderLease(
                        lease,
                        runRoot,
                        scenarioId,
                        catalog,
                        QualificationRunFinalizer.PublicationObserver.NONE);
                QualificationRunFinalizer.verifyPublished(
                        lease,
                        runRoot.resolve(QualificationRunFinalizer.RESULT_FILE),
                        catalog);
            }
            default -> throw new IllegalArgumentException(
                    "unsupported run publication recovery kind: " + kind);
        }
    }
}
