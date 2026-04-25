package dev.talos.runtime;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.talos.tools.ToolCall;
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
     *  The alias set ({@code name | function | tool_name | tool}) is kept in sync with
     *  {@link #extractName(JsonNode)} so the detection gate is not narrower than the
     *  alias-aware extractor. Without this, a model emitting {@code ```json { "tool_name": ... }```}
     *  has its fallback tool call silently dropped before extraction. */
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile(
            "```(?:json)?\\s*\\n(\\{[^`]*\"(?:name|function|tool_name|tool)\"[^`]*\\})\\s*\\n?```",
            Pattern.DOTALL
    );

    /** Bare JSON at line boundaries with "talos." prefix (model forgot XML wrapper). */
    private static final Pattern BARE_JSON_PATTERN = Pattern.compile(
            "(?:^|\\n)\\s*(\\{\\s*\"(?:name|function|tool_name|tool)\"\\s*:\\s*\"talos\\.(?:[^{}]*|\\{[^{}]*\\})*\\})",
            Pattern.DOTALL
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
                || trimmed.contains("\"function\"")
                || trimmed.contains("\"tool\"");
        return startsLikeToolEnvelope && mentionsToolShape && trimmed.contains("talos.");
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
                    && call.toolName().startsWith("talos.");
        } catch (Exception ignored) {
            return false;
        }
    }

    // ── Internal extraction helpers ──────────────────────────────────

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
                    LOG.debug("Adjacent JSON pass: stopping at non-JSON boundary: {}", e.getMessage());
                    break;
                }
                if (!node.isObject()) continue;
                ToolCall call = parseJsonNode(node);
                if (call == null || call.toolName() == null || !call.toolName().startsWith("talos.")) {
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
            LOG.debug("Adjacent JSON pass: extraction failed: {}", e.getMessage());
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
                LOG.warn("Failed to parse tool_call JSON: {}", e.getMessage());
                LOG.debug("Malformed payload: {}", jsonPayload);
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
            return call.toolName() != null && call.toolName().startsWith("talos.")
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
            LOG.warn("tool_call missing 'name' field: {}", json);
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
            if (!inner.isMissingNode() && inner.isObject() && inner.has("name")) {
                return inner;
            }
        }
        return root;
    }

    /** Extract tool name, trying "name", "function", "tool_name", "tool". */
    private static String extractName(JsonNode root) {
        for (String key : List.of("name", "function", "tool_name", "tool")) {
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

