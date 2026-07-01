package dev.talos.cli.repl;

/** Minimal session surface needed by commands (e.g., :k, :debug). */
public interface SessionState {
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
