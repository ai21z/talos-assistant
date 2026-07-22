package dev.talos.qualification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Child-JVM entry point for qualification publication process-lock and crash tests. */
public final class QualificationPacketPublicationProcessMain {
    private QualificationPacketPublicationProcessMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 10) {
            runMatrixPublisher(args);
            return;
        }
        if (args.length != 7) System.exit(64);
        runLegacyPublisher(args);
    }

    private static void runLegacyPublisher(String[] args) throws Exception {
        String mode = args[0];
        Path packetRoot = Path.of(args[1]).toAbsolutePath().normalize();
        Path runRoot = Path.of(args[2]).toAbsolutePath().normalize();
        Path ready = Path.of(args[3]).toAbsolutePath().normalize();
        Path release = Path.of(args[4]).toAbsolutePath().normalize();
        String value = args[5];
        long timeoutMillis = Long.parseLong(args[6]);
        Files.createDirectories(ready.getParent());

        if (mode.equals("hold")) {
            QualificationPacketPublisher.withLease(
                    packetRoot,
                    Duration.ofMillis(timeoutMillis),
                    QualificationPacketPublisher.FaultInjector.NONE,
                    lease -> {
                        Files.writeString(ready, "ready\n", StandardCharsets.UTF_8);
                        waitFor(release, Duration.ofSeconds(30));
                        return null;
                    });
            return;
        }

        QualificationPacketPublisher.FaultStage fault = mode.equals("crash")
                ? QualificationPacketPublisher.FaultStage.valueOf(value)
                : null;
        Files.writeString(ready, "ready\n", StandardCharsets.UTF_8);
        waitFor(release, Duration.ofSeconds(30));
        try {
            QualificationPacketPublisher.withLease(
                    packetRoot,
                    Duration.ofMillis(timeoutMillis),
                    (stage, ordinal) -> {
                        if (fault != null && stage == fault
                                && (ordinal == 0 || ordinal == -1)) {
                            Runtime.getRuntime().halt(73);
                        }
                    },
                    lease -> {
                        byte[] prompt = ("prompt-" + value + "\n")
                                .getBytes(StandardCharsets.UTF_8);
                        byte[] model = ("model-" + value + "\n")
                                .getBytes(StandardCharsets.UTF_8);
                        lease.publish(
                                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                                List.of(
                                        new QualificationPacketPublisher.Member(
                                                runRoot.resolve("prompt-debug.txt"), prompt),
                                        new QualificationPacketPublisher.Member(
                                                runRoot.resolve("llama.log"), model)));
                        return null;
                    });
        } catch (QualificationPacketPublisher.PublicationException failure) {
            System.err.println(failure.reason().name());
            System.exit(failure.reason()
                    == QualificationPacketPublisher.FailureReason.LOCK_TIMEOUT ? 5 : 4);
        }
    }

    private static void runMatrixPublisher(String[] args) throws Exception {
        String mode = args[0];
        if (!List.of(
                "matrix-publish",
                "matrix-crash",
                "matrix-pause",
                "matrix-bootstrap-publish",
                "matrix-bootstrap-crash",
                "matrix-bootstrap-pause").contains(mode)) {
            System.exit(64);
        }
        Path packetRoot = Path.of(args[1]).toAbsolutePath().normalize();
        Path runRoot = Path.of(args[2]).toAbsolutePath().normalize();
        Path ready = Path.of(args[3]).toAbsolutePath().normalize();
        Path release = Path.of(args[4]).toAbsolutePath().normalize();
        String value = args[5];
        long timeoutMillis = Long.parseLong(args[6]);
        QualificationPacketPublisher.PublicationKind kind =
                QualificationPacketPublisher.PublicationKind.valueOf(args[7]);
        QualificationPacketPublisher.FaultStage fault = "NONE".equals(args[8])
                ? null : QualificationPacketPublisher.FaultStage.valueOf(args[8]);
        int faultOrdinal = Integer.parseInt(args[9]);
        Path stageReady = sibling(ready, ".stage");
        Path stageRelease = sibling(release, ".stage");
        Files.createDirectories(ready.getParent());
        Files.writeString(ready, "ready\n", StandardCharsets.UTF_8);
        waitFor(release, Duration.ofSeconds(30));

        try {
            QualificationPacketPublisher.FaultInjector injector = (stage, ordinal) -> {
                if (fault == null || stage != fault || ordinal != faultOrdinal) {
                    return;
                }
                if (mode.equals("matrix-crash") || mode.equals("matrix-bootstrap-crash")) {
                    Runtime.getRuntime().halt(73);
                }
                if (mode.equals("matrix-pause") || mode.equals("matrix-bootstrap-pause")) {
                    Files.writeString(
                            stageReady,
                            stage.name() + ":" + ordinal + "\n",
                            StandardCharsets.UTF_8);
                    waitFor(stageRelease, Duration.ofSeconds(30));
                }
            };
            QualificationPacketPublisher.LeaseAction<QualificationPacketPublisher.CommittedSet>
                    publication = lease -> {
                        if (kind == QualificationPacketPublisher.PublicationKind
                                .CAMPAIGN_COMPLETION) {
                            QualificationCampaignCompletion.Publication completion =
                                    QualificationCampaignCompletion.completeUnderLease(
                                            lease,
                                            QualificationProtocol.BankKind.ADMISSION,
                                            QualificationScenarioBank.loadDefault());
                            QualificationPacketPublisher.CommittedSet committed =
                                    lease.requireCommittedSet(
                                            kind, List.of(completion.path()));
                            return new QualificationPacketPublisher.CommittedSet(
                                    committed.commit(),
                                    committed.memberBytes(),
                                    committed.commitPath(),
                                    completion.created());
                        }
                        return lease.publish(
                                kind,
                                matrixMembers(packetRoot, runRoot, kind, value));
                    };
            QualificationPacketPublisher.CommittedSet committed = mode.startsWith("matrix-bootstrap-")
                    ? QualificationPacketPublisher.withBootstrapLease(
                            packetRoot,
                            Duration.ofMillis(timeoutMillis),
                            injector,
                            publication)
                    : QualificationPacketPublisher.withLease(
                            packetRoot,
                            Duration.ofMillis(timeoutMillis),
                            injector,
                            publication);
            System.out.println("CREATED=" + committed.created());
            System.out.println("TRANSACTION=" + committed.commit().transactionId());
        } catch (QualificationPacketPublisher.PublicationException failure) {
            System.err.println(failure.reason().name());
            System.err.println(failure.commitState().name());
            System.exit(failure.reason()
                    == QualificationPacketPublisher.FailureReason.LOCK_TIMEOUT ? 5 : 4);
        }
    }

    private static List<QualificationPacketPublisher.Member> matrixMembers(
            Path packetRoot,
            Path runRoot,
            QualificationPacketPublisher.PublicationKind kind,
            String value) throws IOException {
        return switch (kind) {
            case EXECUTION_IDENTITY -> List.of(
                    member(packetRoot.resolve(QualificationExecutionIdentityWriter.LEDGER_FILE),
                            "identity-ledger", value),
                    member(packetRoot.resolve(QualificationExecutionIdentity.HARNESS_FILE),
                            "identity-harness", value),
                    member(packetRoot.resolve(QualificationExecutionIdentity.RUNTIME_FILE),
                            "identity-runtime", value));
            case PTY_CAPTURE -> List.of(
                    member(runRoot.resolve("transcript.raw.json"), "pty", value));
            case PTY_FAILURE -> List.of(
                    member(runRoot.resolve("transcript.failed.txt"), "pty-failure", value));
            case APPROVAL_EVIDENCE -> List.of(
                    member(runRoot.resolve(QualificationApprovalFinalizer.OUTPUT_FILE),
                            "approvals", value));
            case MECHANICAL_REQUIREMENTS -> List.of(
                    member(runRoot.resolve(QualificationRequirementFinalizer.OUTPUT_FILE),
                            "requirements", value));
            case PROVIDER_EVIDENCE -> List.of(
                    member(runRoot.resolve("prompt-debug.txt"), "prompt", value),
                    member(runRoot.resolve("llama.log"), "model", value));
            case RUN_FINALIZATION -> List.of(
                    member(runRoot.resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                            "causal", value),
                    member(runRoot.resolve(QualificationRunFinalizer.CANARY_FILE),
                            "canary", value),
                    member(runRoot.resolve(QualificationRunFinalizer.RESULT_FILE),
                            "result", value));
            case CAMPAIGN_COMPLETION -> throw new IllegalArgumentException(
                    "campaign completion uses its canonical lifecycle owner");
            case SCORECARD -> {
                QualificationRecords.CampaignManifest campaign = QualificationJson.read(
                        packetRoot.resolve("campaign.json"),
                        QualificationRecords.CampaignManifest.class);
                Path target = packetRoot.resolve("scorecards").resolve(
                        campaign.candidateAlias() + "-" + campaign.configId()
                                + "-admission.json");
                yield List.of(member(target, "scorecard", value));
            }
            default -> throw new IllegalArgumentException(
                    "matrix process does not support " + kind);
        };
    }

    private static QualificationPacketPublisher.Member member(
            Path path,
            String label,
            String value) {
        return new QualificationPacketPublisher.Member(
                path,
                (label + "-" + value + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static Path sibling(Path path, String suffix) {
        return path.resolveSibling(path.getFileName() + suffix);
    }

    private static void waitFor(Path release, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(release)) {
            if (System.nanoTime() >= deadline) {
                throw new IOException("process-test release timed out");
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("process-test release was interrupted", interrupted);
            }
        }
    }
}
