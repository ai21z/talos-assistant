package dev.talos.core.index;

import org.junit.jupiter.api.Test;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test glob-to-regex conversion for subdirectory matching.
 */
class GlobMatchingTest {

    @Test
    void testDoubleStarGlobMatching() {
        // Simulate the FIXED implementation with proper placeholder handling
        String glob = "**/*.md";
        String regex = glob.toLowerCase()
            .replace(".", "\\.")
            // Use unique placeholders to prevent interference
            .replace("**/", "__DOUBLESTAR_SLASH__")
            .replace("**", "__DOUBLESTAR__")
            .replace("*", "[^/]*")
            // Now replace placeholders with actual regex (no more * chars to interfere)
            .replace("__DOUBLESTAR_SLASH__", "(?:.*/)?")
            .replace("__DOUBLESTAR__", ".*");

        System.out.println("Generated regex: ^" + regex + "$");
        Pattern pattern = Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE);

        // These should match
        assertTrue(pattern.matcher("readme.md").matches(), "Should match root-level .md");
        assertTrue(pattern.matcher("docs/landing.md").matches(), "Should match subdirectory .md");
        assertTrue(pattern.matcher("docs/nested/deep/file.md").matches(), "Should match deeply nested .md");

        // These should NOT match
        assertFalse(pattern.matcher("readme.txt").matches(), "Should not match .txt");
        assertFalse(pattern.matcher("docs/file.java").matches(), "Should not match .java");
    }

    @Test
    void testSingleStarGlobMatching() {
        String glob = "*.md";
        String regex = glob.toLowerCase()
            .replace(".", "\\.")
            .replace("*", "[^/]*");
        Pattern pattern = Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE);

        // These should match
        assertTrue(pattern.matcher("readme.md").matches(), "Should match root-level .md");

        // These should NOT match (single * shouldn't cross directories)
        assertFalse(pattern.matcher("docs/landing.md").matches(), "Should NOT match subdirectory .md");
    }
}
