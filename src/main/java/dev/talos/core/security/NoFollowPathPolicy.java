package dev.talos.core.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Reject-symbolic-and-reparse-link path authority for trust-bearing filesystem consumers.
 *
 * <p>This policy authenticates every existing lexical component with
 * {@link LinkOption#NOFOLLOW_LINKS}. Symbolic links, Windows reparse aliases
 * exposed as {@link BasicFileAttributes#isOther()}, and other non-ordinary
 * entries are rejected before content I/O. It proves stable-link containment;
 * it does not claim protection against hard links, bind mounts, or every
 * hostile concurrent rename.
 */
public final class NoFollowPathPolicy {
    private NoFollowPathPolicy() {}

    public enum Reason {
        OUTSIDE_ROOT,
        LINKED_COMPONENT,
        NON_ORDINARY_COMPONENT,
        MISSING,
        NOT_DIRECTORY,
        NOT_REGULAR_FILE,
        UNSAFE_CREATE_TARGET,
        CHANGED_DURING_ACCESS
    }

    public enum EntryType {
        DIRECTORY,
        REGULAR_FILE,
        MISSING
    }

    /** A path-free, typed failure suitable for user-visible safe rendering. */
    public static final class Violation extends IOException {
        private final Reason reason;

        private Violation(Reason reason) {
            super("No-follow path policy rejected the operation: " + reason.name());
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        private Violation(Reason reason, Throwable cause) {
            super("No-follow path policy rejected the operation: " + reason.name(), cause);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public Reason reason() {
            return reason;
        }
    }

    public record TreeEntry(Path path, EntryType type) {
        public TreeEntry {
            path = Objects.requireNonNull(path, "path");
            if (type == EntryType.MISSING) {
                throw new IllegalArgumentException("tree entries must exist");
            }
        }
    }

    public record TreeFile(Path path, byte[] bytes) {
        public TreeFile {
            path = Objects.requireNonNull(path, "path");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /**
     * Bound authority for one ordinary, existing directory. Accessors reject
     * symbolic-link components and entries reported as non-ordinary; hard
     * links and bind mounts are outside this policy's claim.
     */
    public static final class GuardedRoot {
        private final Path lexicalRoot;
        private final Path realRoot;

        private GuardedRoot(Path lexicalRoot, Path realRoot) {
            this.lexicalRoot = lexicalRoot;
            this.realRoot = realRoot;
        }

        public Path path() {
            return lexicalRoot;
        }

        public EntryType inspect(Path requested) throws IOException {
            Scan scan = scanDescendant(requested, true);
            if (!scan.exists()) return EntryType.MISSING;
            return typeOf(scan.attributes());
        }

        public Path requireDirectory(Path requested) throws IOException {
            Scan scan = scanDescendant(requested, false);
            if (!scan.attributes().isDirectory()) {
                throw violation(Reason.NOT_DIRECTORY);
            }
            verifyRealContainment(scan.path());
            return scan.path();
        }

        public Path requireRegularFile(Path requested) throws IOException {
            Scan scan = scanDescendant(requested, false);
            if (!scan.attributes().isRegularFile()) {
                throw violation(Reason.NOT_REGULAR_FILE);
            }
            verifyRealContainment(scan.path());
            return scan.path();
        }

        /**
         * Validates an output path whose final component may be absent or an
         * ordinary regular file. Existing directories and special entries fail.
         */
        public Path requireRegularFileTarget(Path requested) throws IOException {
            Scan scan = scanDescendant(requested, true);
            if (scan.exists() && !scan.attributes().isRegularFile()) {
                throw violation(Reason.UNSAFE_CREATE_TARGET);
            }
            verifyNearestRealContainment(scan);
            return scan.path();
        }

        /**
         * Creates missing descendant directories only after every existing
         * ancestor has passed the no-follow policy, then authenticates the
         * resulting directory again.
         */
        public Path createDirectories(Path requested) throws IOException {
            Path directory = normalizedDescendant(requested);
            Scan before = scanAbsolute(directory, true);
            verifyNearestRealContainment(before);
            if (before.exists() && !before.attributes().isDirectory()) {
                throw violation(Reason.UNSAFE_CREATE_TARGET);
            }
            Files.createDirectories(directory);
            return requireDirectory(directory);
        }

        /** Deterministic, no-follow snapshot of one directory's immediate entries. */
        public List<TreeEntry> list(Path requested) throws IOException {
            Path directory = requireDirectory(requested);
            return listPrevalidatedDirectory(directory);
        }

        private List<TreeEntry> listPrevalidatedDirectory(Path requested)
                throws IOException {
            Path directory = normalizedDescendant(requested);
            BasicFileAttributes directoryAttributes = attributes(directory);
            rejectLinkedOrOther(directoryAttributes);
            if (!directoryAttributes.isDirectory()) {
                throw violation(Reason.NOT_DIRECTORY);
            }
            List<TreeEntry> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    entries.add(inspectImmediateChild(directory, child));
                }
            }
            entries.sort(Comparator.comparing(entry ->
                    lexicalRoot.relativize(entry.path()).toString().replace('\\', '/')));
            return List.copyOf(entries);
        }

        /** Deterministic, fully prevalidated tree snapshot; links fail the walk. */
        public List<TreeEntry> walk(Path requested) throws IOException {
            Path start = normalizedDescendant(requested);
            Scan scan = scanDescendant(start, false);
            EntryType startType = typeOf(scan.attributes());
            if (startType == EntryType.REGULAR_FILE) {
                verifyRealContainment(start);
                return List.of(new TreeEntry(start, EntryType.REGULAR_FILE));
            }
            if (startType != EntryType.DIRECTORY) {
                throw violation(Reason.NON_ORDINARY_COMPONENT);
            }

            List<TreeEntry> entries = new ArrayList<>();
            List<Path> pending = new ArrayList<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                Path directory = pending.removeFirst();
                entries.add(new TreeEntry(directory, EntryType.DIRECTORY));
                for (TreeEntry child : listPrevalidatedDirectory(directory)) {
                    if (child.type() == EntryType.DIRECTORY) {
                        pending.add(child.path());
                    } else if (child.type() == EntryType.REGULAR_FILE) {
                        entries.add(child);
                    } else {
                        throw violation(Reason.NON_ORDINARY_COMPONENT);
                    }
                }
            }
            entries.sort(Comparator.comparing(entry ->
                    lexicalRoot.relativize(entry.path()).toString().replace('\\', '/')));
            return List.copyOf(entries);
        }

        /**
         * Reads a deterministic snapshot of selected ordinary files while each
         * immediate child is still under its authenticated parent directory.
         */
        public List<TreeFile> readRegularFiles(
                Path requested,
                Predicate<Path> include) throws IOException {
            Objects.requireNonNull(include, "file predicate");
            Path start = normalizedDescendant(requested);
            Scan scan = scanDescendant(start, false);
            EntryType startType = typeOf(scan.attributes());
            if (startType == EntryType.REGULAR_FILE) {
                if (!include.test(start)) return List.of();
                return List.of(new TreeFile(start, readAllBytes(start)));
            }
            if (startType != EntryType.DIRECTORY) {
                throw violation(Reason.NON_ORDINARY_COMPONENT);
            }

            List<TreeFile> files = new ArrayList<>();
            List<Path> pending = new ArrayList<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                Path directory = pending.removeFirst();
                for (TreeEntry entry : listPrevalidatedDirectory(directory)) {
                    if (entry.type() == EntryType.DIRECTORY) {
                        pending.add(entry.path());
                    } else if (entry.type() == EntryType.REGULAR_FILE
                            && include.test(entry.path())) {
                        files.add(new TreeFile(
                                entry.path(),
                                readPrevalidatedRegularFile(entry.path())));
                    }
                }
            }
            files.sort(Comparator.comparing(file ->
                    lexicalRoot.relativize(file.path()).toString().replace('\\', '/')));
            return List.copyOf(files);
        }

        public List<TreeFile> readRegularFiles(Path requested) throws IOException {
            return readRegularFiles(requested, ignored -> true);
        }

        public InputStream openRegularFile(Path requested) throws IOException {
            Path file = requireRegularFile(requested);
            try {
                return Files.newInputStream(
                        file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException openedFailure) {
                try {
                    requireRegularFile(file);
                } catch (IOException changed) {
                    throw new Violation(Reason.CHANGED_DURING_ACCESS, changed);
                }
                throw openedFailure;
            }
        }

        public byte[] readAllBytes(Path requested) throws IOException {
            try (InputStream input = openRegularFile(requested)) {
                return input.readAllBytes();
            }
        }

        public String readString(Path requested, Charset charset) throws IOException {
            Objects.requireNonNull(charset, "charset");
            return decodeStrict(readAllBytes(requested), charset);
        }

        private Scan scanDescendant(Path requested, boolean allowMissing) throws IOException {
            Path path = normalizedDescendant(requested);
            Scan scan = scanAbsolute(path, allowMissing);
            verifyNearestRealContainment(scan);
            return scan;
        }

        private Path normalizedDescendant(Path requested) throws Violation {
            Path path = normalizeAbsolute(requested);
            if (!path.startsWith(lexicalRoot)) {
                throw violation(Reason.OUTSIDE_ROOT);
            }
            return path;
        }

        private TreeEntry inspectImmediateChild(Path directory, Path requested)
                throws IOException {
            Path child = normalizedDescendant(requested);
            if (!Objects.equals(child.getParent(), directory)) {
                throw violation(Reason.OUTSIDE_ROOT);
            }
            BasicFileAttributes childAttributes = attributes(child);
            rejectLinkedOrOther(childAttributes);
            EntryType type = typeOf(childAttributes);
            // The parent directory was authenticated immediately before this
            // listing. A child that is neither a symbolic link nor a
            // detectable reparse entry cannot redirect this stable access
            // outside that parent. Directory children are authenticated again
            // before their own listing.
            return new TreeEntry(child, type);
        }

        private byte[] readPrevalidatedRegularFile(Path file) throws IOException {
            try (InputStream input = Files.newInputStream(
                    file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                return input.readAllBytes();
            } catch (IOException openedFailure) {
                try {
                    BasicFileAttributes changed = attributes(file);
                    rejectLinkedOrOther(changed);
                    if (!changed.isRegularFile()) {
                        throw violation(Reason.CHANGED_DURING_ACCESS);
                    }
                } catch (IOException changed) {
                    throw new Violation(Reason.CHANGED_DURING_ACCESS, changed);
                }
                throw openedFailure;
            }
        }

        private void verifyNearestRealContainment(Scan scan) throws IOException {
            Path existing = scan.exists() ? scan.path() : scan.nearestExisting();
            if (existing == null) throw violation(Reason.MISSING);
            verifyRealContainment(existing);
        }

        private void verifyRealContainment(Path existing) throws IOException {
            Path real = existing.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw violation(Reason.OUTSIDE_ROOT);
            }
        }
    }

    public static GuardedRoot bindExistingDirectory(Path requestedRoot) throws IOException {
        Path root = normalizeAbsolute(requestedRoot);
        Scan scan = scanAbsolute(root, false);
        if (!scan.attributes().isDirectory()) {
            throw violation(Reason.NOT_DIRECTORY);
        }
        return new GuardedRoot(root, root.toRealPath());
    }

    public static Path requireUnlinkedRegularFile(Path requested) throws IOException {
        Path path = normalizeAbsolute(requested);
        Scan scan = scanAbsolute(path, false);
        if (!scan.attributes().isRegularFile()) {
            throw violation(Reason.NOT_REGULAR_FILE);
        }
        return path;
    }

    public static Path requireUnlinkedDirectory(Path requested) throws IOException {
        Path path = normalizeAbsolute(requested);
        Scan scan = scanAbsolute(path, false);
        if (!scan.attributes().isDirectory()) {
            throw violation(Reason.NOT_DIRECTORY);
        }
        return path;
    }

    /**
     * Creates an absolute directory tree only after all existing ancestors have
     * passed the no-follow policy, then authenticates the resulting directory.
     */
    public static Path createUnlinkedDirectories(Path requested) throws IOException {
        Path directory = normalizeAbsolute(requested);
        Scan before = scanAbsolute(directory, true);
        if (before.exists() && !before.attributes().isDirectory()) {
            throw violation(Reason.UNSAFE_CREATE_TARGET);
        }
        Files.createDirectories(directory);
        return requireUnlinkedDirectory(directory);
    }

    public static InputStream openUnlinkedRegularFile(Path requested) throws IOException {
        Path file = requireUnlinkedRegularFile(requested);
        try {
            return Files.newInputStream(
                    file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException openedFailure) {
            try {
                requireUnlinkedRegularFile(file);
            } catch (IOException changed) {
                throw new Violation(Reason.CHANGED_DURING_ACCESS, changed);
            }
            throw openedFailure;
        }
    }

    public static byte[] readAllBytes(Path requested) throws IOException {
        try (InputStream input = openUnlinkedRegularFile(requested)) {
            return input.readAllBytes();
        }
    }

    public static String readString(Path requested, Charset charset) throws IOException {
        Objects.requireNonNull(charset, "charset");
        return decodeStrict(readAllBytes(requested), charset);
    }

    private static Scan scanAbsolute(Path requested, boolean allowMissing) throws IOException {
        Path path = normalizeAbsolute(requested);
        Path current = path.getRoot();
        if (current == null) throw violation(Reason.OUTSIDE_ROOT);

        BasicFileAttributes rootAttributes = attributes(current);
        rejectLinkedOrOther(rootAttributes);
        Path nearest = current;
        BasicFileAttributes finalAttributes = rootAttributes;

        for (int index = 0; index < path.getNameCount(); index++) {
            current = current.resolve(path.getName(index));
            BasicFileAttributes attributes;
            try {
                attributes = attributes(current);
            } catch (NoSuchFileException missing) {
                if (!allowMissing) throw violation(Reason.MISSING);
                return new Scan(path, false, null, nearest);
            }
            rejectLinkedOrOther(attributes);
            boolean finalComponent = index == path.getNameCount() - 1;
            if (!finalComponent && !attributes.isDirectory()) {
                throw violation(Reason.NOT_DIRECTORY);
            }
            nearest = current;
            finalAttributes = attributes;
        }
        return new Scan(path, true, finalAttributes, nearest);
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static String decodeStrict(byte[] bytes, Charset charset) throws IOException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static void rejectLinkedOrOther(BasicFileAttributes attributes)
            throws Violation {
        if (attributes.isSymbolicLink()) {
            throw violation(Reason.LINKED_COMPONENT);
        }
        if (attributes.isOther()) {
            throw violation(Reason.NON_ORDINARY_COMPONENT);
        }
    }

    private static EntryType typeOf(BasicFileAttributes attributes) throws Violation {
        if (attributes.isDirectory()) return EntryType.DIRECTORY;
        if (attributes.isRegularFile()) return EntryType.REGULAR_FILE;
        throw violation(Reason.NON_ORDINARY_COMPONENT);
    }

    private static Path normalizeAbsolute(Path requested) throws Violation {
        if (requested == null) throw violation(Reason.MISSING);
        try {
            return requested.toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            throw new Violation(Reason.OUTSIDE_ROOT, invalid);
        }
    }

    private static Violation violation(Reason reason) {
        return new Violation(reason);
    }

    private record Scan(
            Path path,
            boolean exists,
            BasicFileAttributes attributes,
            Path nearestExisting) {}
}
