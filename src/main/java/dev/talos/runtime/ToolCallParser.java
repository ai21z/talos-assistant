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
 * <p>LLMs are instructed (via {@link dev.talos.core.llm.SystemPromptBuilder})
 * to emit tool calls in this XML-like format:
 *
 * <pre>{@code
 * <tool_call>
 * {"name": "talos.read_file", "parameters": {"path": "src/Main.java"}}
 * </tool_call>
 * }</pre>
 *
 * <h3>Protocol hardening</h3>
 * <p>Local models (especially smaller ones) inconsistently emit tool calls.
 * This parser accepts several common variants while keeping the canonical
 * {@code <tool_call>} format as the primary path:
 *
 * <ul>
 *   <li><b>Variant XML tags</b>: {@code <function_call>}, {@code <tool>},
 *       {@code <function>} are accepted alongside {@code <tool_call>}</li>
 *   <li><b>Code-fenced JSON</b>: {@code ```json … ```} blocks containing
 *       a JSON object with a {@code "name"} field and {@code "talos."} prefix</li>
 *   <li><b>Key normalization</b>: {@code "function"}, {@code "tool_name"},
 *       {@code "tool"} are accepted as aliases for {@code "name"};
 *       {@code "arguments"}, {@code "args"} are accepted as aliases for
 *       {@code "parameters"}</li>
 *   <li><b>Nested wrapper</b>: {@code {"tool_call": {"name": …}}} unwrapped
 *       automatically</li>
 * </ul>
 *
 * <p>Malformed blocks are logged and skipped. The parser is stateless and
 * thread-safe.
 */
public final class ToolCallParser {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCallParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Canonical pattern: {@code <tool_call>…</tool_call>}.
     * Kept as the primary pattern for backward compatibility.
     */
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "<tool_call>\\s*(.*?)\\s*</tool_call>",
            Pattern.DOTALL
    );

    /**
     * Extended pattern: accepts variant XML tags used by local models.
     * Matches {@code <tool_call>}, {@code <function_call>}, {@code <tool>},
     * {@code <function>} with their corresponding closing tags.
     */
    private static final Pattern VARIANT_TAG_PATTERN = Pattern.compile(
            "<(tool_call|function_call|tool|function)>\\s*(.*?)\\s*</\\1>",
            Pattern.DOTALL
    );

    /**
     * Code-fence pattern: {@code ```json … ```} blocks.
     * Only matches if the JSON contains a "name" key (to avoid matching
     * arbitrary code blocks).
     */
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile(
            "```(?:json)?\\s*\\n(\\{[^`]*\"name\"[^`]*\\})\\s*\\n?```",
            Pattern.DOTALL
    );

    /**
     * Bare JSON pattern: standalone JSON objects at line boundaries that
     * look like tool calls (contain "name" key with "talos." prefix).
     * This catches cases where the model forgets the XML wrapper entirely.
     */
    private static final Pattern BARE_JSON_PATTERN = Pattern.compile(
            "(?:^|\\n)\\s*(\\{\\s*\"(?:name|function|tool_name|tool)\"\\s*:\\s*\"talos\\.(?:[^{}]*|\\{[^{}]*\\})*\\})",
            Pattern.DOTALL
    );

    /**
     * Combined strip pattern: removes all recognized tool-call block formats.
     */
    private static final Pattern STRIP_PATTERN = Pattern.compile(
            "<(?:tool_call|function_call|tool|function)>\\s*.*?\\s*</(?:tool_call|function_call|tool|function)>",
            Pattern.DOTALL
    );

    private ToolCallParser() {} // utility class

    /**
     * Parse all tool-call blocks from an LLM response.
     *
     * <p>Tries extraction in priority order:
     * <ol>
     *   <li>XML-tagged blocks (canonical + variant tags)</li>
     *   <li>Code-fenced JSON blocks</li>
     *   <li>Bare JSON objects at line boundaries</li>
     * </ol>
     *
     * <p>Higher-priority matches consume their text range; lower-priority
     * patterns only match in unconsumed regions. This prevents double-parsing
     * a tool call that appears both in tags and as bare JSON.
     *
     * @param llmResponse the raw LLM text response
     * @return list of parsed ToolCall records (empty if none found)
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

    /**
     * Strip all recognized tool-call blocks from the text, returning only
     * the LLM's reasoning/explanation text.
     *
     * @param llmResponse the raw LLM text response
     * @return the text with tool-call blocks removed and excess whitespace collapsed
     */
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

    /**
     * Extract tool calls from all matches of a pattern.
     *
     * @param pattern  the regex pattern to match
     * @param group    the capture group index containing the JSON payload
     * @param text     the LLM response text
     * @param calls    accumulator for parsed calls
     * @param consumed set of normalized payloads already parsed (dedup)
     */
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

    /**
     * Parse a single JSON payload into a ToolCall.
     *
     * <p>Accepts the canonical format plus common variants:
     * <ul>
     *   <li>{@code "name"}, {@code "function"}, {@code "tool_name"},
     *       {@code "tool"} → tool name</li>
     *   <li>{@code "parameters"}, {@code "arguments"}, {@code "args"},
     *       {@code "params"} → parameter map</li>
     *   <li>{@code {"tool_call": {"name": …}}} → auto-unwrap</li>
     * </ul>
     */
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

    /**
     * Unwrap common nesting patterns:
     * {@code {"tool_call": {...}}}, {@code {"function_call": {...}}}.
     */
    private static JsonNode unwrapIfNeeded(JsonNode root) {
        for (String wrapper : List.of("tool_call", "function_call")) {
            JsonNode inner = root.path(wrapper);
            if (!inner.isMissingNode() && inner.isObject() && inner.has("name")) {
                return inner;
            }
        }
        return root;
    }

    /**
     * Extract the tool name from the JSON root, trying canonical and
     * variant key names.
     */
    private static String extractName(JsonNode root) {
        for (String key : List.of("name", "function", "tool_name", "tool")) {
            JsonNode node = root.path(key);
            if (!node.isMissingNode() && !node.asText("").isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    /**
     * Extract the parameters map from the JSON root, trying canonical
     * and variant key names. Values are coerced to strings.
     */
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

