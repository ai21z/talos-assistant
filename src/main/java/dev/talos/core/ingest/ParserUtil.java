package dev.talos.core.ingest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Lightweight, safe text extraction for common dev docs. */
public final class ParserUtil {
    private ParserUtil() {}

    public static String smartParse(Path file) throws IOException {
        if (UnsupportedDocumentFormats.isUnsupported(file)) {
            throw new IOException(UnsupportedDocumentFormats.capabilityMessage(file));
        }
        // Preserve cheap rejection for path-based callers before allocating the full file.
        try (var channel = Files.newByteChannel(file)) {
            ByteBuffer prefix = ByteBuffer.allocate(4096);
            channel.read(prefix);
            for (int i = 0; i < prefix.position(); i++) {
                if (prefix.get(i) == 0) throw new IOException("Binary or unsupported file: " + file);
            }
        }
        return smartParse(file, Files.readAllBytes(file));
    }

    /** Parse the same immutable bytes that the caller hashes for index freshness. */
    public static String smartParse(Path file, byte[] snapshot) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        String ext = extOf(name);

        if (UnsupportedDocumentFormats.isUnsupported(file)) {
            throw new IOException(UnsupportedDocumentFormats.capabilityMessage(file));
        }

        // quick binary sniff
        if (!likelyText(snapshot)) throw new IOException("Binary or unsupported file: " + file);

        String raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(snapshot)).toString();

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
            case "html", "htm", "xml", "svg", "xhtml" -> {
                // Developer agent: preserve full source for code review and indexing.
                // The previous behaviour stripped <script>, <style>, and all tags,
                // destroying CSS/JS and reducing 190-line files to ~200 chars of
                // plain text - causing single-chunk indexing and context starvation.
                return raw.trim();
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

    private static boolean likelyText(byte[] snapshot) {
        for (int i = 0; i < Math.min(snapshot.length, 4096); i++) {
            if (snapshot[i] == 0) return false;
        }
        return true;
    }

}
