package dev.talos.core.context;

import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnRecord;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for conversation compaction: {@link ConversationCompactor},
 * {@link ConversationManager} compaction lifecycle, and
 * {@link SessionMemory#pruneOldest(int)}.
 */
class ConversationCompactionTest {

    private static Config placeholderConfig() {
        Config cfg = new Config();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "placeholder");
        llm.put("default_backend", "ollama");
        cfg.data.put("llm", llm);
        return cfg;
    }

    private static void addOverflowingTurns(ConversationManager cm) {
        for (int i = 0; i < 8; i++) {
            cm.addTurn("What about feature number " + i + "?",
                    "Feature " + i + " is a complex topic that requires detailed explanation. "
                            + "Here are the key points you should know about this feature.");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ConversationCompactor
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class CompactorTests {

        @Test
        void compact_nullTurns_returnsExistingSketch() {
            LlmClient llm = new LlmClient(placeholderConfig());
            String result = ConversationCompactor.compact("old sketch", null, llm);
            assertEquals("old sketch", result);
        }

        @Test
        void compact_emptyTurns_returnsExistingSketch() {
            LlmClient llm = new LlmClient(placeholderConfig());
            String result = ConversationCompactor.compact("old sketch", List.of(), llm);
            assertEquals("old sketch", result);
        }

        @Test
        void compact_withTurns_returnsNewSketch() {
            // Explicit placeholder transport keeps this compaction test deterministic.
            LlmClient llm = new LlmClient(placeholderConfig());
            List<ChatMessage> turns = List.of(
                    ChatMessage.user("What is Talos?"),
                    ChatMessage.assistant("Talos is a local-first workspace assistant.")
            );
            String result = ConversationCompactor.compact(null, turns, llm);
            // PLACEHOLDER mode returns something — exact text depends on implementation
            // but it should not be null, not be empty, and should be different from null
            assertNotNull(result);
        }

        @Test
        void compact_nullLlm_throws() {
            assertThrows(NullPointerException.class, () ->
                    ConversationCompactor.compact(null, List.of(), null));
        }

        @Test
        void tryCompact_blankOutput_reportsFailureAndPreservesExistingSketch() {
            LlmClient llm = ScriptedNativeLlmClient.of(List.of(new LlmClient.StreamResult("", List.of())));
            List<ChatMessage> turns = List.of(
                    ChatMessage.user("Keep this exact fact"),
                    ChatMessage.assistant("The exact fact is still active.")
            );

            ConversationCompactor.CompactionResult result =
                    ConversationCompactor.tryCompact("prior sketch", turns, llm);

            assertFalse(result.succeeded());
            assertEquals("prior sketch", result.sketch());
            assertEquals("empty-output", result.reason());
            assertEquals(ConversationCompactor.CompactionResult.Category.BLANK_OUTPUT, result.category());
        }

        @Test
        void tryCompact_redactsSecretLikeSketchBeforeReturningSuccess() {
            LlmClient llm = ScriptedNativeLlmClient.of(List.of(new LlmClient.StreamResult(
                    "User approved reading .env. TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak. "
                            + "Keep talos.read_file evidence.",
                    List.of())));
            List<ChatMessage> turns = List.of(
                    ChatMessage.user("Read .env after approval."),
                    ChatMessage.assistant("The approved file says TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak.")
            );

            ConversationCompactor.CompactionResult result =
                    ConversationCompactor.tryCompact("prior sketch", turns, llm);

            assertTrue(result.succeeded());
            assertFalse(result.sketch().contains("must-not-leak"), result.sketch());
            assertTrue(result.sketch().contains("TALOS_T61E_LLAMA_CPP_SECRET=[redacted]"), result.sketch());
        }

        @Test
        void tryCompact_redactsPrivateDocumentFactsBeforeReturningSuccess() {
            LlmClient llm = ScriptedNativeLlmClient.of(List.of(new LlmClient.StreamResult(
                    "Private document evidence mentioned Patient Name: Eleni Nikolaou and ordinary fact Aster-7.",
                    List.of())));
            List<ChatMessage> turns = List.of(
                    ChatMessage.user("Read private-medical.pdf"),
                    ChatMessage.assistant("Patient Name: Eleni Nikolaou; ordinary fact Aster-7.")
            );

            ConversationCompactor.CompactionResult result =
                    ConversationCompactor.tryCompact("prior sketch", turns, llm);

            assertTrue(result.succeeded());
            assertFalse(result.sketch().contains("Eleni Nikolaou"), result.sketch());
            assertTrue(result.sketch().contains("[redacted-private-document-canary]"), result.sketch());
            assertTrue(result.sketch().contains("Aster-7"), result.sketch());
        }

        @Test
        void tryCompact_rejectsTrivialSketchForSubstantiveTurns() {
            LlmClient llm = ScriptedNativeLlmClient.of(List.of(new LlmClient.StreamResult("summary omitted", List.of())));
            List<ChatMessage> turns = List.of(
                    ChatMessage.user("Create index.html and style.css for Retrocats."),
                    ChatMessage.assistant("Verification failed for index.html because script.js was missing.")
            );

            ConversationCompactor.CompactionResult result =
                    ConversationCompactor.tryCompact("prior sketch", turns, llm);

            assertFalse(result.succeeded());
            assertEquals("prior sketch", result.sketch());
            assertTrue(result.reason().contains("trivial"), result.reason());
            assertEquals(ConversationCompactor.CompactionResult.Category.INTEGRITY_REJECT, result.category());
        }

        @Test
        void tryCompact_rejectsSketchThatDropsAllCriticalEvidenceAnchors() {
            LlmClient llm = ScriptedNativeLlmClient.of(List.of(new LlmClient.StreamResult(
                    "The user was working on the project.",
                    List.of())));
            List<ChatMessage> turns = List.of(
                    ChatMessage.user("Use talos.write_file to update index.html."),
                    ChatMessage.assistant("Verification failed for index.html after checkpoint chk-123.")
            );

            ConversationCompactor.CompactionResult result =
                    ConversationCompactor.tryCompact("prior sketch", turns, llm);

            assertFalse(result.succeeded());
            assertEquals("prior sketch", result.sketch());
            assertTrue(result.reason().contains("critical-evidence"), result.reason());
            assertEquals(ConversationCompactor.CompactionResult.Category.INTEGRITY_REJECT, result.category());
        }

        @Test
        void tryCompact_acceptsSketchThatPreservesCriticalEvidenceAnchors() {
            LlmClient llm = ScriptedNativeLlmClient.of(List.of(new LlmClient.StreamResult(
                    "User was editing index.html with talos.write_file; verification failed after checkpoint chk-123.",
                    List.of())));
            List<ChatMessage> turns = List.of(
                    ChatMessage.user("Use talos.write_file to update index.html."),
                    ChatMessage.assistant("Verification failed for index.html after checkpoint chk-123.")
            );

            ConversationCompactor.CompactionResult result =
                    ConversationCompactor.tryCompact("prior sketch", turns, llm);

            assertTrue(result.succeeded(), result.reason());
            assertTrue(result.sketch().contains("index.html"));
            assertTrue(result.sketch().contains("talos.write_file"));
            assertTrue(result.sketch().contains("verification failed"));
            assertEquals(ConversationCompactor.CompactionResult.Category.SUCCESS, result.category());
        }

        @Test
        void buildCompactionPrompt_withSketch() {
            String prompt = ConversationCompactor.buildCompactionPrompt(
                    "Prior: user building a CLI tool",
                    List.of(
                            ChatMessage.user("Add tests"),
                            ChatMessage.assistant("I added 10 tests to FooTest.java")
                    )
            );
            assertTrue(prompt.contains("Prior summary:"));
            assertTrue(prompt.contains("Prior: user building a CLI tool"));
            assertTrue(prompt.contains("Add tests"));
            assertTrue(prompt.contains("FooTest.java"));
        }

        @Test
        void buildCompactionPrompt_redactsProtectedContentBeforeSendingToLlm() {
            String prompt = ConversationCompactor.buildCompactionPrompt(
                    "Prior TOKEN=old-secret",
                    List.of(
                            ChatMessage.user("My API_KEY=abc12345 should not be copied."),
                            ChatMessage.assistant("Private document fact: Eleni Nikolaou.")));

            assertFalse(prompt.contains("old-secret"), prompt);
            assertFalse(prompt.contains("abc12345"), prompt);
            assertFalse(prompt.contains("Eleni Nikolaou"), prompt);
            assertTrue(prompt.contains("TOKEN=[redacted]"), prompt);
            assertTrue(prompt.contains("API_KEY=[redacted]"), prompt);
            assertTrue(prompt.contains("[redacted-private-document-canary]"), prompt);
        }

        @Test
        void buildCompactionPrompt_withoutSketch() {
            String prompt = ConversationCompactor.buildCompactionPrompt(
                    null,
                    List.of(ChatMessage.user("hello"), ChatMessage.assistant("hi"))
            );
            assertFalse(prompt.contains("Prior summary:"));
            assertTrue(prompt.contains("hello"));
        }

        @Test
        void buildCompactionPrompt_truncatesLongMessages() {
            String longMessage = "x".repeat(5000);
            String prompt = ConversationCompactor.buildCompactionPrompt(
                    null,
                    List.of(ChatMessage.user(longMessage))
            );
            // Individual messages are truncated to 2000 chars + "…"
            assertTrue(prompt.length() < longMessage.length());
        }

        @Test
        void buildCompactionPrompt_capsTotal() {
            // Build a huge prompt that exceeds MAX_INPUT_CHARS
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("x".repeat(200));
            }
            List<ChatMessage> turns = List.of(ChatMessage.user(sb.toString()));
            String prompt = ConversationCompactor.buildCompactionPrompt(null, turns);
            assertTrue(prompt.length() <= ConversationCompactor.MAX_INPUT_CHARS);
        }

        @Test
        void systemPrompt_isReasonableLength() {
            // Compaction system prompt should be concise but can be detailed
            assertTrue(ConversationCompactor.COMPACTION_SYSTEM_PROMPT.length() < 1500);
            assertTrue(ConversationCompactor.COMPACTION_SYSTEM_PROMPT.contains("summarizer"));
        }

        @Test
        void maxSketchChars_isReasonable() {
            // 2000 chars allows enough detail for creative artifact summaries
            assertEquals(2_000, ConversationCompactor.MAX_SKETCH_CHARS);
        }

        @Test
        void conversationCompactorDoesNotDependOnRuntimeLogPolicy() throws Exception {
            String source = Files.readString(Path.of(
                    "src/main/java/dev/talos/core/context/ConversationCompactor.java"));
            String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

            assertFalse(source.contains("dev.talos.runtime.policy.SafeLogFormatter"), source);
            assertFalse(baseline.contains(
                    "src/main/java/dev/talos/core/context/ConversationCompactor.java"
                            + "|dev.talos.runtime.policy.SafeLogFormatter"), baseline);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SessionMemory.pruneOldest
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class PruneOldestTests {

        @Test
        void pruneOldest_removesFromFront() {
            SessionMemory mem = new SessionMemory();
            mem.update("q1", "a1");
            mem.update("q2", "a2");
            mem.update("q3", "a3");
            assertEquals(6, mem.getTurns().size());

            mem.pruneOldest(2); // remove first pair (q1/a1)
            List<ChatMessage> remaining = mem.getTurns();
            assertEquals(4, remaining.size());
            assertEquals("q2", remaining.get(0).content());
            assertEquals("a2", remaining.get(1).content());
        }

        @Test
        void pruneOldest_zero_noOp() {
            SessionMemory mem = new SessionMemory();
            mem.update("q1", "a1");
            mem.pruneOldest(0);
            assertEquals(2, mem.getTurns().size());
        }

        @Test
        void pruneOldest_moreThanAvailable_clearsAll() {
            SessionMemory mem = new SessionMemory();
            mem.update("q1", "a1");
            mem.pruneOldest(100);
            assertTrue(mem.getTurns().isEmpty());
            assertNull(mem.get()); // flat buffer cleared
        }

        @Test
        void pruneOldest_rebuildsBuffer() {
            SessionMemory mem = new SessionMemory();
            mem.update("q1", "a1");
            mem.update("q2", "a2");

            mem.pruneOldest(2); // remove first pair
            String buffer = mem.get();
            assertNotNull(buffer);
            assertFalse(buffer.contains("q1"));
            assertTrue(buffer.contains("q2"));
        }

        @Test
        void pruneOldest_preservesStructuredToolEvidence() {
            SessionMemory mem = new SessionMemory();
            mem.update("q1", "a1");
            mem.update("q2", "a2");
            mem.recordToolEvidence(1, List.of(new TurnRecord.ToolCallSummary("talos.write_file", "index.html", true)));

            mem.pruneOldest(2);

            assertEquals(1, mem.toolEvidence().size());
            SessionMemory.ToolEvidence evidence = mem.toolEvidence().getFirst();
            assertEquals("talos.write_file", evidence.toolName());
            assertEquals("index.html", evidence.pathHint());
        }

        @Test
        void pruneOldest_allRemoved_bufferNull() {
            SessionMemory mem = new SessionMemory();
            mem.update("q1", "a1");
            mem.pruneOldest(2);
            assertNull(mem.get());
            assertFalse(mem.hasContent());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ConversationManager compaction integration
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class CompactionIntegrationTests {

        @Test
        void maybeCompact_belowThreshold_returnsFalse() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(8192));
            LlmClient llm = new LlmClient(placeholderConfig());

            // Add fewer than COMPACTION_THRESHOLD_PAIRS
            for (int i = 0; i < ConversationManager.COMPACTION_THRESHOLD_PAIRS - 1; i++) {
                cm.addTurn("q" + i, "a" + i);
            }

            assertFalse(cm.maybeCompact(llm));
        }

        @Test
        void maybeCompact_nullLlm_returnsFalse() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(8192));
            assertFalse(cm.maybeCompact(null));
        }

        @Test
        void maybeCompact_fitsInBudget_returnsFalse() {
            // Use a large budget so everything fits
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(1_000_000));
            LlmClient llm = new LlmClient(placeholderConfig());

            for (int i = 0; i < 10; i++) {
                cm.addTurn("short q" + i, "short a" + i);
            }

            // With 1M token budget, 25% = 250K tokens — 10 short turns easily fit
            assertFalse(cm.maybeCompact(llm));
        }

        @Test
        void maybeCompact_overBudget_compactsAndPrunes() {
            // Use a very small budget so history overflows quickly
            SessionMemory mem = new SessionMemory();
            TokenBudget tinyBudget = new TokenBudget(200); // ~200 tokens = 800 chars total, 25% = 50 tokens = 200 chars for history
            ConversationManager cm = new ConversationManager(mem, tinyBudget);
            LlmClient llm = new LlmClient(placeholderConfig());

            // Add enough turns to overflow: 6+ pairs with decent-length content
            addOverflowingTurns(cm);

            int turnsBefore = cm.turnCount();
            assertTrue(turnsBefore >= ConversationManager.COMPACTION_THRESHOLD_PAIRS);

            boolean compacted = cm.maybeCompact(llm);
            assertTrue(compacted, "Should have compacted");

            // After compaction: fewer turns in memory, sketch populated
            assertTrue(cm.turnCount() < turnsBefore,
                    "Turns should be pruned: before=" + turnsBefore + ", after=" + cm.turnCount());
        }

        /**
         * T797 pin: the failure breaker opens after three consecutive
         * failures, the skip status renders this exact operational string,
         * and a success resets the counter. T798 builds compactNow on the
         * same internals (the forced path bypasses the OPEN breaker by
         * explicit user intent, but its failures still count).
         */
        @Test
        void t797_failureBreakerOpensAfterThreeAndRendersTheExactSkipString() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            addOverflowingTurns(cm);

            for (int i = 0; i < ConversationManager.MAX_CONSECUTIVE_COMPACTION_FAILURES; i++) {
                assertFalse(cm.maybeCompactWith(
                        (sketch, oldTurns) ->
                                ConversationCompactor.CompactionResult.failed(sketch, "llm-down"),
                        ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                        ConversationManager.HISTORY_BUDGET_FRACTION));
            }

            // Breaker open: the next attempt skips without calling the compactor.
            assertFalse(cm.maybeCompactWith(
                    (sketch, oldTurns) -> {
                        throw new AssertionError("breaker-open attempts must not reach the compactor");
                    },
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION));
            assertEquals(
                    "status=SKIPPED category=SKIPPED reason=failure-breaker-open"
                            + " failures=3 oldTurns=0 preservedTail=16 integrity=NOT_DERIVED",
                    cm.lastCompactionStatus().renderCompact(),
                    "the operational skip string is pinned exactly - /context renders it");
        }

        /** T797 pin: today compaction is silent — it exposes status, no event. */
        @Test
        void t797_compactionSetsStatusOnly_noUserVisibleSignalExistsYet() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            addOverflowingTurns(cm);

            boolean compacted = cm.maybeCompactWith(
                    (sketch, oldTurns) -> ConversationCompactor.CompactionResult.succeeded("S"),
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION);

            assertTrue(compacted);
            assertEquals("SUCCEEDED", cm.lastCompactionStatus().status());
            // T798 deliberately adds pollCompactionEvent() + the render-side
            // notice (T805); until then the only observable is this status.
        }

        @Test
        void maybeCompact_failedCompactionPreservesTurnsAndSketch() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            cm.setSketch("prior sketch");
            addOverflowingTurns(cm);
            List<ChatMessage> turnsBefore = mem.getTurns();

            boolean compacted = cm.maybeCompactWith(
                    (existingSketch, oldTurns) ->
                            ConversationCompactor.CompactionResult.failed(existingSketch, "thrown"),
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION);

            assertFalse(compacted);
            assertEquals("prior sketch", cm.sketch());
            assertEquals(turnsBefore, mem.getTurns());
        }

        @Test
        void maybeCompact_thrownCompactionPreservesTurnsAndSketch() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            cm.setSketch("prior sketch");
            addOverflowingTurns(cm);
            List<ChatMessage> turnsBefore = mem.getTurns();

            boolean compacted = cm.maybeCompactWith((existingSketch, oldTurns) -> {
                        throw new IllegalStateException("compactor failed");
                    },
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION);

            assertFalse(compacted);
            assertEquals("prior sketch", cm.sketch());
            assertEquals(turnsBefore, mem.getTurns());
        }

        @Test
        void maybeCompact_blankCompactionOutputPreservesTurnsAndSketch() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            cm.setSketch("prior sketch");
            addOverflowingTurns(cm);
            List<ChatMessage> turnsBefore = mem.getTurns();
            LlmClient llm = ScriptedNativeLlmClient.of(List.of(new LlmClient.StreamResult("", List.of())));

            assertFalse(cm.maybeCompact(llm));

            assertEquals("prior sketch", cm.sketch());
            assertEquals(turnsBefore, mem.getTurns());
        }

        @Test
        void maybeCompact_successPrunesExactlySummarizedOldTurnSnapshot() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            addOverflowingTurns(cm);
            int turnsBefore = mem.getTurns().size();
            AtomicInteger summarizedTurns = new AtomicInteger();

            boolean compacted = cm.maybeCompactWith((existingSketch, oldTurns) -> {
                        summarizedTurns.set(oldTurns.size());
                        return ConversationCompactor.CompactionResult.succeeded("new sketch");
                    },
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION);

            assertTrue(compacted);
            assertEquals("new sketch", cm.sketch());
            assertTrue(summarizedTurns.get() > 0);
            assertEquals(turnsBefore - summarizedTurns.get(), mem.getTurns().size());
        }

        @Test
        void maybeCompact_successKeepsRecentTailVerbatim() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            addOverflowingTurns(cm);
            List<ChatMessage> before = mem.getTurns();
            List<ChatMessage> expectedTail = before.subList(before.size() - 2, before.size());

            boolean compacted = cm.maybeCompactWith(
                    (existingSketch, oldTurns) -> ConversationCompactor.CompactionResult.succeeded(
                            "Summarized old turns while retaining recent tail."),
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION);

            assertTrue(compacted);
            List<ChatMessage> after = mem.getTurns();
            assertEquals(expectedTail, after.subList(after.size() - 2, after.size()));
        }

        @Test
        void maybeCompact_passesOnlyCompleteUserAssistantPairsToCompactor() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            addOverflowingTurns(cm);
            AtomicReference<List<ChatMessage>> summarized = new AtomicReference<>();

            boolean compacted = cm.maybeCompactWith((existingSketch, oldTurns) -> {
                        summarized.set(oldTurns);
                        return ConversationCompactor.CompactionResult.succeeded("summary with complete pairs");
                    },
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION);

            assertTrue(compacted);
            List<ChatMessage> oldTurns = summarized.get();
            assertNotNull(oldTurns);
            assertFalse(oldTurns.isEmpty());
            assertEquals(0, oldTurns.size() % 2, "oldTurns must contain whole user/assistant pairs");
            for (int i = 0; i < oldTurns.size(); i += 2) {
                assertEquals("user", oldTurns.get(i).role(), "pair starts with user at index " + i);
                assertEquals("assistant", oldTurns.get(i + 1).role(), "pair ends with assistant at index " + (i + 1));
            }
        }

        @Test
        void maybeCompact_malformedOddHistoryDoesNotCompactOrPrune() {
            OddTurnMemory mem = new OddTurnMemory();
            for (int i = 0; i < 6; i++) {
                mem.update("Question " + i + " with enough content to overflow budget",
                        "Answer " + i + " with enough content to overflow the very small budget quickly.");
            }
            mem.addDanglingUserTurn("Dangling user turn that must not be split");
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            List<ChatMessage> before = mem.getTurns();
            AtomicInteger attempts = new AtomicInteger();

            boolean compacted = cm.maybeCompactWith((existingSketch, oldTurns) -> {
                        attempts.incrementAndGet();
                        return ConversationCompactor.CompactionResult.succeeded("should not happen");
                    },
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION);

            assertFalse(compacted);
            assertEquals(0, attempts.get(), "malformed history should fail before invoking compactor");
            assertEquals(before, mem.getTurns());
        }

        @Test
        void maybeCompact_integrityFailurePreservesTurnsAndSketch() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            cm.setSketch("prior sketch");
            addOverflowingTurns(cm);
            List<ChatMessage> before = mem.getTurns();
            LlmClient llm = ScriptedNativeLlmClient.of(List.of(new LlmClient.StreamResult("no context", List.of())));

            assertFalse(cm.maybeCompact(llm));

            assertEquals("prior sketch", cm.sketch());
            assertEquals(before, mem.getTurns());
        }

        @Test
        void maybeCompact_integrityRejectsDoNotTripLlmFailureBreaker() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            addOverflowingTurns(cm);
            AtomicInteger attempts = new AtomicInteger();

            for (int i = 0; i < 4; i++) {
                assertFalse(cm.maybeCompactWith((existingSketch, oldTurns) -> {
                            attempts.incrementAndGet();
                            return ConversationCompactor.CompactionResult.integrityRejected(
                                    existingSketch, "critical-evidence-missing:index.html");
                        },
                        ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                        ConversationManager.HISTORY_BUDGET_FRACTION));
            }

            assertTrue(cm.maybeCompactWith((existingSketch, oldTurns) -> {
                        attempts.incrementAndGet();
                        return ConversationCompactor.CompactionResult.succeeded("recovered sketch");
                    },
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION));

            assertEquals(5, attempts.get(), "integrity rejects should not consume the LLM failure breaker");
            assertEquals("recovered sketch", cm.sketch());
        }

        @Test
        void maybeCompact_exposesLastCompactionStatusForPromptAudit() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            addOverflowingTurns(cm);

            assertFalse(cm.lastCompactionStatus().attempted());
            assertEquals("NEVER_ATTEMPTED", cm.lastCompactionStatus().status());

            assertFalse(cm.maybeCompactWith((existingSketch, oldTurns) ->
                            ConversationCompactor.CompactionResult.integrityRejected(
                                    existingSketch, "critical-evidence-missing:index.html"),
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION));

            ConversationCompactionStatus rejected = cm.lastCompactionStatus();
            assertTrue(rejected.attempted());
            assertEquals("FAILED", rejected.status());
            assertEquals("INTEGRITY_REJECT", rejected.category());
            assertEquals("critical-evidence-missing:index.html", rejected.reason());
            assertEquals("REJECTED", rejected.integrityStatus());
            assertEquals(0, rejected.consecutiveFailureCount(),
                    "integrity reject should not increment the LLM/output failure count");
            assertTrue(rejected.summarizedTurnCount() > 0);
            assertTrue(rejected.preservedTailTurnCount() > 0);

            assertTrue(cm.maybeCompactWith((existingSketch, oldTurns) ->
                            ConversationCompactor.CompactionResult.succeeded("recovered sketch"),
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION));

            ConversationCompactionStatus succeeded = cm.lastCompactionStatus();
            assertEquals("SUCCEEDED", succeeded.status());
            assertEquals("SUCCESS", succeeded.category());
            assertEquals("ACCEPTED", succeeded.integrityStatus());
            assertEquals(0, succeeded.consecutiveFailureCount());
        }

        @Test
        void maybeCompact_threeConsecutiveFailuresTripBreakerForSession() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            addOverflowingTurns(cm);
            AtomicInteger attempts = new AtomicInteger();

            for (int i = 0; i < 4; i++) {
                assertFalse(cm.maybeCompactWith((existingSketch, oldTurns) -> {
                            attempts.incrementAndGet();
                            return ConversationCompactor.CompactionResult.failed(existingSketch, "test-failure");
                        },
                        ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                        ConversationManager.HISTORY_BUDGET_FRACTION));
            }

            assertEquals(3, attempts.get(), "fourth call should be skipped by the breaker");
        }

        @Test
        void maybeCompact_successResetsFailureBreaker() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            addOverflowingTurns(cm);
            AtomicInteger attempts = new AtomicInteger();

            for (int i = 0; i < 2; i++) {
                assertFalse(cm.maybeCompactWith((existingSketch, oldTurns) -> {
                            attempts.incrementAndGet();
                            return ConversationCompactor.CompactionResult.failed(existingSketch, "test-failure");
                        },
                        ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                        ConversationManager.HISTORY_BUDGET_FRACTION));
            }

            assertTrue(cm.maybeCompactWith((existingSketch, oldTurns) -> {
                        attempts.incrementAndGet();
                        return ConversationCompactor.CompactionResult.succeeded("reset sketch");
                    },
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION));

            addOverflowingTurns(cm);
            assertFalse(cm.maybeCompactWith((existingSketch, oldTurns) -> {
                        attempts.incrementAndGet();
                        return ConversationCompactor.CompactionResult.failed(existingSketch, "after-reset");
                    },
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION));

            assertEquals(4, attempts.get(), "failure after success should still invoke compaction");
        }

        @Test
        void clear_resetsCompactionFailureBreaker() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(200));
            addOverflowingTurns(cm);
            AtomicInteger attempts = new AtomicInteger();

            for (int i = 0; i < 3; i++) {
                assertFalse(cm.maybeCompactWith((existingSketch, oldTurns) -> {
                            attempts.incrementAndGet();
                            return ConversationCompactor.CompactionResult.failed(existingSketch, "test-failure");
                        },
                        ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                        ConversationManager.HISTORY_BUDGET_FRACTION));
            }

            cm.clear();
            addOverflowingTurns(cm);

            assertTrue(cm.maybeCompactWith((existingSketch, oldTurns) -> {
                        attempts.incrementAndGet();
                        return ConversationCompactor.CompactionResult.succeeded("after clear");
                    },
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION));

            assertEquals(4, attempts.get(), "clear should reset the breaker for this session");
        }

        @Test
        void buildHistory_includesSketch() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(8192));

            // Set a sketch directly
            cm.setSketch("User is building a CLI tool called Talos.");

            // Add one turn
            cm.addTurn("Add tests", "Done, added 5 tests.");

            List<ChatMessage> history = cm.buildHistory(2000);
            assertFalse(history.isEmpty());

            // First message should be the sketch
            ChatMessage first = history.getFirst();
            assertTrue(first.content().contains("Conversation context"),
                    "First message should contain sketch prefix");
            assertTrue(first.content().contains("Talos"),
                    "Sketch content should be preserved");

            // Should also contain the recent turn
            boolean hasRecentUser = history.stream()
                    .anyMatch(m -> "user".equals(m.role()) && m.content().contains("Add tests"));
            assertTrue(hasRecentUser, "Recent turns should be included");
        }

        @Test
        void buildHistory_noSketch_noPrefix() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(8192));

            cm.addTurn("hello", "hi there");

            List<ChatMessage> history = cm.buildHistory(2000);
            // No sketch → no sketch message
            boolean hasSketch = history.stream()
                    .anyMatch(m -> m.content().contains("Conversation context"));
            assertFalse(hasSketch, "No sketch should be present");
        }

        @Test
        void buildHistory_emptyWithSketchOnly() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(8192));
            cm.setSketch("User was asking about architecture.");

            List<ChatMessage> history = cm.buildHistory(2000);
            assertEquals(1, history.size());
            assertTrue(history.getFirst().content().contains("architecture"));
        }

        @Test
        void buildHistory_sketchExceedsbudget_omitted() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(8192));
            cm.setSketch("x".repeat(1000)); // ~250 tokens

            // Budget of 10 tokens — sketch alone exceeds it
            List<ChatMessage> history = cm.buildHistory(10);
            // Sketch is omitted because it doesn't fit
            assertTrue(history.isEmpty() || !history.getFirst().content().contains("Conversation context"));
        }

        @Test
        void clear_resetsSketch() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(8192));
            cm.setSketch("old context");
            cm.addTurn("q", "a");

            cm.clear();

            assertNull(cm.sketch());
            assertFalse(cm.hasHistory());
        }

        @Test
        void hasHistory_trueWithSketchOnly() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(8192));
            assertFalse(cm.hasHistory());

            cm.setSketch("some context");
            assertTrue(cm.hasHistory(), "Should return true when sketch exists");
        }

        @Test
        void sketch_getAndSet() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(8192));

            assertNull(cm.sketch());
            cm.setSketch("test sketch");
            assertEquals("test sketch", cm.sketch());
            cm.setSketch(null);
            assertNull(cm.sketch());
        }

        @Test
        void compactionThreshold_isReasonable() {
            assertTrue(ConversationManager.COMPACTION_THRESHOLD_PAIRS >= 4,
                    "Threshold should be at least 4 pairs");
            assertTrue(ConversationManager.COMPACTION_THRESHOLD_PAIRS <= 20,
                    "Threshold should be at most 20 pairs");
        }
    }

    private static final class OddTurnMemory implements ConversationMemory {
        private final List<ChatMessage> turns = new ArrayList<>();

        @Override
        public String get() {
            return turns.isEmpty() ? null : "odd-memory";
        }

        @Override
        public List<ChatMessage> getTurns() {
            return List.copyOf(turns);
        }

        @Override
        public void update(String userInput, String answer) {
            turns.add(ChatMessage.user(userInput));
            turns.add(ChatMessage.assistant(answer));
        }

        void addDanglingUserTurn(String text) {
            turns.add(ChatMessage.user(text));
        }

        @Override
        public void pruneOldest(int count) {
            for (int i = 0; i < count && !turns.isEmpty(); i++) {
                turns.removeFirst();
            }
        }

        @Override
        public boolean hasContent() {
            return !turns.isEmpty();
        }

        @Override
        public void clear() {
            turns.clear();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MemoryUpdateListener compaction wiring
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ListenerCompactionTests {

        @Test
        void listener_withoutLlm_noCompaction() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(8192));
            // No LLM — old constructor
            var listener = new dev.talos.runtime.MemoryUpdateListener(cm);

            var result = new dev.talos.runtime.TurnResult(
                    new dev.talos.runtime.Result.Ok("answer"), null, 1,
                    java.time.Duration.ofMillis(100));
            listener.onTurnComplete(result, "question");

            // Turn should still be recorded
            assertEquals(1, cm.turnCount());
            // But no compaction (no LLM)
            assertNull(cm.sketch());
        }

        @Test
        void listener_withLlm_recordsTurn() {
            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem, new TokenBudget(8192));
            LlmClient llm = new LlmClient(placeholderConfig());
            var listener = new dev.talos.runtime.MemoryUpdateListener(cm, llm);

            var result = new dev.talos.runtime.TurnResult(
                    new dev.talos.runtime.Result.Ok("answer"), null, 1,
                    java.time.Duration.ofMillis(100));
            listener.onTurnComplete(result, "question");

            assertEquals(1, cm.turnCount());
        }
    }
}

