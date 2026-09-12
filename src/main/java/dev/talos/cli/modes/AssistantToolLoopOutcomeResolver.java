package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;

import java.nio.file.Path;
import java.util.List;

final class AssistantToolLoopOutcomeResolver {
    private AssistantToolLoopOutcomeResolver() {
    }

    record Resolution(String answer, String extraSummary, boolean outputLimitReached) {
    }

    @FunctionalInterface
    interface PhaseTransition {
        void moveToVerify(ToolCallLoop.LoopResult loopResult, int extraMutationSuccesses);
    }

    @FunctionalInterface
    interface AnswerShaper {
        String shape(
                String answer,
                ToolCallLoop.LoopResult loopResult,
                int extraMutationSuccesses,
                boolean actionObligationFailed);
    }

    @FunctionalInterface
    interface SelectedAnswerShaper {
        AnswerOutputLimit.Presentation shape(String answer, ToolCallLoop.LoopResult loopResult,
                                             int extraMutationSuccesses, boolean actionObligationFailed);
    }

    static Resolution resolve(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            Context ctx,
            PhaseTransition phaseTransition,
            AnswerShaper answerShaper
    ) {
        return resolveSelected(answer, messages, plan, loopResult, workspace, ctx, phaseTransition,
                (text, loop, mutations, failed) -> new AnswerOutputLimit.Presentation(
                        answerShaper.shape(text, loop, mutations, failed), true));
    }

    static Resolution resolveSelected(
            String answer, List<ChatMessage> messages, CurrentTurnPlan plan, ToolCallLoop.LoopResult loopResult,
            Path workspace, Context ctx, PhaseTransition phaseTransition, SelectedAnswerShaper answerShaper) {
        if (plan == null) {
            throw new IllegalArgumentException(
                    "captured retry plan is required");
        }
        CurrentTurnPlan safePlan = plan;
        boolean outputLimitReached = loopResult.finalAnswerOutputLimitReached();
        if (loopResult.readOnlySynthesisStatus() != ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED) {
            // The bounded synthesis owns its single chance to answer, including
            // rejection. Do not let any later speculative repair dispatch again.
            // Verification and final truthfulness/privacy shaping still run.
            phaseTransition.moveToVerify(loopResult, 0);
            AnswerOutputLimit.Presentation finalAnswer = answerShaper.shape(
                    answer, loopResult, 0, false);
            return new Resolution(
                    finalAnswer.answer(),
                    AnswerGroundingDisclosure.toolLoopSummary(loopResult, safePlan, workspace, messages),
                    finalAnswer.outputLimit().apply(outputLimitReached));
        }
        PostToolSynthesisRetry.Result synthesis = PostToolSynthesisRetry.selectSynthesisIfNeeded(
                answer,
                loopResult.toolsInvoked(),
                messages,
                retryMessages -> TurnModelDispatcher.dispatchBufferedRetry(
                        ctx,
                        retryMessages,
                        safePlan,
                        RetryDispatchPurpose.POST_TOOL_SYNTHESIS));
        answer = synthesis.answer();
        outputLimitReached = synthesis.outputLimit().apply(outputLimitReached);

        MissingMutationRetry.Result mutationRetry = MissingMutationRetry.retryIfNeeded(
                answer,
                messages,
                safePlan,
                loopResult,
                workspace,
                ctx,
                (retryMessages, retryPlan, retryToolSpecs) ->
                        TurnModelDispatcher.dispatchEscalatedRetry(
                                ctx,
                                retryMessages,
                                retryPlan,
                                retryToolSpecs,
                                RetryDispatchPurpose.MISSING_MUTATION));
        answer = mutationRetry.answer();
        outputLimitReached = mutationRetry.outputLimit().apply(outputLimitReached);

        if (mutationRetry.retryLoopResult() != null
                && mutationRetry.retryLoopResult().readOnlySynthesisStatus()
                    != ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED) {
            ToolCallLoop.LoopResult selected = MissingMutationRetry.mergeEvidence(
                    loopResult, mutationRetry.retryLoopResult());
            phaseTransition.moveToVerify(selected, 0);
            AnswerOutputLimit.Presentation shaped = answerShaper.shape(
                    answer, selected, 0, mutationRetry.actionObligationFailed());
            return new Resolution(shaped.answer(),
                    visibleToolLoopSummary(loopResult, mutationRetry, null, safePlan, workspace, messages),
                    shaped.outputLimit().apply(outputLimitReached));
        }

        InspectCompletenessRetry.Result inspectRetry = InspectCompletenessRetry.retryIfNeeded(
                answer,
                messages,
                safePlan,
                loopResult,
                workspace,
                ctx,
                retryMessages -> TurnModelDispatcher.dispatchBufferedRetry(
                        ctx,
                        retryMessages,
                        safePlan,
                        RetryDispatchPurpose.INSPECT_COMPLETENESS));
        answer = inspectRetry.answer();
        outputLimitReached = inspectRetry.outputLimit().apply(outputLimitReached);

        ToolCallLoop.LoopResult outcomeLoopResult = mutationRetry.retryLoopResult() != null
                ? MissingMutationRetry.mergeEvidence(loopResult, mutationRetry.retryLoopResult())
                : inspectRetry.loopResult() != null ? inspectRetry.loopResult() : loopResult;
        if (inspectRetry.loopResult() != null
                && inspectRetry.loopResult().readOnlySynthesisStatus()
                    != ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED) {
            // A nested inspection loop can own the sole synthesis too. Select its
            // matching result before any deterministic evidence-handoff recovery.
            outcomeLoopResult = inspectRetry.loopResult();
            phaseTransition.moveToVerify(outcomeLoopResult, 0);
            AnswerOutputLimit.Presentation shaped = answerShaper.shape(
                    answer, outcomeLoopResult, 0, mutationRetry.actionObligationFailed());
            return new Resolution(shaped.answer(),
                    visibleToolLoopSummary(loopResult, mutationRetry, inspectRetry, safePlan, workspace, messages),
                    shaped.outputLimit().apply(outputLimitReached));
        }
        ReadEvidenceHandoff.Result evidenceRecovery = ReadEvidenceHandoff.readEvidenceRecoveryForPartialTargetsIfNeeded(
                answer, messages, safePlan, outcomeLoopResult, workspace, ctx);
        if (evidenceRecovery.loopResult() != null) {
            answer = evidenceRecovery.answer();
            outputLimitReached = evidenceRecovery.outputLimit().apply(outputLimitReached);
            outcomeLoopResult = evidenceRecovery.loopResult();
        }
        int outcomeExtraMutationSuccesses = 0;

        phaseTransition.moveToVerify(outcomeLoopResult, outcomeExtraMutationSuccesses);

        AnswerOutputLimit.Presentation finalAnswer = answerShaper.shape(
                answer,
                outcomeLoopResult,
                outcomeExtraMutationSuccesses,
                mutationRetry.actionObligationFailed());

        return new Resolution(
                finalAnswer.answer(),
                joinExtraSummaries(
                        visibleToolLoopSummary(loopResult, mutationRetry, inspectRetry, safePlan, workspace, messages),
                        evidenceRecovery.extraSummary()), finalAnswer.outputLimit().apply(outputLimitReached));
    }

    private static String visibleToolLoopSummary(
            ToolCallLoop.LoopResult loopResult,
            MissingMutationRetry.Result mutationRetry,
            InspectCompletenessRetry.Result inspectRetry,
            CurrentTurnPlan plan,
            Path workspace,
            List<ChatMessage> messages
    ) {
        String baseSummary = loopResult == null
                ? null
                : AnswerGroundingDisclosure.toolLoopSummary(loopResult, plan, workspace, messages);
        String mutationRetrySummary = mutationRetry == null ? null : mutationRetry.extraSummary();
        if (inspectRetry != null && inspectRetry.loopResult() != null) {
            String inspectSummary = AnswerGroundingDisclosure.toolLoopSummary(
                    inspectRetry.loopResult(), plan, workspace, messages);
            return joinExtraSummaries(mutationRetrySummary, inspectSummary);
        }
        String withMutationRetry = joinExtraSummaries(baseSummary, mutationRetrySummary);
        return joinExtraSummaries(withMutationRetry, inspectRetry == null ? null : inspectRetry.extraSummary());
    }

    private static String joinExtraSummaries(String first, String second) {
        if ((first == null || first.isBlank()) && (second == null || second.isBlank())) return null;
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + "\n\n" + second;
    }
}
