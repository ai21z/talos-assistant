package dev.talos.core.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Owns one fail-closed, same-filesystem replacement of a primary file.
 *
 * <p>The commit point is the successful atomic move. Every failure before that
 * point leaves the target untouched by this writer and attempts to remove its
 * sibling temp; a cleanup failure is surfaced explicitly. A failure after the
 * move is reported as committed-but-unverified; it is never described as an
 * unchanged target.
 *
 * <p>Cooperative writers in this JVM serialize the definitive preimage check
 * and atomic promotion. Consumers that require cross-process exclusion must
 * hold their owner-specific process lock; portable file moves do not provide
 * an external compare-and-swap against noncooperating writers.
 *
 * <p>The atomic unit is one named directory entry. Replacing it creates a new
 * file object, so file-key/inode identity, creation/birth time, link count, and
 * propagation to other hard-link aliases are deliberately not preserved.
 *
 * <p>This class deliberately owns no workspace containment, approval,
 * checkpoint, retry, or multi-file transaction policy.
 */
public final class AtomicFileWriter {

    private static final String TEMP_PREFIX = ".talos-atomic-";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final int MAX_TEMP_NAME_ATTEMPTS = 32;
    private static final int HASH_BUFFER_BYTES = 16 * 1024;
    // Equality is supported; over-limit metadata fails before temp publication.
    static final int MAX_USER_DEFINED_ATTRIBUTES = 256;
    static final int MAX_USER_DEFINED_NAME_BYTES = 64 * 1024;
    static final int MAX_USER_DEFINED_ATTRIBUTE_VALUE_BYTES = 1024 * 1024;
    static final int MAX_USER_DEFINED_AGGREGATE_VALUE_BYTES = 8 * 1024 * 1024;
    private static final LinkOption[] NO_FOLLOW = { LinkOption.NOFOLLOW_LINKS };
    private static final Object PROMOTION_MUTEX = new Object();

    private final FaultInjector faultInjector;
    private final Operations operations;

    public AtomicFileWriter() {
        this(FaultInjector.NONE, Operations.system());
    }

    /**
     * Fault seam for deterministic durability tests. Production callers should
     * use {@link #AtomicFileWriter()}. Every callback runs before the definitive
     * preimage/metadata check or after promotion; the package-private mover is
     * the exact at-promotion seam, so no callback can open a check-to-move race.
     */
    public AtomicFileWriter(FaultInjector faultInjector) {
        this(faultInjector, Operations.system());
    }

    AtomicFileWriter(
            FaultInjector faultInjector,
            AtomicMover atomicMover,
            DirectorySynchronizer directorySynchronizer
    ) {
        this(
                faultInjector,
                Operations.system()
                        .withAtomicMover(atomicMover)
                        .withDirectorySynchronizer(directorySynchronizer));
    }

    AtomicFileWriter(FaultInjector faultInjector, Operations operations) {
        this.faultInjector = faultInjector == null ? FaultInjector.NONE : faultInjector;
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public enum CommitState {
        NOT_COMMITTED,
        COMMIT_STATE_UNKNOWN,
        COMMITTED_UNVERIFIED
    }

    public enum FailureReason {
        INVALID_TARGET,
        SOURCE_IO,
        STALE_PREIMAGE,
        TEMP_IO,
        TEMP_VALIDATION_FAILED,
        METADATA_UNSUPPORTED,
        METADATA_LIMIT_EXCEEDED,
        METADATA_FAILED,
        ATOMIC_MOVE_UNSUPPORTED,
        PROMOTION_FAILED,
        POST_COMMIT_VERIFICATION_FAILED,
        TEMP_CLEANUP_FAILED
    }

    public enum MetadataStatus {
        NOT_APPLICABLE,
        PRESERVED,
        UNSUPPORTED
    }

    public enum DirectorySyncStatus {
        SYNCED,
        UNSUPPORTED
    }

    public enum FaultStage {
        AFTER_TEMP_CREATE,
        AFTER_INITIAL_METADATA_APPLY,
        AFTER_INITIAL_METADATA_FLUSH,
        AFTER_PARTIAL_TEMP_WRITE,
        AFTER_TEMP_FLUSH,
        AFTER_METADATA_APPLY,
        AFTER_METADATA_FLUSH,
        AFTER_TEMP_VALIDATION,
        BEFORE_PREIMAGE_RECHECK,
        BEFORE_PROMOTION,
        AFTER_PROMOTION
    }

    @FunctionalInterface
    public interface FaultInjector {
        FaultInjector NONE = (stage, target, temp) -> { };

        void at(FaultStage stage, Path target, Path temp) throws IOException;
    }

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    interface DirectorySynchronizer {
        DirectorySyncStatus sync(Path directory) throws IOException;
    }

    @FunctionalInterface
    interface ContentWriter {
        void write(FileChannel channel, ByteBuffer source) throws IOException;
    }

    @FunctionalInterface
    interface FileSynchronizer {
        void force(FileChannel channel) throws IOException;
    }

    @FunctionalInterface
    interface ForceCall {
        void force(FileChannel channel, boolean metadata) throws IOException;
    }

    @FunctionalInterface
    interface ContentValidator {
        void validate(Path path, long expectedLength, String expectedSha256)
                throws IOException;
    }

    interface MetadataOperations {
        SecurityMetadata capture(Path path, boolean applicable) throws IOException;

        void apply(SecurityMetadata metadata, Path path) throws IOException;

        void reconcile(SecurityMetadata metadata, Path path) throws IOException;

        void verify(SecurityMetadata metadata, Path path) throws IOException;
    }

    @FunctionalInterface
    interface TempCleaner {
        void delete(Path temp, SecurityMetadata creationMetadata) throws IOException;
    }

    @FunctionalInterface
    interface DeleteCall {
        boolean deleteIfExists(Path path) throws IOException;
    }

    @FunctionalInterface
    interface MoveCall {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    static final class Operations {
        private static final MetadataOperations SYSTEM_METADATA = new MetadataOperations() {
            @Override
            public SecurityMetadata capture(Path path, boolean applicable) throws IOException {
                return SecurityMetadata.capture(path, applicable);
            }

            @Override
            public void apply(SecurityMetadata metadata, Path path) throws IOException {
                metadata.apply(path);
            }

            @Override
            public void reconcile(SecurityMetadata metadata, Path path) throws IOException {
                metadata.reconcile(path);
            }

            @Override
            public void verify(SecurityMetadata metadata, Path path) throws IOException {
                metadata.verify(path);
            }
        };
        private static final Operations SYSTEM = new Operations(
                AtomicFileWriter::writeFully,
                metadataForcing(FileChannel::force),
                AtomicFileWriter::validateContent,
                SYSTEM_METADATA,
                AtomicFileWriter.atomicMover(Files::move),
                AtomicFileWriter::syncDirectory,
                AtomicFileWriter.tempCleaner(Files::deleteIfExists));

        private final ContentWriter contentWriter;
        private final FileSynchronizer fileSynchronizer;
        private final ContentValidator contentValidator;
        private final MetadataOperations metadataOperations;
        private final AtomicMover atomicMover;
        private final DirectorySynchronizer directorySynchronizer;
        private final TempCleaner tempCleaner;

        private Operations(
                ContentWriter contentWriter,
                FileSynchronizer fileSynchronizer,
                ContentValidator contentValidator,
                MetadataOperations metadataOperations,
                AtomicMover atomicMover,
                DirectorySynchronizer directorySynchronizer,
                TempCleaner tempCleaner
        ) {
            this.contentWriter = Objects.requireNonNull(contentWriter, "contentWriter");
            this.fileSynchronizer =
                    Objects.requireNonNull(fileSynchronizer, "fileSynchronizer");
            this.contentValidator =
                    Objects.requireNonNull(contentValidator, "contentValidator");
            this.metadataOperations =
                    Objects.requireNonNull(metadataOperations, "metadataOperations");
            this.atomicMover = Objects.requireNonNull(atomicMover, "atomicMover");
            this.directorySynchronizer =
                    Objects.requireNonNull(directorySynchronizer, "directorySynchronizer");
            this.tempCleaner = Objects.requireNonNull(tempCleaner, "tempCleaner");
        }

        static Operations system() {
            return SYSTEM;
        }

        ContentWriter contentWriter() {
            return contentWriter;
        }

        FileSynchronizer fileSynchronizer() {
            return fileSynchronizer;
        }

        ContentValidator contentValidator() {
            return contentValidator;
        }

        MetadataOperations metadataOperations() {
            return metadataOperations;
        }

        AtomicMover atomicMover() {
            return atomicMover;
        }

        DirectorySynchronizer directorySynchronizer() {
            return directorySynchronizer;
        }

        TempCleaner tempCleaner() {
            return tempCleaner;
        }

        Operations withContentWriter(ContentWriter replacement) {
            return new Operations(
                    replacement,
                    fileSynchronizer,
                    contentValidator,
                    metadataOperations,
                    atomicMover,
                    directorySynchronizer,
                    tempCleaner);
        }

        Operations withFileSynchronizer(FileSynchronizer replacement) {
            return new Operations(
                    contentWriter,
                    replacement,
                    contentValidator,
                    metadataOperations,
                    atomicMover,
                    directorySynchronizer,
                    tempCleaner);
        }

        Operations withContentValidator(ContentValidator replacement) {
            return new Operations(
                    contentWriter,
                    fileSynchronizer,
                    replacement,
                    metadataOperations,
                    atomicMover,
                    directorySynchronizer,
                    tempCleaner);
        }

        Operations withMetadataOperations(MetadataOperations replacement) {
            return new Operations(
                    contentWriter,
                    fileSynchronizer,
                    contentValidator,
                    replacement,
                    atomicMover,
                    directorySynchronizer,
                    tempCleaner);
        }

        Operations withAtomicMover(AtomicMover replacement) {
            return new Operations(
                    contentWriter,
                    fileSynchronizer,
                    contentValidator,
                    metadataOperations,
                    replacement,
                    directorySynchronizer,
                    tempCleaner);
        }

        Operations withDirectorySynchronizer(DirectorySynchronizer replacement) {
            return new Operations(
                    contentWriter,
                    fileSynchronizer,
                    contentValidator,
                    metadataOperations,
                    atomicMover,
                    replacement,
                    tempCleaner);
        }

        Operations withTempCleaner(TempCleaner replacement) {
            return new Operations(
                    contentWriter,
                    fileSynchronizer,
                    contentValidator,
                    metadataOperations,
                    atomicMover,
                    directorySynchronizer,
                    replacement);
        }
    }

    public record Preimage(boolean existed, String sha256) {
        public Preimage {
            sha256 = sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT);
            if (existed && !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Present preimage requires a SHA-256 value");
            }
            if (!existed && !sha256.isEmpty()) {
                throw new IllegalArgumentException("Absent preimage cannot carry a SHA-256 value");
            }
        }

        public static Preimage absent() {
            return new Preimage(false, "");
        }

        public static Preimage present(String sha256) {
            return new Preimage(true, sha256);
        }
    }

    public record ReadSnapshot(byte[] bytes, Preimage preimage) {
        public ReadSnapshot {
            bytes = bytes == null ? new byte[0] : bytes.clone();
            Objects.requireNonNull(preimage, "preimage");
            if (!preimage.existed()) {
                throw new IllegalArgumentException("Read snapshot requires a present preimage");
            }
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record WriteOutcome(
            String replacementSha256,
            MetadataStatus posixMetadata,
            MetadataStatus aclMetadata,
            MetadataStatus dosMetadata,
            MetadataStatus userDefinedMetadata,
            DirectorySyncStatus directorySync
    ) {
        public WriteOutcome {
            Objects.requireNonNull(replacementSha256, "replacementSha256");
            Objects.requireNonNull(posixMetadata, "posixMetadata");
            Objects.requireNonNull(aclMetadata, "aclMetadata");
            Objects.requireNonNull(dosMetadata, "dosMetadata");
            Objects.requireNonNull(userDefinedMetadata, "userDefinedMetadata");
            Objects.requireNonNull(directorySync, "directorySync");
        }
    }

    public static final class AtomicWriteException extends IOException {
        private final FailureReason reason;
        private final CommitState commitState;

        private AtomicWriteException(FailureReason reason, CommitState commitState) {
            super(safeMessage(reason, commitState));
            this.reason = Objects.requireNonNull(reason, "reason");
            this.commitState = Objects.requireNonNull(commitState, "commitState");
        }

        public FailureReason reason() {
            return reason;
        }

        public CommitState commitState() {
            return commitState;
        }
    }

    /**
     * Captures the current leaf state without following a symbolic-link target.
     */
    public Preimage capture(Path target) throws AtomicWriteException {
        Path normalized = normalizedTarget(target);
        try {
            return captureUnchecked(normalized);
        } catch (AtomicWriteException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw failure(FailureReason.SOURCE_IO, CommitState.NOT_COMMITTED);
        }
    }

    /**
     * Reads exact bytes and returns the hash token bound to those same bytes.
     */
    public ReadSnapshot readSnapshot(Path target) throws AtomicWriteException {
        Path normalized = normalizedTarget(target);
        try {
            var attributes = Files.readAttributes(
                    normalized,
                    java.nio.file.attribute.BasicFileAttributes.class,
                    NO_FOLLOW);
            if (!attributes.isRegularFile()) {
                throw failure(FailureReason.INVALID_TARGET, CommitState.NOT_COMMITTED);
            }
            byte[] bytes = Files.readAllBytes(normalized);
            return new ReadSnapshot(bytes, Preimage.present(sha256(bytes)));
        } catch (AtomicWriteException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw failure(FailureReason.SOURCE_IO, CommitState.NOT_COMMITTED);
        }
    }

    /**
     * Replaces a target only if its current state still matches {@code expected}.
     */
    public WriteOutcome replace(Path target, byte[] replacement, Preimage expected)
            throws AtomicWriteException {
        Path normalized = normalizedTarget(target);
        byte[] replacementBytes = replacement == null ? new byte[0] : replacement.clone();
        Objects.requireNonNull(expected, "expected");
        Path parent = normalized.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw failure(FailureReason.INVALID_TARGET, CommitState.NOT_COMMITTED);
        }

        Path temp = null;
        SecurityMetadata tempCreationMetadata = null;
        CommitState observedState = CommitState.NOT_COMMITTED;
        AtomicWriteException pendingFailure = null;
        WriteOutcome outcome = null;
        try {
            requireExpectedPreimage(normalized, expected);
            SecurityMetadata metadata = captureMetadata(normalized, expected.existed());
            requireWritableExistingTarget(normalized, expected.existed());
            String replacementSha256 = sha256(replacementBytes);

            temp = createSiblingTemp(parent);
            tempCreationMetadata = captureMetadata(temp, true);
            faultInjector.at(FaultStage.AFTER_TEMP_CREATE, normalized, temp);
            try (FileChannel stagedChannel =
                         FileChannel.open(temp, StandardOpenOption.WRITE)) {
                writeFlushAndApplyMetadata(
                        stagedChannel,
                        temp,
                        replacementBytes,
                        normalized,
                        metadata);

                validateContentWithOperations(
                        temp,
                        replacementBytes.length,
                        replacementSha256);
                verifyMetadata(metadata, temp);
                faultInjector.at(FaultStage.AFTER_TEMP_VALIDATION, normalized, temp);

                faultInjector.at(FaultStage.BEFORE_PREIMAGE_RECHECK, normalized, temp);
                faultInjector.at(FaultStage.BEFORE_PROMOTION, normalized, temp);

                synchronized (PROMOTION_MUTEX) {
                    requireExpectedPreimage(normalized, expected);
                    verifyMetadata(metadata, normalized);
                    observedState = CommitState.COMMIT_STATE_UNKNOWN;
                    try {
                        operations.atomicMover().move(temp, normalized);
                    } catch (AtomicMoveNotSupportedException e) {
                        observedState = CommitState.NOT_COMMITTED;
                        throw failure(
                                FailureReason.ATOMIC_MOVE_UNSUPPORTED,
                                CommitState.NOT_COMMITTED);
                    } catch (IOException | RuntimeException e) {
                        throw failure(
                                FailureReason.PROMOTION_FAILED,
                                CommitState.COMMIT_STATE_UNKNOWN);
                    }
                    observedState = CommitState.COMMITTED_UNVERIFIED;
                    temp = null;

                    faultInjector.at(FaultStage.AFTER_PROMOTION, normalized, null);
                    validateContentWithOperations(
                            normalized,
                            replacementBytes.length,
                            replacementSha256);
                    if (metadata.applicable()) {
                        reconcileMetadata(metadata, normalized);
                        operations.fileSynchronizer().force(stagedChannel);
                        verifyMetadata(metadata, normalized);
                    }
                    DirectorySyncStatus directorySync =
                            operations.directorySynchronizer().sync(parent);
                    validateContentWithOperations(
                            normalized,
                            replacementBytes.length,
                            replacementSha256);
                    verifyMetadata(metadata, normalized);
                    outcome = new WriteOutcome(
                            replacementSha256,
                            metadata.posixStatus(),
                            metadata.aclStatus(),
                            metadata.dosStatus(),
                            metadata.userDefinedStatus(),
                            directorySync);
                }
            }
        } catch (AtomicWriteException e) {
            pendingFailure = e;
        } catch (MetadataLimitExceededException e) {
            pendingFailure = failure(
                    FailureReason.METADATA_LIMIT_EXCEEDED,
                    observedState);
        } catch (MetadataUnsupportedException e) {
            pendingFailure = failure(
                    FailureReason.METADATA_UNSUPPORTED,
                    observedState);
        } catch (MetadataFailureException e) {
            pendingFailure = failure(
                    FailureReason.METADATA_FAILED,
                    observedState);
        } catch (ContentValidationException e) {
            pendingFailure = failure(
                    observedState == CommitState.COMMITTED_UNVERIFIED
                            ? FailureReason.POST_COMMIT_VERIFICATION_FAILED
                            : FailureReason.TEMP_VALIDATION_FAILED,
                    observedState);
        } catch (IOException | RuntimeException e) {
            pendingFailure = failure(
                    observedState == CommitState.COMMITTED_UNVERIFIED
                            ? FailureReason.POST_COMMIT_VERIFICATION_FAILED
                            : FailureReason.TEMP_IO,
                    observedState);
        }

        if (temp != null) {
            try {
                operations.tempCleaner().delete(temp, tempCreationMetadata);
            } catch (IOException | RuntimeException cleanupFailure) {
                AtomicWriteException classifiedCleanup = failure(
                        FailureReason.TEMP_CLEANUP_FAILED,
                        strongestState(observedState, pendingFailure));
                if (pendingFailure == null) {
                    pendingFailure = classifiedCleanup;
                } else {
                    classifiedCleanup.addSuppressed(pendingFailure);
                    pendingFailure = classifiedCleanup;
                }
            }
        }

        if (pendingFailure != null) {
            throw pendingFailure;
        }
        if (outcome == null) {
            throw failure(FailureReason.TEMP_IO, observedState);
        }
        return outcome;
    }

    /**
     * Convenience for consumers that derive no content from the prior bytes.
     */
    public WriteOutcome replaceCurrent(Path target, byte[] replacement)
            throws AtomicWriteException {
        Preimage expected = capture(target);
        return replace(target, replacement, expected);
    }

    private void writeFlushAndApplyMetadata(
            FileChannel channel,
            Path temp,
            byte[] bytes,
            Path target,
            SecurityMetadata metadata
    ) throws IOException {
        if (metadata.applicable()) {
            applyMetadata(metadata, temp);
            faultInjector.at(FaultStage.AFTER_INITIAL_METADATA_APPLY, target, temp);
            operations.fileSynchronizer().force(channel);
            faultInjector.at(FaultStage.AFTER_INITIAL_METADATA_FLUSH, target, temp);
            verifyMetadata(metadata, temp);
        }

        ByteBuffer source = ByteBuffer.wrap(bytes);
        if (source.hasRemaining()) {
            int firstChunkSize = Math.max(1, source.remaining() / 2);
            ByteBuffer firstChunk = source.slice(0, firstChunkSize);
            operations.contentWriter().write(channel, firstChunk);
            source.position(firstChunkSize);
            faultInjector.at(FaultStage.AFTER_PARTIAL_TEMP_WRITE, target, temp);
        } else {
            faultInjector.at(FaultStage.AFTER_PARTIAL_TEMP_WRITE, target, temp);
        }
        operations.contentWriter().write(channel, source);
        operations.fileSynchronizer().force(channel);
        faultInjector.at(FaultStage.AFTER_TEMP_FLUSH, target, temp);

        if (metadata.applicable()) {
            applyMetadata(metadata, temp);
            faultInjector.at(FaultStage.AFTER_METADATA_APPLY, target, temp);
            operations.fileSynchronizer().force(channel);
            faultInjector.at(FaultStage.AFTER_METADATA_FLUSH, target, temp);
            verifyMetadata(metadata, temp);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer source) throws IOException {
        while (source.hasRemaining()) {
            int written = channel.write(source);
            if (written <= 0) {
                throw new IOException("File channel made no write progress");
            }
        }
    }

    private static Path createSiblingTemp(Path parent) throws IOException {
        for (int attempt = 0; attempt < MAX_TEMP_NAME_ATTEMPTS; attempt++) {
            Path candidate = parent.resolve(TEMP_PREFIX + UUID.randomUUID() + TEMP_SUFFIX);
            try {
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException ignored) {
                // Retry with fresh entropy. No existing path is ever reused.
            }
        }
        throw new IOException("Unable to reserve atomic-write temp");
    }

    private static void requireExpectedPreimage(Path target, Preimage expected)
            throws IOException {
        Preimage current = captureUnchecked(target);
        if (!current.equals(expected)) {
            throw failure(FailureReason.STALE_PREIMAGE, CommitState.NOT_COMMITTED);
        }
    }

    private static void requireWritableExistingTarget(Path target, boolean existed)
            throws AtomicWriteException {
        if (!existed) {
            return;
        }
        try (FileChannel ignored = FileChannel.open(
                target,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            // Non-truncating access check preserves direct-write authorization semantics.
        } catch (IOException | RuntimeException e) {
            throw failure(FailureReason.SOURCE_IO, CommitState.NOT_COMMITTED);
        }
    }

    private static Preimage captureUnchecked(Path target) throws IOException {
        try {
            var attributes = Files.readAttributes(
                    target,
                    java.nio.file.attribute.BasicFileAttributes.class,
                    NO_FOLLOW);
            if (!attributes.isRegularFile()) {
                throw failure(FailureReason.INVALID_TARGET, CommitState.NOT_COMMITTED);
            }
            return Preimage.present(sha256(target));
        } catch (NoSuchFileException e) {
            return Preimage.absent();
        }
    }

    private SecurityMetadata captureMetadata(Path path, boolean applicable)
            throws IOException {
        try {
            return operations.metadataOperations().capture(path, applicable);
        } catch (MetadataUnsupportedException
                 | MetadataLimitExceededException
                 | MetadataFailureException e) {
            throw e;
        } catch (UnsupportedOperationException e) {
            throw new MetadataUnsupportedException();
        } catch (IOException | RuntimeException e) {
            throw new MetadataFailureException();
        }
    }

    private void applyMetadata(SecurityMetadata metadata, Path path) throws IOException {
        try {
            operations.metadataOperations().apply(metadata, path);
        } catch (MetadataUnsupportedException
                 | MetadataLimitExceededException
                 | MetadataFailureException e) {
            throw e;
        } catch (UnsupportedOperationException e) {
            throw new MetadataUnsupportedException();
        } catch (IOException | RuntimeException e) {
            throw new MetadataFailureException();
        }
    }

    private void reconcileMetadata(SecurityMetadata metadata, Path path) throws IOException {
        try {
            operations.metadataOperations().reconcile(metadata, path);
        } catch (MetadataUnsupportedException
                 | MetadataLimitExceededException
                 | MetadataFailureException e) {
            throw e;
        } catch (UnsupportedOperationException e) {
            throw new MetadataUnsupportedException();
        } catch (IOException | RuntimeException e) {
            throw new MetadataFailureException();
        }
    }

    private void verifyMetadata(SecurityMetadata metadata, Path path) throws IOException {
        try {
            operations.metadataOperations().verify(metadata, path);
        } catch (MetadataUnsupportedException
                 | MetadataLimitExceededException
                 | MetadataFailureException e) {
            throw e;
        } catch (UnsupportedOperationException e) {
            throw new MetadataUnsupportedException();
        } catch (IOException | RuntimeException e) {
            throw new MetadataFailureException();
        }
    }

    private void validateContentWithOperations(
            Path path,
            long expectedLength,
            String expectedSha256
    ) throws IOException {
        try {
            operations.contentValidator().validate(path, expectedLength, expectedSha256);
        } catch (ContentValidationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ContentValidationException();
        }
    }

    private static void validateContent(Path path, long expectedLength, String expectedSha256)
            throws IOException {
        if (Files.size(path) != expectedLength || !sha256(path).equals(expectedSha256)) {
            throw new ContentValidationException();
        }
    }

    private static Path normalizedTarget(Path target) throws AtomicWriteException {
        if (target == null) {
            throw failure(FailureReason.INVALID_TARGET, CommitState.NOT_COMMITTED);
        }
        try {
            return target.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw failure(FailureReason.INVALID_TARGET, CommitState.NOT_COMMITTED);
        }
    }

    static FileSynchronizer metadataForcing(ForceCall forceCall) {
        Objects.requireNonNull(forceCall, "forceCall");
        return channel -> forceCall.force(channel, true);
    }

    static AtomicMover atomicMover(MoveCall moveCall) {
        Objects.requireNonNull(moveCall, "moveCall");
        return (source, target) -> moveCall.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    static TempCleaner tempCleaner(DeleteCall deleteCall) {
        Objects.requireNonNull(deleteCall, "deleteCall");
        return (temp, creationMetadata) ->
                deleteTemp(temp, creationMetadata, deleteCall);
    }

    private static DirectorySyncStatus syncDirectory(Path directory) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return DirectorySyncStatus.UNSUPPORTED;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
            return DirectorySyncStatus.SYNCED;
        } catch (UnsupportedOperationException e) {
            return DirectorySyncStatus.UNSUPPORTED;
        }
    }

    private static void deleteTemp(
            Path temp,
            SecurityMetadata creationMetadata,
            DeleteCall deleteCall
    ) throws IOException {
        try {
            deleteCall.deleteIfExists(temp);
        } catch (java.nio.file.AccessDeniedException denied) {
            try {
                if (creationMetadata != null) {
                    creationMetadata.restoreForCleanup(temp);
                } else {
                    DosFileAttributeView dos = Files.getFileAttributeView(
                            temp,
                            DosFileAttributeView.class,
                            NO_FOLLOW);
                    if (dos == null) {
                        throw denied;
                    }
                    SecurityMetadata.clearReadOnlyIfNeeded(dos);
                }
                deleteCall.deleteIfExists(temp);
            } catch (IOException | RuntimeException recoveryFailure) {
                denied.addSuppressed(recoveryFailure);
                throw denied;
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[HASH_BUFFER_BYTES];
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime");
        }
    }

    private static AtomicWriteException failure(
            FailureReason reason,
            CommitState commitState
    ) {
        return new AtomicWriteException(reason, commitState);
    }

    private static CommitState strongestState(
            CommitState observedState,
            AtomicWriteException pendingFailure
    ) {
        CommitState pendingState =
                pendingFailure == null ? CommitState.NOT_COMMITTED : pendingFailure.commitState();
        if (observedState == CommitState.COMMITTED_UNVERIFIED
                || pendingState == CommitState.COMMITTED_UNVERIFIED) {
            return CommitState.COMMITTED_UNVERIFIED;
        }
        if (observedState == CommitState.COMMIT_STATE_UNKNOWN
                || pendingState == CommitState.COMMIT_STATE_UNKNOWN) {
            return CommitState.COMMIT_STATE_UNKNOWN;
        }
        return CommitState.NOT_COMMITTED;
    }

    private static String safeMessage(FailureReason reason, CommitState commitState) {
        if (reason == FailureReason.TEMP_CLEANUP_FAILED) {
            return switch (commitState) {
                case NOT_COMMITTED ->
                        "Atomic file replacement failed before commit and temporary-file cleanup failed.";
                case COMMIT_STATE_UNKNOWN ->
                        "Atomic file replacement outcome is unknown and temporary-file cleanup failed.";
                case COMMITTED_UNVERIFIED ->
                        "Atomic file replacement committed, but verification and temporary-file cleanup failed.";
            };
        }
        if (commitState == CommitState.COMMITTED_UNVERIFIED) {
            return "Atomic file replacement committed, but durability verification failed.";
        }
        if (commitState == CommitState.COMMIT_STATE_UNKNOWN) {
            return "Atomic file replacement outcome is unknown after promotion failed.";
        }
        return switch (reason) {
            case STALE_PREIMAGE ->
                    "Atomic file replacement rejected a stale preimage.";
            case ATOMIC_MOVE_UNSUPPORTED ->
                    "Atomic file replacement is unsupported by this filesystem.";
            case INVALID_TARGET ->
                    "Atomic file replacement rejected the target type.";
            case METADATA_UNSUPPORTED ->
                    "Atomic file replacement could not preserve required security metadata.";
            case METADATA_LIMIT_EXCEEDED ->
                    "Atomic file replacement exceeded the supported security-metadata preservation limit.";
            case TEMP_CLEANUP_FAILED ->
                    "Atomic file replacement failed to clean its temporary file.";
            default ->
                    "Atomic file replacement failed before commit.";
        };
    }

    private record PosixSnapshot(
            UserPrincipal owner,
            GroupPrincipal group,
            Set<PosixFilePermission> permissions
    ) {
        private PosixSnapshot {
            permissions = Set.copyOf(permissions);
        }
    }

    private record AclSnapshot(UserPrincipal owner, List<AclEntry> entries) {
        private AclSnapshot {
            entries = List.copyOf(entries);
        }
    }

    private record DosSnapshot(
            boolean readOnly,
            boolean hidden,
            boolean system,
            boolean archive
    ) { }

    private record UserDefinedAttributeSnapshot(String name, byte[] value) {
        private UserDefinedAttributeSnapshot {
            Objects.requireNonNull(name, "name");
            value = Objects.requireNonNull(value, "value").clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        private void writeTo(UserDefinedFileAttributeView view) throws IOException {
            int written = view.write(name, ByteBuffer.wrap(value));
            if (written != value.length) {
                throw new MetadataFailureException();
            }
        }

        @Override
        public boolean equals(Object candidate) {
            return candidate instanceof UserDefinedAttributeSnapshot other
                    && name.equals(other.name)
                    && Arrays.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + Arrays.hashCode(value);
        }
    }

    record UserDefinedSnapshot(List<UserDefinedAttributeSnapshot> attributes) {
        UserDefinedSnapshot {
            attributes = List.copyOf(attributes);
        }

        static UserDefinedSnapshot capture(UserDefinedFileAttributeView view)
                throws IOException {
            List<String> names = boundedNames(view);
            List<UserDefinedAttributeSnapshot> attributes =
                    new ArrayList<>(names.size());
            long totalValueBytes = 0;
            for (String name : names) {
                int size = view.size(name);
                if (size < 0) {
                    throw new MetadataFailureException();
                }
                if (size > MAX_USER_DEFINED_ATTRIBUTE_VALUE_BYTES
                        || size > MAX_USER_DEFINED_AGGREGATE_VALUE_BYTES - totalValueBytes) {
                    throw new MetadataLimitExceededException();
                }
                totalValueBytes += size;
                ByteBuffer value = ByteBuffer.allocate(size);
                int read = view.read(name, value);
                if (read != size || value.position() != size || view.size(name) != size) {
                    throw new MetadataFailureException();
                }
                attributes.add(new UserDefinedAttributeSnapshot(name, value.array()));
            }
            if (!names.equals(boundedNames(view))) {
                throw new MetadataFailureException();
            }
            return new UserDefinedSnapshot(attributes);
        }

        private static List<String> boundedNames(UserDefinedFileAttributeView view)
                throws IOException {
            List<String> names = new ArrayList<>(view.list());
            if (names.size() > MAX_USER_DEFINED_ATTRIBUTES) {
                throw new MetadataLimitExceededException();
            }
            names.sort(String::compareTo);
            Set<String> unique = new HashSet<>();
            long totalNameBytes = 0;
            for (String name : names) {
                if (name == null || name.isEmpty() || !unique.add(name)) {
                    throw new MetadataFailureException();
                }
                totalNameBytes += name.getBytes(StandardCharsets.UTF_8).length;
                if (totalNameBytes > MAX_USER_DEFINED_NAME_BYTES) {
                    throw new MetadataLimitExceededException();
                }
            }
            return names;
        }

        void applyExact(UserDefinedFileAttributeView view) throws IOException {
            UserDefinedSnapshot current = capture(view);
            if (equals(current)) {
                return;
            }
            for (UserDefinedAttributeSnapshot attribute : current.attributes) {
                view.delete(attribute.name());
            }
            for (UserDefinedAttributeSnapshot attribute : attributes) {
                attribute.writeTo(view);
            }
            verify(view);
        }

        void verify(UserDefinedFileAttributeView view) throws IOException {
            if (!equals(capture(view))) {
                throw new MetadataFailureException();
            }
        }
    }

    record SecurityMetadata(
            boolean applicable,
            PosixSnapshot posix,
            AclSnapshot acl,
            DosSnapshot dos,
            UserDefinedSnapshot userDefined
    ) {
        static SecurityMetadata capture(Path target, boolean applicable) throws IOException {
            if (!applicable) {
                return new SecurityMetadata(false, null, null, null, null);
            }
            try {
                PosixFileAttributeView posixView = Files.getFileAttributeView(
                        target,
                        PosixFileAttributeView.class,
                        NO_FOLLOW);
                PosixSnapshot posix = null;
                if (posixView != null) {
                    PosixFileAttributes attributes = posixView.readAttributes();
                    posix = new PosixSnapshot(
                            attributes.owner(),
                            attributes.group(),
                            attributes.permissions());
                }

                AclFileAttributeView aclView = Files.getFileAttributeView(
                        target,
                        AclFileAttributeView.class,
                        NO_FOLLOW);
                AclSnapshot acl = null;
                if (aclView != null) {
                    acl = new AclSnapshot(aclView.getOwner(), aclView.getAcl());
                }

                DosFileAttributeView dosView = Files.getFileAttributeView(
                        target,
                        DosFileAttributeView.class,
                        NO_FOLLOW);
                DosSnapshot dos = null;
                if (dosView != null) {
                    DosFileAttributes attributes = dosView.readAttributes();
                    dos = new DosSnapshot(
                            attributes.isReadOnly(),
                            attributes.isHidden(),
                            attributes.isSystem(),
                            attributes.isArchive());
                }

                UserDefinedFileAttributeView userDefinedView =
                        Files.getFileAttributeView(
                                target,
                                UserDefinedFileAttributeView.class,
                                NO_FOLLOW);
                UserDefinedSnapshot userDefined = userDefinedView == null
                        ? null
                        : UserDefinedSnapshot.capture(userDefinedView);
                return new SecurityMetadata(true, posix, acl, dos, userDefined);
            } catch (MetadataUnsupportedException
                     | MetadataLimitExceededException
                     | MetadataFailureException e) {
                throw e;
            } catch (UnsupportedOperationException e) {
                throw new MetadataUnsupportedException();
            } catch (IOException | RuntimeException e) {
                throw new MetadataFailureException();
            }
        }

        void restoreForCleanup(Path path) throws IOException {
            try {
                DosFileAttributeView dosView = Files.getFileAttributeView(
                        path,
                        DosFileAttributeView.class,
                        NO_FOLLOW);
                if (dosView != null) {
                    clearReadOnlyIfNeeded(dosView);
                }
                if (posix != null) {
                    PosixFileAttributeView view = requiredView(
                            path,
                            PosixFileAttributeView.class);
                    applyPosixIfNeeded(
                            posix.owner(),
                            posix.group(),
                            posix.permissions(),
                            view);
                }
                if (acl != null) {
                    AclFileAttributeView view = requiredView(
                            path,
                            AclFileAttributeView.class);
                    applyAclIfNeeded(acl.owner(), acl.entries(), view);
                }
                if (userDefined != null) {
                    UserDefinedFileAttributeView view = requiredView(
                            path,
                            UserDefinedFileAttributeView.class);
                    userDefined.applyExact(view);
                }
                if (dos != null && dosView != null) {
                    applyDosIfNeeded(
                            false,
                            dos.hidden(),
                            dos.system(),
                            dos.archive(),
                            dosView);
                }
            } catch (MetadataUnsupportedException
                     | MetadataLimitExceededException
                     | MetadataFailureException e) {
                throw e;
            } catch (UnsupportedOperationException e) {
                throw new MetadataUnsupportedException();
            } catch (IOException | RuntimeException e) {
                throw new MetadataFailureException();
            }
        }

        MetadataStatus posixStatus() {
            return status(posix);
        }

        MetadataStatus aclStatus() {
            return status(acl);
        }

        MetadataStatus dosStatus() {
            return status(dos);
        }

        MetadataStatus userDefinedStatus() {
            return status(userDefined);
        }

        void apply(Path path) throws IOException {
            if (!applicable) {
                return;
            }
            try {
                if (posix != null) {
                    PosixFileAttributeView view = requiredView(
                            path,
                            PosixFileAttributeView.class);
                    applyPosixIfNeeded(
                            posix.owner(),
                            posix.group(),
                            posix.permissions(),
                            view);
                }
                if (acl != null) {
                    AclFileAttributeView view = requiredView(
                            path,
                            AclFileAttributeView.class);
                    applyAclIfNeeded(acl.owner(), acl.entries(), view);
                }
                if (userDefined != null) {
                    UserDefinedFileAttributeView view = requiredView(
                            path,
                            UserDefinedFileAttributeView.class);
                    userDefined.applyExact(view);
                }
                if (dos != null) {
                    DosFileAttributeView view = requiredView(
                            path,
                            DosFileAttributeView.class);
                    applyDosIfNeeded(
                            dos.readOnly(),
                            dos.hidden(),
                            dos.system(),
                            dos.archive(),
                            view);
                }
            } catch (MetadataUnsupportedException
                     | MetadataLimitExceededException
                     | MetadataFailureException e) {
                throw e;
            } catch (UnsupportedOperationException e) {
                throw new MetadataUnsupportedException();
            } catch (IOException | RuntimeException e) {
                throw new MetadataFailureException();
            }
        }

        static void applyPosixIfNeeded(
                UserPrincipal owner,
                GroupPrincipal group,
                Set<PosixFilePermission> permissions,
                PosixFileAttributeView view
        ) throws IOException {
            PosixFileAttributes attributes = view.readAttributes();
            boolean principalChanged = false;
            if (!samePrincipal(group, attributes.group())) {
                view.setGroup(group);
                principalChanged = true;
            }
            if (!samePrincipal(owner, attributes.owner())) {
                view.setOwner(owner);
                principalChanged = true;
            }
            Set<PosixFilePermission> currentPermissions = principalChanged
                    ? view.readAttributes().permissions()
                    : attributes.permissions();
            if (!permissions.equals(currentPermissions)) {
                view.setPermissions(permissions);
            }
        }

        static void applyAclIfNeeded(
                UserPrincipal owner,
                List<AclEntry> entries,
                AclFileAttributeView view
        ) throws IOException {
            if (!samePrincipal(owner, view.getOwner())) {
                view.setOwner(owner);
            }
            if (!entries.equals(view.getAcl())) {
                view.setAcl(entries);
            }
        }

        static void applyDosIfNeeded(
                boolean readOnly,
                boolean hidden,
                boolean system,
                boolean archive,
                DosFileAttributeView view
        ) throws IOException {
            DosFileAttributes attributes = view.readAttributes();
            boolean currentReadOnly = attributes.isReadOnly();
            if (currentReadOnly && !readOnly) {
                view.setReadOnly(false);
                currentReadOnly = false;
            }
            if (archive != attributes.isArchive()) {
                view.setArchive(archive);
            }
            if (hidden != attributes.isHidden()) {
                view.setHidden(hidden);
            }
            if (system != attributes.isSystem()) {
                view.setSystem(system);
            }
            if (readOnly != currentReadOnly) {
                view.setReadOnly(readOnly);
            }
        }

        static void clearReadOnlyIfNeeded(DosFileAttributeView view) throws IOException {
            if (view.readAttributes().isReadOnly()) {
                view.setReadOnly(false);
            }
        }

        void reconcile(Path path) throws IOException {
            if (!applicable) {
                return;
            }
            try {
                if (posix != null) {
                    PosixFileAttributeView view = requiredView(
                            path,
                            PosixFileAttributeView.class);
                    applyPosixIfNeeded(
                            posix.owner(),
                            posix.group(),
                            posix.permissions(),
                            view);
                }
                if (acl != null) {
                    AclFileAttributeView view = requiredView(
                            path,
                            AclFileAttributeView.class);
                    applyAclIfNeeded(acl.owner(), acl.entries(), view);
                }
                if (dos != null) {
                    DosFileAttributeView view = requiredView(
                            path,
                            DosFileAttributeView.class);
                    applyDosIfNeeded(
                            dos.readOnly(),
                            dos.hidden(),
                            dos.system(),
                            dos.archive(),
                            view);
                }
            } catch (MetadataUnsupportedException
                     | MetadataLimitExceededException
                     | MetadataFailureException e) {
                throw e;
            } catch (UnsupportedOperationException e) {
                throw new MetadataUnsupportedException();
            } catch (IOException | RuntimeException e) {
                throw new MetadataFailureException();
            }
        }

        void verify(Path path) throws IOException {
            if (!applicable) {
                return;
            }
            try {
                if (posix != null) {
                    PosixFileAttributes attributes = requiredView(
                            path,
                            PosixFileAttributeView.class).readAttributes();
                    if (!samePrincipal(posix.owner(), attributes.owner())
                            || !samePrincipal(posix.group(), attributes.group())
                            || !posix.permissions().equals(attributes.permissions())) {
                        throw new MetadataFailureException();
                    }
                }
                if (acl != null) {
                    AclFileAttributeView view = requiredView(
                            path,
                            AclFileAttributeView.class);
                    if (!samePrincipal(acl.owner(), view.getOwner())
                            || !acl.entries().equals(view.getAcl())) {
                        throw new MetadataFailureException();
                    }
                }
                if (dos != null) {
                    DosFileAttributes attributes = requiredView(
                            path,
                            DosFileAttributeView.class).readAttributes();
                    if (dos.readOnly() != attributes.isReadOnly()
                            || dos.hidden() != attributes.isHidden()
                            || dos.system() != attributes.isSystem()
                            || dos.archive() != attributes.isArchive()) {
                        throw new MetadataFailureException();
                    }
                }
                if (userDefined != null) {
                    UserDefinedFileAttributeView view = requiredView(
                            path,
                            UserDefinedFileAttributeView.class);
                    userDefined.verify(view);
                }
            } catch (MetadataUnsupportedException
                     | MetadataLimitExceededException
                     | MetadataFailureException e) {
                throw e;
            } catch (UnsupportedOperationException e) {
                throw new MetadataUnsupportedException();
            } catch (IOException | RuntimeException e) {
                throw new MetadataFailureException();
            }
        }

        private MetadataStatus status(Object snapshot) {
            if (!applicable) {
                return MetadataStatus.NOT_APPLICABLE;
            }
            return snapshot == null ? MetadataStatus.UNSUPPORTED : MetadataStatus.PRESERVED;
        }

        private static <V extends java.nio.file.attribute.FileAttributeView> V requiredView(
                Path path,
                Class<V> type
        ) throws MetadataUnsupportedException {
            V view = Files.getFileAttributeView(path, type, NO_FOLLOW);
            if (view == null) {
                throw new MetadataUnsupportedException();
            }
            return view;
        }

        static boolean samePrincipal(UserPrincipal expected, UserPrincipal actual) {
            return Objects.equals(expected, actual);
        }
    }

    private static final class MetadataUnsupportedException extends IOException { }

    private static final class MetadataLimitExceededException extends IOException { }

    private static final class MetadataFailureException extends IOException { }

    private static final class ContentValidationException extends IOException { }
}
