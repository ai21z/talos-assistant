package dev.talos.runtime.toolcall;

import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.core.privacy.PrivacyConfigFacts;
import dev.talos.core.privacy.PrivateDocumentContentPolicy;
import dev.talos.runtime.RuntimeTurnContext;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Blocks private/sensitive document over-reads outside the current named target set. */
final class PrivateDocumentNamedTargetGuard {
    private PrivateDocumentNamedTargetGuard() {}

    static String diagnostic(
            ToolCall call,
            RuntimeTurnContext ctx,
            Path workspace,
            TaskContract contract,
            String pathHint
    ) {
        if (call == null || ctx == null || contract == null || workspace == null) return null;
        if (!"read_file".equals(ToolAliasPolicy.localCanonicalName(call.toolName()))) return null;
        if (pathHint == null || pathHint.isBlank()) return null;
        if (contract.expectedTargets().isEmpty()) return null;

        Path requestedPath = resolve(workspace, pathHint);
        FileCapabilityPolicy.FormatInfo info =
                FileCapabilityPolicy.describe(requestedPath, ctx.cfg()).orElse(null);
        if (!PrivateDocumentContentPolicy.isExtractedDocument(info)) return null;

        Set<String> allowedTargets = normalizedTargets(contract);
        if (allowedTargets.isEmpty()) return null;
        String requested = normalizeRequested(workspace, requestedPath, pathHint);
        if (allowedTargets.contains(requested)) return null;
        if (!PrivacyConfigFacts.privateMode(ctx.cfg())
                && !looksPrivateOrSensitiveDocumentPath(workspace, requestedPath, requested)) {
            return null;
        }

        return ProtectedContentPolicy.sanitizeText(
                "Blocked private document read for `" + requested
                        + "` because it is outside the current requested private document target set: "
                        + renderAllowedTargets(allowedTargets)
                        + ". Ask for this document explicitly in the current turn before reading it.");
    }

    private static Set<String> normalizedTargets(TaskContract contract) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        addTargets(out, contract.expectedTargets());
        addTargets(out, contract.sourceEvidenceTargets());
        return out;
    }

    private static void addTargets(Set<String> out, Set<String> targets) {
        if (targets == null) return;
        for (String target : targets) {
            String normalized = normalizeTarget(target);
            if (!normalized.isBlank()) out.add(normalized);
        }
    }

    private static String renderAllowedTargets(Set<String> allowedTargets) {
        if (allowedTargets == null || allowedTargets.isEmpty()) return "(none)";
        return "`" + String.join("`, `", allowedTargets) + "`";
    }

    private static Path resolve(Path workspace, String pathHint) {
        try {
            Path candidate = Path.of(pathHint);
            if (candidate.isAbsolute()) return candidate.normalize();
            return workspace.resolve(candidate).normalize();
        } catch (RuntimeException e) {
            return workspace.resolve(pathHint == null ? "" : pathHint).normalize();
        }
    }

    private static String normalizeRequested(Path workspace, Path requestedPath, String rawPath) {
        try {
            Path ws = workspace.toAbsolutePath().normalize();
            Path requested = requestedPath.toAbsolutePath().normalize();
            if (requested.startsWith(ws)) {
                return normalizeTarget(ws.relativize(requested).toString());
            }
        } catch (RuntimeException ignored) {
            // Fall through to raw path normalization.
        }
        return normalizeTarget(rawPath);
    }

    private static boolean looksPrivateOrSensitiveDocumentPath(Path workspace, Path requestedPath, String requested) {
        if (ProtectedContentPolicy.isProtectedPath(workspace, requestedPath)) return true;
        if (ProtectedContentPolicy.looksProtectedPathString(requested)) return true;
        String lower = normalizeTarget(requested).toLowerCase(Locale.ROOT);
        String filename = lower;
        int slash = filename.lastIndexOf('/');
        if (slash >= 0) {
            filename = filename.substring(slash + 1);
        }
        return filename.contains("private")
                || filename.contains("patient")
                || filename.contains("medical")
                || filename.contains("clinic")
                || filename.contains("health")
                || filename.contains("personal")
                || filename.contains("family")
                || filename.contains("tax")
                || filename.contains("legal");
    }

    private static String normalizeTarget(String path) {
        if (path == null) return "";
        String value = path.replace('\\', '/').strip();
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        return value;
    }
}
