package dev.talos.runtime.capability;

import dev.talos.core.capability.CapabilityKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityResolutionTest {

    @Test
    void noneResolutionProvidesStableEmptyDefaults() {
        CapabilityResolution resolution = CapabilityResolution.none();

        assertEquals(CapabilityKind.INSPECT, resolution.capabilityKind());
        assertEquals(ArtifactKind.GENERIC_FILE, resolution.artifactKind());
        assertEquals(ArtifactOperation.NONE, resolution.operation());
        assertEquals(List.of(), resolution.expectedTargetPaths());
        assertEquals(List.of(), resolution.protectedTargetPaths());
        assertEquals(Set.of(), resolution.allowedTools());
        assertEquals(Set.of(), resolution.blockedTools());
        assertEquals(CapabilityResolution.EvidenceRequirement.NONE, resolution.evidenceRequirement());
        assertEquals(VerifierProfile.NONE, resolution.verifierProfile());
        assertEquals(CapabilityResolution.ApprovalMode.AUTO, resolution.approvalMode());
        assertEquals(CapabilityResolution.CheckpointMode.NONE, resolution.checkpointMode());
        assertEquals(CapabilityResolution.OutputDominanceRule.NORMAL, resolution.outputDominanceRule());
    }

    @Test
    void resolutionDefensivelyCopiesCollections() {
        var expectedTargets = new java.util.ArrayList<>(List.of("index.html"));
        var protectedTargets = new java.util.ArrayList<>(List.of(".env"));
        var allowedTools = new java.util.LinkedHashSet<>(Set.of("talos.read_file"));
        var blockedTools = new java.util.LinkedHashSet<>(Set.of("talos.write_file"));

        CapabilityResolution resolution = new CapabilityResolution(
                CapabilityKind.INSPECT,
                ArtifactKind.STATIC_WEB,
                ArtifactOperation.READ_ONLY,
                expectedTargets,
                protectedTargets,
                allowedTools,
                blockedTools,
                CapabilityResolution.EvidenceRequirement.READ_TARGET_REQUIRED,
                VerifierProfile.STATIC_WEB,
                CapabilityResolution.ApprovalMode.ASK,
                CapabilityResolution.CheckpointMode.BUNDLE,
                CapabilityResolution.OutputDominanceRule.PRIVACY_DOMINANT);

        expectedTargets.add("styles.css");
        protectedTargets.add("secret.txt");
        allowedTools.add("talos.grep");
        blockedTools.add("talos.edit_file");

        assertEquals(List.of("index.html"), resolution.expectedTargetPaths());
        assertEquals(List.of(".env"), resolution.protectedTargetPaths());
        assertEquals(Set.of("talos.read_file"), resolution.allowedTools());
        assertEquals(Set.of("talos.write_file"), resolution.blockedTools());
        assertThrows(UnsupportedOperationException.class,
                () -> resolution.expectedTargetPaths().add("scripts.js"));
        assertThrows(UnsupportedOperationException.class,
                () -> resolution.allowedTools().add("talos.list_dir"));
    }
}
