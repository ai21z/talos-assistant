package dev.talos.runtime.policy;

import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.intent.ConversationEvidenceRequest;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeIdentityProviderTaskTest {
    @AfterEach void clear() { TurnTaskContractCapture.clear(); }

    @Test void spansSelectOriginalBytesAndRetainEveryOtherRequirement() {
        String request = "Hi, WHO ARE YOU?\nRead README.md and settings.json. Which model are you running? "
                + "Keep both parts separate. Don't change files or run anything.";
        var spans = ConversationEvidenceRequest.standaloneRuntimeIdentityClauses(request);
        assertEquals(2, spans.size());
        assertEquals("Hi, WHO ARE YOU?", request.substring(spans.getFirst().start(), spans.getFirst().end()));
        assertEquals("Which model are you running?", request.substring(spans.getLast().start(), spans.getLast().end()));
        assertEquals("\nRead README.md and settings.json.  Keep both parts separate. Don't change files or run anything.",
                RuntimeModelIdentityAnswerPolicy.providerTask(request));
    }

    @Test void identityPositionDoesNotEraseUnrelatedBytes() {
        for (String request : List.of("Who are you? Explain recursion.", "Explain recursion. Who are you?",
                "Who are you? Explain recursion. Which model are you running?")) {
            assertEquals("Explain recursion.", RuntimeModelIdentityAnswerPolicy.providerTask(request).strip());
        }
    }

    @Test void ambiguousQuotedAndSharedScopeCasesRemainUnchanged() {
        for (String request : List.of(
                "Without reading files, who are you? Explain recursion.",
                "Don't open the project files, identify yourself. Explain recursion.",
                "Who are you and read README.md?", "If you can, who are you? Read README.md.",
                "Do not identify yourself. Read README.md.", "Explain 'Who are you?' and read README.md.",
                "Translate \"Who are you?\". Read README.md.", "(Who are you?) Read README.md.",
                "Who are you? {Read README.md.", "Who\u000bare you? Read README.md.",
                "\rWho are you? Read README.md.", "Who are you? " + "x".repeat(4096),
                "Who are you? " + "Explain recursion. ".repeat(17), "Who are you?", "Who are you? Be honest.")) {
            assertEquals(request, RuntimeModelIdentityAnswerPolicy.providerTask(request), request);
        }
    }

    @Test void exactTaskBoundaryRetainsHistoryNativeDataAndRestrictions() {
        String request = "Who are you? Read README.md. Do not change files.";
        var restrictions = new ContentRestrictions(true, false, true, false);
        ChatMessage current = ChatMessage.user(request).withContentRestrictions(restrictions);
        var call = new ChatMessage.NativeToolCall("id", "talos.read_file", java.util.Map.of("path", "README.md"));
        var history = new ArrayList<>(List.of(ChatMessage.user(request), ChatMessage.assistant("An old answer."),
                current, ChatMessage.assistantWithToolCalls("", List.of(call)), ChatMessage.toolResult("id", request),
                ChatMessage.user("[Pending diagnosis reads]\nWho are you?")));
        var before = List.copyOf(history);
        var copy = new ArrayList<>(history);
        assertTrue(RuntimeModelIdentityAnswerPolicy.projectProviderTask(copy, 2, request));
        assertEquals(before, history);
        assertEquals("Read README.md. Do not change files.", copy.get(2).content().strip());
        assertEquals(restrictions, copy.get(2).contentRestrictions());
        for (int index : List.of(0, 1, 3, 4, 5)) assertSame(before.get(index), copy.get(index));
        assertFalse(RuntimeModelIdentityAnswerPolicy.projectProviderTask(copy, 2, request));
        assertFalse(RuntimeModelIdentityAnswerPolicy.projectProviderTask(copy, -1, request));
        assertFalse(RuntimeModelIdentityAnswerPolicy.projectProviderTask(copy, 4, request));
        assertFalse(RuntimeModelIdentityAnswerPolicy.projectProviderTask(copy, 5, request));
    }

    @Test void ineligibleCurrentUserCannotSelectOlderHistory() {
        String request = "Who are you? Read README.md. Do not change files.";
        var call = new ChatMessage.NativeToolCall("id", "talos.read_file", java.util.Map.of("path", "README.md"));
        for (ChatMessage current : List.of(
                new ChatMessage("user", request, List.of(call), null),
                new ChatMessage("user", request, null, "id"),
                ChatMessage.user(null))) {
            var messages = new ArrayList<>(List.of(ChatMessage.user(request), current));
            var resolution = TaskContractResolver.resolveFromMessages(messages);
            TurnTaskContractCapture.set(resolution, messages, ModelIdentitySnapshot.empty(), true);
            assertEquals(-1, TurnTaskContractCapture.providerTaskBoundaryIndex(messages));
            assertEquals("", TurnTaskContractCapture.providerTaskRequest());
            var copy = new ArrayList<>(messages);
            assertFalse(RuntimeModelIdentityAnswerPolicy.projectCapturedProviderTask(copy, 0, request));
            assertEquals(messages, copy);
        }
    }

    @Test void capturedPresentationIsRequiredAndAuthorityIsNotReclassified() {
        String request = "Who are you? Read README.md and settings.json. Do not change files or run commands.";
        var messages = new ArrayList<>(List.of(ChatMessage.system("system"), ChatMessage.user(request)));
        var resolution = TaskContractResolver.resolveFromMessages(messages);
        TurnTaskContractCapture.set(resolution, messages, ModelIdentitySnapshot.empty(), false);
        assertFalse(RuntimeModelIdentityAnswerPolicy.projectCapturedProviderTask(new ArrayList<>(messages), 1, request));
        TurnTaskContractCapture.set(resolution, messages, ModelIdentitySnapshot.empty(), true);
        var before = TurnTaskContractCapture.getResolution();
        var copy = new ArrayList<>(messages);
        assertTrue(RuntimeModelIdentityAnswerPolicy.projectCapturedProviderTask(copy, 1, request));
        assertSame(before, TurnTaskContractCapture.getResolution());
        assertEquals(request, TurnTaskContractCapture.originalUserRequestOrResolve(copy));
        assertFalse(TurnTaskContractCapture.get().mutationAllowed());
        assertEquals(request, messages.get(1).content());
        assertEquals(resolution.contract().expectedTargets(), TurnTaskContractCapture.get().expectedTargets());
        TurnTaskContractCapture.clear();
        assertEquals(request, RuntimeModelIdentityAnswerPolicy.capturedProviderTask(request));
    }
}
