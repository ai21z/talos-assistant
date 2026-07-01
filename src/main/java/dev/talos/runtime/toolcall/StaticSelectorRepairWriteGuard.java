package dev.talos.runtime.toolcall;

import dev.talos.runtime.repair.StaticSelectorRepairGuard;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;

import java.util.List;
import java.util.Optional;

final class StaticSelectorRepairWriteGuard {
    static final String OBLIGATION = "STATIC_SELECTOR_REPAIR";
    static final String FAILURE_KIND = "STATIC_SELECTOR_REPAIR_PRESERVED_MISSING_SELECTOR";

    private StaticSelectorRepairWriteGuard() {
    }

    record Failure(String reason, String answer) {
    }

    static Optional<Failure> evaluate(List<ChatMessage> messages, List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return Optional.empty();
        for (ToolCall call : calls) {
            if (call == null) continue;
            var violation = StaticSelectorRepairGuard.violationForWrite(messages, call);
            if (violation.isEmpty()) continue;
            return Optional.of(failureFor(violation.get()));
        }
        return Optional.empty();
    }

    private static Failure failureFor(StaticSelectorRepairGuard.Violation violation) {
        String reason = FAILURE_KIND + ": " + violation.detail();
        return new Failure(reason, failureAnswer(violation));
    }

    private static String failureAnswer(StaticSelectorRepairGuard.Violation violation) {
        String target = violation == null ? "(unknown)" : violation.target();
        String selectors = violation == null || violation.selectors().isEmpty()
                ? "(unknown)"
                : String.join(", ", violation.selectors());
        String detail = violation == null ? "" : violation.detail();
        return "[Action obligation failed: static selector repair write preserved verifier-known missing selectors.]\n\n"
                + "Target: " + target + ".\n"
                + "Preserved selector(s): " + selectors + ".\n"
                + detail + "\n"
                + "Talos stopped this turn deterministically.";
    }
}
