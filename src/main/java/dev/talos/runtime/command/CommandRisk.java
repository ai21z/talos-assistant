package dev.talos.runtime.command;

/** Command-specific risk categories before mapping to tool permission policy. */
public enum CommandRisk {
    READ_ONLY_DIAGNOSTIC,
    BUILD_OR_TEST,
    WORKSPACE_MUTATION,
    DESTRUCTIVE,
    NETWORK,
    INTERACTIVE,
    UNKNOWN
}
