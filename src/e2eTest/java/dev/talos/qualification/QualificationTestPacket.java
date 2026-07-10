package dev.talos.qualification;

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
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
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
        Files.writeString(modelPath, "test model bytes\n", StandardCharsets.UTF_8);
        Files.writeString(executablePath, "test engine bytes\n", StandardCharsets.UTF_8);
        String modelSha256 = QualificationScenarioBank.sha256(Files.readAllBytes(modelPath));
        String executableSha256 = QualificationScenarioBank.sha256(Files.readAllBytes(executablePath));

        Path rules = candidateDir.resolve("calibration-rules.json");
        Path guide = candidateDir.resolve("calibration-guide.md");
        QualificationJson.write(rules, calibrationRules(modelPath, modelSha256, executableSha256));
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
                "test-campaign", "0.10.8", "a".repeat(40), QualificationProtocol.VERSION,
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
        QualificationJson.write(runDir.resolve("request.json"), new QualificationRecords.RequestRecord(
                QualificationRecords.REQUEST_SCHEMA, QualificationRecords.SCHEMA_VERSION,
                scenario.id(), scenario.prompt(), "r1", "TRUE_PTY", "talos"));
        QualificationJson.write(runDir.resolve("result.json"), result);
        QualificationJson.write(runDir.resolve("trace.json"), java.util.Map.of(
                "schemaVersion", 2,
                "traceId", "trace-1",
                "model", java.util.Map.of("backend", "llama_cpp", "model", "qwen14-runtime"),
                "taskContract", java.util.Map.of(
                        "type", "READ_ONLY_QA", "mutationAllowed", false, "verificationRequired", false),
                "promptAudit", java.util.Map.of(
                        "taskType", "READ_ONLY_QA",
                        "mutationAllowed", false,
                        "actionObligation", "DIRECT_ANSWER_ONLY"),
                "events", List.of(java.util.Map.of(
                        "type", "OUTCOME_RENDERED", "timestamp", "2026-07-10T00:00:01Z",
                        "phase", "", "toolName", "",
                        "data", java.util.Map.of("status", "COMPLETE", "classification", "READ_ONLY_ANSWERED"))),
                "verification", java.util.Map.of("status", "NOT_RUN", "summary", "Not applicable"),
                "outcome", java.util.Map.of(
                        "status", "COMPLETE", "verificationStatus", "NOT_RUN",
                        "approvalStatus", "NONE", "mutationStatus", "NOT_REQUESTED",
                        "classification", "READ_ONLY_ANSWERED")));
        QualificationJson.write(runDir.resolve("approvals.json"), java.util.Map.of("events", List.of()));
        QualificationJson.write(runDir.resolve("transcript.raw.json"), java.util.Map.of(
                "schemaName", "talos.modelQualification.ptyCapture",
                "schemaVersion", QualificationProtocol.VERSION,
                "transport", "TEST_PTY",
                "tty", true,
                "captureStartedAt", "2026-07-10T00:00:00Z",
                "captureEndedAt", "2026-07-10T00:00:02Z",
                "command", "talos",
                "workspace", root.resolve("workspace").toString(),
                "events", List.of(
                        java.util.Map.of("timestamp", "2026-07-10T00:00:00Z", "type", "input", "data", "scenario-prompt"),
                        java.util.Map.of("timestamp", "2026-07-10T00:00:01Z", "type", "output", "data", "Outcome: READ_ONLY_ANSWERED"),
                        java.util.Map.of("timestamp", "2026-07-10T00:00:02Z", "type", "input", "data", "last-trace")),
                "rawOutput", "Outcome: READ_ONLY_ANSWERED"));
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
                java.util.Map.entry("toolEligibleGenerations", 1),
                java.util.Map.entry("repairAttempts", 0),
                java.util.Map.entry("measurementSource", "test fixture")));
        QualificationJson.write(runDir.resolve("resources.json"), java.util.Map.ofEntries(
                java.util.Map.entry("schemaName", "talos.modelQualification.resourceEvidence"),
                java.util.Map.entry("schemaVersion", QualificationRecords.SCHEMA_VERSION),
                java.util.Map.entry("processWorkingSetBytes", 8L << 30),
                java.util.Map.entry("systemRamBytes", 32L << 30),
                java.util.Map.entry("pagefileOrSwapBytes", 0),
                java.util.Map.entry("gpuVramUsedBytes", 0),
                java.util.Map.entry("gpuVramBytes", 8L << 30),
                java.util.Map.entry("measurementSource", "test fixture")));
        QualificationJson.write(runDir.resolve("canary.json"), java.util.Map.of("passed", true));
        Files.writeString(runDir.resolve("answer.txt"), "4\n", StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("transcript.txt"),
                "talos test transcript\nOutcome: READ_ONLY_ANSWERED\n", StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("prompt-debug.txt"), "redacted prompt debug\n", StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("llama.log"), "prompt eval time\n", StandardCharsets.UTF_8);

        return new QualificationTestPacket(root, runDir);
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
            String executableSha256) {
        return new QualificationRecords.CalibrationRules(
                QualificationRecords.CALIBRATION_SCHEMA, QualificationRecords.SCHEMA_VERSION,
                "standard", "STANDARD", "", "qwen14",
                "Qwen/Qwen2.5-Coder-14B-Instruct-GGUF", "b".repeat(40), "model.gguf",
                size(modelPath), modelSha256, "Apache-2.0", "official",
                "qwen2-dense", 14_700_000_000L, 14_700_000_000L,
                "a".repeat(40), QualificationProtocol.VERSION, "b9918", executableSha256,
                "cuda", List.of("-m", modelPath.toString(), "--jinja", "-c", "16384", "--alias", "qwen14-runtime"),
                List.of("-m", modelPath.toString(), "--jinja", "-c", "16384", "--alias", "qwen14-runtime", "-lv", "4"), "native",
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
                QualificationRecords.RUN_SCHEMA, QualificationRecords.SCHEMA_VERSION,
                "test-campaign", catalog.sha256(), "qwen14", "reference", "cuda", "standard",
                scenario.id(), "r" + trial, trial, true, "", QualificationRecords.FailureClass.NONE,
                terminalPassed ? scenario.expectedTerminalStates().getFirst() : "FAILED",
                QualificationRecords.TrustGates.allPassed(), false, false, "", 0,
                1, 0, 0, 32, 50, 900, 0,
                1100, 100, 1000, 500.0, 32.0,
                8L << 30, 32L << 30, 0, 8L << 30, true,
                decisions, calls, requirements);
    }
}
