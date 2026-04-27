package dev.talos.runtime;

import dev.talos.runtime.task.TaskContract;

/**
 * Thread-local carrier for the resolved current-turn task contract.
 *
 * <p>The executor resolves contracts from full message history. Tool execution
 * must use the same resolved contract, not a second latest-message-only
 * classification, so repair follow-ups and other history-aware contracts remain
 * coherent through the approval gateway.
 */
public final class TurnTaskContractCapture {

    private static final ThreadLocal<TaskContract> HOLDER = new ThreadLocal<>();

    private TurnTaskContractCapture() {}

    public static void set(TaskContract contract) {
        if (contract == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(contract);
        }
    }

    public static TaskContract get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
