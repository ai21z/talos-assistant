package dev.talos.runtime.workspace;

import java.util.List;

/** One non-destructive operation inside a workspace batch apply request. */
public record WorkspaceBatchOperation(
        Kind kind,
        String sourcePath,
        String destinationPath,
        String targetPath,
        String newName,
        boolean overwrite,
        boolean recursive
) {
    public WorkspaceBatchOperation {
        if (kind == null) kind = Kind.MKDIR;
        sourcePath = normalize(sourcePath);
        destinationPath = normalize(destinationPath);
        targetPath = normalize(targetPath);
        newName = newName == null ? "" : newName.strip();
    }

    public List<String> pathValues() {
        return switch (kind) {
            case MKDIR -> List.of(targetPath);
            case MOVE_PATH, COPY_PATH -> List.of(sourcePath, destinationPath);
            case RENAME_PATH -> List.of(sourcePath, destinationPath);
            case DELETE_PATH -> List.of(targetPath);
        };
    }

    public String previewLine() {
        return switch (kind) {
            case MKDIR -> "mkdir " + targetPath;
            case MOVE_PATH -> "move " + sourcePath + " -> " + destinationPath;
            case COPY_PATH -> "copy " + sourcePath + " -> " + destinationPath;
            case RENAME_PATH -> "rename " + sourcePath + " -> " + destinationPath;
            case DELETE_PATH -> "delete " + targetPath;
        };
    }

    public String appliedPathSummary() {
        return switch (kind) {
            case MKDIR -> targetPath;
            case MOVE_PATH, COPY_PATH, RENAME_PATH -> sourcePath + " -> " + destinationPath;
            case DELETE_PATH -> targetPath;
        };
    }

    private static String normalize(String path) {
        return path == null ? "" : path.strip().replace('\\', '/');
    }

    public enum Kind {
        MKDIR,
        MOVE_PATH,
        COPY_PATH,
        RENAME_PATH,
        DELETE_PATH
    }
}
