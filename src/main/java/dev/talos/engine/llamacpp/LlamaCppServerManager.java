package dev.talos.engine.llamacpp;

import dev.talos.core.security.NoFollowPathPolicy;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ControlFlowFailure;
import dev.talos.spi.types.Health;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class LlamaCppServerManager implements AutoCloseable {
    private static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DEFAULT_READINESS_POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_FORCED_SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);
    private static final LlamaCppProcessTerminationOwner.Policy
            DEFAULT_TERMINATION_POLICY =
            new LlamaCppProcessTerminationOwner.Policy(
                    DEFAULT_SHUTDOWN_TIMEOUT,
                    DEFAULT_FORCED_SHUTDOWN_TIMEOUT);
    private static final int LOG_EXCERPT_BYTES = 1600;
    private static final String DEFAULT_AGENT_PARALLEL = "1";
    private static final String DEFAULT_AGENT_PREDICT = "2048";
    private static final String VERIFICATION_LOG_VERBOSITY = "4";
    private static final List<String> PARALLEL_FLAGS = List.of("--parallel", "-np");
    private static final List<String> PREDICT_FLAGS = List.of("--predict", "--n-predict", "-n");
    private static final List<String> VERBOSITY_FLAGS = List.of("-lv", "--verbosity", "--log-verbosity");

    private final LlamaCppConfig config;
    private final LlamaCppProcessLauncher launcher;
    private final HttpClient http;
    private final Duration readinessTimeout;
    private final Duration readinessPollInterval;
    private final Path logDir;
    private final LlamaCppEndpointOwnerProbe.Probe endpointOwnerProbe;
    private final LlamaCppEndpointOwnerProbe.ObserverIdentityReader endpointObserverIdentityReader;

    private LlamaCppProcess process;
    private LlamaCppProcessTerminationOwner.Lease terminationLease;
    private String lastLaunchFailure = "";
    private String lastTerminationFailure = "";
    private LlamaCppProcessTerminationOwner.Outcome lastTerminationOutcome;
    private boolean ready;
    private long readinessObservedEpochMilli;
    private LlamaCppQualificationLaunchExpectation qualificationExpectation;
    private LlamaCppProcessLaunchObservation qualificationLaunch;
    private LlamaCppEndpointOwnerProbe.ObserverIdentity qualificationEndpointObserver;
    private ProviderProcessLaunchReceipt qualificationReceipt;
    private HttpClient qualificationHttp;
    private QualificationPropsObservation qualificationProps;

    record QualificationTransport(
            ProviderProcessLaunchReceipt receipt,
            HttpClient httpClient) {
        QualificationTransport {
            Objects.requireNonNull(receipt, "provider process receipt");
            Objects.requireNonNull(httpClient, "qualification HTTP client");
        }
    }

    private record QualificationPropsObservation(
            long processId,
            long processStartEpochMilli,
            String launchNonce,
            String responseSha256) {
        QualificationPropsObservation {
            if (processId < 1 || processStartEpochMilli < 1) {
                throw new IllegalArgumentException("qualification props process identity is invalid");
            }
            launchNonce = Objects.toString(launchNonce, "");
            responseSha256 = Objects.toString(responseSha256, "");
            if (!launchNonce.matches("[0-9a-f]{32}")
                    || !responseSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("qualification props identity is invalid");
            }
        }

        boolean matches(LlamaCppProcessLaunchObservation launch) {
            return launch != null
                    && processId == launch.processId()
                    && processStartEpochMilli == launch.processStartEpochMilli()
                    && launchNonce.equals(launch.launchNonce());
        }
    }

    LlamaCppServerManager(LlamaCppConfig config, LlamaCppProcessLauncher launcher, HttpClient http) {
        this(config, launcher, http,
                DEFAULT_READINESS_TIMEOUT,
                DEFAULT_READINESS_POLL_INTERVAL,
                defaultLogDir(),
                LlamaCppEndpointOwnerProbe::observe,
                LlamaCppEndpointOwnerProbe::currentObserverIdentity);
    }

    LlamaCppServerManager(LlamaCppConfig config,
                          LlamaCppProcessLauncher launcher,
                          HttpClient http,
                          Duration readinessTimeout,
                          Duration readinessPollInterval,
                          Path logDir) {
        this(config, launcher, http, readinessTimeout, readinessPollInterval,
                logDir, LlamaCppEndpointOwnerProbe::observe,
                LlamaCppEndpointOwnerProbe::currentObserverIdentity);
    }

    LlamaCppServerManager(LlamaCppConfig config,
                          LlamaCppProcessLauncher launcher,
                          HttpClient http,
                          Duration readinessTimeout,
                           Duration readinessPollInterval,
                           Path logDir,
                           LlamaCppEndpointOwnerProbe.Probe endpointOwnerProbe) {
        this(config, launcher, http, readinessTimeout, readinessPollInterval, logDir,
                endpointOwnerProbe, LlamaCppEndpointOwnerProbe::currentObserverIdentity);
    }

    LlamaCppServerManager(LlamaCppConfig config,
                          LlamaCppProcessLauncher launcher,
                          HttpClient http,
                          Duration readinessTimeout,
                          Duration readinessPollInterval,
                          Path logDir,
                          LlamaCppEndpointOwnerProbe.Probe endpointOwnerProbe,
                          LlamaCppEndpointOwnerProbe.ObserverIdentityReader
                                  endpointObserverIdentityReader) {
        this.config = Objects.requireNonNull(config);
        this.launcher = launcher == null ? new ProcessBuilderLlamaCppProcessLauncher() : launcher;
        this.http = http == null ? HttpClient.newHttpClient() : http;
        this.readinessTimeout = readinessTimeout == null ? DEFAULT_READINESS_TIMEOUT : readinessTimeout;
        this.readinessPollInterval = readinessPollInterval == null
                ? DEFAULT_READINESS_POLL_INTERVAL
                : readinessPollInterval;
        this.logDir = logDir == null ? defaultLogDir() : logDir;
        this.endpointOwnerProbe = Objects.requireNonNull(
                endpointOwnerProbe, "endpoint owner probe");
        this.endpointObserverIdentityReader = Objects.requireNonNull(
                endpointObserverIdentityReader, "endpoint observer identity reader");
    }

    synchronized void ensureStarted() {
        ensureStarted(null);
    }

    synchronized void ensureStarted(LlamaCppQualificationLaunchExpectation expectation) {
        if (!config.managed()) return;
        if (lastTerminationOutcome != null || !lastTerminationFailure.isBlank()) {
            String detail = !lastTerminationFailure.isBlank()
                    ? lastTerminationFailure
                    : "managed llama.cpp process termination remains unproven: "
                            + lastTerminationOutcome.safeSummary();
            throw new EngineException.ConnectionFailed(detail, null);
        }
        if (process != null && process.isAlive()) {
            if (terminationLease == null) {
                throw cacheTerminationOwnershipFailure(
                        "managed llama.cpp process termination ownership "
                                + "is unavailable for the live launch epoch",
                        null);
            }
            requireOutputCaptureHealthy();
            if (expectation != null) requireSameQualificationLaunch(expectation);
            if (ready) return;
            waitForReadiness(logPath());
            return;
        }
        if (process != null || terminationLease != null) {
            stopManagedProcess(logPath(), "previous process exit");
        }
        ready = false;
        readinessObservedEpochMilli = 0L;
        clearQualificationBinding();

        if (expectation != null) {
            // Qualification trust paths are authenticated before the ordinary
            // managed preflight can inspect model metadata through Java's
            // link-following filesystem APIs.
            requireQualificationArgumentSet(expectation);
            requireQualificationTrustPaths();
            requireQualificationEndpointConsistency();
        }
        String validation = managedValidationFailure();
        if (!validation.isBlank()) {
            throw new EngineException.ConnectionFailed(validation, null);
        }

        List<String> command = buildCommand();
        Map<String, String> environment = buildEnvironment();
        if (expectation != null) {
            Map<String, String> inherited = new LinkedHashMap<>(System.getenv());
            inherited.putAll(environment);
            environment = ProviderProcessLaunchReceipt
                    .qualificationEffectiveEnvironment(inherited);
            requireFrozenQualificationEnvironment(environment, expectation);
        }

        if (expectation != null) {
            LlamaCppEndpointOwnerProbe.Observation listener = observeQualificationEndpoint(
                    expectation, 0L, 0L, "preflight");
            if (listener.state() != LlamaCppEndpointOwnerProbe.State.ABSENT) {
                String reason = listener.state()
                                == LlamaCppEndpointOwnerProbe.State.OTHER_OR_AMBIGUOUS
                        || listener.state() == LlamaCppEndpointOwnerProbe.State.EXACT
                        ? "pre-existing listener occupies the managed qualification endpoint"
                        : "managed qualification endpoint listener state is indeterminate";
                throw new EngineException.ConnectionFailed(reason, null);
            }
            qualificationEndpointObserver = listener.observerIdentity();
        }

        Path logPath = logPath();
        try {
            prepareLog(logPath);
            prepareModelCacheDir();
            appendLifecycleLog(logPath, "Talos managed llama.cpp server starting on "
                    + config.listenHost() + ":" + config.port());
            Path qualificationWorkingDirectory = null;
            if (expectation != null) {
                qualificationWorkingDirectory = NoFollowPathPolicy.requireUnlinkedDirectory(
                        Path.of(config.serverPath()).toAbsolutePath()
                                .normalize().getParent());
                // This is the one authoritative content hash for the launch
                // receipt. Keep it after every deterministic preflight and
                // directly adjacent to the exact child launch.
                verifyQualificationContent(expectation);
            }
            process = expectation == null
                    ? launcher.start(command, logPath, environment)
                    : launcher.startWithExactEnvironment(
                            command,
                            logPath,
                            environment,
                            qualificationWorkingDirectory);
            if (process == null) {
                throw new EngineException.ConnectionFailed(
                        "managed llama.cpp launcher returned no process", null);
            }
            try {
                terminationLease = process.terminationLease();
            } catch (RuntimeException ownershipFailure) {
                throw cacheTerminationOwnershipFailure(
                        "managed llama.cpp process termination ownership "
                                + "could not be observed",
                        ownershipFailure);
            }
            if (terminationLease == null) {
                throw cacheTerminationOwnershipFailure(
                        "managed llama.cpp process termination ownership is unavailable",
                        null);
            }
            requireOutputCaptureHealthy();
            appendLifecycleLog(logPath, "Talos managed llama.cpp server process launched");
            if (expectation != null) {
                LlamaCppProcessLaunchObservation observation = process.launchObservation();
                if (observation == null || !observation.exactProcessIdentityAvailable()) {
                    throw new EngineException.ConnectionFailed(
                            "managed qualification process start identity is unavailable", null);
                }
                verifyLaunchObservation(observation, command, environment, expectation);
                qualificationExpectation = expectation;
                qualificationLaunch = observation;
                qualificationHttp = LlamaCppQualificationHttpClient.create(
                        config.listenHost(), config.port(), Duration.ofSeconds(3));
            }
            lastLaunchFailure = "";
        } catch (LlamaCppProcessTerminationOwner.LaunchBlocked blocked) {
            lastLaunchFailure = blocked.getMessage();
            ready = false;
            readinessObservedEpochMilli = 0L;
            throw new EngineException.ConnectionFailed(
                    lastLaunchFailure,
                    blocked);
        } catch (LlamaCppProcessTerminationOwner.Unsettled unsettled) {
            terminationLease = unsettled.lease();
            lastTerminationOutcome = unsettled.outcome();
            lastTerminationFailure =
                    "managed llama.cpp process termination remains unproven: "
                            + unsettled.outcome().safeSummary();
            ready = false;
            readinessObservedEpochMilli = 0L;
            throw new EngineException.ConnectionFailed(
                    lastTerminationFailure,
                    unsettled);
        } catch (IOException e) {
            lastLaunchFailure = "failed to launch llama.cpp server: " + e.getMessage();
            throw new EngineException.ConnectionFailed("llama_cpp launch: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            try {
                stopManagedProcess(logPath, "launch identity failed");
            } catch (RuntimeException terminationFailure) {
                terminationFailure.addSuppressed(e);
                throw terminationFailure;
            }
            throw e;
        }
        try {
            waitForReadiness(logPath);
        } catch (RuntimeException e) {
            try {
                stopManagedProcess(logPath, "readiness failed");
            } catch (RuntimeException terminationFailure) {
                terminationFailure.addSuppressed(e);
                throw terminationFailure;
            }
            throw e;
        }
    }

    synchronized ProviderProcessLaunchReceipt bindQualificationReceipt(
            LlamaCppQualificationLaunchExpectation expectation,
            String resolvedProfile,
            boolean profileQualified) {
        requireSameQualificationLaunch(expectation);
        requireOutputCaptureHealthy();
        if (!ready) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification process is not ready", null);
        }
        if (!profileQualified) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification profile is not fully qualified", null);
        }
        if (readinessObservedEpochMilli < qualificationLaunch.launchObservedEpochMilli()) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification readiness observation is unavailable", null);
        }
        if (qualificationProps == null || !qualificationProps.matches(qualificationLaunch)) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification /props observation is unavailable", null);
        }
        requireQualificationEndpointOwner("receipt binding");
        ProviderProcessLaunchReceipt exact = new ProviderProcessLaunchReceipt(
                qualificationLaunch.processId(),
                qualificationLaunch.processStartEpochMilli(),
                qualificationLaunch.launchObservedEpochMilli(),
                readinessObservedEpochMilli,
                qualificationLaunch.launchNonce(),
                expectation.candidateDeclarationSha256(),
                expectation.engineExecutableSha256(),
                expectation.modelArtifactSha256(),
                qualificationLaunch.launchCommandSha256(),
                expectation.launchArgumentSet(),
                qualificationLaunch.effectiveEnvironmentSha256(),
                ProviderProcessLaunchReceipt.EnvironmentContract
                        .QUALIFICATION_RELEVANT_ALLOWLIST_V1,
                config.listenHost(),
                config.port(),
                true,
                qualificationProps.responseSha256(),
                resolvedProfile,
                ProviderProcessLaunchReceipt.ServedIdentity.MATCHED,
                qualificationEndpointObserver.kind(),
                qualificationEndpointObserver.sha256(),
                qualificationEndpointObserver.kind().ownershipBoundary(),
                qualificationEndpointObserver.kind().stableAssumption(),
                ProviderProcessLaunchReceipt.ContentVerificationBoundary
                        .IMMEDIATE_PRELAUNCH_NOFOLLOW_SHA256,
                ProviderProcessLaunchReceipt.StableFilesystemAssumption
                        .STABLE_LOCAL_FILES_DURING_PROCESS_LOAD);
        if (qualificationReceipt != null && !qualificationReceipt.equals(exact)) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification process receipt rebind is inconsistent", null);
        }
        qualificationReceipt = exact;
        return exact;
    }

    synchronized ProviderProcessLaunchReceipt requireActiveQualificationReceipt() {
        requireOutputCaptureHealthy();
        if (qualificationReceipt == null
                || process == null
                || !ready
                || terminationLease == null
                || lastTerminationOutcome != null
                || !lastTerminationFailure.isBlank()
                || !process.isAlive()
                || qualificationLaunch == null
                || !qualificationLaunch.sameProcess(process.launchObservation())) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification process receipt is stale or unavailable", null);
        }
        requireQualificationEndpointOwner("transport lifecycle");
        return qualificationReceipt;
    }

    synchronized QualificationTransport requireActiveQualificationTransport() {
        ProviderProcessLaunchReceipt receipt = requireActiveQualificationReceipt();
        if (qualificationHttp == null) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification HTTP epoch is unavailable", null);
        }
        return new QualificationTransport(receipt, qualificationHttp);
    }

    synchronized LlamaCppPropsClient.Result fetchQualificationProps(
            LlamaCppQualificationLaunchExpectation expectation) {
        requireSameQualificationLaunch(expectation);
        requireOutputCaptureHealthy();
        if (!ready || qualificationHttp == null) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification process is not ready for /props", null);
        }
        requireQualificationEndpointOwner("props pre-send");
        LlamaCppPropsClient.Result result =
                new LlamaCppPropsClient(qualificationHttp)
                        .fetch(URI.create(config.baseUrl()));
        requireQualificationEndpointOwner("props completion");
        requireOutputCaptureHealthy();
        qualificationProps = result.state() == LlamaCppPropsClient.State.AVAILABLE
                ? new QualificationPropsObservation(
                        qualificationLaunch.processId(),
                        qualificationLaunch.processStartEpochMilli(),
                        qualificationLaunch.launchNonce(),
                        result.props().responseSha256())
                : null;
        return result;
    }

    synchronized Health health() {
        if (!lastTerminationFailure.isBlank()) {
            return Health.down(lastTerminationFailure);
        }
        if (lastTerminationOutcome != null) {
            return Health.down(
                    "managed llama.cpp process termination remains unproven: "
                            + lastTerminationOutcome.safeSummary());
        }
        if (process != null) {
            if (!observeOutputCaptureHealthy()) {
                return Health.down(
                        "managed llama.cpp output capture is incomplete");
            }
        }
        String validation = managedValidationFailure();
        if (!validation.isBlank()) return Health.down(validation);
        if (!lastLaunchFailure.isBlank()) return Health.down(lastLaunchFailure);
        return httpHealth();
    }

    List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add(config.serverPath());
        command.addAll(buildArguments(config));
        return command;
    }

    static List<String> buildArguments(LlamaCppConfig config) {
        List<String> command = new ArrayList<>();
        if (config.hasHfSource()) {
            command.add("--hf-repo");
            command.add(config.hfRepo());
            if (config.hfFile() != null && !config.hfFile().isBlank()) {
                command.add("--hf-file");
                command.add(config.hfFile());
            }
        } else {
            command.add("-m");
            command.add(config.modelPath());
        }
        command.add("-c");
        command.add(String.valueOf(config.context()));
        command.add("--host");
        command.add(config.listenHost());
        command.add("--port");
        command.add(String.valueOf(config.port()));
        if (config.jinja()) {
            command.add("--jinja");
        }
        if (config.model() != null && !config.model().isBlank()) {
            command.add("--alias");
            command.add(config.model());
        }
        if (config.chatTemplate() != null && !config.chatTemplate().isBlank()) {
            command.add("--chat-template");
            command.add(config.chatTemplate());
        }
        if (config.chatTemplateFile() != null && !config.chatTemplateFile().isBlank()) {
            command.add("--chat-template-file");
            command.add(config.chatTemplateFile());
        }
        appendManagedAgentDefault(command, config.serverArgs(), PARALLEL_FLAGS, "--parallel", DEFAULT_AGENT_PARALLEL);
        appendManagedAgentDefault(command, config.serverArgs(), PREDICT_FLAGS, "--predict", DEFAULT_AGENT_PREDICT);
        // Debug log verbosity is scoped to verification launches (doctor
        // --start, tune): it puts the offload and rate evidence lines in the
        // server log, but it also writes prompt and workspace content there,
        // so ordinary sessions stay at llama.cpp's normal verbosity. A
        // user-configured verbosity in server_args always wins.
        if (config.verificationLogging()) {
            appendManagedAgentDefault(command, config.serverArgs(), VERBOSITY_FLAGS, "-lv", VERIFICATION_LOG_VERBOSITY);
        }
        command.addAll(LlamaCppContextArgs.sanitize(config.serverArgs()));
        return List.copyOf(command);
    }

    Map<String, String> buildEnvironment() {
        if (!config.hasHfSource()) return Map.of();
        if (config.hfCacheDir() == null || config.hfCacheDir().isBlank()) return Map.of();
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HF_HOME", config.hfCacheDir());
        return environment;
    }

    private void prepareModelCacheDir() throws IOException {
        if (!config.hasHfSource()) return;
        if (config.hfCacheDir() == null || config.hfCacheDir().isBlank()) return;
        Files.createDirectories(Path.of(config.hfCacheDir()));
    }

    private static void appendManagedAgentDefault(List<String> command,
                                                  List<String> serverArgs,
                                                  List<String> overrideFlags,
                                                  String flag,
                                                  String value) {
        if (hasOverrideFlag(serverArgs, overrideFlags)) return;
        command.add(flag);
        command.add(value);
    }

    private static boolean hasOverrideFlag(List<String> serverArgs, List<String> flags) {
        if (serverArgs == null || serverArgs.isEmpty()) return false;
        for (String raw : serverArgs) {
            String arg = raw == null ? "" : raw.trim();
            if (arg.isBlank()) continue;
            for (String flag : flags) {
                if (arg.equals(flag) || arg.startsWith(flag + "=")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String managedValidationFailure() {
        // Single source of truth shared with the doctor preflight (T784);
        // the failure strings are pinned by both test suites.
        return LlamaCppPreflight.validationFailure(config);
    }

    private void verifyQualificationContent(
            LlamaCppQualificationLaunchExpectation expectation) {
        try {
            String engine = sha256NoFollow(Path.of(config.serverPath()));
            if (!engine.equals(expectation.engineExecutableSha256())) {
                throw new EngineException.ConnectionFailed(
                        "managed qualification engine content identity changed before launch", null);
            }
            String model = sha256NoFollow(Path.of(config.modelPath()));
            if (!model.equals(expectation.modelArtifactSha256())) {
                throw new EngineException.ConnectionFailed(
                        "managed qualification model content identity changed before launch", null);
            }
        } catch (EngineException.ConnectionFailed failure) {
            throw failure;
        } catch (Exception failure) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification content identity could not be observed", failure);
        }
    }

    private void requireQualificationTrustPaths() {
        try {
            Path engine = NoFollowPathPolicy.requireUnlinkedRegularFile(
                    Path.of(config.serverPath()));
            NoFollowPathPolicy.requireUnlinkedDirectory(
                    engine.toAbsolutePath().normalize().getParent());
            NoFollowPathPolicy.requireUnlinkedRegularFile(Path.of(config.modelPath()));
        } catch (Exception failure) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification trust paths are missing or linked", failure);
        }
    }

    private void requireQualificationEndpointConsistency() {
        try {
            URI endpoint = URI.create(config.baseUrl());
            String endpointHost = Objects.toString(endpoint.getHost(), "");
            if (!"http".equalsIgnoreCase(endpoint.getScheme())
                    || !"127.0.0.1".equals(config.listenHost())
                    || !endpointHost.equalsIgnoreCase(config.listenHost())
                    || endpoint.getPort() != config.port()
                    || endpoint.getUserInfo() != null
                    || (endpoint.getPath() != null
                        && !endpoint.getPath().isBlank()
                        && !"/".equals(endpoint.getPath()))
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null) {
                throw new EngineException.ConnectionFailed(
                        "managed qualification probe endpoint differs from its launch endpoint",
                        null);
            }
        } catch (EngineException.ConnectionFailed failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification probe endpoint is invalid", invalid);
        }
    }

    private void requireSameQualificationLaunch(
            LlamaCppQualificationLaunchExpectation expectation) {
        requireQualificationArgumentSet(expectation);
        if (expectation == null
                || qualificationExpectation == null
                || !qualificationExpectation.equals(expectation)
                || qualificationLaunch == null
                || process == null
                || !process.isAlive()
                || !qualificationLaunch.sameProcess(process.launchObservation())) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification launch identity is stale or mismatched", null);
        }
    }

    private void requireQualificationArgumentSet(
            LlamaCppQualificationLaunchExpectation expectation) {
        ProviderProcessLaunchReceipt.LaunchArgumentSet expected =
                config.verificationLogging()
                        ? ProviderProcessLaunchReceipt.LaunchArgumentSet.VERIFICATION
                        : ProviderProcessLaunchReceipt.LaunchArgumentSet.STANDARD;
        if (expectation == null || expectation.launchArgumentSet() != expected) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification launch argument set differs from configuration",
                    null);
        }
    }

    private static void verifyLaunchObservation(
            LlamaCppProcessLaunchObservation observation,
            List<String> command,
            Map<String, String> environmentOverlay,
            LlamaCppQualificationLaunchExpectation expectation) {
        String expectedCommand = ProviderProcessLaunchReceipt.digestOrderedValues(command);
        if (!expectedCommand.equals(observation.launchCommandSha256())) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification launch command identity is inconsistent", null);
        }
        String expectedEnvironment = ProviderProcessLaunchReceipt.digestEffectiveEnvironment(
                environmentOverlay == null ? Map.of() : environmentOverlay);
        if (!expectedEnvironment.equals(observation.effectiveEnvironmentSha256())) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification launch environment identity is inconsistent", null);
        }
        if (!expectedEnvironment.equals(expectation.effectiveEnvironmentSha256())) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification launch environment differs from frozen calibration",
                    null);
        }
    }

    private static void requireFrozenQualificationEnvironment(
            Map<String, String> environment,
            LlamaCppQualificationLaunchExpectation expectation) {
        String actual = ProviderProcessLaunchReceipt.digestEffectiveEnvironment(environment);
        if (!actual.equals(expectation.effectiveEnvironmentSha256())) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification launch environment differs from frozen calibration",
                    null);
        }
    }

    private void requireQualificationEndpointOwner(String boundary) {
        if (qualificationLaunch == null || qualificationEndpointObserver == null) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification endpoint owner is unavailable at " + boundary,
                    null);
        }
        LlamaCppEndpointOwnerProbe.Observation owner = observeQualificationEndpoint(
                qualificationExpectation,
                qualificationLaunch.processId(),
                qualificationLaunch.processStartEpochMilli(),
                boundary);
        requireStableEndpointObserver(owner, boundary);
        if (!owner.matches(
                qualificationLaunch.processId(),
                qualificationLaunch.processStartEpochMilli())) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification endpoint owner differs from the launched process at "
                            + boundary,
                    null);
        }
    }

    private void clearQualificationBinding() {
        qualificationExpectation = null;
        qualificationLaunch = null;
        qualificationEndpointObserver = null;
        qualificationReceipt = null;
        qualificationHttp = null;
        qualificationProps = null;
    }

    private static String sha256NoFollow(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = NoFollowPathPolicy.openUnlinkedRegularFile(path)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Health httpHealth() {
        try {
            if (qualificationLaunch != null) {
                requireQualificationEndpointOwner("health pre-send");
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpClient client = qualificationLaunch == null ? http : qualificationHttp;
            if (client == null) {
                return Health.down("llama.cpp qualification HTTP epoch is unavailable");
            }
            HttpResponse<Void> response = client.send(
                    request, HttpResponse.BodyHandlers.discarding());
            if (qualificationLaunch != null) {
                requireQualificationEndpointOwner("readiness completion");
            }
            if (response.statusCode() / 100 == 2) {
                return Health.ok("llama_cpp", true);
            }
            return Health.down("llama.cpp health check failed: HTTP " + response.statusCode());
        } catch (InterruptedException interrupted) {
            throw ControlFlowFailure.propagate(interrupted);
        } catch (ConnectException e) {
            return Health.down("llama.cpp health check failed: connection refused");
        } catch (Exception e) {
            if (ControlFlowFailure.classify(e) != ControlFlowFailure.Kind.NONE) {
                throw ControlFlowFailure.propagate(e);
            }
            return Health.down("llama.cpp health check failed: " + e.getMessage());
        }
    }

    private void waitForReadiness(Path logPath) {
        long deadline = System.nanoTime() + Math.max(1L, readinessTimeout.toNanos());
        String lastHealth = "not checked";

        while (System.nanoTime() <= deadline) {
            if (process == null || !process.isAlive()) {
                lastLaunchFailure = "llama.cpp server exited before readiness. "
                        + logExcerptSuffix(logPath);
                throw new EngineException.ConnectionFailed(lastLaunchFailure, null);
            }
            requireOutputCaptureHealthy();

            if (qualificationLaunch != null && !qualificationListenerReady()) {
                lastHealth = "managed qualification listener is not ready";
                sleepPollInterval();
                continue;
            }
            Health health = httpHealth();
            if (health.ok()) {
                requireOutputCaptureHealthy();
                if (qualificationLaunch != null) {
                    requireQualificationEndpointOwner("readiness");
                }
                lastLaunchFailure = "";
                ready = true;
                readinessObservedEpochMilli = Math.max(
                        Instant.now().toEpochMilli(),
                        qualificationLaunch == null
                                ? 1L
                                : qualificationLaunch.launchObservedEpochMilli());
                return;
            }
            lastHealth = health.message();
            sleepPollInterval();
        }

        lastLaunchFailure = "llama.cpp server did not become ready within "
                + readinessTimeout.toSeconds()
                + "s; last health: " + lastHealth
                + ". " + logExcerptSuffix(logPath);
        throw new EngineException.ConnectionFailed(lastLaunchFailure, null);
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(Math.max(1L, readinessPollInterval.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastLaunchFailure = "interrupted while waiting for llama.cpp readiness";
            throw new RuntimeException(e);
        }
    }

    private Path logPath() {
        return LlamaCppRuntimePaths.managedLogFile(logDir, config.port());
    }

    private static Path defaultLogDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("USERPROFILE");
        }
        Path base = home == null || home.isBlank()
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(home);
        return base.resolve(".talos").resolve("logs");
    }

    private static void prepareLog(Path logPath) throws IOException {
        if (logPath == null) return;
        Files.createDirectories(logPath.getParent());
        Files.writeString(logPath, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void appendLifecycleLog(Path logPath, String message) {
        if (logPath == null || message == null || message.isBlank()) return;
        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(logPath,
                    "[" + Instant.now() + "] " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Lifecycle diagnostics are best-effort and must not mask engine errors.
        }
    }

    private static String logExcerptSuffix(Path logPath) {
        String excerpt = logExcerpt(logPath);
        return excerpt.isBlank() ? "No llama.cpp server log excerpt available." : "Log excerpt: " + excerpt;
    }

    private static String logExcerpt(Path logPath) {
        if (logPath == null || !Files.isRegularFile(logPath)) return "";
        try {
            byte[] bytes = Files.readAllBytes(logPath);
            int start = Math.max(0, bytes.length - LOG_EXCERPT_BYTES);
            return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8)
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    @Override
    public synchronized void close() {
        stopManagedProcess(logPath(), "close");
    }

    private void stopManagedProcess(Path logPath, String reason) {
        if (process == null && terminationLease == null) return;
        LlamaCppProcess ownedProcess = process;
        ready = false;
        readinessObservedEpochMilli = 0L;
        appendLifecycleLog(logPath, "Talos managed llama.cpp server stopping: "
                + Objects.toString(reason, ""));

        LlamaCppProcessTerminationOwner.Lease lease = terminationLease;
        if (lease == null && ownedProcess != null) {
            try {
                lease = ownedProcess.terminationLease();
            } catch (RuntimeException ownershipFailure) {
                EngineException.ConnectionFailed failure =
                        cacheTerminationOwnershipFailure(
                                "managed llama.cpp process termination ownership "
                                        + "could not be observed",
                                ownershipFailure);
                appendLifecycleLog(logPath, lastTerminationFailure);
                throw failure;
            }
            terminationLease = lease;
        }
        if (lease == null) {
            EngineException.ConnectionFailed failure =
                    cacheTerminationOwnershipFailure(
                            "managed llama.cpp process termination ownership is unavailable",
                            null);
            appendLifecycleLog(logPath, lastTerminationFailure);
            throw failure;
        }

        LlamaCppProcessTerminationOwner.Outcome outcome =
                lease.terminate(DEFAULT_TERMINATION_POLICY);
        if (!outcome.terminated()) {
            lastTerminationOutcome = outcome;
            lastTerminationFailure =
                    "managed llama.cpp process termination remains unproven: "
                            + outcome.safeSummary();
            appendLifecycleLog(logPath, lastTerminationFailure);
            throw new EngineException.ConnectionFailed(
                    lastTerminationFailure,
                    new LlamaCppProcessTerminationOwner.Unsettled(
                            lastTerminationFailure,
                            null,
                            outcome,
                            lease));
        }

        boolean outputCaptureHealthy = true;
        if (ownedProcess != null) {
            try {
                outputCaptureHealthy = ownedProcess.outputCaptureHealthy();
            } catch (RuntimeException unavailable) {
                outputCaptureHealthy = false;
            }
        }

        appendLifecycleLog(logPath, "Talos managed llama.cpp server stopped");
        process = null;
        terminationLease = null;
        lastTerminationOutcome = null;
        lastTerminationFailure = "";
        clearQualificationBinding();
        if (!outputCaptureHealthy) {
            lastLaunchFailure =
                    "managed llama.cpp output capture is incomplete";
            appendLifecycleLog(logPath, lastLaunchFailure);
            throw new EngineException.ConnectionFailed(
                    lastLaunchFailure,
                    null);
        }
    }

    private void requireOutputCaptureHealthy() {
        if (observeOutputCaptureHealthy()) return;
        throw new EngineException.ConnectionFailed(lastLaunchFailure, null);
    }

    private boolean observeOutputCaptureHealthy() {
        boolean healthy = process == null;
        try {
            if (process != null) healthy = process.outputCaptureHealthy();
        } catch (RuntimeException unavailable) {
            healthy = false;
        }
        if (healthy) return true;
        lastLaunchFailure = "managed llama.cpp output capture is incomplete";
        ready = false;
        readinessObservedEpochMilli = 0L;
        return false;
    }

    private EngineException.ConnectionFailed cacheTerminationOwnershipFailure(
            String message,
            Throwable cause) {
        ready = false;
        readinessObservedEpochMilli = 0L;
        lastTerminationOutcome = null;
        lastTerminationFailure = Objects.toString(message, "");
        return new EngineException.ConnectionFailed(
                lastTerminationFailure,
                cause);
    }

    private boolean qualificationListenerReady() {
        LlamaCppEndpointOwnerProbe.Observation owner = observeQualificationEndpoint(
                qualificationExpectation,
                qualificationLaunch.processId(),
                qualificationLaunch.processStartEpochMilli(),
                "readiness pre-send");
        requireStableEndpointObserver(owner, "readiness pre-send");
        if (owner.state() == LlamaCppEndpointOwnerProbe.State.ABSENT) return false;
        if (!owner.matches(
                qualificationLaunch.processId(),
                qualificationLaunch.processStartEpochMilli())) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification endpoint owner differs from the launched process at readiness pre-send",
                    null);
        }
        return true;
    }

    private LlamaCppEndpointOwnerProbe.Observation observeQualificationEndpoint(
            LlamaCppQualificationLaunchExpectation expectation,
            long expectedProcessId,
            long expectedStartEpochMilli,
            String boundary) {
        LlamaCppEndpointOwnerProbe.ObserverIdentity current =
                requireFrozenEndpointObserver(expectation, boundary);
        LlamaCppEndpointOwnerProbe.Observation observation = endpointOwnerProbe.observe(
                config.listenHost(),
                config.port(),
                expectedProcessId,
                expectedStartEpochMilli,
                Duration.ofSeconds(5));
        if (observation == null || !current.equals(observation.observerIdentity())) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification endpoint observer changed while observing "
                            + boundary,
                    null);
        }
        return observation;
    }

    private LlamaCppEndpointOwnerProbe.ObserverIdentity requireFrozenEndpointObserver(
            LlamaCppQualificationLaunchExpectation expectation,
            String boundary) {
        final LlamaCppEndpointOwnerProbe.ObserverIdentity current;
        try {
            current = endpointObserverIdentityReader.current();
        } catch (Exception unavailable) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification endpoint observer identity is unavailable at "
                            + boundary,
                    unavailable);
        }
        if (expectation == null
                || current == null
                || current.kind() != expectation.endpointObserverKind()
                || !current.sha256().equals(expectation.endpointObserverIdentitySha256())) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification endpoint observer differs from frozen calibration at "
                            + boundary,
                    null);
        }
        return current;
    }

    private void requireStableEndpointObserver(
            LlamaCppEndpointOwnerProbe.Observation observation,
            String boundary) {
        if (observation == null
                || qualificationEndpointObserver == null
                || !qualificationEndpointObserver.equals(observation.observerIdentity())) {
            throw new EngineException.ConnectionFailed(
                    "managed qualification endpoint observer differs from preflight at "
                            + boundary,
                    null);
        }
    }

}
