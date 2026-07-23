package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;
import dev.talos.engine.llamacpp.LlamaCppQualificationObserverIdentity;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;

import java.io.IOException;
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
                case "launch-environment-hash" -> {
                    LlamaCppQualificationObserverIdentity observer =
                            LlamaCppQualificationObserverIdentity.current();
                    out.println(ProviderProcessLaunchReceipt.EnvironmentContract
                            .QUALIFICATION_RELEVANT_ALLOWLIST_V1.name()
                            + " "
                             + ProviderProcessLaunchReceipt.digestEffectiveEnvironment(
                                     System.getenv())
                            + " " + observer.kind().name()
                            + " " + observer.sha256());
                    yield 0;
                }
                case "preflight" -> preflight(parsed.root(), catalogForPacket(parsed.root()), out, err);
                case "validate" -> validate(parsed.root(), catalogForPacket(parsed.root()), out, err);
                case "assert-writable-campaign" -> assertWritableCampaign(
                        parsed.root(), catalogForMutation(parsed.root()), out);
                case "score" -> score(parsed.root(), parsed.bank(), catalogForMutation(parsed.root()), out, err);
                case "complete-campaign" -> completeCampaign(
                        parsed.root(), parsed.bank(), catalogForMutation(parsed.root()), out, err);
                case "reconcile-run-publication" -> reconcileRunPublication(
                        parsed.root(), parsed.runRoot(), parsed.scenarioId(),
                        parsed.serverPort(), catalogForMutation(parsed.root()), out, err);
                case "requirements" -> requirements(
                        parsed.root(), parsed.runRoot(), parsed.scenarioId(),
                        catalogForMutation(parsed.root()), out);
                case "approvals" -> approvals(
                        parsed.root(), parsed.runRoot(), parsed.scenarioId(),
                        catalogForMutation(parsed.root()), out);
                case "provider-evidence" -> providerEvidence(
                        parsed.root(), parsed.runRoot(), parsed.scenarioId(),
                        parsed.serverPort(), catalogForMutation(parsed.root()), out);
                case "trace-evidence" -> traceEvidence(
                        parsed.root(), parsed.runRoot(), parsed.scenarioId(),
                        catalogForMutation(parsed.root()), out);
                default -> throw new IllegalArgumentException("Unknown command: " + parsed.command());
            };
        } catch (IllegalArgumentException e) {
            err.println("Qualification command failed: " + e.getMessage());
            err.println(Arguments.usage());
            return 2;
        } catch (Exception e) {
            QualificationPacketPublisher.printTypedFailure(err, e);
            err.println("Qualification command failed: " + e.getMessage());
            return 3;
        }
    }

    private static QualificationScenarioBank.Catalog catalogForPacket(Path root) throws Exception {
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(root);
        Path campaignPath = packetFiles.path().resolve("campaign.json");
        if (packetFiles.inspect(campaignPath)
                != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
            return QualificationScenarioBank.loadDefault();
        }
        int protocolVersion = QualificationJson.requiredInt(
                QualificationJson.readTree(
                        packetFiles.readAllBytes(campaignPath)).get("protocolVersion"),
                "campaign protocolVersion");
        return QualificationScenarioBank.load(protocolVersion);
    }

    private static QualificationScenarioBank.Catalog catalogForMutation(Path root) throws Exception {
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(root);
        Path campaignPath = packetFiles.path().resolve("campaign.json");
        if (packetFiles.inspect(campaignPath)
                != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
            throw new IllegalArgumentException(
                    "qualification artifact publication requires campaign.json");
        }
        int protocolVersion = QualificationJson.requiredInt(
                QualificationJson.readTree(
                        packetFiles.readAllBytes(campaignPath)).get("protocolVersion"),
                "campaign protocolVersion");
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(protocolVersion);
        QualificationArtifactPublication.requireWritableCampaign(root, catalog);
        return catalog;
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

    private static int assertWritableCampaign(
            Path root,
            QualificationScenarioBank.Catalog catalog,
            PrintStream out) throws IOException {
        return QualificationPacketPublisher.withLease(root, lease -> {
            Path completion = lease.packetRoot().resolve(
                    QualificationCampaignCompletion.OUTPUT_FILE);
            QualificationPacketPublisher.PublicationStatus lifecycle =
                    lease.publicationStatus(
                            QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                            List.of(completion));
            if (lifecycle != QualificationPacketPublisher.PublicationStatus.ABSENT) {
                throw new IOException("campaign completion has started or committed");
            }
            out.println("Qualification campaign is writable under protocol v"
                    + catalog.protocolVersion() + ": " + lease.packetRoot());
            return 0;
        });
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
            PrintStream err) throws java.io.IOException {
        return QualificationPacketPublisher.withLease(
                root,
                lease -> scoreUnderLease(lease, bank, catalog, out, err));
    }

    private static int scoreUnderLease(
            QualificationPacketPublisher.Lease lease,
            QualificationProtocol.BankKind bank,
            QualificationScenarioBank.Catalog catalog,
            PrintStream out,
            PrintStream err) throws java.io.IOException {
        Path root = lease.packetRoot();
        QualificationRecords.CampaignManifest lockedCampaign = lease.campaign();
        QualificationRecords.CampaignCompletion completion;
        try {
            completion = QualificationCampaignCompletion.requireCommitted(
                    lease, bank, catalog);
        } catch (IOException | RuntimeException incomplete) {
            QualificationPacketPublisher.printTypedFailure(err, incomplete);
            err.println("FAIL campaign completion is not committed: "
                    + incomplete.getMessage());
            return 1;
        }
        if (completion.completionReason()
                != QualificationRecords.CampaignCompletionReason.COMPLETE_GRID) {
            err.println("FAIL hard-stop campaign completion is not scoreable");
            return 1;
        }
        String name = lockedCampaign.candidateAlias() + "-" + lockedCampaign.configId() + "-"
                + bank.name().toLowerCase(Locale.ROOT) + ".json";
        Path target = root.resolve("scorecards").resolve(name);
        String transactionId = QualificationPacketPublisher.transactionIdFor(
                root,
                QualificationPacketPublisher.PublicationKind.SCORECARD,
                List.of(target));
        QualificationEvidenceValidator.Validation validation =
                QualificationEvidenceValidator.validateForTransactionRecovery(
                        lease, catalog, transactionId);
        if (!validation.valid()) {
            validation.findings().forEach(finding -> err.println("FAIL " + finding));
            return 1;
        }
        QualificationRecords.EngineFacts engine = validation.engine();
        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog,
                bank,
                validation.runs(),
                new QualificationScorer.PerformanceEnvelope(
                        engine.doctorProjectionSeconds(), engine.configuredTimeoutSeconds()));
        QualificationRecords.CampaignManifest campaign = validation.campaign();
        if (!campaign.equals(lockedCampaign)) {
            throw new IOException("qualification campaign changed while scoring");
        }
        lease.publish(
                QualificationPacketPublisher.PublicationKind.SCORECARD,
                List.of(new QualificationPacketPublisher.Member(
                        target, QualificationJson.bytes(scorecard))));
        out.println("Qualification scorecard: " + target.toAbsolutePath().normalize());
        out.println("Disposition: " + scorecard.disposition());
        return switch (scorecard.disposition()) {
            case ADMISSION_PASSED, ACCEPTED_BETA, EXPERIMENTAL_SELECTABLE -> 0;
            case ADMISSION_FAILED, BLOCKED_TRUST, BLOCKED_INCOMPLETE, REJECTED -> 1;
        };
    }

    private static int completeCampaign(
            Path root,
            QualificationProtocol.BankKind bank,
            QualificationScenarioBank.Catalog catalog,
            PrintStream out,
            PrintStream err) {
        try {
            QualificationCampaignCompletion.Publication publication =
                    QualificationCampaignCompletion.complete(root, bank, catalog);
            out.println("Qualification campaign completion: "
                    + publication.path().toAbsolutePath().normalize());
            out.println("Completion reason: "
                    + publication.completion().completionReason());
            return 0;
        } catch (IOException | RuntimeException incomplete) {
            QualificationPacketPublisher.printTypedFailure(err, incomplete);
            err.println("FAIL campaign completion refused: " + incomplete.getMessage());
            return 1;
        }
    }

    private static int reconcileRunPublication(
            Path root,
            Path runRoot,
            String scenarioId,
            int serverPort,
            QualificationScenarioBank.Catalog catalog,
            PrintStream out,
            PrintStream err) {
        try {
            QualificationRunPublicationReconciler.Result result =
                    QualificationRunPublicationReconciler.reconcile(
                            root, runRoot, scenarioId, serverPort, catalog);
            String kind = result.publicationKind() == null
                    ? "null"
                    : "\"" + result.publicationKind().name() + "\"";
            out.println("{\"state\":\"" + result.state().name()
                    + "\",\"publicationKind\":" + kind + "}");
            return 0;
        } catch (IOException | RuntimeException failure) {
            QualificationPacketPublisher.printTypedFailure(err, failure);
            err.println("FAIL run publication reconciliation refused: "
                    + failure.getMessage());
            return 1;
        }
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
                        root, runRoot, scenarioId, serverPort, catalog);
        out.println("Qualification provider evidence: prompt="
                + result.promptDebug().toAbsolutePath().normalize()
                + " model=" + result.modelLog().toAbsolutePath().normalize()
                + " providerPromptSent=" + result.providerPromptSent());
        return 0;
    }

    private static int traceEvidence(
            Path root,
            Path runRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog,
            PrintStream out) throws Exception {
        catalog.scenario(scenarioId);
        QualificationTraceEvidencePublication.Result result =
                QualificationTraceEvidencePublication.publish(
                        root, runRoot, scenarioId, catalog);
        out.println("Qualification trace evidence: trace="
                + result.trace().toAbsolutePath().normalize()
                + " traceId=" + result.traceId()
                + " sha256=" + result.sha256()
                + " created=" + result.created());
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
            if (command.equals("bank-hash")
                    || command.equals("launch-environment-hash")) {
                if (args.size() != 1) {
                    throw new IllegalArgumentException(command + " accepts no arguments");
                }
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
            if ((command.equals("score") || command.equals("complete-campaign"))
                    && bank == null) {
                throw new IllegalArgumentException(
                        "--bank admission|full is required for " + command);
            }
            if ((command.equals("requirements")
                    || command.equals("approvals")
                    || command.equals("provider-evidence")
                    || command.equals("trace-evidence")
                    || command.equals("reconcile-run-publication"))
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
                    && !command.equals("assert-writable-campaign")
                    && !command.equals("score")
                    && !command.equals("complete-campaign")
                    && !command.equals("reconcile-run-publication")
                    && !command.equals("requirements")
                    && !command.equals("approvals")
                    && !command.equals("provider-evidence")
                    && !command.equals("trace-evidence")) {
                throw new IllegalArgumentException("Unknown command: " + command);
            }
            return new Arguments(
                    command, root, bank, scenarioId, runRoot, serverPort);
        }

        static String usage() {
            return "Usage: bank-hash | launch-environment-hash | "
                    + "preflight --root <packet> | validate --root <packet> | "
                    + "assert-writable-campaign --root <packet> | "
                     + "complete-campaign --root <packet> --bank admission|full | "
                     + "reconcile-run-publication --root <packet> --scenario <id> "
                    + "--run <run-root> [--server-port <port>] | "
                    + "score --root <packet> --bank admission|full | requirements --root <packet> "
                    + "--scenario <id> --run <run-root> | approvals --root <packet> "
                    + "--scenario <id> --run <run-root> | provider-evidence --root <packet> "
                    + "--scenario <id> --run <run-root> --server-port <port> | "
                    + "trace-evidence --root <packet> --scenario <id> --run <run-root>";
        }
    }
}
