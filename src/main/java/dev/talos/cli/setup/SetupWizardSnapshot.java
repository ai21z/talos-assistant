package dev.talos.cli.setup;

import java.nio.file.Path;

public record SetupWizardSnapshot(
        String osName,
        String osArch,
        boolean wsl,
        String distroName,
        int javaFeature,
        Path configPath,
        boolean configExists,
        Path llamaServerPath,
        boolean llamaServerExists,
        long usableDiskMb,
        long maxMemoryMb) {
}
