package dev.talos.core.tool;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolProtocolTextCanonicalJsonContractTest {
    private static final String READ =
            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}";

    @Test
    void fencedProposalUsesPinnedFirstJsonRootSemantics() {
        for (String trailing : List.of(
                " true",
                "\n<think>hidden</think>",
                "\n<script>alert(1)</script>",
                "\n\"unterminated",
                "\n{\"broken\":\"unterminated",
                "\n{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}")) {
            String response = "```json\n" + READ + trailing + "\n```";

            List<ToolProtocolText.SelectedExecutableJsonSpan> spans =
                    ToolProtocolText.recognizedExecutableJsonSpans(response);
            assertEquals(1, spans.size(), trailing);
            assertEquals(READ, spans.getFirst().rootFrom(response));
            assertTrue(ToolProtocolText.containsToolCalls(response), trailing);
            assertTrue(
                    ToolProtocolText.containsRecognizedCodeFenceToolCall(response),
                    trailing);
        }

        String ordinaryFirst = "```json\n"
                + "{\"name\":\"ordinary\"}\n"
                + READ
                + "\n```";
        assertTrue(ToolProtocolText.recognizedExecutableJsonSpans(
                ordinaryFirst).isEmpty());
        assertFalse(ToolProtocolText.containsToolCalls(ordinaryFirst));

        assertTrue(ToolProtocolText.looksLikeStandaloneToolJson(READ + " true"));
        assertFalse(ToolProtocolText.looksLikeStandaloneToolJson(
                "{\"name\":\"ordinary\"}" + READ));
    }

    @Test
    void multiBacktickEnvelopeOwnsEveryDelimiterTick() {
        for (int markerLength : List.of(4, 5, 6, 7, 12)) {
            String marker = "`".repeat(markerLength);
            String fence = marker + "json\n" + READ + marker;
            String source = "before" + fence + "after";

            ToolProtocolText.JsonProposalSelection selection =
                    ToolProtocolText.selectExecutableJsonProposals(source);

            assertEquals(1, selection.executionOrder().size(), marker);
            ToolProtocolText.SelectedExecutableJsonSpan selected =
                    selection.executionOrder().getFirst();
            assertEquals("before".length(),
                    selected.envelopeStartInclusive(), marker);
            assertEquals("before".length() + fence.length(),
                    selected.envelopeEndExclusive(), marker);
            assertEquals(READ, selected.rootFrom(source), marker);
            assertEquals("beforeafter",
                    ToolProtocolText.stripToolCalls(source), marker);
        }
    }

    @Test
    void strippingFenceCannotPromoteTrailingAliasIntoExecutablePosition() {
        String trailingAlias = "{\"name\":\"write_file\",\"arguments\":{"
                + "\"path\":\"x.txt\",\"content\":\"x\"}}";
        String source = "```json\n" + READ + "\n```" + trailingAlias;

        List<ToolProtocolText.SelectedExecutableJsonSpan> sourceSpans =
                ToolProtocolText.recognizedExecutableJsonSpans(source);
        assertEquals(1, sourceSpans.size(),
                "trailing alias must not be executable before stripping");
        assertEquals(READ, sourceSpans.getFirst().rootFrom(source));

        String stripped = ToolProtocolText.stripToolCalls(source);

        assertEquals("", stripped);
        assertFalse(ToolProtocolText.containsToolCalls(stripped));
        assertTrue(ToolProtocolText.recognizedExecutableJsonSpans(
                stripped).isEmpty());
    }

    @Test
    void spanOffsetsReferToOriginalUtf16TextAfterJavaStripWhitespace() {
        String javaStripWhitespace = new String(new int[] {
                0x001C, 0x001D, 0x001E, 0x001F,
                0x1680,
                0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
                0x2008, 0x2009, 0x200A,
                0x2028, 0x2029,
                0x205F, 0x3000
        }, 0, 19);
        String prefix = "prose-\uD83D\uDE80\n```json\n";
        String response = prefix
                + javaStripWhitespace
                + READ
                + javaStripWhitespace
                + "```";

        List<ToolProtocolText.SelectedExecutableJsonSpan> spans =
                ToolProtocolText.recognizedExecutableJsonSpans(response);

        assertEquals(1, spans.size());
        ToolProtocolText.SelectedExecutableJsonSpan span = spans.get(0);
        assertEquals(prefix.indexOf("```"), span.envelopeStartInclusive());
        assertEquals(response.length(), span.envelopeEndExclusive());
        assertEquals(prefix.length() + javaStripWhitespace.length(),
                span.rootStartInclusive());
        assertEquals(
                span.rootStartInclusive() + READ.length(),
                span.rootEndExclusive());
        assertEquals(READ, span.rootFrom(response));
        assertEquals(
                response.substring(
                        span.envelopeStartInclusive(), span.envelopeEndExclusive()),
                response.substring(prefix.indexOf("```")));
    }

    @Test
    void escapedToolNameUsesDecodedIdentityAcrossDetectionSurfaces() {
        String escaped =
                "{\"name\":\"talos\\u002eread_file\",\"arguments\":{\"path\":\"README.md\"}}";
        String fenced = "```json\n" + escaped + "\n```";

        assertEquals(1,
                ToolProtocolText.recognizedExecutableJsonSpans(fenced).size());
        assertTrue(ToolProtocolText.containsToolCalls(fenced));
        assertTrue(ToolProtocolText.containsRecognizedCodeFenceToolCall(fenced));
        assertTrue(ToolProtocolText.containsPotentialExecutableToolNameField(fenced));
    }

    @Test
    void truncatedNamePrefixClassificationUsesCanonicalPolicy() {
        assertTrue(ToolProtocolText.containsPotentialExecutableToolNamePrefix(
                "{\"function_name\":\"talos.write_"));
        assertTrue(ToolProtocolText.containsPotentialExecutableToolNamePrefix(
                "{\"name\":\"talos\\u002ecustom_"));
        assertTrue(ToolProtocolText.containsPotentialExecutableToolNamePrefix(
                "{\"tool\":\"write_"));
        assertTrue(ToolProtocolText.containsPotentialExecutableToolNamePrefix(
                "{\"tool_name\":\"file_utils:edit_"));

        assertFalse(ToolProtocolText.containsPotentialExecutableToolNamePrefix(
                "{\"function_name\":\"foreign:write_"));
        assertFalse(ToolProtocolText.containsPotentialExecutableToolNamePrefix(
                "{\"function_name\":\"weather."));
        assertFalse(ToolProtocolText.containsPotentialExecutableToolNamePrefix(
                "{\"function_name\":\"talos.write_file\"}"));
    }

    @Test
    void escapedNameKeyDoesNotBroadenFencedGateButRemainsStandaloneCompatible() {
        String escapedKey =
                "{\"na\\u006de\":\"talos.read_file\","
                        + "\"arguments\":{\"path\":\"README.md\"}}";

        assertTrue(ToolProtocolText.recognizedExecutableJsonSpans(
                "```json\n" + escapedKey + "\n```").isEmpty());
        assertTrue(ToolProtocolText.looksLikeStandaloneToolJson(escapedKey));
    }

    @Test
    void malformedPayloadStillDoesNotQualify() {
        String payload = "{\"name\":\"talos.read_file\",}";
        String fenced = "```json\n" + payload + "\n```";
        assertTrue(ToolProtocolText.recognizedExecutableJsonSpans(fenced).isEmpty());
        assertFalse(ToolProtocolText.containsToolCalls(fenced));
    }

    @Test
    void firstNameKeyAndFirstWrapperKeepPinnedPrecedence() {
        for (String payload : List.of(
                "{\"name\":\"ordinary\",\"tool\":\"talos.read_file\"}",
                "{\"tool_call\":{\"name\":\"ordinary\"},"
                        + "\"function_call\":{\"name\":\"talos.read_file\"}}",
                "{\"name\":\"talos.read_file\","
                        + "\"tool_call\":{\"name\":\"ordinary\"}}")) {
            String fenced = "```json\n" + payload + "\n```";
            assertTrue(
                    ToolProtocolText.recognizedExecutableJsonSpans(fenced).isEmpty(),
                    payload);
            assertFalse(ToolProtocolText.containsToolCalls(fenced), payload);
        }

        for (String payload : List.of(
                "{\"name\":\"talos.read_file\",\"tool\":\"ordinary\"}",
                "{\"name\":\"talos.read_file\",\"tool\":\"foreign:read_file\"}",
                "{\"tool_call\":{\"name\":\"talos.read_file\"},"
                        + "\"function_call\":{\"name\":\"ordinary\"}}",
                "{\"name\":\"ordinary\","
                        + "\"tool_call\":{\"name\":\"talos.read_file\"}}",
                "{\"name\":\"write_file\",\"tool\":\"talos.write_file\","
                        + "\"arguments\":{\"path\":\"x.txt\",\"content\":\"x\"}}")) {
            String fenced = "```json\n" + payload + "\n```";
            assertEquals(
                    1,
                    ToolProtocolText.recognizedExecutableJsonSpans(fenced).size(),
                    payload);
            assertTrue(ToolProtocolText.containsToolCalls(fenced), payload);
        }
    }

    @Test
    void closedAndUnclosedCodeFenceEnvelopesBlockBareFallback() {
        String unclosed = "```json\n" + READ;
        String foreignFence = "```java\n" + READ + "\n```";

        assertTrue(
                ToolProtocolText.recognizedExecutableJsonSpans(unclosed).isEmpty());
        assertFalse(ToolProtocolText.containsToolCalls(unclosed));
        assertTrue(
                ToolProtocolText.recognizedExecutableJsonSpans(foreignFence).isEmpty());
        assertFalse(ToolProtocolText.containsToolCalls(foreignFence));
    }

    @Test
    void potentialEnvelopeSpansCoverRejectedAndUnclosedToolProtocol() {
        String rejected = "```json\n" + READ + "\n<think>trailing</think>\n```";
        String unclosed = "```json\n" + READ;

        List<ToolProtocolText.PotentialJsonEnvelopeSpan> rejectedSpans =
                ToolProtocolText.potentialExecutableJsonEnvelopeSpans(rejected);
        List<ToolProtocolText.PotentialJsonEnvelopeSpan> unclosedSpans =
                ToolProtocolText.potentialExecutableJsonEnvelopeSpans(unclosed);

        assertEquals(1, rejectedSpans.size());
        assertEquals(0, rejectedSpans.get(0).startInclusive());
        assertEquals(rejected.length(), rejectedSpans.get(0).endExclusive());
        assertTrue(rejectedSpans.get(0).closed());
        assertEquals(1, unclosedSpans.size());
        assertEquals(unclosed.length(), unclosedSpans.get(0).endExclusive());
        assertFalse(unclosedSpans.get(0).closed());
        assertEquals("", ToolProtocolText.stripToolCalls(rejected));
        assertEquals("", ToolProtocolText.stripToolCalls(unclosed));
    }

    @Test
    void parentFenceLineBreakAndMarkerRunLanguageRemainsAccepted() {
        for (String lineBreak : List.of("\n", "\r\n", "\u000B", "\f", "\r",
                "\u0085", "\u2028", "\u2029")) {
            String response = "````json" + lineBreak + READ + lineBreak + "````";
            List<ToolProtocolText.SelectedExecutableJsonSpan> spans =
                    ToolProtocolText.recognizedExecutableJsonSpans(response);

            assertEquals(1, spans.size(), "line break U+"
                    + Integer.toHexString(lineBreak.codePointAt(0)));
            assertEquals(0, spans.get(0).envelopeStartInclusive());
            assertEquals(response.length(),
                    spans.get(0).envelopeEndExclusive());
            assertEquals(READ, spans.get(0).rootFrom(response));
        }

        String adjacent = "```json\n" + READ + "\n``````json\n" + READ + "\n```";
        assertEquals(
                1,
                ToolProtocolText.recognizedExecutableJsonSpans(adjacent).size());

        String backticksBeforeName = """
                ```json
                {"arguments":{"content":"```"},"name":"talos.write_file"}
                ```
                """;
        assertEquals(
                1,
                ToolProtocolText.recognizedExecutableJsonSpans(
                        backticksBeforeName).size());
    }

    @Test
    void afterNameInternalBackticksFailClosedInsideTheirOuterEnvelope() {
        String response = """
                ```json
                {"name":"talos.write_file","arguments":{"content":"```"}}
                ```
                """;

        assertTrue(ToolProtocolText.recognizedExecutableJsonSpans(response).isEmpty());
        assertFalse(ToolProtocolText.containsToolCalls(response));
        List<ToolProtocolText.PotentialJsonEnvelopeSpan> potential =
                ToolProtocolText.potentialExecutableJsonEnvelopeSpans(response);
        assertEquals(1, potential.size());
        assertEquals(0, potential.get(0).startInclusive());
        assertEquals(response.stripTrailing().length(), potential.get(0).endExclusive());
        assertTrue(potential.get(0).closed());
    }

    @Test
    void standaloneFallbackNeverEscapesClosedOrUnclosedFenceEnvelopes() {
        String closed = """
                ```java
                String marker = "```";
                {"name":"talos.read_file","arguments":{"path":"README.md"}}
                ```
                """;
        String unclosed = """
                ```java
                String marker = "```";
                {"name":"talos.read_file","arguments":{"path":"README.md"}}
                """;

        assertTrue(ToolProtocolText.recognizedExecutableJsonSpans(closed).isEmpty());
        assertTrue(ToolProtocolText.recognizedExecutableJsonSpans(unclosed).isEmpty());
        assertFalse(ToolProtocolText.containsToolCalls(closed));
        assertFalse(ToolProtocolText.containsToolCalls(unclosed));
    }

    @Test
    void adjacentOrdinaryAndToolFencesStayEnvelopeLocal() {
        String response = """
                ```json
                {"name":"demo-app","example":"ordinary"}
                ```
                ```java
                System.out.println("ordinary");
                ```
                ```json
                %s
                ```
                """.formatted(READ);

        List<ToolProtocolText.SelectedExecutableJsonSpan> spans =
                ToolProtocolText.recognizedExecutableJsonSpans(response);

        assertEquals(1, spans.size());
        assertEquals(READ, spans.get(0).rootFrom(response));
        assertEquals(response.lastIndexOf("```json"),
                spans.get(0).envelopeStartInclusive());
    }

    @Test
    void apostropheInGenericFenceCannotSwallowLaterJsonFence() {
        String response = "```text\n"
                + "don't alter this\n"
                + "```\n"
                + "```json\n"
                + READ
                + "\n```";

        List<ToolProtocolText.SelectedExecutableJsonSpan> spans =
                ToolProtocolText.recognizedExecutableJsonSpans(response);

        assertEquals(1, spans.size());
        assertEquals(READ, spans.getFirst().rootFrom(response));
        assertEquals(response.indexOf("```json"),
                spans.getFirst().envelopeStartInclusive());
    }

    @Test
    void unmatchedGenericQuoteCannotSwallowLaterJsonFence() {
        String ordinary = "```text\nString x = \"unterminated;\n```\n";
        String tool = "```json\n" + READ + "\n```";
        String response = ordinary + tool;

        List<ToolProtocolText.SelectedExecutableJsonSpan> spans =
                ToolProtocolText.recognizedExecutableJsonSpans(response);

        assertEquals(1, spans.size());
        assertEquals(READ, spans.getFirst().rootFrom(response));
    }

    @Test
    void malformedFenceCannotSwallowLaterValidFence() {
        String malformed = "```json\n{\"function_name\":\"talos.write_";
        String valid = "```json\n" + READ + "\n```";
        for (String response : List.of(
                malformed + "\n```\n" + valid,
                malformed + "``````json\n" + READ + "\n```")) {
            List<ToolProtocolText.SelectedExecutableJsonSpan> spans =
                    ToolProtocolText.recognizedExecutableJsonSpans(response);

            assertEquals(1, spans.size(), response);
            assertEquals(READ, spans.getFirst().rootFrom(response), response);
        }
    }

    @Test
    void apostropheInOrdinaryObjectCannotSwallowLaterStandaloneProposal() {
        String ordinary = "{\"label\":\"ordinary\",\"text\":don't alter}";
        String response = ordinary + "\nbetween\n" + READ;

        List<ToolProtocolText.SelectedExecutableJsonSpan> spans =
                ToolProtocolText.recognizedExecutableJsonSpans(response);

        assertEquals(1, spans.size());
        assertEquals(READ, spans.getFirst().rootFrom(response));
        assertEquals(response.indexOf(READ), spans.getFirst().rootStartInclusive());
        assertTrue(ToolProtocolText.potentialExecutableStandaloneJsonSpans(
                response).isEmpty());
        assertEquals(-1, ToolProtocolText.unsettledJsonProtocolTailStart(response));

        for (int end = 1; end < ordinary.length(); end++) {
            assertEquals(0,
                    ToolProtocolText.unsettledJsonProtocolTailStart(
                            response.substring(0, end)),
                    "ordinary prefix length " + end);
        }
        for (int end = ordinary.length(); end <= response.indexOf(READ); end++) {
            assertEquals(-1,
                    ToolProtocolText.unsettledJsonProtocolTailStart(
                            response.substring(0, end)),
                    "settled gap prefix length " + end);
        }
        for (int end = response.indexOf(READ) + 1; end < response.length(); end++) {
            assertEquals(response.indexOf(READ),
                    ToolProtocolText.unsettledJsonProtocolTailStart(
                            response.substring(0, end)),
                    "tool prefix length " + end);
        }
    }

    @Test
    void repeatedFencesRemainBoundedAndDoNotCrossIntoFinalProposal() {
        String ordinaryFence = """
                ```json
                {"label":"demo-app","value":"```"}
                ```
                """;
        StringBuilder response = new StringBuilder(1_000_000);
        for (int i = 0; i < 12_000; i++) {
            response.append(ordinaryFence);
        }
        response.append("```json\n").append(READ).append("\n```");

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    List<ToolProtocolText.SelectedExecutableJsonSpan> spans =
                            ToolProtocolText.recognizedExecutableJsonSpans(
                                    response.toString());
                    assertEquals(1, spans.size());
                    assertEquals(READ, spans.get(0).rootFrom(response.toString()));
        });
    }

    @Test
    void repeated_internal_delimiters_are_scanned_monotonically() {
        String root = "{\"name\":\"talos.write_file\","
                + "\"arguments\":{\"path\":\"x\",\"content\":\""
                + "x```".repeat(32_000)
                + "\"}}";
        String source = "```json\n" + root + "\n```";

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> assertTrue(ToolProtocolText
                        .recognizedExecutableJsonSpans(source)
                        .isEmpty()));
    }

    @Test
    void repeated_long_unsettled_delimiters_are_scanned_monotonically() {
        String source = "```json\n"
                + "{\"name\":\"talos.write_file\","
                + "\"arguments\":{\"path\":\"x\",\"content\":\""
                + "x``````".repeat(32_000)
                + "\"}}```";
        ToolProtocolText.selectExecutableJsonProposals(
                source.substring(0, Math.min(source.length(), 1_000)));

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> {
                    ToolProtocolText.JsonProposalSelection selection =
                            ToolProtocolText.selectExecutableJsonProposals(source);
                    assertTrue(selection.executionOrder().isEmpty());
                    assertTrue(selection.sourceOrder().isEmpty());
                    assertTrue(selection.recognizedCandidates().isEmpty());
                    assertEquals(1, selection.suppressionOnlyEnvelopes().size());
                    assertEquals(0, selection.suppressionOnlyEnvelopes()
                            .getFirst().startInclusive());
                    assertEquals(source.length(), selection.suppressionOnlyEnvelopes()
                            .getFirst().endExclusive());
                });
    }

    @Test
    void completeFencesDoNotRescanOneLargeTrailingEnvelope() {
        int completeCount = 8_000;
        StringBuilder source = new StringBuilder(1_000_000);
        for (int i = 0; i < completeCount; i++) {
            source.append("```json\n")
                    .append("{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"file-")
                    .append(i)
                    .append("\"}}\n```\n");
        }
        String trailingRoot = "{\"name\":\"talos.write_file\","
                + "\"arguments\":{\"path\":\"tail.txt\",\"content\":\""
                + "x".repeat(320_000)
                + "\"}}";
        source.append("```json\n").append(trailingRoot);
        String response = source.toString();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(4),
                () -> {
                    ToolProtocolText.JsonProposalSelection selection =
                            ToolProtocolText.selectExecutableJsonProposals(response);
                    assertEquals(
                            completeCount,
                            selection.executionOrder().size());
                    assertEquals(
                            1,
                            selection.suppressionOnlyEnvelopes().size());
                    assertTrue(response.substring(
                            selection.suppressionOnlyEnvelopes().getFirst()
                                    .startInclusive())
                            .startsWith("```json\n" + trailingRoot));
                });
    }

    @Test
    void malformedFencesDoNotScanAcrossLaterEnvelopes() {
        int malformedCount = 12_000;
        String malformedFence = "```json\n"
                + "{\"name\":\"talos.read_file\"\n"
                + "```\n";
        String response = malformedFence.repeat(malformedCount);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> {
                    ToolProtocolText.JsonProposalSelection selection =
                            ToolProtocolText.selectExecutableJsonProposals(response);
                    assertTrue(selection.executionOrder().isEmpty());
                    assertEquals(
                            malformedCount,
                            selection.suppressionOnlyEnvelopes().size());
                });
    }

    @Test
    void inlineRootClosesDoNotScanAcrossLaterEnvelopes() {
        int fenceCount = 8_000;
        StringBuilder source = new StringBuilder(600_000);
        for (int i = 0; i < fenceCount; i++) {
            source.append("```json\n")
                    .append("{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"file-")
                    .append(i)
                    .append("\"}}```x");
        }
        String response = source.toString();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(4),
                () -> {
                    ToolProtocolText.JsonProposalSelection selection =
                            ToolProtocolText.selectExecutableJsonProposals(response);
                    assertEquals(fenceCount, selection.executionOrder().size());
                    assertEquals(
                            "{\"name\":\"talos.read_file\","
                                    + "\"arguments\":{\"path\":\"file-0\"}}",
                            selection.executionOrder().getFirst()
                                    .rootFrom(response));
                    assertEquals(
                            "{\"name\":\"talos.read_file\","
                                    + "\"arguments\":{\"path\":\"file-"
                                    + (fenceCount - 1) + "\"}}",
                            selection.executionOrder().getLast()
                                    .rootFrom(response));
                });
    }

    @Test
    void manySelectedAndDuplicateFencesRemainBounded() {
        StringBuilder distinct = new StringBuilder(256_000);
        for (int i = 0; i < 2_000; i++) {
            distinct.append("```json\n")
                    .append("{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"file-")
                    .append(i)
                    .append("\"}}\n```\n");
        }
        String duplicateRoot =
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"same\"}}";
        String duplicateFence = "```json\n" + duplicateRoot + "\n```\n";
        String duplicate = duplicateFence.repeat(2_000);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    ToolProtocolText.JsonProposalSelection distinctSelection =
                            ToolProtocolText.selectExecutableJsonProposals(
                                    distinct.toString());
                    assertEquals(2_000, distinctSelection.executionOrder().size());
                    assertTrue(distinctSelection.suppressionOnlyEnvelopes().isEmpty());

                    ToolProtocolText.JsonProposalSelection duplicateSelection =
                            ToolProtocolText.selectExecutableJsonProposals(duplicate);
                    assertEquals(1, duplicateSelection.executionOrder().size());
                    assertEquals(1_999,
                            duplicateSelection.suppressionOnlyEnvelopes().size());
                });
    }

    @Test
    void aliasesAfterArbitraryProseDoNotBroadenParentStandaloneGrammar() {
        String aliasAfterProse =
                "Example:\n{\"name\":\"write_file\",\"arguments\":{\"path\":\"x\"}}";
        String escapedAfterProse =
                "Example:\n{\"name\":\"talos\\u002eread_file\","
                        + "\"arguments\":{\"path\":\"x\"}}";
        String canonicalAfterProse =
                "Example:\n{\"name\":\"talos.read_file\","
                        + "\"arguments\":{\"path\":\"x\"}}";

        assertTrue(ToolProtocolText.recognizedExecutableJsonSpans(
                aliasAfterProse).isEmpty());
        assertTrue(ToolProtocolText.recognizedExecutableJsonSpans(
                escapedAfterProse).isEmpty());
        assertEquals(1, ToolProtocolText.recognizedExecutableJsonSpans(
                canonicalAfterProse).size());
    }

    @Test
    void adjacentStandaloneObjectsHaveExactIndependentSpans() {
        String first =
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"a{b}.txt\"}}";
        String second =
                "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}";
        String response = "\u2003" + first + "\n \t" + second + "\n";

        List<ToolProtocolText.SelectedExecutableJsonSpan> spans =
                ToolProtocolText.recognizedExecutableJsonSpans(response);

        assertEquals(2, spans.size());
        assertEquals(first, spans.get(0).rootFrom(response));
        assertEquals(second, spans.get(1).rootFrom(response));
        assertEquals(response.indexOf(first), spans.get(0).rootStartInclusive());
        assertEquals(response.indexOf(second), spans.get(1).rootStartInclusive());
    }

    @Test
    void malformedStandaloneRangesAreDisplayOnlyAndFenceAware() {
        String malformed =
                "Before\n{\"name\":\"talos.read_file\","
                        + "\"arguments\":{\"path\":\"README.md\"}"
                        + "<think>hidden</think>}\nAfter";
        String unclosed =
                "{\"name\":\"talos.read_file\",\"arguments\":{";
        String valid = READ;
        String fenced = "```java\n" + malformed + "\n```";

        List<ToolProtocolText.PotentialJsonEnvelopeSpan> malformedSpans =
                ToolProtocolText.potentialExecutableStandaloneJsonSpans(malformed);
        List<ToolProtocolText.PotentialJsonEnvelopeSpan> unclosedSpans =
                ToolProtocolText.potentialExecutableStandaloneJsonSpans(unclosed);

        assertEquals(1, malformedSpans.size());
        assertTrue(malformedSpans.get(0).closed());
        assertEquals(malformed.indexOf('{'), malformedSpans.get(0).startInclusive());
        assertEquals(malformed.lastIndexOf('}') + 1,
                malformedSpans.get(0).endExclusive());
        assertEquals(1, unclosedSpans.size());
        assertFalse(unclosedSpans.get(0).closed());
        assertTrue(ToolProtocolText.potentialExecutableStandaloneJsonSpans(valid).isEmpty());
        assertTrue(ToolProtocolText.potentialExecutableStandaloneJsonSpans(fenced).isEmpty());
    }

    @Test
    void unsettledTailBoundaryDropsOnlyIncompleteProtocolCandidates() {
        String fenced = "```json\n" + READ + "\n```";
        for (int end = 1; end < fenced.length(); end++) {
            assertEquals(0,
                    ToolProtocolText.unsettledJsonProtocolTailStart(
                            fenced.substring(0, end)),
                    "prefix length " + end);
        }

        assertEquals(0, ToolProtocolText.unsettledJsonProtocolTailStart("{"));
        assertEquals(7, ToolProtocolText.unsettledJsonProtocolTailStart(
                "prose\n {\"na"));
        assertEquals(-1, ToolProtocolText.unsettledJsonProtocolTailStart(
                "{\"name\":\"ordinary\",\"value\":true}"));
        assertEquals(-1, ToolProtocolText.unsettledJsonProtocolTailStart(fenced));
        assertEquals(-1, ToolProtocolText.unsettledJsonProtocolTailStart(
                "```java\nordinary and intentionally unclosed"));
    }
}
