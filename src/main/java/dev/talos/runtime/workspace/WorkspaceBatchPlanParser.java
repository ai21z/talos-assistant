package dev.talos.runtime.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRiskLevel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Parses the JSON-string protocol for talos.apply_workspace_batch. */
public final class WorkspaceBatchPlanParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkspaceBatchPlanParser() {}

    public static Optional<WorkspaceBatchPlan> parse(ToolCall call) {
        String json = operationsJson(call);
        if (json == null || json.isBlank()) return Optional.empty();
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid operations_json: " + e.getMessage(), e);
        }
        JsonNode operationsNode = root.isArray() ? root : root.get("operations");
        if (operationsNode == null || !operationsNode.isArray()) {
            throw new IllegalArgumentException("Invalid operations_json: expected an array or an object with operations.");
        }

        List<WorkspaceBatchOperation> operations = new ArrayList<>();
        for (JsonNode node : operationsNode) {
            operations.add(parseOperation(node));
        }
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("Invalid operations_json: at least one operation is required.");
        }

        List<WorkspaceOperationPlan.PathEffect> effects = new ArrayList<>();
        for (WorkspaceBatchOperation operation : operations) {
            switch (operation.kind()) {
                case MKDIR -> effects.add(WorkspaceOperationPlan.PathEffect.absentBefore(
                        operation.targetPath(), true, WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY));
                case MOVE_PATH, RENAME_PATH -> {
                    WorkspaceOperationPlan.OperationKind kind = operation.kind() == WorkspaceBatchOperation.Kind.MOVE_PATH
                            ? WorkspaceOperationPlan.OperationKind.MOVE_PATH
                            : WorkspaceOperationPlan.OperationKind.RENAME_PATH;
                    effects.add(WorkspaceOperationPlan.PathEffect.source(operation.sourcePath(), true, kind));
                    effects.add(WorkspaceOperationPlan.PathEffect.destination(operation.destinationPath(), true, kind));
                }
                case COPY_PATH -> {
                    effects.add(WorkspaceOperationPlan.PathEffect.source(
                            operation.sourcePath(), false, WorkspaceOperationPlan.OperationKind.COPY_PATH));
                    effects.add(WorkspaceOperationPlan.PathEffect.destination(
                            operation.destinationPath(), true, WorkspaceOperationPlan.OperationKind.COPY_PATH));
                }
            }
        }

        String preview = operations.stream()
                .map(WorkspaceBatchOperation::previewLine)
                .reduce((left, right) -> left + "; " + right)
                .orElse("batch workspace apply");
        WorkspaceOperationPlan checkpointPlan = WorkspaceOperationPlan.batch(
                WorkspaceOperationPlan.OperationKind.BATCH_APPLY,
                effects,
                ToolRiskLevel.WRITE,
                true,
                WorkspaceOperationPlan.OverwritePolicy.OVERWRITE,
                true,
                "Apply workspace batch: " + preview,
                preview);
        return Optional.of(new WorkspaceBatchPlan(operations, checkpointPlan, preview));
    }

    public static List<String> pathValues(ToolCall call) {
        try {
            Optional<WorkspaceBatchPlan> plan = parse(call);
            return plan.map(WorkspaceBatchPlan::pathValues).orElse(List.of());
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private static WorkspaceBatchOperation parseOperation(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Invalid operations_json: every operation must be an object.");
        }
        WorkspaceBatchOperation.Kind kind = parseKind(text(node, "op", "kind", "operation", "type"));
        return switch (kind) {
            case MKDIR -> new WorkspaceBatchOperation(
                    kind,
                    "",
                    "",
                    requiredPath(node, "path", "dir", "directory"),
                    "",
                    false,
                    false);
            case MOVE_PATH -> new WorkspaceBatchOperation(
                    kind,
                    requiredPath(node, "from", "source", "source_path", "src", "path"),
                    requiredPath(node, "to", "destination", "destination_path", "dest", "target"),
                    "",
                    "",
                    bool(node, "overwrite"),
                    false);
            case COPY_PATH -> new WorkspaceBatchOperation(
                    kind,
                    requiredPath(node, "from", "source", "source_path", "src", "path"),
                    requiredPath(node, "to", "destination", "destination_path", "dest", "target"),
                    "",
                    "",
                    bool(node, "overwrite"),
                    bool(node, "recursive"));
            case RENAME_PATH -> renameOperation(node, kind);
        };
    }

    private static WorkspaceBatchOperation renameOperation(JsonNode node, WorkspaceBatchOperation.Kind kind) {
        String source = requiredPath(node, "path", "from", "source", "source_path");
        String newName = requiredPath(node, "new_name", "newName", "name", "to_name");
        validateNewName(newName);
        String destination = siblingPath(source, newName);
        return new WorkspaceBatchOperation(kind, source, destination, "", newName, bool(node, "overwrite"), false);
    }

    private static WorkspaceBatchOperation.Kind parseKind(String rawKind) {
        if (rawKind == null || rawKind.isBlank()) {
            throw new IllegalArgumentException("Invalid operations_json: operation is missing `op`.");
        }
        String normalized = rawKind.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "mkdir", "make_dir", "make_directory", "create_dir", "create_directory" ->
                    WorkspaceBatchOperation.Kind.MKDIR;
            case "move", "mv", "move_path" -> WorkspaceBatchOperation.Kind.MOVE_PATH;
            case "copy", "cp", "copy_path" -> WorkspaceBatchOperation.Kind.COPY_PATH;
            case "rename", "rename_path" -> WorkspaceBatchOperation.Kind.RENAME_PATH;
            default -> throw new IllegalArgumentException("Unsupported batch operation: " + rawKind);
        };
    }

    private static String requiredPath(JsonNode node, String canonical, String... aliases) {
        String value = text(node, canonical, aliases);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Invalid operations_json: missing required path `" + canonical + "`.");
        }
        return value.strip().replace('\\', '/');
    }

    private static String text(JsonNode node, String canonical, String... aliases) {
        JsonNode value = node.get(canonical);
        if (value != null && !value.isNull()) return value.asText();
        for (String alias : aliases) {
            value = node.get(alias);
            if (value != null && !value.isNull()) return value.asText();
        }
        return null;
    }

    private static boolean bool(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return false;
        if (value.isBoolean()) return value.asBoolean();
        String text = value.asText("").strip().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "yes".equals(text) || "1".equals(text) || "on".equals(text);
    }

    private static void validateNewName(String newName) {
        String value = newName == null ? "" : newName.strip();
        try {
            if (value.isBlank()
                    || ".".equals(value)
                    || "..".equals(value)
                    || value.contains("/")
                    || value.contains("\\")
                    || Path.of(value).isAbsolute()) {
                throw new IllegalArgumentException("`new_name` must be a single path segment.");
            }
        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException("`new_name` must be a single path segment.", e);
        }
    }

    private static String siblingPath(String source, String newName) {
        String normalized = source.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? newName : normalized.substring(0, slash + 1) + newName;
    }

    private static String operationsJson(ToolCall call) {
        if (call == null) return null;
        for (String key : List.of("operations_json", "operations", "plan_json", "batch_json")) {
            String value = call.param(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
