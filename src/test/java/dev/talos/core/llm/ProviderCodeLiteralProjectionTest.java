package dev.talos.core.llm;

import dev.talos.core.tool.ToolProtocolText;
import dev.talos.core.util.Sanitize;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProviderCodeLiteralProjectionTest {
    private static final String FALLBACK = "existing presentation projection";
    private static final String BUTTON = "<button id=\"submit\">Submit</button>";
    private static final String READ =
            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"sample.txt\"}}";

    @Test
    void completeSyntheticMixedAnswerPreservesAllSourceExamples() {
        String source = "The handler selects `#send`, but the markup declares:\n\n"
                + "```html\n" + BUTTON + "\n```\n\n"
                + "The page loads `<script src=\"client.js\"></script>`. "
                + "Its output container opens with `<p id=\"status\">`, and the alternative "
                + "entry point starts with `<script src=\"other.js\">`.\n"
                + "Use the matching selector:\n"
                + "```javascript\nconst button = document.querySelector('#submit');\n```\n"
                + "The second script is a separate file. No edits or tests were performed.";
        assertNotEquals(source, Sanitize.sanitizeForOutputPreservingToolCalls(source));
        assertPreserved(source);
    }

    @Test
    void inlineCodeHasNoElementAttributeOrHtmlValidityWhitelist() {
        for (String payload : List.of(
                "<script src='client.js'></script>",
                "<script src='other.js'>",
                "<p id=\"status\">",
                "</custom-widget>",
                "<custom-widget data-state='ready'>x</custom-widget>",
                "<button id='x'onclick='run()'>Run</textarea>",
                "<iframe srcdoc='<script>run()</script>'></iframe>",
                "<svg onload='run()'><foreignObject>x</foreignObject></svg>",
                "<form action='javascript:run()'><input autofocus></form>",
                "<button style='display:none' formaction='javascript:run()'>Run</button>",
                "<button>&lt;script&gt; &amp; text</button>",
                "<!-- arbitrary comment -->",
                "onclick=\"run()\"",
                "a < b && c > d")) {
            assertPreserved("Example: `" + payload + "`.");
        }
    }

    @Test
    void codeCanQuoteSeparateOpeningAndClosingTags() {
        assertPreserved("Start with `<button>`, then close it with `</button>`.");
        assertPreserved("Example: `<button>unfinished HTML`.");
    }

    @Test
    void fenceLanguageDoesNotDetermineWhetherPayloadIsCode() {
        for (String info : List.of("", "html", "HTML", "js", "javascript", "jsx", "tsx",
                "xml", "svg", "python", "text", "json", "css", "c++", "unknown-language",
                "html extra", "  custom {#example .numbered}  ")) {
            assertPreserved("```" + info + "\n" + BUTTON + "\n```\n");
            assertPreserved("~~~" + info + "\n" + BUTTON + "\n~~~\n");
        }
    }

    @Test
    void multilineCodeKeepsNestedMarkupAndUnmatchedInnerDelimiters() {
        String payload = "<section>\n  <button onclick=\"run()\">Run</button>\n"
                + "  <script>const html = `<input>`;</script>\n</section>";
        assertPreserved("```html\n" + payload + "\n```\nDone.");
        assertPreserved("````text\n```html\n" + BUTTON + "\n```\n````\n");
        assertPreserved("~~~~text\n~~~\n" + BUTTON + "\n~~~\n~~~~\n");
        assertPreserved("```html\n" + BUTTON + "\n``` not a closer\nstill code\n```\n");
    }

    @Test
    void longerInlineDelimitersAllowLiteralBackticks() {
        assertPreserved("Example: ``<button title='`quoted`'>Run</button>``.");
        assertPreserved("Example: ```<script>`x` + ``y``</script>```.");
        assertPreserved("Example: ``<button>```x```</button>``.");
        String delimiter = "`".repeat(32);
        assertPreserved("Example: " + delimiter + BUTTON + delimiter + ".");
    }

    @Test
    void closingFenceCanBeLongerAndHaveHorizontalPadding() {
        for (String marker : List.of("`", "~")) {
            for (int length : List.of(3, 4, 32)) {
                String opening = marker.repeat(length);
                String closing = marker.repeat(Math.min(32, length + 1));
                for (int indent = 0; indent <= 3; indent++) {
                    assertPreserved(opening + "text\n" + BUTTON + "\n"
                            + " ".repeat(indent) + closing + " \t\nDone.");
                }
            }
        }
    }

    @Test
    void ordinaryProseAndEmptyRegionsDoNotInterfereWithLaterCode() {
        assertPreserved("2 < 10 and 10 > 2. `value`.\n```text\n```\n"
                + "~~~\n~~~\nExample: `" + BUTTON + "`.");
        assertPreserved("- First: `" + BUTTON + "`.\n- Second: `<script src='next.js'>`.");
        assertPreserved("> An inline example: `" + BUTTON + "`.");
    }

    @Test
    void backslashesInsideCodeArePayloadAndOpeningEscapeParityIsRespected() {
        assertPreserved("Example: ``<button>\\` literal</button>``.");
        assertPreserved("Example: `<button>path\\</button>\\`.");
        assertPreserved("Example: \\\\`" + BUTTON + "`.");
        assertFallback("Example: \\`" + BUTTON + "`.");
        assertFallback("Example: \\\\\\`" + BUTTON + "`.");
        assertFallback("\\`plain` and `" + BUTTON + "`.");
    }

    @Test
    void unfinishedMismatchedAndNonLineDelimitedBoundariesUseOriginalFallback() {
        for (String source : List.of(
                "Example: `" + BUTTON,
                "Example: ``" + BUTTON + "`.",
                "Example: `" + BUTTON + "\nnext line`.",
                "```html\n" + BUTTON,
                "```html\n" + BUTTON + "\n``",
                "````html\n" + BUTTON + "\n```",
                "```html\n" + BUTTON + "\n~~~",
                "~~~html\n" + BUTTON + "\n```",
                "```html\n" + BUTTON + "\n``` trailing",
                "prefix ```html\n" + BUTTON + "\n```",
                "```html `invalid`\n" + BUTTON + "\n```",
                "```html\n" + BUTTON + "\n    ```",
                "```html\r\n" + BUTTON + "\r\n```",
                "```html\n" + BUTTON + "\n```\n`unfinished")) {
            assertFallback(source);
        }
    }

    @Test
    void indentedAndContainerFencesAreOutsideTheBoundedGrammar() {
        for (String marker : List.of("```", "~~~")) {
            for (int indent = 1; indent <= 3; indent++) {
                assertFallback(" ".repeat(indent) + marker + "html\n" + BUTTON + "\n"
                        + " ".repeat(indent) + marker + "\n");
                assertFallback("- item\n" + " ".repeat(indent) + marker + "html\n"
                        + "<script>run()</script>\n" + marker + "\n");
            }
            assertFallback("> " + marker + "html\n> " + BUTTON + "\n> " + marker + "\n");
        }
    }

    @Test
    void markupAndNormalizationOutsideCodeCannotBeLaundered() {
        for (String source : List.of(
                "<button>outside `" + BUTTON + "` tail</button>",
                "<button>outside `</button>`.",
                "Example: `<button>` outside</button>",
                "Example: `" + BUTTON + "`.<script>run()</script>",
                "Example: `" + BUTTON + "`. onclick=\"run()\"",
                "<!-- raw HTML block\n`" + BUTTON + "`.\n-->",
                "<div>\nExample: `" + BUTTON + "`.\n</div>",
                "Example: `" + BUTTON + "`. \\u003cdiv\\u003e",
                "Example: `" + BUTTON + "`.\n<think>private</think>")) {
            assertFallback(source);
        }
    }

    @Test
    void hiddenThinkContentIsCheckedBeforeMaskingAcrossEveryBoundary() {
        for (String hidden : List.of(
                "<think>private</think>",
                "< THINK >private< / think >",
                "<think>",
                "</think>",
                "\\u003cthink\\u003eprivate\\u003c/think\\u003e",
                "\\u003cthink>private</think\\u003e",
                "\\u003cdiv\\u003e")) {
            assertFallback("Example: `<button>" + hidden + "</button>`.");
            assertFallback("```html\n<button>" + hidden + "</button>\n```\n");
        }
        assertFallback("<think>outside `" + BUTTON + "`.</think>");
        assertFallback("Example: `<button><think>` outside `</think></button>`.");
    }

    @Test
    void c0C1AndAnsiControlsAreNeverMaskedByCode() {
        for (int code = 0; code <= 0x9f; code++) {
            char value = (char) code;
            if (!Character.isISOControl(value) || value == '\t' || value == '\n') continue;
            assertFallback("Example: `<button>" + value + "Run</button>`.");
            assertFallback("```html\n<button>" + value + "Run</button>\n```\n");
        }
        assertFallback("Example: `<button>\u001b[31mRun</button>`.");
        assertPreserved("```html\n\t" + BUTTON + "\n\n```\n");
    }

    @Test
    void realAndMalformedToolProtocolsKeepExistingSelectionAndProjection() {
        for (String protocol : List.of(
                READ,
                "{\"name\":\"talos.read_file\",\"arguments\":",
                "{\"name\":\"talos.future_tool\",\"arguments\":{}}",
                "{\"name\":\"read_file\",\"arguments\":{\"path\":\"sample.txt\"}}")) {
            String source = "Example: `" + BUTTON + "`.\n```json\n" + protocol + "\n```\n";
            var selection = ToolProtocolText.selectExecutableJsonProposals(source);
            assertTrue(!selection.recognizedCandidates().isEmpty()
                    || !selection.suppressionOnlyEnvelopes().isEmpty(), source);
            assertFallback(source);
        }
        for (String protocol : List.of(
                "<tool_call>" + READ + "</tool_call>",
                "<function_call>" + READ + "</function_call>")) {
            assertFallback("Example: `" + BUTTON + " " + protocol + "`.");
            assertFallback("```html\n" + BUTTON + "\n" + protocol + "\n```\n");
        }
        assertFallback("Example: `" + BUTTON
                + " {\"name\":\"talos.read_file\",\"arguments\":`.");
        String source = "Example: `" + BUTTON + "`.\n```json\n" + READ + "\n```\n";
        String fallback = Sanitize.sanitizeForOutputPreservingToolCalls(source);
        assertEquals(fallback, ProviderInlineHtmlLiteralProjection.preserveIfInert(source, fallback));
        assertEquals(READ, ToolProtocolText.selectExecutableJsonProposals(fallback)
                .executionOrder().getFirst().rootFrom(fallback));
        assertFallback("````text\n" + BUTTON + "\n```json\n" + READ + "\n```\n````\n");
    }

    @Test
    void inertToolNamedExamplesDoNotAcquireProtocolStatus() {
        // The canonical grammar already excludes these ordinary code contexts.
        // A tool name in inert source is not itself execution authority, and
        // preserving the complete source must not change that classification.
        for (String protocol : List.of(READ,
                "{\"name\":\"talos.future_tool\",\"arguments\":{}}",
                "{\"name\":\"read_file\",\"arguments\":{\"path\":\"sample.txt\"}}")) {
            for (String source : List.of(
                    "Example: `" + BUTTON + " " + protocol + "`.",
                    "```html\n" + BUTTON + "\n" + protocol + "\n```\n")) {
                var before = ToolProtocolText.selectExecutableJsonProposals(source);
                assertTrue(before.recognizedCandidates().isEmpty(), source);
                assertTrue(before.suppressionOnlyEnvelopes().isEmpty(), source);
                assertEquals(source, ToolProtocolText.stripToolCallsPreservingLayout(source));
                assertPreserved(source);
                String result = ProviderInlineHtmlLiteralProjection.preserveIfInert(source, FALLBACK);
                assertEquals(before, ToolProtocolText.selectExecutableJsonProposals(result));
                assertEquals(result, ToolProtocolText.stripToolCallsPreservingLayout(result));
            }
        }
        assertPreserved("```text\n" + BUTTON
                + "\n{\"name\":\"talos.read_file\",\"arguments\":\n```\n");
    }

    @Test
    void foreignNamedJsonIsStillOrdinaryCode() {
        assertPreserved("```json\n{\"name\":\"external.example\",\"html\":\""
                + "<button>Run</button>\"}\n```\n");
    }

    @Test
    void exactPayloadLimitCountsPaddingButNotFinalFenceLineBreak() {
        for (int length : List.of(4_096, 4_097)) {
            String payload = "<button>" + "x".repeat(length - 17) + "</button>";
            assertEquals(length, payload.length());
            String inline = "Example: `" + payload + "`.";
            String fenced = "```html\n" + payload + "\n```\n";
            if (length == 4_096) {
                assertPreserved(inline);
                assertPreserved(fenced);
                assertFallback("```html\n " + payload + "\n```\n");
                assertFallback("```html\n" + payload + "\n\n```\n");
            } else {
                assertFallback(inline);
                assertFallback(fenced);
            }
        }
    }

    @Test
    void regionCountLimitIncludesEveryCodeRegion() {
        String accepted = "`x` ".repeat(255) + "`" + BUTTON + "`.";
        assertPreserved(accepted);
        assertFallback("`x` " + accepted);
    }

    @Test
    void sourceDelimiterAndInfoLimitsHaveExactBoundaries() {
        String example = "Example: `" + BUTTON + "`.";
        String accepted = "p".repeat(65_536 - example.length()) + example;
        assertPreserved(accepted);
        assertFallback("p" + accepted);
        assertPreserved("```" + "x".repeat(256) + "\n" + BUTTON + "\n```\n");
        assertFallback("```" + "x".repeat(257) + "\n" + BUTTON + "\n```\n");
        for (String marker : List.of("`", "~")) {
            assertPreserved(marker.repeat(32) + "text\n" + BUTTON + "\n" + marker.repeat(32) + "\n");
            assertFallback(marker.repeat(33) + "text\n" + BUTTON + "\n" + marker.repeat(33) + "\n");
            assertFallback(marker.repeat(3) + "text\n" + BUTTON + "\n" + marker.repeat(33) + "\n");
        }
        assertFallback("Example: " + "`".repeat(33) + BUTTON + "`".repeat(33) + ".");
    }

    @Test
    void unchangedProjectionAndAbsentSourceRetainCallerFallbackIdentity() {
        assertSame(FALLBACK, ProviderInlineHtmlLiteralProjection.preserveIfInert(FALLBACK, FALLBACK));
        assertSame(FALLBACK, ProviderInlineHtmlLiteralProjection.preserveIfInert(null, FALLBACK));
        assertFallback("Ordinary prose without a code region.");
        assertFalse(Sanitize.sanitizeForOutput("Example: `" + BUTTON + "`.").contains("<button"),
                "the shared sanitizer remains unchanged");
    }

    private static void assertPreserved(String source) {
        assertEquals(source, ProviderInlineHtmlLiteralProjection.preserveIfInert(source, FALLBACK), source);
        assertEquals(source, ProviderInlineHtmlLiteralProjection.preserveIfInert(source,
                Sanitize.sanitizeForOutputPreservingToolCalls(source)), source);
    }

    private static void assertFallback(String source) {
        assertSame(FALLBACK, ProviderInlineHtmlLiteralProjection.preserveIfInert(source, FALLBACK), source);
        String existing = Sanitize.sanitizeForOutputPreservingToolCalls(source);
        assertEquals(existing, ProviderInlineHtmlLiteralProjection.preserveIfInert(source, existing), source);
    }
}
