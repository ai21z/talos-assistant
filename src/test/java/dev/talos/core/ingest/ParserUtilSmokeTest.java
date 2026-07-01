package dev.talos.core.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ParserUtilSmokeTest {

    @Test
    public void smartParse_basicTextMdJava() throws Exception {
        Path tmp = Files.createTempDirectory("talos-parse");
        try {
            Path md = tmp.resolve("a.md");
            Path txt = tmp.resolve("b.txt");
            Path jv = tmp.resolve("C.java");

            Files.writeString(md, "---\ntitle: T\n---\n# Hello\nMarkdown", StandardCharsets.UTF_8);
            Files.writeString(txt, "plain text\nline 2", StandardCharsets.UTF_8);
            Files.writeString(jv, "public class C{/** j */}", StandardCharsets.UTF_8);

            String s1 = ParserUtil.smartParse(md);
            String s2 = ParserUtil.smartParse(txt);
            String s3 = ParserUtil.smartParse(jv);

            assertNotNull(s1);
            assertNotNull(s2);
            assertNotNull(s3);

            assertTrue(s1.contains("Hello") || s1.length() > 0);
            assertTrue(s2.contains("plain") || s2.length() > 0);
            assertTrue(s3.contains("class") || s3.length() > 0);
        } finally {
            // best-effort cleanup
            try { Files.walk(tmp).sorted((a,b)->b.compareTo(a)).forEach(p -> { try { Files.deleteIfExists(p);} catch(Exception ignored){} }); } catch (Exception ignored) {}
        }
    }

    @Test
    public void smartParse_rejectsUnsupportedBinaryDocumentsAsCapabilityLimit(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("sample.pdf");
        Files.writeString(pdf, "%PDF-1.7 fake test payload", StandardCharsets.UTF_8);

        IOException ex = assertThrows(IOException.class, () -> ParserUtil.smartParse(pdf));

        assertTrue(ex.getMessage().contains("Unsupported binary document format: sample.pdf"));
        assertTrue(ex.getMessage().contains("cannot extract PDF contents"));
        assertFalse(ex.getMessage().contains("empty"));
    }

    // ─── P1 regression: HTML/XML source preservation ───

    @Nested
    class HtmlSourcePreservation {

        @TempDir Path tmp;

        private static final String HTML_WITH_ALL = """
                <!DOCTYPE html>
                <html lang="en">
                <head><title>Test</title>
                <style>
                    body { background: #000; color: white; }
                    .card { border-radius: 12px; }
                </style>
                </head>
                <body>
                <h1>Hello</h1>
                <script>
                    function greet() { return 'hi'; }
                    document.getElementById('x').textContent = greet();
                </script>
                </body>
                </html>
                """;

        @Test
        void html_preservesScriptBlocks() throws Exception {
            Path f = tmp.resolve("page.html");
            Files.writeString(f, HTML_WITH_ALL);
            String parsed = ParserUtil.smartParse(f);
            assertTrue(parsed.contains("function greet()"),
                    "Script content must be preserved for code review");
            assertTrue(parsed.contains("getElementById"),
                    "DOM API calls must survive parsing");
        }

        @Test
        void html_preservesStyleBlocks() throws Exception {
            Path f = tmp.resolve("page.html");
            Files.writeString(f, HTML_WITH_ALL);
            String parsed = ParserUtil.smartParse(f);
            assertTrue(parsed.contains("background: #000"),
                    "CSS declarations must be preserved");
            assertTrue(parsed.contains("border-radius: 12px"),
                    "CSS properties must survive parsing");
        }

        @Test
        void html_preservesTagStructure() throws Exception {
            Path f = tmp.resolve("page.html");
            Files.writeString(f, HTML_WITH_ALL);
            String parsed = ParserUtil.smartParse(f);
            assertTrue(parsed.contains("<h1>Hello</h1>"),
                    "HTML tags must be preserved for structural analysis");
            assertTrue(parsed.contains("<!DOCTYPE html>"),
                    "DOCTYPE must be preserved");
            assertTrue(parsed.contains("<html lang=\"en\">"),
                    "Root element attributes must be preserved");
        }

        @Test
        void htm_extensionAlsoPreserved() throws Exception {
            Path f = tmp.resolve("legacy.htm");
            Files.writeString(f, "<html><body><script>var x=1;</script></body></html>");
            String parsed = ParserUtil.smartParse(f);
            assertTrue(parsed.contains("var x=1;"),
                    ".htm extension must get the same treatment as .html");
        }

        @Test
        void xml_preservedAsSource() throws Exception {
            Path f = tmp.resolve("config.xml");
            Files.writeString(f, "<?xml version=\"1.0\"?>\n<root><item key=\"val\"/></root>");
            String parsed = ParserUtil.smartParse(f);
            assertTrue(parsed.contains("<item key=\"val\""),
                    "XML attributes must be preserved");
            assertTrue(parsed.contains("<?xml"),
                    "XML declaration must be preserved");
        }

        @Test
        void svg_preservedAsSource() throws Exception {
            Path f = tmp.resolve("icon.svg");
            Files.writeString(f, "<svg viewBox=\"0 0 100 100\"><circle cx=\"50\" cy=\"50\" r=\"40\"/></svg>");
            String parsed = ParserUtil.smartParse(f);
            assertTrue(parsed.contains("<circle"),
                    "SVG elements must be preserved");
            assertTrue(parsed.contains("viewBox"),
                    "SVG attributes must be preserved");
        }

        @Test
        void html_producesMultipleChunks() throws Exception {
            // Build a realistic HTML file that is >1200 chars (default chunk_chars)
            StringBuilder sb = new StringBuilder();
            sb.append("<!DOCTYPE html>\n<html>\n<head><style>\n");
            for (int i = 0; i < 50; i++) sb.append("  .class").append(i).append(" { color: red; }\n");
            sb.append("</style></head>\n<body>\n<script>\n");
            for (int i = 0; i < 50; i++) sb.append("  function fn").append(i).append("() { return ").append(i).append("; }\n");
            sb.append("</script>\n</body>\n</html>\n");

            Path f = tmp.resolve("big.html");
            Files.writeString(f, sb.toString());
            String parsed = ParserUtil.smartParse(f);

            // After fix, parsed content should be large enough for multiple chunks
            assertTrue(parsed.length() > 1200,
                    "Parsed HTML must be >1200 chars for multi-chunk indexing, was " + parsed.length());

            // Verify chunking actually produces multiple chunks
            List<ParsedChunk> chunks = Chunker.chunk("big.html", parsed, 1200, 150);
            assertTrue(chunks.size() > 1,
                    "A large HTML file must produce multiple chunks, got " + chunks.size());
        }
    }
}
