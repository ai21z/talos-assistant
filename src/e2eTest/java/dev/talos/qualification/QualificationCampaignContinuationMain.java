package dev.talos.qualification;

import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.Path;

/** CLI guard for a campaign runner after it finalizes one declared trial. */
public final class QualificationCampaignContinuationMain {
    private QualificationCampaignContinuationMain() {}

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err);
        if (exit != 0) System.exit(exit);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length != 3
                || args[0] == null || args[0].isBlank()
                || args[1] == null || args[1].isBlank()
                || args[2] == null || args[2].isBlank()) {
            err.println("Expected packet root, scenario id, and positive repetition number");
            return 2;
        }
        try {
            Path packetRoot = Path.of(args[0]).toAbsolutePath().normalize();
            String scenarioId = safeSegment(args[1], "scenario id");
            int repetition = positiveRepetition(args[2]);
            QualificationArtifactPublication.requireWritableCampaign(
                    packetRoot, QualificationScenarioBank.loadDefault());
            return QualificationPacketPublisher.withLease(
                    packetRoot,
                    lease -> evaluateUnderLease(
                            lease, scenarioId, repetition, out, err));
        } catch (Exception failure) {
            QualificationPacketPublisher.printTypedFailure(err, failure);
            err.println("STOP unable-to-evaluate-result: " + failure.getMessage());
            return 1;
        }
    }

    private static int evaluateUnderLease(
            QualificationPacketPublisher.Lease lease,
            String scenarioId,
            int repetition,
            PrintStream out,
            PrintStream err) throws IOException {
            Path packetRoot = lease.packetRoot();
            QualificationRecords.CampaignManifest campaign = lease.campaign();
            Path completionPath = packetRoot.resolve(
                    QualificationCampaignCompletion.OUTPUT_FILE);
            QualificationPacketPublisher.PublicationStatus lifecycle =
                    lease.publicationStatus(
                            QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                            java.util.List.of(completionPath));
            if (lifecycle != QualificationPacketPublisher.PublicationStatus.ABSENT) {
                throw new IllegalStateException(
                        "campaign completion has started or committed");
            }
            if (repetition > campaign.repetitions().size()) {
                throw new IllegalArgumentException(
                        "repetition is not declared by the campaign: " + repetition);
            }
            String repetitionId = campaign.repetitions().get(repetition - 1);
            if (repetitionId == null || repetitionId.isBlank()
                    || campaign.repetitions().indexOf(repetitionId)
                            != campaign.repetitions().lastIndexOf(repetitionId)) {
                throw new IllegalArgumentException(
                        "campaign repetition identity is blank or not unique");
            }

            Path resultPath = packetRoot.resolve("runs")
                    .resolve(safeSegment(campaign.candidateAlias(), "candidate alias"))
                    .resolve(safeSegment(campaign.machineId(), "machine id"))
                    .resolve(safeSegment(campaign.configId(), "configuration id"))
                    .resolve(scenarioId)
                    .resolve("trial-%02d".formatted(repetition))
                    .resolve(QualificationRunFinalizer.RESULT_FILE)
                    .toAbsolutePath().normalize();
            if (!resultPath.startsWith(packetRoot)) {
                throw new IllegalArgumentException("derived result path escapes packet root");
            }

            QualificationScenarioBank.Catalog catalog =
                    QualificationScenarioBank.load(QualificationProtocol.VERSION);
            QualificationRunFinalizer.Publication publication =
                    QualificationRunFinalizer.verifyPublished(
                            lease, resultPath, catalog);
            QualificationRecords.RunResult result = publication.result();
            if (!scenarioId.equals(result.scenarioId())
                    || repetition != result.repetition()
                    || !repetitionId.equals(result.repetitionId())) {
                throw new IllegalArgumentException(
                        "published result identity disagrees with requested trial coordinates");
            }
            boolean modelAnswerEvidenceMatched = publication.causal().answerOrigin()
                    == QualificationRecords.AnswerOrigin.MODEL_PROVIDER;
            QualificationCampaignContinuationPolicy.Decision decision =
                    QualificationCampaignContinuationPolicy.evaluate(
                            result,
                            modelAnswerEvidenceMatched);
            if (decision.stop()) {
                decision.reasons().forEach(reason -> err.println("STOP " + reason));
                return 1;
            }
            out.println("CONTINUE " + result.scenarioId() + "/" + result.repetitionId());
            return 0;
    }

    private static int positiveRepetition(String value) {
        try {
            int repetition = Integer.parseInt(value);
            if (repetition < 1) throw new NumberFormatException("not positive");
            return repetition;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "repetition must be a positive integer", invalid);
        }
    }

    private static String safeSegment(String value, String label) {
        return QualificationNamespace.requireSegment(value, label);
    }
}
