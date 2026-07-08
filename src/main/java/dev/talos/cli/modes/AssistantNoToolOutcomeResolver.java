package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;

import java.nio.file.Path;
import java.util.List;

final class AssistantNoToolOutcomeResolver {
    private AssistantNoToolOutcomeResolver() {
    }

    record Resolution(String answer, String extraSummary) {
    }

    @FunctionalInterface
    interface RetryPlanResolver {
        CurrentTurnPlan resolve(List<ChatMessage> messages);
    }

    @FunctionalInterface
    interface PhaseTransition {
        void moveToVerify(ToolCallLoop.LoopResult loopResult, int extraMutationSuccesses);
    }

    @FunctionalInterface
    interface ToolAnswerShaper {
        String shape(
                String answer,
                ToolCallLoop.LoopResult loopResult,
                int extraMutationSuccesses,
                boolean actionObligationFailed);
    }

    @FunctionalInterface
    interface NoToolAnswerShaper {
        String shape(String answer, boolean actionObligationFailed);
    }

    static Resolution resolve(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Path workspace,
            Context ctx,
            RetryPlanResolver retryPlanResolver,
            PhaseTransition phaseTransition,
            ToolAnswerShaper toolAnswerShaper,
            NoToolAnswerShaper noToolAnswerShaper
    ) {
        CurrentTurnPlan safePlan = safePlan(plan, messages, retryPlanResolver);
        if (ToolCallParser.looksLikeMalformedProtocolArrayDebris(answer)
                || ToolCallParser.looksLikeMalformedToolProtocol(answer)) {
            // T743: on mutation/workspace-obligation turns, malformed protocol
            // debris gets one bounded MissingMutationRetry pass before the
            // no-action notice.
            boolean retryableObligation = safePlan != null
                    && safePlan.taskContract() != null
                    && safePlan.taskContract().mutationAllowed()
                    && ResponseObligationVerifier.unsatisfiedNoToolResponse(
                            safePlan.actionObligation(), answer);
            if (retryableObligation) {
                ToolCallLoop.LoopResult debrisLoop = emptyNoToolLoopResult(answer, messages);
                MissingMutationRetry.Result debrisRetry = mutationRetryIfNeeded(
                        answer, messages, safePlan, debrisLoop, workspace, ctx);
                boolean retryMutated = debrisRetry.mutationsInRetry() > 0
                        || (debrisRetry.retryLoopResult() != null
                                && debrisRetry.retryLoopResult().mutatingToolSuccesses() > 0);
                if (retryMutated) {
                    ToolCallLoop.LoopResult verificationLoop = debrisRetry.retryLoopResult() == null
                            ? debrisLoop
                            : debrisRetry.retryLoopResult();
                    int extraMutationSuccesses = debrisRetry.retryLoopResult() == null
                            ? debrisRetry.mutationsInRetry()
                            : 0;
                    phaseTransition.moveToVerify(verificationLoop, extraMutationSuccesses);
                    return new Resolution(
                            toolAnswerShaper.shape(
                                    debrisRetry.answer(),
                                    verificationLoop,
                                    extraMutationSuccesses,
                                    debrisRetry.actionObligationFailed()),
                            debrisRetry.extraSummary());
                }
            }
            return new Resolution(noToolAnswerShaper.shape(answer, false), null);
        }

        ToolCallLoop.LoopResult noToolLoopResult = emptyNoToolLoopResult(answer, messages);
        MissingMutationRetry.Result mutationRetry = mutationRetryIfNeeded(
                answer, messages, safePlan, noToolLoopResult, workspace, ctx);
        if (mutationRetry.extraSummary() != null || mutationRetry.mutationsInRetry() > 0) {
            ToolCallLoop.LoopResult verificationLoop = mutationRetry.retryLoopResult() == null
                    ? noToolLoopResult
                    : mutationRetry.retryLoopResult();
            int extraMutationSuccesses = mutationRetry.retryLoopResult() == null
                    ? mutationRetry.mutationsInRetry()
                    : 0;
            phaseTransition.moveToVerify(verificationLoop, extraMutationSuccesses);
            return new Resolution(
                    toolAnswerShaper.shape(
                            mutationRetry.answer(),
                            verificationLoop,
                            extraMutationSuccesses,
                            mutationRetry.actionObligationFailed()),
                    mutationRetry.extraSummary());
        }

        ReadEvidenceHandoff.Result readEvidenceHandoff = ReadEvidenceHandoff.readEvidenceHandoffIfNeeded(
                mutationRetry.answer(), messages, safePlan, workspace, ctx);
        if (readEvidenceHandoff.loopResult() != null) {
            return new Resolution(
                    toolAnswerShaper.shape(
                            readEvidenceHandoff.answer(),
                            readEvidenceHandoff.loopResult(),
                            0,
                            false),
                    AnswerGroundingDisclosure.toolLoopSummary(
                            readEvidenceHandoff.loopResult(), safePlan, workspace, messages));
        }

        ReadOnlyInspectionRetry.Result inspectionRetry = ReadOnlyInspectionRetry.retryIfNeeded(
                mutationRetry.answer(),
                messages,
                safePlan,
                workspace,
                ctx,
                retryMessages -> TurnModelDispatcher.dispatchBuffered(
                        ctx,
                        retryMessages,
                        retryPlanResolver.resolve(retryMessages)));
        if (inspectionRetry.loopResult() != null) {
            return new Resolution(
                    toolAnswerShaper.shape(
                            inspectionRetry.answer(),
                            inspectionRetry.loopResult(),
                            0,
                            false),
                    AnswerGroundingDisclosure.toolLoopSummary(
                            inspectionRetry.loopResult(), safePlan, workspace, messages));
        }

        return new Resolution(
                noToolAnswerShaper.shape(
                        inspectionRetry.answer(),
                        mutationRetry.actionObligationFailed()),
                AnswerGroundingDisclosure.zeroReadWorkspaceNote(safePlan));
    }

    private static CurrentTurnPlan safePlan(
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            RetryPlanResolver retryPlanResolver
    ) {
        return plan == null ? retryPlanResolver.resolve(messages) : plan;
    }

    private static MissingMutationRetry.Result mutationRetryIfNeeded(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan safePlan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            Context ctx
    ) {
        return MissingMutationRetry.retryIfNeeded(
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
                                retryToolSpecs));
    }

    private static ToolCallLoop.LoopResult emptyNoToolLoopResult(
            String answer,
            List<ChatMessage> messages
    ) {
        return new ToolCallLoop.LoopResult(
                answer == null ? "" : answer,
                0,
                0,
                List.of(),
                messages,
                0,
                0,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0);
    }
}
