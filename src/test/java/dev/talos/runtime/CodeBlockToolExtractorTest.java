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

        @Test void false_plain() {
            assertFalse(CodeBlockToolExtractor.containsExtractableBlocks(
                    "```json\n{}\n```"));
        }

        @Test void false_null() {
            assertFalse(CodeBlockToolExtractor.containsExtractableBlocks(null));
        }
    }
}

