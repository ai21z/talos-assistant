package dev.talos.runtime.intent;

import java.util.Set;
import java.util.regex.Pattern;

/** Bounded imperative file-content intent, without target or execution authority. */
public final class FileContentEvidenceRequest {
    private static final int MAX_LENGTH = 4096;
    private static final String SOURCE_DATA = "(?:evidence|information|facts|content)";
    private static final String SOURCE_ACTION = "(?:read|open|inspect|examine|review|use\\s+"
            + SOURCE_DATA + "\\s+from)";
    private static final String ONGOING_SOURCE_ACTION = "(?:reading|opening|inspecting|examining|reviewing|using\\s+"
            + SOURCE_DATA + "\\s+from)";
    private static final Pattern DIRECT = Pattern.compile("(?i)"
            + "(?:please\\s+)?(?:(?:can|could|would)\\s+you\\s+)?(?:please\\s+)?"
            + SOURCE_ACTION + "\\s+"
            + "(?<object>(?:[\\p{L}\\p{N}_-]+[\\s,]+){0,12}?)files?\\b");
    private static final Set<String> FILE_MODIFIERS = Set.of(
            "the", "my", "our", "these", "those", "and", "actual", "current", "main", "relevant",
            "local", "source", "project", "workspace", "repository", "text", "config", "configuration",
            "html", "htm", "css", "js", "jsx", "javascript", "ts", "tsx", "typescript", "java", "py", "python",
            "json", "yaml", "yml", "md", "markdown", "xml", "toml", "csv");
    private static final Pattern CONDITIONAL_OR_WITHDRAWN = Pattern.compile("(?i)\\b(?:"
            + "if|unless|optionally|pretend|imagine|suppose|hypothetically"
            + "|(?:do\\s+not|don't|dont|never|without|avoid|stop)\\s+(?:"
            + SOURCE_ACTION + "|" + ONGOING_SOURCE_ACTION + ")"
            + "|no\\s+(?:file|workspace|project)\\s+(?:inspection|access|reading)"
            + "|(?:file|workspace|project)\\s+(?:inspection|access|reading)\\s+is\\s+not"
            + ")\\b");
    private static final String SUPPLIED_LOCATION = "(?:(?:the|this|my|your)\\s+)?"
            + "(?:prompt|message|example|attachment|quotation)";
    private static final Pattern SUPPLIED_CONTENT = Pattern.compile("(?i)\\b(?:"
            + "(?:in|from)\\s+" + SUPPLIED_LOCATION
            + "|(?:shown|provided|pasted|quoted|attached|uploaded)\\s+"
            + "(?:here|below|above|(?:in|with|to|within)\\s+" + SUPPLIED_LOCATION + ")"
            + ")\\b");

    private FileContentEvidenceRequest() {}

    public static boolean requiresCurrentFiles(String request) {
        if (request == null || request.isBlank() || request.length() > MAX_LENGTH) return false;
        var lexical = RequestLexicalView.of(request);
        if (lexical.containsMalformedDelimiter(0, request.length())
                || lexical.containsProviderRemovedControl(0, request.length())) return false;
        String unquoted = lexical.maskedLower();
        if (CONDITIONAL_OR_WITHDRAWN.matcher(unquoted).find()
                || SUPPLIED_CONTENT.matcher(unquoted).find()) return false;
        for (int start = 0; start < request.length();) {
            int end = lexical.sentenceEndAfter(start);
            var span = lexical.trimmedSpan(start, end);
            var direct = DIRECT.matcher(lexical.masked()).region(span.start(), span.end());
            if (direct.lookingAt() && lexical.isTopLevel(direct.start())
                    && lexical.isUnquotedRange(direct.start(), direct.end())) {
                boolean nounPhrase = true;
                for (String word : direct.group("object").toLowerCase(java.util.Locale.ROOT).split("[\\s,]+"))
                    if (!word.isEmpty() && !FILE_MODIFIERS.contains(word)) nounPhrase = false;
                if (nounPhrase) return true;
            }
            start = end;
        }
        return false;
    }
}
