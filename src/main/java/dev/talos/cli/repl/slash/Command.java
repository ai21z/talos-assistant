package dev.talos.cli.repl.slash;

import dev.talos.runtime.Result;
import dev.talos.cli.repl.Context;

/** A colon command like :k, :debug, :q. */
public interface Command {
    CommandSpec spec();
    Result execute(String args, Context ctx) throws Exception;
}
