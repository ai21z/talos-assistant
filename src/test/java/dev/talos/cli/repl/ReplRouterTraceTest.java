package dev.talos.cli.repl;

import dev.talos.runtime.Result;

import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ReplRouterTraceTest {

    @Test
    void formatsCurrentTurnPolicyTraceForDebugTraceMode() {
        TurnPolicyTrace policyTrace = new TurnPolicyTrace(
                "SMALL_TALK",
                false,
                false,
                List.of(),
                List.of(),
                "INSPECT",
                "INSPECT",
                List.of(),
                List.of(),
                List.of(),
                "conversation-boundary-policy");
        TurnResult result = new TurnResult(
                new Result.Ok("hello"),
                null,
                1,
                Duration.ofMillis(10),
                new TurnAudit(
                        List.of(),
                        0,
                        0,
                        0,
                        policyTrace,
                        LocalTurnTrace.builder(
                                        "trc-mode",
                                        "session",
                                        1,
                                        "2026-06-28T00:00:00Z")
                                .mode("ask")
                                .build()));

        String text = ReplRouter.formatCurrentTurnTrace(result);

        assertTrue(text.contains("Current Turn Trace"));
        assertTrue(text.contains("mode: ask"));
        assertTrue(text.contains("contract: SMALL_TALK mutationAllowed=false verificationRequired=false"));
        assertTrue(text.contains("classificationReason: conversation-boundary-policy"));
        assertTrue(text.contains("phase: initial=INSPECT final=INSPECT"));
        assertTrue(text.contains("nativeTools: none"));
        assertTrue(text.contains("blocked: none"));
    }

    @Test
    void debugTraceRendersRequiredPostReadMutationRoleWithoutCoercion() {
        TurnPolicyTrace policyTrace = TurnPolicyTrace.from(
                new dev.talos.runtime.task.TaskContract(
                        dev.talos.runtime.task.TaskType.FILE_EDIT,
                        true,
                        true,
                        true,
                        java.util.Set.of("style.css"),
                        java.util.Set.of("style.css"),
                        java.util.Set.of(),
                        "Read style.css, then update style.css.",
                        "required-post-read-mutation"),
                "APPLY",
                List.of("talos.read_file", "talos.edit_file"),
                List.of("tool_use:read_file", "tool_use:edit_file"));
        TurnResult result = new TurnResult(
                new Result.Ok("done"),
                null,
                1,
                Duration.ofMillis(10),
                new TurnAudit(
                        List.of(),
                        0,
                        0,
                        0,
                        policyTrace,
                        LocalTurnTrace.builder(
                                        "trc-required-post-read",
                                        "session",
                                        1,
                                        "2026-07-23T00:00:00Z")
                                .mode("auto")
                                .build()));

        String text = ReplRouter.formatCurrentTurnTrace(result);

        assertTrue(text.contains(
                "targetRoles: style.css=MUST_MUTATE_AFTER_SOURCE_EVIDENCE"), text);
        assertEquals(
                1,
                text.split("MUST_MUTATE_AFTER_SOURCE_EVIDENCE", -1).length - 1,
                text);
        assertFalse(java.util.regex.Pattern
                .compile("style\\.css=MUST_MUTATE(?!_)")
                .matcher(text)
                .find(), text);
    }
}
