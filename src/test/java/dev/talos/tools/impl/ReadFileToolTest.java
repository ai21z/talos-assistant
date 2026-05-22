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

/**
 * Tests for {@link ReadFileTool}.
 */
class ReadFileToolTest {

    @TempDir Path workspace;
    private ReadFileTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        tool = new ReadFileTool();
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ctx = new ToolContext(workspace, sandbox, new Config());

        // Create test files
        Files.writeString(workspace.resolve("hello.txt"), "line 1\nline 2\nline 3\nline 4\nline 5\n");
        Files.createDirectories(workspace.resolve("sub"));
        Files.writeString(workspace.resolve("sub/nested.txt"), "nested content");
    }

    @Test
    void descriptor() {
        assertEquals("talos.read_file", tool.name());
        assertNotNull(tool.descriptor().parametersSchema());
    }

    @Test
    void readFullFile() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertNotNull(r.output());
        assertTrue(r.output().contains("line 1"));
        assertTrue(r.output().contains("line 5"));
    }

    @Test
    void trimsAccidentalPathWhitespaceWhenCanonicalFileExists() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", " hello.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("line 1"));
    }

    @Test
    void doesNotTrimWhitespaceWhenNeitherRawNorTrimmedPathExists() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", " missing.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.NOT_FOUND, r.error().code());
        assertTrue(r.errorMessage().contains(" missing.txt"), r.errorMessage());
    }

    @Test
    void keepsExactWhitespacePathWhenItExists() throws IOException {
        Path exact = workspace.resolve(" hello.txt");
        try {
            Files.writeString(exact, "exact whitespace path\n");
        } catch (IOException | RuntimeException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "platform did not allow leading-space filename: " + e.getMessage());
        }

        ToolCall call = new ToolCall("talos.read_file", Map.of("path", " hello.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("exact whitespace path"), r.output());
        assertFalse(r.output().contains("line 1"), r.output());
    }

    @Test
    void readNestedFile() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "sub/nested.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("nested content"));
    }

    @Test
    void readWithOffset() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt", "offset", "3"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("1 | line 1"));
        assertTrue(r.output().contains("3 | line 3"));
    }

    @Test
    void readWithMaxLines() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt", "max_lines", "2"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("1 | line 1"));
        assertTrue(r.output().contains("2 | line 2"));
        assertTrue(r.output().contains("more lines"));
    }

    @Test
    void fileNotFound() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "nonexistent.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.NOT_FOUND, r.error().code());
    }

    @Test
    void missingPathParam() {
        ToolCall call = new ToolCall("talos.read_file", Map.of());
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test
    void pathEscapesWorkspace() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "../../etc/passwd"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("not allowed"));
    }

    @Test
    void directoryNotAllowed() throws IOException {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "sub"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("directory"));
    }

    @Test
    void malformedPdfReportsExtractionFailureWithoutFabrication() throws IOException {
        Files.writeString(workspace.resolve("sample.pdf"), "%PDF-1.7 fake test payload");

        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "sample.pdf"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.UNSUPPORTED_FORMAT, r.error().code());
        assertTrue(r.errorMessage().contains("Cannot extract text from sample.pdf"), r.errorMessage());
        assertTrue(r.errorMessage().contains("PDF extraction failed"), r.errorMessage());
        assertFalse(r.errorMessage().contains("fake test payload"), r.errorMessage());
    }

    @Test
    void enabledPdfExtractionReadsKnownText() throws IOException {
        writePdf(workspace.resolve("sample.pdf"), "Talos read-file PDF text");
        Config cfg = extractionEnabled("pdf");
        ToolContext extractionCtx = new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg);

        ToolResult r = tool.execute(new ToolCall("talos.read_file", Map.of("path", "sample.pdf")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("Talos read-file PDF text"), r.output());
        assertTrue(r.output().contains("Extracted document text"), r.output());
        assertTrue(r.output().contains("PDF text extraction may not match visual order"), r.output());
    }

    @Test
    void enabledPdfExtractionReportsOcrRequiredForNoTextPdf() throws IOException {
        writeEmptyPdf(workspace.resolve("scan.pdf"));
        Config cfg = extractionEnabled("pdf");
        ToolContext extractionCtx = new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg);

        ToolResult r = tool.execute(new ToolCall("talos.read_file", Map.of("path", "scan.pdf")), extractionCtx);

        assertFalse(r.success());
        assertEquals(ToolError.UNSUPPORTED_FORMAT, r.error().code());
        assertTrue(r.errorMessage().contains("OCR_REQUIRED"), r.errorMessage());
        assertTrue(r.errorMessage().contains("OCR"), r.errorMessage());
        assertFalse(r.errorMessage().contains("Extracted document text"), r.errorMessage());
    }

    @Test
    void enabledDocxExtractionReadsKnownText() throws IOException {
        writeDocx(workspace.resolve("sample.docx"), "Talos read-file DOCX text");
        Config cfg = extractionEnabled("word");
        ToolContext extractionCtx = new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg);

        ToolResult r = tool.execute(new ToolCall("talos.read_file", Map.of("path", "sample.docx")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("Talos read-file DOCX text"), r.output());
        assertTrue(r.output().contains("DOCX extraction is text-oriented"), r.output());
    }

    @Test
    void privateModeDocxSendToModelStillCarriesPrivateDocumentMetadata() throws IOException {
        writeDocx(workspace.resolve("private-notes.docx"), "Family medical note");
        Config cfg = extractionEnabled("word");
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "document_extraction", new LinkedHashMap<>(Map.of(
                        "allow_send_to_model", Boolean.TRUE,
                        "persist_raw_artifacts", Boolean.FALSE,
                        "allow_rag_indexing", Boolean.FALSE)))));
        ToolContext extractionCtx = new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg);

        ToolResult r = tool.execute(new ToolCall("talos.read_file", Map.of("path", "private-notes.docx")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.contentMetadata().modelHandoffAllowed());
        assertEquals(ToolContentMetadata.ContentPrivacyClass.PRIVATE_DOCUMENT_EXTRACTED_TEXT,
                r.contentMetadata().privacyClass());
    }

    @Test
    void extractedDocumentMetadataUsesSinglePrivateDocumentDecision() throws IOException {
        String source = Files.readString(Path.of("src/main/java/dev/talos/tools/impl/ReadFileTool.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertTrue(source.contains("import dev.talos.core.privacy.PrivateDocumentContentPolicy;"), source);
        assertFalse(source.contains("import dev.talos.runtime.policy.PrivateDocumentPolicy;"), source);
        assertTrue(source.contains("PrivateDocumentContentPolicy.decide("), source);
        assertFalse(source.contains("PrivateDocumentPolicy.privateDocumentContent("), source);
        assertFalse(source.contains("PrivateDocumentPolicy.rawArtifactPersistenceAllowed("), source);
        assertFalse(source.contains("PrivateDocumentPolicy.ragIndexAllowed("), source);
        assertFalse(source.contains("PrivateDocumentPolicy.decisionReason("), source);
        assertFalse(baseline.contains(
                "tools-no-runtime|src/main/java/dev/talos/tools/impl/ReadFileTool.java|"
                        + "dev.talos.runtime.policy.PrivateDocumentPolicy"), baseline);
    }

    @Test
    void enabledXlsxExtractionReadsKnownCells() throws IOException {
        writeXlsx(workspace.resolve("sample.xlsx"), "Talos read-file XLSX text");
        Config cfg = extractionEnabled("excel");
        ToolContext extractionCtx = new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg);

        ToolResult r = tool.execute(new ToolCall("talos.read_file", Map.of("path", "sample.xlsx")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("Sheet: Budget"), r.output());
        assertTrue(r.output().contains("B2: Talos read-file XLSX text"), r.output());
        assertTrue(r.output().contains("formulas are not recalculated"), r.output());
    }

    @Test
    void enabledImageOcrReadsConfiguredLocalCommandOutput() throws IOException {
        Files.write(workspace.resolve("scan.png"), new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        Config cfg = extractionEnabled("image_ocr");
        family(cfg, "image_ocr").put("command", javaExecutable());
        family(cfg, "image_ocr").put("args", List.of(
                "-cp",
                System.getProperty("java.class.path"),
                FakeOcrCli.class.getName(),
                "{input}"));
        ToolContext extractionCtx = new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg);

        ToolResult r = tool.execute(new ToolCall("talos.read_file", Map.of("path", "scan.png")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("OCR fixture visible text"), r.output());
        assertTrue(r.output().contains("API_TOKEN=[redacted]"), r.output());
        assertFalse(r.output().contains("t267-token-should-not-appear"), r.output());
    }

    @Test
    void nullContextFails() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt"));
        ToolResult r = tool.execute(call, null);

        assertFalse(r.success());
        assertEquals(ToolError.INTERNAL_ERROR, r.error().code());
    }

    @Test
    void lineNumbersAreCorrect() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        // Lines should be numbered 1-based with " | " separator
        assertTrue(r.output().contains("1 | line 1"));
        assertTrue(r.output().contains("5 | line 5"));
    }

    // ── E2: char-based output truncation ────────────────────────────

    @Test
    void smallFileIsNotTruncated() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("truncated"), "Small file should not be truncated");
    }

    @Test
    void largeFileIsTruncatedAtCharLimit() throws IOException {
        // Build a file large enough to exceed MAX_OUTPUT_CHARS (16K)
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            sb.append("This is a reasonably long line of content number ").append(i)
              .append(" used to build a file that exceeds the character cap.\n");
        }
        Files.writeString(workspace.resolve("large.txt"), sb.toString());

        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "large.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("truncated at 16K"), "Should truncate with message, got: " + r.output().substring(0, 100));
        assertTrue(r.output().contains("talos.grep"), "Truncation message should suggest talos.grep");
        assertTrue(r.output().length() <= ReadFileTool.MAX_OUTPUT_CHARS + 200,
                "Output should not greatly exceed the cap");
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

