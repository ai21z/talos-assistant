package dev.talos.qualification;

import dev.talos.core.security.WorkspaceContainment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class QualificationEvidenceValidator {
    private static final List<String> CALIBRATION_GUIDE_HEADINGS = List.of(
            "## Identity and provenance",
            "## Runtime and tool mode",
            "## Context, sampling, caps, timeout, and seeds",
            "## Hardware envelope",
            "## Risks, stop conditions, and evidence",
            "## Permitted deviations");
    private static final List<String> REQUIRED_JSON = List.of(
            "request.json",
            "result.json",
            "trace.json",
            "approvals.json",
            "before-hashes.json",
            "after-hashes.json",
            "timings.json",
            "resources.json",
            "canary.json");
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
                        validateRun(packetRoot, resultPath.getParent(), catalog, campaign, findings, runs);
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
        validateEvidenceContents(catalog, runRoot, scenario, run, findings);
        validateRunPath(packetRoot, runRoot, run, findings);
    }

    private static void validateEvidenceContents(
            QualificationScenarioBank.Catalog catalog,
            Path runRoot,
            QualificationProtocol.Scenario scenario,
            QualificationRecords.RunResult run,
            List<String> findings) {
        try {
            var canary = QualificationJson.readTree(runRoot.resolve("canary.json"));
            if (!canary.path("passed").asBoolean(false)) {
                findings.add("Runtime artifact canary did not pass: " + runRoot);
            }
            var trace = QualificationJson.readTree(runRoot.resolve("trace.json"));
            if (!trace.isObject() || trace.isEmpty()) {
                findings.add("Trace JSON is empty: " + runRoot);
            }
            validateApprovals(runRoot, scenario, findings);

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
            List<QualificationRecords.RequirementResult> mechanical =
                    QualificationRequirementEvaluator.evaluate(scenario,
                            new QualificationRequirementEvaluator.Evidence(
                                    answer, traceText, beforeBytes, afterBytes));
            if (!mechanical.equals(run.requirements())) {
                findings.add("Run requirement results differ from mechanical evidence: " + runRoot);
            }
        } catch (IOException | RuntimeException e) {
            findings.add("Unable to validate run evidence contents under " + runRoot + ": " + message(e));
        }
    }

    private static void validateApprovals(
            Path runRoot,
            QualificationProtocol.Scenario scenario,
            List<String> findings) throws IOException {
        var approvals = QualificationJson.readTree(runRoot.resolve("approvals.json"));
        var events = approvals.path("events");
        if (!approvals.isObject() || !events.isArray()) {
            findings.add("Approval evidence must contain an events array: " + runRoot);
            return;
        }
        List<String> responses = new ArrayList<>();
        events.forEach(event -> responses.add(event.path("response").asText("")));
        switch (scenario.approvalBehavior()) {
            case NONE -> {
                if (!responses.isEmpty()) findings.add("Unexpected approval event for NONE scenario: " + runRoot);
            }
            case APPROVE_ONCE -> {
                if (responses.stream().noneMatch(response -> response.equals("APPROVED"))) {
                    findings.add("APPROVE_ONCE scenario lacks APPROVED evidence: " + runRoot);
                }
            }
            case DENY -> {
                if (responses.stream().noneMatch(response -> response.equals("DENIED"))) {
                    findings.add("DENY scenario lacks DENIED evidence: " + runRoot);
                }
            }
            case SESSION_ALLOW -> {
                if (responses.stream().noneMatch(response -> response.equals("APPROVED_REMEMBER"))) {
                    findings.add("SESSION_ALLOW scenario lacks APPROVED_REMEMBER evidence: " + runRoot);
                }
            }
            case INTERRUPT_BEFORE_APPROVAL -> {
                if (!responses.isEmpty()) {
                    findings.add("Interrupted-before-approval scenario contains approval decisions: " + runRoot);
                }
            }
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
        if (campaign.protocolVersion() != QualificationProtocol.VERSION) {
            findings.add("Unsupported protocolVersion: " + campaign.protocolVersion());
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
