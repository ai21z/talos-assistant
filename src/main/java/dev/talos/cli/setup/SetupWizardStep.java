package dev.talos.cli.setup;

public record SetupWizardStep(String id, Action action, String title, String detail) {
    public enum Action {
        SKIP,
        ASK,
        REUSE_OR_ASK,
        BLOCK_OR_ASK
    }
}
