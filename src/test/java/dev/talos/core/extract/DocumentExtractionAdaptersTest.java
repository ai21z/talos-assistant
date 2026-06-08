package dev.talos.core.extract;

import dev.talos.core.Config;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentExtractionAdaptersTest {

    @Test
    void pdf_text_extraction_reads_known_text_and_page_provenance(@TempDir Path workspace) throws Exception {
        Path pdf = workspace.resolve("known.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText("Talos PDF fixture text");
                stream.endText();
            }
            document.save(pdf.toFile());
        }
        Config cfg = extractionEnabled("pdf");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(pdf, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertTrue(result.safeText().contains("Talos PDF fixture text"), result.safeText());
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("visual order")), result.warnings().toString());
        assertTrue(result.provenance().adapterName().contains("pdfbox"));
        assertRuntimeVersion(PDDocument.class, result.provenance().adapterVersion());
        assertFalse(result.provenance().adapterVersion().contains("3.0.6"), result.provenance().toString());
    }

    @Test
    void pdf_without_extractable_text_reports_ocr_required_not_success(@TempDir Path workspace) throws Exception {
        Path pdf = workspace.resolve("scanned-like.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        Config cfg = extractionEnabled("pdf");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(pdf, workspace));

        assertEquals(DocumentExtractionStatus.OCR_REQUIRED, result.status());
        assertTrue(result.safeText().isBlank(), result.safeText());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.code().equals("pdf-no-text") && w.message().contains("OCR")),
                result.warnings().toString());
        assertFalse(result.modelHandoffAllowed(), "no extracted text should be handed to the model as evidence");
    }

    @Test
    void encrypted_pdf_reports_encrypted_not_generic_failed(@TempDir Path workspace) throws Exception {
        Path pdf = workspace.resolve("locked.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            AccessPermission permissions = new AccessPermission();
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-password", "user-password", permissions);
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(pdf.toFile());
        }
        Config cfg = extractionEnabled("pdf");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(pdf, workspace));

        assertEquals(DocumentExtractionStatus.ENCRYPTED, result.status());
        assertTrue(result.safeText().isBlank(), result.safeText());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.code().equals("document-encrypted")
                        && w.message().contains("encrypted")),
                result.warnings().toString());
        assertFalse(result.modelHandoffAllowed(), "encrypted documents must not be handed to the model as evidence");
    }

    @Test
    void corrupt_pdf_reports_corrupt_not_generic_failed(@TempDir Path workspace) throws Exception {
        Path pdf = workspace.resolve("corrupt.pdf");
        Files.writeString(pdf, "%PDF-1.4\nnot a valid pdf body");
        Config cfg = extractionEnabled("pdf");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(pdf, workspace));

        assertEquals(DocumentExtractionStatus.CORRUPT, result.status(), result.warnings().toString());
        assertTrue(result.safeText().isBlank(), result.safeText());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.code().equals("document-corrupt")
                        && w.message().contains("corrupt")),
                result.warnings().toString());
        assertFalse(result.modelHandoffAllowed(), "corrupt PDFs must not be handed to the model as evidence");
    }

    @Test
    void docx_text_extraction_reads_known_paragraphs_and_tables(@TempDir Path workspace) throws Exception {
        Path docx = workspace.resolve("known.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Talos DOCX fixture paragraph");
            var table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("ColumnA");
            table.getRow(0).getCell(1).setText("ColumnB");
            try (OutputStream out = Files.newOutputStream(docx)) {
                doc.write(out);
            }
        }
        Config cfg = extractionEnabled("word");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(docx, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertTrue(result.safeText().contains("Talos DOCX fixture paragraph"), result.safeText());
        assertTrue(result.safeText().contains("ColumnA"), result.safeText());
        assertTrue(result.safeText().contains("ColumnB"), result.safeText());
        assertTrue(result.provenance().adapterName().contains("poi-docx"));
        assertRuntimeVersion(XWPFDocument.class, result.provenance().adapterVersion());
    }

    @Test
    void corrupt_docx_reports_corrupt_not_generic_failed(@TempDir Path workspace) throws Exception {
        Path docx = workspace.resolve("corrupt.docx");
        Files.writeString(docx, "not a real docx archive");
        Config cfg = extractionEnabled("word");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(docx, workspace));

        assertEquals(DocumentExtractionStatus.CORRUPT, result.status(), result.warnings().toString());
        assertTrue(result.safeText().isBlank(), result.safeText());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.code().equals("document-corrupt")
                        && w.message().contains("corrupt")),
                result.warnings().toString());
        assertFalse(result.modelHandoffAllowed(), "corrupt DOCX files must not be handed to the model as evidence");
    }

    @Test
    void xlsx_text_extraction_reads_known_cells_with_coordinates(@TempDir Path workspace) throws Exception {
        Path xlsx = workspace.resolve("known.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Budget");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("Category");
            row.createCell(1).setCellValue("Amount");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("Rent");
            data.createCell(1).setCellValue(1200);
            try (OutputStream out = Files.newOutputStream(xlsx)) {
                workbook.write(out);
            }
        }
        Config cfg = extractionEnabled("excel");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(xlsx, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertTrue(result.safeText().contains("Sheet: Budget"), result.safeText());
        assertTrue(result.safeText().contains("A1: Category"), result.safeText());
        assertTrue(result.safeText().contains("B2: 1200"), result.safeText());
        assertTrue(result.provenance().adapterName().contains("poi-xlsx"));
        assertRuntimeVersion(XSSFWorkbook.class, result.provenance().adapterVersion());
    }

    @Test
    void xlsx_text_extraction_skips_hidden_sheets_and_reports_limitation(@TempDir Path workspace) throws Exception {
        Path xlsx = workspace.resolve("hidden-sheet.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var visible = workbook.createSheet("VisibleBudget");
            visible.createRow(0).createCell(0).setCellValue("Visible public amount");
            var hidden = workbook.createSheet("HiddenPrivate");
            hidden.createRow(0).createCell(0).setCellValue("HIDDEN_PRIVATE_SHOULD_NOT_APPEAR");
            workbook.setSheetHidden(1, true);
            try (OutputStream out = Files.newOutputStream(xlsx)) {
                workbook.write(out);
            }
        }
        Config cfg = extractionEnabled("excel");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(xlsx, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertTrue(result.safeText().contains("Visible public amount"), result.safeText());
        assertFalse(result.safeText().contains("HIDDEN_PRIVATE_SHOULD_NOT_APPEAR"), result.safeText());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.code().equals("excel-hidden-sheets")
                        && w.message().contains("hidden sheet")),
                result.warnings().toString());
    }

    @Test
    void xlsx_formula_cells_report_formula_and_cached_value_policy(@TempDir Path workspace) throws Exception {
        Path xlsx = workspace.resolve("formula.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Budget");
            sheet.createRow(0).createCell(0).setCellValue(2);
            sheet.createRow(1).createCell(0).setCellValue(3);
            var formula = sheet.createRow(2).createCell(0);
            formula.setCellFormula("SUM(A1:A2)");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            try (OutputStream out = Files.newOutputStream(xlsx)) {
                workbook.write(out);
            }
        }
        Config cfg = extractionEnabled("excel");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(xlsx, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertTrue(result.safeText().contains("A3: [formula=SUM(A1:A2); cached=5]"), result.safeText());
        assertTrue(result.warnings().stream()
                        .anyMatch(w -> w.code().equals("xlsx-formula-policy")
                                && w.message().contains("not recalculated")),
                result.warnings().toString());
    }

    @Test
    void xlsx_large_output_reports_partial_with_truncation_warning(@TempDir Path workspace) throws Exception {
        Path xlsx = workspace.resolve("large.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Large");
            for (int i = 0; i < 180; i++) {
                sheet.createRow(i).createCell(0).setCellValue(deterministicPayload(i, 420));
            }
            try (OutputStream out = Files.newOutputStream(xlsx)) {
                workbook.write(out);
            }
        }
        Config cfg = extractionEnabled("excel");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(xlsx, workspace));

        assertEquals(DocumentExtractionStatus.PARTIAL, result.status());
        assertTrue(result.safeText().length() <= 64_000, "safe text should be capped");
        assertTrue(result.warnings().stream()
                        .anyMatch(w -> w.code().equals("extraction-truncated")
                                && w.message().contains("truncated")),
                result.warnings().toString());
    }

    private static String deterministicPayload(int row, int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        long state = 0x9E3779B97F4A7C15L ^ row;
        StringBuilder out = new StringBuilder(length + 24);
        out.append("row-").append(row).append('-');
        for (int i = out.length(); i < length; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            int index = (int) Math.floorMod(state, alphabet.length());
            out.append(alphabet.charAt(index));
        }
        return out.toString();
    }

    @Test
    void corrupt_xlsx_reports_corrupt_not_generic_failed(@TempDir Path workspace) throws Exception {
        Path xlsx = workspace.resolve("corrupt.xlsx");
        Files.writeString(xlsx, "not a real xlsx workbook");
        Config cfg = extractionEnabled("excel");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(xlsx, workspace));

        assertEquals(DocumentExtractionStatus.CORRUPT, result.status());
        assertTrue(result.safeText().isBlank(), result.safeText());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.code().equals("document-corrupt")
                        && w.message().contains("corrupt")),
                result.warnings().toString());
        assertFalse(result.modelHandoffAllowed(), "corrupt documents must not be handed to the model as evidence");
    }

    @Test
    void xls_text_extraction_reads_known_cells_with_coordinates(@TempDir Path workspace) throws Exception {
        Path xls = workspace.resolve("known.xls");
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet("Budget");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("Category");
            row.createCell(1).setCellValue("Amount");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("Rent");
            data.createCell(1).setCellValue(1200);
            try (OutputStream out = Files.newOutputStream(xls)) {
                workbook.write(out);
            }
        }
        Config cfg = extractionEnabled("excel");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(xls, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertTrue(result.safeText().contains("Sheet: Budget"), result.safeText());
        assertTrue(result.safeText().contains("A1: Category"), result.safeText());
        assertTrue(result.safeText().contains("B2: 1200"), result.safeText());
        assertTrue(result.provenance().adapterName().contains("poi-xls"));
        assertRuntimeVersion(HSSFWorkbook.class, result.provenance().adapterVersion());
    }

    @Test
    void xls_text_extraction_skips_hidden_sheets_and_reports_limitation(@TempDir Path workspace) throws Exception {
        Path xls = workspace.resolve("hidden-sheet.xls");
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            var visible = workbook.createSheet("VisibleBudget");
            visible.createRow(0).createCell(0).setCellValue("Visible public xls amount");
            var hidden = workbook.createSheet("HiddenPrivate");
            hidden.createRow(0).createCell(0).setCellValue("HIDDEN_XLS_PRIVATE_SHOULD_NOT_APPEAR");
            workbook.setSheetHidden(1, true);
            try (OutputStream out = Files.newOutputStream(xls)) {
                workbook.write(out);
            }
        }
        Config cfg = extractionEnabled("excel");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(xls, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertTrue(result.safeText().contains("Visible public xls amount"), result.safeText());
        assertFalse(result.safeText().contains("HIDDEN_XLS_PRIVATE_SHOULD_NOT_APPEAR"), result.safeText());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.code().equals("excel-hidden-sheets")
                        && w.message().contains("hidden sheet")),
                result.warnings().toString());
    }

    @Test
    void xls_formula_cells_report_formula_and_cached_value_policy(@TempDir Path workspace) throws Exception {
        Path xls = workspace.resolve("formula.xls");
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet("Budget");
            sheet.createRow(0).createCell(0).setCellValue(4);
            sheet.createRow(1).createCell(0).setCellValue(6);
            var formula = sheet.createRow(2).createCell(0);
            formula.setCellFormula("SUM(A1:A2)");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            try (OutputStream out = Files.newOutputStream(xls)) {
                workbook.write(out);
            }
        }
        Config cfg = extractionEnabled("excel");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(xls, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertTrue(result.safeText().contains("A3: [formula=SUM(A1:A2); cached=10]"), result.safeText());
        assertTrue(result.warnings().stream()
                        .anyMatch(w -> w.code().equals("xls-formula-policy")
                                && w.message().contains("not recalculated")),
                result.warnings().toString());
    }

    @Test
    void corrupt_xls_reports_corrupt_not_generic_failed(@TempDir Path workspace) throws Exception {
        Path xls = workspace.resolve("corrupt.xls");
        Files.writeString(xls, "not a real xls workbook");
        Config cfg = extractionEnabled("excel");

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(xls, workspace));

        assertEquals(DocumentExtractionStatus.CORRUPT, result.status(), result.warnings().toString());
        assertTrue(result.safeText().isBlank(), result.safeText());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.code().equals("document-corrupt")
                        && w.message().contains("corrupt")),
                result.warnings().toString());
        assertFalse(result.modelHandoffAllowed(), "corrupt XLS files must not be handed to the model as evidence");
    }


    @Test
    void image_ocr_uses_configured_local_ocr_command_and_redacts_output(@TempDir Path workspace) throws Exception {
        Path image = workspace.resolve("scan.png");
        Files.write(image, new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        Config cfg = extractionEnabled("image_ocr");
        Map<String, Object> imageCfg = family(cfg, "image_ocr");
        imageCfg.put("command", javaExecutable());
        imageCfg.put("args", List.of(
                "-cp",
                System.getProperty("java.class.path"),
                FakeOcrCli.class.getName(),
                "{input}"));

        DocumentExtractionResult result = new DocumentExtractionService(cfg)
                .extract(DocumentExtractionRequest.read(image, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertTrue(result.safeText().contains("OCR fixture visible text"), result.safeText());
        assertFalse(result.safeText().contains("t267-token-should-not-appear"), result.safeText());
        assertTrue(result.safeText().contains("API_TOKEN=[redacted]"), result.safeText());
        assertTrue(result.provenance().adapterName().contains("tesseract"));
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

    private static void assertRuntimeVersion(Class<?> type, String observed) {
        String runtimeVersion = type.getPackage().getImplementationVersion();
        if (runtimeVersion != null && !runtimeVersion.isBlank()) {
            assertEquals(runtimeVersion, observed);
        } else {
            assertFalse(observed == null || observed.isBlank(), "adapter version should not be blank");
        }
    }
}
