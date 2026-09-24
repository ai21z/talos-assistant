package dev.talos.runtime;

import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.diff.DocumentPairAdmission;
import dev.talos.core.diff.DocumentPairReceipt;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.toolcall.ToolResultModelContextHandoff;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.safety.ContentRestrictions;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.types.*;
import dev.talos.tools.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DocumentDerivedPrivacyTest {
    private static final String PROSE = "The ceramics workshop meets beside the old observatory on Thursday.";
    @TempDir Path workspace;

    @AfterEach void clear() {
        TurnAuditCapture.end();
        PromptDebugCapture.clear();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mixedOperandsKeepOrderAndWithholdPrivateDerivedHistory(boolean reversed) throws Exception {
        TurnAuditCapture.begin();
        var capture = capture(true, false, reversed, false);
        var receipt = capture.receipt();
        assertEquals(reversed ? "public.txt" : "private.docx", receipt.left().relativePath());
        assertEquals(reversed ? "private.docx" : "public.txt", receipt.right().relativePath());
        var decision = handoff(receipt);
        assertFalse(decision.contentWithheldFromModelContext());
        assertEquals(PROSE, decision.modelResult().output());
        assertFalse(TurnAuditCapture.currentContentRestrictions().historyAllowed());
        assertFalse(TurnAuditCapture.currentContentRestrictions().artifactAllowed());
        assertEquals(0, TurnAuditCapture.end().approvalsRequired());
    }

    @Test void duplicateOperandsRemainTwoIdentities() throws Exception {
        TurnAuditCapture.begin();
        var receipt = capture(true, false, false, true).receipt();
        assertEquals(receipt.left(), receipt.right());
        assertNotNull(receipt.left());
        assertNotNull(receipt.right());
        assertFalse(handoff(receipt).contentWithheldFromModelContext());
        assertEquals(1, TurnAuditCapture.end().documentPairs().size());
    }

    @Test void deniedCompoundPermissionCannotBePromotedOrApproved() throws Exception {
        TurnAuditCapture.begin();
        var receipt = capture(false, false, false, false).receipt();
        var metadata = ToolContentMetadata.documentPair(receipt).withModelHandoffAllowed(true, "approved");
        assertFalse(metadata.modelHandoffAllowed());
        var approvals = new AtomicInteger();
        var result = ToolResultModelContextHandoff.decide(new ToolCall("compare_documents", Map.of()),
                null, "private.docx", ToolResult.ok(PROSE, metadata), (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                });
        assertTrue(result.contentWithheldFromModelContext());
        assertFalse(result.modelResult().output().contains(PROSE));
        assertEquals(0, approvals.get());
        assertEquals(ContentRestrictions.UNRESTRICTED, TurnAuditCapture.end().contentRestrictions());
    }

    @Test void missingStaleAndFabricatedReceiptsFailClosed() throws Exception {
        TurnAuditCapture.begin();
        var capture = capture(true, false, false, false);
        var fake = DocumentPairReceipt.bind(capture.receipt().turn(), capture.admission().pair());
        assertTrue(handoff(fake).contentWithheldFromModelContext());
        TurnAuditCapture.end();
        TurnAuditCapture.begin();
        assertTrue(handoff(capture.receipt()).contentWithheldFromModelContext());
        var metadata = new ToolContentMetadata(ToolContentMetadata.ContentPrivacyClass.NORMAL,
                ToolContentMetadata.ContentSource.DOCUMENT_COMPARISON, "", true, true, true, "");
        assertTrue(ToolResultModelContextHandoff.decide(new ToolCall("compare_documents", Map.of()),
                null, "", ToolResult.ok(PROSE, metadata), null).contentWithheldFromModelContext());
        assertEquals(ContentRestrictions.UNRESTRICTED, TurnAuditCapture.end().contentRestrictions());
        assertThrows(IllegalStateException.class, () -> capture(true, false, false, false));
    }

    @Test void rawArtifactOptInDoesNotPermitPrivateAnswerHistory() throws Exception {
        TurnAuditCapture.begin();
        handoff(capture(true, true, false, true).receipt());
        var restrictions = TurnAuditCapture.currentContentRestrictions();
        assertTrue(restrictions.artifactAllowed());
        assertFalse(restrictions.historyAllowed());
        assertEquals(PROSE, SafeLogFormatter.text(PROSE));
        assertEquals(ContentRestrictions.WITHHELD, restrictions.forHistory(PROSE));
    }

    @Test void failedComparisonIsNotReclassifiedAsSuccess() throws Exception {
        TurnAuditCapture.begin();
        var receipt = capture(true, false, false, false).receipt();
        var raw = new ToolResult(false, null, new ToolError("READ_FAILED", PROSE), null,
                ToolContentMetadata.documentPair(receipt));
        var decision = ToolResultModelContextHandoff.decide(new ToolCall("compare_documents", Map.of()),
                null, "", raw, null);
        assertFalse(decision.modelResult().success());
        assertFalse(decision.modelResult().errorMessage().contains(PROSE));
        assertEquals(ContentRestrictions.UNRESTRICTED, TurnAuditCapture.end().contentRestrictions());
    }

    @Test void restrictedBackgroundCaptureCannotReplaceUserFacingHistory() throws Exception {
        TurnAuditCapture.begin();
        PromptDebugCapture.beginTurn();
        var visible = PromptDebugSnapshot.fromChatRequest(new ChatRequest("compat", "fixture", "", "",
                List.of(), null, List.of(ChatMessage.user("visible request"))), false);
        PromptDebugCapture.record(visible);
        handoff(capture(true, false, false, false).receipt());
        var controls = new ChatRequestControls(ToolChoiceMode.AUTO, "", ResponseFormatMode.TEXT, "",
                List.of(PromptDebugCapture.BACKGROUND_MAINTENANCE_TAG, PROSE));
        var request = new ChatRequest("compat", "fixture", "", "", List.of(), null,
                List.of(ChatMessage.assistant(PROSE)), List.of(), controls);
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(request, false, PROSE));
        assertSame(visible, PromptDebugCapture.latest().orElseThrow());
        assertEquals(1, PromptDebugCapture.history().size());
        assertFalse(PromptDebugCapture.latestRecorded().orElseThrow().toString().contains(PROSE));
    }

    @Test void consumedMarkedHistoryRestrictsTheNewAnswerAudit() throws Exception {
        TurnAuditCapture.begin();
        handoff(capture(true, false, false, false).receipt());
        var history = ChatMessage.assistant(PROSE);
        TurnAuditCapture.end();
        TurnAuditCapture.begin();
        new ChatRequest("compat", "fixture", "", "", List.of(), null, List.of(history));
        assertFalse(TurnAuditCapture.end().contentRestrictions().historyAllowed());
    }

    @Test void tighterMetadataCannotBeLostToTheReceipt() throws Exception {
        TurnAuditCapture.begin();
        var receipt = capture(true, true, false, true).receipt();
        var metadata = new ToolContentMetadata(ToolContentMetadata.ContentPrivacyClass.NORMAL,
                ToolContentMetadata.ContentSource.DOCUMENT_COMPARISON, "", true, false, false, "", List.of(), receipt);
        ToolResultModelContextHandoff.decide(new ToolCall("compare_documents", Map.of()), null, "",
                ToolResult.ok(PROSE, metadata), null);
        assertFalse(TurnAuditCapture.end().contentRestrictions().artifactAllowed());
    }

    @Test void laterPublicEvidenceCannotLoosenARestrictedTurn() throws Exception {
        TurnAuditCapture.begin();
        handoff(capture(true, false, false, false).receipt());
        var first = TurnAuditCapture.currentContentRestrictions();
        ToolResultModelContextHandoff.decide(new ToolCall("read_file", Map.of()), null, "public.txt",
                ToolResult.ok("ordinary output"), null);
        assertEquals(first, TurnAuditCapture.currentContentRestrictions());
        assertEquals(first, ChatMessage.assistant(PROSE).contentRestrictions());
    }

    @Test void promptDebugAndLogsContainNoDerivedProseOrNativeArguments() throws Exception {
        TurnAuditCapture.begin();
        handoff(capture(true, false, false, false).receipt());
        var messages = List.of(ChatMessage.toolResult("call", PROSE),
                ChatMessage.assistantWithToolCalls(PROSE,
                        List.of(new ChatMessage.NativeToolCall("id", "read_file", Map.of("path", PROSE)))));
        var request = new ChatRequest("compat", "fixture", "", "", List.of(), Duration.ofSeconds(1), messages);
        var snapshot = PromptDebugSnapshot.fromProviderBody(request, false, "{\"derived\":\"" + PROSE + "\"}");
        PromptDebugCapture.record(snapshot.withDiagnostics(Map.of("malformedCall", PROSE)));
        var retained = PromptDebugCapture.latest().orElseThrow();
        assertFalse(retained.toString().contains(PROSE));
        assertTrue(retained.messages().stream().allMatch(m -> m.toolCalls() == null && m.toolCallId() == null));
        assertEquals(ContentRestrictions.WITHHELD, retained.providerBodyJson());
        assertEquals(ContentRestrictions.WITHHELD, SafeLogFormatter.value(Map.of("arguments", PROSE)));
        assertEquals(ContentRestrictions.WITHHELD, SafeLogFormatter.text(PROSE));
        assertEquals(ContentRestrictions.WITHHELD, SafeLogFormatter.throwableMessage(new RuntimeException(PROSE)));
        assertFalse(SafeLogFormatter.parameters(Map.of("path", PROSE)).toString().contains(PROSE));
        assertFalse(dev.talos.cli.prompt.PromptDebugInspector.redactedProviderBodyJson(retained).contains(PROSE));
    }

    @Test void markedMessageProtectsCaptureAfterTurnScopeHasEnded() throws Exception {
        TurnAuditCapture.begin();
        handoff(capture(true, false, false, false).receipt());
        var marked = ChatMessage.user(PROSE);
        TurnAuditCapture.end();
        assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
        var request = new ChatRequest("compat", "fixture", "", "", List.of(), null, List.of(marked));
        var snapshot = PromptDebugSnapshot.fromProviderBody(request, false, PROSE);
        assertEquals(ContentRestrictions.WITHHELD, snapshot.providerBodyJson());
        assertFalse(snapshot.toString().contains(PROSE));
    }

    @Test void reconstructionAndScopeCleanupDoNotRequireOriginalToolMessage() throws Exception {
        TurnAuditCapture.begin();
        handoff(capture(true, false, false, false).receipt());
        var request = new ChatRequest("compat", "fixture", "", "", List.of(), null,
                List.of(ChatMessage.assistant(PROSE)));
        assertFalse(request.contentRestrictions.historyAllowed());
        TurnAuditCapture.end();
        TurnAuditCapture.begin();
        assertEquals(ContentRestrictions.UNRESTRICTED, ChatMessage.assistant("unrelated").contentRestrictions());
        assertEquals("unrelated", SafeLogFormatter.text("unrelated"));
        assertTrue(TurnAuditCapture.end().documentPairs().isEmpty());
    }

    @Test void modelDeniedMarkedMessagesCannotReachARequest() {
        var message = new ChatMessage("tool", PROSE, null, "id",
                new ContentRestrictions(false, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest("compat", "fixture", "", "",
                List.of(), null, List.of(message)));
    }

    @Test void frozenRestrictionsProtectMemoryJournalSnapshotAndReloadAfterCleanup() throws Exception {
        String sessionId = JsonSessionStore.newSessionInstanceId(workspace, Instant.now());
        TurnAuditCapture.begin();
        handoff(capture(true, false, false, false).receipt());
        TurnAuditCapture.recordToolCall("read_file", PROSE, false, PROSE);
        var rawTrace = LocalTurnTrace.builder("trc-private", sessionId, 1, Instant.now().toString())
                .event(TurnTraceEvent.simple("TOOL_EXECUTED", Instant.now().toString(), Map.of("pathHint", PROSE)))
                .outcome("OK", "NOT_RUN", "NOT_REQUIRED", "NONE", "READ_ONLY_ANSWERED").build();
        var audit = TurnAuditCapture.end().withLocalTrace(rawTrace);
        assertFalse(audit.toString().contains(PROSE));
        assertEquals("OK", audit.localTrace().outcome().status());
        var turn = new TurnResult(new Result.Ok(PROSE), null, 1, Duration.ofMillis(1), audit);
        assertEquals(PROSE, ((Result.Ok) turn.result()).text);
        var memory = new SessionMemory();
        var manager = new ConversationManager(memory, new TokenBudget());
        new MemoryUpdateListener(manager).onTurnComplete(turn, "Compare both documents.");
        assertEquals(ContentRestrictions.WITHHELD, manager.buildHistory().getLast().content());
        assertFalse(memory.toString().contains(PROSE));
        var store = new JsonSessionStore(workspace.resolve("sessions"));
        try (var writer = store.acquireSessionWriter(sessionId)) {
            new JsonTurnLogAppender(writer, sessionId).onTurnComplete(turn, "Compare both documents.");
            writer.save(new SessionData(sessionId, workspace.toString(), "", 1, Instant.now(),
                    manager.buildHistory().stream().map(m -> new SessionData.Turn(m.role(), m.content())).toList()));
        }
        assertEquals(ContentRestrictions.WITHHELD, store.loadTurns(sessionId).getFirst().assistantText());
        assertEquals(ContentRestrictions.WITHHELD, store.load(sessionId).orElseThrow().turns().getLast().content());
        var exports = workspace.resolve("exports");
        var command = new dev.talos.cli.repl.slash.SessionCommand(workspace, store, sessionId, exports);
        var context = dev.talos.cli.repl.Context.builder(new Config(null)).memory(memory).conversationManager(manager).build();
        assertInstanceOf(Result.TrustedInfo.class, command.execute("export --raw", context));
        try (var files = Files.walk(exports)) {
            var exported = files.filter(Files::isRegularFile).toList();
            assertTrue(exported.stream().anyMatch(path -> path.toString().endsWith(".md")));
            for (var path : exported) assertFalse(Files.readString(path).contains(PROSE), path.toString());
        }
        try (var paths = Files.walk(workspace.resolve("sessions"))) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(path).contains(PROSE), path.toString());
            }
        }
    }

    @Test void restrictedAuditRetainsAppliedAndUncertainEffectPresence() throws Exception {
        TurnAuditCapture.begin();
        handoff(capture(true, false, false, false).receipt());
        TurnAuditCapture.recordExecutedToolCall(new ToolCall("write_file", Map.of("path", PROSE)),
                List.of(PROSE), false, PROSE, new ToolMutationEffects(List.of(PROSE, "second"), List.of(PROSE)));
        var audit = TurnAuditCapture.end();
        assertFalse(audit.toString().contains(PROSE));
        assertFalse(audit.toolCalls().getFirst().success());
        assertEquals(2, audit.toolCalls().getFirst().mutationEffects().appliedPaths().size());
        assertEquals(1, audit.toolCalls().getFirst().mutationEffects().uncertainPaths().size());
        assertEquals(audit.toolCalls(), audit.withLocalTrace(null).toolCalls());
        var turn = new TurnResult(new Result.Ok(PROSE), null, 1, Duration.ofMillis(1), audit);
        var summary = dev.talos.runtime.context.ChangeSummaryContext.updateAfterTurn(null, turn);
        assertEquals(2, summary.changedFiles().size());
        assertTrue(summary.changedFiles().stream().anyMatch(change -> "UNKNOWN".equals(change.toolOutcome())));
        assertFalse(summary.toString().contains(PROSE));
    }

    @Test void privateProposalCannotBecomeActiveTaskHistory() throws Exception {
        TurnAuditCapture.begin();
        handoff(capture(true, false, false, false).receipt());
        var turn = new TurnResult(new Result.Ok(PROSE), null, 1, Duration.ofMillis(1), TurnAuditCapture.end());
        var update = new dev.talos.cli.repl.ActiveTaskContextUpdater().updateAfterTurn(turn,
                "Propose changes to index.html", null, null);
        assertFalse(update.toString().contains(PROSE));
        assertFalse(update.activeTaskContext().state() == dev.talos.runtime.context.ActiveTaskContext.State.ACTIVE);
    }

    @Test void validTerminalEventsDoNotAuthorizeTopLevelTraceProse() {
        String timestamp = Instant.now().toString();
        var safe = LocalTurnTrace.builder("trc-projection", "session", 1, timestamp)
                .event(dev.talos.runtime.trace.TurnSettlementTraceEvent.create(timestamp,
                        TurnSettlement.Kind.NO_RESULT, TurnExecutionStage.MODE_ROUTING,
                        TurnFailureClassifier.Classification.none()))
                .event(TurnTraceEvent.simple("TRACE_COMPLETED", timestamp, Map.of()))
                .build().safeTerminalProjection(TurnSettlement.Kind.NO_RESULT, false);
        var builder = LocalTurnTrace.builder(safe.traceId(), safe.sessionId(), 1, timestamp)
                .warning("DOCUMENT", PROSE)
                .outcome("NO_RESULT", "NOT_RUN", "UNKNOWN", "UNKNOWN", "NO_MODE_RESULT");
        safe.events().forEach(builder::event);
        var hybrid = builder.build();
        assertTrue(dev.talos.runtime.trace.TurnEvidenceProjectionTraceEvent.decode(hybrid).valid());
        assertTrue(hybrid.toString().contains(PROSE));
        var projected = hybrid.contentWithheldProjection();
        assertFalse(projected.toString().contains(PROSE));
        assertTrue(dev.talos.runtime.trace.TurnEvidenceProjectionTraceEvent.decode(projected).valid());
        assertEquals(projected, projected.contentWithheldProjection());
    }

    @Test void restrictedLegacyMutationsAndAbnormalSettlementsRetainOpaqueEvidence() throws Exception {
        TurnAuditCapture.begin();
        handoff(capture(true, false, false, false).receipt());
        TurnAuditCapture.recordExecutedToolCall(new ToolCall("make_directory", Map.of("path", PROSE)),
                List.of(PROSE), true, "");
        var audit = TurnAuditCapture.end();
        for (var kind : TurnSettlement.Kind.values()) {
            var projected = audit.safeTerminalProjection(kind, false);
            assertEquals(audit.toolCalls(), projected.toolCalls());
            var turn = new TurnResult(new Result.Ok(PROSE), null, 1, Duration.ofMillis(1), projected);
            var summary = dev.talos.runtime.context.ChangeSummaryContext.updateAfterTurn(null, turn);
            assertEquals(1, summary.changedFiles().size(), kind.name());
            assertFalse(summary.toString().contains(PROSE));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"complete", "failed", "cancelled", "no_result"})
    void actualTurnLifecycleRetainsPolicyAfterCleanup(String outcome) throws Exception {
        var store = new JsonSessionStore(workspace.resolve("lifecycle"));
        String sessionId = JsonSessionStore.newSessionInstanceId(workspace, Instant.now());
        var memory = new SessionMemory();
        var manager = new ConversationManager(memory, new TokenBudget());
        var settled = new java.util.concurrent.atomic.AtomicReference<TurnSettlement>();
        var processor = new TurnProcessor((raw, path, context) -> {
            try { handoff(capture(true, false, false, false).receipt()); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            if (outcome.equals("failed")) throw new IllegalStateException(PROSE);
            if (outcome.equals("cancelled")) throw new java.util.concurrent.CancellationException(PROSE);
            return outcome.equals("no_result") ? java.util.Optional.empty() : java.util.Optional.of(new Result.Ok(PROSE));
        });
        processor.addListener(new MemoryUpdateListener(manager));
        processor.addListener(new SessionListener() {
            @Override public TurnPublicationReceipt onTurnSettled(TurnSettlement settlement, String userInput) {
                assertFalse(TurnAuditCapture.isActive());
                assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
                settled.set(settlement);
                return TurnPublicationReceipt.complete();
            }
        });
        try (var writer = store.acquireSessionWriter(sessionId)) {
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            var session = new Session(workspace, new Config(null));
            var context = dev.talos.cli.repl.Context.builder(new Config(null)).build();
            if (outcome.equals("failed") || outcome.equals("cancelled")) {
                assertThrows(RuntimeException.class, () -> processor.process(session, "Compare both documents.", context));
            } else processor.process(session, "Compare both documents.", context);
        }
        assertNotNull(settled.get());
        assertFalse(settled.get().result().audit().contentRestrictions().historyAllowed());
        var rows = store.loadTurns(sessionId);
        assertEquals(1, rows.size());
        assertEquals(outcome.equals("complete") ? "ok" : outcome, rows.getFirst().status());
        var trace = store.loadTrace(sessionId, rows.getFirst().traceId()).orElseThrow();
        assertFalse(trace.toString().contains(PROSE));
        if (outcome.equals("complete")) {
            assertEquals(ContentRestrictions.WITHHELD, rows.getFirst().assistantText());
            assertEquals(ContentRestrictions.WITHHELD, manager.buildHistory().getLast().content());
            assertEquals(PROSE, ((Result.Ok) settled.get().result().result()).text);
        } else {
            assertEquals("", rows.getFirst().assistantText());
            assertTrue(manager.buildHistory().isEmpty());
            assertEquals(1, trace.events().stream().filter(event -> "TURN_SETTLED".equals(event.type())).count());
            assertTrue(dev.talos.runtime.trace.TurnEvidenceProjectionTraceEvent.decode(trace).valid());
        }
        assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
    }

    private TurnAuditCapture.DocumentCapture capture(boolean allowModel, boolean allowArtifact,
                                                     boolean reversed, boolean same) throws Exception {
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(PROSE);
            try (var output = Files.newOutputStream(workspace.resolve("private.docx"))) { document.write(output); }
        }
        Files.writeString(workspace.resolve("public.txt"), "A public counterpart.");
        var config = new Config(null);
        config.data.put("document_extraction", Map.of("enabled", true, "word", Map.of("enabled", true)));
        config.data.put("privacy", Map.of("mode", "private", "document_extraction",
                Map.of("allow_send_to_model", allowModel, "persist_raw_artifacts", allowArtifact)));
        var pair = new DocumentPairAdmission.RequestedPair(reversed ? "public.txt" : "private.docx",
                same || reversed ? "private.docx" : "public.txt");
        var result = TurnAuditCapture.captureDocumentPair(workspace, new Sandbox(workspace, config.data), config, pair, pair);
        assertEquals(DocumentPairAdmission.Status.COMPLETE, result.admission().status());
        return result;
    }

    private ToolResultModelContextHandoff.Decision handoff(DocumentPairReceipt receipt) {
        return ToolResultModelContextHandoff.decide(new ToolCall("compare_documents", Map.of()), null, "",
                ToolResult.ok(PROSE, ToolContentMetadata.documentPair(receipt)), null);
    }
}
