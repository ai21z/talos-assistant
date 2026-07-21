package dev.talos.core.tool;

import dev.talos.core.util.Sanitize;
import dev.talos.runtime.ToolCallParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ToolProtocolTextSelectedJsonSpanContractTest {
    private static final String READ =
            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}";
    private static final String LIST =
            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}";
    private static final String WRITE =
            "{\"name\":\"talos.write_file\",\"arguments\":{"
                    + "\"path\":\"x.txt\",\"content\":\"safe\"}}";

    @Test
    void selectedFencedPayloadEndsAtTheFirstConsumedJsonRoot() {
        for (String trailing : List.of(
                "\n<script>outside()</script>",
                "\n<think>outside</think>",
                "\n" + LIST)) {
            String response = "```json\n" + READ + trailing + "\n```";

            List<ToolProtocolText.SelectedExecutableJsonSpan> selected =
                    ToolProtocolText.recognizedExecutableJsonSpans(response);

            assertEquals(1, selected.size(), trailing);
            ToolProtocolText.SelectedExecutableJsonSpan span = selected.getFirst();
            assertEquals(response.indexOf(READ), span.rootStartInclusive(), trailing);
            assertEquals(response.indexOf(READ) + READ.length(),
                    span.rootEndExclusive(), trailing);
            assertEquals(READ, span.rootFrom(response), trailing);
        }
    }

    @Test
    void excludedSelectionRangesMustContainAtLeastOneSourceCharacter() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolProtocolText.SourceRange(4, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolProtocolText.SourceRange(5, 4));
    }

    @Test
    void unsettledTailUsesTheCanonicalLeadingAdjacentValueSequence() {
        String leading = " \t\u2003";
        String prefix = leading + "{\"label\":\"ordinary\"}"
                + "[1]true\"value\"null";
        for (int length = 1; length < READ.length(); length++) {
            String source = prefix + READ.substring(0, length);
            assertEquals(prefix.length(),
                    ToolProtocolText.unsettledJsonProtocolTailStart(source),
                    "prefix=" + length);
        }
        assertEquals(-1,
                ToolProtocolText.unsettledJsonProtocolTailStart(prefix + READ));
    }

    @Test
    void exclusionAwareSelectionRetainsPriorityDeduplicationAndSourceIdentity() {
        String hiddenFence = "```json\n" + WRITE + "\n```";
        String visibleFence = "```json\n" + WRITE + "\n```";
        String response = hiddenFence + "\n" + visibleFence + "\n" + READ;
        int visibleStart = hiddenFence.length() + 1;

        ToolProtocolText.JsonProposalSelection selection =
                ToolProtocolText.selectExecutableJsonProposalsOutside(
                        response,
                        List.of(
                                new ToolProtocolText.SourceRange(0, hiddenFence.length()),
                                new ToolProtocolText.SourceRange(
                                        hiddenFence.length() - 2,
                                        visibleStart)));

        assertEquals(List.of(WRITE), selection.executionOrder().stream()
                .map(span -> span.rootFrom(response))
                .toList());
        assertEquals(response.indexOf(WRITE, visibleStart),
                selection.executionOrder().getFirst().rootStartInclusive());
        assertEquals(2, selection.recognizedCandidates().size());
    }

    @Test
    void excludedFenceCannotControlVisibleBarePriority() {
        String fence = "```json\n" + WRITE + "\n```";
        String response = fence + "\n" + READ;

        ToolProtocolText.JsonProposalSelection selection =
                ToolProtocolText.selectExecutableJsonProposalsOutside(
                        response,
                        List.of(new ToolProtocolText.SourceRange(0, fence.length())));

        assertEquals(List.of(READ), selection.executionOrder().stream()
                .map(span -> span.rootFrom(response))
                .toList());
    }

    @Test
    void canonicalSelectionAppliesExactPayloadDeduplication() {
        String duplicateStandalone = READ + "\n" + READ;
        String fence = "```json\n" + READ + "\n```";
        String duplicateFences = fence + "\n" + fence;

        assertEquals(1, ToolCallParser.parse(duplicateStandalone).size());
        assertEquals(1,
                ToolProtocolText.recognizedExecutableJsonSpans(
                        duplicateStandalone).size());
        assertEquals(1, ToolCallParser.parse(duplicateFences).size());
        assertEquals(1,
                ToolProtocolText.recognizedExecutableJsonSpans(
                        duplicateFences).size());
    }

    @Test
    void byteDistinctRootsThatDecodeToTheSameCallRemainDistinctAndOrdered() {
        String spaced = "{\"name\": \"talos.read_file\", "
                + "\"arguments\": {\"path\": \"README.md\"}}";
        String response = "```json\n" + READ + "\n```\n"
                + "```json\n" + spaced + "\n```";

        ToolProtocolText.JsonProposalSelection selection =
                ToolProtocolText.selectExecutableJsonProposals(response);

        assertEquals(
                List.of(READ, spaced),
                selection.executionOrder().stream()
                        .map(span -> span.rootFrom(response))
                        .toList());
        assertEquals(
                List.of(READ, spaced),
                selection.sourceOrder().stream()
                        .map(span -> span.rootFrom(response))
                        .toList());
        assertEquals(2, ToolCallParser.parse(response).size(),
                "deduplication is exact-root identity, not semantic call identity");
    }

    @Test
    void canonicalSelectionAppliesFencePriorityAndParserOrder() {
        String fencedWrite = "```json\n" + WRITE + "\n```";
        String leadingAdjacent = READ + "\n" + LIST + "\n" + fencedWrite;
        List<ToolProtocolText.SelectedExecutableJsonSpan> selected =
                ToolProtocolText.recognizedExecutableJsonSpans(leadingAdjacent);
        ToolProtocolText.JsonProposalSelection selection =
                ToolProtocolText.selectExecutableJsonProposals(leadingAdjacent);

        assertEquals(
                List.of("talos.write_file", "talos.read_file", "talos.list_dir"),
                ToolCallParser.parse(leadingAdjacent).stream()
                        .map(call -> call.toolName())
                        .toList());
        assertEquals(List.of(WRITE, READ, LIST), selected.stream()
                .map(span -> span.rootFrom(leadingAdjacent))
                .toList());
        assertEquals(List.of(READ, LIST, WRITE), selection.sourceOrder().stream()
                .map(span -> span.rootFrom(leadingAdjacent))
                .toList());

        String fencedThenBare = "```json\n" + READ + "\n```\n" + LIST;
        assertEquals(1, ToolCallParser.parse(fencedThenBare).size());
        assertEquals(List.of(READ),
                ToolProtocolText.recognizedExecutableJsonSpans(fencedThenBare)
                        .stream()
                        .map(span -> span.rootFrom(fencedThenBare))
                        .toList());
    }

    @Test
    void argumentDistinctRepeatedCallsRemainDistinct() {
        String first =
                "{\"name\":\"talos.write_file\",\"arguments\":{"
                        + "\"path\":\"x.txt\",\"content\":\"one\"}}";
        String second =
                "{\"name\":\"talos.write_file\",\"arguments\":{"
                        + "\"path\":\"x.txt\",\"content\":\"two\"}}";
        String response = first + "\n" + second;

        assertEquals(2, ToolCallParser.parse(response).size());
        assertEquals(List.of(first, second),
                ToolProtocolText.recognizedExecutableJsonSpans(response)
                        .stream()
                        .map(span -> span.rootFrom(response))
                        .toList());
    }

    @Test
    void sanitizationPreservesOnlyTheSelectedRootExactly() {
        for (String trailing : List.of(
                "\n<script>outside()</script>",
                "\n<think>outside</think>",
                "\n{\"name\":\"talos.write_file\",\"arguments\":{"
                        + "\"path\":\"x.js\","
                        + "\"content\":\"<script>outside()</script>\"}}")) {
            String response = "```json\n" + READ + trailing + "\n```";

            String sanitized = Sanitize.sanitizeForOutputPreservingToolCalls(response);

            assertTrue(sanitized.contains(READ), sanitized);
            assertFalse(sanitized.contains("<script>"), sanitized);
            assertFalse(sanitized.contains("outside()"), sanitized);
            assertFalse(sanitized.contains("<think>"), sanitized);
            assertFalse(sanitized.contains("outside</think>"), sanitized);
            assertFalse(sanitized.contains("talos.write_file"), sanitized);
            assertEquals(1, ToolCallParser.parse(sanitized).size(), sanitized);
            assertEquals("talos.read_file",
                    ToolCallParser.parse(sanitized).getFirst().toolName());
        }
    }

    @Test
    void subordinateProposalInsideSelectedFenceIsSuppressionOnly() {
        String unsafeWrite =
                "{\"name\":\"talos.write_file\",\"arguments\":{"
                        + "\"path\":\"x.js\","
                        + "\"content\":\"<script>outside()</script>\"}}";
        for (String subordinate : List.of(unsafeWrite, READ)) {
            String response = "```json\n" + READ + "\n" + subordinate + "\n```";

            ToolProtocolText.JsonProposalSelection selection =
                    ToolProtocolText.selectExecutableJsonProposals(response);

            assertEquals(List.of(READ), selection.executionOrder().stream()
                    .map(span -> span.rootFrom(response))
                    .toList());
            assertEquals(1, selection.suppressionOnlyEnvelopes().size());
            ToolProtocolText.PotentialJsonEnvelopeSpan suppressed =
                    selection.suppressionOnlyEnvelopes().getFirst();
            assertEquals(subordinate,
                    response.substring(
                            suppressed.startInclusive(),
                            suppressed.endExclusive()));

            String sanitized = Sanitize.sanitizeForOutputPreservingToolCalls(response);
            assertEquals(1, occurrences(sanitized, READ), sanitized);
            assertFalse(sanitized.contains("talos.write_file"), sanitized);
            assertFalse(sanitized.contains("outside()"), sanitized);
            assertEquals(1, ToolCallParser.parse(sanitized).size(), sanitized);
        }
    }

    @Test
    void unsafeDuplicateKeepsOnlyTheSelectedExactRoot() {
        String unsafe =
                "{\"name\":\"talos.write_file\",\"arguments\":{"
                        + "\"path\":\"x.js\","
                        + "\"content\":\"<script>inside()</script>\"}}";
        for (String response : List.of(
                unsafe + "\n" + unsafe,
                "```json\n" + unsafe + "\n```\n"
                        + "```json\n" + unsafe + "\n```")) {
            ToolProtocolText.JsonProposalSelection selection =
                    ToolProtocolText.selectExecutableJsonProposals(response);
            assertEquals(1, selection.executionOrder().size(), response);
            assertEquals(1, selection.suppressionOnlyEnvelopes().size(), response);

            String sanitized = Sanitize.sanitizeForOutputPreservingToolCalls(response);

            assertEquals(1, occurrences(sanitized, unsafe), sanitized);
            assertEquals(1, ToolCallParser.parse(sanitized).size(), sanitized);
            assertEquals("<script>inside()</script>",
                    ToolCallParser.parse(sanitized).getFirst().param("content"));
            assertEquals("", ToolProtocolText.stripToolCalls(response), response);
        }
    }

    @Test
    void fencedPriorityMakesTrailingBareProposalSuppressionOnly() {
        String unsafeBare =
                "{\"name\":\"talos.write_file\",\"arguments\":{"
                        + "\"path\":\"x.js\","
                        + "\"content\":\"<script>outside()</script>\"}}";
        String response = "```json\n" + READ + "\n```\n" + unsafeBare;
        ToolProtocolText.JsonProposalSelection selection =
                ToolProtocolText.selectExecutableJsonProposals(response);

        assertEquals(List.of(READ), selection.executionOrder().stream()
                .map(span -> span.rootFrom(response))
                .toList());
        assertEquals(1, selection.suppressionOnlyEnvelopes().size());

        String sanitized = Sanitize.sanitizeForOutputPreservingToolCalls(response);

        assertTrue(sanitized.contains(READ), sanitized);
        assertFalse(sanitized.contains("talos.write_file"), sanitized);
        assertFalse(sanitized.contains("outside()"), sanitized);
        assertEquals(1, ToolCallParser.parse(sanitized).size(), sanitized);
        assertEquals("", ToolProtocolText.stripToolCalls(response));
    }

    @Test
    void identicalRootsWithDifferentFenceTailsDeduplicateByRoot() {
        String firstFence = "```json\n" + READ
                + "\n<script>firstTail()</script>\n```";
        String secondFence = "```json\n" + READ
                + "\n<script>secondTail()</script>\n```";
        String response = firstFence + "\n" + secondFence;

        ToolProtocolText.JsonProposalSelection selection =
                ToolProtocolText.selectExecutableJsonProposals(response);
        assertEquals(1, selection.executionOrder().size());
        assertEquals(1, selection.suppressionOnlyEnvelopes().size());

        String sanitized = Sanitize.sanitizeForOutputPreservingToolCalls(response);

        assertEquals(1, occurrences(sanitized, READ), sanitized);
        assertFalse(sanitized.contains("firstTail"), sanitized);
        assertFalse(sanitized.contains("secondTail"), sanitized);
        assertEquals(1, ToolCallParser.parse(sanitized).size(), sanitized);
        assertEquals("", ToolProtocolText.stripToolCalls(response));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = text.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }
}
