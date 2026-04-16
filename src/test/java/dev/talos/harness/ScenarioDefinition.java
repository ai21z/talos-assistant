package dev.talos.harness;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Describes a single deterministic harness scenario.
 *
 * <p>A scenario has:
 * <ul>
 *   <li><b>name</b> — human-readable label used in assertion messages</li>
 *   <li><b>initialFiles</b> — files to pre-populate the workspace with</li>
 *   <li><b>scriptedResponse</b> — the LLM response string the runner injects into the loop.
 *       This may contain one or more tool call blocks (JSON or XML format). The loop
 *       executes them against the real tool registry, so filesystem side-effects are real.</li>
 *   <li><b>approvalPolicy</b> — controls how write/edit approvals are resolved
 *       without interactive user input</li>
 * </ul>
 *
 * <p>Scenarios are intentionally simple: one scripted LLM response, one workspace state.
 * The harness runner drives {@link dev.talos.runtime.ToolCallLoop} with this response,
 * then hands the workspace to expectations for post-run assertions.
 */
public record ScenarioDefinition(
        String name,
        Map<String, String> initialFiles,
        String scriptedResponse,
        ScenarioApprovalPolicy approvalPolicy
) {

    /** Construct with a default {@link ScenarioApprovalPolicy#APPROVE_ALL} policy. */
    public ScenarioDefinition(String name, Map<String, String> initialFiles, String scriptedResponse) {
        this(name, initialFiles, scriptedResponse, ScenarioApprovalPolicy.APPROVE_ALL);
    }

    // ── Builder ──────────────────────────────────────────────────────

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private final Map<String, String> files = new LinkedHashMap<>();
        private String scriptedResponse = "";
        private ScenarioApprovalPolicy policy = ScenarioApprovalPolicy.APPROVE_ALL;

        private Builder(String name) {
            this.name = name;
        }

        /** Pre-populate a file in the workspace. */
        public Builder withFile(String relativePath, String content) {
            files.put(relativePath, content);
            return this;
        }

        /**
         * Set the scripted LLM response to inject into the tool loop.
         * This string should contain any tool calls the scenario needs to exercise.
         */
        public Builder withScriptedResponse(String response) {
            this.scriptedResponse = response;
            return this;
        }

        /** Set the approval policy (default: APPROVE_ALL). */
        public Builder withApprovalPolicy(ScenarioApprovalPolicy policy) {
            this.policy = policy;
            return this;
        }

        public ScenarioDefinition build() {
            return new ScenarioDefinition(name, Map.copyOf(files), scriptedResponse, policy);
        }
    }
}

