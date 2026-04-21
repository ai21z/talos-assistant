package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guards for P0 (action-is-the-answer) and {@link ToolCallLoop#firstSentenceSummary}.
 *
 * <p>P0 problem: on local 31B Q4 models, the post-mutation re-prompt routinely
 * cost 5-15 minutes of wall-clock for an "okay, I created the file" reply the
 * user did not need (observed in the real transcript: 14m32s producing empty
 * text after a successful {@code talos.write_file}). The fix: when a tool-call
 * iteration had ≥1 successful mutating tool, skip the re-prompt entirely and
 * emit a deterministic action summary built from the tool output.
 *
 * <p>Proof-of-skip technique: build the loop with a {@link Context} whose
 * {@code llm()} is {@code null}. If the loop tried to re-prompt, it would NPE.
 * Therefore a passing test is direct evidence that the re-prompt was skipped.
 */
class ToolCallLoopP0Test {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @Nested
    class ActionIsTheAnswer {

        @Test
        void skipsRepromptAfterSuccessfulWriteFile() {
            // write_file success → loop should NOT call ctx.llm() again.
            // Context has no llm, so any re-prompt attempt would NPE.
            var loop = createLoop(fakeWriteFileTool());
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("system"),
                    ChatMessage.user("create index.html for me")));

            String llmResponse = """
                    <tool_call>
                    {"name": "talos.write_file", "parameters": {"path": "index.html", "content": "<html/>"}}
                    </tool_call>""";

            var result = loop.run(llmResponse, messages, WS, ctxWithoutLlm());

            // P0: one iteration, one tool, one mutation success, no re-prompt.
            assertEquals(1, result.iterations(), "should have executed one iteration");
            assertEquals(1, result.toolsInvoked());
            assertEquals(1, result.mutatingToolSuccesses());
            assertEquals(0, result.failedCalls());

            // The deterministic answer replaces what would have been the
            // model's post-mutation commentary.
            assertTrue(result.finalAnswer().startsWith("✓ "),
                    "answer should start with action check mark, got: " + result.finalAnswer());
            assertTrue(result.finalAnswer().contains("Created index.html"),
                    "answer should carry the first sentence of the tool output, got: "
                            + result.finalAnswer());
            // No stray tool-call XML from the original prose.
            assertFalse(result.finalAnswer().contains("<tool_call>"));
        }

        @Test
        void skipIsPerIteration_readsThenWritesStillSkipsAfterWrite() {
            // Mixed batch in one iteration: a read-only echo + a mutating write.
            // The mutation triggers the P0 skip just the same.
            var loop = createLoop(fakeWriteFileTool(), readOnlyEchoTool());
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("system"),
                    ChatMessage.user("update index.html")));

            String llmResponse = """
                    <tool_call>
                    {"name": "talos.echo", "parameters": {"input": "probing"}}
                    </tool_call>
                    <tool_call>
                    {"name": "talos.write_file", "parameters": {"path": "index.html", "content": "x"}}
                    </tool_call>""";

            var result = loop.run(llmResponse, messages, WS, ctxWithoutLlm());

            assertEquals(1, result.iterations());
            assertEquals(2, result.toolsInvoked());
            assertEquals(1, result.mutatingToolSuccesses());
            assertTrue(result.finalAnswer().contains("✓ "),
                    "answer should carry the mutation summary, got: " + result.finalAnswer());
        }

        @Test
        void noSkipWhenBatchIsOnlyReadOnly() {
            // No mutations → the existing re-prompt path must still run.
            // With a null llm this SHOULD NPE, which proves the skip is
            // correctly gated on the presence of successful mutations.
            var loop = createLoop(readOnlyEchoTool());
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("system"),
                    ChatMessage.user("what is in this workspace?")));

            String llmResponse = """
                    <tool_call>
                    {"name": "talos.echo", "parameters": {"input": "probing"}}
                    </tool_call>""";

            // The loop catches Exception around the re-prompt and converts
            // the error into a textual answer — so this completes without
            // propagating, but the answer must NOT be a mutation summary.
            var result = loop.run(llmResponse, messages, WS, ctxWithoutLlm());

            assertEquals(0, result.mutatingToolSuccesses());
            assertFalse(result.finalAnswer().startsWith("✓ "),
                    "read-only batch must NOT synthesize an action summary");
        }
    }

    // ── CCR-020 — partial-success iterations must re-prompt ────────────

    @Nested
    class PartialSuccessRepromptTests {

        @Test
        void repromptsAfterPartialSuccessMixedMutationBatch() {
            // Mixed batch in ONE iteration: one mutating tool succeeds, a
            // second mutating tool fails. Pre-CCR-020 this short-circuited
            // and left the workspace half-edited; CCR-020 requires the
            // loop to re-prompt so the model can retry the failed edit.
            //
            // With a stub Context (LLM unavailable for real use), the
            // re-prompt path captures the exception and converts it to an
            // error string. We assert:
            //   (a) the loop did NOT emit a "✓ …" mutation summary as the
            //       final answer (that would indicate the P0 skip fired),
            //   (b) the final answer reflects the re-prompt branch.
            var loop = createLoop(fakeWriteFileTool(), alwaysFailingEditTool());
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("system"),
                    ChatMessage.user("update index.html and style.css")));

            String llmResponse = """
                    <tool_call>
                    {"name": "talos.write_file", "parameters": {"path": "index.html", "content": "<html/>"}}
                    </tool_call>
                    <tool_call>
                    {"name": "talos.edit_file", "parameters": {"path": "style.css", "old_string": "a", "new_string": "b"}}
                    </tool_call>""";

            var result = loop.run(llmResponse, messages, WS, ctxWithoutLlm());

            assertEquals(1, result.mutatingToolSuccesses(),
                    "write_file should have succeeded");
            assertTrue(result.failedCalls() >= 1,
                    "edit_file should have failed");
            assertFalse(result.finalAnswer().startsWith("✓ "),
                    "partial-success iteration MUST NOT short-circuit to a "
                            + "plain mutation summary (CCR-020); got: "
                            + result.finalAnswer());
        }

        @Test
        void stillSkipsWhenEveryCallInIterationSucceeds() {
            // Regression guard: the original P0 behavior must still hold
            // when there are zero failures in the iteration. A null-llm
            // stub proves re-prompt was not attempted (any attempt would
            // NPE or error-stub the answer).
            var loop = createLoop(fakeWriteFileTool());
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("system"),
                    ChatMessage.user("create index.html")));

            String llmResponse = """
                    <tool_call>
                    {"name": "talos.write_file", "parameters": {"path": "index.html", "content": "<html/>"}}
                    </tool_call>""";

            var result = loop.run(llmResponse, messages, WS, ctxWithoutLlm());

            assertEquals(1, result.mutatingToolSuccesses());
            assertEquals(0, result.failedCalls(),
                    "no failures in the iteration");
            assertTrue(result.finalAnswer().startsWith("✓ "),
                    "all-success iteration must still skip re-prompt and "
                            + "emit the deterministic action summary");
        }
    }

    @Nested
    class FirstSentenceSummary {

        @Test
        void extractsHeadSentenceFromWriteFileSuccessString() {
            String out = "Created index.html (79 lines, 2847 bytes). Verified: HTML structure OK. [verified by checker v1]";
            assertEquals("Created index.html (79 lines, 2847 bytes)",
                    ToolCallLoop.firstSentenceSummary(out));
        }

        @Test
        void dropsTrailingBracketAnnotation() {
            String out = "Wrote config.yaml [verified]";
            assertEquals("Wrote config.yaml",
                    ToolCallLoop.firstSentenceSummary(out));
        }

        @Test
        void handlesMissingTerminatorViaNewlineOrLengthCap() {
            String out = "Updated build.gradle.kts\nmore context below";
            assertEquals("Updated build.gradle.kts",
                    ToolCallLoop.firstSentenceSummary(out));
        }

        @Test
        void stripsToolResultHeaderIfPresent() {
            String out = "[tool_result: talos.write_file]\nCreated a.txt (3 bytes).";
            assertEquals("Created a.txt (3 bytes)",
                    ToolCallLoop.firstSentenceSummary(out));
        }

        @Test
        void hardCapsPathologicallyLongSingleSentences() {
            String out = "x".repeat(500);
            String summary = ToolCallLoop.firstSentenceSummary(out);
            assertTrue(summary.length() <= 160);
            assertTrue(summary.endsWith("…"));
        }

        @Test
        void nullOrBlankYieldsEmpty() {
            assertEquals("", ToolCallLoop.firstSentenceSummary(null));
            assertEquals("", ToolCallLoop.firstSentenceSummary(""));
            assertEquals("", ToolCallLoop.firstSentenceSummary("   \n  "));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static ToolCallLoop createLoop(TalosTool... tools) {
        var registry = new ToolRegistry();
        for (TalosTool t : tools) registry.register(t);
        var processor = new TurnProcessor(
                ModeController.defaultController(), new NoOpApprovalGate(), registry);
        return new ToolCallLoop(processor);
    }

    /** A Context with no LLM wired — any re-prompt attempt will NPE. */
    private static Context ctxWithoutLlm() {
        return Context.builder(new Config()).build();
    }

    /** A fake {@code talos.write_file} that returns the real success string shape. */
    private static TalosTool fakeWriteFileTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.write_file"; }
            @Override public String description() { return "Fake write_file for tests"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.write_file", "write a file");
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                String path = call.param("path", "unknown");
                String content = call.param("content", "");
                return ToolResult.ok("Created " + path + " ("
                        + (content.split("\n").length) + " lines, "
                        + content.getBytes().length + " bytes). Verified: HTML structure OK.");
            }
        };
    }

    private static TalosTool readOnlyEchoTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.echo"; }
            @Override public String description() { return "Echo"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.echo", "Echo");
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.ok("echo: " + call.param("input", ""));
            }
        };
    }

    /**
     * A fake {@code talos.edit_file} that always fails with an
     * old-string-not-found error. Used to drive the CCR-020 partial-success
     * branch (one mutation succeeds, this one fails in the same iteration).
     */
    private static TalosTool alwaysFailingEditTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.edit_file"; }
            @Override public String description() { return "Fake edit_file that always fails"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.edit_file", "edit a file");
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.fail(dev.talos.tools.ToolError.invalidParams(
                        "old_string not found in " + call.param("path", "file")
                                + ". The exact text was not found in the file."));
            }
        };
    }
}

