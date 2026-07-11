package dev.talos.qualification;

import java.io.PrintStream;
import java.nio.file.Path;

/** CLI guard for a campaign runner after it finalizes each result.json. */
public final class QualificationCampaignContinuationMain {
    private QualificationCampaignContinuationMain() {}

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err);
        if (exit != 0) System.exit(exit);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
            err.println("Expected one finalized result.json path");
            return 2;
        }
        try {
            Path path = Path.of(args[0]).toAbsolutePath().normalize();
            QualificationRecords.RunResult result = QualificationJson.read(
                    path, QualificationRecords.RunResult.class);
            QualificationCampaignContinuationPolicy.Decision decision =
                    QualificationCampaignContinuationPolicy.evaluate(result);
            if (decision.stop()) {
                decision.reasons().forEach(reason -> err.println("STOP " + reason));
                return 1;
            }
            out.println("CONTINUE " + result.scenarioId() + "/" + result.repetitionId());
            return 0;
        } catch (Exception failure) {
            err.println("STOP unable-to-evaluate-result: " + failure.getMessage());
            return 1;
        }
    }
}
