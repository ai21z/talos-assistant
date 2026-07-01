package dev.talos.core.tool;

import dev.talos.tools.BackendToolProfile;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ToolNamePolicyTest {

    @Test
    void resolvesCanonicalToolNames() {
        ToolNamePolicy.Decision decision = ToolNamePolicy.resolve("talos.read_file");

        assertTrue(decision.accepted());
        assertEquals("talos.read_file", decision.canonicalToolName());
        assertEquals("read_file", decision.localCanonicalName());
        assertEquals(ToolNamePolicy.AliasDecisionStatus.CANONICAL, decision.status());
        assertEquals(ToolNamePolicy.BackendProfile.TALOS, decision.profile());
        assertTrue(decision.readOnly());
        assertFalse(decision.mutating());
    }

    @Test
    void resolvesBackendAliasesWithoutToolsDependency() {
        ToolNamePolicy.Decision decision = ToolNamePolicy.resolve("tool_use:write_file");

        assertTrue(decision.accepted());
        assertEquals("talos.write_file", decision.canonicalToolName());
        assertEquals("write_file", decision.localCanonicalName());
        assertEquals(ToolNamePolicy.AliasDecisionStatus.ACCEPTED_ALIAS, decision.status());
        assertEquals(ToolNamePolicy.BackendProfile.TOOL_USE, decision.profile());
        assertFalse(decision.readOnly());
        assertTrue(decision.mutating());
    }

    @Test
    void rejectsUnknownNamespaceButRetainsCanonicalTarget() {
        ToolNamePolicy.Decision decision = ToolNamePolicy.resolve("foreign:write_file");

        assertFalse(decision.accepted());
        assertTrue(decision.traceWorthy());
        assertEquals("talos.write_file", decision.canonicalToolName());
        assertEquals(ToolNamePolicy.AliasDecisionStatus.REJECTED_UNKNOWN_NAMESPACE, decision.status());
        assertEquals(ToolNamePolicy.BackendProfile.UNKNOWN, decision.profile());
    }

    @Test
    void normalizesTalosSeparators() {
        assertEquals("talos.write_file", ToolNamePolicy.normalizeTalosSeparator("talos:write_file"));
        assertEquals("talos.write_file", ToolNamePolicy.normalizeTalosSeparator("talos/write_file"));
        assertEquals("talos.write_file", ToolNamePolicy.normalizeTalosSeparator("talos-write_file"));
        assertEquals("talos.write_file", ToolNamePolicy.normalizeTalosSeparator("talos_write_file"));
    }

    @Test
    void findsFirstToolAliasToken() {
        Optional<String> token = ToolNamePolicy.firstToolAliasToken(
                "Please use file_utils:read_file and then continue.");

        assertEquals(Optional.of("file_utils:read_file"), token);
    }

    @Test
    void backendProfileNamesStayCompatibleWithToolsApi() {
        assertEquals(
                Arrays.stream(BackendToolProfile.values()).map(Enum::name).toList(),
                Arrays.stream(ToolNamePolicy.BackendProfile.values()).map(Enum::name).toList());
    }
}
