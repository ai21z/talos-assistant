package dev.talos.cli.modes;

import dev.talos.cli.prompt.PromptDebugInspector;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.CurrentTurnCapabilityFrame;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.CanonicalToolDescriptors;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantTurnPreparationTargetRolesTest {
    private static final String TARGET_ROLES_DIAGNOSTIC = "taskContract.targetRoles";

    @AfterEach
    void cleanup() {
        PromptDebugCapture.clear();
    }

    @Test
    void authoritativeEmptyEffectiveRoleSetSurvivesIntoPromptDebug(@TempDir Path workspace) {
        String userRequest = "Create style.css.";
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(userRequest)));
        PromptDebugCapture.beginTurn();

        AssistantTurnPreparation.PreparedTurn prepared =
                AssistantTurnPreparation.prepare(
                        messages,
                        workspace,
                        null,
                        false,
                        CapabilityPosture.PLAN_READ_ONLY);
        assertTrue(prepared.plan().taskContract().expectedTargets().isEmpty());
        assertFalse(prepared.taskResolution().mutationTailPlan().present());

        PromptDebugCapture.record(new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "test",
                "model",
                false,
                Instant.parse("2026-07-30T00:00:00Z"),
                List.of(ChatMessage.user(userRequest)),
                List.of(),
                ChatRequestControls.defaults(),
                ""));

        PromptDebugSnapshot snapshot = PromptDebugCapture.latest().orElseThrow();
        assertEquals(
                "(none)",
                snapshot.diagnostics().get(TARGET_ROLES_DIAGNOSTIC));
        String rendered = PromptDebugInspector.format(snapshot);
        assertTrue(rendered.contains("- Target roles: (none)"), rendered);
        assertFalse(rendered.contains("style.css = MUST_MUTATE"), rendered);
    }

    @Test
    void contentThenExactWorkspaceOperationKeepsBothTypedSurfaces(
            @TempDir Path workspace
    ) {
        String request =
                "Edit index.html so it works, then copy style.css to script.js.";
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        Context context = Context.builder(new Config())
                .toolRegistry(CanonicalToolDescriptors.registry())
                .build();

        AssistantTurnPreparation.PreparedTurn prepared =
                AssistantTurnPreparation.prepare(
                        messages,
                        workspace,
                        context,
                        false,
                        CapabilityPosture.AGENT);

        assertFalse(prepared.taskResolution().blocksWorkspaceOperations());
        assertEquals(
                1,
                prepared.taskResolution().workspaceOperationPlan()
                        .authorizedOperations().size());
        assertEquals(
                ActionObligation.WORKSPACE_OPERATION_REQUIRED,
                prepared.plan().actionObligation());
        assertEquals(
                java.util.Set.of("index.html"),
                prepared.plan().taskContract().expectedTargets());
        assertTrue(
                prepared.plan().promptTools().contains("talos.edit_file"),
                prepared.plan().promptTools().toString());
        assertTrue(
                prepared.plan().promptTools().contains("talos.copy_path"),
                prepared.plan().promptTools().toString());
        assertEquals(
                1,
                prepared.plan().promptTools().stream()
                        .filter(WorkspaceOperationPlanner::isWorkspaceOperationTool)
                        .count());

        String frame = CurrentTurnCapabilityFrame.render(prepared.plan());
        assertTrue(
                frame.contains("obligation: WORKSPACE_OPERATION_REQUIRED"),
                frame);
        assertTrue(frame.contains("operationKind: COPY_PATH"), frame);
        assertTrue(frame.contains("exactTool: talos.copy_path"), frame);
        assertTrue(frame.contains("contentPredecessors: index.html"), frame);
    }

    @Test
    void promptDiagnosticCarriesTheExactIndependentAuthorityBoundary(
            @TempDir Path workspace
    ) {
        String request =
                "Edit index.html so it works, and update style.css too.";
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        PromptDebugCapture.beginTurn();

        AssistantTurnPreparation.PreparedTurn prepared =
                AssistantTurnPreparation.prepare(
                        messages,
                        workspace,
                        null,
                        false,
                        CapabilityPosture.AGENT);
        var decision = prepared.taskResolution()
                .mutationTailPlan()
                .decision("style.css")
                .orElseThrow();
        int expectedAuthorityEnd =
                request.lastIndexOf("style.css") + "style.css".length();

        PromptDebugCapture.record(new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "test",
                "model",
                false,
                Instant.parse("2026-07-30T00:00:00Z"),
                List.of(ChatMessage.user(request)),
                List.of(),
                ChatRequestControls.defaults(),
                ""));

        String diagnostic = PromptDebugCapture.latest()
                .orElseThrow()
                .diagnostics()
                .get(TARGET_ROLES_DIAGNOSTIC);
        assertEquals(expectedAuthorityEnd, decision.authorityEndOffset());
        assertTrue(decision.authorityEndOffset() < decision.endOffset());
        assertTrue(
                diagnostic.contains("style.css = MUST_MUTATE"),
                diagnostic);
        assertTrue(
                diagnostic.contains("[sourceSpan="
                        + decision.startOffset()
                        + ":"
                        + decision.authorityEndOffset()
                        + ":"
                        + decision.endOffset()
                        + "]"),
                diagnostic);
    }

    @Test
    void replayedPriorRequestUsesMessageHistoryEvidenceWithoutClaimingCurrentOffsets(
            @TempDir Path workspace
    ) {
        String request =
                "Edit index.html so it works, and update style.css too.";
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        PromptDebugCapture.beginTurn();

        AssistantTurnPreparation.PreparedTurn prepared =
                AssistantTurnPreparation.prepare(
                        messages,
                        workspace,
                        null,
                        true,
                        CapabilityPosture.AGENT);
        var style = prepared.taskResolution()
                .intent()
                .targets()
                .find("style.css")
                .orElseThrow();

        assertEquals(TargetSource.MESSAGE_HISTORY, style.derivation().source());
        assertEquals(-1, style.derivation().startOffset());
        assertEquals(-1, style.derivation().authorityEndOffset());
        assertEquals(-1, style.derivation().endOffset());
        assertEquals("update style.css too.", style.derivation().sourceText());
        assertTrue(prepared.taskResolution().mutationTailPlan().present());

        PromptDebugCapture.record(new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "test",
                "model",
                false,
                Instant.parse("2026-07-30T00:00:00Z"),
                List.of(ChatMessage.user(request)),
                List.of(),
                ChatRequestControls.defaults(),
                ""));
        String diagnostic = PromptDebugCapture.latest()
                .orElseThrow()
                .diagnostics()
                .get(TARGET_ROLES_DIAGNOSTIC);
        assertTrue(
                diagnostic.contains("[sourceSpan=unknown:unknown:unknown]"),
                diagnostic);
    }

    @Test
    void prohibitedWorkspaceTailsRemainBlockedInSurfaceAndObligation(
            @TempDir Path workspace
    ) {
        for (String request : List.of(
                "Edit index.html so it works, but do not create a directory named docs.",
                "Edit index.html so it works, but do not copy style.css to script.js.",
                "Edit index.html so it works, but do not move style.css to script.js.",
                "Edit index.html so it works, but do not rename style.css to script.js.",
                "Edit index.html so it works, but do not delete style.css.",
                "Edit index.html so it works, but do not touch config.env.")) {
            List<ChatMessage> messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user(request)));
            Context context = Context.builder(new Config())
                    .toolRegistry(CanonicalToolDescriptors.registry())
                    .build();

            AssistantTurnPreparation.PreparedTurn prepared =
                    assertDoesNotThrow(
                            () -> AssistantTurnPreparation.prepare(
                                    messages,
                                    workspace,
                                    context,
                                    false,
                                    CapabilityPosture.AGENT),
                            request);

            assertTrue(
                    prepared.taskResolution().blocksWorkspaceOperations(),
                    request);
            assertEquals(
                    ActionObligation.MUTATING_TOOL_REQUIRED,
                    prepared.plan().actionObligation(),
                    () -> request + " -> " + prepared.taskResolution());
            assertFalse(
                    prepared.plan().promptTools().stream()
                            .anyMatch(
                                    WorkspaceOperationPlanner
                                            ::isWorkspaceOperationTool),
                    request + " -> " + prepared.plan().promptTools());
            assertTrue(
                    prepared.plan().promptTools().contains("talos.edit_file"),
                    request + " -> " + prepared.plan().promptTools());
        }
    }

    @Test
    void exactMkdirRequestUsesOnlyItsTypedWorkspaceSurface(
            @TempDir Path workspace
    ) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(
                        "Make it work so create a directory named generated.")));
        Context context = Context.builder(new Config())
                .toolRegistry(CanonicalToolDescriptors.registry())
                .build();

        AssistantTurnPreparation.PreparedTurn prepared =
                AssistantTurnPreparation.prepare(
                        messages,
                        workspace,
                        context,
                        false,
                        CapabilityPosture.AGENT);

        assertFalse(prepared.taskResolution().blocksWorkspaceOperations());
        assertFalse(
                prepared.taskResolution().hasIndependentAuthorizedContentMutation());
        assertEquals(
                ActionObligation.WORKSPACE_OPERATION_REQUIRED,
                prepared.plan().actionObligation());
        assertEquals(List.of("talos.mkdir"), prepared.plan().promptTools());
        String frame = CurrentTurnCapabilityFrame.render(prepared.plan());
        assertTrue(frame.contains("operationKind: MKDIR"), frame);
        assertTrue(frame.contains("exactTool: talos.mkdir"), frame);
    }

    @Test
    void incompleteTargetScanReplacesAStaleMutationSurface(
            @TempDir Path workspace
    ) {
        String request = "Repair it. " + "a.txt ".repeat(8_193);
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        ActiveTaskContext saved = ActiveTaskContext.verifierFindings(
                1,
                "trace-overflow-context",
                List.of("saved.txt"),
                List.of("saved.txt still needs repair"),
                "FAILED");
        SessionMemory memory = new SessionMemory();
        memory.setActiveTaskContext(saved);
        memory.setArtifactGoal(ArtifactGoal.fromActiveContext(saved));
        Context context = Context.builder(new Config())
                .memory(memory)
                .toolRegistry(CanonicalToolDescriptors.registry())
                .build()
                .withNativeToolSpecs(List.of(new ToolSpec(
                        "talos.edit_file",
                        "stale mutation override",
                        "{}")));

        AssistantTurnPreparation.PreparedTurn prepared =
                AssistantTurnPreparation.prepare(
                        messages,
                        workspace,
                        context,
                        false,
                        CapabilityPosture.AGENT);

        assertTrue(prepared.taskResolution().sequentialMutationPlan()
                .targetIndexLimitExceeded());
        assertTrue(prepared.taskResolution().blocksUnscopedMutation());
        assertFalse(prepared.taskResolution().hasAuthorizedContentMutation());
        assertEquals(ExecutionPhase.INSPECT, prepared.plan().phaseInitial());
        assertEquals(ActionObligation.NONE, prepared.plan().actionObligation());
        assertEquals(ActiveTaskContext.NONE_OR_NOT_DERIVED,
                prepared.plan().activeTaskContext());
        assertEquals(ActiveTaskContext.NONE_OR_NOT_DERIVED,
                prepared.plan().artifactGoal());
        assertEquals(saved, memory.activeTaskContext());
        assertFalse(prepared.plan().promptTools().contains("talos.edit_file"));
        assertFalse(prepared.plan().promptTools().contains("talos.write_file"));
        assertFalse(prepared.ctx().nativeToolSpecs().stream()
                .map(ToolSpec::name)
                .anyMatch(name -> name.equals("talos.edit_file")
                        || name.equals("talos.write_file")));
    }

    @Test
    void preparedTurnRejectsIndependentPlanAndResolutionAuthorities(
            @TempDir Path workspace
    ) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Edit index.html.")));
        AssistantTurnPreparation.PreparedTurn prepared =
                AssistantTurnPreparation.prepare(
                        messages,
                        workspace,
                        null,
                        false,
                        CapabilityPosture.AGENT);
        var different = TaskContractResolver.resolveFromUserRequest(
                "Edit style.css.");

        assertThrows(
                IllegalArgumentException.class,
                () -> new AssistantTurnPreparation.PreparedTurn(
                        prepared.ctx(),
                        prepared.plan(),
                        different));
    }
}
