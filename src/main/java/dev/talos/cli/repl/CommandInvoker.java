package dev.talos.cli.repl;

import dev.talos.runtime.Result;

/** Functional bridge for wrapping any callable in the ExecutionPipeline. */
@FunctionalInterface
public interface CommandInvoker {
    Result invoke() throws Exception;
}
