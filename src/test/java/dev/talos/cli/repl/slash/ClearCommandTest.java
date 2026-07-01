package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionMemory;
import dev.talos.core.Config;
import dev.talos.runtime.context.ChangeSummaryContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClearCommand}.
 */
class ClearCommandTest {

    @Test
    void clearEmptyConversation() {
        var ctx = Context.builder(new Config()).build();
        var cmd = new ClearCommand();

        Result r = cmd.execute("", ctx);
        assertInstanceOf(Result.Info.class, r);
        assertTrue(r.toString().contains("already empty"));
    }

    @Test
    void clearWithHistory() {
        var memory = new SessionMemory();
        memory.update("hello", "hi there");
        memory.update("how are you", "I'm fine");
        memory.setChangeSummaryContext(new ChangeSummaryContext(
                ChangeSummaryContext.SCHEMA_VERSION,
                java.util.List.of(new ChangeSummaryContext.FileChange("README.md", "talos.write_file", 1, "trace-1")),
                java.util.List.of(),
                "PASSED",
                "COMPLETED_VERIFIED",
                java.util.List.of()));
        var ctx = Context.builder(new Config()).memory(memory).build();
        var cmd = new ClearCommand();

        Result r = cmd.execute("", ctx);
        assertInstanceOf(Result.Info.class, r);
        assertTrue(r.toString().contains("2 exchanges"));
        assertTrue(r.toString().contains("removed"));

        // Memory should be cleared
        assertFalse(memory.hasContent());
        assertTrue(memory.getTurns().isEmpty());
        assertFalse(memory.changeSummaryContext().hasRecordedChanges());
    }

    @Test
    void clearSingleExchange() {
        var memory = new SessionMemory();
        memory.update("hello", "hi");
        var ctx = Context.builder(new Config()).memory(memory).build();
        var cmd = new ClearCommand();

        Result r = cmd.execute("", ctx);
        assertTrue(r.toString().contains("1 exchange"));
        assertFalse(r.toString().contains("exchanges"));
    }

    @Test
    void clearTwice() {
        var memory = new SessionMemory();
        memory.update("hello", "hi");
        var ctx = Context.builder(new Config()).memory(memory).build();
        var cmd = new ClearCommand();

        cmd.execute("", ctx);
        Result r2 = cmd.execute("", ctx);
        assertTrue(r2.toString().contains("already empty"));
    }

    @Test
    void specHasCorrectName() {
        var cmd = new ClearCommand();
        assertEquals("clear", cmd.spec().name());
        assertTrue(cmd.spec().aliases().contains("cls"));
        assertTrue(cmd.spec().aliases().contains("reset"));
        assertTrue(cmd.spec().summary().contains("context"));
    }
}

