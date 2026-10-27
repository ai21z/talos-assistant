package dev.talos.qualification;

import dev.talos.core.io.AtomicFileWriter;
import dev.talos.core.security.NoFollowPathPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed namespace and publication rules for protocol-v6 qualification artifacts.
 *
 * <p>This class deliberately does not claim crash-atomic or cross-process
 * qualification publication or multi-file transactions. T1192 owns the
 * qualification transaction and T1172 may supply its single-file primitive.
 * T1170's bound no-follow authority is enforced here before content I/O.
 */
final class QualificationArtifactPublication {
    private static final int PUBLICATION_LOCK_STRIPES = 64;
    private static final Object[] PUBLICATION_LOCKS = publicationLocks();
    private QualificationArtifactPublication() {}

    record DeclaredRun(
            Path packetRoot,
            Path runRoot,
            NoFollowPathPolicy.GuardedRoot packetFiles,
            NoFollowPathPolicy.GuardedRoot runFiles,
            QualificationRecords.CampaignManifest campaign,
            int trialNumber,
            String repetitionId) {}

    static QualificationRecords.CampaignManifest requireWritableCampaign(
            Path requestedPacketRoot,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Objects.requireNonNull(requestedPacketRoot, "packet root");
        Objects.requireNonNull(catalog, "scenario catalog");
        requireWritableProtocol(catalog.protocolVersion());

        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(requestedPacketRoot);
        Path packetRoot = packetFiles.path();
        Path campaignPath = packetRoot.resolve("campaign.json");
        QualificationRecords.CampaignManifest campaign = QualificationJson.read(
                packetFiles.readAllBytes(campaignPath),
                QualificationRecords.CampaignManifest.class);
        if (!QualificationRecords.CAMPAIGN_SCHEMA.equals(campaign.schemaName())
                || campaign.schemaVersion() != QualificationRecords.SCHEMA_VERSION) {
            throw invalid("campaign manifest schema is not canonical");
        }
        requireWritableProtocol(campaign.protocolVersion());
        if (!catalog.sha256().equals(campaign.scenarioBankSha256())) {
            throw invalid("campaign scenario-bank identity does not match the selected catalog");
        }
        QualificationScenarioBank.Catalog canonical = QualificationScenarioBank.loadDefault();
        if (!canonical.sha256().equals(catalog.sha256())) {
            throw invalid("artifact publication requires the canonical protocol-v6 catalog");
        }
        QualificationNamespace.requireSegment(campaign.campaignId(), "campaign id");
        QualificationNamespace.requireSegment(
                campaign.candidateAlias(), "campaign candidate alias");
        QualificationNamespace.requireSegment(campaign.machineId(), "campaign machine id");
        QualificationNamespace.requireSegment(campaign.engineLaneId(), "campaign engine lane id");
        QualificationNamespace.requireSegment(campaign.configId(), "campaign configuration id");
        requireRepetitions(campaign.repetitions());
        return campaign;
    }

    static DeclaredRun requireWritableDeclaredRun(
            Path requestedPacketRoot,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Objects.requireNonNull(requestedRunRoot, "run root");
        String safeScenario = QualificationNamespace.requireSegment(scenarioId, "scenario id");
        catalog.scenario(safeScenario);
        QualificationRecords.CampaignManifest campaign =
                requireWritableCampaign(requestedPacketRoot, catalog);

        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(requestedPacketRoot);
        Path packetRoot = packetFiles.path();
        Path runRoot = requestedRunRoot.toAbsolutePath().normalize();
        NoFollowPathPolicy.GuardedRoot runFiles =
                NoFollowPathPolicy.bindExistingDirectory(runRoot);
        Path runsRoot = packetFiles.requireDirectory(packetRoot.resolve("runs"));
        String candidateAlias = QualificationNamespace.requireSegment(
                campaign.candidateAlias(), "campaign candidate alias");
        String machineId = QualificationNamespace.requireSegment(
                campaign.machineId(), "campaign machine id");
        String configId = QualificationNamespace.requireSegment(
                campaign.configId(), "campaign configuration id");
        Path declaredBase = runsRoot
                .resolve(candidateAlias)
                .resolve(machineId)
                .resolve(configId)
                .resolve(safeScenario);
        List<String> repetitions = campaign.repetitions();
        for (int index = 0; index < repetitions.size(); index++) {
            Path declared = declaredBase.resolve("trial-%02d".formatted(index + 1));
            if (packetFiles.inspect(declared) == NoFollowPathPolicy.EntryType.DIRECTORY
                    && declared.equals(runRoot)
                    && Files.isSameFile(declared, runFiles.path())) {
                return new DeclaredRun(
                        packetRoot,
                        runRoot,
                        packetFiles,
                        runFiles,
                        campaign,
                        index + 1,
                        repetitions.get(index));
            }
        }
        throw invalid("run root is not an exact trial declared by the campaign: " + runRoot);
    }

    static void requireWritableProtocol(int protocolVersion) {
        if (protocolVersion != QualificationProtocol.VERSION) {
            throw invalid("qualification artifact publication writes exactly protocol v"
                    + QualificationProtocol.VERSION + "; protocol v" + protocolVersion
                    + " is read-only or unsupported");
        }
    }

    /**
     * Publishes bytes once, or accepts an exact existing/racing winner without overwriting it.
     * Returns {@code true} only when this invocation created the target.
     */
    static boolean publishNewOrVerifyExact(
            NoFollowPathPolicy.GuardedRoot owner,
            Path requestedTarget,
            byte[] canonicalBytes,
            String label
    )
            throws IOException {
        Objects.requireNonNull(owner, "publication owner");
        Objects.requireNonNull(requestedTarget, "publication target");
        Objects.requireNonNull(canonicalBytes, "canonical bytes");
        String safeLabel = label == null || label.isBlank() ? "qualification artifact" : label;
        Path target = requestedTarget.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw invalid(safeLabel + " has no parent directory");
        // T1172 keeps this legacy leaf publication off the live destination until one-file
        // promotion. The monitor preserves its existing same-process exact-retry contract.
        // Protocol-v6 authority sets do not use this leaf: QualificationPacketPublisher owns
        // their packet lease, declared members, commit record, and crash recovery.
        synchronized (publicationLock(target)) {
            owner.createDirectories(parent);

            if (owner.inspect(target) != NoFollowPathPolicy.EntryType.MISSING) {
                verifyExactOrdinaryFile(owner, target, canonicalBytes, safeLabel);
                return false;
            }
            owner.requireRegularFileTarget(target);
            new AtomicFileWriter().replace(
                    target,
                    canonicalBytes,
                    AtomicFileWriter.Preimage.absent());
            verifyExactOrdinaryFile(owner, target, canonicalBytes, safeLabel);
            return true;
        }
    }

    private static void verifyExactOrdinaryFile(
            NoFollowPathPolicy.GuardedRoot owner,
            Path target,
            byte[] canonicalBytes,
            String label
    ) throws IOException {
        byte[] existing = owner.readAllBytes(target);
        if (!Arrays.equals(existing, canonicalBytes)) {
            throw invalid("conflicting published " + label);
        }
    }

    private static void requireRepetitions(List<String> repetitions) {
        if (repetitions == null || repetitions.isEmpty()) {
            throw invalid("campaign repetitions are missing");
        }
        Set<String> unique = new HashSet<>();
        for (String repetition : repetitions) {
            String value = QualificationNamespace.requireSegment(
                    repetition, "campaign repetition id");
            if (!unique.add(value)) {
                throw invalid("campaign repetition ids must be unique");
            }
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static Object publicationLock(Path target) {
        int stripe = Math.floorMod(target.hashCode(), PUBLICATION_LOCK_STRIPES);
        return PUBLICATION_LOCKS[stripe];
    }

    private static Object[] publicationLocks() {
        Object[] locks = new Object[PUBLICATION_LOCK_STRIPES];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }
}
