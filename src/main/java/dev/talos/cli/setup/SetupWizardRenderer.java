package dev.talos.cli.setup;

public final class SetupWizardRenderer {
    private SetupWizardRenderer() {}

    public static String render(SetupWizardPlan plan) {
        StringBuilder out = new StringBuilder();
        out.append("""
                Talos setup wizard dry run
                No changes will be made: no package installs, no model downloads, no config writes, no model starts.

                Detected environment:
                """);
        SetupWizardSnapshot snapshot = plan.snapshot();
        out.append("  OS: ").append(snapshot.osName()).append(" ").append(snapshot.osArch());
        if (snapshot.wsl()) {
            out.append(" (WSL)");
        }
        out.append("\n");
        if (snapshot.distroName() != null && !snapshot.distroName().isBlank()) {
            out.append("  Distro: ").append(snapshot.distroName()).append("\n");
        }
        out.append("  Java: ").append(snapshot.javaFeature() <= 0 ? "unknown" : snapshot.javaFeature()).append("\n");
        out.append("  Config: ").append(snapshot.configPath() == null ? "(unknown)" : snapshot.configPath())
                .append(snapshot.configExists() ? " (found)" : " (missing)")
                .append("\n");
        out.append("  llama-server: ");
        if (snapshot.llamaServerPath() == null) {
            out.append("not detected\n");
        } else {
            out.append(snapshot.llamaServerPath())
                    .append(snapshot.llamaServerExists() ? " (file found)" : " (not a file)")
                    .append("\n");
        }
        if (snapshot.usableDiskMb() >= 0) {
            out.append("  Usable disk: ~").append(snapshot.usableDiskMb()).append(" MB\n");
        }
        if (snapshot.maxMemoryMb() > 0) {
            out.append("  JVM max memory: ~").append(snapshot.maxMemoryMb()).append(" MB\n");
        }
        out.append("  System RAM: ");
        if (snapshot.systemMemoryMb() > 0) {
            out.append("~").append(snapshot.systemMemoryMb()).append(" MB\n");
        } else {
            out.append("unknown\n");
        }

        out.append("\nDecision plan:\n");
        for (SetupWizardStep step : plan.steps()) {
            out.append("  [").append(label(step.action())).append("] ")
                    .append(step.title()).append(" - ")
                    .append(step.detail())
                    .append("\n");
        }

        out.append("""

                Milestone boundary:
                  This dry run only renders the setup plan. Package-manager execution, pinned llama.cpp install,
                  model download, config write, and doctor start are not performed here.
                """);
        return out.toString();
    }

    private static String label(SetupWizardStep.Action action) {
        return switch (action) {
            case SKIP -> "skip";
            case ASK -> "ask";
            case REUSE_OR_ASK -> "reuse/ask";
            case BLOCK_OR_ASK -> "block/ask";
        };
    }
}
