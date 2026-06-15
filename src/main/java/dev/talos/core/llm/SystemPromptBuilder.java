package dev.talos.core.llm;

import dev.talos.core.util.WorkspaceManifest;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 *         .withPromptTools(promptTools)
 *         .withHistory(true)
 *         .build();
 * }</pre>
 */
public final class SystemPromptBuilder {

    // --- Resource paths for composable sections ---
    private static final String RES_IDENTITY      = "prompts/sections/identity.txt";
    private static final String RES_ASK_RULES     = "prompts/sections/ask-rules.txt";
    private static final String RES_RAG_RULES     = "prompts/sections/rag-rules.txt";
    private static final String RES_UNIFIED_RULES = "prompts/sections/unified-rules.txt";
    private static final String RES_TOOLS         = "prompts/sections/tools-preamble.txt";
    private static final String RES_TOOLS_NATIVE  = "prompts/sections/tools-preamble-native.txt";
    private static final String RES_CONVERSATION  = "prompts/sections/conversation.txt";


    private final Mode mode;
    private List<PromptToolDescriptor> promptTools;
    private boolean hasHistory;
    private boolean nativeTools;
    private boolean readOnlyToolMode;
    private boolean commandToolMode;
    private boolean directoryListingToolMode;
    private Set<String> visibleToolNames;
    private java.nio.file.Path workspace;

    /** The prompt modes. */
    public enum Mode { ASK, RAG, UNIFIED }

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

    /** Create a builder for unified assistant mode (tools + retrieval-as-tool). */
    public static SystemPromptBuilder forUnified() {
        return new SystemPromptBuilder(Mode.UNIFIED);
    }

    /** Include prompt-facing tool descriptions. */
    public SystemPromptBuilder withPromptTools(List<PromptToolDescriptor> promptTools) {
        this.promptTools = promptTools == null ? null : List.copyOf(promptTools);
        return this;
    }

    /**
     * Indicate whether the engine supports native tool calling.
     * When true, uses a shorter preamble without format instructions
     * (the native API handles format). When false, uses the full
     * preamble with JSON code-fenced format instructions as fallback.
     */
    public SystemPromptBuilder withNativeTools(boolean nativeTools) {
        this.nativeTools = nativeTools;
        return this;
    }

    /**
     * Limit the visible tool surface to read-only tools for diagnostic turns.
     *
     * <p>This is prompt/tool-surface steering only. Runtime policy remains the
     * authority that blocks mutating tools when the task contract disallows them.
     */
    public SystemPromptBuilder withReadOnlyToolMode(boolean readOnlyToolMode) {
        this.readOnlyToolMode = readOnlyToolMode;
        return this;
    }

    /** Include approved command verification tools alongside inspection tools. */
    public SystemPromptBuilder withCommandToolMode(boolean commandToolMode) {
        this.commandToolMode = commandToolMode;
        return this;
    }

    /**
     * Limit the visible tool surface to directory listing only.
     *
     * <p>Used for prompts such as "What files are in this folder?" where
     * reading file contents would violate data minimization.
     */
    public SystemPromptBuilder withDirectoryListingToolMode(boolean directoryListingToolMode) {
        this.directoryListingToolMode = directoryListingToolMode;
        if (directoryListingToolMode) {
            this.readOnlyToolMode = true;
        }
        return this;
    }

    /**
     * Restrict the textual tool section to the exact per-turn tool surface.
     *
     * <p>The provider API receives native tool specs separately, but the system
     * prompt also contains human-readable tool guidance. Keeping both surfaces
     * aligned prevents hidden or disallowed tools from being described in prompt
     * text after policy narrowing has removed them from the native tool array.
     */
    public SystemPromptBuilder withVisibleToolNames(List<String> visibleToolNames) {
        this.visibleToolNames = visibleToolNames == null
                ? null
                : Set.copyOf(visibleToolNames);
        return this;
    }

    /** Include the workspace path in the system prompt so the model knows where it's working. */
    public SystemPromptBuilder withWorkspace(java.nio.file.Path workspace) {
        this.workspace = workspace;
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
     *   <li>Load composable sections from {@code prompts/sections/}</li>
     *   <li>If the identity section exists, compose from parts (identity + mode rules + tools + conversation)</li>
     *   <li>Otherwise, use a minimal inline default prompt with dynamic sections appended</li>
     * </ol>
     */
    public String build() {
        // Composable path: load identity section, compose with mode rules + dynamic sections
        String identity = readResource(RES_IDENTITY);
        if (identity != null) {
            return buildComposed(identity);
        }

        // Fallback: inline default prompt + dynamic sections (no external resource files needed)
        return appendDynamicSections(defaultPrompt());
    }

    /** Compose from individual sections. */
    private String buildComposed(String identity) {
        var sb = new StringBuilder();

        // 1. Identity
        sb.append(identity.strip());

        // 1b. Workspace manifest (file tree + README snippet for instant awareness)
        if (workspace != null) {
            if (directoryListingToolMode) {
                sb.append("\n\nWorkspace: ").append(workspace.toAbsolutePath().toString().replace('\\', '/'));
            } else {
                String manifest = WorkspaceManifest.build(workspace);
                if (!manifest.isEmpty()) {
                    sb.append("\n\n").append(manifest);
                } else {
                    // Path doesn't exist on disk (yet) — still inject the path for awareness
                    sb.append("\n\nWorkspace: ").append(workspace.toAbsolutePath().toString().replace('\\', '/'));
                }
            }
        }

        // 2. Mode-specific rules
        String modeRes = switch (mode) {
            case ASK     -> RES_ASK_RULES;
            case RAG     -> RES_RAG_RULES;
            case UNIFIED -> RES_UNIFIED_RULES;
        };
        String modeRules = directoryListingToolMode
                ? DEFAULT_DIRECTORY_LISTING_MODE_RULES
                : readResource(modeRes);
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
        String result = base.strip();

        // Workspace manifest
        if (workspace != null) {
            if (directoryListingToolMode) {
                result += "\n\nWorkspace: " + workspace.toAbsolutePath().toString().replace('\\', '/');
            } else {
                String manifest = WorkspaceManifest.build(workspace);
                if (!manifest.isEmpty()) {
                    result += "\n\n" + manifest;
                } else {
                    result += "\n\nWorkspace: " + workspace.toAbsolutePath().toString().replace('\\', '/');
                }
            }
        }

        if (!dynamic.isEmpty()) {
            result += "\n\n" + dynamic;
        }
        return result;
    }

    /** Build the dynamic (tool + conversation) sections. */
    private String buildDynamicSections() {
        var sb = new StringBuilder();
        boolean visibleCommandTool = commandToolVisible();

        if (directoryListingToolMode) {
            sb.append(DEFAULT_DIRECTORY_LISTING_TASK_CONTRACT);
        } else if (readOnlyToolMode && visibleCommandTool) {
            sb.append(DEFAULT_VERIFICATION_TASK_CONTRACT);
        } else if (readOnlyToolMode) {
            sb.append(DEFAULT_READ_ONLY_TASK_CONTRACT);
        }

        // Tools section
        String toolSection = buildToolSection();
        if (toolSection != null) {
            if (!sb.isEmpty()) sb.append("\n\n");
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

    /** Build tool descriptions from prompt-facing descriptors. */
    private String buildToolSection() {
        if (promptTools == null || promptTools.isEmpty()) {
            return null;
        }

        List<PromptToolDescriptor> descriptors = promptTools;
        if (visibleToolNames != null) {
            descriptors = descriptors.stream()
                    .filter(td -> visibleToolNames.contains(td.name()))
                    .toList();
        }
        if (directoryListingToolMode) {
            descriptors = descriptors.stream()
                    .filter(td -> "talos.list_dir".equals(td.name()))
                    .toList();
        } else if (readOnlyToolMode) {
            descriptors = descriptors.stream()
                    .filter(td -> !td.requiresApproval()
                            || (commandToolMode && "talos.run_command".equals(td.name())))
                    .toList();
        }
        if (descriptors.isEmpty()) {
            return null;
        }

        var sb = new StringBuilder();

        // Choose preamble based on native tool support:
        // - Native: shorter preamble without format instructions (API handles format)
        // - Fallback: full preamble with JSON code-fenced format instructions
        boolean visibleCommandTool = commandToolVisible();
        if (directoryListingToolMode && nativeTools) {
            sb.append(DEFAULT_DIRECTORY_LISTING_TOOLS_PREAMBLE_NATIVE);
        } else if (directoryListingToolMode) {
            sb.append(DEFAULT_DIRECTORY_LISTING_TOOLS_PREAMBLE);
        } else if (readOnlyToolMode && visibleCommandTool && nativeTools) {
            sb.append(DEFAULT_VERIFICATION_TOOLS_PREAMBLE_NATIVE);
        } else if (readOnlyToolMode && visibleCommandTool) {
            sb.append(DEFAULT_VERIFICATION_TOOLS_PREAMBLE);
        } else if (readOnlyToolMode && nativeTools) {
            sb.append(DEFAULT_READ_ONLY_TOOLS_PREAMBLE_NATIVE);
        } else if (readOnlyToolMode) {
            sb.append(DEFAULT_READ_ONLY_TOOLS_PREAMBLE);
        } else if (nativeTools) {
            String nativePreamble = readResource(RES_TOOLS_NATIVE);
            if (nativePreamble != null) {
                sb.append(nativePreamble.strip());
            } else {
                sb.append(DEFAULT_TOOLS_PREAMBLE_NATIVE);
            }
        } else {
            String preamble = readResource(RES_TOOLS);
            if (preamble != null) {
                sb.append(preamble.strip());
            } else {
                sb.append(DEFAULT_TOOLS_PREAMBLE);
            }
        }

        sb.append("\n\n");

        // Tool descriptions
        for (PromptToolDescriptor td : descriptors) {
            sb.append("- **").append(td.name()).append("**: ").append(td.description());
            if (td.parametersSchema() != null) {
                sb.append("\n  Parameters: `").append(td.parametersSchema().strip()).append("`");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private boolean commandToolVisible() {
        return commandToolMode
                && (visibleToolNames == null || visibleToolNames.contains("talos.run_command"));
    }

    /** Minimal fallback prompt when no resource files exist. */
    private String defaultPrompt() {
        return switch (mode) {
            case ASK     -> "You are Talos, a local-first workspace assistant. Answer clearly and concisely.\n";
            case RAG     -> "You are Talos, a local-first workspace assistant. Answer using the provided context snippets.\n";
            case UNIFIED -> "You are Talos, a local-first workspace assistant with full tool access. Use tools proactively for file operations and project questions.\n";
        };
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
            You have access to the following tools. To invoke a tool, emit a tool call as a JSON object in EXACTLY this format:
            
            ```json
            {"name": "tool_name", "parameters": {"key": "value"}}
            ```
            
            Example — reading a file:
            ```json
            {"name": "talos.read_file", "parameters": {"path": "src/Main.java"}}
            ```
            
            Example — creating/writing a file:
            ```json
            {"name": "talos.write_file", "parameters": {"path": "output/summary.txt", "content": "This is the file content.\\nLine two.\\n"}}
            ```
            
            FILE CREATION AND MODIFICATION (CRITICAL):
            - You CAN create files. You have talos.write_file. USE IT.
            - When the user asks you to CREATE, WRITE, SAVE, PUT, or GENERATE a file → call talos.write_file with the full content.
            - When the user asks you to EDIT an existing file → call talos.edit_file with old_string and new_string.
            - NEVER say "I cannot create files." NEVER just print code in a code block. ALWAYS call the tool.
            - After writing or editing, briefly confirm what you did.
            
            Rules:
            - CONTEXT FIRST: If the provided context snippets already answer the user's question, respond directly from context. Do NOT call a tool when the answer is already in front of you.
            - Only call a tool when you need to PERFORM an action (read a file, run a search, etc.) that the current context cannot satisfy.
            - Emit each tool call as a JSON code block (```json). The JSON must have "name" and "parameters" keys exactly as shown.
            - You may emit multiple tool call blocks in one response.
            - After each tool call, the result will be returned in a follow-up message. Use the result to answer the user.
            - Do NOT fabricate tool results. Wait for the actual result.
            - Only call tools that are listed below. Do not invent tool names.
            - If a tool returns an error, explain the issue to the user.""";

    private static final String DEFAULT_DIRECTORY_LISTING_MODE_RULES = """
            Directory Listing Mode
            The user is asking only for file or directory names. Minimize data access.
            Use the listed directory tool once, then answer with names only.
            Do not infer, summarize, or inspect file contents unless the user asks for that in a later turn.""";

    private static final String DEFAULT_TOOLS_PREAMBLE_NATIVE = """
            Available Tools
            You have access to the following tools. The runtime handles tool invocation \
            format automatically — just decide WHICH tool to call and with WHAT parameters.
            
            FILE CREATION AND MODIFICATION (CRITICAL):
            - You CAN create files. You have talos.write_file. USE IT.
            - When the user asks you to CREATE, WRITE, SAVE, PUT, or GENERATE a file → call talos.write_file with the full content.
            - When the user asks you to EDIT an existing file → call talos.edit_file with old_string and new_string.
            - NEVER say "I cannot create files." NEVER just print code in a code block. ALWAYS call the tool.
            - After writing or editing, briefly confirm what you did.
            
            Rules:
            - CONTEXT FIRST: If the provided context snippets already answer the user's question, respond directly from context. Do NOT call a tool when the answer is already in front of you.
            - Only call a tool when you need to PERFORM an action (read a file, run a search, etc.) that the current context cannot satisfy.
            - You may call multiple tools in one response.
            - After each tool call, the result will be returned in a follow-up message. Use the result to answer the user.
            - Do NOT fabricate tool results. Wait for the actual result.
            - Only call tools that are listed below. Do not invent tool names.
            - If a tool returns an error, explain the issue to the user.""";

    private static final String DEFAULT_READ_ONLY_TOOLS_PREAMBLE = """
            Available Tools
            This turn is read-only or diagnostic. Only inspection tools are listed for this turn.
            Do not call write/edit tools. If you identify a possible fix, describe it and wait for an explicit change request.

            To invoke a tool, emit a tool call as a JSON object in EXACTLY this format:

            ```json
            {"name": "tool_name", "parameters": {"key": "value"}}
            ```

            When to call:
            - Workspace questions -> talos.list_dir, talos.read_file, talos.grep, or talos.retrieve.
            - Small workspaces -> list files, then read the obvious primary files before answering.
            - Search tasks -> talos.grep for exact text or selectors.
            - Semantic cross-file search on a large indexed workspace -> talos.retrieve.

            Rules:
            - Wait for tool results before answering. Do not fabricate results.
            - Only call tools listed below. Do not invent names.
            - Never call the same tool with the same parameters twice in one turn.""";

    private static final String DEFAULT_READ_ONLY_TASK_CONTRACT = """
            Current Turn Contract
            - This specific user turn is read-only or diagnostic.
            - Do not call talos.write_file or talos.edit_file in this turn.
            - Inspect with read-only tools, then describe findings and possible fixes without applying them.
            - Wait for an explicit change request before using mutating tools.""";

    private static final String DEFAULT_VERIFICATION_TASK_CONTRACT = """
            Current Turn Contract
            - This specific user turn is verification-oriented.
            - Do not call talos.write_file or talos.edit_file in this turn.
            - You may call listed inspection tools and approved command verification profiles.
            - Command execution is runtime-approved, bounded, and profile-based; never invent shell commands.""";

    private static final String DEFAULT_DIRECTORY_LISTING_TASK_CONTRACT = """
            Current Turn Contract
            - This specific user turn asks only to list directory entries.
            - Use talos.list_dir only.
            - Do not inspect, search, retrieve, summarize, or infer file contents unless the user explicitly asks for that in a later turn.
            - Do not call talos.write_file or talos.edit_file in this turn.""";

    private static final String DEFAULT_READ_ONLY_TOOLS_PREAMBLE_NATIVE = """
            Available Tools
            This turn is read-only or diagnostic. Only inspection tools are listed for this turn.
            Do not call write/edit tools. If you identify a possible fix, describe it and wait for an explicit change request.
            The runtime handles tool invocation format automatically - decide which listed inspection tool to call and with what parameters.

            When to call:
            - Workspace questions -> talos.list_dir, talos.read_file, talos.grep, or talos.retrieve.
            - Small workspaces -> list files, then read the obvious primary files before answering.
            - Search tasks -> talos.grep for exact text or selectors.
            - Semantic cross-file search on a large indexed workspace -> talos.retrieve.

            Rules:
            - Wait for tool results before answering. Do not fabricate results.
            - Only call tools listed below. Do not invent names.
            - Never call the same tool with the same parameters twice in one turn.""";

    private static final String DEFAULT_VERIFICATION_TOOLS_PREAMBLE = """
            Available Tools
            This turn is verification-oriented. Only inspection tools and approved command verification tools are listed.
            Do not call write/edit tools. Do not invent shell commands.

            To invoke a tool, emit a tool call as a JSON object in EXACTLY this format:

            ```json
            {"name": "tool_name", "parameters": {"key": "value"}}
            ```

            When to call:
            - Workspace evidence -> talos.list_dir, talos.read_file, talos.grep, or talos.retrieve.
            - Build/test verification -> talos.run_command with an approved Gradle profile.

            Rules:
            - Wait for tool results before answering. Do not fabricate results.
            - Only call tools listed below. Do not invent names.
            - Never provide raw shell, cmd.exe, PowerShell, package install, network, or git write commands.""";

    private static final String DEFAULT_VERIFICATION_TOOLS_PREAMBLE_NATIVE = """
            Available Tools
            This turn is verification-oriented. Only inspection tools and approved command verification tools are listed.
            Do not call write/edit tools. Do not invent shell commands.
            The runtime handles tool invocation format automatically - decide which listed tool to call and with what parameters.

            When to call:
            - Workspace evidence -> talos.list_dir, talos.read_file, talos.grep, or talos.retrieve.
            - Build/test verification -> talos.run_command with an approved Gradle profile.

            Rules:
            - Wait for tool results before answering. Do not fabricate results.
            - Only call tools listed below. Do not invent names.
            - Never provide raw shell, cmd.exe, PowerShell, package install, network, or git write commands.""";

    private static final String DEFAULT_DIRECTORY_LISTING_TOOLS_PREAMBLE = """
            Available Tools
            This turn is a directory-listing task. Only talos.list_dir is listed for this turn.

            To invoke a tool, emit a tool call as a JSON object in EXACTLY this format:

            ```json
            {"name": "tool_name", "parameters": {"key": "value"}}
            ```

            Rules:
            - Call talos.list_dir on "." unless the user named another in-workspace directory.
            - Answer with directory entries only.
            - Do not read, grep, retrieve, summarize, or infer file contents.
            - Only call tools listed below. Do not invent names.""";

    private static final String DEFAULT_DIRECTORY_LISTING_TOOLS_PREAMBLE_NATIVE = """
            Available Tools
            This turn is a directory-listing task. Only talos.list_dir is listed for this turn.
            The runtime handles tool invocation format automatically.

            Rules:
            - Call talos.list_dir on "." unless the user named another in-workspace directory.
            - Answer with directory entries only.
            - Do not read, grep, retrieve, summarize, or infer file contents.
            - Only call tools listed below. Do not invent names.""";

    private static final String DEFAULT_CONVERSATION = """
            Conversation Continuity (CRITICAL)
            - You are in a multi-turn conversation. Prior messages are provided as history.
            - ALWAYS use conversation history to understand references like "it", "that", "this".
            - If you created or discussed something in a previous turn, remember it and build on it.
            - Treat every follow-up as continuing the same conversation thread.
            - YOUR LAST RESPONSE is the most important context. If the user says "make it better" or "try again", work from your most recent output.
            - When refining creative output (ASCII art, code, prose), modify the specific artifact — do NOT start from scratch.
            - NEVER say "I don't have access to our previous conversation" — the history IS provided to you.
            - If a [Conversation context] summary appears, treat it as established facts.""";

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
                + ", tools=" + (promptTools != null && !promptTools.isEmpty())
                + ", nativeTools=" + nativeTools
                + ", readOnlyToolMode=" + readOnlyToolMode
                + ", commandToolMode=" + commandToolMode
                + ", directoryListingToolMode=" + directoryListingToolMode
                + ", history=" + hasHistory + "]";
    }
}
