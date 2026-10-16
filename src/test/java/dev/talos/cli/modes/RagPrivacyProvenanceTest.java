package dev.talos.cli.modes;

import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.core.context.ContextResult;
import dev.talos.core.ingest.ParserUtil;
import dev.talos.core.index.LuceneStore;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.rag.RagService;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnTraceCapture;
import dev.talos.runtime.toolcall.ToolResultModelContextHandoff;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.impl.RetrieveTool;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Real harmless DOCX indexing and retrieval, actual mode/handoff boundaries, scripted provider only. */
class RagPrivacyProvenanceTest {
    private static final String PROSE = "Bluebird meadow ceramics workshop reference.";
    @TempDir Path root;
    private Path workspace;
    private String priorHome;
    private final List<LlmClient> clients = new ArrayList<>();

    @BeforeEach void isolate() throws Exception {
        workspace = Files.createDirectory(root.resolve("workspace"));
        priorHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectory(root.resolve("home")).toString());
        TurnAuditCapture.begin();
    }

    @AfterEach void restore() {
        clients.forEach(LlmClient::close);
        if (priorHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", priorHome);
        TurnAuditCapture.end();
        ContentRestrictionScope.end();
        LastPromptCapture.clear();
        TurnTaskContractCapture.clear();
        TurnTraceCapture.clear();
        LocalTurnTraceCapture.clear();
        ContextLedgerCapture.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"secrets/notes.md", "protected/notes.md", ".env"})
    void protectedPinsNeverEnterFirstProviderRequest(String path) throws Exception {
        Path source = workspace.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, PROSE);
        Config cfg = config(false, false);
        var provider = runMode(cfg, new RagService(cfg), "Explain \"" + path + "\".");
        assertFalse(provider.requests().isEmpty());
        assertFalse(text(provider.requests().getFirst().messages).contains(PROSE));
        assertFalse(text(LastPromptCapture.latest().orElseThrow().messages()).contains(PROSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"secrets/notes.md", "notes.md"})
    void protectedDirectAndBasenamePinsAreRejectedBeforeParsing(String requestedPath) throws Exception {
        Files.createDirectory(workspace.resolve("secrets"));
        Files.writeString(workspace.resolve("secrets/notes.md"), PROSE);
        Files.writeString(workspace.resolve("public.md"), "Ordinary public document.");
        AtomicInteger parses = new AtomicInteger();
        var mode = new RagMode(path -> {
            parses.incrementAndGet();
            assertEquals(workspace.resolve("public.md"), path);
            return ParserUtil.smartParse(path);
        });
        Config cfg = config(false, false);
        var provider = provider();
        mode.handle("Explain " + requestedPath + " and public.md.", workspace,
                Context.builder(cfg).rag(new RagService(cfg)).llm(provider.client()).build());
        assertEquals(1, parses.get(), "Only the admitted public pin may reach the parser");
        assertFalse(text(provider.requests().getFirst().messages).contains(PROSE));
        assertTrue(text(provider.requests().getFirst().messages).contains("Ordinary public document."));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void localDocumentIndexOptInDoesNotGrantRagModeModelOrArtifactPermission(boolean modelAllowed)
            throws Exception {
        Config cfg = config(true, modelAllowed);
        RagService rag = indexDocument(cfg);
        var provider = runMode(cfg, rag, "Explain the bluebird meadow ceramics workshop reference.");
        assertFalse(provider.requests().isEmpty());
        ChatRequest request = provider.requests().getFirst();
        assertEquals(modelAllowed, text(request.messages).contains(PROSE));
        if (modelAllowed) assertPrivateRequest(request);
    }

    @ParameterizedTest
    @CsvSource({"notes#draft.docx,false", "notes#draft.docx,true",
            "folder#draft/notes.docx,false", "folder#draft/notes.docx,true"})
    void embeddedDelimiterRetainsPrivacyThroughIndexRetrievalPackingAndToolHandoff(String source, boolean modelAllowed)
            throws Exception {
        Config cfg = config(true, modelAllowed);
        RagService rag = indexDocument(cfg, source);
        var prepared = rag.prepare(workspace, "bluebird meadow ceramics workshop", 5);
        var document = prepared.snippets().stream().filter(s -> s.text().contains(PROSE)).findFirst().orElseThrow();
        assertEquals(source, document.metadata().sourceIdentity().path());
        assertEquals(modelAllowed, document.contentRestrictions().modelAllowed());
        assertFalse(document.contentRestrictions().artifactAllowed());
        assertFalse(document.contentRestrictions().historyAllowed());
        assertTrue(document.contentRestrictions().indexAllowed());

        var provider = runMode(cfg, rag, "Explain the bluebird meadow ceramics workshop reference.");
        var request = provider.requests().getFirst();
        assertEquals(modelAllowed, text(request.messages).contains(PROSE));
        if (modelAllowed) assertPrivateRequest(request);

        var call = new ToolCall("talos.retrieve", Map.of("query", "bluebird meadow ceramics workshop"));
        var result = new RetrieveTool(rag).execute(call,
                new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg));
        assertTrue(result.success(), result.errorMessage());
        assertTrue(result.output().contains(PROSE));
        assertEquals(modelAllowed, result.contentMetadata().modelHandoffAllowed());
        assertFalse(result.contentMetadata().rawArtifactPersistenceAllowed());
        assertEquals(!modelAllowed,
                ToolResultModelContextHandoff.decide(call, null, "", result, null).contentWithheldFromModelContext());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retrieveToolRetainsDocumentPermissionsThroughRuntimeHandoff(boolean modelAllowed) throws Exception {
        Config cfg = config(true, modelAllowed);
        RagService rag = indexDocument(cfg);
        ToolCall call = new ToolCall("talos.retrieve", Map.of("query", "bluebird meadow ceramics workshop"));
        var result = new RetrieveTool(rag).execute(call,
                new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg));
        assertTrue(result.success(), result.errorMessage());
        assertTrue(result.output().contains(PROSE), "Local retrieval remains useful when model use is denied");
        assertEquals(modelAllowed, result.contentMetadata().modelHandoffAllowed());
        var decision = ToolResultModelContextHandoff.decide(call, null, "", result, null);
        assertEquals(!modelAllowed, decision.contentWithheldFromModelContext());
        assertEquals(modelAllowed, decision.modelResult().output().contains(PROSE));
        if (modelAllowed) {
            ChatRequest request = new ChatRequest("fixture", "fixture", "", "", List.of(),
                    Duration.ofSeconds(1), List.of(ChatMessage.toolResult("retrieve-1", decision.modelResult().output())));
            assertPrivateRequest(request);
        }
    }

    @Test
    void revokingModelPermissionAfterIndexingAppliesToBothConsumers() throws Exception {
        RagService rag = indexDocument(config(true, true));
        Config denied = config(true, false);
        RagService deniedRag = new RagService(denied);
        var provider = runMode(denied, deniedRag, "Explain the bluebird meadow ceramics workshop reference.");
        assertFalse(text(provider.requests().getFirst().messages).contains(PROSE));
        var result = new RetrieveTool(deniedRag).execute(
                new ToolCall("talos.retrieve", Map.of("query", "bluebird meadow ceramics workshop")),
                new ToolContext(workspace, new Sandbox(workspace, Map.of()), denied));
        assertTrue(result.output().contains(PROSE));
        assertFalse(result.contentMetadata().modelHandoffAllowed());
        assertIndexed(rag);
    }

    @Test
    void ordinaryTextRetrievalRemainsModelAndHistoryEligible() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), PROSE);
        Config cfg = config(true, false);
        RagService rag = new RagService(cfg);
        rag.getIndexer().index(workspace, true);
        var provider = runMode(cfg, rag, "Explain the bluebird meadow ceramics workshop reference.");
        assertTrue(text(provider.requests().getFirst().messages).contains(PROSE));
        assertTrue(provider.requests().getFirst().contentRestrictions.historyAllowed());
    }

    @Test
    void disabledExtractionCannotDowngradeCachedDocumentProvenance() throws Exception {
        Config cfg = config(true, true);
        RagService original = indexDocument(cfg);
        var cached = original.prepare(workspace, "bluebird meadow ceramics workshop", 5);
        assertTrue(cached.snippets().stream().anyMatch(s -> s.text().contains(PROSE)));
        cfg.data.put("document_extraction", Map.of("enabled", false));
        RagService retained = cachedService(cfg, cached);
        var provider = runMode(cfg, retained, "Explain the bluebird meadow ceramics workshop reference.");
        assertFalse(text(provider.requests().getFirst().messages).contains(PROSE));
        var result = new RetrieveTool(retained).execute(
                new ToolCall("talos.retrieve", Map.of("query", "bluebird meadow")),
                new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg));
        assertTrue(result.output().contains(PROSE));
        assertFalse(result.contentMetadata().modelHandoffAllowed());
        assertFalse(result.contentMetadata().rawArtifactPersistenceAllowed());
    }

    @Test
    void mixedRetrievalKeepsPublicRagContextButIntersectsWholeToolResult() throws Exception {
        Config cfg = config(true, false);
        RagService original = indexDocument(cfg);
        var document = original.prepare(workspace, "bluebird meadow ceramics workshop", 5);
        var mixed = new ArrayList<>(document.snippets());
        mixed.add(new ContextResult.Snippet("public.md#0", "Public orchard instruction."));
        RagService retained = cachedService(cfg, new RagService.Prepared(mixed, List.of()));
        var provider = runMode(cfg, retained, "Explain the workshop reference and orchard instruction.");
        String sent = text(provider.requests().getFirst().messages);
        assertFalse(sent.contains(PROSE));
        assertTrue(sent.contains("Public orchard instruction."));
        var call = new ToolCall("talos.retrieve", Map.of("query", "workshop orchard"));
        var result = new RetrieveTool(retained).execute(call,
                new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg));
        assertTrue(result.output().contains(PROSE));
        assertTrue(result.output().contains("Public orchard instruction."));
        assertFalse(result.contentMetadata().modelHandoffAllowed());
        assertTrue(ToolResultModelContextHandoff.decide(call, null, "", result, null).contentWithheldFromModelContext());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void legacyAskCarriesPackedPolicyIntoProviderRequest(boolean modelAllowed, boolean activeTurn) throws Exception {
        Config cfg = config(true, modelAllowed);
        var prepared = indexDocument(cfg).prepare(workspace, "bluebird meadow ceramics workshop", 5);
        cfg.data.put("net", Map.of("enabled", true));
        var provider = provider();
        RagService legacy = new RagService(cfg) {
            @Override public Prepared prepare(Path ws, String query, Integer k) { return prepared; }
            @Override protected LlmClient createLlmClient() { return provider.client(); }
        };
        if (!activeTurn) TurnAuditCapture.end();
        var answer = legacy.ask(workspace, "Explain the workshop reference.", 5);
        assertFalse(provider.requests().isEmpty(), answer.text());
        var request = provider.requests().getFirst();
        assertTrue(request.messages.isEmpty(), "Legacy request and sampling path must be retained");
        assertEquals(modelAllowed, request.flattenedContext().contains(PROSE));
        if (modelAllowed) assertPrivateRequest(request);
        if (!activeTurn) assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
        else if (modelAllowed) assertFalse(ContentRestrictionScope.current().historyAllowed());
    }

    @Test
    void quotedDocumentPinCannotEnterThroughPlainTextParser() throws Exception {
        Config cfg = config(true, false);
        indexDocument(cfg);
        var provider = runMode(cfg, cachedService(cfg, new RagService.Prepared(List.of(), List.of())),
                "Explain \"notes.docx\".");
        assertFalse(text(provider.requests().getFirst().messages).contains(PROSE));
    }

    @Test
    void endedTurnRetainsPrivateRequestRestrictionsWithoutContaminatingNextTurn() throws Exception {
        Config cfg = config(true, true);
        var provider = runMode(cfg, indexDocument(cfg), "Explain the bluebird meadow ceramics workshop reference.");
        var privateRequest = provider.requests().getFirst();
        var audit = TurnAuditCapture.end();
        assertFalse(audit.contentRestrictions().historyAllowed());
        assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
        assertPrivateRequest(privateRequest);
        TurnAuditCapture.begin();
        var next = new ChatRequest("fixture", "fixture", "", "", List.of(), Duration.ofSeconds(1),
                List.of(ChatMessage.user("Ordinary next turn.")));
        assertEquals(ContentRestrictions.UNRESTRICTED, next.contentRestrictions);
    }

    private static RagService cachedService(Config cfg, RagService.Prepared prepared) {
        return new RagService(cfg) {
            @Override public Prepared prepare(Path ws, String query, Integer k) { return prepared; }
        };
    }

    private RagService indexDocument(Config cfg) throws Exception {
        return indexDocument(cfg, "notes.docx");
    }

    private RagService indexDocument(Config cfg, String source) throws Exception {
        Files.createDirectories(workspace.resolve(source).getParent());
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(PROSE);
            try (var out = Files.newOutputStream(workspace.resolve(source))) { document.write(out); }
        }
        RagService rag = new RagService(cfg);
        rag.getIndexer().index(workspace, true);
        assertIndexed(rag);
        return rag;
    }

    private void assertIndexed(RagService rag) {
        try (var store = new LuceneStore(rag.getIndexer().indexDirFor(workspace), 0)) {
            assertTrue(store.matchAll(20).stream().anyMatch(hit -> store.getTextByPath(hit.path()).contains(PROSE)),
                    "Local index permission must remain independent of model permission");
        }
    }

    private ScriptedNativeLlmClient.RecordedClient runMode(Config cfg, RagService rag, String question)
            throws Exception {
        var provider = provider();
        new RagMode().handle(question, workspace,
                Context.builder(cfg).rag(rag).llm(provider.client()).build()).orElseThrow();
        return provider;
    }

    private ScriptedNativeLlmClient.RecordedClient provider() {
        var provider = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(
                List.of(new LlmClient.StreamResult("The available context was inspected.", List.of())), 16_384);
        clients.add(provider.client());
        return provider;
    }

    private static void assertPrivateRequest(ChatRequest request) {
        assertFalse(request.contentRestrictions.artifactAllowed());
        assertFalse(request.contentRestrictions.historyAllowed());
        assertTrue(request.contentRestrictions.indexAllowed());
        assertEquals(ContentRestrictions.WITHHELD,
                PromptDebugSnapshot.fromProviderBody(request, false, PROSE).providerBodyJson());
        assertEquals(ContentRestrictions.WITHHELD, request.contentRestrictions.forHistory(PROSE));
    }

    private static String text(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::content).collect(Collectors.joining("\n"));
    }

    private static Config config(boolean privateMode, boolean modelAllowed) {
        Config cfg = new Config(null);
        cfg.data.put("privacy", Map.of("mode", privateMode ? "private" : "developer",
                "rag", Map.of("enabled_in_private_mode", true),
                "document_extraction", Map.of("allow_send_to_model", modelAllowed,
                        "allow_rag_indexing", true, "persist_raw_artifacts", false),
                "protected_read", Map.of("allow_send_to_model", false, "default_scope", "LOCAL_DISPLAY_ONLY")));
        cfg.data.put("document_extraction", Map.of("enabled", true, "word", Map.of("enabled", true)));
        cfg.data.put("embed", Map.of("provider", "disabled", "model", "disabled"));
        cfg.data.put("net", Map.of("enabled", false));
        cfg.data.put("rag", Map.of("vectors", Map.of("enabled", false), "includes", List.of("**/*.md", "**/*.docx")));
        return cfg;
    }
}
