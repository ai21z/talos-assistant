package dev.talos.runtime.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedReadScopeIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    @AfterEach
    void clearTraceCapture() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
    }

    @Test
    void private_mode_approved_protected_read_is_withheld_from_model_context() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV\n");

        Config cfg = new Config(null);
        cfg.data.put("privacy", Map.of("mode", "private"));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("I cannot see the raw protected value.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read .env and tell me the value."));

        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                messages,
                workspace,
                ctx);

        String transcript = messages.toString();
        assertFalse(transcript.contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), transcript);
        assertFalse(transcript.contains("API_TOKEN="), transcript);
        assertTrue(transcript.contains("withheld from model context"), transcript);
        assertTrue(transcript.contains("LOCAL_DISPLAY_ONLY") || transcript.contains("withheld from model context"), transcript);
        assertFalse(result.finalAnswer().contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), result.finalAnswer());
    }

    @Test
    void developer_mode_approved_protected_read_can_reach_model_context_explicit_risk() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV\n");

        Config cfg = new Config(null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("The approved file contained FILE_DISCOVERED_CANARY_SCOPE_ENV.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read .env and tell me the value."));

        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                messages,
                workspace,
                ctx);

        String transcript = messages.toString();
        assertTrue(transcript.contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), transcript);
        assertTrue(result.finalAnswer().contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), result.finalAnswer());
    }

    @Test
    void private_mode_send_to_model_requires_explicit_opt_in() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV\n");

        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "protected_read", new LinkedHashMap<>(Map.of(
                        "default_scope", "SEND_TO_MODEL_CONTEXT",
                        "allow_send_to_model", false)))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("I cannot see the raw protected value.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read .env and tell me the value."));

        loop.run("{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                messages, workspace, ctx);

        assertFalse(messages.toString().contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), messages.toString());
        assertTrue(messages.toString().contains("withheld from model context"), messages.toString());
    }

    @Test
    void private_mode_docx_extraction_is_withheld_from_model_context() throws Exception {
        Path docx = workspace.resolve("medical-notes.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Patient Name: Eleni Nikolaou");
            try (OutputStream out = Files.newOutputStream(docx)) {
                doc.write(out);
            }
        }

        Config cfg = privateModeConfig();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, fixedApprovalGate(ApprovalResponse.DENIED), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("I cannot see the raw private document text.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read medical-notes.docx and tell me the patient name."));

        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"medical-notes.docx\"}}",
                messages,
                workspace,
                ctx);

        String transcript = messages.toString();
        assertFalse(transcript.contains("Patient Name: Eleni Nikolaou"), transcript);
        assertTrue(transcript.contains("withheld from model context"), transcript);
        assertFalse(transcript.contains("protected file contents"), transcript);
        assertFalse(result.finalAnswer().contains("Patient Name: Eleni Nikolaou"), result.finalAnswer());
    }

    @Test
    void private_mode_xlsx_extraction_is_withheld_from_model_context() throws Exception {
        Path xlsx = workspace.resolve("family-budget.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Budget");
            sheet.createRow(0).createCell(0).setCellValue("Family medical bill: 1837.42 EUR");
            try (OutputStream out = Files.newOutputStream(xlsx)) {
                workbook.write(out);
            }
        }

        Config cfg = privateModeConfig();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, fixedApprovalGate(ApprovalResponse.DENIED), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("I cannot see the raw private workbook text.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read family-budget.xlsx and tell me the bill amount."));

        loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"family-budget.xlsx\"}}",
                messages,
                workspace,
                ctx);

        String transcript = messages.toString();
        assertFalse(transcript.contains("Family medical bill: 1837.42 EUR"), transcript);
        assertTrue(transcript.contains("withheld from model context"), transcript);
        assertFalse(transcript.contains("protected file contents"), transcript);
    }

    @Test
    void private_mode_pdf_extraction_is_withheld_from_model_context() throws Exception {
        writePdf(workspace.resolve("lease.pdf"), "Patient Name: Eleni Nikolaou");

        Config cfg = privateModeConfig();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, fixedApprovalGate(ApprovalResponse.DENIED), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("I cannot see the raw private PDF text.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read lease.pdf and tell me the patient name."));

        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"lease.pdf\"}}",
                messages,
                workspace,
                ctx);

        String transcript = messages.toString();
        assertFalse(transcript.contains("Patient Name: Eleni Nikolaou"), transcript);
        assertTrue(transcript.contains("withheld from model context"), transcript);
        assertFalse(transcript.contains("protected file contents"), transcript);
        assertFalse(result.finalAnswer().contains("Patient Name: Eleni Nikolaou"), result.finalAnswer());
    }

    @Test
    void private_mode_xls_extraction_is_withheld_from_model_context() throws Exception {
        writeXls(workspace.resolve("family-budget.xls"), "Family medical bill: 1837.42 EUR");

        Config cfg = privateModeConfig();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, fixedApprovalGate(ApprovalResponse.DENIED), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("I cannot see the raw private workbook text.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read family-budget.xls and tell me the bill amount."));

        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"family-budget.xls\"}}",
                messages,
                workspace,
                ctx);

        String transcript = messages.toString();
        assertFalse(transcript.contains("Family medical bill: 1837.42 EUR"), transcript);
        assertTrue(transcript.contains("withheld from model context"), transcript);
        assertFalse(transcript.contains("protected file contents"), transcript);
        assertFalse(result.finalAnswer().contains("Family medical bill: 1837.42 EUR"), result.finalAnswer());
    }

    @Test
    void private_mode_named_pdf_target_blocks_sibling_private_document_before_handoff_approval() throws Exception {
        writePdf(workspace.resolve("private-report.pdf"), "Named PDF fact");
        writeDocx(workspace.resolve("private-report.docx"), "Sibling DOCX fact");

        AtomicInteger approvals = new AtomicInteger();
        Config cfg = privateModeConfig();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null,
                approvalGate(approvals, new AtomicReference<>(""), new AtomicReference<>(""), ApprovalResponse.APPROVED),
                registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("I did not inspect the sibling document.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        TurnTaskContractCapture.set(readOnlyContract(
                "Summarize private-report.pdf.",
                Set.of("private-report.pdf"),
                Set.of()));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Summarize private-report.pdf."));

        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"private-report.docx\"}}",
                messages,
                workspace,
                ctx);

        String transcript = messages.toString();
        assertEquals(0, approvals.get(), "blocked sibling read must not ask private handoff approval");
        assertTrue(transcript.contains("outside the current requested private document target set"), transcript);
        assertFalse(transcript.contains("Sibling DOCX fact"), transcript);
        assertEquals(0, result.readPaths().size(), result.readPaths().toString());
    }

    @Test
    void private_mode_multiple_named_document_targets_allow_each_named_target() throws Exception {
        writePdf(workspace.resolve("private-report.pdf"), "Named PDF fact");
        writeDocx(workspace.resolve("private-report.docx"), "Named DOCX fact");

        Config cfg = privateModeConfig();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, fixedApprovalGate(ApprovalResponse.DENIED), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("I cannot see the raw private document text.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        TurnTaskContractCapture.set(readOnlyContract(
                "Summarize private-report.pdf and private-report.docx.",
                Set.of("private-report.pdf", "private-report.docx"),
                Set.of()));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Summarize private-report.pdf and private-report.docx."));

        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"private-report.docx\"}}",
                messages,
                workspace,
                ctx);

        String transcript = messages.toString();
        assertFalse(transcript.contains("outside the current requested private document target set"), transcript);
        assertTrue(transcript.contains("withheld from model context"), transcript);
        assertEquals(List.of("private-report.docx"), result.readPaths());
    }

    @Test
    void private_mode_named_xlsx_target_blocks_sibling_private_workbook_before_extraction() throws Exception {
        writeXls(workspace.resolve("private-workbook.xls"), "Sibling workbook fact");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Private").createRow(0).createCell(0).setCellValue("Named workbook fact");
            try (OutputStream out = Files.newOutputStream(workspace.resolve("private-workbook.xlsx"))) {
                workbook.write(out);
            }
        }

        Config cfg = privateModeConfig();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, fixedApprovalGate(ApprovalResponse.APPROVED), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("I did not inspect the sibling workbook.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        TurnTaskContractCapture.set(readOnlyContract(
                "Summarize private-workbook.xlsx.",
                Set.of("private-workbook.xlsx"),
                Set.of()));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Summarize private-workbook.xlsx."));

        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"private-workbook.xls\"}}",
                messages,
                workspace,
                ctx);

        String transcript = messages.toString();
        assertTrue(transcript.contains("outside the current requested private document target set"), transcript);
        assertFalse(transcript.contains("Sibling workbook fact"), transcript);
        assertEquals(0, result.readPaths().size(), result.readPaths().toString());
    }

    @Test
    void developer_mode_public_document_read_is_not_restricted_by_private_named_target_guard() throws Exception {
        writePdf(workspace.resolve("public-report.pdf"), "Public report fact");

        Config cfg = new Config(null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("The report says Public report fact.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        TurnTaskContractCapture.set(readOnlyContract(
                "Summarize public-report.pdf.",
                Set.of("different-target.pdf"),
                Set.of()));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Summarize public-report.pdf."));

        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"public-report.pdf\"}}",
                messages,
                workspace,
                ctx);

        String transcript = messages.toString();
        assertFalse(transcript.contains("outside the current requested private document target set"), transcript);
        assertTrue(transcript.contains("Public report fact"), transcript);
        assertEquals(List.of("public-report.pdf"), result.readPaths());
    }

    @Test
    void private_mode_withheld_document_final_answer_redacts_model_fabricated_private_fact() throws Exception {
        Path docx = workspace.resolve("medical-notes.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Patient Name: Eleni Nikolaou");
            try (OutputStream out = Files.newOutputStream(docx)) {
                doc.write(out);
            }
        }

        Config cfg = privateModeConfig();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, fixedApprovalGate(ApprovalResponse.DENIED), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("The patient is Eleni Nikolaou.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read medical-notes.docx and tell me the patient name."));

        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"medical-notes.docx\"}}",
                messages,
                workspace,
                ctx);

        assertFalse(result.finalAnswer().contains("Eleni Nikolaou"), result.finalAnswer());
        assertTrue(result.finalAnswer().contains("[redacted-private-document-canary]"), result.finalAnswer());
    }

    @Test
    void private_mode_document_send_to_model_opt_in_allows_model_handoff() throws Exception {
        Path docx = workspace.resolve("medical-notes.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Clinic appointment reference Alpha Safe Handoff");
            try (OutputStream out = Files.newOutputStream(docx)) {
                doc.write(out);
            }
        }

        Config cfg = privateModeDocumentSendToModelConfig();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("The document contains Alpha Safe Handoff.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read medical-notes.docx and summarize it."));

        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"medical-notes.docx\"}}",
                messages,
                workspace,
                ctx);

        String transcript = messages.toString();
        assertTrue(transcript.contains("Clinic appointment reference Alpha Safe Handoff"), transcript);
        assertFalse(transcript.contains("withheld from model context"), transcript);
        assertTrue(result.finalAnswer().contains("Alpha Safe Handoff"), result.finalAnswer());
    }

    @Test
    void private_mode_document_send_to_model_requires_per_turn_approval_and_traces_scope() throws Exception {
        Path docx = workspace.resolve("medical-notes.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Clinic appointment reference Alpha Per Turn");
            try (OutputStream out = Files.newOutputStream(docx)) {
                doc.write(out);
            }
        }

        AtomicInteger approvals = new AtomicInteger();
        AtomicReference<String> approvalDescription = new AtomicReference<>("");
        AtomicReference<String> approvalDetail = new AtomicReference<>("");
        ApprovalGate gate = approvalGate(approvals, approvalDescription, approvalDetail, ApprovalResponse.APPROVED);
        Config cfg = privateModeConfig();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, gate, registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("The document contains Alpha Per Turn.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read medical-notes.docx and summarize it."));

        beginTrace("Read medical-notes.docx and summarize it.");
        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"medical-notes.docx\"}}",
                messages,
                workspace,
                ctx);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(1, approvals.get());
        assertTrue(approvalDescription.get().contains("private document model handoff"),
                approvalDescription.get());
        assertTrue(approvalDetail.get().contains("medical-notes.docx"), approvalDetail.get());
        assertTrue(approvalDetail.get().contains("SEND_TO_MODEL_CONTEXT"), approvalDetail.get());
        assertTrue(approvalDetail.get().contains("per-turn"), approvalDetail.get());

        String transcript = messages.toString();
        assertTrue(transcript.contains("Clinic appointment reference Alpha Per Turn"), transcript);
        assertFalse(transcript.contains("withheld from model context"), transcript);
        assertTrue(result.finalAnswer().contains("Alpha Per Turn"), result.finalAnswer());

        assertTrue(hasTraceEvent(trace, "PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_REQUIRED"), trace.events().toString());
        assertTrue(hasTraceEvent(trace, "PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_GRANTED"), trace.events().toString());
        assertFalse(hasTraceEvent(trace, "PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_DENIED"), trace.events().toString());
        String traceJson = MAPPER.writeValueAsString(trace);
        assertFalse(traceJson.contains("Clinic appointment reference Alpha Per Turn"), traceJson);
        assertTrue(traceJson.contains("PRIVATE_DOCUMENT_EXTRACTED_TEXT"), traceJson);
        assertTrue(traceJson.contains("SEND_TO_MODEL_CONTEXT"), traceJson);
    }

    @Test
    void private_mode_document_send_to_model_denial_keeps_withheld_result_and_traces_denial() throws Exception {
        Path docx = workspace.resolve("medical-notes.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Clinic appointment reference Alpha Denied");
            try (OutputStream out = Files.newOutputStream(docx)) {
                doc.write(out);
            }
        }

        AtomicInteger approvals = new AtomicInteger();
        AtomicReference<String> approvalDescription = new AtomicReference<>("");
        AtomicReference<String> approvalDetail = new AtomicReference<>("");
        ApprovalGate gate = approvalGate(approvals, approvalDescription, approvalDetail, ApprovalResponse.DENIED);
        Config cfg = privateModeConfig();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, gate, registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("I cannot see the raw private document text.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read medical-notes.docx and summarize it."));

        beginTrace("Read medical-notes.docx and summarize it.");
        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"medical-notes.docx\"}}",
                messages,
                workspace,
                ctx);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(1, approvals.get());
        assertTrue(approvalDescription.get().contains("private document model handoff"),
                approvalDescription.get());
        assertTrue(approvalDetail.get().contains("SEND_TO_MODEL_CONTEXT"), approvalDetail.get());

        String transcript = messages.toString();
        assertFalse(transcript.contains("Clinic appointment reference Alpha Denied"), transcript);
        assertTrue(transcript.contains("withheld from model context"), transcript);
        assertFalse(result.finalAnswer().contains("Alpha Denied"), result.finalAnswer());

        assertTrue(hasTraceEvent(trace, "PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_REQUIRED"), trace.events().toString());
        assertFalse(hasTraceEvent(trace, "PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_GRANTED"), trace.events().toString());
        assertTrue(hasTraceEvent(trace, "PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_DENIED"), trace.events().toString());
        String traceJson = MAPPER.writeValueAsString(trace);
        assertFalse(traceJson.contains("Clinic appointment reference Alpha Denied"), traceJson);
        assertTrue(traceJson.contains("PRIVATE_DOCUMENT_EXTRACTED_TEXT"), traceJson);
    }

    @Test
    void private_mode_send_to_model_opt_in_allows_handoff_but_persistence_redacts() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV\n");

        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "protected_read", new LinkedHashMap<>(Map.of(
                        "default_scope", "SEND_TO_MODEL_CONTEXT",
                        "allow_send_to_model", true,
                        "persist_raw_artifacts", false)))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("The approved file contained FILE_DISCOVERED_CANARY_SCOPE_ENV.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read .env and tell me the value."));

        loop.run("{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                messages, workspace, ctx);

        assertTrue(messages.toString().contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), messages.toString());

        JsonSessionStore store = new JsonSessionStore(workspace.resolve("sessions"));
        store.appendTurn("sid-scope", new TurnRecord(
                1,
                Instant.parse("2026-05-15T00:00:00Z"),
                100,
                "Read .env",
                "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV",
                List.of(new TurnRecord.ToolCallSummary(
                        "talos.read_file",
                        ".env",
                        true,
                        "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV")),
                1,
                1,
                0,
                "trace FILE_DISCOVERED_CANARY_SCOPE_ENV"));

        String jsonl = Files.readString(workspace.resolve("sessions").resolve("sid-scope.turns.jsonl"));
        assertFalse(jsonl.contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), jsonl);
        assertFalse(jsonl.contains("t267-token-should-not-appear"), jsonl);
        assertTrue(jsonl.contains("API_TOKEN=[redacted]"), jsonl);
    }

    @Test
    void persist_raw_artifacts_false_even_when_send_to_model_true() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "protected_read", new LinkedHashMap<>(Map.of(
                        "default_scope", "SEND_TO_MODEL_CONTEXT",
                        "allow_send_to_model", true,
                        "persist_raw_artifacts", false)))));

        assertTrue(dev.talos.runtime.policy.ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(cfg));
        assertFalse(dev.talos.runtime.policy.ProtectedReadScopePolicy.persistRawArtifacts(cfg));
    }

    private static Config privateModeConfig() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of("mode", "private")));
        return cfg;
    }

    private static Config privateModeDocumentSendToModelConfig() {
        Config cfg = privateModeConfig();
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "document_extraction", new LinkedHashMap<>(Map.of(
                        "allow_send_to_model", true,
                        "persist_raw_artifacts", false,
                        "allow_rag_indexing", false)))));
        return cfg;
    }

    private static ApprovalGate approvalGate(
            AtomicInteger approvals,
            AtomicReference<String> description,
            AtomicReference<String> detail,
            ApprovalResponse response) {
        return new ApprovalGate() {
            @Override
            public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }

            @Override
            public ApprovalResponse approveFull(String desc, String det) {
                approvals.incrementAndGet();
                description.set(desc == null ? "" : desc);
                detail.set(det == null ? "" : det);
                return response;
            }
        };
    }

    private static ApprovalGate fixedApprovalGate(ApprovalResponse response) {
        return approvalGate(new AtomicInteger(), new AtomicReference<>(""), new AtomicReference<>(""), response);
    }

    private static void beginTrace(String request) {
        LocalTurnTraceCapture.begin(
                "trc-private-doc-handoff",
                "sid-private-doc-handoff",
                1,
                "2026-05-20T12:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                request);
    }

    private static boolean hasTraceEvent(LocalTurnTrace trace, String eventType) {
        return trace != null
                && trace.events().stream()
                .map(TurnTraceEvent::type)
                .anyMatch(eventType::equals);
    }

    private static TaskContract readOnlyContract(String request, Set<String> expectedTargets, Set<String> sourceTargets) {
        return new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                expectedTargets,
                sourceTargets,
                Set.of(),
                request,
                "test-contract");
    }

    private static void writePdf(Path path, String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            document.save(path.toFile());
        }
    }

    private static void writeXls(Path path, String text) throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet("Budget");
            sheet.createRow(0).createCell(0).setCellValue(text);
            try (OutputStream out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
        }
    }

    private static void writeDocx(Path path, String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(text);
            try (OutputStream out = Files.newOutputStream(path)) {
                doc.write(out);
            }
        }
    }
}
