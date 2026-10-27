package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationPacketPublisherDurabilityTest {
    @TempDir
    Path temp;

    @Test
    void leaseCannotEscapeItsCallback() throws Exception {
        Fixture fixture = fixture("escaped-lease");
        AtomicReference<QualificationPacketPublisher.Lease> escaped = new AtomicReference<>();
        QualificationPacketPublisher.withLease(fixture.packet().root(), lease -> {
            escaped.set(lease);
            return null;
        });

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> escaped.get().publish(
                        QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                        members(fixture)));

        assertEquals(QualificationPacketPublisher.FailureReason.INVALID_REQUEST, failure.reason());
        assertEquals(QualificationPacketPublisher.CommitState.NOT_COMMITTED,
                failure.commitState());
        assertPublicationAbsent(fixture);
    }

    @Test
    void leaseCannotBeUsedByAnotherThreadWhileItsCallbackIsActive() throws Exception {
        Fixture fixture = fixture("cross-thread-lease");
        AtomicReference<Throwable> observed = new AtomicReference<>();

        QualificationPacketPublisher.withLease(fixture.packet().root(), lease -> {
            Thread foreign = new Thread(() -> {
                try {
                    lease.publish(
                            QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                            members(fixture));
                } catch (Throwable failure) {
                    observed.set(failure);
                }
            }, "qualification-foreign-lease-user");
            foreign.start();
            try {
                foreign.join(Duration.ofSeconds(10).toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while awaiting foreign lease user", interrupted);
            }
            assertFalse(foreign.isAlive(), "foreign lease user did not terminate");
            return null;
        });

        Throwable observedFailure = observed.get();
        assertNotNull(observedFailure);
        QualificationPacketPublisher.PublicationException failure = assertInstanceOf(
                QualificationPacketPublisher.PublicationException.class, observedFailure);
        assertEquals(QualificationPacketPublisher.FailureReason.INVALID_REQUEST, failure.reason());
        assertEquals(QualificationPacketPublisher.CommitState.NOT_COMMITTED,
                failure.commitState());
        assertPublicationAbsent(fixture);
    }

    @Test
    void oneTimeoutBudgetSpansJvmAndProcessLockWaiting() throws Exception {
        Fixture fixture = fixture("shared-lock-deadline");
        Path controls = temp.resolve("shared-lock-deadline-controls");
        Path processReady = controls.resolve("process.ready");
        Path processRelease = controls.resolve("process.release");
        Process processHolder = startProcessHolder(
                fixture, processReady, processRelease, Duration.ofSeconds(10));
        CountDownLatch localHolderAcquired = new CountDownLatch(1);
        CountDownLatch releaseLocalHolder = new CountDownLatch(1);
        AtomicReference<Throwable> localHolderFailure = new AtomicReference<>();
        Thread localHolder = null;
        Thread delayedRelease = null;
        Duration totalBudget = Duration.ofMillis(1_500);
        AtomicLong contenderJvmAcquired = new AtomicLong();
        long started = 0L;
        long finished = 0L;
        QualificationPacketPublisher.PublicationException timeoutFailure;
        try {
            awaitFile(processReady);
            localHolder = new Thread(() -> {
                try {
                    QualificationPacketPublisher.withLease(
                            fixture.packet().root(),
                            Duration.ofSeconds(10),
                            (stage, ordinal) -> {
                                if (stage
                                        == QualificationPacketPublisher.FaultStage
                                                .AFTER_JVM_LOCK_ACQUIRED) {
                                    localHolderAcquired.countDown();
                                    awaitLatch(
                                            releaseLocalHolder,
                                            Duration.ofSeconds(10),
                                            "local holder release");
                                    throw new IOException("release the local lock holder");
                                }
                            },
                            lease -> {
                                throw new AssertionError(
                                        "local holder unexpectedly acquired the process lock");
                            });
                } catch (Throwable failure) {
                    localHolderFailure.set(failure);
                }
            }, "qualification-local-lock-holder");
            localHolder.start();
            assertTrue(localHolderAcquired.await(10, TimeUnit.SECONDS),
                    "local holder did not acquire the JVM lock");

            delayedRelease = new Thread(() -> {
                try {
                    Thread.sleep(600L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    releaseLocalHolder.countDown();
                }
            }, "qualification-local-lock-release");
            started = System.nanoTime();
            delayedRelease.start();
            timeoutFailure = assertThrows(
                    QualificationPacketPublisher.PublicationException.class,
                    () -> QualificationPacketPublisher.withLease(
                            fixture.packet().root(),
                            totalBudget,
                            (stage, ordinal) -> {
                                if (stage
                                        == QualificationPacketPublisher.FaultStage
                                                .AFTER_JVM_LOCK_ACQUIRED) {
                                    contenderJvmAcquired.set(System.nanoTime());
                                }
                            },
                            lease -> null));
            finished = System.nanoTime();
        } finally {
            releaseLocalHolder.countDown();
            Files.createDirectories(processRelease.getParent());
            Files.writeString(processRelease, "release\n", StandardCharsets.UTF_8);
            join(localHolder, Duration.ofSeconds(10));
            join(delayedRelease, Duration.ofSeconds(10));
            ProcessResult processResult = await(processHolder);
            assertEquals(0, processResult.exit(), processResult.output());
        }

        assertEquals(QualificationPacketPublisher.FailureReason.LOCK_TIMEOUT,
                timeoutFailure.reason());
        Throwable holderFailure = localHolderFailure.get();
        assertNotNull(holderFailure);
        assertInstanceOf(IOException.class, holderFailure);
        long acquired = contenderJvmAcquired.get();
        assertTrue(acquired > started, "contender never acquired the JVM lock");
        long jvmWaitMillis = Duration.ofNanos(acquired - started).toMillis();
        long processWaitMillis = Duration.ofNanos(finished - acquired).toMillis();
        long totalMillis = Duration.ofNanos(finished - started).toMillis();
        assertTrue(jvmWaitMillis >= 400L,
                () -> "JVM-lock wait was not established: " + jvmWaitMillis + "ms");
        assertTrue(processWaitMillis >= 400L,
                () -> "process-lock wait was not established: " + processWaitMillis + "ms");
        assertTrue(processWaitMillis < totalBudget.toMillis() - 200L,
                () -> "process-lock wait used a reset timeout: " + processWaitMillis + "ms");
        assertTrue(totalMillis >= totalBudget.toMillis() - 250L,
                () -> "shared timeout returned too early: " + totalMillis + "ms");
        assertTrue(totalMillis < totalBudget.toMillis() + 500L,
                () -> "shared timeout exceeded its Windows bound: " + totalMillis + "ms");
        assertPublicationAbsent(fixture);
    }

    @Test
    void everyBootstrapFaultLeavesAnEmptyStateThatBootstrapCanComplete() {
        List<QualificationPacketPublisher.FaultStage> stages = List.of(
                QualificationPacketPublisher.FaultStage.AFTER_CONTROL_ROOT_CREATE,
                QualificationPacketPublisher.FaultStage.AFTER_COMMITS_DIRECTORY_CREATE,
                QualificationPacketPublisher.FaultStage.AFTER_INTENTS_DIRECTORY_CREATE,
                QualificationPacketPublisher.FaultStage.AFTER_STAGES_DIRECTORY_CREATE,
                QualificationPacketPublisher.FaultStage.AFTER_LOCK_FILE_FLUSH);

        assertAll("bootstrap recovery seams", indexed(stages).stream().map(testCase -> () -> {
            QualificationTestPacket packet = bootstrapPacket("bootstrap-" + testCase.index());
            AtomicReference<Boolean> actionRan = new AtomicReference<>(false);

            assertThrows(IOException.class, () -> QualificationPacketPublisher.withBootstrapLease(
                    packet.root(),
                    Duration.ofSeconds(5),
                    (actual, ordinal) -> {
                        if (actual == testCase.value() && ordinal == -1) {
                            throw new IOException("injected bootstrap fault");
                        }
                    },
                    lease -> {
                        actionRan.set(true);
                        return null;
                    }));
            assertFalse(actionRan.get(), testCase.value().name());

            QualificationPacketPublisher.withBootstrapLease(packet.root(), lease -> {
                actionRan.set(true);
                return null;
            });

            assertTrue(actionRan.get(), testCase.value().name());
            assertCompleteEmptyControlState(packet.root());
        }));
    }

    @Test
    void intentAndMemberFaultsReportNotCommittedAtTheirExactOrdinals() {
        List<FaultCase> cases = new ArrayList<>();
        for (QualificationPacketPublisher.FaultStage stage : List.of(
                QualificationPacketPublisher.FaultStage.AFTER_METADATA_STAGE_CREATE,
                QualificationPacketPublisher.FaultStage.AFTER_METADATA_PARTIAL_WRITE,
                QualificationPacketPublisher.FaultStage.AFTER_METADATA_FINAL_WRITE,
                QualificationPacketPublisher.FaultStage.AFTER_INTENT_FLUSH,
                QualificationPacketPublisher.FaultStage.AFTER_TARGET_CREATE)) {
            cases.add(new FaultCase(stage, -2,
                    QualificationPacketPublisher.CommitState.NOT_COMMITTED));
        }
        for (int ordinal : List.of(0, 1)) {
            for (QualificationPacketPublisher.FaultStage stage : List.of(
                    QualificationPacketPublisher.FaultStage.AFTER_MEMBER_STAGE_CREATE,
                    QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PARTIAL_WRITE,
                    QualificationPacketPublisher.FaultStage.AFTER_MEMBER_FINAL_WRITE,
                    QualificationPacketPublisher.FaultStage.AFTER_MEMBER_FLUSH,
                    QualificationPacketPublisher.FaultStage.AFTER_MEMBER_VALIDATION,
                    QualificationPacketPublisher.FaultStage.BEFORE_MEMBER_PROMOTION,
                    QualificationPacketPublisher.FaultStage.AFTER_TARGET_CREATE,
                    QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION)) {
                cases.add(new FaultCase(stage, ordinal,
                        QualificationPacketPublisher.CommitState.NOT_COMMITTED));
            }
        }

        assertAll("intent and member transaction states", indexed(cases).stream()
                .map(testCase -> () -> assertFaultStateAndRecover(
                        "precommit-" + testCase.index(), testCase.value())));
    }

    @Test
    void commitFaultsReportTheDurablyObservedCommitState() {
        List<FaultCase> cases = List.of(
                notCommitted(QualificationPacketPublisher.FaultStage.AFTER_METADATA_STAGE_CREATE),
                notCommitted(QualificationPacketPublisher.FaultStage.AFTER_METADATA_PARTIAL_WRITE),
                notCommitted(QualificationPacketPublisher.FaultStage.AFTER_METADATA_FINAL_WRITE),
                notCommitted(QualificationPacketPublisher.FaultStage.AFTER_COMMIT_STAGE_FLUSH),
                notCommitted(QualificationPacketPublisher.FaultStage.BEFORE_COMMIT_PROMOTION),
                committed(QualificationPacketPublisher.FaultStage.AFTER_TARGET_CREATE),
                committed(QualificationPacketPublisher.FaultStage.AFTER_COMMIT_PROMOTION),
                committed(QualificationPacketPublisher.FaultStage.AFTER_COMMIT_VERIFICATION),
                committed(QualificationPacketPublisher.FaultStage.BEFORE_CLEANUP));

        assertAll("commit transaction states", indexed(cases).stream()
                .map(testCase -> () -> assertFaultStateAndRecover(
                        "commit-" + testCase.index(), testCase.value())));
    }

    @Test
    void everyCleanupFaultIsCommittedAndExactRetryFinishesCleanup() {
        List<FaultCase> cases = List.of(
                committed(
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_STAGE_CLEANUP, 0),
                committed(
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_STAGE_CLEANUP, 1),
                committed(QualificationPacketPublisher.FaultStage.AFTER_INTENT_STAGE_CLEANUP),
                committed(QualificationPacketPublisher.FaultStage.AFTER_COMMIT_STAGE_CLEANUP),
                committed(QualificationPacketPublisher.FaultStage.BEFORE_INTENT_CLEANUP),
                committed(QualificationPacketPublisher.FaultStage.AFTER_INTENT_CLEANUP),
                committed(QualificationPacketPublisher.FaultStage.AFTER_CLEANUP));

        assertAll("cleanup recovery seams", indexed(cases).stream().map(testCase -> () -> {
            Fixture fixture = fixture("cleanup-" + testCase.index());
            QualificationPacketPublisher.PublicationException failure = assertThrows(
                    QualificationPacketPublisher.PublicationException.class,
                    () -> publishWithFault(fixture, testCase.value()));
            assertEquals(QualificationPacketPublisher.CommitState.COMMITTED,
                    failure.commitState(), testCase.value().toString());
            byte[] promptBefore = Files.readAllBytes(fixture.prompt());
            byte[] modelBefore = Files.readAllBytes(fixture.model());
            Path commit = commitPath(fixture);
            byte[] commitBefore = Files.readAllBytes(commit);

            QualificationPacketPublisher.CommittedSet recovered = publish(fixture);

            assertFalse(recovered.created(), testCase.value().toString());
            assertArrayEquals(promptBefore, Files.readAllBytes(fixture.prompt()));
            assertArrayEquals(modelBefore, Files.readAllBytes(fixture.model()));
            assertArrayEquals(commitBefore, Files.readAllBytes(commit));
            assertNoRecoveryState(fixture.packet().root());
            read(fixture);
        }));
    }

    @Test
    void exactCommittedRetryPreservesAllAuthoritativeBytesAndMtimes() throws Exception {
        Fixture fixture = fixture("immutable-retry");
        QualificationPacketPublisher.CommittedSet first = publish(fixture);
        Path commit = first.commitPath();
        Map<Path, Snapshot> before = Map.of(
                fixture.prompt(), snapshot(fixture.prompt()),
                fixture.model(), snapshot(fixture.model()),
                commit, snapshot(commit));

        QualificationPacketPublisher.CommittedSet retry = publish(fixture);

        assertFalse(retry.created());
        for (Map.Entry<Path, Snapshot> entry : before.entrySet()) {
            Snapshot after = snapshot(entry.getKey());
            assertArrayEquals(entry.getValue().bytes(), after.bytes(), entry.getKey().toString());
            assertEquals(entry.getValue().mtime(), after.mtime(), entry.getKey().toString());
        }
        assertNoRecoveryState(fixture.packet().root());
    }

    private void assertFaultStateAndRecover(String name, FaultCase testCase) throws Exception {
        Fixture fixture = fixture(name);
        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> publishWithFault(fixture, testCase));
        assertEquals(testCase.expected(), failure.commitState(), testCase.toString());

        QualificationPacketPublisher.CommittedSet recovered = publish(fixture);

        assertArrayEquals(fixture.promptBytes(), Files.readAllBytes(fixture.prompt()));
        assertArrayEquals(fixture.modelBytes(), Files.readAllBytes(fixture.model()));
        assertTrue(Files.isRegularFile(recovered.commitPath()));
        assertNoRecoveryState(fixture.packet().root());
        read(fixture);
    }

    private QualificationTestPacket bootstrapPacket(String name) throws Exception {
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve(name), QualificationTestPacket.protocol6Catalog());
        packet.removeRunEvidenceForTest();
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.EXECUTION_IDENTITY,
                List.of(
                        packet.root().resolve(QualificationExecutionIdentityWriter.LEDGER_FILE),
                        packet.root().resolve(QualificationExecutionIdentity.HARNESS_FILE),
                        packet.root().resolve(QualificationExecutionIdentity.RUNTIME_FILE)));
        Path commits = packet.root()
                .resolve(QualificationPacketPublisher.CONTROL_DIRECTORY)
                .resolve("commits");
        try (var entries = Files.list(commits)) {
            assertTrue(entries.findAny().isEmpty(), "bootstrap fixture retained a commit");
        }
        packet.removeEmptyPublicationControlForTest();
        return packet;
    }

    private Fixture fixture(String name) throws Exception {
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve(name), QualificationTestPacket.protocol6Catalog());
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                List.of(
                        packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                        packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE),
                        packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE)));
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                List.of(packet.runRoot().resolve(
                        QualificationRequirementFinalizer.OUTPUT_FILE)));
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                List.of(packet.runRoot().resolve(
                        QualificationApprovalFinalizer.OUTPUT_FILE)));
        Path prompt = packet.runRoot().resolve("prompt-debug.txt");
        Path model = packet.runRoot().resolve("llama.log");
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(prompt, model));
        return new Fixture(
                packet,
                prompt,
                model,
                "provider request\n".getBytes(StandardCharsets.UTF_8),
                "model log\n".getBytes(StandardCharsets.UTF_8));
    }

    private static QualificationPacketPublisher.CommittedSet publish(Fixture fixture)
            throws IOException {
        return QualificationPacketPublisher.withLease(
                fixture.packet().root(),
                lease -> lease.publish(
                        QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                        members(fixture)));
    }

    private static QualificationPacketPublisher.CommittedSet publishWithFault(
            Fixture fixture,
            FaultCase testCase) throws IOException {
        return QualificationPacketPublisher.withLease(
                fixture.packet().root(),
                Duration.ofSeconds(5),
                (actual, ordinal) -> {
                    if (actual == testCase.stage() && ordinal == testCase.ordinal()) {
                        throw new IOException("injected durability fault");
                    }
                },
                lease -> lease.publish(
                        QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                        members(fixture)));
    }

    private static QualificationPacketPublisher.CommittedSet read(Fixture fixture)
            throws IOException {
        return QualificationPacketPublisher.requireCommitted(
                fixture.packet().root(),
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(fixture.prompt(), fixture.model()));
    }

    private static Path commitPath(Fixture fixture) throws IOException {
        return QualificationPacketPublisher.commitPathForTest(
                fixture.packet().root(),
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(fixture.prompt(), fixture.model()));
    }

    private static List<QualificationPacketPublisher.Member> members(Fixture fixture) {
        return List.of(
                new QualificationPacketPublisher.Member(
                        fixture.prompt(), fixture.promptBytes()),
                new QualificationPacketPublisher.Member(
                        fixture.model(), fixture.modelBytes()));
    }

    private static void assertPublicationAbsent(Fixture fixture) throws IOException {
        assertFalse(Files.exists(fixture.prompt()));
        assertFalse(Files.exists(fixture.model()));
        assertFalse(Files.exists(commitPath(fixture)));
        assertNoRecoveryState(fixture.packet().root());
    }

    private static void assertCompleteEmptyControlState(Path packetRoot) throws IOException {
        Path control = packetRoot.resolve(QualificationPacketPublisher.CONTROL_DIRECTORY);
        assertTrue(Files.isDirectory(control));
        assertTrue(Files.isRegularFile(control.resolve("writer.lock")));
        assertEquals(0L, Files.size(control.resolve("writer.lock")));
        for (String directory : List.of("commits", "intents", "stages")) {
            Path child = control.resolve(directory);
            assertTrue(Files.isDirectory(child), directory);
            try (var entries = Files.list(child)) {
                assertTrue(entries.findAny().isEmpty(), directory);
            }
        }
    }

    private static void assertNoRecoveryState(Path packetRoot) throws IOException {
        Path control = packetRoot.resolve(QualificationPacketPublisher.CONTROL_DIRECTORY);
        for (String directory : List.of("intents", "stages")) {
            try (var entries = Files.list(control.resolve(directory))) {
                assertTrue(entries.findAny().isEmpty(), directory);
            }
        }
    }

    private static Snapshot snapshot(Path path) throws IOException {
        return new Snapshot(Files.readAllBytes(path), Files.getLastModifiedTime(path));
    }

    private static Process startProcessHolder(
            Fixture fixture,
            Path ready,
            Path release,
            Duration timeout) throws IOException {
        Files.createDirectories(ready.getParent());
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                QualificationPacketPublicationProcessMain.class.getName(),
                "hold",
                fixture.packet().root().toString(),
                fixture.packet().runRoot().toString(),
                ready.toString(),
                release.toString(),
                "unused",
                Long.toString(timeout.toMillis()))
                .redirectErrorStream(true)
                .start();
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!Files.exists(path)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("child process did not acquire the process lock");
            }
            Thread.sleep(10L);
        }
    }

    private static void awaitLatch(
            CountDownLatch latch,
            Duration timeout,
            String label) throws IOException {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException(label + " timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(label + " was interrupted", interrupted);
        }
    }

    private static void join(Thread thread, Duration timeout) throws Exception {
        if (thread == null) return;
        thread.join(timeout.toMillis());
        assertFalse(thread.isAlive(), thread.getName() + " did not terminate");
    }

    private static ProcessResult await(Process process) throws Exception {
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException("child process did not terminate");
        }
        return new ProcessResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static FaultCase notCommitted(QualificationPacketPublisher.FaultStage stage) {
        return new FaultCase(stage, -1,
                QualificationPacketPublisher.CommitState.NOT_COMMITTED);
    }

    private static FaultCase committed(QualificationPacketPublisher.FaultStage stage) {
        return committed(stage, -1);
    }

    private static FaultCase committed(
            QualificationPacketPublisher.FaultStage stage,
            int ordinal) {
        return new FaultCase(stage, ordinal,
                QualificationPacketPublisher.CommitState.COMMITTED);
    }

    private static <T> List<Indexed<T>> indexed(List<T> values) {
        List<Indexed<T>> indexed = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            indexed.add(new Indexed<>(index, values.get(index)));
        }
        return List.copyOf(indexed);
    }

    private record Indexed<T>(int index, T value) {}

    private record ProcessResult(int exit, String output) {}

    private record FaultCase(
            QualificationPacketPublisher.FaultStage stage,
            int ordinal,
            QualificationPacketPublisher.CommitState expected) {}

    private record Snapshot(byte[] bytes, FileTime mtime) {
        Snapshot {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record Fixture(
            QualificationTestPacket packet,
            Path prompt,
            Path model,
            byte[] promptBytes,
            byte[] modelBytes) {
        Fixture {
            promptBytes = promptBytes.clone();
            modelBytes = modelBytes.clone();
        }

        @Override
        public byte[] promptBytes() {
            return promptBytes.clone();
        }

        @Override
        public byte[] modelBytes() {
            return modelBytes.clone();
        }
    }
}
