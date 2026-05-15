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
}
