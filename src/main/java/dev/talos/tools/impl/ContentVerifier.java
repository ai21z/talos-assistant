package dev.talos.tools.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.tools.VerificationStatus;
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
 * <p>Stateless and thread-safe. Same pattern as {@link dev.talos.tools.ContentSanitizer}.
 */
final class ContentVerifier {

    private ContentVerifier() {}

    private static final Logger LOG = LoggerFactory.getLogger(ContentVerifier.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * Structured verification result with a {@link VerificationStatus} enum
     * and a human-readable summary.
     *
     * @param status  structured verification outcome
     * @param summary human-readable description
     */
    record VerifyResult(VerificationStatus status, String summary) {
        /** Convenience: returns true if the status is acceptable (PASS or UNKNOWN). */
        boolean ok() { return status.acceptable(); }
    }

    static VerifyResult verify(Path file, String writtenContent) {
        String readBack;
        try {
            readBack = Files.readString(file);
        } catch (IOException e) {
            String reason = SafeLogFormatter.throwableMessage(e);
            LOG.warn("Read-back failed for {}: {}", SafeLogFormatter.value(file), reason);
            return new VerifyResult(VerificationStatus.INTEGRITY_FAIL, "read-back failed: " + reason);
        }
        if (!readBack.equals(writtenContent)) {
            LOG.warn("Read-back mismatch for {}: wrote {} chars, read {} chars",
                    SafeLogFormatter.value(file), writtenContent.length(), readBack.length());
            return new VerifyResult(VerificationStatus.INTEGRITY_FAIL,
                    "read-back mismatch (wrote " + writtenContent.length()
                    + " chars, read " + readBack.length() + " chars)");
        }
        String ext = getExtension(file);
        return switch (ext) {
            case "json"         -> verifyJson(readBack);
            case "html", "htm"  -> verifyHtml(readBack);
            case "yaml", "yml"  -> verifyYaml(readBack);
            case "xml"          -> verifyXml(readBack);
            case "css"          -> verifyCss(readBack);
            case "js", "jsx", "mjs" -> verifyJs(readBack);
            default             -> new VerifyResult(VerificationStatus.UNKNOWN, "read-back OK");
        };
    }

    private static VerifyResult verifyJson(String content) {
        if (content == null || content.isBlank()) {
            return new VerifyResult(VerificationStatus.FAIL, "JSON parse failed - empty content");
        }
        try {
            var tree = JSON_MAPPER.readTree(content);
            if (tree == null) {
                return new VerifyResult(VerificationStatus.FAIL, "JSON parse failed - empty or null content");
            }
            return new VerifyResult(VerificationStatus.PASS, "valid JSON");
        } catch (Exception e) {
            return new VerifyResult(VerificationStatus.FAIL, "JSON parse failed - " + brief(e));
        }
    }

    private static VerifyResult verifyYaml(String content) {
        try {
            new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(content);
            return new VerifyResult(VerificationStatus.PASS, "valid YAML");
        } catch (Exception e) {
            return new VerifyResult(VerificationStatus.FAIL, "YAML parse failed - " + brief(e));
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
            return new VerifyResult(VerificationStatus.PASS, "valid XML");
        } catch (Exception e) {
            return new VerifyResult(VerificationStatus.FAIL, "XML parse failed - " + brief(e));
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
        // Check for broken attribute syntax (common model failure)
        // Pattern: <tag attr="value without closing quote or >
        if (lower.contains("onclick=\"") && !lower.contains("onclick=\"\"")) {
            // Count onclick attributes vs properly closed ones
            int onclickCount = countSubstring(lower, "onclick=\"");
            int properClose = countSubstring(lower, "onclick=\"" ) ; // check for "> after onclick
            // Simple heuristic: look for onclick not followed by "> within a reasonable distance
            if (lower.matches("(?s).*onclick=\"[^\"]{0,200}[^\">\n]*<.*")) {
                warnings.add("possibly broken onclick attribute (missing closing quote/bracket)");
            }
        }
        if (warnings.isEmpty()) return new VerifyResult(VerificationStatus.PASS, "HTML structure OK");
        String detail = warnings.size() <= 3
                ? String.join("; ", warnings)
                : String.join("; ", warnings.subList(0, 3))
                  + " (+" + (warnings.size() - 3) + " more)";
        return new VerifyResult(VerificationStatus.WARN, "HTML issues - " + detail);
    }

    /**
     * Verify CSS content doesn't contain HTML/JS that was likely written by mistake.
     * This catches the transcript scenario where a CSS file received HTML+JS mixed content.
     */
    private static VerifyResult verifyCss(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        List<String> warnings = new ArrayList<>();

        // CSS files should never contain HTML structural tags
        if (lower.contains("<!doctype") || lower.contains("<html"))
            warnings.add("contains HTML markup (<!DOCTYPE or <html>) - wrong content type for CSS");
        if (lower.contains("<body") || lower.contains("<head"))
            warnings.add("contains HTML structural tags (<body>/<head>) - wrong content type for CSS");
        if (lower.contains("<script"))
            warnings.add("contains <script> tag - wrong content type for CSS");

        if (warnings.isEmpty()) return new VerifyResult(VerificationStatus.PASS, "CSS content OK");
        return new VerifyResult(VerificationStatus.WARN, "CSS issues - " + String.join("; ", warnings));
    }

    /**
     * Verify JS content doesn't contain HTML/CSS that was likely written by mistake.
     * This catches scenarios where JS files receive {@code </script>} closing tags
     * or full HTML pages (model confusion between inline scripts and external files).
     */
    private static VerifyResult verifyJs(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        List<String> warnings = new ArrayList<>();

        // JS files should never contain closing script tags (that's inline HTML, not a .js file)
        if (lower.contains("</script>"))
            warnings.add("contains </script> tag - this is a standalone JS file, not an inline script");
        // JS files should never contain HTML document structure
        if (lower.contains("<!doctype") || lower.contains("<html"))
            warnings.add("contains HTML markup - wrong content type for JS file");

        if (warnings.isEmpty()) return new VerifyResult(VerificationStatus.PASS, "JS content OK");
        return new VerifyResult(VerificationStatus.WARN, "JS issues - " + String.join("; ", warnings));
    }

    private static int countSubstring(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
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



