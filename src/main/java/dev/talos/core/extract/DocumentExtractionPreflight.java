package dev.talos.core.extract;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.safety.ProtectedContentSanitizer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reports whether the configured local document extraction surface is usable.
 *
 * <p>This class intentionally does not execute configured OCR commands. Status
 * and startup diagnostics must not run arbitrary user-configured programs just
 * to print a dashboard. Actual extraction remains owned by
 * {@link DocumentExtractionService}, where tool execution is explicit and
 * bounded.
 */
public final class DocumentExtractionPreflight {
    private DocumentExtractionPreflight() {}

    public record FamilyStatus(
            String label,
            boolean enabled,
            boolean usable,
            String summary,
            String detail) {
        public FamilyStatus {
            label = label == null ? "" : label;
            summary = ProtectedContentSanitizer.sanitizeText(summary == null ? "" : summary);
            detail = ProtectedContentSanitizer.sanitizeText(detail == null ? "" : detail);
        }
    }

    public static List<FamilyStatus> assess(Config cfg) {
        return List.of(
                configuredFamily(cfg, "PDF", "pdf", "PDFBox text extractor configured."),
                configuredFamily(cfg, "Word", "word", "Apache POI DOCX text extractor configured."),
                configuredFamily(cfg, "Excel", "excel", "Apache POI XLS/XLSX visible-cell extractor configured."),
                imageOcr(cfg));
    }

    public static FamilyStatus imageOcr(Config cfg) {
        boolean globalEnabled = globalEnabled(cfg);
        Map<String, Object> image = family(cfg, "image_ocr");
        boolean enabled = globalEnabled && CfgUtil.boolAt(image, "enabled", false);
        String command = String.valueOf(image.getOrDefault("command", "")).strip();

        if (!enabled) {
            return new FamilyStatus(
                    "Image OCR",
                    false,
                    false,
                    "disabled",
                    command.isBlank()
                            ? "OCR command not configured."
                            : "OCR family disabled; configured command is ignored.");
        }
        if (command.isBlank()) {
            return new FamilyStatus(
                    "Image OCR",
                    true,
                    false,
                    "unavailable",
                    "OCR is enabled, but the local OCR command is not configured.");
        }

        return resolveCommand(command)
                .map(path -> new FamilyStatus(
                        "Image OCR",
                        true,
                        true,
                        "available",
                        "OCR command resolves to: " + path.toAbsolutePath().normalize()))
                .orElseGet(() -> new FamilyStatus(
                        "Image OCR",
                        true,
                        false,
                        "unavailable",
                        "OCR command not found on PATH or at configured path: " + command));
    }

    public static String render(Config cfg) {
        StringBuilder sb = new StringBuilder("Document Extraction\n");
        for (FamilyStatus status : assess(cfg)) {
            sb.append("  ")
                    .append(status.label())
                    .append(": ")
                    .append(status.summary());
            if (!status.detail().isBlank()) {
                sb.append(" - ").append(status.detail());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static FamilyStatus configuredFamily(Config cfg, String label, String key, String detail) {
        boolean enabled = globalEnabled(cfg) && CfgUtil.boolAt(family(cfg, key), "enabled", true);
        return new FamilyStatus(
                label,
                enabled,
                enabled,
                enabled ? "enabled" : "disabled",
                enabled ? detail : label + " extraction is disabled by configuration.");
    }

    private static boolean globalEnabled(Config cfg) {
        Map<String, Object> extraction = CfgUtil.map((cfg == null ? new Config(null) : cfg).data.get("document_extraction"));
        return CfgUtil.boolAt(extraction, "enabled", true);
    }

    private static Map<String, Object> family(Config cfg, String family) {
        Map<String, Object> extraction = CfgUtil.map((cfg == null ? new Config(null) : cfg).data.get("document_extraction"));
        return CfgUtil.map(extraction.get(family));
    }

    private static Optional<Path> resolveCommand(String command) {
        String cleaned = stripWrappingQuotes(command == null ? "" : command.strip());
        if (cleaned.isBlank()) return Optional.empty();

        Path direct = Path.of(cleaned);
        if (direct.isAbsolute() || containsPathSeparator(cleaned)) {
            return executableFile(direct);
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) return Optional.empty();
        List<String> extensions = commandExtensions(cleaned);
        for (String dir : pathEnv.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (dir.isBlank()) continue;
            Path base = Path.of(stripWrappingQuotes(dir.strip()));
            for (String ext : extensions) {
                Optional<Path> hit = executableFile(base.resolve(cleaned + ext));
                if (hit.isPresent()) return hit;
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> executableFile(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) return Optional.of(normalized);
        } catch (RuntimeException ignored) {
            // Invalid path text or inaccessible path. Treat as unresolved.
        }
        return Optional.empty();
    }

    private static List<String> commandExtensions(String command) {
        if (command.contains(".")) return List.of("");
        if (!isWindows()) return List.of("");
        Set<String> extensions = new LinkedHashSet<>();
        extensions.add("");
        String pathext = System.getenv("PATHEXT");
        if (pathext == null || pathext.isBlank()) {
            extensions.addAll(List.of(".COM", ".EXE", ".BAT", ".CMD"));
        } else {
            for (String ext : pathext.split(";")) {
                if (!ext.isBlank()) extensions.add(ext.trim());
            }
        }
        return new ArrayList<>(extensions);
    }

    private static boolean containsPathSeparator(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
    }

    private static String stripWrappingQuotes(String value) {
        if (value == null) return "";
        String s = value.strip();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
