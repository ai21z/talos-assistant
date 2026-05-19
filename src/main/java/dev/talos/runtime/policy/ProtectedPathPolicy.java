package dev.talos.runtime.policy;

import dev.talos.runtime.toolcall.ToolAliasPolicy;
import dev.talos.runtime.workspace.WorkspaceBatchPlanParser;
import dev.talos.tools.PathArgumentCanonicalizer;
import dev.talos.tools.ToolCall;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Classifies workspace paths that need stricter local permission behavior. */
public final class ProtectedPathPolicy {
    private ProtectedPathPolicy() {}

    private static final List<String> PATH_KEYS =
            List.of(
                    "path", "file_path", "filepath", "file", "filename",
                    "from", "to", "source", "source_path", "src",
                    "destination", "destination_path", "dest", "target",
                    "dir", "directory");

    private static final List<String> PRIVATE_KEY_FILENAMES =
            List.of("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519");

    private static final List<String> PRIVATE_KEY_EXTENSIONS =
            List.of(".pem", ".key", ".p12", ".pfx");

    public static ResourceDecision classify(Path workspace, ToolCall call) {
        List<ResourceDecision> decisions = classifyAll(workspace, call);
        return decisions.isEmpty() ? ResourceDecision.noPath() : decisions.get(0);
    }

    public static List<ResourceDecision> classifyAll(Path workspace, ToolCall call) {
        if (call == null) return List.of(ResourceDecision.noPath());
        var decisions = new java.util.ArrayList<ResourceDecision>();
        if ("apply_workspace_batch".equals(ToolAliasPolicy.localCanonicalName(call.toolName()))) {
            for (String value : WorkspaceBatchPlanParser.pathValues(call)) {
                if (value != null && !value.isBlank()) {
                    decisions.add(classify(workspace, value));
                }
            }
        }
        for (String key : PATH_KEYS) {
            String value = call.param(key);
            if (value != null && !value.isBlank()) {
                decisions.add(classify(workspace, value));
            }
        }
        return decisions.isEmpty() ? List.of(ResourceDecision.noPath()) : List.copyOf(decisions);
    }

    public static ResourceDecision classify(Path workspace, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return ResourceDecision.noPath();
        }
        if (workspace == null) {
            return new ResourceDecision(rawPath, "", true, false, true, false, "");
        }

        Path ws;
        Path resolved;
        PathArgumentCanonicalizer.Resolution canonical =
                PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(workspace, rawPath);
        String effectivePath = canonical.effectivePath();
        try {
            ws = workspace.toAbsolutePath().normalize();
            Path candidate = Path.of(effectivePath);
            resolved = (candidate.isAbsolute() ? candidate : ws.resolve(candidate)).normalize();
        } catch (Exception e) {
            return new ResourceDecision(rawPath, "", true, false, true, false, "");
        }

        if (!startsWithWorkspace(resolved, ws)) {
            return new ResourceDecision(rawPath, "", true, false, true, false, "");
        }

        String relative = normalizeRelative(ws.relativize(resolved));
        String lower = relative.toLowerCase(Locale.ROOT);
        String kind = protectedKind(lower);
        return new ResourceDecision(rawPath, relative, true, true, false, !kind.isBlank(), kind);
    }

    public static boolean looksLikeProtectedPathToken(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return false;
        String normalized = stripWrappingQuotes(rawPath.strip())
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return !protectedKind(normalized).isBlank();
    }

    private static boolean startsWithWorkspace(Path resolved, Path workspace) {
        if (resolved.startsWith(workspace)) return true;
        String r = normalizeAbsolute(resolved);
        String w = normalizeAbsolute(workspace);
        return isWindows() && (r.equals(w) || r.startsWith(w.endsWith("/") ? w : w + "/"));
    }

    private static String normalizeAbsolute(Path path) {
        return path.toAbsolutePath().normalize().toString()
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizeRelative(Path relative) {
        String s = relative.toString().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        return s;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String stripWrappingQuotes(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '`' && last == '`')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String protectedKind(String lowerRelative) {
        if (lowerRelative.isBlank()) return "";
        List<String> segments = List.of(lowerRelative.split("/+"));

        if (segments.contains(".git") || segments.contains(".gnupg")) return "CONTROL";
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (".github".equals(segments.get(i)) && "workflows".equals(segments.get(i + 1))) {
                return "CONTROL";
            }
        }

        for (String segment : segments) {
            if (segment.equals(".env") || segment.startsWith(".env.")) return "SECRET";
            if (segment.endsWith(".env")) return "SECRET";
            if (segment.equals("secrets") || segment.equals("tokens") || segment.equals("credentials")) return "SECRET";
            if (segment.equals("protected")) return "SECRET";
            if (segment.equals(".ssh") || segment.equals(".aws") || segment.equals(".azure")) return "SECRET";
            if (PRIVATE_KEY_FILENAMES.contains(segment)) return "SECRET";
            if (segment.contains("secret")
                    || segment.contains("token")
                    || segment.contains("credential")
                    || segment.contains("password")
                    || segment.contains("private_key")
                    || segment.contains("private-key")) {
                return "SECRET";
            }
        }
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (".config".equals(segments.get(i)) && "gcloud".equals(segments.get(i + 1))) {
                return "SECRET";
            }
        }

        String filename = segments.isEmpty() ? lowerRelative : segments.get(segments.size() - 1);
        if (filename.contains("secret")
                || filename.contains("token")
                || filename.contains("credential")
                || filename.contains("password")
                || filename.contains("private_key")
                || filename.contains("private-key")) {
            return "SECRET";
        }
        for (String ext : PRIVATE_KEY_EXTENSIONS) {
            if (filename.endsWith(ext)) return "SECRET";
        }
        return "";
    }
}
