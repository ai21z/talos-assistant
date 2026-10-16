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

    record Resolution(String answer, String extraSummary, boolean outputLimitReached) {
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

    @FunctionalInterface
    interface SelectedToolAnswerShaper {
        AnswerOutputLimit.Presentation shape(String answer, ToolCallLoop.LoopResult loopResult,
                                             int extraMutationSuccesses, boolean actionObligationFailed);
    }

    @FunctionalInterface
    interface SelectedNoToolAnswerShaper {
        AnswerOutputLimit.Presentation shape(String answer, boolean actionObligationFailed);
    }

    static Resolution resolve(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Path workspace,
            Context ctx,
            PhaseTransition phaseTransition,
            ToolAnswerShaper toolAnswerShaper,
            NoToolAnswerShaper noToolAnswerShaper
    ) {
        return resolveSelected(answer, false, messages, plan, workspace, ctx, phaseTransition,
                (text, loop, mutations, failed) -> new AnswerOutputLimit.Presentation(
                        toolAnswerShaper.shape(text, loop, mutations, failed), true),
                (text, failed) -> new AnswerOutputLimit.Presentation(noToolAnswerShaper.shape(text, failed), true));
    }

    static Resolution resolveSelected(
            String answer, boolean initialOutputLimitReached, List<ChatMessage> messages, CurrentTurnPlan plan,
            Path workspace, Context ctx, PhaseTransition phaseTransition,
            SelectedToolAnswerShaper toolAnswerShaper, SelectedNoToolAnswerShaper noToolAnswerShaper) {
        if (plan == null) {
            throw new IllegalArgumentException(
                    "captured retry plan is required");
        }
        CurrentTurnPlan safePlan = plan;
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
                    return resolution(
                            toolAnswerShaper.shape(
                                    debrisRetry.answer(),
                                    verificationLoop,
                                    extraMutationSuccesses,
                                    debrisRetry.actionObligationFailed()),
                            debrisRetry.extraSummary(), debrisRetry.outputLimit().apply(initialOutputLimitReached));
                }
            }
            return resolution(noToolAnswerShaper.shape(answer, false), null, initialOutputLimitReached);
        }

        ToolCallLoop.LoopResult noToolLoopResult = emptyNoToolLoopResult(answer, messages);
        MissingMutationRetry.Result mutationRetry = mutationRetryIfNeeded(
                answer, messages, safePlan, noToolLoopResult, workspace, ctx);
        boolean outputLimitReached = mutationRetry.outputLimit().apply(initialOutputLimitReached);
        if (mutationRetry.extraSummary() != null || mutationRetry.mutationsInRetry() > 0) {
            ToolCallLoop.LoopResult verificationLoop = mutationRetry.retryLoopResult() == null
                    ? noToolLoopResult
                    : mutationRetry.retryLoopResult();
            int extraMutationSuccesses = mutationRetry.retryLoopResult() == null
                    ? mutationRetry.mutationsInRetry()
                    : 0;
            phaseTransition.moveToVerify(verificationLoop, extraMutationSuccesses);
            return resolution(
                    toolAnswerShaper.shape(
                            mutationRetry.answer(),
                            verificationLoop,
                            extraMutationSuccesses,
                            mutationRetry.actionObligationFailed()),
                    mutationRetry.extraSummary(), outputLimitReached);
        }

        ReadEvidenceHandoff.Result readEvidenceHandoff = ReadEvidenceHandoff.readEvidenceHandoffIfNeeded(
                mutationRetry.answer(), messages, safePlan, workspace, ctx);
        if (readEvidenceHandoff.loopResult() != null) {
            return resolution(
                    toolAnswerShaper.shape(
                            readEvidenceHandoff.answer(),
                            readEvidenceHandoff.loopResult(),
                            0,
                            false),
                    AnswerGroundingDisclosure.toolLoopSummary(
                            readEvidenceHandoff.loopResult(), safePlan, workspace, messages),
                    readEvidenceHandoff.outputLimit().apply(outputLimitReached));
        }

        ReadOnlyInspectionRetry.Result inspectionRetry = ReadOnlyInspectionRetry.retryIfNeeded(
                mutationRetry.answer(),
                messages,
                safePlan,
                workspace,
                ctx,
                retryMessages -> TurnModelDispatcher.dispatchBufferedRetry(
                        ctx,
                        retryMessages,
                        safePlan,
                        RetryDispatchPurpose.READ_ONLY_INSPECTION));
        if (inspectionRetry.loopResult() != null) {
            return resolution(
                    toolAnswerShaper.shape(
                            inspectionRetry.answer(),
                            inspectionRetry.loopResult(),
                            0,
                            false),
                    AnswerGroundingDisclosure.toolLoopSummary(
                            inspectionRetry.loopResult(), safePlan, workspace, messages),
                    inspectionRetry.outputLimit().apply(outputLimitReached));
        }

        if (safePlan.workspaceResultsApplicable()) {
            return resolution(toolAnswerShaper.shape(inspectionRetry.answer(), noToolLoopResult, 0, false),
                    null, inspectionRetry.outputLimit().apply(outputLimitReached));
        }
        return resolution(
                noToolAnswerShaper.shape(
                        inspectionRetry.answer(),
                        mutationRetry.actionObligationFailed()),
                AnswerGroundingDisclosure.zeroReadWorkspaceNote(safePlan),
                inspectionRetry.outputLimit().apply(outputLimitReached));
    }

    private static Resolution resolution(AnswerOutputLimit.Presentation shaped, String summary, boolean reached) {
        return new Resolution(shaped.answer(), summary, shaped.outputLimit().apply(reached));
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
                                retryToolSpecs,
                                RetryDispatchPurpose.MISSING_MUTATION));
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
