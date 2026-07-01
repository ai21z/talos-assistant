package dev.talos.runtime.verification;

import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.spi.types.ChatMessage;

import java.util.List;
import java.util.Optional;

/**
 * Compatibility facade for static verification repair instructions.
 *
 * <p>The repair decision now belongs to {@link RepairPolicy}; this class keeps
 * the older call site shape while T39 moves repair ownership into
 * {@code dev.talos.runtime.repair}.
 */
public final class StaticVerificationRepairContext {

    private StaticVerificationRepairContext() {}

    public static Optional<String> instructionFor(
            List<ChatMessage> messages,
            TaskContract contract
    ) {
        return RepairPolicy.planForStaticVerification(messages, contract)
                .plan()
                .map(plan -> plan.instruction().isBlank() ? null : plan.instruction());
    }
}
