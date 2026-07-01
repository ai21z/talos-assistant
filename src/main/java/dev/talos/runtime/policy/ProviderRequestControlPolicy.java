package dev.talos.runtime.policy;

import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Maps runtime-owned turn obligations to provider-neutral chat controls. */
public final class ProviderRequestControlPolicy {
    private static final Set<String> MUTATING_TOOLS = Set.of("talos.write_file", "talos.edit_file");
    private static final Set<String> WORKSPACE_TOOLS = Set.of(
            "talos.apply_workspace_batch", "talos.mkdir", "talos.copy_path",
            "talos.move_path", "talos.rename_path", "talos.delete_path");
    private static final Set<String> INSPECTION_TOOLS = Set.of(
            "talos.grep", "talos.list_dir", "talos.read_file", "talos.retrieve");
    private static final Set<String> COMMAND_TOOLS = Set.of("talos.run_command");

    private ProviderRequestControlPolicy() {}

    public static ChatRequestControls forTurn(
            CurrentTurnPlan plan,
            List<ToolSpec> visibleTools,
            boolean requiredToolChoiceSupported
    ) {
        return forTurn(plan, visibleTools, requiredToolChoiceSupported, false);
    }

    public static ChatRequestControls forTurn(
            CurrentTurnPlan plan,
            List<ToolSpec> visibleTools,
            boolean requiredToolChoiceSupported,
            boolean namedToolChoiceSupported
    ) {
        if (!requiredToolChoiceSupported || plan == null || visibleTools == null || visibleTools.isEmpty()) {
            return ChatRequestControls.defaults();
        }

        ActionObligation action = plan.actionObligation();
        EvidenceObligation evidence = EvidenceObligationPolicy.parse(plan.evidenceObligation());
        boolean mutatingToolsVisible = hasAnyTool(visibleTools, MUTATING_TOOLS);
        boolean workspaceToolsVisible = hasAnyTool(visibleTools, WORKSPACE_TOOLS);
        boolean inspectionToolsVisible = hasAnyTool(visibleTools, INSPECTION_TOOLS);
        boolean commandToolsVisible = hasAnyTool(visibleTools, COMMAND_TOOLS);

        boolean require = false;
        String namedTool = "";
        List<String> tags = new ArrayList<>();

        if (explicitCommandRequest(plan) && commandToolsVisible) {
            require = true;
            tags.add("action-obligation:" + action.name());
            tags.add("evidence-obligation:" + evidence.name());
            tags.add("required-tool:talos.run_command");
        } else if (action == ActionObligation.WORKSPACE_OPERATION_REQUIRED && workspaceToolsVisible) {
            // Workspace operations were the one mutation family left at AUTO,
            // letting qwen2.5-coder emit malformed payloads or skip the call
            // entirely in three full-bank release runs (T739). REQUIRED engages
            // the provider grammar from token zero; NAMED pins the exact tool
            // when the surface exposes only one.
            require = true;
            tags.add("action-obligation:" + action.name());
            if (namedToolChoiceSupported) {
                List<String> visibleWorkspace = visibleToolNames(visibleTools, WORKSPACE_TOOLS);
                if (visibleWorkspace.size() == 1) {
                    namedTool = visibleWorkspace.get(0);
                    tags.add("required-tool:" + namedTool);
                }
            }
        } else if (action == ActionObligation.CONDITIONAL_REVIEW_FIX
                && (inspectionToolsVisible || mutatingToolsVisible)) {
            require = true;
            tags.add("action-obligation:" + action.name());
        } else if ((action == ActionObligation.MUTATING_TOOL_REQUIRED
                || action == ActionObligation.REPAIR_FROM_VERIFIER_FINDINGS)
                && mutatingToolsVisible) {
            require = true;
            tags.add("action-obligation:" + action.name());
        } else if (requiresInspectionTool(action) && inspectionToolsVisible) {
            require = true;
            tags.add("action-obligation:" + action.name());
        }

        if (requiresEvidenceTool(evidence) && inspectionToolsVisible) {
            require = true;
            tags.add("evidence-obligation:" + evidence.name());
        }

        if (!require) return ChatRequestControls.defaults();
        // Tool-obligation turns run near-greedy: server-default sampling with a
        // random seed produced divergent outputs for byte-identical requests in
        // the 0.10.1 release banks (T740).
        return new ChatRequestControls(
                namedTool.isBlank() ? ToolChoiceMode.REQUIRED : ToolChoiceMode.NAMED,
                namedTool,
                ResponseFormatMode.TEXT,
                "",
                tags,
                SamplingControls.NEAR_GREEDY);
    }

    private static boolean requiresInspectionTool(ActionObligation action) {
        return action == ActionObligation.INSPECT_REQUIRED
                || action == ActionObligation.VERIFY_FROM_EVIDENCE
                || action == ActionObligation.LIST_DIR_ONLY;
    }

    private static boolean requiresEvidenceTool(EvidenceObligation evidence) {
        return evidence != null && evidence != EvidenceObligation.NONE;
    }

    private static boolean explicitCommandRequest(CurrentTurnPlan plan) {
        return plan != null
                && plan.taskContract() != null
                && "explicit-command-verification-request".equals(plan.taskContract().classificationReason());
    }

    private static boolean hasAnyTool(List<ToolSpec> tools, Set<String> names) {
        for (ToolSpec tool : tools) {
            String name = tool == null ? "" : Objects.toString(tool.name(), "");
            if (names.contains(name)) return true;
        }
        return false;
    }

    private static List<String> visibleToolNames(List<ToolSpec> tools, Set<String> names) {
        List<String> out = new ArrayList<>();
        for (ToolSpec tool : tools) {
            String name = tool == null ? "" : Objects.toString(tool.name(), "");
            if (names.contains(name) && !out.contains(name)) out.add(name);
        }
        return out;
    }
}
