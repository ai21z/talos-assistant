package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.TurnTraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
                List.of("approval denied by user for talos.write_file"),
                "explicit-request-pattern");
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
        assertTrue(text.contains("Classification reason: explicit-request-pattern"));
        assertTrue(text.contains("Expected targets: index.html"));
        assertTrue(text.contains("Phase: initial=APPLY final=APPLY"));
        assertTrue(text.contains("Native tools: talos.read_file, talos.write_file"));
        assertTrue(text.contains("Blocked: approval denied by user for talos.write_file"));
        assertTrue(text.contains("reason: approval denied by user for talos.write_file"));
    }

    @Test
    void traceViewRedactsSecretLikeValuesFromUserRequestPreview() {
        TurnPolicyTrace policyTrace = new TurnPolicyTrace(
                "FILE_EDIT",
                true,
                true,
                List.of(".env"),
                List.of(),
                "APPLY",
                "APPLY",
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of("permission policy denied talos.write_file: PROTECTED_PATH_DENY path=.env"));
        TurnRecord turn = new TurnRecord(
                9,
                Instant.parse("2026-04-26T00:00:00Z"),
                1234,
                "Overwrite .env with SECRET=changed. Use talos.write_file.",
                "No file changed because the protected path policy blocked the request.",
                List.of(new TurnRecord.ToolCallSummary(
                        "talos.write_file",
                        ".env",
                        false,
                        "permission policy denied talos.write_file: PROTECTED_PATH_DENY path=.env")),
                0,
                0,
                0,
                "",
                "ok",
                policyTrace);

        String text = ExplainLastTurnCommand.renderTrace(turn);

        assertTrue(text.contains("User Request"), text);
        assertTrue(text.contains("Overwrite .env with SECRET=[redacted]. Use talos.write_file."), text);
        assertFalse(text.contains("SECRET=changed"), text);
        assertTrue(text.contains("talos.write_file -> .env [failed]"), text);
        assertTrue(text.contains("PROTECTED_PATH_DENY"), text);
    }

    @Test
    void traceViewIncludesLocalTraceWhenTurnHasTraceId() {
        Path workspace = Path.of("/project/local-trace").toAbsolutePath().normalize();
        var store = new JsonSessionStore(tempDir);
        var cmd = new ExplainLastTurnCommand(workspace, store);
        String sessionId = JsonSessionStore.sessionIdFor(workspace);
        LocalTurnTrace trace = LocalTurnTrace.builder(
                        "trc-local",
                        sessionId,
                        1,
                        "2026-04-28T12:00:00Z")
                .workspaceHash("workspace-hash")
                .mode("auto")
                .model("ollama", "qwen2.5-coder:14b")
                .toolSurface(
                        List.of("talos.read_file", "talos.write_file"),
                        List.of("talos.read_file", "talos.write_file"),
                        "mutation task")
                .promptAudit(new dev.talos.runtime.trace.PromptAuditSnapshot(
                        1,
                        "FILE_CREATE",
                        true,
                        true,
                        "APPLY",
                        "APPLY",
                        "MUTATING_TOOL_REQUIRED",
                        "NONE_OR_NOT_DERIVED",
                        "NOT_DERIVED",
                        "NONE_OR_NOT_DERIVED",
                        "NONE_OR_NOT_DERIVED",
                        "NONE_OR_NOT_DERIVED",
                        "INCLUDED",
                        4,
                        true,
                        "AFTER_HISTORY_BEFORE_USER",
                        "frame-hash",
                        "[CurrentTurnCapability] SECRET=[redacted]",
                        2,
                        1,
                        7,
                        "prompt-hash",
                        List.of("talos.read_file", "talos.write_file"),
                        List.of("talos.read_file", "talos.write_file"),
                        List.of(),
                        dev.talos.runtime.trace.TraceRedactionMode.DEFAULT))
                .event(TurnTraceEvent.simple(
                        "ACTION_OBLIGATION_EVALUATED",
                        "2026-04-28T12:00:00Z",
                        Map.of(
                                "obligation", "MUTATING_TOOL_REQUIRED",
                                "status", "UNSATISFIED",
                                "reason", "model response had no write/edit tool calls")))
                .event(TurnTraceEvent.simple(
                        "ACTION_OBLIGATION_EVALUATED",
                        "2026-04-28T12:00:01Z",
                        Map.of(
                                "obligation", "MUTATING_TOOL_REQUIRED",
                                "status", "SATISFIED_AFTER_RETRY",
                                "reason", "retry response issued write/edit tool calls")))
                .checkpoint("CREATED", "chk-local")
                .repair("PLANNED", "STATIC_VERIFICATION_REPAIR steps=2 problems=3")
                .verification("FAILED", "Static verification failed", List.of("scripts.js missing"))
                .outcome("FAILED", "FAILED", "UNKNOWN", "PARTIAL", "TASK_INCOMPLETE")
                .build();
        store.saveTrace(sessionId, trace);
        store.appendTurn(sessionId, new TurnRecord(
                1,
                Instant.parse("2026-04-28T12:00:01Z"),
                1200,
                "create bmi app",
                "Static verification failed.",
                List.of(new TurnRecord.ToolCallSummary("talos.write_file", "index.html", true)),
                1,
                1,
                0,
                "",
                "ok",
                TurnPolicyTrace.empty(),
                "trc-local"));

        Result result = cmd.execute("trace", minimalCtx());

        assertInstanceOf(Result.TrustedInfo.class, result);
        String text = ((Result.TrustedInfo) result).text;
        assertTrue(text.contains("Local trace: trc-local"), text);
        assertTrue(text.contains("Schema: 2"), text);
        assertTrue(text.contains("Redaction: DEFAULT"), text);
        assertTrue(text.contains("Prompt Audit"), text);
        assertTrue(text.contains("actionObligation: MUTATING_TOOL_REQUIRED"), text);
        assertTrue(text.contains("currentTurnFrame: injected AFTER_HISTORY_BEFORE_USER hash=frame-hash"), text);
        assertTrue(text.contains("SECRET=[redacted]"), text);
        assertFalse(text.contains("SECRET=changed"), text);
        assertTrue(text.contains("Action obligation: MUTATING_TOOL_REQUIRED (SATISFIED_AFTER_RETRY)"), text);
        assertTrue(text.contains("Checkpoint: CREATED chk-local"), text);
        assertTrue(text.contains("Repair: PLANNED - STATIC_VERIFICATION_REPAIR steps=2 problems=3"), text);
        assertTrue(text.contains("Verification: FAILED - Static verification failed"), text);
        assertTrue(text.contains("scripts.js missing"), text);
        assertTrue(text.contains("Outcome: FAILED"), text);
    }

    @Test
    void traceViewIncludesProjectMemoryPromptAuditStatus() {
        LocalTurnTrace trace = LocalTurnTrace.builder(
                        "trc-project-memory-last",
                        "sid",
                        1,
                        "2026-06-07T12:00:00Z")
                .promptAudit(new dev.talos.runtime.trace.PromptAuditSnapshot(
                        1,
                        "WORKSPACE_EXPLAIN",
                        false,
                        false,
                        "INSPECT",
                        "INSPECT",
                        "INSPECT_REQUIRED",
                        "WORKSPACE_INSPECTION_REQUIRED",
                        "NOT_DERIVED",
                        "NONE_OR_NOT_DERIVED",
                        "NONE_OR_NOT_DERIVED",
                        "NONE_OR_NOT_DERIVED",
                        "INCLUDED",
                        0,
                        true,
                        "AFTER_HISTORY_BEFORE_USER",
                        "frame-hash",
                        "[CurrentTurnCapability]",
                        3,
                        1,
                        4,
                        "prompt-hash",
                        List.of("talos.list_dir", "talos.read_file"),
                        List.of("talos.list_dir", "talos.read_file"),
                        List.of(),
                        dev.talos.runtime.trace.TraceRedactionMode.DEFAULT,
                        "NOT_DERIVED",
                        "status=LOADED reason=WORKSPACE_EXPLAIN included=1 decisions=1 truncated=0 tiers=REPO_ROOT"))
                .build();
        TurnRecord turn = record(
                1,
                "Explain this project.",
                "I will inspect it.",
                List.of(),
                0,
                0,
                0,
                "ok");

        String text = ExplainLastTurnCommand.renderTrace(turn, trace);

        assertTrue(text.contains("projectMemory: status=LOADED"), text);
        assertTrue(text.contains("tiers=REPO_ROOT"), text);
    }

    @Test
    void traceViewUsesLocalOutcomeForBlockedNoToolMutation() {
        TurnRecord turn = record(
                11,
                "Change index.html to say hello.",
                "[Action obligation failed: no file was changed in this turn.]",
                List.of(),
                0,
                0,
                0,
                "ok");
        LocalTurnTrace trace = LocalTurnTrace.builder(
                        "trc-blocked-no-tool",
                        "sid",
                        11,
                        "2026-05-03T00:00:00Z")
                .outcome(
                        "BLOCKED",
                        "UNKNOWN",
                        "UNKNOWN",
                        "NONE",
                        "BLOCKED_BY_POLICY")
                .build();

        String text = ExplainLastTurnCommand.renderTrace(turn, trace);

        assertTrue(text.contains("Status:    BLOCKED"), text);
        assertTrue(text.contains("Outcome:   BLOCKED_BY_POLICY"), text);
        assertTrue(text.contains("Status tag: BLOCKED"), text);
        assertFalse(text.contains("Status:    ok"), text);
        assertFalse(text.contains("Outcome:   NO_TOOL_RESPONSE"), text);
        assertFalse(text.contains("Status tag: ok"), text);
    }

    @Test
    void traceViewUsesLocalOutcomeForAdvisoryNoToolEvidence() {
        TurnRecord turn = record(
                12,
                "Read README.md and summarize it.",
                "[Evidence incomplete: required workspace evidence was not gathered in this turn.]",
                List.of(),
                0,
                0,
                0,
                "ok");
        LocalTurnTrace trace = LocalTurnTrace.builder(
                        "trc-advisory-no-tool",
                        "sid",
                        12,
                        "2026-05-03T00:00:00Z")
                .outcome(
                        "ADVISORY_ONLY",
                        "UNKNOWN",
                        "UNKNOWN",
                        "NONE",
                        "ADVISORY_ONLY")
                .build();

        String text = ExplainLastTurnCommand.renderTrace(turn, trace);

        assertTrue(text.contains("Status:    ADVISORY_ONLY"), text);
        assertTrue(text.contains("Outcome:   ADVISORY_ONLY"), text);
        assertTrue(text.contains("Status tag: ADVISORY_ONLY"), text);
        assertFalse(text.contains("Status:    ok"), text);
        assertFalse(text.contains("Outcome:   NO_TOOL_RESPONSE"), text);
        assertFalse(text.contains("Status tag: ok"), text);
    }

    @Test
    void traceViewUsesLocalOutcomeForBackendFailure() {
        TurnRecord turn = record(
                13,
                "Overwrite index.html with exactly AFTER.",
                "[Engine error: Engine error (HTTP 400)]",
                List.of(),
                0,
                0,
                0,
                "ok");
        LocalTurnTrace trace = LocalTurnTrace.builder(
                        "trc-backend-response-error",
                        "sid",
                        13,
                        "2026-05-03T00:00:00Z")
                .outcome(
                        "FAILED",
                        "NOT_RUN",
                        "UNKNOWN",
                        "BACKEND_ERROR",
                        "BACKEND_RESPONSE_ERROR")
                .build();

        String text = ExplainLastTurnCommand.renderTrace(turn, trace);

        assertTrue(text.contains("Status:    FAILED"), text);
        assertTrue(text.contains("Outcome:   BACKEND_RESPONSE_ERROR"), text);
        assertTrue(text.contains("Status tag: FAILED"), text);
        assertTrue(text.contains("Outcome: FAILED (BACKEND_RESPONSE_ERROR)"), text);
        assertFalse(text.contains("Status:    ok"), text);
        assertFalse(text.contains("Outcome:   NO_TOOL_RESPONSE"), text);
    }

    @Test
    void traceViewShowsRolefulTargetDerivationReasons() {
        String prompt = "Keep styles.css unchanged. Update index.html and scripts.js.";
        TurnPolicyTrace policyTrace = TurnPolicyTrace.from(
                dev.talos.runtime.task.TaskContractResolver.fromUserRequest(prompt),
                "APPLY",
                List.of("talos.read_file", "talos.write_file"),
                List.of("talos.read_file", "talos.write_file"));
        TurnRecord turn = new TurnRecord(
                14,
                Instant.parse("2026-04-26T00:00:00Z"),
                1234,
                prompt,
                "Blocked before completion.",
                List.of(),
                0,
                0,
                0,
                "2 stages, 5.0ms, final=3",
                "ok",
                policyTrace);

        String text = ExplainLastTurnCommand.renderTrace(turn);

        assertTrue(text.contains("Target roles:"), text);
        assertTrue(text.contains("styles.css = FORBIDDEN (preserve-unchanged-target)"), text);
        assertTrue(text.contains("index.html = MUST_MUTATE"), text);
        assertTrue(text.contains("scripts.js = MUST_MUTATE"), text);
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
    void rendersDeniedProtectedReadAsBlockedApprovalOutcome() {
        TurnRecord turn = record(
                10,
                "Read .env and tell me what it says.",
                "Protected content was not read because approval was denied.",
                List.of(new TurnRecord.ToolCallSummary(
                        "talos.read_file",
                        ".env",
                        false,
                        "approval denied by user for talos.read_file")),
                1,
                0,
                1,
                "ok");

        String text = ExplainLastTurnCommand.renderTrace(turn);

        assertTrue(text.contains("Outcome:   BLOCKED_BY_APPROVAL"), text);
        assertFalse(text.contains("Outcome:   COMPLETE"), text);
        assertFalse(text.contains("READ_ONLY_ANSWERED"), text);
        assertTrue(text.contains("talos.read_file -> .env [failed]"), text);
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
