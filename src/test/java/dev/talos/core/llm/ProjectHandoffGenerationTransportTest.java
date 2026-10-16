package dev.talos.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.cli.modes.UnifiedAssistantMode;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.engine.compat.CompatChatClient;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.intent.WorkStatusRequest;
import dev.talos.runtime.policy.ProjectHandoffPolicy;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.RequestMessageLayout;
import dev.talos.spi.types.TokenChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Real selector and HTTP serialization are checked with synthetic provider responses. */
@Timeout(20)
class ProjectHandoffGenerationTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HANDOFF = "Let's stop here. Give me a short handoff. What is known, what remains "
            + "unverified, and what should happen next? Can this be called tested and ready to ship? "
            + "Keep proposed work separate from anything actually done in this conversation. "
            + "Don't change or run anything.";
    @TempDir Path workspace;

    @AfterEach void clear() {
        PromptDebugCapture.clear();
        ProviderResponseCapture.clear();
        dev.talos.runtime.trace.LocalTurnTraceCapture.clear();
        dev.talos.runtime.TurnTaskContractCapture.clear();
        dev.talos.runtime.TurnTraceCapture.clear();
        dev.talos.cli.prompt.LastPromptCapture.clear();
        if (dev.talos.runtime.TurnAuditCapture.isActive()) dev.talos.runtime.TurnAuditCapture.end();
    }

    @ParameterizedTest
    @CsvSource({"true,SINGLE_LEADING_SYSTEM", "false,SINGLE_LEADING_SYSTEM",
            "true,PRESERVE_AS_WRITTEN", "false,PRESERVE_AS_WRITTEN"})
    void realSelectorBindsClosedSchemaToCapturedHistory(boolean streaming, RequestMessageLayout layout) throws Exception {
        try (var fixture = new Fixture(streaming, layout, true, "selected")) {
            var memory = memory();
            var settled = fixture.run(memory);
            assertEquals(1, fixture.sends.get());
            var body = JSON.readTree(fixture.body.get());
            assertEquals("json_schema", body.at("/response_format/type").asText());
            assertEquals(JSON.readTree(ProjectHandoffPolicy.capture(memory.getTurns(),
                            dev.talos.runtime.intent.WorkStatusRequest.parse(HANDOFF),
                            dev.talos.safety.ContentRestrictions.UNRESTRICTED).advisorySchema()),
                    body.at("/response_format/json_schema/schema"));
            assertEquals(512, body.path("max_tokens").asInt());
            assertEquals("none", body.path("tool_choice").asText());
            assertFalse(body.has("tools"));
            assertFalse(body.has("continue_final_message"));
            assertEquals(2, body.path("messages").size());
            assertEquals("system", body.at("/messages/0/role").asText());
            assertEquals("user", body.at("/messages/1/role").asText());
            assertFalse(body.at("/messages/0/content").asText().contains("Mara"));
            assertTrue(body.at("/messages/1/content").asText().contains("Packaging remains pending with Mara."));
            var selectionInput = JSON.readTree(body.at("/messages/1/content").asText());
            assertEquals(HANDOFF, selectionInput.path("current_request").asText(),
                    "The captured current handoff question must reach the actual provider body");
            assertTrue(selectionInput.path("references").isArray());
            assertEquals(5, selectionInput.path("requested_results").size());
            assertEquals("and what should happen next", selectionInput.path("requested_results").get(3).path("text").asText());
            assertTrue(body.at("/response_format/json_schema/schema/required").toString().contains("request-4"));
            var controls = PromptDebugCapture.latest().orElseThrow().controls();
            assertTrue(controls.singleAttempt());
            assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, controls.toolAuthorityMode());
            assertTrue(text(settled.result()).contains(ProjectHandoffPolicy.literal("Packaging remains pending with Mara.")));
            assertTrue(text(settled.result()).contains("Testing: not established"));
            assertTrue(text(settled.result()).contains("Release acceptance: not established"));
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
            assertTrue(settled.audit().toolCalls().isEmpty());
            assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
            var terminal = settled.audit().localTrace().events().stream()
                    .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type())).reduce((first, last) -> last).orElseThrow().data();
            assertEquals("MODEL_PROVIDER", terminal.get("origin"));
            assertFalse(terminal.get("sourceGenerationId").toString().isBlank());
            assertFalse(terminal.get("sourceAttemptId").toString().isBlank());
            String sources = dev.talos.runtime.policy.ProjectHandoffSourceAccess.render(
                    new dev.talos.runtime.TurnRecord(settled.turnNumber(), java.time.Instant.now(), 0, HANDOFF,
                            text(settled.result()), List.of(), 0, 0, 0, "", "ok", dev.talos.runtime.TurnPolicyTrace.empty(),
                            settled.audit().localTrace().traceId()), settled.audit().localTrace()).orElseThrow();
            assertTrue(sources.contains(ProjectHandoffPolicy.literal("Packaging remains pending with Mara.")), sources);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reasoning-limit", "fence", "schema-error", "unknown-id", "legacy", "missing-result"})
    void failedOrUnfinishedWireResponseRemainsUnavailableWithoutRetry(String response) throws Exception {
        try (var fixture = new Fixture(true, RequestMessageLayout.SINGLE_LEADING_SYSTEM, true, response)) {
            var settled = fixture.run(memory());
            assertEquals(1, fixture.sends.get());
            assertTrue(text(settled.result()).contains("Project handoff: useful admitted"));
            assertFalse(text(settled.result()).contains("REASONING_SENTINEL"));
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
            assertTrue(settled.audit().toolCalls().isEmpty());
            assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
            assertNull(dev.talos.runtime.TurnTaskContractCapture.get());
        }
    }

    @Test void everyEmptySelectionReachesWireAndRemainsAnExplicitProjectGap() throws Exception {
        try (var fixture = new Fixture(true, RequestMessageLayout.SINGLE_LEADING_SYSTEM, true, "empty")) {
            var settled = fixture.run(memory());
            String answer = text(settled.result());
            assertEquals(5, answer.split("Historical summary unavailable for this question", -1).length - 1);
            assertEquals(5, answer.split("Model-identified gap, unverified", -1).length - 1);
            assertFalse(answer.contains("New clarification proposal"));
            assertEquals(1, fixture.sends.get());
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
            assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
        }
    }

    @Test void optionalProseIsOmittedWithoutClippingRuntimeOrModelGaps() throws Exception {
        try (var fixture = new Fixture(false, RequestMessageLayout.PRESERVE_AS_WRITTEN, true, "expanded")) {
            String request = "Give me a short handoff. What remains unknown?";
            assertProjectQuestionOwned(request);
            var processor = new TurnProcessor((raw, ws, context) -> java.util.Optional.of(new dev.talos.runtime.Result.Ok(
                    dev.talos.cli.modes.AssistantTurnExecutor.execute(new java.util.ArrayList<>(List.of(
                                    dev.talos.spi.types.ChatMessage.user(raw))), ws, (Context) context,
                            new dev.talos.cli.modes.AssistantTurnExecutor.Options().responseMaxChars(3600)
                                    .runtimeIdentity(dev.talos.spi.types.ModelIdentitySnapshot.empty())).text())));
            var settled = processor.process(new Session(workspace, fixture.config), request,
                    Context.builder(fixture.config).memory(memory()).llm(fixture.client).build());
            String answer = text(settled.result());
            assertEquals(1, fixture.sends.get());
            assertTrue(answer.length() <= 3600, answer);
            assertTrue(answer.contains("Runtime activity and verification"), answer);
            assertTrue(answer.contains("No fresh workspace-content inspection or tool execution was performed"), answer);
            assertTrue(answer.contains("Optional advisory prose was omitted"), answer);
            assertEquals(2, answer.split("Model-identified gap, unverified", -1).length - 1, answer);
            assertFalse(answer.contains("[output truncated]"), answer);
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
        }
    }

    @Test void mixedIdentityKeepsIdentityAndUnresolvedInventoryWithoutGeneration() throws Exception {
        try (var fixture = new Fixture(false, RequestMessageLayout.PRESERVE_AS_WRITTEN, true, "expanded")) {
            var settled = fixture.run(memory(), "Who are you? Give me a short handoff. What remains unverified?");
            String answer = text(settled.result());
            assertEquals(0, fixture.sends.get(), "The existing request grammar does not admit this mixed request completely");
            assertTrue(answer.contains("Runtime identity"), answer);
            assertTrue(answer.contains("Who are you"), answer);
            assertTrue(answer.contains("What remains unverified"), answer);
            assertTrue(answer.contains("Runtime activity and verification"), answer);
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
        }
    }

    @Test void rawPrivateGeneratedTextCannotBypassRedactionThroughEscapes() throws Exception {
        try (var fixture = new Fixture(false, RequestMessageLayout.PRESERVE_AS_WRITTEN, true, "private-path")) {
            var settled = fixture.run(memory());
            String answer = text(settled.result());
            assertTrue(answer.contains("Project handoff: useful admitted"), answer);
            assertFalse(answer.contains("private"), answer);
            assertFalse(answer.contains("{U+003A}"), answer);
            assertEquals(1, fixture.sends.get());
            assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Give me a short handoff. What remains unknown?",
            "Give me a handoff. What should happen next?"})
    void distinctCurrentRequestsReachWireWithoutChangingSelectableHistory(String request) throws Exception {
        try (var fixture = new Fixture(true, RequestMessageLayout.SINGLE_LEADING_SYSTEM, true, "selected")) {
            assertProjectQuestionOwned(request);
            var settled = fixture.run(memory(), request);
            var body = JSON.readTree(fixture.body.get());
            var input = JSON.readTree(body.at("/messages/1/content").asText());
            assertEquals(request, input.path("current_request").asText());
            assertEquals(2, input.path("references").size());
            assertFalse(body.at("/messages/0/content").asText().contains(request));
            assertFalse(body.at("/response_format/json_schema/schema").toString().contains(request));
            assertEquals(1, fixture.sends.get());
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
            assertTrue(settled.audit().toolCalls().isEmpty());
        }
    }

    @Test void explicitRuntimeUnverifiedQuestionStaysColdWithoutInventingProjectGaps() throws Exception {
        String request = "Give me a short handoff. What remains unverified?";
        var parsed = WorkStatusRequest.parse(request);
        assertEquals(WorkStatusRequest.Coverage.COVERED, parsed.coverage());
        assertTrue(parsed.runtimeOnly());
        assertFalse(parsed.requiresOperationDetails());
        assertFalse(ProjectHandoffPolicy.required(parsed));
        assertTrue(ProjectHandoffPolicy.requestedResults(parsed).isEmpty());
        try (var fixture = new Fixture(true, RequestMessageLayout.SINGLE_LEADING_SYSTEM, true, "selected")) {
            var settled = fixture.run(memory(), request);
            String answer = text(settled.result());
            assertEquals(0, fixture.preparations.get());
            assertEquals(0, fixture.sends.get());
            assertNull(fixture.body.get());
            assertTrue(settled.audit().toolCalls().isEmpty());
            assertEquals("NOT_RUN", settled.audit().localTrace().verification().status());
            assertTrue(answer.contains("Current source agreement and missing verification remain unestablished"), answer);
            assertTrue(answer.contains("Recorded PASS does not establish current testing or candidate acceptance"), answer);
            assertTrue(answer.contains("Runtime status records are unavailable in the retained window"), answer);
            assertTrue(answer.contains("No fresh workspace-content inspection or tool execution was performed"), answer);
            assertFalse(answer.contains("Model-identified gap"), answer);
            assertFalse(answer.contains("Original project question"), answer);
            assertFalse(answer.contains("[output truncated]"), answer);
            // Complete status presentation does not certify testing, current state or release acceptance.
            assertTrue(settled.audit().workStatusReceipt().presentationComplete());
            assertEquals("READ_ONLY_ANSWERED", settled.audit().localTrace().outcome().classification());
        }
    }

    private static void assertProjectQuestionOwned(String request) {
        var parsed = WorkStatusRequest.parse(request);
        assertEquals(WorkStatusRequest.Coverage.COVERED, parsed.coverage());
        assertFalse(parsed.runtimeOnly());
        assertTrue(parsed.components().stream().anyMatch(component ->
                parsed.domain(component) == WorkStatusRequest.EvidenceDomain.PROJECT));
        assertTrue(ProjectHandoffPolicy.required(parsed));
        assertEquals(2, ProjectHandoffPolicy.requestedResults(parsed).size());
    }

    @Test void modelDisallowedCurrentInputDoesNotPrepareOrDispatch() throws Exception {
        try (var fixture = new Fixture(true, RequestMessageLayout.SINGLE_LEADING_SYSTEM, true, "selected")) {
            var processor = new TurnProcessor((raw, ws, context) -> {
                var output = dev.talos.cli.modes.AssistantTurnExecutor.execute(new java.util.ArrayList<>(List.of(
                        dev.talos.spi.types.ChatMessage.user(raw).withContentRestrictions(
                                new dev.talos.safety.ContentRestrictions(false, true, true, true)))),
                        ws, (Context) context, new dev.talos.cli.modes.AssistantTurnExecutor.Options());
                return java.util.Optional.of(new dev.talos.runtime.Result.Ok(output.text()));
            });
            var settled = processor.process(new Session(workspace, fixture.config), HANDOFF,
                    Context.builder(fixture.config).memory(memory()).llm(fixture.client).build());
            assertEquals(0, fixture.sends.get());
            assertEquals(0, fixture.preparations.get());
            assertNull(fixture.body.get());
            assertTrue(settled.audit().toolCalls().isEmpty());
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
        }
    }

    @Test void malformedOwnedCurrentRequestCannotGenerate() throws Exception {
        String request = HANDOFF + "\n\u001b[31m";
        var assessment = dev.talos.runtime.intent.WorkStatusRequest.parse(request);
        assertTrue(assessment.selected());
        assertEquals(dev.talos.runtime.intent.WorkStatusRequest.Coverage.INVALID, assessment.coverage());
        assertTrue(dev.talos.runtime.policy.WorkStatusAnswerPolicy.requestFor(
                dev.talos.runtime.task.TaskContractResolver.fromUserRequest(request)).isPresent());
        try (var fixture = new Fixture(true, RequestMessageLayout.SINGLE_LEADING_SYSTEM, true, "selected")) {
            var settled = fixture.run(memory(), request);
            assertEquals(0, fixture.sends.get());
            assertEquals(0, fixture.preparations.get());
            assertNull(fixture.body.get());
            assertTrue(settled.audit().toolCalls().isEmpty());
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
            assertNull(dev.talos.runtime.TurnTaskContractCapture.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableSchemaDoesNotSendOrPrepareAnUnconstrainedRequest(boolean streaming) throws Exception {
        try (var fixture = new Fixture(streaming, RequestMessageLayout.PRESERVE_AS_WRITTEN, false, "selected")) {
            var settled = fixture.run(memory());
            assertEquals(0, fixture.sends.get());
            assertEquals(0, fixture.preparations.get());
            assertNull(fixture.body.get());
            assertTrue(text(settled.result()).contains("Project handoff: useful admitted"));
            assertTrue(settled.audit().toolCalls().isEmpty());
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
        }
    }

    private static SessionMemory memory() {
        var memory = new SessionMemory();
        memory.update("What is pending?", "Packaging remains pending with Mara.");
        return memory;
    }

    private static String text(dev.talos.runtime.Result result) {
        return switch (result) {
            case dev.talos.runtime.Result.Ok ok -> ok.text;
            case dev.talos.runtime.Result.Streamed streamed -> streamed.fullText;
            default -> fail("An answer was expected");
        };
    }

    private final class Fixture implements AutoCloseable {
        final AtomicReference<String> body = new AtomicReference<>();
        final AtomicInteger sends = new AtomicInteger();
        final AtomicInteger preparations = new AtomicInteger();
        final HttpServer server;
        final HttpClient http = HttpClient.newHttpClient();
        final Config config = new Config(null);
        final LlmClient client;

        Fixture(boolean streaming, RequestMessageLayout layout, boolean schema, String responseKind) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                sends.incrementAndGet();
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                boolean length = "reasoning-limit".equals(responseKind);
                var input = JSON.readTree(JSON.readTree(body.get()).path("messages").get(1).path("content").asText());
                var results = new LinkedHashMap<String, Object>();
                for (var result : input.path("requested_results"))
                    results.put(result.path("id").asText(), Map.of("summary", "empty".equals(responseKind) ? List.of()
                            : List.of(Map.of("text", "expanded".equals(responseKind) ? "A.B/".repeat(160)
                                    : "private-path".equals(responseKind) ? "C:/private/acme.txt" : "Packaging was reported pending, not performed.", "sources",
                                    List.of("unknown-id".equals(responseKind) ? "reference-999" : "reference-1"))),
                            "proposal", List.of(), "gap", "empty".equals(responseKind) ? "No useful historical summary was supplied."
                                    : "expanded".equals(responseKind) ? "Current acceptance remains unresolved." : ""));
                String valid = JSON.writeValueAsString(results);
                String selected = switch (responseKind) {
                    case "reasoning-limit" -> "";
                    case "fence" -> "```json\n" + valid + "\n```";
                    case "legacy" -> "{\"references\":[\"reference-1\"]}";
                    case "missing-result" -> "{\"request-1\":[\"reference-1\"]}";
                    default -> valid;
                };
                String reply = JSON.writeValueAsString(Map.of("choices", List.of(Map.of(
                        streaming ? "delta" : "message", Map.of("role", "assistant", "content", selected,
                                "reasoning_content", length ? "REASONING_SENTINEL" : ""),
                        "finish_reason", length ? "length" : "stop"))));
                boolean error = "schema-error".equals(responseKind);
                if (error) reply = "{\"error\":{\"message\":\"Synthetic schema rejection\",\"type\":\"invalid_request_error\"}}";
                else if (streaming) reply = "data: " + reply + "\n\ndata: [DONE]\n\n";
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", streaming && !error ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(error ? 400 : 200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
                exchange.close();
            });
            server.start();
            var adapter = new CompatChatClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "fixture-model", http, JSON);
            var resolver = new LlmEngineResolver() {
                @Override public void select(String backend, String model) { }
                @Override public void close() { }
                @Override public boolean capabilitiesAuthoritative() { return true; }
                @Override public Capabilities capabilities() {
                    return Capabilities.of(true, streaming, false, 16_384, false, false, false, false, schema, false, false);
                }
                @Override public Capabilities prepareCapabilities() {
                    preparations.incrementAndGet();
                    return capabilities();
                }
                @Override public Stream<TokenChunk> chatStream(ChatRequest request) {
                    throw new AssertionError("An unobserved dispatch was attempted");
                }
                @Override public Stream<TokenChunk> chatStream(ChatRequest request, ProviderTransportHandoff handoff) throws Exception {
                    return streaming ? adapter.chatStream(request, layout, handoff)
                            : adapter.chatStreamNonStreaming(request, layout, CompatChatClient.NativeToolPrefill.NONE, handoff);
                }
                @Override public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request, ProviderTransportHandoff handoff) throws Exception {
                    return adapter.chatStreamNonStreaming(request, layout, CompatChatClient.NativeToolPrefill.NONE, handoff);
                }
            };
            config.data.put("llm", new LinkedHashMap<>(Map.of("transport", "engine", "default_backend", "llama_cpp")));
            config.data.put("llama_cpp", new LinkedHashMap<>(Map.of("model", "fixture-model")));
            client = new LlmClient(config, resolver);
        }

        dev.talos.runtime.TurnResult run(SessionMemory memory) throws Exception {
            return run(memory, HANDOFF);
        }

        dev.talos.runtime.TurnResult run(SessionMemory memory, String request) throws Exception {
            var processor = new TurnProcessor((raw, ws, context) -> new UnifiedAssistantMode().handle(raw, ws, (Context) context));
            return processor.process(new Session(workspace, config), request,
                    Context.builder(config).memory(memory).llm(client).build());
        }

        @Override public void close() {
            client.close();
            server.stop(0);
            http.shutdownNow();
        }
    }
}
