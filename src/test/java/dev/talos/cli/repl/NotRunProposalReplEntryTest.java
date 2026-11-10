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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-F7: a proposal that never reached a tool is not reported as a failed change. Through the real REPL entry with the
 * harness of UnprovenMutationContinuationReplEntryTest: the bootstrap composition with a scripted native provider, the
 * TalosBootstrap tool registry, mode routing, the real approval gate and a real session store. The provider and the
 * command profile registry are synthetic. The scripts replay the call shapes of harder coding lab version 3 session
 * laguna-h2-r2 over the h2 fixture text.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(120)
class NotRunProposalReplEntryTest {
    private static final String H2 = "Change the function name get_qty to quantity_on_hand in inventory.py, orders.py "
            + "and report.py, including its definition, imports and calls, so that the code works as before.";
    private static final String INVENTORY = "\"\"\"Stock levels by SKU.\"\"\"\n\n\ndef get_qty(stock, sku):\n"
            + "    \"\"\"Return the quantity on hand for a SKU, or 0 when the SKU is unknown.\"\"\"\n"
            + "    return stock.get(sku, 0)\n\n\ndef total_units(stock):\n"
            + "    \"\"\"Return the number of units across every SKU.\"\"\"\n    return sum(stock.values())\n";
    private static final String ORDERS = "\"\"\"Order checks.\"\"\"\nfrom inventory import get_qty\n\n\n"
            + "def can_fulfil(stock, order):\n    \"\"\"Return True when every line of the order is in stock.\"\"\"\n"
            + "    return all(get_qty(stock, sku) >= qty for sku, qty in order.items())\n";
    private static final String REPORT = "\"\"\"Stock report.\"\"\"\nfrom inventory import get_qty\n\n\n"
            + "def low_stock(stock, skus, threshold=5):\n"
            + "    \"\"\"Return the SKUs whose quantity on hand is below the threshold, in the given order.\"\"\"\n"
            + "    return [sku for sku in skus if get_qty(stock, sku) < threshold]\n";
    private static final String DECIDED = "MUTATION_CONTINUATION_DECIDED";

    @TempDir
    Path temp;

    @Test
    void refusedEditsAfterEveryRequestedFileChangedAreListedAsNotRunAndTheTurnCompletes() throws Exception {
        Run run = run(H2, List.of(
                calls(read("inventory.py"), read("orders.py"), read("report.py")),
                calls(editCall("inventory.py", "def get_qty(", "def quantity_on_hand("),
                        editCall("orders.py", "from inventory import get_qty", "from inventory import quantity_on_hand"),
                        editCall("orders.py", "get_qty(stock, sku) >= qty", "quantity_on_hand(stock, sku) >= qty")),
                edit("report.py", "from inventory import get_qty", "from inventory import quantity_on_hand"),
                edit("report.py", "if get_qty(stock, sku) < threshold", "if quantity_on_hand(stock, sku) < threshold"),
                calls(editCall("inventory.py", "def quantity_on_hand(", "def quantity_on_hand("),
                        editCall("inventory.py", "", "def quantity_on_hand(")),
                text("Renamed get_qty to quantity_on_hand in all three files.")),
                List.of("y", "y", "y", "y", "y"), fixtures());

        for (String file : List.of("inventory.py", "orders.py", "report.py")) {
            String text = Files.readString(run.workspace().resolve(file));
            assertFalse(text.contains("get_qty"), file + ": " + text);
            assertTrue(text.contains("quantity_on_hand"), file + ": " + text);
        }
        assertEquals(5, run.prompts().size(), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        assertEquals("COMPLETED_UNVERIFIED", outcomeClassification(run), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.startsWith("[Checks passed - "), answer);
        assertTrue(answer.contains("[Truth check: the file changes listed below were applied. Other proposed changes "
                + "in this turn did not run and changed no file.]"), answer);
        assertTrue(answer.contains("Not run (these changed no file):\n"
                + "- inventory.py: talos.edit_file not run, refused before approval "
                + "(old_string and new_string were identical).\n"
                + "- inventory.py: talos.edit_file not run, refused before approval "
                + "(old_string or new_string missing or empty)."), answer);
        for (String absent : List.of("some requested file changes succeeded and some failed", "Failed:",
                "Updated 3 files:", "No approval was requested", "Renamed get_qty")) {
            assertFalse(answer.contains(absent), absent + " in " + answer);
        }
        String box = answerBox(run);
        assertTrue(box.contains("[2 not run]"), box);
        assertFalse(box.contains(" failed]"), box);
    }

    @Test
    void anExpectedFileWithoutAChangeKeepsTheTurnPartialAndListsTheRefusalsAsNotRun() throws Exception {
        Run run = run(H2, List.of(
                calls(read("inventory.py"), read("orders.py"), read("report.py")),
                calls(editCall("inventory.py", "def get_qty(", "def quantity_on_hand("),
                        editCall("orders.py", "from inventory import get_qty", "from inventory import quantity_on_hand"),
                        editCall("orders.py", "get_qty(stock, sku) >= qty", "quantity_on_hand(stock, sku) >= qty")),
                calls(editCall("orders.py", "get_qty(stock, sku) >= qty", "quantity_on_hand(stock, sku) >= qty")),
                text("Renamed get_qty to quantity_on_hand in all three files.")),
                List.of("y", "y", "y"), fixtures());

        assertTrue(Files.readString(run.workspace().resolve("report.py")).contains("get_qty"), run.transcript());
        // The unmet expected-target obligation still decides the turn. LA-F7 changes only the appended record.
        assertEquals("BLOCKED", outcomeStatus(run), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains("[Action obligation failed: pending expected target progress was not satisfied.]"),
                answer);
        assertTrue(answer.contains("[Truth check: some file changes were applied, but this turn's records do not "
                + "show a change to every file this request expects."), answer);
        assertTrue(answer.contains("Expected files not changed:\n"
                + "- report.py: expected by this request, but not changed in this turn.\n"
                + "Not run (these changed no file):\n"
                + "- orders.py: talos.edit_file not run, refused before approval (old_string not found in the file)."),
                answer);
        assertFalse(answer.contains("some requested file changes succeeded and some failed"), answer);
        assertFalse(answer.contains("Failed:"), answer);
        String box = answerBox(run);
        assertTrue(box.contains("[1 not run]"), box);
        assertFalse(box.contains(" failed]"), box);
    }

    private static Map<String, String> fixtures() {
        return Map.of("inventory.py", INVENTORY, "orders.py", ORDERS, "report.py", REPORT);
    }

    private static String outcomeClassification(Run run) {
        List<Map<String, Object>> outcomes = events(run, "OUTCOME_EVALUATED");
        assertEquals(1, outcomes.size(), run.transcript());
        return String.valueOf(outcomes.getFirst().get("classification"));
    }

    private static ChatMessage.NativeToolCall read(String path) {
        return new ChatMessage.NativeToolCall("call-read-" + Math.abs(path.hashCode()), "talos.read_file",
                Map.of("path", path));
    }

    private static ChatMessage.NativeToolCall editCall(String path, String oldString, String newString) {
        return new ChatMessage.NativeToolCall("call-edit-" + Math.abs((path + "|" + oldString + "|" + newString)
                .hashCode()), "talos.edit_file", Map.of("path", path, "old_string", oldString, "new_string", newString));
    }

    private static LlmClient.StreamResult calls(ChatMessage.NativeToolCall... calls) {
        return new LlmClient.StreamResult("", List.of(calls), "tool_calls");
    }

    /** The last answer box of the transcript, read with the box's line wrapping joined. */
    private static String answerBox(Run run) {
        return run.transcript().substring(run.transcript().lastIndexOf("+- answer -"))
                .replaceAll("\\s*\\R\\s*\\|\\s*", " ");
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
        return runTurns(List.of(request), responses, answers, files, terminalFailure);
    }

    /**
     * Runs one turn per request in one session, with one provider script, one approval policy and one answer list
     * across the turns. The trace is the last turn's.
     */
    private Run runTurns(List<String> turnRequests, List<LlmClient.StreamResult> responses, List<String> answers,
                         Map<String, String> files, RuntimeException terminalFailure) throws Exception {
        String request = String.join("\n", turnRequests);
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

            for (String turnRequest : turnRequests) {
                assertTrue(router.tryHandlePrompt(turnRequest));
            }
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
}
