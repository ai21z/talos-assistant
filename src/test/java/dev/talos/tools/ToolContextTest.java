package dev.talos.tools;

import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolContextTest {

    @TempDir Path workspace;

    @Test
    void constructorRejectsNulls() {
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        Config config = new Config();

        assertThrows(NullPointerException.class, () -> new ToolContext(null, sandbox, config));
        assertThrows(NullPointerException.class, () -> new ToolContext(workspace, null, config));
        assertThrows(NullPointerException.class, () -> new ToolContext(workspace, sandbox, null));
    }

    @Test
    void resolveProducesNormalizedPath() {
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ToolContext ctx = new ToolContext(workspace, sandbox, new Config());

        Path resolved = ctx.resolve("src/Main.java");
        assertTrue(resolved.isAbsolute());
        assertTrue(resolved.toString().contains("Main.java"));
    }

    @Test
    void resolveDoesNotCheckSandbox() {
        // resolve() should NOT enforce sandbox — caller must check separately
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ToolContext ctx = new ToolContext(workspace, sandbox, new Config());

        // This resolves outside workspace but resolve() itself should not throw
        Path resolved = ctx.resolve("../../etc/passwd");
        assertNotNull(resolved);
        // But sandbox should reject it
        assertFalse(ctx.sandbox().allowedPath(resolved));
    }

    @Test
    void accessors() {
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        Config config = new Config();
        ToolContext ctx = new ToolContext(workspace, sandbox, config);

        assertSame(workspace, ctx.workspace());
        assertSame(sandbox, ctx.sandbox());
        assertSame(config, ctx.config());
    }
}

