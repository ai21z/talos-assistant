package dev.talos.runtime.intent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Immutable authority decisions for one quote-aware constraint tail. */
public record CoordinatedMutationTailPlan(
        String canonicalRequest,
        int constraintStart,
        int tailEnd,
        List<TargetDecision> decisions,
        List<WorkspaceOperationObservation> workspaceOperationObservations,
        List<WorkspaceOperationClauseObservation>
                workspaceOperationClauseObservations,
        List<WorkspaceOperationObligation> workspaceOperationObligations
) {
    public static final int NO_CONSTRAINT = -1;

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

    public record TargetDecision(
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
        public TargetDecision {
            path = TargetRef.normalizePath(path);
            if (authority == null) {
                throw new IllegalArgumentException("authority must not be null");
            }
            action = action == null ? "" : action.strip();
            if (action.isBlank()) {
                throw new IllegalArgumentException("decision action must not be blank");
            }
            if (actionFamily == null) {
                throw new IllegalArgumentException(
                        "decision action family must not be null");
            }
            if (authority == Authority.WORKSPACE_OPERATION_BLOCKED
                    && actionFamily != ActionFamily.WORKSPACE_OPERATION) {
                throw new IllegalArgumentException(
                        "blocked workspace authority requires a workspace action family");
            }
            if (actionFamily == ActionFamily.WORKSPACE_OPERATION
                    && authority != Authority.WORKSPACE_OPERATION_BLOCKED
                    && authority != Authority.FORBIDDEN) {
                throw new IllegalArgumentException(
                        "workspace actions cannot receive positive tail authority");
            }
            sourceText = sourceText == null ? "" : sourceText;
            if (startOffset < 0
                    || actionStartOffset < startOffset
                    || actionEndOffset <= actionStartOffset
                    || targetStartOffset < startOffset
                    || targetStartOffset >= authorityEndOffset
                    || actionEndOffset - actionStartOffset != action.length()
                    || authorityEndOffset <= startOffset
                    || endOffset < authorityEndOffset
                    || sourceText.isEmpty()) {
                throw new IllegalArgumentException("invalid decision source span");
            }
        }

        IntentDerivation derivation(String reason) {
            return new IntentDerivation(
                    TargetSource.USER_REQUEST,
                     reason,
                     startOffset,
                     authorityEndOffset,
                     endOffset,
                     sourceText,
                    1.0);
        }
    }

    public record WorkspaceOperationObservation(
            String path,
            String action,
            int startOffset,
            int endOffset,
            String sourceText
    ) {
        public WorkspaceOperationObservation {
            path = TargetRef.normalizePath(path);
            action = action == null ? "" : action.strip();
            sourceText = sourceText == null ? "" : sourceText;
            if (action.isBlank()
                    || startOffset < 0
                    || endOffset <= startOffset
                    || sourceText.isEmpty()) {
                throw new IllegalArgumentException(
                        "invalid workspace-operation observation");
            }
        }
    }

    public record WorkspaceOperationClauseObservation(
            String action,
            int startOffset,
            int endOffset,
            String sourceText
    ) {
        public WorkspaceOperationClauseObservation {
            action = action == null ? "" : action.strip();
            sourceText = sourceText == null ? "" : sourceText;
            if (action.isBlank()
                    || startOffset < 0
                    || endOffset <= startOffset
                    || sourceText.isEmpty()) {
                throw new IllegalArgumentException(
                        "invalid workspace-operation clause observation");
            }
        }
    }

    public CoordinatedMutationTailPlan(
            String canonicalRequest,
            int constraintStart,
            int tailEnd,
            List<TargetDecision> decisions
    ) {
        this(
                canonicalRequest,
                constraintStart,
                tailEnd,
                decisions,
                observationsFrom(decisions),
                List.of(),
                List.of());
    }

    public CoordinatedMutationTailPlan(
            String canonicalRequest,
            int constraintStart,
            int tailEnd,
            List<TargetDecision> decisions,
            List<WorkspaceOperationObservation> workspaceOperationObservations
    ) {
        this(
                canonicalRequest,
                constraintStart,
                tailEnd,
                decisions,
                workspaceOperationObservations,
                List.of(),
                List.of());
    }

    public CoordinatedMutationTailPlan(
            String canonicalRequest,
            int constraintStart,
            int tailEnd,
            List<TargetDecision> decisions,
            List<WorkspaceOperationObservation> workspaceOperationObservations,
            List<WorkspaceOperationClauseObservation>
                    workspaceOperationClauseObservations
    ) {
        this(
                canonicalRequest,
                constraintStart,
                tailEnd,
                decisions,
                workspaceOperationObservations,
                workspaceOperationClauseObservations,
                List.of());
    }

    public CoordinatedMutationTailPlan {
        canonicalRequest = canonicalRequest == null ? "" : canonicalRequest;
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        workspaceOperationObservations = workspaceOperationObservations == null
                ? List.of()
                : workspaceOperationObservations.stream().distinct().toList();
        workspaceOperationClauseObservations =
                workspaceOperationClauseObservations == null
                        ? List.of()
                        : workspaceOperationClauseObservations.stream()
                                .distinct()
                                .toList();
        workspaceOperationObligations = workspaceOperationObligations == null
                ? List.of()
                : workspaceOperationObligations.stream().distinct().toList();
        if (constraintStart == NO_CONSTRAINT) {
            if (tailEnd != NO_CONSTRAINT
                    || !decisions.isEmpty()
                    || !workspaceOperationObservations.isEmpty()
                    || !workspaceOperationClauseObservations.isEmpty()
                    || !workspaceOperationObligations.isEmpty()) {
                throw new IllegalArgumentException("empty tail plan must not carry decisions");
            }
        } else if (constraintStart < 0
                || tailEnd < constraintStart
                || tailEnd > canonicalRequest.length()) {
            throw new IllegalArgumentException("invalid constraint tail bounds");
        }
        RequestLexicalView lexicalView = RequestLexicalView.of(canonicalRequest);
        List<String> decisionPaths = decisions.stream()
                .filter(java.util.Objects::nonNull)
                .map(TargetDecision::path)
                .distinct()
                .toList();
        Set<TargetSpanIdentity> exactTargets =
                CoordinatedMutationTailParser.targetOccurrences(
                        lexicalView,
                        decisionPaths,
                        Math.max(0, constraintStart),
                        Math.max(0, tailEnd)).stream()
                        .map(occurrence -> new TargetSpanIdentity(
                                occurrence.path(),
                                occurrence.envelopeStart(),
                                occurrence.envelopeEnd()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<ActionValidationIdentity, Boolean> actionValidation =
                new LinkedHashMap<>();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (TargetDecision decision : decisions) {
            if (decision.startOffset() < constraintStart
                    || decision.endOffset() > tailEnd
                    || !canonicalRequest.regionMatches(
                            true,
                            decision.actionStartOffset(),
                            decision.action(),
                            0,
                            decision.action().length())
                    || !exactTargets.contains(new TargetSpanIdentity(
                            decision.path(),
                            decision.targetStartOffset(),
                            decision.authorityEndOffset()))
                    || !decision.sourceText().equals(canonicalRequest.substring(
                            decision.startOffset(),
                            decision.endOffset()))) {
                throw new IllegalArgumentException(
                        "decision source span must be inside the tail and copied "
                                + "from the canonical request");
            }
            if (!hasExactTypedAction(
                    lexicalView,
                    canonicalRequest,
                    decision,
                    actionValidation)) {
                throw new IllegalArgumentException(
                        "decision action must be exact typed request evidence: "
                                + decision);
            }
            if (!containsPath(decision.sourceText(), decision.path())) {
                throw new IllegalArgumentException(
                        "decision source span must contain its canonical target path");
            }
            if (!paths.add(decision.path())) {
                throw new IllegalArgumentException(
                        "tail plan must contain one effective decision per target");
            }
        }
        for (WorkspaceOperationObservation observation
                : workspaceOperationObservations) {
            if (observation.startOffset() < constraintStart
                    || observation.endOffset() > tailEnd
                    || !observation.sourceText().equals(
                            canonicalRequest.substring(
                                    observation.startOffset(),
                                    observation.endOffset()))
                    || !containsPath(
                            observation.sourceText(),
                            observation.path())) {
                throw new IllegalArgumentException(
                        "workspace-operation observation must be an exact "
                                + "target-bearing span inside the tail");
            }
        }
        for (WorkspaceOperationClauseObservation observation
                : workspaceOperationClauseObservations) {
            if (observation.startOffset() < constraintStart
                    || observation.endOffset() > tailEnd
                    || !observation.sourceText().equals(
                            canonicalRequest.substring(
                                    observation.startOffset(),
                                    observation.endOffset()))
                    || !containsAction(
                            observation.sourceText(),
                            observation.action())) {
                throw new IllegalArgumentException(
                        "workspace-operation clause observation must be an "
                                + "exact action-bearing span inside the tail");
            }
        }
        for (WorkspaceOperationObligation obligation
                : workspaceOperationObligations) {
            if (obligation == null
                    || obligation.owner()
                            != WorkspaceOperationObligation.Owner
                                    .COORDINATED_TAIL
                    || !obligation.canonicalRequestSha256().equals(
                            sha256(canonicalRequest))
                    || obligation.sourceStartOffset() < constraintStart
                    || obligation.sourceEndOffset() > tailEnd
                    || !canonicalRequest.substring(
                                    obligation.sourceStartOffset(),
                                    obligation.sourceEndOffset())
                            .equals(obligation.sourceText())
                    || workspaceOperationClauseObservations.stream().noneMatch(
                            observation ->
                                    observation.startOffset()
                                                    == obligation
                                                            .sourceStartOffset()
                                            && observation.endOffset()
                                                    == obligation
                                                            .sourceEndOffset())) {
                throw new IllegalArgumentException(
                        "tail workspace obligation must bind one exact owned observation");
            }
        }
    }

    public static CoordinatedMutationTailPlan empty(String canonicalRequest) {
        return new CoordinatedMutationTailPlan(
                canonicalRequest,
                NO_CONSTRAINT,
                NO_CONSTRAINT,
                List.of());
    }

    public boolean present() {
        return constraintStart != NO_CONSTRAINT;
    }

    public Optional<TargetDecision> decision(String path) {
        String normalized;
        try {
            normalized = TargetRef.normalizePath(path);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
        return decisions.stream()
                .filter(decision -> decision.path().equals(normalized))
                .findFirst();
    }

    public Set<String> pathsWithAuthority(Authority authority) {
        if (authority == null || decisions.isEmpty()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (TargetDecision decision : decisions) {
            if (decision.authority() == authority) out.add(decision.path());
        }
        return Collections.unmodifiableSet(out);
    }

    public boolean blocksWorkspaceOperations() {
        return !workspaceOperationObservations.isEmpty()
                || !workspaceOperationClauseObservations.isEmpty();
    }

    public Map<String, IntentDerivation> requiredDerivations() {
        if (decisions.isEmpty()) return Map.of();
        LinkedHashMap<String, IntentDerivation> out = new LinkedHashMap<>();
        for (TargetDecision decision : decisions) {
            if (decision.authority() == Authority.REQUIRED) {
                out.put(decision.path(), decision.derivation("explicit-mutation-target"));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    public Map<String, IntentDerivation> decisionDerivations() {
        if (decisions.isEmpty()) return Map.of();
        LinkedHashMap<String, IntentDerivation> out = new LinkedHashMap<>();
        for (TargetDecision decision : decisions) {
            String reason = switch (decision.authority()) {
                case REQUIRED -> "explicit-mutation-target";
                case OPTIONAL -> "optional-mutation-awaits-typed-owner";
                case NON_AUTHORITATIVE -> "non-authoritative-mutation-mention";
                case WORKSPACE_OPERATION_BLOCKED ->
                        "workspace-operation-awaits-typed-owner";
                case FORBIDDEN -> "explicit-mutation-prohibition";
            };
            out.put(decision.path(), decision.derivation(reason));
        }
        return Collections.unmodifiableMap(out);
    }

    public boolean matchesIntent(TaskIntent intent) {
        return matchesIntentExcluding(intent, Set.of(), false);
    }

    private static boolean hasExactTypedAction(
            RequestLexicalView view,
            String canonicalRequest,
            TargetDecision decision,
            Map<ActionValidationIdentity, Boolean> cache
    ) {
        ActionValidationIdentity identity = new ActionValidationIdentity(
                decision.action(),
                decision.actionFamily(),
                decision.authority(),
                decision.actionStartOffset(),
                decision.actionEndOffset(),
                decision.targetStartOffset(),
                decision.endOffset());
        return cache.computeIfAbsent(identity, ignored -> {
            boolean prohibitionOperator = decision.authority()
                            == Authority.FORBIDDEN
                    && CoordinatedMutationTailParser
                            .isCanonicalProhibitionOperator(decision.action());
            if (prohibitionOperator) {
                boolean governedWorkspace =
                        CanonicalMutationClauseMatcher.actions(
                                view,
                                decision.startOffset(),
                                decision.targetStartOffset()).stream().anyMatch(
                                action -> action.family()
                                        == CanonicalMutationClauseMatcher.Family
                                                .WORKSPACE_OPERATION);
                return governedWorkspace
                        == (decision.actionFamily()
                                == ActionFamily.WORKSPACE_OPERATION);
            }
            return CanonicalMutationClauseMatcher.actions(
                    view,
                    decision.actionStartOffset(),
                    decision.actionEndOffset()).stream().anyMatch(action ->
                    action.start() == decision.actionStartOffset()
                            && action.end() == decision.actionEndOffset()
                            && action.verb().equalsIgnoreCase(decision.action())
                            && CanonicalMutationClauseMatcher.actionMatchesFamily(
                                    action,
                                    decision.actionFamily()
                                                    == ActionFamily
                                                            .WORKSPACE_OPERATION
                                            ? CanonicalMutationClauseMatcher.Family
                                                    .WORKSPACE_OPERATION
                                            : CanonicalMutationClauseMatcher.Family
                                                    .CONTENT_MUTATION,
                                    canonicalRequest.substring(
                                            decision.actionEndOffset(),
                                            decision.endOffset())));
        });
    }

    private record TargetSpanIdentity(
            String path,
            int startOffset,
            int endOffset
    ) {}

    private record ActionValidationIdentity(
            String action,
            ActionFamily family,
            Authority authority,
            int startOffset,
            int endOffset,
            int targetStartOffset,
            int sourceEndOffset
    ) {}

    public boolean matchesIntentExcluding(
            TaskIntent intent,
            Set<String> pathsOwnedByAnotherPlan,
            boolean confirmedReplay
    ) {
        if (intent == null
                || !canonicalRequest.equals(intent.originalUserRequest())) {
            return false;
        }
        Set<String> excluded = normalizedPaths(pathsOwnedByAnotherPlan);
        for (TargetDecision decision : decisions) {
            if (excluded.contains(decision.path())) continue;
            if (!decisionMatchesIntent(
                    decision,
                    intent,
                    confirmedReplay)) return false;
        }
        return true;
    }

    public boolean matchesConfirmedReplayIntent(TaskIntent intent) {
        return matchesIntentExcluding(intent, Set.of(), true);
    }

    /**
     * Retains only decisions that still match an effective intent while
     * preserving every independently observed workspace-operation span.
     *
     * <p>A target alias or posture projection may legitimately replace one
     * content decision. It must never erase an unrelated blocked workspace
     * operation and reopen that operation through legacy prose detection.
     */
    CoordinatedMutationTailPlan projectOntoExcluding(
            TaskIntent intent,
            Set<String> pathsOwnedByAnotherPlan,
            boolean confirmedReplay
    ) {
        if (intent == null
                || !canonicalRequest.equals(intent.originalUserRequest())) {
            throw new IllegalArgumentException(
                    "tail projection requires the identical canonical request");
        }
        if (!present()) return this;
        Set<String> retainedOverlap = normalizedPaths(pathsOwnedByAnotherPlan);
        List<TargetDecision> retained = decisions.stream()
                .filter(decision ->
                        retainedOverlap.contains(decision.path())
                                || decisionMatchesIntent(
                                        decision,
                                        intent,
                                        confirmedReplay))
                .toList();
        if (retained.equals(decisions)) return this;
        return new CoordinatedMutationTailPlan(
                canonicalRequest,
                constraintStart,
                tailEnd,
                retained,
                workspaceOperationObservations,
                workspaceOperationClauseObservations,
                workspaceOperationObligations);
    }

    private boolean decisionMatchesIntent(
            TargetDecision decision,
            TaskIntent intent,
            boolean confirmedReplay
    ) {
        TargetRef target = intent.targets().find(decision.path()).orElse(null);
        if (target == null) {
            return false;
        }
        boolean exactDecisionRole =
                roleMatches(decision.authority(), target.role());
        if (!exactDecisionRole) {
            return safelyStrongerNonMutationRole(
                    decision.authority(),
                    target.role());
        }
        IntentDerivation actual = target.derivation();
        IntentDerivation expected = decisionDerivations().get(decision.path());
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

    private static boolean roleMatches(Authority authority, TargetRole role) {
        return switch (authority) {
            case REQUIRED -> role == TargetRole.MUST_MUTATE
                    || role == TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE;
            case OPTIONAL, NON_AUTHORITATIVE, WORKSPACE_OPERATION_BLOCKED ->
                    role == TargetRole.MENTIONED_ONLY;
            case FORBIDDEN -> role == TargetRole.FORBIDDEN;
        };
    }

    private static boolean safelyStrongerNonMutationRole(
            Authority authority,
            TargetRole role
    ) {
        if (authority != Authority.OPTIONAL
                && authority != Authority.NON_AUTHORITATIVE
                && authority != Authority.WORKSPACE_OPERATION_BLOCKED) {
            return false;
        }
        return role == TargetRole.FORBIDDEN
                || role == TargetRole.MUST_READ
                || role == TargetRole.SOURCE_EVIDENCE
                || role == TargetRole.VERIFY_ONLY;
    }

    private static Set<String> normalizedPaths(Set<String> raw) {
        if (raw == null || raw.isEmpty()) return Set.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : raw) {
            try {
                normalized.add(TargetRef.normalizePath(path));
            } catch (IllegalArgumentException ignored) {
                // Invalid exclusions cannot shadow a canonical decision.
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    static boolean containsPath(String sourceText, String path) {
        String source = sourceText == null ? "" : sourceText.replace('\\', '/');
        String target = path == null ? "" : path.replace('\\', '/');
        if (target.isBlank() || source.length() < target.length()) return false;
        for (int i = 0; i + target.length() <= source.length(); i++) {
            int end = i + target.length();
            boolean currentDirectoryAlias =
                    i >= 2 && source.charAt(i - 2) == '.' && source.charAt(i - 1) == '/';
            boolean leftBoundary = i == 0
                    || currentDirectoryAlias
                    || !pathCharacter(source.charAt(i - 1));
            boolean rightBoundary = end == source.length()
                    || !pathCharacter(source.charAt(end))
                    || source.charAt(end) == '.'
                    && (end + 1 == source.length()
                    || Character.isWhitespace(source.charAt(end + 1)));
            if (leftBoundary
                    && rightBoundary
                    && source.regionMatches(false, i, target, 0, target.length())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAction(String sourceText, String action) {
        String source = sourceText == null ? "" : sourceText;
        String expected = action == null ? "" : action;
        if (expected.isBlank() || source.length() < expected.length()) {
            return false;
        }
        for (int i = 0; i + expected.length() <= source.length(); i++) {
            int end = i + expected.length();
            boolean leftBoundary = i == 0
                    || !Character.isLetterOrDigit(source.charAt(i - 1));
            boolean rightBoundary = end == source.length()
                    || !Character.isLetterOrDigit(source.charAt(end));
            if (leftBoundary
                    && rightBoundary
                    && source.regionMatches(
                            true,
                            i,
                            expected,
                            0,
                            expected.length())) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            (value == null ? "" : value)
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static List<WorkspaceOperationObservation> observationsFrom(
            List<TargetDecision> decisions
    ) {
        if (decisions == null || decisions.isEmpty()) return List.of();
        return decisions.stream()
                .filter(decision -> decision != null
                        && decision.actionFamily()
                                == ActionFamily.WORKSPACE_OPERATION)
                .map(decision -> new WorkspaceOperationObservation(
                        decision.path(),
                        decision.action(),
                        decision.startOffset(),
                        decision.endOffset(),
                        decision.sourceText()))
                .toList();
    }

    private static boolean pathCharacter(char value) {
        return Character.isLetterOrDigit(value)
                || value == '_'
                || value == '-'
                || value == '.'
                || value == '/';
    }
}
