package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnEnvelopePersistenceReceipt;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.intent.MutationPlanSelection;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A removal turn is persisted with its typed request and its trace through the real session store, whatever the
 * operand preflight decided (MICA invariant I2 with the memory rule that raw history is retained). The P7 live run
 * found these turns rejected as malformed and lost. A composed request is admitted only as the exact composition of
 * the typed request, so forged evidence still publishes nothing (T1204). The provider is scripted, the executor, tools
 * and store are real.
 */
class OperationOperandPersistenceTest {

    private static final String SESSION = "operand-persistence";
    private static final String DELETE_THIS_PAGE =
            "ok can you delete this page now please? I want this workspace clean";

    @AfterEach
    void clear() {
        LocalTurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void aBoundPageDeleteIsPersistedWithTheTypedRequestAndTheComposedEvidence(
            @TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);

        Turn turn = run(workspace, DELETE_THIS_PAGE, List.of(
                "{\"name\":\"talos.delete_path\",\"arguments\":{\"path\":\"index.html\"}}",
                "Deleted index.html."));
        Persisted persisted = persist(store, turn);

        assertPersisted(persisted, DELETE_THIS_PAGE);
        assertFalse(Files.exists(workspace.resolve("index.html")), turn.text());
        TurnPolicyTrace policy = persisted.loaded().getFirst().policyTrace();
        assertFalse(policy.lexicalDecisions().isEmpty(), policy.toString());
        assertTrue(policy.lexicalDecisions().stream().allMatch(decision -> decision.sourceRequestSha256().equals(
                MutationPlanSelection.sourceRequestSha256("Delete \"index.html\"."))), policy.toString());
        assertTrue(new JsonSessionStore(store).loadTrace(SESSION, turn.trace().traceId()).isPresent());
    }

    @Test
    void aBoundPageDeleteThatTheModelDoesNotIssueIsPersisted(@TempDir Path workspace, @TempDir Path store)
            throws Exception {
        onePage(workspace);

        Persisted persisted = persist(store, run(workspace, DELETE_THIS_PAGE, List.of(
                "I can delete the page for you.", "I can delete the page for you.")));

        assertPersisted(persisted, DELETE_THIS_PAGE);
        assertTrue(Files.exists(workspace.resolve("index.html")));
    }

    @Test
    void aBulkDeleteThatNamesAFileIsPersistedWithNoAuthority(@TempDir Path workspace, @TempDir Path store)
            throws Exception {
        onePage(workspace);
        String request = "hm you just emptied the index.html file. Can you completely DELETE all the files in this "
                + "workspace?";

        Turn turn = run(workspace, request, List.of("unused"));
        Persisted persisted = persist(store, turn);

        assertEquals(0, turn.provider().requests().size());
        assertTrue(turn.text().contains("one named path per request"), turn.text());
        assertPersisted(persisted, request);
        assertNoAuthority(persisted.loaded().getFirst().policyTrace());
    }

    @Test
    void aBulkDeleteWithoutAFileNameIsPersisted(@TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);
        String request = "Can you completely DELETE all the files in this workspace?";

        Persisted persisted = persist(store, run(workspace, request, List.of("unused")));

        assertPersisted(persisted, request);
        assertNoAuthority(persisted.loaded().getFirst().policyTrace());
    }

    @Test
    void theAnsweredClassificationHoldsOnlyForARecordWithoutAuthority(@TempDir Path workspace) throws Exception {
        onePage(workspace);
        TurnPolicyTrace answered = run(workspace, "Can you completely DELETE all the files in this workspace?",
                List.of("unused")).record().policyTrace();
        assertTrue(answered.answeredWithoutExecution(), answered.toString());

        TurnPolicyTrace claimed = new TurnPolicyTrace(answered.taskType(), true, answered.verificationRequired(),
                answered.expectedTargets(), answered.forbiddenTargets(), answered.initialPhase(),
                answered.finalPhase(), answered.nativeTools(), answered.promptTools(), answered.blocks(),
                answered.classificationReason(), answered.rolefulTargets(), answered.lexicalDecisions(),
                answered.lexicalRegions(), answered.mutationRequested(), answered.workspaceOperationPlan());
        assertFalse(claimed.answeredWithoutExecution(), claimed.toString());
    }

    @Test
    void anAmbiguousPageClarificationIsPersistedWithNoAuthority(@TempDir Path workspace, @TempDir Path store)
            throws Exception {
        onePage(workspace);
        Files.writeString(workspace.resolve("about.html"), "<p>About</p>\n");
        String request = "please remove the page";

        Turn turn = run(workspace, request, List.of("unused"));
        Persisted persisted = persist(store, turn);

        assertEquals(0, turn.provider().requests().size());
        assertPersisted(persisted, request);
        TurnPolicyTrace policy = persisted.loaded().getFirst().policyTrace();
        // The typed request parses into a delete of a path named "page". It is recorded, but nothing in it is active.
        assertFalse(policy.lexicalDecisions().isEmpty(), policy.toString());
        assertNoAuthority(policy);
    }

    @Test
    void aClarificationAfterAPriorEditTurnIsPersistedAgainstTheTypedRequestAlone(
            @TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);
        Files.writeString(workspace.resolve("about.html"), "<p>About</p>\n");
        String request = "please remove the page";
        List<ChatMessage> history = List.of(
                ChatMessage.user("Edit index.html and about.html to change the heading to Welcome"),
                ChatMessage.assistant("Both headings now read Welcome."));

        Turn turn = run(workspace, history, request, List.of("unused"));
        Persisted persisted = persist(store, turn);

        assertEquals(0, turn.provider().requests().size());
        assertPersisted(persisted, request);
        assertNoAuthority(persisted.loaded().getFirst().policyTrace());
    }

    @Test
    void aMissingNamedFileStatementIsPersisted(@TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);
        String request = "delete notes.txt please";

        Turn turn = run(workspace, request, List.of("unused"));
        Persisted persisted = persist(store, turn);

        assertTrue(turn.text().contains("does not exist"), turn.text());
        assertPersisted(persisted, request);
        assertNoAuthority(persisted.loaded().getFirst().policyTrace());
    }

    @Test
    void aConstrainedDeleteAnswerIsPersisted(@TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);
        String request = "delete the page but keep a backup of index.html";

        Turn turn = run(workspace, request, List.of("unused"));
        Persisted persisted = persist(store, turn);

        assertEquals(0, turn.provider().requests().size());
        assertPersisted(persisted, request);
        assertNoAuthority(persisted.loaded().getFirst().policyTrace());
    }

    @Test
    void aContentRemovalEditIsPersisted(@TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);
        String request = "remove the footer from index.html";

        Turn turn = run(workspace, request, contentEditResponses());
        Persisted persisted = persist(store, turn);

        assertPersisted(persisted, request);
        assertEquals("<main>Main</main>\n", Files.readString(workspace.resolve("index.html")), turn.text());
        assertTrue(new JsonSessionStore(store).loadTrace(SESSION, turn.trace().traceId()).isPresent());
    }

    @Test
    void aTypedDeleteOfANamedFileStillPersists(@TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);
        String request = "delete index.html";

        Persisted persisted = persist(store, run(workspace, request, List.of(
                "{\"name\":\"talos.delete_path\",\"arguments\":{\"path\":\"index.html\"}}",
                "Deleted index.html.")));

        assertPersisted(persisted, request);
    }

    @Test
    void aBoundDeleteOfADotSlashSpellingIsPersisted(@TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);
        String request = "please erase ./index.html";

        Turn turn = run(workspace, request, List.of(
                "{\"name\":\"talos.delete_path\",\"arguments\":{\"path\":\"index.html\"}}",
                "Deleted index.html."));
        Persisted persisted = persist(store, turn);

        assertEvidenceIdentifies(turn.record(), "Delete \"./index.html\".");
        assertPersisted(persisted, request);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aBoundDeleteOfABackslashSpellingIsPersisted(@TempDir Path workspace, @TempDir Path store) throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/a.txt"), "a\n");
        String request = "please erase docs\\a.txt";

        Turn turn = run(workspace, request, List.of("I can delete it."));
        Persisted persisted = persist(store, turn);

        assertEvidenceIdentifies(turn.record(), "Delete \"docs\\a.txt\".");
        assertPersisted(persisted, request);
    }

    @Test
    void aContentEditOfADotSlashPlaceIsPersisted(@TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);
        String request = "remove the footer from ./index.html";

        Turn turn = run(workspace, request, contentEditResponses());
        Persisted persisted = persist(store, turn);

        assertEvidenceIdentifies(turn.record(), "Edit \"./index.html\" to remove the footer");
        assertPersisted(persisted, request);
    }

    @Test
    void theSameTypedTextAnsweredAndThenExecutedKeepsBothRowsOnReload(
            @TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);
        String request = "delete notes.txt";
        Persisted answered = persist(store, run(workspace, List.of(), request, List.of("unused"), 1));
        assertEquals(TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED, answered.receipt().state());

        Files.writeString(workspace.resolve("notes.txt"), "n\n");
        Turn executed = run(workspace, List.of(), request, List.of(
                "{\"name\":\"talos.delete_path\",\"arguments\":{\"path\":\"notes.txt\"}}",
                "Deleted notes.txt."), 2);
        Persisted second = persist(store, executed);

        assertFalse(Files.exists(workspace.resolve("notes.txt")), executed.text());
        assertEquals(TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED, second.receipt().state());
        JsonSessionStore reopened = new JsonSessionStore(store);
        assertEquals(2, reopened.loadTurns(SESSION).size());
        assertTrue(reopened.observeTurnLogTail(SESSION).exact(), reopened.observeTurnLogTail(SESSION).toString());
        assertTrue(reopened.loadTrace(SESSION, executed.trace().traceId()).isPresent());
    }

    @Test
    void aComposedDeleteAfterAnAnsweredDeleteOfTheSameTextKeepsBothRowsOnReload(
            @TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);
        Files.writeString(workspace.resolve("about.html"), "<p>About</p>\n");
        String request = "please remove the page";
        persist(store, run(workspace, List.of(), request, List.of("unused"), 1));

        Files.delete(workspace.resolve("about.html"));
        Turn bound = run(workspace, List.of(), request, List.of("I can delete it."), 2);
        Persisted second = persist(store, bound);

        assertEquals(TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED, second.receipt().state());
        JsonSessionStore reopened = new JsonSessionStore(store);
        assertEquals(2, reopened.loadTurns(SESSION).size());
        assertTrue(reopened.observeTurnLogTail(SESSION).exact(), reopened.observeTurnLogTail(SESSION).toString());
    }

    @Test
    void aBoundDeleteWithProtectedContentOutsideTheClauseIsPersisted(@TempDir Path workspace, @TempDir Path store)
            throws Exception {
        onePage(workspace);
        String request = "delete this page now please. TALOS_CANARY_P3C";

        Turn turn = run(workspace, request, List.of("I can delete the page for you."));
        Persisted persisted = persist(store, turn);

        assertEquals(TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED, persisted.receipt().state(),
                turn.text());
        assertEquals(1, persisted.loaded().size());
        assertFalse(persisted.loaded().getFirst().userInput().contains("TALOS_CANARY_P3C"));
        assertTrue(new JsonSessionStore(store).loadTrace(SESSION, turn.trace().traceId()).isPresent());
    }

    @Test
    void anAnsweredRemovalAfterACorrectionIsPersistedAgainstTheTypedRequestAlone(
            @TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);
        String request = "nothing changed, just delete all the files";
        List<ChatMessage> history = List.of(
                ChatMessage.user("Edit index.html to change the heading to Welcome"),
                ChatMessage.assistant("No files were changed."));

        Turn turn = run(workspace, history, request, List.of("unused"));
        Persisted persisted = persist(store, turn);

        assertEquals(0, turn.provider().requests().size(), turn.text());
        // The prior answer reads as an incomplete outcome, so the repair projection is reached. The record still
        // identifies the typed request, not a combined repair request.
        assertEvidenceIdentifies(turn.record(), request);
        assertPersisted(persisted, request);
        assertNoAuthority(persisted.loaded().getFirst().policyTrace());
    }

    @Test
    void oneTypedTextBoundToTwoPagesAndThenClarifiedKeepsEveryRowOnReload(
            @TempDir Path workspace, @TempDir Path store) throws Exception {
        onePage(workspace);
        String request = "please remove the page";
        persist(store, run(workspace, List.of(), request, List.of("I can delete it."), 1));

        Files.delete(workspace.resolve("index.html"));
        Files.writeString(workspace.resolve("about.html"), "<p>About</p>\n");
        persist(store, run(workspace, List.of(), request, List.of("I can delete it."), 2));

        Files.writeString(workspace.resolve("contact.html"), "<p>Contact</p>\n");
        Turn clarified = run(workspace, List.of(), request, List.of("unused"), 3);
        Persisted third = persist(store, clarified);

        assertEquals(TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED, third.receipt().state());
        JsonSessionStore reopened = new JsonSessionStore(store);
        assertEquals(3, reopened.loadTurns(SESSION).size());
        assertTrue(reopened.observeTurnLogTail(SESSION).exact(), reopened.observeTurnLogTail(SESSION).toString());
        assertTrue(reopened.loadTrace(SESSION, clarified.trace().traceId()).isPresent());
    }

    @Test
    void aComposedDeleteUnderATypedRequestThatIsNotARemovalPublishesNothing(
            @TempDir Path workspace, @TempDir Path control, @TempDir Path forged) throws Exception {
        onePage(workspace);
        Turn turn = run(workspace, DELETE_THIS_PAGE, List.of("I can delete the page for you."));

        assertEvidenceIdentifies(turn.record(), "Delete \"index.html\".");
        assertRecordOnlyAccepted(control, turn.record());
        assertRejected(forged, withUserInput(turn.record(), "show me what is in index.html"));
    }

    @Test
    void aRecursiveCompositionUnderANonRecursiveTypedRequestPublishesNothing(
            @TempDir Path workspace, @TempDir Path control, @TempDir Path forged) throws Exception {
        Files.createDirectories(workspace.resolve("assets"));
        Files.writeString(workspace.resolve("assets/logo.svg"), "<svg/>\n");
        Turn turn = run(workspace, "please erase assets recursively", List.of("I can delete it."));

        assertEvidenceIdentifies(turn.record(), "Delete \"assets\" recursively.");
        assertRecordOnlyAccepted(control, turn.record());
        assertRejected(forged, withUserInput(turn.record(), "please erase assets"));
    }

    @Test
    void aContentEditCompositionUnderATypedDeletePublishesNothing(
            @TempDir Path workspace, @TempDir Path control, @TempDir Path forged) throws Exception {
        onePage(workspace);
        Turn turn = run(workspace, "remove the footer from index.html", contentEditResponses());

        assertEvidenceIdentifies(turn.record(), "Edit \"index.html\" to remove the footer");
        assertRecordOnlyAccepted(control, turn.record());
        assertRejected(forged, withUserInput(turn.record(), "please remove the page"));
    }

    @Test
    void aContentEditCompositionUnderAPlaceOfAnotherKindPublishesNothing(
            @TempDir Path workspace, @TempDir Path control, @TempDir Path forged) throws Exception {
        onePage(workspace);
        Turn turn = run(workspace, "remove the footer from index.html", contentEditResponses());

        assertRecordOnlyAccepted(control, turn.record());
        assertRejected(forged, withUserInput(turn.record(), "remove the footer from the stylesheet"));
    }

    @Test
    void aComposedDeleteUnderABulkTypedRequestPublishesNothing(
            @TempDir Path workspace, @TempDir Path control, @TempDir Path forged) throws Exception {
        onePage(workspace);
        Turn turn = run(workspace, DELETE_THIS_PAGE, List.of("I can delete the page for you."));

        assertRecordOnlyAccepted(control, turn.record());
        assertRejected(forged, withUserInput(turn.record(), "please remove all the pages"));
    }

    @Test
    void aComposedDeleteUnderATypedRequestNamingAnotherFilePublishesNothing(
            @TempDir Path workspace, @TempDir Path control, @TempDir Path forged) throws Exception {
        onePage(workspace);
        Turn turn = run(workspace, DELETE_THIS_PAGE, List.of("I can delete the page for you."));

        assertRecordOnlyAccepted(control, turn.record());
        assertRejected(forged, withUserInput(turn.record(), "please delete about.html"));
    }

    @Test
    void aComposedDeleteUnderAConstrainedTypedRequestPublishesNothing(
            @TempDir Path workspace, @TempDir Path control, @TempDir Path forged) throws Exception {
        onePage(workspace);
        Turn turn = run(workspace, DELETE_THIS_PAGE, List.of("I can delete the page for you."));

        assertRecordOnlyAccepted(control, turn.record());
        assertRejected(forged, withUserInput(turn.record(), "delete the page but keep a backup"));
    }

    @Test
    void aComposedDeleteOfAFileOfAnotherKindPublishesNothing(
            @TempDir Path workspace, @TempDir Path control, @TempDir Path forged) throws Exception {
        onePage(workspace);
        Turn turn = run(workspace, DELETE_THIS_PAGE, List.of("I can delete the page for you."));

        assertRecordOnlyAccepted(control, turn.record());
        assertRejected(forged, withUserInput(turn.record(), "please remove the stylesheet"));
    }

    private static void assertEvidenceIdentifies(TurnRecord record, String composed) {
        var decisions = record.policyTrace().lexicalDecisions();
        var regions = record.policyTrace().lexicalRegions();
        String identity = MutationPlanSelection.sourceRequestSha256(composed);
        assertFalse(decisions.isEmpty() && regions.isEmpty(), record.policyTrace().toString());
        assertTrue(decisions.stream().allMatch(decision -> decision.sourceRequestSha256().equals(identity))
                && regions.stream().allMatch(region -> region.sourceRequestSha256().equals(identity)),
                record.policyTrace().toString());
    }

    private static void assertPersisted(Persisted persisted, String request) {
        assertEquals(TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED, persisted.receipt().state());
        assertEquals(1, persisted.loaded().size());
        assertEquals(request, persisted.loaded().getFirst().userInput());
    }

    private static void assertNoAuthority(TurnPolicyTrace policy) {
        assertTrue(policy.answeredWithoutExecution(), policy.toString());
        assertFalse(policy.mutationAllowed(), policy.toString());
        assertFalse(policy.mutationRequested(), policy.toString());
        assertTrue(policy.expectedTargets().isEmpty(), policy.toString());
        assertTrue(policy.rolefulTargets().isEmpty(), policy.toString());
        assertTrue(policy.nativeTools().isEmpty() && policy.promptTools().isEmpty(), policy.toString());
        assertTrue(policy.lexicalDecisions().stream().noneMatch(TurnPolicyTrace.LexicalDecisionEvidence::activeInResolution),
                policy.toString());
        assertEquals("NONE", policy.workspaceOperationPlan().state(), policy.toString());
    }

    private static void assertRecordOnlyAccepted(Path storeDirectory, TurnRecord record) {
        TurnRecord recordOnly = withTraceId(record, "");
        JsonSessionStore store = new JsonSessionStore(storeDirectory);
        try (var writer = store.acquireSessionWriter(SESSION)) {
            assertEquals(TurnEnvelopePersistenceReceipt.State.TURN_WRITE_ONLY,
                    writer.persistTurnEnvelope(SESSION, recordOnly, null).state());
        }
        assertEquals(1, store.loadTurns(SESSION).size());
    }

    private static void assertRejected(Path storeDirectory, TurnRecord record) {
        TurnRecord recordOnly = withTraceId(record, "");
        JsonSessionStore store = new JsonSessionStore(storeDirectory);
        try (var writer = store.acquireSessionWriter(SESSION)) {
            assertEquals(TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED,
                    writer.persistTurnEnvelope(SESSION, recordOnly, null).state());
        }
        assertFalse(Files.exists(storeDirectory.resolve(SESSION + ".turns.jsonl")));
    }

    private static TurnRecord withUserInput(TurnRecord record, String userInput) {
        return new TurnRecord(record.turnNumber(), record.timestamp(), record.durationMs(), userInput,
                record.assistantText(), record.toolCalls(), record.approvalsRequired(), record.approvalsGranted(),
                record.approvalsDenied(), record.retrievalTraceSummary(), record.status(), record.policyTrace(),
                record.traceId());
    }

    private static TurnRecord withTraceId(TurnRecord record, String traceId) {
        return new TurnRecord(record.turnNumber(), record.timestamp(), record.durationMs(), record.userInput(),
                record.assistantText(), record.toolCalls(), record.approvalsRequired(), record.approvalsGranted(),
                record.approvalsDenied(), record.retrievalTraceSummary(), record.status(), record.policyTrace(),
                traceId);
    }

    private record Turn(
            String text,
            ScriptedNativeLlmClient.RecordedClient provider,
            TurnRecord record,
            LocalTurnTrace trace) {}

    private record Persisted(TurnEnvelopePersistenceReceipt receipt, List<TurnRecord> loaded) {}

    private static Persisted persist(Path storeDirectory, Turn turn) {
        JsonSessionStore store = new JsonSessionStore(storeDirectory);
        TurnEnvelopePersistenceReceipt receipt;
        try (var writer = store.acquireSessionWriter(SESSION)) {
            receipt = writer.persistTurnEnvelope(SESSION, turn.record(), turn.trace());
        }
        return new Persisted(receipt, store.loadTurns(SESSION));
    }

    private static Turn run(Path workspace, String request, List<String> responses) {
        return run(workspace, List.of(), request, responses);
    }

    private static Turn run(Path workspace, List<ChatMessage> history, String request, List<String> responses) {
        return run(workspace, history, request, responses, 1);
    }

    private static Turn run(
            Path workspace, List<ChatMessage> history, String request, List<String> responses, int turnNumber) {
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.begin("trc-operand-persistence-" + turnNumber, SESSION, turnNumber,
                "2026-10-30T00:00:0" + turnNumber + "Z", "workspace-hash", "test", "llama_cpp", "test-model", request);
        var registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());
        registry.register(new dev.talos.tools.impl.FileWriteTool());
        registry.register(new dev.talos.tools.impl.FileEditTool());
        registry.register(new dev.talos.tools.impl.DeletePathTool());
        var processor = new dev.talos.runtime.TurnProcessor(null, new dev.talos.runtime.NoOpApprovalGate(), registry);
        var provider = recordingProvider(registry, responses);
        var ctx = Context.builder(new Config())
                .llm(provider.client())
                .memory(new SessionMemory())
                .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new dev.talos.runtime.ToolCallLoop(processor, 6))
                .build();
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.addAll(history);
        messages.add(ChatMessage.user(request));
        TurnAuditCapture.begin();
        AssistantTurnExecutor.TurnOutput output;
        TurnAudit audit;
        LocalTurnTrace trace;
        try {
            output = AssistantTurnExecutor.execute(messages, workspace, ctx, new AssistantTurnExecutor.Options());
        } finally {
            trace = LocalTurnTraceCapture.complete();
            audit = TurnAuditCapture.end();
        }
        TurnRecord record = new TurnRecord(turnNumber, Instant.parse("2026-10-30T00:00:0" + turnNumber + "Z"), 1,
                request, output.text(),
                audit.toolCalls(), audit.approvalsRequired(), audit.approvalsGranted(), audit.approvalsDenied(),
                "", "ok", audit.policyTrace(), trace.traceId());
        return new Turn(output.text(), provider, record, trace);
    }

    private static List<String> contentEditResponses() {
        return List.of(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"index.html\","
                        + "\"old_string\":\"<footer>Footer</footer>\\n\",\"new_string\":\"\"}}",
                "Removed the footer from index.html.");
    }

    private static ScriptedNativeLlmClient.RecordedClient recordingProvider(ToolRegistry registry,
                                                                           List<String> responses) {
        List<LlmClient.StreamResult> results = responses.stream()
                .map(response -> new LlmClient.StreamResult(response, List.of()))
                .toList();
        var recorded = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(results, 16_384);
        recorded.client().setToolSpecs(registry.descriptors().stream()
                .map(descriptor -> new ToolSpec(descriptor.name(), descriptor.description(),
                        descriptor.parametersSchema() == null ? "{}" : descriptor.parametersSchema()))
                .toList());
        return recorded;
    }

    private static void onePage(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<main>Main</main>\n<footer>Footer</footer>\n");
        Files.writeString(workspace.resolve("style.css"), "body {}\n");
    }
}
