package dev.talos.runtime.toolcall;

import dev.talos.runtime.TemplatePlaceholderGuard;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.WorkspacePathIdentity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class StaticRepairWriteContentGuard {
    static final String FAILURE_KIND = "STATIC_REPAIR_INVALID_WRITE_CONTENT";

    private StaticRepairWriteContentGuard() {
    }

    record Failure(String reason, String answer) {
    }

    static Optional<Failure> evaluate(List<ChatMessage> messages, List<ToolCall> calls) {
        return evaluate(messages, calls, null);
    }

    static Optional<Failure> evaluate(List<ChatMessage> messages, List<ToolCall> calls, Path workspace) {
        if (calls == null || calls.isEmpty()) return Optional.empty();
        Set<String> targets = RepairPolicy.fullRewriteTargetsFromRepairContext(messages);
        if (targets == null || targets.isEmpty()) return Optional.empty();
        String detail = invalidWriteDetail(calls, new ArrayList<>(targets), workspace);
        if (detail == null) return Optional.empty();
        return Optional.of(new Failure(
                FAILURE_KIND + ": " + detail,
                failureAnswer(detail)));
    }

    static String invalidWriteDetail(List<ToolCall> calls, List<String> targets) {
        return invalidWriteDetail(calls, targets, null);
    }

    /** A write that names a repair target by text or as the same workspace file has its content checked. */
    static String invalidWriteDetail(List<ToolCall> calls, List<String> targets, Path workspace) {
        Set<String> normalizedTargets = normalizedTargets(targets);
        if (normalizedTargets.isEmpty() || calls == null || calls.isEmpty()) {
            return null;
        }
        for (ToolCall call : calls) {
            if (call == null || !"talos.write_file".equals(call.canonicalToolName())) continue;
            String rawPath = call.param("path", "");
            String path = ToolCallSupport.normalizePath(rawPath);
            if (path.isBlank() || !(normalizedTargets.contains(path)
                    || namesTargetFile(workspace, targets, rawPath))) continue;
            String content = firstPresentParam(
                    call,
                    "content",
                    "text",
                    "body",
                    "data",
                    "file_content");
            if (content == null) {
                return rejectedWriteDetail(
                        path,
                        "missing required `content` argument");
            }
            if (content.isBlank()) {
                return rejectedWriteDetail(
                        path,
                        "empty or blank content");
            }
            if (TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(content)) {
                return rejectedWriteDetail(
                        path,
                        "literal template-placeholder content");
            }
        }
        return null;
    }

    private static String rejectedWriteDetail(String path, String reason) {
        String safePath = path == null || path.isBlank() ? "(unknown)" : path;
        String safeReason = reason == null || reason.isBlank() ? "invalid content" : reason;
        return "Static web repair rejected talos.write_file(" + safePath + ") before apply because "
                + safeReason + ". No approval was requested and no file was changed.";
    }

    private static String failureAnswer(String detail) {
        String safeDetail = detail == null || detail.isBlank()
                ? "Static web repair write content was invalid before apply."
                : detail.strip();
        return "[Action obligation failed: static repair write content was invalid.]\n\n"
                + safeDetail + "\n"
                + "Talos stopped this turn deterministically.";
    }

    static boolean namesTargetFile(Path workspace, List<String> targets, String rawPath) {
        if (workspace == null || targets == null || rawPath == null || rawPath.isBlank()) return false;
        for (String target : targets) {
            if (WorkspacePathIdentity.matches(workspace, target, rawPath)) return true;
        }
        return false;
    }

    private static Set<String> normalizedTargets(List<String> targets) {
        if (targets == null || targets.isEmpty()) return Set.of();
        Set<String> normalized = new HashSet<>();
        for (String target : targets) {
            String path = ToolCallSupport.normalizePath(target);
            if (!path.isBlank()) normalized.add(path);
        }
        return normalized;
    }

    private static String firstPresentParam(ToolCall call, String... keys) {
        if (call == null || keys == null) return null;
        for (String key : keys) {
            String value = call.param(key);
            if (value != null) return value;
        }
        return null;
    }
}
