package dev.talos.tools;

import dev.talos.core.security.NoFollowPathPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Decides whether a requested target and a recorded tool path name the same
 * workspace file, whichever spelling each one used.
 *
 * <p>Both sides must lie lexically inside the workspace. Paths with the same
 * normalized shape are the same file. On Windows a case-only variant counts
 * only when both entries exist, no-follow inspection passes and both resolve
 * to the same stored name. No link is followed, no basename is compared and
 * the process folder is never used as a base. A missing or relative workspace
 * gives no identity. A spelling with a root but no absolute form, such as
 * {@code C:notes.txt}, is never proven to be the target, but it may match one
 * when the tools would resolve it there.
 */
public final class WorkspacePathIdentity {
    private WorkspacePathIdentity() {}

    public enum Identity {
        SAME,
        DISTINCT,
        UNPROVEN,
        REJECTED
    }

    /** True when both spellings are proven to name the same workspace file. */
    public static boolean matches(Path workspace, String requested, String recorded) {
        return identity(workspace, requested, recorded) == Identity.SAME;
    }

    /**
     * True when both spellings name the same workspace file and spell its
     * names below the workspace in the same case. A repair write must name its
     * target as requested, so a case variant does not satisfy it even where
     * the file system ignores case.
     */
    public static boolean matchesKeepingCase(Path workspace, String requested, String recorded) {
        if (!matches(workspace, requested, recorded)) return false;
        try {
            Path root = workspace.normalize();
            Path recordedPath = PathArgumentCanonicalizer
                    .canonicalizeExistingPathWhitespace(root, recorded)
                    .resolvedPath();
            Path requestedPath = root.resolve(requested.strip()).normalize();
            int below = root.getNameCount();
            if (recordedPath == null || recordedPath.getNameCount() != requestedPath.getNameCount()) {
                return false;
            }
            for (int index = below; index < requestedPath.getNameCount(); index++) {
                if (!recordedPath.getName(index).toString().equals(requestedPath.getName(index).toString())) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    /**
     * True when the spellings may name the same workspace file, including an
     * unproven case variant, a rooted spelling without an absolute form and a
     * path rejected by no-follow inspection. Used where a match must be
     * assumed, such as a forbidden target.
     */
    public static boolean mayMatch(Path workspace, String requested, String recorded) {
        Identity identity = identity(workspace, requested, recorded);
        return identity == Identity.SAME
                || identity == Identity.UNPROVEN
                || identity == Identity.REJECTED;
    }

    static Identity identity(Path workspace, String requested, String recorded) {
        if (workspace == null || !workspace.isAbsolute()
                || requested == null || requested.isBlank()
                || recorded == null || recorded.isBlank()) {
            return Identity.DISTINCT;
        }
        try {
            Path root = workspace.normalize();
            PathArgumentCanonicalizer.Resolution resolution =
                    PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(root, recorded);
            Path recordedPath = resolution.resolvedPath();
            String requestedSpelling = requested.strip();
            Path requestedPath = root.resolve(requestedSpelling).normalize();
            if (recordedPath == null
                    || !recordedPath.isAbsolute()
                    || !requestedPath.isAbsolute()
                    || !lexicallyWithin(root, recordedPath)
                    || !lexicallyWithin(root, requestedPath)) {
                return Identity.DISTINCT;
            }
            Identity identity = guardedIdentity(root, recordedPath, requestedPath);
            if (identity == Identity.DISTINCT) return Identity.DISTINCT;
            if (rootedWithoutAbsoluteForm(resolution.effectivePath())
                    || rootedWithoutAbsoluteForm(requestedSpelling)) {
                return Identity.UNPROVEN;
            }
            return identity == Identity.SAME
                    ? sameStoredName(recordedPath, requestedPath)
                    : identity;
        } catch (RuntimeException rejected) {
            return Identity.DISTINCT;
        }
    }

    /**
     * The guarded identity, with a case variant proven only by its stored
     * names. Used where a match grants something, such as an expected target
     * before approval. A forbidden check keeps {@link #guardedIdentity}.
     */
    public static Identity provenIdentity(Path root, Path first, Path second) {
        Identity identity = guardedIdentity(root, first, second);
        return identity == Identity.SAME
                ? sameStoredName(first.toAbsolutePath().normalize(), second.toAbsolutePath().normalize())
                : identity;
    }

    /**
     * {@link Files#isSameFile} treats two Windows spellings that differ only in
     * case as equal without asking the file system, so a case variant is the
     * same file only when both spellings resolve to the same stored names. In a
     * case-sensitive folder such names are two files. The root keeps the
     * caller's spelling of a share host and name, so it is compared ignoring
     * case.
     */
    private static Identity sameStoredName(Path first, Path second) {
        if (first.toString().equals(second.toString())) return Identity.SAME;
        try {
            Path firstStored = first.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path secondStored = second.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!sameRootIgnoringCase(firstStored, secondStored)
                    || firstStored.getNameCount() != secondStored.getNameCount()) {
                return Identity.DISTINCT;
            }
            for (int index = 0; index < firstStored.getNameCount(); index++) {
                if (!firstStored.getName(index).toString().equals(secondStored.getName(index).toString())) {
                    return Identity.DISTINCT;
                }
            }
            return Identity.SAME;
        } catch (IOException | RuntimeException unavailable) {
            return Identity.REJECTED;
        }
    }

    private static boolean rootedWithoutAbsoluteForm(String value) {
        Path lexical = Path.of(value);
        return lexical.getRoot() != null && !lexical.isAbsolute();
    }

    public static Identity guardedIdentity(Path root, Path first, Path second) {
        Path a = first.toAbsolutePath().normalize();
        Path b = second.toAbsolutePath().normalize();
        boolean sameLexicalShape = a.toString().equals(b.toString())
                || isWindowsPathPlatform()
                        && samePathShapeIgnoringCase(a, b);
        if (!sameLexicalShape) {
            return Identity.DISTINCT;
        }
        try {
            NoFollowPathPolicy.GuardedRoot guardedRoot =
                    NoFollowPathPolicy.bindExistingDirectory(root);
            NoFollowPathPolicy.EntryType firstType = guardedRoot.inspect(first);
            NoFollowPathPolicy.EntryType secondType = guardedRoot.inspect(second);
            if (a.toString().equals(b.toString())) return Identity.SAME;
            if (firstType == NoFollowPathPolicy.EntryType.MISSING
                    || secondType == NoFollowPathPolicy.EntryType.MISSING) {
                return Identity.UNPROVEN;
            }
            return Files.isSameFile(a, b)
                    ? Identity.SAME
                    : Identity.DISTINCT;
        } catch (IOException | RuntimeException identityFailure) {
            return Identity.REJECTED;
        }
    }

    /**
     * True where a backslash is a path separator, so a spelling whose
     * backslashes were rewritten to slashes still names the same path. On
     * POSIX a backslash can be part of a file name, and such a spelling must
     * not be given to identity.
     */
    public static boolean normalizedSpellingIsLossless() {
        return isWindowsPathPlatform();
    }

    public static boolean lexicallyWithin(Path root, Path candidate) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (normalizedCandidate.startsWith(normalizedRoot)) return true;
        if (!isWindowsPathPlatform()
                || normalizedCandidate.getNameCount() < normalizedRoot.getNameCount()
                || !sameRootIgnoringCase(normalizedCandidate, normalizedRoot)) {
            return false;
        }
        for (int index = 0; index < normalizedRoot.getNameCount(); index++) {
            if (!normalizedCandidate.getName(index).toString().equalsIgnoreCase(
                    normalizedRoot.getName(index).toString())) {
                return false;
            }
        }
        return true;
    }

    private static boolean samePathShapeIgnoringCase(Path first, Path second) {
        if (first.getNameCount() != second.getNameCount()
                || !sameRootIgnoringCase(first, second)) {
            return false;
        }
        for (int index = 0; index < first.getNameCount(); index++) {
            if (!first.getName(index).toString().equalsIgnoreCase(
                    second.getName(index).toString())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameRootIgnoringCase(Path first, Path second) {
        Path firstRoot = first.getRoot();
        Path secondRoot = second.getRoot();
        if (firstRoot == null || secondRoot == null) {
            return firstRoot == null && secondRoot == null;
        }
        return firstRoot.toString().equalsIgnoreCase(secondRoot.toString());
    }

    private static boolean isWindowsPathPlatform() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }
}
