package dev.talos.runtime.verification;

import dev.talos.runtime.task.TaskContractResolver;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment.Reason;
import static dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment.limited;
import static dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment.preservedAnswer;

/**
 * A narrow preservation gate for answers consistent with observed selector mismatches.
 * This is not a semantic grader: arithmetic and other model explanation are not verified.
 * Diagnostic shapes outside this bounded subset retain the static, explicitly limited fallback.
 */
public final class ReadOnlyWebDiagnosticAnswer {
    public static final String LIMITED_PREFIX =
            "[Answer incomplete: static diagnostics only; the requested explanation was not safely completed.]";

    private static final Pattern NEGATED_EXECUTION = Pattern.compile(
            "(?i)\\b(?:no|not|never|without)\\s+(?:(?:a|any|the)\\s+)?"
                    + "(?:browser(?:\\s+or\\s+tests?)?|tests?|runtime)\\s*"
                    + "(?:(?:were|was|has\\s+been|have\\s+been)\\s+)?"
                    + "(?:run|executed|verification|tests?|testing|rendered|rendering)?"
                    + "|\\b(?:did\\s+not|didn't|have\\s+not|haven't)\\s+(?:run|execute|perform)"
                    + "\\s+(?:a\\s+|any\\s+)?(?:browser\\s+)?tests?");
    private static final Pattern EXECUTION_CLAIM = Pattern.compile(
            "(?i)\\b(?:browser|tested|verified|rendered|executed|passed|ran|tests?\\s+(?:were\\s+)?run)\\b"
                    + "|\\b(?:I|we)\\s+(?:clicked|pressed|opened|saw|observed|interacted|confirmed)\\b");
    private static final Pattern UNSUPPORTED_ASSERTION = Pattern.compile(
            "(?i)\\b(?:works?\\s+(?:perfectly|correctly)|(?:fully|completely)\\s+(?:working|functional)"
                    + "|no\\s+(?:issues?|problems?|bugs?|errors?|mismatches?)"
                    + "|(?:all|every)\\s+selectors?\\s+(?:exist|match)"
                    + "|syntax\\s+errors?|closing\\s+(?:script\\s+)?tags?"
                    + "|missing\\s+(?:closing\\s+)?(?:tags?|braces?|parentheses))\\b");
    private static final Pattern ID_SELECTOR = Pattern.compile("#([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern CLASS_SELECTOR = Pattern.compile("(?:^|[\"'`]|(?<=\\s))\\.([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern SCRIPT_LIKE_PATH = Pattern.compile(".*\\.(?:js|mjs|cjs|jsx|ts|tsx)");
    private static final Pattern RECOMMENDATION = Pattern.compile(
            "(?i)\\b(?:use|using|fix(?:es|ed|ing)?|repair(?:s|ed|ing)?|chang(?:e[ds]?|ing)|"
                    + "replac(?:e[ds]?|ing|ement)|switch(?:es|ed|ing)?|set(?:s|ting)?|updat(?:e[ds]?|ing)|"
                    + "correct(?:ed|ing|ion)?|recommend(?:s|ed|ing|ation)?|solution|should|"
                    + "propos(?:e[ds]?|ing|al)|revis(?:e[ds]?|ing|ion)|new)\\b");
    private static final Pattern FENCE_LINE = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})([^\\n]*)$");
    private static final Pattern HEADING = Pattern.compile("(?m)^ {0,3}#{1,6}\\s+([^\\n]+)$");
    private static final Pattern LIST_ITEM = Pattern.compile("(?m)^ {0,3}(?:[-+*]|[0-9]{1,6}[.)])\\s+");
    private static final Pattern NEGATIVE_EXAMPLE = Pattern.compile(
            "(?i)^ {0,3}(?:(?:[-+*]|[0-9]{1,6}[.)])\\s+)?(?:wrong|incorrect|broken)\\s*:\\s*(.+)$");
    private static final String NEGATIVE_SELECTOR_PREDICATE =
            "(?:does\\s+not\\s+exist|doesn't\\s+exist|is\\s+(?:missing|absent)|"
                    + "is\\s+not\\s+(?:present|found)|(?:does\\s+not|doesn't)\\s+match\\s+(?:any\\s+)?"
                    + "(?:element|node|target)|matches\\s+no\\s+(?:element|node|target))\\b";
    private static final int MAX_CONTEXT_CHARS = 1_024;
    private static final Pattern INLINE_CODE = Pattern.compile(
            "(?<![\\\\\\x60])(?<ticks>\\x60{1,32})(?!\\x60)(?<body>[^\\x60\\r\\n]{1,512})\\k<ticks>(?!\\x60)");
    private static final Pattern MEMBER_CALL = Pattern.compile(
            "\\.(?<member>[A-Za-z_$][A-Za-z0-9_$]*)\\([^()\\r\\n]{0,256}\\)");
    private static final Pattern SELECTOR_ROLE = Pattern.compile(
            "(?i)\\b(?:css|class(?:es)?|selectors?|select(?:s|ed|ing)?|querySelector(?:All)?|getElementsByClassName)\\b");
    private static final Pattern NEGATED_ROLE = Pattern.compile(
            "(?i)\\b(?:not|never|no|neither|without|isn't|isn’t|doesn't|doesn’t|don't|don’t)\\b");

    private ReadOnlyWebDiagnosticAnswer() {}

    static boolean canPreserve(String answer, Collection<String> readPaths,
                               StaticWebSelectorAnalyzer.Facts facts, List<StaticWebDiagnostic> diagnostics) {
        return assess(answer, readPaths, facts, diagnostics).preserved();
    }

    public static ReadOnlyWebAnswerAssessment assess(String answer, Collection<String> readPaths,
                                                     StaticWebSelectorAnalyzer.Facts facts,
                                                     List<StaticWebDiagnostic> diagnostics) {
        if (answer == null || answer.isBlank() || answer.length() > 65_536 || facts == null
                || diagnostics == null || diagnostics.isEmpty()) return limited(Reason.INVALID_INPUT);
        if (diagnostics.stream().anyMatch(diagnostic -> diagnostic == null
                || !diagnostic.kind().supportsAnswerPreservation())) return limited(Reason.UNSUPPORTED_DIAGNOSTIC);

        String plain = answer.replace("**", "").replace("\r\n", "\n").replace('\r', '\n');
        if (UNSUPPORTED_ASSERTION.matcher(plain).find()) return limited(Reason.UNSUPPORTED_ASSERTION);
        if (EXECUTION_CLAIM.matcher(withoutNegatedCodeExecution(
                NEGATED_EXECUTION.matcher(plain).replaceAll(""), facts)).find()) {
            return limited(Reason.EXECUTION_CLAIM_WITHOUT_EVIDENCE);
        }
        // Only the selector view hides observed member names. Execution and
        // assertion checks above still see all original code and prose.
        plain = selectorRoleText(plain, facts);
        if (contradictsPresentSelector(plain, facts)) return limited(Reason.SELECTOR_CONTRADICTION);

        Set<String> read = new LinkedHashSet<>();
        if (readPaths != null) readPaths.forEach(path -> read.add(normalize(path)));
        for (String cited : TaskContractResolver.extractExpectedTargets(answer)) {
            if (!read.contains(normalize(cited))) return limited(Reason.UNREAD_PATH);
        }
        boolean uniqueReadScript = read.stream().filter(path -> SCRIPT_LIKE_PATH.matcher(path).matches()).count() == 1
                && read.contains(normalize(facts.jsFile()));

        Set<String> missingIds = new LinkedHashSet<>(facts.jsIds());
        missingIds.removeAll(facts.htmlIds());
        Set<String> missingClasses = new LinkedHashSet<>(facts.jsClasses());
        missingClasses.removeAll(facts.htmlClasses());
        if (missingIds.isEmpty() && missingClasses.isEmpty()) return limited(Reason.NO_MISSING_SELECTOR);
        if (missingIds.size() + missingClasses.size() > 32) return limited(Reason.SELECTOR_LIMIT_EXCEEDED);
        for (String id : missingIds) {
            if (!acknowledgesMissingSelector(plain, "#" + id, facts, uniqueReadScript)) {
                return limited(Reason.SELECTOR_RELATION_NOT_ESTABLISHED);
            }
        }
        for (String name : missingClasses) {
            if (!acknowledgesMissingSelector(plain, "." + name, facts, uniqueReadScript)) {
                return limited(Reason.SELECTOR_RELATION_NOT_ESTABLISHED);
            }
        }

        // Any proposed ID selector must be present in the inspected HTML or be
        // the observed broken selector being explained. Do not infer a replacement.
        var selector = ID_SELECTOR.matcher(answer);
        while (selector.find()) {
            if (!facts.htmlIds().contains(selector.group(1)) && !missingIds.contains(selector.group(1))) {
                return limited(Reason.UNOBSERVED_ID_SELECTOR);
            }
        }
        var classSelector = CLASS_SELECTOR.matcher(selectorRoleText(answer, facts));
        while (classSelector.find()) {
            if (!facts.htmlClasses().contains(classSelector.group(1))
                    && !missingClasses.contains(classSelector.group(1))) {
                return limited(Reason.UNOBSERVED_CLASS_SELECTOR);
            }
        }
        return preservedAnswer();
    }

    private static boolean acknowledgesMissingSelector(String answer, String selector,
                                                       StaticWebSelectorAnalyzer.Facts facts,
                                                       boolean uniqueReadScript) {
        if (hasNegatedObservedQuerySource(answer, selector, facts, uniqueReadScript)) return false;
        String literal = Pattern.quote(selector) + "(?![A-Za-z0-9_-])";
        String reference = selectorReference(selector);
        // A truthful source citation/absence elsewhere cannot license a direct
        // contradictory assertion that this missing selector is present.
        if (Pattern.compile("(?i)" + reference + "[\\s`'\",()]*(?:which\\s+)?"
                + "(?:exists?|does\\s+exist|is\\s+(?:present|found|available|usable|valid|not\\s+(?:missing|absent))|"
                + "matches\\s+(?:an?\\s+|the\\s+)(?:element|node|target))\\b")
                .matcher(answer).find() || positiveHtmlPresenceClaim(answer, reference, facts)) return false;
        // Every occurrence must belong to a recognized absence statement or a
        // current-source query citation. A separate truthful sentence cannot
        // license a conflicting assertion/recommendation elsewhere in the answer.
        List<Pattern> allowed = List.of(
                Pattern.compile("(?i)" + reference + "[\\s`'\",()]*(?:which\\s+)?"
                        + NEGATIVE_SELECTOR_PREDICATE),
                Pattern.compile("(?i)\\bno\\s+element[^.!?\\n]{0,80}\\b(?:has|with)\\s+(?:the\\s+)?[`'\"]?" + reference),
                Pattern.compile("(?i)\\b(?:wrong|incorrect|broken|missing)\\s+selector[\\s(`'\"]*" + literal));
        var cited = Pattern.compile(reference).matcher(answer);
        boolean sawReference = false;
        while (cited.find()) {
            sawReference = true;
            if (coveredByPreposedAbsence(answer, reference, cited.start(), cited.end(), true)) return false;
            // An explicit rejected-example field governs only its immediate
            // selector/query payload, never an instruction such as "Wrong: use
            // #x to fix it" or the contents of a neighboring Correct item.
            if (negativeExamplePayload(answer, selector, cited.start())) {
                if (recommendationHeading(answer, cited.start())
                        || recommendationListCaption(answer, cited.start())) return false;
                if (Pattern.compile(queryExpression(selector)).matcher(facts.js()).find()) continue;
            }
            // A source expression can also be proposed as a repair. Decide that
            // role before accepting its exact match with currently broken code.
            boolean replacementSource = replacementSourceOperand(answer, selector, facts, cited.start(), cited.end());
            SourceUse sourceUse = sourceUse(answer, selector, facts, cited.start(), cited.end());
            if (sourceUse != null && !sourceUse.observed()) return false;
            if (recommendationScope(answer, cited.start(), sourceUse) && !replacementSource) return false;
            if (sourceUse != null) continue;
            if (replacementSource) continue;
            boolean covered = coveredByPreposedAbsence(answer, reference, cited.start(), cited.end(), false);
            for (Pattern pattern : allowed) {
                if (coveredBy(pattern, answer, cited.start(), cited.end())) {
                    covered = true;
                    break;
                }
            }
            if (!covered && !negativeSameSelectorClause(answer, selector, reference, cited.start(), cited.end())
                    && !observedTargetAlias(answer, selector, facts, cited.start(), cited.end())
                    && !negativeSelectorContrast(answer, selector, facts, cited.start(), cited.end())
                    && !currentSourceSelectorCitation(answer, literal, facts, cited.start(), cited.end())
                    && !currentSourceListenerCitation(answer, selector, facts, cited.start(), cited.end())
                    && !currentSourceEventComparison(answer, selector, facts, cited.start(), cited.end())
                    && !currentSourceQueryCitation(answer, selector, facts, uniqueReadScript,
                            cited.start(), cited.end())) return false;
        }
        return sawReference;
    }

    private static String selectorRoleText(String answer, StaticWebSelectorAnalyzer.Facts facts) {
        StringBuilder view = new StringBuilder(answer);
        var code = INLINE_CODE.matcher(answer);
        int regions = 0;
        while (code.find()) {
            if (++regions > 256) return answer; // Keep the original conservative checks beyond this budget.
            String body = code.group("body");
            var call = MEMBER_CALL.matcher(body);
            if (!call.matches() || !facts.js().contains(body)
                    || containingFence(answer, code.start(), code.end()) != null) continue;
            int from = clauseStart(answer, code.start());
            int to = clauseEnd(answer, code.end());
            if (to - from > MAX_CONTEXT_CHARS) continue;
            String outside = (answer.substring(from, code.start()) + answer.substring(code.end(), to)).replace("**", "");
            if (SELECTOR_ROLE.matcher(outside).find()
                    || SELECTOR_ROLE.matcher(headingBefore(answer, code.start()).replace("**", "")).find()
                    || isListItem(answer, code.start())
                    && SELECTOR_ROLE.matcher(listCaption(answer, code.start()).replace("**", "")).find()) continue;
            // Mask only this member identifier, not arguments, neighboring
            // code or a whole sentence. Real selector strings remain visible.
            int memberStart = code.start("body") + call.start("member");
            int memberEnd = code.start("body") + call.end("member");
            for (int at = memberStart; at < memberEnd; at++) view.setCharAt(at, ' ');
        }
        return view.toString();
    }

    private static String headingBefore(String answer, int position) {
        var headings = HEADING.matcher(answer);
        String heading = "";
        while (headings.find() && headings.start() < position) heading = headings.group(1);
        return heading;
    }

    private static boolean observedTargetAlias(String answer, String selector,
                                              StaticWebSelectorAnalyzer.Facts facts, int start, int end) {
        if (containingFence(answer, start, end) != null) return false;
        int from = Math.max(clauseStart(answer, start), answer.lastIndexOf('\n', start - 1) + 1);
        int to = Math.min(clauseEnd(answer, end), lineEnd(answer, end));
        if (to - from > MAX_CONTEXT_CHARS) return false;
        String prefix = answer.substring(from, start);
        if (isTableRow(answer, start)) {
            if (!tableSourceCitation(answer, start, facts)) return false;
            prefix = tableCell(answer, start).prefix();
        } else if (!namesFile(prefix, facts.jsFile()) || !sourceRolePrefix(prefix, facts)) return false;
        // An attribute alias is tied to the adjacent parenthesized expression,
        // not arbitrary natural-language verbs or a source named in another row.
        String attribute = selector.startsWith("#") ? "id" : "class";
        var alias = Pattern.compile("(?i:" + attribute + ")\\s*=\\s*[\\x60'\"]*"
                + Pattern.quote(selector.substring(1)) + "[\\x60'\"]*\\s*"
                + "\\([\\x60]*(" + queryExpression(selector) + ")[\\x60]*\\)")
                .matcher(answer.substring(start, to));
        if (!alias.lookingAt() || end > start + alias.end()
                || !Pattern.compile(queryExpression(selector)).matcher(facts.js()).find()) return false;
        // Do not borrow attribution through a different file or HTML subject.
        return sourceRolePrefix(prefix, facts);
    }

    private static boolean negativeSelectorContrast(String answer, String selector,
                                                    StaticWebSelectorAnalyzer.Facts facts, int start, int end) {
        int from = Math.max(clauseStart(answer, start), answer.lastIndexOf('\n', start - 1) + 1);
        int to = lineEnd(answer, end);
        if (to - from > MAX_CONTEXT_CHARS || containingFence(answer, start, end) != null
                || !Pattern.compile(queryExpression(selector)).matcher(facts.js()).find()) return false;
        String clause = answer.substring(from, to);
        if (NEGATED_ROLE.matcher(clause).find() || RECOMMENDATION.matcher(clause).find()) return false;
        // The role comes from a closed negative-caption comparison, not the
        // particular descriptive noun between "wrong" and the two operands.
        var contrast = Pattern.compile("\\A\\s*(?:(?:[-+*]|[0-9]{1,6}[.)])\\s+)?"
                + "(?:[A-Za-z][A-Za-z -]{0,64}:\\s*)?"
                + "(?i:wrong|incorrect|broken|missing)\\s+[A-Za-z][A-Za-z -]{0,64}\\s*\\("
                + "(?<oldQuote>[\\x60'\"]?)(?<old>" + Pattern.quote(selector) + ")\\k<oldQuote>"
                + "\\s+(?i:vs\\.?|versus|instead\\s+of)\\s+(?<newQuote>[\\x60'\"]?)"
                + "(?<target>[#.][A-Za-z_][A-Za-z0-9_-]*)\\k<newQuote>\\)\\s*[.!?]?\\z").matcher(clause);
        if (!contrast.matches() || from + contrast.start("old") != start
                || from + contrast.end("old") != end) return false;
        String target = contrast.group("target");
        return target.startsWith("#") ? facts.htmlIds().contains(target.substring(1))
                : facts.htmlClasses().contains(target.substring(1));
    }

    private static int lineEnd(String answer, int from) {
        int end = answer.indexOf('\n', from);
        return end < 0 ? answer.length() : end;
    }

    private static boolean hasNegatedObservedQuerySource(String answer, String selector,
                                                        StaticWebSelectorAnalyzer.Facts facts,
                                                        boolean uniqueReadScript) {
        Pattern query = Pattern.compile(queryExpression(selector));
        if (!query.matcher(facts.js()).find()) return false;
        String source = "(?:" + fileReference(facts.jsFile())
                + (uniqueReadScript ? "|\\bthe\\s+script\\b" : "") + ")";
        Pattern negatedSource = Pattern.compile("(?i)" + source + "[`'\"]?\\s+"
                + "(?:(?:do|does)\\s+not|not|never|no\\s+longer|doesn['’]t|don['’]t)\\s+"
                + "(?:uses?|quer(?:y|ies)|selects?|references?)\\b");
        var quoted = query.matcher(answer);
        while (quoted.find()) {
            Fence fence = containingFence(answer, quoted.start(), quoted.end());
            String caption;
            boolean inline = false;
            if (fence != null) {
                String language = fence.language();
                if (fence.contentEnd() < quoted.end()
                        || !language.isEmpty() && !language.equals("js") && !language.equals("javascript")) continue;
                caption = captionBefore(answer, fence.opening());
            } else {
                if (isListItem(answer, quoted.start())
                        && negatedSource.matcher(listCaption(answer, quoted.start())).find()) return true;
                int from = clauseStart(answer, quoted.start());
                if (quoted.start() - from > MAX_CONTEXT_CHARS) continue;
                caption = answer.substring(from, quoted.start());
                inline = true;
            }
            // A caption governs its query block. An inline predicate must be
            // directly attached to this exact expression, not another target
            // earlier in the clause. Both precede returns-null/absence roles.
            var contradiction = negatedSource.matcher(caption);
            while (contradiction.find()) {
                if (!inline || Pattern.compile("[\\s`'\":]*")
                        .matcher(caption.substring(contradiction.end())).matches()) return true;
            }
        }
        return false;
    }

    private static boolean currentSourceQueryCitation(String answer, String selector,
                                                     StaticWebSelectorAnalyzer.Facts facts,
                                                     boolean uniqueReadScript, int start, int end) {
        String call = queryExpression(selector);
        if (!Pattern.compile(call).matcher(facts.js()).find()) return false;
        var quoted = Pattern.compile(call).matcher(answer);
        while (quoted.find()) {
            boolean inQuery = quoted.start() <= start && quoted.end() >= end;
            Fence fence = containingFence(answer, quoted.start(), quoted.end());
            if (fence != null) {
                String language = fence.language();
                if (fence.contentEnd() < quoted.end()
                        || !language.isEmpty() && !language.equals("js") && !language.equals("javascript")) {
                    if (inQuery) return false;
                    continue;
                }
                // Attribution comes from the adjacent Markdown caption, not a
                // particular English description of how a listener is attached.
                // A selector named in that caption belongs to the same exact
                // observed expression, not to another source block elsewhere.
                String caption = captionBefore(answer, fence.opening());
                if (sourceCaption(caption, facts, uniqueReadScript)) {
                    if (inQuery) return true;
                    int captionStart = answer.lastIndexOf(caption, fence.opening());
                    // The caption may name the query's terminal selector, but
                    // that does not license another predicate after it (for
                    // example, "#x is not missing").
                    if (captionStart >= 0 && start >= captionStart && end <= captionStart + caption.length()
                            && Pattern.compile("[\\s`'\"():]*")
                            .matcher(answer.substring(end, captionStart + caption.length())).matches()) return true;
                }
                if (inQuery) return false;
                continue;
            }
            if (!inQuery) continue;
            if (isTableRow(answer, start)) return tableSourceCitation(answer, start, facts);
            int from = clauseStart(answer, start);
            if (start - from <= MAX_CONTEXT_CHARS
                    && namesFile(answer.substring(from, start), facts.jsFile())) return true;
            if (isListItem(answer, start)
                    && sourceCaption(listCaption(answer, start), facts, uniqueReadScript)) return true;
        }
        // Retain the existing unambiguous inline forms (including "the script"
        // when the inspected source expression supplies the file binding).
        String source = uniqueReadScript ? "(?:\\bthe\\s+script|" + fileReference(facts.jsFile()) + ")"
                : fileReference(facts.jsFile());
        for (Pattern pattern : List.of(
                Pattern.compile("(?i)" + call + "[`'\"]?\\s+returns\\s+[`'\"]?null\\b"),
                Pattern.compile("(?i)" + source + "[`'\"]?\\s+uses\\s+" + call))) {
            if (coveredBy(pattern, answer, start, end)) return true;
        }
        return false;
    }

    private static boolean sourceCaption(String caption, StaticWebSelectorAnalyzer.Facts facts,
                                         boolean uniqueReadScript) {
        if (caption.isBlank() || RECOMMENDATION.matcher(caption).find()) return false;
        return namesFile(caption, facts.jsFile()) || uniqueReadScript
                && Pattern.compile("(?i)\\bthe\\s+script\\b").matcher(caption).find();
    }

    private static boolean coveredByPreposedAbsence(String answer, String reference,
                                                    int start, int end, boolean negated) {
        var claims = Pattern.compile("(?i)\\b(?:non[- ]?existent|missing|absent)\\s+[`'\"]?"
                + reference).matcher(answer);
        while (claims.find()) {
            if (claims.start() > start || claims.end() < end) continue;
            String prefix = answer.substring(Math.max(0, claims.start() - 80), claims.start());
            boolean directlyNegated = Pattern.compile("(?i)\\b(?:not|never|isn't|aren't|no\\s+longer)\\s+"
                    + "(?:(?:a|an|the)\\s+)?$").matcher(prefix).find();
            if (directlyNegated == negated) return true;
        }
        return false;
    }

    private static String withoutNegatedCodeExecution(String answer, StaticWebSelectorAnalyzer.Facts facts) {
        // A statement about an observed JS call not executing is not a claim
        // that Talos ran a browser/test. Actor and browser claims remain visible
        // to the independent execution-claim guard, even in the same sentence.
        if (!Pattern.compile("\\baddEventListener\\s*\\(").matcher(facts.js()).find()) return answer;
        return Pattern.compile("(?i)`?addEventListener`?\\s+(?:call|invocation)\\s+"
                + "(?:(?:is|was|will\\s+be|would\\s+be)\\s+)?(?:never|not)\\s+(?:executed|run|invoked)\\b")
                .matcher(answer).replaceAll("");
    }

    private static String selectorReference(String selector) {
        String attribute = selector.startsWith("#") ? "id" : "class";
        return "(?:" + Pattern.quote(selector) + "(?![A-Za-z0-9_-])|\\b(?i:" + attribute
                + ")\\s*(?:=|is\\s+|of\\s+)?[\\s`'\"]*" + Pattern.quote(selector.substring(1))
                + "(?![A-Za-z0-9_-]))";
    }

    private static String queryExpression(String selector) {
        return "document\\.querySelector\\(\\s*['\"]" + Pattern.quote(selector) + "['\"]\\s*\\)";
    }

    private static boolean negativeExamplePayload(String answer, String selector, int position) {
        if (containingFence(answer, position, position + 1) != null) return false;
        int from = answer.lastIndexOf('\n', Math.max(0, position - 1)) + 1;
        int to = answer.indexOf('\n', position);
        if (to < 0) to = answer.length();
        if (to - from > MAX_CONTEXT_CHARS) return false;
        var label = NEGATIVE_EXAMPLE.matcher(answer.substring(from, to));
        if (!label.matches()) return false;
        String payload = label.group(1).strip();
        if (payload.startsWith("`") && payload.endsWith("`") && payload.length() > 2) {
            payload = payload.substring(1, payload.length() - 1);
        }
        return payload.equals(selector) || Pattern.compile(queryExpression(selector) + ";?").matcher(payload).matches();
    }

    private static boolean positiveHtmlPresenceClaim(String answer, String reference,
                                                     StaticWebSelectorAnalyzer.Facts facts) {
        // An assertion about current HTML is not a source citation merely
        // because the same clause also names JavaScript containing the query.
        String html = "(?:\\b(?:HTML|markup|page|DOM)\\b|" + fileReference(facts.htmlFile()) + ")";
        // Bind the asserted target directly. A later missing-selector reference
        // cannot be borrowed across an intervening, truthful HTML attribute.
        var claims = Pattern.compile("(?i)" + html + "[`'\"]?\\s+(?:(?:currently|now)\\s+)?"
                + "(?:has|contains|defines|includes)\\s+(?:(?:a|an|the)\\s+)?"
                + "(?:(?:button|element|node|target)\\s+(?:with\\s+(?:the\\s+)?)?)?"
                + "(?:selector\\s+)?[`'\"]?" + reference).matcher(answer);
        while (claims.find()) {
            String prefix = answer.substring(Math.max(0, claims.start() - 80), claims.start());
            // In "No element in the page has id x", page is the location of
            // a directly negated subject, not a positive HTML-presence claim.
            if (!Pattern.compile("(?i)\\bno\\s+(?:element|node|target|button)\\s+(?:in|on)\\s+(?:the\\s+)?$")
                    .matcher(prefix).find()) return true;
        }
        return false;
    }

    private static boolean currentSourceSelectorCitation(String answer, String literal,
                                                         StaticWebSelectorAnalyzer.Facts facts, int start, int end) {
        int from = clauseStart(answer, start);
        if (start - from > MAX_CONTEXT_CHARS || containingFence(answer, start, end) != null) return false;
        String clause = answer.substring(from, clauseEnd(answer, end));
        var source = Pattern.compile("(?i)" + fileReference(facts.jsFile())
                + "([`'\"]?[^;.!?\\n]{0,80})\\b(?:uses|queries|selects|references)\\s+[`'\"]?"
                + literal).matcher(clause);
        while (source.find()) {
            if (from + source.start() <= start && from + source.end() >= end
                    && !Pattern.compile("(?i)\\b(?:not|never|no\\s+longer)\\b").matcher(source.group(1)).find()) return true;
        }
        return false;
    }

    private static boolean currentSourceListenerCitation(String answer, String selector,
                                                         StaticWebSelectorAnalyzer.Facts facts, int start, int end) {
        int from = clauseStart(answer, start);
        int to = clauseEnd(answer, end);
        if (to - from > MAX_CONTEXT_CHARS || containingFence(answer, start, end) != null
                || !observedClickListener(selector, facts)) return false;
        // A listener description is a current-source role only when the exact
        // inspected query owns the click listener. A different element's listener
        // cannot supply that fact. Optional line prose is not certified here.
        var citation = Pattern.compile("\\A\\s*(?<fileQuote>[`'\"]?)" + Pattern.quote(facts.jsFile())
                + "\\k<fileQuote>[ \\t]+(?i:listens[ \\t]+for[ \\t]+(?:a[ \\t]+click|clicks)[ \\t]+on)[ \\t]+"
                + "(?<selectorQuote>[`'\"]?)(?<selector>" + Pattern.quote(selector) + ")\\k<selectorQuote>"
                + "(?:[ \\t]+\\((?i:line)[ \\t]+[0-9]{1,6}[ \\t]+(?i:of)[ \\t]+"
                + "(?<lineFileQuote>[`'\"]?)" + Pattern.quote(facts.jsFile()) + "\\k<lineFileQuote>\\))?[ \\t]*\\z")
                .matcher(answer.substring(from, to));
        return citation.matches() && from + citation.start("selector") == start
                && from + citation.end("selector") == end;
    }

    private static boolean currentSourceEventComparison(String answer, String selector,
                                                         StaticWebSelectorAnalyzer.Facts facts, int start, int end) {
        int from = clauseStart(answer, start);
        int to = clauseEnd(answer, end);
        if (to - from > MAX_CONTEXT_CHARS || containingFence(answer, start, end) != null
                || !observedClickListener(selector, facts)) return false;
        // The closed source-bound comparison marks only its first operand as
        // wrong. Reject negation, reversed direction, another file and extra
        // clauses; the destination still has to exist with exact selector case.
        var comparison = Pattern.compile("\\A\\s*(?:(?:[-+*]|[0-9]{1,6}[.)])[ \\t]+)?"
                + "(?<fileQuote>[`'\"]?)" + Pattern.quote(facts.jsFile()) + "\\k<fileQuote>[ \\t]*:[ \\t]*"
                + "(?i:(?:intended[ \\t]+[A-Za-z][A-Za-z -]{0,64}[ \\t]+logic,[ \\t]+but[ \\t]+has[ \\t]+a[ \\t]+)?"
                + "(?:wrong|incorrect|broken)[ \\t]+event[ \\t]+selector)[ \\t]*\\("
                + "(?<oldQuote>[`'\"]?)(?<old>" + Pattern.quote(selector) + ")\\k<oldQuote>"
                + "[ \\t]+(?i:instead[ \\t]+of)[ \\t]+(?<targetQuote>[`'\"]?)"
                + "(?<target>[#.][A-Za-z_][A-Za-z0-9_-]*)\\k<targetQuote>\\)[ \\t]*\\z")
                .matcher(answer.substring(from, to));
        if (!comparison.matches() || from + comparison.start("old") != start
                || from + comparison.end("old") != end) return false;
        String target = comparison.group("target");
        return target.startsWith("#") ? facts.htmlIds().contains(target.substring(1))
                : facts.htmlClasses().contains(target.substring(1));
    }

    private static boolean observedClickListener(String selector, StaticWebSelectorAnalyzer.Facts facts) {
        return Pattern.compile(queryExpression(selector)
                + "\\s*\\.\\s*addEventListener\\s*\\(\\s*(['\"])click\\1\\s*,").matcher(facts.js()).find();
    }

    private static boolean contradictsPresentSelector(String answer, StaticWebSelectorAnalyzer.Facts facts) {
        Set<String> cited = new LinkedHashSet<>();
        var ids = ID_SELECTOR.matcher(answer);
        while (ids.find()) if (facts.htmlIds().contains(ids.group(1))) cited.add("#" + ids.group(1));
        var classes = CLASS_SELECTOR.matcher(answer);
        while (classes.find()) if (facts.htmlClasses().contains(classes.group(1))) cited.add("." + classes.group(1));
        var attributes = Pattern.compile("\\b(?i:(id|class))\\s*(?:=|is\\s+|of\\s+)?"
                + "[\\s`'\"]*([A-Za-z_][A-Za-z0-9_-]*)").matcher(answer);
        while (attributes.find()) {
            boolean id = attributes.group(1).equalsIgnoreCase("id");
            if ((id ? facts.htmlIds() : facts.htmlClasses()).contains(attributes.group(2))) {
                cited.add((id ? "#" : ".") + attributes.group(2));
            }
        }
        if (cited.size() > 32) return true; // Outside the bounded selector-claim surface.
        for (String selector : cited) {
            String reference = selectorReference(selector);
            var references = Pattern.compile(reference).matcher(answer);
            while (references.find()) {
                if (negativeExamplePayload(answer, selector, references.start())
                        || coveredByPreposedAbsence(answer, reference, references.start(), references.end(), false)) return true;
            }
            // Match only a directly attached absence predicate, not an unrelated
            // negative statement somewhere after a quoted HTML/source literal.
            if (Pattern.compile("(?i)" + reference + "[\\s`'\",()]*(?:which\\s+)?"
                    + NEGATIVE_SELECTOR_PREDICATE).matcher(answer).find()
                    || Pattern.compile("(?i)\\bno\\s+element[^.!?\\n]{0,80}\\b(?:has|with)\\s+"
                    + "(?:the\\s+)?[`'\"]?" + reference).matcher(answer).find()) return true;
        }
        return false;
    }

    private static boolean negativeSameSelectorClause(String answer, String selector, String reference,
                                                       int start, int end) {
        int from = clauseStart(answer, start);
        int to = clauseEnd(answer, end);
        if (to - from > MAX_CONTEXT_CHARS) return false;
        String clause = answer.substring(from, to);
        if (clause.contains("```") || clause.contains("~~~") || RECOMMENDATION.matcher(clause).find()) return false;
        // Co-reference is local: "selector #x ... id x, which does not exist".
        // A different selector or a positive HTML-presence claim cannot borrow
        // that absence, nor can a following sentence or semicolon clause.
        var ids = ID_SELECTOR.matcher(clause);
        while (ids.find()) if (!("#" + ids.group(1)).equals(selector)) return false;
        var classes = CLASS_SELECTOR.matcher(clause);
        while (classes.find()) if (!("." + classes.group(1)).equals(selector)) return false;
        if (Pattern.compile("(?i)\\b(?:HTML|markup|page|DOM)\\b[^.;!?\\n]{0,160}"
                + "\\b(?:has|contains|defines|includes)\\b").matcher(clause).find()) return false;
        return Pattern.compile("(?i)" + reference + "[\\s`'\",()]*(?:which\\s+)?"
                + NEGATIVE_SELECTOR_PREDICATE).matcher(clause).find();
    }

    /** A local source/predicate/operand binding, never a permission or verified execution claim. */
    private record SourceUse(boolean observed, int predicateStart, int predicateEnd) {}

    private static SourceUse sourceUse(String answer, String selector,
                                       StaticWebSelectorAnalyzer.Facts facts, int start, int end) {
        if (containingFence(answer, start, end) != null || isTableRow(answer, start)) return null;
        int from = Math.max(clauseStart(answer, start), answer.lastIndexOf('\n', start - 1) + 1);
        int to = Math.min(clauseEnd(answer, end), lineEnd(answer, end));
        if (to - from > MAX_CONTEXT_CHARS) return null;
        String clause = answer.substring(from, to);
        // Parse a bounded present-use relation, including unsupported aspects so
        // that a negated/modal source claim cannot fall through as mere absence.
        // Names and selector values come from the clause and inspected facts,
        // not from a model-specific phrase or a particular fixture filename.
        Set<String> sources = new LinkedHashSet<>(TaskContractResolver.extractExpectedTargets(clause));
        // An already-inspected source does not depend on the user-request
        // resolver's narrower extension vocabulary (for example .mjs).
        sources.add(facts.jsFile());
        for (String file : sources) {
            var use = Pattern.compile("(?<![\\x60'\"A-Za-z0-9_./-])(?<fileQuote>[`'\"]?)"
                    + Pattern.quote(file) + "\\k<fileQuote>[ \\t]+"
                    + "(?<aspect>(?i:(?:(?:currently|now|is|not|never|no|longer|does|do|doesn't|doesn’t|"
                    + "isn't|isn’t|should|must|may|might|would|could|can|will|be|being)[ \\t]+){0,5}))"
                    + "(?<verb>(?i:uses?|using|queries|querying|references?|referencing|selects?|selecting|targets?|targeting))"
                    + "[ \\t]+(?i:(?:the[ \\t]+)?(?:(?:wrong|incorrect|broken|missing)[ \\t]+)?selector[ \\t]+)?"
                    + "(?<oldQuote>[`'\"]?)(?<old>[#.][A-Za-z_][A-Za-z0-9_-]*)\\k<oldQuote>"
                    + "(?:[ \\t]+(?i:instead[ \\t]+of|rather[ \\t]+than)[ \\t]+(?<newQuote>[`'\"]?)"
                    + "(?<target>[#.][A-Za-z_][A-Za-z0-9_-]*)\\k<newQuote>)?[ \\t]*\\z").matcher(clause);
            if (!use.find() || start < from + use.start("old") || end > from + use.end()) continue;
            boolean oldOperand = start == from + use.start("old") && end == from + use.end("old");
            String aspect = use.group("aspect").strip().toLowerCase(Locale.ROOT).replaceAll("[ \\t]+", " ");
            boolean progressive = use.group("verb").toLowerCase(Locale.ROOT).endsWith("ing");
            boolean present = progressive ? aspect.matches("(?:is(?: currently| now)?|(?:currently|now) is)")
                    : use.group("verb").toLowerCase(Locale.ROOT).matches("uses|queries|references|selects|targets")
                    && aspect.matches("(?:currently|now)?");
            String prefix = clause.substring(0, use.start("verb"));
            boolean asserted = !Pattern.compile("(?i)\\b(?:if|unless|when|once|after|before|not|never|"
                    + "should|must|may|might|would|could|can|will|try|consider|imagine|suppose|"
                    + "ensure|make|let|hypothetical|hypothetically)\\b").matcher(prefix).find();
            for (String owner : TaskContractResolver.extractExpectedTargets(prefix)) {
                if (!owner.equals(facts.jsFile())) asserted = false;
            }
            String target = use.group("target");
            boolean targetPresent = target == null || (target.startsWith("#")
                    ? facts.htmlIds().contains(target.substring(1)) : facts.htmlClasses().contains(target.substring(1)));
            boolean sourceObserved = selector.startsWith("#") ? facts.jsIds().contains(selector.substring(1))
                    : facts.jsClasses().contains(selector.substring(1));
            boolean observed = oldOperand && file.equals(facts.jsFile()) && present && asserted
                    && sourceObserved && targetPresent && !recommendationAncestorHeading(answer, start);
            return new SourceUse(observed, from + use.start("verb"), from + use.end("verb"));
        }
        return null;
    }

    private static boolean recommendationAncestorHeading(String answer, int position) {
        boolean[] proposed = new boolean[7];
        var headings = HEADING.matcher(answer);
        while (headings.find() && headings.start() < position) {
            if (containingFence(answer, headings.start(), headings.end()) != null) continue;
            String heading = headings.group().stripLeading();
            int depth = 0;
            while (depth < heading.length() && heading.charAt(depth) == '#') depth++;
            proposed[depth] = RECOMMENDATION.matcher(headings.group(1)).find();
            for (int child = depth + 1; child < proposed.length; child++) proposed[child] = false;
        }
        for (boolean scope : proposed) if (scope) return true;
        return false;
    }

    private static boolean recommendationScope(String answer, int position, SourceUse sourceUse) {
        if (isTableRow(answer, position)) {
            TableCell cell = tableCell(answer, position);
            if (cell == null || RECOMMENDATION.matcher(cell.header()).find()
                    || RECOMMENDATION.matcher(cell.caption()).find()) return true;
        }
        int from = clauseStart(answer, position);
        if (position - from > MAX_CONTEXT_CHARS) return true;
        var recommendation = RECOMMENDATION.matcher(answer).region(from, position);
        while (recommendation.find()) {
            // Discount only the parsed descriptive verb. Other action words
            // and enclosing proposal roles still govern this occurrence.
            if (sourceUse == null || recommendation.start() != sourceUse.predicateStart()
                    || recommendation.end() != sourceUse.predicateEnd()) return true;
        }
        if (isListItem(answer, position) && recommendationListCaption(answer, position)) return true;
        Fence fence = containingFence(answer, position, position + 1);
        if (fence != null && RECOMMENDATION.matcher(captionBefore(answer, fence.opening())).find()) return true;
        return recommendationHeading(answer, position);
    }

    private static boolean recommendationHeading(String answer, int position) {
        var headings = HEADING.matcher(answer);
        String heading = "";
        while (headings.find() && headings.start() < position) heading = headings.group(1);
        return RECOMMENDATION.matcher(heading).find();
    }

    private record TableCell(String owner, String header, String caption, String prefix) {}

    private static boolean isTableRow(String answer, int position) {
        int from = answer.lastIndexOf('\n', position - 1) + 1;
        return answer.substring(from, lineEnd(answer, position)).stripLeading().startsWith("|")
                && containingFence(answer, position, position + 1) == null;
    }

    private static String[] tableCells(String line) {
        if (line.length() > MAX_CONTEXT_CHARS || line.contains("\\|")) return null;
        String[] cells = line.strip().split("\\|", -1);
        if (cells.length < 4 || cells.length > 10
                || !cells[0].isEmpty() || !cells[cells.length - 1].isEmpty()) return null;
        return cells;
    }

    private static TableCell tableCell(String answer, int position) {
        int from = answer.lastIndexOf('\n', position - 1) + 1;
        String row = answer.substring(from, lineEnd(answer, position));
        String[] cells = tableCells(row);
        if (cells == null) return null;
        int bar = row.lastIndexOf('|', position - from);
        int column = 0;
        for (int at = 0; at < bar; at++) if (row.charAt(at) == '|') column++;
        if (column < 1 || column >= cells.length - 2) return null;
        // A row owner is not a nearby filename search: it is the complete
        // first cell, paired with this column's header and separator.
        int previous = from;
        while (previous > 0 && from - previous <= MAX_CONTEXT_CHARS) {
            int lineStart = answer.lastIndexOf('\n', previous - 2) + 1;
            String[] preceding = tableCells(answer.substring(lineStart, previous - 1));
            if (preceding == null || preceding.length != cells.length) return null;
            boolean separator = true;
            for (int i = 1; i < preceding.length - 1; i++) {
                if (!preceding[i].strip().matches(":?-{3,}:?")) separator = false;
            }
            if (separator) {
                if (lineStart == 0) return null;
                int headerStart = answer.lastIndexOf('\n', lineStart - 2) + 1;
                if (from - headerStart > MAX_CONTEXT_CHARS) return null;
                String[] headers = tableCells(answer.substring(headerStart, lineStart - 1));
                if (headers == null || headers.length != cells.length) return null;
                return new TableCell(cells[1].strip(), headers[column + 1].strip(),
                        captionBefore(answer, headerStart), row.substring(bar + 1, position - from));
            }
            previous = lineStart;
        }
        return null;
    }

    private static boolean tableSourceCitation(String answer, int position,
                                               StaticWebSelectorAnalyzer.Facts facts) {
        TableCell cell = tableCell(answer, position);
        if (cell == null) return false;
        String owner = cell.owner();
        if (owner.length() > 2 && owner.charAt(0) == 96 && owner.charAt(owner.length() - 1) == 96) {
            owner = owner.substring(1, owner.length() - 1);
        }
        return owner.equals(facts.jsFile()) && sourceRolePrefix(cell.header(), facts)
                && !RECOMMENDATION.matcher(cell.caption()).find()
                && !negatedSourceCaption(cell.caption(), facts) && sourceRolePrefix(cell.prefix(), facts);
    }

    private static boolean negatedSourceCaption(String caption, StaticWebSelectorAnalyzer.Facts facts) {
        // A negative problem title is not a negated source assertion. Bind
        // denial of a current query to its explicitly named source instead.
        return Pattern.compile(fileReference(facts.jsFile()) + "[\\x60'\"]?\\s+"
                + "(?i:(?:does\\s+not|never|no\\s+longer|doesn['’]t)\\s+"
                + "(?:uses?|quer(?:y|ies)|references?|selects?|contains?|targets?))\\b")
                .matcher(caption).find();
    }

    private static boolean sourceRolePrefix(String prefix, StaticWebSelectorAnalyzer.Facts facts) {
        if (NEGATED_ROLE.matcher(prefix).find() || RECOMMENDATION.matcher(prefix).find()
                || Pattern.compile("(?i)\\b(?:html|markup|page|dom)\\b").matcher(prefix).find()) return false;
        for (String file : TaskContractResolver.extractExpectedTargets(prefix)) {
            if (!normalize(file).equals(normalize(facts.jsFile()))) return false;
        }
        return true;
    }

    private static boolean recommendationListCaption(String answer, int position) {
        var recommendation = RECOMMENDATION.matcher(listCaption(answer, position));
        while (recommendation.find()) {
            // A descriptive Correct/Wrong comparison is not itself an action
            // instruction. Explicit use/fix/proposed/updated roles still win.
            if (!recommendation.group().equalsIgnoreCase("correct")) return true;
        }
        return false;
    }

    private static boolean isListItem(String answer, int position) {
        int from = answer.lastIndexOf('\n', position - 1) + 1;
        return LIST_ITEM.matcher(answer).region(from, answer.length()).lookingAt();
    }

    private static String listCaption(String answer, int position) {
        int from = answer.lastIndexOf('\n', Math.max(0, position - 1)) + 1;
        // Sibling items have their own roles; only their shared non-item
        // caption can make the whole list an instruction/proposed replacement.
        while (from > 0) {
            int previous = answer.lastIndexOf('\n', from - 2) + 1;
            String line = answer.substring(previous, from - 1);
            if (!LIST_ITEM.matcher(line).lookingAt()) break;
            from = previous;
        }
        return captionBefore(answer, from);
    }

    private static boolean replacementSourceOperand(String answer, String selector,
                                                    StaticWebSelectorAnalyzer.Facts facts, int start, int end) {
        int from = clauseStart(answer, start);
        int to = clauseEnd(answer, end);
        if (to - from > MAX_CONTEXT_CHARS) return false;
        String clause = answer.substring(from, to);
        var replacement = Pattern.compile("(?i:\\b(?:replace|change|switch))\\s+[`'\"]?("
                + Pattern.quote(selector) + ")[`'\"]?\\s+(?i:with|to)\\s+[`'\"]?([#.][A-Za-z_][A-Za-z0-9_-]*)"
                + "[`'\"]?\\s*[.!?]?$").matcher(clause.stripTrailing());
        String target;
        if (replacement.find() && from + replacement.start(1) == start && from + replacement.end(1) == end) {
            target = replacement.group(2);
        } else {
            // Explicit from/to prose gives the missing selector a source role,
            // not a proposed-target role. Bind that role to this inspected file
            // and exact current query; an unrelated read script is insufficient.
            // The closed clause grammar cannot borrow a negation, conditional,
            // neighboring instruction or fenced example as its action prefix.
            if (containingFence(answer, start, end) != null
                    || !Pattern.compile(queryExpression(selector)).matcher(facts.js()).find()) return false;
            var directed = Pattern.compile("\\A\\s*(?i:(?:to[ \\t]+(?:fix|repair)[ \\t]+"
                    + "(?:the[ \\t]+)?(?:bug|mismatch|issue)[ \\t]*,[ \\t]*)?"
                    + "(?:change|switch|replace)[ \\t]+(?:the[ \\t]+)?selector[ \\t]+in[ \\t]+)"
                    + "(?<fileQuote>[`'\"]?)" + Pattern.quote(facts.jsFile()) + "\\k<fileQuote>"
                    + "[ \\t]+(?i:from)[ \\t]+(?<oldQuote>[`'\"]?)(?<old>" + Pattern.quote(selector)
                    + ")\\k<oldQuote>[ \\t]+(?i:to|with)[ \\t]+(?<targetQuote>[`'\"]?)"
                    + "(?<target>[#.][A-Za-z_][A-Za-z0-9_-]*)\\k<targetQuote>[ \\t]*[.!?]?\\z")
                    .matcher(clause.stripTrailing());
            if (!directed.matches() || from + directed.start("old") != start
                    || from + directed.end("old") != end) return false;
            target = directed.group("target");
        }
        return target.startsWith("#") ? facts.htmlIds().contains(target.substring(1))
                : facts.htmlClasses().contains(target.substring(1));
    }

    private record Fence(int opening, int contentStart, int contentEnd, String language) {}

    private static Fence containingFence(String answer, int start, int end) {
        var lines = FENCE_LINE.matcher(answer);
        int opening = -1;
        int contentStart = -1;
        String delimiter = "";
        String language = "";
        while (lines.find()) {
            String marker = lines.group(1);
            String tail = lines.group(2).strip();
            if (opening < 0) {
                opening = lines.start();
                contentStart = lines.end();
                delimiter = marker;
                language = tail.toLowerCase(Locale.ROOT);
            } else if (marker.charAt(0) == delimiter.charAt(0) && marker.length() >= delimiter.length()
                    && tail.isEmpty()) {
                if (contentStart <= start && end <= lines.start()) return new Fence(opening, contentStart, lines.start(), language);
                opening = -1;
            }
        }
        // Distinguish an unfinished fence from ordinary prose so it cannot
        // fall through to the inline source-attribution allowance.
        return opening >= 0 && contentStart <= start
                ? new Fence(opening, contentStart, -1, language) : null;
    }

    private static String captionBefore(String answer, int opening) {
        int end = opening;
        while (end > 0 && Character.isWhitespace(answer.charAt(end - 1))) end--;
        if (end == 0) return "";
        int from = answer.lastIndexOf("\n\n", Math.max(0, end - 1)) + 2;
        if (from == 1) from = 0;
        return end - from > MAX_CONTEXT_CHARS ? "" : answer.substring(from, end);
    }

    private static boolean namesFile(String text, String file) {
        return !file.isBlank() && Pattern.compile(fileReference(file)).matcher(text).find();
    }

    private static String fileReference(String file) {
        return "(?<![A-Za-z0-9_./-])" + Pattern.quote(file) + "(?![A-Za-z0-9_./-])";
    }

    private static int clauseStart(String answer, int position) {
        int from = position;
        while (from > 0 && !clauseBoundary(answer, from - 1)) from--;
        // A closed Markdown code block ends its source-citation context even
        // when prose follows on the very next line. Unfinished fences do not.
        var lines = FENCE_LINE.matcher(answer);
        String opening = "";
        while (lines.find() && lines.end() <= position) {
            String marker = lines.group(1);
            if (opening.isEmpty()) {
                opening = marker;
            } else if (marker.charAt(0) == opening.charAt(0) && marker.length() >= opening.length()
                    && lines.group(2).isBlank()) {
                from = Math.max(from, lines.end());
                opening = "";
            }
        }
        var items = LIST_ITEM.matcher(answer);
        while (items.find() && items.start() <= position) {
            if (items.start() > from && containingFence(answer, items.start(), items.end()) == null) from = items.start();
        }
        return from;
    }

    private static int clauseEnd(String answer, int position) {
        int to = position;
        while (to < answer.length() && !clauseBoundary(answer, to)) to++;
        var items = LIST_ITEM.matcher(answer);
        while (items.find() && items.start() < to) {
            if (items.start() >= position && containingFence(answer, items.start(), items.end()) == null) {
                to = items.start();
                break;
            }
        }
        return to;
    }

    private static boolean clauseBoundary(String answer, int at) {
        char current = answer.charAt(at);
        return current == ';' || ((current == '.' || current == '!' || current == '?')
                && (at + 1 == answer.length() || Character.isWhitespace(answer.charAt(at + 1))))
                || current == '\n' && (at > 0 && answer.charAt(at - 1) == '\n'
                || at + 1 < answer.length() && answer.charAt(at + 1) == '\n');
    }

    private static boolean coveredBy(Pattern pattern, String answer, int start, int end) {
        var match = pattern.matcher(answer);
        while (match.find()) {
            if (match.start() <= start && match.end() >= end) return true;
        }
        return false;
    }

    private static String normalize(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }
}
