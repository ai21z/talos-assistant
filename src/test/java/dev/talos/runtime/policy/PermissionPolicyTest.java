package dev.talos.runtime.policy;

import dev.talos.core.Config;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.SessionApprovalPolicy;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PermissionPolicyTest {

    @TempDir
    Path workspace;

    @Test
    void denyBeatsAskAndAllow() {
        Config cfg = configWithRules(List.of(
                rule("allow", List.of("talos.write_file"), List.of("WRITE"), List.of("APPLY"), List.of("src/**")),
                rule("ask", List.of("talos.write_file"), List.of("WRITE"), List.of("APPLY"), List.of("src/**")),
                rule("deny", List.of("talos.write_file"), List.of("WRITE"), List.of("APPLY"), List.of("src/blocked.txt"))
        ));
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = policy.decide(request(cfg,
                new ToolCall("talos.write_file", Map.of("path", "src/blocked.txt", "content", "x")),
                ToolRiskLevel.WRITE,
                ExecutionPhase.APPLY));

        assertEquals(PermissionAction.DENY, decision.action());
        assertEquals("CONFIG_DENY", decision.reasonCode());
    }

    @Test
    void askBeatsAllow() {
        Config cfg = configWithRules(List.of(
                rule("allow", List.of("talos.write_file"), List.of("WRITE"), List.of("APPLY"), List.of("src/**")),
                rule("ask", List.of("talos.write_file"), List.of("WRITE"), List.of("APPLY"), List.of("src/review.txt"))
        ));
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = policy.decide(request(cfg,
                new ToolCall("talos.write_file", Map.of("path", "src/review.txt", "content", "x")),
                ToolRiskLevel.WRITE,
                ExecutionPhase.APPLY));

        assertEquals(PermissionAction.ASK, decision.action());
        assertEquals("CONFIG_ASK", decision.reasonCode());
        assertFalse(decision.rememberEligible(), "explicit ask rules should not silently become session-wide allow");
    }

    @Test
    void protectedMutationIsDeniedBeforeApproval() {
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = policy.decide(request(new Config(),
                new ToolCall("talos.write_file", Map.of("path", ".env", "content", "SECRET=1")),
                ToolRiskLevel.WRITE,
                ExecutionPhase.APPLY));

        assertEquals(PermissionAction.DENY, decision.action());
        assertEquals("PROTECTED_PATH_DENY", decision.reasonCode());
        assertFalse(decision.rememberEligible());
        assertTrue(decision.userMessage().contains("protected path"));
    }

    @Test
    void protectedReadFileAsksWithoutRemembering() {
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = policy.decide(request(new Config(),
                new ToolCall("talos.read_file", Map.of("path", ".env")),
                ToolRiskLevel.READ_ONLY,
                ExecutionPhase.INSPECT));

        assertEquals(PermissionAction.ASK, decision.action());
        assertEquals("PROTECTED_PATH_ASK", decision.reasonCode());
        assertFalse(decision.rememberEligible());
    }

    @Test
    void defaultSafeWriteAsksAndCanBeRemembered() {
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = policy.decide(request(new Config(),
                new ToolCall("talos.write_file", Map.of("path", "src/app.js", "content", "x")),
                ToolRiskLevel.WRITE,
                ExecutionPhase.APPLY));

        assertEquals(PermissionAction.ASK, decision.action());
        assertEquals("DEFAULT_WRITE_ASK", decision.reasonCode());
        assertTrue(decision.rememberEligible());
    }

    @Test
    void sessionRememberAllowsOnlySafeInWorkspaceWrites() {
        SessionApprovalPolicy sessionPolicy = new SessionApprovalPolicy();
        sessionPolicy.rememberApproval(workspace,
                new ToolCall("talos.write_file", Map.of("path", "src/first.txt", "content", "x")),
                ToolRiskLevel.WRITE);
        PermissionPolicy policy = new DeclarativePermissionPolicy(sessionPolicy);

        PermissionDecision safe = policy.decide(request(new Config(),
                new ToolCall("talos.write_file", Map.of("path", "src/second.txt", "content", "x")),
                ToolRiskLevel.WRITE,
                ExecutionPhase.APPLY));
        PermissionDecision protectedPath = policy.decide(request(new Config(),
                new ToolCall("talos.write_file", Map.of("path", ".env", "content", "SECRET=1")),
                ToolRiskLevel.WRITE,
                ExecutionPhase.APPLY));

        assertEquals(PermissionAction.ALLOW, safe.action());
        assertEquals("SESSION_REMEMBER_ALLOW", safe.reasonCode());
        assertEquals(PermissionAction.DENY, protectedPath.action());
        assertEquals("PROTECTED_PATH_DENY", protectedPath.reasonCode());
    }

    @Test
    void workspaceEscapeIsDeniedEvenIfConfigAllowsEverything() {
        Config cfg = configWithRules(List.of(
                rule("allow", List.of("talos.write_file"), List.of("WRITE"), List.of("APPLY"), List.of("**/*"))
        ));
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = policy.decide(request(cfg,
                new ToolCall("talos.write_file", Map.of("path", "../outside.txt", "content", "x")),
                ToolRiskLevel.WRITE,
                ExecutionPhase.APPLY));

        assertEquals(PermissionAction.DENY, decision.action());
        assertEquals("WORKSPACE_ESCAPE", decision.reasonCode());
    }

    private PermissionRequest request(Config cfg, ToolCall call, ToolRiskLevel risk, ExecutionPhase phase) {
        return new PermissionRequest(workspace, cfg, call, risk, phase);
    }

    private static Config configWithRules(List<Map<String, Object>> rules) {
        Config config = new Config();
        config.data.put("permissions", Map.of("rules", rules));
        return config;
    }

    private static Map<String, Object> rule(
            String effect,
            List<String> tools,
            List<String> risks,
            List<String> phases,
            List<String> paths
    ) {
        return Map.of(
                "effect", effect,
                "tools", tools,
                "risks", risks,
                "phases", phases,
                "paths", paths,
                "reason", effect + " test rule");
    }
}
