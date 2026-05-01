package dev.talos.runtime.capability;

import dev.talos.runtime.task.TaskContract;

import java.nio.file.Path;
import java.util.Set;

public final class CapabilityProfileRegistry {
    private CapabilityProfileRegistry() {}

    public static CapabilityProfile select(TaskContract contract) {
        return select(contract, null, Set.of());
    }

    public static CapabilityProfile select(TaskContract contract, Path workspace, Set<String> mutatedPaths) {
        CapabilityProfile staticWeb = StaticWebCapabilityProfile.select(contract, workspace, mutatedPaths);
        if (staticWeb.staticWeb()) return staticWeb;
        return CapabilityProfile.none();
    }
}
