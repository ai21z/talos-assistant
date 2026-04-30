package dev.talos.runtime.trace;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.task.TaskContract;
import dev.talos.spi.types.ChatMessage;

import java.util.List;

/** Redacted prompt/control audit summary for one model call. */
public record PromptAuditSnapshot(
        int schemaVersion,
        String taskType,
        boolean mutationAllowed,
        boolean verificationRequired,
        String phaseInitial,
        String phaseFinal,
        String actionObligation,
        String evidenceObligation,
        String outputObligation,
        String activeTaskContext,
        String artifactGoal,
        String verifierProfile,
        String historyPolicy,
        int historyMessageCount,
        boolean currentTurnFrameInjected,
        String currentTurnFramePlacement,
        String currentTurnFrameHash,
        String currentTurnFramePreviewRedacted,
        int systemMessageCount,
        int userMessageCount,
        int totalMessageCount,
        String promptHash,
        List<String> nativeTools,
        List<String> promptTools,
        List<String> blockedTools,
        TraceRedactionMode redactionMode
) {
    public static final String NONE_OR_NOT_DERIVED = "NONE_OR_NOT_DERIVED";
    public static final String NOT_DERIVED = "NOT_DERIVED";

    public PromptAuditSnapshot {
        schemaVersion = schemaVersion <= 0 ? 1 : schemaVersion;
        taskType = safe(taskType);
        phaseInitial = safe(phaseInitial);
        phaseFinal = safe(phaseFinal);
        actionObligation = safe(actionObligation);
        evidenceObligation = blankDefault(evidenceObligation, NONE_OR_NOT_DERIVED);
        outputObligation = blankDefault(outputObligation, NOT_DERIVED);
        activeTaskContext = blankDefault(activeTaskContext, NONE_OR_NOT_DERIVED);
        artifactGoal = blankDefault(artifactGoal, NONE_OR_NOT_DERIVED);
        verifierProfile = blankDefault(verifierProfile, NONE_OR_NOT_DERIVED);
        historyPolicy = blankDefault(historyPolicy, NOT_DERIVED);
        currentTurnFramePlacement = blankDefault(currentTurnFramePlacement, "UNKNOWN");
        currentTurnFrameHash = safe(currentTurnFrameHash);
        currentTurnFramePreviewRedacted = PromptAuditRedactor.preview(currentTurnFramePreviewRedacted);
        promptHash = safe(promptHash);
        nativeTools = nativeTools == null ? List.of() : List.copyOf(nativeTools);
        promptTools = promptTools == null ? List.of() : List.copyOf(promptTools);
        blockedTools = blockedTools == null ? List.of() : List.copyOf(blockedTools);
        redactionMode = redactionMode == null ? TraceRedactionMode.DEFAULT : redactionMode;
    }

    public static PromptAuditSnapshot empty() {
        return new PromptAuditSnapshot(
                1,
                "",
                false,
                false,
                "",
                "",
                "",
                NONE_OR_NOT_DERIVED,
                NOT_DERIVED,
                NONE_OR_NOT_DERIVED,
                NONE_OR_NOT_DERIVED,
                NONE_OR_NOT_DERIVED,
                NOT_DERIVED,
                0,
                false,
                "UNKNOWN",
                "",
                "",
                0,
                0,
                0,
                "",
                List.of(),
                List.of(),
                List.of(),
                TraceRedactionMode.DEFAULT);
    }

    public static PromptAuditSnapshot fromMessages(
            TaskContract contract,
            ExecutionPhase phaseInitial,
            ExecutionPhase phaseFinal,
            ActionObligation actionObligation,
            List<ChatMessage> messages,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blockedTools
    ) {
        PromptMessageLayout layout = PromptMessageLayout.fromMessages(messages);
        String taskType = contract == null || contract.type() == null ? "" : contract.type().name();
        return new PromptAuditSnapshot(
                1,
                taskType,
                contract != null && contract.mutationAllowed(),
                contract != null && contract.verificationRequired(),
                phaseInitial == null ? "" : phaseInitial.name(),
                phaseFinal == null ? "" : phaseFinal.name(),
                actionObligation == null ? "" : actionObligation.name(),
                NONE_OR_NOT_DERIVED,
                NOT_DERIVED,
                NONE_OR_NOT_DERIVED,
                NONE_OR_NOT_DERIVED,
                NONE_OR_NOT_DERIVED,
                layout.historyPolicy(),
                layout.historyMessageCount(),
                layout.currentTurnFrameInjected(),
                layout.currentTurnFramePlacement(),
                layout.currentTurnFrameHash(),
                layout.currentTurnFramePreviewRedacted(),
                layout.systemMessageCount(),
                layout.userMessageCount(),
                layout.totalMessageCount(),
                layout.promptHash(),
                nativeTools,
                promptTools,
                blockedTools,
                TraceRedactionMode.DEFAULT);
    }

    public boolean hasPromptAuditData() {
        return !taskType.isBlank()
                || !actionObligation.isBlank()
                || currentTurnFrameInjected
                || !nativeTools.isEmpty()
                || !promptTools.isEmpty();
    }

    public String renderCompact() {
        StringBuilder sb = new StringBuilder();
        sb.append("Prompt Audit\n");
        sb.append("  contract: ").append(blankDefault(taskType, "UNKNOWN"))
                .append(" mutationAllowed=").append(mutationAllowed)
                .append(" verificationRequired=").append(verificationRequired)
                .append('\n');
        if (!phaseInitial.isBlank() || !phaseFinal.isBlank()) {
            sb.append("  phase: ").append(blankDefault(phaseInitial, "UNKNOWN"));
            if (!phaseFinal.isBlank() && !phaseFinal.equals(phaseInitial)) {
                sb.append(" -> ").append(phaseFinal);
            }
            sb.append('\n');
        }
        sb.append("  actionObligation: ").append(blankDefault(actionObligation, NOT_DERIVED)).append('\n');
        sb.append("  evidenceObligation: ").append(blankDefault(evidenceObligation, NONE_OR_NOT_DERIVED)).append('\n');
        sb.append("  outputObligation: ").append(blankDefault(outputObligation, NOT_DERIVED)).append('\n');
        sb.append("  activeTaskContext: ").append(blankDefault(activeTaskContext, NONE_OR_NOT_DERIVED)).append('\n');
        sb.append("  artifactGoal: ").append(blankDefault(artifactGoal, NONE_OR_NOT_DERIVED)).append('\n');
        sb.append("  verifierProfile: ").append(blankDefault(verifierProfile, NONE_OR_NOT_DERIVED)).append('\n');
        sb.append("  history: ").append(blankDefault(historyPolicy, NOT_DERIVED))
                .append(" messages=").append(historyMessageCount)
                .append('\n');
        sb.append("  currentTurnFrame: ")
                .append(currentTurnFrameInjected ? "injected " : "not-injected ")
                .append(blankDefault(currentTurnFramePlacement, "UNKNOWN"));
        if (!currentTurnFrameHash.isBlank()) {
            sb.append(" hash=").append(currentTurnFrameHash);
        }
        sb.append('\n');
        if (!currentTurnFramePreviewRedacted.isBlank()) {
            sb.append("  framePreview: ").append(currentTurnFramePreviewRedacted).append('\n');
        }
        sb.append("  messages: system=").append(systemMessageCount)
                .append(" history=").append(historyMessageCount)
                .append(" user=").append(userMessageCount)
                .append(" total=").append(totalMessageCount)
                .append('\n');
        sb.append("  nativeTools: ").append(listOrNone(nativeTools)).append('\n');
        sb.append("  promptTools: ").append(listOrNone(promptTools)).append('\n');
        if (!blockedTools.isEmpty()) {
            sb.append("  blockedTools: ").append(listOrNone(blockedTools)).append('\n');
        }
        sb.append("  promptHash: ").append(blankDefault(promptHash, "none")).append('\n');
        sb.append("  redaction: ").append(redactionMode).append('\n');
        return sb.toString();
    }

    private static String listOrNone(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
