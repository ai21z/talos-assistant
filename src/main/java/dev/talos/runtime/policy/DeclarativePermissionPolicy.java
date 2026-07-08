package dev.talos.runtime.policy;

import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolRiskLevel;

import java.util.LinkedHashMap;
import java.util.List;
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
                    "Permission policy denied mutation of protected path "
                            + protectedPathLabel(protectedResource)
                            + ". No approval was requested and no file was changed.",
                    protectedResource);
        }

        PermissionConfig config = PermissionConfig.from(request.config());
        PermissionDecision explicit = explicitDecision(config, request, resources, PermissionAction.DENY);
        if (explicit != null) return explicit;

        if (!risk.requiresApproval() && protectedResource != null && isSpecificReadTool(request.call().toolName())) {
            return PermissionDecision.ask("PROTECTED_PATH_ASK",
                    "Permission policy requires approval before reading protected path "
                            + protectedPathLabel(protectedResource) + ".",
                    protectedResource,
                    false);
        }

        explicit = explicitDecision(config, request, resources, PermissionAction.ASK);
        if (explicit != null) return explicit;
        explicit = explicitAllowDecision(config, request, resources);
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

    private static String protectedPathLabel(ResourceDecision resource) {
        if (resource == null) return "``";
        String kind = resource.protectedKind() == null || resource.protectedKind().isBlank()
                ? ""
                : " (" + resource.protectedKind() + ")";
        return "`" + resource.relativePath() + "`" + kind;
    }

    private static PermissionDecision explicitDecision(
            PermissionConfig config,
            PermissionRequest request,
            java.util.List<ResourceDecision> resources,
            PermissionAction action
    ) {
        java.util.List<ResourceDecision> candidates =
                resources == null || resources.isEmpty()
                        ? java.util.List.of(ResourceDecision.noPath())
                        : resources;
        for (PermissionRule rule : config.rules()) {
            if (rule.action() != action) continue;
            for (ResourceDecision resource : candidates) {
                if (rule.matches(request, resource)) {
                    return switch (action) {
                        case DENY -> PermissionDecision.deny("CONFIG_DENY",
                                "Permission policy denied the tool call: " + rule.reason(), resource);
                        case ASK -> PermissionDecision.ask("CONFIG_ASK",
                                "Permission policy requires approval: " + rule.reason(), resource, false);
                        case ALLOW -> PermissionDecision.allow("CONFIG_ALLOW", resource);
                    };
                }
            }
        }
        return null;
    }

    private static PermissionDecision explicitAllowDecision(
            PermissionConfig config,
            PermissionRequest request,
            java.util.List<ResourceDecision> resources
    ) {
        java.util.List<ResourceDecision> candidates = allowCoverageCandidates(request, resources);
        if (candidates.isEmpty()) {
            candidates = resources == null || resources.isEmpty()
                    ? java.util.List.of(ResourceDecision.noPath())
                    : resources;
        }
        ResourceDecision firstCovered = null;
        for (ResourceDecision resource : candidates) {
            boolean covered = false;
            for (PermissionRule rule : config.rules()) {
                if (rule.action() != PermissionAction.ALLOW) continue;
                if (rule.matches(request, resource)) {
                    if (firstCovered == null) firstCovered = resource;
                    covered = true;
                    break;
                }
            }
            if (!covered) return null;
        }
        return PermissionDecision.allow(
                "CONFIG_ALLOW",
                firstCovered == null ? ResourceDecision.noPath() : firstCovered);
    }

    private static java.util.List<ResourceDecision> allowCoverageCandidates(
            PermissionRequest request,
            java.util.List<ResourceDecision> fallbackResources
    ) {
        if (request == null || !request.effectiveRisk().requiresApproval()) {
            return fallbackResources == null ? java.util.List.of() : fallbackResources;
        }
        if (request.call() != null && WorkspaceOperationPlanner.isWorkspaceOperationTool(request.call().toolName())) {
            try {
                return WorkspaceOperationPlanner.checkpointPlan(request.call())
                        .map(plan -> classifyUnique(request, plan.checkpointPaths()))
                        .filter(list -> !list.isEmpty())
                        .orElseGet(() -> fallbackResources == null ? java.util.List.of() : fallbackResources);
            } catch (IllegalArgumentException ignored) {
                return fallbackResources == null ? java.util.List.of() : fallbackResources;
            }
        }
        return fallbackResources == null ? java.util.List.of() : fallbackResources;
    }

    private static java.util.List<ResourceDecision> classifyUnique(
            PermissionRequest request,
            java.util.List<String> paths
    ) {
        if (paths == null || paths.isEmpty()) return java.util.List.of();
        LinkedHashMap<String, ResourceDecision> out = new LinkedHashMap<>();
        for (String path : paths) {
            if (path == null || path.isBlank()) continue;
            ResourceDecision resource = ProtectedPathPolicy.classify(request.workspace(), path);
            String key = resource.relativePath().isBlank() ? resource.rawPath() : resource.relativePath();
            out.putIfAbsent(key, resource);
        }
        return List.copyOf(out.values());
    }

    private static boolean isSpecificReadTool(String toolName) {
        if (toolName == null) return false;
        return "read_file".equals(ToolAliasPolicy.localCanonicalName(toolName));
    }
}
