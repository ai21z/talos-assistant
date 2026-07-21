package dev.talos.core.llm;

import dev.talos.core.tool.ToolProtocolText;
import dev.talos.runtime.ToolCallParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProviderTextAssemblyTest {
    private static final String READ =
            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}";
    private static final String READ_SHA256 =
            "afc75168a955846e5f8869beef57313540b707925e166d7e7da1078e69dfec19";

    @Test
    void ordinaryProseKeepsStreamingPastOrdinaryOBackslashBacktickAndInlineBrace() {
        ProviderTextAssembly assembly = new ProviderTextAssembly(1_000);
        StringBuilder emitted = new StringBuilder();

        for (String chunk : new String[] {
                "Hello ",
                "world ",
                "C:\\temp ",
                "`inline` ",
                "value {x}"
        }) {
            assertFalse(assembly.append(chunk));
            assembly.emitStable(emitted::append);
        }
        String terminal = assembly.finalText();
        assembly.emitTerminal(terminal, emitted::append);

        assertEquals("Hello world C:\\temp `inline` value {x}", terminal);
        assertEquals(terminal, emitted.toString());
    }

    @Test
    void suppressionOnlyNestedJsonCannotInvalidateAnEmittedPrefix() {
        String source = "prose {\"x\":{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"a\"}}}";
        ProviderTextAssembly assembly = new ProviderTextAssembly(1_000);
        StringBuilder emitted = new StringBuilder();

        assembly.append(source);
        assembly.emitStable(emitted::append);
        assertEquals("prose ", emitted.toString());

        String terminal = assembly.finalText();
        assembly.emitTerminal(terminal, emitted::append);

        assertEquals(terminal, emitted.toString());
        assertFalse(terminal.contains("talos.read_file"), terminal);
    }

    @Test
    void eventAssignmentIsBufferedBeforeSanitizationCanChangeIt() {
        ProviderTextAssembly assembly = new ProviderTextAssembly(1_000);
        StringBuilder emitted = new StringBuilder();

        assembly.append("prefix textC");
        assembly.emitStable(emitted::append);
        assertEquals("prefix textC", emitted.toString());

        assembly.append("ontent = 'Ready'; suffix");
        assembly.emitStable(emitted::append);
        assertEquals("prefix textC", emitted.toString(),
                "a complete event-shaped assignment must remain buffered");

        String terminal = assembly.finalText();
        assembly.emitTerminal(terminal, emitted::append);
        assertEquals("prefix textC; suffix", terminal);
        assertEquals(terminal, emitted.toString());
        assertTrue(assembly.normalized(terminal));
    }

    @Test
    void exactBoundaryIsNotTruncationButBoundaryPlusOneIs() {
        ProviderTextAssembly exact = new ProviderTextAssembly(4);
        assertFalse(exact.append("1234"));
        assertFalse(exact.truncated());
        assertEquals("1234", exact.finalText());

        ProviderTextAssembly overflow = new ProviderTextAssembly(4);
        assertFalse(overflow.append("1234"));
        assertTrue(overflow.append("5"));
        assertTrue(overflow.truncated());
        assertEquals("1234", overflow.nonAuthoritativeText());
    }

    @Test
    void aSurrogatePairSplitAcrossChunksIsRetainedExactly() {
        ProviderTextAssembly assembly = new ProviderTextAssembly(100);
        String pair = "\uD83D\uDE00";
        StringBuilder emitted = new StringBuilder();

        assembly.append("before " + pair.substring(0, 1));
        assembly.emitStable(emitted::append);
        assertEquals("before ", emitted.toString());

        assembly.append(pair.substring(1) + " after");
        assembly.emitStable(emitted::append);
        String terminal = assembly.finalText();
        assembly.emitTerminal(terminal, emitted::append);

        assertEquals("before " + pair + " after", terminal);
        assertEquals(terminal, emitted.toString());
    }

    @Test
    void nonAuthoritativeProjectionNeverChangesAnAlreadyEmittedPrefix() {
        ProviderTextAssembly assembly = new ProviderTextAssembly(1_000);
        StringBuilder emitted = new StringBuilder();

        assembly.append("  visible prose\n\n\n");
        assembly.emitStable(emitted::append);
        String terminal = assembly.nonAuthoritativeText();
        assembly.emitTerminal(terminal, emitted::append);

        assertEquals("  visible prose\n\n\n", terminal);
        assertEquals(terminal, emitted.toString());
    }

    @Test
    void nonAuthoritativeProjectionKeepsSafeProseAndDropsUnsettledProtocol() {
        ProviderTextAssembly assembly = new ProviderTextAssembly(1_000);
        StringBuilder emitted = new StringBuilder();

        assembly.append("Before\n```json\n{\"name\":\"talos.write_file\",");
        assembly.emitStable(emitted::append);
        String terminal = assembly.nonAuthoritativeText();
        assembly.emitTerminal(terminal, emitted::append);

        assertEquals("Before\n", terminal);
        assertEquals(terminal, emitted.toString());
    }

    @Test
    void removableTextCannotJoinEmittedBackticksIntoProtocol() {
        String source = "``<script>x</script>`json\n"
                + "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}\n```";

        ProviderTextAssembly authoritative = new ProviderTextAssembly(1_000);
        StringBuilder authoritativeOutput = new StringBuilder();
        authoritative.append(source);
        authoritative.emitStable(authoritativeOutput::append);
        assertEquals("", authoritativeOutput.toString(),
                "ambiguous backticks must stay buffered");
        String finalText = authoritative.finalText();
        authoritative.emitTerminal(finalText, authoritativeOutput::append);
        assertEquals("", finalText);
        assertEquals(finalText, authoritativeOutput.toString());

        ProviderTextAssembly nonAuthoritative = new ProviderTextAssembly(1_000);
        StringBuilder nonAuthoritativeOutput = new StringBuilder();
        nonAuthoritative.append(source);
        nonAuthoritative.emitStable(nonAuthoritativeOutput::append);
        String rejected = nonAuthoritative.nonAuthoritativeText();
        nonAuthoritative.emitTerminal(rejected, nonAuthoritativeOutput::append);
        assertEquals("", rejected);
        assertEquals(rejected, nonAuthoritativeOutput.toString());
    }

    @Test
    void ordinaryApostropheObjectCannotConsumeLaterNonAuthoritativeProjection() {
        String ordinary = "{\"label\":\"ordinary\",\"text\":don't alter}";
        String proposal = "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";
        String source = "before\n" + ordinary + "\nbetween\n" + proposal + "\nafter";
        String expected = "before\n" + ordinary + "\nbetween\n\nafter";

        for (int split = 0; split <= source.length(); split++) {
            ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
            StringBuilder emitted = new StringBuilder();

            assembly.append(source.substring(0, split));
            assembly.emitStable(emitted::append);
            assembly.append(source.substring(split));
            assembly.emitStable(emitted::append);
            String terminal = assembly.nonAuthoritativeText();
            assembly.emitTerminal(terminal, emitted::append);

            assertEquals(expected, terminal, "split=" + split);
            assertEquals(terminal, emitted.toString(), "emitted split=" + split);
        }
    }

    @Test
    void everyCappedFenceAndStandalonePrefixFailsClosed() {
        for (String proposal : new String[] {
                "```json\n{\"name\":\"talos.write_file\","
                        + "\"arguments\":{\"path\":\"x\",\"content\":\"Ready\"}}\n```",
                "{\"name\":\"talos.write_file\","
                        + "\"arguments\":{\"path\":\"x\",\"content\":\"Ready\"}}"
        }) {
            for (int cap = 1; cap <= proposal.length(); cap++) {
                ProviderTextAssembly assembly = new ProviderTextAssembly(cap);
                assertFalse(assembly.append(proposal.substring(0, cap)), "cap=" + cap);
                assertTrue(assembly.append("x"), "cap=" + cap);

                String terminal = assembly.nonAuthoritativeText();

                assertEquals(
                        "",
                        terminal,
                        "protocol prefix leaked at cap=" + cap + ": " + terminal);
            }
        }
    }

    @Test
    void leadingAdjacentProposalNeverBecomesAnImmutableStablePrefix() {
        String ordinary = "{\"label\":\"ordinary\"}";
        String source = ordinary + READ;

        for (int split = 0; split <= source.length(); split++) {
            ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
            StringBuilder emitted = new StringBuilder();
            assembly.append(source.substring(0, split));
            assembly.emitStable(emitted::append);
            assembly.append(source.substring(split));
            assembly.emitStable(emitted::append);

            assertEquals(ordinary, emitted.toString(), "stable split=" + split);
            String rejected = assembly.nonAuthoritativeText();
            assembly.emitTerminal(rejected, emitted::append);
            assertEquals(ordinary, rejected, "terminal split=" + split);
            assertEquals(rejected, emitted.toString(), "emitted split=" + split);
        }
    }

    @Test
    void everyLeadingAdjacentProposalPrefixFailsClosed() {
        String ordinary = "{\"label\":\"ordinary\"}";
        for (int prefixLength = 1; prefixLength <= READ.length(); prefixLength++) {
            String source = ordinary + READ.substring(0, prefixLength);
            ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
            StringBuilder emitted = new StringBuilder();
            for (int index = 0; index < source.length(); index++) {
                assembly.append(source.substring(index, index + 1));
                assembly.emitStable(emitted::append);
            }

            assertEquals(ordinary, emitted.toString(), "prefix=" + prefixLength);
            String rejected = assembly.nonAuthoritativeText();
            assembly.emitTerminal(rejected, emitted::append);
            assertEquals(ordinary, rejected, "terminal prefix=" + prefixLength);
            assertEquals(rejected, emitted.toString(), "emitted prefix=" + prefixLength);
        }
    }

    @Test
    void leadingAdjacentMixedJsonValuesRetainContextWithoutExposingProtocol() {
        String leading = " \t\u2003";
        String ordinary = "{\"label\":\"ordinary\"}";
        String intermediate = "[1]true\"value\"null";
        String source = leading + ordinary + intermediate + READ;
        String rejectedExpected = leading + ordinary;

        for (int split = 0; split <= source.length(); split++) {
            ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
            StringBuilder emitted = new StringBuilder();
            assembly.append(source.substring(0, split));
            assembly.emitStable(emitted::append);
            assembly.append(source.substring(split));
            assembly.emitStable(emitted::append);

            assertFalse(emitted.toString().contains("talos.read_file"),
                    "stable split=" + split);
            String rejected = assembly.nonAuthoritativeText();
            assembly.emitTerminal(rejected, emitted::append);
            assertEquals(rejectedExpected, rejected, "terminal split=" + split);
            assertEquals(rejected, emitted.toString(), "emitted split=" + split);
        }

        ProviderTextAssembly authoritative = new ProviderTextAssembly(10_000);
        authoritative.append(source);
        assertEquals(source, authoritative.finalText());

        for (int prefixLength = 1; prefixLength <= READ.length(); prefixLength++) {
            ProviderTextAssembly incomplete = new ProviderTextAssembly(10_000);
            StringBuilder emitted = new StringBuilder();
            String prefixSource = leading + ordinary + intermediate
                    + READ.substring(0, prefixLength);
            for (int index = 0; index < prefixSource.length(); index++) {
                incomplete.append(prefixSource.substring(index, index + 1));
                incomplete.emitStable(emitted::append);
            }
            String rejected = incomplete.nonAuthoritativeText();
            incomplete.emitTerminal(rejected, emitted::append);
            assertEquals(rejectedExpected, rejected, "prefix=" + prefixLength);
            assertEquals(rejected, emitted.toString(), "emitted prefix=" + prefixLength);
            assertFalse(ToolProtocolText.containsToolCalls(rejected), rejected);

            ProviderTextAssembly eos = new ProviderTextAssembly(10_000);
            eos.append(prefixSource);
            eos.emitStable(ignored -> {});
            assertEquals(
                    prefixLength == READ.length() ? prefixSource : rejectedExpected,
                    eos.finalText(),
                    "provider EOS prefix=" + prefixLength);
        }
    }

    @Test
    void terminalProjectionDoesNotDependOnStableEmissionSinkPresence() {
        String ordinary = "{\"label\":\"ordinary\"}";
        String source = ordinary + "[1]true\"value\"null" + READ.substring(0, 24);

        ProviderTextAssembly nullSink = new ProviderTextAssembly(10_000);
        nullSink.append(source);
        nullSink.emitStable(null);

        ProviderTextAssembly noOpSink = new ProviderTextAssembly(10_000);
        noOpSink.append(source);
        noOpSink.emitStable(ignored -> {});

        ProviderTextAssembly recordingSink = new ProviderTextAssembly(10_000);
        StringBuilder emitted = new StringBuilder();
        recordingSink.append(source);
        recordingSink.emitStable(emitted::append);

        assertEquals(ordinary, nullSink.nonAuthoritativeText());
        assertEquals(nullSink.nonAuthoritativeText(), noOpSink.nonAuthoritativeText());
        assertEquals(nullSink.nonAuthoritativeText(), recordingSink.nonAuthoritativeText());
        assertEquals(nullSink.finalText(), noOpSink.finalText());
        assertEquals(nullSink.finalText(), recordingSink.finalText());
    }

    @Test
    void provenStablePrefixCanBeDeliveredAfterAnEarlierNullSinkScan() {
        ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
        StringBuilder delivered = new StringBuilder();
        String ordinary = "{\"label\":\"ordinary\"}";

        assembly.append(ordinary + "[1]");
        assembly.emitStable(null);
        assembly.emitStable(delivered::append);

        assertEquals(ordinary, delivered.toString());
    }

    @Test
    void terminalProjectionParityIncludesWhitespaceNormalizationHazards() {
        String source = "safe\n\n\n" + READ + "\nafter";

        ProviderTextAssembly nullSink = new ProviderTextAssembly(10_000);
        nullSink.append(source);
        nullSink.emitStable(null);

        ProviderTextAssembly recordingSink = new ProviderTextAssembly(10_000);
        recordingSink.append(source);
        recordingSink.emitStable(ignored -> {});

        assertEquals(nullSink.nonAuthoritativeText(), recordingSink.nonAuthoritativeText());
        assertEquals("safe\n\n\n", nullSink.nonAuthoritativeText());
    }

    @Test
    void balancedApostrophePseudoObjectDoesNotHideLaterProviderProposal() {
        String pseudo = "{\"message\": 'don't stop'}";
        String source = pseudo + "\n" + READ;
        ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);

        assembly.append(source);
        assembly.emitStable(ignored -> {});
        String terminal = assembly.finalText();

        assertEquals(source, terminal);
        assertEquals(1, ToolCallParser.parse(terminal).size(), terminal);
        assertEquals(READ,
                ToolProtocolText.recognizedExecutableJsonSpans(terminal)
                        .getFirst()
                        .rootFrom(terminal));
    }

    @Test
    void unsafeFenceTailsArePartitionInvariantWithoutChangingSelectedRoot()
            throws Exception {
        for (String tail : List.of(
                "\n<script>outside()</script>",
                "\n<think>outside</think>",
                "\n\"unterminated <script>outside()</script>",
                "\n{\"name\":\"talos.write_file\",\"arguments\":{"
                        + "\"path\":\"x.js\","
                        + "\"content\":\"<script>outside()</script>\"}}")) {
            String source = "```json\n" + READ + tail + "\n```";
            String expected = assembledFinal(source, List.of(source));

            assertFalse(expected.contains("<script>"), expected);
            assertFalse(expected.contains("outside()"), expected);
            assertFalse(expected.contains("<think>"), expected);
            assertFalse(expected.contains("outside</think>"), expected);
            assertEquals(1, ToolCallParser.parse(expected).size(), expected);
            assertEquals(READ_SHA256, sha256(
                    ToolProtocolText.recognizedExecutableJsonSpans(expected)
                            .getFirst()
                            .rootFrom(expected)));

            for (int split = 0; split <= source.length(); split++) {
                assertEquals(expected, assembledFinal(
                        source,
                        List.of(source.substring(0, split), source.substring(split))),
                        "tail=" + tail + " split=" + split);
            }

            List<String> oneCharacterChunks = source.chars()
                    .mapToObj(value -> Character.toString((char) value))
                    .toList();
            assertEquals(expected,
                    assembledFinal(source, oneCharacterChunks),
                    "one-character tail=" + tail);
        }
    }

    private static String assembledFinal(String source, List<String> chunks) {
        ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
        StringBuilder emitted = new StringBuilder();
        for (String chunk : chunks) {
            assertFalse(assembly.append(chunk));
            assembly.emitStable(emitted::append);
        }
        assertEquals(source, assembly.rawText());
        String terminal = assembly.finalText();
        assembly.emitTerminal(terminal, emitted::append);
        assertEquals(terminal, emitted.toString());
        assertTrue(assembly.normalized(terminal));
        return terminal;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
