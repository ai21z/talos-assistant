package dev.talos.tools;

import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolValidationTest {

    @TempDir Path workspace;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new ToolContext(workspace, new Sandbox(workspace, null), new Config());
    }

    @Nested class RequireNonBlank {
        @Test void null_whenPresent() {
            assertNull(ToolValidation.requireNonBlank(
                    new ToolCall("t", Map.of("path", "src/Main.java")), "path"));
        }
        @Test void error_whenNull() {
            ToolResult r = ToolValidation.requireNonBlank(new ToolCall("t", Map.of()), "path");
            assertNotNull(r); assertFalse(r.success()); assertTrue(r.errorMessage().contains("path"));
        }
        @Test void error_whenBlank() {
            assertNotNull(ToolValidation.requireNonBlank(new ToolCall("t", Map.of("path", "  ")), "path"));
        }
    }

    @Nested class RequireNonEmpty {
        @Test void null_whenPresent() {
            assertNull(ToolValidation.requireNonEmpty(new ToolCall("t", Map.of("s", "text")), "s"));
        }
        @Test void null_forWhitespace() {
            assertNull(ToolValidation.requireNonEmpty(new ToolCall("t", Map.of("s", "  ")), "s"));
        }
        @Test void error_whenEmpty() {
            assertNotNull(ToolValidation.requireNonEmpty(new ToolCall("t", Map.of("s", "")), "s"));
        }
        @Test void error_whenNull() {
            assertNotNull(ToolValidation.requireNonEmpty(new ToolCall("t", Map.of()), "s"));
        }
    }

    @Nested class RequirePresent {
        @Test void null_whenPresent() {
            assertNull(ToolValidation.requirePresent(new ToolCall("t", Map.of("k", "")), "k"));
        }
        @Test void error_whenNull() {
            assertNotNull(ToolValidation.requirePresent(new ToolCall("t", Map.of()), "k"));
        }
    }

    @Nested class ResolveSandboxed {
        @Test void ok_insideWorkspace() {
            var r = ToolValidation.resolveSandboxed(ctx, "src/Main.java");
            assertInstanceOf(ToolValidation.PathResult.Ok.class, r);
        }
        @Test void err_outsideWorkspace() {
            var r = ToolValidation.resolveSandboxed(ctx, "../../etc/passwd");
            assertInstanceOf(ToolValidation.PathResult.Err.class, r);
        }
    }

    @Nested class ResolveFile {
        @Test void ok_existingFile() throws IOException {
            Files.writeString(workspace.resolve("a.txt"), "hi");
            assertInstanceOf(ToolValidation.PathResult.Ok.class,
                    ToolValidation.resolveFile(ctx, "a.txt"));
        }
        @Test void err_missing() {
            var r = ToolValidation.resolveFile(ctx, "no.txt");
            assertInstanceOf(ToolValidation.PathResult.Err.class, r);
            assertTrue(((ToolValidation.PathResult.Err) r).error().errorMessage().contains("not found"));
        }
        @Test void err_directory() throws IOException {
            Files.createDirectory(workspace.resolve("sub"));
            var r = ToolValidation.resolveFile(ctx, "sub");
            assertInstanceOf(ToolValidation.PathResult.Err.class, r);
            assertTrue(((ToolValidation.PathResult.Err) r).error().errorMessage().contains("directory"));
        }
    }

    @Nested class ResolveFileWithSize {
        @Test void ok_underLimit() throws IOException {
            Files.writeString(workspace.resolve("s.txt"), "hi");
            assertInstanceOf(ToolValidation.PathResult.Ok.class,
                    ToolValidation.resolveFile(ctx, "s.txt", 1024));
        }
        @Test void err_overLimit() throws IOException {
            Files.writeString(workspace.resolve("b.txt"), "x".repeat(2048));
            var r = ToolValidation.resolveFile(ctx, "b.txt", 1024);
            assertInstanceOf(ToolValidation.PathResult.Err.class, r);
            assertTrue(((ToolValidation.PathResult.Err) r).error().errorMessage().contains("too large"));
        }
    }

    @Nested class ResolveDirectory {
        @Test void ok_existing() throws IOException {
            Files.createDirectory(workspace.resolve("src"));
            assertInstanceOf(ToolValidation.PathResult.Ok.class,
                    ToolValidation.resolveDirectory(ctx, "src"));
        }
        @Test void err_missing() {
            var r = ToolValidation.resolveDirectory(ctx, "nope");
            assertInstanceOf(ToolValidation.PathResult.Err.class, r);
            assertTrue(((ToolValidation.PathResult.Err) r).error().errorMessage().contains("not found"));
        }
        @Test void err_isFile() throws IOException {
            Files.writeString(workspace.resolve("f.txt"), "x");
            var r = ToolValidation.resolveDirectory(ctx, "f.txt");
            assertInstanceOf(ToolValidation.PathResult.Err.class, r);
            assertTrue(((ToolValidation.PathResult.Err) r).error().errorMessage().contains("not a directory"));
        }
    }

    @Nested class IntParam {
        @Test void parsesValid() {
            assertEquals(42, ToolValidation.intParam(new ToolCall("t", Map.of("n", "42")), "n", 0));
        }
        @Test void default_whenAbsent() {
            assertEquals(10, ToolValidation.intParam(new ToolCall("t", Map.of()), "n", 10));
        }
        @Test void default_whenBlank() {
            assertEquals(10, ToolValidation.intParam(new ToolCall("t", Map.of("n", " ")), "n", 10));
        }
        @Test void default_whenNaN() {
            assertEquals(10, ToolValidation.intParam(new ToolCall("t", Map.of("n", "abc")), "n", 10));
        }
        @Test void trims() {
            assertEquals(99, ToolValidation.intParam(new ToolCall("t", Map.of("n", " 99 ")), "n", 0));
        }
    }

    @Nested class PathResultContract {
        @Test void patternMatch() {
            ToolValidation.PathResult r = new ToolValidation.PathResult.Ok(Path.of("x"));
            String got = switch (r) {
                case ToolValidation.PathResult.Ok ok -> "ok:" + ok.path();
                case ToolValidation.PathResult.Err e -> "err";
            };
            assertTrue(got.startsWith("ok:"));
        }
    }
}

