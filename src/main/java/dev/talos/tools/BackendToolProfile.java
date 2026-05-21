package dev.talos.tools;

/** Minimal static profile label for tool-alias decisions. */
public enum BackendToolProfile {
    TALOS("talos"),
    TOOL_USE("tool_use"),
    FILE_UTILS("file_utils"),
    UNKNOWN("unknown");

    private final String id;

    BackendToolProfile(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
