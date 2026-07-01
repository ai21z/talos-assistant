package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** T757: fail-closed mutation/checkpoint classification through the registry. */
class ToolMutationGateTest {

    private final ToolRegistry registry = ToolMetadataParityTest.bootstrapEquivalentRegistry();

    @Test
    void unknownToolNameFailsClosed() {
        assertTrue(ToolMutationGate.isMutating(registry, "talos.shredder"));
        assertTrue(ToolMutationGate.requiresCheckpoint(registry, "talos.shredder"));
        assertTrue(ToolMutationGate.isMutating(registry, ""));
        assertTrue(ToolMutationGate.isMutating(registry, null));
        assertTrue(ToolMutationGate.isMutating(null, "talos.read_file"));
    }

    @Test
    void registeredToolsClassifyByMetadata() {
        assertFalse(ToolMutationGate.isMutating(registry, "talos.read_file"));
        assertFalse(ToolMutationGate.isMutating(registry, "talos.grep"));
        assertFalse(ToolMutationGate.isMutating(registry, "talos.run_command"));
        assertTrue(ToolMutationGate.isMutating(registry, "talos.write_file"));
        assertTrue(ToolMutationGate.isMutating(registry, "talos.delete_path"));

        assertFalse(ToolMutationGate.requiresCheckpoint(registry, "talos.run_command"));
        assertTrue(ToolMutationGate.requiresCheckpoint(registry, "talos.edit_file"));
        assertTrue(ToolMutationGate.requiresCheckpoint(registry, "talos.apply_workspace_batch"));
    }

    @Test
    void aliasFormsResolveThroughRegistryRescue() {
        // The registry's alias rescue resolves model-emitted shapes; the gate
        // then classifies by the RESOLVED tool's metadata, not the raw name.
        assertTrue(ToolMutationGate.isMutating(registry, "writefile"));
        assertTrue(ToolMutationGate.isMutating(registry, "mv"));
        assertTrue(ToolMutationGate.isMutating(registry, "tool_use:write_file"));
        assertFalse(ToolMutationGate.isMutating(registry, "readfile"));
    }
}
