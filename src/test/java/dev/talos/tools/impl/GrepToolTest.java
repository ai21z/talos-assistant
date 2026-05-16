package dev.talos.tools.impl;

import dev.talos.core.Config;
import dev.talos.core.extract.FakeOcrCli;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GrepToolTest {

    @TempDir Path workspace;
    private GrepTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        tool = new GrepTool();
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ctx = new ToolContext(workspace, sandbox, new Config());

        Files.writeString(workspace.resolve("App.java"),
                "package com.example;\npublic class App {\n    public void run() {}\n}\n");
        Files.writeString(workspace.resolve("README.md"),
                "# My Project\nThis is a demo project.\nSee App.java for details.\n");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Util.java"),
                "package com.example;\npublic class Util {\n    public static String hello() { return \"hello\"; }\n}\n");
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve(".git/config"), "some git config with public");
    }

    @Test void descriptor() {
        assertEquals("talos.grep", tool.name());
        assertNotNull(tool.descriptor().parametersSchema());
    }

    @Test void plainTextSearch() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "public class")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("App.java"));
        assertTrue(r.output().contains("Util.java"));
    }

    @Test void regexSearch() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "class\\s+\\w+", "regex", "true")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("App.java"));
    }

    @Test void includeGlobFilter() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "public", "include", "*.java")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains(".java"));
        assertFalse(r.output().contains("README.md"));
    }

    @Test void commaSeparatedIncludeGlobIsRejectedInsteadOfSilentFalseNegative() throws IOException {
        Files.writeString(workspace.resolve("script.js"),
                "const button = document.querySelector('.missing-button');\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of(
                "pattern", "\\.missing-button",
                "include", "*.html, *.css",
                "regex", "true")), ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.error().message().contains("include"), r.error().message());
        assertTrue(r.error().message().contains("comma-separated"), r.error().message());
    }

    @Test void noMatchesFound() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "xyznonexistentxyz")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("No matches"));
    }

    @Test void includeGlobReportsUnsupportedBinaryDocuments() throws IOException {
        Files.writeString(workspace.resolve("sample.xlsx"), "fake excel payload");

        var r = tool.execute(new ToolCall("talos.grep", Map.of(
                "pattern", "budget",
                "include", "*.xlsx")), ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("No matches found"));
        assertTrue(r.output().contains("Skipped unsupported binary document(s): sample.xlsx"));
        assertTrue(r.output().contains("cannot extract PDF/Office binary contents"));
    }

    @Test void enabledPdfExtractionGrepFindsKnownText() throws IOException {
        writePdf(workspace.resolve("report.pdf"), "Quarterly budget alpha");
        ToolContext extractionCtx = extractionCtx("pdf");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "budget alpha")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("report.pdf"), r.output());
        assertTrue(r.output().contains("Quarterly budget alpha"), r.output());
    }

    @Test void enabledPdfExtractionGrepReportsNoTextPdfAsSkipped() throws IOException {
        writeEmptyPdf(workspace.resolve("scan.pdf"));
        ToolContext extractionCtx = extractionCtx("pdf");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "budget alpha")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("No matches found"), r.output());
        assertTrue(r.output().contains("Skipped unsupported binary document(s): scan.pdf"), r.output());
        assertTrue(r.output().contains("OCR_REQUIRED"), r.output());
        assertFalse(r.output().contains("scan.pdf:"), r.output());
    }

    @Test void enabledDocxExtractionGrepFindsKnownText() throws IOException {
        writeDocx(workspace.resolve("brief.docx"), "Word roadmap beta");
        ToolContext extractionCtx = extractionCtx("word");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "roadmap beta")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("brief.docx"), r.output());
        assertTrue(r.output().contains("Word roadmap beta"), r.output());
    }

    @Test void enabledXlsxExtractionGrepFindsKnownCellText() throws IOException {
        writeXlsx(workspace.resolve("budget.xlsx"), "Excel revenue gamma");
        ToolContext extractionCtx = extractionCtx("excel");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "revenue gamma")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("budget.xlsx"), r.output());
        assertTrue(r.output().contains("B2: Excel revenue gamma"), r.output());
    }

    @Test void enabledImageOcrGrepFindsConfiguredOcrText() throws IOException {
        Files.write(workspace.resolve("scan.png"), new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        Config cfg = extractionEnabled("image_ocr");
        family(cfg, "image_ocr").put("command", javaExecutable());
        family(cfg, "image_ocr").put("args", List.of(
                "-cp",
                System.getProperty("java.class.path"),
                FakeOcrCli.class.getName(),
                "{input}"));
        ToolContext extractionCtx = new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg);

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "visible text")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("scan.png"), r.output());
        assertTrue(r.output().contains("OCR fixture visible text"), r.output());
        assertFalse(r.output().contains("t267-token-should-not-appear"), r.output());
    }

    @Test void grep_does_not_leak_env_canary() throws IOException {
        Files.writeString(workspace.resolve(".env"), "TALOS_SECRET=DO_NOT_LEAK_T267_ENV\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "DO_NOT_LEAK_T267_ENV")), ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("DO_NOT_LEAK_T267_ENV"));
        assertTrue(r.output().contains("protected content") || r.output().contains("[redacted"));
    }

    @Test void grep_does_not_leak_env_local_canary() throws IOException {
        Files.writeString(workspace.resolve(".env.local"), "TALOS_SECRET=DO_NOT_LEAK_T267_ENV\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "DO_NOT_LEAK_T267_ENV")), ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("DO_NOT_LEAK_T267_ENV"));
        assertTrue(r.output().contains("protected content") || r.output().contains("[redacted"));
    }

    @Test void grep_does_not_leak_secrets_directory_canary() throws IOException {
        Files.createDirectories(workspace.resolve("secrets"));
        Files.writeString(workspace.resolve("secrets/private-notes.md"), "DO_NOT_LEAK_T267_SECRETS\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "DO_NOT_LEAK_T267_SECRETS")), ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("DO_NOT_LEAK_T267_SECRETS"));
        assertTrue(r.output().contains("protected content") || r.output().contains("[redacted"));
    }

    @Test void grep_does_not_leak_protected_directory_canary() throws IOException {
        Files.createDirectories(workspace.resolve("protected"));
        Files.writeString(workspace.resolve("protected/private-notes.md"), "DO_NOT_LEAK_T267_PROTECTED_DIR\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "DO_NOT_LEAK_T267_PROTECTED_DIR")), ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("DO_NOT_LEAK_T267_PROTECTED_DIR"));
        assertTrue(r.output().contains("protected content") || r.output().contains("[redacted"));
    }

    @Test void grep_redacts_secret_like_assignment_in_normal_file() throws IOException {
        Files.writeString(workspace.resolve("notes.md"), "API_TOKEN=t267-token-should-not-appear\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "API_TOKEN")), ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("API_TOKEN=[redacted]"));
        assertFalse(r.output().contains("t267-token-should-not-appear"));
    }

    @Test void grep_redacts_private_marker_in_normal_file() throws IOException {
        Files.writeString(workspace.resolve("notes.md"),
                "PRIVATE_MARKER = DO_NOT_LEAK_T267_PRIVATE_MARKER\nordinary searchable text\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "PRIVATE_MARKER")), ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("PRIVATE_MARKER=[redacted]"));
        assertFalse(r.output().contains("DO_NOT_LEAK_T267_PRIVATE_MARKER"));
    }

    @Test void unsupported_binary_grep_skips_and_reports_without_include_glob() throws IOException {
        Files.writeString(workspace.resolve("report.docx"), "budget canary in fake docx payload\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "budget")), ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("fake docx payload"));
        assertTrue(r.output().contains("Search was limited to searchable text files")
                || r.output().contains("Skipped unsupported"));
    }

    @Test void maxResultsRespected() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "public", "max_results", "1")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("1 match"));
    }

    @Test void skipsGitDirectory() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "git config")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("No matches"));
    }

    @Test void missingPatternParam() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of()), ctx);
        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test void invalidRegexReturnsError() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "[invalid", "regex", "true")), ctx);
        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test void matchesIncludeLineNumbers() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "class App", "include", "*.java")), ctx);
        assertTrue(r.success());
        // GrepTool format: "path:line | content"
        assertTrue(r.output().contains(":2 "), "Expected line number in output: " + r.output());
    }

    @Test void caseInsensitiveByDefault() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "PUBLIC CLASS")), ctx);
        assertTrue(r.success());
        assertFalse(r.output().contains("No matches"));
    }

    private ToolContext extractionCtx(String family) {
        return new ToolContext(workspace, new Sandbox(workspace, Map.of()), extractionEnabled(family));
    }

    private static Config extractionEnabled(String family) {
        Config cfg = new Config(null);
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        Map<String, Object> familyCfg = new LinkedHashMap<>();
        familyCfg.put("enabled", Boolean.TRUE);
        documentExtraction.put(family, familyCfg);
        cfg.data.put("document_extraction", documentExtraction);
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> family(Config cfg, String family) {
        return (Map<String, Object>) ((Map<String, Object>) cfg.data.get("document_extraction")).get(family);
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

    private static void writeEmptyPdf(Path path) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
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
