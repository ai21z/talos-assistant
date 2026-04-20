package dev.talos.cli.modes;

import org.junit.jupiter.api.Test;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test auto-mode intent detection patterns for routing queries to the right mode.
 */
class AutoModeIntentRoutingTest {


    private static final Pattern TRIVIAL_QUERY_PATTERN = Pattern.compile(
        "(?i)(?:how many|count)\\s+['\"]?[a-z]['\"]?\\s+in\\s+|" +
        "(?:spell|define|what is|what does|who is|who was|when did)\\s+|" +
        "(?:calculate|compute|solve)\\s+|" +
        "\\d+\\s*[+\\-*/]\\s*\\d+"
    );

    @Test
    void listFilesQueriesRouteToAssistForToolHandling() {
        // "list files" queries route through PromptClassifier normally.
        // "what files are here?" now routes to RETRIEVE because "here" is
        // a workspace proximity signal — the user is asking about THIS workspace.
        assertEquals(PromptClassifier.Route.RETRIEVE,
                PromptClassifier.route("what files are here?"));
        assertEquals(PromptClassifier.Route.ASSIST,
                PromptClassifier.route("list all files"));
        assertEquals(PromptClassifier.Route.ASSIST,
                PromptClassifier.route("which files are indexed"));
        assertEquals(PromptClassifier.Route.ASSIST,
                PromptClassifier.route("what docs are available"));

        // "show files" routes to COMMAND (DEV_COMMAND pattern matches "show <non-excluded>")
        assertEquals(PromptClassifier.Route.COMMAND,
                PromptClassifier.route("show files"));
    }

    @Test
    void testTrivialQueryDetection() {
        // Should match trivial/non-workspace queries
        assertTrue(TRIVIAL_QUERY_PATTERN.matcher("How many 'r' in strawberry?").find());
        assertTrue(TRIVIAL_QUERY_PATTERN.matcher("count 'e' in 'hello'").find());
        assertTrue(TRIVIAL_QUERY_PATTERN.matcher("what is polymorphism").find());
        assertTrue(TRIVIAL_QUERY_PATTERN.matcher("define recursion").find());
        assertTrue(TRIVIAL_QUERY_PATTERN.matcher("who is Linus Torvalds").find());
        assertTrue(TRIVIAL_QUERY_PATTERN.matcher("calculate 15 + 27").find());
        assertTrue(TRIVIAL_QUERY_PATTERN.matcher("solve 100 * 5").find());
        
        // Should NOT match workspace queries
        assertFalse(TRIVIAL_QUERY_PATTERN.matcher("Summarize README.md").find());
        assertFalse(TRIVIAL_QUERY_PATTERN.matcher("Compare these two files").find());
    }

    @Test
    void testFileTokenDetection() {
        // Should detect file-like tokens
        assertTrue(containsFileTokens("summarize README.md"));
        assertTrue(containsFileTokens("compare file1.java and file2.java"));
        assertTrue(containsFileTokens("what's in config.yaml?"));
        
        // Should NOT detect in trivial queries
        assertFalse(containsFileTokens("How many 'r' in strawberry?"));
        assertFalse(containsFileTokens("what is polymorphism"));
    }

    private static boolean containsFileTokens(String rawLine) {
        return rawLine.matches(".*\\b\\w+\\.(java|md|txt|yaml|yml|json|xml|properties|html|js|py|go|rs|cpp)\\b.*");
    }
}

