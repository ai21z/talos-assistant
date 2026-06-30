package dev.talos.runtime.policy;

/** Typed allow/ask/deny decision for one attempted tool call. */
public record PermissionDecision(
        PermissionAction action,
        String reasonCode,
        String userMessage,
        String relativePath,
        boolean protectedPath,
        String protectedKind,
        boolean rememberEligible
) {
    public PermissionDecision {
        if (action == null) action = PermissionAction.ASK;
        reasonCode = reasonCode == null || reasonCode.isBlank() ? "UNKNOWN" : reasonCode;
        userMessage = userMessage == null ? "" : userMessage;
        relativePath = relativePath == null ? "" : relativePath;
        protectedKind = protectedKind == null ? "" : protectedKind;
    }

    public static PermissionDecision allow(String reasonCode, ResourceDecision resource) {
        return new PermissionDecision(
                PermissionAction.ALLOW,
                reasonCode,
                "",
                resource == null ? "" : resource.relativePath(),
                resource != null && resource.protectedPath(),
                resource == null ? "" : resource.protectedKind(),
                false);
    }

    public static PermissionDecision ask(
            String reasonCode,
            String userMessage,
            ResourceDecision resource,
            boolean rememberEligible
    ) {
        return new PermissionDecision(
                PermissionAction.ASK,
                reasonCode,
                userMessage,
                resource == null ? "" : resource.relativePath(),
                resource != null && resource.protectedPath(),
                resource == null ? "" : resource.protectedKind(),
                rememberEligible);
    }

    public static PermissionDecision deny(String reasonCode, String userMessage, ResourceDecision resource) {
        return new PermissionDecision(
                PermissionAction.DENY,
                reasonCode,
                userMessage,
                resource == null ? "" : resource.relativePath(),
                resource != null && resource.protectedPath(),
                resource == null ? "" : resource.protectedKind(),
                false);
    }

    public PermissionDecision forceAsk(String reasonCode, String message) {
        return new PermissionDecision(
                PermissionAction.ASK,
                reasonCode,
                message,
                relativePath,
                protectedPath,
                protectedKind,
                false);
    }
}
