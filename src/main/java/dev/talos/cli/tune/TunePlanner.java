package dev.talos.cli.tune;

import dev.talos.cli.setup.LlamaCppEngineManifest;
import dev.talos.cli.setup.SetupWizardSnapshot;
import dev.talos.engine.llamacpp.ManagedContextSelector;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

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

    public static Optional<Proposal> plan(Facts facts, Path userHome, Predicate<Path> fileExists) {
        SetupWizardSnapshot snapshot = new SetupWizardSnapshot(
                facts.osName(),
                facts.osArch(),
                facts.wsl(),
                "",
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
        Path serverPath = lane.installDir(userHome).resolve(lane.executableName());
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
                fileExists.test(serverPath),
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
