package dev.talos.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.modes.UnifiedAssistantMode;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.ExactAnswerRenderFixture;
import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.*;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Real direct routes receive identical JSON with and without an actual provider EOS. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AssistantDirectGenerationSettlementTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DRAFT_REQUEST = "Please save story.txt now. Write a short story about Lina, a clockmaker "
            + "in a seaside town whose clock is always seven minutes slow. Do not create notes.txt.";
    private static final String STORY = "Lina waited beside the sea until her clock caught the tide.";
    private static final String HANDOFF = "Let's stop here. Give me a short handoff. What is known, what remains "
            + "unverified, and what should happen next? Can this be called tested and ready to ship? "
            + "Keep proposed work separate from anything actually done in this conversation. "
            + "Don't change or run anything.";
    private static final String SUMMARY = "Earlier statements remain historical.";
    @TempDir Path temp;
    private Path workspace;
    private String priorHome;

    @BeforeEach void isolate() throws Exception {
        workspace = Files.createDirectory(temp.resolve("workspace"));
        priorHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectory(temp.resolve("home")).toString());
    }

    @AfterEach void clear() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        TurnSourceEvidenceCapture.clear();
        TurnTraceCapture.clear();
        ProviderResponseCapture.clear();
        PromptDebugCapture.clear();
        dev.talos.cli.prompt.LastPromptCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        if (priorHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", priorHome);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void contentDraftRequiresProviderEosEvenWhenJsonIsComplete(boolean providerEos) throws Exception {
        var memory = new SessionMemory();
        String json = JSON.writeValueAsString(Map.of("kind", "draft", "text", STORY));
        Run run = run(DRAFT_REQUEST, json, providerEos, memory);
        assertEquals(DRAFT_REQUEST, run.request().messages.getLast().content());
        assertEquals(0, run.request().controls.maxOutputTokens());
        assertEquals(dev.talos.runtime.context.ContentDraftProposal.SCHEMA, run.request().controls.jsonSchema());
        if (providerEos) {
            assertNotNull(memory.pendingContentDraft(), "Normal STOP must still admit this exact JSON draft");
            assertEquals(STORY, memory.pendingContentDraft().content());
            assertTrue(run.answer().contains(STORY), run.answer());
            assertTrue(run.display().contains(STORY), run.display());
        } else {
            assertNull(memory.pendingContentDraft(), "Unsettled generation must not become selectable content");
            assertEquals("\nA complete draft could not be prepared. No write was attempted. "
                    + "Please restate the full content request.\n\n", run.answer());
            assertFalse(run.answer().contains(STORY), run.answer());
            assertFalse(run.display().contains(STORY), run.display());
            assertFalse(run.answer().contains(json), run.answer());
            assertTrue(run.display().contains("No write was attempted"), run.display());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void projectHandoffRequiresProviderEosEvenWhenJsonIsComplete(boolean providerEos) throws Exception {
        var memory = new SessionMemory();
        memory.update("What about packaging?", "Packaging remains pending with Mara.");
        var slots = new LinkedHashMap<String, Object>();
        for (String id : List.of("request-1", "request-2", "request-3", "request-4", "request-7")) {
            slots.put(id, Map.of("summary", List.of(Map.of("text", SUMMARY,
                    "sources", List.of("reference-1"))), "proposal", List.of(), "gap", ""));
        }
        String json = JSON.writeValueAsString(slots);
        Run run = run(HANDOFF, json, providerEos, memory);
        assertEquals(HANDOFF, JSON.readTree(run.request().messages.getLast().content())
                .path("current_request").asText());
        assertEquals(512, run.request().controls.maxOutputTokens());
        assertEquals(5, JSON.readTree(run.request().controls.jsonSchema()).path("required").size());
        var supplied = JSON.readTree(run.request().messages.getLast().content()).path("references");
        assertEquals(2, supplied.size());
        assertEquals("reference-1", supplied.get(1).path("id").asText());
        assertEquals("Packaging remains pending with Mara.", supplied.get(1).path("text").asText());
        assertNull(memory.pendingContentDraft());
        assertTrue(run.answer().contains("Testing: not established"), run.answer());
        assertTrue(run.answer().contains("Release acceptance: not established"), run.answer());
        assertFalse(run.turn().audit().workStatusReceipt().presentationComplete());
        String renderedSummary = dev.talos.runtime.policy.ProjectHandoffPolicy.literal(SUMMARY);
        if (providerEos) {
            assertTrue(run.answer().contains(renderedSummary), "Normal STOP must admit the same valid composition: " + run.answer());
            assertTrue(run.display().contains(renderedSummary), run.display());
        } else {
            assertTrue(run.answer().contains("Project handoff: useful admitted conversation references are unavailable"), run.answer());
            assertFalse(run.answer().contains(SUMMARY), run.answer());
            assertFalse(run.display().contains(SUMMARY), run.display());
            assertFalse(run.answer().contains(renderedSummary), run.answer());
            assertFalse(run.display().contains(renderedSummary), run.display());
            assertFalse(run.answer().contains("Packaging remains pending with Mara."), run.answer());
            assertFalse(run.answer().contains(json), run.answer());
            assertTrue(run.display().contains("Testing: not established"), run.display());
        }
    }

    private Run run(String request, String json, boolean providerEos, SessionMemory memory) throws Exception {
        assertTrue(JSON.readTree(json).isObject(), "The rejection witness must be syntactically complete JSON");
        var config = new Config(null);
        config.data.put("llm", new LinkedHashMap<>(Map.of("transport", "engine", "default_backend", "llama_cpp")));
        var resolver = new DirectResolver(json, providerEos);
        var registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        registry.register(new ReadFileTool());
        var approvals = new AtomicInteger();
        var processor = new TurnProcessor((raw, root, context) ->
                new UnifiedAssistantMode().handle(raw, root, (Context) context), (description, detail) -> {
                    approvals.incrementAndGet();
                    return false;
                }, registry, ApprovalPolicy.ALWAYS_ASK);
        var display = new ByteArrayOutputStream();
        var renderer = ExactAnswerRenderFixture.create(display, true, false, 160, new Redactor());
        try (var client = new LlmClient(config, resolver); var session = new Session(workspace, config, memory)) {
            assertTrue(client.supportsJsonSchemaResponse());
            client.setToolSpecs(registry.descriptors().stream()
                    .map(tool -> new ToolSpec(tool.name(), tool.description(), tool.parametersSchema())).toList());
            var context = Context.builder(config).llm(client).memory(memory).toolRegistry(registry)
                    .sandbox(new Sandbox(workspace, Map.of()))
                    .exactAnswerPresentation(renderer.exactAnswerPresentation())
                    .streamSink(renderer.answerStreamSink(null))
                    .toolCallLoop(new ToolCallLoop(processor, 4)).build();
            var turn = processor.process(session, request, context);
            assertEquals("", display.toString(StandardCharsets.UTF_8), "No pre-settlement fragments may be printed");
            var ok = assertInstanceOf(Result.Ok.class, turn.result());
            renderer.render(turn.result());
            assertEquals(1, resolver.requests.size(), "Missing EOS must not cause another generation attempt");
            ChatRequest sent = resolver.requests.getFirst();
            assertTrue(sent.tools.isEmpty());
            assertEquals(ToolChoiceMode.NONE, sent.controls.toolChoice());
            assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, sent.controls.toolAuthorityMode());
            assertEquals(ResponseFormatMode.JSON_SCHEMA, sent.controls.responseFormat());
            assertTrue(sent.controls.singleAttempt());
            var events = turn.audit().localTrace().events();
            assertEquals(1L, events.stream().filter(event -> "GENERATION_REQUEST_DISPATCHED".equals(event.type())).count());
            assertFalse(events.stream().anyMatch(event -> "GENERATION_TRANSPORT_UNOBSERVED".equals(event.type())));
            var responses = events.stream().filter(event -> "GENERATION_RESPONSE_CAPTURED".equals(event.type())).toList();
            assertEquals(1, responses.size());
            assertEquals(providerEos ? "PROVIDER_EOS" : "STREAM_WITHOUT_EOS", responses.getFirst().data().get("completion"));
            assertEquals("ADVISORY_ONLY", turn.audit().localTrace().outcome().classification());
            assertTrue(turn.audit().toolCalls().isEmpty());
            assertEquals(0, approvals.get());
            assertFalse(Files.exists(workspace.resolve("story.txt")));
            assertFalse(Files.exists(workspace.resolve("notes.txt")));
            return new Run(turn, sent, ok.text, display.toString(StandardCharsets.UTF_8));
        }
    }

    private record Run(TurnResult turn, ChatRequest request, String answer, String display) {}

    private static final class DirectResolver implements LlmEngineResolver {
        private final String json;
        private final boolean providerEos;
        private final List<ChatRequest> requests = new ArrayList<>();

        private DirectResolver(String json, boolean providerEos) {
            this.json = json;
            this.providerEos = providerEos;
        }

        @Override public void select(String backend, String model) { }
        @Override public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 16_384, false, false, false, false, true, false, true);
        }
        @Override public boolean capabilitiesAuthoritative() { return true; }
        @Override public Stream<TokenChunk> chatStream(ChatRequest request) {
            requests.add(request);
            if (requests.size() > 1) throw new AssertionError("Unexpected retry of direct generation");
            return providerEos ? Stream.of(TokenChunk.of(json), TokenChunk.eos("stop")) : Stream.of(TokenChunk.of(json));
        }
        @Override public Stream<TokenChunk> chatStream(ChatRequest request, ProviderTransportHandoff handoff) {
            return ObservedProviderTransportFixture.dispatch(request, handoff, () -> chatStream(request));
        }
        @Override public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request, ProviderTransportHandoff handoff) {
            return chatStream(request, handoff);
        }
        @Override public void close() { }
    }
}
