package dev.talos.cli.setup;

import dev.talos.engine.llamacpp.LlamaCppModelProfiles;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Interactive setup wizard milestone 2. This runner is deliberately limited to
 * explicit config writing. It never installs packages, downloads models, starts
 * llama.cpp, or runs doctor.
 */
public final class SetupWizardRunner {
    private SetupWizardRunner() {}

    @FunctionalInterface
    public interface ConfigRenderer {
        String render(String profile, Path serverPath, Path cacheDir, int port);
    }

    public record Result(int exitCode, boolean wroteConfig, Path configPath, Path backupPath, String output) {}

    public static Result run(
            SetupWizardPlan plan,
            InputStream input,
            PrintStream out,
            ConfigRenderer renderer,
            Path cacheDir,
            int port) {
        StringBuilder log = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        try {
            SetupWizardSnapshot snapshot = plan.snapshot();
            line(out, log, "Talos setup wizard");
            line(out, log, "No package installs, model downloads, or model starts will be run.");
            line(out, log, "");
            renderDetectedEnvironment(plan, out, log);

            if (snapshot.javaFeature() < 21) {
                line(out, log, "Blocked: Java 21+ is required before Talos setup can continue.");
                return new Result(2, false, snapshot.configPath(), null, log.toString());
            }

            Path serverPath = chooseServer(snapshot, reader, out, log);
            if (serverPath == null) {
                line(out, log, "Model setup skipped. No config written.");
                line(out, log, "Next: provide a Linux-compatible llama-server path, then rerun `talos setup wizard`.");
                return new Result(0, false, snapshot.configPath(), null, log.toString());
            }

            String profile = chooseProfile(reader, out, log);
            if (profile == null) {
                line(out, log, "Model setup skipped. No config written.");
                return new Result(0, false, snapshot.configPath(), null, log.toString());
            }

            Path configPath = snapshot.configPath();
            if (configPath == null) {
                line(out, log, "Blocked: Talos config path could not be resolved.");
                return new Result(2, false, null, null, log.toString());
            }

            String yaml = renderer.render(profile, serverPath, cacheDir, Math.max(1, port));
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
            line(out, log, "Selected profile: " + profile);
            line(out, log, "No package installs, model downloads, or model starts were run.");
            line(out, log, "Next: run `talos doctor --start` to verify this machine-local setup.");
            return new Result(0, true, configPath, backup, log.toString());
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
            StringBuilder log) throws Exception {
        Path detected = snapshot.llamaServerPath();
        if (detected != null && snapshot.wsl() && isWindowsExecutable(detected)) {
            line(out, log, "Detected llama-server is not Linux-compatible under WSL: " + detected);
            return promptForServer(reader, out, log, snapshot);
        }
        if (detected != null && Files.isRegularFile(detected)) {
            if (askYes(reader, out, log, "Use detected llama-server at " + detected + "? [y/N] ")) {
                return detected;
            }
        }
        return promptForServer(reader, out, log, snapshot);
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

    private static String chooseProfile(BufferedReader reader, PrintStream out, StringBuilder log) throws Exception {
        List<LlamaCppModelProfiles.CannedProfile> accepted = acceptedProfiles();
        line(out, log, "Accepted beta model profiles:");
        for (int i = 0; i < accepted.size(); i++) {
            var profile = accepted.get(i);
            line(out, log, "  " + (i + 1) + ". " + profile.alias()
                    + " - " + profile.hfRepo() + " / " + profile.hfFile());
        }
        String raw = ask(reader, out, log, "Choose profile number/name, or press Enter to skip model config: ");
        if (raw.isBlank() || raw.equalsIgnoreCase("s") || raw.equalsIgnoreCase("skip")) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        try {
            int index = Integer.parseInt(normalized);
            if (index >= 1 && index <= accepted.size()) {
                return accepted.get(index - 1).alias();
            }
        } catch (NumberFormatException ignored) {
            // Fall through to alias matching.
        }
        for (var profile : accepted) {
            if (profile.alias().equals(normalized)) {
                return profile.alias();
            }
        }
        line(out, log, "Unknown accepted beta profile: " + raw + ". No config written.");
        return null;
    }

    private static List<LlamaCppModelProfiles.CannedProfile> acceptedProfiles() {
        ArrayList<LlamaCppModelProfiles.CannedProfile> out = new ArrayList<>();
        for (var profile : LlamaCppModelProfiles.profiles().values()) {
            if (profile.supportTier() == LlamaCppModelProfiles.SupportTier.ACCEPTED_BETA) {
                out.add(profile);
            }
        }
        return List.copyOf(out);
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

    private static String safeTimestamp() {
        return Instant.now().toString().replace(":", "").replace(".", "");
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ');
    }
}
