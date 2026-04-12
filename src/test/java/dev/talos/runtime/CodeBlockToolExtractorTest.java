package dev.talos.runtime;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeBlockToolExtractorTest {

    @Nested
    @DisplayName("extract — inline filename patterns")
    class InlineFilename {

        @Test void cStyleComment_withLang() {
            String r = "Here:\n```json // settings.json\n{ \"key\": \"value\" }\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertEquals(1, calls.size());
            assertEquals("talos.write_file", calls.get(0).toolName());
            assertEquals("settings.json", calls.get(0).param("path"));
            assertTrue(calls.get(0).param("content").contains("\"key\""));
        }

        @Test void shellComment_withLang() {
            String r = "```python # src/main.py\nprint(\"hello\")\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertEquals(1, calls.size());
            assertEquals("src/main.py", calls.get(0).param("path"));
        }

        @Test void cStyleComment_noLang() {
            String r = "```// config.yaml\nserver:\n  port: 8080\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertEquals(1, calls.size());
            assertEquals("config.yaml", calls.get(0).param("path"));
        }

        @Test void filenamePrefix() {
            String r = "```java filename: src/App.java\npublic class App {}\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertEquals(1, calls.size());
            assertEquals("src/App.java", calls.get(0).param("path"));
        }

        @Test void multipleBlocks() {
            String r = "```json // a.json\n{}\n```\ntext\n```java // B.java\nclass B {}\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertEquals(2, calls.size());
            assertEquals("a.json", calls.get(0).param("path"));
            assertEquals("B.java", calls.get(1).param("path"));
        }
    }

    @Nested
    @DisplayName("extract — preceding filename")
    class PrecedingFilename {

        @Test void backtickFilename_colon() {
            String r = "Create `build.gradle.kts`:\n```kotlin\nplugins { id(\"java\") }\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertEquals(1, calls.size());
            assertEquals("build.gradle.kts", calls.get(0).param("path"));
        }
    }

    @Nested
    @DisplayName("extract — heading/prose filename")
    class HeadingFilename {

        @Test
        @DisplayName("heading with backtick filename + blank line + fence")
        void heading_blankLine_fence() {
            String r = "### Updated `index.html`\n\n```html\n<p>Hello</p>\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertEquals(1, calls.size());
            assertEquals("talos.write_file", calls.get(0).toolName());
            assertEquals("index.html", calls.get(0).param("path"));
            assertTrue(calls.get(0).param("content").contains("<p>Hello</p>"));
        }

        @Test
        @DisplayName("heading with emoji + extra text around filename")
        void heading_emoji_extraText() {
            String r = "### ✅ `styles.css` (Copy This Entire Block)\n\nModern CSS:\n\n```css\nbody { color: red; }\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertEquals(1, calls.size());
            assertEquals("styles.css", calls.get(0).param("path"));
            assertTrue(calls.get(0).param("content").contains("body { color: red; }"));
        }

        @Test
        @DisplayName("prose paragraph mentions filename before heading + fence")
        void prose_then_heading_then_fence() {
            String r = "Please replace your `index.html` content.\n\n### Updated `index.html`\n\n```html\n<h1>New</h1>\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            // Dedup: only one call for index.html even though mentioned twice
            assertEquals(1, calls.size());
            assertEquals("index.html", calls.get(0).param("path"));
        }

        @Test
        @DisplayName("no match: plain prose without backtick filename")
        void no_backtick_filename() {
            String r = "Here is the complete file:\n\n```html\n<p>Hello</p>\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertTrue(calls.isEmpty(), "No backtick-quoted filename → no extraction");
        }

        @Test
        @DisplayName("no match: filename too far from fence (6+ lines)")
        void filename_too_far() {
            String r = "### Updated `index.html`\n\nline1\nline2\nline3\nline4\nline5\n```html\n<p>Hello</p>\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertTrue(calls.isEmpty(), "Filename 6+ lines before fence should not match");
        }

        @Test
        @DisplayName("heading with path in subdirectory")
        void heading_with_path() {
            String r = "### Updated `src/app.js`\n\n```javascript\nconsole.log('hi');\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertEquals(1, calls.size());
            assertEquals("src/app.js", calls.get(0).param("path"));
        }

        @Test
        @DisplayName("bold text with filename in prose")
        void bold_filename_prose() {
            String r = "Save this as **`config.yaml`**:\n\n```yaml\nkey: value\n```\n";
            // Note: the backtick filename `config.yaml` is preceded by **
            // but our regex looks for ` not ** — let's verify the ** case.
            // The pattern matches `config.yaml` inside **`config.yaml`**
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertEquals(1, calls.size());
            assertEquals("config.yaml", calls.get(0).param("path"));
        }
    }

    @Nested
    @DisplayName("extract — no match")
    class NoMatch {

        @Test void plainBlock() {
            assertTrue(CodeBlockToolExtractor.extract("```java\ncode\n```").isEmpty());
        }

        @Test void nullInput() {
            assertTrue(CodeBlockToolExtractor.extract(null).isEmpty());
        }

        @Test void emptyInput() {
            assertTrue(CodeBlockToolExtractor.extract("").isEmpty());
        }

        @Test void noBlocks() {
            assertTrue(CodeBlockToolExtractor.extract("Just text.").isEmpty());
        }
    }

    @Nested
    @DisplayName("extract — edge cases")
    class EdgeCases {

        @Test void deduplicates_samePath() {
            String r = "```json // c.json\n{\"a\":1}\n```\n```json // c.json\n{\"a\":2}\n```\n";
            assertEquals(1, CodeBlockToolExtractor.extract(r).size());
        }

        @Test void ignores_parentTraversal() {
            String r = "```json // ../../etc/passwd\nroot:x\n```\n";
            assertTrue(CodeBlockToolExtractor.extract(r).isEmpty());
        }

        @Test void multilineContent() {
            String r = "```java // Hello.java\npublic class Hello {\n    void hi() {}\n}\n```\n";
            List<ToolCall> calls = CodeBlockToolExtractor.extract(r);
            assertEquals(1, calls.size());
            assertTrue(calls.get(0).param("content").contains("class Hello"));
        }
    }

    @Nested
    @DisplayName("containsExtractableBlocks")
    class ContainsCheck {

        @Test void true_inline() {
            assertTrue(CodeBlockToolExtractor.containsExtractableBlocks(
                    "```json // t.json\n{}\n```"));
        }

        @Test void true_preceding() {
            assertTrue(CodeBlockToolExtractor.containsExtractableBlocks(
                    "`t.json`:\n```json\n{}\n```"));
        }

        @Test void true_heading() {
            assertTrue(CodeBlockToolExtractor.containsExtractableBlocks(
                    "### Updated `index.html`\n\n```html\n<p>Hi</p>\n```"));
        }

        @Test void false_plain() {
            assertFalse(CodeBlockToolExtractor.containsExtractableBlocks(
                    "```json\n{}\n```"));
        }

        @Test void false_null() {
            assertFalse(CodeBlockToolExtractor.containsExtractableBlocks(null));
        }
    }
}

