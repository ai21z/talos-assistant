package dev.loqj.cli.commands;

import dev.loqj.cli.repl.Result;
import dev.loqj.cli.repl.Context;

/** A colon command like :k, :debug, :q. */
public interface Command {
    CommandSpec spec();
    Result execute(String args, Context ctx) throws Exception;
}
