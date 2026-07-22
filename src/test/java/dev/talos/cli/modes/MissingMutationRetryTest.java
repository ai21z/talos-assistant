package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolError;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.impl.FileWriteTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingMutationRetryTest {

    @TempDir
    Path workspace;

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void missingMutationRetryPreservesProviderReceiptIntoToolParsing() {
        LocalTurnTraceCapture.begin(
                "missing-mutation-retry-receipt", "session", 1,
                "2026-07-16T00:00:00Z", "workspace", "auto", "test", "model",
                "retry receipt");
        String request = "Create notes.md with a single line of text.";
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(request)));
        CurrentTurnPlan safePlan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());
        var invalid = new ToolCallLoop.ToolOutcome(
                "talos.write_file", "notes.md", false, true, false, "",
                "Invalid parameters.", null, ToolError.INVALID_PARAMS);
        var loopResult = new ToolCallLoop.LoopResult(
                "", 1, 1, List.of("talos.write_file"), messages, 1, 0, false, 0,
                List.of(), 0, 0, 0, 0, List.of(invalid));
        var writeCall = new ChatMessage.NativeToolCall(
                "call_write_notes",
                "talos.write_file",
                Map.of("path", "notes.md", "content", "hello\n"));
        var provider = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(writeCall))),
                16_384);
        Context ctx = testContext(provider.client(), new FileWriteTool());

        MissingMutationRetry.retryIfNeeded(
                "I tried to create the file.", messages, safePlan, loopResult, workspace,
                ctx,
                (retryMessages, plan, specs) ->
                        TurnModelDispatcher.dispatchEscalatedRetry(
                                ctx, retryMessages, plan, specs));

        var trace = LocalTurnTraceCapture.complete();
        var parsed = trace.events().stream()
                .filter(event -> "TOOL_CALL_PARSED".equals(event.type()))
                .filter(event -> event.data().containsKey("providerGenerationId"))
                .findFirst().orElseThrow();
        var completed = trace.events().stream()
                .filter(event -> "GENERATION_COMPLETED".equals(event.type()))
                .filter(event -> event.data().get("generationId").equals(
                        parsed.data().get("providerGenerationId")))
                .findFirst().orElseThrow();
        assertEquals(completed.data().get("selectedAttemptId"),
                parsed.data().get("providerAttemptId"));
        assertEquals(1, parsed.data().get("providerProposalOrdinal"));
    }

    @Test
    void invalidParamsMutatingFailureGetsOneCorrectedRetryWithErrorEchoed() {
        String request = "Create notes.md with a single line of text.";
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(request)));
        CurrentTurnPlan safePlan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());
        var invalid = new ToolCallLoop.ToolOutcome(
                "talos.write_file", "notes.md", false, true, false, "",
                "Invalid operations_json: expected an array or an object with operations.",
                null, ToolError.INVALID_PARAMS);
        var loopResult = new ToolCallLoop.LoopResult(
                "", 1, 1, List.of("talos.write_file"), messages, 1, 0, false, 0,
                List.of(), 0, 0, 0, 0, List.of(invalid));

        List<String> capturedRetryPrompts = new ArrayList<>();
        MissingMutationRetry.Result result = MissingMutationRetry.retryIfNeeded(
                "I tried to create the file.", messages, safePlan, loopResult, workspace,
                testContext(),
                (retryMessages, plan, specs) -> {
                    retryMessages.forEach(m -> capturedRetryPrompts.add(m.content() == null ? "" : m.content()));
                    return new LlmClient.StreamResult("no tool calls in retry", List.of());
                });

        assertEquals(1, capturedRetryPrompts.stream()
                        .filter(c -> c.contains("rejected with invalid parameters")).count(),
                "invalid-params failure should fire exactly one corrected retry: " + capturedRetryPrompts);
        assertTrue(capturedRetryPrompts.stream().anyMatch(c ->
                        c.contains("Invalid operations_json: expected an array or an object with operations.")),
                "retry prompt must echo the tool error: " + capturedRetryPrompts);
        assertTrue(result.actionObligationFailed() || result.extraSummary() != null,
                "failed retry must surface obligation failure state");
    }

    @Test
    void deniedMutationStillSuppressesRetry() {
        String request = "Create notes.md with a single line of text.";
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(request)));
        CurrentTurnPlan safePlan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());
        var denied = new ToolCallLoop.ToolOutcome(
                "talos.write_file", "notes.md", false, true, true, "",
                "User did not approve the talos.write_file call.");
        var loopResult = new ToolCallLoop.LoopResult(
                "", 1, 1, List.of("talos.write_file"), messages, 1, 0, false, 0,
                List.of(), 0, 0, 0, 0, List.of(denied));

        List<String> captured = new ArrayList<>();
        MissingMutationRetry.Result result = MissingMutationRetry.retryIfNeeded(
                "Approval was denied.", messages, safePlan, loopResult, workspace,
                testContext(),
                (retryMessages, plan, specs) -> {
                    captured.add("retry-fired");
                    return new LlmClient.StreamResult("", List.of());
                });

        assertTrue(captured.isEmpty(), "denied mutation must never re-prompt");
        assertEquals("Approval was denied.", result.answer());
    }

    @Test
    void runtimeReplacementCannotInheritRetryProviderOwnerFromIdenticalBytes() {
        beginTrace("missing-mutation-provider-collision");
        String request = "Create notes.md with a single line of text.";
        String original = "I will create notes.md.";
        String deterministic = ResponseObligationVerifier.deterministicNoActionAnswer();
        providerTextResult(original);
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(request)));
        CurrentTurnPlan safePlan = mutationPlan(request);

        MissingMutationRetry.Result result = MissingMutationRetry.retryIfNeeded(
                original,
                messages,
                safePlan,
                retryableMutationLoop(messages),
                workspace,
                testContext(),
                (retryMessages, plan, specs) -> providerTextResult(deterministic));

        assertEquals(deterministic, result.answer(),
                "runtime replacement must preserve its existing user-visible bytes");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.answer());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertRuntimeTerminalOwner(trace);
    }

    @Test
    void runtimeReplacementCannotInheritPriorToolOwnerFromIdenticalBytes() {
        beginTrace("missing-mutation-tool-collision");
        String request = "Create notes.md with a single line of text.";
        String deterministic = ResponseObligationVerifier.deterministicNoActionAnswer();
        LocalTurnTraceCapture.recordToolAnswerSelected(deterministic);
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(request)));
        CurrentTurnPlan safePlan = mutationPlan(request);

        MissingMutationRetry.Result result = MissingMutationRetry.retryIfNeeded(
                deterministic,
                messages,
                safePlan,
                retryableMutationLoop(messages),
                workspace,
                testContext(),
                (retryMessages, plan, specs) -> {
                    throw new IllegalStateException("injected missing-mutation retry failure");
                });

        assertEquals(deterministic, result.answer(),
                "runtime replacement must preserve its existing user-visible bytes");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.answer());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertRuntimeTerminalOwner(trace);
    }

    @Test
    void wrappedCancellationEscapesMissingMutationRetry() {
        String request = "Create notes.md with a single line of text.";
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        RuntimeException wrapped = new RuntimeException(
                new CancellationException("cancel missing-mutation retry"));

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                MissingMutationRetry.retryIfNeeded(
                        "I will create notes.md.",
                        messages,
                        mutationPlan(request),
                        retryableMutationLoop(messages),
                        workspace,
                        testContext(),
                        (retryMessages, plan, specs) -> {
                            throw wrapped;
                        }));

        assertSame(wrapped, thrown,
                "retry cancellation must preserve the caller-owned runtime wrapper");
    }

    @Test
    void blankSuccessfulMutationSummaryRestoresExactOriginalProviderCandidate()
            throws Exception {
        beginTrace("missing-mutation-blank-success-restoration");
        String request = "Create notes.md with a single line of text.";
        String original = "I will create notes.md.";
        LlmClient.StreamResult originalResult = providerTextResult(original);
        ProviderResponseCapture.Receipt originalReceipt = originalResult.provenance();
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"), ChatMessage.user(request)));
        CurrentTurnPlan safePlan = mutationPlan(request);

        FileWriteTool delegate = new FileWriteTool();
        TalosTool blankOutputWrite = new TalosTool() {
            @Override
            public String name() {
                return delegate.name();
            }

            @Override
            public String description() {
                return delegate.description();
            }

            @Override
            public ToolDescriptor descriptor() {
                return delegate.descriptor();
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext context) {
                ToolResult result = delegate.execute(call, context);
                return result.success()
                        ? new ToolResult(
                                true, "", null, result.verification(),
                                result.contentMetadata())
                        : result;
            }
        };
        var writeCall = new ChatMessage.NativeToolCall(
                "call_write_notes",
                "talos.write_file",
                Map.of("path", "notes.md", "content", "hello\n"));
        var provider = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(writeCall))),
                16_384);
        Context ctx = testContext(provider.client(), blankOutputWrite);

        MissingMutationRetry.Result result = MissingMutationRetry.retryIfNeeded(
                original,
                messages,
                safePlan,
                retryableMutationLoop(messages),
                workspace,
                ctx,
                (retryMessages, plan, specs) ->
                        TurnModelDispatcher.dispatchEscalatedRetry(
                                ctx, retryMessages, plan, specs));

        assertEquals(original, result.answer());
        assertEquals(1, result.mutationsInRetry());
        assertEquals("hello\n", Files.readString(workspace.resolve("notes.md")));
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.answer());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        List<TurnTraceEvent> events = trace.events();
        List<TurnTraceEvent> blankToolCandidates = events.stream()
                .filter(event -> "DETERMINISTIC_ANSWER_SELECTED".equals(event.type()))
                .filter(event -> "TOOL_DETERMINISTIC".equals(event.data().get("origin")))
                .filter(event -> Integer.valueOf(0).equals(event.data().get("answerChars")))
                .toList();
        assertEquals(1, blankToolCandidates.size());
        TurnTraceEvent restoration = events.stream()
                .filter(event -> "ANSWER_CANDIDATE_RESTORED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("MODEL_PROVIDER", restoration.data().get("origin"));
        assertEquals(originalReceipt.generationId(),
                restoration.data().get("sourceGenerationId"));
        assertEquals(originalReceipt.attemptId(),
                restoration.data().get("sourceAttemptId"));
        assertEquals("SPECULATIVE_RETRY_EMPTY", restoration.data().get("reasonCode"));
        assertTrue(events.stream().noneMatch(event ->
                "ANSWER_TRANSFORMED".equals(event.type())));
        TurnTraceEvent terminal = events.stream()
                .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("MODEL_PROVIDER", terminal.data().get("origin"));
        assertEquals(originalReceipt.generationId(),
                terminal.data().get("sourceGenerationId"));
        assertEquals(originalReceipt.attemptId(),
                terminal.data().get("sourceAttemptId"));
        assertTrue(events.indexOf(blankToolCandidates.getFirst())
                < events.indexOf(restoration));
        assertTrue(events.indexOf(restoration) < events.indexOf(terminal));
    }

    private static CurrentTurnPlan mutationPlan(String request) {
        return CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());
    }

    private static ToolCallLoop.LoopResult retryableMutationLoop(List<ChatMessage> messages) {
        return new ToolCallLoop.LoopResult(
                "", 0, 0, List.of(), messages, 0, 0, false, 0,
                List.of(), 0, 0, 0, 0);
    }

    private static LlmClient.StreamResult providerTextResult(String text) {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeTerminal(
                attempt, ProviderTransportHandoff.DispatchEvidence.unbound());
        ProviderResponseCapture.Receipt receipt = attempt.respond(new ProviderResponseCapture.Response(
                text,
                List.of(),
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false,
                false));
        generation.complete(receipt);
        return new LlmClient.StreamResult(
                text, List.of(), "", "", ProviderResponseCapture.AbortOrigin.NONE,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false, false, receipt);
    }

    private static void assertRuntimeTerminalOwner(LocalTurnTrace trace) {
        TurnTraceEvent terminal = trace.events().stream()
                .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("RUNTIME_DETERMINISTIC", terminal.data().get("origin"));
        assertFalse(terminal.data().containsKey("sourceGenerationId"));
        assertFalse(terminal.data().containsKey("sourceAttemptId"));
    }

    private static void beginTrace(String traceId) {
        LocalTurnTraceCapture.begin(
                traceId,
                "session-missing-mutation-retry",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "verify missing-mutation retry answer lineage");
    }

    private Context testContext() {
        return testContext(new FileWriteTool());
    }

    private Context testContext(TalosTool writeTool) {
        var registry = new ToolRegistry();
        registry.register(writeTool);
        var processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        var loop = new ToolCallLoop(processor, 3);
        return Context.builder(new Config())
                .llm(LlmClient.scripted("unused"))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();
    }

    private Context testContext(LlmClient llm, TalosTool writeTool) {
        var registry = new ToolRegistry();
        registry.register(writeTool);
        var processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        return Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(processor, 3))
                .nativeToolSpecs(specsFrom(registry))
                .build();
    }

    private static List<ToolSpec> specsFrom(ToolRegistry registry) {
        return registry.descriptors().stream()
                .map(descriptor -> new ToolSpec(
                        descriptor.name(),
                        descriptor.description(),
                        descriptor.parametersSchema()))
                .toList();
    }

    @Test
    void compactStaticRepairContextBelongsToMissingMutationRetry() {
        ChatMessage compact = MissingMutationRetry.compactStaticVerificationRepairInstructionForRetry(
                ChatMessage.system("""
                        [Static verification repair context]
                        The previous mutation task ended incomplete after static verification.

                        Expected targets: index.html, scripts.js, styles.css

                        Missing expected targets: scripts.js

                        Previous static verification problems:
                        - scripts.js: expected target was not successfully mutated.
                        - HTML does not link JavaScript file: `scripts.js`
                        - Calculator/form task is missing a submit/calculate button.

                        Repair plan:
                        Full-file replacement targets: index.html, scripts.js, styles.css
                        - index.html: You must use talos.write_file with complete corrected file content for index.html.
                        - scripts.js: You must use talos.write_file with complete corrected file content for scripts.js.
                        - styles.css: You must use talos.write_file with complete corrected file content for styles.css.

                        Cross-file coherence checklist:
                        - HTML must link every CSS and JavaScript file being written.
                        - Every JavaScript ID or selector must exist in HTML before the JavaScript uses it.
                        """
                        + "VERBOSE_REPAIR_PADDING ".repeat(200)));

        String content = compact.content();
        assertTrue(content.startsWith("[Static verification repair context]"), content);
        assertTrue(content.contains("Expected targets: index.html, scripts.js, styles.css"), content);
        assertTrue(content.contains("Missing expected targets: scripts.js"), content);
        assertTrue(content.contains("scripts.js: expected target was not successfully mutated."), content);
        assertTrue(content.contains("Full-file replacement targets: index.html, scripts.js, styles.css"), content);
        assertFalse(content.contains("VERBOSE_REPAIR_PADDING"), content);
        assertFalse(content.contains("Cross-file coherence checklist"), content);
    }

    @Test
    void compactStaticRepairContextPreservesRequirementsAndDropsNonControllingSelectorInventory() {
        ChatMessage compact = MissingMutationRetry.compactStaticVerificationRepairInstructionForRetry(
                ChatMessage.system("""
                        [Static verification repair context]
                        Previous mutation task ended incomplete after static verification.

                        Expected targets: index.html, style.css, script.js

                        [StaticWebRequirements]
                        requiredVisibleFacts: Retrocats, Costanza, Merri, Rome 15 July 2026
                        forbiddenArtifacts: tailwind.css, tailwind.min.css

                        Previous static verification problems:
                        - tailwind.css: local Tailwind artifact is unsupported without an explicit build/runtime path.
                        - style.css: expected target was not successfully mutated.

                        Repair plan:
                        Full-file replacement targets: index.html, style.css, script.js

                        [Current static selector facts]
                        HTML classes: %s
                        CSS classes: %s
                        JavaScript selectors: %s
                        """.formatted(
                        "class-token ".repeat(250),
                        "css-token ".repeat(250),
                        "js-token ".repeat(250))));

        String content = compact.content();
        assertTrue(content.contains("[StaticWebRequirements]"), content);
        assertTrue(content.contains("requiredVisibleFacts: Retrocats, Costanza, Merri, Rome 15 July 2026"),
                content);
        assertTrue(content.contains("forbiddenArtifacts: tailwind.css, tailwind.min.css"), content);
        assertTrue(content.contains("Full-file replacement targets: index.html, style.css, script.js"), content);
        assertFalse(content.contains("[Current static selector facts]"), content);
        assertFalse(content.contains("class-token"), content);
        assertTrue(content.length() < 1_800, "compact repair context too large: " + content.length());
    }

    /**
     * T1007: the compact retry ToolSpec replaces the rich descriptor exactly
     * where failures concentrate, so it must carry the same grammar floor
     * (minLength) that keeps empty old_string unproducible on the managed
     * backend, and must keep teaching the anchor idiom.
     */
    @Test
    void compactRetryEditSpecKeepsTheGrammarFloorAndAnchorIdiom() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var editSpec = MissingMutationRetry.compactMutationRetryToolSpec(new dev.talos.spi.types.ToolSpec(
                "talos.edit_file", "ignored", "{}"));
        var writeSpec = MissingMutationRetry.compactMutationRetryToolSpec(new dev.talos.spi.types.ToolSpec(
                "talos.write_file", "ignored", "{}"));

        var editProps = mapper.readTree(editSpec.parametersSchemaJson()).get("properties");
        assertEquals(1, editProps.get("old_string").path("minLength").asInt(-1),
                "compact edit spec must keep minLength 1 on old_string");
        assertEquals(1, editProps.get("path").path("minLength").asInt(-1),
                "compact edit spec must keep minLength 1 on path");
        assertTrue(editProps.get("new_string").path("minLength").isMissingNode(),
                "new_string stays unconstrained for legitimate deletions");
        assertTrue(editSpec.description().toLowerCase(java.util.Locale.ROOT).contains("anchor"),
                "compact edit spec must keep the anchor idiom hint: " + editSpec.description());

        var writeProps = mapper.readTree(writeSpec.parametersSchemaJson()).get("properties");
        assertEquals(1, writeProps.get("path").path("minLength").asInt(-1),
                "compact write spec must keep minLength 1 on path");
    }

}
