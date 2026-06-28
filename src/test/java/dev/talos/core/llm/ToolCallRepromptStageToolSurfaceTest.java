package dev.talos.core.llm;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.toolcall.LoopState;
import dev.talos.runtime.toolcall.ToolCallExecutionStage;
import dev.talos.runtime.toolcall.ToolCallRepromptStage;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallRepromptStageToolSurfaceTest {

    @Test
    void staticWebExpectedTargetProgressRepromptUsesOnlyWriteFileTool() {
        RecordingResolver resolver = new RecordingResolver();
        List<ToolSpec> broadTools = broadToolSurface();
        LlmClient llm = new LlmClient(engineConfig(), resolver);
        llm.setToolSpecs(broadTools);
        Context ctx = Context.builder(engineConfig())
                .llm(llm)
                .nativeToolSpecs(broadTools)
                .build();
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Create index.html, styles.css, and scripts.js for a BMI calculator.")
        ));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                ctx,
                null,
                10,
                0);
        state.toolOutcomes.add(mutatingOutcome("talos.write_file", "index.html"));
        state.toolOutcomes.add(mutatingOutcome("talos.write_file", "styles.css"));
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                2,
                List.of("[ok] Updated index.html", "[ok] Updated styles.css"),
                0,
                false,
                false,
                false,
                2);

        boolean shouldReprompt = new ToolCallRepromptStage().reprompt(state, outcome);

        assertTrue(shouldReprompt);
        assertEquals(
                List.of("talos.write_file"),
                toolNames(resolver.lastRequest));
    }

    @Test
    void transientRetryPreservesTemporaryExpectedProgressOverlay() {
        TransientThenRecordingResolver resolver = new TransientThenRecordingResolver();
        List<ToolSpec> broadTools = broadToolSurface();
        LlmClient llm = new LlmClient(engineConfig(), resolver);
        llm.setToolSpecs(broadTools);
        Context ctx = Context.builder(engineConfig())
                .llm(llm)
                .nativeToolSpecs(broadTools)
                .build();
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Create index.html, styles.css, and scripts.js for a BMI calculator.")
        ));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                ctx,
                null,
                10,
                0);
        state.toolOutcomes.add(mutatingOutcome("talos.write_file", "index.html"));
        state.toolOutcomes.add(mutatingOutcome("talos.write_file", "styles.css"));
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                2,
                List.of("[ok] Updated index.html", "[ok] Updated styles.css"),
                0,
                false,
                false,
                false,
                2);

        boolean shouldReprompt = new ToolCallRepromptStage().reprompt(state, outcome);

        assertTrue(shouldReprompt);
        String retryPayload = messageContents(resolver.retryRequest);
        assertTrue(retryPayload.contains("[Expected target progress]"), retryPayload);
        assertTrue(retryPayload.contains("[Current task - stay focused on this]"), retryPayload);
        assertFalse(state.messages.stream()
                        .map(ChatMessage::content)
                        .filter(content -> content != null)
                        .anyMatch(content -> content.startsWith("[Expected target progress]")
                                || content.startsWith("[Current task")),
                "temporary overlay messages must still be cleaned from durable loop history");
    }

    @Test
    void transientRetryEmptyResultKeepsRetryFallbackDespitePendingObligation() {
        TransientThenEmptyResolver resolver = new TransientThenEmptyResolver();
        List<ToolSpec> broadTools = broadToolSurface();
        LlmClient llm = new LlmClient(engineConfig(), resolver);
        llm.setToolSpecs(broadTools);
        Context ctx = Context.builder(engineConfig())
                .llm(llm)
                .nativeToolSpecs(broadTools)
                .build();
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Create index.html, styles.css, and scripts.js for a BMI calculator.")
        ));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                ctx,
                null,
                10,
                0);
        state.toolOutcomes.add(mutatingOutcome("talos.write_file", "index.html"));
        state.toolOutcomes.add(mutatingOutcome("talos.write_file", "styles.css"));
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                2,
                List.of("[ok] Updated index.html", "[ok] Updated styles.css"),
                0,
                false,
                false,
                false,
                2);

        boolean shouldReprompt = new ToolCallRepromptStage().reprompt(state, outcome);

        assertFalse(shouldReprompt);
        assertFalse(state.failureDecision.shouldStop(), state.failureDecision.reason());
        assertEquals("(no answer from model after retry)", state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void staticFullRewriteRepairRepromptUsesOnlyWriteFileTool() {
        RecordingResolver resolver = new RecordingResolver();
        List<ToolSpec> broadTools = broadToolSurface();
        LlmClient llm = new LlmClient(engineConfig(), resolver);
        llm.setToolSpecs(broadTools);
        Context ctx = Context.builder(engineConfig())
                .llm(llm)
                .nativeToolSpecs(broadTools)
                .build();
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
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                ctx,
                null,
                10,
                0);
        state.toolOutcomes.add(mutatingOutcome("talos.write_file", "index.html"));
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                1,
                List.of("[ok] Updated index.html"),
                0,
                false,
                false,
                false,
                1);

        boolean shouldReprompt = new ToolCallRepromptStage().reprompt(state, outcome);

        assertTrue(shouldReprompt);
        assertEquals(List.of("talos.write_file"), toolNames(resolver.lastRequest));
    }

    @Test
    void staticFullRewriteRepairAfterReadOnlyInspectionStillUsesOnlyWriteFileTool() {
        RecordingResolver resolver = new RecordingResolver();
        List<ToolSpec> broadTools = broadToolSurface();
        LlmClient llm = new LlmClient(engineConfig(), resolver);
        llm.setToolSpecs(broadTools);
        Context ctx = Context.builder(engineConfig())
                .llm(llm)
                .nativeToolSpecs(broadTools)
                .build();
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.system("""
                        [Static verification repair context]
                        Expected targets: index.html, scripts.js, styles.css

                        Previous static verification problems:
                        - CSS references missing class selectors: `.h1`

                        Repair plan:
                        Full-file replacement targets: index.html, scripts.js, styles.css
                        - index.html: You must use talos.write_file with complete corrected file content for index.html.
                        - scripts.js: You must use talos.write_file with complete corrected file content for scripts.js.
                        - styles.css: You must use talos.write_file with complete corrected file content for styles.css.
                        """),
                ChatMessage.user("Review the BMI calculator you just created and fix any obvious issue.")
        ));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                ctx,
                null,
                10,
                0);
        state.toolOutcomes.add(readOnlyOutcome("talos.list_dir", ""));
        state.toolNames.add("talos.list_dir");
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                0,
                List.of("[tool_result: talos.list_dir] index.html scripts.js styles.css"),
                0,
                false,
                false,
                false,
                1);

        boolean shouldReprompt = new ToolCallRepromptStage().reprompt(state, outcome);

        assertTrue(shouldReprompt);
        assertTrue(state.hasPendingActionObligation());
        assertEquals(List.of("talos.write_file"), toolNames(resolver.lastRequest));
    }

    @Test
    void staticFullRewriteRepairAfterReadOnlyInspectionUsesCompactRepairPayload() {
        RecordingResolver resolver = new RecordingResolver();
        List<ToolSpec> broadTools = broadToolSurface();
        LlmClient llm = new LlmClient(engineConfig(), resolver);
        llm.setToolSpecs(broadTools);
        Context ctx = Context.builder(engineConfig())
                .llm(llm)
                .nativeToolSpecs(broadTools)
                .build();
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys with OLD_BROAD_TOOL_MANUAL talos.rename_path talos.run_command"),
                ChatMessage.user("OLD_UNRELATED_MARKER: write some unrelated file."),
                ChatMessage.assistant("OLD_UNRELATED_MARKER: done."),
                ChatMessage.system("""
                        [Static verification repair context]
                        Expected targets: index.html, scripts.js, styles.css

                        Previous static verification problems:
                        - CSS references missing class selectors: `.h1`

                        Repair plan:
                        Full-file replacement targets: index.html, scripts.js, styles.css
                        - index.html: You must use talos.write_file with complete corrected file content for index.html.
                        - scripts.js: You must use talos.write_file with complete corrected file content for scripts.js.
                        - styles.css: You must use talos.write_file with complete corrected file content for styles.css.
                        """),
                ChatMessage.user("Review the BMI calculator you just created and fix any obvious issue.")
        ));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                ctx,
                null,
                10,
                0);
        state.toolOutcomes.add(readOnlyOutcome("talos.list_dir", ""));
        state.toolNames.add("talos.list_dir");
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                0,
                List.of("[tool_result: talos.list_dir] index.html scripts.js styles.css"),
                0,
                false,
                false,
                false,
                1);

        boolean shouldReprompt = new ToolCallRepromptStage().reprompt(state, outcome);

        assertTrue(shouldReprompt);
        String payload = messageContents(resolver.lastRequest);
        assertFalse(payload.contains("OLD_UNRELATED_MARKER"), payload);
        assertFalse(payload.contains("OLD_BROAD_TOOL_MANUAL"), payload);
        assertTrue(payload.contains("[Static verification repair context]"), payload);
        assertTrue(payload.contains("[Static repair progress]"), payload);
        assertTrue(payload.contains("Review the BMI calculator"), payload);
    }

    private static ToolCallLoop.ToolOutcome mutatingOutcome(
            String toolName,
            String pathHint
    ) {
        return toolOutcome(toolName, pathHint, true);
    }

    private static ToolCallLoop.ToolOutcome readOnlyOutcome(
            String toolName,
            String pathHint
    ) {
        return toolOutcome(toolName, pathHint, false);
    }

    private static ToolCallLoop.ToolOutcome toolOutcome(
            String toolName,
            String pathHint,
            boolean mutating
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                pathHint,
                true,
                mutating,
                false,
                "mutation applied",
                "");
    }

    private static List<ToolSpec> broadToolSurface() {
        return List.of(
                tool("talos.read_file"),
                tool("talos.list_dir"),
                tool("talos.write_file"),
                tool("talos.edit_file"),
                tool("talos.mkdir"),
                tool("talos.run_command"));
    }

    private static ToolSpec tool(String name) {
        return new ToolSpec(name, name, "{}");
    }

    private static List<String> toolNames(ChatRequest request) {
        return request == null || request.tools == null
                ? List.of()
                : request.tools.stream().map(ToolSpec::name).toList();
    }

    private static String messageContents(ChatRequest request) {
        if (request == null || request.messages == null) return "";
        return request.messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
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
        private volatile ChatRequest lastRequest;

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            this.lastRequest = request;
            return Stream.of(TokenChunk.of("No tool call."), TokenChunk.eos());
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class TransientThenRecordingResolver implements LlmEngineResolver {
        private int calls;
        private volatile ChatRequest retryRequest;

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            calls++;
            if (calls <= 3) {
                throw new EngineException.Transient("temporary backend failure", 503);
            }
            retryRequest = request;
            return Stream.of(TokenChunk.of("Retry answer."), TokenChunk.eos());
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class TransientThenEmptyResolver implements LlmEngineResolver {
        private int calls;

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            calls++;
            if (calls <= 3) {
                throw new EngineException.Transient("temporary backend failure", 503);
            }
            return Stream.of(TokenChunk.eos());
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
