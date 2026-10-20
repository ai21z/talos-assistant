package dev.talos.cli.modes;

import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.prompt.PromptInspector;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.observation.TurnRequestFrame;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.EvidenceGate;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The selected workspace, not the process working directory, governs turn
 * planning. The test JVM runs in the project folder and the workspace is a
 * separate temporary folder, so the two bases genuinely differ. Provider
 * text is scripted and is not a live model.
 *
 * <p>Without any workspace, a target is judged by its protected name only.
 * A rooted target inside the working directory but outside the workspace
 * has a protected name and is not a protected workspace read, so it shows
 * whether a builder really received the workspace.
 */
@Timeout(30)
class WorkspaceOwnershipBoundaryTest {
    private static final String INVENTED = "SECRET_TOKEN=abc123";
    private static final String PROTECTED = EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED.name();
    private static final String READ_TARGET = EvidenceObligation.READ_TARGET_REQUIRED.name();

    @TempDir Path temporary;
    Path workspace;

    @BeforeEach void useTheResolvedWorkspace() throws Exception {
        workspace = temporary.toRealPath();
    }

    @AfterEach void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        TurnSourceEvidenceCapture.clear();
        TurnTraceCapture.clear();
        ProviderResponseCapture.clear();
        PromptDebugCapture.clear();
        LastPromptCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test void parentRelativeProtectedTargetIsClassifiedAgainstTheSelectedWorkspace() {
        assumeSeparateBases();
        String target = "../" + workspace.getFileName() + "/.env";
        CurrentTurnPlan plan = prepare(read(target));

        assertTrue(targets(plan).contains(target), "target parsed: " + targets(plan));
        assertEquals(PROTECTED, plan.evidenceObligation());
    }

    @Test void plainProtectedTargetStaysProtected() {
        CurrentTurnPlan plan = prepare(read(".env"));

        assertTrue(targets(plan).contains(".env"), "target parsed: " + targets(plan));
        assertEquals(PROTECTED, plan.evidenceObligation());
    }

    @Test void targetInsideTheWorkingDirectoryButOutsideTheWorkspaceIsNotTreatedAsAProtectedWorkspaceRead() {
        String rooted = rootedTargetInsideTheWorkingDirectory();
        CurrentTurnPlan plan = prepare(read(rooted));

        assertTrue(targets(plan).contains(rooted), "target parsed: " + targets(plan));
        assertEquals(READ_TARGET, plan.evidenceObligation());
    }

    @Test void retriesKeepTheWorkspaceObligationAndPostToolSynthesisStillDropsIt() {
        assumeSeparateBases();
        CurrentTurnPlan protectedPlan = prepare(read("../" + workspace.getFileName() + "/.env"));
        CurrentTurnPlan outsidePlan = prepare(read(rootedTargetInsideTheWorkingDirectory()));

        assertEquals(PROTECTED, RetryDispatchPurpose.READ_ONLY_INSPECTION.project(protectedPlan).evidenceObligation());
        assertEquals(READ_TARGET, RetryDispatchPurpose.READ_ONLY_INSPECTION.project(outsidePlan).evidenceObligation());
        assertEquals(EvidenceObligation.NONE.name(),
                RetryDispatchPurpose.POST_TOOL_SYNTHESIS.project(protectedPlan).evidenceObligation());
    }

    @Test void workspaceLessPlansUseTheOpenRequestFrameAndOtherwiseTheProtectedNameOnly() {
        String rooted = rootedTargetInsideTheWorkingDirectory();
        TaskContract contract = TaskContractResolver.fromUserRequest(read(rooted));

        TurnRequestFrame frame = TurnRequestFrame.open(read(rooted), workspace, 1, null);
        try {
            assertEquals(READ_TARGET, CurrentTurnPlan.compatibility(
                    contract, ExecutionPhase.INSPECT, List.of(), List.of(), List.of()).evidenceObligation());
        } finally {
            frame.close();
        }
        assertEquals(PROTECTED, CurrentTurnPlan.compatibility(
                contract, ExecutionPhase.INSPECT, List.of(), List.of(), List.of()).evidenceObligation());
    }

    @Test void promptPreviewDerivesAgainstTheSelectedWorkspace() {
        String rooted = rootedTargetInsideTheWorkingDirectory();

        var render = PromptInspector.renderNext("auto", read(rooted), workspace, null);
        String prompt = render.messages().stream().map(ChatMessage::content).reduce("", String::concat);

        assertTrue(prompt.contains("evidenceObligation: " + READ_TARGET), prompt);
        assertFalse(prompt.contains(PROTECTED), prompt);
    }

    @Test void unifiedModePromptDerivesAgainstTheSelectedWorkspace() throws Exception {
        String rooted = rootedTargetInsideTheWorkingDirectory();
        var fixture = fixture("I cannot answer that without reading the file.");
        var context = context(fixture.client(), new DenyingGate());

        LastPromptCapture.clear();
        try (var client = fixture.client()) {
            new UnifiedAssistantMode().handle(read(rooted), workspace, context);
        }

        // The mode's own plan is what /prompt shows. The provider request carries the prepared turn plan.
        String shownByPromptCommand = LastPromptCapture.latest().orElseThrow().messages().stream()
                .map(ChatMessage::content).reduce("", String::concat);
        assertTrue(shownByPromptCommand.contains("evidenceObligation: " + READ_TARGET), shownByPromptCommand);
        assertFalse(shownByPromptCommand.contains(PROTECTED), shownByPromptCommand);
        assertFalse(fixture.requests().isEmpty(), "the provider received a request");
        String firstRequest = fixture.requests().getFirst().messages.stream()
                .map(ChatMessage::content).reduce("", String::concat);
        assertTrue(firstRequest.contains("evidenceObligation: " + READ_TARGET), firstRequest);
        assertFalse(firstRequest.contains(PROTECTED), firstRequest);
    }

    @Test void unreadProtectedTargetIsRecordedAndContainedAsAProtectedRead() throws Exception {
        assumeSeparateBases();
        Files.writeString(workspace.resolve(".env"), "API_KEY=real-value\n");
        String request = read("../" + workspace.getFileName() + "/.env");

        Run run = run(request, new DenyingGate(),
                "The file contains " + INVENTED + ".", "The file contains " + INVENTED + ".");

        assertAll(
                () -> assertEquals(PROTECTED, run.trace().promptAudit().evidenceObligation(),
                        "recorded obligation shown by /last"),
                () -> assertEquals(gateObligation(request), run.trace().promptAudit().evidenceObligation(),
                        "the recorded obligation equals the gate's selection"),
                () -> assertFalse(run.answer().contains("real-value"), run.answer()),
                () -> assertFalse(run.answer().contains(INVENTED), run.answer()),
                () -> assertTrue(run.audit().toolCalls().stream()
                                .noneMatch(call -> call.success() && call.name().equals("talos.read_file")),
                        "no protected read succeeds without approval"));
    }

    @Test void targetOutsideTheWorkspaceIsRecordedAsAnOrdinaryReadThroughTheTurn() throws Exception {
        String request = read(rootedTargetInsideTheWorkingDirectory());

        Run run = run(request, new DenyingGate(), "It says hello.", "It says hello.");

        assertEquals(READ_TARGET, run.trace().promptAudit().evidenceObligation(), "recorded obligation shown by /last");
        assertEquals(gateObligation(request), run.trace().promptAudit().evidenceObligation(),
                "the recorded obligation equals the gate's selection");
    }

    private void assumeSeparateBases() {
        Path launch = Path.of("").toAbsolutePath().normalize();
        assumeTrue(!workspace.startsWith(launch) && !launch.startsWith(workspace),
                "The workspace must lie outside the process working directory");
    }

    private String rootedTargetInsideTheWorkingDirectory() {
        assumeSeparateBases();
        Path launch = Path.of("").toAbsolutePath().normalize();
        String rooted = "/" + launch.getRoot().relativize(launch).toString().replace('\\', '/') + "/.env";
        String lower = rooted.toLowerCase(Locale.ROOT);
        assumeTrue(rooted.matches("[A-Za-z0-9_./-]+") && !lower.contains("exist") && !lower.contains("present"),
                "The working directory path must be a plain target");
        return rooted;
    }

    private static String read(String target) {
        return "Read " + target + " and tell me what it says.";
    }

    private String gateObligation(String request) {
        return EvidenceGate.selectObligation(prepare(request), workspace, null).name();
    }

    private CurrentTurnPlan prepare(String request) {
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(request)));
        return AssistantTurnPreparation.prepare(messages, workspace, null, false, CapabilityPosture.AGENT).plan();
    }

    private static List<String> targets(CurrentTurnPlan plan) {
        var out = new ArrayList<String>(plan.taskContract().expectedTargets());
        out.addAll(plan.taskContract().sourceEvidenceTargets());
        return out;
    }

    private static ScriptedNativeLlmClient.RecordedClient fixture(String... responses) {
        return ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(
                java.util.Arrays.stream(responses).map(text -> new LlmClient.StreamResult(text, List.of())).toList(),
                ModelIdentitySnapshot::empty);
    }

    private Context context(LlmClient client, ApprovalGate gate) {
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new ListDirTool());
        var processor = new TurnProcessor(null, gate, registry, ApprovalPolicy.ALWAYS_ASK);
        client.setToolSpecs(registry.descriptors().stream()
                .map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema())).toList());
        return Context.builder(new Config(null)).llm(client).memory(new SessionMemory())
                .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).approvalGate(gate)
                .toolCallLoop(new ToolCallLoop(processor, 4)).build();
    }

    private Run run(String request, ApprovalGate gate, String... responses) throws Exception {
        clearCaptures();
        var fixture = fixture(responses);
        var context = context(fixture.client(), gate);
        var messages = new ArrayList<>(List.of(ChatMessage.system("You are Talos."), ChatMessage.user(request)));
        LocalTurnTraceCapture.begin("workspace-ownership", "workspace-offline", 1, "2026-10-20T00:00:00Z",
                "synthetic-workspace", "auto", "scripted", "scripted-text-tools", request);
        TurnAuditCapture.begin();
        try (var client = fixture.client()) {
            var output = AssistantTurnExecutor.execute(messages, workspace, context,
                    new AssistantTurnExecutor.Options().capabilityPosture(CapabilityPosture.AGENT).llmTimeoutMs(5_000));
            var audit = TurnAuditCapture.end();
            var trace = LocalTurnTraceCapture.complete();
            return new Run(output.text(), audit, trace);
        }
    }

    private record Run(String answer, TurnAudit audit, LocalTurnTrace trace) {}

    private static final class DenyingGate implements ApprovalGate {
        @Override public boolean approve(String description, String detail) { return false; }
        @Override public ApprovalResponse approveFull(String description, String detail) { return ApprovalResponse.DENIED; }
    }
}
