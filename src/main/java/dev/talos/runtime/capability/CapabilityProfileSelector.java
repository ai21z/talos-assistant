package dev.talos.runtime.capability;

import dev.talos.runtime.task.TaskContract;

import java.nio.file.Path;
import java.util.Set;

@FunctionalInterface
interface CapabilityProfileSelector {
    CapabilityProfile select(TaskContract contract, Path workspace, Set<String> mutatedPaths);
}
