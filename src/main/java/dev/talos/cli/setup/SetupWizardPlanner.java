package dev.talos.cli.setup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

public final class SetupWizardPlanner {
    private SetupWizardPlanner() {}

    public static SetupWizardPlan plan(SetupWizardSnapshot snapshot) {
        ArrayList<SetupWizardStep> steps = new ArrayList<>();
        steps.add(javaStep(snapshot));
        steps.add(configStep(snapshot));
        steps.add(llamaServerStep(snapshot));
        steps.add(modelProfileStep());
        steps.add(new SetupWizardStep(
                "verification",
                SetupWizardStep.Action.ASK,
                "Doctor verification",
                "After explicit setup choices are applied in a later milestone, run `talos doctor --start` and report the exact pass, skip, or failure reason."));
        return new SetupWizardPlan(snapshot, steps);
    }

    private static SetupWizardStep javaStep(SetupWizardSnapshot snapshot) {
        if (snapshot.javaFeature() >= 21) {
            return new SetupWizardStep(
                    "java",
                    SetupWizardStep.Action.SKIP,
                    "Java runtime",
                    "Java " + snapshot.javaFeature() + " detected; skip Java installation.");
        }
        return new SetupWizardStep(
                "java",
                SetupWizardStep.Action.BLOCK_OR_ASK,
                "Java runtime",
                "Java 21+ was not detected; source/developer Linux lanes must install Java before Talos can run.");
    }

    private static SetupWizardStep configStep(SetupWizardSnapshot snapshot) {
        Path config = snapshot.configPath();
        String path = config == null ? "~/.talos/config.yaml" : config.toString();
        if (snapshot.configExists()) {
            return new SetupWizardStep(
                    "config",
                    SetupWizardStep.Action.REUSE_OR_ASK,
                    "Talos config",
                    "Existing config detected at " + path + "; reuse it unless the user explicitly chooses replacement with backup.");
        }
        return new SetupWizardStep(
                "config",
                SetupWizardStep.Action.ASK,
                "Talos config",
                "No config found at " + path + "; the interactive wizard must ask before writing it.");
    }

    private static SetupWizardStep llamaServerStep(SetupWizardSnapshot snapshot) {
        Path server = snapshot.llamaServerPath();
        if (server != null && snapshot.wsl() && isWindowsExecutable(server)) {
            return new SetupWizardStep(
                    "llama-server",
                    SetupWizardStep.Action.BLOCK_OR_ASK,
                    "llama.cpp server",
                    "Windows .exe visible from WSL is not a Linux-compatible llama-server: " + server
                            + ". Choose a Linux-compatible binary, pinned Talos manifest, or skip model setup.");
        }
        if (server != null && snapshot.llamaServerExists()) {
            return new SetupWizardStep(
                    "llama-server",
                    SetupWizardStep.Action.REUSE_OR_ASK,
                    "llama.cpp server",
                    "Linux-compatible llama-server detected at " + server + "; ask whether to use this path.");
        }
        var manifest = LlamaCppEngineManifest.select(snapshot);
        if (manifest.isPresent()) {
            var entry = manifest.get();
            StringBuilder detail = new StringBuilder(
                    "No compatible llama-server detected; ask whether to install pinned "
                            + entry.variant() + " llama.cpp " + entry.upstreamTag()
                            + " from " + entry.assetName()
                            + " (SHA-256 " + entry.sha256() + ")");
            if (entry.companion() != null) {
                detail.append(" plus driver runtime ")
                        .append(entry.companion().assetName())
                        .append(" (SHA-256 ")
                        .append(entry.companion().sha256())
                        .append(")");
            }
            if (!entry.minNvidiaDriver().isBlank()) {
                detail.append("; requires NVIDIA driver >= ")
                        .append(entry.minNvidiaDriver())
                        .append(", detected ")
                        .append(snapshot.gpuDriverVersion().isBlank() ? "none" : snapshot.gpuDriverVersion());
            }
            detail.append(", provide a path, or skip model setup.");
            return new SetupWizardStep(
                    "llama-server",
                    SetupWizardStep.Action.ASK,
                    "llama.cpp server",
                    detail.toString());
        }
        return new SetupWizardStep(
                "llama-server",
                SetupWizardStep.Action.ASK,
                "llama.cpp server",
                "No compatible llama-server detected; ask whether to provide a path or skip model setup.");
    }

    private static SetupWizardStep modelProfileStep() {
        StringBuilder detail = new StringBuilder("Accepted beta profiles: ");
        boolean first = true;
        for (var model : LlamaCppModelManifest.acceptedBeta()) {
            if (!first) {
                detail.append("; ");
            }
            first = false;
            detail.append(model.alias())
                    .append(" (")
                    .append(model.hfRepo())
                    .append(" / ")
                    .append(model.hfFile())
                    .append("; ")
                    .append(model.guidanceLine())
                    .append("; cache: ~/.talos/models/gguf; support: ")
                    .append(model.supportLevel())
                    .append(")");
        }
        detail.append(". The interactive wizard must ask before any model download.");
        return new SetupWizardStep(
                "model-profile",
                SetupWizardStep.Action.ASK,
                "Model profile",
                detail.toString());
    }

    private static boolean isWindowsExecutable(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".exe");
    }
}
