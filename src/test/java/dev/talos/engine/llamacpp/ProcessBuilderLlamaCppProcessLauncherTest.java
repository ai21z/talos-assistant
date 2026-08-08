package dev.talos.engine.llamacpp;

import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessBuilderLlamaCppProcessLauncherTest {

    @TempDir Path temp;

    @Test
    void realChildObservationBindsPidStartCommandAndEffectiveEnvironment() throws Exception {
        List<String> command = sleeperCommand();
        Path log = temp.resolve("observed-child.log");
        ProcessBuilder expected = new ProcessBuilder(command);
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher();

        LlamaCppProcess child = launcher.start(command, log);
        try {
            LlamaCppProcessLaunchObservation observation = child.launchObservation();
            assertNotNull(observation);
            assertTrue(observation.exactProcessIdentityAvailable());
            assertEquals(
                    ProviderProcessLaunchReceipt.digestOrderedValues(command),
                    observation.launchCommandSha256());
            assertEquals(
                    ProviderProcessLaunchReceipt.digestEffectiveEnvironment(
                            expected.environment()),
                    observation.effectiveEnvironmentSha256());
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(Duration.ofSeconds(2)));
            deleteImmediately(log);
        }
    }

    @Test
    void observationFailureSettlesTheRealChildBeforeControlReturns() throws Exception {
        AtomicLong childPid = new AtomicLong();
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher((process, builder, observed) -> {
                    childPid.set(process.pid());
                    throw new IllegalStateException("injected observation failure");
                });

        Path log = temp.resolve("failed-observation-child.log");
        assertThrows(IllegalStateException.class, () -> launcher.start(
                sleeperCommand(), log));

        assertTrue(childPid.get() > 0L);
        ProcessHandle handle = ProcessHandle.of(childPid.get()).orElse(null);
        assertFalse(handle != null && handle.isAlive(),
                "the launcher must not return while an unowned child remains alive");
        deleteImmediately(log);
    }

    @Test
    void failedProcessStartReleasesTheAtomicLaunchPermit()
            throws Exception {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher(
                        ProcessBuilderLlamaCppProcessLauncher::observeLaunch,
                        owner);
        Path failedLog = temp.resolve("failed-process-start.log");

        assertThrows(java.io.IOException.class, () -> launcher.start(
                List.of(temp.resolve("missing-provider-executable").toString()),
                failedLog));
        deleteImmediately(failedLog);

        Path successfulLog = temp.resolve("post-failure-child.log");
        LlamaCppProcess child = launcher.start(sleeperCommand(), successfulLog);
        try {
            assertTrue(child.isAlive());
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(Duration.ofSeconds(2)));
            deleteImmediately(successfulLog);
        }
    }

    @Test
    void activeLeaseBlocksASecondLauncherBeforeProcessStart()
            throws Exception {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        ProcessBuilderLlamaCppProcessLauncher firstLauncher =
                new ProcessBuilderLlamaCppProcessLauncher(
                        ProcessBuilderLlamaCppProcessLauncher::observeLaunch,
                        owner);
        AtomicLong secondStart = new AtomicLong();
        ProcessBuilderLlamaCppProcessLauncher secondLauncher =
                new ProcessBuilderLlamaCppProcessLauncher(
                        (process, builder, observed) -> {
                            secondStart.incrementAndGet();
                            return ProcessBuilderLlamaCppProcessLauncher.observeLaunch(
                                    process, builder, observed);
                        },
                        owner);
        Path firstLog = temp.resolve("first-active-child.log");
        Path secondLog = temp.resolve("blocked-second-child.log");
        LlamaCppProcess first = firstLauncher.start(sleeperCommand(), firstLog);

        try {
            assertThrows(
                    LlamaCppProcessTerminationOwner.LaunchBlocked.class,
                    () -> secondLauncher.start(sleeperCommand(), secondLog));
            assertEquals(0L, secondStart.get());
            assertFalse(Files.exists(secondLog));
        } finally {
            first.destroyForcibly();
            assertTrue(first.waitFor(Duration.ofSeconds(2)));
            deleteImmediately(firstLog);
        }
    }

    @Test
    void observationFailureUsesTheLeaseBoundToTheRawChild()
            throws Exception {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        AtomicReference<Process> rawChild = new AtomicReference<>();
        Path log = temp.resolve("owned-observation-child.log");
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher(
                        (process, builder, observed) -> {
                            rawChild.set(process);
                            throw new IllegalStateException(
                                    "injected observation failure");
                        },
                        owner);

        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> launcher.start(sleeperCommand(), log));
            assertNotNull(rawChild.get());
            assertFalse(rawChild.get().isAlive());
            assertEquals(0, owner.ownedLeaseCount());
            deleteImmediately(log);
        } finally {
            Process child = rawChild.get();
            if (child != null) {
                child.destroyForcibly();
                child.waitFor(5L, java.util.concurrent.TimeUnit.SECONDS);
            }
            if (Files.exists(log)) awaitDelete(log);
        }
    }

    @Test
    void retainedLeaseBlocksAnotherLauncherUntilRetryProvesExit()
            throws Exception {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        RetainedTarget retainedTarget = new RetainedTarget();
        LlamaCppProcessTerminationOwner.Lease retainedLease =
                owner.adopt(retainedTarget);
        LlamaCppProcessTerminationOwner.Policy immediate =
                new LlamaCppProcessTerminationOwner.Policy(
                        Duration.ZERO,
                        Duration.ZERO);
        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_LIVE,
                retainedLease.terminate(immediate).state());
        ProcessBuilderLlamaCppProcessLauncher replacement =
                new ProcessBuilderLlamaCppProcessLauncher(
                        ProcessBuilderLlamaCppProcessLauncher::observeLaunch,
                        owner);
        Path log = temp.resolve("replacement-child.log");

        assertThrows(
                LlamaCppProcessTerminationOwner.LaunchBlocked.class,
                () -> replacement.start(sleeperCommand(), log));
        assertFalse(Files.exists(log));

        retainedTarget.alive = false;
        LlamaCppProcess child = replacement.start(sleeperCommand(), log);
        try {
            assertTrue(child.isAlive());
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(Duration.ofSeconds(2)));
            deleteImmediately(log);
        }
    }

    @Test
    void observationFailureSettlesTheRealRootAndPublishedGrandchild()
            throws Exception {
        Path childPidFile = temp.resolve("published-grandchild.pid");
        Path log = temp.resolve("root-with-grandchild.log");
        List<String> command =
                new java.util.ArrayList<>(javaCommand(ChildSpawner.class));
        command.add(childPidFile.toString());
        AtomicLong rootPid = new AtomicLong();
        AtomicLong childPid = new AtomicLong();
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher(
                        (process, builder, observed) -> {
                            rootPid.set(process.pid());
                            long published = awaitPublishedChild(
                                    process,
                                    childPidFile,
                                    Duration.ofSeconds(10));
                            childPid.set(published);
                            throw new IllegalStateException(
                                    "injected observation failure after child publication");
                        });

        try {
            assertThrows(IllegalStateException.class,
                    () -> launcher.start(command, log));

            assertTrue(rootPid.get() > 0L);
            assertTrue(childPid.get() > 0L);
            assertNotAlive(rootPid.get(),
                    "the launcher must settle the failed-handoff root before returning");
            assertNotAlive(childPid.get(),
                    "the launcher must settle the captured grandchild before returning");
            deleteImmediately(log);
        } finally {
            forceStop(rootPid.get());
            forceStop(childPid.get());
            awaitNotAlive(rootPid.get(), Duration.ofSeconds(5));
            awaitNotAlive(childPid.get(), Duration.ofSeconds(5));
            if (Files.exists(log)) awaitDelete(log);
        }
    }

    @Test
    void exactEnvironmentLaunchUsesTheAuthenticatedWorkingDirectory() throws Exception {
        Path working = Files.createDirectory(temp.resolve("engine-directory"));
        Path log = temp.resolve("working-directory-child.log");
        List<String> command = javaCommand(ExactLaunchReporter.class);
        Map<String, String> exactEnvironment = new LinkedHashMap<>();
        exactEnvironment.put("TMP", temp.toString());
        if ("\\".equals(java.nio.file.FileSystems.getDefault().getSeparator())) {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null) systemRoot = System.getenv("SYSTEMROOT");
            assertNotNull(systemRoot);
            exactEnvironment.put("SYSTEMROOT", systemRoot);
        }
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher((process, builder, observed) -> {
                    assertEquals(exactEnvironment, builder.environment());
                    assertFalse(builder.environment().containsKey("PATH"));
                    long started = process.toHandle().info().startInstant()
                            .map(java.time.Instant::toEpochMilli)
                            .orElse(0L);
                    return new LlamaCppProcessLaunchObservation(
                            process.pid(),
                            started,
                            Math.max(Math.max(1L, observed), started),
                            "0123456789abcdef0123456789abcdef",
                            ProviderProcessLaunchReceipt.digestOrderedValues(builder.command()),
                            ProviderProcessLaunchReceipt.digestEffectiveEnvironment(
                                    builder.environment()));
                });

        LlamaCppProcess child = launcher.startWithExactEnvironment(
                command, log, exactEnvironment, working);

        assertTrue(child.waitFor(Duration.ofSeconds(10)));
        assertNotNull(child.terminationLease().lastOutcome());
        assertTrue(child.terminationLease().lastOutcome().terminated(),
                "natural exit must settle the process ownership lease");
        assertEquals(
                working.toAbsolutePath().normalize()
                        + "|" + temp + "|false|"
                        + String.join(",", new TreeSet<>(exactEnvironment.keySet())),
                Files.readString(log).strip());
    }

    @Test
    void naturalExitDrainsHighVolumeOutputBeforeReturning() throws Exception {
        Path log = temp.resolve("high-volume-child.log");
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher();

        LlamaCppProcess child = launcher.start(
                javaCommand(HighVolumeReporter.class),
                log);

        assertTrue(child.waitFor(Duration.ofSeconds(15)));
        assertArrayEquals(expectedHighVolumeOutput(), Files.readAllBytes(log));
        assertTrue(child.outputCaptureHealthy());
        deleteImmediately(log);
    }

    @Test
    void nullLogLaunchStillDrainsHighVolumeOutputWithoutDeadlock()
            throws Exception {
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher();

        LlamaCppProcess child = launcher.start(
                javaCommand(HighVolumeReporter.class),
                null);

        assertTrue(child.waitFor(Duration.ofSeconds(15)));
        assertTrue(child.terminationLease().lastOutcome().terminated());
        assertTrue(child.outputCaptureHealthy());
    }

    @Test
    void naturallyExitedUnsettledAdapterIsRecoveredBeforeReplacementLaunch()
            throws Exception {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher(
                        ProcessBuilderLlamaCppProcessLauncher::observeLaunch,
                        owner);
        Path firstLog = temp.resolve("naturally-exited-unsettled.log");
        Path secondLog = temp.resolve("replacement-after-natural-exit.log");
        LlamaCppProcess first = launcher.start(
                javaCommand(ShortReporter.class), firstLog);

        awaitProcessExit(first, Duration.ofSeconds(10));
        assertEquals(null, first.terminationLease().lastOutcome());

        LlamaCppProcess second = startEventually(
                launcher,
                sleeperCommand(),
                secondLog,
                Duration.ofSeconds(5));
        try {
            assertTrue(first.terminationLease().lastOutcome().terminated());
            assertTrue(first.outputCaptureHealthy());
            assertTrue(second.isAlive());
        } finally {
            second.destroyForcibly();
            assertTrue(second.waitFor(Duration.ofSeconds(2)));
            deleteImmediately(firstLog);
            deleteImmediately(secondLog);
        }
    }

    @Test
    void sinkOpenFailureSettlesChildAndReleasesLaunchPermit() throws Exception {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher(
                        ProcessBuilderLlamaCppProcessLauncher::observeLaunch,
                        owner);
        Path invalidLog = Files.createDirectory(temp.resolve("log-directory"));

        assertThrows(IOException.class,
                () -> launcher.start(sleeperCommand(), invalidLog));
        assertEquals(0, owner.ownedLeaseCount());

        Path replacementLog = temp.resolve("replacement-after-sink-failure.log");
        LlamaCppProcess replacement = launcher.start(
                sleeperCommand(), replacementLog);
        try {
            assertTrue(replacement.isAlive());
        } finally {
            replacement.destroyForcibly();
            assertTrue(replacement.waitFor(Duration.ofSeconds(2)));
            deleteImmediately(replacementLog);
        }
    }

    @Test
    void directForcedTerminationDoesNotStartAGracefulCaptureRace()
            throws Exception {
        AtomicReference<GracefulCaptureRaceTarget> observedTarget =
                new AtomicReference<>();
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner((process, resources) -> {
                    GracefulCaptureRaceTarget target =
                            new GracefulCaptureRaceTarget(process, resources);
                    observedTarget.set(target);
                    return target;
                });
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher(
                        ProcessBuilderLlamaCppProcessLauncher::observeLaunch,
                        owner);
        Path log = temp.resolve("forced-only-capture-race.log");
        LlamaCppProcess child = launcher.start(sleeperCommand(), log);

        try {
            try {
                child.destroyForcibly();
            } catch (LlamaCppProcessTerminationOwner.Unsettled unsettled) {
                assertEquals(
                        LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                        unsettled.outcome().state());
                assertTrue(unsettled.outcome().causes().contains(
                        LlamaCppProcessTerminationOwner.Cause
                                .TREE_CAPTURE_UNSTABLE));
                throw unsettled;
            }

            GracefulCaptureRaceTarget target = observedTarget.get();
            assertNotNull(target);
            assertEquals(0, target.gracefulSignals.get(),
                    "an explicitly forced operation must not begin a graceful phase");
            assertEquals(1, target.forcedSignals.get());
            assertEquals(0, owner.ownedLeaseCount());
            assertFalse(target.process.isAlive());
        } finally {
            GracefulCaptureRaceTarget target = observedTarget.get();
            if (target != null && target.process.isAlive()) {
                target.process.destroyForcibly();
                target.process.waitFor(5L, TimeUnit.SECONDS);
            }
            if (Files.exists(log)) awaitDelete(log);
        }
    }

    @Test
    void pumpThreadStartFailureStillClosesEveryOpenedCaptureHandle() {
        TrackingInputStream source = new TrackingInputStream(
                new ByteArrayInputStream("unused".getBytes(StandardCharsets.UTF_8)));
        TrackingOutputStream sink = new TrackingOutputStream(0);
        TrackingProcess process = new TrackingProcess(source);
        AtomicInteger threadRequests = new AtomicInteger();
        ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture capture =
                new ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture(
                        () -> sink,
                        (name, task) -> {
                            if (threadRequests.incrementAndGet() == 1) {
                                throw new IllegalStateException(
                                        "injected pump thread start failure");
                            }
                            return Thread.ofPlatform()
                                    .daemon(true)
                                    .name(name)
                                    .unstarted(task);
                        });

        assertThrows(IllegalStateException.class,
                () -> capture.start(process));
        assertFalse(capture.healthy());
        capture.settle(Duration.ofSeconds(1));
        assertTrue(source.closed.get());
        assertTrue(sink.closed.get());
        assertEquals(2, threadRequests.get());
    }

    @Test
    void closerThreadStartFailureCanBeRetriedWithoutLosingHandles()
            throws Exception {
        TrackingInputStream source = new TrackingInputStream(
                new ByteArrayInputStream("complete".getBytes(StandardCharsets.UTF_8)));
        TrackingOutputStream sink = new TrackingOutputStream(1);
        TrackingProcess process = new TrackingProcess(source);
        AtomicInteger threadRequests = new AtomicInteger();
        ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture capture =
                new ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture(
                        () -> sink,
                        (name, task) -> {
                            int request = threadRequests.incrementAndGet();
                            if (request == 2) {
                                throw new IllegalStateException(
                                        "injected closer thread start failure");
                            }
                            return Thread.ofPlatform()
                                    .daemon(true)
                                    .name(name)
                                    .unstarted(task);
                        });
        capture.start(process);
        awaitCondition(() -> sink.closeAttempts.get() == 1,
                Duration.ofSeconds(2), "pump did not reach sink close");

        assertThrows(IllegalStateException.class,
                () -> capture.settle(Duration.ofMillis(100)));
        assertFalse(sink.closed.get());

        capture.settle(Duration.ofSeconds(1));
        assertTrue(source.closed.get());
        assertTrue(sink.closed.get());
        assertEquals(3, threadRequests.get());
    }

    @Test
    void callerJoiningFailedCaptureAttemptRunsOneSerializedFollowOn()
            throws Exception {
        TrackingInputStream source = new TrackingInputStream(
                new ByteArrayInputStream("complete".getBytes(StandardCharsets.UTF_8)));
        TrackingOutputStream sink = new TrackingOutputStream(2);
        TrackingProcess process = new TrackingProcess(source);
        CountDownLatch firstBeforeCompletion = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondBeforeCompletion = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(2);
        CountDownLatch interruptObserved = new CountDownLatch(1);
        CountDownLatch followOnSelections = new CountDownLatch(2);
        AtomicInteger completions = new AtomicInteger();
        AtomicLong joinedSequence = new AtomicLong(-1L);
        AtomicBoolean followOnMismatch = new AtomicBoolean();
        ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture capture =
                new ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture(
                        () -> sink,
                        platformThreadFactory(),
                        () -> {
                            int completion = completions.incrementAndGet();
                            if (completion == 1) {
                                firstBeforeCompletion.countDown();
                                awaitRestoringInterrupt(releaseFirst);
                            } else if (completion == 2) {
                                secondBeforeCompletion.countDown();
                                awaitRestoringInterrupt(releaseSecond);
                            }
                        },
                        new ProcessBuilderLlamaCppProcessLauncher
                                .CaptureAttemptJoinObserver() {
                            @Override
                            public void joined(long sequence) {
                                joinedSequence.updateAndGet(previous ->
                                        previous == -1L || previous == sequence
                                                ? sequence
                                                : -2L);
                                joined.countDown();
                            }

                            @Override
                            public void interrupted(long sequence) {
                                interruptObserved.countDown();
                            }

                            @Override
                            public void followOnSelected(
                                    long predecessor,
                                    long followOn) {
                                if (predecessor != 1L || followOn != 2L) {
                                    followOnMismatch.set(true);
                                }
                                followOnSelections.countDown();
                            }
                        });
        capture.start(process);
        assertTrue(sink.firstCloseAttempt.await(2L, TimeUnit.SECONDS),
                "pump did not reach sink close");

        AtomicReference<Boolean> firstResult = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Boolean> interruptedResult = new AtomicReference<>();
        AtomicReference<Throwable> interruptedFailure = new AtomicReference<>();
        AtomicReference<Boolean> otherResult = new AtomicReference<>();
        AtomicReference<Throwable> otherFailure = new AtomicReference<>();
        Thread first = null;
        Thread interruptedFollower = null;
        Thread otherFollower = null;
        try {
            first = Thread.ofPlatform().start(() -> {
                try {
                    firstResult.set(capture.settle(Duration.ofSeconds(2)));
                } catch (Throwable failure) {
                    firstFailure.set(failure);
                }
            });
            assertTrue(firstBeforeCompletion.await(2L, TimeUnit.SECONDS));

            interruptedFollower = Thread.ofPlatform().start(() -> {
                try {
                    interruptedResult.set(capture.settle(Duration.ofSeconds(2)));
                } catch (Throwable failure) {
                    interruptedFailure.set(failure);
                }
            });
            otherFollower = Thread.ofPlatform().start(() -> {
                try {
                    otherResult.set(capture.settle(Duration.ofSeconds(2)));
                } catch (Throwable failure) {
                    otherFailure.set(failure);
                }
            });
            assertTrue(joined.await(2L, TimeUnit.SECONDS),
                    "both followers must join the exact older attempt");
            assertEquals(2, sink.closeAttempts.get(),
                    "the follow-on must not overlap its active predecessor");
            interruptedFollower.interrupt();
            assertTrue(interruptObserved.await(2L, TimeUnit.SECONDS),
                    "the joined interrupt was not consumed by the N wait");
            releaseFirst.countDown();
            assertTrue(secondBeforeCompletion.await(2L, TimeUnit.SECONDS),
                    "the exact N+1 completion barrier was not reached");
            assertTrue(followOnSelections.await(2L, TimeUnit.SECONDS),
                    "every N follower must select exact N+1 before release");
            assertFalse(followOnMismatch.get());
            releaseSecond.countDown();
            joinCaller(first);
            joinCaller(interruptedFollower);
            joinCaller(otherFollower);

            assertFalse(first.isAlive());
            assertFalse(interruptedFollower.isAlive());
            assertFalse(otherFollower.isAlive());
            assertEquals(1L, joinedSequence.get());
            assertEquals(null, interruptedFailure.get());
            assertEquals(null, otherFailure.get());
            assertEquals(Boolean.TRUE, interruptedResult.get(),
                    "the N interrupt must survive the N+1 completion wait");
            assertEquals(Boolean.FALSE, otherResult.get());
            assertTrue(firstFailure.get() == null
                            || firstFailure.get() instanceof IllegalStateException,
                    "the creator may observe either the shared repair or its own failure");
            if (firstFailure.get() == null) {
                assertEquals(Boolean.FALSE, firstResult.get());
            }
            assertTrue(source.closed.get());
            assertTrue(sink.closed.get());
            assertEquals(3, sink.closeAttempts.get(),
                    "pump, failed owner, and one follow-on are the only close attempts");
            assertEquals(2, completions.get(),
                    "joined callers must share exactly one follow-on generation");
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            joinCaller(first);
            joinCaller(interruptedFollower);
            joinCaller(otherFollower);
        }
    }

    @Test
    void expiredCaptureJoinCannotRenewItsDeadlineOrStartAFollowOn()
            throws Exception {
        TrackingInputStream source = new TrackingInputStream(
                new ByteArrayInputStream("complete".getBytes(StandardCharsets.UTF_8)));
        TrackingOutputStream sink = new TrackingOutputStream(2);
        TrackingProcess process = new TrackingProcess(source);
        CountDownLatch firstBeforeCompletion = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture capture =
                new ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture(
                        () -> sink,
                        platformThreadFactory(),
                        () -> {
                            if (completions.incrementAndGet() == 1) {
                                firstBeforeCompletion.countDown();
                                awaitRestoringInterrupt(releaseFirst);
                            }
                        },
                        sequence -> joined.countDown());
        capture.start(process);
        assertTrue(sink.firstCloseAttempt.await(2L, TimeUnit.SECONDS),
                "pump did not reach sink close");

        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> expiredFailure = new AtomicReference<>();
        Thread first = null;
        Thread expired = null;
        try {
            first = Thread.ofPlatform().start(() -> {
                try {
                    capture.settle(Duration.ofSeconds(2));
                } catch (Throwable failure) {
                    firstFailure.set(failure);
                }
            });
            assertTrue(firstBeforeCompletion.await(2L, TimeUnit.SECONDS));

            expired = Thread.ofPlatform().start(() -> {
                try {
                    capture.settle(Duration.ofMillis(25));
                } catch (Throwable failure) {
                    expiredFailure.set(failure);
                }
            });
            assertTrue(joined.await(2L, TimeUnit.SECONDS));
            joinCaller(expired);

            assertFalse(expired.isAlive());
            assertTrue(expiredFailure.get() instanceof IllegalStateException);
            assertTrue(expiredFailure.get().getMessage().contains(
                    "closure timed out"));
            assertEquals(2, sink.closeAttempts.get());

            releaseFirst.countDown();
            joinCaller(first);
            assertFalse(first.isAlive());
            assertTrue(firstFailure.get() instanceof IllegalStateException);
            assertFalse(sink.closed.get());
            assertEquals(2, sink.closeAttempts.get(),
                    "an expired join must not launch work after its deadline");

            assertFalse(capture.settle(Duration.ofSeconds(1)));
            assertTrue(sink.closed.get());
            assertEquals(3, sink.closeAttempts.get());
            assertEquals(2, completions.get());
        } finally {
            releaseFirst.countDown();
            joinCaller(first);
            joinCaller(expired);
        }
    }

    @Test
    void persistentCaptureFailureStopsAfterOneJoinedFollowOn()
            throws Exception {
        TrackingInputStream source = new TrackingInputStream(
                new ByteArrayInputStream("complete".getBytes(StandardCharsets.UTF_8)));
        TrackingOutputStream sink = new TrackingOutputStream(3);
        TrackingProcess process = new TrackingProcess(source);
        CountDownLatch firstBeforeCompletion = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture capture =
                new ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture(
                        () -> sink,
                        platformThreadFactory(),
                        () -> {
                            if (completions.incrementAndGet() == 1) {
                                firstBeforeCompletion.countDown();
                                awaitRestoringInterrupt(releaseFirst);
                            }
                        },
                        sequence -> joined.countDown());
        capture.start(process);
        assertTrue(sink.firstCloseAttempt.await(2L, TimeUnit.SECONDS),
                "pump did not reach sink close");

        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> joinedFailure = new AtomicReference<>();
        Thread first = null;
        Thread follower = null;
        try {
            first = Thread.ofPlatform().start(() -> {
                try {
                    capture.settle(Duration.ofSeconds(2));
                } catch (Throwable failure) {
                    firstFailure.set(failure);
                }
            });
            assertTrue(firstBeforeCompletion.await(2L, TimeUnit.SECONDS));

            follower = Thread.ofPlatform().start(() -> {
                try {
                    capture.settle(Duration.ofSeconds(2));
                } catch (Throwable failure) {
                    joinedFailure.set(failure);
                }
            });
            assertTrue(joined.await(2L, TimeUnit.SECONDS));
            assertEquals(2, sink.closeAttempts.get());
            releaseFirst.countDown();
            joinCaller(first);
            joinCaller(follower);

            assertFalse(first.isAlive());
            assertFalse(follower.isAlive());
            assertTrue(firstFailure.get() instanceof IllegalStateException);
            assertTrue(joinedFailure.get() instanceof IllegalStateException);
            assertFalse(sink.closed.get());
            assertEquals(3, sink.closeAttempts.get(),
                    "one joined call may run N+1 but never N+2");
            assertEquals(2, completions.get());

            assertFalse(capture.settle(Duration.ofSeconds(1)));
            assertTrue(sink.closed.get());
            assertEquals(4, sink.closeAttempts.get(),
                    "a later caller owns a new bounded attempt");
            assertEquals(3, completions.get());
        } finally {
            releaseFirst.countDown();
            joinCaller(first);
            joinCaller(follower);
        }
    }

    @Test
    void unkillableRawObservationFailureRetainsExactChildUntilExitProof()
            throws Exception {
        AtomicReference<Process> rawChild = new AtomicReference<>();
        AtomicReference<RawProcessTarget> exactTarget = new AtomicReference<>();
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner((process, resources) -> {
                    RawProcessTarget target = new RawProcessTarget(
                            process, resources, true);
                    exactTarget.set(target);
                    return target;
                });
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher(
                        (process, builder, observed) -> {
                            rawChild.set(process);
                            throw new IllegalStateException(
                                    "injected observation failure");
                        },
                        owner);
        Path log = temp.resolve("retained-raw-observation-child.log");

        LlamaCppProcessTerminationOwner.Unsettled unsettled = assertThrows(
                LlamaCppProcessTerminationOwner.Unsettled.class,
                () -> launcher.start(sleeperCommand(), log));
        assertNotNull(rawChild.get());
        assertTrue(rawChild.get().isAlive());
        assertSame(rawChild.get(), exactTarget.get().process);
        assertTrue(unsettled.lease().owns(
                rawChild.get(), exactTarget.get().resources));
        assertEquals(1, owner.ownedLeaseCount());

        ProcessBuilderLlamaCppProcessLauncher replacement =
                new ProcessBuilderLlamaCppProcessLauncher(
                        ProcessBuilderLlamaCppProcessLauncher::observeLaunch,
                        owner);
        assertThrows(
                LlamaCppProcessTerminationOwner.LaunchBlocked.class,
                () -> replacement.start(
                        List.of(temp.resolve("must-not-start").toString()),
                        temp.resolve("blocked-replacement.log")));

        rawChild.get().destroyForcibly();
        assertTrue(rawChild.get().waitFor(5L, TimeUnit.SECONDS));
        assertThrows(IOException.class, () -> replacement.start(
                List.of(temp.resolve("missing-after-exit-proof").toString()),
                temp.resolve("released-replacement.log")));
        assertEquals(0, owner.ownedLeaseCount());
        deleteImmediately(log);
    }

    @Test
    void captureTimeoutRetainsOpenHandlesThenRetryClosesBeforeRelease()
            throws Exception {
        BlockingInputStream source = new BlockingInputStream();
        TrackingOutputStream sink = new TrackingOutputStream(0);
        ProductionCapture fixture = startProductionCapture(source, sink);
        try {
            assertTrue(source.readEntered.await(2L, TimeUnit.SECONDS));

            LlamaCppProcessTerminationOwner.Outcome first =
                    fixture.lease.terminate(
                            capturePolicy(Duration.ofMillis(25)));
            assertTrue(fixture.settlement.entered.await(2L, TimeUnit.SECONDS));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                    first.state());
            assertTrue(first.causes().contains(
                    LlamaCppProcessTerminationOwner.Cause
                            .RESOURCE_SETTLEMENT_FAILED));
            assertFalse(source.closed.get());
            assertFalse(sink.closed.get());

            source.release.countDown();
            LlamaCppProcessTerminationOwner.Outcome second =
                    fixture.lease.terminate(
                            capturePolicy(Duration.ofSeconds(2)));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.TERMINATED,
                    second.state());
            assertTrue(source.closed.get());
            assertTrue(sink.closed.get());
            assertEquals(0, fixture.owner.ownedLeaseCount());
        } finally {
            source.release.countDown();
            fixture.forceStop();
        }
    }

    @Test
    void interruptDuringCaptureSettlementIsRestoredAfterBoundedCleanup()
            throws Exception {
        BlockingInputStream source = new BlockingInputStream();
        TrackingOutputStream sink = new TrackingOutputStream(2);
        ProductionCapture fixture = startProductionCapture(source, sink);
        try {
            assertTrue(source.readEntered.await(2L, TimeUnit.SECONDS));
            AtomicReference<LlamaCppProcessTerminationOwner.Outcome> outcome =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interruptRestored = new AtomicBoolean();
            Thread terminator = Thread.ofPlatform().start(() -> {
                try {
                    outcome.set(fixture.lease.terminate(
                            capturePolicy(Duration.ofSeconds(2))));
                    interruptRestored.set(
                            Thread.currentThread().isInterrupted());
                } catch (Throwable unavailable) {
                    failure.set(unavailable);
                }
            });
            assertTrue(fixture.settlement.entered.await(2L, TimeUnit.SECONDS),
                    "termination did not enter exact capture settlement");

            terminator.interrupt();
            source.release.countDown();
            terminator.join(3_000L);

            assertFalse(terminator.isAlive());
            assertEquals(null, failure.get());
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                    outcome.get().state());
            assertTrue(outcome.get().causes().contains(
                    LlamaCppProcessTerminationOwner.Cause
                            .RESOURCE_SETTLEMENT_FAILED));
            assertTrue(outcome.get().interruptRestored());
            assertTrue(interruptRestored.get());
            assertTrue(source.closed.get());
            assertFalse(sink.closed.get());

            LlamaCppProcessTerminationOwner.Outcome retry =
                    fixture.lease.terminate(
                            capturePolicy(Duration.ofSeconds(1)));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.TERMINATED,
                    retry.state());
            assertTrue(sink.closed.get());
            assertEquals(0, fixture.owner.ownedLeaseCount());
        } finally {
            source.release.countDown();
            fixture.forceStop();
        }
    }

    @Test
    void timeoutCannotReleaseBeforeExactResourceAttemptCompletionOrLoseInterrupt()
            throws Exception {
        CountDownLatch beforeCompletion = new CountDownLatch(1);
        CountDownLatch releaseCompletion = new CountDownLatch(1);
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner(() -> {
                    beforeCompletion.countDown();
                    boolean interrupted = false;
                    while (releaseCompletion.getCount() != 0L) {
                        try {
                            releaseCompletion.await();
                        } catch (InterruptedException stop) {
                            interrupted = true;
                            Thread.interrupted();
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                });
        Process raw = new ProcessBuilder(sleeperCommand()).start();
        LlamaCppProcessTerminationOwner.ResourceSettlement resources =
                new LlamaCppProcessTerminationOwner.ResourceSettlement() {
                    @Override
                    public boolean settle(Duration timeout) {
                        try {
                            raw.getInputStream().close();
                        } catch (IOException unavailable) {
                            throw new IllegalStateException(unavailable);
                        }
                        return true;
                    }

                    @Override
                    public boolean ownsProcessStdout() {
                        return true;
                    }
                };
        LlamaCppProcessTerminationOwner.Lease lease;
        try (LlamaCppProcessTerminationOwner.LaunchPermit permit =
                     owner.retryRetainedAndAcquire(
                             capturePolicy(Duration.ofSeconds(1)))) {
            lease = permit.adopt(raw, resources);
        }
        try {
            LlamaCppProcessTerminationOwner.Outcome timedOut = lease.terminate(
                    new LlamaCppProcessTerminationOwner.Policy(
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1),
                            Duration.ofMillis(25)));

            assertTrue(beforeCompletion.await(2L, TimeUnit.SECONDS));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                    timedOut.state(),
                    "closed flags cannot release ownership before the exact "
                            + "closer attempt completes");
            assertTrue(timedOut.causes().contains(
                    LlamaCppProcessTerminationOwner.Cause
                            .RESOURCE_SETTLEMENT_FAILED));
            assertFalse(timedOut.interruptRestored());
            assertEquals(1, owner.ownedLeaseCount());

            LlamaCppProcessTerminationOwner.Outcome stillPending = lease.terminate(
                    new LlamaCppProcessTerminationOwner.Policy(
                            Duration.ZERO,
                            Duration.ZERO,
                            Duration.ofMillis(25)));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                    stillPending.state(),
                    "closed flags cannot bypass the still-active exact attempt");
            assertFalse(stillPending.interruptRestored());
            assertEquals(1, owner.ownedLeaseCount());

            releaseCompletion.countDown();
            LlamaCppProcessTerminationOwner.Outcome completed = lease.terminate(
                    capturePolicy(Duration.ofSeconds(2)));

            assertEquals(
                    LlamaCppProcessTerminationOwner.State.TERMINATED,
                    completed.state());
            assertTrue(completed.causes().contains(
                    LlamaCppProcessTerminationOwner.Cause.INTERRUPTED));
            assertTrue(completed.interruptRestored());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, owner.ownedLeaseCount());
        } finally {
            Thread.interrupted();
            releaseCompletion.countDown();
            raw.destroyForcibly();
            raw.waitFor(5L, TimeUnit.SECONDS);
        }
    }

    @Test
    void callerJoiningFailedProcessResourcesRunsOneSerializedFollowOn()
            throws Exception {
        CountDownLatch firstBeforeCompletion = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondBeforeCompletion = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);
        CountDownLatch interruptObserved = new CountDownLatch(1);
        CountDownLatch followOnSelected = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicLong joinedSequence = new AtomicLong(-1L);
        AtomicBoolean followOnMismatch = new AtomicBoolean();
        List<Duration> delegatedBudgets = new ArrayList<>();
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner(
                        () -> {
                            int completion = completions.incrementAndGet();
                            if (completion == 1) {
                                firstBeforeCompletion.countDown();
                                awaitRestoringInterrupt(releaseFirst);
                            } else if (completion == 2) {
                                secondBeforeCompletion.countDown();
                                awaitRestoringInterrupt(releaseSecond);
                            }
                        },
                        new LlamaCppProcessTerminationOwner
                                .ResourceAttemptJoinObserver() {
                            @Override
                            public void joined(long sequence) {
                                joinedSequence.set(sequence);
                                joined.countDown();
                            }

                            @Override
                            public void interrupted(long sequence) {
                                interruptObserved.countDown();
                            }

                            @Override
                            public void followOnSelected(
                                    long predecessor,
                                    long followOn) {
                                if (predecessor != 1L || followOn != 2L) {
                                    followOnMismatch.set(true);
                                }
                                followOnSelected.countDown();
                            }
                        });
        Process raw = new ProcessBuilder(sleeperCommand()).start();
        LlamaCppProcessTerminationOwner.ResourceSettlement resources = timeout -> {
            int inFlight = active.incrementAndGet();
            maxActive.accumulateAndGet(inFlight, Math::max);
            try {
                delegatedBudgets.add(timeout);
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException(
                            "injected first resource settlement failure");
                }
                return false;
            } finally {
                active.decrementAndGet();
            }
        };
        LlamaCppProcessTerminationOwner.Lease lease;
        try (LlamaCppProcessTerminationOwner.LaunchPermit permit =
                     owner.retryRetainedAndAcquire(
                             capturePolicy(Duration.ofSeconds(1)))) {
            lease = permit.adopt(raw, resources);
        }
        try {
            LlamaCppProcessTerminationOwner.Outcome first = lease.terminate(
                    capturePolicy(Duration.ofMillis(250)));
            assertTrue(firstBeforeCompletion.await(2L, TimeUnit.SECONDS));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                    first.state());
            assertTrue(first.causes().contains(
                    LlamaCppProcessTerminationOwner.Cause
                            .RESOURCE_SETTLEMENT_FAILED));
            assertEquals(1, owner.ownedLeaseCount());

            AtomicReference<LlamaCppProcessTerminationOwner.Outcome> outcome =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interruptRestored = new AtomicBoolean();
            Thread follower = Thread.ofPlatform().start(() -> {
                try {
                    outcome.set(lease.terminate(
                            capturePolicy(Duration.ofSeconds(2))));
                    interruptRestored.set(
                            Thread.currentThread().isInterrupted());
                } catch (Throwable unavailable) {
                    failure.set(unavailable);
                }
            });
            assertTrue(joined.await(2L, TimeUnit.SECONDS),
                    "the retry did not join the exact active resource attempt");
            assertEquals(1, attempts.get(),
                    "the follow-on must not overlap its active predecessor");
            follower.interrupt();
            assertTrue(interruptObserved.await(2L, TimeUnit.SECONDS),
                    "the joined interrupt was not consumed by the N wait");
            releaseFirst.countDown();
            assertTrue(secondBeforeCompletion.await(2L, TimeUnit.SECONDS),
                    "the resource N+1 completion barrier was not reached");
            assertTrue(followOnSelected.await(2L, TimeUnit.SECONDS));
            assertFalse(followOnMismatch.get());
            releaseSecond.countDown();
            joinCaller(follower);

            assertFalse(follower.isAlive());
            assertEquals(null, failure.get());
            assertEquals(1L, joinedSequence.get());
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.TERMINATED,
                    outcome.get().state());
            assertTrue(outcome.get().causes().contains(
                    LlamaCppProcessTerminationOwner.Cause.INTERRUPTED));
            assertTrue(outcome.get().interruptRestored());
            assertTrue(interruptRestored.get());
            assertEquals(2, attempts.get());
            assertEquals(2, completions.get());
            assertEquals(1, maxActive.get(),
                    "resource attempts must remain serialized");
            assertEquals(2, delegatedBudgets.size());
            Duration followOnBudget = delegatedBudgets.get(1);
            assertTrue(followOnBudget.compareTo(Duration.ZERO) > 0,
                    "the follow-on needs a positive remaining budget");
            assertTrue(followOnBudget.compareTo(Duration.ofSeconds(2)) < 0,
                    "the follow-on may not receive the caller's full budget");
            assertFalse(followOnBudget.equals(delegatedBudgets.get(0)),
                    "the follow-on must not reuse the older attempt's budget");
            assertEquals(0, owner.ownedLeaseCount());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            raw.destroyForcibly();
            raw.waitFor(5L, TimeUnit.SECONDS);
        }
    }

    @Test
    void expiredProcessResourceJoinCannotRunDelegationAfterItsDeadline()
            throws Exception {
        Duration expiredBudget = Duration.ofMillis(25);
        CountDownLatch joined = new CountDownLatch(1);
        CountDownLatch firstCompleted = new CountDownLatch(1);
        CountDownLatch delegatedCalled = new CountDownLatch(1);
        AtomicReference<Duration> delegatedBudget = new AtomicReference<>();
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner(
                        firstCompleted::countDown,
                        sequence -> joined.countDown());
        Process raw = new ProcessBuilder(sleeperCommand()).start();
        BlockingClose stdinBlocker = new BlockingClose();
        DelegatingProcess process = new DelegatingProcess(
                raw,
                new BlockingCloseOutputStream(
                        raw.getOutputStream(),
                        stdinBlocker),
                raw.getInputStream(),
                raw.getErrorStream());
        LlamaCppProcessTerminationOwner.ResourceSettlement resources = timeout -> {
            delegatedBudget.set(timeout);
            delegatedCalled.countDown();
            return false;
        };
        LlamaCppProcessTerminationOwner.Lease lease;
        try (LlamaCppProcessTerminationOwner.LaunchPermit permit =
                     owner.retryRetainedAndAcquire(
                             capturePolicy(Duration.ofSeconds(1)))) {
            lease = permit.adopt(process, resources);
        }
        try {
            LlamaCppProcessTerminationOwner.Outcome first = lease.terminate(
                    capturePolicy(expiredBudget));
            assertTrue(stdinBlocker.entered.await(2L, TimeUnit.SECONDS));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                    first.state());

            AtomicReference<LlamaCppProcessTerminationOwner.Outcome> expired =
                    new AtomicReference<>();
            Thread follower = Thread.ofPlatform().start(() -> expired.set(
                    lease.terminate(capturePolicy(expiredBudget))));
            assertTrue(joined.await(2L, TimeUnit.SECONDS));
            joinCaller(follower);
            assertFalse(follower.isAlive());
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                    expired.get().state());
            assertEquals(null, delegatedBudget.get(),
                    "delegation cannot run while stdin consumes the old budget");

            stdinBlocker.release.countDown();
            assertTrue(firstCompleted.await(2L, TimeUnit.SECONDS));
            assertFalse(delegatedCalled.await(100L, TimeUnit.MILLISECONDS),
                    "expired closure must not perform late delegated work");

            Duration retryBudget = Duration.ofSeconds(1);
            LlamaCppProcessTerminationOwner.Outcome retry = lease.terminate(
                    capturePolicy(retryBudget));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.TERMINATED,
                    retry.state());
            Duration observed = delegatedBudget.get();
            assertNotNull(observed);
            assertTrue(observed.compareTo(Duration.ZERO) > 0);
            assertTrue(observed.compareTo(retryBudget) < 0,
                    "delegation must receive remaining, not full, retry budget");
            assertFalse(observed.equals(expiredBudget),
                    "the retry must not reuse the expired predecessor budget");
            assertEquals(0, owner.ownedLeaseCount());
        } finally {
            stdinBlocker.release.countDown();
            raw.destroyForcibly();
            raw.waitFor(5L, TimeUnit.SECONDS);
        }
    }

    @Test
    void persistentProcessResourceFailureStopsAfterOneJoinedFollowOn()
            throws Exception {
        CountDownLatch firstBeforeCompletion = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner(
                        () -> {
                            if (completions.incrementAndGet() == 1) {
                                firstBeforeCompletion.countDown();
                                awaitRestoringInterrupt(releaseFirst);
                            }
                        },
                        sequence -> joined.countDown());
        Process raw = new ProcessBuilder(sleeperCommand()).start();
        LlamaCppProcessTerminationOwner.ResourceSettlement resources = timeout -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new IllegalStateException(
                        "injected persistent resource settlement failure");
            }
            return false;
        };
        LlamaCppProcessTerminationOwner.Lease lease;
        try (LlamaCppProcessTerminationOwner.LaunchPermit permit =
                     owner.retryRetainedAndAcquire(
                             capturePolicy(Duration.ofSeconds(1)))) {
            lease = permit.adopt(raw, resources);
        }
        try {
            LlamaCppProcessTerminationOwner.Outcome first = lease.terminate(
                    capturePolicy(Duration.ofMillis(25)));
            assertTrue(firstBeforeCompletion.await(2L, TimeUnit.SECONDS));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                    first.state());

            AtomicReference<LlamaCppProcessTerminationOwner.Outcome> outcome =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread follower = Thread.ofPlatform().start(() -> {
                try {
                    outcome.set(lease.terminate(
                            capturePolicy(Duration.ofSeconds(2))));
                } catch (Throwable unavailable) {
                    failure.set(unavailable);
                }
            });
            assertTrue(joined.await(2L, TimeUnit.SECONDS));
            assertEquals(1, attempts.get());
            releaseFirst.countDown();
            follower.join(3_000L);

            assertFalse(follower.isAlive());
            assertEquals(null, failure.get());
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                    outcome.get().state());
            assertTrue(outcome.get().causes().contains(
                    LlamaCppProcessTerminationOwner.Cause
                            .RESOURCE_SETTLEMENT_FAILED));
            assertEquals(2, attempts.get(),
                    "one joined call may run N+1 but never N+2");
            assertEquals(2, completions.get());
            assertEquals(1, owner.ownedLeaseCount(),
                    "persistent failure must retain exact ownership");

            LlamaCppProcessTerminationOwner.Outcome independentRetry =
                    lease.terminate(capturePolicy(Duration.ofSeconds(1)));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.TERMINATED,
                    independentRetry.state());
            assertEquals(3, attempts.get(),
                    "a later caller owns a new bounded attempt");
            assertEquals(0, owner.ownedLeaseCount());
        } finally {
            releaseFirst.countDown();
            raw.destroyForcibly();
            raw.waitFor(5L, TimeUnit.SECONDS);
        }
    }

    @Test
    void transientSinkCloseFailureIsRetryableWithoutPoisoningCapture()
            throws Exception {
        TrackingInputStream source = new TrackingInputStream(
                new ByteArrayInputStream("complete".getBytes(StandardCharsets.UTF_8)));
        TrackingOutputStream sink = new TrackingOutputStream(2);
        ProductionCapture fixture = startProductionCapture(source, sink);
        try {
            awaitCondition(() -> sink.closeAttempts.get() >= 1,
                    Duration.ofSeconds(2), "pump did not attempt sink close");

            LlamaCppProcessTerminationOwner.Outcome first =
                    fixture.lease.terminate(
                            capturePolicy(Duration.ofMillis(100)));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                    first.state());
            assertFalse(sink.closed.get());
            assertTrue(fixture.capture.captureComplete());
            assertTrue(fixture.capture.healthy());

            LlamaCppProcessTerminationOwner.Outcome second =
                    fixture.lease.terminate(
                            capturePolicy(Duration.ofSeconds(1)));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.TERMINATED,
                    second.state());
            assertTrue(sink.closed.get());
            assertTrue(source.closed.get());
            assertEquals(3, sink.closeAttempts.get());
        } finally {
            fixture.forceStop();
        }
    }

    @Test
    void captureCloseFlagsCannotOutrunTheExactCloserCompletionBarrier()
            throws Exception {
        TrackingInputStream source = new TrackingInputStream(
                new ByteArrayInputStream("complete".getBytes(StandardCharsets.UTF_8)));
        TrackingOutputStream sink = new TrackingOutputStream(1);
        CountDownLatch beforeCompletion = new CountDownLatch(1);
        CountDownLatch releaseCompletion = new CountDownLatch(1);
        ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture capture =
                new ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture(
                        () -> sink,
                        (name, task) -> Thread.ofPlatform()
                                .daemon(true)
                                .name(name)
                                .unstarted(task),
                        () -> {
                            beforeCompletion.countDown();
                            boolean interrupted = false;
                            while (releaseCompletion.getCount() != 0L) {
                                try {
                                    releaseCompletion.await();
                                } catch (InterruptedException stop) {
                                    interrupted = true;
                                    Thread.interrupted();
                                }
                            }
                            if (interrupted) Thread.currentThread().interrupt();
                        });
        Process raw = new ProcessBuilder(sleeperCommand()).start();
        DelegatingProcess process = new DelegatingProcess(
                raw,
                raw.getOutputStream(),
                source,
                raw.getErrorStream());
        try {
            capture.start(process);
            awaitCondition(
                    () -> sink.closeAttempts.get() >= 1,
                    Duration.ofSeconds(2),
                    "pump did not expose the retryable close path");

            IllegalStateException timedOut = assertThrows(
                    IllegalStateException.class,
                    () -> capture.settle(Duration.ofMillis(25)));

            assertTrue(beforeCompletion.await(2L, TimeUnit.SECONDS));
            assertTrue(timedOut.getMessage().contains("closure timed out"));
            assertTrue(source.closed.get());
            assertTrue(sink.closed.get(),
                    "the red requires closed flags before worker completion");

            releaseCompletion.countDown();
            assertFalse(capture.settle(Duration.ofSeconds(2)));
            assertTrue(capture.captureComplete());
            assertTrue(capture.healthy());
        } finally {
            releaseCompletion.countDown();
            raw.destroyForcibly();
            raw.waitFor(5L, TimeUnit.SECONDS);
        }
    }

    @Test
    void transferFailureInvalidatesEvidenceButDoesNotRetainClosedResources()
            throws Exception {
        TrackingInputStream source = new TrackingInputStream(
                new ThrowingInputStream());
        TrackingOutputStream sink = new TrackingOutputStream(0);
        TrackingProcess process = new TrackingProcess(source);
        ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture capture =
                new ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture(
                        () -> sink);
        LlamaCppProcessTerminationOwner owner = captureOwner();
        LlamaCppProcessTerminationOwner.Lease lease;
        try (LlamaCppProcessTerminationOwner.LaunchPermit permit =
                     owner.retryRetainedAndAcquire(capturePolicy(
                             Duration.ofSeconds(1)))) {
            lease = permit.adopt(process, capture);
            capture.start(process);
        }

        LlamaCppProcessTerminationOwner.Outcome outcome =
                lease.terminate(capturePolicy(Duration.ofSeconds(1)));
        assertEquals(
                LlamaCppProcessTerminationOwner.State.TERMINATED,
                outcome.state());
        assertTrue(source.closed.get());
        assertTrue(sink.closed.get());
        assertFalse(capture.captureComplete());
        assertFalse(capture.healthy());
        assertNotNull(capture.captureFailure());
        assertEquals(0, owner.ownedLeaseCount());
    }

    @Test
    void defaultProcessTargetBoundsBlockingChildStdinClose() throws Exception {
        assertBlockingDefaultResourceClose(true);
    }

    @Test
    void defaultProcessTargetBoundsBlockingChildStderrClose() throws Exception {
        assertBlockingDefaultResourceClose(false);
    }

    @Test
    void defaultProcessTargetPropagatesDelegatedInterruptObservation()
            throws Exception {
        Process raw = new ProcessBuilder(sleeperCommand()).start();
        DelegatingProcess process = new DelegatingProcess(
                raw,
                raw.getOutputStream(),
                raw.getInputStream(),
                raw.getErrorStream());
        LlamaCppProcessTerminationOwner.ResourceSettlement settlement =
                timeout -> true;
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        LlamaCppProcessTerminationOwner.Lease lease;
        try (LlamaCppProcessTerminationOwner.LaunchPermit permit =
                     owner.retryRetainedAndAcquire(
                             capturePolicy(Duration.ofSeconds(1)))) {
            lease = permit.adopt(process, settlement);
        }
        try {
            LlamaCppProcessTerminationOwner.Outcome outcome =
                    lease.terminate(capturePolicy(Duration.ofSeconds(1)));

            assertEquals(
                    LlamaCppProcessTerminationOwner.State.TERMINATED,
                    outcome.state());
            assertTrue(outcome.causes().contains(
                    LlamaCppProcessTerminationOwner.Cause.INTERRUPTED));
            assertTrue(outcome.interruptRestored());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, owner.ownedLeaseCount());
        } finally {
            Thread.interrupted();
            raw.destroyForcibly();
            raw.waitFor(5L, TimeUnit.SECONDS);
        }
    }

    @Test
    void delegatedInterruptOnFailureIsRestoredAndRetryable()
            throws Exception {
        Process raw = new ProcessBuilder(sleeperCommand()).start();
        DelegatingProcess process = new DelegatingProcess(
                raw,
                raw.getOutputStream(),
                raw.getInputStream(),
                raw.getErrorStream());
        AtomicInteger attempts = new AtomicInteger();
        LlamaCppProcessTerminationOwner.ResourceSettlement settlement = timeout -> {
            if (attempts.incrementAndGet() == 1) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "injected interrupted resource failure");
            }
            return false;
        };
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        LlamaCppProcessTerminationOwner.Lease lease;
        try (LlamaCppProcessTerminationOwner.LaunchPermit permit =
                     owner.retryRetainedAndAcquire(
                             capturePolicy(Duration.ofSeconds(1)))) {
            lease = permit.adopt(process, settlement);
        }
        try {
            LlamaCppProcessTerminationOwner.Outcome first =
                    lease.terminate(capturePolicy(Duration.ofSeconds(1)));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                    first.state());
            assertTrue(first.causes().contains(
                    LlamaCppProcessTerminationOwner.Cause.INTERRUPTED));
            assertTrue(first.causes().contains(
                    LlamaCppProcessTerminationOwner.Cause
                            .RESOURCE_SETTLEMENT_FAILED));
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();

            LlamaCppProcessTerminationOwner.Outcome second =
                    lease.terminate(capturePolicy(Duration.ofSeconds(1)));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.TERMINATED,
                    second.state());
            assertFalse(Thread.currentThread().isInterrupted());
            assertEquals(2, attempts.get());
            assertEquals(0, owner.ownedLeaseCount());
        } finally {
            Thread.interrupted();
            raw.destroyForcibly();
            raw.waitFor(5L, TimeUnit.SECONDS);
        }
    }

    @Test
    void launcherWithoutAnExactEnvironmentImplementationFailsClosed() {
        LlamaCppProcessLauncher weakerLauncher = (command, log) -> null;

        assertThrows(UnsupportedOperationException.class,
                () -> weakerLauncher.startWithExactEnvironment(
                        List.of("provider"),
                        temp.resolve("unused.log"),
                        Map.of("PATH", "controlled"),
                        temp));
    }

    @Test
    void exactEnvironmentRejectsUnlistedValuesAndMissingWindowsSystemRoot() {
        ProcessBuilderLlamaCppProcessLauncher launcher =
                new ProcessBuilderLlamaCppProcessLauncher();

        assertThrows(IllegalArgumentException.class,
                () -> launcher.startWithExactEnvironment(
                        sleeperCommand(),
                        temp.resolve("unlisted.log"),
                        Map.of("TALOS_UNLISTED", "value"),
                        temp));
        if ("\\".equals(java.nio.file.FileSystems.getDefault().getSeparator())) {
            assertThrows(IllegalArgumentException.class,
                    () -> launcher.startWithExactEnvironment(
                            sleeperCommand(),
                            temp.resolve("missing-system-root.log"),
                            Map.of("TMP", temp.toString()),
                            temp));
        }
    }

    private static List<String> sleeperCommand() {
        return javaCommand(Sleeper.class);
    }

    private static List<String> javaCommand(Class<?> mainClass) {
        Path java = ProcessHandle.current().info().command()
                .map(Path::of)
                .orElseThrow();
        return List.of(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                mainClass.getName());
    }

    private static byte[] expectedHighVolumeOutput() {
        byte[] exact = new byte[2 * 1024 * 1024 + 5];
        Arrays.fill(exact, 0, exact.length - 5, (byte) 'x');
        byte[] suffix = "DONE\n".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(suffix, 0, exact, exact.length - suffix.length,
                suffix.length);
        return exact;
    }

    private static void awaitProcessExit(
            LlamaCppProcess process,
            Duration timeout) throws InterruptedException {
        awaitCondition(
                () -> !process.isAlive(),
                timeout,
                "child did not exit naturally");
    }

    private static void awaitCondition(
            BooleanSupplier condition,
            Duration timeout,
            String failure) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(5L);
        }
        assertTrue(condition.getAsBoolean(), failure);
    }

    private static ProcessBuilderLlamaCppProcessLauncher.CaptureThreadFactory
            platformThreadFactory() {
        return (name, task) -> Thread.ofPlatform()
                .daemon(true)
                .name(name)
                .unstarted(task);
    }

    private static void awaitRestoringInterrupt(CountDownLatch release) {
        boolean interrupted = false;
        while (release.getCount() != 0L) {
            try {
                release.await();
            } catch (InterruptedException stop) {
                interrupted = true;
                Thread.interrupted();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void joinCaller(Thread caller) throws InterruptedException {
        if (caller == null) return;
        caller.join(3_000L);
        if (caller.isAlive()) {
            caller.interrupt();
            caller.join(1_000L);
        }
    }

    private static LlamaCppProcess startEventually(
            ProcessBuilderLlamaCppProcessLauncher launcher,
            List<String> command,
            Path log,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        LlamaCppProcessTerminationOwner.LaunchBlocked last = null;
        while (System.nanoTime() < deadline) {
            try {
                return launcher.start(command, log);
            } catch (LlamaCppProcessTerminationOwner.LaunchBlocked blocked) {
                last = blocked;
                Thread.sleep(10L);
            }
        }
        throw new AssertionError(
                "naturally exited process ownership was never recovered",
                last);
    }

    private static LlamaCppProcessTerminationOwner captureOwner() {
        return new LlamaCppProcessTerminationOwner(
                CaptureTarget::new);
    }

    private static ProductionCapture startProductionCapture(
            InputStream source,
            TrackingOutputStream sink) throws Exception {
        Process raw = new ProcessBuilder(sleeperCommand()).start();
        DelegatingProcess process = new DelegatingProcess(
                raw,
                raw.getOutputStream(),
                source,
                raw.getErrorStream());
        ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture capture =
                new ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture(
                        () -> sink);
        SignallingResourceSettlement settlement =
                new SignallingResourceSettlement(capture);
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        try {
            LlamaCppProcessTerminationOwner.Lease lease;
            try (LlamaCppProcessTerminationOwner.LaunchPermit permit =
                         owner.retryRetainedAndAcquire(
                                 capturePolicy(Duration.ofSeconds(2)))) {
                lease = permit.adopt(process, settlement);
                capture.start(process);
            }
            return new ProductionCapture(
                    raw, owner, lease, capture, settlement);
        } catch (Exception | Error failure) {
            raw.destroyForcibly();
            raw.waitFor(5L, TimeUnit.SECONDS);
            throw failure;
        }
    }

    private static LlamaCppProcessTerminationOwner.Policy capturePolicy(
            Duration resourceTimeout) {
        return LlamaCppProcessTerminationOwner.Policy.forcedOnly(
                Duration.ofSeconds(2),
                resourceTimeout);
    }

    private static void assertBlockingDefaultResourceClose(boolean stdin)
            throws Exception {
        Process raw = new ProcessBuilder(sleeperCommand()).start();
        BlockingClose blocker = new BlockingClose();
        OutputStream childStdin = stdin
                ? new BlockingCloseOutputStream(raw.getOutputStream(), blocker)
                : raw.getOutputStream();
        InputStream childStderr = stdin
                ? raw.getErrorStream()
                : new BlockingCloseInputStream(raw.getErrorStream(), blocker);
        DelegatingProcess process = new DelegatingProcess(
                raw,
                childStdin,
                raw.getInputStream(),
                childStderr);
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(process);
        try {
            long started = System.nanoTime();
            LlamaCppProcessTerminationOwner.Outcome first = lease.terminate(
                    new LlamaCppProcessTerminationOwner.Policy(
                            Duration.ofMillis(100),
                            Duration.ofSeconds(2),
                            Duration.ofMillis(25)));
            long elapsedMillis = Duration.ofNanos(
                    System.nanoTime() - started).toMillis();

            assertTrue(blocker.entered.await(2L, TimeUnit.SECONDS));
            assertTrue(elapsedMillis < 2_000L,
                    "blocking process-stream close escaped its resource bound");
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                    first.state());
            assertTrue(first.causes().contains(
                    LlamaCppProcessTerminationOwner.Cause
                            .RESOURCE_SETTLEMENT_FAILED));
            assertEquals(1, owner.ownedLeaseCount());

            blocker.release.countDown();
            LlamaCppProcessTerminationOwner.Outcome second = lease.terminate(
                    capturePolicy(Duration.ofSeconds(2)));
            assertEquals(
                    LlamaCppProcessTerminationOwner.State.TERMINATED,
                    second.state());
            assertEquals(0, owner.ownedLeaseCount());
        } finally {
            blocker.release.countDown();
            raw.destroyForcibly();
            raw.waitFor(5L, TimeUnit.SECONDS);
        }
    }

    private static void awaitDelete(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (java.io.IOException busy) {
                last = busy;
                Thread.sleep(10L);
            }
        }
        throw new AssertionError(
                "terminated child retained its redirected log handle: " + path,
                last);
    }

    private static void deleteImmediately(Path path) throws Exception {
        Files.deleteIfExists(path);
    }

    private static void assertNotAlive(long processId, String message) {
        ProcessHandle handle = ProcessHandle.of(processId).orElse(null);
        assertFalse(handle != null && handle.isAlive(), message);
    }

    private static long awaitPublishedChild(
            Process root,
            Path pidFile,
            Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (Files.isRegularFile(pidFile)) {
                    long child = Long.parseLong(
                            Files.readString(pidFile).strip());
                    boolean visible;
                    try (java.util.stream.Stream<ProcessHandle> descendants =
                                 root.toHandle().descendants()) {
                        visible = descendants.anyMatch(
                                candidate -> candidate.pid() == child
                                        && candidate.isAlive());
                    }
                    if (visible) return child;
                }
            } catch (Exception notReady) {
                last = notReady;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while waiting for child publication",
                        interrupted);
            }
        }
        throw new IllegalStateException(
                "child process was not published as a live descendant",
                last);
    }

    private static boolean awaitNotAlive(long processId, Duration timeout)
            throws InterruptedException {
        if (processId < 1L) return true;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ProcessHandle handle = ProcessHandle.of(processId).orElse(null);
            if (handle == null || !handle.isAlive()) return true;
            Thread.sleep(10L);
        }
        ProcessHandle handle = ProcessHandle.of(processId).orElse(null);
        return handle == null || !handle.isAlive();
    }

    private static void forceStop(long processId) {
        if (processId < 1L) return;
        ProcessHandle.of(processId).ifPresent(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
    }

    public static final class Sleeper {
        private Sleeper() {}

        public static void main(String[] args) throws Exception {
            Thread.sleep(60_000L);
        }
    }

    public static final class ExactLaunchReporter {
        private ExactLaunchReporter() {}

        public static void main(String[] args) {
            System.out.println(
                    Path.of("").toAbsolutePath().normalize()
                            + "|"
                            + System.getenv("TMP")
                            + "|"
                            + System.getenv().containsKey("PATH")
                            + "|"
                            + String.join(",", new TreeSet<>(System.getenv().keySet())));
        }
    }

    public static final class ChildSpawner {
        private ChildSpawner() {}

        public static void main(String[] args) throws Exception {
            if (args.length != 1) {
                throw new IllegalArgumentException(
                        "child PID publication path is required");
            }
            Process child = new ProcessBuilder(javaCommand(Sleeper.class))
                    .start();
            Files.writeString(
                    Path.of(args[0]),
                    Long.toString(child.pid()),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Thread.sleep(60_000L);
        }
    }

    public static final class HighVolumeReporter {
        private HighVolumeReporter() {}

        public static void main(String[] args) throws Exception {
            byte[] block = new byte[8192];
            java.util.Arrays.fill(block, (byte) 'x');
            for (int index = 0; index < 256; index++) {
                System.out.write(block);
            }
            System.out.write("DONE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.flush();
        }
    }

    public static final class ShortReporter {
        private ShortReporter() {}

        public static void main(String[] args) throws Exception {
            System.out.print("short\n");
            System.out.flush();
            Thread.sleep(250L);
        }
    }

    private static final class TrackingProcess extends Process {
        private final InputStream input;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private TrackingProcess(InputStream input) {
            this.input = input;
        }

        @Override public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }
        @Override public int waitFor() { alive.set(false); return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }
        @Override public int exitValue() {
            if (alive.get()) throw new IllegalThreadStateException();
            return 0;
        }
        @Override public void destroy() { alive.set(false); }
        @Override public Process destroyForcibly() {
            alive.set(false);
            return this;
        }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public long pid() { return 31_001L; }
    }

    private static final class DelegatingProcess extends Process {
        private final Process delegate;
        private final OutputStream output;
        private final InputStream input;
        private final InputStream error;

        private DelegatingProcess(
                Process delegate,
                OutputStream output,
                InputStream input,
                InputStream error) {
            this.delegate = delegate;
            this.output = output;
            this.input = input;
            this.error = error;
        }

        @Override public OutputStream getOutputStream() { return output; }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return error; }
        @Override public int waitFor() throws InterruptedException {
            return delegate.waitFor();
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.waitFor(timeout, unit);
        }
        @Override public int exitValue() { return delegate.exitValue(); }
        @Override public void destroy() { delegate.destroy(); }
        @Override public Process destroyForcibly() {
            delegate.destroyForcibly();
            return this;
        }
        @Override public boolean isAlive() { return delegate.isAlive(); }
        @Override public long pid() { return delegate.pid(); }
        @Override public ProcessHandle toHandle() { return delegate.toHandle(); }
    }

    private record ProductionCapture(
            Process raw,
            LlamaCppProcessTerminationOwner owner,
            LlamaCppProcessTerminationOwner.Lease lease,
            ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture capture,
            SignallingResourceSettlement settlement) {
        private void forceStop() throws InterruptedException {
            raw.destroyForcibly();
            raw.waitFor(5L, TimeUnit.SECONDS);
        }
    }

    private static final class SignallingResourceSettlement
            implements LlamaCppProcessTerminationOwner.ResourceSettlement {
        private final ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture
                delegate;
        private final CountDownLatch entered = new CountDownLatch(1);

        private SignallingResourceSettlement(
                ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture
                        delegate) {
            this.delegate = delegate;
        }

        @Override public boolean settle(Duration timeout) {
            entered.countDown();
            return delegate.settle(timeout);
        }

        @Override public boolean ownsProcessStdout() {
            return true;
        }
    }

    private static final class BlockingClose {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private void awaitRelease() throws IOException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("blocking close interrupted", interrupted);
            }
        }
    }

    private static final class BlockingCloseOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final BlockingClose blocker;

        private BlockingCloseOutputStream(
                OutputStream delegate,
                BlockingClose blocker) {
            this.delegate = delegate;
            this.blocker = blocker;
        }

        @Override public void write(int value) throws IOException {
            delegate.write(value);
        }
        @Override public void close() throws IOException {
            blocker.awaitRelease();
            delegate.close();
        }
    }

    private static final class BlockingCloseInputStream extends InputStream {
        private final InputStream delegate;
        private final BlockingClose blocker;

        private BlockingCloseInputStream(
                InputStream delegate,
                BlockingClose blocker) {
            this.delegate = delegate;
            this.blocker = blocker;
        }

        @Override public int read() throws IOException { return delegate.read(); }
        @Override public void close() throws IOException {
            blocker.awaitRelease();
            delegate.close();
        }
    }

    private static final class CaptureTarget
            implements LlamaCppProcessTerminationOwner.Target {
        private final Process process;
        private final LlamaCppProcessTerminationOwner.ResourceSettlement resources;
        private final LlamaCppProcessTerminationOwner.ProcessIdentity identity;

        private CaptureTarget(
                Process process,
                LlamaCppProcessTerminationOwner.ResourceSettlement resources) {
            this.process = process;
            this.resources = resources;
            this.identity = new LlamaCppProcessTerminationOwner.ProcessIdentity(
                    process.pid(),
                    0L,
                    LlamaCppProcessTerminationOwner.IdentityAuthority
                            .DIRECT_PROCESS_CAPABILITY);
        }

        @Override public LlamaCppProcessTerminationOwner.ProcessIdentity identity() {
            return identity;
        }
        @Override public boolean identityStillMatches() { return true; }
        @Override public boolean isAlive() { return process.isAlive(); }
        @Override public List<LlamaCppProcessTerminationOwner.Target> descendants() {
            return List.of();
        }
        @Override public void destroy() { process.destroy(); }
        @Override public void destroyForcibly() { process.destroyForcibly(); }
        @Override public boolean waitFor(Duration timeout)
                throws InterruptedException {
            return process.waitFor(
                    Math.max(1L, timeout.toMillis()),
                    TimeUnit.MILLISECONDS);
        }
        @Override public boolean owns(
                Process candidate,
                LlamaCppProcessTerminationOwner.ResourceSettlement
                        candidateResources) {
            return process == candidate && resources == candidateResources;
        }
        @Override public boolean closeResources(Duration timeout) {
            return resources.settle(timeout);
        }
    }

    /**
     * Deterministically models the WSL race: a zero-wait graceful signal starts
     * shutdown, then the root exits while the post-grace tree capture is in
     * progress. A genuinely forced-only policy never enters that path.
     */
    private static final class GracefulCaptureRaceTarget
            implements LlamaCppProcessTerminationOwner.Target {
        private final Process process;
        private final LlamaCppProcessTerminationOwner.ResourceSettlement resources;
        private final LlamaCppProcessTerminationOwner.ProcessIdentity identity;
        private final AtomicInteger gracefulSignals = new AtomicInteger();
        private final AtomicInteger forcedSignals = new AtomicInteger();

        private GracefulCaptureRaceTarget(
                Process process,
                LlamaCppProcessTerminationOwner.ResourceSettlement resources) {
            this.process = process;
            this.resources = resources;
            this.identity = new LlamaCppProcessTerminationOwner.ProcessIdentity(
                    process.pid(),
                    0L,
                    LlamaCppProcessTerminationOwner.IdentityAuthority
                            .DIRECT_PROCESS_CAPABILITY);
        }

        @Override
        public LlamaCppProcessTerminationOwner.ProcessIdentity identity() {
            return identity;
        }

        @Override public boolean identityStillMatches() { return true; }
        @Override public boolean isAlive() { return process.isAlive(); }

        @Override
        public List<LlamaCppProcessTerminationOwner.Target> descendants() {
            if (gracefulSignals.get() > 0 && process.isAlive()) {
                process.destroyForcibly();
                try {
                    if (!process.waitFor(5L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "race fixture child did not exit");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "race fixture cleanup interrupted", interrupted);
                }
            }
            return List.of();
        }

        @Override public void destroy() { gracefulSignals.incrementAndGet(); }

        @Override
        public void destroyForcibly() {
            forcedSignals.incrementAndGet();
            process.destroyForcibly();
        }

        @Override
        public boolean waitFor(Duration timeout) throws InterruptedException {
            long millis = timeout == null ? 0L : timeout.toMillis();
            return millis > 0L
                    ? process.waitFor(millis, TimeUnit.MILLISECONDS)
                    : !process.isAlive();
        }

        @Override
        public boolean owns(
                Process candidate,
                LlamaCppProcessTerminationOwner.ResourceSettlement
                        candidateResources) {
            return process == candidate && resources == candidateResources;
        }

        @Override
        public boolean closeResources(Duration timeout) {
            return resources.settle(timeout);
        }
    }

    private static final class RawProcessTarget
            implements LlamaCppProcessTerminationOwner.Target {
        private final Process process;
        private final LlamaCppProcessTerminationOwner.ResourceSettlement resources;
        private final boolean ignoreSignals;
        private final LlamaCppProcessTerminationOwner.ProcessIdentity identity;

        private RawProcessTarget(
                Process process,
                LlamaCppProcessTerminationOwner.ResourceSettlement resources,
                boolean ignoreSignals) {
            this.process = process;
            this.resources = resources;
            this.ignoreSignals = ignoreSignals;
            this.identity = new LlamaCppProcessTerminationOwner.ProcessIdentity(
                    process.pid(),
                    0L,
                    LlamaCppProcessTerminationOwner.IdentityAuthority
                            .DIRECT_PROCESS_CAPABILITY);
        }

        @Override public LlamaCppProcessTerminationOwner.ProcessIdentity identity() {
            return identity;
        }
        @Override public boolean identityStillMatches() { return true; }
        @Override public boolean isAlive() { return process.isAlive(); }
        @Override public List<LlamaCppProcessTerminationOwner.Target> descendants() {
            return List.of();
        }
        @Override public void destroy() {
            if (!ignoreSignals) process.destroy();
        }
        @Override public void destroyForcibly() {
            if (!ignoreSignals) process.destroyForcibly();
        }
        @Override public boolean waitFor(Duration timeout)
                throws InterruptedException {
            return process.waitFor(
                    Math.max(1L, timeout.toMillis()),
                    TimeUnit.MILLISECONDS);
        }
        @Override public boolean owns(
                Process candidate,
                LlamaCppProcessTerminationOwner.ResourceSettlement
                        candidateResources) {
            return process == candidate && resources == candidateResources;
        }
        @Override public boolean closeResources(Duration timeout) {
            try {
                process.getOutputStream().close();
                boolean interrupted = resources.settle(timeout);
                process.getErrorStream().close();
                return interrupted;
            } catch (IOException unavailable) {
                throw new IllegalStateException(
                        "raw process resource settlement failed", unavailable);
            }
        }
    }

    private static final class TrackingInputStream extends InputStream {
        private final InputStream delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TrackingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override public int read() throws IOException { return delegate.read(); }
        @Override public int read(byte[] bytes, int offset, int length)
                throws IOException {
            return delegate.read(bytes, offset, length);
        }
        @Override public void close() throws IOException {
            delegate.close();
            closed.set(true);
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override public int read() throws IOException {
            readEntered.countDown();
            try {
                release.await();
                return -1;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("blocking source interrupted", interrupted);
            }
        }

        @Override public int read(byte[] bytes, int offset, int length)
                throws IOException {
            return read();
        }

        @Override public void close() {
            closed.set(true);
        }
    }

    private static final class ThrowingInputStream extends InputStream {
        @Override public int read() throws IOException {
            throw new IOException("injected transfer failure");
        }
    }

    private static final class TrackingOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final AtomicInteger closeFailuresRemaining;
        private final AtomicInteger closeAttempts = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CountDownLatch firstCloseAttempt = new CountDownLatch(1);

        private TrackingOutputStream(int closeFailures) {
            this.closeFailuresRemaining = new AtomicInteger(closeFailures);
        }

        @Override public void write(int value) {
            delegate.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) {
            delegate.write(bytes, offset, length);
        }
        @Override public void close() throws IOException {
            closeAttempts.incrementAndGet();
            firstCloseAttempt.countDown();
            if (closeFailuresRemaining.getAndUpdate(value ->
                    Math.max(0, value - 1)) > 0) {
                throw new IOException("injected sink close failure");
            }
            closed.set(true);
            delegate.close();
        }
    }

    private static final class RetainedTarget
            implements LlamaCppProcessTerminationOwner.Target {
        private final LlamaCppProcessTerminationOwner.ProcessIdentity identity =
                new LlamaCppProcessTerminationOwner.ProcessIdentity(
                        10_001L,
                        1L,
                        LlamaCppProcessTerminationOwner.IdentityAuthority
                                .EXACT_PID_START);
        private boolean alive = true;

        @Override
        public LlamaCppProcessTerminationOwner.ProcessIdentity identity() {
            return identity;
        }

        @Override
        public boolean identityStillMatches() {
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public List<LlamaCppProcessTerminationOwner.Target> descendants() {
            return List.of();
        }

        @Override
        public void destroy() {}

        @Override
        public void destroyForcibly() {}

        @Override
        public boolean waitFor(Duration timeout) {
            return !alive;
        }
    }
}
