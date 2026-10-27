package dev.talos.core.llm;

import dev.talos.core.tool.ToolProtocolText;
import dev.talos.core.util.Sanitize;
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
    void completeRecordedMixedAnswerIsExactAcrossEveryChunkBoundary() throws Exception {
        String source = RecordedMixedCodeAnswer.text();
        assertEquals(RecordedMixedCodeAnswer.SHA256, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))));
        assertExactAcrossPartitions(source);
    }

    @Test
    void inertInlineHtmlLiteralRemainsExactAcrossEveryPartition() {
        String source = "Verified: `<button id=\"calculate\">Calculate</button>`; "
                + "replace `\"#calc\"` with `\"#calculate\"`.";
        for (int split = 0; split <= source.length(); split++) {
            ProviderTextAssembly assembly = new ProviderTextAssembly(1_000);
            StringBuilder emitted = new StringBuilder();
            assembly.append(source.substring(0, split));
            assembly.emitStable(emitted::append);
            assembly.append(source.substring(split));
            assembly.emitStable(emitted::append);
            String terminal = assembly.finalText();
            assembly.emitTerminal(terminal, emitted::append);
            assertEquals(source, terminal, "partition " + split);
            assertEquals(source, emitted.toString(), "partition " + split);
            assertFalse(assembly.normalized(terminal), "partition " + split);
        }
        assertFalse(Sanitize.sanitizeForOutput(source).contains("<button"),
                "the existing shared terminal/prompt sanitizer is not relaxed");
    }

    @Test
    void inertFencedHtmlRemainsExactAcrossEveryPartitionWithOtherCodeContexts() {
        String source = "The selector in `script.js` is `#calc`. The button is:\n\n"
                + "```html\n<button id=\"calculate\">Calculate</button>\n```\n\n"
                + "Use this selector:\n```js\nconst selector = `#calculate`;\n```\n"
                + "The BMI is:\n```\n72 / (1.8 * 1.8) = 22.222\n```\n"
                + "Keep `scripts.js` unchanged.";
        assertExactAcrossPartitions(source);
        assertFalse(Sanitize.sanitizeForOutput(source).contains("<button"),
                "only the provider-EOS literal projection changes, not the shared sanitizer");
    }

    @Test
    void closedCodeRegionsPreserveMarkupByStructureNotTagOrLanguage() {
        for (String source : List.of(
                "```jsx\n<script src=\"script.js\"></script><p id=\"result\">\n```",
                "~~~html\n<button onclick=\"run()\">Run</button>\n~~~",
                "````text\n<button style=\"display:none\">Hidden</button>\n````",
                "Example: `<button id=\"x\">Run</textarea>` and `<p id=\"result\">`.",
                "Example: `<script src=\"script.js\"></script>` and `<script src=\"scripts.js\">`.",
                "Example: ``<button title=\"`literal`\">Run</button>``.",
                "```\n<textarea name='note'>Draft text</textarea>\n```",
                "```HTML\n\n  <button title=\"Next step\">Continue</button>\t\n\n```\n",
                "Inline `<button id=\"one\">One</button>`.\n"
                        + "```html\n<button id=\"two\">Two</button>\n```\n"
                        + "```text\nordinary `quoted` text\n```\n")) {
            assertExactAcrossPartitions(source);
        }
    }

    @Test
    void unsafeMalformedOrAmbiguousFencesRetainTheExistingProjection() {
        String safe = "<button id=\"x\">Run</button>";
        for (String source : List.of(
                "```html\n<button id=\"x\"><think>hidden</think></button>\n```",
                "```html\n<button id=\"x\">\\u003cthink\\u003ehidden</button>\n```",
                "```html\n<button id=\"x\">\u001b[31mRun</button>\n```",
                "```html\n" + safe + "\n```\n<think>hidden</think>",
                "```html\n" + safe + "\n```\n\u0001control",
                "```html\r\n" + safe + "\r\n```",
                "```html\n" + safe + "\n``` trailing",
                "```html\n" + safe + "\n~~~",
                "```html\n" + safe,
                "prefix ```html\n" + safe + "\n```",
                "```html\n<button>Run\n```\n</button>",
                "```html\n" + safe + "\n```js\nnested\n```\n```",
                "```html\n" + safe + "\n```\n```json\n" + READ + "\n```")) {
            ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
            for (String chunk : source.chars().mapToObj(value -> Character.toString((char) value)).toList()) {
                assembly.append(chunk);
                assembly.emitStable(null);
            }
            String expected = ToolProtocolText.unsettledJsonProtocolTailStart(source) >= 0
                    ? assembly.nonAuthoritativeText()
                    : Sanitize.sanitizeForOutputPreservingToolCalls(source);
            assertEquals(expected, assembly.finalText(), source);
            assertEquals(!source.equals(expected), assembly.normalized(assembly.finalText()), source);
        }
    }

    @Test
    void fencedLiteralDoesNotGainAnExceptionWithoutProviderEos() {
        String source = "Example:\n```html\n<button id=\"x\">Run</button>\n```\nDone.";
        ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
        StringBuilder emitted = new StringBuilder();
        assembly.append(source);
        assembly.emitStable(emitted::append);
        String projected = assembly.nonAuthoritativeText();
        assembly.emitTerminal(projected, emitted::append);
        assertEquals(Sanitize.sanitizeForOutputPreservingToolCalls(source), projected);
        assertFalse(projected.contains("<button"));
        assertEquals(projected, emitted.toString());
        assertTrue(assembly.normalized(projected));
    }

    @Test
    void fencedLiteralRetainsTheBoundedCodePayloadSizeLimit() {
        for (int length : List.of(4_096, 4_097)) {
            String literal = "<button>" + "x".repeat(length - 17) + "</button>";
            String source = "```html\n" + literal + "\n```";
            ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
            assembly.append(source);
            assertEquals(length == 4_096 ? source
                    : Sanitize.sanitizeForOutputPreservingToolCalls(source), assembly.finalText());
            assertEquals(length != 4_096, assembly.normalized(assembly.finalText()));
        }
    }

    @Test
    void unsafeOrAmbiguousQuotedHtmlKeepsExistingProjection() {
        List<String> sources = List.of(
                "`<button id=\"x\">\\u003cthink\\u003ehidden</button>`",
                "`<button id=\"x\">Run</button>",
                "\\`<button id=\"x\">Run</button>`",
                "```html\n<button id=\"x\">Run</button>\n``",
                "<button>outside `<button id=\"x\">Run</button>` tail</button>",
                "`<button id=\"x\">Run</button>`<script>secret()</script>",
                "`<button id=\"x\">Run</button>`<think>hidden</think>",
                "`<button id=\"x\">\u001b[31mRun</button>`",
                "`<button id=\"x\"><tool_call>" + READ + "</tool_call></button>`",
                "`<button id=\"x\">Run</button>`\n```json\n" + READ + "\n```",
                "<button>\n```json\n" + READ + "\n```\n`</button>`");
        for (String hostile : sources) {
            // Ordinary suffix also exercises settled EOS; a trailing backtick
            // alone can take the older unsettled-protocol branch instead.
            for (String source : List.of(hostile, hostile + " End.")) {
                ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
                assembly.append(source);
                String originalProjection = ToolProtocolText.unsettledJsonProtocolTailStart(source) >= 0
                        ? assembly.nonAuthoritativeText()
                        : Sanitize.sanitizeForOutputPreservingToolCalls(source);
                assertEquals(originalProjection, assembly.finalText(), source);
                assertEquals(!source.equals(originalProjection),
                        assembly.normalized(assembly.finalText()), source);
            }
        }
    }

    @Test
    void literalRecognitionIsNotBoundToTheCapturedElementNameOrIdentifier() {
        for (String source : List.of(
                "Example: `<textarea id='message'>Draft text</textarea>`.",
                "Example: `<button class=\"secondary\" title=\"Next step\">Continue</button>`.",
                "Examples: `<button id=\"one\">One</button>` and `<textarea name=\"note\">Two</textarea>`.")) {
            ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
            for (int index = 0; index < source.length(); index++) {
                assembly.append(source.substring(index, index + 1));
                assembly.emitStable(null);
            }
            assertEquals(source, assembly.finalText());
            assertFalse(assembly.normalized(assembly.finalText()));
        }
    }

    @Test
    void quotedHtmlDoesNotRewriteExecutableJsonArguments() {
        String root = "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"index.html\","
                + "\"old_string\":\"<button id=\\\"old\\\">Old</button>\","
                + "\"new_string\":\"<button id=\\\"new\\\">New</button>\"}}";
        for (String example : List.of("Example: `<button id=\"new\">New</button>`.\n",
                "Example:\n```html\n<button id=\"new\">New</button>\n```\n")) {
            String source = example + "```json\n" + root + "\n```\n";
            ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
            assembly.append(source);
            String projected = assembly.finalText();
            assertEquals(Sanitize.sanitizeForOutputPreservingToolCalls(source), projected);
            assertEquals(root, ToolProtocolText.selectExecutableJsonProposals(projected)
                    .executionOrder().getFirst().rootFrom(projected));
            assertTrue(assembly.normalized(projected), "textual tools do not get a new normalization waiver");
        }
    }

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

    private static void assertExactAcrossPartitions(String source) {
        for (int split = 0; split <= source.length(); split++) {
            ProviderTextAssembly assembly = new ProviderTextAssembly(10_000);
            StringBuilder emitted = new StringBuilder();
            assembly.append(source.substring(0, split));
            assembly.emitStable(emitted::append);
            assembly.append(source.substring(split));
            assembly.emitStable(emitted::append);
            String terminal = assembly.finalText();
            assembly.emitTerminal(terminal, emitted::append);
            assertEquals(source, terminal, "partition " + split);
            assertEquals(source, emitted.toString(), "emitted partition " + split);
            assertFalse(assembly.normalized(terminal), "partition " + split);
        }
        ProviderTextAssembly characterAssembly = new ProviderTextAssembly(10_000);
        StringBuilder emitted = new StringBuilder();
        source.chars().forEach(value -> {
            characterAssembly.append(Character.toString((char) value));
            characterAssembly.emitStable(emitted::append);
        });
        String terminal = characterAssembly.finalText();
        characterAssembly.emitTerminal(terminal, emitted::append);
        assertEquals(source, terminal, "single-character chunks");
        assertEquals(source, emitted.toString(), "single-character emission");
        assertFalse(characterAssembly.normalized(terminal));
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
