package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Desired same-turn budget behavior through the actual completeness-retry boundary.
 * The verification-required DIAGNOSE_ONLY contract is explicit fixture admission,
 * matching InspectCompletenessRetryTest's generic retry coverage. It is not a claim
 * about natural-language classification. Native provider capture, both loop runs,
 * missing-primary-file detection and all file reads are real. Provider prose and
 * choices are scripted. No iteration counters or loop results are manufactured.
 *
 * The retry must share the original loop allowance and stop before excess acquisition.
 * Answer settlement, saved history and semantic verification are outside this test.
 */
class ReadOnlySharedBudgetTest {
    private static final int SAME_TURN_ITERATION_ALLOWANCE = 4;
    private static final Map<String, String> BODIES = Map.of(
            "README.md", "README_EVIDENCE says script.js is loaded and scripts.js is an unused sentinel.\n",
            "index.html", "<button id=\"calculate\">INDEX_EVIDENCE</button>"
                    + "<script src=\"script.js\"></script><link rel=\"stylesheet\" href=\"styles.css\">\n",
            "script.js", "const SCRIPT_EVIDENCE = document.querySelector('#calc');\n",
            "scripts.js", "const SCRIPTS_EVIDENCE = 'not loaded';\n",
            "styles.css", "/* STYLES_EVIDENCE */ #calculate { color: black; }\n");
    private static final String REQUEST =
            "Read README.md, index.html, script.js, scripts.js and styles.css. Explain why the Calculate "
                    + "button fails, give the correct selector and the BMI for 72 kg and 1.8 m, and distinguish "
                    + "the two JavaScript files. Do not edit files or claim tests were run.";
    private static final String EARLY =
            "script.js selects #calc, but index.html defines #calculate. The remaining sentinel and stylesheet "
                    + "have not been inspected. No files were edited and no tests were run.";
    private static final String CANDIDATE =
            "The inspected script.js selects #calc while index.html defines #calculate. "
                    + "The suggested selector is #calculate. scripts.js is an unused sentinel and styles.css "
                    + "sets the button color. Static arithmetic gives BMI 72 / (1.8 * 1.8), approximately 22.2. "
                    + "No files were edited and no tests were run.";

    @TempDir Path workspace;

    @AfterEach void clearCapture() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
    }

    @Test void completenessRetrySharesTheAlreadySpentSameTurnIterationAllowance() throws Exception {
        for (var entry : BODIES.entrySet())
            Files.writeString(workspace.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
        LocalTurnTraceCapture.begin("read-only-shared-budget", "tests", 1,
                "2026-09-27T00:00:00Z", "workspace", "auto", "test", "fixture", REQUEST);
        var contract = new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, true,
                BODIES.keySet(), Set.of(), REQUEST);
        var plan = CurrentTurnPlan.compatibility(contract, ExecutionPhase.INSPECT,
                List.of("talos.read_file"), List.of("talos.read_file"), List.of());
        var messages = new ArrayList<>(List.of(ChatMessage.system("You are Talos."), ChatMessage.user(REQUEST)));
        TurnTaskContractCapture.set(contract);
        TurnUserRequestCapture.set(REQUEST);
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                read("original-readme", "README.md"),
                read("original-index", "index.html"),
                read("original-script", "script.js"),
                new LlmClient.StreamResult(EARLY, List.of()),
                read("retry-scripts", "scripts.js"),
                read("retry-styles", "styles.css"),
                new LlmClient.StreamResult(CANDIDATE, List.of())), 16_384);
        try (LlmClient llm = recorded.client()) {
            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            var specs = registry.descriptors().stream().map(descriptor -> new ToolSpec(
                    descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList();
            var context = Context.builder(new Config()).llm(llm).sandbox(new Sandbox(workspace, Map.of()))
                    .toolRegistry(registry).nativeToolSpecs(specs)
                    .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry),
                            SAME_TURN_ITERATION_ALLOWANCE)).build();
            var initial = TurnModelDispatcher.dispatchBuffered(context, messages, plan);
            assertTrue(initial.provenance().providerToolCallEligible(), "PRECONDITION: captured native authority");
            var original = context.toolCallLoop().run(initial.text(), initial.toolCalls(), initial.provenance(),
                    messages, workspace, context);
            assertEquals(SAME_TURN_ITERATION_ALLOWANCE - 1, original.iterations(),
                    "PRECONDITION: original real loop has spent all but one iteration");
            assertEquals(3, original.toolsInvoked());
            assertEquals(Set.of("README.md", "index.html", "script.js"), Set.copyOf(original.readPaths()));
            assertEquals(0, original.failedCalls());
            assertFalse(original.failureDecision().shouldStop());
            assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED, original.readOnlySynthesisStatus());
            assertEquals(Set.of("scripts.js", "styles.css"), Set.copyOf(
                    InspectCompletenessRetry.missingReads(workspace, plan, original)),
                    "PRECONDITION: real primary-file detection finds two missing reads with one iteration left");
            assertReadsHaveBodies(original, recorded.requests());

            AtomicInteger retryDispatches = new AtomicInteger();
            var retry = InspectCompletenessRetry.retryIfNeeded(original.finalAnswer(), messages, plan, original,
                    workspace, context, sent -> {
                        retryDispatches.incrementAndGet();
                        return TurnModelDispatcher.dispatchBuffered(context, sent, plan);
                    });
            assertEquals(1, retryDispatches.get(), "Only the existing single completeness retry is exercised");
            assertNotNull(retry.loopResult(), "The retry has one authorized iteration still available");
            var combined = retry.loopResult();
            assertReadsHaveBodies(combined, recorded.requests());
            assertEquals(0, combined.mutatingToolSuccesses());
            assertTrue(combined.toolOutcomes().stream().noneMatch(outcome -> outcome.mutating()
                    || outcome.mutationEffects().hasEffects()));
            for (var entry : BODIES.entrySet())
                assertEquals(entry.getValue(), Files.readString(workspace.resolve(entry.getKey()), StandardCharsets.UTF_8));
            try (var paths = Files.list(workspace)) {
                assertEquals(BODIES.keySet(), paths.map(path -> path.getFileName().toString())
                        .collect(java.util.stream.Collectors.toSet()));
            }
            LocalTurnTraceCapture.recordRoutedAnswerCaptured(retry.answer());
            var trace = LocalTurnTraceCapture.complete();
            long executedReads = trace.events().stream().filter(event -> "TOOL_EXECUTED".equals(event.type())
                    && "talos.read_file".equals(event.toolName())).count();
            assertEquals(combined.toolsInvoked(), executedReads,
                    "Trace and merged accounting must describe the same actual executions");
            // One read is scripted per iteration, so both constraints are observable here.
            // The execution assertion prevents passing merely by clamping reported counters.
            assertAll("SHARED_BUDGET: a completeness retry must consume the remaining same-turn allowance",
                    () -> assertTrue(combined.iterations() <= SAME_TURN_ITERATION_ALLOWANCE,
                            "Merged real iterations exceed the configured allowance: " + combined.iterations()),
                    () -> assertTrue(executedReads <= SAME_TURN_ITERATION_ALLOWANCE,
                            "Actual same-turn reads exceed the configured allowance: " + executedReads));
            assertEquals(SAME_TURN_ITERATION_ALLOWANCE, combined.iterations());
            assertEquals(0, context.toolCallLoop().remainingIterations(combined));
            assertFalse(combined.readPaths().contains("styles.css"),
                    "The fifth read must be stopped at execution, not hidden from accounting");
        }
    }

    @Test void exhaustedOriginalAllowanceDoesNotDispatchCompletenessRetry() throws Exception {
        exerciseRetryAllowance(3, false);
    }

    @Test void genuineResidualAllowancePermitsMissingReadsAndIsNotResetAfterMerge() throws Exception {
        exerciseRetryAllowance(5, true);
    }

    private void exerciseRetryAllowance(int allowance, boolean expectRetry) throws Exception {
        for (var entry : BODIES.entrySet())
            Files.writeString(workspace.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
        LocalTurnTraceCapture.begin("read-only-budget-" + allowance, "tests", 1,
                "2026-09-27T00:00:00Z", "workspace", "auto", "test", "fixture", REQUEST);
        var contract = new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, true,
                BODIES.keySet(), Set.of(), REQUEST);
        var plan = CurrentTurnPlan.compatibility(contract, ExecutionPhase.INSPECT,
                List.of("talos.read_file"), List.of("talos.read_file"), List.of());
        var messages = new ArrayList<>(List.of(ChatMessage.system("You are Talos."), ChatMessage.user(REQUEST)));
        TurnTaskContractCapture.set(contract);
        TurnUserRequestCapture.set(REQUEST);
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                read("original-readme", "README.md"),
                read("original-index", "index.html"),
                read("original-script", "script.js"),
                new LlmClient.StreamResult(EARLY, List.of()),
                read("retry-scripts", "scripts.js"),
                read("retry-styles", "styles.css"),
                new LlmClient.StreamResult(CANDIDATE, List.of())), 16_384);
        try (LlmClient llm = recorded.client()) {
            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            var specs = registry.descriptors().stream().map(descriptor -> new ToolSpec(
                    descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList();
            var context = Context.builder(new Config()).llm(llm).sandbox(new Sandbox(workspace, Map.of()))
                    .toolRegistry(registry).nativeToolSpecs(specs)
                    .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry),
                            allowance)).build();
            var initial = TurnModelDispatcher.dispatchBuffered(context, messages, plan);
            var original = context.toolCallLoop().run(initial.text(), initial.toolCalls(), initial.provenance(),
                    messages, workspace, context);
            assertEquals(3, original.iterations());
            assertEquals(3, original.toolsInvoked());
            assertFalse(original.failureDecision().shouldStop(), "The allowance gate must be exercised independently");
            assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED, original.readOnlySynthesisStatus());
            assertEquals(allowance - 3, context.toolCallLoop().remainingIterations(original));
            assertEquals(Set.of("scripts.js", "styles.css"), Set.copyOf(
                    InspectCompletenessRetry.missingReads(workspace, plan, original)));
            int requestsBefore = recorded.requests().size();
            AtomicInteger retryDispatches = new AtomicInteger();
            var retry = InspectCompletenessRetry.retryIfNeeded(original.finalAnswer(), messages, plan, original,
                    workspace, context, sent -> {
                        retryDispatches.incrementAndGet();
                        return TurnModelDispatcher.dispatchBuffered(context, sent, plan);
                    });
            if (expectRetry) {
                assertEquals(1, retryDispatches.get());
                assertNotNull(retry.loopResult());
                assertEquals(5, retry.loopResult().iterations());
                assertEquals(5, retry.loopResult().toolsInvoked());
                assertEquals(BODIES.keySet(), Set.copyOf(retry.loopResult().readPaths()));
                assertEquals(CANDIDATE, retry.answer());
                assertReadsHaveBodies(retry.loopResult(), recorded.requests());
                assertEquals(0, context.toolCallLoop().remainingIterations(retry.loopResult()),
                        "Merged accounting retains the original consumption");
                assertEquals(requestsBefore + 3, recorded.requests().size());
            } else {
                assertEquals(0, retryDispatches.get(), "No provider call is allowed after acquisition is spent");
                assertNull(retry.loopResult());
                assertEquals(original.finalAnswer(), retry.answer());
                assertEquals(requestsBefore, recorded.requests().size());
            }
            LocalTurnTraceCapture.recordRoutedAnswerCaptured(retry.answer());
            var trace = LocalTurnTraceCapture.complete();
            assertEquals(expectRetry ? 5L : 3L, trace.events().stream()
                    .filter(event -> "TOOL_EXECUTED".equals(event.type())
                            && "talos.read_file".equals(event.toolName())).count());
            for (var entry : BODIES.entrySet())
                assertEquals(entry.getValue(), Files.readString(workspace.resolve(entry.getKey()), StandardCharsets.UTF_8));
        }
    }

    private static void assertReadsHaveBodies(ToolCallLoop.LoopResult result, List<ChatRequest> requests) {
        for (var outcome : result.toolOutcomes()) {
            assertTrue(outcome.success() && !outcome.denied(), "Every observed fixture read must have succeeded");
            assertEquals("talos.read_file", outcome.toolName());
            String path = Path.of(outcome.pathHint()).getFileName().toString();
            assertTrue(BODIES.containsKey(path), "Only fixture sources may have been read");
            String rendered = renderedBody(BODIES.get(path));
            Set<String> nativeIds = Stream.concat(requests.stream().flatMap(request -> request.messages.stream()),
                    result.messages().stream()).filter(ChatMessage::hasNativeToolCalls)
                    .flatMap(message -> message.toolCalls().stream())
                    .filter(call -> "talos.read_file".equals(call.name())
                            && Map.of("path", path).equals(call.arguments()))
                    .map(ChatMessage.NativeToolCall::id).collect(java.util.stream.Collectors.toSet());
            assertFalse(nativeIds.isEmpty(), "The original native call/path association must be present");
            // Original carrier observations survive in captured requests even after normal compaction.
            // ToolOutcome.toolCallId is the runtime trace ID, not the provider's native call ID.
            assertTrue(Stream.concat(requests.stream().flatMap(request -> request.messages.stream()),
                    result.messages().stream()).anyMatch(message -> "tool".equals(message.role())
                            && nativeIds.contains(message.toolCallId())
                            && message.content() != null && message.content().contains(rendered)),
                    "The actual native call/body association must be observable for " + path);
        }
    }

    private static LlmClient.StreamResult read(String id, String path) {
        return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                id, "talos.read_file", Map.of("path", path))));
    }

    private static String renderedBody(String body) {
        var lines = body.lines().toList();
        var rendered = new StringBuilder();
        for (int index = 0; index < lines.size(); index++)
            rendered.append(index + 1).append(" | ").append(lines.get(index)).append('\n');
        return rendered.toString().stripTrailing();
    }
}
