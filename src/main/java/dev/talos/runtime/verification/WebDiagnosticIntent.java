package dev.talos.runtime.verification;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;

public final class WebDiagnosticIntent {
    private WebDiagnosticIntent() {}

    public static boolean matchesReadOnlyRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        TaskContract contract = TaskContractResolver.fromUserRequest(userRequest);
        if (contract.mutationRequested()) return false;

        String lower = userRequest.toLowerCase();
        boolean webSurface = lower.contains("website")
                || lower.contains("web site")
                || lower.contains("web app")
                || lower.contains("webpage")
                || lower.contains("web page")
                || lower.contains("html")
                || lower.contains("css")
                || lower.contains("javascript")
                || lower.contains("script.js")
                || lower.contains("bmi");
        boolean diagnostic = lower.contains("not working")
                || lower.contains("broken")
                || lower.contains("issue")
                || lower.contains("problem")
                || lower.contains("inspect")
                || lower.contains("diagnose")
                || lower.contains("troubleshoot")
                || lower.contains("identify")
                || lower.contains("check")
                || lower.contains("why");
        return webSurface && diagnostic;
    }
}
