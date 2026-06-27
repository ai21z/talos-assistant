package dev.talos.cli.repl.slash;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.cli.ui.AnsiColor;

import java.util.List;
import java.util.Locale;

public final class ModeCommand implements Command {
    private final ModeController modes;
    public ModeCommand(ModeController modes) { this.modes = modes; }

    @Override public CommandSpec spec() {
        return new CommandSpec("mode", List.of(), "/mode <mode>",
                availabilitySummary(),
                CommandGroup.MODELS);
    }

    @Override public Result execute(String args, Context ctx) {
        String a = (args == null ? "" : args.trim()).toLowerCase(Locale.ROOT);
        if (a.isEmpty()) {
            return new Result.Info("Mode: " + AnsiColor.blue(modes.getActiveName())
                    + "\nAvailable: " + String.join(", ", modes.availableModeNames()));
        }
        if (!modes.setActive(a)) {
            String available = String.join(", ", modes.availableModeNames());
            if (modes.reservedModeNames().contains(a)) {
                return new Result.Error("Mode '" + a + "' is reserved and not yet available. "
                        + "Available: " + available, 200);
            }
            return new Result.Error("Unknown mode '" + a + "'. Available: " + available, 200);
        }
        return new Result.Info("Mode: " + AnsiColor.blue(modes.getActiveName()));
    }

    /**
     * Help summary derived from the live registry (T874) so the advertised set
     * cannot drift from what {@link ModeController#setActive} accepts, and reserved
     * stubs are marked rather than presented as selectable.
     */
    private String availabilitySummary() {
        String available = String.join(", ", modes.availableModeNames());
        List<String> reserved = modes.reservedModeNames();
        String reservedNote = reserved.isEmpty()
                ? ""
                : " (" + String.join(", ", reserved) + " reserved)";
        return "Switch mode: " + available + reservedNote + ".";
    }
}
