package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.RecordedMixedCodeAnswer;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real task resolution, provider capture, tool execution and bounded answer synthesis; no live model. */
class ReadOnlyDiagnosisSynthesisIntegrationTest {
    private static final List<String> TARGETS =
            List.of("README.md", "index.html", "script.js", "scripts.js");
    private static final String REQUEST =
            "Read README.md, index.html, script.js and scripts.js. Diagnose why the Calculate button "
                    + "does not work, identify the correct selector, calculate BMI for 72 kg and 1.8 m, "
                    + "and distinguish script.js from scripts.js. Do not edit any files or run tests.";
    private static final String ANSWER =
            "index.html loads script.js and defines the button as id=calculate. "
                    + "script.js selects #calc instead of #calculate, so the selector does not match. "
                    + "README.md documents that script.js is the loaded implementation. "
                    + "scripts.js is an unrelated sentinel, not the loaded script. "
                    + "For 72 kg and 1.8 m, BMI is 72 / (1.8 * 1.8) = 22.2. "
                    + "This is a source-based diagnosis. No files were edited and no tests were run.";
    private static final Map<String, String> CONTENTS = Map.of(
            "README.md", "The calculator loads script.js, not scripts.js.\n",
            "index.html", "<button id=\"calculate\">Calculate</button>\n<script src=\"script.js\"></script>\n",
            "script.js", "document.querySelector('#calc').addEventListener('click', calculateBmi);\n",
            "scripts.js", "const unrelatedSentinel = 'not loaded by index.html';\n");

    @TempDir Path workspace;

    @Test void fourRealReadsImmediatelySynthesizeTheCompleteRecordedMixedAnswerWithoutMutation() throws Exception {
        var before = createFiles(false);
        String source = RecordedMixedCodeAnswer.text();
        var run = run(6, List.of(
                read("read-readme", "README.md"), read("read-index", "index.html"),
                read("read-script", "script.js"), read("read-scripts", "scripts.js"), answer(source)));
        assertEquals(source, run.result().finalAnswer());
        assertEquals(4, run.result().toolsInvoked());
        assertEquals(0, run.result().failedCalls());
        assertEquals(0, run.result().retriedCalls());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED,
                run.result().readOnlySynthesisStatus());
        assertEquals(5, run.requests().size());
        assertEquals(1, synthesisRequests(run).size());
        assertTrue(run.requests().getLast().tools.isEmpty());
        assertEquals(ToolChoiceMode.NONE, run.requests().getLast().controls.toolChoice());
        assertTrue(run.requests().getLast().controls.singleAttempt());
        var selected = run.trace().events().stream()
                .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type())).toList();
        assertEquals(1, selected.size());
        assertEquals("MODEL_PROVIDER", selected.getFirst().data().get("origin"));
        assertEquals("sha256:" + RecordedMixedCodeAnswer.SHA256,
                selected.getFirst().data().get("answerHash"));
        assertNoMutations(run, before);
    }
    private final List<LlmClient> clients = new ArrayList<>();

    @BeforeEach void beginTrace() {
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.begin("read-only-diagnosis-integration", "tests", 1,
                "2026-09-09T00:00:00Z", "workspace", "auto", "llama_cpp", "fixture", REQUEST);
    }

    @AfterEach void close() {
        clients.forEach(LlmClient::close);
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test void pendingReadsStayRequiredAndFourthReadImmediatelySelectsOneResponseOnlySynthesis() throws Exception {
        var before = createFiles(false);
        var run = run(10, List.of(
                read("read-readme", "README.md"), read("read-index", "index.html"),
                read("read-script", "script.js"), read("read-scripts", "scripts.js"), answer(ANSWER),
                read("must-not-request-a-fifth-read", "README.md")));

        assertEquals(4, run.result().iterations());
        assertEquals(0, run.result().cushionFiresRedundantRead());
        assertFalse(run.result().hitIterLimit());
        assertEquals(5, run.requests().size(), "Four native proposals and one immediate response-only dispatch");
        assertSuccessfulReadOnlySynthesis(run, before);
        for (int completed = 1; completed <= 3; completed++) {
            var continuation = run.requests().get(completed);
            assertEquals(ToolChoiceMode.REQUIRED, continuation.controls.toolChoice());
            assertEquals(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                    continuation.controls.toolAuthorityMode());
            assertEquals(List.of("talos.read_file"), continuation.tools.stream().map(ToolSpec::name).toList());
            assertTrue(continuation.controls.singleAttempt(), "Pending evidence cannot acquire transport retries");
            var remaining = TARGETS.subList(completed, TARGETS.size());
            String instruction = continuation.messages.stream().map(ChatMessage::content)
                    .filter(java.util.Objects::nonNull)
                    .filter(text -> text.startsWith("[Pending diagnosis reads]\n"))
                    .findFirst().orElseThrow();
            for (String target : remaining) assertTrue(instruction.contains(target), instruction);
            for (String target : TARGETS.subList(0, completed))
                assertFalse(instruction.contains(target), "Do not request completed target again: " + instruction);
        }
        assertEquals(Map.of("path", "README.md"), run.initial().toolCalls().getFirst().arguments());
        assertEquals(4, run.trace().events().stream().filter(event -> "TOOL_EXECUTED".equals(event.type())).count(),
                "Complete evidence must never dispatch or execute a fifth filesystem read");
    }

    @Test void completeReadsAtToolCapKeepLimitTruthfulAndSelectTheCapturedAnswer() throws Exception {
        var before = createFiles(false);
        var run = run(4, List.of(
                read("read-readme", "README.md"), read("read-index", "index.html"),
                read("read-script", "script.js"), read("read-scripts", "scripts.js"), answer(ANSWER)));

        assertEquals(4, run.result().iterations());
        assertTrue(run.result().hitIterLimit(), "Successful synthesis must not erase the exhausted tool budget");
        assertEquals(0, run.result().cushionFiresRedundantRead());
        assertEquals(5, run.requests().size(), "No additional tool request or synthesis retry is allowed");
        assertSuccessfulReadOnlySynthesis(run, before);
    }

    @Test void missingTargetDoesNotAcquireResponseOnlySynthesis() throws Exception {
        var before = createFiles(true);
        var run = run(4, List.of(
                read("read-readme", "README.md"), read("read-index", "index.html"),
                read("read-script", "script.js"), read("read-missing", "scripts.js"),
                answer("scripts.js was not found. The requested four-file diagnosis remains incomplete.")));

        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED, run.result().readOnlySynthesisStatus());
        assertTrue(run.result().failedCalls() > 0, "The real read tool must report the missing target");
        assertTrue(run.result().toolOutcomes().stream().anyMatch(outcome -> "NOT_FOUND".equals(outcome.errorCode())));
        assertEquals(0, synthesisRequests(run).size(), "Partial or failed evidence must not enable synthesis");
        assertFalse(Files.exists(workspace.resolve("scripts.js")));
        assertNoMutations(run, before);
    }

    @Test void unusableSynthesisStopsOnceWithoutPretendingTheDiagnosisIsComplete() throws Exception {
        var before = createFiles(false);
        var run = run(4, List.of(
                read("read-readme", "README.md"), read("read-index", "index.html"),
                read("read-script", "script.js"), read("read-scripts", "scripts.js"),
                answer("How can I help you?")));

        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, run.result().readOnlySynthesisStatus());
        assertTrue(run.result().failureDecision().shouldStop());
        assertTrue(run.result().finalAnswer().contains("requested diagnosis remains incomplete"));
        assertFalse(run.result().finalAnswer().contains("How can I help"));
        assertEquals(1, synthesisRequests(run).size());
        assertEquals(5, run.requests().size(), "No retry after a deflecting response");
        assertEquals(4, run.result().toolsInvoked());
        assertNoMutations(run, before);
    }

    private Run run(int maxIterations, List<LlmClient.StreamResult> responses) {
        Config config = new Config();
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(config, responses, 16_384);
        clients.add(recorded.client());
        ReadFileTool readTool = new ReadFileTool();
        var descriptor = readTool.descriptor();
        var specs = List.of(new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema()));
        var registry = new ToolRegistry();
        registry.register(readTool);
        var loop = new ToolCallLoop(
                new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry), maxIterations);
        var context = Context.builder(config).sandbox(new Sandbox(workspace, Map.of()))
                .llm(recorded.client()).nativeToolSpecs(specs).build();
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("Use the available read tool for the requested source diagnosis."),
                ChatMessage.user(REQUEST)));

        var resolution = TaskContractResolver.resolveFromMessages(messages);
        assertEquals(TaskType.DIAGNOSE_ONLY, resolution.contract().type(), "Exercise real task resolution, not a hand-built contract");
        assertFalse(resolution.contract().mutationAllowed());
        assertFalse(resolution.contract().mutationRequested());
        var required = new java.util.LinkedHashSet<>(resolution.contract().expectedTargets());
        required.addAll(resolution.contract().sourceEvidenceTargets());
        assertEquals(Set.copyOf(TARGETS), required);
        TurnTaskContractCapture.set(resolution, messages);
        TurnUserRequestCapture.set(REQUEST);
        recorded.client().setToolSpecs(specs);

        // The first call also uses the real capture path, rather than forging a loop-state receipt.
        var initial = recorded.client().chatFull(messages, specs, ChatRequestControls.defaults()
                .withToolAuthorityMode(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE));
        assertTrue(initial.provenance().providerToolCallEligible());
        assertEquals(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE, initial.provenance().toolAuthorityMode());
        var result = loop.run(initial.text(), initial.toolCalls(), initial.provenance(), messages, workspace, context);
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.finalAnswer());
        return new Run(result, List.copyOf(recorded.requests()), LocalTurnTraceCapture.complete(), initial);
    }

    private void assertSuccessfulReadOnlySynthesis(Run run, Map<String, byte[]> before) throws Exception {
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, run.result().readOnlySynthesisStatus());
        assertEquals(ANSWER, run.result().finalAnswer(), "Retain exact provider prose without a tool-limit suffix");
        assertFalse(run.result().finalAnswerOutputLimitReached());
        assertFalse(run.result().failureDecision().shouldStop(), run.result().failureDecision().reason());
        assertEquals(4, run.result().toolsInvoked());
        assertEquals(0, run.result().failedCalls());
        assertEquals(0, run.result().retriedCalls());
        assertEquals(Set.copyOf(TARGETS), Set.copyOf(run.result().readPaths()));
        assertEquals(1, synthesisRequests(run).size());
        var synthesis = synthesisRequests(run).getFirst();
        assertSame(run.requests().getLast(), synthesis);
        assertTrue(synthesis.tools.isEmpty());
        assertEquals(ToolChoiceMode.NONE, synthesis.controls.toolChoice());
        assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, synthesis.controls.toolAuthorityMode());
        assertTrue(synthesis.controls.singleAttempt());
        assertEquals(2048, synthesis.controls.maxOutputTokens());
        assertEquals(4, synthesis.messages.stream().filter(message -> "tool".equals(message.role())).count());

        for (String target : TARGETS) {
            String rendered = renderedBody(CONTENTS.get(target));
            assertEquals(1, synthesis.messages.stream().filter(message -> "tool".equals(message.role())
                    && message.content().contains(rendered)).count(), target + " must be restored exactly once");
            assertFalse(synthesis.messages.stream().filter(message -> "system".equals(message.role()))
                    .anyMatch(message -> message.content().contains(rendered)), "Source bodies remain untrusted tool-role data");
        }
        int anchor = -1;
        for (int index = 0; index < synthesis.messages.size(); index++) {
            ChatMessage message = synthesis.messages.get(index);
            if ("system".equals(message.role()) && message.content().startsWith("[CurrentTurnCapability]")) anchor = index;
            if (!"tool".equals(message.role())) continue;
            assertTrue(anchor >= 0 && anchor < index, "The budget anchor must precede every required result");
            ChatMessage originalCall = synthesis.messages.get(index - 1);
            assertEquals("assistant", originalCall.role());
            assertEquals(message.toolCallId(), originalCall.toolCalls().getFirst().id());
        }
        // Bounded pending-read requests no longer need the generic history-compaction branch.
        // When a carrier was compacted, restoration must still preserve its original call ID;
        // deliberately compacted histories are exercised by ReadOnlyEvidenceContextTest.
        for (ChatMessage retained : run.result().messages()) {
            if (!"tool".equals(retained.role()) || !retained.content().startsWith("[compacted:")) continue;
            assertTrue(synthesis.messages.stream().anyMatch(restored -> "tool".equals(restored.role())
                    && restored.toolCallId().equals(retained.toolCallId())
                    && !restored.content().startsWith("[compacted:")), "Restore a compacted carrier under its original call ID");
        }
        assertProviderLineage(run);
        assertNoMutations(run, before);
    }

    private static void assertProviderLineage(Run run) throws Exception {
        List<TurnTraceEvent> selections = run.trace().events().stream()
                .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type())).toList();
        assertEquals(1, selections.size());
        var selected = selections.getFirst();
        assertEquals("MODEL_PROVIDER", selected.data().get("origin"));
        String answerHash = "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                        ANSWER.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(answerHash, selected.data().get("answerHash"));
        var captures = run.trace().events().stream()
                .filter(event -> "GENERATION_RESPONSE_CAPTURED".equals(event.type())).toList();
        assertEquals(run.requests().size(), captures.size());
        var last = captures.getLast();
        assertEquals(answerHash, last.data().get("assistantHash"));
        assertEquals(last.data().get("generationId"), selected.data().get("sourceGenerationId"));
        assertEquals(last.data().get("attemptId"), selected.data().get("sourceAttemptId"));
        assertNotEquals(run.initial().provenance().generationId(), selected.data().get("sourceGenerationId"));
        assertEquals("PROVIDER_EOS", last.data().get("completion"));
        assertEquals(0, last.data().get("nativeToolCallCount"));
    }

    private void assertNoMutations(Run run, Map<String, byte[]> before) throws Exception {
        assertEquals(0, run.result().mutatingToolSuccesses());
        assertTrue(run.result().toolOutcomes().stream().noneMatch(outcome -> outcome.mutating()
                || outcome.mutationEffects().hasEffects()));
        try (var paths = Files.list(workspace)) {
            assertEquals(before.keySet(), paths.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        for (var entry : before.entrySet())
            assertArrayEquals(entry.getValue(), Files.readAllBytes(workspace.resolve(entry.getKey())), entry.getKey());
    }

    private Map<String, byte[]> createFiles(boolean omitSentinel) throws Exception {
        var before = new LinkedHashMap<String, byte[]>();
        for (String target : TARGETS) {
            if (omitSentinel && "scripts.js".equals(target)) continue;
            Files.writeString(workspace.resolve(target), CONTENTS.get(target));
            before.put(target, Files.readAllBytes(workspace.resolve(target)));
        }
        return before;
    }

    private static List<ChatRequest> synthesisRequests(Run run) {
        return run.requests().stream().filter(request -> request.controls.debugTags()
                .contains("bounded-read-only-synthesis")).toList();
    }

    private static LlmClient.StreamResult read(String id, String path) {
        return new LlmClient.StreamResult("", List.of(
                new ChatMessage.NativeToolCall(id, "talos.read_file", Map.of("path", path))));
    }

    private static LlmClient.StreamResult answer(String text) {
        return new LlmClient.StreamResult(text, List.of());
    }

    private static String renderedBody(String contents) {
        var lines = contents.lines().toList();
        var rendered = new StringBuilder();
        for (int index = 0; index < lines.size(); index++)
            rendered.append(index + 1).append(" | ").append(lines.get(index)).append('\n');
        return rendered.toString().stripTrailing();
    }

    private record Run(ToolCallLoop.LoopResult result, List<ChatRequest> requests,
                       LocalTurnTrace trace, LlmClient.StreamResult initial) {}
}
