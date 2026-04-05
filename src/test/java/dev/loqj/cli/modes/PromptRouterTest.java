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
        "show me build.gradle.kts",
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

    // ── PascalCase code identifiers ──────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "what does RagService do",
        "explain ModeController",
        "how does ContextPacker work",
        "where is RetrievalPipeline defined",
        "show me how PromptRouter decides",
    })
    void pascal_case_identifiers_trigger_retrieval(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "PascalCase '" + input + "' should trigger retrieval");
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
    })
    void dev_commands_route_to_command(String input) {
        assertEquals(COMMAND, PromptRouter.route(input),
                "Dev command '" + input + "' should route to COMMAND");
    }

    @Test
    void show_me_is_not_a_command() {
        // "show me build.gradle.kts" has a file ref → RETRIEVE, not COMMAND
        // because "show me" is natural language
        assertEquals(RETRIEVE, PromptRouter.route("show me build.gradle.kts"));
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
        assertEquals(RETRIEVE, PromptRouter.route("hey what is RagService"));
    }

    @Test
    void greeting_with_workspace_frame_triggers_retrieval() {
        assertEquals(RETRIEVE, PromptRouter.route("hey how does this project work"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void null_input_routes_to_assist() {
        assertEquals(ASSIST, PromptRouter.route(null));
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
    }

    @Test
    void statement_is_not_question_like() {
        assertFalse(PromptRouter.isQuestionLike("the design is nice"));
        assertFalse(PromptRouter.isQuestionLike("i like the pipeline"));
        assertFalse(PromptRouter.isQuestionLike("ok got it"));
    }
}

