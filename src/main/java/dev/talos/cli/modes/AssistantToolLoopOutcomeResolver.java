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

    record Resolution(String answer, String extraSummary) {
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
        if (plan == null) {
            throw new IllegalArgumentException(
                    "captured retry plan is required");
        }
        CurrentTurnPlan safePlan = plan;
        answer = PostToolSynthesisRetry.synthesizeIfNeeded(
                answer,
                loopResult.toolsInvoked(),
                messages,
                retryMessages -> TurnModelDispatcher.dispatchBufferedRetry(
                        ctx,
                        retryMessages,
                        safePlan,
                        RetryDispatchPurpose.POST_TOOL_SYNTHESIS));

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

        ToolCallLoop.LoopResult outcomeLoopResult = mutationRetry.retryLoopResult() != null
                ? MissingMutationRetry.mergeEvidence(loopResult, mutationRetry.retryLoopResult())
                : inspectRetry.loopResult() != null ? inspectRetry.loopResult() : loopResult;
        ReadEvidenceHandoff.Result evidenceRecovery = ReadEvidenceHandoff.readEvidenceRecoveryForPartialTargetsIfNeeded(
                answer, messages, safePlan, outcomeLoopResult, workspace, ctx);
        if (evidenceRecovery.loopResult() != null) {
            answer = evidenceRecovery.answer();
            outcomeLoopResult = evidenceRecovery.loopResult();
        }
        int outcomeExtraMutationSuccesses = 0;

        phaseTransition.moveToVerify(outcomeLoopResult, outcomeExtraMutationSuccesses);

        String finalAnswer = answerShaper.shape(
                answer,
                outcomeLoopResult,
                outcomeExtraMutationSuccesses,
                mutationRetry.actionObligationFailed());

        return new Resolution(
                finalAnswer,
                joinExtraSummaries(
                        visibleToolLoopSummary(loopResult, mutationRetry, inspectRetry, safePlan, workspace, messages),
                        evidenceRecovery.extraSummary()));
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
