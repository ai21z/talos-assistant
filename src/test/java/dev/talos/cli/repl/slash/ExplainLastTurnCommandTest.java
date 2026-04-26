package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.TurnPolicyTrace;
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
    void specIncludesLastAlias() {
        var cmd = new ExplainLastTurnCommand(Path.of("/ws"), new JsonSessionStore(tempDir));

        assertTrue(cmd.spec().aliases().contains("last"));
        assertTrue(cmd.spec().usage().contains("sources"));
        assertTrue(cmd.spec().usage().contains("--verbose"));
    }

    @Test
    void rendersToolsView() {
        TurnRecord turn = record(
                5,
                "Inspect files",
                "Done.",
                List.of(
                        new TurnRecord.ToolCallSummary("talos.read_file", "index.html", true),
                        new TurnRecord.ToolCallSummary("talos.grep", ".cta-button", false)),
                0,
                0,
                0,
                "ok");

        String text = ExplainLastTurnCommand.renderTools(turn);

        assertTrue(text.contains("Last Turn Tools"));
        assertTrue(text.contains("1. talos.read_file -> index.html [ok]"));
        assertTrue(text.contains("2. talos.grep -> .cta-button [failed]"));
    }

    @Test
    void rendersSourcesViewFromTraceAndToolPaths() {
        TurnRecord turn = record(
                6,
                "Inspect files",
                "Done.",
                List.of(
                        new TurnRecord.ToolCallSummary("talos.read_file", "index.html", true),
                        new TurnRecord.ToolCallSummary("talos.read_file", "index.html", true),
                        new TurnRecord.ToolCallSummary("talos.grep", "script.js", true)),
                0,
                0,
                0,
                "ok");

        String text = ExplainLastTurnCommand.renderSources(turn);

        assertTrue(text.contains("Last Turn Sources"));
        assertTrue(text.contains("Retrieval:"));
        assertEquals(1, countOccurrences(text, "index.html"));
        assertTrue(text.contains("script.js"));
    }

    @Test
    void rendersTraceView() {
        TurnRecord turn = record(
                7,
                "Inspect files",
                "Done.",
                List.of(new TurnRecord.ToolCallSummary("talos.list_dir", ".", true)),
                0,
                0,
                0,
                "ok");

        String text = ExplainLastTurnCommand.renderTrace(turn);

        assertTrue(text.contains("Last Turn"));
        assertTrue(text.contains("Trace Detail"));
        assertTrue(text.contains("Tool calls: 1"));
    }

    @Test
    void verboseFlagRendersTraceView() {
        Path workspace = Path.of("/project/verbose").toAbsolutePath().normalize();
        var store = new JsonSessionStore(tempDir);
        var cmd = new ExplainLastTurnCommand(workspace, store);
        store.appendTurn(JsonSessionStore.sessionIdFor(workspace), record(
                1,
                "Inspect files",
                "Done.",
                List.of(new TurnRecord.ToolCallSummary("talos.list_dir", ".", true)),
                0,
                0,
                0,
                "ok"));

        Result result = cmd.execute("--verbose", minimalCtx());

        assertInstanceOf(Result.TrustedInfo.class, result);
        String text = ((Result.TrustedInfo) result).text;
        assertTrue(text.contains("Trace Detail"), text);
        assertTrue(text.contains("Tool calls: 1"), text);
    }

    @Test
    void executeSelectsNewestTimestampWhenTurnNumbersRestartAfterSessionClear() {
        Path workspace = Path.of("/project/restarted-turns").toAbsolutePath().normalize();
        var store = new JsonSessionStore(tempDir);
        var cmd = new ExplainLastTurnCommand(workspace, store);
        String sessionId = JsonSessionStore.sessionIdFor(workspace);
        store.appendTurn(sessionId, recordAt(
                11,
                Instant.parse("2026-04-26T08:00:00Z"),
                "Old saved request",
                "Old saved answer",
                List.of(),
                0,
                0,
                0,
                "ok"));
        store.appendTurn(sessionId, recordAt(
                1,
                Instant.parse("2026-04-26T20:00:00Z"),
                "hello",
                "Hi.",
                List.of(),
                0,
                0,
                0,
                "ok"));

        Result result = cmd.execute("", minimalCtx());

        assertInstanceOf(Result.TrustedInfo.class, result);
        String text = ((Result.TrustedInfo) result).text;
        assertTrue(text.contains("hello"), text);
        assertFalse(text.contains("Old saved request"), text);
    }

    @Test
    void activeProcessCommandIgnoresSavedTurnsFromBeforeStartup() {
        Path workspace = Path.of("/project/active-last").toAbsolutePath().normalize();
        var store = new JsonSessionStore(tempDir);
        String sessionId = JsonSessionStore.sessionIdFor(workspace);
        store.appendTurn(sessionId, recordAt(
                12,
                Instant.parse("2026-04-26T08:00:00Z"),
                "old saved request",
                "old saved answer",
                List.of(),
                0,
                0,
                0,
                "ok"));
        store.appendTurn(sessionId, recordAt(
                1,
                Instant.parse("2026-04-26T12:05:00Z"),
                "hello",
                "Hi.",
                List.of(),
                0,
                0,
                0,
                "ok"));
        var cmd = new ExplainLastTurnCommand(
                workspace, store, Instant.parse("2026-04-26T12:00:00Z"));

        Result result = cmd.execute("trace", minimalCtx());

        assertInstanceOf(Result.TrustedInfo.class, result);
        String text = ((Result.TrustedInfo) result).text;
        assertTrue(text.contains("hello"), text);
        assertFalse(text.contains("old saved request"), text);
    }

    @Test
    void activeProcessCommandLabelsOnlyPersistedSavedHistory() {
        Path workspace = Path.of("/project/saved-only-last").toAbsolutePath().normalize();
        var store = new JsonSessionStore(tempDir);
        String sessionId = JsonSessionStore.sessionIdFor(workspace);
        store.appendTurn(sessionId, recordAt(
                12,
                Instant.parse("2026-04-26T08:00:00Z"),
                "old saved request",
                "old saved answer",
                List.of(),
                0,
                0,
                0,
                "ok"));
        var cmd = new ExplainLastTurnCommand(
                workspace, store, Instant.parse("2026-04-26T12:00:00Z"));

        Result result = cmd.execute("trace", minimalCtx());

        assertInstanceOf(Result.Info.class, result);
        String text = ((Result.Info) result).text;
        assertTrue(text.contains("active process"), text);
        assertTrue(text.contains("not loaded"), text);
        assertFalse(text.contains("old saved request"), text);
    }

    @Test
    void traceViewIncludesPolicyTraceAndBlockReasons() {
        TurnPolicyTrace policyTrace = new TurnPolicyTrace(
                "FILE_CREATE",
                true,
                true,
                List.of("index.html"),
                List.of(),
                "APPLY",
                "APPLY",
                List.of("talos.read_file", "talos.write_file"),
                List.of("talos.read_file", "talos.write_file"),
                List.of("approval denied by user for talos.write_file"));
        TurnRecord turn = new TurnRecord(
                8,
                Instant.parse("2026-04-26T00:00:00Z"),
                1234,
                "Create index.html",
                "No file changed.",
                List.of(new TurnRecord.ToolCallSummary(
                        "talos.write_file",
                        "index.html",
                        false,
                        "approval denied by user for talos.write_file")),
                1,
                0,
                1,
                "",
                "ok",
                policyTrace);

        String text = ExplainLastTurnCommand.renderTrace(turn);

        assertTrue(text.contains("Contract: FILE_CREATE mutationAllowed=true verificationRequired=true"));
        assertTrue(text.contains("Expected targets: index.html"));
        assertTrue(text.contains("Phase: initial=APPLY final=APPLY"));
        assertTrue(text.contains("Native tools: talos.read_file, talos.write_file"));
        assertTrue(text.contains("Blocked: approval denied by user for talos.write_file"));
        assertTrue(text.contains("reason: approval denied by user for talos.write_file"));
    }

    @Test
    void executeRejectsUnknownView() {
        var cmd = new ExplainLastTurnCommand(Path.of("/ws"), new JsonSessionStore(tempDir));

        Result result = cmd.execute("logs", minimalCtx());

        assertInstanceOf(Result.Error.class, result);
        assertTrue(result.toString().contains("Usage"));
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

    private static TurnRecord recordAt(
            int turnNumber,
            Instant timestamp,
            String userInput,
            String assistantText,
            List<TurnRecord.ToolCallSummary> toolCalls,
            int approvalsRequired,
            int approvalsGranted,
            int approvalsDenied,
            String status) {
        return new TurnRecord(
                turnNumber,
                timestamp,
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

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
