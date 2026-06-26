package dev.talos.core.extract;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.core.privacy.PrivateDocumentContentPolicy;
import dev.talos.safety.ProtectedContentSanitizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class DocumentExtractionService {
    public static final String EXTRACTION_POLICY_VERSION = "document-extraction-policy-v1";
    private static final int MAX_EXTRACTED_CHARS = 64_000;
    private static final long DEFAULT_OCR_TIMEOUT_MS = 10_000L;

    private final Config cfg;

    public DocumentExtractionService(Config cfg) {
        this.cfg = cfg == null ? new Config(null) : cfg;
    }

    public DocumentExtractionResult extract(DocumentExtractionRequest request) {
        Objects.requireNonNull(request, "request");
        Path path = request.path();
        String sourcePath = relativePath(request.workspaceRoot(), path);
        FileCapabilityPolicy.FormatInfo info = FileCapabilityPolicy.describe(path, cfg).orElse(null);
        if (info != null && info.capability() != FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_ENABLED
                && info.capability() != FileCapabilityPolicy.Capability.OCR_ENABLED) {
            return unsupportedResult(request, sourcePath, info);
        }
        if (info != null && info.capability() == FileCapabilityPolicy.Capability.OCR_ENABLED) {
            return extractOcr(request, sourcePath, info);
        }
        if (info != null && info.capability() == FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_ENABLED) {
            return extractKnownDocument(request, sourcePath, info);
        }

        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            String safe = ProtectedContentSanitizer.sanitizeText(raw);
            return new DocumentExtractionResult(
                    sourcePath,
                    request.intent(),
                    info == null ? FileCapabilityPolicy.Capability.SUPPORTED_TEXT : info.capability(),
                    DocumentExtractionStatus.SUCCESS,
                    safe,
                    List.of(),
                    provenance(sourcePath, "text", "builtin"),
                    PrivateDocumentContentPolicy.modelHandoffAllowed(cfg, request, info));
        } catch (IOException | RuntimeException e) {
            return new DocumentExtractionResult(
                    sourcePath,
                    request.intent(),
                    info == null ? FileCapabilityPolicy.Capability.UNKNOWN_TEXT_ATTEMPT_ALLOWED : info.capability(),
                    DocumentExtractionStatus.FAILED,
                    "",
                List.of(new DocumentExtractionWarning("read-failed",
                        "Text extraction failed: " + ProtectedContentSanitizer.sanitizeText(e.getClass().getSimpleName()))),
                    provenance(sourcePath, "text", "builtin"),
                    false);
        }
    }

    private DocumentExtractionResult extractKnownDocument(
            DocumentExtractionRequest request,
            String sourcePath,
            FileCapabilityPolicy.FormatInfo info) {
        try {
            String ext = info.extension();
            if ("pdf".equals(ext)) {
                String text = extractPdf(request.path());
                if (text == null || text.isBlank()) {
                    return statusOnly(request, sourcePath, info,
                            DocumentExtractionStatus.OCR_REQUIRED,
                            new DocumentExtractionWarning("pdf-no-text",
                                    "No text was extracted from this PDF. It may be scanned or image-only; OCR is required before Talos can rely on its contents."));
                }
                return extracted(request, sourcePath, info, text,
                        List.of(new DocumentExtractionWarning("pdf-text-order",
                                "PDF text extraction may not match visual order or layout.")),
                        "pdfbox", implementationVersion(PDDocument.class, "unknown"));
            }
            if ("docx".equals(ext)) {
                return extracted(request, sourcePath, info, extractDocx(request.path()),
                        List.of(new DocumentExtractionWarning("docx-partial-structures",
                                "DOCX extraction is text-oriented; layout, comments, tracked changes, and embedded objects may be partial or omitted.")),
                        "poi-docx", implementationVersion(XWPFDocument.class, "unknown"));
            }
            if ("xlsx".equals(ext)) {
                WorkbookExtraction workbook = extractXlsx(request.path());
                return extracted(request, sourcePath, info, workbook.text(),
                        excelWarnings("xlsx-formula-policy",
                                "XLSX extraction reports visible cells and cached display values; formulas are not recalculated.",
                                workbook.hiddenSheetsSkipped()),
                        "poi-xlsx", implementationVersion(XSSFWorkbook.class, "unknown"));
            }
            if ("xls".equals(ext)) {
                WorkbookExtraction workbook = extractXls(request.path());
                return extracted(request, sourcePath, info, workbook.text(),
                        excelWarnings("xls-formula-policy",
                                "XLS extraction reports visible cells and cached display values; formulas are not recalculated.",
                                workbook.hiddenSheetsSkipped()),
                        "poi-xls", implementationVersion(HSSFWorkbook.class, "unknown"));
            }
            return statusOnly(request, sourcePath, info,
                    DocumentExtractionStatus.UNSUPPORTED_DISABLED,
                    new DocumentExtractionWarning("adapter-missing",
                            info.label() + " is marked extractable, but no adapter is available."));
        } catch (Exception e) {
            DocumentExtractionStatus status = classifyExtractionFailure(e);
            DocumentExtractionWarning warning = extractionFailureWarning(info, status);
            return new DocumentExtractionResult(
                    sourcePath,
                    request.intent(),
                    info.capability(),
                    status,
                    "",
                    List.of(warning),
                    provenance(sourcePath, "document", "builtin"),
                    false);
        }
    }

    private DocumentExtractionResult extractOcr(
            DocumentExtractionRequest request,
            String sourcePath,
            FileCapabilityPolicy.FormatInfo info) {
        Map<String, Object> ocr = familyConfig("image_ocr");
        String command = String.valueOf(ocr.getOrDefault("command", "")).strip();
        if (command.isBlank()) {
            return statusOnly(request, sourcePath, info,
                    DocumentExtractionStatus.OCR_UNAVAILABLE,
                    new DocumentExtractionWarning("ocr-unavailable",
                            "OCR is enabled by policy, but no local OCR command is configured."));
        }
        List<String> args = CfgUtil.strList(ocr.get("args"));
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        if (args.isEmpty()) {
            commandLine.add(request.path().toString());
            commandLine.add("stdout");
        } else {
            for (String arg : args) {
                commandLine.add(arg.replace("{input}", request.path().toString()));
            }
        }
        long timeoutMs = CfgUtil.longAt(ocr, "timeout_ms", DEFAULT_OCR_TIMEOUT_MS);
        try {
            ProcessBuilder builder = new ProcessBuilder(commandLine);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                process.destroyForcibly();
                return statusOnly(request, sourcePath, info,
                        DocumentExtractionStatus.FAILED,
                        new DocumentExtractionWarning("ocr-timeout",
                                "OCR command exceeded " + Duration.ofMillis(timeoutMs).toSeconds() + " second timeout."));
            }
            String output = readLimited(process.getInputStream(), MAX_EXTRACTED_CHARS);
            if (process.exitValue() != 0) {
                return statusOnly(request, sourcePath, info,
                        DocumentExtractionStatus.OCR_UNAVAILABLE,
                        new DocumentExtractionWarning("ocr-failed",
                                "OCR command failed without usable text."));
            }
            if (output.isBlank()) {
                return statusOnly(request, sourcePath, info,
                        DocumentExtractionStatus.OCR_REQUIRED,
                        new DocumentExtractionWarning("ocr-empty",
                                "OCR completed but did not extract text."));
            }
            return extracted(request, sourcePath, info, output,
                    List.of(new DocumentExtractionWarning("ocr-text-only",
                            "Image support is OCR text extraction only; Talos does not perform visual scene understanding.")),
                    "tesseract-command", "local");
        } catch (Exception e) {
            return statusOnly(request, sourcePath, info,
                    DocumentExtractionStatus.OCR_UNAVAILABLE,
                    new DocumentExtractionWarning("ocr-unavailable",
                            "OCR command could not be started: " + ProtectedContentSanitizer.sanitizeText(e.getClass().getSimpleName())));
        }
    }

    private DocumentExtractionResult extracted(
            DocumentExtractionRequest request,
            String sourcePath,
            FileCapabilityPolicy.FormatInfo info,
            String rawText,
            List<DocumentExtractionWarning> warnings,
            String adapterName,
            String adapterVersion) {
        boolean truncated = rawText != null && rawText.length() > MAX_EXTRACTED_CHARS;
        String safe = ProtectedContentSanitizer.sanitizeText(limit(rawText));
        List<DocumentExtractionWarning> effectiveWarnings = new ArrayList<>(
                warnings == null ? List.of() : warnings);
        if (truncated) {
            effectiveWarnings.add(new DocumentExtractionWarning("extraction-truncated",
                    "Extracted text was truncated at " + MAX_EXTRACTED_CHARS
                            + " characters; request a narrower file range or search term before relying on omitted content."));
        }
        return new DocumentExtractionResult(
                sourcePath,
                request.intent(),
                info.capability(),
                truncated ? DocumentExtractionStatus.PARTIAL : DocumentExtractionStatus.SUCCESS,
                safe,
                List.copyOf(effectiveWarnings),
                provenance(sourcePath, adapterName, adapterVersion),
                PrivateDocumentContentPolicy.modelHandoffAllowed(cfg, request, info));
    }

    private static String extractPdf(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            if (document.isEncrypted()) {
                throw new IOException("encrypted PDF");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private static String extractDocx(Path path) throws IOException {
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(path));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private static WorkbookExtraction extractXlsx(Path path) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(path))) {
            return extractWorkbook(workbook);
        }
    }

    private static WorkbookExtraction extractXls(Path path) throws IOException {
        try (HSSFWorkbook workbook = new HSSFWorkbook(Files.newInputStream(path))) {
            return extractWorkbook(workbook);
        }
    }

    private record WorkbookExtraction(String text, int hiddenSheetsSkipped) {}

    private static WorkbookExtraction extractWorkbook(Workbook workbook) {
        StringBuilder out = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        int hiddenSheetsSkipped = 0;
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            if (workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex)) {
                hiddenSheetsSkipped++;
                continue;
            }
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            out.append("Sheet: ").append(sheet.getSheetName()).append('\n');
            for (Row row : sheet) {
                for (Cell cell : row) {
                    String value = formatWorkbookCell(cell, formatter);
                    if (!value.isBlank()) {
                        out.append(cell.getAddress().formatAsString())
                                .append(": ")
                                .append(value)
                                .append('\n');
                    }
                }
            }
        }
        return new WorkbookExtraction(out.toString(), hiddenSheetsSkipped);
    }

    private static String formatWorkbookCell(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        if (cell.getCellType() != CellType.FORMULA) {
            return formatter.formatCellValue(cell);
        }
        String formula = cell.getCellFormula();
        String cached = cachedFormulaValue(cell, formatter);
        if (cached.isBlank()) {
            return "[formula=" + formula + "; cached=(blank)]";
        }
        return "[formula=" + formula + "; cached=" + cached + "]";
    }

    private static String cachedFormulaValue(Cell cell, DataFormatter formatter) {
        return switch (cell.getCachedFormulaResultType()) {
            case NUMERIC -> formatter.formatRawCellContents(
                    cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(),
                    cell.getCellStyle().getDataFormatString());
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case ERROR -> {
                FormulaError error = FormulaError.forInt(cell.getErrorCellValue());
                yield error == null ? "ERROR" : "ERROR(" + error.getString() + ")";
            }
            case BLANK, _NONE, FORMULA -> "";
        };
    }

    private static List<DocumentExtractionWarning> excelWarnings(
            String formulaCode,
            String formulaMessage,
            int hiddenSheetsSkipped
    ) {
        List<DocumentExtractionWarning> warnings = new ArrayList<>();
        warnings.add(new DocumentExtractionWarning(formulaCode, formulaMessage));
        if (hiddenSheetsSkipped > 0) {
            warnings.add(new DocumentExtractionWarning("excel-hidden-sheets",
                    "Skipped " + hiddenSheetsSkipped + " hidden sheet(s); Excel extraction reports visible sheets/cells only."));
        }
        return List.copyOf(warnings);
    }

    private static DocumentExtractionStatus classifyExtractionFailure(Exception e) {
        String signal = failureSignal(e);
        if (signal.contains("invalidpassword")
                || signal.contains("password")
                || signal.contains("encrypt")) {
            return DocumentExtractionStatus.ENCRYPTED;
        }
        if (signal.contains("zip")
                || signal.contains("notoffice")
                || signal.contains("notole2")
                || signal.contains("officexml")
                || signal.contains("invalidformat")
                || signal.contains("invalid header")
                || signal.contains("recordformat")
                || signal.contains("valid ole2")
                || signal.contains("root object")
                || signal.contains("trailer")
                || signal.contains("xref")
                || signal.contains("end-of-file")
                || signal.contains("eof")
                || signal.contains("truncated")
                || signal.contains("not a valid")) {
            return DocumentExtractionStatus.CORRUPT;
        }
        return DocumentExtractionStatus.FAILED;
    }

    private static String failureSignal(Throwable throwable) {
        StringBuilder signal = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            signal.append(' ')
                    .append(current.getClass().getName())
                    .append(' ')
                    .append(current.getMessage() == null ? "" : current.getMessage());
            current = current.getCause();
        }
        return signal.toString().toLowerCase(Locale.ROOT);
    }

    private static DocumentExtractionWarning extractionFailureWarning(
            FileCapabilityPolicy.FormatInfo info,
            DocumentExtractionStatus status) {
        return switch (status) {
            case ENCRYPTED, PASSWORD_PROTECTED -> new DocumentExtractionWarning("document-encrypted",
                    info.label() + " is encrypted or password protected; Talos cannot extract its contents without an explicit supported decrypt step.");
            case CORRUPT -> new DocumentExtractionWarning("document-corrupt",
                    info.label() + " appears corrupt or invalid for its file type; Talos cannot rely on its contents.");
            default -> new DocumentExtractionWarning("extraction-failed",
                    info.label() + " extraction failed.");
        };
    }

    private Map<String, Object> familyConfig(String family) {
        Map<String, Object> extraction = CfgUtil.map(cfg.data.get("document_extraction"));
        return CfgUtil.map(extraction.get(family));
    }

    private static String readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(limit, 4096));
        int next;
        while ((next = input.read()) >= 0 && bytes.size() < limit) {
            bytes.write(next);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String limit(String value) {
        if (value == null) return "";
        if (value.length() <= MAX_EXTRACTED_CHARS) return value;
        return value.substring(0, MAX_EXTRACTED_CHARS);
    }

    private DocumentExtractionResult unsupportedResult(
            DocumentExtractionRequest request,
            String sourcePath,
            FileCapabilityPolicy.FormatInfo info) {
        DocumentExtractionStatus status = switch (info.defaultOutcome()) {
            case OCR_UNAVAILABLE -> DocumentExtractionStatus.OCR_UNAVAILABLE;
            case DEFERRED_UNSUPPORTED -> DocumentExtractionStatus.DEFERRED_UNSUPPORTED;
            case UNSUPPORTED_ARCHIVE -> DocumentExtractionStatus.UNSUPPORTED_ARCHIVE;
            case UNSUPPORTED_BINARY -> DocumentExtractionStatus.UNSUPPORTED_BINARY;
            default -> DocumentExtractionStatus.UNSUPPORTED_DISABLED;
        };
        String message = switch (status) {
            case OCR_UNAVAILABLE -> "OCR extraction for " + info.label() + " is not enabled or unavailable.";
            case DEFERRED_UNSUPPORTED -> info.label() + " extraction is deferred and not available in this beta scope.";
            case UNSUPPORTED_ARCHIVE -> "Archive extraction is not supported; Talos will not recurse into " + info.label() + " files.";
            case UNSUPPORTED_BINARY -> info.label() + " is not a supported text extraction format.";
            default -> info.label() + " extraction is not enabled.";
        };
        return statusOnly(request, sourcePath, info, status,
                new DocumentExtractionWarning("extraction-not-available", message));
    }

    private DocumentExtractionResult statusOnly(
            DocumentExtractionRequest request,
            String sourcePath,
            FileCapabilityPolicy.FormatInfo info,
            DocumentExtractionStatus status,
            DocumentExtractionWarning warning) {
        return new DocumentExtractionResult(
                sourcePath,
                request.intent(),
                info.capability(),
                status,
                "",
                List.of(warning),
                provenance(sourcePath, "unsupported", "builtin"),
                false);
    }

    private static DocumentExtractionProvenance provenance(String sourcePath, String adapterName, String adapterVersion) {
        return new DocumentExtractionProvenance(
                sourcePath,
                adapterName,
                adapterVersion,
                EXTRACTION_POLICY_VERSION);
    }

    private static String implementationVersion(Class<?> type, String fallback) {
        Package pkg = type == null ? null : type.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.isBlank() ? fallback : version;
    }

    private static String relativePath(Path workspaceRoot, Path path) {
        try {
            Path root = workspaceRoot == null ? path.getParent() : workspaceRoot;
            return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
        } catch (Exception ignored) {
            return path.getFileName() == null ? path.toString() : path.getFileName().toString();
        }
    }
}
