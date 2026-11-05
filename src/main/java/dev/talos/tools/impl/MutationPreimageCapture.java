package dev.talos.tools.impl;

import dev.talos.core.io.AtomicFileWriter;
import dev.talos.core.io.StrictUtf8;
import dev.talos.core.util.Hash;
import dev.talos.tools.MutationPreimage;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Builds the producer preimage of a file mutation. The content is kept only for a safe, unprotected target within
 * {@link MutationPreimage#MAX_BYTES}, as strict UTF-8, and only when its bytes hash to the compare-and-replace token.
 * Capture is optional evidence, so a failure gives none and never changes the mutation outcome.
 */
final class MutationPreimageCapture {
    private MutationPreimageCapture() {}

    /** The prior content of an existing target whose exact bytes the producer already holds. */
    static MutationPreimage existing(Path workspace, String effectPath, byte[] bytes, String sha256) {
        try {
            if (workspace == null || effectPath == null || bytes == null || sha256 == null
                    || bytes.length > MutationPreimage.MAX_BYTES) {
                return MutationPreimage.none();
            }
            String target = effectPath.replace('\\', '/');
            if (!ExecutedEditDetail.safeTarget(workspace, target)
                    || !Hash.sha256Hex(bytes).equalsIgnoreCase(sha256)) {
                return MutationPreimage.none();
            }
            return MutationPreimage.existing(target, sha256, StrictUtf8.decode(bytes));
        } catch (Exception | StackOverflowError unavailable) {
            return MutationPreimage.none();
        }
    }

    /**
     * The prior content of the target that a full write is about to replace. The bytes are read once, before the
     * replace, without following a link and never beyond the bound, and they are kept only when they hash to the token
     * captured for the compare-and-replace. A target that did not exist gives an absent preimage. The write path itself
     * is not changed.
     */
    static MutationPreimage beforeWrite(
            Path workspace,
            String effectPath,
            Path resolved,
            AtomicFileWriter.Preimage expected
    ) {
        try {
            if (workspace == null || effectPath == null || resolved == null || expected == null) {
                return MutationPreimage.none();
            }
            String target = effectPath.replace('\\', '/');
            if (!ExecutedEditDetail.safeTarget(workspace, target)) return MutationPreimage.none();
            if (!expected.existed()) return MutationPreimage.absent(target);
            BasicFileAttributes attributes = Files.readAttributes(
                    resolved, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MutationPreimage.MAX_BYTES) {
                return MutationPreimage.none();
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(resolved, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(MutationPreimage.MAX_BYTES + 1);
            }
            return existing(workspace, target, bytes, expected.sha256());
        } catch (Exception | StackOverflowError unavailable) {
            return MutationPreimage.none();
        }
    }
}
