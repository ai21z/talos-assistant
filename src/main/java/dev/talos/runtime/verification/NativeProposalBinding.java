package dev.talos.runtime.verification;

import dev.talos.runtime.task.AnswerResultRequirements;

import java.util.List;
import java.util.Objects;

import static dev.talos.runtime.verification.ReadOnlyAnswerProposal.*;

/** Catalog selections are carried with their original coordinates. */
record NativeProposalBinding(Proposal proposal, Span targetSpan, List<BoundResult> results, boolean requestCovered,
                             List<ReadOnlyAnswerInterpretation> interpretations) {
    NativeProposalBinding {
        Objects.requireNonNull(proposal);
        Objects.requireNonNull(targetSpan);
        results = List.copyOf(results);
        interpretations = List.copyOf(interpretations);
        if (results.isEmpty() || results.size() > MAX_ITEMS || results.size() != proposal.results().size()) {
            throw rejected("NATIVE_RESULT_BINDING_INVALID");
        }
        long selected = interpretations.stream()
                .filter(value -> value.decision() == ReadOnlyAnswerInterpretation.Decision.SELECTED).count();
        if (interpretations.size() > MAX_ITEMS || selected > 8 || results.size() + selected > MAX_ITEMS) {
            throw rejected("NATIVE_INTERPRETATION_BOUND");
        }
    }

    NativeProposalBinding(Proposal proposal, Span targetSpan, List<BoundResult> results, boolean requestCovered) {
        this(proposal, targetSpan, results, requestCovered, List.of());
    }

    record BoundResult(String slotId, AnswerResultRequirements.Slot slot, Span formulaSpan,
                       List<Span> operandSpans, Span precisionSpan, String rejectionReason) {
        BoundResult {
            Objects.requireNonNull(slotId);
            Objects.requireNonNull(slot);
            operandSpans = List.copyOf(operandSpans);
            rejectionReason = Objects.requireNonNull(rejectionReason);
            if (operandSpans.size() > MAX_ITEMS) throw rejected("NATIVE_OPERAND_BINDING_INVALID");
        }
    }
}
