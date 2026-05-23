package dev.talos.runtime.verification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StaticWebStructureVerifier {

    private static final Pattern HTML_INLINE_SCRIPT = Pattern.compile(
            "(?is)<script\\b(?![^>]*\\bsrc\\s*=)[^>]*>(.*?)</script>");
    private static final Pattern HTML_INLINE_STYLE = Pattern.compile(
            "(?is)<style\\b[^>]*>(.*?)</style>");
    private static final String[] HTML_STRUCTURAL_TAGS = {
            "html", "head", "body", "div", "span", "section", "article",
            "nav", "header", "footer", "main", "aside", "form", "button",
            "select", "textarea", "script", "style", "svg"
    };

    private StaticWebStructureVerifier() {}

    static List<String> htmlStructureProblems(String htmlFile, String html) {
        if (html == null || html.isBlank()) {
            return List.of(htmlFile + ": HTML file is empty.");
        }
        String lower = html.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        Set<String> malformedClosings = malformedClosingTags(lower);
        for (String tag : malformedClosings) {
            out.add(htmlFile + ": malformed closing tag `</" + tag + ">` is missing `>`.");
        }
        for (String tag : HTML_STRUCTURAL_TAGS) {
            int opens = countCompleteTag(lower, "<" + tag, tag.length() + 1);
            int closes = countCompleteTag(lower, "</" + tag, tag.length() + 2);
            if (opens > closes && !malformedClosings.contains(tag)) {
                out.add(htmlFile + ": unclosed `<" + tag + ">` tag (" + (opens - closes)
                        + " open without close).");
            }
        }
        return out;
    }

    static boolean hasNonBlankInlineScript(String html) {
        if (html == null || html.isBlank()) return false;
        Matcher matcher = HTML_INLINE_SCRIPT.matcher(html);
        while (matcher.find()) {
            String content = matcher.group(1);
            if (content != null && !content.strip().isBlank()) return true;
        }
        return false;
    }

    static boolean hasNonBlankInlineStyle(String html) {
        if (html == null || html.isBlank()) return false;
        Matcher matcher = HTML_INLINE_STYLE.matcher(html);
        while (matcher.find()) {
            String content = matcher.group(1);
            if (content != null && !content.strip().isBlank()) return true;
        }
        return false;
    }

    static List<String> calculatorFormProblems(String request, String html) {
        String lowerHtml = html == null ? "" : html.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (!containsTag(lowerHtml, "form") && !containsTag(lowerHtml, "input")) {
            out.add("Calculator/form task is missing a form or input container.");
        }
        if (shouldExpectWeightHeightControls(request)) {
            if (!hasInputFor(lowerHtml, "weight")) {
                out.add("Calculator/form task is missing a weight input.");
            }
            if (!hasInputFor(lowerHtml, "height")) {
                out.add("Calculator/form task is missing a height input.");
            }
        }
        if (!containsTag(lowerHtml, "button") && !lowerHtml.contains("type=\"submit\"")
                && !lowerHtml.contains("type='submit'")) {
            out.add("Calculator/form task is missing a submit/calculate button.");
        }
        if (!hasResultOutput(lowerHtml)) {
            out.add("Calculator/form task is missing a result output element.");
        }
        return out;
    }

    private static Set<String> malformedClosingTags(String lowerHtml) {
        Set<String> out = new LinkedHashSet<>();
        if (lowerHtml == null || lowerHtml.isBlank()) return out;
        int idx = lowerHtml.indexOf("</");
        while (idx >= 0) {
            int nameStart = idx + 2;
            int pos = nameStart;
            while (pos < lowerHtml.length()) {
                char c = lowerHtml.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '-' || c == ':') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos > nameStart) {
                String tag = lowerHtml.substring(nameStart, pos);
                int after = pos;
                while (after < lowerHtml.length() && Character.isWhitespace(lowerHtml.charAt(after))) {
                    after++;
                }
                if (after >= lowerHtml.length() || lowerHtml.charAt(after) != '>') {
                    out.add(tag);
                }
            }
            idx = lowerHtml.indexOf("</", Math.max(idx + 2, pos));
        }
        return out;
    }

    private static int countCompleteTag(String lowerHtml, String tagStart, int afterTagOffset) {
        int count = 0;
        int idx = 0;
        while ((idx = lowerHtml.indexOf(tagStart, idx)) >= 0) {
            int after = idx + afterTagOffset;
            if (after >= lowerHtml.length()) break;
            char delimiter = lowerHtml.charAt(after);
            if (delimiter == '>' || delimiter == '/' || Character.isWhitespace(delimiter)) {
                int closeBracket = lowerHtml.indexOf('>', after);
                int nextTag = lowerHtml.indexOf('<', after);
                if (closeBracket >= 0 && (nextTag < 0 || closeBracket < nextTag)) {
                    count++;
                }
            }
            idx = after;
        }
        return count;
    }

    private static boolean shouldExpectWeightHeightControls(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("bmi")
                || lower.contains("weight")
                || lower.contains("height");
    }

    private static boolean containsTag(String lowerHtml, String tag) {
        return lowerHtml != null && lowerHtml.contains("<" + tag);
    }

    private static boolean hasInputFor(String lowerHtml, String name) {
        if (lowerHtml == null || lowerHtml.isBlank()) return false;
        Pattern pattern = Pattern.compile("<input\\b[^>]*(id|name|placeholder|aria-label)\\s*=\\s*(['\"])[^'\"]*"
                + Pattern.quote(name.toLowerCase(Locale.ROOT))
                + "[^'\"]*\\2", Pattern.CASE_INSENSITIVE);
        return pattern.matcher(lowerHtml).find();
    }

    private static boolean hasResultOutput(String lowerHtml) {
        if (lowerHtml == null || lowerHtml.isBlank()) return false;
        return lowerHtml.contains("<output")
                || lowerHtml.contains("id=\"result\"")
                || lowerHtml.contains("id='result'")
                || lowerHtml.contains("class=\"result\"")
                || lowerHtml.contains("class='result'");
    }
}
