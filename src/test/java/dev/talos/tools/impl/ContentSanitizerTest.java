package dev.talos.tools.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ContentSanitizer}: stripping trailing markdown commentary
 * that LLMs accidentally include in tool content parameters.
 */
class ContentSanitizerTest {

    // ═══════════════════════════════════════════════════════════════════════
    //  Happy path: trailing markdown stripped
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class TrailingMarkdownStripped {

        @Test
        void html_with_trailing_headings_and_bullets() {
            String content = """
                    <!DOCTYPE html>
                    <html>
                    <body><h1>Hello</h1></body>
                    </html>
                    ```

                    ### Key Changes and Improvements:

                    1. **Structure:** Improved the layout.
                    2. **Styling:** Added modern CSS.
                    """;
            String result = ContentSanitizer.sanitize(content, "index.html");

            assertTrue(result.contains("</html>"), "Should keep the HTML content");
            assertFalse(result.contains("Key Changes"), "Should strip markdown commentary");
            assertFalse(result.contains("```"), "Should strip the stray fence");
        }

        @Test
        void css_with_trailing_numbered_list() {
            String content = """
                    body { color: red; }
                    .card { padding: 10px; }
                    ```

                    **Explanation of Changes:**
                    1. **Improved Styling:** Added modern CSS rules.
                    2. **Focus on Structure:** Better centering.
                    """;
            String result = ContentSanitizer.sanitize(content, "styles.css");

            assertTrue(result.contains("body { color: red; }"));
            assertFalse(result.contains("Explanation of Changes"));
        }

        @Test
        void javascript_with_trailing_explanation() {
            String content = """
                    function hello() {
                        console.log("hi");
                    }
                    ```

                    ### Summary
                    - This function logs a greeting.
                    - It takes no parameters.
                    """;
            String result = ContentSanitizer.sanitize(content, "app.js");

            assertTrue(result.contains("console.log"));
            assertFalse(result.contains("Summary"));
            assertFalse(result.contains("This function logs"));
        }

        @Test
        void fence_with_language_tag_stripped() {
            String content = """
                    <div>Hello</div>
                    ```html

                    ### Changes
                    - Updated the div content.
                    """;
            String result = ContentSanitizer.sanitize(content, "page.html");

            assertTrue(result.contains("<div>Hello</div>"));
            assertFalse(result.contains("Changes"));
        }

        @Test
        void trailing_reminder_text_stripped() {
            String content = """
                    h1 { font-size: 2em; }
                    ```

                    **Remember to replace your existing CSS with this structure.**
                    """;
            String result = ContentSanitizer.sanitize(content, "style.css");

            assertTrue(result.contains("h1 { font-size: 2em; }"));
            assertFalse(result.contains("Remember"));
        }

        @Test
        void trailing_to_use_instruction_stripped() {
            String content = """
                    <p>Hello World</p>
                    ```

                    **To use this code:** Copy the entire block and save it as an HTML file.
                    """;
            String result = ContentSanitizer.sanitize(content, "page.html");

            assertTrue(result.contains("<p>Hello World</p>"));
            assertFalse(result.contains("To use this code"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Markdown file exemption
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class MarkdownExemption {

        @Test
        void md_file_content_preserved_unchanged() {
            String content = """
                    # README
                    
                    ```java
                    System.out.println("hello");
                    ```
                    
                    ### Notes
                    - This is valid markdown.
                    """;
            String result = ContentSanitizer.sanitize(content, "README.md");
            assertEquals(content, result, ".md files should be exempt from sanitization");
        }

        @Test
        void markdown_extension_preserved() {
            String content = "# Title\n```\n### Section\n- item\n";
            assertEquals(content, ContentSanitizer.sanitize(content, "docs/guide.markdown"));
        }

        @Test
        void mdx_extension_preserved() {
            String content = "# Title\n```\n### Section\n- item\n";
            assertEquals(content, ContentSanitizer.sanitize(content, "page.mdx"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  No trailing fence: content unchanged
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class NoFenceUnchanged {

        @Test
        void clean_html_content_unchanged() {
            String content = """
                    <!DOCTYPE html>
                    <html>
                    <body><h1>Hello</h1></body>
                    </html>
                    """;
            assertEquals(content, ContentSanitizer.sanitize(content, "index.html"));
        }

        @Test
        void clean_css_content_unchanged() {
            String content = "body { color: red; }\n.card { padding: 10px; }\n";
            assertEquals(content, ContentSanitizer.sanitize(content, "styles.css"));
        }

        @Test
        void content_without_fence_but_with_markdown_chars() {
            String content = "# This is a CSS comment\nbody { color: #333; }\n";
            assertEquals(content, ContentSanitizer.sanitize(content, "style.css"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Conservative: non-markdown after fence → unchanged
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ConservativeNoStrip {

        @Test
        void fence_followed_by_code_left_unchanged() {
            // A file that legitimately contains a code fence (e.g., a template)
            String content = """
                    <pre>
                    ```
                    function hello() {}
                    </pre>
                    """;
            assertEquals(content, ContentSanitizer.sanitize(content, "template.html"));
        }

        @Test
        void fence_followed_by_mixed_content_left_unchanged() {
            String content = """
                    body { color: red; }
                    ```
                    more css code here
                    ### This is not purely markdown
                    """;
            // "more css code here" doesn't look like markdown, so nothing stripped
            assertEquals(content, ContentSanitizer.sanitize(content, "styles.css"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class EdgeCases {

        @Test
        void null_content_returns_null() {
            assertNull(ContentSanitizer.sanitize(null, "file.html"));
        }

        @Test
        void empty_content_returns_empty() {
            assertEquals("", ContentSanitizer.sanitize("", "file.html"));
        }

        @Test
        void null_path_still_sanitizes() {
            String content = """
                    <p>Hello</p>
                    ```

                    ### Notes
                    - Item one
                    """;
            String result = ContentSanitizer.sanitize(content, null);
            assertFalse(result.contains("Notes"), "Should still sanitize when path is null");
        }

        @Test
        void fence_at_very_end_no_following_text_unchanged() {
            String content = "body { color: red; }\n```";
            assertEquals(content, ContentSanitizer.sanitize(content, "style.css"));
        }

        @Test
        void only_blank_lines_after_fence_unchanged() {
            String content = "body { color: red; }\n```\n\n\n";
            assertEquals(content, ContentSanitizer.sanitize(content, "style.css"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Real-world patterns from test-output.txt
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class RealWorldPatterns {

        @Test
        void write_file_content_with_explanation_block() {
            // Pattern observed in test-output.txt Turn 6 / Turn 8
            String content = """
                    .container {
                        max-width: 1200px;
                        margin: 0 auto;
                    }
                    .info-box {
                        background-color: #e9ecef;
                        padding: 15px;
                    }
                    ```

                    **Explanation of Changes:**
                    1. **Improved Styling:** Added modern CSS rules for input focus and buttons.
                    2. **Focus on Structure:** The structure assumes a container for centering.
                    3. **CSS Context:** Consolidated CSS block for the main HTML file.
                    """;

            String result = ContentSanitizer.sanitize(content, "styles.css");

            assertTrue(result.contains(".container"), "Should keep CSS content");
            assertTrue(result.contains(".info-box"), "Should keep CSS content");
            assertFalse(result.contains("Explanation of Changes"), "Should strip explanation");
            assertFalse(result.contains("Improved Styling"), "Should strip numbered list");
        }

        @Test
        void html_with_key_changes_commentary() {
            String content = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head><title>BMI Calculator</title></head>
                    <body>
                    <div class="calculator-container">
                        <h1>BMI Calculator</h1>
                    </div>
                    </body>
                    </html>
                    ```

                    ### Key Changes and Improvements:

                    1.  **Structure & Aesthetics:** Wrapped content in a container class.
                    2.  **Validation:** Added robust JavaScript validation.
                    3.  **Category Refinement:** Better color coding for BMI categories.

                    This final version is a complete, standalone HTML file.
                    """;

            String result = ContentSanitizer.sanitize(content, "index.html");

            assertTrue(result.contains("</html>"), "Should keep HTML content");
            assertFalse(result.contains("Key Changes"), "Should strip heading");
            assertFalse(result.contains("Structure & Aesthetics"), "Should strip explanation");
            assertFalse(result.contains("standalone HTML file"), "Should strip trailing sentence");
        }
    }
}

