package dev.talos.core.privacy;

import dev.talos.core.Config;
import dev.talos.core.context.ContextResult;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.safety.ProtectedWorkspacePaths;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Admission shared by implicit source reads and indexed context consumers. */
public final class ContextSourcePolicy {
    private ContextSourcePolicy() {}

    /** Implicit preload has no protected-read approval. Explicit tools retain that workflow. */
    public static boolean mayPreload(Path workspace, Path source) {
        var decision = ProtectedWorkspacePaths.classify(workspace, source.toAbsolutePath().toString());
        return decision.insideWorkspace() && !decision.workspaceEscape() && !decision.protectedPath();
    }

    public static ContextResult.Snippet admit(Config cfg, Path workspace, ContextResult.Snippet snippet) {
        String sourcePath = sourcePath(snippet);
        DocumentContentDecision decision;
        try {
            decision = sourcePath == null || sourcePath.isBlank()
                    ? deniedIdentity()
                    : sourceDecision(cfg, workspace, workspace.resolve(sourcePath).normalize());
        } catch (InvalidPathException e) {
            decision = deniedIdentity();
        }
        if (snippet.contentDecision() != null) {
            decision = decision == null ? snippet.contentDecision()
                    : DocumentContentDecision.forDerivedPair(snippet.contentDecision(), decision);
        }
        return new ContextResult.Snippet(snippet.path(), snippet.text(), snippet.metadata(), decision);
    }

    private static DocumentContentDecision sourceDecision(Config cfg, Path workspace, Path source) {
        DocumentContentDecision decision = null;
        if (!mayPreload(workspace, source)) {
            decision = new DocumentContentDecision(true, false, false, false, "protected source requires explicit read admission");
        } else {
            var format = FileCapabilityPolicy.describe(source, cfg).orElse(null);
            if (format != null && format.extractable()) {
                // Disabled extraction cannot turn retained document text into ordinary source text.
                decision = format.enabled()
                        ? PrivateDocumentContentPolicy.decide(cfg, DocumentExtractionRequest.read(source, workspace), format)
                        : new DocumentContentDecision(PrivacyConfigFacts.privateMode(cfg), false, false, false,
                                "document extraction is disabled");
            }
        }
        return decision;
    }

    private static String sourcePath(ContextResult.Snippet snippet) {
        String path = snippet.path().replace('\\', '/');
        var identity = snippet.metadata().sourceIdentity();
        if (identity == null) return basePath(path);
        String source = identity.path().replace('\\', '/');
        // Explicit provenance must describe this source or this source's serialized chunk.
        // A mismatch is not repaired by guessing which path was intended.
        return path.equals(source) || basePath(path).equals(source) ? source : null;
    }

    private static DocumentContentDecision deniedIdentity() {
        return new DocumentContentDecision(true, false, false, false,
                "source identity is missing, invalid or inconsistent");
    }

    /** Decode only the terminal nonnegative int suffix emitted by Chunker, preserving literal '#'. */
    public static String basePath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/');
        int chunk = normalized.lastIndexOf('#');
        if (chunk <= 0 || chunk == normalized.length() - 1) return normalized;
        String suffix = normalized.substring(chunk + 1);
        if (suffix.length() > 10 || suffix.length() > 1 && suffix.charAt(0) == '0') return normalized;
        for (int i = 0; i < suffix.length(); i++) {
            if (suffix.charAt(i) < '0' || suffix.charAt(i) > '9') return normalized;
        }
        try {
            Integer.parseInt(suffix);
            return normalized.substring(0, chunk);
        } catch (NumberFormatException e) {
            return normalized;
        }
    }
}
