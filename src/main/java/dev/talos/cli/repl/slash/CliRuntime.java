package dev.talos.cli.repl.slash;

/** Tiny surface to let commands adjust REPL session settings. */
public interface CliRuntime {
    int getK();
    void setK(int k);
    boolean isDebug();
    void setDebug(boolean on);
}
