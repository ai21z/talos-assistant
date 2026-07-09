package dev.talos.cli.setup;

import java.nio.file.Path;

/**
 * Read-only environment facts captured before setup decisions.
 *
 * <p>GPU fields are evidence, not assumptions: {@code gpuDriverVersion} is
 * empty unless an NVIDIA driver was actually detected, and lane selection
 * treats "no driver evidence" as CPU (T986).
 */
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
        long maxMemoryMb,
        long systemMemoryMb,
        String gpuName,
        long gpuVramTotalMb,
        String gpuDriverVersion) {

    public SetupWizardSnapshot {
        gpuName = gpuName == null ? "" : gpuName.strip();
        gpuDriverVersion = gpuDriverVersion == null ? "" : gpuDriverVersion.strip();
    }

    /** Pre-T986 shape: no GPU evidence captured. */
    public SetupWizardSnapshot(
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
            long maxMemoryMb,
            long systemMemoryMb) {
        this(osName, osArch, wsl, distroName, javaFeature, configPath, configExists,
                llamaServerPath, llamaServerExists, usableDiskMb, maxMemoryMb, systemMemoryMb,
                "", -1L, "");
    }

    public boolean gpuDetected() {
        return !gpuDriverVersion.isBlank();
    }
}
