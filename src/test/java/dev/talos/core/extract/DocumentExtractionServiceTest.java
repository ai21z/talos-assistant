package dev.talos.core.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.spi.types.ControlFlowFailure;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentExtractionServiceTest {

    @Test
    void service_uses_neutral_sanitizer_and_core_private_document_content_policy() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/talos/core/extract/DocumentExtractionService.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertTrue(source.contains("import dev.talos.safety.ProtectedContentSanitizer;"), source);
        assertTrue(source.contains("import dev.talos.core.privacy.PrivateDocumentContentPolicy;"), source);
        assertFalse(source.contains("import dev.talos.runtime.policy.PrivateDocumentPolicy;"), source);
        assertTrue(source.contains("PrivateDocumentContentPolicy.modelHandoffAllowed("), source);
        assertFalse(source.contains("import dev.talos.runtime.policy.ProtectedContentPolicy;"), source);
        assertFalse(baseline.contains(
                        "core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.ProtectedContentPolicy"),
                baseline);
        assertFalse(baseline.contains(
                        "core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.PrivateDocumentPolicy"),
                baseline);
    }

    @Test
    void text_file_extraction_returns_sanitized_safe_text(@TempDir Path workspace) throws Exception {
        Path notes = workspace.resolve("notes.txt");
        Files.writeString(notes, "hello\nAPI_TOKEN=t267-token-should-not-appear\n");
        DocumentExtractionService service = new DocumentExtractionService(new Config(null));

        DocumentExtractionResult result = service.extract(DocumentExtractionRequest.read(notes, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertTrue(result.safeText().contains("hello"));
        assertTrue(result.safeText().contains("API_TOKEN=[redacted]"));
        assertFalse(result.safeText().contains("t267-token-should-not-appear"));
        assertEquals(DocumentExtractionIntent.READ, result.intent());
    }

    @Test
    void disabled_pdf_returns_structured_disabled_status_without_raw_text(@TempDir Path workspace) throws Exception {
        Path pdf = workspace.resolve("report.pdf");
        Files.write(pdf, new byte[] { '%', 'P', 'D', 'F' });
        DocumentExtractionService service = new DocumentExtractionService(extractionDisabled());

        DocumentExtractionResult result = service.extract(DocumentExtractionRequest.read(pdf, workspace));

        assertEquals(DocumentExtractionStatus.UNSUPPORTED_DISABLED, result.status());
        assertEquals(FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_DISABLED, result.capability());
        assertTrue(result.safeText().isBlank());
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("not enabled")));
    }

    @Test
    void result_serialization_omits_raw_parser_text(@TempDir Path workspace) throws Exception {
        Path notes = workspace.resolve("notes.txt");
        Files.writeString(notes, "PRIVATE_MARKER = DO_NOT_LEAK_SERIALIZATION\n");
        DocumentExtractionService service = new DocumentExtractionService(new Config(null));

        DocumentExtractionResult result = service.extract(DocumentExtractionRequest.read(notes, workspace));
        String json = new ObjectMapper().writeValueAsString(result);

        assertFalse(json.contains("DO_NOT_LEAK_SERIALIZATION"), json);
        assertFalse(json.toLowerCase().contains("raw"), json);
        assertTrue(json.contains("[redacted]"), json);
    }

    @Test
    void image_without_ocr_reports_ocr_unavailable(@TempDir Path workspace) throws Exception {
        Path image = workspace.resolve("scan.png");
        Files.write(image, new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        DocumentExtractionService service = new DocumentExtractionService(new Config(null));

        DocumentExtractionResult result = service.extract(DocumentExtractionRequest.read(image, workspace));

        assertEquals(DocumentExtractionStatus.OCR_UNAVAILABLE, result.status());
        assertEquals(FileCapabilityPolicy.Capability.OCR_REQUIRED_DISABLED, result.capability());
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("OCR")));
    }

    @Test
    void private_mode_document_extraction_is_not_model_handoff_by_default(@TempDir Path workspace) throws Exception {
        Path docx = workspace.resolve("medical-notes.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Patient Name: Eleni Nikolaou");
            try (OutputStream out = Files.newOutputStream(docx)) {
                doc.write(out);
            }
        }

        DocumentExtractionResult result = new DocumentExtractionService(privateModeConfig())
                .extract(DocumentExtractionRequest.read(docx, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertFalse(result.safeText().contains("Eleni Nikolaou"), result.safeText());
        assertTrue(result.safeText().contains("[redacted-private-document-canary]"), result.safeText());
        assertFalse(result.modelHandoffAllowed(),
                "ordinary extracted document text must default to local-display-only in private mode");
    }

    @Test
    void ocrInterruptionKillsChildAndPropagatesWithInterruptFlag(@TempDir Path workspace) throws Exception {
        Path image = workspace.resolve("scan.png");
        Path pidFile = workspace.resolve("ocr-child.pid");
        Files.write(image, new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        Config cfg = ocrConfig(List.of(
                "-cp",
                System.getProperty("java.class.path"),
                BlockingOcrCli.class.getName(),
                pidFile.toString()));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                new DocumentExtractionService(cfg)
                        .extract(DocumentExtractionRequest.read(image, workspace));
            } catch (Throwable throwable) {
                failure.set(throwable);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "document-ocr-interruption-test");

        long pid = -1;
        try {
            caller.start();
            pid = awaitPid(pidFile);
            caller.interrupt();
            caller.join(10_000);

            assertFalse(caller.isAlive(), "interrupted OCR extraction must terminate promptly");
            assertNotNull(failure.get(), "interruption must propagate instead of becoming OCR_UNAVAILABLE");
            assertEquals(ControlFlowFailure.Kind.INTERRUPTION,
                    ControlFlowFailure.classify(failure.get()));
            assertTrue(interrupted.get(), "caller interrupt status must be restored before propagation");
            assertFalse(awaitNotAlive(pid), "OCR child process must be forcibly terminated");
        } finally {
            if (pid > 0) ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            if (caller.isAlive()) {
                caller.interrupt();
                caller.join(5_000);
            }
        }
    }

    @Test
    void directOcrCancellationPropagates(@TempDir Path workspace) throws Exception {
        Path image = workspace.resolve("scan.png");
        Files.write(image, new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        CancellationException cancellation = new CancellationException("stop OCR");
        DocumentExtractionService service = new DocumentExtractionService(
                ocrConfig(List.of("{input}")),
                builder -> { throw cancellation; });

        CancellationException thrown = assertThrows(
                CancellationException.class,
                () -> service.extract(DocumentExtractionRequest.read(image, workspace)));

        assertSame(cancellation, thrown);
    }

    @Test
    void wrappedOcrInterruptionPropagatesAndRestoresFlag(@TempDir Path workspace) throws Exception {
        Path image = workspace.resolve("scan.png");
        Files.write(image, new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        RuntimeException wrapper = new RuntimeException(new InterruptedException("stop OCR"));
        DocumentExtractionService service = new DocumentExtractionService(
                ocrConfig(List.of("{input}")),
                builder -> { throw wrapper; });

        try {
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> service.extract(DocumentExtractionRequest.read(image, workspace)));

            assertSame(wrapper, thrown);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void boundedWrapperDepthOcrInterruptionStillPropagates(@TempDir Path workspace) throws Exception {
        Path image = workspace.resolve("scan.png");
        Files.write(image, new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        RuntimeException wrapper = boundedDepthWrapper(new InterruptedException("stop OCR"));
        DocumentExtractionService service = new DocumentExtractionService(
                ocrConfig(List.of("{input}")),
                builder -> { throw wrapper; });

        try {
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> service.extract(DocumentExtractionRequest.read(image, workspace)));

            assertSame(wrapper, thrown);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void typedValidationBarrierRemainsOcrUnavailable(@TempDir Path workspace) throws Exception {
        Path image = workspace.resolve("scan.png");
        Files.write(image, new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        RuntimeException wrapper = new RuntimeException(
                new IllegalArgumentException(new InterruptedException("not caller interruption")));
        DocumentExtractionService service = new DocumentExtractionService(
                ocrConfig(List.of("{input}")),
                builder -> { throw wrapper; });

        DocumentExtractionResult result = service.extract(DocumentExtractionRequest.read(image, workspace));

        assertEquals(DocumentExtractionStatus.OCR_UNAVAILABLE, result.status());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void directOcrInterruptionPropagatesAndRestoresFlag(@TempDir Path workspace) throws Exception {
        Path image = workspace.resolve("scan.png");
        Files.write(image, new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        InterruptedException interruption = new InterruptedException("stop OCR");
        DocumentExtractionService service = new DocumentExtractionService(
                ocrConfig(List.of("{input}")),
                builder -> { throw interruption; });

        try {
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> service.extract(DocumentExtractionRequest.read(image, workspace)));

            assertSame(interruption, thrown.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void ordinaryOcrStartupFailureRemainsUnavailable(@TempDir Path workspace) throws Exception {
        Path image = workspace.resolve("scan.png");
        Files.write(image, new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        DocumentExtractionService service = new DocumentExtractionService(
                ocrConfig(List.of("{input}")),
                builder -> { throw new java.io.IOException("ordinary OCR failure"); });

        DocumentExtractionResult result = service.extract(DocumentExtractionRequest.read(image, workspace));

        assertEquals(DocumentExtractionStatus.OCR_UNAVAILABLE, result.status());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.code().equals("ocr-unavailable")));
    }

    private static Config extractionDisabled() {
        Config cfg = new Config(null);
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.FALSE);
        cfg.data.put("document_extraction", documentExtraction);
        return cfg;
    }

    private static Config privateModeConfig() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of("mode", "private")));
        return cfg;
    }

    private static Config ocrConfig(List<String> args) {
        Config cfg = new Config(null);
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        Map<String, Object> ocr = new LinkedHashMap<>();
        ocr.put("enabled", Boolean.TRUE);
        ocr.put("command", javaExecutable());
        ocr.put("args", args);
        ocr.put("timeout_ms", 20_000L);
        documentExtraction.put("image_ocr", ocr);
        cfg.data.put("document_extraction", documentExtraction);
        return cfg;
    }

    private static String javaExecutable() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("windows")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    public static final class BlockingOcrCli {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(30_000);
        }
    }

    private static long awaitPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(pidFile)) {
                String value = Files.readString(pidFile).strip();
                if (!value.isBlank()) return Long.parseLong(value);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("OCR child process did not publish its pid");
    }

    private static boolean awaitNotAlive(long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) return false;
            Thread.sleep(20);
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static RuntimeException boundedDepthWrapper(Throwable leaf) {
        Throwable current = leaf;
        for (int i = 0; i < 64; i++) current = new RuntimeException(current);
        return (RuntimeException) current;
    }
}
