package dev.talos.core.llm;

import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantTurnExecutorMutationRetryToolSurfaceTest {

    @Test
    void missingMutationRetryUsesOnlyWriteAndEditTools() {
        RecordingResolver resolver = new RecordingResolver(List.of(
                "Done. The files are complete.",
                "I still will not call tools."));
        Context ctx = context(resolver);
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Create index.html, styles.css, and scripts.js for a BMI calculator.")
        ));

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages,
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(output.text().startsWith("[Action obligation failed:"), output.text());
        assertTrue(resolver.requests.size() >= 2, "expected initial call and retry call");
        assertEquals(
                List.of("talos.edit_file", "talos.write_file"),
                sortedToolNames(resolver.requests.get(1)));
    }

    @Test
    void staticFullRewriteMissingMutationRetryUsesOnlyWriteFileTool() {
        RecordingResolver resolver = new RecordingResolver(List.of(
                "Done. The repair is complete.",
                "I still will not call tools."));
        Context ctx = context(resolver);
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.system("""
                        [Static verification repair context]
                        Expected targets: index.html, scripts.js, styles.css

                        Previous static verification problems:
                        - HTML does not link JavaScript file: `scripts.js`

                        Repair plan:
                        - index.html: You must use talos.write_file with complete corrected file content for index.html.
                        - scripts.js: You must use talos.write_file with complete corrected file content for scripts.js.
                        - styles.css: You must use talos.write_file with complete corrected file content for styles.css.

                        Full-file replacement targets: index.html, scripts.js, styles.css
                        """),
                ChatMessage.user("Fix the remaining static verification problems.")
        ));

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages,
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(output.text().startsWith("[Action obligation failed:"), output.text());
        assertTrue(resolver.requests.size() >= 2, "expected initial call and retry call");
        assertEquals(List.of("talos.write_file"), sortedToolNames(resolver.requests.get(1)));
    }

    private static Context context(RecordingResolver resolver) {
        List<ToolSpec> broadTools = broadToolSurface();
        LlmClient llm = new LlmClient(engineConfig(), resolver);
        llm.setToolSpecs(broadTools);
        return Context.builder(engineConfig())
                .llm(llm)
                .nativeToolSpecs(broadTools)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null), 3))
                .build();
    }

    private static List<ToolSpec> broadToolSurface() {
        return List.of(
                tool("talos.read_file"),
                tool("talos.list_dir"),
                tool("talos.write_file"),
                tool("talos.edit_file"),
                tool("talos.mkdir"),
                tool("talos.run_command"),
                tool("talos.apply_workspace_batch"),
                tool("talos.copy_path"),
                tool("talos.move_path"),
                tool("talos.rename_path"));
    }

    private static ToolSpec tool(String name) {
        return new ToolSpec(name, name, "{}");
    }

    private static List<String> sortedToolNames(ChatRequest request) {
        return request == null || request.tools == null
                ? List.of()
                : request.tools.stream()
                .map(ToolSpec::name)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static Config engineConfig() {
        Config cfg = new Config();
        LinkedHashMap<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "llama_cpp");
        cfg.data.put("llm", llm);

        LinkedHashMap<String, Object> backend = new LinkedHashMap<>();
        backend.put("model", "gpt-oss:20b");
        cfg.data.put("llama_cpp", backend);
        return cfg;
    }

    private static final class RecordingResolver implements LlmEngineResolver {
        private final List<String> responses;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int cursor;

        private RecordingResolver(List<String> responses) {
            this.responses = responses == null || responses.isEmpty()
                    ? List.of("")
                    : List.copyOf(responses);
        }

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            this.requests.add(request);
            int index = Math.min(cursor++, responses.size() - 1);
            return Stream.of(TokenChunk.of(responses.get(index)), TokenChunk.eos());
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
