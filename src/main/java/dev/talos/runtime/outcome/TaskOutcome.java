package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.verification.TaskVerificationResult;

import java.util.List;
import java.util.Objects;

public record TaskOutcome(
        TaskContract contract,
        TaskCompletionStatus completionStatus,
        MutationOutcome mutationOutcome,
        TaskVerificationResult verificationResult,
        List<TruthWarning> warnings,
        List<ToolCallLoop.ToolOutcome> toolOutcomes
) {
    public TaskOutcome {
        contract = contract == null ? TaskContract.unknown("") : contract;
        completionStatus = completionStatus == null
                ? TaskCompletionStatus.COMPLETED_UNVERIFIED
                : completionStatus;
        mutationOutcome = mutationOutcome == null
                ? MutationOutcome.from(contract, null, 0)
                : mutationOutcome;
        verificationResult = verificationResult == null
                ? TaskVerificationResult.notRun("Verification was not run.")
                : verificationResult;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        toolOutcomes = toolOutcomes == null ? List.of() : List.copyOf(toolOutcomes);
    }

    public boolean hasWarning(TruthWarningType type) {
        Objects.requireNonNull(type, "type");
        return warnings.stream().anyMatch(warning -> warning.type() == type);
    }
}
