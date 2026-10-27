package dev.talos.runtime.verification;

import dev.talos.runtime.trace.LocalTurnTraceCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static dev.talos.runtime.verification.ReadOnlyAnswerComponents.*;
import static dev.talos.runtime.verification.ReadOnlyAnswerInterpretation.Decision;
import static org.junit.jupiter.api.Assertions.*;

class NativeObservationPresentationTest {
    private static final String FACT = "The checked source fact was retained.";
    private static final String GROUP = "a".repeat(64);
    private static final List<Reference> REFERENCES = List.of(new Reference("observation-1", "b".repeat(64), 0, 12));

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void sharedEvidenceIsRenderedOnceWithoutRemovingAnyDecisionOrComponent() {
        var components = shared(3);
        String answer = components.render();

        assertEquals(6, components.components().size());
        assertEquals(3, components.components().stream().filter(value -> !value.met()).count());
        assertEquals(3, components.components().stream().filter(value -> value.kind().equals("OBSERVATION")).count());
        assertEquals(1, occurrences(answer, FACT));
        assertTrue(answer.contains("regions `region-1`, `region-2`, `region-3`"));
        assertFalse(components.complete());
        assertFalse(components.deliveredIntact(answer));
    }

    @Test
    void differentRuntimeGroupsAreNotMergedByEqualTextOrRole() {
        var components = limited(List.of(observation("region-1", GROUP, "DIAGNOSIS", FACT, REFERENCES),
                observation("region-2", "c".repeat(64), "DIAGNOSIS", FACT, REFERENCES)));

        assertEquals(2, occurrences(components.render(), FACT));
    }

    @Test
    void differentRelationTypesAreNotMergedEvenWithAnInconsistentGroupIdentity() {
        var components = limited(List.of(observation("region-1", GROUP, "DIAGNOSIS", FACT, REFERENCES),
                observation("region-2", GROUP, "SELECTOR", FACT, REFERENCES)));

        assertEquals(2, occurrences(components.render(), FACT));
    }

    @Test
    void differentObservedReferencesAreNotMergedEvenWithAnInconsistentGroupIdentity() {
        var changed = List.of(new Reference("observation-2", "b".repeat(64), 0, 12));
        var components = limited(List.of(observation("region-1", GROUP, "DIAGNOSIS", FACT, REFERENCES),
                observation("region-2", GROUP, "DIAGNOSIS", FACT, changed)));

        assertEquals(2, occurrences(components.render(), FACT));
    }

    @Test
    void conflictingFactTextIsNotSilentlyDiscarded() {
        var components = limited(List.of(observation("region-1", GROUP, "DIAGNOSIS", FACT, REFERENCES),
                observation("region-2", GROUP, "DIAGNOSIS", "Another checked fact was retained.", REFERENCES)));

        assertTrue(components.render().contains(FACT));
        assertTrue(components.render().contains("Another checked fact was retained."));
    }

    @Test
    void requiredResultsAndUnsupportedObservationsAreNeverGrouped() {
        var values = new ArrayList<Component>();
        for (String kind : List.of("CALCULATION", "COMPARISON", "UNKNOWN", "OBSERVATION")) {
            values.add(new Component(kind + "-1", kind, Support.UNSUPPORTED, "UNMET", FACT, List.of(), ""));
            values.add(new Component(kind + "-2", kind, Support.UNSUPPORTED, "UNMET", FACT, List.of(), ""));
        }
        assertEquals(8, occurrences(limited(values).render(), FACT));
        var presentation = new ObservationPresentation(GROUP, "DIAGNOSIS", "region-1", FACT);
        assertThrows(IllegalArgumentException.class, () -> new Component("x", "CALCULATION", Support.ATTRIBUTED_INTERPRETATION,
                "CHECKED", FACT, REFERENCES, "", presentation, null));
        assertThrows(IllegalArgumentException.class, () -> new Component("x", "OBSERVATION", Support.CONTRADICTED,
                "CHECKED", FACT, REFERENCES, "", presentation, null));
    }

    @Test
    void truncationRecomputesDisplayedRegionMembershipFromRetainedComponents() {
        var rows = new ArrayList<>(shared(3).components());
        rows.set(5, observation("region-3-" + "x".repeat(60), GROUP, "DIAGNOSIS", FACT, REFERENCES));
        rows.add(new Component("extra", "UNKNOWN", Support.UNSUPPORTED, "UNMET", "Unresolved extra. " + "x".repeat(500), List.of(), ""));
        var components = limited(rows);
        String answer = components.render();
        String bounded = null;
        for (int cap = 1; cap < answer.length(); cap++) {
            String candidate = components.present(answer, UnaryOperator.identity(), cap);
            if (candidate.contains("regions `region-1`, `region-2`.")) {
                bounded = candidate;
                assertTrue(candidate.length() <= cap);
                break;
            }
        }
        assertNotNull(bounded);
        assertFalse(bounded.contains("regions `region-1`, `region-2`, `region-3-"));
        assertTrue(bounded.contains("Unresolved region-3"));
        assertTrue(bounded.contains("Required components were omitted by the output limit."));
        assertEquals(7, components.components().size());
    }

    @Test
    void privacyShapingCannotRestoreGroupedSourceText() {
        var components = shared(3);
        var calls = new AtomicInteger();
        String answer = components.present(components.render(), text -> {
            calls.incrementAndGet();
            return text.replace(FACT, "[withheld]");
        }, 10_000);

        assertEquals(1, calls.get());
        assertFalse(answer.contains(FACT));
        assertTrue(answer.contains("[withheld]"));
        assertTrue(answer.contains("changed by presentation policy"));
    }

    @Test
    void everyDecisionAndObservationBindingRemainsInThePresentedTrace() {
        LocalTurnTraceCapture.begin("trc-observation-presentation", "session", 1, "2026-09-14T00:00:00Z",
                "workspace-hash", "unified", "scripted", "model", "read retained sources");
        var rows = new ArrayList<>(shared(3).components());
        rows.set(1, unknown("region-2", Decision.UNSUPPORTED));
        rows.set(2, unknown("region-3", Decision.MISSING));
        var components = limited(rows);
        LocalTurnTraceCapture.recordReadOnlyAnswerComponentsPresented(components, components.render());
        var event = LocalTurnTraceCapture.complete().events().stream()
                .filter(value -> value.type().equals("READ_ONLY_ANSWER_COMPONENTS_PRESENTED")).findFirst().orElseThrow();
        var captured = (List<?>) event.data().get("components");

        assertEquals(6, captured.size());
        assertEquals(false, event.data().get("requestCovered"));
        assertEquals(false, event.data().get("requirementsComplete"));
        for (int index = 0; index < 3; index++) {
            var row = (Map<?, ?>) captured.get(index);
            assertEquals(Decision.values()[index].name(), row.get("interpretationDecision"));
            assertEquals("UNSUPPORTED", row.get("status"));
        }
        for (int index = 3; index < 6; index++) {
            var row = (Map<?, ?>) captured.get(index);
            var metadata = (Map<?, ?>) row.get("observationPresentation");
            assertEquals(GROUP, metadata.get("evidenceGroupId"));
            assertEquals("region-" + (index - 2), metadata.get("regionId"));
            assertFalse(metadata.containsKey("sourceText"));
            assertFalse(((List<?>) row.get("references")).isEmpty());
        }
    }

    private static ReadOnlyAnswerComponents shared(int count) {
        var rows = new ArrayList<Component>();
        for (int index = 1; index <= count; index++) rows.add(unknown("region-" + index, Decision.SELECTED));
        for (int index = 1; index <= count; index++) rows.add(observation("region-" + index, GROUP, "DIAGNOSIS", FACT, REFERENCES));
        return limited(rows);
    }

    private static Component unknown(String region, Decision decision) {
        return new Component(region, "UNKNOWN", Support.UNSUPPORTED, "UNRESOLVED", "Unresolved " + region + ".",
                List.of(), ReadOnlyWebDiagnosticResult.textHash(region + decision), null, decision);
    }

    private static Component observation(String region, String group, String kind, String text, List<Reference> references) {
        return new Component(region + ":relation", "OBSERVATION", Support.ATTRIBUTED_INTERPRETATION, "CHECKED",
                "Observation for " + region + ". " + text, references, ReadOnlyWebDiagnosticResult.textHash(region + group),
                new ObservationPresentation(group, kind, region, text), null);
    }

    private static ReadOnlyAnswerComponents limited(List<Component> rows) {
        return new ReadOnlyAnswerComponents(rows, false, CoverageOrigin.NATIVE_LEDGER_WITH_INTERPRETATIONS);
    }

    private static int occurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }
}
