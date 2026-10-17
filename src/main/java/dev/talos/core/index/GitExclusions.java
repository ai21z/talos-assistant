package dev.talos.core.index;

import dev.talos.safety.SafeLogFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** The files Git keeps in a workspace, so retrieval does not index what Git excludes. */
public final class GitExclusions {
    private static final Logger LOG = LoggerFactory.getLogger(GitExclusions.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_NESTED_DEPTH = 8;
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    private static final List<String> REPOSITORY_OVERRIDES =
            List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR", "GIT_OBJECT_DIRECTORY");

    /** Kept paths are workspace-relative with forward slashes. A null set means Git did not answer. */
    public record Scope(Set<String> kept, String note) {
        public Scope {
            kept = kept == null ? null : kept.stream().map(GitExclusions::key).collect(java.util.stream.Collectors.toUnmodifiableSet());
            note = note == null ? "" : note;
        }
        public boolean applies() { return kept != null; }
        public boolean keeps(String relativePath) {
            return kept == null || kept.contains(key(relativePath));
        }
    }

    private GitExclusions() {}

    /** Tracked and untracked files that Git does not ignore, relative to the workspace root. */
    public static Scope resolve(Path root) {
        Path git = executable();
        if (git == null) return new Scope(null, "git is not on the path, so Git exclusions were not applied");
        Set<String> kept = new HashSet<>();
        String failure = list(git, root.toAbsolutePath().normalize(), "", kept, 0);
        if (failure != null) return new Scope(null, failure);
        // An ignored or empty workspace lists nothing. Indexing nothing would be wrong, so the walk stands.
        if (kept.isEmpty()) return new Scope(null, "Git lists no kept files for this workspace, so Git exclusions were not applied");
        return new Scope(kept, "Git exclusions were applied");
    }

    /** Only an absolute executable found on PATH is run, never a git placed in the workspace. */
    static Path executable() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path directory;
            try {
                // Windows accepts quoted PATH entries, which are stripped so the entry still resolves.
                directory = Path.of(entry.strip().replace("\"", ""));
            } catch (RuntimeException invalid) {
                continue;
            }
            if (!directory.isAbsolute()) continue;
            // Only the native executable is accepted. A .cmd shim would hand the path to cmd.exe for re-parsing.
            Path candidate = directory.resolve(IS_WINDOWS ? "git.exe" : "git");
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    /** Lists one repository. A nested repository that is not a submodule is listed in turn under its prefix. */
    private static String list(Path git, Path directory, String prefix, Set<String> kept, int depth) {
        Process process;
        try {
            var builder = new ProcessBuilder(git.toString(), "-C", directory.toString(),
                    "ls-files", "--cached", "--others", "--exclude-standard", "-z")
                    .redirectErrorStream(false);
            REPOSITORY_OVERRIDES.forEach(builder.environment()::remove);
            process = builder.start();
        } catch (IOException unavailable) {
            return "git could not be started, so Git exclusions were not applied";
        }
        try {
            // Output is read before waiting, so a large listing cannot fill the pipe and stall.
            var watchdog = new Thread(() -> {
                try {
                    if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "talos-git-ls-files-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            byte[] stdout = process.getInputStream().readAllBytes();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "git ls-files timed out, so Git exclusions were not applied";
            }
            if (process.exitValue() != 0) {
                return "git did not list this workspace (exit " + process.exitValue()
                        + (stderr.isEmpty() ? "" : ", " + SafeLogFormatter.text(stderr)) + "), so Git exclusions were not applied";
            }
            List<String> nested = new java.util.ArrayList<>();
            for (String entry : new String(stdout, StandardCharsets.UTF_8).split("\0")) {
                if (entry.isEmpty()) continue;
                // A nested repository has a .git directory, or a .git file for a worktree or a separate git dir.
                if (entry.endsWith("/") && Files.exists(directory.resolve(entry).resolve(".git"))) nested.add(entry);
                else kept.add(prefix + entry);
            }
            for (String entry : nested) {
                if (depth >= MAX_NESTED_DEPTH) return "Git repositories are nested too deeply, so Git exclusions were not applied";
                String failure = list(git, directory.resolve(entry), prefix + entry, kept, depth + 1);
                if (failure != null) return failure;
            }
            return null;
        } catch (IOException failed) {
            process.destroyForcibly();
            return "git ls-files could not be read, so Git exclusions were not applied";
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return "git ls-files was interrupted, so Git exclusions were not applied";
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            LOG.debug("Git exclusion scope resolved for {}", SafeLogFormatter.value(directory));
        }
    }

    private static String key(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        return IS_WINDOWS ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }
}
