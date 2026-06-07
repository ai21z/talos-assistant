package dev.talos.runtime.context;

import dev.talos.runtime.task.TaskContract;

import java.nio.file.Path;

/** Inputs needed to load project memory for a turn. */
public record ProjectMemoryRequest(
        Path workspace,
        Path userHome,
        TaskContract taskContract
) {
    public ProjectMemoryRequest {
        userHome = userHome == null ? Path.of(System.getProperty("user.home", ".")) : userHome;
    }
}
