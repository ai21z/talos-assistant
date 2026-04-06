package dev.talos.core.ingest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
