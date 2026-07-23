package dev.talos.engine.llamacpp;

import dev.talos.core.security.NoFollowPathPolicy;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Fail-closed operating-system observation of one numeric-loopback listener.
 *
 * <p>This is deliberately limited to the supported qualification platforms.
 * It samples listener ownership; it does not claim immunity from a hostile
 * kernel or a replacement racing between the final sample and the send call.
 */
final class LlamaCppEndpointOwnerProbe {
    enum State {
        ABSENT,
        EXACT,
        OTHER_OR_AMBIGUOUS,
        INDETERMINATE
    }

    record ObserverIdentity(
            ProviderProcessLaunchReceipt.EndpointObserverKind kind,
            String sha256) {
        ObserverIdentity {
            kind = Objects.requireNonNull(kind, "endpoint observer kind");
            sha256 = Objects.toString(sha256, "");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "endpoint observer identity SHA-256 is invalid");
            }
        }
    }

    record Observation(
            State state,
            long processId,
            long processStartEpochMilli,
            ObserverIdentity observerIdentity) {
        Observation {
            state = Objects.requireNonNull(state, "endpoint owner state");
            observerIdentity = Objects.requireNonNull(
                    observerIdentity, "endpoint observer identity");
            if (state == State.EXACT) {
                if (processId < 1 || processStartEpochMilli < 1) {
                    throw new IllegalArgumentException(
                            "exact endpoint ownership requires a PID and start identity");
                }
            } else if (processId != 0 || processStartEpochMilli != 0) {
                throw new IllegalArgumentException(
                        "non-exact endpoint ownership cannot carry an identity");
            }
        }

        static Observation absent(ObserverIdentity observerIdentity) {
            return new Observation(State.ABSENT, 0L, 0L, observerIdentity);
        }

        static Observation exact(
                long processId,
                long processStartEpochMilli,
                ObserverIdentity observerIdentity) {
            return new Observation(
                    State.EXACT, processId, processStartEpochMilli, observerIdentity);
        }

        static Observation other(ObserverIdentity observerIdentity) {
            return new Observation(
                    State.OTHER_OR_AMBIGUOUS, 0L, 0L, observerIdentity);
        }

        static Observation indeterminate(ObserverIdentity observerIdentity) {
            return new Observation(State.INDETERMINATE, 0L, 0L, observerIdentity);
        }

        boolean matches(long expectedProcessId, long expectedStartEpochMilli) {
            return state == State.EXACT
                    && processId == expectedProcessId
                    && processStartEpochMilli == expectedStartEpochMilli;
        }
    }

    @FunctionalInterface
    interface Probe {
        Observation observe(
                String numericLoopbackHost,
                int port,
                long expectedProcessId,
                long expectedStartEpochMilli,
                Duration timeout);
    }

    @FunctionalInterface
    interface ObserverIdentityReader {
        ObserverIdentity current() throws IOException;
    }

    private static final int MAX_QUERY_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_OBSERVER_HELPER_BYTES = 64 * 1024 * 1024;
    private static final int MAX_PROC_ROWS = 65_536;
    private static final int MAX_PROC_LINE_CHARS = 2_048;
    private static final int MAX_PROC_PROCESSES = 65_536;
    private static final int MAX_PROCESS_DESCRIPTORS = 65_536;
    private static final List<String> WINDOWS_QUERY_ARGUMENTS = List.of("-a", "-n", "-o");
    private static final ObserverIdentity LINUX_OBSERVER_IDENTITY = new ObserverIdentity(
            ProviderProcessLaunchReceipt.EndpointObserverKind
                    .LINUX_PROCFS_MANAGED_PROCESS_TREE_V1,
            ProviderProcessLaunchReceipt.digestOrderedValues(List.of(
                    "talos.llamaCppEndpointObserver",
                    "1",
                    "LINUX_PROCFS_MANAGED_PROCESS_TREE_V1",
                    "/proc/net/tcp",
                    "/proc/net/tcp6",
                    "/proc/{managed-process-or-live-descendant}/fd",
                    "preflight-exact-or-wildcard-readiness-exact-only",
                    "exclusive-managed-process-owner",
                    "no-out-of-managed-tree-file-descriptor-delegation")));

    record WindowsObserver(
            Path system32Directory,
            Path helper,
            ObserverIdentity identity) {
        WindowsObserver {
            system32Directory = Objects.requireNonNull(
                    system32Directory, "authenticated System32 directory");
            helper = Objects.requireNonNull(helper, "authenticated netstat helper");
            identity = Objects.requireNonNull(identity, "Windows observer identity");
            if (identity.kind()
                    != ProviderProcessLaunchReceipt.EndpointObserverKind
                            .WINDOWS_NETSTAT_NOFOLLOW_SHA256) {
                throw new IllegalArgumentException("Windows observer kind is invalid");
            }
        }
    }

    record WindowsListeners(Set<Long> exactOwners, Set<Long> wildcardOwners) {
        WindowsListeners {
            exactOwners = Set.copyOf(Objects.requireNonNull(exactOwners, "exact owners"));
            wildcardOwners = Set.copyOf(Objects.requireNonNull(
                    wildcardOwners, "wildcard owners"));
        }

        boolean isEmpty() {
            return exactOwners.isEmpty() && wildcardOwners.isEmpty();
        }
    }

    private LlamaCppEndpointOwnerProbe() {}

    static Observation observe(
            String host,
            int port,
            long expectedProcessId,
            long expectedStartEpochMilli,
            Duration timeout) {
        final ProviderProcessLaunchReceipt.EnvironmentKeySemantics semantics;
        try {
            semantics = ProviderProcessLaunchReceipt.detectEnvironmentKeySemantics();
        } catch (RuntimeException indeterminate) {
            return Observation.indeterminate(unavailableObserverIdentity(
                    ProviderProcessLaunchReceipt.EndpointObserverKind
                            .LINUX_PROCFS_MANAGED_PROCESS_TREE_V1));
        }
        if (semantics
                == ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_INSENSITIVE) {
            final WindowsObserver observer;
            try {
                observer = authenticateWindowsObserver();
            } catch (Exception unavailable) {
                return Observation.indeterminate(unavailableObserverIdentity(
                        ProviderProcessLaunchReceipt.EndpointObserverKind
                                .WINDOWS_NETSTAT_NOFOLLOW_SHA256));
            }
            if (!numericLoopback(host) || port < 1 || port > 65_535) {
                return Observation.indeterminate(observer.identity());
            }
            return observeWindows(host, port, expectedProcessId,
                    expectedStartEpochMilli, timeout, observer);
        }
        if (semantics
                == ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_SENSITIVE) {
            if (!numericLoopback(host) || port < 1 || port > 65_535) {
                return Observation.indeterminate(LINUX_OBSERVER_IDENTITY);
            }
            return observeLinux(
                    host, port, expectedProcessId, expectedStartEpochMilli,
                    timeout, LINUX_OBSERVER_IDENTITY);
        }
        return Observation.indeterminate(unavailableObserverIdentity(
                ProviderProcessLaunchReceipt.EndpointObserverKind
                        .LINUX_PROCFS_MANAGED_PROCESS_TREE_V1));
    }

    private static Observation observeWindows(
            String host,
            int port,
            long expectedProcessId,
            long expectedStartEpochMilli,
            Duration timeout,
            WindowsObserver observer) {
        Process query = null;
        try {
            ProcessBuilder builder = windowsQueryBuilder(observer);
            query = builder.start();
            long timeoutMillis = boundedTimeout(timeout).toMillis();
            Process exactQuery = query;
            java.util.concurrent.CompletableFuture<byte[]> outputRead =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return exactQuery.getInputStream().readNBytes(
                                    MAX_QUERY_OUTPUT_BYTES + 1);
                        } catch (IOException unreadable) {
                            throw new java.util.concurrent.CompletionException(unreadable);
                        }
                    });
            if (!query.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                query.destroyForcibly();
                query.waitFor(2L, TimeUnit.SECONDS);
                outputRead.cancel(true);
                return Observation.indeterminate(observer.identity());
            }
            byte[] output = outputRead.get(2L, TimeUnit.SECONDS);
            if (query.exitValue() != 0 || output.length > MAX_QUERY_OUTPUT_BYTES) {
                return Observation.indeterminate(observer.identity());
            }
            WindowsObserver after = authenticateWindowsObserver();
            if (!observer.equals(after)) {
                return Observation.indeterminate(observer.identity());
            }
            WindowsListeners listeners = windowsListeners(output, port);
            if (listeners.isEmpty()) return Observation.absent(observer.identity());
            if (!exactWindowsEndpointOwner(listeners, expectedProcessId)) {
                return Observation.other(observer.identity());
            }
            long owner = listeners.exactOwners().iterator().next();
            if (expectedProcessId < 1 || owner != expectedProcessId) {
                return Observation.other(observer.identity());
            }
            return liveProcessIdentity(owner, expectedStartEpochMilli)
                    ? Observation.exact(
                            owner, expectedStartEpochMilli, observer.identity())
                    : Observation.other(observer.identity());
        } catch (InterruptedException interrupted) {
            if (query != null && query.isAlive()) query.destroyForcibly();
            Thread.currentThread().interrupt();
            return Observation.indeterminate(observer.identity());
        } catch (Exception unavailable) {
            if (query != null && query.isAlive()) query.destroyForcibly();
            return Observation.indeterminate(observer.identity());
        }
    }

    static Set<Long> windowsListenerOwners(byte[] output, int port)
            throws IOException {
        WindowsListeners listeners = windowsListeners(output, port);
        Set<Long> owners = new HashSet<>(listeners.exactOwners());
        owners.addAll(listeners.wildcardOwners());
        return Set.copyOf(owners);
    }

    static boolean exactWindowsEndpointOwner(
            WindowsListeners listeners, long expectedProcessId) {
        return listeners != null
                && expectedProcessId > 0
                && listeners.wildcardOwners().isEmpty()
                && listeners.exactOwners().equals(Set.of(expectedProcessId));
    }

    static WindowsListeners windowsListeners(byte[] output, int port)
            throws IOException {
        String exact = new String(output, StandardCharsets.US_ASCII);
        Set<Long> exactOwners = new HashSet<>();
        Set<Long> wildcardOwners = new HashSet<>();
        String ipv4 = "127.0.0.1:" + port;
        String ipv4Wildcard = "0.0.0.0:" + port;
        String ipv6Wildcard = "[::]:" + port;
        for (String raw : exact.split("\\R")) {
            String line = raw.strip();
            if (line.isBlank()) continue;
            String[] fields = line.split("\\s+");
            if (fields.length < 5
                    || !"TCP".equalsIgnoreCase(fields[0])
                    || !"LISTENING".equalsIgnoreCase(fields[3])) {
                continue;
            }
            String local = fields[1];
            String remote = fields[2];
            boolean exactLoopback = local.equals(ipv4);
            boolean wildcard = local.equals(ipv4Wildcard) || local.equals(ipv6Wildcard);
            if (!exactLoopback && !wildcard) {
                continue;
            }
            if (!(remote.equals("0.0.0.0:0") || remote.equals("[::]:0"))) continue;
            String pid = fields[fields.length - 1];
            if (!pid.matches("[1-9][0-9]{0,18}")) {
                throw new IOException("netstat listener PID is malformed");
            }
            (exactLoopback ? exactOwners : wildcardOwners).add(Long.parseLong(pid));
        }
        return new WindowsListeners(exactOwners, wildcardOwners);
    }

    static ObserverIdentity currentObserverIdentity() throws IOException {
        ProviderProcessLaunchReceipt.EnvironmentKeySemantics semantics =
                ProviderProcessLaunchReceipt.detectEnvironmentKeySemantics();
        return switch (semantics) {
            case CASE_INSENSITIVE -> authenticateWindowsObserver().identity();
            case CASE_SENSITIVE -> LINUX_OBSERVER_IDENTITY;
        };
    }

    private static WindowsObserver authenticateWindowsObserver() throws IOException {
        String systemRoot = Objects.toString(System.getenv("SystemRoot"), "").strip();
        String windowsDirectory = Objects.toString(System.getenv("windir"), "").strip();
        if (systemRoot.isBlank() || windowsDirectory.isBlank()) {
            throw new IOException("Windows observer root is unavailable");
        }
        return authenticateWindowsObserver(
                Path.of(systemRoot), Path.of(windowsDirectory));
    }

    static WindowsObserver authenticateWindowsObserver(
            Path systemRoot,
            Path windowsDirectory) throws IOException {
        Path root = NoFollowPathPolicy.requireUnlinkedDirectory(systemRoot);
        Path windows = NoFollowPathPolicy.requireUnlinkedDirectory(windowsDirectory);
        if (!normalizedWindowsLocation(root).equals(normalizedWindowsLocation(windows))) {
            throw new IOException("Windows observer roots differ");
        }
        Path system32 = NoFollowPathPolicy.requireUnlinkedDirectory(
                root.resolve("System32"));
        Path helper = NoFollowPathPolicy.requireUnlinkedRegularFile(
                system32.resolve("netstat.exe"));
        return new WindowsObserver(
                system32, helper, windowsObserverIdentity(system32, helper));
    }

    static ObserverIdentity windowsObserverIdentity(
            Path authenticatedSystem32,
            Path authenticatedHelper) throws IOException {
        Path system32 = NoFollowPathPolicy.requireUnlinkedDirectory(
                authenticatedSystem32);
        Path helper = NoFollowPathPolicy.requireUnlinkedRegularFile(
                authenticatedHelper);
        if (helper.getParent() == null
                || !normalizedWindowsLocation(helper.getParent()).equals(
                        normalizedWindowsLocation(system32))
                || !"netstat.exe".equalsIgnoreCase(
                        Objects.toString(helper.getFileName(), ""))) {
            throw new IOException("Windows endpoint observer helper location is invalid");
        }
        byte[] helperBytes;
        try (InputStream input = NoFollowPathPolicy.openUnlinkedRegularFile(helper)) {
            helperBytes = input.readNBytes(MAX_OBSERVER_HELPER_BYTES + 1);
        }
        if (helperBytes.length > MAX_OBSERVER_HELPER_BYTES) {
            throw new IOException("Windows endpoint observer helper exceeds its bound");
        }
        String normalizedLocationSha256 =
                ProviderProcessLaunchReceipt.digestOrderedValues(List.of(
                        "talos.windowsSystem32NormalizedLocation",
                        "1",
                        normalizedWindowsLocation(system32)));
        List<byte[]> fields = new ArrayList<>();
        fields.add("talos.llamaCppEndpointObserver".getBytes(StandardCharsets.UTF_8));
        fields.add("1".getBytes(StandardCharsets.UTF_8));
        fields.add("WINDOWS_NETSTAT_NOFOLLOW_SHA256".getBytes(StandardCharsets.UTF_8));
        fields.add(normalizedLocationSha256.getBytes(StandardCharsets.US_ASCII));
        fields.add("netstat.exe".getBytes(StandardCharsets.US_ASCII));
        for (String argument : WINDOWS_QUERY_ARGUMENTS) {
            fields.add(argument.getBytes(StandardCharsets.US_ASCII));
        }
        fields.add("EXPLICIT_SYSTEMROOT_ONLY_CHILD_ENVIRONMENT".getBytes(
                StandardCharsets.US_ASCII));
        fields.add(ProviderProcessLaunchReceipt.digestEffectiveEnvironment(
                        windowsObserverEnvironment(system32))
                .getBytes(StandardCharsets.US_ASCII));
        fields.add("AUTHENTICATED_SYSTEM32_WORKING_DIRECTORY".getBytes(
                StandardCharsets.US_ASCII));
        fields.add("PREFLIGHT_EXACT_OR_WILDCARD_READINESS_EXACT_ONLY".getBytes(
                StandardCharsets.US_ASCII));
        fields.add(helperBytes);
        return new ObserverIdentity(
                ProviderProcessLaunchReceipt.EndpointObserverKind
                        .WINDOWS_NETSTAT_NOFOLLOW_SHA256,
                digestLengthDelimitedBytes(fields));
    }

    static ProcessBuilder windowsQueryBuilder(WindowsObserver observer) {
        WindowsObserver exact = Objects.requireNonNull(observer, "Windows observer");
        List<String> command = new ArrayList<>();
        command.add(exact.helper().toString());
        command.addAll(WINDOWS_QUERY_ARGUMENTS);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(exact.system32Directory().toFile())
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(
                windowsObserverEnvironment(exact.system32Directory()));
        return builder;
    }

    static Map<String, String> windowsObserverEnvironment(Path authenticatedSystem32) {
        Path system32 = Objects.requireNonNull(
                authenticatedSystem32, "authenticated System32 directory");
        Path systemRoot = system32.getParent();
        if (systemRoot == null) {
            throw new IllegalArgumentException(
                    "authenticated System32 directory lacks its Windows root");
        }
        return Map.of("SYSTEMROOT", systemRoot.toString());
    }

    private static String normalizedWindowsLocation(Path path) {
        return Objects.requireNonNull(path, "Windows observer location")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace('/', '\\')
                .toLowerCase(Locale.ROOT);
    }

    private static String digestLengthDelimitedBytes(List<byte[]> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] value : values) {
                byte[] exact = Objects.requireNonNull(value, "observer identity field");
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(exact.length).array());
                digest.update(exact);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static ObserverIdentity unavailableObserverIdentity(
            ProviderProcessLaunchReceipt.EndpointObserverKind kind) {
        return new ObserverIdentity(
                kind,
                ProviderProcessLaunchReceipt.digestOrderedValues(List.of(
                        "talos.llamaCppEndpointObserverUnavailable", "1", kind.name())));
    }

    private static Observation observeLinux(
            String host,
            int port,
            long expectedProcessId,
            long expectedStartEpochMilli,
            Duration timeout,
            ObserverIdentity observerIdentity) {
        try {
            long deadline = System.nanoTime() + boundedTimeout(timeout).toNanos();
            Set<String> exactListenerInodes = new HashSet<>();
            Set<String> wildcardListenerInodes = new HashSet<>();
            collectLinuxListeners(
                    Path.of("/proc/net/tcp"), host, port, false,
                    exactListenerInodes, wildcardListenerInodes, deadline);
            collectLinuxListeners(
                    Path.of("/proc/net/tcp6"), host, port, true,
                    exactListenerInodes, wildcardListenerInodes, deadline);
            if (exactListenerInodes.isEmpty() && wildcardListenerInodes.isEmpty()) {
                return Observation.absent(observerIdentity);
            }
            if (expectedProcessId < 1
                    || !exactHostListenerScope(exactListenerInodes, wildcardListenerInodes)
                    || !liveProcessIdentity(expectedProcessId, expectedStartEpochMilli)) {
                return Observation.other(observerIdentity);
            }
            Map<Long, Set<String>> owners = linuxManagedTreeListenerOwners(
                    Path.of("/proc"), expectedProcessId, expectedStartEpochMilli,
                    exactListenerInodes, deadline);
            if (!exclusiveLinuxListenerOwner(
                    owners, expectedProcessId, exactListenerInodes)) {
                return Observation.other(observerIdentity);
            }
            if (!liveProcessIdentity(expectedProcessId, expectedStartEpochMilli)) {
                return Observation.other(observerIdentity);
            }
            return Observation.exact(
                    expectedProcessId, expectedStartEpochMilli, observerIdentity);
        } catch (Exception unavailable) {
            return Observation.indeterminate(observerIdentity);
        }
    }

    static void collectLinuxListeners(
            Path table,
            String host,
            int port,
            boolean ipv6,
            Set<String> inodes,
            long deadline) throws IOException {
        Set<String> exact = new HashSet<>();
        Set<String> wildcard = new HashSet<>();
        collectLinuxListeners(table, host, port, ipv6, exact, wildcard, deadline);
        inodes.addAll(exact);
        inodes.addAll(wildcard);
    }

    static void collectLinuxListeners(
            Path table,
            String host,
            int port,
            boolean ipv6,
            Set<String> exactInodes,
            Set<String> wildcardInodes,
            long deadline) throws IOException {
        if (!Files.isRegularFile(table, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(table)) {
            throw new IOException("procfs listener table is unavailable");
        }
        int rows = 0;
        try (BufferedReader reader = Files.newBufferedReader(
                table, StandardCharsets.US_ASCII)) {
            for (String raw; (raw = reader.readLine()) != null;) {
                if (++rows > MAX_PROC_ROWS || System.nanoTime() > deadline) {
                    throw new IOException("procfs listener scan exceeded its bound");
                }
                if (raw.length() > MAX_PROC_LINE_CHARS) {
                    throw new IOException("procfs listener row exceeds its bound");
                }
                String line = raw.strip();
                if (line.isBlank() || line.startsWith("sl")) continue;
                String[] fields = line.split("\\s+");
                if (fields.length < 10 || !"0A".equals(fields[3])) continue;
                String[] local = fields[1].split(":", 2);
                if (local.length != 2) continue;
                int observedPort;
                try {
                    observedPort = Integer.parseInt(local[1], 16);
                } catch (NumberFormatException malformed) {
                    throw new IOException("malformed procfs listener port", malformed);
                }
                LinuxAddressScope addressScope = linuxAddressScope(host, local[0], ipv6);
                if (observedPort != port || addressScope == LinuxAddressScope.NONE) {
                    continue;
                }
                if (!fields[9].matches("[1-9][0-9]*")) {
                    throw new IOException("malformed procfs listener inode");
                }
                (addressScope == LinuxAddressScope.EXACT
                        ? exactInodes : wildcardInodes).add(fields[9]);
            }
        }
    }

    static boolean linuxAddressMatches(String host, String address, boolean ipv6) {
        return linuxAddressScope(host, address, ipv6) != LinuxAddressScope.NONE;
    }

    static boolean exactHostListenerScope(
            Set<String> exactInodes, Set<String> wildcardInodes) {
        return exactInodes != null
                && !exactInodes.isEmpty()
                && wildcardInodes != null
                && wildcardInodes.isEmpty();
    }

    enum LinuxAddressScope {
        EXACT,
        WILDCARD,
        NONE
    }

    static LinuxAddressScope linuxAddressScope(String host, String address, boolean ipv6) {
        if ("127.0.0.1".equals(host) && !ipv6) {
            if ("0100007F".equals(address)) return LinuxAddressScope.EXACT;
            if ("00000000".equals(address)) return LinuxAddressScope.WILDCARD;
            return LinuxAddressScope.NONE;
        }
        if ("127.0.0.1".equals(host) && ipv6) {
            if ("0000000000000000FFFF00000100007F".equals(address)) {
                return LinuxAddressScope.EXACT;
            }
            if ("00000000000000000000000000000000".equals(address)) {
                return LinuxAddressScope.WILDCARD;
            }
            return LinuxAddressScope.NONE;
        }
        if ("::1".equals(host) && ipv6) {
            if ("00000000000000000000000001000000".equals(address)) {
                return LinuxAddressScope.EXACT;
            }
            if ("00000000000000000000000000000000".equals(address)) {
                return LinuxAddressScope.WILDCARD;
            }
            return LinuxAddressScope.NONE;
        }
        return LinuxAddressScope.NONE;
    }

    /**
     * Reverse-maps every matching listener inode through the expected process
     * and its live descendants. The tree and matching owner map must be stable
     * across the bounded scan; incomplete managed-tree visibility is therefore
     * indeterminate rather than exact.
     */
    static Map<Long, Set<String>> linuxManagedTreeListenerOwners(
            Path procRoot,
            long expectedProcessId,
            long expectedStartEpochMilli,
            Set<String> listenerInodes,
            long deadline) throws IOException {
        Path root = Objects.requireNonNull(procRoot, "proc root");
        Set<String> listeners = Set.copyOf(Objects.requireNonNull(
                listenerInodes, "listener inodes"));
        if (expectedProcessId < 1 || expectedStartEpochMilli < 1 || listeners.isEmpty()) {
            throw new IOException("listener-owner scan lacks an expected process or inode");
        }
        Map<Long, Long> treeBefore = linuxManagedProcessTree(
                expectedProcessId, expectedStartEpochMilli, deadline);
        Map<Long, Set<String>> first = linuxListenerOwnersForProcesses(
                root, treeBefore.keySet(), listeners, deadline);
        Map<Long, Set<String>> second = linuxListenerOwnersForProcesses(
                root, treeBefore.keySet(), listeners, deadline);
        Map<Long, Long> treeAfter = linuxManagedProcessTree(
                expectedProcessId, expectedStartEpochMilli, deadline);
        if (!treeBefore.equals(treeAfter) || !first.equals(second)) {
            throw new IOException("managed process tree changed during listener-owner scan");
        }
        return second;
    }

    static Map<Long, Set<String>> linuxListenerOwnersForProcesses(
            Path procRoot,
            Set<Long> processIds,
            Set<String> listenerInodes,
            long deadline) throws IOException {
        Path root = Objects.requireNonNull(procRoot, "proc root");
        Set<Long> processes = Set.copyOf(Objects.requireNonNull(
                processIds, "managed process IDs"));
        Set<String> listeners = Set.copyOf(Objects.requireNonNull(
                listenerInodes, "listener inodes"));
        if (processes.isEmpty() || listeners.isEmpty()) {
            throw new IOException("listener-owner scan lacks a managed process or inode");
        }
        Map<Long, Set<String>> owners = new LinkedHashMap<>();
        for (long processId : processes.stream().sorted().toList()) {
            if (processId < 1 || System.nanoTime() > deadline) {
                throw new IOException("managed process descriptor scan exceeded its bound");
            }
            Set<String> matches = new HashSet<>(
                    linuxSocketInodes(root, processId, deadline));
            matches.retainAll(listeners);
            if (!matches.isEmpty()) owners.put(processId, Set.copyOf(matches));
        }
        return Map.copyOf(owners);
    }

    static boolean exclusiveLinuxListenerOwner(
            Map<Long, Set<String>> owners,
            long expectedProcessId,
            Set<String> listenerInodes) {
        if (owners == null || expectedProcessId < 1
                || listenerInodes == null || listenerInodes.isEmpty()) {
            return false;
        }
        Set<String> expectedInodes = owners.getOrDefault(expectedProcessId, Set.of());
        return expectedInodes.containsAll(listenerInodes)
                && owners.keySet().equals(Set.of(expectedProcessId));
    }

    private static Map<Long, Long> linuxManagedProcessTree(
            long expectedProcessId,
            long expectedStartEpochMilli,
            long deadline) throws IOException {
        ProcessHandle expected = ProcessHandle.of(expectedProcessId)
                .orElseThrow(() -> new IOException("managed process is unavailable"));
        Map<Long, Long> identities = new LinkedHashMap<>();
        addManagedProcessIdentity(
                identities, expected, expectedProcessId, expectedStartEpochMilli, deadline);
        try (java.util.stream.Stream<ProcessHandle> descendants = expected.descendants()) {
            java.util.Iterator<ProcessHandle> iterator = descendants.iterator();
            int observed = 1;
            while (iterator.hasNext()) {
                if (++observed > MAX_PROC_PROCESSES || System.nanoTime() > deadline) {
                    throw new IOException("managed process tree scan exceeded its bound");
                }
                ProcessHandle descendant = iterator.next();
                if (!descendant.isAlive()) continue;
                addManagedProcessIdentity(identities, descendant, 0L, 0L, deadline);
            }
        }
        if (!liveProcessIdentity(expectedProcessId, expectedStartEpochMilli)) {
            throw new IOException("managed process changed during tree scan");
        }
        return Map.copyOf(identities);
    }

    private static void addManagedProcessIdentity(
            Map<Long, Long> identities,
            ProcessHandle process,
            long requiredProcessId,
            long requiredStartEpochMilli,
            long deadline) throws IOException {
        if (System.nanoTime() > deadline || !process.isAlive()) {
            throw new IOException("managed process identity is unavailable");
        }
        long processId = process.pid();
        long started = process.info().startInstant()
                .map(value -> value.truncatedTo(ChronoUnit.MILLIS))
                .map(Instant::toEpochMilli)
                .orElseThrow(() -> new IOException(
                        "managed process start identity is unavailable"));
        if ((requiredProcessId > 0 && processId != requiredProcessId)
                || (requiredStartEpochMilli > 0 && started != requiredStartEpochMilli)) {
            throw new IOException("managed process identity differs from its launch");
        }
        Long prior = identities.putIfAbsent(processId, started);
        if (prior != null && prior != started) {
            throw new IOException("managed process ID was reused during tree scan");
        }
    }

    private static Set<String> linuxSocketInodes(
            Path procRoot,
            long processId,
            long deadline) throws IOException {
        Path fdRoot = procRoot.resolve(Long.toString(processId)).resolve("fd");
        Set<String> inodes = new HashSet<>();
        int count = 0;
        try (DirectoryStream<Path> descriptors = Files.newDirectoryStream(fdRoot)) {
            for (Path descriptor : descriptors) {
                if (++count > MAX_PROCESS_DESCRIPTORS || System.nanoTime() > deadline) {
                    throw new IOException("process descriptor scan exceeded its bound");
                }
                final Path target;
                try {
                    target = Files.readSymbolicLink(descriptor);
                } catch (NoSuchFileException racedClosedDescriptor) {
                    continue;
                }
                String raw = target.toString();
                if (raw.matches("socket:\\[[1-9][0-9]*]")) {
                    inodes.add(raw.substring("socket:[".length(), raw.length() - 1));
                }
            }
        }
        return Set.copyOf(inodes);
    }

    private static boolean liveProcessIdentity(long processId, long expectedStartEpochMilli) {
        if (processId < 1 || expectedStartEpochMilli < 1) return false;
        Optional<ProcessHandle> handle = ProcessHandle.of(processId);
        if (handle.isEmpty() || !handle.get().isAlive()) return false;
        return handle.get().info().startInstant()
                .map(value -> value.truncatedTo(ChronoUnit.MILLIS))
                .map(Instant::toEpochMilli)
                .filter(value -> value == expectedStartEpochMilli)
                .isPresent();
    }

    private static Duration boundedTimeout(Duration requested) {
        long millis = requested == null ? 5_000L : requested.toMillis();
        return Duration.ofMillis(Math.max(250L, Math.min(10_000L, millis)));
    }

    private static boolean numericLoopback(String host) {
        return "127.0.0.1".equals(host);
    }
}
