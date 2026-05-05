package dev.talos.runtime.workspace;

import dev.talos.runtime.toolcall.ToolAliasPolicy;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRiskLevel;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Builds runtime plans for first-class workspace operation tools. */
public final class WorkspaceOperationPlanner {
    private WorkspaceOperationPlanner() {}

    public static boolean isWorkspaceOperationTool(String toolName) {
        String canonical = ToolAliasPolicy.localCanonicalName(toolName);
        return "mkdir".equals(canonical)
                || "move_path".equals(canonical)
                || "copy_path".equals(canonical)
                || "rename_path".equals(canonical);
    }

    public static Optional<WorkspaceOperationPlan> checkpointPlan(ToolCall call) {
        if (call == null) return Optional.empty();
        return switch (ToolAliasPolicy.localCanonicalName(call.toolName())) {
            case "mkdir" -> mkdirPlan(call);
            case "move_path" -> movePlan(call);
            case "copy_path" -> copyPlan(call);
            case "rename_path" -> renamePlan(call);
            default -> Optional.empty();
        };
    }

    public static Optional<String> validateBeforeApproval(ToolCall call) {
        if (call == null || !isWorkspaceOperationTool(call.toolName())) return Optional.empty();
        return switch (ToolAliasPolicy.localCanonicalName(call.toolName())) {
            case "mkdir" -> requirePath(call, "path", "dir", "directory").isPresent()
                    ? Optional.empty()
                    : Optional.of("Invalid talos.mkdir call: missing required parameter `path`. "
                            + "No approval was requested and no file was changed.");
            case "move_path" -> validateTwoPathOperation(call, "talos.move_path");
            case "copy_path" -> validateTwoPathOperation(call, "talos.copy_path");
            case "rename_path" -> validateRename(call);
            default -> Optional.empty();
        };
    }

    private static Optional<WorkspaceOperationPlan> mkdirPlan(ToolCall call) {
        return requirePath(call, "path", "dir", "directory")
                .map(path -> WorkspaceOperationPlan.batch(
                        WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY,
                        List.of(WorkspaceOperationPlan.PathEffect.absentBefore(path, true)),
                        ToolRiskLevel.WRITE,
                        true,
                        WorkspaceOperationPlan.OverwritePolicy.NOT_APPLICABLE,
                        false,
                        "Create directory " + normalizePath(path) + ".",
                        "Create directory: " + normalizePath(path)));
    }

    private static Optional<WorkspaceOperationPlan> movePlan(ToolCall call) {
        Optional<String> source = sourcePath(call);
        Optional<String> destination = destinationPath(call);
        if (source.isEmpty() || destination.isEmpty()) return Optional.empty();
        return Optional.of(WorkspaceOperationPlan.movePath(
                source.get(),
                destination.get(),
                overwritePolicy(call)));
    }

    private static Optional<WorkspaceOperationPlan> copyPlan(ToolCall call) {
        Optional<String> source = sourcePath(call);
        Optional<String> destination = destinationPath(call);
        if (source.isEmpty() || destination.isEmpty()) return Optional.empty();
        return Optional.of(WorkspaceOperationPlan.copyPath(
                source.get(),
                destination.get(),
                overwritePolicy(call),
                boolParam(call, "recursive")));
    }

    private static Optional<WorkspaceOperationPlan> renamePlan(ToolCall call) {
        Optional<String> source = requirePath(call, "path", "from", "source", "source_path");
        String newName = param(call, "new_name", "newName", "name", "to_name");
        if (source.isEmpty() || validateNewName(newName).isPresent()) return Optional.empty();
        String destination = siblingPath(source.get(), newName.strip());
        return Optional.of(WorkspaceOperationPlan.batch(
                WorkspaceOperationPlan.OperationKind.RENAME_PATH,
                List.of(
                        WorkspaceOperationPlan.PathEffect.source(source.get(), true),
                        WorkspaceOperationPlan.PathEffect.destination(destination, true)),
                ToolRiskLevel.WRITE,
                true,
                overwritePolicy(call),
                false,
                "Rename " + normalizePath(source.get()) + " to " + normalizePath(destination) + ".",
                "Rename: " + normalizePath(source.get()) + " -> " + normalizePath(destination)));
    }

    private static Optional<String> validateTwoPathOperation(ToolCall call, String toolName) {
        if (sourcePath(call).isEmpty()) {
            return Optional.of("Invalid " + toolName + " call: missing required parameter `from`. "
                    + "No approval was requested and no file was changed.");
        }
        if (destinationPath(call).isEmpty()) {
            return Optional.of("Invalid " + toolName + " call: missing required parameter `to`. "
                    + "No approval was requested and no file was changed.");
        }
        return Optional.empty();
    }

    private static Optional<String> validateRename(ToolCall call) {
        if (requirePath(call, "path", "from", "source", "source_path").isEmpty()) {
            return Optional.of("Invalid talos.rename_path call: missing required parameter `path`. "
                    + "No approval was requested and no file was changed.");
        }
        return validateNewName(param(call, "new_name", "newName", "name", "to_name"))
                .map(message -> "Invalid talos.rename_path call: " + message
                        + ". No approval was requested and no file was changed.");
    }

    private static Optional<String> sourcePath(ToolCall call) {
        return requirePath(call, "from", "source", "source_path", "src", "path");
    }

    private static Optional<String> destinationPath(ToolCall call) {
        return requirePath(call, "to", "destination", "destination_path", "dest", "target");
    }

    private static Optional<String> requirePath(ToolCall call, String canonical, String... aliases) {
        String value = param(call, canonical, aliases);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(normalizePath(value));
    }

    private static String param(ToolCall call, String canonical, String... aliases) {
        if (call == null) return null;
        String value = call.param(canonical);
        if (value != null) return value;
        for (String alias : aliases) {
            value = call.param(alias);
            if (value != null) return value;
        }
        return null;
    }

    private static WorkspaceOperationPlan.OverwritePolicy overwritePolicy(ToolCall call) {
        return boolParam(call, "overwrite")
                ? WorkspaceOperationPlan.OverwritePolicy.OVERWRITE
                : WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS;
    }

    private static boolean boolParam(ToolCall call, String key) {
        String value = call == null ? null : call.param(key);
        if (value == null || value.isBlank()) return false;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return "true".equals(normalized)
                || "yes".equals(normalized)
                || "y".equals(normalized)
                || "1".equals(normalized)
                || "on".equals(normalized);
    }

    private static Optional<String> validateNewName(String newName) {
        if (newName == null || newName.isBlank()) {
            return Optional.of("missing required parameter `new_name`");
        }
        String value = newName.strip();
        try {
            if (".".equals(value)
                    || "..".equals(value)
                    || value.contains("/")
                    || value.contains("\\")
                    || Path.of(value).isAbsolute()) {
                return Optional.of("`new_name` must be a single path segment");
            }
        } catch (Exception e) {
            return Optional.of("`new_name` must be a single path segment");
        }
        return Optional.empty();
    }

    private static String siblingPath(String source, String newName) {
        String normalized = normalizePath(source);
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? newName : normalized.substring(0, slash + 1) + newName;
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.strip().replace('\\', '/');
    }
}
