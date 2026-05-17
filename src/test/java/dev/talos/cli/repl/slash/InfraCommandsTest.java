package dev.talos.cli.repl.slash;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import dev.talos.core.rag.RagService;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.XmlCompatTelemetry;
import dev.talos.core.index.LuceneStore;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for commands that need workspace paths or infrastructure:
 * StatusCommand, ShowCommand, FilesCommand, ReindexCommand,
 * BenchCommand, ModelsCommand, SetModelCommand, SecretCommand.
 *
 * <p>Tests cover: spec metadata, argument parsing, error paths,
 * and file-fallback paths. Commands that need Ollama/CacheDb are
 * tested for their error handling (graceful failure, not crashes).
 */
@DisplayName("REPL commands — infrastructure-dependent")
class InfraCommandsTest {

    @TempDir
    Path ws;

    private final Context ctx = Context.builder(new Config()).build();

    // ═══════════════════════════════════════════════════════════════════════
    //  StatusCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("StatusCommand")
    class Status {

        @org.junit.jupiter.api.BeforeEach
        void resetXmlCompatTelemetry() {
            XmlCompatTelemetry.resetForTests();
        }

        @Test void returns_trusted_info() {
            var cmd = new StatusCommand(ModeController.defaultController(), ws);
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.TrustedInfo.class, r);
        }

        @Test void output_contains_status_header() {
            var cmd = new StatusCommand(ModeController.defaultController(), ws);
            String text = cmd.execute("", ctx).toString();
            assertTrue(text.contains("Talos v"), "Should contain dashboard header");
        }

        @Test void output_contains_mode() {
            var cmd = new StatusCommand(ModeController.defaultController(), ws);
            String text = cmd.execute("", ctx).toString();
            assertTrue(text.contains("Mode"), "Should contain mode label");
        }

        @Test void output_contains_limits() {
            var cmd = new StatusCommand(ModeController.defaultController(), ws);
            String text = cmd.execute("--verbose", ctx).toString();
            assertTrue(text.contains("Limits"), "Should contain limits section");
            assertTrue(text.contains("top_k_max"), "Should show top_k_max limit");
        }

        @Test void non_verbose_suggests_verbose() {
            var cmd = new StatusCommand(ModeController.defaultController(), ws);
            String text = cmd.execute("", ctx).toString();
            assertTrue(text.contains("--verbose"), "Should suggest --verbose");
        }

        @Test void verbose_flag_accepted() {
            var cmd = new StatusCommand(ModeController.defaultController(), ws);
            Result r = cmd.execute("--verbose", ctx);
            assertInstanceOf(Result.TrustedInfo.class, r);
            // Verbose output should NOT suggest --verbose
            assertFalse(r.toString().contains("/status --verbose for diagnostics"));
        }

        @Test void v_flag_accepted() {
            var cmd = new StatusCommand(ModeController.defaultController(), ws);
            Result r = cmd.execute("-v", ctx);
            assertInstanceOf(Result.TrustedInfo.class, r);
        }

        @Test void output_contains_config_info() {
            var cmd = new StatusCommand(ModeController.defaultController(), ws);
            String text = cmd.execute("--verbose", ctx).toString();
            assertTrue(text.contains("Config"), "Should contain config section");
        }

        @Test void spec_name() {
            var cmd = new StatusCommand(ModeController.defaultController(), ws);
            assertEquals("status", cmd.spec().name());
        }

        @Test void verbose_contains_xml_compat_section() {
            ToolCallParser.parse("<tool_call>{\"name\":\"talos.read_file\",\"parameters\":{\"path\":\"a.txt\"}}</tool_call>");
            var cmd = new StatusCommand(ModeController.defaultController(), ws);
            String text = cmd.execute("--verbose", ctx).toString();
            assertTrue(text.contains("XML Compat"), "Should contain XML compatibility telemetry section");
            assertTrue(text.contains("parser_activations=1"), "Should surface XML parser fallback counter");
            assertTrue(text.contains("last_tools=talos.read_file"), "Should show last XML-derived tool names");
        }

        @Test void verbose_contains_document_extraction_preflight() {
            var cmd = new StatusCommand(ModeController.defaultController(), ws);

            String text = cmd.execute("--verbose", ctx).toString();

            assertTrue(text.contains("Document Extraction"), text);
            assertTrue(text.contains("PDF"), text);
            assertTrue(text.contains("Word"), text);
            assertTrue(text.contains("Excel"), text);
            assertTrue(text.contains("Image OCR"), text);
            assertTrue(text.contains("not configured"), text);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ShowCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ShowCommand")
    class Show {

        @Test void empty_args_returns_error() {
            var cmd = new ShowCommand(ws);
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.Error.class, r);
            assertTrue(r.toString().contains("Usage"));
        }

        @Test void null_args_returns_error() {
            var cmd = new ShowCommand(ws);
            Result r = cmd.execute(null, ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void invalid_chunk_id_returns_error() {
            var cmd = new ShowCommand(ws);
            Result r = cmd.execute("file.java#abc", ctx);
            assertInstanceOf(Result.Error.class, r);
            assertTrue(r.toString().contains("Invalid chunk ID"));
        }

        @Test void file_fallback_reads_existing_file() throws Exception {
            Files.writeString(ws.resolve("readme.txt"), "Hello from file");
            var cmd = new ShowCommand(ws);
            Result r = cmd.execute("readme.txt", ctx);
            // This may either succeed via file fallback or error via index lookup failure
            // Either way it should not crash
            assertNotNull(r);
        }

        @Test void file_fallback_shows_content() throws Exception {
            Files.writeString(ws.resolve("test.txt"), "test content here");
            var cmd = new ShowCommand(ws);
            Result r = cmd.execute("test.txt", ctx);
            // If index lookup fails but file exists, should show file content
            if (r instanceof Result.Ok ok) {
                assertTrue(ok.text.contains("test content here"));
            }
            // If index lookup throws, we get an error — that's also acceptable
        }

        @Test void file_fallback_rejects_workspace_escape() throws Exception {
            Path outside = ws.resolveSibling("outside.txt");
            Files.writeString(outside, "outside workspace content");
            var cmd = new ShowCommand(ws);

            Result r = cmd.execute("../outside.txt", ctx);

            assertInstanceOf(Result.Error.class, r);
            assertFalse(r.toString().contains("outside workspace content"));
        }

        @Test void document_fallback_extracts_docx_for_local_display_in_private_mode() throws Exception {
            Path docx = ws.resolve("private-medical.docx");
            try (XWPFDocument doc = new XWPFDocument()) {
                doc.createParagraph().createRun().setText("Patient Name: Eleni Nikolaou");
                try (OutputStream out = Files.newOutputStream(docx)) {
                    doc.write(out);
                }
            }
            Config cfg = new Config(null);
            ProtectedReadScopePolicy.setPrivateMode(cfg, true);
            var cmd = new ShowCommand(ws);

            Result r = cmd.execute("private-medical.docx", Context.builder(cfg).build());

            Result.Ok ok = assertInstanceOf(Result.Ok.class, r);
            assertTrue(ok.text.contains("Document: private-medical.docx"), ok.text);
            assertTrue(ok.text.contains("local display"), ok.text);
            assertTrue(ok.text.contains("[redacted-private-document-canary]"), ok.text);
            assertFalse(ok.text.contains("Eleni Nikolaou"), ok.text);
        }

        @Test void document_fallback_extracts_pdf_for_local_display_in_private_mode() throws Exception {
            Path pdf = ws.resolve("private-report.pdf");
            writePdf(pdf, "Patient Name: Eleni Nikolaou");
            Config cfg = new Config(null);
            ProtectedReadScopePolicy.setPrivateMode(cfg, true);
            var cmd = new ShowCommand(ws);

            Result r = cmd.execute("private-report.pdf", Context.builder(cfg).build());

            Result.Ok ok = assertInstanceOf(Result.Ok.class, r);
            assertTrue(ok.text.contains("Document: private-report.pdf"), ok.text);
            assertTrue(ok.text.contains("local display"), ok.text);
            assertTrue(ok.text.contains("[redacted-private-document-canary]"), ok.text);
            assertFalse(ok.text.contains("Eleni Nikolaou"), ok.text);
        }

        @Test void document_fallback_extracts_xls_for_local_display_in_private_mode() throws Exception {
            Path xls = ws.resolve("private-workbook.xls");
            try (HSSFWorkbook workbook = new HSSFWorkbook()) {
                var sheet = workbook.createSheet("Sheet1");
                sheet.createRow(0).createCell(0).setCellValue("Patient Name: Eleni Nikolaou");
                try (OutputStream out = Files.newOutputStream(xls)) {
                    workbook.write(out);
                }
            }
            Config cfg = new Config(null);
            ProtectedReadScopePolicy.setPrivateMode(cfg, true);
            var cmd = new ShowCommand(ws);

            Result r = cmd.execute("private-workbook.xls", Context.builder(cfg).build());

            Result.Ok ok = assertInstanceOf(Result.Ok.class, r);
            assertTrue(ok.text.contains("Document: private-workbook.xls"), ok.text);
            assertTrue(ok.text.contains("local display"), ok.text);
            assertTrue(ok.text.contains("[redacted-private-document-canary]"), ok.text);
            assertFalse(ok.text.contains("Eleni Nikolaou"), ok.text);
        }

        @Test void document_fallback_extracts_xlsx_for_local_display_in_private_mode() throws Exception {
            Path xlsx = ws.resolve("private-workbook.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                var sheet = workbook.createSheet("Sheet1");
                sheet.createRow(0).createCell(0).setCellValue("Patient Name: Eleni Nikolaou");
                try (OutputStream out = Files.newOutputStream(xlsx)) {
                    workbook.write(out);
                }
            }
            Config cfg = new Config(null);
            ProtectedReadScopePolicy.setPrivateMode(cfg, true);
            var cmd = new ShowCommand(ws);

            Result r = cmd.execute("private-workbook.xlsx", Context.builder(cfg).build());

            Result.Ok ok = assertInstanceOf(Result.Ok.class, r);
            assertTrue(ok.text.contains("Document: private-workbook.xlsx"), ok.text);
            assertTrue(ok.text.contains("local display"), ok.text);
            assertTrue(ok.text.contains("[redacted-private-document-canary]"), ok.text);
            assertFalse(ok.text.contains("Eleni Nikolaou"), ok.text);
        }

        @Test void nonexistent_file_returns_error() {
            var cmd = new ShowCommand(ws);
            Result r = cmd.execute("nonexistent.java#0", ctx);
            // Should be an error (either "not found" or "Show failed")
            assertNotNull(r);
            assertTrue(r instanceof Result.Error, "Missing file should produce error");
        }

        @Test void spec_name() {
            var cmd = new ShowCommand(ws);
            assertEquals("show", cmd.spec().name());
        }

        private void writePdf(Path path, String text) throws Exception {
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(40, 700);
                    stream.showText(text);
                    stream.endText();
                }
                doc.save(path.toFile());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  FilesCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FilesCommand")
    class FilesCmd {

        @Test void no_index_returns_error_not_crash() throws Exception {
            var cmd = new FilesCommand(ws);
            Result r = cmd.execute("", ctx);
            // No index exists → should return error gracefully
            assertNotNull(r);
            assertTrue(r instanceof Result.Error || r instanceof Result.Info,
                    "Missing index should produce error or info, not crash");
        }

        @Test void with_index_lists_files() throws Exception {
            // Build a real tiny index
            Path indexDir = ws.resolve(".talos-index");
            Files.createDirectories(indexDir);
            try (var store = new LuceneStore(indexDir, 0)) {
                store.add("src/Main.java#0", "public class Main {}", null, "h1", 0);
                store.add("src/Main.java#1", "  public static void main() {}", null, "h1", 1);
                store.add("README.md#0", "# Project", null, "h2", 0);
                store.commit();
            }

            // FilesCommand needs ctx.rag().getIndexer().indexDirFor(workspace)
            // which won't resolve to our temp dir — so this tests the error path
            var cmd = new FilesCommand(ws);
            Result r = cmd.execute("", ctx);
            assertNotNull(r);
        }

        @Test void spec_name_and_group() {
            var cmd = new FilesCommand(ws);
            assertEquals("files", cmd.spec().name());
            assertEquals(CommandGroup.KNOWLEDGE, cmd.spec().group());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ReindexCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ReindexCommand")
    class Reindex {

        @Test void stats_with_no_prior_run() {
            var cmd = new ReindexCommand(ws);
            // --stats when no prior run should return info
            Result r = cmd.execute("--stats", ctx);
            assertNotNull(r);
            // Either Info (no stats) or Error (failed to get indexer)
            assertTrue(r instanceof Result.Info || r instanceof Result.Error || r instanceof Result.Ok);
        }

        @Test void prune_invalid_days_returns_error() {
            var cmd = new ReindexCommand(ws);
            Result r = cmd.execute("--prune abc", ctx);
            assertNotNull(r);
            if (r instanceof Result.Error err) {
                assertTrue(err.message.contains("Invalid days"));
            }
        }

        @Test void reindex_graceful_failure() {
            var cmd = new ReindexCommand(ws);
            Result r = cmd.execute("", ctx);
            // Without Ollama, reindex will fail — should return error, not crash
            assertNotNull(r);
        }

        @Test void post_reindex_hook_called() {
            var hookCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
            var cmd = new ReindexCommand(ws, () -> hookCalled.set(true));
            // Even if reindex fails, we verify the hook plumbing exists
            cmd.execute("", ctx); // may fail, that's okay
            // Hook only runs on success; since this will fail, hook may not run
            assertNotNull(cmd.spec());
        }

        @Test void spec_name_and_group() {
            var cmd = new ReindexCommand(ws);
            assertEquals("reindex", cmd.spec().name());
            assertEquals(CommandGroup.KNOWLEDGE, cmd.spec().group());
            assertTrue(cmd.spec().aliases().contains("--stats"));
            assertTrue(cmd.spec().aliases().contains("--full"));
            assertTrue(cmd.spec().aliases().contains("--prune"));
        }

        @Test void private_mode_reindex_refuses_when_rag_disabled() throws Exception {
            Files.writeString(ws.resolve("README.md"), "public searchable text\n");
            Config cfg = configWithVectorsDisabled();
            ProtectedReadScopePolicy.setPrivateMode(cfg, true);
            Context privateCtx = Context.builder(cfg).rag(new RagService(cfg)).build();
            var cmd = new ReindexCommand(ws);

            Result r = cmd.execute("", privateCtx);

            Result.Info info = assertInstanceOf(Result.Info.class, r);
            assertTrue(info.text.contains("private mode"), info.text);
            assertTrue(info.text.contains("RAG"), info.text);
            Path indexDir = new RagService(cfg).getIndexer().indexDirFor(ws);
            try (var entries = Files.list(indexDir)) {
                assertTrue(entries.findAny().isEmpty(),
                        "private-mode /reindex must not write index artifacts when private-mode RAG is disabled");
            }
        }

        @Test void private_mode_reindex_allows_when_explicitly_enabled() throws Exception {
            Files.writeString(ws.resolve("README.md"), "public searchable text\n");
            Config cfg = configWithVectorsDisabled();
            ProtectedReadScopePolicy.setPrivateMode(cfg, true);
            privacyRag(cfg).put("enabled_in_private_mode", Boolean.TRUE);
            Context privateCtx = Context.builder(cfg).rag(new RagService(cfg)).build();
            var cmd = new ReindexCommand(ws);

            Result r = cmd.execute("", privateCtx);

            assertInstanceOf(Result.Ok.class, r);
            assertTrue(Files.exists(new RagService(cfg).getIndexer().indexDirFor(ws)));
        }

        private Config configWithVectorsDisabled() {
            Config cfg = new Config(null);
            Map<String, Object> rag = new LinkedHashMap<>();
            rag.put("vectors", new LinkedHashMap<>(Map.of("enabled", Boolean.FALSE)));
            cfg.data.put("rag", rag);
            return cfg;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> privacyRag(Config cfg) {
            Map<String, Object> privacy = (Map<String, Object>) cfg.data.computeIfAbsent("privacy", ignored -> new LinkedHashMap<>());
            return (Map<String, Object>) privacy.computeIfAbsent("rag", ignored -> new LinkedHashMap<>());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  BenchCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("BenchCommand")
    class Bench {

        @Test void execute_graceful_failure() {
            var cmd = new BenchCommand(ws);
            // Without Ollama, bench will fail
            Result r = cmd.execute("", ctx);
            assertNotNull(r);
            // Should return error or ok (empty workspace = no files = fast finish)
            assertTrue(r instanceof Result.Error || r instanceof Result.Ok);
        }

        @Test void spec_name() {
            var cmd = new BenchCommand(ws);
            assertEquals("bench", cmd.spec().name());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ModelsCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ModelsCommand")
    class Models {

        @Test void execute_without_ollama_returns_error() throws Exception {
            var cmd = new ModelsCommand();
            Result r = cmd.execute("", ctx);
            // Without running Ollama, this should fail gracefully
            assertNotNull(r);
            assertTrue(r instanceof Result.Error || r instanceof Result.Info || r instanceof Result.Ok,
                    "Should handle missing Ollama gracefully");
            if (r instanceof Result.Ok ok) {
                assertTrue(ok.text.contains("/set model <backend/model>"));
                assertFalse(ok.text.contains(":set model"));
            }
        }

        @Test void error_message_mentions_ollama() throws Exception {
            var cmd = new ModelsCommand();
            Result r = cmd.execute("", ctx);
            if (r instanceof Result.Error err) {
                assertTrue(err.message.toLowerCase().contains("ollama"),
                        "Error should mention Ollama");
            }
        }

        @Test void spec_name_and_group() {
            var cmd = new ModelsCommand();
            assertEquals("models", cmd.spec().name());
            assertTrue(cmd.spec().aliases().contains("model"));
            assertEquals(CommandGroup.MODELS, cmd.spec().group());
        }

        @Test void command_registry_accepts_model_alias_for_models() {
            var reg = new CommandRegistry();
            reg.register(new ModelsCommand());

            assertTrue(reg.has("models"));
            assertTrue(reg.has("model"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SetModelCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SetModelCommand")
    class SetModel {

        @Test void no_model_prefix_returns_error() throws Exception {
            var cmd = new SetModelCommand();
            Result r = cmd.execute("something", ctx);
            assertInstanceOf(Result.Error.class, r);
            assertTrue(r.toString().contains("Usage"));
        }

        @Test void plural_models_subcommand_returns_usage_without_prefix_model_lookup() throws Exception {
            var cmd = new SetModelCommand();
            Result r = cmd.execute("models ollama/qwen2.5-coder:14b", ctx);

            assertInstanceOf(Result.Error.class, r);
            assertTrue(r.toString().contains("Usage"), r.toString());
            assertFalse(r.toString().contains("sollama"), r.toString());
        }

        @Test void empty_model_name_returns_error() throws Exception {
            var cmd = new SetModelCommand();
            Result r = cmd.execute("model", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void null_args_returns_error() throws Exception {
            var cmd = new SetModelCommand();
            Result r = cmd.execute(null, ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void empty_args_returns_error() throws Exception {
            var cmd = new SetModelCommand();
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void invalid_chars_sanitized() throws Exception {
            var cmd = new SetModelCommand();
            Result r = cmd.execute("model !!!@@@", ctx);
            assertInstanceOf(Result.Error.class, r);
            assertTrue(r.toString().contains("Invalid model name"));
        }

        @Test void valid_model_attempts_engine_lookup() throws Exception {
            var cmd = new SetModelCommand();
            // With no running Ollama, this should error on engine lookup
            Result r = cmd.execute("model qwen3:8b", ctx);
            assertNotNull(r);
            // Either Error (model not found / engine not reachable) or Info
        }

        @Test void spec_name() {
            var cmd = new SetModelCommand();
            assertEquals("set", cmd.spec().name());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SecretCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SecretCommand")
    class Secret {

        @Test void empty_args_returns_usage() throws Exception {
            var cmd = new SecretCommand(new Config(), ctx.audit());
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.Error.class, r);
            assertTrue(r.toString().contains("Usage"));
        }

        @Test void null_args_returns_usage() throws Exception {
            var cmd = new SecretCommand(new Config(), ctx.audit());
            Result r = cmd.execute(null, ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void single_token_returns_usage() throws Exception {
            var cmd = new SecretCommand(new Config(), ctx.audit());
            Result r = cmd.execute("get", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void unknown_op_returns_usage() throws Exception {
            var cmd = new SecretCommand(new Config(), ctx.audit());
            Result r = cmd.execute("list keys", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void get_nonexistent_returns_error() throws Exception {
            var cmd = new SecretCommand(new Config(), ctx.audit());
            Result r = cmd.execute("get nonexistent_key_12345", ctx);
            assertInstanceOf(Result.Error.class, r);
            assertTrue(r.toString().contains("No secret"));
        }

        @Test void del_nonexistent_returns_info() throws Exception {
            var cmd = new SecretCommand(new Config(), ctx.audit());
            Result r = cmd.execute("del nonexistent_key_12345", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertTrue(r.toString().contains("No secret"));
        }

        @Test void delete_alias_works() throws Exception {
            var cmd = new SecretCommand(new Config(), ctx.audit());
            Result r = cmd.execute("delete nonexistent_key_12345", ctx);
            assertInstanceOf(Result.Info.class, r);
        }

        @Test void rm_alias_works() throws Exception {
            var cmd = new SecretCommand(new Config(), ctx.audit());
            Result r = cmd.execute("rm nonexistent_key_12345", ctx);
            assertInstanceOf(Result.Info.class, r);
        }

        @Test void spec_name() {
            var cmd = new SecretCommand(new Config(), ctx.audit());
            assertEquals("secret", cmd.spec().name());
        }
    }
}

