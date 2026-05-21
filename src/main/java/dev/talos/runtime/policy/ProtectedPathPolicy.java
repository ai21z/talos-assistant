package dev.talos.runtime.policy;

import dev.talos.safety.ProtectedPathTokens;
import dev.talos.tools.ToolAliasPolicy;
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
        String kind = ProtectedPathTokens.protectedKind(lower);
        return new ResourceDecision(rawPath, relative, true, true, false, !kind.isBlank(), kind);
    }

    public static boolean looksLikeProtectedPathToken(String rawPath) {
        return ProtectedPathTokens.looksProtectedPathToken(rawPath);
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
}
