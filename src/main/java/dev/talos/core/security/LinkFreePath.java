package dev.talos.core.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Round 8: resolves a path inside a workspace without following any link, so a model-supplied path never makes the
 * file system follow a link to a place outside the workspace, such as a network share.
 *
 * <p>Each component is read with {@code NOFOLLOW_LINKS} below a folder that is already link-free. A symbolic link is
 * replaced by its target text, read with {@code readSymbolicLink}, and the walk continues from the folder that holds
 * the link. A target that is absolute outside the workspace and its real path, a ".." that leaves the real path, a
 * reparse point that is not a symbolic link, such as an NTFS junction, and more than {@link #LINK_LIMIT} links end the
 * walk unresolved. A missing component ends the walk, and the names below it are appended as text, because nothing
 * below a missing name exists. On Windows ".." is applied to the text of a path and of a link target before any
 * lookup, as the file system applies it, and elsewhere it is applied to the link-free folder a component reaches.
 *
 * <p>Only the workspace itself is resolved through its links, once, because it is the operator's path. Hard links,
 * bind mounts and concurrent renames are outside this claim.
 */
public final class LinkFreePath {
    /** The most symbolic links one walk replaces. */
    public static final int LINK_LIMIT = 8;
    private static final boolean WINDOWS = java.io.File.separatorChar == '\\';

    private LinkFreePath() {}

    public enum Status {
        /** Every component was read without following it, and the path is inside the workspace's real path. */
        RESOLVED,
        /** The path, a link target or a ".." leaves the workspace, decided from text without following it. */
        OUTSIDE,
        /** A reparse point that is not a symbolic link, such as an NTFS junction, whose target cannot be read. */
        REPARSE_POINT,
        /** More than {@link #LINK_LIMIT} symbolic links were met. */
        LINK_LIMIT,
        /** A component could not be read. */
        UNREADABLE
    }

    /** The outcome of a walk. {@code path} is the link-free path when the status is RESOLVED, else null. */
    public record Walk(Status status, Path path) {
        public boolean resolved() {
            return status == Status.RESOLVED && path != null;
        }

        static Walk of(Status status) {
            return new Walk(status, null);
        }
    }

    /** The lexical and the real path of a workspace. Only the workspace itself is resolved through links. */
    public record Roots(Path lexical, Path real) {
        public static Roots of(Path workspace) {
            if (workspace == null) return new Roots(null, null);
            Path lexical;
            try {
                lexical = workspace.toAbsolutePath().normalize();
            } catch (RuntimeException unresolvable) {
                return new Roots(null, null);
            }
            Path real;
            try {
                real = lexical.toRealPath();
            } catch (IOException | RuntimeException unresolvable) {
                real = lexical;
            }
            return new Roots(lexical, real);
        }

        /** True when the candidate lies lexically inside the workspace or inside its real path. No probe is made. */
        public boolean admits(Path candidate) {
            Path normalized = absolute(candidate, this);
            if (normalized == null) return false;
            normalized = normalized.normalize();
            return normalized.startsWith(lexical) || real != null && normalized.startsWith(real);
        }
    }

    /** Resolves every component of the candidate, links included. */
    public static Walk resolve(Path candidate, Roots roots) {
        return walk(candidate, roots, false);
    }

    /**
     * Resolves the candidate's parent and keeps the candidate's own last name unfollowed, as an operation that acts on
     * the entry itself, such as a delete, a move or a rename, sees it.
     */
    public static Walk resolveEntry(Path candidate, Roots roots) {
        return walk(candidate, roots, true);
    }

    private static Walk walk(Path candidate, Roots roots, boolean keepLastName) {
        if (roots == null || roots.lexical() == null || roots.real() == null) return Walk.of(Status.UNREADABLE);
        Path absolute = absolute(candidate, roots);
        if (absolute == null) return Walk.of(Status.UNREADABLE);
        Path normalized = absolute.normalize();
        Path base = normalized.startsWith(roots.lexical()) ? roots.lexical()
                : normalized.startsWith(roots.real()) ? roots.real() : null;
        if (base == null) return Walk.of(Status.OUTSIDE);
        Path walked = WINDOWS || !absolute.startsWith(base) ? normalized : absolute;

        Deque<String> names = new ArrayDeque<>();
        for (Path name : base.relativize(walked)) {
            if (!name.toString().isEmpty()) names.addLast(name.toString());
        }
        String last = null;
        if (keepLastName && !names.isEmpty() && !".".equals(names.peekLast()) && !"..".equals(names.peekLast())) {
            last = names.pollLast();
        }

        Path current = roots.real();
        int links = 0;
        while (!names.isEmpty()) {
            String name = names.pollFirst();
            if (name.isEmpty() || ".".equals(name)) continue;
            if ("..".equals(name)) {
                if (current.equals(roots.real()) || current.getParent() == null) return Walk.of(Status.OUTSIDE);
                current = current.getParent();
                continue;
            }
            Path next;
            BasicFileAttributes attributes;
            try {
                next = current.resolve(name);
                attributes = Files.readAttributes(next, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                return missingTail(current.resolve(name), names, last, roots);
            } catch (IOException | RuntimeException unreadable) {
                return Walk.of(Status.UNREADABLE);
            }
            if (attributes.isSymbolicLink()) {
                if (++links > LINK_LIMIT) return Walk.of(Status.LINK_LIMIT);
                Path target;
                try {
                    target = Files.readSymbolicLink(next);
                } catch (IOException | RuntimeException unreadable) {
                    return Walk.of(Status.UNREADABLE);
                }
                List<String> replaced = new ArrayList<>();
                if (target.isAbsolute()) {
                    Path targetNormalized = target.normalize();
                    Path targetBase = targetNormalized.startsWith(roots.real()) ? roots.real()
                            : targetNormalized.startsWith(roots.lexical()) ? roots.lexical() : null;
                    if (targetBase == null) return Walk.of(Status.OUTSIDE);
                    Path targetWalked = WINDOWS || !target.startsWith(targetBase) ? targetNormalized : target;
                    for (Path part : targetBase.relativize(targetWalked)) replaced.add(part.toString());
                    current = roots.real();
                } else if (target.getRoot() != null) {
                    // A rooted target without a drive, or a drive-relative one, depends on process state.
                    return Walk.of(Status.OUTSIDE);
                } else {
                    for (Path part : WINDOWS ? target.normalize() : target) replaced.add(part.toString());
                }
                for (int index = replaced.size() - 1; index >= 0; index--) names.addFirst(replaced.get(index));
                continue;
            }
            if (attributes.isOther()) return Walk.of(Status.REPARSE_POINT);
            current = next;
            if (!attributes.isDirectory() && !names.isEmpty()) {
                // Nothing exists below a file, so the names below it are text, as below a missing name.
                String child = names.pollFirst();
                if ("..".equals(child)) return Walk.of(Status.UNREADABLE);
                try {
                    return missingTail(next.resolve(child), names, last, roots);
                } catch (RuntimeException unreadable) {
                    return Walk.of(Status.UNREADABLE);
                }
            }
        }
        Path resolved;
        try {
            resolved = last == null ? current : current.resolve(last);
        } catch (RuntimeException unreadable) {
            return Walk.of(Status.UNREADABLE);
        }
        return resolved.startsWith(roots.real()) ? new Walk(Status.RESOLVED, resolved) : Walk.of(Status.OUTSIDE);
    }

    private static Walk missingTail(Path missing, Deque<String> names, String last, Roots roots) {
        // A ".." below a missing name would make the text reach existing folders that were never read.
        if (names.contains("..")) return Walk.of(Status.UNREADABLE);
        try {
            Path tail = missing;
            for (String name : names) tail = tail.resolve(name);
            if (last != null) tail = tail.resolve(last);
            tail = tail.normalize();
            return tail.startsWith(roots.real()) ? new Walk(Status.RESOLVED, tail) : Walk.of(Status.OUTSIDE);
        } catch (RuntimeException unreadable) {
            return Walk.of(Status.UNREADABLE);
        }
    }

    private static Path absolute(Path candidate, Roots roots) {
        if (candidate == null || roots == null || roots.lexical() == null) return null;
        try {
            return candidate.isAbsolute() ? candidate : roots.lexical().resolve(candidate);
        } catch (RuntimeException unresolvable) {
            return null;
        }
    }
}
