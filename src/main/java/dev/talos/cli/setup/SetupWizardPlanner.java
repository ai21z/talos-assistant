package dev.talos.cli.setup;

import dev.talos.engine.llamacpp.LlamaCppModelProfiles;

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
                "No config found at " + path + "; a later interactive milestone must ask before writing it.");
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
        return new SetupWizardStep(
                "llama-server",
                SetupWizardStep.Action.ASK,
                "llama.cpp server",
                "No compatible llama-server detected; ask whether to provide a path, install from a future pinned Talos manifest, or skip model setup.");
    }

    private static SetupWizardStep modelProfileStep() {
        StringBuilder detail = new StringBuilder("Accepted beta profiles: ");
        boolean first = true;
        for (var profile : LlamaCppModelProfiles.profiles().values()) {
            if (profile.supportTier() != LlamaCppModelProfiles.SupportTier.ACCEPTED_BETA) {
                continue;
            }
            if (!first) {
                detail.append("; ");
            }
            first = false;
            detail.append(profile.alias())
                    .append(" (")
                    .append(profile.hfRepo())
                    .append(" / ")
                    .append(profile.hfFile())
                    .append(")");
        }
        detail.append(". A later interactive milestone must show size/RAM guidance and ask before any model download.");
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
