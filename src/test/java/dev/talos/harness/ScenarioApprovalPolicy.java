package dev.talos.harness;

/**
 * Controls how the scenario harness handles tool approval requests.
 *
 * <p>In normal use Talos asks the user before mutating files.
 * Scenarios can configure this globally so tests do not require
 * interactive input.
 */
public enum ScenarioApprovalPolicy {

    /** All tool calls are silently approved — fastest, lowest friction. */
    APPROVE_ALL,

    /** All write/edit calls are silently denied — useful for read-only scenarios. */
    DENY_WRITES,

    /** All calls are denied — tests that verify denied-tool-call behavior. */
    DENY_ALL
}

