package dev.talos.cli.commands;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.*;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class UndoCommandTest {
    @TempDir Path workspace;
    private FileUndoStack undoStack;
    private FileWriteTool writeTool;
    private FileEditTool editTool;
    private UndoCommand undoCmd;
    private ToolContext toolCtx;
    private Context ctx;
    @BeforeEach
    void setUp() {
        undoStack = new FileUndoStack();
        writeTool = new FileWriteTool(undoStack);
        editTool = new FileEditTool(undoStack);
        undoCmd = new UndoCommand(undoStack);
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        toolCtx = new ToolContext(workspace, sandbox, new Config());
        ctx = Context.builder(new Config()).build();
    }
    @Nested class Spec {
        @Test void name() { assertEquals("undo", undoCmd.spec().name()); }
        @Test void group() { assertEquals(CommandGroup.KNOWLEDGE, undoCmd.spec().group()); }
    }
    @Nested class EmptyStack {
        @Test void returnsInfo() {
            Result r = undoCmd.execute("", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertTrue(r.toString().contains("Nothing to undo"));
        }
        @Test void nullStack() {
            var cmd = new UndoCommand(null);
            assertInstanceOf(Result.Info.class, cmd.execute("", ctx));
        }
    }
    @Nested class UndoCreate {
        @Test void deletesNewFile() throws IOException {
            writeTool.execute(new ToolCall("talos.write_file",
                    Map.of("path", "new.txt", "content", "hello")), toolCtx);
            assertTrue(Files.exists(workspace.resolve("new.txt")));
            Result r = undoCmd.execute("", ctx);
            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("deleted"));
            assertFalse(Files.exists(workspace.resolve("new.txt")));
        }
        @Test void alreadyGone() throws IOException {
            writeTool.execute(new ToolCall("talos.write_file",
                    Map.of("path", "tmp.txt", "content", "x")), toolCtx);
            Files.delete(workspace.resolve("tmp.txt"));
            Result r = undoCmd.execute("", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertTrue(r.toString().contains("already gone"));
        }
    }
    @Nested class UndoOverwrite {
        @Test void restoresPrevious() throws IOException {
            Files.writeString(workspace.resolve("e.txt"), "original");
            writeTool.execute(new ToolCall("talos.write_file",
                    Map.of("path", "e.txt", "content", "changed")), toolCtx);
            assertEquals("changed", Files.readString(workspace.resolve("e.txt")));
            Result r = undoCmd.execute("", ctx);
            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("restored"));
            assertEquals("original", Files.readString(workspace.resolve("e.txt")));
        }
    }
    @Nested class UndoEdit {
        @Test void revertsEdit() throws IOException {
            Files.writeString(workspace.resolve("c.java"), "int x = 1;");
            editTool.execute(new ToolCall("talos.edit_file",
                    Map.of("path", "c.java", "old_string", "x = 1", "new_string", "x = 42")), toolCtx);
            assertTrue(Files.readString(workspace.resolve("c.java")).contains("x = 42"));
            Result r = undoCmd.execute("", ctx);
            assertInstanceOf(Result.Ok.class, r);
            assertEquals("int x = 1;", Files.readString(workspace.resolve("c.java")));
        }
    }
    @Nested class MultiUndo {
        @Test void reverseOrder() throws IOException {
            writeTool.execute(new ToolCall("talos.write_file",
                    Map.of("path", "a.txt", "content", "A")), toolCtx);
            writeTool.execute(new ToolCall("talos.write_file",
                    Map.of("path", "b.txt", "content", "B")), toolCtx);
            assertTrue(Files.exists(workspace.resolve("a.txt")));
            assertTrue(Files.exists(workspace.resolve("b.txt")));
            Result r1 = undoCmd.execute("", ctx);
            assertTrue(r1.toString().contains("b.txt"));
            assertFalse(Files.exists(workspace.resolve("b.txt")));
            assertTrue(Files.exists(workspace.resolve("a.txt")));
            Result r2 = undoCmd.execute("", ctx);
            assertTrue(r2.toString().contains("a.txt"));
            assertFalse(Files.exists(workspace.resolve("a.txt")));
            assertInstanceOf(Result.Info.class, undoCmd.execute("", ctx));
        }
    }
}
