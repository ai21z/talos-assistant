package dev.talos.core.ingest;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Capability boundary for binary document formats Talos does not extract yet.
 */
public final class UnsupportedDocumentFormats {
    private static final Map<String, Format> FORMATS = Map.of(
            "pdf", new Format("pdf", "PDF", "PDF"),
            "doc", new Format("doc", "Microsoft Word .doc", "Word document"),
            "docx", new Format("docx", "Microsoft Word .docx", "Word document"),
            "xls", new Format("xls", "Microsoft Excel .xls", "Excel workbook"),
            "xlsx", new Format("xlsx", "Microsoft Excel .xlsx", "Excel workbook"),
            "ppt", new Format("ppt", "Microsoft PowerPoint .ppt", "PowerPoint presentation"),
            "pptx", new Format("pptx", "Microsoft PowerPoint .pptx", "PowerPoint presentation")
    );

    private UnsupportedDocumentFormats() {}

    public static Optional<Format> describe(Path path) {
        if (path == null || path.getFileName() == null) return Optional.empty();
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return Optional.empty();
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Optional.ofNullable(FORMATS.get(ext));
    }

    public static boolean isUnsupported(Path path) {
        return describe(path).isPresent();
    }

    public static String capabilityMessage(Path path) {
        String fileName = path == null || path.getFileName() == null
                ? "requested file"
                : path.getFileName().toString();
        Format format = describe(path).orElse(new Format("", "binary document", "binary document"));
        return "Unsupported binary document format: " + fileName + " (" + format.label() + "). "
                + "Talos cannot extract " + format.contentName()
                + " contents with the current local text-tool surface. "
                + "Convert it to text, Markdown, CSV, or another supported text format before relying on its contents.";
    }

    public record Format(String extension, String label, String contentName) {}
}
