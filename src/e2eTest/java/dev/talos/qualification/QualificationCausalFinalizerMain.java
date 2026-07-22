package dev.talos.qualification;

import java.io.PrintStream;
import java.nio.file.Path;

/** Tracked sole-writer entry point for protocol-v6 run finalization. */
public final class QualificationCausalFinalizerMain {
    private QualificationCausalFinalizerMain() {}

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err);
        if (exit != 0) System.exit(exit);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length != 3) {
            err.println("Expected: <packet-root> <run-root> <scenario-id>");
            return 2;
        }
        try {
            Path packetRoot = Path.of(args[0]).toAbsolutePath().normalize();
            Path runRoot = Path.of(args[1]).toAbsolutePath().normalize();
            String scenarioId = required(args[2], "scenario id");
            int protocolVersion = QualificationJson.requiredInt(
                    QualificationJson.readTree(packetRoot.resolve("campaign.json"))
                            .path("protocolVersion"),
                    "campaign protocolVersion");
            QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(protocolVersion);
            Path output = QualificationRunFinalizer.finalizeRun(
                    packetRoot, runRoot, scenarioId, catalog);
            out.println(output.toAbsolutePath().normalize());
            return 0;
        } catch (Exception failure) {
            QualificationPacketPublisher.printTypedFailure(err, failure);
            String message = failure.getMessage();
            err.println("Qualification run finalization failed: "
                    + (message == null || message.isBlank()
                    ? failure.getClass().getSimpleName()
                    : message));
            return 1;
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.strip();
    }
}
