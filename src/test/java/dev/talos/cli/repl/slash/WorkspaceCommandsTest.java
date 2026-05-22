package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.Config;
import dev.talos.core.extract.FakeOcrCli;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        void slash_grep_private_mode_does_not_expose_neighbor_fields() throws IOException {
            Files.writeString(ws.resolve("health-notes.md"),
                    "Patient: Mira Stone\nCondition marker: DO_NOT_LEAK_PRIVATE_ROW\n");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("DO_NOT_LEAK_PRIVATE_ROW", privateModeContext());

            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("health-notes.md"), r.toString());
            assertFalse(r.toString().contains("DO_NOT_LEAK_PRIVATE_ROW"), r.toString());
            assertFalse(r.toString().contains("Mira Stone"), r.toString());
            assertTrue(r.toString().contains("withheld by private-mode search policy"), r.toString());
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
        void slash_grep_enabled_pdf_extraction_finds_known_text() throws IOException {
            writePdf(ws.resolve("report.pdf"), "Slash PDF budget alpha");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("budget alpha", extractionContext("pdf"));

            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("report.pdf"), r.toString());
            assertTrue(r.toString().contains("Slash PDF budget alpha"), r.toString());
        }

        @Test
        void slash_grep_enabled_docx_extraction_finds_known_text() throws IOException {
            writeDocx(ws.resolve("brief.docx"), "Slash Word roadmap beta");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("roadmap beta", extractionContext("word"));

            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("brief.docx"), r.toString());
            assertTrue(r.toString().contains("Slash Word roadmap beta"), r.toString());
        }

        @Test
        void slash_grep_private_mode_docx_extraction_withholds_ordinary_private_facts() throws IOException {
            writeDocx(ws.resolve("medical-notes.docx"), "Patient name: Marina Stavrou");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("Marina Stavrou", privateExtractionContext("word"));

            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("medical-notes.docx"), r.toString());
            assertTrue(r.toString().contains("withheld from model context by private-document policy"), r.toString());
            assertFalse(r.toString().contains("Marina Stavrou"), r.toString());
            assertFalse(r.toString().contains("Patient name"), r.toString());
        }

        @Test
        void slash_grep_enabled_xlsx_extraction_finds_known_text() throws IOException {
            writeXlsx(ws.resolve("budget.xlsx"), "Slash Excel revenue gamma");
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("revenue gamma", extractionContext("excel"));

            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("budget.xlsx"), r.toString());
            assertTrue(r.toString().contains("B2: Slash Excel revenue gamma"), r.toString());
        }

        @Test
        void slash_grep_enabled_image_ocr_finds_known_text() throws IOException {
            Files.write(ws.resolve("scan.png"), new byte[] { (byte) 0x89, 'P', 'N', 'G' });
            var cmd = new GrepCommand(ws);

            Result r = cmd.execute("visible text", ocrExtractionContext());

            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("scan.png"), r.toString());
            assertTrue(r.toString().contains("OCR fixture visible text"), r.toString());
            assertFalse(r.toString().contains("t267-token-should-not-appear"), r.toString());
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

    private static Context extractionContext(String family) {
        Config cfg = new Config(null);
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        Map<String, Object> familyCfg = new LinkedHashMap<>();
        familyCfg.put("enabled", Boolean.TRUE);
        documentExtraction.put(family, familyCfg);
        cfg.data.put("document_extraction", documentExtraction);
        return Context.builder(cfg).build();
    }

    private static Context privateModeContext() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of("mode", "private")));
        return Context.builder(cfg).build();
    }

    private static Context privateExtractionContext(String family) {
        Config cfg = new Config(null);
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        Map<String, Object> familyCfg = new LinkedHashMap<>();
        familyCfg.put("enabled", Boolean.TRUE);
        documentExtraction.put(family, familyCfg);
        cfg.data.put("document_extraction", documentExtraction);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of("mode", "private")));
        return Context.builder(cfg).build();
    }

    @SuppressWarnings("unchecked")
    private static Context ocrExtractionContext() {
        Config cfg = new Config(null);
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        Map<String, Object> familyCfg = new LinkedHashMap<>();
        familyCfg.put("enabled", Boolean.TRUE);
        familyCfg.put("command", javaExecutable());
        familyCfg.put("args", List.of(
                "-cp",
                System.getProperty("java.class.path"),
                FakeOcrCli.class.getName(),
                "{input}"));
        documentExtraction.put("image_ocr", familyCfg);
        cfg.data.put("document_extraction", documentExtraction);
        return Context.builder(cfg).build();
    }

    private static String javaExecutable() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static void writePdf(Path path, String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            document.save(path.toFile());
        }
    }

    private static void writeDocx(Path path, String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(text);
            try (var out = Files.newOutputStream(path)) {
                document.write(out);
            }
        }
    }

    private static void writeXlsx(Path path, String text) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Budget");
            var row = sheet.createRow(1);
            row.createCell(1).setCellValue(text);
            try (var out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
        }
    }
}

