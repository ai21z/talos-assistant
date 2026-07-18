package dev.talos.qualification;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed namespace and publication rules for protocol-v6 qualification artifacts.
 *
 * <p>This class deliberately does not claim crash-atomic or cross-process
 * qualification publication, multi-file transactions, or linked-ancestor
 * containment. T1192 owns the qualification transaction; T1172 may supply its
 * single-file primitive, and T1170 owns containment.
 */
final class QualificationArtifactPublication {
    private static final int PUBLICATION_LOCK_STRIPES = 64;
    private static final Object[] PUBLICATION_LOCKS = publicationLocks();
    private QualificationArtifactPublication() {}

    record DeclaredRun(
            Path packetRoot,
            Path runRoot,
            QualificationRecords.CampaignManifest campaign,
            int trialNumber,
            String repetitionId) {}

    static QualificationRecords.CampaignManifest requireWritableCampaign(
            Path requestedPacketRoot,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Objects.requireNonNull(requestedPacketRoot, "packet root");
        Objects.requireNonNull(catalog, "scenario catalog");
        requireWritableProtocol(catalog.protocolVersion());

        Path packetRoot = requestedPacketRoot.toAbsolutePath().normalize().toRealPath();
        Path campaignPath = packetRoot.resolve("campaign.json");
        requireOrdinaryFile(campaignPath, "campaign manifest");
        QualificationRecords.CampaignManifest campaign = QualificationJson.read(
                campaignPath, QualificationRecords.CampaignManifest.class);
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

        Path packetRoot = requestedPacketRoot.toAbsolutePath().normalize().toRealPath();
        Path runRoot = requestedRunRoot.toAbsolutePath().normalize().toRealPath();
        Path runsRoot = packetRoot.resolve("runs").toRealPath();
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
            if (Files.isDirectory(declared, LinkOption.NOFOLLOW_LINKS)
                    && declared.toRealPath().equals(runRoot)) {
                return new DeclaredRun(
                        packetRoot,
                        runRoot,
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
    static boolean publishNewOrVerifyExact(Path requestedTarget, byte[] canonicalBytes, String label)
            throws IOException {
        Objects.requireNonNull(requestedTarget, "publication target");
        Objects.requireNonNull(canonicalBytes, "canonical bytes");
        String safeLabel = label == null || label.isBlank() ? "qualification artifact" : label;
        Path target = requestedTarget.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw invalid(safeLabel + " has no parent directory");
        // A CREATE_NEW target becomes visible before Files.write has closed it. Serialize
        // same-process publishers so an exact follower cannot inspect that transient state.
        // Cross-process qualification serialization and the multi-file transaction remain
        // T1192 scope; T1172 may supply the single-file atomic-promotion primitive.
        synchronized (publicationLock(target)) {
            Files.createDirectories(parent);

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                verifyExactOrdinaryFile(target, canonicalBytes, safeLabel);
                return false;
            }
            try {
                Files.write(target, canonicalBytes,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException raced) {
                verifyExactOrdinaryFile(target, canonicalBytes, safeLabel);
                return false;
            }
            verifyExactOrdinaryFile(target, canonicalBytes, safeLabel);
            return true;
        }
    }

    private static void verifyExactOrdinaryFile(
            Path target, byte[] canonicalBytes, String label) throws IOException {
        requireOrdinaryFile(target, label);
        byte[] existing = Files.readAllBytes(target);
        if (!Arrays.equals(existing, canonicalBytes)) {
            throw invalid("conflicting published " + label + ": " + target);
        }
    }

    private static void requireOrdinaryFile(Path path, String label) {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid(label + " is missing or is not an ordinary file: " + path);
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
