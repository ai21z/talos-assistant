package dev.talos.cli.modes;

import dev.talos.core.index.WorkspaceSymbolChecker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assistant-first prompt router for auto-mode with conversation context.
 *
 * <p><b>The assistant is the default.</b> Everything is a conversation turn
 * unless there is strong evidence that workspace retrieval is needed.
 *
 * <h3>Routing layers</h3>
 * <ol>
 *   <li><b>COMMAND</b> — structural file operations (open, show, ls, dir)</li>
 *   <li><b>RETRIEVE</b> — workspace framing, file references, PascalCase identifiers
 *       in question/action context, or identifiers confirmed in workspace index</li>
 *   <li><b>Sticky retrieval</b> — non-social follow-ups inherit retrieval context</li>
 *   <li><b>ASSIST</b> — default LLM conversation, no retrieval</li>
 * </ol>
 *
 * <p>False retrieval is worse than missed retrieval — when in doubt, be an assistant.
 */
public final class PromptClassifier {

    private PromptClassifier() {}

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

    /** Matches explicit file/directory commands: ls, dir, list, open, view, show. */
    private static final Pattern DEV_COMMAND = Pattern.compile(
        "(?i)^\\s*(?:" +
            "(?:ls|dir)(?:\\s+|$)|" +
            "list\\s*$|" +
            "list\\s+(?!all\\b|the\\b|every\\b|files?\\b|folders?\\b|directories\\b|items\\b|entries\\b|names\\b|me\\b)(?:\"[^\"]+\"|'[^']+'|`[^`]+`|\\S+)\\s*$|" +
            "(?:open|view)\\s+(?![\"']?(?:me|the|all|every)\\b)\\S|" +
            "show\\s+(?![\"']?(?:me|the|all|every|how|why|what)\\b)\\S" +
        ")"
    );

    /** "show me [the] &lt;file&gt;" — compound command prefix (supports quoted paths). */
    private static final Pattern SHOW_ME_PREFIX = Pattern.compile(
        "(?i)^\\s*show\\s+me\\s+(?:the\\s+)?"
    );

    // ── Layer 2: retrieval signals ──────────────────────────────────────

    /** File references: word.ext patterns and well-known filenames. Unconditional retrieval trigger. */
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
     * PascalCase identifiers (e.g. {@code RagService}). At least two segments.
     * Requires question/action context to trigger retrieval (brand names also use PascalCase).
     */
    private static final Pattern CODE_IDENTIFIER = Pattern.compile(
        "\\b[A-Z][a-z]+(?:[A-Z][a-z0-9]+)+\\b"
    );

    /** Workspace-proximity terms ("here", "workspace", "working on"). Requires question/action context. */
    private static final Pattern WORKSPACE_PROXIMITY = Pattern.compile(
        "(?i)\\bhere\\b|\\bworkspace\\b|\\bworking\\s+on\\b"
    );

    /**
     * "the/this [qualifier] &lt;tech-noun&gt;" pattern. Allows an optional intervening
     * word (e.g. "the Sandbox class"). Requires question/action context.
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
            "stylesheet|styles?|script|markup|element|section|form|" +
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
     * Conversational prefixes stripped before question/follow-up/action detection.
     *
     * <p>Includes casual interjections ("hey", "ok") AND polite request framing
     * ("can you", "could you", "please", "i want you to", etc.) so that
     * "Can you update the file?" normalizes to "update the file?" before
     * intent classification.
     */
    private static final Pattern CONVERSATIONAL_PREFIX = Pattern.compile(
        "(?i)^(?:" +
            // casual interjections
            "(?:hey|hi|hello|ok(?:ay)?|so|well|um+|hmm+|oh|ah|yo|alright|" +
            "sure|right|actually|cool|yeah|yep|yup),?\\s+" +
            "|" +
            // polite request framing (order: longer phrases first to avoid partial matches)
            "(?:i['\u2018\u2019]?d like you to|i want you to|i need you to|" +
            "can you(?: please)?|could you(?: please)?|would you(?: please)?|will you(?: please)?|" +
            "you should|go ahead and|try to|just|please)\\s+" +
        ")"
    );

    // ── Result type ──────────────────────────────────────────────────────

    /** Routing result with trigger label and evaluation trace (used by {@code :route} diagnostic). */
    public record RouteResult(Route route, String trigger, List<String> steps) {
        public RouteResult {
            steps = List.copyOf(steps);   // defensive copy, immutable
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Routes a prompt (stateless — no conversation context). */
    public static Route route(String input) {
        return route(input, null);
    }

    /** Routes with conversation context (sticky retrieval for non-social follow-ups). */
    public static Route route(String input, Route lastRoute) {
        return route(input, lastRoute, null);
    }

    /** Routes with conversation context and optional workspace symbol resolution. */
    public static Route route(String input, Route lastRoute, WorkspaceSymbolChecker checker) {
        return explainRoute(input, lastRoute, checker).route();
    }

    /** Full routing with explanation trace. Single code path for all routing decisions. */
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

        // Layer 1c: action-verb gate — mutation/inspection actions route to
        // ASSIST (tool-calling path) even if they mention files or the workspace.
        // "edit index.html" is a tool action, not a retrieval query.
        // "create settings.json" is a tool action, not a retrieval query.
        //
        // Exception: when the prompt contains a PascalCase code identifier
        // (e.g. "fix RagService"), it is a code-context action
        // that needs retrieval, so we let it fall through.
        boolean isAction = isActionLike(lower);
        boolean isMutation = isAction && isMutationOrInspection(lower);
        if (isMutation) {
            boolean hasCodeTarget = CODE_IDENTIFIER.matcher(trimmed).find();
            if (!hasCodeTarget) {
                steps.add("mutation/inspection intent, no code entity → tool path");
                return new RouteResult(Route.ASSIST, "action intent (tool-calling)", steps);
            }
            steps.add("mutation/inspection but targets code entity — continuing to retrieval");
        } else if (isAction) {
            steps.add("action-like but not mutation/inspection — continuing");
        } else {
            steps.add("not action-like — continuing");
        }

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
        // isAction already computed in Layer 1c above
        boolean hasIntentContext = isQ || isAction;

        if (hasIntentContext && CODE_IDENTIFIER.matcher(trimmed).find()) {
            String intentType = isAction ? "action" : "question";
            steps.add(intentType + " context + PascalCase identifier");
            return new RouteResult(Route.RETRIEVE,
                    "PascalCase identifier in " + intentType, steps);
        }
        if (hasIntentContext && WORKSPACE_PROXIMITY.matcher(lower).find()) {
            String intentType = isAction ? "action" : "question";
            steps.add(intentType + " context + workspace proximity term");
            return new RouteResult(Route.RETRIEVE,
                    "workspace proximity in " + intentType, steps);
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

    /** Checks if input matches "show me [the] &lt;file-reference&gt;" (supports quoted paths). */
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

    /** True if the input looks like a question (strips conversational prefixes first). */
    static boolean isQuestionLike(String lower) {
        String stripped = CONVERSATIONAL_PREFIX.matcher(lower).replaceFirst("");
        return stripped.endsWith("?")
            || stripped.startsWith("how ")    || stripped.startsWith("what ")
            || stripped.startsWith("where ")  || stripped.startsWith("why ")
            || stripped.startsWith("when ")   || stripped.startsWith("who ")
            || stripped.startsWith("which ")  || stripped.startsWith("do ")
            || stripped.startsWith("does ")   || stripped.startsWith("is ")
            || stripped.startsWith("are ")    || stripped.startsWith("can ")
            || stripped.startsWith("should ") || stripped.startsWith("could ")
            || stripped.startsWith("explain ") || stripped.startsWith("describe ")
            || stripped.startsWith("show me ") || stripped.startsWith("tell me about ")
            || stripped.startsWith("tell me ")
            || stripped.startsWith("what's ")  || stripped.startsWith("where's ")
            || stripped.startsWith("how's ")   || stripped.startsWith("who's ");
    }

    /**
     * True if input starts with an imperative action verb ("write", "create", "fix", etc.).
     * Does NOT trigger retrieval alone — only gates the PascalCase/tech-noun checks.
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
            || stripped.startsWith("format ")    || stripped.startsWith("document ")
            || stripped.startsWith("list ")      || stripped.startsWith("ls ")
            || stripped.startsWith("grep ")      || stripped.startsWith("save ")
            || stripped.startsWith("make ")      || stripped.startsWith("put ")
            || stripped.startsWith("improve ")   || stripped.startsWith("overwrite ");
    }

    /**
     * True for unambiguous tool-execution verbs (create, write, delete, edit, update, fix, etc.).
     * These route to ASSIST (tool-calling) even when file/workspace signals are present.
     *
     * <p>Includes both mutation verbs (create, delete, edit, update, fix, change, improve,
     * modify, rewrite, overwrite) and inspection verbs (list, search, grep, scan).
     */
    static boolean isMutationOrInspection(String lower) {
        String stripped = CONVERSATIONAL_PREFIX.matcher(lower).replaceFirst("");
        return stripped.startsWith("create ")    || stripped.startsWith("write ")
            || stripped.startsWith("generate ")  || stripped.startsWith("save ")
            || stripped.startsWith("make ")      || stripped.startsWith("put ")
            || stripped.startsWith("delete ")    || stripped.startsWith("remove ")
            || stripped.startsWith("rename ")    || stripped.startsWith("move ")
            || stripped.startsWith("edit ")      || stripped.startsWith("update ")
            || stripped.startsWith("fix ")       || stripped.startsWith("change ")
            || stripped.startsWith("improve ")   || stripped.startsWith("modify ")
            || stripped.startsWith("rewrite ")   || stripped.startsWith("overwrite ")
            || stripped.startsWith("list ")      || stripped.startsWith("ls ")
            || stripped.startsWith("search ")    || stripped.startsWith("find ")
            || stripped.startsWith("grep ")      || stripped.startsWith("scan ");
    }

    /** True if input is a non-social follow-up (strips conversational prefixes first). */
    static boolean isFollowUp(String lower) {
        if (SOCIAL_FOLLOW_UP.matcher(lower).find()) return false;
        String stripped = CONVERSATIONAL_PREFIX.matcher(lower).replaceFirst("");
        return FOLLOW_UP.matcher(stripped).find();
    }

    /** True if any PascalCase identifier in the input exists in the workspace index. */
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
