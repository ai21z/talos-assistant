package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.DocumentComparisonTool;
import dev.talos.runtime.toolcall.ToolResultModelContextHandoff;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.tools.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real producer and final consumer are joined with a synthetic completed loop envelope. */
class DocumentComparisonOutcomeTest {
    @TempDir Path workspace;
    private Config config = new Config(null);
    private String request;
    @AfterEach void clear() {
        TurnAuditCapture.end();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
        PromptDebugCapture.clear();
    }
    private void begin(String text) {
        request = text;
        TurnAuditCapture.begin();
        TurnUserRequestCapture.set(text);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(text));
    }
    private ToolResult produce(String left, String right) {
        return produce(left, right, new NoOpApprovalGate());
    }
    private ToolResult produce(String left, String right, ApprovalGate gate) {
        var registry = new ToolRegistry();
        registry.register(new DocumentComparisonTool());
        var processor = new TurnProcessor(ModeController.defaultController(), gate, registry);
        var context = Context.builder(config).sandbox(new Sandbox(workspace, config.data)).build();
        return processor.executeTool(new Session(workspace, config),
                new ToolCall(DocumentComparisonTool.NAME, Map.of("left", left, "right", right)), context);
    }
    @ParameterizedTest @ValueSource(strings = {"left:deny", "right:deny", "left:ask", "right:ask"})
    void bothOperandsObeyConfiguredPermissionAtRealGateway(String setting) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "left");
        Files.writeString(workspace.resolve("b.txt"), "right");
        String[] parts = setting.split(":");
        String deniedPath = parts[0].equals("left") ? "a.txt" : "b.txt";
        config.data.put("permissions", Map.of("rules", List.of(Map.of("effect", parts[1],
                "tools", List.of(DocumentComparisonTool.NAME), "paths", List.of(deniedPath)))));
        begin("Compare a.txt and b.txt.");
        var approvals = new java.util.concurrent.atomic.AtomicInteger();
        var result = produce("a.txt", "b.txt", (description, detail) -> {
            approvals.incrementAndGet();
            return false;
        });
        assertFalse(result.success(), result.output());
        assertEquals(parts[1].equals("ask") ? 1 : 0, approvals.get());
        assertTrue(TurnAuditCapture.currentComparisons(request, workspace).isEmpty());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, finish(result).completionStatus());
    }

    @ParameterizedTest @ValueSource(strings = {"plain", "bom-crlf", "right-changed", "real-loop", "original-L1",
            "real-loop-sequential", "real-loop-partial", "original-L1-sequential", "original-L1-partial"})
    void originalMixedDiagnosisBindsBothComputedSnapshotsToProviderEvidence(String variation) throws Exception {
        String text = "Read README.md, index.html, script.js and scripts.js. Explain why the Calculate button fails and give the correct selector. "
                + "For span 12 m and rise 2.5 m, give the panel area according to README.md and the mathematical value of the expression in script.js separately. "
                + "Explain any disagreement and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
        boolean originalL1 = variation.startsWith("original-L1");
        if (originalL1) text = "Please read README.md, index.html, script.js, scripts.js and notes.md. Explain exactly why the Calculate button is broken. Identify the selector that should be used, calculate the BMI for 72 kg and 1.8 m, and explain how script.js differs from scripts.js. Do not edit files or claim tests were run.";
        var sources = new java.util.LinkedHashMap<String, String>();
        sources.put("README.md", "The Calculate button should compute panel area as span multiplied by rise.\n");
        sources.put("index.html", "<html><body><label>Span (m)<input id=\"span\" value=\"12\"></label><label>Rise (m)<input id=\"rise\" value=\"2.5\"></label>"
                + "<button id=\"calculate\">Calculate</button><p id=\"result\"></p><script src=\"script.js\"></script></body></html>\n");
        sources.put("script.js", """
                const button = document.querySelector('#calc');
                button.addEventListener('click', () => {
                  const span = Number(document.querySelector('#span').value);
                  const rise = Number(document.querySelector('#rise').value);
                  document.querySelector('#result').textContent = (span + rise).toFixed(1);
                });
                """);
        sources.put("scripts.js", "const arbitraryName = 'unrelated';\n");
        if (originalL1) {
            sources.put("README.md", "# Calculator review fixture\n\nThe calculator in index.html loads script.js. scripts.js is an unrelated sentinel and must stay unchanged. The Calculate button should compute BMI as weight divided by height squared. This fixture is local test data.\n");
            sources.put("index.html", "<!doctype html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\"><title>BMI fixture</title></head>\n<body>\n<label>Weight (kg)<input id=\"weight\" value=\"72\"></label>\n<label>Height (m)<input id=\"height\" value=\"1.8\"></label>\n<button id=\"calculate\">Calculate</button>\n<p id=\"result\"></p>\n<script src=\"script.js\"></script>\n</body>\n</html>\n");
            sources.put("script.js", "const button = document.querySelector('#calc');\nbutton.addEventListener('click', () => {\n  const weight = Number(document.querySelector('#weight').value);\n  const height = Number(document.querySelector('#height').value);\n  document.querySelector('#result').textContent = (weight / (height * height)).toFixed(1);\n});\n");
            sources.put("scripts.js", "// Unrelated sentinel. This file is not loaded by index.html.\nconst sentinel = 'KEEP_UNCHANGED';\n");
            sources.put("notes.md", "Fixture housekeeping\nThis directory is used for local review. The sentinel file is retained for comparison.\n");
        }
        if (variation.equals("bom-crlf")) sources.compute("scripts.js", (key, value) -> "\ufeff" + value.replace("\n", "\r\n"));
        for (var source : sources.entrySet()) Files.writeString(workspace.resolve(source.getKey()), source.getValue());
        begin(text);
        LocalTurnTraceCapture.begin("comparison-mixed", "local-test", 1, "2026-09-24T00:00:00Z",
                "workspace", "ask", "fixture", "fixture", text);
        var contract = TurnTaskContractCapture.get();
        var captured = dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidenceTestSupport.captured(workspace, contract, sources,
                (meaning, observed) -> {
                    var candidate = dev.talos.runtime.verification.NativeAnswerProposalTestSupport.decisionProposal(meaning, observed);
                    try {
                        var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                                dev.talos.runtime.verification.NativeReadOnlyAnswerProposal.evaluationSchema(meaning, observed));
                        candidate.set("version", schema.at("/properties/version/enum/0"));
                        candidate.set("catalogId", schema.at("/properties/catalogId/enum/0"));
                    } catch (Exception invalid) { throw new IllegalStateException(invalid); }
                    return candidate.toString();
                });
        assertTrue(captured.evidence().proposalSelected());
        if (variation.equals("right-changed")) Files.writeString(workspace.resolve("scripts.js"), "const changed = 'new';\n");
        var outcomes = new java.util.ArrayList<ToolCallLoop.ToolOutcome>();
        sources.forEach((path, body) -> outcomes.add(new ToolCallLoop.ToolOutcome("talos.read_file", path, true, false, false, body, "")));
        ToolCallLoop.LoopResult loop;
        if (variation.startsWith("real-loop") || originalL1) {
            TurnAuditCapture.end();
            TurnAuditCapture.begin();
            LocalTurnTraceCapture.begin("comparison-real-loop", "local-test", 2, "2026-09-24T00:00:00Z",
                    "workspace", "ask", "fixture", "fixture", text);
            var responses = new java.util.ArrayList<dev.talos.core.llm.LlmClient.StreamResult>();
            var paths = List.copyOf(sources.keySet());
            int batchSize = variation.endsWith("sequential") ? 1 : variation.endsWith("partial") ? 2 : paths.size();
            for (int start = 0; start < paths.size(); start += batchSize) {
                responses.add(new dev.talos.core.llm.LlmClient.StreamResult("", paths.subList(start, Math.min(paths.size(), start + batchSize))
                        .stream().map(path -> new ChatMessage.NativeToolCall("read-" + path, "talos.read_file", Map.<String,Object>of("path", path))).toList()));
            }
            int readDispatches = responses.size();
            responses.add(new dev.talos.core.llm.LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("pair", DocumentComparisonTool.NAME,
                    Map.of("left", "script.js", "right", "scripts.js")))));
            var counter = new java.util.concurrent.atomic.AtomicInteger();
            var recorded = dev.talos.core.llm.ScriptedNativeLlmClient.recordingResponder(providerRequest -> {
                int step = counter.getAndIncrement();
                if (step < responses.size()) return responses.get(step);
                assertEquals(responses.size(), step, "No extra generation is expected");
                try {
                    var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(providerRequest.controls.jsonSchema());
                    return new dev.talos.core.llm.LlmClient.StreamResult(selectFixture(schema).toString(), List.of());
                } catch (Exception invalid) { throw new IllegalStateException(invalid); }
            }, 16_384);
            try (var client = recorded.client()) {
                var registry = new ToolRegistry();
                registry.register(new dev.talos.tools.impl.ReadFileTool());
                registry.register(new DocumentComparisonTool());
                var specs = List.of(new dev.talos.tools.impl.ReadFileTool().descriptor(), new DocumentComparisonTool().descriptor()).stream()
                        .map(value -> new dev.talos.spi.types.ToolSpec(value.name(), value.description(), value.parametersSchema())).toList();
                client.setToolSpecs(specs);
                var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
                var engine = new ToolCallLoop(processor, 10);
                var context = Context.builder(config).sandbox(new Sandbox(workspace, config.data)).llm(client)
                        .nativeToolSpecs(specs).toolRegistry(registry).toolCallLoop(engine).build();
                var messages = new java.util.ArrayList<>(List.of(ChatMessage.system("Perform the requested read-only diagnosis."), ChatMessage.user(text)));
                TurnTaskContractCapture.set(TaskContractResolver.resolveFromMessages(messages), messages);
                var initial = client.chatFull(messages, specs, dev.talos.spi.types.ChatRequestControls.defaults()
                        .withToolAuthorityMode(client.toolAuthorityModeForToolSurface(specs)));
                loop = engine.run(initial.text(), initial.toolCalls(), initial.provenance(), messages, workspace, context);
                assertEquals(sources.size() + 1, loop.toolsInvoked(), loop.toString());
                assertEquals(0, loop.failedCalls(), loop.toString());
                assertEquals(readDispatches + 2, recorded.requests().size());
                for (int i = 1; i < readDispatches; i++) {
                    assertEquals(dev.talos.spi.types.ToolChoiceMode.REQUIRED, recorded.requests().get(i).controls.toolChoice());
                    assertEquals(List.of("talos.read_file"), recorded.requests().get(i).tools.stream().map(dev.talos.spi.types.ToolSpec::name).toList());
                }
                assertEquals(dev.talos.spi.types.ToolChoiceMode.REQUIRED, recorded.requests().get(readDispatches).controls.toolChoice());
                assertEquals(List.of(DocumentComparisonTool.NAME), recorded.requests().get(readDispatches).tools.stream().map(dev.talos.spi.types.ToolSpec::name).toList());
                assertEquals(dev.talos.spi.types.ToolChoiceMode.NONE, recorded.requests().getLast().controls.toolChoice());
                assertTrue(recorded.requests().getLast().tools.isEmpty());
                assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, loop.readOnlySynthesisStatus());
            }
        } else {
            var comparison = produce("script.js", "scripts.js");
            assertTrue(comparison.success(), comparison.errorMessage());
            outcomes.add(new ToolCallLoop.ToolOutcome(DocumentComparisonTool.NAME, "", true, false, false, comparison.output(), ""));
            loop = new ToolCallLoop.LoopResult(captured.answer(), 5, 5, outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(),
                List.of(), 0, 0, false, 0, List.copyOf(sources.keySet()), 0, 0, 0, 0, null, outcomes,
                Map.of(), false, null, null, captured.evidence());
        }
        var result = ExecutionOutcome.fromToolLoop(loop.finalAnswer(), List.of(ChatMessage.user(text)), loop, workspace, 0);
        if (variation.equals("right-changed")) {
            assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, result.completionStatus());
            assertTrue(result.finalAnswer().contains("could not be bound"), result.finalAnswer());
        } else {
            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, result.completionStatus(), result.finalAnswer());
            assertTrue(result.finalAnswer().contains(originalL1 ? "22.2" : "14.5"), result.finalAnswer());
            if (!originalL1) assertTrue(result.finalAnswer().contains("30"), result.finalAnswer());
            assertTrue(result.finalAnswer().contains("#calculate"), result.finalAnswer());
            assertTrue(result.finalAnswer().contains("source roles differ"), result.finalAnswer());
        }
    }
    private ExecutionOutcome finish(ToolResult result) {
        return finish(result, List.of());
    }
    private ExecutionOutcome finish(ToolResult result, List<ToolCallLoop.ToolOutcome> reads) {
        var messages = List.of(ChatMessage.user(request));
        var outcome = new ToolCallLoop.ToolOutcome(DocumentComparisonTool.NAME, "", result.success(), false, false,
                result.success() ? result.output() : "", result.success() ? "" : result.errorMessage());
        var outcomes = new java.util.ArrayList<>(reads);
        outcomes.add(outcome);
        var loop = new ToolCallLoop.LoopResult("Untrusted prose claims equal files.", outcomes.size(), outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(), messages,
                (int) outcomes.stream().filter(value -> !value.success()).count(), 0, false,
                0, reads.stream().map(ToolCallLoop.ToolOutcome::pathHint).toList(), 0, 0, 0, 0, outcomes);
        return ExecutionOutcome.fromToolLoop(loop.finalAnswer(), messages, loop, workspace, 0);
    }
    @Test void realGatewayAndFinalConsumerUseComputedChangesNotModelProse() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "before\n");
        Files.writeString(workspace.resolve("b.txt"), "after\n");
        begin("Compare a.txt and b.txt.");
        var produced = produce("a.txt", "b.txt");
        assertTrue(produced.success(), produced.errorMessage());
        var answer = finish(produced);
        assertTrue(answer.finalAnswer().contains("Removed \"before\" [LF]"), answer.finalAnswer());
        assertTrue(answer.finalAnswer().contains("Added \"after\" [LF]"));
        assertFalse(answer.finalAnswer().contains("Untrusted prose"));
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, answer.completionStatus(), answer.finalAnswer());
    }
    @ParameterizedTest @ValueSource(strings = {"review.html", "inspect.js"})
    void operandNamesCannotCreateAnUnrequestedDiagnosis(String left) throws Exception {
        Files.writeString(workspace.resolve(left), "before\n");
        Files.writeString(workspace.resolve("b.html"), "after\n");
        begin("Compare " + left + " and b.html.");
        var result = produce(left, "b.html");
        assertTrue(result.success(), result.errorMessage());
        var outcome = finish(result);
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("Added \"after\" [LF]"));
        assertFalse(outcome.finalAnswer().contains("Untrusted prose"));
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
    }

    @ParameterizedTest @ValueSource(strings = {"complete", "missing", "failed"})
    void explicitReadsAndComparisonRemainSeparateObligations(String variant) throws Exception {
        Files.writeString(workspace.resolve("review.html"), "before\n");
        Files.writeString(workspace.resolve("b.html"), "after\n");
        begin("Read review.html and b.html. Compare review.html and b.html.");
        var reads = new java.util.ArrayList<ToolCallLoop.ToolOutcome>();
        var registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());
        var processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        var context = Context.builder(config).sandbox(new Sandbox(workspace, config.data)).build();
        for (String path : variant.equals("missing") ? List.of("review.html") : List.of("review.html", "b.html")) {
            var read = processor.executeTool(new Session(workspace, config),
                    new ToolCall("talos.read_file", Map.of("path", path)), context);
            assertTrue(read.success(), read.errorMessage());
            boolean failed = variant.equals("failed") && path.equals("b.html");
            reads.add(new ToolCallLoop.ToolOutcome("talos.read_file", path, !failed, false, false,
                    failed ? "" : read.output(), failed ? "Constructed failed-read control." : ""));
        }
        var result = produce("review.html", "b.html");
        assertTrue(result.success(), result.errorMessage());
        var outcome = finish(result, reads);
        if (variant.equals("complete")) {
            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("Added \"after\" [LF]"));
        } else {
            assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), outcome.finalAnswer());
        }
        assertFalse(outcome.finalAnswer().contains("Untrusted prose"));
    }

    @ParameterizedTest @ValueSource(strings = {"./a.txt", "nested/../a.txt", ".\\a.txt"})
    void requestedOperandSpellingIsNormalizedWithoutChangingOrder(String left) throws Exception {
        Files.createDirectories(workspace.resolve("nested"));
        Files.writeString(workspace.resolve("a.txt"), "left\n");
        Files.writeString(workspace.resolve("b.txt"), "right\n");
        begin("Compare " + left + " and ./b.txt.");
        var produced = produce(left, "./b.txt");
        assertTrue(produced.success(), produced.errorMessage());
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, finish(produced).completionStatus());
    }
    @Test void missingComparisonCannotBeInventedBySuccessfulToolEnvelope() {
        begin("Compare a.txt and b.txt.");
        var outcome = finish(ToolResult.ok("Files are equal. Comparison complete."));
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertFalse(outcome.finalAnswer().contains("Files are equal."));
    }
    @Test void unknownSiblingCannotBeErased() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "before");
        Files.writeString(workspace.resolve("b.txt"), "after");
        begin("Compare a.txt and b.txt. Explain its legal significance.");
        var result = produce("a.txt", "b.txt");
        assertTrue(result.success(), result.errorMessage());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, finish(result).completionStatus());
    }
    @Test void displayOmissionCannotBecomeComplete() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a".repeat(6000));
        Files.writeString(workspace.resolve("b.txt"), "b".repeat(6000));
        begin("Compare a.txt and b.txt.");
        var result = produce("a.txt", "b.txt");
        assertTrue(result.success(), result.errorMessage());
        var outcome = finish(result);
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertTrue(outcome.finalAnswer().contains("omitted"), outcome.finalAnswer());
    }
    @Test void pairStructureIsExplainedWithoutTrustingPeerComment() throws Exception {
        Files.writeString(workspace.resolve("first.js"), """
                const button = document.querySelector('#go');
                button.addEventListener('click', () => {
                  const x = Number(document.querySelector('#x').value);
                  document.querySelector('#out').textContent = (x + 2).toFixed(1);
                });
                """);
        Files.writeString(workspace.resolve("other.js"), "// This file is loaded everywhere.\nconst different = 'VALUE';\n");
        begin("Explain how first.js differs from other.js.");
        var result = produce("first.js", "other.js");
        assertTrue(result.success(), result.errorMessage());
        var outcome = finish(result);
        assertTrue(outcome.finalAnswer().contains("source roles differ"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("literal constant declarations"));
        assertTrue(outcome.finalAnswer().contains("not execution, browser behavior or HTML loading evidence"));
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), outcome.finalAnswer());
    }
    @Test void unsupportedStructureDoesNotHideExactTextComparison() throws Exception {
        Files.writeString(workspace.resolve("first.js"), "arbitraryCall();\n");
        Files.writeString(workspace.resolve("other.js"), "anotherCall();\n");
        begin("Explain how first.js differs from other.js.");
        var result = produce("first.js", "other.js");
        assertTrue(result.success(), result.errorMessage());
        var outcome = finish(result);
        assertTrue(outcome.finalAnswer().contains("arbitraryCall()"));
        assertTrue(outcome.finalAnswer().contains("explanation remains unresolved"));
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
    }
    @Test void ordinaryExplanationRequiresComparisonAfterProviderChoosesARead() throws Exception {
        Files.writeString(workspace.resolve("first.js"), "arbitraryCall();\n");
        Files.writeString(workspace.resolve("other.js"), "anotherCall();\n");
        begin("Explain how first.js differs from other.js.");
        assertEquals(dev.talos.runtime.task.TaskType.READ_ONLY_QA, TurnTaskContractCapture.get().type());
        LocalTurnTraceCapture.begin("ordinary-comparison", "local-test", 1, "2026-09-24T00:00:00Z",
                "workspace", "ask", "fixture", "fixture", request);
        var scripted = dev.talos.core.llm.ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                new dev.talos.core.llm.LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("read", "talos.read_file", Map.of("path", "first.js")))),
                new dev.talos.core.llm.LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("pair", DocumentComparisonTool.NAME,
                        Map.of("left", "first.js", "right", "other.js")))),
                new dev.talos.core.llm.LlmClient.StreamResult("Unsupported behavior.", List.of())), 16_384);
        try (var client = scripted.client()) {
            var registry = new ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new DocumentComparisonTool());
            var specs = List.of(new dev.talos.tools.impl.ReadFileTool().descriptor(), new DocumentComparisonTool().descriptor()).stream()
                    .map(value -> new dev.talos.spi.types.ToolSpec(value.name(), value.description(), value.parametersSchema())).toList();
            client.setToolSpecs(specs);
            var engine = new ToolCallLoop(new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry), 6);
            var context = Context.builder(config).sandbox(new Sandbox(workspace, config.data)).llm(client)
                    .nativeToolSpecs(specs).toolRegistry(registry).toolCallLoop(engine).build();
            var messages = new java.util.ArrayList<>(List.of(ChatMessage.user(request)));
            var initial = client.chatFull(messages, specs, dev.talos.spi.types.ChatRequestControls.defaults()
                    .withToolAuthorityMode(client.toolAuthorityModeForToolSurface(specs)));
            var loop = engine.run(initial.text(), initial.toolCalls(), initial.provenance(), messages, workspace, context);
            assertEquals(dev.talos.spi.types.ToolChoiceMode.REQUIRED, scripted.requests().get(1).controls.toolChoice());
            assertEquals(List.of(DocumentComparisonTool.NAME), scripted.requests().get(1).tools.stream().map(dev.talos.spi.types.ToolSpec::name).toList());
            assertEquals(2, loop.toolsInvoked());
            assertEquals(0, loop.failedCalls());
            var answer = ExecutionOutcome.fromToolLoop(loop.finalAnswer(), messages, loop, workspace, 0);
            assertTrue(answer.finalAnswer().contains("arbitraryCall()"), answer.finalAnswer());
            assertTrue(answer.finalAnswer().contains("anotherCall()"), answer.finalAnswer());
            assertTrue(answer.finalAnswer().contains("explanation remains unresolved"), answer.finalAnswer());
            assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, answer.completionStatus());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"b.txt", "./b.txt"})
    void ordinaryExplicitReadsCompleteBeforeComparisonOnlyNarrowing(String secondRead) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "before\n");
        Files.writeString(workspace.resolve("b.txt"), "after\n");
        begin("Read a.txt and " + secondRead + ". Compare a.txt and b.txt.");
        assertEquals(dev.talos.runtime.task.TaskType.READ_ONLY_QA, TurnTaskContractCapture.get().type());
        LocalTurnTraceCapture.begin("ordinary-explicit-reads", "local-test", 1, "2026-09-24T00:00:00Z",
                "workspace", "ask", "fixture", "fixture", request);
        var scripted = dev.talos.core.llm.ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                new dev.talos.core.llm.LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("read-a", "talos.read_file", Map.of("path", "a.txt")))),
                new dev.talos.core.llm.LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("read-second", "talos.read_file", Map.of("path", secondRead)))),
                new dev.talos.core.llm.LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("pair", DocumentComparisonTool.NAME,
                        Map.of("left", "a.txt", "right", "b.txt")))),
                new dev.talos.core.llm.LlmClient.StreamResult("The files differ.", List.of())), 16_384);
        try (var client = scripted.client()) {
            var registry = new ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new DocumentComparisonTool());
            var specs = List.of(new dev.talos.tools.impl.ReadFileTool().descriptor(), new DocumentComparisonTool().descriptor()).stream()
                    .map(value -> new dev.talos.spi.types.ToolSpec(value.name(), value.description(), value.parametersSchema())).toList();
            client.setToolSpecs(specs);
            var engine = new ToolCallLoop(new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry), 6);
            var context = Context.builder(config).sandbox(new Sandbox(workspace, config.data)).llm(client)
                    .nativeToolSpecs(specs).toolRegistry(registry).toolCallLoop(engine).build();
            var messages = new java.util.ArrayList<>(List.of(ChatMessage.user(request)));
            var initial = client.chatFull(messages, specs, dev.talos.spi.types.ChatRequestControls.defaults()
                    .withToolAuthorityMode(client.toolAuthorityModeForToolSurface(specs)));
            var loop = engine.run(initial.text(), initial.toolCalls(), initial.provenance(), messages, workspace, context);
            assertEquals(specs, scripted.requests().get(1).tools);
            assertEquals(dev.talos.spi.types.ToolChoiceMode.AUTO, scripted.requests().get(1).controls.toolChoice());
            assertEquals(dev.talos.spi.types.ToolChoiceMode.REQUIRED, scripted.requests().get(2).controls.toolChoice());
            assertEquals(List.of(DocumentComparisonTool.NAME), scripted.requests().get(2).tools.stream().map(dev.talos.spi.types.ToolSpec::name).toList());
            assertEquals(3, loop.toolsInvoked());
            assertEquals(0, loop.failedCalls());
            var answer = ExecutionOutcome.fromToolLoop(loop.finalAnswer(), messages, loop, workspace, 0);
            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, answer.completionStatus(), answer.finalAnswer());
            assertTrue(answer.finalAnswer().contains("Added \"after\" [LF]"), answer.finalAnswer());
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void localPrivateComparisonRestrictsPersistenceWithoutGrantingModelPermission(boolean reversed) throws Exception {
        String privateText = "The ceramics workshop meets beside the old observatory on Thursday.";
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(privateText);
            try (var stream = Files.newOutputStream(workspace.resolve("private.docx"))) { document.write(stream); }
        }
        Files.writeString(workspace.resolve("public.txt"), "Public counterpart.");
        config.data.put("document_extraction", Map.of("enabled", true, "word", Map.of("enabled", true)));
        config.data.put("privacy", Map.of("mode", "private", "document_extraction", Map.of("allow_send_to_model", false)));
        String left = reversed ? "public.txt" : "private.docx";
        String right = reversed ? "private.docx" : "public.txt";
        begin("Compare " + left + " and " + right + ".");
        var result = produce(left, right);
        assertTrue(result.success(), result.errorMessage());
        var handoff = ToolResultModelContextHandoff.decide(new ToolCall(DocumentComparisonTool.NAME,
                Map.of("left", left, "right", right)), null, "", result, null);
        assertTrue(handoff.contentWithheldFromModelContext());
        assertFalse(handoff.modelResult().output().contains(privateText));
        var answer = finish(result);
        assertTrue(answer.finalAnswer().contains(privateText), answer.finalAnswer());
        var restrictions = TurnAuditCapture.currentContentRestrictions();
        assertFalse(restrictions.modelAllowed());
        assertFalse(restrictions.historyAllowed());
        assertFalse(restrictions.artifactAllowed());
        assertEquals(ContentRestrictions.WITHHELD, restrictions.forHistory(answer.finalAnswer()));
        assertFalse(new ChatMessage("assistant", answer.finalAnswer()).toString().contains(privateText));
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void realComparisonAnswerIsWithheldByTerminalMemoryAndStoredSession(boolean reversed) throws Exception {
        String privateText = "The ceramics workshop meets beside the old observatory on Thursday.";
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(privateText);
            try (var stream = Files.newOutputStream(workspace.resolve("private.docx"))) { document.write(stream); }
        }
        Files.writeString(workspace.resolve("public.txt"), "Public counterpart.");
        config.data.put("document_extraction", Map.of("enabled", true, "word", Map.of("enabled", true)));
        config.data.put("privacy", Map.of("mode", "private", "document_extraction", Map.of("allow_send_to_model", false)));
        String left = reversed ? "public.txt" : "private.docx";
        String right = reversed ? "private.docx" : "public.txt";
        request = "Compare " + left + " and " + right + ".";
        var store = new JsonSessionStore(workspace.resolve("session-store"));
        String sessionId = JsonSessionStore.newSessionInstanceId(workspace, java.time.Instant.now());
        var memory = new dev.talos.core.context.ConversationManager(new SessionMemory(), new dev.talos.core.context.TokenBudget());
        var processor = new TurnProcessor((raw, path, context) -> {
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(raw));
            var produced = produce(left, right);
            assertTrue(produced.success(), produced.errorMessage());
            var answer = finish(produced);
            assertTrue(answer.finalAnswer().contains(privateText));
            assertThrows(IllegalArgumentException.class, () -> new dev.talos.spi.types.ChatRequest(
                    "fixture", "fixture", "", "", List.of(), null, List.of(ChatMessage.assistant(answer.finalAnswer()))));
            assertFalse(dev.talos.safety.SafeLogFormatter.text(answer.finalAnswer()).contains(privateText));
            return java.util.Optional.of(new Result.Ok(answer.finalAnswer()));
        });
        processor.addListener(new MemoryUpdateListener(memory));
        try (var writer = store.acquireSessionWriter(sessionId)) {
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            var turn = processor.process(new Session(workspace, config), request, Context.builder(config).build());
            assertTrue(((Result.Ok) turn.result()).text.contains(privateText));
            assertFalse(turn.audit().contentRestrictions().historyAllowed());
            assertFalse(turn.audit().contentRestrictions().artifactAllowed());
        }
        var rows = store.loadTurns(sessionId);
        assertEquals(1, rows.size());
        assertEquals(ContentRestrictions.WITHHELD, rows.getFirst().assistantText());
        assertEquals(ContentRestrictions.WITHHELD, memory.buildHistory().getLast().content());
        var trace = store.loadTrace(sessionId, rows.getFirst().traceId()).orElseThrow();
        assertFalse(trace.toString().contains(privateText));
        assertFalse(TurnAuditCapture.isActive());
    }

    private static com.fasterxml.jackson.databind.JsonNode selectFixture(com.fasterxml.jackson.databind.JsonNode schema) {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        if (schema.has("oneOf")) {
            for (var branch : schema.path("oneOf")) if (branch.path("type").asText().equals("object")) return selectFixture(branch);
            return selectFixture(schema.path("oneOf").get(0));
        }
        if (schema.has("enum")) return schema.path("enum").get(0).deepCopy();
        return switch (schema.path("type").asText()) {
            case "null" -> json.nullNode();
            case "object" -> {
                var result = json.createObjectNode();
                schema.path("properties").fields().forEachRemaining(entry -> result.set(entry.getKey(), selectFixture(entry.getValue())));
                yield result;
            }
            case "array" -> {
                var result = json.createArrayNode();
                int count = schema.path("minItems").asInt();
                assertEquals(count, schema.path("maxItems").asInt());
                if (count > 0) {
                    assertEquals(count, schema.path("items").path("enum").size(), "Only unambiguous constructed bindings are used");
                    schema.path("items").path("enum").forEach(result::add);
                }
                yield result;
            }
            default -> throw new IllegalArgumentException("Unsupported fixture schema");
        };
    }

    @ParameterizedTest @ValueSource(strings = {"Compare a.txt with b.txt.", "Explain the differences between a.txt and b.txt.",
            "Compare \"first file.txt\" with \"second file.txt\".", "Compare a.txt and b.txt. Explain how the documents changed.",
            "Compare \u201ca.txt\u201d and \u201cb.txt\u201d."})
    void unsupportedComparisonWordingCannotBecomeCompleteFromReadsAndModelProse(String text) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "left");
        Files.writeString(workspace.resolve("b.txt"), "right");
        begin(text);
        var outcomes = List.of(new ToolCallLoop.ToolOutcome("talos.read_file", "a.txt", true, false, false, "left", ""),
                new ToolCallLoop.ToolOutcome("talos.read_file", "b.txt", true, false, false, "right", ""));
        var messages = List.of(ChatMessage.user(text));
        String prose = "The files are equal and their comparison is complete.";
        var loop = new ToolCallLoop.LoopResult(prose, 2, 2, List.of("talos.read_file"), messages, 0, 0, false,
                0, List.of("a.txt", "b.txt"), 0, 0, 0, 0, outcomes);
        var answer = ExecutionOutcome.fromToolLoop(prose, messages, loop, workspace, 0);
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, answer.completionStatus(), answer.finalAnswer());
        assertFalse(answer.finalAnswer().contains(prose), answer.finalAnswer());
        var noTool = ExecutionOutcome.fromNoTool(prose, messages, null, true);
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, noTool.completionStatus(), noTool.finalAnswer());
    }

    @ParameterizedTest @ValueSource(strings = {"success", "failed-applied", "failed-uncertain", "count-only"})
    void mutationEvidenceCannotRetainAnUnchangedWorkspaceClaim(String variant) {
        begin("Compare a.txt and b.txt.");
        var messages = List.of(ChatMessage.user(request));
        boolean success = variant.equals("success");
        var effects = variant.equals("failed-uncertain") ? ToolMutationEffects.uncertain("a.txt") : ToolMutationEffects.applied("a.txt");
        var mutation = new ToolCallLoop.ToolOutcome("talos.write_file", "a.txt", success, true, false,
                "", success ? "" : "Readback failed.").withMutationEffects(effects);
        var loop = new ToolCallLoop.LoopResult("The comparison is complete. No files were changed.", 1, 1,
                List.of("talos.write_file"), messages, success ? 0 : 1, 0, false,
                success ? 1 : 0, List.of(), 0, 0, 0, 0, variant.equals("count-only") ? List.of() : List.of(mutation));
        var answer = ExecutionOutcome.fromToolLoop(loop.finalAnswer(), messages, loop, workspace, variant.equals("count-only") ? 1 : 0);
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, answer.completionStatus());
        assertFalse(answer.finalAnswer().contains("No files were changed"), answer.finalAnswer());
        assertFalse(answer.finalAnswer().contains("The comparison is complete"), answer.finalAnswer());
    }

    @ParameterizedTest @ValueSource(strings = {"stale-turn", "duplicate", "wrong-workspace"})
    void ambiguousOrHistoricalComputationCannotCompleteTheCurrentAnswer(String variation) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "left");
        Files.writeString(workspace.resolve("b.txt"), "right");
        begin("Compare a.txt and b.txt.");
        var produced = produce("a.txt", "b.txt");
        assertTrue(produced.success());
        if (variation.equals("stale-turn")) { TurnAuditCapture.end(); TurnAuditCapture.begin(); }
        if (variation.equals("duplicate")) assertTrue(produce("a.txt", "b.txt").success());
        Path original = workspace;
        if (variation.equals("wrong-workspace")) workspace = Files.createDirectory(workspace.resolve("different"));
        try { assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, finish(produced).completionStatus()); }
        finally { workspace = original; }
    }

    @ParameterizedTest @ValueSource(strings = {"Compare a.txt and b.txt.", "Read a.txt and b.txt."})
    void oversizedReadOnlyRequestCannotClaimCompleteCoverage(String prefix) {
        begin(prefix + "\n" + "x".repeat(dev.talos.runtime.task.AnswerRequirements.MAX_REQUEST_CHARS));
        var messages = List.of(ChatMessage.user(request));
        var outcomes = List.of(new ToolCallLoop.ToolOutcome("talos.read_file", "a.txt", true, false, false, "left", ""),
                new ToolCallLoop.ToolOutcome("talos.read_file", "b.txt", true, false, false, "right", ""));
        var loop = new ToolCallLoop.LoopResult("All work is complete.", 1, 2, List.of("talos.read_file"), messages,
                0, 0, false, 0, List.of("a.txt", "b.txt"), 0, 0, 0, 0, outcomes);
        var answer = ExecutionOutcome.fromToolLoop(loop.finalAnswer(), messages, loop, workspace, 0);
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, answer.completionStatus());
        assertTrue(answer.finalAnswer().contains("bounded interpretation limit"), answer.finalAnswer());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE,
                ExecutionOutcome.fromNoTool(loop.finalAnswer(), messages, null, true).completionStatus());
    }

    @ParameterizedTest @ValueSource(strings = {"normal", "oversized-success", "oversized-failure"})
    void successfulCommandCannotOverrideUnestablishedRequestCoverage(String variant) {
        boolean oversized = !variant.equals("normal");
        boolean success = !variant.equals("oversized-failure");
        begin("Verify that the Gradle tests pass." + (oversized ? "\n" + "x".repeat(dev.talos.runtime.task.AnswerRequirements.MAX_REQUEST_CHARS) : ""));
        var messages = List.of(ChatMessage.user(request));
        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(TaskContractResolver.fromMessages(messages),
                dev.talos.runtime.phase.ExecutionPhase.VERIFY, List.of("talos.run_command"), List.of("talos.run_command"), List.of());
        assertEquals(dev.talos.runtime.task.TaskType.VERIFY_ONLY, plan.taskContract().type());
        var outcome = new ToolCallLoop.ToolOutcome("talos.run_command", "", success, false, false,
                success ? "Command succeeded: gradle_test exited with code 0 after 31ms" : "",
                success ? "" : "Command exited with code 1", null, "");
        var loop = new ToolCallLoop.LoopResult("Everything is complete.", 1, 1, List.of("talos.run_command"), messages,
                success ? 0 : 1, 0, false, 0, List.of(), 0, 0, 0, 0, List.of(outcome));
        var answer = ExecutionOutcome.fromToolLoop(loop.finalAnswer(), plan, messages, loop, workspace, 0);
        assertEquals(!success ? ExecutionOutcome.CompletionStatus.FAILED : oversized
                ? ExecutionOutcome.CompletionStatus.ADVISORY_ONLY : ExecutionOutcome.CompletionStatus.COMPLETE, answer.completionStatus());
        assertTrue(answer.finalAnswer().contains(success ? "Command succeeded" : "Command failed"), answer.finalAnswer());
        if (oversized && success) assertTrue(answer.finalAnswer().contains("bounded interpretation limit"));
    }

    @ParameterizedTest @ValueSource(strings = {"pdf", "docx"})
    void publicExtractedDocumentIsComparedThroughRealProducerAndAnswer(String extension) throws Exception {
        String content = "The annual public meeting is on Tuesday.";
        if (extension.equals("docx")) {
            try (var document = new XWPFDocument()) {
                document.createParagraph().createRun().setText(content);
                try (var stream = Files.newOutputStream(workspace.resolve("left.docx"))) { document.write(stream); }
            }
        } else {
            try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
                var page = new org.apache.pdfbox.pdmodel.PDPage();
                document.addPage(page);
                try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(20, 700);
                    stream.showText(content);
                    stream.endText();
                }
                document.save(workspace.resolve("left.pdf").toFile());
            }
        }
        Files.writeString(workspace.resolve("right.txt"), "The annual public meeting is on Friday.\n");
        config.data.put("document_extraction", Map.of("enabled", true, "pdf", Map.of("enabled", true), "word", Map.of("enabled", true)));
        begin("Compare left." + extension + " and right.txt.");
        var produced = produce("left." + extension, "right.txt");
        assertTrue(produced.success(), produced.errorMessage());
        var answer = finish(produced);
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, answer.completionStatus(), answer.finalAnswer());
        assertTrue(answer.finalAnswer().contains("extracted text only"));
        assertTrue(answer.finalAnswer().contains(content));
        assertTrue(answer.finalAnswer().contains("Friday"));
    }
}
