package dev.talos.runtime.toolcall;

import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.util.Locale;
import java.util.regex.Pattern;

final class StaticWebRequiredAssetWriteGuard {
    private static final Pattern NEGATED_BLANK_REQUEST = Pattern.compile(
            "(?s).*(?:do\\s+not|don't|dont|not|no)\\s+.{0,120}\\b(?:blank|empty|clear|truncate|wipe)\\b.*");
    private static final Pattern EXPLICIT_BLANK_REQUEST = Pattern.compile(
            "(?s).*(?:leave|make)\\s+(?:it|[a-z0-9_.\\\\/-]+)\\s+blank\\b.*");

    private StaticWebRequiredAssetWriteGuard() {}

    static String diagnostic(
            ToolCall call,
            LoopState state,
            TaskContract contract,
            String pathHint
    ) {
        if (call == null || contract == null || pathHint == null || pathHint.isBlank()) {
            return null;
        }
        if (!"write_file".equals(ToolAliasPolicy.localCanonicalName(call.toolName()))) {
            return null;
        }
        if (!contract.mutationAllowed() || !contract.verificationRequired()) {
            return null;
        }
        if (contract.type() != TaskType.FILE_EDIT && contract.type() != TaskType.FILE_CREATE) {
            return null;
        }
        String path = ToolCallSupport.normalizePath(pathHint);
        if (!StaticWebCapabilityProfile.isSmallWebFile(path)) {
            return null;
        }
        if (!isExpectedTarget(contract, path)) {
            return null;
        }
        String content = call.param("content");
        if (content == null) {
            content = call.param("text");
        }
        if (content == null || !content.isBlank()) {
            return null;
        }
        if (explicitlyAllowsBlankRequiredAsset(contract.originalUserRequest(), path)) {
            return null;
        }
        return "Static-web write rejected before approval: " + path
                + " is a blank required static-web asset. Required HTML/CSS/JS targets must receive "
                + "complete file content unless the user explicitly asks to clear or truncate the file. "
                + "No approval was requested and no file was changed.";
    }

    private static boolean isExpectedTarget(TaskContract contract, String path) {
        if (contract == null || path == null || path.isBlank()) return false;
        for (String target : contract.expectedTargets()) {
            String normalized = ToolCallSupport.normalizePath(target);
            if (path.equals(normalized) || path.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static boolean explicitlyAllowsBlankRequiredAsset(String request, String path) {
        if (request == null || request.isBlank()) return false;
        if (path == null || path.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        if (NEGATED_BLANK_REQUEST.matcher(lower).matches()) {
            return false;
        }
        String target = ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
        String basename = target.contains("/")
                ? target.substring(target.lastIndexOf('/') + 1)
                : target;
        return targetBoundBlankPermission(lower, target)
                || (!basename.equals(target) && targetBoundBlankPermission(lower, basename));
    }

    private static boolean targetBoundBlankPermission(String requestLower, String targetLower) {
        if (requestLower == null || requestLower.isBlank()
                || targetLower == null || targetLower.isBlank()) {
            return false;
        }
        String target = Pattern.quote(targetLower);
        return Pattern.compile("(?s).*\\b(?:clear|empty|truncate|wipe)\\s+"
                        + "(?:the\\s+)?(?:file\\s+)?" + target + "\\b.*")
                .matcher(requestLower)
                .matches()
                || Pattern.compile("(?s).*\\b(?:clear|empty|truncate|wipe)\\s+"
                        + "(?:all\\s+)?(?:content|contents)\\s+(?:from|of|in)\\s+"
                        + "(?:the\\s+)?(?:file\\s+)?" + target + "\\b.*")
                .matcher(requestLower)
                .matches()
                || Pattern.compile("(?s).*\\b(?:delete|remove)\\s+all\\s+"
                        + "(?:content|contents)\\s+(?:from|of|in)\\s+"
                        + "(?:the\\s+)?(?:file\\s+)?" + target + "\\b.*")
                .matcher(requestLower)
                .matches()
                || Pattern.compile("(?s).*\\b(?:leave|make)\\s+"
                        + "(?:the\\s+)?(?:file\\s+)?" + target + "\\s+blank\\b.*")
                .matcher(requestLower)
                .matches()
                || (EXPLICIT_BLANK_REQUEST.matcher(requestLower).matches()
                && Pattern.compile("(?s).*\\b" + target + "\\b.*")
                .matcher(requestLower)
                .matches());
    }
}
