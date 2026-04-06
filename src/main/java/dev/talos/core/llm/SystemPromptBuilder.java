package dev.talos.core.llm;

import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * Composable builder for system prompts.
 *
 * <p>Assembles a system prompt from reusable sections:
 * <ol>
 *   <li><b>Identity</b> — who Talos is (always present)</li>
 *   <li><b>Mode section</b> — mode-specific behavior rules (ask vs rag)</li>
 *   <li><b>Tool section</b> — available tools, auto-generated from registry</li>
 *   <li><b>Conversation section</b> — continuity rules (when history exists)</li>
 * </ol>
 *
 * <p>Each section is loaded from a classpath resource or falls back to a
 * sensible default. Sections are composed in order, separated by blank lines.
 *
 * <p>Usage:
 * <pre>{@code
 * String prompt = SystemPromptBuilder.forAsk()
 *         .withTools(toolRegistry)
 *         .withHistory(true)
 *         .build();
 * }</pre>
 */
public final class SystemPromptBuilder {

    // --- Resource paths for composable sections ---
    private static final String RES_IDENTITY      = "prompts/sections/identity.txt";
    private static final String RES_ASK_RULES     = "prompts/sections/ask-rules.txt";
    private static final String RES_RAG_RULES     = "prompts/sections/rag-rules.txt";
    private static final String RES_TOOLS         = "prompts/sections/tools-preamble.txt";
    private static final String RES_CONVERSATION  = "prompts/sections/conversation.txt";

    // --- Fallback: legacy monolithic prompt files ---
    private static final String RES_LEGACY_ASK = "prompts/ask-system.txt";
    private static final String RES_LEGACY_RAG = "prompts/rag-system.txt";

    private final Mode mode;
    private ToolRegistry toolRegistry;
    private boolean hasHistory;

    /** The two prompt modes. */
    public enum Mode { ASK, RAG }

    private SystemPromptBuilder(Mode mode) {
        this.mode = Objects.requireNonNull(mode);
    }

    /** Create a builder for ask/chat mode. */
    public static SystemPromptBuilder forAsk() {
        return new SystemPromptBuilder(Mode.ASK);
    }

    /** Create a builder for RAG/retrieval mode. */
    public static SystemPromptBuilder forRag() {
        return new SystemPromptBuilder(Mode.RAG);
    }

    /** Include tool descriptions from the given registry. */
    public SystemPromptBuilder withTools(ToolRegistry registry) {
        this.toolRegistry = registry;
        return this;
    }

    /** Include conversation continuity instructions. */
    public SystemPromptBuilder withHistory(boolean hasHistory) {
        this.hasHistory = hasHistory;
        return this;
    }

    /**
     * Build the composed system prompt.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Try to load composable sections from {@code prompts/sections/}</li>
     *   <li>If the identity section exists, compose from parts</li>
     *   <li>Otherwise, fall back to the legacy monolithic prompt file</li>
     * </ol>
     *
     * <p>This allows incremental migration: as long as the legacy files
     * exist, they remain the source of truth. Once composable sections
     * are added, they take precedence.
     */
    public String build() {
        // Try composable path first
        String identity = readResource(RES_IDENTITY);
        if (identity != null) {
            return buildComposed(identity);
        }

        // Fall back to legacy monolithic prompt + tool/conversation appendix
        String legacy = readResource(mode == Mode.ASK ? RES_LEGACY_ASK : RES_LEGACY_RAG);
        if (legacy == null) {
            legacy = defaultPrompt();
        }
        return appendDynamicSections(legacy);
    }

    /** Compose from individual sections. */
    private String buildComposed(String identity) {
        var sb = new StringBuilder();

        // 1. Identity
        sb.append(identity.strip());

        // 2. Mode-specific rules
        String modeRules = readResource(mode == Mode.ASK ? RES_ASK_RULES : RES_RAG_RULES);
        if (modeRules != null) {
            sb.append("\n\n").append(modeRules.strip());
        }

        // 3. Dynamic sections (tools, conversation)
        String dynamic = buildDynamicSections();
        if (!dynamic.isEmpty()) {
            sb.append("\n\n").append(dynamic);
        }

        return sb.toString();
    }

    /** Append tools and conversation sections to an existing base prompt. */
    private String appendDynamicSections(String base) {
        String dynamic = buildDynamicSections();
        if (dynamic.isEmpty()) {
            return base;
        }
        return base.strip() + "\n\n" + dynamic;
    }

    /** Build the dynamic (tool + conversation) sections. */
    private String buildDynamicSections() {
        var sb = new StringBuilder();

        // Tools section
        String toolSection = buildToolSection();
        if (toolSection != null) {
            sb.append(toolSection);
        }

        // Conversation continuity section
        if (hasHistory) {
            String convSection = readResource(RES_CONVERSATION);
            if (convSection != null) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(convSection.strip());
            } else {
                // Inline default conversation instructions
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(DEFAULT_CONVERSATION);
            }
        }

        return sb.toString();
    }

    /** Build tool descriptions from registry. */
    private String buildToolSection() {
        if (toolRegistry == null || toolRegistry.isEmpty()) {
            return null;
        }

        List<ToolDescriptor> descriptors = toolRegistry.descriptors();
        if (descriptors.isEmpty()) {
            return null;
        }

        var sb = new StringBuilder();

        // Tool preamble from resource or default
        String preamble = readResource(RES_TOOLS);
        if (preamble != null) {
            sb.append(preamble.strip());
        } else {
            sb.append(DEFAULT_TOOLS_PREAMBLE);
        }

        sb.append("\n\n");

        // Tool descriptions
        for (ToolDescriptor td : descriptors) {
            sb.append("- **").append(td.name()).append("**: ").append(td.description());
            if (td.parametersSchema() != null) {
                sb.append("\n  Parameters: `").append(td.parametersSchema().strip()).append("`");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /** Minimal fallback prompt when no resource files exist. */
    private String defaultPrompt() {
        return mode == Mode.ASK
                ? "You are Talos, a local-first knowledge assistant. Answer clearly and concisely.\n"
                : "You are Talos, a local-first knowledge engine. Answer using the provided context snippets.\n";
    }

    /** Read a classpath resource, returning null if not found. */
    static String readResource(String path) {
        try (InputStream in = SystemPromptBuilder.class.getClassLoader().getResourceAsStream(path)) {
            if (in != null) return new String(in.readAllBytes());
        } catch (Exception ignored) {
            // Resource not available
        }
        return null;
    }

    // --- Default inline sections used when resource files are absent ---

    private static final String DEFAULT_TOOLS_PREAMBLE = """
            Available Tools
            You have access to the following tools. When a user's request would benefit \
            from using a tool, describe which tool you would call and with what parameters. \
            Do not fabricate tool results.""";

    private static final String DEFAULT_CONVERSATION = """
            Conversation Continuity (CRITICAL)
            - You are in a multi-turn conversation. Prior messages are provided as history.
            - ALWAYS use conversation history to understand references like "it", "that", "this".
            - If you created or discussed something in a previous turn, remember it and build on it.
            - Treat every follow-up as continuing the same conversation thread.""";

    /**
     * Estimate token count for the built prompt.
     * Uses the standard ~4 chars per token heuristic.
     */
    public int estimateTokens() {
        return Math.max(1, build().length() / 4);
    }

    @Override
    public String toString() {
        return "SystemPromptBuilder[mode=" + mode
                + ", tools=" + (toolRegistry != null && !toolRegistry.isEmpty())
                + ", history=" + hasHistory + "]";
    }
}

