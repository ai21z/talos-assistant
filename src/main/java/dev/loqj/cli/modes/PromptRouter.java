package dev.loqj.cli.modes;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Stateless, assistant-first prompt router for auto-mode.
 *
 * <h3>Design principle</h3>
 * <p><b>The assistant is the default.</b> Everything is a conversation turn
 * unless there is strong evidence that workspace retrieval is needed.
 * Retrieval is a capability that requires justification, not a default lane.
 *
 * <h3>Routing layers</h3>
 * <ol>
 *   <li><b>COMMAND</b> — structural file operations (open, show, view, ls, dir).
 *       Unambiguous syntax triggers; no LLM involved.</li>
 *   <li><b>RETRIEVE</b> — strong workspace evidence detected. Invokes the
 *       full retrieval pipeline (BM25 + KNN + rerank + context packing).</li>
 *   <li><b>ASSIST</b> — default. Plain LLM conversation with no retrieval.
 *       Handles greetings, casual chat, general questions, anything without
 *       workspace anchors.</li>
 * </ol>
 *
 * <h3>Retrieval policy</h3>
 * <p>A prompt triggers retrieval only when at least one of these is present:
 * <ul>
 *   <li>Explicit file reference: {@code RagService.java}, {@code build.gradle.kts}</li>
 *   <li>Workspace framing: "this project", "the codebase", "in our repo"</li>
 *   <li>PascalCase code identifier: {@code RagService}, {@code ModeController}</li>
 *   <li>Question + anchored technical noun: "what does <b>the pipeline</b> do?"</li>
 * </ul>
 *
 * <h3>Asymmetric cost rationale</h3>
 * <p>False retrieval (bizarre repo-grounded answer to "hey") is far worse than
 * missed retrieval (user can re-ask with {@code :mode rag}). We optimize for
 * precision: when in doubt, be an assistant.
 */
public final class PromptRouter {

    private PromptRouter() {}

    /** Routing decision for a single prompt. */
    public enum Route {
        /** Structural file command: open, show, view, ls, list, dir */
        COMMAND,
        /** Strong workspace signal present — invoke retrieval pipeline */
        RETRIEVE,
        /** Default: plain LLM conversation, no retrieval */
        ASSIST
    }

    // ── Layer 1: structural dev commands ─────────────────────────────────

    /**
     * Matches explicit file/directory commands.
     * <ul>
     *   <li>{@code ls}, {@code dir} — always</li>
     *   <li>{@code list <path>} — but not "list all/the/every/files/me"</li>
     *   <li>{@code open/show/view <path>} — but not "show me/the/all/every"</li>
     * </ul>
     */
    private static final Pattern DEV_COMMAND = Pattern.compile(
        "(?i)^\\s*(?:" +
            "(?:ls|dir)(?:\\s+|$)|" +
            "list\\s+(?!all\\b|the\\b|every\\b|files\\b|me\\b)|" +
            "(?:open|show|view)\\s+(?![\"']?(?:me|the|all|every)\\b)" +
        ")"
    );

    // ── Layer 2: retrieval signals ──────────────────────────────────────

    /**
     * Explicit file references: word.ext patterns and well-known filenames.
     * This is the strongest workspace signal.
     */
    private static final Pattern FILE_REF = Pattern.compile(
        "(?i)\\b[\\w./\\\\-]+\\.(?:" +
            "java|kt|py|js|ts|jsx|tsx|go|rs|cpp|c|h|hpp|cs|rb|php|" +
            "md|txt|yaml|yml|json|xml|html|css|scss|sql|sh|bat|ps1|" +
            "gradle|kts|toml|properties|conf|cfg|ini|env|lock|dockerfile" +
        ")\\b|" +
        "\\b(?:pom\\.xml|build\\.gradle(?:\\.kts)?|" +
            "Dockerfile|Makefile|README|LICENSE|CONTRIBUTING)\\b"
    );

    /**
     * Workspace-framing phrases: explicit references to "this project",
     * "the codebase", "our repo", etc.
     */
    private static final Pattern WORKSPACE_FRAME = Pattern.compile(
        "(?i)" +
        "\\b(?:this|the|our|my)\\s+(?:project|code(?:base)?|repo(?:sitory)?|workspace|source\\s*code)\\b|" +
        "\\b(?:in|from|of)\\s+(?:the|this|our)\\s+(?:project|code(?:base)?|repo(?:sitory)?|workspace)\\b"
    );

    /**
     * PascalCase code identifiers: names like {@code RagService},
     * {@code ModeController}, {@code ContextPacker}. Must have at least
     * two capitalized segments to avoid false positives on normal proper nouns.
     */
    private static final Pattern CODE_IDENTIFIER = Pattern.compile(
        "\\b[A-Z][a-z]+(?:[A-Z][a-z0-9]+)+\\b"
    );

    /**
     * Definite-article + technical noun: "the pipeline", "this config", etc.
     * Only triggers retrieval when the input also looks like a question
     * (checked separately), to avoid matching casual statements like
     * "the design is nice".
     */
    private static final Pattern ANCHORED_TECH_NOUN = Pattern.compile(
        "(?i)\\b(?:the|this)\\s+(?:" +
            "pipeline|service|class|method|function|interface|module|package|" +
            "config(?:uration)?|handler|controller|endpoint|" +
            "index(?:er|ing)?|chunk(?:er|ing)?|rerank(?:er|ing)?|retriev(?:al|er)|" +
            "embed(?:ding|der)?|pars(?:er|ing)|build(?:er)?|" +
            "schema|migration|database|table|" +
            "api|cli|repl|engine|stage|mode|router|factory|" +
            "error|exception|bug|test(?:s|ing)?" +
        ")\\b"
    );

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Routes a raw user prompt to a handling strategy.
     *
     * @param input raw user input (may be null/blank)
     * @return routing decision; never null
     */
    public static Route route(String input) {
        if (input == null || input.isBlank()) return Route.ASSIST;

        String trimmed = input.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        // Layer 1: structural dev commands
        if (DEV_COMMAND.matcher(trimmed).find()) {
            return Route.COMMAND;
        }

        // Layer 2: strong retrieval signals (unconditional)
        if (FILE_REF.matcher(trimmed).find()) return Route.RETRIEVE;
        if (WORKSPACE_FRAME.matcher(lower).find()) return Route.RETRIEVE;
        if (CODE_IDENTIFIER.matcher(trimmed).find()) return Route.RETRIEVE;

        // Layer 2b: retrieval signals (conditional on question context)
        //   "what does the pipeline do?" → RETRIEVE
        //   "the design is nice"         → ASSIST (not a question)
        if (isQuestionLike(lower) && ANCHORED_TECH_NOUN.matcher(lower).find()) {
            return Route.RETRIEVE;
        }

        // Layer 3: everything else → be an assistant
        return Route.ASSIST;
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    /**
     * Checks whether the input looks like a question or inquiry.
     * Matches question words, "explain/describe" commands, and trailing '?'.
     */
    static boolean isQuestionLike(String lower) {
        return lower.endsWith("?")
            || lower.startsWith("how ")    || lower.startsWith("what ")
            || lower.startsWith("where ")  || lower.startsWith("why ")
            || lower.startsWith("when ")   || lower.startsWith("who ")
            || lower.startsWith("does ")   || lower.startsWith("is ")
            || lower.startsWith("are ")    || lower.startsWith("can ")
            || lower.startsWith("should ") || lower.startsWith("could ")
            || lower.startsWith("explain ") || lower.startsWith("describe ")
            || lower.startsWith("show me ") || lower.startsWith("tell me about ");
    }
}

