package dev.talos.core.tool;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Non-executing text cleanup for Talos tool-call protocol fragments.
 *
 * <p>This class deliberately does not parse executable tool calls. It owns
 * answer/sink cleanup for places, such as RAG answers, where tool protocol
 * text is never valid user-facing prose but no runtime tool dispatcher exists.
 */
public final class ToolProtocolText {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile(
            "```(?:json)?[ \\t]*\\R([\\s\\S]*?\"(?:name|function|function_name|tool_name|tool)\"[\\s\\S]*?)\\R?```"
    );

    /**
     * Bare Talos tool-call JSON at line boundaries. Single owner of this pattern;
     * the runtime parser references it via {@link #bareToolJsonPattern()}.
     *
     * <p>The quantifiers are possessive (T754). The alternation branches start with
     * disjoint characters (non-brace versus {@code '{'}), so at every position at
     * most one branch can consume; giving characters back can never produce a
     * different successful partition, and possessive matching accepts exactly the
     * same language while eliminating the exponential backtracking a long unclosed
     * candidate otherwise triggers on every model response. The first branch is
     * {@code ++} (not {@code *+}) deliberately: a zero-length first-branch iteration
     * terminates the loop, and under a possessive outer loop there is no backtrack
     * left to retry that iteration with the brace branch; {@code *+} silently stops
     * matching one-level nested argument objects. Any future edit to this
     * alternation must re-verify branch disjointness before keeping the possessive
     * form (pinned by the adversarial and nested-brace tests).
     */
    private static final Pattern BARE_JSON_PATTERN = Pattern.compile(
            "(?:^|\\n)\\s*(\\{\\s*\"(?:name|function|function_name|tool_name|tool)\"\\s*:\\s*\"talos\\.(?:[^{}]++|\\{[^{}]*+\\})*+\\})",
            Pattern.DOTALL
    );

    private static final Pattern TOOL_NAME_FIELD_PATTERN = Pattern.compile(
            "\"(?:name|function|function_name|tool_name|tool)\"\\s*:\\s*['\"]([^'\"]+)['\"]",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private static final Pattern STRIP_PATTERN = Pattern.compile(
            "<(?:tool_call|function_call|tool|function)>\\s*.*?\\s*</(?:tool_call|function_call|tool|function)>",
            Pattern.DOTALL
    );

    private ToolProtocolText() {}

    /**
     * The bare tool-call JSON pattern shared with the runtime text-fallback
     * parser, so the two detection surfaces cannot drift apart.
     */
    public static Pattern bareToolJsonPattern() {
        return BARE_JSON_PATTERN;
    }

    /** Strip recognized Talos tool-call protocol text, returning only prose. */
    public static String stripToolCalls(String text) {
        if (text == null) return "";
        if (looksLikeStandaloneToolJson(text)) {
            return "";
        }
        String stripped = stripRecognizedXmlBlocks(text);
        stripped = stripRecognizedCodeFences(stripped);
        stripped = BARE_JSON_PATTERN.matcher(stripped).replaceAll("");
        stripped = stripMalformedToolProtocolBlocks(stripped);
        stripped = stripped.replaceAll("\\n{3,}", "\n\n");
        return stripped.strip();
    }

    /**
     * Returns true when {@code text} contains a complete Talos tool-call protocol
     * fragment. This is non-executing and is safe for core stream-control code.
     */
    public static boolean containsToolCalls(String text) {
        if (text == null || text.isBlank()) return false;
        if (containsRecognizedXmlToolCall(text)) return true;
        if (containsRecognizedCodeFenceToolCall(text)) return true;
        if (BARE_JSON_PATTERN.matcher(text).find()) return true;
        return looksLikeStandaloneToolJson(text);
    }

    /**
     * Returns true when an XML-variant block names a recognized Talos tool or
     * accepted alias. Foreign-named XML (a model quoting some other agent's
     * protocol) is prose, not Talos protocol, matching the fence gate.
     */
    public static boolean containsRecognizedXmlToolCall(String text) {
        if (text == null || text.isBlank()) return false;
        Matcher matcher = STRIP_PATTERN.matcher(text);
        while (matcher.find()) {
            if (containsAcceptedToolNameField(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    /** Returns true when a code fence contains a recognized Talos tool name or accepted alias. */
    public static boolean containsRecognizedCodeFenceToolCall(String text) {
        if (text == null || text.isBlank()) return false;
        Matcher matcher = CODE_FENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            if (containsAcceptedToolNameField(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when {@code text} is exactly one standalone JSON object that
     * names a recognized Talos tool or accepted alias.
     */
    public static boolean looksLikeStandaloneToolJson(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(trimmed);
            if (!root.isObject()) return false;
            String name = extractName(unwrapIfNeeded(root));
            return name != null && isRecognizedToolName(name);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Returns true for a narrow malformed native-tool protocol debris shape:
     * a small standalone JSON-like array containing only commas and whitespace,
     * for example {@code [ , ]}.
     */
    public static boolean looksLikeMalformedProtocolArrayDebris(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.length() < 3 || trimmed.length() > 512) return false;
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return false;

        String inner = trimmed.substring(1, trimmed.length() - 1);
        boolean sawComma = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == ',') {
                sawComma = true;
            } else if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return sawComma;
    }

    /**
     * Returns true for a JSON-like Talos tool-call object that cannot be parsed
     * as executable JSON protocol.
     */
    public static boolean looksLikeMalformedToolProtocol(String text) {
        return !malformedToolProtocolSpans(text).isEmpty();
    }

    private static String stripMalformedToolProtocolBlocks(String text) {
        List<int[]> spans = malformedToolProtocolSpans(text);
        if (spans.isEmpty()) return text;

        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (int[] span : spans) {
            if (span[0] > cursor) {
                out.append(text, cursor, span[0]);
            }
            cursor = Math.max(cursor, span[1]);
        }
        if (cursor < text.length()) {
            out.append(text, cursor, text.length());
        }
        return out.toString();
    }

    private static String stripRecognizedXmlBlocks(String text) {
        Matcher matcher = STRIP_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            if (containsAcceptedToolNameField(matcher.group())) {
                matcher.appendReplacement(out, "");
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String stripRecognizedCodeFences(String text) {
        Matcher matcher = CODE_FENCE_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            if (containsAcceptedToolNameField(matcher.group(1))) {
                matcher.appendReplacement(out, "");
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static List<int[]> malformedToolProtocolSpans(String text) {
        String value = text == null ? "" : text;
        if (value.isBlank()) return List.of();

        List<int[]> spans = new ArrayList<>();
        int searchFrom = 0;
        while (searchFrom < value.length()) {
            int start = value.indexOf('{', searchFrom);
            if (start < 0) break;
            int end = findJsonLikeObjectEnd(value, start);
            if (end < 0) break;

            String candidate = value.substring(start, end + 1);
            if (isMalformedToolProtocolCandidate(candidate)) {
                spans.add(new int[] { start, end + 1 });
                searchFrom = end + 1;
            } else {
                searchFrom = start + 1;
            }
        }
        return spans;
    }

    private static boolean isMalformedToolProtocolCandidate(String candidate) {
        return containsAcceptedToolNameField(candidate) && !looksLikeStandaloneToolJson(candidate);
    }

    private static boolean containsAcceptedToolNameField(String candidate) {
        Matcher nameMatcher = TOOL_NAME_FIELD_PATTERN.matcher(candidate);
        while (nameMatcher.find()) {
            if (isRecognizedToolName(nameMatcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static int findJsonLikeObjectEnd(String text, int start) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }

            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
                if (depth < 0) return -1;
            }
        }
        return -1;
    }

    private static JsonNode unwrapIfNeeded(JsonNode root) {
        for (String wrapper : List.of("tool_call", "function_call")) {
            JsonNode inner = root.path(wrapper);
            if (!inner.isMissingNode() && inner.isObject() && hasNameAlias(inner)) {
                return inner;
            }
        }
        return root;
    }

    private static boolean hasNameAlias(JsonNode root) {
        for (String key : List.of("name", "function", "function_name", "tool_name", "tool")) {
            if (root.has(key)) return true;
        }
        return false;
    }

    private static String extractName(JsonNode root) {
        for (String key : List.of("name", "function", "function_name", "tool_name", "tool")) {
            JsonNode node = root.path(key);
            if (!node.isMissingNode() && !node.asText("").isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    private static boolean isRecognizedToolName(String rawName) {
        ToolNamePolicy.Decision decision = ToolNamePolicy.resolve(rawName);
        return decision.accepted()
                || (decision.status() == ToolNamePolicy.AliasDecisionStatus.UNKNOWN
                && decision.canonicalToolName().startsWith("talos."));
    }
}
