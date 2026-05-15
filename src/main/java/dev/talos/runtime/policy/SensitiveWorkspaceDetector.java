package dev.talos.runtime.policy;

import dev.talos.core.ingest.FileCapabilityPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SensitiveWorkspaceDetector {
    private static final List<String> SENSITIVE_FOLDER_TERMS = List.of(
            "tax", "taxes", "health", "medical", "legal", "family", "admin", "paperwork",
            "finance", "bank", "insurance", "passport", "credentials", "secrets", "protected");
    private static final List<String> SHORT_TOKEN_FOLDER_TERMS = List.of("id");

    private static final List<String> SENSITIVE_FILENAME_TERMS = List.of(
            "password", "token", "credential", "private", "ssn", "passport", "insurance", "tax");

    private SensitiveWorkspaceDetector() {}

    public record Assessment(boolean sensitive, List<String> signals, String warning) {}

    public static Assessment assess(Path workspace) {
        Path root = workspace == null ? Path.of(".") : workspace.toAbsolutePath().normalize();
        List<String> signals = new ArrayList<>();

        String folderName = root.getFileName() == null
                ? ""
                : root.getFileName().toString().toLowerCase(Locale.ROOT);
        if (containsSensitiveFolderTerm(folderName) || containsShortTokenTerm(folderName)) {
            signals.add("workspace name looks sensitive");
        }

        int privateDocumentCount = 0;
        try (var stream = Files.walk(root, 2)) {
            for (Path path : stream.toList()) {
                if (path.equals(root)) continue;
                Path rel = root.relativize(path);
                String normalized = rel.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                String fileName = path.getFileName() == null
                        ? ""
                        : path.getFileName().toString().toLowerCase(Locale.ROOT);

                if (Files.isDirectory(path)) {
                    if (normalized.equals("secrets") || normalized.equals("protected")
                            || normalized.endsWith("/secrets") || normalized.endsWith("/protected")) {
                        signals.add("protected directory present");
                    } else if (containsSensitiveFolderTerm(fileName) || containsShortTokenTerm(fileName)) {
                        signals.add("sensitive-looking directory present");
                    }
                    continue;
                }

                if (fileName.equals(".env") || fileName.startsWith(".env.")) {
                    signals.add("protected env-like file present");
                }
                for (String term : SENSITIVE_FILENAME_TERMS) {
                    if (fileName.contains(term)) {
                        signals.add("sensitive-looking filename present");
                        break;
                    }
                }
                if (containsShortTokenTerm(fileName)) {
                    signals.add("sensitive-looking filename present");
                }
                if (FileCapabilityPolicy.describe(path).isPresent()) {
                    privateDocumentCount++;
                }
            }
        } catch (IOException ignored) {
            return new Assessment(false, List.of(), "");
        }

        if (privateDocumentCount >= 3) {
            signals.add("many private documents or unsupported document-like files present");
        }

        List<String> distinct = signals.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return new Assessment(false, List.of(), "");
        }
        return new Assessment(true, distinct,
                "This workspace looks sensitive. Private mode is recommended. Run /privacy private on. "
                        + "Signals: " + String.join(", ", distinct) + ".");
    }

    private static boolean containsSensitiveFolderTerm(String value) {
        for (String term : SENSITIVE_FOLDER_TERMS) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsShortTokenTerm(String value) {
        List<String> tokens = tokens(value);
        for (String term : SHORT_TOKEN_FOLDER_TERMS) {
            if (tokens.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> tokens(String value) {
        if (value == null || value.isBlank()) return List.of();
        String[] parts = value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) out.add(part);
        }
        return out;
    }
}
