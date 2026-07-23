package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import dev.talos.spi.types.ProviderTransportHandoff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppProviderProcessBindingTest {

    private static final LlamaCppEndpointOwnerProbe.ObserverIdentity OBSERVER_A =
            new LlamaCppEndpointOwnerProbe.ObserverIdentity(
                    ProviderProcessLaunchReceipt.EndpointObserverKind
                            .LINUX_PROCFS_MANAGED_PROCESS_TREE_V1,
                    "8".repeat(64));
    private static final LlamaCppEndpointOwnerProbe.ObserverIdentity OBSERVER_B =
            new LlamaCppEndpointOwnerProbe.ObserverIdentity(
                    ProviderProcessLaunchReceipt.EndpointObserverKind
                            .LINUX_PROCFS_MANAGED_PROCESS_TREE_V1,
                    "9".repeat(64));

    @TempDir Path tempDir;
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    @Test
    void qualificationLaunchRejectsAPreExistingListenerBeforeStartingTheChild() throws Exception {
        Fixture fixture = fixture("engine", "model");
        QueueLauncher launcher = launcher(observation(7101, 1_721_600_000_000L, fixture));
        LlamaCppServerManager manager = manager(
                fixture, launcher, LlamaCppEndpointOwnerProbe.Observation.other(OBSERVER_A));

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(fixture.expectation()));

        assertTrue(failure.getMessage().contains("pre-existing listener"));
        assertEquals(0, launcher.starts);
    }

    @Test
    void defaultListenerProbeRejectsARealPreExistingListener() throws Exception {
        Fixture fixture = fixture("engine", "model");
        LlamaCppQualificationLaunchExpectation expectation = withObserver(
                fixture.expectation(), LlamaCppEndpointOwnerProbe.currentObserverIdentity());
        QueueLauncher launcher = launcher(observation(7101, 1_721_600_000_000L, fixture));
        LlamaCppServerManager manager = new LlamaCppServerManager(
                fixture.config(),
                launcher,
                HttpClient.newHttpClient(),
                Duration.ofSeconds(2),
                Duration.ofMillis(5),
                tempDir.resolve("real-listener-logs"));

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(expectation));

        assertTrue(failure.getMessage().contains("pre-existing listener"));
        assertEquals(0, launcher.starts);
    }

    @Test
    void defaultProbeRejectsAnUnrelatedListenerThatAppearsAfterPreflight()
            throws Exception {
        Fixture fixture = fixture("engine", "model");
        LlamaCppQualificationLaunchExpectation expectation = withObserver(
                fixture.expectation(), LlamaCppEndpointOwnerProbe.currentObserverIdentity());
        int port = fixture.server().getAddress().getPort();
        fixture.server().stop(0);
        servers.remove(fixture.server());
        assertPortCanBeRebound(port);

        LlamaCppProcessLauncher launcher = new LlamaCppProcessLauncher() {
            @Override
            public LlamaCppProcess start(List<String> command, Path logPath) throws IOException {
                return start(command, logPath, Map.of());
            }

            @Override
            public LlamaCppProcess start(
                    List<String> command,
                    Path logPath,
                    Map<String, String> environment) throws IOException {
                HttpServer child = HttpServer.create(
                        new InetSocketAddress("127.0.0.1", port), 0);
                child.createContext("/health", exchange -> {
                    byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
                child.start();
                servers.add(child);
                long observed = System.currentTimeMillis();
                ProcessBuilder exact = new ProcessBuilder(command);
                exact.environment().clear();
                exact.environment().putAll(environment);
                return new FakeProcess(new LlamaCppProcessLaunchObservation(
                        7101L,
                        Math.max(1L, observed - 1L),
                        observed,
                        "0123456789abcdef0123456789abcdef",
                        ProviderProcessLaunchReceipt.digestOrderedValues(command),
                        ProviderProcessLaunchReceipt.digestEffectiveEnvironment(
                                exact.environment())));
            }

            @Override
            public LlamaCppProcess startWithExactEnvironment(
                    List<String> command,
                    Path logPath,
                    Map<String, String> environment,
                    Path workingDirectory) throws IOException {
                return start(command, logPath, environment);
            }
        };
        LlamaCppServerManager manager = new LlamaCppServerManager(
                fixture.config(),
                launcher,
                HttpClient.newHttpClient(),
                Duration.ofSeconds(2),
                Duration.ofMillis(5),
                tempDir.resolve("child-listener-logs"));

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(expectation));

        assertTrue(failure.getMessage().contains("endpoint owner differs"));
    }

    @Test
    void qualificationLaunchRejectsAProbeEndpointDifferentFromTheLaunchEndpoint()
            throws Exception {
        Fixture fixture = fixture("engine", "model", true);
        QueueLauncher launcher = launcher(observation(7101, 1_721_600_000_000L, fixture));
        LlamaCppServerManager manager = manager(
                fixture, launcher, LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A));

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(fixture.expectation()));

        assertTrue(failure.getMessage().contains("probe endpoint differs"));
        assertEquals(0, launcher.starts);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1",
            "http://0.0.0.0",
            "http://user@127.0.0.1",
            "http://127.0.0.1/path",
            "http://127.0.0.1?query=1",
            "http://127.0.0.1#fragment"
    })
    void qualificationEndpointRejectsNonCanonicalAuthorityAndUrlParts(String host)
            throws Exception {
        Fixture fixture = fixture("engine", "model");
        LlamaCppConfig config = config(
                fixture.engine(), fixture.model(), host,
                fixture.server().getAddress().getPort());
        Fixture endpointFixture = new Fixture(
                fixture.engine(), fixture.model(), fixture.server(), config,
                fixture.engineSha256(), fixture.modelSha256(), fixture.expectation());
        QueueLauncher launcher = launcher(observation(
                7101, 1_721_600_000_000L, endpointFixture));
        LlamaCppServerManager manager = new LlamaCppServerManager(
                config,
                launcher,
                HttpClient.newHttpClient(),
                Duration.ofSeconds(2),
                Duration.ofMillis(5),
                tempDir.resolve("endpoint-logs-" + Math.abs(host.hashCode())),
                (ignoredHost, ignoredPort, ignoredPid, ignoredStart, ignoredTimeout) ->
                        LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A));

        assertThrows(EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(fixture.expectation()));
        assertEquals(0, launcher.starts);
    }

    @Test
    void sameSizeModelMutationWithRestoredTimestampFailsBeforeLaunch() throws Exception {
        Fixture fixture = fixture("engine", "model-a");
        FileTime original = Files.getLastModifiedTime(fixture.model());
        Files.writeString(fixture.model(), "model-b", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(fixture.model(), original);
        QueueLauncher launcher = launcher(observation(7101, 1_721_600_000_000L, fixture));
        LlamaCppServerManager manager = manager(
                fixture, launcher, LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A));

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(fixture.expectation()));

        assertTrue(failure.getMessage().contains("model content identity"));
        assertEquals(0, launcher.starts);
    }

    @Test
    void sameSizeEngineMutationWithRestoredTimestampFailsBeforeLaunch() throws Exception {
        Fixture fixture = fixture("engine-a", "model");
        FileTime original = Files.getLastModifiedTime(fixture.engine());
        Files.writeString(fixture.engine(), "engine-b", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(fixture.engine(), original);
        QueueLauncher launcher = launcher(observation(7101, 1_721_600_000_000L, fixture));
        LlamaCppServerManager manager = manager(
                fixture,
                launcher,
                LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A));

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(fixture.expectation()));

        assertTrue(failure.getMessage().contains("engine content identity"));
        assertEquals(0, launcher.starts);
    }

    @Test
    void sameSizeMutationDuringEndpointPreflightFailsAtTheAdjacentLaunchHash()
            throws Exception {
        Fixture fixture = fixture("engine", "model-a");
        FileTime original = Files.getLastModifiedTime(fixture.model());
        QueueLauncher launcher = launcher(observation(
                7101, 1_721_600_000_000L, fixture));
        AtomicBoolean mutated = new AtomicBoolean();
        LlamaCppServerManager manager = new LlamaCppServerManager(
                fixture.config(),
                launcher,
                HttpClient.newHttpClient(),
                Duration.ofSeconds(2),
                Duration.ofMillis(5),
                tempDir.resolve("preflight-mutation-logs"),
                (host, port, expectedPid, expectedStart, timeout) -> {
                    if (expectedPid == 0L && mutated.compareAndSet(false, true)) {
                        try {
                            Files.writeString(
                                    fixture.model(), "model-b", StandardCharsets.UTF_8);
                            Files.setLastModifiedTime(fixture.model(), original);
                        } catch (IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    }
                    return LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A);
                },
                () -> OBSERVER_A);

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(fixture.expectation()));

        assertTrue(mutated.get());
        assertTrue(failure.getMessage().contains("model content identity"));
        assertEquals(0, launcher.starts);
    }

    @Test
    void managedQualificationReceiptBindsLaunchContentProcessAndReadiness() throws Exception {
        Fixture fixture = fixture("engine", "model");
        QueueLauncher launcher = launcher(observation(7101, 1_721_600_000_000L, fixture));
        LlamaCppServerManager manager = manager(
                fixture, launcher, LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A));

        manager.ensureStarted(fixture.expectation());
        assertThrows(EngineException.ConnectionFailed.class,
                () -> manager.bindQualificationReceipt(
                        fixture.expectation(), "qualification-model", true));
        LlamaCppPropsClient.Result props = manager.fetchQualificationProps(
                fixture.expectation());
        assertEquals(LlamaCppPropsClient.State.AVAILABLE, props.state());
        ProviderProcessLaunchReceipt receipt = manager.bindQualificationReceipt(
                fixture.expectation(), "qualification-model", true);

        assertEquals(7101L, receipt.processId());
        assertEquals(fixture.engineSha256(), receipt.engineExecutableSha256());
        assertEquals(fixture.modelSha256(), receipt.modelArtifactSha256());
        assertEquals(fixture.expectation().candidateDeclarationSha256(),
                receipt.candidateDeclarationSha256());
        assertEquals(props.props().responseSha256(), receipt.propsResponseSha256());
        assertEquals(OBSERVER_A.kind(), receipt.endpointObserverKind());
        assertEquals(OBSERVER_A.sha256(), receipt.endpointObserverIdentitySha256());
        assertEquals(OBSERVER_A.kind().ownershipBoundary(),
                receipt.endpointOwnershipBoundary());
        assertEquals(OBSERVER_A.kind().stableAssumption(),
                receipt.stableEndpointAssumption());
        assertEquals(receipt, manager.requireActiveQualificationReceipt());
        assertEquals(receipt, manager.bindQualificationReceipt(
                fixture.expectation(), "qualification-model", true));
        EngineException.ConnectionFailed conflict = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.bindQualificationReceipt(
                        fixture.expectation(), "different-profile", true));
        assertTrue(conflict.getMessage().contains("rebind is inconsistent"));
    }

    @Test
    void launchArgumentSetMustMatchTheConfiguredQualificationMode() throws Exception {
        Fixture fixture = fixture("engine", "model");
        LlamaCppQualificationLaunchExpectation wrong =
                new LlamaCppQualificationLaunchExpectation(
                        fixture.expectation().candidateDeclarationSha256(),
                        fixture.engineSha256(),
                        fixture.modelSha256(),
                        fixture.expectation().effectiveEnvironmentSha256(),
                        fixture.expectation().endpointObserverKind(),
                        fixture.expectation().endpointObserverIdentitySha256(),
                        ProviderProcessLaunchReceipt.LaunchArgumentSet.VERIFICATION);
        QueueLauncher launcher = launcher(observation(7101, 1_721_600_000_000L, fixture));
        LlamaCppServerManager manager = manager(
                fixture, launcher, LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A));

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(wrong));

        assertTrue(failure.getMessage().contains("argument set differs"));
        assertEquals(0, launcher.starts);
    }

    @Test
    void deadProcessCannotReuseCachedReceiptAndPidReuseGetsANewIdentity() throws Exception {
        Fixture fixture = fixture("engine", "model");
        FakeProcess first = observation(7101, 1_721_600_000_000L, fixture);
        FakeProcess second = observation(7101, 1_721_600_000_999L, fixture);
        QueueLauncher launcher = launcher(first, second);
        LlamaCppServerManager manager = manager(
                fixture, launcher, LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A));

        manager.ensureStarted(fixture.expectation());
        manager.fetchQualificationProps(fixture.expectation());
        ProviderProcessLaunchReceipt receiptA = manager.bindQualificationReceipt(
                fixture.expectation(), "qualification-model", true);
        first.alive = false;

        assertThrows(EngineException.ConnectionFailed.class,
                manager::requireActiveQualificationReceipt);

        manager.ensureStarted(fixture.expectation());
        manager.fetchQualificationProps(fixture.expectation());
        ProviderProcessLaunchReceipt receiptB = manager.bindQualificationReceipt(
                fixture.expectation(), "qualification-model", true);

        assertNotEquals(receiptA.receiptId(), receiptB.receiptId());
        assertEquals(receiptA.processId(), receiptB.processId());
        assertNotEquals(receiptA.processStartEpochMilli(), receiptB.processStartEpochMilli());
    }

    @Test
    void replacementProcessCannotBindThePriorProcessPropsBeforeRefetch()
            throws Exception {
        Fixture fixture = fixture("engine", "model");
        FakeProcess first = observation(7101, 1_721_600_000_000L, fixture);
        FakeProcess second = observation(7102, 1_721_600_000_999L, fixture);
        LlamaCppServerManager manager = manager(
                fixture, launcher(first, second),
                LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A));

        manager.ensureStarted(fixture.expectation());
        manager.fetchQualificationProps(fixture.expectation());
        ProviderProcessLaunchReceipt firstReceipt = manager.bindQualificationReceipt(
                fixture.expectation(), "qualification-model", true);
        first.alive = false;

        manager.ensureStarted(fixture.expectation());
        EngineException.ConnectionFailed staleProps = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.bindQualificationReceipt(
                        fixture.expectation(), "qualification-model", true));

        assertTrue(staleProps.getMessage().contains("/props observation is unavailable"));
        manager.fetchQualificationProps(fixture.expectation());
        ProviderProcessLaunchReceipt secondReceipt = manager.bindQualificationReceipt(
                fixture.expectation(), "qualification-model", true);
        assertNotEquals(firstReceipt.receiptId(), secondReceipt.receiptId());
        assertEquals(7102L, secondReceipt.processId());
    }

    @Test
    void replacementProcessCannotAdmitThePreviouslySelectedHttpEpoch() throws Exception {
        Fixture fixture = fixture("engine", "model");
        FakeProcess first = observation(7101, 1_721_600_000_000L, fixture);
        FakeProcess second = observation(7102, 1_721_600_000_999L, fixture);
        LlamaCppServerManager manager = manager(
                fixture, launcher(first, second),
                LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A));

        manager.ensureStarted(fixture.expectation());
        manager.fetchQualificationProps(fixture.expectation());
        manager.bindQualificationReceipt(
                fixture.expectation(), "qualification-model", true);
        LlamaCppServerManager.QualificationTransport selected =
                manager.requireActiveQualificationTransport();

        first.alive = false;
        manager.ensureStarted(fixture.expectation());
        manager.fetchQualificationProps(fixture.expectation());
        ProviderProcessLaunchReceipt replacement = manager.bindQualificationReceipt(
                fixture.expectation(), "qualification-model", true);
        LlamaCppServerManager.QualificationTransport replacementTransport =
                manager.requireActiveQualificationTransport();
        assertNotEquals(selected.receipt().receiptId(), replacement.receiptId());
        assertNotSame(selected.httpClient(), replacementTransport.httpClient());

        AtomicInteger delegateAdmissions = new AtomicInteger();
        AtomicBoolean sendEntered = new AtomicBoolean();
        ProviderTransportHandoff bound = LlamaCppEngine.bindSelectedQualificationTransport(
                delegateAdmissions::incrementAndGet,
                selected,
                manager::requireActiveQualificationReceipt);

        ProviderTransportHandoff.ProcessBindingInvalidated failure = assertThrows(
                ProviderTransportHandoff.ProcessBindingInvalidated.class,
                () -> bound.admitTransport(
                        ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                                "{}", List.of()))
                        .invokeSend(() -> {
                            sendEntered.set(true);
                            return null;
                        }));

        assertTrue(failure.getCause() instanceof EngineException.ConnectionFailed);
        assertTrue(failure.getCause().getMessage().contains("HTTP epoch"));
        assertEquals(0, delegateAdmissions.get());
        assertFalse(sendEntered.get());
    }

    @Test
    void missingProcessStartIdentityCannotBecomeAQualificationReceipt() throws Exception {
        Fixture fixture = fixture("engine", "model");
        FakeProcess process = new FakeProcess(new LlamaCppProcessLaunchObservation(
                7101L,
                0L,
                1_721_600_000_100L,
                "0123456789abcdef0123456789abcdef",
                ProviderProcessLaunchReceipt.digestOrderedValues(
                        List.of(fixture.engine().toString(), "-m", fixture.model().toString())),
                ProviderProcessLaunchReceipt.digestEffectiveEnvironment(Map.of())));
        QueueLauncher launcher = launcher(process);
        LlamaCppServerManager manager = manager(
                fixture, launcher, LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A));

        assertThrows(EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(fixture.expectation()));
        assertTrue(process.destroyed);
    }

    @Test
    void launcherCommandIdentityMustMatchTheExactCommandSuppliedByTheManager() throws Exception {
        Fixture fixture = fixture("engine", "model");
        FakeProcess process = observation(7101, 1_721_600_000_000L, fixture);
        LlamaCppProcessLaunchObservation observed = process.observation;
        process = new FakeProcess(new LlamaCppProcessLaunchObservation(
                observed.processId(),
                observed.processStartEpochMilli(),
                observed.launchObservedEpochMilli(),
                observed.launchNonce(),
                "0".repeat(64),
                observed.effectiveEnvironmentSha256()));
        LlamaCppServerManager manager = manager(
                fixture, launcher(process),
                LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A));

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(fixture.expectation()));

        assertTrue(failure.getMessage().contains("command identity"));
        assertTrue(process.destroyed);
    }

    @Test
    void launcherEnvironmentIdentityMustMatchTheEffectiveChildEnvironment() throws Exception {
        Fixture fixture = fixture("engine", "model");
        FakeProcess process = observation(7101, 1_721_600_000_000L, fixture);
        LlamaCppProcessLaunchObservation observed = process.observation;
        process = new FakeProcess(new LlamaCppProcessLaunchObservation(
                observed.processId(),
                observed.processStartEpochMilli(),
                observed.launchObservedEpochMilli(),
                observed.launchNonce(),
                observed.launchCommandSha256(),
                "0".repeat(64)));
        LlamaCppServerManager manager = manager(
                fixture, launcher(process),
                LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A));

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(fixture.expectation()));

        assertTrue(failure.getMessage().contains("environment identity"));
        assertTrue(process.destroyed);
    }

    @Test
    void frozenEnvironmentMismatchFailsBeforeObserverOrProviderLaunch() throws Exception {
        Fixture fixture = fixture("engine", "model");
        LlamaCppQualificationLaunchExpectation changed =
                new LlamaCppQualificationLaunchExpectation(
                        fixture.expectation().candidateDeclarationSha256(),
                        fixture.expectation().engineExecutableSha256(),
                        fixture.expectation().modelArtifactSha256(),
                        "0".repeat(64),
                        fixture.expectation().endpointObserverKind(),
                        fixture.expectation().endpointObserverIdentitySha256(),
                        fixture.expectation().launchArgumentSet());
        QueueLauncher launcher = launcher(observation(7101, 1_721_600_000_000L, fixture));
        AtomicInteger probes = new AtomicInteger();
        LlamaCppServerManager manager = new LlamaCppServerManager(
                fixture.config(),
                launcher,
                HttpClient.newHttpClient(),
                Duration.ofSeconds(2),
                Duration.ofMillis(5),
                tempDir.resolve("frozen-environment-mismatch-logs"),
                (host, port, expectedPid, expectedStart, timeout) -> {
                    probes.incrementAndGet();
                    return LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A);
                },
                () -> OBSERVER_A);

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(changed));

        assertTrue(failure.getMessage().contains("environment differs from frozen calibration"));
        assertEquals(0, probes.get());
        assertEquals(0, launcher.starts);
    }

    @Test
    void endpointObserverIdentityMustRemainEqualToThePreflightObserver() throws Exception {
        Fixture fixture = fixture("engine", "model");
        FakeProcess process = observation(7101, 1_721_600_000_000L, fixture);
        LlamaCppServerManager manager = new LlamaCppServerManager(
                fixture.config(),
                launcher(process),
                HttpClient.newHttpClient(),
                Duration.ofSeconds(2),
                Duration.ofMillis(5),
                tempDir.resolve("observer-change-logs"),
                (host, port, expectedPid, expectedStart, timeout) ->
                        expectedPid == 0L
                                ? LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER_A)
                                : LlamaCppEndpointOwnerProbe.Observation.exact(
                                        expectedPid, expectedStart, OBSERVER_B),
                () -> OBSERVER_A);

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(fixture.expectation()));

        assertTrue(failure.getMessage().contains("observer changed while observing"));
        assertTrue(process.destroyed);
    }

    @Test
    void frozenObserverMismatchFailsBeforeProbeOrProviderLaunch() throws Exception {
        Fixture fixture = fixture("engine", "model");
        Path frozenRoot = Files.createDirectories(
                tempDir.resolve("frozen-windows/System32"));
        Files.writeString(frozenRoot.resolve("netstat.exe"), "frozen-helper");
        Path substitutedRoot = Files.createDirectories(
                tempDir.resolve("substituted-windows/System32"));
        Files.writeString(substitutedRoot.resolve("netstat.exe"), "substituted-helper");
        LlamaCppEndpointOwnerProbe.ObserverIdentity frozen =
                LlamaCppEndpointOwnerProbe.authenticateWindowsObserver(
                        frozenRoot.getParent(), frozenRoot.getParent()).identity();
        LlamaCppEndpointOwnerProbe.ObserverIdentity substituted =
                LlamaCppEndpointOwnerProbe.authenticateWindowsObserver(
                        substitutedRoot.getParent(), substitutedRoot.getParent()).identity();
        LlamaCppQualificationLaunchExpectation expectation =
                new LlamaCppQualificationLaunchExpectation(
                        fixture.expectation().candidateDeclarationSha256(),
                        fixture.expectation().engineExecutableSha256(),
                        fixture.expectation().modelArtifactSha256(),
                        fixture.expectation().effectiveEnvironmentSha256(),
                        frozen.kind(),
                        frozen.sha256(),
                        fixture.expectation().launchArgumentSet());
        QueueLauncher launcher = launcher(observation(7101, 1_721_600_000_000L, fixture));
        AtomicInteger probes = new AtomicInteger();
        LlamaCppServerManager manager = new LlamaCppServerManager(
                fixture.config(),
                launcher,
                HttpClient.newHttpClient(),
                Duration.ofSeconds(2),
                Duration.ofMillis(5),
                tempDir.resolve("frozen-observer-mismatch-logs"),
                (host, port, expectedPid, expectedStart, timeout) -> {
                    probes.incrementAndGet();
                    return LlamaCppEndpointOwnerProbe.Observation.absent(substituted);
                },
                () -> substituted);

        EngineException.ConnectionFailed failure = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> manager.ensureStarted(expectation));

        assertTrue(failure.getMessage().contains("differs from frozen calibration"));
        assertEquals(0, probes.get());
        assertEquals(0, launcher.starts);
    }

    private LlamaCppServerManager manager(
            Fixture fixture,
            QueueLauncher launcher,
            LlamaCppEndpointOwnerProbe.Observation prelaunchObservation) {
        return new LlamaCppServerManager(
                fixture.config(),
                launcher,
                HttpClient.newHttpClient(),
                Duration.ofSeconds(2),
                Duration.ofMillis(5),
                tempDir.resolve("logs"),
                (host, port, expectedPid, expectedStart, timeout) ->
                        expectedPid == 0L
                                ? prelaunchObservation
                                : LlamaCppEndpointOwnerProbe.Observation.exact(
                                        expectedPid, expectedStart,
                                        prelaunchObservation.observerIdentity()),
                prelaunchObservation::observerIdentity);
    }

    private static LlamaCppQualificationLaunchExpectation withObserver(
            LlamaCppQualificationLaunchExpectation expectation,
            LlamaCppEndpointOwnerProbe.ObserverIdentity observer) {
        return new LlamaCppQualificationLaunchExpectation(
                expectation.candidateDeclarationSha256(),
                expectation.engineExecutableSha256(),
                expectation.modelArtifactSha256(),
                expectation.effectiveEnvironmentSha256(),
                observer.kind(),
                observer.sha256(),
                expectation.launchArgumentSet());
    }

    private Fixture fixture(String engineBytes, String modelBytes) throws Exception {
        return fixture(engineBytes, modelBytes, false);
    }

    private Fixture fixture(
            String engineBytes,
            String modelBytes,
            boolean divergentEmbeddedPort) throws Exception {
        Path engine = tempDir.resolve("llama-server.exe");
        Path model = tempDir.resolve("qualification.gguf");
        Files.writeString(engine, engineBytes, StandardCharsets.UTF_8);
        Files.writeString(model, modelBytes, StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        byte[] props = new ObjectMapper().writeValueAsBytes(Map.of(
                "default_generation_settings", Map.of("n_ctx", 8192),
                "model_path", model.toAbsolutePath().normalize().toString(),
                "chat_template", "embedded-jinja",
                "chat_template_caps", Map.of(
                        "supports_tools", true,
                        "supports_tool_calls", true,
                        "supports_parallel_tool_calls", false),
                "modalities", Map.of("vision", false),
                "build_info", "b9918"));
        server.createContext("/props", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, props.length);
            exchange.getResponseBody().write(props);
            exchange.close();
        });
        server.start();
        servers.add(server);
        LlamaCppConfig config = config(
                engine,
                model,
                divergentEmbeddedPort
                        ? "http://127.0.0.1:" + (server.getAddress().getPort() + 1)
                        : "http://127.0.0.1",
                server.getAddress().getPort());
        String engineHash = sha256(engine);
        String modelHash = sha256(model);
        return new Fixture(
                engine,
                model,
                server,
                config,
                engineHash,
                modelHash,
                new LlamaCppQualificationLaunchExpectation(
                        "a".repeat(64), engineHash, modelHash,
                        ProviderProcessLaunchReceipt.digestEffectiveEnvironment(
                                new ProcessBuilder().environment()),
                        OBSERVER_A.kind(),
                        OBSERVER_A.sha256(),
                        ProviderProcessLaunchReceipt.LaunchArgumentSet.STANDARD));
    }

    private static LlamaCppConfig config(
            Path engine,
            Path model,
            String host,
            int port) {
        Config cfg = new Config();
        Map<String, Object> llama = new LinkedHashMap<>();
        llama.put("mode", "managed");
        llama.put("server_path", engine.toString());
        llama.put("model_path", model.toString());
        llama.put("model", "qualification-model");
        llama.put("host", host);
        llama.put("port", port);
        llama.put("context", 8192);
        Map<String, Object> engines = new LinkedHashMap<>();
        engines.put("llama_cpp", llama);
        cfg.data.put("engines", engines);
        return LlamaCppConfig.from(cfg);
    }

    private static void assertPortCanBeRebound(int port) throws IOException {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress("127.0.0.1", port));
        }
    }

    private static FakeProcess observation(
            long pid,
            long start,
            Fixture fixture) {
        List<String> command = new ArrayList<>();
        command.add(fixture.engine().toString());
        command.addAll(LlamaCppServerManager.buildArguments(fixture.config()));
        ProcessBuilder builder = new ProcessBuilder(command);
        return new FakeProcess(new LlamaCppProcessLaunchObservation(
                pid,
                start,
                start + 100,
                String.format("%032x", start),
                ProviderProcessLaunchReceipt.digestOrderedValues(command),
                ProviderProcessLaunchReceipt.digestEffectiveEnvironment(builder.environment())));
    }

    private static QueueLauncher launcher(FakeProcess... processes) {
        return new QueueLauncher(List.of(processes));
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }

    private record Fixture(
            Path engine,
            Path model,
            HttpServer server,
            LlamaCppConfig config,
            String engineSha256,
            String modelSha256,
            LlamaCppQualificationLaunchExpectation expectation) {}

    private static final class QueueLauncher implements LlamaCppProcessLauncher {
        private final ArrayDeque<FakeProcess> processes;
        private int starts;

        private QueueLauncher(List<FakeProcess> processes) {
            this.processes = new ArrayDeque<>(processes);
        }

        @Override
        public LlamaCppProcess start(List<String> command, Path logPath) throws IOException {
            return start(command, logPath, Map.of());
        }

        @Override
        public LlamaCppProcess start(
                List<String> command,
                Path logPath,
                Map<String, String> environment) throws IOException {
            starts++;
            FakeProcess process = processes.pollFirst();
            if (process == null) throw new IOException("no fake process");
            return process;
        }

        @Override
        public LlamaCppProcess startWithExactEnvironment(
                List<String> command,
                Path logPath,
                Map<String, String> environment,
                Path workingDirectory) throws IOException {
            return start(command, logPath, environment);
        }
    }

    private static final class FakeProcess implements LlamaCppProcess {
        private final LlamaCppProcessLaunchObservation observation;
        private boolean alive = true;
        private boolean destroyed;

        private FakeProcess(LlamaCppProcessLaunchObservation observation) {
            this.observation = observation;
        }

        @Override public boolean isAlive() { return alive; }
        @Override public LlamaCppProcessLaunchObservation launchObservation() { return observation; }

        @Override
        public void destroy() {
            destroyed = true;
            alive = false;
        }
    }
}
