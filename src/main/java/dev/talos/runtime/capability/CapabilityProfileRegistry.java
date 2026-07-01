package dev.talos.runtime.capability;

import dev.talos.runtime.task.TaskContract;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class CapabilityProfileRegistry {
    private static final List<CapabilityProfileSelector> SELECTORS = List.of(
            StaticWebCapabilityProfile::select,
            SourceDerivedCapabilityProfile::select,
            DocumentExtractionCapabilityProfile::select);

    private CapabilityProfileRegistry() {}

    public static CapabilityProfile select(TaskContract contract) {
        return select(contract, null, Set.of());
    }

    public static CapabilityProfile select(TaskContract contract, Path workspace, Set<String> mutatedPaths) {
        for (CapabilityProfileSelector selector : SELECTORS) {
            CapabilityProfile profile = selector.select(contract, workspace, mutatedPaths);
            if (profile != null && profile != CapabilityProfile.none()) return profile;
        }
        return CapabilityProfile.none();
    }
}
