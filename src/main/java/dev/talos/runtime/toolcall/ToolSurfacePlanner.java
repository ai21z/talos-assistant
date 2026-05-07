package dev.talos.runtime.toolcall;

import dev.talos.core.capability.CapabilityKind;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.workspace.WorkspaceOperationIntent;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolRiskLevel;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plans the native tool surface for one turn from the current task contract,
 * execution phase, and tool operation metadata.
 */
public final class ToolSurfacePlanner {
    private static final Pattern SLASH_PATH_CANDIDATE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_.\\\\/-])([A-Za-z0-9_.-]+(?:[\\\\/][A-Za-z0-9_.-]+)+)"
                    + "(?=$|\\s|[`'\"),;:!?\\]])");
    private static final Pattern FILE_EXTENSION = Pattern.compile("(?i).*\\.[A-Za-z0-9]{1,8}$");

    private ToolSurfacePlanner() {}

    public static Plan plan(
            TaskContract contract,
            ExecutionPhase phase,
            ToolRegistry registry
    ) {
        if (registry == null || registry.isEmpty()) {
            return new Plan(List.of(), "no registry tools");
        }
        if (contract != null && contract.type() == TaskType.SMALL_TALK) {
            return new Plan(List.of(), "small-talk");
        }
        if (contract != null && contract.type() == TaskType.DIRECTORY_LISTING) {
            return select(registry, ToolSurfacePlanner::isDirectoryListingTool, "directory listing");
        }
        if (contract != null
                && !contract.mutationAllowed()
                && verifyOnlyDirectoryAwarePathCheck(contract)) {
            return select(
                    registry,
                    descriptor -> isFileReadTool(descriptor) || isDirectoryListingTool(descriptor),
                    "verify-only path check with directory targets");
        }
        if (contract != null
                && !contract.mutationAllowed()
                && !contract.expectedTargets().isEmpty()) {
            return select(registry, ToolSurfacePlanner::isFileReadTool, "expected target read");
        }

        boolean mutationAllowed = contract != null
                && contract.mutationAllowed()
                && phase == ExecutionPhase.APPLY;

        if (mutationAllowed) {
            var workspaceOperation = WorkspaceOperationIntent.detect(contract);
            if (workspaceOperation.isPresent() && !requiresFileWriteForExactExpectation(contract)) {
                WorkspaceOperationIntent.Intent intent = workspaceOperation.get();
                return select(
                        registry,
                        descriptor -> intent.toolNames().contains(descriptor.name()),
                        intent.surfaceReason());
            }
            return select(registry, ToolSurfacePlanner::isApplyOperation, "mutation apply surface");
        }
        if (explicitCommandVerificationSurface(contract, phase)) {
            return select(registry, ToolSurfacePlanner::isCommandOperation, "explicit command profile surface");
        }
        if (verificationCommandSurface(contract, phase)) {
            return select(registry, ToolSurfacePlanner::isVerificationOperation, "verification command surface");
        }
        return select(registry, ToolSurfacePlanner::isReadOnlyOperation, "read-only metadata surface");
    }

    public static List<String> defaultVisibleToolNames(TaskContract contract, ExecutionPhase phase) {
        if (contract == null || contract.type() == TaskType.SMALL_TALK) return List.of();
        if (contract.type() == TaskType.DIRECTORY_LISTING) return List.of("talos.list_dir");
        if (!contract.mutationAllowed()
                && verifyOnlyDirectoryAwarePathCheck(contract)) {
            return List.of("talos.list_dir", "talos.read_file");
        }
        if (contract.mutationAllowed() && phase == ExecutionPhase.APPLY) {
            var workspaceOperation = WorkspaceOperationIntent.detect(contract);
            if (workspaceOperation.isPresent() && !requiresFileWriteForExactExpectation(contract)) {
                return workspaceOperation.get().toolNames();
            }
            return List.of(
                    "talos.apply_workspace_batch",
                    "talos.copy_path",
                    "talos.edit_file",
                    "talos.grep",
                    "talos.list_dir",
                    "talos.mkdir",
                    "talos.move_path",
                    "talos.read_file",
                    "talos.rename_path",
                    "talos.run_command",
                    "talos.retrieve",
                    "talos.write_file");
        }
        if (explicitCommandVerificationSurface(contract, phase)) {
            return List.of("talos.run_command");
        }
        if (verificationCommandSurface(contract, phase)) {
            return List.of("talos.grep", "talos.list_dir", "talos.read_file", "talos.retrieve", "talos.run_command");
        }
        return List.of("talos.grep", "talos.list_dir", "talos.read_file", "talos.retrieve");
    }

    public static List<String> names(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) return List.of();
        return specs.stream()
                .map(ToolSpec::name)
                .sorted()
                .toList();
    }

    private static boolean requiresFileWriteForExactExpectation(TaskContract contract) {
        return contract != null && !TaskExpectationResolver.resolve(contract).isEmpty();
    }

    private static Plan select(ToolRegistry registry, java.util.function.Predicate<ToolDescriptor> predicate,
                               String reason) {
        List<ToolSpec> specs = registry.descriptors().stream()
                .filter(predicate)
                .map(ToolSurfacePlanner::toSpec)
                .toList();
        return new Plan(specs, reason);
    }

    private static boolean isReadOnlyOperation(ToolDescriptor descriptor) {
        ToolOperationMetadata metadata = metadata(descriptor);
        return metadata != null
                && metadata.riskLevel() != null
                && !metadata.riskLevel().requiresApproval()
                && !metadata.mutatesWorkspace()
                && !metadata.destructive();
    }

    private static boolean isApplyOperation(ToolDescriptor descriptor) {
        ToolOperationMetadata metadata = metadata(descriptor);
        if (metadata == null) return false;
        if (isReadOnlyOperation(descriptor)) return true;
        if (isCommandOperation(descriptor)) return true;
        return metadata.mutatesWorkspace()
                && !metadata.destructive()
                && metadata.riskLevel() == ToolRiskLevel.WRITE;
    }

    private static boolean isVerificationOperation(ToolDescriptor descriptor) {
        return isReadOnlyOperation(descriptor) || isCommandOperation(descriptor);
    }

    private static boolean isCommandOperation(ToolDescriptor descriptor) {
        ToolOperationMetadata metadata = metadata(descriptor);
        return metadata != null
                && metadata.capabilityKind() == CapabilityKind.EXECUTE
                && metadata.riskLevel() == ToolRiskLevel.WRITE
                && metadata.requiresApproval()
                && !metadata.mutatesWorkspace()
                && !metadata.requiresCheckpoint()
                && !metadata.destructive();
    }

    private static boolean verificationCommandSurface(TaskContract contract, ExecutionPhase phase) {
        return contract != null
                && contract.verificationRequired()
                && !contract.mutationAllowed()
                && contract.expectedTargets().isEmpty()
                && phase == ExecutionPhase.VERIFY;
    }

    private static boolean explicitCommandVerificationSurface(TaskContract contract, ExecutionPhase phase) {
        return verificationCommandSurface(contract, phase)
                && "explicit-command-verification-request".equals(contract.classificationReason())
                && explicitCommandProfileRequest(contract);
    }

    private static boolean explicitCommandProfileRequest(TaskContract contract) {
        if (contract == null || contract.originalUserRequest() == null) return false;
        String lower = contract.originalUserRequest().toLowerCase(java.util.Locale.ROOT);
        return lower.contains("talos.run_command")
                || lower.contains("command profile")
                || lower.contains("approved gradle")
                || lower.contains("approved bounded command")
                || lower.contains("profile gradle_");
    }

    private static boolean isDirectoryListingTool(ToolDescriptor descriptor) {
        ToolOperationMetadata metadata = metadata(descriptor);
        if (metadata == null || metadata.capabilityKind() != CapabilityKind.INSPECT) return false;
        return metadata.pathRoles().containsValue(ToolOperationMetadata.PathRole.TARGET_DIRECTORY);
    }

    private static boolean verifyOnlyDirectoryAwarePathCheck(TaskContract contract) {
        if (contract == null || contract.type() != TaskType.VERIFY_ONLY) return false;
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        if (containsExtensionlessSlashPath(request)) return true;
        boolean mentionsDirectory = lower.contains("directory")
                || lower.contains("directories")
                || lower.contains("folder")
                || lower.contains("folders");
        boolean asksPathStatus = lower.contains("exists")
                || lower.contains("exist")
                || lower.contains("present")
                || lower.contains("path");
        return mentionsDirectory && asksPathStatus;
    }

    private static boolean containsExtensionlessSlashPath(String request) {
        if (request == null || request.isBlank()) return false;
        Matcher matcher = SLASH_PATH_CANDIDATE.matcher(request);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate == null || candidate.isBlank()) continue;
            String normalized = trimTrailingPathPunctuation(candidate.replace('\\', '/'));
            int slash = normalized.lastIndexOf('/');
            String last = slash < 0 ? normalized : normalized.substring(slash + 1);
            if (last.isBlank()) continue;
            if (!FILE_EXTENSION.matcher(last).matches()) return true;
        }
        return false;
    }

    private static String trimTrailingPathPunctuation(String value) {
        if (value == null || value.isBlank()) return "";
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?') {
                end--;
                continue;
            }
            break;
        }
        return value.substring(0, end);
    }

    private static boolean isFileReadTool(ToolDescriptor descriptor) {
        ToolOperationMetadata metadata = metadata(descriptor);
        if (metadata == null || metadata.capabilityKind() != CapabilityKind.INSPECT) return false;
        return metadata.pathRoles().containsValue(ToolOperationMetadata.PathRole.TARGET_FILE);
    }

    private static ToolOperationMetadata metadata(ToolDescriptor descriptor) {
        if (descriptor == null) return null;
        ToolOperationMetadata metadata = descriptor.operationMetadata();
        if (metadata != null) return metadata;
        ToolRiskLevel risk = descriptor.riskLevel() == null
                ? ToolRiskLevel.READ_ONLY
                : descriptor.riskLevel();
        return ToolOperationMetadata.defaultFor(descriptor.name(), risk);
    }

    private static ToolSpec toSpec(ToolDescriptor descriptor) {
        return new ToolSpec(
                descriptor.name(),
                descriptor.description(),
                descriptor.parametersSchema());
    }

    public record Plan(List<ToolSpec> nativeToolSpecs, String reason) {
        public Plan {
            nativeToolSpecs = List.copyOf(nativeToolSpecs == null ? List.of() : nativeToolSpecs);
            reason = reason == null ? "" : reason;
        }

        public List<String> nativeToolNames() {
            return names(nativeToolSpecs);
        }
    }
}
