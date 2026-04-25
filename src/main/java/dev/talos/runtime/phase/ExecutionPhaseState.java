package dev.talos.runtime.phase;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Turn-scoped mutable phase holder carried through the runtime context. */
public final class ExecutionPhaseState {
    private final AtomicReference<ExecutionPhase> phase;

    public ExecutionPhaseState() {
        this(ExecutionPhase.APPLY);
    }

    public ExecutionPhaseState(ExecutionPhase initialPhase) {
        this.phase = new AtomicReference<>(normalize(initialPhase));
    }

    public ExecutionPhase phase() {
        return phase.get();
    }

    public void moveTo(ExecutionPhase nextPhase) {
        phase.set(normalize(nextPhase));
    }

    private static ExecutionPhase normalize(ExecutionPhase phase) {
        return Objects.requireNonNullElse(phase, ExecutionPhase.APPLY);
    }
}
