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
        String name = file.getFileName().toString().toLowerCase();
        String ext = extOf(name);

        if (UnsupportedDocumentFormats.isUnsupported(file)) {
            throw new IOException(UnsupportedDocumentFormats.capabilityMessage(file));
        }

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

    private static boolean likelyText(Path file) throws IOException {
        try (var channel = Files.newByteChannel(file)) {
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            channel.read(buffer);
            buffer.flip();

            while (buffer.hasRemaining()) {
                int b = buffer.get() & 0xFF;
                if (b == 0) return false;
            }
            return true;
        }
    }

}
