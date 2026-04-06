package dev.talos.cli.repl;

/** Minimal session surface needed by commands (e.g., :k, :debug). */
public interface SessionState {
    int getK();
    void setK(int k);

    boolean isDebug();
    void setDebug(boolean on);
}
