package dev.talos.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A path mask covers the path and nothing else. Punctuation and markdown around a path belong to
 * the sentence and stay visible, and a path in backticks, parentheses or quotes is still masked.
 */
final class RedactorPathPunctuationTest {

    private final Redactor redactor = new Redactor();

    @Test
    void theLiveLocationSentenceKeepsItsClosingBacktickAndFullStop() {
        assertEquals("We are in the workspace located at `[path]`. How can I assist you with this workspace?",
                redactor.redactBlock("We are in the workspace located at `C:/Users/arisz/Desktop/testai/test001`. "
                        + "How can I assist you with this workspace?"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Saved to C:\\Users\\a\\notes.txt, then closed.|Saved to [path], then closed.",
            "(see C:/a/b/c)|(see [path])",
            "Is it C:/a/b?|Is it [path]?",
            "Look in C:/a/b: it is there.|Look in [path]: it is there.",
            "Done; C:/a/b; next|Done; [path]; next",
            "Run it from /usr/local/bin.|Run it from [path].",
            "Path /home/u/project/src: done|Path [path]: done",
            "Is it /home/u/x?|Is it [path]?",
            "[C:/a/b]|[[path]]",
            "It is C:/a/b!|It is [path]!",
    })
    void trailingPunctuationStaysOutsideTheMask(String input, String expected) {
        assertEquals(expected, redactor.redactBlock(input));
    }

    /**
     * Quoted root-relative web routes are not filesystem paths and stay readable, so an edit to a page
     * that links its stylesheet still has a redaction-stable detail. A quoted or backticked POSIX
     * filesystem path is therefore not masked either, which is recorded as an open trade-off.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "<link href=\"/css/site.css\">|<link href=\"/css/site.css\">",
            "<script src='/js/app.js'></script>|<script src='/js/app.js'></script>",
            "[docs](/docs/guide.md)|[docs](/docs/guide.md)",
    })
    void quotedRootRelativeWebRoutesStayReadable(String input, String expected) {
        assertEquals(expected, redactor.redactBlock(input));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Open C:/a/b.txt now|Open [path] now",
            "Open C:/a/b.txt.|Open [path].",
            "Open C:/a/v1.2/file now|Open [path] now",
            "Open /home/u/a.tar.gz now|Open [path] now",
            "Open C:/a/b-c_d now|Open [path] now",
    })
    void aMaskedPathLeaksNoSegmentOrExtension(String input, String expected) {
        assertEquals(expected, redactor.redactBlock(input));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "type /help to start|type /help to start",
            "see https://example.com/a/b for docs|see https://example.com/a/b for docs",
            "use a/b/c as a relative path|use a/b/c as a relative path",
    })
    void textThatIsNotAnAbsolutePathIsUnchanged(String input, String expected) {
        assertEquals(expected, redactor.redactBlock(input));
    }
}
