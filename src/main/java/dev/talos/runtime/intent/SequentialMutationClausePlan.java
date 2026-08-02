package dev.talos.runtime.intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Exact source evidence for independent mutation clauses after an already
 * bound top-level mutation clause.
 *
 * <p>Occurrence decisions are deliberately not collapsed by path. A request
 * may mention the same target in more than one independent clause, and every
 * occurrence remains available for trace and persistence. Consumers that
 * need one compiled role use {@link #effectiveDecision(String)}; its
 * precedence is deterministic and never reconstructs authority from prose.
 */
public record SequentialMutationClausePlan(
        String canonicalRequest,
        List<SourceSpan> excludedSpans,
        List<ClauseDecision> occurrenceDecisions,
        List<OwnedRegion> ownedRegions,
        AuthorityLimit authorityLimit
) {

    static final int MAX_EXCLUDED_SPANS = 4_096;
    static final int MAX_OCCURRENCE_DECISIONS = 4_096;
    static final int MAX_OWNED_REGIONS = 4_096;
    public static final int MAX_EVIDENCE_ROWS = 4_096;
    static final long MAX_RETAINED_EVIDENCE_CHARACTERS = 1_048_576L;

    public enum Authority {
        REQUIRED,
        OPTIONAL,
        NON_AUTHORITATIVE,
        WORKSPACE_OPERATION_BLOCKED,
        FORBIDDEN
    }

    public enum ActionFamily {
        CONTENT_MUTATION,
        WORKSPACE_OPERATION
    }

    public enum RegionKind {
        CONTENT_MUTATION,
        WORKSPACE_OPERATION,
        PROVIDER_REMOVED_CONTROL_CLAUSE,
        TARGET_OUTPUT_LIMIT_QUARANTINE,
        TARGET_OCCURRENCE_LIMIT_QUARANTINE,
        LEXICAL_EVIDENCE_LIMIT_QUARANTINE,
        NON_AUTHORITATIVE_TARGET_CLAUSE,
        UNCLASSIFIED_TARGET_CLAUSE
    }

    /** Bounded status proving that lexical authority analysis stopped safely. */
    public record AuthorityLimit(RegionKind kind) {
        public AuthorityLimit {
            if (!isAuthorityLimitKind(kind)) {
                throw new IllegalArgumentException(
                        "invalid sequential authority limit kind");
            }
        }
    }

    /** One canonical-request span owned by another typed parser. */
    public record SourceSpan(int startOffset, int endOffset) {
        public SourceSpan {
            if (startOffset < 0 || endOffset <= startOffset) {
                throw new IllegalArgumentException("invalid excluded source span");
            }
        }

        public boolean intersects(int start, int end) {
            return start < endOffset && end > startOffset;
        }
    }

    /**
     * One exact post-boundary region owned by this plan.
     *
     * <p>An unclassified target clause is retained only to prove that a
     * sequential boundary exists and force roleful compilation. It grants no
     * authority and must not be masked as a recognized mutation clause.
     */
    public record OwnedRegion(
            RegionKind kind,
            int startOffset,
            int endOffset,
            String sourceText
    ) {
        public OwnedRegion {
            if (kind == null || startOffset < 0 || endOffset <= startOffset) {
                throw new IllegalArgumentException("invalid owned source region");
            }
            sourceText = sourceText == null ? "" : sourceText;
            if (sourceText.isEmpty()) {
                throw new IllegalArgumentException(
                        "owned source region must retain exact source text");
            }
        }

        public boolean masksLegacyMutationScan() {
            return kind == RegionKind.CONTENT_MUTATION
                    || kind == RegionKind.WORKSPACE_OPERATION
                    || kind == RegionKind.PROVIDER_REMOVED_CONTROL_CLAUSE
                    || kind == RegionKind.TARGET_OUTPUT_LIMIT_QUARANTINE
                    || kind == RegionKind.TARGET_OCCURRENCE_LIMIT_QUARANTINE
                    || kind == RegionKind.LEXICAL_EVIDENCE_LIMIT_QUARANTINE
                    || kind == RegionKind.NON_AUTHORITATIVE_TARGET_CLAUSE;
        }

        public SourceSpan span() {
            return new SourceSpan(startOffset, endOffset);
        }
    }

    /**
     * One exact target occurrence and the authority established by its own
     * top-level clause.
     */
    public static final class ClauseDecision {
        private final String path;
        private final Authority authority;
        private final String action;
        private final ActionFamily actionFamily;
        private final int actionStartOffset;
        private final int actionEndOffset;
        private final int targetStartOffset;
        private final int startOffset;
        private final int authorityEndOffset;
        private final int endOffset;
        private final String sourceText;

        ClauseDecision(
                String path,
                Authority authority,
                String action,
                ActionFamily actionFamily,
                int actionStartOffset,
                int actionEndOffset,
                int targetStartOffset,
                int startOffset,
                int authorityEndOffset,
                int endOffset,
                String sourceText
        ) {
            this.path = TargetRef.normalizePath(path);
            if (authority == null) {
                throw new IllegalArgumentException("authority must not be null");
            }
            this.authority = authority;
            this.action = action == null ? "" : action.strip();
            if (this.action.isBlank()) {
                throw new IllegalArgumentException("decision action must not be blank");
            }
            if (actionFamily == null) {
                throw new IllegalArgumentException(
                        "decision action family must not be null");
            }
            if (actionFamily == ActionFamily.WORKSPACE_OPERATION
                    && authority != Authority.WORKSPACE_OPERATION_BLOCKED
                    && authority != Authority.NON_AUTHORITATIVE
                    && authority != Authority.FORBIDDEN) {
                throw new IllegalArgumentException(
                        "workspace operations cannot receive mutation authority");
            }
            if (authority == Authority.WORKSPACE_OPERATION_BLOCKED
                    && actionFamily != ActionFamily.WORKSPACE_OPERATION) {
                throw new IllegalArgumentException(
                        "blocked workspace authority requires a workspace action");
            }
            this.actionFamily = actionFamily;
            this.actionStartOffset = actionStartOffset;
            this.actionEndOffset = actionEndOffset;
            this.targetStartOffset = targetStartOffset;
            this.startOffset = startOffset;
            this.authorityEndOffset = authorityEndOffset;
            this.endOffset = endOffset;
            this.sourceText = sourceText == null ? "" : sourceText;
            if (startOffset < 0
                    || actionStartOffset < startOffset
                    || actionEndOffset <= actionStartOffset
                    || actionEndOffset - actionStartOffset != this.action.length()
                    || targetStartOffset < startOffset
                    || targetStartOffset >= authorityEndOffset
                    || authorityEndOffset < actionEndOffset
                    || endOffset < authorityEndOffset
                    || this.sourceText.isEmpty()) {
                throw new IllegalArgumentException("invalid decision source span");
            }
        }

        public String path() { return path; }
        public Authority authority() { return authority; }
        public String action() { return action; }
        public ActionFamily actionFamily() { return actionFamily; }
        public int actionStartOffset() { return actionStartOffset; }
        public int actionEndOffset() { return actionEndOffset; }
        public int targetStartOffset() { return targetStartOffset; }
        public int startOffset() { return startOffset; }
        public int authorityEndOffset() { return authorityEndOffset; }
        public int endOffset() { return endOffset; }
        public String sourceText() { return sourceText; }

        public IntentDerivation derivation() {
            return new IntentDerivation(
                    TargetSource.USER_REQUEST,
                    switch (authority) {
                        case REQUIRED -> "explicit-sequential-mutation-target";
                        case OPTIONAL -> "optional-mutation-target";
                        case NON_AUTHORITATIVE ->
                                "non-authoritative-sequential-mutation-mention";
                        case WORKSPACE_OPERATION_BLOCKED ->
                                "sequential-workspace-operation-awaits-typed-owner";
                        case FORBIDDEN ->
                                "explicit-sequential-mutation-prohibition";
                    },
                    startOffset,
                    authorityEndOffset,
                    endOffset,
                    sourceText,
                    1.0);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ClauseDecision that)) return false;
            return actionStartOffset == that.actionStartOffset
                    && actionEndOffset == that.actionEndOffset
                    && targetStartOffset == that.targetStartOffset
                    && startOffset == that.startOffset
                    && authorityEndOffset == that.authorityEndOffset
                    && endOffset == that.endOffset
                    && path.equals(that.path)
                    && authority == that.authority
                    && action.equals(that.action)
                    && actionFamily == that.actionFamily
                    && sourceText.equals(that.sourceText);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    path,
                    authority,
                    action,
                    actionFamily,
                    actionStartOffset,
                    actionEndOffset,
                    targetStartOffset,
                    startOffset,
                    authorityEndOffset,
                    endOffset,
                    sourceText);
        }

        @Override
        public String toString() {
            return "ClauseDecision[path=" + path
                    + ", authority=" + authority
                    + ", action=" + action
                    + ", actionFamily=" + actionFamily
                    + ", actionStartOffset=" + actionStartOffset
                    + ", actionEndOffset=" + actionEndOffset
                    + ", targetStartOffset=" + targetStartOffset
                    + ", startOffset=" + startOffset
                    + ", authorityEndOffset=" + authorityEndOffset
                    + ", endOffset=" + endOffset
                    + ", sourceText=" + sourceText + "]";
        }
    }

    public SequentialMutationClausePlan(
            String canonicalRequest,
            List<SourceSpan> excludedSpans,
            List<ClauseDecision> occurrenceDecisions,
            List<OwnedRegion> ownedRegions
    ) {
        this(
                canonicalRequest,
                excludedSpans,
                occurrenceDecisions,
                ownedRegions,
                null);
    }

    public SequentialMutationClausePlan {
        canonicalRequest = canonicalRequest == null ? "" : canonicalRequest;
        excludedSpans = normalizedSpans(excludedSpans, canonicalRequest.length());
        occurrenceDecisions = boundedEvidenceCopy(
                occurrenceDecisions,
                MAX_OCCURRENCE_DECISIONS,
                "occurrence decisions");
        ownedRegions = boundedEvidenceCopy(
                ownedRegions,
                MAX_OWNED_REGIONS,
                "owned regions");
        requireCombinedEvidenceRows(
                excludedSpans,
                occurrenceDecisions,
                ownedRegions,
                authorityLimit);
        requireBoundedEvidenceCharacters(occurrenceDecisions, ownedRegions);

        validateOwnedRegions(canonicalRequest, excludedSpans, ownedRegions);
        validateAuthorityLimitQuarantine(
                canonicalRequest,
                excludedSpans,
                occurrenceDecisions,
                ownedRegions,
                authorityLimit);

        if (!occurrenceDecisions.isEmpty()) {
            RequestLexicalView lexicalView = RequestLexicalView.of(canonicalRequest);
            List<String> decisionPaths = occurrenceDecisions.stream()
                .map(ClauseDecision::path)
                .distinct()
                .toList();
            Set<TargetSpanIdentity> exactTargets =
                CoordinatedMutationTailParser.targetOccurrences(
                        lexicalView,
                        decisionPaths,
                        0,
                        canonicalRequest.length()).stream()
                        .map(occurrence -> new TargetSpanIdentity(
                                occurrence.path(),
                                occurrence.envelopeStart(),
                                occurrence.envelopeEnd()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Map<ActionValidationIdentity, Boolean> actionValidation =
                new LinkedHashMap<>();

            ClauseDecision priorDecision = null;
            Set<TargetSpanIdentity> identities = new LinkedHashSet<>();
            int excludedCursor = 0;
            int regionCursor = 0;
            EnumMap<RegionKind, Integer> maximumRegionEnd =
                new EnumMap<>(RegionKind.class);
            for (ClauseDecision decision : occurrenceDecisions) {
            if (decision.endOffset() > canonicalRequest.length()
                    || !matchesExactSource(
                            canonicalRequest,
                            decision.startOffset(),
                            decision.endOffset(),
                            decision.sourceText())
                    || !exactTargets.contains(new TargetSpanIdentity(
                            decision.path(),
                            decision.targetStartOffset(),
                            decision.authorityEndOffset()))
                    || !canonicalRequest.regionMatches(
                            true,
                            decision.actionStartOffset(),
                            decision.action(),
                            0,
                            decision.action().length())
                    || !hasExactTypedAction(
                            lexicalView,
                            canonicalRequest,
                            decision,
                            actionValidation)) {
                throw new IllegalArgumentException(
                        "decision evidence must be exact canonical-request bytes: "
                                + decision);
            }
            if (priorDecision != null
                    && SOURCE_ORDER.compare(priorDecision, decision) > 0) {
                throw new IllegalArgumentException(
                        "occurrence decisions must remain in source order");
            }
            priorDecision = decision;
            excludedCursor = advancePastExcluded(
                    excludedSpans,
                    excludedCursor,
                    decision.startOffset());
            if (intersectsAtCursor(
                    excludedSpans,
                    excludedCursor,
                    decision.startOffset(),
                    decision.endOffset())) {
                throw new IllegalArgumentException(
                        "sequential decisions cannot overlap excluded source spans");
            }
            TargetSpanIdentity identity = new TargetSpanIdentity(
                    decision.path(),
                    decision.targetStartOffset(),
                    decision.authorityEndOffset());
            if (!identities.add(identity)) {
                throw new IllegalArgumentException(
                        "one decision is allowed per exact target occurrence");
            }

            while (regionCursor < ownedRegions.size()
                    && ownedRegions.get(regionCursor).startOffset()
                            <= decision.startOffset()) {
                OwnedRegion region = ownedRegions.get(regionCursor++);
                maximumRegionEnd.merge(
                        region.kind(),
                        region.endOffset(),
                        Math::max);
            }
            RegionKind expectedKind = decision.actionFamily()
                            == ActionFamily.WORKSPACE_OPERATION
                    ? RegionKind.WORKSPACE_OPERATION
                    : RegionKind.CONTENT_MUTATION;
            if (maximumRegionEnd.getOrDefault(expectedKind, -1)
                    < decision.endOffset()) {
                throw new IllegalArgumentException(
                        "decision evidence must have one containing typed region: "
                                + decision);
            }
            }
        }
    }

    private static void requireCombinedEvidenceRows(
            List<SourceSpan> excludedSpans,
            List<ClauseDecision> decisions,
            List<OwnedRegion> regions,
            AuthorityLimit authorityLimit
    ) {
        long rows = authorityLimit == null
                ? (long) excludedSpans.size() + decisions.size() + regions.size()
                : 1L;
        if (rows > MAX_EVIDENCE_ROWS) {
            throw new IllegalArgumentException(
                    "combined lexical evidence exceeds its row bound");
        }
    }

    private static void validateAuthorityLimitQuarantine(
            String canonicalRequest,
            List<SourceSpan> excludedSpans,
            List<ClauseDecision> decisions,
            List<OwnedRegion> regions,
            AuthorityLimit authorityLimit
    ) {
        Set<RegionKind> limitKinds = regions.stream()
                .map(OwnedRegion::kind)
                .filter(SequentialMutationClausePlan::isAuthorityLimitKind)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!limitKinds.isEmpty()) {
            throw new IllegalArgumentException(
                    "authority limits must use bounded plan status, not source regions");
        }
        if (authorityLimit == null) return;
        if (!decisions.isEmpty() || !regions.isEmpty()) {
            throw new IllegalArgumentException(
                    "authority-limit quarantine cannot retain partial clause decisions");
        }
        if (canonicalRequest.isEmpty()) {
            throw new IllegalArgumentException(
                    "authority-limit quarantine requires a nonempty canonical request");
        }
    }

    private static void validateOwnedRegions(
            String canonicalRequest,
            List<SourceSpan> excludedSpans,
            List<OwnedRegion> ownedRegions
    ) {
        if (ownedRegions.isEmpty()) return;
        RequestLexicalView lexicalView = RequestLexicalView.of(canonicalRequest);
        OwnedRegion prior = null;
        Set<RegionSpanIdentity> identities = new HashSet<>();
        int excludedCursor = 0;
        for (OwnedRegion region : ownedRegions) {
            if (region.endOffset() > canonicalRequest.length()
                    || !matchesExactSource(
                            canonicalRequest,
                            region.startOffset(),
                            region.endOffset(),
                            region.sourceText())
                    || prior != null
                            && REGION_SOURCE_ORDER.compare(prior, region) > 0) {
                throw new IllegalArgumentException(
                        "owned regions must be source-ordered exact request bytes");
            }
            if (region.kind() == RegionKind.PROVIDER_REMOVED_CONTROL_CLAUSE
                    && !lexicalView.containsProviderRemovedControl(
                            region.startOffset(),
                            region.endOffset())
                    && !lexicalView.containsLoneCarriageReturn(
                            region.startOffset(),
                            region.endOffset())) {
                throw new IllegalArgumentException(
                        "control-tainted regions must retain an exact invalid control");
            }
            if (!identities.add(new RegionSpanIdentity(
                    region.startOffset(),
                    region.endOffset()))) {
                throw new IllegalArgumentException(
                        "one owned region is allowed per exact source span");
            }
            excludedCursor = advancePastExcluded(
                    excludedSpans,
                    excludedCursor,
                    region.startOffset());
            if (intersectsAtCursor(
                    excludedSpans,
                    excludedCursor,
                    region.startOffset(),
                    region.endOffset())) {
                throw new IllegalArgumentException(
                        "owned regions cannot overlap excluded source spans");
            }
            prior = region;
        }
    }

    private static boolean matchesExactSource(
            String canonicalRequest,
            int startOffset,
            int endOffset,
            String sourceText
    ) {
        return sourceText.length() == endOffset - startOffset
                && canonicalRequest.regionMatches(
                        startOffset,
                        sourceText,
                        0,
                        sourceText.length());
    }

    private static int advancePastExcluded(
            List<SourceSpan> excludedSpans,
            int cursor,
            int startOffset
    ) {
        int current = cursor;
        while (current < excludedSpans.size()
                && excludedSpans.get(current).endOffset() <= startOffset) {
            current++;
        }
        return current;
    }

    private static boolean intersectsAtCursor(
            List<SourceSpan> excludedSpans,
            int cursor,
            int startOffset,
            int endOffset
    ) {
        return cursor < excludedSpans.size()
                && excludedSpans.get(cursor).intersects(startOffset, endOffset);
    }

    private static <T> List<T> boundedEvidenceCopy(
            List<T> raw,
            int maximumEntries,
            String label
    ) {
        if (raw == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        if (raw.size() > maximumEntries) {
            throw new IllegalArgumentException("too many " + label);
        }
        ArrayList<T> copy = new ArrayList<>(raw.size());
        for (T value : raw) {
            if (value == null) {
                throw new IllegalArgumentException(
                        label + " must not contain null entries");
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    private static void requireBoundedEvidenceCharacters(
            List<ClauseDecision> decisions,
            List<OwnedRegion> regions
    ) {
        long retained = 0;
        Set<EvidenceSpanIdentity> retainedSpans = new HashSet<>();
        for (ClauseDecision decision : decisions) {
            EvidenceSpanIdentity identity = new EvidenceSpanIdentity(
                    decision.startOffset(),
                    decision.endOffset(),
                    decision.sourceText());
            if (retainedSpans.add(identity)) {
                retained = addEvidenceCharacters(retained, decision.sourceText());
            }
        }
        for (OwnedRegion region : regions) {
            EvidenceSpanIdentity identity = new EvidenceSpanIdentity(
                    region.startOffset(),
                    region.endOffset(),
                    region.sourceText());
            if (retainedSpans.add(identity)) {
                retained = addEvidenceCharacters(retained, region.sourceText());
            }
        }
    }

    private static boolean isAuthorityLimitKind(RegionKind kind) {
        return kind == RegionKind.TARGET_OUTPUT_LIMIT_QUARANTINE
                || kind == RegionKind.TARGET_OCCURRENCE_LIMIT_QUARANTINE
                || kind == RegionKind.LEXICAL_EVIDENCE_LIMIT_QUARANTINE;
    }

    static boolean exceedsRetainedEvidenceCharacters(
            List<ClauseDecision> decisions,
            List<OwnedRegion> regions
    ) {
        try {
            requireBoundedEvidenceCharacters(
                    decisions == null ? List.of() : decisions,
                    regions == null ? List.of() : regions);
            return false;
        } catch (IllegalArgumentException e) {
            return "sequential plan retains too many evidence characters"
                    .equals(e.getMessage());
        }
    }

    private record EvidenceSpanIdentity(
            int startOffset,
            int endOffset,
            String sourceText
    ) {}

    private static long addEvidenceCharacters(long retained, String value) {
        if (value.length() > MAX_RETAINED_EVIDENCE_CHARACTERS - retained) {
            throw new IllegalArgumentException(
                    "sequential plan retains too many evidence characters");
        }
        return retained + value.length();
    }

    private static boolean hasExactTypedAction(
            RequestLexicalView view,
            String canonicalRequest,
            ClauseDecision decision,
            Map<ActionValidationIdentity, Boolean> cache
    ) {
        ActionValidationIdentity identity = new ActionValidationIdentity(
                decision.action(),
                decision.actionFamily(),
                decision.actionStartOffset(),
                decision.actionEndOffset(),
                familyEvidenceEnd(decision));
        return cache.computeIfAbsent(identity, ignored ->
                CanonicalMutationClauseMatcher.actions(
                        view,
                        decision.actionStartOffset(),
                        decision.actionEndOffset()).stream().anyMatch(action ->
                        action.start() == decision.actionStartOffset()
                                && action.end() == decision.actionEndOffset()
                                && action.verb().equalsIgnoreCase(
                                        decision.action())
                                && CanonicalMutationClauseMatcher
                                        .actionMatchesFamily(
                                                action,
                                                decision.actionFamily()
                                                                == ActionFamily
                                                                        .WORKSPACE_OPERATION
                                                        ? CanonicalMutationClauseMatcher
                                                                .Family
                                                                .WORKSPACE_OPERATION
                                                        : CanonicalMutationClauseMatcher
                                                                .Family
                                                                .CONTENT_MUTATION,
                                                 canonicalRequest.substring(
                                                         decision.actionEndOffset(),
                                                         familyEvidenceEnd(
                                                                 decision)))));
    }

    private static int familyEvidenceEnd(ClauseDecision decision) {
        return decision.actionFamily() == ActionFamily.CONTENT_MUTATION
                ? decision.authorityEndOffset()
                : decision.endOffset();
    }

    private record TargetSpanIdentity(
            String path,
            int startOffset,
            int endOffset
    ) {}

    private record ActionValidationIdentity(
            String action,
            ActionFamily family,
            int startOffset,
            int endOffset,
            int sourceEndOffset
    ) {}

    public static SequentialMutationClausePlan empty(String canonicalRequest) {
        return empty(canonicalRequest, List.of());
    }

    public static SequentialMutationClausePlan empty(
            String canonicalRequest,
            List<SourceSpan> excludedSpans
    ) {
        return new SequentialMutationClausePlan(
                canonicalRequest,
                excludedSpans,
                List.of(),
                List.of(),
                null);
    }

    static SequentialMutationClausePlan authorityLimit(
            String canonicalRequest,
            List<SourceSpan> excludedSpans,
            RegionKind kind
    ) {
        return new SequentialMutationClausePlan(
                canonicalRequest,
                excludedSpans,
                List.of(),
                List.of(),
                new AuthorityLimit(kind));
    }

    public boolean present() {
        return authorityLimit != null || !ownedRegions.isEmpty();
    }

    /** True when bounded target indexing could not analyze every candidate. */
    public boolean targetIndexLimitExceeded() {
        return authorityLimit != null
                && (authorityLimit.kind()
                                == RegionKind.TARGET_OUTPUT_LIMIT_QUARANTINE
                        || authorityLimit.kind()
                                == RegionKind.TARGET_OCCURRENCE_LIMIT_QUARANTINE);
    }

    /** True when any bounded authority analysis could not finish safely. */
    public boolean authorityLimitExceeded() {
        return authorityLimit != null;
    }

    /** Stable, evidence-visible reason for a fail-closed incomplete scan. */
    public String targetIndexLimitReason() {
        boolean output = authorityLimit != null
                && authorityLimit.kind()
                        == RegionKind.TARGET_OUTPUT_LIMIT_QUARANTINE;
        boolean occurrences = authorityLimit != null
                && authorityLimit.kind()
                        == RegionKind.TARGET_OCCURRENCE_LIMIT_QUARANTINE;
        if (output && occurrences) {
            throw new IllegalStateException(
                    "one sequential plan cannot have two target scan limits");
        }
        if (output) return "sequential-target-output-limit-exceeded";
        if (occurrences) {
            return "sequential-target-occurrence-limit-exceeded";
        }
        return "";
    }

    /** Stable, evidence-visible reason for any fail-closed authority limit. */
    public String authorityLimitReason() {
        String targetReason = targetIndexLimitReason();
        boolean evidenceRows = authorityLimit != null
                && authorityLimit.kind()
                        == RegionKind.LEXICAL_EVIDENCE_LIMIT_QUARANTINE;
        if (!targetReason.isEmpty() && evidenceRows) {
            throw new IllegalStateException(
                    "one sequential plan cannot have two authority limits");
        }
        return evidenceRows
                ? "sequential-lexical-evidence-limit-exceeded"
                : targetReason;
    }

    /** Source-ordered exact target-occurrence decisions. */
    public List<ClauseDecision> decisions() {
        return occurrenceDecisions;
    }

    /** Only typed mutation/workspace regions may suppress legacy mutation scans. */
    public List<SourceSpan> legacyMutationSpans() {
        return ownedRegions.stream()
                .filter(OwnedRegion::masksLegacyMutationScan)
                .map(OwnedRegion::span)
                .distinct()
                .toList();
    }

    /** Every exact source occurrence retained by the parser. */
    public List<ClauseDecision> decisions(String path) {
        String normalized;
        try {
            normalized = TargetRef.normalizePath(path);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
        String expected = normalized;
        return occurrenceDecisions.stream()
                .filter(decision -> decision.path().equals(expected))
                .toList();
    }

    /** The single deterministic decision used to compile a target role. */
    public Optional<ClauseDecision> effectiveDecision(String path) {
        return decisions(path).stream().max(EFFECTIVE_ORDER);
    }

    /** Source-ordered effective decisions, one per canonical path. */
    public List<ClauseDecision> effectiveDecisions() {
        return effectiveByPath().values().stream()
                .sorted(SOURCE_ORDER)
                .toList();
    }

    public Set<String> pathsWithAuthority(Authority authority) {
        if (authority == null || occurrenceDecisions.isEmpty()) return Set.of();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (ClauseDecision decision : effectiveDecisions()) {
            if (decision.authority() == authority) paths.add(decision.path());
        }
        return Collections.unmodifiableSet(paths);
    }

    public boolean blocksWorkspaceOperations() {
        return ownedRegions.stream().anyMatch(region ->
                        region.kind() == RegionKind.WORKSPACE_OPERATION)
                || occurrenceDecisions.stream().anyMatch(decision ->
                        decision.actionFamily()
                                == ActionFamily.WORKSPACE_OPERATION);
    }

    public Map<String, IntentDerivation> requiredDerivations() {
        if (occurrenceDecisions.isEmpty()) return Map.of();
        LinkedHashMap<String, IntentDerivation> derivations =
                new LinkedHashMap<>();
        for (ClauseDecision decision : effectiveDecisions()) {
            if (decision.authority() == Authority.REQUIRED) {
                derivations.put(decision.path(), decision.derivation());
            }
        }
        return Collections.unmodifiableMap(derivations);
    }

    public Map<String, IntentDerivation> decisionDerivations() {
        if (occurrenceDecisions.isEmpty()) return Map.of();
        LinkedHashMap<String, IntentDerivation> derivations =
                new LinkedHashMap<>();
        for (ClauseDecision decision : effectiveDecisions()) {
            derivations.put(decision.path(), decision.derivation());
        }
        return Collections.unmodifiableMap(derivations);
    }

    /**
     * Validates only each path's effective typed decision. Earlier occurrences
     * remain evidence, but cannot create a second compiled role.
     */
    public boolean matchesIntent(TaskIntent intent) {
        return matchesIntentExcluding(intent, Set.of(), false);
    }

    public boolean matchesConfirmedReplayIntent(TaskIntent intent) {
        return matchesIntentExcluding(intent, Set.of(), true);
    }

    public boolean matchesIntentExcluding(
            TaskIntent intent,
            Set<String> pathsOwnedByAnotherPlan,
            boolean confirmedReplay
    ) {
        if (intent == null
                || !canonicalRequest.equals(intent.originalUserRequest())) {
            return false;
        }
        Set<String> excludedPaths = normalizePaths(pathsOwnedByAnotherPlan);
        for (ClauseDecision decision : effectiveDecisions()) {
            if (excludedPaths.contains(decision.path())) continue;
            TargetRef target = intent.targets().find(decision.path()).orElse(null);
            if (target == null) {
                return false;
            }
            if (!roleMatches(decision.authority(), target.role())) {
                if (safelyStrongerNonMutationRole(
                        decision.authority(),
                        target.role())) {
                    continue;
                }
                return false;
            }
            IntentDerivation actual = target.derivation();
            IntentDerivation expected = decision.derivation();
            if (!confirmedReplay && !actual.equals(expected)) return false;
            if (confirmedReplay
                    && (actual.source() != TargetSource.MESSAGE_HISTORY
                            || actual.startOffset()
                                    != IntentDerivation.UNKNOWN_OFFSET
                            || actual.authorityEndOffset()
                                    != IntentDerivation.UNKNOWN_OFFSET
                            || actual.endOffset()
                                    != IntentDerivation.UNKNOWN_OFFSET
                            || !actual.reason().equals(expected.reason())
                            || !actual.sourceText().equals(expected.sourceText())
                            || Double.compare(
                                    actual.confidence(),
                                    expected.confidence()) != 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean roleMatches(Authority authority, TargetRole role) {
        return switch (authority) {
            case REQUIRED -> role == TargetRole.MUST_MUTATE
                    || role == TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE;
            case OPTIONAL -> role == TargetRole.MENTIONED_ONLY;
            case NON_AUTHORITATIVE, WORKSPACE_OPERATION_BLOCKED ->
                    role == TargetRole.MENTIONED_ONLY;
            case FORBIDDEN -> role == TargetRole.FORBIDDEN;
        };
    }

    private static boolean safelyStrongerNonMutationRole(
            Authority authority,
            TargetRole role
    ) {
        if (role == TargetRole.FORBIDDEN) return true;
        if (authority != Authority.OPTIONAL
                && authority != Authority.NON_AUTHORITATIVE
                && authority != Authority.WORKSPACE_OPERATION_BLOCKED) {
            return false;
        }
        return role == TargetRole.MUST_READ
                || role == TargetRole.SOURCE_EVIDENCE
                || role == TargetRole.VERIFY_ONLY;
    }

    static List<SourceSpan> normalizedSpans(
            List<SourceSpan> raw,
            int requestLength
    ) {
        if (raw == null) {
            throw new IllegalArgumentException(
                    "excluded source spans must not be null");
        }
        if (raw.size() > MAX_EXCLUDED_SPANS) {
            throw new IllegalArgumentException("too many excluded source spans");
        }
        if (raw.isEmpty()) return List.of();
        Set<SourceSpan> identities = new HashSet<>();
        ArrayList<SourceSpan> checked = new ArrayList<>(raw.size());
        for (SourceSpan span : raw) {
            if (span == null) {
                throw new IllegalArgumentException(
                        "excluded source spans must not contain null entries");
            }
            if (!identities.add(span)) {
                throw new IllegalArgumentException(
                        "duplicate excluded source span");
            }
            checked.add(span);
        }
        List<SourceSpan> sorted = checked.stream()
                .sorted(Comparator
                        .comparingInt(SourceSpan::startOffset)
                        .thenComparingInt(SourceSpan::endOffset))
                .toList();
        List<SourceSpan> merged = new ArrayList<>();
        for (SourceSpan span : sorted) {
            if (span.endOffset() > requestLength) {
                throw new IllegalArgumentException(
                        "excluded source span exceeds canonical request");
            }
            if (merged.isEmpty()) {
                merged.add(span);
                continue;
            }
            SourceSpan prior = merged.getLast();
            if (span.startOffset() <= prior.endOffset()) {
                merged.set(
                        merged.size() - 1,
                        new SourceSpan(
                                prior.startOffset(),
                                Math.max(prior.endOffset(), span.endOffset())));
            } else {
                merged.add(span);
            }
        }
        return List.copyOf(merged);
    }

    private static Set<String> normalizePaths(Set<String> raw) {
        if (raw == null || raw.isEmpty()) return Set.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : raw) {
            try {
                normalized.add(TargetRef.normalizePath(path));
            } catch (IllegalArgumentException ignored) {
                // Invalid exclusions cannot shadow a canonical path.
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private Map<String, ClauseDecision> effectiveByPath() {
        if (occurrenceDecisions.isEmpty()) return Map.of();
        LinkedHashMap<String, ClauseDecision> byPath = new LinkedHashMap<>();
        for (ClauseDecision candidate : occurrenceDecisions) {
            ClauseDecision current = byPath.get(candidate.path());
            if (current == null
                    || EFFECTIVE_ORDER.compare(candidate, current) > 0) {
                byPath.put(candidate.path(), candidate);
            }
        }
        return Collections.unmodifiableMap(byPath);
    }

    private static boolean derivationMatches(
            IntentDerivation actual,
            IntentDerivation expected,
            boolean confirmedReplay
    ) {
        if (!confirmedReplay) return actual.equals(expected);
        return actual.source() == TargetSource.MESSAGE_HISTORY
                && actual.startOffset() == IntentDerivation.UNKNOWN_OFFSET
                && actual.authorityEndOffset() == IntentDerivation.UNKNOWN_OFFSET
                && actual.endOffset() == IntentDerivation.UNKNOWN_OFFSET
                && actual.reason().equals(expected.reason())
                && actual.sourceText().equals(expected.sourceText())
                && Double.compare(actual.confidence(), expected.confidence()) == 0;
    }

    private static boolean containsPath(String sourceText, String path) {
        return CoordinatedMutationTailPlan.containsPath(sourceText, path);
    }

    private static int precedence(Authority authority) {
        return switch (authority) {
            case FORBIDDEN -> 5;
            case REQUIRED -> 4;
            case WORKSPACE_OPERATION_BLOCKED -> 3;
            case OPTIONAL -> 2;
            case NON_AUTHORITATIVE -> 1;
        };
    }

    private static final Comparator<ClauseDecision> SOURCE_ORDER = Comparator
            .comparingInt(ClauseDecision::startOffset)
            .thenComparingInt(ClauseDecision::actionStartOffset)
            .thenComparingInt(ClauseDecision::targetStartOffset)
            .thenComparingInt(ClauseDecision::authorityEndOffset)
            .thenComparing(ClauseDecision::path)
            .thenComparingInt(ClauseDecision::actionEndOffset)
            .thenComparingInt(ClauseDecision::endOffset)
            .thenComparing(decision -> decision.authority().name())
            .thenComparing(decision -> decision.actionFamily().name())
            .thenComparing(ClauseDecision::action)
            .thenComparing(ClauseDecision::sourceText);

    private static final Comparator<OwnedRegion> REGION_SOURCE_ORDER =
            Comparator.comparingInt(OwnedRegion::startOffset)
                    .thenComparingInt(OwnedRegion::endOffset)
                    .thenComparing(region -> region.kind().name())
                    .thenComparing(OwnedRegion::sourceText);

    private static final Comparator<ClauseDecision> EFFECTIVE_ORDER =
            Comparator.comparingInt((ClauseDecision decision) ->
                            precedence(decision.authority()))
                    .thenComparingInt(ClauseDecision::targetStartOffset)
                    .thenComparingInt(ClauseDecision::authorityEndOffset);

    private record RegionSpanIdentity(int startOffset, int endOffset) {}
}
