package dev.talos.runtime.policy;

import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Maps runtime-owned turn obligations to provider-neutral chat controls. */
public final class ProviderRequestControlPolicy {
    private static final Set<String> MUTATING_TOOLS = Set.of("talos.write_file", "talos.edit_file");
    private static final Set<String> INSPECTION_TOOLS = Set.of(
            "talos.grep", "talos.list_dir", "talos.read_file", "talos.retrieve");

    private ProviderRequestControlPolicy() {}

    public static ChatRequestControls forTurn(
            CurrentTurnPlan plan,
            List<ToolSpec> visibleTools,
            boolean requiredToolChoiceSupported
    ) {
        if (!requiredToolChoiceSupported || plan == null || visibleTools == null || visibleTools.isEmpty()) {
            return ChatRequestControls.defaults();
        }

        ActionObligation action = plan.actionObligation();
        EvidenceObligation evidence = EvidenceObligationPolicy.parse(plan.evidenceObligation());
        boolean mutatingToolsVisible = hasAnyTool(visibleTools, MUTATING_TOOLS);
        boolean inspectionToolsVisible = hasAnyTool(visibleTools, INSPECTION_TOOLS);

        boolean require = false;
        List<String> tags = new ArrayList<>();

        if ((action == ActionObligation.MUTATING_TOOL_REQUIRED
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
        return new ChatRequestControls(
                ToolChoiceMode.REQUIRED,
                "",
                ResponseFormatMode.TEXT,
                "",
                tags);
    }

    private static boolean requiresInspectionTool(ActionObligation action) {
        return action == ActionObligation.INSPECT_REQUIRED
                || action == ActionObligation.VERIFY_FROM_EVIDENCE
                || action == ActionObligation.LIST_DIR_ONLY;
    }

    private static boolean requiresEvidenceTool(EvidenceObligation evidence) {
        return evidence != null && evidence != EvidenceObligation.NONE;
    }

    private static boolean hasAnyTool(List<ToolSpec> tools, Set<String> names) {
        for (ToolSpec tool : tools) {
            String name = tool == null ? "" : Objects.toString(tool.name(), "");
            if (names.contains(name)) return true;
        }
        return false;
    }
}
