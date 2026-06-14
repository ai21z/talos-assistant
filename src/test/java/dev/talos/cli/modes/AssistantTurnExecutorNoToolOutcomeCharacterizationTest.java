package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AssistantTurnExecutor no-tool outcome characterization")
class AssistantTurnExecutorNoToolOutcomeCharacterizationTest {

    private static final Path T816_REPORT = Path.of(
            "work-cycle-docs",
            "reports",
            "t816-assistant-turn-executor-no-tool-outcome-characterization.md");

    @Test
    void noToolMalformedMutationDebrisRetryRunsBeforeNoActionShaping(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("scripts.js"), """
                document.querySelector("#wrongButton").addEventListener("click", () => {
                  console.log("wrong");
                });
                """);
        ToolRegistry registry = registryWith(new FileEditTool());
        Context ctx = context(
                workspace,
                registry,
                LlmClient.scripted(List.of(
                        """
                        {
                          "name": "talos.edit_file",
                          "arguments": {
                            "path": "scripts.js",
                            "old_string": 'document.querySelector("#wrongButton").addEventListener("click", () => {',
                            "new_string": 'document.querySelector("button").addEventListener("click", () => {'
                          }
                        }
                        """,
                        """
                        {
                          "name": "talos.edit_file",
                          "arguments": {
                            "path": "scripts.js",
                            "old_string": "document.querySelector(\\"#wrongButton\\").addEventListener(\\"click\\", () => {",
                            "new_string": "document.querySelector(\\"button\\").addEventListener(\\"click\\", () => {"
                          }
                        }
                        """)),
                3);

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages("My BMI page is almost there, but when I press the button nothing happens. "
                        + "Please keep the look the same and just make the button work."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        String scripts = Files.readString(workspace.resolve("scripts.js"));
        assertTrue(scripts.contains("document.querySelector(\"button\")"), scripts);
        assertNotEquals(AssistantTurnExecutor.MALFORMED_TOOL_PROTOCOL_REPLACEMENT, out.text(),
                "rescued no-tool debris must not reach the no-action shaping answer");
    }

    @Test
    void noToolMissingMutationRetryUsesBufferedPathAndVisibleSummary(@TempDir Path workspace)
            throws Exception {
        var visibleChunks = new ArrayList<String>();
        ToolRegistry registry = registryWith(new FileWriteTool());
        Context ctx = context(
                workspace,
                registry,
                LlmClient.scripted(List.of(
                        "Create `script.js` with this JavaScript code.",
                        "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"script.js\","
                                + "\"content\":\"document.body.dataset.ready = 't816';\"}}",
                        "Created script.js.")),
                3,
                visibleChunks);

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages("Create the script.js file you need in this workspace."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertFalse(out.streamed(),
                "mutation turns with a stream sink must stay buffered for bounded no-tool retry");
        assertTrue(visibleChunks.isEmpty(),
                "initial unsupported no-tool prose must not reach the stream sink");
        assertTrue(Files.exists(workspace.resolve("script.js")));
        assertEquals("document.body.dataset.ready = 't816';",
                Files.readString(workspace.resolve("script.js")));
        assertTrue(out.text().contains("[Used 1 tool(s): talos.write_file"), out.text());
    }

    @Test
    void noToolReadEvidenceHandoffRunsBeforeFinalAnswer(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Project\nT816-README-MARKER\n");
        ToolRegistry registry = registryWith(new ReadFileTool());
        Context ctx = context(
                workspace,
                registry,
                LlmClient.scripted(List.of(
                        "I can summarize the README.",
                        "README evidence gathered: T816-README-MARKER.")),
                5);

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages("Read README.md and summarize it."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(out.text().contains("T816-README-MARKER"), out.text());
        assertTrue(out.text().contains("talos.read_file"), out.text());
        assertFalse(out.text().contains("[Evidence incomplete:"), out.text());
        assertFalse(out.text().contains("I can summarize the README."), out.text());
    }

    @Test
    void noToolReadOnlyInspectionRetryRunsBeforeFinalAnswerAndBuffersDeflection(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="style.css"></head>
                  <body><h1>Night Drive</h1><script src="script.js"></script></body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { background: #111; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('T816-ready');\n");
        var visibleChunks = new ArrayList<String>();
        ToolRegistry registry = registryWith(new ListDirTool(), new ReadFileTool());
        Context ctx = context(
                workspace,
                registry,
                LlmClient.scripted(List.of(
                        "Sure, please provide the path of the folder you want me to inspect.",
                        "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}\n"
                                + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}\n"
                                + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"style.css\"}}\n"
                                + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"script.js\"}}",
                        "This workspace is a Night Drive page. script.js prints T816-ready.")),
                5,
                visibleChunks);

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages("I'm not a developer. What is this folder for? Explain the website."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertFalse(out.streamed(),
                "workspace-evidence turns with a stream sink must stay buffered for inspection retry");
        assertTrue(visibleChunks.isEmpty(),
                "initial inspection deflection must not reach the stream sink");
        assertTrue(out.text().contains("[Used 4 tool(s): talos.list_dir, talos.read_file"),
                out.text());
        assertTrue(out.text().contains("T816-ready"), out.text());
        assertFalse(out.text().contains("provide the path"), out.text());
    }

    @Test
    void noToolOutcomeReportPinsT817MoveStayBoundary() throws Exception {
        String report = Files.readString(T816_REPORT);

        assertTrue(report.contains("# T816 AssistantTurnExecutor No-Tool Outcome Characterization"));
        assertTrue(report.contains("resolveNoToolAnswer(...)"));
        assertTrue(report.contains("emptyNoToolLoopResult(...)"));
        assertTrue(report.contains("AssistantNoToolOutcomeResolver"));
        assertTrue(report.contains("T816 does not authorize production"));
        assertTrue(report.contains("extraction"));
        assertTrue(report.contains("Keep in `AssistantTurnExecutor`"));
        assertTrue(report.contains("shapeAnswerWithoutTools(...)"));
        assertTrue(report.contains("shapeAnswerAfterToolLoop(...)"));
        assertTrue(report.contains("trace begin/set/clear"));
        assertTrue(report.contains("TurnOutput"));
    }

    private static Context context(
            Path workspace,
            ToolRegistry registry,
            LlmClient llm,
            int maxIterations
    ) {
        return context(workspace, registry, llm, maxIterations, null);
    }

    private static Context context(
            Path workspace,
            ToolRegistry registry,
            LlmClient llm,
            int maxIterations,
            List<String> streamChunks
    ) {
        Context.Builder builder = Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), maxIterations));
        if (streamChunks != null) {
            builder.streamSink(streamChunks::add);
        }
        return builder.build();
    }

    private static ToolRegistry registryWith(TalosTool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (TalosTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private static List<ChatMessage> messages(String request) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(request));
        return messages;
    }
}
