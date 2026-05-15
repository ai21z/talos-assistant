package dev.talos.runtime.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcessCommandRunnerTest {

    @Test
    void capturesSuccessfulJavaVersionWithoutShell(@TempDir Path workspace) {
        CommandResult result = new ProcessCommandRunner().run(plan(
                javaExecutable(),
                List.of("-version"),
                workspace,
                20_000,
                CommandOutputLimits.defaults()));

        assertTrue(result.success(), result.stderr());
        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.stderr().toLowerCase(java.util.Locale.ROOT).contains("version"),
                result.stderr());
    }

    @Test
    void capturesNonZeroExitCode(@TempDir Path workspace) {
        CommandResult result = new ProcessCommandRunner().run(plan(
                javaExecutable(),
                List.of("-cp", classPath(), ExitWithCode.class.getName(), "7"),
                workspace,
                20_000,
                CommandOutputLimits.defaults()));

        assertFalse(result.success());
        assertEquals(7, result.exitCode());
        assertFalse(result.timedOut());
    }

    @Test
    void timeoutKillsProcess(@TempDir Path workspace) {
        CommandResult result = new ProcessCommandRunner().run(plan(
                javaExecutable(),
                List.of("-cp", classPath(), Sleepy.class.getName()),
                workspace,
                200,
                CommandOutputLimits.defaults()));

        assertFalse(result.success());
        assertTrue(result.timedOut());
        assertTrue(result.killed());
        assertEquals(-1, result.exitCode());
    }

    @Test
    void capsLargeOutput(@TempDir Path workspace) {
        CommandResult result = new ProcessCommandRunner().run(plan(
                javaExecutable(),
                List.of("-cp", classPath(), SpamStdout.class.getName()),
                workspace,
                20_000,
                new CommandOutputLimits(64, 64, 64)));

        assertTrue(result.success(), result.stderr());
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stdout().length() <= 64, result.stdout().length() + " chars");
    }

    @Test
    void redactsSecretLikeOutput(@TempDir Path workspace) {
        CommandResult result = new ProcessCommandRunner().run(plan(
                javaExecutable(),
                List.of("-cp", classPath(), SecretPrinter.class.getName()),
                workspace,
                20_000,
                CommandOutputLimits.defaults()));

        assertTrue(result.success(), result.stderr());
        assertTrue(result.redactionApplied());
        assertTrue(result.stdout().contains("API_TOKEN=[redacted]"), result.stdout());
        assertFalse(result.stdout().contains("abc123"), result.stdout());
    }

    private static CommandPlan plan(
            String executable,
            List<String> argv,
            Path workspace,
            long timeoutMs,
            CommandOutputLimits limits
    ) {
        return new CommandPlan(
                "test_profile",
                "Test profile",
                executable,
                argv,
                workspace,
                CommandRisk.READ_ONLY_DIAGNOSTIC,
                false,
                false,
                List.of(),
                true,
                false,
                timeoutMs,
                100,
                limits);
    }

    private static String javaExecutable() {
        String exe = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static String classPath() {
        return System.getProperty("java.class.path");
    }

    public static final class ExitWithCode {
        public static void main(String[] args) {
            int code = args.length == 0 ? 1 : Integer.parseInt(args[0]);
            System.exit(code);
        }
    }

    public static final class Sleepy {
        public static void main(String[] args) throws Exception {
            Thread.sleep(30_000);
        }
    }

    public static final class SpamStdout {
        public static void main(String[] args) {
            System.out.print("x".repeat(10_000));
        }
    }

    public static final class SecretPrinter {
        public static void main(String[] args) {
            System.out.println("API_TOKEN=abc123");
        }
    }
}
