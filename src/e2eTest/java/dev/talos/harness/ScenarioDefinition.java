package dev.talos.harness;

import dev.talos.runtime.phase.ExecutionPhase;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Describes a single deterministic harness scenario.
 *
 * <p>A scenario has:
 * <ul>
 *   <li><b>name</b> - human-readable label used in assertion messages</li>
 *   <li><b>initialFiles</b> - files to pre-populate the workspace with</li>
 *   <li><b>scriptedResponse</b> - the LLM response string the runner injects into the loop.
 *       This may contain one or more tool call blocks (JSON or XML format). The loop
 *       executes them against the real tool registry, so filesystem side-effects are real.</li>
 *   <li><b>approvalPolicy</b> - controls how write/edit approvals are resolved
 *       without interactive user input</li>
 *   <li><b>executionPhase</b> - optional forced phase for policy scenarios</li>
 *   <li><b>mode</b> - optional public/legacy mode selected before route-through
 *       scenarios run; defaults to {@code auto}</li>
 *   <li><b>privateMode</b> - optional private-mode config for privacy/tool-surface scenarios</li>
 * </ul>
 *
 * <p>Scenarios are intentionally simple: one scripted LLM response, one workspace state.
 * The harness runner drives {@link dev.talos.runtime.ToolCallLoop} with this response,
 * then hands the workspace to expectations for post-run assertions.
 */
public record ScenarioDefinition(
        String name,
        Map<String, String> initialFiles,
        String userPrompt,
        String scriptedResponse,
        ScenarioApprovalPolicy approvalPolicy,
        ExecutionPhase executionPhase,
        String mode,
        boolean privateMode
) {

    public ScenarioDefinition {
        initialFiles = initialFiles == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(initialFiles));
        userPrompt = userPrompt == null ? "" : userPrompt;
        scriptedResponse = scriptedResponse == null ? "" : scriptedResponse;
        approvalPolicy = approvalPolicy == null ? ScenarioApprovalPolicy.APPROVE_ALL : approvalPolicy;
        mode = normalizeMode(mode);
    }

    /** Back-compat constructor; route-through scenarios default to auto mode. */
    public ScenarioDefinition(String name,
                              Map<String, String> initialFiles,
                              String userPrompt,
                              String scriptedResponse,
                              ScenarioApprovalPolicy approvalPolicy,
                              ExecutionPhase executionPhase) {
        this(name, initialFiles, userPrompt, scriptedResponse, approvalPolicy, executionPhase, "auto", false);
    }

    /** Construct with a default {@link ScenarioApprovalPolicy#APPROVE_ALL} policy. */
    public ScenarioDefinition(String name, Map<String, String> initialFiles, String scriptedResponse) {
        this(name, initialFiles, "", scriptedResponse, ScenarioApprovalPolicy.APPROVE_ALL, null, "auto", false);
    }

    /** Back-compat constructor with user prompt and default approval policy. */
    public ScenarioDefinition(String name, Map<String, String> initialFiles, String userPrompt, String scriptedResponse) {
        this(name, initialFiles, userPrompt, scriptedResponse, ScenarioApprovalPolicy.APPROVE_ALL, null, "auto", false);
    }

    // ── Builder ──────────────────────────────────────────────────────

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private final Map<String, String> files = new LinkedHashMap<>();
        private String userPrompt = "";
        private String scriptedResponse = "";
        private ScenarioApprovalPolicy policy = ScenarioApprovalPolicy.APPROVE_ALL;
        private ExecutionPhase executionPhase;
        private String mode = "auto";
        private boolean privateMode;

        private Builder(String name) {
            this.name = name;
        }

        /** Pre-populate a file in the workspace. */
        public Builder withFile(String relativePath, String content) {
            files.put(relativePath, content);
            return this;
        }

        /** Set the user prompt associated with the scenario. */
        public Builder withUserPrompt(String prompt) {
            this.userPrompt = prompt == null ? "" : prompt;
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

        /** Force a runtime execution phase for phase-policy scenarios. */
        public Builder withExecutionPhase(ExecutionPhase executionPhase) {
            this.executionPhase = executionPhase;
            return this;
        }

        /** Select the mode for TurnProcessor route-through scenarios (default: auto). */
        public Builder withMode(String mode) {
            this.mode = normalizeMode(mode);
            return this;
        }

        public Builder withPrivateMode() {
            this.privateMode = true;
            return this;
        }

        public ScenarioDefinition build() {
            return new ScenarioDefinition(
                    name, new LinkedHashMap<>(files), userPrompt, scriptedResponse, policy, executionPhase, mode, privateMode);
        }
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) return "auto";
        return mode.trim().toLowerCase(Locale.ROOT);
    }
}

