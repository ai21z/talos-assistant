package dev.talos.runtime.intent;

import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adversarial completeness checks for the exact T1204 durable manifest. */
class MutationPlanSelectionExactManifestTest {

    private static final String REPEATED_CROSS_PLAN_REQUEST =
            "Edit index.html so it works, and update style.css too. "
                    + "Update style.css. Rewrite style.css.";
    private static final String WORKSPACE_OBSERVATION_REQUEST =
            "Update style.css so the color works, but maybe update style.css, "
                    + "and delete cache.tmp.";

    @Test
    void completeExactManifestMatchesBothAuthoritativePlans() {
        Fixture fixture = fixture(REPEATED_CROSS_PLAN_REQUEST);

        assertFalse(fixture.policy().lexicalDecisions().isEmpty());
        assertFalse(fixture.policy().lexicalRegions().isEmpty());
        requireManifest(
                fixture,
                fixture.policy().lexicalDecisions(),
                fixture.policy().lexicalRegions());
    }

    @Test
    void sameSpanWorkspaceObservationsUseTheDurableManifestOrder() {
        Fixture fixture = fixture(
                "Make it work so create docs and assets, then copy "
                        + "style.css to docs/style.css.");

        assertTrue(fixture.policy().lexicalRegions().stream()
                .filter(region -> "WORKSPACE_OPERATION_TARGET_OBSERVATION"
                        .equals(region.kind()))
                .count() >= 2);
        requireManifest(
                fixture,
                fixture.policy().lexicalDecisions(),
                fixture.policy().lexicalRegions());
    }

    @Test
    void recomputedAuthorityForgeryCannotReplaceParserAuthority() {
        Fixture fixture = fixture("Edit index.html. Update style.css.");
        TurnPolicyTrace.LexicalDecisionEvidence original =
                fixture.policy().lexicalDecisions().getFirst();
        assertEquals("REQUIRED", original.authority());
        TurnPolicyTrace.LexicalDecisionEvidence forged = decision(
                original,
                original.ordinal(),
                original.owner(),
                "OPTIONAL",
                original.selectedForPath(),
                original.activeInResolution());
        List<TurnPolicyTrace.LexicalDecisionEvidence> decisions =
                List.of(forged);

        requireStructurallyCanonicalDecisions(fixture, decisions);
        assertSemanticManifestRejection(
                fixture,
                decisions,
                fixture.policy().lexicalRegions(),
                "decision");
    }

    @Test
    void recomputedOwnerForgeryCannotMoveAuthorityBetweenParsers() {
        Fixture fixture = fixture("Edit index.html. Update style.css.");
        TurnPolicyTrace.LexicalDecisionEvidence original =
                fixture.policy().lexicalDecisions().getFirst();
        assertEquals(
                MutationPlanSelection.Owner.SEQUENTIAL_MUTATION_CLAUSE.name(),
                original.owner());
        TurnPolicyTrace.LexicalDecisionEvidence forged = decision(
                original,
                original.ordinal(),
                MutationPlanSelection.Owner.COORDINATED_MUTATION_TAIL.name(),
                original.authority(),
                original.selectedForPath(),
                original.activeInResolution());
        List<TurnPolicyTrace.LexicalDecisionEvidence> decisions =
                List.of(forged);

        requireStructurallyCanonicalDecisions(fixture, decisions);
        assertSemanticManifestRejection(
                fixture,
                decisions,
                fixture.policy().lexicalRegions(),
                "decision");
    }

    @Test
    void recomputedRegionKindForgeryCannotChangeParserOwnedMeaning() {
        Fixture fixture = fixture(WORKSPACE_OBSERVATION_REQUEST);
        TurnPolicyTrace.LexicalRegionEvidence original = region(
                fixture,
                "CONSTRAINT_TAIL");
        TurnPolicyTrace.LexicalRegionEvidence forged = region(
                original,
                original.ordinal(),
                original.owner(),
                "WORKSPACE_OPERATION_CLAUSE_OBSERVATION",
                "remove",
                "");
        List<TurnPolicyTrace.LexicalRegionEvidence> regions = replaceRegion(
                fixture.policy().lexicalRegions(),
                original.ordinal(),
                forged);

        requireStructurallyCanonicalRegions(fixture, regions);
        assertSemanticManifestRejection(
                fixture,
                fixture.policy().lexicalDecisions(),
                regions,
                "region");
    }

    @Test
    void recomputedRegionActionForgeryCannotChangeObservedOperation() {
        Fixture fixture = fixture(WORKSPACE_OBSERVATION_REQUEST);
        TurnPolicyTrace.LexicalRegionEvidence original = region(
                fixture,
                "WORKSPACE_OPERATION_TARGET_OBSERVATION");
        assertEquals("delete", original.action());
        TurnPolicyTrace.LexicalRegionEvidence forged = region(
                original,
                original.ordinal(),
                original.owner(),
                original.kind(),
                "remove",
                original.pathSha256());
        List<TurnPolicyTrace.LexicalRegionEvidence> regions = replaceRegion(
                fixture.policy().lexicalRegions(),
                original.ordinal(),
                forged);

        requireStructurallyCanonicalRegions(fixture, regions);
        assertSemanticManifestRejection(
                fixture,
                fixture.policy().lexicalDecisions(),
                regions,
                "region");
    }

    @Test
    void recomputedRegionPathForgeryCannotRetargetObservedOperation() {
        Fixture fixture = fixture(WORKSPACE_OBSERVATION_REQUEST);
        TurnPolicyTrace.LexicalRegionEvidence original = region(
                fixture,
                "WORKSPACE_OPERATION_TARGET_OBSERVATION");
        TurnPolicyTrace.LexicalRegionEvidence forged = region(
                original,
                original.ordinal(),
                original.owner(),
                original.kind(),
                original.action(),
                MutationPlanSelection.pathSha256("other.tmp"));
        List<TurnPolicyTrace.LexicalRegionEvidence> regions = replaceRegion(
                fixture.policy().lexicalRegions(),
                original.ordinal(),
                forged);

        requireStructurallyCanonicalRegions(fixture, regions);
        assertSemanticManifestRejection(
                fixture,
                fixture.policy().lexicalDecisions(),
                regions,
                "region");
    }

    @Test
    void decisionManifestRejectsMissingExtraReorderedAndEmptyArrays() {
        Fixture fixture = fixture(REPEATED_CROSS_PLAN_REQUEST);
        List<TurnPolicyTrace.LexicalDecisionEvidence> original =
                fixture.policy().lexicalDecisions();
        assertEquals(3, original.size());

        List<TurnPolicyTrace.LexicalDecisionEvidence> missing =
                renumberDecisions(original.subList(1, original.size()));
        requireStructurallyCanonicalDecisions(fixture, missing);
        assertSemanticManifestRejection(
                fixture,
                missing,
                fixture.policy().lexicalRegions(),
                "complete parser manifest");

        TurnPolicyTrace.LexicalDecisionEvidence first = original.getFirst();
        TurnPolicyTrace.LexicalDecisionEvidence synthetic = decision(
                first,
                original.size(),
                first.owner(),
                "NON_AUTHORITATIVE",
                false,
                false);
        ArrayList<TurnPolicyTrace.LexicalDecisionEvidence> expanded =
                new ArrayList<>(original);
        expanded.add(synthetic);
        List<TurnPolicyTrace.LexicalDecisionEvidence> extra =
                sortAndRenumberDecisions(expanded);
        requireStructurallyCanonicalDecisions(fixture, extra);
        assertSemanticManifestRejection(
                fixture,
                extra,
                fixture.policy().lexicalRegions(),
                "complete parser manifest");

        ArrayList<TurnPolicyTrace.LexicalDecisionEvidence> reversed =
                new ArrayList<>(original);
        java.util.Collections.reverse(reversed);
        IllegalArgumentException reordered = assertThrows(
                IllegalArgumentException.class,
                () -> requireManifest(
                        fixture,
                        renumberDecisions(reversed),
                        fixture.policy().lexicalRegions()));
        assertTrue(reordered.getMessage().contains("source order"),
                reordered.getMessage());

        assertSemanticManifestRejection(
                fixture,
                List.of(),
                fixture.policy().lexicalRegions(),
                "complete parser manifest");
    }

    @Test
    void regionManifestRejectsMissingExtraReorderedAndEmptyArrays() {
        Fixture fixture = fixture(WORKSPACE_OBSERVATION_REQUEST);
        List<TurnPolicyTrace.LexicalRegionEvidence> original =
                fixture.policy().lexicalRegions();
        assertTrue(original.size() > 2);

        List<TurnPolicyTrace.LexicalRegionEvidence> missing =
                renumberRegions(original.subList(1, original.size()));
        requireStructurallyCanonicalRegions(fixture, missing);
        assertSemanticManifestRejection(
                fixture,
                fixture.policy().lexicalDecisions(),
                missing,
                "complete parser manifest");

        TurnPolicyTrace.LexicalRegionEvidence constraint = region(
                fixture,
                "CONSTRAINT_TAIL");
        TurnPolicyTrace.LexicalRegionEvidence synthetic = region(
                constraint,
                original.size(),
                constraint.owner(),
                "WORKSPACE_OPERATION_CLAUSE_OBSERVATION",
                "remove",
                "");
        ArrayList<TurnPolicyTrace.LexicalRegionEvidence> expanded =
                new ArrayList<>(original);
        expanded.add(synthetic);
        List<TurnPolicyTrace.LexicalRegionEvidence> extra =
                sortAndRenumberRegions(expanded);
        requireStructurallyCanonicalRegions(fixture, extra);
        assertSemanticManifestRejection(
                fixture,
                fixture.policy().lexicalDecisions(),
                extra,
                "complete parser manifest");

        ArrayList<TurnPolicyTrace.LexicalRegionEvidence> reversed =
                new ArrayList<>(original);
        java.util.Collections.reverse(reversed);
        IllegalArgumentException reordered = assertThrows(
                IllegalArgumentException.class,
                () -> requireManifest(
                        fixture,
                        fixture.policy().lexicalDecisions(),
                        renumberRegions(reversed)));
        assertTrue(reordered.getMessage().contains("source order"),
                reordered.getMessage());

        assertSemanticManifestRejection(
                fixture,
                fixture.policy().lexicalDecisions(),
                List.of(),
                "complete parser manifest");
    }

    @Test
    void repeatedCrossPlanOccurrencesRemainCompleteAndIndividuallyRequired() {
        Fixture fixture = fixture(REPEATED_CROSS_PLAN_REQUEST);
        List<TurnPolicyTrace.LexicalDecisionEvidence> decisions =
                fixture.policy().lexicalDecisions();

        assertEquals(3, decisions.stream()
                .filter(decision -> "style.css".equals(decision.path()))
                .count());
        assertEquals(
                List.of(
                        MutationPlanSelection.Owner
                                .COORDINATED_MUTATION_TAIL.name(),
                        MutationPlanSelection.Owner
                                .SEQUENTIAL_MUTATION_CLAUSE.name(),
                        MutationPlanSelection.Owner
                                .SEQUENTIAL_MUTATION_CLAUSE.name()),
                decisions.stream()
                        .map(TurnPolicyTrace.LexicalDecisionEvidence::owner)
                        .toList());
        requireManifest(
                fixture,
                decisions,
                fixture.policy().lexicalRegions());

        List<TurnPolicyTrace.LexicalDecisionEvidence> withoutTailOccurrence =
                renumberDecisions(decisions.stream()
                        .filter(decision -> !MutationPlanSelection.Owner
                                .COORDINATED_MUTATION_TAIL.name().equals(
                                        decision.owner()))
                        .toList());
        requireStructurallyCanonicalDecisions(
                fixture,
                withoutTailOccurrence);
        assertSemanticManifestRejection(
                fixture,
                withoutTailOccurrence,
                fixture.policy().lexicalRegions(),
                "complete parser manifest");
    }

    @Test
    void lfAndCrlfRequestsHaveSeparateCompleteIdentitiesAndCannotCrossBind() {
        String lfRequest = "Edit index.html.\nUpdate style.css.";
        String crlfRequest = lfRequest.replace("\n", "\r\n");
        Fixture lf = fixture(lfRequest);
        Fixture crlf = fixture(crlfRequest);

        requireManifest(
                lf,
                lf.policy().lexicalDecisions(),
                lf.policy().lexicalRegions());
        requireManifest(
                crlf,
                crlf.policy().lexicalDecisions(),
                crlf.policy().lexicalRegions());
        assertNotEquals(
                lf.policy().lexicalDecisions().getFirst()
                        .sourceRequestSha256(),
                crlf.policy().lexicalDecisions().getFirst()
                        .sourceRequestSha256());
        assertNotEquals(
                lf.policy().lexicalDecisions().getFirst()
                        .sourceRequestChars(),
                crlf.policy().lexicalDecisions().getFirst()
                        .sourceRequestChars());

        assertThrows(
                IllegalArgumentException.class,
                () -> requireManifest(
                        lf,
                        crlf.policy().lexicalDecisions(),
                        crlf.policy().lexicalRegions()));
        assertThrows(
                IllegalArgumentException.class,
                () -> requireManifest(
                        crlf,
                        lf.policy().lexicalDecisions(),
                        lf.policy().lexicalRegions()));
    }

    @Test
    void combinedEvidenceRowBoundaryCountsBothParserOwners() {
        int sequentialRows =
                SequentialMutationClausePlan.MAX_EVIDENCE_ROWS - 1;
        String request = "x ".repeat(
                SequentialMutationClausePlan.MAX_EVIDENCE_ROWS);
        List<SequentialMutationClausePlan.SourceSpan> spans =
                new ArrayList<>(sequentialRows + 1);
        for (int index = 0; index < sequentialRows; index++) {
            spans.add(new SequentialMutationClausePlan.SourceSpan(
                    index * 2,
                    index * 2 + 1));
        }
        CoordinatedMutationTailPlan tail = new CoordinatedMutationTailPlan(
                request,
                request.length() - 1,
                request.length(),
                List.of());
        SequentialMutationClausePlan exact =
                SequentialMutationClausePlan.empty(request, spans);

        assertEquals(
                SequentialMutationClausePlan.MAX_EVIDENCE_ROWS,
                MutationPlanSelection.evidenceRowCount(tail, exact));

        spans.add(new SequentialMutationClausePlan.SourceSpan(
                sequentialRows * 2,
                sequentialRows * 2 + 1));
        SequentialMutationClausePlan oneOver =
                SequentialMutationClausePlan.empty(request, spans);
        assertEquals(
                SequentialMutationClausePlan.MAX_EVIDENCE_ROWS + 1L,
                MutationPlanSelection.evidenceRowCount(tail, oneOver));
    }

    @Test
    void combinedEvidenceCharacterBoundaryDeduplicatesOnlyExactSpans() {
        int maximum = (int) SequentialMutationClausePlan
                .MAX_RETAINED_EVIDENCE_CHARACTERS;
        String request = "x".repeat(maximum + 1);
        CoordinatedMutationTailPlan exactTail =
                new CoordinatedMutationTailPlan(
                        request,
                        0,
                        maximum,
                        List.of());
        SequentialMutationClausePlan identical =
                SequentialMutationClausePlan.empty(
                        request,
                        List.of(new SequentialMutationClausePlan.SourceSpan(
                                0,
                                maximum)));
        assertFalse(MutationPlanSelection.exceedsEvidenceCharacters(
                exactTail,
                identical));

        SequentialMutationClausePlan oneOver =
                SequentialMutationClausePlan.empty(
                        request,
                        List.of(new SequentialMutationClausePlan.SourceSpan(
                                0,
                                maximum + 1)));
        assertTrue(MutationPlanSelection.exceedsEvidenceCharacters(
                CoordinatedMutationTailPlan.empty(request),
                oneOver));

        CoordinatedMutationTailPlan overlapTail =
                new CoordinatedMutationTailPlan(
                        request,
                        0,
                        600_000,
                        List.of());
        SequentialMutationClausePlan partialOverlap =
                SequentialMutationClausePlan.empty(
                        request,
                        List.of(new SequentialMutationClausePlan.SourceSpan(
                                500_000,
                                1_000_000)));
        assertTrue(MutationPlanSelection.exceedsEvidenceCharacters(
                overlapTail,
                partialOverlap));
    }

    @Test
    void projectedRolefulEvidenceHasAnExactExpandedBoundary() {
        int half = (int) MutationPlanSelection
                .MAX_PROJECTED_ROLEFUL_SOURCE_CHARACTERS / 2;
        MutationPlanSelection.SelectedDecision first = projectedDecision(
                "first.css",
                "edit x" + " ".repeat(half - 6));
        MutationPlanSelection.SelectedDecision second = projectedDecision(
                "second.css",
                "edit x" + " ".repeat(half - 6));

        assertFalse(MutationPlanSelection
                .exceedsProjectedRolefulSourceCharacters(
                        List.of(first, second)));

        MutationPlanSelection.SelectedDecision oneOver = projectedDecision(
                "second.css",
                "edit x" + " ".repeat(half - 5));
        assertTrue(MutationPlanSelection
                .exceedsProjectedRolefulSourceCharacters(
                        List.of(first, oneOver)));
    }

    private static MutationPlanSelection.SelectedDecision projectedDecision(
            String path,
            String sourceText
    ) {
        return new MutationPlanSelection.SelectedDecision(
                path,
                MutationPlanSelection.Owner.SEQUENTIAL_MUTATION_CLAUSE,
                "REQUIRED",
                "edit",
                "CONTENT_MUTATION",
                0,
                4,
                5,
                0,
                6,
                sourceText.length(),
                sourceText,
                new IntentDerivation(
                        TargetSource.USER_REQUEST,
                        "expanded-role-evidence-boundary",
                        0,
                        6,
                        sourceText.length(),
                        sourceText,
                        1.0),
                4);
    }

    private static Fixture fixture(String request) {
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        TurnPolicyTrace policy = TurnPolicyTrace.from(
                resolution,
                "APPLY",
                List.of("talos.edit_file"),
                List.of("tool_use:edit_file"));
        return new Fixture(request.strip(), resolution, policy);
    }

    private static void requireManifest(
            Fixture fixture,
            List<TurnPolicyTrace.LexicalDecisionEvidence> decisions,
            List<TurnPolicyTrace.LexicalRegionEvidence> regions
    ) {
        MutationPlanSelection.requireCanonicalDurableManifest(
                fixture.request(),
                fixture.resolution().mutationTailPlan(),
                fixture.resolution().sequentialMutationPlan(),
                fixture.resolution().activeLexicalPaths(),
                decisions,
                regions);
    }

    private static void requireStructurallyCanonicalDecisions(
            Fixture fixture,
            List<TurnPolicyTrace.LexicalDecisionEvidence> decisions
    ) {
        MutationPlanSelection.validatedDurableDecisions(decisions);
    }

    private static void requireStructurallyCanonicalRegions(
            Fixture fixture,
            List<TurnPolicyTrace.LexicalRegionEvidence> regions
    ) {
        MutationPlanSelection.validatedDurableRegions(regions);
    }

    private static void assertSemanticManifestRejection(
            Fixture fixture,
            List<TurnPolicyTrace.LexicalDecisionEvidence> decisions,
            List<TurnPolicyTrace.LexicalRegionEvidence> regions,
            String expectedMessage
    ) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> requireManifest(fixture, decisions, regions));
        assertTrue(error.getMessage().contains(expectedMessage),
                error.getMessage());
    }

    private static TurnPolicyTrace.LexicalRegionEvidence region(
            Fixture fixture,
            String kind
    ) {
        return fixture.policy().lexicalRegions().stream()
                .filter(region -> kind.equals(region.kind()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "fixture lacks region " + kind + ": "
                                + fixture.policy().lexicalRegions()));
    }

    private static List<TurnPolicyTrace.LexicalRegionEvidence> replaceRegion(
            List<TurnPolicyTrace.LexicalRegionEvidence> source,
            int ordinal,
            TurnPolicyTrace.LexicalRegionEvidence replacement
    ) {
        ArrayList<TurnPolicyTrace.LexicalRegionEvidence> out =
                new ArrayList<>(source);
        out.set(ordinal, replacement);
        return List.copyOf(out);
    }

    private static List<TurnPolicyTrace.LexicalDecisionEvidence>
            sortAndRenumberDecisions(
                    List<TurnPolicyTrace.LexicalDecisionEvidence> source
            ) {
        ArrayList<TurnPolicyTrace.LexicalDecisionEvidence> sorted =
                new ArrayList<>(source);
        sorted.sort(Comparator
                .comparingInt(TurnPolicyTrace.LexicalDecisionEvidence
                        ::startOffset)
                .thenComparingInt(TurnPolicyTrace.LexicalDecisionEvidence
                        ::actionStartOffset)
                .thenComparingInt(TurnPolicyTrace.LexicalDecisionEvidence
                        ::actionEndOffset)
                .thenComparingInt(TurnPolicyTrace.LexicalDecisionEvidence
                        ::targetStartOffset)
                .thenComparingInt(TurnPolicyTrace.LexicalDecisionEvidence
                        ::authorityEndOffset)
                .thenComparingInt(TurnPolicyTrace.LexicalDecisionEvidence
                        ::endOffset)
                .thenComparing(TurnPolicyTrace.LexicalDecisionEvidence::owner)
                .thenComparing(TurnPolicyTrace.LexicalDecisionEvidence::path)
                .thenComparing(
                        TurnPolicyTrace.LexicalDecisionEvidence::authority)
                .thenComparing(
                        TurnPolicyTrace.LexicalDecisionEvidence::actionFamily)
                .thenComparing(TurnPolicyTrace.LexicalDecisionEvidence::action)
                .thenComparing(TurnPolicyTrace.LexicalDecisionEvidence
                        ::occurrenceSha256));
        return renumberDecisions(sorted);
    }

    private static List<TurnPolicyTrace.LexicalDecisionEvidence>
            renumberDecisions(
                    List<TurnPolicyTrace.LexicalDecisionEvidence> source
            ) {
        ArrayList<TurnPolicyTrace.LexicalDecisionEvidence> out =
                new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            TurnPolicyTrace.LexicalDecisionEvidence value = source.get(index);
            out.add(decision(
                    value,
                    index,
                    value.owner(),
                    value.authority(),
                    value.selectedForPath(),
                    value.activeInResolution()));
        }
        return List.copyOf(out);
    }

    private static List<TurnPolicyTrace.LexicalRegionEvidence>
            sortAndRenumberRegions(
                    List<TurnPolicyTrace.LexicalRegionEvidence> source
            ) {
        ArrayList<TurnPolicyTrace.LexicalRegionEvidence> sorted =
                new ArrayList<>(source);
        sorted.sort(Comparator
                .comparingInt(TurnPolicyTrace.LexicalRegionEvidence
                        ::startOffset)
                .thenComparingInt(TurnPolicyTrace.LexicalRegionEvidence
                        ::endOffset)
                .thenComparing(TurnPolicyTrace.LexicalRegionEvidence::owner)
                .thenComparing(TurnPolicyTrace.LexicalRegionEvidence::kind)
                .thenComparing(TurnPolicyTrace.LexicalRegionEvidence::action)
                .thenComparing(
                        TurnPolicyTrace.LexicalRegionEvidence::pathSha256)
                .thenComparing(
                        TurnPolicyTrace.LexicalRegionEvidence::regionSha256));
        return renumberRegions(sorted);
    }

    private static List<TurnPolicyTrace.LexicalRegionEvidence>
            renumberRegions(List<TurnPolicyTrace.LexicalRegionEvidence> source) {
        ArrayList<TurnPolicyTrace.LexicalRegionEvidence> out =
                new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            TurnPolicyTrace.LexicalRegionEvidence value = source.get(index);
            out.add(region(
                    value,
                    index,
                    value.owner(),
                    value.kind(),
                    value.action(),
                    value.pathSha256()));
        }
        return List.copyOf(out);
    }

    private static TurnPolicyTrace.LexicalDecisionEvidence decision(
            TurnPolicyTrace.LexicalDecisionEvidence source,
            int ordinal,
            String owner,
            String authority,
            boolean selectedForPath,
            boolean activeInResolution
    ) {
        String occurrenceSha256 = MutationPlanSelection.occurrenceSha256(
                owner,
                source.pathSha256(),
                authority,
                source.action(),
                source.actionFamily(),
                source.source(),
                source.sourceRequestSha256(),
                source.sourceRequestChars(),
                source.sourceRequestBytes(),
                source.actionStartOffset(),
                source.actionEndOffset(),
                source.targetStartOffset(),
                source.startOffset(),
                source.authorityEndOffset(),
                source.endOffset(),
                source.sourceSpanSha256());
        return new TurnPolicyTrace.LexicalDecisionEvidence(
                ordinal,
                owner,
                source.path(),
                source.pathSha256(),
                source.storedPathSha256(),
                authority,
                source.action(),
                source.actionFamily(),
                source.source(),
                source.evidenceState(),
                source.sourceRequestSha256(),
                source.sourceRequestChars(),
                source.sourceRequestBytes(),
                source.actionStartOffset(),
                source.actionEndOffset(),
                source.targetStartOffset(),
                source.startOffset(),
                source.authorityEndOffset(),
                source.endOffset(),
                selectedForPath,
                activeInResolution,
                source.sourceSpanSha256(),
                occurrenceSha256);
    }

    private static TurnPolicyTrace.LexicalRegionEvidence region(
            TurnPolicyTrace.LexicalRegionEvidence source,
            int ordinal,
            String owner,
            String kind,
            String action,
            String pathSha256
    ) {
        String regionSha256 = MutationPlanSelection.regionSha256(
                owner,
                kind,
                source.source(),
                source.sourceRequestSha256(),
                source.sourceRequestChars(),
                source.sourceRequestBytes(),
                action,
                pathSha256,
                source.startOffset(),
                source.endOffset(),
                source.sourceSpanSha256());
        return new TurnPolicyTrace.LexicalRegionEvidence(
                ordinal,
                owner,
                kind,
                source.source(),
                source.evidenceState(),
                source.sourceRequestSha256(),
                source.sourceRequestChars(),
                source.sourceRequestBytes(),
                action,
                pathSha256,
                source.startOffset(),
                source.endOffset(),
                source.sourceSpanSha256(),
                regionSha256);
    }

    private record Fixture(
            String request,
            TaskResolution resolution,
            TurnPolicyTrace policy
    ) {}
}
