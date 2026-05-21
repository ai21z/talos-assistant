package dev.talos.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolAliasPolicyOwnershipTest {

    @Test
    void toolAliasPolicyIsOwnedByToolsPackage() throws Exception {
        assertTrue(Files.exists(Path.of("src/main/java/dev/talos/tools/ToolAliasPolicy.java")));
        assertFalse(Files.exists(Path.of("src/main/java/dev/talos/runtime/toolcall/ToolAliasPolicy.java")));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));
        assertFalse(baseline.contains("dev.talos.runtime.toolcall.ToolAliasPolicy"), baseline);
    }

    @Test
    void toolRegistryDoesNotDependOnRuntimeLogPolicy() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/talos/tools/ToolRegistry.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertFalse(source.contains("dev.talos.runtime.policy.SafeLogFormatter"), source);
        assertFalse(baseline.contains(
                "src/main/java/dev/talos/tools/ToolRegistry.java"
                        + "|dev.talos.runtime.policy.SafeLogFormatter"), baseline);
    }

    @Test
    void toolAliasPolicyStillResolvesBackendAliases() {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve("tool_use:write_file");

        assertTrue(decision.accepted());
        assertEquals("talos.write_file", decision.canonicalToolName());
        assertEquals("write_file", decision.localCanonicalName());
        assertEquals(BackendToolProfile.TOOL_USE, decision.profile());
    }
}
