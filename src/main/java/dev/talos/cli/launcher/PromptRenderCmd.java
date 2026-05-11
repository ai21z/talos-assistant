package dev.talos.cli.launcher;

import dev.talos.cli.prompt.PromptInspector;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.SessionState;
import dev.talos.cli.ui.TerminalCapabilities;
import dev.talos.core.Config;
import dev.talos.core.util.Sanitize;
import dev.talos.core.rag.RagService;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.BatchWorkspaceApplyTool;
import dev.talos.tools.impl.DeletePathTool;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.RenamePathTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RetrieveTool;
import dev.talos.tools.impl.RunCommandTool;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

@CommandLine.Command(
        name = "prompt-render",
        description = "Render the prompt Talos would send without calling the model"
)
public class PromptRenderCmd implements Runnable {
    @CommandLine.Option(names = {"--root", "--workspace"}, description = "Workspace root (default: .)")
    Path root;

    @CommandLine.Option(names = "--mode", description = "Prompt mode: auto, unified, ask, or rag")
    String mode = "auto";

    @CommandLine.Option(names = "--input", description = "Optional user input to include as the final user message")
    String input = "";

    @Override
    public void run() {
        try {
            Path workspace = (root == null ? Path.of(".") : root).toAbsolutePath().normalize();
            try { workspace = workspace.toRealPath(); } catch (Exception ignored) {}
            if (!Files.isDirectory(workspace)) {
                System.err.println("Not a directory: " + workspace);
                return;
            }

            Config cfg = new Config();
            RagService rag = new RagService(cfg);
            ToolRegistry registry = toolRegistry(rag);
            Context ctx = Context.builder(cfg)
                    .withDefaults(workspace, session())
                    .rag(rag)
                    .toolRegistry(registry)
                    .build();

            String rendered = PromptInspector.format(
                    PromptInspector.renderNext(mode, input, workspace, ctx));
            System.out.print(Sanitize.sanitizeForTerminalOutput(
                    rendered,
                    TerminalCapabilities.detectDefault().unicodeSafe()));
        } catch (Exception e) {
            System.err.println("prompt-render failed: " + e.getMessage());
            if (Boolean.getBoolean("talos.debug")) e.printStackTrace(System.err);
        }
    }

    private static ToolRegistry toolRegistry(RagService rag) {
        FileUndoStack undoStack = new FileUndoStack();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
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

    private static SessionState session() {
        return new SessionState() {
            private int k = 8;
            private boolean debug;

            @Override public int getK() { return k; }
            @Override public void setK(int k) { this.k = Math.max(1, k); }
            @Override public boolean isDebug() { return debug; }
            @Override public void setDebug(boolean on) { debug = on; }
        };
    }
}
