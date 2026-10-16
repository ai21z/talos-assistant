package dev.talos.runtime;

import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.phase.ExecutionPhaseState;
import dev.talos.spi.types.ToolSpec;

import java.util.List;

/**
 * Runtime-facing view of the CLI composition context.
 *
 * <p>The CLI may own the concrete composition object, but runtime execution
 * should depend only on the small set of collaborators it actually uses.
 */
public interface RuntimeTurnContext {
    Config cfg();

    default dev.talos.core.security.Redactor redactor() {
        return new dev.talos.core.security.Redactor(cfg() == null ? java.util.Map.of() : cfg().data);
    }

    LlmClient llm();

    Sandbox sandbox();

    ExecutionPhaseState executionPhaseState();

    List<ToolSpec> nativeToolSpecs();

    default dev.talos.runtime.observation.TurnRequestFrame requestFrame() { return null; }

    default RuntimeTurnContext withRequestFrame(dev.talos.runtime.observation.TurnRequestFrame frame) {
        if (frame.controlled()) throw new IllegalStateException("A controlled boundary requires an explicit context carrier");
        return this;
    }

    default RuntimeTurnContext prepareRequestPins(String request, java.nio.file.Path workspace) { return this; }

    default boolean hasNativeToolSpecOverride() {
        return nativeToolSpecs() != null;
    }
}
