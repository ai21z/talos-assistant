package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.expectation.LiteralContentExpectation;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactWriteContextFallbackTest {
    @Test
    void preparesCompactExactWriteFallbackWithWriteFileOnly() {
        Context ctx = Context.builder(new Config())
                .nativeToolSpecs(List.of(writeFile(), editFile()))
                .build();
        CurrentTurnPlan plan = exactWritePlan();

        ExactWriteContextFallback.Request request = ExactWriteContextFallback
                .prepare(ctx, plan, (ignoredCtx, ignoredPlan, ignoredTools) -> new ChatRequestControls(
                        ToolChoiceMode.REQUIRED,
                        "talos.write_file",
                        ResponseFormatMode.TEXT,
                        "",
                        List.of("existing-tag")))
                .orElseThrow();

        assertEquals(List.of("talos.write_file"),
                request.toolSpecs().stream().map(ToolSpec::name).toList());
        assertEquals("Write file.", request.toolSpecs().getFirst().description());
        String prompt = request.messages().stream()
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("Talos compact current-turn retry."), prompt);
        assertTrue(prompt.contains("[ExpectedTargets]"), prompt);
        assertTrue(prompt.contains("requiredTargets: index.html"), prompt);
        assertTrue(prompt.contains("[ExactFileWrite]"), prompt);
        assertTrue(prompt.contains("AFTER"), prompt);
        assertFalse(prompt.contains("older failed BMI repair history"), prompt);
        assertEquals(ToolChoiceMode.REQUIRED, request.controls().toolChoice());
        assertTrue(request.controls().debugTags().contains("existing-tag"));
        assertTrue(request.controls().debugTags().contains("context-budget-current-turn-fallback"));
    }

    @Test
    void skipsFallbackWithoutExactLiteralExpectation() {
        Context ctx = Context.builder(new Config())
                .nativeToolSpecs(List.of(writeFile()))
                .build();
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html"),
                Set.of(),
                "Update index.html.");
        CurrentTurnPlan plan = new CurrentTurnPlan(
                contract,
                "Update index.html.",
                ExecutionPhase.APPLY,
                ExecutionPhase.APPLY,
                ActionObligation.MUTATING_TOOL_REQUIRED,
                List.of(),
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        assertTrue(ExactWriteContextFallback
                .prepare(ctx, plan, (ignoredCtx, ignoredPlan, ignoredTools) -> ChatRequestControls.defaults())
                .isEmpty());
    }

    @Test
    void recordsCompactFallbackTraceEvent() {
        CurrentTurnPlan plan = exactWritePlan();
        LocalTurnTraceCapture.begin(
                "trc-t446-exact-write-context-fallback",
                "sid",
                1,
                "2026-05-25T00:00:00Z",
                "workspace-hash",
                "test",
                "scripted",
                "test-model",
                plan.originalUserRequest());
        try {
            ExactWriteContextFallback.record(
                    plan,
                    new EngineException.ContextBudgetExceeded(9000, 8000, 8192, 0));
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(trace.events().stream()
                            .anyMatch(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type())
                                    && "RETRIED_COMPACT_CONTEXT".equals(event.data().get("status"))
                                    && String.valueOf(event.data().get("reason"))
                                    .contains("talos.write_file only")),
                    "trace should record the exact-write compact fallback decision");
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    private static CurrentTurnPlan exactWritePlan() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html"),
                Set.of(),
                "Overwrite index.html with exactly AFTER. Use talos.write_file.");
        return new CurrentTurnPlan(
                contract,
                "Overwrite index.html with exactly AFTER. Use talos.write_file.",
                ExecutionPhase.APPLY,
                ExecutionPhase.APPLY,
                ActionObligation.MUTATING_TOOL_REQUIRED,
                List.of(new LiteralContentExpectation(
                        "index.html",
                        "AFTER",
                        LiteralContentExpectation.MatchMode.EXACT,
                        "with exactly")),
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NOT_DERIVED,
                "older failed BMI repair history",
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);
    }

    private static ToolSpec writeFile() {
        return new ToolSpec(
                "talos.write_file",
                "Write a file.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}");
    }

    private static ToolSpec editFile() {
        return new ToolSpec(
                "talos.edit_file",
                "Edit a file.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"old_string\":{\"type\":\"string\"},\"new_string\":{\"type\":\"string\"}},\"required\":[\"path\",\"old_string\",\"new_string\"]}");
    }
}
