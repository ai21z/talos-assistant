package dev.talos.core.extract;

import java.nio.file.Path;
import java.util.Objects;

public record DocumentExtractionRequest(Path path, Path workspaceRoot, DocumentExtractionIntent intent) {
    public DocumentExtractionRequest {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        workspaceRoot = workspaceRoot == null
                ? path.getParent()
                : workspaceRoot.toAbsolutePath().normalize();
        intent = intent == null ? DocumentExtractionIntent.READ : intent;
    }

    public static DocumentExtractionRequest read(Path path, Path workspaceRoot) {
        return new DocumentExtractionRequest(path, workspaceRoot, DocumentExtractionIntent.READ);
    }

    public static DocumentExtractionRequest search(Path path, Path workspaceRoot) {
        return new DocumentExtractionRequest(path, workspaceRoot, DocumentExtractionIntent.SEARCH);
    }

    public static DocumentExtractionRequest index(Path path, Path workspaceRoot) {
        return new DocumentExtractionRequest(path, workspaceRoot, DocumentExtractionIntent.INDEX);
    }
}
