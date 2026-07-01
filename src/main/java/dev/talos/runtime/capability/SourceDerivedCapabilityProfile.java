package dev.talos.runtime.capability;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public final class SourceDerivedCapabilityProfile {
    public static final String ID = "source-derived";

    private SourceDerivedCapabilityProfile() {}

    public static CapabilityProfile select(TaskContract contract, Path workspace, Set<String> mutatedPaths) {
        if (!isApplicable(contract)) return CapabilityProfile.none();
        return CapabilityProfile.sourceDerived(operationFor(contract));
    }

    public static boolean isApplicable(TaskContract contract) {
        if (contract == null) return false;
        if (contract.sourceEvidenceTargets().isEmpty() || contract.expectedTargets().isEmpty()) return false;
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        return request.toLowerCase(Locale.ROOT).contains("summariz");
    }

    private static ArtifactOperation operationFor(TaskContract contract) {
        if (contract == null) return ArtifactOperation.NONE;
        if (!contract.mutationRequested()) return ArtifactOperation.READ_ONLY;
        if (contract.type() == TaskType.FILE_CREATE) return ArtifactOperation.CREATE;
        return ArtifactOperation.EDIT;
    }
}
