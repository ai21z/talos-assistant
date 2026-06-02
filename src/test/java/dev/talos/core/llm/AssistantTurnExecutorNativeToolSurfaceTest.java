package dev.talos.core.llm;

import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
import dev.talos.tools.impl.DeletePathTool;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RenamePathTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantTurnExecutorNativeToolSurfaceTest {

    @Test
    void readOnlyTurnSendsOnlyReadOnlyNativeToolSpecs() {
        RecordingResolver resolver = new RecordingResolver();
        Context ctx = context(resolver);

        AssistantTurnExecutor.execute(
                messages("What is in this workspace?"),
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        List<String> names = toolNames(resolver.lastRequest);
        assertTrue(names.contains("talos.read_file"));
        assertFalse(names.contains("talos.write_file"));
        assertFalse(names.contains("talos.edit_file"));
    }

    @Test
    void directAnswerOnlyTurnsSendNoNativeToolSpecs() {
        for (String prompt : List.of(
                "hello",
                "Hello friend",
                "Hello friend, how are you?",
                "how are you are you good?",
                "perfect just as I want it!")) {
            RecordingResolver resolver = new RecordingResolver();
            Context ctx = context(resolver);

            AssistantTurnExecutor.execute(
                    messages(prompt),
                    Path.of("."),
                    ctx,
                    new AssistantTurnExecutor.Options());

            assertNotNull(resolver.lastRequest, prompt);
            List<String> names = toolNames(resolver.lastRequest);
            assertTrue(names.isEmpty(), prompt);
        }
    }

    @Test
    void nearSlashCommandReturnsDeterministicGuidanceWithoutLlmRequest() {
        RecordingResolver resolver = new RecordingResolver();
        Context ctx = context(resolver);

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages("debug /trace"),
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertEquals("Use `/last trace` to show the most recent trace.", output.text());
        assertNull(resolver.lastRequest);
    }

    @Test
    void mutationTurnSendsWriteAndEditNativeToolSpecs() {
        RecordingResolver resolver = new RecordingResolver();
        Context ctx = context(resolver);

        AssistantTurnExecutor.execute(
                messages("Create a README.md file."),
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        List<String> names = toolNames(resolver.lastRequest);
        assertTrue(names.contains("talos.read_file"));
        assertTrue(names.contains("talos.write_file"));
        assertTrue(names.contains("talos.edit_file"));
    }

    @Test
    void broadStaticWebRewriteSendsWriteFileButNotEditFile() {
        RecordingResolver resolver = new RecordingResolver();
        Context ctx = context(resolver);

        AssistantTurnExecutor.execute(
                messages("Update index.html and scripts.js so Neon Meridian is a polished synthwave band "
                        + "landing page. Adjust styles.css as needed. Make #teaser-button update "
                        + "#teaser-status with a visible teaser message."),
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        List<String> names = toolNames(resolver.lastRequest);
        assertTrue(names.contains("talos.read_file"), names.toString());
        assertTrue(names.contains("talos.write_file"), names.toString());
        assertFalse(names.contains("talos.edit_file"), names.toString());
        assertFalse(names.contains("talos.mkdir"), names.toString());
    }

    @Test
    void explicitMoveTurnSendsOnlyMovePathNativeToolSpec() {
        RecordingResolver resolver = new RecordingResolver();
        Context ctx = context(resolver);

        AssistantTurnExecutor.execute(
                messages("Move workspace-notes/readme-renamed.md to archive/readme-renamed.md."),
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertEquals(List.of("talos.move_path"), toolNames(resolver.lastRequest));
    }

    @Test
    void compoundWorkspaceTurnSendsCompleteWorkspaceOperationSurface() {
        RecordingResolver resolver = new RecordingResolver();
        Context ctx = context(resolver);

        AssistantTurnExecutor.execute(
                messages("Create folders assets and drafts, copy docs/summary.md to drafts/summary-copy.md, "
                        + "rename it to summary-renamed.md, then move it to assets/summary-renamed.md."),
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertEquals(
                List.of(
                        "talos.apply_workspace_batch",
                        "talos.copy_path",
                        "talos.mkdir",
                        "talos.move_path",
                        "talos.rename_path"),
                toolNames(resolver.lastRequest));
    }

    private static Context context(RecordingResolver resolver) {
        ToolRegistry registry = new ToolRegistry();
        FileUndoStack undoStack = new FileUndoStack();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        registry.register(new BatchWorkspaceApplyTool());
        registry.register(new MakeDirectoryTool());
        registry.register(new MovePathTool());
        registry.register(new CopyPathTool());
        registry.register(new RenamePathTool());
        registry.register(new DeletePathTool());

        LlmClient llm = new LlmClient(engineConfig(), resolver);
        llm.setToolSpecs(registry.descriptors().stream()
                .map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema()))
                .toList());

        return Context.builder(engineConfig())
                .llm(llm)
                .toolRegistry(registry)
                .build();
    }

    private static List<ChatMessage> messages(String user) {
        return new ArrayList<>(List.of(ChatMessage.system("system"), ChatMessage.user(user)));
    }

    private static List<String> toolNames(ChatRequest request) {
        return request.tools.stream().map(ToolSpec::name).sorted().toList();
    }

    private static Config engineConfig() {
        Config cfg = new Config();
        LinkedHashMap<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "ollama");
        cfg.data.put("llm", llm);

        LinkedHashMap<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("model", "qwen2.5-coder:14b");
        cfg.data.put("ollama", ollama);
        return cfg;
    }

    private static final class RecordingResolver implements LlmEngineResolver {
        private volatile ChatRequest lastRequest;

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            this.lastRequest = request;
            return Stream.of(TokenChunk.of("plain reply"), TokenChunk.eos());
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
