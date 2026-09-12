package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyEvidenceHandoffTest {
    @TempDir Path workspace;

    @AfterEach void clear() {
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    private LoopState source() throws Exception {
        LoopState state = ReadOnlyEvidenceContextTest.state(workspace);
        beginTrace("handoff-current-turn", 1);
        for (String path : List.of("README.md", "index.html", "script.js"))
            ReadOnlyEvidenceContextTest.read(state, path, "1 | original contents of " + path,
                    Map.of("path", path));
        ToolCallSupport.compactOlderToolResultsInPlace(state.messages, state.compactedToolResultOriginals);
        return state;
    }

    private static void beginTrace(String id, int turn) {
        LocalTurnTraceCapture.begin(id, "session", turn, "2026-09-09T00:00:00Z", "workspace",
                "code", "scripted", "fixture", "Read four files and diagnose without editing.");
    }

    private static LoopState nested(LoopState source, List<ChatMessage> messages, Path root) {
        return new LoopState("", List.of(), messages, root, source.ctx, null, 10, 0);
    }

    private static List<ChatMessage> retryMessages(LoopState source) {
        List<ChatMessage> retry = new ArrayList<>(source.messages);
        retry.add(ChatMessage.assistant("Incomplete original diagnosis"));
        retry.add(ChatMessage.user("Read scripts.js before the final diagnosis."));
        return retry;
    }

    private static long visibleBodies(List<ChatMessage> messages) {
        return messages.stream().filter(message -> "tool".equals(message.role())
                && message.content().contains("original contents of ")).count();
    }

    private static void assertUnavailable(ReadOnlyEvidenceHandoff handoff, LoopState source) {
        List<ChatMessage> messages = retryMessages(source);
        List<ChatMessage> before = List.copyOf(messages);
        try (var ignored = handoff.restoreForRequest(messages, source.workspace)) {
            assertEquals(before, messages);
        }
        LoopState retry = nested(source, messages, source.workspace);
        handoff.seed(retry);
        assertTrue(retry.readEvidence.isEmpty());
        assertTrue(retry.pathsReadThisTurn.isEmpty());
    }

    @Test void partialEvidenceRestoresBeforeFirstRetryThenSeedsTheNestedLoop() throws Exception {
        var source = source();
        var handoff = ReadOnlyEvidenceHandoff.capture(source);
        List<ChatMessage> original = List.copyOf(source.messages);
        List<ChatMessage> retryMessages = retryMessages(source);
        List<ChatMessage> compacted = List.copyOf(retryMessages);
        assertEquals(2, visibleBodies(retryMessages));
        try (var ignored = handoff.restoreForRequest(retryMessages, workspace)) {
            assertEquals(3, visibleBodies(retryMessages));
            for (int i = 0; i < original.size(); i++) {
                assertEquals(original.get(i).role(), retryMessages.get(i).role());
                assertEquals(original.get(i).toolCallId(), retryMessages.get(i).toolCallId());
            }
            assertFalse(retryMessages.stream().filter(message -> "system".equals(message.role()))
                    .anyMatch(message -> message.content().contains("original contents of ")));
        }
        assertEquals(compacted, retryMessages);
        assertEquals(original, source.messages);
        LoopState retry = nested(source, retryMessages, workspace);
        handoff.seed(retry);
        assertEquals(3, retry.pathsReadThisTurn.size());
        assertEquals(0, retry.totalToolsInvoked);
        assertEquals(0, retry.iterations);
        assertTrue(retry.toolOutcomes.isEmpty());
        assertFalse(ReadOnlyEvidenceContext.select(retry).complete());
        ReadOnlyEvidenceContextTest.read(retry, "scripts.js", "1 | original contents of scripts.js",
                Map.of("path", "scripts.js"));
        assertTrue(ReadOnlyEvidenceContext.select(retry).complete());
        try (var ignored = ToolRepromptMessageOverlay.apply(retry, List.of(), List.of(), "")) {
            assertEquals(4, visibleBodies(retry.messages));
        }
        assertEquals(4, retry.readEvidence.latestReadFileBodiesByNormalizedPath().size());
    }

    @Test void differentTraceOrTurnCannotReuseAnIdenticalTask() throws Exception {
        var source = source();
        var handoff = ReadOnlyEvidenceHandoff.capture(source);
        beginTrace("another-turn", 1);
        assertUnavailable(handoff, source);
        beginTrace("handoff-current-turn", 2);
        assertUnavailable(handoff, source);
    }

    @Test void absentTraceOrTaskCaptureCannotExportOrRestore() throws Exception {
        var source = source();
        var handoff = ReadOnlyEvidenceHandoff.capture(source);
        LocalTurnTraceCapture.clear();
        assertUnavailable(handoff, source);
        assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
        beginTrace("handoff-current-turn", 1);
        TurnTaskContractCapture.clear();
        assertUnavailable(handoff, source);
        assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
    }

    @Test void differentWorkspaceOrTaskRefusesTheEntireHandoff() throws Exception {
        var source = source();
        var handoff = ReadOnlyEvidenceHandoff.capture(source);
        var messages = retryMessages(source);
        var before = List.copyOf(messages);
        try (var ignored = handoff.restoreForRequest(messages, workspace.resolve("different"))) {
            assertEquals(before, messages);
        }
        var retry = nested(source, messages, workspace.resolve("different"));
        handoff.seed(retry);
        assertTrue(retry.readEvidence.isEmpty());
        var contract = TurnTaskContractCapture.get();
        TurnTaskContractCapture.set(new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, false,
                contract.expectedTargets(), Set.of(), "A different diagnosis request"));
        assertUnavailable(handoff, source);
    }

    @Test void changedBodyRoleIdOrResultPositionFailsClosed() throws Exception {
        var source = source();
        var handoff = ReadOnlyEvidenceHandoff.capture(source);
        int index = 3;
        ChatMessage original = source.messages.get(index);
        assertEquals("tool", original.role());
        for (var replacement : List.of(ChatMessage.toolResult(original.toolCallId(), "replacement"),
                ChatMessage.toolResult("different-id", original.content()),
                ChatMessage.system(original.content()))) {
            source.messages.set(index, replacement);
            assertUnavailable(handoff, source);
        }
        source.messages.set(index, original);
        source.messages.add(0, ChatMessage.system("shifted history"));
        assertUnavailable(handoff, source);
    }

    @Test void duplicateCallOrResultIdentityFailsClosedEvenAfterTheOriginalResult() throws Exception {
        var source = source();
        var handoff = ReadOnlyEvidenceHandoff.capture(source);
        source.messages.add(source.messages.get(2));
        assertUnavailable(handoff, source);
        source.messages.removeLast();
        source.messages.add(source.messages.get(3));
        assertUnavailable(handoff, source);
        assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
    }

    @Test void mutableNativeArgumentsCannotChangeTheCapturedIdentity() throws Exception {
        var source = source();
        ChatMessage assistant = source.messages.get(2);
        var call = assistant.toolCalls().getFirst();
        var arguments = new LinkedHashMap<>(call.arguments());
        source.messages.set(2, ChatMessage.assistantWithToolCalls(assistant.content(), List.of(
                new ChatMessage.NativeToolCall(call.id(), call.name(), arguments))));
        var handoff = ReadOnlyEvidenceHandoff.capture(source);
        assertNotSame(ReadOnlyEvidenceHandoff.empty(), handoff);
        arguments.put("path", "different.md");
        assertUnavailable(handoff, source);
    }

    @Test void proposalChangedBeforeCaptureDoesNotRebindTheExecutedLedgerCall() throws Exception {
        var source = source();
        ChatMessage assistant = source.messages.get(2);
        var call = assistant.toolCalls().getFirst();
        for (var changed : List.of(
                new ChatMessage.NativeToolCall(call.id(), "talos.write_file", call.arguments()),
                new ChatMessage.NativeToolCall(call.id(), call.name(), Map.of("path", "different.md")))) {
            source.messages.set(2, ChatMessage.assistantWithToolCalls(assistant.content(), List.of(changed)));
            assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
        }
    }

    @Test void nestedJsonArgumentsAreCopiedWithoutLosingActualCallIdentity() throws Exception {
        var source = source();
        ReadOnlyEvidenceContextTest.read(source, "README.md", "1 | original contents of README.md",
                Map.of("path", "README.md", "metadata", "{\"observed\":\"original\"}"));
        int assistantIndex = source.messages.size() - 2;
        ChatMessage assistant = source.messages.get(assistantIndex);
        var call = assistant.toolCalls().getFirst();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("observed", "original");
        var arguments = new LinkedHashMap<>(call.arguments());
        arguments.put("metadata", metadata);
        source.messages.set(assistantIndex, ChatMessage.assistantWithToolCalls(assistant.content(), List.of(
                new ChatMessage.NativeToolCall(call.id(), call.name(), arguments))));
        var handoff = ReadOnlyEvidenceHandoff.capture(source);
        assertNotSame(ReadOnlyEvidenceHandoff.empty(), handoff);
        metadata.put("observed", "changed");
        assertUnavailable(handoff, source);
    }

    @Test void nonJsonIdentityObjectsAreRejectedWithoutCallingTheirStringConversion() throws Exception {
        var source = source();
        var called = new AtomicBoolean();
        ChatMessage assistant = source.messages.get(2);
        var call = assistant.toolCalls().getFirst();
        var arguments = new LinkedHashMap<>(call.arguments());
        arguments.put("foreign", new Object() {
            @Override public String toString() { called.set(true); throw new AssertionError("not inert"); }
        });
        source.messages.set(2, ChatMessage.assistantWithToolCalls(assistant.content(), List.of(
                new ChatMessage.NativeToolCall(call.id(), call.name(), arguments))));
        assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
        assertFalse(called.get());
    }

    @Test void cyclicAndOversizedNativeIdentitiesFailClosed() throws Exception {
        var source = source();
        ChatMessage assistant = source.messages.get(2);
        var call = assistant.toolCalls().getFirst();
        var cyclic = new LinkedHashMap<String, Object>();
        cyclic.put("itself", cyclic);
        for (Object value : List.of(cyclic, "x".repeat(ReadOnlyEvidenceContext.MAX_EVIDENCE_CHARS + 1))) {
            var arguments = new LinkedHashMap<>(call.arguments());
            arguments.put("metadata", value);
            source.messages.set(2, ChatMessage.assistantWithToolCalls(assistant.content(), List.of(
                    new ChatMessage.NativeToolCall(call.id(), call.name(), arguments))));
            assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
        }
    }

    @Test void invalidatedSourceAndNestedEvidenceCannotBeResurrected() throws Exception {
        var source = source();
        var handoff = ReadOnlyEvidenceHandoff.capture(source);
        var retry = nested(source, retryMessages(source), workspace);
        handoff.seed(retry);
        assertFalse(retry.readEvidence.isEmpty());
        ReadEvidenceStateAccounting.clearSuccessfulReadCaches(retry);
        assertTrue(ReadOnlyEvidenceContext.select(retry).carriers().isEmpty());
        handoff.seed(retry);
        assertTrue(ReadOnlyEvidenceContext.select(retry).carriers().isEmpty());
        ReadEvidenceStateAccounting.clearSuccessfulReadCaches(source);
        assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
    }

    @Test void aFreshReadSupersedesTheImportedEntryAndPreservesSequenceInvalidation() throws Exception {
        var source = source();
        var retry = nested(source, retryMessages(source), workspace);
        ReadOnlyEvidenceHandoff.capture(source).seed(retry);
        long previous = retry.readEvidence.latestCompleteReadFileForPath("README.md", true).sequence();
        ReadOnlyEvidenceContextTest.read(retry, "README.md", "1 | new current contents",
                Map.of("path", "README.md"));
        var current = retry.readEvidence.latestCompleteReadFileForPath("README.md", true);
        assertTrue(current.sequence() > previous);
        assertEquals("1 | new current contents", current.body());
        try (var ignored = ToolRepromptMessageOverlay.apply(retry, List.of(), List.of(), "")) {
            assertFalse(retry.messages.get(3).content().contains("original contents of README.md"));
            assertEquals(1, retry.messages.stream().filter(message -> "tool".equals(message.role())
                    && message.content().contains("1 | new current contents")).count());
        }
    }

    @Test void failurePrivacyMutationAndPriorSynthesisStatesCannotExport() throws Exception {
        var source = source();
        source.failedCalls = 1;
        assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
        source.failedCalls = 0;
        source.contentWithheldFromModelContext = true;
        assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
        source.contentWithheldFromModelContext = false;
        source.mutationSinceStart = true;
        assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
        source.mutationSinceStart = false;
        source.failureDecision = FailureDecision.stop(FailureAction.ASK_USER, "terminal");
        assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
        source.failureDecision = FailureDecision.continueLoop();
        for (var status : List.of(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED,
                ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED)) {
            source.readOnlySynthesisStatus = status;
            assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
        }
    }

    @Test void seedDoesNotEnterAnAlreadyUsedOrIneligibleNestedLoop() throws Exception {
        var source = source();
        var handoff = ReadOnlyEvidenceHandoff.capture(source);
        var retry = nested(source, retryMessages(source), workspace);
        retry.iterations = 1;
        handoff.seed(retry);
        assertTrue(retry.readEvidence.isEmpty());
        retry.iterations = 0;
        retry.contentWithheldFromModelContext = true;
        handoff.seed(retry);
        assertTrue(retry.readEvidence.isEmpty());
        retry.contentWithheldFromModelContext = false;
        retry.readOnlySynthesisStatus = ToolCallLoop.ReadOnlySynthesisStatus.REJECTED;
        handoff.seed(retry);
        assertTrue(retry.readEvidence.isEmpty());
    }

    @Test void protectedAliasesTruncatedRangedAndOversizedBodiesRemainIneligible() throws Exception {
        var source = source();
        var originalContract = TurnTaskContractCapture.get();
        TurnTaskContractCapture.set(new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, false,
                Set.of(".env"), Set.of(), "Read .env and diagnose without editing"));
        ReadOnlyEvidenceContextTest.read(source, ".env", "dummy", Map.of("path", ".env"));
        assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
        TurnTaskContractCapture.set(originalContract);
        for (String body : List.of("... (output truncated)",
                dev.talos.runtime.policy.ProtectedContentPolicy.REDACTED_VALUE,
                "x".repeat(ReadOnlyEvidenceContext.MAX_EVIDENCE_CHARS))) {
            ReadOnlyEvidenceContextTest.read(source, "README.md", body, Map.of("path", "README.md"));
            assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
        }
        ReadOnlyEvidenceContextTest.read(source, "README.md", "complete", Map.of("path", "README.md"));
        ReadOnlyEvidenceContextTest.read(source, "README.md", "different range",
                Map.of("path", "README.md", "offset", "2"));
        assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
    }

    @Test void overlayCloseIsIdempotentAndDoesNotOverwriteChangedMessages() throws Exception {
        var source = source();
        var retry = retryMessages(source);
        var overlay = ReadOnlyEvidenceHandoff.capture(source).restoreForRequest(retry, workspace);
        assertEquals(3, visibleBodies(retry));
        ChatMessage changed = ChatMessage.toolResult(retry.get(3).toolCallId(), "new external result");
        retry.set(3, changed);
        overlay.close();
        overlay.close();
        assertEquals(changed, retry.get(3));
    }

    @Test void sameFileAliasesCannotExportTheSameNativeCarrierTwice() throws Exception {
        var source = source();
        java.nio.file.Files.createLink(workspace.resolve("README-alias.md"), workspace.resolve("README.md"));
        TurnTaskContractCapture.set(new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, false,
                Set.of("README.md", "README-alias.md"), Set.of(), "Diagnose both aliases without editing."));
        assertUnavailable(ReadOnlyEvidenceHandoff.capture(source), source);
    }

    @Test void emptyAndLegacyLoopsHaveNoAdditionalBehavior() throws Exception {
        var source = source();
        assertUnavailable(ReadOnlyEvidenceHandoff.empty(), source);
        assertSame(ReadOnlyEvidenceHandoff.empty(), ReadOnlyEvidenceHandoff.capture(null));
    }
}
