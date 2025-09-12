package dev.loqj.cli.repl;

/** Functional bridge for wrapping any callable in the ExecutionPipeline. */
@FunctionalInterface
public interface CommandInvoker {
    Result invoke() throws Exception;
}
