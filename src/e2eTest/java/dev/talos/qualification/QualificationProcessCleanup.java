package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

final class QualificationProcessCleanup {
    private static final byte[] EXIT_COMMAND = "/exit\r".getBytes(StandardCharsets.UTF_8);
    private static final Duration GRACEFUL_WAIT = Duration.ofSeconds(10);
    private static final Duration NORMAL_WAIT = Duration.ofSeconds(2);
    private static final Duration FORCED_WAIT = Duration.ofSeconds(2);
    private static final Duration SERVER_DISCOVERY_WAIT = Duration.ofSeconds(5);
    private static final String WINDOWS_SERVER_QUERY = """
            $ErrorActionPreference = 'Stop'
            $port = [int]$env:TALOS_QUALIFICATION_SERVER_PORT
            $expected = [IO.Path]::GetFullPath($env:TALOS_QUALIFICATION_SERVER_EXE)
            $earliest = [DateTimeOffset]::Parse($env:TALOS_QUALIFICATION_NOT_BEFORE).UtcDateTime
            $connections = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
              Where-Object { $_.LocalPort -eq $port })
            foreach ($connection in $connections) {
              $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($connection.OwningProcess)"
              if ($null -eq $process -or [string]::IsNullOrWhiteSpace($process.ExecutablePath)) { continue }
              $actual = [IO.Path]::GetFullPath($process.ExecutablePath)
              if (-not [string]::Equals($actual, $expected, [StringComparison]::OrdinalIgnoreCase)) { continue }
              $created = ([DateTime]$process.CreationDate).ToUniversalTime()
              if ($created -lt $earliest) { continue }
              [Console]::Out.WriteLine(('{0}|{1}' -f $process.ProcessId, $created.ToString('o')))
            }
            """;

    private QualificationProcessCleanup() {}

    static Result stop(
            Process root,
            Path discoveryExecutable,
            Path serverExecutable,
            int serverPort,
            Instant notBefore) {
        return stop(
                root,
                discoveryExecutable,
                serverExecutable,
                serverPort,
                notBefore,
                true);
    }

    static Result stop(
            Process root,
            Path discoveryExecutable,
            Path serverExecutable,
            int serverPort,
            Instant notBefore,
            boolean allowGracefulExit) {
        Target rootTarget = root == null ? null : new ProcessTarget(root);
        FinderResult finder = findCampaignServers(
                discoveryExecutable,
                serverExecutable,
                serverPort,
                notBefore);
        return stop(rootTarget, finder.targets(), GRACEFUL_WAIT, NORMAL_WAIT, FORCED_WAIT,
                allowGracefulExit, finder.verified(), finder.errors());
    }

    static Result stop(
            Target root,
            List<Target> campaignServers,
            Duration gracefulWait,
            Duration normalWait,
            Duration forcedWait) {
        return stop(root, campaignServers, gracefulWait, normalWait, forcedWait,
                true, true, List.of());
    }

    static Result stop(
            Target root,
            List<Target> campaignServers,
            Duration gracefulWait,
            Duration normalWait,
            Duration forcedWait,
            boolean allowGracefulExit) {
        return stop(root, campaignServers, gracefulWait, normalWait, forcedWait,
                allowGracefulExit, true, List.of());
    }

    private static Result stop(
            Target root,
            List<Target> campaignServers,
            Duration gracefulWait,
            Duration normalWait,
            Duration forcedWait,
            boolean allowGracefulExit,
            boolean campaignServerDiscoveryVerified,
            List<String> discoveryErrors) {
        List<String> errors = new ArrayList<>(discoveryErrors);
        List<Target> descendants = root == null ? List.of() : safeDescendants(root, errors);
        List<Target> servers = campaignServers == null
                ? List.of()
                : campaignServers.stream().filter(target -> target != null).toList();
        List<Target> owned = distinctTargets(descendants, servers, root);

        List<Long> descendantPids = sortedPids(descendants);
        List<Long> serverPids = sortedPids(servers);
        boolean gracefulRequested = allowGracefulExit && requestGracefulExit(root, errors);

        awaitAll(owned, gracefulWait, errors);
        List<Long> normalStops = stopSurvivors(owned, false, errors);
        awaitAll(owned, normalWait, errors);
        List<Long> forcedStops = stopSurvivors(owned, true, errors);
        awaitAll(owned, forcedWait, errors);
        List<Long> survivors = alivePids(owned);

        Outcome outcome;
        if (!campaignServerDiscoveryVerified || !survivors.isEmpty()) outcome = Outcome.FAILED;
        else if (!forcedStops.isEmpty()) outcome = Outcome.FORCED;
        else if (!normalStops.isEmpty()) outcome = Outcome.TERMINATED;
        else if (gracefulRequested) outcome = Outcome.GRACEFUL;
        else outcome = Outcome.ALREADY_EXITED;

        return new Result(
                outcome,
                gracefulRequested,
                campaignServerDiscoveryVerified,
                descendantPids,
                serverPids,
                normalStops,
                forcedStops,
                survivors,
                errors);
    }

    private static boolean requestGracefulExit(Target root, List<String> errors) {
        if (root == null || !root.isAlive()) return false;
        if (!root.identityMatches()) {
            errors.add("root process identity changed before graceful cleanup");
            return false;
        }
        try {
            OutputStream input = root.input();
            input.write(EXIT_COMMAND);
            input.flush();
            return true;
        } catch (Exception failure) {
            errors.add("failed to request /exit from PID " + root.pid() + ": "
                    + safeMessage(failure));
            return false;
        }
    }

    private static List<Target> safeDescendants(Target root, List<String> errors) {
        try {
            return root.descendants();
        } catch (Exception failure) {
            errors.add("failed to snapshot descendants of PID " + root.pid() + ": "
                    + safeMessage(failure));
            return List.of();
        }
    }

    private static List<Target> distinctTargets(
            List<Target> descendants,
            List<Target> campaignServers,
            Target root) {
        Map<Long, Target> byPid = new LinkedHashMap<>();
        campaignServers.stream()
                .sorted(Comparator.comparingLong(Target::pid))
                .forEach(target -> byPid.putIfAbsent(target.pid(), target));
        descendants.stream()
                .sorted(Comparator.comparingLong(Target::pid))
                .forEach(target -> byPid.putIfAbsent(target.pid(), target));
        if (root != null) {
            byPid.remove(root.pid());
            byPid.put(root.pid(), root);
        }
        return List.copyOf(byPid.values());
    }

    private static void awaitAll(List<Target> targets, Duration timeout, List<String> errors) {
        long timeoutNanos = Math.max(0L, timeout == null ? 0L : timeout.toNanos());
        long deadline = System.nanoTime() + timeoutNanos;
        for (Target target : targets) {
            if (!target.isAlive()) continue;
            long remaining = Math.max(0L, deadline - System.nanoTime());
            try {
                target.waitFor(Duration.ofNanos(remaining));
            } catch (Exception failure) {
                errors.add("failed while awaiting PID " + target.pid() + ": "
                        + safeMessage(failure));
            }
        }
    }

    private static List<Long> stopSurvivors(
            List<Target> targets,
            boolean force,
            List<String> errors) {
        List<Long> stopped = new ArrayList<>();
        for (Target target : targets) {
            if (!target.isAlive()) continue;
            if (!target.identityMatches()) {
                errors.add("refused to stop PID " + target.pid() + " because its identity changed");
                continue;
            }
            try {
                if (force) target.destroyForcibly();
                else target.destroy();
                stopped.add(target.pid());
            } catch (Exception failure) {
                errors.add("failed to " + (force ? "force " : "") + "stop PID "
                        + target.pid() + ": " + safeMessage(failure));
            }
        }
        return List.copyOf(stopped);
    }

    private static List<Long> alivePids(List<Target> targets) {
        return targets.stream()
                .filter(Target::isAlive)
                .map(Target::pid)
                .distinct()
                .sorted()
                .toList();
    }

    private static List<Long> sortedPids(List<Target> targets) {
        return targets.stream().map(Target::pid).distinct().sorted().toList();
    }

    private static FinderResult findCampaignServers(
            Path discoveryExecutable,
            Path expectedExecutable,
            int expectedPort,
            Instant notBefore) {
        if (notBefore == null) return FinderResult.verified(List.of());
        if (expectedExecutable == null || expectedPort <= 0) {
            return FinderResult.failed("campaign server identity is incomplete");
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return findWindowsCampaignServers(
                    discoveryExecutable,
                    expectedExecutable,
                    expectedPort,
                    notBefore);
        }
        return findPortableCampaignServers(expectedExecutable, expectedPort, notBefore);
    }

    private static FinderResult findWindowsCampaignServers(
            Path discoveryExecutable,
            Path expectedExecutable,
            int expectedPort,
            Instant notBefore) {
        return findWindowsCampaignServers(
                discoveryExecutable,
                expectedExecutable,
                expectedPort,
                notBefore,
                ProcessBuilder::start);
    }

    static FinderResult findWindowsCampaignServers(
            Path discoveryExecutable,
            Path expectedExecutable,
            int expectedPort,
            Instant notBefore,
            QueryProcessStarter starter) {
        Process query = null;
        try {
            Path validatedDiscovery =
                    NoFollowPathPolicy.requireUnlinkedRegularFile(discoveryExecutable);
            ProcessBuilder builder = new ProcessBuilder(
                    validatedDiscovery.toString(),
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    WINDOWS_SERVER_QUERY)
                    .redirectErrorStream(true);
            builder.environment().put("TALOS_QUALIFICATION_SERVER_PORT", Integer.toString(expectedPort));
            builder.environment().put("TALOS_QUALIFICATION_SERVER_EXE",
                    expectedExecutable.toAbsolutePath().normalize().toString());
            builder.environment().put("TALOS_QUALIFICATION_NOT_BEFORE",
                    notBefore.minusSeconds(2L).toString());
            NoFollowPathPolicy.requireUnlinkedRegularFile(discoveryExecutable);
            query = starter.start(builder);
            if (!query.waitFor(SERVER_DISCOVERY_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                query.destroyForcibly();
                return FinderResult.failed("campaign server discovery timed out");
            }
            String output = new String(query.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (query.exitValue() != 0) {
                return FinderResult.failed("campaign server discovery failed: " + bounded(output));
            }
            if (output.isBlank()) return FinderResult.verified(List.of());

            Map<Long, Target> targets = new LinkedHashMap<>();
            for (String line : output.split("\\R")) {
                String[] fields = line.strip().split("\\|", 2);
                if (fields.length != 2) {
                    return FinderResult.failed("campaign server discovery returned malformed output");
                }
                long pid = Long.parseLong(fields[0]);
                Instant started = Instant.parse(fields[1]).truncatedTo(ChronoUnit.MILLIS);
                Optional<ProcessHandle> handle = ProcessHandle.of(pid);
                if (handle.isEmpty() || !handle.get().isAlive()) continue;
                targets.putIfAbsent(pid, new HandleTarget(handle.get(), started));
            }
            return FinderResult.verified(targets.values().stream().toList());
        } catch (Exception failure) {
            if (query != null && query.isAlive()) query.destroyForcibly();
            return FinderResult.failed("campaign server discovery failed: " + safeMessage(failure));
        }
    }

    private static FinderResult findPortableCampaignServers(
            Path expectedExecutable,
            int expectedPort,
            Instant notBefore) {
        Path normalizedExpected = expectedExecutable.toAbsolutePath().normalize();
        Instant earliest = notBefore.minusSeconds(2L);
        Pattern portPattern = Pattern.compile(
                "(?:^|\\s)--port(?:=|\\s+)" + expectedPort + "(?=\\s|$)");
        List<Target> matches = new ArrayList<>();
        try (var processes = ProcessHandle.allProcesses()) {
            processes.forEach(handle -> {
                ProcessHandle.Info info = handle.info();
                Optional<Instant> started = info.startInstant();
                Optional<String> command = info.command();
                Optional<String> commandLine = info.commandLine();
                if (!handle.isAlive()
                        || started.isEmpty()
                        || started.get().isBefore(earliest)
                        || command.isEmpty()
                        || commandLine.isEmpty()
                        || !sameExecutable(normalizedExpected, command.get())
                        || !portPattern.matcher(commandLine.get()).find()) {
                    return;
                }
                Target target = new HandleTarget(handle, started.get());
                if (target.identityMatches()) matches.add(target);
            });
        } catch (Exception ignored) {
            return FinderResult.failed("campaign server discovery failed");
        }
        return FinderResult.verified(matches.stream()
                .sorted(Comparator.comparingLong(Target::pid))
                .toList());
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "no diagnostic output";
        String flattened = value.replace('\r', ' ').replace('\n', ' ').strip();
        return flattened.length() <= 500 ? flattened : flattened.substring(0, 500);
    }

    private static boolean sameExecutable(Path expected, String actualCommand) {
        try {
            Path actual = Path.of(actualCommand).toAbsolutePath().normalize();
            if (Files.isRegularFile(expected) && Files.isRegularFile(actual)) {
                return Files.isSameFile(expected, actual);
            }
            return expected.toString().equalsIgnoreCase(actual.toString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String safeMessage(Exception failure) {
        String message = failure == null ? "" : failure.getMessage();
        return message == null || message.isBlank()
                ? failure == null ? "unknown failure" : failure.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ').strip();
    }

    enum Outcome {
        ALREADY_EXITED,
        GRACEFUL,
        TERMINATED,
        FORCED,
        FAILED
    }

    record Result(
            Outcome outcome,
            boolean gracefulExitRequested,
            boolean campaignServerDiscoveryVerified,
            List<Long> capturedDescendantPids,
            List<Long> campaignServerPids,
            List<Long> normalStopPids,
            List<Long> forcedStopPids,
            List<Long> survivingPids,
            List<String> errors) {
        Result {
            capturedDescendantPids = List.copyOf(capturedDescendantPids);
            campaignServerPids = List.copyOf(campaignServerPids);
            normalStopPids = List.copyOf(normalStopPids);
            forcedStopPids = List.copyOf(forcedStopPids);
            survivingPids = List.copyOf(survivingPids);
            errors = List.copyOf(errors);
        }

        Map<String, Object> evidence() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("outcome", outcome.name());
            out.put("gracefulExitRequested", gracefulExitRequested);
            out.put("campaignServerDiscoveryVerified", campaignServerDiscoveryVerified);
            out.put("capturedDescendantPids", capturedDescendantPids);
            out.put("campaignServerPids", campaignServerPids);
            out.put("normalStopPids", normalStopPids);
            out.put("forcedStopPids", forcedStopPids);
            out.put("survivingPids", survivingPids);
            out.put("errors", errors);
            return Map.copyOf(out);
        }
    }

    @FunctionalInterface
    interface QueryProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    record FinderResult(boolean verified, List<Target> targets, List<String> errors) {
        FinderResult {
            targets = List.copyOf(targets);
            errors = List.copyOf(errors);
        }

        private static FinderResult verified(List<Target> targets) {
            return new FinderResult(true, targets, List.of());
        }

        private static FinderResult failed(String error) {
            return new FinderResult(false, List.of(), List.of(error));
        }
    }

    interface Target {
        long pid();
        boolean isAlive();
        boolean identityMatches();
        List<Target> descendants();
        OutputStream input();
        boolean waitFor(Duration timeout) throws Exception;
        void destroy();
        void destroyForcibly();
    }

    private static final class ProcessTarget implements Target {
        private final Process process;

        private ProcessTarget(Process process) {
            this.process = process;
        }

        @Override public long pid() { return process.pid(); }
        @Override public boolean isAlive() { return process.isAlive(); }
        @Override public boolean identityMatches() { return true; }

        @Override
        public List<Target> descendants() {
            // A fresh numeric-PID snapshot cannot establish ownership after the
            // native Process exits. Bind discovery to this still-live root.
            if (!process.isAlive()) return List.of();
            ProcessHandle root = process.toHandle();
            Instant rootStart = root.info().startInstant().orElse(null);
            if (rootStart == null || !root.isAlive()) return List.of();
            List<Target> captured;
            try (var handles = process.descendants()) {
                captured = handles
                        .map(handle -> {
                            Instant started = handle.info().startInstant().orElse(null);
                            if (handle.pid() == root.pid() || !handle.isAlive()
                                    || started == null || started.isBefore(rootStart)) return null;
                            return new HandleTarget(handle, started);
                        })
                        .filter(java.util.Objects::nonNull)
                        .sorted(Comparator.comparingLong(Target::pid))
                        .map(target -> (Target) target)
                        .toList();
            }
            // Closing the snapshot and rechecking the owned Process prevents
            // mid-enumeration exit/replacement from authorizing new descendants.
            return process.isAlive() && root.isAlive()
                    && root.info().startInstant().filter(rootStart::equals).isPresent()
                    ? captured : List.of();
        }

        @Override public OutputStream input() { return process.getOutputStream(); }

        @Override
        public boolean waitFor(Duration timeout) throws Exception {
            return process.waitFor(Math.max(0L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        }

        @Override public void destroy() { process.destroy(); }
        @Override public void destroyForcibly() { process.destroyForcibly(); }
    }

    private static final class HandleTarget implements Target {
        private final ProcessHandle handle;
        private final Instant startInstant;

        private HandleTarget(ProcessHandle handle, Instant startInstant) {
            this.handle = handle;
            this.startInstant = startInstant == null
                    ? null
                    : startInstant.truncatedTo(ChronoUnit.MILLIS);
        }

        @Override public long pid() { return handle.pid(); }
        @Override public boolean isAlive() { return handle.isAlive(); }

        @Override
        public boolean identityMatches() {
            if (!handle.isAlive()) return true;
            return startInstant != null
                    && handle.info().startInstant()
                    .map(value -> value.truncatedTo(ChronoUnit.MILLIS))
                    .filter(startInstant::equals)
                    .isPresent();
        }

        @Override public List<Target> descendants() { return List.of(); }
        @Override public OutputStream input() { return OutputStream.nullOutputStream(); }

        @Override
        public boolean waitFor(Duration timeout) throws Exception {
            if (!handle.isAlive()) return true;
            try {
                handle.onExit().get(Math.max(0L, timeout.toMillis()), TimeUnit.MILLISECONDS);
                return !handle.isAlive();
            } catch (TimeoutException timeoutFailure) {
                return false;
            }
        }

        @Override public void destroy() { handle.destroy(); }
        @Override public void destroyForcibly() { handle.destroyForcibly(); }
    }
}
