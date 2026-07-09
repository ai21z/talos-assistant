package dev.talos.engine.llamacpp;

/** Shared context bounds for managed llama.cpp launch and user-facing status. */
public final class LlamaCppContextLimits {
    public static final int MAX_CONTEXT = 262_144;

    private LlamaCppContextLimits() {
    }
}
