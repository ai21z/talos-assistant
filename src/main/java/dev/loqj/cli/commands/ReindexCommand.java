package dev.loqj.cli.commands;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.List;

public final class ReindexCommand implements Command {
    private final Path workspace;
    public ReindexCommand(Path workspace) { this.workspace = workspace; }

    @Override public CommandSpec spec() {
        return new CommandSpec("reindex", List.of(), ":reindex", "Rebuild the local index for this workspace.");
    }

    @Override
    public Result execute(String args, Context ctx) {
        try {
            var idx = ctx.rag().getIndexer();
            var summary = idx.reindex(workspace);
            String msg = (summary == null ? "Reindexed.\n" : (summary.toString() + "\n"));
            return new Result.Ok(msg);
        } catch (Exception ex) {
            String err = ex.getMessage() == null ? "(no details)" : ex.getMessage()
                    .replaceAll("([A-Za-z]:)?[\\\\/][^\\\\/]+(?:[\\\\/][^\\\\/]+)*", "[path]");
            return new Result.Error("Reindex failed: " + err + "\n", 500);
        }
    }
}
