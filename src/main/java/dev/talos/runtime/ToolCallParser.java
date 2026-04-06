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
 * <p>This parser extracts all such blocks from the response text, deserializes
 * the JSON payload into {@link ToolCall} records, and provides a method to
 * strip the blocks from the text (leaving the LLM's reasoning/explanation).
 *
 * <p>Malformed blocks are logged and skipped. The parser is stateless and
 * thread-safe.
 */
public final class ToolCallParser {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCallParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Pattern matching {@code <tool_call>…</tool_call>} blocks.
     * Allows optional whitespace and newlines inside the tags.
     * Uses DOTALL so the JSON payload can span multiple lines.
     */
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "<tool_call>\\s*(.*?)\\s*</tool_call>",
            Pattern.DOTALL
    );

    private ToolCallParser() {} // utility class

    /**
     * Parse all tool-call blocks from an LLM response.
     *
     * @param llmResponse the raw LLM text response
     * @return list of parsed ToolCall records (empty if none found)
     */
    public static List<ToolCall> parse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return List.of();
        }

        List<ToolCall> calls = new ArrayList<>();
        Matcher matcher = TOOL_CALL_PATTERN.matcher(llmResponse);

        while (matcher.find()) {
            String jsonPayload = matcher.group(1).strip();
            if (jsonPayload.isEmpty()) continue;

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

        return Collections.unmodifiableList(calls);
    }

    /**
     * Returns true if the response contains at least one tool-call block.
     */
    public static boolean containsToolCalls(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) return false;
        return TOOL_CALL_PATTERN.matcher(llmResponse).find();
    }

    /**
     * Strip all {@code <tool_call>…</tool_call>} blocks from the text,
     * returning only the LLM's reasoning/explanation text.
     *
     * @param llmResponse the raw LLM text response
     * @return the text with tool-call blocks removed and excess whitespace collapsed
     */
    public static String stripToolCalls(String llmResponse) {
        if (llmResponse == null) return "";
        String stripped = TOOL_CALL_PATTERN.matcher(llmResponse).replaceAll("");
        // Collapse excessive blank lines left by removed blocks
        stripped = stripped.replaceAll("\\n{3,}", "\n\n");
        return stripped.strip();
    }

    /**
     * Parse a single JSON payload into a ToolCall.
     * Expected format: {@code {"name": "...", "parameters": {...}}}
     */
    private static ToolCall parseJson(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);

        // Extract name
        JsonNode nameNode = root.path("name");
        if (nameNode.isMissingNode() || nameNode.asText("").isBlank()) {
            LOG.warn("tool_call missing 'name' field: {}", json);
            return null;
        }
        String name = nameNode.asText();

        // Extract parameters (flat string map)
        Map<String, String> params = new LinkedHashMap<>();
        JsonNode paramsNode = root.path("parameters");
        if (!paramsNode.isMissingNode() && paramsNode.isObject()) {
            var fields = paramsNode.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                params.put(entry.getKey(), entry.getValue().asText(""));
            }
        }

        return new ToolCall(name, params);
    }
}

