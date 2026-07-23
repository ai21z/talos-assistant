package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Finalizes immutable provider evidence without conflating process startup with model generation. */
final class QualificationProviderEvidenceFinalizer {
    private static final String PROMPT_OUTPUT = "prompt-debug.txt";
    private static final String MODEL_OUTPUT = "llama.log";
    private static final long MAX_PROMPT_BYTES = 16L * 1024 * 1024;
    private static final long MAX_MODEL_LOG_BYTES = 64L * 1024 * 1024;

    private QualificationProviderEvidenceFinalizer() {}

    record Result(Path promptDebug, Path modelLog, boolean providerPromptSent) {}

    static Result finalizeEvidence(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            int serverPort,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        if (serverPort < 1 || serverPort > 65_535) {
            throw new IllegalArgumentException("server port must be between 1 and 65535");
        }
        return QualificationPacketPublisher.withLease(
                packetRoot,
                lease -> finalizeUnderLease(
                        lease, requestedRunRoot, scenarioId, serverPort, catalog));
    }

    static Result finalizeUnderLease(
            QualificationPacketPublisher.Lease lease,
            Path requestedRunRoot,
            String scenarioId,
            int serverPort,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        QualificationArtifactPublication.DeclaredRun declared =
                QualificationArtifactPublication.requireWritableDeclaredRun(
                        lease.packetRoot(), requestedRunRoot, scenarioId, catalog);
        NoFollowPathPolicy.GuardedRoot packetFiles = declared.packetFiles();
        NoFollowPathPolicy.GuardedRoot runFiles = declared.runFiles();
        Path runRoot = declared.runRoot();
        Path root = declared.packetRoot();
        int frozenServerPort = frozenServerPort(lease, declared);
        if (serverPort != frozenServerPort) {
            throw new IllegalArgumentException(
                    "server port differs from the frozen qualification calibration: expected "
                            + frozenServerPort);
        }
        Path promptOutput = runRoot.resolve(PROMPT_OUTPUT);
        Path modelOutput = runRoot.resolve(MODEL_OUTPUT);
        lease.requireTransactionProvenance(
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(promptOutput, modelOutput));
        List<Path> renders = providerRenders(runFiles, runRoot);
        if (renders.size() > 1) {
            throw new IllegalStateException(
                    "expected exactly one provider prompt render, found " + renders.size());
        }
        boolean providerPromptSent = !renders.isEmpty();
        byte[] promptBytes;
        byte[] modelBytes;
        if (providerPromptSent) {
            Path sourceLog = root.resolve(
                    Path.of("home", ".talos", "logs", "llama_cpp-" + serverPort + ".log"));
            sourceLog = requiredSourceLog(packetFiles, sourceLog);
            promptBytes = boundedBytes(
                    runFiles, renders.getFirst(), MAX_PROMPT_BYTES, "provider prompt render");
            modelBytes = boundedBytes(
                    packetFiles, sourceLog, MAX_MODEL_LOG_BYTES, "llama.cpp source log");
        } else {
            promptBytes = QualificationProviderEvidence.promptNotApplicableText()
                    .getBytes(StandardCharsets.UTF_8);
            modelBytes = QualificationProviderEvidence.modelRuntimeNotApplicableText()
                    .getBytes(StandardCharsets.UTF_8);
        }
        lease.publish(
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(
                        new QualificationPacketPublisher.Member(promptOutput, promptBytes),
                        new QualificationPacketPublisher.Member(modelOutput, modelBytes)));
        return new Result(promptOutput, modelOutput, providerPromptSent);
    }

    private static int frozenServerPort(
            QualificationPacketPublisher.Lease lease,
            QualificationArtifactPublication.DeclaredRun declared) throws IOException {
        Path root = declared.packetRoot();
        Path ledgerPath = root.resolve(QualificationExecutionIdentityWriter.LEDGER_FILE);
        Path harnessPath = root.resolve(QualificationExecutionIdentity.HARNESS_FILE);
        Path runtimePath = root.resolve(QualificationExecutionIdentity.RUNTIME_FILE);
        QualificationPacketPublisher.CommittedSet identity = lease.requireCommittedSet(
                QualificationPacketPublisher.PublicationKind.EXECUTION_IDENTITY,
                List.of(ledgerPath, harnessPath, runtimePath));
        byte[] runtimeBytes = identity.bytes(root, runtimePath);
        QualificationExecutionIdentity.RuntimeManifest runtime = QualificationJson.read(
                runtimeBytes, QualificationExecutionIdentity.RuntimeManifest.class);
        if (!java.util.Arrays.equals(runtimeBytes, QualificationJson.bytes(runtime))
                || !QualificationExecutionIdentity.RUNTIME_SCHEMA.equals(runtime.schemaName())
                || runtime.schemaVersion() != QualificationExecutionIdentity.RUNTIME_SCHEMA_VERSION
                || runtime.protocolVersion() != QualificationProtocol.VERSION
                || !runtime.campaignId().equals(declared.campaign().campaignId())) {
            throw new IOException("committed runtime identity is not canonical for this campaign");
        }

        Path calibrationPath = root.resolve("candidates")
                .resolve(declared.campaign().candidateAlias())
                .resolve("calibration-rules.json");
        byte[] calibrationBytes = declared.packetFiles().readAllBytes(calibrationPath);
        QualificationRecords.CalibrationRules calibration = QualificationJson.read(
                calibrationBytes, QualificationRecords.CalibrationRules.class);
        String calibrationSha256 = QualificationScenarioBank.sha256(calibrationBytes);
        if (!java.util.Arrays.equals(calibrationBytes, QualificationJson.bytes(calibration))
                || !QualificationRecords.CALIBRATION_SCHEMA.equals(calibration.schemaName())
                || calibration.schemaVersion()
                        != QualificationRecords.CALIBRATION_SCHEMA_VERSION
                || calibration.protocolVersion() != QualificationProtocol.VERSION
                || !calibration.candidateAlias().equals(declared.campaign().candidateAlias())
                || !calibration.configurationId().equals(declared.campaign().configId())
                || !calibration.talosCommit().equals(declared.campaign().talosCommit())
                || !calibrationSha256.equals(declared.campaign().calibrationRulesSha256())
                || !calibrationSha256.equals(runtime.calibrationRulesSha256())) {
            throw new IOException("frozen qualification calibration identity is invalid");
        }
        return exactServerPort(calibration.launchArguments());
    }

    private static int exactServerPort(List<String> arguments) throws IOException {
        Integer port = null;
        for (int index = 0; index < arguments.size(); index++) {
            if (!"--port".equals(arguments.get(index))) continue;
            if (port != null || index + 1 >= arguments.size()) {
                throw new IOException("frozen calibration must declare exactly one server port");
            }
            String value = arguments.get(++index);
            try {
                port = Integer.parseInt(value);
            } catch (NumberFormatException invalid) {
                throw new IOException("frozen calibration server port is invalid", invalid);
            }
        }
        if (port == null || port < 1 || port > 65_535) {
            throw new IOException("frozen calibration server port is missing or invalid");
        }
        return port;
    }

    private static List<Path> providerRenders(
            NoFollowPathPolicy.GuardedRoot runFiles,
            Path runRoot
    ) throws IOException {
        return runFiles.walk(runRoot).stream()
                .filter(entry ->
                        entry.type() == NoFollowPathPolicy.EntryType.REGULAR_FILE)
                .map(NoFollowPathPolicy.TreeEntry::path)
                .filter(path -> path.getParent().equals(runRoot))
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith("prompt-debug-") && name.endsWith(".md");
                })
                .sorted()
                .toList();
    }

    private static Path requiredSourceLog(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path sourceLog
    ) throws IOException {
        NoFollowPathPolicy.EntryType type = packetFiles.inspect(sourceLog);
        if (type != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
            throw new IllegalStateException("llama.cpp source log is missing");
        }
        return packetFiles.requireRegularFile(sourceLog);
    }

    private static byte[] boundedBytes(
            NoFollowPathPolicy.GuardedRoot root,
            Path path,
            long maxBytes,
            String label
    ) throws IOException {
        root.requireRegularFile(path);
        long size = Files.size(path);
        if (size <= 0 || size > maxBytes) {
            throw new IllegalStateException(label + " size is outside the allowed range: " + size);
        }
        return root.readAllBytes(path);
    }

    static void writePair(
            Path packetRoot,
            Path promptOutput,
            byte[] promptBytes,
            Path modelOutput,
            byte[] modelBytes) throws IOException {
        QualificationPacketPublisher.withLease(packetRoot, lease -> {
            lease.publish(
                    QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                    List.of(
                            new QualificationPacketPublisher.Member(promptOutput, promptBytes),
                            new QualificationPacketPublisher.Member(modelOutput, modelBytes)));
            return null;
        });
    }
}
