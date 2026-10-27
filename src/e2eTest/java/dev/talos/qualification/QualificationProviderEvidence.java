package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class QualificationProviderEvidence {
    static final String NO_PROVIDER_PROMPT = "No provider prompt was sent for the last turn.";
    private static final String PROMPT_HEADER = "Prompt debug: NOT_APPLICABLE";
    private static final String MODEL_RUNTIME_HEADER = "Model runtime: NOT_APPLICABLE";

    private QualificationProviderEvidence() {}

    static void capture(
            Path packetRoot,
            Path runRoot,
            String commandOutput,
            int protocolVersion) throws IOException {
        QualificationArtifactPublication.requireWritableProtocol(protocolVersion);
        QualificationPacketPublisher.withLease(packetRoot, lease -> {
            NoFollowPathPolicy.GuardedRoot packetFiles =
                    NoFollowPathPolicy.bindExistingDirectory(lease.packetRoot());
            Path containedRunRoot = packetFiles.requireDirectory(runRoot);
            NoFollowPathPolicy.GuardedRoot runFiles =
                    NoFollowPathPolicy.bindExistingDirectory(containedRunRoot);
            if (hasProviderRender(runFiles, containedRunRoot)) return null;
            if (commandOutput == null || !commandOutput.contains(NO_PROVIDER_PROMPT)) {
                throw new IllegalStateException(
                        "prompt-debug produced neither a provider render nor verified no-provider output");
            }
            Path promptDebug = containedRunRoot.resolve("prompt-debug.txt");
            Path modelLog = containedRunRoot.resolve("llama.log");
            byte[] promptBytes = promptNotApplicableText().getBytes(StandardCharsets.UTF_8);
            byte[] modelBytes = modelRuntimeNotApplicableText().getBytes(StandardCharsets.UTF_8);
            if (hasProviderRender(runFiles, containedRunRoot)) {
                throw new IllegalStateException(
                        "provider render appeared while deriving no-provider evidence");
            }
            lease.publish(
                    QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                    java.util.List.of(
                            new QualificationPacketPublisher.Member(promptDebug, promptBytes),
                            new QualificationPacketPublisher.Member(modelLog, modelBytes)));
            return null;
        });
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

    private static boolean hasProviderRender(
            NoFollowPathPolicy.GuardedRoot runFiles,
            Path runRoot
    ) throws IOException {
        return runFiles.walk(runRoot).stream().anyMatch(entry -> {
            Path path = entry.path();
            String name = path.getFileName().toString();
            return entry.type() == NoFollowPathPolicy.EntryType.REGULAR_FILE
                    && path.getParent().equals(runRoot)
                    && name.startsWith("prompt-debug-")
                    && name.endsWith(".md");
        });
    }
}
