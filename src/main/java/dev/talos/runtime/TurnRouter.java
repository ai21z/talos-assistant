package dev.talos.runtime;

import java.nio.file.Path;
import java.util.Optional;

/** Runtime-owned port for dispatching a user prompt to the configured turn mode. */
public interface TurnRouter {
    Optional<Result> route(String rawLine, Path workspace, RuntimeTurnContext ctx) throws Exception;

    default String traceMode(String rawLine) {
        return "unknown";
    }
}
