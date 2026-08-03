package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
import dev.talos.tools.ToolCall;

/** Derived path and workspace-operation metadata for one tool execution. */
record ToolExecutionPathContext(WorkspaceOperationPlan workspaceOperationPlan, String pathHint) {
    static ToolExecutionPathContext from(ToolCall call) {
        return from(call, TurnTaskContractCapture.workspaceOperationPlan());
    }

    static ToolExecutionPathContext from(
            ToolCall call,
            WorkspaceOperationAuthorityPlan authorityPlan
    ) {
        WorkspaceOperationPlan plan = workspaceOperationPlan(
                call,
                authorityPlan);
        return new ToolExecutionPathContext(plan, pathHint(call, plan));
    }

    private static WorkspaceOperationPlan workspaceOperationPlan(
            ToolCall call,
            WorkspaceOperationAuthorityPlan authorityPlan
    ) {
        if (call == null || !WorkspaceOperationPlanner.isWorkspaceOperationTool(call.toolName())) return null;
        try {
            return WorkspaceOperationPlanner.checkpointPlan(
                    call,
                    authorityPlan).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String pathHint(ToolCall call, WorkspaceOperationPlan workspaceOperationPlan) {
        if (workspaceOperationPlan != null) {
            String changedPath = workspaceOperationPlan.primaryChangedPath();
            if (!changedPath.isBlank()) return changedPath;
        }
        return ToolCallSupport.resolvePathHint(call);
    }
}
