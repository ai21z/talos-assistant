package dev.talos.qualification;

import dev.talos.core.io.AtomicFileWriter;
import dev.talos.core.security.NoFollowPathPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, transaction-owned protocol-v6 campaign lifecycle authority. */
final class QualificationCampaignCompletion {
    static final String OUTPUT_FILE = "campaign-completion.json";

    private QualificationCampaignCompletion() {}

    record Publication(
            Path path,
            QualificationRecords.CampaignCompletion completion,
            boolean created) {}

    record Coordinate(String scenarioId, int repetition) {}

    private interface LifecycleEvidence {
        Coordinate coordinate();
        Instant captureStartedAt();
        Instant captureEndedAt();
        QualificationCampaignContinuationPolicy.Decision continuation();
    }

    record LifecycleFact(
            Coordinate coordinate,
            Instant captureStartedAt,
            Instant captureEndedAt,
            QualificationCampaignContinuationPolicy.Decision continuation)
            implements LifecycleEvidence {}

    record LifecycleDecision(
            QualificationRecords.CampaignCompletionReason reason,
            List<String> stopReasons) {
        LifecycleDecision {
            stopReasons = List.copyOf(stopReasons);
        }
    }

    private record VerifiedRun(
            Coordinate coordinate,
            String repetitionId,
            Path runRoot,
            QualificationRunFinalizer.Publication publication,
            QualificationPacketPublisher.CommitRecord commit,
            Instant captureStartedAt,
            Instant captureEndedAt,
            QualificationCampaignContinuationPolicy.Decision continuation)
            implements LifecycleEvidence {}

    @FunctionalInterface
    private interface RunAuthorityReader {
        VerifiedAuthority read(Path resultPath, List<Path> members) throws IOException;
    }

    private record VerifiedAuthority(
            QualificationRunFinalizer.Publication publication,
            QualificationPacketPublisher.CommitRecord commit) {}

    static Publication complete(
            Path packetRoot,
            QualificationProtocol.BankKind bank,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Objects.requireNonNull(bank, "qualification bank");
        Objects.requireNonNull(catalog, "scenario catalog");
        return QualificationPacketPublisher.withLease(
                packetRoot,
                lease -> completeUnderLease(lease, bank, catalog));
    }

    static Publication completeUnderLease(
            QualificationPacketPublisher.Lease lease,
            QualificationProtocol.BankKind bank,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        QualificationRecords.CampaignCompletion desired = derive(
                lease.packetRoot(), lease.campaign(), bank, catalog,
                (resultPath, members) -> {
                    QualificationPacketPublisher.CommittedSet committed =
                            lease.requireCommittedSet(
                                    QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                                    members);
                    return new VerifiedAuthority(
                            QualificationRunFinalizer.verifyPublished(
                                    lease, resultPath, catalog),
                            committed.commit());
                });
        Path output = lease.packetRoot().resolve(OUTPUT_FILE);
        QualificationPacketPublisher.PublicationStatus before = lease.publicationStatus(
                QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                List.of(output));
        QualificationPacketPublisher.CommittedSet committed = lease.publish(
                QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                List.of(new QualificationPacketPublisher.Member(
                        output, QualificationJson.bytes(desired))));
        QualificationRecords.CampaignCompletion declared = parseCommitted(
                lease.packetRoot(), output, committed, lease.campaign(), catalog);
        if (!declared.equals(desired)) {
            throw new IOException(
                    "committed campaign completion conflicts with current run authority");
        }
        return new Publication(
                output,
                declared,
                before == QualificationPacketPublisher.PublicationStatus.ABSENT
                        && committed.created());
    }

    static QualificationRecords.CampaignCompletion requireCommitted(
            QualificationPacketPublisher.Lease lease,
            QualificationProtocol.BankKind bank,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Path output = lease.packetRoot().resolve(OUTPUT_FILE);
        QualificationPacketPublisher.CommittedSet committed = lease.requireCommittedSet(
                QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                List.of(output));
        QualificationRecords.CampaignCompletion declared = parseCommitted(
                lease.packetRoot(), output, committed, lease.campaign(), catalog);
        QualificationRecords.CampaignCompletion expected = derive(
                lease.packetRoot(), lease.campaign(), bank, catalog,
                (resultPath, members) -> {
                    QualificationPacketPublisher.CommittedSet runCommit =
                            lease.requireCommittedSet(
                                    QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                                    members);
                    return new VerifiedAuthority(
                            QualificationRunFinalizer.verifyPublished(
                                    lease, resultPath, catalog),
                            runCommit.commit());
                });
        if (!declared.equals(expected)) {
            throw new IOException(
                    "campaign completion differs from current committed run authority");
        }
        return declared;
    }

    static QualificationRecords.CampaignCompletion validateDesiredUnderLease(
            QualificationPacketPublisher.Lease lease,
            byte[] desiredBytes,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Objects.requireNonNull(lease, "qualification publication lease");
        Objects.requireNonNull(desiredBytes, "campaign completion bytes");
        CampaignSnapshot snapshot = canonicalCampaign(
                NoFollowPathPolicy.bindExistingDirectory(lease.packetRoot()),
                lease.packetRoot(),
                catalog);
        QualificationRecords.CampaignCompletion desired = QualificationJson.read(
                desiredBytes, QualificationRecords.CampaignCompletion.class);
        if (!Arrays.equals(desiredBytes, QualificationJson.bytes(desired))) {
            throw new IOException("campaign completion is not canonical JSON");
        }
        validateShape(desired, snapshot, catalog);
        QualificationRecords.CampaignCompletion expected = derive(
                lease.packetRoot(),
                lease.campaign(),
                desired.bank(),
                catalog,
                (resultPath, members) -> {
                    QualificationPacketPublisher.CommittedSet runCommit =
                            lease.requireCommittedSet(
                                    QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                                    members);
                    return new VerifiedAuthority(
                            QualificationRunFinalizer.verifyPublished(
                                    lease, resultPath, catalog),
                            runCommit.commit());
                });
        if (!desired.equals(expected)) {
            throw new IOException(
                    "campaign completion differs from current committed run authority");
        }
        return desired;
    }

    static QualificationRecords.CampaignCompletion requireCommitted(
            Path packetRoot,
            QualificationProtocol.BankKind bank,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(packetRoot);
        QualificationRecords.CampaignManifest campaign = canonicalCampaign(
                packetFiles, packetFiles.path(), catalog).campaign();
        Path output = packetFiles.path().resolve(OUTPUT_FILE);
        QualificationPacketPublisher.CommittedSet committed =
                QualificationPacketPublisher.requireCommitted(
                        packetFiles.path(),
                        QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                        List.of(output));
        QualificationRecords.CampaignCompletion declared = parseCommitted(
                packetFiles.path(), output, committed, campaign, catalog);
        QualificationRecords.CampaignCompletion expected = derive(
                packetFiles.path(), campaign, bank, catalog,
                (resultPath, members) -> {
                    QualificationPacketPublisher.CommittedSet runCommit =
                            QualificationPacketPublisher.requireCommitted(
                                    packetFiles.path(),
                                    QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                                    members);
                    return new VerifiedAuthority(
                            QualificationRunFinalizer.verifyPublished(
                                    packetFiles.path(), resultPath, catalog),
                            runCommit.commit());
                });
        if (!declared.equals(expected)) {
            throw new IOException(
                    "campaign completion differs from current committed run authority");
        }
        return declared;
    }

    static QualificationRecords.CampaignCompletion readCommitted(
            Path packetRoot,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(packetRoot);
        CampaignSnapshot campaign = canonicalCampaign(
                packetFiles, packetFiles.path(), catalog);
        Path output = packetFiles.path().resolve(OUTPUT_FILE);
        QualificationPacketPublisher.CommittedSet committed =
                QualificationPacketPublisher.requireCommitted(
                        packetFiles.path(),
                        QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                        List.of(output));
        return parseCommitted(
                packetFiles.path(), output, committed, campaign.campaign(), catalog);
    }

    private static QualificationRecords.CampaignCompletion derive(
            Path packetRoot,
            QualificationRecords.CampaignManifest leaseCampaign,
            QualificationProtocol.BankKind bank,
            QualificationScenarioBank.Catalog catalog,
            RunAuthorityReader runAuthority) throws IOException {
        Objects.requireNonNull(bank, "qualification bank");
        Objects.requireNonNull(catalog, "scenario catalog");
        if (catalog.protocolVersion() != QualificationProtocol.VERSION) {
            throw new IllegalArgumentException(
                    "campaign completion writes exactly protocol v"
                            + QualificationProtocol.VERSION);
        }
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(packetRoot);
        Path root = packetFiles.path();
        CampaignSnapshot campaignSnapshot = canonicalCampaign(packetFiles, root, catalog);
        QualificationRecords.CampaignManifest campaign = campaignSnapshot.campaign();
        if (!campaign.equals(leaseCampaign)) {
            throw new IOException("campaign identity changed under the publication lease");
        }

        int repetitions = bank == QualificationProtocol.BankKind.ADMISSION ? 1 : 3;
        if (campaign.repetitions().size() != repetitions
                || new HashSet<>(campaign.repetitions()).size() != repetitions
                || campaign.repetitions().stream().anyMatch(
                        value -> value == null || value.isBlank())) {
            throw new IOException(
                    "campaign repetition identities do not match the selected bank");
        }
        List<Coordinate> expected = expectedCoordinates(bank, catalog);
        Map<Coordinate, Integer> expectedOrder = new HashMap<>();
        for (int index = 0; index < expected.size(); index++) {
            expectedOrder.put(expected.get(index), index);
        }

        Path runsRoot = root.resolve("runs");
        if (packetFiles.inspect(runsRoot) != NoFollowPathPolicy.EntryType.DIRECTORY) {
            throw new IOException("campaign completion requires a runs directory");
        }
        List<NoFollowPathPolicy.TreeEntry> runTree = packetFiles.walk(runsRoot);
        List<Path> resultPaths = new ArrayList<>();
        for (Coordinate coordinate : expected) {
            Path resultPath = runsRoot
                    .resolve(campaign.candidateAlias())
                    .resolve(campaign.machineId())
                    .resolve(campaign.configId())
                    .resolve(coordinate.scenarioId())
                    .resolve("trial-%02d".formatted(coordinate.repetition()))
                    .resolve(QualificationRunFinalizer.RESULT_FILE);
            NoFollowPathPolicy.EntryType resultType = packetFiles.inspect(resultPath);
            if (resultType == NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                resultPaths.add(resultPath);
            } else if (resultType != NoFollowPathPolicy.EntryType.MISSING) {
                throw new IOException("campaign result path is not a regular file");
            }
        }
        if (resultPaths.isEmpty()) {
            throw new IOException("campaign completion requires committed run results");
        }

        Map<Coordinate, VerifiedRun> actual = new HashMap<>();
        for (Path resultPath : resultPaths) {
            Path runRoot = resultPath.getParent();
            Path scenarioSegment = runRoot.getName(runRoot.getNameCount() - 2);
            String scenarioId = scenarioSegment.toString();
            QualificationArtifactPublication.DeclaredRun declared =
                    QualificationArtifactPublication.requireWritableDeclaredRun(
                            root, runRoot, scenarioId, catalog);
            Coordinate coordinate = new Coordinate(scenarioId, declared.trialNumber());
            if (!expectedOrder.containsKey(coordinate)) {
                throw new IOException(
                        "campaign contains a run outside the selected bank: " + coordinate);
            }
            List<Path> members = List.of(
                    runRoot.resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                    runRoot.resolve(QualificationRunFinalizer.CANARY_FILE),
                    resultPath);
            VerifiedAuthority authority = runAuthority.read(resultPath, members);
            QualificationRecords.RunResult result = authority.publication().result();
            if (!result.scenarioId().equals(scenarioId)
                    || result.repetition() != declared.trialNumber()
                    || !result.repetitionId().equals(declared.repetitionId())) {
                throw new IOException(
                        "committed run identity disagrees with its declared coordinate");
            }
            boolean matchedModelAnswer = authority.publication().causal().answerOrigin()
                    == QualificationRecords.AnswerOrigin.MODEL_PROVIDER;
            QualificationCampaignContinuationPolicy.Decision continuation =
                    QualificationCampaignContinuationPolicy.evaluate(
                            result, matchedModelAnswer);
            QualificationPtyEvidence.Capture capture = QualificationPtyEvidence.parse(
                    runRoot.resolve("transcript.raw.json"),
                    QualificationProtocol.VERSION);
            VerifiedRun verified = new VerifiedRun(
                    coordinate,
                    declared.repetitionId(),
                    runRoot,
                    authority.publication(),
                    authority.commit(),
                    capture.startedAt(),
                    capture.endedAt(),
                    continuation);
            if (actual.putIfAbsent(coordinate, verified) != null) {
                throw new IOException("campaign contains a duplicate run coordinate");
            }
        }

        List<VerifiedRun> ordered = actual.values().stream()
                .sorted(java.util.Comparator.comparingInt(
                        run -> expectedOrder.get(run.coordinate())))
                .toList();
        LifecycleDecision lifecycle = evaluateLifecycle(expected, ordered);
        Set<Path> verifiedRoots = ordered.stream()
                .map(VerifiedRun::runRoot)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (NoFollowPathPolicy.TreeEntry entry : runTree) {
            if (entry.type() != NoFollowPathPolicy.EntryType.REGULAR_FILE) continue;
            boolean owned = verifiedRoots.stream().anyMatch(entry.path()::startsWith);
            if (!owned) {
                throw new IOException(
                        "campaign contains unfinalized run evidence: " + entry.path());
            }
        }

        VerifiedRun last = ordered.getLast();
        Instant started = parseCanonicalInstant(campaign.startedAt(), "campaign startedAt");
        if (ordered.getFirst().captureStartedAt().isBefore(started)) {
            throw new IOException("campaign capture starts before campaign start");
        }
        if (last.captureEndedAt().isBefore(started)) {
            throw new IOException("campaign completion precedes campaign start");
        }

        List<QualificationRecords.CampaignRunCommitRef> refs = ordered.stream()
                .map(run -> new QualificationRecords.CampaignRunCommitRef(
                        run.coordinate().scenarioId(),
                        run.coordinate().repetition(),
                        run.repetitionId(),
                        run.commit().transactionId(),
                        run.commit().setSha256()))
                .toList();
        String runSetSha256 = QualificationScenarioBank.sha256(
                QualificationJson.bytes(refs));
        QualificationRecords.CampaignCompletion completion =
                new QualificationRecords.CampaignCompletion(
                        QualificationRecords.CAMPAIGN_COMPLETION_SCHEMA,
                        QualificationRecords.CAMPAIGN_COMPLETION_SCHEMA_VERSION,
                        QualificationProtocol.VERSION,
                        campaign.campaignId(),
                        campaign.scenarioBankSha256(),
                        bank,
                        campaignSnapshot.sha256(),
                        last.captureEndedAt().toString(),
                        lifecycle.reason(),
                        expected.size(),
                        refs.size(),
                        runSetSha256,
                        refs,
                        lifecycle.stopReasons());
        validateShape(completion, campaignSnapshot, catalog);
        return completion;
    }

    static List<Coordinate> expectedCoordinates(
            QualificationProtocol.BankKind bank,
            QualificationScenarioBank.Catalog catalog) {
        int repetitions = bank == QualificationProtocol.BankKind.ADMISSION ? 1 : 3;
        List<Coordinate> expected = new ArrayList<>();
        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (QualificationProtocol.Scenario scenario : catalog.bank(bank).scenarios()) {
                expected.add(new Coordinate(scenario.id(), repetition));
            }
        }
        return List.copyOf(expected);
    }

    static LifecycleDecision evaluateLifecycle(
            QualificationProtocol.BankKind bank,
            QualificationScenarioBank.Catalog catalog,
            List<LifecycleFact> facts) throws IOException {
        List<Coordinate> expected = expectedCoordinates(bank, catalog);
        Map<Coordinate, Integer> order = new HashMap<>();
        for (int index = 0; index < expected.size(); index++) {
            order.put(expected.get(index), index);
        }
        Set<Coordinate> identities = new HashSet<>();
        List<LifecycleFact> ordered = facts.stream()
                .map(fact -> Objects.requireNonNull(fact, "campaign lifecycle fact"))
                .peek(fact -> {
                    if (!order.containsKey(fact.coordinate())
                            || !identities.add(fact.coordinate())) {
                        throw new IllegalArgumentException(
                                "campaign lifecycle fact identity is invalid");
                    }
                })
                .sorted(java.util.Comparator.comparingInt(
                        fact -> order.get(fact.coordinate())))
                .toList();
        return evaluateLifecycle(expected, ordered);
    }

    private static LifecycleDecision evaluateLifecycle(
            List<Coordinate> expected,
            List<? extends LifecycleEvidence> ordered) throws IOException {
        if (ordered.isEmpty()) {
            throw new IOException("campaign completion requires committed run results");
        }
        for (int index = 0; index < ordered.size(); index++) {
            LifecycleEvidence current = ordered.get(index);
            if (index >= expected.size()
                    || !current.coordinate().equals(expected.get(index))) {
                throw new IOException(
                        "campaign committed runs are not an exact bank-order prefix");
            }
            if (current.captureEndedAt().isBefore(current.captureStartedAt())) {
                throw new IOException("campaign capture ends before it starts");
            }
            if (index + 1 < ordered.size() && current.continuation().stop()) {
                throw new IOException(
                        "campaign continued after a mechanically required hard stop at "
                                + current.coordinate() + ": "
                                + current.continuation().reasons());
            }
            if (index > 0 && current.captureStartedAt()
                    .isBefore(ordered.get(index - 1).captureEndedAt())) {
                throw new IOException(
                        "campaign capture chronology disagrees with declared run order");
            }
        }
        LifecycleEvidence last = ordered.getLast();
        if (last.continuation().stop()) {
            return new LifecycleDecision(
                    QualificationRecords.CampaignCompletionReason.HARD_STOP,
                    last.continuation().reasons());
        }
        if (ordered.size() == expected.size()) {
            return new LifecycleDecision(
                    QualificationRecords.CampaignCompletionReason.COMPLETE_GRID,
                    List.of());
        }
        throw new IOException(
                "campaign run grid is incomplete and has no mechanical hard stop");
    }

    private static QualificationRecords.CampaignCompletion parseCommitted(
            Path packetRoot,
            Path output,
            QualificationPacketPublisher.CommittedSet committed,
            QualificationRecords.CampaignManifest campaign,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        byte[] bytes = committed.bytes(packetRoot, output);
        QualificationRecords.CampaignCompletion completion = QualificationJson.read(
                bytes, QualificationRecords.CampaignCompletion.class);
        if (!Arrays.equals(bytes, QualificationJson.bytes(completion))) {
            throw new IOException("campaign completion is not canonical JSON");
        }
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(packetRoot);
        CampaignSnapshot snapshot = canonicalCampaign(packetFiles, packetFiles.path(), catalog);
        if (!snapshot.campaign().equals(campaign)) {
            throw new IOException("campaign completion campaign identity changed");
        }
        validateShape(completion, snapshot, catalog);
        return completion;
    }

    private static void validateShape(
            QualificationRecords.CampaignCompletion completion,
            CampaignSnapshot campaign,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        if (!QualificationRecords.CAMPAIGN_COMPLETION_SCHEMA.equals(completion.schemaName())
                || completion.schemaVersion()
                        != QualificationRecords.CAMPAIGN_COMPLETION_SCHEMA_VERSION
                || completion.protocolVersion() != QualificationProtocol.VERSION) {
            throw new IOException("campaign completion schema is invalid");
        }
        if (!completion.campaignId().equals(campaign.campaign().campaignId())
                || !completion.scenarioBankSha256()
                        .equals(campaign.campaign().scenarioBankSha256())
                || !completion.scenarioBankSha256().equals(catalog.sha256())
                || !completion.campaignManifestSha256().equals(campaign.sha256())) {
            throw new IOException("campaign completion identity is invalid");
        }
        int repetitions = completion.bank() == QualificationProtocol.BankKind.ADMISSION ? 1 : 3;
        int expected = catalog.bank(completion.bank()).scenarios().size() * repetitions;
        if (completion.expectedTrialCount() != expected
                || completion.committedRunCount() != completion.committedRuns().size()
                || completion.committedRunCount() < 1
                || completion.committedRunCount() > expected) {
            throw new IOException("campaign completion trial counts are invalid");
        }
        requireHash(completion.committedRunSetSha256(), "completion run-set SHA-256");
        if (!completion.committedRunSetSha256().equals(
                QualificationScenarioBank.sha256(
                        QualificationJson.bytes(completion.committedRuns())))) {
            throw new IOException("campaign completion run-set hash is invalid");
        }
        Set<Coordinate> identities = new HashSet<>();
        for (QualificationRecords.CampaignRunCommitRef ref : completion.committedRuns()) {
            if (ref.scenarioId() == null || ref.scenarioId().isBlank()
                    || ref.repetition() < 1
                    || ref.repetitionId() == null || ref.repetitionId().isBlank()
                    || !identities.add(new Coordinate(ref.scenarioId(), ref.repetition()))) {
                throw new IOException("campaign completion run identity is invalid");
            }
            requireHash(ref.transactionId(), "run transaction id");
            requireHash(ref.setSha256(), "run set SHA-256");
        }
        Instant ended = parseCanonicalInstant(completion.endedAt(), "completion endedAt");
        Instant started = parseCanonicalInstant(campaign.campaign().startedAt(), "campaign startedAt");
        if (ended.isBefore(started)) {
            throw new IOException("campaign completion precedes campaign start");
        }
        if (completion.completionReason()
                == QualificationRecords.CampaignCompletionReason.COMPLETE_GRID) {
            if (completion.committedRunCount() != expected
                    || !completion.stopReasons().isEmpty()) {
                throw new IOException("complete-grid campaign completion is inconsistent");
            }
        } else if (completion.completionReason()
                == QualificationRecords.CampaignCompletionReason.HARD_STOP) {
            if (completion.stopReasons().isEmpty()
                    || completion.stopReasons().stream().anyMatch(
                            reason -> reason == null || reason.isBlank())) {
                throw new IOException("hard-stop campaign completion lacks exact reasons");
            }
        } else {
            throw new IOException("campaign completion reason is unavailable");
        }
    }

    private record CampaignSnapshot(
            QualificationRecords.CampaignManifest campaign,
            byte[] bytes,
            String sha256) {
        CampaignSnapshot {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static CampaignSnapshot canonicalCampaign(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Path campaignPath = packetRoot.resolve("campaign.json");
        AtomicFileWriter.ReadSnapshot snapshot =
                new AtomicFileWriter().readSnapshot(campaignPath);
        QualificationRecords.CampaignManifest campaign = QualificationJson.read(
                snapshot.bytes(), QualificationRecords.CampaignManifest.class);
        if (!Arrays.equals(snapshot.bytes(), QualificationJson.bytes(campaign))) {
            throw new IOException("protocol-v6 campaign manifest is not canonical JSON");
        }
        if (!QualificationRecords.CAMPAIGN_SCHEMA.equals(campaign.schemaName())
                || campaign.schemaVersion() != QualificationRecords.SCHEMA_VERSION
                || campaign.protocolVersion() != QualificationProtocol.VERSION
                || !campaign.scenarioBankSha256().equals(catalog.sha256())) {
            throw new IOException("protocol-v6 campaign identity is invalid");
        }
        if (campaign.endedAt() != null && !campaign.endedAt().isBlank()) {
            throw new IOException(
                    "protocol-v6 campaign.json must remain immutable; completion is separate");
        }
        packetFiles.requireRegularFile(campaignPath);
        return new CampaignSnapshot(
                campaign, snapshot.bytes(), snapshot.preimage().sha256());
    }

    private static Instant parseCanonicalInstant(String value, String label) throws IOException {
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value)) {
                throw new IOException(label + " must be canonical UTC");
            }
            return parsed;
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw new IOException(label + " is invalid", invalid);
        }
    }

    private static void requireHash(String value, String label) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IOException(label + " must be lowercase SHA-256");
        }
    }
}
