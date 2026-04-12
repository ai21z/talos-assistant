package dev.talos.tools.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight post-write verification for files created/edited by tools.
 *
 * <p>Supported: JSON (Jackson), YAML (Jackson YAML), XML (SAX),
 * HTML (tag-balance), other (read-back only).
 *
 * <p>Stateless and thread-safe. Same pattern as {@link ContentSanitizer}.
 */
final class ContentVerifier {

    private ContentVerifier() {}

    private static final Logger LOG = LoggerFactory.getLogger(ContentVerifier.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    record VerifyResult(boolean ok, String summary) {}

    static VerifyResult verify(Path file, String writtenContent) {
        String readBack;
        try {
            readBack = Files.readString(file);
        } catch (IOException e) {
            LOG.warn("Read-back failed for {}: {}", file, e.getMessage());
            return new VerifyResult(false, "read-back failed: " + e.getMessage());
        }
        if (!readBack.equals(writtenContent)) {
            LOG.warn("Read-back mismatch for {}: wrote {} chars, read {} chars",
                    file, writtenContent.length(), readBack.length());
            return new VerifyResult(false,
                    "read-back mismatch (wrote " + writtenContent.length()
                    + " chars, read " + readBack.length() + " chars)");
        }
        String ext = getExtension(file);
        return switch (ext) {
            case "json"         -> verifyJson(readBack);
            case "html", "htm"  -> verifyHtml(readBack);
            case "yaml", "yml"  -> verifyYaml(readBack);
            case "xml"          -> verifyXml(readBack);
            default             -> new VerifyResult(true, "read-back OK");
        };
    }

    private static VerifyResult verifyJson(String content) {
        if (content == null || content.isBlank()) {
            return new VerifyResult(false, "JSON parse failed — empty content");
        }
        try {
            var tree = JSON_MAPPER.readTree(content);
            if (tree == null) {
                return new VerifyResult(false, "JSON parse failed — empty or null content");
            }
            return new VerifyResult(true, "valid JSON");
        } catch (Exception e) {
            return new VerifyResult(false, "JSON parse failed — " + brief(e));
        }
    }

    private static VerifyResult verifyYaml(String content) {
        try {
            new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(content);
            return new VerifyResult(true, "valid YAML");
        } catch (Exception e) {
            return new VerifyResult(false, "YAML parse failed — " + brief(e));
        }
    }

    private static VerifyResult verifyXml(String content) {
        try {
            var f = javax.xml.parsers.SAXParserFactory.newInstance();
            f.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.newSAXParser().parse(
                    new org.xml.sax.InputSource(new StringReader(content)),
                    new org.xml.sax.helpers.DefaultHandler());
            return new VerifyResult(true, "valid XML");
        } catch (Exception e) {
            return new VerifyResult(false, "XML parse failed — " + brief(e));
        }
    }

    private static final String[] STRUCTURAL_TAGS = {
        "html", "head", "body", "div", "span", "section", "article",
        "nav", "header", "footer", "main", "aside",
        "table", "thead", "tbody", "tfoot",
        "ul", "ol", "dl", "form", "select", "textarea",
        "script", "style", "svg"
    };

    private static VerifyResult verifyHtml(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        List<String> warnings = new ArrayList<>();
        for (String tag : STRUCTURAL_TAGS) {
            int opens = countTag(lower, "<" + tag);
            int closes = countTag(lower, "</" + tag);
            if (opens > closes) {
                warnings.add("unclosed <" + tag + "> ("
                        + (opens - closes) + " open without close)");
            }
        }
        if (warnings.isEmpty()) return new VerifyResult(true, "HTML structure OK");
        String detail = warnings.size() <= 3
                ? String.join("; ", warnings)
                : String.join("; ", warnings.subList(0, 3))
                  + " (+" + (warnings.size() - 3) + " more)";
        return new VerifyResult(false, "HTML issues — " + detail);
    }

    static int countTag(String lower, String tagStart) {
        int count = 0, idx = 0;
        while ((idx = lower.indexOf(tagStart, idx)) >= 0) {
            int after = idx + tagStart.length();
            if (after >= lower.length()) { count++; break; }
            char c = lower.charAt(after);
            if (c == ' ' || c == '>' || c == '/' || c == '\t'
                    || c == '\n' || c == '\r') count++;
            idx = after;
        }
        return count;
    }

    static String getExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        if (m.length() > 120) m = m.substring(0, 117) + "...";
        return m.replace('\n', ' ').replace('\r', ' ');
    }
}



