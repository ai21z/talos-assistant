package dev.talos.cli.repl;

import dev.talos.runtime.SessionListener;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.context.ChangeSummaryContext;

/** Updates session active-task memory after completed turns. */
public final class ActiveTaskContextUpdateListener implements SessionListener {

    private final SessionMemory memory;
    private final ActiveTaskContextUpdater updater;

    public ActiveTaskContextUpdateListener(SessionMemory memory) {
        this(memory, new ActiveTaskContextUpdater());
    }

    ActiveTaskContextUpdateListener(SessionMemory memory, ActiveTaskContextUpdater updater) {
        this.memory = memory;
        this.updater = updater == null ? new ActiveTaskContextUpdater() : updater;
    }

    @Override
    public void onTurnComplete(TurnResult result, String userInput) {
        if (memory == null) return;
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                userInput,
                memory.activeTaskContext(),
                memory.artifactGoal());
        memory.setActiveTaskContext(update.activeTaskContext());
        memory.setArtifactGoal(update.artifactGoal());
        memory.setChangeSummaryContext(ChangeSummaryContext.updateAfterTurn(
                memory.changeSummaryContext(),
                result));
    }
}
