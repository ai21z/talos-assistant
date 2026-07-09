package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.DebugLevel;
import dev.talos.runtime.SessionMemory;
import dev.talos.cli.repl.SessionState;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.context.ChangeSummaryContext;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.tools.ToolRegistry;
import dev.talos.runtime.command.RunCommandTool;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AssistantTurnExecutor} - the shared LLM turn execution
 * logic used by AskMode and RagMode.
 *
 * <p>Uses PLACEHOLDER transport (default LlmClient) for deterministic,
 * no-network-required tests.
 */
@DisplayName("AssistantTurnExecutor")
class AssistantTurnExecutorTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    private static Context scriptedContext(String... responses) {
        return Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(responses)))
                .build();
    }

    private static int countOccurrences(String text, String needle) {
        if (text == null || text.isEmpty() || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static Config documentExtractionEnabled(String family) {
        Config cfg = new Config(null);
        java.util.Map<String, Object> documentExtraction = new java.util.LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        java.util.Map<String, Object> familyCfg = new java.util.LinkedHashMap<>();
        familyCfg.put("enabled", Boolean.TRUE);
        documentExtraction.put(family, familyCfg);
        cfg.data.put("document_extraction", documentExtraction);
        return cfg;
    }

    private static void writeDocxFixture(Path path, String text) throws Exception {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            document.createParagraph().createRun().setText(text);
            try (var out = Files.newOutputStream(path)) {
                document.write(out);
            }
        }
    }

    private static void writePassingBmiFixture(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                <head>
                  <title>BMI Calculator</title>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <main class="app">
                    <h1>BMI Calculator</h1>
                    <form id="bmi-form">
                      <label>Height <input id="height" name="height" type="number"></label>
                      <label>Weight <input id="weight" name="weight" type="number"></label>
                      <button id="calculate" type="submit">Calculate</button>
                    </form>
                    <output id="result"></output>
                  </main>
                  <script src="scripts.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                body { font-family: system-ui; }
                .app { max-width: 36rem; margin: 2rem auto; }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                const form = document.getElementById('bmi-form');
                const result = document.getElementById('result');
                form.addEventListener('submit', event => {
                  event.preventDefault();
                  const height = Number(document.getElementById('height').value) / 100;
                  const weight = Number(document.getElementById('weight').value);
                  const bmi = weight / (height * height);
                  result.textContent = `BMI: ${bmi.toFixed(1)}`;
                });
                """);
    }

    private static SessionState sessionWithDebugLevel(DebugLevel level) {
        return new SessionState() {
            @Override public int getK() { return 8; }
            @Override public void setK(int k) { }
            @Override public boolean isDebug() { return level != null && level.enabled(); }
            @Override public void setDebug(boolean on) { }
            @Override public DebugLevel getDebugLevel() { return level == null ? DebugLevel.OFF : level; }
            @Override public void setDebugLevel(DebugLevel ignored) { }
        };
    }

    @Test
    @DisplayName("records task contract and phase in active turn audit")
    void recordsPolicyTraceInActiveTurnAudit() {
        var ctx = scriptedContext("done");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("system"),
                ChatMessage.user("Create index.html")));

        TurnAuditCapture.begin();
        try {
            AssistantTurnExecutor.execute(messages, WS, ctx, new AssistantTurnExecutor.Options());
            var audit = TurnAuditCapture.end();

            assertEquals("FILE_CREATE", audit.policyTrace().taskType());
            assertTrue(audit.policyTrace().mutationAllowed());
            assertTrue(audit.policyTrace().verificationRequired());
            assertEquals("APPLY", audit.policyTrace().initialPhase());
        } finally {
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        }
    }

    @Test
    void policyTraceUsesWorkspaceReconciledStaticWebTargets(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("scripts.js"), "console.log('existing');\n");
        Files.writeString(workspace.resolve("styles.css"), "body { margin: 0; }\n");
        var ctx = scriptedContext("done");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("system"),
                ChatMessage.user("Create a modern synthwave website here with CSS styling and JavaScript interaction.")));

        TurnAuditCapture.begin();
        try {
            AssistantTurnExecutor.execute(messages, workspace, ctx, new AssistantTurnExecutor.Options());
            var audit = TurnAuditCapture.end();

            assertEquals(List.of("index.html", "scripts.js", "styles.css"),
                    audit.policyTrace().expectedTargets());
        } finally {
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        }
    }

    @Test
    void directoryListingDoesNotTriggerPrimaryFileInspectionRetry(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Directory listing fixture.\n");
        Files.writeString(workspace.resolve("index.html"), "<h1>hello</h1>\n");
        Files.writeString(workspace.resolve("notes.md"), "Hidden project token: ALPHA-742\n");

        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user("What files are in this folder?"));
        var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                "Directory entries:\n- README.md\n- index.html\n- notes.md",
                1,
                1,
                List.of("talos.list_dir"),
                List.of(),
                0,
                0,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0);
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("""
                        {"name":"talos.read_file","arguments":{"path":"index.html"}}"""))
                .toolCallLoop(new dev.talos.runtime.ToolCallLoop(new dev.talos.runtime.TurnProcessor(null)))
                .build();

        var result = AssistantTurnExecutor.inspectCompletenessRetryIfNeeded(
                loopResult.finalAnswer(),
                messages,
                loopResult,
                workspace,
                ctx);

        assertEquals(loopResult.finalAnswer(), result.answer());
        assertNull(result.extraSummary());
    }

    @Test
    @DisplayName("records and prints redacted prompt audit in debug prompt mode")
    void recordsAndPrintsPromptAuditInDebugPromptMode() {
        StringBuilder stream = new StringBuilder();
        var ctx = Context.builder(new Config())
                .session(sessionWithDebugLevel(DebugLevel.PROMPT))
                .llm(LlmClient.scripted("hello"))
                .streamSink(stream::append)
                .build();
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("system"),
                ChatMessage.user("Hello friend")));

        LocalTurnTraceCapture.begin(
                "trc-prompt",
                "sid",
                1,
                "2026-04-30T00:00:00Z",
                "workspace-hash",
                "auto",
                "scripted",
                "test-model",
                "Hello friend");
        try {
            AssistantTurnExecutor.execute(messages, WS, ctx, new AssistantTurnExecutor.Options());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertNotNull(trace.promptAudit());
            assertFalse(trace.promptAudit().taskType().isBlank());
            assertFalse(trace.promptAudit().actionObligation().isBlank());
            assertTrue(stream.toString().contains("Prompt Audit"), stream.toString());
            assertTrue(stream.toString().contains("actionObligation:"), stream.toString());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void directTurnClearsStalePromptDebugCapture() {
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                new ChatRequest(
                        "ollama",
                        "stale-model",
                        "",
                        "",
                        List.of(),
                        null,
                        List.of(ChatMessage.user("stale prompt")),
                        List.of()),
                false,
                "{\"stale\":true}"));
        var ctx = scriptedContext("this should not be used");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("system"),
                ChatMessage.user("What can you do in this workspace? Answer briefly.")));

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages, WS, ctx, new AssistantTurnExecutor.Options());

        assertTrue(output.text().contains("Talos can inspect this local workspace"), output.text());
        assertTrue(PromptDebugCapture.latest().isEmpty(), "direct local answers must not leave stale provider captures");
    }

    @Test
    void metaEvidenceReadQuestionAnswersFromRuntimeEvidenceWithoutReadingFile(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "PRIVATE-MARKER-SHOULD-NOT-BE-READ\n");
        var registry = new dev.talos.tools.ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());
        var processor = new dev.talos.runtime.TurnProcessor(
                null, new dev.talos.runtime.NoOpApprovalGate(), registry);
        var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
        SessionMemory memory = new SessionMemory();
        var ctx = Context.builder(new Config())
                .memory(memory)
                .llm(LlmClient.scripted(List.of(
                        "I will answer confidently without evidence.",
                        "I read notes.md.")))
                .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user("Did you read notes.md?"));

        TurnAuditCapture.begin();
        try {
            AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());
            var audit = TurnAuditCapture.end();

            assertTrue(output.text().startsWith("No."), output.text());
            assertTrue(output.text().contains("no runtime evidence"), output.text());
            assertFalse(output.text().contains("PRIVATE-MARKER-SHOULD-NOT-BE-READ"), output.text());
            assertTrue(audit.toolCalls().isEmpty(), audit.toolCalls().toString());
        } finally {
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        }
    }

    @Test
    void metaEvidenceReadQuestionCanAnswerYesFromPriorRuntimeEvidence(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "Prior evidence fixture.\n");
        SessionMemory memory = new SessionMemory();
        memory.recordToolEvidence(7, List.of(
                new dev.talos.runtime.TurnRecord.ToolCallSummary("talos.read_file", "notes.md", true)));
        var ctx = Context.builder(new Config())
                .memory(memory)
                .llm(LlmClient.scripted("This model response should not be used."))
                .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                .build();
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user(
                "Did you read notes.md after edits earlier in this session? Answer yes or no."));

        TurnAuditCapture.begin();
        try {
            AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());
            var audit = TurnAuditCapture.end();

            assertTrue(output.text().startsWith("Yes."), output.text());
            assertTrue(output.text().contains("runtime evidence"), output.text());
            assertTrue(audit.toolCalls().isEmpty(), audit.toolCalls().toString());
        } finally {
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        }
    }

    @Test
    void deicticApplyUsesActiveProposalContextForToolSurfaceAndPromptAudit(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Old title\n");
        String userRequest = "Apply that README.md proposal now.";
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                1, "trace-propose", List.of("README.md"),
                "Replace the README title and add usage.");
        SessionMemory memory = new SessionMemory();
        memory.setActiveTaskContext(context);
        memory.setArtifactGoal(ArtifactGoal.fromActiveContext(context));

        var registry = new dev.talos.tools.ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());
        registry.register(new dev.talos.tools.impl.FileWriteTool());
        var processor = new dev.talos.runtime.TurnProcessor(
                null, new dev.talos.runtime.NoOpApprovalGate(), registry);
        var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
        var ctx = Context.builder(new Config())
                .memory(memory)
                .llm(LlmClient.scripted(List.of(
                        "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"README.md\","
                                + "\"content\":\"# Talos\\n\\nUsage: run Talos.\\n\"}}",
                        "Updated README.md.")))
                .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user(userRequest));

        TurnAuditCapture.begin();
        LocalTurnTraceCapture.begin(
                "trc-apply",
                "sid",
                2,
                "2026-04-30T00:00:00Z",
                "workspace-hash",
                "auto",
                "scripted",
                "test-model",
                userRequest);
        try {
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());
            var audit = TurnAuditCapture.end();
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(Files.readString(workspace.resolve("README.md")).contains("Usage: run Talos."));
            assertTrue(out.text().contains("Updated README.md"), out.text());
            assertEquals("FILE_EDIT", audit.policyTrace().taskType());
            assertTrue(audit.policyTrace().mutationAllowed());
            assertEquals(List.of("README.md"), audit.policyTrace().expectedTargets());
            assertNotNull(trace.promptAudit());
            assertTrue(trace.promptAudit().activeTaskContext().contains("state=ACTIVE"),
                    trace.promptAudit().activeTaskContext());
            assertTrue(trace.promptAudit().activeTaskContext().contains("kind=PROPOSED_CHANGES"),
                    trace.promptAudit().activeTaskContext());
            assertTrue(trace.promptAudit().artifactGoal().contains("kind=README"),
                    trace.promptAudit().artifactGoal());
            assertTrue(trace.promptAudit().artifactGoal().contains("operation=APPLY_EDIT"),
                    trace.promptAudit().artifactGoal());
            assertTrue(trace.promptAudit().nativeTools().contains("talos.read_file"),
                    trace.promptAudit().nativeTools().toString());
            assertTrue(trace.promptAudit().nativeTools().contains("talos.write_file"),
                    trace.promptAudit().nativeTools().toString());
        } finally {
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void noWorkspaceChatSuppressesActiveContextInPromptAudit() {
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                1, "trace-propose", List.of("README.md"),
                "Replace the README title and add usage.");
        ArtifactGoal goal = ArtifactGoal.fromActiveContext(context);
        SessionMemory memory = new SessionMemory();
        memory.setActiveTaskContext(context);
        memory.setArtifactGoal(goal);
        var ctx = Context.builder(new Config())
                .memory(memory)
                .llm(LlmClient.scripted("No problem, we can just chat."))
                .build();
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user("I am only chatting, please don't inspect my files."));

        TurnAuditCapture.begin();
        LocalTurnTraceCapture.begin(
                "trc-chat",
                "sid",
                2,
                "2026-04-30T00:00:00Z",
                "workspace-hash",
                "auto",
                "scripted",
                "test-model",
                "I am only chatting, please don't inspect my files.");
        try {
            AssistantTurnExecutor.execute(messages, WS, ctx, new AssistantTurnExecutor.Options());
            var audit = TurnAuditCapture.end();
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertEquals(TaskType.SMALL_TALK.name(), audit.policyTrace().taskType());
            assertFalse(audit.policyTrace().mutationAllowed());
            assertNotNull(trace.promptAudit());
            assertTrue(trace.promptAudit().activeTaskContext().contains("state=SUPPRESSED"),
                    trace.promptAudit().activeTaskContext());
            assertFalse(trace.promptAudit().activeTaskContext().contains("README.md"),
                    trace.promptAudit().activeTaskContext());
            assertFalse(trace.promptAudit().activeTaskContext().contains("Replace the README"),
                    trace.promptAudit().activeTaskContext());
            assertTrue(trace.promptAudit().artifactGoal().equals("NONE_OR_NOT_DERIVED")
                            || (!trace.promptAudit().artifactGoal().contains("README")
                            && !trace.promptAudit().artifactGoal().contains("APPLY_EDIT")),
                    trace.promptAudit().artifactGoal());
            assertEquals(ActiveTaskContext.State.ACTIVE, memory.activeTaskContext().state());
            assertEquals(goal, memory.artifactGoal());
        } finally {
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void modelSwitchStyleSmallTalkDoesNotExposeToolsOrExpiredContextInPromptAudit() {
        for (String prompt : List.of(
                "Hello friend, how are you?",
                "Hello friend, how are you after the model command?")) {
            ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                    1, "trace-propose", List.of("README.md"),
                    "Replace the README title and add usage.");
            SessionMemory memory = new SessionMemory();
            memory.setActiveTaskContext(context);
            memory.setArtifactGoal(ArtifactGoal.fromActiveContext(context));
            for (int i = 0; i < 4; i++) {
                memory.update("previous user " + i, "previous answer " + i);
            }
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var ctx = Context.builder(new Config())
                    .memory(memory)
                    .llm(LlmClient.scripted("Hello. I am doing well."))
                    .toolRegistry(registry)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("system"));
            messages.add(ChatMessage.user(prompt));

            TurnAuditCapture.begin();
            LocalTurnTraceCapture.begin(
                    "trc-model-switch-small-talk",
                    "sid",
                    6,
                    "2026-05-01T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    prompt);
            try {
                AssistantTurnExecutor.execute(messages, WS, ctx, new AssistantTurnExecutor.Options());
                var audit = TurnAuditCapture.end();
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertEquals(TaskType.SMALL_TALK.name(), audit.policyTrace().taskType(), prompt);
                assertTrue(audit.policyTrace().nativeTools().isEmpty(),
                        audit.policyTrace().nativeTools().toString());
                assertNotNull(trace.promptAudit());
                assertEquals(TaskType.SMALL_TALK.name(), trace.promptAudit().taskType(), prompt);
                assertEquals("DIRECT_ANSWER_ONLY", trace.promptAudit().actionObligation(), prompt);
                assertTrue(trace.promptAudit().nativeTools().isEmpty(),
                        trace.promptAudit().nativeTools().toString());
                assertTrue(trace.promptAudit().promptTools().isEmpty(),
                        trace.promptAudit().promptTools().toString());
                assertEquals("NONE_OR_NOT_DERIVED", trace.promptAudit().activeTaskContext(), prompt);
                assertEquals(ActiveTaskContext.State.NONE, memory.activeTaskContext().state(), prompt);
            } finally {
                if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
                LocalTurnTraceCapture.clear();
            }
        }
    }

    @Test
    void deicticApplyReplacesStaleNativeSurfaceAndCapabilityFrame(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Old title\n");
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                1, "trace-propose", List.of("README.md"),
                "Replace the README title and add usage.");
        SessionMemory memory = new SessionMemory();
        memory.setActiveTaskContext(context);
        memory.setArtifactGoal(ArtifactGoal.fromActiveContext(context));

        var registry = new dev.talos.tools.ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());
        registry.register(new dev.talos.tools.impl.FileWriteTool());
        registry.register(new dev.talos.tools.impl.FileEditTool());
        var processor = new dev.talos.runtime.TurnProcessor(
                null, new dev.talos.runtime.NoOpApprovalGate(), registry);
        var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
        var ctx = Context.builder(new Config())
                .memory(memory)
                .llm(LlmClient.scripted(List.of(
                        "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"README.md\","
                                + "\"content\":\"# Talos\\n\\nUsage: run Talos.\\n\"}}",
                        "Updated README.md.")))
                .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .nativeToolSpecs(List.of(new ToolSpec("talos.read_file", "Read", "{}")))
                .build();
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.system("""
                [CurrentTurnCapability]
                [TaskContract]
                type: WORKSPACE_EXPLAIN
                mutationAllowed: false
                verificationRequired: false
                phase: INSPECT
                visibleTools: talos.read_file
                """));
        messages.add(ChatMessage.user("make those changes"));

        LocalTurnTraceCapture.begin(
                "trc-apply-stale-frame",
                "sid",
                2,
                "2026-04-30T00:00:00Z",
                "workspace-hash",
                "auto",
                "scripted",
                "test-model",
                "make those changes");
        try {
            AssistantTurnExecutor.execute(messages, workspace, ctx, new AssistantTurnExecutor.Options());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(trace.promptAudit().nativeTools().contains("talos.write_file"),
                    trace.promptAudit().nativeTools().toString());
            assertTrue(trace.promptAudit().nativeTools().contains("talos.edit_file"),
                    trace.promptAudit().nativeTools().toString());
            List<String> frames = messages.stream()
                    .filter(AssistantTurnExecutorTest::isCurrentTurnCapabilityFrame)
                    .map(ChatMessage::content)
                    .toList();
            assertEquals(1, frames.size(), frames.toString());
            assertTrue(frames.getFirst().contains("type: FILE_EDIT"), frames.getFirst());
            assertTrue(frames.getFirst().contains("mutationAllowed: true"), frames.getFirst());
            assertTrue(frames.getFirst().contains("talos.write_file"), frames.getFirst());
            assertTrue(frames.getFirst().contains("kind=PROPOSED_CHANGES"), frames.getFirst());
            assertFalse(frames.getFirst().contains("type: WORKSPACE_EXPLAIN"), frames.getFirst());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    private static boolean isCurrentTurnCapabilityFrame(ChatMessage message) {
        return message != null
                && message.content() != null
                && message.content().contains("[CurrentTurnCapability]");
    }

    @Test
    @DisplayName("truth and grounding annotations are ASCII-safe for redirected terminals")
    void annotationsAreAsciiSafe() {
        List<String> annotations = List.of(
                AssistantTurnExecutor.FALSE_MUTATION_ANNOTATION,
                AssistantTurnExecutor.PARTIAL_MUTATION_ANNOTATION,
                AssistantTurnExecutor.DENIED_MUTATION_ANNOTATION,
                AssistantTurnExecutor.INVALID_MUTATION_ANNOTATION,
                AssistantTurnExecutor.UNDER_INSPECTION_ANNOTATION,
                AssistantTurnExecutor.UNGROUNDED_ANNOTATION,
                AssistantTurnExecutor.STREAMING_NO_TOOL_MUTATION_ANNOTATION,
                AssistantTurnExecutor.STREAMING_NO_TOOL_MUTATION_REPLACEMENT,
                AssistantTurnExecutor.MALFORMED_TOOL_PROTOCOL_REPLACEMENT
        );

        for (String annotation : annotations) {
            assertTrue(annotation.chars().allMatch(ch -> ch < 128),
                    "Terminal-facing annotation must remain ASCII-safe: " + annotation);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Non-streaming path (no streamSink)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Non-streaming path")
    class NonStreaming {

        @Test
        void returns_non_empty_answer() {
            var ctx = scriptedContext("non-streamed answer");
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options();

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertFalse(out.text().isBlank(), "Should return non-empty text");
            assertFalse(out.streamed(), "Non-streaming path should not be marked streamed");
        }

        @Test
        void respects_timeout_option() {
            var ctx = scriptedContext("timeout-safe answer");
            var messages = basicMessages();
            // Very long timeout - should still work normally
            var opts = new AssistantTurnExecutor.Options().llmTimeoutMs(60_000L);

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertFalse(out.text().isBlank());
        }

        @Test
        void explicitMutationNoToolAnswerRetriesAndExecutesWrite(@TempDir Path workspace)
                throws Exception {
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            String unsupportedNoToolProse = "Create `script.js` with the following JavaScript code.";
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            unsupportedNoToolProse,
                            "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"script.js\","
                                    + "\"content\":\"document.body.dataset.ready = 'true';\"}}",
                            "Created script.js.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Create the script.js file you need in this workspace."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(Files.exists(workspace.resolve("script.js")),
                    "no-tool mutation retry must execute the write_file call");
            assertEquals("document.body.dataset.ready = 'true';",
                    Files.readString(workspace.resolve("script.js")));
            assertTrue(out.text().contains("[Used 1 tool(s): talos.write_file"),
                    "retry tool execution summary should be visible");
            assertFalse(messages.stream()
                            .filter(message -> "assistant".equals(message.role()))
                            .anyMatch(message -> unsupportedNoToolProse.equals(message.content())),
                    "unsupported no-tool prose must not be replayed as assistant history for the retry");
            assertTrue(messages.stream()
                            .filter(message -> "assistant".equals(message.role()))
                            .anyMatch(message -> message.content().contains(
                                    "[Action obligation check: the previous model response did not issue "
                                            + "required write/edit tool calls.]")),
                    "retry context should contain the runtime-owned no-tool summary");
        }

        @Test
        void naturalDeleteRequestUsesFirstClassDeleteTool(@TempDir Path workspace) throws Exception {
            Files.createDirectories(workspace.resolve("docs"));
            Files.writeString(workspace.resolve("docs/synthwave-webpage-plan.md"), "delete me");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.DeletePathTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.delete_path\",\"arguments\":{\"path\":\"docs/synthwave-webpage-plan.md\"}}",
                            "Deleted docs/synthwave-webpage-plan.md.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Delete docs/synthwave-webpage-plan.md please."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(Files.exists(workspace.resolve("docs/synthwave-webpage-plan.md")));
            assertTrue(out.text().contains("[Used 1 tool(s): talos.delete_path"), out.text());
            assertFalse(out.text().contains("talos.write_file"), out.text());
            assertFalse(out.text().contains("Task incomplete"), out.text());
        }

        @Test
        void naturalDeleteRequestAcceptsDeleteFileAlias(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("obsolete-guide.md"), "delete me");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.DeletePathTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.delete_file\",\"arguments\":{\"path\":\"obsolete-guide.md\"}}",
                            "Deleted obsolete-guide.md.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Delete obsolete-guide.md please."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(Files.exists(workspace.resolve("obsolete-guide.md")));
            assertTrue(out.text().contains("[Used 1 tool(s):"), out.text());
            assertFalse(out.text().contains("Unknown tool"), out.text());
        }

        @Test
        void failedWorkspaceSwitchFencesNextRelativeFolderMutation(@TempDir Path workspace) {
            var memory = new SessionMemory();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.MakeDirectoryTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.mkdir\",\"arguments\":{\"path\":\"should-not-be-on-desktop\"}}",
                            "Created should-not-be-on-desktop.")))
                    .memory(memory)
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();

            var switchMessages = new ArrayList<ChatMessage>();
            switchMessages.add(ChatMessage.system("sys"));
            switchMessages.add(ChatMessage.user("Change workspace to Desktop."));
            AssistantTurnExecutor.TurnOutput switchOut = AssistantTurnExecutor.execute(
                    switchMessages, workspace, ctx, new AssistantTurnExecutor.Options());
            assertTrue(switchOut.text().contains("cannot change workspace"), switchOut.text());

            var createMessages = new ArrayList<ChatMessage>();
            createMessages.add(ChatMessage.system("sys"));
            createMessages.add(ChatMessage.user("Create folder should-not-be-on-desktop."));
            AssistantTurnExecutor.TurnOutput createOut = AssistantTurnExecutor.execute(
                    createMessages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(Files.exists(workspace.resolve("should-not-be-on-desktop")));
            assertTrue(createOut.text().contains("current workspace is still"), createOut.text());
            assertTrue(createOut.text().contains(workspace.toAbsolutePath().normalize().toString()), createOut.text());
            assertTrue(createOut.text().contains("should-not-be-on-desktop"), createOut.text());
            assertFalse(createOut.text().contains("[Used"), createOut.text());
        }

        @Test
        void confirmationAfterWorkspaceFenceAppliesSavedRelativeMutation(@TempDir Path workspace) {
            var memory = new SessionMemory();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.MakeDirectoryTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            ToolSpec mkdir = new ToolSpec(
                    "talos.mkdir",
                    "Create a directory.",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(
                            new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                    "call_mkdir",
                                    "talos.mkdir",
                                    java.util.Map.of("path", "should-not-be-on-desktop")))),
                            new LlmClient.StreamResult("Created should-not-be-on-desktop.", List.of())),
                    4096);
            ToolSpec staleRead = new ToolSpec(
                    "talos.read_file",
                    "Read a file.",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");
            ToolSpec staleList = new ToolSpec(
                    "talos.list_dir",
                    "List a directory.",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");
            var ctx = Context.builder(new Config())
                    .llm(recorded.client())
                    .memory(memory)
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .nativeToolSpecs(List.of(staleRead, staleList))
                    .build();

            var switchMessages = new ArrayList<ChatMessage>();
            switchMessages.add(ChatMessage.system("sys"));
            switchMessages.add(ChatMessage.user("Change workspace to Desktop."));
            AssistantTurnExecutor.execute(switchMessages, workspace, ctx, new AssistantTurnExecutor.Options());

            var createMessages = new ArrayList<ChatMessage>();
            createMessages.add(ChatMessage.system("sys"));
            createMessages.add(ChatMessage.user("Create folder should-not-be-on-desktop."));
            AssistantTurnExecutor.execute(createMessages, workspace, ctx, new AssistantTurnExecutor.Options());

            var confirmMessages = new ArrayList<ChatMessage>();
            confirmMessages.add(ChatMessage.system("sys"));
            confirmMessages.add(ChatMessage.system("""
                    [CurrentTurnCapability]
                    type: WORKSPACE_EXPLAIN
                    mutationAllowed: false
                    visibleTools: talos.list_dir
                    """));
            confirmMessages.add(ChatMessage.user("Change workspace to Desktop."));
            confirmMessages.add(ChatMessage.assistant("Talos cannot change workspace from inside the REPL."));
            confirmMessages.add(ChatMessage.user("Create folder should-not-be-on-desktop."));
            confirmMessages.add(ChatMessage.assistant(
                    "The current workspace is still " + workspace.toAbsolutePath().normalize()
                            + ". Confirm if you want this change applied in the current workspace."));
            confirmMessages.add(ChatMessage.user("Yes, create it in the current workspace."));
            AssistantTurnExecutor.TurnOutput confirmOut = AssistantTurnExecutor.execute(
                    confirmMessages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(Files.isDirectory(workspace.resolve("should-not-be-on-desktop")));
            assertTrue(confirmOut.text().contains("[Used 1 tool(s): talos.mkdir"), confirmOut.text());
            assertFalse(confirmOut.text().contains("current workspace is still"), confirmOut.text());
            assertFalse(recorded.requests().isEmpty(), "confirmation must reach the backend as a mutation turn");
            ChatRequest request = recorded.requests().getFirst();
            String prompt = request.messages.stream()
                    .map(message -> message.content() == null ? "" : message.content())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertEquals(1, request.messages.stream()
                    .filter(AssistantTurnExecutorTest::isCurrentTurnCapabilityFrame)
                    .count(), "exactly one current-turn frame should be sent");
            assertTrue(prompt.contains("type: FILE_CREATE"), prompt);
            assertTrue(prompt.contains("mutationAllowed: true"), prompt);
            assertTrue(prompt.contains("visibleTools: talos.mkdir"), prompt);
            assertFalse(prompt.contains("visibleTools: talos.list_dir, talos.read_file"), prompt);
            assertTrue(prompt.contains("Create folder should-not-be-on-desktop."), prompt);
            assertFalse(prompt.contains("type: WORKSPACE_EXPLAIN"), prompt);
        }

        @Test
        void hiddenWorkspaceOperationToolIsRejectedBeforeExecution(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("source.txt"), "source");
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.MakeDirectoryTool());
            registry.register(new dev.talos.tools.impl.MovePathTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            ToolSpec move = new ToolSpec(
                    "talos.move_path",
                    "Move a workspace path.",
                    "{\"type\":\"object\",\"properties\":{\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"}},\"required\":[\"from\",\"to\"]}");
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.mkdir\",\"arguments\":{\"path\":\"archive\"}}",
                            "I stopped after the policy block.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .nativeToolSpecs(List.of(move))
                    .build();

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Move source.txt to archive/source.txt."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(Files.exists(workspace.resolve("archive")),
                    "hidden talos.mkdir must be rejected before it creates a directory");
            assertTrue(out.text().contains("talos.mkdir"), out.text());
            assertTrue(out.text().contains("not allowed") || out.text().contains("policy"), out.text());
        }

        @Test
        void compoundWorkspaceOperationCanApplyBatchThroughVisibleSurface(@TempDir Path workspace) throws Exception {
            Files.createDirectories(workspace.resolve("docs"));
            Files.writeString(workspace.resolve("docs/summary.md"), "summary body");
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.runtime.workspace.BatchWorkspaceApplyTool());
            registry.register(new dev.talos.tools.impl.MakeDirectoryTool());
            registry.register(new dev.talos.tools.impl.CopyPathTool());
            registry.register(new dev.talos.tools.impl.RenamePathTool());
            registry.register(new dev.talos.tools.impl.MovePathTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(
                            new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                    "call_batch",
                                    "talos.apply_workspace_batch",
                                    java.util.Map.of("operations_json", """
                                            [
                                              {"op":"mkdir","path":"assets"},
                                              {"op":"mkdir","path":"drafts"},
                                              {"op":"copy_path","from":"docs/summary.md","to":"drafts/summary-copy.md"},
                                              {"op":"rename_path","path":"drafts/summary-copy.md","new_name":"summary-renamed.md"},
                                              {"op":"move_path","from":"drafts/summary-renamed.md","to":"assets/summary-renamed.md"}
                                            ]
                                            """)))),
                            new LlmClient.StreamResult("Applied the workspace organization batch.", List.of())),
                    4096);
            var ctx = Context.builder(new Config())
                    .llm(recorded.client())
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Create folders assets and drafts, copy docs/summary.md "
                    + "to drafts/summary-copy.md, rename it to summary-renamed.md, then move it "
                    + "to assets/summary-renamed.md."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertEquals("summary body", Files.readString(workspace.resolve("assets/summary-renamed.md")));
            assertFalse(Files.exists(workspace.resolve("drafts/summary-renamed.md")));
            assertTrue(out.text().contains("[Used 1 tool(s): talos.apply_workspace_batch"), out.text());
            assertFalse(recorded.requests().isEmpty(), "compound workspace turn must reach the backend");
            List<String> toolNames = recorded.requests().getFirst().tools.stream()
                    .map(ToolSpec::name)
                    .sorted()
                    .toList();
            assertEquals(
                    List.of(
                            "talos.apply_workspace_batch",
                            "talos.copy_path",
                            "talos.mkdir",
                            "talos.move_path",
                            "talos.rename_path"),
                    toolNames);
        }

        @Test
        void readOnlyDirectEvidenceQuestionReplacesApologyNonAnswer(@TempDir Path workspace) throws Exception {
            Files.createDirectories(workspace.resolve("docs"));
            Files.writeString(workspace.resolve("docs/summary.md"),
                    "Public release summary only.\nNo private markers are included here.\n");
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"docs/summary.md\"}}",
                            "I apologize for the confusion. Let's proceed with the task as originally requested.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Read docs/summary.md and tell me if it mentions the private notes marker. Do not read notes.md or .env."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("No. docs/summary.md does not mention"), out.text());
            assertTrue(out.text().contains("docs/summary.md"), out.text());
            assertFalse(out.text().toLowerCase(java.util.Locale.ROOT).contains("let's proceed"), out.text());
            assertFalse(out.text().toLowerCase(java.util.Locale.ROOT).contains("apologize"), out.text());
        }

        @Test
        void readOnlyDirectEvidenceQuestionKeepsConcreteModelAnswer(@TempDir Path workspace) throws Exception {
            Files.createDirectories(workspace.resolve("docs"));
            Files.writeString(workspace.resolve("docs/summary.md"),
                    "Public release summary only.\nNo private markers are included here.\n");
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"docs/summary.md\"}}",
                            "No, docs/summary.md does not mention the private notes marker.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Does docs/summary.md mention the private notes marker?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("No, docs/summary.md does not mention the private notes marker."),
                    out.text());
            assertFalse(out.text().contains("Read docs/summary.md:"), out.text());
        }

        @Test
        void readOnlyDirectEvidenceQuestionReplacesContradictoryYesAnswer(@TempDir Path workspace) throws Exception {
            Files.createDirectories(workspace.resolve("docs"));
            Files.writeString(workspace.resolve("docs/summary.md"),
                    "Avoid private notes or secrets.\nPublic summary only.\n");
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"docs/summary.md\"}}",
                            "Yes - line 1 references the private-notes marker.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Does docs/summary.md mention the private notes marker?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains(
                    "No. docs/summary.md does not mention \"private notes marker\" in the inspected content."),
                    out.text());
            assertFalse(out.text().startsWith("Yes"), out.text());
        }

        @Test
        void readOnlyDirectEvidenceQuestionKeepsAgreeingYesAnswer(@TempDir Path workspace) throws Exception {
            Files.createDirectories(workspace.resolve("docs"));
            Files.writeString(workspace.resolve("docs/summary.md"),
                    "The private notes marker is not included in released copy.\n");
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"docs/summary.md\"}}",
                            "Yes, docs/summary.md mentions the private notes marker.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Does docs/summary.md mention the private notes marker?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("Yes, docs/summary.md mentions the private notes marker."),
                    out.text());
            assertFalse(out.text().contains("Read docs/summary.md:"), out.text());
        }

        @Test
        void summarizeSourceIntoFileReadsSourceThenWritesTarget(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("long-notes.txt"), """
                    - Alice shipped the prototype.
                    - Beta users asked for clearer onboarding.
                    - Next step is to publish a short release note.
                    """);
            Files.writeString(workspace.resolve(".env"), "SECRET_MARKER=do-not-read");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"long-notes.txt\"}}\n"
                                    + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"docs/summary.md\","
                                    + "\"content\":\"- Prototype shipped.\\n- Onboarding needs clearer guidance.\\n"
                                    + "- Publish a short release note next.\"}}",
                            "Created docs/summary.md from long-notes.txt.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Summarize long-notes.txt into docs/summary.md. "
                            + "Keep it under 8 bullets and do not read protected files."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(Files.exists(workspace.resolve("docs/summary.md")), out.text());
            String summary = Files.readString(workspace.resolve("docs/summary.md"));
            assertTrue(summary.contains("Prototype shipped."), summary);
            assertFalse(summary.contains("SECRET_MARKER"), summary);
            assertTrue(out.text().contains("[Used 2 tool(s): talos.read_file, talos.write_file"), out.text());
            assertFalse(out.text().contains("[Evidence incomplete"), out.text());
            List<String> frames = messages.stream()
                    .filter(AssistantTurnExecutorTest::isCurrentTurnCapabilityFrame)
                    .map(ChatMessage::content)
                    .toList();
            assertEquals(1, frames.size(), frames.toString());
            assertTrue(frames.getFirst().contains("requiredTargets: docs/summary.md"), frames.getFirst());
            assertTrue(frames.getFirst().contains("sourceTargets: long-notes.txt"), frames.getFirst());
            assertFalse(frames.getFirst().contains(".env"), frames.getFirst());
        }

        @Test
        void readThenCreateFromItDoesNotPermitModelToOverwriteSource(@TempDir Path workspace) throws Exception {
            String originalSource = """
                    - Alice shipped the prototype.
                    - Beta users asked for clearer onboarding.
                    - Next step is to publish a short release note.
                    """;
            Files.writeString(workspace.resolve("long-notes.txt"), originalSource);
            Files.writeString(workspace.resolve(".env"), "SECRET_MARKER=do-not-read");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"long-notes.txt\"}}\n"
                                    + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"ideas/summary.md\","
                                    + "\"content\":\"- Prototype shipped.\\n- Onboarding needs clearer guidance.\"}}\n"
                                    + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"long-notes.txt\","
                                    + "\"content\":\"source rewrite\"}}",
                            "Updated ideas/summary.md and long-notes.txt.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "read long-notes.txt and create ideas/summary.md from it; do not read .env."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(Files.exists(workspace.resolve("ideas/summary.md")), out.text());
            assertEquals(originalSource, Files.readString(workspace.resolve("long-notes.txt")),
                    "Source evidence must remain input-only for read-then-create-from-it requests.");
            assertFalse(out.text().contains("Updated ideas/summary.md and long-notes.txt."), out.text());
            assertFalse(out.text().contains("Updated long-notes.txt"), out.text());
            assertTrue(out.text().contains("Target outside expected targets before approval")
                            || out.text().contains("outside the current expected target set"),
                    out.text());

            List<String> frames = messages.stream()
                    .filter(AssistantTurnExecutorTest::isCurrentTurnCapabilityFrame)
                    .map(ChatMessage::content)
                    .toList();
            assertEquals(1, frames.size(), frames.toString());
            assertTrue(frames.getFirst().contains("requiredTargets: ideas/summary.md"), frames.getFirst());
            assertTrue(frames.getFirst().contains("sourceTargets: long-notes.txt"), frames.getFirst());
            assertFalse(frames.getFirst().contains("requiredTargets: long-notes.txt"), frames.getFirst());
            assertFalse(frames.getFirst().contains(".env"), frames.getFirst());
        }

        @Test
        void staticWebBuildFromSourceReadsBriefAndDoesNotMutateSource(@TempDir Path workspace) throws Exception {
            String brief = """
                    Neon Harbor needs a synthwave landing page with a hero section,
                    a tour call to action, and a mailing list signup.
                    """;
            Files.writeString(workspace.resolve("brief.txt"), brief);

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"brief.txt\"}}\n"
                                    + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"index.html\","
                                    + "\"content\":\"<!doctype html>\\n<html lang=\\\"en\\\">\\n<head>\\n"
                                    + "  <meta charset=\\\"utf-8\\\">\\n  <title>Neon Harbor</title>\\n"
                                    + "  <link rel=\\\"stylesheet\\\" href=\\\"styles.css\\\">\\n</head>\\n"
                                    + "<body>\\n  <main>\\n    <h1>Neon Harbor</h1>\\n"
                                    + "    <p>Tour dates and mailing list signup.</p>\\n"
                                    + "    <button id=\\\"join-list\\\">Join list</button>\\n"
                                    + "    <p id=\\\"status\\\"></p>\\n  </main>\\n"
                                    + "  <script src=\\\"scripts.js\\\"></script>\\n</body>\\n</html>\\n\"}}\n"
                                    + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"styles.css\","
                                    + "\"content\":\"body { font-family: system-ui, sans-serif; background: #101018; color: white; }\\n"
                                    + "main { max-width: 42rem; margin: 3rem auto; }\\n"
                                    + "button { padding: 0.75rem 1rem; }\\n\"}}\n"
                                    + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"scripts.js\","
                                    + "\"content\":\"document.getElementById('join-list').addEventListener('click', () => {\\n"
                                    + "  document.getElementById('status').textContent = 'Signed up';\\n});\\n\"}}",
                            "Created the static page from brief.txt.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "create a website from brief.txt with index.html styles.css scripts.js. do not use script.js."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertEquals(brief, Files.readString(workspace.resolve("brief.txt")),
                    "Source brief must remain evidence/input, not a mutation target.");
            assertTrue(Files.exists(workspace.resolve("index.html")), out.text());
            assertTrue(Files.exists(workspace.resolve("styles.css")), out.text());
            assertTrue(Files.exists(workspace.resolve("scripts.js")), out.text());
            assertFalse(Files.exists(workspace.resolve("script.js")),
                    "Forbidden singular script.js must not be created.");
            assertFalse(out.text().contains("brief.txt: expected target was not successfully mutated"), out.text());
            List<String> frames = messages.stream()
                    .filter(AssistantTurnExecutorTest::isCurrentTurnCapabilityFrame)
                    .map(ChatMessage::content)
                    .toList();
            assertEquals(1, frames.size(), frames.toString());
            assertTrue(frames.getFirst().contains("requiredTargets: index.html, scripts.js, styles.css")
                            || frames.getFirst().contains("requiredTargets: index.html, styles.css, scripts.js"),
                    frames.getFirst());
            assertTrue(frames.getFirst().contains("sourceTargets: brief.txt"), frames.getFirst());
            assertFalse(frames.getFirst().contains("requiredTargets: brief.txt"), frames.getFirst());
        }

        @Test
        void summarizeSourceIntoFileSplitReadThenRetryPreservesSourceEvidence(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("long-notes.txt"), """
                    - Alice shipped the prototype.
                    - Beta users asked for clearer onboarding.
                    - Next step is to publish a short release note.
                    """);

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"long-notes.txt\"}}",
                            "I read long-notes.txt.",
                            "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"docs/summary.md\","
                                    + "\"content\":\"- Alice shipped the prototype.\\n"
                                    + "- Beta users need clearer onboarding.\\n"
                                    + "- Publish a short release note next.\"}}",
                            "Created docs/summary.md from long-notes.txt.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Summarize long-notes.txt into docs/summary.md. Keep it under 8 bullets."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(Files.exists(workspace.resolve("docs/summary.md")), out.text());
            assertFalse(out.text().contains("[Evidence incomplete"), out.text());
            assertTrue(out.text().contains("Source-derived coverage checks passed"), out.text());
            assertTrue(out.text().contains("summary semantics were not fully verified"), out.text());
            assertFalse(out.text().contains("[Static verification: passed"), out.text());
        }

        @Test
        void sourceDerivedCreateWritesAfterRuntimeForcesSourceRead(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("problem.md"), """
                    Implement Dijkstra shortest path for a small weighted directed graph.
                    Provide a pytest test file for the sample graph A->B cost 2, B->C cost 3, A->C cost 10;
                    expected A to C distance is 5.
                    """);

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 6);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"dijkstra.py\","
                                    + "\"content\":\"# placeholder before source read\"}}\n"
                                    + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"test_dijkstra.py\","
                                    + "\"content\":\"# placeholder before source read\"}}",
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"problem.md\"}}",
                            "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"dijkstra.py\","
                                    + "\"content\":\"import heapq\\n\\n"
                                    + "def shortest_path(graph, start, goal):\\n"
                                    + "    queue = [(0, start)]\\n"
                                    + "    seen = set()\\n"
                                    + "    while queue:\\n"
                                    + "        dist, node = heapq.heappop(queue)\\n"
                                    + "        if node == goal:\\n"
                                    + "            return dist\\n"
                                    + "        if node in seen:\\n"
                                    + "            continue\\n"
                                    + "        seen.add(node)\\n"
                                    + "        for nxt, cost in graph.get(node, {}).items():\\n"
                                    + "            heapq.heappush(queue, (dist + cost, nxt))\\n"
                                    + "    return float('inf')\\n\"}}\n"
                                    + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"test_dijkstra.py\","
                                    + "\"content\":\"from dijkstra import shortest_path\\n\\n"
                                    + "def test_sample_graph():\\n"
                                    + "    graph = {'A': {'B': 2, 'C': 10}, 'B': {'C': 3}, 'C': {}}\\n"
                                    + "    assert shortest_path(graph, 'A', 'C') == 5\\n\"}}")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create dijkstra.py and test_dijkstra.py according to problem.md, then run pytest if available. "
                            + "If Python execution is unavailable, say explicitly that Python/pytest was not run."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(Files.exists(workspace.resolve("dijkstra.py")), out.text());
            assertTrue(Files.exists(workspace.resolve("test_dijkstra.py")), out.text());
            assertTrue(Files.readString(workspace.resolve("dijkstra.py")).contains("def shortest_path"));
            assertTrue(Files.readString(workspace.resolve("test_dijkstra.py")).contains("expected")
                            || Files.readString(workspace.resolve("test_dijkstra.py")).contains("== 5"),
                    Files.readString(workspace.resolve("test_dijkstra.py")));
            assertFalse(Files.readString(workspace.resolve("dijkstra.py")).contains("placeholder before source read"));
            assertFalse(out.text().contains("Source-derived artifact write blocked before approval"), out.text());
        }

        @Test
        void summarizeSourceIntoFileInstructionEchoFailsVerification(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("long-notes.txt"), """
                    - The band is called Neon Harbor.
                    - The website needs a hero, latest single, tour dates, mailing list, and press kit.
                    - The tone should be direct, stylish, and practical.
                    """);

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"long-notes.txt\"}}",
                            "I read long-notes.txt.",
                            "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"docs/summary.md\","
                                    + "\"content\":\"Summarize the contents of long-notes.txt into 8 concise bullet points.\"}}",
                            "Created docs/summary.md from long-notes.txt.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Summarize long-notes.txt into docs/summary.md. Keep it under 8 bullets."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(Files.exists(workspace.resolve("docs/summary.md")), out.text());
            assertTrue(out.text().contains("Source-derived artifact verification failed"), out.text());
            assertTrue(out.text().contains("target content appears to repeat the request"), out.text());
            assertFalse(out.text().contains("[File write/readback passed"), out.text());
        }

        @Test
        void summarizeSourceIntoFileWithoutSourceReadDoesNotCreateUngroundedArtifact(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("long-notes.txt"), "Grounded source text.");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"docs/summary.md\","
                                    + "\"content\":\"- Ungrounded summary.\"}}",
                            "Created docs/summary.md.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Summarize long-notes.txt into docs/summary.md."));

            LocalTurnTraceCapture.begin("trc-t259-source-write-before-read", "session", 1,
                    "2026-05-13T00:00:00Z", "ws", "test", "llama_cpp", "qwen",
                    "Summarize long-notes.txt into docs/summary.md.");
            AssistantTurnExecutor.TurnOutput out;
            LocalTurnTrace trace;
            try {
                out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                trace = LocalTurnTraceCapture.complete();
            } finally {
                LocalTurnTraceCapture.clear();
            }

            assertFalse(Files.exists(workspace.resolve("docs/summary.md")),
                    "A source-derived artifact must not be written before the required source file is read.");
            assertTrue(out.text().contains("Source-derived artifact write blocked before approval"), out.text());
            assertTrue(out.text().contains("long-notes.txt"), out.text());
            assertFalse(out.text().contains("[File write/readback passed"), out.text());
            assertFalse(out.text().contains("Created docs/summary.md."), out.text());
            assertTrue(trace.events().stream()
                            .anyMatch(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type())
                                    && "SOURCE_EVIDENCE_WRITE_BEFORE_READ".equals(event.data().get("failureKind"))),
                    "Trace should record the source-evidence write-before-read gate.");
        }

        @Test
        void explicitMutationNoToolCapabilityDenialRetriesAndExecutesWrite(@TempDir Path workspace)
                throws Exception {
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I am unable to create or modify files within your workspace directly "
                                    + "as I do not have access to the underlying file system. "
                                    + "However, I can provide code snippets.",
                            "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"index.html\","
                                    + "\"content\":\"<!doctype html><title>BMI</title>\"}}",
                            "Created index.html.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "I want to create a modern BMI calculator website to use! Can you make it?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(Files.exists(workspace.resolve("index.html")),
                    "no-tool capability denial must be retried through mutating tools");
            assertTrue(out.text().contains("[Used 1 tool(s): talos.write_file"),
                    "retry tool execution summary should be visible");
            assertFalse(out.text().contains("unable to create or modify files"), out.text());
            assertFalse(out.text().contains("underlying file system"), out.text());
        }

        @Test
        void explicitMutationRetryStillRefusesReturnsDeterministicNoActionAnswer(@TempDir Path workspace)
                throws Exception {
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I am unable to create or modify files within your workspace directly.",
                            "I still do not have access to the underlying file system.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "I want to create a modern BMI calculator website to use! Can you make it?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(Files.exists(workspace.resolve("index.html")));
            assertTrue(out.text().contains("Talos can apply approved file changes in this workspace"),
                    out.text());
            assertTrue(out.text().contains("no files were changed"), out.text());
            assertFalse(out.text().contains("unable to create or modify files"), out.text());
            assertFalse(out.text().contains("underlying file system"), out.text());
        }

        @Test
        void postDenialRepairFollowUpNoToolAnswerRetriesAndExecutesPriorWrite(@TempDir Path workspace)
                throws Exception {
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I'm sorry, but I cannot assist with that request.",
                            "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"scripts.js\","
                                    + "\"content\":\"console.log(\\\"repair ok\\\");\"}}",
                            "Created scripts.js.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create scripts.js with exactly this text: console.log(\"repair ok\"); "
                            + "Use file tools; do not just show code."));
            messages.add(ChatMessage.assistant("""
                    [Mutation not applied: approval was denied.]

                    No file changes were applied because approval was denied.
                    scripts.js: approval denied.
                    """));
            messages.add(ChatMessage.user("nothing changed, try one more time"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(Files.exists(workspace.resolve("scripts.js")),
                    "post-denial retry must reissue the prior write through tools");
            assertEquals("console.log(\"repair ok\");",
                    Files.readString(workspace.resolve("scripts.js")));
            assertTrue(out.text().contains("[Used 1 tool(s): talos.write_file"),
                    "retry tool execution summary should be visible");
            assertFalse(out.text().contains("cannot assist"), out.text());
        }

        @Test
        void staticVerificationRepairRetryPromptIncludesVerifierFindings(@TempDir Path workspace)
                throws Exception {
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I can help with the repair.",
                            "I still need to know what to change.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create index.html, styles.css, and scripts.js for a BMI calculator."));
            messages.add(ChatMessage.assistant("""
                    [Task incomplete: Static verification failed - HTML does not link JavaScript file: `scripts.js`]

                    The requested task is not verified complete.
                    Remaining static verification problems:
                    - styles.css: expected target was not successfully mutated.
                    - HTML does not link JavaScript file: `scripts.js`
                    - Calculator/form task is missing a submit/calculate button.
                    """));
            messages.add(ChatMessage.user("Fix the remaining static verification problems now."));

            AssistantTurnExecutor.execute(messages, workspace, ctx, new AssistantTurnExecutor.Options());

            String repairInstruction = messages.stream()
                    .map(message -> message.content() == null ? "" : message.content())
                    .filter(content -> content.contains("[Static verification repair context]"))
                    .findFirst()
                    .orElse("");
            assertFalse(repairInstruction.isBlank(),
                    "repair turn must inject prior verifier findings before retrying");
            assertTrue(repairInstruction.contains("HTML does not link JavaScript file"),
                    repairInstruction);
            assertTrue(repairInstruction.contains("submit/calculate button"),
                    repairInstruction);
            assertTrue(repairInstruction.contains("Expected targets:"),
                    repairInstruction);
            assertTrue(repairInstruction.contains("talos.write_file with complete corrected file content"),
                    repairInstruction);
            assertTrue(repairInstruction.contains("Do not repeat an edit_file old_string that already failed"),
                    repairInstruction);
        }

        @Test
        void compactMutationRetryPreservesCssSelectorFactsFromRepairContext() {
            ChatMessage compact = AssistantTurnExecutor.compactStaticVerificationRepairInstructionForRetry(
                    ChatMessage.system("""
                            [Static verification repair context]
                            The previous mutation task ended incomplete after static verification.

                            Expected targets: index.html, scripts.js, styles.css

                            Previous static verification problems:
                            - CSS references missing class selectors: `.button`

                            Repair plan:
                            Full-file replacement targets: styles.css
                            - styles.css: You must use talos.write_file with complete corrected file content for styles.css.

                            CSS selector repair constraint:
                            - Only CSS targets are in this repair plan, so do not depend on HTML edits to satisfy the verifier.

                            [Current static selector facts]
                            I checked the selectors against the actual workspace files:

                            Observed in HTML:
                            - Classes: none
                            - IDs: `result`

                            Mismatches found:
                            - CSS references missing class selectors: `.button`
                            Use these current facts when rewriting CSS; do not preserve a selector listed as missing.
                            """));

            String content = compact.content();
            assertTrue(content.contains("CSS selector repair constraint"), content);
            assertTrue(content.contains("[Current static selector facts]"), content);
            assertTrue(content.contains("Observed in HTML:"), content);
            assertTrue(content.contains("- Classes: none"), content);
            assertTrue(content.contains("CSS references missing class selectors: `.button`"), content);
        }

        @Test
        void freshExactWriteSupersedesDisjointExistingStaticRepairContext(@TempDir Path workspace)
                throws Exception {
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"index.html\","
                                    + "\"content\":\"AFTER\"}}",
                            "Updated index.html.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.system("""
                    [Static verification repair context]
                    The previous mutation task ended incomplete after static verification.

                    Expected targets: scripts.js

                    Previous static verification problems:
                    - scripts.js: expected target was not successfully mutated.

                    Repair plan:
                    Full-file replacement targets: scripts.js
                    - scripts.js: You must use talos.write_file with complete corrected file content for scripts.js.
                    """));
            messages.add(ChatMessage.user(
                    "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js."));
            messages.add(ChatMessage.assistant("""
                    [Task incomplete: Static verification failed - scripts.js: expected target was not successfully mutated.]

                    The requested task is not verified complete.
                    Unresolved static verification problems:
                    - scripts.js: expected target was not successfully mutated.

                    Applied mutating tool calls:
                    - index.html: Updated index.html
                    - styles.css: Updated styles.css
                    - script.js: Updated script.js
                    """));
            messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));

            AssistantTurnExecutor.TurnOutput out;
            LocalTurnTrace trace;
            LocalTurnTraceCapture.begin(
                    "trc-t166-stale-repair-superseded",
                    "sid",
                    9,
                    "2026-05-06T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Overwrite index.html with exactly AFTER. Use talos.write_file.");
            try {
                out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                trace = LocalTurnTraceCapture.complete();
            } finally {
                LocalTurnTraceCapture.clear();
            }

            assertEquals("AFTER", Files.readString(workspace.resolve("index.html")));
            assertFalse(out.text().startsWith("[Action obligation failed:"), out.text());
            assertFalse(out.text().contains("pending static repair progress"), out.text());
            assertFalse(messages.stream()
                            .map(message -> message.content() == null ? "" : message.content())
                            .anyMatch(content -> content.startsWith("[Static verification repair context]")
                                    && content.contains("Full-file replacement targets: scripts.js")),
                    "fresh disjoint exact writes must remove stale static repair frames before the tool loop");
            assertTrue(trace.events().stream()
                            .anyMatch(event -> "REPAIR_DECISION_RECORDED".equals(event.type())
                                    && "SUPERSEDED".equals(event.data().get("status"))
                                    && String.valueOf(event.data().get("summary")).contains("scripts.js")),
                    "trace should record the stale static repair supersession");
        }

        @Test
        void exactLiteralWriteContextBudgetFallbackUsesCompactCurrentTurnPrompt(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), "BEFORE");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            ToolSpec writeFile = new ToolSpec(
                    "talos.write_file",
                    "Write a file.",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}");
            ToolSpec editFile = new ToolSpec(
                    "talos.edit_file",
                    "Edit a file.",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"old_string\":{\"type\":\"string\"},\"new_string\":{\"type\":\"string\"}},\"required\":[\"path\",\"old_string\",\"new_string\"]}");
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(
                            new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                    "call_exact",
                                    "talos.write_file",
                                    java.util.Map.of("path", "index.html", "content", "AFTER")))),
                            new LlmClient.StreamResult("Updated index.html.", List.of())),
                    2048);
            var visibleChunks = new ArrayList<String>();
            var ctx = Context.builder(new Config())
                    .llm(recorded.client())
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .streamSink(visibleChunks::add)
                    .nativeToolSpecs(List.of(writeFile, editFile))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys " + "large-system-token ".repeat(600)));
            messages.add(ChatMessage.user(
                    "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js."));
            messages.add(ChatMessage.assistant("""
                    [Task incomplete: Static verification failed - OLD_BMI_HISTORY_MARKER]

                    The requested task is not verified complete.
                    """));
            messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));

            AssistantTurnExecutor.TurnOutput out;
            LocalTurnTrace trace;
            LocalTurnTraceCapture.begin(
                    "trc-t219-exact-context-fallback",
                    "sid",
                    10,
                    "2026-05-08T00:00:00Z",
                    "workspace-hash",
                    "test",
                    "llama_cpp",
                    "gpt-oss-20b",
                    "Overwrite index.html with exactly AFTER. Use talos.write_file.");
            try {
                out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                trace = LocalTurnTraceCapture.complete();
            } finally {
                LocalTurnTraceCapture.clear();
            }

            assertEquals("AFTER", Files.readString(workspace.resolve("index.html")));
            assertFalse(out.streamed(), "mutation turns with a stream sink still use the buffered fallback path");
            assertTrue(visibleChunks.isEmpty(), "exact-write fallback must not stream partial mutation output");
            assertFalse(out.text().contains("Context budget exceeded"), out.text());
            assertFalse(out.text().contains("OLD_BMI_HISTORY_MARKER"), out.text());
            assertFalse(recorded.requests().isEmpty(), "compact fallback must reach the backend");

            ChatRequest fallbackRequest = recorded.requests().getFirst();
            String fallbackPrompt = fallbackRequest.messages.stream()
                    .map(message -> message.content() == null ? "" : message.content())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(fallbackPrompt.contains("OLD_BMI_HISTORY_MARKER"), fallbackPrompt);
            assertFalse(fallbackPrompt.contains("Create a complete static BMI calculator"), fallbackPrompt);
            assertTrue(fallbackPrompt.contains("[ExpectedTargets]"), fallbackPrompt);
            assertTrue(fallbackPrompt.contains("requiredTargets: index.html"), fallbackPrompt);
            assertTrue(fallbackPrompt.contains("[ExactFileWrite]"), fallbackPrompt);
            assertTrue(fallbackPrompt.contains("AFTER"), fallbackPrompt);
            assertTrue(fallbackPrompt.contains("Available mutating tools: talos.write_file."), fallbackPrompt);
            assertFalse(fallbackPrompt.contains(
                    "Available mutating tools: talos.write_file, talos.edit_file."), fallbackPrompt);
            assertEquals(List.of("talos.write_file"),
                    fallbackRequest.tools.stream().map(ToolSpec::name).toList());
            assertEquals(ToolChoiceMode.REQUIRED, fallbackRequest.controls.toolChoice());
            assertTrue(fallbackRequest.controls.debugTags().contains(
                    "context-budget-current-turn-fallback"));
            assertTrue(trace.events().stream()
                            .anyMatch(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type())
                                    && "RETRIED_COMPACT_CONTEXT".equals(event.data().get("status"))),
                    "trace should record the compact current-turn fallback");
        }

        @Test
        void contextBudgetFallbackDoesNotRunForDeicticNonLiteralMutation(@TempDir Path workspace)
                throws Exception {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult("This should not be reached.", List.of())),
                    2048);
            var ctx = Context.builder(new Config())
                    .llm(recorded.client())
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .nativeToolSpecs(List.of(new ToolSpec(
                            "talos.write_file",
                            "Write a file.",
                            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}")))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys " + "large-system-token ".repeat(600)));
            messages.add(ChatMessage.user("Here is the proposal: change README somehow."));
            messages.add(ChatMessage.assistant("Proposal: update README.md with a clearer heading."));
            messages.add(ChatMessage.user("Apply that proposal now."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("Context budget exceeded"), out.text());
            assertTrue(recorded.requests().isEmpty(),
                    "non-literal/deictic mutation requests must not use the exact-write compact fallback");
        }

        @Test
        void naturalRepairFollowUpWithoutCurrentMutationDoesNotSurfaceStaleSuccess(@TempDir Path workspace)
                throws Exception {
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "The BMI calculator is now working in the browser.",
                            "The BMI calculator is now working in the browser.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create index.html, styles.css, and scripts.js for a BMI calculator."));
            messages.add(ChatMessage.assistant("""
                    [Task incomplete: Static verification failed - HTML does not link JavaScript file: `scripts.js`]

                    The requested task is not verified complete.
                    Remaining static verification problems:
                    - styles.css: expected target was not successfully mutated.
                    - HTML does not link JavaScript file: `scripts.js`
                    - Calculator/form task is missing a submit/calculate button.
                    """));
            messages.add(ChatMessage.user(
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("[Action obligation failed:"), out.text());
            assertFalse(out.text().contains("now working in the browser"), out.text());
        }

        @Test
        void workspaceExplainNoToolDeflectionRetriesWithReadTools(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), """
                    <!doctype html>
                    <html>
                      <head><link rel="stylesheet" href="style.css"></head>
                      <body><h1>Night Drive</h1><script src="script.js"></script></body>
                    </html>
                    """);
            Files.writeString(workspace.resolve("style.css"), "body { background: #111; }\n");
            Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

            var chunks = new ArrayList<String>();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ListDirTool());
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "Sure, please provide the path of the folder you want me to inspect.",
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"style.css\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"script.js\"}}",
                            "This workspace is a small Night Drive web page. index.html loads style.css for styling and script.js for behavior.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .streamSink(chunks::add)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "I'm not a developer. What is this folder for? Please explain the website in plain English."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(out.streamed(),
                    "workspace-evidence turns should stay buffered so no-tool deflections can be retried");
            assertTrue(chunks.isEmpty(), "buffered retry path must not leak the initial deflection");
            assertTrue(out.text().contains("[Used 4 tool(s): talos.list_dir, talos.read_file"),
                    out.text());
            assertTrue(out.text().contains("Night Drive web page"), out.text());
            assertFalse(out.text().contains("provide the path"), out.text());
        }

        @Test
        void directoryListingWithContentReadIsDowngradedByEvidenceVerifier(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("README.md"), "Hidden project token: ALPHA-742\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ListDirTool());
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}",
                            "README.md contains ALPHA-742.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("List the files here."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("[Evidence incomplete:"), out.text());
            assertFalse(out.text().startsWith("Directory entries:"), out.text());
        }

        @Test
        void directoryListingUsesRequestedRootEvenWhenModelListsEmptySubdirectories(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("README.md"), "Hidden project token: ALPHA-742\n");
            Files.writeString(workspace.resolve("notes.md"), "Private notes.\n");
            Files.writeString(workspace.resolve("config.json"), "{}\n");
            Files.writeString(workspace.resolve("index.html"), "<button>Run</button>\n");
            Files.writeString(workspace.resolve("script.js"), "console.log('bug');\n");
            Files.writeString(workspace.resolve("styles.css"), "body{}\n");
            Files.writeString(workspace.resolve("report.docx"), "fake-binary\n");
            Files.createDirectories(workspace.resolve("natural-notes"));
            Files.createDirectories(workspace.resolve("audit-output"));

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ListDirTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}\n"
                                    + "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"natural-notes\"}}\n"
                                    + "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"audit-output\"}}\n"
                                    + "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".env\"}}\n"
                                    + "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"config.json\"}}\n"
                                    + "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"index.html\"}}\n"
                                    + "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"report.docx\"}}\n"
                                    + "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"script.js\"}}\n"
                                    + "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"styles.css\"}}",
                            "Directory entries:\n- (empty directory)")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("List files only; do not show content from README.md or notes.md."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("Directory entries:"), out.text());
            assertTrue(out.text().contains("- README.md"), out.text());
            assertTrue(out.text().contains("- notes.md"), out.text());
            assertTrue(out.text().contains("- natural-notes/"), out.text());
            assertFalse(out.text().contains("- (empty directory)"), out.text());
            assertFalse(out.text().contains("Hidden project token"), out.text());
            assertFalse(out.text().contains("Private notes"), out.text());
        }

        @Test
        void directoryListingUsesExplicitNamedDirectoryWhenUserRequestedIt(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("README.md"), "Root readme.\n");
            Files.createDirectories(workspace.resolve("natural-notes"));

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ListDirTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}\n"
                                    + "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"natural-notes\"}}",
                            "Directory entries:\n- README.md")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "List files in natural-notes only; do not show file contents."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("Directory entries:"), out.text());
            assertTrue(out.text().contains("- (empty directory)"), out.text());
            assertFalse(out.text().contains("- README.md"), out.text());
            assertFalse(out.text().contains("Root readme"), out.text());
        }

        @Test
        void verifyOnlyDirectoryPathSummaryOverridesUngroundedDirectoryContentClaim(@TempDir Path workspace)
                throws Exception {
            Files.createDirectories(workspace.resolve("archive"));
            Files.createDirectories(workspace.resolve("copies"));
            Files.createDirectories(workspace.resolve("scratch/nested/reports"));
            Files.writeString(workspace.resolve("archive/readme-renamed.md"), "# Archive Readme\n");
            Files.writeString(workspace.resolve("copies/readme-final.md"), "# Final Copy\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ListDirTool());
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 8);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"archive\"}}\n"
                                    + "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"copies\"}}\n"
                                    + "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"scratch/nested/reports\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"archive/readme-renamed.md\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"copies/readme-final.md\"}}",
                            "Verified paths: scratch/nested/reports exists and contains files, not shown here.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Verify the final workspace paths for archive/readme-renamed.md, "
                            + "copies/readme-final.md, and scratch/nested/reports. Do not edit files."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("archive/readme-renamed.md: file exists"), out.text());
            assertTrue(out.text().contains("copies/readme-final.md: file exists"), out.text());
            assertTrue(out.text().contains("scratch/nested/reports: directory exists and is empty"), out.text());
            assertFalse(out.text().contains("contains files"), out.text());
            assertFalse(out.text().contains("not shown here"), out.text());
        }

        @Test
        void explicitReadRequestWithZeroToolsDoesNotCompleteAsOrdinaryAnswer(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("README.md"), "# Project\nActual read content.\n");

            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("README says Actual read content."))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read README.md and summarize it."));

            LocalTurnTraceCapture.begin(
                    "trc-t57-zero-tools",
                    "sid",
                    1,
                    "2026-04-30T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Read README.md and summarize it.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("[Evidence incomplete:"), out.text());
                assertFalse(out.text().contains("READ_ONLY_ANSWERED"), out.text());
                assertEquals("READ_TARGET_REQUIRED", trace.promptAudit().evidenceObligation());
                assertEquals("ADVISORY_ONLY", trace.outcome().status());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void nonProtectedReadTargetNoToolAnswerRunsEvidenceRecovery(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("README.md"), "# Project\nActual read content.\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I can summarize the README.",
                            "README evidence gathered: Actual read content.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read README.md and summarize it."));

            LocalTurnTraceCapture.begin(
                    "trc-t77-read-evidence-recovery",
                    "sid",
                    1,
                    "2026-05-02T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Read README.md and summarize it.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("README evidence gathered"), out.text());
                assertFalse(out.text().contains("[Evidence incomplete:"), out.text());
                assertTrue(out.text().contains("talos.read_file"), out.text());
                assertEquals("READ_TARGET_REQUIRED", trace.promptAudit().evidenceObligation());
                assertEquals("COMPLETE", trace.outcome().status());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void partialMultiTargetReadRunsEvidenceRecoveryForAllTargets(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "Alpha evidence.\n");
            Files.writeString(workspace.resolve("b.txt"), "Beta evidence.\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"a.txt\"}}",
                            "I only read a.txt.",
                            "Both targets were gathered: Alpha evidence and Beta evidence.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Compare a.txt with b.txt."));

            LocalTurnTraceCapture.begin(
                    "trc-t77-partial-read-evidence-recovery",
                    "sid",
                    1,
                    "2026-05-02T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Compare a.txt with b.txt.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("Both targets were gathered"), out.text());
                assertFalse(out.text().contains("[Evidence incomplete:"), out.text());
                assertEquals("READ_TARGET_REQUIRED", trace.promptAudit().evidenceObligation());
                assertEquals("COMPLETE", trace.outcome().status());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void offTargetDeniedProtectedReadDoesNotBlockRequestedDocxEvidenceRecovery(@TempDir Path workspace)
                throws Exception {
            writeDocxFixture(workspace.resolve("report.docx"), "DOCX requested target evidence.");
            Files.createDirectories(workspace.resolve("protected"));
            Files.createDirectories(workspace.resolve("secrets"));
            Files.writeString(workspace.resolve("protected/private-notes.md"), "FILE_DISCOVERED_CANARY_OFFTARGET\n");
            Files.writeString(workspace.resolve("secrets/private-notes.md"), "FILE_DISCOVERED_CANARY_SECRET\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null,
                    (description, detail) -> false,
                    registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(documentExtractionEnabled("word"))
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"protected/private-notes.md\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"secrets/private-notes.md\"}}",
                            "Protected content was not read because approval was denied.",
                            "Recovered requested DOCX evidence: DOCX requested target evidence.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Summarize report.docx using extracted text only, and mention any extraction limitation."));

            LocalTurnTraceCapture.begin(
                    "trc-off-target-protected-denial-docx-recovery",
                    "sid",
                    1,
                    "2026-05-16T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Summarize report.docx using extracted text only, and mention any extraction limitation.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("Recovered requested DOCX evidence"), out.text());
                assertTrue(out.text().contains("DOCX requested target evidence"), out.text());
                assertFalse(out.text().contains("FILE_DISCOVERED_CANARY_OFFTARGET"), out.text());
                assertFalse(out.text().contains("FILE_DISCOVERED_CANARY_SECRET"), out.text());
                assertEquals("READ_TARGET_REQUIRED", trace.promptAudit().evidenceObligation());
                assertEquals("COMPLETE", trace.outcome().status());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void readOnlyReadmeProposalFlagsUnverifiedCommandsAsNotObserved(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("README.md"),
                    "# Focused Audit Fixture\n\nThis workspace checks response grounding.\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}",
                            """
                                    The README should add setup steps:
                                    1. Install dependencies using `npm install`.
                                    2. Run the audit with `node script.js`.
                                    """)))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Please review README.md and propose concise improvements, but do not edit any files yet."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("[Grounding warning:"), out.text());
            assertTrue(out.text().contains("not present in inspected workspace evidence"), out.text());
            assertTrue(out.text().contains("npm install"), out.text());
            assertTrue(out.text().contains("node script.js"), out.text());
        }

        @Test
        void readOnlyReadmeProposalAllowsObservedCommandsWithoutWarning(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("README.md"),
                    "# Node Fixture\n\nSetup: run `npm install`.\nUsage: run `node script.js`.\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}",
                            "Keep the existing setup commands `npm install` and `node script.js`, then add a purpose sentence.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Please review README.md and propose concise improvements, but do not edit any files yet."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(out.text().contains("[Grounding warning:"), out.text());
            assertTrue(out.text().contains("npm install"), out.text());
            assertTrue(out.text().contains("node script.js"), out.text());
        }

        @Test
        void readOnlyReadmeProposalRemovesExcludedEnvAdviceWhenUnobserved(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("README.md"),
                    "# Focused Audit Fixture\n\nThis workspace checks response grounding.\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}",
                            """
                                    Add usage instructions.
                                    Add a section documenting `.env` variables.
                                    Keep the fixture title.
                                    """)))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "I do not want the .env, I want README.md. Please review README.md and propose concise improvements, but do not edit any files yet."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("[Grounding warning:"), out.text());
            assertFalse(out.text().contains("documenting `.env` variables"), out.text());
            assertTrue(out.text().contains("Add usage instructions"), out.text());
            assertTrue(out.text().contains("Keep the fixture title"), out.text());
        }

        @Test
        void readOnlyReadmeProposalFlagsInternalPromptTextClaimedAsFileContent(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("README.md"),
                    "# Focused Audit Fixture\n\nThis workspace checks response grounding.\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}",
                            """
                                    Current Content:
                                    Behavior Rules
                                    You are an action-capable local assistant with full read/write access via tools.
                                    Suggested improvement: document talos.write_file usage.
                                    """)))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Please review README.md and propose concise improvements, but do not edit any files yet."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("[Grounding warning:"), out.text());
            assertTrue(out.text().contains("not present in inspected workspace evidence"), out.text());
            assertTrue(out.text().contains("Behavior Rules"), out.text());
            assertTrue(out.text().contains("talos.write_file"), out.text());
        }

        @Test
        void readOnlyProposalFlagsUnobservedNonFixtureFilenames(@TempDir Path workspace)
                throws Exception {
            // T762: file detection is evidence-derived, not the audit-fixture
            // name list - claims about ANY unread file now warn. This pins the
            // capability the old hardcoded marker set could not provide.
            Files.writeString(workspace.resolve("README.md"),
                    "# Focused Audit Fixture\n\nThis workspace checks response grounding.\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}",
                            "The README should document that main.py loads data.csv at startup.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Please review README.md and propose concise improvements, but do not edit any files yet."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("[Grounding warning:"), out.text());
            assertTrue(out.text().contains("main.py"), out.text());
        }

        @Test
        void readOnlyReadmeProposalFlagsUnobservedWorkspaceFileMeanings(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("README.md"),
                    "# Focused Audit Fixture\n\nThis workspace checks response grounding.\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}",
                            """
                                    Add a file overview:
                                    - `.env`: configuration for environment variables.
                                    - `report.docx`: report document.
                                    - `script.js`: JavaScript logic.
                                    """)))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Please review README.md and propose concise improvements, but do not edit any files yet."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("[Grounding warning:"), out.text());
            assertTrue(out.text().contains("not present in inspected workspace evidence"), out.text());
            assertTrue(out.text().contains("configuration for environment variables"), out.text());
        }

        @Test
        void readTargetHandoffReplacesMalformedPostReadAnswerWithEvidence(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("config.json"), "{\"name\":\"t57-fixture\"}\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I can read config.json.",
                            "{\"name\": <function-name>, \"arguments\": <args-json-object>}")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read config.json and tell me the name."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("t57-fixture"), out.text());
            assertFalse(out.text().contains("<function-name>"), out.text());
            assertFalse(out.text().contains("<args-json-object>"), out.text());
            assertFalse(out.text().contains("[Evidence incomplete:"), out.text());
        }

        @Test
        void streamingReadEvidencePromptUsesBufferedRecoveryPath(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("README.md"), "# Project\nActual read content.\n");

            var visibleChunks = new ArrayList<String>();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I can summarize the README.",
                            "README evidence gathered: Actual read content.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .streamSink(visibleChunks::add)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read README.md and summarize it."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(out.streamed(),
                    "read-evidence turns should buffer so no unsupported no-tool prose is printed first");
            assertTrue(visibleChunks.isEmpty(),
                    "initial no-tool prose must not reach the stream sink before evidence recovery");
            assertTrue(out.text().contains("README evidence gathered"), out.text());
            assertFalse(out.text().contains("[Evidence incomplete:"), out.text());
        }

        @Test
        void failedNoToolMutationRetryDoesNotCompleteAsUnverified(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), "<h1>Old</h1>\n");

            var registry = new dev.talos.tools.ToolRegistry();
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I updated index.html.",
                            "I still cannot edit files here.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Change index.html to say hello."));

            LocalTurnTraceCapture.begin(
                    "trc-t58-failed-mutation-obligation",
                    "sid",
                    1,
                    "2026-04-30T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Change index.html to say hello.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().startsWith("[Action obligation failed:"), out.text());
                assertEquals("<h1>Old</h1>\n", Files.readString(workspace.resolve("index.html")));
                assertEquals("BLOCKED", trace.outcome().status());
                assertEquals("BLOCKED_BY_POLICY", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void failedMutationRetryAfterReadOnlyToolLoopDoesNotCompleteAsUnverified(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), "<h1>Old</h1>\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "I inspected index.html and updated it in this response.",
                            "I still cannot edit files here.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Change index.html to say hello."));

            LocalTurnTraceCapture.begin(
                    "trc-t58-failed-mutation-obligation-after-read",
                    "sid",
                    1,
                    "2026-04-30T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Change index.html to say hello.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("[Action obligation failed:"), out.text());
                assertEquals("<h1>Old</h1>\n", Files.readString(workspace.resolve("index.html")));
                assertEquals("BLOCKED", trace.outcome().status());
                assertEquals("BLOCKED_BY_POLICY", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void readOnlyToolMutationRetryDoesNotCompleteAsUnverified(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), "<h1>Old</h1>\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "I inspected index.html and updated it in this response.",
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "I inspected index.html again but did not change it.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Change index.html to say hello."));

            LocalTurnTraceCapture.begin(
                    "trc-t58-read-only-mutation-retry",
                    "sid",
                    1,
                    "2026-04-30T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Change index.html to say hello.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("[Action obligation failed:"), out.text());
                assertEquals("<h1>Old</h1>\n", Files.readString(workspace.resolve("index.html")));
                assertEquals("BLOCKED", trace.outcome().status());
                assertEquals("BLOCKED_BY_POLICY", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void repairFixRetryWithOnlyInspectionToolsGetsTypedRepairBreach(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), "<h1>Old</h1>\n");
            Files.writeString(workspace.resolve("styles.css"), "body{}\n");
            Files.writeString(workspace.resolve("scripts.js"), "console.log('old');\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ListDirTool());
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I reviewed the BMI calculator and it is ready to use.",
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}",
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "I inspected the files and everything is complete.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create a complete static BMI calculator in this folder with index.html, "
                            + "styles.css, and scripts.js. It should calculate BMI from height and weight."));
            messages.add(ChatMessage.assistant("""
                    [Task incomplete: Static verification failed - HTML does not link JavaScript file: `scripts.js`]

                    The requested task is not verified complete.
                    Remaining static verification problems:
                    - HTML does not link JavaScript file: `scripts.js`
                    - Calculator/form task is missing a submit/calculate button.
                    """));
            messages.add(ChatMessage.user(
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser."));

            LocalTurnTraceCapture.begin(
                    "trc-t120-repair-inspection-only",
                    "sid",
                    1,
                    "2026-05-04T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("repair/fix turn inspected files but did not change them"),
                        out.text());
                assertFalse(out.text().toLowerCase(java.util.Locale.ROOT).contains("ready to use"),
                        out.text());
                assertFalse(out.text().toLowerCase(java.util.Locale.ROOT).contains("everything is complete"),
                        out.text());
                assertEquals("<h1>Old</h1>\n", Files.readString(workspace.resolve("index.html")));
                assertEquals("BLOCKED", trace.outcome().status());
                assertEquals("BLOCKED_BY_POLICY", trace.outcome().classification());

                var failed = trace.events().stream()
                        .filter(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type()))
                        .filter(event -> "FAILED".equals(event.data().get("status")))
                        .reduce((first, second) -> second)
                        .orElseThrow();
                assertEquals("REPAIR_INSPECTION_ONLY", failed.data().get("failureKind"));
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void conditionalReviewFixAllowsInspectionOnlyWhenCurrentStaticWebPasses(@TempDir Path workspace)
                throws Exception {
            writePassingBmiFixture(workspace);

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 8);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"styles.css\"}}",
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"scripts.js\"}}",
                            "I inspected the BMI calculator and it is ready to use.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser."));

            LocalTurnTraceCapture.begin(
                    "trc-t158-conditional-no-change",
                    "sid",
                    1,
                    "2026-05-06T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    messages.get(messages.size() - 1).content());
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("No file change was needed"), out.text());
                assertTrue(out.text().contains("Runtime static diagnostic inspection"), out.text());
                assertFalse(out.text().contains("Runtime static verification found"), out.text());
                assertTrue(out.text().contains("No files were changed"), out.text());
                assertFalse(out.text().contains("repair/fix turn inspected files but did not change them"),
                        out.text());
                assertFalse(out.text().contains("[Action obligation failed:"), out.text());
                assertEquals("NOT_RUN", trace.verification().status());
                assertEquals(0, trace.events().stream()
                        .filter(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type()))
                        .filter(event -> "REPAIR_INSPECTION_ONLY".equals(event.data().get("failureKind")))
                        .count());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void conditionalReviewFixFailsAfterRetryMutatingToolTargetsMissingFile(@TempDir Path workspace)
                throws Exception {
            writePassingBmiFixture(workspace);

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ListDirTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 8);
            String missingEdit = """
                    {"name":"talos.edit_file","arguments":{"path":"bmi_calculator.js","old_string":"old","new_string":"new"}}
                    """;
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}",
                            missingEdit,
                            "No file change is required.",
                            missingEdit,
                            "No file change is required.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser."));

            LocalTurnTraceCapture.begin(
                    "trc-t231-conditional-failed-mutation",
                    "sid",
                    1,
                    "2026-05-08T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    messages.get(messages.size() - 1).content());
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("invalid mutation arguments"), out.text());
                assertTrue(out.text().contains("target file not found before approval"), out.text());
                assertTrue(out.text().contains("bmi_calculator.js"), out.text());
                assertFalse(out.text().contains("No file change is required"), out.text());
                assertFalse(out.text().toLowerCase(java.util.Locale.ROOT).contains("complete"),
                        out.text());
                assertEquals("FAILED", trace.outcome().status());
                assertEquals("FAILED", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void conditionalReviewFixAllowsNoChangeWhenPassingWorkspaceHasStaleSimilarScriptSibling(
                @TempDir Path workspace) throws Exception {
            writePassingBmiFixture(workspace);
            Files.writeString(workspace.resolve("README.md"), "fixture\n");
            Files.writeString(workspace.resolve("notes.md"), "private notes\n");
            Files.writeString(workspace.resolve("config.json"), "{}\n");
            Files.writeString(workspace.resolve(".env"), "SECRET=fake\n");
            Files.writeString(workspace.resolve("report.docx"), "fake-binary\n");
            Files.writeString(workspace.resolve("script.js"), """
                    const button = document.querySelector('.cta-button');
                    const result = document.querySelector('#result');
                    if (button && result) {
                      button.addEventListener('click', () => {
                        result.textContent = 'Audit action complete.';
                      });
                    }
                    """);

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 8);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"script.js\"}}",
                            "No file change is required.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create a complete static BMI calculator in this folder with index.html, "
                            + "styles.css, and scripts.js. It should calculate BMI from height and weight."));
            messages.add(ChatMessage.assistant("""
                    [Static verification: passed - Static web coherence checks passed for 3 mutated target(s).]

                    Updated 3 files: index.html, styles.css, scripts.js.
                    """));
            messages.add(ChatMessage.user(
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser."));

            LocalTurnTraceCapture.begin(
                    "trc-t172-stale-sibling-no-change",
                    "sid",
                    1,
                    "2026-05-06T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    messages.get(messages.size() - 1).content());
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("No file change was needed"), out.text());
                assertTrue(out.text().contains("Runtime static diagnostic inspection"), out.text());
                assertFalse(out.text().contains("Runtime static verification found"), out.text());
                assertTrue(out.text().contains(
                        "Diagnostic inspection checked files: index.html, styles.css, scripts.js"),
                        out.text());
                assertTrue(out.text().contains(
                        "Tool-read files this turn: index.html, script.js"),
                        out.text());
                assertFalse(out.text().contains("Talos inspected the current workspace files"),
                        out.text());
                assertFalse(out.text().contains("repair/fix turn inspected files but did not change them"),
                        out.text());
                assertEquals(1, trace.events().stream()
                        .filter(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type()))
                        .filter(event -> "SATISFIED_BY_INSPECTION".equals(event.data().get("status")))
                        .count());
                assertEquals("NOT_RUN", trace.verification().status());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void conditionalReviewFixDoesNotConvertConcreteRepairClaimIntoNoChange(@TempDir Path workspace)
                throws Exception {
            writePassingBmiFixture(workspace);

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 8);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"styles.css\"}}",
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"scripts.js\"}}",
                            "I found an obvious issue in scripts.js that needs to be fixed.",
                            "I still will not edit files.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("[Action obligation failed:"), out.text());
            assertFalse(out.text().contains("No file change was needed"), out.text());
            assertTrue(Files.readString(workspace.resolve("scripts.js")).contains("weight / (height * height)"));
        }

        @Test
        void conditionalReviewFixStillRequiresMutationWhenCurrentStaticWebHasBlocker(@TempDir Path workspace)
                throws Exception {
            writePassingBmiFixture(workspace);
            Files.writeString(workspace.resolve("index.html"), """
                    <!doctype html>
                    <html>
                    <head><link rel="stylesheet" href="styles.css"></head>
                    <body>
                      <form id="bmi-form">
                        <input id="height" name="height">
                        <input id="weight" name="weight">
                        <button id="calculate" type="submit">Calculate</button>
                        <output id="result"></output>
                      </form>
                      <script src="script.js"></script>
                    </body>
                    </html>
                    """);

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 8);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"styles.css\"}}",
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"scripts.js\"}}",
                            "I inspected the BMI calculator and it is ready to use.",
                            "I still will not edit files.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("[Action obligation failed:"), out.text());
            assertFalse(out.text().contains("No file change was needed"), out.text());
            assertTrue(Files.readString(workspace.resolve("index.html")).contains("script.js"));
        }

        @Test
        void conditionalReviewFixCanInspectThenApplyConcreteRepair(@TempDir Path workspace)
                throws Exception {
            writePassingBmiFixture(workspace);
            Files.writeString(workspace.resolve("scripts.js"), """
                    const form = document.getElementById('bmi-form');
                    const result = document.getElementById('result');
                    form.addEventListener('submit', event => {
                      event.preventDefault();
                      result.textContent = 'BMI: pending';
                    });
                    """);

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 8);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"scripts.js\"}}",
                            """
                            {"name":"talos.edit_file","arguments":{"path":"scripts.js","old_string":"result.textContent = 'BMI: pending';","new_string":"const height = Number(document.getElementById('height').value) / 100;\\n  const weight = Number(document.getElementById('weight').value);\\n  const bmi = weight / (height * height);\\n  result.textContent = `BMI: ${bmi.toFixed(1)}`;"}}
                            """)))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(out.text().contains("[Action obligation failed:"), out.text());
            assertTrue(Files.readString(workspace.resolve("scripts.js"))
                    .contains("result.textContent = `BMI: ${bmi.toFixed(1)}`;"));
        }

        @Test
        void repairFixRetryWithStaticFullRewriteTargetEditFileGetsTypedWrongToolBreach(
                @TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("index.html"), """
                    <!doctype html>
                    <html>
                    <head><link rel="stylesheet" href="styles.css"></head>
                    <body><script src="scripts.js"></script></body>
                    </html>
                    """);
            Files.writeString(workspace.resolve("styles.css"), "body{}\n");
            Files.writeString(workspace.resolve("scripts.js"), "console.log('old');\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I reviewed the BMI calculator and it is ready to use.",
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"scripts.js\"}}",
                            "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"scripts.js\","
                                    + "\"old_string\":\"console.log('old');\","
                                    + "\"new_string\":\"console.log('fixed');\"}}",
                            "I fixed scripts.js and everything is complete.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create a complete static BMI calculator in this folder with index.html, "
                            + "styles.css, and scripts.js. It should calculate BMI from height and weight."));
            messages.add(ChatMessage.assistant("""
                    [Task incomplete: Static verification failed - HTML does not link JavaScript file: `scripts.js`]

                    The requested task is not verified complete.
                    Remaining static verification problems:
                    - HTML does not link JavaScript file: `scripts.js`
                    - Calculator/form task is missing a submit/calculate button.
                    """));
            messages.add(ChatMessage.user(
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser."));

            LocalTurnTraceCapture.begin(
                    "trc-t121-static-repair-wrong-tool",
                    "sid",
                    1,
                    "2026-05-04T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("static repair used the wrong mutation tool"),
                        out.text());
                assertTrue(out.text().contains("talos.write_file"), out.text());
                assertTrue(out.text().contains("scripts.js"), out.text());
                assertFalse(out.text().toLowerCase(java.util.Locale.ROOT).contains("ready to use"),
                        out.text());
                assertFalse(out.text().toLowerCase(java.util.Locale.ROOT).contains("everything is complete"),
                        out.text());
                assertEquals("console.log('old');\n", Files.readString(workspace.resolve("scripts.js")));
                assertEquals("BLOCKED", trace.outcome().status());
                assertEquals("BLOCKED_BY_POLICY", trace.outcome().classification());

                var failed = trace.events().stream()
                        .filter(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type()))
                        .filter(event -> "FAILED".equals(event.data().get("status")))
                        .reduce((first, second) -> second)
                        .orElseThrow();
                assertEquals("STATIC_REPAIR_WRONG_TOOL", failed.data().get("failureKind"));
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void repairFixRetryWithPartialMutationAndStaticFullRewriteTargetEditFileGetsTypedWrongToolBreach(
                @TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("index.html"), """
                    <!doctype html>
                    <html>
                    <head><link rel="stylesheet" href="styles.css"></head>
                    <body><script src="scripts.js"></script></body>
                    </html>
                    """);
            Files.writeString(workspace.resolve("styles.css"), "body{}\n");
            Files.writeString(workspace.resolve("scripts.js"), "console.log('old');\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I reviewed the BMI calculator and it is ready to use.",
                            """
                            {"name":"talos.write_file","arguments":{"path":"index.html","content":"<!doctype html>\\n<html>\\n<head><title>Partial Repair</title><link rel=\\"stylesheet\\" href=\\"styles.css\\"></head>\\n<body><button id=\\"calculate\\">Calculate</button><script src=\\"scripts.js\\"></script></body>\\n</html>\\n"}}
                            {"name":"talos.edit_file","arguments":{"path":"scripts.js","old_string":"console.log('old');\\n","new_string":"console.log('fixed');\\n"}}
                            """,
                            "I fixed scripts.js and everything is complete.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create a complete static BMI calculator in this folder with index.html, "
                            + "styles.css, and scripts.js. It should calculate BMI from height and weight."));
            messages.add(ChatMessage.assistant("""
                    [Task incomplete: Static verification failed - HTML does not link JavaScript file: `scripts.js`]

                    The requested task is not verified complete.
                    Remaining static verification problems:
                    - HTML does not link JavaScript file: `scripts.js`
                    - Calculator/form task is missing a submit/calculate button.
                    """));
            messages.add(ChatMessage.user(
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser."));

            LocalTurnTraceCapture.begin(
                    "trc-t122-partial-static-repair-wrong-tool",
                    "sid",
                    1,
                    "2026-05-04T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Review the BMI calculator you just created and fix any obvious issue "
                            + "that would stop it from working in a browser.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("static repair used the wrong mutation tool"),
                        out.text());
                assertTrue(out.text().contains("talos.write_file"), out.text());
                assertTrue(out.text().contains("scripts.js"), out.text());
                assertTrue(out.text().contains("Some files may have changed before this failure"),
                        out.text());
                assertFalse(out.text().toLowerCase(java.util.Locale.ROOT).contains("ready to use"),
                        out.text());
                assertFalse(out.text().toLowerCase(java.util.Locale.ROOT).contains("everything is complete"),
                        out.text());
                assertTrue(Files.readString(workspace.resolve("index.html")).contains("Partial Repair"));
                assertEquals("console.log('old');\n", Files.readString(workspace.resolve("scripts.js")));
                assertEquals("BLOCKED", trace.outcome().status());
                assertEquals("BLOCKED_BY_POLICY", trace.outcome().classification());

                var failed = trace.events().stream()
                        .filter(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type()))
                        .filter(event -> "FAILED".equals(event.data().get("status")))
                        .reduce((first, second) -> second)
                        .orElseThrow();
                assertEquals("STATIC_REPAIR_WRONG_TOOL", failed.data().get("failureKind"));
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void invalidMutationRetryAfterReadOnlyToolLoopFailsOutcome(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), "<h1>Old</h1>\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "I inspected index.html and updated it in this response.",
                            "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"index.html\","
                                    + "\"new_string\":\"<h1>Hello</h1>\"}}",
                            "I updated index.html.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Change index.html to say hello."));

            LocalTurnTraceCapture.begin(
                    "trc-t58-invalid-mutation-retry-after-read",
                    "sid",
                    1,
                    "2026-04-30T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Change index.html to say hello.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains(AssistantTurnExecutor.INVALID_MUTATION_ANNOTATION), out.text());
                assertEquals("<h1>Old</h1>\n", Files.readString(workspace.resolve("index.html")));
                assertEquals("FAILED", trace.outcome().status());
                assertEquals("FAILED", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void protectedReadDenialKeepsSecretOutAndBlocksOutcome(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve(".env"), "SECRET=manual-test\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, (description, detail) -> false, registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config(null))
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                            "The file says SECRET=manual-test.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read .env and tell me what it says."));

            LocalTurnTraceCapture.begin(
                    "trc-t57-protected-read",
                    "sid",
                    1,
                    "2026-04-30T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Read .env and tell me what it says.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("Protected content was not read"), out.text());
                assertFalse(out.text().contains("SECRET=manual-test"), out.text());
                assertEquals("PROTECTED_READ_APPROVAL_REQUIRED", trace.promptAudit().evidenceObligation());
                assertEquals("BLOCKED", trace.outcome().status());
                assertEquals("BLOCKED_BY_APPROVAL", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void escapedDotfileAliasUsesProtectedReadApprovalWhenCurrentTargetMatches(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve(".env"), "SECRET=manual-test\n");

            var approvals = new java.util.concurrent.atomic.AtomicInteger();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null,
                    (description, detail) -> {
                        approvals.incrementAndGet();
                        assertTrue(description.contains("protected read"), description);
                        assertTrue(detail.contains(".env"), detail);
                        return true;
                    },
                    registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config(null))
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"\\\\.env\"}}",
                            "The approved file says SECRET=manual-test.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read .env and tell me what it says."));

            LocalTurnTraceCapture.begin(
                    "trc-t194-escaped-dotfile-protected-read",
                    "sid",
                    1,
                    "2026-05-07T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Read .env and tell me what it says.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertEquals(1, approvals.get(), "escaped .env alias must still require explicit approval");
                assertTrue(out.text().contains("SECRET=manual-test"), out.text());
                assertFalse(out.text().contains("WORKSPACE_ESCAPE"), out.text());
                assertTrue(trace.events().stream().anyMatch(event ->
                        "TOOL_PATH_ARGUMENT_NORMALIZED".equals(event.type())
                                && ".env".equals(event.data().get("normalizedPath"))),
                        "trace should record escaped dotfile alias normalization");
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void escapedDotfileAliasRemainsBlockedWhenCurrentTargetDoesNotMatch(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve(".env"), "SECRET=manual-test\n");
            Files.writeString(workspace.resolve("README.md"), "Public readme\n");

            var approvals = new java.util.concurrent.atomic.AtomicInteger();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null,
                    (description, detail) -> {
                        approvals.incrementAndGet();
                        return true;
                    },
                    registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"\\\\.env\"}}",
                            "The file says SECRET=manual-test.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read README.md and tell me what it says."));

            LocalTurnTraceCapture.begin(
                    "trc-t194-escaped-dotfile-unmatched-target",
                    "sid",
                    1,
                    "2026-05-07T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Read README.md and tell me what it says.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertEquals(0, approvals.get(), "unmatched escaped .env must not be converted into an approval");
                assertFalse(out.text().contains("SECRET=manual-test"), out.text());
                assertTrue(trace.events().stream().anyMatch(event ->
                        "PERMISSION_DECISION".equals(event.type())
                                && "WORKSPACE_ESCAPE".equals(event.data().get("reasonCode"))),
                        "unmatched escaped .env should remain a workspace-escape denial");
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void explicitProtectedReadNoToolAnswerUsesRuntimeHandoffAndApproval(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve(".env"), "SECRET=manual-test\n");

            var approvals = new java.util.concurrent.atomic.AtomicInteger();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null,
                    (description, detail) -> {
                        approvals.incrementAndGet();
                        assertTrue(description.contains("protected read"), description);
                        assertTrue(detail.contains(".env"), detail);
                        return false;
                    },
                    registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config(null))
                    .llm(LlmClient.scripted(List.of(
                            "I can help with that.",
                            "The file says SECRET=manual-test.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Earlier, read .env and tell me what it says."));
            messages.add(ChatMessage.assistant("The approved file says SECRET=manual-test."));
            messages.add(ChatMessage.user("Read .env and tell me what it says."));

            LocalTurnTraceCapture.begin(
                    "trc-t72-protected-read-no-tool-handoff",
                    "sid",
                    1,
                    "2026-05-01T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Read .env and tell me what it says.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertEquals(1, approvals.get(), "no-tool protected read must still reach approval");
                assertTrue(out.text().contains("Protected content was not read"), out.text());
                assertFalse(out.text().contains("SECRET=manual-test"), out.text());
                assertEquals("PROTECTED_READ_APPROVAL_REQUIRED", trace.promptAudit().evidenceObligation());
                assertEquals("BLOCKED", trace.outcome().status());
                assertEquals("BLOCKED_BY_APPROVAL", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void explicitProtectedReadNoToolAnswerCanUseApprovedContent(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve(".env"), "SECRET=manual-test\n");

            var approvals = new java.util.concurrent.atomic.AtomicInteger();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null,
                    (description, detail) -> {
                        approvals.incrementAndGet();
                        assertTrue(description.contains("protected read"), description);
                        assertTrue(detail.contains(".env"), detail);
                        return true;
                    },
                    registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config(null))
                    .llm(LlmClient.scripted(List.of(
                            "I can help with that.",
                            "The approved file says SECRET=manual-test.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read .env and tell me what it says."));

            LocalTurnTraceCapture.begin(
                    "trc-t72-protected-read-no-tool-approved",
                    "sid",
                    1,
                    "2026-05-01T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Read .env and tell me what it says.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertEquals(1, approvals.get(), "no-tool protected read must ask before reading");
                assertTrue(out.text().contains("SECRET=manual-test"), out.text());
                assertFalse(out.text().contains("Protected content was not read"), out.text());
                assertEquals("PROTECTED_READ_APPROVAL_REQUIRED", trace.promptAudit().evidenceObligation());
                assertEquals("COMPLETE", trace.outcome().status());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void approvedProtectedReadRefusalUsesRuntimePostcondition(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve(".env"), "SECRET=manual-test\n");

            var approvals = new java.util.concurrent.atomic.AtomicInteger();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null,
                    (description, detail) -> {
                        approvals.incrementAndGet();
                        assertTrue(description.contains("protected read"), description);
                        assertTrue(detail.contains(".env"), detail);
                        return true;
                    },
                    registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config(null))
                    .llm(LlmClient.scripted(List.of(
                            "I can help with that.",
                            "I'm sorry, but I can't provide that.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read .env and tell me what it says."));

            LocalTurnTraceCapture.begin(
                    "trc-t124-protected-read-refusal-postcondition",
                    "sid",
                    1,
                    "2026-05-05T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Read .env and tell me what it says.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertEquals(1, approvals.get(), "protected read still requires explicit approval");
                assertTrue(out.text().contains("SECRET=manual-test"), out.text());
                assertFalse(out.text().contains("can't provide"), out.text());
                assertFalse(out.text().toLowerCase(java.util.Locale.ROOT).contains("complete"), out.text());
                assertEquals("ADVISORY_ONLY", trace.outcome().classification());
                assertTrue(trace.warnings().stream().anyMatch(warning ->
                        "APPROVED_PROTECTED_READ_POSTCONDITION".equals(warning.code())));
                assertTrue(trace.events().stream().anyMatch(event ->
                        "PROTECTED_READ_POSTCONDITION_CHECKED".equals(event.type())));
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void mixedProtectedAndPublicReadNoToolHandoffReadsAllExpectedTargetsAfterApproval(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve(".env"), "SECRET=manual-test\n");
            Files.writeString(workspace.resolve("README.md"), "Public project notes.\n");

            var approvals = new java.util.concurrent.atomic.AtomicInteger();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null,
                    (description, detail) -> {
                        approvals.incrementAndGet();
                        assertTrue(description.contains("protected read"), description);
                        assertTrue(detail.contains(".env"), detail);
                        return true;
                    },
                    registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config(null))
                    .llm(LlmClient.scripted(List.of(
                            "I can help with that.",
                            "The approved files say SECRET=manual-test and Public project notes.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read .env and README.md and tell me what both say."));

            LocalTurnTraceCapture.begin(
                    "trc-t82-mixed-protected-public-read-handoff",
                    "sid",
                    1,
                    "2026-05-02T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Read .env and README.md and tell me what both say.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertEquals(1, approvals.get(), "mixed protected/public read should ask only for protected target");
                assertTrue(out.text().contains("SECRET=manual-test"), out.text());
                assertTrue(out.text().contains("Public project notes"), out.text());
                assertTrue(out.text().contains("talos.read_file"), out.text());
                assertFalse(out.text().contains("[Evidence incomplete:"), out.text());
                assertEquals("PROTECTED_READ_APPROVAL_REQUIRED", trace.promptAudit().evidenceObligation());
                assertEquals("COMPLETE", trace.outcome().status());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void streamingProtectedReadNoToolAnswerUsesBufferedRecoveryAndApproval(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve(".env"), "SECRET=manual-test\n");

            var visibleChunks = new ArrayList<String>();
            var approvals = new java.util.concurrent.atomic.AtomicInteger();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null,
                    (description, detail) -> {
                        approvals.incrementAndGet();
                        assertTrue(description.contains("protected read"), description);
                        assertTrue(detail.contains(".env"), detail);
                        return true;
                    },
                    registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config(null))
                    .llm(LlmClient.scripted(List.of(
                            "I cannot access local files directly.",
                            "The approved file says SECRET=manual-test.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .streamSink(visibleChunks::add)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read .env and tell me the value inside."));

            LocalTurnTraceCapture.begin(
                    "trc-t77-protected-read-streaming-recovery",
                    "sid",
                    1,
                    "2026-05-02T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Read .env and tell me the value inside.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertFalse(out.streamed(),
                        "protected read turns should buffer so approval can run before user-visible prose");
                assertTrue(visibleChunks.isEmpty(),
                        "initial no-tool prose must not consume the approval response slot");
                assertEquals(1, approvals.get(), "protected read recovery must still ask approval");
                assertTrue(out.text().contains("SECRET=manual-test"), out.text());
                assertFalse(out.text().contains("not attempted"), out.text());
                assertEquals("PROTECTED_READ_APPROVAL_REQUIRED", trace.promptAudit().evidenceObligation());
                assertEquals("COMPLETE", trace.outcome().status());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void protectedTargetMentionWithoutReadIntentDoesNotTriggerRuntimeHandoff(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve(".env"), "SECRET=manual-test\n");
            Files.writeString(workspace.resolve("README.md"), "Public readme\n");

            var approvals = new java.util.concurrent.atomic.AtomicInteger();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null,
                    (description, detail) -> {
                        approvals.incrementAndGet();
                        return true;
                    },
                    registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of("README is the target.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("I do not want the .env, I want the README.md !"));

            LocalTurnTraceCapture.begin(
                    "trc-t72-protected-target-mention-no-handoff",
                    "sid",
                    1,
                    "2026-05-01T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "I do not want the .env, I want the README.md !");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertEquals(0, approvals.get(), "negated protected target mention must not ask for read approval");
                assertFalse(out.text().contains("SECRET=manual-test"), out.text());
                assertEquals("READ_TARGET_REQUIRED", trace.promptAudit().evidenceObligation());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void staleProtectedContentFromEarlierTurnIsSuppressedWithoutFreshApproval(@TempDir Path workspace)
                throws Exception {
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "The earlier approved file said TALOS_T61B_SECRET=visible-only-after-approval.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read .env and tell me what it says."));
            messages.add(ChatMessage.assistant("The approved file says TALOS_T61B_SECRET=visible-only-after-approval."));
            messages.add(ChatMessage.user("Please review it"));

            LocalTurnTraceCapture.begin(
                    "trc-t73-stale-protected-content",
                    "sid",
                    2,
                    "2026-05-01T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Please review it");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertFalse(out.text().contains("visible-only-after-approval"), out.text());
                assertTrue(out.text().contains("protected content from an earlier approved read"), out.text());
                assertTrue(trace.warnings().stream()
                        .anyMatch(warning -> "PROTECTED_HISTORY_SUPPRESSED".equals(warning.code())),
                        trace.warnings().toString());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void unsupportedPptxReadReportsCapabilityWithoutClaimingSummary(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("slides.pptx"), "fake-binary-pptx-placeholder");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"slides.pptx\"}}",
                            "The report says PROFIT-ALPHA.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Can you read slides.pptx and summarize it?"));

            LocalTurnTraceCapture.begin(
                    "trc-t57-unsupported-pptx",
                    "sid",
                    1,
                    "2026-04-30T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Can you read slides.pptx and summarize it?");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().toLowerCase(java.util.Locale.ROOT)
                        .contains("unsupported binary document"), out.text());
                assertFalse(out.text().contains("PROFIT-ALPHA"), out.text());
                assertEquals("UNSUPPORTED_CAPABILITY_CHECK_REQUIRED", trace.promptAudit().evidenceObligation());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void unsupportedOnlyNamedPptxTargetPreflightsBeforeDriftingModelReads(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("slides.pptx"), "fake-binary-pptx-placeholder");
            Files.writeString(workspace.resolve("README.md"), "README-SECRET should not be read.\n");
            Files.writeString(workspace.resolve("notes.md"), "NOTES-SECRET should not be read.\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.ListDirTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"notes.md\"}}",
                            "README says README-SECRET. Notes say NOTES-SECRET.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("What files are here?"));
            messages.add(ChatMessage.assistant("Directory entries:\n- README.md\n- notes.md\n- slides.pptx"));
            messages.add(ChatMessage.user("Summarize slides.pptx."));

            LocalTurnTraceCapture.begin(
                    "trc-t90-unsupported-pptx-preflight",
                    "sid",
                    2,
                    "2026-05-02T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "scripted",
                    "test-model",
                    "Summarize slides.pptx.");
            TurnAuditCapture.begin();
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                var audit = TurnAuditCapture.end();
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("[Document capability note:"), out.text());
                assertTrue(out.text().contains("slides.pptx"), out.text());
                assertFalse(out.text().contains("README-SECRET"), out.text());
                assertFalse(out.text().contains("NOTES-SECRET"), out.text());
                assertEquals("UNSUPPORTED_CAPABILITY_CHECK_REQUIRED", trace.promptAudit().evidenceObligation());
                assertEquals(List.of("talos.read_file"),
                        audit.toolCalls().stream().map(dev.talos.runtime.TurnRecord.ToolCallSummary::name).toList());
                assertEquals(List.of("slides.pptx"),
                        audit.toolCalls().stream().map(dev.talos.runtime.TurnRecord.ToolCallSummary::pathHint).toList());
            } finally {
                if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void unsupportedDocxCreationRequestReturnsCapabilityAnswerWithoutProviderOrFakeFile(
                @TempDir Path workspace) throws Exception {
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scriptedFailure(new RuntimeException("provider should not be called")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "okay I want your help with a doc file. can you create a docx file about "
                            + "how a cool looking synthwave webpage for a band should be created?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("cannot create valid Microsoft Word .docx files"), out.text());
            assertTrue(out.text().contains("No file was changed"), out.text());
            assertFalse(out.text().contains("provider should not be called"), out.text());
            try (var entries = Files.list(workspace)) {
                assertTrue(entries.findAny().isEmpty(),
                        "unsupported DOCX creation must not create a fake file");
            }
        }

        @Test
        void unsupportedPdfFormatRequestReturnsCapabilityAnswerWithoutProviderOrFakeFile(
                @TempDir Path workspace) throws Exception {
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scriptedFailure(new RuntimeException("provider should not be called")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "oh I was wrong... I want you to delete the docx file and make the same thing "
                            + "but in pdf format please."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("cannot create valid PDF files"), out.text());
            assertTrue(out.text().contains("No file was changed"), out.text());
            assertFalse(out.text().contains("provider should not be called"), out.text());
            assertFalse(Files.exists(workspace.resolve("synthwave_band_webpage.pdf")));
        }

        @Test
        void unsupportedPdfCreationLivePhraseReturnsCapabilityAnswerWithoutProviderOrFallbackFile(
                @TempDir Path workspace) throws Exception {
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scriptedFailure(new RuntimeException("provider should not be called")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "0I want to create a pdf with instructions for me on how to create a bmi calculator web page!"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("cannot create valid PDF files"), out.text());
            assertTrue(out.text().contains("No file was changed"), out.text());
            assertFalse(out.text().contains("provider should not be called"), out.text());
            try (var entries = Files.list(workspace)) {
                assertTrue(entries.findAny().isEmpty(), "unsupported PDF request must not create fallback files");
            }
        }

        @Test
        void markdownSummaryFromOfficeDocumentSourcesDoesNotTriggerUnsupportedBinaryCreationAnswer(
                @TempDir Path workspace) {
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("No tool call from provider."))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create office-summary.md summarizing board-brief.pdf, client-notes.docx, and revenue.xlsx."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(out.text().contains("cannot create valid PDF files"), out.text());
            assertFalse(out.text().contains("cannot create valid Microsoft Word .docx files"), out.text());
            assertFalse(out.text().contains("cannot create valid Microsoft Excel .xlsx files"), out.text());
        }

        @Test
        void unsupportedPdfCreationFollowUpReturnsCapabilityAnswerWithoutProviderOrFallbackFile(
                @TempDir Path workspace) throws Exception {
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scriptedFailure(new RuntimeException("provider should not be called")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("you should create the pdf guide!"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("cannot create valid PDF files"), out.text());
            assertTrue(out.text().contains("No file was changed"), out.text());
            assertFalse(out.text().contains("provider should not be called"), out.text());
            assertFalse(Files.exists(workspace.resolve("pdf_guide.md")));
        }

        @Test
        void unsupportedPdfCapabilityQuestionUsesTalosProductAnswer() {
            var ctx = scriptedContext(
                    "As an AI text-based model, I don't have the capability to directly create PDF files.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("so you cannot create pdf ?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("Talos cannot create valid PDF files"), out.text());
            assertTrue(out.text().contains("Markdown"), out.text());
            assertFalse(out.text().toLowerCase().contains("as an ai"), out.text());
            assertFalse(out.text().toLowerCase().contains("text-based model"), out.text());
        }

        @Test
        void unsupportedBinaryDocumentWriteIsRejectedBeforeApproval(@TempDir Path workspace)
                throws Exception {
            var approvals = new java.util.concurrent.atomic.AtomicInteger();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null,
                    (description, detail) -> {
                        approvals.incrementAndGet();
                        return true;
                    },
                    registry);
            var ctx = Context.builder(new Config())
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .build();
            var session = new dev.talos.runtime.Session(workspace, new Config());
            var request = "Create sample.pdf containing hello.";

            dev.talos.runtime.TurnUserRequestCapture.set(request);
            dev.talos.runtime.TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            try {
                dev.talos.tools.ToolResult result = processor.executeTool(
                        session,
                        new dev.talos.tools.ToolCall("talos.write_file", java.util.Map.of(
                                "path", "sample.pdf",
                                "content", "hello")),
                        ctx);

                assertFalse(result.success());
                assertEquals(dev.talos.tools.ToolError.UNSUPPORTED_FORMAT, result.error().code());
                assertTrue(result.errorMessage().contains("cannot create valid PDF files"),
                        result.errorMessage());
                assertEquals(0, approvals.get(), "unsupported write must not ask for approval");
                assertFalse(Files.exists(workspace.resolve("sample.pdf")));
            } finally {
                dev.talos.runtime.TurnUserRequestCapture.clear();
                dev.talos.runtime.TurnTaskContractCapture.clear();
            }
        }

        @Test
        void smallTalkTextFallbackToolCallIsNotExecuted(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("notes.md"), "Hidden project token: ALPHA-742\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"notes.md\"}}")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("hello, answer briefly as Talos"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(out.text().contains("talos.read_file"), out.text());
            assertFalse(out.text().contains("ALPHA-742"), out.text());
            assertFalse(out.text().contains("Used 1 tool"), out.text());
        }

        @Test
        void malformedSingleQuotedToolProtocolIsReplacedWithoutMutation(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("scripts.js"), """
                    document.querySelector("#wrongButton").addEventListener("click", () => {
                      console.log("wrong");
                    });
                    """);

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of("""
                            {
                              "name": "talos.edit_file",
                              "arguments": {
                                "path": "scripts.js",
                                "old_string": 'document.querySelector("#wrongButton").addEventListener("click", () => {',
                                "new_string": 'document.querySelector("button").addEventListener("click", () => {'
                              }
                            }
                            """)))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "My BMI page is almost there, but when I press the button nothing happens. "
                            + "Please keep the look the same and just make the button work."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertEquals(AssistantTurnExecutor.MALFORMED_TOOL_PROTOCOL_REPLACEMENT, out.text());
            assertFalse(out.text().contains("talos.edit_file"), out.text());
            assertFalse(out.text().contains("old_string"), out.text());
            assertTrue(Files.readString(workspace.resolve("scripts.js")).contains("#wrongButton"),
                    "malformed protocol must not mutate files");
        }

        @Test
        void malformedBackendToolArgumentsAreFailureDominantAndTraceDiagnosed(@TempDir Path workspace)
                throws Exception {
            Path script = workspace.resolve("scripts.js");
            Files.writeString(script, "console.log('old');\n");
            String malformedPayload = """
                    {"path":"scripts.js","content":"SHOULD_NOT_APPEAR","patient":"Eleni Nikolaou"
                    """;
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scriptedFailure(new EngineException.MalformedResponse(
                            "compat chat stream tool arguments",
                            malformedPayload)))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Overwrite scripts.js with exactly console.log('new');"));

            LocalTurnTraceCapture.begin(
                    "trc-malformed-compat",
                    "session",
                    1,
                    "2026-05-06T00:00:00Z",
                    "workspace",
                    "ask",
                    "llama_cpp",
                    "qwen2.5-coder-14b.gguf",
                    "Overwrite scripts.js with exactly console.log('new');");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("Malformed engine response for compat chat stream tool arguments"),
                        out.text());
                assertFalse(out.text().contains("SHOULD_NOT_APPEAR"), out.text());
                assertFalse(out.text().toLowerCase(java.util.Locale.ROOT).contains("ready to use"), out.text());
                assertEquals("console.log('old');\n", Files.readString(script),
                        "malformed tool arguments must not mutate files");
                assertEquals("BACKEND_MALFORMED_RESPONSE", trace.outcome().classification());
                var malformedEvent = trace.events().stream()
                        .filter(event -> "BACKEND_MALFORMED_RESPONSE_CAPTURED".equals(event.type()))
                        .findFirst()
                        .orElseThrow();
                assertEquals("compat chat stream tool arguments", malformedEvent.data().get("context"));
                assertEquals(malformedPayload.length(), malformedEvent.data().get("bodyChars"));
                assertTrue(String.valueOf(malformedEvent.data().get("bodyHash")).startsWith("sha256:"));
                assertFalse(malformedEvent.data().containsKey("bodyPreview"), malformedEvent.data().toString());
                assertFalse(malformedEvent.data().toString().contains("SHOULD_NOT_APPEAR"),
                        malformedEvent.data().toString());
                assertFalse(malformedEvent.data().toString().contains("Eleni Nikolaou"),
                        malformedEvent.data().toString());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void malformedStreamedToolArgumentsRecoverWithNonStreamingToolCallAndExecuteMutation(
                @TempDir Path workspace) throws Exception {
            Path script = workspace.resolve("scripts.js");
            Files.writeString(script, "console.log('old');");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var llm = ScriptedNativeLlmClient.compatMalformedStreamThenNonStreamingRecovery(
                    new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                            "call_1",
                            "talos.write_file",
                            java.util.Map.of("path", "scripts.js", "content", "console.log('new');")))),
                    List.of(new LlmClient.StreamResult("Updated scripts.js.", List.of())));
            var ctx = Context.builder(new Config())
                    .llm(llm)
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .nativeToolSpecs(List.of(new ToolSpec(
                            "talos.write_file",
                            "Write a file.",
                            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}")))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Overwrite scripts.js with exactly console.log('new');"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertEquals("console.log('new');", Files.readString(script));
            assertTrue(out.text().contains("Updated scripts.js"), out.text());
            assertFalse(out.text().contains("Malformed engine response"), out.text());
            assertFalse(out.text().toLowerCase(java.util.Locale.ROOT).contains("ready to use"), out.text());
        }

        @Test
        void readOnlyDeniedWriteFileProtocolIsSanitizedWithoutFakeApproval(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), "<h1>Current</h1>\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            String prompt = "Can you look at this page and tell me what is wrong? Do not edit files yet.";
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            """
                            ```json
                            {"name":"talos.write_file","arguments":{"path":"index.html","content":"<h1>Changed</h1>"}}
                            ```
                            Do you approve these changes?
                            """,
                            """
                            I prepared the update.

                            ```json
                            {"name":"talos.write_file","arguments":{"path":"index.html","content":"<h1>Changed</h1>"}}
                            ```

                            Do you approve these changes?
                            """)))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(prompt));

            dev.talos.runtime.TurnUserRequestCapture.set(prompt);
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());

                assertTrue(out.text().contains("read-only"), out.text());
                assertTrue(out.text().contains("No file changes were applied"), out.text());
                assertFalse(out.text().contains("\"name\""), out.text());
                assertFalse(out.text().contains("\"arguments\""), out.text());
                assertFalse(out.text().contains("Do you approve these changes"), out.text());
                assertFalse(out.text().contains("I prepared the update"), out.text());
                assertEquals("<h1>Current</h1>\n", Files.readString(workspace.resolve("index.html")));
            } finally {
                dev.talos.runtime.TurnUserRequestCapture.clear();
            }
        }

        @Test
        void readOnlyDeniedEditFileProtocolIsSanitizedWithoutFakeApproval(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), "<h1>Current</h1>\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileEditTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            String prompt = "Can you diagnose this page without changing files?";
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            """
                            ```json
                            {"name":"talos.edit_file","arguments":{"path":"index.html","old_string":"<h1>Current</h1>","new_string":"<h1>Changed</h1>"}}
                            ```
                            Would you like me to apply these changes?
                            """,
                            "Please approve these changes so I can apply them.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(prompt));

            dev.talos.runtime.TurnUserRequestCapture.set(prompt);
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());

                assertTrue(out.text().contains("read-only"), out.text());
                assertTrue(out.text().contains("No file changes were applied"), out.text());
                assertFalse(out.text().contains("\"name\""), out.text());
                assertFalse(out.text().contains("\"arguments\""), out.text());
                assertFalse(out.text().contains("Please approve these changes"), out.text());
                assertFalse(out.text().contains("Would you like me to apply"), out.text());
                assertEquals("<h1>Current</h1>\n", Files.readString(workspace.resolve("index.html")));
            } finally {
                dev.talos.runtime.TurnUserRequestCapture.clear();
            }
        }

        @Test
        void workspaceExplainListOnlyUnderinspectionRetriesWithPrimaryReads(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), """
                    <!doctype html>
                    <html>
                      <head><link rel="stylesheet" href="style.css"></head>
                      <body><h1>Night Drive</h1><a class="cta" href="#listen">Listen</a><script src="script.js"></script></body>
                    </html>
                    """);
            Files.writeString(workspace.resolve("style.css"), ".cta { color: #ff4fd8; }\n");
            Files.writeString(workspace.resolve("script.js"), "document.querySelector('.cta').dataset.ready = 'true';\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ListDirTool());
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}",
                            "The folder contains index.html, style.css, and script.js, so it is a basic website.",
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"style.css\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"script.js\"}}",
                            "This is a Night Drive landing page. index.html defines the call-to-action link, style.css styles it, and script.js marks the CTA as ready.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "I'm not a developer. What is this folder for? Please explain the website in plain English."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertEquals(1, countOccurrences(out.text(), "[Used "), out.text());
            assertTrue(out.text().contains(
                    "[Used 4 tool(s): talos.list_dir, talos.read_file | 2 iteration(s)"),
                    out.text());
            assertTrue(out.text().contains("read: index.html, script.js, style.css"), out.text());
            assertTrue(out.text().contains("Night Drive landing page"), out.text());
            assertTrue(out.text().contains("style.css styles it"), out.text());
            assertFalse(out.text().contains("basic website"), out.text());
        }

        @Test
        void workspaceExplainDisclosesUnreadRootCandidateAfterSubdirectoryOnlyReads(@TempDir Path workspace)
                throws Exception {
            Files.createDirectories(workspace.resolve("notes"));
            Files.writeString(workspace.resolve("notes/project-notes.md"), """
                    Project is preparing a public beta.
                    Next action: clean the release notes.
                    """);
            Files.writeString(workspace.resolve("notes/meeting-notes.md"), """
                    Meeting decision: smoke the installer.
                    Next action: verify download commands.
                    """);
            Files.writeString(workspace.resolve("todos.md"), """
                    HIGH PRIORITY: write the rollback runbook.
                    HIGH PRIORITY: verify checksums.
                    HIGH PRIORITY: publish install guide.
                    """);

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ListDirTool());
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"notes\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"notes/project-notes.md\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"notes/meeting-notes.md\"}}",
                            """
                            The top three next actions are clean the release notes, smoke the installer, and verify download commands.
                            """)))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "What are the top three next actions for this project based on this workspace?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertEquals(1, countOccurrences(out.text(), "[Used "), out.text());
            assertTrue(out.text().contains(
                    "read: notes/meeting-notes.md, notes/project-notes.md"), out.text());
            assertTrue(out.text().contains(
                    "2 of 3 workspace candidate files read, unread: todos.md"), out.text());
            assertTrue(out.text().contains("clean the release notes"), out.text());
        }

        @Test
        void workspaceExplainWithoutReadToolsDisclosesNoWorkspaceReads(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("README.md"), "Talos fixture workspace.\n");
            var ctx = scriptedContext("This workspace appears to be a local CLI project.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("What is this project?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("answered without reading workspace files"), out.text());
        }

        @Test
        void verifyOnlyNoToolAnswerRetriesBeforeConfirming(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), """
                    <!doctype html>
                    <html>
                      <head><link rel="stylesheet" href="style.css"></head>
                      <body><h1>BMI</h1><script src="script.js"></script></body>
                    </html>
                    """);
            Files.writeString(workspace.resolve("style.css"), "body { font-family: sans-serif; }\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ListDirTool());
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I can't provide a definitive answer without being able to see and analyze the files myself.",
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"style.css\"}}",
                            "Confirmed from the files: the page is incomplete because index.html references script.js, but only index.html and style.css are present.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "It looks like it is a non-completed web page right? Can you confirm that?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("[Used 3 tool(s): talos.list_dir, talos.read_file"),
                    out.text());
            assertTrue(out.text().contains("I inspected the current-turn static web files"), out.text());
            assertTrue(out.text().contains(
                    "index.html: linked JavaScript was not inspected in this turn: `script.js`"),
                    out.text());
            assertFalse(out.text().contains("Confirmed from the files"), out.text());
            assertFalse(out.text().contains("without being able to see"), out.text());
        }

        @Test
        void verifyOnlyWebCompletionUsesStaticDiagnostics(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), """
                    <!doctype html>
                    <html>
                      <head><link rel="stylesheet" href="style.css"></head>
                      <body><h1>Horror Synthwave Band</h1><script src="script.js"></script></body>
                    </html>
                    """);
            Files.writeString(workspace.resolve("style.css"), ".cta-button { color: #ff4fd8; }\n");
            Files.writeString(workspace.resolve("script.js"), "document.querySelector('.cta-button').addEventListener('click', () => {});\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ListDirTool());
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"style.css\"}}\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"script.js\"}}",
                            "The website appears complete and well structured.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "It looks like it is a web page right? Can you confirm if it is complete? Do not change anything."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("Static web diagnostics found"), out.text());
            assertTrue(out.text().contains(".cta-button"), out.text());
            assertTrue(out.text().contains("No files were changed."), out.text());
            assertFalse(out.text().contains("appears complete"), out.text());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Streaming path (with streamSink)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Streaming path")
    class Streaming {

        @Test
        void returns_answer_and_marks_streamed() {
            var chunks = new ArrayList<String>();
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("streamed answer"))
                    .streamSink(chunks::add)
                    .build();
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options();

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertFalse(out.text().isBlank(), "Should return non-empty text");
            assertTrue(out.streamed(), "Streaming path should be marked streamed");
            assertFalse(chunks.isEmpty(), "Stream sink should have received chunks");
        }

        @Test
        void streamed_text_matches_returned_text() {
            var chunks = new ArrayList<String>();
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("streamed parity"))
                    .streamSink(chunks::add)
                    .build();
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options();

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            String streamed = String.join("", chunks);
            assertEquals(streamed, out.text(),
                    "Returned text should match what was streamed");
        }

        @Test
        void streamingIdentityQuestionEmitsTalosIdentity() {
            var chunks = new ArrayList<String>();
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("I'm Qwen, made by Alibaba Cloud."))
                    .streamSink(chunks::add)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("You are Talos."));
            messages.add(ChatMessage.user("who are you?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            String visible = String.join("", chunks);
            assertTrue(out.streamed(), "identity response should use the visible streaming path");
            assertEquals(visible, out.text());
            assertTrue(out.text().contains("Talos"), out.text());
            assertFalse(out.text().toLowerCase().contains("qwen"), out.text());
            assertFalse(out.text().toLowerCase().contains("alibaba"), out.text());
        }

        @Test
        void stream_filter_hides_bare_json_while_tool_loop_still_executes(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), "<h1>Hello</h1>");

            var visibleChunks = new ArrayList<String>();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var streamFilter = new dev.talos.runtime.ToolCallStreamFilter(visibleChunks::add);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I will inspect.\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "The file contains Hello.")))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .streamSink(streamFilter)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("How does dependency injection work in Java?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            String visible = String.join("", visibleChunks);
            assertFalse(visible.contains("\"name\""),
                    "bare tool-call JSON must not be visible in streamed output");
            assertFalse(visible.contains("talos.read_file"),
                    "tool protocol must be suppressed from streamed output");
            assertTrue(visible.contains("I will inspect."),
                    "ordinary prose before the tool call should remain visible");
            assertFalse(visible.contains("The file contains Hello."),
                    "tool-loop follow-up prose should not stream before final answer shaping");
            assertTrue(out.text().contains("The file contains Hello."),
                    "raw response must still enter the tool loop and complete normally");
        }

        @Test
        void reprompt_stream_filter_flushes_protocol_debris_between_turns(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), "<h1>Hello</h1>");

            var visibleChunks = new ArrayList<String>();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var streamFilter = new dev.talos.runtime.ToolCallStreamFilter(visibleChunks::add);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "```json\n\n```",
                            "plain second turn")))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .streamSink(streamFilter)
                    .build();

            AssistantTurnExecutor.execute(new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user("How does dependency injection work in Java?"))), workspace, ctx,
                    new AssistantTurnExecutor.Options());
            AssistantTurnExecutor.execute(new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user("Say hello."))), workspace, ctx,
                    new AssistantTurnExecutor.Options());

            String visible = String.join("", visibleChunks);
            assertFalse(visible.contains("```json"),
                    "empty protocol fence buffered during a tool-loop reprompt must not leak into the next turn");
            assertTrue(visible.contains("plain second turn"),
                    "the next normal streamed turn should still be visible");
        }

        @Test
        void malformed_protocol_array_is_hidden_and_replaced_on_streaming_no_tool_path() {
            var visibleChunks = new ArrayList<String>();
            var streamFilter = new dev.talos.runtime.ToolCallStreamFilter(visibleChunks::add);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("""
                            [
                                ,

                            ]
                            """))
                    .streamSink(streamFilter)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Explain what edit you would make. Do not change files."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            String visible = String.join("", visibleChunks);
            assertFalse(dev.talos.runtime.ToolCallParser.looksLikeMalformedProtocolArrayDebris(visible),
                    "malformed protocol array must not be visible in streamed output");
            assertFalse(visible.contains("\n    ,"),
                    "the raw comma-only protocol array body must not be visible");
            assertTrue(visible.contains("invalid tool-call payload"),
                    "streamed user-visible output should contain the truthful replacement");
            assertEquals(AssistantTurnExecutor.MALFORMED_TOOL_PROTOCOL_REPLACEMENT, out.text());
            assertTrue(out.streamed());
        }

        @Test
        void explicitMutationWithStreamSinkUsesBufferedRetryPath(@TempDir Path workspace)
                throws Exception {
            var visibleChunks = new ArrayList<String>();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "Create `script.js` with this JavaScript code.",
                            "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"script.js\","
                                    + "\"content\":\"document.body.dataset.ready = 'stream-buffered';\"}}",
                            "Created script.js.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .streamSink(visibleChunks::add)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Create the script.js file you need in this workspace."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(out.streamed(),
                    "mutation turns should be buffered so advisory no-tool prose is not printed first");
            assertTrue(visibleChunks.isEmpty(),
                    "initial advisory no-tool prose must not reach the stream sink");
            assertTrue(Files.exists(workspace.resolve("script.js")));
            assertEquals("document.body.dataset.ready = 'stream-buffered';",
                    Files.readString(workspace.resolve("script.js")));
            assertTrue(out.text().contains("[Used 1 tool(s): talos.write_file"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Answer sanitization and truncation
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Sanitization and truncation")
    class SanitizationAndTruncation {

        @Test
        void answer_sanitizer_is_applied() {
            var ctx = scriptedContext("raw answer");
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options()
                    .answerSanitizer(s -> "SANITIZED:" + s);

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertTrue(out.text().startsWith("SANITIZED:"),
                    "Sanitizer should have been applied: " + out.text());
        }

        @Test
        void response_truncated_when_over_max_chars() {
            var ctx = scriptedContext("long answer");
            // Use a question that generates a longer PLACEHOLDER response
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("You are a helpful assistant."));
            messages.add(ChatMessage.user("Explain the concept of dependency injection in software engineering"));
            // responseMaxChars(1) ensures any non-trivial answer gets truncated
            var opts = new AssistantTurnExecutor.Options().responseMaxChars(1);

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertTrue(out.text().contains("[output truncated]"),
                    "Should contain truncation marker: " + out.text());
        }

        @Test
        void null_sanitizer_treated_as_identity() {
            var ctx = scriptedContext("identity answer");
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options().answerSanitizer(null);

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertFalse(out.text().isBlank(), "Should still return text with null sanitizer");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Error handling (structural verification)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        /**
         * Verifies the execute method catches exceptions without propagating.
         * Since LlmClient is final and PLACEHOLDER mode doesn't throw,
         * we verify error-path behavior by wrapping execute in a context
         * where the CompletableFuture times out (very short timeout).
         */
        @Test
        void extremely_short_timeout_triggers_timeout_handling() {
            var ctx = scriptedContext("fast answer");
            var messages = basicMessages();
            // 1ms timeout - PLACEHOLDER is fast enough that this might not trigger,
            // but verifies the timeout wiring exists without errors
            var opts = new AssistantTurnExecutor.Options().llmTimeoutMs(1L);

            // Should not throw - errors are caught internally
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);
            assertNotNull(out.text(), "Should always return non-null text");
        }

        @Test
        void execute_never_throws_to_caller() {
            // Even with a minimal context, execute should never propagate exceptions
            var ctx = scriptedContext("no throw");
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options();

            assertDoesNotThrow(
                    () -> AssistantTurnExecutor.execute(messages, WS, ctx, opts),
                    "Execute must catch all exceptions internally");
        }

        @Test
        void response_error_under_mutation_records_backend_failure_outcome() {
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scriptedFailure(new EngineException.ResponseError(
                            400,
                            "invalid request payload")))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("system"));
            messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));

            LocalTurnTraceCapture.begin(
                    "trc-engine-response-error",
                    "sid",
                    1,
                    "2026-05-03T00:00:00Z",
                    "workspace-hash",
                    "test",
                    "llama_cpp",
                    "qwen2.5-coder-14b",
                    "Overwrite index.html with exactly AFTER. Use talos.write_file.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, WS, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("Engine error"), out.text());
                assertNoSuccessProse(out.text());
                assertEquals("FAILED", trace.outcome().status());
                assertEquals("BACKEND_RESPONSE_ERROR", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void llama_cpp_context_overflow_records_context_budget_failure_outcome() {
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scriptedFailure(new EngineException.ResponseError(
                            400,
                            "request (4383 tokens) exceeds the available context size (4096 tokens)")))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("system"));
            messages.add(ChatMessage.user("Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js."));

            LocalTurnTraceCapture.begin(
                    "trc-context-budget",
                    "sid",
                    1,
                    "2026-05-03T00:00:00Z",
                    "workspace-hash",
                    "test",
                    "llama_cpp",
                    "qwen2.5-coder-14b",
                    "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, WS, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("Engine error"), out.text());
                assertNoSuccessProse(out.text());
                assertEquals("FAILED", trace.outcome().status());
                assertEquals("CONTEXT_BUDGET_EXCEEDED", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void local_context_budget_preflight_failure_is_failure_dominant() {
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scriptedFailure(new EngineException.ContextBudgetExceeded(
                            8500, 5634, 8192, 42)))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("system"));
            messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));

            LocalTurnTraceCapture.begin(
                    "trc-context-budget-preflight",
                    "sid",
                    1,
                    "2026-05-07T00:00:00Z",
                    "workspace-hash",
                    "test",
                    "llama_cpp",
                    "qwen2.5-coder-14b",
                    "Overwrite index.html with exactly AFTER. Use talos.write_file.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, WS, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("Context budget exceeded"), out.text());
                assertFalse(out.text().contains("Engine error"), out.text());
                assertNoSuccessProse(out.text());
                assertEquals("FAILED", trace.outcome().status());
                assertEquals("CONTEXT_BUDGET_EXCEEDED", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void connection_failure_under_mutation_records_backend_failure_outcome() {
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scriptedFailure(new EngineException.ConnectionFailed(
                            "llama.cpp server exited before readiness",
                            null)))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("system"));
            messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));

            LocalTurnTraceCapture.begin(
                    "trc-engine-connection-failed",
                    "sid",
                    1,
                    "2026-05-03T00:00:00Z",
                    "workspace-hash",
                    "test",
                    "llama_cpp",
                    "gpt-oss-20b",
                    "Overwrite index.html with exactly AFTER. Use talos.write_file.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, WS, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("Model engine not reachable"), out.text());
                assertNoSuccessProse(out.text());
                assertEquals("FAILED", trace.outcome().status());
                assertEquals("BACKEND_CONNECTION_FAILED", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void unsupported_model_connection_failure_is_visible_and_failure_dominant() {
            String diagnostic = "llama_cpp model 'gpt-oss-20b' at C:\\models\\gpt-oss.gguf "
                    + "uses unsupported GGUF architecture 'gptoss'. No fallback model was selected.";
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scriptedFailure(new EngineException.ConnectionFailed(
                            diagnostic,
                            null)))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("system"));
            messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));

            LocalTurnTraceCapture.begin(
                    "trc-unsupported-model",
                    "sid",
                    1,
                    "2026-05-03T00:00:00Z",
                    "workspace-hash",
                    "test",
                    "llama_cpp",
                    "gpt-oss-20b",
                    "Overwrite index.html with exactly AFTER. Use talos.write_file.");
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, WS, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("unsupported GGUF architecture 'gptoss'"), out.text());
                assertTrue(out.text().contains("gpt-oss-20b"), out.text());
                assertTrue(out.text().contains("C:\\models\\gpt-oss.gguf"), out.text());
                assertTrue(out.text().contains("No fallback model was selected"), out.text());
                assertNoSuccessProse(out.text());
                assertEquals("FAILED", trace.outcome().status());
                assertEquals("BACKEND_CONNECTION_FAILED", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void engine_exception_subtypes_are_all_sealed_and_accounted_for() {
            // Structural test: verify the sealed hierarchy matches what execute() catches.
            // This ensures new subtypes added to EngineException won't slip through.
            var subtypes = EngineException.class.getPermittedSubclasses();
            assertNotNull(subtypes, "EngineException should be sealed");
            // execute() catches: ConnectionFailed, ModelNotFound, Transient, EngineException (base).
            // ContextBudgetExceeded, ResponseError, and MalformedResponse are intentionally covered by the base catch.
            assertEquals(6, subtypes.length,
                    "EngineException should have exactly 6 subtypes (if this changes, update execute())");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TurnOutput record
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TurnOutput")
    class TurnOutputTests {

        @Test
        void record_accessors() {
            var to = new AssistantTurnExecutor.TurnOutput("hello", true);
            assertEquals("hello", to.text());
            assertTrue(to.streamed());
        }

        @Test
        void record_equality() {
            var a = new AssistantTurnExecutor.TurnOutput("x", false);
            var b = new AssistantTurnExecutor.TurnOutput("x", false);
            assertEquals(a, b);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Options
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Options")
    class OptionsTests {

        @Test
        void fluent_api_returns_same_instance() {
            var opts = new AssistantTurnExecutor.Options();
            var returned = opts.llmTimeoutMs(1000).responseMaxChars(500).answerSanitizer(s -> s);
            assertSame(opts, returned, "Fluent methods should return same instance");
        }

        @Test
        void default_options_work() {
            var ctx = scriptedContext("default options answer");
            var messages = basicMessages();
            // Default options - should work without any configuration
            var opts = new AssistantTurnExecutor.Options();

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertFalse(out.text().isBlank());
        }

        @Test
        void identityQuestionUsesTalosIdentityNotModelProvider() {
            var ctx = scriptedContext(
                    "I'm Qwen, a large language model created by Alibaba Cloud.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("You are Talos."));
            messages.add(ChatMessage.user("hello who are you?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("Talos"), out.text());
            assertFalse(out.text().toLowerCase().contains("qwen"), out.text());
            assertFalse(out.text().toLowerCase().contains("alibaba"), out.text());
        }

        @Test
        void capabilityQuestionUsesTalosProductCapabilities() {
            var ctx = scriptedContext(
                    "As an AI language model, I can write poems and answer general questions.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("You are Talos."));
            messages.add(ChatMessage.user("Nice what can you do for me? How can you assist me?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            String lower = out.text().toLowerCase();
            assertTrue(out.text().contains("Talos"), out.text());
            assertTrue(lower.contains("local workspace"), out.text());
            assertTrue(lower.contains("approval"), out.text());
            assertTrue(lower.contains("talos.run_command") || lower.contains("bounded command"),
                    out.text());
            assertFalse(lower.contains("cannot use browser, shell"), out.text());
            assertFalse(lower.contains("raw shell"), out.text());
            assertFalse(lower.contains("as an ai language model"), out.text());
            assertFalse(lower.contains("poems"), out.text());
        }

        @Test
        void verifyOnlyCommandRetryPromptMatchesRunCommandToolSurface(@TempDir Path workspace) {
            String request = "Run the approved Gradle check command profile.";
            var contract = TaskContractResolver.fromUserRequest(request);
            var plan = CurrentTurnPlan.compatibility(
                    contract,
                    ExecutionPhase.VERIFY,
                    List.of("talos.run_command"),
                    List.of("talos.run_command"),
                    List.of("talos.list_dir", "talos.read_file"));
            var registry = new ToolRegistry();
            registry.register(new RunCommandTool(commandPlan -> fail("retry response should not execute a command")));
            var processor = new TurnProcessor(
                    ModeController.defaultController(),
                    new NoOpApprovalGate(),
                    registry);
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult("No command was run.", List.of())),
                    16_384);
            var ctx = Context.builder(new Config())
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(new ToolCallLoop(processor))
                    .nativeToolSpecs(List.of(new ToolSpec("talos.run_command", "Run approved command", "{}")))
                    .llm(recorded.client())
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("You are Talos."));
            messages.add(ChatMessage.user(request));

            AssistantTurnExecutor.readOnlyInspectionRetryIfNeeded(
                    "I cannot verify that from here.", messages, plan, workspace, ctx);

            assertFalse(recorded.requests().isEmpty(), "retry should send a provider request");
            String retryPrompt = recorded.requests().getFirst().messages.stream()
                    .filter(message -> "user".equals(message.role()))
                    .reduce((first, second) -> second)
                    .orElseThrow()
                    .content();
            assertTrue(retryPrompt.contains("talos.run_command"), retryPrompt);
            assertFalse(retryPrompt.contains("talos.list_dir"), retryPrompt);
            assertFalse(retryPrompt.contains("Use read-only tools"), retryPrompt);
        }

        @Test
        void workspaceSwitchRequestGetsDeterministicUnsupportedAnswer() {
            var ctx = scriptedContext("I switched to Desktop and can work there now.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("You are Talos."));
            messages.add(ChatMessage.user("Change workspace to Desktop."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            String lower = out.text().toLowerCase();
            assertTrue(lower.contains("cannot change workspace"), out.text());
            assertTrue(lower.contains("current session"), out.text());
            assertTrue(lower.contains("/workspace"), out.text());
            assertFalse(lower.contains("switched to desktop"), out.text());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private static List<ChatMessage> basicMessages() {
        var msgs = new ArrayList<ChatMessage>();
        msgs.add(ChatMessage.system("You are a helpful assistant."));
        msgs.add(ChatMessage.user("What is 2+2?"));
        return msgs;
    }

    private static void assertNoSuccessProse(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        assertFalse(lower.contains("complete"), text);
        assertFalse(lower.contains("ready to use"), text);
        assertFalse(lower.contains("open in browser"), text);
        assertFalse(lower.contains("save these files"), text);
    }

    // ── Deflection detection tests ───────────────────────────────────

    @Nested
    @DisplayName("isDeflection")
    class DeflectionTests {

        @Test
        void nullOrBlankIsDeflection() {
            assertTrue(AssistantTurnExecutor.isDeflection(null));
            assertTrue(AssistantTurnExecutor.isDeflection(""));
            assertTrue(AssistantTurnExecutor.isDeflection("   "));
        }

        @Test
        void genericAssistantBoilerplateIsDeflection() {
            assertTrue(AssistantTurnExecutor.isDeflection("How can I help you with these files?"));
            assertTrue(AssistantTurnExecutor.isDeflection("What would you like me to do next?"));
            assertTrue(AssistantTurnExecutor.isDeflection("Is there anything else you need?"));
            assertTrue(AssistantTurnExecutor.isDeflection("Feel free to ask if you have questions."));
            assertTrue(AssistantTurnExecutor.isDeflection("How can I assist you today?"));
        }

        @Test
        void substantiveShortAnswerIsNotDeflection() {
            assertFalse(AssistantTurnExecutor.isDeflection(
                    "The main HTML file is index.html. It loads style.css and script.js."));
        }

        @Test
        void longSubstantiveAnswerIsNotDeflection() {
            // A genuinely grounded answer that happens to be long
            String grounded = "The workspace contains index.html which is a BMI Calculator. "
                    + "CSS is defined inline via a <style> block in the <head>. "
                    + "JavaScript is inline via a <script> block before </body>. "
                    + "There are no external CSS or JS files. "
                    + "The settings.json file is not referenced by the HTML. "
                    + "x".repeat(400); // pad to > 500 chars
            assertFalse(AssistantTurnExecutor.isDeflection(grounded));
        }

        @Test
        void capabilityRecitationWithDeflectionEndingIsDeflection() {
            // This matches the real transcript Turn 3: a capability speech ending with "How can I assist you?"
            String capabilitySpeech =
                    "I can help you with tasks involving file manipulation and code searching within a workspace.\n\n"
                    + "Here is what I can do:\n\n"
                    + "* **Read/Write Files:** I can read the content of existing files, create new files, or overwrite existing ones.\n"
                    + "* **Edit Files:** I can perform find-and-replace operations on specific strings within a file.\n"
                    + "* **List Directories:** I can explore the structure of the workspace.\n"
                    + "* **Search Code:** I can search for specific text or regular expressions.\n\n"
                    + "**How can I assist you today?** Do you want to read a file, search for code, or perform a modification?";
            assertTrue(AssistantTurnExecutor.isDeflection(capabilitySpeech),
                    "Capability-recitation with deflection ending must be caught. Length: " + capabilitySpeech.length());
        }

        @Test
        void capabilityMentionWithoutDeflectionEndingIsNotDeflection() {
            // Mentions a capability but ends with substantive content - should not be flagged
            String answer = "I can help you with this analysis. "
                    + "The index.html file contains inline CSS in a <style> block. "
                    + "The calculateBMI() function handles the BMI computation. "
                    + "There are no external stylesheet or script references. "
                    + "x".repeat(300); // pad to > 500 chars
            assertFalse(AssistantTurnExecutor.isDeflection(answer));
        }
    }

    // ── Synthesis retry tests ────────────────────────────────────────

    @Nested
    @DisplayName("synthesisRetryIfNeeded")
    class SynthesisRetryTests {

        @Test
        void noRetryWhenNoToolsUsed() {
            var ctx = Context.builder(new Config()).build();
            var messages = basicMessages();
            String result = AssistantTurnExecutor.synthesisRetryIfNeeded(
                    "How can I help?", 0, messages, ctx);
            assertEquals("How can I help?", result, "Should not retry when no tools invoked");
        }

        @Test
        void noRetryWhenAnswerIsSubstantive() {
            var ctx = Context.builder(new Config()).build();
            var messages = basicMessages();
            String substantive = "The main file is index.html with inline CSS and JS.";
            String result = AssistantTurnExecutor.synthesisRetryIfNeeded(
                    substantive, 3, messages, ctx);
            assertEquals(substantive, result, "Should not retry substantive answers");
        }

        @Test
        void retryTriggeredForDeflectionAfterToolUse() {
            var ctx = scriptedContext("Scripted retry answer.");
            var messages = new ArrayList<>(basicMessages());
            String deflection = "How can I help you with these files?";
            String result = AssistantTurnExecutor.synthesisRetryIfNeeded(
                    deflection, 2, messages, ctx);

            // The retry should have appended messages and called the LLM
            assertTrue(messages.size() > 2,
                    "Retry should have appended assistant + user messages");
            assertNotEquals(deflection, result,
                    "Retry should produce a different answer from the deflection");
        }

        @Test
        void retryAddsCorrectPromptMessages() {
            var ctx = scriptedContext("retry message");
            var messages = new ArrayList<>(basicMessages());
            String deflection = "What would you like me to do?";
            AssistantTurnExecutor.synthesisRetryIfNeeded(deflection, 1, messages, ctx);

            // Should have added: assistant(deflection) + user(retry instruction)
            boolean hasRetryInstruction = messages.stream()
                    .anyMatch(m -> m.content() != null
                            && m.content().contains("already gathered the needed evidence"));
            assertTrue(hasRetryInstruction,
                    "Retry should inject a synthesis instruction message");
        }

        // ── Part A regression: post-tool task-anchor loss (real transcript) ───

        /**
         * Regression A: the real manual transcript (test-output.txt, Turn 2 / 6)
         * ended with "the original question is not visible in our current
         * conversation history" because the old retry prompt was generic. The
         * new retry must pin the user's verbatim request into the retry message
         * so the model cannot claim the question is missing.
         */
        @Test
        void retryPromptAnchorsToVerbatimUserRequest() {
            var ctx = scriptedContext("anchored retry answer");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("You are a helpful assistant."));
            String originalRequest =
                    "I dont like this site's look and feel... I want to completely change it and "
                    + "make it look like a garden in the spring where almonds starting blooming";
            messages.add(ChatMessage.user(originalRequest));
            // Simulate post-tool assistant + tool-result messages that push the
            // user request back in the context (matches native tool-call path).
            messages.add(ChatMessage.assistant("I'll inspect the files."));
            messages.add(ChatMessage.toolResult("call-1", "[tool_result] index.html contents…"));
            messages.add(ChatMessage.toolResult("call-2", "[tool_result] index.html, settings.json"));

            // A short deflection that the gate reliably catches (real Turn 2
            // ended with this family of phrasing once the retry didn't anchor).
            String deflection = "How can I help you with these files?";

            AssistantTurnExecutor.synthesisRetryIfNeeded(deflection, 2, messages, ctx);

            // Find the retry-instruction user message (most recently appended).
            String retryContent = null;
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage m = messages.get(i);
                if ("user".equals(m.role()) && m.content() != null
                        && m.content().contains("already gathered the needed evidence")) {
                    retryContent = m.content();
                    break;
                }
            }
            assertNotNull(retryContent, "Retry prompt must be appended as a user-role message");
            assertTrue(retryContent.contains("almonds starting blooming"),
                    "Retry prompt must include the verbatim original user request so the model "
                    + "cannot claim the question is missing. Actual: " + retryContent);
            assertTrue(retryContent.contains("Do not say the question is missing"),
                    "Retry prompt must explicitly forbid the 'question not visible' failure mode.");
        }

        /**
         * Regression A (helper-level): {@link AssistantTurnExecutor#latestUserRequest}
         * must return the ORIGINAL user request, not an intermediate tool_result,
         * on the native tool-call path where tool results have role="tool".
         */
        @Test
        void latestUserRequestReturnsOriginalOnNativeToolPath() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("redesign index.html as a spring garden"));
            messages.add(ChatMessage.assistant("reading…"));
            messages.add(ChatMessage.toolResult("c1", "file contents"));
            messages.add(ChatMessage.toolResult("c2", "dir listing"));

            String req = AssistantTurnExecutor.latestUserRequest(messages);
            assertEquals("redesign index.html as a spring garden", req,
                    "latestUserRequest must skip role=tool messages and return the user turn");
        }

        @Test
        void latestUserRequestSkipsSyntheticToolResultsOnTextPath() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("hey can you tell me what is in this workspace?"));
            messages.add(ChatMessage.assistant("{\"name\":\"talos.edit_file\",\"arguments\":{}}"));
            messages.add(ChatMessage.user("[tool_result: talos.edit_file]\n"
                    + "[error] This exact edit was already attempted and failed. "
                    + "Alternatively, use talos.write_file to replace the entire file content.\n"
                    + "[/tool_result]"));

            String req = AssistantTurnExecutor.latestUserRequest(messages);

            assertEquals("hey can you tell me what is in this workspace?", req,
                    "latestUserRequest must not treat text-path tool results as user intent");
        }

        @Test
        void mutationRetryExecutesTextFallbackToolCallsInsteadOfReturningRawJson() {
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.TalosTool() {
                @Override public String name() { return "talos.list_dir"; }
                @Override public String description() { return "List files"; }
                @Override public dev.talos.tools.ToolDescriptor descriptor() {
                    return new dev.talos.tools.ToolDescriptor(
                            name(), description(), "{\"path\":\"string\"}");
                }
                @Override public dev.talos.tools.ToolResult execute(
                        dev.talos.tools.ToolCall call, dev.talos.tools.ToolContext ctx) {
                    return dev.talos.tools.ToolResult.ok("index.html\nstyle.css");
                }
            });

            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}",
                            "Listed files from the retry.")))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("change the file"));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "original answer", 1, 1, List.of("talos.read_file"), messages,
                    0, 0, false, 0, List.of(), 0, 0, 0, 0);

            var result = AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    "original answer", messages, loopResult, WS, ctx);

            assertEquals(ResponseObligationVerifier.deterministicNoActionAnswer(), result.answer());
            assertTrue(result.actionObligationFailed());
            assertFalse(result.answer().contains("\"name\""),
                    "text-fallback tool JSON must not leak as the final answer");
            assertNotNull(result.extraSummary(),
                    "text-fallback retry tool calls should re-enter the tool loop");
        }

        @Test
        void mutationRetryContinuesRemainingExpectedTargetAfterPartialRetryMutation(@TempDir Path workspace)
                throws Exception {
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            registry.register(new dev.talos.tools.impl.FileWriteTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 4);
            var specs = registry.descriptors().stream()
                    .map(descriptor -> new ToolSpec(
                            descriptor.name(),
                            descriptor.description(),
                            descriptor.parametersSchema()))
                    .toList();
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(
                            new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                    "call_read_problem",
                                    "talos.read_file",
                                    java.util.Map.of("path", "problem.md", "max_lines", 200)))),
                            new LlmClient.StreamResult("I have enough information to implement this.", List.of()),
                            new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                    "call_dijkstra",
                                    "talos.write_file",
                                    java.util.Map.of(
                                            "path", "dijkstra.py",
                                            "content", "def shortest_path():\n    return 5\n")))),
                            new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                    "call_test",
                                    "talos.write_file",
                                    java.util.Map.of(
                                            "path", "test_dijkstra.py",
                                            "content", "from dijkstra import shortest_path\n\n"
                                                    + "def test_shortest_path():\n"
                                                    + "    assert shortest_path() == 5\n"))))),
                    16_384);
            recorded.client().setToolSpecs(specs);
            var ctx = Context.builder(new Config())
                    .llm(recorded.client())
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .nativeToolSpecs(specs)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            Files.writeString(workspace.resolve("problem.md"), "Implement Dijkstra and provide a pytest file.\n");
            messages.add(ChatMessage.user("Create dijkstra.py and test_dijkstra.py according to problem.md."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(recorded.requests().size() >= 4,
                    "initial call, post-read response, missing-mutation retry, and remaining-target continuation should all reach model");
            String continuationPrompt = recorded.requests().get(3).messages.stream()
                    .map(message -> message.content() == null ? "" : message.content())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(Files.isRegularFile(workspace.resolve("dijkstra.py")));
            assertTrue(Files.isRegularFile(workspace.resolve("test_dijkstra.py")),
                    "remaining expected target must be continued after partial missing-mutation retry\n"
                            + "answer:\n" + out.text() + "\ncontinuation prompt:\n" + continuationPrompt);
            assertFalse(out.text().contains("test_dijkstra.py: expected target was not successfully mutated"),
                    out.text());
            assertTrue(continuationPrompt.contains("test_dijkstra.py"), continuationPrompt);
            assertTrue(continuationPrompt.contains("Remaining expected target"), continuationPrompt);
            // Envelope-agnostic focus check: either continuation shape (the
            // fresh [RemainingExpectedTargetsAfterMutationRetry] frame or the
            // in-loop [Expected target progress] overlay, which .py requests
            // share with .java since the file-target inventories unified) must
            // list ONLY the unsatisfied target as remaining. The satisfied
            // target may still appear elsewhere (the turn's requiredTargets
            // history), but never in the remaining list.
            java.util.regex.Matcher remaining = java.util.regex.Pattern
                    .compile("Remaining expected target[^:]*:\\s*([^\\n]*)")
                    .matcher(continuationPrompt);
            assertTrue(remaining.find(), continuationPrompt);
            String remainingList = remaining.group(1).strip();
            assertTrue(remainingList.startsWith("test_dijkstra.py"),
                    "continuation must focus on the remaining unsatisfied target only, got: " + remainingList);
            assertFalse(remainingList.startsWith("dijkstra.py")
                            || remainingList.contains(" dijkstra.py")
                            || remainingList.contains(",dijkstra.py"),
                    "already-satisfied target must not be re-demanded as remaining: " + remainingList);
        }

        @Test
        void mutationRetryForFreshExplicitRequestDoesNotReissueOlderMutationRequest() {
            var processor = new dev.talos.runtime.TurnProcessor(null);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("I still will not call tools."))
                    .toolCallLoop(new dev.talos.runtime.ToolCallLoop(processor, 3))
                    .build();

            String staleRequest = "Make script.js fix the selector bug by changing .missing-button to .cta-button.";
            String currentRequest = "Create a complete static BMI calculator in this folder with "
                    + "index.html, styles.css, and scripts.js. It should calculate BMI from height and weight.";
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(staleRequest));
            messages.add(ChatMessage.assistant("""
                    [Task incomplete: Static verification failed - HTML does not link JavaScript file: `script.js`]

                    Applied mutating tool calls:
                    - script.js: Edited script.js
                    """));
            messages.add(ChatMessage.user(currentRequest));

            CurrentTurnPlan plan = CurrentTurnPlan.create(
                    TaskContractResolver.fromMessages(messages),
                    ExecutionPhase.APPLY,
                    List.of("talos.write_file", "talos.edit_file"),
                    List.of("talos.write_file", "talos.edit_file"),
                    List.of());
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "Created the BMI calculator website files.",
                    1,
                    0,
                    List.of(),
                    messages,
                    0,
                    0,
                    false,
                    0,
                    List.of(),
                    0,
                    0,
                    0,
                    0);

            AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    loopResult.finalAnswer(), messages, plan, loopResult, WS, ctx);

            String retryPrompt = messages.stream()
                    .filter(message -> "user".equals(message.role()))
                    .map(ChatMessage::content)
                    .filter(content -> content != null
                            && content.contains("Retry required:"))
                    .findFirst()
                    .orElseThrow();

            assertTrue(retryPrompt.contains("The user's request was:"), retryPrompt);
            assertTrue(retryPrompt.contains(currentRequest), retryPrompt);
            assertFalse(retryPrompt.contains("The previous mutation request to reissue is"), retryPrompt);
            assertFalse(retryPrompt.contains(staleRequest), retryPrompt);
        }

        @Test
        void mutationRetryContextBudgetExceededReturnsTypedDeterministicFailure() {
            var processor = new dev.talos.runtime.TurnProcessor(null);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scriptedFailure(new EngineException.ContextBudgetExceeded(
                            5946, 5635, 8192, 0)))
                    .toolCallLoop(new dev.talos.runtime.ToolCallLoop(processor, 3))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Create index.html, styles.css, and scripts.js for a BMI calculator."));

            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "Done. The BMI calculator is complete.",
                    1,
                    0,
                    List.of(),
                    messages,
                    0,
                    0,
                    false,
                    0,
                    List.of(),
                    0,
                    0,
                    0,
                    0);

            var result = AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    loopResult.finalAnswer(), messages, loopResult, WS, ctx);

            assertTrue(result.actionObligationFailed());
            assertEquals(0, result.mutationsInRetry());
            assertTrue(result.answer().startsWith("[Action obligation failed:"), result.answer());
            assertTrue(result.answer().toLowerCase().contains("context budget"), result.answer());
            assertFalse(result.answer().contains("Engine error"), result.answer());
            assertFalse(result.answer().toLowerCase().contains("complete"), result.answer());
        }

        @Test
        void mutationRetryForRepairFollowUpCanReissuePreviousMutationRequest() {
            var processor = new dev.talos.runtime.TurnProcessor(null);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("I still will not call tools."))
                    .toolCallLoop(new dev.talos.runtime.ToolCallLoop(processor, 3))
                    .build();

            String previousRequest = "Create a complete static BMI calculator in this folder with "
                    + "index.html, styles.css, and scripts.js. It should calculate BMI from height and weight.";
            String followUp = "Review the BMI calculator you just created and fix any obvious issue "
                    + "that would stop it from working in a browser.";
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(previousRequest));
            messages.add(ChatMessage.assistant("""
                    [Action obligation failed: pending static repair progress was not satisfied.]

                    Remaining target(s): scripts.js.
                    """));
            messages.add(ChatMessage.user(followUp));

            CurrentTurnPlan plan = CurrentTurnPlan.create(
                    TaskContractResolver.fromMessages(messages),
                    ExecutionPhase.APPLY,
                    List.of("talos.write_file", "talos.edit_file"),
                    List.of("talos.write_file", "talos.edit_file"),
                    List.of());
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "Looks fine to me.",
                    1,
                    0,
                    List.of(),
                    messages,
                    0,
                    0,
                    false,
                    0,
                    List.of(),
                    0,
                    0,
                    0,
                    0);

            AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    loopResult.finalAnswer(), messages, plan, loopResult, WS, ctx);

            String retryPrompt = messages.stream()
                    .filter(message -> "user".equals(message.role()))
                    .map(ChatMessage::content)
                    .filter(content -> content != null
                            && content.contains("Retry required:"))
                    .findFirst()
                    .orElseThrow();

            assertTrue(retryPrompt.contains("The current user message is a retry/repair follow-up"), retryPrompt);
            assertTrue(retryPrompt.contains(followUp), retryPrompt);
            assertTrue(retryPrompt.contains("The previous mutation request to reissue is"), retryPrompt);
            assertTrue(retryPrompt.contains(previousRequest), retryPrompt);
        }

        @Test
        void mutationRetryDoesNotFireFromSyntheticToolResultTail() {
            var ctx = scriptedContext("retry should not be called");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("hey can you tell me what is in this workspace?"));
            messages.add(ChatMessage.assistant("{\"name\":\"talos.edit_file\",\"arguments\":{}}"));
            messages.add(ChatMessage.user("[tool_result: talos.edit_file]\n"
                    + "[error] This exact edit was already attempted and failed. "
                    + "Alternatively, use talos.write_file to replace the entire file content.\n"
                    + "[/tool_result]"));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "original answer", 10, 8, List.of("talos.edit_file"), messages,
                    3, 2, true, 0, List.of("index.html"), 0, 0, 2, 0);

            var result = AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    "original answer", messages, loopResult, WS, ctx);

            assertEquals("original answer", result.answer(),
                    "synthetic B3 diagnostic must not be treated as mutation intent");
            assertEquals(0, result.mutationsInRetry());
            assertNull(result.extraSummary());
        }

        @Test
        void mutationRetryDoesNotFireAfterApprovalDeniedMutation() {
            var ctx = scriptedContext("retry should not be called");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("I think the html is completely wrong. Can you fix it?"));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "manual replacement prose", 3, 5,
                    List.of("talos.read_file", "talos.edit_file", "talos.write_file"),
                    messages, 2, 0, false, 0, List.of("index.html"),
                    0, 0, 0, 0,
                    List.of(
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", false, true, true, "",
                                    "User did not approve the talos.edit_file call."),
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "index.html", false, true, true, "",
                                    "User did not approve the talos.write_file call.")
                    ));

            var result = AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    "manual replacement prose", messages, loopResult, WS, ctx);

            assertEquals("manual replacement prose", result.answer());
            assertEquals(0, result.mutationsInRetry());
            assertNull(result.extraSummary(),
                    "approval denial already explains zero mutations, so missing-mutation retry must not fire");
        }

        @Test
        void policyDeniedMutationSummaryDoesNotClaimUserApprovalWasDenied() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Overwrite .env with SECRET=changed."));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "raw answer", 1, 1,
                    List.of("talos.write_file"),
                    messages, 1, 0, false, 0, List.of(".env"),
                    0, 0, 0, 0,
                    List.of(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                            "talos.write_file", ".env", false, true, true,
                            "", "Permission policy denied the talos.write_file call. "
                            + "Permission policy denied mutation of protected path `.env`. "
                            + "No approval was requested and no file was changed.",
                            null, dev.talos.tools.ToolError.DENIED
                    )));

            String answer = AssistantTurnExecutor.summarizeDeniedMutationOutcomesIfNeeded(
                    "raw answer", messages, loopResult, 0);

            assertTrue(answer.startsWith(AssistantTurnExecutor.POLICY_DENIED_MUTATION_ANNOTATION));
            assertTrue(answer.contains("No file changes were applied because permission policy denied"));
            assertTrue(answer.contains(".env"));
            assertTrue(answer.contains("protected path"));
            assertFalse(answer.contains("not approved"));
            assertFalse(answer.contains("approval was denied"));
            assertFalse(answer.contains(".env: approval denied"));
        }

        @Test
        void deniedProtectedReadSummaryCanonicalizesDisplayPath() {
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "raw secret prose", 1, 1,
                    List.of("talos.read_file"), List.of(),
                    1, 0, false, 0, List.of(),
                    0, 0, 0, 0,
                    List.of(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                            "talos.read_file", " .env", false, false, true,
                            "", "User did not approve the talos.read_file call.",
                            null, dev.talos.tools.ToolError.DENIED
                    ).withFailureReason(dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED)));

            String answer = AssistantTurnExecutor.summarizeDeniedProtectedReadOutcomesIfNeeded(
                    "raw secret prose", loopResult);

            assertTrue(answer.contains("- .env: approval denied"), answer);
            assertFalse(answer.contains("-  .env"), answer);
            assertFalse(answer.contains("raw secret prose"), answer);
        }

        @Test
        void mutationRetryDoesNotFireAfterInvalidMutatingArgs() {
            var registry = new dev.talos.tools.ToolRegistry();
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of("retry should not be called")))
                    .toolRegistry(registry)
                    .toolCallLoop(new dev.talos.runtime.ToolCallLoop(processor, 3))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Now apply the smallest fix by editing index.html."));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "invalid mutation summary", 1, 1,
                    List.of("talos.edit_file"),
                    messages, 1, 0, false, 0, List.of("index.html"),
                    0, 0, 0, 0,
                    List.of(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                            "talos.edit_file", "index.html", false, true, false,
                            "", "Invalid talos.edit_file call: `old_string` must be present and non-empty.",
                            null, dev.talos.tools.ToolError.INVALID_PARAMS
                    )));

            var result = AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    "invalid mutation summary", messages, loopResult, WS, ctx);

            // T743: invalid mutating arguments now get exactly one bounded
            // corrected retry with the tool error echoed into the re-prompt.
            // The scripted retry response issues no tool call, so the retry
            // fails closed with a deterministic obligation-failure answer.
            assertEquals(0, result.mutationsInRetry());
            assertTrue(result.actionObligationFailed(),
                    "failed corrected retry must surface obligation failure");
            assertTrue(messages.stream().anyMatch(m -> m.content() != null
                            && m.content().contains("rejected with invalid parameters")
                            && m.content().contains("`old_string` must be present and non-empty")),
                    "retry prompt must echo the tool error");
            assertTrue(result.answer().contains("[Action obligation failed"),
                    "failed retry must end with the deterministic no-action answer: " + result.answer());
        }

        @Test
        void mutationRetryDoesNotFireAfterFailurePolicyStop() {
            var registry = new dev.talos.tools.ToolRegistry();
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of("retry should not be called")))
                    .toolRegistry(registry)
                    .toolCallLoop(new dev.talos.runtime.ToolCallLoop(processor, 3))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Now apply the smallest fix by editing index.html."));
            var stop = dev.talos.runtime.failure.FailureDecision.stop(
                    dev.talos.runtime.failure.FailureAction.ASK_USER,
                    "failure policy stopped the tool loop after repeated failures");
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "failure policy stopped", 3, 3,
                    List.of("talos.edit_file", "talos.edit_file", "talos.edit_file"),
                    messages, 3, 0, false, 0, List.of("index.html"),
                    0, 0, 0, 0,
                    stop,
                    List.of());

            var result = AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    "failure policy stopped", messages, loopResult, WS, ctx);

            assertEquals("failure policy stopped", result.answer());
            assertEquals(0, result.mutationsInRetry());
            assertNull(result.extraSummary(),
                    "failure-policy stop is terminal for the main loop, so retry must not restart it");
        }

        @Test
        void mutationRetryApprovalDenialUsesDeniedMutationSummary(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("index.html"), "<div class=\"hero-content\">\n");
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.TalosTool() {
                @Override public String name() { return "talos.edit_file"; }
                @Override public String description() { return "Edit file"; }
                @Override public dev.talos.tools.ToolDescriptor descriptor() {
                    return new dev.talos.tools.ToolDescriptor(
                            name(), description(), null, dev.talos.tools.ToolRiskLevel.WRITE);
                }
                @Override public dev.talos.tools.ToolResult execute(
                        dev.talos.tools.ToolCall call, dev.talos.tools.ToolContext ctx) {
                    return dev.talos.tools.ToolResult.ok("edit-ok");
                }
            });

            var processor = new dev.talos.runtime.TurnProcessor(
                    null, (description, detail) -> false, registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"index.html\","
                                    + "\"old_string\":\"<div class=\\\"hero-content\\\">\","
                                    + "\"new_string\":\"<div class=\\\"hero-content cta-button\\\">\"}}")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Now apply the smallest fix by editing index.html."));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "raw malformed tool call", 1, 0, List.of(), messages,
                    0, 0, false, 0, List.of(), 0, 0, 0, 0);

            var result = AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    "raw malformed tool call", messages, loopResult, workspace, ctx);

            assertEquals(0, result.mutationsInRetry());
            assertNotNull(result.extraSummary());
            assertTrue(result.answer().contains("No file changes were applied because approval was denied for:"),
                    result.answer());
            assertTrue(result.answer().contains("index.html: approval denied"), result.answer());
            assertFalse(result.answer().contains("Tool loop stopped because the requested mutation was not approved."),
                    "retry-path denial should use the same denied-mutation summary as the main tool loop");
        }
    }

    // ── Regression: inspect-only failure class ───────────────────────

    @Nested
    @DisplayName("Inspect-only regression")
    class InspectRegressionTests {

        /**
         * Regression test for the real transcript failure: a trivial HTML workspace
         * with inline CSS/JS. The model gathered all evidence but returned a generic
         * "How can I help?" deflection instead of answering.
         *
         * <p>This test proves the deflection gate catches this class of failure
         * and the synthesis retry fires. It does not prove the retry produces a
         * correct grounded answer (that requires a real model), but it proves the
         * mechanism activates for exactly the pattern observed.
         */
        @Test
        void deflectionDetectedForRealTranscriptPattern() {
            // Turn 1 final answer from the real transcript (291 chars)
            String turn1Answer = "I have listed the files in the current directory: `index.html` and `settings.json`.\n\n"
                    + "How can I help you with these files? For example, do you want me to read their content, modify them, "
                    + "or perform some kind of operation?";
            assertTrue(AssistantTurnExecutor.isDeflection(turn1Answer),
                    "Turn 1 transcript answer should be detected as deflection");

            // Turn 3 capability-recitation (714 chars)
            String turn3Answer = "I can help you with tasks involving file manipulation and code searching within a workspace.\n\n"
                    + "Here is what I can do:\n\n"
                    + "* **Read/Write Files:** I can read the content of existing files, create new files, or overwrite existing ones.\n"
                    + "*   **Edit Files:** I can perform find-and-replace operations on specific strings within a file.\n"
                    + "*   **List Directories:** I can explore the structure of the workspace.\n"
                    + "* **Search Code:** I can search for specific text or regular expressions across multiple files "
                    + "(`grep`), or perform semantic searches using `retrieve`.\n\n"
                    + "**How can I assist you today?** Do you want to read a file, search for code, or perform a modification?";
            assertTrue(AssistantTurnExecutor.isDeflection(turn3Answer),
                    "Turn 3 capability-recitation should be detected as deflection. Length: " + turn3Answer.length());
        }

        @Test
        void synthesisRetryFiresForRealTranscriptDeflection() {
            var ctx = scriptedContext("Grounded follow-up based on inspected files.");

            // Simulate the message state after tool execution: system + user + tool results
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("You are a helpful assistant."));
            messages.add(ChatMessage.user(
                    "Explore this workspace and identify the main HTML entry file, "
                    + "the main stylesheet file, and the main JavaScript file."));

            // The deflection that was actually returned
            String deflection = "I have listed the files in the current directory: `index.html` and `settings.json`.\n\n"
                    + "How can I help you with these files?";

            String result = AssistantTurnExecutor.synthesisRetryIfNeeded(
                    deflection, 3, messages, ctx);

            // The retry must have fired (message count increased)
            assertTrue(messages.size() > 2,
                    "Synthesis retry must fire for the real transcript deflection");
            assertNotEquals(deflection, result,
                    "Retry should produce a different answer");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  R2 - Claim-vs-action truth layer (annotate-first)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("annotateIfFalseMutationClaim")
    class ClaimVsActionTests {

        /** Build a LoopResult with the given number of successful mutating tool calls. */
        private dev.talos.runtime.ToolCallLoop.LoopResult loopResult(int mutatingSuccesses) {
            return new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 1, 1,
                    List.of("talos.read_file"),
                    List.of(), 0, 0, false, mutatingSuccesses, List.of(),
                    0, 0, 0, 0);
        }

        @Test
        @DisplayName("mutation claim + zero mutating successes → annotated")
        void falseMutationClaimGetsAnnotated() {
            // Real Turn 5 pattern: answer confidently asserts an applied edit,
            // but only read_file was invoked - no write_file / edit_file success.
            String answer = "The changes have been applied to `index.html`.\n\n"
                    + "I updated the headline and the introductory description to sound more "
                    + "professional and authoritative, while keeping the core functionality intact.";

            String out = AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, loopResult(0));

            assertNotEquals(answer, out, "Answer must be modified (annotated)");
            assertTrue(out.startsWith(AssistantTurnExecutor.FALSE_MUTATION_ANNOTATION),
                    "Annotation must be prepended so users see it first");
            assertTrue(out.contains(answer), "Original answer text must be preserved verbatim");
        }

        @Test
        @DisplayName("mutation claim + successful mutating tool → NOT annotated")
        void realMutationBackingClaimIsNotAnnotated() {
            String answer = "I updated the headline in index.html as requested.";

            String out = AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, loopResult(1));

            assertEquals(answer, out,
                    "Answer backed by a real mutating tool success must not be annotated");
            assertFalse(out.startsWith(AssistantTurnExecutor.FALSE_MUTATION_ANNOTATION));
        }

        @Test
        @DisplayName("no mutation claim → never annotated regardless of tool successes")
        void nonMutationAnswerIsNeverAnnotated() {
            String answer = "Based on the file contents, this is a BMI calculator written "
                    + "in a single HTML file with inline style and script blocks.";

            // Both zero mutations and some mutations - neither should annotate a
            // read-only / descriptive answer.
            assertEquals(answer,
                    AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, loopResult(0)));
            assertEquals(answer,
                    AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, loopResult(2)));
        }

        @Test
        @DisplayName("zero changed-files answer → NOT annotated")
        void zeroChangedFilesAnswerIsNotAnnotated() {
            String answer = "I created or modified zero files during this Ask-mode test.";

            String out = AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, loopResult(0));

            assertEquals(answer, out);
            assertFalse(out.startsWith(AssistantTurnExecutor.FALSE_MUTATION_ANNOTATION));
            assertFalse(AssistantTurnExecutor.containsMutationClaim(answer));
        }

        @Test
        @DisplayName("containsMutationClaim detects real Turn 5 phrases")
        void detectsTranscriptPhrases() {
            assertTrue(AssistantTurnExecutor.containsMutationClaim(
                    "The changes have been applied to `index.html`."));
            assertTrue(AssistantTurnExecutor.containsMutationClaim(
                    "I updated the headline to be more professional."));
            assertTrue(AssistantTurnExecutor.containsMutationClaim(
                    "I've edited the CTA button text."));
            assertTrue(AssistantTurnExecutor.containsMutationClaim(
                    "I wrote the new file."));
            assertTrue(AssistantTurnExecutor.containsMutationClaim(
                    "The file has been updated with the new content."));
        }

        @Test
        @DisplayName("containsMutationClaim does not flag benign descriptive language")
        void descriptiveLanguageIsNotFlagged() {
            // Grounded discussion of file contents must not trip the detector.
            assertFalse(AssistantTurnExecutor.containsMutationClaim(
                    "The label reads 'Weight (kg)' and the input accepts numbers."));
            assertFalse(AssistantTurnExecutor.containsMutationClaim(
                    "If you want to update the headline, you can edit line 12."));
            assertFalse(AssistantTurnExecutor.containsMutationClaim(
                    "You could change the CSS class, though it is not strictly required."));
            assertFalse(AssistantTurnExecutor.containsMutationClaim(
                    "The site uses inline styles and an inline script."));
        }

        @Test
        @DisplayName("null / blank answer → unchanged (no annotation)")
        void nullOrBlankPassThrough() {
            assertNull(AssistantTurnExecutor.annotateIfFalseMutationClaim(null, loopResult(0)));
            assertEquals("", AssistantTurnExecutor.annotateIfFalseMutationClaim("", loopResult(0)));
            assertEquals("   ", AssistantTurnExecutor.annotateIfFalseMutationClaim("   ", loopResult(0)));
        }

        @Test
        @DisplayName("null LoopResult → answer returned unchanged (defensive)")
        void nullLoopResultPassThrough() {
            String answer = "I updated the file.";
            assertEquals(answer,
                    AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, null));
        }

        @Test
        @DisplayName("partial mutation success replaces answer with verified outcome summary")
        void partialMutationTurnGetsVerifiedSummary() {
            String answer = "Great! The title, header, hero copy, and stylesheet have all been updated.";
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 2, 4,
                    List.of("talos.edit_file", "talos.edit_file", "talos.edit_file", "talos.write_file"),
                    List.of(), 1, 0, false, 3, List.of(),
                    0, 0, 0, 0,
                    List.of(
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", false, true, "",
                                    "old_string not found in index.html. The exact text was not found in the file."),
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", true, true,
                                    "Edited index.html: replaced 4 line(s) with 4 line(s)", ""),
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", true, true,
                                    "Edited index.html: replaced 6 line(s) with 6 line(s)", ""),
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "style.css", true, true,
                                    "Updated style.css (28 lines, 540 bytes)", "")
                    ));

            String out = AssistantTurnExecutor.summarizePartialMutationOutcomesIfNeeded(answer, loopResult, 0);

            assertTrue(out.startsWith(AssistantTurnExecutor.PARTIAL_MUTATION_ANNOTATION));
            assertTrue(out.contains("Succeeded:"));
            assertTrue(out.contains("Failed:"));
            assertTrue(out.contains("style.css"));
            assertTrue(out.contains("old_string not found"));
            assertFalse(out.contains("title, header, hero copy, and stylesheet have all been updated"),
                    "unverified model prose must be replaced on partial-success mutation turns");
        }

        @Test
        @DisplayName("denied mutation turn replaces manual-update prose with factual no-change summary")
        void deniedMutationTurnGetsNoChangeSummary() {
            String answer = """
                    I understand the user's request and will proceed by manually updating the file.

                    ### Corrected `index.html` Content:
                    ```html
                    <!DOCTYPE html><html>broken replacement</html>
                    ```
                    """;
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("I think the html is completely wrong. Can you fix it?"));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 2, 3,
                    List.of("talos.read_file", "talos.edit_file"),
                    messages, 1, 0, false, 0, List.of("index.html"),
                    0, 0, 0, 0,
                    List.of(
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", false, true, true, "",
                                    "User did not approve the talos.edit_file call.")
                                    .withFailureReason(dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED)
                    ));

            String out = AssistantTurnExecutor.summarizeDeniedMutationOutcomesIfNeeded(
                    answer, messages, loopResult, 0);

            assertTrue(out.startsWith(AssistantTurnExecutor.DENIED_MUTATION_ANNOTATION));
            assertTrue(out.contains("No file changes were applied"));
            assertTrue(out.contains("approval was denied"));
            assertTrue(out.contains("index.html"));
            assertFalse(out.contains("Corrected `index.html` Content"),
                    "manual replacement prose must not survive a denied mutation turn");
        }

        @Test
        @DisplayName("denied mutation does not also get generic false-mutation annotation")
        void deniedMutationSkipsGenericFalseMutationAnnotation() {
            String answer = "The changes have been applied to `index.html`.";
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 1, 1,
                    List.of("talos.edit_file"),
                    List.of(), 1, 0, false, 0, List.of("index.html"),
                    0, 0, 0, 0,
                    List.of(
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", false, true, true, "",
                                    "User did not approve the talos.edit_file call.")
                    ));

            String out = AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, loopResult, 0);

            assertEquals(answer, out,
                    "denied mutation turns should be handled by the dedicated denied-mutation summary only");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  R6 - No-tool grounding retry (evidence-required prompts)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("groundingRetryIfNeeded (R6, scoped to non-streaming no-tool branch)")
    class GroundingRetryTests {

        /** A clearly-above-threshold ungrounded-shape answer (no tools were used). */
        private String longUngroundedAnswer() {
            // 900+ chars of confident-sounding but zero-evidence prose. Shaped
            // like the real Turn 2/3/4 transcript fabrications - substantive
            // enough to slip past any deflection tier, short of sanitation.
            return "Based on the typical structure of this kind of project, the site "
                 + "is organized as a single HTML file with separate stylesheet and "
                 + "script references linked from the head and body. The CSS file "
                 + "controls visual presentation - colors, spacing, typography - "
                 + "while the JavaScript file handles the interactive behavior, "
                 + "especially the BMI calculation on form submission. The HTML "
                 + "provides the structural skeleton for both. In practice this "
                 + "means the three components are tightly coupled through the id "
                 + "and class attributes on the HTML elements, which the CSS "
                 + "selectors and the JavaScript document.getElementById calls rely "
                 + "on. As long as those identifiers remain stable the site will "
                 + "work as expected. No obvious cross-linking errors are likely "
                 + "given the conventional nature of the implementation. The "
                 + "general advice would be to keep the class names consistent and "
                 + "to make sure the script tag's src attribute and the link tag's "
                 + "href attribute both resolve correctly at load time.";
        }

        private Context newCtx() { return scriptedContext("grounded retry answer"); }

        // ── Helper detection tests ────────────────────────────────────

        @Test
        @DisplayName("latestUserRequest returns the last user-role message content")
        void latestUserRequestWalksFromTail() {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("first question"));
            messages.add(ChatMessage.assistant("first answer"));
            messages.add(ChatMessage.user("second question"));
            messages.add(ChatMessage.assistant("second answer"));

            assertEquals("second question", AssistantTurnExecutor.latestUserRequest(messages));
        }

        @Test
        @DisplayName("latestUserRequest returns null when no user message present")
        void latestUserRequestNullWhenAbsent() {
            List<ChatMessage> messages = List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.assistant("answer"));
            assertNull(AssistantTurnExecutor.latestUserRequest(messages));
            assertNull(AssistantTurnExecutor.latestUserRequest(List.of()));
            assertNull(AssistantTurnExecutor.latestUserRequest(null));
        }

        @Test
        @DisplayName("looksLikeEvidenceRequest matches real transcript prompts")
        void evidenceRequestMatchesTranscriptPrompts() {
            // Exact phrases from test-output.txt user turns that failed
            // ungrounded. These are the shapes the gate must catch.
            assertTrue(AssistantTurnExecutor.looksLikeEvidenceRequest(
                    "tell me how this site is wired together: which HTML file "
                    + "loads which CSS and JS files, and whether there are any "
                    + "broken or suspicious references."));
            assertTrue(AssistantTurnExecutor.looksLikeEvidenceRequest(
                    "Read the main HTML, CSS, and JS files and tell me 3 "
                    + "concrete improvement opportunities. Use evidence from "
                    + "the actual files, not generic website advice."));
            assertTrue(AssistantTurnExecutor.looksLikeEvidenceRequest(
                    "Check whether this website has mismatches between HTML "
                    + "classes/IDs and the selectors used in CSS or JavaScript."));
        }

        @Test
        @DisplayName("looksLikeEvidenceRequest does not match casual conversation")
        void evidenceRequestDoesNotMatchCasualPrompts() {
            assertFalse(AssistantTurnExecutor.looksLikeEvidenceRequest(
                    "explain how BMI is calculated"));
            assertFalse(AssistantTurnExecutor.looksLikeEvidenceRequest(
                    "what's the difference between metric and imperial BMI?"));
            assertFalse(AssistantTurnExecutor.looksLikeEvidenceRequest(
                    "can you rewrite this headline to sound more professional?"));
            assertFalse(AssistantTurnExecutor.looksLikeEvidenceRequest(""));
            assertFalse(AssistantTurnExecutor.looksLikeEvidenceRequest(null));
        }

        // ── Gate firing behavior ──────────────────────────────────────

        @Test
        @DisplayName("FIRES: long answer + zero tools + evidence-request prompt")
        void firesOnTranscriptTurn4Shape() {
            var ctx = newCtx();
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Check whether this website has mismatches between HTML "
                    + "classes/IDs and the selectors used in CSS or JavaScript. "
                    + "Do not change anything yet."));

            String ungrounded = longUngroundedAnswer();
            int beforeCount = messages.size();

            String out = AssistantTurnExecutor.groundingRetryIfNeeded(
                    ungrounded, messages, ctx);

            // Retry must have fired: assistant + corrective user message appended.
            assertEquals(beforeCount + 2, messages.size(),
                    "Grounding retry must append assistant + corrective user message");
            assertEquals("assistant", messages.get(beforeCount).role());
            assertEquals("user", messages.get(beforeCount + 1).role());
            assertTrue(messages.get(beforeCount + 1).content().toLowerCase()
                            .contains("without reading any files"),
                    "Corrective prompt must mention the lack of file reads");

            // Result must not be the original. It is either the retry text
            // (when PLACEHOLDER returned something substantive) or the
            // annotated original - both acceptable. Distinguish:
            assertNotEquals(ungrounded, out, "Result must differ from the original");
            if (out.startsWith(AssistantTurnExecutor.UNGROUNDED_ANNOTATION)) {
                // Retry was blank/identical - original was annotated.
                assertTrue(out.contains(ungrounded),
                        "Annotated result must preserve the original answer");
            }
        }

        // ── Non-firing cases ──────────────────────────────────────────

        @Test
        @DisplayName("DOES NOT FIRE: user did not ask for evidence (casual prompt)")
        void doesNotFireOnCasualPrompt() {
            var ctx = newCtx();
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.user("explain how BMI is calculated"));

            String answer = longUngroundedAnswer();
            int beforeCount = messages.size();

            String out = AssistantTurnExecutor.groundingRetryIfNeeded(answer, messages, ctx);

            assertSame(answer, out,
                    "Must not fire when the user did not ask for evidence/inspection");
            assertEquals(beforeCount, messages.size(),
                    "Messages must be unchanged when the gate does not fire");
        }

        @Test
        @DisplayName("DOES NOT FIRE: answer is short (below UNGROUNDED_MIN_CHARS)")
        void doesNotFireOnShortAnswer() {
            var ctx = newCtx();
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.user(
                    "Read the main files and identify the entry points."));

            String shortAnswer = "I'm not sure. Can you rephrase?";
            int beforeCount = messages.size();

            String out = AssistantTurnExecutor.groundingRetryIfNeeded(
                    shortAnswer, messages, ctx);

            assertSame(shortAnswer, out,
                    "Must not fire for answers below UNGROUNDED_MIN_CHARS");
            assertEquals(beforeCount, messages.size());
        }

        @Test
        @DisplayName("DOES NOT FIRE: null / blank answer passes through")
        void doesNotFireOnNullOrBlank() {
            var ctx = newCtx();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Read the workspace and evidence from actual files."));

            assertNull(AssistantTurnExecutor.groundingRetryIfNeeded(null, messages, ctx));
            assertEquals("", AssistantTurnExecutor.groundingRetryIfNeeded("", messages, ctx));
            assertEquals("   ", AssistantTurnExecutor.groundingRetryIfNeeded("   ", messages, ctx));
        }

        @Test
        @DisplayName("NO OVERREACH: legitimate long explanation without evidence keywords is untouched")
        void doesNotFireOnLegitimateLongExplanation() {
            var ctx = newCtx();
            List<ChatMessage> messages = new ArrayList<>();
            // User asks a general knowledge question. A long, substantive
            // explanation answering it is legitimate - must not be second-guessed.
            messages.add(ChatMessage.user(
                    "explain the difference between BMI and body fat percentage"));

            String longExplanation = longUngroundedAnswer();
            int beforeCount = messages.size();

            String out = AssistantTurnExecutor.groundingRetryIfNeeded(
                    longExplanation, messages, ctx);

            assertSame(longExplanation, out,
                    "Long explanatory answers without an evidence-request prompt "
                    + "must pass through untouched");
            assertEquals(beforeCount, messages.size());
        }

        @Test
        @DisplayName("UNGROUNDED_MIN_CHARS is a boundary: one char below does not fire")
        void boundaryBelowThresholdDoesNotFire() {
            var ctx = newCtx();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Read the main files and verify the wiring."));

            // Exactly UNGROUNDED_MIN_CHARS - 1 characters.
            String justBelow = "a".repeat(AssistantTurnExecutor.UNGROUNDED_MIN_CHARS - 1);

            String out = AssistantTurnExecutor.groundingRetryIfNeeded(
                    justBelow, messages, ctx);

            assertSame(justBelow, out, "Answer one char below threshold must not fire");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  N2 - Streaming-path grounding annotation
    //
    //  These tests lock in the streaming counterpart to R6. The helper is a
    //  pure predicate - we test it directly so the decision boundary is
    //  deterministic (independent of the PLACEHOLDER LLM's output length).
    //  One integration-level test confirms wiring by asserting absence of
    //  the annotation on a non-evidence prompt regardless of answer length.
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N2 - Streaming grounding annotation")
    class StreamingGroundingTests {

        /** Long enough to pass {@link AssistantTurnExecutor#UNGROUNDED_MIN_CHARS}. */
        private String longAnswer() {
            return "a".repeat(AssistantTurnExecutor.UNGROUNDED_MIN_CHARS + 50);
        }

        @Test
        @DisplayName("predicate fires: long answer + evidence-request prompt")
        void fires_on_long_answer_plus_evidence_request() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Please read the source files and verify the wiring."));

            assertTrue(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    longAnswer(), messages),
                    "long answer + evidence marker must fire");
        }

        @Test
        @DisplayName("predicate does NOT fire: answer below UNGROUNDED_MIN_CHARS")
        void does_not_fire_when_answer_too_short() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Please read the source files and verify the wiring."));

            // Exactly one char below the threshold.
            String justBelow = "a".repeat(AssistantTurnExecutor.UNGROUNDED_MIN_CHARS - 1);
            assertFalse(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    justBelow, messages),
                    "just-below-threshold answer must not fire");
        }

        @Test
        @DisplayName("predicate does NOT fire: no evidence-request marker in prompt")
        void does_not_fire_without_evidence_marker() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Tell me a joke about fish."));

            assertFalse(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    longAnswer(), messages),
                    "plain conversational prompt must not fire the grounding gate");
        }

        @Test
        @DisplayName("predicate does NOT fire: null or blank answer")
        void does_not_fire_on_null_or_blank_answer() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Read the files and check the wiring."));

            assertFalse(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    null, messages));
            assertFalse(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    "", messages));
            assertFalse(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    "   \n\t   ", messages));
        }

        @Test
        @DisplayName("predicate inspects ONLY the latest user message")
        void inspects_only_latest_user_message() {
            var messages = new ArrayList<ChatMessage>();
            // Earlier turn had evidence markers; latest turn does not.
            messages.add(ChatMessage.user("Please read the files and verify."));
            messages.add(ChatMessage.assistant("Sure, here is my analysis."));
            messages.add(ChatMessage.user("Now tell me a joke."));

            assertFalse(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    longAnswer(), messages),
                    "earlier evidence-request must not leak into a later conversational turn");
        }

        @Test
        @DisplayName("predicate mirrors non-streaming decision shape on same inputs")
        void predicate_mirrors_non_streaming_decision() {
            // Same gating logic (length + latest user marker) should yield
            // the same yes/no answer on both helpers. We assert this
            // invariant directly so future edits to one without the other
            // are caught.
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Read the main file and identify the mismatch."));
            String longAns = longAnswer();

            boolean streamingFires = AssistantTurnExecutor
                    .shouldAppendStreamingGroundingAnnotation(longAns, messages);

            // The non-streaming helper has extra retry logic, but its own
            // firing precondition is structurally the same: >= MIN_CHARS
            // and looksLikeEvidenceRequest(latestUserRequest(messages)).
            boolean nonStreamingGatingMatches =
                    longAns.length() >= AssistantTurnExecutor.UNGROUNDED_MIN_CHARS
                    && AssistantTurnExecutor.looksLikeEvidenceRequest(
                            AssistantTurnExecutor.latestUserRequest(messages));

            assertEquals(nonStreamingGatingMatches, streamingFires,
                    "streaming predicate must agree with non-streaming gate on gating inputs");
            assertTrue(streamingFires, "sanity: this shape must fire");
        }

        @Test
        @DisplayName("streaming execute() does NOT inject annotation on non-evidence prompt")
        void streaming_execute_no_annotation_without_evidence_marker() {
            // Integration-level: regardless of what the PLACEHOLDER LLM
            // happens to return, a conversational prompt with no evidence
            // markers MUST NOT cause the annotation to be appended.
            var chunks = new ArrayList<String>();
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("This is a short scripted answer."))
                    .streamSink(chunks::add)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Tell me a short joke, please."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.streamed(), "streaming path must be marked streamed");
            assertFalse(out.text().contains("Grounding check"),
                    "no annotation must appear on non-evidence prompts. Got: " + out.text());
            String joined = String.join("", chunks);
            assertFalse(joined.contains("Grounding check"),
                    "no annotation must be pushed to the stream sink on non-evidence prompts");
        }

        @Test
        @DisplayName("streaming execute() does not rewrite the streamed prose (annotation is additive)")
        void streaming_execute_does_not_rewrite_streamed_content() {
            // Whatever the PLACEHOLDER returned, it must appear verbatim in
            // out.text() - the annotation may or may not be appended, but
            // the original streamed content is never replaced or shortened.
            var chunks = new ArrayList<String>();
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("Streamed content for evidence request."))
                    .streamSink(chunks::add)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Read the files and check the wiring."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            String streamedText = String.join("", chunks);
            // Remove any annotation the gate may have pushed into the sink.
            String streamedWithoutAnnotation = streamedText
                    .replace(AssistantTurnExecutor.UNGROUNDED_ANNOTATION.stripTrailing(), "")
                    .replaceAll("\\s+$", "");
            String textWithoutAnnotation = out.text()
                    .replace(AssistantTurnExecutor.UNGROUNDED_ANNOTATION.stripTrailing(), "")
                    .replaceAll("\\s+$", "");

            // The pre-annotation text content must match in both surfaces
            // (modulo the surrounding newline padding the annotation uses).
            assertTrue(textWithoutAnnotation.startsWith(streamedWithoutAnnotation.stripTrailing()),
                    "streamed content must appear at the start of out.text() - annotation must be additive, not a rewrite.\n"
                    + "streamed=<" + streamedWithoutAnnotation + ">\n"
                    + "text=<" + textWithoutAnnotation + ">");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  N1 - Transcript regression anchors
    //
    //  One test per transcript turn from test-output.txt (the playground run
    //  that exposed the trust layer gaps). Each test pins an exact user-prompt
    //  shape + an answer shape that today's trust gates MUST catch, so a
    //  future regression that weakens a gate (tightens a threshold, narrows
    //  a marker set, loosens a claim detector) fails with a clear turn
    //  reference.
    //
    //  Scope note: these are executor-seam (static-gate) tests, not harness
    //  scenarios. The harness seam (ToolCallLoop) bypasses AssistantTurnExecutor,
    //  so R2/R6/N2 cannot fire there. LlmClient is final with no scripted-mode
    //  seam, so driving execute() end-to-end with scripted LLM responses would
    //  require extracting an interface - a speculative abstraction the branch
    //  rules discourage. The static-gate pattern (already used by
    //  ClaimVsActionTests, GroundingRetryTests, StreamingGroundingTests) is
    //  the correct and lowest-risk anchor for transcript-level assertions.
    //
    //  Turn mapping:
    //    T1 (under-inspection)      → NO TEST YET. P4 gate does not exist.
    //    T2 (wiring fabrication)    → t2_wiringFabrication_triggersR6
    //    T3 (code fabrication)      → t3_codeFabrication_triggersR6
    //    T4 (selector fabrication)  → see GroundingRetryTests#firesOnTranscriptTurn4Shape
    //    T5 (false mutation claim)  → t5_falseMutationClaim_triggersR2
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N1 - Transcript regressions (test-output.txt anchors)")
    class TranscriptRegressions {

        /** Turn 2 prompt, verbatim from test-output.txt. */
        private static final String TURN2_USER_PROMPT =
                "tell me how this site is wired together: which HTML file "
              + "loads which CSS and JS files, and whether there are any "
              + "broken or suspicious references.";

        /** Turn 3 prompt, verbatim from test-output.txt. */
        private static final String TURN3_USER_PROMPT =
                "Read the main HTML, CSS, and JS files and tell me 3 "
              + "concrete improvement opportunities. Use evidence from "
              + "the actual files, not generic website advice.";

        /**
         * Turn 2 fabrication shape: confident wiring narrative asserting
         * external link/script references that the workspace did not contain.
         * Must exceed UNGROUNDED_MIN_CHARS (600) so the R6 length gate passes
         * and the evidence-marker gate determines firing.
         */
        private String turn2WiringFabrication() {
            return "The site is wired together as three coordinated files loaded "
                 + "by index.html. The <head> section contains a <link rel=\"stylesheet\" "
                 + "href=\"style.css\"> element that pulls in the visual presentation, "
                 + "and the <body> closes with a <script src=\"script.js\"></script> "
                 + "reference that wires up the interactive behavior. The CSS selectors "
                 + "target the form's input ids and the result container, while the "
                 + "JavaScript listens for the submit event on the form element and "
                 + "writes the computed BMI back into the result div via "
                 + "document.getElementById. There are no obvious broken references - "
                 + "the href and src attributes match the sibling file names, and the "
                 + "class/id naming is consistent across all three files. As long as "
                 + "the files remain in the same directory the load order will resolve "
                 + "correctly and the calculator will function end to end. This is the "
                 + "conventional multi-file layout you would expect for a small "
                 + "single-page utility like this one.";
        }

        /**
         * Turn 3 fabrication shape: "three concrete improvements" that reference
         * code patterns the files do not actually contain. Again must exceed
         * UNGROUNDED_MIN_CHARS so only the evidence-marker gate determines firing.
         */
        private String turn3CodeFabrication() {
            return "Here are three concrete improvement opportunities based on "
                 + "the files. First, the form submission handler in script.js "
                 + "uses an inline onsubmit attribute which mixes behavior into "
                 + "markup; moving to addEventListener('submit', ...) would "
                 + "separate concerns and make the event chain easier to test. "
                 + "Second, the CSS in style.css relies on element selectors like "
                 + "'input' and 'div' that match too broadly - switching to "
                 + "scoped class selectors (e.g. .bmi-input, .bmi-result) would "
                 + "reduce the risk of style leakage if the page ever grows. "
                 + "Third, the BMI formula in the JavaScript assumes metric "
                 + "input without validating the number range, so extremely "
                 + "large or negative weights produce nonsensical results; "
                 + "adding a simple min/max guard before the division would "
                 + "harden the calculator against bad input. Together these "
                 + "changes keep the single-file simplicity while tightening "
                 + "structure, style scope, and input validation.";
        }

        // ── T2 ────────────────────────────────────────────────────────

        @Test
        @DisplayName("T2 - Turn-2 wiring fabrication shape triggers R6 retry")
        void t2_wiringFabrication_triggersR6() {
            var ctx = scriptedContext("grounded T2 retry answer");
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(TURN2_USER_PROMPT));

            String fabrication = turn2WiringFabrication();
            assertTrue(fabrication.length() >= AssistantTurnExecutor.UNGROUNDED_MIN_CHARS,
                    "fixture precondition: Turn-2 fabrication must be long enough "
                  + "to pass the R6 length gate (got " + fabrication.length() + ")");

            int before = messages.size();
            String out = AssistantTurnExecutor.groundingRetryIfNeeded(
                    fabrication, messages, ctx);

            assertEquals(before + 2, messages.size(),
                    "T2 regression: R6 must fire for the Turn-2 wiring prompt + "
                  + "fabrication shape (expect assistant + corrective user message "
                  + "appended)");
            assertNotEquals(fabrication, out,
                    "T2 regression: result must differ from the original fabrication");
        }

        // ── T3 ────────────────────────────────────────────────────────

        @Test
        @DisplayName("T3 - Turn-3 code-fabrication shape triggers R6 retry")
        void t3_codeFabrication_triggersR6() {
            var ctx = scriptedContext("grounded T3 retry answer");
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(TURN3_USER_PROMPT));

            String fabrication = turn3CodeFabrication();
            assertTrue(fabrication.length() >= AssistantTurnExecutor.UNGROUNDED_MIN_CHARS,
                    "fixture precondition: Turn-3 fabrication must be long enough "
                  + "to pass the R6 length gate (got " + fabrication.length() + ")");

            int before = messages.size();
            String out = AssistantTurnExecutor.groundingRetryIfNeeded(
                    fabrication, messages, ctx);

            assertEquals(before + 2, messages.size(),
                    "T3 regression: R6 must fire for the Turn-3 'evidence from the "
                  + "actual files' prompt + code-fabrication shape");
            assertNotEquals(fabrication, out,
                    "T3 regression: result must differ from the original fabrication");
        }

        // ── T4 ────────────────────────────────────────────────────────
        //
        // Turn 4 (selector-mismatch audit fabrication) is already pinned by
        // GroundingRetryTests#firesOnTranscriptTurn4Shape. No duplicate here -
        // see that test's transcript-anchored prompt for the T4 regression.

        // ── T5 ────────────────────────────────────────────────────────

        @Test
        @DisplayName("T5 - Turn-5 false mutation claim (verbatim) is annotated")
        void t5_falseMutationClaim_triggersR2() {
            // Verbatim Turn-5 final narration from test-output.txt: Talos
            // invoked only read_file, then claimed the edit was applied.
            String answer =
                    "I've updated the CTA button text to 'Let's Get Healthy'. "
                  + "The changes have been applied to the `index.html` file.";

            // Loop shape that matches the transcript: 1 tool call (read_file),
            // zero mutating successes (no write_file / edit_file).
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 1, 1,
                    List.of("talos.read_file"),
                    List.of(), 0, 0, false, /*mutatingSuccesses*/ 0, List.of(),
                    0, 0, 0, 0);

            String out = AssistantTurnExecutor.annotateIfFalseMutationClaim(
                    answer, loopResult);

            assertNotEquals(answer, out,
                    "T5 regression: verbatim Turn-5 phrasing must be annotated "
                  + "when no mutating tool succeeded");
            assertTrue(out.startsWith(AssistantTurnExecutor.FALSE_MUTATION_ANNOTATION),
                    "T5 regression: FALSE_MUTATION_ANNOTATION must be prepended so "
                  + "the user sees the correction before the fabricated claim");
            assertTrue(out.contains(answer),
                    "T5 regression: original answer text must be preserved verbatim "
                  + "inside the annotated output");
        }

        // ── T1 ────────────────────────────────────────────────────────

        /** Turn 1 prompt, verbatim from test-output.txt (line 22). */
        private static final String TURN1_USER_PROMPT =
                "Explore this workspace and identify the main HTML entry file, "
              + "the main stylesheet file, and the main JavaScript file. "
              + "Read the relevant files first, then summarize the site "
              + "structure with exact file names.";

        /**
         * Turn 1 under-inspection shape: the verbatim transcript turn read
         * only {@code index.html} (1 read) and then produced a confident
         * three-file summary. The fabricated answer is ≥ 500 chars to pass
         * {@code INSPECT_MIN_CHARS}.
         */
        private String turn1UnderInspectionAnswer() {
            return "The site is built from three coordinated files. "
                 + "index.html is the main entry point and references the "
                 + "stylesheet style.css in its <head> plus the JavaScript "
                 + "file script.js at the bottom of <body>. The CSS file "
                 + "defines the visual presentation for the BMI calculator "
                 + "form and result panel, while the JavaScript file wires "
                 + "up the form submit handler and computes the BMI from "
                 + "the input values before writing the result back into "
                 + "the DOM. The three files live side-by-side in the same "
                 + "directory and together produce a single-page BMI "
                 + "calculator that works end to end when index.html is "
                 + "opened in a browser.";
        }

        @Test
        @DisplayName("T1 - Turn-1 under-inspection (1 read, multi-file prompt) is annotated")
        void t1_underInspection_triggersN3() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(TURN1_USER_PROMPT));

            String answer = turn1UnderInspectionAnswer();
            assertTrue(answer.length() >= AssistantTurnExecutor.INSPECT_MIN_CHARS,
                    "fixture precondition: Turn-1 answer must be long enough "
                  + "to pass the N3 length gate (got " + answer.length() + ")");

            // Loop shape that matches the transcript: 1 read_file call,
            // zero mutating successes (no write_file / edit_file).
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 1, 1,
                    List.of("talos.read_file"),
                    List.of(), 0, 0, false, /*mutatingSuccesses*/ 0, List.of(),
                    0, 0, 0, 0);

            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    answer, messages, loopResult);

            assertNotEquals(answer, out,
                    "T1 regression: verbatim Turn-1 prompt + 1-read "
                  + "loopResult must trigger N3 annotation");
            assertTrue(out.startsWith(AssistantTurnExecutor.UNDER_INSPECTION_ANNOTATION),
                    "T1 regression: UNDER_INSPECTION_ANNOTATION must be "
                  + "prepended so the user sees the correction before the "
                  + "under-inspected answer");
            assertTrue(out.contains(answer),
                    "T1 regression: original answer text must be preserved "
                  + "verbatim inside the annotated output");
        }
    }

    @Nested
    @DisplayName("Streaming no-tool truthfulness")
    class StreamingNoToolTruthfulnessTests {

        @Test
        @DisplayName("evidence-request fabrication is visibly annotated on streaming no-tool path")
        void streamingEvidenceFabricationIsAnnotated() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Check whether this website has mismatches between HTML classes/IDs and the selectors used in CSS or JavaScript. Do not change anything yet."));

            String fabricated = "Based on the workspace contents, index.html contains a CTA button, "
                    + "style.css defines `.cta-button`, and script.js wires it up with querySelector. "
                    + "There are no mismatches between the files. "
                    + "x".repeat(AssistantTurnExecutor.UNGROUNDED_MIN_CHARS);

            String out = AssistantTurnExecutor.enforceStreamingNoToolTruthfulness(fabricated, messages);

            assertTrue(out.startsWith(AssistantTurnExecutor.UNGROUNDED_ANNOTATION),
                    "streaming no-tool evidence fabrication must be visibly annotated");
            assertTrue(out.contains(fabricated));
        }

        @Test
        @DisplayName("explicit mutation no-tool narration is replaced with factual no-change notice")
        void streamingMutationNarrationIsReplaced() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("I think the html is completely wrong. Can you fix it?"));

            String fabricated = """
                    Sure! Here is the updated index.html.

                    ### Updated `index.html`
                    Summary of changes:
                    - updated index.html
                    - these changes should ensure the selectors now match
                    """;

            String out = AssistantTurnExecutor.enforceStreamingNoToolTruthfulness(fabricated, messages);

            assertEquals(AssistantTurnExecutor.STREAMING_NO_TOOL_MUTATION_REPLACEMENT, out,
                    "explicit mutation no-tool narration must not survive as final answer text");
        }

        @Test
        @DisplayName("narrow mutation narrative marker set does not flag descriptive analysis")
        void streamingMutationNarrativeMarkersStayNarrow() {
            String descriptive = "The label has been updated to read 'Weight', and the CSS class is documented below.";
            assertFalse(AssistantTurnExecutor.containsStreamingMutationNarrative(descriptive));
        }

        @Test
        @DisplayName("meta-question about tool use does not trigger explicit mutation replacement")
        void metaQuestionAboutEditToolRemainsReadOnly() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Why didn't you call the edit tool?"));

            String answer = """
                    I should have called the edit tool once you explicitly requested a change.
                    """; 

            assertFalse(AssistantTurnExecutor.shouldReplaceStreamingNoToolMutationNarrative(answer, messages));
            assertEquals(answer, AssistantTurnExecutor.enforceStreamingNoToolTruthfulness(answer, messages));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  N3 - Inspect under-completion truth layer
    //
    //  Covers the annotate-first gate that fires when the user asked for
    //  multi-file inspection ("read the entry files", "all three", …) but
    //  the turn made ≤ 1 read-only tool call and emitted a substantive
    //  answer. Annotate-only by design (a retry would require re-running
    //  the tool loop). Sibling to ClaimVsActionTests / GroundingRetryTests.
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N3 - Inspect under-completion")
    class InspectUnderCompletionTests {

        /** Long enough to pass {@link AssistantTurnExecutor#INSPECT_MIN_CHARS}. */
        private String longAnswer() {
            return "a".repeat(AssistantTurnExecutor.INSPECT_MIN_CHARS + 50);
        }

        private static List<ChatMessage> msgsWith(String userText) {
            var m = new ArrayList<ChatMessage>();
            m.add(ChatMessage.system("sys"));
            m.add(ChatMessage.user(userText));
            return m;
        }

        /** Loop result with {@code reads} read_file calls, zero mutating successes. */
        private static dev.talos.runtime.ToolCallLoop.LoopResult loopWithReads(int reads) {
            var names = new ArrayList<String>();
            for (int i = 0; i < reads; i++) names.add("talos.read_file");
            return new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", reads, reads, names, List.of(),
                    0, 0, false, /*mutatingSuccesses*/ 0, List.of(),
                    0, 0, 0, 0);
        }

        // ── Positive cases ────────────────────────────────────────────

        @Test
        @DisplayName("fires: long answer + one read + multi-file prompt marker")
        void fires_on_canonical_shape() {
            var messages = msgsWith("Read the relevant files first, then summarize.");
            String answer = longAnswer();
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    answer, messages, loopWithReads(1));
            assertTrue(out.startsWith(AssistantTurnExecutor.UNDER_INSPECTION_ANNOTATION));
            assertTrue(out.contains(answer));
        }

        @Test
        @DisplayName("fires: zero reads but tools were invoked (e.g. only list_dir-less path)")
        void fires_when_tools_invoked_but_no_reads() {
            // A turn that used a non-read tool (hypothetical) - still under-inspected.
            var messages = msgsWith("Read all the entry files and summarize.");
            String answer = longAnswer();
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 1, 1, List.of("talos.some_non_read_tool"),
                    List.of(), 0, 0, false, 0, List.of(),
                    0, 0, 0, 0);
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    answer, messages, loopResult);
            assertTrue(out.startsWith(AssistantTurnExecutor.UNDER_INSPECTION_ANNOTATION));
        }

        // ── Negative cases ────────────────────────────────────────────

        @Test
        @DisplayName("does NOT fire: two reads (inspection complete)")
        void does_not_fire_with_two_reads() {
            var messages = msgsWith("Read the relevant files first.");
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    longAnswer(), messages, loopWithReads(2));
            assertEquals(longAnswer(), out);
        }

        @Test
        @DisplayName("does NOT fire: zero tools invoked (R6 / N2 territory)")
        void does_not_fire_when_zero_tools() {
            var messages = msgsWith("Read the entry files first.");
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 0, 0, List.of(), List.of(), 0, 0, false, 0, List.of(),
                    0, 0, 0, 0);
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    longAnswer(), messages, loopResult);
            assertEquals(longAnswer(), out);
        }

        @Test
        @DisplayName("does NOT fire: mutating tool succeeded (did the work)")
        void does_not_fire_when_mutating_success() {
            var messages = msgsWith("Read the entry files then fix style.css.");
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 2, 2,
                    List.of("talos.read_file", "talos.edit_file"),
                    List.of(), 0, 0, false, /*mutatingSuccesses*/ 1, List.of(),
                    0, 0, 0, 0);
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    longAnswer(), messages, loopResult);
            assertEquals(longAnswer(), out,
                    "mutating success means the turn did real work - signal is noise");
        }

        @Test
        @DisplayName("does NOT fire: answer below INSPECT_MIN_CHARS")
        void does_not_fire_when_answer_short() {
            var messages = msgsWith("Read the relevant files first.");
            String shortAnswer = "a".repeat(AssistantTurnExecutor.INSPECT_MIN_CHARS - 1);
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    shortAnswer, messages, loopWithReads(1));
            assertEquals(shortAnswer, out);
        }

        @Test
        @DisplayName("does NOT fire: prompt has no inspect-first marker")
        void does_not_fire_without_inspect_marker() {
            var messages = msgsWith("What is the BMI formula?");
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    longAnswer(), messages, loopWithReads(1));
            assertEquals(longAnswer(), out);
        }

        @Test
        @DisplayName("does NOT fire: null or blank answer")
        void does_not_fire_on_null_or_blank_answer() {
            var messages = msgsWith("Read the entry files first.");
            assertNull(AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    null, messages, loopWithReads(1)));
            assertEquals("   ", AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    "   ", messages, loopWithReads(1)));
        }

        @Test
        @DisplayName("does NOT fire: null loopResult")
        void does_not_fire_on_null_loop_result() {
            var messages = msgsWith("Read the entry files first.");
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    longAnswer(), messages, null);
            assertEquals(longAnswer(), out);
        }

        // ── Predicate and helper invariants ───────────────────────────

        @Test
        @DisplayName("looksLikeInspectFirstRequest: transcript markers hit, generic prompts miss")
        void inspect_marker_set_discriminates() {
            assertTrue(AssistantTurnExecutor.looksLikeInspectFirstRequest(
                    "Read the relevant files first, then answer."));
            assertTrue(AssistantTurnExecutor.looksLikeInspectFirstRequest(
                    "Identify the main HTML entry file."));
            assertTrue(AssistantTurnExecutor.looksLikeInspectFirstRequest(
                    "All three components should be inspected."));
            assertTrue(AssistantTurnExecutor.looksLikeInspectFirstRequest(
                    "Start by reading the main files."));
            assertFalse(AssistantTurnExecutor.looksLikeInspectFirstRequest(
                    "What is the capital of France?"));
            assertFalse(AssistantTurnExecutor.looksLikeInspectFirstRequest(null));
            assertFalse(AssistantTurnExecutor.looksLikeInspectFirstRequest(""));
        }

        @Test
        @DisplayName("readOnlyToolCount: counts read_file / list_dir / grep, ignores others, strips talos.")
        void read_only_tool_count_is_correct() {
            var mixed = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 4, 4,
                    List.of("talos.read_file", "talos.edit_file",
                            "list_dir", "talos.grep", "talos.write_file"),
                    List.of(), 0, 0, false, 1, List.of(),
                    0, 0, 0, 0);
            assertEquals(3, AssistantTurnExecutor.readOnlyToolCount(mixed),
                    "should count read_file + list_dir + grep, not edit_file / write_file");
            assertEquals(0, AssistantTurnExecutor.readOnlyToolCount(null));
        }
    }

    @Nested
    @DisplayName("Selector mismatch grounding")
    class SelectorMismatchGroundingTests {

        @Test
        @DisplayName("selector mismatch request is overridden by deterministic workspace analysis")
        void selectorMismatchAnswerIsGroundedFromWorkspace() throws Exception {
            Path ws = Files.createTempDirectory("talos-selector-grounding-");
            try {
                Files.writeString(ws.resolve("index.html"), """
                        <!DOCTYPE html>
                        <html>
                          <body class="synthwave-theme">
                            <section id="hero">
                              <div class="hero-content"></div>
                            </section>
                          </body>
                        </html>
                        """);
                Files.writeString(ws.resolve("style.css"), """
                        body.synthwave-theme {}
                        #hero {}
                        .hero-content {}
                        .cta-button {}
                        """);
                Files.writeString(ws.resolve("script.js"), """
                        document.querySelector('.cta-button');
                        """);

                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user("Check whether this website has mismatches between HTML classes/IDs and the selectors used in CSS or JavaScript. Do not change anything yet."));

                var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                        "unused", 4, 4,
                        List.of("talos.list_dir", "talos.read_file", "talos.read_file", "talos.read_file"),
                        List.of(), 0, 0, false, 0, List.of("index.html", "style.css", "script.js"),
                        0, 0, 0, 0);

                String bogus = "There are no mismatches. The class `cta-button` is present in HTML and JavaScript.";
                String out = AssistantTurnExecutor.overrideSelectorMismatchAnalysisIfNeeded(
                        bogus, messages, loopResult, ws);

                assertNotEquals(bogus, out);
                assertTrue(out.contains("Mismatches found:"));
                assertTrue(out.contains("`.cta-button`"));
                assertFalse(out.contains("present in HTML and JavaScript"));
                assertFalse(out.contains("#ff4500"));
                assertFalse(out.contains("#ffffff"));
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }
    }

    @Nested
    @DisplayName("Read-only web diagnostics grounding")
    class ReadOnlyWebDiagnosticsGroundingTests {

        @Test
        @DisplayName("natural site diagnostic request is recognized")
        void naturalSiteDiagnosticRequestIsRecognized() {
            assertTrue(AssistantTurnExecutor.looksLikeReadOnlyWebDiagnosticRequest(
                    "This site has broken links. Can you check what is wrong without changing files?"));
        }

        @Test
        @DisplayName("natural static button review request is recognized")
        void naturalStaticButtonReviewRequestIsRecognized() {
            assertTrue(AssistantTurnExecutor.looksLikeReadOnlyWebDiagnosticRequest(
                    "Review the current static web page and say whether the button can work in a browser. "
                            + "Do not inspect protected files."));
        }

        @Test
        @DisplayName("web diagnostic request is overridden by deterministic static facts")
        void readOnlyWebDiagnosticAnswerIsGroundedFromWorkspace() throws Exception {
            Path ws = Files.createTempDirectory("talos-web-diagnostics-grounding-");
            try {
                Files.writeString(ws.resolve("index.html"), """
                        <!DOCTYPE html>
                        <html>
                          <head><link rel="stylesheet" href="styles.css"></head>
                          <body>
                            <div class="calculator-container">
                              <form id="bmi-form">
                                <button type="submit">Calculate BMI</button
                              </form>
                            </div>
                            <script src="script.js"></script
                          </body>
                        </html>
                        """);
                Files.writeString(ws.resolve("styles.css"), """
                        calculator-container { max-width: 420px; }
                        """);
                Files.writeString(ws.resolve("script.js"), """
                        document.getElementById('bmi-form');
                        """);

                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user(
                        "Inspect this BMI website and identify why it is not working. Do not edit files yet."));

                var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                        "unused", 4, 4,
                        List.of("talos.list_dir", "talos.read_file", "talos.read_file", "talos.read_file"),
                        List.of(), 0, 0, false, 0,
                        List.of("index.html", "styles.css", "script.js"),
                        0, 0, 0, 0);

                String bogus = "The issue is that the script.js file is missing a closing script tag.";
                String out = AssistantTurnExecutor.overrideReadOnlyWebDiagnosticsIfNeeded(
                        bogus, messages, loopResult, ws);

                assertNotEquals(bogus, out);
                assertTrue(out.contains("Static web diagnostics found:"), out);
                assertTrue(out.contains("index.html: malformed closing tag `</button>`"), out);
                assertTrue(out.contains("index.html: malformed closing tag `</script>`"), out);
                assertTrue(out.contains("`calculator-container` should probably be `.calculator-container`"), out);
                assertFalse(out.contains("script.js file is missing a closing script tag"));
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("script import question is grounded from current index.html")
        void scriptImportQuestionUsesCurrentIndexHtmlAfterExactOverwrite() throws Exception {
            Path ws = Files.createTempDirectory("talos-script-import-grounding-");
            try {
                Files.writeString(ws.resolve("index.html"), "AFTER\n");
                Files.writeString(ws.resolve("script.js"), "console.log('old');\n");
                Files.writeString(ws.resolve("scripts.js"), "console.log('new');\n");

                var registry = new dev.talos.tools.ToolRegistry();
                registry.register(new dev.talos.tools.impl.ReadFileTool());
                var processor = new dev.talos.runtime.TurnProcessor(
                        null, new dev.talos.runtime.NoOpApprovalGate(), registry);
                var loop = new dev.talos.runtime.ToolCallLoop(processor, 4);
                var ctx = Context.builder(new Config())
                        .llm(LlmClient.scripted(List.of(
                                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                                "index.html imports the BMI script from scripts.js.")))
                        .sandbox(new dev.talos.core.security.Sandbox(ws, java.util.Map.of()))
                        .toolRegistry(registry)
                        .toolCallLoop(loop)
                        .build();
                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user(
                        "Which file does index.html import for the BMI script, script.js or scripts.js?"));

                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, ws, ctx, new AssistantTurnExecutor.Options());

                assertTrue(out.text().contains("[Static web import check]"), out.text());
                assertTrue(out.text().contains(
                        "Neither `script.js` nor `scripts.js` is imported by `index.html`."), out.text());
                assertFalse(out.text().contains("imports the BMI script from scripts.js"), out.text());
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("static button review false success is replaced by deterministic diagnostics")
        void staticButtonReviewFalseSuccessIsGroundedFromWorkspace() throws Exception {
            Path ws = Files.createTempDirectory("talos-static-button-grounding-");
            try {
                Files.writeString(ws.resolve("index.html"), """
                        <!DOCTYPE html>
                        <html>
                          <head><link rel="stylesheet" href="styles.css"></head>
                          <body>
                            <button class="cta-button" type="button">Run action</button>
                            <p id="result">Waiting.</p>
                            <script src="script.js"></script>
                          </body>
                        </html>
                        """);
                Files.writeString(ws.resolve("styles.css"), """
                        .cta-button { color: red; }
                        """);
                Files.writeString(ws.resolve("script.js"), """
                        const button = document.querySelector('.cta-button');
                        const result = document.querySelector('#result');

                        if (button && result) {
                          button.addEventListener('click', () => {
                            result.textC;
                          });
                        }
                        """);

                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user(
                        "Review the current static web page and say whether the button can work in a browser. "
                                + "Do not inspect protected files."));

                var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                        "unused", 3, 3,
                        List.of("talos.list_dir", "talos.read_file", "talos.read_file"),
                        List.of(), 0, 0, false, 0,
                        List.of("index.html", "script.js"),
                        0, 0, 0, 0);

                String bogus = """
                        Yes - the page will work as expected in a browser.

                        Opening `index.html` in a browser will show the button and, when clicked,
                        will replace "Waiting." with "Audit action complete."
                        """;
                String out = AssistantTurnExecutor.overrideReadOnlyWebDiagnosticsIfNeeded(
                        bogus, messages, loopResult, ws);

                assertNotEquals(bogus, out);
                assertTrue(out.contains("Static web diagnostics found:"), out);
                assertTrue(out.contains("script.js"), out);
                assertTrue(out.contains("does not assign visible result text"), out);
                assertFalse(out.contains("will work as expected"), out);
                assertFalse(out.contains("will replace \"Waiting.\""), out);
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("static button review reports missing button and script linkage from read evidence")
        void staticButtonReviewReportsMissingButtonAndScriptLinkage() throws Exception {
            Path ws = Files.createTempDirectory("talos-static-button-missing-linkage-");
            try {
                Files.writeString(ws.resolve("index.html"), """
                        <!DOCTYPE html>
                        <html>
                          <head><link rel="stylesheet" href="styles.css"></head>
                          <body>
                            <main>
                              <h1>Focused Button</h1>
                              <p id="result">Waiting.</p>
                            </main>
                          </body>
                        </html>
                        """);
                Files.writeString(ws.resolve("styles.css"), "body { font-family: sans-serif; }\n");
                Files.writeString(ws.resolve("script.js"), """
                        const button = document.querySelector('.cta-button');
                        const result = document.querySelector('#result');

                        if (button && result) {
                          button.addEventListener('click', () => {
                            result.textC;
                          });
                        }
                        """);

                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user(
                        "Review the current static web page and say whether the button can work in a browser. "
                                + "Do not inspect protected files."));

                var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                        "unused", 2, 2,
                        List.of("talos.read_file", "talos.read_file"),
                        List.of(), 0, 0, false, 0,
                        List.of("index.html", "script.js"),
                        0, 0, 0, 0);

                String bogus = "The button can work in a browser. No issues found.";
                String out = AssistantTurnExecutor.overrideReadOnlyWebDiagnosticsIfNeeded(
                        bogus, messages, loopResult, ws);

                assertNotEquals(bogus, out);
                assertTrue(out.contains("Static web diagnostics found:"), out);
                assertTrue(out.contains("HTML does not link JavaScript file: `script.js`"), out);
                assertTrue(out.contains("JavaScript references missing class selectors: `.cta-button`"), out);
                assertTrue(out.contains("does not assign visible result text"), out);
                assertFalse(out.contains("did not find obvious"), out);
                assertFalse(out.contains("No issues found"), out);
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("plan static-web diagnosis does not claim unread CSS as inspected")
        void planStaticWebDiagnosisDoesNotClaimUnreadCssAsInspected() throws Exception {
            Path ws = Files.createTempDirectory("talos-plan-static-web-unread-css-");
            try {
                Files.writeString(ws.resolve("index.html"), """
                        <!doctype html>
                        <html>
                          <head><link rel="stylesheet" href="styles.css"></head>
                          <body>
                            <button class="real-button" type="button">Run</button>
                            <p id="result">Waiting.</p>
                            <script src="script.js"></script>
                          </body>
                        </html>
                        """);
                Files.writeString(ws.resolve("styles.css"), ".real-button { color: green; }\n");
                Files.writeString(ws.resolve("script.js"), """
                        const button = document.querySelector('.missing-button');
                        const result = document.querySelector('#result');

                        if (button && result) {
                          button.addEventListener('click', () => {
                            result.textContent = 'Done';
                          });
                        }
                        """);

                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user(
                        "Plan a fix for the static page so the button can work. "
                                + "Inspect only index.html and script.js; do not read .env and do not edit."));

                var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                        "I inspected index.html, styles.css, and script.js. The CSS is fine.",
                        2,
                        2,
                        List.of("talos.read_file", "talos.read_file"),
                        List.of(),
                        0,
                        0,
                        false,
                        0,
                        List.of("index.html", "script.js"),
                        0,
                        0,
                        0,
                        0);

                String out = AssistantTurnExecutor.overrideReadOnlyWebDiagnosticsIfNeeded(
                        loopResult.finalAnswer(), messages, loopResult, ws);

                assertNotEquals(loopResult.finalAnswer(), out);
                assertTrue(out.contains("Static web diagnostics found:"), out);
                assertTrue(out.contains("HTML: `index.html`"), out);
                assertTrue(out.contains("JavaScript: `script.js`"), out);
                assertFalse(out.contains("CSS: `styles.css`"), out);
                assertTrue(out.contains("JavaScript references missing class selectors: `.missing-button`"), out);
                assertTrue(out.contains("Implementation plan:"), out);
                assertFalse(out.contains("The CSS is fine"), out);
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("static button review grounds html-only underinspection when script is visible but unlinked")
        void staticButtonReviewGroundsHtmlOnlyUnderinspectionWhenVisibleScriptIsUnlinked() throws Exception {
            Path ws = Files.createTempDirectory("talos-static-button-html-only-underinspection-");
            try {
                Files.writeString(ws.resolve("README.md"), "# Audit fixture\n");
                Files.writeString(ws.resolve("notes.md"), "Private note marker.\n");
                Files.writeString(ws.resolve("config.json"), "{\"project\":\"audit\"}\n");
                Files.writeString(ws.resolve("report.docx"), "fake unsupported binary payload");
                Files.writeString(ws.resolve("index.html"), """
                        <!doctype html>
                        <html lang="en">
                        <head>
                          <meta charset="utf-8">
                          <link rel="stylesheet" href="styles.css">
                        </head>
                        <body>
                          <main>
                            <h1>Focused Button</h1>
                            <p id="result" aria-live="polite">Waiting.</p>
                          </main>
                        </body>
                        </html>
                        """);
                Files.writeString(ws.resolve("styles.css"), "body { font-family: sans-serif; }\n");
                Files.writeString(ws.resolve("script.js"), """
                        const button = document.querySelector('.cta-button');
                        const result = document.querySelector('#result');

                        if (button && result) {
                          button.addEventListener('click', () => {
                            result.textC;
                          });
                        }
                        """);

                var registry = new dev.talos.tools.ToolRegistry();
                registry.register(new dev.talos.tools.impl.ReadFileTool());
                var processor = new dev.talos.runtime.TurnProcessor(
                        null, new dev.talos.runtime.NoOpApprovalGate(), registry);
                var loop = new dev.talos.runtime.ToolCallLoop(processor, 6);
                var llm = ScriptedNativeLlmClient.of(List.of(
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_0",
                                "talos.read_file",
                                java.util.Map.of("path", "index.html")))),
                        new LlmClient.StreamResult("""
                                The provided index.html file does not include any buttons or JavaScript code.

                                To make the button functional, create or update script.js:

                                ```javascript
                                document.getElementById('myButton').addEventListener('click', function() {
                                  document.getElementById('result').textC;
                                });
                                ```

                                With these changes, the button should work in a browser.
                                """, List.of())));
                var ctx = Context.builder(new Config())
                        .llm(llm)
                        .sandbox(new dev.talos.core.security.Sandbox(ws, java.util.Map.of()))
                        .toolRegistry(registry)
                        .toolCallLoop(loop)
                        .build();
                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user(
                        "Review the current static web page and say whether the button can work in a browser. "
                                + "Do not inspect protected files."));

                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, ws, ctx, new AssistantTurnExecutor.Options());

                assertTrue(out.text().contains("Static web diagnostics found:"), out.text());
                assertTrue(out.text().contains(
                        "index.html: inspected HTML does not link a local JavaScript file."), out.text());
                assertFalse(out.text().contains("JavaScript references missing class selectors"), out.text());
                assertFalse(out.text().contains("does not assign visible result text"), out.text());
                assertFalse(out.text().contains("With these changes, the button should work in a browser"),
                        out.text());
                assertFalse(out.text().contains("document.getElementById('myButton')"), out.text());
                assertFalse(out.text().contains("result').textC"), out.text());
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("static button diagnostics survive primary-file completeness retry")
        void staticButtonDiagnosticsSurviveInspectCompletenessRetry() throws Exception {
            Path ws = Files.createTempDirectory("talos-static-button-retry-grounding-");
            try {
                Files.writeString(ws.resolve("index.html"), """
                        <!DOCTYPE html>
                        <html>
                          <head><link rel="stylesheet" href="styles.css"></head>
                          <body>
                            <button class="cta-button" type="button">Run action</button>
                            <p id="result">Waiting.</p>
                            <script src="script.js"></script>
                          </body>
                        </html>
                        """);
                Files.writeString(ws.resolve("styles.css"), """
                        .cta-button { color: red; }
                        """);
                Files.writeString(ws.resolve("script.js"), """
                        const button = document.querySelector('.cta-button');
                        const result = document.querySelector('#result');

                        if (button && result) {
                          button.addEventListener('click', () => {
                            result.textC;
                          });
                        }
                        """);

                var registry = new dev.talos.tools.ToolRegistry();
                registry.register(new dev.talos.tools.impl.ReadFileTool());
                var processor = new dev.talos.runtime.TurnProcessor(
                        null, new dev.talos.runtime.NoOpApprovalGate(), registry);
                var loop = new dev.talos.runtime.ToolCallLoop(processor, 6);
                var llm = ScriptedNativeLlmClient.of(List.of(
                        new LlmClient.StreamResult("", List.of(
                                new ChatMessage.NativeToolCall(
                                        "call_0",
                                        "talos.read_file",
                                        java.util.Map.of("path", "index.html")),
                                new ChatMessage.NativeToolCall(
                                        "call_1",
                                        "talos.read_file",
                                        java.util.Map.of("path", "script.js")))),
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_2",
                                "talos.read_file",
                                java.util.Map.of("path", "styles.css")))),
                        new LlmClient.StreamResult("""
                                I apologize for the oversight. The button issue can be fixed by changing:

                                ```js
                                result.textC;
                                ```

                                to:

                                ```js
                                result.textC;
                                ```

                                After making this change, the button should work correctly.
                                """, List.of())));
                var ctx = Context.builder(new Config())
                        .llm(llm)
                        .sandbox(new dev.talos.core.security.Sandbox(ws, java.util.Map.of()))
                        .toolRegistry(registry)
                        .toolCallLoop(loop)
                        .build();
                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user(
                        "Review the current static web page and say whether the button can work in a browser. "
                                + "Do not inspect protected files."));

                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, ws, ctx, new AssistantTurnExecutor.Options());

                assertTrue(out.text().contains("Static web diagnostics found:"), out.text());
                assertTrue(out.text().contains("script.js"), out.text());
                assertTrue(out.text().contains("does not assign visible result text"), out.text());
                assertFalse(out.text().contains("After making this change, the button should work correctly"),
                        out.text());
                assertFalse(out.text().contains("I apologize for the oversight"), out.text());
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("static button review continues once to read linked script in full audit fixture")
        void staticButtonReviewReadsLinkedScriptWhenFullFixtureSkipsPrimaryRetry() throws Exception {
            Path ws = Files.createTempDirectory("talos-static-button-linked-script-continuation-");
            try {
                Files.writeString(ws.resolve("README.md"), "# Audit fixture\n");
                Files.writeString(ws.resolve("notes.md"), "Private note marker.\n");
                Files.writeString(ws.resolve("config.json"), "{\"project\":\"audit\"}\n");
                Files.writeString(ws.resolve("report.docx"), "fake unsupported binary payload");
                Files.writeString(ws.resolve("index.html"), """
                        <!DOCTYPE html>
                        <html>
                          <head><link rel="stylesheet" href="styles.css"></head>
                          <body>
                            <button class="cta-button" type="button">Run action</button>
                            <p id="result">Waiting.</p>
                            <script src="script.js"></script>
                          </body>
                        </html>
                        """);
                Files.writeString(ws.resolve("styles.css"), ".cta-button { color: red; }\n");
                Files.writeString(ws.resolve("script.js"), """
                        const button = document.querySelector('.cta-button');
                        const result = document.querySelector('#result');

                        if (button && result) {
                          button.addEventListener('click', () => {
                            result.textC;
                          });
                        }
                        """);

                var registry = new dev.talos.tools.ToolRegistry();
                registry.register(new dev.talos.tools.impl.ReadFileTool());
                var processor = new dev.talos.runtime.TurnProcessor(
                        null, new dev.talos.runtime.NoOpApprovalGate(), registry);
                var loop = new dev.talos.runtime.ToolCallLoop(processor, 6);
                var llm = ScriptedNativeLlmClient.of(List.of(
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_0",
                                "talos.read_file",
                                java.util.Map.of("path", "index.html")))),
                        new LlmClient.StreamResult("Yes, the button will work in a browser.", List.of()),
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_1",
                                "talos.read_file",
                                java.util.Map.of("path", "script.js")))),
                        new LlmClient.StreamResult("""
                                After reading the script, the button works correctly and is ready to use.
                                """, List.of())));
                var ctx = Context.builder(new Config())
                        .llm(llm)
                        .sandbox(new dev.talos.core.security.Sandbox(ws, java.util.Map.of()))
                        .toolRegistry(registry)
                        .toolCallLoop(loop)
                        .build();
                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user(
                        "Review the current static web page and say whether the button can work in a browser. "
                                + "Do not inspect protected files."));

                AssistantTurnExecutor.TurnOutput out;
                LocalTurnTrace trace;
                LocalTurnTraceCapture.begin(
                        "trc-t196-read-only-continuation-summary",
                        "sid",
                        1,
                        "2026-05-07T00:00:00Z",
                        "workspace",
                        "test",
                        "scripted",
                        "scripted",
                        messages.get(messages.size() - 1).content());
                try {
                    out = AssistantTurnExecutor.execute(
                            messages, ws, ctx, new AssistantTurnExecutor.Options());
                    trace = LocalTurnTraceCapture.complete();
                } finally {
                    LocalTurnTraceCapture.clear();
                }

                assertTrue(out.text().contains("Static web diagnostics found:"), out.text());
                assertTrue(out.text().contains("script.js"), out.text());
                assertTrue(out.text().contains("does not assign visible result text"), out.text());
                assertEquals(1, countOccurrences(out.text(), "[Used "), out.text());
                assertTrue(out.text().contains("[Used 2 tool(s): talos.read_file | 2 iteration(s)"),
                        out.text());
                assertTrue(out.text().contains("read: index.html, script.js"), out.text());
                long tracedReadCalls = trace.events().stream()
                        .filter(event -> "TOOL_CALL_PARSED".equals(event.type()))
                        .filter(event -> "talos.read_file".equals(event.toolName()))
                        .count();
                assertEquals(2, tracedReadCalls, trace.events().toString());
                assertFalse(out.text().contains("ready to use"), out.text());
                assertFalse(out.text().contains("button works correctly"), out.text());
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("linked script inspect continuation ignores protected and external scripts")
        void linkedScriptInspectContinuationIgnoresProtectedAndExternalScripts() throws Exception {
            Path ws = Files.createTempDirectory("talos-static-button-linked-script-safe-targets-");
            try {
                Files.writeString(ws.resolve("index.html"), """
                        <!DOCTYPE html>
                        <html>
                          <body>
                            <script src="https://cdn.example.invalid/app.js"></script>
                            <script src="//cdn.example.invalid/other.js"></script>
                            <script src=".env.secret.js"></script>
                            <script src="script.js"></script>
                          </body>
                        </html>
                        """);
                Files.writeString(ws.resolve(".env.secret.js"), "const secret = 'protected';\n");
                Files.writeString(ws.resolve("script.js"), "console.log('public');\n");
                var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                        "unused", 1, 1,
                        List.of("talos.read_file"),
                        List.of(), 0, 0, false, 0,
                        List.of("index.html"),
                        0, 0, 0, 0,
                        List.of(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                "talos.read_file",
                                "index.html",
                                true,
                                false,
                                false,
                                "read index.html",
                                "")));

                List<String> missing = AssistantTurnExecutor.missingInspectReads(ws, loopResult);

                assertEquals(List.of("script.js"), missing);
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("linked script continuation failure keeps evidence-incomplete containment")
        void linkedScriptContinuationNoToolRetryKeepsEvidenceIncompleteContainment() throws Exception {
            Path ws = Files.createTempDirectory("talos-static-button-linked-script-no-tool-");
            try {
                Files.writeString(ws.resolve("README.md"), "# Audit fixture\n");
                Files.writeString(ws.resolve("notes.md"), "Private note marker.\n");
                Files.writeString(ws.resolve("config.json"), "{\"project\":\"audit\"}\n");
                Files.writeString(ws.resolve("report.docx"), "fake unsupported binary payload");
                Files.writeString(ws.resolve("index.html"), """
                        <!DOCTYPE html>
                        <html>
                          <head><link rel="stylesheet" href="styles.css"></head>
                          <body>
                            <button class="cta-button" type="button">Run action</button>
                            <p id="result">Waiting.</p>
                            <script src="script.js"></script>
                          </body>
                        </html>
                        """);
                Files.writeString(ws.resolve("styles.css"), ".cta-button { color: red; }\n");
                Files.writeString(ws.resolve("script.js"), "result.textC;\n");

                var registry = new dev.talos.tools.ToolRegistry();
                registry.register(new dev.talos.tools.impl.ReadFileTool());
                var processor = new dev.talos.runtime.TurnProcessor(
                        null, new dev.talos.runtime.NoOpApprovalGate(), registry);
                var loop = new dev.talos.runtime.ToolCallLoop(processor, 6);
                var llm = ScriptedNativeLlmClient.of(List.of(
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_0",
                                "talos.read_file",
                                java.util.Map.of("path", "index.html")))),
                        new LlmClient.StreamResult("Yes, the button will work in a browser.", List.of()),
                        new LlmClient.StreamResult("No more reads are needed. The page works.", List.of())));
                var ctx = Context.builder(new Config())
                        .llm(llm)
                        .sandbox(new dev.talos.core.security.Sandbox(ws, java.util.Map.of()))
                        .toolRegistry(registry)
                        .toolCallLoop(loop)
                        .build();
                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user(
                        "Review the current static web page and say whether the button can work in a browser. "
                                + "Do not inspect protected files."));

                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, ws, ctx, new AssistantTurnExecutor.Options());

                assertTrue(out.text().contains("[Evidence incomplete"), out.text());
                assertTrue(out.text().contains("linked script source target(s): script.js"), out.text());
                assertFalse(out.text().contains("Static web diagnostics found:"), out.text());
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("static button review is not grounded from unread linked script evidence")
        void staticButtonReviewDoesNotGroundWhenLinkedScriptWasNotRead() throws Exception {
            Path ws = Files.createTempDirectory("talos-static-button-unread-script-");
            try {
                Files.writeString(ws.resolve("index.html"), """
                        <!DOCTYPE html>
                        <html>
                          <head><link rel="stylesheet" href="styles.css"></head>
                          <body>
                            <button class="cta-button" type="button">Run action</button>
                            <p id="result">Waiting.</p>
                            <script src="script.js"></script>
                          </body>
                        </html>
                        """);
                Files.writeString(ws.resolve("styles.css"), ".cta-button { color: red; }\n");
                Files.writeString(ws.resolve("script.js"), "result.textC;\n");

                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user(
                        "Review the current static web page and say whether the button can work in a browser. "
                                + "Do not inspect protected files."));

                var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                        "unused", 1, 1,
                        List.of("talos.read_file"),
                        List.of(), 0, 0, false, 0,
                        List.of("index.html"),
                        0, 0, 0, 0);

                String answer = "I only read index.html.";
                String out = AssistantTurnExecutor.overrideReadOnlyWebDiagnosticsIfNeeded(
                        answer, messages, loopResult, ws);

                assertEquals(answer, out);
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("candidate-only script import question is grounded from current index.html")
        void candidateOnlyScriptImportQuestionUsesCurrentIndexHtmlAfterExactOverwrite() throws Exception {
            Path ws = Files.createTempDirectory("talos-script-import-candidate-grounding-");
            try {
                Files.writeString(ws.resolve("index.html"), "AFTER\n");
                Files.writeString(ws.resolve("script.js"), "console.log('old');\n");
                Files.writeString(ws.resolve("scripts.js"), "console.log('new');\n");

                var registry = new dev.talos.tools.ToolRegistry();
                registry.register(new dev.talos.tools.impl.ReadFileTool());
                var processor = new dev.talos.runtime.TurnProcessor(
                        null, new dev.talos.runtime.NoOpApprovalGate(), registry);
                var loop = new dev.talos.runtime.ToolCallLoop(processor, 4);
                var ctx = Context.builder(new Config())
                        .llm(LlmClient.scripted(List.of(
                                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                                "The BMI calculation is in scripts.js.")))
                        .sandbox(new dev.talos.core.security.Sandbox(ws, java.util.Map.of()))
                        .toolRegistry(registry)
                        .toolCallLoop(loop)
                        .build();
                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user(
                        "Which exact file currently imports the BMI script, script.js or scripts.js? "
                                + "Verify from current files and answer only after inspection. "
                                + "Do not read protected files."));

                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, ws, ctx, new AssistantTurnExecutor.Options());

                assertTrue(out.text().contains("[Static web import check]"), out.text());
                assertTrue(out.text().contains(
                        "Neither `script.js` nor `scripts.js` is imported by `index.html`."), out.text());
                assertFalse(out.text().contains("The BMI calculation is in scripts.js"), out.text());
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("candidate-only script import grounding works in full audit fixture shape")
        void scriptImportGroundingUsesInferredIndexHtmlInFullAuditFixtureShape() throws Exception {
            Path ws = Files.createTempDirectory("talos-script-import-audit-fixture-grounding-");
            try {
                Files.writeString(ws.resolve("README.md"), "# Audit fixture\n");
                Files.writeString(ws.resolve("notes.md"), "Private note marker.\n");
                Files.writeString(ws.resolve("config.json"), "{\"project\":\"audit\"}\n");
                Files.writeString(ws.resolve("report.docx"), "fake unsupported binary payload");
                Files.writeString(ws.resolve("index.html"), "AFTER\n");
                Files.writeString(ws.resolve("script.js"), "console.log('old');\n");
                Files.writeString(ws.resolve("scripts.js"), "console.log('new');\n");
                Files.writeString(ws.resolve("styles.css"), "body { margin: 0; }\n");

                var registry = new dev.talos.tools.ToolRegistry();
                registry.register(new dev.talos.tools.impl.ReadFileTool());
                var processor = new dev.talos.runtime.TurnProcessor(
                        null, new dev.talos.runtime.NoOpApprovalGate(), registry);
                var loop = new dev.talos.runtime.ToolCallLoop(processor, 4);
                var ctx = Context.builder(new Config())
                        .llm(LlmClient.scripted(List.of(
                                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"script.js\"}}",
                                "script.js imports the BMI script.")))
                        .sandbox(new dev.talos.core.security.Sandbox(ws, java.util.Map.of()))
                        .toolRegistry(registry)
                        .toolCallLoop(loop)
                        .build();
                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user("Search for the selector .missing-button using workspace search."));
                messages.add(ChatMessage.assistant(
                        "[Static selector search]\nscript.js:1 | const button = document.querySelector('.missing-button');"));
                messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));
                messages.add(ChatMessage.assistant("""
                        [Static verification: passed - Exact content verification passed.]

                        [ok] Updated index.html (1 lines, 5 bytes)
                        """));
                messages.add(ChatMessage.user(
                        "Which exact file currently imports the BMI script, script.js or scripts.js? "
                                + "Verify from current files and answer only after inspection. "
                                + "Do not read protected files."));

                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, ws, ctx, new AssistantTurnExecutor.Options());

                assertTrue(out.text().contains("[Static web import check]"), out.text());
                assertTrue(out.text().contains(
                        "Neither `script.js` nor `scripts.js` is imported by `index.html`."), out.text());
                assertTrue(out.text().contains("Current script imports found in `index.html`: none."),
                        out.text());
                assertFalse(out.text().contains("script.js imports the BMI script."), out.text());
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("script import grounding wins after prior exact-write history")
        void scriptImportGroundingWinsAfterPriorExactWriteHistory() throws Exception {
            Path ws = Files.createTempDirectory("talos-script-import-history-grounding-");
            try {
                Files.writeString(ws.resolve("index.html"), "AFTER\n");
                Files.writeString(ws.resolve("script.js"), "console.log('old');\n");
                Files.writeString(ws.resolve("scripts.js"),
                        "console.log('alternate script file present but not imported initially');\n");

                var registry = new dev.talos.tools.ToolRegistry();
                registry.register(new dev.talos.tools.impl.ReadFileTool());
                var processor = new dev.talos.runtime.TurnProcessor(
                        null, new dev.talos.runtime.NoOpApprovalGate(), registry);
                var loop = new dev.talos.runtime.ToolCallLoop(processor, 4);
                var ctx = Context.builder(new Config())
                        .llm(LlmClient.scripted(List.of(
                                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"scripts.js\"}}",
                                """
                                Based on the current contents of the files, `scripts.js` contains the reference to the BMI script.

                                [Static verification: passed - Exact content verification passed.]

                                [ok] Confirmed that `scripts.js` contains the reference to the BMI script.
                                """)))
                        .sandbox(new dev.talos.core.security.Sandbox(ws, java.util.Map.of()))
                        .toolRegistry(registry)
                        .toolCallLoop(loop)
                        .build();
                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user("Search for the selector .missing-button using workspace search."));
                messages.add(ChatMessage.assistant(
                        "[Static selector search]\nscript.js:1 | const button = document.querySelector('.missing-button');"));
                messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));
                messages.add(ChatMessage.assistant("""
                        [Static verification: passed - Exact content verification passed.]

                        [ok] Updated index.html (1 lines, 5 bytes)
                        """));
                messages.add(ChatMessage.user(
                        "Which exact file currently imports the BMI script, script.js or scripts.js? "
                                + "Verify from current files and answer only after inspection. "
                                + "Do not read protected files."));

                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, ws, ctx, new AssistantTurnExecutor.Options());

                assertTrue(out.text().contains("[Static web import check]"), out.text());
                assertTrue(out.text().contains(
                        "Neither `script.js` nor `scripts.js` is imported by `index.html`."), out.text());
                assertTrue(out.text().contains("Current script imports found in `index.html`: none."),
                        out.text());
                assertFalse(out.text().contains("Confirmed that `scripts.js` contains the reference"),
                        out.text());
                assertFalse(out.text().contains("[Static verification: passed - Exact content verification passed.]"),
                        out.text());
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("script import grounding wins with native tool-call response")
        void scriptImportGroundingWinsWithNativeToolCallResponse() throws Exception {
            Path ws = Files.createTempDirectory("talos-script-import-native-grounding-");
            try {
                Files.writeString(ws.resolve("index.html"), "AFTER\n");
                Files.writeString(ws.resolve("script.js"), "console.log('old');\n");
                Files.writeString(ws.resolve("scripts.js"),
                        "console.log('alternate script file present but not imported initially');\n");

                var registry = new dev.talos.tools.ToolRegistry();
                registry.register(new dev.talos.tools.impl.ReadFileTool());
                var processor = new dev.talos.runtime.TurnProcessor(
                        null, new dev.talos.runtime.NoOpApprovalGate(), registry);
                var loop = new dev.talos.runtime.ToolCallLoop(processor, 4);
                var llm = ScriptedNativeLlmClient.of(List.of(
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_0",
                                "talos.read_file",
                                java.util.Map.of("path", "scripts.js")))),
                        new LlmClient.StreamResult("""
                                Based on the current contents of the files, `scripts.js` contains the reference to the BMI script.

                                [Static verification: passed - Exact content verification passed.]

                                [ok] Confirmed that `scripts.js` contains the reference to the BMI script.
                                """, List.of())));
                var ctx = Context.builder(new Config())
                        .llm(llm)
                        .sandbox(new dev.talos.core.security.Sandbox(ws, java.util.Map.of()))
                        .toolRegistry(registry)
                        .toolCallLoop(loop)
                        .build();
                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user("Search for the selector .missing-button using workspace search."));
                messages.add(ChatMessage.assistant(
                        "[Static selector search]\nscript.js:1 | const button = document.querySelector('.missing-button');"));
                messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));
                messages.add(ChatMessage.assistant("""
                        [Static verification: passed - Exact content verification passed.]

                        [ok] Updated index.html (1 lines, 5 bytes)
                        """));
                messages.add(ChatMessage.user(
                        "Which exact file currently imports the BMI script, script.js or scripts.js? "
                                + "Verify from current files and answer only after inspection. "
                                + "Do not read protected files."));

                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, ws, ctx, new AssistantTurnExecutor.Options());

                assertTrue(out.text().contains("[Static web import check]"), out.text());
                assertTrue(out.text().contains(
                        "Neither `script.js` nor `scripts.js` is imported by `index.html`."), out.text());
                assertTrue(out.text().contains("Current script imports found in `index.html`: none."),
                        out.text());
                assertFalse(out.text().contains("Confirmed that `scripts.js` contains the reference"),
                        out.text());
                assertFalse(out.text().contains("[Static verification: passed - Exact content verification passed.]"),
                        out.text());
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("script import grounding uses stable turn request after internal retry messages")
        void scriptImportGroundingUsesStableTurnRequestAfterInternalRetryMessages() throws Exception {
            Path ws = Files.createTempDirectory("talos-script-import-plan-grounding-");
            try {
                Files.writeString(ws.resolve("index.html"), "AFTER\n");
                Files.writeString(ws.resolve("script.js"), "console.log('old');\n");
                Files.writeString(ws.resolve("scripts.js"), "console.log('new');\n");

                String originalRequest = "Which exact file currently imports the BMI script, "
                        + "script.js or scripts.js? Verify from current files and answer only after inspection. "
                        + "Do not read protected files.";
                var plan = CurrentTurnPlan.create(
                        TaskContractResolver.fromUserRequest(originalRequest),
                        ExecutionPhase.INSPECT,
                        List.of("talos.read_file"),
                        List.of(),
                        List.of());
                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user(originalRequest));
                messages.add(ChatMessage.assistant("The current file importing the BMI script is scripts.js."));
                messages.add(ChatMessage.user(
                        "Your previous answer was produced without reading any files. "
                                + "Use the available file tools to read the relevant files, then answer."));

                var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                        "The current file importing the BMI script is scripts.js.",
                        2,
                        2,
                        List.of("talos.read_file", "talos.read_file"),
                        List.of(),
                        0,
                        0,
                        false,
                        0,
                        List.of("index.html", "scripts.js"),
                        0,
                        0,
                        0,
                        0);

                ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                        "The current file importing the BMI script is scripts.js.",
                        plan,
                        messages,
                        loopResult,
                        ws,
                        0);

                assertTrue(outcome.finalAnswer().contains("[Static web import check]"), outcome.finalAnswer());
                assertTrue(outcome.finalAnswer().contains(
                        "Neither `script.js` nor `scripts.js` is imported by `index.html`."),
                        outcome.finalAnswer());
                assertFalse(outcome.finalAnswer().contains("importing the BMI script is scripts.js"),
                        outcome.finalAnswer());
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }

        @Test
        @DisplayName("read-only tool-loop limit without runtime-owned answer is advisory")
        void readOnlyToolLoopLimitWithoutRuntimeOwnedAnswerIsAdvisory() {
            String request = "Read README.md and config.json, then compare them using current file evidence.";
            var plan = CurrentTurnPlan.create(
                    TaskContractResolver.fromUserRequest(request),
                    ExecutionPhase.INSPECT,
                    List.of("talos.read_file"),
                    List.of(),
                    List.of());
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(request));

            String exhaustedAnswer = """
                    [Tool-call limit reached. Some tool calls were not executed.]

                    Everything is complete and ready.
                    """;
            var toolOutcomes = List.of(
                    new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                            "talos.read_file", "README.md", true, false, "read README.md", ""),
                    new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                            "talos.read_file", "config.json", true, false, "read config.json", ""));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    exhaustedAnswer,
                    10,
                    6,
                    List.of("talos.read_file", "talos.read_file", "talos.read_file",
                            "talos.read_file", "talos.read_file", "talos.read_file"),
                    messages,
                    0,
                    0,
                    true,
                    0,
                    List.of("README.md", "config.json"),
                    0,
                    0,
                    0,
                    0,
                    toolOutcomes);

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    exhaustedAnswer, plan, messages, loopResult, null, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
            assertEquals(dev.talos.runtime.outcome.TaskCompletionStatus.ADVISORY_ONLY,
                    outcome.taskOutcome().completionStatus());
            assertTrue(outcome.finalAnswer().contains("tool-call limit"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("did not complete"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Everything is complete and ready"), outcome.finalAnswer());
            assertTrue(outcome.taskOutcome().warnings().stream()
                            .anyMatch(warning -> warning.message().contains("tool-call limit")),
                    outcome.taskOutcome().warnings().toString());
        }

        @Test
        @DisplayName("read-only tool-loop limit records advisory trace outcome")
        void readOnlyToolLoopLimitRecordsAdvisoryTraceOutcome(@TempDir Path ws) throws Exception {
            Files.writeString(ws.resolve("README.md"), "Project README evidence.\n");
            Files.writeString(ws.resolve("config.json"), "{\"mode\":\"test\"}\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 2);
            var ctx = Context.builder(new Config())
                    .llm(ScriptedNativeLlmClient.of(List.of(
                            new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                    "call_0",
                                    "talos.read_file",
                                    java.util.Map.of("path", "README.md")))),
                            new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                    "call_1",
                                    "talos.read_file",
                                    java.util.Map.of("path", "config.json")))))))
                    .sandbox(new dev.talos.core.security.Sandbox(ws, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Read README.md and config.json, then compare them using current file evidence."));

            LocalTurnTraceCapture.begin(
                    "trc-t185-read-only-limit",
                    "sid",
                    185,
                    "2026-05-07T00:00:00Z",
                    "workspace-hash",
                    "test",
                    "test-backend",
                    "test-model",
                    messages.getLast().content());
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, ws, ctx, new AssistantTurnExecutor.Options());
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertTrue(out.text().contains("Read-only evidence incomplete"), out.text());
                assertTrue(out.text().contains("tool-call limit"), out.text());
                assertEquals("ADVISORY_ONLY", trace.outcome().status());
                assertEquals("ADVISORY_ONLY", trace.outcome().classification());
                assertTrue(trace.warnings().stream()
                                .anyMatch(warning -> "READ_ONLY_TOOL_LOOP_LIMIT".equals(warning.code())),
                        trace.warnings().toString());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        @DisplayName("selector search no-match from html-only grep is grounded against current static files")
        void selectorSearchNoMatchFromHtmlOnlyGrepIsGroundedAgainstCurrentStaticFiles(@TempDir Path ws)
                throws Exception {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html><body><button id="run">Run</button><script src="script.js"></script></body></html>
                    """);
            Files.writeString(ws.resolve("styles.css"), "body { font-family: sans-serif; }\n");
            Files.writeString(ws.resolve("script.js"),
                    "const button = document.querySelector('.missing-button');\n");
            Files.writeString(ws.resolve(".env"), "FAKE_SECRET_DO_NOT_READ=protected-marker\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.GrepTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 5);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.grep\",\"arguments\":{\"pattern\":\".missing-button\",\"include\":\"*.html\"}}",
                            "No matches were found in the workspace.")))
                    .sandbox(new dev.talos.core.security.Sandbox(ws, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Search for the selector .missing-button using workspace search. "
                            + "Return matching file and line only; do not read full files and do not read protected files."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, ws, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("[Static selector search]"), out.text());
            assertTrue(out.text().contains(
                    "script.js:1 | const button = document.querySelector('.missing-button');"), out.text());
            assertFalse(out.text().contains("No matches were found in the workspace"), out.text());
            assertFalse(out.text().contains("FAKE_SECRET_DO_NOT_READ"), out.text());
        }

        @Test
        @DisplayName("selector search no-match after invalid comma glob retry is grounded against js files")
        void selectorSearchNoMatchAfterInvalidCommaGlobRetryIsGroundedAgainstJsFiles(@TempDir Path ws)
                throws Exception {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html><body><button id="run">Run</button><script src="script.js"></script></body></html>
                    """);
            Files.writeString(ws.resolve("styles.css"), ".run { color: blue; }\n");
            Files.writeString(ws.resolve("script.js"),
                    "const button = document.querySelector('.missing-button');\n");

            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.GrepTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 6);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.grep\",\"arguments\":{\"pattern\":\".missing-button\",\"include\":\"*.css,*.html\"}}",
                            "{\"name\":\"talos.grep\",\"arguments\":{\"pattern\":\".missing-button\",\"include\":\"*.{html,css}\"}}",
                            "There are no matching selectors in .html or .css files.")))
                    .sandbox(new dev.talos.core.security.Sandbox(ws, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Search for the selector .missing-button using workspace search. "
                            + "Return matching file and line only; do not read full files and do not read protected files."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, ws, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("[Static selector search]"), out.text());
            assertTrue(out.text().contains(
                    "script.js:1 | const button = document.querySelector('.missing-button');"), out.text());
            assertFalse(out.text().contains("There are no matching selectors"), out.text());
        }

        @Test
        @DisplayName("mutation requests do not use read-only web diagnostic override")
        void mutationRequestsAreNotOverridden() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Fix this BMI website."));

            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 1, 1,
                    List.of("talos.read_file"), List.of(),
                    0, 0, false, 0, List.of("index.html"),
                    0, 0, 0, 0);

            String answer = "I can fix it.";
            assertEquals(answer, AssistantTurnExecutor.overrideReadOnlyWebDiagnosticsIfNeeded(
                    answer, messages, loopResult, WS));
        }
    }

    @Nested
    @DisplayName("Verified follow-up summaries")
    class VerifiedFollowUpSummaries {

        @Test
        void staticWebDiagnosticFollowUpUsesPreviousRuntimeOwnedDiagnostics(@TempDir Path workspace)
                throws Exception {
            var ctx = scriptedContext("The button should work now.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Review the current static web page and say whether the button can work in a browser. "
                            + "Do not inspect protected files."));
            messages.add(ChatMessage.assistant("""
                    [Truth check: no file was changed in this turn. The model attempted to call mutating tools, but this turn was classified as read-only, so those calls were blocked.]

                    No file changes were applied. Ask explicitly to edit, update, or create files if you want Talos to modify the workspace.

                    Read-only answer from inspected evidence:
                    I inspected the primary web files:

                    - HTML: `index.html`
                    - CSS: `styles.css`
                    - JavaScript: `script.js`

                    Static web diagnostics found:
                    - HTML does not link JavaScript file: `script.js`
                    - JavaScript references missing class selectors: `.cta-button`
                    - script.js: button click handler references `#result` but does not assign visible result text with `textContent` or `innerText`.

                    No files were changed.
                    """));
            messages.add(ChatMessage.user(
                    "Based only on verified file evidence from the previous answer, list the blockers "
                            + "that prevent the button from working. Do not inspect protected files."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("Based on the previous runtime-owned static web diagnostics"),
                    out.text());
            assertTrue(out.text().contains("HTML does not link JavaScript file: `script.js`"), out.text());
            assertTrue(out.text().contains("JavaScript references missing class selectors: `.cta-button`"),
                    out.text());
            assertTrue(out.text().contains("does not assign visible result text"), out.text());
            assertFalse(out.text().contains("[Evidence incomplete"), out.text());
            assertFalse(out.text().contains("The button should work now"), out.text());
        }

        @Test
        void staticWebDiagnosticFollowUpDoesNotTrustArbitraryPreviousProse(@TempDir Path workspace)
                throws Exception {
            var ctx = scriptedContext(
                    "The previous answer says the button works.",
                    "No more evidence is needed.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Can this button work?"));
            messages.add(ChatMessage.assistant(
                    "I looked at the page and the button should work. No blockers."));
            messages.add(ChatMessage.user(
                    "Based only on verified file evidence from the previous answer, list the blockers "
                            + "that prevent the button from working. Do not inspect protected files."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertFalse(out.text().contains("Based on the previous runtime-owned static web diagnostics"),
                    out.text());
            assertFalse(out.text().contains("button should work. No blockers"), out.text());
        }

        @Test
        void changeSummaryFollowUpUsesPreviousPartialVerificationInsteadOfNewUnsupportedClaim() {
            var ctx = scriptedContext("I added the Listen Now button and wired script.js.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Fix the broken CTA on this page."));
            messages.add(ChatMessage.assistant("""
                    Partial verification: static checks failed after the mutation.
                    The turn remains partial; the requested task is not verified complete.

                    Succeeded:
                    - talos.edit_file -> index.html

                    Remaining static verification problems:
                    - index.html: HTML references missing script.js.
                    - index.html: `.cta-button` is still not present in the HTML.
                    """));
            messages.add(ChatMessage.user("Can you summarize what changed?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().contains("partial"), out.text());
            assertTrue(out.text().contains("index.html"), out.text());
            assertTrue(out.text().contains("script.js"), out.text());
            assertTrue(out.text().contains(".cta-button"), out.text());
            assertFalse(out.text().contains("I added the Listen Now button"), out.text());
            assertFalse(out.text().contains("wired script.js"), out.text());
        }

        @Test
        void statusFollowUpUsesPreviousPartialVerificationInsteadOfNewCompletionClaim() {
            var ctx = scriptedContext("The workspace now appears to have a functional 3-file BMI calculator.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "No no I want a functioning 3-file BMI calculator. Update index.html and styles.css "
                            + "and create scripts.js. Make it modern and responsive."));
            messages.add(ChatMessage.assistant("""
                    [Partial verification: static checks failed - HTML does not link JavaScript file: `scripts.js`]

                    The turn remains partial. Some changes were applied, but unresolved static problems remain.

                    Remaining static verification problems:
                    - styles.css: expected target was not successfully mutated.
                    - HTML does not link JavaScript file: `scripts.js`
                    - HTML defines duplicate IDs: `#result`
                    - Calculator/form task is missing a submit/calculate button.
                    """));
            messages.add(ChatMessage.user("did you make the changes?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("Partially."), out.text());
            assertTrue(out.text().contains("partial"), out.text());
            assertTrue(out.text().contains("not complete"), out.text());
            assertTrue(out.text().contains("HTML does not link JavaScript file"), out.text());
            assertTrue(out.text().contains("submit/calculate button"), out.text());
            assertFalse(out.text().contains("functional 3-file BMI calculator"), out.text());
        }

        @Test
        void artifactScopedPdfDocxStatusQuestionDoesNotUseLatestUnrelatedPartialOutcome() {
            var ctx = scriptedContext("Partially. The latest web task remains partial.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("can you create a docx file about a synthwave band page?"));
            messages.add(ChatMessage.assistant("""
                    [Document capability note: Talos cannot create valid Microsoft Word .docx files with the current local text-file tool surface.]

                    No file was changed.
                    """));
            messages.add(ChatMessage.user("create a pdf version instead please"));
            messages.add(ChatMessage.assistant("""
                    [Document capability note: Talos cannot create valid PDF files with the current local text-file tool surface.]

                    No file was changed.
                    """));
            messages.add(ChatMessage.user("make a static web page from rough-brief.txt"));
            messages.add(ChatMessage.assistant("""
                    [Partial verification: static checks failed - rough-brief.txt: expected target was not successfully mutated.]

                    Remaining static verification problems:
                    - rough-brief.txt: expected target was not successfully mutated.
                    - styles.css: expected target was not successfully mutated.
                    """));
            messages.add(ChatMessage.user("did you create any valid pdf or docx in this audit? be honest."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("No."), out.text());
            assertTrue(out.text().contains("valid PDF or DOCX"), out.text());
            assertTrue(out.text().contains("runtime evidence"), out.text());
            assertTrue(out.text().contains("unsupported-document"), out.text());
            assertFalse(out.text().contains("rough-brief.txt"), out.text());
            assertFalse(out.text().contains("styles.css"), out.text());
            assertFalse(out.text().contains("latest web task"), out.text());
        }

        @Test
        void unsupportedNaturalCommandRequestReturnsDeterministicNoRunAnswer() {
            var ctx = scriptedContext("I inspected the workspace and no command is available.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "run the safe command check for this folder. if it can't run, say exactly that."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("I can't run that command check"), out.text());
            assertTrue(out.text().contains("approved command profile"), out.text());
            assertFalse(out.text().contains("I inspected the workspace"), out.text());
        }

        @Test
        void checkpointRestoreRequestReturnsDeterministicSlashCommandHandoff() {
            var ctx = scriptedContext("I cannot revert the changes because no backup exists.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("ok revert your changes"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("Checkpoint restore is available"), out.text());
            assertTrue(out.text().contains("/checkpoint list"), out.text());
            assertTrue(out.text().contains("/checkpoint restore <id>"), out.text());
            assertTrue(out.text().contains("approval-gated"), out.text());
            assertFalse(out.text().contains("no backup exists"), out.text());
        }

        @Test
        void changedFilesAuditQuestionWithoutRuntimeLedgerDoesNotUsePreviousAssistantProse() {
            var ctx = scriptedContext("The audit changed .env and README.md.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "No no I want a functioning 3-file BMI calculator. Update index.html and styles.css "
                            + "and create scripts.js. Make it modern and responsive."));
            messages.add(ChatMessage.assistant("""
                    [Partial verification: static checks failed - HTML does not link JavaScript file: `scripts.js`]

                    The turn remains partial. Some changes were applied, but unresolved static problems remain.

                    Succeeded:
                    - talos.write_file -> index.html
                    - talos.write_file -> scripts.js

                    Remaining static verification problems:
                    - styles.css: expected target was not successfully mutated.
                    - HTML does not link JavaScript file: `scripts.js`
                    """));
            messages.add(ChatMessage.user("What files changed during this audit? Do not read protected files."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("No files were changed by Talos"), out.text());
            assertTrue(out.text().contains("runtime mutation history"), out.text());
            assertFalse(out.text().contains("index.html"), out.text());
            assertFalse(out.text().contains("scripts.js"), out.text());
            assertFalse(out.text().contains("styles.css"), out.text());
            assertFalse(out.text().contains(".env"), out.text());
            assertFalse(out.text().contains("The audit changed .env and README.md."), out.text());
        }

        @Test
        void changedFilesAuditQuestionWithNoRuntimeChangesReturnsDeterministicNoChangeAnswer() {
            SessionMemory memory = new SessionMemory();
            var ctx = Context.builder(new Config())
                    .memory(memory)
                    .llm(LlmClient.scripted("The audit changed README.md."))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("What files changed during this focused audit? Do not read protected files."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("No files were changed by Talos"), out.text());
            assertTrue(out.text().contains("runtime mutation history"), out.text());
            assertFalse(out.text().contains("README.md"), out.text());
            assertFalse(out.text().contains(".env"), out.text());
            assertFalse(out.text().contains("The audit changed README.md."), out.text());
        }

        @Test
        void changedFilesModifyQuestionDoesNotInferFromWorkspaceMarkers(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("README.md"),
                    "audit marker: README.md was changed during this audit");
            Files.writeString(workspace.resolve(".env"), "must-not-leak=secret");
            SessionMemory memory = new SessionMemory();
            var ctx = Context.builder(new Config())
                    .memory(memory)
                    .llm(LlmClient.scripted("README.md and .env changed."))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Which files did you modify in this session?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("No files were changed by Talos"), out.text());
            assertFalse(out.text().contains("README.md"), out.text());
            assertFalse(out.text().contains(".env"), out.text());
            assertFalse(out.text().contains("README.md and .env changed."), out.text());
        }

        @Test
        void changedFilesAuditQuestionPrefersRuntimeLedgerOverFailedVerifierProse() {
            SessionMemory memory = new SessionMemory();
            memory.setChangeSummaryContext(new ChangeSummaryContext(
                    ChangeSummaryContext.SCHEMA_VERSION,
                    List.of(
                            new ChangeSummaryContext.FileChange("index.html", "talos.write_file", 18, "trc-bmi"),
                            new ChangeSummaryContext.FileChange("styles.css", "talos.write_file", 18, "trc-bmi"),
                            new ChangeSummaryContext.FileChange("script.js", "talos.write_file", 18, "trc-bmi")),
                    List.of("scripts.js"),
                    "FAILED",
                    "TASK_INCOMPLETE",
                    List.of(
                            "scripts.js: expected target was not successfully mutated.",
                            "Calculator/form task is missing a result output element.")));
            var ctx = Context.builder(new Config())
                    .memory(memory)
                    .llm(LlmClient.scripted("The audit changed .env and README.md."))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create a complete static BMI calculator in this folder with index.html, styles.css, "
                            + "and scripts.js. It should calculate BMI from height and weight."));
            messages.add(ChatMessage.assistant("""
                    [Task incomplete: Static verification failed - scripts.js: expected target was not successfully mutated.; Calculator/form task is missing a result output element.]

                    The requested task is not verified complete. Applied changes below are workspace changes only; unresolved static problems remain.

                    Unresolved static verification problems:
                    - scripts.js: expected target was not successfully mutated.
                    - Calculator/form task is missing a result output element.
                    """));
            messages.add(ChatMessage.user("What files changed during this audit? Do not read protected files."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("Recorded file changes"), out.text());
            assertTrue(out.text().contains("index.html"), out.text());
            assertTrue(out.text().contains("styles.css"), out.text());
            assertTrue(out.text().contains("script.js"), out.text());
            assertTrue(out.text().contains("scripts.js"), out.text());
            assertTrue(out.text().contains("not verified complete"), out.text());
            assertFalse(out.text().startsWith("No. The previous verified outcome"), out.text());
            assertFalse(out.text().contains(".env"), out.text());
            assertFalse(out.text().contains("README.md"), out.text());
            assertFalse(out.text().contains("The audit changed .env and README.md."), out.text());
        }

        @Test
        void changedFilesAuditQuestionPreservesUnresolvedExactFailureDespiteLaterPassedStatus() {
            SessionMemory memory = new SessionMemory();
            memory.setChangeSummaryContext(new ChangeSummaryContext(
                    ChangeSummaryContext.SCHEMA_VERSION,
                    List.of(
                            new ChangeSummaryContext.FileChange("README.md", "talos.write_file", 21, "trc-readme"),
                            new ChangeSummaryContext.FileChange("index.html", "talos.write_file", 22, "trc-index")),
                    List.of(),
                    "PASSED",
                    "COMPLETED_VERIFIED",
                    List.of(),
                    List.of(new ChangeSummaryContext.VerificationFailure(
                            List.of("README.md"),
                            21,
                            "FAILED",
                            "TASK_INCOMPLETE",
                            "trc-readme",
                            List.of("README.md: exact content mismatch; expected 27 bytes/2 lines, observed 28 bytes/3 lines.")))));
            var ctx = Context.builder(new Config())
                    .memory(memory)
                    .llm(LlmClient.scripted("Everything is verified now."))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("What files changed during this audit?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("Recorded file changes"), out.text());
            assertTrue(out.text().contains("README.md"), out.text());
            assertTrue(out.text().contains("index.html"), out.text());
            assertTrue(out.text().contains("Unresolved verification failures"), out.text());
            assertTrue(out.text().contains("exact content mismatch"), out.text());
            assertTrue(out.text().contains("not verified complete"), out.text());
            assertFalse(out.text().contains("Verification status: verified complete"), out.text());
            assertFalse(out.text().contains("Everything is verified now"), out.text());
        }

        @Test
        void changedFilesAuditQuestionShowsPerFileVerificationStateForMixedHistory() {
            SessionMemory memory = new SessionMemory();
            memory.setChangeSummaryContext(new ChangeSummaryContext(
                    ChangeSummaryContext.SCHEMA_VERSION,
                    List.of(
                            new ChangeSummaryContext.FileChange(
                                    "index.html",
                                    "talos.write_file",
                                    30,
                                    "trc-index",
                                    "SUCCEEDED",
                                    "PASSED",
                                    "COMPLETED_VERIFIED"),
                            new ChangeSummaryContext.FileChange(
                                    "scripts.js",
                                    "talos.write_file",
                                    31,
                                    "trc-scripts",
                                    "SUCCEEDED",
                                    "NOT_RUN",
                                    "COMPLETED_UNVERIFIED")),
                    List.of(),
                    "PASSED",
                    "COMPLETED_VERIFIED",
                    List.of()));
            var ctx = Context.builder(new Config())
                    .memory(memory)
                    .llm(LlmClient.scripted("Everything is verified."))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("What files changed during this audit?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("Recorded file changes"), out.text());
            assertTrue(out.text().contains("index.html"), out.text());
            assertTrue(out.text().contains("turn 30"), out.text());
            assertTrue(out.text().contains("PASSED"), out.text());
            assertTrue(out.text().contains("COMPLETED_VERIFIED"), out.text());
            assertTrue(out.text().contains("scripts.js"), out.text());
            assertTrue(out.text().contains("turn 31"), out.text());
            assertTrue(out.text().contains("NOT_RUN"), out.text());
            assertTrue(out.text().contains("COMPLETED_UNVERIFIED"), out.text());
            assertTrue(out.text().contains("not verified complete"), out.text());
            assertFalse(out.text().contains("Verification status: verified complete"), out.text());
            assertFalse(out.text().contains("Everything is verified"), out.text());
        }

        @Test
        void changedFilesUncertaintyQuestionIncludesExplicitRuntimeUncertaintyClause() {
            SessionMemory memory = new SessionMemory();
            memory.setChangeSummaryContext(new ChangeSummaryContext(
                    ChangeSummaryContext.SCHEMA_VERSION,
                    List.of(new ChangeSummaryContext.FileChange(
                            "index.html",
                            "talos.write_file",
                            30,
                            "trc-index",
                            "SUCCEEDED",
                            "PASSED",
                            "COMPLETED_VERIFIED")),
                    List.of(),
                    "PASSED",
                    "COMPLETED_VERIFIED",
                    List.of()));
            var ctx = Context.builder(new Config())
                    .memory(memory)
                    .llm(LlmClient.scripted("No uncertainty."))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("State any uncertainty you have about files changed during this audit. "
                    + "Do not claim unverified facts and do not read protected files."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("Recorded file changes"), out.text());
            assertTrue(out.text().contains("index.html"), out.text());
            assertTrue(out.text().contains("Uncertainty:"), out.text());
            assertTrue(out.text().contains("runtime mutation history"), out.text());
            assertTrue(out.text().contains("external edits"), out.text());
            assertTrue(out.text().contains("protected file contents"), out.text());
            assertFalse(out.text().contains("No uncertainty."), out.text());
        }

        @Test
        void sessionUncertaintyQuestionAnswersFromRuntimeEvidenceNotIdentityProse() {
            SessionMemory memory = new SessionMemory();
            memory.setChangeSummaryContext(new ChangeSummaryContext(
                    ChangeSummaryContext.SCHEMA_VERSION,
                    List.of(
                            new ChangeSummaryContext.FileChange(
                                    "index.html",
                                    "talos.write_file",
                                    30,
                                    "trc-index",
                                    "SUCCEEDED",
                                    "PASSED",
                                    "COMPLETED_VERIFIED"),
                            new ChangeSummaryContext.FileChange(
                                    "script.js",
                                    "talos.write_file",
                                    30,
                                    "trc-script",
                                    "SUCCEEDED",
                                    "FAILED",
                                    "TASK_INCOMPLETE")),
                    List.of("scripts.js"),
                    "FAILED",
                    "TASK_INCOMPLETE",
                    List.of("scripts.js: expected target was not successfully mutated.")));
            var ctx = Context.builder(new Config())
                    .memory(memory)
                    .llm(LlmClient.scripted("I am Talos, a local-first workspace assistant."))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("what are you unsure about from this session? short and evidence-based."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("Uncertainty:"), out.text());
            assertTrue(out.text().contains("not verified complete"), out.text());
            assertTrue(out.text().contains("scripts.js"), out.text());
            assertTrue(out.text().contains("expected target was not successfully mutated"), out.text());
            assertTrue(out.text().contains("runtime mutation history"), out.text());
            assertFalse(out.text().contains("I am Talos"), out.text());
        }

        @Test
        void verificationStatusQuestionUsesLatestRuntimeVerifierFailureNotModelOverclaim() {
            SessionMemory memory = new SessionMemory();
            memory.setChangeSummaryContext(new ChangeSummaryContext(
                    ChangeSummaryContext.SCHEMA_VERSION,
                    List.of(
                            new ChangeSummaryContext.FileChange(
                                    "index.html",
                                    "talos.write_file",
                                    41,
                                    "trc-retrocats",
                                    "SUCCEEDED",
                                    "FAILED",
                                    "TASK_INCOMPLETE"),
                            new ChangeSummaryContext.FileChange(
                                    "style.css",
                                    "talos.write_file",
                                    41,
                                    "trc-retrocats",
                                    "SUCCEEDED",
                                    "FAILED",
                                    "TASK_INCOMPLETE")),
                    List.of("script.js"),
                    "FAILED",
                    "TASK_INCOMPLETE",
                    List.of(
                            "style.css: Tailwind directives (@apply) are unprocessed without a Tailwind build or runtime.",
                            "script.js: expected target was not successfully mutated.")));
            var ctx = Context.builder(new Config())
                    .memory(memory)
                    .llm(LlmClient.scripted("The static verification indicates that everything is present and working."))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Is it verified now? What, if anything, is still unverified?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("No. Latest Talos-recorded verification is not verified complete."),
                    out.text());
            assertTrue(out.text().contains("verifier=FAILED"), out.text());
            assertTrue(out.text().contains("completion=TASK_INCOMPLETE"), out.text());
            assertTrue(out.text().contains("script.js"), out.text());
            assertTrue(out.text().contains("@apply"), out.text());
            assertTrue(out.text().contains("runtime mutation history"), out.text());
            assertFalse(out.text().contains("indicates that everything is present"), out.text());
        }

        @Test
        void verificationStatusQuestionWithoutLoadedVerifierStateDoesNotInferSuccess() {
            var ctx = Context.builder(new Config())
                    .memory(new SessionMemory())
                    .llm(LlmClient.scripted("Yes, it is verified now."))
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Is it verified now?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("No loaded prior verifier state is available"),
                    out.text());
            assertTrue(out.text().contains("did not run post-apply verification"), out.text());
            assertFalse(out.text().contains("Yes, it is verified"), out.text());
        }

        @Test
        void staticWebRepairActionWithUnverifiedLanguageDoesNotShortCircuitToStatusAnswer(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), """
                    <!doctype html>
                    <html>
                    <head>
                      <link rel="stylesheet" href="style.css">
                    </head>
                    <body>
                      <main>Retrocats</main>
                      <script src="script.js"></script>
                    </body>
                    </html>
                    """);
            Files.writeString(workspace.resolve("style.css"), "body { background: #050505; }\n");
            Files.writeString(workspace.resolve("script.js"), "console.log('Retrocats');\n");

            var registry = new ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .memory(new SessionMemory())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "Inspected index.html for the repair pass.")))
                    .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Make this Retrocats website even more polished and complete. "
                            + "Use Tailwind correctly, preserve facts, and repair anything unverified."));

            TurnAuditCapture.begin();
            try {
                AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                        messages, workspace, ctx, new AssistantTurnExecutor.Options());
                var audit = TurnAuditCapture.end();

                assertTrue(audit.policyTrace().mutationAllowed(), audit.policyTrace().toString());
                assertTrue(audit.policyTrace().verificationRequired(), audit.policyTrace().toString());
                assertTrue(audit.policyTrace().expectedTargets().contains("index.html"),
                        audit.policyTrace().toString());
                assertFalse(out.text().startsWith("No loaded prior verifier state is available"), out.text());
                assertTrue(out.text().contains("talos.read_file"), out.text());
            } finally {
                if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
            }
        }

        @Test
        void repeatedStatusFollowUpDoesNotDuplicatePreviousVerifiedPreamble() {
            var ctx = scriptedContext("Yes, it is done now.");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "No no I want a functioning 3-file BMI calculator. Update index.html and styles.css "
                            + "and create scripts.js. Make it modern and responsive."));
            messages.add(ChatMessage.assistant("""
                    [Partial verification: static checks failed - HTML does not link JavaScript file: `scripts.js`]

                    The turn remains partial. Some changes were applied, but unresolved static problems remain.

                    Remaining static verification problems:
                    - styles.css: expected target was not successfully mutated.
                    - HTML does not link JavaScript file: `scripts.js`
                    - Calculator/form task is missing a submit/calculate button.
                    """));
            messages.add(ChatMessage.user("did you make the changes?"));
            messages.add(ChatMessage.assistant("""
                    The previous verified result says the last change is not complete.

                    The previous verified result says the last change is not complete.

                    [Partial verification: static checks failed - HTML does not link JavaScript file: `scripts.js`]

                    The turn remains partial. Some changes were applied, but unresolved static problems remain.

                    Remaining static verification problems:
                    - styles.css: expected target was not successfully mutated.
                    - HTML does not link JavaScript file: `scripts.js`
                    - Calculator/form task is missing a submit/calculate button.
                    """));
            messages.add(ChatMessage.user("is it working now?"));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.text().startsWith("Partially."), out.text());
            assertEquals(0, occurrences(out.text(), "The previous verified result says"), out.text());
            assertEquals(1, occurrences(out.text(), "HTML does not link JavaScript file"), out.text());
            assertEquals(1, occurrences(out.text(), "submit/calculate button"), out.text());
            assertFalse(out.text().contains("Yes, it is done now."), out.text());
        }

        private int occurrences(String text, String needle) {
            if (text == null || needle == null || needle.isEmpty()) return 0;
            int count = 0;
            int index = 0;
            while ((index = text.indexOf(needle, index)) >= 0) {
                count++;
                index += needle.length();
            }
            return count;
        }
    }
}





