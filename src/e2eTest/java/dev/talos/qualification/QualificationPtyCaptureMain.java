package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Windows true-PTY capture entrypoint for immutable local model-qualification campaigns. */
public final class QualificationPtyCaptureMain {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Duration STARTUP_WAIT = Duration.ofSeconds(30);
    private static final Duration STARTUP_RECOVERY_WAIT = Duration.ofSeconds(10);
    private static final Duration TURN_WAIT = Duration.ofSeconds(330);
    private static final Duration SLASH_WAIT = Duration.ofSeconds(30);
    private static final String PROMPT = QualificationPtyOutcomeRace.PROMPT_MARKER;

    private final Path campaignRoot;
    private final QualificationRecords.CampaignManifest campaign;
    private final Path runRoot;
    private final Path workspace;
    private final Path monitorScript;
    private final Path stopFile;
    private final Path resourceSamples;
    private final Path transcriptPath;
    private final Path llamaLog;
    private final Path serverExecutable;
    private final int serverPort;
    private final Path installedTalos;
    private final String prompt;
    private final String approvalBehavior;
    private final int protocolVersion;
    private final CaptureBuffer capture = new CaptureBuffer();
    private final List<Map<String, Object>> events = new ArrayList<>();
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
        this.campaignRoot = campaignRoot.toAbsolutePath().normalize();
        JsonNode campaignNode = JSON.readTree(this.campaignRoot.resolve("campaign.json").toFile());
        this.campaign = JSON.treeToValue(
                campaignNode, QualificationRecords.CampaignManifest.class);
        String candidateAlias = required(campaign.candidateAlias(), "candidateAlias");
        String machineId = required(campaign.machineId(), "machineId");
        String configId = required(campaign.configId(), "configId");
        this.protocolVersion = campaign.protocolVersion();
        if (protocolVersion <= 0) {
            throw new IllegalStateException("campaign protocolVersion is missing or invalid");
        }
        JsonNode calibration = JSON.readTree(this.campaignRoot.resolve(Path.of(
                "candidates", candidateAlias, "calibration-rules.json")).toFile());
        String serverPortText = argumentValue(calibration.path("launchArguments"), "--port");
        this.serverPort = Integer.parseInt(serverPortText);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = YAML.readValue(
                this.campaignRoot.resolve(Path.of("home", ".talos", "config.yaml")).toFile(),
                Map.class);
        Map<String, Object> engines = map(config.get("engines"));
        Map<String, Object> llamaCpp = map(engines.get("llama_cpp"));
        String configuredServerPath = String.valueOf(llamaCpp.getOrDefault("server_path", "")).strip();
        int configuredPort = intValue(llamaCpp.get("port"), -1);
        if (configuredServerPath.isBlank() || configuredPort != this.serverPort) {
            throw new IllegalStateException(
                    "campaign config server path/port does not match frozen calibration");
        }
        this.serverExecutable = Path.of(configuredServerPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(this.serverExecutable)) {
            throw new IllegalStateException("configured llama.cpp server is missing: "
                    + this.serverExecutable);
        }
        String trial = "trial-%02d".formatted(repetition);
        this.runRoot = this.campaignRoot.resolve(Path.of(
                "runs", candidateAlias, machineId, configId, scenarioId, trial));
        this.workspace = this.campaignRoot.resolve(Path.of("workspaces", scenarioId, trial));
        this.monitorScript = this.campaignRoot.resolve(Path.of("tools", "monitor-resources.ps1"));
        this.stopFile = runRoot.resolve("resource-monitor.stop");
        this.resourceSamples = runRoot.resolve("resource-samples.csv");
        this.transcriptPath = runRoot.resolve("transcript.raw.json");
        this.llamaLog = this.campaignRoot.resolve(Path.of(
                "home", ".talos", "logs", "llama_cpp-" + serverPortText + ".log"));
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            throw new IllegalStateException("LOCALAPPDATA is unavailable");
        }
        this.installedTalos = Path.of(localAppData, "Programs", "talos", "bin", "talos.bat")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace) || !Files.isDirectory(runRoot)) {
            throw new IllegalStateException("prepare-trial.ps1 must run first");
        }
        if (Files.exists(transcriptPath)) {
            throw new IllegalStateException("refusing to overwrite " + transcriptPath);
        }
        JsonNode scenario = JSON.readTree(runRoot.resolve("scenario.json").toFile());
        this.prompt = scenario.path("prompt").asText();
        this.approvalBehavior = scenario.path("approvalBehavior").asText();
        if (prompt.isBlank() || approvalBehavior.isBlank()) {
            throw new IllegalStateException("scenario prompt or approval behavior is blank");
        }
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

    private void run() throws Exception {
        Instant started = Instant.now();
        Exception captureFailure = null;
        try {
            startMonitor();
            startTalos();
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
                Files.writeString(runRoot.resolve("transcript.failed.txt"),
                        capture.text() + System.lineSeparator() + captureFailure,
                        StandardCharsets.UTF_8);
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
                runRoot, capture.slice(promptDebugAnchor, cursor));

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
        Files.deleteIfExists(stopFile);
        monitor = new ProcessBuilder(
                "pwsh", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                monitorScript.toString(), "-Output", resourceSamples.toString(),
                "-StopFile", stopFile.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private void startTalos() throws Exception {
        Path winpty = Path.of("C:/Program Files/Git/usr/bin/winpty.exe");
        if (!Files.isRegularFile(winpty) || !Files.isRegularFile(installedTalos)) {
            throw new IllegalStateException("winpty or installed Talos launcher is missing");
        }
        Files.deleteIfExists(llamaLog);
        ProcessBuilder builder = new ProcessBuilder(
                winpty.toString(), "-Xallow-non-tty", "--", installedTalos.toString())
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        Path canonicalRoot = campaignRoot.toRealPath();
        Path calibration = canonicalRoot.resolve(Path.of(
                "candidates", campaign.candidateAlias(), "calibration-rules.json")).toRealPath();
        String javaOptions = String.join(" ",
                jvmProperty("user.home", canonicalRoot.resolve("home").toString()),
                jvmProperty("talos.qualification.root", canonicalRoot.toString()),
                jvmProperty("talos.qualification.calibration", calibration.toString()),
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
        waitForFile(llamaLog, "processing task", Duration.ofSeconds(60));
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

    private static void waitForFile(Path path, String marker, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path)
                    && Files.readString(path, StandardCharsets.UTF_8).contains(marker)) {
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
            Map<String, Object> packet = new LinkedHashMap<>();
            packet.put("schemaName", "talos.modelQualification.ptyCapture");
            packet.put("schemaVersion", protocolVersion);
            packet.put("transport", "WINDOWS_PTY");
            packet.put("tty", true);
            packet.put("captureStartedAt", started.toString());
            packet.put("captureEndedAt", Instant.now().toString());
            packet.put("command", forwardSlashes(installedTalos));
            packet.put("workspace", forwardSlashes(workspace));
            packet.put("expectedApprovalBehavior", approvalBehavior);
            packet.put("terminalOutcome", terminalOutcome);
            packet.put("captureFailure", failure == null ? "" : failure.toString());
            packet.put("processCleanup", cleanupResult == null ? Map.of() : cleanupResult.evidence());
            packet.put("events", events);
            packet.put("rawOutput", capture.text());
            JSON.writeValue(transcriptPath.toFile(), packet);
        } catch (Exception writeFailure) {
            if (failure != null) failure.addSuppressed(writeFailure);
            else throw new IllegalStateException("failed to write PTY capture packet", writeFailure);
        }
    }

    private static Map<String, Object> event(String type, String data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("type", type);
        event.put("data", data);
        return event;
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
            Files.writeString(stopFile, "stop\n", StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        QualificationProcessCleanup.Result result = QualificationProcessCleanup.stop(
                talos, serverExecutable, serverPort, talosStartedNotBefore, allowGracefulCleanup);
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

    private static String required(String value, String field) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty()) throw new IllegalStateException("missing campaign field: " + field);
        return clean;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").strip();
        if (value.isEmpty()) throw new IllegalStateException("missing campaign field: " + field);
        return value;
    }

    private static String argumentValue(JsonNode arguments, String name) {
        if (!arguments.isArray()) throw new IllegalStateException("launchArguments must be an array");
        for (int index = 0; index + 1 < arguments.size(); index++) {
            if (arguments.get(index).asText("").equals(name)) {
                String value = arguments.get(index + 1).asText("").strip();
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
