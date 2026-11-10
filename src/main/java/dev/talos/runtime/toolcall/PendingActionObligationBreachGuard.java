package dev.talos.runtime.toolcall;

import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;
import dev.talos.tools.WorkspacePathIdentity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class PendingActionObligationBreachGuard {

    private PendingActionObligationBreachGuard() {
    }

    record Decision(boolean breach, boolean deferToPolicy, String detail) {
        Decision {
            detail = detail == null ? "" : detail;
        }

        static Decision none() {
            return new Decision(false, false, "");
        }

        static Decision breach(String detail) {
            return new Decision(true, false, detail);
        }

        static Decision deferredToPolicy() {
            return new Decision(false, true, "");
        }
    }

    static Decision assess(PendingActionObligation obligation, List<ToolCall> calls) {
        return assess(obligation, calls, null);
    }

    /**
     * The workspace lets a pending target be written under another spelling of the same
     * workspace file. The static repair content guard checks a write under any spelling, so
     * identity cannot let a write skip it.
     */
    static Decision assess(PendingActionObligation obligation, List<ToolCall> calls, Path workspace) {
        if (obligation == null || calls == null || calls.isEmpty()) {
            return Decision.none();
        }
        return switch (obligation.kind()) {
            case EXPECTED_TARGETS_REMAINING -> expectedTargetDecision(obligation, calls, workspace);
            case OLD_STRING_MISS_TARGET_REPAIR,
                    APPEND_LINE_TARGET_REPAIR,
                    EXPECTED_TARGET_SCOPE_REPAIR -> targetRepairDecision(obligation, calls, workspace);
            case STATIC_REPAIR_TARGETS_REMAINING -> staticRepairDecision(obligation, calls, workspace);
        };
    }

    private static Decision expectedTargetDecision(
            PendingActionObligation obligation,
            List<ToolCall> calls,
            Path workspace
    ) {
        String detail = invalidExpectedTargetMutationDetail(
                calls, obligation.targets(), obligation.changedTargets(), workspace);
        if (detail == null) {
            return Decision.none();
        }
        if (shouldPolicyHandleStaticWebExpectedTargetViolation(calls, obligation.targets())) {
            return Decision.deferredToPolicy();
        }
        return Decision.breach(detail);
    }

    private static Decision targetRepairDecision(
            PendingActionObligation obligation,
            List<ToolCall> calls,
            Path workspace
    ) {
        if (containsMutatingCallForPendingTarget(calls, obligation.targets(), workspace)) {
            return Decision.none();
        }
        String repairName = switch (obligation.kind()) {
            case APPEND_LINE_TARGET_REPAIR -> "append-line compact repair";
            case EXPECTED_TARGET_SCOPE_REPAIR -> "expected-target scope compact repair";
            default -> "old-string miss compact repair";
        };
        return Decision.breach(targetRepairInvalidToolDetail(repairName, calls, obligation.targets()));
    }

    private static Decision staticRepairDecision(
            PendingActionObligation obligation,
            List<ToolCall> calls,
            Path workspace
    ) {
        String invalidWriteDetail = StaticRepairWriteContentGuard.invalidWriteDetail(
                calls,
                obligation.targets(),
                workspace);
        if (invalidWriteDetail == null
                && containsWriteFileForPendingTarget(calls, obligation.targets(), workspace)) {
            return Decision.none();
        }
        String detail = invalidWriteDetail == null
                ? staticRepairInvalidToolDetail(calls, obligation.targets())
                : invalidWriteDetail;
        return Decision.breach(detail);
    }

    private static String invalidExpectedTargetMutationDetail(
            List<ToolCall> calls,
            List<String> targets,
            List<String> changedTargets,
            Path workspace
    ) {
        Set<String> normalizedTargets = normalizedExpectedProgressTargets(targets);
        if (normalizedTargets.isEmpty() || calls == null || calls.isEmpty()) {
            return null;
        }
        Set<String> normalizedChangedTargets = normalizedExpectedProgressTargets(changedTargets);
        List<String> rejectedMutations = new ArrayList<>();
        for (ToolCall call : calls) {
            if (call == null || !ToolCallSupport.isMutatingTool(call.toolName())) continue;
            String rawPath = ToolCallSupport.resolvePathHint(call);
            String path = ToolCallSupport.normalizePath(rawPath);
            if (!path.isBlank() && (matchesPendingExpectedTarget(call.toolName(), path, normalizedTargets)
                    || sameWorkspaceFileAsTarget(workspace, targets, rawPath)
                    || caseVariantOfMissingTarget(workspace, targets, rawPath)
                    || mkdirOfTargetParent(call.toolName(), workspace, targets, rawPath)
                    || admittedChangeOfChangedTarget(call, path, rawPath, normalizedChangedTargets,
                            changedTargets, workspace))) {
                continue;
            }
            String name = call.toolName() == null || call.toolName().isBlank()
                    ? "(unknown mutating tool)"
                    : call.toolName();
            boolean changedTarget = !path.isBlank()
                    && (normalizedChangedTargets.contains(normalizeScopedTargetPreservingCase(path))
                    || sameWorkspaceFileAsTarget(workspace, changedTargets, rawPath));
            rejectedMutations.add(path.isBlank() ? name : name + "(" + path + ")"
                    + (changedTarget ? " [changed in this turn, only an exact edit with arguments or a complete "
                            + "write with content is accepted]" : ""));
        }
        if (rejectedMutations.isEmpty()) {
            return null;
        }
        String targetList = targets == null || targets.isEmpty()
                ? "(unknown)"
                : String.join(", ", targets);
        return "expected-target progress required mutation of remaining target(s): "
                + targetList + ", but the model attempted: "
                + String.join(", ", rejectedMutations)
                + ". No approval was requested and no additional file was changed.";
    }

    private static boolean shouldPolicyHandleStaticWebExpectedTargetViolation(
            List<ToolCall> calls,
            List<String> targets
    ) {
        if (calls == null || calls.isEmpty() || targets == null || targets.isEmpty()) return false;
        if (!targets.stream().allMatch(StaticWebCapabilityProfile::isSmallWebFile)) return false;
        for (ToolCall call : calls) {
            if (call == null || !ToolCallSupport.isMutatingTool(call.toolName())) continue;
            String path = ToolCallSupport.normalizePath(ToolCallSupport.resolvePathHint(call));
            if (path.isBlank()) continue;
            String scoped = normalizeScopedTarget(path);
            if (scoped.contains("/") || !StaticWebCapabilityProfile.isSmallWebFile(scoped)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPendingExpectedTarget(
            String toolName,
            String candidatePath,
            Set<String> normalizedTargets
    ) {
        String candidate = normalizeScopedTargetPreservingCase(candidatePath);
        if (candidate.isBlank()) return false;
        if (normalizedTargets.contains(candidate)) return true;
        if (!isMkdirTool(toolName)) return false;
        for (String target : normalizedTargets) {
            if (target.startsWith(candidate + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * HC-F6b and D26 option A: the call is admitted by the pending expected-target obligation only because it is
     * an exact edit or a complete write of a target that this turn already changed. Such a call answers the
     * runtime's progress request, so the tool loop treats it as proposed after a continuation: a remembered
     * approval does not cover it, and it is asked once without the session option (P23, D20 option A). A call to
     * a remaining target is not counted.
     */
    static boolean admitsAsChangedTargetChange(PendingActionObligation obligation, ToolCall call, Path workspace) {
        if (obligation == null || call == null
                || obligation.kind() != PendingActionObligation.Kind.EXPECTED_TARGETS_REMAINING
                || obligation.changedTargets().isEmpty()
                || !ToolCallSupport.isMutatingTool(call.toolName())) {
            return false;
        }
        String rawPath = ToolCallSupport.resolvePathHint(call);
        String path = ToolCallSupport.normalizePath(rawPath);
        if (path.isBlank()) return false;
        List<String> targets = obligation.targets();
        if (matchesPendingExpectedTarget(call.toolName(), path, normalizedExpectedProgressTargets(targets))
                || sameWorkspaceFileAsTarget(workspace, targets, rawPath)) {
            return false;
        }
        return admittedChangeOfChangedTarget(call, path, rawPath,
                normalizedExpectedProgressTargets(obligation.changedTargets()), obligation.changedTargets(), workspace);
    }

    /**
     * HC-F6: an exact edit of a request target that this turn already changed is in scope, because an exact
     * edit replaces one occurrence and a file can need several. The call still passes every pre-approval
     * check, so it stays inside the request targets and applies only where its old_string occurs exactly
     * once in the named file. D26 option A (HC4-F1): a complete write with non-blank content of such a target
     * is in scope for the same reason, because a model may finish a file it already changed by writing it whole.
     * Both are asked once without the session option (admitsAsChangedTargetChange), and the approval diff is
     * taken against the file as this turn left it. That diff is a bounded preview: its counts cover the whole
     * change and a cut is stated, but a removed earlier change beyond the cut is not named (finding D26-F5, open).
     * An edit with empty arguments, a write without content and every other mutation of a changed target are not
     * admitted here.
     */
    private static boolean admittedChangeOfChangedTarget(
            ToolCall call,
            String path,
            String rawPath,
            Set<String> normalizedChangedTargets,
            List<String> changedTargets,
            Path workspace
    ) {
        if (normalizedChangedTargets.isEmpty()) return false;
        String tool = call.canonicalToolName();
        boolean exactEdit = "talos.edit_file".equals(tool) && !ToolCallSupport.hasEmptyEditArguments(call);
        boolean completeWrite = "talos.write_file".equals(tool) && ToolCallSupport.hasNonBlankWriteContent(call);
        if (!exactEdit && !completeWrite) {
            return false;
        }
        return normalizedChangedTargets.contains(normalizeScopedTargetPreservingCase(path))
                || sameWorkspaceFileAsTarget(workspace, changedTargets, rawPath);
    }

    private static boolean sameWorkspaceFileAsTarget(Path workspace, List<String> targets, String path) {
        if (workspace == null || targets == null) return false;
        for (String target : targets) {
            if (WorkspacePathIdentity.matches(workspace, target, path)) return true;
        }
        return false;
    }

    /** T223: a repair write names its target in the requested case, under any other spelling of the path. */
    private static boolean sameTargetKeepingCase(Path workspace, List<String> targets, String path) {
        if (workspace == null || targets == null) return false;
        for (String target : targets) {
            if (WorkspacePathIdentity.matchesKeepingCase(workspace, target, path)) return true;
        }
        return false;
    }

    /**
     * A case variant of a target that exists under neither spelling is an attempt at the target.
     * The pre-approval rule still refuses it, and the scope repair then names the exact target,
     * so no ordinary folder becomes stricter and no case-sensitive folder becomes looser.
     */
    private static boolean caseVariantOfMissingTarget(Path workspace, List<String> targets, String path) {
        if (workspace == null || targets == null) return false;
        for (String target : targets) {
            if (WorkspacePathIdentity.missingCaseVariant(workspace, target, path)) return true;
        }
        return false;
    }

    private static boolean mkdirOfTargetParent(
            String toolName,
            Path workspace,
            List<String> targets,
            String rawPath
    ) {
        if (workspace == null || targets == null || !isMkdirTool(toolName)) return false;
        for (String target : targets) {
            String parent = normalizeScopedTargetPreservingCase(target);
            for (int slash = parent.lastIndexOf('/'); slash > 0; slash = parent.lastIndexOf('/')) {
                parent = parent.substring(0, slash);
                if (WorkspacePathIdentity.matches(workspace, parent, rawPath)) return true;
            }
        }
        return false;
    }

    private static boolean containsMutatingCallForPendingTarget(
            List<ToolCall> calls,
            List<String> targets,
            Path workspace
    ) {
        Set<String> normalizedTargets = normalizedTargets(targets);
        if (normalizedTargets.isEmpty()) return false;
        for (ToolCall call : calls) {
            if (call == null) continue;
            String toolName = call.canonicalToolName();
            if (!"talos.write_file".equals(toolName) && !"talos.edit_file".equals(toolName)) continue;
            String rawPath = call.param("path", "");
            String path = ToolCallSupport.normalizePath(rawPath);
            if (!path.isBlank() && (normalizedTargets.contains(path)
                    || sameTargetKeepingCase(workspace, targets, rawPath))) {
                return true;
            }
        }
        return false;
    }

    private static String targetRepairInvalidToolDetail(
            String repairName,
            List<ToolCall> calls,
            List<String> targets
    ) {
        String safeRepairName = repairName == null || repairName.isBlank()
                ? "target compact repair"
                : repairName.strip();
        String targetList = targets == null || targets.isEmpty()
                ? "(unknown)"
                : String.join(", ", targets);
        List<String> seen = new ArrayList<>();
        if (calls != null) {
            for (ToolCall call : calls) {
                if (call == null) continue;
                String path = ToolCallSupport.normalizePath(call.param("path", ""));
                String name = call.toolName() == null || call.toolName().isBlank()
                        ? "(unknown tool)"
                        : call.toolName();
                seen.add(path.isBlank() ? name : name + "(" + path + ")");
            }
        }
        String seenCalls = seen.isEmpty() ? "(none)" : String.join(", ", seen);
        return safeRepairName + " required talos.write_file or talos.edit_file "
                + "for target(s): " + targetList + ", but the model returned: " + seenCalls
                + ". No approval was requested and no file was changed.";
    }

    private static boolean containsWriteFileForPendingTarget(
            List<ToolCall> calls,
            List<String> targets,
            Path workspace
    ) {
        Set<String> normalizedTargets = normalizedTargets(targets);
        if (normalizedTargets.isEmpty()) return false;
        for (ToolCall call : calls) {
            if (call == null || !"talos.write_file".equals(call.canonicalToolName())) continue;
            String rawPath = call.param("path", "");
            String path = ToolCallSupport.normalizePath(rawPath);
            if (!path.isBlank() && (normalizedTargets.contains(path)
                    || sameTargetKeepingCase(workspace, targets, rawPath))) {
                return true;
            }
        }
        return false;
    }

    private static String staticRepairInvalidToolDetail(
            List<ToolCall> calls,
            List<String> targets
    ) {
        String attempted = calls == null || calls.isEmpty()
                ? "(none)"
                : calls.stream()
                .filter(Objects::nonNull)
                .map(call -> {
                    String path = ToolCallSupport.normalizePath(call.param("path", ""));
                    return path.isBlank() ? call.toolName() : call.toolName() + "(" + path + ")";
                })
                .toList()
                .toString();
        String targetList = targets == null || targets.isEmpty()
                ? "(unknown)"
                : String.join(", ", targets);
        return "Static web repair requires talos.write_file for remaining target(s): "
                + targetList + ". The model attempted " + attempted
                + " instead, so no additional tool call was executed.";
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

    private static Set<String> normalizedExpectedProgressTargets(List<String> targets) {
        if (targets == null || targets.isEmpty()) return Set.of();
        Set<String> normalized = new HashSet<>();
        for (String target : targets) {
            String path = normalizeScopedTargetPreservingCase(target);
            if (!path.isBlank()) normalized.add(path);
        }
        return normalized;
    }

    private static String normalizeScopedTarget(String path) {
        return normalizeScopedTargetPreservingCase(path).toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeScopedTargetPreservingCase(String path) {
        if (path == null) return "";
        String normalized = ToolCallSupport.normalizePath(path)
                .strip()
                .replaceAll("[`'\"),.;:!?\\]]+$", "");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isMkdirTool(String toolName) {
        String normalized = ToolAliasPolicy.localCanonicalName(toolName);
        return "mkdir".equals(normalized)
                || "make_dir".equals(normalized)
                || "make_directory".equals(normalized)
                || "create_dir".equals(normalized)
                || "create_directory".equals(normalized);
    }
}
