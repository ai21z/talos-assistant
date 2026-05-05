package dev.talos.runtime.capability;

import dev.talos.core.capability.CapabilityKind;

import java.util.List;
import java.util.Set;

/**
 * Turn-level capability selection facts.
 *
 * <p>This record is the stable handoff shape between task/capability
 * resolution and later tool-surface, evidence, approval, checkpoint,
 * verification, and outcome policies.
 */
public record CapabilityResolution(
        CapabilityKind capabilityKind,
        ArtifactKind artifactKind,
        ArtifactOperation operation,
        List<String> expectedTargetPaths,
        List<String> protectedTargetPaths,
        Set<String> allowedTools,
        Set<String> blockedTools,
        EvidenceRequirement evidenceRequirement,
        VerifierProfile verifierProfile,
        ApprovalMode approvalMode,
        CheckpointMode checkpointMode,
        OutputDominanceRule outputDominanceRule
) {
    private static final CapabilityResolution NONE = new CapabilityResolution(
            CapabilityKind.INSPECT,
            ArtifactKind.GENERIC_FILE,
            ArtifactOperation.NONE,
            List.of(),
            List.of(),
            Set.of(),
            Set.of(),
            EvidenceRequirement.NONE,
            VerifierProfile.NONE,
            ApprovalMode.AUTO,
            CheckpointMode.NONE,
            OutputDominanceRule.NORMAL);

    public CapabilityResolution {
        capabilityKind = capabilityKind == null ? CapabilityKind.INSPECT : capabilityKind;
        artifactKind = artifactKind == null ? ArtifactKind.GENERIC_FILE : artifactKind;
        operation = operation == null ? ArtifactOperation.NONE : operation;
        expectedTargetPaths = List.copyOf(expectedTargetPaths == null ? List.of() : expectedTargetPaths);
        protectedTargetPaths = List.copyOf(protectedTargetPaths == null ? List.of() : protectedTargetPaths);
        allowedTools = Set.copyOf(allowedTools == null ? Set.of() : allowedTools);
        blockedTools = Set.copyOf(blockedTools == null ? Set.of() : blockedTools);
        evidenceRequirement = evidenceRequirement == null ? EvidenceRequirement.NONE : evidenceRequirement;
        verifierProfile = verifierProfile == null ? VerifierProfile.NONE : verifierProfile;
        approvalMode = approvalMode == null ? ApprovalMode.AUTO : approvalMode;
        checkpointMode = checkpointMode == null ? CheckpointMode.NONE : checkpointMode;
        outputDominanceRule = outputDominanceRule == null ? OutputDominanceRule.NORMAL : outputDominanceRule;
    }

    public static CapabilityResolution none() {
        return NONE;
    }

    public enum EvidenceRequirement {
        NONE,
        LIST_DIRECTORY_ONLY,
        READ_TARGET_REQUIRED,
        STATIC_WEB_DIAGNOSIS,
        PROTECTED_READ_APPROVED,
        MUTATION_VERIFICATION_REQUIRED
    }

    public enum ApprovalMode {
        AUTO,
        ASK,
        DENY
    }

    public enum CheckpointMode {
        NONE,
        SINGLE_FILE,
        BUNDLE
    }

    public enum OutputDominanceRule {
        NORMAL,
        FAILURE_DOMINANT,
        PRIVACY_DOMINANT,
        PARTIAL_MUTATION_DOMINANT
    }
}
