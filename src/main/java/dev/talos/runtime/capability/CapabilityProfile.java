package dev.talos.runtime.capability;

public record CapabilityProfile(
        String id,
        ArtifactKind artifactKind,
        ArtifactOperation operation,
        TargetSurface targetSurface,
        VerifierProfile verifierProfile,
        RepairProfile repairProfile
) {
    private static final CapabilityProfile NONE = new CapabilityProfile(
            "none",
            ArtifactKind.GENERIC_FILE,
            ArtifactOperation.NONE,
            TargetSurface.NONE,
            VerifierProfile.NONE,
            RepairProfile.NONE);

    public static CapabilityProfile none() {
        return NONE;
    }

    public static CapabilityProfile staticWeb(ArtifactOperation operation, TargetSurface targetSurface) {
        return new CapabilityProfile(
                StaticWebCapabilityProfile.ID,
                ArtifactKind.STATIC_WEB,
                operation == null ? ArtifactOperation.NONE : operation,
                targetSurface == null ? TargetSurface.FUNCTIONAL_WEB : targetSurface,
                VerifierProfile.STATIC_WEB,
                RepairProfile.STATIC_WEB);
    }

    public boolean staticWeb() {
        return artifactKind == ArtifactKind.STATIC_WEB;
    }
}
