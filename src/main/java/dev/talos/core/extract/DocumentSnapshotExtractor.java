package dev.talos.core.extract;

import dev.talos.core.diff.DocumentTextDiff;
import dev.talos.safety.ProtectedContentSanitizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipInputStream;

/** Captured document bytes are parsed without reopening a source path. */
public final class DocumentSnapshotExtractor {
    public static final String POLICY_VERSION = "document-snapshot-extraction-v1";

    public enum Format { PDF, DOCX }
    public enum Status {
        COMPLETE_EXTRACTED_TEXT, INPUT_LIMIT, EXTRACTION_LIMIT, NO_TEXT_LAYER,
        ENCRYPTED, INVALID_DOCUMENT, INCOMPLETE_INPUT, CANCELLED
    }

    public record Limits(int maxSourceBytes, long maxExpandedBytes, int maxArchiveEntries,
                         int maxPdfPages, int maxTextChars) {
        public Limits {
            if (maxSourceBytes < 1 || maxSourceBytes > 2 * 1024 * 1024
                    || maxExpandedBytes < 1 || maxExpandedBytes > 16 * 1024 * 1024
                    || maxArchiveEntries < 1 || maxArchiveEntries > 512
                    || maxPdfPages < 1 || maxPdfPages > 256
                    || maxTextChars < 1 || maxTextChars > 64_000) {
                throw new IllegalArgumentException("Snapshot extraction limits are outside the supported bounds.");
            }
        }

        public static Limits defaults() {
            return new Limits(2 * 1024 * 1024, 16 * 1024 * 1024, 512, 256, 64_000);
        }
    }

    public record Result(Status status, DocumentTextDiff.Snapshot snapshot, List<String> limitations) {
        public Result {
            Objects.requireNonNull(status);
            limitations = List.copyOf(limitations);
            if ((status == Status.COMPLETE_EXTRACTED_TEXT) != (snapshot != null)) {
                throw new IllegalArgumentException("Extraction status and snapshot are inconsistent.");
            }
        }

        @Override public String toString() {
            return "DocumentSnapshotExtraction[status=" + status + "]";
        }
    }

    private DocumentSnapshotExtractor() {}

    public static Result extract(byte[] suppliedBytes, Format format) {
        return extract(suppliedBytes, format, Limits.defaults(), DocumentTextDiff.Limits.defaults());
    }

    /** Admission, permissions and derived-content handoff remain caller responsibilities. */
    public static Result extract(byte[] suppliedBytes, Format format, Limits limits,
                                 DocumentTextDiff.Limits diffLimits) {
        Objects.requireNonNull(suppliedBytes);
        Objects.requireNonNull(format);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(diffLimits);
        if (Thread.currentThread().isInterrupted()) return failed(Status.CANCELLED, format);
        if (suppliedBytes.length > limits.maxSourceBytes()) return failed(Status.INPUT_LIMIT, format);
        byte[] bytes = suppliedBytes.clone();
        try {
            String text;
            String adapter;
            String version;
            if (format == Format.PDF) {
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    checkCancellation();
                    if (document.isEncrypted()) return failed(Status.ENCRYPTED, format);
                    if (document.getNumberOfPages() > limits.maxPdfPages()) throw new ExtractionLimit();
                    long expanded = 0;
                    for (var page : document.getPages()) {
                        try (InputStream content = page.getContents()) {
                            expanded += consumeBounded(content, limits.maxExpandedBytes() - expanded);
                        }
                    }
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setLineSeparator("\n");
                    LimitedWriter writer = new LimitedWriter(limits.maxTextChars());
                    stripper.writeText(document, writer);
                    text = writer.text();
                    if (text.isBlank()) return failed(Status.NO_TEXT_LAYER, format);
                }
                adapter = "pdfbox";
                version = implementationVersion(PDDocument.class);
            } else {
                checkArchive(bytes, limits);
                try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
                     XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    checkCancellation();
                    text = extractor.getText();
                }
                if (text.length() > limits.maxTextChars()) throw new ExtractionLimit();
                adapter = "poi-docx";
                version = implementationVersion(XWPFDocument.class);
            }
            checkCancellation();
            if (!Objects.equals(text, ProtectedContentSanitizer.sanitizeText(text))) {
                return failed(Status.INCOMPLETE_INPUT, format);
            }
            var provenance = new DocumentTextDiff.ExtractionProvenance(
                    adapter, version, POLICY_VERSION, limitations(format), true, false);
            var snapshot = DocumentTextDiff.extracted(bytes, text, provenance, diffLimits);
            if (snapshot.status() == DocumentTextDiff.Status.CANCELLED) return failed(Status.CANCELLED, format);
            if (snapshot.snapshot() == null) return failed(Status.INCOMPLETE_INPUT, format);
            return new Result(Status.COMPLETE_EXTRACTED_TEXT, snapshot.snapshot(), limitations(format));
        } catch (ExtractionCancelled cancelled) {
            return failed(Status.CANCELLED, format);
        } catch (ExtractionLimit limit) {
            return failed(Status.EXTRACTION_LIMIT, format);
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException encrypted) {
            return failed(Status.ENCRYPTED, format);
        } catch (IOException | RuntimeException invalid) {
            if (Thread.currentThread().isInterrupted()) return failed(Status.CANCELLED, format);
            return failed(Status.INVALID_DOCUMENT, format);
        }
    }

    private static void checkArchive(byte[] bytes, Limits limits) throws IOException {
        long expanded = 0;
        int entries = 0;
        boolean documentFound = false;
        var names = new HashSet<String>();
        try (var archive = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                checkCancellation();
                if (++entries > limits.maxArchiveEntries()) throw new ExtractionLimit();
                if (entry.getName().length() > 1024 || !names.add(entry.getName())) {
                    throw new IOException("Invalid document archive.");
                }
                documentFound |= entry.getName().equals("word/document.xml");
                expanded += consumeBounded(archive, limits.maxExpandedBytes() - expanded);
                archive.closeEntry();
            }
        }
        if (!documentFound) throw new IOException("Document part is unavailable.");
    }

    private static long consumeBounded(InputStream input, long remaining) throws IOException {
        byte[] buffer = new byte[4096];
        long consumed = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkCancellation();
            consumed += count;
            if (consumed > remaining) throw new ExtractionLimit();
        }
        return consumed;
    }

    private static void checkCancellation() throws ExtractionCancelled {
        if (Thread.currentThread().isInterrupted()) throw new ExtractionCancelled();
    }

    private static String implementationVersion(Class<?> type) {
        String version = type.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "unknown" : version;
    }

    private static Result failed(Status status, Format format) {
        return new Result(status, null, limitations(format));
    }

    private static List<String> limitations(Format format) {
        return format == Format.PDF
                ? List.of("Only extracted text is compared. PDF visual order, layout and images are not established.",
                    "Page content streams are bounded, not every decoded PDF resource. No hard parser deadline is established.")
                : List.of("Only extracted text is compared. DOCX layout, images, comments and tracked changes are not established.",
                    "Text length is checked after Word extraction. Input and archive limits do not establish a hard parser deadline.");
    }

    private static final class LimitedWriter extends Writer {
        private final int limit;
        private final StringBuilder text = new StringBuilder();

        private LimitedWriter(int limit) { this.limit = limit; }

        @Override public void write(char[] buffer, int offset, int length) throws IOException {
            checkCancellation();
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length > limit - text.length()) throw new ExtractionLimit();
            text.append(buffer, offset, length);
        }

        @Override public void flush() throws IOException { checkCancellation(); }
        @Override public void close() {}
        private String text() { return text.toString(); }
    }

    private static final class ExtractionLimit extends IOException {}
    private static final class ExtractionCancelled extends IOException {}
}
