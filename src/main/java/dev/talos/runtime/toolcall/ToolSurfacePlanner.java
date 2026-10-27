package dev.talos.runtime.toolcall;

import dev.talos.core.capability.CapabilityKind;
import dev.talos.runtime.MutationIntent;
import dev.talos.runtime.intent.ConversationEvidenceRequest;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
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
        return plan(contract, phase, registry, ToolCapabilityAvailability.allEnabled());
    }

    public static Plan plan(
            TaskContract contract,
            ExecutionPhase phase,
            ToolRegistry registry,
            ToolCapabilityAvailability availability
    ) {
        return plan(contract, phase, registry, availability, false);
    }

    private static Plan plan(
            TaskContract contract,
            ExecutionPhase phase,
            ToolRegistry registry,
            ToolCapabilityAvailability availability,
            boolean suppressWorkspaceOperationBranch
    ) {
        if (registry == null || registry.isEmpty()) {
            return new Plan(List.of(), "no registry tools");
        }
        ToolCapabilityAvailability safeAvailability =
                availability == null ? ToolCapabilityAvailability.allEnabled() : availability;
        if (dev.talos.runtime.policy.WorkStatusAnswerPolicy.requestFor(contract).isPresent()) {
            return new Plan(List.of(), "retained work status");
        }
        if (contract != null && contract.type() == TaskType.SMALL_TALK) {
            return new Plan(List.of(), "small-talk");
        }
        if (contract != null && ConversationEvidenceRequest.interpret(contract.originalUserRequest()).source()
                != ConversationEvidenceRequest.Source.NORMAL) {
            return new Plan(List.of(), "runtime conversation evidence");
        }
        if (contract != null && contract.type() == TaskType.CHECKPOINT_RESTORE) {
            return new Plan(List.of(), "checkpoint restore direct answer");
        }
        if (sessionUncertaintyRequest(contract)) {
            return new Plan(List.of(), "session-uncertainty direct answer");
        }
        if (unsupportedCommandRequest(contract)) {
            return new Plan(List.of(), "unsupported command request");
        }
        if (ambiguousMutationClarification(contract)) {
            return new Plan(List.of(), "ambiguous mutation clarification");
        }
        if (contract != null && contract.type() == TaskType.DIRECTORY_LISTING) {
            return select(registry, ToolSurfacePlanner::isDirectoryListingTool, safeAvailability, "directory listing");
        }
        if (contract != null
                && !contract.mutationAllowed()
                && verifyOnlyDirectoryAwarePathCheck(contract)) {
            return select(
                    registry,
                    descriptor -> isFileReadTool(descriptor) || isDirectoryListingTool(descriptor),
                    safeAvailability,
                    "verify-only path check with directory targets");
        }
        if (contract != null
                && !contract.mutationAllowed()
                && readOnlyPathExistenceCheck(contract)) {
            return select(
                    registry,
                    descriptor -> isFileReadTool(descriptor) || isDirectoryListingTool(descriptor),
                    safeAvailability,
                    "read-only path existence surface");
        }
        if (contract != null
                && !contract.mutationAllowed()
                && !contract.expectedTargets().isEmpty()) {
            boolean compare = dev.talos.runtime.task.DocumentComparisonRequest.from(contract).active();
            return select(registry, descriptor -> isFileReadTool(descriptor)
                    || compare && DocumentComparisonTool.NAME.equals(descriptor.name()), safeAvailability, "expected target read");
        }

        if (dev.talos.runtime.task.DocumentComparisonRequest.from(contract).active())
            return select(registry, descriptor -> isFileReadTool(descriptor)
                    || DocumentComparisonTool.NAME.equals(descriptor.name()), safeAvailability, "requested document comparison");

        boolean mutationAllowed = contract != null
                && contract.mutationAllowed()
                && phase == ExecutionPhase.APPLY;

        if (mutationAllowed) {
            if (explicitTextFormatRequest(contract)) {
                return select(
                        registry,
                        descriptor -> isFileReadTool(descriptor)
                                || "talos.write_file".equals(descriptor.name()),
                        safeAvailability,
                        "text format apply surface");
            }
            if (sourceDerivedFileCreateTargets(contract)) {
                return select(
                        registry,
                        ToolSurfacePlanner::isFileTargetFullWriteApplyOperation,
                        safeAvailability,
                        "source-derived file creation apply surface");
            }
            if (staticWebFullFileApplyTargets(contract)) {
                return select(
                        registry,
                        ToolSurfacePlanner::isFileTargetFullWriteApplyOperation,
                        safeAvailability,
                        "static web full-file apply surface");
            }
            if (fileEditTargets(contract)) {
                return select(
                        registry,
                        ToolSurfacePlanner::isFileTargetApplyOperation,
                        safeAvailability,
                        "file edit target apply surface");
            }
            if (exactStaticWebFileTargets(contract)) {
                return select(
                        registry,
                        ToolSurfacePlanner::isFileTargetApplyOperation,
                        safeAvailability,
                        "static web file target apply surface");
            }
            return select(
                    registry,
                    ToolSurfacePlanner::isLegacyApplyOperation,
                    safeAvailability,
                    "typed-authority-free mutation apply surface");
        }
        if (explicitCommandVerificationSurface(contract, phase)) {
            return select(registry, ToolSurfacePlanner::isCommandOperation, safeAvailability, "explicit command profile surface");
        }
        if (verificationCommandSurface(contract, phase)) {
            return select(registry, ToolSurfacePlanner::isVerificationOperation, safeAvailability, "verification command surface");
        }
        return select(registry, ToolSurfacePlanner::isReadOnlyOperation, safeAvailability, "read-only metadata surface");
    }

    /**
     * Plans from the authoritative typed resolution when one is available.
     *
     * <p>Until the dedicated workspace-operation planner owns coordinated
     * source/destination clauses, an explicitly blocked tail must not be
     * advertised merely because legacy request prose resembles an operation.
     */
    public static Plan plan(
            TaskResolution resolution,
            ExecutionPhase phase,
            ToolRegistry registry,
            ToolCapabilityAvailability availability
    ) {
        TaskResolution safeResolution = resolution == null
                ? TaskResolution.contractOnly(TaskContract.unknown(""))
                : resolution;
        if (safeResolution.blocksWorkspaceOperations()
                && safeResolution.blocksUnscopedMutation()) {
            return new Plan(
                    List.of(),
                    "coordinated workspace-operation tail blocked without "
                            + "authorized content mutation");
        }
        if (safeResolution.blocksUnscopedMutation()) {
            return select(
                    registry,
                    ToolSurfacePlanner::isReadOnlyOperation,
                    availability,
                    "non-authoritative mutation tail read-only surface");
        }
        if (phase == ExecutionPhase.APPLY
                && !safeResolution.workspaceOperationPlan()
                .authorizedOperations().isEmpty()) {
            List<String> operationTools = safeResolution
                    .workspaceOperationPlan()
                    .toolNames();
            Plan workspacePlan = select(
                    registry,
                    descriptor -> operationTools.contains(descriptor.name()),
                    availability,
                    "exact typed workspace-operation authority");
            if (!safeResolution.hasIndependentAuthorizedContentMutation()) {
                return workspacePlan;
            }
            Plan contentPlan = plan(
                    safeResolution.contract(),
                    phase,
                    registry,
                    availability,
                    true);
            java.util.LinkedHashMap<String, ToolSpec> combined =
                    new java.util.LinkedHashMap<>();
            contentPlan.nativeToolSpecs().forEach(spec ->
                    combined.put(spec.name(), spec));
            workspacePlan.nativeToolSpecs().forEach(spec ->
                    combined.put(spec.name(), spec));
            return new Plan(
                    List.copyOf(combined.values()),
                    contentPlan.reason()
                            + "; exact typed workspace-operation authority");
        }
        Plan base = plan(
                safeResolution.contract(),
                phase,
                registry,
                availability,
                safeResolution.blocksWorkspaceOperations());
        if (!safeResolution.blocksWorkspaceOperations()) return base;
        return new Plan(
                base.nativeToolSpecs().stream()
                        .filter(spec -> !WorkspaceOperationPlanner
                                .isWorkspaceOperationTool(spec.name()))
                        .toList(),
                base.reason() + "; coordinated workspace-operation tail blocked");
    }

    /**
     * Default visible tool names for callers without a live registry
     * (capability-frame fallbacks, prompt inspector, traces).
     *
     * <p>T761: derived from {@link #plan} over the canonical descriptor
     * catalog - the production-enforced surface IS the advertised surface,
     * by construction. A ~46-line hand-maintained copy of the plan branches
     * previously lived here and had drifted (the expected-target-read branch
     * had no counterpart, so read-only turns with expected targets advertised
     * four read tools while the runtime allowed only talos.read_file).
     *
     * <p>Intentional asymmetry kept from the old code: a null contract
     * yields an EMPTY default frame, while {@code plan(null, ...)} returns
     * the read-only surface for runtime enforcement.
     */
    public static List<String> defaultVisibleToolNames(TaskContract contract, ExecutionPhase phase) {
        if (contract == null) return List.of();
        return plan(contract, phase, CanonicalToolDescriptors.registry()).nativeToolNames();
    }

    public static List<String> defaultVisibleToolNamesForResolution(
            TaskResolution resolution,
            ExecutionPhase phase
    ) {
        if (resolution == null) return List.of();
        return plan(
                resolution,
                phase,
                CanonicalToolDescriptors.registry(),
                ToolCapabilityAvailability.allEnabled()).nativeToolNames();
    }

    public static List<String> names(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) return List.of();
        return specs.stream()
                .map(ToolSpec::name)
                .sorted()
                .toList();
    }

    private static boolean ambiguousMutationClarification(TaskContract contract) {
        return contract != null
                && contract.type() == TaskType.READ_ONLY_QA
                && MutationIntent.classifyShape(contract.originalUserRequest()).decision()
                == MutationIntent.Decision.AMBIGUOUS;
    }

    private static boolean requiresFileWriteForExactExpectation(TaskContract contract) {
        return contract != null && !TaskExpectationResolver.resolve(contract).isEmpty();
    }

    private static boolean fileEditTargets(TaskContract contract) {
        if (contract == null || contract.type() != TaskType.FILE_EDIT || contract.expectedTargets().isEmpty()) {
            return false;
        }
        for (String target : contract.expectedTargets()) {
            if (target == null || !FILE_EXTENSION.matcher(target).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean explicitTextFormatRequest(TaskContract contract) {
        return contract != null
                && "explicit-text-format-request".equals(contract.classificationReason());
    }

    private static boolean sourceDerivedFileCreateTargets(TaskContract contract) {
        if (contract == null
                || contract.type() != TaskType.FILE_CREATE
                || contract.expectedTargets().isEmpty()
                || contract.sourceEvidenceTargets().isEmpty()) {
            return false;
        }
        for (String target : contract.expectedTargets()) {
            if (target == null || !FILE_EXTENSION.matcher(target).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean exactStaticWebFileTargets(TaskContract contract) {
        if (contract == null || contract.expectedTargets().isEmpty()) return false;
        boolean hasHtml = false;
        for (String target : contract.expectedTargets()) {
            if (!StaticWebCapabilityProfile.isSmallWebFile(target)) return false;
            String lower = target == null ? "" : target.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                hasHtml = true;
            }
        }
        return hasHtml;
    }

    private static boolean staticWebFullFileApplyTargets(TaskContract contract) {
        return exactStaticWebFileTargets(contract)
                && StaticWebCapabilityProfile.prefersFullFileWriteForInitialApply(contract);
    }

    private static Plan select(
            ToolRegistry registry,
            java.util.function.Predicate<ToolDescriptor> predicate,
            ToolCapabilityAvailability availability,
            String reason
    ) {
        ToolCapabilityAvailability safeAvailability =
                availability == null ? ToolCapabilityAvailability.allEnabled() : availability;
        List<ToolSpec> specs = registry.descriptors().stream()
                .filter(predicate)
                .filter(descriptor -> safeAvailability.allows(metadata(descriptor)))
                .map(ToolSurfacePlanner::toSpec)
                .toList();
        return new Plan(specs, reason);
    }

    private static boolean isReadOnlyOperation(ToolDescriptor descriptor) {
        if (descriptor != null && DocumentComparisonTool.NAME.equals(descriptor.name())) return false;
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
        return metadata.mutatesWorkspace()
                && !metadata.destructive()
                && metadata.riskLevel() == ToolRiskLevel.WRITE;
    }

    private static boolean isLegacyApplyOperation(ToolDescriptor descriptor) {
        return isApplyOperation(descriptor)
                && !WorkspaceOperationPlanner.isWorkspaceOperationTool(
                        descriptor == null ? "" : descriptor.name());
    }

    private static boolean isFileTargetApplyOperation(ToolDescriptor descriptor) {
        if (isReadOnlyOperation(descriptor)) return true;
        String name = descriptor == null ? "" : descriptor.name();
        return "talos.write_file".equals(name) || "talos.edit_file".equals(name);
    }

    private static boolean isFileTargetFullWriteApplyOperation(ToolDescriptor descriptor) {
        if (isReadOnlyOperation(descriptor)) return true;
        String name = descriptor == null ? "" : descriptor.name();
        return "talos.write_file".equals(name);
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

    private static boolean unsupportedCommandRequest(TaskContract contract) {
        return contract != null
                && "unsupported-command-verification-request".equals(contract.classificationReason());
    }

    private static boolean sessionUncertaintyRequest(TaskContract contract) {
        return contract != null
                && "session-uncertainty-question".equals(contract.classificationReason());
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

    private static boolean readOnlyPathExistenceCheck(TaskContract contract) {
        if (contract == null || contract.mutationAllowed() || contract.expectedTargets().isEmpty()) {
            return false;
        }
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        boolean asksExistence = lower.contains("exists")
                || lower.contains("exist")
                || lower.contains("present")
                || lower.contains("is there")
                || lower.contains("are there");
        boolean asksPathStatus = lower.contains("path")
                && (lower.contains("check") || lower.contains("verify") || lower.contains("whether"));
        return asksExistence || asksPathStatus;
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
