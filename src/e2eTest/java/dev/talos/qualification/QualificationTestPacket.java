package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.spi.types.ProviderResponseCapture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

final class QualificationTestPacket {
    private final Path root;
    private final Path runRoot;

    private QualificationTestPacket(Path root, Path runRoot) {
        this.root = root;
        this.runRoot = runRoot;
    }

    Path root() {
        return root;
    }

    Path runRoot() {
        return runRoot;
    }

    static QualificationTestPacket create(Path root, QualificationScenarioBank.Catalog catalog) throws IOException {
        return create(root, catalog, catalog.admission().scenarios().getFirst());
    }

    static QualificationTestPacket create(
            Path root,
            QualificationScenarioBank.Catalog catalog,
            QualificationProtocol.Scenario scenario) throws IOException {
        Path candidateDir = root.resolve("candidates/qwen14");
        Path machineDir = root.resolve("machines");
        Path engineDir = root.resolve("engines");
        Path runDir = root.resolve("runs/qwen14/reference/standard/" + scenario.id() + "/trial-01");
        Files.createDirectories(candidateDir);
        Files.createDirectories(machineDir);
        Files.createDirectories(engineDir);
        Files.createDirectories(runDir.resolve("before-files"));
        Files.createDirectories(runDir.resolve("after-files"));
        Files.createDirectories(runDir.resolve("final-files"));
        Path artifacts = root.resolve("artifacts");
        Files.createDirectories(artifacts);
        Path modelPath = artifacts.resolve("model.gguf");
        Path executablePath = artifacts.resolve("llama-server.exe");
        Path talosJarPath = artifacts.resolve("talos.jar");
        Files.writeString(modelPath, "test model bytes\n", StandardCharsets.UTF_8);
        Files.writeString(executablePath, "test engine bytes\n", StandardCharsets.UTF_8);
        Files.writeString(talosJarPath, "test installed Talos bytes\n", StandardCharsets.UTF_8);
        String modelSha256 = QualificationScenarioBank.sha256(Files.readAllBytes(modelPath));
        String executableSha256 = QualificationScenarioBank.sha256(Files.readAllBytes(executablePath));
        String talosJarSha256 = QualificationScenarioBank.sha256(Files.readAllBytes(talosJarPath));

        Path rules = candidateDir.resolve("calibration-rules.json");
        Path guide = candidateDir.resolve("calibration-guide.md");
        QualificationJson.write(rules, calibrationRules(
                modelPath, modelSha256, executableSha256, catalog.protocolVersion()));
        Files.writeString(guide, """
                # Qwen14 calibration

                This guide explains the immutable standard configuration defined by calibration-rules.json.

                ## Identity and provenance
                ## Runtime and tool mode
                ## Context, sampling, caps, timeout, and seeds
                ## Hardware envelope
                ## Risks, stop conditions, and evidence
                ## Permitted deviations
                """, StandardCharsets.UTF_8);

        QualificationRecords.CampaignManifest campaign = new QualificationRecords.CampaignManifest(
                QualificationRecords.CAMPAIGN_SCHEMA, QualificationRecords.SCHEMA_VERSION,
                "test-campaign", "0.10.8", "a".repeat(40), catalog.protocolVersion(),
                catalog.sha256(), "qwen14", "reference", "cuda", "standard",
                List.of("r1", "r2", "r3"), "uncontrolled-repetitions",
                QualificationScenarioBank.sha256(Files.readAllBytes(rules)),
                QualificationScenarioBank.sha256(Files.readAllBytes(guide)),
                Instant.parse("2026-07-10T00:00:00Z").toString(), "");
        QualificationRecords.CandidateManifest candidate = new QualificationRecords.CandidateManifest(
                QualificationRecords.CANDIDATE_SCHEMA, QualificationRecords.SCHEMA_VERSION,
                "qwen14", "Qwen/Qwen2.5-Coder-14B-Instruct-GGUF", "b".repeat(40),
                "model.gguf", modelSha256, Files.size(modelPath), "Apache-2.0", "official",
                "qwen2-dense", 14_700_000_000L, 14_700_000_000L,
                "native", "standard-instruct", "embedded-jinja", "CALIBRATION_REFERENCE");
        QualificationRecords.MachineFacts machine = new QualificationRecords.MachineFacts(
                QualificationRecords.MACHINE_SCHEMA, QualificationRecords.SCHEMA_VERSION,
                "reference", "Windows", "test", "amd64", "cpu", 64L << 30,
                "gpu", 16L << 30, "driver", "balanced", "cuda", 16,
                "system-managed", "isolated");
        QualificationRecords.EngineFacts engine = new QualificationRecords.EngineFacts(
                QualificationRecords.ENGINE_SCHEMA, QualificationRecords.SCHEMA_VERSION,
                "cuda", "b9918", "asset.zip", executablePath.toString(), executableSha256,
                List.of("-m", modelPath.toString(), "--jinja", "-c", "16384", "--alias", "qwen14-runtime"),
                List.of("-m", modelPath.toString(), "--jinja", "-c", "16384", "--alias", "qwen14-runtime", "-lv", "4"), "cuda", true,
                "49/49 layers offloaded", 45.0, 300.0);
        QualificationRecords.RunResult result = runResult(catalog, scenario, 1, true, true);

        QualificationJson.write(root.resolve("campaign.json"), campaign);
        QualificationJson.write(candidateDir.resolve("candidate.json"), candidate);
        QualificationJson.write(machineDir.resolve("reference.json"), machine);
        QualificationJson.write(engineDir.resolve("cuda.json"), engine);
        Path harnessTool = root.resolve("tools/test-harness.txt");
        Files.createDirectories(harnessTool.getParent());
        Files.writeString(harnessTool, "protocol-v6 test harness\n", StandardCharsets.UTF_8);
        dev.talos.core.util.RuntimeArtifactIdentity.ClasspathSnapshot runtimeClasspath =
                dev.talos.core.util.RuntimeArtifactIdentity.currentClasspath();
        if (!runtimeClasspath.exact()) {
            throw new IllegalStateException("test runtime classpath identity is unavailable");
        }
        java.util.Map<String, String> harnessFiles = new java.util.TreeMap<>();
        harnessFiles.put("tools/test-harness.txt",
                QualificationScenarioBank.sha256(Files.readAllBytes(harnessTool)));
        for (java.util.Map.Entry<String, String> role
                : QualificationExecutionIdentityWriter.requiredHarnessRoles().entrySet()) {
            Path roleFile = root.resolve(role.getValue());
            Files.createDirectories(roleFile.getParent());
            Files.writeString(roleFile, "test role " + role.getKey() + "\n", StandardCharsets.UTF_8);
            harnessFiles.put(role.getValue(),
                    QualificationScenarioBank.sha256(Files.readAllBytes(roleFile)));
        }
        Path candidateJson = candidateDir.resolve("candidate.json");
        Path engineJson = engineDir.resolve("cuda.json");
        Path machineJson = machineDir.resolve("reference.json");
        String candidateJsonHash = QualificationScenarioBank.sha256(
                Files.readAllBytes(candidateJson));
        String calibrationHash = QualificationScenarioBank.sha256(Files.readAllBytes(rules));
        String engineJsonHash = QualificationScenarioBank.sha256(Files.readAllBytes(engineJson));
        String machineJsonHash = QualificationScenarioBank.sha256(Files.readAllBytes(machineJson));
        java.util.List<QualificationExecutionIdentityWriter.LedgerClasspathEntry> ledgerClasspath =
                dev.talos.core.util.RuntimeArtifactIdentity.currentClasspathEntryIdentities().stream()
                        .map(entry -> new QualificationExecutionIdentityWriter.LedgerClasspathEntry(
                                entry.ordinal(), entry.kind(), entry.logicalToken(),
                                entry.sizeBytes(), entry.sha256(), entry.fileCount()))
                        .toList();
        QualificationExecutionIdentityWriter.ExternalArtifactReceipt modelReceipt =
                QualificationExecutionIdentityWriter.captureExternalArtifact(
                        "MODEL_ARTIFACT", "model/model.gguf", "MODEL_GGUF", modelPath,
                        QualificationExecutionIdentityWriter::sha256File);
        QualificationExecutionIdentityWriter.ExternalArtifactReceipt engineReceipt =
                QualificationExecutionIdentityWriter.captureExternalArtifact(
                        "ENGINE_EXECUTABLE", "engine/llama-server.exe", "ENGINE_EXECUTABLE",
                        executablePath, QualificationExecutionIdentityWriter::sha256File);
        byte[] launcherBytes = "test generated launcher\n".getBytes(StandardCharsets.UTF_8);
        String launcherHash = QualificationScenarioBank.sha256(launcherBytes);
        QualificationExecutionIdentityWriter.ExecutionInputLedger ledger =
                new QualificationExecutionIdentityWriter.ExecutionInputLedger(
                        QualificationExecutionIdentityWriter.LEDGER_SCHEMA,
                        QualificationExecutionIdentityWriter.LEDGER_SCHEMA_VERSION,
                        QualificationProtocol.VERSION,
                        campaign.campaignId(),
                        campaign.talosCommit(),
                        QualificationExecutionIdentity.currentFinalizerImplementationSha256(),
                        new QualificationExecutionIdentityWriter.AggregateIdentity(
                                runtimeClasspath.sha256(), runtimeClasspath.sizeBytes(),
                                runtimeClasspath.entryCount()),
                        new QualificationExecutionIdentityWriter.AggregateIdentity(
                                runtimeClasspath.sha256(), runtimeClasspath.sizeBytes(),
                                runtimeClasspath.entryCount()),
                        java.util.List.of(
                                new QualificationExecutionIdentityWriter.LedgerArtifact(
                                        "BUILT_TALOS_JAR", "built/talos.jar",
                                        Files.size(talosJarPath), talosJarSha256),
                                new QualificationExecutionIdentityWriter.LedgerArtifact(
                                        "INSTALLED_TALOS_JAR", "installed/lib/talos.jar",
                                        Files.size(talosJarPath), talosJarSha256),
                                new QualificationExecutionIdentityWriter.LedgerArtifact(
                                        "CANDIDATE_MANIFEST", "candidates/qwen14/candidate.json",
                                        Files.size(candidateJson), candidateJsonHash),
                                new QualificationExecutionIdentityWriter.LedgerArtifact(
                                        "CALIBRATION_RULES",
                                        "candidates/qwen14/calibration-rules.json",
                                        Files.size(rules), calibrationHash),
                                new QualificationExecutionIdentityWriter.LedgerArtifact(
                                        "ENGINE_MANIFEST", "engines/cuda.json",
                                        Files.size(engineJson), engineJsonHash),
                                new QualificationExecutionIdentityWriter.LedgerArtifact(
                                        "MACHINE_MANIFEST", "machines/reference.json",
                                        Files.size(machineJson), machineJsonHash),
                                new QualificationExecutionIdentityWriter.LedgerArtifact(
                                        "WINDOWS_LAUNCHER", "installed/bin/talos.bat",
                                        launcherBytes.length, launcherHash),
                                new QualificationExecutionIdentityWriter.LedgerArtifact(
                                        "UNIX_LAUNCHER", "installed/bin/talos",
                                        launcherBytes.length, launcherHash)),
                        java.util.List.of(modelReceipt, engineReceipt),
                        ledgerClasspath,
                        QualificationExecutionIdentityWriter.requiredHarnessRoles());
        Path ledgerPath = root.resolve(QualificationExecutionIdentityWriter.LEDGER_FILE);
        QualificationJson.write(ledgerPath, ledger);
        harnessFiles.put(QualificationExecutionIdentityWriter.LEDGER_FILE,
                QualificationScenarioBank.sha256(Files.readAllBytes(ledgerPath)));
        QualificationExecutionIdentity.HarnessManifest harnessManifest =
                new QualificationExecutionIdentity.HarnessManifest(
                        QualificationExecutionIdentity.HARNESS_SCHEMA,
                        QualificationExecutionIdentity.HARNESS_SCHEMA_VERSION,
                        QualificationProtocol.VERSION,
                        campaign.talosCommit(),
                        QualificationExecutionIdentity.currentFinalizerImplementationSha256(),
                        runtimeClasspath.sha256(),
                        harnessFiles,
                        java.util.Map.of(),
                        java.util.Map.of());
        Path harnessManifestPath = root.resolve(QualificationExecutionIdentity.HARNESS_FILE);
        QualificationJson.write(harnessManifestPath, harnessManifest);
        QualificationJson.write(
                root.resolve(QualificationExecutionIdentity.RUNTIME_FILE),
                new QualificationExecutionIdentity.RuntimeManifest(
                        QualificationExecutionIdentity.RUNTIME_SCHEMA,
                        QualificationExecutionIdentity.RUNTIME_SCHEMA_VERSION,
                        campaign.campaignId(),
                        campaign.talosVersion(),
                        campaign.talosCommit(),
                        QualificationProtocol.VERSION,
                        "JAR",
                        Files.size(talosJarPath),
                        runtimeClasspath.sha256(),
                        runtimeClasspath.sizeBytes(),
                        runtimeClasspath.entryCount(),
                        talosJarSha256,
                        talosJarSha256,
                        candidateJsonHash,
                        calibrationHash,
                        engineJsonHash,
                        machineJsonHash,
                        QualificationScenarioBank.sha256(Files.readAllBytes(harnessManifestPath)),
                        "",
                        executableSha256,
                        modelSha256));
        QualificationJson.write(runDir.resolve("request.json"), new QualificationRecords.RequestRecord(
                QualificationRecords.REQUEST_SCHEMA, QualificationRecords.SCHEMA_VERSION,
                scenario.id(), scenario.prompt(), "r1", "TRUE_PTY", "talos"));
        if (catalog.protocolVersion() < QualificationProtocol.VERSION) {
            ObjectNode historicalScenario = (ObjectNode) QualificationJson.tree(scenario);
            historicalScenario.remove("responseOwnerContract");
            QualificationJson.write(runDir.resolve("scenario.json"), historicalScenario);
        } else {
            QualificationJson.write(runDir.resolve("scenario.json"), scenario);
        }
        boolean operatorInterruption = scenario.approvalBehavior()
                == QualificationProtocol.ApprovalBehavior.INTERRUPT_BEFORE_APPROVAL;
        String answerText = operatorInterruption ? "\n" : "4\n";
        boolean protocolV6 = catalog.protocolVersion() == QualificationProtocol.VERSION;
        List<String> declaredTools = scenario.expectedDecisions().stream()
                .flatMap(decision -> decision.allowedTools().stream())
                .distinct()
                .sorted()
                .toList();
        List<java.util.Map<String, Object>> traceEvents = protocolV6
                ? protocolV6TraceEvents(
                        scenario,
                        operatorInterruption,
                        declaredTools,
                        talosJarSha256,
                        Files.size(talosJarPath),
                        runtimeClasspath.sha256(),
                        runtimeClasspath.sizeBytes(),
                        runtimeClasspath.entryCount(),
                        campaign.talosVersion(),
                        campaign.talosCommit())
                : legacyTraceEvents(scenario, operatorInterruption);
        boolean mutationAuthority = scenario.category() == QualificationProtocol.ScenarioCategory.MUTATION
                || scenario.category() == QualificationProtocol.ScenarioCategory.REPAIR
                || scenario.category() == QualificationProtocol.ScenarioCategory.INTERRUPTION;
        String traceTaskType = mutationAuthority ? "WORKSPACE_MUTATION" : "READ_ONLY_QA";
        String traceObligation = mutationAuthority ? "MUTATING_TOOL_REQUIRED" : "DIRECT_ANSWER_ONLY";
        QualificationJson.write(runDir.resolve("trace.json"), java.util.Map.of(
                "schemaVersion", protocolV6
                        ? QualificationProviderAttemptLedger.TRACE_SCHEMA_VERSION : 2,
                "traceId", "trace-1",
                "model", java.util.Map.of("backend", "llama_cpp", "model", "qwen14-runtime"),
                "taskContract", java.util.Map.of(
                        "type", traceTaskType, "mutationAllowed", mutationAuthority,
                        "verificationRequired", false),
                "toolSurface", java.util.Map.of(
                        "nativeTools", declaredTools,
                        "promptTools", declaredTools,
                        "reason", "selected for protocol-v6 causal fixture"),
                "promptAudit", java.util.Map.of(
                        "taskType", traceTaskType,
                        "mutationAllowed", mutationAuthority,
                        "actionObligation", traceObligation,
                        "nativeTools", declaredTools,
                        "promptTools", declaredTools),
                "events", traceEvents,
                "verification", java.util.Map.of("status", "NOT_RUN", "summary", "Not applicable"),
                "outcome", java.util.Map.of(
                        "status", operatorInterruption ? "FAILED" : "COMPLETE",
                        "verificationStatus", "NOT_RUN",
                        "approvalStatus", "NONE", "mutationStatus", "NOT_REQUESTED",
                        "classification", scenario.expectedTerminalStates().getFirst())));
        QualificationJson.write(
                runDir.resolve("approvals.json"),
                new QualificationApprovalFinalizer.Document(List.of()));
        List<QualificationPtyEvidence.Event> captureEvents = new java.util.ArrayList<>();
        captureEvents.add(new QualificationPtyEvidence.Event(
                "2026-07-10T00:00:00Z", "input", "scenario-prompt"));
        if (operatorInterruption) {
            captureEvents.add(new QualificationPtyEvidence.Event(
                    "2026-07-10T00:00:01Z", "input", "user-interrupt"));
            captureEvents.add(new QualificationPtyEvidence.Event(
                    "2026-07-10T00:00:01Z",
                    "terminal-outcome",
                    "turn-aborted-by-operator"));
        }
        captureEvents.add(new QualificationPtyEvidence.Event(
                "2026-07-10T00:00:01Z",
                "output",
                "Outcome: " + scenario.expectedTerminalStates().getFirst()));
        captureEvents.add(new QualificationPtyEvidence.Event(
                "2026-07-10T00:00:02Z", "input", "last-trace"));
        int captureProtocol = catalog.protocolVersion();
        boolean extendedCapture = captureProtocol >= 2;
        boolean cleanupCapture = captureProtocol >= 3;
        QualificationPtyEvidence.ProcessCleanup processCleanup = cleanupCapture
                ? new QualificationPtyEvidence.ProcessCleanup(
                        QualificationProcessCleanup.Outcome.ALREADY_EXITED,
                        false,
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of())
                : null;
        QualificationPtyEvidence.Capture capture = new QualificationPtyEvidence.Capture(
                QualificationPtyEvidence.SCHEMA_NAME,
                catalog.protocolVersion(),
                "TEST_PTY",
                true,
                "2026-07-10T00:00:00Z",
                "2026-07-10T00:00:02Z",
                "talos",
                root.resolve("workspace").toString(),
                extendedCapture ? scenario.approvalBehavior().name() : null,
                extendedCapture
                        ? operatorInterruption
                                ? "turn-aborted-by-operator" : "fixture-complete"
                        : null,
                extendedCapture ? "" : null,
                processCleanup,
                captureEvents,
                "Outcome: " + scenario.expectedTerminalStates().getFirst());
        QualificationJson.write(runDir.resolve("transcript.raw.json"), capture);
        QualificationScenarioBank.FixtureResource fixture = catalog.fixtures().get(scenario.fixture());
        writeEvidenceFiles(runDir.resolve("before-files"), fixture.manifest().files());
        writeEvidenceFiles(runDir.resolve("after-files"), fixture.manifest().files());
        writeEvidenceFiles(runDir.resolve("final-files"), fixture.manifest().files());
        QualificationFixtureMaterializer.Snapshot before =
                QualificationFixtureMaterializer.snapshot(runDir.resolve("before-files"));
        QualificationFixtureMaterializer.Snapshot after =
                QualificationFixtureMaterializer.snapshot(runDir.resolve("after-files"));
        QualificationJson.write(runDir.resolve("before-hashes.json"), before.sha256());
        QualificationJson.write(runDir.resolve("after-hashes.json"), after.sha256());
        QualificationJson.write(runDir.resolve("timings.json"), java.util.Map.ofEntries(
                java.util.Map.entry("schemaName", "talos.modelQualification.timingEvidence"),
                java.util.Map.entry("schemaVersion", QualificationRecords.SCHEMA_VERSION),
                java.util.Map.entry("firstOutputMillis", 900),
                java.util.Map.entry("totalTurnWallTimeMillis", 1100),
                java.util.Map.entry("approvalWaitMillis", 0),
                java.util.Map.entry("promptEvalMillis", 100),
                java.util.Map.entry("generationMillis", 1000),
                java.util.Map.entry("promptTokens", 50),
                java.util.Map.entry("outputTokens", 32),
                java.util.Map.entry("promptTokensPerSecond", 500.0),
                java.util.Map.entry("generationTokensPerSecond", 32.0),
                java.util.Map.entry("watchdogRetries", 0),
                java.util.Map.entry("timedOut", false),
                java.util.Map.entry("outputCapHit", false),
                java.util.Map.entry("backendErrorCode", ""),
                java.util.Map.entry("malformedToolCalls", 0),
                java.util.Map.entry("toolEligibleGenerations", 0),
                java.util.Map.entry("repairAttempts", 0),
                java.util.Map.entry("measurementSource", "test fixture")));
        QualificationJson.write(runDir.resolve("resources.json"), java.util.Map.ofEntries(
                java.util.Map.entry("schemaName", "talos.modelQualification.resourceEvidence"),
                java.util.Map.entry("schemaVersion", QualificationRecords.SCHEMA_VERSION),
                java.util.Map.entry("processWorkingSetBytes", 8L << 30),
                java.util.Map.entry("systemRamBytes", 32L << 30),
                java.util.Map.entry("pagefileOrSwapBytes", 0),
                java.util.Map.entry("gpuVramUsedBytes", 0),
                java.util.Map.entry("gpuVramBytes", 16L << 30),
                java.util.Map.entry("measurementSource", "test fixture")));
        Files.writeString(
                runDir.resolve("resource-samples.csv"),
                QualificationResourceSamples.HEADER + "\n"
                        + "2026-07-10T00:00:01Z," + (32L << 30)
                        + ",0,0," + (8L << 30) + ",0\n",
                StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("answer.txt"), answerText, StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("transcript.txt"),
                "talos test transcript\nOutcome: "
                        + scenario.expectedTerminalStates().getFirst() + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("prompt-debug.txt"), "redacted prompt debug\n", StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("llama.log"), "prompt eval time\n", StandardCharsets.UTF_8);

        if (catalog.protocolVersion() == QualificationCausalFinalizer.WRITABLE_PROTOCOL_VERSION) {
            QualificationRunFinalizer.finalizeRun(root, runDir, scenario.id(), catalog);
        } else {
            QualificationJson.write(
                    runDir.resolve("canary.json"), java.util.Map.of("passed", true));
            com.fasterxml.jackson.databind.node.ObjectNode legacy =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            QualificationJson.tree(result);
            legacy.put("schemaVersion", QualificationRecords.LEGACY_RUN_SCHEMA_VERSION);
            legacy.remove(List.of(
                    "answerOrigin",
                    "responseOwnerContract",
                    "causalOwner",
                    "causalReasonCode",
                    "comparativeScoreEligible",
                    "causalLedger"));
            QualificationJson.write(runDir.resolve("result.json"), legacy);
        }

        return new QualificationTestPacket(root, runDir);
    }

    static QualificationScenarioBank.Catalog protocol6Catalog() {
        return QualificationScenarioBank.load(QualificationProtocol.VERSION);
    }

    static String enableSingleLeadingSystemPreflight(QualificationTestPacket packet)
            throws IOException {
        Path root = packet.root();
        QualificationRecords.CampaignManifest campaign = QualificationJson.read(
                root.resolve("campaign.json"), QualificationRecords.CampaignManifest.class);
        QualificationRecords.CandidateManifest candidate = QualificationJson.read(
                root.resolve("candidates/qwen14/candidate.json"),
                QualificationRecords.CandidateManifest.class);
        QualificationRecords.EngineFacts engine = QualificationJson.read(
                root.resolve("engines/cuda.json"), QualificationRecords.EngineFacts.class);
        Path evidence = root.resolve("preflight/direct-layout-proof.json");
        QualificationJson.write(evidence, java.util.Map.of(
                "requestLayout", "SINGLE_LEADING_SYSTEM",
                "providerAccepted", true));
        QualificationExecutionIdentity.PreflightManifest manifest =
                new QualificationExecutionIdentity.PreflightManifest(
                        QualificationExecutionIdentity.PREFLIGHT_SCHEMA,
                        QualificationExecutionIdentity.PREFLIGHT_SCHEMA_VERSION,
                        campaign.campaignId(),
                        candidate.alias(),
                        candidate.sha256(),
                        engine.sha256(),
                        "SINGLE_LEADING_SYSTEM",
                        java.util.Map.of(
                                "direct-layout-proof.json",
                                QualificationScenarioBank.sha256(Files.readAllBytes(evidence))));
        Path manifestPath = root.resolve(QualificationExecutionIdentity.PREFLIGHT_FILE);
        QualificationJson.write(manifestPath, manifest);
        String manifestHash = QualificationScenarioBank.sha256(Files.readAllBytes(manifestPath));

        Path rulesPath = root.resolve("candidates/qwen14/calibration-rules.json");
        com.fasterxml.jackson.databind.node.ObjectNode rules =
                (com.fasterxml.jackson.databind.node.ObjectNode) QualificationJson.readTree(rulesPath);
        rules.put("requestLayout", "SINGLE_LEADING_SYSTEM");
        rules.put("directPreflightEvidenceManifestSha256", manifestHash);
        QualificationJson.write(rulesPath, rules);

        Path campaignPath = root.resolve("campaign.json");
        com.fasterxml.jackson.databind.node.ObjectNode campaignJson =
                (com.fasterxml.jackson.databind.node.ObjectNode) QualificationJson.readTree(campaignPath);
        campaignJson.put("calibrationRulesSha256",
                QualificationScenarioBank.sha256(Files.readAllBytes(rulesPath)));
        QualificationJson.write(campaignPath, campaignJson);

        Path runtimePath = root.resolve(QualificationExecutionIdentity.RUNTIME_FILE);
        com.fasterxml.jackson.databind.node.ObjectNode runtime =
                (com.fasterxml.jackson.databind.node.ObjectNode) QualificationJson.readTree(runtimePath);
        runtime.put("calibrationRulesSha256",
                QualificationScenarioBank.sha256(Files.readAllBytes(rulesPath)));
        runtime.put("preflightManifestSha256", manifestHash);
        QualificationJson.write(runtimePath, runtime);
        refreshExecutionLedgerBinding(packet, "CALIBRATION_RULES", rulesPath);
        return manifestHash;
    }

    static void refreshCalibrationIdentity(QualificationTestPacket packet) throws IOException {
        Path rulesPath = packet.root().resolve("candidates/qwen14/calibration-rules.json");
        String hash = QualificationScenarioBank.sha256(Files.readAllBytes(rulesPath));
        Path campaignPath = packet.root().resolve("campaign.json");
        com.fasterxml.jackson.databind.node.ObjectNode campaign =
                (com.fasterxml.jackson.databind.node.ObjectNode) QualificationJson.readTree(campaignPath);
        campaign.put("calibrationRulesSha256", hash);
        QualificationJson.write(campaignPath, campaign);
        Path runtimePath = packet.root().resolve(QualificationExecutionIdentity.RUNTIME_FILE);
        com.fasterxml.jackson.databind.node.ObjectNode runtime =
                (com.fasterxml.jackson.databind.node.ObjectNode) QualificationJson.readTree(runtimePath);
        runtime.put("calibrationRulesSha256", hash);
        QualificationJson.write(runtimePath, runtime);
        refreshExecutionLedgerBinding(packet, "CALIBRATION_RULES", rulesPath);
    }

    static void refreshMachineIdentity(QualificationTestPacket packet) throws IOException {
        Path machinePath = packet.root().resolve("machines/reference.json");
        Path runtimePath = packet.root().resolve(QualificationExecutionIdentity.RUNTIME_FILE);
        com.fasterxml.jackson.databind.node.ObjectNode runtime =
                (com.fasterxml.jackson.databind.node.ObjectNode) QualificationJson.readTree(runtimePath);
        runtime.put("machineManifestSha256",
                QualificationScenarioBank.sha256(Files.readAllBytes(machinePath)));
        QualificationJson.write(runtimePath, runtime);
        refreshExecutionLedgerBinding(packet, "MACHINE_MANIFEST", machinePath);
    }

    private static void refreshExecutionLedgerBinding(
            QualificationTestPacket packet,
            String role,
            Path source) throws IOException {
        Path ledgerPath = packet.root().resolve(QualificationExecutionIdentityWriter.LEDGER_FILE);
        com.fasterxml.jackson.databind.node.ObjectNode ledger =
                (com.fasterxml.jackson.databind.node.ObjectNode) QualificationJson.readTree(ledgerPath);
        boolean found = false;
        for (com.fasterxml.jackson.databind.JsonNode artifact : ledger.withArray("artifacts")) {
            if (role.equals(artifact.path("role").asText())) {
                com.fasterxml.jackson.databind.node.ObjectNode object =
                        (com.fasterxml.jackson.databind.node.ObjectNode) artifact;
                object.put("sha256", QualificationScenarioBank.sha256(Files.readAllBytes(source)));
                object.put("sizeBytes", Files.size(source));
                found = true;
            }
        }
        if (!found) throw new IllegalStateException("test ledger role is missing: " + role);
        QualificationJson.write(ledgerPath, ledger);

        Path harnessPath = packet.root().resolve(QualificationExecutionIdentity.HARNESS_FILE);
        com.fasterxml.jackson.databind.node.ObjectNode harness =
                (com.fasterxml.jackson.databind.node.ObjectNode) QualificationJson.readTree(harnessPath);
        ((com.fasterxml.jackson.databind.node.ObjectNode) harness.path("files")).put(
                QualificationExecutionIdentityWriter.LEDGER_FILE,
                QualificationScenarioBank.sha256(Files.readAllBytes(ledgerPath)));
        QualificationJson.write(harnessPath, harness);

        Path runtimePath = packet.root().resolve(QualificationExecutionIdentity.RUNTIME_FILE);
        com.fasterxml.jackson.databind.node.ObjectNode runtime =
                (com.fasterxml.jackson.databind.node.ObjectNode) QualificationJson.readTree(runtimePath);
        runtime.put("harnessManifestSha256",
                QualificationScenarioBank.sha256(Files.readAllBytes(harnessPath)));
        QualificationJson.write(runtimePath, runtime);
    }

    private static List<java.util.Map<String, Object>> protocolV6TraceEvents(
            QualificationProtocol.Scenario scenario,
            boolean operatorInterruption,
            List<String> dispatchedNativeTools,
            String runtimeArtifactSha256,
            long runtimeArtifactSizeBytes,
            String runtimeClasspathSha256,
            long runtimeClasspathSizeBytes,
            int runtimeClasspathEntryCount,
            String runtimeVersion,
            String runtimeCommit) {
        String answer = operatorInterruption ? "" : "4";
        String answerHash = "sha256:" + QualificationScenarioBank.sha256(
                answer.getBytes(StandardCharsets.UTF_8));
        String status = operatorInterruption ? "FAILED" : "COMPLETE";
        String classification = operatorInterruption
                ? "LLM_ABORTED" : scenario.expectedTerminalStates().getFirst();
        java.util.Map<String, Object> traceStarted = traceEvent(
                "TRACE_STARTED",
                java.util.Map.of(
                        "runtimeArtifactKind", "JAR",
                        "runtimeArtifactSha256", runtimeArtifactSha256,
                        "runtimeArtifactSizeBytes", runtimeArtifactSizeBytes,
                        "runtimeClasspathSha256", runtimeClasspathSha256,
                        "runtimeClasspathSizeBytes", runtimeClasspathSizeBytes,
                        "runtimeClasspathEntryCount", runtimeClasspathEntryCount,
                        "runtimeVersion", runtimeVersion,
                        "runtimeCommit", runtimeCommit));
        if (operatorInterruption) {
            return List.of(
                    traceStarted,
                    traceEvent("DETERMINISTIC_ANSWER_SELECTED", java.util.Map.of(
                            "answerHash", answerHash,
                            "answerChars", 0,
                            "origin", "OPERATOR")),
                    traceEvent("TERMINAL_ANSWER_SELECTED", java.util.Map.of(
                            "answerHash", answerHash,
                            "answerChars", 0,
                            "origin", "OPERATOR")),
                    traceEvent("ROUTED_ANSWER_CAPTURED", java.util.Map.of(
                            "answerHash", answerHash,
                            "answerChars", 0)),
                    traceEvent("OUTCOME_RENDERED", java.util.Map.of(
                            "status", status,
                            "classification", classification)),
                    traceEvent("TRACE_COMPLETED", java.util.Map.of()));
        }

        String generationId = "trace-1-generation-1";
        String attemptId = generationId + "-attempt-1";
        String toolAuthorityMode = dispatchedNativeTools.isEmpty()
                ? "NONE" : "PROVIDER_NATIVE";
        java.util.Map<String, Object> generation = new java.util.LinkedHashMap<>(java.util.Map.of(
                "providerLedgerVersion", 1,
                "ledgerSequence", 1,
                "generationId", generationId,
                "source", "FOREGROUND_PROVIDER",
                "purpose", "TURN_RESPONSE"));
        generation.put("toolAuthorityMode", toolAuthorityMode);
        java.util.Map<String, Object> attemptStarted = new java.util.LinkedHashMap<>(
                attemptData(2, generationId, attemptId, false));
        attemptStarted.put("toolAuthorityMode", toolAuthorityMode);
        java.util.Map<String, Object> requestDispatched = new java.util.LinkedHashMap<>(
                attemptData(3, generationId, attemptId, true));
        requestDispatched.put("toolAuthorityMode", toolAuthorityMode);
        requestDispatched.put("providerBodyHash", "sha256:" + QualificationScenarioBank.sha256(
                "protocol-v6-fixture-provider-body".getBytes(StandardCharsets.UTF_8)));
        requestDispatched.put("dispatchedNativeTools", dispatchedNativeTools);
        requestDispatched.put(
                "toolAuthoritySurface",
                toolAuthoritySurface(toolAuthorityMode, dispatchedNativeTools));
        String transportOperationId = attemptId + "-transport-1";
        java.util.Map<String, Object> transportHandoff = transportEvent(
                1, generationId, attemptId, transportOperationId,
                "HANDOFF_ADMITTED", "NONE");
        java.util.Map<String, Object> transportSend = transportEvent(
                2, generationId, attemptId, transportOperationId,
                "SEND_API_ENTERED", "NONE");
        java.util.Map<String, Object> transportHeaders = transportEvent(
                3, generationId, attemptId, transportOperationId,
                "RESPONSE_HEADERS_OBSERVED", "NONE");
        java.util.Map<String, Object> transportBodyOpened = transportEvent(
                4, generationId, attemptId, transportOperationId,
                "RESPONSE_BODY_OPENED", "NONE");
        java.util.Map<String, Object> transportTerminal = transportEvent(
                5, generationId, attemptId, transportOperationId,
                "PROVIDER_TERMINAL_OBSERVED", "NONE");
        java.util.Map<String, Object> transportBodyCompleted = transportEvent(
                6, generationId, attemptId, transportOperationId,
                "RESPONSE_BODY_COMPLETED", "NONE");
        java.util.Map<String, Object> transportSettled = transportEvent(
                7, generationId, attemptId, transportOperationId,
                "SETTLED", "COMPLETED_PROVIDER_TERMINAL");
        java.util.Map<String, Object> response = new java.util.LinkedHashMap<>(
                attemptData(4, generationId, attemptId, true));
        response.put("toolAuthorityMode", toolAuthorityMode);
        response.put("assistantHash", answerHash);
        response.put("assistantChars", answer.length());
        response.put("nativeToolCallCount", 0);
        response.put("capturedNativeToolProposalCount", 0);
        response.put("nativeToolProposalsTruncated", false);
        response.put("textualToolCallPresent", false);
        response.put("completion", "PROVIDER_EOS");
        response.put("providerBacked", true);
        response.put("foreground", true);
        response.put("completedNormally", true);
        response.put("normalized", false);
        response.put("truncated", false);
        response.put("transportOperationId", transportOperationId);
        java.util.Map<String, Object> completed = new java.util.LinkedHashMap<>(generation);
        completed.put("ledgerSequence", 5);
        completed.put("selectedAttemptId", attemptId);
        completed.put("selectedAttemptOrdinal", 1);
        completed.put("completion", "PROVIDER_EOS");
        completed.put("assistantHash", answerHash);
        completed.put("providerTerminalEligible", true);
        completed.put("providerToolCallEligible", false);
        completed.put("transportOperationId", transportOperationId);
        return List.of(
                traceStarted,
                traceEvent("GENERATION_STARTED", generation),
                traceEvent("GENERATION_ATTEMPT_STARTED", attemptStarted),
                traceEvent("GENERATION_REQUEST_DISPATCHED", requestDispatched),
                traceEvent("PROVIDER_TRANSPORT_LIFECYCLE", transportHandoff),
                traceEvent("PROVIDER_TRANSPORT_LIFECYCLE", transportSend),
                traceEvent("PROVIDER_TRANSPORT_LIFECYCLE", transportHeaders),
                traceEvent("PROVIDER_TRANSPORT_LIFECYCLE", transportBodyOpened),
                traceEvent("PROVIDER_TRANSPORT_LIFECYCLE", transportTerminal),
                traceEvent("PROVIDER_TRANSPORT_LIFECYCLE", transportBodyCompleted),
                traceEvent("PROVIDER_TRANSPORT_LIFECYCLE", transportSettled),
                traceEvent("GENERATION_RESPONSE_CAPTURED", response),
                traceEvent("GENERATION_COMPLETED", completed),
                traceEvent("TERMINAL_ANSWER_SELECTED", java.util.Map.of(
                        "answerHash", answerHash,
                        "answerChars", answer.length(),
                        "origin", "MODEL_PROVIDER",
                        "sourceGenerationId", generationId,
                        "sourceAttemptId", attemptId)),
                traceEvent("ROUTED_ANSWER_CAPTURED", java.util.Map.of(
                        "answerHash", answerHash,
                        "answerChars", answer.length())),
                traceEvent("OUTCOME_RENDERED", java.util.Map.of(
                        "status", status,
                        "classification", classification)),
                traceEvent("TRACE_COMPLETED", java.util.Map.of()));
    }

    private static java.util.Map<String, Object> transportEvent(
            long sequence,
            String generationId,
            String attemptId,
            String transportOperationId,
            String transition,
            String settlement
    ) {
        java.util.Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("transportLedgerVersion", 1);
        event.put("transportSequence", sequence);
        event.put("generationId", generationId);
        event.put("attemptId", attemptId);
        event.put("attemptOrdinal", 1);
        event.put("transportOperationId", transportOperationId);
        event.put("transition", transition);
        event.put("observationMode", "OBSERVED");
        event.put("settlement", settlement);
        event.put("transportFailure", "NONE");
        event.put("cancellationState", "NOT_REQUESTED");
        event.put("cancellationReason", "NONE");
        event.put("wireDisposition", "HANDOFF_ADMITTED".equals(transition)
                ? "SEND_API_NOT_ENTERED" : "UNKNOWN");
        event.put("quarantineRequired", false);
        event.put("lateAfterGenerationTerminal", false);
        event.put("processBinding", "UNAVAILABLE");
        return event;
    }

    private static java.util.Map<String, Object> toolAuthoritySurface(
            String toolAuthorityMode,
            List<String> selectedTools
    ) {
        boolean bound = !"NONE".equals(toolAuthorityMode);
        List<String> hashes = bound
                ? selectedTools.stream()
                        .map(ProviderResponseCapture::toolNameHash)
                        .toList()
                : List.of();
        return java.util.Map.of(
                "schemaVersion", 1,
                "bound", bound,
                "toolChoiceMode", bound ? "AUTO" : "NONE",
                "namedToolNameHash", ProviderResponseCapture.toolNameHash(""),
                "selectedToolNameHashes", hashes,
                "authorizedToolNameHashes", hashes);
    }

    private static List<java.util.Map<String, Object>> legacyTraceEvents(
            QualificationProtocol.Scenario scenario,
            boolean operatorInterruption) {
        List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        if (!operatorInterruption) {
            events.add(traceEvent("MODEL_RESPONSE_RECEIVED", java.util.Map.of(
                    "assistantHash", "sha256:" + QualificationScenarioBank.sha256(
                            "4".getBytes(StandardCharsets.UTF_8)),
                    "assistantChars", 1)));
        }
        events.add(traceEvent("OUTCOME_RENDERED", java.util.Map.of(
                "status", operatorInterruption ? "FAILED" : "COMPLETE",
                "classification", operatorInterruption
                        ? "LLM_ABORTED" : scenario.expectedTerminalStates().getFirst())));
        events.add(traceEvent("TRACE_COMPLETED", java.util.Map.of()));
        return List.copyOf(events);
    }

    private static java.util.Map<String, Object> attemptData(
            int sequence,
            String generationId,
            String attemptId,
            boolean requestDispatched) {
        return java.util.Map.ofEntries(
                java.util.Map.entry("providerLedgerVersion", 1),
                java.util.Map.entry("ledgerSequence", sequence),
                java.util.Map.entry("generationId", generationId),
                java.util.Map.entry("source", "FOREGROUND_PROVIDER"),
                java.util.Map.entry("purpose", "TURN_RESPONSE"),
                java.util.Map.entry("attemptId", attemptId),
                java.util.Map.entry("attemptOrdinal", 1),
                java.util.Map.entry("attemptKind", "PRIMARY"),
                java.util.Map.entry("retryReason", "NONE"),
                java.util.Map.entry("requestDispatched", requestDispatched));
    }

    private static java.util.Map<String, Object> traceEvent(
            String type,
            java.util.Map<String, Object> data) {
        return java.util.Map.of(
                "type", type,
                "timestamp", "2026-07-10T00:00:01Z",
                "phase", "",
                "toolName", "",
                "data", data);
    }

    private static void writeEvidenceFiles(Path root, java.util.Map<String, String> files) throws IOException {
        for (java.util.Map.Entry<String, String> entry : files.entrySet()) {
            Path target = root.resolve(entry.getKey()).normalize();
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static QualificationRecords.CalibrationRules calibrationRules(
            Path modelPath,
            String modelSha256,
            String executableSha256,
            int protocolVersion) {
        return new QualificationRecords.CalibrationRules(
                QualificationRecords.CALIBRATION_SCHEMA, QualificationRecords.SCHEMA_VERSION,
                "standard", "STANDARD", "", "qwen14",
                "Qwen/Qwen2.5-Coder-14B-Instruct-GGUF", "b".repeat(40), "model.gguf",
                size(modelPath), modelSha256, "Apache-2.0", "official",
                "qwen2-dense", 14_700_000_000L, 14_700_000_000L,
                 "a".repeat(40), protocolVersion, "b9918", executableSha256,
                 "cuda", List.of("-m", modelPath.toString(), "--jinja", "-c", "16384", "--alias", "qwen14-runtime"),
                 List.of("-m", modelPath.toString(), "--jinja", "-c", "16384", "--alias", "qwen14-runtime", "-lv", "4"),
                 List.of(), "PRESERVE_AS_WRITTEN", "", "native",
                 "embedded-jinja", "standard-instruct", 16384,
                new QualificationRecords.SamplingPolicy(
                        "Talos near-greedy controls", 0.2, 0.8, 20,
                        "request fields omitted, pinned backend defaults apply",
                        0.8, 0.95, 40, 0.05, 1.0, "none",
                        "UNCONTROLLED_REPETITIONS", ""),
                new QualificationRecords.OutputCaps(512, 1024, 2048), 300,
                "uncontrolled-repetitions",
                new QualificationRecords.HardwareEnvelope("WINDOWS_CUDA_16GB", 32L << 30,
                        4L << 30, 16L << 30, 1L << 30, "measured layer offload required"),
                List.of("append placement variance"),
                List.of("malformed native tool call"),
                List.of("sampling seed is not controlled"),
                List.of("12 admission trials complete", "all trust gates pass"),
                List.of("any trust gate failure", "missing mandatory evidence"),
                List.of("PTY transcript", "trace JSON", "approval record", "final bytes"),
                List.of(new QualificationRecords.PermittedDeviation("repetition", "seed",
                        List.of("uncontrolled-r1", "uncontrolled-r2", "uncontrolled-r3"),
                        "results compare by declared repetition, not identical sampling")));
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static QualificationRecords.RunResult runResult(
            QualificationScenarioBank.Catalog catalog,
            QualificationProtocol.Scenario scenario,
            int trial,
            boolean passed,
            boolean correctDecisions) {
        return runResult(catalog, scenario, trial, passed, passed, correctDecisions);
    }

    static QualificationRecords.RunResult runResult(
            QualificationScenarioBank.Catalog catalog,
            QualificationProtocol.Scenario scenario,
            int trial,
            boolean terminalPassed,
            boolean requirementsPassed,
            boolean correctDecisions) {
        List<QualificationRecords.DecisionResult> decisions = scenario.expectedDecisions().stream()
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .map(decision -> new QualificationRecords.DecisionResult(decision.id(), correctDecisions))
                .toList();
        List<QualificationRecords.ToolCallResult> calls = scenario.expectedDecisions().stream()
                .filter(decision -> decision.kind() == QualificationProtocol.DecisionKind.REQUIRED_TOOL)
                .map(decision -> new QualificationRecords.ToolCallResult(
                        decision.allowedTools().isEmpty() ? "talos.read_file" : decision.allowedTools().getFirst(),
                        true, true, true, true))
                .toList();
        List<QualificationRecords.RequirementResult> requirements = scenario.atomicRequirements().stream()
                .map(requirement -> new QualificationRecords.RequirementResult(
                        requirement.id(), requirementsPassed))
                .toList();
        return new QualificationRecords.RunResult(
                QualificationRecords.RUN_SCHEMA, QualificationRecords.RUN_SCHEMA_VERSION,
                "test-campaign", catalog.sha256(), "qwen14", "reference", "cuda", "standard",
                scenario.id(), "r" + trial, trial, true, "", QualificationRecords.FailureClass.NONE,
                QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                QualificationRecords.CausalOwner.MODEL,
                QualificationRecords.CausalReason.OUTCOME_SATISFIED,
                true,
                List.of(),
                terminalPassed ? scenario.expectedTerminalStates().getFirst() : "FAILED",
                QualificationRecords.TrustGates.allPassed(), false, false, "", 0,
                0, 0, 0, 32, 50, 900, 0,
                1100, 100, 1000, 500.0, 32.0,
                8L << 30, 32L << 30, 0, 16L << 30, true,
                decisions, calls, requirements);
    }
}
