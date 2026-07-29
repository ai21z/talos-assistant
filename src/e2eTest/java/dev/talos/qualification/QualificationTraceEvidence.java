package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import dev.talos.runtime.outcome.EvidenceContainmentAnswerGuard;
import dev.talos.runtime.outcome.FileLookupAnswerGuard;
import dev.talos.runtime.outcome.MutationRecoveryAssessment;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.verification.TaskVerificationEvidenceSource;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Derives score-bearing tool and outcome facts from the persisted Talos trace. */
final class QualificationTraceEvidence {
    private static final Set<String> MUTATION_INSPECTION_AUXILIARIES = Set.of(
            "talos.grep", "talos.list_dir", "talos.read_file", "talos.retrieve");
    private static final Set<String> GENERATION_ABORT_REASON_CODES = Set.of(
            "REPETITION_LOOP",
            "WALL_CLOCK_BUDGET_EXCEEDED",
            "IDLE_TIMEOUT",
            "INTERRUPTED",
            "TRANSPORT_FAILURE",
            "UNKNOWN");
    private static final Set<String> TOOL_CALL_TRANSFORMATION_REASON_CODES = Set.of(
            "MISSING_PATH_REPAIR",
            "PROTECTED_ALIAS_NORMALIZATION",
            "SOURCE_EVIDENCE_EXACT_REPAIR",
            "APPEND_WRITE_TO_EDIT",
            "MARKDOWN_COMMENTARY_SANITIZATION",
            "EXACT_LITERAL_WRITE_CORRECTION",
            "PATH_ARGUMENT_NORMALIZATION",
            "WRITE_FORMAT_PRESERVATION");
    private static final Set<String> ANSWER_TRANSFORMATION_REASON_CODES = Set.of(
            "EXECUTOR_POLICY_SHAPING",
            "PROVIDER_RESPONSE_NORMALIZATION",
            "TASK_CONTRACT_TOOL_CALL_SUPPRESSION",
            "MODE_PRESENTATION_LAYOUT",
            "RETRIEVAL_CITATION_SUFFIX",
            "SESSION_COMMIT_NORMALIZATION",
            "TOOL_LOOP_ITERATION_LIMIT",
            "TOOL_LOOP_FINALIZATION");
    private static final Set<String> ANSWER_RESTORATION_REASON_CODES = Set.of(
            "SPECULATIVE_RETRY_REJECTED",
            "SPECULATIVE_RETRY_FAILED",
            "SPECULATIVE_RETRY_EMPTY");
    private static final Set<ToolFailureReason> MATCHED_RECOVERY_FAILURE_REASONS = Set.of(
            ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND,
            ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
            ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED);
    private static final Set<String> MUTATION_EVIDENCE_KINDS = Set.of(
            "NONE",
            "EXACT_EDIT_REPLACEMENT",
            "FULL_WRITE_REPLACEMENT");
    private static final Set<String> STRUCTURED_MUTATION_EVIDENCE_KINDS = Set.of(
            "EXACT_EDIT_REPLACEMENT",
            "FULL_WRITE_REPLACEMENT");
    private static final Set<String> TOOL_OUTCOME_CLASSIFIED_DATA_KEYS_V1 = Set.of(
            "schemaVersion",
            "outcomeOrdinal",
            "toolCallId",
            "success",
            "mutating",
            "denied",
            "failureReason",
            "mutationEvidenceKind");
    private static final Set<String> TOOL_OUTCOME_CLASSIFIED_DATA_KEYS_V2 = Set.of(
            "schemaVersion",
            "outcomeOrdinal",
            "toolCallId",
            "success",
            "mutating",
            "denied",
            "errorCode",
            "failureReason",
            "mutationEvidenceKind");
    private static final Set<String> MUTATION_RECOVERY_DATA_KEYS_V1 = Set.of(
            "schemaVersion",
            "failureOrdinal",
            "repairOrdinal",
            "failureToolCallId",
            "repairToolCallId",
            "decision",
            "resolved",
            "recovered",
            "failureReason",
            "targetHint",
            "targetSha256",
            "requestObligationSha256",
            "verifiedRequestObligationSha256",
            "verificationSource",
            "verificationStatus");
    private static final Set<String> MUTATION_RECOVERY_DATA_KEYS_V2 = Set.of(
            "schemaVersion",
            "failureOrdinal",
            "repairOrdinal",
            "failureToolCallId",
            "repairToolCallId",
            "decision",
            "resolved",
            "recovered",
            "failureReason",
            "targetHint",
            "targetSha256",
            "requestObligationSha256",
            "verifiedRequestObligationSha256",
            "verificationSource",
            "verificationStatus",
            "coveredSuccessfulMutationToolCallIds");

    private QualificationTraceEvidence() {}

    enum ApprovalDisposition {
        NONE,
        GRANTED,
        DENIED
    }

    enum CallSettlement {
        EXECUTED_SUCCESS,
        EXECUTED_FAILURE,
        BLOCKED,
        UNSETTLED
    }

    private enum ExecutionAuthorityKind {
        LEGACY,
        PROVIDER_NATIVE,
        PROVIDER_TEXT_JSON,
        RUNTIME_DETERMINISTIC
    }

    private record ExecutionAuthority(
            ExecutionAuthorityKind kind,
            String sourceIdentity,
            LocalTurnTraceCapture.RuntimeToolAuthorityReason runtimeReason
    ) {
        private static ExecutionAuthority legacy() {
            return new ExecutionAuthority(
                    ExecutionAuthorityKind.LEGACY,
                    "",
                    LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE);
        }
    }

    record Call(
            String toolName,
            String pathHint,
            boolean executed,
            boolean allowed,
            List<String> sourcePaths,
            ApprovalDisposition approvalDisposition,
            String providerGenerationId,
            String providerAttemptId,
            boolean nativeProviderDecisionBound,
            CallSettlement settlement,
            String decisionToolName,
            boolean runtimeTransformed,
            String toolCallId) {
        Call {
            sourcePaths = normalizedSourcePaths(sourcePaths, executed ? pathHint : "");
            approvalDisposition = approvalDisposition == null
                    ? ApprovalDisposition.NONE
                    : approvalDisposition;
            providerGenerationId = providerGenerationId == null
                    ? ""
                    : providerGenerationId.strip();
            providerAttemptId = providerAttemptId == null
                    ? ""
                    : providerAttemptId.strip();
            settlement = settlement == null ? CallSettlement.UNSETTLED : settlement;
            decisionToolName = decisionToolName == null || decisionToolName.isBlank()
                    ? toolName
                    : decisionToolName;
            toolCallId = toolCallId == null ? "" : toolCallId.strip();
            if (!toolCallId.isEmpty()
                    && !toolCallId.matches("call-[1-9][0-9]*-[1-9][0-9]*")) {
                throw new IllegalArgumentException(
                        "tool call id is not canonical: " + toolCallId);
            }
            if (nativeProviderDecisionBound
                    && (providerGenerationId.isBlank() || providerAttemptId.isBlank())) {
                throw new IllegalArgumentException(
                        "native provider decision binding requires generation and attempt ids");
            }
            if (executed != (settlement == CallSettlement.EXECUTED_SUCCESS)) {
                throw new IllegalArgumentException(
                        "tool execution success and settlement evidence disagree");
            }
        }

        Call(
                String toolName,
                String pathHint,
                boolean executed,
                boolean allowed,
                List<String> sourcePaths,
                ApprovalDisposition approvalDisposition,
                String providerGenerationId,
                String providerAttemptId,
                boolean nativeProviderDecisionBound,
                CallSettlement settlement,
                String decisionToolName,
                boolean runtimeTransformed) {
            this(toolName, pathHint, executed, allowed, sourcePaths,
                    approvalDisposition, providerGenerationId, providerAttemptId,
                    nativeProviderDecisionBound, settlement, decisionToolName,
                    runtimeTransformed, "");
        }

        Call(
                String toolName,
                String pathHint,
                boolean executed,
                boolean allowed,
                List<String> sourcePaths,
                ApprovalDisposition approvalDisposition,
                String providerGenerationId,
                String providerAttemptId,
                boolean nativeProviderDecisionBound,
                CallSettlement settlement) {
            this(toolName, pathHint, executed, allowed, sourcePaths,
                    approvalDisposition, providerGenerationId, providerAttemptId,
                    nativeProviderDecisionBound, settlement, toolName, false);
        }

        Call(
                String toolName,
                String pathHint,
                boolean executed,
                boolean allowed,
                List<String> sourcePaths,
                ApprovalDisposition approvalDisposition,
                String providerGenerationId,
                String providerAttemptId) {
            this(toolName, pathHint, executed, allowed, sourcePaths,
                    approvalDisposition, providerGenerationId, providerAttemptId,
                    false, executed ? CallSettlement.EXECUTED_SUCCESS : CallSettlement.UNSETTLED);
        }

        Call(
                String toolName,
                String pathHint,
                boolean executed,
                boolean allowed,
                List<String> sourcePaths) {
            this(toolName, pathHint, executed, allowed, sourcePaths,
                    ApprovalDisposition.NONE, "", "", false,
                    executed ? CallSettlement.EXECUTED_SUCCESS : CallSettlement.UNSETTLED);
        }

        Call(String toolName, String pathHint, boolean executed, boolean allowed) {
            this(toolName, pathHint, executed, allowed, List.of(),
                    ApprovalDisposition.NONE, "", "", false,
                    executed ? CallSettlement.EXECUTED_SUCCESS : CallSettlement.UNSETTLED);
        }

        Call(
                String toolName,
                String pathHint,
                boolean executed,
                boolean allowed,
                List<String> sourcePaths,
                ApprovalDisposition approvalDisposition) {
            this(toolName, pathHint, executed, allowed, sourcePaths,
                    approvalDisposition, "", "", false,
                    executed ? CallSettlement.EXECUTED_SUCCESS : CallSettlement.UNSETTLED);
        }

        QualificationRecords.ToolCallResult toResult() {
            return new QualificationRecords.ToolCallResult(toolName, true, allowed, executed, false);
        }
    }

    record MutationRecoveryFact(
            String failureToolCallId,
            String repairToolCallId,
            String decision,
            boolean proven,
            String evidenceReference) {
        MutationRecoveryFact {
            failureToolCallId = required(
                    failureToolCallId, "mutation recovery failure tool-call id");
            repairToolCallId = required(
                    repairToolCallId, "mutation recovery repair tool-call id");
            decision = required(decision, "mutation recovery decision");
            evidenceReference = required(
                    evidenceReference, "mutation recovery evidence reference");
            if (failureToolCallId.equals(repairToolCallId)) {
                throw new IllegalArgumentException(
                        "mutation recovery requires distinct failure and repair calls");
            }
            if (proven
                    && !MutationRecoveryAssessment.Decision.RECOVERED_MATCHED_OBLIGATION
                            .name().equals(decision)) {
                throw new IllegalArgumentException(
                        "only matched-obligation recovery can be mechanically proven");
            }
        }
    }

    record Facts(
            String traceId,
            String modelId,
            String taskType,
            boolean mutationAllowed,
            String actionObligation,
            String terminalState,
            String outcomeStatus,
            String verificationStatus,
            List<Call> calls,
            List<QualificationRecords.DecisionResult> decisions,
            boolean approvalGranted,
            boolean approvalDenied,
            boolean boundedFileLookupNegative,
            boolean missingEvidenceContained,
            String generationAbortReasonCode,
            boolean generationAbortPartialOutput,
            QualificationProviderAttemptLedger.Ledger providerAttempts,
            boolean runtimeAnswerTransformed,
            Set<String> providerToolSurface,
            List<MutationRecoveryFact> mutationRecoveries) {
        Facts {
            calls = List.copyOf(calls);
            decisions = List.copyOf(decisions);
            generationAbortReasonCode = generationAbortReasonCode == null
                    ? ""
                    : generationAbortReasonCode.strip();
            providerAttempts = providerAttempts == null
                    ? QualificationProviderAttemptLedger.Ledger.empty()
                    : providerAttempts;
            providerToolSurface = providerToolSurface == null
                    ? Set.of()
                    : Set.copyOf(providerToolSurface);
            mutationRecoveries = mutationRecoveries == null
                    ? List.of()
                    : List.copyOf(mutationRecoveries);
        }

        Facts(
                String traceId,
                String modelId,
                String taskType,
                boolean mutationAllowed,
                String actionObligation,
                String terminalState,
                String outcomeStatus,
                String verificationStatus,
                List<Call> calls,
                List<QualificationRecords.DecisionResult> decisions,
                boolean approvalGranted,
                boolean approvalDenied,
                boolean boundedFileLookupNegative,
                boolean missingEvidenceContained,
                String generationAbortReasonCode,
                boolean generationAbortPartialOutput,
                QualificationProviderAttemptLedger.Ledger providerAttempts,
                boolean runtimeAnswerTransformed,
                Set<String> providerToolSurface) {
            this(traceId, modelId, taskType, mutationAllowed, actionObligation,
                    terminalState, outcomeStatus, verificationStatus, calls, decisions,
                    approvalGranted, approvalDenied, boundedFileLookupNegative,
                    missingEvidenceContained, generationAbortReasonCode,
                    generationAbortPartialOutput, providerAttempts,
                    runtimeAnswerTransformed, providerToolSurface, List.of());
        }

        Facts(
                String traceId,
                String modelId,
                String taskType,
                boolean mutationAllowed,
                String actionObligation,
                String terminalState,
                String outcomeStatus,
                String verificationStatus,
                List<Call> calls,
                List<QualificationRecords.DecisionResult> decisions,
                boolean approvalGranted,
                boolean approvalDenied,
                boolean boundedFileLookupNegative,
                boolean missingEvidenceContained,
                String generationAbortReasonCode,
                boolean generationAbortPartialOutput,
                QualificationProviderAttemptLedger.Ledger providerAttempts,
                boolean runtimeAnswerTransformed) {
            this(traceId, modelId, taskType, mutationAllowed, actionObligation,
                    terminalState, outcomeStatus, verificationStatus, calls, decisions,
                    approvalGranted, approvalDenied, boundedFileLookupNegative,
                    missingEvidenceContained, generationAbortReasonCode,
                    generationAbortPartialOutput, providerAttempts,
                    runtimeAnswerTransformed, Set.of(), List.of());
        }

        Facts(
                String traceId,
                String modelId,
                String taskType,
                boolean mutationAllowed,
                String actionObligation,
                String terminalState,
                String outcomeStatus,
                String verificationStatus,
                List<Call> calls,
                List<QualificationRecords.DecisionResult> decisions,
                boolean approvalGranted,
                boolean approvalDenied) {
            this(traceId, modelId, taskType, mutationAllowed, actionObligation,
                    terminalState, outcomeStatus, verificationStatus, calls, decisions,
                    approvalGranted, approvalDenied, false, false, "", false,
                    QualificationProviderAttemptLedger.Ledger.empty(), false);
        }

        Facts(
                String traceId,
                String modelId,
                String taskType,
                boolean mutationAllowed,
                String actionObligation,
                String terminalState,
                String outcomeStatus,
                String verificationStatus,
                List<Call> calls,
                List<QualificationRecords.DecisionResult> decisions,
                boolean approvalGranted,
                boolean approvalDenied,
                boolean boundedFileLookupNegative) {
            this(traceId, modelId, taskType, mutationAllowed, actionObligation,
                    terminalState, outcomeStatus, verificationStatus, calls, decisions,
                    approvalGranted, approvalDenied, boundedFileLookupNegative, false, "", false,
                    QualificationProviderAttemptLedger.Ledger.empty(), false);
        }

        Facts(
                String traceId,
                String modelId,
                String taskType,
                boolean mutationAllowed,
                String actionObligation,
                String terminalState,
                String outcomeStatus,
                String verificationStatus,
                List<Call> calls,
                List<QualificationRecords.DecisionResult> decisions,
                boolean approvalGranted,
                boolean approvalDenied,
                boolean boundedFileLookupNegative,
                boolean missingEvidenceContained) {
            this(traceId, modelId, taskType, mutationAllowed, actionObligation,
                    terminalState, outcomeStatus, verificationStatus, calls, decisions,
                    approvalGranted, approvalDenied, boundedFileLookupNegative,
                    missingEvidenceContained, "", false,
                    QualificationProviderAttemptLedger.Ledger.empty(), false);
        }

        Facts(
                String traceId,
                String modelId,
                String taskType,
                boolean mutationAllowed,
                String actionObligation,
                String terminalState,
                String outcomeStatus,
                String verificationStatus,
                List<Call> calls,
                List<QualificationRecords.DecisionResult> decisions,
                boolean approvalGranted,
                boolean approvalDenied,
                boolean boundedFileLookupNegative,
                boolean missingEvidenceContained,
                String generationAbortReasonCode,
                boolean generationAbortPartialOutput) {
            this(traceId, modelId, taskType, mutationAllowed, actionObligation,
                    terminalState, outcomeStatus, verificationStatus, calls, decisions,
                    approvalGranted, approvalDenied, boundedFileLookupNegative,
                    missingEvidenceContained, generationAbortReasonCode,
                    generationAbortPartialOutput,
                    QualificationProviderAttemptLedger.Ledger.empty(), false);
        }

        List<QualificationRecords.ToolCallResult> toolCallResults() {
            return calls.stream().map(Call::toResult).toList();
        }

        int unnecessaryExecutedCalls() {
            return (int) calls.stream().filter(Call::executed).filter(call -> !call.allowed()).count();
        }

        boolean requiredToolOpportunity(List<String> allowedTools) {
            QualificationProviderAttemptLedger.Attempt attempt = selectedDispatchedAttempt();
            return requiredToolOpportunity(attempt, allowedTools);
        }

        boolean requiredToolOpportunity(Call call, List<String> allowedTools) {
            QualificationProviderAttemptLedger.Attempt attempt = selectedDispatchedAttempt(call);
            return requiredToolOpportunity(attempt, allowedTools);
        }

        private boolean requiredToolOpportunity(
                QualificationProviderAttemptLedger.Attempt attempt,
                List<String> allowedTools) {
            return attempt != null
                    && dispatchMatchesPlannedNativeSurface(attempt)
                    && allowedTools != null && allowedTools.stream()
                    .map(ProviderResponseCapture::toolNameHash)
                    .anyMatch(attempt.authoritySurface()
                            .authorizedToolNameHashes()::contains);
        }

        boolean hasProviderToolOpportunity() {
            QualificationProviderAttemptLedger.Attempt attempt = selectedDispatchedAttempt();
            return hasProviderToolOpportunity(attempt);
        }

        boolean hasProviderToolOpportunity(Call call) {
            QualificationProviderAttemptLedger.Attempt attempt = selectedDispatchedAttempt(call);
            return hasProviderToolOpportunity(attempt);
        }

        private boolean hasProviderToolOpportunity(
                QualificationProviderAttemptLedger.Attempt attempt) {
            return attempt != null
                    && dispatchMatchesPlannedNativeSurface(attempt)
                    && !attempt.authoritySurface().authorizedToolNameHashes().isEmpty();
        }

        private QualificationProviderAttemptLedger.Attempt selectedDispatchedAttempt() {
            QualificationProviderAttemptLedger.Generation generation = providerAttempts
                    .latestTerminalEligibleForegroundDecision();
            return generation == null ? null : generation.selectedAttempt();
        }

        private QualificationProviderAttemptLedger.Attempt selectedDispatchedAttempt(Call call) {
            if (call == null
                    || call.providerGenerationId().isBlank()
                    || call.providerAttemptId().isBlank()) {
                return null;
            }
            QualificationProviderAttemptLedger.Generation generation = providerAttempts
                    .selectedGeneration(
                            call.providerGenerationId(), call.providerAttemptId());
            return generation == null ? null : generation.selectedAttempt();
        }

        private boolean dispatchMatchesPlannedNativeSurface(
                QualificationProviderAttemptLedger.Attempt attempt) {
            List<String> dispatched = attempt.dispatchedNativeTools();
            List<String> dispatchedHashes = dispatched.stream()
                    .map(ProviderResponseCapture::toolNameHash)
                    .toList();
            return !attempt.providerBodyHash().isBlank()
                    && !dispatched.isEmpty()
                    && attempt.authoritySurface().bound()
                    && dispatchedHashes.equals(
                            attempt.authoritySurface().selectedToolNameHashes())
                    && providerToolSurface.containsAll(dispatched);
        }
    }

    static Facts read(Path tracePath, QualificationProtocol.Scenario scenario) throws IOException {
        return read(QualificationJson.readTree(tracePath), scenario);
    }

    static Facts read(byte[] traceBytes, QualificationProtocol.Scenario scenario) throws IOException {
        return read(QualificationJson.read(traceBytes, JsonNode.class), scenario);
    }

    private static Facts read(JsonNode root, QualificationProtocol.Scenario scenario) {
        int traceSchemaVersion = QualificationJson.requiredInt(
                root.path("schemaVersion"), "trace schemaVersion");
        if (!root.isObject() || (traceSchemaVersion != 2
                && traceSchemaVersion
                        != QualificationProviderAttemptLedger.PRE_AUTHORITY_TRACE_SCHEMA_VERSION
                && traceSchemaVersion != QualificationProviderAttemptLedger.TRACE_SCHEMA_VERSION
                && traceSchemaVersion
                        != QualificationProviderAttemptLedger.PROCESS_TRACE_SCHEMA_VERSION)) {
            throw new IllegalArgumentException(
                    "trace must be a Talos schemaVersion 2, 3, 4, or 5 object");
        }
        rejectIncompleteTerminalProjection(root.path("events"));
        boolean exactToolLineage = traceSchemaVersion
                >= QualificationProviderAttemptLedger.PRE_AUTHORITY_TRACE_SCHEMA_VERSION;
        boolean exactExecutionAuthority = traceSchemaVersion
                >= QualificationProviderAttemptLedger.TRACE_SCHEMA_VERSION;
        String traceId = required(QualificationJson.requiredText(
                root.path("traceId"), "traceId"), "traceId");
        String modelId = required(QualificationJson.requiredText(
                root.path("model").path("model"), "trace model id"), "trace model id");
        JsonNode taskContract = requiredObject(root.path("taskContract"), "trace taskContract");
        String taskType = required(QualificationJson.requiredText(
                taskContract.path("type"), "trace task type"), "trace task type");
        boolean mutationAllowed = requiredBoolean(
                taskContract.path("mutationAllowed"), "trace taskContract mutationAllowed");
        JsonNode promptAudit = requiredObject(root.path("promptAudit"), "trace promptAudit");
        String promptTaskType = required(QualificationJson.requiredText(
                promptAudit.path("taskType"), "trace promptAudit task type"),
                "trace promptAudit task type");
        boolean promptMutationAllowed = requiredBoolean(
                promptAudit.path("mutationAllowed"), "trace promptAudit mutationAllowed");
        String actionObligation = required(QualificationJson.requiredText(
                promptAudit.path("actionObligation"), "trace action obligation"),
                "trace action obligation");
        if (!taskType.equals(promptTaskType) || mutationAllowed != promptMutationAllowed) {
            throw new IllegalArgumentException(
                    "trace taskContract and promptAudit authority disagree");
        }
        Set<String> providerToolSurface = Set.of();
        Set<String> providerPromptSurface = Set.of();
        if (exactToolLineage) {
            JsonNode toolSurface = requiredObject(
                    root.path("toolSurface"), "trace toolSurface");
            Set<String> selectedNativeTools = requiredToolNames(
                    toolSurface.path("nativeTools"), "trace toolSurface nativeTools");
            Set<String> selectedPromptTools = requiredToolNames(
                    toolSurface.path("promptTools"), "trace toolSurface promptTools");
            Set<String> auditedNativeTools = requiredToolNames(
                    promptAudit.path("nativeTools"), "trace promptAudit nativeTools");
            Set<String> auditedPromptTools = requiredToolNames(
                    promptAudit.path("promptTools"), "trace promptAudit promptTools");
            if (!selectedNativeTools.equals(auditedNativeTools)
                    || !selectedPromptTools.equals(auditedPromptTools)) {
                throw new IllegalArgumentException(
                        "trace selected tool surface and prompt audit disagree");
            }
            // Prompt-only descriptors remain plan-time evidence until a
            // context/provider-body manifest binds their exact final bytes.
            // Native tools can be matched to the selected attempt's exact
            // serialized provider-body receipt.
            providerToolSurface = Set.copyOf(selectedNativeTools);
            providerPromptSurface = Set.copyOf(selectedPromptTools);
        }
        String terminalState = required(QualificationJson.requiredText(
                root.path("outcome").path("classification"),
                "trace outcome classification"), "trace outcome classification");
        String outcomeStatus = required(QualificationJson.requiredText(
                root.path("outcome").path("status"), "trace outcome status"),
                "trace outcome status");
        String verificationStatus = required(QualificationJson.requiredText(
                root.path("verification").path("status"), "trace verification status"),
                "trace verification status");
        JsonNode events = root.path("events");
        if (!events.isArray()) throw new IllegalArgumentException("trace events must be an array");
        QualificationProviderAttemptLedger.Ledger providerAttempts =
                QualificationProviderAttemptLedger.parse(root);
        if (exactToolLineage) {
            validateAuthoritySurfacesWithinPlan(
                    providerAttempts, providerToolSurface, providerPromptSurface);
        }

        List<MutableCall> calls = new ArrayList<>();
        boolean approvalGranted = false;
        boolean approvalDenied = false;
        boolean boundedFileLookupNegative = false;
        boolean missingEvidenceContainmentEvent = false;
        String generationAbortReasonCode = "";
        boolean generationAbortPartialOutput = false;
        int generationAbortEvents = 0;
        String answerLineageSourceHash = "";
        int answerLineageSourceChars = -1;
        String answerLineageCurrentHash = "";
        int answerLineageCurrentChars = -1;
        boolean answerRestorationObserved = false;
        Set<String> suppressedProviderResponses = new HashSet<>();
        Set<String> consumedExecutionAuthorities = new HashSet<>();
        List<IndexedTraceEvent> requestObligationEvents = new ArrayList<>();
        List<IndexedTraceEvent> verificationCompletedEvents = new ArrayList<>();
        List<IndexedTraceEvent> outcomeEvaluatedEvents = new ArrayList<>();
        List<ToolOutcomeClassificationFact> toolOutcomeClassifications =
                new ArrayList<>();
        List<MutationRecoveryClaim> mutationRecoveryClaims = new ArrayList<>();
        for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
            JsonNode event = events.get(eventIndex);
            String type = QualificationJson.requiredText(
                    event.path("type"), "trace event type");
            String persistedToolName = QualificationJson.optionalText(
                    event.path("toolName"), "trace event toolName");
            if (exactExecutionAuthority
                    && requiresCanonicalToolIdentity(type)
                    && !ProviderResponseCapture.toolNameHash(persistedToolName).equals(
                            requiredHash(
                                    event.path("data").path("canonicalToolNameHash"),
                                    type + " canonical tool-name hash"))) {
                throw new IllegalArgumentException(
                        type + " canonical tool-name identity does not match its event");
            }
            String tool = canonicalTool(persistedToolName);
            String pathHint = normalizedPathHint(QualificationJson.optionalText(
                    event.path("data").path("pathHint"), "trace event pathHint"));
            if (type.equals("REQUEST_OBLIGATION_COMPILED")) {
                requestObligationEvents.add(new IndexedTraceEvent(event, eventIndex));
            } else if (type.equals("VERIFICATION_COMPLETED")) {
                verificationCompletedEvents.add(new IndexedTraceEvent(event, eventIndex));
            } else if (type.equals("OUTCOME_EVALUATED")) {
                outcomeEvaluatedEvents.add(new IndexedTraceEvent(event, eventIndex));
            } else if (type.equals("TOOL_OUTCOME_CLASSIFIED")) {
                if (traceSchemaVersion
                        != QualificationProviderAttemptLedger.PROCESS_TRACE_SCHEMA_VERSION) {
                    throw new IllegalArgumentException(
                            "typed tool outcome evidence requires trace schemaVersion 5");
                }
                ToolOutcomeClassificationFact classification =
                        toolOutcomeClassificationFact(event, eventIndex);
                if (toolOutcomeClassifications.stream().anyMatch(existing ->
                        existing.toolCallId().equals(classification.toolCallId()))) {
                    throw new IllegalArgumentException(
                            "trace classifies one tool outcome more than once: "
                                    + classification.toolCallId());
                }
                toolOutcomeClassifications.add(classification);
            } else if (type.equals("MUTATION_FAILURE_RECOVERY_EVALUATED")) {
                if (traceSchemaVersion
                        != QualificationProviderAttemptLedger.PROCESS_TRACE_SCHEMA_VERSION) {
                    throw new IllegalArgumentException(
                            "mutation recovery evidence requires trace schemaVersion 5");
                }
                mutationRecoveryClaims.add(mutationRecoveryClaim(event, eventIndex));
            } else if (type.equals("TOOL_CALL_PARSED")) {
                if (tool.isBlank()) throw new IllegalArgumentException("parsed tool event lacks toolName");
                ProviderLink providerLink = providerLink(
                        event.path("data"), providerAttempts, eventIndex);
                String toolCallId = exactToolLineage
                        ? requiredToolCallId(event.path("data"), "parsed tool call")
                        : "";
                ExecutionAuthority executionAuthority = exactExecutionAuthority
                        ? executionAuthority(
                                event.path("data"),
                                providerLink,
                                providerAttempts,
                                traceId,
                                toolCallId,
                                tool)
                        : ExecutionAuthority.legacy();
                if (exactExecutionAuthority
                        && !consumedExecutionAuthorities.add(
                                executionAuthority.sourceIdentity())) {
                    throw new IllegalArgumentException(
                            "trace reuses executable tool authority: "
                                    + executionAuthority.sourceIdentity());
                }
                if (exactToolLineage && calls.stream().anyMatch(
                        call -> call.toolCallId.equals(toolCallId))) {
                    throw new IllegalArgumentException(
                            "trace reuses parsed tool call id: " + toolCallId);
                }
                calls.add(new MutableCall(
                        tool,
                        pathHint,
                        providerLink.generationId(),
                        providerLink.attemptId(),
                        providerLink.nativeDecisionBound(),
                        toolCallId,
                        exactToolLineage
                                ? requiredHash(event.path("data").path("toolNameHash"),
                                        "parsed tool name hash")
                                : "",
                        exactToolLineage
                                ? requiredHash(event.path("data").path("argumentsHash"),
                                        "parsed tool arguments hash")
                                : "",
                        executionAuthority.kind(),
                        executionAuthority.runtimeReason(),
                        eventIndex));
            } else if (type.equals("TOOL_CALL_TRANSFORMED")) {
                if (!exactToolLineage) {
                    throw new IllegalArgumentException(
                            "tool transformation requires trace schemaVersion 3 or newer");
                }
                applyTransformation(
                        calls,
                        event,
                        tool,
                        pathHint,
                        providerAttempts,
                        eventIndex);
            } else if (type.equals("ANSWER_TRANSFORMED")) {
                AnswerTransformation transformation = validateAnswerTransformation(event);
                if (answerLineageSourceHash.isBlank()) {
                    answerLineageSourceHash = transformation.fromHash();
                    answerLineageSourceChars = transformation.fromChars();
                } else if (!answerLineageCurrentHash.equals(transformation.fromHash())
                        || answerLineageCurrentChars != transformation.fromChars()) {
                    throw new IllegalArgumentException(
                            "answer transformation lineage is not contiguous");
                }
                if (transformation.fromHash().equals(transformation.answerHash())
                        && transformation.fromChars() == transformation.answerChars()) {
                    throw new IllegalArgumentException(
                            "answer transformation must change the exact answer");
                }
                answerLineageCurrentHash = transformation.answerHash();
                answerLineageCurrentChars = transformation.answerChars();
            } else if (type.equals("ANSWER_CANDIDATE_RESTORED")) {
                AnswerRestoration restoration = validateAnswerRestoration(event);
                answerRestorationObserved = true;
                answerLineageSourceHash = restoration.answerHash();
                answerLineageSourceChars = restoration.answerChars();
                answerLineageCurrentHash = restoration.answerHash();
                answerLineageCurrentChars = restoration.answerChars();
            } else if (type.equals("PROVIDER_TOOL_CALLS_SUPPRESSED")) {
                String suppressedResponse = validateProviderToolSuppression(
                        event, providerAttempts, eventIndex);
                if (!suppressedProviderResponses.add(suppressedResponse)) {
                    throw new IllegalArgumentException(
                            "trace suppresses one provider response more than once");
                }
            } else if (type.equals("TOOL_CALL_BLOCKED") && !tool.isBlank()) {
                MutableCall match = exactToolLineage
                        ? exactPending(calls, event.path("data"), tool, pathHint,
                                "blocked tool call")
                        : uniquePending(calls, tool, pathHint);
                if (match == null) {
                    throw new IllegalArgumentException("blocked tool call lacks a parsed call: " + tool);
                }
                requireCurrentProviderAuthority(
                        match, providerAttempts, eventIndex, "blocked tool call");
                match.settled = true;
                match.settlement = CallSettlement.BLOCKED;
                match.settlementEventIndex = eventIndex;
            } else if (type.equals("TOOL_EXECUTED")) {
                MutableCall match = exactToolLineage
                        ? exactPending(calls, event.path("data"), tool, pathHint,
                                "tool execution")
                        : uniquePending(calls, tool, pathHint);
                if (match == null) {
                    throw new IllegalArgumentException("tool execution lacks a parsed call: " + tool);
                }
                requireCurrentProviderAuthority(
                        match, providerAttempts, eventIndex, "tool execution");
                if (exactExecutionAuthority
                        && match.executionAuthorityKind
                                == ExecutionAuthorityKind.LEGACY) {
                    throw new IllegalArgumentException(
                            "tool execution lacks executable authority: " + tool);
                }
                match.settled = true;
                match.executed = QualificationJson.requiredBoolean(
                        event.path("data").path("success"), "tool execution success");
                match.settlement = match.executed
                        ? CallSettlement.EXECUTED_SUCCESS
                        : CallSettlement.EXECUTED_FAILURE;
                match.settlementEventIndex = eventIndex;
                if (match.executed) {
                    match.sourcePaths.addAll(sourcePaths(event.path("data"), pathHint));
                }
            } else if (type.equals("TOOL_CALL_AUTHORITY_REJECTED")) {
                validateAuthorityRejection(event.path("data"));
            } else if (type.equals("APPROVAL_REQUIRED")) {
                bindApproval(calls, event.path("data"), tool, pathHint,
                        ApprovalDisposition.NONE, true, exactToolLineage,
                        providerAttempts, eventIndex);
            } else if (type.equals("APPROVAL_GRANTED")) {
                approvalGranted = true;
                bindApproval(calls, event.path("data"), tool, pathHint,
                        ApprovalDisposition.GRANTED, false, exactToolLineage,
                        providerAttempts, eventIndex);
            } else if (type.equals("APPROVAL_DENIED")) {
                approvalDenied = true;
                bindApproval(calls, event.path("data"), tool, pathHint,
                        ApprovalDisposition.DENIED, false, exactToolLineage,
                        providerAttempts, eventIndex);
            } else if (type.endsWith("APPROVAL_GRANTED")) {
                approvalGranted = true;
            } else if (type.endsWith("APPROVAL_DENIED")) {
                approvalDenied = true;
            } else if (type.equals("PROTOCOL_SANITIZED")
                    && FileLookupAnswerGuard.TRACE_REASON.equals(
                    QualificationJson.optionalText(
                            event.path("data").path("reason"), "protocol reason"))) {
                boundedFileLookupNegative = true;
            } else if (type.equals("PROTOCOL_SANITIZED")
                    && EvidenceContainmentAnswerGuard.TRACE_REASON.equals(
                    QualificationJson.optionalText(
                            event.path("data").path("reason"), "protocol reason"))) {
                missingEvidenceContainmentEvent = true;
            } else if (type.equals("MODEL_GENERATION_ABORTED")) {
                generationAbortEvents++;
                if (generationAbortEvents > 1) {
                    throw new IllegalArgumentException("trace has multiple model generation abort events");
                }
                JsonNode data = requiredObject(event.path("data"), "model generation abort data");
                generationAbortReasonCode = required(QualificationJson.requiredText(
                        data.path("reasonCode"), "model generation abort reason code"),
                        "model generation abort reason code");
                if (!GENERATION_ABORT_REASON_CODES.contains(generationAbortReasonCode)) {
                    throw new IllegalArgumentException(
                            "model generation abort reason code is not canonical: "
                                    + generationAbortReasonCode);
                }
                generationAbortPartialOutput = requiredBoolean(
                        data.path("partialOutput"), "model generation abort partialOutput");
            }
        }
        validateToolOutcomeClassifications(toolOutcomeClassifications, calls);

        boolean missingEvidenceWarning = false;
        JsonNode warnings = root.path("warnings");
        if (warnings.isArray()) {
            for (JsonNode warning : warnings) {
                if ("MISSING_EVIDENCE".equals(QualificationJson.optionalText(
                        warning.path("code"), "trace warning code"))) {
                    missingEvidenceWarning = true;
                    break;
                }
            }
        }

        Set<String> expectedTools = new HashSet<>();
        scenario.expectedDecisions().forEach(decision -> expectedTools.addAll(decision.allowedTools()));
        boolean mutationLike = scenario.category() == QualificationProtocol.ScenarioCategory.MUTATION
                || scenario.category() == QualificationProtocol.ScenarioCategory.REPAIR
                || scenario.category() == QualificationProtocol.ScenarioCategory.INTERRUPTION;
        List<Call> frozenCalls = calls.stream()
                .map(call -> new Call(call.toolName, call.pathHint, call.executed,
                        expectedTools.contains(call.toolName)
                                || (mutationLike && MUTATION_INSPECTION_AUXILIARIES.contains(call.toolName)),
                        List.copyOf(call.sourcePaths), call.approvalDisposition,
                        call.providerGenerationId, call.providerAttemptId,
                        call.nativeProviderDecisionBound, call.settlement,
                        call.decisionToolName, call.runtimeTransformed,
                        call.toolCallId))
                .toList();
        List<MutationRecoveryFact> mutationRecoveries = bindMutationRecoveries(
                mutationRecoveryClaims,
                requestObligationEvents,
                verificationCompletedEvents,
                outcomeEvaluatedEvents,
                toolOutcomeClassifications,
                calls,
                terminalState,
                outcomeStatus,
                verificationStatus,
                root.path("outcome").path("mutationStatus"),
                root.path("turnNumber"));
        boolean runtimeAnswerTransformed = answerRestorationObserved
                || (!answerLineageSourceHash.isBlank()
                        && (!answerLineageSourceHash.equals(answerLineageCurrentHash)
                            || answerLineageSourceChars != answerLineageCurrentChars));
        return new Facts(traceId, modelId, taskType, mutationAllowed, actionObligation,
                terminalState, outcomeStatus, verificationStatus,
                frozenCalls,
                decisions(
                        scenario,
                        frozenCalls,
                        !suppressedProviderResponses.isEmpty(),
                        selectedProviderToolCallPresent(providerAttempts)),
                approvalGranted, approvalDenied,
                boundedFileLookupNegative,
                missingEvidenceContainmentEvent && missingEvidenceWarning,
                generationAbortReasonCode,
                generationAbortPartialOutput,
                providerAttempts,
                runtimeAnswerTransformed,
                providerToolSurface,
                mutationRecoveries);
    }

    private static void rejectIncompleteTerminalProjection(JsonNode events) {
        if (!events.isArray()) return;
        for (JsonNode event : events) {
            if (event != null
                    && "TURN_EVIDENCE_PROJECTION".equals(
                            QualificationJson.optionalText(
                                    event.get("type"), "trace events[].type"))) {
                throw new IllegalArgumentException(
                        "trace carries an explicitly incomplete terminal evidence projection");
            }
        }
    }

    private record RequestObligationTraceFact(
            String canonicalSha256,
            String derivationStatus,
            List<String> requiredMutationTargets,
            int eventIndex) {}

    private record VerificationTraceFact(String status, int eventIndex) {}

    private record IndexedTraceEvent(JsonNode event, int eventIndex) {}

    private record ToolOutcomeClassificationFact(
            int schemaVersion,
            int outcomeOrdinal,
            String toolCallId,
            boolean success,
            boolean mutating,
            boolean denied,
            String errorCode,
            ToolFailureReason failureReason,
            String mutationEvidenceKind,
            int eventIndex) {}

    private record MutationRecoveryClaim(
            int schemaVersion,
            int failureOrdinal,
            int repairOrdinal,
            String failureToolCallId,
            String repairToolCallId,
            MutationRecoveryAssessment.Decision decision,
            boolean resolved,
            boolean recovered,
            ToolFailureReason failureReason,
            String targetHint,
            String targetSha256,
            String requestObligationSha256,
            String verifiedRequestObligationSha256,
            TaskVerificationEvidenceSource verificationSource,
            TaskVerificationStatus verificationStatus,
            List<String> coveredSuccessfulMutationToolCallIds,
            int eventIndex) {}

    private static RequestObligationTraceFact requestObligationTraceFact(
            JsonNode event,
            int eventIndex) {
        JsonNode data = requiredObject(
                event.path("data"), "request obligation trace data");
        String canonicalSha256 = requiredRawSha256(
                data.path("canonicalSha256"),
                "request obligation canonical SHA-256",
                false);
        String derivationStatus = required(QualificationJson.requiredText(
                data.path("derivationStatus"),
                "request obligation derivation status"),
                "request obligation derivation status");
        if (!derivationStatus.equals("COMPLETE")
                && !derivationStatus.equals("NOT_MIGRATED")) {
            throw new IllegalArgumentException(
                    "request obligation derivation status is not canonical: "
                            + derivationStatus);
        }
        List<String> requiredMutationTargets = obligationTargets(
                data.path("requiredMutationTargets"),
                "request obligation required mutation targets");
        return new RequestObligationTraceFact(
                canonicalSha256,
                derivationStatus,
                requiredMutationTargets,
                eventIndex);
    }

    private static VerificationTraceFact verificationTraceFact(
            JsonNode event,
            int eventIndex) {
        JsonNode data = requiredObject(
                event.path("data"), "verification completed data");
        TaskVerificationStatus status = QualificationJson.requiredEnum(
                data.path("status"),
                TaskVerificationStatus.class,
                "verification completed status");
        return new VerificationTraceFact(status.name(), eventIndex);
    }

    private static ToolOutcomeClassificationFact toolOutcomeClassificationFact(
            JsonNode event,
            int eventIndex) {
        JsonNode data = requiredObject(
                event.path("data"), "typed tool outcome data");
        int schemaVersion = QualificationJson.requiredInt(
                data.path("schemaVersion"),
                "typed tool outcome schemaVersion");
        if (schemaVersion == 1) {
            requireExactKeys(
                    data,
                    TOOL_OUTCOME_CLASSIFIED_DATA_KEYS_V1,
                    "typed tool outcome data");
        } else if (schemaVersion == 2) {
            requireExactKeys(
                    data,
                    TOOL_OUTCOME_CLASSIFIED_DATA_KEYS_V2,
                    "typed tool outcome data");
        } else {
            throw new IllegalArgumentException(
                    "typed tool outcome schemaVersion must be 1 or 2");
        }
        int outcomeOrdinal = QualificationJson.requiredInt(
                data.path("outcomeOrdinal"),
                "typed tool outcome ordinal");
        if (outcomeOrdinal <= 0) {
            throw new IllegalArgumentException(
                    "typed tool outcome ordinal must be positive");
        }
        String toolCallId = exactText(
                data.path("toolCallId"), "typed tool outcome tool-call id");
        requireCanonicalToolCallId(
                toolCallId, "typed tool outcome tool-call id");
        boolean success = requiredBoolean(
                data.path("success"), "typed tool outcome success");
        boolean mutating = requiredBoolean(
                data.path("mutating"), "typed tool outcome mutating");
        boolean denied = requiredBoolean(
                data.path("denied"), "typed tool outcome denied");
        String errorCode = schemaVersion == 2
                ? exactText(
                        data.path("errorCode"),
                        "typed tool outcome error code")
                : "";
        ToolFailureReason failureReason = QualificationJson.requiredEnum(
                data.path("failureReason"),
                ToolFailureReason.class,
                "typed tool outcome failure reason");
        String mutationEvidenceKind = exactText(
                data.path("mutationEvidenceKind"),
                "typed tool outcome mutation evidence kind");
        if (!MUTATION_EVIDENCE_KINDS.contains(mutationEvidenceKind)) {
            throw new IllegalArgumentException(
                    "typed tool outcome mutation evidence kind is not canonical: "
                            + mutationEvidenceKind);
        }
        if (success
                && (denied
                    || !errorCode.isBlank()
                    || failureReason != ToolFailureReason.NONE)) {
            throw new IllegalArgumentException(
                    "successful typed tool outcome carries contradictory failure state");
        }
        if (!mutating && !"NONE".equals(mutationEvidenceKind)) {
            throw new IllegalArgumentException(
                    "non-mutating typed tool outcome carries mutation evidence");
        }
        return new ToolOutcomeClassificationFact(
                schemaVersion,
                outcomeOrdinal,
                toolCallId,
                success,
                mutating,
                denied,
                errorCode,
                failureReason,
                mutationEvidenceKind,
                eventIndex);
    }

    private static MutationRecoveryClaim mutationRecoveryClaim(
            JsonNode event,
            int eventIndex) {
        JsonNode data = requiredObject(
                event.path("data"), "mutation recovery data");
        int schemaVersion = QualificationJson.requiredInt(
                data.path("schemaVersion"), "mutation recovery schemaVersion");
        if (schemaVersion == 1) {
            requireExactKeys(
                    data,
                    MUTATION_RECOVERY_DATA_KEYS_V1,
                    "mutation recovery data");
        } else if (schemaVersion == 2) {
            requireExactKeys(
                    data,
                    MUTATION_RECOVERY_DATA_KEYS_V2,
                    "mutation recovery data");
        } else {
            throw new IllegalArgumentException(
                    "mutation recovery schemaVersion must be 1 or 2");
        }
        int failureOrdinal = QualificationJson.requiredInt(
                data.path("failureOrdinal"), "mutation recovery failure ordinal");
        int repairOrdinal = QualificationJson.requiredInt(
                data.path("repairOrdinal"), "mutation recovery repair ordinal");
        if (failureOrdinal <= 0 || repairOrdinal < 0) {
            throw new IllegalArgumentException(
                    "mutation recovery ordinals are invalid");
        }
        String failureToolCallId = exactText(
                data.path("failureToolCallId"),
                "mutation recovery failure tool-call id");
        String repairToolCallId = exactText(
                data.path("repairToolCallId"),
                "mutation recovery repair tool-call id");
        if (!failureToolCallId.isEmpty()) {
            requireCanonicalToolCallId(
                    failureToolCallId, "mutation recovery failure tool-call id");
        }
        if (!repairToolCallId.isEmpty()) {
            requireCanonicalToolCallId(
                    repairToolCallId, "mutation recovery repair tool-call id");
        }
        MutationRecoveryAssessment.Decision decision =
                QualificationJson.requiredEnum(
                        data.path("decision"),
                        MutationRecoveryAssessment.Decision.class,
                        "mutation recovery decision");
        boolean resolved = requiredBoolean(
                data.path("resolved"), "mutation recovery resolved");
        boolean recovered = requiredBoolean(
                data.path("recovered"), "mutation recovery recovered");
        ToolFailureReason failureReason = QualificationJson.requiredEnum(
                data.path("failureReason"),
                ToolFailureReason.class,
                "mutation recovery failure reason");
        String targetHint = exactText(
                data.path("targetHint"), "mutation recovery target hint");
        if (!targetHint.equals(normalizedPathHint(targetHint))) {
            throw new IllegalArgumentException(
                    "mutation recovery target hint is not canonical");
        }
        String targetSha256 = requiredHash(
                data.path("targetSha256"), "mutation recovery target SHA-256");
        if (!"<protected-path>".equals(targetHint)
                && !sha256(targetHint).equals(targetSha256)) {
            throw new IllegalArgumentException(
                    "mutation recovery target identity does not match its hint");
        }
        String requestObligationSha256 = requiredRawSha256(
                data.path("requestObligationSha256"),
                "mutation recovery request-obligation SHA-256",
                true);
        String verifiedRequestObligationSha256 = requiredRawSha256(
                data.path("verifiedRequestObligationSha256"),
                "mutation recovery verified request-obligation SHA-256",
                true);
        TaskVerificationEvidenceSource verificationSource =
                QualificationJson.requiredEnum(
                        data.path("verificationSource"),
                        TaskVerificationEvidenceSource.class,
                        "mutation recovery verification source");
        TaskVerificationStatus verificationStatus =
                QualificationJson.requiredEnum(
                        data.path("verificationStatus"),
                        TaskVerificationStatus.class,
                        "mutation recovery verification status");
        List<String> coveredSuccessfulMutationToolCallIds = schemaVersion == 2
                ? orderedUniqueToolCallIds(
                        data.path("coveredSuccessfulMutationToolCallIds"),
                        "mutation recovery covered successful mutation call ids")
                : List.of();

        boolean matched = decision
                == MutationRecoveryAssessment.Decision.RECOVERED_MATCHED_OBLIGATION;
        boolean redundant = decision
                == MutationRecoveryAssessment.Decision
                        .REDUNDANT_ALREADY_APPLIED_OPERATION;
        boolean typedRepairSequence = decision
                == MutationRecoveryAssessment.Decision
                        .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN;
        if (matched) {
            if (!resolved
                    || !recovered
                    || repairOrdinal <= failureOrdinal
                    || failureToolCallId.isBlank()
                    || repairToolCallId.isBlank()
                    || failureToolCallId.equals(repairToolCallId)) {
                throw new IllegalArgumentException(
                        "matched-obligation recovery has inconsistent resolution fields");
            }
            if (schemaVersion == 2
                    && !coveredSuccessfulMutationToolCallIds.contains(
                            repairToolCallId)) {
                throw new IllegalArgumentException(
                        "matched-obligation recovery receipt does not cover its repair");
            }
        } else if (redundant) {
            if (!resolved
                    || recovered
                    || repairOrdinal <= 0
                    || repairOrdinal >= failureOrdinal
                    || failureToolCallId.isBlank()
                    || repairToolCallId.isBlank()
                    || failureToolCallId.equals(repairToolCallId)) {
                throw new IllegalArgumentException(
                    "redundant-operation recovery has inconsistent resolution fields");
            }
        } else if (typedRepairSequence) {
            if (!resolved
                    || recovered
                    || repairOrdinal <= failureOrdinal
                    || failureToolCallId.isBlank()
                    || repairToolCallId.isBlank()
                    || failureToolCallId.equals(repairToolCallId)
                    || !coveredSuccessfulMutationToolCallIds.isEmpty()
                    || !verifiedRequestObligationSha256.isEmpty()) {
                throw new IllegalArgumentException(
                        "typed repair sequence has inconsistent unproven resolution fields");
            }
        } else if (resolved
                || recovered
                || repairOrdinal != 0
                || !repairToolCallId.isEmpty()) {
            throw new IllegalArgumentException(
                    "unresolved mutation recovery claims a repair");
        }
        return new MutationRecoveryClaim(
                schemaVersion,
                failureOrdinal,
                repairOrdinal,
                failureToolCallId,
                repairToolCallId,
                decision,
                resolved,
                recovered,
                failureReason,
                targetHint,
                targetSha256,
                requestObligationSha256,
                verifiedRequestObligationSha256,
                verificationSource,
                verificationStatus,
                coveredSuccessfulMutationToolCallIds,
                eventIndex);
    }

    private static List<MutationRecoveryFact> bindMutationRecoveries(
            List<MutationRecoveryClaim> claims,
            List<IndexedTraceEvent> requestObligationEvents,
            List<IndexedTraceEvent> verificationCompletedEvents,
            List<IndexedTraceEvent> outcomeEvaluatedEvents,
            List<ToolOutcomeClassificationFact> toolOutcomeClassifications,
            List<MutableCall> calls,
            String terminalState,
            String outcomeStatus,
            String rootVerificationStatus,
            JsonNode rootMutationStatus,
            JsonNode rootTurnNumber) {
        if (claims.isEmpty()) return List.of();
        int turnNumber = QualificationJson.requiredInt(
                rootTurnNumber, "trace turnNumber");
        if (turnNumber < 1) {
            throw new IllegalArgumentException(
                    "trace turnNumber must be positive");
        }
        validateExactCallSequence(calls, turnNumber);
        validateToolOutcomeClassifications(toolOutcomeClassifications, calls);
        List<MutationRecoveryFact> facts = new ArrayList<>();
        Set<String> evaluatedFailureCalls = new HashSet<>();
        for (MutationRecoveryClaim claim : claims) {
            int recoveryVerificationEventIndex = -1;
            if (claim.schemaVersion() == 2) {
                if (verificationCompletedEvents.size() != 1
                        || verificationCompletedEvents.getFirst().eventIndex()
                                >= claim.eventIndex()) {
                    throw new IllegalArgumentException(
                            "schema-v2 mutation recovery must follow one "
                                    + "verification event");
                }
                if (outcomeEvaluatedEvents.size() != 1
                        || claim.eventIndex()
                                >= outcomeEvaluatedEvents.getFirst().eventIndex()) {
                    throw new IllegalArgumentException(
                            "schema-v2 mutation recovery must precede one "
                                    + "terminal outcome event");
                }
                recoveryVerificationEventIndex =
                        verificationCompletedEvents.getFirst().eventIndex();
            }
            if (!claim.failureToolCallId().isBlank()
                    && !evaluatedFailureCalls.add(claim.failureToolCallId())) {
                throw new IllegalArgumentException(
                        "trace evaluates one mutation failure more than once: "
                                + claim.failureToolCallId());
            }
            MutableCall failure = claim.failureToolCallId().isBlank()
                    ? null
                    : settledCallById(
                            calls,
                            claim.failureToolCallId(),
                            "mutation recovery failure");
            if (failure != null) {
                if (claim.decision()
                        == MutationRecoveryAssessment.Decision.DENIED_OR_POLICY) {
                    requireDeniedMutationCall(failure, claim);
                } else {
                    requireFailedMutationCall(failure, claim);
                }
                bindUnresolvedFailureClassification(
                        claim,
                        failure,
                        toolOutcomeClassifications,
                        recoveryVerificationEventIndex);
            }
            if (claim.decision()
                    == MutationRecoveryAssessment.Decision
                            .RECOVERED_MATCHED_OBLIGATION) {
                facts.add(bindMatchedRecovery(
                        claim,
                        failure,
                        calls,
                        requestObligationEvents,
                        verificationCompletedEvents,
                        outcomeEvaluatedEvents,
                        toolOutcomeClassifications,
                        terminalState,
                        outcomeStatus,
                        rootVerificationStatus,
                        rootMutationStatus));
            } else if (claim.decision()
                    == MutationRecoveryAssessment.Decision
                            .REDUNDANT_ALREADY_APPLIED_OPERATION) {
                facts.add(bindRedundantOperation(
                        claim,
                        failure,
                        calls,
                        toolOutcomeClassifications));
            } else if (claim.decision()
                    == MutationRecoveryAssessment.Decision
                            .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN) {
                facts.add(bindTypedRepairSequence(
                        claim,
                        failure,
                        calls,
                        requestObligationEvents,
                        verificationCompletedEvents,
                        toolOutcomeClassifications,
                        terminalState,
                        outcomeStatus,
                        rootVerificationStatus,
                        rootMutationStatus));
            }
        }
        return List.copyOf(facts);
    }

    private static MutationRecoveryFact bindTypedRepairSequence(
            MutationRecoveryClaim claim,
            MutableCall failure,
            List<MutableCall> calls,
            List<IndexedTraceEvent> requestObligationEvents,
            List<IndexedTraceEvent> verificationCompletedEvents,
            List<ToolOutcomeClassificationFact> toolOutcomeClassifications,
            String terminalState,
            String outcomeStatus,
            String rootVerificationStatus,
            JsonNode rootMutationStatus
    ) {
        if (claim.schemaVersion() != 2
                || failure == null
                || !"talos.edit_file".equals(failure.toolName)
                || (claim.failureReason() != ToolFailureReason.EDIT_EMPTY_ARGUMENTS
                    && claim.failureReason()
                            != ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED)) {
            throw new IllegalArgumentException(
                    "typed repair sequence lacks one eligible exact edit failure");
        }
        MutableCall repair = settledCallById(
                calls,
                claim.repairToolCallId(),
                "typed repair sequence repair");
        if (repair.settlement != CallSettlement.EXECUTED_SUCCESS
                || repair.settlementEventIndex <= failure.settlementEventIndex
                || claim.eventIndex() <= repair.settlementEventIndex) {
            throw new IllegalArgumentException(
                    "typed repair sequence lacks one later successful mutation");
        }
        requireExactRecoveryTarget(claim, failure, repair);
        requireExactTypedRepairProtocol(
                claim,
                failure,
                repair,
                calls,
                toolOutcomeClassifications);

        if (requestObligationEvents.size() != 1) {
            throw new IllegalArgumentException(
                    "typed repair sequence requires one request obligation");
        }
        IndexedTraceEvent rawObligation = requestObligationEvents.getFirst();
        RequestObligationTraceFact obligation = requestObligationTraceFact(
                rawObligation.event(), rawObligation.eventIndex());
        if (!"NOT_MIGRATED".equals(obligation.derivationStatus())
                || obligation.eventIndex() >= failure.parsedEventIndex
                || claim.requestObligationSha256().isBlank()
                || !claim.requestObligationSha256().equals(
                        obligation.canonicalSha256())
                || !obligation.requiredMutationTargets().contains(
                        claim.targetHint())) {
            throw new IllegalArgumentException(
                    "typed repair sequence lacks expected-target authority");
        }

        if (verificationCompletedEvents.size() != 1) {
            throw new IllegalArgumentException(
                    "typed repair sequence requires one verification event");
        }
        IndexedTraceEvent rawVerification = verificationCompletedEvents.getFirst();
        VerificationTraceFact verification = verificationTraceFact(
                rawVerification.event(), rawVerification.eventIndex());
        if (claim.verificationSource()
                    != TaskVerificationEvidenceSource.POST_APPLY_STATIC
                || claim.verificationStatus() != TaskVerificationStatus.PASSED
                || !"PASSED".equals(rootVerificationStatus)
                || !"PASSED".equals(verification.status())
                || verification.eventIndex() <= repair.settlementEventIndex
                || verification.eventIndex() >= claim.eventIndex()) {
            throw new IllegalArgumentException(
                    "typed repair sequence verification is not authoritative");
        }
        String mutationStatus = exactText(
                rootMutationStatus,
                "trace outcome mutation status");
        if (!validMatchedRecoveryOutcome(
                terminalState,
                outcomeStatus,
                mutationStatus)) {
            throw new IllegalArgumentException(
                    "typed repair sequence disagrees with the trace outcome");
        }

        ToolOutcomeClassificationFact failureClassification =
                toolOutcomeClassificationById(
                        toolOutcomeClassifications,
                        claim.failureToolCallId());
        ToolOutcomeClassificationFact repairClassification =
                toolOutcomeClassificationById(
                        toolOutcomeClassifications,
                        claim.repairToolCallId());
        if (failureClassification == null || repairClassification == null) {
            throw new IllegalArgumentException(
                    "typed repair sequence lacks exact outcome classifications");
        }
        requireOutcomeOrdinal(
                toolOutcomeClassifications,
                failureClassification,
                claim.failureOrdinal(),
                "typed repair sequence failure");
        requireOutcomeOrdinal(
                toolOutcomeClassifications,
                repairClassification,
                claim.repairOrdinal(),
                "typed repair sequence repair");
        if (failureClassification.schemaVersion() != 2
                || failureClassification.success()
                || !failureClassification.mutating()
                || failureClassification.denied()
                || !ToolError.INVALID_PARAMS.equals(
                        failureClassification.errorCode())
                || failureClassification.failureReason()
                        != claim.failureReason()
                || !"NONE".equals(
                        failureClassification.mutationEvidenceKind())
                || failureClassification.eventIndex()
                        >= repair.parsedEventIndex
                || failureClassification.eventIndex()
                        >= verification.eventIndex()) {
            throw new IllegalArgumentException(
                    "typed repair sequence contradicts its failed outcome");
        }
        if (!typedRepairSuccess(
                claim.failureReason(),
                repair,
                repairClassification)
                || repairClassification.eventIndex()
                        <= repair.settlementEventIndex
                || repairClassification.eventIndex()
                        >= verification.eventIndex()) {
            throw new IllegalArgumentException(
                    "typed repair sequence contradicts its repair outcome");
        }

        long eligibleFailures = toolOutcomeClassifications.stream()
                .filter(classification ->
                        !classification.success()
                                && classification.mutating()
                                && !classification.denied()
                                && (classification.failureReason()
                                            == ToolFailureReason.EDIT_EMPTY_ARGUMENTS
                                    || classification.failureReason()
                                            == ToolFailureReason
                                                    .EDIT_FULL_REWRITE_REQUIRED))
                .count();
        if (eligibleFailures != 1) {
            throw new IllegalArgumentException(
                    "typed repair sequence does not contain exactly one eligible failure");
        }

        List<MutableCall> sameTargetSuccesses = calls.stream()
                .filter(call ->
                        call.settlement == CallSettlement.EXECUTED_SUCCESS
                                && ToolAliasPolicy.isMutating(call.toolName)
                                && claim.targetHint().equals(call.pathHint))
                .toList();
        if (sameTargetSuccesses.size() != 1
                || sameTargetSuccesses.getFirst() != repair) {
            throw new IllegalArgumentException(
                    "typed repair sequence does not contain one same-target success");
        }

        for (ToolOutcomeClassificationFact classification
                : toolOutcomeClassifications) {
            if (!classification.success() || !classification.mutating()) continue;
            MutableCall successfulCall = settledCallById(
                    calls,
                    classification.toolCallId(),
                    "typed repair sequence successful mutation");
            if (classification.schemaVersion() != 2
                    || classification.denied()
                    || !classification.errorCode().isBlank()
                    || classification.eventIndex() >= verification.eventIndex()
                    || successfulCall.pathHint.isBlank()
                    || "<protected-path>".equals(successfulCall.pathHint)
                    || !obligation.requiredMutationTargets().contains(
                            successfulCall.pathHint)
                    || !typedRepairOperation(
                            successfulCall,
                            classification)) {
                throw new IllegalArgumentException(
                        "typed repair sequence contains an unbound successful mutation");
            }
        }
        return new MutationRecoveryFact(
                claim.failureToolCallId(),
                claim.repairToolCallId(),
                claim.decision().name(),
                false,
                recoveryEvidenceReference(claim));
    }

    private static boolean typedRepairSuccess(
            ToolFailureReason failureReason,
            MutableCall repair,
            ToolOutcomeClassificationFact classification
    ) {
        if (classification == null
                || classification.schemaVersion() != 2
                || !classification.success()
                || !classification.mutating()
                || classification.denied()
                || !classification.errorCode().isBlank()
                || classification.failureReason() != ToolFailureReason.NONE) {
            return false;
        }
        if (failureReason == ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED) {
            return "talos.write_file".equals(repair.toolName)
                    && typedRepairOperation(repair, classification);
        }
        return ("talos.edit_file".equals(repair.toolName)
                        && "EXACT_EDIT_REPLACEMENT".equals(
                                classification.mutationEvidenceKind()))
                || ("talos.write_file".equals(repair.toolName)
                        && "FULL_WRITE_REPLACEMENT".equals(
                                classification.mutationEvidenceKind()));
    }

    private static boolean typedRepairOperation(
            MutableCall call,
            ToolOutcomeClassificationFact classification
    ) {
        if (call == null || classification == null) return false;
        return ("talos.edit_file".equals(call.toolName)
                        && "EXACT_EDIT_REPLACEMENT".equals(
                                classification.mutationEvidenceKind()))
                || ("talos.write_file".equals(call.toolName)
                        && ("FULL_WRITE_REPLACEMENT".equals(
                                    classification.mutationEvidenceKind())
                            || "NONE".equals(
                                    classification.mutationEvidenceKind())));
    }

    private static void requireExactTypedRepairProtocol(
            MutationRecoveryClaim claim,
            MutableCall failure,
            MutableCall repair,
            List<MutableCall> calls,
            List<ToolOutcomeClassificationFact> classifications
    ) {
        int failureIndex = calls.indexOf(failure);
        int repairIndex = calls.indexOf(repair);
        if (failureIndex < 0 || repairIndex < 0) {
            throw new IllegalArgumentException(
                    "typed repair sequence lacks exact call ordering");
        }
        if (claim.failureReason()
                == ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED) {
            if (repairIndex != failureIndex + 1) {
                throw new IllegalArgumentException(
                        "full-rewrite repair is not the direct redirected call");
            }
            return;
        }
        if (claim.failureReason() != ToolFailureReason.EDIT_EMPTY_ARGUMENTS
                || repairIndex != failureIndex + 2) {
            throw new IllegalArgumentException(
                    "empty-edit repair lacks one exact intervening read");
        }
        MutableCall read = calls.get(failureIndex + 1);
        ToolOutcomeClassificationFact readClassification =
                toolOutcomeClassificationById(
                        classifications,
                        read.toolCallId);
        if (!"talos.read_file".equals(read.toolName)
                || !claim.targetHint().equals(read.pathHint)
                || read.settlement != CallSettlement.EXECUTED_SUCCESS
                || read.settlementEventIndex <= failure.settlementEventIndex
                || read.settlementEventIndex >= repair.parsedEventIndex
                || readClassification == null
                || readClassification.schemaVersion() != 2
                || !readClassification.success()
                || readClassification.mutating()
                || readClassification.denied()
                || !readClassification.errorCode().isBlank()
                || readClassification.failureReason()
                        != ToolFailureReason.NONE
                || !"NONE".equals(
                        readClassification.mutationEvidenceKind())
                || readClassification.eventIndex()
                        <= read.settlementEventIndex
                || readClassification.eventIndex()
                        >= repair.parsedEventIndex) {
            throw new IllegalArgumentException(
                    "empty-edit repair lacks one exact successful same-target read");
        }
    }

    private static MutationRecoveryFact bindMatchedRecovery(
            MutationRecoveryClaim claim,
            MutableCall failure,
            List<MutableCall> calls,
            List<IndexedTraceEvent> requestObligationEvents,
            List<IndexedTraceEvent> verificationCompletedEvents,
            List<IndexedTraceEvent> outcomeEvaluatedEvents,
            List<ToolOutcomeClassificationFact> toolOutcomeClassifications,
            String terminalState,
            String outcomeStatus,
            String rootVerificationStatus,
            JsonNode rootMutationStatus) {
        if (failure == null
                || !MATCHED_RECOVERY_FAILURE_REASONS.contains(
                        claim.failureReason())) {
            throw new IllegalArgumentException(
                    "matched-obligation recovery lacks an eligible typed failure");
        }
        MutableCall repair = settledCallById(
                calls,
                claim.repairToolCallId(),
                "mutation recovery repair");
        if (repair.settlement != CallSettlement.EXECUTED_SUCCESS
                || !ToolAliasPolicy.isMutating(repair.toolName)
                || repair.settlementEventIndex <= failure.settlementEventIndex
                || claim.eventIndex() <= repair.settlementEventIndex) {
            throw new IllegalArgumentException(
                    "matched-obligation recovery lacks one later successful mutation");
        }
        requireExactRecoveryTarget(claim, failure, repair);
        if (requestObligationEvents.size() != 1) {
            throw new IllegalArgumentException(
                    "matched-obligation recovery requires one request obligation");
        }
        IndexedTraceEvent rawObligation = requestObligationEvents.getFirst();
        RequestObligationTraceFact obligation = requestObligationTraceFact(
                rawObligation.event(), rawObligation.eventIndex());
        if (!"COMPLETE".equals(obligation.derivationStatus())
                || obligation.eventIndex() >= failure.parsedEventIndex
                || claim.requestObligationSha256().isBlank()
                || claim.verifiedRequestObligationSha256().isBlank()
                || !claim.requestObligationSha256().equals(
                        claim.verifiedRequestObligationSha256())
                || !claim.requestObligationSha256().equals(
                        obligation.canonicalSha256())) {
            throw new IllegalArgumentException(
                    "matched-obligation recovery request identity is not proven");
        }
        if (verificationCompletedEvents.size() != 1) {
            throw new IllegalArgumentException(
                    "matched-obligation recovery requires one verification event");
        }
        IndexedTraceEvent rawVerification = verificationCompletedEvents.getFirst();
        VerificationTraceFact verification = verificationTraceFact(
                rawVerification.event(), rawVerification.eventIndex());
        if (claim.verificationSource()
                    != TaskVerificationEvidenceSource.POST_APPLY_STATIC
                || claim.verificationStatus() != TaskVerificationStatus.PASSED
                || !"PASSED".equals(rootVerificationStatus)
                || !"PASSED".equals(verification.status())
                || verification.eventIndex() <= repair.settlementEventIndex
                || verification.eventIndex() >= claim.eventIndex()) {
            throw new IllegalArgumentException(
                    "matched-obligation recovery verification is not authoritative");
        }
        String mutationStatus = exactText(
                rootMutationStatus,
                "trace outcome mutation status");
        if (!validMatchedRecoveryOutcome(
                terminalState,
                outcomeStatus,
                mutationStatus)) {
            throw new IllegalArgumentException(
                    "matched-obligation recovery disagrees with the trace outcome");
        }
        boolean laterSameTargetSuccess = calls.stream().anyMatch(call ->
                call != repair
                        && call.settlement == CallSettlement.EXECUTED_SUCCESS
                        && ToolAliasPolicy.isMutating(call.toolName)
                        && claim.targetHint().equals(call.pathHint)
                        && call.settlementEventIndex > repair.settlementEventIndex);
        if (laterSameTargetSuccess) {
            throw new IllegalArgumentException(
                    "matched-obligation recovery is invalidated by a later mutation");
        }
        ToolOutcomeClassificationFact failureClassification =
                toolOutcomeClassificationById(
                        toolOutcomeClassifications,
                        claim.failureToolCallId());
        ToolOutcomeClassificationFact repairClassification =
                toolOutcomeClassificationById(
                        toolOutcomeClassifications,
                        claim.repairToolCallId());
        if (failureClassification != null) {
            requireOutcomeOrdinal(
                    toolOutcomeClassifications,
                    failureClassification,
                    claim.failureOrdinal(),
                    "mutation recovery failure");
        }
        if (repairClassification != null) {
            requireOutcomeOrdinal(
                    toolOutcomeClassifications,
                    repairClassification,
                    claim.repairOrdinal(),
                    "mutation recovery repair");
        }
        if (failureClassification != null
                && (failureClassification.success()
                    || !failureClassification.mutating()
                    || failureClassification.denied()
                    || failureClassification.failureReason()
                            != claim.failureReason()
                    || failureClassification.eventIndex()
                            >= verification.eventIndex())) {
            throw new IllegalArgumentException(
                    "matched-obligation recovery contradicts its typed failed outcome");
        }
        if (repairClassification != null
                && (!repairClassification.success()
                    || !repairClassification.mutating()
                    || repairClassification.denied()
                    || repairClassification.failureReason()
                            != ToolFailureReason.NONE
                    || !STRUCTURED_MUTATION_EVIDENCE_KINDS.contains(
                            repairClassification.mutationEvidenceKind())
                    || repairClassification.eventIndex()
                            >= verification.eventIndex())) {
            throw new IllegalArgumentException(
                    "matched-obligation recovery contradicts its typed repair outcome");
        }
        boolean proven = false;
        if (claim.schemaVersion() == 2
                && failureClassification != null
                && repairClassification != null) {
            if (toolOutcomeClassifications.stream().anyMatch(classification ->
                    classification.success()
                            && classification.mutating()
                            && classification.eventIndex()
                                    > verification.eventIndex())) {
                throw new IllegalArgumentException(
                        "matched-obligation recovery has a successful mutation "
                                + "after verification");
            }
            List<String> successfulMutationsBeforeVerification =
                    toolOutcomeClassifications.stream()
                            .filter(classification ->
                                    classification.success()
                                            && classification.mutating()
                                            && classification.eventIndex()
                                                    < verification.eventIndex())
                            .map(ToolOutcomeClassificationFact::toolCallId)
                            .toList();
            if (!claim.coveredSuccessfulMutationToolCallIds().equals(
                    successfulMutationsBeforeVerification)) {
                throw new IllegalArgumentException(
                        "matched-obligation recovery receipt coverage disagrees "
                                + "with classified successful mutations");
            }
            proven = true;
        }
        return new MutationRecoveryFact(
                claim.failureToolCallId(),
                claim.repairToolCallId(),
                claim.decision().name(),
                proven,
                recoveryEvidenceReference(claim));
    }

    private static MutationRecoveryFact bindRedundantOperation(
            MutationRecoveryClaim claim,
            MutableCall failure,
            List<MutableCall> calls,
            List<ToolOutcomeClassificationFact> toolOutcomeClassifications) {
        if (failure == null) {
            throw new IllegalArgumentException(
                    "redundant-operation observation lacks its failed call");
        }
        MutableCall priorSuccess = settledCallById(
                calls,
                claim.repairToolCallId(),
                "redundant-operation prior success");
        if (priorSuccess.settlement != CallSettlement.EXECUTED_SUCCESS
                || !ToolAliasPolicy.isMutating(priorSuccess.toolName)
                || priorSuccess.settlementEventIndex >= failure.settlementEventIndex
                || claim.eventIndex() <= failure.settlementEventIndex) {
            throw new IllegalArgumentException(
                    "redundant-operation observation lacks one prior successful mutation");
        }
        ToolOutcomeClassificationFact failedClassification =
                toolOutcomeClassificationById(
                        toolOutcomeClassifications,
                        claim.failureToolCallId());
        ToolOutcomeClassificationFact priorClassification =
                toolOutcomeClassificationById(
                        toolOutcomeClassifications,
                        claim.repairToolCallId());
        if (failedClassification != null) {
            requireOutcomeOrdinal(
                    toolOutcomeClassifications,
                    failedClassification,
                    claim.failureOrdinal(),
                    "redundant-operation failure");
        }
        if (priorClassification != null) {
            requireOutcomeOrdinal(
                    toolOutcomeClassifications,
                    priorClassification,
                    claim.repairOrdinal(),
                    "redundant-operation prior success");
        }
        requireExactRecoveryTarget(claim, failure, priorSuccess);
        return new MutationRecoveryFact(
                claim.failureToolCallId(),
                claim.repairToolCallId(),
                claim.decision().name(),
                false,
                recoveryEvidenceReference(claim));
    }

    private static void requireFailedMutationCall(
            MutableCall failure,
            MutationRecoveryClaim claim) {
        if (!ToolAliasPolicy.isMutating(failure.toolName)
                || failure.approvalDisposition == ApprovalDisposition.DENIED
                || (failure.settlement != CallSettlement.BLOCKED
                    && failure.settlement != CallSettlement.EXECUTED_FAILURE)
                || failure.settlementEventIndex < 0
                || claim.eventIndex() <= failure.settlementEventIndex) {
            throw new IllegalArgumentException(
                    "mutation recovery does not bind one non-denied failed mutation");
        }
        if (!claim.targetHint().isBlank()
                && !"<protected-path>".equals(claim.targetHint())
                && !claim.targetHint().equals(failure.pathHint)) {
            throw new IllegalArgumentException(
                    "mutation recovery failure target disagrees with its event");
        }
    }

    private static void requireDeniedMutationCall(
            MutableCall failure,
            MutationRecoveryClaim claim) {
        if (!ToolAliasPolicy.isMutating(failure.toolName)
                || (failure.settlement != CallSettlement.BLOCKED
                    && failure.settlement != CallSettlement.EXECUTED_FAILURE)
                || failure.settlementEventIndex < 0
                || claim.eventIndex() <= failure.settlementEventIndex) {
            throw new IllegalArgumentException(
                    "denied mutation recovery claim does not bind one denied mutation");
        }
    }

    private static boolean validMatchedRecoveryOutcome(
            String terminalState,
            String outcomeStatus,
            String mutationStatus) {
        if ("SUCCEEDED".equals(mutationStatus)) {
            return "COMPLETED_VERIFIED".equals(terminalState)
                    && "COMPLETE".equals(outcomeStatus);
        }
        if (!"PARTIAL".equals(mutationStatus)) return false;
        return ("PARTIAL".equals(terminalState)
                        && "PARTIAL".equals(outcomeStatus))
                || (("BLOCKED_BY_APPROVAL".equals(terminalState)
                                || "BLOCKED_BY_POLICY".equals(terminalState))
                        && "BLOCKED".equals(outcomeStatus));
    }

    private static void bindUnresolvedFailureClassification(
            MutationRecoveryClaim claim,
            MutableCall failure,
            List<ToolOutcomeClassificationFact> classifications,
            int verificationEventIndex) {
        ToolOutcomeClassificationFact classification =
                toolOutcomeClassificationById(
                        classifications,
                        claim.failureToolCallId());
        if (classification == null) return;
        requireOutcomeOrdinal(
                classifications,
                classification,
                claim.failureOrdinal(),
                "mutation recovery failure");
        boolean denied = claim.decision()
                == MutationRecoveryAssessment.Decision.DENIED_OR_POLICY;
        if (classification.success()
                || !classification.mutating()
                || classification.denied() != denied
                || classification.failureReason() != claim.failureReason()
                || (verificationEventIndex >= 0
                    && classification.eventIndex() >= verificationEventIndex)
                || classification.eventIndex() >= claim.eventIndex()) {
            throw new IllegalArgumentException(
                    "mutation recovery claim contradicts its typed failed outcome");
        }
    }

    private static void requireExactRecoveryTarget(
            MutationRecoveryClaim claim,
            MutableCall failure,
            MutableCall repair) {
        if (claim.targetHint().isBlank()
                || "<protected-path>".equals(claim.targetHint())
                || !sha256(claim.targetHint()).equals(claim.targetSha256())
                || !claim.targetHint().equals(failure.pathHint)
                || !claim.targetHint().equals(repair.pathHint)) {
            throw new IllegalArgumentException(
                    "mutation recovery exact same-target identity is not proven");
        }
    }

    private static MutableCall settledCallById(
            List<MutableCall> calls,
            String toolCallId,
            String label) {
        List<MutableCall> matches = calls.stream()
                .filter(call -> call.toolCallId.equals(toolCallId))
                .toList();
        if (matches.size() != 1
                || !matches.getFirst().settled
                || matches.getFirst().settlement == CallSettlement.UNSETTLED) {
            throw new IllegalArgumentException(
                    label + " lacks one settled parsed call id: " + toolCallId);
        }
        return matches.getFirst();
    }

    private static void validateExactCallSequence(
            List<MutableCall> calls,
            int turnNumber) {
        for (int index = 0; index < calls.size(); index++) {
            String expected = "call-" + turnNumber + "-" + (index + 1);
            if (!expected.equals(calls.get(index).toolCallId)) {
                throw new IllegalArgumentException(
                        "trace tool-call lineage is not monotonic for turn "
                                + turnNumber + ": expected " + expected);
            }
        }
    }

    private static void validateToolOutcomeClassifications(
            List<ToolOutcomeClassificationFact> classifications,
            List<MutableCall> calls) {
        int previousOutcomeOrdinal = 0;
        Set<String> classifiedCallIds = new HashSet<>();
        for (ToolOutcomeClassificationFact classification : classifications) {
            if (classification.outcomeOrdinal() <= previousOutcomeOrdinal) {
                throw new IllegalArgumentException(
                        "typed tool outcome ordinals are not strictly increasing");
            }
            previousOutcomeOrdinal = classification.outcomeOrdinal();
            if (!classifiedCallIds.add(classification.toolCallId())) {
                throw new IllegalArgumentException(
                        "settled parsed call has more than one typed outcome: "
                                + classification.toolCallId());
            }
            MutableCall call = settledCallById(
                    calls,
                    classification.toolCallId(),
                    "typed tool outcome");
            boolean executedSuccess =
                    call.settlement == CallSettlement.EXECUTED_SUCCESS;
            if (classification.eventIndex() <= call.settlementEventIndex
                    || classification.success() != executedSuccess
                    || classification.mutating()
                            != ToolAliasPolicy.isMutating(call.toolName)) {
                throw new IllegalArgumentException(
                        "typed tool outcome disagrees with its settled call: "
                                + classification.toolCallId());
            }
        }
        if (!classifications.isEmpty()) {
            for (MutableCall call : calls) {
                if (call.settlement == CallSettlement.UNSETTLED
                        || (call.settlement == CallSettlement.BLOCKED
                            && !ToolAliasPolicy.isMutating(call.toolName))) {
                    continue;
                }
                if (toolOutcomeClassificationById(
                                classifications, call.toolCallId)
                        == null) {
                    throw new IllegalArgumentException(
                            "settled parsed call lacks one typed outcome: "
                                    + call.toolCallId);
                }
            }
        }
    }

    private static ToolOutcomeClassificationFact toolOutcomeClassificationById(
            List<ToolOutcomeClassificationFact> classifications,
            String toolCallId) {
        return classifications.stream()
                .filter(classification ->
                        classification.toolCallId().equals(toolCallId))
                .findFirst()
                .orElse(null);
    }

    private static void requireOutcomeOrdinal(
            List<ToolOutcomeClassificationFact> classifications,
            ToolOutcomeClassificationFact classification,
            int claimedOrdinal,
            String label) {
        if (!classifications.contains(classification)
                || classification.outcomeOrdinal() != claimedOrdinal) {
            throw new IllegalArgumentException(
                    label + " ordinal disagrees with typed outcome order");
        }
    }

    private static String recoveryEvidenceReference(MutationRecoveryClaim claim) {
        return "trace.json#MUTATION_FAILURE_RECOVERY_EVALUATED[failureToolCallId="
                + claim.failureToolCallId() + "]";
    }

    private static String exactText(JsonNode value, String label) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(label + " is missing or invalid");
        }
        String text = value.textValue();
        if (!text.equals(text.strip())) {
            throw new IllegalArgumentException(label + " is not canonical");
        }
        return text;
    }

    private static String requiredRawSha256(
            JsonNode value,
            String label,
            boolean allowEmpty) {
        String hash = exactText(value, label);
        if ((!allowEmpty || !hash.isEmpty())
                && !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return hash;
    }

    private static void requireCanonicalToolCallId(
            String toolCallId,
            String label) {
        if (!toolCallId.matches("call-[1-9][0-9]*-[1-9][0-9]*")) {
            throw new IllegalArgumentException(
                    label + " is not canonical: " + toolCallId);
        }
    }

    private static List<String> orderedUniqueToolCallIds(
            JsonNode values,
            String label) {
        if (values == null || !values.isArray()) {
            throw new IllegalArgumentException(label + " is missing or invalid");
        }
        List<String> ordered = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            String toolCallId = exactText(value, label + "[]");
            requireCanonicalToolCallId(toolCallId, label + "[]");
            if (!unique.add(toolCallId)) {
                throw new IllegalArgumentException(
                        label + " contains a duplicate: " + toolCallId);
            }
            ordered.add(toolCallId);
        }
        return List.copyOf(ordered);
    }

    private static List<String> obligationTargets(
            JsonNode values,
            String label
    ) {
        if (values == null || !values.isArray()) {
            throw new IllegalArgumentException(label + " is missing or invalid");
        }
        List<String> targets = new ArrayList<>();
        Set<String> visibleTargets = new HashSet<>();
        for (JsonNode value : values) {
            String target = exactText(value, label + "[]");
            if ("<protected-path>".equals(target)) {
                targets.add(target);
                continue;
            }
            String canonical;
            try {
                canonical = RequestObligation.canonicalTarget(target);
            } catch (IllegalArgumentException unsafe) {
                throw new IllegalArgumentException(
                        label + " contains an unsafe target", unsafe);
            }
            if (!canonical.equals(target) || !visibleTargets.add(target)) {
                throw new IllegalArgumentException(
                        label + " contains a noncanonical or duplicate target: "
                                + target);
            }
            targets.add(target);
        }
        return List.copyOf(targets);
    }

    private static void validateAuthoritySurfacesWithinPlan(
            QualificationProviderAttemptLedger.Ledger ledger,
            Set<String> selectedNativeTools,
            Set<String> selectedPromptTools
    ) {
        Set<String> nativeHashes = selectedNativeTools.stream()
                .map(ProviderResponseCapture::toolNameHash)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> promptHashes = selectedPromptTools.stream()
                .map(ProviderResponseCapture::toolNameHash)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (QualificationProviderAttemptLedger.Generation generation : ledger.generations()) {
            Set<String> planned = switch (generation.toolAuthorityMode()) {
                case NONE -> Set.of();
                case PROVIDER_NATIVE -> nativeHashes;
                case PROVIDER_TEXT_JSON -> promptHashes;
            };
            for (QualificationProviderAttemptLedger.Attempt attempt : generation.attempts()) {
                if (!attempt.requestDispatched()) continue;
                QualificationProviderAttemptLedger.AuthoritySurface surface =
                        attempt.authoritySurface();
                if (generation.toolAuthorityMode()
                        == ProviderResponseCapture.ToolAuthorityMode.NONE) {
                    if (surface.bound()) {
                        throw new IllegalArgumentException(
                                "tool-authority NONE attempt carries a selected surface");
                    }
                    continue;
                }
                if (!surface.bound()
                        || !planned.containsAll(surface.selectedToolNameHashes())) {
                    throw new IllegalArgumentException(
                            "provider attempt authority surface broadens the planned tool surface");
                }
            }
        }
    }

    private static Set<String> requiredToolNames(JsonNode values, String label) {
        if (values == null || !values.isArray()) {
            throw new IllegalArgumentException(label + " must be an array");
        }
        java.util.LinkedHashSet<String> tools = new java.util.LinkedHashSet<>();
        for (JsonNode value : values) {
            String tool = required(QualificationJson.requiredText(value, label + " entry"),
                    label + " entry");
            if (tool.isBlank() || !tool.equals(tool.strip()) || tool.length() > 256
                    || !tools.add(tool)) {
                throw new IllegalArgumentException(
                        label + " contains a blank, normalized, oversized, or duplicate tool");
            }
        }
        return Set.copyOf(tools);
    }

    private static AnswerTransformation validateAnswerTransformation(JsonNode event) {
        JsonNode data = requiredObject(event.path("data"), "answer transformation data");
        Set<String> keys = new HashSet<>();
        data.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(Set.of(
                "fromAnswerHash", "fromAnswerChars",
                "answerHash", "answerChars", "reasonCode"))) {
            throw new IllegalArgumentException("answer transformation data has unexpected fields");
        }
        String fromHash = requiredHash(
                data.path("fromAnswerHash"), "answer transformation source hash");
        String answerHash = requiredHash(
                data.path("answerHash"), "answer transformation target hash");
        if (!data.path("fromAnswerChars").isIntegralNumber()
                || !data.path("fromAnswerChars").canConvertToInt()
                || data.path("fromAnswerChars").intValue() < 0
                || !data.path("answerChars").isIntegralNumber()
                || !data.path("answerChars").canConvertToInt()
                || data.path("answerChars").intValue() < 0) {
            throw new IllegalArgumentException("answer transformation character counts are invalid");
        }
        String reason = required(QualificationJson.requiredText(
                data.path("reasonCode"), "answer transformation reason code"),
                "answer transformation reason code");
        if (!ANSWER_TRANSFORMATION_REASON_CODES.contains(reason)) {
            throw new IllegalArgumentException(
                    "answer transformation reason is not canonical: " + reason);
        }
        return new AnswerTransformation(
                fromHash,
                data.path("fromAnswerChars").intValue(),
                answerHash,
                data.path("answerChars").intValue(),
                reason);
    }

    private static AnswerRestoration validateAnswerRestoration(JsonNode event) {
        JsonNode data = requiredObject(
                event.path("data"), "answer candidate restoration data");
        Set<String> keys = new HashSet<>();
        data.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(Set.of(
                "answerHash", "answerChars", "origin",
                "sourceGenerationId", "sourceAttemptId", "reasonCode"))) {
            throw new IllegalArgumentException(
                    "answer candidate restoration data has unexpected fields");
        }
        String answerHash = requiredHash(
                data.path("answerHash"), "answer candidate restoration hash");
        if (!data.path("answerChars").isIntegralNumber()
                || !data.path("answerChars").canConvertToInt()
                || data.path("answerChars").intValue() < 0) {
            throw new IllegalArgumentException(
                    "answer candidate restoration lengths are invalid");
        }
        String originText = required(QualificationJson.requiredText(
                data.path("origin"), "answer candidate restoration origin"),
                "answer candidate restoration origin");
        QualificationRecords.AnswerOrigin origin;
        try {
            origin = QualificationRecords.AnswerOrigin.valueOf(originText);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "answer candidate restoration origin is not canonical: "
                            + originText,
                    invalid);
        }
        if (origin == QualificationRecords.AnswerOrigin.NONE) {
            throw new IllegalArgumentException(
                    "answer candidate restoration cannot establish NONE origin");
        }
        if (!data.path("sourceGenerationId").isTextual()
                || !data.path("sourceAttemptId").isTextual()) {
            throw new IllegalArgumentException(
                    "answer candidate restoration source ids must be strings");
        }
        String generationId = data.path("sourceGenerationId").textValue();
        String attemptId = data.path("sourceAttemptId").textValue();
        if (origin == QualificationRecords.AnswerOrigin.MODEL_PROVIDER) {
            if (!generationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
                    || !attemptId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")) {
                throw new IllegalArgumentException(
                        "provider answer restoration source ids are invalid");
            }
        } else if (!generationId.isEmpty() || !attemptId.isEmpty()) {
            throw new IllegalArgumentException(
                    "non-provider answer restoration cannot claim provider source ids");
        }
        String reason = required(QualificationJson.requiredText(
                data.path("reasonCode"), "answer candidate restoration reason code"),
                "answer candidate restoration reason code");
        if (!ANSWER_RESTORATION_REASON_CODES.contains(reason)) {
            throw new IllegalArgumentException(
                    "answer candidate restoration reason is not canonical: " + reason);
        }
        return new AnswerRestoration(
                answerHash,
                data.path("answerChars").intValue(),
                origin,
                generationId,
                attemptId,
                reason);
    }

    private static String validateProviderToolSuppression(
            JsonNode event,
            QualificationProviderAttemptLedger.Ledger ledger,
            int eventIndex) {
        JsonNode data = requiredObject(
                event.path("data"), "provider tool suppression data");
        Set<String> keys = new HashSet<>();
        data.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(Set.of(
                "sourceGenerationId", "sourceAttemptId", "reasonCode",
                "nativeToolCallCount", "textualToolCallPresent"))) {
            throw new IllegalArgumentException(
                    "provider tool suppression data has unexpected fields");
        }
        String generationId = required(QualificationJson.requiredText(
                data.path("sourceGenerationId"), "suppressed provider generation id"),
                "suppressed provider generation id");
        String attemptId = required(QualificationJson.requiredText(
                data.path("sourceAttemptId"), "suppressed provider attempt id"),
                "suppressed provider attempt id");
        if (!generationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
                || !attemptId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")) {
            throw new IllegalArgumentException(
                    "provider tool suppression ids are invalid");
        }
        if (!"TASK_CONTRACT_BLOCKED_TOOL_CALLS".equals(required(
                QualificationJson.requiredText(
                        data.path("reasonCode"), "provider tool suppression reason"),
                "provider tool suppression reason"))) {
            throw new IllegalArgumentException(
                    "provider tool suppression reason is not canonical");
        }
        QualificationProviderAttemptLedger.Generation generation =
                ledger.selectedGeneration(generationId, attemptId);
        QualificationProviderAttemptLedger.Response response = generation == null
                ? null : generation.selectedResponse();
        int nativeToolCallCount = QualificationJson.requiredInt(
                data.path("nativeToolCallCount"),
                "provider tool suppression nativeToolCallCount");
        boolean textualToolCallPresent = requiredBoolean(
                data.path("textualToolCallPresent"),
                "provider tool suppression textualToolCallPresent");
        if (generation == null
                || response == null
                || !"GENERATION_COMPLETED".equals(generation.terminalEvent())
                || generation.terminalEventIndex() >= eventIndex
                || ledger.latestNonBackgroundEventIndexBefore(eventIndex)
                        != generation.terminalEventIndex()
                || !generation.providerTerminalEligible()
                || !response.toolCallPresent()
                || nativeToolCallCount < 0
                || nativeToolCallCount != response.nativeToolCallCount()
                || textualToolCallPresent != response.textualToolCallPresent()) {
            throw new IllegalArgumentException(
                    "provider tool suppression does not match its selected response");
        }
        return generationId + '\u0000' + attemptId;
    }

    private record AnswerTransformation(
            String fromHash,
            int fromChars,
            String answerHash,
            int answerChars,
            String reasonCode) {}

    private record AnswerRestoration(
            String answerHash,
            int answerChars,
            QualificationRecords.AnswerOrigin origin,
            String sourceGenerationId,
            String sourceAttemptId,
            String reasonCode) {}

    private static List<QualificationRecords.DecisionResult> decisions(
            QualificationProtocol.Scenario scenario,
            List<Call> calls,
            boolean suppressedProviderToolCallPresent,
            boolean selectedProviderToolCallPresent) {
        List<QualificationRecords.DecisionResult> out = new ArrayList<>();
        for (QualificationProtocol.ExpectedDecision decision : scenario.expectedDecisions()) {
            if (!decision.scored()) continue;
            boolean correct;
            if (decision.kind() == QualificationProtocol.DecisionKind.NO_TOOL) {
                correct = calls.isEmpty()
                        && !suppressedProviderToolCallPresent
                        && !selectedProviderToolCallPresent;
            } else {
                Call first = firstDecisionCall(scenario, calls);
                String firstDecisionTool = first == null ? "" : first.decisionToolName();
                correct = decision.allowedTools().contains(firstDecisionTool);
            }
            out.add(new QualificationRecords.DecisionResult(decision.id(), correct));
        }
        return List.copyOf(out);
    }

    private static boolean selectedProviderToolCallPresent(
            QualificationProviderAttemptLedger.Ledger providerAttempts) {
        QualificationProviderAttemptLedger.Generation generation = providerAttempts
                .latestTerminalEligibleForegroundDecision();
        QualificationProviderAttemptLedger.Response response = generation == null
                ? null
                : generation.selectedResponse();
        return response != null && response.toolCallPresent();
    }

    static Call firstDecisionCall(
            QualificationProtocol.Scenario scenario,
            List<Call> calls) {
        java.util.stream.Stream<Call> candidates = calls.stream();
        if (scenario.category() == QualificationProtocol.ScenarioCategory.MUTATION
                || scenario.category() == QualificationProtocol.ScenarioCategory.REPAIR) {
            candidates = candidates.filter(call ->
                    !MUTATION_INSPECTION_AUXILIARIES.contains(call.decisionToolName()));
        }
        return candidates.findFirst().orElse(null);
    }

    private record ProviderLink(
            String generationId,
            String attemptId,
            boolean nativeDecisionBound) {
        private static ProviderLink none() {
            return new ProviderLink("", "", false);
        }
    }

    private static ExecutionAuthority executionAuthority(
            JsonNode data,
            ProviderLink providerLink,
            QualificationProviderAttemptLedger.Ledger ledger,
            String traceId,
            String toolCallId,
            String parsedToolName
    ) {
        JsonNode authority = requiredObject(
                data.path("executionAuthority"), "tool execution authority");
        int schemaVersion = QualificationJson.requiredInt(
                authority.path("schemaVersion"), "tool execution authority schemaVersion");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException(
                    "tool execution authority schemaVersion must be 1");
        }
        String status = required(QualificationJson.requiredText(
                authority.path("status"), "tool execution authority status"),
                "tool execution authority status");
        if (!"AUTHORIZED".equals(status)) {
            throw new IllegalArgumentException(
                    "parsed tool execution authority must be AUTHORIZED");
        }
        ExecutionAuthorityKind kind = QualificationJson.requiredEnum(
                authority.path("kind"),
                ExecutionAuthorityKind.class,
                "tool execution authority kind");
        if (kind == ExecutionAuthorityKind.LEGACY) {
            throw new IllegalArgumentException(
                    "trace-v4 parsed calls cannot claim legacy execution authority");
        }
        String authorityId = requiredHash(
                authority.path("authorityId"), "tool execution authority id");
        String toolNameHash = requiredHash(
                authority.path("toolNameHash"), "authority tool-name hash");
        String argumentsHash = requiredHash(
                authority.path("argumentsHash"), "authority arguments hash");
        if (!toolNameHash.equals(requiredHash(
                data.path("toolNameHash"), "parsed tool-name hash"))
                || !argumentsHash.equals(requiredHash(
                        data.path("argumentsHash"), "parsed arguments hash"))) {
            throw new IllegalArgumentException(
                    "tool execution authority does not match the parsed call");
        }
        String expectedAuthorityId = sha256(
                traceId + '\u0000' + toolCallId + '\u0000' + kind.name());
        if (!expectedAuthorityId.equals(authorityId)) {
            throw new IllegalArgumentException(
                    "tool execution authority id does not match its trace owner");
        }

        return switch (kind) {
            case PROVIDER_NATIVE -> nativeExecutionAuthority(
                    data, authority, providerLink, ledger, toolNameHash);
            case PROVIDER_TEXT_JSON -> textExecutionAuthority(
                    authority, providerLink, ledger, toolNameHash);
            case RUNTIME_DETERMINISTIC -> runtimeExecutionAuthority(
                    authority, providerLink, authorityId, parsedToolName, toolNameHash);
            case LEGACY -> throw new IllegalStateException("legacy authority already rejected");
        };
    }

    private static ExecutionAuthority nativeExecutionAuthority(
            JsonNode parsedData,
            JsonNode authority,
            ProviderLink providerLink,
            QualificationProviderAttemptLedger.Ledger ledger,
            String toolNameHash
    ) {
        requireExactKeys(authority, Set.of(
                "schemaVersion", "status", "kind", "authorityId",
                "toolNameHash", "argumentsHash",
                "providerGenerationId", "providerAttemptId", "sourceOrdinal",
                "parserFormat", "providerCallIdHash",
                "providerToolNameHash", "providerArgumentsHash"),
                "native tool execution authority");
        if (!providerLink.nativeDecisionBound()) {
            throw new IllegalArgumentException(
                    "native execution authority lacks a native provider decision");
        }
        String generationId = required(QualificationJson.requiredText(
                authority.path("providerGenerationId"),
                "native authority provider generation id"),
                "native authority provider generation id");
        String attemptId = required(QualificationJson.requiredText(
                authority.path("providerAttemptId"),
                "native authority provider attempt id"),
                "native authority provider attempt id");
        if (!generationId.equals(providerLink.generationId())
                || !attemptId.equals(providerLink.attemptId())) {
            throw new IllegalArgumentException(
                    "native execution authority provider identity disagrees");
        }
        int ordinal = QualificationJson.requiredInt(
                authority.path("sourceOrdinal"), "native authority source ordinal");
        if (ordinal < 1
                || ordinal != QualificationJson.requiredInt(
                        parsedData.path("providerProposalOrdinal"),
                        "parsed provider proposal ordinal")) {
            throw new IllegalArgumentException(
                    "native execution authority source ordinal is invalid");
        }
        if (!"NATIVE".equals(QualificationJson.requiredText(
                authority.path("parserFormat"), "native authority parser format"))) {
            throw new IllegalArgumentException(
                    "native execution authority parser format is invalid");
        }
        QualificationProviderAttemptLedger.Generation generation =
                ledger.selectedGeneration(generationId, attemptId);
        QualificationProviderAttemptLedger.Attempt selectedAttempt =
                generation == null ? null : generation.selectedAttempt();
        if (generation == null
                || generation.toolAuthorityMode()
                    != ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE
                || !generation.providerTerminalEligible()
                || selectedAttempt == null
                || !selectedAttempt.authoritySurface().authorizes(toolNameHash)) {
            throw new IllegalArgumentException(
                    "native execution authority requires an exact selected tool surface");
        }
        QualificationProviderAttemptLedger.Proposal proposal =
                ledger.selectedProposal(generationId, attemptId, ordinal);
        if (proposal == null
                || !proposal.providerCallIdHash().equals(requiredHash(
                        authority.path("providerCallIdHash"),
                        "native authority provider call-id hash"))
                || !proposal.toolNameHash().equals(requiredHash(
                        authority.path("providerToolNameHash"),
                        "native authority provider tool-name hash"))
                || !proposal.argumentsHash().equals(requiredHash(
                        authority.path("providerArgumentsHash"),
                        "native authority provider arguments hash"))
                || !proposal.toolNameHash().equals(toolNameHash)) {
            throw new IllegalArgumentException(
                    "native execution authority does not match its provider proposal");
        }
        return new ExecutionAuthority(
                ExecutionAuthorityKind.PROVIDER_NATIVE,
                "native\u0000" + generationId + '\u0000' + attemptId + '\u0000' + ordinal,
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE);
    }

    private static ExecutionAuthority textExecutionAuthority(
            JsonNode authority,
            ProviderLink providerLink,
            QualificationProviderAttemptLedger.Ledger ledger,
            String toolNameHash
    ) {
        requireExactKeys(authority, Set.of(
                "schemaVersion", "status", "kind", "authorityId",
                "toolNameHash", "argumentsHash",
                "providerGenerationId", "providerAttemptId", "sourceOrdinal",
                "parserFormat", "providerAssistantHash"),
                "text tool execution authority");
        if (providerLink.generationId().isBlank()
                || providerLink.nativeDecisionBound()) {
            throw new IllegalArgumentException(
                    "text execution authority lacks a textual provider decision");
        }
        String generationId = required(QualificationJson.requiredText(
                authority.path("providerGenerationId"),
                "text authority provider generation id"),
                "text authority provider generation id");
        String attemptId = required(QualificationJson.requiredText(
                authority.path("providerAttemptId"),
                "text authority provider attempt id"),
                "text authority provider attempt id");
        if (!generationId.equals(providerLink.generationId())
                || !attemptId.equals(providerLink.attemptId())) {
            throw new IllegalArgumentException(
                    "text execution authority provider identity disagrees");
        }
        int ordinal = QualificationJson.requiredInt(
                authority.path("sourceOrdinal"), "text authority source ordinal");
        if (ordinal < 1
                || ordinal > ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS) {
            throw new IllegalArgumentException(
                    "text execution authority source ordinal is invalid");
        }
        if (!"JSON_TEXT".equals(QualificationJson.requiredText(
                authority.path("parserFormat"), "text authority parser format"))) {
            throw new IllegalArgumentException(
                    "text execution authority parser format is invalid");
        }
        QualificationProviderAttemptLedger.Generation generation =
                ledger.selectedGeneration(generationId, attemptId);
        QualificationProviderAttemptLedger.Attempt selectedAttempt =
                generation == null ? null : generation.selectedAttempt();
        QualificationProviderAttemptLedger.Response response =
                generation == null ? null : generation.selectedResponse();
        if (response == null
                || generation.toolAuthorityMode()
                    != ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON
                || response.completion()
                        != dev.talos.spi.types.ProviderResponseCapture.Completion.PROVIDER_EOS
                || !generation.providerTerminalEligible()
                || selectedAttempt == null
                || !selectedAttempt.authoritySurface().authorizes(toolNameHash)
                || response.normalized()
                || response.truncated()
                || response.nativeToolCallCount() != 0
                || !response.textualToolCallPresent()
                || !response.assistantHash().equals(requiredHash(
                        authority.path("providerAssistantHash"),
                        "text authority provider assistant hash"))) {
            throw new IllegalArgumentException(
                    "text execution authority does not match an eligible provider response");
        }
        return new ExecutionAuthority(
                ExecutionAuthorityKind.PROVIDER_TEXT_JSON,
                "text\u0000" + generationId + '\u0000' + attemptId + '\u0000' + ordinal,
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE);
    }

    private static ExecutionAuthority runtimeExecutionAuthority(
            JsonNode authority,
            ProviderLink providerLink,
            String authorityId,
            String parsedToolName,
            String parsedToolNameHash
    ) {
        requireExactKeys(authority, Set.of(
                "schemaVersion", "status", "kind", "authorityId",
                "toolNameHash", "argumentsHash", "runtimeReason"),
                "runtime tool execution authority");
        if (!providerLink.generationId().isBlank()
                || !providerLink.attemptId().isBlank()) {
            throw new IllegalArgumentException(
                    "runtime execution authority cannot claim provider ownership");
        }
        LocalTurnTraceCapture.RuntimeToolAuthorityReason reason =
                QualificationJson.requiredEnum(
                        authority.path("runtimeReason"),
                        LocalTurnTraceCapture.RuntimeToolAuthorityReason.class,
                        "runtime tool authority reason");
        if (reason == LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE) {
            throw new IllegalArgumentException(
                    "runtime execution authority reason is missing");
        }
        if (!runtimeReasonAllowsTool(reason, parsedToolName, parsedToolNameHash)) {
            throw new IllegalArgumentException(
                    "runtime execution authority reason does not permit the parsed tool");
        }
        return new ExecutionAuthority(
                ExecutionAuthorityKind.RUNTIME_DETERMINISTIC,
                "runtime\u0000" + authorityId,
                reason);
    }

    private static void validateAuthorityRejection(JsonNode data) {
        JsonNode authority = requiredObject(
                data.path("executionAuthority"), "tool authority rejection");
        requireExactKeys(authority, Set.of(
                "schemaVersion", "status", "kind", "reasonCode"),
                "tool authority rejection");
        if (QualificationJson.requiredInt(
                authority.path("schemaVersion"),
                "tool authority rejection schemaVersion") != 1
                || !"REJECTED".equals(QualificationJson.requiredText(
                        authority.path("status"), "tool authority rejection status"))) {
            throw new IllegalArgumentException(
                    "tool authority rejection has invalid schema or status");
        }
        String kind = required(QualificationJson.requiredText(
                authority.path("kind"), "tool authority rejection kind"),
                "tool authority rejection kind");
        if (!kind.equals("UNBOUND")) {
            ExecutionAuthorityKind parsedKind = QualificationJson.requiredEnum(
                    authority.path("kind"),
                    ExecutionAuthorityKind.class,
                    "tool authority rejection kind");
            if (parsedKind == ExecutionAuthorityKind.LEGACY) {
                throw new IllegalArgumentException(
                        "trace-v4 authority rejection cannot claim legacy kind");
            }
        }
        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason reason =
                QualificationJson.requiredEnum(
                authority.path("reasonCode"),
                LocalTurnTraceCapture.ToolCallAuthorityRejectionReason.class,
                "tool authority rejection reason");
        validateAuthorityRejectionKind(kind, reason);
    }

    private static void validateAuthorityRejectionKind(
            String kind,
            LocalTurnTraceCapture.ToolCallAuthorityRejectionReason reason
    ) {
        Set<LocalTurnTraceCapture.ToolCallAuthorityRejectionReason> common = Set.of(
                LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                        .LINEAGE_SCOPE_MISMATCH,
                LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                        .LINEAGE_CALL_MISMATCH,
                LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                        .ACTIVE_CALL_EXISTS);
        Set<LocalTurnTraceCapture.ToolCallAuthorityRejectionReason> providerPreFormat =
                Set.of(
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_RECEIPT_MISSING,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_RECEIPT_NOT_CURRENT,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_GENERATION_STALE,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_SOURCE_INELIGIBLE,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_PURPOSE_INELIGIBLE,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_TOOL_SURFACE_UNBOUND,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_DISPATCH_SURFACE_MISMATCH,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_TOOL_SURFACE_MISMATCH,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_TOOL_INELIGIBLE,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_RESPONSE_MISSING,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_RESPONSE_MISMATCH);
        java.util.LinkedHashSet<LocalTurnTraceCapture.ToolCallAuthorityRejectionReason>
                reachable = new java.util.LinkedHashSet<>(common);
        switch (kind) {
            case "RUNTIME_DETERMINISTIC" -> reachable.addAll(Set.of(
                    LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                            .RUNTIME_AUTHORITY_REASON_MISSING,
                    LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                            .RUNTIME_AUTHORITY_SCOPE_INVALID));
            case "PROVIDER_NATIVE" -> {
                reachable.addAll(providerPreFormat);
                reachable.addAll(Set.of(
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_TOOL_MODE_MISMATCH,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_TERMINAL_INELIGIBLE,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_PROPOSAL_ORDINAL_INVALID,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_PROPOSAL_EVIDENCE_MISSING,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_PROPOSAL_MISMATCH,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_PROPOSAL_ALREADY_USED));
            }
            case "PROVIDER_TEXT_JSON" -> {
                reachable.addAll(providerPreFormat);
                reachable.addAll(Set.of(
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_TOOL_MODE_MISMATCH,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .TEXT_PROTOCOL_INELIGIBLE,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_PROPOSAL_ORDINAL_INVALID,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_PROPOSAL_MISMATCH,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .PROVIDER_PROPOSAL_ALREADY_USED));
            }
            case "UNBOUND" -> {
                reachable.addAll(providerPreFormat);
                reachable.addAll(Set.of(
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .LINEAGE_MISSING,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .TEXT_PARSER_LINEAGE_REQUIRED,
                        LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                .XML_COMPATIBILITY_NOT_AUTHORIZED));
            }
            default -> throw new IllegalArgumentException(
                    "tool authority rejection kind is unknown");
        }
        if (!reachable.contains(reason)) {
            throw new IllegalArgumentException(
                    "tool authority rejection kind and reason are incompatible");
        }
    }

    private static void requireExactKeys(
            JsonNode object,
            Set<String> expected,
            String label
    ) {
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(label + " has unexpected fields");
        }
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static ProviderLink providerLink(
            JsonNode data,
            QualificationProviderAttemptLedger.Ledger ledger,
            int parsedEventIndex) {
        String generationId = QualificationJson.optionalText(
                data.path("providerGenerationId"), "provider generation id").strip();
        String attemptId = QualificationJson.optionalText(
                data.path("providerAttemptId"), "provider attempt id").strip();
        boolean hasProposalOrdinal = data.has("providerProposalOrdinal");
        boolean hasToolHash = data.has("providerToolNameHash");
        boolean hasArgumentsHash = data.has("providerArgumentsHash");
        boolean hasParsedToolHash = data.has("providerParsedToolNameHash");
        boolean hasParsedArgumentsHash = data.has("providerParsedArgumentsHash");
        if (generationId.isBlank() && attemptId.isBlank()) {
            if (hasProposalOrdinal || hasToolHash || hasArgumentsHash
                    || hasParsedToolHash || hasParsedArgumentsHash) {
                throw new IllegalArgumentException(
                        "parsed tool proposal evidence lacks a provider generation/attempt link");
            }
            return ProviderLink.none();
        }
        if (generationId.isBlank() || attemptId.isBlank()) {
            throw new IllegalArgumentException(
                    "parsed tool provider link must preserve generation and attempt together");
        }
        QualificationProviderAttemptLedger.Generation generation =
                ledger.selectedGeneration(generationId, attemptId);
        if (generation == null
                || !"GENERATION_COMPLETED".equals(generation.terminalEvent())
                || !generation.providerToolCallEligible()) {
            throw new IllegalArgumentException(
                    "parsed tool provider link does not resolve to a selected completed response");
        }
        if (generation.terminalEventIndex() >= parsedEventIndex
                || ledger.latestNonBackgroundEventIndexBefore(parsedEventIndex)
                        != generation.terminalEventIndex()) {
            throw new IllegalArgumentException(
                    "parsed tool provider link is reordered or stale");
        }
        QualificationProviderAttemptLedger.Response response = generation.selectedResponse();
        if (response == null || !response.toolCallPresent()) {
            throw new IllegalArgumentException(
                    "parsed tool provider link resolves to a response without a tool call");
        }
        if (response.nativeToolCallCount() > 0) {
            if (!generation.providerTerminalEligible()) {
                throw new IllegalArgumentException(
                        "native parsed tool link requires a terminal-eligible response");
            }
            if (!hasProposalOrdinal || !hasToolHash || !hasArgumentsHash
                    || !hasParsedToolHash || !hasParsedArgumentsHash) {
                throw new IllegalArgumentException(
                        "native parsed tool link lacks its raw provider proposal identity");
            }
            JsonNode ordinalNode = data.path("providerProposalOrdinal");
            if (!ordinalNode.isIntegralNumber() || !ordinalNode.canConvertToInt()
                    || ordinalNode.intValue() < 1) {
                throw new IllegalArgumentException(
                        "native parsed tool proposal ordinal is invalid");
            }
            String toolHash = requiredHash(data.path("providerToolNameHash"),
                    "parsed provider tool-name hash");
            String argumentsHash = requiredHash(data.path("providerArgumentsHash"),
                    "parsed provider arguments hash");
            String parsedToolHash = requiredHash(data.path("providerParsedToolNameHash"),
                    "provider-bound parsed tool-name hash");
            String parsedArgumentsHash = requiredHash(
                    data.path("providerParsedArgumentsHash"),
                    "provider-bound parsed arguments hash");
            String eventToolHash = requiredHash(data.path("toolNameHash"),
                    "parsed tool-name hash");
            String eventArgumentsHash = requiredHash(data.path("argumentsHash"),
                    "parsed arguments hash");
            QualificationProviderAttemptLedger.Proposal proposal = ledger.selectedProposal(
                    generationId, attemptId, ordinalNode.intValue());
            if (proposal == null
                    || !proposal.toolNameHash().equals(toolHash)
                    || !proposal.argumentsHash().equals(argumentsHash)
                    || !toolHash.equals(parsedToolHash)
                    || !parsedToolHash.equals(eventToolHash)
                    || !parsedArgumentsHash.equals(eventArgumentsHash)) {
                throw new IllegalArgumentException(
                        "native parsed tool link does not match its raw provider proposal");
            }
        } else {
            if (!response.textualToolCallPresent()) {
                throw new IllegalArgumentException(
                        "textual parsed tool link lacks a typed textual provider call");
            }
            if (hasProposalOrdinal || hasToolHash || hasArgumentsHash
                    || hasParsedToolHash || hasParsedArgumentsHash) {
                throw new IllegalArgumentException(
                        "textual parsed tool link claims nonexistent native proposal evidence");
            }
        }
        return new ProviderLink(generationId, attemptId, response.nativeToolCallCount() > 0);
    }

    private static String requiredHash(JsonNode value, String label) {
        if (value == null || !value.isTextual()
                || !value.textValue().matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " is missing or invalid");
        }
        return value.textValue();
    }

    private static MutableCall uniquePending(List<MutableCall> calls, String tool, String pathHint) {
        List<MutableCall> candidates = new ArrayList<>();
        for (MutableCall call : calls) {
            if (!call.settled && call.toolName.equals(tool)) candidates.add(call);
        }
        if (candidates.isEmpty()) return null;

        List<MutableCall> exact = candidates.stream()
                .filter(call -> !pathHint.isBlank()
                        && !call.pathHint.isBlank()
                        && call.pathHint.equals(pathHint))
                .toList();
        if (exact.size() == 1) return exact.getFirst();
        if (!exact.isEmpty()) return null;
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static void applyTransformation(
            List<MutableCall> calls,
            JsonNode event,
            String tool,
            String pathHint,
            QualificationProviderAttemptLedger.Ledger providerAttempts,
            int eventIndex) {
        JsonNode data = requiredObject(event.path("data"), "tool transformation data");
        String toolCallId = requiredToolCallId(data, "tool transformation");
        MutableCall match = pendingById(calls, toolCallId, "tool transformation");
        requireCurrentProviderAuthority(
                match, providerAttempts, eventIndex, "tool transformation");
        if (match.approvalObserved) {
            throw new IllegalArgumentException(
                    "tool call transformation occurred after approval: " + toolCallId);
        }
        String fromToolNameHash = requiredHash(
                data.path("fromToolNameHash"), "transformation source tool name hash");
        String fromArgumentsHash = requiredHash(
                data.path("fromArgumentsHash"), "transformation source arguments hash");
        if (!match.toolNameHash.equals(fromToolNameHash)
                || !match.argumentsHash.equals(fromArgumentsHash)) {
            throw new IllegalArgumentException(
                    "tool call transformation source does not match current lineage: "
                            + toolCallId);
        }
        String reasonCode = required(QualificationJson.requiredText(
                data.path("reasonCode"), "tool transformation reason code"),
                "tool transformation reason code");
        if (!TOOL_CALL_TRANSFORMATION_REASON_CODES.contains(reasonCode)) {
            throw new IllegalArgumentException(
                    "tool transformation reason is not canonical: " + reasonCode);
        }
        String nextToolNameHash = requiredHash(
                data.path("toolNameHash"), "transformation target tool name hash");
        String nextArgumentsHash = requiredHash(
                data.path("argumentsHash"), "transformation target arguments hash");
        if (match.toolNameHash.equals(nextToolNameHash)
                && match.argumentsHash.equals(nextArgumentsHash)) {
            throw new IllegalArgumentException(
                    "tool transformation does not change exact call identity: " + toolCallId);
        }
        if (match.executionAuthorityKind
                    == ExecutionAuthorityKind.RUNTIME_DETERMINISTIC
                && !runtimeReasonAllowsTool(
                        match.runtimeAuthorityReason, tool, nextToolNameHash)) {
            throw new IllegalArgumentException(
                    "runtime execution authority reason does not permit transformed tool: "
                            + toolCallId);
        }
        if ((match.executionAuthorityKind == ExecutionAuthorityKind.PROVIDER_NATIVE
                    || match.executionAuthorityKind
                            == ExecutionAuthorityKind.PROVIDER_TEXT_JSON)
                && !providerAttemptAuthorizes(
                        providerAttempts,
                        match.providerGenerationId,
                        match.providerAttemptId,
                        nextToolNameHash)) {
            throw new IllegalArgumentException(
                    "provider tool transformation leaves the selected authority surface: "
                            + toolCallId);
        }
        match.toolName = tool;
        match.pathHint = pathHint;
        match.toolNameHash = nextToolNameHash;
        match.argumentsHash = nextArgumentsHash;
        match.runtimeTransformed = true;
    }

    private static boolean providerAttemptAuthorizes(
            QualificationProviderAttemptLedger.Ledger providerAttempts,
            String generationId,
            String attemptId,
            String toolNameHash
    ) {
        if (providerAttempts == null) return false;
        QualificationProviderAttemptLedger.Generation generation =
                providerAttempts.selectedGeneration(generationId, attemptId);
        QualificationProviderAttemptLedger.Attempt attempt = generation == null
                ? null
                : generation.selectedAttempt();
        return attempt != null
                && attempt.authoritySurface().authorizes(toolNameHash);
    }

    private static void requireCurrentProviderAuthority(
            MutableCall call,
            QualificationProviderAttemptLedger.Ledger providerAttempts,
            int eventIndex,
            String label
    ) {
        if (call.executionAuthorityKind != ExecutionAuthorityKind.PROVIDER_NATIVE
                && call.executionAuthorityKind
                        != ExecutionAuthorityKind.PROVIDER_TEXT_JSON) {
            return;
        }
        QualificationProviderAttemptLedger.Generation generation =
                providerAttempts.selectedGeneration(
                        call.providerGenerationId, call.providerAttemptId);
        if (generation == null
                || providerAttempts.latestNonBackgroundEventIndexBefore(eventIndex)
                        != generation.terminalEventIndex()) {
            throw new IllegalArgumentException(
                    label + " uses stale provider execution authority: "
                            + call.toolCallId);
        }
    }

    private static MutableCall exactPending(
            List<MutableCall> calls,
            JsonNode data,
            String tool,
            String pathHint,
            String label) {
        String toolCallId = requiredToolCallId(data, label);
        MutableCall match = pendingById(calls, toolCallId, label);
        String toolNameHash = requiredHash(data.path("toolNameHash"), label + " tool name hash");
        String argumentsHash = requiredHash(data.path("argumentsHash"), label + " arguments hash");
        if (!match.toolName.equals(tool)
                || !match.pathHint.equals(pathHint)
                || !match.toolNameHash.equals(toolNameHash)
                || !match.argumentsHash.equals(argumentsHash)) {
            throw new IllegalArgumentException(
                    label + " does not match current exact tool-call identity: " + toolCallId);
        }
        return match;
    }

    private static MutableCall pendingById(
            List<MutableCall> calls,
            String toolCallId,
            String label) {
        List<MutableCall> matches = calls.stream()
                .filter(call -> !call.settled && call.toolCallId.equals(toolCallId))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    label + " lacks one pending parsed call id: " + toolCallId);
        }
        return matches.getFirst();
    }

    private static String requiredToolCallId(JsonNode data, String label) {
        String toolCallId = required(QualificationJson.requiredText(
                data.path("toolCallId"), label + " id"), label + " id");
        if (!toolCallId.matches("call-[1-9][0-9]*-[1-9][0-9]*")) {
            throw new IllegalArgumentException(label + " id is not canonical: " + toolCallId);
        }
        return toolCallId;
    }

    private static void bindApproval(
            List<MutableCall> calls,
            JsonNode data,
            String tool,
            String pathHint,
            ApprovalDisposition disposition,
            boolean requiredEvent,
            boolean exactToolLineage,
            QualificationProviderAttemptLedger.Ledger providerAttempts,
            int eventIndex) {
        if (tool.isBlank()) {
            throw new IllegalArgumentException("tool approval event lacks toolName");
        }
        MutableCall match = exactToolLineage
                ? exactPending(calls, data, tool, pathHint, "tool approval")
                : uniquePendingApproval(calls, tool, pathHint);
        if (match == null) {
            throw new IllegalArgumentException("tool approval lacks a unique parsed call: " + tool);
        }
        requireCurrentProviderAuthority(
                match, providerAttempts, eventIndex, "tool approval");
        if (requiredEvent) {
            if (match.approvalRequiredObserved) {
                throw new IllegalArgumentException(
                        "tool call has multiple approval-required events: " + tool);
            }
            match.approvalRequiredObserved = true;
            match.approvalObserved = true;
            return;
        }
        if (match.approvalDisposition != ApprovalDisposition.NONE) {
            throw new IllegalArgumentException("tool call has multiple approval decisions: " + tool);
        }
        match.approvalDisposition = disposition;
        match.approvalObserved = true;
    }

    private static MutableCall uniquePendingApproval(
            List<MutableCall> calls,
            String tool,
            String pathHint) {
        List<MutableCall> candidates = calls.stream()
                .filter(call -> !call.settled && call.toolName.equals(tool))
                .toList();
        if (candidates.isEmpty()) return null;
        if (!pathHint.isBlank()) {
            List<MutableCall> exact = candidates.stream()
                    .filter(call -> call.pathHint.equals(pathHint))
                    .toList();
            if (exact.size() == 1) return exact.getFirst();
            if (!exact.isEmpty()) return null;
            if (candidates.stream().anyMatch(call -> !call.pathHint.isBlank())) return null;
        }
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static String canonicalTool(String value) {
        String tool = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (tool.isBlank()) return "";
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(tool);
        if (decision.accepted()) return decision.canonicalToolName();
        return tool.startsWith("talos.") ? tool : "talos." + tool;
    }

    private static boolean requiresCanonicalToolIdentity(String type) {
        return "TOOL_CALL_PARSED".equals(type)
                || "TOOL_CALL_TRANSFORMED".equals(type)
                || "TOOL_CALL_BLOCKED".equals(type)
                || "TOOL_EXECUTED".equals(type)
                || "APPROVAL_REQUIRED".equals(type)
                || "APPROVAL_GRANTED".equals(type)
                || "APPROVAL_DENIED".equals(type);
    }

    private static boolean runtimeReasonAllowsTool(
            LocalTurnTraceCapture.RuntimeToolAuthorityReason reason,
            String toolName,
            String toolNameHash
    ) {
        if (reason == null || toolName == null || toolName.isBlank()
                || toolNameHash == null || toolNameHash.isBlank()) {
            return false;
        }
        return switch (reason) {
            case NONE -> false;
            case DIRECT_RUNTIME_API -> true;
            case READ_EVIDENCE_HANDOFF ->
                    exactRuntimeToolIdentity(
                            toolName, toolNameHash, "talos.read_file");
            case EXPECTED_TARGET_SCOPE_REPAIR ->
                    exactRuntimeToolIdentity(
                            toolName, toolNameHash, "talos.edit_file");
        };
    }

    private static boolean exactRuntimeToolIdentity(
            String canonicalToolName,
            String toolNameHash,
            String requiredRawToolName
    ) {
        return requiredRawToolName.equals(canonicalToolName)
                && ProviderResponseCapture.toolNameHash(requiredRawToolName)
                        .equals(toolNameHash);
    }

    private static String normalizedPathHint(String value) {
        return value == null ? "" : value.strip().replace('\\', '/');
    }

    private static List<String> sourcePaths(JsonNode data, String fallbackPath) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        JsonNode values = data == null ? null : data.path("pathHints");
        if (values != null && values.isArray()) {
            values.forEach(value -> {
                String path = normalizedPathHint(QualificationJson.requiredText(
                        value, "tool source path"));
                if (!path.isBlank()) out.add(path);
            });
        }
        String fallback = normalizedPathHint(fallbackPath);
        if (!fallback.isBlank()) out.add(fallback);
        return List.copyOf(out);
    }

    private static List<String> normalizedSourcePaths(List<String> values, String fallbackPath) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String path = normalizedPathHint(value);
                if (!path.isBlank()) out.add(path);
            }
        }
        String fallback = normalizedPathHint(fallbackPath);
        if (!fallback.isBlank()) out.add(fallback);
        return List.copyOf(out);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is missing");
        return value;
    }

    private static JsonNode requiredObject(JsonNode value, String label) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(label + " is missing or invalid");
        }
        return value;
    }

    private static boolean requiredBoolean(JsonNode value, String label) {
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(label + " is missing or invalid");
        }
        return value.booleanValue();
    }

    private static final class MutableCall {
        private String toolName;
        private String pathHint;
        private final String decisionToolName;
        private final String providerGenerationId;
        private final String providerAttemptId;
        private boolean nativeProviderDecisionBound;
        private final String toolCallId;
        private String toolNameHash;
        private String argumentsHash;
        private final ExecutionAuthorityKind executionAuthorityKind;
        private final LocalTurnTraceCapture.RuntimeToolAuthorityReason runtimeAuthorityReason;
        private final java.util.LinkedHashSet<String> sourcePaths = new java.util.LinkedHashSet<>();
        private boolean settled;
        private boolean executed;
        private boolean runtimeTransformed;
        private boolean approvalObserved;
        private boolean approvalRequiredObserved;
        private CallSettlement settlement = CallSettlement.UNSETTLED;
        private ApprovalDisposition approvalDisposition = ApprovalDisposition.NONE;
        private final int parsedEventIndex;
        private int settlementEventIndex = -1;

        private MutableCall(
                String toolName,
                String pathHint,
                String providerGenerationId,
                String providerAttemptId,
                boolean nativeProviderDecisionBound,
                String toolCallId,
                String toolNameHash,
                String argumentsHash,
                ExecutionAuthorityKind executionAuthorityKind,
                LocalTurnTraceCapture.RuntimeToolAuthorityReason runtimeAuthorityReason,
                int parsedEventIndex) {
            this.toolName = toolName;
            this.pathHint = pathHint;
            this.decisionToolName = toolName;
            this.providerGenerationId = providerGenerationId == null
                    ? ""
                    : providerGenerationId.strip();
            this.providerAttemptId = providerAttemptId == null
                    ? ""
                    : providerAttemptId.strip();
            this.nativeProviderDecisionBound = nativeProviderDecisionBound;
            this.toolCallId = toolCallId == null ? "" : toolCallId;
            this.toolNameHash = toolNameHash == null ? "" : toolNameHash;
            this.argumentsHash = argumentsHash == null ? "" : argumentsHash;
            this.executionAuthorityKind = executionAuthorityKind == null
                    ? ExecutionAuthorityKind.LEGACY
                    : executionAuthorityKind;
            this.runtimeAuthorityReason = runtimeAuthorityReason == null
                    ? LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE
                    : runtimeAuthorityReason;
            this.parsedEventIndex = parsedEventIndex;
        }
    }
}
