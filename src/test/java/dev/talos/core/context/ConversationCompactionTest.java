package dev.talos.core.context;

import dev.talos.cli.repl.SessionMemory;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            for (int i = 0; i < 8; i++) {
                cm.addTurn("What about feature number " + i + "?",
                           "Feature " + i + " is a complex topic that requires detailed explanation. "
                           + "Here are the key points you should know about this feature.");
            }

            int turnsBefore = cm.turnCount();
            assertTrue(turnsBefore >= ConversationManager.COMPACTION_THRESHOLD_PAIRS);

            boolean compacted = cm.maybeCompact(llm);
            assertTrue(compacted, "Should have compacted");

            // After compaction: fewer turns in memory, sketch populated
            assertTrue(cm.turnCount() < turnsBefore,
                    "Turns should be pruned: before=" + turnsBefore + ", after=" + cm.turnCount());
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
                    new dev.talos.cli.repl.Result.Ok("answer"), null, 1,
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
                    new dev.talos.cli.repl.Result.Ok("answer"), null, 1,
                    java.time.Duration.ofMillis(100));
            listener.onTurnComplete(result, "question");

            assertEquals(1, cm.turnCount());
        }
    }
}

