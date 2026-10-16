package dev.talos.core.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static dev.talos.core.tool.ToolProtocolText.SelectedFenceTailShape.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolProtocolTextFenceTailShapeTest {
    private static final String READ = "{\"name\":\"talos.read_file\",\"parameters\":{\"path\":\"private-canary.txt\"}}";

    @ParameterizedTest
    @MethodSource("tails")
    void wholeTailMustBeConsumedWithWhitespaceBetweenValues(String tail,
            ToolProtocolText.SelectedFenceTailShape expected) {
        var shapes = inspect(List.of(tail));
        assertEquals(Set.of(expected), shapes);
        assertFalse(shapes.toString().contains("private-canary"));
        assertThrows(UnsupportedOperationException.class, () -> shapes.clear());
    }

    static Stream<Arguments> tails() {
        return Stream.of(
                Arguments.of("\n" + READ, COMPLETE_RECOGNIZED_OBJECT_SEQUENCE),
                Arguments.of(" \t\r\n" + READ + "\n" + READ + " \t", COMPLETE_RECOGNIZED_OBJECT_SEQUENCE),
                Arguments.of("\n{\"tool_call\":{\"name\":\"talos.read_file\"}}", COMPLETE_RECOGNIZED_OBJECT_SEQUENCE),
                // Recognition is deliberately not argument validation.
                Arguments.of("\n{\"name\":\"talos.read_file\"}", COMPLETE_RECOGNIZED_OBJECT_SEQUENCE),
                Arguments.of("\n{\"name\":\"unknown\",\"tool\":\"talos.read_file\"}", COMPLETE_OTHER_JSON_SEQUENCE),
                Arguments.of("\n{\"tool_call\":{\"name\":\"unknown\"},\"name\":\"talos.read_file\"}", COMPLETE_OTHER_JSON_SEQUENCE),
                Arguments.of("\n" + READ + "\n{}", COMPLETE_OTHER_JSON_SEQUENCE),
                Arguments.of("\n[" + READ + "]", COMPLETE_OTHER_JSON_SEQUENCE),
                Arguments.of("\ntrue false null 12 \"text\"", COMPLETE_OTHER_JSON_SEQUENCE),
                Arguments.of("\n\"{\\\"name\\\":\\\"talos.read_file\\\"}\"", COMPLETE_OTHER_JSON_SEQUENCE),
                Arguments.of("\n{\"name\":\"talos.read_file\",\"parameters\":{\"path\":\"brace } and \\\" quote\"}}",
                        COMPLETE_RECOGNIZED_OBJECT_SEQUENCE),
                Arguments.of(READ, MALFORMED_INCOMPLETE_OR_OTHER),
                Arguments.of("\n" + READ + READ, MALFORMED_INCOMPLETE_OR_OTHER),
                Arguments.of("\n\"space \"" + READ, MALFORMED_INCOMPLETE_OR_OTHER),
                Arguments.of("\n" + READ + ",\n" + READ, MALFORMED_INCOMPLETE_OR_OTHER),
                Arguments.of("\n" + READ + "\ntrailing prose", MALFORMED_INCOMPLETE_OR_OTHER),
                Arguments.of("\nprose\n" + READ, MALFORMED_INCOMPLETE_OR_OTHER),
                Arguments.of("\n" + READ + "\n{\"name\":\"talos.read_file\",", MALFORMED_INCOMPLETE_OR_OTHER),
                Arguments.of("\n{\"name\":\"talos.read_file\", BROKEN}", MALFORMED_INCOMPLETE_OR_OTHER),
                Arguments.of("\u2003" + READ, MALFORMED_INCOMPLETE_OR_OTHER),
                Arguments.of("\n", MALFORMED_INCOMPLETE_OR_OTHER),
                Arguments.of("", MALFORMED_INCOMPLETE_OR_OTHER));
    }

    @Test
    void independentFenceShapesRemainDistinct() {
        assertEquals(Set.of(COMPLETE_RECOGNIZED_OBJECT_SEQUENCE, COMPLETE_OTHER_JSON_SEQUENCE,
                MALFORMED_INCOMPLETE_OR_OTHER), inspect(List.of("\n" + READ, "\n{}", "\n" + READ + " junk")));
    }

    @Test
    void aggregateTailBoundsDoNotReturnPartiallyInspectedPositiveShapes() {
        int limit = ToolProtocolText.MAX_DIAGNOSTIC_FENCE_TAIL_CHARS;
        String atLimit = " ".repeat(limit - READ.length()) + READ;
        assertEquals(Set.of(COMPLETE_RECOGNIZED_OBJECT_SEQUENCE), inspect(List.of(atLimit)));
        assertEquals(Set.of(INSPECTION_LIMIT_REACHED), inspect(List.of(atLimit + " ")));
        assertEquals(Set.of(INSPECTION_LIMIT_REACHED), inspect(List.of("\n" + READ, atLimit)));
        var tails = new ArrayList<String>();
        for (int index = 0; index < ToolProtocolText.MAX_DIAGNOSTIC_FENCE_TAILS; index++) tails.add("\n" + READ);
        assertEquals(Set.of(COMPLETE_RECOGNIZED_OBJECT_SEQUENCE), inspect(tails));
        tails.add("\n" + READ);
        assertEquals(Set.of(INSPECTION_LIMIT_REACHED), inspect(tails));
    }

    @Test
    void parserResourceFailureAndInvalidDiagnosticInputAreNonpositive() {
        assertEquals(Set.of(MALFORMED_INCOMPLETE_OR_OTHER),
                inspect(List.of("\n" + "[".repeat(2_000) + "0" + "]".repeat(2_000))));
        assertEquals(Set.of(MALFORMED_INCOMPLETE_OR_OTHER), ToolProtocolText.inspectSelectedFenceTails(null, List.of()));
        assertEquals(Set.of(MALFORMED_INCOMPLETE_OR_OTHER), ToolProtocolText.inspectSelectedFenceTails("", null));
        assertEquals(Set.of(), ToolProtocolText.inspectSelectedFenceTails("", List.of()));
        var outside = new ToolProtocolText.SelectedExecutableJsonSpan(0, 2, 0, 3, 3,
                ToolProtocolText.JsonProtocolKind.FENCED_JSON);
        assertEquals(Set.of(MALFORMED_INCOMPLETE_OR_OTHER),
                ToolProtocolText.inspectSelectedFenceTails("{}", List.of(outside)));
    }

    @Test
    @Timeout(20)
    void manyValuesAreInspectedAsOneWholeTail() {
        assertEquals(Set.of(COMPLETE_RECOGNIZED_OBJECT_SEQUENCE), inspect(List.of(("\n" + READ).repeat(800))));
    }

    // Constructed spans isolate diagnosis from the selector's earlier rejection predicates.
    private static Set<ToolProtocolText.SelectedFenceTailShape> inspect(List<String> tails) {
        var source = new StringBuilder();
        var spans = new ArrayList<ToolProtocolText.SelectedExecutableJsonSpan>();
        for (String tail : tails) {
            int start = source.length();
            source.append("{}").append(tail);
            spans.add(new ToolProtocolText.SelectedExecutableJsonSpan(start, start + 2, start,
                    source.length(), source.length(), ToolProtocolText.JsonProtocolKind.FENCED_JSON));
        }
        return ToolProtocolText.inspectSelectedFenceTails(source.toString(), spans);
    }
}
