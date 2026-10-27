package dev.talos.core.extract;

import dev.talos.core.diff.DocumentTextDiff;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static dev.talos.core.extract.DocumentSnapshotExtractor.Format;
import static dev.talos.core.extract.DocumentSnapshotExtractor.Status;
import static org.junit.jupiter.api.Assertions.*;

class DocumentSnapshotExtractorTest {
    private static final DocumentTextDiff.Limits DIFF_LIMITS = DocumentTextDiff.Limits.defaults();

    @ParameterizedTest
    @EnumSource(Format.class)
    void extractedTextIsBoundToTheSuppliedDocumentBytes(Format format) throws Exception {
        byte[] bytes = document(format, "A bounded comparison.", "First revision");
        String hash = sha256(bytes);

        var result = DocumentSnapshotExtractor.extract(bytes, format);

        assertEquals(Status.COMPLETE_EXTRACTED_TEXT, result.status());
        assertTrue(result.snapshot().text().contains("A bounded comparison."));
        assertEquals(hash, result.snapshot().rawSha256());
        assertEquals(bytes.length, result.snapshot().rawByteLength());
        assertFalse(result.limitations().isEmpty());
        assertFalse(result.toString().contains("A bounded comparison."));
        assertFalse(result.snapshot().toString().contains("A bounded comparison."));
        bytes[0] ^= 1;
        assertEquals(hash, result.snapshot().rawSha256());
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void matchingExtractedTextDoesNotEstablishMatchingDocumentBytes(Format format) throws Exception {
        var left = DocumentSnapshotExtractor.extract(document(format, "Same body.", "First revision"), format);
        var right = DocumentSnapshotExtractor.extract(document(format, "Same body.", "Second revision"), format);

        assertEquals(Status.COMPLETE_EXTRACTED_TEXT, left.status());
        assertEquals(Status.COMPLETE_EXTRACTED_TEXT, right.status());
        assertEquals(left.snapshot().text(), right.snapshot().text());
        assertNotEquals(left.snapshot().rawSha256(), right.snapshot().rawSha256());
        var comparison = DocumentTextDiff.compare(left.snapshot(), right.snapshot(), DIFF_LIMITS);
        assertEquals(DocumentTextDiff.Status.COMPLETE, comparison.status());
        assertEquals(DocumentTextDiff.Equality.DIFFERENT, comparison.rawEquality());
        assertEquals(DocumentTextDiff.Equality.EQUAL, comparison.textEquality());
        assertEquals(DocumentTextDiff.Equality.NOT_APPLICABLE, comparison.formatEquality());
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void secondDocumentChangesAreReflected(Format format) throws Exception {
        var left = DocumentSnapshotExtractor.extract(document(format, "Earlier term.", "Revision"), format);
        var right = DocumentSnapshotExtractor.extract(document(format, "Revised term.", "Revision"), format);

        var comparison = DocumentTextDiff.compare(left.snapshot(), right.snapshot(), DIFF_LIMITS);

        assertEquals(DocumentTextDiff.Status.COMPLETE, comparison.status());
        assertEquals(DocumentTextDiff.Equality.DIFFERENT, comparison.textEquality());
        assertFalse(comparison.hunks().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void redactionCannotProduceAUsableEqualitySnapshot(Format format) throws Exception {
        var result = DocumentSnapshotExtractor.extract(
                document(format, "password=internal-value", "Revision"), format);

        assertEquals(Status.INCOMPLETE_INPUT, result.status());
        assertNull(result.snapshot());
        assertFalse(result.toString().contains("internal-value"));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void sourceLimitRejectsBeforeParsing(Format format) throws Exception {
        byte[] bytes = document(format, "Small source.", "Revision");
        var limits = new DocumentSnapshotExtractor.Limits(1, 1024, 10, 10, 100);

        var result = DocumentSnapshotExtractor.extract(bytes, format, limits, DIFF_LIMITS);

        assertEquals(Status.INPUT_LIMIT, result.status());
        assertNull(result.snapshot());
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void textLimitDoesNotReturnASuccessfulPrefix(Format format) throws Exception {
        byte[] bytes = document(format, "The complete sentence exceeds the small limit.", "Revision");
        var limits = new DocumentSnapshotExtractor.Limits(2 * 1024 * 1024, 16 * 1024 * 1024, 512, 256, 3);

        var result = DocumentSnapshotExtractor.extract(bytes, format, limits, DIFF_LIMITS);

        assertEquals(Status.EXTRACTION_LIMIT, result.status());
        assertNull(result.snapshot());
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void corruptInputHasNoComparisonSnapshot(Format format) {
        var result = DocumentSnapshotExtractor.extract("Invalid document".getBytes(StandardCharsets.UTF_8), format);

        assertEquals(Status.INVALID_DOCUMENT, result.status());
        assertNull(result.snapshot());
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void interruptionIsPreserved(Format format) throws Exception {
        byte[] bytes = document(format, "Source.", "Revision");
        try {
            Thread.currentThread().interrupt();
            var result = DocumentSnapshotExtractor.extract(bytes, format);
            assertEquals(Status.CANCELLED, result.status());
            assertNull(result.snapshot());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void archiveExpansionIsBoundedBeforeTheWordParser() throws Exception {
        byte[] bytes = document(Format.DOCX, "Compressed source.", "Revision");
        var limits = new DocumentSnapshotExtractor.Limits(2 * 1024 * 1024, 1, 512, 256, 64_000);

        var result = DocumentSnapshotExtractor.extract(bytes, Format.DOCX, limits, DIFF_LIMITS);

        assertEquals(Status.EXTRACTION_LIMIT, result.status());
        assertNull(result.snapshot());
    }

    @Test
    void archiveEntryCountIsBounded() throws Exception {
        byte[] bytes = document(Format.DOCX, "Source.", "Revision");
        var limits = new DocumentSnapshotExtractor.Limits(2 * 1024 * 1024, 16 * 1024 * 1024, 1, 256, 64_000);

        var result = DocumentSnapshotExtractor.extract(bytes, Format.DOCX, limits, DIFF_LIMITS);

        assertEquals(Status.EXTRACTION_LIMIT, result.status());
    }

    @Test
    void duplicateArchiveNamesAreRejectedBeforeParsing() throws Exception {
        byte[] bytes;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream archive = new ZipOutputStream(output)) {
            for (String name : new String[] { "word/document.xml", "word/document.xm2" }) {
                archive.putNextEntry(new ZipEntry(name));
                archive.write("<root/>".getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
            archive.finish();
            bytes = new String(output.toByteArray(), StandardCharsets.ISO_8859_1)
                    .replace("word/document.xm2", "word/document.xml")
                    .getBytes(StandardCharsets.ISO_8859_1);
        }

        var result = DocumentSnapshotExtractor.extract(bytes, Format.DOCX);

        assertEquals(Status.INVALID_DOCUMENT, result.status());
        assertNull(result.snapshot());
    }

    @Test
    void tighterComparisonLimitsDoNotAdmitPartialExtraction() throws Exception {
        byte[] bytes = document(Format.DOCX, "Complete source.", "Revision");
        var small = new DocumentTextDiff.Limits(2 * 1024 * 1024, 1, 4096, 100_000, 8_000_000, 512, 128_000);

        var result = DocumentSnapshotExtractor.extract(bytes, Format.DOCX,
                DocumentSnapshotExtractor.Limits.defaults(), small);

        assertEquals(Status.INCOMPLETE_INPUT, result.status());
        assertNull(result.snapshot());
    }

    @Test
    void pdfDecodedPageStreamsAreBounded() throws Exception {
        byte[] bytes = document(Format.PDF, "Source.", "Revision");
        var limits = new DocumentSnapshotExtractor.Limits(2 * 1024 * 1024, 1, 512, 256, 64_000);

        var result = DocumentSnapshotExtractor.extract(bytes, Format.PDF, limits, DIFF_LIMITS);

        assertEquals(Status.EXTRACTION_LIMIT, result.status());
    }

    @Test
    void pdfPageCountIsBounded() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(output);
            bytes = output.toByteArray();
        }
        var limits = new DocumentSnapshotExtractor.Limits(2 * 1024 * 1024, 16 * 1024 * 1024, 512, 1, 64_000);

        assertEquals(Status.EXTRACTION_LIMIT,
                DocumentSnapshotExtractor.extract(bytes, Format.PDF, limits, DIFF_LIMITS).status());
    }

    @Test
    void emptyPdfDoesNotEstablishACompleteTextLayer() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            bytes = output.toByteArray();
        }

        var result = DocumentSnapshotExtractor.extract(bytes, Format.PDF);

        assertEquals(Status.NO_TEXT_LAYER, result.status());
        assertNull(result.snapshot());
    }

    @Test
    void emptyWordTextRemainsAValidExtractedRepresentation() throws Exception {
        var result = DocumentSnapshotExtractor.extract(document(Format.DOCX, "", "Revision"), Format.DOCX);

        assertEquals(Status.COMPLETE_EXTRACTED_TEXT, result.status());
        assertTrue(result.snapshot().text().isBlank());
    }

    @Test
    void encryptedPdfIsNotTreatedAsAnEmptyDocument() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            var protection = new StandardProtectionPolicy("owner-value", "reader-value", new AccessPermission());
            protection.setEncryptionKeyLength(128);
            document.protect(protection);
            document.save(output);
            bytes = output.toByteArray();
        }

        var result = DocumentSnapshotExtractor.extract(bytes, Format.PDF);

        assertEquals(Status.ENCRYPTED, result.status());
        assertNull(result.snapshot());
    }

    @Test
    void limitsCannotDisableTheHardAdmissionCaps() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentSnapshotExtractor.Limits(0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentSnapshotExtractor.Limits(2 * 1024 * 1024 + 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentSnapshotExtractor.Limits(1, 16 * 1024 * 1024 + 1L, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentSnapshotExtractor.Limits(1, 1, 513, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentSnapshotExtractor.Limits(1, 1, 1, 257, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentSnapshotExtractor.Limits(1, 1, 1, 1, 64_001));
    }

    private static byte[] document(Format format, String text, String title) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (format == Format.DOCX) {
                try (XWPFDocument document = new XWPFDocument()) {
                    document.getProperties().getCoreProperties().setTitle(title);
                    document.createParagraph().createRun().setText(text);
                    document.write(output);
                }
            } else {
                try (PDDocument document = new PDDocument()) {
                    document.getDocumentInformation().setTitle(title);
                    PDPage page = new PDPage();
                    document.addPage(page);
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        stream.newLineAtOffset(40, 700);
                        stream.showText(text);
                        stream.endText();
                    }
                    document.save(output);
                }
            }
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
