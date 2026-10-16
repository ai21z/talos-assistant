package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.llm.TextToolCallInstructions;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRepromptRequestBuilderTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearCapturedTurn() {
        TurnTaskContractCapture.clear();
    }

    @Test
    void boundedStaticRepairAddsSharedFormatOnlyForActualSelectedTextSurface() {
        for (String mode : List.of("text", "native", "none")) {
            boolean textual = mode.equals("text");
            try (LlmClient llm = textual ? ScriptedNativeLlmClient.textToolFallbackStrings("unused")
                    : ScriptedNativeLlmClient.of(List.of(new LlmClient.StreamResult("unused", List.of())))) {
                List<ToolSpec> selected = mode.equals("none") ? List.of() : List.of(tool("talos.write_file"));
                Context context = Context.builder(new Config(null)).llm(llm).nativeToolSpecs(selected).build();
                LoopState state = new LoopState("", List.of(), ProviderResponseCapture.Receipt.none(),
                        List.of(ChatMessage.system("old manual"), ChatMessage.user("Fix index.html.")),
                        tempDir, context, null, 5, 0);

                List<ChatMessage> messages = ToolRepromptRequestBuilder.messages(
                        state, true, List.of("index.html"), "Fix index.html.", selected);
                String assembled = messages.stream().map(ChatMessage::content)
                        .collect(java.util.stream.Collectors.joining("\n"));

                assertEquals(List.of("system", "system", "user"),
                        messages.stream().map(ChatMessage::role).toList());
                assertFalse(assembled.contains("old manual"));
                assertTrue(assembled.contains("Call talos.write_file with complete corrected file content"));
                assertTrue(assembled.contains("Do not write any other file in this continuation"));
                assertTrue(assembled.contains("Fix index.html."));
                assertEquals(textual ? 1 : 0,
                        assembled.split("Textual tool-call format:", -1).length - 1);
                if (textual) assertTrue(assembled.contains(TextToolCallInstructions.formatContract()));
                assertEquals(selected, context.nativeToolSpecs());
            }
        }
    }

    @Test
    void staticRepairProgressNarrowsToolsToWriteFileWhenAvailable() {
        LoopState state = loopState(broadTools(), List.of(ChatMessage.user("Fix the page.")));

        List<ToolSpec> tools = ToolRepromptRequestBuilder.toolSpecs(state, true, false);

        assertEquals(List.of("talos.write_file"), toolNames(tools));
    }

    @Test
    void expectedTargetProgressNarrowsToolsToWriteAndEditWhenAvailable() {
        LoopState state = loopState(broadTools(), List.of(ChatMessage.user("Edit README.md.")));

        List<ToolSpec> tools = ToolRepromptRequestBuilder.toolSpecs(state, false, true);

        assertEquals(List.of("talos.write_file", "talos.edit_file"), toolNames(tools));
    }

    @Test
    void staticWebExpectedTargetProgressNarrowsToolsToWriteFileOnly() {
        LoopState state = loopState(
                broadTools(),
                List.of(ChatMessage.user(
                        "Create a complete website. Use exactly index.html, style.css, and script.js.")));

        List<ToolSpec> tools = ToolRepromptRequestBuilder.toolSpecs(state, false, true);

        assertEquals(List.of("talos.write_file"), toolNames(tools));
    }

    @Test
    void narrowingFailsClosedWhenNoRequestedToolsAreAvailable() {
        List<ToolSpec> readOnlyTools = List.of(tool("talos.read_file"), tool("talos.list_dir"));
        LoopState state = loopState(readOnlyTools, List.of(ChatMessage.user("Fix README.md.")));

        List<ToolSpec> tools = ToolRepromptRequestBuilder.toolSpecs(state, true, false);

        assertTrue(tools.isEmpty());
    }

    @Test
    void nestedRepromptFailsClosedWithoutExactBoundResponseAuthority() {
        LoopState state = loopState(
                broadTools(),
                List.of(ChatMessage.user("Fix README.md.")));

        state.currentResponseReceipt = null;
        assertTrue(ToolRepromptRequestBuilder.currentNativeToolSpecs(
                state).isEmpty());

        state.currentResponseReceipt =
                ProviderResponseCapture.Receipt.none();
        assertTrue(ToolRepromptRequestBuilder.currentNativeToolSpecs(
                state).isEmpty());

        state.currentResponseReceipt =
                unboundProviderReceipt();
        assertTrue(ToolRepromptRequestBuilder.currentNativeToolSpecs(
                state).isEmpty());

        ProviderResponseCapture.Receipt staticOnly =
                boundButProviderIneligibleReceipt(
                        broadTools().stream()
                                .map(ToolSpec::name)
                                .toList());
        assertTrue(staticOnly.toolAuthorityContract().bound());
        assertFalse(staticOnly.providerToolCallEligible());
        state.currentResponseReceipt = staticOnly;
        assertTrue(ToolRepromptRequestBuilder.currentNativeToolSpecs(
                state).isEmpty());
    }

    @Test
    void staticRepairMessagesPreserveCompactPayloadAndCurrentTask() {
        LoopState state = loopState(
                broadTools(),
                List.of(
                        ChatMessage.system("old broad tool manual talos.run_command"),
                        ChatMessage.user("old unrelated task"),
                        ChatMessage.system("""
                                [Static verification repair context]
                                Expected targets: index.html, scripts.js, styles.css

                                Previous static verification problems:
                                - HTML does not link JavaScript file: `scripts.js`

                                Full-file replacement targets: index.html, scripts.js, styles.css
                                """),
                        ChatMessage.user("Fix the remaining static page issue.")));

        List<ChatMessage> messages =
                ToolRepromptRequestBuilder.messages(
                        state,
                        true,
                        List.of("scripts.js", "styles.css"),
                        "Fix the remaining static page issue.");

        String payload = messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
        assertEquals(4, messages.size());
        assertFalse(payload.contains("old broad tool manual"), payload);
        assertFalse(payload.contains("old unrelated task"), payload);
        assertTrue(payload.contains("You are Talos, a local-first workspace assistant."), payload);
        assertTrue(payload.contains("[Static verification repair context]"), payload);
        assertTrue(payload.contains("[Static repair progress]"), payload);
        assertTrue(payload.contains("scripts.js, styles.css"), payload);
        assertTrue(payload.contains("Fix the remaining static page issue."), payload);
    }

    @Test
    void staticRepairMessagesIncludeReadbackForRemainingRepairTarget() throws Exception {
        // T1010/T1011: the readback binds to the current disk bytes of the
        // exact target and travels as structured data, never keyed off
        // cached read signatures.
        Files.writeString(tempDir.resolve("styles.css"),
                "body { color: #fff; }\n.stage { padding: 3rem; }\n");
        LoopState state = loopState(
                broadTools(),
                List.of(ChatMessage.user("Adjust styles.css as needed.")),
                tempDir);

        List<ChatMessage> messages =
                ToolRepromptRequestBuilder.messages(
                        state,
                        true,
                        List.of("styles.css"),
                        "Adjust styles.css as needed.");

        String payload = messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(payload.contains("[StaticRepairReadbacks]"), payload);
        assertTrue(payload.contains("\"path\":\"styles.css\""), payload);
        assertTrue(payload.contains(".stage { padding: 3rem; }"), payload);
    }

    @Test
    void staticRepairMessagesReadCurrentRemainingTargetWhenReadCacheWasCleared() throws Exception {
        Files.writeString(tempDir.resolve("styles.css"), """
                body {
                  background: #14061f;
                }

                .stage {
                  padding: 3rem;
                }
                """);
        LoopState state = loopState(
                broadTools(),
                List.of(ChatMessage.user("Adjust styles.css as needed.")),
                tempDir);

        List<ChatMessage> messages =
                ToolRepromptRequestBuilder.messages(
                        state,
                        true,
                        List.of("styles.css"),
                        "Adjust styles.css as needed.");

        String payload = messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(payload.contains("[StaticRepairReadbacks]"), payload);
        assertTrue(payload.contains("\"path\":\"styles.css\""), payload);
        assertTrue(payload.contains("background: #14061f;"), payload);
        assertTrue(payload.contains(".stage"), payload);
    }

    @Test
    void staticRepairMessagesDoNotReadRemainingTargetOutsideWorkspace() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(tempDir.resolve("outside.css"), "body { color: hotpink; }");
        LoopState state = loopState(
                broadTools(),
                List.of(ChatMessage.user("Adjust styles.css as needed.")),
                workspace);

        List<ChatMessage> messages =
                ToolRepromptRequestBuilder.messages(
                        state,
                        true,
                        List.of("../outside.css"),
                        "Adjust styles.css as needed.");

        String payload = messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(payload.contains("[StaticRepairReadbacks]"), payload);
        assertFalse(payload.contains("hotpink"), payload);
    }

    @Test
    void staticRepairMessagesUseTargetedFinalUserInstruction() {
        LoopState state = loopState(
                broadTools(),
                List.of(ChatMessage.user("Update index.html and scripts.js. Adjust styles.css as needed.")));

        List<ChatMessage> messages =
                ToolRepromptRequestBuilder.messages(
                        state,
                        true,
                        List.of("styles.css"),
                        "Update index.html and scripts.js. Adjust styles.css as needed.");

        ChatMessage last = messages.get(messages.size() - 1);
        assertEquals("user", last.role());
        assertTrue(last.content().contains("Repair exactly the remaining static-web target path(s): styles.css"),
                last.content());
        assertTrue(last.content().contains("Do not write any other file in this continuation."), last.content());
        assertTrue(last.content().contains("Original user request:"), last.content());
    }

    @Test
    void nonStaticRepromptWithoutCapturedResolutionFailsClosed() {
        List<ChatMessage> messages = List.of(ChatMessage.system("sys"), ChatMessage.user("Continue."));
        LoopState state = loopState(broadTools(), messages);
        dev.talos.runtime.TurnTaskContractCapture.clear();

        assertThrows(
                IllegalStateException.class,
                () -> ToolRepromptRequestBuilder.messages(
                        state,
                        false,
                        List.of(),
                        "Continue."));
    }

    @Test
    void pendingActionObligationUsesRequiredToolChoiceOnlyWhenSupportedAndMutatingToolsExist() {
        LoopState state = loopState(broadTools(), List.of(ChatMessage.user("Edit README.md.")));
        state.setPendingActionObligation(PendingActionObligation.expectedTargets(List.of("README.md")));

        ChatRequestControls controls = ToolRepromptRequestBuilder.controls(state, "expected-target", true);
        ChatRequestControls unsupported = ToolRepromptRequestBuilder.controls(state, "expected-target", false);
        LoopState readOnlyState = loopState(List.of(tool("talos.read_file")), List.of(ChatMessage.user("Read.")));
        readOnlyState.setPendingActionObligation(PendingActionObligation.expectedTargets(List.of("README.md")));

        assertEquals(ToolChoiceMode.REQUIRED, controls.toolChoice());
        assertEquals(List.of("pending-action-obligation", "expected-target"), controls.debugTags());
        assertEquals(ChatRequestControls.defaults(), unsupported);
        assertEquals(ChatRequestControls.defaults(),
                ToolRepromptRequestBuilder.controls(readOnlyState, "expected-target", true));
    }

    @Test
    void capturedMutationRequiresNativeContinuationWithoutRaisingOrNarrowingRetryObligation() {
        LoopState state = capturedState("Change README.md from old to new.");
        state.addToolOutcome(new ToolCallLoop.ToolOutcome(
                "talos.read_file", "README.md", true, false, false, "read", ""));

        ChatRequestControls controls = ToolRepromptRequestBuilder.controls(state, "continuation", true);

        assertEquals(ToolChoiceMode.REQUIRED, controls.toolChoice());
        assertEquals(List.of("current-turn-mutation-obligation", "continuation"), controls.debugTags());
        assertEquals(ChatRequestControls.defaults().sampling(), controls.sampling());
        assertEquals(0, controls.maxOutputTokens());
        assertFalse(state.hasPendingActionObligation());
        assertEquals(toolNames(broadTools()),
                toolNames(ToolRepromptRequestBuilder.toolSpecs(state, false, false)));
        assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", false));

        state.addToolOutcome(new ToolCallLoop.ToolOutcome(
                "talos.edit_file", "README.md", true, true, false, "edited", ""));
        assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true));
    }

    @Test
    void uncapturedReadOnlyAndUnresolvedRequestsDoNotCreateContinuationAuthority() {
        for (String request : List.of("Read README.md. Do not change files.",
                "Implement those changes now.", "Review the BMI calculator you just created and fix any obvious issue "
                        + "that would stop it from working in a browser.")) {
            LoopState state = capturedState(request);
            assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true), request);
        }
        LoopState state = capturedState("Change README.md from old to new.");
        TurnTaskContractCapture.clear();
        assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true));
    }

    @Test
    void textModeUnboundAndNonmutatingSurfacesCannotAcquireRequiredNativeContinuation() {
        LoopState state = capturedState("Change README.md from old to new.");
        state.currentResponseReceipt = boundProviderReceipt(toolNames(broadTools()),
                ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON);
        assertTrue(state.currentResponseReceipt.providerToolCallEligible());
        assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true));
        state.currentResponseReceipt = ProviderResponseCapture.Receipt.none();
        assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true));
        state.currentResponseReceipt = boundProviderReceipt(List.of("talos.read_file"));
        assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true));
    }

    @Test
    void verificationPhaseDoesNotKeepRequiringMutation() {
        LoopState state = capturedState("Change README.md from old to new.");
        state.ctx.executionPhaseState().moveTo(dev.talos.runtime.phase.ExecutionPhase.VERIFY);
        assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true));
    }

    @Test
    void terminalStopAndPreexecutionDenialsNeverRequireAnotherToolRequest() {
        LoopState stopped = capturedState("Change README.md from old to new.");
        stopped.failureDecision = FailureDecision.stop(FailureAction.ASK_USER, "stop");
        assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(stopped, "", true));
        for (var reason : List.of(dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED,
                dev.talos.tools.ToolFailureReason.PERMISSION_POLICY_DENIED,
                dev.talos.tools.ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID)) {
            LoopState state = capturedState("Change README.md from old to new.");
            state.setPendingActionObligation(PendingActionObligation.expectedTargets(List.of("README.md")));
            state.addToolOutcome(new ToolCallLoop.ToolOutcome("talos.edit_file", "README.md",
                    false, true, false, "blocked", "blocked", null, "", null, null, reason));
            assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true), reason.name());
        }
    }

    @Test
    void nativeReceiptCannotRequireToolsWhenCurrentProviderReportsTextualAuthority() {
        LoopState captured = capturedState("Change README.md from old to new.");
        try (LlmClient llm = ScriptedNativeLlmClient.textToolFallbackStrings("Done.")) {
            Context context = Context.builder(new Config(null)).llm(llm)
                    .nativeToolSpecs(broadTools()).build();
            LoopState state = new LoopState("", List.of(), captured.currentResponseReceipt,
                    captured.messages, tempDir, context, null, 5, 0);
            assertTrue(state.currentResponseReceipt.providerToolCallEligible());
            assertEquals(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                    state.currentResponseReceipt.toolAuthorityContract().mode());
            assertEquals(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON,
                    llm.toolAuthorityModeForToolSurface(broadTools()));

            // Isolate the current-mode gate from REQUIRED capability support.
            assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true));
        }
    }

    @Test
    void unsuccessfulMutationWithAppliedOrUncertainEffectsCannotRequireContinuation() {
        for (var effects : List.of(dev.talos.tools.ToolMutationEffects.applied("README.md"),
                dev.talos.tools.ToolMutationEffects.uncertain("README.md"))) {
            LoopState state = capturedState("Change README.md from old to new.");
            assertEquals(ToolChoiceMode.REQUIRED, ToolRepromptRequestBuilder.controls(state, "", true).toolChoice());
            state.addToolOutcome(new ToolCallLoop.ToolOutcome("talos.edit_file", "README.md",
                    false, true, false, "failed after effects", "failed").withMutationEffects(effects));

            assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true));
            state.setPendingActionObligation(PendingActionObligation.expectedTargets(List.of("README.md")));
            assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true));
        }
    }

    @Test
    void currentMutationObligationRemainsRequiredUntilBothExpectedTargetsAreSatisfied() {
        LoopState state = capturedState("Change README.md from old title to new title and NOTES.md from old note to new note.");
        assertEquals(java.util.Set.of("README.md", "NOTES.md"),
                TurnTaskContractCapture.getResolution().contract().expectedTargets());
        state.addToolOutcome(new ToolCallLoop.ToolOutcome("talos.edit_file", "README.md",
                true, true, false, "edited", ""));

        assertEquals(ToolChoiceMode.REQUIRED, ToolRepromptRequestBuilder.controls(state, "", true).toolChoice());
        assertFalse(state.hasPendingActionObligation());
        assertEquals(List.of("NOTES.md"), ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state));

        state.addToolOutcome(new ToolCallLoop.ToolOutcome("talos.edit_file", "NOTES.md",
                true, true, false, "edited", ""));
        assertTrue(ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state).isEmpty());
        assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true));
    }

    @Test
    void targetlessAllowedMutationRequiresAFirstSuccessButNotAnotherMutation() {
        String request = "Fix the typo in the current document.";
        List<ChatMessage> messages = List.of(ChatMessage.user(request));
        // Explicit captured-contract fixture exercises the targetless policy branch,
        // independently of natural-language target inference.
        TurnTaskContractCapture.set(new dev.talos.runtime.task.TaskContract(
                dev.talos.runtime.task.TaskType.FILE_EDIT, true, true, false,
                java.util.Set.of(), java.util.Set.of(), request));
        LoopState state = loopState(broadTools(), messages, tempDir);
        assertTrue(TurnTaskContractCapture.getResolution().contract().mutationAllowed());
        assertTrue(TurnTaskContractCapture.getResolution().contract().expectedTargets().isEmpty());
        state.addToolOutcome(new ToolCallLoop.ToolOutcome("talos.read_file", "README.md",
                true, false, false, "read", ""));
        assertEquals(ToolChoiceMode.REQUIRED, ToolRepromptRequestBuilder.controls(state, "", true).toolChoice());

        state.addToolOutcome(new ToolCallLoop.ToolOutcome("talos.edit_file", "README.md",
                true, true, false, "edited", ""));
        assertEquals(ChatRequestControls.defaults(), ToolRepromptRequestBuilder.controls(state, "", true));
    }

    private LoopState capturedState(String request) {
        List<ChatMessage> messages = List.of(ChatMessage.user(request));
        TurnTaskContractCapture.set(TaskContractResolver.resolveFromMessages(messages), messages);
        return loopState(broadTools(), messages, tempDir);
    }

    @Test
    void executionStageDelegatesRepromptRequestAssemblyToBuilder() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));
        String selector = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptObligationSelector.java"));

        assertTrue(selector.contains("ToolRepromptRequestBuilder."), selector);
        assertFalse(source.contains("ToolRepromptRequestBuilder."), source);
        assertFalse(source.contains("private static List<ToolSpec> repromptToolSpecs"), source);
        assertFalse(source.contains("private static List<ChatMessage> repromptMessages"), source);
        assertFalse(source.contains("private static ChatRequestControls repromptControls"), source);
        assertFalse(source.contains("private static List<ToolSpec> currentNativeToolSpecs"), source);
        assertFalse(source.contains("private static List<ToolSpec> filterTools"), source);
    }

    private static LoopState loopState(List<ToolSpec> tools, List<ChatMessage> messages) {
        return loopState(tools, messages, Path.of("."));
    }

    private static LoopState loopState(List<ToolSpec> tools, List<ChatMessage> messages, Path workspace) {
        Context ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("No tool call."))
                .nativeToolSpecs(tools)
                .build();
        return new LoopState(
                "",
                List.of(),
                boundProviderReceipt(
                        tools.stream().map(ToolSpec::name).toList()),
                messages,
                workspace,
                ctx,
                null,
                5,
                0);
    }

    private static List<ToolSpec> broadTools() {
        return List.of(
                tool("talos.read_file"),
                tool("talos.list_dir"),
                tool("talos.write_file"),
                tool("talos.edit_file"),
                tool("talos.run_command"));
    }

    private static ToolSpec tool(String name) {
        return new ToolSpec(name, name, "{}");
    }

    private static List<String> toolNames(List<ToolSpec> tools) {
        return tools.stream().map(ToolSpec::name).toList();
    }

    private static ProviderResponseCapture.Receipt boundProviderReceipt(
            List<String> selectedToolNames
    ) {
        return boundProviderReceipt(selectedToolNames, ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE);
    }

    private static ProviderResponseCapture.Receipt boundProviderReceipt(
            List<String> selectedToolNames,
            ProviderResponseCapture.ToolAuthorityMode mode
    ) {
        ProviderResponseCapture.Scope scope =
                ProviderResponseCapture.begin(
                        new ProviderResponseCapture.Listener() {},
                        "tool-reprompt-request-builder");
        try {
            ProviderResponseCapture.Generation generation =
                    scope.openGeneration(
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Purpose.TURN_RESPONSE,
                        ProviderResponseCapture.ToolAuthorityContract.bound(
                                mode,
                                selectedToolNames,
                                ToolChoiceMode.AUTO,
                                ""));
            ProviderResponseCapture.Attempt attempt =
                    generation.startAttempt(
                            ProviderResponseCapture.AttemptKind.PRIMARY,
                            ProviderResponseCapture.RetryReason.NONE);
            ObservedProviderTransportFixture.completeTerminal(
                    attempt,
                    new ProviderTransportHandoff.DispatchEvidence(
                            "sha256:" + "a".repeat(64),
                            mode == ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE
                                    ? selectedToolNames : List.of()));
            ProviderResponseCapture.Receipt receipt = attempt.respond(
                    new ProviderResponseCapture.Response(
                            mode == ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE ? ""
                                    : "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}",
                            mode == ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE ? 1 : 0,
                            ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                            ProviderResponseCapture.Completion.PROVIDER_EOS,
                            false,
                            false,
                            mode == ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON));
            generation.complete(receipt);
            return receipt;
        } finally {
            ProviderResponseCapture.clear();
        }
    }

    private static ProviderResponseCapture.Receipt
            boundButProviderIneligibleReceipt(
                    List<String> selectedToolNames
            ) {
        ProviderResponseCapture.Scope scope =
                ProviderResponseCapture.begin(
                        new ProviderResponseCapture.Listener() {},
                        "tool-reprompt-static-only-receipt");
        try {
            ProviderResponseCapture.Generation generation =
                    scope.openGeneration(
                            ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                            ProviderResponseCapture.Purpose.TURN_RESPONSE,
                            ProviderResponseCapture.ToolAuthorityContract.bound(
                                    ProviderResponseCapture.ToolAuthorityMode
                                            .PROVIDER_NATIVE,
                                    selectedToolNames,
                                    ToolChoiceMode.AUTO,
                                    ""));
            ProviderResponseCapture.Attempt attempt =
                    generation.startAttempt(
                            ProviderResponseCapture.AttemptKind.PRIMARY,
                            ProviderResponseCapture.RetryReason.NONE);
            attempt.requestDispatched(
                    new ProviderTransportHandoff.DispatchEvidence(
                            "sha256:" + "d".repeat(64),
                            selectedToolNames));
            ProviderResponseCapture.Receipt receipt = attempt.respond(
                    new ProviderResponseCapture.Response(
                            "",
                            1,
                            ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                            ProviderResponseCapture.Completion.PROVIDER_EOS,
                            false,
                            false));
            generation.complete(receipt);
            return receipt;
        } finally {
            ProviderResponseCapture.clear();
        }
    }

    private static ProviderResponseCapture.Receipt unboundProviderReceipt() {
        ProviderResponseCapture.Generation generation =
                ProviderResponseCapture.openGeneration(
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Purpose.TURN_RESPONSE,
                        ProviderResponseCapture.ToolAuthorityContract.unbound(
                                ProviderResponseCapture.ToolAuthorityMode
                                        .PROVIDER_NATIVE));
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeTerminal(
                attempt,
                new ProviderTransportHandoff.DispatchEvidence(
                        "sha256:" + "b".repeat(64),
                        List.of()));
        ProviderResponseCapture.Receipt receipt = attempt.respond(
                new ProviderResponseCapture.Response(
                        "",
                        0,
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false,
                        false));
        generation.complete(receipt);
        return receipt;
    }
}
