package dev.talos.runtime.intent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finite parser for top-level mutation clauses following one independently
 * bound mutation clause.
 *
 * <p>This parser owns only declared sequential boundaries. It neither parses
 * T1196 constraint-tail bytes nor grants workspace-operation authority.
 */
public final class SequentialMutationClauseParser {

    private static final Pattern DIRECT_TARGET_PREFIX = Pattern.compile(
            "(?iu)^[\\s:,-]*(?:(?:exactly|only)\\s+)*"
                    + "(?:(?:the|a|an|both|current|existing|local|new)\\s+)*"
                    + "(?:(?:path|file|files|directory|directories|dir|dirs|"
                    + "folder|folders)\\s+)?"
                    + "(?:(?:named|called|as)\\s+)?$");
    private static final Pattern ADJACENT_TARGET_GAP = Pattern.compile(
            "(?iu)^\\s*(?:(?:,\\s*(?:(?:and\\s*/\\s*or|and|or|&)\\s+)?"
                    + "|(?:and\\s*/\\s*or|and|or|&)\\s+)"
                    + "(?:also\\s+)?)$");
    private static final Pattern ADVISORY_CLAUSE_PREFIX = Pattern.compile(
            "(?iu)^\\s*(?:please\\s+)?consider\\b");
    private static final Pattern DISJUNCTIVE_TARGET_GAP = Pattern.compile(
            "(?iu)\\b(?:or|and\\s*/\\s*or)\\b|(?:^|\\s)/\\s*or\\b");
    private static final Pattern DISJUNCTIVE_CLAUSE_SIGNAL = Pattern.compile(
            "(?iu)\\b(?:or|either|alternatively|versus)\\b"
                    + "|\\band\\s*/\\s*or\\b|\\s/\\s"
                    + "|\\bbut\\s+(?:do\\s+not|don['\\u2019]?t|dont|never|"
                    + "avoid|leave)\\b");
    private static final Pattern POSITIVE_TERMINAL_SUFFIX = Pattern.compile(
            "(?iu)^\\s*(?:(?:too|also)\\s*)?[.!?\\u2026]*\\s*$");
    private static final Pattern SEQUENTIAL_ACTION_LEAD = Pattern.compile(
            "(?iu)^\\s*(?:(?:and|also|but|next|please|then)\\s+)*$");
    private static final Pattern DIRECTORY_SUFFIX = Pattern.compile(
            "(?iu)^\\s*(?:(?:as\\s+)?(?:an?\\s+)?"
                    + "(?:directories|directory|dirs|dir|folders|folder))\\b");
    private static final Pattern DIRECTORY_PREFIX_SIGNAL = Pattern.compile(
            "(?iu)\\b(?:directories|directory|dirs|dir|folders|folder)\\b");
    private static final Pattern PRONOMINAL_PROHIBITION = Pattern.compile(
            "(?iu)^[\\s;,.!?\\u2026]*(?:(?:but|and|then)\\s+)?(?:"
                    + "(?:do\\s+not|don['\\u2019]?t|dont|never)\\s+"
                    + "(?:change|edit|update|modify|rewrite|replace|alter|touch)"
                    + "\\s+(?:(?:it|that|this)(?:\\s+file)?|them|those\\s+files)"
                    + "|leave\\s+(?:(?:it|that|this)(?:\\s+file)?|them|those\\s+files)"
                    + "\\s+unchanged)"
                    + "\\b[\\s.!?\\u2026]*");
    private static final Set<String> BARE_ANAPHORA = Set.of(
            "it", "that", "this", "them", "those");
    private static final Set<String> GENERIC_RESOURCE_CLASSES = Set.of(
            "css", "html", "javascript", "js", "typescript");
    private static final Set<String> CONTROL_RETAINABLE_PRIMARY_VERBS = Set.of(
            "edit",
            "update",
            "rewrite",
            "refactor",
            "modify",
            "fix",
            "adjust",
            "tweak",
            "restyle",
            "redesign",
            "polish",
            "repair",
            "touch",
            "overwrite");

    private SequentialMutationClauseParser() {}

    public static SequentialMutationClausePlan parse(
            String canonicalRequest,
            Set<String> candidateTargets
    ) {
        return parse(canonicalRequest, candidateTargets, List.of());
    }

    /** Excludes the complete source range owned by a present T1196 plan. */
    public static SequentialMutationClausePlan parse(
            String canonicalRequest,
            Set<String> candidateTargets,
            CoordinatedMutationTailPlan excludedTailPlan
    ) {
        List<SequentialMutationClausePlan.SourceSpan> excluded =
                excludedTailPlan != null
                        && excludedTailPlan.present()
                        && excludedTailPlan.tailEnd()
                                > excludedTailPlan.constraintStart()
                ? List.of(new SequentialMutationClausePlan.SourceSpan(
                        excludedTailPlan.constraintStart(),
                        excludedTailPlan.tailEnd()))
                : List.of();
        return parse(canonicalRequest, candidateTargets, excluded);
    }

    /**
     * Parses exact canonical bytes while treating supplied T1196 ranges as
     * opaque. Excluded ranges can never contribute an anchor, decision, or
     * owned region.
     */
    public static SequentialMutationClausePlan parse(
            String canonicalRequest,
            Set<String> candidateTargets,
            List<SequentialMutationClausePlan.SourceSpan> excludedSpans
    ) {
        return parseInternal(
                canonicalRequest,
                candidateTargets,
                excludedSpans,
                new MutableParseWork()).plan();
    }

    static MeasuredPlan parseMeasured(
            String canonicalRequest,
            Set<String> candidateTargets
    ) {
        return parseMeasured(
                canonicalRequest,
                candidateTargets,
                List.of());
    }

    static MeasuredPlan parseMeasured(
            String canonicalRequest,
            Set<String> candidateTargets,
            List<SequentialMutationClausePlan.SourceSpan> excludedSpans
    ) {
        return parseInternal(
                canonicalRequest,
                candidateTargets,
                excludedSpans,
                new MutableParseWork());
    }

    private static MeasuredPlan parseInternal(
            String canonicalRequest,
            Set<String> candidateTargets,
            List<SequentialMutationClausePlan.SourceSpan> excludedSpans,
            MutableParseWork work
    ) {
        String request = canonicalRequest == null ? "" : canonicalRequest;
        work.inputCharacters = request.length();
        List<SequentialMutationClausePlan.SourceSpan> excluded =
                normalizeExcluded(excludedSpans, request.length());
        List<String> candidates = normalizedCandidates(candidateTargets);
        if (request.isBlank() || candidates.isEmpty()) {
            return measured(
                    SequentialMutationClausePlan.empty(request, excluded),
                    work);
        }

        RequestLexicalView view = RequestLexicalView.of(request);
        CoordinatedMutationTailParser.TargetOccurrenceScan occurrenceScan =
                CoordinatedMutationTailParser.targetOccurrenceScan(
                        view,
                        candidates,
                        0,
                        request.length());
        if (occurrenceScan.limitExceeded()) {
            return measured(
                    targetIndexLimitQuarantine(
                            request,
                            excluded,
                            occurrenceScan.status()),
                    work);
        }
        RequestLexicalView authorityView = excluded.isEmpty()
                ? view
                : RequestLexicalView.of(
                        authorityRequestOutsideExcludedSpans(
                                request,
                                excluded));
        SequenceBoundaryIndex boundaries = SequenceBoundaryIndex.of(
                view,
                excluded,
                work);
        EvidenceBudget evidence = new EvidenceBudget(
                request,
                excluded.size(),
                work);
        List<CoordinatedMutationTailParser.TargetOccurrence> occurrences =
                collapseTerminalPunctuationAlternatives(
                        occurrenceScan.occurrences()).stream()
                        .filter(occurrence -> !intersectsExcluded(
                                excluded,
                                occurrence.envelopeStart(),
                        occurrence.envelopeEnd()))
                        .toList();
        work.targetOccurrences = occurrences.size();

        try {
        List<RequestLexicalView.Span> targetEnvelopes = occurrences.stream()
                .map(occurrence -> new RequestLexicalView.Span(
                        occurrence.envelopeStart(),
                        occurrence.envelopeEnd()))
                        .filter(occurrence -> view.isTopLevel(
                                occurrence.start()))
                .toList();
        List<CanonicalMutationClauseMatcher.Action> indexedActions =
                CanonicalMutationClauseMatcher.indexedActions(
                        authorityView,
                        0,
                        request.length(),
                        targetEnvelopes).actions().stream()
                        .filter(action -> !intersectsExcluded(
                                excluded,
                                action.start(),
                                action.end()))
                        .toList();
        List<CanonicalMutationClauseMatcher.Action> actions = indexedActions
                        .stream()
                        .filter(action -> authorityView.isTopLevel(action.start())
                                || authorityView.containsMalformedDelimiter(
                                        0,
                                        action.start()))
                        .toList();
        work.actions = indexedActions.size();

        List<BoundAction> boundActions = bindActions(
                view,
                candidates,
                occurrences,
                actions,
                excluded,
                boundaries,
                work);

        int anchorOutputEnd = -1;
        int previousBoundEnd = -1;
        int preAnchorBoundEnd = -1;
        boolean anchorEstablished = false;
        BoundAction primaryAnchor = null;
        List<SequentialMutationClausePlan.ClauseDecision> decisions =
                new ArrayList<>();
        List<SequentialMutationClausePlan.OwnedRegion> regions =
                new ArrayList<>(providerRemovedControlQuarantines(
                        view,
                        candidates,
                        boundActions,
                        excluded,
                        boundaries,
                        evidence,
                        work));
        Set<RegionIdentity> regionIdentities = new HashSet<>();
        for (SequentialMutationClausePlan.OwnedRegion region : regions) {
            regionIdentities.add(new RegionIdentity(
                    region.kind(),
                    region.startOffset(),
                    region.endOffset()));
        }
        boolean[] laterEligibleAnchors = new boolean[boundActions.size()];
        boolean eligibleAnchorSeen = false;
        for (int index = boundActions.size() - 1; index >= 0; index--) {
            laterEligibleAnchors[index] = eligibleAnchorSeen;
            if (eligibleContentAnchor(boundActions.get(index))) {
                eligibleAnchorSeen = true;
            }
        }

        for (int index = 0; index < boundActions.size(); index++) {
            BoundAction bound = boundActions.get(index);
            int authorityFrom = anchorEstablished
                    ? Math.max(0, previousBoundEnd)
                    : Math.max(0, preAnchorBoundEnd);
            SequenceBoundary stableReset = boundaries.stableHardResetBefore(
                    authorityFrom,
                    bound.action().start());
            int authorityStart = stableReset.present()
                    ? stableReset.clauseStart()
                    : freshClauseStart(
                            boundaries,
                            authorityFrom,
                            bound.action().start());
            SequentialMutationClausePlan.Authority authority = authorityFor(
                    authorityView,
                    bound.action(),
                    bound.family(),
                    bound.disjunctiveTargets(),
                    bound.pronominalProhibition(),
                    authorityStart,
                    bound.outputEnd(),
                    bound.clauseEnd());
            if (!anchorEstablished) {
                int rejectedPreAnchorOffset = boundaries.firstRejectedOffset(
                        boundaries.rejectionSearchStart(
                                Math.max(0, preAnchorBoundEnd),
                                bound.action().start()),
                        bound.clauseEnd());
                SequentialMutationClausePlan.Authority preAnchorAuthority =
                        inheritedPreAnchorAuthority(
                                authorityView,
                                boundaries,
                                preAnchorBoundEnd,
                                bound.action().start(),
                                authority);
                boolean eligibleAnchor = eligibleContentAnchor(bound);
                boolean controlSafeAnchor =
                        !view.containsProviderRemovedControl(
                                bound.outputEnd(),
                                view.request().length())
                                || controlRetainablePrimaryAnchor(bound);
                boolean laterEligibleAnchor = laterEligibleAnchors[index];
                if (preAnchorAuthority
                                == SequentialMutationClausePlan.Authority.REQUIRED
                        && eligibleAnchor
                        && controlSafeAnchor
                        && rejectedPreAnchorOffset < 0) {
                    anchorEstablished = true;
                    anchorOutputEnd = bound.outputEnd();
                    previousBoundEnd = bound.outputEnd();
                    primaryAnchor = bound;
                } else if (eligibleAnchor) {
                    int rejectedConnector = rejectedPreAnchorOffset;
                    if (rejectedConnector >= 0) {
                        RequestLexicalView.Span rejectedSpan = rejectedSpan(
                                view,
                                preAnchorBoundEnd >= 0
                                        ? rejectedConnector
                                        : Math.min(
                                                rejectedConnector,
                                                bound.action().start()),
                                bound.clauseEnd());
                        if (rejectedSpan.end() > rejectedSpan.start()
                                && !intersectsExcluded(
                                        excluded,
                                        rejectedSpan.start(),
                                        rejectedSpan.end())) {
                            String sourceText = evidence.materialize(
                                    rejectedSpan);
                            work.regionDedupLookups++;
                            addRegion(
                                    regions,
                                    regionIdentities,
                                    new SequentialMutationClausePlan.OwnedRegion(
                                            SequentialMutationClausePlan
                                                    .RegionKind.CONTENT_MUTATION,
                                            rejectedSpan.start(),
                                            rejectedSpan.end(),
                                            sourceText),
                                    evidence);
                            SequentialMutationClausePlan.Authority
                                    rejectedAuthority = authority
                                            == SequentialMutationClausePlan
                                                    .Authority.FORBIDDEN
                                            ? authority
                                            : SequentialMutationClausePlan
                                                    .Authority.NON_AUTHORITATIVE;
                            for (CoordinatedMutationTailParser.TargetOccurrence
                                    target : bound.targets()) {
                                addDecisionIfContained(
                                        decisions,
                                        rejectedAuthority,
                                        bound.action(),
                                        bound.family(),
                                        target,
                                        rejectedSpan,
                                        sourceText,
                                        evidence);
                            }
                        }
                        preAnchorBoundEnd = bound.outputEnd();
                        continue;
                    }
                    if (!controlSafeAnchor
                            || laterEligibleAnchor
                                    && explicitlyRestrictivePreAnchor(
                                            bound,
                                            preAnchorAuthority)) {
                        addPreAnchorNonAuthorityEvidence(
                                view,
                                bound,
                                authorityStart,
                                preAnchorAuthority,
                                excluded,
                                regions,
                                decisions,
                                regionIdentities,
                                evidence,
                                work);
                        preAnchorBoundEnd = bound.outputEnd();
                        continue;
                    }
                    if (preAnchorBoundEnd < 0) {
                        preAnchorBoundEnd = bound.outputEnd();
                        continue;
                    }
                    SequenceBoundary restrictiveBoundary =
                            boundaries.latestBefore(
                                    preAnchorBoundEnd,
                                    bound.action().start());
                    if (restrictiveBoundary.present()) {
                        RequestLexicalView.Span restrictiveSpan =
                                view.trimmedSpan(
                                        restrictiveBoundary.clauseStart(),
                                        bound.clauseEnd());
                        if (restrictiveSpan.end() > restrictiveSpan.start()
                                && !intersectsExcluded(
                                        excluded,
                                        restrictiveSpan.start(),
                                        restrictiveSpan.end())) {
                            String sourceText = evidence.materialize(
                                    restrictiveSpan);
                            work.regionDedupLookups++;
                            addRegion(
                                    regions,
                                    regionIdentities,
                                    new SequentialMutationClausePlan.OwnedRegion(
                                            SequentialMutationClausePlan.RegionKind
                                                    .CONTENT_MUTATION,
                                            restrictiveSpan.start(),
                                            restrictiveSpan.end(),
                                            sourceText),
                                    evidence);
                            SequentialMutationClausePlan.Authority
                                    restrictiveAuthority = preAnchorAuthority
                                            == SequentialMutationClausePlan
                                                    .Authority.REQUIRED
                                            ? SequentialMutationClausePlan
                                                    .Authority.NON_AUTHORITATIVE
                                            : preAnchorAuthority;
                            for (CoordinatedMutationTailParser.TargetOccurrence
                                    target : bound.targets()) {
                                addDecisionIfContained(
                                        decisions,
                                        restrictiveAuthority,
                                        bound.action(),
                                        bound.family(),
                                        target,
                                        restrictiveSpan,
                                        sourceText,
                                        evidence);
                            }
                        }
                    }
                }
                if (!anchorEstablished && eligibleAnchor) {
                    preAnchorBoundEnd = bound.outputEnd();
                }
                continue;
            }

            SequenceBoundary boundary = boundaries.latestBefore(
                    previousBoundEnd,
                    bound.action().start());
            if (!boundary.present() && stableReset.present()) {
                boundary = stableReset;
            }
            if (!boundary.present()) {
                if (hasRejectedSequentialBoundary(
                        view,
                        previousBoundEnd,
                        bound.action().start())) {
                    int rejectedConnector = boundaries.firstRejectedOffset(
                            previousBoundEnd,
                            bound.action().start());
                    RequestLexicalView.Span rejectedSpan = rejectedSpan(
                            view,
                            rejectedConnector >= 0
                                    ? rejectedConnector
                                    : bound.action().start(),
                            bound.clauseEnd());
                    if (rejectedSpan.end() > rejectedSpan.start()
                            && !intersectsExcluded(
                                    excluded,
                                    rejectedSpan.start(),
                                    rejectedSpan.end())) {
                        String sourceText = evidence.materialize(rejectedSpan);
                        SequentialMutationClausePlan.RegionKind regionKind =
                                bound.family()
                                                == CanonicalMutationClauseMatcher
                                                        .Family
                                                        .WORKSPACE_OPERATION
                                        ? SequentialMutationClausePlan
                                                .RegionKind.WORKSPACE_OPERATION
                                        : SequentialMutationClausePlan
                                                .RegionKind.CONTENT_MUTATION;
                        work.regionDedupLookups++;
                        addRegion(
                                regions,
                                regionIdentities,
                                new SequentialMutationClausePlan.OwnedRegion(
                                        regionKind,
                                        rejectedSpan.start(),
                                        rejectedSpan.end(),
                                        sourceText),
                                evidence);
                        SequentialMutationClausePlan.Authority rejectedAuthority =
                                authority
                                                == SequentialMutationClausePlan
                                                        .Authority.FORBIDDEN
                                        ? authority
                                        : SequentialMutationClausePlan
                                                .Authority.NON_AUTHORITATIVE;
                        for (CoordinatedMutationTailParser.TargetOccurrence target
                                : bound.targets()) {
                            addDecisionIfContained(
                                    decisions,
                                    rejectedAuthority,
                                    bound.action(),
                                    bound.family(),
                                    target,
                                    rejectedSpan,
                                    sourceText,
                                    evidence);
                        }
                    }
                    previousBoundEnd = bound.outputEnd();
                    continue;
                }
                if (authority == SequentialMutationClausePlan.Authority.REQUIRED
                        && bound.family()
                                == CanonicalMutationClauseMatcher.Family
                                        .CONTENT_MUTATION) {
                    previousBoundEnd = bound.outputEnd();
                }
                continue;
            }

            RequestLexicalView.Span regionSpan = view.trimmedSpan(
                    boundary.clauseStart(),
                    bound.clauseEnd());
            if (regionSpan.end() <= regionSpan.start()
                    || intersectsExcluded(
                            excluded,
                            regionSpan.start(),
                            regionSpan.end())) {
                continue;
            }
            SequentialMutationClausePlan.RegionKind regionKind =
                    bound.family()
                                    == CanonicalMutationClauseMatcher.Family
                                            .WORKSPACE_OPERATION
                            ? SequentialMutationClausePlan.RegionKind
                                    .WORKSPACE_OPERATION
                            : SequentialMutationClausePlan.RegionKind
                                    .CONTENT_MUTATION;
            String sourceText = evidence.materialize(regionSpan);
            work.regionDedupLookups++;
            addRegion(regions, regionIdentities,
                    new SequentialMutationClausePlan.OwnedRegion(
                    regionKind,
                    regionSpan.start(),
                    regionSpan.end(),
                            sourceText),
                    evidence);

            boolean recognizedActionLead = SEQUENTIAL_ACTION_LEAD.matcher(
                    view.maskedLower().substring(
                            boundary.clauseStart(),
                            bound.action().start()))
                    .matches();
            SequentialMutationClausePlan.Authority boundedAuthority =
                    recognizedActionLead
                            || authority
                                    == SequentialMutationClausePlan.Authority
                                            .OPTIONAL
                            || authority
                                    == SequentialMutationClausePlan.Authority
                                            .FORBIDDEN
                            || authority
                                    == SequentialMutationClausePlan.Authority
                                            .NON_AUTHORITATIVE
                    ? authority
                    : SequentialMutationClausePlan.Authority
                            .NON_AUTHORITATIVE;

            for (CoordinatedMutationTailParser.TargetOccurrence target
                    : bound.targets()) {
                addDecisionIfContained(
                        decisions,
                        boundedAuthority,
                        bound.action(),
                        bound.family(),
                        target,
                        regionSpan,
                        sourceText,
                        evidence);
            }
            previousBoundEnd = bound.outputEnd();
        }

        if (anchorEstablished) {
            addRetainedPrimaryAnchorEvidence(
                    view,
                    primaryAnchor,
                    excluded,
                    regions,
                    decisions,
                    regionIdentities,
                    evidence,
                    work);
            addUnclassifiedSequentialRegions(
                    view,
                    occurrences,
                    indexedActions,
                    excluded,
                    anchorOutputEnd,
                    regions,
                    decisions,
                    regionIdentities,
                    boundaries,
                    boundActions,
                    evidence,
                    work);
        }

        decisions.sort(Comparator
                .comparingInt(SequentialMutationClausePlan.ClauseDecision
                        ::startOffset)
                .thenComparingInt(SequentialMutationClausePlan.ClauseDecision
                        ::actionStartOffset)
                .thenComparingInt(SequentialMutationClausePlan.ClauseDecision
                        ::targetStartOffset)
                .thenComparingInt(SequentialMutationClausePlan.ClauseDecision
                        ::authorityEndOffset)
                .thenComparing(SequentialMutationClausePlan.ClauseDecision
                        ::path));
        regions.sort(Comparator
                .comparingInt(SequentialMutationClausePlan.OwnedRegion
                        ::startOffset)
                .thenComparingInt(SequentialMutationClausePlan.OwnedRegion
                        ::endOffset));
        if ((long) excluded.size() + decisions.size() + regions.size()
                        > SequentialMutationClausePlan.MAX_EVIDENCE_ROWS) {
            return measured(authorityLimitQuarantine(
                    request,
                    excluded,
                    SequentialMutationClausePlan.RegionKind
                            .LEXICAL_EVIDENCE_LIMIT_QUARANTINE), work);
        }
        if (SequentialMutationClausePlan.exceedsRetainedEvidenceCharacters(
                decisions,
                regions)) {
            return measured(authorityLimitQuarantine(
                    request,
                    excluded,
                    SequentialMutationClausePlan.RegionKind
                            .LEXICAL_EVIDENCE_LIMIT_QUARANTINE), work);
        }
        return measured(new SequentialMutationClausePlan(
                request,
                excluded,
                decisions,
                regions), work);
        } catch (LexicalEvidenceLimitExceeded ignored) {
            return measured(authorityLimitQuarantine(
                    request,
                    excluded,
                    SequentialMutationClausePlan.RegionKind
                            .LEXICAL_EVIDENCE_LIMIT_QUARANTINE), work);
        }
    }

    private static void addRetainedPrimaryAnchorEvidence(
            RequestLexicalView view,
            BoundAction primaryAnchor,
            List<SequentialMutationClausePlan.SourceSpan> excluded,
            List<SequentialMutationClausePlan.OwnedRegion> regions,
            List<SequentialMutationClausePlan.ClauseDecision> decisions,
            Set<RegionIdentity> regionIdentities,
            EvidenceBudget evidence,
            MutableParseWork work
    ) {
        if (view == null || primaryAnchor == null) return;
        boolean controlAfterAnchor = view.containsProviderRemovedControl(
                primaryAnchor.outputEnd(),
                view.request().length());
        boolean earlierTypedRegion = regions.stream().anyMatch(region ->
                region.endOffset() <= primaryAnchor.action().start());
        Set<String> primaryPaths = primaryAnchor.targets().stream()
                .map(CoordinatedMutationTailParser.TargetOccurrence::path)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean laterSamePathDecision = decisions.stream().anyMatch(decision ->
                decision.startOffset() >= primaryAnchor.outputEnd()
                        && primaryPaths.contains(decision.path()));
        if (!controlAfterAnchor
                && !earlierTypedRegion
                && !laterSamePathDecision) {
            return;
        }
        if (controlAfterAnchor
                && !controlRetainablePrimaryAnchor(primaryAnchor)) {
            return;
        }
        RequestLexicalView.Span anchorSpan = view.trimmedSpan(
                primaryAnchor.action().start(),
                primaryAnchor.outputEnd());
        if (anchorSpan.end() < primaryAnchor.outputEnd()
                || intersectsExcluded(
                        excluded,
                        anchorSpan.start(),
                        anchorSpan.end())) {
            return;
        }
        String sourceText = evidence.materialize(anchorSpan);
        work.regionDedupLookups++;
        addRegion(
                regions,
                regionIdentities,
                new SequentialMutationClausePlan.OwnedRegion(
                        SequentialMutationClausePlan.RegionKind
                                .CONTENT_MUTATION,
                        anchorSpan.start(),
                        anchorSpan.end(),
                        sourceText),
                evidence);
        for (CoordinatedMutationTailParser.TargetOccurrence target
                : primaryAnchor.targets()) {
            addDecisionIfContained(
                    decisions,
                    SequentialMutationClausePlan.Authority.REQUIRED,
                    primaryAnchor.action(),
                    primaryAnchor.family(),
                    target,
                    anchorSpan,
                    sourceText,
                    evidence);
        }
    }

    private static void addPreAnchorNonAuthorityEvidence(
            RequestLexicalView view,
            BoundAction bound,
            int authorityStart,
            SequentialMutationClausePlan.Authority directAuthority,
            List<SequentialMutationClausePlan.SourceSpan> excluded,
            List<SequentialMutationClausePlan.OwnedRegion> regions,
            List<SequentialMutationClausePlan.ClauseDecision> decisions,
            Set<RegionIdentity> regionIdentities,
            EvidenceBudget evidence,
            MutableParseWork work
    ) {
        if (view == null || bound == null || directAuthority == null) return;
        RequestLexicalView.Span span = view.trimmedSpan(
                Math.max(0, authorityStart),
                bound.clauseEnd());
        if (span.end() <= span.start()
                || intersectsExcluded(excluded, span.start(), span.end())) {
            return;
        }
        SequentialMutationClausePlan.Authority safeAuthority = directAuthority;
        if (safeAuthority == SequentialMutationClausePlan.Authority.REQUIRED) {
            safeAuthority = bound.family()
                            == CanonicalMutationClauseMatcher.Family
                                    .WORKSPACE_OPERATION
                    ? SequentialMutationClausePlan.Authority
                            .WORKSPACE_OPERATION_BLOCKED
                    : SequentialMutationClausePlan.Authority.NON_AUTHORITATIVE;
        }
        SequentialMutationClausePlan.RegionKind kind = bound.family()
                        == CanonicalMutationClauseMatcher.Family
                                .WORKSPACE_OPERATION
                ? SequentialMutationClausePlan.RegionKind.WORKSPACE_OPERATION
                : SequentialMutationClausePlan.RegionKind.CONTENT_MUTATION;
        String sourceText = evidence.materialize(span);
        work.regionDedupLookups++;
        addRegion(
                regions,
                regionIdentities,
                new SequentialMutationClausePlan.OwnedRegion(
                        kind,
                        span.start(),
                        span.end(),
                        sourceText),
                evidence);
        for (CoordinatedMutationTailParser.TargetOccurrence target
                : bound.targets()) {
            addDecisionIfContained(
                    decisions,
                    safeAuthority,
                    bound.action(),
                    bound.family(),
                    target,
                    span,
                    sourceText,
                    evidence);
        }
    }

    private static boolean controlRetainablePrimaryAnchor(BoundAction bound) {
        if (bound == null || bound.targets().size() != 1) return false;
        String verb = bound.action().verb().toLowerCase(Locale.ROOT);
        return CONTROL_RETAINABLE_PRIMARY_VERBS.contains(verb)
                && bound.targets().stream()
                        .allMatch(target -> looksLikeFileTarget(target.path()));
    }

    private static boolean explicitlyRestrictivePreAnchor(
            BoundAction bound,
            SequentialMutationClausePlan.Authority authority
    ) {
        if (bound == null || authority == null) return false;
        if (bound.family()
                == CanonicalMutationClauseMatcher.Family.WORKSPACE_OPERATION) {
            return true;
        }
        return bound.action().scopeKind()
                        == CanonicalMutationClauseMatcher.ScopeKind
                                .REPORTED_ADVISORY
                || bound.action().scopeKind()
                        == CanonicalMutationClauseMatcher.ScopeKind.PROHIBITED
                || bound.action().nonImperativeUse()
                || authority == SequentialMutationClausePlan.Authority.FORBIDDEN;
    }

    private static SequentialMutationClausePlan.Authority
            inheritedPreAnchorAuthority(
                    RequestLexicalView view,
                    SequenceBoundaryIndex boundaries,
                    int priorOutputEnd,
                    int actionStart,
                    SequentialMutationClausePlan.Authority directAuthority
            ) {
        if (view == null
                || priorOutputEnd < 0
                || actionStart <= priorOutputEnd
                || directAuthority
                        != SequentialMutationClausePlan.Authority.REQUIRED) {
            return directAuthority;
        }
        if (boundaries != null
                && boundaries.hasInheritedScopeReset(
                        priorOutputEnd,
                        actionStart)) {
            return directAuthority;
        }
        CanonicalMutationClauseMatcher.ScopeKind inherited =
                CanonicalMutationClauseMatcher.finiteScopeKind(
                        view.maskedLower().substring(
                                priorOutputEnd,
                                actionStart));
        return switch (inherited) {
            case OPTIONAL -> SequentialMutationClausePlan.Authority.OPTIONAL;
            case PROHIBITED -> SequentialMutationClausePlan.Authority.FORBIDDEN;
            case REPORTED_ADVISORY -> SequentialMutationClausePlan.Authority
                    .NON_AUTHORITATIVE;
            case NONE -> directAuthority;
        };
    }

    private static MeasuredPlan measured(
            SequentialMutationClausePlan plan,
            MutableParseWork work
    ) {
        return new MeasuredPlan(plan, work.snapshot());
    }

    private static SequentialMutationClausePlan targetIndexLimitQuarantine(
            String request,
            List<SequentialMutationClausePlan.SourceSpan> excluded,
            CoordinatedMutationTailParser.TargetOccurrenceScanStatus status
    ) {
        SequentialMutationClausePlan.RegionKind kind = switch (status) {
            case OUTPUT_VISIT_LIMIT -> SequentialMutationClausePlan.RegionKind
                    .TARGET_OUTPUT_LIMIT_QUARANTINE;
            case OCCURRENCE_LIMIT -> SequentialMutationClausePlan.RegionKind
                    .TARGET_OCCURRENCE_LIMIT_QUARANTINE;
            case COMPLETE -> throw new IllegalArgumentException(
                    "complete target scans cannot create quarantine evidence");
        };
        return authorityLimitQuarantine(request, excluded, kind);
    }

    private static SequentialMutationClausePlan authorityLimitQuarantine(
            String request,
            List<SequentialMutationClausePlan.SourceSpan> excluded,
            SequentialMutationClausePlan.RegionKind kind
    ) {
        return SequentialMutationClausePlan.authorityLimit(
                request,
                excluded,
                kind);
    }

    private static void addDecisionIfContained(
            List<SequentialMutationClausePlan.ClauseDecision> decisions,
            SequentialMutationClausePlan.Authority authority,
            CanonicalMutationClauseMatcher.Action action,
            CanonicalMutationClauseMatcher.Family family,
            CoordinatedMutationTailParser.TargetOccurrence target,
            RequestLexicalView.Span sourceSpan,
            String sourceText,
            EvidenceBudget evidence
    ) {
        if (decisions == null || authority == null || action == null
                || family == null || target == null || sourceSpan == null) {
            return;
        }
        if (action.start() < sourceSpan.start()
                || action.end() > sourceSpan.end()
                || target.envelopeStart() < sourceSpan.start()
                || target.envelopeEnd() > sourceSpan.end()
                || action.end() > target.envelopeEnd()) {
            return;
        }
        evidence.addDecision(
                decisions,
                new SequentialMutationClausePlan.ClauseDecision(
                target.path(),
                authority,
                action.verb(),
                actionFamily(family),
                action.start(),
                action.end(),
                target.envelopeStart(),
                sourceSpan.start(),
                target.envelopeEnd(),
                sourceSpan.end(),
                sourceText));
    }

    private static boolean eligibleContentAnchor(BoundAction bound) {
        if (bound == null
                || bound.family()
                        != CanonicalMutationClauseMatcher.Family
                                .CONTENT_MUTATION) {
            return false;
        }
        String verb = bound.action().verb().toLowerCase(Locale.ROOT);
        if (!"create".equals(verb) && !"make".equals(verb)) return true;
        return bound.targets().stream()
                .anyMatch(target -> target.quoted()
                        || looksLikeFileTarget(target.path()));
    }

    private static boolean looksLikeFileTarget(String path) {
        if (path == null || path.isBlank()) return false;
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = normalized.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot + 1 < name.length();
    }

    private static List<BoundAction> bindActions(
            RequestLexicalView view,
            List<String> candidates,
            List<CoordinatedMutationTailParser.TargetOccurrence> occurrences,
            List<CanonicalMutationClauseMatcher.Action> actions,
            List<SequentialMutationClausePlan.SourceSpan> excluded,
            SequenceBoundaryIndex boundaries,
            MutableParseWork work
    ) {
        List<BoundAction> bound = new ArrayList<>();
        boolean disjunctiveFromPriorAction = false;
        for (int index = 0; index < actions.size(); index++) {
            CanonicalMutationClauseMatcher.Action action = actions.get(index);
            int nextActionStart = index + 1 < actions.size()
                    ? actions.get(index + 1).start()
                    : view.request().length();
            work.sentenceEndQueries++;
            int scanEnd = Math.min(
                    nextActionStart,
                    view.sentenceEndAfter(action.end()));
            scanEnd = boundaries.clampForwardScanEnd(
                    action.end(),
                    scanEnd);
            DirectTargets direct =
                    directTargets(
                            view,
                            occurrences,
                            action,
                            scanEnd,
                            excluded,
                            work);
            if (direct.targets().isEmpty()) {
                disjunctiveFromPriorAction = false;
                continue;
            }
            int outputEnd = direct.targets().getLast().envelopeEnd();
            int clauseEnd = clauseEnd(
                    boundaries,
                    outputEnd,
                    scanEnd,
                    nextActionStart);
            CanonicalMutationClauseMatcher.Family family = effectiveFamily(
                    view,
                    action,
                    direct.targets().getFirst().envelopeStart(),
                    outputEnd,
                    clauseEnd);
            int pronominalProhibitionEnd = family
                            == CanonicalMutationClauseMatcher.Family
                                    .CONTENT_MUTATION
                    ? pronominalProhibitionEnd(view, outputEnd, excluded)
                    : -1;
            if (pronominalProhibitionEnd >= 0) {
                clauseEnd = Math.max(clauseEnd, pronominalProhibitionEnd);
            }
            boolean disjunctiveContinuation =
                    DISJUNCTIVE_CLAUSE_SIGNAL.matcher(
                            view.maskedLower().substring(
                                    outputEnd,
                                    Math.max(outputEnd, nextActionStart)))
                            .find();
            bound.add(new BoundAction(
                    action,
                    family,
                    direct.targets(),
                    direct.disjunctive()
                            || disjunctiveFromPriorAction
                            || disjunctiveContinuation,
                    pronominalProhibitionEnd >= 0,
                    outputEnd,
                    clauseEnd));
            disjunctiveFromPriorAction = disjunctiveContinuation;
        }
        return List.copyOf(bound);
    }

    private static DirectTargets
            directTargets(
                    RequestLexicalView view,
                    List<CoordinatedMutationTailParser.TargetOccurrence>
                            occurrences,
                    CanonicalMutationClauseMatcher.Action action,
                    int scanEnd,
                    List<SequentialMutationClausePlan.SourceSpan> excluded,
                    MutableParseWork work
            ) {
        List<CoordinatedMutationTailParser.TargetOccurrence> selected =
                new ArrayList<>();
        boolean disjunctive = false;
        int priorEnd = action.end();
        int occurrenceIndex = firstOccurrenceAtOrAfter(
                occurrences,
                action.end());
        for (; occurrenceIndex < occurrences.size(); occurrenceIndex++) {
            work.directTargetVisits++;
            CoordinatedMutationTailParser.TargetOccurrence occurrence =
                    occurrences.get(occurrenceIndex);
            if (occurrence.envelopeEnd() > scanEnd) break;
            if (occurrence.envelopeStart() < priorEnd) {
                continue;
            }
            if (!occurrence.quoted()
                    && GENERIC_RESOURCE_CLASSES.contains(
                            occurrence.path().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (intersectsExcluded(
                    excluded,
                    priorEnd,
                    occurrence.envelopeEnd())) {
                break;
            }
            String gap = view.masked().substring(
                    priorEnd,
                    occurrence.envelopeStart());
            boolean direct = selected.isEmpty()
                    ? DIRECT_TARGET_PREFIX.matcher(gap).matches()
                    : ADJACENT_TARGET_GAP.matcher(gap).matches();
            if (!direct) {
                if (selected.isEmpty()) continue;
                break;
            }
            if (!selected.isEmpty()
                    && DISJUNCTIVE_TARGET_GAP.matcher(gap).find()) {
                disjunctive = true;
            }
            if (selected.isEmpty()
                    && action.family()
                            == CanonicalMutationClauseMatcher.Family
                                    .CONTENT_MUTATION
                    && !CanonicalMutationClauseMatcher
                            .directlyBindsContentTarget(
                                    view.request().substring(
                                            action.start(),
                                            occurrence.envelopeEnd()),
                                    occurrence.path())) {
                continue;
            }
            selected.add(occurrence);
            priorEnd = occurrence.envelopeEnd();
            if (action.family()
                    == CanonicalMutationClauseMatcher.Family
                            .WORKSPACE_OPERATION) {
                break;
            }
        }
        return new DirectTargets(List.copyOf(selected), disjunctive);
    }

    private static int firstOccurrenceAtOrAfter(
            List<CoordinatedMutationTailParser.TargetOccurrence> occurrences,
            int offset
    ) {
        int low = 0;
        int high = occurrences.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (occurrences.get(middle).envelopeStart() < offset) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static CanonicalMutationClauseMatcher.Family effectiveFamily(
            RequestLexicalView view,
            CanonicalMutationClauseMatcher.Action action,
            int targetStart,
            int outputEnd,
            int clauseEnd
    ) {
        if (action.family()
                == CanonicalMutationClauseMatcher.Family.WORKSPACE_OPERATION) {
            return action.family();
        }
        String verb = action.verb().toLowerCase(Locale.ROOT);
        if (!"create".equals(verb) && !"make".equals(verb)) {
            return action.family();
        }
        String prefix = view.maskedLower().substring(
                Math.min(action.end(), targetStart),
                Math.max(action.end(), targetStart));
        String suffix = view.maskedLower().substring(
                Math.min(outputEnd, clauseEnd),
                Math.max(outputEnd, clauseEnd));
        return DIRECTORY_PREFIX_SIGNAL.matcher(prefix).find()
                        || DIRECTORY_SUFFIX.matcher(suffix).find()
                ? CanonicalMutationClauseMatcher.Family.WORKSPACE_OPERATION
                : action.family();
    }

    private static SequentialMutationClausePlan.Authority authorityFor(
            RequestLexicalView view,
            CanonicalMutationClauseMatcher.Action action,
            CanonicalMutationClauseMatcher.Family family,
            boolean disjunctiveTargets,
            boolean pronominalProhibition,
            int clauseStart,
            int outputEnd,
            int clauseEnd
    ) {
        String prefix = view.maskedLower().substring(
                Math.max(0, clauseStart),
                action.start());
        CanonicalMutationClauseMatcher.ScopeKind scope = action.scopeKind();
        boolean prohibited = scope
                        == CanonicalMutationClauseMatcher.ScopeKind.PROHIBITED
                || CanonicalMutationClauseMatcher.hasOpenProhibitionScope(prefix);
        if (prohibited) {
            return SequentialMutationClausePlan.Authority.FORBIDDEN;
        }
        if (view.containsProviderRemovedControl(clauseStart, clauseEnd)
                || view.containsLoneCarriageReturn(clauseStart, clauseEnd)
                || view.containsMalformedDelimiter(clauseStart, clauseEnd)
                || disjunctiveTargets) {
            return SequentialMutationClausePlan.Authority.NON_AUTHORITATIVE;
        }
        if (pronominalProhibition) {
            return SequentialMutationClausePlan.Authority.FORBIDDEN;
        }
        if (family
                == CanonicalMutationClauseMatcher.Family.WORKSPACE_OPERATION) {
            return action.nonImperativeUse()
                            || scope != CanonicalMutationClauseMatcher.ScopeKind.NONE
                    ? SequentialMutationClausePlan.Authority.NON_AUTHORITATIVE
                    : SequentialMutationClausePlan.Authority
                            .WORKSPACE_OPERATION_BLOCKED;
        }
        if (scope == CanonicalMutationClauseMatcher.ScopeKind.OPTIONAL) {
            return SequentialMutationClausePlan.Authority.OPTIONAL;
        }
        if (scope
                == CanonicalMutationClauseMatcher.ScopeKind.REPORTED_ADVISORY) {
            return SequentialMutationClausePlan.Authority.NON_AUTHORITATIVE;
        }
        String suffix = view.maskedLower().substring(
                Math.min(outputEnd, clauseEnd),
                Math.max(outputEnd, clauseEnd));
        boolean opensConstraintTail =
                CoordinatedMutationTailParser.firstUnquotedConstraintBoundary(
                        view,
                        outputEnd,
                         clauseEnd) == outputEnd;
        if (opensConstraintTail) {
            return SequentialMutationClausePlan.Authority.REQUIRED;
        }
        CanonicalMutationClauseMatcher.ScopeKind postfixScope = opensConstraintTail
                ? CanonicalMutationClauseMatcher.ScopeKind.NONE
                : CanonicalMutationClauseMatcher.finitePostfixScopeKind(suffix);
        if (postfixScope
                == CanonicalMutationClauseMatcher.ScopeKind.OPTIONAL) {
            return SequentialMutationClausePlan.Authority.OPTIONAL;
        }
        if (postfixScope
                        == CanonicalMutationClauseMatcher.ScopeKind
                                .REPORTED_ADVISORY
                || action.nonImperativeUse()
                        && !followsUnicodeLineBoundary(view, clauseStart)) {
            return SequentialMutationClausePlan.Authority.NON_AUTHORITATIVE;
        }
        if (!POSITIVE_TERMINAL_SUFFIX.matcher(suffix).matches()) {
            return SequentialMutationClausePlan.Authority.NON_AUTHORITATIVE;
        }
        return SequentialMutationClausePlan.Authority.REQUIRED;
    }

    private static int pronominalProhibitionEnd(
            RequestLexicalView view,
            int outputEnd,
            List<SequentialMutationClausePlan.SourceSpan> excluded
    ) {
        int firstSentenceEnd = view.sentenceEndAfter(outputEnd);
        int nextVisible = firstSentenceEnd;
        while (nextVisible < view.request().length()
                && Character.isWhitespace(view.request().charAt(nextVisible))) {
            nextVisible++;
        }
        int scanEnd = nextVisible < view.request().length()
                ? view.sentenceEndAfter(nextVisible)
                : firstSentenceEnd;
        scanEnd = Math.max(firstSentenceEnd, scanEnd);
        if (scanEnd <= outputEnd) return -1;
        java.util.regex.Matcher matcher = PRONOMINAL_PROHIBITION.matcher(
                view.maskedLower());
        matcher.region(outputEnd, scanEnd);
        if (!matcher.lookingAt()) return -1;
        int matchedEnd = matcher.end();
        return view.isUnquotedRange(outputEnd, matchedEnd)
                        && !view.containsProviderRemovedControl(
                                outputEnd,
                                matchedEnd)
                        && !view.containsLoneCarriageReturn(
                                outputEnd,
                                matchedEnd)
                        && !view.containsMalformedDelimiter(
                                outputEnd,
                                matchedEnd)
                        && !intersectsExcluded(
                                excluded,
                                outputEnd,
                                matchedEnd)
                ? matchedEnd
                : -1;
    }

    private static boolean followsUnicodeLineBoundary(
            RequestLexicalView view,
            int clauseStart
    ) {
        if (view == null || clauseStart <= 0) return false;
        for (int offset = clauseStart - 1; offset >= 0; offset--) {
            char value = view.request().charAt(offset);
            if (value == '\u2028' || value == '\u2029') return true;
            if (!Character.isWhitespace(value)) return false;
        }
        return false;
    }

    private static int freshClauseStart(
            SequenceBoundaryIndex boundaries,
            int from,
            int actionStart
    ) {
        SequenceBoundary boundary = boundaries.latestBefore(from, actionStart);
        return boundary.present() ? boundary.clauseStart() : Math.max(0, from);
    }

    private static RequestLexicalView.Span rejectedSpan(
            RequestLexicalView view,
            int start,
            int end
    ) {
        int safeStart = Math.max(0, Math.min(start, view.request().length()));
        RequestLexicalView.Span trimmed = view.trimmedSpan(safeStart, end);
        return new RequestLexicalView.Span(safeStart, trimmed.end());
    }

    private static List<SequentialMutationClausePlan.OwnedRegion>
            providerRemovedControlQuarantines(
                    RequestLexicalView view,
                    List<String> candidates,
                    List<BoundAction> boundActions,
                    List<SequentialMutationClausePlan.SourceSpan> excluded,
                    SequenceBoundaryIndex boundaries,
                    EvidenceBudget evidence,
                    MutableParseWork work
            ) {
        if (view == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<SequentialMutationClausePlan.OwnedRegion> regions =
                new ArrayList<>();
        Set<RegionIdentity> identities = new HashSet<>();
        String request = view.request();
        if (!view.containsProviderRemovedControl(0, request.length())) {
            return List.of();
        }
        List<SequentialMutationClausePlan.SourceSpan> boundTargetSpans =
                normalizedBoundTargetSpans(boundActions);
        int quarantinedThrough = -1;
        for (int offset = 0; offset < request.length(); offset++) {
            if (offset < quarantinedThrough) continue;
            if (!view.containsProviderRemovedControl(offset, offset + 1)
                    || intersectsExcluded(excluded, offset, offset + 1)) {
                continue;
            }
            int start = offset;
            if (offset > 0
                    && offset + 1 < request.length()
                    && wordCharacter(request.charAt(offset - 1))
                    && wordCharacter(request.charAt(offset + 1))) {
                while (start > 0
                        && wordCharacter(request.charAt(start - 1))) {
                    start--;
                }
            }
            SequenceBoundary reset = boundaries.firstHardResetAfter(
                    offset + 1,
                    request.length());
            int end = reset.present()
                    ? reset.endOffset()
                    : request.length();
            end = Math.max(
                    offset + 1,
                    view.trimmedSpan(start, end).end());
            RequestLexicalView.Span bounded = exclusionBoundedSpan(
                    excluded,
                    start,
                    offset,
                    end);
            start = bounded.start();
            end = bounded.end();
            quarantinedThrough = Math.max(quarantinedThrough, end);
            if (end <= start
                    || intersectsSortedSpans(
                            boundTargetSpans,
                            start,
                            end)) {
                continue;
            }
            String sourceText = evidence.materialize(start, end);
            work.regionDedupLookups++;
            addRegion(
                    regions,
                    identities,
                    new SequentialMutationClausePlan.OwnedRegion(
                            SequentialMutationClausePlan.RegionKind
                                    .PROVIDER_REMOVED_CONTROL_CLAUSE,
                            start,
                            end,
                            sourceText),
                    evidence);
        }
        regions.sort(Comparator
                .comparingInt(SequentialMutationClausePlan.OwnedRegion
                        ::startOffset)
                .thenComparingInt(SequentialMutationClausePlan.OwnedRegion
                        ::endOffset));
        return List.copyOf(regions);
    }

    private static List<SequentialMutationClausePlan.SourceSpan>
            normalizedBoundTargetSpans(
            List<BoundAction> boundActions
    ) {
        if (boundActions == null || boundActions.isEmpty()) return List.of();
        List<SequentialMutationClausePlan.SourceSpan> raw = new ArrayList<>();
        for (BoundAction bound : boundActions) {
            for (CoordinatedMutationTailParser.TargetOccurrence target
                    : bound.targets()) {
                if (target.envelopeEnd() > target.envelopeStart()) {
                    raw.add(new SequentialMutationClausePlan.SourceSpan(
                            target.envelopeStart(),
                            target.envelopeEnd()));
                }
            }
        }
        raw.sort(Comparator
                .comparingInt(SequentialMutationClausePlan.SourceSpan
                        ::startOffset)
                .thenComparingInt(SequentialMutationClausePlan.SourceSpan
                        ::endOffset));
        List<SequentialMutationClausePlan.SourceSpan> merged =
                new ArrayList<>();
        for (SequentialMutationClausePlan.SourceSpan span : raw) {
            if (merged.isEmpty()) {
                merged.add(span);
                continue;
            }
            SequentialMutationClausePlan.SourceSpan prior = merged.getLast();
            if (span.startOffset() <= prior.endOffset()) {
                merged.set(
                        merged.size() - 1,
                        new SequentialMutationClausePlan.SourceSpan(
                                prior.startOffset(),
                                Math.max(
                                        prior.endOffset(),
                                        span.endOffset())));
            } else {
                merged.add(span);
            }
        }
        return List.copyOf(merged);
    }

    private static RequestLexicalView.Span exclusionBoundedSpan(
            List<SequentialMutationClausePlan.SourceSpan> excluded,
            int start,
            int controlOffset,
            int end
    ) {
        int safeStart = start;
        int safeEnd = end;
        for (SequentialMutationClausePlan.SourceSpan span : excluded) {
            if (span.endOffset() <= controlOffset) {
                safeStart = Math.max(safeStart, span.endOffset());
                continue;
            }
            if (span.startOffset() > controlOffset) {
                safeEnd = Math.min(safeEnd, span.startOffset());
                break;
            }
            return new RequestLexicalView.Span(
                    controlOffset,
                    controlOffset);
        }
        return new RequestLexicalView.Span(safeStart, safeEnd);
    }

    private static boolean intersectsSortedSpans(
            List<SequentialMutationClausePlan.SourceSpan> spans,
            int start,
            int end
    ) {
        int low = 0;
        int high = spans == null ? 0 : spans.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (spans.get(middle).endOffset() <= start) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return spans != null
                && low < spans.size()
                && spans.get(low).startOffset() < end;
    }

    private static boolean hasRejectedSequentialBoundary(
            RequestLexicalView view,
            int from,
            int to
    ) {
        int safeFrom = Math.max(0, from);
        int safeTo = Math.min(
                view.request().length(),
                Math.max(safeFrom, to));
        if (view.containsProviderRemovedControl(safeFrom, safeTo)
                || view.containsLoneCarriageReturn(safeFrom, safeTo)
                || view.containsMalformedDelimiter(safeFrom, safeTo)) {
            return true;
        }
        for (int offset = safeFrom; offset < safeTo; offset++) {
            if (view.isQuoted(offset)) continue;
            char value = view.request().charAt(offset);
            if (value == ':' || value == '\u2013' || value == '\u2014') {
                return true;
            }
            if (!view.isTopLevel(offset)
                    && (value == ','
                            || value == ';'
                            || value == '\n'
                            || value == '\r'
                            || view.isSentenceBoundaryAt(offset))) {
                return true;
            }
        }
        return false;
    }

    private static int clauseEnd(
            SequenceBoundaryIndex boundaries,
            int outputEnd,
            int scanEnd,
            int nextActionStart
    ) {
        int end = scanEnd;
        int rejectedBoundary = boundaries.firstRejectedOffset(
                outputEnd,
                nextActionStart);
        if (rejectedBoundary >= 0) {
            int structuralBoundary = boundaries.firstBoundaryOffset(
                    outputEnd,
                    rejectedBoundary);
            return boundaries.trimmedEnd(
                    outputEnd,
                    structuralBoundary >= 0
                            ? structuralBoundary
                            : nextActionStart < boundaries.requestLength()
                                    ? rejectedBoundary
                                    : rejectedBoundary + 1);
        }
        SequenceBoundary next = nextActionStart < boundaries.requestLength()
                && nextActionStart <= scanEnd
                ? boundaries.firstAfter(outputEnd, nextActionStart)
                : boundaries.firstClauseStop(outputEnd, scanEnd);
        if (next.present()) {
            end = boundaries.isSentenceBoundary(next.startOffset())
                    ? next.endOffset()
                    : next.startOffset();
        }
        return boundaries.trimmedEnd(outputEnd, end);
    }

    private static void addUnclassifiedSequentialRegions(
            RequestLexicalView view,
            List<CoordinatedMutationTailParser.TargetOccurrence> occurrences,
            List<CanonicalMutationClauseMatcher.Action> actions,
            List<SequentialMutationClausePlan.SourceSpan> excluded,
            int anchorOutputEnd,
            List<SequentialMutationClausePlan.OwnedRegion> regions,
            List<SequentialMutationClausePlan.ClauseDecision> decisions,
            Set<RegionIdentity> regionIdentities,
            SequenceBoundaryIndex boundaries,
            List<BoundAction> boundActions,
            EvidenceBudget evidence,
            MutableParseWork work
    ) {
        Set<Integer> boundActionStarts = boundActions.stream()
                .map(bound -> bound.action().start())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<SequentialMutationClausePlan.SourceSpan> covered =
                SequentialMutationClausePlan.normalizedSpans(
                        regions.stream()
                                .map(SequentialMutationClausePlan.OwnedRegion
                                        ::span)
                                .toList(),
                        view.request().length());
        Map<ClauseIdentity, UnclassifiedClause> unclassifiedClauses =
                new LinkedHashMap<>();
        int coverageIndex = 0;
        int nestedActionCursor = 0;
        Map<Integer, CanonicalMutationClauseMatcher.Action>
                lastNestedActionByOwner = new LinkedHashMap<>();
        for (CoordinatedMutationTailParser.TargetOccurrence occurrence
                : occurrences) {
            work.unclassifiedOccurrenceVisits++;
            while (nestedActionCursor < actions.size()
                    && actions.get(nestedActionCursor).end()
                            <= occurrence.envelopeStart()) {
                CanonicalMutationClauseMatcher.Action visited =
                        actions.get(nestedActionCursor++);
                work.nestedActionVisits++;
                if (visited.start() > anchorOutputEnd
                        && !view.isTopLevel(visited.start())) {
                    int owner = view.delimiterOwner(visited.start());
                    if (owner >= 0) {
                        lastNestedActionByOwner.put(owner, visited);
                    }
                }
            }
            while (coverageIndex < covered.size()
                    && covered.get(coverageIndex).endOffset()
                            <= occurrence.envelopeStart()) {
                coverageIndex++;
            }
            boolean alreadyCovered = coverageIndex < covered.size()
                    && covered.get(coverageIndex).startOffset()
                            <= occurrence.envelopeStart()
                    && covered.get(coverageIndex).endOffset()
                            >= occurrence.envelopeEnd();
            if (occurrence.envelopeStart() <= anchorOutputEnd
                    || alreadyCovered) {
                continue;
            }
            if (!view.isTopLevel(occurrence.envelopeStart())) {
                CanonicalMutationClauseMatcher.Action nestedAction =
                        lastNestedActionByOwner.get(
                                view.delimiterOwner(
                                        occurrence.envelopeStart()));
                if (nestedAction != null) {
                    RequestLexicalView.Span nestedSpan = view.trimmedSpan(
                            nestedAction.start(),
                            occurrence.envelopeEnd());
                    String nestedSource = evidence.materialize(nestedSpan);
                    work.regionDedupLookups++;
                    addRegion(
                            regions,
                            regionIdentities,
                            new SequentialMutationClausePlan.OwnedRegion(
                                    nestedAction.family()
                                                    == CanonicalMutationClauseMatcher
                                                            .Family
                                                            .WORKSPACE_OPERATION
                                            ? SequentialMutationClausePlan
                                                    .RegionKind
                                                    .WORKSPACE_OPERATION
                                            : SequentialMutationClausePlan
                                                    .RegionKind
                                                    .CONTENT_MUTATION,
                                    nestedSpan.start(),
                                    nestedSpan.end(),
                                    nestedSource),
                            evidence);
                    SequentialMutationClausePlan.Authority nestedAuthority =
                            nestedAction.scopeKind()
                                            == CanonicalMutationClauseMatcher
                                                    .ScopeKind.PROHIBITED
                                    ? SequentialMutationClausePlan.Authority
                                            .FORBIDDEN
                                    : SequentialMutationClausePlan.Authority
                                            .NON_AUTHORITATIVE;
                    addDecisionIfContained(
                            decisions,
                            nestedAuthority,
                            nestedAction,
                            nestedAction.family(),
                            occurrence,
                            nestedSpan,
                            nestedSource,
                            evidence);
                }
                continue;
            }
            SequenceBoundary boundary = boundaries.latestBefore(
                    anchorOutputEnd,
                    occurrence.envelopeStart());
            if (!boundary.present()) {
                continue;
            }
            work.sentenceEndQueries++;
            int end = view.sentenceEndAfter(occurrence.envelopeEnd());
            RequestLexicalView.Span span = view.trimmedSpan(
                    boundary.clauseStart(),
                    end);
            if (span.end() <= span.start()
                    || intersectsExcluded(excluded, span.start(), span.end())) {
                continue;
            }
            ClauseIdentity clauseIdentity = new ClauseIdentity(
                    span.start(),
                    span.end());
            UnclassifiedClause clause = unclassifiedClauses.get(
                    clauseIdentity);
            if (clause == null) {
                int clauseLength = span.end() - span.start();
                work.clauseClassificationCharacters += clauseLength;
                boolean explicitNonAuthority =
                        unclassifiedNonAuthoritativeClause(
                                view.maskedLower().substring(
                                        span.start(),
                                        span.end()));
                boolean accepted = boundary.strong() || explicitNonAuthority;
                if (!accepted) {
                    clause = UnclassifiedClause.rejected();
                } else {
                    CanonicalMutationClauseMatcher.Action action = firstAction(
                            actions,
                            span.start(),
                            span.end());
                    boolean rejectedAction = action != null
                            && !boundActionStarts.contains(action.start());
                    SequentialMutationClausePlan.RegionKind kind =
                            rejectedAction
                                    ? action.family()
                                                    == CanonicalMutationClauseMatcher
                                                            .Family
                                                            .WORKSPACE_OPERATION
                                            ? SequentialMutationClausePlan
                                                    .RegionKind
                                                    .WORKSPACE_OPERATION
                                            : SequentialMutationClausePlan
                                                    .RegionKind.CONTENT_MUTATION
                                    : explicitNonAuthority
                                            ? SequentialMutationClausePlan.RegionKind
                                                    .NON_AUTHORITATIVE_TARGET_CLAUSE
                                            : SequentialMutationClausePlan.RegionKind
                                                    .UNCLASSIFIED_TARGET_CLAUSE;
                    String sourceText = evidence.materialize(span);
                    clause = new UnclassifiedClause(
                            true,
                            action,
                            rejectedAction,
                            kind,
                            sourceText);
                    work.regionDedupLookups++;
                    addRegion(regions, regionIdentities,
                            new SequentialMutationClausePlan.OwnedRegion(
                                    kind,
                                    span.start(),
                                    span.end(),
                                    sourceText),
                            evidence);
                }
                unclassifiedClauses.put(clauseIdentity, clause);
            }
            if (!clause.accepted()) continue;
            if (clause.rejectedAction()) {
                CanonicalMutationClauseMatcher.Action action = clause.action();
                if (action.start() >= span.start()
                        && action.end() <= occurrence.envelopeEnd()) {
                    evidence.addDecision(
                            decisions,
                            new SequentialMutationClausePlan.ClauseDecision(
                                    occurrence.path(),
                                    SequentialMutationClausePlan.Authority
                                            .NON_AUTHORITATIVE,
                                    action.verb(),
                                    actionFamily(action.family()),
                                    action.start(),
                                    action.end(),
                                    occurrence.envelopeStart(),
                                    span.start(),
                                    occurrence.envelopeEnd(),
                                    span.end(),
                                    clause.sourceText()));
                }
            }
        }
    }

    private static String authorityRequestOutsideExcludedSpans(
            String request,
            List<SequentialMutationClausePlan.SourceSpan> excluded
    ) {
        if (request == null || request.isEmpty()
                || excluded == null || excluded.isEmpty()) {
            return request == null ? "" : request;
        }
        StringBuilder masked = new StringBuilder(request);
        for (SequentialMutationClausePlan.SourceSpan span : excluded) {
            int start = Math.max(0, Math.min(span.startOffset(), masked.length()));
            int end = Math.max(start, Math.min(span.endOffset(), masked.length()));
            for (int index = start; index < end; index++) {
                char value = masked.charAt(index);
                if (value != '\r'
                        && value != '\n'
                        && !terminalBoundaryAtEndOfSpan(
                                masked,
                                index,
                                start,
                                end)) {
                    masked.setCharAt(index, ' ');
                }
            }
        }
        return masked.toString();
    }

    private static boolean terminalBoundaryAtEndOfSpan(
            CharSequence request,
            int index,
            int spanStart,
            int spanEnd
    ) {
        char value = request.charAt(index);
        if (value != '.' && value != '!' && value != '?'
                && value != '\u2026' && value != ';' && value != ',') {
            return false;
        }
        for (int cursor = index + 1; cursor < spanEnd; cursor++) {
            if (!Character.isWhitespace(request.charAt(cursor))) return false;
        }
        for (int cursor = index - 1; cursor >= spanStart; cursor--) {
            if (!Character.isWhitespace(request.charAt(cursor))) return true;
        }
        return false;
    }

    private static boolean unclassifiedNonAuthoritativeClause(String clause) {
        String visible = clause == null ? "" : clause;
        return visible.stripLeading().startsWith("without ")
                || CanonicalMutationClauseMatcher.finiteScopeKind(visible)
                        != CanonicalMutationClauseMatcher.ScopeKind.NONE
                || ADVISORY_CLAUSE_PREFIX.matcher(visible).find();
    }

    private static CanonicalMutationClauseMatcher.Action firstAction(
            List<CanonicalMutationClauseMatcher.Action> actions,
            int start,
            int end
    ) {
        int low = 0;
        int high = actions.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (actions.get(middle).start() < start) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low < actions.size() && actions.get(low).end() <= end
                ? actions.get(low)
                : null;
    }

    private static void addRegion(
            List<SequentialMutationClausePlan.OwnedRegion> regions,
            Set<RegionIdentity> identities,
            SequentialMutationClausePlan.OwnedRegion candidate,
            EvidenceBudget evidence
    ) {
        if (identities.add(new RegionIdentity(
                candidate.kind(),
                candidate.startOffset(),
                candidate.endOffset()))) {
            evidence.addRegion(regions, candidate);
        }
    }

    private record RegionIdentity(
            SequentialMutationClausePlan.RegionKind kind,
            int startOffset,
            int endOffset
    ) {}

    private record ClauseIdentity(int startOffset, int endOffset) {}

    private static final class EvidenceBudget {
        private final String request;
        private final MutableParseWork work;
        private final Map<ClauseIdentity, String> materialized =
                new LinkedHashMap<>();
        private long retainedCharacters;
        private int rows;

        private EvidenceBudget(
                String request,
                int initialRows,
                MutableParseWork work
        ) {
            this.request = request == null ? "" : request;
            this.work = work;
            this.rows = initialRows;
            if (initialRows < 0
                    || initialRows
                            > SequentialMutationClausePlan.MAX_EVIDENCE_ROWS) {
                throw new LexicalEvidenceLimitExceeded();
            }
        }

        private String materialize(RequestLexicalView.Span span) {
            if (span == null) throw new IllegalArgumentException(
                    "evidence span must not be null");
            return materialize(span.start(), span.end());
        }

        private String materialize(int startOffset, int endOffset) {
            if (startOffset < 0
                    || endOffset <= startOffset
                    || endOffset > request.length()) {
                throw new IllegalArgumentException("invalid evidence span");
            }
            ClauseIdentity identity = new ClauseIdentity(
                    startOffset,
                    endOffset);
            String existing = materialized.get(identity);
            if (existing != null) return existing;
            if (rows >= SequentialMutationClausePlan.MAX_EVIDENCE_ROWS) {
                throw new LexicalEvidenceLimitExceeded();
            }
            int length = endOffset - startOffset;
            if (length > SequentialMutationClausePlan
                            .MAX_RETAINED_EVIDENCE_CHARACTERS
                    - retainedCharacters) {
                throw new LexicalEvidenceLimitExceeded();
            }
            String sourceText = request.substring(startOffset, endOffset);
            materialized.put(identity, sourceText);
            retainedCharacters += length;
            work.clauseMaterializationCharacters += length;
            return sourceText;
        }

        private void addDecision(
                List<SequentialMutationClausePlan.ClauseDecision> decisions,
                SequentialMutationClausePlan.ClauseDecision decision
        ) {
            reserveRow();
            decisions.add(decision);
        }

        private void addRegion(
                List<SequentialMutationClausePlan.OwnedRegion> regions,
                SequentialMutationClausePlan.OwnedRegion region
        ) {
            reserveRow();
            regions.add(region);
        }

        private void reserveRow() {
            if (rows >= SequentialMutationClausePlan.MAX_EVIDENCE_ROWS) {
                throw new LexicalEvidenceLimitExceeded();
            }
            rows++;
        }
    }

    private static final class LexicalEvidenceLimitExceeded
            extends RuntimeException {
        private LexicalEvidenceLimitExceeded() {
            super(null, null, false, false);
        }
    }

    private record UnclassifiedClause(
            boolean accepted,
            CanonicalMutationClauseMatcher.Action action,
            boolean rejectedAction,
            SequentialMutationClausePlan.RegionKind kind,
            String sourceText
    ) {
        private static UnclassifiedClause rejected() {
            return new UnclassifiedClause(false, null, false, null, "");
        }
    }

    private static boolean wordAt(String value, int offset, String word) {
        if (offset < 0 || offset + word.length() > value.length()
                || !value.regionMatches(true, offset, word, 0, word.length())) {
            return false;
        }
        int end = offset + word.length();
        boolean left = offset == 0
                || !wordCharacter(value.charAt(offset - 1));
        boolean right = end == value.length()
                || !wordCharacter(value.charAt(end));
        return left && right;
    }

    private static boolean wordCharacter(char value) {
        return Character.isLetterOrDigit(value)
                || value == '_'
                || value == '-'
                || value == '.'
                || value == '/'
                || value == '\\';
    }

    private static SequentialMutationClausePlan.ActionFamily actionFamily(
            CanonicalMutationClauseMatcher.Family family
    ) {
        return family == CanonicalMutationClauseMatcher.Family
                        .WORKSPACE_OPERATION
                ? SequentialMutationClausePlan.ActionFamily.WORKSPACE_OPERATION
                : SequentialMutationClausePlan.ActionFamily.CONTENT_MUTATION;
    }

    private static List<String> normalizedCandidates(Set<String> targets) {
        if (targets == null || targets.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String target : targets) {
            try {
                String canonical = TargetRef.normalizePath(target);
                normalized.add(canonical);
                if (canonical.length() > 1
                        && ".,;:!?".indexOf(
                                canonical.charAt(canonical.length() - 1)) >= 0) {
                    normalized.add(canonical.substring(
                            0,
                            canonical.length() - 1));
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid legacy candidates cannot acquire typed authority.
            }
        }
        return normalized.stream()
                .sorted(Comparator
                        .comparingInt(String::length)
                        .reversed()
                        .thenComparing(String::compareTo))
                .toList();
    }

    private static List<CoordinatedMutationTailParser.TargetOccurrence>
            collapseTerminalPunctuationAlternatives(
                    List<CoordinatedMutationTailParser.TargetOccurrence> raw
            ) {
        if (raw == null || raw.size() < 2) {
            return raw == null ? List.of() : List.copyOf(raw);
        }
        Set<TargetAlternativeIdentity> shorter = raw.stream()
                .map(occurrence -> new TargetAlternativeIdentity(
                        occurrence.path(),
                        occurrence.envelopeStart(),
                        occurrence.envelopeEnd()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return raw.stream().filter(occurrence -> {
            String path = occurrence.path();
            if (!occurrence.quoted()
                    && BARE_ANAPHORA.contains(path.toLowerCase(Locale.ROOT))) {
                return false;
            }
            if (occurrence.quoted() || path.length() < 2) return true;
            char last = path.charAt(path.length() - 1);
            return ".,;:!?".indexOf(last) < 0
                    || !shorter.contains(new TargetAlternativeIdentity(
                            path.substring(0, path.length() - 1),
                            occurrence.envelopeStart(),
                            occurrence.envelopeEnd() - 1));
        }).toList();
    }

    private record TargetAlternativeIdentity(
            String path,
            int startOffset,
            int endOffset
    ) {}

    private static List<SequentialMutationClausePlan.SourceSpan>
            normalizeExcluded(
                    List<SequentialMutationClausePlan.SourceSpan> raw,
                    int requestLength
            ) {
        return SequentialMutationClausePlan.normalizedSpans(
                raw,
                requestLength);
    }

    private static boolean intersectsExcluded(
            List<SequentialMutationClausePlan.SourceSpan> excluded,
            int start,
            int end
    ) {
        for (SequentialMutationClausePlan.SourceSpan span : excluded) {
            if (span.startOffset() >= end) return false;
            if (span.intersects(start, end)) return true;
        }
        return false;
    }

    private record BoundAction(
            CanonicalMutationClauseMatcher.Action action,
            CanonicalMutationClauseMatcher.Family family,
            List<CoordinatedMutationTailParser.TargetOccurrence> targets,
            boolean disjunctiveTargets,
            boolean pronominalProhibition,
            int outputEnd,
            int clauseEnd
    ) {
        private BoundAction {
            targets = List.copyOf(targets);
        }
    }

    private record DirectTargets(
            List<CoordinatedMutationTailParser.TargetOccurrence> targets,
            boolean disjunctive
    ) {
        private DirectTargets {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    /** Immutable top-level boundary index with logarithmic range selection. */
    private static final class SequenceBoundaryIndex {
        private final RequestLexicalView view;
        private final List<BoundaryToken> strong;
        private final List<BoundaryToken> weak;
        private final List<BoundaryToken> clauseStops;
        private final List<BoundaryToken> inheritedScopeResets;
        private final List<SequentialMutationClausePlan.SourceSpan> excluded;
        private final MutableParseWork work;

        private SequenceBoundaryIndex(
                RequestLexicalView view,
                List<BoundaryToken> strong,
                List<BoundaryToken> weak,
                List<BoundaryToken> clauseStops,
                List<BoundaryToken> inheritedScopeResets,
                List<SequentialMutationClausePlan.SourceSpan> excluded,
                MutableParseWork work
        ) {
            this.view = view;
            this.strong = List.copyOf(strong);
            this.weak = List.copyOf(weak);
            this.clauseStops = List.copyOf(clauseStops);
            this.inheritedScopeResets = List.copyOf(inheritedScopeResets);
            this.excluded = List.copyOf(excluded);
            this.work = work;
        }

        static SequenceBoundaryIndex of(
                RequestLexicalView view,
                List<SequentialMutationClausePlan.SourceSpan> excluded,
                MutableParseWork work
        ) {
            List<BoundaryToken> strong = new ArrayList<>();
            List<BoundaryToken> weak = new ArrayList<>();
            List<BoundaryToken> clauseStops = new ArrayList<>();
            List<BoundaryToken> inheritedScopeResets = new ArrayList<>();
            String request = view.request();
            for (int offset = 0; offset < request.length(); offset++) {
                work.boundaryBuildVisits++;
                if (intersectsExcluded(excluded, offset, offset + 1)
                        || view.isQuoted(offset)
                        || !view.isTopLevel(offset)) {
                    continue;
                }
                char value = request.charAt(offset);
                if (value == '(' || value == '[' || value == '{'
                        || value == ':'
                        || value == '\u2013'
                        || value == '\u2014') {
                    clauseStops.add(new BoundaryToken(
                            offset,
                            offset,
                            false));
                    continue;
                }
                if (value == '\r'
                        && offset + 1 < request.length()
                        && request.charAt(offset + 1) == '\n') {
                    BoundaryToken boundary =
                            new BoundaryToken(offset, offset + 2, true);
                    strong.add(boundary);
                    inheritedScopeResets.add(boundary);
                    offset++;
                    continue;
                }
                if (value == '\n'
                        || value == '\u2028'
                        || value == '\u2029'
                        || value == ';'
                        || view.isSentenceBoundaryAt(offset)) {
                    BoundaryToken boundary =
                            new BoundaryToken(offset, offset + 1, true);
                    strong.add(boundary);
                    inheritedScopeResets.add(boundary);
                    continue;
                }
                if (value == ',') {
                    weak.add(new BoundaryToken(offset, offset + 1, false));
                    continue;
                }
                if (wordAt(request, offset, "then")
                        && view.isUnquotedRange(offset, offset + 4)) {
                    strong.add(new BoundaryToken(offset, offset + 4, true));
                    offset += 3;
                }
            }
            for (SequentialMutationClausePlan.SourceSpan span : excluded) {
                BoundaryToken startStop = excludedStartClauseStop(view, span);
                if (startStop != null) {
                    clauseStops.add(startStop);
                }
                if (excludedEndsAtHardBoundary(view, span)) {
                    BoundaryToken endReset = new BoundaryToken(
                            span.endOffset(),
                            span.endOffset(),
                            true);
                    strong.add(endReset);
                    inheritedScopeResets.add(endReset);
                }
            }
            strong.sort(Comparator.comparingInt(BoundaryToken::start));
            weak.sort(Comparator.comparingInt(BoundaryToken::start));
            clauseStops.sort(Comparator.comparingInt(BoundaryToken::start));
            inheritedScopeResets.sort(
                    Comparator.comparingInt(BoundaryToken::start));
            return new SequenceBoundaryIndex(
                    view,
                    strong,
                    weak,
                    clauseStops,
                    inheritedScopeResets,
                    excluded,
                    work);
        }

        private static BoundaryToken excludedStartClauseStop(
                RequestLexicalView view,
                SequentialMutationClausePlan.SourceSpan span
        ) {
            int excludedStart = span.startOffset();
            int cursor = excludedStart;
            while (cursor > 0
                    && horizontalBoundaryWhitespace(
                            view.request().charAt(cursor - 1))) {
                cursor--;
            }
            int punctuation = cursor - 1;
            if (punctuation < 0
                    || view.request().charAt(punctuation) != ','
                    || view.isQuoted(punctuation)
                    || !view.isTopLevel(punctuation)) {
                return null;
            }
            return new BoundaryToken(punctuation, excludedStart, true);
        }

        private static boolean excludedEndsAtHardBoundary(
                RequestLexicalView view,
                SequentialMutationClausePlan.SourceSpan span
        ) {
            int cursor = span.endOffset() - 1;
            while (cursor >= span.startOffset()
                    && horizontalBoundaryWhitespace(
                            view.request().charAt(cursor))) {
                cursor--;
            }
            if (cursor < span.startOffset()
                    || view.isQuoted(cursor)
                    || !view.isTopLevel(cursor)) {
                return false;
            }
            char value = view.request().charAt(cursor);
            return value == ';'
                    || value == '\n'
                    || value == '\u2028'
                    || value == '\u2029'
                    || view.isSentenceBoundaryAt(cursor);
        }

        private static boolean horizontalBoundaryWhitespace(char value) {
            return value == ' ' || value == '\t' || value == '\f';
        }

        boolean hasInheritedScopeReset(int from, int to) {
            work.boundaryQueries++;
            int safeFrom = Math.max(0, from);
            int safeTo = Math.min(
                    view.request().length(),
                    Math.max(safeFrom, to));
            return firstInRange(
                    inheritedScopeResets,
                    safeFrom,
                    safeTo) != null;
        }

        SequenceBoundary stableHardResetBefore(int from, int to) {
            work.boundaryQueries++;
            int safeFrom = Math.max(0, from);
            int safeTo = Math.min(
                    view.request().length(),
                    Math.max(safeFrom, to));
            BoundaryToken token = lastInRange(
                    inheritedScopeResets,
                    safeFrom,
                    safeTo);
            if (token == null) return SequenceBoundary.none();
            if (view.containsMalformedDelimiter(safeFrom, token.start())
                    || view.containsLoneCarriageReturn(safeFrom, token.start())
                            && !view.containsProviderRemovedControl(
                                    safeFrom,
                                    token.start())
                    || !stableProviderControlResetPrefix(
                            safeFrom,
                            token.start())) {
                return SequenceBoundary.none();
            }
            SequenceBoundary boundary = materialize(token, safeTo);
            return invalidRange(
                            boundary.startOffset(),
                            boundary.clauseStart())
                    ? SequenceBoundary.none()
                    : boundary;
        }

        private boolean stableProviderControlResetPrefix(int from, int to) {
            if (!view.containsProviderRemovedControl(from, to)) return true;
            for (int offset = Math.max(0, from);
                    offset < Math.min(view.request().length(), to);
                    offset++) {
                if (view.containsProviderRemovedControl(offset, offset + 1)) {
                    continue;
                }
                char value = view.request().charAt(offset);
                if (Character.isWhitespace(value)
                        || value == '.'
                        || value == '?'
                        || value == '!'
                        || value == ';') {
                    continue;
                }
                return false;
            }
            return true;
        }

        int rejectionSearchStart(int from, int to) {
            SequenceBoundary stableReset = stableHardResetBefore(from, to);
            return stableReset.present()
                    ? stableReset.clauseStart()
                    : Math.max(0, from);
        }

        SequenceBoundary latestBefore(int from, int to) {
            work.boundaryQueries++;
            int safeFrom = Math.max(0, from);
            int safeTo = Math.min(view.request().length(), Math.max(safeFrom, to));
            safeFrom = suffixStartOutsideExcluded(safeFrom, safeTo);
            if (invalidRange(safeFrom, safeTo)) return SequenceBoundary.none();
            BoundaryToken latestStrong = lastInRange(strong, safeFrom, safeTo);
            BoundaryToken selected = latestStrong == null
                    ? firstInRange(weak, safeFrom, safeTo)
                    : latestStrong;
            return materialize(selected, safeTo);
        }

        SequenceBoundary firstAfter(int from, int to) {
            work.boundaryQueries++;
            int safeFrom = Math.max(0, from);
            int safeTo = Math.min(view.request().length(), Math.max(safeFrom, to));
            int validationEnd = prefixEndBeforeExcluded(safeFrom, safeTo);
            if (invalidRange(safeFrom, validationEnd)) {
                return SequenceBoundary.none();
            }
            BoundaryToken firstStrong = firstInRange(strong, safeFrom, safeTo);
            BoundaryToken firstWeak = firstInRange(weak, safeFrom, safeTo);
            BoundaryToken firstStop = firstInRange(
                    clauseStops,
                    safeFrom,
                    safeTo);
            BoundaryToken selected = earliest(
                    earliest(firstStrong, firstWeak),
                    firstStop);
            return materialize(selected, safeTo);
        }

        SequenceBoundary firstHardResetAfter(int from, int to) {
            work.boundaryQueries++;
            int safeFrom = Math.max(0, from);
            int safeTo = Math.min(
                    view.request().length(),
                    Math.max(safeFrom, to));
            return materialize(
                    firstInRange(inheritedScopeResets, safeFrom, safeTo),
                    safeTo);
        }

        private static BoundaryToken earliest(
                BoundaryToken left,
                BoundaryToken right
        ) {
            if (left == null) return right;
            if (right == null) return left;
            return left.start() <= right.start() ? left : right;
        }

        int requestLength() {
            return view.request().length();
        }

        SequenceBoundary firstClauseStop(int from, int to) {
            int safeFrom = Math.max(0, from);
            int safeTo = Math.min(
                    view.request().length(),
                    Math.max(safeFrom, to));
            return materialize(
                    firstInRange(clauseStops, safeFrom, safeTo),
                    safeTo);
        }

        int firstBoundaryOffset(int from, int to) {
            int safeFrom = Math.max(0, from);
            int safeTo = Math.min(
                    view.request().length(),
                    Math.max(safeFrom, to));
            BoundaryToken selected = earliest(
                    earliest(
                            firstInRange(strong, safeFrom, safeTo),
                            firstInRange(weak, safeFrom, safeTo)),
                    firstInRange(clauseStops, safeFrom, safeTo));
            return selected == null ? -1 : selected.start();
        }

        int clampForwardScanEnd(int from, int end) {
            int safeFrom = Math.max(0, from);
            int safeEnd = Math.min(
                    view.request().length(),
                    Math.max(safeFrom, end));
            for (SequentialMutationClausePlan.SourceSpan span : excluded) {
                if (span.endOffset() <= safeFrom) continue;
                if (span.startOffset() >= safeEnd) break;
                return Math.max(safeFrom, span.startOffset());
            }
            return safeEnd;
        }

        boolean isSentenceBoundary(int offset) {
            return view.isSentenceBoundaryAt(offset);
        }

        int trimmedEnd(int start, int end) {
            return view.trimmedSpan(start, end).end();
        }

        int firstRejectedOffset(int from, int to) {
            int safeFrom = Math.max(0, from);
            int safeTo = Math.min(
                    view.request().length(),
                    Math.max(safeFrom, to));
            for (int offset = safeFrom; offset < safeTo; offset++) {
                SequentialMutationClausePlan.SourceSpan excludedSpan =
                        excludedContaining(offset);
                if (excludedSpan != null) {
                    offset = excludedSpan.endOffset() - 1;
                    continue;
                }
                char value = view.request().charAt(offset);
                if (view.containsProviderRemovedControl(offset, offset + 1)
                        || view.containsMalformedDelimiter(offset, offset + 1)
                        || value == '\r'
                                && (offset + 1 >= view.request().length()
                                        || view.request().charAt(offset + 1)
                                                != '\n')) {
                    return offset;
                }
            }
            return -1;
        }

        private int suffixStartOutsideExcluded(int from, int to) {
            int start = from;
            for (SequentialMutationClausePlan.SourceSpan span : excluded) {
                if (span.startOffset() >= to) break;
                if (span.endOffset() > start) start = span.endOffset();
            }
            return Math.min(start, to);
        }

        private int prefixEndBeforeExcluded(int from, int to) {
            for (SequentialMutationClausePlan.SourceSpan span : excluded) {
                if (span.endOffset() <= from) continue;
                if (span.startOffset() >= to) break;
                return Math.max(from, span.startOffset());
            }
            return to;
        }

        private SequentialMutationClausePlan.SourceSpan excludedContaining(
                int offset
        ) {
            for (SequentialMutationClausePlan.SourceSpan span : excluded) {
                if (span.startOffset() > offset) return null;
                if (span.endOffset() > offset) return span;
            }
            return null;
        }

        private boolean invalidRange(int from, int to) {
            return view.containsProviderRemovedControl(from, to)
                    || view.containsLoneCarriageReturn(from, to)
                    || view.containsMalformedDelimiter(from, to);
        }

        private SequenceBoundary materialize(BoundaryToken token, int to) {
            if (token == null) return SequenceBoundary.none();
            int clauseStart = token.end();
            while (clauseStart < to
                    && Character.isWhitespace(
                            view.request().charAt(clauseStart))) {
                clauseStart++;
            }
            return new SequenceBoundary(
                    token.start(),
                    token.end(),
                    clauseStart,
                    token.strong());
        }

        private static BoundaryToken firstInRange(
                List<BoundaryToken> tokens,
                int from,
                int to
        ) {
            int index = lowerBound(tokens, from);
            return index < tokens.size() && tokens.get(index).start() < to
                    ? tokens.get(index)
                    : null;
        }

        private static BoundaryToken lastInRange(
                List<BoundaryToken> tokens,
                int from,
                int to
        ) {
            int index = lowerBound(tokens, to) - 1;
            return index >= 0 && tokens.get(index).start() >= from
                    ? tokens.get(index)
                    : null;
        }

        private static int lowerBound(List<BoundaryToken> tokens, int offset) {
            int low = 0;
            int high = tokens.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (tokens.get(middle).start() < offset) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }
    }

    private record BoundaryToken(int start, int end, boolean strong) {}

    record MeasuredPlan(
            SequentialMutationClausePlan plan,
            ParseWorkSnapshot work
    ) {}

    record ParseWorkSnapshot(
            long inputCharacters,
            long targetOccurrences,
            long actions,
            long boundaryBuildVisits,
            long boundaryQueries,
            long directTargetVisits,
            long unclassifiedOccurrenceVisits,
            long sentenceEndQueries,
            long regionDedupLookups,
            long nestedActionVisits,
            long clauseClassificationCharacters,
            long clauseMaterializationCharacters
    ) {
        long totalVisits() {
            return boundaryBuildVisits
                    + boundaryQueries
                    + directTargetVisits
                    + unclassifiedOccurrenceVisits
                    + sentenceEndQueries
                    + regionDedupLookups
                    + nestedActionVisits
                    + clauseClassificationCharacters
                    + clauseMaterializationCharacters;
        }
    }

    private static final class MutableParseWork {
        private long inputCharacters;
        private long targetOccurrences;
        private long actions;
        private long boundaryBuildVisits;
        private long boundaryQueries;
        private long directTargetVisits;
        private long unclassifiedOccurrenceVisits;
        private long sentenceEndQueries;
        private long regionDedupLookups;
        private long nestedActionVisits;
        private long clauseClassificationCharacters;
        private long clauseMaterializationCharacters;

        private ParseWorkSnapshot snapshot() {
            return new ParseWorkSnapshot(
                    inputCharacters,
                    targetOccurrences,
                    actions,
                    boundaryBuildVisits,
                    boundaryQueries,
                    directTargetVisits,
                    unclassifiedOccurrenceVisits,
                    sentenceEndQueries,
                    regionDedupLookups,
                    nestedActionVisits,
                    clauseClassificationCharacters,
                    clauseMaterializationCharacters);
        }
    }

    private record SequenceBoundary(
            int startOffset,
            int endOffset,
            int clauseStart,
            boolean strong
    ) {
        private static SequenceBoundary none() {
            return new SequenceBoundary(-1, -1, -1, false);
        }

        private boolean present() {
            return startOffset >= 0;
        }
    }
}
