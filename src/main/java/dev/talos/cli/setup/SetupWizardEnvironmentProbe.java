package dev.talos.cli.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only setup probe for the wizard dry-run. This class may inspect local
 * files and environment state, but it must not install, download, write config,
 * start models, or mutate PATH/profile files.
 */
public final class SetupWizardEnvironmentProbe {
    private SetupWizardEnvironmentProbe() {}

    public static SetupWizardSnapshot capture(Path explicitConfigPath) {
        return capture(explicitConfigPath, null);
    }

    public static SetupWizardSnapshot capture(Path explicitConfigPath, Path explicitServerPath) {
        Path configPath = explicitConfigPath == null ? defaultConfigPath() : explicitConfigPath;
        boolean configExists = configPath != null && Files.isRegularFile(configPath);
        Path serverPath = explicitServerPath != null
                ? explicitServerPath
                : configExists
                ? configuredServerPath(configPath).orElseGet(SetupWizardEnvironmentProbe::pathServerCandidate)
                : pathServerCandidate();

        return new SetupWizardSnapshot(
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                detectWsl(),
                detectDistro(),
                Runtime.version().feature(),
                configPath,
                configExists,
                serverPath,
                serverPath != null && Files.isRegularFile(serverPath),
                usableDiskMb(configPath),
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                systemMemoryMb());
    }

    private static Path defaultConfigPath() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("USERPROFILE");
        }
        if (home == null || home.isBlank()) {
            return null;
        }
        return Path.of(home, ".talos", "config.yaml");
    }

    private static Optional<Path> configuredServerPath(Path configPath) {
        try {
            Object parsed = new ObjectMapper(new YAMLFactory()).readValue(configPath.toFile(), Object.class);
            if (!(parsed instanceof Map<?, ?> root)) {
                return Optional.empty();
            }
            Object engines = root.get("engines");
            if (!(engines instanceof Map<?, ?> engineMap)) {
                return Optional.empty();
            }
            Object llamaCpp = engineMap.get("llama_cpp");
            if (!(llamaCpp instanceof Map<?, ?> llamaMap)) {
                return Optional.empty();
            }
            Object serverPath = llamaMap.get("server_path");
            if (!(serverPath instanceof String value) || value.isBlank()) {
                return Optional.empty();
            }
            return safePath(value);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Path pathServerCandidate() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] entries = path.split(java.io.File.pathSeparator);
        String[] names = isWindowsOs() ? new String[]{"llama-server.exe", "llama-server"} : new String[]{"llama-server", "llama-server.exe"};
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Optional<Path> dir = safePath(entry);
            if (dir.isEmpty()) {
                continue;
            }
            for (String name : names) {
                Path candidate = dir.get().resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Optional<Path> safePath(String raw) {
        try {
            return Optional.of(Path.of(raw.trim()));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static boolean detectWsl() {
        String distro = System.getenv("WSL_DISTRO_NAME");
        String interop = System.getenv("WSL_INTEROP");
        if ((distro != null && !distro.isBlank()) || (interop != null && !interop.isBlank())) {
            return true;
        }
        return fileContains("/proc/version", "microsoft")
                || fileContains("/proc/sys/kernel/osrelease", "microsoft")
                || fileContains("/proc/sys/kernel/osrelease", "wsl");
    }

    private static String detectDistro() {
        Path osRelease = Path.of("/etc/os-release");
        if (!Files.isRegularFile(osRelease)) {
            return "";
        }
        try {
            for (String line : Files.readAllLines(osRelease)) {
                if (line.startsWith("PRETTY_NAME=")) {
                    return stripShellValue(line.substring("PRETTY_NAME=".length()));
                }
            }
        } catch (IOException ignored) {
            return "";
        }
        return "";
    }

    private static boolean fileContains(String file, String needle) {
        try {
            Path path = Path.of(file);
            return Files.isRegularFile(path)
                    && Files.readString(path).toLowerCase(Locale.ROOT).contains(needle);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String stripShellValue(String value) {
        String out = value == null ? "" : value.trim();
        if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
            return out.substring(1, out.length() - 1);
        }
        return out;
    }

    private static long usableDiskMb(Path anchor) {
        try {
            Path path = anchor == null ? Path.of(System.getProperty("user.home", ".")) : anchor;
            Path probe = Files.exists(path) ? path : nearestExistingParent(path);
            FileStore store = Files.getFileStore(probe);
            return store.getUsableSpace() / (1024 * 1024);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static Path nearestExistingParent(Path path) {
        Path cursor = path == null ? null : path.toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor)) {
            cursor = cursor.getParent();
        }
        return cursor == null ? Path.of(".") : cursor;
    }

    private static long systemMemoryMb() {
        try {
            OperatingSystemMXBean mx = ManagementFactory.getOperatingSystemMXBean();
            if (mx instanceof com.sun.management.OperatingSystemMXBean sun) {
                long bytes = sun.getTotalMemorySize();
                return bytes > 0 ? bytes / (1024 * 1024) : -1;
            }
        } catch (RuntimeException ignored) {
            return -1;
        }
        return -1;
    }

    private static boolean isWindowsOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
