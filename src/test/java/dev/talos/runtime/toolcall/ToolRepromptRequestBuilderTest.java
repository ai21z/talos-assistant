package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void narrowingPreservesOriginalToolsWhenNoRequestedToolsAreAvailable() {
        List<ToolSpec> readOnlyTools = List.of(tool("talos.read_file"), tool("talos.list_dir"));
        LoopState state = loopState(readOnlyTools, List.of(ChatMessage.user("Fix README.md.")));

        List<ToolSpec> tools = ToolRepromptRequestBuilder.toolSpecs(state, true, false);

        assertSame(readOnlyTools, tools);
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
    void staticRepairMessagesIncludeReadbackForRemainingRepairTarget() {
        LoopState state = loopState(
                broadTools(),
                List.of(ChatMessage.user("Adjust styles.css as needed.")));
        state.successfulReadCallBodies.put(
                "talos.read_file:path=styles.css;",
                "1 | body { color: #fff; }\n2 | .stage { padding: 3rem; }");

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
        assertTrue(payload.contains("Path: styles.css"), payload);
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
        assertTrue(payload.contains("Path: styles.css"), payload);
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
    void nonStaticRepairMessagesReuseCurrentStateMessages() {
        List<ChatMessage> messages = List.of(ChatMessage.system("sys"), ChatMessage.user("Continue."));
        LoopState state = loopState(broadTools(), messages);

        assertSame(messages, ToolRepromptRequestBuilder.messages(state, false, List.of(), "Continue."));
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
        return new LoopState("", List.of(), messages, workspace, ctx, null, 5, 0);
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
}
