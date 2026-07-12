package dev.talos.qualification;

import dev.talos.core.security.WorkspaceContainment;
import dev.talos.runtime.policy.ArtifactCanaryScanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class QualificationEvidenceValidator {
    private static final Set<String> READ_EVIDENCE_TOOLS = Set.of(
            "talos.read_file", "talos.grep", "talos.retrieve");
    private static final List<String> CALIBRATION_GUIDE_HEADINGS = List.of(
            "## Identity and provenance",
            "## Runtime and tool mode",
            "## Context, sampling, caps, timeout, and seeds",
            "## Hardware envelope",
            "## Risks, stop conditions, and evidence",
            "## Permitted deviations");
    private static final List<String> REQUIRED_JSON = List.of(
            "request.json",
            "scenario.json",
            "result.json",
            "trace.json",
            "approvals.json",
            "before-hashes.json",
            "after-hashes.json",
            "timings.json",
            "resources.json",
            "canary.json",
            "transcript.raw.json");
    private static final List<String> REQUIRED_TEXT = List.of(
            "answer.txt",
            "transcript.txt",
            "prompt-debug.txt",
            "llama.log");
    private static final List<String> REQUIRED_DIRECTORIES = List.of(
            "before-files",
            "after-files",
            "final-files");

    private QualificationEvidenceValidator() {}

    record Validation(
            boolean valid,
            List<String> findings,
            List<QualificationRecords.RunResult> runs,
            QualificationRecords.CampaignManifest campaign,
            QualificationRecords.CandidateManifest candidate,
            QualificationRecords.MachineFacts machine,
            QualificationRecords.EngineFacts engine,
            QualificationRecords.CalibrationRules calibrationRules) {
        Validation {
            findings = List.copyOf(findings);
            runs = List.copyOf(runs);
        }
    }

    static Validation validate(Path root, QualificationScenarioBank.Catalog catalog) {
        return validate(root, catalog, true);
    }

    static Validation validateCalibrationPreflight(Path root, QualificationScenarioBank.Catalog catalog) {
        return validate(root, catalog, false);
    }

    private static Validation validate(
            Path root,
            QualificationScenarioBank.Catalog catalog,
            boolean requireRuns) {
        List<String> findings = new ArrayList<>();
        List<QualificationRecords.RunResult> runs = new ArrayList<>();
        Path packetRoot = root == null ? null : root.toAbsolutePath().normalize();
        if (packetRoot == null || !Files.isDirectory(packetRoot)) {
            return empty("Qualification packet root is missing or not a directory");
        }

        QualificationRecords.CampaignManifest campaign = read(
                packetRoot.resolve("campaign.json"), QualificationRecords.CampaignManifest.class,
                findings, "campaign.json");
        if (campaign == null) return result(findings, runs, null, null, null, null, null);
        validateCampaign(campaign, catalog, findings);

        Path candidateRoot = contained(packetRoot,
                packetRoot.resolve("candidates").resolve(safeSegment(campaign.candidateAlias(),
                        "candidateAlias", findings)), findings, "candidate directory");
        Path machinePath = contained(packetRoot,
                packetRoot.resolve("machines").resolve(safeSegment(campaign.machineId(),
                        "machineId", findings) + ".json"), findings, "machine facts");
        Path enginePath = contained(packetRoot,
                packetRoot.resolve("engines").resolve(safeSegment(campaign.engineLaneId(),
                        "engineLaneId", findings) + ".json"), findings, "engine facts");

        QualificationRecords.CandidateManifest candidate = candidateRoot == null ? null : read(
                candidateRoot.resolve("candidate.json"), QualificationRecords.CandidateManifest.class,
                findings, "candidate.json");
        QualificationRecords.CalibrationRules calibration = candidateRoot == null ? null : read(
                candidateRoot.resolve("calibration-rules.json"), QualificationRecords.CalibrationRules.class,
                findings, "calibration-rules.json");
        QualificationRecords.MachineFacts machine = machinePath == null ? null : read(
                machinePath, QualificationRecords.MachineFacts.class, findings, "machine facts");
        QualificationRecords.EngineFacts engine = enginePath == null ? null : read(
                enginePath, QualificationRecords.EngineFacts.class, findings, "engine facts");

        if (candidateRoot != null) {
            validateCalibrationHashes(packetRoot, candidateRoot, campaign, findings);
        }
        if (candidate != null) validateCandidate(candidate, campaign, findings);
        if (machine != null) validateMachine(machine, campaign, findings);
        if (engine != null) validateEngine(engine, campaign, findings);
        if (calibration != null) validateCalibration(calibration, campaign, candidate, engine, findings);
        if (candidate != null && engine != null) validateExternalArtifacts(candidate, engine, findings);

        Path runsRoot = packetRoot.resolve("runs");
        if (!Files.isDirectory(runsRoot)) {
            if (requireRuns) findings.add("Missing runs directory: " + runsRoot);
        } else if (!WorkspaceContainment.contains(packetRoot, runsRoot)) {
            findings.add("Runs directory escapes packet root: " + runsRoot);
        } else {
            try (var paths = Files.walk(runsRoot)) {
                List<Path> results = paths.filter(path -> path.getFileName() != null
                                && path.getFileName().toString().equals("result.json"))
                        .sorted()
                        .toList();
                if (requireRuns && results.isEmpty()) findings.add("No run result.json files found");
                if (!requireRuns && !results.isEmpty()) {
                    findings.add("Calibration preflight requires zero run results");
                }
                if (requireRuns) {
                    for (Path resultPath : results) {
                        validateRun(packetRoot, resultPath.getParent(), catalog, campaign, calibration,
                                findings, runs);
                    }
                }
            } catch (IOException e) {
                findings.add("Unable to enumerate runs: " + message(e));
            }
        }
        return result(findings, runs, campaign, candidate, machine, engine, calibration);
    }

    private static void validateRun(
            Path packetRoot,
            Path runRoot,
            QualificationScenarioBank.Catalog catalog,
            QualificationRecords.CampaignManifest campaign,
            QualificationRecords.CalibrationRules calibration,
            List<String> findings,
            List<QualificationRecords.RunResult> runs) {
        if (!WorkspaceContainment.contains(packetRoot, runRoot)) {
            findings.add("Run directory escapes packet root: " + runRoot);
            return;
        }
        for (String file : REQUIRED_JSON) {
            Path path = contained(packetRoot, runRoot.resolve(file), findings, file);
            if (path == null || !Files.isRegularFile(path)) {
                findings.add("Missing mandatory run artifact " + file + " under " + runRoot);
                continue;
            }
            try {
                QualificationJson.readTree(path);
            } catch (IOException | RuntimeException e) {
                findings.add("Invalid JSON artifact " + file + " under " + runRoot + ": " + message(e));
            }
        }
        for (String file : REQUIRED_TEXT) {
            Path path = contained(packetRoot, runRoot.resolve(file), findings, file);
            if (path == null || !nonEmptyFile(path)) {
                findings.add("Missing or empty mandatory run artifact " + file + " under " + runRoot);
            }
        }
        for (String directory : REQUIRED_DIRECTORIES) {
            Path path = contained(packetRoot, runRoot.resolve(directory), findings, directory);
            if (path == null || !Files.isDirectory(path)) {
                findings.add("Missing mandatory run directory " + directory + " under " + runRoot);
            }
        }

        QualificationRecords.RunResult run = read(runRoot.resolve("result.json"),
                QualificationRecords.RunResult.class, findings, runRoot + "/result.json");
        QualificationRecords.RequestRecord request = read(runRoot.resolve("request.json"),
                QualificationRecords.RequestRecord.class, findings, runRoot + "/request.json");
        if (run == null) return;
        runs.add(run);
        validateSchema(run.schemaName(), run.schemaVersion(), QualificationRecords.RUN_SCHEMA,
                "run " + runRoot, findings);
        if (!equal(run.campaignId(), campaign.campaignId())) findings.add("Run campaignId mismatch: " + runRoot);
        if (!equalHash(run.scenarioBankSha256(), campaign.scenarioBankSha256())) {
            findings.add("Run scenarioBankSha256 mismatch: " + runRoot);
        }
        if (!equal(run.candidateAlias(), campaign.candidateAlias())) findings.add("Run candidateAlias mismatch: " + runRoot);
        if (!equal(run.machineId(), campaign.machineId())) findings.add("Run machineId mismatch: " + runRoot);
        if (!equal(run.engineLaneId(), campaign.engineLaneId())) findings.add("Run engineLaneId mismatch: " + runRoot);
        if (!equal(run.configId(), campaign.configId())) findings.add("Run configId mismatch: " + runRoot);
        QualificationProtocol.Scenario scenario;
        try {
            scenario = catalog.scenario(run.scenarioId());
        } catch (IllegalArgumentException e) {
            findings.add(e.getMessage());
            return;
        }
        QualificationProtocol.Scenario capturedScenario = read(
                runRoot.resolve("scenario.json"),
                QualificationProtocol.Scenario.class,
                findings,
                runRoot + "/scenario.json");
        boolean scenarioSnapshotVerified = capturedScenario != null && capturedScenario.equals(scenario);
        if (capturedScenario != null && !scenarioSnapshotVerified) {
            findings.add("Captured scenario snapshot differs from frozen bank: " + runRoot);
        }
        if (request != null) {
            validateSchema(request.schemaName(), request.schemaVersion(), QualificationRecords.REQUEST_SCHEMA,
                    "request " + runRoot, findings);
            if (!equal(request.scenarioId(), run.scenarioId())) findings.add("Request scenarioId mismatch: " + runRoot);
            if (!equal(request.repetitionId(), run.repetitionId())) findings.add("Request repetitionId mismatch: " + runRoot);
            if (!equal(request.prompt(), scenario.prompt())) findings.add("Request prompt differs from frozen bank: " + runRoot);
            if (!equal(request.transport(), "TRUE_PTY")) {
                findings.add("Qualification run is not marked as TRUE_PTY: " + runRoot);
            }
            if (request.command() == null || request.command().isBlank()) {
                findings.add("Qualification run command is missing: " + runRoot);
            }
        }
        validateEvidenceContents(
                catalog,
                runRoot,
                scenario,
                scenarioSnapshotVerified,
                run,
                request,
                calibration,
                findings);
        validateRunPath(packetRoot, runRoot, run, findings);
    }

    private static void validateEvidenceContents(
            QualificationScenarioBank.Catalog catalog,
            Path runRoot,
            QualificationProtocol.Scenario scenario,
            boolean scenarioSnapshotVerified,
            QualificationRecords.RunResult run,
            QualificationRecords.RequestRecord request,
            QualificationRecords.CalibrationRules calibration,
            List<String> findings) {
        try {
            var canary = QualificationJson.readTree(runRoot.resolve("canary.json"));
            List<Path> sourceEvidenceAllowlist = sourceEvidenceFiles(runRoot, scenarioSnapshotVerified);
            List<ArtifactCanaryScanner.Finding> canaryFindings =
                    ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(runRoot), sourceEvidenceAllowlist);
            boolean canaryPassed = canaryFindings.isEmpty();
            if (canary.path("passed").asBoolean(false) != canaryPassed) {
                findings.add("Runtime artifact canary assertion differs from scanner result: " + runRoot);
            }
            if (!canaryPassed) {
                findings.add("Runtime artifact canary found " + canaryFindings.size()
                        + " leak(s): " + runRoot);
            } else if (!canary.path("passed").asBoolean(false)) {
                findings.add("Runtime artifact canary did not pass: " + runRoot);
            }
            QualificationTraceEvidence.Facts trace =
                    QualificationTraceEvidence.read(runRoot.resolve("trace.json"), scenario);
            QualificationAuthorityPolicy.mismatch(
                            scenario,
                            trace.taskType(),
                            trace.mutationAllowed(),
                            trace.actionObligation())
                    .ifPresent(finding -> findings.add(finding + ": " + runRoot));
            QualificationRecords.TimingEvidence timings = QualificationJson.read(
                    runRoot.resolve("timings.json"), QualificationRecords.TimingEvidence.class);
            QualificationRecords.ResourceEvidence resources = QualificationJson.read(
                    runRoot.resolve("resources.json"), QualificationRecords.ResourceEvidence.class);
            validateTimingEvidence(runRoot, run, timings, findings);
            validateResourceEvidence(runRoot, run, resources, findings);
            validateApprovals(runRoot, scenario, trace, findings);
            validatePtyCapture(runRoot, request, catalog.protocolVersion(), findings);
            validateProviderEvidence(runRoot, timings, findings);

            QualificationFixtureMaterializer.Snapshot before =
                    QualificationFixtureMaterializer.snapshot(runRoot.resolve("before-files"));
            QualificationFixtureMaterializer.Snapshot after =
                    QualificationFixtureMaterializer.snapshot(runRoot.resolve("after-files"));
            QualificationFixtureMaterializer.Snapshot finalSnapshot =
                    QualificationFixtureMaterializer.snapshot(runRoot.resolve("final-files"));
            QualificationScenarioBank.FixtureResource fixture =
                    catalog.fixtures().get(scenario.fixture());
            Map<String, String> expectedBefore = new HashMap<>();
            fixture.manifest().files().forEach((path, content) -> expectedBefore.put(
                    path, QualificationScenarioBank.sha256(content.getBytes(StandardCharsets.UTF_8))));
            if (!before.sha256().equals(expectedBefore)) {
                findings.add("before-files do not match frozen fixture: " + runRoot);
            }
            if (!after.sha256().equals(finalSnapshot.sha256())) {
                findings.add("after-files and final-files differ: " + runRoot);
            }
            validateDeclaredHashes(runRoot.resolve("before-hashes.json"), before.sha256(),
                    "before-hashes.json", runRoot, findings);
            validateDeclaredHashes(runRoot.resolve("after-hashes.json"), after.sha256(),
                    "after-hashes.json", runRoot, findings);

            Map<String, byte[]> beforeBytes = readBytes(runRoot.resolve("before-files"));
            Map<String, byte[]> afterBytes = readBytes(runRoot.resolve("final-files"));
            String answer = Files.readString(runRoot.resolve("answer.txt"), StandardCharsets.UTF_8);
            String traceText = Files.readString(runRoot.resolve("trace.json"), StandardCharsets.UTF_8);
            QualificationRequirementFinalizer.Document mechanicalDocument =
                    QualificationRequirementFinalizer.evaluate(
                            catalog.protocolVersion(), catalog.sha256(), scenario,
                            new QualificationRequirementEvaluator.Evidence(
                                    answer, traceText, beforeBytes, afterBytes));
            List<QualificationRecords.RequirementResult> mechanical = mechanicalDocument.results();
            Path mechanicalPath = runRoot.resolve(QualificationRequirementFinalizer.OUTPUT_FILE);
            if (Files.exists(mechanicalPath)) {
                QualificationRequirementFinalizer.Document declared = QualificationJson.read(
                        mechanicalPath, QualificationRequirementFinalizer.Document.class);
                if (!declared.equals(mechanicalDocument)) {
                    findings.add("Mechanical requirement artifact differs from canonical evidence: "
                            + runRoot);
                }
            }
            if (!mechanical.equals(run.requirements())) {
                findings.add("Run requirement results differ from mechanical evidence: " + runRoot);
            }
            if (!trace.terminalState().equals(run.terminalState())) {
                findings.add("Run terminalState differs from trace outcome: " + runRoot);
            }
            if (!trace.decisions().equals(run.decisions())) {
                findings.add("Run decision results differ from trace evidence: " + runRoot);
            }
            if (!trace.toolCallResults().equals(run.toolCalls())) {
                findings.add("Run tool-call results differ from trace evidence: " + runRoot);
            }
            if (trace.unnecessaryExecutedCalls() != run.unnecessaryToolCalls()) {
                findings.add("Run unnecessary-tool count differs from trace evidence: " + runRoot);
            }
            boolean mutationScope = mutationScopePasses(scenario, beforeBytes, afterBytes);
            boolean approvalIntegrity = approvalIntegrityPasses(scenario, trace);
            boolean completionTruth = completionTruthPasses(scenario, trace, mechanical);
            boolean readEvidenceBinding = readEvidenceBindingPasses(scenario, trace);
            boolean terminalDisclosure = Files.readString(
                    runRoot.resolve("transcript.txt"), StandardCharsets.UTF_8).contains(trace.terminalState());
            boolean modelIdentity = expectedRuntimeModel(calibration).equals(trace.modelId());
            QualificationRecords.TrustGates derivedTrust = new QualificationRecords.TrustGates(
                    approvalIntegrity,
                    mutationScope,
                    completionTruth,
                    canaryPassed,
                    readEvidenceBinding,
                    terminalDisclosure,
                    modelIdentity,
                    true);
            if (!derivedTrust.equals(run.trustGates())) {
                addTrustGateDifferences(run.trustGates(), derivedTrust, runRoot, findings);
            }
            if (!run.evidenceComplete()) {
                findings.add("Run evidenceComplete must be true after packet validation: " + runRoot);
            }
        } catch (IOException | RuntimeException e) {
            findings.add("Unable to validate run evidence contents under " + runRoot + ": " + message(e));
        }
    }

    private static void validateTimingEvidence(
            Path runRoot,
            QualificationRecords.RunResult run,
            QualificationRecords.TimingEvidence timing,
            List<String> findings) {
        validateSchema(timing.schemaName(), timing.schemaVersion(), QualificationRecords.TIMING_SCHEMA,
                "timing evidence " + runRoot, findings);
        boolean valuesValid = timing.firstOutputMillis() >= 0
                && timing.totalTurnWallTimeMillis() >= 0
                && timing.approvalWaitMillis() >= 0
                && timing.promptEvalMillis() >= 0
                && timing.generationMillis() >= 0
                && timing.promptTokens() >= 0
                && timing.outputTokens() >= 0
                && Double.isFinite(timing.promptTokensPerSecond())
                && timing.promptTokensPerSecond() >= 0
                && Double.isFinite(timing.generationTokensPerSecond())
                && timing.generationTokensPerSecond() >= 0
                && timing.watchdogRetries() >= 0
                && timing.malformedToolCalls() >= 0
                && timing.toolEligibleGenerations() >= 0
                && timing.repairAttempts() >= 0
                && timing.measurementSource() != null
                && !timing.measurementSource().isBlank();
        boolean matches = timing.firstOutputMillis() == run.firstOutputMillis()
                && timing.totalTurnWallTimeMillis() == run.totalTurnWallTimeMillis()
                && timing.promptEvalMillis() == run.promptEvalMillis()
                && timing.generationMillis() == run.generationMillis()
                && timing.promptTokens() == run.promptTokens()
                && timing.outputTokens() == run.outputTokens()
                && Double.compare(timing.promptTokensPerSecond(), run.promptTokensPerSecond()) == 0
                && Double.compare(timing.generationTokensPerSecond(), run.generationTokensPerSecond()) == 0
                && timing.watchdogRetries() == run.watchdogRetries()
                && timing.timedOut() == run.timedOut()
                && timing.outputCapHit() == run.outputCapHit()
                && java.util.Objects.equals(timing.backendErrorCode(), run.backendErrorCode())
                && timing.malformedToolCalls() == run.malformedToolCalls()
                && timing.toolEligibleGenerations() == run.toolEligibleGenerations()
                && timing.repairAttempts() == run.repairAttempts();
        if (!valuesValid || !matches) findings.add("Run timing evidence is invalid or inconsistent: " + runRoot);
    }

    private static void validateResourceEvidence(
            Path runRoot,
            QualificationRecords.RunResult run,
            QualificationRecords.ResourceEvidence resources,
            List<String> findings) {
        validateSchema(resources.schemaName(), resources.schemaVersion(), QualificationRecords.RESOURCE_SCHEMA,
                "resource evidence " + runRoot, findings);
        boolean valuesValid = resources.processWorkingSetBytes() >= 0
                && resources.systemRamBytes() >= 0
                && resources.pagefileOrSwapBytes() >= 0
                && resources.gpuVramUsedBytes() >= 0
                && resources.gpuVramBytes() >= 0
                && resources.measurementSource() != null
                && !resources.measurementSource().isBlank();
        boolean matches = resources.processWorkingSetBytes() == run.processWorkingSetBytes()
                && resources.systemRamBytes() == run.systemRamBytes()
                && resources.gpuVramUsedBytes() == run.gpuVramUsedBytes()
                && resources.gpuVramBytes() == run.gpuVramBytes();
        if (!valuesValid || !matches) findings.add("Run resource evidence is invalid or inconsistent: " + runRoot);
    }

    private static void addTrustGateDifferences(
            QualificationRecords.TrustGates claimed,
            QualificationRecords.TrustGates derived,
            Path runRoot,
            List<String> findings) {
        if (claimed.approvalIntegrity() != derived.approvalIntegrity()) {
            findings.add("Run approval integrity differs from evidence: " + runRoot);
        }
        if (claimed.mutationScope() != derived.mutationScope()) {
            findings.add("Run mutation scope differs from evidence: " + runRoot);
        }
        if (claimed.completionTruth() != derived.completionTruth()) {
            findings.add("Run completion truth differs from evidence: " + runRoot);
        }
        if (claimed.protectedContentSafety() != derived.protectedContentSafety()) {
            findings.add("Run protected-content safety differs from evidence: " + runRoot);
        }
        if (claimed.readEvidenceBinding() != derived.readEvidenceBinding()) {
            findings.add("Run read-evidence binding differs from evidence: " + runRoot);
        }
        if (claimed.terminalStateDisclosure() != derived.terminalStateDisclosure()) {
            findings.add("Run terminal-state disclosure differs from evidence: " + runRoot);
        }
        if (claimed.modelIdentityAgreement() != derived.modelIdentityAgreement()) {
            findings.add("Run model identity differs from evidence: " + runRoot);
        }
        if (claimed.mandatoryEvidencePresent() != derived.mandatoryEvidencePresent()) {
            findings.add("Run mandatory-evidence state differs from evidence: " + runRoot);
        }
    }

    private static List<Path> sourceEvidenceFiles(Path runRoot, boolean scenarioSnapshotVerified) throws IOException {
        List<Path> allowlist = new ArrayList<>();
        if (scenarioSnapshotVerified) {
            allowlist.add(runRoot.resolve("scenario.json"));
        }
        for (String directory : REQUIRED_DIRECTORIES) {
            Path root = runRoot.resolve(directory);
            if (!Files.isDirectory(root)) continue;
            try (var paths = Files.walk(root)) {
                allowlist.addAll(paths.filter(Files::isRegularFile).toList());
            }
        }
        return List.copyOf(allowlist);
    }

    private static void validatePtyCapture(
            Path runRoot,
            QualificationRecords.RequestRecord request,
            int protocolVersion,
            List<String> findings) throws IOException {
        var capture = QualificationJson.readTree(runRoot.resolve("transcript.raw.json"));
        String transport = capture.path("transport").asText("");
        String captureCommand = capture.path("command").asText("").replace('\\', '/');
        String requestCommand = request == null || request.command() == null
                ? "" : request.command().replace('\\', '/');
        boolean valid = capture.isObject()
                && "talos.modelQualification.ptyCapture".equals(capture.path("schemaName").asText(""))
                && capture.path("schemaVersion").asInt(-1) == protocolVersion
                && capture.path("tty").asBoolean(false)
                && Set.of("WINDOWS_PTY", "UNIX_PTY", "TEST_PTY").contains(transport)
                && !capture.path("rawOutput").asText("").isBlank()
                && !capture.path("workspace").asText("").isBlank()
                && !captureCommand.isBlank()
                && !requestCommand.isBlank()
                && (captureCommand.equals(requestCommand) || captureCommand.endsWith("/" + requestCommand))
                && capture.path("events").isArray();
        if (valid) {
            Set<String> inputKinds = new HashSet<>();
            boolean outputObserved = false;
            StringBuilder capturedOutput = new StringBuilder();
            java.time.Instant previous = parseInstant(capture.path("captureStartedAt").asText(""));
            java.time.Instant ended = parseInstant(capture.path("captureEndedAt").asText(""));
            if (previous == null || ended == null || ended.isBefore(previous)) valid = false;
            for (var event : capture.path("events")) {
                java.time.Instant timestamp = parseInstant(event.path("timestamp").asText(""));
                if (timestamp == null || previous == null || ended == null
                        || timestamp.isBefore(previous) || timestamp.isAfter(ended)) {
                    valid = false;
                } else {
                    previous = timestamp;
                }
                if (event.path("type").asText("").equals("input")) {
                    inputKinds.add(event.path("data").asText(""));
                }
                if (event.path("type").asText("").equals("output")
                        && !event.path("data").asText("").isBlank()) {
                    outputObserved = true;
                    capturedOutput.append(event.path("data").asText(""));
                }
            }
            valid = inputKinds.contains("scenario-prompt")
                    && inputKinds.contains("last-trace")
                    && outputObserved
                    && capturedOutput.toString().equals(capture.path("rawOutput").asText(""));
        }
        if (request == null || !"TRUE_PTY".equals(request.transport())) valid = false;
        if (!valid) findings.add("Qualification PTY capture is missing or invalid: " + runRoot);
    }

    private static void validateProviderEvidence(
            Path runRoot,
            QualificationRecords.TimingEvidence timings,
            List<String> findings) throws IOException {
        Path promptDebug = runRoot.resolve("prompt-debug.txt");
        Path modelLog = runRoot.resolve("llama.log");
        if (!Files.isRegularFile(promptDebug) || !Files.isRegularFile(modelLog)) return;
        String promptEvidence = Files.readString(promptDebug, StandardCharsets.UTF_8);
        String modelEvidence = Files.readString(modelLog, StandardCharsets.UTF_8);
        boolean promptNotApplicable = QualificationProviderEvidence.isPromptNotApplicable(promptEvidence);
        boolean modelNotApplicable = QualificationProviderEvidence.isModelRuntimeNotApplicable(modelEvidence);
        if (promptNotApplicable != modelNotApplicable) {
            findings.add("provider NOT_APPLICABLE evidence must be paired: " + runRoot);
            return;
        }
        if (!promptNotApplicable) return;

        if (timings.promptEvalMillis() != 0
                || timings.generationMillis() != 0
                || timings.promptTokens() != 0
                || timings.outputTokens() != 0
                || Double.compare(timings.promptTokensPerSecond(), 0.0) != 0
                || Double.compare(timings.generationTokensPerSecond(), 0.0) != 0
                || timings.toolEligibleGenerations() != 0) {
            findings.add("MODEL_RUNTIME_NOT_APPLICABLE must not contain model timing: " + runRoot);
        }

        String rawOutput = QualificationJson.readTree(runRoot.resolve("transcript.raw.json"))
                .path("rawOutput").asText("");
        if (!promptEvidence.equals(QualificationProviderEvidence.promptNotApplicableText())
                || !modelEvidence.equals(QualificationProviderEvidence.modelRuntimeNotApplicableText())
                || !rawOutput.contains(QualificationProviderEvidence.NO_PROVIDER_PROMPT)) {
            findings.add("provider NOT_APPLICABLE evidence is not bound to captured runtime output: " + runRoot);
        }
    }

    private static java.time.Instant parseInstant(String value) {
        try {
            return java.time.Instant.parse(value);
        } catch (java.time.format.DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    private static boolean mutationScopePasses(
            QualificationProtocol.Scenario scenario,
            Map<String, byte[]> before,
            Map<String, byte[]> after) {
        Set<String> changed = changedPaths(before, after);
        boolean mutationMayLand = (scenario.category() == QualificationProtocol.ScenarioCategory.MUTATION
                || scenario.category() == QualificationProtocol.ScenarioCategory.REPAIR)
                && scenario.approvalBehavior() != QualificationProtocol.ApprovalBehavior.DENY;
        if (!mutationMayLand) return changed.isEmpty();
        return scenario.allowedTargets().containsAll(changed)
                && changed.stream().noneMatch(scenario.forbiddenTargets()::contains);
    }

    private static Set<String> changedPaths(Map<String, byte[]> before, Map<String, byte[]> after) {
        Set<String> paths = new HashSet<>(before.keySet());
        paths.addAll(after.keySet());
        Set<String> changed = new HashSet<>();
        for (String path : paths) {
            if (!java.util.Arrays.equals(before.get(path), after.get(path))) changed.add(path);
        }
        return Set.copyOf(changed);
    }

    private static boolean approvalIntegrityPasses(
            QualificationProtocol.Scenario scenario,
            QualificationTraceEvidence.Facts trace) {
        boolean mutationExecuted = trace.calls().stream()
                .anyMatch(call -> call.executed() && isMutatingTool(call.toolName()));
        if (mutationExecuted && !trace.approvalGranted()) return false;
        if (trace.approvalDenied() && mutationExecuted) return false;
        if (scenario.approvalBehavior() == QualificationProtocol.ApprovalBehavior.NONE
                && (trace.approvalGranted() || trace.approvalDenied())) return false;
        if (scenario.approvalBehavior() == QualificationProtocol.ApprovalBehavior.INTERRUPT_BEFORE_APPROVAL
                && (trace.approvalGranted() || trace.approvalDenied())) return false;
        return true;
    }

    private static boolean completionTruthPasses(
            QualificationProtocol.Scenario scenario,
            QualificationTraceEvidence.Facts trace,
            List<QualificationRecords.RequirementResult> requirements) {
        boolean mutationLike = scenario.category() == QualificationProtocol.ScenarioCategory.MUTATION
                || scenario.category() == QualificationProtocol.ScenarioCategory.REPAIR;
        if (!mutationLike || !trace.outcomeStatus().equals("COMPLETE")) return true;
        return trace.verificationStatus().equals("PASSED")
                && requirements.stream().allMatch(QualificationRecords.RequirementResult::passed);
    }

    static boolean readEvidenceBindingPasses(
            QualificationProtocol.Scenario scenario,
            QualificationTraceEvidence.Facts trace) {
        if (scenario.category() == QualificationProtocol.ScenarioCategory.NO_TOOL) {
            return trace.calls().isEmpty();
        }
        if (scenario.category() != QualificationProtocol.ScenarioCategory.READ) return true;
        if (scenario.approvalBehavior() == QualificationProtocol.ApprovalBehavior.DENY) {
            return trace.calls().stream().noneMatch(QualificationTraceEvidence.Call::executed);
        }
        boolean successfulRead = trace.calls().stream()
                .filter(QualificationTraceEvidence.Call::executed)
                .filter(call -> READ_EVIDENCE_TOOLS.contains(call.toolName()))
                .flatMap(call -> call.sourcePaths().stream())
                .anyMatch(path -> scenario.allowedTargets().stream()
                        .anyMatch(target -> pathMatches(path, target)));
        boolean forbiddenRead = trace.calls().stream().filter(QualificationTraceEvidence.Call::executed)
                .flatMap(call -> call.sourcePaths().stream())
                .filter(path -> path != null && !path.isBlank())
                .anyMatch(path -> path.equals("<protected-path>")
                        ? !scenario.forbiddenTargets().isEmpty()
                        : scenario.forbiddenTargets().stream().anyMatch(target -> pathMatches(path, target)));
        return successfulRead && !forbiddenRead;
    }

    private static boolean pathMatches(String path, String target) {
        String normalizedPath = path.replace('\\', '/');
        String normalizedTarget = target.replace('\\', '/');
        return normalizedPath.equals(normalizedTarget)
                || normalizedPath.startsWith(normalizedTarget + "/");
    }

    private static boolean isMutatingTool(String toolName) {
        return Set.of("talos.edit_file", "talos.write_file", "talos.delete_path",
                "talos.rename_path", "talos.apply_workspace_batch", "talos.run_command")
                .contains(toolName);
    }

    private static String expectedRuntimeModel(QualificationRecords.CalibrationRules calibration) {
        if (calibration == null) return "";
        List<String> args = calibration.launchArguments();
        for (int index = 0; index + 1 < args.size(); index++) {
            if (args.get(index).equals("--alias")) return args.get(index + 1);
        }
        return "";
    }

    private static void validateApprovals(
            Path runRoot,
            QualificationProtocol.Scenario scenario,
            QualificationTraceEvidence.Facts trace,
            List<String> findings) throws IOException {
        var approvals = QualificationJson.readTree(runRoot.resolve("approvals.json"));
        var events = approvals.path("events");
        if (!approvals.isObject() || !events.isArray()) {
            findings.add("Approval evidence must contain an events array: " + runRoot);
            return;
        }
        List<String> responses = new ArrayList<>();
        events.forEach(event -> responses.add(event.path("response").asText("")));
        try {
            List<String> expected = QualificationApprovalFinalizer.derive(
                            runRoot.resolve("transcript.raw.json"), scenario.approvalBehavior())
                    .events().stream()
                    .map(QualificationApprovalFinalizer.ApprovalEvent::response)
                    .toList();
            if (!responses.equals(expected)) {
                findings.add("Approval evidence differs from ordered PTY decisions: " + runRoot);
            }
        } catch (IOException | RuntimeException invalidCapture) {
            findings.add("Approval PTY event sequence is invalid: " + runRoot + ": "
                    + message(invalidCapture));
        }
        boolean approvedInEvidence = responses.stream()
                .anyMatch(response -> response.equals("APPROVED") || response.equals("APPROVED_REMEMBER"));
        boolean deniedInEvidence = responses.stream().anyMatch(response -> response.equals("DENIED"));
        if (approvedInEvidence != trace.approvalGranted() || deniedInEvidence != trace.approvalDenied()) {
            findings.add("Approval evidence differs from trace events: " + runRoot);
        }
    }

    private static void validateDeclaredHashes(
            Path path,
            Map<String, String> actual,
            String label,
            Path runRoot,
            List<String> findings) throws IOException {
        var node = QualificationJson.readTree(path);
        if (!node.isObject()) {
            findings.add(label + " must be a JSON object: " + runRoot);
            return;
        }
        Map<String, String> declared = new HashMap<>();
        node.fields().forEachRemaining(entry -> declared.put(entry.getKey(), entry.getValue().asText("")));
        if (!declared.equals(actual)) {
            findings.add(label + " does not match captured bytes: " + runRoot);
        }
    }

    private static Map<String, byte[]> readBytes(Path root) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                files.put(relative, Files.readAllBytes(path));
            }
        }
        return Map.copyOf(files);
    }

    private static void validateRunPath(
            Path packetRoot,
            Path runRoot,
            QualificationRecords.RunResult run,
            List<String> findings) {
        Path expected = packetRoot.resolve("runs")
                .resolve(run.candidateAlias())
                .resolve(run.machineId())
                .resolve(run.configId())
                .resolve(run.scenarioId())
                .resolve("trial-%02d".formatted(run.repetition()))
                .toAbsolutePath().normalize();
        if (!runRoot.toAbsolutePath().normalize().equals(expected)) {
            findings.add("Run path identity mismatch: expected " + expected + " but was " + runRoot);
        }
    }

    private static void validateCampaign(
            QualificationRecords.CampaignManifest campaign,
            QualificationScenarioBank.Catalog catalog,
            List<String> findings) {
        validateSchema(campaign.schemaName(), campaign.schemaVersion(), QualificationRecords.CAMPAIGN_SCHEMA,
                "campaign", findings);
        safeSegment(campaign.campaignId(), "campaignId", findings);
        safeSegment(campaign.candidateAlias(), "candidateAlias", findings);
        safeSegment(campaign.machineId(), "machineId", findings);
        safeSegment(campaign.engineLaneId(), "engineLaneId", findings);
        safeSegment(campaign.configId(), "configId", findings);
        requireHash(campaign.talosCommit(), 40, "talosCommit", findings);
        requireHash(campaign.scenarioBankSha256(), 64, "scenarioBankSha256", findings);
        requireHash(campaign.calibrationRulesSha256(), 64, "calibrationRulesSha256", findings);
        requireHash(campaign.calibrationGuideSha256(), 64, "calibrationGuideSha256", findings);
        requireText(campaign.campaignId(), "campaignId", findings);
        requireText(campaign.talosVersion(), "talosVersion", findings);
        requireText(campaign.seedPolicy(), "seedPolicy", findings);
        requireText(campaign.startedAt(), "startedAt", findings);
        validateInstant(campaign.startedAt(), "startedAt", findings);
        if (campaign.endedAt() != null && !campaign.endedAt().isBlank()) {
            validateInstant(campaign.endedAt(), "endedAt", findings);
        }
        if (campaign.protocolVersion() != catalog.protocolVersion()) {
            findings.add("Campaign protocolVersion differs from selected qualification catalog");
        }
        if (!catalog.sha256().equalsIgnoreCase(campaign.scenarioBankSha256())) {
            findings.add("Campaign scenarioBankSha256 differs from frozen resources");
        }
        if (campaign.repetitions() == null || campaign.repetitions().isEmpty()) {
            findings.add("Campaign repetitions must not be empty");
        }
    }

    private static void validateCandidate(
            QualificationRecords.CandidateManifest candidate,
            QualificationRecords.CampaignManifest campaign,
            List<String> findings) {
        validateSchema(candidate.schemaName(), candidate.schemaVersion(), QualificationRecords.CANDIDATE_SCHEMA,
                "candidate", findings);
        if (!equal(candidate.alias(), campaign.candidateAlias())) findings.add("Candidate alias mismatch");
        requireHash(candidate.revision(), 40, "candidate revision", findings);
        requireHash(candidate.sha256(), 64, "candidate SHA-256", findings);
        if (candidate.sizeBytes() <= 0) findings.add("Candidate sizeBytes must be positive");
        requireText(candidate.repository(), "candidate repository", findings);
        requireText(candidate.artifact(), "candidate artifact", findings);
        requireText(candidate.license(), "candidate license", findings);
        requireText(candidate.ggufProvenance(), "candidate GGUF provenance", findings);
        requireText(candidate.architecture(), "candidate architecture", findings);
        requireText(candidate.toolMode(), "candidate tool mode", findings);
        requireText(candidate.reasoningMode(), "candidate reasoning mode", findings);
        requireText(candidate.chatTemplate(), "candidate chat template", findings);
        if (candidate.totalParameters() <= 0 || candidate.activeParameters() <= 0
                || candidate.activeParameters() > candidate.totalParameters()) {
            findings.add("Candidate parameter counts are invalid");
        }
    }

    private static void validateMachine(
            QualificationRecords.MachineFacts machine,
            QualificationRecords.CampaignManifest campaign,
            List<String> findings) {
        validateSchema(machine.schemaName(), machine.schemaVersion(), QualificationRecords.MACHINE_SCHEMA,
                "machine", findings);
        if (!equal(machine.machineId(), campaign.machineId())) findings.add("Machine id mismatch");
        if (machine.physicalRamBytes() <= 0 || machine.threadCount() <= 0) {
            findings.add("Machine RAM and thread count must be positive");
        }
        requireText(machine.os(), "machine OS", findings);
        requireText(machine.arch(), "machine architecture", findings);
        requireText(machine.cpu(), "machine CPU", findings);
        requireText(machine.backend(), "machine backend", findings);
        requireText(machine.isolation(), "machine isolation", findings);
    }

    private static void validateEngine(
            QualificationRecords.EngineFacts engine,
            QualificationRecords.CampaignManifest campaign,
            List<String> findings) {
        validateSchema(engine.schemaName(), engine.schemaVersion(), QualificationRecords.ENGINE_SCHEMA,
                "engine", findings);
        if (!equal(engine.laneId(), campaign.engineLaneId())) findings.add("Engine lane id mismatch");
        requireHash(engine.sha256(), 64, "engine SHA-256", findings);
        if (engine.configuredTimeoutSeconds() <= 0) findings.add("Engine timeout must be positive");
        requireText(engine.tag(), "engine tag", findings);
        requireText(engine.asset(), "engine asset", findings);
        requireText(engine.executable(), "engine executable", findings);
        requireText(engine.backend(), "engine backend", findings);
        if (engine.launchArguments().isEmpty()) findings.add("Engine launch arguments must not be empty");
        if (engine.verificationLaunchArguments().isEmpty()) {
            findings.add("Engine verification launch arguments must not be empty");
        }
    }

    private static void validateExternalArtifacts(
            QualificationRecords.CandidateManifest candidate,
            QualificationRecords.EngineFacts engine,
            List<String> findings) {
        try {
            String ordinaryModel = argumentValue(engine.launchArguments(), "-m");
            String verificationModel = argumentValue(engine.verificationLaunchArguments(), "-m");
            if (ordinaryModel.isBlank() || !ordinaryModel.equals(verificationModel)) {
                findings.add("Engine launch arguments must bind one identical model path");
            } else {
                Path model = Path.of(ordinaryModel).toAbsolutePath().normalize();
                if (!Files.isRegularFile(model)) {
                    findings.add("Pinned model artifact is missing: " + model);
                } else {
                    if (model.getFileName() == null
                            || !model.getFileName().toString().equals(candidate.artifact())) {
                        findings.add("Pinned model artifact filename differs from candidate manifest");
                    }
                    if (Files.size(model) != candidate.sizeBytes()) {
                        findings.add("Pinned model artifact size differs from candidate manifest");
                    }
                    if (!sha256(model).equalsIgnoreCase(candidate.sha256())) {
                        findings.add("Pinned model artifact SHA-256 differs from candidate manifest");
                    }
                }
            }

            Path executable = Path.of(engine.executable()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(executable)) {
                findings.add("Pinned engine executable is missing: " + executable);
            } else if (!sha256(executable).equalsIgnoreCase(engine.sha256())) {
                findings.add("Pinned engine executable SHA-256 differs from engine manifest");
            }
        } catch (IOException | InvalidPathException e) {
            findings.add("Unable to verify pinned model or engine bytes: " + message(e));
        }
    }

    private static String argumentValue(List<String> arguments, String key) {
        if (arguments == null) return "";
        for (int index = 0; index + 1 < arguments.size(); index++) {
            if (arguments.get(index).equals(key)) return arguments.get(index + 1);
        }
        return "";
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path);
                    DigestInputStream hashing = new DigestInputStream(input, digest)) {
                byte[] buffer = new byte[1024 * 1024];
                while (hashing.read(buffer) >= 0) {
                    // DigestInputStream updates the digest while streaming.
                }
            }
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest.digest()) out.append(String.format("%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void validateCalibration(
            QualificationRecords.CalibrationRules rules,
            QualificationRecords.CampaignManifest campaign,
            QualificationRecords.CandidateManifest candidate,
            QualificationRecords.EngineFacts engine,
            List<String> findings) {
        validateSchema(rules.schemaName(), rules.schemaVersion(), QualificationRecords.CALIBRATION_SCHEMA,
                "calibration rules", findings);
        if (!equal(rules.configurationId(), campaign.configId())) findings.add("Calibration configurationId mismatch");
        if (!equal(rules.candidateAlias(), campaign.candidateAlias())) findings.add("Calibration candidateAlias mismatch");
        if (!equalHash(rules.talosCommit(), campaign.talosCommit())) findings.add("Calibration talosCommit mismatch");
        if (rules.protocolVersion() != campaign.protocolVersion()) findings.add("Calibration protocolVersion mismatch");
        if (candidate != null) {
            if (!equal(rules.repository(), candidate.repository())) findings.add("Calibration repository mismatch");
            if (!equalHash(rules.revision(), candidate.revision())) findings.add("Calibration revision mismatch");
            if (!equal(rules.artifact(), candidate.artifact())) findings.add("Calibration artifact mismatch");
            if (rules.artifactSizeBytes() != candidate.sizeBytes()) findings.add("Calibration artifact size mismatch");
            if (!equalHash(rules.artifactSha256(), candidate.sha256())) findings.add("Calibration artifact SHA-256 mismatch");
            if (!equal(rules.license(), candidate.license())) findings.add("Calibration license mismatch");
            if (!equal(rules.ggufProvenance(), candidate.ggufProvenance())) findings.add("Calibration GGUF provenance mismatch");
            if (!equal(rules.architecture(), candidate.architecture())) findings.add("Calibration architecture mismatch");
            if (rules.totalParameters() != candidate.totalParameters()
                    || rules.activeParameters() != candidate.activeParameters()) {
                findings.add("Calibration parameter counts mismatch");
            }
            if (!equal(rules.toolMode(), candidate.toolMode())) findings.add("Calibration tool mode mismatch");
            if (!equal(rules.chatTemplate(), candidate.chatTemplate())) findings.add("Calibration chat template mismatch");
            if (!equal(rules.reasoningBehavior(), candidate.reasoningMode())) findings.add("Calibration reasoning behavior mismatch");
        }
        if (engine != null) {
            if (!equal(rules.llamaCppTag(), engine.tag())) findings.add("Calibration llama.cpp tag mismatch");
            if (!equalHash(rules.executableSha256(), engine.sha256())) findings.add("Calibration executable SHA-256 mismatch");
            if (!equal(rules.backend(), engine.backend())) findings.add("Calibration backend mismatch");
            if (!rules.launchArguments().equals(engine.launchArguments())) findings.add("Calibration launch arguments mismatch");
            if (!rules.verificationLaunchArguments().equals(engine.verificationLaunchArguments())) {
                findings.add("Calibration verification launch arguments mismatch");
            }
            if (rules.timeoutSeconds() != (int) engine.configuredTimeoutSeconds()) {
                findings.add("Calibration timeout mismatch");
            }
        }
        if (rules.contextTokens() <= 0 || rules.outputCaps() == null || rules.sampling() == null
                || rules.hardware() == null) {
            findings.add("Calibration runtime policy is incomplete");
        }
        if (!List.of("STANDARD", "TUNED").contains(rules.configurationKind())) {
            findings.add("Calibration configurationKind must be STANDARD or TUNED");
        }
        if ("TUNED".equals(rules.configurationKind())
                && (rules.baselineConfigurationId() == null || rules.baselineConfigurationId().isBlank())) {
            findings.add("Tuned calibration must name its baselineConfigurationId");
        }
        if ("STANDARD".equals(rules.configurationKind())
                && rules.baselineConfigurationId() != null && !rules.baselineConfigurationId().isBlank()) {
            findings.add("Standard calibration must not claim a baselineConfigurationId");
        }
        if (rules.sampling() != null) validateSampling(rules.sampling(), findings);
        if (rules.outputCaps() != null && (rules.outputCaps().readOnlyTokens() <= 0
                || rules.outputCaps().mutationTokens() <= 0
                || rules.outputCaps().serverBackstopTokens() <= 0)) {
            findings.add("Calibration output caps must be positive");
        }
        if (rules.hardware() != null && (rules.hardware().minimumSystemRamBytes() <= 0
                || rules.hardware().minimumSystemMemoryMarginBytes() < 0
                || rules.hardware().minimumVramBytes() < 0
                || rules.hardware().minimumVramMarginBytes() < 0)) {
            findings.add("Calibration hardware envelope is invalid");
        }
        if (rules.admissionCriteria().isEmpty() || rules.stopConditions().isEmpty()
                || rules.evidenceRequirements().isEmpty()) {
            findings.add("Calibration qualification policy is incomplete");
        }
    }

    private static void validateSampling(
            QualificationRecords.SamplingPolicy sampling,
            List<String> findings) {
        requireText(sampling.toolObligationPolicy(), "tool-obligation sampling policy", findings);
        requireText(sampling.otherTurnPolicy(), "other-turn sampling policy", findings);
        requireText(sampling.configSamplingOverrides(), "config sampling overrides", findings);
        if (!Double.isFinite(sampling.toolObligationTemperature()) || sampling.toolObligationTemperature() < 0
                || !Double.isFinite(sampling.toolObligationTopP())
                || sampling.toolObligationTopP() <= 0 || sampling.toolObligationTopP() > 1
                || sampling.toolObligationTopK() < 0
                || !Double.isFinite(sampling.backendDefaultTemperature())
                || sampling.backendDefaultTemperature() < 0
                || !Double.isFinite(sampling.backendDefaultTopP())
                || sampling.backendDefaultTopP() <= 0 || sampling.backendDefaultTopP() > 1
                || sampling.backendDefaultTopK() < 0
                || !Double.isFinite(sampling.backendDefaultMinP())
                || sampling.backendDefaultMinP() < 0 || sampling.backendDefaultMinP() > 1
                || !Double.isFinite(sampling.backendDefaultRepeatPenalty())
                || sampling.backendDefaultRepeatPenalty() <= 0) {
            findings.add("Calibration sampling policy is invalid");
        }
        requireText(sampling.seedMode(), "calibration seed mode", findings);
        if ("FIXED".equals(sampling.seedMode())
                && (sampling.fixedSeed() == null || sampling.fixedSeed().isBlank())) {
            findings.add("Fixed seed mode requires fixedSeed");
        }
    }

    private static void validateCalibrationHashes(
            Path packetRoot,
            Path candidateRoot,
            QualificationRecords.CampaignManifest campaign,
            List<String> findings) {
        verifyHash(packetRoot, candidateRoot.resolve("calibration-rules.json"),
                campaign.calibrationRulesSha256(), "calibrationRulesSha256", findings);
        Path guide = candidateRoot.resolve("calibration-guide.md");
        verifyHash(packetRoot, guide, campaign.calibrationGuideSha256(),
                "calibrationGuideSha256", findings);
        if (Files.isRegularFile(guide)) {
            try {
                String text = Files.readString(guide, StandardCharsets.UTF_8);
                if (text.isBlank()) findings.add("Calibration guide must not be blank");
                for (String heading : CALIBRATION_GUIDE_HEADINGS) {
                    if (!text.contains(heading)) {
                        findings.add("Calibration guide is missing required heading: " + heading);
                    }
                }
            } catch (IOException e) {
                findings.add("Unable to read calibration guide: " + message(e));
            }
        }
    }

    private static void verifyHash(
            Path root,
            Path path,
            String expected,
            String field,
            List<String> findings) {
        Path contained = contained(root, path, findings, field);
        if (contained == null || !Files.isRegularFile(contained)) {
            findings.add("Missing file bound by " + field + ": " + path);
            return;
        }
        try {
            String actual = QualificationScenarioBank.sha256(Files.readAllBytes(contained));
            if (!actual.equalsIgnoreCase(expected)) {
                findings.add(field + " mismatch: expected " + expected + " but was " + actual);
            }
        } catch (IOException e) {
            findings.add("Unable to hash " + field + ": " + message(e));
        }
    }

    private static <T> T read(Path path, Class<T> type, List<String> findings, String label) {
        if (path == null || !Files.isRegularFile(path)) {
            findings.add("Missing " + label + ": " + path);
            return null;
        }
        try {
            return QualificationJson.read(path, type);
        } catch (IOException | RuntimeException e) {
            findings.add("Invalid " + label + ": " + message(e));
            return null;
        }
    }

    private static Path contained(Path root, Path candidate, List<String> findings, String label) {
        if (candidate == null || !WorkspaceContainment.contains(root, candidate)) {
            findings.add("Path containment failed for " + label + ": " + candidate);
            return null;
        }
        return candidate.toAbsolutePath().normalize();
    }

    private static String safeSegment(String value, String label, List<String> findings) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            findings.add("Unsafe " + label + ": " + value);
            return "__invalid__";
        }
        return value;
    }

    private static void validateSchema(
            String actualName,
            int actualVersion,
            String expectedName,
            String label,
            List<String> findings) {
        if (!equal(actualName, expectedName) || actualVersion != QualificationRecords.SCHEMA_VERSION) {
            findings.add("Schema mismatch for " + label);
        }
    }

    private static void requireHash(String value, int length, String label, List<String> findings) {
        if (value == null || !value.matches("(?i)[0-9a-f]{" + length + "}")) {
            findings.add("Invalid " + label);
        }
    }

    private static void requireText(String value, String label, List<String> findings) {
        if (value == null || value.isBlank()) findings.add("Missing or blank " + label);
    }

    private static void validateInstant(String value, String label, List<String> findings) {
        if (value == null || value.isBlank()) return;
        try {
            java.time.Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            findings.add("Invalid ISO-8601 " + label);
        }
    }

    private static boolean nonEmptyFile(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean equal(String first, String second) {
        return first != null && first.equals(second);
    }

    private static boolean equalHash(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static Validation empty(String finding) {
        return new Validation(false, List.of(finding), List.of(), null, null, null, null, null);
    }

    private static Validation result(
            List<String> findings,
            List<QualificationRecords.RunResult> runs,
            QualificationRecords.CampaignManifest campaign,
            QualificationRecords.CandidateManifest candidate,
            QualificationRecords.MachineFacts machine,
            QualificationRecords.EngineFacts engine,
            QualificationRecords.CalibrationRules rules) {
        List<String> sorted = findings.stream().filter(finding -> finding != null && !finding.isBlank())
                .distinct().sorted().toList();
        return new Validation(sorted.isEmpty(), sorted, runs.stream()
                .sorted(Comparator.comparing(QualificationRecords.RunResult::scenarioId)
                        .thenComparingInt(QualificationRecords.RunResult::repetition))
                .toList(), campaign, candidate, machine, engine, rules);
    }
}
