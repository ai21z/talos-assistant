package dev.talos.core.extract;

public record DocumentExtractionWarning(String code, String message) {
    public DocumentExtractionWarning {
        code = code == null ? "warning" : code;
        message = message == null ? "" : message;
    }
}
