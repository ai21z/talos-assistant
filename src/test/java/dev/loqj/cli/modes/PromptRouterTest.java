package dev.loqj.cli.modes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.loqj.cli.modes.PromptRouter.Route.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PromptRouter}: verifies assistant-first routing behavior.
 *
 * <p>These tests validate the actual user-facing routing, not just keyword
 * matching. The core invariant: <b>anything without strong workspace evidence
 * must route to ASSIST, never to RETRIEVE.</b>
 *
 * <p>Secondary invariant: <b>PascalCase alone is not sufficient for retrieval.</b>
 * It requires question context to distinguish code inquiries from brand names
 * and proper nouns.
 */
class PromptRouterTest {

    // ═══════════════════════════════════════════════════════════════════════
    //  ASSIST: conversational turns (the core fix)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "hey",
        "Hey!",
        "hi",
        "hello",
        "howdy",
        "yo",
        "good morning",
        "good afternoon",
    })
    void greetings_route_to_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "Greeting '" + input + "' must not trigger retrieval");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "thanks",
        "thank you",
        "bye",
        "goodbye",
        "see you later",
        "cheers",
    })
    void farewells_route_to_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "Farewell '" + input + "' must not trigger retrieval");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "got it",
        "understood",
        "makes sense",
        "ok",
        "okay",
        "sure",
        "yes",
        "cool",
        "nice",
        "perfect",
        "great",
    })
    void acknowledgments_route_to_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "Acknowledgment '" + input + "' must not trigger retrieval");
    }

    // ── The original failure cases ───────────────────────────────────────

    @Test
    void conversational_followup_routes_to_assist() {
        // This was the original bug: "I dont know good, what about you?"
        // routed to RAG because UNKNOWN fell through to the RAG sweep
        assertEquals(ASSIST, PromptRouter.route("I dont know good, what about you?"));
    }

    @Test
    void casual_how_are_you_routes_to_assist() {
        assertEquals(ASSIST, PromptRouter.route("how are you?"));
    }

    @Test
    void social_response_routes_to_assist() {
        assertEquals(ASSIST, PromptRouter.route("I'm doing fine, what about you?"));
    }

    @Test
    void hello_how_are_you_routes_to_assist() {
        assertEquals(ASSIST, PromptRouter.route("hello, how are you?"));
    }

    // ── General knowledge questions (no workspace signals) ───────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "what time is it right now",
        "tell me about the weather today",
        "can you translate this to French for me",
        "tell me a joke",
        "what is the capital of France",
        "how do I make pasta",
        "who won the world cup",
        "explain quantum computing to me",
        "what is machine learning",
        "translate this to French",
    })
    void general_knowledge_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "General question '" + input + "' must not trigger retrieval");
    }

    // ── Meta/self-referential questions ──────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "who are you",
        "what can you do",
        "help me",
        "what are your capabilities",
    })
    void meta_questions_route_to_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "Meta question '" + input + "' must not trigger retrieval");
    }

    // ── Short ambiguous input ────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "hmm",
        "lol",
        "wow",
        "I am bored",
        "not sure",
        "go on",
        "say something",
        "what now",
    })
    void short_non_technical_input_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "Short input '" + input + "' must not trigger retrieval");
    }

    // ── Previously broken: generic words that used to trigger RAG ────────

    @ParameterizedTest
    @ValueSource(strings = {
        "I need to find my keys",
        "can you search for a good recipe",
        "explain the meaning of life",
        "compare apples and oranges",
        "describe your favorite movie",
        "I found a bug in my garden",
        "the design of this room is nice",
        "fix my broken heart",
        "where should I eat dinner",
        "how does the weather work",
    })
    void generic_english_does_not_trigger_retrieval(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "Generic English '" + input + "' must not trigger retrieval");
    }

    // ── PascalCase without question context → ASSIST ─────────────────────
    // These are the key false-positive cases that the new design prevents.

    @ParameterizedTest
    @ValueSource(strings = {
        "I use PowerPoint",
        "IntelliJ is great",
        "MaryJane said hello",
        "check out YouTube",
        "I prefer StackOverflow",
        "LinkedIn is down",
        "try GitHub Desktop",
    })
    void pascal_case_without_question_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "PascalCase without question '" + input + "' must NOT trigger retrieval");
    }

    @Test
    void bare_pascal_case_without_question_routes_to_assist() {
        // Bare PascalCase with no question context: not enough evidence.
        // User can type "what is RagService" or ":mode rag RagService" instead.
        assertEquals(ASSIST, PromptRouter.route("RagService"));
        assertEquals(ASSIST, PromptRouter.route("ModeController"));
    }

    // ── Ambiguous technical English (no workspace anchor) ────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "how does dependency injection work",
        "what is a REST API",
        "explain microservices architecture",
        "what is the difference between threads and processes",
        "how does garbage collection work in general",
        "what is a design pattern",
        "how does a pipeline work",
    })
    void ambiguous_technical_english_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "Ambiguous tech '" + input + "' must not trigger retrieval without workspace anchor");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  RETRIEVE: strong workspace signals
    // ═══════════════════════════════════════════════════════════════════════

    // ── File references ──────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "explain RagService.java",
        "what does Config.yaml do",
        "summarize README.md",
        "differences between Foo.java and Bar.java",
        "what is in pom.xml",
    })
    void file_references_trigger_retrieval(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "File ref '" + input + "' should trigger retrieval");
    }

    // ── Workspace framing ────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "how does this project handle authentication",
        "what is the codebase structure",
        "find errors in this codebase",
        "what patterns are used in our project",
        "explain the architecture of this workspace",
        "in this project how is logging done",
    })
    void workspace_framing_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "Workspace frame '" + input + "' should trigger retrieval");
    }

    // ── PascalCase code identifiers WITH question context ────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "what does RagService do",
        "explain ModeController",
        "how does ContextPacker work",
        "where is RetrievalPipeline defined",
        "show me how PromptRouter decides",
    })
    void pascal_case_in_question_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "PascalCase+question '" + input + "' should trigger retrieval");
    }

    // ── Question + anchored technical noun ───────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "what does the pipeline do",
        "how does the retrieval work",
        "where is the config defined",
        "explain the indexing process",
        "what does the service return",
        "how does the build work",
        "what is the test coverage",
        "describe the error handling",
        "explain the chunking strategy",
    })
    void question_with_anchored_noun_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "Question+anchor '" + input + "' should trigger retrieval");
    }

    // ── Anchored nouns WITHOUT question context → ASSIST ─────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "the design is nice",
        "the pipeline looks complicated",
        "I like the service",
        "the config seems reasonable",
    })
    void anchored_noun_without_question_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "Statement '" + input + "' should NOT trigger retrieval");
    }

    // ── Generic "a/an" vs specific "the/this" ────────────────────────────

    @Test
    void generic_article_does_not_trigger_retrieval() {
        // "a pipeline" is generic; "the pipeline" in a question is specific
        assertEquals(ASSIST, PromptRouter.route("how does a pipeline work"));
    }

    @Test
    void definite_article_in_question_triggers_retrieval() {
        assertEquals(RETRIEVE, PromptRouter.route("how does the pipeline work"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  COMMAND: dev file operations
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "open src/Main.java",
        "show build.gradle.kts",
        "view README.md",
        "ls src/",
        "ls",
        "list docs",
        "dir src/main",
        "list",
    })
    void dev_commands_route_to_command(String input) {
        assertEquals(COMMAND, PromptRouter.route(input),
                "Dev command '" + input + "' should route to COMMAND");
    }

    // ── "show me <file>" → COMMAND (not RETRIEVE) ───────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "show me build.gradle.kts",
        "show me README.md",
        "show me src/Main.java",
        "show me the Dockerfile",
        "show me the README",
    })
    void show_me_file_routes_to_command(String input) {
        assertEquals(COMMAND, PromptRouter.route(input),
                "Show-me-file '" + input + "' should route to COMMAND (direct file display)");
    }

    // ── "show me <natural language>" → NOT COMMAND ───────────────────────

    @Test
    void show_me_how_is_not_a_command() {
        // "show me how X works" is a question, not a file display
        assertEquals(RETRIEVE, PromptRouter.route("show me how PromptRouter decides"));
    }

    @Test
    void show_me_joke_is_assist() {
        assertEquals(ASSIST, PromptRouter.route("show me your best joke"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Mixed signals
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void greeting_with_file_ref_triggers_retrieval() {
        // File reference overrides casual prefix
        assertEquals(RETRIEVE, PromptRouter.route("hey explain RagService.java"));
    }

    @Test
    void greeting_with_pascal_case_triggers_retrieval() {
        // "hey what is RagService" — prefix stripped, question + PascalCase
        assertEquals(RETRIEVE, PromptRouter.route("hey what is RagService"));
    }

    @Test
    void greeting_with_workspace_frame_triggers_retrieval() {
        assertEquals(RETRIEVE, PromptRouter.route("hey how does this project work"));
    }

    @Test
    void hey_explain_ragservice_java_is_retrieval() {
        // Mixed: greeting + explain + file ref → strongest signal wins
        assertEquals(RETRIEVE, PromptRouter.route("hey, explain RagService.java"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Follow-up context (sticky retrieval)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void follow_up_after_retrieve_stays_in_retrieve() {
        // After a RETRIEVE turn, continuation questions inherit context
        assertEquals(RETRIEVE, PromptRouter.route("what about the parse method?", RETRIEVE));
        assertEquals(RETRIEVE, PromptRouter.route("and the constructor?", RETRIEVE));
        assertEquals(RETRIEVE, PromptRouter.route("tell me more", RETRIEVE));
        assertEquals(RETRIEVE, PromptRouter.route("how does it work?", RETRIEVE));
        assertEquals(RETRIEVE, PromptRouter.route("what else is there?", RETRIEVE));
        assertEquals(RETRIEVE, PromptRouter.route("go on", RETRIEVE));
        assertEquals(RETRIEVE, PromptRouter.route("elaborate", RETRIEVE));
        assertEquals(RETRIEVE, PromptRouter.route("continue", RETRIEVE));
    }

    @Test
    void social_follow_up_after_retrieve_breaks_context() {
        // Social follow-ups do NOT inherit retrieval context
        assertEquals(ASSIST, PromptRouter.route("thanks", RETRIEVE));
        assertEquals(ASSIST, PromptRouter.route("thank you", RETRIEVE));
        assertEquals(ASSIST, PromptRouter.route("that's great", RETRIEVE));
        assertEquals(ASSIST, PromptRouter.route("bye", RETRIEVE));
        assertEquals(ASSIST, PromptRouter.route("see you", RETRIEVE));
    }

    @Test
    void what_about_you_after_retrieve_is_social() {
        // "what about you?" is social, not a code follow-up
        assertEquals(ASSIST, PromptRouter.route("what about you?", RETRIEVE));
        assertEquals(ASSIST, PromptRouter.route("how about you?", RETRIEVE));
        assertEquals(ASSIST, PromptRouter.route("and you?", RETRIEVE));
    }

    @Test
    void follow_up_after_assist_stays_assist() {
        // No sticky retrieval when last turn was ASSIST
        assertEquals(ASSIST, PromptRouter.route("what about it?", ASSIST));
        assertEquals(ASSIST, PromptRouter.route("tell me more", ASSIST));
        assertEquals(ASSIST, PromptRouter.route("go on", ASSIST));
    }

    @Test
    void follow_up_without_context_stays_assist() {
        // First turn (no lastRoute) — no sticky context
        assertEquals(ASSIST, PromptRouter.route("what about it?"));
        assertEquals(ASSIST, PromptRouter.route("tell me more"));
    }

    @Test
    void strong_signal_overrides_follow_up_context() {
        // Even after ASSIST, strong signals independently classify as RETRIEVE
        assertEquals(RETRIEVE, PromptRouter.route("explain RagService.java", ASSIST));
        assertEquals(RETRIEVE, PromptRouter.route("what does this project do", ASSIST));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void null_input_routes_to_assist() {
        assertEquals(ASSIST, PromptRouter.route(null));
        assertEquals(ASSIST, PromptRouter.route(null, RETRIEVE));
    }

    @Test
    void blank_input_routes_to_assist() {
        assertEquals(ASSIST, PromptRouter.route(""));
        assertEquals(ASSIST, PromptRouter.route("   "));
    }

    @Test
    void route_never_returns_null() {
        assertNotNull(PromptRouter.route("anything"));
        assertNotNull(PromptRouter.route(null));
        assertNotNull(PromptRouter.route(""));
        assertNotNull(PromptRouter.route("test", RETRIEVE));
        assertNotNull(PromptRouter.route("test", null));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  isQuestionLike helper
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void question_mark_is_question_like() {
        assertTrue(PromptRouter.isQuestionLike("what about you?"));
    }

    @Test
    void question_word_is_question_like() {
        assertTrue(PromptRouter.isQuestionLike("how does this work"));
        assertTrue(PromptRouter.isQuestionLike("what is this"));
        assertTrue(PromptRouter.isQuestionLike("where is the file"));
        assertTrue(PromptRouter.isQuestionLike("explain the pipeline"));
        assertTrue(PromptRouter.isQuestionLike("describe the architecture"));
        assertTrue(PromptRouter.isQuestionLike("tell me about the api"));
    }

    @Test
    void conversational_prefix_stripped_for_question_detection() {
        // "hey what is X" → strip "hey " → "what is X" → question-like
        assertTrue(PromptRouter.isQuestionLike("hey what is ragservice"));
        assertTrue(PromptRouter.isQuestionLike("ok explain the pipeline"));
        assertTrue(PromptRouter.isQuestionLike("so how does this work"));
        assertTrue(PromptRouter.isQuestionLike("well, what is this"));
    }

    @Test
    void statement_is_not_question_like() {
        assertFalse(PromptRouter.isQuestionLike("the design is nice"));
        assertFalse(PromptRouter.isQuestionLike("i like the pipeline"));
        assertFalse(PromptRouter.isQuestionLike("ok got it"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  isFollowUp helper
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void continuation_patterns_are_follow_ups() {
        assertTrue(PromptRouter.isFollowUp("what about the parse method"));
        assertTrue(PromptRouter.isFollowUp("and the constructor"));
        assertTrue(PromptRouter.isFollowUp("tell me more"));
        assertTrue(PromptRouter.isFollowUp("go on"));
        assertTrue(PromptRouter.isFollowUp("elaborate"));
        assertTrue(PromptRouter.isFollowUp("how does it work"));
        assertTrue(PromptRouter.isFollowUp("what else"));
    }

    @Test
    void social_patterns_are_not_follow_ups() {
        assertFalse(PromptRouter.isFollowUp("what about you"));
        assertFalse(PromptRouter.isFollowUp("thanks"));
        assertFalse(PromptRouter.isFollowUp("that's great"));
        assertFalse(PromptRouter.isFollowUp("no thanks"));
        assertFalse(PromptRouter.isFollowUp("bye"));
    }

    @Test
    void non_continuation_is_not_follow_up() {
        assertFalse(PromptRouter.isFollowUp("hey"));
        assertFalse(PromptRouter.isFollowUp("I am bored"));
        assertFalse(PromptRouter.isFollowUp("just wondering"));
    }
}
