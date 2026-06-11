package dev.talos.runtime.toolcall;

import dev.talos.runtime.command.RunCommandTool;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
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

/**
 * The canonical descriptor catalog: the same 13 tools TalosBootstrap
 * registers, available to planners that need a registry-shaped view of the
 * full tool surface without a live session (T761).
 *
 * <p>Construction is descriptor-only: none of these instances ever execute.
 * RetrieveTool's service may be null (descriptor() never touches it) and
 * the file tools carry no undo stack. A parity test pins this catalog
 * against a bootstrap-equivalent registry so it cannot rot when tools are
 * added or their metadata changes.
 */
public final class CanonicalToolDescriptors {

    private static final ToolRegistry REGISTRY = build();

    private CanonicalToolDescriptors() {}

    public static ToolRegistry registry() {
        return REGISTRY;
    }

    private static ToolRegistry build() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
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
        registry.register(new RetrieveTool(null));
        return registry;
    }
}
