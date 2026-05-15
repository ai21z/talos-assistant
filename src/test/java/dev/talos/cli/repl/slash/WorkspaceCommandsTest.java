package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for workspace-bound commands: GrepCommand, WorkspaceCommand.
 *
 * <p>Uses {@code @TempDir} for isolated filesystem operations.
 */
@DisplayName("REPL commands — workspace-bound")
class WorkspaceCommandsTest {

    @TempDir
    Path ws;

    private final Context ctx = Context.builder(new Config()).build();

    // ═══════════════════════════════════════════════════════════════════════
    //  GrepCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GrepCommand")
    class Grep {

        @Test
        void finds_matching_text() throws IOException {
            Files.writeString(ws.resolve("hello.java"), "public class Hello {\n  // greeting\n}\n");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("greeting", ctx);
            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("greeting"));
            assertTrue(r.toString().contains("1 matches"));
        }

        @Test
        void no_matches_returns_info() throws IOException {
            Files.writeString(ws.resolve("hello.java"), "public class Hello {}\n");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("nonexistent_string_xyz", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertTrue(r.toString().contains("No matches"));
        }

        @Test
        void empty_args_returns_error() {
            var cmd = new GrepCommand(ws);
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test
        void null_args_returns_error() {
            var cmd = new GrepCommand(ws);
            Result r = cmd.execute(null, ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test
        void quoted_pattern_strips_quotes() throws IOException {
            Files.writeString(ws.resolve("data.txt"), "SMOKEPROBE-123\n");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("\"SMOKEPROBE-\"", ctx);
            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("SMOKEPROBE"));
        }

        @Test
        void case_insensitive_matching() throws IOException {
            Files.writeString(ws.resolve("test.java"), "FooBarBaz\n");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("foobarbaz", ctx);
            assertInstanceOf(Result.Ok.class, r);
        }

        @Test
        void shows_line_numbers() throws IOException {
            Files.writeString(ws.resolve("lines.java"), "line1\nline2\ntarget_here\nline4\n");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("target_here", ctx);
            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("3:"), "Should show line number 3");
        }

        @Test
        void searches_css_files_by_default() throws IOException {
            Files.writeString(ws.resolve("style.css"), ".cta-button { color: white; }\n");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("cta-button", ctx);

            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("style.css"), r.toString());
            assertTrue(r.toString().contains(".cta-button"), r.toString());
        }

        @Test
        void slash_grep_does_not_leak_env_canary() throws IOException {
            Files.writeString(ws.resolve(".env"), "TALOS_SECRET=DO_NOT_LEAK_T267_ENV\n");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("DO_NOT_LEAK_T267_ENV", ctx);

            assertTrue(r instanceof Result.Ok || r instanceof Result.Info);
            assertFalse(r.toString().contains("DO_NOT_LEAK_T267_ENV"));
            assertTrue(r.toString().contains("protected content") || r.toString().contains("[redacted"));
        }

        @Test
        void slash_grep_does_not_leak_private_marker() throws IOException {
            Files.writeString(ws.resolve("notes.md"),
                    "PRIVATE_MARKER = DO_NOT_LEAK_T267_PRIVATE_MARKER\nordinary searchable text\n");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("PRIVATE_MARKER", ctx);

            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("PRIVATE_MARKER=[redacted]"));
            assertFalse(r.toString().contains("DO_NOT_LEAK_T267_PRIVATE_MARKER"));
        }

        @Test
        void slash_grep_unsupported_binary_skips_and_reports() throws IOException {
            Files.writeString(ws.resolve("report.docx"), "budget canary in fake docx payload\n");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("budget", ctx);

            assertTrue(r instanceof Result.Ok || r instanceof Result.Info);
            assertFalse(r.toString().contains("fake docx payload"));
            assertTrue(r.toString().contains("Search was limited to searchable text files")
                    || r.toString().contains("Skipped unsupported"));
        }

        @Test
        void skips_build_directories() throws IOException {
            Path buildDir = ws.resolve("build");
            Files.createDirectories(buildDir);
            Files.writeString(buildDir.resolve("output.java"), "should_not_find_this\n");
            Files.writeString(ws.resolve("src.java"), "findable content\n");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("should_not_find_this", ctx);
            assertInstanceOf(Result.Info.class, r, "build/ should be excluded");
        }

        @Test
        void spec_name() {
            var cmd = new GrepCommand(ws);
            assertEquals("grep", cmd.spec().name());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  WorkspaceCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WorkspaceCommand")
    class Workspace {

        @Test
        void returns_trusted_info() {
            var cmd = new WorkspaceCommand(ws);
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.TrustedInfo.class, r);
        }

        @Test
        void output_contains_workspace_path() {
            var cmd = new WorkspaceCommand(ws);
            Result r = cmd.execute("", ctx);
            String text = r.toString();
            assertTrue(text.contains("Workspace"), "Should show workspace label");
        }

        @Test
        void output_contains_index_dir() {
            var cmd = new WorkspaceCommand(ws);
            Result r = cmd.execute("", ctx);
            String text = r.toString();
            assertTrue(text.contains("Index dir"), "Should show index dir");
        }

        @Test
        void output_contains_vectors_status() {
            var cmd = new WorkspaceCommand(ws);
            Result r = cmd.execute("", ctx);
            String text = r.toString();
            assertTrue(text.contains("Vectors"), "Should show vector status");
        }

        @Test
        void output_shows_no_index_for_empty_workspace() {
            var cmd = new WorkspaceCommand(ws);
            Result r = cmd.execute("", ctx);
            String text = r.toString();
            assertTrue(text.contains("NO"), "Empty workspace should have no index");
        }

        @Test
        void spec_name_and_alias() {
            var cmd = new WorkspaceCommand(ws);
            assertEquals("workspace", cmd.spec().name());
            assertTrue(cmd.spec().aliases().contains("where"));
        }

        @Test
        void spec_description_says_show_only() {
            var cmd = new WorkspaceCommand(ws);

            String description = cmd.spec().summary().toLowerCase();
            assertTrue(description.contains("show"), description);
            assertTrue(description.contains("does not change"), description);
        }
    }
}

