package dev.talos.engine.llamacpp;

import com.sun.net.httpserver.HttpServer;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LlamaCppEndpointOwnerProbeTest {

    @TempDir Path temp;

    @Test
    void realNumericLoopbackListenerBindsCurrentPidAndStartIdentity() throws Exception {
        ProviderProcessLaunchReceipt.EnvironmentKeySemantics semantics =
                ProviderProcessLaunchReceipt.detectEnvironmentKeySemantics();
        assumeTrue(semantics
                        == ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_INSENSITIVE
                || (Files.isRegularFile(Path.of("/proc/net/tcp"))
                        && Files.isRegularFile(Path.of("/proc/net/tcp6"))));
        long pid = ProcessHandle.current().pid();
        long started = ProcessHandle.current().info().startInstant()
                .map(Instant::toEpochMilli)
                .orElseThrow();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            LlamaCppEndpointOwnerProbe.Observation exact = awaitExact(port, pid, started);
            assertTrue(exact.matches(pid, started));
            assertEquals(LlamaCppEndpointOwnerProbe.State.OTHER_OR_AMBIGUOUS,
                    LlamaCppEndpointOwnerProbe.observe(
                            "127.0.0.1", port, pid, started + 1,
                            Duration.ofSeconds(5)).state());
            assertEquals(LlamaCppEndpointOwnerProbe.State.OTHER_OR_AMBIGUOUS,
                    LlamaCppEndpointOwnerProbe.observe(
                            "127.0.0.1", port, pid + 1, started,
                            Duration.ofSeconds(5)).state());
        } finally {
            server.stop(0);
        }
        LlamaCppEndpointOwnerProbe.Observation absent = LlamaCppEndpointOwnerProbe.observe(
                "127.0.0.1", port, pid, started, Duration.ofSeconds(5));
        assertEquals(LlamaCppEndpointOwnerProbe.State.ABSENT, absent.state());
    }

    @Test
    void wildcardListenerOccupiesPreflightButNeverBindsExactLoopbackAuthority()
            throws Exception {
        ProviderProcessLaunchReceipt.EnvironmentKeySemantics semantics =
                ProviderProcessLaunchReceipt.detectEnvironmentKeySemantics();
        assumeTrue(semantics
                        == ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_INSENSITIVE
                || (Files.isRegularFile(Path.of("/proc/net/tcp"))
                        && Files.isRegularFile(Path.of("/proc/net/tcp6"))));
        long pid = ProcessHandle.current().pid();
        long started = ProcessHandle.current().info().startInstant()
                .map(Instant::toEpochMilli)
                .orElseThrow();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.start();
        try {
            int port = server.getAddress().getPort();
            assertEquals(LlamaCppEndpointOwnerProbe.State.OTHER_OR_AMBIGUOUS,
                    awaitState(port, 0L, 0L));
            assertEquals(LlamaCppEndpointOwnerProbe.State.OTHER_OR_AMBIGUOUS,
                    awaitState(port, pid, started));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void windowsParserCountsExactAndWildcardListenersAndRejectsMalformedPid()
            throws Exception {
        byte[] rows = ("Active Connections\r\n"
                + "  Proto  Local Address          Foreign Address        State           PID\r\n"
                + "  TCP    127.0.0.1:8080         0.0.0.0:0              LISTENING       41\r\n"
                + "  TCP    0.0.0.0:8080           0.0.0.0:0              LISTENING       42\r\n"
                + "  TCP    [::]:8080              [::]:0                 LISTENING       43\r\n"
                + "  TCP    127.0.0.1:8080         127.0.0.1:9000         ESTABLISHED     44\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertEquals(Set.of(41L, 42L, 43L),
                LlamaCppEndpointOwnerProbe.windowsListenerOwners(rows, 8080));
        LlamaCppEndpointOwnerProbe.WindowsListeners listeners =
                LlamaCppEndpointOwnerProbe.windowsListeners(rows, 8080);
        assertEquals(Set.of(41L), listeners.exactOwners());
        assertEquals(Set.of(42L, 43L), listeners.wildcardOwners());
        assertFalse(LlamaCppEndpointOwnerProbe.exactWindowsEndpointOwner(listeners, 41L));

        byte[] wildcardOnly =
                "TCP 0.0.0.0:8080 0.0.0.0:0 LISTENING 41\r\n"
                        .getBytes(StandardCharsets.US_ASCII);
        assertFalse(LlamaCppEndpointOwnerProbe.exactWindowsEndpointOwner(
                LlamaCppEndpointOwnerProbe.windowsListeners(wildcardOnly, 8080), 41L));

        byte[] exactOnly =
                "TCP 127.0.0.1:8080 0.0.0.0:0 LISTENING 41\r\n"
                        .getBytes(StandardCharsets.US_ASCII);
        assertTrue(LlamaCppEndpointOwnerProbe.exactWindowsEndpointOwner(
                LlamaCppEndpointOwnerProbe.windowsListeners(exactOnly, 8080), 41L));

        byte[] malformed = "TCP 127.0.0.1:8080 0.0.0.0:0 LISTENING pid\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(java.io.IOException.class,
                () -> LlamaCppEndpointOwnerProbe.windowsListenerOwners(malformed, 8080));
    }

    @Test
    void windowsObserverIdentityBindsHelperBytesLocationFixedArgvExactEnvAndCwd()
            throws Exception {
        Path rootA = Files.createDirectory(temp.resolve("windows-a"));
        Path system32A = Files.createDirectory(rootA.resolve("System32"));
        Path helperA = system32A.resolve("netstat.exe");
        Files.writeString(helperA, "helper-a", StandardCharsets.US_ASCII);

        LlamaCppEndpointOwnerProbe.WindowsObserver first =
                LlamaCppEndpointOwnerProbe.authenticateWindowsObserver(rootA, rootA);
        ProcessBuilder query = LlamaCppEndpointOwnerProbe.windowsQueryBuilder(first);

        assertEquals(List.of(helperA.toAbsolutePath().normalize().toString(), "-a", "-n", "-o"),
                query.command());
        assertEquals(
                Map.of("SYSTEMROOT", rootA.toAbsolutePath().normalize().toString()),
                query.environment());
        assertEquals(system32A.toAbsolutePath().normalize().toFile(), query.directory());
        assertEquals(
                ProviderProcessLaunchReceipt.EndpointObserverKind
                        .WINDOWS_NETSTAT_NOFOLLOW_SHA256,
                first.identity().kind());

        Files.writeString(helperA, "helper-b", StandardCharsets.US_ASCII);
        LlamaCppEndpointOwnerProbe.WindowsObserver changedBytes =
                LlamaCppEndpointOwnerProbe.authenticateWindowsObserver(rootA, rootA);
        assertFalse(first.identity().equals(changedBytes.identity()));

        Path rootB = Files.createDirectory(temp.resolve("windows-b"));
        Path system32B = Files.createDirectory(rootB.resolve("System32"));
        Files.writeString(system32B.resolve("netstat.exe"), "helper-a",
                StandardCharsets.US_ASCII);
        LlamaCppEndpointOwnerProbe.WindowsObserver changedLocation =
                LlamaCppEndpointOwnerProbe.authenticateWindowsObserver(rootB, rootB);
        assertFalse(first.identity().equals(changedLocation.identity()));
    }

    @Test
    void linuxObserverIdentityIsVersionedAndStable() throws Exception {
        assumeTrue(ProviderProcessLaunchReceipt.detectEnvironmentKeySemantics()
                == ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_SENSITIVE);

        LlamaCppEndpointOwnerProbe.ObserverIdentity first =
                LlamaCppEndpointOwnerProbe.currentObserverIdentity();
        LlamaCppEndpointOwnerProbe.ObserverIdentity second =
                LlamaCppEndpointOwnerProbe.currentObserverIdentity();

        assertEquals(
                ProviderProcessLaunchReceipt.EndpointObserverKind
                        .LINUX_PROCFS_MANAGED_PROCESS_TREE_V1,
                first.kind());
        assertTrue(first.sha256().matches("[0-9a-f]{64}"));
        assertEquals(first, second);
    }

    @Test
    void linuxParserIncludesIpv4AndDualStackWildcardListenersWithinBounds()
            throws Exception {
        Path ipv4 = temp.resolve("tcp");
        Path ipv6 = temp.resolve("tcp6");
        Files.writeString(ipv4,
                "  sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
                        + "   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 11111\n",
                StandardCharsets.US_ASCII);
        Files.writeString(ipv6,
                "  sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
                        + "   0: 00000000000000000000000000000000:1F90 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 22222\n",
                StandardCharsets.US_ASCII);
        Set<String> inodes = new HashSet<>();
        LlamaCppEndpointOwnerProbe.collectLinuxListeners(
                ipv4, "127.0.0.1", 8080, false, inodes, Long.MAX_VALUE);
        LlamaCppEndpointOwnerProbe.collectLinuxListeners(
                ipv6, "127.0.0.1", 8080, true, inodes, Long.MAX_VALUE);
        assertEquals(Set.of("11111", "22222"), inodes);

        Set<String> exact = new HashSet<>();
        Set<String> wildcard = new HashSet<>();
        LlamaCppEndpointOwnerProbe.collectLinuxListeners(
                ipv4, "127.0.0.1", 8080, false,
                exact, wildcard, Long.MAX_VALUE);
        LlamaCppEndpointOwnerProbe.collectLinuxListeners(
                ipv6, "127.0.0.1", 8080, true,
                exact, wildcard, Long.MAX_VALUE);
        assertEquals(Set.of("11111"), exact);
        assertEquals(Set.of("22222"), wildcard);
        assertFalse(LlamaCppEndpointOwnerProbe.exactHostListenerScope(exact, wildcard));
        assertFalse(LlamaCppEndpointOwnerProbe.exactHostListenerScope(
                Set.of(), Set.of("22222")));
        assertTrue(LlamaCppEndpointOwnerProbe.exactHostListenerScope(
                Set.of("11111"), Set.of()));

        assertTrue(LlamaCppEndpointOwnerProbe.linuxAddressMatches(
                "127.0.0.1", "0100007F", false));
        assertTrue(LlamaCppEndpointOwnerProbe.linuxAddressMatches(
                "127.0.0.1", "00000000000000000000000000000000", true));
        assertTrue(LlamaCppEndpointOwnerProbe.linuxAddressMatches(
                "127.0.0.1", "0000000000000000FFFF00000100007F", true));
        assertEquals(LlamaCppEndpointOwnerProbe.LinuxAddressScope.EXACT,
                LlamaCppEndpointOwnerProbe.linuxAddressScope(
                        "127.0.0.1", "0100007F", false));
        assertEquals(LlamaCppEndpointOwnerProbe.LinuxAddressScope.WILDCARD,
                LlamaCppEndpointOwnerProbe.linuxAddressScope(
                        "127.0.0.1", "00000000", false));
        assertThrows(java.io.IOException.class,
                () -> LlamaCppEndpointOwnerProbe.collectLinuxListeners(
                        temp.resolve("missing"), "127.0.0.1", 8080, false,
                        new HashSet<>(), Long.MAX_VALUE));
    }

    @Test
    void linuxOwnerClassificationRequiresCompleteExclusiveExpectedOwnership() {
        long expected = 101L;
        Set<String> listeners = Set.of("11111", "22222");

        assertTrue(LlamaCppEndpointOwnerProbe.exclusiveLinuxListenerOwner(
                Map.of(expected, listeners), expected, listeners));
        assertFalse(LlamaCppEndpointOwnerProbe.exclusiveLinuxListenerOwner(
                Map.of(expected, Set.of("11111")), expected, listeners));
        assertFalse(LlamaCppEndpointOwnerProbe.exclusiveLinuxListenerOwner(
                Map.of(expected, listeners, 102L, Set.of("11111")), expected, listeners));
    }

    @Test
    void linuxOwnerScanFailsClosedWhenManagedTreeDescriptorsAreUnavailable()
            throws Exception {
        Path proc = Files.createDirectory(temp.resolve("proc-incomplete"));
        Files.createDirectories(proc.resolve("101").resolve("fd"));
        Files.createDirectory(proc.resolve("102"));

        assertThrows(java.io.IOException.class,
                () -> LlamaCppEndpointOwnerProbe.linuxListenerOwnersForProcesses(
                        proc, Set.of(101L, 102L), Set.of("11111"), Long.MAX_VALUE));

        Path external = Files.createDirectory(temp.resolve("proc-external"));
        Files.createDirectories(external.resolve("201").resolve("fd"));
        Files.createDirectory(external.resolve("202"));
        assertEquals(Map.of(), LlamaCppEndpointOwnerProbe.linuxListenerOwnersForProcesses(
                external, Set.of(201L), Set.of("11111"), Long.MAX_VALUE));
    }

    @Test
    void linuxSyntheticProcReverseScanFindsEverySameUidSharedOwner()
            throws Exception {
        assumeTrue(ProviderProcessLaunchReceipt.detectEnvironmentKeySemantics()
                == ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_SENSITIVE);
        Path proc = Files.createDirectory(temp.resolve("proc-shared"));
        for (long processId : List.of(301L, 302L)) {
            Path descriptors = Files.createDirectories(
                    proc.resolve(Long.toString(processId)).resolve("fd"));
            Files.createSymbolicLink(
                    descriptors.resolve("3"), Path.of("socket:[11111]"));
        }

        assertEquals(
                Map.of(301L, Set.of("11111"), 302L, Set.of("11111")),
                LlamaCppEndpointOwnerProbe.linuxListenerOwnersForProcesses(
                        proc, Set.of(301L, 302L), Set.of("11111"), Long.MAX_VALUE));
    }

    @Test
    void linuxForkedProcessSharingTheListenerCannotBindToTheParentReceipt()
            throws Exception {
        ProviderProcessLaunchReceipt.EnvironmentKeySemantics semantics =
                ProviderProcessLaunchReceipt.detectEnvironmentKeySemantics();
        assumeTrue(semantics
                == ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_SENSITIVE);
        assumeTrue(Files.isRegularFile(Path.of("/proc/net/tcp"))
                && Files.isRegularFile(Path.of("/proc/net/tcp6")));
        assumeTrue(pythonForkAvailable());

        String script = """
                import os, socket, time
                listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                listener.bind(("127.0.0.1", 0))
                listener.listen(1)
                child = os.fork()
                if child == 0:
                    connection, address = listener.accept()
                    connection.sendall(b"served-by-child-b")
                    connection.close()
                    time.sleep(30)
                    os._exit(0)
                print(f"{listener.getsockname()[1]} {child}", flush=True)
                time.sleep(30)
                """;
        Process parent = new ProcessBuilder("python3", "-c", script)
                .redirectErrorStream(true)
                .start();
        long childPid = 0L;
        try (java.io.BufferedReader output = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        parent.getInputStream(), StandardCharsets.UTF_8))) {
            String identity = awaitFixtureIdentity(parent, output);
            String[] fields = identity.split(" ");
            assertEquals(2, fields.length, identity);
            int port = Integer.parseInt(fields[0]);
            childPid = Long.parseLong(fields[1]);
            long started = parent.toHandle().info().startInstant()
                    .map(Instant::toEpochMilli)
                    .orElseThrow();

            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
                client.setSoTimeout(2_000);
                assertEquals("served-by-child-b", new String(
                        client.getInputStream().readNBytes(64), StandardCharsets.US_ASCII));
            }

            LlamaCppEndpointOwnerProbe.Observation observation =
                    LlamaCppEndpointOwnerProbe.observe(
                            "127.0.0.1", port, parent.pid(), started,
                            Duration.ofSeconds(5));
            assertEquals(LlamaCppEndpointOwnerProbe.State.OTHER_OR_AMBIGUOUS,
                    observation.state(), observation.toString());
        } finally {
            terminate(childPid);
            terminate(parent.toHandle());
        }
    }

    private static boolean pythonForkAvailable() {
        Process check = null;
        try {
            check = new ProcessBuilder(
                    "python3", "-c", "import os; assert hasattr(os, 'fork')")
                    .redirectErrorStream(true)
                    .start();
            return check.waitFor(5L, TimeUnit.SECONDS) && check.exitValue() == 0;
        } catch (Exception unavailable) {
            return false;
        } finally {
            if (check != null && check.isAlive()) check.destroyForcibly();
        }
    }

    private static String awaitFixtureIdentity(
            Process fixture,
            java.io.BufferedReader output) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!output.ready() && fixture.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(output.ready(), "shared-listener fixture did not report its identity");
        String identity = output.readLine();
        assertTrue(identity != null && identity.matches("[1-9][0-9]{0,4} [1-9][0-9]*"),
                String.valueOf(identity));
        return identity;
    }

    private static void terminate(long processId) {
        if (processId < 1) return;
        ProcessHandle.of(processId).ifPresent(LlamaCppEndpointOwnerProbeTest::terminate);
    }

    private static void terminate(ProcessHandle process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            process.onExit().get(1L, TimeUnit.SECONDS);
        } catch (Exception stillAlive) {
            process.destroyForcibly();
            try {
                process.onExit().get(2L, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The bounded fixture cleanup has exhausted both stop paths.
            }
        }
    }

    private static LlamaCppEndpointOwnerProbe.Observation awaitExact(
            int port,
            long pid,
            long started) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        LlamaCppEndpointOwnerProbe.Observation last =
                LlamaCppEndpointOwnerProbe.Observation.absent(
                        LlamaCppEndpointOwnerProbe.currentObserverIdentity());
        while (System.nanoTime() < deadline) {
            last = LlamaCppEndpointOwnerProbe.observe(
                    "127.0.0.1", port, pid, started, Duration.ofSeconds(5));
            if (last.matches(pid, started)) return last;
            Thread.sleep(50L);
        }
        return last;
    }

    private static LlamaCppEndpointOwnerProbe.State awaitState(
            int port, long pid, long started) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        LlamaCppEndpointOwnerProbe.State last = LlamaCppEndpointOwnerProbe.State.ABSENT;
        while (System.nanoTime() < deadline) {
            last = LlamaCppEndpointOwnerProbe.observe(
                    "127.0.0.1", port, pid, started, Duration.ofSeconds(5)).state();
            if (last == LlamaCppEndpointOwnerProbe.State.OTHER_OR_AMBIGUOUS) return last;
            Thread.sleep(50L);
        }
        return last;
    }
}
