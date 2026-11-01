package dev.talos.runtime.toolcall;

import dev.talos.core.tool.ToolNamePolicy;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolFailureReason;

import java.util.List;
import java.util.Optional;

/**
 * One corrective native re-prompt after a text-form tool call in native tool mode (MICA decision D7).
 * The rejected call never runs. Any other authority rejection, a dispatched call or a second mismatch keeps the stop.
 */
final class ToolModeCorrectionReprompt {
    static final String NOTICE_PREFIX = "[Runtime notice]";
    static final String NOTICE = NOTICE_PREFIX + " The previous reply wrote a tool call as text. Text tool calls are"
            + " not executed in this tool mode, and nothing was run. Call the tool through the native tool interface,"
            + " or answer without tools.";
    static final String NOT_OFFERED_NOTICE = NOTICE_PREFIX + " The previous reply wrote a tool call as text for a tool"
            + " that is not offered in this request. Text tool calls are not executed in this tool mode, and nothing"
            + " was run. Call an offered tool through the native tool interface, or answer without tools.";

    private ToolModeCorrectionReprompt() {
    }

    static Optional<Boolean> tryHandle(LoopState state, ToolCallExecutionStage.IterationOutcome outcome) {
        if (!eligible(state, outcome)) return Optional.empty();
        // The rejected response earned no tool authority, so the correction offers the tools of the rejected request.
        List<ToolSpec> tools = ToolRepromptRequestBuilder.offeredNativeToolSpecs(state);
        if (tools.isEmpty()) return Optional.empty();
        ToolRepromptObligationSelector.Selection obligation = ToolRepromptObligationSelector.select(state, outcome);
        state.toolModeCorrectionSent = true;
        TurnAuditCapture.recordToolModeCorrectionSent();
        state.messages.add(ChatMessage.user(rejectedCallsOffered(state, tools) ? NOTICE : NOT_OFFERED_NOTICE));
        // A runtime notice is never a kept tool result.
        state.noticeMessageIndices.add(state.messages.size() - 1);
        return Optional.of(ToolRepromptOverlayContinuation.execute(
                state,
                obligation.remainingRepairTargets(),
                obligation.remainingExpectedTargets(),
                TurnTaskContractCapture.originalUserRequestOrResolve(state.messages),
                obligation.staticRepairObligationActive(),
                tools));
    }

    static boolean eligible(LoopState state, ToolCallExecutionStage.IterationOutcome outcome) {
        // After a tool ran in the turn, the gathered evidence decides the stop instead of a further generation.
        // Retry stages start new tool loops in the same turn, so both facts are also read from the turn audit.
        if (state == null || outcome == null || state.toolModeCorrectionSent || state.totalToolsInvoked > 0
                || TurnAuditCapture.toolModeCorrectionSentThisTurn() || TurnAuditCapture.anyToolExecutedThisTurn()
                || state.iterations >= state.maxIterations) return false;
        if (!outcome.executionAuthorityRejectedThisIteration() || !outcome.authorityRejections().equals(
                List.of(LocalTurnTraceCapture.ToolCallAuthorityRejectionReason.PROVIDER_TOOL_MODE_MISMATCH))) {
            return false;
        }
        if (outcome.successesThisIteration() > 0 || outcome.mutationsThisIteration() > 0
                || outcome.approvalDeniedThisIteration() || outcome.mutatingDeniedThisIteration()
                || outcome.pathPolicyBlockedThisIteration()) return false;
        var receipt = state.currentResponseReceipt;
        if (receipt == null || receipt.toolAuthorityMode()
                != ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE) return false;
        // Every failure of this iteration is an authority rejection, so no call of the iteration was dispatched.
        int rejected = 0;
        for (int i = state.toolOutcomes.size() - 1; i >= 0; i--) {
            if (state.toolOutcomes.get(i).failureReason() != ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID) break;
            rejected++;
        }
        return rejected > 0 && rejected >= outcome.failuresThisIteration();
    }

    /** Whether every tool named by the rejected text calls of this iteration is offered natively. */
    private static boolean rejectedCallsOffered(LoopState state, List<ToolSpec> offered) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ToolSpec spec : offered) {
            if (spec != null && spec.name() != null) names.add(ToolNamePolicy.localCanonicalName(spec.name()));
        }
        for (int i = state.toolOutcomes.size() - 1; i >= 0; i--) {
            var rejected = state.toolOutcomes.get(i);
            if (rejected.failureReason() != ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID) break;
            if (!names.contains(ToolNamePolicy.localCanonicalName(rejected.toolName()))) return false;
        }
        return true;
    }
}
