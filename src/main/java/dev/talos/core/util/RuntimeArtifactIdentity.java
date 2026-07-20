package dev.talos.core.util;

import dev.talos.core.security.NoFollowPathPolicy;

import java.net.URI;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.io.File;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Exact byte identity for the Talos code artifact executing the current turn. */
public final class RuntimeArtifactIdentity {
    private static final String QUALIFICATION_ROOT_PROPERTY = "talos.qualification.root";

    private RuntimeArtifactIdentity() {}

    public record Snapshot(
            String kind,
            String sha256,
            long sizeBytes,
            String version,
            String commitSha,
            String classpathSha256,
            long classpathSizeBytes,
            int classpathEntryCount) {
        public Snapshot {
            kind = safe(kind);
            sha256 = safe(sha256);
            version = safe(version);
            commitSha = safe(commitSha);
            classpathSha256 = safe(classpathSha256);
            sizeBytes = Math.max(0L, sizeBytes);
            classpathSizeBytes = Math.max(0L, classpathSizeBytes);
            classpathEntryCount = Math.max(0, classpathEntryCount);
        }

        public boolean exact() {
            return sha256.matches("(?i)[0-9a-f]{64}")
                    && sizeBytes > 0
                    && !BuildInfo.UNKNOWN.equals(version)
                    && commitSha.matches("(?i)[0-9a-f]{40}")
                    && classpathSha256.matches("(?i)[0-9a-f]{64}")
                    && classpathSizeBytes > 0
                    && classpathEntryCount > 0;
        }
    }

    /** Path-free identity of the exact ordered Java application classpath. */
    public record ClasspathSnapshot(
            String sha256,
            long sizeBytes,
            int entryCount) {
        public ClasspathSnapshot {
            sha256 = safe(sha256);
            sizeBytes = Math.max(0L, sizeBytes);
            entryCount = Math.max(0, entryCount);
        }

        public boolean exact() {
            return sha256.matches("(?i)[0-9a-f]{64}")
                    && sizeBytes > 0
                    && entryCount > 0;
        }
    }

    /** One path-free, length-framed input to the classpath aggregate. */
    public record ClasspathEntryIdentity(
            int ordinal,
            String kind,
            String logicalToken,
            String sha256,
            long sizeBytes,
            int fileCount) {
        public ClasspathEntryIdentity {
            kind = safe(kind);
            logicalToken = safe(logicalToken);
            sha256 = safe(sha256);
            sizeBytes = Math.max(0L, sizeBytes);
            fileCount = Math.max(0, fileCount);
        }

        public boolean exact() {
            return ordinal > 0
                    && ("FILE".equals(kind) || "DIRECTORY".equals(kind))
                    && !logicalToken.isBlank()
                    && !logicalToken.equals(".")
                    && !logicalToken.equals("..")
                    && logicalToken.indexOf('/') < 0
                    && logicalToken.indexOf('\\') < 0
                    && sha256.matches("[0-9a-f]{64}")
                    && sizeBytes > 0
                    && fileCount > 0;
        }
    }

    public static Snapshot current() {
        if (!qualificationEvidenceRequested()) {
            return unavailableSnapshot();
        }
        return ExactIdentityHolder.CURRENT;
    }

    public static ClasspathSnapshot currentClasspath() {
        return ExactIdentityHolder.CURRENT_CLASSPATH;
    }

    /** Immutable path-free inputs that mechanically reproduce {@link #currentClasspath()}. */
    public static List<ClasspathEntryIdentity> currentClasspathEntryIdentities() {
        return ExactIdentityHolder.CURRENT_CLASSPATH_IDENTITY.entries();
    }

    private static boolean qualificationEvidenceRequested() {
        return !System.getProperty(QUALIFICATION_ROOT_PROPERTY, "").isBlank();
    }

    private static Snapshot unavailableSnapshot() {
        return new Snapshot(
                "UNAVAILABLE", "", 0L, BuildInfo.version(), BuildInfo.fullCommitSha(),
                "", 0L, 0);
    }

    /** Exact identity is initialized only for explicit qualification/evidence work. */
    private static final class ExactIdentityHolder {
        private static final ClasspathIdentity CURRENT_CLASSPATH_IDENTITY =
                detectCurrentClasspath();
        private static final ClasspathSnapshot CURRENT_CLASSPATH =
                CURRENT_CLASSPATH_IDENTITY.snapshot();
        private static final Snapshot CURRENT = detect(CURRENT_CLASSPATH);

        private ExactIdentityHolder() {}
    }

    /**
     * Identifies an explicitly ordered classpath without persisting any raw
     * path. Entry order and multiplicity are part of the digest; duplicate
     * normalized paths are rejected rather than silently collapsed.
     */
    public static ClasspathSnapshot identifyClasspath(List<Path> orderedEntries) {
        return inspectClasspath(orderedEntries).snapshot();
    }

    /** Reconstructs the aggregate from a durable path-free entry ledger. */
    public static ClasspathSnapshot identifyClasspathEntries(
            List<ClasspathEntryIdentity> orderedEntries) {
        if (orderedEntries == null || orderedEntries.isEmpty()) {
            return new ClasspathSnapshot("", 0L, 0);
        }
        try {
            MessageDigest aggregate = MessageDigest.getInstance("SHA-256");
            updateLengthDelimited(aggregate,
                    "talos-runtime-classpath-v1".getBytes(StandardCharsets.UTF_8));
            long totalBytes = 0L;
            int expectedOrdinal = 1;
            for (ClasspathEntryIdentity identity : orderedEntries) {
                if (identity == null || !identity.exact()
                        || identity.ordinal() != expectedOrdinal) {
                    throw new IllegalStateException("classpath entry identity is invalid");
                }
                updateLengthDelimited(aggregate,
                        Integer.toString(identity.ordinal()).getBytes(StandardCharsets.UTF_8));
                updateLengthDelimited(aggregate,
                        identity.kind().getBytes(StandardCharsets.UTF_8));
                updateLengthDelimited(aggregate,
                        identity.logicalToken().getBytes(StandardCharsets.UTF_8));
                updateLengthDelimited(aggregate,
                        identity.sha256().getBytes(StandardCharsets.US_ASCII));
                updateLengthDelimited(aggregate,
                        Long.toString(identity.sizeBytes()).getBytes(StandardCharsets.US_ASCII));
                updateLengthDelimited(aggregate,
                        Integer.toString(identity.fileCount()).getBytes(StandardCharsets.US_ASCII));
                totalBytes = Math.addExact(totalBytes, identity.sizeBytes());
                expectedOrdinal++;
            }
            return new ClasspathSnapshot(
                    HexFormat.of().formatHex(aggregate.digest()),
                    totalBytes,
                    orderedEntries.size());
        } catch (Exception unavailable) {
            return new ClasspathSnapshot("", 0L, 0);
        }
    }

    private static ClasspathIdentity inspectClasspath(List<Path> orderedEntries) {
        if (orderedEntries == null || orderedEntries.isEmpty()) {
            return ClasspathIdentity.unavailable();
        }
        try {
            Set<Path> seen = new HashSet<>();
            List<ClasspathEntryIdentity> identities = new ArrayList<>();
            for (Path raw : orderedEntries) {
                if (raw == null) throw new IllegalStateException("classpath entry is null");
                Path entry = raw.toAbsolutePath().normalize();
                if (!seen.add(entry)) {
                    throw new IllegalStateException("duplicate classpath entry");
                }
                EntryIdentity identified = identifyEntry(entry);
                Path fileName = entry.getFileName();
                if (fileName == null || fileName.toString().isBlank()) {
                    throw new IllegalStateException("classpath entry has no logical name");
                }
                identities.add(new ClasspathEntryIdentity(
                        identities.size() + 1,
                        identified.kind(),
                        fileName.toString(),
                        identified.sha256(),
                        identified.sizeBytes(),
                        identified.fileCount()));
            }
            List<ClasspathEntryIdentity> immutable = List.copyOf(identities);
            ClasspathSnapshot snapshot = identifyClasspathEntries(immutable);
            return snapshot.exact()
                    ? new ClasspathIdentity(snapshot, immutable)
                    : ClasspathIdentity.unavailable();
        } catch (Exception unavailable) {
            return ClasspathIdentity.unavailable();
        }
    }

    private static Snapshot detect(ClasspathSnapshot classpath) {
        try {
            URI location = RuntimeArtifactIdentity.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path codeSource = Path.of(location).toAbsolutePath().normalize();
            try {
                NoFollowPathPolicy.requireUnlinkedRegularFile(codeSource);
                return new Snapshot(
                        "JAR",
                        sha256(codeSource),
                        Files.size(codeSource),
                        BuildInfo.version(),
                        BuildInfo.fullCommitSha(),
                        classpath.sha256(), classpath.sizeBytes(), classpath.entryCount());
            } catch (NoFollowPathPolicy.Violation notARegularFile) {
                if (notARegularFile.reason() != NoFollowPathPolicy.Reason.NOT_REGULAR_FILE) {
                    throw notARegularFile;
                }
            }
            try {
                NoFollowPathPolicy.GuardedRoot codeRoot =
                        NoFollowPathPolicy.bindExistingDirectory(codeSource);
                Path packageRoot = codeSource.resolve("dev/talos");
                List<NoFollowPathPolicy.TreeFile> classes =
                        codeRoot.readRegularFiles(packageRoot);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0L;
                for (NoFollowPathPolicy.TreeFile file : classes) {
                    byte[] bytes = file.bytes();
                    byte[] relative = packageRoot.relativize(file.path()).toString()
                            .replace('\\', '/').getBytes(StandardCharsets.UTF_8);
                    updateLengthDelimited(digest, relative);
                    updateLengthDelimited(digest, bytes);
                    total = Math.addExact(total, bytes.length);
                }
                if (!classes.isEmpty()) {
                    return new Snapshot(
                            "EXPLODED_CLASSES",
                            HexFormat.of().formatHex(digest.digest()),
                            total,
                            BuildInfo.version(),
                            BuildInfo.fullCommitSha(),
                            classpath.sha256(), classpath.sizeBytes(), classpath.entryCount());
                }
            } catch (NoFollowPathPolicy.Violation notADirectory) {
                if (notADirectory.reason() != NoFollowPathPolicy.Reason.NOT_DIRECTORY) {
                    throw notADirectory;
                }
            }
        } catch (Exception ignored) {
            // An unavailable identity is recorded explicitly. Qualification v6
            // rejects it rather than converting absence into a verified claim.
        }
        return new Snapshot(
                "UNAVAILABLE", "", 0L, BuildInfo.version(), BuildInfo.fullCommitSha(),
                classpath.sha256(), classpath.sizeBytes(), classpath.entryCount());
    }

    private static ClasspathIdentity detectCurrentClasspath() {
        String raw = System.getProperty("java.class.path", "");
        if (raw.isBlank()) return ClasspathIdentity.unavailable();
        List<Path> entries = new ArrayList<>();
        for (String value : raw.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
            if (value == null || value.isBlank()) {
                return ClasspathIdentity.unavailable();
            }
            try {
                entries.add(Path.of(value));
            } catch (RuntimeException invalid) {
                return ClasspathIdentity.unavailable();
            }
        }
        return inspectClasspath(entries);
    }

    private record ClasspathIdentity(
            ClasspathSnapshot snapshot,
            List<ClasspathEntryIdentity> entries) {
        private ClasspathIdentity {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        private static ClasspathIdentity unavailable() {
            return new ClasspathIdentity(new ClasspathSnapshot("", 0L, 0), List.of());
        }
    }

    private record EntryIdentity(String kind, String sha256, long sizeBytes, int fileCount) {}

    private static EntryIdentity identifyEntry(Path entry) throws Exception {
        try {
            NoFollowPathPolicy.requireUnlinkedRegularFile(entry);
            return new EntryIdentity("FILE", sha256(entry), Files.size(entry), 1);
        } catch (NoFollowPathPolicy.Violation notARegularFile) {
            if (notARegularFile.reason() != NoFollowPathPolicy.Reason.NOT_REGULAR_FILE) {
                throw notARegularFile;
            }
        }
        NoFollowPathPolicy.GuardedRoot entryRoot =
                NoFollowPathPolicy.bindExistingDirectory(entry);
        List<NoFollowPathPolicy.TreeFile> files =
                entryRoot.readRegularFiles(entry);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        for (NoFollowPathPolicy.TreeFile file : files) {
            byte[] bytes = file.bytes();
            updateLengthDelimited(digest, entry.relativize(file.path()).toString()
                    .replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            updateLengthDelimited(digest, bytes);
            total = Math.addExact(total, bytes.length);
        }
        if (files.isEmpty()) throw new IllegalStateException("empty classpath directory");
        return new EntryIdentity(
                "DIRECTORY", HexFormat.of().formatHex(digest.digest()), total, files.size());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = NoFollowPathPolicy.openUnlinkedRegularFile(path);
                DigestInputStream hashing = new DigestInputStream(input, digest)) {
            byte[] buffer = new byte[1024 * 1024];
            while (hashing.read(buffer) >= 0) {
                // DigestInputStream updates the digest.
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateLengthDelimited(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        digest.update(bytes);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
