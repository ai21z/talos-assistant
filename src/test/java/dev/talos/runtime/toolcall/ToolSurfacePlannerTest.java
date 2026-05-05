package dev.talos.runtime.toolcall;

import dev.talos.core.capability.CapabilityKind;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import dev.talos.tools.impl.BatchWorkspaceApplyTool;
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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolSurfacePlannerTest {

    @Test
    void smallTalkExposesNoTools() {
        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest("hello who are you?"),
                ExecutionPhase.INSPECT,
                registry());

        assertEquals(List.of(), plan.nativeToolNames());
        assertEquals(List.of(), plan.nativeToolSpecs());
        assertEquals("small-talk", plan.reason());
    }

    @Test
    void readOnlySurfaceUsesMetadataAndOmitsMutationOperations() {
        ToolRegistry registry = registry();
        registry.register(new MetadataOnlyInspectTool());
        registry.register(new MetadataOnlyMutationTool());

        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest("What is this project?"),
                ExecutionPhase.INSPECT,
                registry);

        List<String> names = plan.nativeToolNames();
        assertTrue(names.contains("talos.read_file"));
        assertTrue(names.contains("talos.list_dir"));
        assertTrue(names.contains("talos.grep"));
        assertTrue(names.contains("talos.retrieve"));
        assertTrue(names.contains("talos.metadata_inspect"));
        assertFalse(names.contains("talos.write_file"));
        assertFalse(names.contains("talos.edit_file"));
        assertFalse(names.contains("talos.metadata_mutation"));
        assertEquals("read-only metadata surface", plan.reason());
    }

    @Test
    void mutationApplySurfaceIncludesReadOnlyAndMutationOperations() {
        ToolRegistry registry = registry();
        registry.register(new MetadataOnlyDestructiveTool());

        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest("Create a README.md file."),
                ExecutionPhase.APPLY,
                registry);

        List<String> names = plan.nativeToolNames();
        assertTrue(names.contains("talos.read_file"));
        assertTrue(names.contains("talos.list_dir"));
        assertTrue(names.contains("talos.grep"));
        assertTrue(names.contains("talos.retrieve"));
        assertTrue(names.contains("talos.write_file"));
        assertTrue(names.contains("talos.edit_file"));
        assertTrue(names.contains("talos.apply_workspace_batch"));
        assertTrue(names.contains("talos.mkdir"));
        assertTrue(names.contains("talos.move_path"));
        assertTrue(names.contains("talos.copy_path"));
        assertTrue(names.contains("talos.rename_path"));
        assertFalse(names.contains("talos.metadata_delete"));
        assertEquals("mutation apply surface", plan.reason());
    }

    @Test
    void directoryListingSurfaceUsesDirectoryTargetMetadata() {
        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest("What files are in this folder?"),
                ExecutionPhase.INSPECT,
                registry());

        assertEquals(List.of("talos.list_dir"), plan.nativeToolNames());
        assertEquals("directory listing", plan.reason());
    }

    @Test
    void namedReadTargetSurfaceUsesFileTargetMetadataForProtectedAndPublicReads() {
        for (String request : List.of(
                "Read config.json and tell me the name.",
                "Read .env and tell me what it says.")) {
            ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                    TaskContractResolver.fromUserRequest(request),
                    ExecutionPhase.INSPECT,
                    registry());

            assertEquals(List.of("talos.read_file"), plan.nativeToolNames(), request);
            assertEquals("expected target read", plan.reason(), request);
        }
    }

    @Test
    void verifyPhaseDowngradesMutationContractToReadOnlyMetadataSurface() {
        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest("Edit index.html."),
                ExecutionPhase.VERIFY,
                registry());

        List<String> names = plan.nativeToolNames();
        assertTrue(names.contains("talos.read_file"));
        assertTrue(names.contains("talos.grep"));
        assertFalse(names.contains("talos.write_file"));
        assertFalse(names.contains("talos.edit_file"));
        assertEquals("read-only metadata surface", plan.reason());
    }

    @Test
    void defaultNamesMatchCurrentPromptFallbackSurfaces() {
        assertEquals(
                List.of(),
                ToolSurfacePlanner.defaultVisibleToolNames(
                        TaskContractResolver.fromUserRequest("hello"),
                        ExecutionPhase.INSPECT));

        assertEquals(
                List.of("talos.list_dir"),
                ToolSurfacePlanner.defaultVisibleToolNames(
                        TaskContractResolver.fromUserRequest("what files are here?"),
                        ExecutionPhase.INSPECT));

        assertEquals(
                List.of("talos.grep", "talos.list_dir", "talos.read_file", "talos.retrieve"),
                ToolSurfacePlanner.defaultVisibleToolNames(
                        TaskContractResolver.fromUserRequest("what is this project?"),
                        ExecutionPhase.INSPECT));

        assertEquals(
                List.of("talos.apply_workspace_batch", "talos.copy_path", "talos.edit_file", "talos.grep", "talos.list_dir",
                        "talos.mkdir", "talos.move_path", "talos.read_file", "talos.rename_path",
                        "talos.retrieve", "talos.write_file"),
                ToolSurfacePlanner.defaultVisibleToolNames(
                        TaskContractResolver.fromUserRequest("create a README.md file"),
                        ExecutionPhase.APPLY));
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        FileUndoStack undoStack = new FileUndoStack();
        registry.register(new ReadFileTool());
        registry.register(new ListDirTool());
        registry.register(new GrepTool());
        registry.register(new RetrieveTool(null));
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        registry.register(new BatchWorkspaceApplyTool());
        registry.register(new MakeDirectoryTool());
        registry.register(new MovePathTool());
        registry.register(new CopyPathTool());
        registry.register(new RenamePathTool());
        return registry;
    }

    private static final class MetadataOnlyInspectTool implements TalosTool {
        @Override public String name() { return "talos.metadata_inspect"; }
        @Override public String description() { return "metadata inspect"; }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("ok"); }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    name(),
                    description(),
                    "{}",
                    ToolRiskLevel.WRITE,
                    ToolOperationMetadata.inspect(name(), Map.of(), "METADATA_INSPECTED"));
        }
    }

    private static final class MetadataOnlyMutationTool implements TalosTool {
        @Override public String name() { return "talos.metadata_mutation"; }
        @Override public String description() { return "metadata mutation"; }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("ok"); }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    name(),
                    description(),
                    "{}",
                    ToolRiskLevel.READ_ONLY,
                    ToolOperationMetadata.workspaceMutation(
                            name(),
                            CapabilityKind.EDIT,
                            ToolRiskLevel.WRITE,
                            Map.of("path", ToolOperationMetadata.PathRole.TARGET_FILE),
                            false,
                            true,
                            "METADATA_MUTATED",
                            "CONTENT_VERIFY"));
        }
    }

    private static final class MetadataOnlyDestructiveTool implements TalosTool {
        @Override public String name() { return "talos.metadata_delete"; }
        @Override public String description() { return "metadata delete"; }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("ok"); }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    name(),
                    description(),
                    "{}",
                    ToolRiskLevel.DESTRUCTIVE,
                    ToolOperationMetadata.workspaceMutation(
                            name(),
                            CapabilityKind.DELETE,
                            ToolRiskLevel.DESTRUCTIVE,
                            Map.of("path", ToolOperationMetadata.PathRole.TARGET_PATH),
                            false,
                            true,
                            "METADATA_DELETED",
                            "PATH_ABSENT"));
        }
    }
}
