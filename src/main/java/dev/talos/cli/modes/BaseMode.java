package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base class providing common utilities for mode implementations.
 */
abstract class BaseMode {
    protected static final Pattern FILE_TOKEN = Pattern.compile(
            "([A-Za-z0-9_./\\\\-]+\\.(?:java|md|txt|yaml|yml|xml|gradle|kts|json|properties|html|htm))\\b",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    protected static final Pattern FIRST_PATH_PATTERN = Pattern.compile(
            "^[^\\s:]++\\s++(?:\"([^\"]++)\"|'([^']++)'|`([^`++]++)`|(\\S++))",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    /**
     * Checks if the query line indicates an intent to open/show/view a file.
     */
    protected static boolean isOpenIntent(String lower) {
        return lower.startsWith("open ") || lower.startsWith("show ") || lower.startsWith("view ")
                || lower.contains("can you open") || lower.contains("can you show") || lower.contains("open?");
    }

    /**
     * Checks if the query line indicates an intent to list directory contents.
     */
    protected static boolean isListIntent(String lower) {
        return lower.startsWith("ls ") || lower.startsWith("list ") || lower.startsWith("dir ")
                || lower.startsWith("what is inside ") || lower.contains("what is inside")
                || lower.startsWith("what's inside ");
    }

    /**
     * Securely resolves a candidate path against the workspace boundary.
     */
    protected static Path secureResolve(Path workspace, Path candidate) {
        if (candidate == null) return null;
        Path base = toRealOrNorm(workspace);
        Path cand = toRealOrNorm(candidate.isAbsolute() ? candidate : base.resolve(candidate));
        return cand;
    }

    /**
     * Converts a path to its real path or normalized absolute path if real path resolution fails.
     */
    protected static Path toRealOrNorm(Path p) {
        try { return p.toAbsolutePath().normalize().toRealPath(); }
        catch (Exception e) { return p.toAbsolutePath().normalize(); }
    }

    /**
     * Checks if candidate path is under the base path.
     */
    protected static boolean under(Path base, Path cand) {
        Path b = toRealOrNorm(base);
        Path c = toRealOrNorm(cand);
        return c.startsWith(b);
    }

    /**
     * Relativizes a path against the base and normalizes separators to forward slashes.
     */
    protected static String relativize(Path base, Path p) {
        try { return base.relativize(p).toString().replace('\\','/'); }
        catch (Exception e) { return p.getFileName().toString(); }
    }

    /**
     * Expands tilde (~) to user home directory in path strings.
     */
    protected static String expandTilde(String raw) {
        if (raw == null) return null;
        if (raw.equals("~")) return userHome();
        if (raw.startsWith("~" + java.io.File.separator) || raw.startsWith("~/")) {
            return userHome() + raw.substring(1);
        }
        return raw;
    }

    /**
     * Returns the user home directory path.
     */
    protected static String userHome() {
        String home = System.getProperty("user.home");
        return (home == null || home.isBlank()) ? System.getProperty("user.dir", ".") : home;
    }

    /**
     * Best-effort resolution of the first path-like argument in a line, matching RunCmd semantics.
     */
    protected static Path resolveFirstPathToken(Path ws, String line, int maxDepth) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty()) return null;

        Matcher m = FIRST_PATH_PATTERN.matcher(s);
        if (m.find()) {
            String raw = m.group(1);
            if (raw == null) raw = m.group(2);
            if (raw == null) raw = m.group(3);
            if (raw == null) raw = m.group(4);
            if (raw != null && !raw.isBlank()) {
                String exp = expandTilde(raw);
                Path cand;
                try { cand = Path.of(exp); } catch (Exception bad) { return null; }
                cand = secureResolve(ws, cand);
                return cand;
            }
        }

        Matcher f = FILE_TOKEN.matcher(line);
        if (f.find()) {
            String token = f.group(1);
            Path cand = secureResolve(ws, Path.of(token));
            if (Files.exists(cand)) return cand;
            String base = Path.of(token).getFileName().toString();
            try (var walk = Files.walk(ws, maxDepth)) {
                return walk.filter(Files::isRegularFile)
                        .filter(fp -> fp.getFileName().toString().equalsIgnoreCase(base))
                        .findFirst().orElse(null);
            } catch (Exception ignore) { /* fallthrough */ }
        }
        return null;
    }

    /**
     * Sandbox gate: validates path is within workspace and passes allow/deny rules.
     */
    protected static boolean allowed(Context ctx, Path p) {
        if (ctx == null || ctx.sandbox() == null) return true;
        return ctx.sandbox().allowedPath(p);
    }
}
