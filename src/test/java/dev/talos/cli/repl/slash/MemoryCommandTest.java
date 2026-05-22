package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.cli.repl.SessionMemory;
import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryCommandTest {

    @Test void clearResetsMemory() {
        var mem = new SessionMemory();
        mem.update("q", "a");
        assertTrue(mem.hasContent());

        var ctx = Context.builder(new Config())
                .memory(mem)
                .build();

        var cmd = new MemoryCommand();
        Result r = cmd.execute("clear", ctx);

        assertInstanceOf(Result.Info.class, r);
        assertFalse(mem.hasContent(), "Memory should be cleared");
    }

    @Test void nonClearArgReturnsError() {
        var ctx = Context.builder(new Config()).build();
        var cmd = new MemoryCommand();

        Result r = cmd.execute("show", ctx);
        assertInstanceOf(Result.Error.class, r);
    }

    @Test void emptyArgReturnsError() {
        var ctx = Context.builder(new Config()).build();
        var cmd = new MemoryCommand();

        Result r = cmd.execute("", ctx);
        assertInstanceOf(Result.Error.class, r);
    }

    @Test void nullArgReturnsError() {
        var ctx = Context.builder(new Config()).build();
        var cmd = new MemoryCommand();

        Result r = cmd.execute(null, ctx);
        assertInstanceOf(Result.Error.class, r);
    }
}

