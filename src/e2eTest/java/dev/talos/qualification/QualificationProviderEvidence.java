package dev.talos.qualification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class QualificationProviderEvidence {
    static final String NO_PROVIDER_PROMPT = "No provider prompt was sent for the last turn.";
    private static final String PROMPT_HEADER = "Prompt debug: NOT_APPLICABLE";
    private static final String MODEL_RUNTIME_HEADER = "Model runtime: NOT_APPLICABLE";

    private QualificationProviderEvidence() {}

    static void capture(Path runRoot, String commandOutput, int protocolVersion) throws IOException {
        QualificationArtifactPublication.requireWritableProtocol(protocolVersion);
        if (hasProviderRender(runRoot)) return;
        if (commandOutput == null || !commandOutput.contains(NO_PROVIDER_PROMPT)) {
            throw new IllegalStateException(
                    "prompt-debug produced neither a provider render nor verified no-provider output");
        }
        Path promptDebug = runRoot.resolve("prompt-debug.txt");
        Path modelLog = runRoot.resolve("llama.log");
        byte[] promptBytes = promptNotApplicableText().getBytes(StandardCharsets.UTF_8);
        byte[] modelBytes = modelRuntimeNotApplicableText().getBytes(StandardCharsets.UTF_8);
        // A published immutable member is never rolled back: another publisher may
        // already have observed it. T1192 owns atomic visibility of the complete pair.
        QualificationArtifactPublication.publishNewOrVerifyExact(
                promptDebug, promptBytes, "provider NOT_APPLICABLE prompt evidence");
        QualificationArtifactPublication.publishNewOrVerifyExact(
                modelLog, modelBytes, "provider NOT_APPLICABLE runtime evidence");
    }

    static String promptNotApplicableText() {
        return PROMPT_HEADER + "\n"
                + "Reason: " + NO_PROVIDER_PROMPT + "\n"
                + "Evidence source: structured PTY output from /prompt-debug save.\n";
    }

    static String modelRuntimeNotApplicableText() {
        return MODEL_RUNTIME_HEADER + "\n"
                + "Reason: " + NO_PROVIDER_PROMPT + "\n"
                + "No model timing, token rate, or offload claim applies to this turn.\n"
                + "Evidence source: structured PTY output from /prompt-debug save.\n";
    }

    static boolean isPromptNotApplicable(String evidence) {
        return evidence != null && evidence.startsWith(PROMPT_HEADER + "\n");
    }

    static boolean isModelRuntimeNotApplicable(String evidence) {
        return evidence != null && evidence.startsWith(MODEL_RUNTIME_HEADER + "\n");
    }

    private static boolean hasProviderRender(Path runRoot) throws IOException {
        try (var files = Files.list(runRoot)) {
            return files.anyMatch(path -> {
                String name = path.getFileName().toString();
                return Files.isRegularFile(path)
                        && name.startsWith("prompt-debug-")
                        && name.endsWith(".md");
            });
        }
    }
}
