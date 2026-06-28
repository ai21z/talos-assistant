package dev.talos.runtime.toolcall;

import dev.talos.runtime.command.RunCommandTool;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolRiskLevel;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T757 anti-drift pins. Two sources of mutation truth remain after the
 * ToolCallSupport name lists were deleted: per-tool ToolOperationMetadata
 * (consumed by the TurnProcessor trust gates) and ToolAliasPolicy's
 * canonical sets (consumed by static name-classification heuristics).
 * This test pins (1) a golden metadata row per registered tool and
 * (2) parity between the two sources, so they cannot drift silently.
 */
class ToolMetadataParityTest {

    /** The same 13 tools TalosBootstrap registers (RetrieveTool's service is not used by descriptor()). */
    static ToolRegistry bootstrapEquivalentRegistry() {
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

    private record Golden(
            ToolRiskLevel risk,
            boolean mutates,
            boolean checkpoint,
            boolean approval,
            boolean destructive
    ) {}

    private static final Map<String, Golden> GOLDEN = Map.ofEntries(
            Map.entry("talos.read_file", new Golden(ToolRiskLevel.READ_ONLY, false, false, false, false)),
            Map.entry("talos.list_dir", new Golden(ToolRiskLevel.READ_ONLY, false, false, false, false)),
            Map.entry("talos.grep", new Golden(ToolRiskLevel.READ_ONLY, false, false, false, false)),
            Map.entry("talos.retrieve", new Golden(ToolRiskLevel.READ_ONLY, false, false, false, false)),
            Map.entry("talos.write_file", new Golden(ToolRiskLevel.WRITE, true, true, true, false)),
            Map.entry("talos.edit_file", new Golden(ToolRiskLevel.WRITE, true, true, true, false)),
            Map.entry("talos.apply_workspace_batch", new Golden(ToolRiskLevel.WRITE, true, true, true, false)),
            Map.entry("talos.mkdir", new Golden(ToolRiskLevel.WRITE, true, true, true, false)),
            Map.entry("talos.move_path", new Golden(ToolRiskLevel.WRITE, true, true, true, false)),
            Map.entry("talos.copy_path", new Golden(ToolRiskLevel.WRITE, true, true, true, false)),
            Map.entry("talos.rename_path", new Golden(ToolRiskLevel.WRITE, true, true, true, false)),
            Map.entry("talos.delete_path", new Golden(ToolRiskLevel.DESTRUCTIVE, true, true, true, true)),
            // run_command does not mutate the workspace through file tools and
            // is gated by command profiles + approval, not checkpoints.
            Map.entry("talos.run_command", new Golden(ToolRiskLevel.WRITE, false, false, true, false)));

    @Test
    void everyRegisteredToolMatchesItsGoldenMetadataRow() {
        ToolRegistry registry = bootstrapEquivalentRegistry();

        for (var entry : GOLDEN.entrySet()) {
            String name = entry.getKey();
            Golden expected = entry.getValue();
            var tool = registry.get(name);
            assertNotNull(tool, name + " must be registered");
            ToolOperationMetadata meta = tool.descriptor().operationMetadata();
            assertNotNull(meta, name + " metadata must never be null");
            assertEquals(expected.risk(), meta.riskLevel(), name + " riskLevel");
            assertEquals(expected.mutates(), meta.mutatesWorkspace(), name + " mutatesWorkspace");
            assertEquals(expected.checkpoint(), meta.requiresCheckpoint(), name + " requiresCheckpoint");
            assertEquals(expected.approval(), meta.requiresApproval(), name + " requiresApproval");
            assertEquals(expected.destructive(), meta.destructive(), name + " destructive");
        }
    }

    @Test
    void goldenTableCoversTheWholeRegistry() {
        ToolRegistry registry = bootstrapEquivalentRegistry();
        for (var descriptor : registry.descriptors()) {
            assertTrue(GOLDEN.containsKey(descriptor.name()),
                    "unpinned tool registered: " + descriptor.name()
                            + " - add a golden metadata row before shipping it");
        }
        assertEquals(GOLDEN.size(), registry.descriptors().size(),
                "golden table and registry must stay the same size");
    }

    @Test
    void canonicalDescriptorCatalogMatchesTheBootstrapRegistry() {
        // T761: defaultVisibleToolNames derives from plan() over
        // CanonicalToolDescriptors - this pin keeps that catalog from rotting
        // when tools are added or their metadata changes.
        var canonical = CanonicalToolDescriptors.registry().descriptors();
        var bootstrap = bootstrapEquivalentRegistry().descriptors();

        assertEquals(
                bootstrap.stream().map(d -> d.name()).sorted().toList(),
                canonical.stream().map(d -> d.name()).sorted().toList(),
                "canonical catalog must register exactly the bootstrap tool set");
        for (var expected : bootstrap) {
            var actual = canonical.stream()
                    .filter(d -> d.name().equals(expected.name()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(expected.riskLevel(), actual.riskLevel(), expected.name());
            assertEquals(expected.operationMetadata(), actual.operationMetadata(), expected.name());
        }
    }

    @Test
    void aliasPolicyClassificationMatchesMetadataForEveryTool() {
        ToolRegistry registry = bootstrapEquivalentRegistry();
        for (var descriptor : registry.descriptors()) {
            String name = descriptor.name();
            ToolOperationMetadata meta = descriptor.operationMetadata();
            assertEquals(meta.mutatesWorkspace(), ToolAliasPolicy.isMutating(name),
                    name + ": ToolAliasPolicy.isMutating must match metadata.mutatesWorkspace");
            assertEquals(meta.riskLevel() == ToolRiskLevel.READ_ONLY, ToolAliasPolicy.isReadOnly(name),
                    name + ": ToolAliasPolicy.isReadOnly must match READ_ONLY risk");
        }
    }
}
