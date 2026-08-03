package dev.talos.runtime.policy;

import dev.talos.core.Config;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.SessionApprovalPolicy;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class PermissionPolicyTest {

    @TempDir
    Path workspace;

    @AfterEach
    void clearCurrentTurnAuthority() {
        TurnTaskContractCapture.clear();
    }

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

        PermissionDecision decision = policy.decide(request(new Config(null),
                new ToolCall("talos.read_file", Map.of("path", ".env")),
                ToolRiskLevel.READ_ONLY,
                ExecutionPhase.INSPECT));

        assertEquals(PermissionAction.ASK, decision.action());
        assertEquals("PROTECTED_PATH_ASK", decision.reasonCode());
        assertFalse(decision.rememberEligible());
    }

    @Test
    void protectedReadAliasCarriesTargetTruthfulProtectedKind() {
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = policy.decide(request(new Config(null),
                new ToolCall("talos.read_file", Map.of("path", "SSH~1/id_rsa")),
                ToolRiskLevel.READ_ONLY,
                ExecutionPhase.INSPECT));

        assertEquals(PermissionAction.ASK, decision.action());
        assertEquals("PROTECTED_PATH_ASK", decision.reasonCode());
        assertTrue(decision.protectedPath());
        assertEquals("SECRET", decision.protectedKind());
        assertTrue(decision.userMessage().contains("protected path"));
        assertTrue(decision.userMessage().contains("SSH~1/id_rsa"));
        assertTrue(decision.userMessage().contains("(SECRET)"));
    }

    @Test
    void explicitDenyRuleBeatsProtectedReadAsk() {
        Config cfg = configWithRules(List.of(
                rule("deny", List.of("talos.read_file"), List.of("READ_ONLY"), List.of("INSPECT"), List.of(".env"))
        ));
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = policy.decide(request(cfg,
                new ToolCall("talos.read_file", Map.of("path", ".env")),
                ToolRiskLevel.READ_ONLY,
                ExecutionPhase.INSPECT));

        assertEquals(PermissionAction.DENY, decision.action());
        assertEquals("CONFIG_DENY", decision.reasonCode());
        assertTrue(decision.userMessage().contains("deny test rule"));
    }

    @Test
    void explicitDenyRuleMatchesCanonicalReadAlias() {
        Config cfg = configWithRules(List.of(
                rule("deny", List.of("talos.read_file"), List.of("READ_ONLY"), List.of("INSPECT"), List.of("README.md"))
        ));
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = policy.decide(request(cfg,
                new ToolCall("file_read", Map.of("path", "README.md")),
                ToolRiskLevel.READ_ONLY,
                ExecutionPhase.INSPECT));

        assertEquals(PermissionAction.DENY, decision.action());
        assertEquals("CONFIG_DENY", decision.reasonCode());
    }

    @Test
    void protectedReadAliasAsksWithoutRemembering() {
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = policy.decide(request(new Config(null),
                new ToolCall("file_read", Map.of("path", ".env")),
                ToolRiskLevel.READ_ONLY,
                ExecutionPhase.INSPECT));

        assertEquals(PermissionAction.ASK, decision.action());
        assertEquals("PROTECTED_PATH_ASK", decision.reasonCode());
        assertFalse(decision.rememberEligible());
    }

    @Test
    void explicitDenyRuleAppliesToCopyDestinationResource() {
        Config cfg = configWithRules(List.of(
                rule("deny", List.of("talos.copy_path"), List.of("WRITE"), List.of("APPLY"), List.of("blocked/**"))
        ));
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = withWorkspaceAuthority(
                "Copy README.md to blocked/copy.md.",
                () -> policy.decide(request(cfg,
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "README.md",
                                "to", "blocked/copy.md")),
                        ToolRiskLevel.WRITE,
                        ExecutionPhase.APPLY)));

        assertEquals(PermissionAction.DENY, decision.action());
        assertEquals("CONFIG_DENY", decision.reasonCode());
        assertEquals("blocked/copy.md", decision.relativePath());
    }

    @Test
    void permissionRulesClassifyBatchArgumentsWithoutCreatingTypedAuthority() {
        Config cfg = configWithRules(List.of(
                rule("allow", List.of("talos.apply_workspace_batch"), List.of("WRITE"), List.of("APPLY"),
                        List.of("allowed/**"))
        ));
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        ToolCall call = new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                        [
                          {"op":"mkdir","path":"allowed/one"},
                          {"op":"mkdir","path":"outside/two"}
                        ]
                        """));
        TurnTaskContractCapture.clear();
        assertEquals(
                TurnTaskContractCapture.WorkspaceAuthorization.NO_TYPED_AUTHORITY,
                TurnTaskContractCapture.authorizeWorkspaceOperation(call));
        PermissionDecision decision = policy.decide(request(cfg,
                call,
                ToolRiskLevel.WRITE,
                ExecutionPhase.APPLY));

        assertEquals(PermissionAction.ASK, decision.action());
        assertEquals("DEFAULT_WRITE_ASK", decision.reasonCode());
        assertEquals("allowed/one", decision.relativePath());
    }

    @Test
    void configAllowClassifiesCoveredBatchWithoutCreatingTypedAuthority() {
        Config cfg = configWithRules(List.of(
                rule("allow", List.of("talos.apply_workspace_batch"), List.of("WRITE"), List.of("APPLY"),
                        List.of("allowed/**"))
        ));
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        ToolCall call = new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                        [
                          {"op":"mkdir","path":"allowed/one"},
                          {"op":"mkdir","path":"allowed/two"}
                        ]
                        """));
        TurnTaskContractCapture.clear();
        assertEquals(
                TurnTaskContractCapture.WorkspaceAuthorization.NO_TYPED_AUTHORITY,
                TurnTaskContractCapture.authorizeWorkspaceOperation(call));
        PermissionDecision decision = policy.decide(request(cfg,
                call,
                ToolRiskLevel.WRITE,
                ExecutionPhase.APPLY));

        assertEquals(PermissionAction.ALLOW, decision.action());
        assertEquals("CONFIG_ALLOW", decision.reasonCode());
        assertEquals("allowed/one", decision.relativePath());
    }

    @Test
    void explicitAllowForCopyRequiresDestinationCoverageButNotSourceCoverage() {
        Config cfg = configWithRules(List.of(
                rule("allow", List.of("talos.copy_path"), List.of("WRITE"), List.of("APPLY"), List.of("allowed/**"))
        ));
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = withWorkspaceAuthority(
                "Copy README.md to allowed/README.md.",
                () -> policy.decide(request(cfg,
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "README.md",
                                "to", "allowed/README.md")),
                        ToolRiskLevel.WRITE,
                        ExecutionPhase.APPLY)));

        assertEquals(PermissionAction.ALLOW, decision.action());
        assertEquals("CONFIG_ALLOW", decision.reasonCode());
        assertEquals("allowed/README.md", decision.relativePath());
    }

    @Test
    void moveAndRenameRequireAllowCoverageForBothMutatedPaths() {
        List<PartialWorkspaceAllowFixture> fixtures = List.of(
                new PartialWorkspaceAllowFixture(
                        "Move source.txt to allowed/moved.txt.",
                        new ToolCall("talos.move_path", Map.of(
                                "from", "source.txt",
                                "to", "allowed/moved.txt")),
                        List.of("source.txt"),
                        List.of("allowed/**")),
                new PartialWorkspaceAllowFixture(
                        "Rename source.txt to renamed.txt.",
                        new ToolCall("talos.rename_path", Map.of(
                                "path", "source.txt",
                                "new_name", "renamed.txt")),
                        List.of("source.txt"),
                        List.of("renamed.txt")));
        PermissionPolicy policy = new DeclarativePermissionPolicy(
                ApprovalPolicy.ALWAYS_ASK);

        for (PartialWorkspaceAllowFixture fixture : fixtures) {
            for (List<String> partialPaths : List.of(
                    fixture.sourceOnlyPaths(),
                    fixture.destinationOnlyPaths())) {
                Config cfg = configWithRules(List.of(rule(
                        "allow",
                        List.of(fixture.call().toolName()),
                        List.of("WRITE"),
                        List.of("APPLY"),
                        partialPaths)));

                PermissionDecision decision = withWorkspaceAuthority(
                        fixture.request(),
                        () -> policy.decide(request(
                                cfg,
                                fixture.call(),
                                ToolRiskLevel.WRITE,
                                ExecutionPhase.APPLY)));

                assertEquals(PermissionAction.ASK, decision.action(),
                        fixture.request() + " paths=" + partialPaths);
                assertEquals("DEFAULT_WRITE_ASK", decision.reasonCode(),
                        fixture.request() + " paths=" + partialPaths);
                assertFalse(decision.rememberEligible(), fixture.request());
            }
        }
    }

    @Test
    void renameComputedProtectedDestinationIsDeniedBeforeApproval() {
        PermissionPolicy policy = new DeclarativePermissionPolicy(ApprovalPolicy.ALWAYS_ASK);

        PermissionDecision decision = withWorkspaceAuthority(
                "Rename notes.txt to .env.",
                () -> policy.decide(request(new Config(),
                        new ToolCall("talos.rename_path", Map.of(
                                "path", "notes.txt",
                                "new_name", ".env")),
                        ToolRiskLevel.WRITE,
                        ExecutionPhase.APPLY)));

        assertEquals(PermissionAction.DENY, decision.action());
        assertEquals("PROTECTED_PATH_DENY", decision.reasonCode());
        assertEquals(".env", decision.relativePath());
        assertTrue(decision.protectedPath());
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

    @Test
    void activeApprovalPolicyDenyOutranksTypedWorkspaceOnceOnlyRule() {
        ApprovalPolicy deny = new ApprovalPolicy() {
            @Override
            public Decision decide(
                    Path ignoredWorkspace,
                    ToolCall ignoredCall,
                    ToolRiskLevel ignoredRisk
            ) {
                return Decision.DENY;
            }

            @Override
            public void rememberApproval(
                    Path ignoredWorkspace,
                    ToolCall ignoredCall,
                    ToolRiskLevel ignoredRisk
            ) {
                throw new AssertionError("denied operations must not be remembered");
            }
        };
        PermissionPolicy policy = new DeclarativePermissionPolicy(deny);
        Map<String, ToolCall> fixtures = Map.of(
                "Copy source.txt to copied.txt.",
                new ToolCall("talos.copy_path", Map.of(
                        "from", "source.txt", "to", "copied.txt")),
                "Create a directory named assets.",
                new ToolCall("talos.mkdir", Map.of("path", "assets")));

        fixtures.forEach((request, call) -> {
            PermissionDecision decision = withWorkspaceAuthority(
                    request,
                    () -> policy.decide(request(
                            new Config(),
                            call,
                            ToolRiskLevel.WRITE,
                            ExecutionPhase.APPLY)));

            assertEquals(PermissionAction.DENY, decision.action(), request);
            assertEquals("APPROVAL_POLICY_DENY", decision.reasonCode(), request);
            assertFalse(decision.rememberEligible(), request);
        });
    }

    private static <T> T withWorkspaceAuthority(
            String request,
            Supplier<T> action
    ) {
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));
        try {
            return action.get();
        } finally {
            TurnTaskContractCapture.clear();
        }
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

    private record PartialWorkspaceAllowFixture(
            String request,
            ToolCall call,
            List<String> sourceOnlyPaths,
            List<String> destinationOnlyPaths
    ) {}
}
