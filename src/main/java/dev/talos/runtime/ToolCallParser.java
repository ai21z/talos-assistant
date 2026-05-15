package dev.talos.runtime;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.talos.runtime.toolcall.ToolAliasPolicy;
import dev.talos.tools.ToolCall;
import dev.talos.runtime.policy.SafeLogFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses tool-call blocks from LLM text responses (text fallback path).
 *
 * <p><b>Architecture note (native-first pipeline):</b> This parser serves
 * the <em>text fallback</em> path only. When native tool calling is enabled
 * (the primary path), tool calls arrive as structured
 * {@link dev.talos.spi.types.ChatMessage.NativeToolCall} objects and bypass
 * this parser entirely.
 *
 * <p>The text fallback accepts multiple formats, in priority order:
 * <ol>
 *   <li><b>Code-fenced JSON</b> (<em>active fallback</em>): {@code ```json ... ```} blocks
 *       containing a {@code "name"} key — the instructed text fallback format when
 *       native tool calling is unavailable.</li>
 *   <li><b>Bare JSON</b> (<em>catch-all</em>): JSON objects with {@code "talos."} prefix
 *       at line boundaries — for models that skip both wrappers.</li>
 *   <li><b>XML tags</b> (<em>deprecated compatibility — checked last</em>):
 *       {@code <tool_call>}, {@code <function_call>}, {@code <tool>}, {@code <function>}
 *       — retained temporarily for models that may still emit XML from training habits
 *       or cached context. No prompt path instructs this format. Emits a deprecation
 *       warning when matched. Scheduled for removal once native tool calling has been
 *       stable across model versions.</li>
 * </ol>
 *
 * <p>Key aliases ({@code "function"}, {@code "arguments"}, etc.) and nested wrappers
 * ({@code {"tool_call": {...}}}) are normalized. Malformed blocks are logged and skipped.
 * Stateless and thread-safe.
 *
 * @see ToolCallLoop
 */
public final class ToolCallParser {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCallParser.class);

    /**
     * Lenient JSON reader for the text-fallback path.
     *
     * <p>Why not vanilla {@code new ObjectMapper()}: local code-tuned models
     * (qwen2.5-coder, deepseek-coder, etc.) routinely emit JSON tool_call
     * payloads with literal newlines and tabs inside string values. RFC-8259
     * forbids unescaped control chars in strings; Jackson rejects them by
     * default with {@code "Unrecognized character escape (CTRL-CHAR, code 10)"}.
     * That rejection silently drops valid tool calls — we observed three
     * consecutive turns in a real transcript where qwen called
     * {@code talos.edit_file} but the parser ate every payload.
     *
     * <p>The two enabled features are scoped to JSON reading only and do not
     * affect serialization. They mirror what every mainstream LLM-with-tools
     * framework (LangChain, OpenClaw, llama.cpp server) does for the same reason.
     *
     * <ul>
     *   <li>{@code ALLOW_UNESCAPED_CONTROL_CHARS} — accept literal LF/CR/TAB
     *       inside string values (the actual cause of the dropped tool calls).</li>
     *   <li>{@code ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER} — tolerate
     *       over-escaping like {@code \\'} or {@code \\$} that some models
     *       produce when generating code-bearing arguments.</li>
     * </ul>
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    /** Variant XML tags: tool_call, function_call, tool, function.
     *  DEPRECATED COMPATIBILITY ONLY — retained for models that emit XML variants.
     *  JSON code fences are the actively instructed text fallback.
     *  Scheduled for removal once native tool calling is stable across model versions. */
    private static final Pattern VARIANT_TAG_PATTERN = Pattern.compile(
            "<(tool_call|function_call|tool|function)>\\s*(.*?)\\s*</\\1>",
            Pattern.DOTALL
    );

    /** Code-fenced JSON blocks containing any of the recognized name-key aliases.
     *  The alias set ({@code name | function | function_name | tool_name | tool}) is kept in sync with
     *  {@link #extractName(JsonNode)} so the detection gate is not narrower than the
     *  alias-aware extractor. Without this, a model emitting {@code ```json { "tool_name": ... }```}
     *  has its fallback tool call silently dropped before extraction. */
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile(
            "```(?:json)?[ \\t]*\\R([\\s\\S]*?\"(?:name|function|function_name|tool_name|tool)\"[\\s\\S]*?)\\R?```"
    );

    /** Bare JSON at line boundaries with "talos." prefix (model forgot XML wrapper). */
    private static final Pattern BARE_JSON_PATTERN = Pattern.compile(
            "(?:^|\\n)\\s*(\\{\\s*\"(?:name|function|function_name|tool_name|tool)\"\\s*:\\s*\"talos\\.(?:[^{}]*|\\{[^{}]*\\})*\\})",
            Pattern.DOTALL
    );

    private static final Pattern TOOL_NAME_FIELD_PATTERN = Pattern.compile(
            "\"(?:name|function|function_name|tool_name|tool)\"\\s*:\\s*['\"]([^'\"]+)['\"]",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    /** Combined pattern for stripping all recognized tool-call block formats.
     *  Includes XML tags (DEPRECATED compatibility) and code-fenced/bare JSON. */
    private static final Pattern STRIP_PATTERN = Pattern.compile(
            "<(?:tool_call|function_call|tool|function)>\\s*.*?\\s*</(?:tool_call|function_call|tool|function)>",
            Pattern.DOTALL
    );

    private ToolCallParser() {} // utility class

    /**
     * Parse all tool-call blocks from an LLM response.
     * Tries code-fenced JSON first, then bare JSON, then deprecated XML tags.
     */
    public static List<ToolCall> parse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return List.of();
        }

        List<ToolCall> calls = new ArrayList<>();
        Set<String> consumedPayloads = new HashSet<>();

        // Pass 1: code-fenced JSON blocks — ACTIVE fallback format (instructed)
        extractFromPattern(CODE_FENCE_PATTERN, 1, llmResponse, calls, consumedPayloads);

        // Pass 2: bare JSON (only if no code-fenced blocks were found — avoids
        // double-parsing when the model wraps AND bare-emits the same call)
        if (calls.isEmpty()) {
            extractFromPattern(BARE_JSON_PATTERN, 1, llmResponse, calls, consumedPayloads);
        }

        // Pass 2b: Jackson-based adjacent standalone JSON objects.
        // Supplements Pass 2 when BARE_JSON_PATTERN misses objects whose string values
        // contain literal brace characters (e.g. CSS rules in old_string/new_string,
        // JavaScript function bodies in content). Uses call-identity deduplication to
        // avoid re-adding anything Pass 2 already found.
        // Only runs for responses that start with '{' — i.e. raw-JSON-only model output.
        extractAdjacentStandaloneToolJsons(llmResponse, calls);

        // Pass 3: XML-tagged blocks — DEPRECATED COMPATIBILITY ONLY (checked last).
        //         Not actively instructed. Retained only for models that still emit
        //         XML from training habits. Will be removed once native calling is stable.
        int preXmlCount = calls.size();
        extractFromPattern(VARIANT_TAG_PATTERN, 2, llmResponse, calls, consumedPayloads);
        if (calls.size() > preXmlCount) {
            XmlCompatTelemetry.recordParserFallback(calls.subList(preXmlCount, calls.size()));
            LOG.warn("XML tool-call format detected — this is deprecated. "
                    + "The model should use native tool calling or JSON code-fence format.");
        }

        if (calls.isEmpty()) {
            ToolCall standalone = tryParseStandaloneToolJson(llmResponse);
            if (standalone != null) {
                calls.add(standalone);
            }
        }

        return Collections.unmodifiableList(calls);
    }

    /**
     * Returns true if the response contains at least one recognizable
     * tool-call block (tagged, code-fenced, bare JSON, or adjacent standalone JSON).
     *
     * <p>The final check mirrors Pass 2b in {@link #parse}: uses Jackson streaming
     * to detect adjacent raw JSON objects whose string values contain brace characters
     * that {@link #BARE_JSON_PATTERN} cannot traverse.
     */
    public static boolean containsToolCalls(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) return false;
        if (VARIANT_TAG_PATTERN.matcher(llmResponse).find()) return true;
        if (CODE_FENCE_PATTERN.matcher(llmResponse).find()) return true;
        if (BARE_JSON_PATTERN.matcher(llmResponse).find()) return true;
        if (tryParseStandaloneToolJson(llmResponse) != null) return true;
        // Align with Pass 2b: detect adjacent standalone raw JSON objects that
        // BARE_JSON_PATTERN misses when string values contain literal brace chars.
        var probe = new ArrayList<ToolCall>(1);
        extractAdjacentStandaloneToolJsons(llmResponse, probe);
        return !probe.isEmpty();
    }

    /** Strip all recognized tool-call blocks, returning only the LLM's prose. */
    public static String stripToolCalls(String llmResponse) {
        if (llmResponse == null) return "";
        if (tryParseStandaloneToolJson(llmResponse) != null) {
            return "";
        }
        String stripped = STRIP_PATTERN.matcher(llmResponse).replaceAll("");
        // Also strip code-fenced tool calls
        stripped = CODE_FENCE_PATTERN.matcher(stripped).replaceAll("");
        // Also strip bare JSON tool calls
        stripped = BARE_JSON_PATTERN.matcher(stripped).replaceAll("");
        // Also strip malformed JSON-like tool protocol objects that are not
        // executable JSON but still look like Talos tool-call protocol.
        stripped = stripMalformedToolProtocolBlocks(stripped);
        // Collapse excessive blank lines left by removed blocks
        stripped = stripped.replaceAll("\\n{3,}", "\n\n");
        return stripped.strip();
    }

    static boolean looksLikeUnfinishedToolPayload(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return false;
        }
        String trimmed = llmResponse.strip();
        // Intentional: once the runtime has already entered real tool execution,
        // a fully parseable tool payload in final-answer position still means the
        // continuation was left unfinished. The loop should normally consume it;
        // if it survives to final-answer acceptance, we prefer a truthful runtime
        // fallback over surfacing raw tool JSON to the user.
        if (containsToolCalls(trimmed)) {
            return true;
        }
        boolean startsLikeToolEnvelope = trimmed.startsWith("{")
                || trimmed.startsWith("```json")
                || trimmed.startsWith("```")
                || trimmed.startsWith("<tool_call>")
                || trimmed.startsWith("<function_call>")
                || trimmed.startsWith("<tool>")
                || trimmed.startsWith("<function>");
        boolean mentionsToolShape = trimmed.contains("\"name\"")
                || trimmed.contains("\"tool_name\"")
                || trimmed.contains("\"function_name\"")
                || trimmed.contains("\"function\"")
                || trimmed.contains("\"tool\"");
        return startsLikeToolEnvelope && mentionsToolShape && trimmed.contains("talos.");
    }

    /**
     * Returns true for a narrow malformed native-tool protocol debris shape:
     * a small standalone JSON-like array containing only commas and whitespace,
     * for example {@code [ , ]}.
     *
     * <p>This deliberately does not treat {@code []}, ordinary JSON arrays, or
     * user-facing JSON examples as protocol. The observed failure shape was an
     * invalid empty array fragment from a failed tool-call attempt, not a broad
     * JSON syntax problem.
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
     *
     * <p>Observed local models sometimes emit objects like:
     *
     * <pre>
     * {
     *   "name": "talos.edit_file",
     *   "arguments": {
     *     "old_string": 'single-quoted value'
     *   }
     * }
     * </pre>
     *
     * <p>This is not a format Talos should execute, but it is clearly protocol
     * text and should not be displayed as ordinary assistant prose. Detection is
     * deliberately narrow: the candidate must be a brace-balanced object with a
     * recognized Talos tool-name field. Valid JSON tool calls return false here
     * because they belong on the normal parser/execution path.
     */
    public static boolean looksLikeMalformedToolProtocol(String text) {
        return !malformedToolProtocolSpans(text).isEmpty();
    }

    /**
     * Returns true when {@code text} is exactly one standalone JSON object that
     * parses as a Talos tool call.
     *
     * <p>Unlike {@link #parseJson(String)}, this helper does not log warnings
     * for ordinary non-tool JSON. It exists for display filtering, where normal
     * JSON examples may be inspected speculatively before deciding whether to
     * suppress them from the terminal stream.
     */
    static boolean looksLikeStandaloneToolJson(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || !trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(trimmed);
            if (!root.isObject()) return false;
            ToolCall call = parseJsonNode(root);
            return call != null
                    && call.toolName() != null
                    && isRecognizedToolName(call.toolName());
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isRecognizedToolName(String rawName) {
        return ToolAliasPolicy.resolve(rawName).accepted();
    }

    // ── Internal extraction helpers ──────────────────────────────────

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
        Matcher nameMatcher = TOOL_NAME_FIELD_PATTERN.matcher(candidate);
        String toolName = null;
        while (nameMatcher.find()) {
            String raw = nameMatcher.group(1);
            if (isRecognizedToolName(raw)) {
                toolName = raw;
                break;
            }
        }
        if (toolName == null) return false;

        try {
            JsonNode root = MAPPER.readTree(candidate);
            ToolCall call = parseJsonNode(root);
            return call == null
                    || call.toolName() == null
                    || !isRecognizedToolName(call.toolName());
        } catch (Exception ignored) {
            return true;
        }
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

    /**
     * Pass 2b: Jackson streaming extractor for adjacent standalone raw JSON tool objects.
     *
     * <p>The regex-based {@link #BARE_JSON_PATTERN} uses {@code [^{}]*} for inner
     * content and therefore misses JSON objects whose string values contain literal
     * brace characters (for example CSS rules in {@code old_string}, or JavaScript
     * function bodies in {@code content}). This pass uses Jackson's streaming
     * {@code MappingIterator} which correctly handles braces inside string values.
     *
     * <p>Runs after Pass 2 and supplements it: any valid {@code talos.*} calls not
     * already present in {@code calls} are appended. Deduplication is by call identity
     * (toolName + parameters) so the key format is independent of the raw-text
     * normalization used by {@link #extractFromPattern}.
     *
     * <p>Restricted to raw-JSON-only model output: only runs when the trimmed text
     * starts with an open brace, ensuring prose, code-fenced, and XML-tagged
     * responses are never affected.
     */
    private static void extractAdjacentStandaloneToolJsons(String text, List<ToolCall> calls) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return;
        }
        try (var jp = MAPPER.createParser(trimmed)) {
            var iter = MAPPER.readerFor(JsonNode.class).<JsonNode>readValues(jp);
            while (iter.hasNextValue()) {
                JsonNode node;
                try {
                    node = iter.nextValue();
                } catch (Exception e) {
                    LOG.debug("Adjacent JSON pass: stopping at non-JSON boundary: {}",
                            SafeLogFormatter.throwableMessage(e));
                    break;
                }
                if (!node.isObject()) continue;
                ToolCall call = parseJsonNode(node);
                if (call == null || call.toolName() == null || !isRecognizedToolName(call.toolName())) {
                    continue;
                }
                boolean duplicate = calls.stream().anyMatch(c ->
                        c.toolName().equals(call.toolName()) &&
                        c.parameters().equals(call.parameters()));
                if (!duplicate) {
                    calls.add(call);
                }
            }
        } catch (Exception e) {
            LOG.debug("Adjacent JSON pass: extraction failed: {}", SafeLogFormatter.throwableMessage(e));
        }
    }

    /** Extract tool calls from all matches of a pattern, deduplicating by payload. */
    private static void extractFromPattern(Pattern pattern, int group,
                                           String text, List<ToolCall> calls,
                                           Set<String> consumed) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String jsonPayload = matcher.group(group).strip();
            if (jsonPayload.isEmpty()) continue;

            // Deduplicate: skip if we already parsed an identical payload
            String normalized = jsonPayload.replaceAll("\\s+", " ");
            if (!consumed.add(normalized)) continue;

            try {
                ToolCall call = parseJson(jsonPayload);
                if (call != null) {
                    calls.add(call);
                }
            } catch (Exception e) {
                LOG.warn("Failed to parse tool_call JSON: {}", SafeLogFormatter.throwableMessage(e));
                LOG.debug("Malformed payload: {}", SafeLogFormatter.value(jsonPayload));
            }
        }
    }

    private static ToolCall tryParseStandaloneToolJson(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || !trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        try {
            ToolCall call = parseJson(trimmed);
            if (call == null) {
                return null;
            }
            return call.toolName() != null && isRecognizedToolName(call.toolName())
                    ? call
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Parse a single JSON payload into a ToolCall (handles key aliases and nested wrappers). */
    static ToolCall parseJson(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        ToolCall call = parseJsonNode(root);
        if (call == null) {
            LOG.warn("tool_call missing 'name' field: {}", SafeLogFormatter.value(json));
        }
        return call;
    }

    /**
     * Parse a pre-parsed {@link JsonNode} into a {@link ToolCall}, handling key
     * aliases and nested wrappers. Returns {@code null} if the name is missing.
     */
    private static ToolCall parseJsonNode(JsonNode root) {
        root = unwrapIfNeeded(root);
        String name = extractName(root);
        if (name == null || name.isBlank()) {
            return null;
        }
        return new ToolCall(name, extractParams(root));
    }

    /** Unwrap {@code {"tool_call": {...}}} or {@code {"function_call": {...}}} nesting. */
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

    /** Extract tool name, trying "name", "function", "function_name", "tool_name", "tool". */
    private static String extractName(JsonNode root) {
        for (String key : List.of("name", "function", "function_name", "tool_name", "tool")) {
            JsonNode node = root.path(key);
            if (!node.isMissingNode() && !node.asText("").isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    /** Extract params map, trying "parameters", "arguments", "args", "params". */
    private static Map<String, String> extractParams(JsonNode root) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String key : List.of("parameters", "arguments", "args", "params")) {
            JsonNode paramsNode = root.path(key);
            if (!paramsNode.isMissingNode() && paramsNode.isObject()) {
                var fields = paramsNode.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    params.put(entry.getKey(), entry.getValue().asText(""));
                }
                return params;
            }
        }
        return params;
    }
}

