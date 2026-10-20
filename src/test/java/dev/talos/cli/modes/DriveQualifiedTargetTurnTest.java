package dev.talos.cli.modes;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Paths named with their Windows drive keep that drive through turn preparation and tools. Provider text is scripted. */
@Timeout(30)
@EnabledOnOs(OS.WINDOWS)
class DriveQualifiedTargetTurnTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temporary;
    Path workspace;

    @BeforeEach void useTheResolvedWorkspace() throws Exception {
        workspace = temporary.toRealPath();
        Files.writeString(workspace.resolve("notes.txt"), "hello from notes\n");
        Files.writeString(workspace.resolve("secret.txt"), "keep me\n");
    }

    @AfterEach void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        TurnSourceEvidenceCapture.clear();
        TurnTraceCapture.clear();
        ProviderResponseCapture.clear();
        PromptDebugCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test void targetOnAnotherDriveIsReportedWithItsDrive() throws Exception {
        String other = workspace.getRoot().toString().toUpperCase(Locale.ROOT).startsWith("D") ? "E" : "D";
        String named = other + ":\\elsewhere\\notes.txt";

        Run run = run("Read " + named + " and tell me what it says.", new AtomicInteger(),
                call("talos.read_file", Map.of("path", named)), "It says hello from notes.");

        assertTrue(run.answer().contains(other + ":/elsewhere/notes.txt"), run.answer());
        assertFalse(run.answer().contains("Required target(s): /elsewhere"), run.answer());
    }

    @Test void driveQualifiedFolderIsNotPlannedAsAFolderNamedAfterTheDrive() {
        String named = workspace.resolve("out").toString();

        CurrentTurnPlan plan = prepare("Create a folder named " + named);

        String drive = workspace.getRoot().toString().substring(0, 1);
        assertTrue(plan.workspaceOperationPlan().authorizedOperations().stream()
                        .noneMatch(operation -> drive.equalsIgnoreCase(operation.targetPath())
                                || drive.equalsIgnoreCase(operation.destinationPath())),
                String.valueOf(plan.workspaceOperationPlan()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forbiddenDriveQualifiedTargetIsKeptAndBlockedBeforeApproval(boolean absoluteToolPath) throws Exception {
        String forbidden = workspace.resolve("secret.txt").toString();
        String request = "Edit notes.txt but do not touch " + forbidden + ".";
        CurrentTurnPlan plan = prepare(request);
        assertTrue(plan.taskContract().forbiddenTargets().contains(forbidden.replace('\\', '/')),
                plan.taskContract().forbiddenTargets().toString());

        var approvals = new AtomicInteger();
        Run run = run(request, approvals,
                call("talos.write_file", Map.of("path", absoluteToolPath ? forbidden : "secret.txt", "content", "changed\n")),
                "Done.");

        assertEquals("keep me\n", Files.readString(workspace.resolve("secret.txt")));
        assertEquals(0, approvals.get(), "no approval is requested for a forbidden target");
        assertTrue(run.audit().toolCalls().stream().noneMatch(c -> c.success() && c.name().equals("talos.write_file")),
                run.audit().toolCalls().toString());
        assertTrue(run.audit().toolCalls().stream().anyMatch(c -> c.name().equals("talos.write_file")
                        && (c.failureReason() + " " + c.reason()).toLowerCase(Locale.ROOT).contains("forbidden")),
                "the write is refused by the forbidden target check: " + run.audit().toolCalls());
    }

    private CurrentTurnPlan prepare(String request) {
        return AssistantTurnPreparation.prepare(new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(request))),
                workspace, null, false, CapabilityPosture.AGENT).plan();
    }

    private static String call(String name, Map<String, ?> arguments) throws Exception {
        return JSON.writeValueAsString(Map.of("name", name, "arguments", arguments));
    }

    private Run run(String request, AtomicInteger approvals, String... responses) throws Exception {
        var scripted = new ArrayList<LlmClient.StreamResult>();
        for (String response : responses) scripted.add(new LlmClient.StreamResult(response, List.of()));
        scripted.add(new LlmClient.StreamResult(responses[responses.length - 1], List.of()));
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(scripted, ModelIdentitySnapshot::empty);
        var registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        registry.register(new ReadFileTool());
        registry.register(new ListDirTool());
        ApprovalGate gate = new ApprovalGate() {
            @Override public boolean approve(String description, String detail) { return approveFull(description, detail).isApproved(); }
            @Override public ApprovalResponse approveFull(String description, String detail) {
                approvals.incrementAndGet();
                return ApprovalResponse.APPROVED;
            }
        };
        var processor = new TurnProcessor(null, gate, registry, ApprovalPolicy.ALWAYS_ASK);
        fixture.client().setToolSpecs(registry.descriptors().stream()
                .map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema())).toList());
        var context = Context.builder(new Config(null)).llm(fixture.client()).memory(new SessionMemory())
                .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).approvalGate(gate)
                .toolCallLoop(new ToolCallLoop(processor, 4)).build();
        LocalTurnTraceCapture.begin("drive-target", "offline", 1, "2026-10-20T00:00:00Z",
                "synthetic-workspace", "auto", "scripted", "scripted-text-tools", request);
        TurnAuditCapture.begin();
        try (var client = fixture.client()) {
            var output = AssistantTurnExecutor.execute(
                    new ArrayList<>(List.of(ChatMessage.system("You are Talos."), ChatMessage.user(request))),
                    workspace, context,
                    new AssistantTurnExecutor.Options().capabilityPosture(CapabilityPosture.AGENT).llmTimeoutMs(5_000));
            var audit = TurnAuditCapture.end();
            LocalTurnTraceCapture.complete();
            return new Run(output.text(), audit);
        }
    }

    private record Run(String answer, TurnAudit audit) {}
}
