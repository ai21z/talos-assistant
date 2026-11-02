package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.context.ActiveTaskContext;
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
 * The change offer survives the real delivery path (MICA decision D6, P3b). UnifiedAssistantMode, TurnProcessor,
 * approval, memory publication and the active task listener are real. The provider is scripted, and the verified
 * site context after the first turn is seeded.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MicaChangeOfferBoundaryTest {
    private static final String T11 = "I dont like it ... a modern dark synthwave landing page is more appropriate "
            + "than this. Can you help me?";
    private static final List<String> SITE = List.of("index.html", "style.css", "script.js");
    private static final String OFFER = "Reply yes to let Talos apply this change to index.html, style.css and "
            + "script.js. File changes still follow your approval settings.";
    private static final String WRITE = "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"index.html\","
            + "\"content\":\"<main class=\\\"synthwave\\\">Dark</main>\\n\"}}";
    @TempDir Path temp;
    private Path workspace;
    private String priorHome;

    @BeforeEach
    void isolate() throws Exception {
        workspace = Files.createDirectory(temp.resolve("workspace"));
        priorHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectory(temp.resolve("home")).toString());
        Files.writeString(workspace.resolve("index.html"), "<main>Light</main>\n");
        Files.writeString(workspace.resolve("style.css"), "body { color: black; }\n");
        Files.writeString(workspace.resolve("script.js"), "void 0;\n");
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
    void theDeliveredOfferIsBoundAndANextTurnYesAppliesItUnderApproval() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            var first = conversation.run("What is in index.html?", "It is a light page.");
            conversation.memory.setActiveTaskContext(ActiveTaskContext.verifiedMutation(
                    first.turn().turnNumber(), "trc-site", SITE, "VERIFIED"));

            var offered = conversation.run(T11, "A darker palette would suit a synthwave page.");
            assertTrue(offered.answer().stripTrailing().endsWith("\n\n" + OFFER), offered.answer());
            assertTrue(conversation.memory.activeTaskContext().hasBoundChangeOffer(),
                    conversation.memory.activeTaskContext().toString());
            assertEquals(0, conversation.approvals.get());
            assertEquals("<main>Light</main>\n", Files.readString(workspace.resolve("index.html")));

            var accepted = conversation.run("yes", WRITE, "Applied the dark synthwave design to index.html.");
            assertEquals(1, conversation.approvals.get(), accepted.answer());
            assertEquals("<main class=\"synthwave\">Dark</main>\n", Files.readString(workspace.resolve("index.html")),
                    accepted.answer());
        }
    }

    @Test
    void aYesAfterAnInterveningTurnAppliesNothing() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            var first = conversation.run("What is in index.html?", "It is a light page.");
            conversation.memory.setActiveTaskContext(ActiveTaskContext.verifiedMutation(
                    first.turn().turnNumber(), "trc-site", SITE, "VERIFIED"));
            conversation.run(T11, "A darker palette would suit a synthwave page.");
            conversation.run("What colors would you use?", "Magenta and cyan.");

            var late = conversation.run("yes", WRITE, "Applied.");
            assertEquals(0, conversation.approvals.get(), late.answer());
            assertEquals("<main>Light</main>\n", Files.readString(workspace.resolve("index.html")));
        }
    }

    private record Run(TurnResult turn, String answer) {}

    private final class Conversation implements AutoCloseable {
        private final Config config = new Config(null);
        private final SessionMemory memory = new SessionMemory();
        private final ToolRegistry registry = new ToolRegistry();
        private final AtomicInteger approvals = new AtomicInteger();
        private final List<ApprovalResponse> decisions;
        private final Session session;
        private final TurnProcessor processor;

        private Conversation(ApprovalResponse... decisions) {
            this.decisions = List.of(decisions);
            registry.register(new FileWriteTool());
            registry.register(new FileEditTool());
            registry.register(new ReadFileTool());
            var gate = new ApprovalGate() {
                @Override public boolean approve(String description, String detail) {
                    return approveFull(description, detail).isApproved();
                }
                @Override public ApprovalResponse approveFull(String description, String detail) {
                    int index = approvals.getAndIncrement();
                    return index < Conversation.this.decisions.size()
                            ? Conversation.this.decisions.get(index) : ApprovalResponse.DENIED;
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
