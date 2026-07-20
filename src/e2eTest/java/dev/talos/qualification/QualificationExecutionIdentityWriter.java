package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;
import dev.talos.core.util.RuntimeArtifactIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * Create-new producer for protocol-v6 execution identity manifests.
 *
 * <p>The producer consumes an already assembled, pre-trial packet and a separately frozen
 * input specification. It never copies inputs and never replaces a manifest. Repeating the
 * command is permitted only when all three existing identity files are byte-identical to the derived
 * representation.
 */
final class QualificationExecutionIdentityWriter {
    static final String INPUT_SCHEMA = "talos.modelQualification.harnessInputSpecification";
    static final int INPUT_SCHEMA_VERSION = 1;
    static final String LEDGER_FILE = "execution-input-ledger.json";
    static final String LEDGER_SCHEMA = "talos.modelQualification.executionInputLedger";
    static final int LEDGER_SCHEMA_VERSION = 1;
    static final String EXTERNAL_ARTIFACT_VERIFICATION_SCOPE =
            "CAPTURE_STREAM_HASH_PLUS_POST_CAPTURE_METADATA_CONTINUITY";
    static final String ACTIVE_PROCESS_LAUNCH_RECEIPT = "UNAVAILABLE";
    private static final Map<String, String> REQUIRED_HARNESS_ROLES = Map.ofEntries(
            Map.entry("CAMPAIGN_IDENTITY_ASSERTION",
                    "tools/assert-campaign-identity.ps1"),
            Map.entry("FINALIZE_TRIAL",
                    "tools/finalize-trial.ps1"),
            Map.entry("PROTOCOL_V6_FINALIZER_TEMPLATE",
                    "work-cycle-docs/templates/finalize-model-qualification-result-v6.ps1"),
            Map.entry("RESOURCE_STOP_POLICY",
                    "tools/resource-stop-policy.ps1"),
            Map.entry("TIMESTAMP_POLICY",
                    "tools/timestamp-policy.ps1"),
            Map.entry("TRIAL_STOP_ASSERTION",
                    "tools/assert-trial-stop-conditions.ps1"));

    private QualificationExecutionIdentityWriter() {}

    record HarnessInputSpecification(
            String schemaName,
            int schemaVersion,
            int protocolVersion,
            String talosCommit,
            String finalizerImplementationSha256,
            String finalizerClasspathSha256,
            String builtTalosArtifactSha256,
            String installedTalosArtifactSha256,
            String candidateManifestSha256,
            String calibrationRulesSha256,
            String engineManifestSha256,
            String machineManifestSha256,
            String modelArtifactSha256,
            String engineExecutableSha256,
            List<ClasspathInput> installedRuntimeClasspath,
            Map<String, String> harnessRoles,
            Map<String, String> packetFiles,
            Map<String, String> repositoryFiles,
            Map<String, String> buildFiles) {}

    record ClasspathInput(String path, String sha256) {}

    record ExecutionInputLedger(
            String schemaName,
            int schemaVersion,
            int protocolVersion,
            String campaignId,
            String talosCommit,
            String finalizerImplementationSha256,
            AggregateIdentity finalizerClasspath,
            AggregateIdentity installedRuntimeClasspathIdentity,
            List<LedgerArtifact> artifacts,
            List<ExternalArtifactReceipt> externalArtifacts,
            List<LedgerClasspathEntry> installedRuntimeClasspath,
            Map<String, String> harnessRoles) {}

    record AggregateIdentity(String sha256, long sizeBytes, int entryCount) {}

    record LedgerArtifact(
            String role,
            String logicalToken,
            long sizeBytes,
            String sha256) {}

    record LedgerClasspathEntry(
            int ordinal,
            String kind,
            String logicalToken,
            long sizeBytes,
            String sha256,
            int fileCount) {}

    record ExternalArtifactReceipt(
            String role,
            String logicalToken,
            String kind,
            String sha256,
            String hashObservationContract,
            long sizeBytes,
            long lastModifiedMillis,
            long creationTimeMillis,
            String fileKeySha256,
            String postCaptureContinuityContract) {}

    @FunctionalInterface
    interface ContentHasher {
        String sha256(Path path) throws IOException;
    }

    record Request(
            Path packetRoot,
            Path repositoryRoot,
            Path builtTalosArtifact,
            Path installedTalosArtifact,
            Path modelArtifact,
            Path engineExecutable,
            Path harnessInputSpecification,
            String currentCommit,
            String currentVersion) {}

    record VerificationRequest(
            Path packetRoot,
            Path repositoryRoot,
            Path builtTalosArtifact,
            Path installedTalosArtifact,
            Path modelArtifact,
            Path engineExecutable,
            String currentCommit,
            String currentVersion) {}

    record Result(
            Path executionInputLedger,
            String executionInputLedgerSha256,
            boolean executionInputLedgerCreated,
            Path harnessManifest,
            String harnessManifestSha256,
            boolean harnessManifestCreated,
            Path runtimeIdentity,
            String runtimeIdentitySha256,
            boolean runtimeIdentityCreated) {}

    record VerificationResult(
            String executionInputLedgerSha256,
            String harnessManifestSha256,
            String runtimeIdentitySha256,
            String builtTalosArtifactSha256,
            String installedTalosArtifactSha256,
            String installedRuntimeClasspathSha256,
            String modelArtifactObservedSha256,
            String engineExecutableObservedSha256,
            String externalArtifactVerificationScope,
            String activeProcessLaunchReceipt) {}

    static Result write(Request requested) throws IOException {
        return write(requested, QualificationExecutionIdentityWriter::sha256File);
    }

    static Result write(Request requested, ContentHasher externalArtifactHasher)
            throws IOException {
        Objects.requireNonNull(requested, "request");
        Objects.requireNonNull(externalArtifactHasher, "externalArtifactHasher");
        Path packetRoot = requiredAbsoluteDirectory(requested.packetRoot(), "packet root");
        Path repositoryRoot = requiredAbsoluteDirectory(requested.repositoryRoot(), "repository root");
        Path builtTalos = requiredAbsoluteFile(requested.builtTalosArtifact(), "built Talos artifact");
        Path installedTalos = requiredAbsoluteFile(
                requested.installedTalosArtifact(), "installed Talos artifact");
        Path modelArtifact = requiredAbsoluteFile(requested.modelArtifact(), "model artifact");
        Path engineExecutable = requiredAbsoluteFile(
                requested.engineExecutable(), "engine executable");
        Path inputPath = requiredAbsoluteFile(
                requested.harnessInputSpecification(), "harness input specification");
        String currentCommit = requireRevision(requested.currentCommit(), "current commit");
        String currentVersion = requireText(requested.currentVersion(), "current version");

        rejectSameFile(builtTalos, installedTalos,
                "built and installed Talos artifact paths must be distinct");
        rejectSameFile(builtTalos, modelArtifact, "Talos and model artifact paths collide");
        rejectSameFile(builtTalos, engineExecutable, "Talos and engine artifact paths collide");
        rejectSameFile(installedTalos, modelArtifact, "Talos and model artifact paths collide");
        rejectSameFile(installedTalos, engineExecutable,
                "Talos and engine artifact paths collide");
        rejectSameFile(modelArtifact, engineExecutable,
                "model and engine executable paths collide");

        Path campaignPath = packetRoot.resolve("campaign.json");
        QualificationRecords.CampaignManifest campaign = readPacket(
                packetRoot, campaignPath, QualificationRecords.CampaignManifest.class, "campaign");
        requireCampaign(campaign, currentCommit, currentVersion);
        if (containsHistoricalEvidence(packetRoot, campaign)) {
            throw invalid("refusing execution identity generation for a historical or started packet");
        }

        String alias = safeSegment(campaign.candidateAlias(), "candidate alias");
        String lane = safeSegment(campaign.engineLaneId(), "engine lane");
        String machineId = safeSegment(campaign.machineId(), "machine id");
        Path candidatePath = packetRoot.resolve("candidates").resolve(alias).resolve("candidate.json");
        Path calibrationPath = packetRoot.resolve("candidates").resolve(alias)
                .resolve("calibration-rules.json");
        Path guidePath = packetRoot.resolve("candidates").resolve(alias)
                .resolve("calibration-guide.md");
        Path enginePath = packetRoot.resolve("engines").resolve(lane + ".json");
        Path machinePath = packetRoot.resolve("machines").resolve(machineId + ".json");

        byte[] candidateBytes = requiredPacketBytes(packetRoot, candidatePath, "candidate manifest");
        byte[] calibrationBytes = requiredPacketBytes(
                packetRoot, calibrationPath, "calibration rules");
        byte[] engineBytes = requiredPacketBytes(packetRoot, enginePath, "engine manifest");
        byte[] machineBytes = requiredPacketBytes(packetRoot, machinePath, "machine manifest");
        byte[] guideBytes = requiredPacketBytes(packetRoot, guidePath, "calibration guide");
        QualificationRecords.CandidateManifest candidate = strict(
                candidateBytes, QualificationRecords.CandidateManifest.class, "candidate manifest");
        QualificationRecords.CalibrationRules calibration = strict(
                calibrationBytes, QualificationRecords.CalibrationRules.class, "calibration rules");
        QualificationRecords.EngineFacts engine = strict(
                engineBytes, QualificationRecords.EngineFacts.class, "engine manifest");
        QualificationRecords.MachineFacts machine = strict(
                machineBytes, QualificationRecords.MachineFacts.class, "machine manifest");

        ExternalArtifactReceipt modelReceipt = captureExternalArtifact(
                "MODEL_ARTIFACT",
                "model/" + safeSegment(
                        modelArtifact.getFileName().toString(), "model artifact name"),
                "MODEL_GGUF",
                modelArtifact,
                externalArtifactHasher);
        ExternalArtifactReceipt engineReceipt = captureExternalArtifact(
                "ENGINE_EXECUTABLE",
                "engine/" + safeSegment(
                        engineExecutable.getFileName().toString(), "engine executable name"),
                "ENGINE_EXECUTABLE",
                engineExecutable,
                externalArtifactHasher);
        String modelHash = modelReceipt.sha256();
        String engineHash = engineReceipt.sha256();
        String candidateHash = sha256(candidateBytes);
        String calibrationHash = sha256(calibrationBytes);
        String engineManifestHash = sha256(engineBytes);
        String machineHash = sha256(machineBytes);
        validatePacketIdentities(
                campaign, candidate, calibration, engine, machine,
                candidateBytes, calibrationBytes, guideBytes, engineBytes, machineBytes,
                modelArtifact, modelHash, engineExecutable, engineHash, currentCommit);

        JarIdentity builtJar = jarIdentity(builtTalos, currentCommit, currentVersion, "built Talos");
        JarIdentity installedJar = jarIdentity(
                installedTalos, currentCommit, currentVersion, "installed Talos");
        if (!builtJar.sha256().equals(installedJar.sha256())
                || builtJar.sizeBytes() != installedJar.sizeBytes()) {
            throw invalid("built and installed Talos artifact bytes differ");
        }

        HarnessInputSpecification inputs = readInputSpecification(inputPath);
        String finalizerHash = QualificationExecutionIdentity.currentFinalizerImplementationSha256();
        RuntimeArtifactIdentity.ClasspathSnapshot finalizerClasspath =
                QualificationExecutionIdentity.executingFinalizerClasspathSnapshot();
        validateInputSpecification(
                inputs, packetRoot, repositoryRoot, campaign, currentCommit,
                finalizerHash, finalizerClasspath,
                builtJar, installedJar,
                candidateHash, calibrationHash,
                engineManifestHash, machineHash, modelHash, engineHash);
        InstalledRuntimeIdentity installedRuntime =
                validateInstalledRuntimeClasspath(installedTalos, inputs.installedRuntimeClasspath());

        ExecutionInputLedger ledger = new ExecutionInputLedger(
                LEDGER_SCHEMA,
                LEDGER_SCHEMA_VERSION,
                QualificationProtocol.VERSION,
                campaign.campaignId(),
                currentCommit,
                finalizerHash,
                new AggregateIdentity(
                        finalizerClasspath.sha256(),
                        finalizerClasspath.sizeBytes(),
                        finalizerClasspath.entryCount()),
                new AggregateIdentity(
                        installedRuntime.snapshot().sha256(),
                        installedRuntime.snapshot().sizeBytes(),
                        installedRuntime.snapshot().entryCount()),
                List.of(
                        artifact("BUILT_TALOS_JAR", "built/talos.jar", builtJar),
                        artifact("INSTALLED_TALOS_JAR", "installed/lib/talos.jar", installedJar),
                        artifact("CANDIDATE_MANIFEST", "candidates/" + alias + "/candidate.json",
                                candidateBytes.length, candidateHash),
                        artifact("CALIBRATION_RULES",
                                "candidates/" + alias + "/calibration-rules.json",
                                calibrationBytes.length, calibrationHash),
                        artifact("ENGINE_MANIFEST", "engines/" + lane + ".json",
                                engineBytes.length, engineManifestHash),
                        artifact("MACHINE_MANIFEST", "machines/" + machineId + ".json",
                                machineBytes.length, machineHash),
                        installedRuntime.windowsLauncher(),
                        installedRuntime.unixLauncher()),
                List.of(modelReceipt, engineReceipt),
                installedRuntime.entries(),
                normalizedRoles(inputs.harnessRoles(), inputs));
        byte[] ledgerBytes = QualificationJson.bytes(ledger);
        String ledgerHash = sha256(ledgerBytes);

        Map<String, String> harnessFiles = new TreeMap<>(normalized(inputs.packetFiles()));
        if (harnessFiles.putIfAbsent(LEDGER_FILE, ledgerHash) != null) {
            throw invalid("packet harness inputs collide with the generated execution ledger");
        }
        QualificationExecutionIdentity.HarnessManifest harness =
                new QualificationExecutionIdentity.HarnessManifest(
                        QualificationExecutionIdentity.HARNESS_SCHEMA,
                        QualificationExecutionIdentity.HARNESS_SCHEMA_VERSION,
                        QualificationProtocol.VERSION,
                        currentCommit,
                        finalizerHash,
                        finalizerClasspath.sha256(),
                        Map.copyOf(harnessFiles),
                        normalized(inputs.repositoryFiles()),
                        normalized(inputs.buildFiles()));
        byte[] harnessBytes = QualificationJson.bytes(harness);
        String harnessHash = sha256(harnessBytes);

        String preflightHash = QualificationExecutionIdentity.verifyPreflight(
                packetRoot, campaign, candidate, calibration, engine,
                safe(calibration.directPreflightEvidenceManifestSha256()));
        QualificationExecutionIdentity.RuntimeManifest runtime =
                new QualificationExecutionIdentity.RuntimeManifest(
                        QualificationExecutionIdentity.RUNTIME_SCHEMA,
                        QualificationExecutionIdentity.RUNTIME_SCHEMA_VERSION,
                        campaign.campaignId(),
                        currentVersion,
                        currentCommit,
                        QualificationProtocol.VERSION,
                        "JAR",
                        installedJar.sizeBytes(),
                        installedRuntime.snapshot().sha256(),
                        installedRuntime.snapshot().sizeBytes(),
                        installedRuntime.snapshot().entryCount(),
                        builtJar.sha256(),
                        installedJar.sha256(),
                        candidateHash,
                        calibrationHash,
                        engineManifestHash,
                        machineHash,
                        harnessHash,
                        preflightHash,
                        engineHash,
                        modelHash);
        byte[] runtimeBytes = QualificationJson.bytes(runtime);

        Path ledgerPath = packetRoot.resolve(LEDGER_FILE);
        Path harnessPath = packetRoot.resolve(QualificationExecutionIdentity.HARNESS_FILE);
        Path runtimePath = packetRoot.resolve(QualificationExecutionIdentity.RUNTIME_FILE);
        ensureNoCaseVariant(packetRoot, ledgerPath);
        ensureNoCaseVariant(packetRoot, harnessPath);
        ensureNoCaseVariant(packetRoot, runtimePath);
        Existing ledgerExisting = inspectExisting(
                ledgerPath, ledgerBytes, "execution input ledger");
        Existing harnessExisting = inspectExisting(harnessPath, harnessBytes, "harness manifest");
        Existing runtimeExisting = inspectExisting(runtimePath, runtimeBytes, "runtime identity");
        List<Created> created = new ArrayList<>();
        try {
            if (ledgerExisting == Existing.MISSING) {
                createExact(ledgerPath, ledgerBytes, "execution input ledger");
                created.add(new Created(ledgerPath, ledgerBytes));
            }
            if (harnessExisting == Existing.MISSING) {
                createExact(harnessPath, harnessBytes, "harness manifest");
                created.add(new Created(harnessPath, harnessBytes));
            }
            if (runtimeExisting == Existing.MISSING) {
                createExact(runtimePath, runtimeBytes, "runtime identity");
                created.add(new Created(runtimePath, runtimeBytes));
            }
            QualificationExecutionIdentity.verify(
                    packetRoot, campaign, candidate, calibration, machine, engine,
                    new byte[0], false);
        } catch (Throwable failure) {
            rollbackCreated(created, failure);
            throw failure;
        }

        return new Result(
                ledgerPath, ledgerHash, ledgerExisting == Existing.MISSING,
                harnessPath, harnessHash, harnessExisting == Existing.MISSING,
                runtimePath, sha256(runtimeBytes), runtimeExisting == Existing.MISSING);
    }

    /**
     * Revalidates an existing protocol-v6 identity chain without the local operator input
     * specification and without writing packet state. External model and engine artifacts are
     * checked against their capture receipts using the receipt's explicitly bounded metadata
     * continuity contract; this method does not reread multi-gigabyte artifact content.
     */
    static VerificationResult verifyExisting(VerificationRequest requested) throws IOException {
        Objects.requireNonNull(requested, "verification request");
        Path packetRoot = requiredAbsoluteDirectory(requested.packetRoot(), "packet root");
        Path repositoryRoot = requiredAbsoluteDirectory(
                requested.repositoryRoot(), "repository root");
        Path builtTalos = requiredAbsoluteFile(
                requested.builtTalosArtifact(), "built Talos artifact");
        Path installedTalos = requiredAbsoluteFile(
                requested.installedTalosArtifact(), "installed Talos artifact");
        Path modelArtifact = requiredAbsoluteFile(
                requested.modelArtifact(), "model artifact");
        Path engineExecutable = requiredAbsoluteFile(
                requested.engineExecutable(), "engine executable");
        String currentCommit = requireRevision(requested.currentCommit(), "current commit");
        String currentVersion = requireText(requested.currentVersion(), "current version");

        rejectSameFile(builtTalos, installedTalos,
                "built and installed Talos artifact paths must be distinct");
        rejectSameFile(builtTalos, modelArtifact, "Talos and model artifact paths collide");
        rejectSameFile(builtTalos, engineExecutable, "Talos and engine artifact paths collide");
        rejectSameFile(installedTalos, modelArtifact, "Talos and model artifact paths collide");
        rejectSameFile(installedTalos, engineExecutable,
                "Talos and engine artifact paths collide");
        rejectSameFile(modelArtifact, engineExecutable,
                "model and engine executable paths collide");

        QualificationRecords.CampaignManifest campaign = readPacket(
                packetRoot, packetRoot.resolve("campaign.json"),
                QualificationRecords.CampaignManifest.class, "campaign");
        requireCampaign(campaign, currentCommit, currentVersion);
        String alias = safeSegment(campaign.candidateAlias(), "candidate alias");
        String lane = safeSegment(campaign.engineLaneId(), "engine lane");
        String machineId = safeSegment(campaign.machineId(), "machine id");
        Path candidatePath = packetRoot.resolve("candidates").resolve(alias)
                .resolve("candidate.json");
        Path calibrationPath = packetRoot.resolve("candidates").resolve(alias)
                .resolve("calibration-rules.json");
        Path guidePath = packetRoot.resolve("candidates").resolve(alias)
                .resolve("calibration-guide.md");
        Path enginePath = packetRoot.resolve("engines").resolve(lane + ".json");
        Path machinePath = packetRoot.resolve("machines").resolve(machineId + ".json");
        byte[] candidateBytes = requiredPacketBytes(packetRoot, candidatePath, "candidate manifest");
        byte[] calibrationBytes = requiredPacketBytes(
                packetRoot, calibrationPath, "calibration rules");
        byte[] guideBytes = requiredPacketBytes(packetRoot, guidePath, "calibration guide");
        byte[] engineBytes = requiredPacketBytes(packetRoot, enginePath, "engine manifest");
        byte[] machineBytes = requiredPacketBytes(packetRoot, machinePath, "machine manifest");
        QualificationRecords.CandidateManifest candidate = strict(
                candidateBytes, QualificationRecords.CandidateManifest.class, "candidate manifest");
        QualificationRecords.CalibrationRules calibration = strict(
                calibrationBytes, QualificationRecords.CalibrationRules.class,
                "calibration rules");
        QualificationRecords.EngineFacts engine = strict(
                engineBytes, QualificationRecords.EngineFacts.class, "engine manifest");
        QualificationRecords.MachineFacts machine = strict(
                machineBytes, QualificationRecords.MachineFacts.class, "machine manifest");

        byte[] runtimeBytes = requiredPacketBytes(
                packetRoot, packetRoot.resolve(QualificationExecutionIdentity.RUNTIME_FILE),
                "runtime identity");
        byte[] harnessBytes = requiredPacketBytes(
                packetRoot, packetRoot.resolve(QualificationExecutionIdentity.HARNESS_FILE),
                "harness manifest");
        QualificationExecutionIdentity.RuntimeManifest runtime = strict(
                runtimeBytes, QualificationExecutionIdentity.RuntimeManifest.class,
                "runtime identity");
        QualificationExecutionIdentity.HarnessManifest harness = strict(
                harnessBytes, QualificationExecutionIdentity.HarnessManifest.class,
                "harness manifest");

        validatePacketIdentities(
                campaign, candidate, calibration, engine, machine,
                candidateBytes, calibrationBytes, guideBytes, engineBytes, machineBytes,
                modelArtifact, runtime.modelArtifactSha256(),
                engineExecutable, runtime.engineExecutableSha256(), currentCommit);
        QualificationExecutionIdentity.verify(
                packetRoot, campaign, candidate, calibration, machine, engine,
                new byte[0], false);
        ExecutionInputLedger ledger = verifyLedger(
                packetRoot, campaign, candidate, engine, runtime, harness);

        JarIdentity builtJar = jarIdentity(
                builtTalos, currentCommit, currentVersion, "built Talos");
        JarIdentity installedJar = jarIdentity(
                installedTalos, currentCommit, currentVersion, "installed Talos");
        if (!builtJar.sha256().equals(installedJar.sha256())
                || builtJar.sizeBytes() != installedJar.sizeBytes()) {
            throw invalid("built and installed Talos artifact bytes differ");
        }
        equalText(runtime.runtimeArtifactKind(), "JAR", "runtime artifact kind mismatch");
        equalHash(builtJar.sha256(), runtime.builtTalosJarSha256(),
                "live built Talos artifact hash mismatch");
        equalHash(installedJar.sha256(), runtime.installedTalosJarSha256(),
                "live installed Talos artifact hash mismatch");
        if (runtime.runtimeArtifactSizeBytes() != installedJar.sizeBytes()) {
            throw invalid("live installed Talos artifact size mismatch");
        }

        validateExistingHarnessInputs(packetRoot, repositoryRoot, harness);
        List<ClasspathInput> classpathInputs = classpathInputsFromLedger(
                ledger.installedRuntimeClasspath());
        InstalledRuntimeIdentity installedRuntime = validateInstalledRuntimeClasspath(
                installedTalos, classpathInputs);
        if (!installedRuntime.entries().equals(ledger.installedRuntimeClasspath())) {
            throw invalid("installed runtime classpath entries differ from the execution ledger");
        }
        validateAggregate(
                ledger.installedRuntimeClasspathIdentity(), installedRuntime.snapshot(),
                "live installed runtime classpath");
        equalHash(installedRuntime.snapshot().sha256(), runtime.runtimeClasspathSha256(),
                "live installed runtime classpath hash mismatch");
        if (installedRuntime.snapshot().sizeBytes() != runtime.runtimeClasspathSizeBytes()
                || installedRuntime.snapshot().entryCount()
                        != runtime.runtimeClasspathEntryCount()) {
            throw invalid("live installed runtime classpath size/count mismatch");
        }

        Map<String, LedgerArtifact> artifacts = validateArtifacts(ledger.artifacts());
        validateArtifact(artifacts, "BUILT_TALOS_JAR", "built/talos.jar",
                builtJar.sha256(), builtJar.sizeBytes());
        validateArtifact(artifacts, "INSTALLED_TALOS_JAR", "installed/lib/talos.jar",
                installedJar.sha256(), installedJar.sizeBytes());
        validateArtifact(artifacts, "CANDIDATE_MANIFEST",
                "candidates/" + alias + "/candidate.json",
                sha256(candidateBytes), candidateBytes.length);
        validateArtifact(artifacts, "CALIBRATION_RULES",
                "candidates/" + alias + "/calibration-rules.json",
                sha256(calibrationBytes), calibrationBytes.length);
        validateArtifact(artifacts, "ENGINE_MANIFEST", "engines/" + lane + ".json",
                sha256(engineBytes), engineBytes.length);
        validateArtifact(artifacts, "MACHINE_MANIFEST", "machines/" + machineId + ".json",
                sha256(machineBytes), machineBytes.length);
        validateArtifact(artifacts, "WINDOWS_LAUNCHER", "installed/bin/talos.bat",
                installedRuntime.windowsLauncher().sha256(),
                installedRuntime.windowsLauncher().sizeBytes());
        validateArtifact(artifacts, "UNIX_LAUNCHER", "installed/bin/talos",
                installedRuntime.unixLauncher().sha256(),
                installedRuntime.unixLauncher().sizeBytes());

        Path declaredModel = modelPath(engine.launchArguments(), "engine launch arguments");
        if (!Files.isSameFile(declaredModel, modelArtifact)) {
            throw invalid("explicit model artifact path differs from the engine manifest");
        }
        Path declaredExecutable = requiredAbsoluteFile(
                parsePath(engine.executable(), "engine manifest executable"),
                "engine manifest executable");
        if (!Files.isSameFile(declaredExecutable, engineExecutable)) {
            throw invalid("explicit engine executable path differs from the engine manifest");
        }

        return new VerificationResult(
                sha256(requiredPacketBytes(
                        packetRoot, packetRoot.resolve(LEDGER_FILE), "execution input ledger")),
                sha256(harnessBytes),
                sha256(runtimeBytes),
                builtJar.sha256(),
                installedJar.sha256(),
                installedRuntime.snapshot().sha256(),
                runtime.modelArtifactSha256(),
                runtime.engineExecutableSha256(),
                EXTERNAL_ARTIFACT_VERIFICATION_SCOPE,
                ACTIVE_PROCESS_LAUNCH_RECEIPT);
    }

    private static void requireCampaign(
            QualificationRecords.CampaignManifest campaign,
            String currentCommit,
            String currentVersion) {
        if (!QualificationRecords.CAMPAIGN_SCHEMA.equals(campaign.schemaName())
                || campaign.schemaVersion() != QualificationRecords.SCHEMA_VERSION
                || campaign.protocolVersion() != QualificationProtocol.VERSION) {
            throw invalid("execution identity producer requires a protocol-v6 campaign");
        }
        equalRevision(campaign.talosCommit(), currentCommit,
                "campaign and current commit differ");
        equalText(campaign.talosVersion(), currentVersion,
                "campaign and current version differ");
        equalHash(campaign.scenarioBankSha256(), QualificationScenarioBank.loadDefault().sha256(),
                "campaign scenario-bank hash differs from the executing protocol-v6 bank");
        requireText(campaign.campaignId(), "campaign id");
        requireText(campaign.configId(), "configuration id");
        try {
            QualificationNamespace.requireSegment(campaign.campaignId(), "campaign id");
            QualificationNamespace.requireSegment(campaign.candidateAlias(), "candidate alias");
            QualificationNamespace.requireSegment(campaign.machineId(), "machine id");
            QualificationNamespace.requireSegment(campaign.engineLaneId(), "engine lane");
            QualificationNamespace.requireSegment(campaign.configId(), "configuration id");
            for (String repetition : campaign.repetitions()) {
                QualificationNamespace.requireSegment(repetition, "campaign repetition id");
            }
        } catch (IllegalArgumentException unsafe) {
            throw invalid(unsafe.getMessage(), unsafe);
        }
    }

    private static void validatePacketIdentities(
            QualificationRecords.CampaignManifest campaign,
            QualificationRecords.CandidateManifest candidate,
            QualificationRecords.CalibrationRules calibration,
            QualificationRecords.EngineFacts engine,
            QualificationRecords.MachineFacts machine,
            byte[] candidateBytes,
            byte[] calibrationBytes,
            byte[] guideBytes,
            byte[] engineBytes,
            byte[] machineBytes,
            Path modelArtifact,
            String modelHash,
            Path engineExecutable,
            String engineHash,
            String currentCommit) throws IOException {
        if (!QualificationRecords.CANDIDATE_SCHEMA.equals(candidate.schemaName())
                || candidate.schemaVersion() != QualificationRecords.SCHEMA_VERSION
                || !QualificationRecords.CALIBRATION_SCHEMA.equals(calibration.schemaName())
                || calibration.schemaVersion() != QualificationRecords.SCHEMA_VERSION
                || !QualificationRecords.ENGINE_SCHEMA.equals(engine.schemaName())
                || engine.schemaVersion() != QualificationRecords.SCHEMA_VERSION
                || !QualificationRecords.MACHINE_SCHEMA.equals(machine.schemaName())
                || machine.schemaVersion() != QualificationRecords.SCHEMA_VERSION) {
            throw invalid("packet identity record schema mismatch");
        }
        equalText(candidate.alias(), campaign.candidateAlias(), "candidate alias mismatch");
        equalText(calibration.candidateAlias(), campaign.candidateAlias(),
                "calibration candidate alias mismatch");
        equalText(calibration.configurationId(), campaign.configId(),
                "calibration configuration mismatch");
        equalText(engine.laneId(), campaign.engineLaneId(), "engine lane mismatch");
        equalText(machine.machineId(), campaign.machineId(), "machine identity mismatch");
        equalRevision(calibration.talosCommit(), currentCommit,
                "calibration and current commit differ");
        if (calibration.protocolVersion() != QualificationProtocol.VERSION) {
            throw invalid("calibration protocol version mismatch");
        }
        equalHash(sha256(calibrationBytes), campaign.calibrationRulesSha256(),
                "campaign calibration hash mismatch");
        equalHash(sha256(guideBytes), campaign.calibrationGuideSha256(),
                "campaign calibration-guide hash mismatch");
        requireHash(sha256(candidateBytes), "candidate manifest");
        requireHash(sha256(engineBytes), "engine manifest");
        requireHash(sha256(machineBytes), "machine manifest");

        equalText(candidate.repository(), calibration.repository(),
                "candidate/calibration repository mismatch");
        equalRevision(candidate.revision(), calibration.revision(),
                "candidate/calibration revision mismatch");
        equalText(candidate.artifact(), calibration.artifact(),
                "candidate/calibration artifact mismatch");
        equalText(candidate.artifact(), modelArtifact.getFileName().toString(),
                "explicit model artifact filename mismatch");
        equalHash(candidate.sha256(), modelHash, "candidate model artifact hash mismatch");
        equalHash(calibration.artifactSha256(), modelHash,
                "calibration model artifact hash mismatch");
        long modelSize = Files.size(modelArtifact);
        if (candidate.sizeBytes() != modelSize || calibration.artifactSizeBytes() != modelSize) {
            throw invalid("model artifact size mismatch");
        }
        equalHash(engine.sha256(), engineHash, "engine executable hash mismatch");
        equalHash(calibration.executableSha256(), engineHash,
                "calibration engine executable hash mismatch");
        Path declaredExecutable = requiredAbsoluteFile(
                parsePath(engine.executable(), "engine manifest executable"),
                "engine manifest executable");
        if (!Files.isSameFile(declaredExecutable, engineExecutable)) {
            throw invalid("explicit engine executable path differs from the engine manifest");
        }
        requireModelArgument(calibration.launchArguments(), modelArtifact,
                "calibration launch arguments");
        requireModelArgument(calibration.verificationLaunchArguments(), modelArtifact,
                "calibration verification launch arguments");
        if (!calibration.directPreflightLaunchArguments().isEmpty()) {
            requireModelArgument(calibration.directPreflightLaunchArguments(), modelArtifact,
                    "calibration direct-preflight launch arguments");
        }
        if (!engine.launchArguments().equals(calibration.launchArguments())
                || !engine.verificationLaunchArguments().equals(
                calibration.verificationLaunchArguments())) {
            throw invalid("engine and calibration launch arguments differ");
        }
    }

    private static void requireModelArgument(List<String> arguments, Path model, String label)
            throws IOException {
        List<Path> values = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if ("-m".equals(argument) || "--model".equals(argument)) {
                if (++i >= arguments.size()) throw invalid(label + " has a missing model value");
                values.add(requiredAbsoluteFile(parsePath(arguments.get(i), label + " model"),
                        label + " model"));
            }
        }
        if (values.size() != 1 || !Files.isSameFile(values.getFirst(), model)) {
            throw invalid(label + " does not bind exactly the explicit model artifact");
        }
    }

    private static HarnessInputSpecification readInputSpecification(Path path) throws IOException {
        try {
            return QualificationJson.read(
                    NoFollowPathPolicy.readAllBytes(path),
                    HarnessInputSpecification.class);
        } catch (RuntimeException failure) {
            throw invalid("harness input specification is invalid", failure);
        }
    }

    private static void validateInputSpecification(
            HarnessInputSpecification inputs,
            Path packetRoot,
            Path repositoryRoot,
            QualificationRecords.CampaignManifest campaign,
            String currentCommit,
            String finalizerHash,
            RuntimeArtifactIdentity.ClasspathSnapshot finalizerClasspath,
            JarIdentity builtJar,
            JarIdentity installedJar,
            String candidateHash,
            String calibrationHash,
            String engineManifestHash,
            String machineHash,
            String modelHash,
            String engineHash) throws IOException {
        if (!INPUT_SCHEMA.equals(inputs.schemaName())
                || inputs.schemaVersion() != INPUT_SCHEMA_VERSION
                || inputs.protocolVersion() != QualificationProtocol.VERSION) {
            throw invalid("harness input specification schema mismatch");
        }
        equalRevision(inputs.talosCommit(), currentCommit,
                "harness input specification and current commit differ");
        equalRevision(inputs.talosCommit(), campaign.talosCommit(),
                "harness input specification and campaign commit differ");
        equalHash(inputs.finalizerImplementationSha256(), finalizerHash,
                "harness input specification and executing finalizer differ");
        if (!finalizerClasspath.exact()) {
            throw invalid("executing finalizer classpath identity is unavailable");
        }
        equalHash(inputs.finalizerClasspathSha256(), finalizerClasspath.sha256(),
                "harness input specification and executing finalizer classpath differ");
        equalHash(inputs.builtTalosArtifactSha256(), builtJar.sha256(),
                "frozen built Talos artifact hash mismatch");
        equalHash(inputs.installedTalosArtifactSha256(), installedJar.sha256(),
                "frozen installed Talos artifact hash mismatch");
        equalHash(inputs.candidateManifestSha256(), candidateHash,
                "frozen candidate manifest hash mismatch");
        equalHash(inputs.calibrationRulesSha256(), calibrationHash,
                "frozen calibration rules hash mismatch");
        equalHash(inputs.engineManifestSha256(), engineManifestHash,
                "frozen engine manifest hash mismatch");
        equalHash(inputs.machineManifestSha256(), machineHash,
                "frozen machine manifest hash mismatch");
        equalHash(inputs.modelArtifactSha256(), modelHash,
                "frozen model artifact hash mismatch");
        equalHash(inputs.engineExecutableSha256(), engineHash,
                "frozen engine executable hash mismatch");
        if (inputs.installedRuntimeClasspath() == null
                || inputs.installedRuntimeClasspath().isEmpty()) {
            throw invalid("installed runtime classpath is missing");
        }
        requireNonempty(inputs.packetFiles(), "packet harness files");
        requireNonempty(inputs.repositoryFiles(), "repository harness files");
        requireNonempty(inputs.buildFiles(), "build harness files");
        normalizedRoles(inputs.harnessRoles(), inputs);

        Set<String> identities = new HashSet<>();
        verifyDeclaredFiles(packetRoot, packetRoot, inputs.packetFiles(), "packet", identities);
        verifyDeclaredFiles(packetRoot, repositoryRoot, inputs.repositoryFiles(),
                "repository", identities);
        verifyDeclaredFiles(packetRoot, repositoryRoot, inputs.buildFiles(), "build", identities);
        for (String path : inputs.repositoryFiles().keySet()) {
            if (safeLogicalPath(path, "repository file").startsWith("build/")) {
                throw invalid("repository harness files must not be classified as build files");
            }
        }
        for (String path : inputs.buildFiles().keySet()) {
            if (!safeLogicalPath(path, "build file").startsWith("build/")) {
                throw invalid("build harness file paths must begin with build/");
            }
        }
    }

    static Map<String, String> requiredHarnessRoles() {
        return Map.copyOf(new TreeMap<>(REQUIRED_HARNESS_ROLES));
    }

    static ExecutionInputLedger verifyLedger(
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            QualificationRecords.CandidateManifest candidate,
            QualificationRecords.EngineFacts engine,
            QualificationExecutionIdentity.RuntimeManifest runtime,
            QualificationExecutionIdentity.HarnessManifest harness) throws IOException {
        String declaredLedgerHash = null;
        for (Map.Entry<String, String> entry : harness.files().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(LEDGER_FILE)) {
                if (!entry.getKey().equals(LEDGER_FILE) || declaredLedgerHash != null) {
                    throw invalid("harness manifest has a missing or case-colliding execution ledger");
                }
                declaredLedgerHash = requireHash(entry.getValue(), "execution input ledger");
            }
        }
        if (declaredLedgerHash == null) {
            throw invalid("harness manifest does not bind the mandatory execution input ledger");
        }
        Path ledgerPath = packetRoot.resolve(LEDGER_FILE);
        byte[] ledgerBytes = requiredPacketBytes(
                packetRoot, ledgerPath, "execution input ledger");
        equalHash(sha256(ledgerBytes), declaredLedgerHash,
                "execution input ledger hash mismatch");
        ExecutionInputLedger ledger;
        try {
            ledger = QualificationJson.read(ledgerBytes, ExecutionInputLedger.class);
        } catch (RuntimeException malformed) {
            throw invalid("execution input ledger is invalid", malformed);
        }
        if (!LEDGER_SCHEMA.equals(ledger.schemaName())
                || ledger.schemaVersion() != LEDGER_SCHEMA_VERSION
                || ledger.protocolVersion() != QualificationProtocol.VERSION) {
            throw invalid("execution input ledger schema mismatch");
        }
        equalText(ledger.campaignId(), campaign.campaignId(),
                "execution input ledger campaign mismatch");
        equalRevision(ledger.talosCommit(), campaign.talosCommit(),
                "execution input ledger commit mismatch");
        equalHash(ledger.finalizerImplementationSha256(),
                harness.finalizerImplementationSha256(),
                "execution input ledger finalizer mismatch");
        RuntimeArtifactIdentity.ClasspathSnapshot currentFinalizer =
                QualificationExecutionIdentity.executingFinalizerClasspathSnapshot();
        validateAggregate(
                ledger.finalizerClasspath(), currentFinalizer,
                "execution input ledger finalizer classpath");
        equalHash(ledger.finalizerClasspath().sha256(), harness.finalizerClasspathSha256(),
                "execution input ledger and harness finalizer classpath differ");

        Map<String, LedgerArtifact> artifacts = validateArtifacts(ledger.artifacts());
        validateArtifact(artifacts, "BUILT_TALOS_JAR", "built/talos.jar",
                runtime.builtTalosJarSha256(), runtime.runtimeArtifactSizeBytes());
        validateArtifact(artifacts, "INSTALLED_TALOS_JAR", "installed/lib/talos.jar",
                runtime.installedTalosJarSha256(), runtime.runtimeArtifactSizeBytes());
        String alias = safeSegment(campaign.candidateAlias(), "candidate alias");
        String lane = safeSegment(campaign.engineLaneId(), "engine lane");
        String machine = safeSegment(campaign.machineId(), "machine id");
        validateArtifact(artifacts, "CANDIDATE_MANIFEST",
                "candidates/" + alias + "/candidate.json",
                runtime.candidateManifestSha256(), -1L);
        validateArtifact(artifacts, "CALIBRATION_RULES",
                "candidates/" + alias + "/calibration-rules.json",
                runtime.calibrationRulesSha256(), -1L);
        validateArtifact(artifacts, "ENGINE_MANIFEST", "engines/" + lane + ".json",
                runtime.engineManifestSha256(), -1L);
        validateArtifact(artifacts, "MACHINE_MANIFEST", "machines/" + machine + ".json",
                runtime.machineManifestSha256(), -1L);
        validateArtifact(artifacts, "WINDOWS_LAUNCHER", "installed/bin/talos.bat", null, -1L);
        validateArtifact(artifacts, "UNIX_LAUNCHER", "installed/bin/talos", null, -1L);
        if (artifacts.size() != 8) {
            throw invalid("execution input ledger artifact role inventory is not exact");
        }

        List<ExternalArtifactReceipt> receipts = ledger.externalArtifacts();
        if (receipts == null || receipts.size() != 2) {
            throw invalid("execution input ledger external artifact receipts are incomplete");
        }
        Map<String, ExternalArtifactReceipt> receiptByRole = new TreeMap<>();
        for (ExternalArtifactReceipt receipt : receipts) {
            if (receipt == null || receiptByRole.putIfAbsent(receipt.role(), receipt) != null) {
                throw invalid("execution input ledger external artifact receipt roles collide");
            }
        }
        Path modelPath = modelPath(engine.launchArguments(), "engine launch arguments");
        Path verificationModel = modelPath(
                engine.verificationLaunchArguments(), "engine verification launch arguments");
        if (!Files.isSameFile(modelPath, verificationModel)) {
            throw invalid("engine launch modes select different model artifacts");
        }
        ExternalArtifactReceipt modelReceipt = receiptByRole.get("MODEL_ARTIFACT");
        ExternalArtifactReceipt engineReceipt = receiptByRole.get("ENGINE_EXECUTABLE");
        if (modelReceipt == null || engineReceipt == null || receiptByRole.size() != 2) {
            throw invalid("execution input ledger external artifact receipt roles are not exact");
        }
        equalText(modelReceipt.logicalToken(),
                "model/" + safeSegment(candidate.artifact(), "model artifact name"),
                "model receipt logical token mismatch");
        verifyExternalArtifactReceipt(
                modelReceipt, modelPath, runtime.modelArtifactSha256(),
                candidate.sizeBytes(), "MODEL_GGUF");
        Path executable = requiredAbsoluteFile(
                parsePath(engine.executable(), "engine executable"), "engine executable");
        equalText(engineReceipt.logicalToken(),
                "engine/" + safeSegment(executable.getFileName().toString(),
                        "engine executable name"),
                "engine receipt logical token mismatch");
        verifyExternalArtifactReceipt(
                engineReceipt, executable, runtime.engineExecutableSha256(),
                -1L, "ENGINE_EXECUTABLE");

        List<LedgerClasspathEntry> entries = ledger.installedRuntimeClasspath();
        if (entries == null || entries.isEmpty()) {
            throw invalid("execution input ledger runtime classpath entries are missing");
        }
        List<RuntimeArtifactIdentity.ClasspathEntryIdentity> identities = entries.stream()
                .map(entry -> new RuntimeArtifactIdentity.ClasspathEntryIdentity(
                        entry.ordinal(), entry.kind(), entry.logicalToken(), entry.sha256(),
                        entry.sizeBytes(), entry.fileCount()))
                .toList();
        RuntimeArtifactIdentity.ClasspathSnapshot reconstructed =
                RuntimeArtifactIdentity.identifyClasspathEntries(identities);
        if (!reconstructed.exact()) {
            throw invalid("execution input ledger runtime classpath entries are invalid");
        }
        validateAggregate(
                ledger.installedRuntimeClasspathIdentity(), reconstructed,
                "execution input ledger runtime classpath aggregate");
        equalHash(reconstructed.sha256(), runtime.runtimeClasspathSha256(),
                "execution input ledger and runtime classpath hash differ");
        if (reconstructed.sizeBytes() != runtime.runtimeClasspathSizeBytes()
                || reconstructed.entryCount() != runtime.runtimeClasspathEntryCount()) {
            throw invalid("execution input ledger and runtime classpath size/count differ");
        }
        validateLedgerRoles(ledger.harnessRoles(), harness);
        return ledger;
    }

    private static void validateAggregate(
            AggregateIdentity declared,
            RuntimeArtifactIdentity.ClasspathSnapshot actual,
            String label) {
        if (declared == null || !actual.exact()) throw invalid(label + " is unavailable");
        equalHash(declared.sha256(), actual.sha256(), label + " hash mismatch");
        if (declared.sizeBytes() != actual.sizeBytes()
                || declared.entryCount() != actual.entryCount()) {
            throw invalid(label + " size/count mismatch");
        }
    }

    private static Map<String, LedgerArtifact> validateArtifacts(List<LedgerArtifact> declared) {
        if (declared == null || declared.isEmpty()) {
            throw invalid("execution input ledger artifact identities are missing");
        }
        Map<String, LedgerArtifact> byRole = new TreeMap<>();
        Set<String> tokens = new HashSet<>();
        for (LedgerArtifact artifact : declared) {
            if (artifact == null) throw invalid("execution input ledger artifact is null");
            String role = requireText(artifact.role(), "execution input artifact role");
            if (!role.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw invalid("execution input artifact role is invalid");
            }
            String token = safeLogicalPath(
                    artifact.logicalToken(), "execution input artifact logical token");
            if (!tokens.add(token.toLowerCase(Locale.ROOT))
                    || byRole.putIfAbsent(role, artifact) != null) {
                throw invalid("execution input artifact roles or tokens collide");
            }
            requireHash(artifact.sha256(), role);
            if (artifact.sizeBytes() <= 0) throw invalid(role + " size is invalid");
        }
        return byRole;
    }

    private static void validateArtifact(
            Map<String, LedgerArtifact> artifacts,
            String role,
            String token,
            String expectedHash,
            long expectedSize) {
        LedgerArtifact artifact = artifacts.get(role);
        if (artifact == null) throw invalid("execution input artifact role is missing: " + role);
        equalText(artifact.logicalToken(), token, role + " logical token mismatch");
        if (expectedHash != null) {
            equalHash(artifact.sha256(), expectedHash, role + " hash mismatch");
        }
        if (expectedSize >= 0 && artifact.sizeBytes() != expectedSize) {
            throw invalid(role + " size mismatch");
        }
    }

    private static void validateLedgerRoles(
            Map<String, String> roles,
            QualificationExecutionIdentity.HarnessManifest harness) {
        if (roles == null || roles.isEmpty()) throw invalid("execution ledger harness roles are missing");
        Set<String> frozenPaths = new HashSet<>();
        frozenPaths.addAll(harness.files().keySet());
        frozenPaths.addAll(harness.repositoryFiles().keySet());
        frozenPaths.addAll(harness.buildFiles().keySet());
        for (Map.Entry<String, String> entry : roles.entrySet()) {
            String role = requireText(entry.getKey(), "execution ledger harness role");
            if (!role.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw invalid("execution ledger harness role is invalid");
            }
            String path = safeLogicalPath(entry.getValue(), "execution ledger harness role");
            if (!frozenPaths.contains(path)) {
                throw invalid("execution ledger harness role is not frozen: " + role);
            }
        }
        for (Map.Entry<String, String> required : REQUIRED_HARNESS_ROLES.entrySet()) {
            if (!Objects.equals(roles.get(required.getKey()), required.getValue())) {
                throw invalid("required execution ledger harness role is missing or misbound: "
                        + required.getKey());
            }
        }
    }

    private static Path modelPath(List<String> arguments, String label) throws IOException {
        String selected = null;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (!"-m".equals(argument) && !"--model".equals(argument)) continue;
            if (selected != null || ++index >= arguments.size()) {
                throw invalid(label + " does not bind exactly one model artifact");
            }
            selected = arguments.get(index);
        }
        if (selected == null) throw invalid(label + " does not bind a model artifact");
        return requiredAbsoluteFile(parsePath(selected, label + " model"), label + " model");
    }

    private static Map<String, String> normalizedRoles(
            Map<String, String> roles,
            HarnessInputSpecification inputs) {
        if (roles == null || roles.isEmpty()) throw invalid("harness role inventory is missing");
        TreeMap<String, String> normalized = new TreeMap<>();
        Set<String> declaredPaths = new HashSet<>();
        declaredPaths.addAll(inputs.packetFiles().keySet());
        declaredPaths.addAll(inputs.repositoryFiles().keySet());
        declaredPaths.addAll(inputs.buildFiles().keySet());
        for (Map.Entry<String, String> entry : roles.entrySet()) {
            String role = requireText(entry.getKey(), "harness role");
            if (!role.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw invalid("harness role name is invalid: " + role);
            }
            String path = safeLogicalPath(entry.getValue(), "harness role " + role);
            if (!declaredPaths.contains(path)) {
                throw invalid("harness role path is not present in the frozen file inventory: " + role);
            }
            if (normalized.putIfAbsent(role, path) != null) {
                throw invalid("duplicate harness role: " + role);
            }
        }
        for (Map.Entry<String, String> required : REQUIRED_HARNESS_ROLES.entrySet()) {
            if (!Objects.equals(normalized.get(required.getKey()), required.getValue())) {
                throw invalid("required harness role is missing or misbound: " + required.getKey());
            }
        }
        return Map.copyOf(normalized);
    }

    private static List<ClasspathInput> classpathInputsFromLedger(
            List<LedgerClasspathEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw invalid("execution input ledger runtime classpath entries are missing");
        }
        List<ClasspathInput> result = new ArrayList<>();
        Set<String> tokens = new HashSet<>();
        int expectedOrdinal = 1;
        for (LedgerClasspathEntry entry : entries) {
            if (entry == null
                    || entry.ordinal() != expectedOrdinal
                    || !"FILE".equals(entry.kind())
                    || entry.fileCount() != 1
                    || entry.sizeBytes() <= 0) {
                throw invalid("execution input ledger runtime classpath entry is invalid");
            }
            String token = safeSegment(
                    entry.logicalToken(), "execution input ledger runtime classpath token");
            if (!tokens.add(token.toLowerCase(Locale.ROOT))) {
                throw invalid("execution input ledger runtime classpath tokens collide");
            }
            result.add(new ClasspathInput("lib/" + token,
                    requireHash(entry.sha256(), "execution input ledger runtime classpath")));
            expectedOrdinal++;
        }
        return List.copyOf(result);
    }

    private static void validateExistingHarnessInputs(
            Path packetRoot,
            Path repositoryRoot,
            QualificationExecutionIdentity.HarnessManifest harness) throws IOException {
        requireNonempty(harness.files(), "packet harness files");
        requireNonempty(harness.repositoryFiles(), "repository harness files");
        requireNonempty(harness.buildFiles(), "build harness files");
        Set<String> identities = new HashSet<>();
        verifyExistingDeclaredFiles(
                packetRoot, harness.files(), "packet", identities);
        verifyExistingDeclaredFiles(
                repositoryRoot, harness.repositoryFiles(), "repository", identities);
        verifyExistingDeclaredFiles(
                repositoryRoot, harness.buildFiles(), "build", identities);
        for (String path : harness.repositoryFiles().keySet()) {
            if (safeLogicalPath(path, "repository file").startsWith("build/")) {
                throw invalid("repository harness files must not be classified as build files");
            }
        }
        for (String path : harness.buildFiles().keySet()) {
            if (!safeLogicalPath(path, "build file").startsWith("build/")) {
                throw invalid("build harness file paths must begin with build/");
            }
        }
    }

    private static void verifyExistingDeclaredFiles(
            Path contentRoot,
            Map<String, String> files,
            String category,
            Set<String> identities) throws IOException {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String logical = safeLogicalPath(entry.getKey(), category + " harness file");
            String collisionKey = Normalizer.normalize(logical, Normalizer.Form.NFC)
                    .toLowerCase(Locale.ROOT);
            if (!identities.add(collisionKey)) {
                throw invalid("harness input paths collide across categories: " + logical);
            }
            if (logical.equalsIgnoreCase(QualificationExecutionIdentity.HARNESS_FILE)
                    || logical.equalsIgnoreCase(QualificationExecutionIdentity.RUNTIME_FILE)) {
                throw invalid("harness input inventory names a generated identity output");
            }
            Path file = requiredRootFile(
                    contentRoot, contentRoot.resolve(logical), category + " harness file");
            equalHash(sha256File(file),
                    requireHash(entry.getValue(), category + " harness file"),
                    category + " harness input hash mismatch: " + logical);
        }
    }

    private static InstalledRuntimeIdentity validateInstalledRuntimeClasspath(
            Path installedTalos,
            List<ClasspathInput> declared) throws IOException {
        Path lib = installedTalos.getParent();
        if (lib == null || !"lib".equalsIgnoreCase(lib.getFileName().toString())) {
            throw invalid("installed Talos artifact must be the distribution lib/talos.jar");
        }
        Path canonicalTalos = requiredRootFile(
                lib, lib.resolve("talos.jar"), "installed Talos artifact");
        if (!Files.isSameFile(installedTalos, canonicalTalos)) {
            throw invalid("installed Talos artifact must be the distribution lib/talos.jar");
        }
        Path appRoot = lib.getParent();
        if (appRoot == null) throw invalid("installed Talos distribution root is unavailable");
        Path windowsLauncher = requiredRootFile(
                appRoot, appRoot.resolve("bin/talos.bat"), "Windows Talos launcher");
        Path unixLauncher = requiredRootFile(
                appRoot, appRoot.resolve("bin/talos"), "Unix Talos launcher");

        List<String> windowsOrder = launcherClasspath(
                windowsLauncher, "set CLASSPATH=", ";", "%APP_HOME%\\lib\\", "Windows");
        List<String> unixOrder = launcherClasspath(
                unixLauncher, "CLASSPATH=", ":", "$APP_HOME/lib/", "Unix");
        if (!windowsOrder.equals(unixOrder)) {
            throw invalid("Windows and Unix launchers declare different runtime classpaths");
        }

        List<String> declaredOrder = new ArrayList<>();
        List<Path> orderedPaths = new ArrayList<>();
        List<LedgerClasspathEntry> ledgerEntries = new ArrayList<>();
        Set<String> collisionKeys = new HashSet<>();
        for (ClasspathInput input : declared) {
            if (input == null) throw invalid("installed runtime classpath entry is null");
            String logical = safeLogicalPath(input.path(), "installed runtime classpath");
            if (!logical.startsWith("lib/") || logical.substring(4).contains("/")) {
                throw invalid("installed runtime classpath entries must be direct lib files");
            }
            String collisionKey = logical.toLowerCase(Locale.ROOT);
            if (!collisionKeys.add(collisionKey)) {
                throw invalid("installed runtime classpath has duplicate or colliding entries");
            }
            String expected = requireHash(input.sha256(), "installed runtime classpath entry");
            Path path = requiredRootFile(appRoot, appRoot.resolve(logical),
                    "installed runtime classpath entry");
            equalHash(sha256File(path), expected,
                    "installed runtime classpath hash mismatch: " + logical);
            declaredOrder.add(logical);
            orderedPaths.add(path);
            ledgerEntries.add(new LedgerClasspathEntry(
                    ledgerEntries.size() + 1,
                    "FILE",
                    path.getFileName().toString(),
                    Files.size(path),
                    expected,
                    1));
        }
        if (!declaredOrder.equals(windowsOrder)) {
            throw invalid("frozen runtime classpath order differs from generated launchers");
        }
        long talosCount = orderedPaths.stream().filter(path -> {
            try {
                return Files.isSameFile(path, installedTalos);
            } catch (IOException ignored) {
                return false;
            }
        }).count();
        if (talosCount != 1 || !"lib/talos.jar".equals(declaredOrder.getFirst())) {
            throw invalid("installed runtime classpath must begin with the exact Talos artifact");
        }

        Set<String> actualLib = new HashSet<>();
        NoFollowPathPolicy.GuardedRoot libFiles =
                NoFollowPathPolicy.bindExistingDirectory(lib);
        for (NoFollowPathPolicy.TreeEntry child : libFiles.list(lib)) {
                if (child.type() != NoFollowPathPolicy.EntryType.REGULAR_FILE
                        || !child.path().getFileName().toString().endsWith(".jar")) {
                    throw invalid("installed lib contains a linked, non-regular, or non-JAR entry");
                }
                String logical = "lib/" + child.path().getFileName();
                String collisionKey = logical.toLowerCase(Locale.ROOT);
                if (!actualLib.add(collisionKey)) {
                    throw invalid("installed lib contains case-colliding entries");
                }
        }
        if (!actualLib.equals(collisionKeys)) {
            throw invalid("frozen runtime classpath does not cover the exact installed lib contents");
        }

        RuntimeArtifactIdentity.ClasspathSnapshot snapshot =
                RuntimeArtifactIdentity.identifyClasspath(orderedPaths);
        if (!snapshot.exact()) {
            throw invalid("installed runtime classpath identity is unavailable");
        }
        return new InstalledRuntimeIdentity(
                snapshot,
                List.copyOf(ledgerEntries),
                artifact("WINDOWS_LAUNCHER", "installed/bin/talos.bat",
                        Files.size(windowsLauncher), sha256File(windowsLauncher)),
                artifact("UNIX_LAUNCHER", "installed/bin/talos",
                        Files.size(unixLauncher), sha256File(unixLauncher)));
    }

    private static List<String> launcherClasspath(
            Path launcher,
            String marker,
            String separator,
            String prefix,
            String label) throws IOException {
        String text = NoFollowPathPolicy.readString(launcher, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
        List<String> matches = text.lines()
                .filter(line -> line.startsWith(marker))
                .toList();
        if (matches.size() != 1) {
            throw invalid(label + " launcher must declare exactly one classpath line");
        }
        String value = matches.getFirst().substring(marker.length());
        if (value.isBlank()) throw invalid(label + " launcher classpath is empty");
        List<String> result = new ArrayList<>();
        for (String entry : value.split(java.util.regex.Pattern.quote(separator), -1)) {
            if (!entry.startsWith(prefix)) {
                throw invalid(label + " launcher classpath entry has an unexpected form");
            }
            String name = entry.substring(prefix.length());
            if (name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
                throw invalid(label + " launcher classpath entry is unsafe");
            }
            result.add("lib/" + name);
        }
        return List.copyOf(result);
    }

    private static void verifyDeclaredFiles(
            Path packetRoot,
            Path contentRoot,
            Map<String, String> files,
            String category,
            Set<String> identities) throws IOException {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String logical = safeLogicalPath(entry.getKey(), category + " harness file");
            String expected = requireHash(entry.getValue(), category + " harness file");
            String collisionKey = Normalizer.normalize(logical, Normalizer.Form.NFC)
                    .toLowerCase(Locale.ROOT);
            if (!identities.add(collisionKey)) {
                throw invalid("harness input paths collide across categories: " + logical);
            }
            if (logical.equalsIgnoreCase(QualificationExecutionIdentity.HARNESS_FILE)
                    || logical.equalsIgnoreCase(QualificationExecutionIdentity.RUNTIME_FILE)
                    || logical.equalsIgnoreCase(LEDGER_FILE)) {
                throw invalid("harness inputs may not name generated identity outputs");
            }
            Path path = contentRoot.resolve(logical).normalize();
            Path file = category.equals("packet")
                    ? requiredPacketFile(packetRoot, path, category + " harness file")
                    : requiredRootFile(contentRoot, path, category + " harness file");
            equalHash(sha256File(file), expected,
                    category + " harness input hash mismatch: " + logical);
        }
    }

    private static JarIdentity jarIdentity(
            Path path,
            String expectedCommit,
            String expectedVersion,
            String label) throws IOException {
        String hash = sha256File(path);
        long size = Files.size(path);
        NoFollowPathPolicy.requireUnlinkedRegularFile(path);
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            if (jar.getManifest() == null) throw invalid(label + " manifest is missing");
            Attributes attributes = jar.getManifest().getMainAttributes();
            equalText(attributes.getValue("Implementation-Version"), expectedVersion,
                    label + " version mismatch");
            var entry = jar.getJarEntry("META-INF/talos-build.properties");
            if (entry == null) throw invalid(label + " build identity is missing");
            Properties properties = new Properties();
            try (InputStream input = jar.getInputStream(entry)) {
                properties.load(input);
            }
            equalRevision(properties.getProperty("git.commit"), expectedCommit,
                    label + " commit mismatch");
            if (!"false".equalsIgnoreCase(safe(properties.getProperty("git.dirty")))) {
                throw invalid(label + " was not produced from a clean tracked worktree");
            }
        } catch (IOException failure) {
            throw new IOException(label + " is not a readable identity-bearing JAR", failure);
        }
        return new JarIdentity(hash, size);
    }

    private static Existing inspectExisting(Path path, byte[] expected, String label)
            throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) throw invalid("existing " + label + " has no parent");
        NoFollowPathPolicy.GuardedRoot owner =
                NoFollowPathPolicy.bindExistingDirectory(parent);
        NoFollowPathPolicy.EntryType type = owner.inspect(path);
        if (type == NoFollowPathPolicy.EntryType.MISSING) return Existing.MISSING;
        if (type != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
            throw invalid("existing " + label + " is linked or not a regular file");
        }
        if (!java.util.Arrays.equals(owner.readAllBytes(path), expected)) {
            throw invalid("existing " + label + " conflicts with derived exact bytes");
        }
        return Existing.IDENTICAL;
    }

    private static boolean containsHistoricalEvidence(
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign) throws IOException {
        if (campaign.endedAt() != null && !campaign.endedAt().isBlank()) return true;
        Path runs = packetRoot.resolve("runs");
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(packetRoot);
        NoFollowPathPolicy.EntryType type = packetFiles.inspect(runs);
        if (type == NoFollowPathPolicy.EntryType.MISSING) return false;
        if (type != NoFollowPathPolicy.EntryType.DIRECTORY) {
            throw invalid("packet runs path is linked or not a directory");
        }
        return packetFiles.walk(runs).stream().skip(1).anyMatch(entry ->
                entry.type() == NoFollowPathPolicy.EntryType.REGULAR_FILE);
    }

    private static void createExact(Path path, byte[] bytes, String label) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) throw invalid(label + " has no parent");
        NoFollowPathPolicy.GuardedRoot owner =
                NoFollowPathPolicy.bindExistingDirectory(parent);
        owner.requireRegularFileTarget(path);
        try {
            try (var output = Files.newOutputStream(
                    path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                output.write(bytes);
            }
        } catch (FileAlreadyExistsException race) {
            throw invalid("refusing concurrent or pre-existing " + label + " creation", race);
        }
        if (!java.util.Arrays.equals(owner.readAllBytes(path), bytes)) {
            throw invalid("created " + label + " bytes do not match the derived representation");
        }
    }

    private static void rollbackCreated(List<Created> created, Throwable failure) {
        for (int i = created.size() - 1; i >= 0; i--) {
            Created item = created.get(i);
            try {
                Path parent = item.path().toAbsolutePath().normalize().getParent();
                NoFollowPathPolicy.GuardedRoot owner =
                        NoFollowPathPolicy.bindExistingDirectory(parent);
                if (owner.inspect(item.path()) == NoFollowPathPolicy.EntryType.REGULAR_FILE
                        && java.util.Arrays.equals(
                                owner.readAllBytes(item.path()), item.bytes())) {
                    Files.delete(item.path());
                } else {
                    failure.addSuppressed(new IllegalStateException(
                            "created identity file changed before rollback: " + item.path()));
                }
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void ensureNoCaseVariant(Path root, Path expected) throws IOException {
        String wanted = expected.getFileName().toString();
        NoFollowPathPolicy.GuardedRoot rootFiles =
                NoFollowPathPolicy.bindExistingDirectory(root);
        for (NoFollowPathPolicy.TreeEntry child : rootFiles.list(root)) {
                String name = child.path().getFileName().toString();
                if (name.equalsIgnoreCase(wanted) && !name.equals(wanted)) {
                    throw invalid("case-colliding identity output exists: " + name);
                }
        }
    }

    private static <T> T readPacket(
            Path root, Path path, Class<T> type, String label) throws IOException {
        return strict(requiredPacketBytes(root, path, label), type, label);
    }

    private static <T> T strict(byte[] bytes, Class<T> type, String label) throws IOException {
        try {
            return QualificationJson.read(bytes, type);
        } catch (RuntimeException failure) {
            throw invalid(label + " is invalid", failure);
        }
    }

    private static byte[] requiredPacketBytes(Path root, Path path, String label)
            throws IOException {
        Path file = requiredPacketFile(root, path, label);
        return NoFollowPathPolicy.bindExistingDirectory(root).readAllBytes(file);
    }

    private static Path requiredPacketFile(Path root, Path path, String label)
            throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw invalid(label + " is outside the packet");
        return requiredRootFile(root, normalized, label);
    }

    private static Path requiredRootFile(Path root, Path path, String label)
            throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        NoFollowPathPolicy.GuardedRoot rootFiles =
                NoFollowPathPolicy.bindExistingDirectory(root);
        if (!normalized.startsWith(rootFiles.path())) {
            throw invalid(label + " is missing, linked, or outside its declared root");
        }
        return rootFiles.requireRegularFile(normalized);
    }

    private static Path requiredAbsoluteDirectory(Path requested, String label) throws IOException {
        Path path = requiredAbsolute(requested, label);
        return NoFollowPathPolicy.requireUnlinkedDirectory(path);
    }

    private static Path requiredAbsoluteFile(Path requested, String label) throws IOException {
        Path path = requiredAbsolute(requested, label);
        return NoFollowPathPolicy.requireUnlinkedRegularFile(path);
    }

    private static Path requiredAbsolute(Path requested, String label) {
        Objects.requireNonNull(requested, label);
        if (!requested.isAbsolute()) throw invalid(label + " must be an explicit absolute path");
        return requested.toAbsolutePath().normalize();
    }

    private static Path parsePath(String value, String label) {
        try {
            return Path.of(requireText(value, label));
        } catch (RuntimeException failure) {
            throw invalid(label + " is not a valid path", failure);
        }
    }

    private static String safeLogicalPath(String value, String label) {
        String logical = safe(value);
        if (logical.isBlank() || logical.indexOf('\\') >= 0 || logical.startsWith("/")
                || logical.matches("(?i)^[a-z]:.*")) {
            throw invalid(label + " path is invalid");
        }
        Path normalized;
        try {
            normalized = Path.of(logical).normalize();
        } catch (RuntimeException failure) {
            throw invalid(label + " path is invalid", failure);
        }
        if (normalized.isAbsolute() || normalized.startsWith("..")
                || !normalized.toString().replace('\\', '/').equals(logical)) {
            throw invalid(label + " path is not canonical");
        }
        return logical;
    }

    private static String safeSegment(String value, String label) {
        try {
            return QualificationNamespace.requireSegment(value, label);
        } catch (IllegalArgumentException unsafe) {
            throw invalid(unsafe.getMessage(), unsafe);
        }
    }

    private static Map<String, String> normalized(Map<String, String> values) {
        TreeMap<String, String> normalized = new TreeMap<>();
        values.forEach((key, value) -> normalized.put(key, value.toLowerCase(Locale.ROOT)));
        return Map.copyOf(normalized);
    }

    private static void requireNonempty(Map<String, String> values, String label) {
        if (values == null || values.isEmpty()) throw invalid(label + " are missing");
    }

    private static void rejectSameFile(Path first, Path second, String message) throws IOException {
        if (Files.isSameFile(first, second)) throw invalid(message);
    }

    private static String sha256(byte[] bytes) {
        return QualificationScenarioBank.sha256(bytes);
    }

    static ExternalArtifactReceipt captureExternalArtifact(
            String role,
            String logicalToken,
            String kind,
            Path path,
            ContentHasher hasher) throws IOException {
        BasicFileAttributes before = stableAttributes(path, role);
        String beforeKey = fileKeyHash(before);
        String hash = requireHash(hasher.sha256(path), role);
        BasicFileAttributes after = stableAttributes(path, role);
        String afterKey = fileKeyHash(after);
        if (before.size() != after.size()
                || before.lastModifiedTime().toMillis() != after.lastModifiedTime().toMillis()
                || before.creationTime().toMillis() != after.creationTime().toMillis()
                || !Objects.equals(beforeKey, afterKey)) {
            throw invalid(role + " changed while its observed stream was being identified");
        }
        String contract = beforeKey.isEmpty()
                ? "METADATA_CONTINUITY_SIZE_MTIME_CTIME_V1"
                : "METADATA_CONTINUITY_SIZE_MTIME_CTIME_FILE_KEY_V1";
        return new ExternalArtifactReceipt(
                role,
                safeLogicalPath(logicalToken, role + " logical token"),
                requireText(kind, role + " kind"),
                hash,
                "STREAM_READ_WITH_METADATA_BRACKETS_V1",
                before.size(),
                before.lastModifiedTime().toMillis(),
                before.creationTime().toMillis(),
                beforeKey,
                contract);
    }

    static void verifyExternalArtifactReceipt(
            ExternalArtifactReceipt receipt,
            Path path,
            String expectedHash,
            long expectedSize,
            String expectedKind) throws IOException {
        Objects.requireNonNull(receipt, "external artifact receipt");
        equalHash(receipt.sha256(), expectedHash,
                receipt.role() + " receipt and manifest hash differ");
        equalText(receipt.kind(), expectedKind, receipt.role() + " receipt kind mismatch");
        equalText(receipt.hashObservationContract(),
                "STREAM_READ_WITH_METADATA_BRACKETS_V1",
                receipt.role() + " hash observation contract mismatch");
        safeLogicalPath(receipt.logicalToken(), receipt.role() + " receipt logical token");
        if (expectedSize >= 0 && receipt.sizeBytes() != expectedSize) {
            throw invalid(receipt.role() + " receipt and manifest size differ");
        }
        BasicFileAttributes actual = stableAttributes(path, receipt.role());
        if (actual.size() != receipt.sizeBytes()
                || actual.lastModifiedTime().toMillis() != receipt.lastModifiedMillis()
                || actual.creationTime().toMillis() != receipt.creationTimeMillis()) {
            throw invalid(receipt.role() + " stable metadata changed after identity capture");
        }
        String actualKey = fileKeyHash(actual);
        switch (receipt.postCaptureContinuityContract()) {
            case "METADATA_CONTINUITY_SIZE_MTIME_CTIME_FILE_KEY_V1" -> {
                requireHash(receipt.fileKeySha256(), receipt.role() + " file key");
                if (!receipt.fileKeySha256().equals(actualKey)) {
                    throw invalid(receipt.role() + " stable file identity changed after capture");
                }
            }
            case "METADATA_CONTINUITY_SIZE_MTIME_CTIME_V1" -> {
                if (!safe(receipt.fileKeySha256()).isEmpty()) {
                    throw invalid(receipt.role() + " receipt has an inconsistent file-key contract");
                }
            }
            default -> throw invalid(receipt.role() + " stability contract is unsupported");
        }
    }

    private static BasicFileAttributes stableAttributes(Path path, String label)
            throws IOException {
        NoFollowPathPolicy.requireUnlinkedRegularFile(path);
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() <= 0) {
            throw invalid(label + " is missing, empty, or not a regular file");
        }
        return attributes;
    }

    private static String fileKeyHash(BasicFileAttributes attributes) {
        Object key = attributes.fileKey();
        if (key == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthDelimited(digest,
                    "talos-external-file-key-v1".getBytes(StandardCharsets.UTF_8));
            updateLengthDelimited(digest, key.getClass().getName().getBytes(StandardCharsets.UTF_8));
            updateLengthDelimited(digest, key.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String sha256File(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = NoFollowPathPolicy.openUnlinkedRegularFile(path);
                    DigestInputStream hashing = new DigestInputStream(input, digest)) {
                byte[] buffer = new byte[1024 * 1024];
                while (hashing.read(buffer) >= 0) {
                    // DigestInputStream updates the digest without materializing the file.
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateLengthDelimited(MessageDigest digest, byte[] value) {
        digest.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(value.length).array());
        digest.update(value);
    }

    private static String requireHash(String value, String label) {
        String hash = safe(value);
        if (!hash.matches("[0-9a-f]{64}")) throw invalid(label + " SHA-256 is invalid");
        return hash;
    }

    private static String requireRevision(String value, String label) {
        String revision = safe(value);
        if (!revision.matches("[0-9a-f]{40}")) throw invalid(label + " revision is invalid");
        return revision;
    }

    private static String requireText(String value, String label) {
        String text = safe(value);
        if (text.isBlank()) throw invalid(label + " is missing");
        return text;
    }

    private static void equalHash(String actual, String expected, String message) {
        if (!requireHash(actual, message).equals(requireHash(expected, message))) {
            throw invalid(message);
        }
    }

    private static void equalRevision(String actual, String expected, String message) {
        if (!requireRevision(actual, message).equals(requireRevision(expected, message))) {
            throw invalid(message);
        }
    }

    private static void equalText(String actual, String expected, String message) {
        if (!Objects.equals(actual, expected)) throw invalid(message);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static LedgerArtifact artifact(String role, String logicalToken, JarIdentity identity) {
        return artifact(role, logicalToken, identity.sizeBytes(), identity.sha256());
    }

    private static LedgerArtifact artifact(
            String role, String logicalToken, long sizeBytes, String sha256) {
        if (sizeBytes <= 0) throw invalid(role + " size is invalid");
        safeLogicalPath(logicalToken, role + " logical token");
        return new LedgerArtifact(role, logicalToken, sizeBytes, requireHash(sha256, role));
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException invalid(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }

    private enum Existing { MISSING, IDENTICAL }

    private record JarIdentity(String sha256, long sizeBytes) {}

    private record InstalledRuntimeIdentity(
            RuntimeArtifactIdentity.ClasspathSnapshot snapshot,
            List<LedgerClasspathEntry> entries,
            LedgerArtifact windowsLauncher,
            LedgerArtifact unixLauncher) {
        InstalledRuntimeIdentity {
            entries = List.copyOf(entries);
        }
    }

    private record Created(Path path, byte[] bytes) {
        Created {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
