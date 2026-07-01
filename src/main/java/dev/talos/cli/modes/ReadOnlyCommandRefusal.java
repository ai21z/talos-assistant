package dev.talos.cli.modes;

import dev.talos.runtime.Result;
import dev.talos.runtime.task.TaskContract;

/** Local refusal for command-execution requests in modes that cannot run commands. */
final class ReadOnlyCommandRefusal {
    private ReadOnlyCommandRefusal() {}

    static boolean matches(TaskContract contract) {
        if (contract == null) return false;
        String reason = contract.classificationReason();
        return "explicit-command-verification-request".equals(reason)
                || "unsupported-command-verification-request".equals(reason);
    }

    static Result.Ok resultFor(String modeName) {
        String display = modeName == null || modeName.isBlank()
                ? "This mode"
                : Character.toUpperCase(modeName.charAt(0)) + modeName.substring(1);
        return new Result.Ok("\n" + display
                + " is read-only and cannot run commands; switch to `/mode agent` "
                + "to run approved bounded command profiles.\n\n");
    }
}
