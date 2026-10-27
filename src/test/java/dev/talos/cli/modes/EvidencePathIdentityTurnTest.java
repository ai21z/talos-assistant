package dev.talos.cli.modes;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A target and a tool path that name the same workspace file in different
 * spellings are one file for evidence, handoff and progress checks.
 * Provider text is scripted and is not a live model.
 */
@Timeout(30)
class EvidencePathIdentityTurnTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temporary;
    Path workspace;
    ApprovalResponse answer = ApprovalResponse.APPROVED;
    final java.util.ArrayDeque<ApprovalResponse> scriptedAnswers = new java.util.ArrayDeque<>();

    @BeforeEach void useTheResolvedWorkspace() throws Exception {
        workspace = temporary.toRealPath();
        Files.writeString(workspace.resolve("notes.txt"), "hello from notes\n");
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

    @Test void relativeTargetReadThroughItsAbsolutePathIsReadOnce() throws Exception {
        Run run = run("Read notes.txt and tell me what it says.", new AtomicInteger(),
                call("talos.read_file", Map.of("path", workspace.resolve("notes.txt").toString())),
                "It says hello from notes.");

        assertEquals(EvidenceObligation.READ_TARGET_REQUIRED.name(), run.obligation());
        assertEquals(1, count(run, "talos.read_file"), run.audit().toolCalls().toString());
        assertFalse(run.answer().contains(EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX), run.answer());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void driveNamedTargetReadThroughItsRelativePathIsReadOnce() throws Exception {
        Run run = run("Read " + workspace.resolve("notes.txt") + " and tell me what it says.", new AtomicInteger(),
                call("talos.read_file", Map.of("path", "notes.txt")),
                "It says hello from notes.");

        assertEquals(EvidenceObligation.READ_TARGET_REQUIRED.name(), run.obligation());
        assertEquals(1, count(run, "talos.read_file"), run.audit().toolCalls().toString());
        assertFalse(run.answer().contains(EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX), run.answer());
    }

    @Test void aFileOfTheSameNameInASubfolderDoesNotStandInForTheTarget() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs").resolve("notes.txt"), "hello from docs\n");

        Run run = run("Read notes.txt and tell me what it says.", new AtomicInteger(),
                call("talos.read_file", Map.of("path", workspace.resolve("docs").resolve("notes.txt").toString())),
                "It says hello from docs.");

        assertTrue(readOf(run, "notes.txt"), run.audit().toolCalls().toString());
    }

    @Test void aSiblingFileDoesNotStandInForTheTarget() throws Exception {
        Files.writeString(workspace.resolve("other.txt"), "hello from other\n");

        Run run = run("Read notes.txt and tell me what it says.", new AtomicInteger(),
                call("talos.read_file", Map.of("path", workspace.resolve("other.txt").toString())),
                "It says hello from other.");

        assertTrue(readOf(run, "notes.txt"), run.audit().toolCalls().toString());
    }

    @Test void approvedProtectedReadUnderAnotherSpellingIsNotReportedAsUnread() throws Exception {
        Files.writeString(workspace.resolve(".env"), "MODE=local\n");
        var approvals = new AtomicInteger();

        Run run = run("Show me what .env contains.", approvals,
                call("talos.read_file", Map.of("path", workspace.resolve(".env").toString())),
                "The file sets the mode.");

        assertEquals(EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED.name(), run.obligation());
        assertEquals(1, approvals.get(), "one approval for one protected read");
        assertTrue(run.audit().toolCalls().stream().anyMatch(c -> c.name().equals("talos.read_file") && c.success()),
                run.audit().toolCalls().toString());
        assertFalse(run.answer().contains("No protected content was read"), run.answer());
        assertFalse(run.answer().contains("[Protected read incomplete"), run.answer());
        assertFalse(run.answer().contains("I did not read protected content this turn"), run.answer());
    }

    @Test void deniedProtectedReadUnderAnotherSpellingIsNotAskedAgain() throws Exception {
        Files.writeString(workspace.resolve(".env"), "MODE=local\n");
        var approvals = new AtomicInteger();
        answer = ApprovalResponse.DENIED;

        Run run = run("Show me what .env contains.", approvals,
                call("talos.read_file", Map.of("path", workspace.resolve(".env").toString())),
                "I could not read it.");

        assertEquals(1, approvals.get(), run.audit().toolCalls().toString());
        assertEquals(1, count(run, "talos.read_file"), run.audit().toolCalls().toString());
        assertFalse(run.answer().contains("MODE=local"), run.answer());
    }

    @Test void aDeniedReadAfterAnApprovedReadOfTheSameFileDoesNotSayNothingWasRead() throws Exception {
        Files.writeString(workspace.resolve(".env"), "MODE=local\n");
        var approvals = new AtomicInteger();
        scriptedAnswers.add(ApprovalResponse.APPROVED);
        scriptedAnswers.add(ApprovalResponse.DENIED);

        Run run = run("Show me what .env contains.", approvals,
                call("talos.read_file", Map.of("path", ".env")),
                call("talos.read_file", Map.of("path", workspace.resolve(".env").toString())),
                "The file sets the mode.");

        assertEquals(2, approvals.get(), run.audit().toolCalls().toString());
        assertFalse(run.answer().contains("Protected content was not read"), run.answer());
        assertFalse(run.answer().contains("No protected content was read"), run.answer());
        assertFalse(run.answer().contains("I did not read protected content this turn"), run.answer());
        assertTrue(run.answer().contains("approved read"), run.answer());
    }

    @Test void writeThroughItsAbsolutePathLeavesNoRemainingTarget() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        var approvals = new AtomicInteger();

        Run run = run("Create docs/a.md containing hello.", approvals,
                call("talos.write_file", Map.of("path", workspace.resolve("docs").resolve("a.md").toString(),
                        "content", "hello\n")),
                "Done.");

        assertEquals("hello\n", Files.readString(workspace.resolve("docs").resolve("a.md")));
        assertEquals(1, approvals.get());
        assertNoRemainingTarget(run);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void driveNamedWriteThroughItsRelativePathLeavesNoRemainingTarget() throws Exception {
        var approvals = new AtomicInteger();

        Run run = run("Create " + workspace.resolve("out.txt") + " containing done.", approvals,
                call("talos.write_file", Map.of("path", "out.txt", "content", "done\n")),
                "Done.");

        assertEquals("done\n", Files.readString(workspace.resolve("out.txt")));
        assertEquals(1, approvals.get());
        assertNoRemainingTarget(run);
    }

    @Test void mixedSpellingsAcrossTwoTargetsDoNotBreachThePendingObligation() throws Exception {
        var approvals = new AtomicInteger();

        Run run = run("Create a.txt and b.txt, each containing x.", approvals,
                call("talos.write_file", Map.of("path", "a.txt", "content", "x\n")),
                call("talos.write_file", Map.of("path", workspace.resolve("b.txt").toString(), "content", "x\n")),
                "Done.");

        assertTrue(Files.exists(workspace.resolve("a.txt")) && Files.exists(workspace.resolve("b.txt")),
                run.audit().toolCalls().toString());
        assertEquals(2, approvals.get());
        assertNoRemainingTarget(run);
    }

    private static void assertNoRemainingTarget(Run run) {
        assertFalse(run.answer().contains("Remaining target(s)"), run.answer());
        assertFalse(run.answer().contains("[Action obligation failed"), run.answer());
        assertFalse(run.answer().contains("expected target was not successfully mutated"), run.answer());
    }

    private static boolean readOf(Run run, String path) {
        return run.audit().toolCalls().stream().anyMatch(call ->
                call.name().equals("talos.read_file") && call.pathHint().equals(path) && call.success());
    }

    private static long count(Run run, String tool) {
        return run.audit().toolCalls().stream().filter(call -> call.name().equals(tool)).count();
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
                return scriptedAnswers.isEmpty() ? answer : scriptedAnswers.poll();
            }
        };
        var processor = new TurnProcessor(null, gate, registry, ApprovalPolicy.ALWAYS_ASK);
        fixture.client().setToolSpecs(registry.descriptors().stream()
                .map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema())).toList());
        var context = Context.builder(new Config(null)).llm(fixture.client()).memory(new SessionMemory())
                .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).approvalGate(gate)
                .toolCallLoop(new ToolCallLoop(processor, 6)).build();
        LocalTurnTraceCapture.begin("evidence-identity", "offline", 1, "2026-10-21T00:00:00Z",
                "synthetic-workspace", "auto", "scripted", "scripted-text-tools", request);
        TurnAuditCapture.begin();
        try (var client = fixture.client()) {
            var output = AssistantTurnExecutor.execute(
                    new ArrayList<>(List.of(ChatMessage.system("You are Talos."), ChatMessage.user(request))),
                    workspace, context,
                    new AssistantTurnExecutor.Options().capabilityPosture(CapabilityPosture.AGENT).llmTimeoutMs(5_000));
            var audit = TurnAuditCapture.end();
            var trace = LocalTurnTraceCapture.complete();
            return new Run(output.text(), audit, trace.promptAudit() == null ? "" : trace.promptAudit().evidenceObligation());
        }
    }

    private record Run(String answer, TurnAudit audit, String obligation) {}
}
