package dev.talos.runtime;
import dev.talos.runtime.SessionMemory;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class MemoryUpdateListenerTest {
    private SessionMemory memory;
    private ConversationManager cm;
    private MemoryUpdateListener listener;
    @BeforeEach
    void setUp() {
        memory = new SessionMemory();
        cm = new ConversationManager(memory, new TokenBudget());
        listener = new MemoryUpdateListener(cm);
    }
    @Test void okResultIsRecordedInMemory() {
        listener.onTurnComplete(tr(new Result.Ok("Hello!"), 1), "hi");
        assertEquals(1, cm.turnCount());
        assertEquals("Hello!", cm.buildHistory().get(1).content());
    }
    @Test void streamedResultIsRecordedInMemory() {
        listener.onTurnComplete(tr(new Result.Streamed("streamed answer", "[Sources]"), 1), "explain X");
        assertEquals(1, cm.turnCount());
        assertEquals("streamed answer", cm.buildHistory().get(1).content());
    }
    @Test void streamedWithEmptySuffixIsRecorded() {
        listener.onTurnComplete(tr(new Result.Streamed("plain streamed", ""), 1), "hey");
        assertEquals(1, cm.turnCount());
        assertEquals("plain streamed", cm.buildHistory().get(1).content());
    }
    @Test void multiTurnStreamedConversation() {
        listener.onTurnComplete(tr(new Result.Streamed("a1", ""), 1), "q1");
        listener.onTurnComplete(tr(new Result.Streamed("a2", ""), 2), "q2");
        listener.onTurnComplete(tr(new Result.Streamed("a3", ""), 3), "q3");
        assertEquals(3, cm.turnCount());
        List<ChatMessage> h = cm.buildHistory();
        assertEquals(6, h.size());
        assertEquals("q1", h.get(0).content());
        assertEquals("a3", h.get(5).content());
    }
    @Test void mixedStreamedAndOkTurns() {
        listener.onTurnComplete(tr(new Result.Streamed("chat", ""), 1), "hello");
        listener.onTurnComplete(tr(new Result.Ok("rag"), 2), "explain");
        assertEquals(2, cm.turnCount());
    }
    @Test void infoResultIsNotRecorded() {
        listener.onTurnComplete(tr(new Result.Info("rebuilt"), 1), "reindex");
        assertEquals(0, cm.turnCount());
    }
    @Test void trustedInfoIsNotRecorded() {
        listener.onTurnComplete(tr(new Result.TrustedInfo("ws: /home"), 1), "ws");
        assertEquals(0, cm.turnCount());
    }
    @Test void errorResultIsNotRecorded() {
        listener.onTurnComplete(tr(new Result.Error("boom", 500), 1), "crash");
        assertEquals(0, cm.turnCount());
    }
    @Test void tableResultIsNotRecorded() {
        listener.onTurnComplete(tr(new Result.Table("T", List.of("c"), List.of(List.of("r"))), 1), "list");
        assertEquals(0, cm.turnCount());
    }
    @Test void streamLifecycleNotRecorded() {
        listener.onTurnComplete(tr(new Result.StreamStart(""), 1), "a");
        listener.onTurnComplete(tr(new Result.StreamChunk("x"), 2), "b");
        listener.onTurnComplete(tr(new Result.StreamEnd(), 3), "c");
        assertEquals(0, cm.turnCount());
    }
    @Test void nullResultIsIgnored() {
        listener.onTurnComplete(null, "hello");
        assertEquals(0, cm.turnCount());
    }
    @Test void nullUserInputIsIgnored() {
        listener.onTurnComplete(tr(new Result.Ok("a"), 1), null);
        assertEquals(0, cm.turnCount());
    }
    @Test void blankUserInputIsIgnored() {
        listener.onTurnComplete(tr(new Result.Ok("a"), 1), "   ");
        assertEquals(0, cm.turnCount());
    }
    @Test void blankAnswerIsNotRecorded() {
        listener.onTurnComplete(tr(new Result.Ok("   "), 1), "hello");
        assertEquals(0, cm.turnCount());
    }
    @Test void emptyStreamedFullTextIsNotRecorded() {
        listener.onTurnComplete(tr(new Result.Streamed("", "[Sources]"), 1), "q");
        assertEquals(0, cm.turnCount());
    }
    @Test void extractTextFromNull() {
        assertNull(MemoryUpdateListener.extractText(null));
    }
    @Test void extractTextFromOk() {
        assertEquals("hello", MemoryUpdateListener.extractText(new Result.Ok("hello")));
    }
    @Test void extractTextFromStreamed() {
        assertEquals("body", MemoryUpdateListener.extractText(new Result.Streamed("body", "[S]")));
    }

    // ---- BUG #1: UI chrome must not leak into conversation history ----

    @Test void stripUiChromeRemovesUsedToolsLine() {
        String in = "Here is your answer.\n[Used 2 tool(s): talos.read_file | 2 iteration(s)]";
        assertEquals("Here is your answer.",
                MemoryUpdateListener.stripUiChromeForHistory(in));
    }

    @Test void stripUiChromeRemovesEditedAndWroteMarkers() {
        String in = "Done.\n✓ Edited foo.txt: replaced 1 line(s)\n✓ Wrote bar.txt\n✓ Created baz/";
        assertEquals("Done.", MemoryUpdateListener.stripUiChromeForHistory(in));
    }

    @Test void stripUiChromeRemovesIterationAndAbortMarkers() {
        String in = "Result.\n[Tool-call limit reached after 8]\n[turn aborted]\n[iteration limit hit]";
        assertEquals("Result.", MemoryUpdateListener.stripUiChromeForHistory(in));
    }

    @Test void stripUiChromeRemovesEngineAndModelErrors() {
        String in = "[Engine error during tool loop: boom]\n[Model 'qwen3:8b' not found. Run: ollama pull qwen3:8b]";
        assertEquals("", MemoryUpdateListener.stripUiChromeForHistory(in));
    }

    @Test void stripUiChromePreservesProseWithBrackets() {
        String in = "The config uses [brackets] in its DSL - that is fine.";
        assertEquals(in, MemoryUpdateListener.stripUiChromeForHistory(in));
    }

    @Test void stripUiChromeReturnsEmptyOnNullOrBlank() {
        assertEquals("", MemoryUpdateListener.stripUiChromeForHistory(null));
        assertEquals("", MemoryUpdateListener.stripUiChromeForHistory("   \n\n  "));
    }

    @Test void chromeOnlyAnswerIsNotRecordedInHistory() {
        // Real transcript pattern: model emits ONLY UI chrome (fabricated).
        // After stripping it would be blank - must not pollute history.
        String chromeOnly = "[Used 2 tool(s): talos.edit_file | 4 iteration(s)]\n✓ Edited index.html: replaced 1 line(s)";
        listener.onTurnComplete(tr(new Result.Streamed(chromeOnly, ""), 1), "edit it");
        assertEquals(0, cm.turnCount(), "chrome-only answer must not be recorded");
    }

    @Test void prosePlusChromeKeepsOnlyProseInHistory() {
        String mixed = "I updated the title.\n[Used 1 tool(s): talos.edit_file | 1 iteration(s)]\n✓ Edited horror-synth-site/index.html: replaced 1 line(s)";
        listener.onTurnComplete(tr(new Result.Streamed(mixed, ""), 1), "rename title");
        assertEquals(1, cm.turnCount());
        assertEquals("I updated the title.", cm.buildHistory().get(1).content());
    }

    @Test void approvedProtectedContentIsRedactedBeforeHistoryPersistence() {
        String answer = """
                The `.env` file contains:

                ```
                TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak
                ```

                This indicates that the environment variable `TALOS_T61E_LLAMA_CPP_SECRET` is set to `must-not-leak`.
                """;

        listener.onTurnComplete(tr(new Result.Streamed(answer, ""), 1),
                "Read .env and tell me the value inside.");

        assertEquals(1, cm.turnCount());
        String stored = cm.buildHistory().get(1).content();
        assertFalse(stored.contains("TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak"), stored);
        assertFalse(stored.contains("must-not-leak"), stored);
        assertTrue(stored.contains("TALOS_T61E_LLAMA_CPP_SECRET=[redacted]"), stored);
    }

    @Test void standaloneProtectedValueAnswerIsRedactedBeforeHistoryPersistence() {
        listener.onTurnComplete(tr(new Result.Streamed("The value is: `must-not-leak`.", ""), 1),
                "Read .env and tell me the value inside.");

        assertEquals(1, cm.turnCount());
        String stored = cm.buildHistory().get(1).content();
        assertFalse(stored.contains("must-not-leak"), stored);
        assertTrue(stored.contains("protected read answer redacted"), stored);
    }

    @Test void privateDocumentFactCanariesAreRedactedBeforeHistoryPersistence() {
        listener.onTurnComplete(tr(new Result.Streamed("""
                I extracted the PDF locally.
                Patient Name: Eleni Nikolaou
                Diagnosis: fictional-condition-alpha
                """, ""), 1), "Read private-medical.pdf");

        assertEquals(1, cm.turnCount());
        String stored = cm.buildHistory().get(1).content();
        assertFalse(stored.contains("Eleni Nikolaou"), stored);
        assertFalse(stored.contains("fictional-condition-alpha"), stored);
        assertTrue(stored.contains("[redacted-private-document-canary]"), stored);
    }

    @Test void privateDocumentReadAnswersAreRedactedBeforeHistoryPersistenceByFormatProvenance() {
        listener.onTurnComplete(tr(new Result.Streamed("""
                The DOCX says:
                Patient code: docx-handoff-ok
                Follow-up: call on Tuesday.
                """, ""), 1), "Read medical-notes.docx and summarize it.");

        assertEquals(1, cm.turnCount());
        String stored = cm.buildHistory().get(1).content();
        assertFalse(stored.contains("docx-handoff-ok"), stored);
        assertFalse(stored.contains("call on Tuesday"), stored);
        assertTrue(stored.contains("private document answer redacted"), stored);
    }

    @Test void refusalStyleReplyIsNotRecordedInHistory() {
        String refusal = "I apologize for the confusion earlier. I am an AI text-based assistant and cannot directly edit files on your system.";
        listener.onTurnComplete(tr(new Result.Streamed(refusal, ""), 1), "please edit it");
        assertEquals(0, cm.turnCount());
    }

    @Test void memoryAwareListenerRecordsToolEvidenceForLaterMetaEvidenceAnswers() {
        var evidenceMemory = new SessionMemory();
        var evidenceConversation = new ConversationManager(evidenceMemory, new TokenBudget());
        var evidenceListener = new MemoryUpdateListener(evidenceConversation, null, evidenceMemory);
        var audit = new TurnAudit(
                List.of(new TurnRecord.ToolCallSummary("talos.read_file", "notes.md", true)),
                0,
                0,
                0);

        evidenceListener.onTurnComplete(
                new TurnResult(new Result.Ok("Read notes.md."), null, 4, Duration.ofMillis(50), audit),
                "Read notes.md");

        assertEquals(1, evidenceMemory.toolEvidence().size());
        SessionMemory.ToolEvidence evidence = evidenceMemory.toolEvidence().getFirst();
        assertEquals(4, evidence.turnNumber());
        assertEquals("talos.read_file", evidence.toolName());
        assertEquals("notes.md", evidence.pathHint());
        assertTrue(evidence.success());
    }

    private static TurnResult tr(Result r, int turn) {
        return new TurnResult(r, null, turn, Duration.ofMillis(50));
    }
}
