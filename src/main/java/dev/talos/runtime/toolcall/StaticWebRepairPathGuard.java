package dev.talos.runtime.toolcall;

import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.task.TaskContract;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.util.Comparator;
import java.util.List;

final class StaticWebRepairPathGuard {
    private StaticWebRepairPathGuard() {}

    static String diagnostic(ToolCall call, TaskContract contract, String pathHint) {
        if (call == null || contract == null) return null;
        if (!"write_file".equals(ToolAliasPolicy.localCanonicalName(call.toolName()))) return null;
        if (!contract.mutationAllowed() || contract.expectedTargets().isEmpty()) return null;
        if (!contract.expectedTargets().stream().allMatch(StaticWebCapabilityProfile::isSmallWebFile)) {
            return null;
        }
        List<String> expected = contract.expectedTargets().stream()
                .map(ToolCallSupport::normalizePath)
                .filter(path -> !path.isBlank())
                .sorted(Comparator.naturalOrder())
                .toList();
        if (expected.isEmpty()) return null;
        if (!isRootOrDirectoryPath(pathHint)) {
            return null;
        }
        String display = pathHint == null || pathHint.isBlank() ? "(empty path)" : pathHint.strip();
        return "Target outside expected targets before approval: `" + display
                + "` is outside the current expected target set: "
                + String.join(", ", expected)
                + ". Similar filenames are not substitutes for required target paths. "
                + "No approval was requested and no file was changed.";
    }

    private static boolean isRootOrDirectoryPath(String pathHint) {
        if (pathHint == null || pathHint.isBlank()) return true;
        String raw = pathHint.strip();
        String normalized = ToolCallSupport.normalizePath(raw);
        return raw.equals(".")
                || raw.equals("./")
                || raw.equals(".\\")
                || raw.equals("/")
                || raw.equals("\\")
                || normalized.isBlank()
                || normalized.equals(".");
    }
}
