package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;

import java.nio.file.Path;
import java.util.Optional;

/** Strategy interface for REPL “modes”. Pure, no printing - return Result for RenderEngine. */
public interface Mode {
    /** Short, user-facing name (ask, rag, dev, web, auto, ...). */
    String name();

    /** Cheap check: should this mode try to handle the raw line? (no I/O, no printing) */
    boolean canHandle(String rawLine);

    /** Execute and return a renderable Result; Optional.empty() means “not applicable”. */
    Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception;

    /**
     * Whether this mode can be selected via {@code /mode <name>}. Reserved or stub
     * modes return {@code false} so the controller refuses to switch into them and
     * never traps the user in a dead mode. Functional modes use the default.
     */
    default boolean available() { return true; }
}
