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
     * Tries XML tags first, then code-fenced JSON, then bare JSON.
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

        // Pass 3: XML-tagged blocks — DEPRECATED COMPATIBILITY ONLY (checked last).
        //         Not actively instructed. Retained only for models that still emit
        //         XML from training habits. Will be removed once native calling is stable.
        int preXmlCount = calls.size();
        extractFromPattern(VARIANT_TAG_PATTERN, 2, llmResponse, calls, consumedPayloads);
        if (calls.size() > preXmlCount) {
            LOG.warn("XML tool-call format detected — this is deprecated. "
                    + "The model should use native tool calling or JSON code-fence format.");
        }

        return Collections.unmodifiableList(calls);
    }

    /**
     * Returns true if the response contains at least one recognizable
     * tool-call block (tagged, code-fenced, or bare JSON).
     */
    public static boolean containsToolCalls(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) return false;
        return VARIANT_TAG_PATTERN.matcher(llmResponse).find()
                || CODE_FENCE_PATTERN.matcher(llmResponse).find()
                || BARE_JSON_PATTERN.matcher(llmResponse).find();
    }

    /** Strip all recognized tool-call blocks, returning only the LLM's prose. */
    public static String stripToolCalls(String llmResponse) {
        if (llmResponse == null) return "";
        String stripped = STRIP_PATTERN.matcher(llmResponse).replaceAll("");
        // Also strip code-fenced tool calls
        stripped = CODE_FENCE_PATTERN.matcher(stripped).replaceAll("");
        // Also strip bare JSON tool calls
        stripped = BARE_JSON_PATTERN.matcher(stripped).replaceAll("");
        // Collapse excessive blank lines left by removed blocks
        stripped = stripped.replaceAll("\\n{3,}", "\n\n");
        return stripped.strip();
    }

    // ── Internal extraction helpers ──────────────────────────────────

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

    /** Parse a single JSON payload into a ToolCall (handles key aliases and nested wrappers). */
    static ToolCall parseJson(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);

        // Auto-unwrap nested wrapper: {"tool_call": {...}}
        root = unwrapIfNeeded(root);

        // Extract name (with key normalization)
        String name = extractName(root);
        if (name == null || name.isBlank()) {
            LOG.warn("tool_call missing 'name' field: {}", json);
            return null;
        }

        // Extract parameters (with key normalization)
        Map<String, String> params = extractParams(root);

        return new ToolCall(name, params);
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

