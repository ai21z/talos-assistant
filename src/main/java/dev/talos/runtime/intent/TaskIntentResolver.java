package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TaskIntentResolver {

    private static final Pattern COORDINATED_NEGATION = Pattern.compile(
            "(?i)\\b(?:and|but)\\s+(?=(?:do\\s+not|don't|dont)\\b)");
    private static final Pattern EXPLICIT_SOURCE_EVIDENCE_ACTION =
            Pattern.compile("(?i)\\b(?:read|open|inspect|review|examine)\\b");
    private static final Pattern NEGATED_SOURCE_EVIDENCE_ACTION = Pattern.compile(
            "(?i)(?:\\b(?:do\\s+not|don't|dont|never|without)\\s*)$");
    private static final Pattern DIRECT_SOURCE_EVIDENCE_CONNECTOR = Pattern.compile(
            "(?i)(?:\\b(?:so|and|then|also|first|next|please|now)\\s*,?\\s*)$");
    private static final Pattern DIRECT_SOURCE_TO_OUTPUT_MARKER =
            Pattern.compile("(?iu)\\b(?:according\\s+to|based\\s+on)\\b");
    private static final Pattern DIRECT_SOURCE_FIRST_OPERAND_PREFIX =
            Pattern.compile(
                    "(?iu)^\\s+(?:(?:both|the|a|an)\\s+)?"
                            + "(?:(?:source|input)s?\\s+)?"
                            + "(?:files?\\s+)?$");
    private static final Pattern DIRECT_SOURCE_ADJACENT_OPERAND_GAP =
            Pattern.compile(
                    "(?iu)^\\s*(?:,\\s*(?:and\\s+)?|and\\s+)$");
    private static final Pattern DIRECT_SOURCE_DISJUNCTIVE_GAP =
            Pattern.compile(
                    "(?iu)^\\s*(?:,\\s*)?"
                            + "(?:and\\s*/\\s*or|or)\\b");
    private static final Pattern DIRECT_SOURCE_OUTPUT_CONNECTOR_GAP =
            Pattern.compile(
                    "(?iu)^[\\s,]*(?:(?:please|strictly|directly|exactly|"
                            + "solely|only)\\s+)*$");
    private static final Pattern NON_AUTHORITATIVE_SOURCE_CONNECTOR_CONTEXT =
            Pattern.compile(
                    "(?iu)\\b(?:proposal|suggestion|recommendation|option)"
                            + "(?:\\s+(?:is|was|would\\s+be)\\b|\\s*:)"
                            + "|\\b(?:report|reviewer|verifier|output|result|"
                            + "finding|message|note|ticket|guide)\\s+"
                            + "(?:says?|said|states?|stated|shows?|showed|"
                            + "proposes?|proposed|suggests?|suggested|"
                            + "recommends?|recommended)\\b");
    private TaskIntentResolver() {}

    public static TaskIntent fromUserRequest(String userRequest, TaskContract legacyContract) {
        return resolve(userRequest, legacyContract).intent();
    }

    public static IntentResolution resolve(String userRequest, TaskContract legacyContract) {
        TaskIntent parityIntent = fromLegacyContract(legacyContract);
        if (legacyContract == null) {
            return new IntentResolution(
                    parityIntent,
                    CoordinatedMutationTailPlan.empty(
                            userRequest == null ? "" : userRequest.strip()));
        }
        String canonicalRequest = legacyContract.originalUserRequest();
        LinkedHashSet<String> tailCandidates = new LinkedHashSet<>();
        tailCandidates.addAll(normalizedPaths(legacyContract.expectedTargets()));
        tailCandidates.addAll(normalizedPaths(legacyContract.sourceEvidenceTargets()));
        tailCandidates.addAll(normalizedPaths(legacyContract.forbiddenTargets()));
        tailCandidates.addAll(normalizedPaths(
                TaskContractResolver.extractExpectedTargets(canonicalRequest)));
        tailCandidates.addAll(normalizedPaths(
                CoordinatedMutationTailParser.discoverDirectTargets(
                        canonicalRequest)));
        CoordinatedMutationTailPlan mutationTailPlan =
                CoordinatedMutationTailParser.parse(canonicalRequest, tailCandidates);
        TaskContract authorityContract = authorityContract(
                canonicalRequest,
                legacyContract,
                mutationTailPlan);
        Set<String> sourceEvidenceTargets = effectiveSourceEvidenceTargets(
                canonicalRequest,
                authorityContract,
                mutationTailPlan);
        Set<String> mutationTargets =
                normalizedPaths(explicitMutationTargets(
                        canonicalRequest,
                        authorityContract,
                        mutationTailPlan));
        Set<String> optionalMutationTargets =
                normalizedPaths(explicitOptionalMutationTargets(
                        canonicalRequest,
                        authorityContract,
                        mutationTailPlan));
        if (hasExactStaticWebFileList(canonicalRequest)
                || readThenRewriteExistingFiles(canonicalRequest)) {
            optionalMutationTargets = Set.of();
        }
        LinkedHashSet<String> optionalSourceOverlaps =
                new LinkedHashSet<>(optionalMutationTargets);
        optionalSourceOverlaps.retainAll(sourceEvidenceTargets);
        boolean unsupportedOptionalPostReadMutation = !optionalSourceOverlaps.isEmpty();
        boolean effectiveMutationAllowed = authorityContract.mutationAllowed();
        if (unsupportedOptionalPostReadMutation) {
            LinkedHashSet<String> remainingMutationTargets =
                    new LinkedHashSet<>(mutationTargets);
            remainingMutationTargets.removeAll(optionalSourceOverlaps);
            mutationTargets = normalizedPaths(remainingMutationTargets);
            LinkedHashSet<String> remainingOptionalTargets =
                    new LinkedHashSet<>(optionalMutationTargets);
            remainingOptionalTargets.removeAll(optionalSourceOverlaps);
            optionalMutationTargets = normalizedPaths(remainingOptionalTargets);
            if (mutationTargets.isEmpty() && optionalMutationTargets.isEmpty()) {
                effectiveMutationAllowed = false;
            }
        }
        if (!optionalMutationTargets.isEmpty()) {
            LinkedHashSet<String> requiredMutationTargets = new LinkedHashSet<>(mutationTargets);
            requiredMutationTargets.removeAll(optionalMutationTargets);
            if (!requiredMutationTargets.isEmpty()) {
                mutationTargets = normalizedPaths(requiredMutationTargets);
            } else {
                optionalMutationTargets = Set.of();
                if (unsupportedOptionalPostReadMutation) {
                    mutationTargets = Set.of();
                    effectiveMutationAllowed = false;
                }
            }
        }
        Set<String> verifyOnlyTargets =
                normalizedPaths(explicitVerifyOnlyTargets(
                        canonicalRequest,
                        authorityContract));
        LinkedHashSet<String> effectiveVerifyOnlyTargets =
                new LinkedHashSet<>(verifyOnlyTargets);
        for (CoordinatedMutationTailPlan.TargetDecision decision
                : mutationTailPlan.decisions()) {
            effectiveVerifyOnlyTargets.remove(decision.path());
        }
        verifyOnlyTargets = normalizedPaths(effectiveVerifyOnlyTargets);
        Set<String> forbiddenTargets =
                normalizedPaths(explicitForbiddenTargets(
                        canonicalRequest,
                        authorityContract,
                        mutationTailPlan));
        LinkedHashSet<String> mentionedOnlyTargets = new LinkedHashSet<>();
        mentionedOnlyTargets.addAll(mutationTailPlan.pathsWithAuthority(
                CoordinatedMutationTailPlan.Authority.OPTIONAL));
        mentionedOnlyTargets.addAll(mutationTailPlan.pathsWithAuthority(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE));
        mentionedOnlyTargets.addAll(mutationTailPlan.pathsWithAuthority(
                CoordinatedMutationTailPlan.Authority.WORKSPACE_OPERATION_BLOCKED));
        boolean requiresTypedProjection = !mutationTailPlan.decisions().isEmpty()
                || hasNonLiteralQuotedExpectedTarget(
                        canonicalRequest,
                        normalizedPaths(legacyContract.expectedTargets()));
        if (!shouldTreatConstraintTargetsAsVerifyOnly(legacyContract, mutationTargets, verifyOnlyTargets)
                && !shouldApplyExplicitForbiddenTargets(legacyContract, mutationTargets, forbiddenTargets)
                && optionalMutationTargets.isEmpty()
                && !unsupportedOptionalPostReadMutation
                && (!requiresTypedProjection
                || mutationTargets.equals(normalizedPaths(
                        authorityContract.expectedTargets())))
                && mutationTailPlan.decisions().isEmpty()) {
            return new IntentResolution(parityIntent, mutationTailPlan);
        }
        TaskIntent intent = rolefulIntent(
                authorityContract.type(),
                authorityContract.mutationRequested(),
                effectiveMutationAllowed,
                authorityContract.verificationRequired(),
                mutationTargets,
                optionalMutationTargets,
                verifyOnlyTargets,
                forbiddenTargets,
                sourceEvidenceTargets,
                normalizedPaths(mentionedOnlyTargets),
                canonicalRequest,
                authorityContract.classificationReason(),
                mutationTailPlan.decisionDerivations());
        return new IntentResolution(
                intent,
                projectSupersededNonAuthorityDecisions(
                        mutationTailPlan,
                        intent));
    }

    private static CoordinatedMutationTailPlan
            projectSupersededNonAuthorityDecisions(
                    CoordinatedMutationTailPlan plan,
                    TaskIntent intent
            ) {
        if (plan == null || intent == null || !plan.present()) return plan;
        CoordinatedMutationTailPlan projected =
                plan.projectOnto(intent, false);
        for (CoordinatedMutationTailPlan.TargetDecision decision
                : plan.decisions()) {
            if (projected.decision(decision.path()).isPresent()) continue;
            if (decision.authority()
                            == CoordinatedMutationTailPlan.Authority.REQUIRED
                    || decision.authority()
                            == CoordinatedMutationTailPlan.Authority.FORBIDDEN) {
                return plan;
            }
        }
        return projected;
    }

    private static TaskContract authorityContract(
            String canonicalRequest,
            TaskContract legacyContract,
            CoordinatedMutationTailPlan mutationTailPlan
    ) {
        if (legacyContract == null
                || mutationTailPlan == null
                || !mutationTailPlan.present()
                || !"global-read-only-negation".equals(
                        legacyContract.classificationReason())
                || mutationTailPlan.pathsWithAuthority(
                        CoordinatedMutationTailPlan.Authority.FORBIDDEN)
                        .isEmpty()) {
            return legacyContract;
        }
        boolean everyProhibitionIsClosedAndTargetLocal =
                mutationTailPlan.decisions().stream()
                        .filter(decision -> decision.authority()
                                == CoordinatedMutationTailPlan.Authority
                                        .FORBIDDEN)
                        .allMatch(decision -> punctuationOrWhitespaceOnly(
                                canonicalRequest,
                                decision.authorityEndOffset(),
                                decision.endOffset()));
        if (!everyProhibitionIsClosedAndTargetLocal) {
            return legacyContract;
        }
        String outsideForbiddenDecisions = requestWithoutForbiddenDecisionSpans(
                canonicalRequest,
                mutationTailPlan);
        TaskContract primary = TaskContractResolver.fromUserRequest(
                outsideForbiddenDecisions);
        if (!primary.mutationAllowed()
                || primary.expectedTargets().isEmpty()) {
            return legacyContract;
        }
        return new TaskContract(
                primary.type(),
                primary.mutationRequested(),
                primary.mutationAllowed(),
                primary.verificationRequired(),
                primary.expectedTargets(),
                primary.sourceEvidenceTargets(),
                primary.forbiddenTargets(),
                canonicalRequest,
                primary.classificationReason());
    }

    private static Set<String> effectiveSourceEvidenceTargets(
            String canonicalRequest,
            TaskContract authorityContract,
            CoordinatedMutationTailPlan mutationTailPlan
    ) {
        Set<String> sourceTargets = authorityContract == null
                ? Set.of()
                : normalizedPaths(authorityContract.sourceEvidenceTargets());
        LinkedHashSet<String> effective = new LinkedHashSet<>(sourceTargets);
        if (mutationTailPlan == null
                || mutationTailPlan.decisions().isEmpty()) {
            return normalizedPaths(effective);
        }
        Set<String> authoritativeRequiredSources =
                authoritativeRequiredSourceEvidenceTargets(canonicalRequest);
        Set<String> authoritativeDirectSources =
                authoritativeDirectSourceEvidenceTargets(canonicalRequest);
        Set<String> explicitSourceMentions =
                explicitSourceEvidenceTargets(canonicalRequest, false);
        for (String source : explicitSourceMentions) {
            if (!authoritativeRequiredSources.contains(source)
                    && !authoritativeDirectSources.contains(source)) {
                effective.remove(source);
            }
        }
        for (CoordinatedMutationTailPlan.TargetDecision decision
                : mutationTailPlan.decisions()) {
            if (hasExplicitSourceEvidenceBeforeDecision(
                    canonicalRequest,
                    decision.path(),
                    mutationTailPlan)) {
                effective.add(decision.path());
            } else if (effective.contains(decision.path())
                    && !authoritativeRequiredSources.contains(decision.path())
                    && !authoritativeDirectSources.contains(decision.path())) {
                effective.remove(decision.path());
            }
        }
        LinkedHashSet<String> tailOnlyNonAuthority = new LinkedHashSet<>();
        tailOnlyNonAuthority.addAll(mutationTailPlan.pathsWithAuthority(
                CoordinatedMutationTailPlan.Authority.OPTIONAL));
        tailOnlyNonAuthority.addAll(mutationTailPlan.pathsWithAuthority(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE));
        tailOnlyNonAuthority.addAll(mutationTailPlan.pathsWithAuthority(
                CoordinatedMutationTailPlan.Authority.WORKSPACE_OPERATION_BLOCKED));
        if (tailOnlyNonAuthority.isEmpty()) return normalizedPaths(effective);

        String outsideTail =
                requestOutsideOwnedTail(canonicalRequest, mutationTailPlan);
        LinkedHashSet<String> independentlyBoundSources =
                new LinkedHashSet<>(
                        authoritativeRequiredSourceEvidenceTargets(outsideTail));
        independentlyBoundSources.addAll(
                authoritativeDirectSourceEvidenceTargets(outsideTail));
        for (String target : tailOnlyNonAuthority) {
            boolean directlyBoundInTail =
                    hasExplicitSourceEvidenceBeforeDecision(
                            canonicalRequest,
                            target,
                            mutationTailPlan)
                            || authoritativeDirectSources.contains(target);
            if (directlyBoundInTail
                    || independentlyBoundSources.contains(target)) {
                effective.add(target);
            } else {
                effective.remove(target);
            }
        }
        return normalizedPaths(effective);
    }

    private static Set<String> authoritativeRequiredSourceEvidenceTargets(
            String request
    ) {
        return explicitSourceEvidenceTargets(request, true);
    }

    private static Set<String> explicitSourceEvidenceTargets(
            String request,
            boolean requiredOnly
    ) {
        if (request == null || request.isBlank()) return Set.of();
        RequestLexicalView view = RequestLexicalView.of(request);
        Set<String> candidateSet =
                TaskContractResolver.extractExpectedTargets(request);
        if (candidateSet.isEmpty()) return Set.of();
        List<String> candidates = candidateSet.stream()
                .sorted(Comparator
                        .comparingInt(String::length)
                        .reversed()
                        .thenComparing(String::compareTo))
                .toList();
        List<CanonicalMutationClauseMatcher.Action> mutations =
                CanonicalMutationClauseMatcher.actions(
                                view,
                                0,
                                request.length())
                        .stream()
                        .filter(action -> action.family()
                                == CanonicalMutationClauseMatcher.Family
                                        .CONTENT_MUTATION)
                        .toList();
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        Matcher sourceAction = EXPLICIT_SOURCE_EVIDENCE_ACTION.matcher(
                view.maskedLower());
        while (sourceAction.find()) {
            if (requiredOnly
                    && !directSourceEvidenceAction(
                    view,
                    sourceAction.start())) {
                continue;
            }
            int sourceEnd = request.length();
            for (CanonicalMutationClauseMatcher.Action mutation
                    : mutations) {
                if (mutation.start() > sourceAction.start()) {
                    sourceEnd = mutation.start();
                    break;
                }
            }
            sourceEnd = sourceEvidenceInstructionEnd(
                    view,
                    sourceAction.end(),
                    sourceEnd);
            List<CoordinatedMutationTailParser.TargetOccurrence>
                    occurrences =
                    CoordinatedMutationTailParser.targetOccurrences(
                            view,
                            candidates,
                            sourceAction.end(),
                            sourceEnd);
            List<CoordinatedMutationTailParser.TargetOccurrence>
                    bound = new ArrayList<>();
            int priorEnd = sourceAction.end();
            for (CoordinatedMutationTailParser.TargetOccurrence
                    occurrence : occurrences) {
                String gap = view.masked().substring(
                        priorEnd,
                        occurrence.envelopeStart());
                Pattern expectedGap = bound.isEmpty()
                        ? DIRECT_SOURCE_FIRST_OPERAND_PREFIX
                        : DIRECT_SOURCE_ADJACENT_OPERAND_GAP;
                if (!expectedGap.matcher(gap).matches()) break;
                bound.add(occurrence);
                priorEnd = occurrence.envelopeEnd();
            }
            if (bound.isEmpty()) continue;
            if (requiredOnly
                    && !CoordinatedMutationTailParser
                            .requiredLocalActionAuthority(
                                    view,
                                    0,
                                    sourceAction.start(),
                                    bound.getLast().envelopeEnd(),
                                    sourceEnd)) {
                continue;
            }
            bound.stream()
                    .map(CoordinatedMutationTailParser
                            .TargetOccurrence::path)
                    .forEach(sources::add);
        }
        return normalizedPaths(sources);
    }

    public static boolean hasAuthoritativeDirectSourceToOutputBinding(
            String canonicalRequest,
            String target
    ) {
        if (canonicalRequest == null
                || canonicalRequest.isBlank()
                || target == null
                || target.isBlank()) {
            return false;
        }
        String normalized;
        try {
            normalized = TargetRef.normalizePath(target);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return authoritativeDirectSourceEvidenceTargets(canonicalRequest)
                .contains(normalized);
    }

    public static Set<String> authoritativeDirectSourceEvidenceTargets(
            String canonicalRequest
    ) {
        return authoritativeDirectSourceAuthority(canonicalRequest)
                .sourceTargets();
    }

    public static DirectSourceAuthority authoritativeDirectSourceAuthority(
            String canonicalRequest
    ) {
        LinkedHashSet<String> outputs = new LinkedHashSet<>();
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        for (DirectSourceBinding binding
                : directSourceBindings(canonicalRequest)) {
            outputs.addAll(binding.outputPaths());
            for (DirectSourceOperand operand : binding.sourceOperands()) {
                sources.add(operand.path());
            }
        }
        return new DirectSourceAuthority(outputs, sources);
    }

    public static boolean authoritativeDirectSourceConnectorAt(
            String canonicalRequest,
            int markerStart,
            int markerEnd
    ) {
        if (canonicalRequest == null
                || markerStart < 0
                || markerEnd <= markerStart
                || markerEnd > canonicalRequest.length()) {
            return false;
        }
        RequestLexicalView view = RequestLexicalView.of(canonicalRequest);
        Set<String> candidates =
                TaskContractResolver.extractExpectedTargets(
                        canonicalRequest.substring(
                                sourceBindingClauseStart(view, markerStart),
                                markerStart));
        return requiredDirectSourceAction(
                        canonicalRequest,
                        view,
                        markerStart,
                        markerEnd,
                        candidates)
                != null;
    }

    static List<DirectSourceBinding> directSourceBindings(
            String canonicalRequest
    ) {
        String request =
                canonicalRequest == null ? "" : canonicalRequest;
        if (request.isBlank()) return List.of();
        RequestLexicalView view = RequestLexicalView.of(request);
        Set<String> candidateSet =
                TaskContractResolver.extractExpectedTargets(request);
        if (candidateSet.isEmpty()) return List.of();
        List<String> candidates = candidateSet.stream()
                .sorted(Comparator
                        .comparingInt(String::length)
                        .reversed()
                        .thenComparing(String::compareTo))
                .toList();
        List<DirectSourceBinding> bindings = new ArrayList<>();
        Matcher marker = DIRECT_SOURCE_TO_OUTPUT_MARKER.matcher(
                view.maskedLower());
        while (marker.find()) {
            CoordinatedMutationTailParser.LocalContentActionBinding action =
                    requiredDirectSourceAction(
                            request,
                            view,
                            marker.start(),
                            marker.end(),
                            candidateSet);
            if (action == null) continue;

            int sourceEnd = directSourceListEnd(view, marker.end());
            List<CoordinatedMutationTailParser.TargetOccurrence> occurrences =
                    CoordinatedMutationTailParser.targetOccurrences(
                            view,
                            candidates,
                            marker.end(),
                            sourceEnd);
            List<DirectSourceOperand> operands = new ArrayList<>();
            int priorEnd = marker.end();
            boolean disjunctive = false;
            for (CoordinatedMutationTailParser.TargetOccurrence occurrence
                    : occurrences) {
                if (occurrence.envelopeStart() < priorEnd) continue;
                String gap = view.masked().substring(
                        priorEnd,
                        occurrence.envelopeStart());
                Pattern expectedGap = operands.isEmpty()
                        ? DIRECT_SOURCE_FIRST_OPERAND_PREFIX
                        : DIRECT_SOURCE_ADJACENT_OPERAND_GAP;
                if (!expectedGap.matcher(gap).matches()) {
                    if (!operands.isEmpty()
                            && DIRECT_SOURCE_DISJUNCTIVE_GAP
                                    .matcher(gap)
                                    .find()) {
                        disjunctive = true;
                    }
                    break;
                }
                operands.add(new DirectSourceOperand(
                        occurrence.path(),
                        occurrence.envelopeStart(),
                        occurrence.envelopeEnd(),
                        request.substring(
                                occurrence.envelopeStart(),
                                occurrence.envelopeEnd())));
                priorEnd = occurrence.envelopeEnd();
            }
            if (operands.isEmpty()) continue;
            if (DIRECT_SOURCE_DISJUNCTIVE_GAP.matcher(
                    view.masked().substring(priorEnd, sourceEnd)).find()) {
                disjunctive = true;
            }
            if (disjunctive) continue;
            bindings.add(new DirectSourceBinding(
                    marker.start(),
                    marker.end(),
                    request.substring(marker.start(), marker.end()),
                    action.actionStart(),
                    action.actionEnd(),
                    action.outputPaths(),
                    operands));
        }
        return List.copyOf(bindings);
    }

    private static CoordinatedMutationTailParser.LocalContentActionBinding
            requiredDirectSourceAction(
                    String canonicalRequest,
                    RequestLexicalView view,
                    int markerStart,
                    int markerEnd,
                    Set<String> candidates
            ) {
        if (canonicalRequest == null
                || view == null
                || markerStart < 0
                || markerEnd <= markerStart
                || markerEnd > canonicalRequest.length()
                || !view.isUnquotedRange(markerStart, markerEnd)) {
            return null;
        }
        String marker = view.maskedLower().substring(
                markerStart,
                markerEnd);
        if (!marker.equals("according to")
                && !marker.equals("based on")) {
            return null;
        }
        int clauseStart = sourceBindingClauseStart(view, markerStart);
        String context = view.maskedLower().substring(
                clauseStart,
                markerStart);
        String activeContext =
                CanonicalMutationClauseMatcher.activeClause(context);
        if (activeContext.isBlank()
                || NON_AUTHORITATIVE_SOURCE_CONNECTOR_CONTEXT
                        .matcher(activeContext)
                        .find()
                || CanonicalMutationClauseMatcher
                        .hasSubjectBoundReportedContext(activeContext)) {
            return null;
        }
        CoordinatedMutationTailParser.LocalContentActionBinding action =
                CoordinatedMutationTailParser
                .requiredLocalContentActionBinding(
                        view,
                        candidates,
                        clauseStart,
                        markerStart);
        if (action == null) return null;
        String connectorGap = view.maskedLower().substring(
                action.outputEnd(),
                markerStart);
        return DIRECT_SOURCE_OUTPUT_CONNECTOR_GAP
                .matcher(connectorGap)
                .matches()
                ? action
                : null;
    }

    private static int directSourceListEnd(
            RequestLexicalView view,
            int start
    ) {
        for (int offset = Math.max(0, start);
                offset < view.request().length();
                offset++) {
            if (view.isQuoted(offset)) continue;
            char value = view.request().charAt(offset);
            if (value == ';'
                    || value == '\n'
                    || value == '\r'
                    || value == '\u2028'
                    || value == '\u2029'
                    || view.isSentenceBoundaryAt(offset)) {
                return offset;
            }
        }
        return view.request().length();
    }

    private static int sourceBindingClauseStart(
            RequestLexicalView view,
            int targetStart
    ) {
        for (int i = targetStart - 1; i >= 0; i--) {
            if (view.isQuoted(i)) continue;
            char value = view.request().charAt(i);
            if (value == ';'
                    || value == '\n'
                    || value == '\r'
                    || value == '\u2028'
                    || value == '\u2029'
                    || view.isSentenceBoundaryAt(i)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static boolean hasExplicitSourceEvidenceBeforeDecision(
            String canonicalRequest,
            String target,
            CoordinatedMutationTailPlan mutationTailPlan
    ) {
        if (canonicalRequest == null
                || canonicalRequest.isBlank()
                || target == null
                || target.isBlank()
                || mutationTailPlan == null) {
            return false;
        }
        CoordinatedMutationTailPlan.TargetDecision decision =
                mutationTailPlan.decision(target).orElse(null);
        if (decision == null
                || decision.authority()
                        == CoordinatedMutationTailPlan.Authority.FORBIDDEN) {
            return false;
        }

        RequestLexicalView view = RequestLexicalView.of(canonicalRequest);
        int mutationActionStart =
                decisionActionStart(view, decision);
        if (mutationActionStart < 0) return false;
        int scopeStart = Math.max(
                0,
                mutationTailPlan.constraintStart());
        return authoritativeRequiredSourceEvidenceTargets(
                        canonicalRequest.substring(
                                scopeStart,
                                mutationActionStart))
                .contains(TargetRef.normalizePath(target));
    }

    private static int decisionActionStart(
            RequestLexicalView view,
            CoordinatedMutationTailPlan.TargetDecision decision
    ) {
        if (view == null || decision == null) return -1;
        int end = Math.min(
                view.request().length(),
                Math.max(decision.startOffset(), decision.authorityEndOffset()));
        return CanonicalMutationClauseMatcher.actions(
                        view,
                        decision.startOffset(),
                        end)
                .stream()
                .filter(action -> action.family()
                        == CanonicalMutationClauseMatcher.Family
                                .CONTENT_MUTATION)
                .filter(action -> action.verb().equalsIgnoreCase(
                        decision.action()))
                .mapToInt(
                        CanonicalMutationClauseMatcher.Action::start)
                .max()
                .orElse(-1);
    }

    private static boolean directSourceEvidenceAction(
            RequestLexicalView view,
            int actionStart
    ) {
        if (view == null || actionStart < 0) return false;
        int prefixStart = Math.max(0, actionStart - 96);
        String rawPrefix = view.maskedLower()
                .substring(prefixStart, actionStart);
        String prefix = rawPrefix.stripTrailing();
        if (prefix.isEmpty()) return true;
        if (NEGATED_SOURCE_EVIDENCE_ACTION.matcher(prefix).find()) {
            return false;
        }
        String trailingWhitespace = rawPrefix.substring(prefix.length());
        if (trailingWhitespace.indexOf('\n') >= 0
                || trailingWhitespace.indexOf('\r') >= 0
                || trailingWhitespace.indexOf('\u2028') >= 0
                || trailingWhitespace.indexOf('\u2029') >= 0) {
            return true;
        }
        int priorOffset = actionStart - 1;
        while (priorOffset >= 0
                && Character.isWhitespace(
                        view.request().charAt(priorOffset))) {
            priorOffset--;
        }
        if (priorOffset >= 0
                && view.request().charAt(priorOffset) == ';') {
            return true;
        }
        if (priorOffset >= 0
                && view.isSentenceBoundaryAt(priorOffset)) {
            return true;
        }
        return DIRECT_SOURCE_EVIDENCE_CONNECTOR.matcher(prefix).find();
    }

    private static int sourceEvidenceInstructionEnd(
            RequestLexicalView view,
            int actionEnd,
            int limit
    ) {
        int safeLimit = Math.min(
                view.request().length(),
                Math.max(actionEnd, limit));
        for (int i = actionEnd; i < safeLimit; i++) {
            if (view.isQuoted(i)) continue;
            char value = view.request().charAt(i);
            if (value == ';' || value == '\n' || value == '\r') {
                return i;
            }
            if (view.isSentenceBoundaryAt(i)) {
                return i + 1;
            }
        }
        return safeLimit;
    }

    private static String requestWithoutForbiddenDecisionSpans(
            String userRequest,
            CoordinatedMutationTailPlan plan
    ) {
        if (userRequest == null || plan == null || !plan.present()) {
            return userRequest;
        }
        StringBuilder out = new StringBuilder(userRequest);
        for (CoordinatedMutationTailPlan.TargetDecision decision
                : plan.decisions()) {
            if (decision.authority()
                    != CoordinatedMutationTailPlan.Authority.FORBIDDEN) {
                continue;
            }
            int start = Math.max(0, decision.startOffset());
            int end = Math.min(
                    out.length(),
                    decision.authorityEndOffset());
            for (int i = start; i < end; i++) {
                char value = out.charAt(i);
                if (value != '\n' && value != '\r') {
                    out.setCharAt(i, ' ');
                }
            }
        }
        return out.toString();
    }

    private static boolean punctuationOrWhitespaceOnly(
            String request,
            int from,
            int to
    ) {
        if (request == null
                || from < 0
                || to < from
                || to > request.length()) {
            return false;
        }
        for (int i = from; i < to; i++) {
            char value = request.charAt(i);
            if (!Character.isWhitespace(value)
                    && Character.isLetterOrDigit(value)) {
                return false;
            }
        }
        return true;
    }

    public static TaskIntent fromLegacyContract(TaskContract contract) {
        if (contract == null) {
            return new TaskIntent(null, false, false, false, ArtifactTargetSet.empty(), "", "");
        }
        Set<String> expectedTargets = normalizedPaths(contract.expectedTargets());
        Set<String> sourceEvidenceTargets = normalizedPaths(contract.sourceEvidenceTargets());
        Set<String> forbiddenTargets = normalizedPaths(contract.forbiddenTargets());
        ArtifactTargetSet targets = ArtifactTargetSet.empty();
        for (String target : expectedTargets) {
            TargetRole role;
            if (!contract.mutationAllowed()) {
                role = sourceEvidenceTargets.contains(target)
                        ? TargetRole.MUST_READ
                        : TargetRole.MUST_MUTATE;
            } else {
                role = sourceEvidenceTargets.contains(target)
                        ? TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE
                        : TargetRole.MUST_MUTATE;
            }
            targets = targets.with(targetRef(contract.originalUserRequest(), target, role));
        }
        for (String target : sourceEvidenceTargets) {
            if (expectedTargets.contains(target)) {
                continue;
            }
            targets = targets.with(targetRef(contract.originalUserRequest(), target, TargetRole.SOURCE_EVIDENCE));
        }
        for (String target : forbiddenTargets) {
            targets = targets.with(targetRef(contract.originalUserRequest(), target, TargetRole.FORBIDDEN));
        }
        return new TaskIntent(
                contract.type(),
                contract.mutationRequested(),
                contract.mutationAllowed(),
                contract.verificationRequired(),
                targets,
                contract.originalUserRequest(),
                contract.classificationReason());
    }

    private static TaskIntent rolefulIntent(
            TaskType type,
            boolean mutationRequested,
            boolean mutationAllowed,
            boolean verificationRequired,
            Set<String> mutationTargets,
            Set<String> optionalMutationTargets,
            Set<String> verifyOnlyTargets,
            Set<String> forbiddenTargets,
            Set<String> sourceEvidenceTargets,
            Set<String> mentionedOnlyTargets,
            String originalUserRequest,
            String classificationReason,
            Map<String, IntentDerivation> decisionDerivations
    ) {
        ArtifactTargetSet targets = ArtifactTargetSet.empty();
        for (String target : mutationTargets) {
            TargetRole role = sourceEvidenceTargets.contains(target)
                    ? TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE
                    : TargetRole.MUST_MUTATE;
            IntentDerivation derivation = decisionDerivations == null
                    ? null
                    : decisionDerivations.get(target);
            if (derivation != null
                    && !"explicit-mutation-target".equals(
                            derivation.reason())) {
                derivation = null;
            }
            targets = targets.with(derivation == null
                    ? targetRef(originalUserRequest, target, role)
                    : new TargetRef(target, role, derivation));
        }
        for (String target : optionalMutationTargets) {
            if (!sourceEvidenceTargets.contains(target)) {
                targets = targets.with(targetRef(
                        originalUserRequest,
                        target,
                        TargetRole.MAY_MUTATE,
                        decisionDerivations));
            }
        }
        for (String target : verifyOnlyTargets) {
            targets = targets.with(targetRef(
                    originalUserRequest,
                    target,
                    TargetRole.VERIFY_ONLY,
                    decisionDerivations));
        }
        for (String target : sourceEvidenceTargets) {
            if (mutationTargets.contains(target)) {
                continue;
            }
            targets = targets.with(targetRef(originalUserRequest, target, TargetRole.SOURCE_EVIDENCE));
        }
        for (String target : forbiddenTargets) {
            targets = targets.with(targetRef(
                    originalUserRequest,
                    target,
                    TargetRole.FORBIDDEN,
                    decisionDerivations));
        }
        for (String target : mentionedOnlyTargets) {
            targets = targets.with(targetRef(
                    originalUserRequest,
                    target,
                    TargetRole.MENTIONED_ONLY,
                    decisionDerivations));
        }
        return new TaskIntent(
                type,
                mutationRequested,
                mutationAllowed,
                verificationRequired,
                targets,
                originalUserRequest,
                classificationReason);
    }

    private static TargetRef targetRef(String userRequest, String target, TargetRole role) {
        return new TargetRef(target, role, derivationForTarget(userRequest, target, role));
    }

    private static TargetRef targetRef(
            String userRequest,
            String target,
            TargetRole role,
            Map<String, IntentDerivation> decisionDerivations
    ) {
        IntentDerivation derivation = decisionDerivations == null
                ? null
                : decisionDerivations.get(target);
        return derivation == null
                ? targetRef(userRequest, target, role)
                : new TargetRef(target, role, derivation);
    }

    private static IntentDerivation derivationForTarget(String userRequest, String target, TargetRole role) {
        boolean preserveTarget = role == TargetRole.FORBIDDEN
                && normalizedPaths(
                        TaskContractResolver.extractPreserveUnchangedTargets(userRequest))
                .contains(TargetRef.normalizePath(target));
        String reason = switch (role) {
            case FORBIDDEN -> preserveTarget ? "preserve-unchanged-target" : "explicit-forbidden-target";
            case MUST_MUTATE, MUST_MUTATE_AFTER_SOURCE_EVIDENCE, OUTPUT_DESTINATION ->
                    mentionedInMutationClause(userRequest, target)
                    ? "explicit-mutation-target"
                    : "active-contract-projection";
            case VERIFY_ONLY -> "verify-only-constraint-target";
            case SOURCE_EVIDENCE, MUST_READ -> "source-evidence-target";
            case MAY_MUTATE -> "optional-mutation-target";
            case MENTIONED_ONLY -> "mentioned-target";
        };
        TargetSource source = "active-contract-projection".equals(reason)
                ? TargetSource.RUNTIME_DEFAULT
                : TargetSource.USER_REQUEST;
        return new IntentDerivation(
                source,
                reason,
                IntentDerivation.UNKNOWN_OFFSET,
                IntentDerivation.UNKNOWN_OFFSET,
                sourceTextForTarget(userRequest, target),
                1.0);
    }

    private static boolean shouldTreatConstraintTargetsAsVerifyOnly(
            TaskContract legacyContract,
            Set<String> mutationTargets,
            Set<String> verifyOnlyTargets
    ) {
        return legacyContract != null
                && legacyContract.mutationAllowed()
                && !mutationTargets.isEmpty()
                && !verifyOnlyTargets.isEmpty();
    }

    private static boolean shouldApplyExplicitForbiddenTargets(
            TaskContract legacyContract,
            Set<String> mutationTargets,
            Set<String> forbiddenTargets
    ) {
        return legacyContract != null
                && legacyContract.mutationAllowed()
                && !mutationTargets.isEmpty()
                && forbiddenTargets != null
                && !forbiddenTargets.equals(normalizedPaths(legacyContract.forbiddenTargets()));
    }

    private static Set<String> explicitMutationTargets(
            String userRequest,
            TaskContract legacyContract,
            CoordinatedMutationTailPlan mutationTailPlan
    ) {
        if (userRequest == null || userRequest.isBlank()
                || legacyContract == null) {
            return Set.of();
        }
        Set<String> expectedTargets = normalizedPaths(legacyContract.expectedTargets());
        Set<String> forbiddenTargets = normalizedPaths(legacyContract.forbiddenTargets());
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        String legacyScanRequest = requestOutsideOwnedTail(userRequest, mutationTailPlan);
        for (String clause : clauses(legacyScanRequest)) {
            String mutationFragment = mutationFragment(clause);
            String lowerClause = visibleClause(mutationFragment).toLowerCase(Locale.ROOT);
            if (isNegatedClause(lowerClause)
                    || isAdvisoryClause(lowerClause)
                    || !containsExplicitMutationVerb(lowerClause)) {
                continue;
            }
            for (String target : expectedTargets) {
                if (!forbiddenTargets.contains(target) && containsTarget(mutationFragment, target)) {
                    targets.add(target);
                }
            }
        }
        if (mutationTailPlan != null && mutationTailPlan.present()) {
            for (String target : expectedTargets) {
                if (!forbiddenTargets.contains(target)
                        && CanonicalMutationClauseMatcher.directlyBindsContentTarget(
                                legacyScanRequest,
                                target)) {
                    targets.add(target);
                }
            }
        }
        if (mutationTailPlan != null) {
            for (String target : mutationTailPlan.pathsWithAuthority(
                    CoordinatedMutationTailPlan.Authority.REQUIRED)) {
                targets.add(target);
            }
            targets.removeAll(mutationTailPlan.pathsWithAuthority(
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN));
            for (String target : mutationTailPlan.pathsWithAuthority(
                    CoordinatedMutationTailPlan.Authority.OPTIONAL)) {
                if (!hasIndependentRequiredContentBinding(
                        legacyScanRequest,
                        target)) {
                    targets.remove(target);
                }
            }
            for (String target : mutationTailPlan.pathsWithAuthority(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE)) {
                if (!hasIndependentRequiredContentBinding(
                        legacyScanRequest,
                        target)) {
                    targets.remove(target);
                }
            }
            targets.removeAll(mutationTailPlan.pathsWithAuthority(
                    CoordinatedMutationTailPlan.Authority.WORKSPACE_OPERATION_BLOCKED));
        }
        return normalizedPaths(targets);
    }

    private static boolean hasIndependentRequiredContentBinding(
            String request,
            String target
    ) {
        if (request == null || request.isBlank()
                || target == null || target.isBlank()) {
            return false;
        }
        String normalizedTarget = TargetRef.normalizePath(target);
        RequestLexicalView view = RequestLexicalView.of(request);
        return CoordinatedMutationTailParser
                .requiredLocalContentActionBindings(
                        view,
                        Set.of(normalizedTarget),
                        0,
                        request.length())
                .stream()
                .anyMatch(binding ->
                        binding.outputPaths().contains(normalizedTarget));
    }

    private static Set<String> explicitOptionalMutationTargets(
            String userRequest,
            TaskContract legacyContract,
            CoordinatedMutationTailPlan mutationTailPlan
    ) {
        if (userRequest == null || userRequest.isBlank()
                || legacyContract == null) {
            return Set.of();
        }
        Set<String> expectedTargets = normalizedPaths(legacyContract.expectedTargets());
        Set<String> forbiddenTargets = normalizedPaths(legacyContract.forbiddenTargets());
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        String legacyScanRequest = requestOutsideOwnedTail(userRequest, mutationTailPlan);
        for (String clause : clauses(legacyScanRequest)) {
            String mutationFragment = mutationFragment(clause);
            String visibleMutationFragment = visibleClause(mutationFragment);
            String lowerClause = visibleMutationFragment.toLowerCase(Locale.ROOT);
            if (isNegatedClause(lowerClause)
                    || isAdvisoryClause(lowerClause)
                    || !containsExplicitMutationVerb(lowerClause)) {
                continue;
            }
            for (String target : expectedTargets) {
                if (!forbiddenTargets.contains(target)
                        && containsTarget(mutationFragment, target)
                        && hasOptionalMutationQualifier(
                                visibleMutationFragment,
                                target,
                                expectedTargets)) {
                    targets.add(target);
                }
            }
        }
        return normalizedPaths(targets);
    }

    private static Set<String> explicitVerifyOnlyTargets(String userRequest, TaskContract legacyContract) {
        if (userRequest == null || userRequest.isBlank()
                || legacyContract == null
                || legacyContract.expectedTargets().isEmpty()) {
            return Set.of();
        }
        Set<String> expectedTargets = normalizedPaths(legacyContract.expectedTargets());
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String clause : clauses(userRequest)) {
            String fragment = constraintFragment(visibleClause(clause));
            if (fragment.isBlank()) continue;
            for (String target : expectedTargets) {
                if (containsTarget(fragment, target)) {
                    targets.add(target);
                }
            }
        }
        return normalizedPaths(targets);
    }

    private static Set<String> explicitForbiddenTargets(
            String userRequest,
            TaskContract legacyContract,
            CoordinatedMutationTailPlan mutationTailPlan
    ) {
        if (userRequest == null || userRequest.isBlank()
                || legacyContract == null) {
            return legacyContract == null
                    ? Set.of()
                    : normalizedPaths(legacyContract.forbiddenTargets());
        }
        Set<String> expectedTargets = normalizedPaths(legacyContract.expectedTargets());
        Set<String> legacyForbidden =
                normalizedPaths(legacyContract.forbiddenTargets());
        Set<String> planPaths = mutationTailPlan == null
                ? Set.of()
                : mutationTailPlan.decisions().stream()
                        .map(CoordinatedMutationTailPlan.TargetDecision::path)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String outsideTail = requestOutsideOwnedTail(userRequest, mutationTailPlan);
        LinkedHashSet<String> outsideForbidden = new LinkedHashSet<>();
        for (String clause : clauses(outsideTail)) {
            String visible = visibleClause(clause);
            String lowerClause = visible.toLowerCase(Locale.ROOT);
            if (!isNegatedClause(lowerClause)) continue;
            for (String target : expectedTargets) {
                if (containsTarget(visible, target)) {
                    outsideForbidden.add(target);
                }
            }
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String target : legacyForbidden) {
            if (!planPaths.contains(target) || outsideForbidden.contains(target)) {
                targets.add(target);
            }
        }
        targets.addAll(outsideForbidden);
        if (mutationTailPlan != null) {
            targets.addAll(mutationTailPlan.pathsWithAuthority(
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN));
        }
        return normalizedPaths(targets);
    }

    private static Set<String> normalizedPaths(Set<String> paths) {
        if (paths == null || paths.isEmpty()) return Set.of();
        TreeSet<String> sorted = new TreeSet<>();
        for (String path : paths) {
            sorted.add(TargetRef.normalizePath(path));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static String[] clauses(String userRequest) {
        if (userRequest == null || userRequest.isEmpty()) return new String[] {""};
        RequestLexicalView view = RequestLexicalView.of(userRequest);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        Matcher coordinatedNegation = COORDINATED_NEGATION.matcher(view.maskedLower());
        java.util.Map<Integer, Integer> negationBoundaries = new java.util.HashMap<>();
        while (coordinatedNegation.find()) {
            negationBoundaries.put(
                    coordinatedNegation.start(),
                    coordinatedNegation.end());
        }
        int start = 0;
        for (int i = 0; i < userRequest.length(); i++) {
            if (view.isQuoted(i)) continue;
            Integer nextClauseStart = negationBoundaries.get(i);
            if (nextClauseStart != null) {
                if (i > start) out.add(userRequest.substring(start, i));
                start = nextClauseStart;
                i = Math.max(i, start - 1);
                continue;
            }
            char value = userRequest.charAt(i);
            boolean sentence = view.isSentenceBoundaryAt(i);
            boolean separator = value == ';' || value == '\n' || value == '\r';
            if (!sentence && !separator) continue;
            int end = sentence ? i + 1 : i;
            if (end > start) out.add(userRequest.substring(start, end));
            start = i + 1;
            while (start < userRequest.length()
                    && Character.isWhitespace(userRequest.charAt(start))) {
                start++;
            }
            i = Math.max(i, start - 1);
        }
        if (start < userRequest.length()) out.add(userRequest.substring(start));
        return out.toArray(String[]::new);
    }

    private static String visibleClause(String clause) {
        return RequestLexicalView.of(clause).masked();
    }

    private static String mutationFragment(String clause) {
        if (clause == null || clause.isBlank()) return "";
        int boundary = firstConstraintMarkerIndex(clause);
        return boundary < 0 ? clause : clause.substring(0, boundary);
    }

    private static String constraintFragment(String clause) {
        if (clause == null || clause.isBlank()) return "";
        int boundary = firstConstraintMarkerIndex(clause);
        return boundary < 0 ? "" : clause.substring(boundary);
    }

    private static int firstConstraintMarkerIndex(String lowerClause) {
        if (lowerClause == null || lowerClause.isBlank()) return -1;
        return CoordinatedMutationTailParser.firstUnquotedConstraintBoundary(
                RequestLexicalView.of(lowerClause));
    }

    private static boolean hasExactStaticWebFileList(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        return lower.contains("use exactly")
                && lower.contains("index.html")
                && lower.contains("style.css")
                && lower.contains("script.js");
    }

    private static boolean readThenRewriteExistingFiles(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean asksReadFirst = lower.contains("read the current")
                || lower.contains("read current")
                || lower.contains("inspect the current")
                || lower.contains("inspect current")
                || lower.contains("open the current")
                || lower.contains("open current");
        if (!asksReadFirst) return false;
        return lower.contains("then rewrite the existing files")
                || lower.contains("then rewrite existing files")
                || lower.contains("then update the existing files")
                || lower.contains("then update existing files")
                || lower.contains("then edit the existing files")
                || lower.contains("then edit existing files")
                || lower.contains("rewrite the existing files")
                || lower.contains("rewrite existing files")
                || lower.contains("rewrite the current files")
                || lower.contains("update the current files");
    }

    private static boolean isNegatedClause(String lowerClause) {
        String trimmed = lowerClause.stripLeading();
        return trimmed.startsWith("do not ")
                || trimmed.startsWith("don't ")
                || trimmed.startsWith("dont ")
                || trimmed.startsWith("without ");
    }

    private static boolean isAdvisoryClause(String lowerClause) {
        return lowerClause.contains("what would")
                || lowerClause.contains("how would")
                || lowerClause.contains("show me how")
                || lowerClause.contains("explain how")
                || lowerClause.stripLeading().startsWith("review ")
                || lowerClause.stripLeading().startsWith("inspect ")
                || lowerClause.stripLeading().startsWith("check ");
    }

    private static boolean containsExplicitMutationVerb(String lowerClause) {
        return lowerClause.matches("(?s).*\\b(?:improve|edit|update|rewrite|modify|change|fix|"
                + "adjust|tweak|restyle|redesign|polish)\\b.*");
    }

    private static boolean hasOptionalMutationQualifier(String fragment, String target, Set<String> allTargets) {
        if (fragment == null || fragment.isBlank() || target == null || target.isBlank()) return false;
        String lower = normalizePathSeparators(fragment).toLowerCase(Locale.ROOT);
        String lowerTarget = normalizePathSeparators(target).toLowerCase(Locale.ROOT);
        int from = 0;
        while (from >= 0 && from < lower.length()) {
            int targetIndex = lower.indexOf(lowerTarget, from);
            if (targetIndex < 0) return false;
            int targetEnd = targetIndex + lowerTarget.length();
            if (hasOptionalMarkerAfter(lower, targetEnd, allTargets, target)
                    || hasOptionalMarkerBefore(lower, targetIndex, allTargets, target)) {
                return true;
            }
            from = targetEnd;
        }
        return false;
    }

    private static boolean hasOptionalMarkerAfter(
            String lower,
            int targetEnd,
            Set<String> allTargets,
            String target
    ) {
        int end = Math.min(lower.length(), targetEnd + 80);
        OptionalMarker marker = firstOptionalMarker(lower, targetEnd, end);
        return marker != null
                && !containsDifferentTarget(lower.substring(targetEnd, marker.index()), allTargets, target);
    }

    private static boolean hasOptionalMarkerBefore(
            String lower,
            int targetIndex,
            Set<String> allTargets,
            String target
    ) {
        int start = Math.max(0, targetIndex - 80);
        OptionalMarker marker = lastOptionalMarker(lower, start, targetIndex);
        return marker != null
                && !containsDifferentTarget(lower.substring(marker.end(), targetIndex), allTargets, target);
    }

    private static OptionalMarker firstOptionalMarker(String lower, int start, int end) {
        OptionalMarker best = null;
        for (String phrase : OPTIONAL_MUTATION_QUALIFIERS) {
            int index = lower.indexOf(phrase, start);
            if (index >= 0 && index < end && (best == null || index < best.index())) {
                best = new OptionalMarker(index, index + phrase.length());
            }
        }
        return best;
    }

    private static OptionalMarker lastOptionalMarker(String lower, int start, int end) {
        OptionalMarker best = null;
        String window = lower.substring(start, end);
        for (String phrase : OPTIONAL_MUTATION_QUALIFIERS) {
            int index = window.lastIndexOf(phrase);
            if (index >= 0) {
                int absolute = start + index;
                if (best == null || absolute > best.index()) {
                    best = new OptionalMarker(absolute, absolute + phrase.length());
                }
            }
        }
        return best;
    }

    private static boolean containsDifferentTarget(String segment, Set<String> allTargets, String target) {
        if (segment == null || segment.isBlank() || allTargets == null || allTargets.isEmpty()) return false;
        String lowerSegment = normalizePathSeparators(segment).toLowerCase(Locale.ROOT);
        String lowerTarget = target == null
                ? ""
                : normalizePathSeparators(target).toLowerCase(Locale.ROOT);
        for (String candidate : allTargets) {
            if (candidate == null || candidate.isBlank()) continue;
            String lowerCandidate =
                    normalizePathSeparators(candidate).toLowerCase(Locale.ROOT);
            if (!lowerCandidate.equals(lowerTarget) && lowerSegment.contains(lowerCandidate)) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> OPTIONAL_MUTATION_QUALIFIERS = Set.of(
            "only if necessary",
            "only if needed",
            "when necessary",
            "when needed",
            "if necessary",
            "if needed",
            "as necessary",
            "as needed"
    );

    private record OptionalMarker(int index, int end) {}

    private static boolean mentionedInMutationClause(String userRequest, String target) {
        if (userRequest == null || userRequest.isBlank() || target == null) return false;
        for (String clause : clauses(userRequest)) {
            String mutationFragment = mutationFragment(clause);
            String lowerClause = visibleClause(mutationFragment).toLowerCase(Locale.ROOT);
            if (!isNegatedClause(lowerClause)
                    && !isAdvisoryClause(lowerClause)
                    && containsExplicitMutationVerb(lowerClause)
                    && containsTarget(mutationFragment, target)) {
                return true;
            }
        }
        return false;
    }

    private static String sourceTextForTarget(String userRequest, String target) {
        if (userRequest == null || userRequest.isBlank() || target == null) return "";
        for (String clause : clauses(userRequest)) {
            if (containsTarget(clause, target)) {
                return clause.strip();
            }
        }
        return "";
    }

    private static boolean containsTarget(String clause, String target) {
        if (clause == null || target == null || target.isBlank()) return false;
        RequestLexicalView view = RequestLexicalView.of(clause);
        String value = normalizePathSeparators(clause);
        String normalizedTarget;
        try {
            normalizedTarget = TargetRef.normalizePath(target);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        for (int offset = 0;
                offset + normalizedTarget.length() <= value.length();
                offset++) {
            if (!value.regionMatches(
                    false,
                    offset,
                    normalizedTarget,
                    0,
                    normalizedTarget.length())) {
                continue;
            }
            int start = offset;
            while (start >= 2
                    && value.charAt(start - 2) == '.'
                    && value.charAt(start - 1) == '/') {
                start -= 2;
            }
            int end = offset + normalizedTarget.length();
            boolean exactQuotedLiteral = view.isExactQuotedLiteral(start, end);
            if (!exactQuotedLiteral && !view.isUnquotedRange(start, end)) {
                continue;
            }
            boolean left = start == 0 || !targetCharacter(value.charAt(start - 1));
            boolean right = end == value.length()
                    || !targetCharacter(value.charAt(end))
                    || value.charAt(end) == '.'
                    && (end + 1 == value.length()
                    || Character.isWhitespace(value.charAt(end + 1)));
            if (left && right) return true;
        }
        return false;
    }

    private static boolean hasNonLiteralQuotedExpectedTarget(
            String request,
            Set<String> expectedTargets
    ) {
        if (request == null || request.isBlank()
                || expectedTargets == null || expectedTargets.isEmpty()) {
            return false;
        }
        String lexical = normalizePathSeparators(request).toLowerCase(Locale.ROOT);
        for (String target : expectedTargets) {
            String normalized = normalizePathSeparators(target).toLowerCase(Locale.ROOT);
            if (lexical.contains(normalized) && !containsTarget(request, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean targetCharacter(char value) {
        return Character.isLetterOrDigit(value)
                || value == '_'
                || value == '-'
                || value == '.'
                || value == '/'
                || value == '\\';
    }

    private static String normalizePathSeparators(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private static String requestOutsideOwnedTail(
            String userRequest,
            CoordinatedMutationTailPlan plan
    ) {
        if (userRequest == null
                || plan == null
                || !plan.present()
                || plan.constraintStart() < 0
                || plan.tailEnd() > userRequest.length()) {
            return userRequest;
        }
        StringBuilder out = new StringBuilder(userRequest);
        for (int i = plan.constraintStart(); i < plan.tailEnd(); i++) {
            char value = out.charAt(i);
            if (value != '\n' && value != '\r') out.setCharAt(i, ' ');
        }
        return out.toString();
    }

    record DirectSourceBinding(
            int connectorStart,
            int connectorEnd,
            String connectorText,
            int actionStart,
            int actionEnd,
            List<String> outputPaths,
            List<DirectSourceOperand> sourceOperands
    ) {
        DirectSourceBinding {
            if (connectorStart < 0
                    || connectorEnd <= connectorStart
                    || connectorText == null
                    || connectorText.isBlank()
                    || actionStart < 0
                    || actionEnd <= actionStart
                    || actionEnd > connectorStart
                    || outputPaths == null
                    || outputPaths.isEmpty()
                    || sourceOperands == null
                    || sourceOperands.isEmpty()) {
                throw new IllegalArgumentException(
                        "invalid direct-source binding");
            }
            outputPaths = List.copyOf(outputPaths);
            sourceOperands = List.copyOf(sourceOperands);
        }
    }

    record DirectSourceOperand(
            String path,
            int startOffset,
            int endOffset,
            String sourceText
    ) {
        DirectSourceOperand {
            if (path == null
                    || path.isBlank()
                    || startOffset < 0
                    || endOffset <= startOffset
                    || sourceText == null
                    || sourceText.isBlank()) {
                throw new IllegalArgumentException(
                        "invalid direct-source operand");
            }
        }
    }

    public record IntentResolution(
            TaskIntent intent,
            CoordinatedMutationTailPlan mutationTailPlan
    ) {
        public IntentResolution {
            intent = intent == null
                    ? new TaskIntent(null, false, false, false, ArtifactTargetSet.empty(), "", "")
                    : intent;
            mutationTailPlan = mutationTailPlan == null
                    ? CoordinatedMutationTailPlan.empty(intent.originalUserRequest())
                    : mutationTailPlan;
        }
    }

    public record DirectSourceAuthority(
            Set<String> outputTargets,
            Set<String> sourceTargets
    ) {
        public DirectSourceAuthority {
            outputTargets = outputTargets == null
                    ? Set.of()
                    : Collections.unmodifiableSet(
                            new LinkedHashSet<>(outputTargets));
            sourceTargets = sourceTargets == null
                    ? Set.of()
                    : Collections.unmodifiableSet(
                            new LinkedHashSet<>(sourceTargets));
        }
    }
}
