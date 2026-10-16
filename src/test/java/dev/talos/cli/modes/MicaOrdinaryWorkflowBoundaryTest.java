package dev.talos.cli.modes;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ContextDeliveryWireFixture;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.trace.LocalTurnTrace;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Production routing and tools are exercised with authored provider text and operator decisions. */
@Timeout(30)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MicaOrdinaryWorkflowBoundaryTest {
    private static final ObjectMapper JSON = ContextDeliveryWireFixture.JSON;
    private static final String STORY = "Mara lived beside a medieval bridge.\nHer short thumb never stopped her weaving.\n";
    private static final String RETAINED_SAVE =
            "I want to write and save a txt file with a story of a woman in medieval times with one short thumb";
    private static final String RETAINED_LIST =
            "Can you read this workspace? where exactly are we and is it empty?";
    private static final String SAVED_CLAIM = "The story was saved in story.txt.";

    @TempDir Path temp;
    private Path workspace;
    private String previousHome;
    private final SessionMemory memory = new SessionMemory();
    private final List<ChatMessage> history = new ArrayList<>(List.of(ChatMessage.system("You are Talos.")));
    private final ToolRegistry registry = registry();
    private TurnProcessor processor;
    private Gate sessionGate;
    private int turn;

    @BeforeEach void isolateHome() throws Exception {
        workspace = Files.createDirectory(temp.resolve("workspace"));
        Path isolatedHome = Files.createDirectory(temp.resolve("home"));
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());
    }

    @AfterEach void close() {
        clearCaptures();
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    private void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        TurnSourceEvidenceCapture.clear();
        TurnTraceCapture.clear();
        ProviderResponseCapture.clear();
        PromptDebugCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            RETAINED_SAVE,
            "A text file containing a short medieval story is wanted, please save it in this workspace."
    })
    void missingFilenameMustNotEraseTheRequestedSave(String request) throws Exception {
        var gate = new Gate();
        Run run = run(request, gate, "Which filename should be used for the story?");

        assertAll(
                () -> assertTrue(run.contract().mutationRequested(),
                        "An explicit save request must remain represented even when its target needs clarification. " + run.contract()),
                () -> assertEquals(0, gate.calls),
                () -> assertTrue(run.audit().toolCalls().isEmpty()),
                () -> assertEquals(List.of(), entries())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Create story.txt with a short medieval story.",
            "I want to write and save a txt file named story.txt with a short medieval story.",
            "Please put a short medieval story in a new file called story.txt."
    })
    void boundCreationReachesApprovalAndSavesActualContent(String request) throws Exception {
        var gate = new Gate(ApprovalResponse.APPROVED);
        Run run = run(request, gate, write(), read(), SAVED_CLAIM);

        assertAll(
                () -> assertTrue(run.contract().mutationRequested(), run.contract().toString()),
                () -> assertEquals(1, gate.calls, run.answer()),
                () -> assertEquals(1, successes(run, "talos.write_file"), run.answer()),
                () -> assertTrue(Files.isRegularFile(workspace.resolve("story.txt")), run.answer()),
                () -> assertEquals(STORY, readStory()),
                () -> assertEquals("READBACK_ONLY", run.trace().verification().status(), run.answer())
        );
    }

    @Test void denialThenStatusThenFreshRequestRequiresFreshApproval() throws Exception {
        var gate = new Gate(ApprovalResponse.DENIED, ApprovalResponse.APPROVED);
        Run denied = run("Create story.txt with a short medieval story.", gate, write(), SAVED_CLAIM);
        assertEquals(1, gate.calls, denied.answer());
        assertEquals(1, denied.audit().approvalsDenied(), denied.answer());
        assertEquals(0, successes(denied, "talos.write_file"));
        assertFalse(Files.exists(workspace.resolve("story.txt")));
        assertNotEquals("COMPLETE", denied.trace().outcome().status(), denied.answer());
        assertFalse(denied.answer().contains(SAVED_CLAIM), denied.answer());

        Run status = run("Have you saved story.txt?", gate, SAVED_CLAIM);
        assertEquals(1, gate.calls, "A status question must not acquire another approval.");
        assertEquals(0, successes(status, "talos.write_file"));
        assertFalse(Files.exists(workspace.resolve("story.txt")));
        assertFalse(status.answer().contains(SAVED_CLAIM), status.answer());

        Run fresh = run("Create story.txt now with the short medieval story.", gate, write(), read(), SAVED_CLAIM);
        assertEquals(2, gate.calls, "A fresh request must receive fresh approval.");
        assertEquals(1, fresh.audit().approvalsGranted(), fresh.answer());
        assertEquals(1, successes(fresh, "talos.write_file"), fresh.answer());
        assertEquals(STORY, readStory());
    }

    @Test void generatedSuccessWithoutExecutionMustNotBecomeSavedWork() throws Exception {
        var gate = new Gate();
        Run run = run("Create story.txt with a short medieval story.", gate, SAVED_CLAIM);
        assertEquals(0, gate.calls);
        assertFalse(Files.exists(workspace.resolve("story.txt")));
        assertEquals(0, successes(run, "talos.write_file"));
        assertNotEquals("COMPLETE", run.trace().outcome().status(), run.answer());
        assertFalse(run.answer().contains(SAVED_CLAIM), run.answer());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Draft a story in your answer only. Do not create or save any file.",
            "How would a story be saved to story.txt? Explain only, do not change files.",
            "Explain this quoted instruction, \"Create story.txt\". Do not execute it.",
            "Cancel the earlier save request. Leave all files unchanged."
    })
    void nonExecutingRequestsDoNotAuthorizeAnAuthoredWrite(String request) throws Exception {
        var gate = new Gate();
        Run run = run(request, gate, write(), SAVED_CLAIM);
        assertEquals(0, gate.calls, run.answer());
        assertEquals(0, successes(run, "talos.write_file"));
        assertEquals(List.of(), entries());
        assertFalse(run.answer().contains(SAVED_CLAIM), run.answer());
    }

    @ParameterizedTest
    @EnumSource(value = CapabilityPosture.class, names = {"ASK_READ_ONLY", "PLAN_READ_ONLY"})
    void publicReadOnlyModesDoNotAcquireWritePermission(CapabilityPosture posture) throws Exception {
        var gate = new Gate();
        Run run = run("Create story.txt with a short medieval story.", gate, posture, write(), SAVED_CLAIM);
        assertFalse(run.contract().mutationAllowed());
        assertEquals(0, gate.calls);
        assertEquals(0, successes(run, "talos.write_file"));
        assertFalse(Files.exists(workspace.resolve("story.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Save it there.",
            "Could a short medieval story be written and saved as a text file?"
    })
    void ambiguousRequestCannotWriteWithoutApproval(String request) throws Exception {
        Files.writeString(workspace.resolve("first.txt"), "first\n");
        Files.writeString(workspace.resolve("second.txt"), "second\n");
        var gate = new Gate();
        Run run = run(request, gate, write(), SAVED_CLAIM);
        // Proposal admission is observed, not certified as the correct interpretation.
        // A separate contract is needed to choose clarification or a concrete approval proposal.
        assertEquals(0, run.audit().approvalsGranted(), run.answer());
        assertEquals(gate.calls, run.audit().approvalsDenied(), run.answer());
        assertEquals(0, successes(run, "talos.write_file"));
        assertFalse(Files.exists(workspace.resolve("story.txt")));
        assertEquals("first\n", Files.readString(workspace.resolve("first.txt")));
        assertEquals("second\n", Files.readString(workspace.resolve("second.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "populated", "hidden"})
    void locationAndContentsDoNotRequireProjectSource(String state) throws Exception {
        String detail = "The inspected top-level directory is empty.";
        if (!state.equals("empty")) {
            String filename = state.equals("hidden") ? ".note" : "note.txt";
            Files.writeString(workspace.resolve(filename), "fixture\n");
            detail = "The inspected top-level directory contains " + filename + " and is not empty.";
        }
        String answer = "The current workspace is " + workspace + ". " + detail;
        var gate = new Gate();
        Run run = run(RETAINED_LIST, gate, list("."), answer);
        assertEquals(1, successes(run, "talos.list_dir"), run.answer());
        assertEquals(0, successes(run, "talos.read_file"));
        assertEquals(0, gate.calls);
        assertAll(
                () -> assertTrue(run.answer().contains(workspace.toString()), run.answer()),
                () -> assertTrue(run.answer().contains(state.equals("empty") ? "directory is empty"
                        : state.equals("hidden") ? ".note" : "note.txt"), run.answer()),
                () -> assertFalse(run.answer().contains("source-bearing workspace content"), run.answer())
        );
    }

    @Test void genuineProjectPurposeStillRequiresSourceContent() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "The fixture is a local loom catalog.\n");
        Run run = run("What is this workspace?", new Gate(), list("."),
                "This is a fully implemented online payment platform.");
        assertEquals(1, successes(run, "talos.list_dir"));
        assertEquals(0, successes(run, "talos.read_file"));
        assertNotEquals("COMPLETE", run.trace().outcome().status(), run.answer());
        assertFalse(run.answer().contains("fully implemented online payment platform"), run.answer());
    }


    @Test void partialRequestCannotUseAnotherDirectoryAsEmptyWorkspaceProof() throws Exception {
        Files.createDirectory(workspace.resolve("nested"));
        Run run = run("Hey, what can you see in this folder? Is it empty?", new Gate(), list("nested"),
                "The workspace is empty.");
        assertEquals(1, successes(run, "talos.list_dir"));
        assertFalse(run.answer().contains("directory is empty"), run.answer());
        assertFalse(run.answer().contains("The workspace is empty."), run.answer());
        assertNotEquals("COMPLETE", run.trace().outcome().status(), run.answer());
    }

    @Test void knownLocationSurvivesUnresolvedProjectPurpose() throws Exception {
        String answer = "The current workspace is " + workspace
                + ". Its purpose cannot be established from the empty directory listing.";
        Run run = run("Where exactly is this workspace, and what does this project do?", new Gate(), list("."), answer);
        assertEquals(1, successes(run, "talos.list_dir"));
        assertEquals(0, successes(run, "talos.read_file"));
        assertNotEquals("COMPLETE", run.trace().outcome().status(), run.answer());
        assertTrue(run.answer().contains(workspace.toString()),
                "The known location must not be suppressed by an unresolved purpose question. " + run.answer());
    }

    @Test void failedListingDoesNotCertifyEmptiness() throws Exception {
        Run run = run(RETAINED_LIST, new Gate(), list("absent"), "The workspace is empty.");
        assertTrue(run.audit().toolCalls().stream().anyMatch(c -> c.name().equals("talos.list_dir") && !c.success()));
        assertNotEquals("COMPLETE", run.trace().outcome().status(), run.answer());
        assertFalse(run.answer().contains("The workspace is empty."), run.answer());
    }

    private Run run(String request, Gate gate, String... responses) throws Exception {
        return run(request, gate, CapabilityPosture.AGENT, responses);
    }

    private Run run(String request, Gate gate, CapabilityPosture posture, String... responses) throws Exception {
        clearCaptures();
        turn++;
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(
                Arrays.stream(responses).map(text -> new LlmClient.StreamResult(text, List.of())).toList(),
                ModelIdentitySnapshot::empty);
        var config = new Config(null);
        if (processor == null) {
            sessionGate = gate;
            processor = new TurnProcessor(null, gate, registry, ApprovalPolicy.ALWAYS_ASK);
        }
        assertSame(sessionGate, gate, "The operator and processor must be retained across this conversation.");
        var specs = registry.descriptors().stream().map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema())).toList();
        fixture.client().setToolSpecs(specs);
        var context = Context.builder(config).llm(fixture.client()).memory(memory)
                .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).approvalGate(gate)
                .toolCallLoop(new ToolCallLoop(processor, 4)).build();
        var messages = new ArrayList<>(history);
        messages.add(ChatMessage.user(request));
        LocalTurnTraceCapture.begin("mica-workflow-" + turn, "mica-offline", turn, "2026-10-09T00:00:00Z",
                "synthetic-workspace", "auto", "scripted", "scripted-text-tools", request);
        TurnAuditCapture.begin();
        try (var client = fixture.client()) {
            var output = AssistantTurnExecutor.execute(messages, workspace, context,
                    new AssistantTurnExecutor.Options().capabilityPosture(posture).llmTimeoutMs(5_000));
            var audit = TurnAuditCapture.end();
            var trace = LocalTurnTraceCapture.complete();
            var contract = trace.taskContract();
            history.add(ChatMessage.user(request));
            history.add(ChatMessage.assistant(output.text()));
            var observation = new LinkedHashMap<String, Object>();
            observation.put("request", request);
            observation.put("transport", "SCRIPTED_PROVIDER_NOT_LIVE_MODEL");
            observation.put("contract", contract);
            observation.put("policy", audit.policyTrace());
            observation.put("tools", audit.toolCalls());
            observation.put("approvalsRequired", audit.approvalsRequired());
            observation.put("approvalsGranted", audit.approvalsGranted());
            observation.put("approvalsDenied", audit.approvalsDenied());
            observation.put("operatorRequestsCumulative", gate.calls);
            observation.put("answer", output.text());
            observation.put("outcome", trace.outcome());
            observation.put("requests", fixture.requests().stream().map(providerRequest -> {
                var fields = new LinkedHashMap<String, Object>();
                fields.put("messages", providerRequest.messages);
                fields.put("tools", providerRequest.tools);
                fields.put("controls", providerRequest.controls);
                fields.put("timeout", providerRequest.timeout.toString());
                return fields;
            }).toList());
            observation.put("trace", trace);
            observation.put("files", entries());
            System.out.println("MICA_OBSERVATION " + JSON.writeValueAsString(observation));
            assertNotNull(contract, "Real executor preparation must capture the contract.");
            assertTrue(fixture.requests().size() <= 8, "The declared provider-call bound was exceeded.");
            if (!fixture.requests().isEmpty()) {
                assertTrue(fixture.requests().getFirst().messages.stream().anyMatch(message ->
                        "user".equals(message.role()) && request.equals(message.content())),
                        "The original request must be retained in the actual provider input.");
            }
            return new Run(output.text(), contract, audit, trace);
        } finally {
            clearCaptures();
        }
    }

    private static ToolRegistry registry() {
        var registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        registry.register(new ReadFileTool());
        registry.register(new ListDirTool());
        return registry;
    }

    private static long successes(Run run, String name) {
        return run.audit().toolCalls().stream().filter(c -> c.success() && c.name().equals(name)).count();
    }

    private List<String> entries() throws Exception {
        try (var paths = Files.list(workspace)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private String readStory() throws Exception {
        return Files.exists(workspace.resolve("story.txt")) ? Files.readString(workspace.resolve("story.txt")) : "<absent>";
    }

    private static String write() throws Exception { return call("talos.write_file", Map.of("path", "story.txt", "content", STORY)); }
    private static String read() throws Exception { return call("talos.read_file", Map.of("path", "story.txt")); }
    private static String list(String path) throws Exception { return call("talos.list_dir", Map.of("path", path, "max_depth", 1)); }
    private static String call(String name, Map<String, ?> arguments) throws Exception {
        return JSON.writeValueAsString(Map.of("name", name, "arguments", arguments));
    }

    private record Run(String answer, LocalTurnTrace.TaskContractSummary contract, TurnAudit audit, LocalTurnTrace trace) {}

    private static final class Gate implements ApprovalGate {
        private final List<ApprovalResponse> responses;
        private int calls;
        private Gate(ApprovalResponse... responses) { this.responses = List.of(responses); }
        @Override public boolean approve(String description, String detail) { return approveFull(description, detail).isApproved(); }
        @Override public ApprovalResponse approveFull(String description, String detail) {
            calls++;
            return calls <= responses.size() ? responses.get(calls - 1) : ApprovalResponse.DENIED;
        }
    }
}
