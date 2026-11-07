package dev.talos.cli.repl;

import dev.talos.cli.approval.CliApprovalGate;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.slash.CommandRegistry;
import dev.talos.core.Audit;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.net.NetPolicy;
import dev.talos.core.rag.RagService;
import dev.talos.core.security.Redactor;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.JsonTurnLogAppender;
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionApprovalPolicy;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.SessionWriterLease;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnTraceCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.command.CommandProfileRegistry;
import dev.talos.runtime.command.ProcessCommandRunner;
import dev.talos.runtime.command.RunCommandTool;
import dev.talos.runtime.toolcall.DocumentComparisonTool;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.DeletePathTool;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RenamePathTool;
import dev.talos.tools.impl.RetrieveTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A round in which every call succeeded ends the turn only when request-bound proof already holds (P20, finding LA-F3,
 * D14 option A), and a checks pass on a changed turn states that task completion was not verified (D15 option A),
 * through the real REPL entry with the harness of ReplacementScopeReplEntryTest: the bootstrap composition with a
 * scripted native provider that records every request and repeats its last response once its script is exhausted,
 * or throws a given failure instead, the TalosBootstrap tool registry, mode routing, the real approval gate and a
 * real session store. The provider and the command profile registry are synthetic.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(120)
class UnprovenMutationContinuationReplEntryTest {
    private static final String TWO_PART =
            "Update notes.md: change the heading to Session Notes and add an item that says Mix the drums.";
    private static final String NOTES = "# Notes\n\n- Tune the bass line.\n- Record the second take.\n";
    private static final String HEADING_ONLY = "# Session Notes\n\n- Tune the bass line.\n- Record the second take.\n";
    private static final String BOTH_PARTS =
            "# Session Notes\n\n- Tune the bass line.\n- Record the second take.\n- Mix the drums.\n";
    private static final String PAGE = "<!DOCTYPE html>\n<html>\n<head>\n<title>Dead Synth</title>\n"
            + "<link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n<h1>Dead Synth</h1>\n</body>\n</html>\n";
    private static final String STYLE = "h1 {\n  color: black;\n}\n";
    private static final String NOT_VERIFIED = "Task completion was not verified.";
    private static final String DECIDED = "MUTATION_CONTINUATION_DECIDED";

    @TempDir
    Path temp;

    @Test
    void aSecondChangeOfOneFileInALaterResponseIsRequestedAndApplied() throws Exception {
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                edit("notes.md", "- Record the second take.\n", "- Record the second take.\n- Mix the drums.\n"),
                text("Changed the heading and added the item.")), List.of("y", "y"),
                Map.of("notes.md", NOTES));

        assertEquals(BOTH_PARTS, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(2, run.prompts().size(), run.transcript());
        assertEquals(4, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED", "CONTINUED"), decisions(run), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains(NOT_VERIFIED), answer);
        assertTrue(answer.contains("[Checks passed - "), answer);
    }

    @Test
    void aRequestBoundPassEndsWithoutAFurtherProviderRequest() throws Exception {
        Run run = run("In notes.md, change second take to final take.", List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "second take", "final take")), List.of("y"),
                Map.of("notes.md", NOTES));

        assertEquals("# Notes\n\n- Tune the bass line.\n- Record the final take.\n",
                Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(2, run.requests().size(), run.transcript());
        assertEquals(List.of("PROOF_HELD"), decisions(run), run.transcript());
        Map<String, Object> decided = events(run, DECIDED).getFirst();
        assertEquals("PASSED", decided.get("proofStatus"), decided.toString());
        assertEquals(Boolean.TRUE, decided.get("requestBound"), decided.toString());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains("[Static verification: passed - "), answer);
        assertFalse(answer.contains(NOT_VERIFIED), answer);
    }

    @Test
    void anEmptyContinuationReplyEndsWithTheToolSummary() throws Exception {
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                text("")), List.of("y"),
                Map.of("notes.md", NOTES));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(3, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        // The displayed answer carries the summary of the change, as the P0 skip gives it. The stored answer keeps
        // the checks bracket, as the live gpt-oss C3 row did.
        assertTrue(run.transcript().contains("Edited notes.md"), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains(NOT_VERIFIED), answer);
        assertFalse(answer.contains("no executable write/edit tool calls"), answer);
        assertFalse(run.transcript().contains("no executable write/edit tool calls"), run.transcript());
    }

    @Test
    void aContinuationClaimOfAChangeThatWasNotMadeIsNotCompleted() throws Exception {
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                text("I updated notes.md and I changed todo.md during this turn.")), List.of("y"),
                Map.of("notes.md", NOTES));

        assertFalse(Files.exists(run.workspace().resolve("todo.md")), run.transcript());
        assertEquals(3, run.requests().size(), run.transcript());
        assertNotEquals("COMPLETE", outcomeStatus(run), run.transcript());
    }

    @Test
    void aContinuationChangeOutsideTheNamedTargetIsBlockedBeforeApprovalAfterASessionApproval() throws Exception {
        Run run = run(TWO_PART, List.of(
                edit("notes.md", "# Notes", "# Session Notes"),
                edit("other.md", "# Other", "# Other page"),
                text("Changed the heading.")), List.of("a", "y"),
                Map.of("notes.md", NOTES, "other.md", "# Other\n"));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals("# Other\n", Files.readString(run.workspace().resolve("other.md")), run.transcript());
        assertEquals(1, run.prompts().size(), run.transcript());
        assertTrue(run.prompts().getFirst().contains("a=yes for session"), run.prompts().toString());
        assertEquals("CONTINUED", decisions(run).getFirst(), run.transcript());
        assertTrue(events(run, "TOOL_CALL_BLOCKED").stream()
                .anyMatch(data -> "other.md".equals(data.get("pathHint"))), run.transcript());
    }

    @Test
    void aContinuationChangeForARequestThatNamesNoFileIsPromptedWithoutTheSessionOption() throws Exception {
        Run run = run("fix the page", List.of(
                edit("style.css", "color: black;", "color: navy;"),
                edit("index.html", "<h1>Dead Synth</h1>", "<h1>Dead Synth Live</h1>"),
                text("Adjusted the page.")), List.of("y", "n"),
                Map.of("index.html", PAGE, "style.css", STYLE));

        assertEquals(PAGE, Files.readString(run.workspace().resolve("index.html")), run.transcript());
        assertEquals("h1 {\n  color: navy;\n}\n", Files.readString(run.workspace().resolve("style.css")),
                run.transcript());
        assertEquals(2, run.prompts().size(), run.transcript());
        assertTrue(run.prompts().stream().noneMatch(prompt -> prompt.contains("a=yes for session")),
                run.prompts().toString());
        assertEquals("CONTINUED", decisions(run).getFirst(), run.transcript());
    }

    @Test
    void aProviderFailureOfTheContinuationEndsWithTheChangeAsBeforeTheContinuation() throws Exception {
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes")), List.of("y"),
                Map.of("notes.md", NOTES),
                new dev.talos.spi.EngineException.ConnectionFailed("127.0.0.1:18261", null));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(3, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        assertEquals(List.of("PROVIDER_FAILURE"), events(run, "MUTATION_CONTINUATION_UNANSWERED").stream()
                .map(data -> String.valueOf(data.get("reason"))).toList(), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        assertTrue(run.transcript().contains("Edited notes.md"), run.transcript());
        assertTrue(run.onlyRow().assistantText().contains(NOT_VERIFIED), run.onlyRow().assistantText());
    }

    @Test
    void aFailureOfALaterRequestAfterTheContinuationEndsWithTheChange() throws Exception {
        // The continuation is answered with a read, and the request after it fails at the provider.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                nativeCall("talos.read_file", Map.of("path", "notes.md"))), List.of("y"),
                Map.of("notes.md", NOTES),
                new dev.talos.spi.EngineException.ConnectionFailed("127.0.0.1:18261", null));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(4, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        assertEquals(List.of("PROVIDER_FAILURE"), events(run, "MUTATION_CONTINUATION_UNANSWERED").stream()
                .map(data -> String.valueOf(data.get("reason"))).toList(), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        assertTrue(run.transcript().contains("Edited notes.md"), run.transcript());
    }

    @Test
    void aContextBudgetOverflowOfTheContinuationEndsWithTheChangeAsBeforeTheContinuation() throws Exception {
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes")), List.of("y"),
                Map.of("notes.md", NOTES),
                new dev.talos.spi.EngineException.ContextBudgetExceeded(5000, 1334, 2048, 0));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(List.of("CONTEXT_BUDGET"), events(run, "MUTATION_CONTINUATION_UNANSWERED").stream()
                .map(data -> String.valueOf(data.get("reason"))).toList(), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        assertFalse(run.onlyRow().assistantText().contains("Action obligation failed"),
                run.onlyRow().assistantText());
    }

    @Test
    void aContinuationReplyWhoseCallWasRefusedEndsWithTheChangeAsBeforeTheContinuation() throws Exception {
        // P21 (finding LP-F1, D17 option A, D18 option A): the reply to the continuation writes its call as text in
        // native tool mode, as qwen did in every live D1 sample. The refused call never runs, and the turn ends as its
        // completed change left it, without a failure stop and without a further request.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                text(textCall("talos.read_file", Map.of("path", "notes.md"))),
                text("Unused.")), List.of("y"),
                Map.of("notes.md", NOTES));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(3, run.requests().size(), run.transcript());
        assertEquals(1, run.prompts().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        List<Map<String, Object>> unanswered = events(run, "MUTATION_CONTINUATION_UNANSWERED");
        assertEquals(List.of("TOOL_AUTHORITY_REJECTED"), unanswered.stream()
                .map(data -> String.valueOf(data.get("reason"))).toList(), run.transcript());
        assertEquals("PROVIDER_TOOL_MODE_MISMATCH", String.valueOf(unanswered.getFirst().get("failureType")),
                unanswered.toString());
        assertFalse(events(run, "TOOL_CALL_AUTHORITY_REJECTED").isEmpty(), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        String answerBox = answerBox(run);
        assertTrue(answerBox.contains("Edited notes.md"), answerBox);
        assertTrue(answerBox.contains("[1 not run]"), answerBox);
        assertFalse(answerBox.contains(" failed]"), answerBox);
        assertFalse(answerBox.contains("[failure policy stopped]"), answerBox);
        assertFalse(answerBox.contains("PROVIDER_TOOL_MODE_MISMATCH"), answerBox);
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains(NOT_VERIFIED), answer);
        assertFalse(answer.contains("PROVIDER_TOOL_MODE_MISMATCH"), answer);
    }

    @Test
    void aRefusedTextFormEditAfterTheContinuationNeverRuns() throws Exception {
        // P21 review: the refused call is an edit, so its never running is visible in the file. The turn is PARTIAL
        // and lists the refused proposal as failed, the LA-F7 class that P21 does not change (finding P21-F1).
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                text(textCall("talos.edit_file", Map.of("path", "notes.md",
                        "old_string", "- Record the second take.\n",
                        "new_string", "- Record the second take.\n- Mix the drums.\n"))),
                text("Unused.")), List.of("y", "y"),
                Map.of("notes.md", NOTES));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(1, run.prompts().size(), run.transcript());
        assertEquals(3, run.requests().size(), run.transcript());
        assertEquals(List.of("TOOL_AUTHORITY_REJECTED"), unansweredReasons(run), run.transcript());
        String answerBox = answerBox(run);
        assertTrue(answerBox.contains("[1 not run]"), answerBox);
        assertFalse(answerBox.contains(" failed]"), answerBox);
        assertFalse(answerBox.contains("[failure policy stopped]"), answerBox);
    }

    @Test
    void aRefusedReplyAfterAContinuationAndAFailedEditEndsWithTheChange() throws Exception {
        // P21 review: the continuation stays issued for the rest of the loop, as P20's rule states. After it, an edit
        // fails before approval, the ordinary re-prompt follows, and its reply writes the call as text.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                edit("notes.md", "- Record the third take.\n", "- Record the third take.\n- Mix the drums.\n"),
                text(textCall("talos.read_file", Map.of("path", "notes.md"))),
                text("Unused.")), List.of("y", "y"),
                Map.of("notes.md", NOTES));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(4, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        assertEquals(List.of("TOOL_AUTHORITY_REJECTED"), unansweredReasons(run), run.transcript());
        String answerBox = answerBox(run);
        assertTrue(answerBox.contains("[1 failed] [1 not run]"), answerBox);
        assertFalse(answerBox.contains("[failure policy stopped]"), answerBox);
        assertFalse(answerBox.contains("PROVIDER_TOOL_MODE_MISMATCH"), answerBox);
    }

    /** The last answer box of the transcript, read with the box's line wrapping joined. */
    private static String answerBox(Run run) {
        return run.transcript().substring(run.transcript().lastIndexOf("+- answer -"))
                .replaceAll("\\s*\\R\\s*\\|\\s*", " ");
    }

    private static List<String> unansweredReasons(Run run) {
        return events(run, "MUTATION_CONTINUATION_UNANSWERED").stream()
                .map(data -> String.valueOf(data.get("reason"))).toList();
    }

    @Test
    void aRequestOfOneAuthorizedOperationEndsOnceThatOperationSucceeds() throws Exception {
        Run run = run("Rename a.md to b.md.", List.of(
                nativeCall("talos.rename_path", Map.of("path", "a.md", "new_name", "b.md"))), List.of("y"),
                Map.of("a.md", "# A\n"));

        assertTrue(Files.exists(run.workspace().resolve("b.md")), run.transcript());
        assertFalse(Files.exists(run.workspace().resolve("a.md")), run.transcript());
        assertEquals(1, run.requests().size(), run.transcript());
        assertEquals(List.of("OPERATION_COMPLETE"), decisions(run), run.transcript());
    }

    @Test
    void theIterationLimitEndsAModelThatKeepsChangingTheFile() throws Exception {
        StringBuilder items = new StringBuilder("# Items\n\n");
        List<LlmClient.StreamResult> responses = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String item = String.format("- item %02d", i);
            items.append(item).append('\n');
            responses.add(edit("items.md", item, String.format("- done %02d", i)));
            answers.add("y");
        }
        Run run = run("Update items.md: mark every item as done.", responses, answers,
                Map.of("items.md", items.toString()));

        int limit = ToolCallLoop.DEFAULT_MAX_ITERATIONS;
        assertEquals(limit, run.requests().size(), run.transcript());
        List<String> decisions = decisions(run);
        assertEquals(limit, decisions.size(), decisions.toString());
        assertEquals("LIMIT_REACHED", decisions.getLast(), decisions.toString());
        assertTrue(decisions.subList(0, limit - 1).stream().allMatch("CONTINUED"::equals), decisions.toString());
        String file = Files.readString(run.workspace().resolve("items.md"));
        assertTrue(file.contains(String.format("- done %02d", limit)), file);
        assertTrue(file.contains(String.format("- item %02d", limit + 1)), file);
        // After continuations the answer lists every change of the turn, not only the last round's.
        String answerBox = run.transcript().substring(run.transcript().lastIndexOf("+- answer -"));
        assertEquals(limit, answerBox.split("Edited items.md", -1).length - 1, answerBox);
    }

    private static List<String> decisions(Run run) {
        return events(run, DECIDED).stream().map(data -> String.valueOf(data.get("decision"))).toList();
    }

    private static String outcomeStatus(Run run) {
        List<Map<String, Object>> outcomes = events(run, "OUTCOME_EVALUATED");
        assertEquals(1, outcomes.size(), run.transcript());
        return String.valueOf(outcomes.getFirst().get("status"));
    }

    private static List<Map<String, Object>> events(Run run, String type) {
        return run.trace().events().stream()
                .filter(event -> type.equals(event.type()))
                .map(event -> event.data())
                .toList();
    }

    private record Run(
            Path workspace,
            List<ChatRequest> requests,
            List<String> prompts,
            String transcript,
            List<TurnRecord> rows,
            LocalTurnTrace trace) {
        TurnRecord onlyRow() {
            assertEquals(1, rows.size(), transcript);
            return rows.getFirst();
        }
    }

    private Run run(String request, List<LlmClient.StreamResult> responses, List<String> answers,
                    Map<String, String> files) throws Exception {
        return run(request, responses, answers, files, null);
    }

    /**
     * Runs one turn. Approval prompt i is answered with answers[i], and any further prompt is denied. Without a
     * terminal failure the provider repeats its last response once the script is exhausted. With one it throws that
     * failure instead.
     */
    private Run run(String request, List<LlmClient.StreamResult> responses, List<String> answers,
                    Map<String, String> files, RuntimeException terminalFailure) throws Exception {
        Path dir = Files.createDirectories(temp.resolve("run-" + Math.abs(request.hashCode())
                + "-" + responses.size() + "-" + String.join("", answers)
                + (terminalFailure == null ? "" : "-" + terminalFailure.getClass().getSimpleName())));
        Path home = Files.createDirectories(dir.resolve("home"));
        Path ws = Files.createDirectories(dir.resolve("workspace"));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(ws.resolve(file.getKey()), file.getValue());
        }
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Path sessions = home.resolve(".talos").resolve("sessions");
        SessionWriterLease lease = null;
        JsonSessionStore store = null;
        RenderEngine render = null;
        try {
            Config cfg = new Config(null);
            Redactor redactor = new Redactor();
            RagService rag = new RagService(cfg);
            CommandProfileRegistry profiles = CommandProfileRegistry.defaultRegistry();
            ToolRegistry registry = productionRegistry(rag, profiles);
            ScriptedNativeLlmClient.RecordedClient provider = terminalFailure == null
                    ? ScriptedNativeLlmClient.recordingWithContextWindow(responses, 16_384)
                    : ScriptedNativeLlmClient.recordingThenFailureWithContextWindow(responses, 16_384, terminalFailure);
            LlmClient llm = provider.client();
            llm.setToolSpecs(registry.descriptors().stream()
                    .map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema()))
                    .toList());

            SessionMemory memory = new SessionMemory();
            ConversationManager conversation = new ConversationManager(memory, TokenBudget.fromConfig(cfg));
            store = new JsonSessionStore(sessions);
            lease = store.reserveNewSession(ws, Instant.now());
            String sessionId = lease.sessionId();
            Session runtimeSession = new Session(ws, cfg, memory, lease);
            ModeController modes = ModeController.defaultController();

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            render = new RenderEngine(cfg, redactor, out, false);
            RenderEngine renderRef = render;
            List<String> prompts = new ArrayList<>();
            CliApprovalGate gate = new CliApprovalGate(prompt -> {
                prompts.add(prompt);
                return prompts.size() <= answers.size() ? answers.get(prompts.size() - 1) : "n";
            }, out, renderRef::stopSpinner);
            TurnProcessor processor = new TurnProcessor(modes, gate, registry, new SessionApprovalPolicy(),
                    new CheckpointService(), profiles);
            ToolCallLoop loop = new ToolCallLoop(processor, ToolCallLoop.DEFAULT_MAX_ITERATIONS,
                    renderRef::printToolProgressResumingSpinner);
            SessionState state = new SessionState() {
                private int k = 6;
                private boolean debug;
                public int getK() { return k; }
                public void setK(int value) { k = value; }
                public boolean isDebug() { return debug; }
                public void setDebug(boolean on) { debug = on; }
            };
            Context ctx = Context.builder(cfg)
                    .limits(Limits.fromConfig(cfg))
                    .session(state)
                    .audit(new Audit())
                    .redactor(redactor)
                    .sandbox(new Sandbox(ws, Map.of()))
                    .rag(rag)
                    .llm(llm)
                    .netPolicy(new NetPolicy(cfg))
                    .memory(memory)
                    .approvalGate(gate)
                    .toolRegistry(registry)
                    .conversationManager(conversation)
                    .toolCallLoop(loop)
                    .streamSink(new ToolCallStreamFilter(render.answerStreamSink(chunk -> {
                        out.print(chunk);
                        out.flush();
                    })))
                    .onStreamComplete(renderRef::stopSpinner)
                    .exactAnswerPresentation(render.exactAnswerPresentation())
                    .build();
            MemoryUpdateListener memoryListener = new MemoryUpdateListener(conversation, llm, memory);
            memoryListener.setAssistMode(true);
            processor.addListener(memoryListener);
            processor.addListener(new ActiveTaskContextUpdateListener(memory));
            processor.addListener(new JsonTurnLogAppender(lease, sessionId));
            ReplRouter router = new ReplRouter(modes, processor, runtimeSession, ctx, render,
                    new CommandRegistry(), ws, new AtomicBoolean(false), "", WorkspaceCommandTemplates.none());

            assertTrue(router.tryHandlePrompt(request));
            render.shutdown();
            render = null;
            String transcript = bytes.toString(StandardCharsets.UTF_8).replaceAll("\u001B\\[[0-9;]*m", "");
            try (JsonSessionStore reopened = new JsonSessionStore(sessions)) {
                List<TurnRecord> rows = reopened.loadTurns(sessionId);
                LocalTurnTrace trace = rows.isEmpty() ? null
                        : reopened.loadTrace(sessionId, rows.getLast().traceId()).orElse(null);
                assertTrue(trace != null, "The turn trace must persist and reload: " + transcript);
                return new Run(ws, List.copyOf(provider.requests()), List.copyOf(prompts), transcript, rows, trace);
            }
        } finally {
            if (render != null) render.shutdown();
            if (lease != null) lease.close();
            if (store != null) store.close();
            TurnUserRequestCapture.clear();
            TurnTaskContractCapture.clear();
            TurnSourceEvidenceCapture.clear();
            TurnTraceCapture.clear();
            LocalTurnTraceCapture.clear();
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
            PromptDebugCapture.clear();
            LastPromptCapture.clear();
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    /** The TalosBootstrap registry in its registration order. */
    private static ToolRegistry productionRegistry(RagService rag, CommandProfileRegistry profiles) {
        ToolRegistry registry = new ToolRegistry();
        for (TalosTool tool : List.<TalosTool>of(new ReadFileTool(), new DocumentComparisonTool(),
                new FileWriteTool(), new FileEditTool(), new BatchWorkspaceApplyTool(), new MakeDirectoryTool(),
                new MovePathTool(), new CopyPathTool(), new RenamePathTool(), new DeletePathTool(),
                new RunCommandTool(profiles, new ProcessCommandRunner()), new GrepTool(), new ListDirTool(),
                new RetrieveTool(rag))) {
            registry.register(tool);
        }
        return registry;
    }

    private static LlmClient.StreamResult nativeCall(String tool, Map<String, Object> arguments) {
        return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("call-" + tool, tool, arguments)),
                "tool_calls");
    }

    private static LlmClient.StreamResult edit(String path, String oldString, String newString) {
        return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                "call-edit-" + Math.abs((path + oldString).hashCode()), "talos.edit_file",
                Map.of("path", path, "old_string", oldString, "new_string", newString))), "tool_calls");
    }

    private static LlmClient.StreamResult text(String content) {
        return new LlmClient.StreamResult(content, List.of(), "stop");
    }

    /** A tool call written as text, the form ToolModeCorrectionBoundaryTest uses, which native tool mode refuses. */
    private static String textCall(String tool, Map<String, Object> arguments) throws Exception {
        return "```json\n" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                Map.of("name", tool, "arguments", arguments)) + "\n```";
    }
}
