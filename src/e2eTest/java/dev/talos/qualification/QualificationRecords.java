package dev.talos.qualification;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class QualificationRecords {
    static final int SCHEMA_VERSION = 1;
    static final int LEGACY_RUN_SCHEMA_VERSION = 1;
    static final int RUN_SCHEMA_VERSION = 2;
    static final int CAUSAL_SCHEMA_VERSION = 2;
    static final int SCORECARD_SCHEMA_VERSION = 2;
    static final String CAMPAIGN_SCHEMA = "talos.modelQualification.campaign";
    static final String CALIBRATION_SCHEMA = "talos.modelQualification.calibrationRules";
    static final String CANDIDATE_SCHEMA = "talos.modelQualification.candidate";
    static final String MACHINE_SCHEMA = "talos.modelQualification.machine";
    static final String ENGINE_SCHEMA = "talos.modelQualification.engine";
    static final String REQUEST_SCHEMA = "talos.modelQualification.request";
    static final String RUN_SCHEMA = "talos.modelQualification.runResult";
    static final String SCORECARD_SCHEMA = "talos.modelQualification.scorecard";
    static final String TIMING_SCHEMA = "talos.modelQualification.timingEvidence";
    static final String RESOURCE_SCHEMA = "talos.modelQualification.resourceEvidence";

    private QualificationRecords() {}

    enum FailureClass {
        NONE,
        MODEL_FAILURE,
        TALOS_RUNTIME_DEFECT,
        HARDWARE_FAILURE,
        INCOMPLETE_EVIDENCE,
        OPERATOR_INTERRUPTION
    }

    enum AnswerOrigin {
        MODEL_PROVIDER,
        RUNTIME_DETERMINISTIC,
        TOOL_DETERMINISTIC,
        OPERATOR,
        NONE
    }

    enum CausalOwner {
        MODEL,
        RUNTIME_POLICY,
        VERIFIER,
        PROVIDER,
        HARNESS,
        HARDWARE,
        OPERATOR,
        MIXED,
        UNKNOWN
    }

    enum ResponseOwnerContract {
        MODEL_SYNTHESIS_REQUIRED,
        DETERMINISTIC_RUNTIME_ALLOWED,
        OPERATOR_OWNED
    }

    enum CausalStage {
        EVIDENCE,
        TRUST_GATE,
        OPERATOR,
        HARDWARE,
        PROVIDER,
        RUNTIME_POLICY,
        DECISION,
        TOOL_EXECUTION,
        ANSWER_ORIGIN,
        RESPONSE_CONTRACT,
        VERIFICATION,
        UTILITY
    }

    enum CausalFact {
        MANDATORY_ARTIFACT_SET_PRESENT,
        MANDATORY_ARTIFACT_SET_INCOMPLETE,
        TRUST_GATES_PASSED,
        TRUST_GATE_APPROVAL_INTEGRITY_FAILED,
        TRUST_GATE_MUTATION_SCOPE_FAILED,
        TRUST_GATE_COMPLETION_TRUTH_FAILED,
        TRUST_GATE_PROTECTED_CONTENT_SAFETY_FAILED,
        TRUST_GATE_READ_EVIDENCE_BINDING_FAILED,
        TRUST_GATE_TERMINAL_STATE_DISCLOSURE_FAILED,
        TRUST_GATE_MODEL_IDENTITY_AGREEMENT_FAILED,
        TRUST_GATE_MANDATORY_EVIDENCE_FAILED,
        HARNESS_PTY_CAPTURE_FAILED,
        OPERATOR_INTERRUPT_AND_ABORT_PROVEN,
        HARDWARE_RESOURCE_SATURATION_PROVEN,
        PROVIDER_FAILURE_PROVEN,
        RUNTIME_GENERATION_FAILURE_PROVEN,
        RUNTIME_TOOL_CALL_TRANSFORMATION_PROVEN,
        RUNTIME_ANSWER_TRANSFORMATION_PROVEN,
        SCORED_DECISION_PASSED,
        SCORED_DECISION_FAILED,
        SCORED_DECISION_ORIGIN_NOT_PROVEN,
        TOOL_OUTCOME_CAUSAL_OWNER_NOT_PROVEN,
        MODEL_RESPONSE_HASH_MATCHED,
        DETERMINISTIC_ANSWER_HASH_MATCHED,
        NO_TERMINAL_ANSWER_MATCHED,
        RESPONSE_OWNER_CONTRACT_SATISFIED,
        RESPONSE_OWNER_CONTRACT_NOT_PROVEN,
        RESPONSE_OWNER_CONTRACT_VIOLATED,
        VERIFIER_REJECTION_PROVEN,
        CANONICAL_REQUIREMENTS_PASSED,
        CANONICAL_REQUIREMENTS_FAILED,
        REQUIREMENT_DOMAIN_CAUSAL_OWNER_NOT_PROVEN,
        EXPECTED_TERMINAL_STATE_PASSED,
        EXPECTED_TERMINAL_STATE_FAILED,
        TURN_TIMEOUT_OBSERVED,
        OUTPUT_CAP_HIT_PROVEN,
        CAUSE_NOT_PROVEN
    }

    enum CausalReason {
        OUTCOME_SATISFIED,
        SUCCESS_ORIGIN_NOT_MECHANICALLY_PROVEN,
        MODEL_RESPONSE_FAILED_REQUIREMENT,
        RUNTIME_DETERMINISTIC_ANSWER_FAILED_REQUIREMENT,
        RESPONSE_OWNER_CONTRACT_VIOLATED,
        VERIFIER_DIVERGED_FROM_CANONICAL_REQUIREMENTS,
        PROVIDER_GENERATION_FAILED,
        RUNTIME_GENERATION_FAILED,
        RUNTIME_TOOL_CALL_TRANSFORMED,
        RUNTIME_ANSWER_TRANSFORMED,
        MODEL_DECISION_FAILED,
        SCORED_DECISION_FAILED,
        DECISION_ORIGIN_NOT_MECHANICALLY_PROVEN,
        TOOL_OUTCOME_CAUSE_NOT_MECHANICALLY_PROVEN,
        RUNTIME_OUTPUT_TRUNCATED,
        RUNTIME_TIMEOUT_ENFORCED,
        MANDATORY_EVIDENCE_INCOMPLETE,
        TRUST_GATE_FAILED,
        HARNESS_STAGE_FAILED,
        HARDWARE_RESOURCE_EXHAUSTED,
        OPERATOR_INTERRUPTED,
        MULTIPLE_CONTRIBUTING_OWNERS,
        UTILITY_OWNER_UNPROVEN_AFTER_RUNTIME_TOOL_TRANSFORMATION,
        UTILITY_OWNER_UNPROVEN_AFTER_RUNTIME_ANSWER_TRANSFORMATION,
        REQUIREMENT_OWNER_NOT_MECHANICALLY_PROVEN,
        CAUSE_NOT_MECHANICALLY_PROVEN,
        LEGACY_CAUSAL_EVIDENCE_UNAVAILABLE,
        FAILURE_CAUSE_DECLARED
    }

    enum Disposition {
        ADMISSION_PASSED,
        ADMISSION_FAILED,
        BLOCKED_TRUST,
        BLOCKED_INCOMPLETE,
        ACCEPTED_BETA,
        EXPERIMENTAL_SELECTABLE,
        REJECTED
    }

    record CampaignManifest(
            String schemaName,
            int schemaVersion,
            String campaignId,
            String talosVersion,
            String talosCommit,
            int protocolVersion,
            String scenarioBankSha256,
            String candidateAlias,
            String machineId,
            String engineLaneId,
            String configId,
            List<String> repetitions,
            String seedPolicy,
            String calibrationRulesSha256,
            String calibrationGuideSha256,
            String startedAt,
            String endedAt) {
        CampaignManifest {
            repetitions = immutable(repetitions);
        }
    }

    record CandidateManifest(
            String schemaName,
            int schemaVersion,
            String alias,
            String repository,
            String revision,
            String artifact,
            String sha256,
            long sizeBytes,
            String license,
            String ggufProvenance,
            String architecture,
            long totalParameters,
            long activeParameters,
            String toolMode,
            String reasoningMode,
            String chatTemplate,
            String disposition) {}

    record MachineFacts(
            String schemaName,
            int schemaVersion,
            String machineId,
            String os,
            String osVersion,
            String arch,
            String cpu,
            long physicalRamBytes,
            String gpu,
            long vramBytes,
            String driver,
            String powerMode,
            String backend,
            int threadCount,
            String pagefileOrSwap,
            String isolation) {}

    record EngineFacts(
            String schemaName,
            int schemaVersion,
            String laneId,
            String tag,
            String asset,
            String executable,
            String sha256,
            List<String> launchArguments,
            List<String> verificationLaunchArguments,
            String backend,
            boolean offloadVerified,
            String offloadEvidence,
            double doctorProjectionSeconds,
            double configuredTimeoutSeconds) {
        EngineFacts {
            launchArguments = immutable(launchArguments);
            verificationLaunchArguments = immutable(verificationLaunchArguments);
        }
    }

    record SamplingPolicy(
            String toolObligationPolicy,
            double toolObligationTemperature,
            double toolObligationTopP,
            int toolObligationTopK,
            String otherTurnPolicy,
            double backendDefaultTemperature,
            double backendDefaultTopP,
            int backendDefaultTopK,
            double backendDefaultMinP,
            double backendDefaultRepeatPenalty,
            String configSamplingOverrides,
            String seedMode,
            String fixedSeed) {}

    record OutputCaps(int readOnlyTokens, int mutationTokens, int serverBackstopTokens) {}

    record HardwareEnvelope(
            String intendedClass,
            long minimumSystemRamBytes,
            long minimumSystemMemoryMarginBytes,
            long minimumVramBytes,
            long minimumVramMarginBytes,
            String offloadRequirement) {}

    record PermittedDeviation(
            String id,
            String field,
            List<String> permittedValues,
            String comparabilityImpact) {
        PermittedDeviation {
            permittedValues = immutable(permittedValues);
        }
    }

    record CalibrationRules(
            String schemaName,
            int schemaVersion,
            String configurationId,
            String configurationKind,
            String baselineConfigurationId,
            String candidateAlias,
            String repository,
            String revision,
            String artifact,
            long artifactSizeBytes,
            String artifactSha256,
            String license,
            String ggufProvenance,
            String architecture,
            long totalParameters,
            long activeParameters,
            String talosCommit,
            int protocolVersion,
            String llamaCppTag,
            String executableSha256,
            String backend,
            List<String> launchArguments,
            List<String> verificationLaunchArguments,
            List<String> directPreflightLaunchArguments,
            String requestLayout,
            String directPreflightEvidenceManifestSha256,
            String toolMode,
            String chatTemplate,
            String reasoningBehavior,
            int contextTokens,
            SamplingPolicy sampling,
            OutputCaps outputCaps,
            int timeoutSeconds,
            String seedPolicy,
            HardwareEnvelope hardware,
            List<String> risks,
            List<String> expectedFailureShapes,
            List<String> knownLimitations,
            List<String> admissionCriteria,
            List<String> stopConditions,
            List<String> evidenceRequirements,
            List<PermittedDeviation> permittedDeviations) {
        CalibrationRules {
            launchArguments = immutable(launchArguments);
            verificationLaunchArguments = immutable(verificationLaunchArguments);
            directPreflightLaunchArguments = immutable(directPreflightLaunchArguments);
            risks = immutable(risks);
            expectedFailureShapes = immutable(expectedFailureShapes);
            knownLimitations = immutable(knownLimitations);
            admissionCriteria = immutable(admissionCriteria);
            stopConditions = immutable(stopConditions);
            evidenceRequirements = immutable(evidenceRequirements);
            permittedDeviations = immutable(permittedDeviations);
        }
    }

    record RequestRecord(
            String schemaName,
            int schemaVersion,
            String scenarioId,
            String prompt,
            String repetitionId,
            String transport,
            String command) {}

    record TimingEvidence(
            String schemaName,
            int schemaVersion,
            long firstOutputMillis,
            long totalTurnWallTimeMillis,
            long approvalWaitMillis,
            long promptEvalMillis,
            long generationMillis,
            int promptTokens,
            int outputTokens,
            double promptTokensPerSecond,
            double generationTokensPerSecond,
            int watchdogRetries,
            boolean timedOut,
            boolean outputCapHit,
            String backendErrorCode,
            int malformedToolCalls,
            int toolEligibleGenerations,
            int repairAttempts,
            String measurementSource) {}

    record ResourceEvidence(
            String schemaName,
            int schemaVersion,
            long processWorkingSetBytes,
            long systemRamBytes,
            long pagefileOrSwapBytes,
            long gpuVramUsedBytes,
            long gpuVramBytes,
            String measurementSource) {}

    record TrustGates(
            boolean approvalIntegrity,
            boolean mutationScope,
            boolean completionTruth,
            boolean protectedContentSafety,
            boolean readEvidenceBinding,
            boolean terminalStateDisclosure,
            boolean modelIdentityAgreement,
            boolean mandatoryEvidencePresent) {
        static TrustGates allPassed() {
            return new TrustGates(true, true, true, true, true, true, true, true);
        }

        boolean passed() {
            return approvalIntegrity && mutationScope && completionTruth && protectedContentSafety
                    && readEvidenceBinding && terminalStateDisclosure && modelIdentityAgreement
                    && mandatoryEvidencePresent;
        }

        TrustGates withCompletionTruth(boolean replacement) {
            return new TrustGates(approvalIntegrity, mutationScope, replacement, protectedContentSafety,
                    readEvidenceBinding, terminalStateDisclosure, modelIdentityAgreement,
                    mandatoryEvidencePresent);
        }

        List<String> failures() {
            java.util.ArrayList<String> failures = new java.util.ArrayList<>();
            if (!approvalIntegrity) failures.add("approvalIntegrity");
            if (!mutationScope) failures.add("mutationScope");
            if (!completionTruth) failures.add("completionTruth");
            if (!protectedContentSafety) failures.add("protectedContentSafety");
            if (!readEvidenceBinding) failures.add("readEvidenceBinding");
            if (!terminalStateDisclosure) failures.add("terminalStateDisclosure");
            if (!modelIdentityAgreement) failures.add("modelIdentityAgreement");
            if (!mandatoryEvidencePresent) failures.add("mandatoryEvidencePresent");
            return List.copyOf(failures);
        }
    }

    record DecisionResult(String id, boolean correct) {}

    record ToolCallResult(
            String toolName,
            boolean schemaValid,
            boolean allowed,
            boolean executed,
            boolean recovered) {}

    record RequirementResult(String id, boolean passed) {}

    /**
     * Ordered mechanical causal observation. For a mixed-owner result,
     * {@code firstCause} marks the first mechanically established failing
     * observation retained for each contributing owner. It is not a claim of
     * statistical independence or an exhaustive universal root cause.
     */
    record CausalLedgerEntry(
            int sequence,
            CausalStage stage,
            CausalOwner owner,
            CausalFact observedFact,
            CausalReason reasonCode,
            String evidenceReference,
            boolean firstCause) {
        CausalLedgerEntry {
            if (sequence < 1) throw new IllegalArgumentException("causal sequence must be positive");
            stage = Objects.requireNonNull(stage, "causal stage");
            owner = Objects.requireNonNull(owner, "causal owner");
            observedFact = Objects.requireNonNull(observedFact, "causal observed fact");
            reasonCode = Objects.requireNonNull(reasonCode, "causal reason code");
            evidenceReference = required(evidenceReference, "causal evidence reference");
        }
    }

    record RunResult(
            String schemaName,
            int schemaVersion,
            String campaignId,
            String scenarioBankSha256,
            String candidateAlias,
            String machineId,
            String engineLaneId,
            String configId,
            String scenarioId,
            String repetitionId,
            int repetition,
            boolean valid,
            String invalidReason,
            FailureClass failureClass,
            AnswerOrigin answerOrigin,
            ResponseOwnerContract responseOwnerContract,
            CausalOwner causalOwner,
            CausalReason causalReasonCode,
            boolean comparativeScoreEligible,
            List<CausalLedgerEntry> causalLedger,
            String terminalState,
            TrustGates trustGates,
            boolean timedOut,
            boolean outputCapHit,
            String backendErrorCode,
            int malformedToolCalls,
            int toolEligibleGenerations,
            int unnecessaryToolCalls,
            int repairAttempts,
            int outputTokens,
            int promptTokens,
            long firstOutputMillis,
            int watchdogRetries,
            long totalTurnWallTimeMillis,
            long promptEvalMillis,
            long generationMillis,
            double promptTokensPerSecond,
            double generationTokensPerSecond,
            long processWorkingSetBytes,
            long systemRamBytes,
            long gpuVramUsedBytes,
            long gpuVramBytes,
            boolean evidenceComplete,
            List<DecisionResult> decisions,
            List<ToolCallResult> toolCalls,
            List<RequirementResult> requirements) {
        RunResult {
            schemaName = Objects.requireNonNull(schemaName, "schemaName");
            if (!RUN_SCHEMA.equals(schemaName)
                    || (schemaVersion != LEGACY_RUN_SCHEMA_VERSION
                        && schemaVersion != RUN_SCHEMA_VERSION)) {
                throw new IllegalArgumentException(
                        "run result must use the canonical supported schema name and version");
            }
            failureClass = Objects.requireNonNull(failureClass, "failureClass");
            answerOrigin = Objects.requireNonNull(answerOrigin, "answerOrigin");
            responseOwnerContract = Objects.requireNonNull(
                    responseOwnerContract, "responseOwnerContract");
            causalOwner = Objects.requireNonNull(causalOwner, "causalOwner");
            causalReasonCode = Objects.requireNonNull(causalReasonCode, "causalReasonCode");
            causalLedger = immutable(causalLedger);
            for (int index = 0; index < causalLedger.size(); index++) {
                if (causalLedger.get(index).sequence() != index + 1) {
                    throw new IllegalArgumentException(
                            "causal ledger sequence must be contiguous and ordered");
                }
            }
            trustGates = Objects.requireNonNull(trustGates, "trustGates");
            decisions = immutable(decisions);
            toolCalls = immutable(toolCalls);
            requirements = immutable(requirements);
        }

        RunResult withTrustGates(TrustGates replacement) {
            return copy(null, null, null, null, null, replacement);
        }

        RunResult withValidity(boolean replacement, String reason) {
            return copy(replacement, reason, null, null, null, null);
        }

        RunResult withTimedOut(boolean replacement) {
            return copy(null, null, replacement, null, null, null);
        }

        RunResult withOutputCapHit(boolean replacement) {
            return copy(null, null, null, replacement, null, null);
        }

        RunResult withMalformedToolCalls(int replacement) {
            return copy(null, null, null, null, replacement, null);
        }

        RunResult withFailure(
                FailureClass replacementFailureClass,
                AnswerOrigin replacementAnswerOrigin,
                ResponseOwnerContract replacementOwnerContract,
                CausalOwner replacementCausalOwner,
                List<CausalLedgerEntry> replacementCausalLedger) {
            return new RunResult(schemaName, schemaVersion, campaignId, scenarioBankSha256,
                    candidateAlias, machineId, engineLaneId, configId, scenarioId, repetitionId,
                    repetition, valid, invalidReason,
                    Objects.requireNonNull(replacementFailureClass, "replacementFailureClass"),
                    Objects.requireNonNull(replacementAnswerOrigin, "replacementAnswerOrigin"),
                    Objects.requireNonNull(replacementOwnerContract, "replacementOwnerContract"),
                    Objects.requireNonNull(replacementCausalOwner, "replacementCausalOwner"),
                    replacementCausalLedger == null || replacementCausalLedger.isEmpty()
                            ? CausalReason.FAILURE_CAUSE_DECLARED
                            : replacementCausalLedger.getFirst().reasonCode(),
                    replacementFailureClass == FailureClass.MODEL_FAILURE
                            || replacementFailureClass == FailureClass.OPERATOR_INTERRUPTION,
                    replacementCausalLedger,
                    terminalState, trustGates, timedOut, outputCapHit, backendErrorCode,
                    malformedToolCalls, toolEligibleGenerations, unnecessaryToolCalls,
                    repairAttempts, outputTokens, promptTokens, firstOutputMillis,
                    watchdogRetries, totalTurnWallTimeMillis, promptEvalMillis,
                    generationMillis, promptTokensPerSecond, generationTokensPerSecond,
                    processWorkingSetBytes, systemRamBytes, gpuVramUsedBytes, gpuVramBytes,
                    evidenceComplete, decisions, toolCalls, requirements);
        }

        RunResult withCausalFinalization(QualificationCausalFinalizer.Document document) {
            Objects.requireNonNull(document, "document");
            QualificationCausalFinalizer.EvidenceIdentity identity = document.evidenceIdentity();
            if (!scenarioId.equals(document.scenarioId())
                    || !repetitionId.equals(document.repetitionId())
                    || !campaignId.equals(identity.campaignId())
                    || !scenarioBankSha256.equals(identity.scenarioBankSha256())
                    || !candidateAlias.equals(identity.candidateAlias())
                    || !machineId.equals(identity.machineId())
                    || !engineLaneId.equals(identity.engineLaneId())
                    || !configId.equals(identity.configId())) {
                throw new IllegalArgumentException(
                        "causal finalization identity does not match run result");
            }
            return new RunResult(schemaName, QualificationRecords.RUN_SCHEMA_VERSION,
                    campaignId, scenarioBankSha256, candidateAlias, machineId,
                    engineLaneId, configId, scenarioId, repetitionId, repetition,
                    valid, invalidReason, document.failureClass(), document.answerOrigin(),
                    document.responseOwnerContract(), document.causalOwner(),
                    document.reasonCode(), document.comparativeScoreEligible(),
                    document.causalLedger(), terminalState, document.trustGates(), timedOut,
                    outputCapHit, backendErrorCode, malformedToolCalls,
                    toolEligibleGenerations, unnecessaryToolCalls, repairAttempts,
                    outputTokens, promptTokens, firstOutputMillis, watchdogRetries,
                    totalTurnWallTimeMillis, promptEvalMillis, generationMillis,
                    promptTokensPerSecond, generationTokensPerSecond,
                    processWorkingSetBytes, systemRamBytes, gpuVramUsedBytes,
                    gpuVramBytes, document.evidenceComplete(), decisions, toolCalls,
                    document.requirements());
        }

        private RunResult copy(
                Boolean validOverride,
                String invalidReasonOverride,
                Boolean timedOutOverride,
                Boolean capOverride,
                Integer malformedOverride,
                TrustGates trustOverride) {
            return new RunResult(schemaName, schemaVersion, campaignId, scenarioBankSha256,
                    candidateAlias, machineId, engineLaneId, configId, scenarioId, repetitionId,
                    repetition, validOverride == null ? valid : validOverride,
                    invalidReasonOverride == null ? invalidReason : invalidReasonOverride,
                    failureClass, answerOrigin, responseOwnerContract, causalOwner,
                    causalReasonCode, comparativeScoreEligible, causalLedger,
                    terminalState, trustOverride == null ? trustGates : trustOverride,
                    timedOutOverride == null ? timedOut : timedOutOverride,
                    capOverride == null ? outputCapHit : capOverride,
                    backendErrorCode,
                    malformedOverride == null ? malformedToolCalls : malformedOverride,
                    toolEligibleGenerations,
                    unnecessaryToolCalls, repairAttempts, outputTokens, promptTokens,
                    firstOutputMillis, watchdogRetries, totalTurnWallTimeMillis, promptEvalMillis,
                    generationMillis, promptTokensPerSecond, generationTokensPerSecond,
                    processWorkingSetBytes, systemRamBytes, gpuVramUsedBytes, gpuVramBytes,
                    evidenceComplete, decisions, toolCalls, requirements);
        }
    }

    record RateMetric(
            long numerator,
            long denominator,
            long invalidated,
            double value,
            double wilsonLower95,
            double wilsonUpper95,
            List<String> reasonCodes) {
        RateMetric {
            reasonCodes = immutable(reasonCodes);
        }
    }

    record Scorecard(
            String schemaName,
            int schemaVersion,
            QualificationProtocol.BankKind bank,
            Disposition disposition,
            int expectedTrials,
            int validTrials,
            int invalidatedTrials,
            int totalScenarioDefinitions,
            int successfulScenarioDefinitions,
            RateMetric taskCompletion,
            RateMetric requirementCoverage,
            RateMetric requiredToolAccuracyMetric,
            RateMetric noToolAbstentionAccuracyMetric,
            RateMetric nativeToolCallValidity,
            RateMetric unnecessaryToolCallRate,
            RateMetric unrecoveredMalformedCallRate,
            RateMetric outputCapHitRate,
            int timeoutCount,
            int repairCount,
            double doctorProjectionSeconds,
            double configuredTimeoutSeconds,
            Map<String, Integer> successfulScenariosByArchetype,
            Map<String, Integer> failureClassCounts,
            Map<String, Integer> causalOwnerCounts,
            List<String> findings) {
        Scorecard {
            successfulScenariosByArchetype = successfulScenariosByArchetype == null
                    ? Map.of() : Map.copyOf(successfulScenariosByArchetype);
            failureClassCounts = failureClassCounts == null ? Map.of() : Map.copyOf(failureClassCounts);
            causalOwnerCounts = causalOwnerCounts == null ? Map.of() : Map.copyOf(causalOwnerCounts);
            findings = immutable(findings);
        }

        double requiredToolAccuracy() {
            return requiredToolAccuracyMetric.value();
        }

        double noToolAbstentionAccuracy() {
            return noToolAbstentionAccuracyMetric.value();
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }

    static boolean supportedRunSchemaVersion(int version) {
        return version == LEGACY_RUN_SCHEMA_VERSION || version == RUN_SCHEMA_VERSION;
    }
}
