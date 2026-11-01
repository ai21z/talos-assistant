package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * A replacement request ends verified only when the change it names is on disk, and a rename, whose completeness
 * text cannot decide, ends as a checks pass (MICA invariant I3, decision D5, P4). The prompts are the qualification
 * bank F05 and F08 prompts. UnifiedAssistantMode, TurnProcessor, approval,
 * verification and trace capture are real. The provider is scripted.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MicaReplacementBindingBoundaryTest {
    private static final String F05 =
            "In src/App.java, change only the returned greeting from hello to hello atlas. Preserve formatting.";
    private static final String APP = "package sample;\n\nfinal class App {\n    static String greeting() {\n"
            + "        return \"hello\";\n    }\n}\n";
    private static final String READ_APP = "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"src/App.java\"}}";
    private static final String F08 =
            "Rename reset to resetAll in script.js only. scripts.js is a different file and must remain unchanged.";
    private static final String READ_SCRIPT = "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"script.js\"}}";
    @TempDir Path temp;
    private Path workspace;
    private String priorHome;

    @BeforeEach
    void isolate() throws Exception {
        workspace = Files.createDirectory(temp.resolve("workspace"));
        priorHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectory(temp.resolve("home")).toString());
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.java"), APP);
        Files.writeString(workspace.resolve("scripts.js"), "export const helpers = ['format', 'validate'];\n");
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
    void theRequestedGreetingChangeIsVerified() throws Exception {
        var run = run(F05, READ_APP, edit("src/App.java", "return \\\"hello\\\";", "return \\\"hello atlas\\\";"),
                "Updated src/App.java.");

        assertTrue(Files.readString(workspace.resolve("src/App.java")).contains("return \"hello atlas\";"));
        var trace = run.turn().audit().localTrace();
        assertEquals("PASSED", trace.outcome().verificationStatus(), run.answer());
        assertEquals("COMPLETED_VERIFIED", trace.outcome().classification(), run.answer());
    }

    @Test
    void aDifferentGreetingIsNotVerified() throws Exception {
        var run = run(F05, READ_APP, edit("src/App.java", "return \\\"hello\\\";", "return \\\"hello there\\\";"),
                "Updated src/App.java.", "Updated src/App.java.", "Updated src/App.java.");

        assertTrue(Files.readString(workspace.resolve("src/App.java")).contains("return \"hello there\";"));
        var trace = run.turn().audit().localTrace();
        assertNotEquals("COMPLETED_VERIFIED", trace.outcome().classification(), run.answer());
        assertEquals("FAILED", trace.outcome().verificationStatus(), run.answer());
    }

    @Test
    void aRenameIsAChecksPassBecauseTextCannotProveItComplete() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "export function reset() {\n  return 'ready';\n}\n");
        var run = run(F08, READ_SCRIPT, edit("script.js", "function reset()", "function resetAll()"),
                "Renamed reset to resetAll in script.js.");

        assertEquals("export function resetAll() {\n  return 'ready';\n}\n",
                Files.readString(workspace.resolve("script.js")));
        var trace = run.turn().audit().localTrace();
        assertEquals("PASSED", trace.outcome().verificationStatus(), run.answer());
        assertEquals("COMPLETED_UNVERIFIED", trace.outcome().classification(), run.answer());
        assertTrue(run.answer().contains("[Checks passed - "), run.answer());
    }

    @Test
    void aRenameThatLeavesAReferenceIsNotVerified() throws Exception {
        Files.writeString(workspace.resolve("script.js"),
                "export function reset() {\n  return 'ready';\n}\nexport const start = reset;\n");
        var run = run(F08, READ_SCRIPT, edit("script.js", "function reset()", "function resetAll()"),
                "Renamed reset to resetAll in script.js.", "Renamed reset to resetAll in script.js.",
                "Renamed reset to resetAll in script.js.");

        assertTrue(Files.readString(workspace.resolve("script.js")).contains("export const start = reset;"));
        var trace = run.turn().audit().localTrace();
        assertNotEquals("COMPLETED_VERIFIED", trace.outcome().classification(), run.answer());
    }

    private static String edit(String path, String oldString, String newString) {
        return "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"" + path + "\",\"old_string\":\""
                + oldString + "\",\"new_string\":\"" + newString + "\"}}";
    }

    private record Run(TurnResult turn, String answer) {}

    private Run run(String request, String... responses) throws Exception {
        Config config = new Config(null);
        SessionMemory memory = new SessionMemory();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        registry.register(new ReadFileTool());
        var gate = new ApprovalGate() {
            @Override public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }
            @Override public ApprovalResponse approveFull(String description, String detail) {
                return ApprovalResponse.APPROVED;
            }
        };
        var mode = new UnifiedAssistantMode();
        var processor = new TurnProcessor((r, root, context) -> mode.handle(r, root, (Context) context),
                gate, registry, ApprovalPolicy.ALWAYS_ASK);
        processor.addListener(new MemoryUpdateListener(
                new ConversationManager(memory, TokenBudget.fromConfig(config)), null, memory));
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(
                Arrays.stream(responses).map(response -> new LlmClient.StreamResult(response, List.of())).toList(),
                16_384);
        try (var session = new Session(workspace, config, memory); var client = fixture.client()) {
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
}
