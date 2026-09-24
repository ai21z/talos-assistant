package dev.talos.runtime.intent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded parser for explicit content-mutation clauses inside one constraint tail.
 *
 * <p>It intentionally does not own top-level sequential clauses or workspace
 * operations. Those families remain non-authoritative until their typed owners
 * consume the shared lexical primitives.
 */
public final class CoordinatedMutationTailParser {

    static final int MAX_TARGET_OUTPUT_VISITS = 16_384;
    static final int MAX_INDEXED_TARGET_OCCURRENCES = 8_192;

    private static final Set<String> BARE_ANAPHORA = Set.of(
            "it", "that", "this", "them", "those");

    private static final List<String> CONSTRAINT_MARKERS = List.of(
            " so ",
            " so,",
            " without breaking ",
            " without changing ",
            " compatible with ",
            " stay compatible with ",
            " stays compatible with ");
    private static final List<String> LEADING_CONSTRAINT_CONNECTORS = List.of(
            " so ",
            " so,");

    private static final Pattern OPTIONAL_SUFFIX = Pattern.compile(
            "(?i)^[\\s,:-]*(?:(?:too|also)\\s+)?"
                     + "(?:(?:if|when|unless)\\b|as\\s+"
                    + "(?:needed|necessary|required|appropriate|applicable|possible|feasible)\\b"
                    + "|only\\s+(?:if|when)\\b"
                    + "|where\\s+(?:needed|necessary|appropriate|applicable|"
                    + "possible|feasible)\\b"
                    + "|(?:maybe|optionally)\\b)");
    private static final Pattern PROPOSAL_SUFFIX = Pattern.compile(
            "(?i)^[\\s,:-]*(?:(?:too|also)\\s+)?"
                    + "(?:(?:only\\s+)?as\\s+(?:a\\s+)?"
                    + "(?:proposal|suggestion|recommendation)\\b"
                    + "|as\\s+an?\\s+option\\b"
                    + "|only\\s+as\\s+(?:a\\s+)?possible\\s+"
                    + "(?:change|edit|update)\\b"
                    + "|for\\s+(?:review|discussion|reference|context|comparison|"
                    + "consideration)(?:\\s+only)?\\b)");
    private static final Pattern TRAILING_ALTERNATIVE_CONNECTOR =
            Pattern.compile("(?i)\\bor\\s*$");
    private static final Pattern TARGET_LOCAL_ALTERNATIVE = Pattern.compile(
            "(?i)^[\\s,:-]*(?:or|otherwise)\\s+(?:(?:leave|keep)\\s+"
                    + "(?:it|this|that|the\\s+(?:file|target))\\s+"
                    + "(?:unchanged|intact|as\\s*-?\\s*is)|do\\s+nothing)\\b");
    private static final Pattern PROHIBITION = Pattern.compile(
             "(?iu)(?:\\bdo\\s+not\\b|\\bdon['\\u2019]t\\b|\\bdont\\b|\\bnever\\b|"
                     + "\\bmust\\s+not\\b|\\bmay\\s+not\\b|\\bshould\\s+not\\b|"
                    + "\\bshall\\s+not\\b|\\bcannot\\b|\\bcan['\\u2019]t\\b|"
                    + "\\b(?:mustn|shouldn|shalln)['\\u2019]t\\b|\\bavoid\\b|"
                     + "\\bnot\\s+(?:also\\s+)?$|"
                     + "\\b(?:you\\s+are\\s+)?not\\s+allowed\\s+to\\s*$|"
                    + "\\b(?:you|talos)\\s+(?:aren|isn)['\\u2019]t\\s+"
                    + "(?:allowed|authorized|permitted)\\s+to\\b|"
                    + "\\b(?:i|we)\\s+forbid(?:\\s+(?:you|talos))?\\s+to\\b|"
                    + "\\b(?:you|talos)\\s+(?:are|is)\\s+forbidden\\s+to\\b|"
                    + "\\b(?:i|we)\\s+(?:deny|revoke)\\s+"
                    + "(?:(?:your|talos['\\u2019]s)\\s+)?permission\\s+to\\b|"
                    + "\\bnot\\s+to\\b|"
                    + "\\b(?:rather\\s+than|instead\\s+of)\\s*$|"
                    + "\\b(?:do\\s+)?(?:anything|everything)\\s+"
                    + "(?:but|except)\\s*$|"
                    + "\\bwithout\\s+(?:changing|editing|modifying|updating|"
                    + "rewriting|adjusting|touching)\\b)");
    private static final Pattern DIRECT_TARGET_PREFIX = Pattern.compile(
            "(?i)^[\\s:,-]*(?:(?:exactly|only)\\s+)*"
                    + "(?:(?:the|a|an|both|current|existing|local)\\s+)*"
                    + "(?:(?:file|files)\\s+)?$");
    private static final Pattern DISCOVERABLE_DIRECT_TARGET_PREFIX = Pattern.compile(
            "(?i)^[\\s:,-]*(?:(?:exactly|only)\\s+)*"
                    + "(?:(?:the|a|an|both|current|existing|local)\\s+)*"
                    + "(?:(?:file|files)\\s+)?");
    private static final Pattern DISCOVERABLE_WORKSPACE_TARGET_PREFIX =
            Pattern.compile(
                    "(?i)^[\\s:,-]*(?:(?:named|called|as)\\s+)?"
                            + "(?:(?:the|a|an|current|existing|local)\\s+)*"
                            + "(?:(?:path|file|directory|folder)\\s+)?");
    private static final Pattern DISCOVERABLE_UNQUOTED_PATH = Pattern.compile(
            "[A-Za-z0-9_.\\\\/-]+");
    private static final Pattern WORKSPACE_NAMED_TARGET_PREFIX = Pattern.compile(
            "(?i)^[\\s:,-]*(?:(?:named|called)\\s+)?"
                    + "(?:(?:the|a|an|current|existing|local)\\s+)*"
                    + "(?:(?:path|file|directory|folder)\\s+)?$");
    private static final Pattern ADJACENT_TARGET_GAP = Pattern.compile(
            "(?i)^\\s*(?:(?:,\\s*(?:(?:and\\s*/\\s*or|and|or|&)\\s+)?"
                    + "|(?:and\\s*/\\s*or|and|or|&)\\s+)"
                    + "(?:also\\s+)?)$");
    private static final Pattern DISJUNCTIVE_TARGET_GAP =
            Pattern.compile("(?i)\\bor\\b|\\band\\s*/\\s*or\\b");
    private static final Pattern PREPOSED_TARGET_SUFFIX = Pattern.compile(
            "(?i)^[\\s,:-]*(?:(?:please|kindly)\\s+)?$");
    private static final Pattern PREPOSED_TARGET_PREFIX_TAIL = Pattern.compile(
            "(?i)(?:^|(?:,\\s*)?\\b(?:and|then|but|also)\\b\\s+"
                    + "|[;\\r\\n]+\\s*[-*]?\\s*"
                    + "|\\s*[\\u2013\\u2014]\\s*)"
                    + "((?:in|inside|within)\\s+"
                    + "(?:(?:the|current|existing|local)\\s+)*"
                    + "(?:(?:file)\\s+)?)$");
    private static final Pattern STRONG_CONNECTOR = Pattern.compile(
            "(?i)(?:,\\s*)?\\b(?:and|but|then)\\b(?:\\s+also\\b)?\\s*"
                    + "|,\\s*\\balso\\b\\s*"
                    + "|[;\\r\\n]+|\\s*[\\u2013\\u2014]\\s*");
    private static final Pattern TRAILING_REPORTED_PAYLOAD_CONNECTOR =
            Pattern.compile(
                    "(?i)(?:,\\s*)?\\b(?:and|but|then)\\b"
                            + "(?:\\s+also\\b)?\\s*$"
                            + "|,\\s*\\balso\\b\\s*$");
    private static final Pattern ANY_CONNECTOR = Pattern.compile(
            "(?i)(?:,\\s*)?\\b(?:and|but|then)\\b(?:\\s+also\\b)?\\s*"
                    + "|,\\s*\\balso\\b\\s*"
                    + "|[,;\\r\\n]+|\\s*[\\u2013\\u2014]\\s*");
    private static final Pattern HARD_CLAUSE_CONNECTOR = Pattern.compile(
            "(?i),\\s*\\b(?:and|but|then|also)\\b\\s*"
                    + "|[;\\r\\n]+|\\s*[\\u2013\\u2014]\\s*");
    private static final Pattern CONNECTOR_WORD =
            Pattern.compile("(?i)\\b(?:and|but|then|also)\\b");
    private static final Pattern REPORTED_CONNECTOR_MODIFIER = Pattern.compile(
            "(?i)\\b(?:the\\s+)?(?:ticket|guide|policy|runbook|reviewer|"
                    + "verifier|report|requirements?|spec(?:ification)?|"
                    + "documentation|document|message|comment|note|issue|task|"
                    + "prompt|example)\\s+(?:then|also)\\s+"
                    + "(?:says?|said|states?|stated|requires?|required|"
                    + "proposes?|proposed|suggests?|suggested|advises?|advised|"
                    + "directs?|directed|commands?|commanded|asks?|asked|"
                    + "instructs?|instructed|tells?|told|requests?|requested|"
                    + "recommends?|recommended|mentions?|mentioned|wrote|reads?)\\b");
    private static final Pattern TARGET_PREFIX_PROHIBITION = Pattern.compile(
            "(?iu)(?:\\b(?:do\\s+not|don['\\u2019]t|dont|never|must\\s+not|may\\s+not|"
                    + "should\\s+not|shall\\s+not|cannot|can['\\u2019]t|"
                    + "(?:mustn|shouldn|shalln)['\\u2019]t)\\s+"
                     + "(?:(?:edit|modify|change|update|adjust|fix|repair|overwrite|"
                     + "rewrite|replace|append|remove|delete|move|copy|rename|"
                     + "refactor|touch|create|make|mkdir|"
                     + "(?:create|make)\\s+(?:(?:a|an|the)\\s+)?"
                     + "(?:new\\s+)?(?:directories|directory|dirs|dir|folders|folder)"
                     + "(?:\\s+(?:named|called))?)\\s+)"
                    + "|\\bavoid\\s+(?:(?:editing|modifying|changing|updating|"
                     + "adjusting|fixing|repairing|overwriting|rewriting|replacing|"
                     + "appending|removing|deleting|moving|copying|renaming|"
                     + "refactoring|touching)\\s+)"
                    + "|\\b(?:i|we)\\s+forbid(?:\\s+(?:you|talos))?\\s+to\\s+"
                     + "|\\b(?:you|talos)\\s+(?:are|is)\\s+forbidden\\s+to\\s+"
                    + "|\\b(?:you|talos)\\s+(?:aren|isn)['\\u2019]t\\s+"
                    + "(?:allowed|authorized|permitted)\\s+to\\s+"
                    + "|\\b(?:i|we)\\s+(?:deny|revoke)\\s+"
                    + "(?:(?:your|talos['\\u2019]s)\\s+)?permission\\s+to\\s+"
                    + "|\\bnot\\s+to\\s+"
                    + "|\\bwithout\\s+(?:editing|modifying|changing|updating|"
                    + "adjusting|fixing|repairing|overwriting|rewriting|replacing|"
                    + "appending|removing|deleting|moving|copying|renaming|"
                    + "refactoring|touching)\\s+)$");
    private static final Pattern TARGET_SUFFIX_PROHIBITION = Pattern.compile(
            "(?i)^\\s*(?:(?:must|may|should|shall)\\s+not\\s+(?:be\\s+)?"
                    + "(?:edited|modified|changed|updated|adjusted|fixed|repaired|"
                    + "overwritten|rewritten|replaced|appended|removed|deleted|"
                    + "moved|copied|renamed|refactored|touched)"
                    + "|(?:(?:must|should|shall)\\s+)?"
                    + "(?:remain|remains|stay|stays)\\s+"
                    + "(?:unchanged|intact|as\\s*-?\\s*is|read\\s*-?\\s*only))\\b");
    /*
     * A conservative one-pass prefilter for the target-local prohibition
     * grammar above. False positives are harmless; false negatives are not.
     * It prevents ordinary positive tails from paying the substantially more
     * expensive scope-sensitive prohibition walk once per target.
     */
    private static final Pattern PERSISTENT_PROHIBITION_SIGNAL = Pattern.compile(
            "(?iu)\\b(?:do\\s+not|don['\\u2019]?t|dont|never|must\\s+not|"
                    + "may\\s+not|should\\s+not|shall\\s+not|cannot|"
                    + "can['\\u2019]?t|mustn['\\u2019]?t|shouldn['\\u2019]?t|"
                    + "shalln['\\u2019]?t|avoid|forbid|forbidden|prohibit|"
                    + "prohibited|disallow|disallowed|deny|revoke|not\\s+to|"
                    + "rather\\s+than|instead\\s+of|without\\s+(?:changing|"
                    + "editing|modifying|updating|rewriting|adjusting|touching)|"
                    + "remain|remains|stay|stays)\\b");
    private static final Pattern DECLARATIVE_SUFFIX_PROHIBITION =
             Pattern.compile(
                     "(?iu)^\\s*,?\\s*(?:which\\s+)?"
                            + "(?:(?:(?:is|are|was|were)"
                            + "|(?:has|have|had)\\s+been)\\s+"
                            + "(?:(?:forbidden|prohibited|disallowed)\\b"
                            + "|(?:not|never|no\\s+longer)\\s+"
                            + "(?:allowed|authorized|permitted)\\b)"
                            + "|(?:has|have|had)"
                            + "(?:n['\\u2019]t|\\s+(?:not|never))\\s+been\\s+"
                            + "(?:allowed|authorized|permitted)\\b)");
    private static final Pattern DECLARATIVE_SUFFIX_NON_AUTHORITATIVE =
            Pattern.compile(
                    "(?iu)^\\s*,?\\s*(?:which\\s+)?(?:is\\s+what\\s+"
                            + "[\\p{L}\\p{N}][\\p{L}\\p{N}_'\\u2019-]*"
                            + "(?:\\s+[\\p{L}\\p{N}][\\p{L}\\p{N}_'\\u2019-]*){0,4}"
                            + "\\s+(?:proposed|suggested|recommended)"
                            + "|was\\s+"
                            + "[\\p{L}\\p{N}][\\p{L}\\p{N}_-]*"
                            + "['\\u2019]s\\s+"
                            + "(?:proposal|suggestion|recommendation)"
                            + "|appears?\\s+in\\s+(?:the\\s+)?"
                            + "(?:report|review|ticket|guide|runbook|spec)"
                             + "|(?:is|was)\\s+(?:(?:only|just|merely)\\s+)?"
                             + "(?:an?\\s+)?(?:proposal|suggestion|"
                             + "recommendation|example|option|hypothetical)"
                            + "|(?:has|have|had)\\s+been\\s+"
                            + "(?:proposed|suggested|recommended|requested|"
                            + "reported|mentioned|documented|described|rejected)"
                            + "|(?:has|have|had)"
                            + "(?:n['\\u2019]t|\\s+(?:not|never))\\s+been\\s+"
                            + "(?:proposed|suggested|recommended|requested|"
                            + "reported|mentioned|documented|described|"
                            + "forbidden|prohibited|disallowed|rejected)"
                            + "|(?:has|have|had)\\s+been\\s+"
                            + "(?:allowed|authorized|permitted)"
                             + "|would\\s+be\\s+(?:one|an)\\s+option"
                            + ")\\b");
    private static final Pattern POSTFIX_REPORTED_ATTRIBUTION =
            Pattern.compile(
                    "(?iu)^\\s*(?:,|[\\u2013\\u2014])\\s*"
                            + "(?:(?:(?:as|or\\s+so)\\s+)?(?:the\\s+)?"
                            + "[\\p{L}\\p{N}][\\p{L}\\p{N}_'\\u2019-]*"
                            + "(?:\\s+[\\p{L}\\p{N}]"
                            + "[\\p{L}\\p{N}_'\\u2019-]*){0,4}\\s+"
                            + CanonicalMutationClauseMatcher
                                    .REPORTED_PREDICATE_WORD
                            + "|"
                            + CanonicalMutationClauseMatcher
                                    .REPORTED_PREDICATE_WORD
                            + "\\s+(?:the\\s+)?"
                            + "[\\p{L}\\p{N}][\\p{L}\\p{N}_'\\u2019-]*"
                            + "(?:\\s+[\\p{L}\\p{N}]"
                            + "[\\p{L}\\p{N}_'\\u2019-]*){0,4}"
                            + "|according\\s+to\\s+(?:the\\s+)?"
                            + "[\\p{L}\\p{N}][\\p{L}\\p{N}_'\\u2019-]*"
                            + "(?:\\s+[\\p{L}\\p{N}]"
                            + "[\\p{L}\\p{N}_'\\u2019-]*){0,4})\\b");
    private static final Pattern DECLARATIVE_SUFFIX_OPTIONAL =
            Pattern.compile(
                    "(?iu)^\\s*,?\\s*(?:which\\s+)?"
                            + "(?:(?:may|might|could)\\s+be\\s+useful"
                            + "|(?:is|was)\\s+optional)\\b");
    private static final Pattern DECLARATIVE_SUFFIX_FRAME =
            Pattern.compile(
                    "(?iu)^\\s*,?\\s*(?:which\\s+)?"
                            + "(?:is|was|would|may|might|could|appears?|seems?)\\b");
    private static final Pattern AUTHORITY_SCOPE_CONTINUATION =
            Pattern.compile("(?i)\\b(?:and|or|also)\\b");
    private static final Pattern TYPED_SCOPE_CONNECTOR =
            Pattern.compile("(?iu)\\b(?:but|then)\\b");
    private static final Pattern PLAIN_THEN_OR_BUT =
            Pattern.compile("(?isu)^\\s*(?:then|but)\\s*$");
    private static final Pattern COORDINATED_SUBJECT_RIGHT = Pattern.compile(
            "(?iu)^\\s*((?:the\\s+)?"
                    + "[\\p{L}\\p{N}][\\p{L}\\p{N}_'\\u2019-]*"
                    + "(?:\\s+[\\p{L}\\p{N}][\\p{L}\\p{N}_'\\u2019-]*){0,5})"
                    + "\\s+(?:(?:really|genuinely|definitely|actually|"
                    + "carefully|explicitly|also|now|next)\\s+){0,4}"
                    + "(?:needs?\\s+to|needed\\s+to|wants?\\s+to|wanted\\s+to|"
                    + "(?:has|have|had)\\s+to|must|should|shall|will|can|could|"
                    + "would|may|might)\\s*$");
    private static final Pattern NATURAL_BATCH_FIRST_TARGET_PREFIX =
            Pattern.compile("^\\s+$");
    private static final Pattern NATURAL_BATCH_TARGET_CONNECTOR =
            Pattern.compile(
                    "(?i)^(?:\\s+|\\s*(?:and(?:\\s*/\\s*or)?|or|&|,)"
                            + "\\s*(?:(?:and|or)\\s+)?$)");
    private static final Pattern WORKSPACE_TARGET_KIND_SUFFIX = Pattern.compile(
            "(?i)^[\\s,:-]*(?:directories|directory|dirs|dir|folders|folder)\\b");
    private CoordinatedMutationTailParser() {}

    public static CoordinatedMutationTailPlan parse(
            String canonicalRequest,
            Set<String> candidateTargets
    ) {
        return parseInternal(
                canonicalRequest,
                candidateTargets,
                new MutableParseWork());
    }

    static ParseWorkResult parseWithWork(
            String canonicalRequest,
            Set<String> candidateTargets
    ) {
        MutableParseWork work = new MutableParseWork();
        CoordinatedMutationTailPlan plan = parseInternal(
                canonicalRequest,
                candidateTargets,
                work);
        return new ParseWorkResult(plan, work.snapshot());
    }

    private static CoordinatedMutationTailPlan parseInternal(
            String canonicalRequest,
            Set<String> candidateTargets,
            MutableParseWork work
    ) {
        String request = canonicalRequest == null ? "" : canonicalRequest;
        RequestLexicalView view = RequestLexicalView.of(request);
        int constraintStart = firstUnquotedConstraintBoundary(view);
        if (constraintStart < 0) {
            return CoordinatedMutationTailPlan.empty(request);
        }
        List<String> candidates = normalizedCandidates(candidateTargets);
        int scanEnd = view.sentenceEndAfter(constraintStart);
        TargetOccurrenceIndex occurrenceIndex = TargetOccurrenceIndex.build(
                view,
                candidates,
                constraintStart,
                scanEnd,
                work);
        int tailEnd = boundedTailEnd(
                view,
                candidates,
                constraintStart,
                occurrenceIndex);
        Map<String, CoordinatedMutationTailPlan.TargetDecision> effective =
                new LinkedHashMap<>();
        List<CoordinatedMutationTailPlan.WorkspaceOperationObservation>
                workspaceOperationObservations = new ArrayList<>();
        List<CoordinatedMutationTailPlan.WorkspaceOperationClauseObservation>
                workspaceOperationClauseObservations = new ArrayList<>();
        List<WorkspaceOperationObligation> workspaceOperationObligations =
                new ArrayList<>();
        List<CanonicalMutationClauseMatcher.Action> actions =
                mutationActions(
                        view,
                        candidates,
                        constraintStart,
                        tailEnd,
                        occurrenceIndex);
        WorkspaceActionIndex workspaceActionIndex =
                WorkspaceActionIndex.build(actions);
        addPersistentProhibitions(
                view,
                candidates,
                constraintStart,
                tailEnd,
                effective,
                occurrenceIndex,
                workspaceActionIndex);
        AuthorityScope inheritedScope = null;
        AuthorityTransition pendingTransition = AuthorityTransition.disconnected();
        int priorBoundEnvelopeEnd = -1;
        for (int i = 0; i < actions.size(); i++) {
            AuthorityTransition incomingTransition = pendingTransition;
            pendingTransition = AuthorityTransition.disconnected();
            CanonicalMutationClauseMatcher.Action action = actions.get(i);
            int nextActionStart = i + 1 < actions.size()
                    ? actions.get(i + 1).start()
                    : tailEnd;
            BoundTargets provisionalDirect = directTargets(
                    view,
                    candidates,
                    action.family(),
                    action.end(),
                    nextActionStart,
                    occurrenceIndex);
            int preposedSearchStart = i == 0
                    ? constraintStart
                    : actions.get(i - 1).end();
            PreposedTargets provisionalPreposed =
                    provisionalDirect.paths().isEmpty()
                            ? preposedTargets(
                                    view,
                                    candidates,
                                    preposedSearchStart,
                                    action.start(),
                                    occurrenceIndex)
                            : PreposedTargets.empty(action.start());
            int actionEnd = i + 1 < actions.size()
                    ? boundaryBeforeNextAction(
                            view,
                            candidates,
                            provisionalDirect.paths().isEmpty()
                                    ? action.end()
                                    : provisionalDirect.lastEnvelopeEnd(),
                            nextActionStart,
                            occurrenceIndex)
                    : tailEnd;
            int clauseStart = i == 0
                    ? boundaryAfterLastConnector(
                            view,
                            candidates,
                            constraintPayloadStart(view, constraintStart),
                            action.start(),
                            STRONG_CONNECTOR,
                            provisionalPreposed.envelopeStart(),
                            provisionalPreposed.envelopeEnd(),
                            occurrenceIndex)
                    : boundaryAfterLastConnector(
                            view,
                            candidates,
                            actions.get(i - 1).end(),
                            action.start(),
                            ANY_CONNECTOR,
                            provisionalPreposed.envelopeStart(),
                            provisionalPreposed.envelopeEnd(),
                            occurrenceIndex);
            if (i > 0 && priorBoundEnvelopeEnd >= 0) {
                clauseStart = Math.max(clauseStart, priorBoundEnvelopeEnd);
            }
            if (incomingTransition.effect() == TransitionEffect.RESET) {
                clauseStart = Math.max(
                        clauseStart,
                        incomingTransition.grammarStart());
            }
            RequestLexicalView.Span span = view.trimmedSpan(clauseStart, actionEnd);
            BoundTargets boundTargets = directTargets(
                    view,
                    candidates,
                    action.family(),
                    action.end(),
                    actionEnd,
                    occurrenceIndex);
            PreposedTargets preposed = PreposedTargets.empty(action.start());
            if (boundTargets.paths().isEmpty()) {
                preposed = provisionalPreposed.targets().isEmpty()
                        ? preposedTargets(
                                view,
                                candidates,
                                clauseStart,
                                action.start(),
                                occurrenceIndex)
                        : provisionalPreposed;
                if (!preposed.targets().isEmpty()) {
                    boundTargets = new BoundTargets(
                            List.of(new BoundTarget(
                                    preposed.targets().getFirst(),
                                    preposed.envelopeStart(),
                                    preposed.envelopeEnd())),
                            action.end(),
                            false);
                    span = view.trimmedSpan(preposed.sourceStart(), actionEnd);
                }
            }
            int actionPrefixEnd = Math.max(
                    span.start(),
                    Math.min(action.start(), span.end()));
            String visiblePrefix = authorityText(
                    view,
                    span.start(),
                    actionPrefixEnd,
                    preposed.envelopeStart(),
                    preposed.envelopeEnd());
            CoordinatedMutationTailPlan.Authority prefixAuthority =
                    prefixAuthority(visiblePrefix);
            int prefixScopeStart = span.start();
            if (i == 0 && clauseStart > constraintStart) {
                AuthorityScope headerScope = activeRestrictiveScope(
                        view,
                        candidates,
                        constraintPayloadStart(view, constraintStart),
                        action.start(),
                        preposed.envelopeStart(),
                        preposed.envelopeEnd(),
                        occurrenceIndex);
                if (headerScope == null) {
                    int payloadStart = constraintPayloadStart(
                            view,
                            constraintStart);
                    String fullHeader = authorityText(
                            view,
                            payloadStart,
                            action.start(),
                            preposed.envelopeStart(),
                            preposed.envelopeEnd());
                    CoordinatedMutationTailPlan.Authority boundedAuthority =
                            authorityForScopeKind(
                                    CanonicalMutationClauseMatcher
                                            .finiteScopeKind(fullHeader));
                    if (boundedAuthority != null) {
                        headerScope = new AuthorityScope(
                                boundedAuthority,
                                payloadStart);
                    }
                }
                if (headerScope != null) {
                    AuthorityTransition headerTransition = authorityTransition(
                            view,
                            headerScope.startOffset(),
                            action.start(),
                            headerScope.authority());
                    if (headerTransition.effect() == TransitionEffect.CONTINUE) {
                        prefixAuthority = restrictiveAuthority(
                                prefixAuthority,
                                headerScope.authority());
                        prefixScopeStart = headerScope.startOffset();
                        span = view.trimmedSpan(
                                Math.min(prefixScopeStart, preposed.sourceStart()),
                                actionEnd);
                    } else if (headerTransition.effect()
                            == TransitionEffect.RESET) {
                        incomingTransition = headerTransition;
                        clauseStart = Math.max(
                                clauseStart,
                                headerTransition.grammarStart());
                        span = view.trimmedSpan(clauseStart, actionEnd);
                        actionPrefixEnd = Math.max(
                                span.start(),
                                Math.min(action.start(), span.end()));
                        visiblePrefix = authorityText(
                                view,
                                span.start(),
                                actionPrefixEnd,
                                preposed.envelopeStart(),
                                preposed.envelopeEnd());
                        prefixAuthority = prefixAuthority(visiblePrefix);
                        prefixScopeStart = span.start();
                    }
                }
            }
            if (action.nonImperativeUse()
                    && incomingTransition.effect() != TransitionEffect.RESET) {
                prefixAuthority = restrictiveAuthority(
                        prefixAuthority,
                        CoordinatedMutationTailPlan.Authority
                                .NON_AUTHORITATIVE);
            }
            AuthorityScope prefixScope = prefixAuthority == null
                    ? null
                    : new AuthorityScope(prefixAuthority, prefixScopeStart);

            int suffixStart = Math.max(
                    action.end(),
                    Math.min(boundTargets.lastEnvelopeEnd(), actionEnd));
            String visibleSuffix =
                    view.maskedLower().substring(suffixStart, actionEnd);
            NaturalBatchMatch naturalBatch = naturalBatchWorkspaceCreate(
                    view,
                    candidates,
                    action,
                    boundTargets,
                    occurrenceIndex,
                    nextActionStart,
                    workspaceActionIndex);
            boolean workspaceOperation =
                    action.family()
                                    == CanonicalMutationClauseMatcher.Family
                                            .WORKSPACE_OPERATION
                            || naturalBatch != null
                            || (("create".equals(action.verb())
                                    || "make".equals(action.verb()))
                                    && WORKSPACE_TARGET_KIND_SUFFIX
                                            .matcher(visibleSuffix)
                                            .find());
            if (naturalBatch != null) {
                boundTargets = naturalBatch.boundTargets();
                span = view.trimmedSpan(
                        Math.min(span.start(), naturalBatch.startOffset()),
                        naturalBatch.endOffset());
            }
            CoordinatedMutationTailPlan.Authority authority = authorityFor(
                    workspaceOperation,
                    prefixAuthority,
                    inheritedScope == null ? null : inheritedScope.authority(),
                    visibleSuffix,
                    boundTargets.disjunctive(),
                    MutationPostfixOwnership.analyzeConstraint(view, suffixStart, actionEnd));
            if (!workspaceOperation && authority != CoordinatedMutationTailPlan.Authority.FORBIDDEN
                    && MutationPostfixOwnership.rawConstraintTainted(view, action.start())) {
                authority = CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE;
            }
            if (inheritedScope != null) {
                span = view.trimmedSpan(
                        inheritedScope.startOffset(),
                        naturalBatch == null
                                ? actionEnd
                                : naturalBatch.endOffset());
            }
            if (authority == CoordinatedMutationTailPlan.Authority.REQUIRED
                    && incomingTransition.effect() == TransitionEffect.RESET) {
                span = view.trimmedSpan(
                        Math.min(
                                incomingTransition.evidenceStart(),
                                span.start()),
                        span.end());
            }
            if ((authority == CoordinatedMutationTailPlan.Authority.REQUIRED
                            || authority
                                    == CoordinatedMutationTailPlan.Authority
                                            .OPTIONAL)
                    && view.containsProviderRemovedControl(
                            span.start(),
                            boundTargets.lastEnvelopeEnd())) {
                authority = CoordinatedMutationTailPlan.Authority
                        .NON_AUTHORITATIVE;
            }
            String sourceText = view.substring(span);
            if (workspaceOperation) {
                workspaceOperationClauseObservations.add(
                        new CoordinatedMutationTailPlan
                                .WorkspaceOperationClauseObservation(
                                        action.verb(),
                                        span.start(),
                                        span.end(),
                                        sourceText));
                if (authority
                        == CoordinatedMutationTailPlan.Authority
                                .WORKSPACE_OPERATION_BLOCKED) {
                    CanonicalMutationClauseMatcher.bindWorkspaceOperation(
                                    view,
                                    action,
                                    CanonicalMutationClauseMatcher.Family
                                            .WORKSPACE_OPERATION,
                                    span.start(),
                                    span.end(),
                                    candidates,
                                    WorkspaceOperationObligation.Owner
                                            .COORDINATED_TAIL)
                            .ifPresent(workspaceOperationObligations::add);
                }
            }
            for (BoundTarget target : boundTargets.targets()) {
                if (workspaceOperation) {
                    workspaceOperationObservations.add(
                            new CoordinatedMutationTailPlan
                                    .WorkspaceOperationObservation(
                                            target.path(),
                                            action.verb(),
                                            span.start(),
                                            span.end(),
                                            sourceText));
                }
                merge(effective, new CoordinatedMutationTailPlan.TargetDecision(
                        target.path(),
                        authority,
                        action.verb(),
                        workspaceOperation
                                ? CoordinatedMutationTailPlan.ActionFamily
                                        .WORKSPACE_OPERATION
                                : CoordinatedMutationTailPlan.ActionFamily
                                        .CONTENT_MUTATION,
                        action.start(),
                        action.end(),
                        target.envelopeStart(),
                        span.start(),
                        target.envelopeEnd(),
                        span.end(),
                        sourceText));
            }
            AuthorityScope candidateScope =
                    prefixScope == null ? inheritedScope : prefixScope;
            if (i + 1 < actions.size() && candidateScope != null) {
                pendingTransition = authorityTransition(
                        view,
                        boundTargets.paths().isEmpty()
                                ? action.end()
                                : boundTargets.lastEnvelopeEnd(),
                        actions.get(i + 1).start(),
                        candidateScope.authority());
                inheritedScope = pendingTransition.effect()
                                == TransitionEffect.CONTINUE
                        ? candidateScope
                        : null;
            } else {
                inheritedScope = null;
            }
            priorBoundEnvelopeEnd = boundTargets.paths().isEmpty()
                    ? action.end()
                    : boundTargets.lastEnvelopeEnd();
        }
        return new CoordinatedMutationTailPlan(
                request,
                constraintStart,
                tailEnd,
                effective.values().stream()
                        .sorted(Comparator.comparing(
                                CoordinatedMutationTailPlan.TargetDecision::path))
                        .toList(),
                workspaceOperationObservations,
                workspaceOperationClauseObservations,
                workspaceOperationObligations);
    }

    private static int constraintPayloadStart(
            RequestLexicalView view,
            int constraintStart
    ) {
        if (view == null || constraintStart < 0) return constraintStart;
        String request = view.request();
        for (String marker : LEADING_CONSTRAINT_CONNECTORS) {
            int markerEnd = constraintStart + marker.length();
            if (markerEnd <= request.length()
                    && request.regionMatches(
                            true,
                            constraintStart,
                            marker,
                            0,
                            marker.length())) {
                return markerEnd;
            }
        }
        return constraintStart;
    }

    static Set<String> discoverDirectTargets(String canonicalRequest) {
        String request = canonicalRequest == null ? "" : canonicalRequest;
        RequestLexicalView view = RequestLexicalView.of(request);
        int constraintStart = firstUnquotedConstraintBoundary(view);
        if (constraintStart < 0) return Set.of();
        int tailEnd = boundedTailEnd(view, List.of(), constraintStart);
        return discoverDirectTargets(view, constraintStart, tailEnd);
    }

    static Set<String> discoverAllDirectTargets(String canonicalRequest) {
        String request = canonicalRequest == null ? "" : canonicalRequest;
        RequestLexicalView view = RequestLexicalView.of(request);
        return discoverDirectTargets(view, 0, request.length());
    }

    private static Set<String> discoverDirectTargets(
            RequestLexicalView view,
            int from,
            int to
    ) {
        String request = view.request();
        int safeFrom = Math.max(0, from);
        int safeTo = Math.min(request.length(), Math.max(safeFrom, to));
        LinkedHashSet<String> discovered = new LinkedHashSet<>();
        List<CanonicalMutationClauseMatcher.Action> actions =
                CanonicalMutationClauseMatcher.actions(
                        view,
                        safeFrom,
                        safeTo);
        for (int index = 0; index < actions.size(); index++) {
            CanonicalMutationClauseMatcher.Action action = actions.get(index);
            int actionLimit = index + 1 < actions.size()
                    ? actions.get(index + 1).start()
                    : safeTo;
            String remainder = request.substring(action.end(), actionLimit);
            Pattern directPrefix =
                    action.family()
                                    == CanonicalMutationClauseMatcher.Family
                                            .WORKSPACE_OPERATION
                            ? DISCOVERABLE_WORKSPACE_TARGET_PREFIX
                            : DISCOVERABLE_DIRECT_TARGET_PREFIX;
            Matcher prefix = directPrefix.matcher(remainder);
            if (!prefix.lookingAt()) continue;
            int targetStart = action.end() + prefix.end();
            if (targetStart >= actionLimit) continue;

            RequestLexicalView.Span quoted =
                    view.exactQuotedContentStartingAt(targetStart, actionLimit);
            if (quoted != null) {
                addDiscoveredTarget(
                        discovered,
                        request.substring(quoted.start(), quoted.end()),
                        true,
                        action.family()
                                == CanonicalMutationClauseMatcher.Family
                                        .WORKSPACE_OPERATION);
                continue;
            }

            Matcher token = DISCOVERABLE_UNQUOTED_PATH.matcher(request);
            token.region(targetStart, actionLimit);
            if (!token.lookingAt()) continue;
            String candidate = token.group();
            while (!candidate.isEmpty()
                    && ".,;:!?".indexOf(
                            candidate.charAt(candidate.length() - 1)) >= 0) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            String suffix = request.substring(token.end(), actionLimit);
            boolean workspaceBlockingTarget =
                    action.family()
                                    == CanonicalMutationClauseMatcher.Family
                                            .WORKSPACE_OPERATION
                            || (("create".equals(action.verb())
                                    || "make".equals(action.verb()))
                                    && WORKSPACE_TARGET_KIND_SUFFIX
                                            .matcher(suffix)
                                            .find());
            addDiscoveredTarget(
                    discovered,
                    candidate,
                    false,
                    workspaceBlockingTarget);
        }
        discoverNaturalWorkspaceOperands(view, actions, discovered);
        return Set.copyOf(discovered);
    }

    private static void discoverNaturalWorkspaceOperands(
            RequestLexicalView view,
            List<CanonicalMutationClauseMatcher.Action> actions,
            Set<String> discovered
    ) {
        if (view == null || actions == null || actions.size() < 2) return;
        for (int i = 0; i + 1 < actions.size(); i++) {
            CanonicalMutationClauseMatcher.Action action = actions.get(i);
            if (!"create".equals(action.verb())
                    && !"make".equals(action.verb())) {
                continue;
            }
            boolean laterWorkspaceOperation = false;
            for (int j = i + 1; j < actions.size(); j++) {
                if (actions.get(j).family()
                        == CanonicalMutationClauseMatcher.Family
                                .WORKSPACE_OPERATION) {
                    laterWorkspaceOperation = true;
                    break;
                }
            }
            if (!laterWorkspaceOperation) continue;

            int from = action.end();
            int to = actions.get(i + 1).start();
            for (int offset = from; offset < to; offset++) {
                RequestLexicalView.Span quoted =
                        view.exactQuotedContentStartingAt(offset, to);
                if (quoted == null) continue;
                addDiscoveredTarget(
                        discovered,
                        view.request().substring(quoted.start(), quoted.end()),
                        true,
                        true);
                RequestLexicalView.Span envelope =
                        view.exactQuotedEnvelope(quoted.start(), quoted.end());
                if (envelope != null) offset = envelope.end() - 1;
            }

            Matcher token = DISCOVERABLE_UNQUOTED_PATH.matcher(view.request());
            token.region(from, to);
            while (token.find()) {
                if (!view.isUnquotedRange(token.start(), token.end())) continue;
                String candidate = token.group();
                if (naturalWorkspaceSyntaxToken(candidate)) continue;
                addDiscoveredTarget(discovered, candidate, false, true);
            }
        }
    }

    private static boolean naturalWorkspaceSyntaxToken(String candidate) {
        if (candidate == null || candidate.isBlank()) return true;
        return switch (candidate.toLowerCase(java.util.Locale.ROOT)) {
            case "a", "an", "and", "or", "first", "then", "also", "the",
                    "new", "me", "named", "called", "as", "directory",
                    "directories", "dir", "dirs", "folder", "folders",
                    "please", "kindly", "carefully" -> true;
            default -> false;
        };
    }

    private static void addDiscoveredTarget(
            Set<String> discovered,
            String rawTarget,
            boolean exactQuoted,
            boolean workspaceBlockingTarget
    ) {
        if (rawTarget == null
                || rawTarget.isBlank()
                || rawTarget.indexOf('\r') >= 0
                || rawTarget.indexOf('\n') >= 0
                || rawTarget.indexOf('\0') >= 0) {
            return;
        }
        String normalized;
        try {
            normalized = TargetRef.normalizePath(rawTarget);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (!exactQuoted
                && BARE_ANAPHORA.contains(normalized.toLowerCase(Locale.ROOT))) {
            return;
        }
        boolean pathShaped = normalized.indexOf('.') >= 0
                || normalized.indexOf('/') >= 0
                || normalized.indexOf('\\') >= 0;
        if ((exactQuoted || pathShaped || workspaceBlockingTarget)
                && normalized.chars().anyMatch(Character::isLetterOrDigit)) {
            discovered.add(normalized);
        }
    }

    static int firstUnquotedConstraintBoundary(RequestLexicalView view) {
        return firstUnquotedConstraintBoundary(
                view,
                0,
                view == null ? 0 : view.request().length());
    }

    static int firstUnquotedConstraintBoundary(
            RequestLexicalView view,
            int from,
            int to
    ) {
        if (view == null) return -1;
        int safeFrom = Math.max(0, from);
        int safeTo = Math.min(
                view.request().length(),
                Math.max(safeFrom, to));
        int first = -1;
        for (String marker : CONSTRAINT_MARKERS) {
            int index = view.firstUnquotedIgnoreCase(
                    marker,
                    safeFrom,
                    safeTo);
            if (index >= 0 && (first < 0 || index < first)) {
                first = index;
            }
        }
        return first;
    }

    private static int boundedTailEnd(
            RequestLexicalView view,
            List<String> candidates,
            int constraintStart
    ) {
        int sentenceEnd = view.sentenceEndAfter(constraintStart);
        TargetOccurrenceIndex occurrenceIndex = TargetOccurrenceIndex.build(
                view,
                candidates,
                constraintStart,
                sentenceEnd,
                new MutableParseWork());
        return boundedTailEnd(
                view,
                candidates,
                constraintStart,
                occurrenceIndex);
    }

    private static int boundedTailEnd(
            RequestLexicalView view,
            List<String> candidates,
            int constraintStart,
            TargetOccurrenceIndex occurrenceIndex
    ) {
        int sentenceEnd = view.sentenceEndAfter(constraintStart);
        String request = view.request();
        for (int i = constraintStart; i < sentenceEnd; i++) {
            if (view.isQuoted(i)) continue;
            char value = request.charAt(i);
            if (value != '\n'
                    && (value != '\r'
                            || i + 1 >= sentenceEnd
                            || request.charAt(i + 1) != '\n')
                    && value != '\u2028'
                    && value != '\u2029') {
                continue;
            }
            int newlineEnd = i + 1;
            if (value == '\r'
                    && newlineEnd < sentenceEnd
                    && request.charAt(newlineEnd) == '\n') {
                newlineEnd++;
            }
            int previous = i - 1;
            while (previous >= constraintStart
                    && Character.isWhitespace(request.charAt(previous))) {
                previous--;
            }
            int next = newlineEnd;
            while (next < sentenceEnd && Character.isWhitespace(request.charAt(next))) {
                next++;
            }
            char prior = previous >= constraintStart ? request.charAt(previous) : '\0';
            String remainder = RequestLexicalView.foldCasePreservingLength(
                    request.substring(next, sentenceEnd));
            boolean malformedLineEnding = view.containsLoneCarriageReturn(
                    Math.max(constraintStart, previous + 1),
                    next);
            boolean continued = malformedLineEnding
                    || prior == ','
                    || prior == ';'
                    || prior == ':'
                    || prior == '\u2013'
                    || prior == '\u2014'
                    || startsWithConnector(remainder)
                    || CanonicalMutationClauseMatcher
                            .hasClosedQuotedPayloadReset(
                                    view,
                                    constraintStart,
                                    view.masked().substring(
                                            constraintStart,
                                            newlineEnd))
                    || activeRestrictiveScope(
                            view,
                            candidates,
                             constraintStart,
                             newlineEnd,
                             -1,
                             -1,
                             occurrenceIndex) != null;
            if (!continued) return i;
            i = newlineEnd - 1;
        }
        return sentenceEnd;
    }

    private static boolean startsWithConnector(String value) {
        if (value == null) return false;
        return value.startsWith("and ")
                || value.startsWith("but ")
                || value.startsWith("then ")
                || value.startsWith("also ");
    }

    private static CoordinatedMutationTailPlan.Authority authorityFor(
            boolean workspaceOperation,
            CoordinatedMutationTailPlan.Authority prefixAuthority,
            CoordinatedMutationTailPlan.Authority inheritedAuthority,
            String visibleSuffix,
            boolean disjunctiveTargets,
            MutationPostfixOwnership.Analysis postfix
    ) {
        CoordinatedMutationTailPlan.Authority authority =
                workspaceOperation
                        ? CoordinatedMutationTailPlan.Authority.WORKSPACE_OPERATION_BLOCKED
                        : CoordinatedMutationTailPlan.Authority.REQUIRED;
        if (!workspaceOperation
                || inheritedAuthority
                        != CoordinatedMutationTailPlan.Authority.FORBIDDEN) {
            authority = restrictiveAuthority(authority, inheritedAuthority);
        }
        if (!workspaceOperation
                || prefixAuthority
                        != CoordinatedMutationTailPlan.Authority.FORBIDDEN) {
            authority = restrictiveAuthority(authority, prefixAuthority);
        }
        String suffix = visibleSuffix == null ? "" : visibleSuffix;
        if (DECLARATIVE_SUFFIX_PROHIBITION.matcher(suffix).find()) {
            authority = restrictiveAuthority(
                    authority,
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN);
        } else if (DECLARATIVE_SUFFIX_NON_AUTHORITATIVE
                        .matcher(suffix).find()
                || POSTFIX_REPORTED_ATTRIBUTION.matcher(suffix).find()
                || PROPOSAL_SUFFIX.matcher(suffix).find()
                || TRAILING_ALTERNATIVE_CONNECTOR.matcher(suffix).find()
                || TARGET_LOCAL_ALTERNATIVE.matcher(suffix).find()
                || disjunctiveTargets) {
            authority = restrictiveAuthority(
                    authority,
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        } else if (DECLARATIVE_SUFFIX_OPTIONAL.matcher(suffix).find()
                || OPTIONAL_SUFFIX.matcher(suffix).find()) {
            authority = restrictiveAuthority(
                    authority,
                    CoordinatedMutationTailPlan.Authority.OPTIONAL);
        } else if (DECLARATIVE_SUFFIX_FRAME.matcher(suffix).find()) {
            authority = restrictiveAuthority(
                    authority,
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        }
        if (!workspaceOperation) {
            authority = restrictiveAuthority(authority, switch (postfix.kind()) {
                case NONE -> null;
                case OPTIONAL -> CoordinatedMutationTailPlan.Authority.OPTIONAL;
                case REPORTED, UNSUPPORTED -> CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE;
            });
        }
        return authority;
    }

    private static CoordinatedMutationTailPlan.Authority prefixAuthority(
            String visiblePrefix
    ) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        if (hasAuthoritativeProhibition(prefix, PROHIBITION)
                && !CanonicalMutationClauseMatcher
                        .reportedOwnerPrecedesProhibition(prefix)) {
            return CoordinatedMutationTailPlan.Authority.FORBIDDEN;
        }
        if (CanonicalMutationClauseMatcher
                .positiveDirectiveFrame(prefix)) {
            return null;
        }
        if (continuesOpenReportedPayload(prefix)) {
            return CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE;
        }
        return switch (CanonicalMutationClauseMatcher
                .restrictiveAuthority(prefix)) {
            case NON_AUTHORITATIVE ->
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE;
            case OPTIONAL -> CoordinatedMutationTailPlan.Authority.OPTIONAL;
            case NONE -> null;
        };
    }

    private static CoordinatedMutationTailPlan.Authority restrictiveAuthority(
            CoordinatedMutationTailPlan.Authority left,
            CoordinatedMutationTailPlan.Authority right
    ) {
        if (left == null) return right;
        if (right == null) return left;
        return restriction(right) > restriction(left) ? right : left;
    }

    private static int restriction(CoordinatedMutationTailPlan.Authority authority) {
        return switch (authority) {
            case FORBIDDEN -> 5;
            case WORKSPACE_OPERATION_BLOCKED -> 4;
            case NON_AUTHORITATIVE -> 3;
            case OPTIONAL -> 2;
            case REQUIRED -> 1;
        };
    }

    private static CoordinatedMutationTailPlan.Authority authorityForScopeKind(
            CanonicalMutationClauseMatcher.ScopeKind scopeKind
    ) {
        if (scopeKind == null) return null;
        return switch (scopeKind) {
            case NONE -> null;
            case OPTIONAL -> CoordinatedMutationTailPlan.Authority.OPTIONAL;
            case REPORTED_ADVISORY ->
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE;
            case PROHIBITED -> CoordinatedMutationTailPlan.Authority.FORBIDDEN;
        };
    }

    private static AuthorityTransition authorityTransition(
            RequestLexicalView view,
            int from,
            int to,
            CoordinatedMutationTailPlan.Authority authority
    ) {
        if (view == null || authority == null) {
            return AuthorityTransition.disconnected();
        }
        int safeFrom = Math.max(0, from);
        int safeTo = Math.min(view.request().length(), Math.max(safeFrom, to));
        String between = view.maskedLower().substring(safeFrom, safeTo);
        if (CanonicalMutationClauseMatcher.hasClosedQuotedPayloadReset(
                view,
                safeFrom,
                between)) {
            CoordinatedMutationTailPlan.Authority outerAuthority =
                    prefixAuthority(CanonicalMutationClauseMatcher
                            .closedQuotedPayloadOuterOwner(
                             view,
                             safeFrom,
                             between));
            if (outerAuthority != authority) {
                int resetStart = CanonicalMutationClauseMatcher
                        .closedQuotedPayloadResetStart(
                                view,
                                safeFrom,
                                between);
                int resetEnd = CanonicalMutationClauseMatcher
                        .closedQuotedPayloadResetEnd(
                                view,
                                safeFrom,
                                between);
                Matcher resetWord = TYPED_SCOPE_CONNECTOR.matcher(
                        between.substring(resetStart, resetEnd));
                int evidenceStart = resetWord.find()
                        ? safeFrom + resetStart + resetWord.start()
                        : safeFrom + resetEnd;
                return new AuthorityTransition(
                        TransitionEffect.RESET,
                        safeFrom + resetEnd,
                        evidenceStart);
            }
        }
        Matcher connector = TYPED_SCOPE_CONNECTOR.matcher(between);
        AuthorityTransition selected = AuthorityTransition.disconnected();
        while (connector.find()) {
            if (connectorInsidePathToken(
                    between,
                    connector.start(),
                    connector.end())) {
                continue;
            }
            String left = between.substring(0, connector.start());
            String right = between.substring(connector.end());
            String token = connector.group().toLowerCase(Locale.ROOT);
            if (!left.stripTrailing().endsWith(",")
                    || !CanonicalMutationClauseMatcher
                            .freshAuthorityPrefix(right)
                    || prefixAuthority(right) != null) {
                continue;
            }
            boolean resets = switch (authority) {
                case OPTIONAL -> "then".equals(token);
                case NON_AUTHORITATIVE -> "but".equals(token)
                        || "then".equals(token)
                                && CanonicalMutationClauseMatcher
                                        .finiteReportedLiteralComplement(left);
                case FORBIDDEN, REQUIRED, WORKSPACE_OPERATION_BLOCKED -> false;
            };
            if (resets) {
                selected = new AuthorityTransition(
                        TransitionEffect.RESET,
                        safeFrom + connector.end(),
                        safeFrom + connector.start());
            }
        }
        if (selected.effect() == TransitionEffect.RESET) {
            return selected;
        }
        boolean continues = PLAIN_THEN_OR_BUT.matcher(between).matches()
                || findStructuralConnector(
                        AUTHORITY_SCOPE_CONTINUATION.matcher(between),
                        between)
                || between.indexOf(',') >= 0
                || between.indexOf(';') >= 0
                || between.indexOf(':') >= 0
                || between.indexOf('\r') >= 0
                || between.indexOf('\n') >= 0
                || between.indexOf('\u2013') >= 0
                || between.indexOf('\u2014') >= 0
                || between.indexOf('\u2028') >= 0
                || between.indexOf('\u2029') >= 0;
        return continues
                ? AuthorityTransition.continues()
                : AuthorityTransition.disconnected();
    }

    private static AuthorityScope activeRestrictiveScope(
            RequestLexicalView view,
            List<String> candidates,
            int from,
            int to,
            int maskedTargetStart,
            int maskedTargetEnd,
            TargetOccurrenceIndex occurrenceIndex
    ) {
        if (view == null) return null;
        int safeFrom = Math.max(0, from);
        int safeTo = Math.min(
                view.request().length(),
                Math.max(safeFrom, to));
        String visible = authorityText(
                view,
                safeFrom,
                safeTo,
                maskedTargetStart,
                maskedTargetEnd);
        int quotedResetEnd =
                CanonicalMutationClauseMatcher.closedQuotedPayloadResetEnd(
                        view,
                        safeFrom,
                        visible);
        int segmentStart = quotedResetEnd >= 0
                ? quotedResetEnd
                : lastFreshScopeResetEnd(visible);
        String active = visible.substring(segmentStart);
        CoordinatedMutationTailPlan.Authority authority =
                prefixAuthority(active);
        String outerOwner = quotedResetEnd >= 0
                ? CanonicalMutationClauseMatcher
                        .closedQuotedPayloadOuterOwner(
                                view,
                                safeFrom,
                                visible)
                : "";
        CoordinatedMutationTailPlan.Authority outerAuthority =
                quotedResetEnd >= 0
                        ? prefixAuthority(outerOwner)
                        : null;
        int activeAbsoluteStart = safeFrom + segmentStart;
        boolean activeProhibitionOwnsEarlierTarget =
                targetInAuthorityPrefix(
                        view,
                         candidates,
                         activeAbsoluteStart,
                         active,
                         occurrenceIndex);
        boolean outerProhibitionOwnsEarlierTarget =
                targetInAuthorityPrefix(
                        view,
                         candidates,
                         safeFrom,
                         outerOwner,
                         occurrenceIndex);
        authority = restrictiveAuthority(authority, outerAuthority);
        boolean openProhibition =
                authority == CoordinatedMutationTailPlan.Authority.FORBIDDEN
                        && (CanonicalMutationClauseMatcher
                                                .hasOpenProhibitionScope(active)
                                        && !activeProhibitionOwnsEarlierTarget
                                || CanonicalMutationClauseMatcher
                                                .hasOpenProhibitionScope(
                                                        outerOwner)
                                        && !outerProhibitionOwnsEarlierTarget);
        if (authority
                        != CoordinatedMutationTailPlan.Authority
                                .NON_AUTHORITATIVE
                && authority
                        != CoordinatedMutationTailPlan.Authority.OPTIONAL
                && !openProhibition) {
            return null;
        }
        return new AuthorityScope(
                authority,
                outerAuthority == null
                        ? safeFrom + segmentStart
                        : safeFrom);
    }

    private static boolean targetInAuthorityPrefix(
            RequestLexicalView view,
            List<String> candidates,
            int absolutePrefixStart,
            String visiblePrefix,
            TargetOccurrenceIndex occurrenceIndex
    ) {
        if (view == null || candidates == null || candidates.isEmpty()) {
            return false;
        }
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        if (prefix.isBlank()) return false;
        int safeStart = Math.max(0, absolutePrefixStart);
        int safeEnd = Math.min(
                view.request().length(),
                safeStart + prefix.length());
        return !occurrenceIndex.between(
                candidates,
                safeStart,
                safeEnd).isEmpty();
    }

    private static int lastFreshScopeResetEnd(String visible) {
        return CanonicalMutationClauseMatcher
                .lastFreshAuthorityResetEnd(visible);
    }

    private static NaturalBatchMatch naturalBatchWorkspaceCreate(
            RequestLexicalView view,
            List<String> candidates,
            CanonicalMutationClauseMatcher.Action action,
            BoundTargets boundTargets,
            TargetOccurrenceIndex occurrenceIndex,
            int operandEnd,
            WorkspaceActionIndex workspaceActionIndex
    ) {
        if (action == null
                || view == null
                || boundTargets == null
                || boundTargets.paths().isEmpty()
                || (!"create".equals(action.verb())
                        && !"make".equals(action.verb()))) {
            return null;
        }
        WorkspaceActionSpan later = workspaceActionIndex.nextAtOrAfter(
                action.end());
        if (later == null) return null;
        List<TargetOccurrence> occurrences = occurrenceIndex.between(
                candidates,
                action.end(),
                Math.min(operandEnd, later.start()));
        List<TargetOccurrence> operands = new ArrayList<>();
        int priorEnd = action.end();
        for (TargetOccurrence occurrence : occurrences) {
            if (occurrence.envelopeStart() < priorEnd) continue;
            String connector = view.masked().substring(
                    priorEnd,
                    occurrence.envelopeStart());
            Pattern expectedConnector = operands.isEmpty()
                    ? NATURAL_BATCH_FIRST_TARGET_PREFIX
                    : NATURAL_BATCH_TARGET_CONNECTOR;
            if (!expectedConnector.matcher(connector).matches()
                    && operands.isEmpty()) {
                return null;
            }
            operands.add(occurrence);
            priorEnd = occurrence.envelopeEnd();
        }
        if (operands.size() < 2) return null;
        String operationConnector = view.masked().substring(
                priorEnd,
                later.start());
        LinkedHashMap<String, BoundTarget> targets = new LinkedHashMap<>();
        for (TargetOccurrence operand : operands) {
            targets.putIfAbsent(
                    operand.path(),
                    new BoundTarget(
                            operand.path(),
                            operand.envelopeStart(),
                            operand.envelopeEnd()));
        }
        return new NaturalBatchMatch(
                new BoundTargets(
                        List.copyOf(targets.values()),
                        operands.getLast().envelopeEnd(),
                        DISJUNCTIVE_TARGET_GAP
                                .matcher(operationConnector)
                                .find()),
                action.start(),
                later.end());
    }

    private static BoundTargets directTargets(
            RequestLexicalView view,
            List<String> candidates,
            CanonicalMutationClauseMatcher.Family family,
            int from,
            int to,
            TargetOccurrenceIndex occurrenceIndex
    ) {
        List<TargetOccurrence> occurrences = occurrenceIndex.between(
                candidates,
                from,
                to);
        if (occurrences.isEmpty()) return BoundTargets.empty(from);

        TargetOccurrence first = occurrences.getFirst();
        String prefix = view.masked().substring(from, first.envelopeStart());
        Pattern prefixPattern =
                family == CanonicalMutationClauseMatcher.Family.WORKSPACE_OPERATION
                        ? WORKSPACE_NAMED_TARGET_PREFIX
                        : DIRECT_TARGET_PREFIX;
        if (!prefixPattern.matcher(prefix).matches()) return BoundTargets.empty(from);
        if (!first.quoted()
                && beginsWithNonTargetQualifier(
                        view.maskedLower().substring(
                                first.envelopeStart(),
                                to))) {
            return BoundTargets.empty(from);
        }

        LinkedHashMap<String, BoundTarget> selected = new LinkedHashMap<>();
        selected.put(first.path(), new BoundTarget(
                first.path(), first.envelopeStart(), first.envelopeEnd()));
        int priorEnd = first.envelopeEnd();
        boolean disjunctive = false;
        for (int i = 1; i < occurrences.size(); i++) {
            TargetOccurrence occurrence = occurrences.get(i);
            if (occurrence.envelopeStart() < priorEnd) continue;
            String gap = view.masked().substring(priorEnd, occurrence.envelopeStart());
            if (!ADJACENT_TARGET_GAP.matcher(gap).matches()) break;
            disjunctive |= DISJUNCTIVE_TARGET_GAP.matcher(gap).find();
            selected.putIfAbsent(
                    occurrence.path(),
                    new BoundTarget(
                            occurrence.path(),
                            occurrence.envelopeStart(),
                            occurrence.envelopeEnd()));
            priorEnd = occurrence.envelopeEnd();
        }
        return new BoundTargets(
                List.copyOf(selected.values()), priorEnd, disjunctive);
    }

    static LocalContentActionBinding requiredLocalContentActionBinding(
            RequestLexicalView view,
            Set<String> candidateTargets,
            int from,
            int to
    ) {
        if (view == null
                || candidateTargets == null
                || candidateTargets.isEmpty()) {
            return null;
        }
        int safeFrom = Math.max(0, from);
        int safeTo = Math.min(
                view.request().length(),
                Math.max(safeFrom, to));
        List<String> candidates = normalizedCandidates(candidateTargets);
        if (candidates.isEmpty()) return null;
        TargetOccurrenceIndex occurrenceIndex = TargetOccurrenceIndex.build(
                view,
                candidates,
                safeFrom,
                safeTo,
                new MutableParseWork());
        List<CanonicalMutationClauseMatcher.Action> actions =
                lexicalMutationActions(
                        view,
                        candidates,
                        safeFrom,
                        safeTo,
                        occurrenceIndex);
        return actions.isEmpty()
                ? null
                : requiredLocalContentActionBindingAt(
                        view,
                        candidates,
                        actions,
                        actions.size() - 1,
                        safeFrom,
                        safeTo,
                        occurrenceIndex);
    }

    /**
     * Returns every required structurally bound local content action in source
     * order without allowing a later restrictive duplicate to hide an earlier
     * independent instruction.
     */
    static List<LocalContentActionBinding> requiredLocalContentActionBindings(
            RequestLexicalView view,
            Set<String> candidateTargets,
            int from,
            int to
    ) {
        return requiredLocalContentActionBindingsWithWork(
                view,
                candidateTargets,
                from,
                to).bindings();
    }

    static RequiredBindingsWorkResult requiredLocalContentActionBindingsWithWork(
            RequestLexicalView view,
            Set<String> candidateTargets,
            int from,
            int to
    ) {
        MutableParseWork work = new MutableParseWork();
        if (view == null
                || candidateTargets == null
                || candidateTargets.isEmpty()) {
            return new RequiredBindingsWorkResult(List.of(), work.snapshot());
        }
        int safeFrom = Math.max(0, from);
        int safeTo = Math.min(
                view.request().length(),
                Math.max(safeFrom, to));
        List<String> candidates = normalizedCandidates(candidateTargets);
        if (candidates.isEmpty()) {
            return new RequiredBindingsWorkResult(List.of(), work.snapshot());
        }
        TargetOccurrenceIndex occurrenceIndex = TargetOccurrenceIndex.build(
                view,
                candidates,
                safeFrom,
                safeTo,
                work);
        List<CanonicalMutationClauseMatcher.Action> actions =
                lexicalMutationActions(
                        view,
                        candidates,
                        safeFrom,
                        safeTo,
                        occurrenceIndex);
        if (actions.isEmpty()) {
            return new RequiredBindingsWorkResult(List.of(), work.snapshot());
        }

        List<LocalContentActionBinding> bindings = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            LocalContentActionBinding binding =
                    requiredLocalContentActionBindingAt(
                            view,
                            candidates,
                            actions,
                             i,
                             safeFrom,
                             safeTo,
                             occurrenceIndex);
            if (binding != null) bindings.add(binding);
        }
        return new RequiredBindingsWorkResult(
                List.copyOf(bindings),
                work.snapshot());
    }

    private static LocalContentActionBinding
            requiredLocalContentActionBindingAt(
                    RequestLexicalView view,
                    List<String> candidates,
                     List<CanonicalMutationClauseMatcher.Action> actions,
                     int actionIndex,
                     int safeFrom,
                     int safeTo,
                     TargetOccurrenceIndex occurrenceIndex
            ) {
        CanonicalMutationClauseMatcher.Action action = actions.get(actionIndex);
        if (action.family()
                        != CanonicalMutationClauseMatcher.Family
                                .CONTENT_MUTATION
                || action.nonImperativeUse()) {
            return null;
        }
        int sentenceEnd = Math.min(
                safeTo,
                view.sentenceEndAfter(action.end()));
        int nextActionStart = actionIndex + 1 < actions.size()
                ? actions.get(actionIndex + 1).start()
                : safeTo;
        int bindingLimit = Math.min(nextActionStart, sentenceEnd);
        BoundTargets outputs = directTargets(
                    view,
                    candidates,
                     action.family(),
                     action.end(),
                     bindingLimit,
                     occurrenceIndex);
        if (outputs.paths().isEmpty()) return null;
        int authorityEnd = nextActionStart < sentenceEnd
                ? boundaryBeforeNextAction(
                        view,
                         candidates,
                         outputs.lastEnvelopeEnd(),
                         nextActionStart,
                         occurrenceIndex)
                : sentenceEnd;
        if (!requiredLocalActionAuthority(
                view,
                safeFrom,
                action.start(),
                outputs.lastEnvelopeEnd(),
                authorityEnd)) {
            return null;
        }
        return new LocalContentActionBinding(
                action.start(),
                action.end(),
                outputs.paths(),
                outputs.lastEnvelopeEnd());
    }

    static boolean requiredLocalActionAuthority(
            RequestLexicalView view,
            int from,
            int actionStart,
            int boundTargetEnd,
            int to
    ) {
        if (view == null) return false;
        int safeFrom = Math.max(0, from);
        int safeActionStart = Math.min(
                view.request().length(),
                Math.max(safeFrom, actionStart));
        if (MutationPostfixOwnership.rawConstraintTainted(view, safeActionStart)) return false;
        int safeTargetEnd = Math.min(
                view.request().length(),
                Math.max(safeActionStart, boundTargetEnd));
        int safeTo = Math.min(
                view.request().length(),
                Math.max(safeTargetEnd, to));
        int sentenceStart = lastSentenceBoundaryEnd(
                view,
                safeFrom,
                safeActionStart);
        String visiblePrefix = authorityText(
                view,
                sentenceStart,
                safeActionStart,
                -1,
                -1);
        int freshScopeStart = lastFreshScopeResetEnd(visiblePrefix);
        CoordinatedMutationTailPlan.Authority prefix =
                prefixAuthority(visiblePrefix.substring(freshScopeStart));
        String visibleSuffix = view.maskedLower().substring(
                safeTargetEnd,
                safeTo);
        return authorityFor(
                        false,
                        prefix,
                        null,
                        visibleSuffix,
                        false,
                        MutationPostfixOwnership.analyzeConstraint(view, safeTargetEnd, safeTo))
                == CoordinatedMutationTailPlan.Authority.REQUIRED;
    }

    private static int lastSentenceBoundaryEnd(
            RequestLexicalView view,
            int from,
            int to
    ) {
        return view.sentenceStartBefore(from, to);
    }

    /**
     * Removes action-looking tokens only after the target grammar proves that
     * the exact same bytes are a direct or preposed bare target.
     */
    private static List<CanonicalMutationClauseMatcher.Action> mutationActions(
            RequestLexicalView view,
            List<String> candidates,
            int from,
            int to,
            TargetOccurrenceIndex occurrenceIndex
    ) {
        List<CanonicalMutationClauseMatcher.Action> retained =
                lexicalMutationActions(
                        view,
                        candidates,
                        from,
                        to,
                        occurrenceIndex);
        if (retained.isEmpty()) return retained;
        List<CanonicalMutationClauseMatcher.Action> bound =
                new ArrayList<>(retained.size());
        for (int i = 0; i < retained.size(); i++) {
            CanonicalMutationClauseMatcher.Action action = retained.get(i);
            if (action.family()
                    == CanonicalMutationClauseMatcher.Family
                            .WORKSPACE_OPERATION) {
                bound.add(action);
                continue;
            }
            int nextActionStart = i + 1 < retained.size()
                    ? retained.get(i + 1).start()
                    : to;
            if (!directTargets(
                    view,
                    candidates,
                     action.family(),
                     action.end(),
                     nextActionStart,
                     occurrenceIndex).paths().isEmpty()) {
                bound.add(action);
                continue;
            }
            int preposedStart = i == 0
                    ? from
                    : retained.get(i - 1).end();
            if (!preposedTargets(
                    view,
                     candidates,
                     preposedStart,
                     action.start(),
                     occurrenceIndex).targets().isEmpty()) {
                bound.add(action);
            }
        }
        return List.copyOf(bound);
    }

    private static List<CanonicalMutationClauseMatcher.Action>
            lexicalMutationActions(
                     RequestLexicalView view,
                     List<String> candidates,
                     int from,
                     int to,
                     TargetOccurrenceIndex occurrenceIndex
            ) {
        List<TargetOccurrence> occurrences =
                occurrenceIndex.between(candidates, from, to);
        CanonicalMutationClauseMatcher.IndexedActions indexedActions =
                CanonicalMutationClauseMatcher.indexedActions(
                        view,
                        from,
                        to,
                        occurrences.stream()
                                .map(occurrence -> new RequestLexicalView.Span(
                                        occurrence.envelopeStart(),
                                        occurrence.envelopeEnd()))
                                .toList());
        occurrenceIndex.work.add(indexedActions.work());
        List<CanonicalMutationClauseMatcher.Action> raw =
                indexedActions.actions();
        if (raw.isEmpty() || candidates == null || candidates.isEmpty()) {
            return raw;
        }
        List<CanonicalMutationClauseMatcher.Action> retained =
                new ArrayList<>(raw.size());
        PriorityQueue<ActiveOccurrence> expiring = new PriorityQueue<>(
                Comparator.comparingInt(ActiveOccurrence::envelopeEnd)
                        .thenComparingInt(ActiveOccurrence::ordinal));
        LinkedHashMap<Integer, TargetOccurrence> active =
                new LinkedHashMap<>();
        int occurrenceCursor = 0;
        for (int i = 0; i < raw.size(); i++) {
            CanonicalMutationClauseMatcher.Action candidateAction = raw.get(i);
            while (occurrenceCursor < occurrences.size()
                    && occurrences.get(occurrenceCursor).envelopeStart()
                            <= candidateAction.start()) {
                TargetOccurrence occurrence = occurrences.get(occurrenceCursor);
                active.put(occurrenceCursor, occurrence);
                expiring.add(new ActiveOccurrence(
                        occurrenceCursor,
                        occurrence.envelopeEnd()));
                occurrenceCursor++;
            }
            while (!expiring.isEmpty()
                    && expiring.peek().envelopeEnd() < candidateAction.end()) {
                active.remove(expiring.remove().ordinal());
            }
            occurrenceIndex.work.actionContainmentQueries++;
            boolean boundTargetBytes = false;
            for (TargetOccurrence occurrence : active.values()) {
                occurrenceIndex.work.actionOccurrenceVisits++;
                CanonicalMutationClauseMatcher.Action priorAction =
                        lastActionEndingAtOrBefore(
                                retained,
                                occurrence.envelopeStart());
                boolean directTarget = priorAction != null
                        && DIRECT_TARGET_PREFIX.matcher(
                                view.masked().substring(
                                        priorAction.end(),
                                        occurrence.envelopeStart()))
                                .matches();
                int nextAction = firstActionStartingAtOrAfter(
                        raw,
                        i + 1,
                        occurrence.envelopeEnd());
                boolean preposedTarget = false;
                if (!directTarget && nextAction >= 0) {
                    preposedTarget = exactPreposedTarget(
                            view,
                            from,
                            raw.get(nextAction).start(),
                            occurrence);
                }
                if (directTarget || preposedTarget) {
                    boundTargetBytes = true;
                    break;
                }
            }
            if (!boundTargetBytes) {
                retained.add(candidateAction);
            }
        }
        return List.copyOf(retained);
    }

    private static CanonicalMutationClauseMatcher.Action
            lastActionEndingAtOrBefore(
                    List<CanonicalMutationClauseMatcher.Action> actions,
                    int offset
            ) {
        int low = 0;
        int high = actions.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (actions.get(middle).end() <= offset) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low == 0 ? null : actions.get(low - 1);
    }

    private static int firstActionStartingAtOrAfter(
            List<CanonicalMutationClauseMatcher.Action> actions,
            int fromIndex,
            int offset
    ) {
        int low = Math.max(0, fromIndex);
        int high = actions.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (actions.get(middle).start() < offset) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low < actions.size() ? low : -1;
    }

    private static boolean exactPreposedTarget(
            RequestLexicalView view,
            int from,
            int actionStart,
            TargetOccurrence occurrence
    ) {
        String prefix = view.masked().substring(
                from,
                occurrence.envelopeStart());
        String suffix = view.masked().substring(
                occurrence.envelopeEnd(),
                actionStart);
        return PREPOSED_TARGET_PREFIX_TAIL.matcher(prefix).find()
                && PREPOSED_TARGET_SUFFIX.matcher(suffix).matches();
    }

    private static PreposedTargets preposedTargets(
            RequestLexicalView view,
            List<String> candidates,
            int from,
            int actionStart,
            TargetOccurrenceIndex occurrenceIndex
    ) {
        List<TargetOccurrence> occurrences = occurrenceIndex.between(
                candidates,
                from,
                actionStart);
        for (int i = occurrences.size() - 1; i >= 0; i--) {
            TargetOccurrence occurrence = occurrences.get(i);
            String prefix = view.masked().substring(from, occurrence.envelopeStart());
            String suffix = view.masked().substring(occurrence.envelopeEnd(), actionStart);
            Matcher localPrefix = PREPOSED_TARGET_PREFIX_TAIL.matcher(prefix);
            if (localPrefix.find()
                    && PREPOSED_TARGET_SUFFIX.matcher(suffix).matches()) {
                return new PreposedTargets(
                        List.of(occurrence.path()),
                        from + localPrefix.start(1),
                        occurrence.envelopeStart(),
                        occurrence.envelopeEnd());
            }
        }
        return PreposedTargets.empty(actionStart);
    }

    static List<TargetOccurrence> targetOccurrences(
            RequestLexicalView view,
            List<String> candidates,
            int from,
            int to
    ) {
        return targetOccurrenceScan(view, candidates, from, to).occurrences();
    }

    static TargetOccurrenceScan targetOccurrenceScan(
            RequestLexicalView view,
            List<String> candidates,
            int from,
            int to
    ) {
        int safeFrom = Math.max(0, from);
        int safeTo = Math.min(
                view.request().length(),
                Math.max(safeFrom, to));
        MutableParseWork work = new MutableParseWork();
        TargetOccurrenceIndex index = TargetOccurrenceIndex.build(
                view,
                candidates,
                safeFrom,
                safeTo,
                work);
        TargetOccurrenceScanStatus status = work.targetOutputLimitExceeded
                ? TargetOccurrenceScanStatus.OUTPUT_VISIT_LIMIT
                : work.targetOccurrenceLimitExceeded
                        ? TargetOccurrenceScanStatus.OCCURRENCE_LIMIT
                        : TargetOccurrenceScanStatus.COMPLETE;
        return new TargetOccurrenceScan(
                index.between(candidates, safeFrom, safeTo),
                status,
                work.targetOutputVisits,
                work.indexedOccurrenceCount);
    }

    private static void addPersistentProhibitions(
            RequestLexicalView view,
            List<String> candidates,
            int constraintStart,
            int tailEnd,
            Map<String, CoordinatedMutationTailPlan.TargetDecision> effective,
            TargetOccurrenceIndex occurrenceIndex,
            WorkspaceActionIndex workspaceActionIndex
    ) {
        int payloadStart = constraintPayloadStart(view, constraintStart);
        String tail = view.maskedLower().substring(payloadStart, tailEnd);
        occurrenceIndex.work.prohibitionFastRejectCharacters += tail.length();
        if (!PERSISTENT_PROHIBITION_SIGNAL.matcher(tail).find()) return;
        for (TargetOccurrence occurrence : occurrenceIndex.between(
                candidates,
                payloadStart,
                tailEnd)) {
            int clauseStart = boundaryAfterLastConnector(
                    view,
                    candidates,
                     payloadStart,
                     occurrence.envelopeStart(),
                     HARD_CLAUSE_CONNECTOR,
                     occurrenceIndex);
            int clauseEnd = boundaryBeforeNextConnector(
                    view,
                    candidates,
                    occurrence.envelopeEnd(),
                    tailEnd,
                    HARD_CLAUSE_CONNECTOR);
            RequestLexicalView.Span span = view.trimmedSpan(clauseStart, clauseEnd);
            String visible = view.maskedLower().substring(span.start(), span.end());
            int relativeStart = occurrence.envelopeStart() - span.start();
            int relativeEnd = occurrence.envelopeEnd() - span.start();
            if (relativeStart < 0
                    || relativeEnd < relativeStart
                    || relativeEnd > visible.length()) {
                continue;
            }
            String prefix = visible.substring(0, relativeStart);
            String suffix = visible.substring(relativeEnd);
            boolean prefixProhibition = !CanonicalMutationClauseMatcher
                            .reportedOwnerPrecedesProhibition(prefix)
                    && hasAuthoritativeProhibition(
                            prefix,
                            TARGET_PREFIX_PROHIBITION);
            boolean suffixProhibition =
                    TARGET_SUFFIX_PROHIBITION.matcher(suffix).find()
                            && !nonAuthoritativeLead(prefix);
            if (!prefixProhibition && !suffixProhibition) {
                continue;
            }
            ProhibitionOperator operator = prohibitionOperator(
                    view,
                    span,
                    relativeStart,
                    relativeEnd,
                    prefixProhibition);
            if (operator == null) continue;
            merge(effective, new CoordinatedMutationTailPlan.TargetDecision(
                    occurrence.path(),
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                    operator.action(),
                    prohibitionActionFamily(
                            workspaceActionIndex,
                            span.start(),
                            occurrence.envelopeStart()),
                    operator.startOffset(),
                    operator.endOffset(),
                    occurrence.envelopeStart(),
                    span.start(),
                    occurrence.envelopeEnd(),
                    span.end(),
                    view.substring(span)));
        }
    }

    private static ProhibitionOperator prohibitionOperator(
            RequestLexicalView view,
            RequestLexicalView.Span span,
            int relativeTargetStart,
            int relativeTargetEnd,
            boolean prefixProhibition
    ) {
        int relativeStart = prefixProhibition ? 0 : relativeTargetEnd;
        int relativeEnd = prefixProhibition
                ? relativeTargetStart
                : span.end() - span.start();
        String visible = view.maskedLower().substring(
                span.start() + relativeStart,
                span.start() + relativeEnd);
        Matcher matcher = PERSISTENT_PROHIBITION_SIGNAL.matcher(visible);
        if (!matcher.find()) return null;
        int start = span.start() + relativeStart + matcher.start();
        int end = span.start() + relativeStart + matcher.end();
        return new ProhibitionOperator(
                view.request().substring(start, end),
                start,
                end);
    }

    static boolean isCanonicalProhibitionOperator(String action) {
        return action != null
                && !action.isBlank()
                && PERSISTENT_PROHIBITION_SIGNAL.matcher(action).matches();
    }

    private record ProhibitionOperator(
            String action,
            int startOffset,
            int endOffset
    ) {}

    private static int boundaryBeforeNextAction(
            RequestLexicalView view,
            List<String> candidates,
            int currentActionEnd,
            int nextActionStart,
            TargetOccurrenceIndex occurrenceIndex
    ) {
        int safeFrom = Math.max(0, currentActionEnd);
        int safeTo = Math.min(
                view.request().length(),
                Math.max(safeFrom, nextActionStart));
        String visible = view.maskedLower().substring(safeFrom, safeTo);
        Matcher matcher = ANY_CONNECTOR.matcher(visible);
        int boundary = nextActionStart;
        while (matcher.find()) {
            if (connectorInsidePathToken(
                    visible,
                    matcher.start(),
                    matcher.end())) {
                continue;
            }
            if (connectorJoinsCollaborativeSubject(
                    view,
                    candidates,
                    safeFrom,
                    visible,
                    matcher.start(),
                    matcher.end(),
                    occurrenceIndex)) {
                continue;
            }
            boundary = safeFrom + matcher.start();
        }
        return boundary;
    }

    private static boolean hasAuthoritativeProhibition(
            String visiblePrefix,
            Pattern prohibitionPattern
    ) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        int positiveFrameStart = CanonicalMutationClauseMatcher
                .positiveNegatedFailureDirectiveStart(prefix);
        String authoritativePrefix = positiveFrameStart >= 0
                ? prefix.substring(0, positiveFrameStart)
                : prefix;
        Matcher prohibition =
                prohibitionPattern.matcher(authoritativePrefix);
        while (prohibition.find()) {
            String lead = authoritativePrefix.substring(
                    0,
                    prohibition.start());
            if (!nonAuthoritativeLead(lead)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nonAuthoritativeLead(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        if (continuesOpenReportedPayload(prefix)) return true;
        int activeStart = lastFreshScopeResetEnd(prefix);
        String active = prefix.substring(Math.min(activeStart, prefix.length()));
        return CanonicalMutationClauseMatcher.restrictiveAuthority(active)
                == CanonicalMutationClauseMatcher.RestrictiveAuthority
                        .NON_AUTHORITATIVE;
    }

    private static boolean continuesOpenReportedPayload(String visiblePrefix) {
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        Matcher connector =
                TRAILING_REPORTED_PAYLOAD_CONNECTOR.matcher(prefix);
        if (!connector.find()) return false;
        return CanonicalMutationClauseMatcher.openReportedPayloadStart(
                prefix.substring(0, connector.start())) >= 0;
    }

    private static int boundaryAfterLastConnector(
            RequestLexicalView view,
            List<String> candidates,
            int from,
            int to,
            Pattern connector,
            TargetOccurrenceIndex occurrenceIndex
    ) {
        return boundaryAfterLastConnector(
                view,
                candidates,
                from,
                to,
                connector,
                -1,
                -1,
                occurrenceIndex);
    }

    private static int boundaryAfterLastConnector(
            RequestLexicalView view,
            List<String> candidates,
            int from,
            int to,
            Pattern connector,
            int ignoredTargetStart,
            int ignoredTargetEnd,
            TargetOccurrenceIndex occurrenceIndex
    ) {
        int safeFrom = Math.max(0, from);
        int safeTo = Math.min(view.request().length(), Math.max(safeFrom, to));
        String visible = view.maskedLower().substring(safeFrom, safeTo);
        Matcher matcher = connector.matcher(visible);
        int quotedResetEnd =
                CanonicalMutationClauseMatcher.closedQuotedPayloadResetEnd(
                        view,
                        safeFrom,
                        visible);
        int boundary = quotedResetEnd < 0
                ? safeFrom
                : safeFrom + quotedResetEnd;
        boolean sawConnector = quotedResetEnd >= 0;
        while (matcher.find()) {
            if (quotedResetEnd >= 0 && matcher.end() <= quotedResetEnd) {
                continue;
            }
            int absoluteStart = safeFrom + matcher.start();
            int absoluteEnd = safeFrom + matcher.end();
            if (ignoredTargetStart >= 0
                    && ignoredTargetEnd > ignoredTargetStart
                    && absoluteStart < ignoredTargetEnd
                    && absoluteEnd > ignoredTargetStart) {
                continue;
            }
            if (connectorInsidePathToken(
                    visible,
                    matcher.start(),
                    matcher.end())) {
                continue;
            }
            if (connectorJoinsCollaborativeSubject(
                    view,
                    candidates,
                    safeFrom,
                    visible,
                    matcher.start(),
                    matcher.end(),
                    occurrenceIndex)) {
                continue;
            }
            boolean closedQuotedPayloadReset =
                    CanonicalMutationClauseMatcher.hasClosedQuotedPayloadReset(
                            view,
                            safeFrom,
                            visible.substring(0, matcher.end()));
            if (!closedQuotedPayloadReset
                    && CanonicalMutationClauseMatcher
                            .rejectsAuthorityResetWithin(
                                    visible,
                                    matcher.start(),
                                    matcher.end())) {
                continue;
            }
            if (connectorContinuesOpenReportedPayload(
                    view,
                    visible,
                    safeFrom,
                    matcher.start(),
                    matcher.end())) {
                continue;
            }
            if (semanticExclusionConnector(
                    visible,
                    matcher.start(),
                    matcher.end())) {
                continue;
            }
            String intervening = visible.substring(
                    boundary - safeFrom,
                    matcher.start());
            if (sawConnector && prefixAuthority(intervening) != null) {
                continue;
            }
            boundary = safeFrom + matcher.end();
            sawConnector = true;
        }
        return boundary;
    }

    private static boolean connectorContinuesOpenReportedPayload(
            RequestLexicalView view,
            String visible,
            int absoluteVisibleStart,
            int connectorStart,
            int connectorEnd
    ) {
        if (view == null
                || visible == null
                || connectorStart < 0
                || connectorEnd < connectorStart
                || connectorEnd > visible.length()) {
            return false;
        }
        String connector = visible.substring(connectorStart, connectorEnd);
        if (connector.matches("(?isu).*\\b(?:but|then)\\b.*")
                && !CanonicalMutationClauseMatcher
                        .rejectsAuthorityResetWithin(
                                visible,
                                connectorStart,
                                connectorEnd)) {
            return false;
        }
        int openerStart =
                CanonicalMutationClauseMatcher.openReportedPayloadStart(
                        visible.substring(0, connectorStart));
        if (openerStart < 0) return false;
        int absoluteOpenerStart = absoluteVisibleStart + openerStart;
        int absoluteConnectorStart = absoluteVisibleStart + connectorStart;
        for (int offset = absoluteOpenerStart;
                offset < absoluteConnectorStart;
                offset++) {
            if (view.isQuoted(offset)) return false;
        }
        return true;
    }

    private static boolean connectorJoinsCollaborativeSubject(
            RequestLexicalView view,
            List<String> candidates,
            int absoluteVisibleStart,
            String visible,
            int connectorStart,
            int connectorEnd,
            TargetOccurrenceIndex occurrenceIndex
    ) {
        if (view == null
                || visible == null
                || connectorStart < 0
                || connectorEnd <= connectorStart
                || connectorEnd > visible.length()) {
            return false;
        }
        Matcher conjunction = Pattern.compile("(?iu)\\band\\b")
                .matcher(visible);
        conjunction.region(connectorStart, connectorEnd);
        if (!conjunction.find()) return false;
        if (conjunction.end() < visible.length()
                && visible.charAt(conjunction.end()) == '/') {
            return false;
        }
        String rawVisible = view.masked().substring(
                absoluteVisibleStart,
                Math.min(
                        view.request().length(),
                        absoluteVisibleStart + visible.length()));
        String left = rawVisible.substring(0, conjunction.start());
        String right = rawVisible.substring(conjunction.end());
        Matcher rightSubject = COORDINATED_SUBJECT_RIGHT.matcher(right);
        if (!rightSubject.matches()) return false;
        if (!CanonicalMutationClauseMatcher.runtimeCollaborativeSubject(
                left + " and " + rightSubject.group(1))) {
            return false;
        }

        int absoluteConnectorStart =
                absoluteVisibleStart + conjunction.start();
        for (TargetOccurrence occurrence : occurrenceIndex.between(
                candidates,
                absoluteVisibleStart,
                absoluteConnectorStart)) {
            if (occurrence.envelopeEnd() > absoluteConnectorStart) continue;
            String between = view.masked().substring(
                    occurrence.envelopeEnd(),
                    absoluteConnectorStart);
            if (between.matches("[\\s,]*")) return false;
        }
        return true;
    }

    private static boolean semanticExclusionConnector(
            String visible,
            int connectorStart,
            int connectorEnd
    ) {
        if (visible == null
                || connectorStart < 0
                || connectorEnd <= connectorStart
                || connectorEnd > visible.length()
                || !visible.substring(connectorStart, connectorEnd)
                        .toLowerCase(java.util.Locale.ROOT)
                        .matches("(?s).*\\bbut\\b.*")) {
            return false;
        }
        return visible.substring(0, connectorStart)
                .matches("(?is).*\\b(?:anything|everything)\\s*$");
    }

    private static int boundaryBeforeNextConnector(
            RequestLexicalView view,
            List<String> candidates,
            int from,
            int to,
            Pattern connector
    ) {
        int safeFrom = Math.max(0, from);
        int safeTo = Math.min(view.request().length(), Math.max(safeFrom, to));
        String visible = view.maskedLower().substring(safeFrom, safeTo);
        Matcher matcher = connector.matcher(visible);
        while (matcher.find()) {
            if (connectorInsidePathToken(
                    visible,
                    matcher.start(),
                    matcher.end())) {
                continue;
            }
            return safeFrom + matcher.start();
        }
        return safeTo;
    }

    /**
     * Returns an offset-preserving authority view with quoted bytes and one
     * structurally bound preposed target envelope blanked.
     *
     * <p>Candidate lexemes that merely resemble connectors remain syntax; only
     * a target already accepted by the preposed-target grammar is masked.
     */
    private static String authorityText(
            RequestLexicalView view,
            int from,
            int to,
            int maskedTargetStart,
            int maskedTargetEnd
    ) {
        int safeFrom = Math.max(0, from);
        int safeTo = Math.min(
                view.request().length(),
                Math.max(safeFrom, to));
        StringBuilder visible = new StringBuilder(
                view.masked().substring(safeFrom, safeTo));
        if (maskedTargetStart >= 0 && maskedTargetEnd > maskedTargetStart) {
            int start = Math.max(safeFrom, maskedTargetStart) - safeFrom;
            int end = Math.min(safeTo, maskedTargetEnd) - safeFrom;
            for (int i = start; i < end; i++) {
                visible.setCharAt(i, ' ');
            }
        }
        return visible.toString();
    }

    private static boolean findStructuralConnector(
            Matcher matcher,
            String visible
    ) {
        while (matcher.find()) {
            if (!connectorInsidePathToken(
                    visible,
                    matcher.start(),
                    matcher.end())) {
                return true;
            }
        }
        return false;
    }

    private static boolean connectorInsidePathToken(
            String visible,
            int matchStart,
            int matchEnd
    ) {
        if (visible == null || visible.isEmpty()) return false;
        Matcher word = CONNECTOR_WORD.matcher(visible);
        word.region(
                Math.max(0, matchStart),
                Math.min(visible.length(), Math.max(matchStart, matchEnd)));
        while (word.find()) {
            boolean leftJoined = word.start() > 0
                    && pathJoinCharacter(visible.charAt(word.start() - 1));
            boolean rightJoined = word.end() < visible.length()
                    && pathJoinCharacter(visible.charAt(word.end()));
            if (leftJoined
                    || rightJoined
                    || connectorInsideReportedModifier(
                            visible,
                            word.start(),
                            word.end())) {
                return true;
            }
        }
        return false;
    }

    private static boolean connectorInsideReportedModifier(
            String visible,
            int connectorStart,
            int connectorEnd
    ) {
        Matcher reported = REPORTED_CONNECTOR_MODIFIER.matcher(visible);
        while (reported.find()) {
            if (reported.start() <= connectorStart
                    && reported.end() >= connectorEnd) {
                return true;
            }
        }
        return false;
    }

    private static boolean pathJoinCharacter(char value) {
        return value == '_'
                || value == '-'
                || value == '.'
                || value == '/'
                || value == '\\';
    }

    private static boolean beginsWithNonTargetQualifier(String suffix) {
        if (suffix == null || suffix.isBlank()) return false;
        return qualifierConsumesClause(OPTIONAL_SUFFIX, suffix)
                || qualifierConsumesClause(PROPOSAL_SUFFIX, suffix)
                || qualifierConsumesClause(TARGET_LOCAL_ALTERNATIVE, suffix);
    }

    private static boolean qualifierConsumesClause(
            Pattern qualifier,
            String suffix
    ) {
        Matcher matcher = qualifier.matcher(suffix);
        if (!matcher.find()) return false;
        String remainder = suffix.substring(matcher.end());
        return remainder.matches("[\\s.!?]*")
                || remainder.matches(
                        "(?i)\\s+(?:needed|necessary|required|appropriate|"
                                + "applicable|possible|feasible)\\b[\\s.!?]*");
    }

    private static void merge(
            Map<String, CoordinatedMutationTailPlan.TargetDecision> effective,
            CoordinatedMutationTailPlan.TargetDecision decision
    ) {
        effective.merge(
                decision.path(),
                decision,
                CoordinatedMutationTailParser::stronger);
    }

    private static CoordinatedMutationTailPlan.ActionFamily
            prohibitionActionFamily(
                    WorkspaceActionIndex workspaceActionIndex,
                    int from,
                    int targetStart
            ) {
        return workspaceActionIndex.contains(from, targetStart)
                ? CoordinatedMutationTailPlan.ActionFamily.WORKSPACE_OPERATION
                : CoordinatedMutationTailPlan.ActionFamily.CONTENT_MUTATION;
    }

    private static CoordinatedMutationTailPlan.TargetDecision stronger(
            CoordinatedMutationTailPlan.TargetDecision left,
            CoordinatedMutationTailPlan.TargetDecision right
    ) {
        /*
         * A positive workspace-operation mention is retained independently in
         * workspaceOperationObservations and remains blocked until T1205. It
         * must not erase a separately authorized content mutation merely
         * because both clauses name the same path. Explicit prohibitions keep
         * their ordinary target-specific precedence below.
         */
        if (left.authority()
                        == CoordinatedMutationTailPlan.Authority
                                .WORKSPACE_OPERATION_BLOCKED
                && right.actionFamily()
                        == CoordinatedMutationTailPlan.ActionFamily
                                .CONTENT_MUTATION) {
            return right;
        }
        if (right.authority()
                        == CoordinatedMutationTailPlan.Authority
                                .WORKSPACE_OPERATION_BLOCKED
                && left.actionFamily()
                        == CoordinatedMutationTailPlan.ActionFamily
                                .CONTENT_MUTATION) {
            return left;
        }
        int leftPrecedence = precedence(left.authority());
        int rightPrecedence = precedence(right.authority());
        if (rightPrecedence != leftPrecedence) {
            return rightPrecedence > leftPrecedence ? right : left;
        }
        return right.actionFamily()
                == CoordinatedMutationTailPlan.ActionFamily.WORKSPACE_OPERATION
                ? right
                : left;
    }

    private static int precedence(CoordinatedMutationTailPlan.Authority authority) {
        return switch (authority) {
            case FORBIDDEN -> 5;
            case WORKSPACE_OPERATION_BLOCKED -> 4;
            case REQUIRED -> 3;
            case OPTIONAL -> 2;
            case NON_AUTHORITATIVE -> 1;
        };
    }

    private static boolean targetBoundary(
            RequestLexicalView view,
            String value,
            int start,
            int end
    ) {
        boolean left = start == 0 || !targetCharacter(value.charAt(start - 1));
        boolean right = end == value.length()
                || !targetCharacter(value.charAt(end))
                || value.charAt(end) == '.'
                && (end + 1 == value.length()
                || Character.isWhitespace(value.charAt(end + 1))
                || view.containsProviderRemovedControl(end + 1, end + 2)
                || ")]}`".indexOf(value.charAt(end + 1)) >= 0);
        return left && right;
    }

    private static boolean targetCharacter(char value) {
        return Character.isLetterOrDigit(value)
                || value == '_'
                || value == '-'
                || value == '.'
                || value == '/'
                || value == '\\';
    }

    private static List<String> normalizedCandidates(Set<String> targets) {
        if (targets == null || targets.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String target : targets) {
            try {
                out.add(TargetRef.normalizePath(target));
            } catch (IllegalArgumentException ignored) {
                // Invalid legacy candidates cannot acquire authority.
            }
        }
        return out.stream()
                .sorted(Comparator
                        .comparingInt(String::length)
                        .reversed()
                        .thenComparing(String::compareTo))
                .toList();
    }

    record ParseWorkResult(
            CoordinatedMutationTailPlan plan,
            ParseWork work
    ) {}

    record RequiredBindingsWorkResult(
            List<LocalContentActionBinding> bindings,
            ParseWork work
    ) {
        RequiredBindingsWorkResult {
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
        }
    }

    /**
     * Deterministic work counters for the target-occurrence index boundary.
     * These counters describe parser operations, not elapsed time.
     */
    record ParseWork(
            int targetIndexBuilds,
            int normalizedRequestCopies,
            int scanStart,
            int scanEnd,
            int candidateCount,
            int indexedOccurrenceCount,
            long targetPatternCharacters,
            long targetInputCharacters,
            long targetAutomatonTransitions,
            long targetFailureTransitions,
            long targetOutputVisits,
            boolean targetOutputLimitExceeded,
            boolean targetOccurrenceLimitExceeded,
            long targetRangeQueries,
            long indexedOccurrenceVisits,
            long fullRangeQueries,
            long actionContainmentQueries,
            long actionOccurrenceVisits,
            long prohibitionFastRejectCharacters,
            int authorityActionScans,
            long authorityActionInputCharacters,
            long authorityNominalPrefixChecks,
            long authorityNominalPrefixCharacters,
            int authorityConnectorScans,
            long authorityConnectorInputCharacters,
            long authorityTransitions,
            long authorityClauseCharacters,
            long authorityFallbackClassifications,
            long authorityFallbackCharacters,
            long authorityFallbackFailClosedClassifications,
            int authorityTargetEnvelopeIndexBuilds,
            int authorityTargetEnvelopeIndexEntries,
            long authorityTargetEnvelopeBuildCharacters,
            long authorityTargetEnvelopeContainmentQueries,
            long authorityTargetEnvelopeRangeQueries,
            long authorityTargetEnvelopeVisits,
            long authorityTargetEnvelopeCoverageChecks
    ) {}

    private static final class MutableParseWork {
        private int targetIndexBuilds;
        private int normalizedRequestCopies;
        private int scanStart;
        private int scanEnd;
        private int candidateCount;
        private int indexedOccurrenceCount;
        private long targetPatternCharacters;
        private long targetInputCharacters;
        private long targetAutomatonTransitions;
        private long targetFailureTransitions;
        private long targetOutputVisits;
        private boolean targetOutputLimitExceeded;
        private boolean targetOccurrenceLimitExceeded;
        private long targetRangeQueries;
        private long indexedOccurrenceVisits;
        private long fullRangeQueries;
        private long actionContainmentQueries;
        private long actionOccurrenceVisits;
        private long prohibitionFastRejectCharacters;
        private int authorityActionScans;
        private long authorityActionInputCharacters;
        private long authorityNominalPrefixChecks;
        private long authorityNominalPrefixCharacters;
        private int authorityConnectorScans;
        private long authorityConnectorInputCharacters;
        private long authorityTransitions;
        private long authorityClauseCharacters;
        private long authorityFallbackClassifications;
        private long authorityFallbackCharacters;
        private long authorityFallbackFailClosedClassifications;
        private int authorityTargetEnvelopeIndexBuilds;
        private int authorityTargetEnvelopeIndexEntries;
        private long authorityTargetEnvelopeBuildCharacters;
        private long authorityTargetEnvelopeContainmentQueries;
        private long authorityTargetEnvelopeRangeQueries;
        private long authorityTargetEnvelopeVisits;
        private long authorityTargetEnvelopeCoverageChecks;

        private void add(CanonicalMutationClauseMatcher.AuthorityWork authority) {
            if (authority == null) return;
            authorityActionScans += authority.actionScans();
            authorityActionInputCharacters += authority.actionInputCharacters();
            authorityNominalPrefixChecks += authority.nominalPrefixChecks();
            authorityNominalPrefixCharacters +=
                    authority.nominalPrefixCharacters();
            authorityConnectorScans += authority.connectorScans();
            authorityConnectorInputCharacters +=
                    authority.connectorInputCharacters();
            authorityTransitions += authority.authorityTransitions();
            authorityClauseCharacters += authority.authorityClauseCharacters();
            authorityFallbackClassifications +=
                    authority.fallbackClassifications();
            authorityFallbackCharacters += authority.fallbackCharacters();
            authorityFallbackFailClosedClassifications +=
                    authority.fallbackFailClosedClassifications();
            authorityTargetEnvelopeIndexBuilds +=
                    authority.targetEnvelopeIndexBuilds();
            authorityTargetEnvelopeIndexEntries +=
                    authority.targetEnvelopeIndexEntries();
            authorityTargetEnvelopeBuildCharacters +=
                    authority.targetEnvelopeBuildCharacters();
            authorityTargetEnvelopeContainmentQueries +=
                    authority.targetEnvelopeContainmentQueries();
            authorityTargetEnvelopeRangeQueries +=
                    authority.targetEnvelopeRangeQueries();
            authorityTargetEnvelopeVisits +=
                    authority.targetEnvelopeVisits();
            authorityTargetEnvelopeCoverageChecks +=
                    authority.targetEnvelopeCoverageChecks();
        }

        private ParseWork snapshot() {
            return new ParseWork(
                    targetIndexBuilds,
                    normalizedRequestCopies,
                    scanStart,
                    scanEnd,
                    candidateCount,
                    indexedOccurrenceCount,
                    targetPatternCharacters,
                    targetInputCharacters,
                    targetAutomatonTransitions,
                    targetFailureTransitions,
                    targetOutputVisits,
                    targetOutputLimitExceeded,
                    targetOccurrenceLimitExceeded,
                    targetRangeQueries,
                    indexedOccurrenceVisits,
                    fullRangeQueries,
                    actionContainmentQueries,
                    actionOccurrenceVisits,
                    prohibitionFastRejectCharacters,
                    authorityActionScans,
                    authorityActionInputCharacters,
                    authorityNominalPrefixChecks,
                    authorityNominalPrefixCharacters,
                    authorityConnectorScans,
                    authorityConnectorInputCharacters,
                    authorityTransitions,
                    authorityClauseCharacters,
                    authorityFallbackClassifications,
                    authorityFallbackCharacters,
                    authorityFallbackFailClosedClassifications,
                    authorityTargetEnvelopeIndexBuilds,
                    authorityTargetEnvelopeIndexEntries,
                    authorityTargetEnvelopeBuildCharacters,
                    authorityTargetEnvelopeContainmentQueries,
                    authorityTargetEnvelopeRangeQueries,
                    authorityTargetEnvelopeVisits,
                    authorityTargetEnvelopeCoverageChecks);
        }
    }

    private static final class TargetOccurrenceIndex {
        private static final Comparator<IndexedTargetOccurrence>
                SOURCE_ORDER = Comparator
                        .comparingInt((IndexedTargetOccurrence indexed) ->
                                indexed.occurrence().envelopeStart())
                        .thenComparing(
                                (IndexedTargetOccurrence indexed) ->
                                        indexed.occurrence().envelopeEnd()
                                                - indexed.occurrence()
                                                        .envelopeStart(),
                                Comparator.reverseOrder())
                        .thenComparingInt(IndexedTargetOccurrence::ordinal);

        private final int requestLength;
        private final int scanStart;
        private final int scanEnd;
        private final List<String> indexedCandidates;
        private final List<IndexedTargetOccurrence> byMatchStart;
        private final MutableParseWork work;

        private TargetOccurrenceIndex(
                int requestLength,
                int scanStart,
                int scanEnd,
                List<String> indexedCandidates,
                List<IndexedTargetOccurrence> byMatchStart,
                MutableParseWork work
        ) {
            this.requestLength = requestLength;
            this.scanStart = scanStart;
            this.scanEnd = scanEnd;
            this.indexedCandidates = List.copyOf(indexedCandidates);
            this.byMatchStart = List.copyOf(byMatchStart);
            this.work = work;
        }

        private static TargetOccurrenceIndex build(
                RequestLexicalView view,
                List<String> candidates,
                int from,
                int to,
                MutableParseWork work
        ) {
            String request = view.request().replace('\\', '/');
            List<String> safeCandidates = candidates == null
                    ? List.of()
                    : List.copyOf(candidates);
            int safeFrom = Math.max(0, from);
            int safeTo = Math.min(
                    request.length(),
                    Math.max(safeFrom, to));
            work.targetIndexBuilds++;
            work.normalizedRequestCopies++;
            work.scanStart = safeFrom;
            work.scanEnd = safeTo;
            work.candidateCount = safeCandidates.size();

            List<AutomatonNode> automaton = new ArrayList<>();
            automaton.add(new AutomatonNode());
            for (int ordinal = 0; ordinal < safeCandidates.size(); ordinal++) {
                String target = safeCandidates.get(ordinal);
                String lexicalTarget = target.replace('\\', '/');
                work.targetPatternCharacters += lexicalTarget.length();
                int state = 0;
                for (int index = 0; index < lexicalTarget.length(); index++) {
                    char value = lexicalTarget.charAt(index);
                    Integer next = automaton.get(state).transitions.get(value);
                    if (next == null) {
                        next = automaton.size();
                        automaton.get(state).transitions.put(value, next);
                        automaton.add(new AutomatonNode());
                    }
                    state = next;
                }
                automaton.get(state).outputs.add(new TargetPattern(
                        target,
                        lexicalTarget.length(),
                        ordinal));
            }
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            for (int child : automaton.getFirst().transitions.values()) {
                automaton.get(child).failure = 0;
                pending.addLast(child);
            }
            while (!pending.isEmpty()) {
                int state = pending.removeFirst();
                for (Map.Entry<Character, Integer> transition
                        : automaton.get(state).transitions.entrySet()) {
                    char value = transition.getKey();
                    int child = transition.getValue();
                    int failure = automaton.get(state).failure;
                    while (failure != 0
                            && !automaton.get(failure)
                                    .transitions.containsKey(value)) {
                        failure = automaton.get(failure).failure;
                    }
                    Integer fallback = automaton.get(failure)
                            .transitions.get(value);
                    automaton.get(child).failure = fallback == null
                            || fallback == child
                            ? 0
                            : fallback;
                    int failureState = automaton.get(child).failure;
                    automaton.get(child).outputLink = automaton
                                    .get(failureState).outputs.isEmpty()
                            ? automaton.get(failureState).outputLink
                            : failureState;
                    pending.addLast(child);
                }
            }

            List<IndexedTargetOccurrence> indexed = new ArrayList<>();
            int state = 0;
            boolean limitExceeded = false;
            scan:
            for (int index = safeFrom; index < safeTo; index++) {
                char value = request.charAt(index);
                work.targetInputCharacters++;
                while (state != 0
                        && !automaton.get(state)
                                .transitions.containsKey(value)) {
                    state = automaton.get(state).failure;
                    work.targetFailureTransitions++;
                }
                Integer next = automaton.get(state).transitions.get(value);
                state = next == null ? 0 : next;
                work.targetAutomatonTransitions++;
                int outputState = state;
                while (outputState >= 0) {
                    for (TargetPattern pattern
                            : automaton.get(outputState).outputs) {
                        work.targetOutputVisits++;
                        if (work.targetOutputVisits
                                > MAX_TARGET_OUTPUT_VISITS) {
                            work.targetOutputLimitExceeded = true;
                            limitExceeded = true;
                            break scan;
                        }
                        int targetEnd = index + 1;
                        int offset = targetEnd - pattern.length();
                        if (offset < safeFrom) continue;
                        int lexicalStart = offset;
                        while (lexicalStart >= 2
                                && request.charAt(lexicalStart - 2) == '.'
                                && request.charAt(lexicalStart - 1) == '/') {
                            lexicalStart -= 2;
                        }
                        if (!targetBoundary(
                                view,
                                request,
                                lexicalStart,
                                targetEnd)) {
                            continue;
                        }
                        RequestLexicalView.Span quotedEnvelope =
                                view.exactQuotedEnvelope(
                                        lexicalStart,
                                        targetEnd);
                        if (quotedEnvelope == null
                                && !view.isUnquotedRange(
                                        lexicalStart,
                                        targetEnd)) {
                            continue;
                        }
                        if (indexed.size()
                                >= MAX_INDEXED_TARGET_OCCURRENCES) {
                            work.targetOccurrenceLimitExceeded = true;
                            limitExceeded = true;
                            break scan;
                        }
                        TargetOccurrence occurrence = new TargetOccurrence(
                                pattern.path(),
                                quotedEnvelope == null
                                        ? lexicalStart
                                        : quotedEnvelope.start(),
                                quotedEnvelope == null
                                        ? targetEnd
                                        : quotedEnvelope.end(),
                                quotedEnvelope != null);
                        indexed.add(new IndexedTargetOccurrence(
                                occurrence,
                                offset,
                                targetEnd,
                                pattern.ordinal()));
                    }
                    outputState = automaton.get(outputState).outputLink;
                }
            }
            if (limitExceeded) indexed.clear();
            indexed.sort(Comparator
                    .comparingInt(IndexedTargetOccurrence::matchStart)
                    .thenComparingInt(IndexedTargetOccurrence::ordinal));
            work.indexedOccurrenceCount = indexed.size();
            return new TargetOccurrenceIndex(
                    request.length(),
                    safeFrom,
                    safeTo,
                    safeCandidates,
                    indexed,
                    work);
        }

        private List<TargetOccurrence> between(
                List<String> candidates,
                int from,
                int to
        ) {
            int safeFrom = Math.max(scanStart, Math.max(0, from));
            int safeTo = Math.min(
                    scanEnd,
                    Math.min(
                            requestLength,
                            Math.max(safeFrom, to)));
            work.targetRangeQueries++;
            if (safeFrom == scanStart && safeTo == scanEnd) {
                work.fullRangeQueries++;
            }
            if (safeFrom >= safeTo || byMatchStart.isEmpty()) {
                return List.of();
            }
            List<String> requestedCandidates = candidates == null
                    ? List.of()
                    : candidates;
            boolean allCandidates = requestedCandidates == indexedCandidates
                    || requestedCandidates.equals(indexedCandidates);
            int cursor = firstAtOrAfter(safeFrom);
            List<IndexedTargetOccurrence> selected = new ArrayList<>();
            while (cursor < byMatchStart.size()) {
                IndexedTargetOccurrence indexed = byMatchStart.get(cursor++);
                if (indexed.matchStart() >= safeTo) break;
                work.indexedOccurrenceVisits++;
                if (indexed.matchEnd() > safeTo
                        || !allCandidates
                                && !requestedCandidates.contains(
                                        indexed.occurrence().path())) {
                    continue;
                }
                selected.add(indexed);
            }
            selected.sort(SOURCE_ORDER);
            return selected.stream()
                    .map(IndexedTargetOccurrence::occurrence)
                    .toList();
        }

        private int firstAtOrAfter(int offset) {
            int low = 0;
            int high = byMatchStart.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (byMatchStart.get(middle).matchStart() < offset) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }
    }

    private static final class AutomatonNode {
        private final Map<Character, Integer> transitions =
                new LinkedHashMap<>();
        private final List<TargetPattern> outputs = new ArrayList<>();
        private int failure;
        private int outputLink = -1;
    }

    private record TargetPattern(
            String path,
            int length,
            int ordinal
    ) {}

    private record IndexedTargetOccurrence(
            TargetOccurrence occurrence,
            int matchStart,
            int matchEnd,
            int ordinal
    ) {}

    private record ActiveOccurrence(int ordinal, int envelopeEnd) {}

    private record WorkspaceActionIndex(int[] starts, int[] ends) {
        private static WorkspaceActionIndex build(
                List<CanonicalMutationClauseMatcher.Action> actions
        ) {
            if (actions == null || actions.isEmpty()) {
                return new WorkspaceActionIndex(new int[0], new int[0]);
            }
            List<CanonicalMutationClauseMatcher.Action> workspace = actions.stream()
                    .filter(action -> action.family()
                            == CanonicalMutationClauseMatcher.Family
                                    .WORKSPACE_OPERATION)
                    .toList();
            return new WorkspaceActionIndex(
                    workspace.stream()
                            .mapToInt(CanonicalMutationClauseMatcher.Action::start)
                            .toArray(),
                    workspace.stream()
                            .mapToInt(CanonicalMutationClauseMatcher.Action::end)
                            .toArray());
        }

        private boolean contains(int from, int to) {
            int low = 0;
            int high = starts.length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (starts[middle] < from) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low < starts.length && starts[low] < to;
        }

        private WorkspaceActionSpan nextAtOrAfter(int from) {
            int low = 0;
            int high = starts.length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (starts[middle] < from) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low < starts.length
                    ? new WorkspaceActionSpan(starts[low], ends[low])
                    : null;
        }
    }

    private record WorkspaceActionSpan(int start, int end) {}

    record TargetOccurrence(
            String path,
            int envelopeStart,
            int envelopeEnd,
            boolean quoted
    ) {}

    enum TargetOccurrenceScanStatus {
        COMPLETE,
        OUTPUT_VISIT_LIMIT,
        OCCURRENCE_LIMIT
    }

    record TargetOccurrenceScan(
            List<TargetOccurrence> occurrences,
            TargetOccurrenceScanStatus status,
            long outputVisits,
            long indexedOccurrenceCount
    ) {
        TargetOccurrenceScan {
            occurrences = occurrences == null
                    ? List.of()
                    : List.copyOf(occurrences);
            status = status == null
                    ? TargetOccurrenceScanStatus.COMPLETE
                    : status;
            if (outputVisits < 0 || indexedOccurrenceCount < 0) {
                throw new IllegalArgumentException(
                        "target occurrence scan counters must be non-negative");
            }
            if (status != TargetOccurrenceScanStatus.COMPLETE
                    && (!occurrences.isEmpty()
                            || indexedOccurrenceCount != 0)) {
                throw new IllegalArgumentException(
                        "limited target occurrence scans cannot retain partial results");
            }
        }

        boolean limitExceeded() {
            return status != TargetOccurrenceScanStatus.COMPLETE;
        }
    }

    record LocalContentActionBinding(
            int actionStart,
            int actionEnd,
            List<String> outputPaths,
            int outputEnd
    ) {
        LocalContentActionBinding {
            if (actionStart < 0
                    || actionEnd <= actionStart
                    || outputPaths == null
                    || outputPaths.isEmpty()
                    || outputEnd < actionEnd) {
                throw new IllegalArgumentException(
                        "invalid local content-action binding");
            }
            outputPaths = List.copyOf(outputPaths);
        }
    }

    private record BoundTarget(
            String path,
            int envelopeStart,
            int envelopeEnd
    ) {
        private BoundTarget {
            path = TargetRef.normalizePath(path);
            if (envelopeStart < 0 || envelopeEnd <= envelopeStart) {
                throw new IllegalArgumentException("invalid bound target span");
            }
        }
    }

    private record BoundTargets(
            List<BoundTarget> targets,
            int lastEnvelopeEnd,
            boolean disjunctive
    ) {
        private BoundTargets {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }

        private List<String> paths() {
            return targets.stream().map(BoundTarget::path).toList();
        }

        private static BoundTargets empty(int lastEnvelopeEnd) {
            return new BoundTargets(List.of(), lastEnvelopeEnd, false);
        }
    }

    private enum TransitionEffect {
        DISCONNECTED,
        CONTINUE,
        RESET
    }

    private record AuthorityTransition(
            TransitionEffect effect,
            int grammarStart,
            int evidenceStart
    ) {
        private AuthorityTransition {
            if (effect == null
                    || effect == TransitionEffect.RESET
                            && (grammarStart < 0
                                    || evidenceStart < 0
                                    || evidenceStart > grammarStart)) {
                throw new IllegalArgumentException(
                        "invalid authority transition");
            }
        }

        private static AuthorityTransition disconnected() {
            return new AuthorityTransition(
                    TransitionEffect.DISCONNECTED,
                    -1,
                    -1);
        }

        private static AuthorityTransition continues() {
            return new AuthorityTransition(
                    TransitionEffect.CONTINUE,
                    -1,
                    -1);
        }
    }

    private record AuthorityScope(
            CoordinatedMutationTailPlan.Authority authority,
            int startOffset
    ) {
        private AuthorityScope {
            if (authority == null || startOffset < 0) {
                throw new IllegalArgumentException("invalid inherited authority scope");
            }
        }
    }

    private record PreposedTargets(
            List<String> targets,
            int sourceStart,
            int envelopeStart,
            int envelopeEnd
    ) {
        private PreposedTargets {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }

        private static PreposedTargets empty(int sourceStart) {
            return new PreposedTargets(List.of(), sourceStart, -1, -1);
        }
    }

    private record NaturalBatchMatch(
            BoundTargets boundTargets,
            int startOffset,
            int endOffset
    ) {
        private NaturalBatchMatch {
            if (boundTargets == null
                    || boundTargets.paths().isEmpty()
                    || startOffset < 0
                    || endOffset <= startOffset) {
                throw new IllegalArgumentException(
                        "invalid natural workspace batch match");
            }
        }
    }
}
