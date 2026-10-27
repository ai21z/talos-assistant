package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequentialMutationClauseParserScalingTest {

    @Test
    void indexedSequentialParsingScalesNearLinearlyByDeterministicWork() {
        Fixture small = fixture(64);
        Fixture large = fixture(128);
        var smallMeasured = SequentialMutationClauseParser.parseMeasured(
                small.request(),
                small.targets());
        var largeMeasured = SequentialMutationClauseParser.parseMeasured(
                large.request(),
                large.targets());

        assertEquals(63,
                smallMeasured.plan().occurrenceDecisions().size());
        assertEquals(127,
                largeMeasured.plan().occurrenceDecisions().size());
        assertTrue(
                largeMeasured.work().totalVisits()
                        <= smallMeasured.work().totalVisits() * 5 / 2 + 32,
                () -> "deterministic parser work grew too quickly: small="
                        + smallMeasured.work()
                        + ", large="
                        + largeMeasured.work());
        assertTrue(
                largeMeasured.work().directTargetVisits()
                        <= largeMeasured.work().actions() * 3,
                () -> "action binding rescanned target occurrences: "
                        + largeMeasured.work());
        assertTrue(
                largeMeasured.work().sentenceEndQueries()
                        <= largeMeasured.work().actions() * 2,
                () -> "sentence boundary lookup was not indexed: "
                        + largeMeasured.work());
        assertTrue(
                largeMeasured.work().regionDedupLookups()
                        <= largeMeasured.work().actions() * 2,
                () -> "region identity work was not bounded: "
                        + largeMeasured.work());
    }

    @Test
    void oneAdvisoryClauseIsClassifiedAndMaterializedOnce() {
        Fixture small = advisoryFixture(64);
        Fixture large = advisoryFixture(128);
        var smallMeasured = SequentialMutationClauseParser.parseMeasured(
                small.request(), small.targets());
        var largeMeasured = SequentialMutationClauseParser.parseMeasured(
                large.request(), large.targets());

        assertEquals(1, smallMeasured.plan().ownedRegions().size());
        assertEquals(1, largeMeasured.plan().ownedRegions().size());
        assertTrue(
                largeMeasured.work().clauseClassificationCharacters()
                        <= large.request().length(),
                () -> "one advisory span was classified repeatedly: "
                        + largeMeasured.work());
        assertTrue(
                largeMeasured.work().clauseMaterializationCharacters()
                        <= large.request().length(),
                () -> "one advisory span was materialized repeatedly: "
                        + largeMeasured.work());
        assertTrue(
                largeMeasured.work().totalVisits()
                        <= smallMeasured.work().totalVisits() * 3 + 64,
                () -> "advisory parsing grew too quickly: small="
                        + smallMeasured.work() + ", large="
                        + largeMeasured.work());
    }

    @Test
    void nestedActionLookupVisitsEachIndexedActionAtMostOnce() {
        Fixture small = nestedFixture(64);
        Fixture large = nestedFixture(128);
        var smallMeasured = SequentialMutationClauseParser.parseMeasured(
                small.request(), small.targets());
        var largeMeasured = SequentialMutationClauseParser.parseMeasured(
                large.request(), large.targets());

        assertEquals(64, smallMeasured.plan().occurrenceDecisions().size());
        assertEquals(128, largeMeasured.plan().occurrenceDecisions().size());
        assertTrue(
                largeMeasured.work().nestedActionVisits()
                        <= largeMeasured.work().actions(),
                () -> "nested action lookup rescanned prior actions: "
                        + largeMeasured.work());
        assertTrue(
                largeMeasured.work().totalVisits()
                        <= smallMeasured.work().totalVisits() * 3 + 64,
                () -> "nested parsing grew too quickly: small="
                        + smallMeasured.work() + ", large="
                        + largeMeasured.work());
    }

    @Test
    void denseProviderControlsProduceOneBoundedQuarantine() {
        StringBuilder request = new StringBuilder();
        for (int index = 0; index < 1_024; index++) {
            request.append('a').append('\u0000');
        }
        request.append("b.css.");

        var measured = SequentialMutationClauseParser.parseMeasured(
                request.toString(),
                Set.of("b.css"));

        assertEquals(1, measured.plan().ownedRegions().size());
        assertTrue(
                measured.work().clauseMaterializationCharacters()
                        <= request.length(),
                () -> "control quarantine retained overlapping suffixes: "
                        + measured.work());
        assertTrue(
                measured.work().regionDedupLookups() <= 1,
                () -> "control quarantine repeated region work: "
                        + measured.work());
    }

    @Test
    void oneMultiTargetClauseSharesOneExactMaterializedSourceSpan() {
        StringBuilder request = new StringBuilder("Edit anchor.css. Then update ");
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        targets.add("anchor.css");
        for (int index = 0; index < 128; index++) {
            if (index > 0) request.append(", ");
            String target = "shared-" + index + ".css";
            targets.add(target);
            request.append(target);
        }
        request.append('.');

        var measured = SequentialMutationClauseParser.parseMeasured(
                request.toString(),
                Set.copyOf(targets));
        var decisions = measured.plan().occurrenceDecisions();
        assertEquals(128, decisions.size());
        String shared = decisions.getFirst().sourceText();
        for (var decision : decisions) {
            assertSame(shared, decision.sourceText());
        }
        assertEquals(shared.length(),
                measured.work().clauseMaterializationCharacters());
    }

    @Test
    void occurrenceOverflowStopsBeforeExcludedBoundaryIndexConstruction() {
        String request = "Edit index.html. For reference, "
                + "a.txt ".repeat(
                        CoordinatedMutationTailParser
                                .MAX_INDEXED_TARGET_OCCURRENCES);
        var measured = SequentialMutationClauseParser.parseMeasured(
                request,
                Set.of("index.html", "a.txt"),
                java.util.List.of(
                        new SequentialMutationClausePlan.SourceSpan(0, 1)));

        assertTrue(measured.plan().authorityLimitExceeded());
        assertEquals(
                SequentialMutationClausePlan.RegionKind
                        .TARGET_OCCURRENCE_LIMIT_QUARANTINE,
                measured.plan().authorityLimit().kind());
        assertEquals(0, measured.work().boundaryBuildVisits());
        assertEquals(0, measured.work().actions());
        assertEquals(0, measured.work().targetOccurrences());
    }

    private static Fixture fixture(int clauses) {
        StringBuilder request = new StringBuilder();
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (int index = 0; index < clauses; index++) {
            String target = "file-" + index + ".txt";
            targets.add(target);
            if (index > 0) request.append(' ');
            request.append(index == 0 ? "Edit " : "Update ")
                    .append(target)
                    .append('.');
        }
        return new Fixture(request.toString(), Set.copyOf(targets));
    }

    private static Fixture advisoryFixture(int targets) {
        StringBuilder request = new StringBuilder("Edit index.html. For reference, ");
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add("index.html");
        for (int index = 0; index < targets; index++) {
            String target = "advisory-" + index + ".txt";
            candidates.add(target);
            if (index > 0) request.append(", ");
            request.append(target);
        }
        request.append('.');
        return new Fixture(request.toString(), Set.copyOf(candidates));
    }

    private static Fixture nestedFixture(int clauses) {
        StringBuilder request = new StringBuilder("Edit index.html. (");
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        targets.add("index.html");
        for (int index = 0; index < clauses; index++) {
            String target = "nested-" + index + ".txt";
            targets.add(target);
            if (index > 0) request.append(", ");
            request.append("Update ").append(target);
        }
        request.append(").");
        return new Fixture(request.toString(), Set.copyOf(targets));
    }

    private record Fixture(String request, Set<String> targets) {}
}
