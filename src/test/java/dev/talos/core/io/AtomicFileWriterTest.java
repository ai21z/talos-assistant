package dev.talos.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AtomicFileWriterTest {

    private static final byte[] ORIGINAL = "authoritative-before".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT =
            "replacement-content-long-enough-for-a-partial-write".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @EnumSource(value = AtomicFileWriter.FaultStage.class, names = {
            "AFTER_TEMP_CREATE",
            "AFTER_INITIAL_METADATA_APPLY",
            "AFTER_INITIAL_METADATA_FLUSH",
            "AFTER_PARTIAL_TEMP_WRITE",
            "AFTER_TEMP_FLUSH",
            "AFTER_METADATA_APPLY",
            "AFTER_METADATA_FLUSH",
            "AFTER_TEMP_VALIDATION",
            "BEFORE_PROMOTION"
    })
    void everyPrecommitFaultKeepsExistingTargetAndMetadataExactAndCleansTemp(
            AtomicFileWriter.FaultStage injectedStage,
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        configureDistinctMetadata(target);
        Map<String, Object> metadataBefore = securityMetadata(target);

        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            assertEquals(directory, ignoredTemp.getParent(), "temp must be a sibling of the target");
            if (stage == injectedStage) {
                throw new IOException("injected precommit failure");
            }
        });
        AtomicFileWriter.Preimage expected = writer.capture(target);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, expected));

        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertEquals(metadataBefore, securityMetadata(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void newFileRetainsTheProvidersDefaultSecurityPolicy(@TempDir Path directory) throws Exception {
        Path control = directory.resolve("normal-create-control.txt");
        Files.createFile(control);
        Map<String, Object> expectedMetadata = securityMetadata(control);
        Path target = directory.resolve("atomic-create.txt");
        AtomicFileWriter writer = new AtomicFileWriter();

        writer.replace(target, ORIGINAL, AtomicFileWriter.Preimage.absent());

        assertEquals(expectedMetadata, securityMetadata(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void partialTempWriteFailureLeavesAbsentTargetAbsent(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("new-file.txt");
        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.AFTER_PARTIAL_TEMP_WRITE) {
                throw new IOException("injected partial write failure");
            }
        });

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, AtomicFileWriter.Preimage.absent()));

        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertFalse(Files.exists(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void injectedContentWriterFailureLeavesOriginalExactAndCleansTemp(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.bin");
        Files.write(target, ORIGINAL);
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicInteger writes = new AtomicInteger();
        AtomicFileWriter.Operations operations = system.withContentWriter((channel, source) -> {
            if (writes.incrementAndGet() == 2) {
                throw new IOException("injected content write failure");
            }
            system.contentWriter().write(channel, source);
        });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(2, writes.get(), "the actual content-write seam must be invoked");
        assertEquals(AtomicFileWriter.FailureReason.TEMP_IO, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void secondFileForceFailureAfterMetadataApplicationFailsBeforeCommit(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        configureDistinctMetadata(target);
        Map<String, Object> metadataBefore = securityMetadata(target);
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicInteger forceCalls = new AtomicInteger();
        AtomicFileWriter.Operations operations =
                system.withFileSynchronizer(channel -> {
                    int call = forceCalls.incrementAndGet();
                    if (call == 2) {
                        throw new IOException("injected metadata durability failure");
                    }
                    system.fileSynchronizer().force(channel);
                });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(2, forceCalls.get(), "content and applied metadata must each be forced");
        assertEquals(AtomicFileWriter.FailureReason.TEMP_IO, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertEquals(metadataBefore, securityMetadata(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void finalMetadataForceFailureAfterContentStillFailsBeforeCommit(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        configureDistinctMetadata(target);
        Map<String, Object> metadataBefore = securityMetadata(target);
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicInteger forceCalls = new AtomicInteger();
        AtomicFileWriter.Operations operations =
                system.withFileSynchronizer(channel -> {
                    int call = forceCalls.incrementAndGet();
                    if (call == 3) {
                        throw new IOException("injected final metadata durability failure");
                    }
                    system.fileSynchronizer().force(channel);
                });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(3, forceCalls.get(),
                "initial metadata, content, and final metadata must each be forced");
        assertEquals(AtomicFileWriter.FailureReason.TEMP_IO, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertEquals(metadataBefore, securityMetadata(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void postPromotionMetadataForceFailureIsCommittedUnverified(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicInteger forceCalls = new AtomicInteger();
        AtomicFileWriter.Operations operations =
                system.withFileSynchronizer(channel -> {
                    int call = forceCalls.incrementAndGet();
                    if (call == 4) {
                        throw new IOException("injected post-promotion metadata force failure");
                    }
                    system.fileSynchronizer().force(channel);
                });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(4, forceCalls.get());
        assertEquals(
                AtomicFileWriter.FailureReason.POST_COMMIT_VERIFICATION_FAILED,
                failure.reason());
        assertEquals(
                AtomicFileWriter.CommitState.COMMITTED_UNVERIFIED,
                failure.commitState());
        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void metadataForceFailurePreventsReplacementContentFromBeingStaged(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        configureDistinctMetadata(target);
        Map<String, Object> metadataBefore = securityMetadata(target);
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger forceCalls = new AtomicInteger();
        AtomicFileWriter.Operations operations = system
                .withContentWriter((channel, source) -> {
                    writes.incrementAndGet();
                    system.contentWriter().write(channel, source);
                })
                .withFileSynchronizer(channel -> {
                    forceCalls.incrementAndGet();
                    throw new IOException("injected metadata durability failure");
                });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(1, forceCalls.get(), "applied metadata must be forced before content");
        assertEquals(0, writes.get(), "replacement content must not precede restrictive metadata");
        assertEquals(AtomicFileWriter.FailureReason.TEMP_IO, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertEquals(metadataBefore, securityMetadata(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void injectedTempValidationFailurePreventsPromotion(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        AtomicInteger validations = new AtomicInteger();
        AtomicFileWriter.Operations operations = AtomicFileWriter.Operations.system()
                .withContentValidator((path, length, sha256) -> {
                    validations.incrementAndGet();
                    throw new IOException("injected content validation failure");
                });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(1, validations.get(), "the actual validation seam must be invoked");
        assertEquals(AtomicFileWriter.FailureReason.TEMP_VALIDATION_FAILED, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void sameLengthTempCorruptionIsRejectedByTheSystemHashValidator(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.bin");
        Files.write(target, ORIGINAL);
        byte[] corrupted = REPLACEMENT.clone();
        corrupted[corrupted.length / 2] ^= 0x01;
        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, temp) -> {
            if (stage == AtomicFileWriter.FaultStage.AFTER_TEMP_FLUSH) {
                Files.write(temp, corrupted);
            }
        });

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(
                AtomicFileWriter.FailureReason.TEMP_VALIDATION_FAILED,
                failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void metadataApplyFailureKeepsOriginalBytesAndMetadataExact(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        configureDistinctMetadata(target);
        Map<String, Object> metadataBefore = securityMetadata(target);
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicInteger writes = new AtomicInteger();
        AtomicFileWriter.MetadataOperations metadata = system.metadataOperations();
        AtomicFileWriter.MetadataOperations failingMetadata =
                new DelegatingMetadataOperations(metadata) {
                    @Override
                    public void apply(
                            AtomicFileWriter.SecurityMetadata snapshot,
                            Path path
                    ) throws IOException {
                        super.apply(snapshot, path);
                        throw new IOException("injected metadata apply failure");
                    }
                };
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                system.withMetadataOperations(failingMetadata)
                        .withContentWriter((channel, source) -> {
                            writes.incrementAndGet();
                            system.contentWriter().write(channel, source);
                        }));

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(AtomicFileWriter.FailureReason.METADATA_FAILED, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertEquals(0, writes.get(),
                "metadata must be applied before replacement content is staged");
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertEquals(metadataBefore, securityMetadata(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void metadataVerifyFailureKeepsOriginalBytesAndMetadataExact(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        configureDistinctMetadata(target);
        Map<String, Object> metadataBefore = securityMetadata(target);
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicInteger writes = new AtomicInteger();
        AtomicFileWriter.MetadataOperations metadata = system.metadataOperations();
        AtomicFileWriter.MetadataOperations failingMetadata =
                new DelegatingMetadataOperations(metadata) {
                    @Override
                    public void verify(
                            AtomicFileWriter.SecurityMetadata snapshot,
                            Path path
                    ) throws IOException {
                        super.verify(snapshot, path);
                        throw new IOException("injected metadata verification failure");
                    }
                };
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                system.withMetadataOperations(failingMetadata)
                        .withContentWriter((channel, source) -> {
                            writes.incrementAndGet();
                            system.contentWriter().write(channel, source);
                        }));

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(AtomicFileWriter.FailureReason.METADATA_FAILED, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertEquals(0, writes.get(),
                "metadata must be verified before replacement content is staged");
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertEquals(metadataBefore, securityMetadata(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void realMetadataMismatchIsRejectedBeforeReplacementContentIsWritten(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        AtomicInteger writes = new AtomicInteger();
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicFileWriter.Operations operations =
                system.withContentWriter((channel, source) -> {
                    writes.incrementAndGet();
                    system.contentWriter().write(channel, source);
                });
        AtomicFileWriter writer = new AtomicFileWriter(
                (stage, ignoredTarget, temp) -> {
                    if (stage == AtomicFileWriter.FaultStage.AFTER_INITIAL_METADATA_FLUSH) {
                        MetadataMutation mutation = metadataMutation(temp);
                        if (mutation == null) {
                            throw new IOException("no mutable metadata view");
                        }
                        mutation.apply().run();
                    }
                },
                operations);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(AtomicFileWriter.FailureReason.METADATA_FAILED, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertEquals(0, writes.get(), "mismatched metadata must stop content staging");
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void unsupportedRequiredMetadataFailsClosed(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        Map<String, Object> metadataBefore = securityMetadata(target);
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicFileWriter.MetadataOperations metadata = system.metadataOperations();
        AtomicFileWriter.MetadataOperations unsupportedMetadata =
                new DelegatingMetadataOperations(metadata) {
                    @Override
                    public void apply(
                            AtomicFileWriter.SecurityMetadata snapshot,
                            Path path
                    ) {
                        throw new UnsupportedOperationException(
                                "injected unsupported metadata view");
                    }
                };
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                system.withMetadataOperations(unsupportedMetadata));

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(AtomicFileWriter.FailureReason.METADATA_UNSUPPORTED, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertEquals(metadataBefore, securityMetadata(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void durabilityOperationsRunInTheRequiredOrder(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        List<String> order = new ArrayList<>();
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicInteger applyCalls = new AtomicInteger();
        AtomicInteger forceCalls = new AtomicInteger();
        AtomicInteger targetValidationCalls = new AtomicInteger();
        AtomicInteger targetVerifyCalls = new AtomicInteger();
        AtomicInteger tempVerifyCalls = new AtomicInteger();
        AtomicFileWriter.MetadataOperations metadata = system.metadataOperations();
        AtomicFileWriter.MetadataOperations orderedMetadata =
                new DelegatingMetadataOperations(metadata) {
                    @Override
                    public void apply(
                            AtomicFileWriter.SecurityMetadata snapshot,
                            Path path
                    ) throws IOException {
                        order.add("metadata-apply-" + applyCalls.incrementAndGet());
                        super.apply(snapshot, path);
                    }

                    @Override
                    public void reconcile(
                            AtomicFileWriter.SecurityMetadata snapshot,
                            Path path
                    ) throws IOException {
                        order.add("metadata-reconcile-final");
                        super.reconcile(snapshot, path);
                    }

                    @Override
                    public void verify(
                            AtomicFileWriter.SecurityMetadata snapshot,
                            Path path
                    ) throws IOException {
                        order.add(path.equals(target) ? "metadata-verify-target-"
                                + targetVerifyCalls.incrementAndGet()
                                : "metadata-verify-temp-"
                                        + tempVerifyCalls.incrementAndGet());
                        super.verify(snapshot, path);
                    }
                };
        AtomicFileWriter.Operations operations = system
                .withContentWriter((channel, source) -> {
                    order.add("write");
                    system.contentWriter().write(channel, source);
                })
                .withFileSynchronizer(channel -> {
                    order.add("force-" + forceCalls.incrementAndGet());
                    system.fileSynchronizer().force(channel);
                })
                .withMetadataOperations(orderedMetadata)
                .withContentValidator((path, length, sha256) -> {
                    order.add(path.equals(target) ? "content-validate-final-"
                            + targetValidationCalls.incrementAndGet()
                            : "content-validate-temp");
                    system.contentValidator().validate(path, length, sha256);
                })
                .withAtomicMover((source, destination) -> {
                    order.add("atomic-move");
                    system.atomicMover().move(source, destination);
                })
                .withDirectorySynchronizer(syncedDirectory -> {
                    order.add("directory-sync");
                    return AtomicFileWriter.DirectorySyncStatus.SYNCED;
                });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        writer.replace(target, REPLACEMENT, writer.capture(target));

        assertBefore(order, "metadata-apply-1", "force-1");
        assertBefore(order, "force-1", "metadata-verify-temp-1");
        assertBefore(order, "metadata-verify-temp-1", "write");
        assertBefore(order, "write", "force-2");
        assertBefore(order, "force-2", "metadata-apply-2");
        assertBefore(order, "metadata-apply-2", "force-3");
        assertBefore(order, "force-3", "metadata-verify-temp-2");
        assertBefore(order, "metadata-verify-temp-2", "content-validate-temp");
        assertBefore(order, "content-validate-temp", "metadata-verify-temp-3");
        assertBefore(order, "metadata-verify-temp-3", "metadata-verify-target-1");
        assertBefore(order, "metadata-verify-target-1", "atomic-move");
        assertBefore(order, "atomic-move", "content-validate-final-1");
        assertBefore(order, "content-validate-final-1", "metadata-reconcile-final");
        assertBefore(order, "metadata-reconcile-final", "force-4");
        assertBefore(order, "force-4", "metadata-verify-target-2");
        assertBefore(order, "metadata-verify-target-2", "directory-sync");
        assertBefore(order, "directory-sync", "content-validate-final-2");
        assertBefore(order, "content-validate-final-2", "metadata-verify-target-3");
    }

    @Test
    void productionFileSynchronizerRequestsMetadataDurability() throws Exception {
        AtomicBoolean metadata = new AtomicBoolean();
        AtomicFileWriter.FileSynchronizer synchronizer =
                AtomicFileWriter.metadataForcing((ignoredChannel, requestedMetadata) ->
                        metadata.set(requestedMetadata));

        synchronizer.force(null);

        assertTrue(metadata.get(), "FileChannel.force must be invoked with metadata=true");
    }

    @Test
    void productionMoverRequestsAtomicReplacementOptions() throws Exception {
        List<CopyOption> observed = new ArrayList<>();
        AtomicFileWriter.AtomicMover mover = AtomicFileWriter.atomicMover(
                (source, target, options) -> observed.addAll(List.of(options)));

        mover.move(Path.of("source"), Path.of("target"));

        assertEquals(
                Set.of(
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING),
                Set.copyOf(observed));
        assertEquals(2, observed.size(), "no fallback or extra move option is permitted");
    }

    @Test
    void unsupportedAtomicMoveFailsClosedWithoutPlainMoveFallback(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        AtomicFileWriter writer = new AtomicFileWriter(
                (stage, ignoredTarget, ignoredTemp) -> { },
                (source, destination) -> {
                    throw new AtomicMoveNotSupportedException(
                            "redacted-source",
                            "redacted-target",
                            "injected unsupported atomic move");
                },
                ignoredDirectory -> AtomicFileWriter.DirectorySyncStatus.UNSUPPORTED);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(AtomicFileWriter.FailureReason.ATOMIC_MOVE_UNSUPPORTED, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void readOnlyExistingLeafRetainsDirectWriteAuthorizationSemantics(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        MetadataMutation readOnly = makeReadOnly(target);
        assumeTrue(readOnly != null, "no supported read-only metadata view");
        readOnly.apply().run();

        try {
            AtomicFileWriter writer = new AtomicFileWriter();
            AtomicFileWriter.AtomicWriteException failure = assertThrows(
                    AtomicFileWriter.AtomicWriteException.class,
                    () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

            assertEquals(AtomicFileWriter.FailureReason.SOURCE_IO, failure.reason());
            assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
            assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
            assertNoAtomicTemps(directory);
        } finally {
            readOnly.restore().run();
        }
    }

    @Test
    void changedPreimageImmediatelyBeforePromotionIsRejectedWithoutOverwrite(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        byte[] concurrent = "concurrent-writer-wins".getBytes(StandardCharsets.UTF_8);
        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.BEFORE_PREIMAGE_RECHECK) {
                Files.write(target, concurrent);
            }
        });
        AtomicFileWriter.Preimage expected = writer.capture(target);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, expected));

        assertEquals(AtomicFileWriter.FailureReason.STALE_PREIMAGE, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(concurrent, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void changeAtTheClosestPrepromotionSeamIsRejectedWithoutOverwrite(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        byte[] concurrent = "closest-seam-concurrent-write".getBytes(StandardCharsets.UTF_8);
        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION) {
                Files.write(target, concurrent);
            }
        });
        AtomicFileWriter.Preimage expected = writer.capture(target);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, expected));

        assertEquals(AtomicFileWriter.FailureReason.STALE_PREIMAGE, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(concurrent, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void sameLengthDifferentBytePreimageIsRejectedBySha256(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.bin");
        Files.write(target, ORIGINAL);
        byte[] concurrent = ORIGINAL.clone();
        concurrent[0] ^= 0x01;
        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION) {
                Files.write(target, concurrent);
            }
        });
        AtomicFileWriter.Preimage expected = writer.capture(target);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, expected));

        assertEquals(AtomicFileWriter.FailureReason.STALE_PREIMAGE, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(concurrent, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void concurrentMetadataOnlyChangeIsRejectedWithoutWeakeningIt(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        MetadataMutation mutation = metadataMutation(target);
        assumeTrue(mutation != null, "no mutable security metadata view is supported");
        Map<String, Object> metadataBefore = securityMetadata(target);
        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION) {
                mutation.apply().run();
            }
        });
        AtomicFileWriter.Preimage expected = writer.capture(target);

        try {
            AtomicFileWriter.AtomicWriteException failure = assertThrows(
                    AtomicFileWriter.AtomicWriteException.class,
                    () -> writer.replace(target, REPLACEMENT, expected));

            assertEquals(AtomicFileWriter.FailureReason.METADATA_FAILED, failure.reason());
            assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
            assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
            assertNotEquals(metadataBefore, securityMetadata(target),
                    "the concurrent metadata update must remain live");
            assertNoAtomicTemps(directory);
        } finally {
            mutation.restore().run();
        }
    }

    @Test
    void absentPreimageThatBecomesPresentIsRejectedWithoutOverwrite(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("new-primary.txt");
        byte[] concurrent = "created-by-concurrent-writer".getBytes(StandardCharsets.UTF_8);
        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION) {
                Files.write(target, concurrent);
            }
        });

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(
                        target,
                        REPLACEMENT,
                        AtomicFileWriter.Preimage.absent()));

        assertEquals(AtomicFileWriter.FailureReason.STALE_PREIMAGE, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(concurrent, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void presentPreimageThatBecomesAbsentIsRejectedWithoutRecreation(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION) {
                Files.delete(target);
            }
        });
        AtomicFileWriter.Preimage expected = writer.capture(target);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, expected));

        assertEquals(AtomicFileWriter.FailureReason.STALE_PREIMAGE, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertFalse(Files.exists(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void cooperativeWritersCannotBothCommitFromTheSamePreimage(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        AtomicFileWriter baseline = new AtomicFileWriter();
        AtomicFileWriter.Preimage expected = baseline.capture(target);
        CountDownLatch bothPrepared = new CountDownLatch(2);
        CountDownLatch releasePromotion = new CountDownLatch(1);
        AtomicFileWriter.FaultInjector barrier = (stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION) {
                bothPrepared.countDown();
                try {
                    if (!releasePromotion.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("timed out waiting to release promotion");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while awaiting promotion", e);
                }
            }
        };
        AtomicFileWriter first = new AtomicFileWriter(barrier);
        AtomicFileWriter second = new AtomicFileWriter(barrier);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();

        Thread firstThread = Thread.ofPlatform().start(
                () -> replaceAndClassify(first, target, "first".getBytes(StandardCharsets.UTF_8),
                        expected, committed, stale));
        Thread secondThread = Thread.ofPlatform().start(
                () -> replaceAndClassify(second, target, "second".getBytes(StandardCharsets.UTF_8),
                        expected, committed, stale));
        assertTrue(bothPrepared.await(10, TimeUnit.SECONDS), "both writers must stage before release");
        releasePromotion.countDown();
        firstThread.join(10_000);
        secondThread.join(10_000);

        assertFalse(firstThread.isAlive(), "first writer must finish");
        assertFalse(secondThread.isAlive(), "second writer must finish");
        assertEquals(1, committed.get(), "exactly one writer may commit");
        assertEquals(1, stale.get(), "the second writer must reject the stale preimage");
        assertNoAtomicTemps(directory);
    }

    @Test
    void successfulCreateAndOverwritePublishExactBytesWithExplicitMetadataStatus(
            @TempDir Path directory
    ) throws Exception {
        AtomicFileWriter writer = new AtomicFileWriter();
        Path target = directory.resolve("primary.txt");

        AtomicFileWriter.WriteOutcome create = writer.replace(
                target,
                ORIGINAL,
                AtomicFileWriter.Preimage.absent());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertEquals(AtomicFileWriter.MetadataStatus.NOT_APPLICABLE, create.posixMetadata());
        assertEquals(AtomicFileWriter.MetadataStatus.NOT_APPLICABLE, create.aclMetadata());
        assertEquals(AtomicFileWriter.MetadataStatus.NOT_APPLICABLE, create.dosMetadata());
        assertEquals(
                AtomicFileWriter.MetadataStatus.NOT_APPLICABLE,
                create.userDefinedMetadata());
        assertNotNull(create.directorySync());

        configureDistinctMetadata(target);
        Map<String, Object> metadataBefore = securityMetadata(target);
        AtomicFileWriter.WriteOutcome overwrite =
                writer.replace(target, REPLACEMENT, writer.capture(target));

        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target));
        assertEquals(metadataBefore, securityMetadata(target));
        assertEquals(
                supports(target, PosixFileAttributeView.class)
                        ? AtomicFileWriter.MetadataStatus.PRESERVED
                        : AtomicFileWriter.MetadataStatus.UNSUPPORTED,
                overwrite.posixMetadata());
        assertEquals(
                supports(target, AclFileAttributeView.class)
                        ? AtomicFileWriter.MetadataStatus.PRESERVED
                        : AtomicFileWriter.MetadataStatus.UNSUPPORTED,
                overwrite.aclMetadata());
        assertEquals(
                supports(target, DosFileAttributeView.class)
                        ? AtomicFileWriter.MetadataStatus.PRESERVED
                        : AtomicFileWriter.MetadataStatus.UNSUPPORTED,
                overwrite.dosMetadata());
        assertEquals(
                supports(target, UserDefinedFileAttributeView.class)
                        ? AtomicFileWriter.MetadataStatus.PRESERVED
                        : AtomicFileWriter.MetadataStatus.UNSUPPORTED,
                overwrite.userDefinedMetadata());
        assertNoAtomicTemps(directory);
    }

    @Test
    void successfulOverwritePreservesExactUserDefinedAttributeNamesAndBytes(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        UserDefinedFileAttributeView userDefined = Files.getFileAttributeView(
                target,
                UserDefinedFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        assumeTrue(userDefined != null, "user-defined attributes are unsupported");
        writeUserDefinedAttribute(userDefined, "talos_binary_probe", new byte[] {
                0, 1, 2, 0x7f, (byte) 0x80, (byte) 0xff
        });
        writeUserDefinedAttribute(
                userDefined,
                "Zone.Identifier",
                "[ZoneTransfer]\r\nZoneId=3\r\n".getBytes(StandardCharsets.UTF_8));
        writeUserDefinedAttribute(userDefined, "talos_empty_probe", new byte[0]);
        Map<String, String> before = userDefinedAttributes(target);
        assertTrue(before.containsKey("talos_empty_probe"));

        new AtomicFileWriter().replace(
                target,
                REPLACEMENT,
                AtomicFileWriter.Preimage.present(sha256(ORIGINAL)));

        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target));
        assertEquals(before, userDefinedAttributes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void concurrentUserDefinedAttributeChangeRejectsWithoutOverwritingEitherState(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        UserDefinedFileAttributeView userDefined = Files.getFileAttributeView(
                target,
                UserDefinedFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        assumeTrue(userDefined != null, "user-defined attributes are unsupported");
        byte[] initial = "metadata-before".getBytes(StandardCharsets.UTF_8);
        byte[] concurrent = "metadata-after!".getBytes(StandardCharsets.UTF_8);
        assertEquals(initial.length, concurrent.length);
        writeUserDefinedAttribute(userDefined, "talos_concurrent_probe", initial);

        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION) {
                UserDefinedFileAttributeView current = Files.getFileAttributeView(
                        target,
                        UserDefinedFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (current == null) {
                    throw new IOException("user-defined attribute view disappeared");
                }
                writeUserDefinedAttribute(current, "talos_concurrent_probe", concurrent);
            }
        });

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(AtomicFileWriter.FailureReason.METADATA_FAILED, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertArrayEquals(
                concurrent,
                readUserDefinedAttribute(
                        Files.getFileAttributeView(
                                target,
                                UserDefinedFileAttributeView.class,
                                LinkOption.NOFOLLOW_LINKS),
                        "talos_concurrent_probe"));
        assertNoAtomicTemps(directory);
    }

    @Test
    void userDefinedPerAttributeLimitAcceptsBoundaryAndRejectsOneByteOver()
            throws Exception {
        byte[] exact =
                new byte[AtomicFileWriter.MAX_USER_DEFINED_ATTRIBUTE_VALUE_BYTES];
        exact[0] = 1;
        exact[exact.length - 1] = 2;
        StubUserDefinedView boundary = new StubUserDefinedView();
        boundary.attributes.put("boundary", exact);

        assertNotNull(AtomicFileWriter.UserDefinedSnapshot.capture(boundary));

        StubUserDefinedView oversized = new StubUserDefinedView() {
            @Override
            public int size(String name) {
                return AtomicFileWriter.MAX_USER_DEFINED_ATTRIBUTE_VALUE_BYTES + 1;
            }

            @Override
            public int read(String name, ByteBuffer destination) {
                throw new AssertionError("oversized values must be rejected before allocation");
            }
        };
        oversized.attributes.put("oversized", new byte[0]);

        assertThrows(
                IOException.class,
                () -> AtomicFileWriter.UserDefinedSnapshot.capture(oversized));
    }

    @Test
    void userDefinedAggregateLimitAcceptsBoundaryAndRejectsOneByteOver()
            throws Exception {
        StubUserDefinedView boundary = new StubUserDefinedView();
        int attributeCount = AtomicFileWriter.MAX_USER_DEFINED_AGGREGATE_VALUE_BYTES
                / AtomicFileWriter.MAX_USER_DEFINED_ATTRIBUTE_VALUE_BYTES;
        for (int index = 0; index < attributeCount; index++) {
            boundary.attributes.put(
                    "attribute-" + index,
                    new byte[AtomicFileWriter.MAX_USER_DEFINED_ATTRIBUTE_VALUE_BYTES]);
        }

        assertNotNull(AtomicFileWriter.UserDefinedSnapshot.capture(boundary));

        boundary.attributes.put("aggregate-overflow", new byte[] { 1 });
        assertThrows(
                IOException.class,
                () -> AtomicFileWriter.UserDefinedSnapshot.capture(boundary));
    }

    @Test
    void userDefinedAttributeCountLimitAcceptsBoundaryAndRejectsOneOver()
            throws Exception {
        StubUserDefinedView boundary = new StubUserDefinedView();
        for (int index = 0;
                index < AtomicFileWriter.MAX_USER_DEFINED_ATTRIBUTES;
                index++) {
            boundary.attributes.put("attribute-" + index, new byte[0]);
        }

        assertNotNull(AtomicFileWriter.UserDefinedSnapshot.capture(boundary));

        boundary.attributes.put("attribute-overflow", new byte[0]);
        assertThrows(
                IOException.class,
                () -> AtomicFileWriter.UserDefinedSnapshot.capture(boundary));
    }

    @Test
    void userDefinedNameLimitAcceptsBoundaryAndRejectsOneByteOver() throws Exception {
        StubUserDefinedView boundary = new StubUserDefinedView();
        boundary.attributes.put(
                "n".repeat(AtomicFileWriter.MAX_USER_DEFINED_NAME_BYTES),
                new byte[0]);

        assertNotNull(AtomicFileWriter.UserDefinedSnapshot.capture(boundary));

        StubUserDefinedView oversized = new StubUserDefinedView();
        oversized.attributes.put(
                "n".repeat(AtomicFileWriter.MAX_USER_DEFINED_NAME_BYTES + 1),
                new byte[0]);
        assertThrows(
                IOException.class,
                () -> AtomicFileWriter.UserDefinedSnapshot.capture(oversized));
    }

    @Test
    void userDefinedCaptureRejectsAnIncompleteRead() {
        StubUserDefinedView incomplete = new StubUserDefinedView() {
            @Override
            public int read(String name, ByteBuffer destination) {
                byte[] value = attributes.get(name);
                int partial = Math.max(0, value.length - 1);
                destination.put(value, 0, partial);
                return partial;
            }
        };
        incomplete.attributes.put("sensitive-name", new byte[] { 1, 2, 3 });

        assertThrows(
                IOException.class,
                () -> AtomicFileWriter.UserDefinedSnapshot.capture(incomplete));
    }

    @Test
    void userDefinedCaptureRejectsSizeDisagreementBeforeAndAfterRead() {
        StubUserDefinedView changingSize = new StubUserDefinedView() {
            private int sizeCalls;

            @Override
            public int size(String name) throws IOException {
                sizeCalls++;
                return sizeCalls == 1 ? super.size(name) : super.size(name) + 1;
            }
        };
        changingSize.attributes.put("changing-size", new byte[] { 1, 2, 3 });

        assertThrows(
                IOException.class,
                () -> AtomicFileWriter.UserDefinedSnapshot.capture(changingSize));
    }

    @Test
    void userDefinedCaptureRejectsANameSetThatChangesDuringCapture() {
        StubUserDefinedView changing = new StubUserDefinedView() {
            private int listCalls;

            @Override
            public List<String> list() {
                listCalls++;
                if (listCalls == 2) {
                    attributes.put("arrived-during-capture", new byte[] { 2 });
                }
                return super.list();
            }
        };
        changing.attributes.put("initial", new byte[] { 1 });

        assertThrows(
                IOException.class,
                () -> AtomicFileWriter.UserDefinedSnapshot.capture(changing));
    }

    @Test
    void userDefinedApplicationRemovesUnexpectedAttributesAndPreservesExactValues()
            throws Exception {
        StubUserDefinedView source = new StubUserDefinedView();
        source.attributes.put("binary", new byte[] { 0, (byte) 0xff, 1 });
        source.attributes.put("text", "expected".getBytes(StandardCharsets.UTF_8));
        AtomicFileWriter.UserDefinedSnapshot snapshot =
                AtomicFileWriter.UserDefinedSnapshot.capture(source);
        StubUserDefinedView destination = new StubUserDefinedView();
        destination.attributes.put("binary", new byte[] { 9 });
        destination.attributes.put("unexpected", new byte[] { 8 });

        snapshot.applyExact(destination);

        assertEquals(snapshot, AtomicFileWriter.UserDefinedSnapshot.capture(destination));
        assertFalse(destination.attributes.containsKey("unexpected"));
    }

    @Test
    void userDefinedApplicationDoesNotRewriteAnExactDestination() throws Exception {
        StubUserDefinedView exact = new StubUserDefinedView();
        exact.attributes.put("binary", new byte[] { 0, (byte) 0xff, 1 });
        AtomicFileWriter.UserDefinedSnapshot snapshot =
                AtomicFileWriter.UserDefinedSnapshot.capture(exact);

        snapshot.applyExact(exact);

        assertEquals(0, exact.writes.get());
        assertEquals(0, exact.deletes.get());
    }

    @Test
    void userDefinedApplicationRejectsAnIncompleteWrite() throws Exception {
        StubUserDefinedView source = new StubUserDefinedView();
        source.attributes.put("binary", new byte[] { 1, 2, 3 });
        AtomicFileWriter.UserDefinedSnapshot snapshot =
                AtomicFileWriter.UserDefinedSnapshot.capture(source);
        StubUserDefinedView incomplete = new StubUserDefinedView() {
            @Override
            public int write(String name, ByteBuffer source) {
                int partial = Math.max(0, source.remaining() - 1);
                byte[] value = new byte[partial];
                source.get(value);
                attributes.put(name, value);
                return partial;
            }
        };

        assertThrows(IOException.class, () -> snapshot.applyExact(incomplete));
    }

    @Test
    void userDefinedApplicationRejectsFullLengthWrongReadback() throws Exception {
        StubUserDefinedView source = new StubUserDefinedView();
        source.attributes.put("binary", new byte[] { 1, 2, 3 });
        AtomicFileWriter.UserDefinedSnapshot snapshot =
                AtomicFileWriter.UserDefinedSnapshot.capture(source);
        StubUserDefinedView corrupting = new StubUserDefinedView() {
            @Override
            public int write(String name, ByteBuffer source) {
                byte[] value = new byte[source.remaining()];
                source.get(value);
                value[0] ^= 0x01;
                attributes.put(name, value);
                return value.length;
            }
        };

        assertThrows(IOException.class, () -> snapshot.applyExact(corrupting));
    }

    @Test
    void disappearingUserDefinedViewFailsClosedWithOriginalStateExact(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        UserDefinedFileAttributeView userDefined = Files.getFileAttributeView(
                target,
                UserDefinedFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        assumeTrue(userDefined != null, "user-defined attributes are unsupported");
        writeUserDefinedAttribute(
                userDefined,
                "talos_view_disappearance_probe",
                new byte[] { 0, 1, (byte) 0xff });
        Map<String, String> attributesBefore = userDefinedAttributes(target);
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicFileWriter.MetadataOperations metadata = system.metadataOperations();
        AtomicFileWriter.MetadataOperations disappearing =
                new DelegatingMetadataOperations(metadata) {
                    @Override
                    public void apply(
                            AtomicFileWriter.SecurityMetadata snapshot,
                            Path path
                    ) {
                        throw new UnsupportedOperationException(
                                "user-defined attribute view disappeared");
                    }
                };
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                system.withMetadataOperations(disappearing));

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(AtomicFileWriter.FailureReason.METADATA_UNSUPPORTED, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertFalse(
                failure.getMessage().contains("talos_view_disappearance_probe"));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertEquals(attributesBefore, userDefinedAttributes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void oversizedRealUserDefinedAttributeFailsClosedWhenTheProviderPermitsIt(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        UserDefinedFileAttributeView userDefined = Files.getFileAttributeView(
                target,
                UserDefinedFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        assumeTrue(userDefined != null, "user-defined attributes are unsupported");
        byte[] oversized =
                new byte[AtomicFileWriter.MAX_USER_DEFINED_ATTRIBUTE_VALUE_BYTES + 1];
        oversized[0] = 1;
        oversized[oversized.length - 1] = 2;
        try {
            writeUserDefinedAttribute(userDefined, "talos_oversized_probe", oversized);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(
                    false,
                    "provider cannot create an oversized attribute: "
                            + e.getClass().getSimpleName());
            return;
        }
        Map<String, String> before = userDefinedAttributes(target);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> new AtomicFileWriter().replace(
                        target,
                        REPLACEMENT,
                        AtomicFileWriter.Preimage.present(sha256(ORIGINAL))));

        assertEquals(
                AtomicFileWriter.FailureReason.METADATA_LIMIT_EXCEEDED,
                failure.reason());
        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertFalse(failure.getMessage().contains("talos_oversized_probe"));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertEquals(before, userDefinedAttributes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void replacementOwnsOnlyTheNamedPathWhenTheOriginalHasAnotherHardLink(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Path alias = directory.resolve("alias.txt");
        Files.write(target, ORIGINAL);
        try {
            Files.createLink(alias, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "hard links are unavailable: " + e.getClass().getSimpleName());
            return;
        }
        assertTrue(Files.isSameFile(target, alias));

        new AtomicFileWriter().replace(
                target,
                REPLACEMENT,
                AtomicFileWriter.Preimage.present(sha256(ORIGINAL)));

        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(alias));
        assertFalse(Files.isSameFile(target, alias));
        assertNoAtomicTemps(directory);
    }

    @Test
    void matchingAclOwnerDoesNotRequireAWriteOwnerOperation() throws Exception {
        UserPrincipal owner = () -> "same-owner";
        List<AclEntry> entries = List.of();
        AtomicInteger ownerWrites = new AtomicInteger();
        AtomicInteger aclWrites = new AtomicInteger();
        AclFileAttributeView view = new AclFileAttributeView() {
            @Override
            public String name() {
                return "acl";
            }

            @Override
            public UserPrincipal getOwner() {
                return owner;
            }

            @Override
            public void setOwner(UserPrincipal ignored) throws IOException {
                ownerWrites.incrementAndGet();
                throw new java.nio.file.AccessDeniedException("redundant owner write denied");
            }

            @Override
            public List<AclEntry> getAcl() {
                return entries;
            }

            @Override
            public void setAcl(List<AclEntry> ignored) {
                aclWrites.incrementAndGet();
            }
        };

        AtomicFileWriter.SecurityMetadata.applyAclIfNeeded(owner, entries, view);

        assertEquals(0, ownerWrites.get(),
                "an already-matching owner must not require WRITE_OWNER permission");
        assertEquals(0, aclWrites.get(),
                "an already-matching ACL must not require a redundant metadata write");
    }

    @Test
    void sameRenderedNameDoesNotMakeDistinctPrincipalsEqual() throws Exception {
        UserPrincipal expected = () -> "shared-rendered-name";
        UserPrincipal actual = () -> "shared-rendered-name";
        AtomicReference<UserPrincipal> assignedOwner = new AtomicReference<>();
        AclFileAttributeView view = new AclFileAttributeView() {
            @Override
            public String name() {
                return "acl";
            }

            @Override
            public UserPrincipal getOwner() {
                return actual;
            }

            @Override
            public void setOwner(UserPrincipal owner) {
                assignedOwner.set(owner);
            }

            @Override
            public List<AclEntry> getAcl() {
                return List.of();
            }

            @Override
            public void setAcl(List<AclEntry> ignored) {
            }
        };

        assertFalse(AtomicFileWriter.SecurityMetadata.samePrincipal(expected, actual),
                "a rendered name is not a principal identity");
        AtomicFileWriter.SecurityMetadata.applyAclIfNeeded(expected, List.of(), view);

        assertEquals(expected, assignedOwner.get(),
                "a distinct principal must be repaired even when its name collides");
    }

    @Test
    void conditionalPosixApplicationSkipsMatchesAndWritesOnlyTheMismatch()
            throws Exception {
        UserPrincipal owner = () -> "owner";
        GroupPrincipal expectedGroup = () -> "expected-group";
        Set<PosixFilePermission> permissions =
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        StubPosixView view = new StubPosixView(owner, expectedGroup, permissions);

        AtomicFileWriter.SecurityMetadata.applyPosixIfNeeded(
                owner, expectedGroup, permissions, view);

        assertEquals(0, view.ownerWrites.get());
        assertEquals(0, view.groupWrites.get());
        assertEquals(0, view.permissionWrites.get());

        view.group = () -> "different-group";
        AtomicFileWriter.SecurityMetadata.applyPosixIfNeeded(
                owner, expectedGroup, permissions, view);

        assertEquals(0, view.ownerWrites.get());
        assertEquals(1, view.groupWrites.get());
        assertEquals(0, view.permissionWrites.get());
        assertEquals(expectedGroup, view.group);

        StubPosixView ownerMismatch =
                new StubPosixView(() -> "different-owner", expectedGroup, permissions);
        AtomicFileWriter.SecurityMetadata.applyPosixIfNeeded(
                owner, expectedGroup, permissions, ownerMismatch);
        assertEquals(1, ownerMismatch.ownerWrites.get());
        assertEquals(0, ownerMismatch.groupWrites.get());
        assertEquals(0, ownerMismatch.permissionWrites.get());
        assertEquals(owner, ownerMismatch.owner);

        Set<PosixFilePermission> differentPermissions =
                Set.of(PosixFilePermission.OWNER_READ);
        StubPosixView permissionMismatch =
                new StubPosixView(owner, expectedGroup, differentPermissions);
        AtomicFileWriter.SecurityMetadata.applyPosixIfNeeded(
                owner, expectedGroup, permissions, permissionMismatch);
        assertEquals(0, permissionMismatch.ownerWrites.get());
        assertEquals(0, permissionMismatch.groupWrites.get());
        assertEquals(1, permissionMismatch.permissionWrites.get());
        assertEquals(permissions, permissionMismatch.permissions);
    }

    @Test
    void conditionalDosApplicationSkipsMatchesAndWritesOnlyTheMismatch()
            throws Exception {
        StubDosView view = new StubDosView(false, false, false, true);

        AtomicFileWriter.SecurityMetadata.applyDosIfNeeded(
                false, false, false, true, view);

        assertEquals(0, view.readOnlyWrites.get());
        assertEquals(0, view.hiddenWrites.get());
        assertEquals(0, view.systemWrites.get());
        assertEquals(0, view.archiveWrites.get());

        view.hidden = true;
        AtomicFileWriter.SecurityMetadata.applyDosIfNeeded(
                false, false, false, true, view);

        assertEquals(0, view.readOnlyWrites.get());
        assertEquals(1, view.hiddenWrites.get());
        assertEquals(0, view.systemWrites.get());
        assertEquals(0, view.archiveWrites.get());
        assertFalse(view.hidden);

        StubDosView readOnlyMismatch = new StubDosView(true, false, false, true);
        AtomicFileWriter.SecurityMetadata.applyDosIfNeeded(
                false, false, false, true, readOnlyMismatch);
        assertDosWrites(readOnlyMismatch, 1, 0, 0, 0);
        assertFalse(readOnlyMismatch.readOnly);

        StubDosView systemMismatch = new StubDosView(false, false, true, true);
        AtomicFileWriter.SecurityMetadata.applyDosIfNeeded(
                false, false, false, true, systemMismatch);
        assertDosWrites(systemMismatch, 0, 0, 1, 0);
        assertFalse(systemMismatch.system);

        StubDosView archiveMismatch = new StubDosView(false, false, false, false);
        AtomicFileWriter.SecurityMetadata.applyDosIfNeeded(
                false, false, false, true, archiveMismatch);
        assertDosWrites(archiveMismatch, 0, 0, 0, 1);
        assertTrue(archiveMismatch.archive);
    }

    @Test
    void aclEntryMismatchWritesOnlyTheAcl() throws Exception {
        UserPrincipal owner = () -> "owner";
        AclEntry expectedEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(AclEntryPermission.READ_ATTRIBUTES)
                .build();
        AtomicInteger ownerWrites = new AtomicInteger();
        AtomicReference<List<AclEntry>> assignedAcl = new AtomicReference<>();
        AclFileAttributeView view = new AclFileAttributeView() {
            @Override public String name() { return "acl"; }
            @Override public UserPrincipal getOwner() { return owner; }
            @Override public void setOwner(UserPrincipal ignored) { ownerWrites.incrementAndGet(); }
            @Override public List<AclEntry> getAcl() { return List.of(); }
            @Override public void setAcl(List<AclEntry> entries) {
                assignedAcl.set(List.copyOf(entries));
            }
        };

        AtomicFileWriter.SecurityMetadata.applyAclIfNeeded(
                owner, List.of(expectedEntry), view);

        assertEquals(0, ownerWrites.get());
        assertEquals(List.of(expectedEntry), assignedAcl.get());
    }

    @Test
    void cleanupWritableRepairDoesNotRequireARedundantDosWrite() throws Exception {
        StubDosView alreadyWritable = new StubDosView(false, false, false, false) {
            @Override
            public void setReadOnly(boolean ignored) throws IOException {
                throw new java.nio.file.AccessDeniedException(
                        "redundant WRITE_ATTRIBUTES operation denied");
            }
        };

        AtomicFileWriter.SecurityMetadata.clearReadOnlyIfNeeded(alreadyWritable);

        StubDosView readOnly = new StubDosView(true, false, false, false);
        AtomicFileWriter.SecurityMetadata.clearReadOnlyIfNeeded(readOnly);
        assertFalse(readOnly.readOnly);
        assertEquals(1, readOnly.readOnlyWrites.get());
    }

    @Test
    void configuredAclIsDistinctFromAnOrdinarySiblingWhenAclViewIsSupported(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Path control = directory.resolve("control.txt");
        Files.write(target, ORIGINAL);
        Files.write(control, ORIGINAL);
        AclFileAttributeView targetAcl = Files.getFileAttributeView(
                target,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        AclFileAttributeView controlAcl = Files.getFileAttributeView(
                control,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        assumeTrue(targetAcl != null && controlAcl != null, "ACL view is unsupported");

        configureDistinctMetadata(target);

        assertNotEquals(
                controlAcl.getAcl(),
                targetAcl.getAcl(),
                "ACL preservation test must not be satisfiable by inherited temp defaults");
    }

    @Test
    void archiveFalseIsReappliedAfterContentWritesWhenDosViewIsSupported(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        DosFileAttributeView dos = Files.getFileAttributeView(
                target,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        assumeTrue(dos != null, "DOS view is unsupported");
        dos.setArchive(false);
        Map<String, Object> metadataBefore = securityMetadata(target);
        AtomicFileWriter writer = new AtomicFileWriter();

        writer.replace(target, REPLACEMENT, writer.capture(target));

        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target));
        assertEquals(metadataBefore, securityMetadata(target));
        assertFalse(dos.readAttributes().isArchive(),
                "content writes must not override the captured archive=false state");
        assertNoAtomicTemps(directory);
    }

    @Test
    void systemReconcilerRepairsMetadataDriftIntroducedByPromotion(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        assumeTrue(metadataMutation(target) != null,
                "no mutable security metadata view is supported");
        Map<String, Object> metadataBefore = securityMetadata(target);
        AtomicBoolean driftWasIntroduced = new AtomicBoolean();
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicFileWriter.Operations operations = system.withAtomicMover((source, destination) -> {
            system.atomicMover().move(source, destination);
            MetadataMutation drift = metadataMutation(destination);
            if (drift == null) {
                throw new IOException("no mutable metadata view after promotion");
            }
            drift.apply().run();
            driftWasIntroduced.set(
                    !metadataBefore.equals(securityMetadata(destination)));
        });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        writer.replace(target, REPLACEMENT, writer.capture(target));

        assertTrue(driftWasIntroduced.get(),
                "the mover must create real post-promotion metadata drift");
        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target));
        assertEquals(metadataBefore, securityMetadata(target),
                "the system reconciler must restore the captured metadata");
        assertNoAtomicTemps(directory);
    }

    @Test
    void reconcileFailureIsReportedAsCommittedUnverified(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicFileWriter.MetadataOperations metadata = system.metadataOperations();
        AtomicFileWriter.MetadataOperations failingMetadata =
                new DelegatingMetadataOperations(metadata) {
                    @Override
                    public void reconcile(
                            AtomicFileWriter.SecurityMetadata snapshot,
                            Path path
                    ) throws IOException {
                        throw new IOException("injected reconcile failure");
                    }
                };
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                system.withMetadataOperations(failingMetadata));

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(AtomicFileWriter.FailureReason.METADATA_FAILED, failure.reason());
        assertEquals(
                AtomicFileWriter.CommitState.COMMITTED_UNVERIFIED,
                failure.commitState());
        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void deleteDenyingTargetAclDoesNotLeaveATempAfterPrecommitFailure(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        AclFileAttributeView acl = Files.getFileAttributeView(
                target,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        assumeTrue(acl != null, "ACL view is unsupported");
        AclEntry denyDelete = AclEntry.newBuilder()
                .setType(AclEntryType.DENY)
                .setPrincipal(acl.getOwner())
                .setPermissions(AclEntryPermission.DELETE)
                .build();
        List<AclEntry> originalAcl = List.copyOf(acl.getAcl());
        List<AclEntry> entries = new ArrayList<>();
        entries.add(denyDelete);
        entries.addAll(originalAcl);
        acl.setAcl(entries);
        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION) {
                throw new IOException("injected prepromotion failure");
            }
        });

        try {
            AtomicFileWriter.AtomicWriteException failure = assertThrows(
                    AtomicFileWriter.AtomicWriteException.class,
                    () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

            assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
            assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
            assertNoAtomicTemps(directory);
        } finally {
            acl.setAcl(originalAcl);
        }
    }

    @Test
    void accessDeniedCleanupRestoresCreationMetadataBeforeRetryingDelete(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        configureDistinctMetadata(target);
        AtomicReference<Map<String, Object>> creationMetadata = new AtomicReference<>();
        AtomicBoolean stagedMetadataWasDistinct = new AtomicBoolean();
        AtomicBoolean creationMetadataWasRestored = new AtomicBoolean();
        AtomicInteger deleteCalls = new AtomicInteger();
        AtomicFileWriter.DeleteCall deleteCall = path -> {
            int call = deleteCalls.incrementAndGet();
            if (call == 1) {
                throw new java.nio.file.AccessDeniedException(
                        "redacted-temp",
                        null,
                        "injected first-delete denial");
            }
            creationMetadataWasRestored.set(
                    creationMetadata.get().equals(securityMetadata(path)));
            return Files.deleteIfExists(path);
        };
        AtomicFileWriter writer = new AtomicFileWriter(
                (stage, ignoredTarget, temp) -> {
                    if (stage == AtomicFileWriter.FaultStage.AFTER_TEMP_CREATE) {
                        creationMetadata.set(securityMetadata(temp));
                    }
                    if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION) {
                        stagedMetadataWasDistinct.set(
                                !creationMetadata.get().equals(securityMetadata(temp)));
                        throw new IOException("injected prepromotion failure");
                    }
                },
                AtomicFileWriter.Operations.system()
                        .withTempCleaner(AtomicFileWriter.tempCleaner(deleteCall)));

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
        assertTrue(stagedMetadataWasDistinct.get(),
                "the cleanup test must first apply metadata unlike the temp default");
        assertEquals(2, deleteCalls.get(), "AccessDenied must trigger exactly one repair retry");
        assertTrue(creationMetadataWasRestored.get(),
                "the retry must observe the temp's original creation metadata");
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void accessDeniedCleanupDoesNotRequireRedundantOwnerWriteOnRestrictedNtfsPath()
            throws Exception {
        Path base = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize()
                .resolve("build")
                .resolve("tmp");
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "t1172-owner-rights-");
        Path target = directory.resolve("primary.txt");
        try {
            Files.write(target, ORIGINAL);
            AclFileAttributeView acl = Files.getFileAttributeView(
                    target,
                    AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            assumeTrue(acl != null, "ACL view is unsupported");
            boolean redundantOwnerWriteDenied = false;
            try {
                acl.setOwner(acl.getOwner());
            } catch (java.nio.file.AccessDeniedException expected) {
                redundantOwnerWriteDenied = true;
            }
            assumeTrue(redundantOwnerWriteDenied,
                    "the validation path grants WRITE_OWNER, so it cannot reproduce this boundary");

            configureDistinctMetadata(target);
            AtomicInteger deleteCalls = new AtomicInteger();
            AtomicFileWriter.DeleteCall deleteCall = path -> {
                if (deleteCalls.incrementAndGet() == 1) {
                    throw new java.nio.file.AccessDeniedException(
                            "redacted-temp",
                            null,
                            "injected first-delete denial");
                }
                return Files.deleteIfExists(path);
            };
            AtomicFileWriter writer = new AtomicFileWriter(
                    (stage, ignoredTarget, ignoredTemp) -> {
                        if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION) {
                            throw new IOException("injected prepromotion failure");
                        }
                    },
                    AtomicFileWriter.Operations.system()
                            .withTempCleaner(AtomicFileWriter.tempCleaner(deleteCall)));

            AtomicFileWriter.AtomicWriteException failure = assertThrows(
                    AtomicFileWriter.AtomicWriteException.class,
                    () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

            assertEquals(AtomicFileWriter.CommitState.NOT_COMMITTED, failure.commitState());
            assertEquals(2, deleteCalls.get());
            assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
            assertNoAtomicTemps(directory);
        } finally {
            try (var paths = Files.list(directory)) {
                for (Path path : paths.toList()) {
                    DosFileAttributeView dos = Files.getFileAttributeView(
                            path,
                            DosFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (dos != null && dos.readAttributes().isReadOnly()) {
                        dos.setReadOnly(false);
                    }
                    Files.deleteIfExists(path);
                }
            }
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void createAndOverwritePreserveArbitrarySuppliedBytes(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.bin");
        byte[] first = { 0, (byte) 0xff, (byte) 0xc3, 0x28, '\r', '\n', 0x7f };
        byte[] second = {
                (byte) 0x80,
                0,
                (byte) 0xfe,
                '\n',
                '\r',
                (byte) 0xed,
                (byte) 0xa0,
                (byte) 0x80
        };
        AtomicFileWriter writer = new AtomicFileWriter();

        writer.replace(target, first, AtomicFileWriter.Preimage.absent());
        assertArrayEquals(first, Files.readAllBytes(target));

        writer.replace(target, second, writer.capture(target));
        assertArrayEquals(second, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void readSnapshotBindsExactBytesToTheExpectedPreimage(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        AtomicFileWriter writer = new AtomicFileWriter();

        AtomicFileWriter.ReadSnapshot snapshot = writer.readSnapshot(target);

        assertArrayEquals(ORIGINAL, snapshot.bytes());
        assertEquals(writer.capture(target), snapshot.preimage());
        assertTrue(snapshot.preimage().existed());
    }

    @Test
    void postPromotionFailureIsNeverReportedAsNotCommitted(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        byte[] laterBytes = "later-external-write".getBytes(StandardCharsets.UTF_8);
        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.AFTER_PROMOTION) {
                Files.write(target, laterBytes);
                throw new IOException("injected post-promotion failure");
            }
        });

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(AtomicFileWriter.CommitState.COMMITTED_UNVERIFIED, failure.commitState());
        assertArrayEquals(laterBytes, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void sameLengthPostPromotionCorruptionIsCaughtByFinalHashValidation(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.bin");
        Files.write(target, ORIGINAL);
        byte[] corrupted = REPLACEMENT.clone();
        corrupted[corrupted.length - 1] ^= 0x01;
        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.AFTER_PROMOTION) {
                Files.write(target, corrupted);
            }
        });

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(
                AtomicFileWriter.FailureReason.POST_COMMIT_VERIFICATION_FAILED,
                failure.reason());
        assertEquals(
                AtomicFileWriter.CommitState.COMMITTED_UNVERIFIED,
                failure.commitState());
        assertArrayEquals(corrupted, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void directorySyncRunsAfterPromotionAndReportsItsActualStatus(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        List<String> order = new ArrayList<>();
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicFileWriter.Operations operations = system
                .withAtomicMover((source, destination) -> {
                    order.add("move");
                    system.atomicMover().move(source, destination);
                })
                .withDirectorySynchronizer(syncedDirectory -> {
                    assertEquals(directory, syncedDirectory);
                    order.add("directory-sync");
                    return AtomicFileWriter.DirectorySyncStatus.SYNCED;
                });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        AtomicFileWriter.WriteOutcome outcome =
                writer.replace(target, REPLACEMENT, writer.capture(target));

        assertEquals(List.of("move", "directory-sync"), order);
        assertEquals(AtomicFileWriter.DirectorySyncStatus.SYNCED, outcome.directorySync());
        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void directorySyncFailureIsReportedAsCommittedUnverified(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        AtomicFileWriter.Operations operations = AtomicFileWriter.Operations.system()
                .withDirectorySynchronizer(ignoredDirectory -> {
                    throw new IOException("injected directory sync failure");
                });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(
                AtomicFileWriter.FailureReason.POST_COMMIT_VERIFICATION_FAILED,
                failure.reason());
        assertEquals(
                AtomicFileWriter.CommitState.COMMITTED_UNVERIFIED,
                failure.commitState());
        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void ambiguousPromotionPlusCleanupFailureNeverBecomesNotCommitted(
            @TempDir Path directory
    ) throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        AtomicFileWriter.Operations system = AtomicFileWriter.Operations.system();
        AtomicFileWriter.Operations operations = system
                .withAtomicMover((source, destination) -> {
                    throw new IOException("injected ambiguous mover failure");
                })
                .withTempCleaner((temp, creationMetadata) -> {
                    system.tempCleaner().delete(temp, creationMetadata);
                    throw new IOException("injected cleanup reporting failure");
                });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(AtomicFileWriter.FailureReason.TEMP_CLEANUP_FAILED, failure.reason());
        assertEquals(AtomicFileWriter.CommitState.COMMIT_STATE_UNKNOWN, failure.commitState());
        assertEquals(1, failure.getSuppressed().length);
        AtomicFileWriter.AtomicWriteException promotion =
                (AtomicFileWriter.AtomicWriteException) failure.getSuppressed()[0];
        assertEquals(AtomicFileWriter.FailureReason.PROMOTION_FAILED, promotion.reason());
        assertEquals(AtomicFileWriter.CommitState.COMMIT_STATE_UNKNOWN, promotion.commitState());
        assertTrue(failure.getMessage().contains("temporary-file cleanup failed"));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertNoAtomicTemps(directory);
    }

    @Test
    void survivingTempCleanupFailureIsTheObservableTopLevelOutcome(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("primary.txt");
        Files.write(target, ORIGINAL);
        AtomicFileWriter.Operations operations = AtomicFileWriter.Operations.system()
                .withAtomicMover((source, destination) -> {
                    throw new IOException("injected ambiguous mover failure");
                })
                .withTempCleaner((temp, creationMetadata) -> {
                    throw new IOException("injected cleanup failure");
                });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));
        List<Path> leaked;
        try (var paths = Files.list(directory)) {
            leaked = paths.filter(path -> !path.equals(target)).toList();
        }

        try {
            assertEquals(AtomicFileWriter.FailureReason.TEMP_CLEANUP_FAILED, failure.reason());
            assertEquals(
                    AtomicFileWriter.CommitState.COMMIT_STATE_UNKNOWN,
                    failure.commitState());
            assertTrue(failure.getMessage().contains("temporary-file cleanup failed"));
            assertEquals(1, leaked.size(), "the injected orphan must be observed exactly");
            assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        } finally {
            for (Path path : leaked) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void genericPromotionFailureUsesSafeUnknownOutcomeText(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("sensitive-customer-name.txt");
        Files.write(target, ORIGINAL);
        AtomicFileWriter.Operations operations = AtomicFileWriter.Operations.system()
                .withAtomicMover((source, destination) -> {
                    throw new IOException("FILE_DISCOVERED_CANARY_PRIVATE_CONTENT");
                });
        AtomicFileWriter writer = new AtomicFileWriter(
                AtomicFileWriter.FaultInjector.NONE,
                operations);

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertEquals(AtomicFileWriter.CommitState.COMMIT_STATE_UNKNOWN, failure.commitState());
        assertFalse(failure.getMessage().contains("sensitive-customer-name"));
        assertFalse(failure.getMessage().contains("FILE_DISCOVERED_CANARY_PRIVATE_CONTENT"));
        assertNoAtomicTemps(directory);
    }

    @Test
    void safeFailureMessageDoesNotExposeTargetOrCauseContent(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("sensitive-customer-name.txt");
        Files.write(target, ORIGINAL);
        AtomicFileWriter writer = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.AFTER_TEMP_CREATE) {
                throw new IOException("FILE_DISCOVERED_CANARY_PRIVATE_CONTENT");
            }
        });

        AtomicFileWriter.AtomicWriteException failure = assertThrows(
                AtomicFileWriter.AtomicWriteException.class,
                () -> writer.replace(target, REPLACEMENT, writer.capture(target)));

        assertFalse(failure.getMessage().contains("sensitive-customer-name"));
        assertFalse(failure.getMessage().contains("FILE_DISCOVERED_CANARY_PRIVATE_CONTENT"));
        assertNoAtomicTemps(directory);
    }

    private static void configureDistinctMetadata(Path target) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                target,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ));
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                target,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            AclEntry marker = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.getOwner())
                    .setPermissions(AclEntryPermission.READ_ATTRIBUTES)
                    .build();
            List<AclEntry> entries = new ArrayList<>(acl.getAcl());
            entries.add(marker);
            acl.setAcl(entries);
        }
        DosFileAttributeView dos = Files.getFileAttributeView(
                target,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            dos.setArchive(true);
            dos.setHidden(true);
            dos.setSystem(false);
            dos.setReadOnly(false);
        }
        UserDefinedFileAttributeView userDefined = Files.getFileAttributeView(
                target,
                UserDefinedFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (userDefined != null) {
            writeUserDefinedAttribute(
                    userDefined,
                    "talos_metadata_marker",
                    new byte[] { 0, 1, 2, (byte) 0xff });
        }
    }

    private static Map<String, Object> securityMetadata(Path target) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        PosixFileAttributeView posix = Files.getFileAttributeView(
                target,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix == null) {
            result.put("posix", "UNSUPPORTED");
        } else {
            var attributes = posix.readAttributes();
            result.put("posix-owner", attributes.owner());
            result.put("posix-group", attributes.group());
            result.put("posix-permissions", Set.copyOf(attributes.permissions()));
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                target,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            result.put("acl", "UNSUPPORTED");
        } else {
            result.put("acl-owner", acl.getOwner());
            result.put("acl-entries", List.copyOf(acl.getAcl()));
        }
        DosFileAttributeView dos = Files.getFileAttributeView(
                target,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (dos == null) {
            result.put("dos", "UNSUPPORTED");
        } else {
            var attributes = dos.readAttributes();
            result.put("dos-readonly", attributes.isReadOnly());
            result.put("dos-hidden", attributes.isHidden());
            result.put("dos-system", attributes.isSystem());
            result.put("dos-archive", attributes.isArchive());
        }
        UserDefinedFileAttributeView userDefined = Files.getFileAttributeView(
                target,
                UserDefinedFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (userDefined == null) {
            result.put("user-defined", "UNSUPPORTED");
        } else {
            result.put("user-defined", userDefinedAttributes(userDefined));
        }
        return result;
    }

    private static Map<String, String> userDefinedAttributes(Path target) throws IOException {
        UserDefinedFileAttributeView view = Files.getFileAttributeView(
                target,
                UserDefinedFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("user-defined attribute view is unavailable");
        }
        return userDefinedAttributes(view);
    }

    private static Map<String, String> userDefinedAttributes(
            UserDefinedFileAttributeView view
    ) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(view.list());
        names.sort(String::compareTo);
        for (String name : names) {
            result.put(name, HexFormat.of().formatHex(readUserDefinedAttribute(view, name)));
        }
        return result;
    }

    private static void writeUserDefinedAttribute(
            UserDefinedFileAttributeView view,
            String name,
            byte[] value
    ) throws IOException {
        int written = view.write(name, ByteBuffer.wrap(value));
        if (written != value.length) {
            throw new IOException("user-defined attribute write was incomplete");
        }
    }

    private static byte[] readUserDefinedAttribute(
            UserDefinedFileAttributeView view,
            String name
    ) throws IOException {
        int size = view.size(name);
        ByteBuffer value = ByteBuffer.allocate(size);
        int read = view.read(name, value);
        if (read != size || value.hasRemaining()) {
            throw new IOException("user-defined attribute read was incomplete");
        }
        return value.array();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <V extends java.nio.file.attribute.FileAttributeView> boolean supports(
            Path target,
            Class<V> view
    ) {
        return Files.getFileAttributeView(target, view, LinkOption.NOFOLLOW_LINKS) != null;
    }

    private static MetadataMutation metadataMutation(Path target) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                target,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> before = Set.copyOf(posix.readAttributes().permissions());
            Set<PosixFilePermission> changed =
                    new java.util.HashSet<>(before);
            if (!changed.add(PosixFilePermission.GROUP_EXECUTE)) {
                changed.remove(PosixFilePermission.GROUP_EXECUTE);
            }
            Set<PosixFilePermission> changedSnapshot = Set.copyOf(changed);
            return new MetadataMutation(
                    () -> posix.setPermissions(changedSnapshot),
                    () -> posix.setPermissions(before));
        }
        DosFileAttributeView dos = Files.getFileAttributeView(
                target,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            boolean before = dos.readAttributes().isHidden();
            return new MetadataMutation(
                    () -> dos.setHidden(!before),
                    () -> dos.setHidden(before));
        }
        return null;
    }

    private static MetadataMutation makeReadOnly(Path target) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                target,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> before = Set.copyOf(posix.readAttributes().permissions());
            Set<PosixFilePermission> changed = new java.util.HashSet<>(before);
            changed.remove(PosixFilePermission.OWNER_WRITE);
            changed.remove(PosixFilePermission.GROUP_WRITE);
            changed.remove(PosixFilePermission.OTHERS_WRITE);
            Set<PosixFilePermission> changedSnapshot = Set.copyOf(changed);
            return new MetadataMutation(
                    () -> posix.setPermissions(changedSnapshot),
                    () -> posix.setPermissions(before));
        }
        DosFileAttributeView dos = Files.getFileAttributeView(
                target,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            boolean before = dos.readAttributes().isReadOnly();
            return new MetadataMutation(
                    () -> dos.setReadOnly(true),
                    () -> dos.setReadOnly(before));
        }
        return null;
    }

    private static void assertNoAtomicTemps(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            assertTrue(paths.noneMatch(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(".talos-atomic-") && name.endsWith(".tmp");
            }), "no atomic-write temp may survive");
        }
    }

    private static void assertBefore(List<String> order, String first, String second) {
        int firstIndex = order.indexOf(first);
        int secondIndex = order.indexOf(second);
        assertTrue(firstIndex >= 0, "missing operation " + first + " in " + order);
        assertTrue(secondIndex >= 0, "missing operation " + second + " in " + order);
        assertTrue(firstIndex < secondIndex,
                first + " must precede " + second + ": " + order);
    }

    private static void assertDosWrites(
            StubDosView view,
            int readOnly,
            int hidden,
            int system,
            int archive
    ) {
        assertEquals(readOnly, view.readOnlyWrites.get());
        assertEquals(hidden, view.hiddenWrites.get());
        assertEquals(system, view.systemWrites.get());
        assertEquals(archive, view.archiveWrites.get());
    }

    private static void replaceAndClassify(
            AtomicFileWriter writer,
            Path target,
            byte[] replacement,
            AtomicFileWriter.Preimage expected,
            AtomicInteger committed,
            AtomicInteger stale
    ) {
        try {
            writer.replace(target, replacement, expected);
            committed.incrementAndGet();
        } catch (AtomicFileWriter.AtomicWriteException e) {
            if (e.reason() == AtomicFileWriter.FailureReason.STALE_PREIMAGE
                    && e.commitState() == AtomicFileWriter.CommitState.NOT_COMMITTED) {
                stale.incrementAndGet();
                return;
            }
            throw new AssertionError("unexpected atomic-write failure", e);
        }
    }

    private static class DelegatingMetadataOperations
            implements AtomicFileWriter.MetadataOperations {
        private final AtomicFileWriter.MetadataOperations delegate;

        private DelegatingMetadataOperations(AtomicFileWriter.MetadataOperations delegate) {
            this.delegate = delegate;
        }

        @Override
        public AtomicFileWriter.SecurityMetadata capture(Path path, boolean applicable)
                throws IOException {
            return delegate.capture(path, applicable);
        }

        @Override
        public void apply(AtomicFileWriter.SecurityMetadata metadata, Path path)
                throws IOException {
            delegate.apply(metadata, path);
        }

        @Override
        public void reconcile(AtomicFileWriter.SecurityMetadata metadata, Path path)
                throws IOException {
            delegate.reconcile(metadata, path);
        }

        @Override
        public void verify(AtomicFileWriter.SecurityMetadata metadata, Path path)
                throws IOException {
            delegate.verify(metadata, path);
        }
    }

    private static final class StubPosixView implements PosixFileAttributeView {
        private static final FileTime ZERO_TIME = FileTime.fromMillis(0);

        private UserPrincipal owner;
        private GroupPrincipal group;
        private Set<PosixFilePermission> permissions;
        private final AtomicInteger ownerWrites = new AtomicInteger();
        private final AtomicInteger groupWrites = new AtomicInteger();
        private final AtomicInteger permissionWrites = new AtomicInteger();

        private StubPosixView(
                UserPrincipal owner,
                GroupPrincipal group,
                Set<PosixFilePermission> permissions
        ) {
            this.owner = owner;
            this.group = group;
            this.permissions = Set.copyOf(permissions);
        }

        @Override
        public String name() {
            return "posix";
        }

        @Override
        public PosixFileAttributes readAttributes() {
            return new PosixFileAttributes() {
                @Override
                public UserPrincipal owner() {
                    return StubPosixView.this.owner;
                }

                @Override
                public GroupPrincipal group() {
                    return StubPosixView.this.group;
                }

                @Override
                public Set<PosixFilePermission> permissions() {
                    return Set.copyOf(StubPosixView.this.permissions);
                }

                @Override public FileTime lastModifiedTime() { return ZERO_TIME; }
                @Override public FileTime lastAccessTime() { return ZERO_TIME; }
                @Override public FileTime creationTime() { return ZERO_TIME; }
                @Override public boolean isRegularFile() { return true; }
                @Override public boolean isDirectory() { return false; }
                @Override public boolean isSymbolicLink() { return false; }
                @Override public boolean isOther() { return false; }
                @Override public long size() { return 0L; }
                @Override public Object fileKey() { return null; }
            };
        }

        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            permissionWrites.incrementAndGet();
            this.permissions = Set.copyOf(permissions);
        }

        @Override
        public void setGroup(GroupPrincipal group) {
            groupWrites.incrementAndGet();
            this.group = group;
        }

        @Override
        public UserPrincipal getOwner() {
            return owner;
        }

        @Override
        public void setOwner(UserPrincipal owner) {
            ownerWrites.incrementAndGet();
            this.owner = owner;
        }

        @Override
        public void setTimes(FileTime modified, FileTime access, FileTime create) {
        }
    }

    private static class StubDosView implements DosFileAttributeView {
        private static final FileTime ZERO_TIME = FileTime.fromMillis(0);

        private boolean readOnly;
        private boolean hidden;
        private boolean system;
        private boolean archive;
        private final AtomicInteger readOnlyWrites = new AtomicInteger();
        private final AtomicInteger hiddenWrites = new AtomicInteger();
        private final AtomicInteger systemWrites = new AtomicInteger();
        private final AtomicInteger archiveWrites = new AtomicInteger();

        private StubDosView(boolean readOnly, boolean hidden, boolean system, boolean archive) {
            this.readOnly = readOnly;
            this.hidden = hidden;
            this.system = system;
            this.archive = archive;
        }

        @Override
        public String name() {
            return "dos";
        }

        @Override
        public DosFileAttributes readAttributes() {
            return new DosFileAttributes() {
                @Override public boolean isReadOnly() { return StubDosView.this.readOnly; }
                @Override public boolean isHidden() { return StubDosView.this.hidden; }
                @Override public boolean isArchive() { return StubDosView.this.archive; }
                @Override public boolean isSystem() { return StubDosView.this.system; }
                @Override public FileTime lastModifiedTime() { return ZERO_TIME; }
                @Override public FileTime lastAccessTime() { return ZERO_TIME; }
                @Override public FileTime creationTime() { return ZERO_TIME; }
                @Override public boolean isRegularFile() { return true; }
                @Override public boolean isDirectory() { return false; }
                @Override public boolean isSymbolicLink() { return false; }
                @Override public boolean isOther() { return false; }
                @Override public long size() { return 0L; }
                @Override public Object fileKey() { return null; }
            };
        }

        @Override
        public void setReadOnly(boolean value) throws IOException {
            readOnlyWrites.incrementAndGet();
            readOnly = value;
        }

        @Override
        public void setHidden(boolean value) {
            hiddenWrites.incrementAndGet();
            hidden = value;
        }

        @Override
        public void setSystem(boolean value) {
            systemWrites.incrementAndGet();
            system = value;
        }

        @Override
        public void setArchive(boolean value) {
            archiveWrites.incrementAndGet();
            archive = value;
        }

        @Override
        public void setTimes(FileTime modified, FileTime access, FileTime create) {
        }
    }

    private static class StubUserDefinedView implements UserDefinedFileAttributeView {
        protected final Map<String, byte[]> attributes = new LinkedHashMap<>();
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();

        @Override
        public String name() {
            return "user";
        }

        @Override
        public List<String> list() {
            return new ArrayList<>(attributes.keySet());
        }

        @Override
        public int size(String name) throws IOException {
            return attribute(name).length;
        }

        @Override
        public int read(String name, ByteBuffer destination) throws IOException {
            byte[] value = attribute(name);
            if (destination.remaining() < value.length) {
                throw new IOException("destination is too small");
            }
            destination.put(value);
            return value.length;
        }

        @Override
        public int write(String name, ByteBuffer source) {
            writes.incrementAndGet();
            byte[] value = new byte[source.remaining()];
            source.get(value);
            attributes.put(name, value);
            return value.length;
        }

        @Override
        public void delete(String name) throws IOException {
            deletes.incrementAndGet();
            if (attributes.remove(name) == null) {
                throw new IOException("attribute is absent");
            }
        }

        private byte[] attribute(String name) throws IOException {
            byte[] value = attributes.get(name);
            if (value == null) {
                throw new IOException("attribute is absent");
            }
            return value;
        }
    }

    private record MetadataMutation(IoRunnable apply, IoRunnable restore) {
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }

}
