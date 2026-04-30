package dev.talos.runtime.trace;

import dev.talos.runtime.task.TaskContract;

import java.util.ArrayList;
import java.util.List;

/**
 * First-class local trace artifact for one Talos turn.
 *
 * <p>Version 2 is intentionally Java-record/JSON friendly and conservative:
 * raw prompts, assistant answers, file contents, and write/edit payloads are
 * summarized by hashes and counts in the default redaction mode.
 */
public record LocalTurnTrace(
        int schemaVersion,
        String traceId,
        String sessionId,
        int turnNumber,
        String timestamp,
        String workspaceHash,
        String mode,
        ModelSummary model,
        TaskContractSummary taskContract,
        List<PhaseTransition> phaseTransitions,
        ToolSurface toolSurface,
        PromptAuditSnapshot promptAudit,
        List<TurnTraceEvent> events,
        VerificationSummary verification,
        RepairSummary repair,
        CheckpointSummary checkpoint,
        OutcomeSummary outcome,
        List<WarningSummary> warnings,
        RedactionSummary redaction
) {
    public LocalTurnTrace {
        schemaVersion = schemaVersion <= 0 ? 2 : schemaVersion;
        traceId = safe(traceId);
        sessionId = safe(sessionId);
        timestamp = safe(timestamp);
        workspaceHash = safe(workspaceHash);
        mode = safe(mode);
        model = model == null ? new ModelSummary("", "") : model;
        taskContract = taskContract == null ? TaskContractSummary.empty() : taskContract;
        phaseTransitions = phaseTransitions == null ? List.of() : List.copyOf(phaseTransitions);
        toolSurface = toolSurface == null ? ToolSurface.empty() : toolSurface;
        promptAudit = promptAudit == null ? PromptAuditSnapshot.empty() : promptAudit;
        events = events == null ? List.of() : List.copyOf(events);
        verification = verification == null ? VerificationSummary.empty() : verification;
        repair = repair == null ? RepairSummary.empty() : repair;
        checkpoint = checkpoint == null ? CheckpointSummary.empty() : checkpoint;
        outcome = outcome == null ? OutcomeSummary.empty() : outcome;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        redaction = redaction == null ? RedactionSummary.defaultMode() : redaction;
    }

    public static Builder builder(String traceId, String sessionId, int turnNumber, String timestamp) {
        return new Builder(traceId, sessionId, turnNumber, timestamp);
    }

    public record ModelSummary(String backend, String model) {
        public ModelSummary {
            backend = safe(backend);
            model = safe(model);
        }
    }

    public record TaskContractSummary(
            String type,
            boolean mutationAllowed,
            boolean verificationRequired,
            boolean mutationRequested,
            List<String> expectedTargets,
            List<String> forbiddenTargets
    ) {
        public TaskContractSummary {
            type = safe(type);
            expectedTargets = expectedTargets == null ? List.of() : List.copyOf(expectedTargets);
            forbiddenTargets = forbiddenTargets == null ? List.of() : List.copyOf(forbiddenTargets);
        }

        static TaskContractSummary empty() {
            return new TaskContractSummary("", false, false, false, List.of(), List.of());
        }

        static TaskContractSummary from(TaskContract contract) {
            if (contract == null) return empty();
            return new TaskContractSummary(
                    contract.type().name(),
                    contract.mutationAllowed(),
                    contract.verificationRequired(),
                    contract.mutationRequested(),
                    contract.expectedTargets().stream().sorted().toList(),
                    contract.forbiddenTargets().stream().sorted().toList());
        }
    }

    public record PhaseTransition(String from, String to, String reason) {
        public PhaseTransition {
            from = safe(from);
            to = safe(to);
            reason = safe(reason);
        }
    }

    public record ToolSurface(List<String> nativeTools, List<String> promptTools, String reason) {
        public ToolSurface {
            nativeTools = nativeTools == null ? List.of() : List.copyOf(nativeTools);
            promptTools = promptTools == null ? List.of() : List.copyOf(promptTools);
            reason = safe(reason);
        }

        static ToolSurface empty() {
            return new ToolSurface(List.of(), List.of(), "");
        }
    }

    public record VerificationSummary(String status, String summary, List<String> problems) {
        public VerificationSummary {
            status = safe(status);
            summary = safe(summary);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        static VerificationSummary empty() {
            return new VerificationSummary("", "", List.of());
        }
    }

    public record RepairSummary(String status, String summary) {
        public RepairSummary {
            status = safe(status);
            summary = safe(summary);
        }

        static RepairSummary empty() {
            return new RepairSummary("", "");
        }
    }

    public record CheckpointSummary(String status, String checkpointId) {
        public CheckpointSummary {
            status = safe(status);
            checkpointId = safe(checkpointId);
        }

        static CheckpointSummary empty() {
            return new CheckpointSummary("", "");
        }
    }

    public record OutcomeSummary(
            String status,
            String verificationStatus,
            String approvalStatus,
            String mutationStatus,
            String classification
    ) {
        public OutcomeSummary {
            status = safe(status);
            verificationStatus = safe(verificationStatus);
            approvalStatus = safe(approvalStatus);
            mutationStatus = safe(mutationStatus);
            classification = safe(classification);
        }

        static OutcomeSummary empty() {
            return new OutcomeSummary("", "", "", "", "");
        }
    }

    public record WarningSummary(String code, String message) {
        public WarningSummary {
            code = safe(code);
            message = safe(message);
        }
    }

    public record TextSummary(String hash, int chars, int bytes, int lines) {
        public TextSummary {
            hash = safe(hash);
        }

        static TextSummary empty() {
            return new TextSummary("", 0, 0, 0);
        }

        static TextSummary from(String text) {
            if (text == null) return empty();
            return new TextSummary(
                    TraceRedactor.hash(text),
                    text.length(),
                    TraceRedactor.bytes(text),
                    TraceRedactor.lines(text));
        }
    }

    public record RedactionSummary(
            TraceRedactionMode mode,
            boolean fullPromptCaptured,
            boolean fullAssistantCaptured,
            boolean fullToolPayloadCaptured,
            String promptHash,
            String assistantHash,
            TextSummary prompt,
            TextSummary assistant
    ) {
        public RedactionSummary {
            mode = mode == null ? TraceRedactionMode.DEFAULT : mode;
            prompt = prompt == null ? TextSummary.empty() : prompt;
            assistant = assistant == null ? TextSummary.empty() : assistant;
            promptHash = promptHash == null || promptHash.isBlank() ? prompt.hash() : promptHash;
            assistantHash = assistantHash == null || assistantHash.isBlank() ? assistant.hash() : assistantHash;
        }

        static RedactionSummary defaultMode() {
            return new RedactionSummary(
                    TraceRedactionMode.DEFAULT,
                    false,
                    false,
                    false,
                    "",
                    "",
                    TextSummary.empty(),
                    TextSummary.empty());
        }
    }

    public static final class Builder {
        private final String traceId;
        private final String sessionId;
        private final int turnNumber;
        private final String timestamp;

        private String workspaceHash = "";
        private String mode = "";
        private ModelSummary model = new ModelSummary("", "");
        private TaskContractSummary taskContract = TaskContractSummary.empty();
        private final List<PhaseTransition> phaseTransitions = new ArrayList<>();
        private ToolSurface toolSurface = ToolSurface.empty();
        private PromptAuditSnapshot promptAudit = PromptAuditSnapshot.empty();
        private final List<TurnTraceEvent> events = new ArrayList<>();
        private VerificationSummary verification = VerificationSummary.empty();
        private RepairSummary repair = RepairSummary.empty();
        private CheckpointSummary checkpoint = CheckpointSummary.empty();
        private OutcomeSummary outcome = OutcomeSummary.empty();
        private final List<WarningSummary> warnings = new ArrayList<>();
        private TextSummary prompt = TextSummary.empty();
        private TextSummary assistant = TextSummary.empty();
        private TraceRedactionMode redactionMode = TraceRedactionMode.DEFAULT;

        private Builder(String traceId, String sessionId, int turnNumber, String timestamp) {
            this.traceId = traceId;
            this.sessionId = sessionId;
            this.turnNumber = turnNumber;
            this.timestamp = timestamp;
        }

        public Builder workspaceHash(String workspaceHash) {
            this.workspaceHash = safe(workspaceHash);
            return this;
        }

        public Builder mode(String mode) {
            this.mode = safe(mode);
            return this;
        }

        public Builder model(String backend, String model) {
            this.model = new ModelSummary(backend, model);
            return this;
        }

        public Builder promptSummary(String prompt) {
            this.prompt = TextSummary.from(prompt);
            return this;
        }

        public Builder assistantSummary(String assistant) {
            this.assistant = TextSummary.from(assistant);
            return this;
        }

        public Builder taskContract(TaskContract contract) {
            this.taskContract = TaskContractSummary.from(contract);
            return this;
        }

        public Builder taskContract(TaskContractSummary summary) {
            this.taskContract = summary == null ? TaskContractSummary.empty() : summary;
            return this;
        }

        public Builder phaseTransition(String from, String to, String reason) {
            this.phaseTransitions.add(new PhaseTransition(from, to, reason));
            return this;
        }

        public Builder toolSurface(List<String> nativeTools, List<String> promptTools, String reason) {
            this.toolSurface = new ToolSurface(nativeTools, promptTools, reason);
            return this;
        }

        public Builder promptAudit(PromptAuditSnapshot snapshot) {
            this.promptAudit = snapshot == null ? PromptAuditSnapshot.empty() : snapshot;
            return this;
        }

        public Builder event(TurnTraceEvent event) {
            if (event != null) this.events.add(event);
            return this;
        }

        public Builder verification(String status, String summary, List<String> problems) {
            this.verification = new VerificationSummary(status, summary, problems);
            return this;
        }

        public Builder repair(String status, String summary) {
            this.repair = new RepairSummary(status, summary);
            return this;
        }

        public Builder checkpoint(String status, String checkpointId) {
            this.checkpoint = new CheckpointSummary(status, checkpointId);
            return this;
        }

        public Builder outcome(
                String status,
                String verificationStatus,
                String approvalStatus,
                String mutationStatus,
                String classification
        ) {
            this.outcome = new OutcomeSummary(
                    status, verificationStatus, approvalStatus, mutationStatus, classification);
            return this;
        }

        public Builder warning(String code, String message) {
            this.warnings.add(new WarningSummary(code, message));
            return this;
        }

        public Builder redactionMode(TraceRedactionMode mode) {
            this.redactionMode = mode == null ? TraceRedactionMode.DEFAULT : mode;
            return this;
        }

        public LocalTurnTrace build() {
            return new LocalTurnTrace(
                    2,
                    traceId,
                    sessionId,
                    turnNumber,
                    timestamp,
                    workspaceHash,
                    mode,
                    model,
                    taskContract,
                    phaseTransitions,
                    toolSurface,
                    promptAudit,
                    events,
                    verification,
                    repair,
                    checkpoint,
                    outcome,
                    warnings,
                    new RedactionSummary(
                            redactionMode,
                            false,
                            false,
                            false,
                            prompt.hash(),
                            assistant.hash(),
                            prompt,
                            assistant));
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
