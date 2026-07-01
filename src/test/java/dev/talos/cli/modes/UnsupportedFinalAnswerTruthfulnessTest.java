package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsupportedFinalAnswerTruthfulnessTest {

    @TempDir
    Path workspace;

    @Test
    void model_attempted_fabrication_is_overridden_by_runtime_postcondition() throws Exception {
        Files.writeString(workspace.resolve("report.docx"), "fake docx payload");

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(new Config(null))
                .llm(LlmClient.scripted(List.of(
                        "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"report.docx\"}}",
                        "I reviewed report.docx. The document says revenue is high.")))
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Summarize report.docx."));

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages, workspace, ctx, new AssistantTurnExecutor.Options());

        assertTrue(out.text().contains("Document capability note"), out.text());
        assertFalse(out.text().contains("revenue is high"), out.text());
        assertFalse(out.text().contains("document says"), out.text().toLowerCase());
    }

    @Test
    void unsupported_docx_compare_to_text_reports_partial_only() throws Exception {
        Files.writeString(workspace.resolve("report.txt"), "public report text\n");
        Files.writeString(workspace.resolve("workbook.xlsx"), "fake workbook payload");

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(new Config(null))
                .llm(LlmClient.scripted(List.of(
                        "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"report.txt\"}}\n"
                                + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"workbook.xlsx\"}}",
                        "I compared report.txt and workbook.xlsx. The spreadsheet contains matching revenue data.")))
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Compare workbook.xlsx with report.txt."));

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages, workspace, ctx, new AssistantTurnExecutor.Options());

        assertTrue(out.text().contains("workbook.xlsx"), out.text());
        assertTrue(out.text().contains("could not inspect"), out.text().toLowerCase());
        assertFalse(out.text().contains("matching revenue data"), out.text());
    }

    @Test
    void unsupported_pdf_summary_does_not_fabricate() throws Exception {
        assertUnsupportedSummaryIsCorrected("report.pdf",
                "I reviewed the PDF. The PDF says revenue increased.",
                "revenue increased");
    }

    @Test
    void unsupported_pptx_summary_does_not_fabricate() throws Exception {
        assertUnsupportedSummaryIsCorrected("slides.pptx",
                "I reviewed the PowerPoint deck. The deck says launch is ready.",
                "launch is ready");
    }

    @Test
    void unsupported_image_summary_does_not_fabricate() throws Exception {
        assertUnsupportedSummaryIsCorrected("image.png",
                "The image shows a signed medical form.",
                "signed medical form");
    }

    @Test
    void unsupported_archive_summary_does_not_fabricate() throws Exception {
        assertUnsupportedSummaryIsCorrected("archive.zip",
                "The archive includes tax documents and receipts.",
                "tax documents and receipts");
    }

    @Test
    void unsupported_binary_summary_does_not_fabricate() throws Exception {
        assertUnsupportedSummaryIsCorrected("binary.bin",
                "The binary file contains a saved password.",
                "saved password");
    }

    @Test
    void unsupported_pdf_compare_to_text_reports_partial_only() throws Exception {
        assertUnsupportedCompareIsCorrected("report.pdf",
                "I compared notes.txt and report.pdf. The PDF contains the same budget.");
    }

    @Test
    void unsupported_image_compare_to_text_reports_partial_only() throws Exception {
        assertUnsupportedCompareIsCorrected("image.png",
                "I compared notes.txt and image.png. The image shows the same budget.");
    }

    @Test
    void unsupported_archive_search_does_not_claim_no_matches_without_skip_note() throws Exception {
        Files.writeString(workspace.resolve("archive.zip"), "fake archive payload budget");

        ToolRegistry registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.GrepTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(new Config(null))
                .llm(LlmClient.scripted(List.of(
                        "{\"name\":\"talos.grep\",\"arguments\":{\"pattern\":\"budget\"}}",
                        "No matches found.")))
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Search for budget."));

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages, workspace, ctx, new AssistantTurnExecutor.Options());

        assertFalse(out.text().equals("No matches found."), out.text());
        assertTrue(out.text().toLowerCase().contains("skipped")
                || out.text().toLowerCase().contains("unsupported"), out.text());
    }

    @Test
    void unsupported_write_pdf_rejected_or_redirected_truthfully() throws Exception {
        assertUnsupportedWriteIsCorrected("summary.pdf",
                "I created summary.pdf as a valid PDF.");
    }

    @Test
    void unsupported_create_docx_rejected_or_redirected_truthfully() throws Exception {
        assertUnsupportedWriteIsCorrected("summary.docx",
                "I created summary.docx as a valid Word document.");
    }

    private void assertUnsupportedSummaryIsCorrected(String fileName, String badAnswer, String forbidden)
            throws Exception {
        Files.writeString(workspace.resolve(fileName), "fake unsupported payload");

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(new Config(null))
                .llm(LlmClient.scripted(List.of(
                        "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"" + fileName + "\"}}",
                        badAnswer)))
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Summarize " + fileName + "."));

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages, workspace, ctx, new AssistantTurnExecutor.Options());

        assertTrue(out.text().contains("Document capability note"), out.text());
        assertFalse(out.text().contains(forbidden), out.text());
    }

    private void assertUnsupportedCompareIsCorrected(String unsupportedFile, String badAnswer) throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "budget public text\n");
        Files.writeString(workspace.resolve(unsupportedFile), "fake unsupported payload");

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(new Config(null))
                .llm(LlmClient.scripted(List.of(
                        "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"notes.txt\"}}\n"
                                + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"" + unsupportedFile + "\"}}",
                        badAnswer)))
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Compare " + unsupportedFile + " with notes.txt."));

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages, workspace, ctx, new AssistantTurnExecutor.Options());

        assertTrue(out.text().contains(unsupportedFile), out.text());
        assertTrue(out.text().toLowerCase().contains("could not inspect"), out.text());
        assertFalse(out.text().toLowerCase().contains("contains the same budget"), out.text());
        assertFalse(out.text().toLowerCase().contains("shows the same budget"), out.text());
    }

    private void assertUnsupportedWriteIsCorrected(String fileName, String badAnswer) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.FileWriteTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(new Config(null))
                .llm(LlmClient.scripted(List.of(
                        "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"" + fileName
                                + "\",\"content\":\"fake\"}}",
                        badAnswer)))
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Create " + fileName + "."));

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages, workspace, ctx, new AssistantTurnExecutor.Options());

        assertTrue(out.text().toLowerCase().contains("unsupported")
                || out.text().toLowerCase().contains("cannot create valid"), out.text());
        assertFalse(out.text().toLowerCase().contains("i created"), out.text());
        assertFalse(out.text().toLowerCase().contains("as a valid pdf"), out.text());
        assertFalse(out.text().toLowerCase().contains("as a valid word document"), out.text());
    }
}
