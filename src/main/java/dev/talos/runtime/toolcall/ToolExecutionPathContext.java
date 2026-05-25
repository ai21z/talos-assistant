package dev.talos.runtime.toolcall;

import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
import dev.talos.tools.ToolCall;

/** Derived path and workspace-operation metadata for one tool execution. */
record ToolExecutionPathContext(WorkspaceOperationPlan workspaceOperationPlan, String pathHint) {
    static ToolExecutionPathContext from(ToolCall call) {
        WorkspaceOperationPlan plan = workspaceOperationPlan(call);
        return new ToolExecutionPathContext(plan, pathHint(call, plan));
    }

    private static WorkspaceOperationPlan workspaceOperationPlan(ToolCall call) {
        if (call == null || !WorkspaceOperationPlanner.isWorkspaceOperationTool(call.toolName())) return null;
        try {
            return WorkspaceOperationPlanner.checkpointPlan(call).orElse(null);
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
