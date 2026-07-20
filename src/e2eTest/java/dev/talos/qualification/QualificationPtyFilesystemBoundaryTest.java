package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QualificationPtyFilesystemBoundaryTest {
    @TempDir
    Path temp;

    @Test
    void ordinaryPtyFilesystemPlanPasses() throws Exception {
        Fixture fixture = fixture("ordinary");

        QualificationPtyFilesystemBoundary.validate(fixture.plan());
    }

    @Test
    void linkedMonitorAndStopTargetFailBeforeExternalBytesCanBeUsed()
            throws Exception {
        Fixture linkedInput = fixture("linked-input");
        Path externalMonitor = Files.writeString(
                temp.resolve("external-monitor.ps1"), "T1170_PTY_INPUT_CANARY");
        Files.delete(linkedInput.monitorScript());
        createSymbolicLinkOrAbort(linkedInput.monitorScript(), externalMonitor);

        assertThrows(NoFollowPathPolicy.Violation.class,
                () -> QualificationPtyFilesystemBoundary.validate(linkedInput.plan()));

        Fixture linkedOutput = fixture("linked-output");
        byte[] externalBytes = "T1170_PTY_OUTPUT_CANARY".getBytes(StandardCharsets.UTF_8);
        Path externalStop = Files.write(temp.resolve("external-stop.txt"), externalBytes);
        createSymbolicLinkOrAbort(linkedOutput.stopFile(), externalStop);

        assertThrows(NoFollowPathPolicy.Violation.class,
                () -> QualificationPtyFilesystemBoundary.validate(linkedOutput.plan()));
        assertArrayEquals(externalBytes, Files.readAllBytes(externalStop));
    }

    @Test
    void failedContainmentInvokesNeitherChildStartAction() throws Exception {
        Fixture fixture = fixture("launch-gate-linked-input");
        Path externalMonitor = Files.writeString(
                temp.resolve("launch-gate-external-monitor.ps1"),
                "T1170_PTY_LAUNCH_GATE_CANARY");
        Files.delete(fixture.monitorScript());
        createSymbolicLinkOrAbort(fixture.monitorScript(), externalMonitor);
        AtomicInteger monitorStarts = new AtomicInteger();
        AtomicInteger talosStarts = new AtomicInteger();

        assertThrows(
                NoFollowPathPolicy.Violation.class,
                () -> QualificationPtyFilesystemBoundary.validateThenStart(
                        fixture.plan(),
                        monitorStarts::incrementAndGet,
                        talosStarts::incrementAndGet));

        assertEquals(0, monitorStarts.get());
        assertEquals(0, talosStarts.get());
    }

    @Test
    void ordinaryContainmentStartsChildrenInDeclaredOrder() throws Exception {
        Fixture fixture = fixture("launch-gate-ordinary");
        List<String> starts = new ArrayList<>();

        QualificationPtyFilesystemBoundary.validateThenStart(
                fixture.plan(),
                () -> starts.add("monitor"),
                () -> starts.add("talos"));

        assertEquals(List.of("monitor", "talos"), starts);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void installedLibraryWindowsJunctionFailsPreflight() throws Exception {
        Fixture fixture = fixture("windows-junction");
        Path external = Files.createDirectory(temp.resolve("external-installed-lib"));
        Files.writeString(external.resolve("canary.jar"), "T1170_PTY_JUNCTION_CANARY");
        Path junction = fixture.installedRoot().resolve("lib/junction");
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), external.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);

        assertThrows(NoFollowPathPolicy.Violation.class,
                () -> QualificationPtyFilesystemBoundary.validate(fixture.plan()));
    }

    private Fixture fixture(String name) throws Exception {
        Path root = Files.createDirectories(temp.resolve(name).resolve("packet"));
        Path runRoot = Files.createDirectories(root.resolve("runs/trial"));
        Path workspace = Files.createDirectories(root.resolve("workspaces/trial"));
        Path home = Files.createDirectories(root.resolve("home/logs")).getParent();
        Path campaign = Files.writeString(root.resolve("campaign.json"), "{}");
        Path calibration = Files.writeString(root.resolve("calibration.json"), "{}");
        Path config = Files.writeString(root.resolve("config.yaml"), "engines: {}\n");
        Path monitor = Files.writeString(root.resolve("monitor.ps1"), "exit 0\n");
        Path scenario = Files.writeString(runRoot.resolve("scenario.json"), "{}");
        Path stop = runRoot.resolve("resource-monitor.stop");
        Path samples = runRoot.resolve("resource-samples.csv");
        Path transcript = runRoot.resolve("transcript.raw.json");
        Path failureTranscript = runRoot.resolve("transcript.failed.txt");
        Path llamaLog = root.resolve("home/logs/llama.log");

        Path absolute = Files.createDirectory(temp.resolve(name).resolve("absolute"));
        Path model = Files.writeString(absolute.resolve("model.gguf"), "model");
        Path server = Files.writeString(absolute.resolve("llama-server.exe"), "server");
        Path powershell = Files.writeString(absolute.resolve("pwsh.exe"), "pwsh");
        Path winpty = Files.writeString(absolute.resolve("winpty.exe"), "winpty");

        Path installedRoot = Files.createDirectories(
                temp.resolve(name).resolve("installed/bin")).getParent();
        Files.createDirectories(installedRoot.resolve("lib"));
        Path launcher = Files.writeString(installedRoot.resolve("bin/talos.bat"), "launcher");
        Files.writeString(installedRoot.resolve("lib/talos.jar"), "jar");

        QualificationPtyFilesystemBoundary.Plan plan =
                new QualificationPtyFilesystemBoundary.Plan(
                        NoFollowPathPolicy.bindExistingDirectory(root),
                        root,
                        List.of(campaign, calibration, config, monitor),
                        List.of(home),
                        List.of(llamaLog),
                        NoFollowPathPolicy.bindExistingDirectory(runRoot),
                        runRoot,
                        List.of(scenario),
                        List.of(stop, samples, transcript, failureTranscript),
                        NoFollowPathPolicy.bindExistingDirectory(workspace),
                        workspace,
                        List.of(model, server, powershell, winpty, launcher),
                        installedRoot,
                        launcher);
        return new Fixture(plan, monitor, stop, installedRoot);
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (Exception unavailable) {
            Assumptions.abort("symbolic-link fixture unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    private record Fixture(
            QualificationPtyFilesystemBoundary.Plan plan,
            Path monitorScript,
            Path stopFile,
            Path installedRoot) {}
}
