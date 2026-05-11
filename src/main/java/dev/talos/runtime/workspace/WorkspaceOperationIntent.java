package dev.talos.runtime.workspace;

import dev.talos.runtime.task.TaskContract;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Detects simple explicit workspace organization operations from the current user request. */
public final class WorkspaceOperationIntent {
    private static final String PATH_TOKEN =
            "`?([A-Za-z0-9_.\\\\/-]+(?:\\.[A-Za-z0-9]+|[\\\\/][A-Za-z0-9_.-]+)?)`?";
    private static final Pattern MOVE_REQUEST = Pattern.compile(
            "\\bmove\\s+" + PATH_TOKEN + "\\s+(?:to|into)\\s+" + PATH_TOKEN,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COPY_REQUEST = Pattern.compile(
            "\\bcopy\\s+" + PATH_TOKEN + "\\s+(?:to|into)\\s+" + PATH_TOKEN,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAME_REQUEST = Pattern.compile(
            "\\brename\\s+" + PATH_TOKEN + "\\s+(?:to|as)\\s+" + PATH_TOKEN,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MKDIR_REQUEST = Pattern.compile(
            "\\b(?:mkdir|make\\s+(?:(?:a|an)\\s+)?(?:new\\s+)?(?:directory|dir|folder)"
                    + "|create\\s+(?:(?:a|an)\\s+)?(?:new\\s+)?(?:directory|dir|folder))\\s+"
                    + "(?:(?:called|named|as)\\s+)?"
                    + PATH_TOKEN,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_REQUEST = Pattern.compile(
            "\\b(?:delete|remove|rm)\\s+" + PATH_TOKEN,
            Pattern.CASE_INSENSITIVE);

    private WorkspaceOperationIntent() {}

    public static Optional<Intent> detect(TaskContract contract) {
        if (contract == null || !contract.mutationAllowed()) return Optional.empty();
        Optional<Intent> intent = detect(contract.originalUserRequest());
        if (intent.isPresent()
                && intent.get().kind() == Kind.DELETE_PATH
                && contract.expectedTargets().isEmpty()) {
            return Optional.empty();
        }
        return intent;
    }

    public static Optional<Intent> detect(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Optional.empty();
        String request = userRequest.strip();
        String lower = request.toLowerCase(Locale.ROOT);
        if (lower.contains("apply_workspace_batch") || lower.contains("operations_json")) {
            return Optional.empty();
        }
        if (MOVE_REQUEST.matcher(request).find()) return Optional.of(new Intent(Kind.MOVE_PATH));
        if (COPY_REQUEST.matcher(request).find()) return Optional.of(new Intent(Kind.COPY_PATH));
        if (RENAME_REQUEST.matcher(request).find()) return Optional.of(new Intent(Kind.RENAME_PATH));
        if (MKDIR_REQUEST.matcher(request).find()) return Optional.of(new Intent(Kind.MKDIR));
        if (DELETE_REQUEST.matcher(request).find()) return Optional.of(new Intent(Kind.DELETE_PATH));
        return Optional.empty();
    }

    public enum Kind {
        MKDIR("talos.mkdir", "workspace mkdir operation surface"),
        MOVE_PATH("talos.move_path", "workspace move operation surface"),
        COPY_PATH("talos.copy_path", "workspace copy operation surface"),
        RENAME_PATH("talos.rename_path", "workspace rename operation surface"),
        DELETE_PATH("talos.delete_path", "workspace delete operation surface");

        private final String toolName;
        private final String surfaceReason;

        Kind(String toolName, String surfaceReason) {
            this.toolName = toolName;
            this.surfaceReason = surfaceReason;
        }

        public String toolName() {
            return toolName;
        }

        public List<String> toolNames() {
            return List.of(toolName);
        }

        public String surfaceReason() {
            return surfaceReason;
        }
    }

    public record Intent(Kind kind) {
        public Intent {
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
        }

        public List<String> toolNames() {
            return kind.toolNames();
        }

        public String surfaceReason() {
            return kind.surfaceReason();
        }
    }
}
