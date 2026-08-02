package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequentialMutationClausePlanInvariantTest {

    @Test
    void oneExactTargetOccurrenceCannotBeClaimedByTwoActions() {
        String request = "Edit and update style.css.";
        int targetStart = request.indexOf("style.css");
        int targetEnd = targetStart + "style.css".length();
        var region = new SequentialMutationClausePlan.OwnedRegion(
                SequentialMutationClausePlan.RegionKind.CONTENT_MUTATION,
                0,
                request.length(),
                request);
        var edit = decision(
                "style.css",
                request,
                "Edit",
                0,
                4,
                targetStart,
                targetEnd);
        int updateStart = request.indexOf("update");
        var update = decision(
                "style.css",
                request,
                "update",
                updateStart,
                updateStart + "update".length(),
                targetStart,
                targetEnd);

        assertDoesNotThrow(() -> new SequentialMutationClausePlan(
                request, List.of(), List.of(edit), List.of(region)));
        assertDoesNotThrow(() -> new SequentialMutationClausePlan(
                request, List.of(), List.of(update), List.of(region)));
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        request,
                        List.of(),
                        List.of(edit, update),
                        List.of(region)));
    }

    @Test
    void sameClauseTargetsMustRemainInExactSourceOrder() {
        String request = "Edit index.html. Update a.css and b.css.";
        SequentialMutationClausePlan parsed = SequentialMutationClauseParser.parse(
                request,
                Set.of("index.html", "a.css", "b.css"));
        assertEquals(
                List.of("a.css", "b.css"),
                parsed.occurrenceDecisions().stream()
                        .map(SequentialMutationClausePlan.ClauseDecision::path)
                        .toList());

        ArrayList<SequentialMutationClausePlan.ClauseDecision> reversed =
                new ArrayList<>(parsed.occurrenceDecisions());
        Collections.reverse(reversed);
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        request,
                        parsed.excludedSpans(),
                        reversed,
                        parsed.ownedRegions()));
    }

    @Test
    void nullAndDuplicateEvidenceFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        "", null, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        "", List.of(), null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        "", List.of(), List.of(), null));

        ArrayList<SequentialMutationClausePlan.SourceSpan> nullSpans =
                new ArrayList<>();
        nullSpans.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        "x", nullSpans, List.of(), List.of()));

        ArrayList<SequentialMutationClausePlan.ClauseDecision> nullDecisions =
                new ArrayList<>();
        nullDecisions.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        "", List.of(), nullDecisions, List.of()));

        ArrayList<SequentialMutationClausePlan.OwnedRegion> nullRegions =
                new ArrayList<>();
        nullRegions.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        "", List.of(), List.of(), nullRegions));

        var span = new SequentialMutationClausePlan.SourceSpan(0, 1);
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        "xy", List.of(span, span), List.of(), List.of()));

        var region = new SequentialMutationClausePlan.OwnedRegion(
                SequentialMutationClausePlan.RegionKind
                        .UNCLASSIFIED_TARGET_CLAUSE,
                0,
                1,
                "x");
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        "x", List.of(), List.of(), List.of(region, region)));

        String request = "Edit index.html. Update style.css.";
        SequentialMutationClausePlan parsed =
                SequentialMutationClauseParser.parse(
                        request,
                        Set.of("index.html", "style.css"));
        var duplicateDecision = parsed.occurrenceDecisions().getFirst();
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        request,
                        parsed.excludedSpans(),
                        List.of(duplicateDecision, duplicateDecision),
                        parsed.ownedRegions()));
    }

    @Test
    void controlTaintedRegionRequiresExactControlBytesAndMasksLegacyParsing() {
        String tainted = "Upda\u0000te b.css.";
        SequentialMutationClausePlan parsed = SequentialMutationClauseParser.parse(
                tainted,
                Set.of("b.css"));

        assertEquals(List.of(), parsed.occurrenceDecisions());
        assertEquals(1, parsed.ownedRegions().size());
        assertEquals(
                SequentialMutationClausePlan.RegionKind
                        .PROVIDER_REMOVED_CONTROL_CLAUSE,
                parsed.ownedRegions().getFirst().kind());
        assertEquals(
                List.of(parsed.ownedRegions().getFirst().span()),
                parsed.legacyMutationSpans());

        String clean = "Update b.css.";
        var forged = new SequentialMutationClausePlan.OwnedRegion(
                SequentialMutationClausePlan.RegionKind
                        .PROVIDER_REMOVED_CONTROL_CLAUSE,
                0,
                clean.length(),
                clean);
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        clean, List.of(), List.of(), List.of(forged)));
    }

    @Test
    void targetIndexQuarantineIsOneBoundedStatusWithoutPartialEvidence() {
        String request = "left-middle-right";
        var excluded = new SequentialMutationClausePlan.SourceSpan(4, 12);
        var left = new SequentialMutationClausePlan.OwnedRegion(
                SequentialMutationClausePlan.RegionKind
                        .TARGET_OCCURRENCE_LIMIT_QUARANTINE,
                0,
                4,
                "left");
        var right = new SequentialMutationClausePlan.OwnedRegion(
                SequentialMutationClausePlan.RegionKind
                        .TARGET_OCCURRENCE_LIMIT_QUARANTINE,
                12,
                request.length(),
                "right");

        SequentialMutationClausePlan limited = assertDoesNotThrow(() ->
                SequentialMutationClausePlan.authorityLimit(
                        request,
                        List.of(excluded),
                        SequentialMutationClausePlan.RegionKind
                                .TARGET_OCCURRENCE_LIMIT_QUARANTINE));
        assertTrue(limited.occurrenceDecisions().isEmpty());
        assertTrue(limited.ownedRegions().isEmpty());
        assertEquals(List.of(excluded), limited.excludedSpans());
        assertEquals(
                SequentialMutationClausePlan.RegionKind
                        .TARGET_OCCURRENCE_LIMIT_QUARANTINE,
                limited.authorityLimit().kind());
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        request,
                        List.of(excluded),
                        List.of(),
                        List.of(left, right)));

        var wrongKind = new SequentialMutationClausePlan.OwnedRegion(
                SequentialMutationClausePlan.RegionKind
                        .TARGET_OUTPUT_LIMIT_QUARANTINE,
                12,
                request.length(),
                "right");
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        request,
                        List.of(excluded),
                        List.of(),
                        List.of(left, wrongKind)));

        var unrelated = new SequentialMutationClausePlan.OwnedRegion(
                SequentialMutationClausePlan.RegionKind
                        .UNCLASSIFIED_TARGET_CLAUSE,
                12,
                request.length(),
                "right");
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        request,
                        List.of(excluded),
                        List.of(),
                        List.of(left, unrelated)));
    }

    @Test
    void exactEntryCapsPassAndOneOverFailsBeforeDeepValidation() {
        int pairedRows = SequentialMutationClausePlan.MAX_EVIDENCE_ROWS / 2;
        PlanFixture maximum = planFixture(pairedRows);
        SequentialMutationClausePlan plan = assertDoesNotThrow(
                () -> new SequentialMutationClausePlan(
                        maximum.request(),
                        List.of(),
                        maximum.decisions(),
                        maximum.regions()));
        assertEquals(
                pairedRows,
                plan.occurrenceDecisions().size());
        assertEquals(
                pairedRows,
                plan.ownedRegions().size());

        ArrayList<SequentialMutationClausePlan.ClauseDecision> tooManyDecisions =
                new ArrayList<>(maximum.decisions());
        tooManyDecisions.add(maximum.decisions().getFirst());
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        maximum.request(),
                        List.of(),
                        tooManyDecisions,
                        maximum.regions()));

        ArrayList<SequentialMutationClausePlan.OwnedRegion> tooManyRegions =
                new ArrayList<>(maximum.regions());
        tooManyRegions.add(maximum.regions().getFirst());
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        maximum.request(),
                        List.of(),
                        maximum.decisions(),
                        tooManyRegions));
    }

    @Test
    void exactExcludedSpanCapPassesAndOneOverFails() {
        int maximum = SequentialMutationClausePlan.MAX_EXCLUDED_SPANS;
        String request = "x ".repeat(maximum);
        ArrayList<SequentialMutationClausePlan.SourceSpan> spans =
                new ArrayList<>(maximum + 1);
        for (int index = 0; index < maximum; index++) {
            spans.add(new SequentialMutationClausePlan.SourceSpan(
                    index * 2,
                    index * 2 + 1));
        }
        SequentialMutationClausePlan plan = assertDoesNotThrow(
                () -> new SequentialMutationClausePlan(
                        request, spans, List.of(), List.of()));
        assertEquals(maximum, plan.excludedSpans().size());

        spans.add(new SequentialMutationClausePlan.SourceSpan(
                maximum * 2,
                maximum * 2 + 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        request + "x", spans, List.of(), List.of()));
    }

    @Test
    void retainedEvidenceCharacterCapIsExact() {
        int maximum = Math.toIntExact(
                SequentialMutationClausePlan.MAX_RETAINED_EVIDENCE_CHARACTERS);
        String exact = "x".repeat(maximum);
        var exactRegion = new SequentialMutationClausePlan.OwnedRegion(
                SequentialMutationClausePlan.RegionKind
                        .UNCLASSIFIED_TARGET_CLAUSE,
                0,
                exact.length(),
                exact);
        assertDoesNotThrow(() -> new SequentialMutationClausePlan(
                exact, List.of(), List.of(), List.of(exactRegion)));

        String over = exact + "x";
        var overRegion = new SequentialMutationClausePlan.OwnedRegion(
                SequentialMutationClausePlan.RegionKind
                        .UNCLASSIFIED_TARGET_CLAUSE,
                0,
                over.length(),
                over);
        assertThrows(IllegalArgumentException.class,
                () -> new SequentialMutationClausePlan(
                        over, List.of(), List.of(), List.of(overRegion)));
    }

    private static SequentialMutationClausePlan.ClauseDecision decision(
            String path,
            String sourceText,
            String action,
            int actionStart,
            int actionEnd,
            int targetStart,
            int targetEnd
    ) {
        return new SequentialMutationClausePlan.ClauseDecision(
                path,
                SequentialMutationClausePlan.Authority.REQUIRED,
                action,
                SequentialMutationClausePlan.ActionFamily.CONTENT_MUTATION,
                actionStart,
                actionEnd,
                targetStart,
                0,
                targetEnd,
                sourceText.length(),
                sourceText);
    }

    private static PlanFixture planFixture(int count) {
        StringBuilder request = new StringBuilder(count * 18);
        ArrayList<SequentialMutationClausePlan.ClauseDecision> decisions =
                new ArrayList<>(count);
        ArrayList<SequentialMutationClausePlan.OwnedRegion> regions =
                new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (index > 0) request.append(' ');
            int clauseStart = request.length();
            String path = String.format(Locale.ROOT, "f%04d.txt", index);
            String clause = "Update " + path + ".";
            request.append(clause);
            int actionStart = clauseStart;
            int actionEnd = actionStart + "Update".length();
            int targetStart = actionEnd + 1;
            int targetEnd = targetStart + path.length();
            int clauseEnd = clauseStart + clause.length();
            decisions.add(new SequentialMutationClausePlan.ClauseDecision(
                    path,
                    SequentialMutationClausePlan.Authority.REQUIRED,
                    "Update",
                    SequentialMutationClausePlan.ActionFamily.CONTENT_MUTATION,
                    actionStart,
                    actionEnd,
                    targetStart,
                    clauseStart,
                    targetEnd,
                    clauseEnd,
                    clause));
            regions.add(new SequentialMutationClausePlan.OwnedRegion(
                    SequentialMutationClausePlan.RegionKind.CONTENT_MUTATION,
                    clauseStart,
                    clauseEnd,
                    clause));
        }
        return new PlanFixture(request.toString(), decisions, regions);
    }

    private record PlanFixture(
            String request,
            List<SequentialMutationClausePlan.ClauseDecision> decisions,
            List<SequentialMutationClausePlan.OwnedRegion> regions
    ) {}
}
