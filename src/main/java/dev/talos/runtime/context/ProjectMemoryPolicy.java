package dev.talos.runtime.context;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;

import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative current-turn policy for loading project-memory files. */
final class ProjectMemoryPolicy {
    private ProjectMemoryPolicy() {}

    private static final Pattern PROJECT_MEMORY_OPT_OUT = Pattern.compile(
            "(?i)(?:"
                    + "\\b(?:do\\s+not|don't|dont)\\s+"
                    + "(?:load|use|read|include|apply)\\s+"
                    + "(?:the\\s+)?(?:project\\s+memory|talos\\.md|\\.talos/rules\\.md|memory\\s+files?)\\b"
                    + "|\\bignore\\s+(?:the\\s+)?"
                    + "(?:project\\s+memory|talos\\.md|\\.talos/rules\\.md|memory\\s+files?)\\b"
                    + "|\\b(?:answer|respond|continue|proceed|work)?\\s*without\\s+"
                    + "(?:using\\s+|loading\\s+|reading\\s+|including\\s+)?"
                    + "(?:project\\s+memory|talos\\.md|\\.talos/rules\\.md|memory\\s+files?)\\b"
                    + ")");

    record Decision(boolean load, String reason) {}

    static Decision decide(ProjectMemoryRequest request) {
        if (request == null || request.workspace() == null) {
            return new Decision(false, "NO_WORKSPACE");
        }
        TaskContract contract = request.taskContract();
        if (contract == null) {
            return new Decision(false, "NO_TASK_CONTRACT");
        }
        String userRequest = contract.originalUserRequest() == null ? "" : contract.originalUserRequest();
        if (looksProjectMemoryOptOut(userRequest)) {
            return new Decision(false, "USER_OPTED_OUT_PROJECT_MEMORY");
        }
        if (looksPrivacyOrProtectedTurn(userRequest)) {
            return new Decision(false, "PRIVACY_OR_PROTECTED_TURN");
        }
        TaskType type = contract.type();
        if (type == TaskType.SMALL_TALK) {
            return new Decision(false, "SMALL_TALK");
        }
        if (type == TaskType.DIRECTORY_LISTING || type == TaskType.VERIFY_ONLY || type == TaskType.CHECKPOINT_RESTORE) {
            return new Decision(false, "STATUS_OR_LISTING_TURN");
        }
        if (contract.mutationAllowed()) {
            return new Decision(true, "MUTATION_WORKSPACE_TASK");
        }
        if (type == TaskType.WORKSPACE_EXPLAIN) {
            return new Decision(true, "WORKSPACE_EXPLAIN");
        }
        if (type == TaskType.READ_ONLY_QA || type == TaskType.DIAGNOSE_ONLY) {
            return mentionsWorkspaceSurface(userRequest)
                    ? new Decision(true, "WORKSPACE_QA")
                    : new Decision(false, "NON_WORKSPACE_QA");
        }
        return new Decision(false, "UNSUPPORTED_TASK_TYPE");
    }

    private static boolean looksProjectMemoryOptOut(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.replace('\\', '/');
        return PROJECT_MEMORY_OPT_OUT.matcher(normalized).find();
    }

    private static boolean looksPrivacyOrProtectedTurn(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.contains("what data leaves")
                || lower.contains("privacy")
                || lower.contains("protected")
                || lower.contains(".env")
                || lower.contains("secret")
                || lower.contains("private marker")
                || lower.contains("do_not_leak");
    }

    private static boolean mentionsWorkspaceSurface(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.contains("workspace")
                || lower.contains("project")
                || lower.contains("repo")
                || lower.contains("repository")
                || lower.contains("code")
                || lower.contains("site")
                || lower.contains("website")
                || lower.contains("file")
                || lower.contains("folder")
                || lower.contains("directory")
                || lower.contains("here");
    }
}
