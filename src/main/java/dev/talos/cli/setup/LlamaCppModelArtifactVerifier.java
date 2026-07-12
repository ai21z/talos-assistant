package dev.talos.cli.setup;

import dev.talos.engine.llamacpp.LlamaCppModelCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Verifies local bytes before writing a current qualification-backed profile. */
public final class LlamaCppModelArtifactVerifier {
    private LlamaCppModelArtifactVerifier() {}

    public static void requireMatch(Path modelPath, LlamaCppModelCatalog.Profile profile) throws Exception {
        requireMatch(modelPath, profile.alias(), profile.artifact());
    }

    static void requireMatch(
            Path modelPath,
            String alias,
            LlamaCppModelCatalog.ModelArtifact artifact) throws Exception {
        long actualSize = Files.size(modelPath);
        if (actualSize != artifact.sizeBytes()) {
            throw mismatch(alias, artifact, "size " + actualSize + " bytes, expected "
                    + artifact.sizeBytes() + " bytes");
        }
        String actualSha256 = LlamaCppModelDownloader.sha256Hex(modelPath).toLowerCase(Locale.ROOT);
        if (!actualSha256.equals(artifact.sha256())) {
            throw mismatch(alias, artifact, "SHA-256 " + actualSha256 + ", expected " + artifact.sha256());
        }
    }

    private static IllegalArgumentException mismatch(
            String alias,
            LlamaCppModelCatalog.ModelArtifact artifact,
            String detail) {
        return new IllegalArgumentException("Local GGUF does not match the pinned " + alias
                + " artifact: " + detail + ". Use " + artifact.fileName()
                + " from repository revision " + artifact.revision() + ".");
    }
}
