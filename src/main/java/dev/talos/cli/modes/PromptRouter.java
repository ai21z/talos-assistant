package dev.talos.cli.modes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assistant-first prompt router for auto-mode with conversation context.
 *
 * <h3>Design principle</h3>
 * <p><b>The assistant is the default.</b> Everything is a conversation turn
 * unless there is strong evidence that workspace retrieval is needed.
 * Retrieval is a capability that requires justification, not a default lane.
 *
 * <h3>Routing layers</h3>
 * <ol>
 *   <li><b>COMMAND</b> — structural file operations: open, show, view, ls, dir,
 *       including "show me &lt;file&gt;" compound commands
 *       (supports quoted paths for files with spaces).</li>
 *   <li><b>RETRIEVE</b> — strong workspace evidence:
 *       <ul>
 *         <li>Workspace framing: "this project", "the codebase", "our repo"</li>
 *         <li>File reference: {@code RagService.java}, {@code build.gradle.kts}</li>
 *         <li>PascalCase identifier <b>in question or action context</b></li>
 *         <li>Anchored tech noun (the/this + tech noun) <b>in question or action context</b></li>
 *         <li>PascalCase identifier <b>confirmed in workspace index</b> (no question
 *             required — the index disambiguates code symbols from brand names)</li>
 *       </ul></li>
 *   <li><b>Sticky retrieval</b> — follow-up turns inherit retrieval context
 *       from the previous turn (e.g. "what about the parse method?" after
 *       a retrieval turn). Social follow-ups are excluded.</li>
 *   <li><b>ASSIST</b> — default. Plain LLM conversation with no retrieval.
 *       Handles greetings, casual chat, general questions, anything without
 *       workspace anchors.</li>
 * </ol>
 *
 * <h3>Retrieval policy</h3>
 * <table>
 *   <caption>Retrieval decision matrix</caption>
 *   <tr><th>Signal</th><th>Decision</th></tr>
 *   <tr><td>Workspace framing ("this project", "the codebase")</td>
 *       <td><b>RETRIEVE</b> — always</td></tr>
 *   <tr><td>File reference (path with extension, pom.xml, etc.)</td>
 *       <td><b>RETRIEVE</b> — always</td></tr>
 *   <tr><td>PascalCase identifier + question or action context</td>
 *       <td><b>RETRIEVE</b></td></tr>
 *   <tr><td>PascalCase identifier without question/action context</td>
 *       <td><b>ASSIST</b> — not enough evidence (unless workspace checker confirms)</td></tr>
 *   <tr><td>PascalCase identifier confirmed in workspace index</td>
 *       <td><b>RETRIEVE</b> — workspace evidence replaces question gating</td></tr>
 *   <tr><td>"the/this" + tech noun + question or action context</td>
 *       <td><b>RETRIEVE</b></td></tr>
 *   <tr><td>"the/this" + tech noun without question/action context</td>
 *       <td><b>ASSIST</b> — statement, not inquiry or action</td></tr>
 *   <tr><td>Follow-up after RETRIEVE (not social)</td>
 *       <td><b>RETRIEVE</b> — sticky context</td></tr>
 *   <tr><td>Social follow-up after RETRIEVE ("thanks", "what about you?")</td>
 *       <td><b>ASSIST</b></td></tr>
 *   <tr><td>No workspace signals</td>
 *       <td><b>ASSIST</b> — always</td></tr>
 * </table>
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
     *   <li>{@code ls}, {@code dir} — always (standalone or with path)</li>
     *   <li>{@code list} — standalone (workspace listing)</li>
     *   <li>{@code list <path>} — but not "list all/the/every/files/me"</li>
     *   <li>{@code open/view <path>} — but not "open me/the/all/every"</li>
     *   <li>{@code show <path>} — but not "show me/the/all/every/how/why/what"
     *       ("show me &lt;file&gt;" is caught by the compound check instead)</li>
     * </ul>
     */
    private static final Pattern DEV_COMMAND = Pattern.compile(
        "(?i)^\\s*(?:" +
            "(?:ls|dir)(?:\\s+|$)|" +
            "list\\s*$|" +
            "list\\s+(?!all\\b|the\\b|every\\b|files\\b|me\\b)\\S|" +
            "(?:open|view)\\s+(?![\"']?(?:me|the|all|every)\\b)\\S|" +
            "show\\s+(?![\"']?(?:me|the|all|every|how|why|what)\\b)\\S" +
        ")"
    );

    /**
     * "show me [the] &lt;file&gt;" — compound command prefix.
     * Catches natural requests like "show me build.gradle.kts" as direct file
     * display, while letting "show me how X works" fall through to retrieval.
     */
    private static final Pattern SHOW_ME_PREFIX = Pattern.compile(
        "(?i)^\\s*show\\s+me\\s+(?:the\\s+)?"
    );

    // ── Layer 2: retrieval signals ──────────────────────────────────────

    /**
     * Explicit file references: word.ext patterns and well-known filenames.
     * This is the strongest workspace signal — unconditional retrieval trigger.
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
     * "the codebase", "our repo", etc. Unconditional retrieval trigger.
     */
    private static final Pattern WORKSPACE_FRAME = Pattern.compile(
        "(?i)" +
        "\\b(?:this|the|our|my)\\s+(?:project|code(?:base)?|repo(?:sitory)?|workspace|source\\s*code|" +
            "site|app(?:lication)?|webapp|folder|directory|file\\s*structure|project\\s*structure|setup)\\b|" +
        "\\b(?:in|from|of)\\s+(?:the|this|our)\\s+(?:project|code(?:base)?|repo(?:sitory)?|workspace|" +
            "site|app(?:lication)?|folder|directory)\\b"
    );

    /**
     * PascalCase code identifiers: names like {@code RagService},
     * {@code ModeController}. Must have at least two capitalized segments.
     *
     * <p><b>Requires question or action context to trigger retrieval.</b>
     * PascalCase alone is insufficient because proper nouns and brand names
     * (PowerPoint, LinkedIn, YouTube, IntelliJ) also use PascalCase.
     * Question or action context disambiguates code inquiries from general
     * mentions.
     */
    private static final Pattern CODE_IDENTIFIER = Pattern.compile(
        "\\b[A-Z][a-z]+(?:[A-Z][a-z0-9]+)+\\b"
    );

    /**
     * Definite-article + technical noun: "the pipeline", "this constructor",
     * "the Sandbox class", etc.
     * Covers architecture patterns, language constructs (constructor, enum, record,
     * annotation, field, variable, property, import, implementation, dependency),
     * infrastructure terms, and domain-specific retrieval/indexing vocabulary.
     *
     * <p>Allows an optional intervening qualifier word so that
     * "the Sandbox class" and "this Config handler" are matched in addition
     * to direct adjacency like "the pipeline" and "this constructor".
     *
     * <p>Only triggers retrieval when the input also looks like a question
     * or action (checked separately), to avoid matching casual statements
     * like "the design is nice".
     */
    private static final Pattern ANCHORED_TECH_NOUN = Pattern.compile(
        "(?i)\\b(?:the|this)\\s+(?:\\S+\\s+)?(?:" +
            "pipeline|service|class|method|function|interface|module|package|" +
            "constructor|enum(?:eration)?|record|annotation|" +
            "variable|field|property|properties|import|" +
            "impl(?:ementation)?|dependency|dependencies|" +
            "config(?:uration)?|handler|controller|endpoint|" +
            "index(?:er|ing)?|chunk(?:er|ing)?|rerank(?:er|ing)?|retriev(?:al|er)|" +
            "embed(?:ding|der)?|pars(?:er|ing)|build(?:er)?|" +
            "schema|migration|database|table|" +
            "api|cli|repl|engine|stage|mode|router|factory|" +
            "error|exception|bug|test(?:s|ing)?|" +
            "directory|folder|file|page|component|view|template|layout|" +
            "stylesheet|style(?:s)?|script|markup|element|section|form|" +
            "header|footer|sidebar|container|wrapper|route|" +
            "plugin|middleware|filter|listener|observer|" +
            "model|entity|dto|dao|repository|store|" +
            "util(?:ity)?|helper|adapter|provider|" +
            "server|client|socket|connection|request|response" +
        ")\\b"
    );

    // ── Layer 3: follow-up detection ────────────────────────────────────

    /**
     * Continuation and pronoun-reference patterns that indicate a follow-up.
     * Must appear at the start of the input (after prefix stripping).
     * Includes "one more [thing/question]" as a continuation signal.
     */
    private static final Pattern FOLLOW_UP = Pattern.compile(
        "(?i)^\\s*(?:" +
            "(?:what|how|where|why|who)\\s+(?:about|else)\\b|" +
            "(?:and|also|but)\\s+(?:what|how|where|why|who|the|that|this)\\b|" +
            "(?:tell|show)\\s+me\\s+more\\b|" +
            "(?:go\\s+on|continue|more\\s+details?|elaborate)\\b|" +
            "(?:what|how)\\s+(?:does|is|are|about|of)\\s+(?:it|that|this|those|these)\\b|" +
            "one\\s+more(?:\\s+(?:thing|question))?\\b" +
        ")"
    );

    /**
     * Social/conversational follow-ups that should NOT inherit retrieval context.
     * Suppresses sticky-retrieval upgrade even when {@link #FOLLOW_UP} matches.
     */
    private static final Pattern SOCIAL_FOLLOW_UP = Pattern.compile(
        "(?i)(?:" +
            "(?:about|for|and)\\s+you\\b|" +
            "how\\s+are\\s+you\\b|" +
            "\\bthanks?\\b|\\bthank\\s+you\\b|" +
            "(?:that'?s?|it'?s?|this\\s+is)\\s+(?:great|good|nice|cool|awesome|helpful|fine|ok(?:ay)?|interesting)\\b|" +
            "no\\s+(?:thanks|problem|worries)\\b|" +
            "(?:bye|goodbye|see\\s+you)\\b" +
        ")"
    );

    /**
     * Common conversational prefixes stripped before question-word and
     * follow-up detection. Covers greetings ("hey", "hello") and
     * acknowledgments ("sure", "right", "actually", "cool", "yeah"),
     * ensuring "cool, what does the parser do" is recognized as question-like
     * and "actually, what about it" is recognized as a follow-up.
     */
    private static final Pattern CONVERSATIONAL_PREFIX = Pattern.compile(
        "(?i)^(?:hey|hi|hello|ok(?:ay)?|so|well|um+|hmm+|oh|ah|yo|alright|" +
            "sure|right|actually|cool|yeah|yep|yup),?\\s+"
    );

    // ── Result type ──────────────────────────────────────────────────────

    /**
     * Structured routing result with human-readable explanation.
     *
     * <p>Used by {@code :route} diagnostic command and debug logging to
     * expose the reasoning behind each routing decision.
     *
     * @param route   the routing decision
     * @param trigger concise label for the decisive signal (e.g. "file reference")
     * @param steps   ordered trace of checks performed; empty list if not requested
     */
    public record RouteResult(Route route, String trigger, List<String> steps) {
        public RouteResult {
            steps = List.copyOf(steps);   // defensive copy, immutable
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Routes a raw user prompt (stateless — no conversation context).
     *
     * @param input raw user input (may be null/blank)
     * @return routing decision; never null
     */
    public static Route route(String input) {
        return route(input, null);
    }

    /**
     * Routes a raw user prompt with conversation context.
     *
     * <p>When {@code lastRoute} is {@link Route#RETRIEVE} and the current input
     * looks like a non-social follow-up, the routing is upgraded from ASSIST to
     * RETRIEVE, allowing multi-turn retrieval conversations.
     *
     * @param input     raw user input (may be null/blank)
     * @param lastRoute route of the previous turn, or null if first turn
     * @return routing decision; never null
     */
    public static Route route(String input, Route lastRoute) {
        return route(input, lastRoute, null);
    }

    /**
     * Routes a raw user prompt with conversation context and optional workspace
     * symbol resolution.
     *
     * <p>Delegates to {@link #explainRoute} and returns only the route.
     * Use {@code explainRoute()} when the reasoning trace is needed.
     *
     * @param input     raw user input (may be null/blank)
     * @param lastRoute route of the previous turn, or null if first turn
     * @param checker   workspace symbol checker, or null to skip workspace lookup
     * @return routing decision; never null
     */
    public static Route route(String input, Route lastRoute, WorkspaceSymbolChecker checker) {
        return explainRoute(input, lastRoute, checker).route();
    }

    /**
     * Routes a raw user prompt and returns a full {@link RouteResult} with
     * the routing decision, trigger label, and evaluation trace.
     *
     * <p>This is the single code path for all routing. The convenience
     * {@code route()} methods delegate here and discard the explanation.
     *
     * @param input     raw user input (may be null/blank)
     * @param lastRoute route of the previous turn, or null if first turn
     * @param checker   workspace symbol checker, or null to skip workspace lookup
     * @return structured result; never null
     */
    public static RouteResult explainRoute(String input, Route lastRoute, WorkspaceSymbolChecker checker) {
        List<String> steps = new ArrayList<>();

        if (input == null || input.isBlank()) {
            return new RouteResult(Route.ASSIST, "empty input", steps);
        }

        String trimmed = input.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        // Layer 1: structural dev commands
        if (DEV_COMMAND.matcher(trimmed).find()) {
            steps.add("matched dev command pattern");
            return new RouteResult(Route.COMMAND, "dev command", steps);
        }
        steps.add("no dev command match");

        // Layer 1b: "show me [the] <file>" compound command
        if (isShowMeFile(trimmed)) {
            steps.add("matched 'show me <file>' pattern");
            return new RouteResult(Route.COMMAND, "show-me-file compound command", steps);
        }
        steps.add("no show-me-file match");

        // Layer 2: strong retrieval signals (unconditional)
        if (WORKSPACE_FRAME.matcher(lower).find()) {
            steps.add("matched workspace framing phrase");
            return new RouteResult(Route.RETRIEVE, "workspace framing", steps);
        }
        steps.add("no workspace framing");

        if (FILE_REF.matcher(trimmed).find()) {
            steps.add("matched file reference pattern");
            return new RouteResult(Route.RETRIEVE, "file reference", steps);
        }
        steps.add("no file reference");

        // Layer 2b: retrieval signals requiring question or action context
        boolean isQ = isQuestionLike(lower);
        boolean isAction = isActionLike(lower);
        boolean hasIntentContext = isQ || isAction;

        if (hasIntentContext && CODE_IDENTIFIER.matcher(trimmed).find()) {
            String intentType = isAction ? "action" : "question";
            steps.add(intentType + " context + PascalCase identifier");
            return new RouteResult(Route.RETRIEVE,
                    "PascalCase identifier in " + intentType, steps);
        }
        if (hasIntentContext && ANCHORED_TECH_NOUN.matcher(lower).find()) {
            String intentType = isAction ? "action" : "question";
            steps.add(intentType + " context + anchored tech noun");
            return new RouteResult(Route.RETRIEVE,
                    "anchored tech noun in " + intentType, steps);
        }
        if (hasIntentContext) {
            steps.add((isAction ? "action" : "question") +
                    "-like but no code identifier or anchored tech noun");
        } else {
            steps.add("not question-like or action-like");
        }

        // Layer 2c: workspace-aware PascalCase resolution
        if (checker != null) {
            if (hasWorkspaceSymbol(trimmed, checker)) {
                steps.add("PascalCase confirmed in workspace index");
                return new RouteResult(Route.RETRIEVE, "workspace symbol match", steps);
            }
            steps.add("no workspace symbol match");
        } else {
            steps.add("workspace checker not available");
        }

        // Layer 3: sticky retrieval for follow-ups
        if (lastRoute == Route.RETRIEVE) {
            if (isFollowUp(lower)) {
                steps.add("follow-up after RETRIEVE turn");
                return new RouteResult(Route.RETRIEVE, "sticky retrieval follow-up", steps);
            }
            steps.add("after RETRIEVE but not a follow-up pattern");
        } else if (lastRoute != null) {
            steps.add("last route was " + lastRoute + " (not RETRIEVE)");
        } else {
            steps.add("no conversation context");
        }

        // Layer 4: everything else → be an assistant
        return new RouteResult(Route.ASSIST, "default — no retrieval evidence", steps);
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    /**
     * Checks if the input matches "show me [the] &lt;file-reference&gt;".
     * Supports quoted paths: {@code show me "docs/My Guide.md"}.
     * For unquoted paths, the first whitespace-delimited token after the prefix
     * must be a file reference for this to be a direct file display command.
     */
    private static boolean isShowMeFile(String trimmed) {
        Matcher m = SHOW_ME_PREFIX.matcher(trimmed);
        if (!m.find()) return false;
        String rest = trimmed.substring(m.end()).trim();
        if (rest.isEmpty()) return false;

        // Quoted path: show me "docs/My Guide.md" or show me 'README.md'
        if (rest.length() > 2 && (rest.charAt(0) == '"' || rest.charAt(0) == '\'')) {
            char q = rest.charAt(0);
            int close = rest.indexOf(q, 1);
            if (close > 1) {
                return FILE_REF.matcher(rest.substring(1, close)).find();
            }
        }

        // Unquoted: check first whitespace-delimited token
        String firstToken = rest.split("\\s+", 2)[0];
        return FILE_REF.matcher(firstToken).find();
    }

    /**
     * Checks whether the input looks like a question or inquiry.
     *
     * <p>Strips common conversational prefixes ("hey", "ok", "so", etc.)
     * before checking for question words, so that "hey what is RagService"
     * is correctly recognized as question-like.
     */
    static boolean isQuestionLike(String lower) {
        String stripped = CONVERSATIONAL_PREFIX.matcher(lower).replaceFirst("");
        return stripped.endsWith("?")
            || stripped.startsWith("how ")    || stripped.startsWith("what ")
            || stripped.startsWith("where ")  || stripped.startsWith("why ")
            || stripped.startsWith("when ")   || stripped.startsWith("who ")
            || stripped.startsWith("does ")   || stripped.startsWith("is ")
            || stripped.startsWith("are ")    || stripped.startsWith("can ")
            || stripped.startsWith("should ") || stripped.startsWith("could ")
            || stripped.startsWith("explain ") || stripped.startsWith("describe ")
            || stripped.startsWith("show me ") || stripped.startsWith("tell me about ");
    }

    /**
     * Checks whether the input looks like an imperative action request.
     *
     * <p>Action verbs like "write", "create", "fix", "refactor" indicate
     * the user wants to <em>do something</em> (often involving tool use).
     * When combined with a PascalCase identifier or an anchored tech noun,
     * these trigger retrieval so that the LLM has workspace context for the
     * action.
     *
     * <p>Action-like alone does NOT trigger retrieval — it only gates the
     * PascalCase and anchored-tech-noun checks, mirroring the question-like
     * gate. "write a poem" stays ASSIST; "write a test for RagService"
     * routes to RETRIEVE.
     *
     * <p>Strips common conversational prefixes ("hey", "ok", etc.) before
     * checking, so "hey, fix the parser" is recognized as action-like.
     */
    static boolean isActionLike(String lower) {
        String stripped = CONVERSATIONAL_PREFIX.matcher(lower).replaceFirst("");
        return stripped.startsWith("write ")     || stripped.startsWith("create ")
            || stripped.startsWith("edit ")      || stripped.startsWith("fix ")
            || stripped.startsWith("add ")       || stripped.startsWith("implement ")
            || stripped.startsWith("refactor ")  || stripped.startsWith("update ")
            || stripped.startsWith("delete ")    || stripped.startsWith("remove ")
            || stripped.startsWith("rename ")    || stripped.startsWith("move ")
            || stripped.startsWith("generate ")  || stripped.startsWith("modify ")
            || stripped.startsWith("rewrite ")   || stripped.startsWith("extract ")
            || stripped.startsWith("optimize ")  || stripped.startsWith("debug ")
            || stripped.startsWith("migrate ")   || stripped.startsWith("convert ")
            || stripped.startsWith("test ")      || stripped.startsWith("run ")
            || stripped.startsWith("build ")     || stripped.startsWith("deploy ")
            || stripped.startsWith("set up ")    || stripped.startsWith("setup ")
            || stripped.startsWith("configure ")
            || stripped.startsWith("scaffold ")  || stripped.startsWith("bootstrap ")
            || stripped.startsWith("wire ")      || stripped.startsWith("hook up ")
            || stripped.startsWith("integrate ")
            || stripped.startsWith("inspect ")
            || stripped.startsWith("review ")    || stripped.startsWith("verify ")
            || stripped.startsWith("scan ")      || stripped.startsWith("analyze ")
            || stripped.startsWith("analyse ")   || stripped.startsWith("examine ")
            || stripped.startsWith("look at ")   || stripped.startsWith("find ")
            || stripped.startsWith("search ")    || stripped.startsWith("explore ")
            || stripped.startsWith("read ")      || stripped.startsWith("change ")
            || stripped.startsWith("install ")   || stripped.startsWith("upgrade ")
            || stripped.startsWith("clean ")     || stripped.startsWith("lint ")
            || stripped.startsWith("format ")    || stripped.startsWith("document ");
    }

    /**
     * Checks whether the input is a conversational follow-up that should
     * inherit retrieval context from the previous turn.
     *
     * <p>Strips common conversational prefixes ("cool", "actually", "right")
     * before checking patterns, so "cool, and the parser?" is recognized
     * as a follow-up.
     *
     * <p>Returns {@code false} for social follow-ups like "thanks" or
     * "what about you?" to prevent casual conversation from accidentally
     * staying in retrieval mode.
     */
    static boolean isFollowUp(String lower) {
        if (SOCIAL_FOLLOW_UP.matcher(lower).find()) return false;
        String stripped = CONVERSATIONAL_PREFIX.matcher(lower).replaceFirst("");
        return FOLLOW_UP.matcher(stripped).find();
    }

    /**
     * Checks whether any PascalCase identifier in the input exists in the
     * indexed workspace. Uses the provided checker to resolve symbols.
     *
     * <p>Iterates over all {@link #CODE_IDENTIFIER} matches and returns
     * {@code true} as soon as any match is confirmed by the checker.
     */
    private static boolean hasWorkspaceSymbol(String trimmed, WorkspaceSymbolChecker checker) {
        Matcher m = CODE_IDENTIFIER.matcher(trimmed);
        while (m.find()) {
            if (checker.existsInWorkspace(m.group())) {
                return true;
            }
        }
        return false;
    }
}
