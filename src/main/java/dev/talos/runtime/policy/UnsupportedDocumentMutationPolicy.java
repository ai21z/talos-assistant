package dev.talos.runtime.policy;

import dev.talos.core.ingest.UnsupportedDocumentFormats;
import dev.talos.runtime.task.TaskContract;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

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
        if (formats.isEmpty() && contract.expectedTargets().isEmpty()) {
            detectRequestedFormats(contract.originalUserRequest(), formats);
        }

        if (formats.isEmpty()) return Optional.empty();
        return Optional.of(answer(formats, true));
    }

    public static Optional<String> answerIfUnsupportedCapabilityQuestion(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Optional.empty();
        String lower = userRequest.toLowerCase(Locale.ROOT);
        if (!looksLikeCreationCapabilityQuestion(lower)) return Optional.empty();

        LinkedHashMap<String, UnsupportedDocumentFormats.Format> formats = new LinkedHashMap<>();
        detectRequestedFormats(userRequest, formats);
        if (formats.isEmpty()) return Optional.empty();
        return Optional.of(answer(formats, looksLikeCreationInstruction(lower)));
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
        detectNaturalFormatRequests(lower, formats);
    }

    private static void detectNaturalFormatRequests(
            String lower,
            LinkedHashMap<String, UnsupportedDocumentFormats.Format> formats
    ) {
        if (lower == null || lower.isBlank() || formats == null) return;
        for (FormatMarkers entry : REQUEST_MARKERS) {
            if (looksLikeNaturalFormatRequest(lower, entry.extension())) {
                UnsupportedDocumentFormats.describeExtension(entry.extension())
                        .ifPresent(format -> formats.putIfAbsent(format.extension(), format));
            }
        }
    }

    private static boolean looksLikeNaturalFormatRequest(String lower, String extension) {
        if (lower == null || lower.isBlank() || extension == null || extension.isBlank()) return false;
        String ext = Pattern.quote(extension.toLowerCase(Locale.ROOT));
        String verbFormat = "\\b(?:create|make|generate|produce|write|save|export|convert)\\s+"
                + "(?:a\\s+|an\\s+|the\\s+)?" + ext + "\\b"
                + "(?=\\s*(?:$|[?.!,;:]|with\\b|for\\b|about\\b|containing\\b|from\\b|"
                + "please\\b|guide\\b|document\\b|file\\b|format\\b|version\\b))";
        String formatArtifact = "\\b" + ext
                + "\\s+(?:guide|document|file|format|version)\\b";
        return Pattern.compile(verbFormat).matcher(lower).find()
                || Pattern.compile(formatArtifact).matcher(lower).find();
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

    private static boolean looksLikeCreationCapabilityQuestion(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return containsAny(lower, new String[]{
                "create", "make", "generate", "produce", "write", "save", "export", "convert"
        });
    }

    private static boolean looksLikeCreationInstruction(String lower) {
        if (lower == null || lower.isBlank()) return false;
        if (lower.contains("cannot") || lower.contains("can't") || lower.contains("cant")) return false;
        return containsAny(lower, new String[]{
                "i want", "i need", "you should", "please", "create", "make", "generate",
                "produce", "write", "save", "export", "convert"
        });
    }

    private static boolean containsAny(String lower, String[] markers) {
        if (lower == null || lower.isBlank() || markers == null) return false;
        for (String marker : markers) {
            if (marker != null && !marker.isBlank() && lower.contains(marker)) return true;
        }
        return false;
    }

    private static String answer(
            LinkedHashMap<String, UnsupportedDocumentFormats.Format> formats,
            boolean includeNoFileChanged
    ) {
        StringBuilder out = new StringBuilder();
        out.append("Talos cannot create valid unsupported binary document files with the current ")
                .append("local text-file tool surface.");
        if (includeNoFileChanged) {
            out.append(" No file was changed.");
        }
        out.append("\n\n");
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
