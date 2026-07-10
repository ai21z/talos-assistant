package dev.talos.qualification;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class QualificationRecords {
    static final int SCHEMA_VERSION = 1;
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
            failureClass = Objects.requireNonNull(failureClass, "failureClass");
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
                    failureClass, terminalState, trustOverride == null ? trustGates : trustOverride,
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
            List<String> findings) {
        Scorecard {
            successfulScenariosByArchetype = successfulScenariosByArchetype == null
                    ? Map.of() : Map.copyOf(successfulScenariosByArchetype);
            failureClassCounts = failureClassCounts == null ? Map.of() : Map.copyOf(failureClassCounts);
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
}
