package dev.talos.runtime.policy;

import dev.talos.safety.ProtectedPathTokens;
import dev.talos.safety.ProtectedWorkspacePaths;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.runtime.workspace.WorkspaceBatchPlanParser;
import dev.talos.tools.ToolCall;

import java.nio.file.Path;
import java.util.List;

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
        ProtectedWorkspacePaths.Decision decision = ProtectedWorkspacePaths.classify(workspace, rawPath);
        return new ResourceDecision(
                decision.rawPath(),
                decision.relativePath(),
                decision.hasPath(),
                decision.insideWorkspace(),
                decision.workspaceEscape(),
                decision.protectedPath(),
                decision.protectedKind());
    }

    public static boolean looksLikeProtectedPathToken(String rawPath) {
        return ProtectedPathTokens.looksProtectedPathToken(rawPath);
    }
}
