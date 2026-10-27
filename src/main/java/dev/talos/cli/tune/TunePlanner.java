package dev.talos.cli.tune;

import dev.talos.cli.setup.LlamaCppEngineManifest;
import dev.talos.cli.setup.SetupWizardSnapshot;
import dev.talos.engine.llamacpp.ManagedContextSelector;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure planning stage of {@code talos tune} (T987).
 *
 * <p>Consumes the shared detection facts (T992 primitive), the T986 lane
 * manifest, and the T991 context selector. Performs no layer-placement or
 * offload arithmetic: llama.cpp's fit owns placement, so the proposal always
 * carries {@code server_args: []}. The only numeric model here is the
 * expected-speed line, which is estimate-labeled and pins its basis in code.
 */
public final class TunePlanner {

    /**
     * Bandwidth model pin (recorded in the T987 ticket): bytes-per-token is
     * the model GGUF file size (one full weight pass per generated token),
     * bandwidth is an asserted desktop-class 60 GB/s, efficiency 0.5. The
     * printed line always carries the word estimate. CUDA lanes print no
     * numeric estimate; verify measures the real rate from the server log.
     */
    static final double CPU_ASSERTED_BANDWIDTH_GBPS = 60.0;
    static final double CPU_BANDWIDTH_EFFICIENCY = 0.5;

    private TunePlanner() {}

    public record Facts(
            String osName,
            String osArch,
            boolean wsl,
            String distroName,
            String gpuName,
            String gpuDriverVersion,
            long gpuVramTotalMb,
            long systemMemoryMb,
            int cpuCores,
            String profile,
            long modelFileSizeBytes) {
    }

    public record Proposal(
            LlamaCppEngineManifest.Entry lane,
            Path serverPath,
            boolean laneInstalled,
            ManagedContextSelector.Decision context,
            String detectionSummary,
            String expectedSpeedLine) {
    }

    /**
     * Read-only lookup of an already-installed lane executable under its
     * install directory. Install layouts differ per platform: Windows zips
     * put the exe at the top, the Ubuntu tar nests it (build/bin), so the
     * real implementation searches recursively - the same walk the
     * installer itself uses to find what it extracted.
     */
    @FunctionalInterface
    public interface InstalledExecutableLocator {
        Optional<Path> locate(Path installDir, String executableName);
    }

    public static Optional<Proposal> plan(Facts facts, Path userHome, InstalledExecutableLocator locator) {
        SetupWizardSnapshot snapshot = new SetupWizardSnapshot(
                facts.osName(),
                facts.osArch(),
                facts.wsl(),
                facts.distroName(),
                21,
                null,
                false,
                null,
                false,
                -1,
                -1,
                facts.systemMemoryMb(),
                facts.gpuName(),
                facts.gpuVramTotalMb(),
                facts.gpuDriverVersion());
        Optional<LlamaCppEngineManifest.Entry> selected = LlamaCppEngineManifest.select(snapshot);
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        LlamaCppEngineManifest.Entry lane = selected.get();
        Path installDir = lane.installDir(userHome);
        Optional<Path> installed = locator.locate(installDir, lane.executableName());
        // Proposed path = the executable actually on disk when the lane is
        // installed (layouts nest per platform); the flat default is only a
        // display placeholder until the installer reports the real path.
        Path serverPath = installed.orElseGet(() -> installDir.resolve(lane.executableName()));
        boolean cuda = lane.backend().startsWith("cuda");
        ManagedContextSelector.Decision context = ManagedContextSelector.select(
                new ManagedContextSelector.Request(
                        facts.profile(),
                        cuda ? ManagedContextSelector.Lane.CUDA : ManagedContextSelector.Lane.CPU,
                        cuda ? facts.gpuVramTotalMb() : -1,
                        facts.systemMemoryMb()));
        return Optional.of(new Proposal(
                lane,
                serverPath,
                installed.isPresent(),
                context,
                detectionSummary(facts),
                expectedSpeedLine(facts, cuda)));
    }

    private static String detectionSummary(Facts facts) {
        StringBuilder out = new StringBuilder();
        if (facts.gpuDriverVersion() != null && !facts.gpuDriverVersion().isBlank()) {
            out.append("GPU: ").append(facts.gpuName())
                    .append(" (driver ").append(facts.gpuDriverVersion());
            if (facts.gpuVramTotalMb() > 0) {
                out.append(", VRAM ").append(facts.gpuVramTotalMb()).append(" MB");
            }
            out.append("; source nvidia-smi)");
        } else {
            out.append("GPU: no NVIDIA driver evidence (nvidia-smi query failed or absent); assuming CPU-only");
        }
        out.append("\nSystem RAM: ")
                .append(facts.systemMemoryMb() > 0 ? "~" + facts.systemMemoryMb() + " MB" : "unknown");
        out.append("\nCPU cores: ")
                .append(facts.cpuCores() > 0 ? String.valueOf(facts.cpuCores()) : "unknown");
        return out.toString();
    }

    private static String expectedSpeedLine(Facts facts, boolean cuda) {
        if (cuda) {
            return "GPU lane generation speed is measured at the verify step from the server log, not asserted.";
        }
        if (facts.modelFileSizeBytes() <= 0) {
            return "Expected CPU generation speed: unknown (no model file size basis; verify measures the real rate).";
        }
        double fileGb = facts.modelFileSizeBytes() / 1_000_000_000.0;
        double estimate = CPU_ASSERTED_BANDWIDTH_GBPS * CPU_BANDWIDTH_EFFICIENCY / fileGb;
        return String.format(Locale.ROOT,
                "Expected CPU generation speed: ~%.1f tok/s (estimate: asserted %.0f GB/s memory bandwidth, "
                        + "%.1f efficiency, model file %.2f GB read per token)",
                estimate,
                CPU_ASSERTED_BANDWIDTH_GBPS,
                CPU_BANDWIDTH_EFFICIENCY,
                fileGb);
    }
}
