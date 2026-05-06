package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.SessionMemory;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.retrieval.RetrievalTrace;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.tools.*;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TurnProcessorTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void cleanupTrace() {
        // Clear any leftover trace from tests
        TurnTraceCapture.consume();
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test void nullInputReturnsNull() throws Exception {
        var tp = new TurnProcessor(ModeController.defaultController());
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        assertNull(tp.process(session, null, ctx));
        assertNull(tp.process(session, "  ", ctx));
        // Turn counter should not have incremented for null/blank inputs
        assertEquals(0, session.turnCount());
    }

    @Test void turnCounterIncrements() throws Exception {
        // Use a controller with a stub registered as "ask" so auto-mode's ASSIST route finds it
        var modes = new ModeController();
        modes.add(new StubMode("ask", true));
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult r1 = tp.process(session, "hello", ctx);
        assertNotNull(r1);
        assertEquals(1, r1.turnNumber());

        TurnResult r2 = tp.process(session, "world", ctx);
        assertNotNull(r2);
        assertEquals(2, r2.turnNumber());

        assertEquals(2, session.turnCount());
    }

    @Test void timingIsPositive() throws Exception {
        var modes = new ModeController();
        modes.add(new StubMode("ask", true));
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult r = tp.process(session, "test", ctx);
        assertNotNull(r);
        assertNotNull(r.elapsed());
        assertFalse(r.elapsed().isNegative());
    }

    @Test void noModeHandlesReturnsNull() throws Exception {
        // Empty controller — no modes registered
        var tp = new TurnProcessor(new ModeController());
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult r = tp.process(session, "orphan input", ctx);
        assertNull(r);
    }

    @Test void exceptionPropagatesForEnvelopeHandling() {
        var modes = new ModeController();
        modes.add(new StubMode("ask", true) {
            @Override public Optional<Result> handle(String raw, Path ws, Context c) throws Exception {
                throw new IllegalStateException("boom");
            }
        });
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        // Exceptions propagate to the caller (ExecutionPipeline) for redaction + audit
        var ex = assertThrows(IllegalStateException.class,
                () -> tp.process(session, "crash", ctx));
        assertEquals("boom", ex.getMessage());
        // Turn counter still incremented (turn was started before dispatch)
        assertEquals(1, session.turnCount());
    }

    @Test void approvalGateDefaultsToNoOp() {
        var tp = new TurnProcessor(ModeController.defaultController());
        assertNotNull(tp.approvalGate());
        assertTrue(tp.approvalGate().approve("test", null));
    }

    @Test void customApprovalGateIsPreserved() {
        ApprovalGate deny = (desc, detail) -> false;
        var tp = new TurnProcessor(ModeController.defaultController(), deny);
        assertSame(deny, tp.approvalGate());
        assertFalse(tp.approvalGate().approve("anything", null));
    }

    // ---- Tool dispatch tests ----

    @Test void executeToolDispatchesToRegisteredTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        var tp = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        ToolCall call = new ToolCall("test.echo", Map.of("input", "hello"));
        ToolResult result = tp.executeTool(session, call, ctx);

        assertTrue(result.success());
        assertEquals("Echo: hello", result.output());
    }

    @Test void executeToolReturnsErrorForUnknownTool() {
        var tp = new TurnProcessor(ModeController.defaultController());
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        ToolCall call = new ToolCall("nonexistent.tool", Map.of());
        ToolResult result = tp.executeTool(session, call, ctx);

        assertFalse(result.success());
        assertEquals(ToolError.NOT_FOUND, result.error().code());
    }

    @Test
    void unknownNamespacedToolAliasIsRejectedAndRecordedInLocalTrace() {
        var tp = new TurnProcessor(ModeController.defaultController());
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        LocalTurnTraceCapture.begin(
                "trc-t60",
                "session-t60",
                1,
                "2026-05-02T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "test");
        try {
            ToolResult result = tp.executeTool(
                    session,
                    new ToolCall("unknown_provider.write_file", Map.of("path", "README.md", "content", "hello")),
                    ctx);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertFalse(result.success());
            assertEquals(ToolError.NOT_FOUND, result.error().code());
            var aliasEvent = trace.events().stream()
                    .filter(event -> "TOOL_ALIAS_DECISION".equals(event.type()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("REJECTED_UNKNOWN_NAMESPACE", aliasEvent.data().get("status"));
            assertEquals("unknown_provider.write_file", aliasEvent.data().get("rawName"));
            assertEquals("talos.write_file", aliasEvent.data().get("canonicalTool"));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test void executeToolWithNullCallReturnsError() {
        var tp = new TurnProcessor(ModeController.defaultController());
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        ToolResult result = tp.executeTool(session, null, ctx);
        assertFalse(result.success());
    }

    @Test void toolRegistryAccessor() {
        ToolRegistry registry = new ToolRegistry();
        var tp = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        assertSame(registry, tp.toolRegistry());
    }

    @Test
    void writeFileMissingContentFailsBeforeApproval(@TempDir Path workspace) {
        AtomicInteger approvals = new AtomicInteger();
        var tp = processorWithFileToolsAndApprovalCounter(approvals);
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);

        ToolResult result = tp.executeTool(session,
                new ToolCall("talos.write_file", Map.of("path", "styles.css")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertTrue(result.errorMessage().contains("content"), result.errorMessage());
        assertTrue(result.errorMessage().contains("No approval was requested"), result.errorMessage());
        assertEquals(0, approvals.get());
        assertFalse(Files.exists(workspace.resolve("styles.css")));
    }

    @Test
    void writeFileMissingPathFailsBeforeApproval(@TempDir Path workspace) {
        AtomicInteger approvals = new AtomicInteger();
        var tp = processorWithFileToolsAndApprovalCounter(approvals);
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);

        ToolResult result = tp.executeTool(session,
                new ToolCall("talos.write_file", Map.of("content", "body { color: red; }")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertTrue(result.errorMessage().contains("path"), result.errorMessage());
        assertTrue(result.errorMessage().contains("No approval was requested"), result.errorMessage());
        assertEquals(0, approvals.get());
    }

    @Test
    void editFileMissingRequiredArgsFailBeforeApproval(@TempDir Path workspace) {
        AtomicInteger approvals = new AtomicInteger();
        var tp = processorWithFileToolsAndApprovalCounter(approvals);
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);

        assertInvalidBeforeApproval(tp, session, ctx, approvals,
                new ToolCall("talos.edit_file", Map.of(
                        "path", "index.html",
                        "old_string", "",
                        "new_string", "replacement")),
                "old_string");
        assertInvalidBeforeApproval(tp, session, ctx, approvals,
                new ToolCall("talos.edit_file", Map.of(
                        "path", "index.html",
                        "old_string", "original")),
                "new_string");
        assertInvalidBeforeApproval(tp, session, ctx, approvals,
                new ToolCall("talos.edit_file", Map.of(
                        "old_string", "original",
                        "new_string", "replacement")),
                "path");
    }

    @Test
    void validWriteFileStillRequestsApproval(@TempDir Path workspace) {
        AtomicInteger approvals = new AtomicInteger();
        var tp = processorWithFileToolsAndApprovalCounter(approvals);
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);

        ToolResult result = tp.executeTool(session,
                new ToolCall("talos.write_file", Map.of(
                        "path", "index.html",
                        "content", "<h1>ok</h1>")), ctx);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, approvals.get());
    }

    @Test
    void forbiddenTargetFromTaskContractFailsBeforeApproval(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<h1>original</h1>");
        AtomicInteger approvals = new AtomicInteger();
        var tp = processorWithFileToolsAndApprovalCounter(approvals);
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);
        String request = "Fix only styles.css. Do not change index.html or scripts.js.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));

        ToolResult result = tp.executeTool(session,
                new ToolCall("talos.write_file", Map.of(
                        "path", "index.html",
                        "content", "<h1>forbidden</h1>")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertTrue(result.errorMessage().contains("forbidden"), result.errorMessage());
        assertTrue(result.errorMessage().contains("index.html"), result.errorMessage());
        assertTrue(result.errorMessage().contains("No approval was requested"), result.errorMessage());
        assertEquals(0, approvals.get());
        assertEquals("<h1>original</h1>", Files.readString(workspace.resolve("index.html")));
    }

    @Test
    void allowedTargetFromScopedContractStillRequestsApproval(@TempDir Path workspace) {
        AtomicInteger approvals = new AtomicInteger();
        var tp = processorWithFileToolsAndApprovalCounter(approvals);
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);
        String request = "Fix only styles.css. Do not change index.html or scripts.js.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));

        ToolResult result = tp.executeTool(session,
                new ToolCall("talos.write_file", Map.of(
                        "path", "styles.css",
                        "content", "body { color: white; }")), ctx);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, approvals.get());
        assertTrue(Files.exists(workspace.resolve("styles.css")));
    }

    @Test
    void exactLiteralWriteUsesRuntimePayloadBeforeApprovalAndWrite(@TempDir Path workspace)
            throws Exception {
        AtomicInteger approvals = new AtomicInteger();
        var tp = processorWithFileToolsAndApprovalCounter(approvals);
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);
        String request = "Edit README.md now using talos.write_file. "
                + "The complete file must contain exactly two lines: "
                + "first line T155 exact literal; second line Line two; no other characters.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));

        ToolResult result = tp.executeTool(session,
                new ToolCall("talos.write_file", Map.of(
                        "path", "README.md",
                        "content", "T155 exact literal\nLine two\n")), ctx);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, approvals.get());
        String written = Files.readString(workspace.resolve("README.md"));
        assertEquals("T155 exact literal\nLine two", written);
        assertEquals(27, written.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(2, written.split("\\R", -1).length);
    }

    @Test
    void deniedExactLiteralWriteShowsCorrectedPayloadAndDoesNotMutate(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("README.md"), "original");
        AtomicInteger approvals = new AtomicInteger();
        List<String> approvalDetails = new ArrayList<>();
        var tp = processorWithFileTools(approvalGate(approvals, approvalDetails, false));
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);
        String request = "Edit README.md now using talos.write_file. "
                + "The complete file must contain exactly two lines: "
                + "first line T155 exact literal; second line Line two; no other characters.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));

        ToolResult result = tp.executeTool(session,
                new ToolCall("talos.write_file", Map.of(
                        "path", "README.md",
                        "content", "T155 exact literal\nLine two\n")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.DENIED, result.error().code());
        assertEquals(1, approvals.get());
        assertEquals("original", Files.readString(workspace.resolve("README.md")));
        assertFalse(approvalDetails.isEmpty());
        assertTrue(approvalDetails.getFirst().contains("T155 exact literal"), approvalDetails.getFirst());
        assertTrue(approvalDetails.getFirst().contains("(27 bytes, 2 lines)"), approvalDetails.getFirst());
        assertFalse(approvalDetails.getFirst().contains("(28 bytes, 3 lines)"), approvalDetails.getFirst());
    }

    @Test
    void expectedTargetScopeRejectsOffTargetWritesBeforeApproval(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "original readme\n");
        Files.writeString(workspace.resolve("notes.md"), "private marker must stay private\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('old sibling');\n");
        AtomicInteger approvals = new AtomicInteger();
        var tp = processorWithFileToolsAndApprovalCounter(approvals);
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);
        String request = "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));

        for (String target : List.of("README.md", "notes.md", "script.js")) {
            ToolResult result = tp.executeTool(session,
                    new ToolCall("talos.write_file", Map.of(
                            "path", target,
                            "content", "off target mutation")), ctx);

            assertFalse(result.success(), target);
            assertEquals(ToolError.INVALID_PARAMS, result.error().code(), target);
            assertTrue(result.errorMessage().contains("outside the current expected target set"),
                    result.errorMessage());
            assertTrue(result.errorMessage().contains("index.html"), result.errorMessage());
            assertTrue(result.errorMessage().contains("styles.css"), result.errorMessage());
            assertTrue(result.errorMessage().contains("scripts.js"), result.errorMessage());
            assertTrue(result.errorMessage().contains("No approval was requested"), result.errorMessage());
        }

        assertEquals(0, approvals.get(), "off-target writes must not reach approval");
        assertEquals("original readme\n", Files.readString(workspace.resolve("README.md")));
        assertEquals("private marker must stay private\n", Files.readString(workspace.resolve("notes.md")));
        assertEquals("console.log('old sibling');\n", Files.readString(workspace.resolve("script.js")));
    }

    @Test
    void expectedTargetScopeRejectsOffTargetEditBeforeApproval(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("script.js"), "console.log('wrong sibling');\n");
        AtomicInteger approvals = new AtomicInteger();
        var tp = processorWithFileToolsAndApprovalCounter(approvals);
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);
        String request = "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));

        ToolResult result = tp.executeTool(session,
                new ToolCall("talos.edit_file", Map.of(
                        "path", "script.js",
                        "old_string", "console.log('wrong sibling');\n",
                        "new_string", "console.log('mutated');\n")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertTrue(result.errorMessage().contains("outside the current expected target set"),
                result.errorMessage());
        assertTrue(result.errorMessage().contains("script.js"), result.errorMessage());
        assertTrue(result.errorMessage().contains("scripts.js"), result.errorMessage());
        assertTrue(result.errorMessage().contains("No approval was requested"), result.errorMessage());
        assertEquals(0, approvals.get());
        assertEquals("console.log('wrong sibling');\n", Files.readString(workspace.resolve("script.js")));
    }

    @Test
    void expectedTargetScopeAllowsExactExpectedTarget(@TempDir Path workspace) {
        AtomicInteger approvals = new AtomicInteger();
        var tp = processorWithFileToolsAndApprovalCounter(approvals);
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);
        String request = "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));

        ToolResult result = tp.executeTool(session,
                new ToolCall("talos.write_file", Map.of(
                        "path", "scripts.js",
                        "content", "console.log('expected target');\n")), ctx);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, approvals.get());
        assertTrue(Files.exists(workspace.resolve("scripts.js")));
    }

    @Test
    void directoryListingContractBlocksContentInspectionTools(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "Hidden project token: ALPHA-742");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        var tp = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);
        String request = "What files are in this folder?";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));

        ToolResult result = tp.executeTool(session,
                new ToolCall("talos.read_file", Map.of("path", "notes.md")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.DENIED, result.error().code());
        assertTrue(result.errorMessage().contains("directory entries"), result.errorMessage());
        assertTrue(result.errorMessage().contains("talos.list_dir"), result.errorMessage());
        assertFalse(result.errorMessage().contains("ALPHA-742"), result.errorMessage());
    }

    @Test void toolReceivesWorkspaceFromSession() {
        ToolRegistry registry = new ToolRegistry();
        // Tool that records the workspace it received
        registry.register(new TalosTool() {
            @Override public String name() { return "test.ws"; }
            @Override public String description() { return "test"; }
            @Override public ToolDescriptor descriptor() { return new ToolDescriptor("test.ws", "test"); }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.ok(ctx.workspace().toString());
            }
        });

        var tp = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        ToolResult result = tp.executeTool(session, new ToolCall("test.ws", Map.of()), ctx);
        assertTrue(result.success());
        assertEquals(WS.toString(), result.output());
    }

    // ---- Test tools ----

    private static class EchoTool implements TalosTool {
        @Override public String name() { return "test.echo"; }
        @Override public String description() { return "Echoes input"; }
        @Override public ToolDescriptor descriptor() { return new ToolDescriptor("test.echo", "Echoes input"); }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
            return ToolResult.ok("Echo: " + call.param("input", "(empty)"));
        }
    }

    private static TurnProcessor processorWithFileToolsAndApprovalCounter(AtomicInteger approvals) {
        return processorWithFileTools(approvalGate(approvals, new ArrayList<>(), true));
    }

    private static TurnProcessor processorWithFileTools(ApprovalGate gate) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        return new TurnProcessor(ModeController.defaultController(), gate, registry);
    }

    private static ApprovalGate approvalGate(
            AtomicInteger approvals,
            List<String> approvalDetails,
            boolean approved
    ) {
        return new ApprovalGate() {
            @Override public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }

            @Override public ApprovalResponse approveFull(String description, String detail) {
                approvals.incrementAndGet();
                approvalDetails.add(detail == null ? "" : detail);
                return approved ? ApprovalResponse.APPROVED : ApprovalResponse.DENIED;
            }
        };
    }

    private static Context contextForWorkspace(Path workspace) {
        return Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
    }

    private static void assertInvalidBeforeApproval(
            TurnProcessor tp,
            Session session,
            Context ctx,
            AtomicInteger approvals,
            ToolCall call,
            String expectedParam
    ) {
        ToolResult result = tp.executeTool(session, call, ctx);

        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertTrue(result.errorMessage().contains(expectedParam), result.errorMessage());
        assertTrue(result.errorMessage().contains("No approval was requested"), result.errorMessage());
        assertEquals(0, approvals.get());
    }

    // ---- Trace capture tests ----

    @Test void traceIsCapturedFromRagLikeMode() throws Exception {
        // Simulate a mode that captures a trace (like RagMode does)
        var modes = new ModeController();
        modes.add(new StubMode("ask", true) {
            @Override public Optional<Result> handle(String raw, Path ws, Context ctx) {
                RetrievalTrace trace = new RetrievalTrace();
                trace.record("Bm25Stage", 1_000_000, 0, 5);
                trace.record("DedupStage", 500_000, 5, 4);
                TurnTraceCapture.capture(trace);
                return Optional.of(new Result.Ok("rag-answer"));
            }
        });
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult r = tp.process(session, "explain X", ctx);
        assertNotNull(r);
        assertNotNull(r.trace(), "Trace should be populated from capture");
        assertEquals(2, r.trace().entries().size());
        assertEquals("Bm25Stage", r.trace().entries().get(0).stageName());
    }

    @Test void traceIsNullForNonRagMode() throws Exception {
        // AskMode doesn't capture a trace → trace should be null
        var modes = new ModeController();
        modes.add(new StubMode("ask", true));
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult r = tp.process(session, "hello", ctx);
        assertNotNull(r);
        assertNull(r.trace(), "Non-RAG modes should produce null trace");
    }

    @Test
    void localTurnTraceIsAttachedToTurnResultWithoutRawPromptOrAnswer() throws Exception {
        var modes = new ModeController();
        modes.add(new StubMode("ask", true) {
            @Override public Optional<Result> handle(String raw, Path ws, Context ctx) {
                return Optional.of(new Result.Ok("Answer mentions SECRET=abc."));
            }
        });
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult result = tp.process(session, "hello SECRET=abc", ctx);

        assertNotNull(result.audit().localTrace());
        LocalTurnTrace trace = result.audit().localTrace();
        assertEquals(2, trace.schemaVersion());
        assertFalse(trace.traceId().isBlank());
        assertTrue(trace.events().stream().anyMatch(event -> "TRACE_STARTED".equals(event.type())));
        assertTrue(trace.events().stream().anyMatch(event -> "MODEL_RESPONSE_RECEIVED".equals(event.type())));
        assertTrue(trace.events().stream().anyMatch(event -> "OUTCOME_RENDERED".equals(event.type())));
        assertFalse(trace.redaction().promptHash().isBlank());
        assertFalse(trace.redaction().assistantHash().isBlank());

        String json = MAPPER.writeValueAsString(trace);
        assertFalse(json.contains("SECRET=abc"), "local trace must not store raw prompt or answer by default");
    }

    @Test
    void localTurnTraceCapturesToolApprovalAndResultEventsWithoutRawWritePayload(@TempDir Path workspace)
            throws Exception {
        AtomicInteger approvals = new AtomicInteger();
        var tp = processorWithFileToolsAndApprovalCounter(approvals);
        var session = new Session(workspace, new Config());
        var ctx = contextForWorkspace(workspace);
        String request = "write index.html";
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "index.html",
                "content", "SECRET=abc\n<h1>ok</h1>"));

        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
        TurnAuditCapture.begin();
        LocalTurnTraceCapture.begin(
                "trc-tool",
                JsonSessionStore.sessionIdFor(workspace),
                1,
                "2026-04-28T12:00:00Z",
                "workspace-hash",
                "auto",
                "ollama",
                "qwen2.5-coder:14b",
                request);

        ToolResult toolResult = tp.executeTool(session, call, ctx);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnAuditCapture.end();

        assertTrue(toolResult.success(), toolResult.errorMessage());
        assertTrue(trace.events().stream().anyMatch(event -> "TOOL_CALL_PARSED".equals(event.type())));
        assertTrue(trace.events().stream().anyMatch(event -> "APPROVAL_REQUIRED".equals(event.type())));
        assertTrue(trace.events().stream().anyMatch(event -> "APPROVAL_GRANTED".equals(event.type())));
        assertTrue(trace.events().stream().anyMatch(event -> "TOOL_EXECUTED".equals(event.type())));

        String json = MAPPER.writeValueAsString(trace);
        assertTrue(json.contains("\"contentHash\""), json);
        assertFalse(json.contains("SECRET=abc"), "write payload must be hashed, not stored raw");
        assertFalse(json.contains("<h1>ok</h1>"), "write payload must be hashed, not stored raw");
    }

    @Test void traceIsClearedBetweenTurns() throws Exception {
        var modes = new ModeController();
        // First turn: RAG-like (captures trace)
        // Second turn: plain (no capture)
        var callCount = new int[]{0};
        modes.add(new StubMode("ask", true) {
            @Override public Optional<Result> handle(String raw, Path ws, Context ctx) {
                callCount[0]++;
                if (callCount[0] == 1) {
                    RetrievalTrace trace = new RetrievalTrace();
                    trace.record("Bm25Stage", 100, 0, 3);
                    TurnTraceCapture.capture(trace);
                }
                // Second call: no capture → should see null trace
                return Optional.of(new Result.Ok("answer-" + callCount[0]));
            }
        });
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult r1 = tp.process(session, "rag question", ctx);
        assertNotNull(r1.trace());

        TurnResult r2 = tp.process(session, "plain question", ctx);
        assertNull(r2.trace(), "Trace from previous turn must not leak");
    }

    // ---- Memory listener integration with streamed results ----

    @Test void memoryListenerRecordsStreamedResults() throws Exception {
        SessionMemory memory = new SessionMemory();
        ConversationManager cm = new ConversationManager(memory, new TokenBudget());

        var modes = new ModeController();
        modes.add(new StubMode("ask", true) {
            @Override public Optional<Result> handle(String raw, Path ws, Context ctx) {
                return Optional.of(new Result.Streamed("streamed answer body", "\n[Sources]"));
            }
        });
        var tp = new TurnProcessor(modes);
        tp.addListener(new MemoryUpdateListener(cm));

        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        tp.process(session, "explain something", ctx);

        assertEquals(1, cm.turnCount());
        var history = cm.buildHistory();
        assertEquals(2, history.size());
        assertEquals("explain something", history.get(0).content());
        assertEquals("streamed answer body", history.get(1).content());
    }

    // ---- Stub mode for isolated testing ----

    private static class StubMode implements dev.talos.cli.modes.Mode {
        private final String modeName;
        private final boolean handles;

        StubMode(String name, boolean handles) {
            this.modeName = name;
            this.handles = handles;
        }

        @Override public String name() { return modeName; }
        @Override public boolean canHandle(String raw) { return handles; }
        @Override public Optional<Result> handle(String raw, Path ws, Context ctx) throws Exception {
            return Optional.of(new Result.Ok("stub-answer"));
        }
    }
}

