package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionApprovalPolicy;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.ActiveToolAuthorityTraceTest;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real preparation and file-tool admission with saved runtime findings and synthetic proposals. */
class AssistantTurnRepairAuthorityTest extends ActiveToolAuthorityTraceTest {
    private static final String NO_CHANGE = "Explain the remaining issue. Do not change files.";
    private static final String REPAIR = "Fix the remaining issue.";
    private static final String ORIGINAL = "const ready = false;\n";
    private static final String REPAIRED = "const ready = true;\n";

    @TempDir Path workspace;

    enum Permission { ASK, CONFIG_ALLOW, REMEMBERED_ALLOW }

    @AfterEach
    void clearCaptures() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        PromptDebugCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @ParameterizedTest
    @EnumSource(Permission.class)
    void currentProhibitionSurvivesPreparationAndBlocksRealWriteBeforeApproval(Permission permission)
            throws Exception {
        Fixture fixture = fixture(permission, 3);
        var prepared = prepare(NO_CHANGE, fixture.context(), CapabilityPosture.AGENT);
        capture(NO_CHANGE, prepared);

        ToolResult result = fixture.processor().executeTool(fixture.session(), write(), prepared.ctx());

        assertAll(
                () -> assertFalse(prepared.taskResolution().contract().mutationAllowed()),
                () -> assertFalse(prepared.taskResolution().contract().mutationRequested()),
                () -> assertEquals(ExecutionPhase.INSPECT, prepared.plan().phaseInitial()),
                () -> assertFalse(prepared.plan().promptTools().contains("talos.write_file")),
                () -> assertFalse(prepared.plan().nativeTools().contains("talos.write_file")),
                () -> assertFalse(result.success(), result.toString()),
                () -> assertEquals(0, fixture.approvals().get()),
                () -> assertEquals(ORIGINAL, Files.readString(workspace.resolve("scripts.js"))));
    }

    @ParameterizedTest
    @EnumSource(Permission.class)
    void explicitCurrentProhibitionAlsoBlocksAnIndependentlyPromotedCapturedContract(Permission permission)
            throws Exception {
        Fixture fixture = fixture(permission, 3);
        // A valid prior repair resolution deliberately simulates stale or over-promoted authority.
        // The admission invariant must hold independently of preparation and tool-surface filtering.
        var repair = prepare(REPAIR, fixture.context(), CapabilityPosture.AGENT);
        assertTrue(repair.taskResolution().contract().mutationAllowed());
        TurnTaskContractCapture.set(repair.taskResolution());
        TurnUserRequestCapture.set(NO_CHANGE);

        ToolResult result = fixture.processor().executeTool(fixture.session(), write(), repair.ctx());

        assertAll(
                () -> assertFalse(result.success(), result.toString()),
                () -> assertEquals(ToolError.DENIED, result.error() == null ? "" : result.error().code()),
                () -> assertEquals(0, fixture.approvals().get()),
                () -> assertEquals(ORIGINAL, Files.readString(workspace.resolve("scripts.js"))));
    }

    @ParameterizedTest
    @EnumSource(Permission.class)
    void explicitRepairStillConsumesSavedTargetsAndExecutesApprovedWrite(Permission permission)
            throws Exception {
        Fixture fixture = fixture(permission, 3);
        var prepared = prepare(REPAIR, fixture.context(), CapabilityPosture.AGENT);
        capture(REPAIR, prepared);

        ToolResult result = fixture.processor().executeTool(fixture.session(), write(), prepared.ctx());

        assertTrue(prepared.taskResolution().contract().mutationAllowed());
        assertTrue(prepared.taskResolution().contract().expectedTargets().contains("scripts.js"));
        assertTrue(result.success(), result.errorMessage());
        assertEquals(permission == Permission.ASK ? 1 : 0, fixture.approvals().get());
        assertEquals(REPAIRED, Files.readString(workspace.resolve("scripts.js")));
    }

    @Test
    void ordinaryReadWithNoChangeStillExecutes() throws Exception {
        Fixture fixture = fixture(Permission.CONFIG_ALLOW, 3);
        String request = "Read scripts.js and explain the remaining issue. Do not change files.";
        var prepared = prepare(request, fixture.context(), CapabilityPosture.AGENT);
        capture(request, prepared);

        ToolResult result = fixture.processor().executeTool(fixture.session(),
                new ToolCall("talos.read_file", Map.of("path", "scripts.js")), prepared.ctx());

        assertTrue(result.success(), result.errorMessage());
        assertFalse(prepared.taskResolution().contract().mutationAllowed());
        assertEquals(0, fixture.approvals().get());
        assertEquals(ORIGINAL, Files.readString(workspace.resolve("scripts.js")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Explain the remaining issue. Do not change files. Run the tests.",
            "Explain the remaining issue. Do not change files. Run the Gradle tests with profile gradle_test."
    })
    void commandClassificationCannotHideExplicitCurrentProhibition(String request) throws Exception {
        Fixture fixture = fixture(Permission.CONFIG_ALLOW, 3);
        var canonical = TaskContractResolver.fromUserRequest(request);
        assertFalse(canonical.mutationAllowed());
        assertNotEquals("global-read-only-negation", canonical.classificationReason(),
                "The fixture must exercise a reason overridden by command classification");
        var prepared = prepare(request, fixture.context(), CapabilityPosture.AGENT);
        capture(request, prepared);

        ToolResult result = fixture.processor().executeTool(fixture.session(), write(), prepared.ctx());

        assertAll(
                () -> assertFalse(prepared.taskResolution().contract().mutationAllowed()),
                () -> assertFalse(result.success(), result.toString()),
                () -> assertEquals(0, fixture.approvals().get()),
                () -> assertEquals(ORIGINAL, Files.readString(workspace.resolve("scripts.js"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Edit index.html so it works, but do not create a directory named generated.",
            "Edit index.html so it works, but do not copy styles.css to scripts.js."
    })
    void targetLocalProhibitionKeepsExplicitPrimaryEditExecutable(String request) throws Exception {
        Fixture fixture = fixture(Permission.ASK, 3);
        var prepared = prepare(request, fixture.context(), CapabilityPosture.AGENT);
        capture(request, prepared);
        String updated = "<html><body>Working example</body></html>\n";

        assertTrue(prepared.taskResolution().contract().mutationAllowed(), request);
        assertTrue(prepared.taskResolution().contract().expectedTargets().contains("index.html"), request);
        assertTrue(prepared.taskResolution().blocksWorkspaceOperations(), request);
        ToolResult result = fixture.processor().executeTool(fixture.session(),
                new ToolCall("talos.write_file", Map.of("path", "index.html", "content", updated)),
                prepared.ctx());

        assertTrue(result.success(), () -> request + ": " + result.errorMessage());
        assertEquals(1, fixture.approvals().get());
        assertEquals(updated, Files.readString(workspace.resolve("index.html")));
        assertEquals(ORIGINAL, Files.readString(workspace.resolve("scripts.js")));
        assertFalse(Files.exists(workspace.resolve("generated")));
    }

    @Test
    void planPostureStillBlocksExplicitRepair() throws Exception {
        Fixture fixture = fixture(Permission.CONFIG_ALLOW, 3);
        var prepared = prepare(REPAIR, fixture.context(), CapabilityPosture.PLAN_READ_ONLY);
        capture(REPAIR, prepared);

        ToolResult result = fixture.processor().executeTool(fixture.session(), write(), prepared.ctx());

        assertFalse(prepared.taskResolution().contract().mutationAllowed());
        assertFalse(result.success());
        assertEquals(0, fixture.approvals().get());
        assertEquals(ORIGINAL, Files.readString(workspace.resolve("scripts.js")));
    }

    @Test
    void expiredVerifierContextCannotSupplyTargets() throws Exception {
        Fixture fixture = fixture(Permission.CONFIG_ALLOW, 6);
        var prepared = prepare(NO_CHANGE, fixture.context(), CapabilityPosture.AGENT);
        capture(NO_CHANGE, prepared);

        ToolResult result = fixture.processor().executeTool(fixture.session(), write(), prepared.ctx());

        assertTrue(prepared.taskResolution().contract().expectedTargets().isEmpty());
        assertFalse(prepared.taskResolution().contract().mutationAllowed());
        assertEquals(ActiveTaskContext.State.NONE, fixture.context().memory().activeTaskContext().state());
        assertFalse(result.success());
        assertEquals(0, fixture.approvals().get());
        assertEquals(ORIGINAL, Files.readString(workspace.resolve("scripts.js")));
    }

    @Test
    void explicitDifferentTargetCannotBorrowSavedTargets() throws Exception {
        Fixture fixture = fixture(Permission.CONFIG_ALLOW, 3);
        var prepared = prepare("Read other.txt and explain the remaining issue. Do not change files.",
                fixture.context(), CapabilityPosture.AGENT);

        assertFalse(prepared.taskResolution().contract().expectedTargets().contains("scripts.js"));
        assertFalse(prepared.taskResolution().contract().mutationAllowed());
        assertEquals(ActiveTaskContext.State.NONE, fixture.context().memory().activeTaskContext().state());
    }

    private AssistantTurnPreparation.PreparedTurn prepare(String request, Context context,
                                                          CapabilityPosture posture) {
        return AssistantTurnPreparation.prepare(new ArrayList<>(List.of(
                ChatMessage.system("You are Talos."), ChatMessage.user(request))),
                workspace, context, false, posture);
    }

    private static void capture(String request, AssistantTurnPreparation.PreparedTurn prepared) {
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(prepared.taskResolution());
    }

    private Fixture fixture(Permission permission, int currentTurn) throws Exception {
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.begin("trace-repair-authority-" + currentTurn,
                "session-repair-authority", currentTurn, "2026-07-20T00:00:00Z",
                "workspace-repair-authority", "test", "test", "test",
                "repair authority at the fixture current turn");
        Files.writeString(workspace.resolve("scripts.js"), ORIGINAL);
        Files.writeString(workspace.resolve("index.html"), "<html><body>Example</body></html>\n");
        Files.writeString(workspace.resolve("styles.css"), "body { color: black; }\n");
        Config config = new Config(null);
        config.data.put("permissions", Map.of("rules", permission == Permission.CONFIG_ALLOW
                ? List.of(Map.of("effect", "allow", "tools", List.of("talos.write_file"),
                        "paths", List.of("scripts.js")))
                : List.of()));
        SessionMemory memory = new SessionMemory();
        for (int turn = 1; turn < currentTurn; turn++) memory.update("Earlier request " + turn, "Earlier answer.");
        ActiveTaskContext saved = ActiveTaskContext.verifierFindings(2, "trace-verifier-findings",
                List.of("index.html", "styles.css", "scripts.js"),
                List.of("scripts.js: the ready flag is still false"), "FAILED");
        memory.setActiveTaskContext(saved);
        memory.setArtifactGoal(ArtifactGoal.fromActiveContext(saved));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        registry.register(new ReadFileTool());
        SessionApprovalPolicy policy = new SessionApprovalPolicy();
        if (permission == Permission.REMEMBERED_ALLOW) {
            policy.rememberApproval(workspace, write(), ToolRiskLevel.WRITE);
            assertEquals(ApprovalPolicy.Decision.AUTO_APPROVE,
                    policy.decide(workspace, write(), ToolRiskLevel.WRITE));
        }
        AtomicInteger approvals = new AtomicInteger();
        TurnProcessor processor = new TurnProcessor(null, (description, detail) -> {
            approvals.incrementAndGet();
            return true;
        }, registry, policy);
        Context context = Context.builder(config).memory(memory)
                .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).build();
        return new Fixture(processor, new Session(workspace, config, memory), context, approvals);
    }

    private static ToolCall write() {
        return new ToolCall("talos.write_file", Map.of("path", "scripts.js", "content", REPAIRED));
    }

    private record Fixture(TurnProcessor processor, Session session, Context context,
                           AtomicInteger approvals) {}
}
