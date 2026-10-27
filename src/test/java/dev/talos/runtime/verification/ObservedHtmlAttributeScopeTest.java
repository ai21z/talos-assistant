package dev.talos.runtime.verification;

import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedHtmlAttributeScopeTest {
    private static final String REQUEST = "Explain the selector mismatch without changing files.";

    @Test
    void prefixedAttributeNamesCannotSupplyReplacementSelectors() {
        for (String attributes : List.of(
                "data-id='replacement' data-class='replacement'",
                "DATA-ID='replacement' DATA-CLASS='replacement'",
                "aria-id='replacement' aria-class='replacement'",
                "example:id='replacement' example:class='replacement'")) {
            assertLimitedForBothSelectors("<div " + attributes + "></div>");
        }
    }

    @Test
    void commentedMarkupCannotSupplyReplacementSelectors() {
        assertLimitedForBothSelectors("<!-- <div id='replacement' class='replacement'></div> -->");
        assertLimitedForBothSelectors("<!-- id='replacement' class='replacement' -->");
    }

    @Test
    void rawTextBodiesCannotSupplyReplacementSelectors() {
        for (String tag : List.of("title", "textarea", "style", "xmp", "iframe", "noembed", "noframes")) {
            assertLimitedForBothSelectors("<" + tag + "><span id='replacement' class='replacement'>"
                    + "Example</span></" + tag + ">");
        }
    }

    @Test
    void quotedAttributeLiteralsCannotSupplyReplacementSelectors() {
        for (String markup : List.of(
                "<div title=\"id='replacement' class='replacement'\"></div>",
                "<div data-example=\"<span id='replacement' class='replacement'></span>\"></div>",
                "<div title='id=\"replacement\" class=\"replacement\"'></div>")) {
            assertLimitedForBothSelectors(markup);
        }
    }

    @Test
    void exactQuotedAttributesRemainUsableAlongsideUnrelatedAttributes() {
        for (String markup : List.of(
                "<div id='replacement' class='replacement extra' data-id='other' data-class='other'></div>",
                "<DIV ID=\"replacement\" cLaSs=\"extra replacement\" DATA-ID=\"other\" hidden></DIV>",
                "<div title='Ordinary quoted text' hidden id='replacement' class='replacement'></div>")) {
            for (String prefix : List.of("#", ".")) {
                String answer = answer(prefix);
                var result = assess(markup, prefix, answer);
                assertTrue(result.assessment().preserved(), markup + " " + prefix);
                assertEquals(answer, result.answer());
            }
        }
    }

    @Test
    void unsupportedUnquotedValuedAttributesAreExplicitlyLimited() {
        for (String markup : List.of(
                "<div id=replacement class='replacement'></div>",
                "<div id='replacement' class=replacement></div>",
                "<div id='replacement' class='replacement' title=ordinary></div>")) {
            for (String prefix : List.of("#", ".")) {
                var result = assess(markup, prefix, answer(prefix));
                assertTrue(result.assessment().limited(), markup + " " + prefix);
                assertEquals(ReadOnlyWebAnswerAssessment.Reason.SOURCE_RELATION_UNSUPPORTED,
                        result.assessment().reason());
            }
        }
    }

    private static void assertLimitedForBothSelectors(String markup) {
        for (String prefix : List.of("#", ".")) {
            String answer = answer(prefix);
            var result = assess(markup, prefix, answer);
            assertTrue(result.assessment().limited(), markup + " " + prefix);
            assertFalse(result.assessment().preserved());
            assertFalse(answer.equals(result.answer()));
            assertTrue(result.answer().startsWith(ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX));
        }
    }

    private static String answer(String prefix) {
        return "`" + prefix + "missing` is missing from index.html. The correct selector is `"
                + prefix + "replacement`.";
    }

    private static ReadOnlyWebDiagnosticResult assess(String markup, String prefix, String answer) {
        String html = "<!doctype html><html><head><title>Page</title></head><body>" + markup
                + "<script src='script.js'></script></body></html>\n";
        String script = "document.querySelector('" + prefix + "missing');\n";
        return StaticTaskVerifier.assessReadOnlyWebDiagnostics(List.of(
                source("index.html", html, 1), source("script.js", script, 2)), REQUEST, answer);
    }

    private static Source source(String path, String text, long ordinal) {
        return new Source(path, text, "attribute-scope-observation", ordinal,
                "a".repeat(64), SOURCE_PROJECTION_VERSION);
    }
}
