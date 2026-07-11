package dev.talos.qualification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class QualificationPromptDebugEvidence {
    static final String NO_PROVIDER_PROMPT = "No provider prompt was sent for the last turn.";
    static final String NOT_APPLICABLE_HEADER = "Prompt debug: NOT_APPLICABLE";

    private QualificationPromptDebugEvidence() {}

    static void capture(Path runRoot, String commandOutput) throws IOException {
        if (hasProviderRender(runRoot)) return;
        if (commandOutput == null || !commandOutput.contains(NO_PROVIDER_PROMPT)) {
            throw new IllegalStateException(
                    "prompt-debug produced neither a provider render nor verified no-provider output");
        }
        Files.writeString(
                runRoot.resolve("prompt-debug.txt"),
                notApplicableText(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    static String notApplicableText() {
        return NOT_APPLICABLE_HEADER + "\n"
                + "Reason: " + NO_PROVIDER_PROMPT + "\n"
                + "Evidence source: structured PTY output from /prompt-debug save.\n";
    }

    static boolean isNotApplicable(String evidence) {
        return evidence != null && evidence.startsWith(NOT_APPLICABLE_HEADER + "\n");
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
