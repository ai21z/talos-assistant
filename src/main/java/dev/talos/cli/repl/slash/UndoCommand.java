package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.FileUndoStack.UndoEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code /undo} — reverts the most recent file write or edit.
 */
public final class UndoCommand implements Command {

    private final FileUndoStack undoStack;

    public UndoCommand(FileUndoStack undoStack) {
        this.undoStack = undoStack;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec("undo", List.of(),
                "/undo", "Undo the last file write/edit.", CommandGroup.KNOWLEDGE);
    }

    @Override
    public Result execute(String args, Context ctx) {
        if (undoStack == null || undoStack.isEmpty()) {
            return new Result.Info("Nothing to undo.\n");
        }

        var opt = undoStack.pop();
        if (opt.isEmpty()) return new Result.Info("Nothing to undo.\n");

        UndoEntry entry = opt.get();
        Path path = entry.path();

        try {
            if (entry.wasNew()) {
                if (Files.exists(path)) {
                    Files.delete(path);
                    return new Result.Ok("Undo: deleted " + path.getFileName()
                            + " (was created by " + entry.toolName() + ")\n");
                }
                return new Result.Info("Undo: file already gone: " + path.getFileName() + "\n");
            }
            String prev = entry.previousContent();
            if (prev == null) {
                return new Result.Error("Undo: no previous content recorded for "
                        + path.getFileName() + "\n", 500);
            }
            Files.writeString(path, prev);
            long lines = prev.chars().filter(c -> c == '\n').count() + (prev.isEmpty() ? 0 : 1);
            return new Result.Ok("Undo: restored " + path.getFileName()
                    + " (" + lines + " lines, from " + entry.toolName() + ")\n");
        } catch (Exception e) {
            return new Result.Error("Undo failed: " + e.getMessage() + "\n", 500);
        }
    }
}
