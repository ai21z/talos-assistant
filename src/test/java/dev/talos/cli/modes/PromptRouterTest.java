package dev.talos.cli.modes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.talos.cli.modes.PromptRouter.Route.*;
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
        // User can type "what is RagService" or "/mode rag RagService" instead.
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

    // ═══════════════════════════════════════════════════════════════════════
    //  RETRIEVE: action-intent with workspace signals
    // ═══════════════════════════════════════════════════════════════════════

    // ── Action verb + PascalCase identifier → RETRIEVE ────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "write a test for RagService",
        "create a unit test for ModeController",
        "refactor ContextPacker",
        "fix RagService",
        "add logging to PromptRouter",
        "implement a new RetrievalPipeline stage",
        "update DevMode to support new feature",
        "delete the old ChunkMetadata",
        "rename RetrievalPipeline to SearchPipeline",
        "generate a test for LuceneStore",
        "rewrite ModeController routing logic",
        "debug RagService pipeline flow",
        "optimize ContextPacker token counting",
        "extract a method from ModeController",
        "wire ToolCallLoop into RagMode",
    })
    void action_with_pascal_case_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "Action+PascalCase '" + input + "' should trigger retrieval");
    }

    // ── Action verb + anchored tech noun → RETRIEVE ───────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "fix the parser",
        "refactor the pipeline",
        "add logging to the service",
        "update the config",
        "rewrite the handler",
        "optimize the indexing",
        "test the retrieval",
        "debug the reranker",
        "migrate the schema",
        "configure the endpoint",
        "implement the interface",
        "delete the test",
        "move the controller",
        "build the module",
    })
    void action_with_anchored_noun_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "Action+anchor '" + input + "' should trigger retrieval");
    }

    // ── Action verb WITHOUT workspace signal → ASSIST ─────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "write a poem",
        "create a haiku about spring",
        "fix my broken heart",
        "add some humor",
        "generate a random number",
        "build a sandcastle",
        "delete my worries",
        "move on to something else",
        "run a marathon",
        "test my patience",
    })
    void action_without_workspace_signal_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "Action without workspace signal '" + input + "' must NOT trigger retrieval");
    }

    // ── Action verb with conversational prefix ────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "hey, write a test for RagService",
        "ok fix the parser",
        "actually, refactor ModeController",
        "so, add logging to the service",
        "well, rewrite the handler",
    })
    void prefixed_action_with_workspace_signal_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "Prefixed action '" + input + "' should trigger retrieval");
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
    //  isActionLike helper
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void action_verbs_are_action_like() {
        assertTrue(PromptRouter.isActionLike("write a test"));
        assertTrue(PromptRouter.isActionLike("create a file"));
        assertTrue(PromptRouter.isActionLike("edit the config"));
        assertTrue(PromptRouter.isActionLike("fix the bug"));
        assertTrue(PromptRouter.isActionLike("add logging"));
        assertTrue(PromptRouter.isActionLike("implement the interface"));
        assertTrue(PromptRouter.isActionLike("refactor the class"));
        assertTrue(PromptRouter.isActionLike("update the version"));
        assertTrue(PromptRouter.isActionLike("delete the old file"));
        assertTrue(PromptRouter.isActionLike("remove unused imports"));
        assertTrue(PromptRouter.isActionLike("rename the variable"));
        assertTrue(PromptRouter.isActionLike("move the method"));
        assertTrue(PromptRouter.isActionLike("generate a report"));
        assertTrue(PromptRouter.isActionLike("modify the schema"));
        assertTrue(PromptRouter.isActionLike("rewrite the handler"));
        assertTrue(PromptRouter.isActionLike("extract a helper method"));
        assertTrue(PromptRouter.isActionLike("optimize the query"));
        assertTrue(PromptRouter.isActionLike("debug the flow"));
        assertTrue(PromptRouter.isActionLike("migrate the database"));
        assertTrue(PromptRouter.isActionLike("convert to records"));
        assertTrue(PromptRouter.isActionLike("test the parser"));
        assertTrue(PromptRouter.isActionLike("run the tests"));
        assertTrue(PromptRouter.isActionLike("build the project"));
        assertTrue(PromptRouter.isActionLike("deploy to staging"));
        assertTrue(PromptRouter.isActionLike("set up the config"));
        assertTrue(PromptRouter.isActionLike("setup logging"));
        assertTrue(PromptRouter.isActionLike("configure the endpoint"));
        assertTrue(PromptRouter.isActionLike("scaffold a new module"));
        assertTrue(PromptRouter.isActionLike("bootstrap the project"));
        assertTrue(PromptRouter.isActionLike("wire the tool loop"));
        assertTrue(PromptRouter.isActionLike("hook up the listener"));
        assertTrue(PromptRouter.isActionLike("integrate the embeddings client"));
    }

    @Test
    void conversational_prefix_stripped_for_action_detection() {
        assertTrue(PromptRouter.isActionLike("hey, write a test"));
        assertTrue(PromptRouter.isActionLike("ok fix the bug"));
        assertTrue(PromptRouter.isActionLike("actually, refactor the class"));
        assertTrue(PromptRouter.isActionLike("so, add logging to the service"));
        assertTrue(PromptRouter.isActionLike("cool, rewrite the handler"));
    }

    @Test
    void non_action_is_not_action_like() {
        assertFalse(PromptRouter.isActionLike("hey"));
        assertFalse(PromptRouter.isActionLike("what is this"));
        assertFalse(PromptRouter.isActionLike("I like the pipeline"));
        assertFalse(PromptRouter.isActionLike("the parser is broken"));
        assertFalse(PromptRouter.isActionLike("ok got it"));
        assertFalse(PromptRouter.isActionLike("how does this work"));
        assertFalse(PromptRouter.isActionLike("explain the constructor"));
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

    // ═══════════════════════════════════════════════════════════════════════
    //  Quoted "show me" paths (B: quoted path support)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "show me \"docs/My Guide.md\"",
        "show me \"README.md\"",
        "show me 'build.gradle.kts'",
        "show me the \"README.md\"",
        "show me \"src/main/java/Foo.java\"",
        "show me 'src/My Config.yaml'",
    })
    void show_me_quoted_file_routes_to_command(String input) {
        assertEquals(COMMAND, PromptRouter.route(input),
                "Quoted show-me-file '" + input + "' should route to COMMAND");
    }

    @Test
    void show_me_quoted_non_file_is_not_command() {
        // Quoted text without file extension isn't a file command
        assertEquals(ASSIST, PromptRouter.route("show me \"how to build\""));
        assertEquals(ASSIST, PromptRouter.route("show me \"some random text\""));
    }

    @Test
    void show_me_unquoted_spaced_path_falls_through_to_retrieve() {
        // Unquoted paths with spaces can't be reliably detected as file commands.
        // "Guide.md" matches FILE_REF in the full input, so it routes to RETRIEVE.
        // Users should quote spaced paths for precise COMMAND behavior.
        assertEquals(RETRIEVE, PromptRouter.route("show me docs/My Guide.md"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Expanded ANCHORED_TECH_NOUN (C: language-level constructs)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "what does the constructor do",
        "explain the enum values",
        "where is the record defined",
        "what does the annotation mean",
        "explain the variable",
        "what is the field for",
        "describe the property",
        "what does the import resolve",
        "explain the implementation",
        "what are the dependencies",
        "how does the enumeration work",
        "what are the properties",
    })
    void language_construct_nouns_trigger_retrieval(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "Language construct '" + input + "' should trigger retrieval");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "the constructor is complex",
        "the enum has too many values",
        "the record looks fine",
        "I like the annotation style",
        "the field is initialized",
        "the implementation is clever",
    })
    void language_construct_statements_stay_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "Statement '" + input + "' should NOT trigger retrieval");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Continuation prefix follow-ups (D: prefix stripping)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "actually, what about the constructor?",
        "cool, and the parser?",
        "right, tell me more",
        "yeah, how does it work",
        "ok, what about that",
        "sure, elaborate",
        "alright, go on",
        "yep, what else is there",
    })
    void continuation_prefix_follow_ups_after_retrieve(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input, RETRIEVE),
                "Prefixed follow-up '" + input + "' after RETRIEVE should stay RETRIEVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ok, thanks",
        "sure, bye",
        "right, that's great",
        "yeah, thank you",
        "cool, no thanks",
    })
    void social_with_prefix_after_retrieve_still_breaks_context(String input) {
        assertEquals(ASSIST, PromptRouter.route(input, RETRIEVE),
                "Social '" + input + "' after RETRIEVE should break to ASSIST");
    }

    @Test
    void one_more_is_follow_up_after_retrieve() {
        assertEquals(RETRIEVE, PromptRouter.route("one more thing about that file", RETRIEVE));
        assertEquals(RETRIEVE, PromptRouter.route("one more question", RETRIEVE));
        assertEquals(RETRIEVE, PromptRouter.route("one more", RETRIEVE));
    }

    @Test
    void one_more_without_context_stays_assist() {
        // "one more" without retrieval context is not enough to trigger
        assertEquals(ASSIST, PromptRouter.route("one more thing about that file"));
        assertEquals(ASSIST, PromptRouter.route("one more question"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Extended prefix stripping in isQuestionLike
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void extended_prefix_stripped_for_question_detection() {
        // New acknowledgment prefixes are stripped before question detection
        assertTrue(PromptRouter.isQuestionLike("sure, explain the pipeline"));
        assertTrue(PromptRouter.isQuestionLike("cool, what does this do"));
        assertTrue(PromptRouter.isQuestionLike("actually, how does it work"));
        assertTrue(PromptRouter.isQuestionLike("right, where is the config"));
        assertTrue(PromptRouter.isQuestionLike("yeah, describe the architecture"));
        assertTrue(PromptRouter.isQuestionLike("yep, explain the constructor"));
    }

    @Test
    void extended_prefix_does_not_create_false_question() {
        // Prefix stripping alone doesn't make non-questions into questions
        assertFalse(PromptRouter.isQuestionLike("sure, I agree"));
        assertFalse(PromptRouter.isQuestionLike("cool, that makes sense"));
        assertFalse(PromptRouter.isQuestionLike("actually, never mind"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Extended isFollowUp helper
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void continuation_prefix_stripped_for_follow_up_detection() {
        assertTrue(PromptRouter.isFollowUp("actually, what about it"));
        assertTrue(PromptRouter.isFollowUp("cool, and the parser"));
        assertTrue(PromptRouter.isFollowUp("right, tell me more"));
        assertTrue(PromptRouter.isFollowUp("yeah, go on"));
        assertTrue(PromptRouter.isFollowUp("ok, elaborate"));
        assertTrue(PromptRouter.isFollowUp("sure, what else"));
    }

    @Test
    void continuation_prefix_social_still_not_follow_up() {
        assertFalse(PromptRouter.isFollowUp("ok, thanks"));
        assertFalse(PromptRouter.isFollowUp("sure, bye"));
        assertFalse(PromptRouter.isFollowUp("right, that's great"));
        assertFalse(PromptRouter.isFollowUp("actually, thank you"));
    }

    @Test
    void one_more_patterns_are_follow_ups() {
        assertTrue(PromptRouter.isFollowUp("one more thing"));
        assertTrue(PromptRouter.isFollowUp("one more question"));
        assertTrue(PromptRouter.isFollowUp("one more"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  End-to-end: realistic multi-turn sequences
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void multi_turn_retrieval_with_prefixed_follow_ups() {
        // Turn 1: explicit retrieval trigger
        assertEquals(RETRIEVE, PromptRouter.route("what does RagService do"));
        // Turn 2: prefixed follow-up → stays in RETRIEVE
        assertEquals(RETRIEVE, PromptRouter.route("cool, and the parser?", RETRIEVE));
        // Turn 3: another prefixed follow-up → still RETRIEVE
        assertEquals(RETRIEVE, PromptRouter.route("actually, what about the constructor?", RETRIEVE));
        // Turn 4: social → breaks to ASSIST
        assertEquals(ASSIST, PromptRouter.route("ok, thanks", RETRIEVE));
    }

    @Test
    void prefixed_question_with_new_tech_noun_triggers_retrieval_independently() {
        // These work even without lastRoute because they contain
        // strong signals (question + anchored tech noun)
        assertEquals(RETRIEVE, PromptRouter.route("actually, what does the constructor do"));
        assertEquals(RETRIEVE, PromptRouter.route("cool, explain the enum"));
        assertEquals(RETRIEVE, PromptRouter.route("right, where is the record"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Workspace-aware PascalCase resolution (Layer 2c)
    // ═══════════════════════════════════════════════════════════════════════

    // Stub checker: returns true for workspace symbols, false for brand names
    private static final WorkspaceSymbolChecker WORKSPACE_CHECKER = symbol -> {
        String lower = symbol.toLowerCase(java.util.Locale.ROOT);
        return switch (lower) {
            case "ragservice", "modecontroller", "contextpacker",
                 "retrievalpipeline", "promptrouter", "devmode",
                 "lucenestore", "chunkmetadata" -> true;
            default -> false;
        };
    };

    // Checker that knows nothing (empty workspace / no index)
    private static final WorkspaceSymbolChecker EMPTY_CHECKER = symbol -> false;

    // ── Bare PascalCase in workspace → RETRIEVE ──────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "RagService",
        "ModeController",
        "ContextPacker",
        "RetrievalPipeline",
        "PromptRouter",
        "DevMode",
        "LuceneStore",
        "ChunkMetadata",
    })
    void bare_workspace_symbol_triggers_retrieval_with_checker(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input, null, WORKSPACE_CHECKER),
                "Bare workspace symbol '" + input + "' should trigger retrieval when checker confirms");
    }

    // ── PascalCase NOT in workspace → ASSIST ─────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "PowerPoint",
        "IntelliJ",
        "YouTube",
        "LinkedIn",
        "StackOverflow",
        "MaryJane",
    })
    void bare_brand_name_stays_assist_even_with_checker(String input) {
        assertEquals(ASSIST, PromptRouter.route(input, null, WORKSPACE_CHECKER),
                "Brand name '" + input + "' should NOT trigger retrieval even with checker");
    }

    // ── Workspace symbol in sentence context ─────────────────────────────

    @Test
    void workspace_symbol_in_casual_sentence_triggers_retrieval() {
        // If a workspace symbol appears in ANY context, it's enough evidence
        assertEquals(RETRIEVE, PromptRouter.route("I was looking at RagService", null, WORKSPACE_CHECKER));
        assertEquals(RETRIEVE, PromptRouter.route("check ModeController please", null, WORKSPACE_CHECKER));
        assertEquals(RETRIEVE, PromptRouter.route("tell me about ContextPacker", null, WORKSPACE_CHECKER));
    }

    @Test
    void brand_name_in_casual_sentence_stays_assist() {
        // Brand names in sentences must NOT trigger retrieval
        assertEquals(ASSIST, PromptRouter.route("I use PowerPoint daily", null, WORKSPACE_CHECKER));
        assertEquals(ASSIST, PromptRouter.route("IntelliJ is my favorite", null, WORKSPACE_CHECKER));
    }

    // ── No checker: falls back to original behavior ──────────────────────

    @Test
    void bare_workspace_symbol_stays_assist_without_checker() {
        // Without a checker, bare PascalCase still routes to ASSIST
        assertEquals(ASSIST, PromptRouter.route("RagService", null, null));
        assertEquals(ASSIST, PromptRouter.route("ModeController"));
        assertEquals(ASSIST, PromptRouter.route("RagService", null));
    }

    // ── Empty checker: no index → ASSIST ─────────────────────────────────

    @Test
    void bare_symbol_stays_assist_with_empty_checker() {
        // When the checker returns false for everything (no index), behave like no checker
        assertEquals(ASSIST, PromptRouter.route("RagService", null, EMPTY_CHECKER));
        assertEquals(ASSIST, PromptRouter.route("ModeController", null, EMPTY_CHECKER));
    }

    // ── Question + workspace symbol still works (Layer 2b fires first) ───

    @Test
    void question_with_workspace_symbol_triggers_via_layer_2b() {
        // Question-gated path fires before workspace lookup — checker not needed
        assertEquals(RETRIEVE, PromptRouter.route("what does RagService do", null, EMPTY_CHECKER));
        assertEquals(RETRIEVE, PromptRouter.route("explain ModeController", null, EMPTY_CHECKER));
    }

    // ── Multiple PascalCase tokens: any match triggers ───────────────────

    @Test
    void any_workspace_symbol_among_multiple_pascal_case_triggers() {
        // "FooBar" is not in workspace, but "RagService" is
        assertEquals(RETRIEVE, PromptRouter.route("FooBar and RagService", null, WORKSPACE_CHECKER));
        // Neither in workspace
        assertEquals(ASSIST, PromptRouter.route("FooBar and BazQuux", null, WORKSPACE_CHECKER));
    }

    // ── Workspace-aware routing with conversation context ─────────────────

    @Test
    void workspace_symbol_overrides_assist_context() {
        // Even after ASSIST, workspace symbol independently triggers RETRIEVE
        assertEquals(RETRIEVE, PromptRouter.route("RagService", ASSIST, WORKSPACE_CHECKER));
    }

    @Test
    void workspace_symbol_with_retrieve_context_still_retrieves() {
        // After RETRIEVE, workspace symbol confirms retrieval
        assertEquals(RETRIEVE, PromptRouter.route("ModeController", RETRIEVE, WORKSPACE_CHECKER));
    }

    // ── Workspace-aware: stronger signals still take priority ─────────────

    @Test
    void file_ref_takes_priority_over_workspace_check() {
        // FILE_REF (Layer 2) fires before workspace check (Layer 2c)
        assertEquals(RETRIEVE, PromptRouter.route("RagService.java", null, EMPTY_CHECKER));
    }

    @Test
    void command_takes_priority_over_workspace_check() {
        // COMMAND (Layer 1) fires before everything
        assertEquals(COMMAND, PromptRouter.route("show build.gradle.kts", null, WORKSPACE_CHECKER));
    }

    // ── Edge: null/blank with checker ─────────────────────────────────────

    @Test
    void null_input_routes_to_assist_with_checker() {
        assertEquals(ASSIST, PromptRouter.route(null, null, WORKSPACE_CHECKER));
    }

    @Test
    void blank_input_routes_to_assist_with_checker() {
        assertEquals(ASSIST, PromptRouter.route("", null, WORKSPACE_CHECKER));
        assertEquals(ASSIST, PromptRouter.route("   ", null, WORKSPACE_CHECKER));
    }

    // ── Backward compatibility: 2-arg route delegates to 3-arg ───────────

    @Test
    void two_arg_route_is_backward_compatible() {
        // The 2-arg method must produce the same results as before
        assertEquals(ASSIST, PromptRouter.route("RagService", null));
        assertEquals(RETRIEVE, PromptRouter.route("what does RagService do", null));
        assertEquals(RETRIEVE, PromptRouter.route("what about the parse method?", RETRIEVE));
        assertEquals(ASSIST, PromptRouter.route("thanks", RETRIEVE));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Action-intent: end-to-end multi-turn sequences
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void multi_turn_action_then_follow_up() {
        // Turn 1: action + PascalCase → RETRIEVE
        assertEquals(RETRIEVE, PromptRouter.route("write a test for RagService"));
        // Turn 2: follow-up → stays in RETRIEVE
        assertEquals(RETRIEVE, PromptRouter.route("what about edge cases?", RETRIEVE));
        // Turn 3: social → breaks to ASSIST
        assertEquals(ASSIST, PromptRouter.route("thanks", RETRIEVE));
    }

    @Test
    void action_after_assist_triggers_retrieval_independently() {
        // Even after ASSIST, action + workspace signal independently triggers RETRIEVE
        assertEquals(RETRIEVE, PromptRouter.route("fix the parser", ASSIST));
        assertEquals(RETRIEVE, PromptRouter.route("refactor ModeController", ASSIST));
    }

    @Test
    void action_with_workspace_checker() {
        // Action + bare PascalCase confirmed by workspace checker
        assertEquals(RETRIEVE, PromptRouter.route("refactor RagService", null, WORKSPACE_CHECKER));
        // Action without PascalCase + no tech noun → ASSIST even with checker
        assertEquals(ASSIST, PromptRouter.route("write a poem", null, WORKSPACE_CHECKER));
    }

    @Test
    void action_with_file_reference_already_routes() {
        // File references fire before Layer 2b — already RETRIEVE
        assertEquals(RETRIEVE, PromptRouter.route("edit build.gradle.kts"));
        assertEquals(RETRIEVE, PromptRouter.route("fix RagService.java"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Expanded workspace framing (G14 fix)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "what is this site about",
        "describe my app",
        "what does the application do",
        "tell me about this webapp",
        "what's in this folder",
        "describe the directory structure",
        "how is this setup organized",
    })
    void expanded_workspace_framing_routes_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "Workspace framing '" + input + "' should trigger retrieval");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Expanded anchored tech nouns (G14 fix)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "what does the directory contain",
        "explain the page layout",
        "how does the component work",
        "describe the template structure",
        "what is the stylesheet for",
        "how does the route handle requests",
        "explain the middleware logic",
        "what does the model represent",
        "describe the repository pattern",
        "how does the adapter work",
    })
    void expanded_tech_nouns_with_question_route_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "Tech noun question '" + input + "' should trigger retrieval");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Expanded action verbs (G14 fix)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "inspect the RagService",
        "review ModeController",
        "verify the Sandbox implementation",
        "scan the TokenBudget class",
        "analyze PromptRouter",
        "examine the ConversationManager",
        "look at the ContextPacker code",
        "find RagService usages",
        "search for TokenBudget references",
        "explore the ToolCallLoop",
        "change the SystemPromptBuilder",
        "install dependencies for RagService",
        "lint the PromptRouter code",
        "format ModeController",
        "document the ConversationCompactor",
    })
    void expanded_action_verbs_with_pascal_case_route_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "Action verb with PascalCase '" + input + "' should trigger retrieval");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "inspect the pipeline",
        "review the handler logic",
        "verify the controller works",
        "scan the directory structure",
        "analyze the component hierarchy",
        "explore the template files",
    })
    void expanded_action_verbs_with_tech_noun_route_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "Action verb with tech noun '" + input + "' should trigger retrieval");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "inspect my car",
        "review the movie",
        "scan the horizon",
        "explore the universe",
    })
    void expanded_action_verbs_without_workspace_signals_route_to_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "Action verb without workspace signal '" + input + "' should route to ASSIST");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Empty-retrieval guidance (RagMode test already covers buildMessages)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void check_out_youtube_still_routes_to_assist() {
        // Regression guard: "check" was removed from isActionLike()
        // because "check out YouTube" is casual speech, not a workspace action
        assertEquals(ASSIST, PromptRouter.route("check out YouTube"));
        assertEquals(ASSIST, PromptRouter.route("check this out"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Workspace proximity: "here", "workspace", "working on" (G14b fix)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "what am I working on here?",
        "what am I working on here",
        "what is here?",
        "what's here",
        "what do we have here",
        "what files are here",
        "can you tell me what's here",
        "describe what's here",
        "show me what's here",
    })
    void here_in_question_routes_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "'" + input + "' should trigger retrieval — 'here' = the workspace");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "what workspace is this?",
        "do you know what workspace this is",
        "which workspace am I in",
        "what workspace are we in",
        "describe this workspace",
        "tell me about this workspace",
        "explain the workspace",
    })
    void workspace_in_question_routes_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "'" + input + "' should trigger retrieval — mentions 'workspace'");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "what am I working on?",
        "what am I working on",
        "what are we working on",
        "show me what I'm working on",
        "describe what we're working on",
    })
    void working_on_in_question_routes_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptRouter.route(input),
                "'" + input + "' should trigger retrieval — 'working on' = current project");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "I'm here to help",
        "here is my question",
        "I am here",
        "hello, I'm here",
    })
    void here_without_question_stays_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "'" + input + "' should stay ASSIST — 'here' without question context");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "I like workspaces in general",
        "workspace is a cool concept",
    })
    void workspace_without_question_stays_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "'" + input + "' should stay ASSIST — 'workspace' without question context");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "I'm working on something",
        "still working on it",
    })
    void working_on_without_question_stays_assist(String input) {
        assertEquals(ASSIST, PromptRouter.route(input),
                "'" + input + "' should stay ASSIST — 'working on' without question context");
    }

    @Test
    void real_session_transcript_questions_route_correctly() {
        // These are the exact questions from the failing user session
        assertEquals(RETRIEVE, PromptRouter.route("what am I working on here?"),
                "Real session Q1 should RETRIEVE");
        assertEquals(RETRIEVE, PromptRouter.route("do you know what workspace this is?"),
                "Real session Q3 should RETRIEVE");
    }
}
