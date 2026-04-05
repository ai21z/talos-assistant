package dev.loqj.cli.modes;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stateless heuristic classifier that determines the likely intent
 * of a user prompt for auto-mode routing.
 *
 * <p>Design: cheap string analysis (no I/O, no model calls). Aims for
 * high precision on clear-cut cases; returns {@link Intent#UNKNOWN}
 * when uncertain, letting the caller fall through to the default sweep.
 *
 * <p>Classification priority:
 * <ol>
 *   <li>DEV — explicit file/directory commands (open, ls, show)</li>
 *   <li>CHAT — greetings, pleasantries, short non-technical input</li>
 *   <li>RAG — file references, code keywords, workspace questions</li>
 *   <li>UNKNOWN — ambiguous; let the mode sweep decide</li>
 * </ol>
 */
public final class IntentClassifier {

    private IntentClassifier() {}

    /** Classified intent for auto-mode routing. */
    public enum Intent { CHAT, RAG, DEV, UNKNOWN }

    // ── Patterns ─────────────────────────────────────────────────────────

    /** Greetings and pleasantries — common casual openers. */
    private static final Pattern GREETING = Pattern.compile(
        "(?i)^\\s*" +
        "(?:hey|hi|hello|howdy|yo|sup|hiya|heya|hola|aloha|" +
        "good\\s+(?:morning|afternoon|evening|night|day)|" +
        "what'?s?\\s+up|whats\\s+up|wassup|" +
        "thanks?(?:\\s+you)?|thank\\s+you|thx|ty|cheers|" +
        "bye|goodbye|good\\s*bye|see\\s+you|later|ciao|" +
        "how\\s+are\\s+you|how'?s?\\s+it\\s+going|" +
        "nice|cool|ok(?:ay)?|sure|yep|yeah|yea|nope|no|yes|" +
        "lol|haha|wow|oops|hmm+|ah+|oh+|" +
        "please|help(?:\\s+me)?|" +
        "who\\s+are\\s+you|what\\s+(?:are|can)\\s+you|" +
        "tell\\s+me\\s+(?:about\\s+yourself|a\\s+joke|something))" +
        "[\\s!?.,:;)*~'\"]*$"
    );

    /** Farewell or acknowledgment — typically end-of-conversation turns. */
    private static final Pattern ACK_OR_FAREWELL = Pattern.compile(
        "(?i)^\\s*(?:got\\s+it|understood|makes\\s+sense|perfect|great|awesome|" +
        "sounds\\s+good|all\\s+good|noted|roger|copy|clear|fine|done)\\s*[!.]*$"
    );

    /** File references: paths with extensions or well-known filenames. */
    private static final Pattern FILE_REF = Pattern.compile(
        "(?i)\\b\\w+\\.(?:java|kt|py|js|ts|jsx|tsx|go|rs|cpp|c|h|hpp|cs|rb|php|" +
        "md|txt|yaml|yml|json|xml|html|css|scss|sql|sh|bat|ps1|gradle|kts|toml|" +
        "properties|dockerfile|conf|cfg|ini|env|lock)\\b|" +
        "\\b(?:pom\\.xml|build\\.gradle|Dockerfile|Makefile|README|LICENSE|CONTRIBUTING)\\b"
    );

    /** Code/workspace keywords that strongly suggest retrieval is needed. */
    private static final Set<String> RAG_KEYWORDS = Set.of(
        "class", "method", "function", "interface", "enum", "record", "module",
        "package", "import", "implement", "extends", "override",
        "error", "exception", "bug", "fix", "issue", "stacktrace", "stack trace",
        "test", "tests", "testing",
        "build", "compile", "gradle", "maven", "dependency", "dependencies",
        "index", "indexing", "indexed", "chunk", "chunks",
        "retrieval", "pipeline", "stage", "rerank",
        "config", "configuration", "setting", "settings",
        "explain", "describe", "analyze", "analyse", "compare", "difference",
        "where", "find", "search", "locate", "look for",
        "how does", "how do", "what does", "what is the", "what are the",
        "why does", "why is", "when does",
        "show me", "walk me through",
        "refactor", "rename", "move", "extract", "inline",
        "api", "endpoint", "controller", "service", "repository",
        "database", "schema", "migration", "table",
        "architecture", "design", "pattern", "structure",
        "workspace", "project", "codebase", "repo",
        "file", "files", "directory", "folder", "source", "sources"
    );

    /**
     * DevMode triggers. 'ls/list/dir' are always DEV.
     * 'open/show/view' are DEV only when followed by a path-like token
     * (not natural language like "show me the config").
     */
    private static final Pattern DEV_COMMAND = Pattern.compile(
        "(?i)^\\s*(?:" +
            "(?:ls|dir)(?:\\s+|$)|" +                                        // ls / dir (always)
            "list\\s+(?!all\\b|the\\b|every\\b|files\\b|me\\b)|" +          // list <path> (not "list all/the/files")
            "(?:open|show|view)\\s+(?![\"']?(?:me|the|all|every)\\b)" +     // open/show/view <path> (not "show me...")
        ")"
    );

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Classifies the given user input into an intent.
     *
     * @param input raw user input (may be null/blank)
     * @return classified intent; never null
     */
    public static Intent classify(String input) {
        if (input == null || input.isBlank()) return Intent.UNKNOWN;

        String trimmed = input.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        // 1. DevMode commands are unmistakable
        if (DEV_COMMAND.matcher(trimmed).find()) {
            return Intent.DEV;
        }

        // 2. Check for file references or code keywords → RAG
        //    Do this BEFORE greeting check so "hey explain RagService.java"
        //    correctly routes to RAG, not chat.
        if (hasFileReference(trimmed)) {
            return Intent.RAG;
        }
        if (hasRagKeyword(lower)) {
            return Intent.RAG;
        }

        // 3. Greeting / pleasantry / acknowledgment → CHAT
        if (GREETING.matcher(trimmed).matches()) {
            return Intent.CHAT;
        }
        if (ACK_OR_FAREWELL.matcher(trimmed).matches()) {
            return Intent.CHAT;
        }

        // 4. Very short input (≤ 3 words) with no code signals → CHAT
        //    "hey there", "what now", "hmm okay" — clearly conversational
        int wordCount = trimmed.split("\\s+").length;
        if (wordCount <= 3) {
            return Intent.CHAT;
        }

        // 5. Questions that start with question words but have no code keywords
        //    are ambiguous — let the sweep handle them.
        //    "What time is it?" → UNKNOWN → sweep → AskMode eventually

        return Intent.UNKNOWN;
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private static boolean hasFileReference(String input) {
        return FILE_REF.matcher(input).find();
    }

    private static boolean hasRagKeyword(String lower) {
        for (String kw : RAG_KEYWORDS) {
            // Use word boundary matching for single words,
            // substring matching for multi-word keywords
            if (kw.contains(" ")) {
                if (lower.contains(kw)) return true;
            } else {
                // Match as whole word
                if (Pattern.compile("\\b" + Pattern.quote(kw) + "\\b").matcher(lower).find()) {
                    return true;
                }
            }
        }
        return false;
    }
}




