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
        assertFalse(names.contains("talos.delete_path"));
        assertFalse(names.contains("talos.run_command"), names.toString());
        assertFalse(names.contains("talos.metadata_delete"));
        assertEquals("mutation apply surface", plan.reason());
    }

    @Test
    void explicitWorkspaceOperationRequestsExposeOnlyMatchingOperationTool() {
        assertWorkspaceOperationSurface(
                "Move workspace-notes/readme-renamed.md to archive/readme-renamed.md.",
                List.of("talos.move_path"),
                "workspace move operation surface");
        assertWorkspaceOperationSurface(
                "Copy docs/plan.md to docs/archive/plan.md.",
                List.of("talos.copy_path"),
                "workspace copy operation surface");
        assertWorkspaceOperationSurface(
                "Rename old.txt to new.txt.",
                List.of("talos.rename_path"),
                "workspace rename operation surface");
        assertWorkspaceOperationSurface(
                "Mkdir docs/reports.",
                List.of("talos.mkdir"),
                "workspace mkdir operation surface");
        assertWorkspaceOperationSurface(
                "Delete docs/old-plan.md please.",
                List.of("talos.delete_path"),
                "workspace delete operation surface");
    }

    @Test
    void compoundWorkspaceOperationRequestsExposeBatchAndRequiredOperationTools() {
        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest(
                        "Create folders assets and drafts, copy docs/summary.md to drafts/summary-copy.md, "
                                + "rename it to summary-renamed.md, then move it to assets/summary-renamed.md."),
                ExecutionPhase.APPLY,
                registry());

        assertEquals(
                List.of(
                        "talos.apply_workspace_batch",
                        "talos.copy_path",
                        "talos.mkdir",
                        "talos.move_path",
                        "talos.rename_path"),
                plan.nativeToolNames());
        assertEquals("compound workspace operation surface", plan.reason());
    }

    @Test
    void naturalBatchDirectoryAndCopyPromptExposesCompoundWorkspaceSurface() {
        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest(
                        "batch this: create batch-one and batch-two, then copy styles.css to batch-one/styles-copy.css."),
                ExecutionPhase.APPLY,
                registry());

        assertEquals(
                List.of("talos.apply_workspace_batch", "talos.copy_path", "talos.mkdir"),
                plan.nativeToolNames());
        assertEquals("compound workspace operation surface", plan.reason());
    }

    @Test
    void naturalDirectoryCreationRequestsExposeOnlyMkdirTool() {
        for (String request : List.of(
                "Create a new dir called workspace-notes.",
                "Create a new folder named audit-output.",
                "Can you create a folder called docs?",
                "make me a folder called ideas")) {
            assertWorkspaceOperationSurface(
                    request,
                    List.of("talos.mkdir"),
                    "workspace mkdir operation surface");
        }
    }

    @Test
    void mixedDirectoryAndExactFileCreateKeepsFileWriteSurface() {
        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest(
                        "Create a directory named workspace-notes and create workspace-notes/summary.txt "
                                + "containing exactly created by audit."),
                ExecutionPhase.APPLY,
                registry());

        List<String> names = plan.nativeToolNames();
        assertTrue(names.contains("talos.mkdir"), names.toString());
        assertTrue(names.contains("talos.write_file"), names.toString());
        assertFalse(
                names.equals(List.of("talos.mkdir")),
                "mixed directory+file creation must not be narrowed to mkdir-only");
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
    void verifyOnlyMixedFileAndDirectoryPathChecksExposeReadFileAndListDirOnly() {
        var contract = TaskContractResolver.fromUserRequest(
                "Verify the final workspace paths for archive/readme-renamed.md, "
                        + "copies/readme-final.md, and scratch/nested/reports. Do not edit files.");

        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(contract, ExecutionPhase.VERIFY, registry());

        List<String> names = plan.nativeToolNames();
        assertEquals("verify-only path check with directory targets", plan.reason());
        assertTrue(names.contains("talos.read_file"), names.toString());
        assertTrue(names.contains("talos.list_dir"), names.toString());
        assertFalse(names.contains("talos.write_file"), names.toString());
        assertFalse(names.contains("talos.edit_file"), names.toString());
        assertFalse(names.contains("talos.mkdir"), names.toString());
        assertFalse(names.contains("talos.move_path"), names.toString());
        assertFalse(names.contains("talos.copy_path"), names.toString());
        assertFalse(names.contains("talos.rename_path"), names.toString());
    }

    @Test
    void verifyOnlyFilePathChecksKeepExpectedTargetReadSurface() {
        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest(
                        "Verify README.md and docs/plan.md. Do not edit files."),
                ExecutionPhase.VERIFY,
                registry());

        assertEquals(List.of("talos.read_file"), plan.nativeToolNames());
        assertEquals("expected target read", plan.reason());
    }

    @Test
    void verifyOnlyDirectoryPathWithoutFileTargetsUsesNarrowReadOnlyPathSurface() {
        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest(
                        "Verify whether scratch/nested/reports exists as a directory. Do not edit files."),
                ExecutionPhase.VERIFY,
                registry());

        List<String> names = plan.nativeToolNames();
        assertEquals("verify-only path check with directory targets", plan.reason());
        assertEquals(List.of("talos.list_dir", "talos.read_file"), names);
        assertFalse(names.contains("talos.run_command"), names.toString());
        assertFalse(names.contains("talos.write_file"), names.toString());
        assertFalse(names.contains("talos.edit_file"), names.toString());
        assertFalse(names.contains("talos.mkdir"), names.toString());
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
    void verifyOrientedDevTaskExposesCommandSurface() {
        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest("Verify that the Gradle build passes."),
                ExecutionPhase.VERIFY,
                registry());

        List<String> names = plan.nativeToolNames();
        assertTrue(names.contains("talos.read_file"));
        assertTrue(names.contains("talos.grep"));
        assertTrue(names.contains("talos.run_command"));
        assertFalse(names.contains("talos.write_file"));
        assertFalse(names.contains("talos.edit_file"));
        assertEquals("verification command surface", plan.reason());
    }

    @Test
    void explicitCommandProbeExposesCommandSurfaceWithoutMutationTools() {
        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest(
                        "Probe timeout behavior. Run dev.talos.TimeoutTest with talos.run_command profile gradle_test, "
                                + "args_json [\"--tests\",\"dev.talos.TimeoutTest\"], and timeout_ms 1000. Do not edit files."),
                ExecutionPhase.VERIFY,
                registry());

        List<String> names = plan.nativeToolNames();
        assertTrue(names.contains("talos.run_command"));
        assertFalse(names.contains("talos.read_file"));
        assertFalse(names.contains("talos.list_dir"));
        assertFalse(names.contains("talos.grep"));
        assertFalse(names.contains("talos.write_file"));
        assertFalse(names.contains("talos.edit_file"));
        assertEquals("explicit command profile surface", plan.reason());
    }

    @Test
    void explicitApprovedCommandProfileRequestExposesOnlyRunCommand() {
        var contract = TaskContractResolver.fromUserRequest(
                "Run the approved Gradle test command profile for this workspace and report the exact command result. "
                        + "Do not invent a pass if the command cannot run.");

        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(contract, ExecutionPhase.VERIFY, registry());

        assertEquals("explicit-command-verification-request", contract.classificationReason());
        assertEquals(List.of("talos.run_command"), plan.nativeToolNames());
        assertEquals("explicit command profile surface", plan.reason());
    }

    @Test
    void unsupportedNaturalCommandRequestExposesNoTools() {
        var contract = TaskContractResolver.fromUserRequest(
                "run the safe command check for this folder. if it can't run, say exactly that.");

        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(contract, ExecutionPhase.VERIFY, registry());

        assertEquals("unsupported command request", plan.reason());
        assertEquals(List.of(), plan.nativeToolNames());
        assertFalse(plan.nativeToolNames().contains("talos.run_command"));
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
                        "talos.mkdir", "talos.move_path", "talos.read_file", "talos.rename_path", "talos.retrieve",
                        "talos.write_file"),
                ToolSurfacePlanner.defaultVisibleToolNames(
                        TaskContractResolver.fromUserRequest("create a README.md file"),
                        ExecutionPhase.APPLY));

        assertEquals(
                List.of("talos.move_path"),
                ToolSurfacePlanner.defaultVisibleToolNames(
                        TaskContractResolver.fromUserRequest(
                                "Move workspace-notes/readme-renamed.md to archive/readme-renamed.md."),
                        ExecutionPhase.APPLY));

        assertEquals(
                List.of("talos.delete_path"),
                ToolSurfacePlanner.defaultVisibleToolNames(
                        TaskContractResolver.fromUserRequest("Delete docs/old-plan.md please."),
                        ExecutionPhase.APPLY));

        assertEquals(
                List.of("talos.apply_workspace_batch", "talos.copy_path", "talos.edit_file", "talos.grep", "talos.list_dir",
                        "talos.mkdir", "talos.move_path", "talos.read_file", "talos.rename_path", "talos.retrieve",
                        "talos.write_file"),
                ToolSurfacePlanner.defaultVisibleToolNames(
                        TaskContractResolver.fromUserRequest("Summarize long-notes.txt into docs/summary.md."),
                        ExecutionPhase.APPLY));

        assertEquals(
                List.of("talos.grep", "talos.list_dir", "talos.read_file", "talos.retrieve", "talos.run_command"),
                ToolSurfacePlanner.defaultVisibleToolNames(
                        TaskContractResolver.fromUserRequest("verify that the Gradle build passes"),
                        ExecutionPhase.VERIFY));

        assertEquals(
                List.of("talos.list_dir", "talos.read_file"),
                ToolSurfacePlanner.defaultVisibleToolNames(
                        TaskContractResolver.fromUserRequest(
                                "Verify the final workspace paths for archive/readme-renamed.md, "
                                        + "copies/readme-final.md, and scratch/nested/reports. Do not edit files."),
                        ExecutionPhase.VERIFY));

        assertEquals(
                List.of("talos.list_dir", "talos.read_file"),
                ToolSurfacePlanner.defaultVisibleToolNames(
                        TaskContractResolver.fromUserRequest(
                                "Verify whether scratch/nested/reports exists as a directory. Do not edit files."),
                        ExecutionPhase.VERIFY));

        assertEquals(
                List.of("talos.run_command"),
                ToolSurfacePlanner.defaultVisibleToolNames(
                        TaskContractResolver.fromUserRequest(
                                "Run the approved Gradle test command profile for this workspace and report the exact command result."),
                        ExecutionPhase.VERIFY));
    }

    private static void assertWorkspaceOperationSurface(
            String request,
            List<String> expectedTools,
            String expectedReason
    ) {
        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.APPLY,
                registry());

        assertEquals(expectedTools, plan.nativeToolNames(), request);
        assertEquals(expectedReason, plan.reason(), request);
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
        registry.register(new DeletePathTool());
        registry.register(new RunCommandTool(plan -> new dev.talos.runtime.command.CommandResult(
                plan, 0, 1, false, false, "", "", false, false, false, "")));
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
