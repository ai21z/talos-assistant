package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic builder boundaries. Planner eligibility and provider execution are tested separately. */
class RuntimeIdentityRepairFieldProjectionTest {
    @TempDir Path workspace;
    private static final String TASK = "Rewrite README.md using source.md. Preserve all existing content.";
    private static final String REQUEST = "Who are you? " + TASK;
    private static final String EVIDENCE = "Quoted question: Who are you?";

    @AfterEach void clear() { TurnTaskContractCapture.clear(); }

    @Test void everyRepairFieldProjectsOnlyItsCurrentTask() throws Exception {
        for (String field : List.of("expected", "exact-source", "before-read", "after-read", "old-string", "append", "compact")) {
            var state = state(REQUEST);
            var before = List.copyOf(state.messages);
            TurnTaskContractCapture.clear();
            String baseline = render(field, state, REQUEST);
            capture(state, false);
            assertEquals(baseline, render(field, state, REQUEST), field + " disabled");
            if (!field.equals("compact")) {
                var unrelated = state("Who are you? Explain recursion.");
                capture(unrelated, true);
                assertEquals(baseline, render(field, state, REQUEST), field + " unbound");
            }
            capture(state, true);
            var authority = TurnTaskContractCapture.getResolution();
            String projected = render(field, state, REQUEST);
            assertEquals(baseline.replace(REQUEST, TASK), projected, field);
            assertTrue(projected.contains(TASK), field);
            if (!field.equals("before-read")) assertTrue(projected.contains(EVIDENCE), field);
            assertSame(authority, TurnTaskContractCapture.getResolution(), field);
            assertEquals(REQUEST, authority.contract().originalUserRequest(), field);
            assertEquals(before, state.messages, field);
        }
    }

    @Test void rawPaddedTaskIsBoundBeforeBuilderTrimming() throws Exception {
        String raw = " \t" + REQUEST + "\n";
        for (String field : List.of("expected", "exact-source", "before-read", "after-read", "old-string", "append", "compact")) {
            var state = state(raw);
            capture(state, true);
            String projected = render(field, state, raw);
            assertTrue(projected.contains("request:\n" + TASK), field + projected);
            assertEquals(raw, TurnTaskContractCapture.providerTaskRequest());
        }
    }

    @Test void temporaryAnchorProjectsBeforeTruncationAndPreservesHistoryAndEvidence() throws Exception {
        String task = TASK + " Keep this label: " + "x".repeat(600) + ".";
        String original = "Who are you? " + task;
        var state = state(original);
        var before = List.copyOf(state.messages);
        for (boolean enabled : List.of(false, true)) {
            capture(state, enabled);
            try (var overlay = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), original)) {
                String expected = enabled ? " " + task : original;
                assertEquals("[Current task - stay focused on this] " + expected.substring(0, 500) + "…",
                        state.messages.getLast().content());
                assertEquals(before, state.messages.subList(0, before.size()));
                assertEquals(original, TurnTaskContractCapture.get().originalUserRequest());
            }
            assertEquals(before, state.messages);
        }
        TurnTaskContractCapture.clear();
        try (var overlay = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), original)) {
            assertEquals("[Current task - stay focused on this] " + original.substring(0, 500) + "…",
                    state.messages.getLast().content());
        }
        assertEquals(before, state.messages);
    }

    private LoopState state(String request) throws Exception {
        var messages = new ArrayList<>(List.of(ChatMessage.system("system"), ChatMessage.user(request)));
        var state = new LoopState("", List.of(), messages, workspace, Context.builder(new Config()).build(), null, 5, 0);
        ReadOnlyEvidenceContextTest.read(state, "README.md", EVIDENCE, Map.of("path", "README.md"));
        return state;
    }

    private void capture(LoopState state, boolean enabled) {
        TurnTaskContractCapture.set(TaskContractResolver.resolveFromMessages(state.messages), state.messages,
                ModelIdentitySnapshot.empty(), enabled);
    }

    private String render(String field, LoopState state, String request) throws Exception {
        var sources = List.of(new SourceDerivedEvidenceGuard.SourceReadback("source.md", EVIDENCE));
        Object result = switch (field) {
            case "expected" -> {
                Class<?> repair = Class.forName(ExpectedTargetScopeRepairPlanner.class.getName() + "$ExpectedTargetRepair");
                var constructor = repair.getDeclaredConstructor(List.class, String.class, String.class, String.class, String.class, String.class);
                constructor.setAccessible(true);
                Object frame = constructor.newInstance(List.of("README.md"), "wrong.md", "wrong target", EVIDENCE, "", "");
                yield invoke(ExpectedTargetScopeRepairPlanner.class, "expectedTargetRepairMessages",
                        new Class<?>[]{repair, String.class}, frame, request);
            }
            case "exact-source" -> invoke(SourceEvidenceExactRepairPlanner.class, "repairMessages",
                    new Class<?>[]{String.class, String.class, List.class, String.class}, "README.md", "missing phrase", sources, request);
            case "before-read" -> invoke(SourceEvidenceReadBeforeWriteRepairPlanner.class, "repairMessages",
                    new Class<?>[]{List.class, String.class}, List.of("source.md"), request);
            case "after-read" -> invoke(SourceEvidencePostReadWriteRepairPlanner.class, "repairMessages",
                    new Class<?>[]{List.class, List.class, String.class}, List.of("README.md"), sources, request);
            case "old-string" -> invoke(TargetReadbackCompactRepairPlanner.class, "oldStringMissRepairMessages",
                    new Class<?>[]{String.class, String.class, String.class, String.class}, "README.md", "not found", EVIDENCE, request);
            case "append" -> invoke(TargetReadbackCompactRepairPlanner.class, "appendLineRepairMessages",
                    new Class<?>[]{String.class, String.class, String.class, String.class, String.class},
                    "README.md", "Who are you?", "preserve lines", EVIDENCE, request);
            case "compact" -> invoke(CompactMutationContinuationPlanner.class, "compactMutationContinuationMessages",
                    new Class<?>[]{LoopState.class, TaskContract.class, String.class, List.class},
                    state, TaskContractResolver.fromUserRequest(request), "test", List.of());
            default -> throw new AssertionError(field);
        };
        @SuppressWarnings("unchecked") var messages = (List<ChatMessage>) result;
        return messages.stream().map(ChatMessage::content).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Object invoke(Class<?> owner, String name, Class<?>[] types, Object... arguments) throws Exception {
        var method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }
}
