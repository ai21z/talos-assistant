package dev.loqj.core.security;

import java.nio.file.Path;

/**
 * No-op sandbox for PR-1. Allows everything, just so code compiles.
 * We’ll swap for the real workspace-only logic in a later PR.
 */
public final class Sandbox {
    public Sandbox(Path workspace, java.util.Map<String,Object> cfg) {}

    public boolean isEnabled() { return false; }

    public boolean allowedPath(Path p) { return true; }

    public String explain(Path p) { return "allowed (no-op sandbox)"; }

    public boolean allowedCommand(String verb) { return true; }
}
