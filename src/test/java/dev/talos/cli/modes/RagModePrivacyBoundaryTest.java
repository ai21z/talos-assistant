package dev.talos.cli.modes;

import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ContextDecision;
import dev.talos.core.context.ContextItem;
import dev.talos.core.context.ContextItemSource;
import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.core.context.ContextPrivacyClass;
import dev.talos.core.context.ContextResult;
import dev.talos.core.context.ExecutionBoundary;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.rag.RagService;
import dev.talos.core.retrieval.RetrievalTrace;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnTraceCapture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Actual mode boundary with synthetic provider answers and instrumented retrieval. */
class RagModePrivacyBoundaryTest {
    private static final String PINNED_CONTENT = "PINNED_WORKSPACE_CONTENT_9871";
    private static final String RETRIEVED_CONTENT = "RETRIEVED_WORKSPACE_CONTENT_5432";
    private final List<LlmClient> ownedClients = new ArrayList<>();

    @TempDir Path workspace;

    @AfterEach
    void clearCaptures() {
        ownedClients.forEach(LlmClient::close);
        LastPromptCapture.clear();
        TurnTaskContractCapture.clear();
        TurnTraceCapture.clear();
        LocalTurnTraceCapture.clear();
        ContextLedgerCapture.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Without reading files, who are you?",
            "Do not inspect my files. Which model are you running?",
            "No file access. Explain dependency injection.",
            "Without reading files, who are you? Filename: notes.md.",
            "Have you changed files? Do not read files.",
            "Have you run tests or changed files during this conversation?",
            "Which model is actually answering me in this session? If you cannot verify it, say so."
    })
    void noInspectionRequestSkipsRetrievalPinsAndCitations(String request) throws Exception {
        Config cfg = config(false, false);
        var rag = new RecordingRagService(cfg);
        var provider = provider();
        Files.writeString(workspace.resolve("notes.md"), PINNED_CONTENT);
        Files.writeString(workspace.resolve("UNMENTIONED_WORKSPACE_ENTRY.md"), "Unmentioned.");

        Result result = handle(request, cfg, rag, provider);

        assertAll(
                () -> assertEquals(0, rag.prepareCalls, "Preparation can start lazy indexing"),
                () -> assertNoWorkspaceContext(result, provider),
                () -> assertEmptyExclusion("TURN_WORKSPACE_PRELOAD_DISABLED"),
                () -> assertNull(TurnTraceCapture.consume(), "A blocked turn has no retrieval trace"));
    }

    @Test
    void noInspectionIdentityDoesNotStartRealLazyIndexing(@TempDir Path isolatedHome) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());
        try {
            Config cfg = config(false, false);
            RagService rag = new RagService(cfg);
            var provider = provider();
            Files.writeString(workspace.resolve("notes.md"), PINNED_CONTENT);

            Result result = handle("Without reading files, who are you?", cfg, rag, provider);

            assertAll(
                    () -> assertNull(rag.getIndexer().getLastRunStats(), "Lazy indexing must not run"),
                    () -> assertFalse(Files.exists(isolatedHome.resolve(".talos/indices")),
                            "No index directories may be created"),
                    () -> assertTrue(provider.requests().isEmpty(), "Identity is answered without generation"),
                    () -> assertNoWorkspaceContext(result, provider));
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void privateModeBlocksPinnedContentsBeforeRetrievalService() throws Exception {
        Config cfg = config(true, false);
        var rag = new RecordingRagService(cfg);
        var provider = provider();
        Files.writeString(workspace.resolve("notes.md"), PINNED_CONTENT);

        Result result = handle("Explain notes.md.", cfg, rag, provider);

        assertAll(
                () -> assertEquals(0, rag.prepareCalls),
                () -> assertEmptyExclusion("PRIVATE_MODE_RAG_DISABLED"),
                () -> assertNoWorkspaceContext(result, provider));
    }

    @Test
    void combinedRestrictionsKeepBothMetadataOnlyExclusions() throws Exception {
        Config cfg = config(true, false);
        var rag = new RecordingRagService(cfg);
        var provider = provider();

        Result result = handle("Without reading files, explain dependency injection.", cfg, rag, provider);

        assertEquals(0, rag.prepareCalls);
        assertEmptyExclusion("PRIVATE_MODE_RAG_DISABLED");
        assertEmptyExclusion("TURN_WORKSPACE_PRELOAD_DISABLED");
        assertNoWorkspaceContext(result, provider);
    }

    @Test
    void privateModeRagOptInDoesNotOverrideNoInspectionRequest() throws Exception {
        Config cfg = config(true, true);
        var rag = new RecordingRagService(cfg);
        var provider = provider();
        Files.writeString(workspace.resolve("notes.md"), PINNED_CONTENT);

        Result result = handle("Without reading files, explain dependency injection.", cfg, rag, provider);

        assertAll(
                () -> assertEquals(0, rag.prepareCalls),
                () -> assertEmptyExclusion("TURN_WORKSPACE_PRELOAD_DISABLED"),
                () -> assertFalse(provider.requests().isEmpty(), "Ordinary direct answers still reach the provider"),
                () -> assertNoWorkspaceContext(result, provider));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permittedRagRetainsPinnedAndRetrievedEvidence(boolean privateMode) throws Exception {
        Config cfg = config(privateMode, true);
        var rag = new RecordingRagService(cfg);
        var provider = provider();
        Files.writeString(workspace.resolve("notes.md"), PINNED_CONTENT);

        Result result = handle("Explain notes.md.", cfg, rag, provider);

        String captured = promptText(LastPromptCapture.latest().orElseThrow().messages());
        assertEquals(1, rag.prepareCalls);
        assertTrue(captured.contains(PINNED_CONTENT), captured);
        assertTrue(captured.contains(RETRIEVED_CONTENT), captured);
        assertTrue(result.toString().contains("[Sources]"), result.toString());
        assertTrue(result.toString().contains("notes.md"), result.toString());
        assertTrue(result.toString().contains("retrieved.md"), result.toString());
        assertSame(rag.trace, TurnTraceCapture.consume());
        assertFalse(provider.requests().isEmpty());
        String dispatched = promptText(provider.requests().getFirst().messages);
        assertTrue(dispatched.contains(PINNED_CONTENT), dispatched);
        assertTrue(dispatched.contains(RETRIEVED_CONTENT), dispatched);
        assertNoPreloadExclusion();
    }

    @Test
    void permittedEmptyRetrievalKeepsExplorationGuidanceAndHistory() throws Exception {
        Config cfg = config(false, false);
        var rag = new RecordingRagService(cfg, List.of());
        var provider = provider();
        var memory = new SessionMemory();
        memory.update("Earlier conversation question.", "Earlier conversation answer.");
        String request = "Explain dependency injection.";

        Result result = handle(request,
                Context.builder(cfg).rag(rag).llm(provider.client()).memory(memory).build());

        assertEquals(1, rag.prepareCalls);
        assertFalse(result.toString().contains("[Sources]"), result.toString());
        assertFalse(provider.requests().isEmpty());
        for (var messages : List.of(LastPromptCapture.latest().orElseThrow().messages(),
                provider.requests().getFirst().messages)) {
            assertTrue(messages.contains(ChatMessage.user("Earlier conversation question.")), messages.toString());
            assertTrue(messages.contains(ChatMessage.assistant("Earlier conversation answer.")), messages.toString());
            assertTrue(messages.stream().anyMatch(message -> message.content()
                    .contains("No context snippets were retrieved for this query.")), messages.toString());
            assertTrue(messages.contains(ChatMessage.user(request)), messages.toString());
        }
        assertNoPreloadExclusion();
    }

    private Result handle(String request, Config cfg, RagService rag,
                          ScriptedNativeLlmClient.RecordedClient provider) throws Exception {
        return handle(request, Context.builder(cfg).rag(rag).llm(provider.client()).build());
    }

    private Result handle(String request, Context ctx) throws Exception {
        LocalTurnTraceCapture.begin("rag-privacy", "rag-privacy-tests", 1,
                "2026-09-27T00:00:00Z", "workspace-hash", "test", "llama_cpp", "fixture", "rag");
        ContextLedgerCapture.begin("rag-privacy", 1);
        return new RagMode().handle(request, workspace, ctx).orElseThrow();
    }

    private static void assertEmptyExclusion(String reason) {
        var entries = ContextLedgerCapture.snapshot().entries().stream()
                .filter(entry -> reason.equals(entry.decision().reasonCode())).toList();
        assertEquals(1, entries.size(), reason);
        assertEquals(ContextDecision.excludedByPrivacyOrTrustPolicy(reason), entries.getFirst().decision());
        assertEquals(ContextItem.fromText(ContextItemSource.RAG_SNIPPET, ExecutionBoundary.RAG_INDEX,
                ContextPrivacyClass.NORMAL, "", "", 0), entries.getFirst().item());
    }

    private static void assertNoPreloadExclusion() {
        var reasons = ContextLedgerCapture.snapshot().summary().byReason();
        assertFalse(reasons.containsKey("PRIVATE_MODE_RAG_DISABLED"), reasons.toString());
        assertFalse(reasons.containsKey("TURN_WORKSPACE_PRELOAD_DISABLED"), reasons.toString());
    }

    private static void assertNoWorkspaceContext(Result result,
                                                ScriptedNativeLlmClient.RecordedClient provider) {
        String captured = promptText(LastPromptCapture.latest().orElseThrow().messages());
        assertAll(
                () -> assertFalse(captured.contains(PINNED_CONTENT), captured),
                () -> assertFalse(captured.contains(RETRIEVED_CONTENT), captured),
                () -> assertFalse(captured.contains("UNMENTIONED_WORKSPACE_ENTRY.md"), captured),
                () -> assertFalse(captured.contains("File structure:"), captured),
                () -> assertFalse(captured.contains("Use your tools (talos.list_dir"), captured),
                () -> assertFalse(result.toString().contains("[Sources]"), result.toString()));
        for (var request : provider.requests()) {
            String dispatched = promptText(request.messages);
            assertFalse(dispatched.contains(PINNED_CONTENT), dispatched);
            assertFalse(dispatched.contains(RETRIEVED_CONTENT), dispatched);
        }
    }

    private static String promptText(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::content).collect(Collectors.joining("\n"));
    }

    private ScriptedNativeLlmClient.RecordedClient provider() {
        var provider = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(
                List.of(new LlmClient.StreamResult("A dependency is supplied by its caller.", List.of())),
                16_384);
        ownedClients.add(provider.client());
        return provider;
    }

    private static Config config(boolean privateMode, boolean privateRagEnabled) {
        Config cfg = new Config(null);
        cfg.data.put("privacy", Map.of("mode", privateMode ? "private" : "developer",
                "rag", Map.of("enabled_in_private_mode", privateRagEnabled)));
        cfg.data.put("embed", Map.of("provider", "disabled", "model", "disabled"));
        cfg.data.put("net", Map.of("enabled", false));
        cfg.data.put("rag", Map.of("vectors", Map.of("enabled", false),
                "includes", List.of("**/*.md")));
        return cfg;
    }

    private static final class RecordingRagService extends RagService {
        private int prepareCalls;
        private final RetrievalTrace trace = new RetrievalTrace();
        private final List<ContextResult.Snippet> snippets;

        private RecordingRagService(Config cfg) {
            this(cfg, List.of(new ContextResult.Snippet("retrieved.md#0", RETRIEVED_CONTENT)));
        }

        private RecordingRagService(Config cfg, List<ContextResult.Snippet> snippets) {
            super(cfg);
            this.snippets = snippets;
        }

        @Override
        public Prepared prepare(Path workspace, String query, Integer topKOverride) {
            prepareCalls++;
            return new Prepared(snippets, snippets.stream().map(ContextResult.Snippet::path).toList(), trace);
        }
    }
}
