package dev.talos.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumSet;
import java.util.stream.Stream;

import static dev.talos.core.util.Sanitize.ExecutableJsonProjectionResult.*;

import static org.junit.jupiter.api.Assertions.*;

class SanitizeExactProposalProjectionTest {
    private static final String FIRST = "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"first.txt\"}}";
    private static final String SECOND = "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"second.txt\"}}";
    private static final String PROSE = "<button>Presentation</button>\n";

    @Test
    void sanitizedProsePreservesBothOrdersAndLiteralArgumentBytes() {
        String edit = "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"style.css\","
                + "\"old_string\":\"<button>{ old }</button>\",\"new_string\":\"<button>{ new }</button>\"}}";
        String source = FIRST + "\n" + PROSE + fence(edit);
        String projection = Sanitize.sanitizeForOutputPreservingToolCalls(source);
        assertNotEquals(source, projection);
        assertTrue(Sanitize.hasExactExecutableJsonProjection(source, projection));
        assertTrue(projection.contains(edit));
    }

    @Test
    void suppressedBareRootBeforeAFenceDoesNotAuthorizeASubset() {
        String source = PROSE + FIRST + "\n" + fence(SECOND);
        assertFalse(Sanitize.hasExactExecutableJsonProjection(source,
                Sanitize.sanitizeForOutputPreservingToolCalls(source)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<think>ordinary reasoning</think>\n", "\u001b[31mPresentation\u001b[0m\n", "<script>outside()</script>\n"})
    void proseSanitationDoesNotGrantAuthorityOrChangeJson(String prefix) {
        String source = prefix + fence(FIRST);
        assertTrue(Sanitize.hasExactExecutableJsonProjection(source,
                Sanitize.sanitizeForOutputPreservingToolCalls(source)));
    }

    @Test
    void changedArgumentEscapeIsRejectedEvenWithEquivalentDecodedValue() {
        assertFalse(Sanitize.hasExactExecutableJsonProjection(PROSE + fence(FIRST),
                fence(FIRST.replace("first.txt", "\\u0066irst.txt"))));
    }

    @Test
    void changedSourceOrderIsRejectedWithoutCollapsingToASet() {
        assertFalse(Sanitize.hasExactExecutableJsonProjection(PROSE + fence(FIRST) + "\n" + fence(SECOND),
                fence(SECOND) + "\n" + fence(FIRST)));
    }

    @Test
    void changedParserPriorityOrEnvelopeKindIsRejected() {
        assertFalse(Sanitize.hasExactExecutableJsonProjection(PROSE + FIRST + "\n" + fence(SECOND),
                fence(FIRST) + "\n" + SECOND));
    }

    @Test
    void missingOrAdditionalRootsAreRejected() {
        assertFalse(Sanitize.hasExactExecutableJsonProjection(PROSE + fence(FIRST) + "\n" + fence(SECOND), fence(FIRST)));
        assertFalse(Sanitize.hasExactExecutableJsonProjection(PROSE + fence(FIRST), fence(FIRST) + "\n" + fence(SECOND)));
    }

    @Test
    void duplicateCandidatesAreNotSilentlyDeduplicatedIntoProof() {
        String source = PROSE + fence(FIRST) + "\n" + fence(FIRST);
        assertFalse(Sanitize.hasExactExecutableJsonProjection(source,
                Sanitize.sanitizeForOutputPreservingToolCalls(source)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<think>", "\\u003cthink\\u003e"})
    void hiddenProposalCannotBePromotedOrDiscardedWithinTheInventory(String opener) {
        String closer = opener.equals("<think>") ? "</think>" : "\\u003c/think\\u003e";
        String source = opener + "\n" + fence(FIRST) + "\n" + closer + "\n" + fence(SECOND);
        assertFalse(Sanitize.hasExactExecutableJsonProjection(source,
                Sanitize.sanitizeForOutputPreservingToolCalls(source)));
        assertFalse(Sanitize.hasExactExecutableJsonProjection(source, fence(FIRST) + "\n" + fence(SECOND)));
    }

    @Test
    void incompleteSecondProposalCannotAuthorizeTheCompleteFirst() {
        String source = fence(FIRST) + "\n{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"unfinished";
        assertFalse(Sanitize.hasExactExecutableJsonProjection(source, fence(FIRST)));
    }

    @Test
    void discardedFenceTailAndXmlMixtureRemainIneligible() {
        String source = "```json\n" + FIRST + "\n" + SECOND + "\n```";
        assertFalse(Sanitize.hasExactExecutableJsonProjection(source, fence(FIRST)));
        source = PROSE + fence(FIRST) + "\n<tool_call>" + SECOND + "</tool_call>";
        assertFalse(Sanitize.hasExactExecutableJsonProjection(source, fence(FIRST)));
    }

    @Test
    void sourceWithoutExecutableJsonCannotAcquireAProposalFromProjection() {
        assertFalse(Sanitize.hasExactExecutableJsonProjection("ordinary prose", fence(FIRST)));
        assertFalse(Sanitize.hasExactExecutableJsonProjection(null, fence(FIRST)));
        assertFalse(Sanitize.hasExactExecutableJsonProjection(fence(FIRST), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("projectionDiagnostics")
    void finiteDiagnosticPreservesBooleanDecision(
            String description, String source, String projected,
            Sanitize.ExecutableJsonProjectionResult expected) {
        assertEquals(expected, Sanitize.executableJsonProjectionResult(source, projected), description);
        assertEquals(expected == EXACT, Sanitize.hasExactExecutableJsonProjection(source, projected), description);
    }

    @Test
    void diagnosticMatrixCoversEveryFiniteResult() {
        var covered = EnumSet.noneOf(Sanitize.ExecutableJsonProjectionResult.class);
        projectionDiagnostics().forEach(row -> covered.add(
                (Sanitize.ExecutableJsonProjectionResult) row.get()[3]));
        assertEquals(EnumSet.allOf(Sanitize.ExecutableJsonProjectionResult.class), covered);
    }

    @Test
    void firstSuppressionPredicateIsReportedWithoutChangingRejection() {
        String first = fence(FIRST);
        String second = fence(SECOND);
        assertSuppression(first + "\n" + first, first,
                Sanitize.ExecutableJsonSuppressionStage.SOURCE_SUPPRESSION);
        assertSuppression("<think>\n" + first + "\n</think>\n" + second, second,
                Sanitize.ExecutableJsonSuppressionStage.VISIBLE_SOURCE_SUPPRESSION);
        assertSuppression(first, first + "\n" + first,
                Sanitize.ExecutableJsonSuppressionStage.PROJECTED_SUPPRESSION);
        assertSuppression(first + "\n" + first, first + "\n" + first,
                Sanitize.ExecutableJsonSuppressionStage.SOURCE_SUPPRESSION);
    }

    @Test
    void twoFencesWithSubordinateCallsCannotAuthorizeTheSurvivingSubset() {
        String editIndex = "{\"name\":\"talos.edit_file\",\"parameters\":{\"path\":\"index.html\","
                + "\"old_string\":\"old\",\"new_string\":\"new\"}}";
        String editStyle = editIndex.replace("index.html", "style.css");
        String readIndex = FIRST.replace("first.txt", "index.html");
        String readStyle = FIRST.replace("first.txt", "style.css");
        String source = fence(editIndex + "\n" + editStyle) + "\n" + fence(readIndex + "\n" + readStyle);
        String projected = Sanitize.sanitizeForOutputPreservingToolCalls(source);
        assertTrue(projected.contains(editIndex));
        assertTrue(projected.contains(readIndex));
        assertFalse(projected.contains(editStyle));
        assertFalse(projected.contains(readStyle));
        assertSuppression(source, projected, Sanitize.ExecutableJsonSuppressionStage.SOURCE_SUPPRESSION);
        assertEquals(java.util.Set.of(dev.talos.core.tool.ToolProtocolText.SelectedFenceTailShape
                        .COMPLETE_RECOGNIZED_OBJECT_SEQUENCE),
                Sanitize.inspectExecutableJsonProjection(source, projected).fenceTailShapes());
    }

    @Test
    void nonsuppressionResultsDoNotAcquireSuppressionMetadata() {
        projectionDiagnostics().forEach(row -> {
            var values = row.get();
            var inspection = Sanitize.inspectExecutableJsonProjection((String) values[1], (String) values[2]);
            assertEquals(values[3], inspection.result());
            if (inspection.result() != SUPPRESSED_OR_UNSELECTED_PROPOSALS) {
                assertEquals(Sanitize.ExecutableJsonSuppressionStage.NONE, inspection.suppressionStage());
                assertTrue(inspection.fenceTailShapes().isEmpty());
            } else assertNotEquals(Sanitize.ExecutableJsonSuppressionStage.NONE, inspection.suppressionStage());
        });
        assertThrows(IllegalArgumentException.class, () -> new Sanitize.ExecutableJsonProjectionInspection(
                EXACT, Sanitize.ExecutableJsonSuppressionStage.SOURCE_SUPPRESSION));
        assertThrows(IllegalArgumentException.class, () -> new Sanitize.ExecutableJsonProjectionInspection(
                SUPPRESSED_OR_UNSELECTED_PROPOSALS, Sanitize.ExecutableJsonSuppressionStage.NONE));
    }

    @Test
    void finiteSuppressionKindsSeparateCounterexamplesWithoutRetainingPayload() {
        var kind = Sanitize.ExecutableJsonSuppressionKind.class;
        String first = fence(FIRST);
        String second = fence(SECOND);
        assertKinds(first + "\n" + first, first,
                java.util.Set.of(Sanitize.ExecutableJsonSuppressionKind.DUPLICATE_RECOGNIZED_ROOT));
        assertKinds(PROSE + FIRST + "\n" + second, second,
                java.util.Set.of(Sanitize.ExecutableJsonSuppressionKind.UNSELECTED_RECOGNIZED_ROOT));
        assertKinds(fence(FIRST + "\n" + SECOND), first,
                java.util.Set.of(Sanitize.ExecutableJsonSuppressionKind.SELECTED_FENCE_TAIL_POTENTIAL));
        assertKinds(fence("{\"name\":\"talos.read_file\", BROKEN}") + "\n" + first, first,
                java.util.Set.of(Sanitize.ExecutableJsonSuppressionKind.OTHER_POTENTIAL_ENVELOPE));
        assertKinds(fence(FIRST + "\n" + SECOND) + "\n" + first, first,
                java.util.Set.of(Sanitize.ExecutableJsonSuppressionKind.SELECTED_FENCE_TAIL_POTENTIAL,
                        Sanitize.ExecutableJsonSuppressionKind.DUPLICATE_RECOGNIZED_ROOT));
        assertEquals(4, kind.getEnumConstants().length);
    }

    @Test
    void hiddenBareProposalChangesSelectionWithoutInventingASuppressionEnvelope() {
        String source = "<think>\n" + FIRST + "\n</think>\n" + SECOND;
        var inspection = Sanitize.inspectExecutableJsonProjection(source, SECOND);
        assertEquals(SUPPRESSED_OR_UNSELECTED_PROPOSALS, inspection.result());
        assertEquals(Sanitize.ExecutableJsonSuppressionStage.HIDDEN_SELECTION_CHANGE,
                inspection.suppressionStage());
        assertTrue(inspection.suppressionKinds().isEmpty());
        assertTrue(inspection.fenceTailShapes().isEmpty());
    }

    @Test
    void wholeTailShapeCannotHideOtherValuesOrTrailingJunk() {
        var complete = dev.talos.core.tool.ToolProtocolText.SelectedFenceTailShape.COMPLETE_RECOGNIZED_OBJECT_SEQUENCE;
        var other = dev.talos.core.tool.ToolProtocolText.SelectedFenceTailShape.COMPLETE_OTHER_JSON_SEQUENCE;
        var malformed = dev.talos.core.tool.ToolProtocolText.SelectedFenceTailShape.MALFORMED_INCOMPLETE_OR_OTHER;
        String source = fence(FIRST + "\n" + SECOND + "\n{}");
        var inspection = Sanitize.inspectExecutableJsonProjection(source, fence(FIRST));
        assertEquals(SUPPRESSED_OR_UNSELECTED_PROPOSALS, inspection.result());
        assertEquals(java.util.Set.of(other), inspection.fenceTailShapes());
        source = fence(FIRST + "\n" + SECOND + " trailing prose");
        inspection = Sanitize.inspectExecutableJsonProjection(source, fence(FIRST));
        assertEquals(SUPPRESSED_OR_UNSELECTED_PROPOSALS, inspection.result());
        assertEquals(java.util.Set.of(malformed), inspection.fenceTailShapes());
        source = fence(FIRST + "\n" + SECOND) + "\n" + fence(SECOND + "\n" + FIRST + " junk");
        inspection = Sanitize.inspectExecutableJsonProjection(source, fence(FIRST) + "\n" + fence(SECOND));
        assertEquals(java.util.Set.of(complete, malformed), inspection.fenceTailShapes());
        assertFalse(Sanitize.hasExactExecutableJsonProjection(source, fence(FIRST) + "\n" + fence(SECOND)));
        assertFalse(inspection.toString().contains("first.txt"));
        assertFalse(inspection.toString().contains("trailing prose"));
        var shapes = inspection.fenceTailShapes();
        assertThrows(UnsupportedOperationException.class, shapes::clear);
    }

    @Test
    void tailShapesDescribeOnlyTheFirstFailingSelectionAndKeepLegacyConstructors() {
        var stage = Sanitize.ExecutableJsonSuppressionStage.SOURCE_SUPPRESSION;
        var kind = Sanitize.ExecutableJsonSuppressionKind.SELECTED_FENCE_TAIL_POTENTIAL;
        assertTrue(new Sanitize.ExecutableJsonProjectionInspection(SUPPRESSED_OR_UNSELECTED_PROPOSALS, stage)
                .fenceTailShapes().isEmpty());
        assertTrue(new Sanitize.ExecutableJsonProjectionInspection(SUPPRESSED_OR_UNSELECTED_PROPOSALS, stage,
                java.util.Set.of(kind)).fenceTailShapes().isEmpty());
        var shapes = java.util.Set.of(dev.talos.core.tool.ToolProtocolText.SelectedFenceTailShape
                .COMPLETE_RECOGNIZED_OBJECT_SEQUENCE);
        assertThrows(IllegalArgumentException.class, () -> new Sanitize.ExecutableJsonProjectionInspection(
                SUPPRESSED_OR_UNSELECTED_PROPOSALS, stage, java.util.Set.of(), shapes));
        var earlier = Sanitize.inspectExecutableJsonProjection(fence(FIRST) + "\n" + fence(FIRST),
                fence(FIRST + "\n" + SECOND));
        assertEquals(stage, earlier.suppressionStage());
        assertTrue(earlier.fenceTailShapes().isEmpty(), "Later projected tail must not contaminate first source failure");
        var projected = Sanitize.inspectExecutableJsonProjection(fence(FIRST), fence(FIRST + "\n" + SECOND));
        assertEquals(Sanitize.ExecutableJsonSuppressionStage.PROJECTED_SUPPRESSION, projected.suppressionStage());
        assertEquals(shapes, projected.fenceTailShapes());
        assertTrue(Sanitize.inspectExecutableJsonProjection(fence(FIRST), fence(FIRST)).fenceTailShapes().isEmpty());
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void manySuppressedObjectsInOneFenceAreDiagnosedAsOneTail() {
        String source = fence(FIRST + ("\n" + SECOND).repeat(800));
        var inspection = Sanitize.inspectExecutableJsonProjection(source, fence(FIRST));
        assertEquals(SUPPRESSED_OR_UNSELECTED_PROPOSALS, inspection.result());
        assertEquals(java.util.Set.of(dev.talos.core.tool.ToolProtocolText.SelectedFenceTailShape
                .COMPLETE_RECOGNIZED_OBJECT_SEQUENCE), inspection.fenceTailShapes());
        assertFalse(Sanitize.hasExactExecutableJsonProjection(source, fence(FIRST)));
    }

    @Test
    void tooManyAffectedFencesRetainOnlyTheFiniteLimitDiagnosis() {
        var source = new StringBuilder();
        var projected = new StringBuilder();
        for (int index = 0; index <= dev.talos.core.tool.ToolProtocolText.MAX_DIAGNOSTIC_FENCE_TAILS; index++) {
            String root = FIRST.replace("first.txt", "first-" + index + ".txt");
            source.append(fence(root + "\n" + SECOND)).append('\n');
            projected.append(fence(root)).append('\n');
        }
        var inspection = Sanitize.inspectExecutableJsonProjection(source.toString(), projected.toString());
        assertEquals(SUPPRESSED_OR_UNSELECTED_PROPOSALS, inspection.result());
        assertEquals(Sanitize.ExecutableJsonSuppressionStage.SOURCE_SUPPRESSION, inspection.suppressionStage());
        assertEquals(java.util.Set.of(dev.talos.core.tool.ToolProtocolText.SelectedFenceTailShape
                .INSPECTION_LIMIT_REACHED), inspection.fenceTailShapes());
        assertFalse(Sanitize.hasExactExecutableJsonProjection(source.toString(), projected.toString()));
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void largeDuplicateInventoryKeepsDiagnosticsFinite() {
        String first = fence(FIRST);
        String source = (first + "\n").repeat(10_000);
        var inspection = Sanitize.inspectExecutableJsonProjection(source, first);
        assertEquals(SUPPRESSED_OR_UNSELECTED_PROPOSALS, inspection.result());
        assertEquals(java.util.Set.of(Sanitize.ExecutableJsonSuppressionKind.DUPLICATE_RECOGNIZED_ROOT),
                inspection.suppressionKinds());
        assertTrue(inspection.toString().length() < 250);
    }

    private static void assertKinds(String source, String projected,
                                     java.util.Set<Sanitize.ExecutableJsonSuppressionKind> kinds) {
        var inspection = Sanitize.inspectExecutableJsonProjection(source, projected);
        assertEquals(SUPPRESSED_OR_UNSELECTED_PROPOSALS, inspection.result());
        assertEquals(kinds, inspection.suppressionKinds());
        assertFalse(inspection.toString().contains("first.txt"));
        assertFalse(inspection.toString().contains("second.txt"));
        assertFalse(Sanitize.hasExactExecutableJsonProjection(source, projected));
        assertThrows(UnsupportedOperationException.class, () -> inspection.suppressionKinds().clear());
    }

    private static void assertSuppression(String source, String projected,
                                          Sanitize.ExecutableJsonSuppressionStage stage) {
        var inspection = Sanitize.inspectExecutableJsonProjection(source, projected);
        assertEquals(SUPPRESSED_OR_UNSELECTED_PROPOSALS, inspection.result());
        assertEquals(stage, inspection.suppressionStage());
        assertFalse(Sanitize.hasExactExecutableJsonProjection(source, projected));
    }

    private static Stream<Arguments> projectionDiagnostics() {
        String first = fence(FIRST);
        String second = fence(SECOND);
        String incomplete = "\n{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"unfinished";
        String xml = "<tool_call>" + SECOND + "</tool_call>";
        String tail = "```json\n" + FIRST + "\nordinary trailing prose\n```";
        String hidden = "<think>HIDDEN_SOURCE_CANARY\n" + first + "\n</think>\n" + second;
        String duplicate = PROSE + first + "\n" + first;
        return Stream.of(
                Arguments.of("exact sanitized prose", PROSE + first, first, EXACT),
                Arguments.of("ordinary hidden prose", "<think>reasoning</think>\n" + first, first, EXACT),
                Arguments.of("both orders with leading bare root", FIRST + "\n" + PROSE + second,
                        FIRST + "\n" + second, EXACT),
                Arguments.of("null source before unfinished output", null, incomplete, MISSING_INPUT),
                Arguments.of("null output", first, null, MISSING_INPUT),
                Arguments.of("unfinished source before XML", first + xml + incomplete, first, UNSETTLED_PROTOCOL),
                Arguments.of("unfinished output", first, first + incomplete, UNSETTLED_PROTOCOL),
                Arguments.of("source XML before empty selection", xml, first, XML_PROTOCOL_PRESENT),
                Arguments.of("output XML", first, first + xml, XML_PROTOCOL_PRESENT),
                Arguments.of("source has no proposal", "ordinary prose", first, EMPTY_VISIBLE_SELECTION),
                Arguments.of("hidden only proposal", "<think>\n" + first + "\n</think>", first,
                        EMPTY_VISIBLE_SELECTION),
                Arguments.of("source duplicate", duplicate, first, SUPPRESSED_OR_UNSELECTED_PROPOSALS),
                Arguments.of("output duplicate", first, first + "\n" + first, SUPPRESSED_OR_UNSELECTED_PROPOSALS),
                Arguments.of("suppressed bare source", PROSE + FIRST + "\n" + second, second,
                        SUPPRESSED_OR_UNSELECTED_PROPOSALS),
                Arguments.of("hidden proposal discarded", hidden, second, SUPPRESSED_OR_UNSELECTED_PROPOSALS),
                Arguments.of("hidden proposal promoted", hidden, first + "\n" + second,
                        SUPPRESSED_OR_UNSELECTED_PROPOSALS),
                Arguments.of("second fence root suppressed", "```json\n" + FIRST + "\n" + SECOND + "\n```",
                        first, SUPPRESSED_OR_UNSELECTED_PROPOSALS),
                Arguments.of("suppression before fence tail", tail + "\n" + first, first,
                        SUPPRESSED_OR_UNSELECTED_PROPOSALS),
                Arguments.of("ordinary fence tail before changed root", tail, second, NONBLANK_FENCE_TAIL),
                Arguments.of("exact argument escape changed", PROSE + first,
                        fence(FIRST.replace("first.txt", "\\u0066irst.txt")), EXACT_ROOTS_MISMATCH),
                Arguments.of("source order changed", PROSE + first + "\n" + second,
                        second + "\n" + first, EXACT_ROOTS_MISMATCH),
                Arguments.of("envelope kind changed", first, FIRST, EXACT_ROOTS_MISMATCH),
                Arguments.of("proposal removed", first + "\n" + second, first, EXACT_ROOTS_MISMATCH),
                Arguments.of("proposal added", first, first + "\n" + second, EXACT_ROOTS_MISMATCH));
    }

    private static String fence(String root) { return "```json\n" + root + "\n```"; }
}
