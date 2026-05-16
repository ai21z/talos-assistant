package dev.talos.core.rag;

import dev.talos.core.Config;
import dev.talos.core.extract.FakeOcrCli;
import dev.talos.core.index.Indexer;
import dev.talos.core.index.LuceneStore;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagDirtyIndexIntegrationTest {

    @TempDir
    Path workspace;

    private Path lastIndexDir;

    @AfterEach
    void cleanIndexDir() throws IOException {
        if (lastIndexDir != null) {
            deleteRecursively(lastIndexDir);
        }
    }

    @Test
    void rag_missing_metadata_triggers_rebuild_and_removes_old_protected_chunks() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "public budget text\n");
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_RAG_DIRTY\n");
        Config cfg = safeRagConfig();
        Indexer indexer = new Indexer(cfg);
        seedDirtyCanaryIndex(indexer, "API_TOKEN=FILE_DISCOVERED_CANARY_RAG_DIRTY");

        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "FILE_DISCOVERED_CANARY_RAG_DIRTY", 5);

        String rendered = prepared.snippets().toString();
        assertFalse(rendered.contains("FILE_DISCOVERED_CANARY_RAG_DIRTY"), rendered);
        assertTrue(indexer.isPolicyMetadataCurrent(workspace));
        try (LuceneStore store = new LuceneStore(indexer.indexDirFor(workspace), 0)) {
            assertNull(store.getTextByPath(".env#0"));
        }
    }

    @Test
    void rag_config_hash_change_triggers_rebuild() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "public alpha text\n");
        Config first = safeRagConfig();
        Indexer firstIndexer = new Indexer(first);
        firstIndexer.index(workspace, true);

        Config changed = safeRagConfig();
        rag(changed).put("top_k", 9);
        Indexer changedIndexer = new Indexer(changed);
        lastIndexDir = changedIndexer.indexDirFor(workspace);
        assertFalse(changedIndexer.isPolicyMetadataCurrent(workspace));

        new RagService(changed).prepare(workspace, "public", 1);

        assertTrue(changedIndexer.isPolicyMetadataCurrent(workspace));
    }

    @Test
    void rag_private_mode_disables_lazy_indexing_by_default() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "public text\n");
        Config cfg = safeRagConfig();
        ProtectedReadScopePolicy.setPrivateMode(cfg, true);

        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "public", 1);

        assertTrue(prepared.hasError());
        assertTrue(prepared.errorReason().contains("disabled in private mode"), prepared.errorReason());
    }

    @Test
    void rag_indexes_enabled_pdf_extraction_text_for_retrieval() throws Exception {
        writePdf(workspace.resolve("report.pdf"), "RAG PDF budget alpha");
        Config cfg = safeRagConfig();
        enableDocumentExtraction(cfg, "pdf");
        rag(cfg).put("includes", new ArrayList<>(List.of("**/*.pdf")));
        rag(cfg).put("excludes", new ArrayList<>(List.of(
                "**/.env", "**/.env.*", "**/*.env",
                "**/secrets/**", "**/protected/**")));
        Indexer indexer = new Indexer(cfg);
        lastIndexDir = indexer.indexDirFor(workspace);

        indexer.index(workspace, true);
        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "budget alpha", 3);

        String rendered = prepared.snippets().toString();
        assertTrue(rendered.contains("RAG PDF budget alpha"), rendered);
        assertTrue(rendered.contains("report.pdf"), rendered);
    }

    @Test
    void rag_indexes_enabled_docx_extraction_text_for_retrieval() throws Exception {
        writeDocx(workspace.resolve("brief.docx"), "RAG DOCX roadmap beta");
        Config cfg = extractionRagConfig("word", "**/*.docx");
        Indexer indexer = new Indexer(cfg);
        lastIndexDir = indexer.indexDirFor(workspace);

        indexer.index(workspace, true);
        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "roadmap beta", 3);

        String rendered = prepared.snippets().toString();
        assertTrue(rendered.contains("RAG DOCX roadmap beta"), rendered);
        assertTrue(rendered.contains("brief.docx"), rendered);
    }

    @Test
    void rag_indexes_enabled_xlsx_extraction_text_for_retrieval() throws Exception {
        writeXlsx(workspace.resolve("budget.xlsx"), "RAG XLSX revenue gamma");
        Config cfg = extractionRagConfig("excel", "**/*.xlsx");
        Indexer indexer = new Indexer(cfg);
        lastIndexDir = indexer.indexDirFor(workspace);

        indexer.index(workspace, true);
        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "revenue gamma", 3);

        String rendered = prepared.snippets().toString();
        assertTrue(rendered.contains("B2: RAG XLSX revenue gamma"), rendered);
        assertTrue(rendered.contains("budget.xlsx"), rendered);
    }

    @Test
    void rag_indexes_enabled_image_ocr_text_for_retrieval() throws Exception {
        Files.write(workspace.resolve("scan.png"), new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        Config cfg = extractionRagConfig("image_ocr", "**/*.png");
        Map<String, Object> ocr = family(cfg, "image_ocr");
        ocr.put("command", javaExecutable());
        ocr.put("args", List.of(
                "-cp",
                System.getProperty("java.class.path"),
                FakeOcrCli.class.getName(),
                "{input}"));
        Indexer indexer = new Indexer(cfg);
        lastIndexDir = indexer.indexDirFor(workspace);

        indexer.index(workspace, true);
        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "visible text", 3);

        String rendered = prepared.snippets().toString();
        assertTrue(rendered.contains("OCR fixture visible text"), rendered);
        assertFalse(rendered.contains("t267-token-should-not-appear"), rendered);
        assertTrue(rendered.contains("scan.png"), rendered);
    }

    private void seedDirtyCanaryIndex(Indexer indexer, String text) throws Exception {
        Path indexDir = indexer.indexDirFor(workspace);
        lastIndexDir = indexDir;
        deleteRecursively(indexDir);
        Files.createDirectories(indexDir);
        try (LuceneStore store = new LuceneStore(indexDir, 0)) {
            store.add(".env#0", text, null);
            store.commit();
        }
    }

    private static Config safeRagConfig() {
        Config cfg = new Config(null);
        cfg.data.put("embed", new LinkedHashMap<>(Map.of(
                "provider", "disabled",
                "model", "disabled")));
        rag(cfg).put("vectors", new LinkedHashMap<>(Map.of("enabled", false)));
        cfg.data.put("net", new LinkedHashMap<>(Map.of("enabled", false)));
        return cfg;
    }

    private static void enableDocumentExtraction(Config cfg, String family) {
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        Map<String, Object> familyCfg = new LinkedHashMap<>();
        familyCfg.put("enabled", Boolean.TRUE);
        documentExtraction.put(family, familyCfg);
        cfg.data.put("document_extraction", documentExtraction);
    }

    private static Config extractionRagConfig(String family, String includeGlob) {
        Config cfg = safeRagConfig();
        enableDocumentExtraction(cfg, family);
        rag(cfg).put("includes", new ArrayList<>(List.of(includeGlob)));
        rag(cfg).put("excludes", new ArrayList<>(List.of(
                "**/.env", "**/.env.*", "**/*.env",
                "**/secrets/**", "**/protected/**")));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rag(Config cfg) {
        Map<String, Object> existing = (Map<String, Object>) cfg.data.get("rag");
        Map<String, Object> copy = new LinkedHashMap<>(existing);
        cfg.data.put("rag", copy);
        return copy;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
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
