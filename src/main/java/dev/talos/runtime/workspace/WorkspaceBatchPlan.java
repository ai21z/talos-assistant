package dev.talos.runtime.workspace;

import java.util.List;

/** Parsed batch workspace operation with preview and checkpoint plan. */
public record WorkspaceBatchPlan(
        List<WorkspaceBatchOperation> operations,
        WorkspaceOperationPlan checkpointPlan,
        String previewSummary
) {
    public WorkspaceBatchPlan {
        operations = List.copyOf(operations == null ? List.of() : operations);
        previewSummary = previewSummary == null ? "" : previewSummary;
    }

    public List<String> pathValues() {
        return operations.stream()
                .flatMap(operation -> operation.pathValues().stream())
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
    }
}
