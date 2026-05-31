package dev.talos.runtime.verification;

public record TargetBinding(
        String triggerSelector,
        String outputSelector,
        String eventType
) {
    public TargetBinding {
        triggerSelector = normalizeSelector(triggerSelector);
        outputSelector = normalizeSelector(outputSelector);
        eventType = eventType == null || eventType.isBlank() ? "click" : eventType.strip().toLowerCase();
    }

    private static String normalizeSelector(String selector) {
        if (selector == null) return "";
        String out = selector.strip();
        if (out.isBlank()) return "";
        return out.startsWith("#") || out.startsWith(".") ? out : "#" + out;
    }
}
