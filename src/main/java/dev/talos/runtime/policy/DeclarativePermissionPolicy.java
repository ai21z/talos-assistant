package dev.talos.runtime.policy;

import dev.talos.runtime.ApprovalPolicy;
import dev.talos.tools.ToolRiskLevel;

import java.util.Objects;

/** Config-backed allow/ask/deny permission policy with session-approval compatibility. */
public final class DeclarativePermissionPolicy implements PermissionPolicy {

    private final ApprovalPolicy sessionApprovalPolicy;

    public DeclarativePermissionPolicy(ApprovalPolicy sessionApprovalPolicy) {
        this.sessionApprovalPolicy = Objects.requireNonNullElse(sessionApprovalPolicy, ApprovalPolicy.ALWAYS_ASK);
    }

    @Override
    public PermissionDecision decide(PermissionRequest request) {
        if (request == null || request.call() == null) {
            return PermissionDecision.deny("INVALID_PERMISSION_REQUEST",
                    "Permission policy denied the tool call because the request was unavailable.",
                    ResourceDecision.noPath());
        }

        java.util.List<ResourceDecision> resources = ProtectedPathPolicy.classifyAll(
                request.workspace(), request.call());
        ResourceDecision resource = primaryResource(resources);
        ToolRiskLevel risk = request.effectiveRisk();

        ResourceDecision workspaceEscape = firstWorkspaceEscape(resources);
        if (workspaceEscape != null) {
            return PermissionDecision.deny("WORKSPACE_ESCAPE",
                    "Permission policy denied the tool call because the target path escapes the workspace.",
                    workspaceEscape);
        }

        ResourceDecision protectedResource = firstProtectedPath(resources);
        if (risk.requiresApproval() && protectedResource != null) {
            return PermissionDecision.deny("PROTECTED_PATH_DENY",
                    "Permission policy denied mutation of protected path `" + protectedResource.relativePath()
                            + "`. No approval was requested and no file was changed.",
                    protectedResource);
        }

        PermissionConfig config = PermissionConfig.from(request.config());
        PermissionDecision explicit = explicitDecision(config, request, resource, PermissionAction.DENY);
        if (explicit != null) return explicit;

        if (!risk.requiresApproval() && protectedResource != null && isSpecificReadTool(request.call().toolName())) {
            return PermissionDecision.ask("PROTECTED_PATH_ASK",
                    "Permission policy requires approval before reading protected path `"
                            + protectedResource.relativePath() + "`.",
                    protectedResource,
                    false);
        }

        explicit = explicitDecision(config, request, resource, PermissionAction.ASK);
        if (explicit != null) return explicit;
        explicit = explicitDecision(config, request, resource, PermissionAction.ALLOW);
        if (explicit != null) return explicit;

        if (!risk.requiresApproval()) {
            return PermissionDecision.allow("DEFAULT_READ_ALLOW", resource);
        }

        ApprovalPolicy.Decision sessionDecision = sessionApprovalPolicy.decide(
                request.workspace(), request.call(), risk);
        if (sessionDecision == ApprovalPolicy.Decision.DENY) {
            return PermissionDecision.deny("APPROVAL_POLICY_DENY",
                    "Permission policy denied the tool call through the active approval policy.",
                    resource);
        }
        if (sessionDecision == ApprovalPolicy.Decision.AUTO_APPROVE) {
            return PermissionDecision.allow("SESSION_REMEMBER_ALLOW", resource);
        }

        boolean rememberEligible = risk == ToolRiskLevel.WRITE
                && resource.hasPath()
                && resource.insideWorkspace()
                && !resource.protectedPath();
        String reason = risk == ToolRiskLevel.DESTRUCTIVE
                ? "DEFAULT_DESTRUCTIVE_ASK"
                : "DEFAULT_WRITE_ASK";
        return PermissionDecision.ask(reason,
                "Permission policy requires approval before running " + request.call().toolName() + ".",
                resource,
                rememberEligible);
    }

    private static ResourceDecision primaryResource(java.util.List<ResourceDecision> resources) {
        if (resources == null || resources.isEmpty()) return ResourceDecision.noPath();
        for (ResourceDecision resource : resources) {
            if (resource != null && resource.hasPath()) return resource;
        }
        return resources.get(0) == null ? ResourceDecision.noPath() : resources.get(0);
    }

    private static ResourceDecision firstWorkspaceEscape(java.util.List<ResourceDecision> resources) {
        if (resources == null) return null;
        for (ResourceDecision resource : resources) {
            if (resource != null && resource.workspaceEscape()) return resource;
        }
        return null;
    }

    private static ResourceDecision firstProtectedPath(java.util.List<ResourceDecision> resources) {
        if (resources == null) return null;
        for (ResourceDecision resource : resources) {
            if (resource != null && resource.protectedPath()) return resource;
        }
        return null;
    }

    private static PermissionDecision explicitDecision(
            PermissionConfig config,
            PermissionRequest request,
            ResourceDecision resource,
            PermissionAction action
    ) {
        for (PermissionRule rule : config.rules()) {
            if (rule.action() == action && rule.matches(request, resource)) {
                return switch (action) {
                    case DENY -> PermissionDecision.deny("CONFIG_DENY",
                            "Permission policy denied the tool call: " + rule.reason(), resource);
                    case ASK -> PermissionDecision.ask("CONFIG_ASK",
                            "Permission policy requires approval: " + rule.reason(), resource, false);
                    case ALLOW -> PermissionDecision.allow("CONFIG_ALLOW", resource);
                };
            }
        }
        return null;
    }

    private static boolean isSpecificReadTool(String toolName) {
        if (toolName == null) return false;
        String normalized = toolName.strip().toLowerCase(java.util.Locale.ROOT);
        return "talos.read_file".equals(normalized)
                || "read_file".equals(normalized)
                || "readfile".equals(normalized);
    }
}
