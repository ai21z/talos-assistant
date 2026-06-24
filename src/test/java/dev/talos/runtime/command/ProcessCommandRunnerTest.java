package dev.talos.runtime.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void redactsBareSecretShapesFromCommandOutput(@TempDir Path workspace) {
        CommandResult result = new ProcessCommandRunner().run(plan(
                javaExecutable(),
                List.of("-cp", classPath(), SecretShapePrinter.class.getName()),
                workspace,
                20_000,
                CommandOutputLimits.defaults()));

        assertTrue(result.success(), result.stderr());
        assertTrue(result.redactionApplied());
        assertFalse(result.stdout().contains("ghp_AbCdEfGhIjKlMnOpQrStUvWxYz1234567890"), result.stdout());
        assertFalse(result.stdout().contains("sk-proj-abcdefghijklmnopqrstuvwxyz1234567890"), result.stdout());
        assertFalse(result.stdout().contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"), result.stdout());
        assertFalse(result.stdout().contains("PRIVATE KEY"), result.stdout());
        assertFalse(result.stdout().contains("supersecret"), result.stdout());
        assertTrue(result.stdout().contains("[redacted]"), result.stdout());
    }

    @Test
    void internalFailureRedactsProtectedExecutablePath(@TempDir Path workspace) {
        Path protectedExecutable = workspace.resolve("protected").resolve("FILE_DISCOVERED_CANARY_CMD_EXEC.exe");

        CommandResult result = new ProcessCommandRunner().run(plan(
                protectedExecutable.toString(),
                List.of(),
                workspace,
                20_000,
                CommandOutputLimits.defaults()));

        assertFalse(result.success());
        assertFalse(result.errorMessage().contains("FILE_DISCOVERED_CANARY_CMD_EXEC"), result.errorMessage());
        assertFalse(result.errorMessage().contains("\\protected\\"), result.errorMessage());
        assertFalse(result.errorMessage().contains("/protected/"), result.errorMessage());
        assertTrue(result.errorMessage().contains("<protected-path>"), result.errorMessage());
    }

    @Test
    void posixEnvironmentAllowlistKeepsGradleBasicsWithoutBroadLeak() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("HOME", "/home/talos");
        source.put("LANG", "C.UTF-8");
        source.put("LC_ALL", "C.UTF-8");
        source.put("TMPDIR", "/tmp/talos");
        source.put("JAVA_HOME", "/opt/jdk-21");
        source.put("PATH", "/usr/bin");
        source.put("AWS_SECRET_ACCESS_KEY", "do-not-forward");
        source.put("TALOS_FAKE_SECRET", "do-not-forward");

        Map<String, String> environment = ProcessCommandRunner.filteredEnvironment(
                source,
                CommandRuntimePlatform.posix());

        assertEquals("/home/talos", environment.get("HOME"));
        assertEquals("C.UTF-8", environment.get("LANG"));
        assertEquals("C.UTF-8", environment.get("LC_ALL"));
        assertEquals("/tmp/talos", environment.get("TMPDIR"));
        assertEquals("/opt/jdk-21", environment.get("JAVA_HOME"));
        assertEquals("/usr/bin", environment.get("PATH"));
        assertEquals(6, environment.size(), "POSIX command environment must stay narrowly allowlisted");
        assertFalse(environment.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(environment.containsKey("TALOS_FAKE_SECRET"));
    }

    @Test
    void windowsEnvironmentAllowlistKeepsProcessBasicsWithoutBroadLeak() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("SystemRoot", "C:\\Windows");
        source.put("WINDIR", "C:\\Windows");
        source.put("ComSpec", "C:\\Windows\\System32\\cmd.exe");
        source.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        source.put("TEMP", "C:\\Users\\talos\\AppData\\Local\\Temp");
        source.put("TMP", "C:\\Users\\talos\\AppData\\Local\\Temp");
        source.put("JAVA_HOME", "C:\\jdk-21");
        source.put("PATH", "C:\\Windows\\System32;C:\\jdk-21\\bin");
        source.put("AWS_SECRET_ACCESS_KEY", "do-not-forward");
        source.put("TALOS_FAKE_SECRET", "do-not-forward");

        Map<String, String> environment = ProcessCommandRunner.filteredEnvironment(
                source,
                CommandRuntimePlatform.windows());

        assertEquals("C:\\Windows", environment.get("SystemRoot"));
        assertEquals("C:\\Windows", environment.get("WINDIR"));
        assertEquals("C:\\Windows\\System32\\cmd.exe", environment.get("ComSpec"));
        assertEquals(".COM;.EXE;.BAT;.CMD", environment.get("PATHEXT"));
        assertEquals("C:\\Users\\talos\\AppData\\Local\\Temp", environment.get("TEMP"));
        assertEquals("C:\\Users\\talos\\AppData\\Local\\Temp", environment.get("TMP"));
        assertEquals("C:\\jdk-21", environment.get("JAVA_HOME"));
        assertEquals("C:\\Windows\\System32;C:\\jdk-21\\bin", environment.get("PATH"));
        assertEquals(8, environment.size(), "Windows command environment must stay narrowly allowlisted");
        assertFalse(environment.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(environment.containsKey("TALOS_FAKE_SECRET"));
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

    public static final class SecretShapePrinter {
        public static void main(String[] args) {
            System.out.println("bare github token ghp_AbCdEfGhIjKlMnOpQrStUvWxYz1234567890");
            System.out.println("openai token sk-proj-abcdefghijklmnopqrstuvwxyz1234567890");
            System.out.println("jwt eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                    + "eyJzdWIiOiJ0YWxvcyIsIm5hbWUiOiJUZXN0In0."
                    + "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
            System.out.println("""
                    -----BEGIN PRIVATE KEY-----
                    MIIEvQIBADANBgkqhkiG9w0BAQEFAASCfakefixtureonly
                    -----END PRIVATE KEY-----""");
            System.out.println("jdbc:postgresql://user:supersecret@localhost/db");
        }
    }
}
