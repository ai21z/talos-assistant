package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Real context selection and handoff are exercised with synthetic, recorded tool-result bodies. */
class ReadOnlySupportingEvidenceTest {
    @TempDir Path workspace;

    @AfterEach void clear() {
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test void supportingReadsBeforeAndAfterTheNamedTargetSurviveContextAndHandoff() throws Exception {
        for (TaskType type : List.of(TaskType.DIAGNOSE_ONLY, TaskType.READ_ONLY_QA)) {
            for (List<String> order : List.of(
                    List.of("guide.md", "settings.json", "retry.py"),
                    List.of("retry.py", "settings.json", "guide.md"))) {
                var state = state(type);
                for (String path : order) read(state, path, "1 | source body of " + path);
                var selected = ReadOnlyEvidenceContext.select(state);
                assertTrue(selected.complete(), "All required evidence was read for " + type);
                assertEquals(Set.copyOf(order), paths(selected), "Acquired support must not be limited to declared targets");
                assertRestorableAndReusable(state, selected);
            }
        }
    }

    @Test void supportingEvidenceDoesNotCompleteAnUnreadRequiredTarget() throws Exception {
        var state = state(TaskType.READ_ONLY_QA);
        read(state, "guide.md", "1 | caller documentation");
        var selected = ReadOnlyEvidenceContext.select(state);
        assertTrue(selected.available());
        assertFalse(selected.complete());
        assertEquals(List.of("retry.py"), selected.remainingTargets());
        assertEquals(Set.of("guide.md"), paths(selected));
        assertRestorableAndReusable(state, selected);
    }

    @Test void aLaterPartialSupportingReadCannotReuseItsOlderFullBody() throws Exception {
        for (boolean ranged : List.of(true, false)) {
            var state = state(TaskType.DIAGNOSE_ONLY);
            read(state, "retry.py", "1 | named target");
            read(state, "guide.md", "1 | complete supporting body");
            assertTrue(ReadOnlyEvidenceContext.select(state).complete());
            var params = ranged ? Map.of("path", "guide.md", "offset", "2") : Map.of("path", "guide.md");
            ReadOnlyEvidenceContextTest.read(state, "guide.md",
                    ranged ? "2 | partial supporting body" : "1 | partial supporting body\n... (output truncated)", params);
            if (ranged) {
                assertEquals("1 | complete supporting body",
                        state.readEvidence.latestCompleteReadFileForPath("guide.md", true).body(),
                        "The old full read still exists, so rejecting it must depend on latest-observation semantics");
            }
            assertUnavailableIncludingHandoff(state);
        }
    }

    @Test void aNinthAcquiredSourceRejectsTheWholeSelectionAndHandoff() throws Exception {
        var state = state(TaskType.READ_ONLY_QA);
        read(state, "retry.py", "1 | named target");
        for (int index = 1; index <= 7; index++) read(state, "support-" + index + ".txt", "1 | support " + index);
        var atLimit = ReadOnlyEvidenceContext.select(state);
        assertTrue(atLimit.complete());
        assertEquals(8, atLimit.carriers().size());
        assertRestorableAndReusable(state, atLimit);
        read(state, "support-8.txt", "1 | ninth source");
        assertUnavailableIncludingHandoff(state);
    }

    @Test void aggregateSupportingEvidenceCannotBeSilentlyClippedAtTheCharacterLimit() throws Exception {
        var state = state(TaskType.DIAGNOSE_ONLY);
        read(state, "retry.py", "1 | named target");
        String initialSupport = "1 | support";
        read(state, "guide.md", initialSupport);
        var initial = ReadOnlyEvidenceContext.select(state);
        assertEquals(2, initial.carriers().size());
        int initialCharacters = initial.carriers().stream().mapToInt(carrier -> carrier.message().content().length()).sum();
        String atLimit = "1 | " + "x".repeat(16_000 - initialCharacters + initialSupport.length() - 4);
        read(state, "guide.md", atLimit);
        var selected = ReadOnlyEvidenceContext.select(state);
        assertTrue(selected.complete());
        assertEquals(16_000, selected.carriers().stream().mapToInt(carrier -> carrier.message().content().length()).sum());
        assertRestorableAndReusable(state, selected);
        read(state, "guide.md", atLimit + "x");
        assertUnavailableIncludingHandoff(state);
    }

    @Test void requiredSameFileAliasesCannotDuplicateOneNativeCarrier() throws Exception {
        var state = state(TaskType.DIAGNOSE_ONLY);
        read(state, "retry.py", "1 | named target");
        read(state, "guide.md", "1 | supporting source");
        Files.createLink(workspace.resolve("retry-alias.py"), workspace.resolve("retry.py"));
        TurnTaskContractCapture.set(new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, false,
                Set.of("retry.py", "retry-alias.py"), Set.of(), "Diagnose both paths without editing."));
        assertUnavailableIncludingHandoff(state);
    }

    @Test void supportingSameFileAliasesNeverExportDuplicateNativeCarriers() throws Exception {
        var state = state(TaskType.DIAGNOSE_ONLY);
        read(state, "retry.py", "1 | named target");
        read(state, "guide.md", "1 | supporting source");
        Files.createLink(workspace.resolve("guide-alias.md"), workspace.resolve("guide.md"));
        read(state, "guide-alias.md", "1 | latest supporting source");
        var selected = ReadOnlyEvidenceContext.select(state);
        if (!selected.available()) {
            assertUnavailableIncludingHandoff(state);
            return;
        }
        assertTrue(selected.complete());
        assertEquals(2, selected.carriers().size(), "Same-file support must have only one latest carrier");
        assertEquals(1, selected.carriers().stream().filter(carrier ->
                carrier.evidence().body().equals("1 | latest supporting source")).count());
        assertRestorableAndReusable(state, selected);
    }

    @Test void distinctSupportingFilesWithIdenticalBytesRetainBothNativeBindings() throws Exception {
        var state = state(TaskType.READ_ONLY_QA);
        read(state, "retry.py", "1 | named target");
        read(state, "guide.md", "1 | identical supporting body");
        read(state, "other-guide.md", "1 | identical supporting body");
        assertFalse(Files.isSameFile(workspace.resolve("guide.md"), workspace.resolve("other-guide.md")));
        var selected = ReadOnlyEvidenceContext.select(state);
        assertTrue(selected.complete());
        assertEquals(Set.of("retry.py", "guide.md", "other-guide.md"), paths(selected));
        assertEquals(2, selected.carriers().stream().filter(carrier ->
                carrier.evidence().body().equals("1 | identical supporting body")).count());
        assertRestorableAndReusable(state, selected);
    }

    @Test void anUnboundSupportingReadCannotBeOmittedToDeclareCompleteness() throws Exception {
        var state = state(TaskType.DIAGNOSE_ONLY);
        read(state, "retry.py", "1 | named target");
        read(state, "guide.md", "1 | supporting source");
        int index = state.messages.size() - 1;
        String id = state.messages.get(index).toolCallId();
        state.messages.set(index, ChatMessage.toolResult(id, "unbound replacement"));
        assertUnavailableIncludingHandoff(state);
    }

    private LoopState state(TaskType type) {
        var state = ReadOnlyEvidenceContextTest.state(workspace);
        String request = "Inspect retry.py and explain its behavior using current files. Leave files unchanged.";
        TurnTaskContractCapture.set(new TaskContract(type, false, false, false,
                Set.of("retry.py"), Set.of(), request));
        state.messages.set(1, ChatMessage.user(request));
        LocalTurnTraceCapture.begin("supporting-evidence", "session", 1, "2026-09-27T00:00:00Z",
                "workspace", "code", "scripted", "fixture", request);
        return state;
    }

    private static void read(LoopState state, String path, String body) throws Exception {
        ReadOnlyEvidenceContextTest.read(state, path, body, Map.of("path", path));
    }

    private static Set<String> paths(ReadOnlyEvidenceContext.Selection selected) {
        return selected.carriers().stream().map(carrier -> carrier.evidence().effectivePath()).collect(Collectors.toSet());
    }

    private static void assertRestorableAndReusable(LoopState state, ReadOnlyEvidenceContext.Selection selected) {
        assertEquals(selected.carriers().size(), selected.carriers().stream().map(carrier -> carrier.call().id()).distinct().count());
        assertEquals(selected.carriers().size(), selected.carriers().stream().map(ReadOnlyEvidenceContext.Carrier::index).distinct().count());
        ToolCallSupport.compactOlderToolResultsInPlace(state.messages, state.compactedToolResultOriginals);
        var compacted = List.copyOf(state.messages);
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            assertOriginalBindings(state.messages, selected);
        }
        assertEquals(compacted, state.messages);
        var handoff = ReadOnlyEvidenceHandoff.capture(state);
        var retryMessages = new ArrayList<>(state.messages);
        retryMessages.add(ChatMessage.assistant("Incomplete source explanation"));
        retryMessages.add(ChatMessage.user("Continue the explanation using the observed sources."));
        var beforeRetry = List.copyOf(retryMessages);
        try (var ignored = handoff.restoreForRequest(retryMessages, state.workspace)) {
            assertOriginalBindings(retryMessages, selected);
        }
        assertEquals(beforeRetry, retryMessages);
        var retry = new LoopState("", List.of(), retryMessages, state.workspace, state.ctx, null, 10, 0);
        handoff.seed(retry);
        var reused = ReadOnlyEvidenceContext.select(retry);
        assertTrue(reused.available());
        assertEquals(selected.complete(), reused.complete());
        assertEquals(selected.remainingTargets(), reused.remainingTargets());
        assertEquals(paths(selected), paths(reused));
        assertEquals(selected.carriers().stream().map(ReadOnlyEvidenceContext.Carrier::message).toList(),
                reused.carriers().stream().map(ReadOnlyEvidenceContext.Carrier::message).toList());
        assertEquals(0, retry.totalToolsInvoked, "Evidence reuse must not invent executed tools");
        assertEquals(0, retry.iterations);
        assertTrue(retry.toolOutcomes.isEmpty());
    }

    private static void assertOriginalBindings(List<ChatMessage> messages, ReadOnlyEvidenceContext.Selection selected) {
        for (var carrier : selected.carriers()) {
            assertEquals(carrier.message(), messages.get(carrier.index()));
            assertEquals(carrier.call().id(), messages.get(carrier.index()).toolCallId());
            assertEquals(1, messages.stream().filter(message -> "assistant".equals(message.role())
                            && message.hasNativeToolCalls())
                    .flatMap(message -> message.toolCalls().stream()).filter(carrier.call()::equals).count());
            assertFalse(messages.stream().filter(message -> "system".equals(message.role()))
                    .anyMatch(message -> message.content().contains(carrier.evidence().body())));
        }
    }

    private static void assertUnavailableIncludingHandoff(LoopState state) {
        var selected = ReadOnlyEvidenceContext.select(state);
        assertFalse(selected.available());
        assertFalse(selected.complete());
        assertTrue(selected.carriers().isEmpty(), "Unsafe support cannot be silently dropped to retain a complete subset");
        assertTrue(selected.remainingTargets().isEmpty());
        var messages = new ArrayList<>(state.messages);
        var before = List.copyOf(messages);
        var handoff = ReadOnlyEvidenceHandoff.capture(state);
        try (var ignored = handoff.restoreForRequest(messages, state.workspace)) {
            assertEquals(before, messages);
        }
        var retry = new LoopState("", List.of(), messages, state.workspace, state.ctx, null, 10, 0);
        handoff.seed(retry);
        assertTrue(retry.readEvidence.isEmpty());
        assertTrue(retry.pathsReadThisTurn.isEmpty());
    }
}
