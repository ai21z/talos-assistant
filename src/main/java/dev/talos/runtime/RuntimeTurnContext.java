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

    LlmClient llm();

    Sandbox sandbox();

    ExecutionPhaseState executionPhaseState();

    List<ToolSpec> nativeToolSpecs();
}
