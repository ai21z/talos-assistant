package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Read-only acquisition, supporting-evidence retention and bounded recovery behavior.
 * Real task resolution, native provider receipts, ToolCallLoop and ReadFileTool are used.
 * Only the provider choices and prose are scripted. File bodies are synthetic saved-audit
 * fixtures with pinned hashes. These tests stop at the loop boundary, before assistant
 * settlement and saved history. Neither EOS nor selection of the scripted candidate proves
 * semantic completeness, Python execution, live-model quality or release acceptance.
 */
class ReadOnlyTransitionBehaviorTest {
    private static final String FIRST_REQUEST =
            "This tiny offline backup example seems to stop one attempt early. Read README.md, settings.json and retry.py. "
                    + "Explain the likely cause and propose the smallest fix. Don't change files yet.";
    private static final String FRESH_REQUEST =
            "Please inspect retry.py again. With the configured retry limit, list the attempt numbers that should run "
                    + "and explain when should_retry becomes false. Use the current files, not just your previous answer.";
    private static final Map<String, String> SAVED_CONTENTS = Map.of(
            "README.md", "# Moss Backup\n\nThis is a tiny offline backup example. The retry_limit in settings.json "
                    + "counts retries after the first attempt. Attempt numbers start at one. The caller runs the first attempt, "
                    + "then calls should_retry after each failed attempt. No background service or network is used.\n",
            "settings.json", "{\n  \"retry_limit\": 2,\n  \"destination\": \"backup\",\n  \"network\": false\n}\n",
            "retry.py", "def total_attempts(retry_limit):\n"
                    + "    \"\"\"Return the first attempt plus the configured retries.\"\"\"\n"
                    + "    return retry_limit\n\n\n"
                    + "def should_retry(attempt_number, retry_limit):\n"
                    + "    return attempt_number < total_attempts(retry_limit)\n");
    private static final Map<String, String> SAVED_HASHES = Map.of(
            "README.md", "d7a0a362ed998ee685a9a1b2da9184632fadf4cc13cdcc9563648f77ce75913b",
            "settings.json", "5e6195c3284a187d6bfcd17ca1ac166d1121d7ca06568fa5823d8953bfc66d1b",
            "retry.py", "99e54186e663e3e503d72646a5eefba2bb5e78ee0c4a7b20a56eed07df3c4c60");
    private static final Layout SAVED = new Layout("README.md", "settings.json", "retry.py", SAVED_CONTENTS,
            SAVED_HASHES);
    private static final Layout RENAMED = new Layout("guide.md", "options.json", "engine.py", Map.of(
            "guide.md", SAVED_CONTENTS.get("README.md").replace("settings.json", "options.json"),
            "options.json", SAVED_CONTENTS.get("settings.json"),
            "engine.py", SAVED_CONTENTS.get("retry.py")), Map.of(
            "guide.md", "50f541acd72d362f839c72b72a8a4153f3262ab8fbf09f7629d56bac09c4e14a",
            "options.json", SAVED_HASHES.get("settings.json"),
            "engine.py", SAVED_HASHES.get("retry.py")));
    private static final String USEFUL_SCRIPTED_ANSWER =
            "The configured retry_limit is 2. It counts retries after the initial attempt, and attempt numbers start at one. "
                    + "Assuming every attempt fails and the documented caller continues while should_retry permits it, "
                    + "the unchanged implementation runs attempts 1 and 2, then should_retry(2, 2) is false. "
                    + "The smallest proposed fix is return retry_limit + 1 in total_attempts. "
                    + "With that correction and the same all-attempts-fail assumption, attempts 1, 2 and 3 run, "
                    + "then should_retry(3, 2) is false. This is static source reasoning. No Python execution was performed.";

    @TempDir Path workspace;
    private final List<LlmClient> clients = new ArrayList<>();

    @AfterEach void close() {
        clients.forEach(LlmClient::close);
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test void savedFreshPromptAllowsSettingsAndCallerAfterNamedTarget() throws Exception {
        assertTargetFirstAcquisition(SAVED, FRESH_REQUEST);
    }

    @Test void paraphrasedFreshPromptAllowsDependenciesAfterRenamedTarget() throws Exception {
        assertTargetFirstAcquisition(RENAMED,
                "Inspect engine.py using the current files. Explain the configured retry count, the one-based "
                        + "attempt sequence and the point at which should_retry returns false. Leave files unchanged.");
    }

    @Test void callerThenSettingsBeforeNamedTargetSurviveInAnswerInput() throws Exception {
        assertSupportingRetention(SAVED, FRESH_REQUEST,
                List.of(SAVED.caller(), SAVED.settings(), SAVED.target()));
    }

    @Test void settingsThenCallerBeforeRenamedTargetSurviveInAnswerInput() throws Exception {
        assertSupportingRetention(RENAMED, FRESH_REQUEST.replace("retry.py", "engine.py"),
                List.of(RENAMED.settings(), RENAMED.caller(), RENAMED.target()));
    }

    @Test void savedThreeFileQaGetsOneBoundedRecoveryAtExhaustedToolBudget() throws Exception {
        assertBudgetRecovery(SAVED, FIRST_REQUEST,
                List.of(SAVED.caller(), SAVED.settings(), SAVED.target()));
    }

    @Test void ordinaryQaParaphraseGetsTheSameBoundedRecovery() throws Exception {
        assertBudgetRecovery(SAVED,
                "Read README.md, settings.json and retry.py. Why does this backup example stop before its retry "
                        + "allowance is used, and what is the smallest proposed correction? Leave files unchanged.",
                List.of(SAVED.caller(), SAVED.settings(), SAVED.target()));
    }

    @Test void renamedThreeFileQaGetsTheSameBoundedRecovery() throws Exception {
        assertBudgetRecovery(RENAMED,
                FIRST_REQUEST.replace("README.md", "guide.md").replace("settings.json", "options.json")
                        .replace("retry.py", "engine.py"),
                List.of(RENAMED.caller(), RENAMED.settings(), RENAMED.target()));
    }

    @Test void qaReadOrderDoesNotChangeRecoveryOrDropEarlierBodies() throws Exception {
        assertBudgetRecovery(SAVED, FIRST_REQUEST,
                List.of(SAVED.target(), SAVED.settings(), SAVED.caller()));
    }

    private void assertTargetFirstAcquisition(Layout layout, String prompt) throws Exception {
        List<String> order = List.of(layout.target(), layout.settings(), layout.caller());
        Run run = run(layout, prompt, TaskType.DIAGNOSE_ONLY, Set.of(layout.target()), 6, order);
        assertNoMutations(run, layout);
        assertReadObserved(run, layout, 0);
        assertTrue(run.requests().size() >= 2, "A continuation is required after the first real read");
        ChatRequest afterNamedTarget = run.requests().get(1);
        assertTrue(afterNamedTarget.tools.stream().anyMatch(spec -> "talos.read_file".equals(spec.name())),
                "ACQUISITION: reading the named target must leave the authorized read tool available for dependencies");
        assertNotEquals(ToolChoiceMode.NONE, afterNamedTarget.controls.toolChoice(),
                "ACQUISITION: declared-target completion alone must not force response-only generation");
        assertAllReadsObserved(run, layout);
        assertAllBodiesBound(run.requests().getLast(), layout, order,
                "RETENTION: every acquired source must be available to the final answer generation");
        assertSelectedUsefulCandidate(run);
        assertBounded(run, 6);
    }

    private void assertSupportingRetention(Layout layout, String prompt, List<String> order) throws Exception {
        Run run = run(layout, prompt, TaskType.DIAGNOSE_ONLY, Set.of(layout.target()), 6, order);
        assertNoMutations(run, layout);
        assertAllReadsObserved(run, layout);
        assertAllBodiesBound(run.requests().getLast(), layout, order,
                "RETENTION: acquired supporting files outside the declared target union must survive in answer input");
        assertSelectedUsefulCandidate(run);
        assertBounded(run, 6);
    }

    private void assertBudgetRecovery(Layout layout, String prompt, List<String> order) throws Exception {
        Run run = run(layout, prompt, TaskType.READ_ONLY_QA, layout.contents().keySet(), 3, order);
        assertNoMutations(run, layout);
        assertAllReadsObserved(run, layout);
        List<ChatRequest> responseOnly = responseOnlyRequests(run);
        assertEquals(1, responseOnly.size(),
                "RECOVERY: eligible read-only QA must get one response-only generation after the configured loop budget");
        ChatRequest recovery = responseOnly.getFirst();
        assertSame(run.requests().getLast(), recovery, "No provider continuation may follow the one-shot recovery");
        assertTrue(recovery.controls.singleAttempt(), "Recovery cannot restart a provider retry budget");
        assertTrue(recovery.controls.maxOutputTokens() > 0 && recovery.controls.maxOutputTokens() <= 4_096,
                "The response-only recovery must retain the bounded output allowance");
        assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, recovery.controls.toolAuthorityMode());
        assertAllBodiesBound(recovery, layout, order,
                "RETENTION: the bounded recovery must receive all three current source bodies");
        assertSelectedUsefulCandidate(run);
        assertBounded(run, 3);
    }

    private Run run(Layout layout, String request, TaskType expectedType, Set<String> expectedTargets,
                    int maxIterations, List<String> readOrder) throws Exception {
        createFiles(layout);
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.begin("read-only-transition-behavior", "tests", 1,
                "2026-09-27T00:00:00Z", "workspace", "auto", "llama_cpp", "fixture", request);
        Config config = new Config();
        var responses = new ArrayList<LlmClient.StreamResult>();
        for (int index = 0; index < readOrder.size(); index++)
            responses.add(new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                    callId(index), "talos.read_file", Map.of("path", readOrder.get(index))))));
        responses.add(new LlmClient.StreamResult(USEFUL_SCRIPTED_ANSWER, List.of()));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(config, responses, 16_384);
        clients.add(recorded.client());
        var readTool = new ReadFileTool();
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
                ChatMessage.user(request)));
        var resolution = TaskContractResolver.resolveFromMessages(messages);
        assertEquals(expectedType, resolution.contract().type(), "PRECONDITION: real prompt classification");
        assertFalse(resolution.contract().mutationAllowed());
        assertFalse(resolution.contract().mutationRequested());
        var targets = new LinkedHashSet<>(resolution.contract().expectedTargets());
        targets.addAll(resolution.contract().sourceEvidenceTargets());
        assertEquals(expectedTargets, targets, "PRECONDITION: real declared source set");
        TurnTaskContractCapture.set(resolution, messages);
        TurnUserRequestCapture.set(request);
        recorded.client().setToolSpecs(specs);
        var initial = recorded.client().chatFull(messages, specs, ChatRequestControls.defaults()
                .withToolAuthorityMode(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE));
        assertTrue(initial.provenance().providerToolCallEligible(), "PRECONDITION: real native-call receipt");
        assertEquals(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                initial.provenance().toolAuthorityMode());
        var result = loop.run(initial.text(), initial.toolCalls(), initial.provenance(), messages, workspace, context);
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.finalAnswer());
        return new Run(result, List.copyOf(recorded.requests()), LocalTurnTraceCapture.complete(), readOrder);
    }

    private static void assertAllReadsObserved(Run run, Layout layout) {
        assertEquals(layout.contents().keySet(), Set.copyOf(run.result().readPaths()),
                "ACQUISITION: all scripted source reads must actually execute");
        assertEquals(0, run.result().failedCalls());
        for (int index = 0; index < run.readOrder().size(); index++) assertReadObserved(run, layout, index);
    }

    private static void assertReadObserved(Run run, Layout layout, int index) {
        String path = run.readOrder().get(index);
        String id = callId(index);
        assertTrue(run.result().toolOutcomes().stream().anyMatch(outcome -> outcome.success()
                && !outcome.denied() && "talos.read_file".equals(outcome.toolName())
                && Path.of(outcome.pathHint()).endsWith(path)), "Successful real read required for " + path);
        // Observe original ID/body association where it was actually delivered. Looking only in
        // final loop messages would confuse ordinary compaction with a never-executed read.
        assertTrue(Stream.concat(run.requests().stream().flatMap(request -> request.messages.stream()),
                run.result().messages().stream()).anyMatch(message -> isBody(message, id, layout.contents().get(path))),
                "Original native result and full body must have been observed for " + path);
    }

    private static void assertAllBodiesBound(ChatRequest request, Layout layout, List<String> order, String phase) {
        for (int index = 0; index < order.size(); index++) {
            String path = order.get(index);
            String id = callId(index);
            assertTrue(request.messages.stream().anyMatch(message -> isBody(message, id, layout.contents().get(path))),
                    phase + ": missing result for " + path);
            assertTrue(request.messages.stream().filter(message -> "assistant".equals(message.role()))
                    .flatMap(message -> message.toolCalls().stream()).anyMatch(call -> id.equals(call.id())
                            && "talos.read_file".equals(call.name()) && Map.of("path", path).equals(call.arguments())),
                    phase + ": missing original native call/path association for " + path);
        }
    }

    private static boolean isBody(ChatMessage message, String id, String contents) {
        return "tool".equals(message.role()) && id.equals(message.toolCallId())
                && message.content() != null && message.content().contains(renderedBody(contents));
    }

    private static void assertSelectedUsefulCandidate(Run run) {
        assertEquals(USEFUL_SCRIPTED_ANSWER, run.result().finalAnswer(),
                "A useful scripted source-grounded candidate must survive the loop transition");
        assertFalse(run.result().failureDecision().shouldStop(), "A valid candidate must not become a loop failure");
        // Equality checks scripted candidate transport, not model reasoning or semantic verification.
    }

    private static void assertBounded(Run run, int maxIterations) {
        assertTrue(run.result().iterations() <= maxIterations, "The configured single-loop iteration budget is bounded");
        assertTrue(run.result().toolsInvoked() <= maxIterations, "One tool per scripted iteration stays within budget");
        assertTrue(responseOnlyRequests(run).size() <= 1, "Only one response-only recovery is allowed");
        assertTrue(run.requests().size() <= maxIterations + 1, "Provider generations remain bounded");
    }

    private static List<ChatRequest> responseOnlyRequests(Run run) {
        // The observable request surface is asserted, not a particular helper or debug-tag name.
        return run.requests().stream().filter(request -> request.tools.isEmpty()
                && request.controls.toolChoice() == ToolChoiceMode.NONE).toList();
    }

    private void createFiles(Layout layout) throws Exception {
        for (var entry : layout.contents().entrySet()) {
            byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            assertEquals(layout.hashes().get(entry.getKey()), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)), "PRECONDITION: pinned source identity");
            Files.write(workspace.resolve(entry.getKey()), bytes);
        }
    }

    private void assertNoMutations(Run run, Layout layout) throws Exception {
        assertEquals(0, run.result().mutatingToolSuccesses());
        assertTrue(run.result().toolOutcomes().stream().noneMatch(outcome -> outcome.mutating()
                || outcome.mutationEffects().hasEffects()));
        assertEquals(run.result().toolsInvoked(), run.trace().events().stream()
                .filter(event -> "TOOL_EXECUTED".equals(event.type())).count());
        try (var paths = Files.list(workspace)) {
            assertEquals(layout.contents().keySet(), paths.map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet()));
        }
        for (var entry : layout.contents().entrySet())
            assertArrayEquals(entry.getValue().getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(workspace.resolve(entry.getKey())), entry.getKey());
    }

    private static String callId(int index) { return "source-read-" + (index + 1); }

    private static String renderedBody(String contents) {
        var lines = contents.lines().toList();
        var rendered = new StringBuilder();
        for (int index = 0; index < lines.size(); index++)
            rendered.append(index + 1).append(" | ").append(lines.get(index)).append('\n');
        return rendered.toString().stripTrailing();
    }

    private record Layout(String caller, String settings, String target, Map<String, String> contents,
                          Map<String, String> hashes) {}
    private record Run(ToolCallLoop.LoopResult result, List<ChatRequest> requests, LocalTurnTrace trace,
                       List<String> readOrder) {}
}
