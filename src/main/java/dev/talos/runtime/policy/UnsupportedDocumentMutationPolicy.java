package dev.talos.runtime.policy;

import dev.talos.core.ingest.UnsupportedDocumentFormats;
import dev.talos.runtime.task.TaskContract;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Deterministic guard for unsupported binary-document creation requests. */
public final class UnsupportedDocumentMutationPolicy {
    private UnsupportedDocumentMutationPolicy() {}

    public static Optional<String> answerIfUnsupportedMutation(TaskContract contract) {
        if (contract == null || !contract.mutationRequested()) return Optional.empty();

        LinkedHashMap<String, UnsupportedDocumentFormats.Format> formats = new LinkedHashMap<>();
        for (String target : contract.expectedTargets()) {
            if (target == null || target.isBlank()) continue;
            try {
                UnsupportedDocumentFormats.describe(Path.of(target))
                        .ifPresent(format -> formats.putIfAbsent(format.extension(), format));
            } catch (RuntimeException ignored) {
                // Invalid paths are handled by the tool/pre-approval path guard.
            }
        }
        detectRequestedFormats(contract.originalUserRequest(), formats);

        if (formats.isEmpty()) return Optional.empty();
        return Optional.of(answer(formats));
    }

    private static void detectRequestedFormats(
            String userRequest,
            LinkedHashMap<String, UnsupportedDocumentFormats.Format> formats
    ) {
        if (userRequest == null || userRequest.isBlank() || formats == null) return;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        boolean docxRequested = containsAny(lower, DOCX_MARKERS);
        for (FormatMarkers entry : REQUEST_MARKERS) {
            for (String marker : entry.markers()) {
                if ("doc".equals(entry.extension())
                        && docxRequested
                        && !marker.startsWith(".")) {
                    continue;
                }
                if (lower.contains(marker)) {
                    UnsupportedDocumentFormats.describeExtension(entry.extension())
                            .ifPresent(format -> formats.putIfAbsent(format.extension(), format));
                    break;
                }
            }
        }
    }

    private static final String[] DOCX_MARKERS =
            new String[]{".docx", "docx file", "docx format", "word document", "word file"};

    private static final List<FormatMarkers> REQUEST_MARKERS = List.of(
            new FormatMarkers("pdf", new String[]{".pdf", "pdf file", "pdf format", "as pdf", "to pdf"}),
            new FormatMarkers("docx", DOCX_MARKERS),
            new FormatMarkers("doc", new String[]{".doc", "doc file", "doc format"}),
            new FormatMarkers("xlsx", new String[]{".xlsx", "xlsx file", "excel workbook", "excel file"}),
            new FormatMarkers("xls", new String[]{".xls", "xls file"}),
            new FormatMarkers("pptx", new String[]{".pptx", "pptx file", "powerpoint presentation", "powerpoint file"}),
            new FormatMarkers("ppt", new String[]{".ppt", "ppt file"})
    );

    private record FormatMarkers(String extension, String[] markers) {}

    private static boolean containsAny(String lower, String[] markers) {
        if (lower == null || lower.isBlank() || markers == null) return false;
        for (String marker : markers) {
            if (marker != null && !marker.isBlank() && lower.contains(marker)) return true;
        }
        return false;
    }

    private static String answer(LinkedHashMap<String, UnsupportedDocumentFormats.Format> formats) {
        StringBuilder out = new StringBuilder();
        out.append("Talos cannot create valid unsupported binary document files with the current ")
                .append("local text-file tool surface. No file was changed.\n\n");
        for (UnsupportedDocumentFormats.Format format : formats.values()) {
            out.append("- Talos cannot create valid ")
                    .append(format.label())
                    .append(" files with the current local text-file tool surface.\n");
        }
        out.append("\nUse a supported source format such as Markdown (`.md`), plain text (`.txt`), ")
                .append("HTML (`.html`), or CSV (`.csv`), then convert it with a dedicated document tool.");
        return out.toString();
    }
}
