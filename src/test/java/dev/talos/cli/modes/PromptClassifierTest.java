package dev.talos.cli.modes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.talos.cli.modes.PromptClassifier.Route.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PromptClassifier}: verifies assistant-first routing behavior.
 *
 * <p>These tests validate the actual user-facing routing, not just keyword
 * matching. The core invariant: <b>anything without strong workspace evidence
 * must route to ASSIST, never to RETRIEVE.</b>
 *
 * <p>Secondary invariant: <b>PascalCase alone is not sufficient for retrieval.</b>
 * It requires question context to distinguish code inquiries from brand names
 * and proper nouns.
 *
 * <p>Test counts are intentionally kept lean: 3–5 representative samples per
 * category. Regression guards for specific bugs are preserved as individual tests.
 */
class PromptClassifierTest {

    // ═══════════════════════════════════════════════════════════════════════
    //  ASSIST: conversational turns (the core fix)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {"hey", "hello", "good morning"})
    void greetings_route_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Greeting '" + input + "' must not trigger retrieval");
    }

    @ParameterizedTest
    @ValueSource(strings = {"thanks", "bye", "see you later"})
    void farewells_route_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Farewell '" + input + "' must not trigger retrieval");
    }

    @ParameterizedTest
    @ValueSource(strings = {"got it", "ok", "sure", "great"})
    void acknowledgments_route_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Acknowledgment '" + input + "' must not trigger retrieval");
    }

    // ── The original failure cases ───────────────────────────────────────

    @Test
    void conversational_followup_routes_to_assist() {
        // This was the original bug: "I dont know good, what about you?"
        // routed to RAG because UNKNOWN fell through to the RAG sweep
        assertEquals(ASSIST, PromptClassifier.route("I dont know good, what about you?"));
    }

    @Test
    void casual_how_are_you_routes_to_assist() {
        assertEquals(ASSIST, PromptClassifier.route("how are you?"));
    }

    @Test
    void social_response_routes_to_assist() {
        assertEquals(ASSIST, PromptClassifier.route("I'm doing fine, what about you?"));
    }

    @Test
    void hello_how_are_you_routes_to_assist() {
        assertEquals(ASSIST, PromptClassifier.route("hello, how are you?"));
    }

    // ── General knowledge questions (no workspace signals) ───────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "what is the capital of France",
        "explain quantum computing to me",
        "tell me a joke",
    })
    void general_knowledge_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "General question '" + input + "' must not trigger retrieval");
    }

    // ── Meta/self-referential questions ──────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"who are you", "what can you do", "help me"})
    void meta_questions_route_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Meta question '" + input + "' must not trigger retrieval");
    }

    // ── Short ambiguous input ────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"hmm", "I am bored", "what now"})
    void short_non_technical_input_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Short input '" + input + "' must not trigger retrieval");
    }

    // ── Previously broken: generic words that used to trigger RAG ────────

    @ParameterizedTest
    @ValueSource(strings = {
        "I need to find my keys",
        "I found a bug in my garden",
        "fix my broken heart",
    })
    void generic_english_does_not_trigger_retrieval(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Generic English '" + input + "' must not trigger retrieval");
    }

    // ── PascalCase without question context → ASSIST ─────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"I use PowerPoint", "IntelliJ is great", "LinkedIn is down"})
    void pascal_case_without_question_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "PascalCase without question '" + input + "' must NOT trigger retrieval");
    }

    @Test
    void bare_pascal_case_without_question_routes_to_assist() {
        assertEquals(ASSIST, PromptClassifier.route("RagService"));
        assertEquals(ASSIST, PromptClassifier.route("ModeController"));
    }

    // ── Ambiguous technical English (no workspace anchor) ────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "how does dependency injection work",
        "what is a REST API",
        "how does a pipeline work",
    })
    void ambiguous_technical_english_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Ambiguous tech '" + input + "' must not trigger retrieval without workspace anchor");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  RETRIEVE: strong workspace signals
    // ═══════════════════════════════════════════════════════════════════════

    // ── File references ──────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "explain RagService.java",
        "summarize README.md",
        "what is in pom.xml",
    })
    void file_references_trigger_retrieval(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "File ref '" + input + "' should trigger retrieval");
    }

    // ── Workspace framing ────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "how does this project handle authentication",
        "what is the codebase structure",
        "in this project how is logging done",
    })
    void workspace_framing_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Workspace frame '" + input + "' should trigger retrieval");
    }

    // ── PascalCase code identifiers WITH question context ────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "what does RagService do",
        "how does ContextPacker work",
        "where is RetrievalPipeline defined",
    })
    void pascal_case_in_question_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "PascalCase+question '" + input + "' should trigger retrieval");
    }

    // ── Question + anchored technical noun ───────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "what does the pipeline do",
        "how does the retrieval work",
        "explain the chunking strategy",
    })
    void question_with_anchored_noun_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Question+anchor '" + input + "' should trigger retrieval");
    }

    // ── Anchored nouns WITHOUT question context → ASSIST ─────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "the design is nice",
        "the pipeline looks complicated",
        "the config seems reasonable",
    })
    void anchored_noun_without_question_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Statement '" + input + "' should NOT trigger retrieval");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  RETRIEVE: action-intent with workspace signals
    // ═══════════════════════════════════════════════════════════════════════

    // ── Action verb + PascalCase identifier → RETRIEVE ────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "write a test for RagService",
        "refactor ContextPacker",
        "add logging to PromptClassifier",
        "wire ToolCallLoop into RagMode",
    })
    void action_with_pascal_case_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Action+PascalCase '" + input + "' should trigger retrieval");
    }

    // ── Action verb + anchored tech noun → RETRIEVE ───────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "refactor the parser",
        "optimize the pipeline",
        "configure the endpoint",
        "analyze the indexing",
    })
    void action_with_anchored_noun_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Action+anchor '" + input + "' should trigger retrieval");
    }

    // ── Action verb WITHOUT workspace signal → ASSIST ─────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "write a poem",
        "fix my broken heart",
        "build a sandcastle",
    })
    void action_without_workspace_signal_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Action without workspace signal '" + input + "' must NOT trigger retrieval");
    }

    // ── Action verb with conversational prefix ────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "hey, write a test for RagService",
        "ok refactor the parser",
        "actually, refactor ModeController",
    })
    void prefixed_action_with_workspace_signal_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Prefixed action '" + input + "' should trigger retrieval");
    }

    // ── Generic "a/an" vs specific "the/this" ────────────────────────────

    @Test
    void generic_article_does_not_trigger_retrieval() {
        assertEquals(ASSIST, PromptClassifier.route("how does a pipeline work"));
    }

    @Test
    void definite_article_in_question_triggers_retrieval() {
        assertEquals(RETRIEVE, PromptClassifier.route("how does the pipeline work"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  COMMAND: dev file operations
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "open src/Main.java",
        "show build.gradle.kts",
        "ls src/",
        "list",
    })
    void dev_commands_route_to_command(String input) {
        assertEquals(COMMAND, PromptClassifier.route(input),
                "Dev command '" + input + "' should route to COMMAND");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "List names only at workspace root. Does ideas exist here? Answer from evidence only.",
        "list names only for batch-one and workspace root. Did batch-two and batch-one/styles-copy.css get created? Answer from evidence only.",
    })
    void natural_list_names_evidence_prompts_route_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Natural evidence prompt must use assistant/tool handling, not DevMode path extraction");
    }

    // ── "show me <file>" → COMMAND (not RETRIEVE) ───────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "show me build.gradle.kts",
        "show me README.md",
        "show me the Dockerfile",
    })
    void show_me_file_routes_to_command(String input) {
        assertEquals(COMMAND, PromptClassifier.route(input),
                "Show-me-file '" + input + "' should route to COMMAND (direct file display)");
    }

    // ── "show me <natural language>" → NOT COMMAND ───────────────────────

    @Test
    void show_me_how_is_not_a_command() {
        assertEquals(RETRIEVE, PromptClassifier.route("show me how PromptClassifier decides"));
    }

    @Test
    void show_me_joke_is_assist() {
        assertEquals(ASSIST, PromptClassifier.route("show me your best joke"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Mixed signals
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void greeting_with_file_ref_triggers_retrieval() {
        assertEquals(RETRIEVE, PromptClassifier.route("hey explain RagService.java"));
    }

    @Test
    void greeting_with_pascal_case_triggers_retrieval() {
        assertEquals(RETRIEVE, PromptClassifier.route("hey what is RagService"));
    }

    @Test
    void greeting_with_workspace_frame_triggers_retrieval() {
        assertEquals(RETRIEVE, PromptClassifier.route("hey how does this project work"));
    }

    @Test
    void hey_explain_ragservice_java_is_retrieval() {
        assertEquals(RETRIEVE, PromptClassifier.route("hey, explain RagService.java"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Follow-up context (sticky retrieval)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void follow_up_after_retrieve_stays_in_retrieve() {
        assertEquals(RETRIEVE, PromptClassifier.route("what about the parse method?", RETRIEVE));
        assertEquals(RETRIEVE, PromptClassifier.route("and the constructor?", RETRIEVE));
        assertEquals(RETRIEVE, PromptClassifier.route("tell me more", RETRIEVE));
        assertEquals(RETRIEVE, PromptClassifier.route("go on", RETRIEVE));
        assertEquals(RETRIEVE, PromptClassifier.route("elaborate", RETRIEVE));
        assertEquals(RETRIEVE, PromptClassifier.route("continue", RETRIEVE));
    }

    @Test
    void social_follow_up_after_retrieve_breaks_context() {
        assertEquals(ASSIST, PromptClassifier.route("thanks", RETRIEVE));
        assertEquals(ASSIST, PromptClassifier.route("bye", RETRIEVE));
        assertEquals(ASSIST, PromptClassifier.route("that's great", RETRIEVE));
    }

    @Test
    void what_about_you_after_retrieve_is_social() {
        assertEquals(ASSIST, PromptClassifier.route("what about you?", RETRIEVE));
        assertEquals(ASSIST, PromptClassifier.route("and you?", RETRIEVE));
    }

    @Test
    void follow_up_after_assist_stays_assist() {
        assertEquals(ASSIST, PromptClassifier.route("what about it?", ASSIST));
        assertEquals(ASSIST, PromptClassifier.route("tell me more", ASSIST));
    }

    @Test
    void follow_up_without_context_stays_assist() {
        assertEquals(ASSIST, PromptClassifier.route("what about it?"));
        assertEquals(ASSIST, PromptClassifier.route("tell me more"));
    }

    @Test
    void strong_signal_overrides_follow_up_context() {
        assertEquals(RETRIEVE, PromptClassifier.route("explain RagService.java", ASSIST));
        assertEquals(RETRIEVE, PromptClassifier.route("what does this project do", ASSIST));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MUTATION VERBS: edit/update/fix/change/improve → ASSIST (tool path)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "edit index.html",
        "update index.html",
        "fix index.html",
        "change index.html",
        "improve index.html",
        "modify index.html",
        "overwrite index.html",
        "rewrite index.html",
    })
    void mutation_verb_with_file_ref_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Mutation '" + input + "' must route to ASSIST (tools), not RETRIEVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "edit the file",
        "update the file",
        "fix the file",
        "improve the file",
        "change the stylesheet",
    })
    void mutation_verb_with_anchored_noun_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Mutation '" + input + "' must route to ASSIST (tools), not RETRIEVE");
    }

    // ── Conversational prefix + mutation → ASSIST ─────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "Can you update the file so the website looks better?",
        "Can you edit the file please?",
        "Could you fix index.html?",
        "please overwrite index.html",
        "I want you to update the file",
        "would you edit the stylesheet?",
    })
    void polite_mutation_request_routes_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Polite mutation request '" + input + "' must route to ASSIST (tools)");
    }

    // ── Mutation with PascalCase code target → still RETRIEVE ─────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "fix RagService",
        "edit ModeController",
        "update ContextPacker",
    })
    void mutation_with_pascal_case_target_triggers_retrieval(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Mutation+PascalCase '" + input + "' should RETRIEVE (needs code context)");
    }

    // ── Information questions about files must NOT regress ──────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "what is index.html?",
        "explain styles.css",
        "what does build.gradle.kts do",
    })
    void information_questions_about_files_still_retrieve_correctly(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Info question '" + input + "' should still RETRIEVE");
    }

    // ── Deterministic commands must not regress ─────────────────────────────

    @Test
    void deterministic_commands_unchanged() {
        assertEquals(COMMAND, PromptClassifier.route("show index.html"));
        assertEquals(COMMAND, PromptClassifier.route("ls"));
        assertEquals(COMMAND, PromptClassifier.route("dir"));
        assertEquals(COMMAND, PromptClassifier.route("list"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void null_input_routes_to_assist() {
        assertEquals(ASSIST, PromptClassifier.route(null));
        assertEquals(ASSIST, PromptClassifier.route(null, RETRIEVE));
    }

    @Test
    void blank_input_routes_to_assist() {
        assertEquals(ASSIST, PromptClassifier.route(""));
        assertEquals(ASSIST, PromptClassifier.route("   "));
    }

    @Test
    void route_never_returns_null() {
        assertNotNull(PromptClassifier.route("anything"));
        assertNotNull(PromptClassifier.route(null));
        assertNotNull(PromptClassifier.route(""));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  isQuestionLike helper
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void question_mark_is_question_like() {
        assertTrue(PromptClassifier.isQuestionLike("what about you?"));
    }

    @Test
    void question_word_is_question_like() {
        assertTrue(PromptClassifier.isQuestionLike("how does this work"));
        assertTrue(PromptClassifier.isQuestionLike("where is the file"));
        assertTrue(PromptClassifier.isQuestionLike("explain the pipeline"));
        assertTrue(PromptClassifier.isQuestionLike("tell me about the api"));
    }

    @Test
    void conversational_prefix_stripped_for_question_detection() {
        assertTrue(PromptClassifier.isQuestionLike("hey what is ragservice"));
        assertTrue(PromptClassifier.isQuestionLike("ok explain the pipeline"));
        assertTrue(PromptClassifier.isQuestionLike("so how does this work"));
    }

    @Test
    void statement_is_not_question_like() {
        assertFalse(PromptClassifier.isQuestionLike("the design is nice"));
        assertFalse(PromptClassifier.isQuestionLike("ok got it"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  isActionLike helper
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void action_verbs_are_action_like() {
        assertTrue(PromptClassifier.isActionLike("write a test"));
        assertTrue(PromptClassifier.isActionLike("fix the bug"));
        assertTrue(PromptClassifier.isActionLike("refactor the class"));
        assertTrue(PromptClassifier.isActionLike("delete the old file"));
        assertTrue(PromptClassifier.isActionLike("generate a report"));
        assertTrue(PromptClassifier.isActionLike("deploy to staging"));
        assertTrue(PromptClassifier.isActionLike("scaffold a new module"));
        assertTrue(PromptClassifier.isActionLike("wire the tool loop"));
    }

    @Test
    void conversational_prefix_stripped_for_action_detection() {
        assertTrue(PromptClassifier.isActionLike("hey, write a test"));
        assertTrue(PromptClassifier.isActionLike("ok fix the bug"));
        assertTrue(PromptClassifier.isActionLike("actually, refactor the class"));
    }

    @Test
    void non_action_is_not_action_like() {
        assertFalse(PromptClassifier.isActionLike("hey"));
        assertFalse(PromptClassifier.isActionLike("what is this"));
        assertFalse(PromptClassifier.isActionLike("the parser is broken"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  isFollowUp helper
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void continuation_patterns_are_follow_ups() {
        assertTrue(PromptClassifier.isFollowUp("what about the parse method"));
        assertTrue(PromptClassifier.isFollowUp("and the constructor"));
        assertTrue(PromptClassifier.isFollowUp("tell me more"));
        assertTrue(PromptClassifier.isFollowUp("elaborate"));
    }

    @Test
    void social_patterns_are_not_follow_ups() {
        assertFalse(PromptClassifier.isFollowUp("what about you"));
        assertFalse(PromptClassifier.isFollowUp("thanks"));
        assertFalse(PromptClassifier.isFollowUp("bye"));
    }

    @Test
    void non_continuation_is_not_follow_up() {
        assertFalse(PromptClassifier.isFollowUp("hey"));
        assertFalse(PromptClassifier.isFollowUp("I am bored"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Quoted "show me" paths (B: quoted path support)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "show me \"docs/My Guide.md\"",
        "show me 'build.gradle.kts'",
        "show me \"src/main/java/Foo.java\"",
    })
    void show_me_quoted_file_routes_to_command(String input) {
        assertEquals(COMMAND, PromptClassifier.route(input),
                "Quoted show-me-file '" + input + "' should route to COMMAND");
    }

    @Test
    void show_me_quoted_non_file_is_not_command() {
        assertEquals(ASSIST, PromptClassifier.route("show me \"how to build\""));
        assertEquals(ASSIST, PromptClassifier.route("show me \"some random text\""));
    }

    @Test
    void show_me_unquoted_spaced_path_falls_through_to_retrieve() {
        assertEquals(RETRIEVE, PromptClassifier.route("show me docs/My Guide.md"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Expanded ANCHORED_TECH_NOUN (C: language-level constructs)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "what does the constructor do",
        "where is the record defined",
        "what are the dependencies",
    })
    void language_construct_nouns_trigger_retrieval(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Language construct '" + input + "' should trigger retrieval");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "the constructor is complex",
        "the record looks fine",
        "the implementation is clever",
    })
    void language_construct_statements_stay_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Statement '" + input + "' should NOT trigger retrieval");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Continuation prefix follow-ups (D: prefix stripping)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "actually, what about the constructor?",
        "cool, and the parser?",
        "ok, what about that",
    })
    void continuation_prefix_follow_ups_after_retrieve(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input, RETRIEVE),
                "Prefixed follow-up '" + input + "' after RETRIEVE should stay RETRIEVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ok, thanks",
        "sure, bye",
        "yeah, thank you",
    })
    void social_with_prefix_after_retrieve_still_breaks_context(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input, RETRIEVE),
                "Social '" + input + "' after RETRIEVE should break to ASSIST");
    }

    @Test
    void one_more_is_follow_up_after_retrieve() {
        assertEquals(RETRIEVE, PromptClassifier.route("one more thing about that file", RETRIEVE));
        assertEquals(RETRIEVE, PromptClassifier.route("one more", RETRIEVE));
    }

    @Test
    void one_more_without_context_stays_assist() {
        assertEquals(ASSIST, PromptClassifier.route("one more thing about that file"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Extended prefix stripping in isQuestionLike
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void extended_prefix_stripped_for_question_detection() {
        assertTrue(PromptClassifier.isQuestionLike("sure, explain the pipeline"));
        assertTrue(PromptClassifier.isQuestionLike("actually, how does it work"));
        assertTrue(PromptClassifier.isQuestionLike("yep, explain the constructor"));
    }

    @Test
    void extended_prefix_does_not_create_false_question() {
        assertFalse(PromptClassifier.isQuestionLike("sure, I agree"));
        assertFalse(PromptClassifier.isQuestionLike("actually, never mind"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Extended isFollowUp helper
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void continuation_prefix_stripped_for_follow_up_detection() {
        assertTrue(PromptClassifier.isFollowUp("actually, what about it"));
        assertTrue(PromptClassifier.isFollowUp("ok, elaborate"));
        assertTrue(PromptClassifier.isFollowUp("sure, what else"));
    }

    @Test
    void continuation_prefix_social_still_not_follow_up() {
        assertFalse(PromptClassifier.isFollowUp("ok, thanks"));
        assertFalse(PromptClassifier.isFollowUp("actually, thank you"));
    }

    @Test
    void one_more_patterns_are_follow_ups() {
        assertTrue(PromptClassifier.isFollowUp("one more thing"));
        assertTrue(PromptClassifier.isFollowUp("one more"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  End-to-end: realistic multi-turn sequences
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void multi_turn_retrieval_with_prefixed_follow_ups() {
        assertEquals(RETRIEVE, PromptClassifier.route("what does RagService do"));
        assertEquals(RETRIEVE, PromptClassifier.route("cool, and the parser?", RETRIEVE));
        assertEquals(RETRIEVE, PromptClassifier.route("actually, what about the constructor?", RETRIEVE));
        assertEquals(ASSIST, PromptClassifier.route("ok, thanks", RETRIEVE));
    }

    @Test
    void prefixed_question_with_new_tech_noun_triggers_retrieval_independently() {
        assertEquals(RETRIEVE, PromptClassifier.route("actually, what does the constructor do"));
        assertEquals(RETRIEVE, PromptClassifier.route("right, where is the record"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Workspace-aware PascalCase resolution (Layer 2c)
    // ═══════════════════════════════════════════════════════════════════════

    private static final WorkspaceSymbolChecker WORKSPACE_CHECKER = symbol -> {
        String lower = symbol.toLowerCase(java.util.Locale.ROOT);
        return switch (lower) {
            case "ragservice", "modecontroller", "contextpacker",
                 "retrievalpipeline", "promptrouter", "devmode",
                 "lucenestore", "chunkmetadata" -> true;
            default -> false;
        };
    };

    private static final WorkspaceSymbolChecker EMPTY_CHECKER = symbol -> false;

    // ── Bare PascalCase in workspace → RETRIEVE ──────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"RagService", "ModeController", "ContextPacker"})
    void bare_workspace_symbol_triggers_retrieval_with_checker(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input, null, WORKSPACE_CHECKER),
                "Bare workspace symbol '" + input + "' should trigger retrieval when checker confirms");
    }

    // ── PascalCase NOT in workspace → ASSIST ─────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"PowerPoint", "YouTube", "MaryJane"})
    void bare_brand_name_stays_assist_even_with_checker(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input, null, WORKSPACE_CHECKER),
                "Brand name '" + input + "' should NOT trigger retrieval even with checker");
    }

    // ── Workspace symbol in sentence context ─────────────────────────────

    @Test
    void workspace_symbol_in_casual_sentence_triggers_retrieval() {
        assertEquals(RETRIEVE, PromptClassifier.route("I was looking at RagService", null, WORKSPACE_CHECKER));
        assertEquals(RETRIEVE, PromptClassifier.route("tell me about ContextPacker", null, WORKSPACE_CHECKER));
    }

    @Test
    void brand_name_in_casual_sentence_stays_assist() {
        assertEquals(ASSIST, PromptClassifier.route("I use PowerPoint daily", null, WORKSPACE_CHECKER));
    }

    // ── No checker: falls back to original behavior ──────────────────────

    @Test
    void bare_workspace_symbol_stays_assist_without_checker() {
        assertEquals(ASSIST, PromptClassifier.route("RagService", null, null));
        assertEquals(ASSIST, PromptClassifier.route("ModeController"));
    }

    // ── Empty checker: no index → ASSIST ─────────────────────────────────

    @Test
    void bare_symbol_stays_assist_with_empty_checker() {
        assertEquals(ASSIST, PromptClassifier.route("RagService", null, EMPTY_CHECKER));
    }

    // ── Question + workspace symbol still works (Layer 2b fires first) ───

    @Test
    void question_with_workspace_symbol_triggers_via_layer_2b() {
        assertEquals(RETRIEVE, PromptClassifier.route("what does RagService do", null, EMPTY_CHECKER));
    }

    // ── Multiple PascalCase tokens: any match triggers ───────────────────

    @Test
    void any_workspace_symbol_among_multiple_pascal_case_triggers() {
        assertEquals(RETRIEVE, PromptClassifier.route("FooBar and RagService", null, WORKSPACE_CHECKER));
        assertEquals(ASSIST, PromptClassifier.route("FooBar and BazQuux", null, WORKSPACE_CHECKER));
    }

    // ── Workspace-aware routing with conversation context ─────────────────

    @Test
    void workspace_symbol_overrides_assist_context() {
        assertEquals(RETRIEVE, PromptClassifier.route("RagService", ASSIST, WORKSPACE_CHECKER));
    }

    @Test
    void workspace_symbol_with_retrieve_context_still_retrieves() {
        assertEquals(RETRIEVE, PromptClassifier.route("ModeController", RETRIEVE, WORKSPACE_CHECKER));
    }

    // ── Workspace-aware: stronger signals still take priority ─────────────

    @Test
    void file_ref_takes_priority_over_workspace_check() {
        assertEquals(RETRIEVE, PromptClassifier.route("RagService.java", null, EMPTY_CHECKER));
    }

    @Test
    void command_takes_priority_over_workspace_check() {
        assertEquals(COMMAND, PromptClassifier.route("show build.gradle.kts", null, WORKSPACE_CHECKER));
    }

    // ── Edge: null/blank with checker ─────────────────────────────────────

    @Test
    void null_input_routes_to_assist_with_checker() {
        assertEquals(ASSIST, PromptClassifier.route(null, null, WORKSPACE_CHECKER));
    }

    @Test
    void blank_input_routes_to_assist_with_checker() {
        assertEquals(ASSIST, PromptClassifier.route("", null, WORKSPACE_CHECKER));
        assertEquals(ASSIST, PromptClassifier.route("   ", null, WORKSPACE_CHECKER));
    }

    // ── Backward compatibility: 2-arg route delegates to 3-arg ───────────

    @Test
    void two_arg_route_is_backward_compatible() {
        assertEquals(ASSIST, PromptClassifier.route("RagService", null));
        assertEquals(RETRIEVE, PromptClassifier.route("what does RagService do", null));
        assertEquals(RETRIEVE, PromptClassifier.route("what about the parse method?", RETRIEVE));
        assertEquals(ASSIST, PromptClassifier.route("thanks", RETRIEVE));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Action-intent: end-to-end multi-turn sequences
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void multi_turn_action_then_follow_up() {
        assertEquals(RETRIEVE, PromptClassifier.route("write a test for RagService"));
        assertEquals(RETRIEVE, PromptClassifier.route("what about edge cases?", RETRIEVE));
        assertEquals(ASSIST, PromptClassifier.route("thanks", RETRIEVE));
    }

    @Test
    void action_after_assist_triggers_retrieval_independently() {
        assertEquals(RETRIEVE, PromptClassifier.route("refactor the parser", ASSIST));
        assertEquals(RETRIEVE, PromptClassifier.route("refactor ModeController", ASSIST));
    }

    @Test
    void action_with_workspace_checker() {
        assertEquals(RETRIEVE, PromptClassifier.route("refactor RagService", null, WORKSPACE_CHECKER));
        assertEquals(ASSIST, PromptClassifier.route("write a poem", null, WORKSPACE_CHECKER));
    }

    @Test
    void action_with_file_reference_already_routes() {
        // Mutation verb + file ref (no PascalCase) → ASSIST (tools)
        assertEquals(ASSIST, PromptClassifier.route("edit build.gradle.kts"));
        // Mutation verb + file ref with PascalCase → RETRIEVE (needs code context)
        assertEquals(RETRIEVE, PromptClassifier.route("fix RagService.java"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Expanded workspace framing (G14 fix)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "what is this site about",
        "describe my app",
        "what's in this folder",
    })
    void expanded_workspace_framing_routes_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Workspace framing '" + input + "' should trigger retrieval");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Expanded anchored tech nouns (G14 fix)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "explain the page layout",
        "how does the component work",
        "how does the adapter work",
    })
    void expanded_tech_nouns_with_question_route_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Tech noun question '" + input + "' should trigger retrieval");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Expanded action verbs (G14 fix)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "inspect the RagService",
        "review ModeController",
        "find RagService usages",
        "document the ConversationCompactor",
    })
    void expanded_action_verbs_with_pascal_case_route_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Action verb with PascalCase '" + input + "' should trigger retrieval");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "inspect the pipeline",
        "analyze the component hierarchy",
    })
    void expanded_action_verbs_with_tech_noun_route_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Action verb with tech noun '" + input + "' should trigger retrieval");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "inspect my car",
        "review the movie",
        "explore the universe",
    })
    void expanded_action_verbs_without_workspace_signals_route_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Action verb without workspace signal '" + input + "' should route to ASSIST");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Empty-retrieval guidance
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void check_out_youtube_still_routes_to_assist() {
        assertEquals(ASSIST, PromptClassifier.route("check out YouTube"));
        assertEquals(ASSIST, PromptClassifier.route("check this out"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Workspace proximity: "here", "workspace", "working on" (G14b fix)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "what am I working on here?",
        "what's here",
        "what files are here",
    })
    void here_in_question_routes_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "'" + input + "' should trigger retrieval — 'here' = the workspace");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "what workspace is this?",
        "describe this workspace",
        "explain the workspace",
    })
    void workspace_in_question_routes_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "'" + input + "' should trigger retrieval — mentions 'workspace'");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "what am I working on?",
        "show me what I'm working on",
    })
    void working_on_in_question_routes_to_retrieve(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "'" + input + "' should trigger retrieval — 'working on' = current project");
    }

    @ParameterizedTest
    @ValueSource(strings = {"I'm here to help", "I am here", "hello, I'm here"})
    void here_without_question_stays_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "'" + input + "' should stay ASSIST — 'here' without question context");
    }

    @Test
    void workspace_without_question_stays_assist() {
        assertEquals(ASSIST, PromptClassifier.route("I like workspaces in general"));
        assertEquals(ASSIST, PromptClassifier.route("workspace is a cool concept"));
    }

    @Test
    void working_on_without_question_stays_assist() {
        assertEquals(ASSIST, PromptClassifier.route("I'm working on something"));
        assertEquals(ASSIST, PromptClassifier.route("still working on it"));
    }

    @Test
    void real_session_transcript_questions_route_correctly() {
        // These are the exact questions from the failing user session
        assertEquals(RETRIEVE, PromptClassifier.route("what am I working on here?"),
                "Real session Q1 should RETRIEVE");
        assertEquals(RETRIEVE, PromptClassifier.route("do you know what workspace this is?"),
                "Real session Q3 should RETRIEVE");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ACTION VERB GATE: mutation/inspection → ASSIST (tool-calling path)
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "create a new file called settings.json",
        "write a hello.py with Flask",
        "generate a README.md for this project",
    })
    void file_creation_actions_route_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "File creation '" + input + "' must route to ASSIST (tools), not RETRIEVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "delete the old config.json",
        "rename Main.java to App.java",
        "move utils.py to the lib folder",
    })
    void file_mutation_actions_route_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "File mutation '" + input + "' must route to ASSIST (tools), not RETRIEVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "list the files in this directory",
        "search for TODO comments",
        "grep for SMOKEPROBE in the project",
        "scan the directory structure",
    })
    void inspection_actions_route_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Inspection '" + input + "' must route to ASSIST (tools), not RETRIEVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "delete the test",
        "move the controller",
        "list the directory",
    })
    void mutation_verbs_override_anchored_nouns_to_assist(String input) {
        assertEquals(ASSIST, PromptClassifier.route(input),
                "Mutation '" + input + "' must route to ASSIST (tools) even with tech noun");
    }

    @Test
    void exact_failing_prompts_now_route_to_assist() {
        assertEquals(ASSIST, PromptClassifier.route("create a new empty file in this workspace called settings.json"));
        assertEquals(ASSIST, PromptClassifier.route("list the files in the directory please"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "what does Main.java do?",
        "explain the Config.java file",
        "describe settings.json",
    })
    void information_questions_about_files_still_retrieve(String input) {
        assertEquals(RETRIEVE, PromptClassifier.route(input),
                "Information question '" + input + "' should still RETRIEVE");
    }

    // ── isMutationOrInspection unit tests ───────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "create a file",
        "delete the old one",
        "rename the file",
        "list all files",
        "search for TODO",
        "grep for errors",
        "edit the file",
        "update the config",
        "fix the bug",
        "change the layout",
        "improve the styling",
        "modify the header",
        "overwrite index.html",
        "rewrite the css",
    })
    void isMutationOrInspection_true(String input) {
        assertTrue(PromptClassifier.isMutationOrInspection(input),
                "'" + input + "' should be mutation/inspection");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "refactor the parser",
        "explain how it works",
        "what is a binary tree",
    })
    void isMutationOrInspection_false(String input) {
        assertFalse(PromptClassifier.isMutationOrInspection(input),
                "'" + input + "' should NOT be mutation/inspection");
    }
}
