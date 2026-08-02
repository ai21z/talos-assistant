package dev.talos.runtime.intent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One deterministic owner selection across the disjoint T1196 and T1204
 * lexical plans.
 *
 * <p>Every exact occurrence remains in its source plan. This class selects
 * only the single decision whose role and derivation may be compiled for a
 * canonical path. Authority wins first; equal authority is resolved by the
 * later exact source occurrence, never by parser type.
 */
public final class MutationPlanSelection {

    /** Aggregate raw role-evidence ceiling before durable JSON projection. */
    public static final long MAX_PROJECTED_ROLEFUL_SOURCE_CHARACTERS =
            1_048_576L;

    /** Maximum number of durable role rows in one policy or trace. */
    public static final int MAX_DURABLE_ROLEFUL_TARGETS = 4_096;

    public enum DurableEvidenceState {
        EXACT,
        REDACTED_HASH_ONLY
    }

    public enum Owner {
        COORDINATED_MUTATION_TAIL,
        SEQUENTIAL_MUTATION_CLAUSE
    }

    public record SelectedDecision(
            String path,
            Owner owner,
            String authority,
            String action,
            String actionFamily,
            int actionStartOffset,
            int actionEndOffset,
            int targetStartOffset,
            int startOffset,
            int authorityEndOffset,
            int endOffset,
            String sourceText,
            IntentDerivation derivation,
            int precedence
    ) {
        public SelectedDecision {
            path = TargetRef.normalizePath(path);
            if (owner == null || authority == null || authority.isBlank()
                    || action == null || action.isBlank()
                    || actionFamily == null || actionFamily.isBlank()
                    || startOffset < 0
                    || endOffset <= startOffset
                    || actionStartOffset < startOffset
                    || actionEndOffset <= actionStartOffset
                    || actionEndOffset - actionStartOffset != action.length()
                    || targetStartOffset < startOffset
                    || targetStartOffset >= authorityEndOffset
                    || authorityEndOffset <= startOffset
                    || authorityEndOffset > endOffset
                    || derivation == null || precedence < 1) {
                throw new IllegalArgumentException(
                        "invalid selected mutation-plan decision");
            }
            sourceText = sourceText == null ? "" : sourceText;
            if (sourceText.length() != endOffset - startOffset) {
                throw new IllegalArgumentException(
                        "selected mutation-plan source text must bind its exact span");
            }
        }
    }

    /** One exact parser-owned region retained for durable negative evidence. */
    public record SelectedRegion(
            Owner owner,
            String kind,
            String action,
            String pathSha256,
            int startOffset,
            int endOffset,
            String sourceText
    ) {
        public SelectedRegion {
            kind = kind == null ? "" : kind.strip();
            action = action == null ? "" : action.strip();
            pathSha256 = pathSha256 == null ? "" : pathSha256;
            sourceText = sourceText == null ? "" : sourceText;
            if (owner == null || kind.isBlank() || startOffset < 0
                    || endOffset <= startOffset || sourceText.isEmpty()
                    || sourceText.length() != endOffset - startOffset
                    || !validRegionKind(owner, kind)) {
                throw new IllegalArgumentException("invalid selected lexical region");
            }
            boolean targetObservation =
                    "WORKSPACE_OPERATION_TARGET_OBSERVATION".equals(kind);
            boolean clauseObservation =
                    "WORKSPACE_OPERATION_CLAUSE_OBSERVATION".equals(kind);
            if (targetObservation
                    && (action.isBlank() || !validSha256(pathSha256))
                    || clauseObservation
                            && (action.isBlank() || !pathSha256.isEmpty())
                    || !targetObservation && !clauseObservation
                            && (!action.isEmpty() || !pathSha256.isEmpty())) {
                throw new IllegalArgumentException(
                        "lexical region action/path identity does not match its kind");
            }
        }
    }

    /** One canonical request identity computed from one UTF-8 materialization. */
    public record SourceRequestIdentity(
            String sha256,
            int characters,
            int bytes
    ) {
        public SourceRequestIdentity {
            if (!validSha256(sha256) || characters < 0 || bytes < 0) {
                throw new IllegalArgumentException(
                        "invalid canonical source-request identity");
            }
        }
    }

    private static final Comparator<SelectedDecision> WINNER_ORDER =
            Comparator.comparingInt(SelectedDecision::precedence)
                    .thenComparingInt(SelectedDecision::targetStartOffset)
                    .thenComparingInt(SelectedDecision::authorityEndOffset);

    private MutationPlanSelection() {}

    public static Map<String, SelectedDecision> selected(
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan
    ) {
        LinkedHashMap<String, SelectedDecision> selected =
                new LinkedHashMap<>();
        if (tailPlan != null) {
            Map<String, IntentDerivation> derivations =
                    tailPlan.decisionDerivations();
            for (CoordinatedMutationTailPlan.TargetDecision decision
                    : tailPlan.decisions()) {
                merge(selected, new SelectedDecision(
                        decision.path(),
                        Owner.COORDINATED_MUTATION_TAIL,
                        decision.authority().name(),
                        decision.action(),
                        decision.actionFamily().name(),
                        decision.actionStartOffset(),
                        decision.actionEndOffset(),
                        decision.targetStartOffset(),
                        decision.startOffset(),
                        decision.authorityEndOffset(),
                        decision.endOffset(),
                        decision.sourceText(),
                        derivations.get(decision.path()),
                        precedence(decision.authority())));
            }
        }
        if (sequentialPlan != null
                && !sequentialPlan.authorityLimitExceeded()) {
            for (SequentialMutationClausePlan.ClauseDecision decision
                    : sequentialPlan.effectiveDecisions()) {
                merge(selected, new SelectedDecision(
                        decision.path(),
                        Owner.SEQUENTIAL_MUTATION_CLAUSE,
                        decision.authority().name(),
                        decision.action(),
                        decision.actionFamily().name(),
                        decision.actionStartOffset(),
                        decision.actionEndOffset(),
                        decision.targetStartOffset(),
                        decision.startOffset(),
                        decision.authorityEndOffset(),
                        decision.endOffset(),
                        decision.sourceText(),
                        decision.derivation(),
                        precedence(decision.authority())));
            }
        }
        return Collections.unmodifiableMap(selected);
    }

    public static Map<String, IntentDerivation> selectedDerivations(
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan
    ) {
        LinkedHashMap<String, IntentDerivation> derivations =
                new LinkedHashMap<>();
        selected(tailPlan, sequentialPlan).forEach((path, decision) ->
                derivations.put(path, decision.derivation()));
        return Collections.unmodifiableMap(derivations);
    }

    public static List<SelectedDecision> allDecisions(
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan
    ) {
        LinkedHashMap<String, SelectedDecision> unique = new LinkedHashMap<>();
        int ordinal = 0;
        if (tailPlan != null) {
            Map<String, IntentDerivation> derivations =
                    tailPlan.decisionDerivations();
            for (CoordinatedMutationTailPlan.TargetDecision decision
                    : tailPlan.decisions()) {
                SelectedDecision converted = new SelectedDecision(
                        decision.path(),
                        Owner.COORDINATED_MUTATION_TAIL,
                        decision.authority().name(),
                        decision.action(),
                        decision.actionFamily().name(),
                        decision.actionStartOffset(),
                        decision.actionEndOffset(),
                        decision.targetStartOffset(),
                        decision.startOffset(),
                        decision.authorityEndOffset(),
                        decision.endOffset(),
                        decision.sourceText(),
                        derivations.get(decision.path()),
                        precedence(decision.authority()));
                unique.put("tail:" + ordinal++, converted);
            }
        }
        ordinal = 0;
        if (sequentialPlan != null
                && !sequentialPlan.authorityLimitExceeded()) {
            for (SequentialMutationClausePlan.ClauseDecision decision
                    : sequentialPlan.occurrenceDecisions()) {
                SelectedDecision converted = new SelectedDecision(
                        decision.path(),
                        Owner.SEQUENTIAL_MUTATION_CLAUSE,
                        decision.authority().name(),
                        decision.action(),
                        decision.actionFamily().name(),
                        decision.actionStartOffset(),
                        decision.actionEndOffset(),
                        decision.targetStartOffset(),
                        decision.startOffset(),
                        decision.authorityEndOffset(),
                        decision.endOffset(),
                        decision.sourceText(),
                        decision.derivation(),
                        precedence(decision.authority()));
                unique.put("sequential:" + ordinal++, converted);
            }
        }
        return unique.values().stream()
                .sorted(Comparator
                         .comparingInt(SelectedDecision::startOffset)
                         .thenComparingInt(SelectedDecision::actionStartOffset)
                         .thenComparingInt(SelectedDecision::actionEndOffset)
                         .thenComparingInt(SelectedDecision::targetStartOffset)
                         .thenComparingInt(SelectedDecision::authorityEndOffset)
                        .thenComparingInt(SelectedDecision::endOffset)
                        .thenComparing(decision -> decision.owner().name())
                        .thenComparing(SelectedDecision::path))
                .toList();
    }

    public static List<SelectedRegion> allRegions(
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan
    ) {
        ArrayList<SelectedRegion> regions = new ArrayList<>();
        if (tailPlan != null && tailPlan.present()) {
            regions.add(new SelectedRegion(
                    Owner.COORDINATED_MUTATION_TAIL,
                    "CONSTRAINT_TAIL",
                    "",
                    "",
                    tailPlan.constraintStart(),
                    tailPlan.tailEnd(),
                    tailPlan.canonicalRequest().substring(
                            tailPlan.constraintStart(),
                            tailPlan.tailEnd())));
            for (CoordinatedMutationTailPlan.WorkspaceOperationObservation observation
                    : tailPlan.workspaceOperationObservations()) {
                regions.add(new SelectedRegion(
                        Owner.COORDINATED_MUTATION_TAIL,
                        "WORKSPACE_OPERATION_TARGET_OBSERVATION",
                        observation.action(),
                        pathSha256(observation.path()),
                        observation.startOffset(),
                        observation.endOffset(),
                        observation.sourceText()));
            }
            for (CoordinatedMutationTailPlan.WorkspaceOperationClauseObservation observation
                    : tailPlan.workspaceOperationClauseObservations()) {
                regions.add(new SelectedRegion(
                        Owner.COORDINATED_MUTATION_TAIL,
                        "WORKSPACE_OPERATION_CLAUSE_OBSERVATION",
                        observation.action(),
                        "",
                        observation.startOffset(),
                        observation.endOffset(),
                        observation.sourceText()));
            }
        }
        if (sequentialPlan != null
                && sequentialPlan.authorityLimitExceeded()) {
            regions.add(new SelectedRegion(
                    Owner.SEQUENTIAL_MUTATION_CLAUSE,
                    sequentialPlan.authorityLimit().kind().name(),
                    "",
                    "",
                    0,
                    sequentialPlan.canonicalRequest().length(),
                    sequentialPlan.canonicalRequest()));
        } else if (sequentialPlan != null) {
            for (SequentialMutationClausePlan.SourceSpan span
                    : sequentialPlan.excludedSpans()) {
                regions.add(new SelectedRegion(
                        Owner.SEQUENTIAL_MUTATION_CLAUSE,
                        "EXCLUDED_SOURCE_SPAN",
                        "",
                        "",
                        span.startOffset(),
                        span.endOffset(),
                        sequentialPlan.canonicalRequest().substring(
                                span.startOffset(),
                                span.endOffset())));
            }
            for (SequentialMutationClausePlan.OwnedRegion region
                    : sequentialPlan.ownedRegions()) {
                regions.add(new SelectedRegion(
                        Owner.SEQUENTIAL_MUTATION_CLAUSE,
                        region.kind().name(),
                        "",
                        "",
                        region.startOffset(),
                        region.endOffset(),
                        region.sourceText()));
            }
        }
        return regions.stream()
                .sorted(Comparator.comparingInt(SelectedRegion::startOffset)
                        .thenComparingInt(SelectedRegion::endOffset)
                        .thenComparing(region -> region.owner().name())
                        .thenComparing(SelectedRegion::kind)
                        .thenComparing(SelectedRegion::action)
                        .thenComparing(SelectedRegion::pathSha256))
                .toList();
    }

    public static long evidenceRowCount(
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan
    ) {
        long rows = 0;
        if (tailPlan != null && tailPlan.present()) {
            rows += tailPlan.decisions().size();
            rows += 1L;
            rows += tailPlan.workspaceOperationObservations().size();
            rows += tailPlan.workspaceOperationClauseObservations().size();
        }
        if (sequentialPlan != null) {
            if (sequentialPlan.authorityLimitExceeded()) rows += 1L;
            else {
                rows += sequentialPlan.excludedSpans().size();
                rows += sequentialPlan.occurrenceDecisions().size();
                rows += sequentialPlan.ownedRegions().size();
            }
        }
        return rows;
    }

    /**
     * Returns true when the two plans retain more distinct exact source-span
     * characters than the shared lexical evidence ceiling permits.
     */
    public static boolean exceedsEvidenceCharacters(
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan
    ) {
        java.util.HashSet<SourceCoordinates> spans = new java.util.HashSet<>();
        long retained = 0L;
        if (tailPlan != null && tailPlan.present()) {
            retained = addEvidenceSpan(
                    spans,
                    retained,
                    tailPlan.constraintStart(),
                    tailPlan.tailEnd());
            if (retained < 0L) return true;
            for (CoordinatedMutationTailPlan.TargetDecision decision
                    : tailPlan.decisions()) {
                retained = addEvidenceSpan(
                        spans,
                        retained,
                        decision.startOffset(),
                        decision.endOffset());
                if (retained < 0L) return true;
            }
            for (CoordinatedMutationTailPlan.WorkspaceOperationObservation row
                    : tailPlan.workspaceOperationObservations()) {
                retained = addEvidenceSpan(
                        spans,
                        retained,
                        row.startOffset(),
                        row.endOffset());
                if (retained < 0L) return true;
            }
            for (CoordinatedMutationTailPlan
                    .WorkspaceOperationClauseObservation row
                    : tailPlan.workspaceOperationClauseObservations()) {
                retained = addEvidenceSpan(
                        spans,
                        retained,
                        row.startOffset(),
                        row.endOffset());
                if (retained < 0L) return true;
            }
        }
        if (sequentialPlan == null
                || sequentialPlan.authorityLimitExceeded()) {
            return false;
        }
        for (SequentialMutationClausePlan.SourceSpan span
                : sequentialPlan.excludedSpans()) {
            retained = addEvidenceSpan(
                    spans,
                    retained,
                    span.startOffset(),
                    span.endOffset());
            if (retained < 0L) return true;
        }
        for (SequentialMutationClausePlan.ClauseDecision decision
                : sequentialPlan.occurrenceDecisions()) {
            retained = addEvidenceSpan(
                    spans,
                    retained,
                    decision.startOffset(),
                    decision.endOffset());
            if (retained < 0L) return true;
        }
        for (SequentialMutationClausePlan.OwnedRegion region
                : sequentialPlan.ownedRegions()) {
            retained = addEvidenceSpan(
                    spans,
                    retained,
                    region.startOffset(),
                    region.endOffset());
            if (retained < 0L) return true;
        }
        return false;
    }

    /**
     * Returns true when selected lexical winners would duplicate more raw
     * source text into roleful-target persistence than the durable boundary
     * permits. Exact-span evidence remains deduplicated separately.
     */
    public static boolean exceedsProjectedRolefulSourceCharacters(
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan
    ) {
        return exceedsProjectedRolefulSourceCharacters(
                selected(tailPlan, sequentialPlan).values());
    }

    static boolean exceedsProjectedRolefulSourceCharacters(
            Iterable<SelectedDecision> decisions
    ) {
        long retained = 0L;
        if (decisions == null) return false;
        for (SelectedDecision decision : decisions) {
            if (decision == null) {
                throw new IllegalArgumentException(
                        "mutation-plan decisions must not contain null entries");
            }
            int length = decision.sourceText().length();
            if (length > MAX_PROJECTED_ROLEFUL_SOURCE_CHARACTERS - retained) {
                return true;
            }
            retained += length;
        }
        return false;
    }

    private static long addEvidenceSpan(
            Set<SourceCoordinates> spans,
            long retained,
            int startOffset,
            int endOffset
    ) {
        SourceCoordinates coordinates = new SourceCoordinates(
                startOffset,
                endOffset);
        if (!spans.add(coordinates)) return retained;
        long length = (long) endOffset - startOffset;
        return length < 0L
                        || length > SequentialMutationClausePlan
                                .MAX_RETAINED_EVIDENCE_CHARACTERS - retained
                ? -1L
                : retained + length;
    }

    private static String cachedSpanSha256(
            Map<SourceCoordinates, SpanDigest> cache,
            int startOffset,
            int endOffset,
            String sourceText
    ) {
        SourceCoordinates coordinates = new SourceCoordinates(
                startOffset,
                endOffset);
        String exact = sourceText == null ? "" : sourceText;
        SpanDigest cached = cache.get(coordinates);
        if (cached != null) {
            if (cached.sourceText() != exact
                    && !cached.sourceText().equals(exact)) {
                throw new IllegalArgumentException(
                        "one lexical span cannot carry conflicting source text");
            }
            return cached.sha256();
        }
        String sha256 = sourceSpanSha256(exact);
        cache.put(coordinates, new SpanDigest(exact, sha256));
        return sha256;
    }

    public static SourceRequestIdentity sourceRequestIdentity(
            String canonicalRequest
    ) {
        String canonical = canonicalRequest(canonicalRequest);
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        return new SourceRequestIdentity(
                sha256Bytes(bytes),
                canonical.length(),
                bytes.length);
    }

    public static String sourceRequestSha256(String canonicalRequest) {
        return sourceRequestIdentity(canonicalRequest).sha256();
    }

    public static String canonicalRequest(String request) {
        return request == null ? "" : request.strip();
    }

    public static int sourceRequestChars(String request) {
        return canonicalRequest(request).length();
    }

    public static int sourceRequestBytes(String request) {
        return canonicalRequest(request).getBytes(StandardCharsets.UTF_8).length;
    }

    public static String pathSha256(String path) {
        return lengthDelimitedSha256(
                "talos.lexical.path.v1",
                TargetRef.normalizePath(path));
    }

    public static String sourceSpanSha256(String sourceText) {
        return lengthDelimitedSha256(
                "talos.lexical.source-span.v1",
                sourceText == null ? "" : sourceText);
    }

    public static String occurrenceSha256(
            String owner,
            String pathSha256,
            String authority,
            String action,
            String actionFamily,
            String source,
            String sourceRequestSha256,
            int sourceRequestChars,
            int sourceRequestBytes,
            int actionStartOffset,
            int actionEndOffset,
            int targetStartOffset,
            int startOffset,
            int authorityEndOffset,
            int endOffset,
            String sourceSpanSha256
    ) {
        return lengthDelimitedSha256(
                "talos.lexical.decision.v2",
                owner,
                pathSha256,
                authority,
                action,
                actionFamily,
                source,
                sourceRequestSha256,
                Integer.toString(sourceRequestChars),
                Integer.toString(sourceRequestBytes),
                Integer.toString(actionStartOffset),
                Integer.toString(actionEndOffset),
                Integer.toString(targetStartOffset),
                Integer.toString(startOffset),
                Integer.toString(authorityEndOffset),
                Integer.toString(endOffset),
                sourceSpanSha256);
    }

    public static String regionSha256(
            String owner,
            String kind,
            String source,
            String sourceRequestSha256,
            int sourceRequestChars,
            int sourceRequestBytes,
            String action,
            String pathSha256,
            int startOffset,
            int endOffset,
            String sourceSpanSha256
    ) {
        return lengthDelimitedSha256(
                "talos.lexical.region.v2",
                owner,
                kind,
                source,
                sourceRequestSha256,
                Integer.toString(sourceRequestChars),
                Integer.toString(sourceRequestBytes),
                action,
                pathSha256,
                Integer.toString(startOffset),
                Integer.toString(endOffset),
                sourceSpanSha256);
    }

    private static String lengthDelimitedSha256(
            String domain,
            String... fields
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthDelimited(digest, domain);
            if (fields != null) {
                for (String field : fields) {
                    updateLengthDelimited(digest, field == null ? "" : field);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void updateLengthDelimited(
            MessageDigest digest,
            String value
    ) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String sha256Bytes(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static void requireDurableDecisionShape(
            Owner owner,
            String path,
            String pathSha256,
            String storedPathSha256,
            String authority,
            String action,
            String actionFamily,
            TargetSource source,
            String evidenceState,
            String sourceRequestSha256,
            int sourceRequestChars,
            int sourceRequestBytes,
            int actionStartOffset,
            int actionEndOffset,
            int targetStartOffset,
            int startOffset,
            int authorityEndOffset,
            int endOffset,
            String sourceSpanSha256,
            String occurrenceSha256,
            boolean selectedForPath,
            boolean activeInResolution
    ) {
        if (owner == null || source == null
                || source != TargetSource.USER_REQUEST
                        && source != TargetSource.MESSAGE_HISTORY) {
            throw new IllegalArgumentException(
                    "lexical evidence requires a typed request source and owner");
        }
        String normalizedPath = TargetRef.normalizePath(path);
        DurableEvidenceState state;
        try {
            state = DurableEvidenceState.valueOf(evidenceState);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "lexical evidence state must be named", e);
        }
        String requestHash = sourceRequestSha256 == null
                ? "" : sourceRequestSha256;
        String pathHash = pathSha256 == null ? "" : pathSha256;
        String storedPathHash = storedPathSha256 == null
                ? "" : storedPathSha256;
        String spanHash = sourceSpanSha256 == null ? "" : sourceSpanSha256;
        String occurrenceHash = occurrenceSha256 == null
                ? "" : occurrenceSha256;
        if (!requestHash.matches("[0-9a-f]{64}")
                || !pathHash.matches("[0-9a-f]{64}")
                || !storedPathHash.matches("[0-9a-f]{64}")
                || !spanHash.matches("[0-9a-f]{64}")
                || !occurrenceHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "lexical evidence requires lowercase SHA-256 identities");
        }
        action = action == null ? "" : action.strip();
        if (normalizedPath.length() > 8192 || action.isBlank()
                || action.length() > 128) {
            throw new IllegalArgumentException(
                    "lexical decision text fields exceed their bounded shape");
        }
        boolean redacted = state == DurableEvidenceState.REDACTED_HASH_ONLY;
        if (sourceRequestChars < 0
                || sourceRequestBytes < 0
                || startOffset < 0
                || actionStartOffset < startOffset
                || actionEndOffset <= actionStartOffset
                || actionEndOffset - actionStartOffset
                        != action.length()
                || targetStartOffset < startOffset
                || targetStartOffset >= authorityEndOffset
                || authorityEndOffset > endOffset
                || endOffset <= startOffset
                || endOffset > sourceRequestChars) {
            throw new IllegalArgumentException(
                    "lexical decision coordinates must bind one action and target");
        } else {
            requireOwnerTypes(
                    owner,
                    authority,
                    action,
                    actionFamily,
                    "",
                    -1,
                    -1,
                    false);
            String storedPathIdentity =
                    MutationPlanSelection.pathSha256(normalizedPath);
            if (!storedPathHash.equals(storedPathIdentity)
                    || !redacted && !pathHash.equals(storedPathIdentity)
                    || !occurrenceHash.equals(MutationPlanSelection.occurrenceSha256(
                            owner.name(),
                            pathHash,
                            authority,
                            action,
                            actionFamily,
                            source.name(),
                            requestHash,
                            sourceRequestChars,
                            sourceRequestBytes,
                            actionStartOffset,
                            actionEndOffset,
                            targetStartOffset,
                            startOffset,
                            authorityEndOffset,
                            endOffset,
                            spanHash))) {
                throw new IllegalArgumentException(
                        "exact lexical evidence identities do not match its fields");
            }
        }
        if (activeInResolution && !selectedForPath) {
            throw new IllegalArgumentException(
                    "only the selected lexical decision may be active");
        }
    }

    public static void requireDurableRegionShape(
            Owner owner,
            String kind,
            TargetSource source,
            String evidenceState,
            String sourceRequestSha256,
            int sourceRequestChars,
            int sourceRequestBytes,
            String action,
            String pathSha256,
            int startOffset,
            int endOffset,
            String sourceSpanSha256,
            String regionSha256
    ) {
        if (owner == null || source == null
                || source != TargetSource.USER_REQUEST
                        && source != TargetSource.MESSAGE_HISTORY
                || kind == null || kind.isBlank() || kind.length() > 128) {
            throw new IllegalArgumentException("invalid lexical region type");
        }
        if (!validRegionKind(owner, kind)) {
            throw new IllegalArgumentException(
                    "lexical region kind does not belong to its parser owner");
        }
        action = action == null ? "" : action.strip();
        pathSha256 = pathSha256 == null ? "" : pathSha256;
        boolean targetObservation =
                "WORKSPACE_OPERATION_TARGET_OBSERVATION".equals(kind);
        boolean clauseObservation =
                "WORKSPACE_OPERATION_CLAUSE_OBSERVATION".equals(kind);
        if (targetObservation
                && (action.isBlank() || !validSha256(pathSha256))
                || clauseObservation
                        && (action.isBlank() || !pathSha256.isEmpty())
                || !targetObservation && !clauseObservation
                        && (!action.isEmpty() || !pathSha256.isEmpty())) {
            throw new IllegalArgumentException(
                    "lexical region action/path identity does not match its kind");
        }
        DurableEvidenceState state;
        try {
            state = DurableEvidenceState.valueOf(evidenceState);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "lexical region evidence state must be named", e);
        }
        if (!validSha256(sourceRequestSha256)
                || !validSha256(sourceSpanSha256)
                || !validSha256(regionSha256)) {
            throw new IllegalArgumentException(
                    "lexical region requires lowercase SHA-256 identities");
        }
        if (sourceRequestChars < 0
                || sourceRequestBytes < 0
                || startOffset < 0
                || endOffset <= startOffset
                || endOffset > sourceRequestChars
                || !regionSha256.equals(MutationPlanSelection.regionSha256(
                        owner.name(),
                        kind,
                        source.name(),
                        sourceRequestSha256,
                        sourceRequestChars,
                        sourceRequestBytes,
                        action,
                        pathSha256,
                        startOffset,
                        endOffset,
                        sourceSpanSha256))) {
            throw new IllegalArgumentException(
                    "exact lexical region identity does not match its fields");
        }
    }

    public static <T extends DurableRegionView> List<T> validatedDurableRegions(
            List<T> regions
    ) {
        if (regions == null || regions.isEmpty()) return List.of();
        if (regions.size() > 4096) {
            throw new IllegalArgumentException("too many lexical regions");
        }
        String sharedHash = null;
        String sharedSource = null;
        String sharedState = null;
        Integer sharedChars = null;
        Integer sharedBytes = null;
        Comparator<T> sourceOrder = Comparator.<T>comparingInt(
                        DurableRegionView::startOffset)
                .thenComparingInt(DurableRegionView::endOffset)
                .thenComparing(DurableRegionView::owner)
                .thenComparing(DurableRegionView::kind)
                .thenComparing(DurableRegionView::action)
                .thenComparing(DurableRegionView::pathSha256)
                .thenComparing(DurableRegionView::regionSha256);
        T previous = null;
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < regions.size(); index++) {
            T region = regions.get(index);
            if (region == null || region.ordinal() != index) {
                throw new IllegalArgumentException(
                        "lexical regions must retain contiguous ordinals");
            }
            Owner owner;
            TargetSource source;
            try {
                owner = Owner.valueOf(region.owner());
                source = TargetSource.valueOf(region.source());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "lexical region owner/source must be named", e);
            }
            requireDurableRegionShape(
                    owner,
                    region.kind(),
                    source,
                    region.evidenceState(),
                    region.sourceRequestSha256(),
                    region.sourceRequestChars(),
                    region.sourceRequestBytes(),
                    region.action(),
                    region.pathSha256(),
                    region.startOffset(),
                    region.endOffset(),
                    region.sourceSpanSha256(),
                    region.regionSha256());
            if (!identities.add(region.regionSha256())) {
                throw new IllegalArgumentException("duplicate lexical region identity");
            }
            if (sharedHash == null) {
                sharedHash = region.sourceRequestSha256();
                sharedSource = region.source();
                sharedState = region.evidenceState();
                sharedChars = region.sourceRequestChars();
                sharedBytes = region.sourceRequestBytes();
            } else if (!sharedHash.equals(region.sourceRequestSha256())
                    || !sharedSource.equals(region.source())
                    || !sharedState.equals(region.evidenceState())
                    || sharedChars != region.sourceRequestChars()
                    || sharedBytes != region.sourceRequestBytes()) {
                throw new IllegalArgumentException(
                        "lexical regions must reference one canonical request state");
            }
            if (previous != null && sourceOrder.compare(previous, region) > 0) {
                throw new IllegalArgumentException(
                        "lexical regions must retain deterministic source order");
            }
            previous = region;
        }
        return List.copyOf(regions);
    }

    /**
     * Proves that one exact durable evidence envelope is the complete ordered
     * output of the authoritative lexical plans for the canonical request.
     * Hashes remain corruption checks; they never substitute for parser-owned
     * authority, ownership, completeness, or source order.
     */
    public static <D extends DurableDecisionView, R extends DurableRegionView>
            void requireCanonicalDurableManifest(
                    String canonicalRequest,
                    CoordinatedMutationTailPlan tailPlan,
                    SequentialMutationClausePlan sequentialPlan,
                    Set<String> activePaths,
                    List<D> decisions,
                    List<R> regions
            ) {
        String canonical = canonicalRequest(canonicalRequest);
        if (tailPlan == null || sequentialPlan == null
                || !canonical.equals(tailPlan.canonicalRequest())
                || !canonical.equals(sequentialPlan.canonicalRequest())) {
            throw new IllegalArgumentException(
                    "lexical manifest requires plans for the exact canonical request");
        }
        List<D> actualDecisions = validatedDurableDecisions(decisions);
        List<R> actualRegions = validatedDurableRegions(regions);
        if (actualDecisions.stream().anyMatch(decision ->
                        !DurableEvidenceState.EXACT.name().equals(
                                decision.evidenceState()))
                || actualRegions.stream().anyMatch(region ->
                        !DurableEvidenceState.EXACT.name().equals(
                                region.evidenceState()))) {
            throw new IllegalArgumentException(
                    "canonical lexical manifest validation requires exact evidence");
        }
        List<SelectedDecision> expectedDecisions = allDecisions(
                tailPlan,
                sequentialPlan);
        List<SelectedRegion> expectedRegions = allRegions(
                tailPlan,
                sequentialPlan);
        if (actualDecisions.size() != expectedDecisions.size()
                || actualRegions.size() != expectedRegions.size()) {
            throw new IllegalArgumentException(
                    "durable lexical evidence is not a complete parser manifest");
        }
        Map<String, SelectedDecision> selected = selected(
                tailPlan,
                sequentialPlan);
        Set<String> expectedActivePaths = activePaths == null
                ? Set.of()
                : activePaths;
        SourceRequestIdentity requestIdentity = sourceRequestIdentity(canonical);
        String requestHash = requestIdentity.sha256();
        int requestChars = requestIdentity.characters();
        int requestBytes = requestIdentity.bytes();
        Map<SourceCoordinates, SpanDigest> spanDigests =
                new LinkedHashMap<>();
        for (int index = 0; index < expectedDecisions.size(); index++) {
            SelectedDecision expected = expectedDecisions.get(index);
            D actual = actualDecisions.get(index);
            String expectedPathHash = pathSha256(expected.path());
            String expectedSpanHash = cachedSpanSha256(
                    spanDigests,
                    expected.startOffset(),
                    expected.endOffset(),
                    expected.sourceText());
            boolean expectedSelected = expected.equals(
                    selected.get(expected.path()));
            if (actual.ordinal() != index
                    || !actual.owner().equals(expected.owner().name())
                    || !actual.path().equals(expected.path())
                    || !actual.pathSha256().equals(expectedPathHash)
                    || !actual.storedPathSha256().equals(expectedPathHash)
                    || !actual.authority().equals(expected.authority())
                    || !actual.action().equals(expected.action())
                    || !actual.actionFamily().equals(expected.actionFamily())
                    || !actual.sourceRequestSha256().equals(requestHash)
                    || actual.sourceRequestChars() != requestChars
                    || actual.sourceRequestBytes() != requestBytes
                    || actual.actionStartOffset()
                            != expected.actionStartOffset()
                    || actual.actionEndOffset() != expected.actionEndOffset()
                    || actual.targetStartOffset()
                            != expected.targetStartOffset()
                    || actual.startOffset() != expected.startOffset()
                    || actual.authorityEndOffset()
                            != expected.authorityEndOffset()
                    || actual.endOffset() != expected.endOffset()
                    || !actual.sourceSpanSha256().equals(expectedSpanHash)
                    || actual.selectedForPath() != expectedSelected
                    || actual.activeInResolution()
                            && (!expectedSelected
                                    || !expectedActivePaths.contains(
                                            expected.path()))) {
                throw new IllegalArgumentException(
                        "durable lexical decision differs from its parser manifest");
            }
        }
        for (int index = 0; index < expectedRegions.size(); index++) {
            SelectedRegion expected = expectedRegions.get(index);
            R actual = actualRegions.get(index);
            if (actual.ordinal() != index
                    || !actual.owner().equals(expected.owner().name())
                    || !actual.kind().equals(expected.kind())
                    || !actual.action().equals(expected.action())
                    || !actual.pathSha256().equals(expected.pathSha256())
                    || !actual.sourceRequestSha256().equals(requestHash)
                    || actual.sourceRequestChars() != requestChars
                    || actual.sourceRequestBytes() != requestBytes
                    || actual.startOffset() != expected.startOffset()
                    || actual.endOffset() != expected.endOffset()
                    || !actual.sourceSpanSha256().equals(cachedSpanSha256(
                            spanDigests,
                            expected.startOffset(),
                            expected.endOffset(),
                            expected.sourceText()))) {
                throw new IllegalArgumentException(
                        "durable lexical region differs from its parser manifest");
            }
        }
    }

    public static <T extends DurableDecisionView>
            List<T> validatedDurableDecisions(List<T> decisions) {
        if (decisions == null || decisions.isEmpty()) return List.of();
        if (decisions.size() > 4096) {
            throw new IllegalArgumentException("too many lexical decisions");
        }
        Comparator<T> sourceOrder = Comparator.<T>comparingInt(
                        DurableDecisionView::startOffset)
                .thenComparingInt(DurableDecisionView::actionStartOffset)
                .thenComparingInt(DurableDecisionView::actionEndOffset)
                .thenComparingInt(DurableDecisionView::targetStartOffset)
                .thenComparingInt(DurableDecisionView::authorityEndOffset)
                .thenComparingInt(DurableDecisionView::endOffset)
                .thenComparing(DurableDecisionView::owner)
                .thenComparing(DurableDecisionView::path)
                .thenComparing(DurableDecisionView::authority)
                .thenComparing(DurableDecisionView::actionFamily)
                .thenComparing(DurableDecisionView::action)
                .thenComparing(DurableDecisionView::occurrenceSha256);
        T previous = null;
        String sharedHash = null;
        String sharedSource = null;
        String sharedState = null;
        Integer sharedChars = null;
        Integer sharedBytes = null;
        Set<String> occurrences = new HashSet<>();
        Map<String, T> winners = new LinkedHashMap<>();
        Map<String, Integer> selectedCounts = new LinkedHashMap<>();
        Map<String, Integer> maximumEndByOwner = new LinkedHashMap<>();
        for (int index = 0; index < decisions.size(); index++) {
            T decision = decisions.get(index);
            if (decision == null) {
                throw new IllegalArgumentException(
                        "lexical decisions must not contain null entries");
            }
            Owner owner;
            TargetSource source;
            try {
                owner = Owner.valueOf(decision.owner());
                source = TargetSource.valueOf(decision.source());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "lexical decision owner/source must be named", e);
            }
            requireDurableDecisionShape(
                    owner,
                    decision.path(),
                    decision.pathSha256(),
                    decision.storedPathSha256(),
                    decision.authority(),
                    decision.action(),
                    decision.actionFamily(),
                    source,
                    decision.evidenceState(),
                    decision.sourceRequestSha256(),
                    decision.sourceRequestChars(),
                    decision.sourceRequestBytes(),
                    decision.actionStartOffset(),
                    decision.actionEndOffset(),
                    decision.targetStartOffset(),
                    decision.startOffset(),
                    decision.authorityEndOffset(),
                    decision.endOffset(),
                    decision.sourceSpanSha256(),
                    decision.occurrenceSha256(),
                    decision.selectedForPath(),
                    decision.activeInResolution());
            if (decision.ordinal() != index
                    || previous != null
                            && sourceOrder.compare(previous, decision) > 0) {
                throw new IllegalArgumentException(
                        "lexical decisions must retain deterministic source order");
            }
            if (!occurrences.add(decision.occurrenceSha256())) {
                throw new IllegalArgumentException(
                        "duplicate lexical decision occurrence");
            }
            if (sharedHash == null) {
                sharedHash = decision.sourceRequestSha256();
                sharedSource = decision.source();
                sharedState = decision.evidenceState();
                sharedChars = decision.sourceRequestChars();
                sharedBytes = decision.sourceRequestBytes();
            } else if (!sharedHash.equals(decision.sourceRequestSha256())
                    || !sharedSource.equals(decision.source())
                    || !sharedState.equals(decision.evidenceState())
                    || sharedChars != decision.sourceRequestChars()
                    || sharedBytes != decision.sourceRequestBytes()) {
                throw new IllegalArgumentException(
                        "lexical decisions must reference one canonical request state");
            }
            for (Map.Entry<String, Integer> prior
                    : maximumEndByOwner.entrySet()) {
                if (!prior.getKey().equals(decision.owner())
                        && prior.getValue() > decision.startOffset()) {
                    throw new IllegalArgumentException(
                            "lexical parser owners cannot overlap source spans");
                }
            }
            maximumEndByOwner.merge(
                    decision.owner(),
                    decision.endOffset(),
                    Math::max);
            previous = decision;
            T currentWinner = winners.get(decision.pathSha256());
            if (currentWinner == null) {
                winners.put(decision.pathSha256(), decision);
            } else {
                int winnerOrder = durableWinnerOrder(
                        currentWinner,
                        decision);
                if (winnerOrder == 0) {
                    throw new IllegalArgumentException(
                            "lexical decisions contain an ambiguous semantic winner");
                }
                if (winnerOrder < 0) {
                    winners.put(decision.pathSha256(), decision);
                }
            }
            if (decision.selectedForPath()) {
                selectedCounts.merge(decision.pathSha256(), 1, Integer::sum);
            }
        }
        Set<String> activePaths = new LinkedHashSet<>();
        for (T decision : decisions) {
            boolean selected = winners.get(decision.pathSha256()) == decision;
            if (decision.selectedForPath() != selected) {
                throw new IllegalArgumentException(
                        "lexical selected-for-path evidence disagrees with precedence");
            }
            if (decision.activeInResolution()) {
                if (!activePaths.add(decision.pathSha256())) {
                    throw new IllegalArgumentException(
                            "one active lexical winner is allowed per path");
                }
            }
            if (selected
                    && "FORBIDDEN".equals(decision.authority())
                    && !decision.activeInResolution()) {
                throw new IllegalArgumentException(
                        "a selected prohibition cannot become inactive");
            }
        }
        for (String path : decisions.stream()
                .map(DurableDecisionView::pathSha256)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new))) {
            if (selectedCounts.getOrDefault(path, 0) != 1) {
                throw new IllegalArgumentException(
                        "lexical evidence requires one selected decision per path");
            }
        }
        return List.copyOf(decisions);
    }

    public interface DurableDecisionView {
        int ordinal();
        String owner();
        String path();
        String pathSha256();
        String storedPathSha256();
        String authority();
        String action();
        String actionFamily();
        String source();
        String evidenceState();
        String sourceRequestSha256();
        int sourceRequestChars();
        int sourceRequestBytes();
        int actionStartOffset();
        int actionEndOffset();
        int targetStartOffset();
        int startOffset();
        int authorityEndOffset();
        int endOffset();
        String sourceSpanSha256();
        String occurrenceSha256();
        boolean selectedForPath();
        boolean activeInResolution();
    }

    public interface DurableRegionView {
        int ordinal();
        String owner();
        String kind();
        String source();
        String evidenceState();
        String sourceRequestSha256();
        int sourceRequestChars();
        int sourceRequestBytes();
        String action();
        String pathSha256();
        int startOffset();
        int endOffset();
        String sourceSpanSha256();
        String regionSha256();
    }

    private static boolean validSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean validRegionKind(Owner owner, String kind) {
        if (owner == null || kind == null) return false;
        return switch (owner) {
            case COORDINATED_MUTATION_TAIL ->
                    "CONSTRAINT_TAIL".equals(kind)
                            || "WORKSPACE_OPERATION_TARGET_OBSERVATION"
                                    .equals(kind)
                            || "WORKSPACE_OPERATION_CLAUSE_OBSERVATION"
                                    .equals(kind);
            case SEQUENTIAL_MUTATION_CLAUSE ->
                    "EXCLUDED_SOURCE_SPAN".equals(kind)
                             || "CONTENT_MUTATION".equals(kind)
                             || "WORKSPACE_OPERATION".equals(kind)
                             || "PROVIDER_REMOVED_CONTROL_CLAUSE".equals(kind)
                             || "TARGET_OUTPUT_LIMIT_QUARANTINE".equals(kind)
                             || "TARGET_OCCURRENCE_LIMIT_QUARANTINE".equals(kind)
                              || "LEXICAL_EVIDENCE_LIMIT_QUARANTINE".equals(kind)
                              || "NON_AUTHORITATIVE_TARGET_CLAUSE".equals(kind)
                              || "UNCLASSIFIED_TARGET_CLAUSE".equals(kind);
        };
    }

    private record SourceCoordinates(int startOffset, int endOffset) {}

    private record SpanDigest(String sourceText, String sha256) {}

    private static int durableWinnerOrder(
            DurableDecisionView left,
            DurableDecisionView right
    ) {
        int byPrecedence = Integer.compare(
                durablePrecedence(left.authority()),
                durablePrecedence(right.authority()));
        if (byPrecedence != 0) return byPrecedence;
        int byStart = Integer.compare(
                left.targetStartOffset(),
                right.targetStartOffset());
        if (byStart != 0) return byStart;
        int byAuthorityEnd = Integer.compare(
                left.authorityEndOffset(),
                right.authorityEndOffset());
        if (byAuthorityEnd != 0) return byAuthorityEnd;
        return byAuthorityEnd;
    }

    private static int durablePrecedence(String authority) {
        return switch (authority) {
            case "FORBIDDEN" -> 5;
            case "REQUIRED" -> 4;
            case "WORKSPACE_OPERATION_BLOCKED" -> 3;
            case "OPTIONAL" -> 2;
            case "NON_AUTHORITATIVE" -> 1;
            default -> throw new IllegalArgumentException(
                    "unknown lexical authority");
        };
    }

    private static void requireOwnerTypes(
            Owner owner,
            String authority,
            String action,
            String actionFamily,
            String sourceText,
            int relativeActionStart,
            int relativeActionEnd,
            boolean requireExactAction
    ) {
        boolean workspace;
        if (owner == Owner.COORDINATED_MUTATION_TAIL) {
            CoordinatedMutationTailPlan.Authority typedAuthority =
                    CoordinatedMutationTailPlan.Authority.valueOf(authority);
            CoordinatedMutationTailPlan.ActionFamily typedFamily =
                    CoordinatedMutationTailPlan.ActionFamily.valueOf(
                            actionFamily);
            workspace = typedFamily
                    == CoordinatedMutationTailPlan.ActionFamily
                            .WORKSPACE_OPERATION;
            if (typedAuthority
                            == CoordinatedMutationTailPlan.Authority
                                    .WORKSPACE_OPERATION_BLOCKED
                    && !workspace) {
                throw new IllegalArgumentException(
                        "blocked tail authority requires a workspace action");
            }
            if (workspace
                    && typedAuthority
                            != CoordinatedMutationTailPlan.Authority
                                    .WORKSPACE_OPERATION_BLOCKED
                    && typedAuthority
                            != CoordinatedMutationTailPlan.Authority.FORBIDDEN) {
                throw new IllegalArgumentException(
                        "tail workspace evidence cannot grant content authority");
            }
        } else {
            SequentialMutationClausePlan.Authority typedAuthority =
                    SequentialMutationClausePlan.Authority.valueOf(authority);
            SequentialMutationClausePlan.ActionFamily typedFamily =
                    SequentialMutationClausePlan.ActionFamily.valueOf(
                            actionFamily);
            workspace = typedFamily
                    == SequentialMutationClausePlan.ActionFamily
                            .WORKSPACE_OPERATION;
            if (typedAuthority
                            == SequentialMutationClausePlan.Authority
                                    .WORKSPACE_OPERATION_BLOCKED
                    && !workspace) {
                throw new IllegalArgumentException(
                        "blocked sequential authority requires a workspace action");
            }
            if (workspace
                    && typedAuthority
                            != SequentialMutationClausePlan.Authority
                                    .WORKSPACE_OPERATION_BLOCKED
                    && typedAuthority
                            != SequentialMutationClausePlan.Authority
                                    .NON_AUTHORITATIVE
                    && typedAuthority
                            != SequentialMutationClausePlan.Authority
                                    .FORBIDDEN) {
                throw new IllegalArgumentException(
                        "sequential workspace evidence cannot grant content authority");
            }
        }
        if (!requireExactAction) return;
        RequestLexicalView view = RequestLexicalView.of(sourceText);
        boolean canonicalAction = CanonicalMutationClauseMatcher.actions(
                view,
                relativeActionStart,
                relativeActionEnd).stream().anyMatch(candidate ->
                candidate.start() == relativeActionStart
                        && candidate.end() == relativeActionEnd
                        && candidate.verb().equalsIgnoreCase(action)
                        && CanonicalMutationClauseMatcher.actionMatchesFamily(
                                candidate,
                                workspace
                                        ? CanonicalMutationClauseMatcher.Family
                                                .WORKSPACE_OPERATION
                                        : CanonicalMutationClauseMatcher.Family
                                                .CONTENT_MUTATION,
                                sourceText.substring(relativeActionEnd)));
        boolean prohibitionOperator = "FORBIDDEN".equals(authority)
                && owner == Owner.COORDINATED_MUTATION_TAIL
                && CoordinatedMutationTailParser
                        .isCanonicalProhibitionOperator(action);
        if (prohibitionOperator) {
            boolean governedWorkspace = CanonicalMutationClauseMatcher.actions(
                    view,
                    0,
                    sourceText.length()).stream().anyMatch(candidate ->
                    CanonicalMutationClauseMatcher.actionMatchesFamily(
                            candidate,
                            CanonicalMutationClauseMatcher.Family
                                    .WORKSPACE_OPERATION,
                            sourceText.substring(candidate.end())));
            if (workspace != governedWorkspace) {
                throw new IllegalArgumentException(
                        "prohibition family must match its governed action evidence");
            }
        }
        if (!canonicalAction && !prohibitionOperator) {
            throw new IllegalArgumentException(
                    "lexical evidence action and family are not canonical");
        }
    }

    public static boolean roleMatches(
            SelectedDecision decision,
            TargetRole role
    ) {
        if (decision == null || role == null) return false;
        return switch (decision.authority()) {
            case "REQUIRED" -> role == TargetRole.MUST_MUTATE
                    || role == TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE;
            case "OPTIONAL", "NON_AUTHORITATIVE",
                    "WORKSPACE_OPERATION_BLOCKED" ->
                    role == TargetRole.MENTIONED_ONLY;
            case "FORBIDDEN" -> role == TargetRole.FORBIDDEN;
            default -> false;
        };
    }

    public static boolean safelySupersededByIndependentRole(
            SelectedDecision decision,
            TargetRole role
    ) {
        if (decision == null || role == null) return false;
        if (role == TargetRole.FORBIDDEN
                && !"FORBIDDEN".equals(decision.authority())) {
            return true;
        }
        if (!"OPTIONAL".equals(decision.authority())
                && !"NON_AUTHORITATIVE".equals(decision.authority())
                && !"WORKSPACE_OPERATION_BLOCKED".equals(
                        decision.authority())) {
            return false;
        }
        return role == TargetRole.MUST_READ
                || role == TargetRole.SOURCE_EVIDENCE
                || role == TargetRole.VERIFY_ONLY;
    }

    public static boolean derivationMatches(
            IntentDerivation actual,
            IntentDerivation expected,
            boolean confirmedReplay
    ) {
        if (actual == null || expected == null) return false;
        if (!confirmedReplay) return actual.equals(expected);
        return actual.source() == TargetSource.MESSAGE_HISTORY
                && actual.startOffset() == IntentDerivation.UNKNOWN_OFFSET
                && actual.authorityEndOffset()
                        == IntentDerivation.UNKNOWN_OFFSET
                && actual.endOffset() == IntentDerivation.UNKNOWN_OFFSET
                && actual.reason().equals(expected.reason())
                && actual.sourceText().equals(expected.sourceText())
                && Double.compare(
                        actual.confidence(),
                        expected.confidence()) == 0;
    }

    private static void merge(
            Map<String, SelectedDecision> selected,
            SelectedDecision candidate
    ) {
        SelectedDecision current = selected.get(candidate.path());
        if (current == null) {
            selected.put(candidate.path(), candidate);
            return;
        }
        int order = WINNER_ORDER.compare(candidate, current);
        if (order == 0) {
            throw new IllegalArgumentException(
                    "mutation plans contain an ambiguous semantic winner");
        }
        if (order > 0) {
            selected.put(candidate.path(), candidate);
        }
    }

    private static int precedence(
            CoordinatedMutationTailPlan.Authority authority
    ) {
        return switch (authority) {
            case FORBIDDEN -> 5;
            case REQUIRED -> 4;
            case WORKSPACE_OPERATION_BLOCKED -> 3;
            case OPTIONAL -> 2;
            case NON_AUTHORITATIVE -> 1;
        };
    }

    private static int precedence(
            SequentialMutationClausePlan.Authority authority
    ) {
        return switch (authority) {
            case FORBIDDEN -> 5;
            case REQUIRED -> 4;
            case WORKSPACE_OPERATION_BLOCKED -> 3;
            case OPTIONAL -> 2;
            case NON_AUTHORITATIVE -> 1;
        };
    }
}
