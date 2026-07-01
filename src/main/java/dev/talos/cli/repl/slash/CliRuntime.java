package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.DebugLevel;

/** Tiny surface to let commands adjust REPL session settings. */
public interface CliRuntime {
    int getK();
    void setK(int k);
    boolean isDebug();
    void setDebug(boolean on);

    default DebugLevel getDebugLevel() {
        return isDebug() ? DebugLevel.BRIEF : DebugLevel.OFF;
    }

    default void setDebugLevel(DebugLevel level) {
        setDebug(level != null && level.enabled());
    }
}
