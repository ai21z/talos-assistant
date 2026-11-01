package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.ui.FinalAnswerStatusRenderer;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The completion classification and the whole-request coverage follow the scope of the proof through the real
 * delivery path (MICA invariant I3, decision D5, P4). UnifiedAssistantMode, TurnProcessor, approval, verification,
 * trace capture and memory publication are real. The provider is scripted.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MicaProofScopeBoundaryTest {
    private static final String EXACT = "Create todo.md containing exactly: Review the local setup. "
            + "Do not change any other file.";
    private static final String WRITE_TODO = "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"todo.md\","
            + "\"content\":\"Review the local setup.\"}}";
    private static final String DECISIONS = "# Decisions\n\nDecision 001 keeps all storage local. Decision 002 requires "
            + "explicit approval before destructive maintenance. Decision 003 records verification evidence after "
            + "changes.\n";
    private static final String APPEND = "Append Decision 004 to docs/decisions.md saying health checks remain "
            + "read-only. Preserve Decisions 001 through 003 and change no code.";
    private static final String READ_DECISIONS =
            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"docs/decisions.md\"}}";
    private static final String WRITE_DECISIONS = "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":"
            + "\"docs/decisions.md\",\"content\":\"# Decisions\\n\\nDecision 001 keeps all storage local. Decision 002 "
            + "requires explicit approval before destructive maintenance. Decision 003 records verification evidence "
            + "after changes.\\n\\nDecision 004: Health checks remain read-only.\\n\"}}";
    private static final String MODERN ="Now edit index.html so it looks like a modern landing page.";
    private static final String WRITE_PAGE = "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"index.html\","
            + "\"content\":\"<!DOCTYPE html>\\n<html>\\n  <head><link rel=\\\"stylesheet\\\" href=\\\"style.css\\\">"
            + "</head>\\n  <body><main id=\\\"hero\\\"><a class=\\\"cta-button\\\">Listen now</a></main>"
            + "<script src=\\\"script.js\\\"></script></body>\\n</html>\\n\"}}";
    @TempDir Path temp;
    private Path workspace;
    private String priorHome;

    @BeforeEach
    void isolate() throws Exception {
        workspace = Files.createDirectory(temp.resolve("workspace"));
        priorHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectory(temp.resolve("home")).toString());
    }

    @AfterEach
    void close() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        TurnSourceEvidenceCapture.clear();
        TurnTraceCapture.clear();
        dev.talos.spi.types.ProviderResponseCapture.clear();
        dev.talos.spi.types.PromptDebugCapture.clear();
        dev.talos.cli.prompt.LastPromptCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        if (priorHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", priorHome);
    }

    @Test
    void anExactRequestObligationIsVerifiedAndItsCoverageIsAssessed() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/decisions.md"), DECISIONS);
        try (var conversation = new Conversation()) {
            var run = conversation.run(APPEND, READ_DECISIONS, WRITE_DECISIONS, "Updated docs/decisions.md.");

            assertEquals(DECISIONS + "\nDecision 004: Health checks remain read-only.\n",
                    Files.readString(workspace.resolve("docs/decisions.md")), run.answer());
            var trace = run.turn().audit().localTrace();
            assertEquals("COMPLETED_VERIFIED", trace.outcome().classification(), run.answer());
            assertTrue(FinalAnswerStatusRenderer.lines(trace)
                    .contains("Whole-request coverage: covered by an exact request check"),
                    FinalAnswerStatusRenderer.lines(trace).toString());
        }
    }

    @Test
    void anExactContentRequestIsVerifiedButOnlyAnObligationReceiptAssessesCoverage() throws Exception {
        try (var conversation = new Conversation()) {
            var run = conversation.run(EXACT, WRITE_TODO, "Created todo.md.");

            assertEquals("Review the local setup.", Files.readString(workspace.resolve("todo.md")));
            var trace = run.turn().audit().localTrace();
            assertEquals("COMPLETED_VERIFIED", trace.outcome().classification(), run.answer());
            assertTrue(FinalAnswerStatusRenderer.lines(trace).contains("Whole-request coverage: not assessed"),
                    FinalAnswerStatusRenderer.lines(trace).toString());
        }
    }

    @Test
    void aStructuralPassIsNotVerifiedAndItsCoverageIsNotAssessed() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="style.css"></head>
                  <body><main id="hero"><a class="cta-button">Listen</a></main><script src="script.js"></script></body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), "#hero {}\n.cta-button {}\n");
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.cta-button');");
        try (var conversation = new Conversation()) {
            var run = conversation.run(MODERN, WRITE_PAGE, "Updated index.html.");

            var trace = run.turn().audit().localTrace();
            assertEquals("PASSED", trace.outcome().verificationStatus(), run.answer());
            assertEquals("COMPLETED_UNVERIFIED", trace.outcome().classification(), run.answer());
            assertTrue(run.answer().contains("[Checks passed - "), run.answer());
            assertTrue(FinalAnswerStatusRenderer.lines(trace).contains("Whole-request coverage: not assessed"),
                    FinalAnswerStatusRenderer.lines(trace).toString());
        }
    }

    private record Run(TurnResult turn, String answer) {}

    private final class Conversation implements AutoCloseable {
        private final Config config = new Config(null);
        private final SessionMemory memory = new SessionMemory();
        private final ToolRegistry registry = new ToolRegistry();
        private final AtomicInteger approvals = new AtomicInteger();
        private final Session session;
        private final TurnProcessor processor;

        private Conversation() {
            registry.register(new FileWriteTool());
            registry.register(new FileEditTool());
            registry.register(new ReadFileTool());
            var gate = new ApprovalGate() {
                @Override public boolean approve(String description, String detail) {
                    return approveFull(description, detail).isApproved();
                }
                @Override public ApprovalResponse approveFull(String description, String detail) {
                    approvals.incrementAndGet();
                    return ApprovalResponse.APPROVED;
                }
            };
            var mode = new UnifiedAssistantMode();
            processor = new TurnProcessor((request, root, context) -> mode.handle(request, root, (Context) context),
                    gate, registry, ApprovalPolicy.ALWAYS_ASK);
            processor.addListener(new MemoryUpdateListener(
                    new ConversationManager(memory, TokenBudget.fromConfig(config)), null, memory));
            processor.addListener(new dev.talos.cli.repl.ActiveTaskContextUpdateListener(memory));
            session = new Session(workspace, config, memory);
        }

        private Run run(String request, String... responses) throws Exception {
            var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(
                    Arrays.stream(responses).map(response -> new LlmClient.StreamResult(response, List.of())).toList(),
                    16_384);
            try (var client = fixture.client()) {
                client.setToolSpecs(registry.descriptors().stream()
                        .map(tool -> new ToolSpec(tool.name(), tool.description(), tool.parametersSchema())).toList());
                var context = Context.builder(config).llm(client).memory(memory).toolRegistry(registry)
                        .sandbox(new Sandbox(workspace, Map.of()))
                        .toolCallLoop(new ToolCallLoop(processor, 4)).build();
                var turn = processor.process(session, request, context);
                String answer = turn.result() instanceof Result.Ok ok ? ok.text
                        : turn.result() instanceof Result.Streamed streamed ? streamed.fullText
                        : turn.result().toString();
                return new Run(turn, answer);
            }
        }

        @Override public void close() { session.close(); }
    }
}
