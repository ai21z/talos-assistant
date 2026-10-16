package dev.talos.core.llm;

import dev.talos.core.util.Sanitize;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderReadInputBinding;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Actual ENGINE request assembly with an observed, scripted provider transport. No model process. */
class LlmClientTextToolInstructionsTest {
    private static final List<ToolSpec> TOOLS = List.of(new ToolSpec("talos.write_file", "Write",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}"));
    private static final ProviderResponseCapture.ToolAuthorityMode TEXT =
            ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON;
    private static final ProviderResponseCapture.ToolAuthorityMode NONE =
            ProviderResponseCapture.ToolAuthorityMode.NONE;

    @AfterEach void clearCaptures() {
        ProviderResponseCapture.clear();
        PromptDebugCapture.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customContinuationFramesReceiveContractOnActualRequestCopy(boolean streaming) {
        for (String frame : List.of("[CompactMutationContinuation]", "[StaticWebContinuation]", "[MissingMutationRetry]", "")) {
            var fixture = fixture(false, 16_384);
            try (LlmClient client = fixture.client()) {
                List<ChatMessage> input = frame.isEmpty() ? List.of(ChatMessage.user("Write README.md."))
                        : List.of(ChatMessage.system(frame + "\nUse only this bounded request."),
                                ChatMessage.user("Write README.md."));
                List<ChatMessage> before = List.copyOf(input);
                invoke(client, streaming, input, TOOLS, controls(TEXT, List.of()));
                assertEquals(1, fixture.requests().size());
                var sent = fixture.requests().getFirst();
                assertEquals(1, systemContractCount(sent.messages));
                assertEquals(before, input);
                for (int i = 0; i < input.size(); i++) assertSame(before.get(i), input.get(i));
                assertEquals(List.of("system", "user"), sent.messages.stream().map(ChatMessage::role).toList());
                assertEquals(input.getLast(), sent.messages.getLast());
                assertTrue(sent.tools.isEmpty(), "TEXT does not create a native surface");
                assertEquals(TEXT, sent.controls.toolAuthorityMode());
                assertEquals(ToolChoiceMode.AUTO, sent.controls.toolChoice());
                assertEquals(0, sent.controls.maxOutputTokens());
                assertFalse(sent.controls.singleAttempt());
                if (!frame.isEmpty()) assertTrue(sent.messages.getFirst().content().startsWith(frame));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completeSystemContractIsIdempotentButQuotedNonSystemContentIsNot(boolean streaming) {
        String contract = TextToolCallInstructions.formatContract();
        for (String role : List.of("system", "user", "assistant", "tool")) {
            var fixture = fixture(false, 16_384);
            try (LlmClient client = fixture.client()) {
                List<ChatMessage> input = List.of(ChatMessage.system("custom system"),
                        new ChatMessage(role, contract, null, role.equals("tool") ? "quoted" : null),
                        ChatMessage.user("Continue the current request."));
                invoke(client, streaming, input, TOOLS, controls(TEXT, List.of()));
                var sent = fixture.requests().getFirst();
                assertEquals(1, systemContractCount(sent.messages));
                assertEquals(input.get(1), sent.messages.get(1));
                if (role.equals("system")) assertEquals(input, sent.messages);
                else assertTrue(sent.messages.getFirst().content().contains(contract));
                invoke(client, streaming, sent.messages, TOOLS, controls(TEXT, List.of()));
                assertEquals(sent.messages, fixture.requests().get(1).messages);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeNoneAndBackgroundRequestsRemainByteEquivalent(boolean streaming) {
        for (String mode : List.of("native", "none", "background")) {
            var fixture = fixture(mode.equals("native"), 16_384);
            try (LlmClient client = fixture.client()) {
                List<ChatMessage> input = List.of(ChatMessage.system("custom compact frame"), ChatMessage.user("Continue."));
                var authority = mode.equals("native") ? ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE
                        : mode.equals("background") ? TEXT : NONE;
                List<String> tags = mode.equals("background") ? List.of(PromptDebugCapture.BACKGROUND_MAINTENANCE_TAG) : List.of();
                invoke(client, streaming, input, TOOLS, controls(authority, tags));
                var sent = fixture.requests().getFirst();
                assertEquals(input, sent.messages);
                assertEquals(0, systemContractCount(sent.messages));
                assertEquals(mode.equals("native") ? TOOLS : List.of(), sent.tools);
                assertEquals(authority, sent.controls.toolAuthorityMode());
                assertEquals(tags, sent.controls.debugTags());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyTextSurfaceStillFailsBeforeDispatch(boolean streaming) {
        var fixture = fixture(false, 16_384);
        try (LlmClient client = fixture.client()) {
            List<ChatMessage> input = List.of(ChatMessage.user("Continue."));
            assertThrows(IllegalArgumentException.class,
                    () -> invoke(client, streaming, input, List.of(), controls(TEXT, List.of())));
            assertTrue(fixture.requests().isEmpty());
            invoke(client, streaming, input, List.of(), controls(NONE, List.of()));
            assertEquals(input, fixture.requests().getFirst().messages);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void addedInstructionIsBudgetedAndCannotBypassPredispatchRejection(boolean streaming) {
        // Existing budget: 8192 context - 4096 response reserve - 100 overhead = 3996 input tokens.
        // The original single-user request fits. The required system instruction cannot fit with it.
        List<ChatMessage> input = List.of(ChatMessage.user("x".repeat(15_400)));
        var fixture = fixture(false, 8192);
        try (LlmClient client = fixture.client()) {
            invoke(client, streaming, input, TOOLS, controls(NONE, List.of()).withMaxOutputTokens(4096));
            assertEquals(1, fixture.requests().size());
            assertEquals(input, fixture.requests().getFirst().messages);
            assertThrows(EngineException.ContextBudgetExceeded.class, () ->
                    invoke(client, streaming, input, TOOLS, controls(TEXT, List.of()).withMaxOutputTokens(4096)));
            assertEquals(1, fixture.requests().size(), "TEXT must fail before a second dispatch");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualPreparedReadBindingRetainsSanitizedCarrierWithFinalTextInstruction(boolean streaming) {
        ProviderResponseCapture.begin(new ProviderResponseCapture.Listener() {});
        var fixture = fixture(false, 16_384);
        ContentRestrictions restricted = new ContentRestrictions(true, false, false, false);
        String source = "\u001b[31m1 | source\u001b[0m\r\n2 | retained\n";
        ChatMessage resultMessage = new ChatMessage("tool", source, null, "read-1", restricted);
        List<ChatMessage> input = List.of(ChatMessage.system("bounded custom system"),
                ChatMessage.user("Change README.md using this read."),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "read-1", "talos.read_file", Map.of("path", "README.md")))), resultMessage);
        try (LlmClient client = fixture.client()) {
            var result = invoke(client, streaming, input, TOOLS, controls(TEXT, List.of(ProviderReadInputBinding.DELIVERY_TAG)));
            var sent = fixture.requests().getFirst();
            assertEquals(1, systemContractCount(sent.messages));
            assertEquals(input.stream().map(ChatMessage::role).toList(), sent.messages.stream().map(ChatMessage::role).toList());
            assertEquals(input.get(2), sent.messages.get(2));
            assertEquals(resultMessage.toolCallId(), sent.messages.get(3).toolCallId());
            assertEquals(restricted, sent.messages.get(3).contentRestrictions());
            assertEquals(restricted, sent.contentRestrictions);
            assertSame(resultMessage, input.getLast());
            assertEquals(source, input.getLast().content());
            var binding = result.provenance().readInputBinding();
            assertTrue(binding.bound(), binding.reasonCode());
            assertEquals(ProviderReadInputBinding.Purpose.DELIVERY, binding.purpose());
            assertEquals(List.of(new ProviderReadInputBinding.ToolResultInput("read-1", Sanitize.sanitizeMessageContent(source))),
                    binding.preparedToolResults());
        }
    }

    @Test
    void helperPreservesCarrierIdentityAndRestrictionsAndPrefersUnrestrictedSystem() {
        ContentRestrictions restricted = new ContentRestrictions(true, false, false, false);
        ChatMessage first = new ChatMessage("system", "restricted system", null, null, restricted);
        ChatMessage carrier = ChatMessage.toolResult("read-1", "source");
        List<ChatMessage> input = List.of(first, ChatMessage.system("ordinary system"), carrier);
        List<ChatMessage> output = TextToolCallInstructions.forRequest(input, TEXT, TOOLS);
        assertSame(first, output.getFirst());
        assertSame(carrier, output.getLast());
        assertEquals(input.get(1).contentRestrictions(), output.get(1).contentRestrictions());
        assertEquals(input.get(1).toolCalls(), output.get(1).toolCalls());
        assertEquals(input.get(1).toolCallId(), output.get(1).toolCallId());
        assertTrue(output.get(1).content().contains(TextToolCallInstructions.formatContract()));
        List<ChatMessage> restrictedOnly = List.of(first, carrier);
        List<ChatMessage> altered = TextToolCallInstructions.forRequest(restrictedOnly, TEXT, TOOLS);
        assertEquals(restricted, altered.getFirst().contentRestrictions());
        assertSame(carrier, altered.getLast());
        ChatMessage denied = new ChatMessage("system", "not for model", null, null,
                new ContentRestrictions(false, false, false, false));
        var deniedOutput = TextToolCallInstructions.forRequest(List.of(denied, carrier), TEXT, TOOLS);
        assertSame(denied, deniedOutput.get(1));
        assertFalse(deniedOutput.get(1).contentRestrictions().modelAllowed());
        assertSame(input, TextToolCallInstructions.forRequest(input, NONE, TOOLS));
        assertSame(input, TextToolCallInstructions.forRequest(input, TEXT, List.of()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modelDisallowedSystemRemainsRejectedBeforeDispatch(boolean streaming) {
        var fixture = fixture(false, 16_384);
        List<ChatMessage> input = List.of(new ChatMessage("system", "withheld system", null, null,
                        new ContentRestrictions(false, false, false, false)), ChatMessage.user("Continue."));
        try (LlmClient client = fixture.client()) {
            EngineException.ResponseError denied = assertThrows(EngineException.ResponseError.class,
                    () -> invoke(client, streaming, input, TOOLS, controls(TEXT, List.of())));
            IllegalArgumentException cause = assertInstanceOf(IllegalArgumentException.class, denied.getCause());
            assertEquals("Document content is not admitted to model context.", cause.getMessage());
            assertTrue(fixture.requests().isEmpty());
            assertEquals(2, input.size());
            assertEquals("withheld system", input.getFirst().content());
            assertFalse(input.getFirst().contentRestrictions().modelAllowed());
        }
    }

    private static ScriptedNativeLlmClient.RecordedClient fixture(boolean nativeTools, int context) {
        List<LlmClient.StreamResult> responses = List.of(new LlmClient.StreamResult("ok", List.of()));
        return nativeTools ? ScriptedNativeLlmClient.recordingWithContextWindow(responses, context)
                : ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(responses, context);
    }

    private static ChatRequestControls controls(ProviderResponseCapture.ToolAuthorityMode authority, List<String> tags) {
        return new ChatRequestControls(ToolChoiceMode.AUTO, "", ResponseFormatMode.TEXT, "", tags)
                .withToolAuthorityMode(authority);
    }

    private static LlmClient.StreamResult invoke(LlmClient client, boolean streaming, List<ChatMessage> input,
                                                 List<ToolSpec> tools, ChatRequestControls controls) {
        return streaming ? client.chatStreamFull(input, ignored -> {}, 5_000, tools, controls)
                : client.chatFull(input, 5_000, tools, controls);
    }

    private static long systemContractCount(List<ChatMessage> messages) {
        return messages.stream().filter(message -> "system".equals(message.role()))
                .mapToLong(message -> message.content().split("Textual tool-call format:", -1).length - 1).sum();
    }
}
