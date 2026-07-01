package dev.talos.runtime.workspace;

import dev.talos.tools.ToolRiskLevel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Internal plan for one workspace operation before it is applied.
 *
 * <p>The plan is the unit future workspace tools can use for approval,
 * checkpointing, preview, application, trace, and result rendering.
 */
public record WorkspaceOperationPlan(
        String operationId,
        OperationKind operationKind,
        List<PathEffect> pathEffects,
        ToolRiskLevel riskLevel,
        boolean requiresCheckpoint,
        OverwritePolicy overwritePolicy,
        boolean recursive,
        String approvalSummary,
        String previewSummary
) {
    public WorkspaceOperationPlan {
        operationId = normalize(operationId, "op-" + UUID.randomUUID());
        operationKind = operationKind == null ? OperationKind.BATCH_APPLY : operationKind;
        pathEffects = List.copyOf(pathEffects == null ? List.of() : pathEffects);
        riskLevel = riskLevel == null ? ToolRiskLevel.WRITE : riskLevel;
        overwritePolicy = overwritePolicy == null ? OverwritePolicy.FAIL_IF_EXISTS : overwritePolicy;
        approvalSummary = normalize(approvalSummary, operationKind.name().toLowerCase().replace('_', ' '));
        previewSummary = normalize(previewSummary, approvalSummary);
    }

    public static WorkspaceOperationPlan movePath(
            String sourcePath,
            String destinationPath,
            OverwritePolicy overwritePolicy
    ) {
        String source = normalizePath(sourcePath);
        String destination = normalizePath(destinationPath);
        return new WorkspaceOperationPlan(
                "",
                OperationKind.MOVE_PATH,
                List.of(
                        PathEffect.source(source, true, OperationKind.MOVE_PATH),
                        PathEffect.destination(destination, true, OperationKind.MOVE_PATH)),
                ToolRiskLevel.WRITE,
                true,
                overwritePolicy,
                false,
                "Move " + source + " to " + destination + ".",
                "Move: " + source + " -> " + destination);
    }

    public static WorkspaceOperationPlan copyPath(
            String sourcePath,
            String destinationPath,
            OverwritePolicy overwritePolicy,
            boolean recursive
    ) {
        String source = normalizePath(sourcePath);
        String destination = normalizePath(destinationPath);
        return new WorkspaceOperationPlan(
                "",
                OperationKind.COPY_PATH,
                List.of(
                        PathEffect.source(source, false, OperationKind.COPY_PATH),
                        PathEffect.destination(destination, true, OperationKind.COPY_PATH)),
                ToolRiskLevel.WRITE,
                true,
                overwritePolicy,
                recursive,
                "Copy " + source + " to " + destination + (recursive ? " recursively" : "") + ".",
                "Copy: " + source + " -> " + destination);
    }

    public static WorkspaceOperationPlan deletePath(String targetPath, boolean recursive) {
        String target = normalizePath(targetPath);
        return new WorkspaceOperationPlan(
                "",
                OperationKind.DELETE_PATH,
                List.of(PathEffect.deleted(target, true)),
                ToolRiskLevel.DESTRUCTIVE,
                true,
                OverwritePolicy.NOT_APPLICABLE,
                recursive,
                "Delete " + target + (recursive ? " recursively" : "") + ".",
                "Delete: " + target);
    }

    public static WorkspaceOperationPlan batch(
            OperationKind operationKind,
            List<PathEffect> pathEffects,
            ToolRiskLevel riskLevel,
            boolean requiresCheckpoint,
            OverwritePolicy overwritePolicy,
            boolean recursive,
            String approvalSummary,
            String previewSummary
    ) {
        return new WorkspaceOperationPlan(
                "",
                operationKind,
                pathEffects,
                riskLevel,
                requiresCheckpoint,
                overwritePolicy,
                recursive,
                approvalSummary,
                previewSummary);
    }

    public List<String> pathsByRole(PathRole role) {
        if (role == null || pathEffects.isEmpty()) return List.of();
        return pathEffects.stream()
                .filter(effect -> effect.role() == role)
                .map(PathEffect::path)
                .toList();
    }

    public List<String> checkpointPaths() {
        if (!requiresCheckpoint || pathEffects.isEmpty()) return List.of();
        Set<String> paths = new LinkedHashSet<>();
        for (PathEffect effect : pathEffects) {
            if (effect.checkpointBefore() && !effect.path().isBlank()) {
                paths.add(effect.path());
            }
        }
        return List.copyOf(paths);
    }

    public List<String> changedPaths() {
        if (pathEffects.isEmpty()) return List.of();
        Set<String> paths = new LinkedHashSet<>();
        for (PathEffect effect : pathEffects) {
            if (effect == null || effect.path().isBlank()) continue;
            OperationKind kind = effect.operationKind() == null ? operationKind : effect.operationKind();
            if (isChangedPathEffect(kind, effect.role())) {
                paths.add(effect.path());
            }
        }
        return List.copyOf(paths);
    }

    public String primaryChangedPath() {
        List<String> paths = changedPaths();
        return paths.isEmpty() ? "" : paths.get(0);
    }

    private static boolean isChangedPathEffect(OperationKind kind, PathRole role) {
        if (kind == null || role == null) return false;
        return switch (kind) {
            case COPY_PATH, MOVE_PATH, RENAME_PATH -> role == PathRole.DESTINATION;
            case CREATE_DIRECTORY -> role == PathRole.ABSENT_BEFORE || role == PathRole.TARGET;
            case DELETE_PATH -> role == PathRole.DELETED;
            case WRITE_FILE, BATCH_APPLY -> role == PathRole.DESTINATION
                    || role == PathRole.TARGET
                    || role == PathRole.ABSENT_BEFORE
                    || role == PathRole.DELETED;
        };
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.strip();
    }

    private static String normalizePath(String path) {
        String value = Objects.requireNonNull(path, "path must not be null").strip();
        if (value.isBlank()) throw new IllegalArgumentException("path must not be blank");
        return value.replace('\\', '/');
    }

    public enum OperationKind {
        CREATE_DIRECTORY,
        WRITE_FILE,
        MOVE_PATH,
        COPY_PATH,
        RENAME_PATH,
        DELETE_PATH,
        BATCH_APPLY
    }

    public enum PathRole {
        SOURCE,
        DESTINATION,
        TARGET,
        DELETED,
        ABSENT_BEFORE
    }

    public enum OverwritePolicy {
        NOT_APPLICABLE,
        FAIL_IF_EXISTS,
        OVERWRITE,
        MERGE_DIRECTORIES
    }

    public record PathEffect(String path, PathRole role, boolean checkpointBefore, OperationKind operationKind) {
        public PathEffect {
            path = normalizePath(path);
            role = role == null ? PathRole.TARGET : role;
        }

        public PathEffect(String path, PathRole role, boolean checkpointBefore) {
            this(path, role, checkpointBefore, null);
        }

        public static PathEffect source(String path, boolean checkpointBefore) {
            return new PathEffect(path, PathRole.SOURCE, checkpointBefore);
        }

        public static PathEffect source(String path, boolean checkpointBefore, OperationKind operationKind) {
            return new PathEffect(path, PathRole.SOURCE, checkpointBefore, operationKind);
        }

        public static PathEffect destination(String path, boolean checkpointBefore) {
            return new PathEffect(path, PathRole.DESTINATION, checkpointBefore);
        }

        public static PathEffect destination(String path, boolean checkpointBefore, OperationKind operationKind) {
            return new PathEffect(path, PathRole.DESTINATION, checkpointBefore, operationKind);
        }

        public static PathEffect target(String path, boolean checkpointBefore) {
            return new PathEffect(path, PathRole.TARGET, checkpointBefore);
        }

        public static PathEffect target(String path, boolean checkpointBefore, OperationKind operationKind) {
            return new PathEffect(path, PathRole.TARGET, checkpointBefore, operationKind);
        }

        public static PathEffect deleted(String path, boolean checkpointBefore) {
            return new PathEffect(path, PathRole.DELETED, checkpointBefore);
        }

        public static PathEffect deleted(String path, boolean checkpointBefore, OperationKind operationKind) {
            return new PathEffect(path, PathRole.DELETED, checkpointBefore, operationKind);
        }

        public static PathEffect absentBefore(String path, boolean checkpointBefore) {
            return new PathEffect(path, PathRole.ABSENT_BEFORE, checkpointBefore);
        }

        public static PathEffect absentBefore(String path, boolean checkpointBefore, OperationKind operationKind) {
            return new PathEffect(path, PathRole.ABSENT_BEFORE, checkpointBefore, operationKind);
        }
    }
}
