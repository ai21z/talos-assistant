package dev.talos.qualification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class QualificationProviderEvidence {
    static final String NO_PROVIDER_PROMPT = "No provider prompt was sent for the last turn.";
    private static final String PROMPT_HEADER = "Prompt debug: NOT_APPLICABLE";
    private static final String MODEL_RUNTIME_HEADER = "Model runtime: NOT_APPLICABLE";

    private QualificationProviderEvidence() {}

    static void capture(Path runRoot, String commandOutput) throws IOException {
        if (hasProviderRender(runRoot)) return;
        if (commandOutput == null || !commandOutput.contains(NO_PROVIDER_PROMPT)) {
            throw new IllegalStateException(
                    "prompt-debug produced neither a provider render nor verified no-provider output");
        }
        Path promptDebug = runRoot.resolve("prompt-debug.txt");
        Path modelLog = runRoot.resolve("llama.log");
        if (Files.exists(promptDebug) || Files.exists(modelLog)) {
            throw new IllegalStateException("refusing to overwrite provider qualification evidence");
        }
        Files.writeString(promptDebug, promptNotApplicableText(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        Files.writeString(modelLog, modelRuntimeNotApplicableText(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
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
