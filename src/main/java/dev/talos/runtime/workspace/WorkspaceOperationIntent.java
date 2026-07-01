package dev.talos.runtime.workspace;

import dev.talos.runtime.task.TaskContract;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
            "\\b(?:mkdir|make\\s+(?:me\\s+)?(?:(?:a|an)\\s+)?(?:new\\s+)?(?:directories|directory|dirs|dir|folders|folder)"
                    + "|create\\s+(?:me\\s+)?(?:(?:a|an)\\s+)?(?:new\\s+)?(?:directories|directory|dirs|dir|folders|folder))\\s+"
                    + "(?:(?:called|named|as)\\s+)?"
                    + PATH_TOKEN,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NATURAL_BATCH_MKDIR_REQUEST = Pattern.compile(
            "\\b(?:create|make)\\s+"
                    + "[A-Za-z0-9_.\\\\/-]+(?:\\s+and\\s+[A-Za-z0-9_.\\\\/-]+)+"
                    + "\\s*,?\\s+(?:then\\s+)?(?:copy|move|rename)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_REQUEST = Pattern.compile(
            "\\b(?:delete|remove|rm)\\s+" + PATH_TOKEN,
            Pattern.CASE_INSENSITIVE);

    private WorkspaceOperationIntent() {}

    public static Optional<Intent> detect(TaskContract contract) {
        if (contract == null || !contract.mutationAllowed()) return Optional.empty();
        if ("explicit-batch-workspace-apply-request".equals(contract.classificationReason())) {
            return Optional.of(new Intent(Kind.COMPOUND));
        }
        Optional<Intent> intent = detect(contract.originalUserRequest());
        if (intent.isPresent()
                && intent.get().kind() == Kind.DELETE_PATH
                && contract.expectedTargets().isEmpty()) {
            return Optional.empty();
        }
        if (intent.isPresent()
                && intent.get().kind() == Kind.MKDIR
                && contract.expectedTargets().stream().anyMatch(WorkspaceOperationIntent::looksLikeFileTarget)) {
            return Optional.empty();
        }
        return intent;
    }

    private static boolean looksLikeFileTarget(String target) {
        return target != null && target.matches("(?i).+\\.[A-Za-z0-9]+$");
    }

    public static Optional<Intent> detect(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Optional.empty();
        String request = userRequest.strip();
        String lower = request.toLowerCase(Locale.ROOT);
        if (lower.contains("apply_workspace_batch") || lower.contains("operations_json")) {
            return Optional.empty();
        }
        List<Kind> kinds = new ArrayList<>();
        if (MKDIR_REQUEST.matcher(request).find()
                || NATURAL_BATCH_MKDIR_REQUEST.matcher(request).find()) {
            kinds.add(Kind.MKDIR);
        }
        if (COPY_REQUEST.matcher(request).find()) kinds.add(Kind.COPY_PATH);
        if (RENAME_REQUEST.matcher(request).find()) kinds.add(Kind.RENAME_PATH);
        if (MOVE_REQUEST.matcher(request).find()) kinds.add(Kind.MOVE_PATH);
        if (DELETE_REQUEST.matcher(request).find()) kinds.add(Kind.DELETE_PATH);
        LinkedHashSet<Kind> distinctKinds = new LinkedHashSet<>(kinds);
        if (distinctKinds.size() > 1) {
            return Optional.of(Intent.compound(List.copyOf(distinctKinds)));
        }
        if (MOVE_REQUEST.matcher(request).find()) return Optional.of(new Intent(Kind.MOVE_PATH));
        if (COPY_REQUEST.matcher(request).find()) return Optional.of(new Intent(Kind.COPY_PATH));
        if (RENAME_REQUEST.matcher(request).find()) return Optional.of(new Intent(Kind.RENAME_PATH));
        if (MKDIR_REQUEST.matcher(request).find()
                || NATURAL_BATCH_MKDIR_REQUEST.matcher(request).find()) {
            return Optional.of(new Intent(Kind.MKDIR));
        }
        if (DELETE_REQUEST.matcher(request).find()) return Optional.of(new Intent(Kind.DELETE_PATH));
        return Optional.empty();
    }

    public enum Kind {
        MKDIR("talos.mkdir", "workspace mkdir operation surface"),
        MOVE_PATH("talos.move_path", "workspace move operation surface"),
        COPY_PATH("talos.copy_path", "workspace copy operation surface"),
        RENAME_PATH("talos.rename_path", "workspace rename operation surface"),
        DELETE_PATH("talos.delete_path", "workspace delete operation surface"),
        COMPOUND("talos.apply_workspace_batch", "compound workspace operation surface");

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

    public record Intent(Kind kind, List<String> toolNames, String surfaceReason) {
        public Intent {
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
            toolNames = List.copyOf(toolNames == null ? kind.toolNames() : toolNames);
            surfaceReason = surfaceReason == null ? kind.surfaceReason() : surfaceReason;
        }

        public Intent(Kind kind) {
            this(kind, kind == null ? List.of() : kind.toolNames(), kind == null ? "" : kind.surfaceReason());
        }

        static Intent compound(List<Kind> kinds) {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            names.add("talos.apply_workspace_batch");
            for (Kind kind : kinds == null ? List.<Kind>of() : kinds) {
                if (kind == Kind.COMPOUND) continue;
                names.add(kind.toolName());
            }
            return new Intent(Kind.COMPOUND, List.copyOf(names), Kind.COMPOUND.surfaceReason());
        }

        public List<String> toolNames() {
            return toolNames;
        }

        public String surfaceReason() {
            return surfaceReason;
        }
    }
}
