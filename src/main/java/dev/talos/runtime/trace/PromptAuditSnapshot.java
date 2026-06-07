package dev.talos.runtime.trace;

import dev.talos.core.context.ConversationCompactionStatus;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.turn.CurrentTurnPlan;
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
        TraceRedactionMode redactionMode,
        String compactionStatus,
        String projectMemoryStatus
) {
    public static final String NONE_OR_NOT_DERIVED = "NONE_OR_NOT_DERIVED";
    public static final String NOT_DERIVED = "NOT_DERIVED";

    public PromptAuditSnapshot {
        schemaVersion = schemaVersion <= 0 ? 1 : schemaVersion;
        taskType = safe(taskType);
        phaseInitial = safe(phaseInitial);
        phaseFinal = safe(phaseFinal);
        actionObligation = safe(actionObligation);
        evidenceObligation = redactedAuditField(evidenceObligation, NONE_OR_NOT_DERIVED);
        outputObligation = redactedAuditField(outputObligation, NOT_DERIVED);
        activeTaskContext = redactedAuditField(activeTaskContext, NONE_OR_NOT_DERIVED);
        artifactGoal = redactedAuditField(artifactGoal, NONE_OR_NOT_DERIVED);
        verifierProfile = redactedAuditField(verifierProfile, NONE_OR_NOT_DERIVED);
        historyPolicy = blankDefault(historyPolicy, NOT_DERIVED);
        currentTurnFramePlacement = blankDefault(currentTurnFramePlacement, "UNKNOWN");
        currentTurnFrameHash = safe(currentTurnFrameHash);
        currentTurnFramePreviewRedacted = PromptAuditRedactor.preview(currentTurnFramePreviewRedacted);
        promptHash = safe(promptHash);
        nativeTools = nativeTools == null ? List.of() : List.copyOf(nativeTools);
        promptTools = promptTools == null ? List.of() : List.copyOf(promptTools);
        blockedTools = blockedTools == null ? List.of() : List.copyOf(blockedTools);
        redactionMode = redactionMode == null ? TraceRedactionMode.DEFAULT : redactionMode;
        compactionStatus = redactedAuditField(compactionStatus, NOT_DERIVED);
        projectMemoryStatus = redactedAuditField(projectMemoryStatus, NOT_DERIVED);
    }

    public PromptAuditSnapshot(
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
            TraceRedactionMode redactionMode,
            String compactionStatus
    ) {
        this(
                schemaVersion,
                taskType,
                mutationAllowed,
                verificationRequired,
                phaseInitial,
                phaseFinal,
                actionObligation,
                evidenceObligation,
                outputObligation,
                activeTaskContext,
                artifactGoal,
                verifierProfile,
                historyPolicy,
                historyMessageCount,
                currentTurnFrameInjected,
                currentTurnFramePlacement,
                currentTurnFrameHash,
                currentTurnFramePreviewRedacted,
                systemMessageCount,
                userMessageCount,
                totalMessageCount,
                promptHash,
                nativeTools,
                promptTools,
                blockedTools,
                redactionMode,
                compactionStatus,
                NOT_DERIVED);
    }

    public PromptAuditSnapshot(
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
        this(
                schemaVersion,
                taskType,
                mutationAllowed,
                verificationRequired,
                phaseInitial,
                phaseFinal,
                actionObligation,
                evidenceObligation,
                outputObligation,
                activeTaskContext,
                artifactGoal,
                verifierProfile,
                historyPolicy,
                historyMessageCount,
                currentTurnFrameInjected,
                currentTurnFramePlacement,
                currentTurnFrameHash,
                currentTurnFramePreviewRedacted,
                systemMessageCount,
                userMessageCount,
                totalMessageCount,
                promptHash,
                nativeTools,
                promptTools,
                blockedTools,
                redactionMode,
                NOT_DERIVED,
                NOT_DERIVED);
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
                TraceRedactionMode.DEFAULT,
                NOT_DERIVED,
                NOT_DERIVED);
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
        CurrentTurnPlan plan = new CurrentTurnPlan(
                contract,
                contract == null ? "" : contract.originalUserRequest(),
                phaseInitial,
                phaseFinal,
                actionObligation,
                List.of(),
                nativeTools,
                promptTools,
                blockedTools,
                NONE_OR_NOT_DERIVED,
                NOT_DERIVED,
                NONE_OR_NOT_DERIVED,
                NONE_OR_NOT_DERIVED,
                NONE_OR_NOT_DERIVED);
        PromptMessageLayout layout = PromptMessageLayout.fromMessages(messages);
        return new PromptAuditSnapshot(
                1,
                contract == null || contract.type() == null ? "" : contract.type().name(),
                contract != null && contract.mutationAllowed(),
                contract != null && contract.verificationRequired(),
                phaseInitial == null ? "" : phaseInitial.name(),
                phaseFinal == null ? "" : phaseFinal.name(),
                actionObligation == null ? "" : actionObligation.name(),
                plan.evidenceObligation(),
                plan.outputObligation(),
                plan.activeTaskContext(),
                plan.artifactGoal(),
                plan.verifierProfile(),
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
                plan.nativeTools(),
                plan.promptTools(),
                plan.blockedTools(),
                TraceRedactionMode.DEFAULT,
                NOT_DERIVED,
                NOT_DERIVED);
    }

    public static PromptAuditSnapshot fromPlan(CurrentTurnPlan plan, List<ChatMessage> messages) {
        return fromPlan(plan, messages, null);
    }

    public static PromptAuditSnapshot fromPlan(
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ConversationCompactionStatus compactionStatus
    ) {
        return fromPlan(plan, messages, compactionStatus, NOT_DERIVED);
    }

    public static PromptAuditSnapshot fromPlan(
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ConversationCompactionStatus compactionStatus,
            String projectMemoryStatus
    ) {
        CurrentTurnPlan safePlan = plan == null
                ? CurrentTurnPlan.compatibility(null, null, List.of(), List.of(), List.of())
                : plan;
        PromptMessageLayout layout = PromptMessageLayout.fromMessages(messages);
        TaskContract contract = safePlan.taskContract();
        String taskType = contract.type() == null ? "" : contract.type().name();
        return new PromptAuditSnapshot(
                1,
                taskType,
                contract.mutationAllowed(),
                contract.verificationRequired(),
                safePlan.phaseInitial() == null ? "" : safePlan.phaseInitial().name(),
                safePlan.phaseFinal() == null ? "" : safePlan.phaseFinal().name(),
                safePlan.actionObligation() == null ? "" : safePlan.actionObligation().name(),
                safePlan.evidenceObligation(),
                safePlan.outputObligation(),
                safePlan.activeTaskContext(),
                safePlan.artifactGoal(),
                safePlan.verifierProfile(),
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
                safePlan.nativeTools(),
                safePlan.promptTools(),
                safePlan.blockedTools(),
                TraceRedactionMode.DEFAULT,
                compactionStatus == null ? NOT_DERIVED : compactionStatus.renderCompact(),
                projectMemoryStatus);
    }

    public boolean hasPromptAuditData() {
        return !taskType.isBlank()
                || !actionObligation.isBlank()
                || currentTurnFrameInjected
                || !nativeTools.isEmpty()
                || !promptTools.isEmpty()
                || !NOT_DERIVED.equals(compactionStatus)
                || !NOT_DERIVED.equals(projectMemoryStatus);
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
        sb.append("  compaction: ").append(blankDefault(compactionStatus, NOT_DERIVED)).append('\n');
        sb.append("  projectMemory: ").append(blankDefault(projectMemoryStatus, NOT_DERIVED)).append('\n');
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

    private static String redactedAuditField(String value, String fallback) {
        return blankDefault(PromptAuditRedactor.preview(value), fallback);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
