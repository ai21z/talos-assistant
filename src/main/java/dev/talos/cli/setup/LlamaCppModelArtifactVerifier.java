package dev.talos.cli.setup;

import dev.talos.engine.llamacpp.LlamaCppModelCatalog;
import dev.talos.engine.llamacpp.profile.LlamaCppArtifactVerificationStore;
import dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver;

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

    public static LlamaCppProfileResolver.ArtifactIdentity requireMatchAndRecord(
            Path modelPath,
            LlamaCppModelCatalog.Profile profile,
            Path talosHome) throws Exception {
        return requireMatchAndRecord(modelPath, profile.alias(), profile.artifact(), talosHome);
    }

    static LlamaCppProfileResolver.ArtifactIdentity requireMatchAndRecord(
            Path modelPath,
            String alias,
            LlamaCppModelCatalog.ModelArtifact artifact,
            Path talosHome) throws Exception {
        LlamaCppProfileResolver.ArtifactIdentity identity = new LlamaCppArtifactVerificationStore()
                .verify(modelPath, alias, artifact, talosHome);
        if (identity.state() == LlamaCppProfileResolver.ArtifactState.EXACT) {
            return identity;
        }
        String detail = switch (identity.state()) {
            case SIZE_MISMATCH -> "size " + identity.actualSize() + " bytes, expected "
                    + identity.expectedSize() + " bytes";
            case SHA256_MISMATCH -> "SHA-256 " + identity.actualSha256() + ", expected "
                    + identity.expectedSha256();
            case MISSING -> "model file is missing";
            default -> "artifact verification did not produce exact evidence (" + identity.state() + ")";
        };
        throw mismatch(alias, artifact, detail);
    }

    private static IllegalArgumentException mismatch(
            String alias,
            LlamaCppModelCatalog.ModelArtifact artifact,
            String detail) {
        return new IllegalArgumentException("Local GGUF does not match the pinned " + alias
                + " artifact: " + detail + ". Use the exact artifact described by profile "
                + alias + ": " + artifact.fileName() + ", source " + artifact.repository()
                + "@" + artifact.revision() + ".");
    }
}
