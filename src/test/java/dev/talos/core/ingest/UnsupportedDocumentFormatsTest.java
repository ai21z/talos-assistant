package dev.talos.core.ingest;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsupportedDocumentFormatsTest {

    @Test
    void unsupported_image_read_is_honest() {
        assertTrue(UnsupportedDocumentFormats.isUnsupported(Path.of("image.png")));
    }

    @Test
    void unsupported_archive_read_is_honest() {
        assertTrue(UnsupportedDocumentFormats.isUnsupported(Path.of("archive.zip")));
    }

    @Test
    void unsupported_binary_read_is_honest() {
        assertTrue(UnsupportedDocumentFormats.isUnsupported(Path.of("binary.bin")));
    }
}
