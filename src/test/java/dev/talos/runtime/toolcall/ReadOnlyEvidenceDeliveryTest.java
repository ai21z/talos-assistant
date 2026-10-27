package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic executed-read fixtures exercise the real ledger, compactor and request overlay. */
class ReadOnlyEvidenceDeliveryTest {
    private static final List<String> PATHS = List.of("caller.txt", "implementation.txt", "options.txt");
    @TempDir Path workspace;

    @AfterEach void clear() { TurnTaskContractCapture.clear(); }

    private LoopState state() {
        var state = ReadOnlyEvidenceContextTest.state(workspace);
        TurnTaskContractCapture.set(new TaskContract(TaskType.WORKSPACE_EXPLAIN, false, false, false,
                Set.of(), Set.of(), "Explain the current caller, implementation and options without editing."));
        return state;
    }

    private static String body(String path) { return "1 | current observation of " + path + "\n"; }

    private static void read(LoopState state, String path, String body) throws Exception {
        ReadOnlyEvidenceContextTest.read(state, path, body, Map.of("path", path));
    }

    private static void compact(LoopState state) {
        ToolCallSupport.compactOlderToolResultsInPlace(state.messages, state.compactedToolResultOriginals);
    }

    private static List<ChatMessage> toolMessages(LoopState state) {
        return state.messages.stream().filter(message -> "tool".equals(message.role())).toList();
    }

    private static void failedRead(LoopState state, String path, String id) {
        failedRead(state, path, id, false);
    }

    private static void failedRead(LoopState state, String path, String id, boolean denied) {
        Map<String, Object> arguments = path.isBlank() ? Map.of() : Map.of("path", path);
        state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                id, "talos.read_file", arguments))));
        state.messages.add(ChatMessage.toolResult(id, "[tool_error: READ_FAILED] fixture read failed"));
        state.failedCalls++;
        var outcome = new ToolCallLoop.ToolOutcome("talos.read_file", path, false, false, denied,
                "", "fixture read failed", null, "READ_FAILED", null, null, null, List.of(), "trace-" + id);
        state.toolOutcomes.add(outcome);
        state.toolOutcomeResults.bind(outcome, state.messages.size() - 1, state.messages.getLast());
    }

    private static void assertVisible(LoopState state, String body) {
        assertEquals(1, toolMessages(state).stream().filter(message -> message.content().contains(body)).count(),
                "The current observed body must survive compaction in its original tool carrier");
        assertFalse(state.messages.stream().filter(message -> !"tool".equals(message.role()))
                .anyMatch(message -> message.content().contains(body)), "Source content must not gain instruction authority");
    }

    private static void assertNotRestored(LoopState state, String body) {
        compact(state);
        var before = List.copyOf(state.messages);
        assertFalse(toolMessages(state).stream().anyMatch(message -> message.content().contains(body)),
                "The tested unsafe observation must actually have been compacted before the overlay");
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            assertFalse(toolMessages(state).stream().anyMatch(message -> message.content().contains(body)),
                    "An unsafe or superseded body must not be restored from older evidence");
        }
        assertEquals(before, state.messages);
    }

    @Test void serialReadsWithNoExplicitTargetsRetainEveryCurrentBodyForTheRequest() throws Exception {
        var state = state();
        for (String path : PATHS) {
            read(state, path, body(path));
            compact(state);
        }
        var before = List.copyOf(state.messages);
        assertTrue(toolMessages(state).getFirst().content().startsWith("[compacted:"));
        var delivery = ReadOnlyEvidenceContext.delivery(state);
        assertTrue(delivery.available());
        assertFalse(delivery.retentionLimited());
        assertEquals(3, delivery.carriers().size());
        assertFalse(ReadOnlyEvidenceContext.select(state).available(), "Delivery must not widen synthesis admission");
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            for (String path : PATHS) assertVisible(state, body(path));
            for (int index = 0; index < before.size(); index++) {
                assertEquals(before.get(index).role(), state.messages.get(index).role());
                assertEquals(before.get(index).toolCallId(), state.messages.get(index).toolCallId());
            }
        }
        assertEquals(before, state.messages, "Restoration belongs only to the current request");
    }

    @Test void batchedReadsUseTheSameDeliveryContractAfterCompaction() throws Exception {
        var state = state();
        List<ChatMessage.NativeToolCall> calls = new ArrayList<>();
        for (String path : PATHS) calls.add(new ChatMessage.NativeToolCall("batch-" + path,
                "talos.read_file", Map.of("path", path)));
        state.messages.add(ChatMessage.assistantWithToolCalls("", calls));
        for (var nativeCall : calls) {
            String path = (String) nativeCall.arguments().get("path");
            Files.writeString(workspace.resolve(path), body(path));
            var call = new ToolCall(nativeCall.name(), Map.of("path", path));
            ReadEvidenceStateAccounting.recordSuccessfulToolResult(state, call, path, ToolResult.ok(body(path)));
            state.messages.add(ChatMessage.toolResult(nativeCall.id(), ToolCallSupport.formatToolResult(call,
                    ToolResult.ok(body(path)))));
            int index = state.messages.size() - 1;
            state.readEvidence.bindToolResult(call, index, state.messages.get(index));
        }
        compact(state);
        var before = List.copyOf(state.messages);
        assertTrue(toolMessages(state).getFirst().content().startsWith("[compacted:"));
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            for (String path : PATHS) assertVisible(state, body(path));
            assertEquals(calls, state.messages.get(2).toolCalls(), "Batched native proposal identity must be preserved");
        }
        assertEquals(before, state.messages);
    }

    @Test void unrelatedFailedReadDoesNotDiscardSuccessfulCurrentObservations() throws Exception {
        var state = state();
        for (String path : PATHS) read(state, path, body(path));
        var failed = new ToolCall("talos.read_file", Map.of());
        state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                "missing-path", failed.toolName(), new LinkedHashMap<>()))));
        state.messages.add(ChatMessage.toolResult("missing-path", "[tool_error: INVALID_PARAMS] path is required"));
        state.failedCalls = 1;
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(failed.toolName(), "", false, false, false,
                "", "path is required", null, "INVALID_PARAMS", null, null, null, List.of(), "missing-path"));
        compact(state);
        var before = List.copyOf(state.messages);
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            for (String path : PATHS) assertVisible(state, body(path));
            assertFalse(ReadOnlyEvidenceContext.select(state).complete());
            assertEquals(1, state.failedCalls);
            assertEquals("", state.toolOutcomes.getFirst().pathHint(), "A missing path must not be guessed");
        }
        assertEquals(before, state.messages);
    }

    @Test void latestFullObservationSupersedesOlderBodyForTheSamePhysicalSource() throws Exception {
        var state = state();
        read(state, "caller.txt", "1 | obsolete caller\n");
        Files.createLink(workspace.resolve("caller-alias.txt"), workspace.resolve("caller.txt"));
        read(state, "caller-alias.txt", "1 | current replacement caller\n");
        read(state, "implementation.txt", body("implementation.txt"));
        read(state, "options.txt", body("options.txt"));
        compact(state);
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            assertVisible(state, "1 | current replacement caller\n");
            assertFalse(toolMessages(state).stream().anyMatch(message -> message.content().contains("obsolete caller")));
        }
    }

    @Test void laterPartialReadCannotResurrectAnOlderCompleteObservation() throws Exception {
        var state = state();
        String old = "1 | obsolete full source\n";
        read(state, "caller.txt", old);
        ReadOnlyEvidenceContextTest.read(state, "caller.txt", "2 | new partial source\n",
                Map.of("path", "caller.txt", "offset", "2"));
        read(state, "implementation.txt", body("implementation.txt"));
        read(state, "options.txt", body("options.txt"));
        assertNotRestored(state, old);
        assertNotRestored(state, "2 | new partial source\n");
        assertTrue(ReadOnlyEvidenceContext.delivery(state).available());
        assertEquals(1, ReadOnlyEvidenceContext.delivery(state).excludedSourceCount());
    }

    @Test void laterFailedRefreshThroughSameFileAliasCannotResurrectOlderSuccess() throws Exception {
        var state = state();
        read(state, "caller.txt", body("caller.txt"));
        Files.createLink(workspace.resolve("caller-alias.txt"), workspace.resolve("caller.txt"));
        failedRead(state, "caller-alias.txt", "failed-refresh");
        read(state, "implementation.txt", body("implementation.txt"));
        read(state, "options.txt", body("options.txt"));
        assertNotRestored(state, body("caller.txt"));
        assertTrue(ReadOnlyEvidenceContext.delivery(state).available());
        assertEquals(1, ReadOnlyEvidenceContext.delivery(state).excludedSourceCount());
    }

    @Test void successfulRefreshAfterAnIdentifiedFailureCanBeDelivered() throws Exception {
        var state = state();
        read(state, "caller.txt", "1 | obsolete source\n");
        failedRead(state, "caller.txt", "earlier-failure");
        read(state, "caller.txt", body("caller.txt"));
        read(state, "implementation.txt", body("implementation.txt"));
        read(state, "options.txt", body("options.txt"));
        compact(state);
        assertTrue(ReadOnlyEvidenceContext.delivery(state).available());
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            assertVisible(state, body("caller.txt"));
            assertFalse(toolMessages(state).stream().anyMatch(message -> message.content().contains("obsolete source")));
            assertFalse(ReadOnlyEvidenceContext.select(state).complete(), "Delivery is not synthesis eligibility");
        }
    }

    @Test void failedRefreshAfterFileReplacementExcludesOnlyThatEarlierObservation() throws Exception {
        var state = state();
        read(state, "safe.txt", body("safe.txt"));
        read(state, "caller.txt", "1 | old file observation\n");
        Files.writeString(workspace.resolve("replacement.txt"), "replacement file contents");
        Files.move(workspace.resolve("replacement.txt"), workspace.resolve("caller.txt"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        failedRead(state, "caller.txt", "replacement-refresh-failed");
        read(state, "implementation.txt", body("implementation.txt"));
        read(state, "options.txt", body("options.txt"));
        compact(state);
        var delivery = ReadOnlyEvidenceContext.delivery(state);
        assertTrue(delivery.available());
        assertEquals(1, delivery.excludedSourceCount());
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            assertVisible(state, body("safe.txt"));
            assertFalse(toolMessages(state).stream()
                    .anyMatch(message -> message.content().contains("old file observation")));
            assertFalse(toolMessages(state).stream()
                    .anyMatch(message -> message.content().contains("replacement file contents")),
                    "Delivery must not reread replacement bytes from disk");
        }
    }

    @Test void restorationIntersectsRestrictionsAcquiredAfterTheOriginalRead() throws Exception {
        var state = state();
        for (String path : PATHS) read(state, path, body(path));
        compact(state);
        int index = 3;
        state.messages.set(index, state.messages.get(index).withContentRestrictions(
                new ContentRestrictions(true, false, false, false)));
        var before = List.copyOf(state.messages);
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            assertVisible(state, body(PATHS.getFirst()));
            assertEquals(new ContentRestrictions(true, false, false, false),
                    state.messages.get(index).contentRestrictions());
        }
        assertEquals(before, state.messages);
    }

    @Test void aLaterModelContextRestrictionPreventsRestoration() throws Exception {
        var state = state();
        for (String path : PATHS) read(state, path, body(path));
        compact(state);
        state.messages.set(3, state.messages.get(3).withContentRestrictions(
                new ContentRestrictions(false, true, true, true)));
        assertNotRestored(state, body(PATHS.getFirst()));
        assertTrue(ReadOnlyEvidenceContext.delivery(state).available());
        assertEquals(1, ReadOnlyEvidenceContext.delivery(state).excludedSourceCount());
    }

    @Test void unusableSourceDoesNotSuppressAnOlderCompactedValidSource() throws Exception {
        for (String kind : List.of("partial", "redacted", "truncated", "protected", "model-blocked",
                "failed-refresh", "denied-refresh", "changed-call")) {
            var state = state();
            String safe = "1 | oldest safe evidence\n";
            read(state, "safe.txt", safe);
            String path = kind.equals("protected") ? ".env" : "mixed.txt";
            String unsafe = switch (kind) {
                case "redacted" -> ProtectedContentPolicy.REDACTED_VALUE;
                case "truncated" -> "1 | truncated evidence\n... (output truncated)";
                default -> "1 | unusable evidence\n";
            };
            read(state, path, unsafe);
            int unsafeResultIndex = state.messages.size() - 1;
            if (kind.equals("partial")) {
                ReadOnlyEvidenceContextTest.read(state, path, "2 | partial replacement\n",
                        Map.of("path", path, "offset", "2"));
            } else if (kind.equals("failed-refresh") || kind.equals("denied-refresh")) {
                failedRead(state, path, "mixed-refresh", kind.equals("denied-refresh"));
            } else if (kind.equals("model-blocked")) {
                state.messages.set(unsafeResultIndex, state.messages.get(unsafeResultIndex)
                        .withContentRestrictions(new ContentRestrictions(false, true, true, true)));
            } else if (kind.equals("changed-call")) {
                var call = state.messages.get(unsafeResultIndex - 1).toolCalls().getFirst();
                state.messages.set(unsafeResultIndex - 1, ChatMessage.assistantWithToolCalls("", List.of(
                        new ChatMessage.NativeToolCall(call.id(), call.name(), Map.of("path", "other.txt")))));
            }
            read(state, "implementation.txt", body("implementation.txt"));
            read(state, "options.txt", body("options.txt"));
            compact(state);
            var before = List.copyOf(state.messages);
            assertTrue(toolMessages(state).getFirst().content().startsWith("[compacted:"), kind);
            var delivery = ReadOnlyEvidenceContext.delivery(state);
            assertTrue(delivery.available(), kind);
            assertFalse(delivery.retentionLimited(), kind);
            assertEquals(1, delivery.excludedSourceCount(), kind);
            assertEquals(3, delivery.carriers().size(), kind);
            try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
                assertVisible(state, safe);
                assertVisible(state, body("implementation.txt"));
                assertVisible(state, body("options.txt"));
                assertFalse(toolMessages(state).stream().anyMatch(message -> message.content().contains(unsafe)), kind);
                assertFalse(toolMessages(state).stream().anyMatch(message -> message.content().contains("partial replacement")), kind);
            }
            assertEquals(before, state.messages, kind);
        }
    }

    @Test void redactedTruncatedAndProtectedResultsCannotBeRestored() throws Exception {
        for (String unsafe : List.of(ProtectedContentPolicy.REDACTED_VALUE,
                "1 | incomplete\n... (output truncated)", "1 | protected fixture content\n")) {
            var state = state();
            String path = unsafe.contains("protected fixture") ? ".env" : "caller.txt";
            read(state, path, unsafe);
            read(state, "implementation.txt", body("implementation.txt"));
            read(state, "options.txt", body("options.txt"));
            assertNotRestored(state, unsafe);
        }
    }

    @Test void withheldOrInvalidatedEvidenceCannotBeRestored() throws Exception {
        for (boolean withheld : List.of(true, false)) {
            var state = state();
            for (String path : PATHS) read(state, path, body(path));
            if (withheld) state.contentWithheldFromModelContext = true;
            else ReadEvidenceStateAccounting.clearSuccessfulReadCaches(state);
            assertNotRestored(state, body(PATHS.getFirst()));
        }
    }

    @Test void changedNativeIdentityCannotRestoreAnOtherwiseMatchingToolResult() throws Exception {
        var state = state();
        for (String path : PATHS) read(state, path, body(path));
        var original = state.messages.get(2).toolCalls().getFirst();
        state.messages.set(2, ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                original.id(), original.name(), Map.of("path", "different.txt")))));
        assertNotRestored(state, body(PATHS.getFirst()));
    }

    @Test void characterAndSourceLimitsDoNotRestoreAnUnboundedSnapshot() throws Exception {
        var oversized = state();
        String large = "x".repeat(ReadOnlyEvidenceContext.MAX_EVIDENCE_CHARS);
        read(oversized, "caller.txt", large);
        read(oversized, "implementation.txt", body("implementation.txt"));
        read(oversized, "options.txt", body("options.txt"));
        assertNotRestored(oversized, large);
        assertTrue(ReadOnlyEvidenceContext.delivery(oversized).retentionLimited());

        var tooMany = state();
        for (int index = 0; index <= ReadOnlyEvidenceContext.MAX_TARGETS; index++)
            read(tooMany, "source-" + index + ".txt", "1 | source observation " + index + "\n");
        assertNotRestored(tooMany, "1 | source observation 0\n");
        assertTrue(ReadOnlyEvidenceContext.delivery(tooMany).retentionLimited());
    }

    @Test void mutationAuthorityDoesNotAcquireReadOnlyRestoration() throws Exception {
        var state = state();
        for (String path : PATHS) read(state, path, body(path));
        TurnTaskContractCapture.set(new TaskContract(TaskType.DIAGNOSE_ONLY, true, true, false,
                Set.of(), Set.of(), "Change the caller."));
        assertNotRestored(state, body(PATHS.getFirst()));
    }
}
