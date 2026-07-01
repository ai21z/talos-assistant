package dev.talos.tools;

import dev.talos.core.capability.CapabilityKind;

import java.util.Map;
import java.util.Objects;

/**
 * Runtime-facing metadata for one tool operation.
 *
 * <p>This record is intentionally descriptive only. It does not change tool
 * execution by itself; later planners and policies can consume it to decide
 * tool exposure, approval, checkpoints, verification, and trace behavior.
 */
public record ToolOperationMetadata(
        String toolName,
        CapabilityKind capabilityKind,
        ToolRiskLevel riskLevel,
        Map<String, PathRole> pathRoles,
        boolean mutatesWorkspace,
        boolean canAffectMultiplePaths,
        boolean requiresApproval,
        boolean requiresCheckpoint,
        boolean destructive,
        boolean supportsDryRun,
        String traceEventKind,
        String verifierHookId
) {
    public ToolOperationMetadata {
        Objects.requireNonNull(toolName, "toolName must not be null");
        capabilityKind = capabilityKind == null ? CapabilityKind.INSPECT : capabilityKind;
        riskLevel = riskLevel == null ? ToolRiskLevel.READ_ONLY : riskLevel;
        pathRoles = Map.copyOf(pathRoles == null ? Map.of() : pathRoles);
        traceEventKind = normalizeId(traceEventKind, "TOOL_EXECUTED");
        verifierHookId = normalizeId(verifierHookId, "");
    }

    public static ToolOperationMetadata defaultFor(String toolName, ToolRiskLevel riskLevel) {
        ToolRiskLevel risk = riskLevel == null ? ToolRiskLevel.READ_ONLY : riskLevel;
        CapabilityKind kind = switch (risk) {
            case READ_ONLY -> CapabilityKind.INSPECT;
            case WRITE -> CapabilityKind.EDIT;
            case DESTRUCTIVE -> CapabilityKind.DELETE;
        };
        boolean mutates = risk != ToolRiskLevel.READ_ONLY;
        return new ToolOperationMetadata(
                toolName,
                kind,
                risk,
                Map.of(),
                mutates,
                false,
                risk.requiresApproval(),
                mutates,
                risk == ToolRiskLevel.DESTRUCTIVE,
                false,
                "TOOL_EXECUTED",
                "");
    }

    public static ToolOperationMetadata inspect(
            String toolName,
            Map<String, PathRole> pathRoles,
            String traceEventKind) {
        return new ToolOperationMetadata(
                toolName,
                CapabilityKind.INSPECT,
                ToolRiskLevel.READ_ONLY,
                pathRoles,
                false,
                false,
                false,
                false,
                false,
                false,
                traceEventKind,
                "");
    }

    public static ToolOperationMetadata retrieve(String toolName, String traceEventKind) {
        return new ToolOperationMetadata(
                toolName,
                CapabilityKind.RETRIEVE,
                ToolRiskLevel.READ_ONLY,
                Map.of(),
                false,
                false,
                false,
                false,
                false,
                false,
                traceEventKind,
                "");
    }

    public static ToolOperationMetadata workspaceMutation(
            String toolName,
            CapabilityKind capabilityKind,
            ToolRiskLevel riskLevel,
            Map<String, PathRole> pathRoles,
            boolean canAffectMultiplePaths,
            boolean requiresCheckpoint,
            String traceEventKind,
            String verifierHookId) {
        ToolRiskLevel risk = riskLevel == null ? ToolRiskLevel.WRITE : riskLevel;
        return new ToolOperationMetadata(
                toolName,
                capabilityKind,
                risk,
                pathRoles,
                true,
                canAffectMultiplePaths,
                risk.requiresApproval(),
                requiresCheckpoint,
                risk == ToolRiskLevel.DESTRUCTIVE,
                false,
                traceEventKind,
                verifierHookId);
    }

    public boolean hasVerifierHook() {
        return !verifierHookId.isBlank();
    }

    private static String normalizeId(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.strip();
    }

    public enum PathRole {
        TARGET_FILE,
        TARGET_DIRECTORY,
        TARGET_PATH,
        SOURCE_PATH,
        DESTINATION_PATH
    }
}
