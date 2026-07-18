package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import dev.talos.runtime.outcome.EvidenceContainmentAnswerGuard;
import dev.talos.runtime.outcome.FileLookupAnswerGuard;
import dev.talos.tools.ToolAliasPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
            boolean runtimeTransformed) {
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
            Set<String> providerToolSurface) {
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
                    runtimeAnswerTransformed, Set.of());
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
                    .map(QualificationTraceEvidence::canonicalTool)
                    .anyMatch(canonicalDispatchedTools(attempt)::contains);
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
                    && !canonicalDispatchedTools(attempt).isEmpty();
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
            Set<String> dispatched = canonicalDispatchedTools(attempt);
            return !attempt.providerBodyHash().isBlank()
                    && dispatched.size() == attempt.dispatchedNativeTools().size()
                    && dispatched.equals(providerToolSurface);
        }

        private Set<String> canonicalDispatchedTools(
                QualificationProviderAttemptLedger.Attempt attempt) {
            return attempt.dispatchedNativeTools().stream()
                    .map(QualificationTraceEvidence::canonicalTool)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
                && traceSchemaVersion != QualificationProviderAttemptLedger.TRACE_SCHEMA_VERSION)) {
            throw new IllegalArgumentException(
                    "trace must be a Talos schemaVersion 2 or 3 object");
        }
        boolean exactToolLineage = traceSchemaVersion
                == QualificationProviderAttemptLedger.TRACE_SCHEMA_VERSION;
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
        for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
            JsonNode event = events.get(eventIndex);
            String type = QualificationJson.requiredText(
                    event.path("type"), "trace event type");
            String tool = canonicalTool(QualificationJson.optionalText(
                    event.path("toolName"), "trace event toolName"));
            String pathHint = normalizedPathHint(QualificationJson.optionalText(
                    event.path("data").path("pathHint"), "trace event pathHint"));
            if (type.equals("TOOL_CALL_PARSED")) {
                if (tool.isBlank()) throw new IllegalArgumentException("parsed tool event lacks toolName");
                ProviderLink providerLink = providerLink(
                        event.path("data"), providerAttempts, eventIndex);
                String toolCallId = exactToolLineage
                        ? requiredToolCallId(event.path("data"), "parsed tool call")
                        : "";
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
                                : ""));
            } else if (type.equals("TOOL_CALL_TRANSFORMED")) {
                if (!exactToolLineage) {
                    throw new IllegalArgumentException(
                            "tool transformation requires trace schemaVersion 3");
                }
                applyTransformation(calls, event, tool, pathHint);
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
                match.settled = true;
                match.settlement = CallSettlement.BLOCKED;
            } else if (type.equals("TOOL_EXECUTED")) {
                MutableCall match = exactToolLineage
                        ? exactPending(calls, event.path("data"), tool, pathHint,
                                "tool execution")
                        : uniquePending(calls, tool, pathHint);
                if (match == null) {
                    throw new IllegalArgumentException("tool execution lacks a parsed call: " + tool);
                }
                match.settled = true;
                match.executed = QualificationJson.requiredBoolean(
                        event.path("data").path("success"), "tool execution success");
                match.settlement = match.executed
                        ? CallSettlement.EXECUTED_SUCCESS
                        : CallSettlement.EXECUTED_FAILURE;
                if (match.executed) {
                    match.sourcePaths.addAll(sourcePaths(event.path("data"), pathHint));
                }
            } else if (type.equals("APPROVAL_REQUIRED")) {
                bindApproval(calls, event.path("data"), tool, pathHint,
                        ApprovalDisposition.NONE, true, exactToolLineage);
            } else if (type.equals("APPROVAL_GRANTED")) {
                approvalGranted = true;
                bindApproval(calls, event.path("data"), tool, pathHint,
                        ApprovalDisposition.GRANTED, false, exactToolLineage);
            } else if (type.equals("APPROVAL_DENIED")) {
                approvalDenied = true;
                bindApproval(calls, event.path("data"), tool, pathHint,
                        ApprovalDisposition.DENIED, false, exactToolLineage);
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
                        call.decisionToolName, call.runtimeTransformed))
                .toList();
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
                providerToolSurface);
    }

    private static Set<String> requiredToolNames(JsonNode values, String label) {
        if (values == null || !values.isArray()) {
            throw new IllegalArgumentException(label + " must be an array");
        }
        java.util.LinkedHashSet<String> tools = new java.util.LinkedHashSet<>();
        for (JsonNode value : values) {
            String raw = required(QualificationJson.requiredText(value, label + " entry"),
                    label + " entry");
            String tool = canonicalTool(raw);
            if (tool.isBlank() || !tools.add(tool)) {
                throw new IllegalArgumentException(label + " contains a blank or duplicate tool");
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
            String pathHint) {
        JsonNode data = requiredObject(event.path("data"), "tool transformation data");
        String toolCallId = requiredToolCallId(data, "tool transformation");
        MutableCall match = pendingById(calls, toolCallId, "tool transformation");
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
        match.toolName = tool;
        match.pathHint = pathHint;
        match.toolNameHash = nextToolNameHash;
        match.argumentsHash = nextArgumentsHash;
        match.runtimeTransformed = true;
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
            boolean exactToolLineage) {
        if (tool.isBlank()) {
            throw new IllegalArgumentException("tool approval event lacks toolName");
        }
        MutableCall match = exactToolLineage
                ? exactPending(calls, data, tool, pathHint, "tool approval")
                : uniquePendingApproval(calls, tool, pathHint);
        if (match == null) {
            throw new IllegalArgumentException("tool approval lacks a unique parsed call: " + tool);
        }
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
        private final java.util.LinkedHashSet<String> sourcePaths = new java.util.LinkedHashSet<>();
        private boolean settled;
        private boolean executed;
        private boolean runtimeTransformed;
        private boolean approvalObserved;
        private boolean approvalRequiredObserved;
        private CallSettlement settlement = CallSettlement.UNSETTLED;
        private ApprovalDisposition approvalDisposition = ApprovalDisposition.NONE;

        private MutableCall(
                String toolName,
                String pathHint,
                String providerGenerationId,
                String providerAttemptId,
                boolean nativeProviderDecisionBound,
                String toolCallId,
                String toolNameHash,
                String argumentsHash) {
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
        }
    }
}
