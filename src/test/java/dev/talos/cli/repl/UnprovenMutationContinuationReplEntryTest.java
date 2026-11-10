package dev.talos.cli.repl;

import dev.talos.cli.approval.CliApprovalGate;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.slash.CommandRegistry;
import dev.talos.core.Audit;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.net.NetPolicy;
import dev.talos.core.rag.RagService;
import dev.talos.core.security.Redactor;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.JsonTurnLogAppender;
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionApprovalPolicy;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.SessionWriterLease;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnTraceCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.command.CommandProfileRegistry;
import dev.talos.runtime.command.ProcessCommandRunner;
import dev.talos.runtime.command.RunCommandTool;
import dev.talos.runtime.toolcall.DocumentComparisonTool;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.DeletePathTool;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RenamePathTool;
import dev.talos.tools.impl.RetrieveTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A round in which every call succeeded ends the turn only when request-bound proof already holds (P20, finding LA-F3,
 * D14 option A), and a checks pass on a changed turn states that task completion was not verified (D15 option A),
 * through the real REPL entry with the harness of ReplacementScopeReplEntryTest: the bootstrap composition with a
 * scripted native provider that records every request and repeats its last response once its script is exhausted,
 * or throws a given failure instead, the TalosBootstrap tool registry, mode routing, the real approval gate and a
 * real session store. The provider and the command profile registry are synthetic.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(120)
class UnprovenMutationContinuationReplEntryTest {
    private static final String TWO_PART =
            "Update notes.md: change the heading to Session Notes and add an item that says Mix the drums.";
    private static final String NOTES = "# Notes\n\n- Tune the bass line.\n- Record the second take.\n";
    private static final String HEADING_ONLY = "# Session Notes\n\n- Tune the bass line.\n- Record the second take.\n";
    private static final String BOTH_PARTS =
            "# Session Notes\n\n- Tune the bass line.\n- Record the second take.\n- Mix the drums.\n";
    private static final String PAGE = "<!DOCTYPE html>\n<html>\n<head>\n<title>Dead Synth</title>\n"
            + "<link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n<h1>Dead Synth</h1>\n</body>\n</html>\n";
    private static final String STYLE = "h1 {\n  color: black;\n}\n";
    private static final String NOT_VERIFIED = "Task completion was not verified.";
    private static final String TWO_FILES =
            "In notes.md, replace status=old with status=new. In more.md, replace status2=old with status2=new.";
    private static final String DECIDED = "MUTATION_CONTINUATION_DECIDED";

    @TempDir
    Path temp;

    @Test
    void aSecondChangeOfOneFileInALaterResponseIsRequestedAndApplied() throws Exception {
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                edit("notes.md", "- Record the second take.\n", "- Record the second take.\n- Mix the drums.\n"),
                text("Changed the heading and added the item.")), List.of("y", "y"),
                Map.of("notes.md", NOTES));

        assertEquals(BOTH_PARTS, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(2, run.prompts().size(), run.transcript());
        assertEquals(4, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED", "CONTINUED"), decisions(run), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains(NOT_VERIFIED), answer);
        assertTrue(answer.contains("[Checks passed - "), answer);
    }

    @Test
    void aRequestBoundPassEndsWithoutAFurtherProviderRequest() throws Exception {
        Run run = run("In notes.md, change second take to final take.", List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "second take", "final take")), List.of("y"),
                Map.of("notes.md", NOTES));

        assertEquals("# Notes\n\n- Tune the bass line.\n- Record the final take.\n",
                Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(2, run.requests().size(), run.transcript());
        assertEquals(List.of("PROOF_HELD"), decisions(run), run.transcript());
        Map<String, Object> decided = events(run, DECIDED).getFirst();
        assertEquals("PASSED", decided.get("proofStatus"), decided.toString());
        assertEquals(Boolean.TRUE, decided.get("requestBound"), decided.toString());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains("[Static verification: passed - "), answer);
        assertFalse(answer.contains(NOT_VERIFIED), answer);
    }

    @Test
    void anEmptyContinuationReplyEndsWithTheToolSummary() throws Exception {
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                text("")), List.of("y"),
                Map.of("notes.md", NOTES));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(3, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        // The displayed answer carries the summary of the change, as the P0 skip gives it. The stored answer keeps
        // the checks bracket, as the live gpt-oss C3 row did.
        assertTrue(run.transcript().contains("Edited notes.md"), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains(NOT_VERIFIED), answer);
        assertFalse(answer.contains("no executable write/edit tool calls"), answer);
        assertFalse(run.transcript().contains("no executable write/edit tool calls"), run.transcript());
    }

    @Test
    void aContinuationClaimOfAChangeThatWasNotMadeIsNotShown() throws Exception {
        // Until P24 the claim reached the answer and the claim guard kept the turn from COMPLETE. Since P24 (D23
        // option A) the reply without a call is not shown, and the turn ends with the summary of the change it made.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                text("I updated notes.md and I changed todo.md during this turn.")), List.of("y"),
                Map.of("notes.md", NOTES));

        assertFalse(Files.exists(run.workspace().resolve("todo.md")), run.transcript());
        assertEquals(3, run.requests().size(), run.transcript());
        assertEquals(List.of("TEXT_REPLY"), unansweredReasons(run), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        assertFalse(run.transcript().contains("todo.md"), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains(NOT_VERIFIED), answer);
        assertTrue(answerBox(run).contains("Edited notes.md"), answerBox(run));
    }

    @Test
    void aContinuationChangeOutsideTheNamedTargetIsBlockedBeforeApprovalAfterASessionApproval() throws Exception {
        Run run = run(TWO_PART, List.of(
                edit("notes.md", "# Notes", "# Session Notes"),
                edit("other.md", "# Other", "# Other page"),
                text("Changed the heading.")), List.of("a", "y"),
                Map.of("notes.md", NOTES, "other.md", "# Other\n"));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals("# Other\n", Files.readString(run.workspace().resolve("other.md")), run.transcript());
        assertEquals(1, run.prompts().size(), run.transcript());
        assertTrue(run.prompts().getFirst().contains("a=yes for session"), run.prompts().toString());
        assertEquals("CONTINUED", decisions(run).getFirst(), run.transcript());
        assertTrue(events(run, "TOOL_CALL_BLOCKED").stream()
                .anyMatch(data -> "other.md".equals(data.get("pathHint"))), run.transcript());
    }

    @Test
    void aContinuationChangeForARequestThatNamesNoFileIsPromptedWithoutTheSessionOption() throws Exception {
        Run run = run("fix the page", List.of(
                edit("style.css", "color: black;", "color: navy;"),
                edit("index.html", "<h1>Dead Synth</h1>", "<h1>Dead Synth Live</h1>"),
                text("Adjusted the page.")), List.of("y", "n"),
                Map.of("index.html", PAGE, "style.css", STYLE));

        assertEquals(PAGE, Files.readString(run.workspace().resolve("index.html")), run.transcript());
        assertEquals("h1 {\n  color: navy;\n}\n", Files.readString(run.workspace().resolve("style.css")),
                run.transcript());
        assertEquals(2, run.prompts().size(), run.transcript());
        assertTrue(run.prompts().stream().noneMatch(prompt -> prompt.contains("a=yes for session")),
                run.prompts().toString());
        assertEquals("CONTINUED", decisions(run).getFirst(), run.transcript());
    }

    @Test
    void aProviderFailureOfTheContinuationEndsWithTheChangeAsBeforeTheContinuation() throws Exception {
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes")), List.of("y"),
                Map.of("notes.md", NOTES),
                new dev.talos.spi.EngineException.ConnectionFailed("127.0.0.1:18261", null));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(3, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        assertEquals(List.of("PROVIDER_FAILURE"), events(run, "MUTATION_CONTINUATION_UNANSWERED").stream()
                .map(data -> String.valueOf(data.get("reason"))).toList(), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        assertTrue(run.transcript().contains("Edited notes.md"), run.transcript());
        assertTrue(run.onlyRow().assistantText().contains(NOT_VERIFIED), run.onlyRow().assistantText());
    }

    @Test
    void aFailureOfALaterRequestAfterTheContinuationEndsWithTheChange() throws Exception {
        // The continuation is answered with a read, and the request after it fails at the provider.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                nativeCall("talos.read_file", Map.of("path", "notes.md"))), List.of("y"),
                Map.of("notes.md", NOTES),
                new dev.talos.spi.EngineException.ConnectionFailed("127.0.0.1:18261", null));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(4, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        assertEquals(List.of("PROVIDER_FAILURE"), events(run, "MUTATION_CONTINUATION_UNANSWERED").stream()
                .map(data -> String.valueOf(data.get("reason"))).toList(), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        assertTrue(run.transcript().contains("Edited notes.md"), run.transcript());
    }

    @Test
    void aContextBudgetOverflowOfTheContinuationEndsWithTheChangeAsBeforeTheContinuation() throws Exception {
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes")), List.of("y"),
                Map.of("notes.md", NOTES),
                new dev.talos.spi.EngineException.ContextBudgetExceeded(5000, 1334, 2048, 0));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(List.of("CONTEXT_BUDGET"), events(run, "MUTATION_CONTINUATION_UNANSWERED").stream()
                .map(data -> String.valueOf(data.get("reason"))).toList(), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        assertFalse(run.onlyRow().assistantText().contains("Action obligation failed"),
                run.onlyRow().assistantText());
    }

    @Test
    void aContinuationReplyWhoseCallWasRefusedEndsWithTheChangeAsBeforeTheContinuation() throws Exception {
        // P21 (finding LP-F1, D17 option A, D18 option A): the reply to the continuation writes its call as text in
        // native tool mode, as qwen did in every live D1 sample. The refused call never runs, and the turn ends as its
        // completed change left it, without a failure stop and without a further request.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                text(textCall("talos.read_file", Map.of("path", "notes.md"))),
                text("Unused.")), List.of("y"),
                Map.of("notes.md", NOTES));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(3, run.requests().size(), run.transcript());
        assertEquals(1, run.prompts().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        List<Map<String, Object>> unanswered = events(run, "MUTATION_CONTINUATION_UNANSWERED");
        assertEquals(List.of("TOOL_AUTHORITY_REJECTED"), unanswered.stream()
                .map(data -> String.valueOf(data.get("reason"))).toList(), run.transcript());
        assertEquals("PROVIDER_TOOL_MODE_MISMATCH", String.valueOf(unanswered.getFirst().get("failureType")),
                unanswered.toString());
        assertFalse(events(run, "TOOL_CALL_AUTHORITY_REJECTED").isEmpty(), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        String answerBox = answerBox(run);
        assertTrue(answerBox.contains("Edited notes.md"), answerBox);
        assertTrue(answerBox.contains("[1 not run]"), answerBox);
        assertFalse(answerBox.contains(" failed]"), answerBox);
        assertFalse(answerBox.contains("[failure policy stopped]"), answerBox);
        assertFalse(answerBox.contains("PROVIDER_TOOL_MODE_MISMATCH"), answerBox);
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains(NOT_VERIFIED), answer);
        assertFalse(answer.contains("PROVIDER_TOOL_MODE_MISMATCH"), answer);
    }

    @Test
    void aRefusedTextFormEditAfterTheContinuationNeverRuns() throws Exception {
        // P21 review: the refused call is an edit, so its never running is visible in the file. LA-F7: the refused
        // proposal never ran, so the answer lists it as not run (finding P21-F1). D25 option B: it was a later
        // proposed change to the requested file and no change followed it, so the turn stays partial and says so.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                text(textCall("talos.edit_file", Map.of("path", "notes.md",
                        "old_string", "- Record the second take.\n",
                        "new_string", "- Record the second take.\n- Mix the drums.\n"))),
                text("Unused.")), List.of("y", "y"),
                Map.of("notes.md", NOTES));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(1, run.prompts().size(), run.transcript());
        assertEquals(3, run.requests().size(), run.transcript());
        assertEquals(List.of("TOOL_AUTHORITY_REJECTED"), unansweredReasons(run), run.transcript());
        assertEquals("PARTIAL", outcomeStatus(run), run.transcript());
        String answerBox = answerBox(run);
        assertTrue(answerBox.contains("[1 not run]"), answerBox);
        assertFalse(answerBox.contains(" failed]"), answerBox);
        assertFalse(answerBox.contains("[failure policy stopped]"), answerBox);
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains("Requested files with a later change that did not run:\n"
                + "- notes.md: changed earlier in this turn, but a later proposed change to it did not run.\n"
                + "Not run (these changed no file):\n"
                + "- notes.md: talos.edit_file not run, it was not accepted as an executable tool call."), answer);
    }

    @Test
    void aRefusedReplyAfterAContinuationAndAFailedEditEndsWithTheChange() throws Exception {
        // P21 review: the continuation stays issued for the rest of the loop, as P20's rule states. After it, an edit
        // fails before approval, the ordinary re-prompt follows, and its reply writes the call as text.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                edit("notes.md", "- Record the third take.\n", "- Record the third take.\n- Mix the drums.\n"),
                text(textCall("talos.read_file", Map.of("path", "notes.md"))),
                text("Unused.")), List.of("y", "y"),
                Map.of("notes.md", NOTES));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(4, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        assertEquals(List.of("TOOL_AUTHORITY_REJECTED"), unansweredReasons(run), run.transcript());
        String answerBox = answerBox(run);
        // LA-F7: the old_string miss was refused before approval, so both refused proposals count as not run.
        assertTrue(answerBox.contains("[2 not run]"), answerBox);
        assertFalse(answerBox.contains(" failed]"), answerBox);
        assertFalse(answerBox.contains("[failure policy stopped]"), answerBox);
        assertFalse(answerBox.contains("PROVIDER_TOOL_MODE_MISMATCH"), answerBox);
    }

    @Test
    void aContinuationChangeIsAskedOnceWithoutTheSessionOptionAfterASessionApproval() throws Exception {
        // P23 (finding LQ-F1, D20 option A): the first change is approved for the session, and the change that the
        // model proposes after the continuation is asked once, without the session option and with the reason.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                edit("notes.md", "- Record the second take.\n", "- Record the second take.\n- Mix the drums.\n"),
                text("Changed the heading and added the item.")), List.of("a", "y"),
                Map.of("notes.md", NOTES));

        assertEquals(BOTH_PARTS, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(2, run.prompts().size(), run.transcript());
        assertTrue(run.prompts().getFirst().contains("a=yes for session"), run.prompts().toString());
        assertFalse(run.prompts().get(1).contains("a=yes for session"), run.prompts().toString());
        assertTrue(joinedTranscript(run).contains(CONTINUATION_WRITE_REASON), run.transcript());
        assertEquals(List.of("DEFAULT_WRITE_ASK", "CONTINUATION_WRITE_ASK"), writeReasons(run), run.transcript());
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), writeRememberEligible(run), run.transcript());
        assertEquals(List.of("CONTINUED", "CONTINUED"), decisions(run), run.transcript());
    }

    @Test
    void theDuplicatingFooterEditOfFindingLqF1IsAskedBeforeItRuns() throws Exception {
        // P23 check 1, the LQ-F1 shape of gpt-oss D1 sample 3 in mica-p21-live-20261107-001: the approved first edit
        // places the footer after the body, and the edit that the model proposes after the continuation adds a
        // second footer inside the body. It is now asked once, without the session option and with the reason.
        String footer = "<footer>Thanks for visiting</footer>\n";
        Run run = run("add a footer that says Thanks for visiting to index.html", List.of(
                nativeCall("talos.read_file", Map.of("path", "index.html")),
                edit("index.html", "</body>\n", "</body>\n" + footer),
                edit("index.html", "<h1>Dead Synth</h1>\n", "<h1>Dead Synth</h1>\n" + footer),
                text("Added the footer.")), List.of("a", "n"),
                Map.of("index.html", PAGE, "style.css", STYLE));

        assertEquals(PAGE.replace("</body>\n", "</body>\n" + footer),
                Files.readString(run.workspace().resolve("index.html")), run.transcript());
        assertEquals(2, run.prompts().size(), run.transcript());
        assertTrue(run.prompts().getFirst().contains("a=yes for session"), run.prompts().toString());
        assertFalse(run.prompts().get(1).contains("a=yes for session"), run.prompts().toString());
        assertTrue(joinedTranscript(run).contains(CONTINUATION_WRITE_REASON), run.transcript());
        assertEquals(List.of("DEFAULT_WRITE_ASK", "CONTINUATION_WRITE_ASK"), writeReasons(run), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
    }

    @Test
    void aDeniedContinuationChangeEndsWithTheCompletedChange() throws Exception {
        // P23 (D20 option A): a denial of the change after the continuation leaves the file as its completed change
        // left it and sends no further request. LA-F7: the declined change never ran, so the answer lists it as not
        // run. D25 option B: it was a later proposed change to the requested file and no change followed it, so the
        // turn stays partial and says so (finding P23-F1).
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                edit("notes.md", "- Record the second take.\n", "- Record the second take.\n- Mix the drums.\n"),
                text("Unused.")), List.of("a", "n"),
                Map.of("notes.md", NOTES));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(2, run.prompts().size(), run.transcript());
        assertFalse(run.prompts().get(1).contains("a=yes for session"), run.prompts().toString());
        assertEquals(3, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        assertEquals(List.of("DEFAULT_WRITE_ASK", "CONTINUATION_WRITE_ASK"), writeReasons(run), run.transcript());
        assertEquals("PARTIAL", outcomeStatus(run), run.transcript());
        String answerBox = answerBox(run);
        assertTrue(answerBox.contains("Edited notes.md"), answerBox);
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.startsWith("[Truth check: some file changes were applied, but a proposed change to a file "
                + "this request expects did not run."), answer);
        assertTrue(answer.contains("Requested files with a later change that did not run:\n"
                + "- notes.md: changed earlier in this turn, but a later proposed change to it did not run.\n"
                + "Not run (these changed no file):\n"
                + "- notes.md: talos.edit_file not run, approval was declined."), answer);
        assertFalse(answerBox.contains("[failure policy stopped]"), answerBox);
        assertFalse(answerBox.contains("Tool loop stopped"), answerBox);
    }

    /**
     * D26 option A, critic item 1: a complete write of a file the turn already changed, while another requested file
     * remains, is asked once without the session option. When the approved write undoes the requested change, the
     * turn does not complete and the model's claim is not shown.
     */
    @Test
    void anAdmittedCompleteWriteThatUndoesTheRequestedChangeDoesNotCompleteTheTurn() throws Exception {
        Run run = run(TWO_FILES, List.of(
                edit("notes.md", "status=old", "status=new"),
                nativeCall("talos.write_file", Map.of("path", "notes.md", "content", "status=old\nfooter\nreviewed\n")),
                edit("more.md", "status2=old", "status2=new"),
                text("Both replacements are done.")), List.of("a", "y"),
                Map.of("notes.md", "status=old\nfooter\n", "more.md", "status2=old\n"));

        assertEquals("status=old\nfooter\nreviewed\n", Files.readString(run.workspace().resolve("notes.md")),
                run.transcript());
        assertEquals("status2=new\n", Files.readString(run.workspace().resolve("more.md")), run.transcript());
        assertEquals(List.of("DEFAULT_WRITE_ASK", "CONTINUATION_WRITE_ASK", "SESSION_REMEMBER_ALLOW"),
                writeReasons(run), run.transcript());
        assertEquals(2, run.prompts().size(), run.transcript());
        assertFalse(run.prompts().get(1).contains("a=yes for session"), run.prompts().toString());
        assertTrue(events(run, "PENDING_ACTION_OBLIGATION_BREACHED").isEmpty(), run.transcript());
        assertNotEquals("COMPLETE", outcomeStatus(run), run.transcript());
        assertFalse(run.onlyRow().assistantText().contains("Both replacements are done."),
                run.onlyRow().assistantText());
    }

    /**
     * D26 option A with D25 option B: a declined complete write of a file the turn already changed is admitted and
     * asked instead of stopping the turn. The decline ends the loop, so more.md stays unchanged and the turn is
     * partial for that reason. The notes.md row is the D25 option B statement that a later proposed change to a
     * requested file did not run.
     */
    @Test
    void aDeclinedCompleteWriteOfAChangedFileKeepsTheTurnPartial() throws Exception {
        Run run = run(TWO_FILES, List.of(
                edit("notes.md", "status=old", "status=new"),
                nativeCall("talos.write_file", Map.of("path", "notes.md", "content", "status=new\nfooter\nreviewed\n")),
                edit("more.md", "status2=old", "status2=new"),
                text("Both replacements are done.")), List.of("a", "n"),
                Map.of("notes.md", "status=old\nfooter\n", "more.md", "status2=old\n"));

        assertEquals("status=new\nfooter\n", Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals("status2=old\n", Files.readString(run.workspace().resolve("more.md")), run.transcript());
        assertTrue(events(run, "PENDING_ACTION_OBLIGATION_BREACHED").isEmpty(), run.transcript());
        assertEquals("PARTIAL", outcomeStatus(run), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains("Expected files not changed:\n"
                + "- more.md: expected by this request, but not changed in this turn.\n"
                + "Requested files with a later change that did not run:\n"
                + "- notes.md: changed earlier in this turn, but a later proposed change to it did not run.\n"),
                answer);
        assertTrue(answer.contains("- notes.md: talos.write_file not run, approval was declined."), answer);
    }

    @Test
    void twoChangesInTheFirstResponseKeepTheSessionApproval() throws Exception {
        // P23: before any continuation, a remembered approval keeps covering the named target, as in P11 L5.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                new LlmClient.StreamResult("", List.of(
                        new ChatMessage.NativeToolCall("call-heading", "talos.edit_file", Map.of("path", "notes.md",
                                "old_string", "# Notes", "new_string", "# Session Notes")),
                        new ChatMessage.NativeToolCall("call-item", "talos.edit_file", Map.of("path", "notes.md",
                                "old_string", "- Record the second take.\n",
                                "new_string", "- Record the second take.\n- Mix the drums.\n"))), "tool_calls"),
                text("Changed the heading and added the item.")), List.of("a"),
                Map.of("notes.md", NOTES));

        assertEquals(BOTH_PARTS, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(1, run.prompts().size(), run.transcript());
        assertEquals(List.of("DEFAULT_WRITE_ASK", "SESSION_REMEMBER_ALLOW"), writeReasons(run), run.transcript());
    }

    @Test
    void aSessionApprovalFromAnEarlierTurnCoversTheFirstChangeOfALaterTurn() throws Exception {
        // P23: the first turn issues a continuation and asks its later change without the session option. The
        // continuation of that turn does not reach the next, so the later turn's first change of a named target runs
        // under the remembered approval without a prompt. A third prompt would be denied and fail the file check.
        Run run = runTurns(List.of(
                        TWO_PART,
                        "In notes.md, change bass line to drum line."),
                List.of(
                        nativeCall("talos.read_file", Map.of("path", "notes.md")),
                        edit("notes.md", "# Notes", "# Session Notes"),
                        edit("notes.md", "- Record the second take.\n",
                                "- Record the second take.\n- Mix the drums.\n"),
                        text("Changed the heading and added the item."),
                        edit("notes.md", "bass line", "drum line"),
                        text("Unused.")),
                List.of("a", "y"),
                Map.of("notes.md", NOTES), null);

        assertEquals("# Session Notes\n\n- Tune the drum line.\n- Record the second take.\n- Mix the drums.\n",
                Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(2, run.prompts().size(), run.transcript());
        assertTrue(run.prompts().getFirst().contains("a=yes for session"), run.prompts().toString());
        assertFalse(run.prompts().get(1).contains("a=yes for session"), run.prompts().toString());
        assertTrue(joinedTranscript(run).contains(CONTINUATION_WRITE_REASON), run.transcript());
        assertEquals(5, run.requests().size(), run.transcript());
        assertEquals(List.of("SESSION_REMEMBER_ALLOW"), writeReasons(run), run.transcript());
        assertEquals(List.of("PROOF_HELD"), decisions(run), run.transcript());
    }

    /** The reply of qwen F2 sample 1 in mica-p23-live-20261108-001, 568 characters without a call (finding LR-F1). */
    private static final String LR_F1_REPLY = "To add the item \"Mix the drums\" to the list, I'll first read the current "
            + "content of notes.md again to ensure I have the latest version.\n\n[tool_response]\n[tool_result: "
            + "talos.read_file]\n1 | # Session Notes\n2 | \n3 | - Tune the bass line.\n4 | - Record the final take.\n\n"
            + "[/tool_result]\n[/tool_response]\n\nNow, I'll append the new item to the list.\n\n[tool_response]\n"
            + "[tool_result: talos.edit_file]\nEdited notes.md: replaced 4 line(s) with 5 line(s) (80 bytes total). "
            + "Verified: read-back OK. [verification: UNKNOWN]\n[verification_status: UNKNOWN]\n[/tool_result]\n"
            + "[/tool_response]";
    private static final String UNRESOLVED_CLAIM = "the actor or time of a claimed file change is unresolved";

    @Test
    void theImitatedToolResultOfFindingLrF1IsNotShownAfterAContinuation() throws Exception {
        // P24 check 1 (D23 option A): turn 1 grants the session approval, the first part of turn 2 runs under it, the
        // continuation is issued, and the reply imitates Talos tool results for a change that was never made.
        Run run = runTurns(List.of("In notes.md, change second take to final take.", TWO_PART),
                List.of(
                        nativeCall("talos.read_file", Map.of("path", "notes.md")),
                        edit("notes.md", "second take", "final take"),
                        edit("notes.md", "# Notes", "# Session Notes"),
                        text(LR_F1_REPLY),
                        text("Unused.")),
                List.of("a"), Map.of("notes.md", NOTES), null);

        assertEquals("# Session Notes\n\n- Tune the bass line.\n- Record the final take.\n",
                Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(1, run.prompts().size(), run.transcript());
        assertEquals(4, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        assertEquals(List.of("TEXT_REPLY"), unansweredReasons(run), run.transcript());
        assertEquals(List.of(LR_F1_REPLY.length()), events(run, "MUTATION_CONTINUATION_UNANSWERED").stream()
                .map(data -> ((Number) data.get("replyChars")).intValue()).toList(), run.transcript());
        String answer = run.rows().getLast().assistantText();
        assertTrue(answer.contains(NOT_VERIFIED), answer);
        assertFalse(answer.contains("[tool_result"), answer);
        assertFalse(answer.contains("Verified: read-back OK."), answer);
        String answerBox = answerBox(run);
        assertTrue(answerBox.contains("Edited notes.md"), answerBox);
        assertFalse(answerBox.contains("[tool_result"), answerBox);
        assertFalse(answerBox.contains("replaced 4 line(s) with 5 line(s)"), answerBox);
    }

    @Test
    void aTrueBoldClaimAfterAContinuationDoesNotMakeTheTurnAdvisory() throws Exception {
        // P24 check 2, the LR-F2 shape of gpt-oss F2: both parts are made, the second after the continuation, and the
        // true reply names the file in bold, which the lexical claim guard cannot resolve.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                edit("notes.md", "- Record the second take.\n", "- Record the second take.\n- Mix the drums.\n"),
                text("Updated **notes.md**:\n\n```\n" + BOTH_PARTS + "```")), List.of("y", "y"),
                Map.of("notes.md", NOTES));

        assertEquals(BOTH_PARTS, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(4, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED", "CONTINUED"), decisions(run), run.transcript());
        assertEquals(List.of("TEXT_REPLY"), unansweredReasons(run), run.transcript());
        assertEquals("COMPLETE", outcomeStatus(run), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains("[Checks passed - "), answer);
        assertTrue(answer.contains(NOT_VERIFIED), answer);
        assertFalse(answer.contains(UNRESOLVED_CLAIM), answer);
        String answerBox = answerBox(run);
        assertEquals(2, answerBox.split("Edited notes.md", -1).length - 1, answerBox);
    }

    @Test
    void aCopiedRuntimeNoticeAsTheContinuationReplyIsNotShown() throws Exception {
        // P24 check 3, the LP-F2 echo of qwen-D_2 turn 2 in mica-p20-live-20261107-001: the reply copies a runtime
        // notice although nothing was blocked in the turn.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                text("[Tool execution was blocked because executable tool authority was invalid "
                        + "(PROVIDER_TOOL_MODE_MISMATCH). No follow-up provider request was sent.]")), List.of("y"),
                Map.of("notes.md", NOTES));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(3, run.requests().size(), run.transcript());
        assertEquals(List.of("TEXT_REPLY"), unansweredReasons(run), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertFalse(answer.contains("PROVIDER_TOOL_MODE_MISMATCH"), answer);
        assertTrue(answer.contains(NOT_VERIFIED), answer);
        assertFalse(answerBox(run).contains("PROVIDER_TOOL_MODE_MISMATCH"), answerBox(run));
    }

    @Test
    void aReplyAfterAContinuationIsKeptWhenTheRequestCarriesATypedAnswerObligation() throws Exception {
        // P24 check 4 (D24 option A): the request asks for a calculation beside the change, a recognized answer
        // requirement, so the reply after the continuation keeps reaching the answer under the existing guards.
        Run run = run(TWO_PART + " Calculate the total for 3 + 4.", List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                edit("notes.md", "- Record the second take.\n", "- Record the second take.\n- Mix the drums.\n"),
                text("Changed the heading and added the item. The total for 3 + 4 is 7.")), List.of("y", "y"),
                Map.of("notes.md", NOTES));

        assertEquals(BOTH_PARTS, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(4, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED", "CONTINUED"), decisions(run), run.transcript());
        assertEquals(List.of(), unansweredReasons(run), run.transcript());
        assertTrue(run.onlyRow().assistantText().contains("The total for 3 + 4 is 7."), run.onlyRow().assistantText());
    }

    @Test
    void aReplyWithoutACallAfterTheLastIterationEndsWithTheChanges() throws Exception {
        // P24 review finding 1, the shape of gpt-oss E1 in mica-p23-live-20261108-001 (11 requests, 10 iterations):
        // after the continuation the model only searches until the last iteration, and the reply that arrives after
        // it is never parsed inside the loop.
        List<LlmClient.StreamResult> responses = new ArrayList<>(List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes")));
        for (int i = 3; i <= ToolCallLoop.DEFAULT_MAX_ITERATIONS; i++) {
            responses.add(nativeCall("talos.grep", Map.of("pattern", "item " + i)));
        }
        responses.add(text(LR_F1_REPLY));
        Run run = run(TWO_PART, responses, List.of("y"), Map.of("notes.md", NOTES));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(ToolCallLoop.DEFAULT_MAX_ITERATIONS + 1, run.requests().size(), run.transcript());
        assertEquals(List.of("CONTINUED"), decisions(run), run.transcript());
        assertEquals(List.of("TEXT_REPLY"), unansweredReasons(run), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains(NOT_VERIFIED), answer);
        assertFalse(answer.contains("[tool_result"), answer);
        assertFalse(answerBox(run).contains("replaced 4 line(s) with 5 line(s)"), answerBox(run));
    }

    @Test
    void aMalformedCallAfterAContinuationEndsWithTheChanges() throws Exception {
        // P24 review finding 5: the reply holds call text that names a Talos tool and does not decode. The provider
        // text projection keeps only the opening tag of a call with an unsettled JSON tail, so the loop receives a
        // reply without call text, and the turn ends with the changes as for any reply without a call.
        Run run = run(TWO_PART, List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                edit("notes.md", "# Notes", "# Session Notes"),
                text("<tool_call>\n{\"name\": \"talos.edit_file\", \"arguments\": {\"path\": \"notes.md\"\n</tool_call>"),
                text("Unused.")), List.of("y"),
                Map.of("notes.md", NOTES));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(3, run.requests().size(), run.transcript());
        assertEquals(List.of("TEXT_REPLY"), unansweredReasons(run), run.transcript());
        assertEquals(List.of("<tool_call>".length()), events(run, "MUTATION_CONTINUATION_UNANSWERED").stream()
                .map(data -> ((Number) data.get("replyChars")).intValue()).toList(), run.transcript());
        assertFalse(run.transcript().contains("<tool_call>"), run.transcript());
        assertTrue(run.onlyRow().assistantText().contains(NOT_VERIFIED), run.onlyRow().assistantText());
        assertTrue(answerBox(run).contains("Edited notes.md"), answerBox(run));
    }

    @Test
    void aReplyWithoutACallAfterAChangeWithoutAContinuationKeepsItsHandling() throws Exception {
        // P24 review finding 6: a change was made, but a named target remains, so the re-prompt is not a
        // continuation and the reply keeps its handling before P24.
        Run run = run("Change the heading of notes.md to Session Notes and the heading of todo.md to Todo List.",
                List.of(
                        edit("notes.md", "# Notes", "# Session Notes"),
                        text("I changed the heading of notes.md only.")), List.of("y"),
                Map.of("notes.md", NOTES, "todo.md", "# Todo\n\n- Buy strings.\n"));

        assertEquals(HEADING_ONLY, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals("# Todo\n\n- Buy strings.\n", Files.readString(run.workspace().resolve("todo.md")),
                run.transcript());
        assertEquals(List.of(), decisions(run), run.transcript());
        assertEquals(List.of(), unansweredReasons(run), run.transcript());
    }

    @Test
    void aReplyWithoutACallBeforeAnyContinuationKeepsItsAnswer() throws Exception {
        // P24 check 5: without a completed change no continuation is issued, so the reply stays the answer.
        Run run = run("Summarize notes.md.", List.of(
                nativeCall("talos.read_file", Map.of("path", "notes.md")),
                text("The file has a heading and two items.")), List.of(),
                Map.of("notes.md", NOTES));

        assertEquals(NOTES, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertEquals(List.of(), decisions(run), run.transcript());
        assertEquals(List.of(), unansweredReasons(run), run.transcript());
        assertTrue(run.onlyRow().assistantText().contains("The file has a heading and two items."),
                run.onlyRow().assistantText());
    }

    private static final String CONTINUATION_WRITE_REASON =
            "This change was proposed after Talos asked the model to continue, so the session approval does not "
                    + "cover it.";

    /** The permission reasons of the writes of the last turn, in order. */
    private static List<String> writeReasons(Run run) {
        return writePermissionDecisions(run).stream().map(data -> String.valueOf(data.get("reasonCode"))).toList();
    }

    private static List<Object> writeRememberEligible(Run run) {
        return writePermissionDecisions(run).stream().map(data -> data.get("rememberEligible")).toList();
    }

    private static List<Map<String, Object>> writePermissionDecisions(Run run) {
        return events(run, "PERMISSION_DECISION").stream()
                .filter(data -> !"DEFAULT_READ_ALLOW".equals(data.get("reasonCode")))
                .toList();
    }

    /** The transcript with the approval window's line wrapping and borders joined. */
    private static String joinedTranscript(Run run) {
        return run.transcript().replaceAll("\\s*\\R\\s*[|│]\\s*", " ");
    }

    /** The last answer box of the transcript, read with the box's line wrapping joined. */
    private static String answerBox(Run run) {
        return run.transcript().substring(run.transcript().lastIndexOf("+- answer -"))
                .replaceAll("\\s*\\R\\s*\\|\\s*", " ");
    }

    private static List<String> unansweredReasons(Run run) {
        return events(run, "MUTATION_CONTINUATION_UNANSWERED").stream()
                .map(data -> String.valueOf(data.get("reason"))).toList();
    }

    @Test
    void aRequestOfOneAuthorizedOperationEndsOnceThatOperationSucceeds() throws Exception {
        Run run = run("Rename a.md to b.md.", List.of(
                nativeCall("talos.rename_path", Map.of("path", "a.md", "new_name", "b.md"))), List.of("y"),
                Map.of("a.md", "# A\n"));

        assertTrue(Files.exists(run.workspace().resolve("b.md")), run.transcript());
        assertFalse(Files.exists(run.workspace().resolve("a.md")), run.transcript());
        assertEquals(1, run.requests().size(), run.transcript());
        assertEquals(List.of("OPERATION_COMPLETE"), decisions(run), run.transcript());
    }

    @Test
    void theIterationLimitEndsAModelThatKeepsChangingTheFile() throws Exception {
        StringBuilder items = new StringBuilder("# Items\n\n");
        List<LlmClient.StreamResult> responses = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String item = String.format("- item %02d", i);
            items.append(item).append('\n');
            responses.add(edit("items.md", item, String.format("- done %02d", i)));
            answers.add("y");
        }
        Run run = run("Update items.md: mark every item as done.", responses, answers,
                Map.of("items.md", items.toString()));

        int limit = ToolCallLoop.DEFAULT_MAX_ITERATIONS;
        assertEquals(limit, run.requests().size(), run.transcript());
        List<String> decisions = decisions(run);
        assertEquals(limit, decisions.size(), decisions.toString());
        assertEquals("LIMIT_REACHED", decisions.getLast(), decisions.toString());
        assertTrue(decisions.subList(0, limit - 1).stream().allMatch("CONTINUED"::equals), decisions.toString());
        String file = Files.readString(run.workspace().resolve("items.md"));
        assertTrue(file.contains(String.format("- done %02d", limit)), file);
        assertTrue(file.contains(String.format("- item %02d", limit + 1)), file);
        // After continuations the answer lists every change of the turn, not only the last round's.
        String answerBox = run.transcript().substring(run.transcript().lastIndexOf("+- answer -"));
        assertEquals(limit, answerBox.split("Edited items.md", -1).length - 1, answerBox);
    }

    private static List<String> decisions(Run run) {
        return events(run, DECIDED).stream().map(data -> String.valueOf(data.get("decision"))).toList();
    }

    private static String outcomeStatus(Run run) {
        List<Map<String, Object>> outcomes = events(run, "OUTCOME_EVALUATED");
        assertEquals(1, outcomes.size(), run.transcript());
        return String.valueOf(outcomes.getFirst().get("status"));
    }

    private static List<Map<String, Object>> events(Run run, String type) {
        return run.trace().events().stream()
                .filter(event -> type.equals(event.type()))
                .map(event -> event.data())
                .toList();
    }

    private record Run(
            Path workspace,
            List<ChatRequest> requests,
            List<String> prompts,
            String transcript,
            List<TurnRecord> rows,
            LocalTurnTrace trace) {
        TurnRecord onlyRow() {
            assertEquals(1, rows.size(), transcript);
            return rows.getFirst();
        }
    }

    private Run run(String request, List<LlmClient.StreamResult> responses, List<String> answers,
                    Map<String, String> files) throws Exception {
        return run(request, responses, answers, files, null);
    }

    /**
     * Runs one turn. Approval prompt i is answered with answers[i], and any further prompt is denied. Without a
     * terminal failure the provider repeats its last response once the script is exhausted. With one it throws that
     * failure instead.
     */
    private Run run(String request, List<LlmClient.StreamResult> responses, List<String> answers,
                    Map<String, String> files, RuntimeException terminalFailure) throws Exception {
        return runTurns(List.of(request), responses, answers, files, terminalFailure);
    }

    /**
     * Runs one turn per request in one session, with one provider script, one approval policy and one answer list
     * across the turns. The trace is the last turn's.
     */
    private Run runTurns(List<String> turnRequests, List<LlmClient.StreamResult> responses, List<String> answers,
                         Map<String, String> files, RuntimeException terminalFailure) throws Exception {
        String request = String.join("\n", turnRequests);
        Path dir = Files.createDirectories(temp.resolve("run-" + Math.abs(request.hashCode())
                + "-" + responses.size() + "-" + String.join("", answers)
                + (terminalFailure == null ? "" : "-" + terminalFailure.getClass().getSimpleName())));
        Path home = Files.createDirectories(dir.resolve("home"));
        Path ws = Files.createDirectories(dir.resolve("workspace"));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(ws.resolve(file.getKey()), file.getValue());
        }
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Path sessions = home.resolve(".talos").resolve("sessions");
        SessionWriterLease lease = null;
        JsonSessionStore store = null;
        RenderEngine render = null;
        try {
            Config cfg = new Config(null);
            Redactor redactor = new Redactor();
            RagService rag = new RagService(cfg);
            CommandProfileRegistry profiles = CommandProfileRegistry.defaultRegistry();
            ToolRegistry registry = productionRegistry(rag, profiles);
            ScriptedNativeLlmClient.RecordedClient provider = terminalFailure == null
                    ? ScriptedNativeLlmClient.recordingWithContextWindow(responses, 16_384)
                    : ScriptedNativeLlmClient.recordingThenFailureWithContextWindow(responses, 16_384, terminalFailure);
            LlmClient llm = provider.client();
            llm.setToolSpecs(registry.descriptors().stream()
                    .map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema()))
                    .toList());

            SessionMemory memory = new SessionMemory();
            ConversationManager conversation = new ConversationManager(memory, TokenBudget.fromConfig(cfg));
            store = new JsonSessionStore(sessions);
            lease = store.reserveNewSession(ws, Instant.now());
            String sessionId = lease.sessionId();
            Session runtimeSession = new Session(ws, cfg, memory, lease);
            ModeController modes = ModeController.defaultController();

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            render = new RenderEngine(cfg, redactor, out, false);
            RenderEngine renderRef = render;
            List<String> prompts = new ArrayList<>();
            CliApprovalGate gate = new CliApprovalGate(prompt -> {
                prompts.add(prompt);
                return prompts.size() <= answers.size() ? answers.get(prompts.size() - 1) : "n";
            }, out, renderRef::stopSpinner);
            TurnProcessor processor = new TurnProcessor(modes, gate, registry, new SessionApprovalPolicy(),
                    new CheckpointService(), profiles);
            ToolCallLoop loop = new ToolCallLoop(processor, ToolCallLoop.DEFAULT_MAX_ITERATIONS,
                    renderRef::printToolProgressResumingSpinner);
            SessionState state = new SessionState() {
                private int k = 6;
                private boolean debug;
                public int getK() { return k; }
                public void setK(int value) { k = value; }
                public boolean isDebug() { return debug; }
                public void setDebug(boolean on) { debug = on; }
            };
            Context ctx = Context.builder(cfg)
                    .limits(Limits.fromConfig(cfg))
                    .session(state)
                    .audit(new Audit())
                    .redactor(redactor)
                    .sandbox(new Sandbox(ws, Map.of()))
                    .rag(rag)
                    .llm(llm)
                    .netPolicy(new NetPolicy(cfg))
                    .memory(memory)
                    .approvalGate(gate)
                    .toolRegistry(registry)
                    .conversationManager(conversation)
                    .toolCallLoop(loop)
                    .streamSink(new ToolCallStreamFilter(render.answerStreamSink(chunk -> {
                        out.print(chunk);
                        out.flush();
                    })))
                    .onStreamComplete(renderRef::stopSpinner)
                    .exactAnswerPresentation(render.exactAnswerPresentation())
                    .build();
            MemoryUpdateListener memoryListener = new MemoryUpdateListener(conversation, llm, memory);
            memoryListener.setAssistMode(true);
            processor.addListener(memoryListener);
            processor.addListener(new ActiveTaskContextUpdateListener(memory));
            processor.addListener(new JsonTurnLogAppender(lease, sessionId));
            ReplRouter router = new ReplRouter(modes, processor, runtimeSession, ctx, render,
                    new CommandRegistry(), ws, new AtomicBoolean(false), "", WorkspaceCommandTemplates.none());

            for (String turnRequest : turnRequests) {
                assertTrue(router.tryHandlePrompt(turnRequest));
            }
            render.shutdown();
            render = null;
            String transcript = bytes.toString(StandardCharsets.UTF_8).replaceAll("\u001B\\[[0-9;]*m", "");
            try (JsonSessionStore reopened = new JsonSessionStore(sessions)) {
                List<TurnRecord> rows = reopened.loadTurns(sessionId);
                LocalTurnTrace trace = rows.isEmpty() ? null
                        : reopened.loadTrace(sessionId, rows.getLast().traceId()).orElse(null);
                assertTrue(trace != null, "The turn trace must persist and reload: " + transcript);
                return new Run(ws, List.copyOf(provider.requests()), List.copyOf(prompts), transcript, rows, trace);
            }
        } finally {
            if (render != null) render.shutdown();
            if (lease != null) lease.close();
            if (store != null) store.close();
            TurnUserRequestCapture.clear();
            TurnTaskContractCapture.clear();
            TurnSourceEvidenceCapture.clear();
            TurnTraceCapture.clear();
            LocalTurnTraceCapture.clear();
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
            PromptDebugCapture.clear();
            LastPromptCapture.clear();
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    /** The TalosBootstrap registry in its registration order. */
    private static ToolRegistry productionRegistry(RagService rag, CommandProfileRegistry profiles) {
        ToolRegistry registry = new ToolRegistry();
        for (TalosTool tool : List.<TalosTool>of(new ReadFileTool(), new DocumentComparisonTool(),
                new FileWriteTool(), new FileEditTool(), new BatchWorkspaceApplyTool(), new MakeDirectoryTool(),
                new MovePathTool(), new CopyPathTool(), new RenamePathTool(), new DeletePathTool(),
                new RunCommandTool(profiles, new ProcessCommandRunner()), new GrepTool(), new ListDirTool(),
                new RetrieveTool(rag))) {
            registry.register(tool);
        }
        return registry;
    }

    private static LlmClient.StreamResult nativeCall(String tool, Map<String, Object> arguments) {
        return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("call-" + tool, tool, arguments)),
                "tool_calls");
    }

    private static LlmClient.StreamResult edit(String path, String oldString, String newString) {
        return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                "call-edit-" + Math.abs((path + oldString).hashCode()), "talos.edit_file",
                Map.of("path", path, "old_string", oldString, "new_string", newString))), "tool_calls");
    }

    private static LlmClient.StreamResult text(String content) {
        return new LlmClient.StreamResult(content, List.of(), "stop");
    }

    /** A tool call written as text, the form ToolModeCorrectionBoundaryTest uses, which native tool mode refuses. */
    private static String textCall(String tool, Map<String, Object> arguments) throws Exception {
        return "```json\n" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                Map.of("name", tool, "arguments", arguments)) + "\n```";
    }
}
