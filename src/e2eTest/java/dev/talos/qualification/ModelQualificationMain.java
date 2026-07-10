package dev.talos.qualification;

import java.io.PrintStream;
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
            QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
            return switch (parsed.command()) {
                case "bank-hash" -> {
                    out.println(catalog.sha256());
                    yield 0;
                }
                case "preflight" -> preflight(parsed.root(), catalog, out, err);
                case "validate" -> validate(parsed.root(), catalog, out, err);
                case "score" -> score(parsed.root(), parsed.bank(), catalog, out, err);
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

    private record Arguments(String command, Path root, QualificationProtocol.BankKind bank) {
        static Arguments parse(String[] raw) {
            List<String> args = raw == null ? List.of() : List.of(raw);
            if (args.isEmpty()) throw new IllegalArgumentException("A command is required");
            String command = args.getFirst().trim().toLowerCase(Locale.ROOT);
            if (command.equals("bank-hash")) {
                if (args.size() != 1) throw new IllegalArgumentException("bank-hash accepts no arguments");
                return new Arguments(command, null, null);
            }
            Path root = null;
            QualificationProtocol.BankKind bank = null;
            for (int index = 1; index < args.size(); index++) {
                String token = args.get(index);
                if (token.equals("--root") && index + 1 < args.size()) {
                    root = Path.of(args.get(++index));
                } else if (token.equals("--bank") && index + 1 < args.size()) {
                    bank = QualificationProtocol.BankKind.valueOf(
                            args.get(++index).trim().toUpperCase(Locale.ROOT));
                } else {
                    throw new IllegalArgumentException("Unknown or incomplete argument: " + token);
                }
            }
            if (root == null) throw new IllegalArgumentException("--root is required");
            if (command.equals("score") && bank == null) {
                throw new IllegalArgumentException("--bank admission|full is required for score");
            }
            if (!command.equals("preflight") && !command.equals("validate") && !command.equals("score")) {
                throw new IllegalArgumentException("Unknown command: " + command);
            }
            return new Arguments(command, root, bank);
        }

        static String usage() {
            return "Usage: bank-hash | preflight --root <packet> | validate --root <packet> | "
                    + "score --root <packet> --bank admission|full";
        }
    }
}
