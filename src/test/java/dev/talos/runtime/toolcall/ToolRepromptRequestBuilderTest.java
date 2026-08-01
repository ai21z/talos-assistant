package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;
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
                                ProviderResponseCapture.ToolAuthorityMode
                                        .PROVIDER_NATIVE,
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
