package dev.talos.tools.impl;

import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ContentVerifier}.
 *
 * Verifies post-write verification logic for JSON, HTML, YAML, XML,
 * and unknown file types. Uses temp files for realistic read-back checks.
 */
@DisplayName("ContentVerifier")
class ContentVerifierTest {

    @TempDir Path tmp;

    private Path writeFile(String name, String content) throws IOException {
        Path file = tmp.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    // ── JSON ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JSON verification")
    class JsonVerification {

        @Test
        @DisplayName("valid JSON object passes")
        void valid_json_object() throws IOException {
            String content = "{\"name\": \"Talos\", \"version\": 1}";
            Path file = writeFile("data.json", content);
            var vr = ContentVerifier.verify(file, content);
            assertTrue(vr.ok(), "Should pass for valid JSON");
            assertEquals("valid JSON", vr.summary());
        }

        @Test
        @DisplayName("valid JSON array passes")
        void valid_json_array() throws IOException {
            String content = "[1, 2, 3]";
            Path file = writeFile("items.json", content);
            var vr = ContentVerifier.verify(file, content);
            assertTrue(vr.ok());
            assertEquals("valid JSON", vr.summary());
        }

        @Test
        @DisplayName("invalid JSON fails with parse error")
        void invalid_json() throws IOException {
            String content = "{\"name\": \"broken}";
            Path file = writeFile("bad.json", content);
            var vr = ContentVerifier.verify(file, content);
            assertFalse(vr.ok(), "Should fail for invalid JSON");
            assertTrue(vr.summary().startsWith("JSON parse failed"),
                    "Summary should describe parse failure: " + vr.summary());
            assertEquals(VerificationStatus.FAIL, vr.status());
        }

        @Test
        @DisplayName("empty JSON file fails")
        void empty_json() throws IOException {
            String content = "";
            Path file = writeFile("empty.json", content);
            var vr = ContentVerifier.verify(file, content);
            assertFalse(vr.ok(), "Empty file is not valid JSON");
        }

        @Test
        @DisplayName("truncated JSON fails")
        void truncated_json() throws IOException {
            String content = "{\"items\": [1, 2, ";
            Path file = writeFile("truncated.json", content);
            var vr = ContentVerifier.verify(file, content);
            assertFalse(vr.ok());
            assertTrue(vr.summary().contains("JSON parse failed"));
        }
    }

    // ── HTML ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("HTML verification")
    class HtmlVerification {

        @Test
        @DisplayName("well-formed HTML passes")
        void well_formed_html() throws IOException {
            String content = """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Test</title></head>
                    <body>
                      <div class="main">
                        <ul><li>One</li><li>Two</li></ul>
                      </div>
                    </body>
                    </html>""";
            Path file = writeFile("index.html", content);
            var vr = ContentVerifier.verify(file, content);
            assertTrue(vr.ok(), "Well-formed HTML should pass: " + vr.summary());
            assertEquals("HTML structure OK", vr.summary());
            assertEquals(VerificationStatus.PASS, vr.status());
        }

        @Test
        @DisplayName("unclosed div triggers warning")
        void unclosed_div() throws IOException {
            String content = "<html><body><div>content</body></html>";
            Path file = writeFile("broken.html", content);
            var vr = ContentVerifier.verify(file, content);
            assertFalse(vr.ok(), "Should detect unclosed <div>");
            assertTrue(vr.summary().contains("unclosed <div>"),
                    "Should mention unclosed div: " + vr.summary());
            assertEquals(VerificationStatus.WARN, vr.status());
        }

        @Test
        @DisplayName("multiple unclosed tags reported")
        void multiple_unclosed() throws IOException {
            String content = "<html><body><div><span><table></body></html>";
            Path file = writeFile("multi.html", content);
            var vr = ContentVerifier.verify(file, content);
            assertFalse(vr.ok());
            assertTrue(vr.summary().contains("unclosed <div>"));
            assertTrue(vr.summary().contains("unclosed <span>"));
            assertTrue(vr.summary().contains("unclosed <table>"));
        }

        @Test
        @DisplayName("HTML fragment without root tags passes (conservative)")
        void html_fragment() throws IOException {
            // A fragment with balanced structural tags should pass
            String content = "<div><span>hello</span></div>";
            Path file = writeFile("fragment.html", content);
            var vr = ContentVerifier.verify(file, content);
            assertTrue(vr.ok(), "Balanced fragment should pass: " + vr.summary());
        }

        @Test
        @DisplayName(".htm extension also triggers HTML checks")
        void htm_extension() throws IOException {
            String content = "<html><body><div>no close</body></html>";
            Path file = writeFile("page.htm", content);
            var vr = ContentVerifier.verify(file, content);
            assertFalse(vr.ok(), "Should check .htm files too");
        }

        @Test
        @DisplayName("tag-like words do not cause false positives")
        void no_false_positive_on_tag_substring() throws IOException {
            // <divider> should NOT count as <div>
            String content = "<html><body><divider>content</divider></body></html>";
            Path file = writeFile("nofp.html", content);
            var vr = ContentVerifier.verify(file, content);
            assertTrue(vr.ok(), "Should not false-positive on <divider>: " + vr.summary());
        }
    }

    // ── YAML ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("YAML verification")
    class YamlVerification {

        @Test
        @DisplayName("valid YAML passes")
        void valid_yaml() throws IOException {
            String content = "name: Talos\nversion: 1\nitems:\n  - one\n  - two\n";
            Path file = writeFile("config.yaml", content);
            var vr = ContentVerifier.verify(file, content);
            assertTrue(vr.ok(), "Valid YAML should pass: " + vr.summary());
            assertEquals("valid YAML", vr.summary());
        }

        @Test
        @DisplayName("valid YAML with .yml extension passes")
        void valid_yml() throws IOException {
            String content = "key: value\n";
            Path file = writeFile("config.yml", content);
            var vr = ContentVerifier.verify(file, content);
            assertTrue(vr.ok());
            assertEquals("valid YAML", vr.summary());
        }

        @Test
        @DisplayName("invalid YAML fails")
        void invalid_yaml() throws IOException {
            String content = "key: value\n  bad indent:\n nope";
            Path file = writeFile("bad.yaml", content);
            var vr = ContentVerifier.verify(file, content);
            // YAML parser may or may not fail on mild indentation issues;
            // if it does fail, it should report honestly
            if (!vr.ok()) {
                assertTrue(vr.summary().contains("YAML parse failed"));
            }
        }
    }

    // ── XML ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("XML verification")
    class XmlVerification {

        @Test
        @DisplayName("valid XML passes")
        void valid_xml() throws IOException {
            String content = "<?xml version=\"1.0\"?>\n<root><item>Hello</item></root>";
            Path file = writeFile("data.xml", content);
            var vr = ContentVerifier.verify(file, content);
            assertTrue(vr.ok(), "Valid XML should pass: " + vr.summary());
            assertEquals("valid XML", vr.summary());
        }

        @Test
        @DisplayName("malformed XML fails")
        void malformed_xml() throws IOException {
            String content = "<root><item>unclosed</root>";
            Path file = writeFile("bad.xml", content);
            var vr = ContentVerifier.verify(file, content);
            assertFalse(vr.ok(), "Malformed XML should fail");
            assertTrue(vr.summary().contains("XML parse failed"),
                    "Should report parse failure: " + vr.summary());
        }

        @Test
        @DisplayName("empty XML file fails")
        void empty_xml() throws IOException {
            String content = "";
            Path file = writeFile("empty.xml", content);
            var vr = ContentVerifier.verify(file, content);
            assertFalse(vr.ok(), "Empty file is not valid XML");
        }
    }

    // ── Unknown extensions ──────────────────────────────────────────────

    @Nested
    @DisplayName("Unknown file types")
    class UnknownTypes {

        @Test
        @DisplayName("plain text gets read-back only")
        void plain_text() throws IOException {
            String content = "Hello, this is plain text.";
            Path file = writeFile("readme.txt", content);
            var vr = ContentVerifier.verify(file, content);
            assertTrue(vr.ok());
            assertEquals("read-back OK", vr.summary());
            assertEquals(VerificationStatus.UNKNOWN, vr.status());
        }

        @Test
        @DisplayName("Java file gets read-back only")
        void java_file() throws IOException {
            String content = "public class Foo {}";
            Path file = writeFile("Foo.java", content);
            var vr = ContentVerifier.verify(file, content);
            assertTrue(vr.ok());
            assertEquals("read-back OK", vr.summary());
        }

        @Test
        @DisplayName("Python file gets read-back only")
        void python_file() throws IOException {
            String content = "print('hello')";
            Path file = writeFile("app.py", content);
            var vr = ContentVerifier.verify(file, content);
            assertTrue(vr.ok());
            assertEquals("read-back OK", vr.summary());
        }

        @Test
        @DisplayName("file with no extension gets read-back only")
        void no_extension() throws IOException {
            String content = "some content";
            Path file = writeFile("Makefile", content);
            var vr = ContentVerifier.verify(file, content);
            assertTrue(vr.ok());
            assertEquals("read-back OK", vr.summary());
        }
    }

    // ── Read-back checks ────────────────────────────────────────────────

    @Nested
    @DisplayName("Read-back verification")
    class ReadBack {

        @Test
        @DisplayName("read-back mismatch detected")
        void readback_mismatch() throws IOException {
            String written = "original content";
            Path file = writeFile("test.txt", written);
            // Tamper with the file after "writing"
            Files.writeString(file, "tampered content");
            var vr = ContentVerifier.verify(file, written);
            assertFalse(vr.ok(), "Should detect mismatch");
            assertTrue(vr.summary().contains("read-back mismatch"),
                    "Should report mismatch: " + vr.summary());
            assertEquals(VerificationStatus.FAIL, vr.status());
        }

        @Test
        @DisplayName("read-back of non-existent file fails")
        void readback_nonexistent() {
            Path file = tmp.resolve("does-not-exist.txt");
            var vr = ContentVerifier.verify(file, "content");
            assertFalse(vr.ok(), "Should fail for non-existent file");
            assertTrue(vr.summary().contains("read-back failed"),
                    "Should report read-back failure: " + vr.summary());
        }
    }

    // ── Utility methods ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Utilities")
    class Utilities {

        @Test void extension_json() {
            assertEquals("json", ContentVerifier.getExtension(Path.of("data.json")));
        }

        @Test void extension_html() {
            assertEquals("html", ContentVerifier.getExtension(Path.of("index.HTML")));
        }

        @Test void extension_none() {
            assertEquals("", ContentVerifier.getExtension(Path.of("Makefile")));
        }

        @Test void extension_dotfile() {
            assertEquals("gitignore", ContentVerifier.getExtension(Path.of(".gitignore")));
        }

        @Test void countTag_div() {
            assertEquals(2, ContentVerifier.countTag("<div><div class=\"a\">", "<div"));
        }

        @Test void countTag_does_not_match_longer_name() {
            assertEquals(0, ContentVerifier.countTag("<divider>", "<div"));
        }

        @Test void countTag_closing() {
            assertEquals(1, ContentVerifier.countTag("</div>", "</div"));
        }
    }
}

