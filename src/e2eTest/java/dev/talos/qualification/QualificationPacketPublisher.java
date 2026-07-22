package dev.talos.qualification;

import dev.talos.core.io.AtomicFileWriter;
import dev.talos.core.security.NoFollowPathPolicy;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Qualification-only transaction owner for immutable protocol-v6 evidence sets.
 *
 * <p>The authoritative commit point is a strict commit record promoted only after every member
 * is durably staged, atomically promoted, and reverified. A physically present member without
 * that record is never treated as committed evidence. Protocol v1-v5 callers do not enter this
 * owner and therefore create no transaction metadata.
 *
 * <p>The process lock coordinates participating Talos qualification writers. It does not claim
 * exclusion against non-cooperating processes, hostile filesystem races, hard links, bind mounts,
 * network filesystems, or power-loss durability on platforms that cannot flush directories.
 */
final class QualificationPacketPublisher {
    static final String CONTROL_DIRECTORY = ".qualification-publication-v1";
    static final String COMMIT_SCHEMA = "talos.modelQualification.publicationCommit";
    static final String INTENT_SCHEMA = "talos.modelQualification.publicationIntent";
    static final int SCHEMA_VERSION = 1;
    private static final String COMMITS_DIRECTORY = "commits";
    private static final String INTENTS_DIRECTORY = "intents";
    private static final String STAGES_DIRECTORY = "stages";
    private static final String LOCK_FILE = "writer.lock";
    private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(30);
    private static final long LOCK_POLL_MILLIS = 25L;
    private static final int HASH_BYTES = 32;
    private static final int IO_CHUNK_BYTES = 16 * 1024;
    private static final Set<String> RESERVED_SEGMENTS = Set.of(".", "..");
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS =
            new ConcurrentHashMap<>();

    private QualificationPacketPublisher() {}

    static boolean printTypedFailure(PrintStream err, Throwable failure) {
        Objects.requireNonNull(err, "publication diagnostic stream");
        Throwable current = failure;
        while (current != null) {
            if (current instanceof PublicationException publication) {
                err.println("{\"schemaName\":\"talos.modelQualification.publicationFailure\""
                        + ",\"schemaVersion\":1"
                        + ",\"reason\":\"" + publication.reason().name() + "\""
                        + ",\"commitState\":\"" + publication.commitState().name() + "\"}");
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    enum PublicationKind {
        EXECUTION_IDENTITY,
        PTY_CAPTURE,
        PTY_FAILURE,
        APPROVAL_EVIDENCE,
        MECHANICAL_REQUIREMENTS,
        PROVIDER_EVIDENCE,
        RUN_FINALIZATION,
        CAMPAIGN_COMPLETION,
        SCORECARD
    }

    enum PublicationStatus {
        ABSENT,
        RECOVERABLE,
        COMMITTED
    }

    record PublicationTarget(PublicationKind kind, List<Path> members) {
        PublicationTarget {
            kind = Objects.requireNonNull(kind, "publication kind");
            members = List.copyOf(Objects.requireNonNull(members, "publication members"));
            if (members.isEmpty()) {
                throw new IllegalArgumentException("publication target has no members");
            }
        }
    }

    record PublicationSelection(PublicationKind kind, PublicationStatus status) {
        PublicationSelection {
            status = Objects.requireNonNull(status, "publication status");
            if ((status == PublicationStatus.ABSENT) != (kind == null)) {
                throw new IllegalArgumentException(
                        "absent publication selection must not declare a kind");
            }
        }
    }

    enum FailureReason {
        INVALID_REQUEST,
        LOCK_TIMEOUT,
        LOCK_INTERRUPTED,
        LOCK_UNAVAILABLE,
        CONFLICT,
        INCOMPLETE,
        INVALID_COMMIT,
        UNSAFE_RECOVERY,
        STAGE_IO,
        ATOMIC_MOVE_UNSUPPORTED,
        PROMOTION_FAILED,
        POST_COMMIT_VERIFICATION_FAILED,
        CLEANUP_FAILED
    }

    enum CommitState {
        NOT_COMMITTED,
        COMMITTED,
        COMMIT_STATE_UNKNOWN
    }

    static final class PublicationException extends IOException {
        private final FailureReason reason;
        private final CommitState commitState;

        PublicationException(FailureReason reason, CommitState commitState, String message) {
            super(message);
            this.reason = Objects.requireNonNull(reason, "failure reason");
            this.commitState = Objects.requireNonNull(commitState, "commit state");
        }

        PublicationException(
                FailureReason reason,
                CommitState commitState,
                String message,
                Throwable cause) {
            super(message, cause);
            this.reason = Objects.requireNonNull(reason, "failure reason");
            this.commitState = Objects.requireNonNull(commitState, "commit state");
        }

        FailureReason reason() {
            return reason;
        }

        CommitState commitState() {
            return commitState;
        }
    }

    enum FaultStage {
        AFTER_CONTROL_ROOT_CREATE,
        AFTER_COMMITS_DIRECTORY_CREATE,
        AFTER_INTENTS_DIRECTORY_CREATE,
        AFTER_STAGES_DIRECTORY_CREATE,
        AFTER_LOCK_FILE_FLUSH,
        AFTER_JVM_LOCK_ACQUIRED,
        AFTER_LEASE_ACQUIRED,
        AFTER_INTENT_FLUSH,
        AFTER_MEMBER_STAGE_CREATE,
        AFTER_MEMBER_PARTIAL_WRITE,
        AFTER_MEMBER_FINAL_WRITE,
        AFTER_METADATA_STAGE_CREATE,
        AFTER_METADATA_PARTIAL_WRITE,
        AFTER_METADATA_FINAL_WRITE,
        AFTER_MEMBER_FLUSH,
        AFTER_MEMBER_VALIDATION,
        BEFORE_MEMBER_PROMOTION,
        AFTER_MEMBER_PROMOTION,
        AFTER_COMMIT_STAGE_FLUSH,
        BEFORE_COMMIT_PROMOTION,
        AFTER_TARGET_CREATE,
        AFTER_COMMIT_PROMOTION,
        AFTER_COMMIT_VERIFICATION,
        BEFORE_CLEANUP,
        AFTER_MEMBER_STAGE_CLEANUP,
        AFTER_INTENT_STAGE_CLEANUP,
        AFTER_COMMIT_STAGE_CLEANUP,
        BEFORE_INTENT_CLEANUP,
        AFTER_INTENT_CLEANUP,
        AFTER_CLEANUP
    }

    @FunctionalInterface
    interface FaultInjector {
        FaultInjector NONE = (stage, memberOrdinal) -> {};

        void at(FaultStage stage, int memberOrdinal) throws IOException;
    }

    @FunctionalInterface
    interface LeaseAction<T> {
        T run(Lease lease) throws IOException;
    }

    record Member(Path path, byte[] bytes) {
        Member {
            path = Objects.requireNonNull(path, "member path").toAbsolutePath().normalize();
            bytes = Objects.requireNonNull(bytes, "member bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record MemberRecord(String relativePath, long byteLength, String sha256) {
        MemberRecord {
            relativePath = requireRelativePath(relativePath);
            if (byteLength < 0) throw invalidArgument("member byte length is negative");
            sha256 = requireHash(sha256, "member SHA-256");
        }
    }

    record CommitRecord(
            String schemaName,
            int schemaVersion,
            int protocolVersion,
            String transactionId,
            PublicationKind publicationKind,
            int runPhaseOrdinal,
            String predecessorTransactionId,
            String predecessorCommitSha256,
            String campaignId,
            String scenarioBankSha256,
            String setSha256,
            List<MemberRecord> members) {
        CommitRecord {
            if (!COMMIT_SCHEMA.equals(schemaName) || schemaVersion != SCHEMA_VERSION
                    || protocolVersion != QualificationProtocol.VERSION) {
                throw invalidArgument("publication commit schema is invalid");
            }
            transactionId = requireHash(transactionId, "transaction id");
            publicationKind = Objects.requireNonNull(publicationKind, "publication kind");
            if (runPhaseOrdinal < 0) {
                throw invalidArgument("publication run phase ordinal is negative");
            }
            predecessorTransactionId = requireOptionalHash(
                    predecessorTransactionId, "predecessor transaction id");
            predecessorCommitSha256 = requireOptionalHash(
                    predecessorCommitSha256, "predecessor commit SHA-256");
            campaignId = QualificationNamespace.requireSegment(campaignId, "campaign id");
            scenarioBankSha256 = requireHash(scenarioBankSha256, "scenario-bank SHA-256");
            setSha256 = requireHash(setSha256, "publication-set SHA-256");
            members = canonicalMemberRecords(members);
        }
    }

    record IntentRecord(
            String schemaName,
            int schemaVersion,
            int protocolVersion,
            String campaignPhase,
            CommitRecord desiredCommit,
            List<String> stagePaths) {
        IntentRecord {
            if (!INTENT_SCHEMA.equals(schemaName) || schemaVersion != SCHEMA_VERSION
                    || protocolVersion != QualificationProtocol.VERSION) {
                throw invalidArgument("publication intent schema is invalid");
            }
            campaignPhase = requireCampaignPhase(campaignPhase);
            desiredCommit = Objects.requireNonNull(desiredCommit, "desired commit");
            boolean scorecard = desiredCommit.publicationKind() == PublicationKind.SCORECARD;
            if (scorecard != "ENDED".equals(campaignPhase)) {
                throw invalidArgument("publication intent campaign phase is invalid");
            }
            stagePaths = canonicalStagePaths(stagePaths, desiredCommit.members().size());
        }
    }

    record CommittedSet(
            CommitRecord commit,
            Map<String, byte[]> memberBytes,
            Path commitPath,
            boolean created) {
        CommittedSet {
            commit = Objects.requireNonNull(commit, "commit");
            Map<String, byte[]> copy = new LinkedHashMap<>();
            Objects.requireNonNull(memberBytes, "member bytes").forEach(
                    (path, bytes) -> copy.put(path, bytes.clone()));
            memberBytes = Map.copyOf(copy);
            commitPath = Objects.requireNonNull(commitPath, "commit path");
        }

        byte[] bytes(Path packetRoot, Path member) {
            String relative = relativePath(packetRoot.toAbsolutePath().normalize(), member);
            byte[] bytes = memberBytes.get(relative);
            if (bytes == null) throw new IllegalArgumentException("member is not in committed set");
            return bytes.clone();
        }
    }

    static <T> T withLease(Path packetRoot, LeaseAction<T> action) throws IOException {
        return withLease(packetRoot, DEFAULT_LOCK_TIMEOUT, FaultInjector.NONE, action);
    }

    static <T> T withBootstrapLease(Path packetRoot, LeaseAction<T> action) throws IOException {
        return withLease(
                packetRoot, DEFAULT_LOCK_TIMEOUT, FaultInjector.NONE, true, action);
    }

    static <T> T withBootstrapLease(
            Path packetRoot,
            Duration timeout,
            FaultInjector faultInjector,
            LeaseAction<T> action) throws IOException {
        return withLease(packetRoot, timeout, faultInjector, true, action);
    }

    static <T> T withLease(
            Path requestedPacketRoot,
            Duration timeout,
            FaultInjector faultInjector,
            LeaseAction<T> action) throws IOException {
        return withLease(requestedPacketRoot, timeout, faultInjector, false, action);
    }

    private static <T> T withLease(
            Path requestedPacketRoot,
            Duration timeout,
            FaultInjector faultInjector,
            boolean bootstrap,
            LeaseAction<T> action) throws IOException {
        Objects.requireNonNull(requestedPacketRoot, "packet root");
        Objects.requireNonNull(timeout, "lock timeout");
        Objects.requireNonNull(faultInjector, "fault injector");
        Objects.requireNonNull(action, "lease action");
        if (timeout.isNegative() || timeout.isZero()) {
            throw failure(FailureReason.INVALID_REQUEST, "lock timeout must be positive");
        }
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            timeoutNanos = Long.MAX_VALUE;
        }
        long lockStarted = System.nanoTime();

        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(requestedPacketRoot);
        Path packetRoot = packetFiles.path();
        QualificationRecords.CampaignManifest initialCampaign =
                requireProtocolSixCampaign(packetFiles, packetRoot);
        QualificationArtifactPublication.requireWritableCampaign(
                packetRoot, QualificationScenarioBank.loadDefault());
        Path requestedControlRoot = packetRoot.resolve(CONTROL_DIRECTORY);
        boolean prepareControl = !hasCompleteControlState(packetFiles, requestedControlRoot);
        if (prepareControl) {
            if (!bootstrap) {
                throw failure(
                        FailureReason.INVALID_REQUEST,
                        "qualification publication control state is not initialized");
            }
            requireBootstrapEligible(packetFiles, packetRoot, initialCampaign);
            requireRecoverableBootstrapControl(packetFiles, requestedControlRoot);
        } else {
            requireExistingControlState(packetFiles, requestedControlRoot);
        }
        Path lockKey = packetRoot.toRealPath();
        ReentrantLock local = JVM_LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        boolean localAcquired;
        try {
            long remaining = remainingNanos(lockStarted, timeoutNanos);
            localAcquired = remaining > 0
                    && local.tryLock(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(
                    FailureReason.LOCK_INTERRUPTED,
                    "qualification publication lease was interrupted",
                    interrupted);
        }
        if (!localAcquired) {
            throw failure(FailureReason.LOCK_TIMEOUT, "qualification publication lease timed out");
        }

        try {
            faultInjector.at(FaultStage.AFTER_JVM_LOCK_ACQUIRED, -1);
            Path controlRoot;
            if (prepareControl) {
                controlRoot = prepareBootstrapDirectory(
                        packetFiles,
                        requestedControlRoot,
                        packetRoot,
                        faultInjector,
                        FaultStage.AFTER_CONTROL_ROOT_CREATE);
                prepareBootstrapDirectory(
                        packetFiles,
                        controlRoot.resolve(COMMITS_DIRECTORY),
                        controlRoot,
                        faultInjector,
                        FaultStage.AFTER_COMMITS_DIRECTORY_CREATE);
                prepareBootstrapDirectory(
                        packetFiles,
                        controlRoot.resolve(INTENTS_DIRECTORY),
                        controlRoot,
                        faultInjector,
                        FaultStage.AFTER_INTENTS_DIRECTORY_CREATE);
                prepareBootstrapDirectory(
                        packetFiles,
                        controlRoot.resolve(STAGES_DIRECTORY),
                        controlRoot,
                        faultInjector,
                        FaultStage.AFTER_STAGES_DIRECTORY_CREATE);
            } else {
                controlRoot = packetFiles.requireDirectory(requestedControlRoot);
            }
            Path lockPath = controlRoot.resolve(LOCK_FILE);
            if (prepareControl) {
                packetFiles.requireRegularFileTarget(lockPath);
            } else {
                packetFiles.requireRegularFile(lockPath);
            }
            boolean processLeaseAcquired = false;
            Set<java.nio.file.OpenOption> lockOptions = prepareControl
                    ? Set.of(
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)
                    : Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try (FileChannel channel = FileChannel.open(
                    lockPath, lockOptions)) {
                packetFiles.requireRegularFile(lockPath);
                if (prepareControl) {
                    channel.force(true);
                    AtomicFileWriter.synchronizeDirectory(controlRoot);
                    faultInjector.at(FaultStage.AFTER_LOCK_FILE_FLUSH, -1);
                    requireExistingControlState(packetFiles, controlRoot);
                }
                FileLock processLock = acquireProcessLock(
                        channel, lockStarted, timeoutNanos);
                try (processLock) {
                    processLeaseAcquired = true;
                    packetFiles.requireRegularFile(lockPath);
                    QualificationRecords.CampaignManifest campaign =
                            requireProtocolSixCampaign(packetFiles, packetRoot);
                    if (!campaign.equals(initialCampaign)) {
                        throw failure(
                                FailureReason.INVALID_REQUEST,
                                "qualification campaign changed while acquiring the publication lease");
                    }
                    if (!bootstrap) {
                        requireCommittedExecutionIdentity(
                                packetFiles, packetRoot, campaign, controlRoot);
                    }
                    Lease lease = new Lease(
                            packetFiles,
                            packetRoot,
                            controlRoot,
                            campaign,
                            faultInjector,
                            bootstrap);
                    faultInjector.at(FaultStage.AFTER_LEASE_ACQUIRED, -1);
                    try {
                        return action.run(lease);
                    } finally {
                        lease.invalidate();
                    }
                }
            } catch (PublicationException e) {
                throw e;
            } catch (OverlappingFileLockException e) {
                throw failure(
                        FailureReason.LOCK_UNAVAILABLE,
                        "qualification publication lease overlaps in this process",
                        e);
            } catch (IOException e) {
                if (processLeaseAcquired) throw e;
                throw failure(
                        FailureReason.LOCK_UNAVAILABLE,
                        "qualification publication lease is unavailable",
                        e);
            }
        } finally {
            local.unlock();
        }
    }

    static CommittedSet requireCommitted(
            Path requestedPacketRoot,
            PublicationKind kind,
            List<Path> expectedMembers) throws IOException {
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(requestedPacketRoot);
        Path packetRoot = packetFiles.path();
        QualificationRecords.CampaignManifest campaign =
                requireProtocolSixCampaign(packetFiles, packetRoot);
        PreparedSet expected = prepare(
                packetFiles, packetRoot, campaign, kind,
                expectedMembers.stream().map(path -> new Member(path, new byte[0])).toList(),
                false);
        Path controlRoot = packetRoot.resolve(CONTROL_DIRECTORY);
        requireExistingControlState(packetFiles, controlRoot);
        validateCommitInventory(packetFiles, packetRoot, campaign, controlRoot);
        requireTransactionSettled(
                packetFiles, packetRoot, expected.transactionId());
        Path commitPath = commitPath(packetRoot, expected.transactionId());
        if (packetFiles.inspect(commitPath) == NoFollowPathPolicy.EntryType.MISSING) {
            throw failure(FailureReason.INCOMPLETE, "qualification publication is incomplete");
        }
        CommitRecord commit = readCanonicalCommit(packetFiles, commitPath);
        validateCommitIdentity(commit, expected, false);
        Map<String, byte[]> bytes = readAndVerifyMembers(
                packetFiles, packetRoot, campaign, commit);
        return new CommittedSet(commit, bytes, commitPath, false);
    }

    static void requireSettledControlState(Path requestedPacketRoot) throws IOException {
        requireControlState(requestedPacketRoot, null);
    }

    static void requireControlStateForRecovery(
            Path requestedPacketRoot,
            String transactionId) throws IOException {
        requireHash(transactionId, "recovery transaction id");
        requireControlState(requestedPacketRoot, transactionId);
    }

    private static void requireControlState(
            Path requestedPacketRoot,
            String allowedTransactionId) throws IOException {
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(requestedPacketRoot);
        Path packetRoot = packetFiles.path();
        QualificationRecords.CampaignManifest campaign =
                requireProtocolSixCampaign(packetFiles, packetRoot);
        validateControlState(
                packetFiles, packetRoot, campaign, allowedTransactionId);
    }

    static String transactionIdFor(
            Path requestedPacketRoot,
            PublicationKind kind,
            List<Path> expectedMembers) throws IOException {
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(requestedPacketRoot);
        Path packetRoot = packetFiles.path();
        QualificationRecords.CampaignManifest campaign =
                requireProtocolSixCampaign(packetFiles, packetRoot);
        return transactionIdentity(packetRoot, campaign, kind, expectedMembers);
    }

    static Path commitPathForTest(
            Path requestedPacketRoot,
            PublicationKind kind,
            List<Path> expectedMembers) throws IOException {
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(requestedPacketRoot);
        Path packetRoot = packetFiles.path();
        QualificationRecords.CampaignManifest campaign =
                requireProtocolSixCampaign(packetFiles, packetRoot);
        return commitPath(
                packetRoot,
                transactionIdentity(packetRoot, campaign, kind, expectedMembers));
    }

    static final class Lease {
        private final NoFollowPathPolicy.GuardedRoot packetFiles;
        private final Path packetRoot;
        private final Path controlRoot;
        private final QualificationRecords.CampaignManifest campaign;
        private final FaultInjector faultInjector;
        private final boolean bootstrap;
        private final Thread ownerThread;
        private volatile boolean active = true;

        private Lease(
                NoFollowPathPolicy.GuardedRoot packetFiles,
                Path packetRoot,
                Path controlRoot,
                QualificationRecords.CampaignManifest campaign,
                FaultInjector faultInjector,
                boolean bootstrap) {
            this.packetFiles = packetFiles;
            this.packetRoot = packetRoot;
            this.controlRoot = controlRoot;
            this.campaign = campaign;
            this.faultInjector = faultInjector;
            this.bootstrap = bootstrap;
            this.ownerThread = Thread.currentThread();
        }

        Path packetRoot() {
            return packetRoot;
        }

        QualificationRecords.CampaignManifest campaign() {
            return campaign;
        }

        void requireTransactionProvenance(
                PublicationKind kind,
                List<Path> expectedMembers) throws IOException {
            requireActive();
            PreparedSet expected = prepare(
                    packetFiles,
                    packetRoot,
                    campaign,
                    kind,
                    expectedMembers.stream()
                            .map(path -> new Member(path, new byte[0]))
                            .toList(),
                    false);
            boolean anyMember = false;
            for (PreparedMember member : expected.members()) {
                if (packetFiles.inspect(member.path())
                        != NoFollowPathPolicy.EntryType.MISSING) {
                    anyMember = true;
                    break;
                }
            }
            if (!anyMember) return;
            Path commit = commitPath(packetRoot, expected.transactionId());
            Path intent = intentPath(packetRoot, expected.transactionId());
            boolean committed = packetFiles.inspect(commit)
                    == NoFollowPathPolicy.EntryType.REGULAR_FILE;
            boolean inProgress = packetFiles.inspect(intent)
                    == NoFollowPathPolicy.EntryType.REGULAR_FILE;
            if (!committed && !inProgress) {
                throw failure(
                        FailureReason.INCOMPLETE,
                        "uncommitted qualification members have no transaction provenance");
            }
            if (committed) {
                CommitRecord record = readCanonicalCommit(packetFiles, commit);
                validateCommitIdentity(record, expected, false);
                readAndVerifyMembers(packetFiles, packetRoot, campaign, record);
            }
            if (inProgress) {
                IntentRecord record = validateIntentEntry(
                        packetFiles, packetRoot, campaign, intent);
                if (!record.desiredCommit().transactionId().equals(expected.transactionId())) {
                    throw failure(
                            FailureReason.UNSAFE_RECOVERY,
                            "qualification member provenance is ambiguous");
                }
            }
        }

        PublicationStatus publicationStatus(
                PublicationKind kind,
                List<Path> expectedMembers) throws IOException {
            requireActive();
            PreparedSet expected = prepare(
                    packetFiles,
                    packetRoot,
                    campaign,
                    kind,
                    expectedMembers.stream()
                            .map(path -> new Member(path, new byte[0]))
                            .toList(),
                    false);
            Path commitPath = commitPath(packetRoot, expected.transactionId());
            Path intentPath = intentPath(packetRoot, expected.transactionId());
            validateControlState(
                    packetFiles, packetRoot, campaign, expected.transactionId());

            boolean anyMember = false;
            for (PreparedMember member : expected.members()) {
                if (packetFiles.inspect(member.path())
                        != NoFollowPathPolicy.EntryType.MISSING) {
                    anyMember = true;
                    break;
                }
            }
            boolean intentPresent = packetFiles.inspect(intentPath)
                    == NoFollowPathPolicy.EntryType.REGULAR_FILE;
            boolean stagesPresent = hasTransactionStage(expected.transactionId());
            if (packetFiles.inspect(commitPath)
                    == NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                CommitRecord committed = readCanonicalCommit(packetFiles, commitPath);
                validateCommitIdentity(committed, expected, false);
                Map<String, byte[]> bytes = readAndVerifyMembers(
                        packetFiles, packetRoot, campaign, committed);
                PreparedSet committedSet = prepare(
                        packetFiles,
                        packetRoot,
                        campaign,
                        committed.publicationKind(),
                        committed.members().stream()
                                .map(member -> new Member(
                                        packetRoot.resolve(member.relativePath()),
                                        bytes.get(member.relativePath())))
                                .toList(),
                        true);
                validateCommitIdentity(committed, committedSet, true);
                if (intentPresent) {
                    requireIntentMatches(
                            validateIntentEntry(
                                    packetFiles, packetRoot, campaign, intentPath),
                            committedSet);
                }
                return intentPresent || stagesPresent
                        ? PublicationStatus.RECOVERABLE
                        : PublicationStatus.COMMITTED;
            }
            if (anyMember && !intentPresent) {
                throw failure(
                        FailureReason.INCOMPLETE,
                        "uncommitted qualification members have no transaction provenance");
            }
            if (intentPresent) {
                IntentRecord intent = validateIntentEntry(
                        packetFiles, packetRoot, campaign, intentPath);
                validateCommitIdentity(intent.desiredCommit(), expected, false);
                return PublicationStatus.RECOVERABLE;
            }
            return stagesPresent ? PublicationStatus.RECOVERABLE : PublicationStatus.ABSENT;
        }

        PublicationSelection selectPublication(
                List<PublicationTarget> orderedTargets) throws IOException {
            requireActive();
            if (orderedTargets == null || orderedTargets.isEmpty()) {
                throw failure(
                        FailureReason.INVALID_REQUEST,
                        "qualification recovery has no supported publication targets");
            }
            List<PreparedSet> expected = new ArrayList<>();
            Set<PublicationKind> kinds = new java.util.HashSet<>();
            Set<String> transactionIds = new java.util.HashSet<>();
            for (PublicationTarget target : orderedTargets) {
                if (!kinds.add(target.kind())) {
                    throw failure(
                            FailureReason.INVALID_REQUEST,
                            "qualification recovery publication kinds are not unique");
                }
                PreparedSet prepared = prepare(
                        packetFiles,
                        packetRoot,
                        campaign,
                        target.kind(),
                        target.members().stream()
                                .map(path -> new Member(path, new byte[0]))
                                .toList(),
                        false);
                if (!transactionIds.add(prepared.transactionId())) {
                    throw failure(
                            FailureReason.INVALID_REQUEST,
                            "qualification recovery transaction identities collide");
                }
                expected.add(prepared);
            }

            IntentRecord activeIntent = null;
            Path intents = controlRoot.resolve(INTENTS_DIRECTORY);
            try (var entries = Files.list(intents)) {
                for (Path entry : entries.toList()) {
                    if (activeIntent != null) {
                        throw failure(
                                FailureReason.INCOMPLETE,
                                "qualification publication has multiple incomplete transactions");
                    }
                    activeIntent = validateIntentEntry(
                            packetFiles, packetRoot, campaign, entry);
                }
            }

            PreparedSet active = null;
            if (activeIntent != null) {
                String transactionId = activeIntent.desiredCommit().transactionId();
                active = expected.stream()
                        .filter(candidate -> candidate.transactionId().equals(transactionId))
                        .findFirst()
                        .orElseThrow(() -> failure(
                                FailureReason.INCOMPLETE,
                                "qualification recovery found an unsupported incomplete transaction"));
                validateCommitIdentity(activeIntent.desiredCommit(), active, false);
            } else {
                Path stages = controlRoot.resolve(STAGES_DIRECTORY);
                try (var entries = Files.list(stages)) {
                    for (Path entry : entries.toList()) {
                        PreparedSet owner = null;
                        for (PreparedSet candidate : expected) {
                            if (stageBelongsToAllowedTransaction(
                                    packetRoot,
                                    entry,
                                    candidate.transactionId(),
                                    null)) {
                                if (owner != null) {
                                    throw failure(
                                            FailureReason.UNSAFE_RECOVERY,
                                            "qualification recovery stage ownership is ambiguous");
                                }
                                owner = candidate;
                            }
                        }
                        if (owner == null || active != null && active != owner) {
                            throw failure(
                                    FailureReason.INCOMPLETE,
                                    "qualification recovery found unsupported staged bytes");
                        }
                        active = owner;
                    }
                }
            }

            validateControlState(
                    packetFiles,
                    packetRoot,
                    campaign,
                    active == null ? null : active.transactionId());

            int lastCommitted = -1;
            for (int index = 0; index < expected.size(); index++) {
                PreparedSet candidate = expected.get(index);
                Path commitPath = commitPath(packetRoot, candidate.transactionId());
                boolean committed = packetFiles.inspect(commitPath)
                        == NoFollowPathPolicy.EntryType.REGULAR_FILE;
                if (committed) {
                    CommitRecord record = readCanonicalCommit(packetFiles, commitPath);
                    validateCommitIdentity(record, candidate, false);
                    readAndVerifyMembers(packetFiles, packetRoot, campaign, record);
                    if (index != lastCommitted + 1) {
                        throw failure(
                                FailureReason.UNSAFE_RECOVERY,
                                "qualification recovery publication sequence has a gap");
                    }
                    lastCommitted = index;
                } else if (index <= lastCommitted) {
                    throw failure(
                            FailureReason.UNSAFE_RECOVERY,
                            "qualification recovery publication sequence is invalid");
                }
            }
            if (active != null) {
                int activeIndex = expected.indexOf(active);
                if (activeIndex < lastCommitted || activeIndex > lastCommitted + 1) {
                    throw failure(
                            FailureReason.UNSAFE_RECOVERY,
                            "qualification recovery sequence conflicts with committed publications");
                }
                return new PublicationSelection(
                        active.kind(), PublicationStatus.RECOVERABLE);
            }
            if (lastCommitted >= 0) {
                return new PublicationSelection(
                        expected.get(lastCommitted).kind(), PublicationStatus.COMMITTED);
            }
            return new PublicationSelection(null, PublicationStatus.ABSENT);
        }

        CommittedSet requireCommittedSet(
                PublicationKind kind,
                List<Path> expectedMembers) throws IOException {
            requireActive();
            PreparedSet expected = prepare(
                    packetFiles,
                    packetRoot,
                    campaign,
                    kind,
                    expectedMembers.stream()
                            .map(path -> new Member(path, new byte[0]))
                            .toList(),
                    false);
            requireTransactionSettled(
                    packetFiles, packetRoot, expected.transactionId());
            Path committedPath = commitPath(packetRoot, expected.transactionId());
            if (packetFiles.inspect(committedPath)
                    != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                throw failure(
                        FailureReason.INCOMPLETE,
                        "qualification publication is incomplete");
            }
            CommitRecord committed = readCanonicalCommit(packetFiles, committedPath);
            validateCommitIdentity(committed, expected, false);
            Map<String, byte[]> bytes = readAndVerifyMembers(
                    packetFiles, packetRoot, campaign, committed);
            return new CommittedSet(committed, bytes, committedPath, false);
        }

        CommittedSet publish(PublicationKind kind, List<Member> requestedMembers)
                throws IOException {
            requireActive();
            if (bootstrap && kind != PublicationKind.EXECUTION_IDENTITY) {
                throw failure(
                        FailureReason.INVALID_REQUEST,
                        "qualification bootstrap leases may publish only execution identity");
            }
            if (!bootstrap && kind == PublicationKind.EXECUTION_IDENTITY) {
                throw failure(
                        FailureReason.INVALID_REQUEST,
                        "execution identity requires the qualification bootstrap lease");
            }
            if (kind == PublicationKind.CAMPAIGN_COMPLETION
                    && requestedMembers != null
                    && requestedMembers.size() == 1) {
                QualificationCampaignCompletion.validateDesiredUnderLease(
                        this,
                        requestedMembers.getFirst().bytes(),
                        QualificationScenarioBank.loadDefault());
            }
            PreparedSet desired = prepare(
                    packetFiles, packetRoot, campaign, kind, requestedMembers, true);
            Path commitPath = commitPath(packetRoot, desired.transactionId());
            Path intentPath = intentPath(packetRoot, desired.transactionId());
            requireLifecyclePermission(desired, commitPath, intentPath);
            rejectForeignRecoveryState(desired, commitPath, intentPath);
            prepareTargets(desired);

            NoFollowPathPolicy.EntryType commitType = packetFiles.inspect(commitPath);
            if (commitType != NoFollowPathPolicy.EntryType.MISSING) {
                CommitRecord committed = readCanonicalCommit(packetFiles, commitPath);
                validateCommitIdentity(committed, desired, false);
                Map<String, byte[]> bytes = readAndVerifyMembers(
                        packetFiles, packetRoot, campaign, committed);
                PreparedSet committedSet = prepare(
                        packetFiles,
                        packetRoot,
                        campaign,
                        committed.publicationKind(),
                        committed.members().stream()
                                .map(member -> new Member(
                                        packetRoot.resolve(member.relativePath()),
                                        bytes.get(member.relativePath())))
                                .toList(),
                        true);
                validateCommitIdentity(committed, committedSet, true);
                if (packetFiles.inspect(intentPath) != NoFollowPathPolicy.EntryType.MISSING) {
                    IntentRecord recoveryIntent;
                    try {
                        recoveryIntent = readCanonicalIntent(packetFiles, intentPath);
                        requireIntentMatches(recoveryIntent, committedSet);
                    } catch (IOException | RuntimeException conflict) {
                        throw new PublicationException(
                                FailureReason.CLEANUP_FAILED,
                                CommitState.COMMITTED,
                                "committed qualification publication has conflicting recovery state",
                                conflict);
                    }
                    cleanupCommitted(intentPath, committedSet, recoveryIntent);
                } else {
                    cleanupCommittedOrphanIntentStage(committedSet);
                }
                validateCommitIdentity(committed, desired, true);
                return new CommittedSet(committed, bytes, commitPath, false);
            }

            boolean existingMember = preflightTargets(desired);
            IntentRecord intent = desired.intent();
            byte[] intentBytes = QualificationJson.bytes(intent);
            NoFollowPathPolicy.EntryType intentType = packetFiles.inspect(intentPath);
            if (intentType == NoFollowPathPolicy.EntryType.MISSING) {
                if (existingMember) {
                    throw failure(
                            FailureReason.INCOMPLETE,
                            "uncommitted qualification members have no transaction provenance");
                }
                writeAtomicCreate(
                        intentPath,
                        intentBytes,
                        desired.transactionId(),
                        desired.setSha256(),
                        "intent",
                        -2,
                        FaultStage.AFTER_INTENT_FLUSH,
                        null);
            } else {
                IntentRecord existing = readCanonicalIntent(packetFiles, intentPath);
                requireIntentMatches(existing, desired);
                requirePromotionLink(
                        metadataStagePath(
                                packetRoot,
                                "intent",
                                desired.transactionId(),
                                desired.setSha256()),
                        intentPath,
                        QualificationJson.bytes(existing),
                        "qualification publication intent");
                intent = existing;
            }

            requireExistingTargetsOwned(desired);

            for (int index = 0; index < desired.members().size(); index++) {
                PreparedMember member = desired.members().get(index);
                try {
                    if (packetFiles.inspect(member.path())
                            == NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                        requirePromotionLink(
                                member.stagePath(),
                                member.path(),
                                member.bytes(),
                                "qualification publication member");
                        forceExact(member.path(), member.bytes(), FailureReason.CONFLICT);
                        AtomicFileWriter.synchronizeDirectory(member.path().getParent());
                        continue;
                    }
                    Path stage = member.stagePath();
                    prepareMemberStage(stage, member, index);
                    faultInjector.at(FaultStage.BEFORE_MEMBER_PROMOTION, index);
                    promoteCreateOnly(stage, member.path(), member.bytes(), index);
                    faultInjector.at(FaultStage.AFTER_MEMBER_PROMOTION, index);
                    requireExact(member.path(), member.bytes(), FailureReason.PROMOTION_FAILED);
                } catch (PublicationException e) {
                    throw e;
                } catch (IOException e) {
                    throw failure(
                            FailureReason.PROMOTION_FAILED,
                            "qualification publication member promotion failed",
                            e);
                }
            }

            for (PreparedMember member : desired.members()) {
                forceExact(member.path(), member.bytes(), FailureReason.CONFLICT);
                AtomicFileWriter.synchronizeDirectory(member.path().getParent());
            }
            byte[] commitBytes = QualificationJson.bytes(desired.commit());
            CommitRecord committed;
            Map<String, byte[]> bytes;
            try {
                writeAtomicCreate(
                        commitPath,
                        commitBytes,
                        desired.transactionId(),
                        desired.setSha256(),
                        "commit",
                        -1,
                        FaultStage.AFTER_COMMIT_STAGE_FLUSH,
                        FaultStage.BEFORE_COMMIT_PROMOTION);
                faultInjector.at(FaultStage.AFTER_COMMIT_PROMOTION, -1);
                committed = readCanonicalCommit(packetFiles, commitPath);
                validateCommitIdentity(committed, desired, true);
                bytes = readAndVerifyMembers(packetFiles, packetRoot, campaign, committed);
            } catch (IOException | RuntimeException e) {
                CommitState observed = e instanceof PublicationException publication
                                && publication.commitState() != CommitState.NOT_COMMITTED
                        ? publication.commitState()
                        : observeCommitState(commitPath, desired);
                throw new PublicationException(
                        FailureReason.POST_COMMIT_VERIFICATION_FAILED,
                        observed,
                        "qualification publication commit could not be verified",
                        e);
            }
            try {
                faultInjector.at(FaultStage.AFTER_COMMIT_VERIFICATION, -1);
                faultInjector.at(FaultStage.BEFORE_CLEANUP, -1);
                cleanupCommitted(intentPath, desired, intent);
                faultInjector.at(FaultStage.AFTER_CLEANUP, -1);
            } catch (PublicationException e) {
                if (e.commitState() == CommitState.COMMITTED) throw e;
                throw committedFinalizationFailure(e);
            } catch (IOException | RuntimeException e) {
                throw committedFinalizationFailure(e);
            }
            return new CommittedSet(committed, bytes, commitPath, true);
        }

        private void invalidate() {
            active = false;
        }

        private void requireActive() throws PublicationException {
            if (!active || Thread.currentThread() != ownerThread) {
                throw failure(
                        FailureReason.INVALID_REQUEST,
                        "qualification publication lease is not active on its owner thread");
            }
        }

        private boolean hasTransactionStage(String transactionId) throws IOException {
            String token = "-" + transactionId + "-";
            try (var entries = Files.list(controlRoot.resolve(STAGES_DIRECTORY))) {
                return entries.anyMatch(path ->
                        path.getFileName().toString().contains(token));
            }
        }

        private boolean campaignCompleted() throws IOException {
            Path completion = packetRoot.resolve(QualificationCampaignCompletion.OUTPUT_FILE);
            PreparedSet expected = prepare(
                    packetFiles,
                    packetRoot,
                    campaign,
                    PublicationKind.CAMPAIGN_COMPLETION,
                    List.of(new Member(completion, new byte[0])),
                    false);
            Path completionCommit = commitPath(packetRoot, expected.transactionId());
            NoFollowPathPolicy.EntryType type = packetFiles.inspect(completionCommit);
            if (type == NoFollowPathPolicy.EntryType.MISSING) return false;
            if (type != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                throw failure(
                        FailureReason.INVALID_COMMIT,
                        "qualification campaign completion commit is invalid");
            }
            CommitRecord committed = readCanonicalCommit(packetFiles, completionCommit);
            validateCommitIdentity(committed, expected, false);
            readAndVerifyMembers(packetFiles, packetRoot, campaign, committed);
            QualificationRecords.CampaignCompletion declared =
                    QualificationCampaignCompletion.readCommitted(
                            packetRoot, QualificationScenarioBank.loadDefault());
            QualificationCampaignCompletion.requireCommitted(
                    this,
                    declared.bank(),
                    QualificationScenarioBank.loadDefault());
            return true;
        }

        private void requireLifecyclePermission(
                PreparedSet desired,
                Path commitPath,
                Path intentPath) throws IOException {
            boolean committed = packetFiles.inspect(commitPath)
                    == NoFollowPathPolicy.EntryType.REGULAR_FILE;
            if (committed) return;
            if (desired.commit().runPhaseOrdinal() > 1
                    && desired.commit().predecessorCommitSha256().isBlank()) {
                throw failure(
                        FailureReason.INVALID_REQUEST,
                        "qualification run phase requires its committed predecessor");
            }
            boolean ended = campaignCompleted();
            NoFollowPathPolicy.EntryType intentType = packetFiles.inspect(intentPath);
            if (intentType == NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                IntentRecord intent = readCanonicalIntent(packetFiles, intentPath);
                requireIntentMatches(intent, desired);
                if (desired.kind() == PublicationKind.SCORECARD) {
                    if (!ended) {
                        throw failure(
                                FailureReason.INVALID_REQUEST,
                                "qualification score publication requires an ended campaign");
                    }
                } else if (ended && !"ACTIVE".equals(intent.campaignPhase())) {
                    throw failure(
                            FailureReason.INVALID_REQUEST,
                            "ended qualification campaign lacks an active recovery intent");
                }
                return;
            }
            if (intentType != NoFollowPathPolicy.EntryType.MISSING) {
                throw failure(
                        FailureReason.UNSAFE_RECOVERY,
                        "qualification publication intent path is invalid");
            }
            if (desired.kind() == PublicationKind.SCORECARD) {
                if (!ended) {
                    throw failure(
                            FailureReason.INVALID_REQUEST,
                            "qualification score publication requires an ended campaign");
                }
            } else if (ended) {
                throw failure(
                        FailureReason.INVALID_REQUEST,
                        "ended qualification campaign rejects new evidence publication");
            }
        }

        private void requireIntentMatches(IntentRecord intent, PreparedSet desired)
                throws PublicationException {
            if (!intent.desiredCommit().equals(desired.commit())
                    || !intent.stagePaths().equals(desired.intent().stagePaths())) {
                throw failure(
                        FailureReason.UNSAFE_RECOVERY,
                        "qualification publication recovery state is ambiguous");
            }
        }

        private CommitState observeCommitState(Path commitPath, PreparedSet desired) {
            try {
                NoFollowPathPolicy.EntryType type = packetFiles.inspect(commitPath);
                if (type == NoFollowPathPolicy.EntryType.MISSING) {
                    return CommitState.NOT_COMMITTED;
                }
                if (type != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                    return CommitState.COMMIT_STATE_UNKNOWN;
                }
                CommitRecord observed = readCanonicalCommit(packetFiles, commitPath);
                validateCommitIdentity(observed, desired, true);
                readAndVerifyMembers(packetFiles, packetRoot, campaign, observed);
                return CommitState.COMMITTED;
            } catch (IOException | RuntimeException ignored) {
                return CommitState.COMMIT_STATE_UNKNOWN;
            }
        }

        private PublicationException committedFinalizationFailure(Throwable cause) {
            return new PublicationException(
                    FailureReason.CLEANUP_FAILED,
                    CommitState.COMMITTED,
                    "qualification publication committed but finalization failed",
                    cause);
        }

        private boolean preflightTargets(PreparedSet desired) throws IOException {
            boolean existing = false;
            for (PreparedMember member : desired.members()) {
                NoFollowPathPolicy.EntryType type = packetFiles.inspect(member.path());
                if (type == NoFollowPathPolicy.EntryType.MISSING) {
                    packetFiles.requireRegularFileTarget(member.path());
                    continue;
                }
                if (type != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                    throw failure(FailureReason.CONFLICT, "qualification publication target conflicts");
                }
                existing = true;
                requireExact(member.path(), member.bytes(), FailureReason.CONFLICT);
            }
            return existing;
        }

        private void requireExistingTargetsOwned(PreparedSet desired) throws IOException {
            for (PreparedMember member : desired.members()) {
                if (packetFiles.inspect(member.path())
                        == NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                    requirePromotionLink(
                            member.stagePath(),
                            member.path(),
                            member.bytes(),
                            "qualification publication member");
                }
            }
        }

        private void prepareTargets(PreparedSet desired) throws IOException {
            for (PreparedMember member : desired.members()) {
                Path parent = member.path().getParent();
                if (desired.kind() == PublicationKind.SCORECARD
                        && parent.equals(packetRoot.resolve("scorecards"))) {
                    if (packetFiles.inspect(parent) == NoFollowPathPolicy.EntryType.MISSING) {
                        packetFiles.createDirectories(parent);
                        AtomicFileWriter.synchronizeDirectory(packetRoot);
                    } else {
                        packetFiles.requireDirectory(parent);
                    }
                } else {
                    packetFiles.requireDirectory(parent);
                }
                packetFiles.requireRegularFileTarget(member.path());
            }
        }

        private void prepareMemberStage(Path stage, PreparedMember member, int ordinal)
                throws IOException {
            NoFollowPathPolicy.EntryType type = packetFiles.inspect(stage);
            if (type != NoFollowPathPolicy.EntryType.MISSING) {
                if (type != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                    throw failure(
                            FailureReason.UNSAFE_RECOVERY,
                            "qualification publication stage is not an ordinary file");
                }
                byte[] existing = packetFiles.readAllBytes(stage);
                if (Arrays.equals(existing, member.bytes())) {
                    forceExact(stage, member.bytes(), FailureReason.STAGE_IO);
                    return;
                }
                deleteOwnedStage(stage);
            }
            packetFiles.requireRegularFileTarget(stage);
            try (FileChannel channel = FileChannel.open(
                    stage,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                faultInjector.at(FaultStage.AFTER_MEMBER_STAGE_CREATE, ordinal);
                writeBytes(channel, member.bytes(), ordinal);
                faultInjector.at(FaultStage.AFTER_MEMBER_FINAL_WRITE, ordinal);
                channel.force(true);
                faultInjector.at(FaultStage.AFTER_MEMBER_FLUSH, ordinal);
            } catch (FileAlreadyExistsException raced) {
                throw failure(
                        FailureReason.UNSAFE_RECOVERY,
                        "qualification publication stage appeared concurrently",
                        raced);
            } catch (PublicationException e) {
                throw e;
            } catch (IOException e) {
                throw failure(FailureReason.STAGE_IO, "qualification publication staging failed", e);
            }
            requireExact(stage, member.bytes(), FailureReason.STAGE_IO);
            faultInjector.at(FaultStage.AFTER_MEMBER_VALIDATION, ordinal);
        }

        private void writeBytes(FileChannel channel, byte[] bytes, int ordinal) throws IOException {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            int first = Math.min(source.remaining(), Math.max(1, source.remaining() / 2));
            if (first > 0) {
                ByteBuffer part = source.slice();
                part.limit(first);
                writeFully(channel, part);
                source.position(source.position() + first);
            }
            faultInjector.at(FaultStage.AFTER_MEMBER_PARTIAL_WRITE, ordinal);
            writeFully(channel, source);
        }

        private void promoteCreateOnly(
                Path stage,
                Path target,
                byte[] expected,
                int ordinal) throws IOException {
            packetFiles.requireRegularFile(stage);
            packetFiles.requireRegularFileTarget(target);
            boolean targetDirectorySynchronized = false;
            try {
                Files.createLink(target, stage);
                packetFiles.requireRegularFile(target);
                if (!Files.isSameFile(stage, target)) {
                    throw failure(
                            FailureReason.PROMOTION_FAILED,
                            "qualification publication target does not reference the staged file");
                }
                AtomicFileWriter.synchronizeDirectory(target.getParent());
                targetDirectorySynchronized = true;
                faultInjector.at(FaultStage.AFTER_TARGET_CREATE, ordinal);
            } catch (UnsupportedOperationException unsupported) {
                throw failure(
                        FailureReason.ATOMIC_MOVE_UNSUPPORTED,
                        "qualification publication requires create-only link promotion",
                        unsupported);
            } catch (FileAlreadyExistsException raced) {
                throw failure(
                        FailureReason.CONFLICT,
                        "qualification publication target appeared concurrently",
                        raced);
            } catch (IOException failed) {
                NoFollowPathPolicy.EntryType stageType = packetFiles.inspect(stage);
                NoFollowPathPolicy.EntryType targetType = packetFiles.inspect(target);
                if (targetType == NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                    CommitState state = ordinal == -1
                            ? targetDirectorySynchronized
                                    ? CommitState.COMMITTED
                                    : CommitState.COMMIT_STATE_UNKNOWN
                            : CommitState.NOT_COMMITTED;
                    throw new PublicationException(
                            FailureReason.PROMOTION_FAILED,
                            state,
                            "qualification publication promotion outcome is unknown",
                            failed);
                }
                throw failure(
                        FailureReason.PROMOTION_FAILED,
                        "qualification publication promotion failed",
                        failed);
            }
            requirePromotionLink(
                    stage,
                    target,
                    expected,
                    "qualification publication target");
        }

        private void writeAtomicCreate(
                Path target,
                byte[] bytes,
                String transactionId,
                String setSha256,
                String role,
                int ordinal,
                FaultStage afterFlush,
                FaultStage beforePromotion) throws IOException {
            Path parent = target.getParent();
            packetFiles.requireDirectory(parent);
            Path stage = metadataStagePath(
                    packetRoot, role, transactionId, setSha256);
            try {
                NoFollowPathPolicy.EntryType stageType = packetFiles.inspect(stage);
                if (stageType != NoFollowPathPolicy.EntryType.MISSING) {
                    if (stageType != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                        throw failure(
                                FailureReason.UNSAFE_RECOVERY,
                                "qualification publication metadata stage is ambiguous");
                    }
                    if (!Arrays.equals(packetFiles.readAllBytes(stage), bytes)) {
                        deleteOwnedStage(stage);
                        stageType = NoFollowPathPolicy.EntryType.MISSING;
                    } else {
                        forceExact(stage, bytes, FailureReason.STAGE_IO);
                    }
                }
                if (stageType == NoFollowPathPolicy.EntryType.MISSING) {
                    packetFiles.requireRegularFileTarget(stage);
                    try (FileChannel channel = FileChannel.open(
                            stage,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                        faultInjector.at(FaultStage.AFTER_METADATA_STAGE_CREATE, ordinal);
                        writeMetadataBytes(channel, bytes, ordinal);
                        faultInjector.at(FaultStage.AFTER_METADATA_FINAL_WRITE, ordinal);
                        channel.force(true);
                    }
                    requireExact(stage, bytes, FailureReason.STAGE_IO);
                }
                if (afterFlush != null) faultInjector.at(afterFlush, ordinal);
                if (beforePromotion != null) faultInjector.at(beforePromotion, ordinal);
            } catch (PublicationException e) {
                throw e;
            } catch (FileAlreadyExistsException e) {
                throw failure(
                        FailureReason.UNSAFE_RECOVERY,
                        "qualification publication metadata stage appeared concurrently",
                        e);
            } catch (IOException e) {
                throw failure(
                        FailureReason.STAGE_IO,
                        "qualification publication metadata staging failed",
                        e);
            }
            promoteCreateOnly(stage, target, bytes, ordinal);
        }

        private void writeMetadataBytes(FileChannel channel, byte[] bytes, int ordinal)
                throws IOException {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            int first = Math.min(source.remaining(), Math.max(1, source.remaining() / 2));
            if (first > 0) {
                ByteBuffer part = source.slice();
                part.limit(first);
                writeFully(channel, part);
                source.position(source.position() + first);
            }
            faultInjector.at(FaultStage.AFTER_METADATA_PARTIAL_WRITE, ordinal);
            writeFully(channel, source);
        }

        private void cleanupCommitted(
                Path intentPath,
                PreparedSet desired,
                IntentRecord expectedIntent) throws IOException {
            try {
                for (int index = 0; index < desired.members().size(); index++) {
                    PreparedMember member = desired.members().get(index);
                    if (packetFiles.inspect(member.stagePath())
                            != NoFollowPathPolicy.EntryType.MISSING) {
                        requirePromotionLink(
                                member.stagePath(),
                                member.path(),
                                member.bytes(),
                                "qualification publication member");
                        forceExact(
                                member.path(),
                                member.bytes(),
                                FailureReason.UNSAFE_RECOVERY);
                        AtomicFileWriter.synchronizeDirectory(member.path().getParent());
                        deleteOwnedStage(member.stagePath());
                    }
                    faultInjector.at(FaultStage.AFTER_MEMBER_STAGE_CLEANUP, index);
                }
                deleteExactMetadataStageIfPresent(
                        metadataStagePath(
                                packetRoot,
                                "intent",
                                desired.transactionId(),
                                desired.setSha256()),
                        QualificationJson.bytes(expectedIntent),
                        intentPath);
                faultInjector.at(FaultStage.AFTER_INTENT_STAGE_CLEANUP, -1);
                deleteExactMetadataStageIfPresent(
                        metadataStagePath(
                                packetRoot,
                                "commit",
                                desired.transactionId(),
                                desired.setSha256()),
                        QualificationJson.bytes(desired.commit()),
                        commitPath(packetRoot, desired.transactionId()));
                faultInjector.at(FaultStage.AFTER_COMMIT_STAGE_CLEANUP, -1);
                faultInjector.at(FaultStage.BEFORE_INTENT_CLEANUP, -1);
                if (packetFiles.inspect(intentPath) != NoFollowPathPolicy.EntryType.MISSING) {
                    IntentRecord intent = readCanonicalIntent(packetFiles, intentPath);
                    if (!intent.equals(expectedIntent)) {
                        throw failure(
                                FailureReason.UNSAFE_RECOVERY,
                                "qualification publication intent changed before cleanup");
                    }
                    deleteOwnedStage(intentPath);
                }
                faultInjector.at(FaultStage.AFTER_INTENT_CLEANUP, -1);
            } catch (PublicationException e) {
                throw new PublicationException(
                        FailureReason.CLEANUP_FAILED,
                        CommitState.COMMITTED,
                        "qualification publication committed but cleanup failed",
                        e);
            } catch (IOException | RuntimeException e) {
                throw new PublicationException(
                        FailureReason.CLEANUP_FAILED,
                        CommitState.COMMITTED,
                        "qualification publication committed but cleanup failed",
                        e);
            }
        }

        private void cleanupCommittedOrphanIntentStage(PreparedSet desired)
                throws IOException {
            Path stage = metadataStagePath(
                    packetRoot,
                    "intent",
                    desired.transactionId(),
                    desired.setSha256());
            if (packetFiles.inspect(stage) == NoFollowPathPolicy.EntryType.MISSING) return;
            try {
                requireExact(
                        stage,
                        QualificationJson.bytes(desired.intent()),
                        FailureReason.UNSAFE_RECOVERY);
                deleteOwnedStage(stage);
            } catch (PublicationException e) {
                throw new PublicationException(
                        FailureReason.CLEANUP_FAILED,
                        CommitState.COMMITTED,
                        "committed qualification publication has an invalid orphan intent stage",
                        e);
            } catch (IOException | RuntimeException e) {
                throw new PublicationException(
                        FailureReason.CLEANUP_FAILED,
                        CommitState.COMMITTED,
                        "committed qualification publication could not settle its orphan intent stage",
                        e);
            }
        }

        private void deleteExactMetadataStageIfPresent(
                Path stage,
                byte[] expected,
                Path target)
                throws IOException {
            if (packetFiles.inspect(stage) == NoFollowPathPolicy.EntryType.MISSING) return;
            requirePromotionLink(
                    stage,
                    target,
                    expected,
                    "qualification publication metadata");
            forceExact(target, expected, FailureReason.UNSAFE_RECOVERY);
            AtomicFileWriter.synchronizeDirectory(target.getParent());
            deleteOwnedStage(stage);
        }

        private void rejectForeignRecoveryState(
                PreparedSet desired,
                Path expectedCommit,
                Path expectedIntent) throws IOException {
            Path expectedIntentStage = metadataStagePath(
                    packetRoot,
                    "intent",
                    desired.transactionId(),
                    desired.setSha256());
            Path expectedCommitStage = metadataStagePath(
                    packetRoot,
                    "commit",
                    desired.transactionId(),
                    desired.setSha256());
            rejectUnexpectedEntries(
                    controlRoot.resolve(INTENTS_DIRECTORY),
                    Set.of(expectedIntent),
                    false);
            rejectUnexpectedEntries(
                    controlRoot.resolve(COMMITS_DIRECTORY),
                    Set.of(),
                    true);
            Set<Path> allowedStages = new java.util.HashSet<>();
            allowedStages.add(expectedIntentStage);
            if (packetFiles.inspect(expectedIntent)
                    == NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                allowedStages.add(expectedCommitStage);
                desired.members().stream()
                        .map(PreparedMember::stagePath)
                        .forEach(allowedStages::add);
            }
            rejectUnexpectedEntries(
                    controlRoot.resolve(STAGES_DIRECTORY),
                    Set.copyOf(allowedStages),
                    false);
        }

        private void rejectUnexpectedEntries(
                Path directory,
                Set<Path> allowed,
                boolean allowCommitRecords) throws IOException {
            packetFiles.requireDirectory(directory);
            try (var entries = Files.list(directory)) {
                for (Path entry : entries.toList()) {
                    Path normalized = entry.toAbsolutePath().normalize();
                    NoFollowPathPolicy.EntryType type = packetFiles.inspect(normalized);
                    boolean allowedCommit = allowCommitRecords
                            && normalized.getFileName().toString().endsWith(".json");
                    if ((!allowed.contains(normalized) && !allowedCommit)
                            || type != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                        throw failure(
                                FailureReason.UNSAFE_RECOVERY,
                                "qualification publication has foreign recovery state");
                    }
                    if (allowedCommit) {
                        validateCommitEntry(packetFiles, packetRoot, campaign, normalized);
                    }
                }
            }
        }

        private void requirePromotionLink(
                Path stage,
                Path target,
                byte[] expected,
                String label) throws IOException {
            if (packetFiles.inspect(stage) != NoFollowPathPolicy.EntryType.REGULAR_FILE
                    || packetFiles.inspect(target)
                            != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                throw failure(
                        FailureReason.UNSAFE_RECOVERY,
                        label + " lacks its transaction-owned promotion link");
            }
            requireExact(stage, expected, FailureReason.UNSAFE_RECOVERY);
            requireExact(target, expected, FailureReason.UNSAFE_RECOVERY);
            try {
                if (!Files.isSameFile(stage, target)) {
                    throw failure(
                            FailureReason.UNSAFE_RECOVERY,
                            label + " is not linked to its transaction-owned stage");
                }
            } catch (PublicationException e) {
                throw e;
            } catch (IOException e) {
                throw failure(
                        FailureReason.UNSAFE_RECOVERY,
                        label + " promotion identity cannot be verified",
                        e);
            }
        }

        private void deleteOwnedStage(Path path) throws IOException {
            packetFiles.requireRegularFile(path);
            Files.delete(path);
            AtomicFileWriter.synchronizeDirectory(path.getParent());
            if (packetFiles.inspect(path) != NoFollowPathPolicy.EntryType.MISSING) {
                throw failure(FailureReason.CLEANUP_FAILED, "qualification cleanup did not remove stage");
            }
        }

        private void requireExact(Path path, byte[] expected, FailureReason reason)
                throws IOException {
            if (packetFiles.inspect(path) != NoFollowPathPolicy.EntryType.REGULAR_FILE
                    || !Arrays.equals(packetFiles.readAllBytes(path), expected)) {
                throw failure(reason, "qualification publication bytes conflict");
            }
        }

        private void forceExact(Path path, byte[] expected, FailureReason reason)
                throws IOException {
            requireExact(path, expected, reason);
            try (FileChannel channel = FileChannel.open(
                    path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
            requireExact(path, expected, reason);
        }
    }

    private record PreparedMember(
            Path path,
            byte[] bytes,
            String relativePath,
            String sha256,
            Path stagePath) {
        PreparedMember {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record PreparedSet(
            PublicationKind kind,
            String transactionId,
            String setSha256,
            List<PreparedMember> members,
            CommitRecord commit,
            IntentRecord intent) {}

    private record PhaseLink(
            int ordinal,
            String predecessorTransactionId,
            String predecessorCommitSha256) {}

    private static PreparedSet prepare(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            PublicationKind kind,
            List<Member> requestedMembers,
            boolean includeBytes) throws IOException {
        Objects.requireNonNull(kind, "publication kind");
        if (requestedMembers == null || requestedMembers.isEmpty()) {
            throw failure(FailureReason.INVALID_REQUEST, "qualification publication has no members");
        }
        List<Member> sorted = requestedMembers.stream()
                .sorted(Comparator.comparing(member -> relativePath(packetRoot, member.path())))
                .toList();
        List<String> relativePaths = new ArrayList<>();
        List<MemberRecord> records = new ArrayList<>();
        Set<String> identities = new java.util.HashSet<>();
        for (Member member : sorted) {
            String relative = relativePath(packetRoot, member.path());
            if (relative.startsWith(CONTROL_DIRECTORY + "/")) {
                throw failure(
                        FailureReason.INVALID_REQUEST,
                        "publication member collides with the reserved control namespace");
            }
            String identity = Normalizer.normalize(relative, Normalizer.Form.NFC)
                    .toLowerCase(Locale.ROOT);
            if (!identities.add(identity)) {
                throw failure(
                        FailureReason.INVALID_REQUEST,
                        "publication member paths collide after canonicalization");
            }
            relativePaths.add(relative);
            byte[] bytes = includeBytes ? member.bytes() : new byte[0];
            records.add(new MemberRecord(relative, bytes.length, sha256(bytes)));
        }
        validateKindMembers(kind, relativePaths, campaign);
        PhaseLink phaseLink = resolvePhaseLink(
                packetFiles, packetRoot, campaign, kind, relativePaths);
        String transactionId = transactionId(kind, relativePaths);
        String setSha256 = hashMemberRecords(kind, records);
        List<PreparedMember> prepared = new ArrayList<>();
        for (int index = 0; index < sorted.size(); index++) {
            Member member = sorted.get(index);
            MemberRecord record = records.get(index);
            Path stage = memberStagePath(
                    packetRoot, transactionId, setSha256, index);
            prepared.add(new PreparedMember(
                    member.path(),
                    includeBytes ? member.bytes() : new byte[0],
                    record.relativePath(),
                    record.sha256(),
                    stage));
        }
        CommitRecord commit = new CommitRecord(
                COMMIT_SCHEMA,
                SCHEMA_VERSION,
                QualificationProtocol.VERSION,
                transactionId,
                kind,
                phaseLink.ordinal(),
                phaseLink.predecessorTransactionId(),
                phaseLink.predecessorCommitSha256(),
                campaign.campaignId(),
                campaign.scenarioBankSha256(),
                setSha256,
                records);
        List<String> stagePaths = prepared.stream()
                .map(member -> relativePath(packetRoot, member.stagePath()))
                .toList();
        IntentRecord intent = new IntentRecord(
                INTENT_SCHEMA,
                SCHEMA_VERSION,
                QualificationProtocol.VERSION,
                kind == PublicationKind.SCORECARD ? "ENDED" : "ACTIVE",
                commit,
                stagePaths);
        return new PreparedSet(kind, transactionId, setSha256, List.copyOf(prepared), commit, intent);
    }

    private static void validateCommitIdentity(
            CommitRecord committed,
            PreparedSet expected,
            boolean compareContent) throws PublicationException {
        boolean sameIdentity = committed.transactionId().equals(expected.transactionId())
                && committed.publicationKind() == expected.kind()
                && committed.runPhaseOrdinal() == expected.commit().runPhaseOrdinal()
                && committed.predecessorTransactionId().equals(
                        expected.commit().predecessorTransactionId())
                && committed.predecessorCommitSha256().equals(
                        expected.commit().predecessorCommitSha256())
                && committed.campaignId().equals(expected.commit().campaignId())
                && committed.scenarioBankSha256()
                        .equals(expected.commit().scenarioBankSha256())
                && committed.members().stream().map(MemberRecord::relativePath).toList()
                        .equals(expected.commit().members().stream()
                                .map(MemberRecord::relativePath).toList());
        boolean sameContent = !compareContent
                || committed.setSha256().equals(expected.setSha256())
                && committed.equals(expected.commit());
        if (!sameIdentity || !sameContent) {
            throw failure(FailureReason.CONFLICT, "qualification publication commit conflicts");
        }
    }

    private static PhaseLink resolvePhaseLink(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            PublicationKind kind,
            List<String> relativePaths) throws IOException {
        int ordinal = runPhaseOrdinal(kind);
        PublicationKind predecessorKind = runPhasePredecessor(kind);
        if (predecessorKind == null) {
            return new PhaseLink(ordinal, "", "");
        }
        List<String> predecessorPaths = runPhaseRelativePaths(
                relativePaths, predecessorKind);
        String predecessorTransactionId = transactionId(
                predecessorKind, predecessorPaths);
        Path predecessorPath = commitPath(packetRoot, predecessorTransactionId);
        NoFollowPathPolicy.EntryType type = packetFiles.inspect(predecessorPath);
        if (type == NoFollowPathPolicy.EntryType.MISSING) {
            return new PhaseLink(ordinal, predecessorTransactionId, "");
        }
        if (type != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
            throw failure(
                    FailureReason.INVALID_COMMIT,
                    "qualification run-phase predecessor commit path is invalid");
        }
        CommitRecord predecessor = readCanonicalCommit(packetFiles, predecessorPath);
        validateCommitRecordShape(packetRoot, campaign, predecessor);
        if (predecessor.publicationKind() != predecessorKind
                || !predecessor.members().stream()
                        .map(MemberRecord::relativePath)
                        .toList()
                        .equals(predecessorPaths)
                || !predecessor.transactionId().equals(predecessorTransactionId)) {
            throw failure(
                    FailureReason.INVALID_COMMIT,
                    "qualification run-phase predecessor identity is invalid");
        }
        readAndVerifyMembers(packetFiles, packetRoot, campaign, predecessor);
        return new PhaseLink(
                ordinal,
                predecessorTransactionId,
                sha256(QualificationJson.bytes(predecessor)));
    }

    private static int runPhaseOrdinal(PublicationKind kind) {
        return switch (kind) {
            case PROVIDER_EVIDENCE -> 1;
            case APPROVAL_EVIDENCE -> 2;
            case MECHANICAL_REQUIREMENTS -> 3;
            case RUN_FINALIZATION -> 4;
            default -> 0;
        };
    }

    private static PublicationKind runPhasePredecessor(PublicationKind kind) {
        return switch (kind) {
            case APPROVAL_EVIDENCE -> PublicationKind.PROVIDER_EVIDENCE;
            case MECHANICAL_REQUIREMENTS -> PublicationKind.APPROVAL_EVIDENCE;
            case RUN_FINALIZATION -> PublicationKind.MECHANICAL_REQUIREMENTS;
            default -> null;
        };
    }

    private static List<String> runPhaseRelativePaths(
            List<String> currentPaths,
            PublicationKind requestedKind) {
        Path parent = Path.of(currentPaths.getFirst()).getParent();
        if (parent == null || currentPaths.stream()
                .map(Path::of)
                .map(Path::getParent)
                .anyMatch(candidate -> !Objects.equals(parent, candidate))) {
            throw invalidArgument("qualification run-phase members do not share one run");
        }
        return runPhaseMemberNames(requestedKind).stream()
                .map(parent::resolve)
                .map(path -> path.toString().replace('\\', '/'))
                .sorted()
                .toList();
    }

    private static List<String> runPhaseMemberNames(PublicationKind kind) {
        return switch (kind) {
            case PROVIDER_EVIDENCE -> List.of("llama.log", "prompt-debug.txt");
            case APPROVAL_EVIDENCE -> List.of(QualificationApprovalFinalizer.OUTPUT_FILE);
            case MECHANICAL_REQUIREMENTS ->
                    List.of(QualificationRequirementFinalizer.OUTPUT_FILE);
            case RUN_FINALIZATION -> List.of(
                    QualificationCausalFinalizer.OUTPUT_FILE,
                    QualificationRunFinalizer.CANARY_FILE,
                    QualificationRunFinalizer.RESULT_FILE);
            default -> throw invalidArgument(
                    "publication kind is not part of the run-phase chain");
        };
    }

    private static String transactionId(PublicationKind kind, List<String> relativePaths) {
        return hashFields(
                "transaction", kind.name(), String.join("\u0000", relativePaths));
    }

    private static String transactionIdentity(
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            PublicationKind kind,
            List<Path> expectedMembers) throws PublicationException {
        Objects.requireNonNull(kind, "publication kind");
        if (expectedMembers == null || expectedMembers.isEmpty()) {
            throw failure(
                    FailureReason.INVALID_REQUEST,
                    "qualification publication has no members");
        }
        List<String> relativePaths = expectedMembers.stream()
                .map(path -> relativePath(packetRoot, path))
                .sorted()
                .toList();
        if (new java.util.HashSet<>(relativePaths).size() != relativePaths.size()) {
            throw failure(
                    FailureReason.INVALID_REQUEST,
                    "qualification publication member paths collide");
        }
        validateKindMembers(kind, relativePaths, campaign);
        return transactionId(kind, relativePaths);
    }

    private static Map<String, byte[]> readAndVerifyMembers(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            CommitRecord commit) throws IOException {
        validateCommitRecordShape(packetRoot, campaign, commit);
        validateRunPhasePredecessor(packetFiles, packetRoot, campaign, commit);
        List<MemberRecord> canonical = canonicalMemberRecords(commit.members());
        validateKindMembers(
                commit.publicationKind(),
                canonical.stream().map(MemberRecord::relativePath).toList(),
                campaign);
        if (!hashMemberRecords(commit.publicationKind(), canonical).equals(commit.setSha256())) {
            throw failure(FailureReason.INVALID_COMMIT, "qualification commit set hash is invalid");
        }
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (MemberRecord member : canonical) {
            Path path = packetRoot.resolve(member.relativePath()).normalize();
            if (!path.startsWith(packetRoot)) {
                throw failure(FailureReason.INVALID_COMMIT, "qualification commit path escapes packet");
            }
            byte[] content;
            try {
                content = packetFiles.readAllBytes(path);
            } catch (IOException e) {
                throw failure(
                        FailureReason.INCOMPLETE,
                        "qualification committed member is unavailable",
                        e);
            }
            if (content.length != member.byteLength()
                    || !sha256(content).equals(member.sha256())) {
                throw failure(
                        FailureReason.INVALID_COMMIT,
                        "qualification committed member bytes are invalid");
            }
            bytes.put(member.relativePath(), content);
        }
        return Map.copyOf(bytes);
    }

    private static void validateRunPhasePredecessor(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            CommitRecord commit) throws IOException {
        PublicationKind predecessorKind = runPhasePredecessor(commit.publicationKind());
        if (predecessorKind == null) return;
        List<String> predecessorPaths = runPhaseRelativePaths(
                commit.members().stream().map(MemberRecord::relativePath).toList(),
                predecessorKind);
        String expectedTransactionId = transactionId(predecessorKind, predecessorPaths);
        if (!commit.predecessorTransactionId().equals(expectedTransactionId)) {
            throw failure(
                    FailureReason.INVALID_COMMIT,
                    "qualification run-phase predecessor transaction is invalid");
        }
        Path predecessorPath = commitPath(packetRoot, expectedTransactionId);
        if (packetFiles.inspect(predecessorPath)
                != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
            throw failure(
                    FailureReason.INVALID_COMMIT,
                    "qualification run-phase predecessor is not committed");
        }
        CommitRecord predecessor = readCanonicalCommit(packetFiles, predecessorPath);
        if (predecessor.publicationKind() != predecessorKind
                || !predecessor.members().stream()
                        .map(MemberRecord::relativePath)
                        .toList()
                        .equals(predecessorPaths)
                || !commit.predecessorCommitSha256().equals(
                        sha256(QualificationJson.bytes(predecessor)))) {
            throw failure(
                    FailureReason.INVALID_COMMIT,
                    "qualification run-phase predecessor commit identity is invalid");
        }
        readAndVerifyMembers(packetFiles, packetRoot, campaign, predecessor);
    }

    private static CommitRecord readCanonicalCommit(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path path) throws IOException {
        byte[] bytes = packetFiles.readAllBytes(path);
        CommitRecord record;
        try {
            record = QualificationJson.read(bytes, CommitRecord.class);
        } catch (IOException | RuntimeException e) {
            throw failure(FailureReason.INVALID_COMMIT, "qualification commit record is invalid", e);
        }
        if (!Arrays.equals(bytes, QualificationJson.bytes(record))) {
            throw failure(FailureReason.INVALID_COMMIT, "qualification commit record is not canonical");
        }
        return record;
    }

    private static IntentRecord readCanonicalIntent(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path path) throws IOException {
        byte[] bytes = packetFiles.readAllBytes(path);
        IntentRecord record;
        try {
            record = QualificationJson.read(bytes, IntentRecord.class);
        } catch (IOException | RuntimeException e) {
            throw failure(
                    FailureReason.UNSAFE_RECOVERY,
                    "qualification publication intent is invalid",
                    e);
        }
        if (!Arrays.equals(bytes, QualificationJson.bytes(record))) {
            throw failure(
                    FailureReason.UNSAFE_RECOVERY,
                    "qualification publication intent is not canonical");
        }
        return record;
    }

    private static void validateSettledControlState(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign) throws IOException {
        validateControlState(packetFiles, packetRoot, campaign, null);
    }

    private static void validateControlState(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            String allowedTransactionId) throws IOException {
        Path controlRoot = packetRoot.resolve(CONTROL_DIRECTORY);
        requireExistingControlState(packetFiles, controlRoot);
        validateCommitInventory(packetFiles, packetRoot, campaign, controlRoot);
        Path intents = controlRoot.resolve(INTENTS_DIRECTORY);
        IntentRecord allowedIntent = null;
        try (var entries = Files.list(intents)) {
            for (Path entry : entries.toList()) {
                if (packetFiles.inspect(entry) != NoFollowPathPolicy.EntryType.REGULAR_FILE
                        || !entry.getFileName().toString().endsWith(".json")) {
                    throw failure(
                            FailureReason.UNSAFE_RECOVERY,
                            "qualification intent inventory contains an unexpected entry");
                }
                IntentRecord intent = validateIntentEntry(
                        packetFiles, packetRoot, campaign, entry);
                if (!intent.desiredCommit().transactionId().equals(allowedTransactionId)
                        || allowedIntent != null) {
                    throw failure(
                            FailureReason.INCOMPLETE,
                            "qualification publication has an incomplete transaction");
                }
                allowedIntent = intent;
            }
        }
        Path stages = controlRoot.resolve(STAGES_DIRECTORY);
        try (var entries = Files.list(stages)) {
            for (Path entry : entries.toList()) {
                if (packetFiles.inspect(entry) != NoFollowPathPolicy.EntryType.REGULAR_FILE
                        || !stageBelongsToAllowedTransaction(
                                packetRoot, entry, allowedTransactionId, allowedIntent)) {
                    throw failure(
                            FailureReason.INCOMPLETE,
                            "qualification publication has staged but uncommitted bytes");
                }
            }
        }
    }

    private static void validateCommitInventory(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            Path controlRoot) throws IOException {
        Path commits = controlRoot.resolve(COMMITS_DIRECTORY);
        try (var entries = Files.list(commits)) {
            for (Path entry : entries.toList()) {
                if (packetFiles.inspect(entry) != NoFollowPathPolicy.EntryType.REGULAR_FILE
                        || !entry.getFileName().toString().endsWith(".json")) {
                    throw failure(
                            FailureReason.INVALID_COMMIT,
                            "qualification commit inventory contains an unexpected entry");
                }
                validateCommitEntry(packetFiles, packetRoot, campaign, entry);
            }
        }
    }

    private static void requireTransactionSettled(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            String transactionId) throws IOException {
        if (packetFiles.inspect(intentPath(packetRoot, transactionId))
                != NoFollowPathPolicy.EntryType.MISSING) {
            throw failure(
                    FailureReason.INCOMPLETE,
                    "qualification publication transaction is not settled");
        }
        Path stages = stageRoot(packetRoot);
        String token = "-" + transactionId + "-";
        try (var entries = Files.list(stages)) {
            if (entries.anyMatch(path -> path.getFileName().toString().contains(token))) {
                throw failure(
                        FailureReason.INCOMPLETE,
                        "qualification publication transaction is not settled");
            }
        }
    }

    private static void validateCommitEntry(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            Path path) throws IOException {
        CommitRecord commit = readCanonicalCommit(packetFiles, path);
        validateCommitRecordShape(packetRoot, campaign, commit);
        if (!path.getFileName().toString().equals(commit.transactionId() + ".json")) {
            throw failure(
                    FailureReason.INVALID_COMMIT,
                    "qualification commit filename does not match its transaction identity");
        }
        readAndVerifyMembers(packetFiles, packetRoot, campaign, commit);
    }

    private static void requireCommittedExecutionIdentity(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            Path controlRoot) throws IOException {
        List<Path> members = List.of(
                packetRoot.resolve(QualificationExecutionIdentityWriter.LEDGER_FILE),
                packetRoot.resolve(QualificationExecutionIdentity.HARNESS_FILE),
                packetRoot.resolve(QualificationExecutionIdentity.RUNTIME_FILE));
        PreparedSet expected = prepare(
                packetFiles,
                packetRoot,
                campaign,
                PublicationKind.EXECUTION_IDENTITY,
                members.stream().map(path -> new Member(path, new byte[0])).toList(),
                false);
        requireExistingControlState(packetFiles, controlRoot);
        requireTransactionSettled(packetFiles, packetRoot, expected.transactionId());
        Path commitPath = commitPath(packetRoot, expected.transactionId());
        if (packetFiles.inspect(commitPath) == NoFollowPathPolicy.EntryType.MISSING) {
            throw failure(
                    FailureReason.INCOMPLETE,
                    "qualification execution identity is not committed");
        }
        CommitRecord commit = readCanonicalCommit(packetFiles, commitPath);
        validateCommitIdentity(commit, expected, false);
        readAndVerifyMembers(packetFiles, packetRoot, campaign, commit);
    }

    private static IntentRecord validateIntentEntry(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            Path path) throws IOException {
        IntentRecord intent = readCanonicalIntent(packetFiles, path);
        CommitRecord desired = intent.desiredCommit();
        validateCommitRecordShape(packetRoot, campaign, desired);
        if (!path.getFileName().toString().equals(desired.transactionId() + ".json")) {
            throw failure(
                    FailureReason.UNSAFE_RECOVERY,
                    "qualification intent filename does not match its transaction identity");
        }
        List<String> expectedStages = new ArrayList<>();
        for (int index = 0; index < desired.members().size(); index++) {
            expectedStages.add(relativePath(
                    packetRoot,
                    memberStagePath(
                            packetRoot,
                            desired.transactionId(),
                            desired.setSha256(),
                            index)));
        }
        if (!expectedStages.equals(intent.stagePaths())) {
            throw failure(
                    FailureReason.UNSAFE_RECOVERY,
                    "qualification intent stage inventory is invalid");
        }
        return intent;
    }

    private static boolean stageBelongsToAllowedTransaction(
            Path packetRoot,
            Path stage,
            String allowedTransactionId,
            IntentRecord allowedIntent) {
        if (allowedTransactionId == null) return false;
        String name = stage.getFileName().toString();
        String transactionToken = "-" + allowedTransactionId + "-";
        if (!name.contains(transactionToken) || !name.endsWith(".stage")) return false;
        if (allowedIntent == null) {
            return name.startsWith("intent-");
        }
        CommitRecord desired = allowedIntent.desiredCommit();
        Set<Path> expected = new java.util.HashSet<>();
        expected.add(metadataStagePath(
                packetRoot,
                "intent",
                desired.transactionId(),
                desired.setSha256()));
        expected.add(metadataStagePath(
                packetRoot,
                "commit",
                desired.transactionId(),
                desired.setSha256()));
        for (int index = 0; index < desired.members().size(); index++) {
            expected.add(memberStagePath(
                    packetRoot,
                    desired.transactionId(),
                    desired.setSha256(),
                    index));
        }
        return expected.contains(stage.toAbsolutePath().normalize());
    }

    private static void validateCommitRecordShape(
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            CommitRecord commit) throws PublicationException {
        if (!commit.campaignId().equals(campaign.campaignId())
                || !commit.scenarioBankSha256().equals(campaign.scenarioBankSha256())) {
            throw failure(
                    FailureReason.INVALID_COMMIT,
                    "qualification commit identity is outside the current campaign");
        }
        List<MemberRecord> members = canonicalMemberRecords(commit.members());
        List<String> relativePaths = members.stream().map(MemberRecord::relativePath).toList();
        validateKindMembers(commit.publicationKind(), relativePaths, campaign);
        int expectedOrdinal = runPhaseOrdinal(commit.publicationKind());
        boolean firstOrUnphased = expectedOrdinal <= 1;
        if (commit.runPhaseOrdinal() != expectedOrdinal
                || firstOrUnphased != commit.predecessorTransactionId().isBlank()
                || firstOrUnphased != commit.predecessorCommitSha256().isBlank()) {
            throw failure(
                    FailureReason.INVALID_COMMIT,
                    "qualification commit run-phase chain is invalid");
        }
        String transactionId = transactionId(commit.publicationKind(), relativePaths);
        if (!transactionId.equals(commit.transactionId())
                || !hashMemberRecords(commit.publicationKind(), members)
                        .equals(commit.setSha256())) {
            throw failure(
                    FailureReason.INVALID_COMMIT,
                    "qualification commit hashes are invalid");
        }
        for (MemberRecord member : members) {
            Path resolved = packetRoot.resolve(member.relativePath()).normalize();
            if (!resolved.startsWith(packetRoot)) {
                throw failure(
                        FailureReason.INVALID_COMMIT,
                        "qualification commit member escapes the packet");
            }
        }
    }

    private static boolean hasCompleteControlState(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path controlRoot) throws IOException {
        NoFollowPathPolicy.EntryType rootType = packetFiles.inspect(controlRoot);
        if (rootType == NoFollowPathPolicy.EntryType.MISSING) return false;
        if (rootType != NoFollowPathPolicy.EntryType.DIRECTORY) {
            throw failure(
                    FailureReason.UNSAFE_RECOVERY,
                    "qualification publication control root is invalid");
        }
        Set<String> expected = Set.of(
                COMMITS_DIRECTORY, INTENTS_DIRECTORY, STAGES_DIRECTORY, LOCK_FILE);
        try (var entries = Files.list(controlRoot)) {
            for (Path entry : entries.toList()) {
                if (!expected.contains(entry.getFileName().toString())) {
                    throw failure(
                            FailureReason.UNSAFE_RECOVERY,
                            "qualification publication control inventory is invalid");
                }
            }
        }
        boolean complete = true;
        for (String directory : List.of(
                COMMITS_DIRECTORY, INTENTS_DIRECTORY, STAGES_DIRECTORY)) {
            NoFollowPathPolicy.EntryType type = packetFiles.inspect(controlRoot.resolve(directory));
            if (type == NoFollowPathPolicy.EntryType.MISSING) {
                complete = false;
            } else if (type != NoFollowPathPolicy.EntryType.DIRECTORY) {
                throw failure(
                        FailureReason.UNSAFE_RECOVERY,
                        "qualification publication control directory is invalid");
            }
        }
        NoFollowPathPolicy.EntryType lockType = packetFiles.inspect(controlRoot.resolve(LOCK_FILE));
        if (lockType == NoFollowPathPolicy.EntryType.MISSING) {
            complete = false;
        } else if (lockType != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
            throw failure(
                    FailureReason.UNSAFE_RECOVERY,
                    "qualification publication lock is invalid");
        }
        return complete;
    }

    private static void requireRecoverableBootstrapControl(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path controlRoot) throws IOException {
        if (packetFiles.inspect(controlRoot) == NoFollowPathPolicy.EntryType.MISSING) return;
        if (hasCompleteControlState(packetFiles, controlRoot)) return;
        for (String directory : List.of(
                COMMITS_DIRECTORY, INTENTS_DIRECTORY, STAGES_DIRECTORY)) {
            Path child = controlRoot.resolve(directory);
            NoFollowPathPolicy.EntryType type = packetFiles.inspect(child);
            if (type == NoFollowPathPolicy.EntryType.MISSING) continue;
            if (type != NoFollowPathPolicy.EntryType.DIRECTORY) {
                throw failure(
                        FailureReason.UNSAFE_RECOVERY,
                        "qualification bootstrap control directory is invalid");
            }
            try (var entries = Files.list(child)) {
                if (entries.findAny().isPresent()) {
                    throw failure(
                            FailureReason.UNSAFE_RECOVERY,
                            "qualification bootstrap control state is not empty");
                }
            }
        }
    }

    private static Path prepareBootstrapDirectory(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path directory,
            Path parent,
            FaultInjector faultInjector,
            FaultStage faultStage) throws IOException {
        NoFollowPathPolicy.EntryType type = packetFiles.inspect(directory);
        Path prepared;
        if (type == NoFollowPathPolicy.EntryType.MISSING) {
            prepared = packetFiles.createDirectories(directory);
        } else if (type == NoFollowPathPolicy.EntryType.DIRECTORY) {
            prepared = packetFiles.requireDirectory(directory);
        } else {
            throw failure(
                    FailureReason.UNSAFE_RECOVERY,
                    "qualification bootstrap control path is invalid");
        }
        AtomicFileWriter.synchronizeDirectory(parent);
        faultInjector.at(faultStage, -1);
        return prepared;
    }

    private static void requireExistingControlState(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path controlRoot) throws IOException {
        packetFiles.requireDirectory(controlRoot);
        packetFiles.requireDirectory(controlRoot.resolve(COMMITS_DIRECTORY));
        packetFiles.requireDirectory(controlRoot.resolve(INTENTS_DIRECTORY));
        packetFiles.requireDirectory(controlRoot.resolve(STAGES_DIRECTORY));
        packetFiles.requireRegularFile(controlRoot.resolve(LOCK_FILE));
        Set<String> expected = Set.of(
                COMMITS_DIRECTORY, INTENTS_DIRECTORY, STAGES_DIRECTORY, LOCK_FILE);
        try (var entries = Files.list(controlRoot)) {
            for (Path entry : entries.toList()) {
                if (!expected.contains(entry.getFileName().toString())) {
                    throw failure(
                            FailureReason.UNSAFE_RECOVERY,
                            "qualification publication control inventory is invalid");
                }
            }
        }
    }

    private static void requireBootstrapEligible(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign) throws IOException {
        if (campaign.endedAt() != null && !campaign.endedAt().isBlank()) {
            throw failure(
                    FailureReason.INVALID_REQUEST,
                    "historical qualification packets cannot initialize publication control");
        }
        for (String name : List.of(
                QualificationExecutionIdentityWriter.LEDGER_FILE,
                QualificationExecutionIdentity.HARNESS_FILE,
                QualificationExecutionIdentity.RUNTIME_FILE)) {
            if (packetFiles.inspect(packetRoot.resolve(name))
                    != NoFollowPathPolicy.EntryType.MISSING) {
                throw failure(
                        FailureReason.INVALID_REQUEST,
                        "existing qualification evidence cannot be adopted during bootstrap");
            }
        }
        Path runs = packetRoot.resolve("runs");
        if (packetFiles.inspect(runs) == NoFollowPathPolicy.EntryType.DIRECTORY
                && packetFiles.walk(runs).stream().anyMatch(
                        entry -> entry.type() == NoFollowPathPolicy.EntryType.REGULAR_FILE)) {
            throw failure(
                    FailureReason.INVALID_REQUEST,
                    "started qualification packets cannot initialize publication control");
        }
        Path scorecards = packetRoot.resolve("scorecards");
        if (packetFiles.inspect(scorecards) != NoFollowPathPolicy.EntryType.MISSING) {
            throw failure(
                    FailureReason.INVALID_REQUEST,
                    "existing scorecard state cannot initialize publication control");
        }
        if (packetFiles.inspect(packetRoot.resolve(QualificationCampaignCompletion.OUTPUT_FILE))
                != NoFollowPathPolicy.EntryType.MISSING) {
            throw failure(
                    FailureReason.INVALID_REQUEST,
                    "existing campaign completion cannot initialize publication control");
        }
    }

    private static QualificationRecords.CampaignManifest requireProtocolSixCampaign(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot) throws IOException {
        byte[] bytes;
        try {
            bytes = packetFiles.readAllBytes(packetRoot.resolve("campaign.json"));
        } catch (IOException unavailable) {
            throw failure(
                    FailureReason.INVALID_REQUEST,
                    "qualification campaign.json is unavailable",
                    unavailable);
        }
        QualificationRecords.CampaignManifest campaign = QualificationJson.read(
                bytes, QualificationRecords.CampaignManifest.class);
        if (!Arrays.equals(bytes, QualificationJson.bytes(campaign))) {
            throw failure(
                    FailureReason.INVALID_REQUEST,
                    "qualification campaign manifest is not canonical JSON");
        }
        if (!QualificationRecords.CAMPAIGN_SCHEMA.equals(campaign.schemaName())
                || campaign.schemaVersion() != QualificationRecords.SCHEMA_VERSION
                || campaign.protocolVersion() != QualificationProtocol.VERSION) {
            throw failure(
                    FailureReason.INVALID_REQUEST,
                    "qualification transaction requires a canonical protocol-v6 campaign");
        }
        QualificationNamespace.requireSegment(campaign.campaignId(), "campaign id");
        requireHash(campaign.scenarioBankSha256(), "scenario-bank SHA-256");
        if (campaign.endedAt() != null && !campaign.endedAt().isBlank()) {
            throw failure(
                    FailureReason.INVALID_REQUEST,
                    "protocol-v6 campaign identity must remain immutable and active");
        }
        return campaign;
    }

    private static FileLock acquireProcessLock(
            FileChannel channel,
            long lockStarted,
            long timeoutNanos)
            throws IOException {
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException overlap) {
                throw failure(
                        FailureReason.LOCK_UNAVAILABLE,
                        "qualification publication lease overlaps in this process",
                        overlap);
            }
            if (remainingNanos(lockStarted, timeoutNanos) <= 0) {
                throw failure(FailureReason.LOCK_TIMEOUT, "qualification publication lease timed out");
            }
            try {
                Thread.sleep(LOCK_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw failure(
                        FailureReason.LOCK_INTERRUPTED,
                        "qualification publication lease was interrupted",
                        interrupted);
            }
        }
    }

    private static long remainingNanos(long started, long timeoutNanos) {
        long elapsed = System.nanoTime() - started;
        if (elapsed < 0) return 0;
        return timeoutNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE - Math.min(elapsed, Long.MAX_VALUE)
                : timeoutNanos - elapsed;
    }

    private static Path commitPath(Path packetRoot, String transactionId) {
        return packetRoot.resolve(CONTROL_DIRECTORY)
                .resolve(COMMITS_DIRECTORY)
                .resolve(transactionId + ".json");
    }

    private static Path intentPath(Path packetRoot, String transactionId) {
        return packetRoot.resolve(CONTROL_DIRECTORY)
                .resolve(INTENTS_DIRECTORY)
                .resolve(transactionId + ".json");
    }

    private static Path metadataStagePath(
            Path packetRoot,
            String role,
            String transactionId,
            String setSha256) {
        return stageRoot(packetRoot).resolve(
                role + "-" + transactionId + "-" + setSha256 + ".stage");
    }

    private static Path memberStagePath(
            Path packetRoot,
            String transactionId,
            String setSha256,
            int ordinal) {
        return stageRoot(packetRoot).resolve(
                "member-" + transactionId + "-" + setSha256 + "-" + ordinal + ".stage");
    }

    private static Path stageRoot(Path packetRoot) {
        return packetRoot.resolve(CONTROL_DIRECTORY).resolve(STAGES_DIRECTORY);
    }

    private static List<MemberRecord> canonicalMemberRecords(List<MemberRecord> members) {
        if (members == null || members.isEmpty()) {
            throw invalidArgument("publication members are missing");
        }
        List<MemberRecord> copy = members.stream()
                .map(member -> Objects.requireNonNull(member, "publication member"))
                .sorted(Comparator.comparing(MemberRecord::relativePath))
                .toList();
        if (!copy.equals(members)) {
            throw invalidArgument("publication members are not canonically ordered");
        }
        Set<String> identities = new java.util.HashSet<>();
        for (MemberRecord member : copy) {
            String identity = Normalizer.normalize(member.relativePath(), Normalizer.Form.NFC)
                    .toLowerCase(Locale.ROOT);
            if (!identities.add(identity)) {
                throw invalidArgument("publication member paths collide");
            }
        }
        return List.copyOf(copy);
    }

    private static List<String> canonicalStagePaths(List<String> stages, int expectedSize) {
        if (stages == null || stages.size() != expectedSize) {
            throw invalidArgument("publication stage inventory is incomplete");
        }
        List<String> copy = stages.stream().map(QualificationPacketPublisher::requireRelativePath)
                .toList();
        if (new java.util.HashSet<>(copy).size() != copy.size()) {
            throw invalidArgument("publication stage paths collide");
        }
        return List.copyOf(copy);
    }

    private static void validateKindMembers(
            PublicationKind kind,
            List<String> paths,
            QualificationRecords.CampaignManifest campaign) {
        Objects.requireNonNull(campaign, "campaign");
        List<String> names = paths.stream()
                .map(path -> Path.of(path).getFileName().toString())
                .sorted()
                .toList();
        List<String> expected = switch (kind) {
            case EXECUTION_IDENTITY -> List.of(
                    QualificationExecutionIdentityWriter.LEDGER_FILE,
                    QualificationExecutionIdentity.HARNESS_FILE,
                    QualificationExecutionIdentity.RUNTIME_FILE).stream().sorted().toList();
            case PTY_CAPTURE -> List.of("transcript.raw.json");
            case PTY_FAILURE -> List.of("transcript.failed.txt");
            case APPROVAL_EVIDENCE -> List.of(QualificationApprovalFinalizer.OUTPUT_FILE);
            case MECHANICAL_REQUIREMENTS ->
                    List.of(QualificationRequirementFinalizer.OUTPUT_FILE);
            case PROVIDER_EVIDENCE -> List.of("llama.log", "prompt-debug.txt");
            case RUN_FINALIZATION -> List.of(
                    QualificationCausalFinalizer.OUTPUT_FILE,
                    QualificationRunFinalizer.CANARY_FILE,
                    QualificationRunFinalizer.RESULT_FILE).stream().sorted().toList();
            case CAMPAIGN_COMPLETION -> List.of(QualificationCampaignCompletion.OUTPUT_FILE);
            case SCORECARD -> names.size() == 1 ? names : List.of("<invalid-scorecard>");
        };
        if (!names.equals(expected)) {
            throw invalidArgument("publication member inventory is invalid for " + kind);
        }
        if (kind == PublicationKind.EXECUTION_IDENTITY) {
            if (paths.stream().map(Path::of).anyMatch(path -> path.getNameCount() != 1)) {
                throw invalidArgument("execution identity members must be packet-root files");
            }
            return;
        }
        if (kind == PublicationKind.CAMPAIGN_COMPLETION) {
            if (paths.size() != 1 || Path.of(paths.getFirst()).getNameCount() != 1) {
                throw invalidArgument(
                        "campaign completion must be one packet-root member");
            }
            return;
        }
        if (kind == PublicationKind.SCORECARD) {
            String prefix = campaign.candidateAlias() + "-" + campaign.configId() + "-";
            Set<String> allowed = Set.of(
                    prefix + "admission.json",
                    prefix + "full.json");
            Path path = Path.of(paths.getFirst());
            if (path.getNameCount() != 2
                    || !path.getName(0).toString().equals("scorecards")
                    || !allowed.contains(path.getFileName().toString())) {
                throw invalidArgument("scorecard publication path is not campaign-owned");
            }
            return;
        }
        {
            Path parent = Path.of(paths.getFirst()).getParent();
            if (paths.stream().map(path -> Path.of(path).getParent()).anyMatch(
                    candidate -> !Objects.equals(parent, candidate))) {
                throw invalidArgument("publication members must share one scope");
            }
            validateDeclaredRunParent(parent, campaign);
        }
    }

    private static void validateDeclaredRunParent(
            Path parent,
            QualificationRecords.CampaignManifest campaign) {
        if (parent == null || parent.getNameCount() != 6
                || !parent.getName(0).toString().equals("runs")
                || !parent.getName(1).toString().equals(campaign.candidateAlias())
                || !parent.getName(2).toString().equals(campaign.machineId())
                || !parent.getName(3).toString().equals(campaign.configId())) {
            throw invalidArgument("publication members are outside the campaign run namespace");
        }
        QualificationScenarioBank.loadDefault().scenario(parent.getName(4).toString());
        Set<String> trials = new java.util.HashSet<>();
        for (int index = 0; index < campaign.repetitions().size(); index++) {
            trials.add("trial-%02d".formatted(index + 1));
        }
        if (!trials.contains(parent.getName(5).toString())) {
            throw invalidArgument("publication members are outside the declared trial namespace");
        }
    }

    private static String requireRelativePath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0
                || value.startsWith("/") || value.endsWith("/")
                || value.indexOf('\u0000') >= 0) {
            throw invalidArgument("publication relative path is invalid");
        }
        Path path = Path.of(value);
        if (path.isAbsolute()) throw invalidArgument("publication path must be relative");
        for (Path segment : path) {
            String part = segment.toString();
            if (part.isBlank() || RESERVED_SEGMENTS.contains(part)) {
                throw invalidArgument("publication relative path contains an unsafe segment");
            }
        }
        String canonical = path.normalize().toString().replace('\\', '/');
        if (!canonical.equals(value)) {
            throw invalidArgument("publication relative path is not canonical");
        }
        return value;
    }

    private static String relativePath(Path packetRoot, Path requested) {
        Path path = requested.toAbsolutePath().normalize();
        if (!path.startsWith(packetRoot) || path.equals(packetRoot)) {
            throw invalidArgument("publication member is outside the packet");
        }
        return requireRelativePath(packetRoot.relativize(path).toString().replace('\\', '/'));
    }

    private static String hashMemberRecords(PublicationKind kind, List<MemberRecord> records) {
        MessageDigest digest = newSha256();
        updateField(digest, "set");
        updateField(digest, kind.name());
        for (MemberRecord member : records) {
            updateField(digest, member.relativePath());
            updateLong(digest, member.byteLength());
            updateField(digest, member.sha256());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hashFields(String... fields) {
        MessageDigest digest = newSha256();
        for (String field : fields) updateField(digest, field);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void updateLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    private static MessageDigest newSha256() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (digest.getDigestLength() != HASH_BYTES) {
                throw new IllegalStateException("SHA-256 provider returned an unexpected length");
            }
            return digest;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requireHash(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalidArgument(label + " must be lowercase SHA-256");
        }
        return value;
    }

    private static String requireOptionalHash(String value, String label) {
        if (value == null || !value.isEmpty() && !value.matches("[0-9a-f]{64}")) {
            throw invalidArgument(label + " must be blank or lowercase SHA-256");
        }
        return value;
    }

    private static String requireCampaignPhase(String value) {
        if (!"ACTIVE".equals(value) && !"ENDED".equals(value)) {
            throw invalidArgument("publication campaign phase is invalid");
        }
        return value;
    }

    private static void writeFully(FileChannel channel, ByteBuffer source) throws IOException {
        while (source.hasRemaining()) {
            int written = channel.write(source);
            if (written <= 0) throw new IOException("file channel made no write progress");
        }
    }

    private static PublicationException failure(FailureReason reason, String message) {
        return new PublicationException(reason, CommitState.NOT_COMMITTED, message);
    }

    private static PublicationException failure(
            FailureReason reason,
            String message,
            Throwable cause) {
        return new PublicationException(reason, CommitState.NOT_COMMITTED, message, cause);
    }

    private static IllegalArgumentException invalidArgument(String message) {
        return new IllegalArgumentException(message);
    }
}
