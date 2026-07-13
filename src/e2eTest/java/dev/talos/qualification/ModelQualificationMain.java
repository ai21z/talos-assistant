package dev.talos.qualification;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Developer entrypoint for qualification bank identity, packet validation, and scoring. */
public final class ModelQualificationMain {
    private ModelQualificationMain() {}

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err);
        if (exit != 0) System.exit(exit);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            Arguments parsed = Arguments.parse(args);
            return switch (parsed.command()) {
                case "bank-hash" -> {
                    QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
                    out.println(catalog.sha256());
                    yield 0;
                }
                case "preflight" -> preflight(parsed.root(), catalogForPacket(parsed.root()), out, err);
                case "validate" -> validate(parsed.root(), catalogForPacket(parsed.root()), out, err);
                case "score" -> score(parsed.root(), parsed.bank(), catalogForPacket(parsed.root()), out, err);
                case "requirements" -> requirements(
                        parsed.root(), parsed.runRoot(), parsed.scenarioId(),
                        catalogForPacket(parsed.root()), out);
                case "approvals" -> approvals(
                        parsed.root(), parsed.runRoot(), parsed.scenarioId(),
                        catalogForPacket(parsed.root()), out);
                case "provider-evidence" -> providerEvidence(
                        parsed.root(), parsed.runRoot(), parsed.scenarioId(),
                        parsed.serverPort(), catalogForPacket(parsed.root()), out);
                default -> throw new IllegalArgumentException("Unknown command: " + parsed.command());
            };
        } catch (IllegalArgumentException e) {
            err.println("Qualification command failed: " + e.getMessage());
            err.println(Arguments.usage());
            return 2;
        } catch (Exception e) {
            err.println("Qualification command failed: " + e.getMessage());
            return 3;
        }
    }

    private static QualificationScenarioBank.Catalog catalogForPacket(Path root) throws Exception {
        Path campaignPath = root.toAbsolutePath().normalize().resolve("campaign.json");
        if (!Files.isRegularFile(campaignPath)) {
            return QualificationScenarioBank.loadDefault();
        }
        int protocolVersion = QualificationJson.readTree(campaignPath)
                .path("protocolVersion").asInt(-1);
        return QualificationScenarioBank.load(protocolVersion);
    }

    private static int preflight(
            Path root,
            QualificationScenarioBank.Catalog catalog,
            PrintStream out,
            PrintStream err) {
        QualificationEvidenceValidator.Validation validation =
                QualificationEvidenceValidator.validateCalibrationPreflight(root, catalog);
        if (!validation.valid()) {
            validation.findings().forEach(finding -> err.println("FAIL " + finding));
            return 1;
        }
        out.println("Qualification calibration frozen and valid: " + root.toAbsolutePath().normalize());
        out.println("Scenario bank SHA-256: " + catalog.sha256());
        return 0;
    }

    private static int validate(
            Path root,
            QualificationScenarioBank.Catalog catalog,
            PrintStream out,
            PrintStream err) {
        QualificationEvidenceValidator.Validation validation =
                QualificationEvidenceValidator.validate(root, catalog);
        if (!validation.valid()) {
            validation.findings().forEach(finding -> err.println("FAIL " + finding));
            return 1;
        }
        out.println("Qualification evidence valid: " + root.toAbsolutePath().normalize());
        out.println("Validated runs: " + validation.runs().size());
        return 0;
    }

    private static int score(
            Path root,
            QualificationProtocol.BankKind bank,
            QualificationScenarioBank.Catalog catalog,
            PrintStream out,
            PrintStream err) throws Exception {
        QualificationEvidenceValidator.Validation validation =
                QualificationEvidenceValidator.validate(root, catalog);
        if (!validation.valid()) {
            validation.findings().forEach(finding -> err.println("FAIL " + finding));
            return 1;
        }
        QualificationRecords.EngineFacts engine = validation.engine();
        if (validation.campaign().endedAt() == null || validation.campaign().endedAt().isBlank()) {
            err.println("FAIL campaign endedAt must be recorded before scoring");
            return 1;
        }
        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog,
                bank,
                validation.runs(),
                new QualificationScorer.PerformanceEnvelope(
                        engine.doctorProjectionSeconds(), engine.configuredTimeoutSeconds()));
        QualificationRecords.CampaignManifest campaign = validation.campaign();
        String name = campaign.candidateAlias() + "-" + campaign.configId() + "-"
                + bank.name().toLowerCase(Locale.ROOT) + ".json";
        Path target = root.resolve("scorecards").resolve(name);
        QualificationJson.write(target, scorecard);
        out.println("Qualification scorecard: " + target.toAbsolutePath().normalize());
        out.println("Disposition: " + scorecard.disposition());
        return switch (scorecard.disposition()) {
            case ADMISSION_PASSED, ACCEPTED_BETA, EXPERIMENTAL_SELECTABLE -> 0;
            case ADMISSION_FAILED, BLOCKED_TRUST, BLOCKED_INCOMPLETE, REJECTED -> 1;
        };
    }

    private static int requirements(
            Path root,
            Path runRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog,
            PrintStream out) throws Exception {
        Path output = QualificationRequirementFinalizer.evaluateAndWrite(
                root, runRoot, scenarioId, catalog);
        out.println("Mechanical qualification requirements: "
                + output.toAbsolutePath().normalize());
        return 0;
    }

    private static int approvals(
            Path root,
            Path runRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog,
            PrintStream out) throws Exception {
        Path output = QualificationApprovalFinalizer.deriveAndWrite(
                root, runRoot, scenarioId, catalog);
        out.println("Qualification approval evidence: "
                + output.toAbsolutePath().normalize());
        return 0;
    }

    private static int providerEvidence(
            Path root,
            Path runRoot,
            String scenarioId,
            int serverPort,
            QualificationScenarioBank.Catalog catalog,
            PrintStream out) throws Exception {
        catalog.scenario(scenarioId);
        QualificationProviderEvidenceFinalizer.Result result =
                QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        root, runRoot, scenarioId, serverPort);
        out.println("Qualification provider evidence: prompt="
                + result.promptDebug().toAbsolutePath().normalize()
                + " model=" + result.modelLog().toAbsolutePath().normalize()
                + " providerPromptSent=" + result.providerPromptSent());
        return 0;
    }

    private record Arguments(
            String command,
            Path root,
            QualificationProtocol.BankKind bank,
            String scenarioId,
            Path runRoot,
            int serverPort) {
        static Arguments parse(String[] raw) {
            List<String> args = raw == null ? List.of() : List.of(raw);
            if (args.isEmpty()) throw new IllegalArgumentException("A command is required");
            String command = args.getFirst().trim().toLowerCase(Locale.ROOT);
            if (command.equals("bank-hash")) {
                if (args.size() != 1) throw new IllegalArgumentException("bank-hash accepts no arguments");
                return new Arguments(command, null, null, "", null, -1);
            }
            Path root = null;
            QualificationProtocol.BankKind bank = null;
            String scenarioId = "";
            Path runRoot = null;
            int serverPort = -1;
            for (int index = 1; index < args.size(); index++) {
                String token = args.get(index);
                if (token.equals("--root") && index + 1 < args.size()) {
                    root = Path.of(args.get(++index));
                } else if (token.equals("--bank") && index + 1 < args.size()) {
                    bank = QualificationProtocol.BankKind.valueOf(
                            args.get(++index).trim().toUpperCase(Locale.ROOT));
                } else if (token.equals("--scenario") && index + 1 < args.size()) {
                    scenarioId = args.get(++index).strip();
                } else if (token.equals("--run") && index + 1 < args.size()) {
                    runRoot = Path.of(args.get(++index));
                } else if (token.equals("--server-port") && index + 1 < args.size()) {
                    try {
                        serverPort = Integer.parseInt(args.get(++index));
                    } catch (NumberFormatException error) {
                        throw new IllegalArgumentException("--server-port must be an integer");
                    }
                } else {
                    throw new IllegalArgumentException("Unknown or incomplete argument: " + token);
                }
            }
            if (root == null) throw new IllegalArgumentException("--root is required");
            if (command.equals("score") && bank == null) {
                throw new IllegalArgumentException("--bank admission|full is required for score");
            }
            if ((command.equals("requirements")
                    || command.equals("approvals")
                    || command.equals("provider-evidence"))
                    && (scenarioId.isBlank() || runRoot == null)) {
                throw new IllegalArgumentException(
                        "--scenario and --run are required for " + command);
            }
            if (command.equals("provider-evidence") && (serverPort < 1 || serverPort > 65_535)) {
                throw new IllegalArgumentException(
                        "--server-port 1..65535 is required for provider-evidence");
            }
            if (!command.equals("preflight")
                    && !command.equals("validate")
                    && !command.equals("score")
                    && !command.equals("requirements")
                    && !command.equals("approvals")
                    && !command.equals("provider-evidence")) {
                throw new IllegalArgumentException("Unknown command: " + command);
            }
            return new Arguments(command, root, bank, scenarioId, runRoot, serverPort);
        }

        static String usage() {
            return "Usage: bank-hash | preflight --root <packet> | validate --root <packet> | "
                    + "score --root <packet> --bank admission|full | requirements --root <packet> "
                    + "--scenario <id> --run <run-root> | approvals --root <packet> "
                    + "--scenario <id> --run <run-root> | provider-evidence --root <packet> "
                    + "--scenario <id> --run <run-root> --server-port <port>";
        }
    }
}
