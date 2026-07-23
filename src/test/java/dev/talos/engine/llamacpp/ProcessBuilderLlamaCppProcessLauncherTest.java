package dev.talos.engine.llamacpp;

import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessBuilderLlamaCppProcessLauncherTest {

    @TempDir Path temp;

    @Test
    void realChildObservationBindsPidStartCommandAndEffectiveEnvironment() throws Exception {
        List<String> command = sleeperCommand();
        ProcessBuilder expected = new ProcessBuilder(command);
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher();

        LlamaCppProcess child = launcher.start(
                command, temp.resolve("observed-child.log"));
        try {
            LlamaCppProcessLaunchObservation observation = child.launchObservation();
            assertNotNull(observation);
            assertTrue(observation.exactProcessIdentityAvailable());
            assertEquals(
                    ProviderProcessLaunchReceipt.digestOrderedValues(command),
                    observation.launchCommandSha256());
            assertEquals(
                    ProviderProcessLaunchReceipt.digestEffectiveEnvironment(
                            expected.environment()),
                    observation.effectiveEnvironmentSha256());
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(Duration.ofSeconds(2)));
        }
    }

    @Test
    void observationFailureSettlesTheRealChildBeforeControlReturns() throws Exception {
        AtomicLong childPid = new AtomicLong();
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher((process, builder, observed) -> {
                    childPid.set(process.pid());
                    throw new IllegalStateException("injected observation failure");
                });

        assertThrows(IllegalStateException.class, () -> launcher.start(
                sleeperCommand(), temp.resolve("failed-observation-child.log")));

        assertTrue(childPid.get() > 0L);
        ProcessHandle handle = ProcessHandle.of(childPid.get()).orElse(null);
        assertFalse(handle != null && handle.isAlive(),
                "the launcher must not return while an unowned child remains alive");
    }

    @Test
    void exactEnvironmentLaunchUsesTheAuthenticatedWorkingDirectory() throws Exception {
        Path working = Files.createDirectory(temp.resolve("engine-directory"));
        Path log = temp.resolve("working-directory-child.log");
        List<String> command = javaCommand(ExactLaunchReporter.class);
        Map<String, String> exactEnvironment = new LinkedHashMap<>();
        exactEnvironment.put("TMP", temp.toString());
        if ("\\".equals(java.nio.file.FileSystems.getDefault().getSeparator())) {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null) systemRoot = System.getenv("SYSTEMROOT");
            assertNotNull(systemRoot);
            exactEnvironment.put("SYSTEMROOT", systemRoot);
        }
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher((process, builder, observed) -> {
                    assertEquals(exactEnvironment, builder.environment());
                    assertFalse(builder.environment().containsKey("PATH"));
                    long started = process.toHandle().info().startInstant()
                            .map(java.time.Instant::toEpochMilli)
                            .orElse(0L);
                    return new LlamaCppProcessLaunchObservation(
                            process.pid(),
                            started,
                            Math.max(Math.max(1L, observed), started),
                            "0123456789abcdef0123456789abcdef",
                            ProviderProcessLaunchReceipt.digestOrderedValues(builder.command()),
                            ProviderProcessLaunchReceipt.digestEffectiveEnvironment(
                                    builder.environment()));
                });

        LlamaCppProcess child = launcher.startWithExactEnvironment(
                command, log, exactEnvironment, working);

        assertTrue(child.waitFor(Duration.ofSeconds(10)));
        assertEquals(
                working.toAbsolutePath().normalize()
                        + "|" + temp + "|false|"
                        + String.join(",", new TreeSet<>(exactEnvironment.keySet())),
                Files.readString(log).strip());
    }

    @Test
    void launcherWithoutAnExactEnvironmentImplementationFailsClosed() {
        LlamaCppProcessLauncher weakerLauncher = (command, log) -> null;

        assertThrows(UnsupportedOperationException.class,
                () -> weakerLauncher.startWithExactEnvironment(
                        List.of("provider"),
                        temp.resolve("unused.log"),
                        Map.of("PATH", "controlled"),
                        temp));
    }

    @Test
    void exactEnvironmentRejectsUnlistedValuesAndMissingWindowsSystemRoot() {
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher();

        assertThrows(IllegalArgumentException.class,
                () -> launcher.startWithExactEnvironment(
                        sleeperCommand(),
                        temp.resolve("unlisted.log"),
                        Map.of("TALOS_UNLISTED", "value"),
                        temp));
        if ("\\".equals(java.nio.file.FileSystems.getDefault().getSeparator())) {
            assertThrows(IllegalArgumentException.class,
                    () -> launcher.startWithExactEnvironment(
                            sleeperCommand(),
                            temp.resolve("missing-system-root.log"),
                            Map.of("TMP", temp.toString()),
                            temp));
        }
    }

    private static List<String> sleeperCommand() {
        return javaCommand(Sleeper.class);
    }

    private static List<String> javaCommand(Class<?> mainClass) {
        Path java = ProcessHandle.current().info().command()
                .map(Path::of)
                .orElseThrow();
        return List.of(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                mainClass.getName());
    }

    public static final class Sleeper {
        private Sleeper() {}

        public static void main(String[] args) throws Exception {
            Thread.sleep(60_000L);
        }
    }

    public static final class ExactLaunchReporter {
        private ExactLaunchReporter() {}

        public static void main(String[] args) {
            System.out.println(
                    Path.of("").toAbsolutePath().normalize()
                            + "|"
                            + System.getenv("TMP")
                            + "|"
                            + System.getenv().containsKey("PATH")
                            + "|"
                            + String.join(",", new TreeSet<>(System.getenv().keySet())));
        }
    }
}
