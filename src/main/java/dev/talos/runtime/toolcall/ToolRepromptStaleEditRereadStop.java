package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.safety.SafeLogFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

final class ToolRepromptStaleEditRereadStop {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRepromptStaleEditRereadStop.class);

    private ToolRepromptStaleEditRereadStop() {
    }

    static Optional<Boolean> tryHandle(LoopState state) {
        if (state.staleEditRereadIgnoredPath == null || state.staleEditRereadIgnoredPath.isBlank()) {
            return Optional.empty();
        }
        FailureDecision decision = FailureDecision.stop(
                FailureAction.ASK_USER,
                "failure policy stopped the tool loop because talos.edit_file was retried for path `"
                        + state.staleEditRereadIgnoredPath
                        + "` before rereading the file after a same-turn mutation changed it. "
                        + "No approval was requested for the stale retry and no additional file change was made.");
        state.stopWithFailure(decision, ToolFailurePolicyStopAnswer.render(state, decision));
        LOG.debug("Stopping tool-call loop after stale edit retry ignored reread requirement for {}",
                SafeLogFormatter.value(state.staleEditRereadIgnoredPath));
        return Optional.of(false);
    }
}
