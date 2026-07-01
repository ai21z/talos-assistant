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
import dev.talos.tools.TalosTool;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantTurnExecutorMutationRetryToolSurfaceTest {

    @Test
    void staticWebMissingMutationRetryUsesOnlyWriteFileTool() {
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
                List.of("talos.write_file"),
                sortedToolNames(resolver.requests.get(1)));
    }

    @Test
    void workspaceOperationNoToolRetryUsesOnlyRequiredOperationToolAndFailsDeterministically() {
        RecordingResolver resolver = new RecordingResolver(List.of(
                "[ok] Created directory scratch/nested/reports.",
                "[ok] Created directory scratch/nested/reports."));
        Context ctx = context(resolver);
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Create directory scratch/nested/reports.")
        ));

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages,
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(output.text().startsWith("[Action obligation failed:"), output.text());
        assertFalse(output.text().contains("[ok] Created directory"), output.text());
        assertTrue(resolver.requests.size() >= 2, "expected initial call and operation retry call");
        assertEquals(List.of("talos.mkdir"), sortedToolNames(resolver.requests.get(1)));
        String retryPrompt = joinedMessageContent(resolver.requests.get(1));
        assertTrue(retryPrompt.contains("obligation: WORKSPACE_OPERATION_REQUIRED"), retryPrompt);
        assertTrue(retryPrompt.contains("talos.mkdir"), retryPrompt);
        assertFalse(retryPrompt.contains("talos.write_file"), retryPrompt);
        assertFalse(retryPrompt.contains("talos.edit_file"), retryPrompt);
    }

    @Test
    void missingMutationRetryUsesCompactMessagesWithoutOldHistory() {
        RecordingResolver resolver = new RecordingResolver(List.of(
                "Done. The files are complete.",
                "I still will not call tools."));
        Context ctx = context(resolver);
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("OLD_HISTORY_MARKER " + "u".repeat(2_000)),
                ChatMessage.assistant("OLD_ASSISTANT_MARKER " + "a".repeat(2_000)),
                ChatMessage.system("OLD_RUNTIME_SYSTEM_MARKER " + "s".repeat(2_000)),
                ChatMessage.user("Create index.html, styles.css, and scripts.js for a BMI calculator.")
        ));

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages,
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(output.text().startsWith("[Action obligation failed:"), output.text());
        assertTrue(resolver.requests.size() >= 2, "expected initial call and retry call");
        String retryPrompt = joinedMessageContent(resolver.requests.get(1));
        assertFalse(retryPrompt.contains("OLD_HISTORY_MARKER"), retryPrompt);
        assertFalse(retryPrompt.contains("OLD_ASSISTANT_MARKER"), retryPrompt);
        assertFalse(retryPrompt.contains("OLD_RUNTIME_SYSTEM_MARKER"), retryPrompt);
        assertTrue(retryPrompt.contains("[MutationRetryCapability]"), retryPrompt);
        assertFalse(retryPrompt.contains("[CurrentTurnCapability]"), retryPrompt);
        assertTrue(retryPrompt.contains("Create index.html, styles.css, and scripts.js"), retryPrompt);
        assertTrue(retryPrompt.contains("previous model response did not issue required write/edit tool calls"),
                retryPrompt);
    }

    @Test
    void missingMutationRetryUsesLeanPreambleInsteadOfLargeLeadingSystemPrompt() {
        RecordingResolver resolver = new RecordingResolver(List.of(
                "Done. The files are complete.",
                "I still will not call tools."));
        Context ctx = context(resolver);
        var messages = new ArrayList<>(List.of(
                ChatMessage.system(largeLeadingSystemPrompt()),
                ChatMessage.user("Create index.html, styles.css, and scripts.js for a BMI calculator.")
        ));

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages,
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(output.text().startsWith("[Action obligation failed:"), output.text());
        assertTrue(resolver.requests.size() >= 2, "expected initial call and retry call");
        String retryPrompt = joinedMessageContent(resolver.requests.get(1));
        assertFalse(retryPrompt.contains("FULL_SYSTEM_MARKER"), retryPrompt);
        assertTrue(retryPrompt.contains("Talos bounded mutation retry"), retryPrompt);
        assertTrue(retryPrompt.contains("Use only listed tools"), retryPrompt);
        assertTrue(retryPrompt.contains("[MutationRetryCapability]"), retryPrompt);
        assertFalse(retryPrompt.contains("[CurrentTurnCapability]"), retryPrompt);
    }

    @Test
    void missingMutationRetryUsesMinimalFrameWithRealWriteEditSchemas() {
        RecordingResolver resolver = new RecordingResolver(List.of(
                "Done. The files are complete.",
                "I still will not call tools."));
        Context ctx = context(resolver, realWriteEditToolSurface());
        var messages = new ArrayList<>(List.of(
                ChatMessage.system(largeLeadingSystemPrompt()),
                ChatMessage.user("Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js. It should calculate BMI from height and weight.")
        ));

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages,
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(output.text().startsWith("[Action obligation failed:"), output.text());
        assertTrue(resolver.requests.size() >= 2, "minimal retry should reach backend with real write/edit schemas");
        ChatRequest retry = resolver.requests.get(1);
        String retryPrompt = joinedMessageContent(retry);
        assertTrue(retryPrompt.contains("[MutationRetryCapability]"), retryPrompt);
        assertFalse(retryPrompt.contains("[CurrentTurnCapability]"), retryPrompt);
        assertFalse(retryPrompt.contains("Do not provide manual snippets instead of acting"), retryPrompt);
        assertTrue(retryPrompt.contains("requiredTargets: index.html, styles.css, scripts.js"), retryPrompt);
        assertTrue(retryPrompt.contains("script.js and scripts.js are different target paths"), retryPrompt);
        assertTrue(retryPrompt.contains("Create a complete static BMI calculator"), retryPrompt);
        assertEquals(
                List.of("talos.write_file"),
                sortedToolNames(retry));
    }

    @Test
    void conditionalReviewFixRetryUsesCompactEnvelopeAndRetrySchemas() {
        RecordingResolver resolver = new RecordingResolver(List.of(
                "I inspected the files and did not change anything.",
                "I still will not call tools."));
        Context ctx = context(resolver, realWriteEditToolSurface());
        var messages = new ArrayList<>(List.of(
                ChatMessage.system(largeLeadingSystemPrompt()),
                ChatMessage.user("Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js. It should calculate BMI from height and weight."),
                ChatMessage.assistant("[Static verification: passed for 3 mutated target(s).]"),
                ChatMessage.user("Review the BMI calculator you just created and fix any obvious issue that would stop it from working in a browser.")
        ));

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages,
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(output.text().startsWith("[Action obligation failed:"), output.text());
        assertTrue(resolver.requests.size() >= 2, "expected conditional review/fix retry call");
        ChatRequest retry = resolver.requests.get(1);
        String retryPrompt = joinedMessageContent(retry);
        assertTrue(retryPrompt.contains("[MutationRetryCapability]"), retryPrompt);
        assertTrue(retryPrompt.contains("obligation: CONDITIONAL_REVIEW_FIX"), retryPrompt);
        assertTrue(retryPrompt.contains("Review the BMI calculator you just created"), retryPrompt);
        assertFalse(retryPrompt.contains("previous model response did not satisfy"),
                "backend retry payload should not include redundant failure-summary prose: " + retryPrompt);
        assertFalse(retryPrompt.contains("If you have not inspected the relevant files yet"), retryPrompt);
        assertFalse(retryPrompt.contains("The runtime handles tool invocation, approval"), retryPrompt);
        assertTrue(retryPrompt.length() < 2_500, "retry prompt was too large: " + retryPrompt.length());
        assertTrue(requestPayloadChars(retry) < 3_000,
                "retry payload including tool schemas was too large: " + requestPayloadChars(retry));

        ToolSpec edit = retry.tools.stream()
                .filter(tool -> "talos.edit_file".equals(tool.name()))
                .findFirst()
                .orElseThrow();
        ToolSpec write = retry.tools.stream()
                .filter(tool -> "talos.write_file".equals(tool.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(edit.parametersSchemaJson().contains("old_string"), edit.parametersSchemaJson());
        assertTrue(edit.parametersSchemaJson().contains("new_string"), edit.parametersSchemaJson());
        assertTrue(write.parametersSchemaJson().contains("content"), write.parametersSchemaJson());
        assertFalse(edit.parametersSchemaJson().contains("line-number prefixes"), edit.parametersSchemaJson());
        assertTrue(edit.parametersSchemaJson().length() < 420, "edit retry schema too large");
        assertTrue(write.parametersSchemaJson().length() < 260, "write retry schema too large");
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

    @Test
    void staticWebCreationMissingMutationRetryUsesWriteFileAndCarriesRequirements() {
        RecordingResolver resolver = new RecordingResolver(List.of(
                "I can describe the site, but I will not call tools.",
                "Still no tool calls."));
        Context ctx = context(resolver);
        String prompt = "Create a complete modern dark synthwave static website for a band called Retrocats. "
                + "Use exactly index.html, style.css, and script.js as the local files. "
                + "Do not create a local tailwind.min.css file. "
                + "The site must preserve these required visible facts: Retrocats, Costanza, "
                + "Berlin 22 July 2026.";
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(prompt)
        ));

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages,
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(output.text().startsWith("[Action obligation failed:"), output.text());
        assertTrue(resolver.requests.size() >= 2, "expected initial call and retry call");
        assertEquals(List.of("talos.write_file"), sortedToolNames(resolver.requests.get(1)));
        String retryPrompt = joinedMessageContent(resolver.requests.get(1));
        assertTrue(retryPrompt.contains("[StaticWebRequirements]"), retryPrompt);
        assertTrue(retryPrompt.contains("Retrocats, Costanza, Berlin 22 July 2026"), retryPrompt);
        assertTrue(retryPrompt.contains("forbiddenArtifacts: tailwind.min.css"), retryPrompt);
    }

    @Test
    void staticFullRewriteMissingMutationRetryPreservesRepairContextAfterCompaction() {
        RecordingResolver resolver = new RecordingResolver(List.of(
                "Done. The repair is complete.",
                "I still will not call tools."));
        Context ctx = context(resolver);
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("OLD_HISTORY_MARKER " + "u".repeat(2_000)),
                ChatMessage.assistant("OLD_ASSISTANT_MARKER " + "a".repeat(2_000)),
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
        String retryPrompt = joinedMessageContent(resolver.requests.get(1));
        assertFalse(retryPrompt.contains("OLD_HISTORY_MARKER"), retryPrompt);
        assertFalse(retryPrompt.contains("OLD_ASSISTANT_MARKER"), retryPrompt);
        assertTrue(retryPrompt.contains("[Static verification repair context]"), retryPrompt);
        assertTrue(retryPrompt.contains("HTML does not link JavaScript file"), retryPrompt);
        assertTrue(retryPrompt.contains("Full-file replacement targets: index.html, scripts.js, styles.css"), retryPrompt);
        assertEquals(List.of("talos.write_file"), sortedToolNames(resolver.requests.get(1)));
    }

    @Test
    void staticFullRewriteMissingMutationRetryCompactsVerboseRepairContext() {
        RecordingResolver resolver = new RecordingResolver(List.of(
                "Done. The repair is complete.",
                "I still will not call tools."));
        Context ctx = context(resolver, realWriteEditToolSurface());
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.system(largeStaticRepairContext()),
                ChatMessage.user("Fix the remaining static verification problems.")
        ));

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages,
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(output.text().startsWith("[Action obligation failed:"), output.text());
        assertTrue(resolver.requests.size() >= 2, "expected initial call and retry call");
        ChatRequest retry = resolver.requests.get(1);
        String retryPrompt = joinedMessageContent(retry);
        assertTrue(retryPrompt.contains("[Static verification repair context]"), retryPrompt);
        assertTrue(retryPrompt.contains("Expected targets: index.html, scripts.js, styles.css"), retryPrompt);
        assertTrue(retryPrompt.contains("Missing expected targets: scripts.js"), retryPrompt);
        assertTrue(retryPrompt.contains("Previous static verification problems:"), retryPrompt);
        assertTrue(retryPrompt.contains("scripts.js: expected target was not successfully mutated."), retryPrompt);
        assertTrue(retryPrompt.contains("Full-file replacement targets: index.html, scripts.js, styles.css"), retryPrompt);
        assertFalse(retryPrompt.contains("VERBOSE_REPAIR_PADDING"), retryPrompt);
        assertFalse(retryPrompt.contains("Cross-file coherence checklist"), retryPrompt);
        assertTrue(retryPrompt.length() < 3_500, "retry prompt was too large: " + retryPrompt.length());
        assertEquals(List.of("talos.write_file"), sortedToolNames(retry));
    }

    @Test
    void compactMissingMutationRetryCanReachBackendWhenFullHistoryWouldExceedBudget() {
        BudgetGuardResolver resolver = new BudgetGuardResolver(
                List.of("Done. The files are complete.", "I still will not call tools."),
                8_000);
        Context ctx = context(resolver);
        var messages = new ArrayList<>(List.of(
                ChatMessage.system(largeLeadingSystemPrompt()),
                ChatMessage.user("OLD_HISTORY_MARKER " + "u".repeat(6_000)),
                ChatMessage.assistant("OLD_ASSISTANT_MARKER " + "a".repeat(6_000)),
                ChatMessage.system("OLD_RUNTIME_SYSTEM_MARKER " + "s".repeat(6_000)),
                ChatMessage.user("Create index.html, styles.css, and scripts.js for a BMI calculator.")
        ));

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages,
                Path.of("."),
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(output.text().startsWith("[Action obligation failed:"), output.text());
        assertEquals(2, resolver.requests.size(), "compact retry should reach the backend");
    }

    private static Context context(LlmEngineResolver resolver) {
        return context(resolver, broadToolSurface());
    }

    private static Context context(LlmEngineResolver resolver, List<ToolSpec> broadTools) {
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

    private static List<ToolSpec> realWriteEditToolSurface() {
        return List.<TalosTool>of(new FileEditTool(), new FileWriteTool()).stream()
                .map(TalosTool::descriptor)
                .map(descriptor -> new ToolSpec(
                        descriptor.name(),
                        descriptor.description(),
                        descriptor.parametersSchema()))
                .toList();
    }

    private static String largeLeadingSystemPrompt() {
        return """
                FULL_SYSTEM_MARKER
                You are Talos with a full ordinary turn prompt.
                This simulates workspace overview, behavior rules, tool policy prose, and long durable instructions.
                """
                + "full-system-padding ".repeat(500);
    }

    private static String largeStaticRepairContext() {
        return """
                [Static verification repair context]
                The previous mutation task ended incomplete after static verification. Use the prior verifier findings as the repair checklist for this turn.

                Expected targets: index.html, scripts.js, styles.css

                Missing expected targets: scripts.js

                Previous static verification problems:
                - scripts.js: expected target was not successfully mutated.
                - Expected web-app build to successfully mutate a JavaScript file.
                - JavaScript references missing class selectors: `.cta-button`

                Repair plan:
                Full-file replacement targets: index.html, scripts.js, styles.css
                - index.html: You must use talos.write_file with complete corrected file content for index.html.
                - scripts.js: You must use talos.write_file with complete corrected file content for scripts.js.
                - styles.css: You must use talos.write_file with complete corrected file content for styles.css.
                - Verify static checks again before claiming completion.

                Cross-file coherence checklist:
                - HTML must link every CSS and JavaScript file being written.
                - Every JavaScript ID or selector must exist in HTML before the JavaScript uses it.
                - CSS selectors should correspond to classes or IDs in HTML where practical.
                """
                + "VERBOSE_REPAIR_PADDING ".repeat(300);
    }

    private static List<String> sortedToolNames(ChatRequest request) {
        return request == null || request.tools == null
                ? List.of()
                : request.tools.stream()
                .map(ToolSpec::name)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static String joinedMessageContent(ChatRequest request) {
        return request == null || request.messages == null
                ? ""
                : request.messages.stream()
                .map(message -> message.content() == null ? "" : message.content())
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static int requestPayloadChars(ChatRequest request) {
        if (request == null) return 0;
        int total = joinedMessageContent(request).length();
        if (request.tools != null) {
            for (ToolSpec tool : request.tools) {
                if (tool == null) continue;
                total += tool.name() == null ? 0 : tool.name().length();
                total += tool.description() == null ? 0 : tool.description().length();
                total += tool.parametersSchemaJson() == null ? 0 : tool.parametersSchemaJson().length();
            }
        }
        return total;
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

    private static final class BudgetGuardResolver implements LlmEngineResolver {
        private final List<String> responses;
        private final int maxRequestChars;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int cursor;

        private BudgetGuardResolver(List<String> responses, int maxRequestChars) {
            this.responses = responses == null || responses.isEmpty()
                    ? List.of("")
                    : List.copyOf(responses);
            this.maxRequestChars = maxRequestChars;
        }

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            String joined = joinedMessageContent(request);
            if (cursor > 0 && joined.length() > maxRequestChars) {
                throw new AssertionError("request exceeded scripted backend budget: " + joined.length());
            }
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
