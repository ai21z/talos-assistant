package dev.talos.core.index;

import dev.talos.core.Config;
import dev.talos.core.rag.RagService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexerPrivateDocumentPolicyTest {

    private static final String PRIVATE_PDF_FACT = "Eleni Nikolaou lease clause";
    private static final String PRIVATE_DOCX_FACT = "Patient Name Eleni Nikolaou";
    private static final String PRIVATE_XLSX_FACT = "Family invoice total 1837.42 EUR";
    private static final String ALLOWED_DOCX_FACT = "Clinic appointment reference Alpha Safe Index";

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
    void privateMode_ragEnabled_privateDocRagIndexingFalse_pdfNotIndexed() throws Exception {
        writePdf(workspace.resolve("lease.pdf"), PRIVATE_PDF_FACT);
        Config cfg = privateRagConfig("pdf", "**/*.pdf", false);
        Indexer indexer = new Indexer(cfg);
        lastIndexDir = indexer.indexDirFor(workspace);

        indexer.index(workspace, true);

        String indexedText = allIndexedText(indexer);
        assertFalse(indexedText.contains(PRIVATE_PDF_FACT), indexedText);
        assertTrue(indexer.getLastRunStats().getFilesSkippedByPrivacy() >= 1,
                indexer.getLastRunStats().getSummary());
    }

    @Test
    void privateMode_ragEnabled_privateDocRagIndexingFalse_docxNotIndexed() throws Exception {
        writeDocx(workspace.resolve("medical-notes.docx"), PRIVATE_DOCX_FACT);
        Config cfg = privateRagConfig("word", "**/*.docx", false);
        Indexer indexer = new Indexer(cfg);
        lastIndexDir = indexer.indexDirFor(workspace);

        indexer.index(workspace, true);

        String indexedText = allIndexedText(indexer);
        assertFalse(indexedText.contains(PRIVATE_DOCX_FACT), indexedText);
        assertTrue(indexer.getLastRunStats().getFilesSkippedByPrivacy() >= 1,
                indexer.getLastRunStats().getSummary());
    }

    @Test
    void privateMode_ragEnabled_privateDocRagIndexingFalse_xlsxNotIndexed() throws Exception {
        writeXlsx(workspace.resolve("family-budget.xlsx"), PRIVATE_XLSX_FACT);
        Config cfg = privateRagConfig("excel", "**/*.xlsx", false);
        Indexer indexer = new Indexer(cfg);
        lastIndexDir = indexer.indexDirFor(workspace);

        indexer.index(workspace, true);

        String indexedText = allIndexedText(indexer);
        assertFalse(indexedText.contains(PRIVATE_XLSX_FACT), indexedText);
        assertTrue(indexer.getLastRunStats().getFilesSkippedByPrivacy() >= 1,
                indexer.getLastRunStats().getSummary());
    }

    @Test
    void privateMode_ragEnabled_privateDocRagIndexingTrue_docxIndexed() throws Exception {
        writeDocx(workspace.resolve("medical-notes.docx"), ALLOWED_DOCX_FACT);
        Config cfg = privateRagConfig("word", "**/*.docx", true);
        Indexer indexer = new Indexer(cfg);
        lastIndexDir = indexer.indexDirFor(workspace);

        indexer.index(workspace, true);

        String indexedText = allIndexedText(indexer);
        assertTrue(indexedText.contains(ALLOWED_DOCX_FACT), indexedText);
    }

    @Test
    void privateDocumentRagIndexingPolicyChangeMarksOldIndexDirtyAndRebuildsWithoutPrivateChunks() throws Exception {
        writeDocx(workspace.resolve("medical-notes.docx"), ALLOWED_DOCX_FACT);
        Config allowed = privateRagConfig("word", "**/*.docx", true);
        Indexer allowedIndexer = new Indexer(allowed);
        lastIndexDir = allowedIndexer.indexDirFor(workspace);
        allowedIndexer.index(workspace, true);
        assertTrue(allowedIndexer.isPolicyMetadataCurrent(workspace));
        assertTrue(allIndexedText(allowedIndexer).contains(ALLOWED_DOCX_FACT));

        Config blocked = privateRagConfig("word", "**/*.docx", false);
        Indexer blockedIndexer = new Indexer(blocked);

        assertFalse(blockedIndexer.isPolicyMetadataCurrent(workspace),
                "privacy.document_extraction.allow_rag_indexing must be part of index freshness");

        RagService.Prepared prepared = new RagService(blocked).prepare(workspace, "Alpha Safe Index", 5);

        String rendered = prepared.snippets().toString();
        assertFalse(rendered.contains(ALLOWED_DOCX_FACT), rendered);
        assertTrue(blockedIndexer.isPolicyMetadataCurrent(workspace));
    }

    private String allIndexedText(Indexer indexer) {
        try (LuceneStore store = new LuceneStore(indexer.indexDirFor(workspace), 0)) {
            StringBuilder out = new StringBuilder();
            for (var hit : store.matchAll(50)) {
                String text = store.getTextByPath(hit.path());
                if (text != null) {
                    out.append(text).append('\n');
                }
            }
            return out.toString();
        }
    }

    private static Config privateRagConfig(String family, String includeGlob, boolean allowPrivateDocumentRagIndexing) {
        Config cfg = new Config(null);
        cfg.data.put("embed", new LinkedHashMap<>(Map.of(
                "provider", "disabled",
                "model", "disabled")));
        cfg.data.put("net", new LinkedHashMap<>(Map.of("enabled", false)));
        ProtectedReadScopePolicy.setPrivateMode(cfg, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> rag = new LinkedHashMap<>((Map<String, Object>) cfg.data.get("rag"));
        rag.put("includes", new ArrayList<>(List.of(includeGlob)));
        rag.put("excludes", new ArrayList<>(List.of(
                "**/.env", "**/.env.*", "**/*.env",
                "**/secrets/**", "**/protected/**")));
        rag.put("vectors", new LinkedHashMap<>(Map.of("enabled", false)));
        cfg.data.put("rag", rag);

        @SuppressWarnings("unchecked")
        Map<String, Object> privacy = new LinkedHashMap<>((Map<String, Object>) cfg.data.get("privacy"));
        privacy.put("mode", "private");
        privacy.put("rag", new LinkedHashMap<>(Map.of("enabled_in_private_mode", true)));
        privacy.put("document_extraction", new LinkedHashMap<>(Map.of(
                "allow_send_to_model", false,
                "persist_raw_artifacts", false,
                "allow_rag_indexing", allowPrivateDocumentRagIndexing)));
        cfg.data.put("privacy", privacy);

        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        documentExtraction.put(family, new LinkedHashMap<>(Map.of("enabled", Boolean.TRUE)));
        cfg.data.put("document_extraction", documentExtraction);
        return cfg;
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
            var sheet = workbook.createSheet("Private");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(text);
            try (var out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
