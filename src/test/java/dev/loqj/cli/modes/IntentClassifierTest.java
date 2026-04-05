package dev.loqj.cli.modes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.loqj.cli.modes.IntentClassifier.Intent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IntentClassifier}: verifies that user prompts are
 * correctly classified into CHAT, RAG, DEV, or UNKNOWN intents.
 */
class IntentClassifierTest {

    // ── CHAT: greetings ──────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "hey", "Hey!", "HEY", "hi", "Hi!", "hello", "Hello!",
        "howdy", "yo", "sup", "hiya", "heya", "hola"
    })
    void greetings_classify_as_chat(String input) {
        assertEquals(CHAT, IntentClassifier.classify(input),
                "'" + input + "' should be CHAT");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "good morning", "Good Morning!", "good afternoon",
        "good evening", "good night", "good day"
    })
    void time_greetings_classify_as_chat(String input) {
        assertEquals(CHAT, IntentClassifier.classify(input),
                "'" + input + "' should be CHAT");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "what's up", "whats up", "what's up?", "wassup",
        "how are you", "how are you?", "how's it going"
    })
    void casual_openers_classify_as_chat(String input) {
        assertEquals(CHAT, IntentClassifier.classify(input),
                "'" + input + "' should be CHAT");
    }

    // ── CHAT: thanks / farewell ──────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "thanks", "thank you", "Thanks!", "thx", "ty", "cheers",
        "bye", "goodbye", "see you", "later", "ciao"
    })
    void thanks_and_farewell_classify_as_chat(String input) {
        assertEquals(CHAT, IntentClassifier.classify(input),
                "'" + input + "' should be CHAT");
    }

    // ── CHAT: acknowledgments ────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "got it", "understood", "makes sense", "perfect", "great",
        "awesome", "sounds good", "all good", "noted", "roger",
        "copy", "clear", "fine", "done"
    })
    void acknowledgments_classify_as_chat(String input) {
        assertEquals(CHAT, IntentClassifier.classify(input),
                "'" + input + "' should be CHAT");
    }

    // ── CHAT: short non-technical input ──────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "ok", "okay", "sure", "yes", "yeah", "yep", "nope", "no",
        "lol", "haha", "wow", "oops", "hmm", "ah", "oh",
        "nice", "cool"
    })
    void short_casual_words_classify_as_chat(String input) {
        assertEquals(CHAT, IntentClassifier.classify(input),
                "'" + input + "' should be CHAT");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "who are you", "what are you", "what can you",
        "tell me about yourself", "tell me a joke",
        "help me", "please"
    })
    void meta_questions_classify_as_chat(String input) {
        assertEquals(CHAT, IntentClassifier.classify(input),
                "'" + input + "' should be CHAT");
    }

    // ── CHAT: short ambiguous (≤ 3 words, no code signals) ──────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "hey there", "what now", "hmm okay", "go on",
        "say something", "not sure"
    })
    void short_non_technical_classify_as_chat(String input) {
        assertEquals(CHAT, IntentClassifier.classify(input),
                "'" + input + "' should be CHAT (short, no code signals)");
    }

    // ── RAG: file references ─────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "explain RagService.java",
        "what does Config.yaml do",
        "show me build.gradle.kts",
        "differences between Foo.java and Bar.java",
        "summarize README.md",
        "what is in pom.xml"
    })
    void file_references_classify_as_rag(String input) {
        assertEquals(RAG, IntentClassifier.classify(input),
                "'" + input + "' should be RAG (file reference)");
    }

    // ── RAG: code keywords ───────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "explain the retrieval pipeline",
        "how does the indexing work",
        "what is the RagService class",
        "where is the error handling",
        "find the method that handles embedding",
        "describe the architecture",
        "compare the test and production code",
        "what exceptions can the build throw",
        "show me the configuration settings",
        "how does the rerank stage work",
        "explain the workspace model",
        "what dependencies does this project use"
    })
    void code_keywords_classify_as_rag(String input) {
        assertEquals(RAG, IntentClassifier.classify(input),
                "'" + input + "' should be RAG (code keyword)");
    }

    // ── RAG: questions about codebase ────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "how does the search service work",
        "what does the chunker do",
        "where is the api endpoint defined",
        "why does the test fail",
        "walk me through the build process"
    })
    void codebase_questions_classify_as_rag(String input) {
        assertEquals(RAG, IntentClassifier.classify(input),
                "'" + input + "' should be RAG (codebase question)");
    }

    // ── DEV: file operations ─────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "open src/Main.java",
        "show build.gradle.kts",
        "view README.md",
        "ls src/",
        "list docs",
        "dir src/main"
    })
    void dev_commands_classify_as_dev(String input) {
        assertEquals(DEV, IntentClassifier.classify(input),
                "'" + input + "' should be DEV");
    }

    @Test
    void show_me_with_file_ref_classifies_as_rag_not_dev() {
        // "show me" is natural language, not a DevMode command
        assertEquals(RAG, IntentClassifier.classify("show me build.gradle.kts"));
    }

    // ── UNKNOWN: ambiguous longer input ──────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "what time is it right now",
        "tell me about the weather today please",
        "can you translate this to French for me"
    })
    void ambiguous_longer_input_classifies_as_unknown(String input) {
        assertEquals(UNKNOWN, IntentClassifier.classify(input),
                "'" + input + "' should be UNKNOWN (ambiguous)");
    }

    // ── Edge: mixed signals (file ref + greeting prefix) → RAG wins ─────

    @Test
    void greeting_with_file_ref_classifies_as_rag() {
        assertEquals(RAG, IntentClassifier.classify("hey explain RagService.java"));
    }

    @Test
    void greeting_with_code_keyword_classifies_as_rag() {
        assertEquals(RAG, IntentClassifier.classify("hey what is the retrieval pipeline"));
    }

    // ── Edge: null / blank → UNKNOWN ─────────────────────────────────────

    @Test
    void null_input_classifies_as_unknown() {
        assertEquals(UNKNOWN, IntentClassifier.classify(null));
    }

    @Test
    void blank_input_classifies_as_unknown() {
        assertEquals(UNKNOWN, IntentClassifier.classify(""));
        assertEquals(UNKNOWN, IntentClassifier.classify("   "));
    }

    // ── Boundary: exactly 3 words with no code signal → CHAT ────────────

    @Test
    void three_word_non_technical_is_chat() {
        assertEquals(CHAT, IntentClassifier.classify("I am bored"));
    }

    // ── Boundary: 4+ words with no code signal → UNKNOWN (sweep) ────────

    @Test
    void four_word_non_technical_is_unknown() {
        assertEquals(UNKNOWN, IntentClassifier.classify("I am very bored"));
    }

    // ── Stability: classify never returns null ───────────────────────────

    @Test
    void classify_never_returns_null() {
        assertNotNull(IntentClassifier.classify("anything"));
        assertNotNull(IntentClassifier.classify(null));
        assertNotNull(IntentClassifier.classify(""));
    }
}




