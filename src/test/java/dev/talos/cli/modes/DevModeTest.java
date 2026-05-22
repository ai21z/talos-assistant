package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Limits;
import dev.talos.runtime.Result;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DevMode} — local file operations (open/show/view + ls/list/dir).
 *
 * <p>Uses {@link TempDir} for isolated filesystem operations and
 * {@link Context.Builder} with explicit Sandbox/Limits wiring.
 */
@DisplayName("DevMode")
class DevModeTest {

    private final DevMode mode = new DevMode();

    @TempDir
    Path ws;

    // ═══════════════════════════════════════════════════════════════════════
    //  canHandle
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("canHandle")
    class CanHandle {

        @Test void open_prefix()  { assertTrue(mode.canHandle("open README.md")); }
        @Test void show_prefix()  { assertTrue(mode.canHandle("show src/Main.java")); }
        @Test void view_prefix()  { assertTrue(mode.canHandle("view config.yml")); }
        @Test void ls_prefix()    { assertTrue(mode.canHandle("ls src")); }
        @Test void list_prefix()  { assertTrue(mode.canHandle("list .")); }
        @Test void dir_prefix()   { assertTrue(mode.canHandle("dir build")); }
        @Test void ls_bare()      { assertTrue(mode.canHandle("ls")); }
        @Test void list_bare()    { assertTrue(mode.canHandle("list")); }
        @Test void dir_bare()     { assertTrue(mode.canHandle("dir")); }

        @Test void case_insensitive() { assertTrue(mode.canHandle("OPEN foo.txt")); }
        @Test void leading_whitespace() { assertTrue(mode.canHandle("  ls src")); }

        @Test void null_input()   { assertFalse(mode.canHandle(null)); }
        @Test void empty_input()  { assertFalse(mode.canHandle("")); }
        @Test void blank_input()  { assertFalse(mode.canHandle("   ")); }
        @Test void random_text()  { assertFalse(mode.canHandle("what is java?")); }

        @Test void show_me_the() {
            // "show me the X" should be handled (normalized in handle(), not canHandle())
            assertTrue(mode.canHandle("show me the README.md"));
        }

        @Test
        void natural_list_names_evidence_prompt_is_not_a_dev_command() {
            assertFalse(mode.canHandle(
                    "List names only at workspace root. Does ideas exist here? Answer from evidence only."));
            assertFalse(mode.canHandle(
                    "list names only for batch-one and workspace root. Did batch-two exist? Answer from evidence only."));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  List operations
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("List operations")
    class ListOps {

        @Test
        void ls_bare_lists_workspace_root() throws IOException {
            Files.createFile(ws.resolve("hello.txt"));
            Files.createDirectory(ws.resolve("subdir"));

            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("ls", ws, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Ok.class, result.get());
            String text = ((Result.Ok) result.get()).text;
            assertTrue(text.contains("[FILE] hello.txt"), "Should list files");
            assertTrue(text.contains("[DIR]  subdir"), "Should list directories");
        }

        @Test
        void ls_subdirectory() throws IOException {
            Path sub = ws.resolve("src");
            Files.createDirectory(sub);
            Files.createFile(sub.resolve("Main.java"));

            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("ls src", ws, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Ok.class, result.get());
            String text = ((Result.Ok) result.get()).text;
            assertTrue(text.contains("[FILE] Main.java"));
        }

        @Test
        void ls_sorts_dirs_before_files() throws IOException {
            Files.createFile(ws.resolve("zebra.txt"));
            Files.createDirectory(ws.resolve("alpha"));
            Files.createFile(ws.resolve("beta.txt"));

            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("ls", ws, ctx);

            String text = ((Result.Ok) result.get()).text;
            int dirIdx = text.indexOf("[DIR]  alpha");
            int fileIdx = text.indexOf("[FILE] beta.txt");
            assertTrue(dirIdx < fileIdx, "Directories should appear before files");
        }

        @Test
        void ls_clips_at_limit() throws IOException {
            // Create more entries than limit allows
            Limits smallLimit = new Limits(100, 10_000_000L, 10, 20_000, 500, 3, 300_000L, 10_000L, 10);
            for (int i = 0; i < 5; i++) {
                Files.createFile(ws.resolve("file" + i + ".txt"));
            }

            Context ctx = Context.builder(new Config())
                    .limits(smallLimit)
                    .sandbox(new Sandbox(ws, Map.of()))
                    .build();

            Optional<Result> result = mode.handle("ls", ws, ctx);
            String text = ((Result.Ok) result.get()).text;
            assertTrue(text.contains("showing first 3 entries"), "Should show clipping message");
        }

        @Test
        void ls_nonexistent_directory() {
            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("ls nosuchdir", ws, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Info.class, result.get());
            assertTrue(((Result.Info) result.get()).text.contains("Not found"));
        }

        @Test
        void ls_file_not_directory() throws IOException {
            Files.createFile(ws.resolve("readme.txt"));

            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("ls readme.txt", ws, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Info.class, result.get());
            assertTrue(((Result.Info) result.get()).text.contains("Not a directory"));
        }

        @Test
        void ls_outside_workspace_refused() throws IOException {
            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("ls ../../..", ws, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Info.class, result.get());
            assertTrue(((Result.Info) result.get()).text.contains("Refusing"));
        }

        @Test
        void list_and_dir_work_as_aliases() throws IOException {
            Files.createFile(ws.resolve("f.txt"));
            Context ctx = ctxForWorkspace(ws);

            Optional<Result> r1 = mode.handle("list", ws, ctx);
            Optional<Result> r2 = mode.handle("dir", ws, ctx);

            assertTrue(r1.isPresent());
            assertTrue(r2.isPresent());
            assertInstanceOf(Result.Ok.class, r1.get());
            assertInstanceOf(Result.Ok.class, r2.get());
            // Both should contain the file
            assertTrue(((Result.Ok) r1.get()).text.contains("f.txt"));
            assertTrue(((Result.Ok) r2.get()).text.contains("f.txt"));
        }

        @Test
        void natural_list_files_here_lists_workspace_root() throws IOException {
            Files.createFile(ws.resolve("index.html"));
            Files.createFile(ws.resolve("style.css"));
            Context ctx = ctxForWorkspace(ws);

            Optional<Result> result = mode.handle("list the files here", ws, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Ok.class, result.get());
            String text = ((Result.Ok) result.get()).text;
            assertTrue(text.contains("[FILE] index.html"), text);
            assertTrue(text.contains("[FILE] style.css"), text);
            assertFalse(text.contains("Not found: the"), text);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  File read operations
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("File read operations")
    class FileRead {

        @Test
        void open_reads_file_content() throws IOException {
            Files.writeString(ws.resolve("hello.txt"), "Hello World\nLine two\n");

            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("open hello.txt", ws, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Ok.class, result.get());
            String text = ((Result.Ok) result.get()).text;
            assertTrue(text.contains("Hello World"), "Should contain file content");
            assertTrue(text.contains("Line two"), "Should contain second line");
            assertTrue(text.contains("hello.txt"), "Should show filename in header");
        }

        @Test
        void show_reads_file() throws IOException {
            Files.writeString(ws.resolve("data.txt"), "some data");

            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("show data.txt", ws, ctx);

            assertInstanceOf(Result.Ok.class, result.get());
            assertTrue(((Result.Ok) result.get()).text.contains("some data"));
        }

        @Test
        void view_reads_file() throws IOException {
            Files.writeString(ws.resolve("config.yml"), "key: value");

            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("view config.yml", ws, ctx);

            assertInstanceOf(Result.Ok.class, result.get());
            assertTrue(((Result.Ok) result.get()).text.contains("key: value"));
        }

        @Test
        void open_nonexistent_file() {
            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("open ghost.txt", ws, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Info.class, result.get());
            assertTrue(((Result.Info) result.get()).text.contains("Not found"));
        }

        @Test
        void open_directory_suggests_ls() throws IOException {
            Files.createDirectory(ws.resolve("mydir"));

            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("open mydir", ws, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Info.class, result.get());
            String text = ((Result.Info) result.get()).text;
            assertTrue(text.contains("directory"), "Should indicate it's a directory");
            assertTrue(text.contains("ls"), "Should suggest using ls");
        }

        @Test
        void open_outside_workspace_refused() {
            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("open ../../../etc/passwd", ws, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Info.class, result.get());
            assertTrue(((Result.Info) result.get()).text.contains("Refusing"));
        }

        @Test
        void open_truncates_large_file() throws IOException {
            // Create a file exceeding the line limit
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("Line ").append(i).append("\n");
            }
            Files.writeString(ws.resolve("big.txt"), sb.toString());

            // Use a limit of 10 lines
            Limits smallLimits = new Limits(100, 10_000_000L, 10, 20_000, 10, 1000, 300_000L, 10_000L, 10);
            Context ctx = Context.builder(new Config())
                    .limits(smallLimits)
                    .sandbox(new Sandbox(ws, Map.of()))
                    .build();

            Optional<Result> result = mode.handle("open big.txt", ws, ctx);
            String text = ((Result.Ok) result.get()).text;
            assertTrue(text.contains("truncated"), "Should indicate truncation");
        }

        @Test
        void open_shows_file_size_in_header() throws IOException {
            String content = "abcdefghij"; // 10 bytes
            Files.writeString(ws.resolve("sized.txt"), content);

            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("open sized.txt", ws, ctx);

            String text = ((Result.Ok) result.get()).text;
            assertTrue(text.contains("bytes"), "Should show byte count in header");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Path extraction & normalization
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Path extraction & normalization")
    class PathExtraction {

        @Test
        void show_me_the_normalized() throws IOException {
            Files.writeString(ws.resolve("README.md"), "# Title");

            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("show me the README.md", ws, ctx);

            assertInstanceOf(Result.Ok.class, result.get());
            assertTrue(((Result.Ok) result.get()).text.contains("# Title"));
        }

        @Test
        void show_me_normalized() throws IOException {
            Files.writeString(ws.resolve("info.txt"), "info");

            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("show me info.txt", ws, ctx);

            assertInstanceOf(Result.Ok.class, result.get());
            assertTrue(((Result.Ok) result.get()).text.contains("info"));
        }

        @Test
        void quoted_path() throws IOException {
            Path dir = ws.resolve("my dir");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("file.txt"), "quoted");

            Context ctx = ctxForWorkspace(ws);
            Optional<Result> result = mode.handle("open \"my dir/file.txt\"", ws, ctx);

            assertInstanceOf(Result.Ok.class, result.get());
            assertTrue(((Result.Ok) result.get()).text.contains("quoted"));
        }

        @Test
        void open_no_argument() {
            Context ctx = ctxForWorkspace(ws);
            // "open" alone has a space requirement in canHandle, but handle() gets raw input
            // canHandle("open ") == false since there's a trailing space with no content
            // But "open " with nothing won't match ARG, target will be null
            Optional<Result> result = mode.handle("open ", ws, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Info.class, result.get());
            assertTrue(((Result.Info) result.get()).text.contains("not found") ||
                       ((Result.Info) result.get()).text.contains("File not found"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Mode metadata
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Mode metadata")
    class Metadata {

        @Test
        void name_is_dev() {
            assertEquals("dev", mode.name());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /** Build a minimal Context with Sandbox rooted at the given workspace. */
    private static Context ctxForWorkspace(Path workspace) {
        return Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();
    }
}

