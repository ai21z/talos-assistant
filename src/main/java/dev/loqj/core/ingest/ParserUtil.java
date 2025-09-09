package dev.loqj.core.ingest;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Lightweight, safe text extraction for common dev docs. */
public final class ParserUtil {
    private ParserUtil() {}

    public static String smartParse(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        String ext = extOf(name);

        // quick binary sniff
        if (!likelyText(file)) throw new IOException("Binary or unsupported file: " + file);

        String raw = Files.readString(file, StandardCharsets.UTF_8);

        switch (ext) {
            case "md", "markdown" -> {
                // Keep headings and code fences as-is; strip HTML comments
                return raw.replaceAll("(?s)<!--.*?-->", "").trim();
            }
            case "txt", "log" -> {
                return raw.trim();
            }
            case "yaml", "yml", "json", "properties", "conf", "cfg", "ini" -> {
                return raw.trim();
            }
            case "html", "htm", "xml" -> {
                // naive tag stripper for quick context (not an HTML parser)
                String noScripts = raw.replaceAll("(?is)<script.*?</script>", " ");
                String noStyles  = noScripts.replaceAll("(?is)<style.*?</style>", " ");
                String textOnly  = noStyles.replaceAll("(?is)<[^>]+>", " ");
                return textOnly.replaceAll("[\\t ]+", " ").replaceAll("\\s+\\n", "\n").trim();
            }
            default -> {
                // Treat code & other plaintext as-is
                return raw.trim();
            }
        }
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "";
        return name.substring(dot + 1);
    }

    private static boolean likelyText(Path file) throws IOException {
        byte[] buf = Files.readAllBytes(file);
        int n = Math.min(buf.length, 4096);
        for (int i = 0; i < n; i++) {
            int b = buf[i] & 0xFF;
            if (b == 0) return false; // NUL often indicates binary
        }
        return true;
    }
}
