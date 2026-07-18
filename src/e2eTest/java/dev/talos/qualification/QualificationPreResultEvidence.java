package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import dev.talos.core.security.WorkspaceContainment;
import dev.talos.runtime.policy.ArtifactCanaryScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Captures and validates the immutable evidence used before result construction. */
final class QualificationPreResultEvidence {
    private static final List<String> REQUIRED_JSON = List.of(
            "request.json",
            "scenario.json",
            "trace.json",
            "approvals.json",
            "before-hashes.json",
            "after-hashes.json",
            "timings.json",
            "resources.json",
            "transcript.raw.json");
    private static final List<String> REQUIRED_NONEMPTY_TEXT = List.of(
            "transcript.txt", "prompt-debug.txt", "llama.log", "resource-samples.csv");
    private static final List<String> REQUIRED_DIRECTORIES = List.of(
            "before-files", "after-files", "final-files");

    private QualificationPreResultEvidence() {}

    record Snapshot(
            Path packetRoot,
            Path runRoot,
            QualificationProtocol.Scenario scenario,
            QualificationRecords.RequestRecord request,
            QualificationRecords.CampaignManifest campaign,
            QualificationRecords.CalibrationRules calibration,
            QualificationRecords.MachineFacts machine,
            QualificationExecutionIdentity.Binding executionIdentity,
            QualificationTraceEvidence.Facts trace,
            QualificationRecords.TimingEvidence timings,
            QualificationRecords.ResourceEvidence resources,
            QualificationResourceSamples.Summary resourceSamples,
            String answer,
            String transcript,
            Map<String, byte[]> beforeFiles,
            Map<String, byte[]> afterFiles,
            Map<String, byte[]> finalFiles,
            Map<String, String> artifactHashes,
            List<QualificationRecords.RequirementResult> requirements,
            ArtifactCanaryScanner.CapturedRuntimeScan canaryScan,
            QualificationRecords.AnswerOrigin matchedAnswerOrigin,
            boolean operatorInterrupted,
            boolean harnessFailureProven,
            QualificationRecords.TrustGates trustGates,
            List<String> findings) {
        Snapshot {
            packetRoot = packetRoot.toAbsolutePath().normalize();
            runRoot = runRoot.toAbsolutePath().normalize();
            scenario = Objects.requireNonNull(scenario, "scenario");
            executionIdentity = Objects.requireNonNull(
                    executionIdentity, "executionIdentity");
            answer = answer == null ? "" : answer;
            transcript = transcript == null ? "" : transcript;
            beforeFiles = immutableBytes(beforeFiles);
            afterFiles = immutableBytes(afterFiles);
            finalFiles = immutableBytes(finalFiles);
            artifactHashes = artifactHashes == null
                    ? Map.of() : Map.copyOf(new TreeMap<>(artifactHashes));
            requirements = requirements == null ? List.of() : List.copyOf(requirements);
            canaryScan = Objects.requireNonNull(canaryScan, "captured canary scan");
            matchedAnswerOrigin = matchedAnswerOrigin == null
                    ? QualificationRecords.AnswerOrigin.NONE : matchedAnswerOrigin;
            trustGates = Objects.requireNonNull(trustGates, "trustGates");
            findings = findings == null ? List.of() : findings.stream().distinct().sorted().toList();
        }

        boolean complete() {
            return findings.isEmpty();
        }

        String repetitionId() {
            if (request != null && request.repetitionId() != null
                    && !request.repetitionId().isBlank()) {
                return request.repetitionId().strip();
            }
            Path fileName = runRoot.getFileName();
            return fileName == null ? "UNBOUND" : "UNBOUND-" + fileName;
        }

        boolean canaryPassed() {
            return canaryScan.passed();
        }

        boolean canaryCoverageComplete() {
            return canaryScan.coverageComplete();
        }

        int canaryFindingCount() {
            return canaryScan.findings().size();
        }

        @Override
        public Map<String, byte[]> beforeFiles() {
            return immutableBytes(beforeFiles);
        }

        @Override
        public Map<String, byte[]> afterFiles() {
            return immutableBytes(afterFiles);
        }

        @Override
        public Map<String, byte[]> finalFiles() {
            return immutableBytes(finalFiles);
        }
    }

    static Snapshot capture(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Objects.requireNonNull(catalog, "catalog");
        Path root = packetRoot.toAbsolutePath().normalize();
        Path runRoot = QualificationRequirementFinalizer.containedRunRoot(
                root, requestedRunRoot, scenarioId, catalog);
        QualificationProtocol.Scenario scenario = catalog.scenario(scenarioId);
        List<String> findings = new ArrayList<>();

        Map<Path, byte[]> runArtifacts = captureTree(runRoot);
        Map<String, String> artifactHashes = new TreeMap<>();
        runArtifacts.forEach((path, bytes) -> {
            String relative = runRoot.relativize(path).toString().replace('\\', '/');
            if (!Set.of("result.json", QualificationCausalFinalizer.OUTPUT_FILE, "canary.json")
                    .contains(relative)) {
                artifactHashes.put("run/" + relative,
                        QualificationScenarioBank.sha256(bytes));
            }
        });
        for (String name : REQUIRED_JSON) {
            byte[] bytes = runArtifacts.get(runRoot.resolve(name));
            if (bytes == null || bytes.length == 0) {
                findings.add("MISSING_OR_EMPTY_JSON:" + name);
            } else {
                try {
                    JsonNode tree = QualificationJson.read(bytes, JsonNode.class);
                    if (tree == null || tree.isNull() || tree.isMissingNode()) {
                        findings.add("INVALID_JSON:" + name);
                    }
                } catch (IOException | RuntimeException invalid) {
                    findings.add("INVALID_JSON:" + name);
                }
            }
        }
        if (!runArtifacts.containsKey(runRoot.resolve("answer.txt"))) {
            findings.add("MISSING_TEXT:answer.txt");
        }
        for (String name : REQUIRED_NONEMPTY_TEXT) {
            byte[] bytes = runArtifacts.get(runRoot.resolve(name));
            if (bytes == null || bytes.length == 0) findings.add("MISSING_OR_EMPTY_TEXT:" + name);
        }
        for (String name : REQUIRED_DIRECTORIES) {
            if (!Files.isDirectory(runRoot.resolve(name))) {
                findings.add("MISSING_DIRECTORY:" + name);
            }
        }

        QualificationRecords.RequestRecord request = parse(
                runArtifacts.get(runRoot.resolve("request.json")),
                QualificationRecords.RequestRecord.class, "request.json", findings);
        QualificationProtocol.Scenario capturedScenario = parse(
                runArtifacts.get(runRoot.resolve("scenario.json")),
                QualificationProtocol.Scenario.class, "scenario.json", findings);
        if (capturedScenario == null || !capturedScenario.equals(scenario)) {
            findings.add("SCENARIO_SNAPSHOT_MISMATCH");
        }

        byte[] campaignBytes = readFile(root.resolve("campaign.json"), "campaign.json", findings);
        putHash(artifactHashes, "packet/campaign.json", campaignBytes);
        byte[] runtimeIdentityBytes = readFile(
                root.resolve(QualificationExecutionIdentity.RUNTIME_FILE),
                QualificationExecutionIdentity.RUNTIME_FILE, findings);
        putHash(artifactHashes, "packet/" + QualificationExecutionIdentity.RUNTIME_FILE,
                runtimeIdentityBytes);
        byte[] harnessManifestBytes = readFile(
                root.resolve(QualificationExecutionIdentity.HARNESS_FILE),
                QualificationExecutionIdentity.HARNESS_FILE, findings);
        putHash(artifactHashes, "packet/" + QualificationExecutionIdentity.HARNESS_FILE,
                harnessManifestBytes);
        Path preflightManifestPath = root.resolve(QualificationExecutionIdentity.PREFLIGHT_FILE);
        if (Files.exists(preflightManifestPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] preflightManifestBytes = readFile(
                    preflightManifestPath, QualificationExecutionIdentity.PREFLIGHT_FILE, findings);
            putHash(artifactHashes, "packet/" + QualificationExecutionIdentity.PREFLIGHT_FILE,
                    preflightManifestBytes);
        }
        QualificationRecords.CampaignManifest campaign = parse(
                campaignBytes, QualificationRecords.CampaignManifest.class,
                "campaign.json", findings);
        QualificationRecords.CandidateManifest candidate = null;
        QualificationRecords.CalibrationRules calibration = null;
        QualificationRecords.MachineFacts machine = null;
        QualificationRecords.EngineFacts engine = null;
        byte[] calibrationBytes = null;
        byte[] guideBytes = null;
        if (campaign != null) {
            Path candidateRoot = contained(root,
                    root.resolve("candidates").resolve(nullToEmpty(campaign.candidateAlias())), findings,
                    "candidate-root");
            Path machinePath = contained(root,
                    root.resolve("machines").resolve(nullToEmpty(campaign.machineId()) + ".json"), findings,
                    "machine-path");
            Path enginePath = contained(root,
                    root.resolve("engines").resolve(nullToEmpty(campaign.engineLaneId()) + ".json"), findings,
                    "engine-path");
            if (candidateRoot != null) {
                byte[] candidateBytes = readFile(candidateRoot.resolve("candidate.json"),
                        "candidate.json", findings);
                putHash(artifactHashes, "packet/candidate.json", candidateBytes);
                candidate = parse(candidateBytes,
                        QualificationRecords.CandidateManifest.class, "candidate.json", findings);
                calibrationBytes = readFile(candidateRoot.resolve("calibration-rules.json"),
                        "calibration-rules.json", findings);
                putHash(artifactHashes, "packet/calibration-rules.json", calibrationBytes);
                calibration = parse(calibrationBytes, QualificationRecords.CalibrationRules.class,
                        "calibration-rules.json", findings);
                guideBytes = readFile(candidateRoot.resolve("calibration-guide.md"),
                        "calibration-guide.md", findings);
                putHash(artifactHashes, "packet/calibration-guide.md", guideBytes);
            }
            if (machinePath != null) {
                byte[] machineBytes = readFile(machinePath, "machine.json", findings);
                putHash(artifactHashes, "packet/machine.json", machineBytes);
                machine = parse(machineBytes, QualificationRecords.MachineFacts.class,
                        "machine.json", findings);
            }
            if (enginePath != null) {
                byte[] engineBytes = readFile(enginePath, "engine.json", findings);
                putHash(artifactHashes, "packet/engine.json", engineBytes);
                engine = parse(engineBytes, QualificationRecords.EngineFacts.class,
                        "engine.json", findings);
            }
        } else {
            findings.add("CAMPAIGN_IDENTITY_UNAVAILABLE");
        }

        validateIdentity(root, runRoot, catalog, scenario, request, campaign, candidate,
                calibration, calibrationBytes, guideBytes, machine, engine, findings);

        byte[] traceBytes = runArtifacts.get(runRoot.resolve("trace.json"));
        QualificationTraceEvidence.Facts trace = null;
        int traceSchemaVersion = -1;
        if (traceBytes != null) {
            try {
                traceSchemaVersion = QualificationJson.readTree(traceBytes)
                        .path("schemaVersion").asInt(-1);
                trace = QualificationTraceEvidence.read(traceBytes, scenario);
                QualificationAuthorityPolicy.mismatch(
                                scenario, trace.taskType(), trace.mutationAllowed(),
                                trace.actionObligation())
                        .ifPresent(mismatch -> findings.add("TRACE_AUTHORITY_MISMATCH:" + mismatch));
            } catch (IOException | RuntimeException invalid) {
                findings.add("TRACE_INVALID");
            }
        }
        QualificationExecutionIdentity.Binding executionIdentity =
                QualificationExecutionIdentity.verify(
                        root, campaign, candidate, calibration, machine, engine,
                        traceBytes, true);
        if (catalog.protocolVersion() == QualificationProtocol.VERSION) {
            // T1188: packet-time hashes and later metadata continuity do not prove
            // which engine/model bytes the active provider process consumed. No
            // forgeable packet file is accepted as a substitute. Protocol v6
            // therefore remains non-comparative until the runtime emits and the
            // harness verifies a typed launch receipt rooted in that process.
            findings.add("ACTIVE_PROCESS_LAUNCH_RECEIPT_UNAVAILABLE");
        }

        QualificationRecords.TimingEvidence timings = parse(
                runArtifacts.get(runRoot.resolve("timings.json")),
                QualificationRecords.TimingEvidence.class, "timings.json", findings);
        QualificationRecords.ResourceEvidence resources = parse(
                runArtifacts.get(runRoot.resolve("resources.json")),
                QualificationRecords.ResourceEvidence.class, "resources.json", findings);
        validateTimings(timings, findings);
        validateResources(resources, machine, findings);
        validateProviderAttemptTiming(
                catalog.protocolVersion(), traceSchemaVersion, trace, timings, findings);
        QualificationResourceSamples.Summary resourceSamples = null;
        if (machine != null) {
            try {
                resourceSamples = QualificationResourceSamples.summarize(
                        runArtifacts.get(runRoot.resolve("resource-samples.csv")), machine);
                if (!resourceSamples.matches(resources)) {
                    findings.add("RESOURCE_SUMMARY_MISMATCH");
                }
            } catch (RuntimeException invalid) {
                findings.add("RESOURCE_SAMPLES_INVALID");
            }
        } else {
            findings.add("RESOURCE_SAMPLES_MACHINE_UNAVAILABLE");
        }

        JsonNode capture = parseTree(
                runArtifacts.get(runRoot.resolve("transcript.raw.json")),
                "transcript.raw.json", findings);
        validatePty(capture, request, catalog.protocolVersion(), findings);
        validateResourceSampleWindow(resourceSamples, capture, findings);
        validateApprovals(runArtifacts.get(runRoot.resolve("approvals.json")), capture,
                scenario, trace, findings);

        Map<String, byte[]> before = capturedDirectory(runRoot, "before-files", runArtifacts);
        Map<String, byte[]> after = capturedDirectory(runRoot, "after-files", runArtifacts);
        Map<String, byte[]> finalFiles = capturedDirectory(runRoot, "final-files", runArtifacts);
        validateFixture(catalog, scenario, before, after, finalFiles,
                runArtifacts.get(runRoot.resolve("before-hashes.json")),
                runArtifacts.get(runRoot.resolve("after-hashes.json")), findings);

        String answer = text(runArtifacts.get(runRoot.resolve("answer.txt")));
        String transcript = text(runArtifacts.get(runRoot.resolve("transcript.txt")));
        QualificationRecords.AnswerOrigin matchedOrigin =
                QualificationCausalOwnership.matchedAnswerOrigin(
                        runArtifacts.get(runRoot.resolve("answer.txt")), traceBytes,
                        catalog.protocolVersion());
        if (catalog.protocolVersion() == QualificationProtocol.VERSION
                && matchedOrigin == QualificationRecords.AnswerOrigin.MODEL_PROVIDER
                && trace != null
                && trace.providerAttempts().nonModelContributionPresent()) {
            findings.add("NON_MODEL_CONTRIBUTION_PRECEDES_MODEL_ANSWER");
        }
        validateProviderEvidence(
                text(runArtifacts.get(runRoot.resolve("prompt-debug.txt"))),
                text(runArtifacts.get(runRoot.resolve("llama.log"))),
                capture, timings, matchedOrigin, findings);

        boolean scenarioSnapshotVerified = capturedScenario != null && capturedScenario.equals(scenario);
        Map<Path, byte[]> rawCanaryArtifacts = runArtifacts.entrySet().stream()
                .filter(entry -> {
                    String relative = runRoot.relativize(entry.getKey()).toString().replace('\\', '/');
                    return !Set.of(
                            "result.json",
                            QualificationCausalFinalizer.OUTPUT_FILE,
                            QualificationRunFinalizer.CANARY_FILE).contains(relative);
                })
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, Map.Entry::getValue));
        Map<Path, byte[]> sourceAllowlist = sourceAllowlist(
                runRoot, runArtifacts, before, after, finalFiles, scenarioSnapshotVerified);
        ArtifactCanaryScanner.CapturedRuntimeScan canaryScan =
                ArtifactCanaryScanner.scanCapturedRuntimeArtifacts(
                        runRoot, rawCanaryArtifacts, sourceAllowlist);
        for (ArtifactCanaryScanner.CoverageGap gap : canaryScan.coverageGaps()) {
            findings.add("CANARY_SCAN_INCOMPLETE:" + gap.reason().name() + ":"
                    + gap.relativePath());
        }
        boolean canaryPassed = canaryScan.passed();

        List<QualificationRecords.RequirementResult> requirements = List.of();
        QualificationRequirementFinalizer.Document requirementDocument = null;
        if (traceBytes != null && runArtifacts.containsKey(runRoot.resolve("answer.txt"))) {
            try {
                requirementDocument = QualificationRequirementFinalizer.evaluate(
                        catalog.protocolVersion(), catalog.sha256(), scenario,
                        new QualificationRequirementEvaluator.Evidence(
                                answer, text(traceBytes), before, finalFiles));
                requirements = requirementDocument.results();
            } catch (RuntimeException invalid) {
                findings.add("CANONICAL_REQUIREMENTS_INVALID");
            }
        }
        byte[] declaredRequirements = runArtifacts.get(
                runRoot.resolve(QualificationRequirementFinalizer.OUTPUT_FILE));
        if (declaredRequirements != null && requirementDocument != null) {
            QualificationRequirementFinalizer.Document declared = parse(
                    declaredRequirements, QualificationRequirementFinalizer.Document.class,
                    QualificationRequirementFinalizer.OUTPUT_FILE, findings);
            if (declared == null || !declared.equals(requirementDocument)) {
                findings.add("MECHANICAL_REQUIREMENTS_MISMATCH");
            }
        }

        boolean complete = findings.isEmpty();
        boolean harnessFailureProven = explicitPtyHarnessFailure(
                capture, request, catalog.protocolVersion());
        boolean operatorInterrupted = complete
                && operatorInterruptionProven(
                        capture, scenario, trace, answer, matchedOrigin);
        QualificationRecords.TrustGates trust = QualificationEvidenceValidator.deriveTrustGates(
                scenario, trace, requirements, canaryPassed, answer, transcript, calibration,
                before, finalFiles, complete);
        return new Snapshot(root, runRoot, scenario, request, campaign, calibration, machine,
                executionIdentity,
                trace, timings, resources, resourceSamples, answer, transcript,
                before, after, finalFiles, artifactHashes,
                requirements, canaryScan,
                complete ? matchedOrigin : QualificationRecords.AnswerOrigin.NONE,
                operatorInterrupted, harnessFailureProven, trust, findings);
    }

    private static boolean explicitPtyHarnessFailure(
            JsonNode capture,
            QualificationRecords.RequestRecord request,
            int protocolVersion) {
        if (protocolVersion != QualificationProtocol.VERSION) return false;
        if (request != null
                && request.transport() != null
                && !request.transport().isBlank()
                && !"TRUE_PTY".equals(request.transport())) {
            return true;
        }
        if (capture == null || !capture.isObject()
                || !"talos.modelQualification.ptyCapture".equals(
                        capture.path("schemaName").asText(""))
                || capture.path("schemaVersion").asInt(-1) != protocolVersion) {
            return false;
        }
        JsonNode tty = capture.path("tty");
        if (tty.isBoolean() && !tty.booleanValue()) return true;
        JsonNode transport = capture.path("transport");
        return transport.isTextual()
                && !transport.textValue().isBlank()
                && !Set.of("WINDOWS_PTY", "UNIX_PTY", "TEST_PTY")
                        .contains(transport.textValue());
    }

    private static void validateIdentity(
            Path root,
            Path runRoot,
            QualificationScenarioBank.Catalog catalog,
            QualificationProtocol.Scenario scenario,
            QualificationRecords.RequestRecord request,
            QualificationRecords.CampaignManifest campaign,
            QualificationRecords.CandidateManifest candidate,
            QualificationRecords.CalibrationRules calibration,
            byte[] calibrationBytes,
            byte[] guideBytes,
            QualificationRecords.MachineFacts machine,
            QualificationRecords.EngineFacts engine,
            List<String> findings) {
        if (request == null) return;
        validateSchema(request.schemaName(), request.schemaVersion(),
                QualificationRecords.REQUEST_SCHEMA, "REQUEST_SCHEMA_MISMATCH", findings);
        if (!scenario.id().equals(request.scenarioId())) findings.add("REQUEST_SCENARIO_MISMATCH");
        if (!scenario.prompt().equals(request.prompt())) findings.add("REQUEST_PROMPT_MISMATCH");
        if (!"TRUE_PTY".equals(request.transport())) findings.add("REQUEST_TRANSPORT_MISMATCH");
        if (request.command() == null || request.command().isBlank()) findings.add("REQUEST_COMMAND_MISSING");
        if (campaign == null) return;
        validateSchema(campaign.schemaName(), campaign.schemaVersion(),
                QualificationRecords.CAMPAIGN_SCHEMA, "CAMPAIGN_SCHEMA_MISMATCH", findings);
        if (campaign.protocolVersion() != catalog.protocolVersion()) findings.add("CAMPAIGN_PROTOCOL_MISMATCH");
        if (!equalHash(campaign.scenarioBankSha256(), catalog.sha256())) {
            findings.add("CAMPAIGN_BANK_HASH_MISMATCH");
        }
        if (!safeSegment(campaign.candidateAlias()) || !safeSegment(campaign.machineId())
                || !safeSegment(campaign.engineLaneId()) || !safeSegment(campaign.configId())) {
            findings.add("CAMPAIGN_PATH_IDENTITY_INVALID");
        }
        int repetition = campaign.repetitions().indexOf(request.repetitionId());
        if (repetition < 0) {
            findings.add("REQUEST_REPETITION_NOT_IN_CAMPAIGN");
        } else {
            Path expected = root.resolve("runs")
                    .resolve(campaign.candidateAlias())
                    .resolve(campaign.machineId())
                    .resolve(campaign.configId())
                    .resolve(scenario.id())
                    .resolve("trial-%02d".formatted(repetition + 1))
                    .toAbsolutePath().normalize();
            if (!expected.equals(runRoot)) findings.add("RUN_PATH_IDENTITY_MISMATCH");
        }
        if (calibrationBytes == null
                || !equalHash(campaign.calibrationRulesSha256(),
                        QualificationScenarioBank.sha256(calibrationBytes))) {
            findings.add("CALIBRATION_RULES_HASH_MISMATCH");
        }
        if (guideBytes == null
                || !equalHash(campaign.calibrationGuideSha256(),
                        QualificationScenarioBank.sha256(guideBytes))) {
            findings.add("CALIBRATION_GUIDE_HASH_MISMATCH");
        }
        if (candidate == null) {
            findings.add("CANDIDATE_IDENTITY_UNAVAILABLE");
        } else {
            validateSchema(candidate.schemaName(), candidate.schemaVersion(),
                    QualificationRecords.CANDIDATE_SCHEMA, "CANDIDATE_SCHEMA_MISMATCH", findings);
            if (!Objects.equals(candidate.alias(), campaign.candidateAlias())) {
                findings.add("CANDIDATE_ALIAS_MISMATCH");
            }
        }
        if (machine == null) {
            findings.add("MACHINE_IDENTITY_UNAVAILABLE");
        } else {
            validateSchema(machine.schemaName(), machine.schemaVersion(),
                    QualificationRecords.MACHINE_SCHEMA, "MACHINE_SCHEMA_MISMATCH", findings);
            if (!Objects.equals(machine.machineId(), campaign.machineId())) {
                findings.add("MACHINE_ID_MISMATCH");
            }
            if (machine.physicalRamBytes() <= 0 || machine.threadCount() <= 0) {
                findings.add("MACHINE_VALUES_INVALID");
            }
        }
        if (engine == null) {
            findings.add("ENGINE_IDENTITY_UNAVAILABLE");
        } else {
            validateSchema(engine.schemaName(), engine.schemaVersion(),
                    QualificationRecords.ENGINE_SCHEMA, "ENGINE_SCHEMA_MISMATCH", findings);
            if (!Objects.equals(engine.laneId(), campaign.engineLaneId())) {
                findings.add("ENGINE_LANE_MISMATCH");
            }
        }
        if (calibration == null) {
            findings.add("CALIBRATION_IDENTITY_UNAVAILABLE");
            return;
        }
        validateSchema(calibration.schemaName(), calibration.schemaVersion(),
                QualificationRecords.CALIBRATION_SCHEMA, "CALIBRATION_SCHEMA_MISMATCH", findings);
        if (!Objects.equals(calibration.configurationId(), campaign.configId())
                || !Objects.equals(calibration.candidateAlias(), campaign.candidateAlias())
                || calibration.protocolVersion() != campaign.protocolVersion()
                || !equalHash(calibration.talosCommit(), campaign.talosCommit())) {
            findings.add("CALIBRATION_CAMPAIGN_IDENTITY_MISMATCH");
        }
        if (candidate != null && (!Objects.equals(calibration.repository(), candidate.repository())
                || !equalHash(calibration.revision(), candidate.revision())
                || !Objects.equals(calibration.artifact(), candidate.artifact())
                || calibration.artifactSizeBytes() != candidate.sizeBytes()
                || !equalHash(calibration.artifactSha256(), candidate.sha256()))) {
            findings.add("CALIBRATION_CANDIDATE_IDENTITY_MISMATCH");
        }
        if (engine != null && (!Objects.equals(calibration.llamaCppTag(), engine.tag())
                || !equalHash(calibration.executableSha256(), engine.sha256())
                || !Objects.equals(calibration.backend(), engine.backend())
                || !calibration.launchArguments().equals(engine.launchArguments())
                || !calibration.verificationLaunchArguments().equals(
                        engine.verificationLaunchArguments()))) {
            findings.add("CALIBRATION_ENGINE_IDENTITY_MISMATCH");
        }
    }

    private static void validateFixture(
            QualificationScenarioBank.Catalog catalog,
            QualificationProtocol.Scenario scenario,
            Map<String, byte[]> before,
            Map<String, byte[]> after,
            Map<String, byte[]> finalFiles,
            byte[] beforeHashes,
            byte[] afterHashes,
            List<String> findings) {
        QualificationScenarioBank.FixtureResource fixture = catalog.fixtures().get(scenario.fixture());
        Map<String, byte[]> expected = new TreeMap<>();
        if (fixture != null) {
            fixture.manifest().files().forEach((path, content) ->
                    expected.put(path, content.getBytes(StandardCharsets.UTF_8)));
        }
        if (!equalBytes(before, expected)) findings.add("BEFORE_FIXTURE_MISMATCH");
        if (!equalBytes(after, finalFiles)) findings.add("AFTER_FINAL_MISMATCH");
        validateDeclaredHashes(beforeHashes, before, "BEFORE_HASHES_MISMATCH", findings);
        validateDeclaredHashes(afterHashes, after, "AFTER_HASHES_MISMATCH", findings);
    }

    private static void validateDeclaredHashes(
            byte[] declaredBytes,
            Map<String, byte[]> actual,
            String finding,
            List<String> findings) {
        JsonNode declared = parseTree(declaredBytes, finding, findings);
        if (declared == null || !declared.isObject()) {
            findings.add(finding);
            return;
        }
        Map<String, String> values = new TreeMap<>();
        declared.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText("")));
        if (!values.equals(hashes(actual))) findings.add(finding);
    }

    private static void validatePty(
            JsonNode capture,
            QualificationRecords.RequestRecord request,
            int protocolVersion,
            List<String> findings) {
        if (capture == null || request == null) {
            findings.add("PTY_CAPTURE_INVALID");
            return;
        }
        String captureCommand = capture.path("command").asText("").replace('\\', '/');
        String requestCommand = nullToEmpty(request.command()).replace('\\', '/');
        boolean valid = capture.isObject()
                && "talos.modelQualification.ptyCapture".equals(capture.path("schemaName").asText(""))
                && capture.path("schemaVersion").asInt(-1) == protocolVersion
                && capture.path("tty").asBoolean(false)
                && Set.of("WINDOWS_PTY", "UNIX_PTY", "TEST_PTY")
                        .contains(capture.path("transport").asText(""))
                && !capture.path("rawOutput").asText("").isBlank()
                && !capture.path("workspace").asText("").isBlank()
                && !captureCommand.isBlank()
                && !requestCommand.isBlank()
                && (captureCommand.equals(requestCommand)
                        || captureCommand.endsWith("/" + requestCommand))
                && capture.path("events").isArray()
                && "TRUE_PTY".equals(request.transport());
        if (valid) {
            Instant previous = instant(capture.path("captureStartedAt").asText(""));
            Instant ended = instant(capture.path("captureEndedAt").asText(""));
            boolean scenarioInput = false;
            boolean lastTrace = false;
            boolean output = false;
            StringBuilder capturedOutput = new StringBuilder();
            if (previous == null || ended == null || ended.isBefore(previous)) valid = false;
            for (JsonNode event : capture.path("events")) {
                Instant timestamp = instant(event.path("timestamp").asText(""));
                if (timestamp == null || previous == null || ended == null
                        || timestamp.isBefore(previous) || timestamp.isAfter(ended)) {
                    valid = false;
                } else {
                    previous = timestamp;
                }
                String type = event.path("type").asText("");
                String data = event.path("data").asText("");
                if ("input".equals(type) && "scenario-prompt".equals(data)) scenarioInput = true;
                if ("input".equals(type) && "last-trace".equals(data)) lastTrace = true;
                if ("output".equals(type) && !data.isBlank()) {
                    output = true;
                    capturedOutput.append(data);
                }
            }
            valid = valid && scenarioInput && lastTrace && output
                    && capturedOutput.toString().equals(capture.path("rawOutput").asText(""));
        }
        if (!valid) findings.add("PTY_CAPTURE_INVALID");
    }

    private static void validateResourceSampleWindow(
            QualificationResourceSamples.Summary samples,
            JsonNode capture,
            List<String> findings) {
        if (samples == null || capture == null) return;
        Instant started = instant(capture.path("captureStartedAt").asText(""));
        Instant ended = instant(capture.path("captureEndedAt").asText(""));
        if (started == null || ended == null
                || samples.firstSampleAt().isBefore(started)
                || samples.lastSampleAt().isAfter(ended)) {
            findings.add("RESOURCE_SAMPLES_OUTSIDE_CAPTURE_WINDOW");
        }
    }

    private static void validateApprovals(
            byte[] approvalBytes,
            JsonNode capture,
            QualificationProtocol.Scenario scenario,
            QualificationTraceEvidence.Facts trace,
            List<String> findings) {
        if (approvalBytes == null || capture == null || trace == null) {
            findings.add("APPROVAL_EVIDENCE_INVALID");
            return;
        }
        try {
            QualificationApprovalFinalizer.Document declared = QualificationJson.read(
                    approvalBytes, QualificationApprovalFinalizer.Document.class);
            QualificationApprovalFinalizer.Document expected = QualificationApprovalFinalizer.derive(
                    capture, scenario.approvalBehavior());
            if (!declared.equals(expected)) findings.add("APPROVAL_PTY_MISMATCH");
            boolean granted = declared.events().stream().anyMatch(event ->
                    event.response().equals("APPROVED")
                            || event.response().equals("APPROVED_REMEMBER"));
            boolean denied = declared.events().stream()
                    .anyMatch(event -> event.response().equals("DENIED"));
            if (granted != trace.approvalGranted() || denied != trace.approvalDenied()) {
                findings.add("APPROVAL_TRACE_MISMATCH");
            }
        } catch (IOException | RuntimeException invalid) {
            findings.add("APPROVAL_EVIDENCE_INVALID");
        }
    }

    private static void validateProviderEvidence(
            String prompt,
            String model,
            JsonNode capture,
            QualificationRecords.TimingEvidence timings,
            QualificationRecords.AnswerOrigin matchedOrigin,
            List<String> findings) {
        boolean promptNotApplicable = QualificationProviderEvidence.isPromptNotApplicable(prompt);
        boolean modelNotApplicable = QualificationProviderEvidence.isModelRuntimeNotApplicable(model);
        if (promptNotApplicable != modelNotApplicable) {
            findings.add("PROVIDER_NOT_APPLICABLE_UNPAIRED");
            return;
        }
        if (!promptNotApplicable) return;
        boolean exact = prompt.equals(QualificationProviderEvidence.promptNotApplicableText())
                && model.equals(QualificationProviderEvidence.modelRuntimeNotApplicableText());
        boolean noTiming = timings != null
                && timings.promptEvalMillis() == 0
                && timings.generationMillis() == 0
                && timings.promptTokens() == 0
                && timings.outputTokens() == 0
                && Double.compare(timings.promptTokensPerSecond(), 0.0) == 0
                && Double.compare(timings.generationTokensPerSecond(), 0.0) == 0
                && timings.toolEligibleGenerations() == 0
                && (timings.backendErrorCode() == null || timings.backendErrorCode().isBlank());
        boolean captured = capture != null && capture.path("rawOutput").asText("")
                .contains(QualificationProviderEvidence.NO_PROVIDER_PROMPT);
        if (!exact || !noTiming || !captured
                || matchedOrigin == QualificationRecords.AnswerOrigin.MODEL_PROVIDER) {
            findings.add("PROVIDER_NOT_APPLICABLE_CONTRADICTION");
        }
    }

    private static void validateTimings(
            QualificationRecords.TimingEvidence timing,
            List<String> findings) {
        if (timing == null) return;
        boolean valid = QualificationRecords.TIMING_SCHEMA.equals(timing.schemaName())
                && timing.schemaVersion() == QualificationRecords.SCHEMA_VERSION
                && timing.firstOutputMillis() >= 0
                && timing.totalTurnWallTimeMillis() >= 0
                && timing.approvalWaitMillis() >= 0
                && timing.firstOutputMillis() <= timing.totalTurnWallTimeMillis()
                && timing.approvalWaitMillis() <= timing.totalTurnWallTimeMillis()
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
        if (!valid) findings.add("TIMING_EVIDENCE_INVALID");
    }

    private static void validateProviderAttemptTiming(
            int protocolVersion,
            int traceSchemaVersion,
            QualificationTraceEvidence.Facts trace,
            QualificationRecords.TimingEvidence timing,
            List<String> findings) {
        int expectedTraceSchema = protocolVersion == QualificationProtocol.VERSION
                ? QualificationProviderAttemptLedger.TRACE_SCHEMA_VERSION
                : 2;
        if (traceSchemaVersion != expectedTraceSchema) {
            findings.add("PROTOCOL_TRACE_SCHEMA_MISMATCH");
            return;
        }
        if (protocolVersion != QualificationProtocol.VERSION
                || trace == null || timing == null) {
            return;
        }
        QualificationProviderAttemptLedger.Ledger ledger = trace.providerAttempts();
        if (ledger.toolProposalTruncationPresent()) {
            findings.add("PROVIDER_TOOL_PROPOSAL_TRACE_TRUNCATED");
        }
        if (ledger.recoveredAttemptPresent()) {
            findings.add("PROVIDER_ATTEMPT_RECOVERY_PRESENT");
        }
        if (timing.timedOut() != ledger.timedOut()) {
            findings.add("TIMING_TRACE_TIMEOUT_MISMATCH");
        }
        if (timing.outputCapHit() != ledger.outputCapHit()) {
            findings.add("TIMING_TRACE_OUTPUT_CAP_MISMATCH");
        }
        if (timing.toolEligibleGenerations() != ledger.toolEligibleGenerations()) {
            findings.add("TIMING_TRACE_TOOL_ELIGIBLE_COUNT_MISMATCH");
        }
        if (timing.backendErrorCode() != null && !timing.backendErrorCode().isBlank()) {
            findings.add("TIMING_BACKEND_ERROR_CODE_FORBIDDEN_V6");
        }
    }

    private static void validateResources(
            QualificationRecords.ResourceEvidence resources,
            QualificationRecords.MachineFacts machine,
            List<String> findings) {
        if (resources == null) return;
        boolean valid = QualificationRecords.RESOURCE_SCHEMA.equals(resources.schemaName())
                && resources.schemaVersion() == QualificationRecords.SCHEMA_VERSION
                && resources.processWorkingSetBytes() >= 0
                && resources.systemRamBytes() >= 0
                && resources.pagefileOrSwapBytes() >= 0
                && resources.gpuVramUsedBytes() >= 0
                && resources.gpuVramBytes() >= 0
                && (resources.gpuVramBytes() == 0
                        ? resources.gpuVramUsedBytes() == 0
                        : resources.gpuVramUsedBytes() <= resources.gpuVramBytes())
                && (machine == null || machine.physicalRamBytes() <= 0
                        || resources.systemRamBytes() <= machine.physicalRamBytes())
                && resources.measurementSource() != null
                && !resources.measurementSource().isBlank();
        if (!valid) findings.add("RESOURCE_EVIDENCE_INVALID");
    }

    static boolean operatorInterruptionProven(
            JsonNode capture,
            QualificationProtocol.Scenario scenario,
            QualificationTraceEvidence.Facts trace,
            String answer,
            QualificationRecords.AnswerOrigin matchedOrigin) {
        if (capture == null || trace == null
                || scenario.approvalBehavior()
                        != QualificationProtocol.ApprovalBehavior.INTERRUPT_BEFORE_APPROVAL
                || scenario.responseOwnerContract()
                        != QualificationRecords.ResponseOwnerContract.OPERATOR_OWNED
                || matchedOrigin != QualificationRecords.AnswerOrigin.OPERATOR
                || !"LLM_ABORTED".equals(trace.terminalState())
                || trace.approvalGranted() || trace.approvalDenied()
                || answer == null || !answer.isBlank()) return false;
        int interrupt = -1;
        int terminal = -1;
        JsonNode events = capture.path("events");
        if (!events.isArray()) return false;
        for (int index = 0; index < events.size(); index++) {
            JsonNode event = events.get(index);
            if ("input".equals(event.path("type").asText(""))
                    && "user-interrupt".equals(event.path("data").asText(""))) {
                interrupt = index;
            }
            if ("terminal-outcome".equals(event.path("type").asText(""))
                    && "turn-aborted-by-operator".equals(event.path("data").asText(""))) {
                terminal = index;
            }
        }
        return interrupt >= 0 && terminal > interrupt;
    }

    private static Map<Path, byte[]> sourceAllowlist(
            Path runRoot,
            Map<Path, byte[]> runArtifacts,
            Map<String, byte[]> before,
            Map<String, byte[]> after,
            Map<String, byte[]> finalFiles,
            boolean scenarioVerified) {
        Map<Path, byte[]> allow = new LinkedHashMap<>();
        Path scenarioPath = runRoot.resolve("scenario.json").toAbsolutePath().normalize();
        if (scenarioVerified && runArtifacts.get(scenarioPath) != null) {
            allow.put(scenarioPath, runArtifacts.get(scenarioPath));
        }
        before.forEach((relative, bytes) ->
                allow.put(runRoot.resolve("before-files").resolve(relative), bytes));
        unchangedPaths(before, after).forEach(relative ->
                allow.put(runRoot.resolve("after-files").resolve(relative), before.get(relative)));
        unchangedPaths(before, finalFiles).forEach(relative ->
                allow.put(runRoot.resolve("final-files").resolve(relative), before.get(relative)));
        return Map.copyOf(allow);
    }

    private static List<String> unchangedPaths(
            Map<String, byte[]> before,
            Map<String, byte[]> candidate) {
        return candidate.entrySet().stream()
                .filter(entry -> before.containsKey(entry.getKey()))
                .filter(entry -> Arrays.equals(
                        before.get(entry.getKey()), entry.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static Map<Path, byte[]> captureTree(Path root) throws IOException {
        Map<Path, byte[]> captured = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                captured.put(path.toAbsolutePath().normalize(), Files.readAllBytes(path));
            }
        }
        return Map.copyOf(captured);
    }

    private static Map<String, byte[]> capturedDirectory(
            Path runRoot,
            String name,
            Map<Path, byte[]> artifacts) {
        Path root = runRoot.resolve(name);
        Map<String, byte[]> captured = new TreeMap<>();
        artifacts.forEach((path, bytes) -> {
            if (path.startsWith(root)) {
                captured.put(root.relativize(path).toString().replace('\\', '/'), bytes.clone());
            }
        });
        return immutableBytes(captured);
    }

    private static Map<String, String> hashes(Map<String, byte[]> files) {
        Map<String, String> hashes = new TreeMap<>();
        files.forEach((path, bytes) -> hashes.put(path, QualificationScenarioBank.sha256(bytes)));
        return Map.copyOf(hashes);
    }

    private static boolean equalBytes(Map<String, byte[]> first, Map<String, byte[]> second) {
        if (!first.keySet().equals(second.keySet())) return false;
        return first.entrySet().stream()
                .allMatch(entry -> Arrays.equals(entry.getValue(), second.get(entry.getKey())));
    }

    private static Map<String, byte[]> immutableBytes(Map<String, byte[]> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, byte[]> copy = new TreeMap<>();
        values.forEach((path, bytes) -> copy.put(path, bytes == null ? new byte[0] : bytes.clone()));
        return Map.copyOf(copy);
    }

    private static byte[] readFile(Path path, String label, List<String> findings) {
        if (path == null || !Files.isRegularFile(path)) {
            findings.add("MISSING_PACKET_ARTIFACT:" + label);
            return null;
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            findings.add("UNREADABLE_PACKET_ARTIFACT:" + label);
            return null;
        }
    }

    private static void putHash(
            Map<String, String> hashes,
            String name,
            byte[] bytes) {
        if (bytes != null) hashes.put(name, QualificationScenarioBank.sha256(bytes));
    }

    private static <T> T parse(
            byte[] bytes,
            Class<T> type,
            String label,
            List<String> findings) {
        if (bytes == null) return null;
        try {
            return QualificationJson.read(bytes, type);
        } catch (IOException | RuntimeException invalid) {
            findings.add("INVALID_ARTIFACT:" + label);
            return null;
        }
    }

    private static JsonNode parseTree(byte[] bytes, String label, List<String> findings) {
        return parse(bytes, JsonNode.class, label, findings);
    }

    private static Path contained(
            Path root,
            Path candidate,
            List<String> findings,
            String label) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!WorkspaceContainment.contains(root, normalized)) {
            findings.add("PATH_CONTAINMENT_FAILED:" + label);
            return null;
        }
        return normalized;
    }

    private static void validateSchema(
            String name,
            int version,
            String expected,
            String finding,
            List<String> findings) {
        if (!Objects.equals(name, expected) || version != QualificationRecords.SCHEMA_VERSION) {
            findings.add(finding);
        }
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException invalid) {
            return null;
        }
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean safeSegment(String value) {
        return QualificationNamespace.isSegment(value);
    }

    private static boolean equalHash(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }
}
