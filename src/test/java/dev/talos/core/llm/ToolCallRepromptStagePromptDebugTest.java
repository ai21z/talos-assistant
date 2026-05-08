package dev.talos.core.llm;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.toolcall.LoopState;
import dev.talos.runtime.toolcall.ToolCallExecutionStage;
import dev.talos.runtime.toolcall.ToolCallRepromptStage;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallRepromptStagePromptDebugTest {

    @AfterEach
    void clearPromptDebug() {
        PromptDebugCapture.clear();
    }

    @Test
    void boundedStaticRepairContinuationIncludesCurrentSelectorFacts(@TempDir Path workspace) throws Exception {
        writeAuditShapedStaticFixture(workspace);
        PromptCaptureResolver resolver = new PromptCaptureResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);
        client.setModel("llama_cpp/qwen2.5-coder-14b");
        List<ToolSpec> writeTools = List.of(writeSpec());
        Context ctx = Context.builder(engineConfig())
                .llm(client)
                .nativeToolSpecs(writeTools)
                .build();
        ArrayList<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.system("""
                        [Static verification repair context]
                        The previous mutation task ended incomplete after static verification.

                        Expected targets: index.html, scripts.js, styles.css

                        Previous static verification problems:
                        - CSS references missing class selectors: `.button`
                        - JavaScript references missing class selectors: `.missing-button`

                        Repair plan:
                        Full-file replacement targets: scripts.js, styles.css
                        Use talos.write_file with complete corrected content for these targets.
                        """),
                ChatMessage.user("Fix the remaining static BMI calculator verification problems.")
        ));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                workspace,
                ctx,
                null,
                10,
                0);
        state.mutatingToolSuccesses = 1;
        state.mutationSinceStart = true;
        state.totalToolsInvoked = 1;
        state.toolNames.add("talos.write_file");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "index.html",
                true,
                true,
                false,
                "Wrote index.html",
                ""));
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                1,
                List.of("Wrote index.html"),
                0,
                false,
                false,
                false,
                1);

        boolean shouldReprompt = new ToolCallRepromptStage().reprompt(state, outcome);

        assertTrue(shouldReprompt);
        String prompt = PromptDebugCapture.latestRecorded()
                .orElseThrow()
                .messages()
                .stream()
                .map(ChatMessage::content)
                .collect(Collectors.joining("\n\n"));
        assertTrue(prompt.contains("[Static verification repair context]"), prompt);
        assertTrue(prompt.contains("[Current static selector facts]"), prompt);
        assertTrue(prompt.contains("Observed in HTML:"), prompt);
        assertTrue(prompt.contains("- Classes: none"), prompt);
        assertTrue(prompt.contains("CSS references missing class selectors: `.button`"), prompt);
        assertTrue(prompt.contains("JavaScript references missing class selectors: `.missing-button`"), prompt);
        assertTrue(prompt.contains("pending-action-obligation")
                        || PromptDebugCapture.latestRecorded()
                        .orElseThrow()
                        .controls()
                        .debugTags()
                        .contains("pending-action-obligation"),
                "bounded retry should remain traceable as a pending action obligation");
    }

    private static void writeAuditShapedStaticFixture(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Fixture\n");
        Files.writeString(workspace.resolve("notes.md"), "private marker\n");
        Files.writeString(workspace.resolve("config.json"), "{\"project\":\"talos-fixture\"}\n");
        Files.write(workspace.resolve("report.docx"), new byte[]{0x50, 0x4b, 0x03, 0x04});
        Files.writeString(workspace.resolve("script.js"), "console.log('stale sibling');\n");
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                <head>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <button id="calculate">Calculate</button>
                  <script src="scripts.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                body { font-family: sans-serif; }
                .button { color: blue; }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.querySelector('.missing-button').addEventListener('click', () => {
                  console.log('clicked');
                });
                """);
    }

    private static ToolSpec writeSpec() {
        return new ToolSpec(
                "talos.write_file",
                "Write a complete file.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}");
    }

    private static Config engineConfig() {
        Config cfg = new Config();
        LinkedHashMap<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "llama_cpp");
        cfg.data.put("llm", llm);

        LinkedHashMap<String, Object> llamaCpp = new LinkedHashMap<>();
        llamaCpp.put("model", "qwen2.5-coder-14b");
        cfg.data.put("llama_cpp", llamaCpp);
        return cfg;
    }

    private static final class PromptCaptureResolver implements LlmEngineResolver {
        private volatile ChatRequest request;

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(
                    true,
                    true,
                    false,
                    8192,
                    true,
                    true,
                    false,
                    false,
                    false,
                    false,
                    false);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            this.request = request;
            return Stream.of(
                    TokenChunk.of("I still need to know what to change."),
                    TokenChunk.eos());
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
