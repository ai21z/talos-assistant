package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InspectCompletenessRetryTest {

    @TempDir
    Path lineageWorkspace;

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void verificationDiagnosisRetainsReadBodiesAcrossGenericRetryAndRecompactsOnFailure(boolean failRetry)
            throws Exception {
        beginTrace("cross-loop-read-evidence-" + failRetry);
        Path workspace = lineageWorkspace;
        Map<String, String> bodies = Map.of(
                "README.md", "README_EVIDENCE says script.js is loaded.\n",
                "index.html", "<button id=\"calculate\">INDEX_EVIDENCE</button><script src=\"script.js\"></script>\n",
                "script.js", "const SCRIPT_EVIDENCE = document.querySelector('#calc');\n",
                "scripts.js", "const SCRIPTS_EVIDENCE = 'not loaded';\n");
        for (var entry : bodies.entrySet()) Files.writeString(workspace.resolve(entry.getKey()), entry.getValue());
        String request = "Read README.md, index.html, script.js and scripts.js. Explain why the Calculate "
                + "button fails, give the correct selector and the BMI for 72 kg and 1.8 m, and distinguish "
                + "the two JavaScript files. Do not edit files or claim tests were run.";
        // Explicit verification stays outside the new non-verifying REQUIRED-read continuation.
        // Preserve coverage of the generic retry's real compaction and cross-loop evidence handoff.
        var contract = new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, true,
                bodies.keySet(), java.util.Set.of(), request);
        var plan = plan(contract);
        var messages = messages(request);
        TurnTaskContractCapture.set(contract);
        List<ChatMessage.NativeToolCall> firstReads = List.of("README.md", "index.html", "script.js").stream()
                .map(path -> new ChatMessage.NativeToolCall("original-" + path, "talos.read_file",
                        Map.<String, Object>of("path", path))).toList();
        String early = "script.js selects #calc, but index.html defines #calculate. Read the remaining sentinel.";
        String finalAnswer = "README.md identifies script.js as loaded. index.html defines #calculate, "
                + "not #calc. scripts.js is the unrelated sentinel. BMI is 22.2. No files edited or tests run.\n\n"
                + "```html\n<button id=\"calculate\">Calculate</button>\n```";
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                new LlmClient.StreamResult("", List.of(firstReads.get(0))),
                new LlmClient.StreamResult("", List.of(firstReads.get(1))),
                new LlmClient.StreamResult("", List.of(firstReads.get(2))),
                new LlmClient.StreamResult(early, List.of()),
                new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                        "missing-scripts", "talos.read_file", Map.of("path", "scripts.js")))),
                new LlmClient.StreamResult(finalAnswer, List.of())), 16_384);
        try (LlmClient llm = recorded.client()) {
            Context ctx = providerContext(workspace, llm);
            var initial = TurnModelDispatcher.dispatchBuffered(ctx, messages, plan);
            var original = ctx.toolCallLoop().run(initial.text(), initial.toolCalls(), initial.provenance(),
                    messages, workspace, ctx);
            assertEquals(3, original.toolsInvoked());
            assertEquals(List.of("scripts.js"), InspectCompletenessRetry.missingReads(workspace, plan, original));
            var before = List.copyOf(messages);
            var readme = messages.stream().filter(m -> "original-README.md".equals(m.toolCallId())).findFirst().orElseThrow();
            assertTrue(readme.content().startsWith("[compacted:"), "fixture must cross the real compaction boundary");
            AtomicReference<List<ChatMessage>> retryCarrier = new AtomicReference<>();
            var result = InspectCompletenessRetry.retryIfNeeded(early, messages, plan, original, workspace, ctx,
                    sent -> {
                        retryCarrier.set(sent);
                        assertEvidenceBodies(sent, List.of("README_EVIDENCE", "INDEX_EVIDENCE", "SCRIPT_EVIDENCE"));
                        assertEquals(3, sent.stream().filter(m -> "tool".equals(m.role())).count());
                        if (failRetry) throw new IllegalStateException("bounded fixture failure");
                        return TurnModelDispatcher.dispatchBuffered(ctx, sent, plan);
                    });
            assertEquals(before, messages, "the caller history must not become permanently expanded");
            assertTrue(retryCarrier.get().stream().filter(m -> "original-README.md".equals(m.toolCallId()))
                    .findFirst().orElseThrow().content().startsWith("[compacted:"));
            if (failRetry) {
                assertEquals(early, result.answer());
                assertNull(result.loopResult());
                assertEquals(4, recorded.requests().size());
            } else {
                assertEquals(finalAnswer, result.answer());
                assertEquals(4, result.loopResult().toolsInvoked(), "prior reads are imported, never rerun or double-counted");
                assertEquals(4, result.loopResult().toolOutcomes().size());
                assertEquals(bodies.keySet(), java.util.Set.copyOf(result.loopResult().readPaths()));
                assertEquals(bodies.keySet(), result.loopResult().readFileBodies().keySet());
                assertEquals(6, recorded.requests().size());
                var finalRequest = recorded.requests().getLast().messages;
                assertEvidenceBodies(finalRequest, List.of("README_EVIDENCE", "INDEX_EVIDENCE", "SCRIPT_EVIDENCE", "SCRIPTS_EVIDENCE"));
                assertEquals(4, finalRequest.stream().filter(m -> "tool".equals(m.role())).count());
                for (var call : firstReads) {
                    assertEquals(1, finalRequest.stream().filter(m -> call.id().equals(m.toolCallId())).count());
                    assertEquals(1, finalRequest.stream().filter(ChatMessage::hasNativeToolCalls)
                            .flatMap(m -> m.toolCalls().stream()).filter(c -> c.id().equals(call.id())).count());
                }
                LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.answer());
                var trace = LocalTurnTraceCapture.complete();
                var terminal = events(trace, "TERMINAL_ANSWER_SELECTED").getFirst();
                assertEquals("MODEL_PROVIDER", terminal.data().get("origin"),
                        "exact fenced answer must survive the real loop finalizer with its provider lineage");
                assertFalse(events(trace, "ANSWER_TRANSFORMED").stream()
                        .anyMatch(event -> "TOOL_LOOP_FINALIZATION".equals(event.data().get("reasonCode"))),
                        "the answer must not be destructively re-projected after client EOS");
            }
            for (var entry : bodies.entrySet()) assertEquals(entry.getValue(), Files.readString(workspace.resolve(entry.getKey())));
        }
    }

    private static void assertEvidenceBodies(List<ChatMessage> messages, List<String> markers) {
        for (String marker : markers) {
            assertEquals(1, messages.stream().filter(m -> "tool".equals(m.role())
                    && m.content().contains(marker)).count(), marker + " must remain in its real tool carrier");
            assertFalse(messages.stream().filter(m -> "system".equals(m.role()))
                    .anyMatch(m -> m.content().contains(marker)), "file bytes never gain system authority");
        }
    }

    @ParameterizedTest
    @EnumSource(value = ToolCallLoop.ReadOnlySynthesisStatus.class, names = {"ACCEPTED", "REJECTED"})
    void completedSynthesisCannotBeResetByDirectCompletenessRetry(ToolCallLoop.ReadOnlySynthesisStatus status)
            throws Exception {
        var fixture = inspectLineageFixture(lineageWorkspace);
        var original = fixture.originalLoop();
        var attempted = new ToolCallLoop.LoopResult(original.finalAnswer(), original.iterations(),
                original.toolsInvoked(), original.toolNames(), original.messages(), original.failedCalls(),
                original.retriedCalls(), original.hitIterLimit(), original.mutatingToolSuccesses(),
                original.readPaths(), 0, 0, 0, 0, original.failureDecision(), original.toolOutcomes(),
                original.readFileBodies(), false, status);
        var ctx = context(lineageWorkspace, "must not be consumed");
        try (var llm = ctx.llm()) {
            var result = InspectCompletenessRetry.retryIfNeeded(fixture.answer(), messages(fixture.request()),
                    plan(fixture.request()), attempted, lineageWorkspace, ctx,
                    ignored -> { throw new AssertionError("a prior synthesis forbids another dispatch"); });
            assertEquals(fixture.answer(), result.answer());
            assertNull(result.loopResult());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_ONLY_INSPECTION_UNAVAILABLE",
            "required diagnosis reads reached the tool iteration limit",
            "tool-call execution authority was rejected"})
    void terminalInspectionStopCannotBeReopenedByMissingEvidence(String reason) throws Exception {
        var fixture = inspectLineageFixture(lineageWorkspace);
        var original = fixture.originalLoop();
        var stopped = new ToolCallLoop.LoopResult(original.finalAnswer(), original.iterations(),
                original.toolsInvoked(), original.toolNames(), original.messages(), original.failedCalls(),
                original.retriedCalls(), original.hitIterLimit(), original.mutatingToolSuccesses(),
                original.readPaths(), 0, 0, 0, 0,
                dev.talos.runtime.failure.FailureDecision.stop(
                        dev.talos.runtime.failure.FailureAction.ASK_USER, reason),
                original.toolOutcomes(), original.readFileBodies(), false,
                ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED);
        var retryPlan = plan(fixture.request());
        assertFalse(InspectCompletenessRetry.missingReads(lineageWorkspace, retryPlan, stopped).isEmpty(),
                "The fixture must still have evidence that would otherwise trigger a retry");
        var ctx = context(lineageWorkspace, "must not be consumed");
        try (var llm = ctx.llm()) {
            var result = InspectCompletenessRetry.retryIfNeeded(fixture.answer(), messages(fixture.request()),
                    retryPlan, stopped, lineageWorkspace, ctx,
                    ignored -> { throw new AssertionError("an explicit stop forbids another dispatch"); });
            assertEquals(fixture.answer(), result.answer());
            assertNull(result.loopResult());
        }
    }

    @Test
    void missingReadsIncludesLinkedScriptButSkipsProtectedAndExternalScripts(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <body>
                    <script src="https://cdn.example.invalid/app.js"></script>
                    <script src="//cdn.example.invalid/other.js"></script>
                    <script src=".env.secret.js"></script>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve(".env.secret.js"), "const secret = 'protected';\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('public');\n");
        ToolCallLoop.LoopResult loopResult = loopResult(
                "unused",
                1,
                1,
                List.of("talos.read_file"),
                List.of("index.html"),
                List.of(outcome("talos.read_file", "index.html")));

        List<String> missing = InspectCompletenessRetry.missingReads(workspace, loopResult);

        assertEquals(List.of("script.js"), missing);
    }

    @Test
    void retryMergesOriginalAndRetryReadEvidenceWithoutDuplicatingOriginalSummary(@TempDir Path workspace)
            throws Exception {
        beginTrace("inspect-retry-receipt");
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html><body><script src="script.js"></script></body></html>
                """);
        Files.writeString(workspace.resolve("script.js"), "console.log('script evidence');\n");
        List<ChatMessage> messages = messages("Read the main files and verify the web page.");
        ToolCallLoop.LoopResult original = loopResult(
                "HTML-only answer.",
                1,
                1,
                List.of("talos.read_file"),
                List.of("index.html"),
                List.of(outcome("talos.read_file", "index.html")));
        String request = "Read the main files and verify the web page.";
        CurrentTurnPlan retryPlan = plan(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromMessages(messages),
                messages);
        var call = new ChatMessage.NativeToolCall(
                "call_1", "talos.read_file", Map.of("path", "script.js"));
        var provider = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("", List.of(call)),
                        new LlmClient.StreamResult("Script evidence answer.", List.of())),
                16_384);
        Context ctx = providerContext(workspace, provider.client());
        AtomicReference<List<ChatMessage>> retryMessages = new AtomicReference<>();

        InspectCompletenessRetry.Result result = InspectCompletenessRetry.retryIfNeeded(
                "HTML-only answer.",
                messages,
                retryPlan,
                original,
                workspace,
                ctx,
                sentMessages -> {
                    retryMessages.set(List.copyOf(sentMessages));
                    return TurnModelDispatcher.dispatchBuffered(
                            ctx, sentMessages, retryPlan);
                });

        assertEquals("Script evidence answer.", result.answer());
        assertNotNull(result.loopResult());
        assertEquals(List.of("index.html", "script.js"), result.loopResult().readPaths());
        assertEquals(List.of("talos.read_file", "talos.read_file"), result.loopResult().toolNames());
        assertEquals(2, result.loopResult().toolsInvoked());
        assertEquals(2, result.loopResult().iterations());
        assertEquals(1, countOccurrences(result.extraSummary(), "[Used "));
        assertTrue(result.extraSummary().contains("[Used 2 tool(s): talos.read_file | 2 iteration(s)"),
                result.extraSummary());
        assertTrue(result.extraSummary().contains("read: index.html, script.js"), result.extraSummary());
        String prompt = retryMessages.get().get(3).content();
        assertTrue(prompt.contains("You started diagnosing the workspace"), prompt);
        assertTrue(prompt.contains("Read these files now before answering: script.js"), prompt);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        var parsed = trace.events().stream()
                .filter(event -> "TOOL_CALL_PARSED".equals(event.type()))
                .filter(event -> "talos.read_file".equals(event.toolName()))
                .filter(event -> event.data().containsKey("providerGenerationId"))
                .findFirst().orElseThrow();
        var completed = trace.events().stream()
                .filter(event -> "GENERATION_COMPLETED".equals(event.type()))
                .filter(event -> event.data().get("generationId").equals(
                        parsed.data().get("providerGenerationId")))
                .findFirst().orElseThrow();
        assertEquals(completed.data().get("selectedAttemptId"),
                parsed.data().get("providerAttemptId"));
        assertEquals(1, parsed.data().get("providerProposalOrdinal"));
    }

    @ParameterizedTest(name = "{0} retry restores original inspect answer owner")
    @EnumSource(FallbackKind.class)
    void fallbackRetryRestoresOriginalInspectAnswerOwner(FallbackKind fallbackKind) throws Exception {
        beginTrace("inspect-completeness-" + fallbackKind.name().toLowerCase());
        InspectLineageFixture fixture = inspectLineageFixture(lineageWorkspace);
        LlmClient.StreamResult originalProviderResult = providerResult(fixture.answer(), List.of());

        InspectCompletenessRetry.Result result = InspectCompletenessRetry.retryIfNeeded(
                fixture.answer(),
                messages(fixture.request()),
                plan(fixture.request()),
                fixture.originalLoop(),
                lineageWorkspace,
                context(lineageWorkspace, "unused scripted answer"),
                ignored -> fallbackProviderResult(fallbackKind, fixture.answer()));

        assertEquals(fixture.answer(), result.answer(),
                "inspect fallback must preserve the original answer bytes");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.answer());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertRestoredProviderOwner(
                trace,
                originalProviderResult.provenance(),
                fallbackKind.restorationReason());
    }

    @Test
    void wrappedCancellationEscapesInspectCompletenessRetry() throws Exception {
        InspectLineageFixture fixture = inspectLineageFixture(lineageWorkspace);
        RuntimeException wrapped = new RuntimeException(
                new CancellationException("cancel inspect-completeness retry"));

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                InspectCompletenessRetry.retryIfNeeded(
                        fixture.answer(),
                        messages(fixture.request()),
                        plan(fixture.request()),
                        fixture.originalLoop(),
                        lineageWorkspace,
                        context(lineageWorkspace, "unused scripted answer"),
                        ignored -> {
                            throw wrapped;
                        }));

        assertSame(wrapped, thrown,
                "retry cancellation must preserve the caller-owned runtime wrapper");
    }

    @Test
    void acceptedDifferentInspectProviderRetryRetainsRetryOwnerWithoutRestoration() throws Exception {
        beginTrace("inspect-completeness-accepted");
        InspectLineageFixture fixture = inspectLineageFixture(lineageWorkspace);
        providerResult(fixture.answer(), List.of());
        AtomicReference<ProviderResponseCapture.Receipt> retryReceipt = new AtomicReference<>();
        String accepted = "Grounded answer from the additional file evidence.";

        InspectCompletenessRetry.Result result = InspectCompletenessRetry.retryIfNeeded(
                fixture.answer(),
                messages(fixture.request()),
                plan(fixture.request()),
                fixture.originalLoop(),
                lineageWorkspace,
                context(lineageWorkspace, "unused scripted answer"),
                ignored -> {
                    LlmClient.StreamResult retry = providerResult(accepted, List.of());
                    retryReceipt.set(retry.provenance());
                    return retry;
                });

        assertEquals(accepted, result.answer(), "accepted retry bytes must remain unchanged");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.answer());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertTrue(events(trace, "ANSWER_CANDIDATE_RESTORED").isEmpty(),
                "accepted retry must not restore the original candidate");
        assertTerminalProviderOwner(trace, retryReceipt.get());
    }

    @Test
    void projectSummaryRequestsReadmeAfterUnrelatedRead(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Local project facts.\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");
        ToolCallLoop.LoopResult loopResult = loopResult(
                "The project is a script.",
                2,
                2,
                List.of("talos.list_dir", "talos.read_file"),
                List.of("script.js"),
                List.of(
                        outcome("talos.list_dir", "."),
                        outcome("talos.read_file", "script.js")));

        List<String> missing = InspectCompletenessRetry.missingReads(
                workspace,
                plan("What does this project do?"),
                loopResult);

        assertEquals(List.of("README.md"), missing);
    }

    @Test
    void projectSummaryRetryReadsAnchorAndMergesEvidence(@TempDir Path workspace) throws Exception {
        beginTrace("inspect-project-summary-retry-receipt");
        Files.writeString(workspace.resolve("README.md"), "A small local qualification project.\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");
        ToolCallLoop.LoopResult original = loopResult(
                "The project is a script.",
                2,
                2,
                List.of("talos.list_dir", "talos.read_file"),
                List.of("script.js"),
                List.of(
                        outcome("talos.list_dir", "."),
                        outcome("talos.read_file", "script.js")));
        String request = "What does this project do?";
        CurrentTurnPlan retryPlan = plan(request);
        List<ChatMessage> messages = messages(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromMessages(messages),
                messages);
        var call = new ChatMessage.NativeToolCall(
                "call_readme",
                "talos.read_file",
                Map.of("path", "README.md"));
        var provider = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("", List.of(call)),
                        new LlmClient.StreamResult(
                                "This is a small local qualification project.",
                                List.of())),
                16_384);
        Context ctx = providerContext(workspace, provider.client());
        AtomicReference<List<ChatMessage>> retryMessages = new AtomicReference<>();

        InspectCompletenessRetry.Result result = InspectCompletenessRetry.retryIfNeeded(
                original.finalAnswer(),
                messages,
                retryPlan,
                original,
                workspace,
                ctx,
                sentMessages -> {
                    retryMessages.set(List.copyOf(sentMessages));
                    return TurnModelDispatcher.dispatchBuffered(
                            ctx, sentMessages, retryPlan);
                });

        assertEquals("This is a small local qualification project.", result.answer());
        assertNotNull(result.loopResult());
        assertEquals(List.of("script.js", "README.md"), result.loopResult().readPaths());
        assertEquals(3, result.loopResult().toolsInvoked());
        String prompt = retryMessages.get().get(3).content();
        assertTrue(prompt.contains("Read these files now before answering: README.md"), prompt);
    }

    @Test
    void projectSummaryDoesNotRequestReadmeAfterAnchorEvidence(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Local project facts.\n");
        ToolCallLoop.LoopResult loopResult = loopResult(
                "A local project.",
                1,
                1,
                List.of("talos.read_file"),
                List.of("README.md"),
                List.of(outcome("talos.read_file", "README.md")));

        List<String> missing = InspectCompletenessRetry.missingReads(
                workspace,
                plan("What does this project do?"),
                loopResult);

        assertEquals(List.of(), missing);
    }

    @Test
    void diagnosisDoesNotInheritProjectSummaryAnchor(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Local project facts.\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");
        TaskContract diagnosis = new TaskContract(
                TaskType.DIAGNOSE_ONLY,
                false,
                false,
                false,
                java.util.Set.of(),
                java.util.Set.of(),
                "Diagnose why script.js fails.");
        ToolCallLoop.LoopResult loopResult = loopResult(
                "The script has a syntax error.",
                1,
                1,
                List.of("talos.read_file"),
                List.of("script.js"),
                List.of(outcome("talos.read_file", "script.js")));

        List<String> missing = InspectCompletenessRetry.missingReads(
                workspace,
                plan(diagnosis),
                loopResult);

        assertEquals(List.of(), missing);
    }

    private static CurrentTurnPlan plan(String request) {
        return plan(TaskContractResolver.fromUserRequest(request));
    }

    private static CurrentTurnPlan plan(TaskContract contract) {
        return CurrentTurnPlan.compatibility(
                contract,
                ExecutionPhase.INSPECT,
                List.of("talos.read_file"),
                List.of("talos.read_file"),
                List.of());
    }

    private static Context context(Path workspace, String finalAnswer) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        return Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(finalAnswer)))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(processor, 5))
                .build();
    }

    private static Context providerContext(Path workspace, LlmClient llm) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        return Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(processor, 5))
                .nativeToolSpecs(specsFrom(registry))
                .build();
    }

    private static List<ToolSpec> specsFrom(ToolRegistry registry) {
        return registry.descriptors().stream()
                .map(descriptor -> new ToolSpec(
                        descriptor.name(),
                        descriptor.description(),
                        descriptor.parametersSchema()))
                .toList();
    }

    private static List<ChatMessage> messages(String request) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(request));
        return messages;
    }

    private static ToolCallLoop.LoopResult loopResult(
            String finalAnswer,
            int iterations,
            int toolsInvoked,
            List<String> toolNames,
            List<String> readPaths,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        return new ToolCallLoop.LoopResult(
                finalAnswer,
                iterations,
                toolsInvoked,
                toolNames,
                List.of(),
                0,
                0,
                false,
                0,
                readPaths,
                0,
                0,
                0,
                0,
                outcomes);
    }

    private static ToolCallLoop.ToolOutcome outcome(String toolName, String target) {
        return new ToolCallLoop.ToolOutcome(toolName, target, true, false, false, "read " + target, "");
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while (value != null && (index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static LlmClient.StreamResult providerResult(
            String text,
            List<ChatMessage.NativeToolCall> calls) {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeTerminal(
                attempt, ProviderTransportHandoff.DispatchEvidence.unbound());
        ProviderResponseCapture.Receipt receipt = attempt.respond(new ProviderResponseCapture.Response(
                text,
                calls,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false,
                false));
        generation.complete(receipt);
        return new LlmClient.StreamResult(
                text, calls, "", "", ProviderResponseCapture.AbortOrigin.NONE,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false, false, receipt);
    }

    private static void beginTrace(String id) {
        LocalTurnTraceCapture.begin(
                id, "session", 1, "2026-07-16T00:00:00Z", "workspace",
                "auto", "test", "model", "retry receipt");
    }

    private enum FallbackKind {
        BLANK("SPECULATIVE_RETRY_EMPTY"),
        EQUAL("SPECULATIVE_RETRY_REJECTED"),
        EXCEPTION("SPECULATIVE_RETRY_FAILED");

        private final String restorationReason;

        FallbackKind(String restorationReason) {
            this.restorationReason = restorationReason;
        }

        String restorationReason() {
            return restorationReason;
        }
    }

    private record InspectLineageFixture(
            String request,
            String answer,
            ToolCallLoop.LoopResult originalLoop) {}

    private static InspectLineageFixture inspectLineageFixture(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"),
                "<!DOCTYPE html><html><body><script src=\"script.js\"></script></body></html>");
        Files.writeString(workspace.resolve("script.js"), "console.log('evidence');\n");
        String request = "Read the main files and verify the web page.";
        String answer = "HTML-only answer.";
        return new InspectLineageFixture(
                request,
                answer,
                loopResult(
                        answer,
                        1,
                        1,
                        List.of("talos.read_file"),
                        List.of("index.html"),
                        List.of(outcome("talos.read_file", "index.html"))));
    }

    private static LlmClient.StreamResult fallbackProviderResult(
            FallbackKind fallbackKind,
            String original) {
        return switch (fallbackKind) {
            case BLANK -> providerResult("", List.of());
            case EQUAL -> providerResult(original, List.of());
            case EXCEPTION -> {
                failProviderGeneration();
                throw new IllegalStateException("injected inspect-completeness retry failure");
            }
        };
    }

    private static void failProviderGeneration() {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        generation.fail(ProviderResponseCapture.Failure.PROVIDER_EXCEPTION);
    }

    private static void assertRestoredProviderOwner(
            LocalTurnTrace trace,
            ProviderResponseCapture.Receipt originalReceipt,
            String expectedReason) {
        List<TurnTraceEvent> restorations = events(trace, "ANSWER_CANDIDATE_RESTORED");
        assertEquals(1, restorations.size(), "fallback must emit exactly one closed restoration");
        TurnTraceEvent restoration = restorations.getFirst();
        assertEquals("MODEL_PROVIDER", restoration.data().get("origin"));
        assertEquals(originalReceipt.generationId(), restoration.data().get("sourceGenerationId"));
        assertEquals(originalReceipt.attemptId(), restoration.data().get("sourceAttemptId"));
        assertEquals(expectedReason, restoration.data().get("reasonCode"));
        assertTrue(events(trace, "ANSWER_TRANSFORMED").isEmpty(),
                "exact original fallback bytes require no transform");
        assertTerminalProviderOwner(trace, originalReceipt);
    }

    private static void assertTerminalProviderOwner(
            LocalTurnTrace trace,
            ProviderResponseCapture.Receipt receipt) {
        TurnTraceEvent terminal = events(trace, "TERMINAL_ANSWER_SELECTED").getFirst();
        assertEquals("MODEL_PROVIDER", terminal.data().get("origin"));
        assertEquals(receipt.generationId(), terminal.data().get("sourceGenerationId"));
        assertEquals(receipt.attemptId(), terminal.data().get("sourceAttemptId"));
    }

    private static List<TurnTraceEvent> events(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(event -> type.equals(event.type()))
                .toList();
    }
}
