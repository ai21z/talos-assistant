package dev.talos.qualification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.talos.core.security.NoFollowPathPolicy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Windows true-PTY capture entrypoint for immutable local model-qualification campaigns. */
public final class QualificationPtyCaptureMain {
    /** YAML is the explicitly separate, non-JSON engine-configuration format. */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Duration STARTUP_WAIT = Duration.ofSeconds(30);
    private static final Duration STARTUP_RECOVERY_WAIT = Duration.ofSeconds(10);
    private static final Duration TURN_WAIT = Duration.ofSeconds(330);
    private static final Duration SLASH_WAIT = Duration.ofSeconds(30);
    private static final String PROMPT = QualificationPtyOutcomeRace.PROMPT_MARKER;

    private final Path campaignRoot;
    private final NoFollowPathPolicy.GuardedRoot packetFiles;
    private final QualificationRecords.CampaignManifest campaign;
    private final Path runRoot;
    private final NoFollowPathPolicy.GuardedRoot runFiles;
    private final Path workspace;
    private final NoFollowPathPolicy.GuardedRoot workspaceFiles;
    private final Path home;
    private final Path calibrationPath;
    private final Path configPath;
    private final Path scenarioPath;
    private final Path monitorScript;
    private final Path stopFile;
    private final Path resourceSamples;
    private final Path transcriptPath;
    private final Path llamaLog;
    private final Path modelArtifact;
    private final Path serverExecutable;
    private final int serverPort;
    private final Path powershellExecutable;
    private final Path winpty;
    private final Path installedTalos;
    private final Path installedRoot;
    private final String prompt;
    private final String approvalBehavior;
    private final int protocolVersion;
    private final CaptureBuffer capture = new CaptureBuffer();
    private final List<QualificationPtyEvidence.Event> events = new ArrayList<>();
    private int cursor;
    private Process talos;
    private Process monitor;
    private Thread outputReader;
    private Instant talosStartedNotBefore;
    private QualificationProcessCleanup.Result cleanupResult;
    private String terminalOutcome = "NOT_STARTED";
    private boolean allowGracefulCleanup = true;

    private QualificationPtyCaptureMain(
            Path campaignRoot,
            String scenarioId,
            int repetition) throws Exception {
        this.packetFiles = NoFollowPathPolicy.bindExistingDirectory(campaignRoot);
        this.campaignRoot = packetFiles.path();
        this.campaign = QualificationJson.read(
                packetFiles.readAllBytes(this.campaignRoot.resolve("campaign.json")),
                QualificationRecords.CampaignManifest.class);
        String candidateAlias = required(campaign.candidateAlias(), "candidateAlias");
        String machineId = required(campaign.machineId(), "machineId");
        String configId = required(campaign.configId(), "configId");
        this.protocolVersion = campaign.protocolVersion();
        QualificationArtifactPublication.requireWritableProtocol(protocolVersion);
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(protocolVersion);
        QualificationArtifactPublication.requireWritableCampaign(this.campaignRoot, catalog);
        QualificationProtocol.Scenario declaredScenario = catalog.scenario(scenarioId);
        if (repetition < 1 || repetition > campaign.repetitions().size()) {
            throw new IllegalArgumentException(
                    "repetition must identify one trial declared by the campaign");
        }

        String trial = "trial-%02d".formatted(repetition);
        Path requestedRunRoot = this.campaignRoot.resolve(Path.of(
                "runs", candidateAlias, machineId, configId, scenarioId, trial));
        QualificationArtifactPublication.DeclaredRun declaredRun =
                QualificationArtifactPublication.requireWritableDeclaredRun(
                        this.campaignRoot, requestedRunRoot, scenarioId, catalog);
        this.runRoot = declaredRun.runRoot();
        this.runFiles = declaredRun.runFiles();
        this.transcriptPath = runRoot.resolve("transcript.raw.json");
        if (runFiles.inspect(transcriptPath) != NoFollowPathPolicy.EntryType.MISSING) {
            throw new IllegalStateException("refusing to overwrite the PTY capture packet");
        }
        this.scenarioPath = runFiles.requireRegularFile(runRoot.resolve("scenario.json"));
        QualificationProtocol.Scenario capturedScenario = QualificationJson.readScenario(
                runFiles.readAllBytes(scenarioPath), protocolVersion);
        if (!declaredScenario.equals(capturedScenario)) {
            throw new IllegalStateException(
                    "captured scenario does not match the frozen qualification catalog");
        }
        this.prompt = capturedScenario.prompt();
        this.approvalBehavior = capturedScenario.approvalBehavior().name();

        this.calibrationPath = packetFiles.requireRegularFile(this.campaignRoot.resolve(Path.of(
                "candidates", candidateAlias, "calibration-rules.json")));
        QualificationRecords.CalibrationRules calibration = QualificationJson.read(
                packetFiles.readAllBytes(calibrationPath),
                QualificationRecords.CalibrationRules.class);
        String environmentContract =
                dev.talos.spi.types.ProviderProcessLaunchReceipt.EnvironmentContract
                        .QUALIFICATION_RELEVANT_ALLOWLIST_V1.name();
        String launchEnvironmentSha256 =
                dev.talos.spi.types.ProviderProcessLaunchReceipt
                        .digestEffectiveEnvironment(System.getenv());
        dev.talos.engine.llamacpp.LlamaCppQualificationObserverIdentity observer =
                dev.talos.engine.llamacpp.LlamaCppQualificationObserverIdentity.current();
        if (calibration.protocolVersion() != QualificationProtocol.VERSION
                || calibration.schemaVersion()
                        != QualificationRecords.CALIBRATION_SCHEMA_VERSION
                || !environmentContract.equals(calibration.launchEnvironmentContract())
                || !launchEnvironmentSha256.equals(
                        calibration.launchEnvironmentSha256())
                || !observer.kind().name().equals(calibration.endpointObserverKind())
                || !observer.sha256().equals(
                        calibration.endpointObserverIdentitySha256())) {
            throw new IllegalStateException(
                    "current provider launch environment or endpoint observer differs from frozen calibration");
        }
        String serverPortText = argumentValue(calibration.launchArguments(), "--port");
        this.serverPort = Integer.parseInt(serverPortText);
        this.modelArtifact = NoFollowPathPolicy.requireUnlinkedRegularFile(Path.of(
                argumentValue(calibration.launchArguments(), "-m"))
                .toAbsolutePath().normalize());
        @SuppressWarnings("unchecked")
        Map<String, Object> config = YAML.readValue(packetFiles.readAllBytes(
                this.campaignRoot.resolve(Path.of("home", ".talos", "config.yaml"))),
                Map.class);
        this.configPath = packetFiles.requireRegularFile(
                this.campaignRoot.resolve(Path.of("home", ".talos", "config.yaml")));
        Map<String, Object> engines = map(config.get("engines"));
        Map<String, Object> llamaCpp = map(engines.get("llama_cpp"));
        String configuredServerPath = String.valueOf(llamaCpp.getOrDefault("server_path", "")).strip();
        int configuredPort = intValue(llamaCpp.get("port"), -1);
        if (configuredServerPath.isBlank() || configuredPort != this.serverPort) {
            throw new IllegalStateException(
                    "campaign config server path/port does not match frozen calibration");
        }
        this.serverExecutable = NoFollowPathPolicy.requireUnlinkedRegularFile(
                Path.of(configuredServerPath).toAbsolutePath().normalize());
        this.workspace = packetFiles.requireDirectory(
                this.campaignRoot.resolve(Path.of("workspaces", scenarioId, trial)));
        this.workspaceFiles = NoFollowPathPolicy.bindExistingDirectory(workspace);
        this.home = packetFiles.requireDirectory(this.campaignRoot.resolve("home"));
        this.monitorScript = packetFiles.requireRegularFile(
                this.campaignRoot.resolve(Path.of("tools", "monitor-resources.ps1")));
        this.stopFile = runRoot.resolve("resource-monitor.stop");
        this.resourceSamples = runRoot.resolve("resource-samples.csv");
        this.llamaLog = this.campaignRoot.resolve(Path.of(
                "home", ".talos", "logs", "llama_cpp-" + serverPortText + ".log"));
        runFiles.requireRegularFileTarget(stopFile);
        runFiles.requireRegularFileTarget(resourceSamples);
        runFiles.requireRegularFileTarget(transcriptPath);
        runFiles.requireRegularFileTarget(runRoot.resolve("transcript.failed.txt"));
        packetFiles.requireRegularFileTarget(llamaLog);
        this.powershellExecutable = resolveExecutableOnPath("pwsh.exe");
        this.winpty = NoFollowPathPolicy.requireUnlinkedRegularFile(
                Path.of("C:/Program Files/Git/usr/bin/winpty.exe"));
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            throw new IllegalStateException("LOCALAPPDATA is unavailable");
        }
        this.installedTalos = NoFollowPathPolicy.requireUnlinkedRegularFile(
                Path.of(localAppData, "Programs", "talos", "bin", "talos.bat")
                        .toAbsolutePath().normalize());
        this.installedRoot = installedTalos.getParent().getParent();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "expected: <campaign-root> <scenario-id> <repetition>");
        }
        QualificationPtyCaptureMain runner = new QualificationPtyCaptureMain(
                Path.of(args[0]), args[1], Integer.parseInt(args[2]));
        runner.run();
    }

    private QualificationPtyFilesystemBoundary.Plan filesystemBoundaryPlan() {
        return new QualificationPtyFilesystemBoundary.Plan(
                        packetFiles,
                        campaignRoot,
                        List.of(
                                campaignRoot.resolve("campaign.json"),
                                calibrationPath,
                                configPath,
                                monitorScript),
                        List.of(home),
                        List.of(llamaLog),
                        runFiles,
                        runRoot,
                        List.of(scenarioPath),
                        List.of(
                                stopFile,
                                resourceSamples,
                                transcriptPath,
                                runRoot.resolve("transcript.failed.txt")),
                        workspaceFiles,
                        workspace,
                        List.of(
                                modelArtifact,
                                serverExecutable,
                                powershellExecutable,
                                winpty,
                                installedTalos),
                        installedRoot,
                        installedTalos);
    }

    private void validateThenStartChildren() throws Exception {
        QualificationPtyFilesystemBoundary.validateThenStart(
                filesystemBoundaryPlan(),
                this::startMonitor,
                this::startTalos);
    }

    private void run() throws Exception {
        Instant started = Instant.now();
        Exception captureFailure = null;
        try {
            validateThenStartChildren();
            waitForStartupIdlePrompt();
            drainOutput();

            addInput("scenario-prompt");
            int turnAnchor = cursor;
            writeLine(prompt);
            switch (approvalBehavior) {
                case "NONE", "APPROVE_ONCE", "SESSION_ALLOW", "DENY" ->
                        terminalOutcome = awaitApprovalOrTerminal(turnAnchor);
                case "INTERRUPT_BEFORE_APPROVAL" -> {
                    interruptDuringGeneration();
                    terminalOutcome = "turn-aborted-by-operator";
                }
                default -> throw new IllegalStateException(
                        "unsupported approval behavior: " + approvalBehavior);
            }

            collectDiagnostics();
            exitTalos();
        } catch (Exception failure) {
            captureFailure = failure;
        } finally {
            cleanupResult = stopProcesses();
            if (cleanupResult.outcome() == QualificationProcessCleanup.Outcome.FAILED) {
                IllegalStateException cleanupFailure = new IllegalStateException(
                        "qualification process cleanup left survivors: "
                                + cleanupResult.survivingPids());
                if (captureFailure == null) captureFailure = cleanupFailure;
                else captureFailure.addSuppressed(cleanupFailure);
            }
            drainOutput();
            writePacket(started, captureFailure);
            if (captureFailure != null) {
                Path failureTranscript = runRoot.resolve("transcript.failed.txt");
                byte[] failureBytes =
                        (capture.text() + System.lineSeparator() + captureFailure)
                                .getBytes(StandardCharsets.UTF_8);
                QualificationPacketPublisher.withLease(campaignRoot, lease -> {
                    lease.publish(
                            QualificationPacketPublisher.PublicationKind.PTY_FAILURE,
                            List.of(new QualificationPacketPublisher.Member(
                                    failureTranscript, failureBytes)));
                    return null;
                });
            }
        }
        if (captureFailure != null) throw captureFailure;
    }

    private String awaitApprovalOrTerminal(int turnAnchor) throws Exception {
        QualificationPtyOutcomeRace.SequenceOutcome sequence;
        try {
            sequence = QualificationPtyOutcomeRace.awaitSequence(
                    approvalBehavior,
                    () -> new QualificationPtyOutcomeRace.Snapshot(
                            capture.text(),
                            talos != null && talos.isAlive(),
                            talos != null && !talos.isAlive() ? talos.exitValue() : -1),
                    turnAnchor,
                    TURN_WAIT,
                    new SystemWaiter(),
                    (action, ordinal) -> {
                        drainOutput();
                        addTerminalOutcome(action.eventLabel());
                        if (!action.sendInput()) return cursor;
                        addInput(action.eventLabel());
                        writeLine(action.response());
                        return cursor;
                    });
        } catch (QualificationPtyOutcomeRace.ApprovalPromptLimitException limit) {
            terminalOutcome = "approval-prompt-limit-exceeded";
            allowGracefulCleanup = false;
            addTerminalOutcome(terminalOutcome);
            throw limit;
        }
        QualificationPtyOutcomeRace.Outcome outcome = sequence.outcome();
        terminalOutcome = switch (outcome.kind()) {
            case APPROVAL -> throw new IllegalStateException(
                    "approval sequence returned an approval");
            case TURN_COMPLETE -> sequence.approvalCount() == 0
                    ? (approvalBehavior.equals("NONE")
                            ? "turn-completed"
                            : "turn-completed-before-expected-approval")
                    : "turn-completed-after-approval";
            case PROCESS_EXIT -> "process-exit-before-terminal-marker";
            case TIMEOUT -> "timeout-before-terminal-marker";
        };

        if (outcome.kind() == QualificationPtyOutcomeRace.Kind.PROCESS_EXIT) {
            addTerminalOutcome(terminalOutcome + ":exit=" + outcome.exitCode());
            throw new IllegalStateException(
                    "Talos exited before approval or turn completion, exit=" + outcome.exitCode());
        }
        if (outcome.kind() == QualificationPtyOutcomeRace.Kind.TIMEOUT) {
            addTerminalOutcome(terminalOutcome);
            throw new IllegalStateException("timed out waiting for approval or turn completion");
        }
        Thread.sleep(100L);
        drainOutput();
        addTerminalOutcome(terminalOutcome);
        return terminalOutcome;
    }

    private String awaitTurnCompletion(String completedOutcome) throws Exception {
        waitForSettledIdlePrompt(cursor, TURN_WAIT);
        drainOutput();
        addTerminalOutcome(completedOutcome);
        return completedOutcome;
    }

    private void collectDiagnostics() throws Exception {
        if (talos == null || !talos.isAlive()) {
            throw new IllegalStateException("Talos exited before prompt-debug and trace capture");
        }
        addInput("prompt-debug");
        int promptDebugAnchor = cursor;
        writeLine("/prompt-debug save " + forwardSlashes(runRoot));
        waitForSettledIdlePrompt(cursor, SLASH_WAIT);
        drainOutput();
        QualificationProviderEvidence.capture(
                campaignRoot,
                runRoot,
                capture.slice(promptDebugAnchor, cursor),
                protocolVersion);

        addInput("last-trace");
        writeLine("/last trace");
        waitForSettledIdlePrompt(cursor, SLASH_WAIT);
        drainOutput();
    }

    private void exitTalos() throws Exception {
        addInput("exit");
        writeLine("/exit");
        int goodbye = waitFor("Goodbye!", cursor, SLASH_WAIT);
        Thread.sleep(150L);
        drainOutputThrough(goodbye + "Goodbye!".length());
    }

    private void startMonitor() throws Exception {
        NoFollowPathPolicy.requireUnlinkedRegularFile(powershellExecutable);
        packetFiles.requireRegularFile(monitorScript);
        deleteOrdinaryFileIfPresent(runFiles, stopFile);
        runFiles.requireRegularFileTarget(resourceSamples);
        monitor = new ProcessBuilder(
                powershellExecutable.toString(),
                "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                monitorScript.toString(), "-Output", resourceSamples.toString(),
                "-StopFile", stopFile.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private void startTalos() throws Exception {
        NoFollowPathPolicy.requireUnlinkedRegularFile(winpty);
        NoFollowPathPolicy.requireUnlinkedRegularFile(installedTalos);
        NoFollowPathPolicy.requireUnlinkedRegularFile(modelArtifact);
        NoFollowPathPolicy.requireUnlinkedRegularFile(serverExecutable);
        workspaceFiles.requireDirectory(workspace);
        packetFiles.requireRegularFile(calibrationPath);
        packetFiles.requireDirectory(home);
        deleteOrdinaryFileIfPresent(packetFiles, llamaLog);
        ProcessBuilder builder = new ProcessBuilder(
                winpty.toString(), "-Xallow-non-tty", "--", installedTalos.toString())
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        String javaOptions = String.join(" ",
                jvmProperty("user.home", home.toString()),
                jvmProperty("talos.qualification.root", campaignRoot.toString()),
                jvmProperty("talos.qualification.calibration", calibrationPath.toString()),
                jvmProperty("talos.qualification.calibrationSha256",
                        campaign.calibrationRulesSha256()));
        builder.environment().put("JAVA_OPTS", javaOptions);
        builder.environment().put("COLUMNS", "120");
        builder.environment().put("LINES", "40");
        talosStartedNotBefore = Instant.now();
        talos = builder.start();
        outputReader = Thread.ofPlatform().daemon().start(
                () -> capture.copyFrom(talos.getInputStream()));
    }

    private static String jvmProperty(String key, String value) {
        String safeKey = required(key, "JVM property key");
        String safeValue = required(value, "JVM property value");
        if (safeKey.contains("=") || safeKey.contains("\"") || safeValue.contains("\"")) {
            throw new IllegalStateException("qualification JVM property contains an unsupported character");
        }
        return "\"-D" + safeKey + "=" + safeValue + "\"";
    }

    private void interruptDuringGeneration() throws Exception {
        waitForFile(packetFiles, llamaLog, "processing task", Duration.ofSeconds(60));
        Thread.sleep(100L);
        drainOutput();
        addInput("user-interrupt");
        talos.getOutputStream().write(3);
        talos.getOutputStream().flush();
        awaitTurnCompletion("turn-aborted-by-operator");
        String turn = outputThrough(cursor);
        if (!turn.contains("turn aborted") && !turn.contains("LLM_ABORTED")) {
            throw new IllegalStateException("interrupt did not produce a visible terminal abort");
        }
        if (turn.contains(QualificationPtyOutcomeRace.APPROVAL_MARKER)) {
            throw new IllegalStateException("approval appeared after user interruption");
        }
    }

    private int waitFor(String marker, int from, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String text = capture.text();
            int found = text.indexOf(marker, from);
            if (found >= 0) return found;
            if (talos != null && !talos.isAlive()) {
                terminalOutcome = "process-exit-while-waiting-for-marker";
                throw new IllegalStateException("Talos exited while waiting for " + marker
                        + ", exit=" + talos.exitValue());
            }
            Thread.sleep(25L);
        }
        terminalOutcome = "timeout-while-waiting-for-marker";
        throw new IllegalStateException("timed out waiting for " + marker);
    }

    private void waitForStartupIdlePrompt() throws Exception {
        QualificationPtyStartupHandshake.await(
                timeout -> waitForSettledIdlePrompt(0, timeout),
                () -> {
                    if (talos == null || !talos.isAlive()) {
                        throw new IllegalStateException(
                                "Talos exited before the startup wakeup could be sent");
                    }
                    addInput("startup-wakeup");
                    writeLine("");
                },
                STARTUP_WAIT,
                STARTUP_RECOVERY_WAIT);
    }

    private int waitForIdlePrompt(int from, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            int found = QualificationPtyOutcomeRace.idlePromptIndex(capture.text(), from);
            if (found >= 0) return found;
            if (talos != null && !talos.isAlive()) {
                terminalOutcome = "process-exit-while-waiting-for-idle-prompt";
                throw new IllegalStateException("Talos exited while waiting for idle prompt, exit="
                        + talos.exitValue());
            }
            Thread.sleep(25L);
        }
        terminalOutcome = "timeout-while-waiting-for-idle-prompt";
        throw new QualificationPtyIdlePromptTimeoutException();
    }

    private void waitForSettledIdlePrompt(int from, Duration timeout) throws Exception {
        QualificationPtyIdleSettle.awaitAndPause(
                () -> waitForIdlePrompt(from, timeout),
                SystemWaiter::pauseThread);
    }

    private static void waitForFile(
            NoFollowPathPolicy.GuardedRoot owner,
            Path path,
            String marker,
            Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (owner.inspect(path) == NoFollowPathPolicy.EntryType.REGULAR_FILE
                    && owner.readString(path, StandardCharsets.UTF_8).contains(marker)) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new IllegalStateException("timed out waiting for " + marker + " in " + path);
    }

    private void addInput(String label) {
        events.add(event("input", label));
    }

    private void addTerminalOutcome(String label) {
        events.add(event("terminal-outcome", label));
    }

    private void drainOutput() {
        drainOutputThrough(capture.length());
    }

    private void drainOutputThrough(int end) {
        if (end <= cursor) return;
        events.add(event("output", capture.slice(cursor, end)));
        cursor = end;
    }

    private void writePacket(Instant started, Exception failure) {
        try {
            QualificationPtyEvidence.Capture packet = new QualificationPtyEvidence.Capture(
                    QualificationPtyEvidence.SCHEMA_NAME,
                    protocolVersion,
                    "WINDOWS_PTY",
                    true,
                    started.toString(),
                    Instant.now().toString(),
                    forwardSlashes(installedTalos),
                    forwardSlashes(workspace),
                    approvalBehavior,
                    terminalOutcome,
                    failure == null ? "" : failure.toString(),
                    QualificationPtyEvidence.ProcessCleanup.from(cleanupResult),
                    events,
                    capture.text());
            byte[] canonicalBytes = QualificationJson.bytes(packet);
            QualificationPtyEvidence.parse(canonicalBytes, protocolVersion);
            QualificationPacketPublisher.withLease(campaignRoot, lease -> {
                lease.publish(
                        QualificationPacketPublisher.PublicationKind.PTY_CAPTURE,
                        List.of(new QualificationPacketPublisher.Member(
                                transcriptPath, canonicalBytes)));
                return null;
            });
        } catch (Exception writeFailure) {
            if (failure != null) failure.addSuppressed(writeFailure);
            else throw new IllegalStateException("failed to write PTY capture packet", writeFailure);
        }
    }

    private static QualificationPtyEvidence.Event event(String type, String data) {
        return new QualificationPtyEvidence.Event(Instant.now().toString(), type, data);
    }

    private void writeLine(String value) throws Exception {
        OutputStream input = talos.getOutputStream();
        input.write(value.getBytes(StandardCharsets.UTF_8));
        input.write('\r');
        input.flush();
    }

    private String outputThrough(int end) {
        return capture.slice(0, end);
    }

    private QualificationProcessCleanup.Result stopProcesses() {
        try {
            runFiles.requireRegularFileTarget(stopFile);
            try (OutputStream output = Files.newOutputStream(
                    stopFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                output.write("stop\n".getBytes(StandardCharsets.UTF_8));
            }
            runFiles.requireRegularFile(stopFile);
        } catch (Exception ignored) {
        }
        QualificationProcessCleanup.Result result = QualificationProcessCleanup.stop(
                talos,
                powershellExecutable,
                serverExecutable,
                serverPort,
                talosStartedNotBefore,
                allowGracefulCleanup);
        stop(monitor, 5_000L);
        if (outputReader != null) {
            try {
                outputReader.join(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return result;
    }

    private static void stop(Process process, long waitMillis) {
        if (process == null) return;
        try {
            if (!process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(1_000L, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            }
        } catch (Exception ignored) {
            process.destroyForcibly();
        }
    }

    private static String forwardSlashes(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static void deleteOrdinaryFileIfPresent(
            NoFollowPathPolicy.GuardedRoot owner,
            Path path
    ) throws Exception {
        NoFollowPathPolicy.EntryType type = owner.inspect(path);
        if (type == NoFollowPathPolicy.EntryType.MISSING) return;
        owner.requireRegularFile(path);
        Files.delete(path);
    }

    private static Path resolveExecutableOnPath(String executable) throws Exception {
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            throw new IllegalStateException("PATH is unavailable");
        }
        for (String token : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (token == null || token.isBlank()) continue;
            Path candidate;
            try {
                candidate = Path.of(token.strip()).resolve(executable)
                        .toAbsolutePath().normalize();
            } catch (RuntimeException invalid) {
                continue;
            }
            try {
                return NoFollowPathPolicy.requireUnlinkedRegularFile(candidate);
            } catch (NoFollowPathPolicy.Violation rejected) {
                if (rejected.reason() == NoFollowPathPolicy.Reason.MISSING
                        || rejected.reason() == NoFollowPathPolicy.Reason.NOT_REGULAR_FILE) {
                    continue;
                }
                throw rejected;
            }
        }
        throw new IllegalStateException("required qualification executable is unavailable");
    }

    private static String required(String value, String field) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty()) throw new IllegalStateException("missing campaign field: " + field);
        return clean;
    }

    private static String argumentValue(List<String> arguments, String name) {
        for (int index = 0; index + 1 < arguments.size(); index++) {
            if (arguments.get(index).equals(name)) {
                String value = arguments.get(index + 1).strip();
                if (!value.isEmpty()) return value;
            }
        }
        throw new IllegalStateException("missing frozen launch argument: " + name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> source) return (Map<String, Object>) source;
        return Map.of();
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static final class SystemWaiter implements QualificationPtyOutcomeRace.Waiter {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void pause(Duration duration) throws InterruptedException {
            pauseThread(duration);
        }

        private static void pauseThread(Duration duration) throws InterruptedException {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        }
    }

    private static final class CaptureBuffer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        void copyFrom(InputStream input) {
            byte[] buffer = new byte[4096];
            try (input) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    synchronized (bytes) {
                        bytes.write(buffer, 0, read);
                    }
                }
            } catch (Exception ignored) {
                // The controlling thread records marker absence or process exit.
            }
        }

        String text() {
            synchronized (bytes) {
                return bytes.toString(StandardCharsets.UTF_8);
            }
        }

        int length() {
            return text().length();
        }

        String slice(int start, int end) {
            String text = text();
            int safeStart = Math.max(0, Math.min(start, text.length()));
            int safeEnd = Math.max(safeStart, Math.min(end, text.length()));
            return text.substring(safeStart, safeEnd);
        }
    }
}
