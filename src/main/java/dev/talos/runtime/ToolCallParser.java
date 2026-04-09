package dev.talos.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.tools.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses tool-call blocks from LLM text responses.
 *
 * <p>Accepts the canonical {@code <tool_call>} XML format plus common variants:
 * variant XML tags, code-fenced JSON, bare JSON with {@code "talos."} prefix,
 * and key aliases ({@code "function"}, {@code "arguments"}, etc.).
 * Malformed blocks are logged and skipped. Stateless and thread-safe.
 */
public final class ToolCallParser {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCallParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Canonical XML pattern: {@code <tool_call>…</tool_call>}. */
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "<tool_call>\\s*(.*?)\\s*</tool_call>",
            Pattern.DOTALL
    );

    /** Variant XML tags: tool_call, function_call, tool, function. */
    private static final Pattern VARIANT_TAG_PATTERN = Pattern.compile(
            "<(tool_call|function_call|tool|function)>\\s*(.*?)\\s*</\\1>",
            Pattern.DOTALL
    );

    /** Code-fenced JSON blocks containing a "name" key. */
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile(
            "```(?:json)?\\s*\\n(\\{[^`]*\"name\"[^`]*\\})\\s*\\n?```",
            Pattern.DOTALL
    );

    /** Bare JSON at line boundaries with "talos." prefix (model forgot XML wrapper). */
    private static final Pattern BARE_JSON_PATTERN = Pattern.compile(
            "(?:^|\\n)\\s*(\\{\\s*\"(?:name|function|tool_name|tool)\"\\s*:\\s*\"talos\\.(?:[^{}]*|\\{[^{}]*\\})*\\})",
            Pattern.DOTALL
    );

    /** Combined pattern for stripping all recognized tool-call block formats. */
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

        // Pass 1: XML-tagged blocks (canonical + variants)
        extractFromPattern(VARIANT_TAG_PATTERN, 2, llmResponse, calls, consumedPayloads);

        // Pass 2: code-fenced JSON blocks
        extractFromPattern(CODE_FENCE_PATTERN, 1, llmResponse, calls, consumedPayloads);

        // Pass 3: bare JSON (only if no tagged blocks were found — avoids
        // double-parsing when the model wraps AND bare-emits the same call)
        if (calls.isEmpty()) {
            extractFromPattern(BARE_JSON_PATTERN, 1, llmResponse, calls, consumedPayloads);
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

