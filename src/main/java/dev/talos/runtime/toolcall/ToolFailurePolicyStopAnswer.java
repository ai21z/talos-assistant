package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;

import java.util.Locale;

final class ToolFailurePolicyStopAnswer {

    private ToolFailurePolicyStopAnswer() {}

    static String render(LoopState state, FailureDecision decision) {
        String reason = decision == null || decision.reason().isBlank()
                ? "repeated tool failures"
                : decision.reason();
        String message = "[Tool loop stopped by failure policy: "
                + reason
                + " Review the latest tool errors before retrying.]";
        String context = runtimeContext(state, reason);
        if (context.isBlank()) return message;
        return message + "\n\n" + context;
    }

    private static String runtimeContext(LoopState state, String reason) {
        if (state == null || reason == null || !reason.toLowerCase(Locale.ROOT).contains("no-progress")) {
            return "";
        }
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || contract.type() == TaskType.UNKNOWN) return "";
        StringBuilder out = new StringBuilder("Runtime context:\n");
        out.append("- task contract: ").append(contract.type()).append('\n');
        out.append("- mutationAllowed=").append(contract.mutationAllowed()).append('\n');
        out.append("- successful mutations: ").append(state.mutatingToolSuccesses).append('\n');
        if (!contract.mutationAllowed()) {
            out.append("- mutating tools were not available for this turn's contract; ")
                    .append("use an explicit create/edit/fix request if you intend a workspace change.\n");
        }
        return out.toString().stripTrailing();
    }
}
