package dev.talos.cli.modes;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.RenderEngine;
import dev.talos.cli.repl.slash.ExplainLastTurnCommand;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.rag.RagService;
import dev.talos.core.security.Redactor;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.JsonTurnLogAppender;
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnTraceCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.command.RunCommandTool;
import dev.talos.runtime.toolcall.DocumentComparisonTool;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ToolSpec;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** One real offline status turn with authored prior draft history, not continuous installed acceptance. */
@Timeout(20)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MicaFinalAnswerParityTest {
    private static final String SAVE_REQUEST = "I want to write and save a txt file with a story "
            + "of a woman in medieval times with one short thumb";
    private static final String DRAFT = "Elara, a blacksmith with one short thumb, forged a lock "
            + "that saved her medieval village.";
    private static final String STATUS_REQUEST = "have you saved the story ?";
    // Provider prose is scripted. No task type, selected verdict or execution evidence is scripted.
    private static final String CAPABILITY_DENIAL = "I can help you write the story, but I cannot directly "
            + "save files to your workspace. You can copy the text and paste it into a `.txt` file "
            + "using a text editor of your choice. If you need further assistance with the story "
            + "or any other task, feel free to let me know!";

    @TempDir Path temp;

    @Test
    void saveStatusFollowUpDisplaysTheSameSelectedAnswerThatIsPersistedAndShownByLast() throws Exception {
        var observed = run("save-status", STATUS_REQUEST, CAPABILITY_DENIAL, true);

        assertEquals(normalize(observed.persisted()), normalize(observed.terminalAnswer()),
                () -> "MICA-W03 answer parity failed after real UnifiedAssistantMode/RenderEngine execution."
                        + "\nRaw provider text: " + CAPABILITY_DENIAL
                        + "\nSelected text: " + observed.selected()
                        + "\nPersisted/reloaded text: " + observed.persisted()
                        + "\nTerminal answer: " + observed.terminalAnswer()
                        + "\n/last: " + observed.last());
    }

    @Test
    void unchangedProviderAnswerHasParityAcrossTheSameBoundariesWithoutFragmentPresentation() throws Exception {
        String answer = "Hello. How can I help?";
        var observed = run("unchanged-stream", "hello", answer, false);

        assertFalse(observed.streamed(), "The selected answer must be presented only as a complete result");
        assertEquals(normalize(answer), normalize(observed.selected()));
        assertEquals(normalize(answer), normalize(observed.terminalAnswer()));
        assertEquals(normalize(observed.persisted()), normalize(observed.terminalAnswer()));
    }

    private Observed run(String label, String request, String providerText, boolean withDraft) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve(label + "-workspace"));
        Path isolatedHome = Files.createDirectory(temp.resolve(label + "-home"));
        Path storage = isolatedHome.resolve(".talos/sessions");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());
        RenderEngine renderer = null;
        try {
            var config = new Config();
            var rag = new RagService(config);
            var registry = ordinaryRegistry(rag);
            var memory = new SessionMemory();
            if (withDraft) memory.update(SAVE_REQUEST, DRAFT);
            var recorded = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(
                    List.of(new LlmClient.StreamResult(providerText, List.of(), "stop")), 16_384);
            String sessionId = JsonSessionStore.newSessionInstanceId(workspace, Instant.now());
            var bytes = new ByteArrayOutputStream();
            try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
                 LlmClient llm = recorded.client();
                 var store = new JsonSessionStore(storage);
                 var writer = store.acquireSessionWriter(sessionId);
                 var session = new Session(workspace, config, memory)) {
                // The registry and renderer are concrete production objects. The mode selects
                // its own current-turn capability surface from the ordinary registry.
                llm.setToolSpecs(registry.descriptors().stream()
                        .map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema())).toList());
                renderer = new RenderEngine(config, new Redactor(), out, false);
                var mode = new UnifiedAssistantMode();
                var processor = new TurnProcessor(
                        (input, root, turnContext) -> mode.handle(input, root, (Context) turnContext),
                        new NoOpApprovalGate(), registry);
                var context = Context.builder(config)
                        .llm(llm).rag(rag).memory(memory).toolRegistry(registry)
                        .sandbox(new Sandbox(workspace, Map.of()))
                        .toolCallLoop(new ToolCallLoop(processor, 3))
                        .streamSink(new ToolCallStreamFilter(renderer.answerStreamSink(out::print)))
                        .build();
                processor.addListener(new JsonTurnLogAppender(writer, sessionId));

                var turn = processor.process(session, request, context);
                assertNotNull(turn);
                assertEquals(0, bytes.size(), "Provider fragments and selected text must remain private before final rendering");
                assertInstanceOf(Result.Ok.class, turn.result(), "Modes must return the complete selected answer");
                boolean streamed = turn.result() instanceof Result.Streamed;
                String selected = switch (turn.result()) {
                    case Result.Streamed s -> s.fullText + s.suffix;
                    case Result.Ok ok -> ok.text;
                    default -> throw new AssertionError("A real answer result was required: " + turn.result());
                };
                assertNotNull(selected);
                assertFalse(selected.isBlank());
                assertTrue(turn.audit().toolCalls().isEmpty(), "The scripted response contains no tool calls");
                assertEquals(1, recorded.requests().size(), "No retry or unplanned provider call is expected");
                List<ChatMessage> dispatched = recorded.requests().getFirst().messages;
                assertEquals(request, dispatched.getLast().content(), "The original follow-up must be dispatched");
                if (withDraft) {
                    int priorUser = messageIndex(dispatched, "user", SAVE_REQUEST);
                    int priorAssistant = messageIndex(dispatched, "assistant", DRAFT);
                    assertTrue(priorUser >= 0 && priorAssistant > priorUser
                                    && priorAssistant < dispatched.size() - 1,
                            "The save request and no-tool draft must precede the status question in actual provider input");
                }

                renderer.render(turn.result());
                String terminalAnswer = answerBody(bytes.toString(StandardCharsets.UTF_8));
                assertFalse(terminalAnswer.isBlank(), "The captured terminal must contain an answer pane");
                var written = store.loadTurns(sessionId);
                assertEquals(1, written.size());
                var persisted = written.getFirst();
                assertEquals(request, persisted.userInput());
                assertEquals(MemoryUpdateListener.assistantTextForPersistence(selected, request),
                        persisted.assistantText(), "Persistence must retain the selected text after normal commit shaping");
                assertFalse(persisted.assistantText().isBlank());
                assertTrue(persisted.toolCalls().isEmpty());

                try (var reopened = new JsonSessionStore(storage)) {
                    var reloaded = reopened.loadTurns(sessionId);
                    assertEquals(written, reloaded, "A fresh store must reload the same durable turn");
                    assertFalse(persisted.traceId().isBlank());
                    var trace = reopened.loadTrace(sessionId, persisted.traceId()).orElseThrow();
                    assertEquals(turn.audit().localTrace().traceId(), trace.traceId());
                    assertEquals(turn.turnNumber(), trace.turnNumber());
                    String last = assertInstanceOf(Result.TrustedInfo.class,
                            new ExplainLastTurnCommand(workspace, reopened, null, sessionId)
                                    .execute("", context)).text;
                    assertTrue(last.contains("Assistant Preview\n  " + preview(persisted.assistantText())), last);
                    // Exercise actual command presentation separately so /last cannot contaminate
                    // the answer bytes being compared in the regression.
                    bytes.reset();
                    renderer.renderCommand("last", new Result.TrustedInfo(last));
                    assertTrue(normalize(stripAnsi(bytes.toString(StandardCharsets.UTF_8)))
                            .contains(normalize(preview(persisted.assistantText()))));
                    try (var contents = Files.list(workspace)) {
                        assertEquals(0, contents.count(), "A status follow-up with no tool calls must not create a story");
                    }
                    System.out.println("MICA_W03_OBSERVATION " + new ObjectMapper().writeValueAsString(Map.of(
                            "case", label,
                            "scope", withDraft ? "one real status turn with authored prior draft history"
                                    : "one real unchanged provider control turn with buffered presentation",
                            "streamed", streamed,
                            "rawProvider", providerText,
                            "selected", selected,
                            "visible", terminalAnswer,
                            "saved", persisted.assistantText(),
                            "last", last,
                            "trace", Map.of(
                                    "traceId", trace.traceId(),
                                    "turnNumber", trace.turnNumber(),
                                    "taskContract", trace.taskContract(),
                                    "evidenceObligation", trace.promptAudit().evidenceObligation(),
                                    "outcome", trace.outcome()))));
                    return new Observed(selected, terminalAnswer, persisted.assistantText(), last, streamed);
                }
            }
        } finally {
            if (renderer != null) renderer.shutdown();
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

    private static ToolRegistry ordinaryRegistry(RagService rag) {
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new DocumentComparisonTool());
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        registry.register(new BatchWorkspaceApplyTool());
        registry.register(new MakeDirectoryTool());
        registry.register(new MovePathTool());
        registry.register(new CopyPathTool());
        registry.register(new RenamePathTool());
        registry.register(new DeletePathTool());
        registry.register(new RunCommandTool());
        registry.register(new GrepTool());
        registry.register(new ListDirTool());
        registry.register(new RetrieveTool(rag));
        return registry;
    }

    private static int messageIndex(List<ChatMessage> messages, String role, String content) {
        for (int i = 0; i < messages.size(); i++) {
            if (role.equals(messages.get(i).role()) && content.equals(messages.get(i).content())) return i;
        }
        return -1;
    }

    private static String answerBody(String terminal) {
        return stripAnsi(terminal).lines()
                .filter(line -> line.startsWith("  │ ") || line.startsWith("  | "))
                .map(line -> line.substring(4))
                .collect(Collectors.joining("\n"));
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static String preview(String text) {
        String oneLine = text.replace('\r', ' ').replace('\n', ' ').strip();
        return oneLine.length() <= 240 ? oneLine : oneLine.substring(0, 237) + "...";
    }

    private record Observed(String selected, String terminalAnswer, String persisted, String last, boolean streamed) {}
}
