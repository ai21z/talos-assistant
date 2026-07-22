package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.core.util.UiChrome;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abort truth (release-blocker follow-up to T988): an aborted generation
 * must be recorded as FAILED / LLM_ABORTED on BOTH provider paths, must
 * stay honestly visible to the user, and its partial output must never be
 * dressed up as a normal answer. The dangerous shape is partial text PLUS
 * a trailing abort marker - a prefix check on the final text misses it.
 */
@DisplayName("AssistantTurnExecutor abort truth")
class AssistantTurnExecutorAbortTruthTest {

    private static final String PARTIAL = "The first half of an answer that never finished";
    private static final String PASS_2B_TOOL_JSON_PARTIAL = """
            {"name":"talos.write_file","arguments":{"path":"index.html","content":"function one() { if (ready) { return 1; } }"}}
            {"name":"talos.write_file","arguments":{"path":"style.css","content":"@media screen { body { color: teal; } }"}}
            """;
    private static final String TOOL_JSON_THEN_ABORT_MARKER =
            PASS_2B_TOOL_JSON_PARTIAL + "\n[turn aborted: stream transport failed after partial output]";
    private static final String READ_TOOL_JSON_PARTIAL =
            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}";

    @Test
    void bufferedRepetitionAbortRecordsCanonicalReasonWithoutPartialOutput(@TempDir Path workspace) {
        String marker = "[turn aborted: non-streaming chat entered a repetition loop]";
        LlmClient.StreamResult result = new LlmClient.StreamResult(
                marker, List.of(), "", "repetition loop");

        LocalTurnTraceCapture.begin(
                "trc-abort-repetition",
                "sid",
                1,
                "2026-07-14T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Read README.md and summarize it.");
        try {
            AssistantTurnExecutor.recordLlmAbortOutcome(result, marker);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            var abortEvents = trace.events().stream()
                    .filter(event -> "MODEL_GENERATION_ABORTED".equals(event.type()))
                    .toList();
            assertEquals(1, abortEvents.size(), trace.events().toString());
            assertEquals("REPETITION_LOOP", abortEvents.getFirst().data().get("reasonCode"));
            assertEquals(Boolean.FALSE, abortEvents.getFirst().data().get("partialOutput"));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void abortTraceUsesOnlyBoundedReasonCodesAndKeepsPartialOutputSeparate() {
        assertAbortEvent("wall-clock budget exceeded", "[turn aborted: budget]",
                "WALL_CLOCK_BUDGET_EXCEEDED", false);
        assertAbortEvent("idle timeout - no tokens", "[turn aborted: idle]",
                "IDLE_TIMEOUT", false);
        assertAbortEvent("interrupted", "[turn aborted: interrupted]",
                "INTERRUPTED", false);
        assertAbortEvent(
                "stream transport failed after partial output, retry skipped to avoid duplicate output",
                PARTIAL + "\n[turn aborted: stream transport failed after partial output]",
                "TRANSPORT_FAILURE",
                true);
        assertAbortEvent("provider detail that must not be persisted", "[turn aborted: provider detail]",
                "UNKNOWN", false);
        assertAbortEvent("", "[turn aborted: repetition loop]",
                "UNKNOWN", false);
    }

    @Test
    void typedExternalOperatorAbortRoutesWithOperatorOrigin() {
        String answer = "[turn aborted: interrupted]";
        LocalTurnTraceCapture.begin(
                "trc-abort-operator-origin",
                "sid",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Interrupt this turn.");
        try {
            AssistantTurnExecutor.recordLlmAbortOutcome(
                    new LlmClient.StreamResult(
                            answer,
                            List.of(),
                            "",
                            "interrupted",
                            ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL),
                    answer);
            LocalTurnTraceCapture.recordRoutedAnswerCaptured(answer);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            var terminal = trace.events().stream()
                    .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type()))
                    .findFirst()
                    .orElseThrow();
            var selection = trace.events().stream()
                    .filter(event -> "DETERMINISTIC_ANSWER_SELECTED".equals(event.type()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("OPERATOR", selection.data().get("origin"));
            assertEquals("OPERATOR", terminal.data().get("origin"));
            assertFalse(terminal.data().containsKey("sourceGenerationId"));
            assertEquals(List.of(
                            "TERMINAL_ANSWER_SELECTED",
                            "ROUTED_ANSWER_CAPTURED",
                            "OUTCOME_RENDERED",
                            "TRACE_COMPLETED"),
                    trace.events().stream()
                            .map(event -> event.type())
                            .filter(type -> type.equals("TERMINAL_ANSWER_SELECTED")
                                    || type.equals("ROUTED_ANSWER_CAPTURED")
                                    || type.equals("OUTCOME_RENDERED")
                                    || type.equals("TRACE_COMPLETED"))
                            .toList());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void operatorAbortWithPartialProviderOutputDoesNotClaimOperatorAuthorship() {
        String answer = "partial provider text\n[turn aborted: interrupted]";
        LocalTurnTraceCapture.begin(
                "trc-abort-operator-partial",
                "sid",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Interrupt this turn after output begins.");
        try {
            AssistantTurnExecutor.recordLlmAbortOutcome(
                    new LlmClient.StreamResult(
                            answer,
                            List.of(),
                            "",
                            "interrupted",
                            ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL),
                    answer);
            LocalTurnTraceCapture.recordRoutedAnswerCaptured(answer);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            var terminal = trace.events().stream()
                    .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("NONE", terminal.data().get("origin"));
            assertTrue(trace.events().stream()
                            .noneMatch(event -> "DETERMINISTIC_ANSWER_SELECTED".equals(event.type())),
                    "operator control of cancellation does not prove authorship of partial bytes");
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void nonOperatorAbortOriginsNeverInferOperatorFromInterruptedProse() {
        for (ProviderResponseCapture.AbortOrigin origin : List.of(
                ProviderResponseCapture.AbortOrigin.RUNTIME_WATCHDOG,
                ProviderResponseCapture.AbortOrigin.RUNTIME_THREAD_INTERRUPT,
                ProviderResponseCapture.AbortOrigin.RUNTIME_STREAM_CLOSE,
                ProviderResponseCapture.AbortOrigin.PROVIDER_TRANSPORT)) {
            String answer = "[turn aborted: interrupted]";
            LocalTurnTraceCapture.begin(
                    "trc-abort-non-operator-" + origin.name().toLowerCase(),
                    "sid",
                    1,
                    "2026-07-16T00:00:00Z",
                    "workspace-hash",
                    "agent",
                    "llama_cpp",
                    "test-model",
                    "Exercise a non-operator abort.");
            try {
                AssistantTurnExecutor.recordLlmAbortOutcome(
                        new LlmClient.StreamResult(answer, List.of(), "", "interrupted", origin),
                        answer);
                LocalTurnTraceCapture.recordRoutedAnswerCaptured(answer);
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();
                var terminal = trace.events().stream()
                        .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type()))
                        .findFirst()
                        .orElseThrow();
                assertEquals("NONE", terminal.data().get("origin"), origin.name());
                assertTrue(trace.events().stream()
                                .noneMatch(event -> "DETERMINISTIC_ANSWER_SELECTED".equals(event.type())),
                        origin.name());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }
    }

    @Test
    void abortMarkerFallbackInvalidatesPriorProviderCandidate() {
        String answer = "partial provider text\n[turn aborted: interrupted]";
        LocalTurnTraceCapture.begin(
                "trc-abort-marker-provider-candidate",
                "sid",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Exercise marker-only abort recovery.");
        try {
            LocalTurnTraceCapture.recordProviderAnswerSelected(
                    answer,
                    authenticProviderReceipt(answer));

            AssistantTurnExecutor.recordLlmAbortOutcome(
                    new LlmClient.StreamResult(answer, List.of()),
                    answer);
            LocalTurnTraceCapture.recordRoutedAnswerCaptured(answer);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            var terminal = trace.events().stream()
                    .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("NONE", terminal.data().get("origin"));
            assertFalse(terminal.data().containsKey("sourceGenerationId"));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    private static ProviderResponseCapture.Receipt authenticProviderReceipt(String answer) {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        ProviderResponseCapture.Receipt receipt = attempt.respond(
                new ProviderResponseCapture.Response(
                        answer,
                        0,
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false,
                        false));
        generation.complete(receipt);
        return receipt;
    }

    private static void assertAbortEvent(
            String rawReason,
            String answer,
            String expectedCode,
            boolean expectedPartialOutput) {
        LocalTurnTraceCapture.begin(
                "trc-abort-code-" + expectedCode,
                "sid",
                1,
                "2026-07-14T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Test abort capture.");
        try {
            AssistantTurnExecutor.recordLlmAbortOutcome(
                    new LlmClient.StreamResult(answer, List.of(), "", rawReason),
                    answer);
            var event = LocalTurnTraceCapture.complete().events().stream()
                    .filter(candidate -> "MODEL_GENERATION_ABORTED".equals(candidate.type()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(expectedCode, event.data().get("reasonCode"));
            assertEquals(expectedPartialOutput, event.data().get("partialOutput"));
            assertFalse(event.data().toString().contains("provider detail"), event.data().toString());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void streamingAbortAfterPartialOutputRecordsLlmAbortedAndShowsTheMarker(@TempDir Path workspace) {
        LlmClient llm = ScriptedNativeLlmClient.transientAfterPartialOutput(PARTIAL);
        List<String> chunks = new ArrayList<>();
        Context ctx = Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, Map.of()))
                .streamSink(chunks::add)
                .onStreamComplete(() -> { })
                .build();

        LocalTurnTraceCapture.begin(
                "trc-abort-streaming",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Explain briefly.");
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    AssistantTurnExecutor.execute(
                            messages("Explain briefly."),
                            workspace,
                            ctx,
                            new AssistantTurnExecutor.Options()));
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertEquals("turn generation ended without a completed response", failure.getMessage());
            assertNotNull(trace.outcome(), "a streamed abort must record a turn outcome");
            assertEquals("FAILED", trace.outcome().status());
            assertEquals("LLM_ABORTED", trace.outcome().classification());
            assertTrue(String.join("", chunks).contains(PARTIAL));
            assertTrue(String.join("", chunks).contains(UiChrome.TURN_ABORTED_PREFIX),
                    "the abort must be visible on the streamed surface, got chunks: " + chunks);
            assertFalse(String.join("", chunks).contains("retry-output"),
                    "an aborted streaming turn must not silently re-generate");
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void bufferedAbortAfterPartialOutputRecordsLlmAborted(@TempDir Path workspace) {
        LlmClient llm = ScriptedNativeLlmClient.transientAfterPartialOutput(PARTIAL);
        Context ctx = Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-abort-buffered",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Explain briefly.");
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    AssistantTurnExecutor.execute(
                            messages("Explain briefly."),
                            workspace,
                            ctx,
                            new AssistantTurnExecutor.Options()));
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertEquals("turn generation ended without a completed response", failure.getMessage());
            assertNotNull(trace.outcome(), "a buffered abort must record a turn outcome");
            assertEquals("FAILED", trace.outcome().status());
            assertEquals("LLM_ABORTED", trace.outcome().classification());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void bufferedTrailingMarkerWithoutMetadataStillRecordsLlmAborted(@TempDir Path workspace) {
        // Marker text without abort metadata (reassembled from plain chunks):
        // the shared line-anchored fallback must still classify the turn as
        // aborted instead of treating the partial as a normal answer.
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(
                        PARTIAL + "\n[turn aborted: interrupted]", List.of())),
                4096);
        Context ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-abort-lexical",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Explain briefly.");
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    AssistantTurnExecutor.execute(
                            messages("Explain briefly."),
                            workspace,
                            ctx,
                            new AssistantTurnExecutor.Options()));
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertEquals("turn generation ended without a completed response", failure.getMessage());
            assertNotNull(trace.outcome(), "a trailing abort marker must record a turn outcome");
            assertEquals("FAILED", trace.outcome().status());
            assertEquals("LLM_ABORTED", trace.outcome().classification());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void streamingAbortWithTextToolJsonRecordsAbortBeforeToolLoop(@TempDir Path workspace) {
        LlmClient client = ScriptedNativeLlmClient.transientAfterPartialOutput(
                "Visible prefix.\n" + PASS_2B_TOOL_JSON_PARTIAL);
        List<String> chunks = new ArrayList<>();
        Context ctx = Context.builder(new Config())
                .llm(client)
                .sandbox(new Sandbox(workspace, Map.of()))
                .streamSink(new ToolCallStreamFilter(chunks::add))
                .onStreamComplete(() -> { })
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null), 1))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-abort-streaming-tool-json",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Explain briefly.");
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    AssistantTurnExecutor.execute(
                            messages("Explain briefly."),
                            workspace,
                            ctx,
                            new AssistantTurnExecutor.Options()));
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertEquals("turn generation ended without a completed response", failure.getMessage());
            assertTrue(String.join("", chunks).contains("Visible prefix."));
            assertFalse(String.join("", chunks).contains("talos.write_file"),
                    "incomplete provider protocol must remain hidden");
            assertNotNull(trace.outcome(), "an aborted turn with text tool JSON must record a turn outcome");
            assertEquals("FAILED", trace.outcome().status());
            assertEquals("LLM_ABORTED", trace.outcome().classification());
            assertTrue(String.join("", chunks).contains(UiChrome.TURN_ABORTED_PREFIX),
                    "the abort must be visible on the streamed surface, got chunks: " + chunks);
            assertFalse(String.join("", chunks).contains("retry-output"),
                    "an aborted turn must not enter the tool loop or continue generation");
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void providerAuthoredAbortMarkerBesideValidToolJsonCannotSpoofRuntimeAbort(
            @TempDir Path workspace) {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(
                        TOOL_JSON_THEN_ABORT_MARKER,
                        List.of())),
                4096);
        Context ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .approvalGate((description, detail) -> true)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null), 1))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-abort-buffered-tool-json",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Create the files.");
        try {
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages("Create the files."),
                    workspace,
                    ctx,
                    new AssistantTurnExecutor.Options());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertFalse(out.text().contains("retry-output"),
                    "a provider-authored marker must not cause an unrelated retry");
            assertTrue(trace.events().stream().noneMatch(event ->
                            "MODEL_GENERATION_ABORTED".equals(event.type())),
                    "literal provider prose is not runtime abort metadata");
            if (trace.outcome() != null) {
                assertFalse("LLM_ABORTED".equals(trace.outcome().classification()),
                        trace.outcome().toString());
            }
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void streamingTransportFailureWithCompleteReadToolJsonDoesNotAuthorizeExecution(
            @TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "must not be read\n");
        LlmClient llm = ScriptedNativeLlmClient.transientTextToolAfterPartialOutput(
                READ_TOOL_JSON_PARTIAL);
        List<String> chunks = new ArrayList<>();
        ToolCallStreamFilter streamFilter = new ToolCallStreamFilter(chunks::add);
        AtomicInteger approvals = new AtomicInteger();
        ApprovalGate gate = (description, detail) -> {
            approvals.incrementAndGet();
            return true;
        };
        ToolRegistry registry = readRegistry();
        Context ctx = Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, Map.of()))
                .streamSink(streamFilter)
                .onStreamComplete(() -> { })
                .toolRegistry(registry)
                .nativeToolSpecs(specsFrom(registry))
                .toolCallLoop(new ToolCallLoop(
                        new TurnProcessor(null, gate, registry), 1))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-abort-streaming-complete-tool-json",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Explain briefly.");
        try {
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages("Explain briefly."),
                    workspace,
                    ctx,
                    new AssistantTurnExecutor.Options());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(out.streamed(), "the read-only request should use the streaming path");
            assertTrue(out.text().contains("retry-output"),
                    "a safely buffered failed attempt may be replaced by a later provider answer");
            assertFalse(out.text().contains("talos.read_file"), out.text());
            assertFalse(String.join("", chunks).contains("talos.read_file"),
                    "raw tool protocol must not reach the streamed surface: " + chunks);
            assertEquals(0, approvals.get(), "unsettled provider output must not request approval");
            assertTrue(trace.events().stream().noneMatch(event ->
                    "TOOL_EXECUTED".equals(event.type())));
            assertTrue(String.join("", chunks).contains("retry-output"), chunks.toString());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void bufferedTransportFailureWithCompleteWriteToolJsonDoesNotAuthorizeExecution(
            @TempDir Path workspace) {
        LlmClient llm = ScriptedNativeLlmClient.transientTextToolAfterPartialOutput(
                PASS_2B_TOOL_JSON_PARTIAL);
        AtomicInteger approvals = new AtomicInteger();
        ApprovalGate gate = (description, detail) -> {
            approvals.incrementAndGet();
            return true;
        };
        ToolRegistry registry = writeRegistry();
        Context ctx = Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, Map.of()))
                .approvalGate(gate)
                .toolRegistry(registry)
                .nativeToolSpecs(specsFrom(registry))
                .toolCallLoop(new ToolCallLoop(
                        new TurnProcessor(null, gate, registry), 1))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-abort-buffered-complete-tool-json",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Create the files.");
        try {
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages("Create the files."),
                    workspace,
                    ctx,
                    new AssistantTurnExecutor.Options());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertFalse(out.text().contains("talos.write_file"), out.text());
            assertEquals(0, approvals.get(), "unsettled provider output must not request approval");
            assertFalse(Files.exists(workspace.resolve("index.html")));
            assertFalse(Files.exists(workspace.resolve("style.css")));
            assertTrue(trace.events().stream().noneMatch(event ->
                    "TOOL_EXECUTED".equals(event.type())));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void normalToolTurnMayMentionAbortMarkerInsideToolContent(@TempDir Path workspace) {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(
                        "I will write it.\n"
                                + "<tool_call>{\"name\":\"talos.write_file\",\"parameters\":{\"path\":\"log.txt\",\"content\":\"[turn aborted: copied from a log]\"}}</tool_call>",
                        List.of(),
                        "",
                        "")),
                4096);
        Context ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .approvalGate((description, detail) -> true)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null), 1))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-normal-tool-json-with-marker",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Create log.txt.");
        try {
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages("Create log.txt."),
                    workspace,
                    ctx,
                    new AssistantTurnExecutor.Options());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertFalse(out.text().contains("LLM_ABORTED"), out.text());
            assertNotNull(trace.outcome(), "a normal tool turn should still record an outcome");
            assertFalse("LLM_ABORTED".equals(trace.outcome().classification()), trace.outcome().toString());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    private static List<ChatMessage> messages(String request) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(request));
        return messages;
    }

    private static ToolRegistry writeRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        return registry;
    }

    private static ToolRegistry readRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        return registry;
    }

    private static List<ToolSpec> specsFrom(ToolRegistry registry) {
        return registry.descriptors().stream()
                .map(descriptor -> new ToolSpec(
                        descriptor.name(),
                        descriptor.description(),
                        descriptor.parametersSchema()))
                .toList();
    }
}
