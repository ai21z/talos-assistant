package dev.talos.runtime.trace;

import dev.talos.runtime.outcome.MutationRecoveryAssessment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds redacted, closed-vocabulary evidence for the final mutation-recovery decision. */
final class MutationRecoveryTraceEventFactory {
    static final String EVENT_TYPE = "MUTATION_FAILURE_RECOVERY_EVALUATED";

    private MutationRecoveryTraceEventFactory() {}

    static List<TurnTraceEvent> evaluated(MutationRecoveryAssessment assessment) {
        if (assessment == null || assessment.failureAssessments().isEmpty()) {
            return List.of();
        }
        return assessment.failureAssessments().stream()
                .map(MutationRecoveryTraceEventFactory::evaluated)
                .toList();
    }

    private static TurnTraceEvent evaluated(
            MutationRecoveryAssessment.FailureAssessment assessment
    ) {
        if (assessment.recovered()
                && (assessment.obligationSha256().isBlank()
                    || !assessment.obligationSha256().equals(
                            assessment.verifiedObligationSha256()))) {
            throw new IllegalArgumentException(
                    "Recovered mutation trace requires one exact verified request obligation");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", 2);
        data.put("failureOrdinal", assessment.failureOrdinal());
        data.put("repairOrdinal", assessment.repairOrdinal());
        data.put("failureToolCallId", assessment.failureToolCallId());
        data.put("repairToolCallId", assessment.repairToolCallId());
        data.put("decision", assessment.decision().name());
        data.put("resolved", assessment.resolved());
        data.put("recovered", assessment.recovered());
        data.put("failureReason", assessment.failureReason().name());
        data.put("targetHint", TraceRedactor.pathHint(assessment.target()));
        data.put("targetSha256", TraceRedactor.hash(assessment.target()));
        data.put("requestObligationSha256", assessment.obligationSha256());
        data.put(
                "verifiedRequestObligationSha256",
                assessment.verifiedObligationSha256());
        data.put(
                "coveredSuccessfulMutationToolCallIds",
                assessment.coveredToolCallIds());
        data.put("verificationSource", assessment.verificationSource().name());
        data.put("verificationStatus", assessment.verificationStatus().name());
        return TurnTraceEvent.simple(
                EVENT_TYPE,
                Instant.now().toString(),
                Map.copyOf(data));
    }
}
