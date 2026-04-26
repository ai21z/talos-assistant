package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.TurnRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExplainLastTurnCommandTest {
    @TempDir Path tempDir;

    @Test
    void noTurnsReturnsInfo() {
        var cmd = new ExplainLastTurnCommand(Path.of("/ws"), new JsonSessionStore(tempDir));

        Result result = cmd.execute("", minimalCtx());

        assertInstanceOf(Result.Info.class, result);
        assertTrue(((Result.Info) result).text.contains("No completed turn"));
    }

    @Test
    void rendersReadOnlyTurnAudit() {
        Path workspace = Path.of("/project/read-only").toAbsolutePath().normalize();
        var store = new JsonSessionStore(tempDir);
        var cmd = new ExplainLastTurnCommand(workspace, store);
        store.appendTurn(JsonSessionStore.sessionIdFor(workspace), record(
                1,
                "Check selectors",
                "Mismatches found",
                List.of(
                        new TurnRecord.ToolCallSummary("talos.list_dir", ".", true),
                        new TurnRecord.ToolCallSummary("talos.read_file", "index.html", true),
                        new TurnRecord.ToolCallSummary("talos.grep", ".cta-button", true)),
                0,
                0,
                0,
                "ok"));

        Result result = cmd.execute("", minimalCtx());

        assertInstanceOf(Result.TrustedInfo.class, result);
        String text = ((Result.TrustedInfo) result).text;
        assertTrue(text.contains("Last Turn"));
        assertTrue(text.contains("Outcome:   INSPECTION_RECORDED"));
        assertTrue(text.contains("talos.read_file -> index.html [ok]"));
        assertTrue(text.contains("User Request"));
    }

    @Test
    void rendersApprovalDeniedOutcome() {
        TurnRecord turn = record(
                2,
                "Edit index.html",
                "No file changes were applied.",
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", false)),
                1,
                0,
                1,
                "ok");

        String text = ExplainLastTurnCommand.render(turn);

        assertTrue(text.contains("Outcome:   BLOCKED_BY_APPROVAL"));
        assertTrue(text.contains("Approvals: required=1 granted=0 denied=1"));
        assertTrue(text.contains("talos.edit_file -> index.html [failed]"));
    }

    @Test
    void rendersMutationAppliedOutcome() {
        TurnRecord turn = record(
                3,
                "Apply the fix",
                "Edited index.html.",
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true)),
                1,
                1,
                0,
                "ok");

        assertEquals("MUTATION_APPLIED", ExplainLastTurnCommand.inferOutcome(turn));
    }

    @Test
    void rendersPartialMutationOutcome() {
        TurnRecord turn = record(
                4,
                "Edit two files",
                "One file changed.",
                List.of(
                        new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true),
                        new TurnRecord.ToolCallSummary("talos.edit_file", "script.js", false)),
                2,
                1,
                0,
                "ok");

        assertEquals("PARTIAL_MUTATION", ExplainLastTurnCommand.inferOutcome(turn));
    }

    private static Context minimalCtx() {
        return Context.builder(new Config()).build();
    }

    private static TurnRecord record(
            int turnNumber,
            String userInput,
            String assistantText,
            List<TurnRecord.ToolCallSummary> toolCalls,
            int approvalsRequired,
            int approvalsGranted,
            int approvalsDenied,
            String status) {
        return new TurnRecord(
                turnNumber,
                Instant.parse("2026-04-26T00:00:00Z"),
                1234,
                userInput,
                assistantText,
                toolCalls,
                approvalsRequired,
                approvalsGranted,
                approvalsDenied,
                "2 stages, 5.0ms, final=3",
                status);
    }
}
