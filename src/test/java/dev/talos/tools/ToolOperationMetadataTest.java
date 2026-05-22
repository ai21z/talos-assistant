package dev.talos.tools;

import dev.talos.core.capability.CapabilityKind;
import dev.talos.tools.ToolOperationMetadata.PathRole;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RetrieveTool;
import dev.talos.runtime.command.RunCommandTool;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolOperationMetadataTest {

    @Test
    void readOnlyInspectionToolsExposeCapabilityMetadata() {
        assertMetadata(
                new ReadFileTool().descriptor().operationMetadata(),
                "talos.read_file",
                CapabilityKind.INSPECT,
                ToolRiskLevel.READ_ONLY,
                Map.of("path", PathRole.TARGET_FILE),
                false,
                false,
                false,
                false,
                "FILE_READ");

        assertMetadata(
                new ListDirTool().descriptor().operationMetadata(),
                "talos.list_dir",
                CapabilityKind.INSPECT,
                ToolRiskLevel.READ_ONLY,
                Map.of("path", PathRole.TARGET_DIRECTORY),
                false,
                false,
                false,
                false,
                "DIRECTORY_LISTED");

        assertMetadata(
                new GrepTool().descriptor().operationMetadata(),
                "talos.grep",
                CapabilityKind.INSPECT,
                ToolRiskLevel.READ_ONLY,
                Map.of(),
                false,
                false,
                false,
                false,
                "WORKSPACE_GREP");

        assertMetadata(
                new RetrieveTool(null).descriptor().operationMetadata(),
                "talos.retrieve",
                CapabilityKind.INSPECT,
                ToolRiskLevel.READ_ONLY,
                Map.of(),
                false,
                false,
                false,
                false,
                "WORKSPACE_RETRIEVED");
    }

    @Test
    void mutatingFileToolsExposeApprovalCheckpointAndTraceMetadata() {
        assertMetadata(
                new FileWriteTool().descriptor().operationMetadata(),
                "talos.write_file",
                CapabilityKind.CREATE,
                ToolRiskLevel.WRITE,
                Map.of("path", PathRole.TARGET_FILE),
                true,
                false,
                true,
                true,
                "FILE_WRITTEN");

        assertMetadata(
                new FileEditTool().descriptor().operationMetadata(),
                "talos.edit_file",
                CapabilityKind.EDIT,
                ToolRiskLevel.WRITE,
                Map.of("path", PathRole.TARGET_FILE),
                true,
                false,
                true,
                true,
                "FILE_EDITED");
    }

    @Test
    void commandToolAsksButDoesNotDeclareSourceMutationOrCheckpoint() {
        ToolOperationMetadata metadata = new RunCommandTool(plan -> new dev.talos.runtime.command.CommandResult(
                plan, 0, 1, false, false, "", "", false, false, false, ""))
                .descriptor()
                .operationMetadata();

        assertMetadata(
                metadata,
                "talos.run_command",
                CapabilityKind.EXECUTE,
                ToolRiskLevel.WRITE,
                Map.of(),
                false,
                false,
                true,
                false,
                "COMMAND_EXECUTED");
    }

    @Test
    void descriptorSuppliesConservativeDefaultMetadataWhenToolDoesNotDeclareIt() {
        ToolDescriptor descriptor = new ToolDescriptor(
                "talos.example_write",
                "example",
                "{}",
                ToolRiskLevel.WRITE);

        ToolOperationMetadata metadata = descriptor.operationMetadata();
        assertEquals("talos.example_write", metadata.toolName());
        assertEquals(CapabilityKind.EDIT, metadata.capabilityKind());
        assertEquals(ToolRiskLevel.WRITE, metadata.riskLevel());
        assertTrue(metadata.mutatesWorkspace());
        assertTrue(metadata.requiresApproval());
        assertTrue(metadata.requiresCheckpoint());
        assertFalse(metadata.destructive());
        assertEquals("TOOL_EXECUTED", metadata.traceEventKind());
    }

    private static void assertMetadata(
            ToolOperationMetadata metadata,
            String toolName,
            CapabilityKind capabilityKind,
            ToolRiskLevel riskLevel,
            Map<String, PathRole> pathRoles,
            boolean mutatesWorkspace,
            boolean canAffectMultiplePaths,
            boolean requiresApproval,
            boolean requiresCheckpoint,
            String traceEventKind) {
        assertNotNull(metadata);
        assertEquals(toolName, metadata.toolName());
        assertEquals(capabilityKind, metadata.capabilityKind());
        assertEquals(riskLevel, metadata.riskLevel());
        assertEquals(pathRoles, metadata.pathRoles());
        assertEquals(mutatesWorkspace, metadata.mutatesWorkspace());
        assertEquals(canAffectMultiplePaths, metadata.canAffectMultiplePaths());
        assertEquals(requiresApproval, metadata.requiresApproval());
        assertEquals(requiresCheckpoint, metadata.requiresCheckpoint());
        assertEquals(riskLevel == ToolRiskLevel.DESTRUCTIVE, metadata.destructive());
        assertEquals(traceEventKind, metadata.traceEventKind());
    }
}
