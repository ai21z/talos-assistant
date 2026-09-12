package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.QuotedHtmlProviderFixture;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.checkpoint.CheckpointCaptureResult;
import dev.talos.runtime.checkpoint.CheckpointRestoreResult;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.checkpoint.CheckpointStore;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.TokenChunk;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.impl.FileEditTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses the real stream projection, receipt, parser, authority and file edit, without a provider. */
class QuotedHtmlNativeProposalAuthorityTest {
    private static final String NAME = "talos.edit_file";
    private static final String OLD =
            "document.querySelector(\"#calc\").addEventListener(\"click\", () => {";
    private static final String NEW =
            "document.querySelector(\"#calculate\").addEventListener(\"click\", () => {";
    private static final String BEFORE = "// retain this header\n" + OLD + "\n  calculate();\n});\n";
    private static final String AFTER = "// retain this header\n" + NEW + "\n  calculate();\n});\n";
    // Exact decoded N2-04 prose and native argument values; no dependency on ignored evidence files.
    private static final String PROSE = "Verified:\n"
            + "- `index.html` line 6: `<button id=\"calculate\">Calculate</button>`\n"
            + "- `script.js` line 2: `document.querySelector(\"#calc\")`\n\n"
            + "Fix:\n- Replace `\"#calc\"` with `\"#calculate\"` in `script.js`.\n\n";

    @TempDir Path workspace;

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        ProviderResponseCapture.clear();
        TurnTaskContractCapture.clear();
    }

    @Test
    void quotedHtmlNativeEditReachesApprovalAndChangesOnlyExactBytesOnce() throws Exception {
        begin("approved");
        LlmClient.StreamResult response = response(PROSE, TokenChunk.eos("tool_calls"));
        Harness harness = harness(response, proposal(NEW), ApprovalResponse.APPROVED);
        ToolCallParseStage.ParsedCalls parsed = new ToolCallParseStage().parse(harness.state(), 1);
        ToolCall call = parsed.calls().getFirst();
        var authority = LocalTurnTraceCapture.authorizeParsedToolCallForExecution(
                "tool_loop", call, parsed.lineage(0));

        assertEquals(PROSE, response.text());
        assertFalse(response.normalized());
        assertTrue(response.provenance().providerTerminalEligible());
        assertTrue(authority.authorized());
        ToolResult applied = harness.processor().executeToolAfterParsed(
                harness.state().toolSession, call, harness.state().ctx, authority.permit());
        ToolResult reused = harness.processor().executeToolAfterParsed(
                harness.state().toolSession, call, harness.state().ctx, authority.permit());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertTrue(applied.success(), applied.toString());
        assertFalse(reused.success());
        assertEquals(ToolError.INVALID_AUTHORITY, reused.error().code());
        assertEquals(1, harness.approvals().get());
        assertEquals(1, harness.checkpoints().get());
        assertEquals(1, harness.invocations().get());
        assertEquals(AFTER, Files.readString(workspace.resolve("script.js")));
        assertEquals("untouched\n", Files.readString(workspace.resolve("scripts.js")));
        assertEquals(1, events(trace, "TOOL_EXECUTED"));
    }

    @Test
    void quotedHtmlNativeEditDenialHasNoCheckpointOrMutation() throws Exception {
        begin("denied");
        LlmClient.StreamResult response = response(PROSE, TokenChunk.eos("tool_calls"));
        Harness harness = harness(response, proposal(NEW), ApprovalResponse.DENIED);
        var outcome = new ToolCallExecutionStage(harness.processor(), null, false).execute(
                harness.state(), new ToolCallParseStage().parse(harness.state(), 1));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(1, harness.approvals().get(), "the intact proposal must reach normal approval");
        assertEquals(0, harness.checkpoints().get());
        assertEquals(0, harness.invocations().get());
        assertEquals(0, outcome.successesThisIteration());
        assertEquals(BEFORE, Files.readString(workspace.resolve("script.js")));
        assertEquals(0, events(trace, "TOOL_EXECUTED"));
    }

    @Test
    void preservedLiteralDoesNotAuthorizeAlteredNativeArguments() throws Exception {
        begin("altered-arguments");
        LlmClient.StreamResult response = response(PROSE, TokenChunk.eos("tool_calls"));
        Harness harness = harness(response, proposal("altered JSON argument"), ApprovalResponse.APPROVED);
        var outcome = new ToolCallExecutionStage(harness.processor(), null, false).execute(
                harness.state(), new ToolCallParseStage().parse(harness.state(), 1));

        assertTrue(response.provenance().providerTerminalEligible());
        assertEquals(0, harness.approvals().get());
        assertEquals(0, harness.invocations().get());
        assertEquals(1, outcome.failuresThisIteration());
        assertEquals(BEFORE, Files.readString(workspace.resolve("script.js")));
    }

    @Test
    void quotedLiteralCannotHideNormalizationOrNonEosTerminal() throws Exception {
        for (String prose : List.of(
                "<think>hidden</think>" + PROSE,
                "<button>outside " + PROSE + " tail</button>")) {
            LocalTurnTraceCapture.clear();
            begin("unsafe");
            LlmClient.StreamResult response = response(prose, TokenChunk.eos("tool_calls"));
            assertTrue(response.normalized());
            assertBlocked(response);
        }
        for (TokenChunk terminal : List.of(TokenChunk.transportEof("tool_calls"), TokenChunk.eos("length"))) {
            LocalTurnTraceCapture.clear();
            begin("non-eos");
            assertBlocked(response(PROSE, terminal));
        }
    }

    private void assertBlocked(LlmClient.StreamResult response) throws Exception {
        Harness harness = harness(response, proposal(NEW), ApprovalResponse.APPROVED);
        new ToolCallExecutionStage(harness.processor(), null, false).execute(
                harness.state(), new ToolCallParseStage().parse(harness.state(), 1));
        assertFalse(response.provenance().providerTerminalEligible());
        assertEquals(0, harness.approvals().get());
        assertEquals(0, harness.checkpoints().get());
        assertEquals(0, harness.invocations().get());
        assertEquals(BEFORE, Files.readString(workspace.resolve("script.js")));
    }

    private Harness harness(LlmClient.StreamResult response, NativeToolCall proposed,
                            ApprovalResponse decision) throws Exception {
        Files.writeString(workspace.resolve("script.js"), BEFORE);
        Files.writeString(workspace.resolve("scripts.js"), "untouched\n");
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger checkpoints = new AtomicInteger();
        AtomicInteger invocations = new AtomicInteger();
        Config config = config();
        config.data.put("checkpoint", Map.of("enabled", true, "fail_closed", true,
                "max_file_bytes", 1_000_000, "max_turn_bytes", 2_000_000));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TalosTool() {
            private final FileEditTool delegate = new FileEditTool();
            @Override public String name() { return delegate.name(); }
            @Override public String description() { return delegate.description(); }
            @Override public ToolDescriptor descriptor() { return delegate.descriptor(); }
            @Override public ToolResult execute(ToolCall call, ToolContext context) {
                invocations.incrementAndGet();
                return delegate.execute(call, context);
            }
        });
        ApprovalGate gate = (description, detail) -> {
            approvals.incrementAndGet();
            return decision.isApproved();
        };
        CheckpointStore store = new CheckpointStore() {
            @Override public CheckpointCaptureResult captureBeforeMutation(
                    Path root, Config cfg, ToolCall call, String traceId, int turn) {
                return CheckpointCaptureResult.captured("quoted-" + checkpoints.incrementAndGet(), 0);
            }
            @Override public CheckpointRestoreResult restore(Path root, String id) {
                return CheckpointRestoreResult.failure(id, "not used");
            }
        };
        TurnProcessor processor = new TurnProcessor(null, gate, registry,
                ApprovalPolicy.ALWAYS_ASK, new CheckpointService(store));
        Context context = Context.builder(config).sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry).build();
        LoopState state = new LoopState(response.text(), List.of(proposed), response.provenance(),
                new ArrayList<>(List.of(ChatMessage.user("Fix the selector in script.js."))),
                workspace, context, new Session(workspace, config), 5, 0);
        return new Harness(processor, state, approvals, checkpoints, invocations);
    }

    private static NativeToolCall proposal(String replacement) {
        return new NativeToolCall("captured-edit", NAME,
                Map.of("path", "script.js", "old_string", OLD, "new_string", replacement));
    }

    private static LlmClient.StreamResult response(String prose, TokenChunk terminal) {
        return QuotedHtmlProviderFixture.response(prose, proposal(NEW), terminal);
    }

    private static Config config() {
        Config config = new Config(null);
        config.data.put("llm", Map.of("transport", "engine", "default_backend", "test"));
        config.data.put("limits", Map.of("response_max_chars", 10_000,
                "llm_timeout_ms", 5_000L, "llm_idle_ms", 0L));
        return config;
    }

    private static void begin(String id) {
        LocalTurnTraceCapture.begin("quoted-html-" + id, "quoted-html-session", 1,
                "2026-09-07T00:00:00Z", "quoted-html-workspace", "test", "test", "model", "edit selector");
    }

    private static long events(LocalTurnTrace trace, String type) {
        return trace.events().stream().filter(event -> type.equals(event.type())).count();
    }

    private record Harness(TurnProcessor processor, LoopState state, AtomicInteger approvals,
                           AtomicInteger checkpoints, AtomicInteger invocations) {}

}
