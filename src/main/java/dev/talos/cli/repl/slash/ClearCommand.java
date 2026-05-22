package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;

import java.util.List;

/**
 * /clear — resets conversation history so the next prompt starts fresh.
 *
 * <p>Clears both the {@code ConversationManager} (structured turns) and
 * the legacy {@code SessionMemory} (flat text buffer), which share the
 * same underlying storage. After this command, the LLM receives no prior
 * conversation context — as if the session just started.
 */
public final class ClearCommand implements Command {

    @Override
    public CommandSpec spec() {
        return new CommandSpec("clear", List.of("cls", "reset"), "/clear", "Reset conversation context.",
                CommandGroup.SESSION);
    }

    @Override
    public Result execute(String args, Context ctx) {
        int turnsBefore = 0;
        if (ctx.conversationManager() != null) {
            turnsBefore = ctx.conversationManager().turnCount();
            ctx.conversationManager().clear();
        } else if (ctx.memory() != null) {
            turnsBefore = ctx.memory().getTurns().size() / 2;
            ctx.memory().clear();
        }

        if (turnsBefore == 0) {
            return new Result.Info("Conversation is already empty.");
        }
        return new Result.Info("Conversation cleared (" + turnsBefore + " exchange"
                + (turnsBefore == 1 ? "" : "s") + " removed).");
    }
}

