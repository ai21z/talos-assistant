package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.IncompleteGenerationPresentation;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import dev.talos.spi.types.ProviderResponseCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Whether a round in which every call succeeded, with no expected target left, ends the turn (P20, finding LA-F3, D14
 * option A, D16 option A). Target progress cannot show that a request with several changes of one file is done after
 * its first change, so the turn ends at once only when request-bound proof already holds on the outcomes so far, when
 * the request's single authorized workspace operation was performed and nothing else was requested, when no further
 * round could run, or when no re-prompt can be assembled because the turn has no captured request resolution.
 * Otherwise the model is asked again with the tool results, under the same tool surface and approval gates.
 *
 * <p>The continuation is optional work after completed changes. Once one was issued, a later request that fails, a
 * reply that a watchdog, a stream close or the transport ended, and an incomplete reply leave the turn as it stood
 * after its last completed change: it ends with the summaries of every change. Operator cancellation and thread
 * interruption keep their own handling. Only typed state decides, and no request text is read here.
 */
final class MutationContinuationDecision {
    private static final Logger LOG = LoggerFactory.getLogger(MutationContinuationDecision.class);

    enum Kind {
        /** Request-bound proof holds, so the summaries of the changes are the answer, as in the P0 skip. */
        PROOF_HELD,
        /** The single authorized workspace operation was performed and nothing else was requested. */
        OPERATION_COMPLETE,
        /** No request-bound proof holds and a further round can run, so the model is asked again. */
        CONTINUED,
        /** No further round can run, so the summaries of the changes are the answer. */
        LIMIT_REACHED,
        /** The turn has no captured request resolution, so no re-prompt can be assembled for it. */
        REQUEST_NOT_CAPTURED
    }

    /** The decision with the proof it was taken on. A null proof status means the proof was not evaluated. */
    record Decision(Kind kind, TaskVerificationStatus proofStatus, boolean requestBound) {
        boolean continues() {
            return kind == Kind.CONTINUED;
        }
    }

    private MutationContinuationDecision() {
    }

    /**
     * Decides and records the decision. The proof is evaluated through the verifier entry that records no trace
     * events, so the decision record is the only trace event this adds. A continuation is counted on the state.
     */
    static Decision decide(LoopState state) {
        Decision decision = evaluate(state);
        if (decision.continues()) state.mutationContinuations++;
        LocalTurnTraceCapture.recordMutationContinuationDecided(
                decision.kind().name(),
                state == null ? 0 : state.iterations,
                state == null ? 0 : state.maxIterations,
                decision.proofStatus() == null ? "NOT_EVALUATED" : decision.proofStatus().name(),
                decision.requestBound());
        return decision;
    }

    /** A continuation was issued in this turn after at least one completed change. */
    static boolean continuationIssued(LoopState state) {
        return state != null && state.mutationContinuations > 0 && state.mutatingToolSuccesses > 0;
    }

    /**
     * Ends the turn with the summaries of every change when, after a continuation, a request could not be sent or
     * failed (review findings D1 and C2 of P20 and the second review). Returns false when no continuation was issued,
     * so the caller's own handling applies.
     */
    static boolean endUnanswered(LoopState state, String reason, Throwable failure) {
        return endUnanswered(state, reason, failure == null ? "" : failure.getClass().getSimpleName());
    }

    /**
     * Ends the turn with the summaries of every change when a reply after a continuation is unusable: ended by a
     * watchdog, a stream close or the transport, or incomplete. An operator cancellation and a thread interruption
     * are left to LoopState, which raises them as before. Returns false when the reply is usable or no continuation
     * was issued.
     */
    static boolean endIfReplyUnusable(LoopState state, LlmClient.StreamResult result) {
        if (!continuationIssued(state) || result == null) return false;
        if (result.aborted()) {
            ProviderResponseCapture.AbortOrigin origin = result.abortOrigin();
            if (origin == ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL
                    || origin == ProviderResponseCapture.AbortOrigin.RUNTIME_THREAD_INTERRUPT) {
                return false;
            }
            LocalTurnTraceCapture.recordModelGenerationAborted(origin,
                    result.abandonment().orElse(null), !result.text().isBlank());
            return endUnanswered(state, "GENERATION_ABORTED", origin.name());
        }
        if (IncompleteGenerationPresentation.required(result)) {
            return endUnanswered(state, "INCOMPLETE_REPLY", result.completion().name());
        }
        return false;
    }

    /** PROVIDER_FAILURE for an engine failure, REQUEST_FAILED for any other failure of the request. */
    static String failureReason(Throwable failure) {
        return failure instanceof dev.talos.spi.EngineException ? "PROVIDER_FAILURE" : "REQUEST_FAILED";
    }

    private static boolean endUnanswered(LoopState state, String reason, String failureType) {
        if (!continuationIssued(state)) return false;
        LOG.warn("A continuation after completed changes got no usable reply ({}, {}). The turn ends with the changes.",
                reason, failureType);
        state.finishWithToolAnswer(String.join("\n", state.pendingMutationSummaries));
        LocalTurnTraceCapture.recordMutationContinuationUnanswered(reason, failureType);
        return true;
    }

    private static Decision evaluate(LoopState state) {
        // The engine runs no call of a reply that arrives once the limit is reached, so asking again would only end
        // the turn with an iteration-limit notice instead of the summaries of the changes.
        if (state == null || state.iterations >= state.maxIterations) {
            return new Decision(Kind.LIMIT_REACHED, null, false);
        }
        // ToolRepromptRequestBuilder assembles a re-prompt only for a turn whose request resolution is captured, as
        // every production turn's is. A compatibility caller without one ends as it did before P20.
        if (TurnTaskContractCapture.getResolution() == null) {
            return new Decision(Kind.REQUEST_NOT_CAPTURED, null, false);
        }
        TaskContract contract = TurnTaskContractCapture.get();
        WorkspaceOperationAuthorityPlan plan = TurnTaskContractCapture.workspaceOperationPlan();
        if (singleOperationPerformed(state, contract, plan)) {
            return new Decision(Kind.OPERATION_COMPLETE, null, false);
        }
        TaskVerificationResult proof = proof(state, contract, plan);
        boolean requestBound = proof.status() == TaskVerificationStatus.PASSED && proof.requestBound();
        return new Decision(requestBound ? Kind.PROOF_HELD : Kind.CONTINUED, proof.status(), requestBound);
    }

    /**
     * A request made of one authorized workspace operation and no expected target, as StaticTaskVerifier treats a
     * pure typed operation, whose operation succeeded. Its plan authorizes no further operation and no file target.
     */
    private static boolean singleOperationPerformed(
            LoopState state,
            TaskContract contract,
            WorkspaceOperationAuthorityPlan plan
    ) {
        if (plan == null || plan.state() != WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE) return false;
        if (contract == null || !contract.expectedTargets().isEmpty()) return false;
        return state.toolOutcomes.stream().anyMatch(outcome -> outcome != null
                && outcome.success()
                && outcome.mutating()
                && outcome.workspaceOperationPlan() != null);
    }

    /** The post-apply verification of the outcomes so far, with the inputs the post-loop verification uses. */
    private static TaskVerificationResult proof(
            LoopState state,
            TaskContract contract,
            WorkspaceOperationAuthorityPlan plan
    ) {
        if (state.workspace == null) return TaskVerificationResult.notRun("");
        if (contract == null || !contract.mutationAllowed() || !contract.verificationRequired()) {
            return TaskVerificationResult.notRun("");
        }
        ToolCallLoop.LoopResult snapshot = new ToolCallLoop.LoopResult(
                state.currentText,
                state.iterations,
                state.totalToolsInvoked,
                List.copyOf(state.toolNames),
                state.messages,
                state.failedCalls,
                state.retriedCalls,
                false,
                state.mutatingToolSuccesses,
                List.copyOf(state.pathsReadThisTurn),
                state.cushionFiresRedundantRead,
                0,
                state.cushionFiresB3EditShortCircuit,
                state.cushionFiresE1Suggestion,
                state.failureDecision,
                List.copyOf(state.toolOutcomes),
                state.readEvidence.latestReadFileBodiesByNormalizedPath());
        return StaticTaskVerifier.verifyWithoutTraceEvents(state.workspace, contract, snapshot, 0, plan);
    }
}
