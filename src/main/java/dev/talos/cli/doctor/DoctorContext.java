package dev.talos.cli.doctor;

import dev.talos.core.Config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Inputs shared by all doctor probes. {@code talosHome} is injectable so
 * probe tests never touch the developer's real {@code ~/.talos}.
 */
public record DoctorContext(Config cfg, Path workspace, Path talosHome) {

    public DoctorContext {
        Objects.requireNonNull(cfg, "cfg is required");
        workspace = (workspace == null ? Path.of(".") : workspace).toAbsolutePath().normalize();
        talosHome = (talosHome == null ? defaultTalosHome() : talosHome).toAbsolutePath().normalize();
    }

    public static DoctorContext of(Config cfg, Path workspace) {
        return new DoctorContext(cfg, workspace, defaultTalosHome());
    }

    /** {@code ~/.talos}, with the same fallbacks the engine log dir uses. */
    public static Path defaultTalosHome() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("USERPROFILE");
        }
        Path base = home == null || home.isBlank()
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(home);
        return base.resolve(".talos");
    }
}
