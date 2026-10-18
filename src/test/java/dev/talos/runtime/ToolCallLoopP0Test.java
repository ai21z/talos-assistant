package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * {@code llm()} is an unavailable scripted client. If the loop tries to re-prompt,
     * that provider failure propagates instead of being converted into assistant text.
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
            // With an unavailable scripted LLM this must propagate the
            // provider failure, which proves the skip is correctly gated
            // on the presence of successful mutations.
            var loop = createLoop(readOnlyEchoTool());
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("system"),
                    ChatMessage.user("what is in this workspace?")));

            String llmResponse = """
                    <tool_call>
                    {"name": "talos.echo", "parameters": {"input": "probing"}}
                    </tool_call>""";

            TurnTaskContractCapture.set(
                    TaskContractResolver.resolveFromMessages(messages),
                    messages);
            try {
                var failure = assertThrows(IllegalStateException.class,
                        () -> loop.run(
                                llmResponse,
                                messages,
                                WS,
                                ctxWithoutLlm(readOnlyEchoTool())));

                assertEquals("test LLM unavailable", failure.getMessage());
                assertTrue(messages.stream().anyMatch(message ->
                                message.content() != null
                                        && message.content().contains("echo: probing")),
                        "read-only tool must execute before the provider re-prompt fails");
            } finally {
                TurnTaskContractCapture.clear();
            }
        }

        @Test
        void rejectedProviderAuthorityStopsWithoutAnyProviderReprompt() {
            var loop = createLoop(fakeWriteFileTool());
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("system"),
                    ChatMessage.user("create index.html")));
            List<ChatMessage.NativeToolCall> proposals = List.of(
                    new ChatMessage.NativeToolCall(
                            "call-static-only",
                            "talos.write_file",
                            Map.of(
                                    "path", "index.html",
                                    "content", "<html/>")));

            beginTrace("invalid-provider-authority-no-reprompt");
            TurnTaskContractCapture.set(
                    TaskContractResolver.resolveFromMessages(messages),
                    messages);
            try {
                ProviderResponseCapture.Receipt receipt =
                        boundButProviderIneligibleNativeReceipt(
                                proposals,
                                List.of("talos.write_file"));
                assertTrue(receipt.toolAuthorityContract().bound());
                assertFalse(receipt.providerToolCallEligible());

                ToolCallLoop.LoopResult result = loop.run(
                        "",
                        proposals,
                        receipt,
                        messages,
                        WS,
                        ctxWithoutLlm(fakeWriteFileTool()));
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertEquals(1, result.iterations());
                assertEquals(1, result.failedCalls());
                assertEquals(0, result.toolsInvoked());
                assertEquals(0, result.mutatingToolSuccesses());
                assertEquals(1, result.toolOutcomes().size());
                assertEquals(
                        ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID,
                        result.toolOutcomes().getFirst().failureReason());
                assertTrue(result.failureDecision().shouldStop());
                assertEquals(
                        FailureAction.ASK_USER,
                        result.failureDecision().action());
                assertTrue(
                        result.finalAnswer().contains(
                                "No follow-up provider request was sent"),
                        result.finalAnswer());
                assertEquals(
                        0,
                        trace.events().stream()
                                .filter(event -> "APPROVAL_REQUIRED"
                                        .equals(event.type()))
                                .count());
                assertEquals(
                        0,
                        trace.events().stream()
                                .filter(event -> "CHECKPOINT_CAPTURED"
                                        .equals(event.type()))
                                .count());
                assertEquals(
                        0,
                        trace.events().stream()
                                .filter(event -> "TOOL_EXECUTED"
                                        .equals(event.type()))
                                .count());
            } finally {
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void rejectedRuntimeAuthorityStopsNeutrallyWithoutProviderReprompt() {
            var loop = createLoop(fakeWriteFileTool());
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("system"),
                    ChatMessage.user("read index.html")));
            List<ChatMessage.NativeToolCall> proposals = List.of(
                    new ChatMessage.NativeToolCall(
                            "call-runtime-write",
                            "talos.write_file",
                            Map.of(
                                    "path", "index.html",
                                    "content", "<html/>")));

            beginTrace("invalid-runtime-authority-no-reprompt");
            TurnTaskContractCapture.set(
                    TaskContractResolver.resolveFromMessages(messages),
                    messages);
            try {
                ToolCallLoop.LoopResult result = loop.runRuntimeDeterministic(
                        "",
                        proposals,
                        LocalTurnTraceCapture.RuntimeToolAuthorityReason
                                .READ_EVIDENCE_HANDOFF,
                        messages,
                        WS,
                        ctxWithoutLlm(fakeWriteFileTool()));
                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertEquals(1, result.iterations());
                assertEquals(1, result.failedCalls());
                assertEquals(0, result.toolsInvoked());
                assertEquals(0, result.mutatingToolSuccesses());
                assertEquals(
                        ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID,
                        result.toolOutcomes().getFirst().failureReason());
                assertTrue(result.failureDecision().shouldStop());
                assertEquals(FailureAction.ASK_USER, result.failureDecision().action());
                assertTrue(
                        result.finalAnswer().contains("executable tool authority was invalid"),
                        result.finalAnswer());
                assertFalse(
                        result.failureDecision().reason().toLowerCase().contains("provider"),
                        result.failureDecision().reason());
                assertFalse(
                        result.failureDecision().reason().toLowerCase().contains("model"),
                        result.failureDecision().reason());
                assertEquals(
                        0,
                        trace.events().stream()
                                .filter(event -> "APPROVAL_REQUIRED".equals(event.type()))
                                .count());
                assertEquals(
                        0,
                        trace.events().stream()
                                .filter(event -> "CHECKPOINT_CAPTURED".equals(event.type()))
                                .count());
                assertEquals(
                        0,
                        trace.events().stream()
                                .filter(event -> "TOOL_EXECUTED".equals(event.type()))
                                .count());
            } finally {
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }
        }
    }

    // ── CCR-020 - partial-success iterations must re-prompt ────────────

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
            // re-prompt path must propagate the provider failure. The
            // recorded tool-result messages prove both calls ran before
            // that failure; returning a mutation summary would not throw.
            var loop = createLoop(fakeWriteFileTool(), alwaysFailingEditTool());
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("system"),
                    ChatMessage.user("update index.html and style.css")));

            List<ChatMessage.NativeToolCall> proposals = List.of(
                    new ChatMessage.NativeToolCall(
                            "call-write",
                            "talos.write_file",
                            Map.of(
                                    "path", "index.html",
                                    "content", "<html/>")),
                    new ChatMessage.NativeToolCall(
                            "call-edit",
                            "talos.edit_file",
                            Map.of(
                                    "path", "style.css",
                                    "old_string", "a",
                                    "new_string", "b")));

            beginTrace("partial-success-provider-reprompt");
            TurnTaskContractCapture.set(
                    TaskContractResolver.resolveFromMessages(messages),
                    messages);
            try {
                ProviderResponseCapture.Receipt receipt =
                        boundNativeProviderReceipt(
                                proposals,
                                List.of(
                                        "talos.write_file",
                                        "talos.edit_file"));
                var failure = assertThrows(IllegalStateException.class,
                        () -> loop.run(
                                "",
                                proposals,
                                receipt,
                                messages,
                                WS,
                                ctxWithoutLlm(
                                        fakeWriteFileTool(),
                                        alwaysFailingEditTool())));

                assertEquals("test LLM unavailable", failure.getMessage());
                assertTrue(messages.stream().anyMatch(message ->
                                message.content() != null
                                        && message.content().contains("Created index.html")),
                        "write_file should succeed before the provider re-prompt fails");
                assertTrue(messages.stream().anyMatch(message ->
                                message.content() != null
                                        && message.content().contains("old_string not found in style.css")),
                        "edit_file failure must be recorded before the provider re-prompt fails");
            } finally {
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }
        }

        @Test
        void receiptLessPartialMutationBatchCannotAcquireProviderRepromptAuthority() {
            var loop = createLoop(fakeWriteFileTool(), alwaysFailingEditTool());
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("system"),
                    ChatMessage.user("update index.html and style.css")));
            String runtimeOwnedBatch = """
                    <tool_call>
                    {"name": "talos.write_file", "parameters": {"path": "index.html", "content": "<html/>"}}
                    </tool_call>
                    <tool_call>
                    {"name": "talos.edit_file", "parameters": {"path": "style.css", "old_string": "a", "new_string": "b"}}
                    </tool_call>""";
            TurnTaskContractCapture.set(
                    TaskContractResolver.resolveFromMessages(messages),
                    messages);
            try {
                ToolCallLoop.LoopResult result = loop.run(
                        runtimeOwnedBatch,
                        messages,
                        WS,
                        ctxWithoutLlm(
                                fakeWriteFileTool(),
                                alwaysFailingEditTool()));

                assertEquals(1, result.mutatingToolSuccesses());
                assertEquals(1, result.failedCalls());
                assertTrue(
                        result.finalAnswer().startsWith(
                                "[Action obligation failed: pending expected target progress"),
                        result.finalAnswer());
                assertTrue(
                        result.finalAnswer().contains(
                                "required retry tool is unavailable in the captured current-turn surface"),
                        result.finalAnswer());
                assertFalse(
                        result.finalAnswer().contains("test LLM unavailable"),
                        result.finalAnswer());
                assertTrue(result.failureDecision().shouldStop());
                assertEquals(2, result.toolOutcomes().size());

                ToolCallLoop.ToolOutcome write = result.toolOutcomes().get(0);
                assertEquals("talos.write_file", write.toolName());
                assertEquals("index.html", write.pathHint());
                assertTrue(write.success());
                assertTrue(write.mutating());
                assertFalse(write.denied());
                assertTrue(write.summary().contains("Created index.html"), write.summary());

                ToolCallLoop.ToolOutcome edit = result.toolOutcomes().get(1);
                assertEquals("talos.edit_file", edit.toolName());
                assertEquals("style.css", edit.pathHint());
                assertFalse(edit.success());
                assertTrue(edit.mutating());
                assertFalse(edit.denied());
                assertTrue(
                        edit.errorMessage().contains(
                                "old_string not found in style.css"),
                        edit.errorMessage());
            } finally {
                TurnTaskContractCapture.clear();
            }
        }

        @Test
        void stillSkipsWhenEveryCallInIterationSucceeds() {
            // Regression guard: the original P0 behavior must still hold
            // when there are zero failures in the iteration. A null-llm
            // stub proves re-prompt was not attempted (any attempt would
            // error-stub the answer).
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

    /** A Context with an unavailable scripted LLM; any re-prompt attempt propagates its failure. */
    private static Context ctxWithoutLlm(TalosTool... availableTools) {
        List<ToolSpec> specs = new ArrayList<>();
        for (TalosTool tool : availableTools) {
            ToolDescriptor descriptor = tool.descriptor();
            specs.add(new ToolSpec(
                    descriptor.name(),
                    descriptor.description(),
                    descriptor.parametersSchema() == null
                            ? "{}"
                            : descriptor.parametersSchema()));
        }
        var builder = Context.builder(placeholderConfig())
                .llm(LlmClient.scriptedFailure(
                        new IllegalStateException(
                                "test LLM unavailable")));
        if (!specs.isEmpty()) {
            builder.nativeToolSpecs(specs);
        }
        return builder.build();
    }

    private static Config placeholderConfig() {
        Config cfg = new Config();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "placeholder");
        llm.put("default_backend", "llama_cpp");
        cfg.data.put("llm", llm);
        return cfg;
    }

    private static ProviderResponseCapture.Receipt boundNativeProviderReceipt(
            List<ChatMessage.NativeToolCall> proposals,
            List<String> selectedToolNames
    ) {
        ProviderResponseCapture.Generation generation =
                ProviderResponseCapture.openGeneration(
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Purpose.TURN_RESPONSE,
                        ProviderResponseCapture.ToolAuthorityContract.bound(
                                ProviderResponseCapture.ToolAuthorityMode
                                        .PROVIDER_NATIVE,
                                selectedToolNames,
                                ToolChoiceMode.AUTO,
                                ""));
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeTerminal(
                attempt,
                new ProviderTransportHandoff.DispatchEvidence(
                        "sha256:" + "a".repeat(64),
                        selectedToolNames));
        ProviderResponseCapture.Receipt receipt = attempt.respond(
                new ProviderResponseCapture.Response(
                        "",
                        proposals.size(),
                        proposals,
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false,
                        false,
                        false));
        generation.complete(receipt);
        return receipt;
    }

    private static ProviderResponseCapture.Receipt
            boundButProviderIneligibleNativeReceipt(
                    List<ChatMessage.NativeToolCall> proposals,
                    List<String> selectedToolNames
            ) {
        ProviderResponseCapture.Generation generation =
                ProviderResponseCapture.openGeneration(
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Purpose.TURN_RESPONSE,
                        ProviderResponseCapture.ToolAuthorityContract.bound(
                                ProviderResponseCapture.ToolAuthorityMode
                                        .PROVIDER_NATIVE,
                                selectedToolNames,
                                ToolChoiceMode.AUTO,
                                ""));
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched(
                new ProviderTransportHandoff.DispatchEvidence(
                        "sha256:" + "d".repeat(64),
                        selectedToolNames));
        ProviderResponseCapture.Receipt receipt = attempt.respond(
                new ProviderResponseCapture.Response(
                        "",
                        proposals,
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false,
                        false,
                        false));
        generation.complete(receipt);
        return receipt;
    }

    private static void beginTrace(String suffix) {
        LocalTurnTraceCapture.begin(
                "trace-p0-" + suffix,
                "session-p0",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-p0",
                "test",
                "scripted",
                "model",
                suffix);
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

