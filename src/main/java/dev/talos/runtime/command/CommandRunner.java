package dev.talos.runtime.command;

/** Executes a previously validated command plan. */
public interface CommandRunner {
    CommandResult run(CommandPlan plan);
}
