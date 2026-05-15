package dev.talos.core.ingest;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Classifies local file formats Talos can or cannot inspect with text tools. */
public final class FileCapabilityPolicy {
    private FileCapabilityPolicy() {}

    public enum Capability {
        SUPPORTED_TEXT,
        UNSUPPORTED_BINARY_DOCUMENT,
        UNSUPPORTED_IMAGE_OR_SCAN,
        UNSUPPORTED_ARCHIVE,
        UNSUPPORTED_COMPILED_OR_EXECUTABLE,
        UNSUPPORTED_BINARY,
        UNKNOWN_TEXT_ATTEMPT_ALLOWED
    }

    public record FormatInfo(String extension, String label, String contentName, Capability capability) {}

    private static final Map<String, FormatInfo> UNSUPPORTED = Map.ofEntries(
            entry("pdf", "PDF", "PDF", Capability.UNSUPPORTED_BINARY_DOCUMENT),
            entry("doc", "Microsoft Word .doc", "Word document", Capability.UNSUPPORTED_BINARY_DOCUMENT),
            entry("docx", "Microsoft Word .docx", "Word document", Capability.UNSUPPORTED_BINARY_DOCUMENT),
            entry("xls", "Microsoft Excel .xls", "Excel workbook", Capability.UNSUPPORTED_BINARY_DOCUMENT),
            entry("xlsx", "Microsoft Excel .xlsx", "Excel workbook", Capability.UNSUPPORTED_BINARY_DOCUMENT),
            entry("ppt", "Microsoft PowerPoint .ppt", "PowerPoint presentation", Capability.UNSUPPORTED_BINARY_DOCUMENT),
            entry("pptx", "Microsoft PowerPoint .pptx", "PowerPoint presentation", Capability.UNSUPPORTED_BINARY_DOCUMENT),
            entry("png", "PNG image", "image", Capability.UNSUPPORTED_IMAGE_OR_SCAN),
            entry("jpg", "JPEG image", "image", Capability.UNSUPPORTED_IMAGE_OR_SCAN),
            entry("jpeg", "JPEG image", "image", Capability.UNSUPPORTED_IMAGE_OR_SCAN),
            entry("gif", "GIF image", "image", Capability.UNSUPPORTED_IMAGE_OR_SCAN),
            entry("bmp", "BMP image", "image", Capability.UNSUPPORTED_IMAGE_OR_SCAN),
            entry("webp", "WebP image", "image", Capability.UNSUPPORTED_IMAGE_OR_SCAN),
            entry("tif", "TIFF image", "image", Capability.UNSUPPORTED_IMAGE_OR_SCAN),
            entry("tiff", "TIFF image", "image", Capability.UNSUPPORTED_IMAGE_OR_SCAN),
            entry("zip", "ZIP archive", "archive", Capability.UNSUPPORTED_ARCHIVE),
            entry("tar", "TAR archive", "archive", Capability.UNSUPPORTED_ARCHIVE),
            entry("gz", "gzip archive", "archive", Capability.UNSUPPORTED_ARCHIVE),
            entry("tgz", "gzip TAR archive", "archive", Capability.UNSUPPORTED_ARCHIVE),
            entry("7z", "7z archive", "archive", Capability.UNSUPPORTED_ARCHIVE),
            entry("rar", "RAR archive", "archive", Capability.UNSUPPORTED_ARCHIVE),
            entry("exe", "Windows executable", "executable", Capability.UNSUPPORTED_COMPILED_OR_EXECUTABLE),
            entry("dll", "dynamic library", "binary library", Capability.UNSUPPORTED_COMPILED_OR_EXECUTABLE),
            entry("so", "shared object", "binary library", Capability.UNSUPPORTED_COMPILED_OR_EXECUTABLE),
            entry("dylib", "dynamic library", "binary library", Capability.UNSUPPORTED_COMPILED_OR_EXECUTABLE),
            entry("class", "Java class file", "compiled class", Capability.UNSUPPORTED_COMPILED_OR_EXECUTABLE),
            entry("jar", "Java archive", "archive", Capability.UNSUPPORTED_COMPILED_OR_EXECUTABLE),
            entry("war", "Java web archive", "archive", Capability.UNSUPPORTED_COMPILED_OR_EXECUTABLE),
            entry("ear", "Java enterprise archive", "archive", Capability.UNSUPPORTED_COMPILED_OR_EXECUTABLE),
            entry("bin", "binary file", "binary file", Capability.UNSUPPORTED_BINARY),
            entry("dat", "binary/data file", "binary file", Capability.UNSUPPORTED_BINARY)
    );

    public static Capability classify(Path path) {
        return describe(path)
                .map(FormatInfo::capability)
                .orElse(Capability.UNKNOWN_TEXT_ATTEMPT_ALLOWED);
    }

    public static Optional<FormatInfo> describe(Path path) {
        String ext = extension(path);
        if (ext.isBlank()) return Optional.empty();
        return Optional.ofNullable(UNSUPPORTED.get(ext));
    }

    public static boolean isUnsupported(Path path) {
        return describe(path).isPresent();
    }

    public static String readCapabilityMessage(Path path) {
        String fileName = fileName(path);
        FormatInfo format = describe(path).orElse(new FormatInfo("", "binary file", "binary file",
                Capability.UNSUPPORTED_BINARY));
        return "Unsupported binary document format: " + fileName + " (" + format.label() + "). "
                + "Talos cannot extract " + format.contentName()
                + " contents with the current local text-tool surface. "
                + "Convert it to text, Markdown, CSV, or another supported text format before relying on its contents.";
    }

    public static String writeCapabilityMessage(Path path) {
        String fileName = fileName(path);
        FormatInfo format = describe(path).orElse(new FormatInfo("", "binary file", "binary file",
                Capability.UNSUPPORTED_BINARY));
        return "Unsupported binary document format: " + fileName + " (" + format.label() + "). "
                + "Talos cannot create valid " + format.label()
                + " files with the current local text-file tool surface. "
                + "Use Markdown, plain text, HTML, CSV, or another supported text source format, "
                + "then convert it with a dedicated document tool.";
    }

    private static Map.Entry<String, FormatInfo> entry(
            String extension,
            String label,
            String contentName,
            Capability capability) {
        return Map.entry(extension, new FormatInfo(extension, label, contentName, capability));
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
