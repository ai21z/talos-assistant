package dev.talos.core.ingest;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Classifies local file formats Talos can or cannot inspect with text tools. */
public final class FileCapabilityPolicy {
    private FileCapabilityPolicy() {}

    public static final String POLICY_VERSION = "file-capability-policy-v3";

    public enum Capability {
        SUPPORTED_TEXT,
        EXTRACTABLE_TEXT_DISABLED,
        EXTRACTABLE_TEXT_ENABLED,
        OCR_REQUIRED_DISABLED,
        OCR_ENABLED,
        DEFERRED_UNSUPPORTED,
        ARCHIVE_UNSUPPORTED,
        COMPILED_OR_EXECUTABLE_UNSUPPORTED,
        UNKNOWN_TEXT_ATTEMPT_ALLOWED,
        UNKNOWN_BINARY_SKIP
    }

    public enum ExtractionOutcome {
        NOT_ATTEMPTED,
        SUCCESS,
        PARTIAL,
        OCR_REQUIRED,
        OCR_UNAVAILABLE,
        PASSWORD_PROTECTED,
        ENCRYPTED,
        CORRUPT,
        LIMIT_EXCEEDED,
        FAILED,
        BLOCKED_BY_PRIVACY,
        UNSUPPORTED_DISABLED,
        DEFERRED_UNSUPPORTED,
        UNSUPPORTED_ARCHIVE,
        UNSUPPORTED_BINARY
    }

    public record FormatInfo(
            String extension,
            String label,
            String contentName,
            Capability capability,
            boolean extractable,
            boolean enabled,
            ExtractionOutcome defaultOutcome) {}

    private enum Family {
        PDF,
        WORD_DOCX,
        WORD_DOC_DEFERRED,
        EXCEL,
        POWERPOINT_DEFERRED,
        IMAGE_OCR,
        ARCHIVE,
        COMPILED,
        BINARY
    }

    private record FormatTemplate(String extension, String label, String contentName, Family family) {}

    private static final Map<String, FormatTemplate> KNOWN_FORMATS = Map.ofEntries(
            entry("pdf", "PDF", "PDF", Family.PDF),
            entry("doc", "Microsoft Word .doc", "legacy Word document", Family.WORD_DOC_DEFERRED),
            entry("docx", "Microsoft Word .docx", "Word document", Family.WORD_DOCX),
            entry("xls", "Microsoft Excel .xls", "Excel workbook", Family.EXCEL),
            entry("xlsx", "Microsoft Excel .xlsx", "Excel workbook", Family.EXCEL),
            entry("ppt", "Microsoft PowerPoint .ppt", "PowerPoint presentation", Family.POWERPOINT_DEFERRED),
            entry("pptx", "Microsoft PowerPoint .pptx", "PowerPoint presentation", Family.POWERPOINT_DEFERRED),
            entry("png", "PNG image", "image", Family.IMAGE_OCR),
            entry("jpg", "JPEG image", "image", Family.IMAGE_OCR),
            entry("jpeg", "JPEG image", "image", Family.IMAGE_OCR),
            entry("gif", "GIF image", "image", Family.IMAGE_OCR),
            entry("bmp", "BMP image", "image", Family.IMAGE_OCR),
            entry("webp", "WebP image", "image", Family.IMAGE_OCR),
            entry("tif", "TIFF image", "image", Family.IMAGE_OCR),
            entry("tiff", "TIFF image", "image", Family.IMAGE_OCR),
            entry("zip", "ZIP archive", "archive", Family.ARCHIVE),
            entry("tar", "TAR archive", "archive", Family.ARCHIVE),
            entry("gz", "gzip archive", "archive", Family.ARCHIVE),
            entry("tgz", "gzip TAR archive", "archive", Family.ARCHIVE),
            entry("7z", "7z archive", "archive", Family.ARCHIVE),
            entry("rar", "RAR archive", "archive", Family.ARCHIVE),
            entry("exe", "Windows executable", "executable", Family.COMPILED),
            entry("dll", "dynamic library", "binary library", Family.COMPILED),
            entry("so", "shared object", "binary library", Family.COMPILED),
            entry("dylib", "dynamic library", "binary library", Family.COMPILED),
            entry("class", "Java class file", "compiled class", Family.COMPILED),
            entry("jar", "Java archive", "archive", Family.COMPILED),
            entry("war", "Java web archive", "archive", Family.COMPILED),
            entry("ear", "Java enterprise archive", "archive", Family.COMPILED),
            entry("bin", "binary file", "binary file", Family.BINARY),
            entry("dat", "binary/data file", "binary file", Family.BINARY)
    );

    public static Capability classify(Path path) {
        return describe(path)
                .map(FormatInfo::capability)
                .orElse(Capability.UNKNOWN_TEXT_ATTEMPT_ALLOWED);
    }

    public static Capability classify(Path path, Config cfg) {
        return describe(path, cfg)
                .map(FormatInfo::capability)
                .orElse(Capability.UNKNOWN_TEXT_ATTEMPT_ALLOWED);
    }

    public static Optional<FormatInfo> describe(Path path) {
        return describe(path, null);
    }

    public static Optional<FormatInfo> describe(Path path, Config cfg) {
        String ext = extension(path);
        if (ext.isBlank()) return Optional.empty();
        FormatTemplate template = KNOWN_FORMATS.get(ext);
        if (template == null) return Optional.empty();
        return Optional.of(toInfo(template, cfg));
    }

    public static boolean isUnsupported(Path path) {
        return describe(path).isPresent();
    }

    public static String readCapabilityMessage(Path path) {
        String fileName = fileName(path);
        FormatInfo format = describe(path).orElse(new FormatInfo("", "binary file", "binary file",
                Capability.UNKNOWN_BINARY_SKIP, false, false, ExtractionOutcome.UNSUPPORTED_BINARY));
        return "Unsupported binary document format: " + fileName + " (" + format.label() + "). "
                + "Talos cannot extract " + format.contentName()
                + " contents with the current local text-tool surface. "
                + "Convert it to text, Markdown, CSV, or another supported text format before relying on its contents.";
    }

    public static String writeCapabilityMessage(Path path) {
        String fileName = fileName(path);
        FormatInfo format = describe(path).orElse(new FormatInfo("", "binary file", "binary file",
                Capability.UNKNOWN_BINARY_SKIP, false, false, ExtractionOutcome.UNSUPPORTED_BINARY));
        return "Unsupported binary document format: " + fileName + " (" + format.label() + "). "
                + "Talos cannot create valid " + format.label()
                + " files with the current local text-file tool surface. "
                + "Use Markdown, plain text, HTML, CSV, or another supported text source format, "
                + "then convert it with a dedicated document tool.";
    }

    private static FormatInfo toInfo(FormatTemplate template, Config cfg) {
        return switch (template.family()) {
            case PDF -> extractable(template, enabled(cfg, "pdf"));
            case WORD_DOCX -> extractable(template, enabled(cfg, "word"));
            case WORD_DOC_DEFERRED, POWERPOINT_DEFERRED -> new FormatInfo(
                    template.extension(),
                    template.label(),
                    template.contentName(),
                    Capability.DEFERRED_UNSUPPORTED,
                    false,
                    false,
                    ExtractionOutcome.DEFERRED_UNSUPPORTED);
            case EXCEL -> extractable(template, enabled(cfg, "excel"));
            case IMAGE_OCR -> {
                boolean enabled = enabled(cfg, "image_ocr");
                yield new FormatInfo(
                        template.extension(),
                        template.label(),
                        template.contentName(),
                        enabled ? Capability.OCR_ENABLED : Capability.OCR_REQUIRED_DISABLED,
                        true,
                        enabled,
                        enabled ? ExtractionOutcome.NOT_ATTEMPTED : ExtractionOutcome.OCR_UNAVAILABLE);
            }
            case ARCHIVE -> new FormatInfo(
                    template.extension(),
                    template.label(),
                    template.contentName(),
                    Capability.ARCHIVE_UNSUPPORTED,
                    false,
                    false,
                    ExtractionOutcome.UNSUPPORTED_ARCHIVE);
            case COMPILED -> new FormatInfo(
                    template.extension(),
                    template.label(),
                    template.contentName(),
                    Capability.COMPILED_OR_EXECUTABLE_UNSUPPORTED,
                    false,
                    false,
                    ExtractionOutcome.UNSUPPORTED_BINARY);
            case BINARY -> new FormatInfo(
                    template.extension(),
                    template.label(),
                    template.contentName(),
                    Capability.UNKNOWN_BINARY_SKIP,
                    false,
                    false,
                    ExtractionOutcome.UNSUPPORTED_BINARY);
        };
    }

    private static FormatInfo extractable(FormatTemplate template, boolean enabled) {
        return new FormatInfo(
                template.extension(),
                template.label(),
                template.contentName(),
                enabled ? Capability.EXTRACTABLE_TEXT_ENABLED : Capability.EXTRACTABLE_TEXT_DISABLED,
                true,
                enabled,
                enabled ? ExtractionOutcome.NOT_ATTEMPTED : ExtractionOutcome.UNSUPPORTED_DISABLED);
    }

    private static boolean enabled(Config cfg, String family) {
        if (cfg == null) return false;
        Map<String, Object> extraction = CfgUtil.map(cfg.data.get("document_extraction"));
        if (!CfgUtil.boolAt(extraction, "enabled", false)) return false;
        Map<String, Object> familyConfig = CfgUtil.map(extraction.get(family));
        return CfgUtil.boolAt(familyConfig, "enabled", false);
    }

    private static Map.Entry<String, FormatTemplate> entry(
            String extension,
            String label,
            String contentName,
            Family family) {
        return Map.entry(extension, new FormatTemplate(extension, label, contentName, family));
    }

    private static String extension(Path path) {
        if (path == null || path.getFileName() == null) return "";
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String fileName(Path path) {
        return path == null || path.getFileName() == null
                ? "requested file"
                : path.getFileName().toString();
    }
}
