package dev.talos.cli.ui.md;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T778: bundled nanorc definitions load from the classpath through JLine's
 * SyntaxHighlighter, color recognizable tokens, never alter the characters,
 * and degrade to plain for unknown languages.
 */
class NanorcHighlighterCatalogTest {

    private static final NanorcHighlighterCatalog CATALOG = NanorcHighlighterCatalog.shared();

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    @Test
    void bundledDefinitionsLoadFromTheClasspath() {
        // Assumption-verifying test for the classpath: URL form of
        // SyntaxHighlighter.build - if this fails, switch the catalog to a
        // Path-based loader.
        for (String lang : new String[]{"java", "python", "javascript", "json",
                "yaml", "bash", "diff", "xml", "html", "css"}) {
            assertTrue(CATALOG.highlighterFor(lang).isPresent(),
                    "bundled definition must load: " + lang);
        }
    }

    @Test
    void aliasesResolveToTheSameDefinitions() {
        for (String alias : new String[]{"py", "js", "ts", "sh", "shell", "yml", "patch", "htm"}) {
            assertTrue(CATALOG.highlighterFor(alias).isPresent(), "alias must resolve: " + alias);
        }
    }

    @Test
    void highlightingColorsTokensWithoutAlteringCharacters() {
        String[][] samples = {
                {"java", "public static final String NAME = \"talos\"; // comment"},
                {"python", "def compute(value):  # doubles"},
                {"json", "{\"key\": \"value\", \"count\": 42}"},
                {"diff", "+added line"},
        };
        for (String[] sample : samples) {
            AttributedString highlighted = CATALOG.highlight(sample[0], sample[1]);
            String ansi = highlighted.toAnsi();
            assertTrue(ansi.contains("\u001B["),
                    sample[0] + " sample should color at least one token: " + ansi);
            assertEquals(sample[1], stripAnsi(ansi),
                    "highlighting must never alter characters (" + sample[0] + ")");
        }
    }

    @Test
    void unknownLanguageDegradesToPlain() {
        assertNull(CATALOG.highlight("brainfuck", "+++---"));
        assertNull(CATALOG.highlight("", "text"));
        assertNull(CATALOG.highlight(null, "text"));
    }
}
