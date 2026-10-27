package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionMemory;
import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryCommandTest {

    @Test void clearResetsSketchOnlyConversation() {
        var ctx = Context.builder(new Config((java.nio.file.Path) null)).build();
        ctx.conversationManager().setSketch("restored summary without raw turns");
        assertTrue(ctx.conversationManager().hasHistory());

        assertInstanceOf(Result.Info.class, new MemoryCommand().execute("clear", ctx));

        assertFalse(ctx.conversationManager().hasHistory());
        assertNull(ctx.conversationManager().sketch());
        assertTrue(ctx.conversationManager().buildHistoryForAssist().isEmpty());
    }

    @Test void invalidArgumentPreservesRawAndSummarizedConversation() {
        var ctx = Context.builder(new Config((java.nio.file.Path) null)).build();
        ctx.conversationManager().addTurn("question", "answer");
        ctx.conversationManager().setSketch("earlier context");

        assertInstanceOf(Result.Error.class, new MemoryCommand().execute("show", ctx));

        assertEquals(1, ctx.conversationManager().turnCount());
        assertEquals("earlier context", ctx.conversationManager().sketch());
    }

    @Test void clearSupportsContextWithoutConversationManager() {
        var base = Context.builder(new Config((java.nio.file.Path) null)).build();
        base.memory().update("question", "answer");
        var ctx = new Context(base.cfg(), base.limits(), base.session(), base.audit(), base.redactor(),
                base.sandbox(), base.rag(), base.llm(), base.netPolicy(), base.memory(),
                base.approvalGate(), base.toolRegistry(), null);
        assertNull(ctx.conversationManager());

        assertInstanceOf(Result.Info.class, new MemoryCommand().execute("clear", ctx));

        assertFalse(base.memory().hasContent());
    }

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

