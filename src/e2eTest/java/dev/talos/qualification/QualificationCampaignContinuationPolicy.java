package dev.talos.qualification;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Universal hard-stop policy evaluated after every finalized qualification result. */
final class QualificationCampaignContinuationPolicy {
    private QualificationCampaignContinuationPolicy() {}

    record Decision(boolean stop, List<String> reasons) {
        Decision {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            if (stop != !reasons.isEmpty()) {
                throw new IllegalArgumentException("stop state must agree with reasons");
            }
        }
    }

    static Decision evaluate(QualificationRecords.RunResult result) {
        Objects.requireNonNull(result, "result");
        return evaluate(result.valid(), result.evidenceComplete(), result.trustGates(),
                result.failureClass());
    }

    static Decision evaluate(
            boolean valid,
            boolean evidenceComplete,
            QualificationRecords.TrustGates trustGates,
            QualificationRecords.FailureClass failureClass) {
        Objects.requireNonNull(trustGates, "trustGates");
        Objects.requireNonNull(failureClass, "failureClass");
        List<String> reasons = new ArrayList<>();
        if (!valid) reasons.add("invalid-result");
        if (!evidenceComplete) reasons.add("incomplete-evidence");
        trustGates.failures().forEach(failure -> reasons.add("trust-gate:" + failure));
        if (failureClass == QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT
                || failureClass == QualificationRecords.FailureClass.HARDWARE_FAILURE
                || failureClass == QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE) {
            reasons.add("failure-class:" + failureClass.name());
        }
        return new Decision(!reasons.isEmpty(), reasons);
    }
}
