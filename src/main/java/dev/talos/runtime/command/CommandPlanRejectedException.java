package dev.talos.runtime.command;

/** Raised when a command request cannot become a safe runtime plan. */
public final class CommandPlanRejectedException extends RuntimeException {
    public CommandPlanRejectedException(String message) {
        super(message);
    }
}
