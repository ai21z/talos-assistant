package dev.talos.cli.setup;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/**
 * Interactive setup wizard. It may install a pinned engine only after explicit
 * confirmation. Package-manager work remains outside the JVM wizard; engine
 * install, model download, config write, and doctor start all require explicit
 * confirmation.
 */
public final class SetupWizardRunner {
    private SetupWizardRunner() {}

    @FunctionalInterface
    public interface ConfigRenderer {
        String render(String profile, Path serverPath, Path modelPath, Path cacheDir, int port);
    }

    @FunctionalInterface
    public interface EngineInstaller {
        LlamaCppEngineInstaller.Result install(LlamaCppEngineManifest.Entry entry, Path talosHome) throws Exception;
    }

    @FunctionalInterface
    public interface ModelDownloader {
        LlamaCppModelDownloader.Result download(LlamaCppModelManifest.Entry entry, Path userHome) throws Exception;
    }

    @FunctionalInterface
    public interface DoctorRunner {
        int run(Path configPath, Path workspace, Path talosHome, PrintStream out);
    }

    @FunctionalInterface
    public interface DiskSpaceProbe {
        long usableDiskMb(Path targetPath);
    }

    public record Result(int exitCode, boolean wroteConfig, Path configPath, Path backupPath, String output) {}

    public static Result run(
            SetupWizardPlan plan,
            InputStream input,
            PrintStream out,
            ConfigRenderer renderer,
            Path cacheDir,
            int port) {
        return run(
                plan,
                input,
                out,
                renderer,
                cacheDir,
                port,
                Path.of(System.getProperty("user.home", ".")),
                Path.of(".").toAbsolutePath().normalize(),
                new LlamaCppEngineInstaller()::install,
                new LlamaCppModelDownloader()::download,
                (configPath, workspace, talosHome, doctorOut) -> {
                    doctorOut.println("Doctor execution is not wired for this setup surface.");
                    return 2;
                },
                SetupWizardRunner::usableDiskMb);
    }

    public static Result run(
            SetupWizardPlan plan,
            InputStream input,
            PrintStream out,
            ConfigRenderer renderer,
            Path cacheDir,
            int port,
            Path userHome,
            Path workspace,
            EngineInstaller engineInstaller,
            ModelDownloader modelDownloader,
            DoctorRunner doctorRunner) {
        return run(
                plan,
                input,
                out,
                renderer,
                cacheDir,
                port,
                userHome,
                workspace,
                engineInstaller,
                modelDownloader,
                doctorRunner,
                SetupWizardRunner::usableDiskMb);
    }

    public static Result run(
            SetupWizardPlan plan,
            InputStream input,
            PrintStream out,
            ConfigRenderer renderer,
            Path cacheDir,
            int port,
            Path userHome,
            EngineInstaller engineInstaller) {
        return run(
                plan,
                input,
                out,
                renderer,
                cacheDir,
                port,
                userHome,
                Path.of(".").toAbsolutePath().normalize(),
                engineInstaller,
                new LlamaCppModelDownloader()::download,
                (configPath, workspace, talosHome, doctorOut) -> {
                    doctorOut.println("Doctor execution is not wired for this setup surface.");
                    return 2;
                },
                SetupWizardRunner::usableDiskMb);
    }

    public static Result run(
            SetupWizardPlan plan,
            InputStream input,
            PrintStream out,
            ConfigRenderer renderer,
            Path cacheDir,
            int port,
            Path userHome,
            Path workspace,
            EngineInstaller engineInstaller,
            ModelDownloader modelDownloader,
            DoctorRunner doctorRunner,
            DiskSpaceProbe diskSpaceProbe) {
        StringBuilder log = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        try {
            SetupWizardSnapshot snapshot = plan.snapshot();
            line(out, log, "Talos setup wizard");
            line(out, log, "Engine install, model download, config write, and doctor start each ask before running.");
            line(out, log, "");
            renderDetectedEnvironment(plan, out, log);

            if (snapshot.javaFeature() < 21) {
                line(out, log, "Blocked: Java 21+ is required before Talos setup can continue.");
                return new Result(2, false, snapshot.configPath(), null, log.toString());
            }

            Path serverPath = chooseServer(snapshot, reader, out, log, userHome, engineInstaller);
            if (serverPath == null) {
                line(out, log, "Model setup skipped. No config written.");
                line(out, log, "Next: provide a Linux-compatible llama-server path, then rerun `talos setup wizard`.");
                return new Result(0, false, snapshot.configPath(), null, log.toString());
            }

            LlamaCppModelManifest.Entry model = chooseProfile(reader, out, log);
            if (model == null) {
                line(out, log, "Model setup skipped. No config written.");
                return new Result(0, false, snapshot.configPath(), null, log.toString());
            }

            Path modelPath = downloadModel(model, reader, out, log, userHome, modelDownloader, diskSpaceProbe);
            if (modelPath == null) {
                return new Result(0, false, snapshot.configPath(), null, log.toString());
            }

            Path configPath = snapshot.configPath();
            if (configPath == null) {
                line(out, log, "Blocked: Talos config path could not be resolved.");
                return new Result(2, false, null, null, log.toString());
            }

            String yaml = renderer.render(model.alias(), serverPath, modelPath, cacheDir, Math.max(1, port));
            line(out, log, "");
            line(out, log, "Generated config preview:");
            line(out, log, yaml.stripTrailing());
            line(out, log, "");
            if (Files.exists(configPath)) {
                line(out, log, "Existing config will be backed up before overwrite: " + configPath);
            }
            if (!askYes(reader, out, log, "Write this config to " + configPath + "? [y/N] ")) {
                line(out, log, "No config written.");
                return new Result(0, false, configPath, null, log.toString());
            }

            Path backup = writeConfig(configPath, yaml);
            if (backup != null) {
                line(out, log, "Backed up existing config to " + backup);
            }
            line(out, log, "Wrote Talos model config: " + configPath);
            line(out, log, "Selected profile: " + model.alias());
            line(out, log, "Model path: " + modelPath);
            if (!askYes(reader, out, log, "Run `talos doctor --start` now? [y/N] ")) {
                line(out, log, "Doctor skipped. Next: run `talos doctor --start` from your workspace to verify this setup.");
                return new Result(0, true, configPath, backup, log.toString());
            }
            int doctorExit = runDoctor(doctorRunner, configPath, workspace, userHome, out, log);
            return new Result(doctorExit == 0 ? 0 : 1, true, configPath, backup, log.toString());
        } catch (Exception error) {
            line(out, log, "setup wizard failed: " + safeMessage(error));
            return new Result(2, false, plan.snapshot().configPath(), null, log.toString());
        }
    }

    private static void renderDetectedEnvironment(SetupWizardPlan plan, PrintStream out, StringBuilder log) {
        SetupWizardSnapshot snapshot = plan.snapshot();
        line(out, log, "Detected environment:");
        line(out, log, "  OS: " + snapshot.osName() + " " + snapshot.osArch() + (snapshot.wsl() ? " (WSL)" : ""));
        if (snapshot.distroName() != null && !snapshot.distroName().isBlank()) {
            line(out, log, "  Distro: " + snapshot.distroName());
        }
        line(out, log, "  Java: " + (snapshot.javaFeature() <= 0 ? "unknown" : snapshot.javaFeature()));
        line(out, log, "  Config: " + (snapshot.configPath() == null ? "(unknown)" : snapshot.configPath())
                + (snapshot.configExists() ? " (found)" : " (missing)"));
        line(out, log, "  llama-server: " + (snapshot.llamaServerPath() == null
                ? "not detected"
                : snapshot.llamaServerPath() + (snapshot.llamaServerExists() ? " (file found)" : " (not a file)")));
        line(out, log, "");
    }

    private static Path chooseServer(
            SetupWizardSnapshot snapshot,
            BufferedReader reader,
            PrintStream out,
            StringBuilder log,
            Path talosHome,
            EngineInstaller engineInstaller) throws Exception {
        Path detected = snapshot.llamaServerPath();
        if (detected != null && snapshot.wsl() && isWindowsExecutable(detected)) {
            line(out, log, "Detected llama-server is not Linux-compatible under WSL: " + detected);
        } else if (detected != null && Files.isRegularFile(detected)) {
            if (askYes(reader, out, log, "Use detected llama-server at " + detected + "? [y/N] ")) {
                return detected;
            }
        }

        Path installed = offerPinnedEngineInstall(snapshot, reader, out, log, talosHome, engineInstaller);
        if (installed != null) {
            return installed;
        }
        return promptForServer(reader, out, log, snapshot);
    }

    private static Path offerPinnedEngineInstall(
            SetupWizardSnapshot snapshot,
            BufferedReader reader,
            PrintStream out,
            StringBuilder log,
            Path talosHome,
            EngineInstaller engineInstaller) throws Exception {
        var manifest = LlamaCppEngineManifest.select(snapshot);
        if (manifest.isEmpty()) {
            return null;
        }

        var entry = manifest.get();
        line(out, log, "Pinned llama.cpp engine available:");
        line(out, log, "  variant: " + entry.variant());
        line(out, log, "  tag: " + entry.upstreamTag());
        line(out, log, "  asset: " + entry.assetName());
        line(out, log, "  size: ~" + Math.max(1, entry.sizeBytes() / (1024 * 1024)) + " MB");
        line(out, log, "  SHA-256: " + entry.sha256());
        line(out, log, "  install dir: " + entry.installDir(talosHome));
        if (!askYes(reader, out, log, "Install this pinned llama.cpp engine now? [y/N] ")) {
            return null;
        }

        var install = engineInstaller.install(entry, talosHome);
        line(out, log, install.message());
        if (install.status() == LlamaCppEngineInstaller.Status.INSTALLED
                || install.status() == LlamaCppEngineInstaller.Status.REUSED) {
            return install.serverPath();
        }
        line(out, log, "Pinned engine install failed; you can provide a Linux-compatible llama-server path or skip.");
        return null;
    }

    private static Path promptForServer(
            BufferedReader reader,
            PrintStream out,
            StringBuilder log,
            SetupWizardSnapshot snapshot) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            String raw = ask(reader, out, log, "Enter Linux-compatible llama-server path, or press Enter to skip model setup: ");
            if (raw.isBlank()) {
                return null;
            }
            Path path;
            try {
                path = Path.of(raw.trim());
            } catch (RuntimeException error) {
                line(out, log, "Invalid path: " + safeMessage(error));
                continue;
            }
            if (snapshot.wsl() && isWindowsExecutable(path)) {
                line(out, log, "Rejected: Windows .exe is not Linux-compatible under WSL.");
                continue;
            }
            if (!Files.isRegularFile(path)) {
                line(out, log, "Rejected: llama-server path is not a file: " + path);
                continue;
            }
            return path;
        }
        line(out, log, "No compatible llama-server selected after 3 attempts.");
        return null;
    }

    private static LlamaCppModelManifest.Entry chooseProfile(BufferedReader reader, PrintStream out, StringBuilder log) throws Exception {
        var accepted = LlamaCppModelManifest.acceptedBeta();
        line(out, log, "Accepted beta model profiles:");
        for (int i = 0; i < accepted.size(); i++) {
            var profile = accepted.get(i);
            line(out, log, "  " + (i + 1) + ". " + profile.alias());
            line(out, log, "     source: " + profile.hfRepo());
            line(out, log, "     file: " + profile.hfFile());
            line(out, log, "     " + profile.guidanceLine());
            line(out, log, "     support: " + profile.supportLevel());
        }
        String raw = ask(reader, out, log, "Choose profile number/name, or press Enter to skip model config: ");
        if (raw.isBlank() || raw.equalsIgnoreCase("s") || raw.equalsIgnoreCase("skip")) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        try {
            int index = Integer.parseInt(normalized);
            if (index >= 1 && index <= accepted.size()) {
                return accepted.get(index - 1);
            }
        } catch (NumberFormatException ignored) {
            // Fall through to alias matching.
        }
        for (var profile : accepted) {
            if (profile.alias().equals(normalized)) {
                return profile;
            }
        }
        line(out, log, "Unknown accepted beta profile: " + raw + ". No config written.");
        return null;
    }

    private static Path downloadModel(
            LlamaCppModelManifest.Entry model,
            BufferedReader reader,
            PrintStream out,
            StringBuilder log,
            Path userHome,
            ModelDownloader modelDownloader,
            DiskSpaceProbe diskSpaceProbe) throws Exception {
        Path modelPath = model.modelPath(userHome);
        line(out, log, "Selected model download:");
        line(out, log, "  profile: " + model.alias());
        line(out, log, "  source: " + model.hfRepo());
        line(out, log, "  file: " + model.hfFile());
        line(out, log, "  download: ~" + model.sizeGiB() + " GiB");
        line(out, log, "  disk needed: ~" + model.requiredFreeDiskGiB() + " GiB free under " + modelPath.getParent());
        line(out, log, "  RAM guidance: " + model.ramGuidance());
        line(out, log, "  SHA-256: " + model.sha256());
        if (!askYes(reader, out, log, "Download this model now? [y/N] ")) {
            line(out, log, "Model setup skipped. No config written.");
            return null;
        }

        long usableMb = diskSpaceProbe == null ? usableDiskMb(modelPath) : diskSpaceProbe.usableDiskMb(modelPath);
        long requiredMb = bytesToMbCeil(model.requiredFreeDiskBytes());
        if (usableMb >= 0 && usableMb < requiredMb) {
            line(out, log, "Blocked: not enough free disk for " + model.alias()
                    + ". Need ~" + model.requiredFreeDiskGiB() + " GiB free under "
                    + modelPath.getParent() + "; detected ~" + mbToGiB(usableMb) + " GiB.");
            return null;
        }

        LlamaCppModelDownloader.Result result = modelDownloader.download(model, userHome);
        if (result.status() == LlamaCppModelDownloader.Status.REUSED
                || result.status() == LlamaCppModelDownloader.Status.DOWNLOADED) {
            line(out, log, result.message());
            return result.modelPath();
        }
        if (result.status() == LlamaCppModelDownloader.Status.EXISTING_MISMATCH) {
            line(out, log, result.message());
            if (!askYes(reader, out, log, "Existing model file does not match the pinned checksum. Replace it? [y/N] ")) {
                line(out, log, "No config written.");
                return null;
            }
            try {
                Files.deleteIfExists(result.modelPath());
            } catch (IOException error) {
                line(out, log, "Model download failed: could not remove mismatched file: " + safeMessage(error));
                return null;
            }
            LlamaCppModelDownloader.Result retry = modelDownloader.download(model, userHome);
            if (retry.status() == LlamaCppModelDownloader.Status.REUSED
                    || retry.status() == LlamaCppModelDownloader.Status.DOWNLOADED) {
                line(out, log, retry.message());
                return retry.modelPath();
            }
            line(out, log, "Model download failed: " + retry.message());
            return null;
        }
        line(out, log, "Model download failed: " + result.message());
        return null;
    }

    private static int runDoctor(
            DoctorRunner doctorRunner,
            Path configPath,
            Path workspace,
            Path userHome,
            PrintStream out,
            StringBuilder log) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream doctorOut = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        Path talosHome = (userHome == null ? Path.of(System.getProperty("user.home", ".")) : userHome)
                .resolve(".talos")
                .toAbsolutePath()
                .normalize();
        int exit = doctorRunner.run(
                configPath,
                workspace == null ? Path.of(".").toAbsolutePath().normalize() : workspace.toAbsolutePath().normalize(),
                talosHome,
                doctorOut);
        doctorOut.flush();
        String text = bytes.toString(StandardCharsets.UTF_8);
        out.print(text);
        log.append(text);
        return exit;
    }

    private static Path writeConfig(Path configPath, String yaml) throws Exception {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path backup = null;
        if (Files.exists(configPath)) {
            backup = configPath.resolveSibling(configPath.getFileName() + ".bak-" + safeTimestamp());
            Files.copy(configPath, backup);
        }
        Files.writeString(configPath, yaml, StandardCharsets.UTF_8);
        return backup;
    }

    private static boolean askYes(
            BufferedReader reader,
            PrintStream out,
            StringBuilder log,
            String prompt) throws Exception {
        String answer = ask(reader, out, log, prompt);
        return answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes");
    }

    private static String ask(BufferedReader reader, PrintStream out, StringBuilder log, String prompt) throws Exception {
        out.print(prompt);
        log.append(prompt);
        String line = reader.readLine();
        if (line == null) {
            line = "";
        }
        log.append(line).append('\n');
        return line.trim();
    }

    private static void line(PrintStream out, StringBuilder log, String text) {
        out.println(text);
        log.append(text).append('\n');
    }

    private static boolean isWindowsExecutable(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".exe");
    }

    private static long usableDiskMb(Path target) {
        try {
            Path probe = nearestExistingParent(target);
            FileStore store = Files.getFileStore(probe);
            return store.getUsableSpace() / (1024 * 1024);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static Path nearestExistingParent(Path path) {
        Path cursor = path == null ? Path.of(".").toAbsolutePath().normalize() : path.toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor)) {
            cursor = cursor.getParent();
        }
        return cursor == null ? Path.of(".").toAbsolutePath().normalize() : cursor;
    }

    private static long bytesToMbCeil(long bytes) {
        return Math.max(0, (bytes + (1024 * 1024) - 1) / (1024 * 1024));
    }

    private static String mbToGiB(long mb) {
        return String.format(Locale.ROOT, "%.2f", mb / 1024.0);
    }

    private static String safeTimestamp() {
        return Instant.now().toString().replace(":", "").replace(".", "");
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ');
    }
}
