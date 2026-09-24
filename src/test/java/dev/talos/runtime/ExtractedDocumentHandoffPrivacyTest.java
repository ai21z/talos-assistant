package dev.talos.runtime;

import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.toolcall.ToolResultModelContextHandoff;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.ContentRestrictions;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolResult;
import dev.talos.tools.impl.ReadFileTool;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real DOCX extraction and persistence boundaries, with synthetic document content and no model. */
class ExtractedDocumentHandoffPrivacyTest {
    private static final String PROSE = "Amber heron 6418 visits the ceramics workshop.";
    private static final ToolCall READ = new ToolCall("talos.read_file", Map.of("path", "notes.docx"));
    @TempDir Path workspace;

    @AfterEach
    void clearScopes() {
        TurnAuditCapture.end();
        ContentRestrictionScope.end();
        PromptDebugCapture.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualPrivateExtractionKeepsArtifactAndHistoryPermissionsIndependent(boolean artifactAllowed)
            throws Exception {
        TurnAuditCapture.begin();
        ToolResult raw = extract(config(true, true, artifactAllowed));
        assertEquals(ToolContentMetadata.ContentPrivacyClass.PRIVATE_DOCUMENT_EXTRACTED_TEXT,
                raw.contentMetadata().privacyClass());
        assertTrue(raw.contentMetadata().modelHandoffAllowed());
        assertEquals(artifactAllowed, raw.contentMetadata().rawArtifactPersistenceAllowed());
        assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current(),
                "Reading local content alone does not admit it to the model.");

        var decision = admit(raw, (description, detail) -> {
            fail("Configured model opt-in must not ask for another approval.");
            return false;
        });
        var expected = new ContentRestrictions(true, artifactAllowed, false, false);
        assertFalse(decision.contentWithheldFromModelContext());
        assertTrue(decision.modelResult().output().contains(PROSE));
        assertEquals(expected, ContentRestrictionScope.current());
        ChatMessage message = message(decision);
        assertEquals(expected, message.contentRestrictions());
        assertTrue(message.content().contains(PROSE));
        assertEquals(artifactAllowed ? PROSE : ContentRestrictions.WITHHELD,
                SafeLogFormatter.text(PROSE));

        TurnAudit audit = TurnAuditCapture.end();
        assertEquals(expected, audit.contentRestrictions());
        assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
        ChatRequest request = request(message);
        assertEquals(expected, request.contentRestrictions,
                "Typed restrictions must survive the ended admission scope.");
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(request, false, PROSE));
        assertEquals(artifactAllowed ? PROSE : ContentRestrictions.WITHHELD,
                PromptDebugCapture.latest().orElseThrow().providerBodyJson());
        if (!artifactAllowed) assertFalse(PromptDebugCapture.latest().orElseThrow().toString().contains(PROSE));

        assertPrivateHistory(audit);
    }

    @Test
    void propagatedWorkerKeepsExtractionRestrictionsDuringRequestAndDebugCapture() throws Exception {
        TurnAuditCapture.begin();
        var decision = admit(extract(config(true, true, false)), null);
        ChatMessage message = message(decision);
        var expected = new ContentRestrictions(true, false, false, false);
        Runnable propagated = ContentRestrictionScope.propagate(() -> {
            assertEquals(expected, ContentRestrictionScope.current());
            ChatRequest request = request(message);
            assertEquals(expected, request.contentRestrictions);
            assertTrue(request.messages.getFirst().content().contains(PROSE));
            PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(request, false, PROSE));
            assertEquals(ContentRestrictions.WITHHELD, SafeLogFormatter.text(PROSE));
        });
        // This exercises the public propagation boundary, not the package-private LlmCallBudget.
        var work = new FutureTask<Void>(() -> {
            propagated.run();
            assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
            return null;
        });
        Thread worker = new Thread(work, "extracted-document-privacy-fixture");
        worker.start();
        try {
            work.get(5, TimeUnit.SECONDS);
        } finally {
            worker.interrupt();
            worker.join(1_000);
        }
        assertFalse(worker.isAlive());
        assertEquals(expected, ContentRestrictionScope.current());
        assertEquals(ContentRestrictions.WITHHELD,
                PromptDebugCapture.latest().orElseThrow().providerBodyJson());
    }

    @Test
    void publicExtractedDocumentDeniesArtifactsWithoutBecomingPrivateHistory() throws Exception {
        TurnAuditCapture.begin();
        ToolResult raw = extract(config(false, false, false));
        assertEquals(ToolContentMetadata.ContentPrivacyClass.EXTRACTED_DOCUMENT_TEXT,
                raw.contentMetadata().privacyClass());
        assertTrue(raw.contentMetadata().modelHandoffAllowed());
        assertFalse(raw.contentMetadata().rawArtifactPersistenceAllowed());

        var decision = admit(raw, null);
        var expected = new ContentRestrictions(true, false, true, true);
        assertEquals(expected, ContentRestrictionScope.current());
        assertFalse(decision.contentWithheldFromModelContext());
        ChatRequest request = request(message(decision));
        assertEquals(expected, request.contentRestrictions);
        assertEquals(PROSE, request.contentRestrictions.forHistory(PROSE));
        assertEquals(ContentRestrictions.WITHHELD,
                PromptDebugSnapshot.fromProviderBody(request, false, PROSE).providerBodyJson());
    }

    @Test
    void deniedModelAdmissionLeavesContentlessNoticeUnrestrictedWithoutGrantingRawPermission()
            throws Exception {
        TurnAuditCapture.begin();
        ToolResult raw = extract(config(true, false, false));
        AtomicInteger approvals = new AtomicInteger();
        var decision = admit(raw, (description, detail) -> {
            approvals.incrementAndGet();
            return false;
        });

        assertEquals(1, approvals.get());
        assertFalse(raw.contentMetadata().modelHandoffAllowed());
        assertFalse(decision.candidateResult().contentMetadata().modelHandoffAllowed());
        assertTrue(decision.contentWithheldFromModelContext());
        assertFalse(decision.modelResult().output().contains(PROSE));
        assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
        ChatRequest notice = request(message(decision));
        assertEquals(ContentRestrictions.UNRESTRICTED, notice.contentRestrictions);
        assertFalse(notice.messages.getFirst().content().contains(PROSE));
        assertEquals(1, TurnAuditCapture.end().approvalsRequired());
    }

    @Test
    void perTurnApprovalGrantsOnlyModelUseAndDoesNotModifyTheOriginalExtractedResult() throws Exception {
        TurnAuditCapture.begin();
        ToolResult raw = extract(config(true, false, false));
        AtomicInteger approvals = new AtomicInteger();
        var decision = admit(raw, (description, detail) -> {
            approvals.incrementAndGet();
            assertFalse(detail.contains(PROSE));
            return true;
        });

        assertEquals(1, approvals.get());
        assertTrue(decision.privateDocumentPerTurnHandoffApproved());
        assertFalse(decision.contentWithheldFromModelContext());
        assertTrue(decision.candidateResult().contentMetadata().modelHandoffAllowed());
        assertFalse(raw.contentMetadata().modelHandoffAllowed());
        assertEquals(new ContentRestrictions(true, false, false, false),
                request(message(decision)).contentRestrictions);
        TurnAuditCapture.end();
        TurnAuditCapture.begin();
        assertTrue(admit(raw, null).contentWithheldFromModelContext(),
                "A previous turn's approval must not permit a new model handoff.");
        assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
    }

    @Test
    void ordinaryReadAndGenericNormalMetadataKeepExistingUnrestrictedBehavior() throws Exception {
        TurnAuditCapture.begin();
        Config config = config(true, false, false);
        Files.writeString(workspace.resolve("ordinary.txt"), PROSE);
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "ordinary.txt"));
        ToolResult raw = new ReadFileTool().execute(call,
                new ToolContext(workspace, new Sandbox(workspace, config.data), config));
        assertTrue(raw.success(), raw.errorMessage());
        assertEquals(ToolContentMetadata.ContentPrivacyClass.NORMAL, raw.contentMetadata().privacyClass());
        for (ToolResult result : List.of(raw, ToolResult.ok(PROSE, ToolContentMetadata.normal()))) {
            var decision = ToolResultModelContextHandoff.decide(call, null, "ordinary.txt", result, null);
            assertFalse(decision.contentWithheldFromModelContext());
            assertTrue(decision.modelResult().output().contains(PROSE));
            assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
        }
    }

    @Test
    void laterPublicOrArtifactAllowedExtractionCannotLoosenAnAlreadyRestrictedTurn() throws Exception {
        TurnAuditCapture.begin();
        admit(extract(config(true, true, false)), null);
        var expected = ContentRestrictionScope.current();
        admit(extract(config(true, true, true)), null);
        admit(extract(config(false, false, false)), null);
        assertEquals(new ContentRestrictions(true, false, false, false), expected);
        assertEquals(expected, ContentRestrictionScope.current());
    }

    private Config config(boolean privateMode, boolean modelAllowed, boolean artifactAllowed) {
        var config = new Config(null);
        config.data.put("document_extraction", Map.of("enabled", true, "word", Map.of("enabled", true)));
        config.data.put("privacy", Map.of("mode", privateMode ? "private" : "developer",
                "document_extraction", Map.of("allow_send_to_model", modelAllowed,
                        "persist_raw_artifacts", artifactAllowed, "allow_rag_indexing", false),
                "rag", Map.of("enabled_in_private_mode", false)));
        return config;
    }

    private ToolResult extract(Config config) throws Exception {
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(PROSE);
            try (var output = Files.newOutputStream(workspace.resolve("notes.docx"))) {
                document.write(output);
            }
        }
        ToolResult result = new ReadFileTool().execute(READ,
                new ToolContext(workspace, new Sandbox(workspace, config.data), config));
        assertTrue(result.success(), result.errorMessage());
        assertTrue(result.output().contains(PROSE));
        assertEquals(ToolContentMetadata.ContentSource.DOCUMENT_EXTRACTION, result.contentMetadata().source());
        return result;
    }

    private static ToolResultModelContextHandoff.Decision admit(ToolResult raw, ApprovalGate gate) {
        return ToolResultModelContextHandoff.decide(READ, null, "notes.docx", raw, gate);
    }

    private static ChatMessage message(ToolResultModelContextHandoff.Decision decision) {
        return ChatMessage.toolResult("document-read", ToolCallSupport.formatToolResult(READ,
                decision.modelResult(), decision.preserveModelResultForToolFormatting()));
    }

    private static ChatRequest request(ChatMessage message) {
        return new ChatRequest("llama_cpp", "fixture", "", "", List.of(), Duration.ofSeconds(5),
                List.of(message));
    }

    private void assertPrivateHistory(TurnAudit audit) throws Exception {
        String sessionId = JsonSessionStore.newSessionInstanceId(workspace, Instant.now());
        var turn = new TurnResult(new Result.Ok(PROSE), null, 1, Duration.ofMillis(1), audit);
        var memory = new SessionMemory();
        var conversation = new ConversationManager(memory, new TokenBudget());
        new MemoryUpdateListener(conversation).onTurnComplete(turn, "Read notes.docx.");
        assertEquals(ContentRestrictions.WITHHELD, conversation.buildHistory().getLast().content());
        var store = new JsonSessionStore(workspace.resolve("sessions"));
        try (var writer = store.acquireSessionWriter(sessionId)) {
            new JsonTurnLogAppender(writer, sessionId).onTurnComplete(turn, "Read notes.docx.");
        }
        assertEquals(ContentRestrictions.WITHHELD, store.loadTurns(sessionId).getFirst().assistantText());
        try (var paths = Files.walk(workspace.resolve("sessions"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(path).contains(PROSE), path.toString());
            }
        }
    }
}
