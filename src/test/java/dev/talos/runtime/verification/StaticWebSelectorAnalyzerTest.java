package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebSelectorAnalyzerTest {

    @TempDir
    Path workspace;

    @Test
    void analyzerOwnsSelectorLinkageAndButtonDiagnostics() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button class="run-action" type="button">Run action</button>
                    <p id="result">Waiting.</p>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".run-action { color: red; }\n");
        Files.writeString(workspace.resolve("script.js"), """
                const button = document.querySelector('.missing-action');
                const result = document.querySelector('#result');
                if (button && result) {
                  button.addEventListener('click', () => {
                    result.textC;
                  });
                }
                """);

        StaticWebSelectorAnalyzer.Facts facts = StaticWebSelectorAnalyzer.analyze(
                workspace.toAbsolutePath().normalize(),
                List.of("index.html", "styles.css", "script.js"),
                List.of());

        assertNotNull(facts);
        assertEquals("index.html", facts.htmlFile());
        assertEquals("styles.css", facts.cssFile());
        assertEquals("script.js", facts.jsFile());
        assertTrue(facts.linkageProblems().isEmpty(), facts.linkageProblems().toString());
        assertTrue(facts.selectorProblems().contains(
                "JavaScript references missing class selectors: `.missing-action`"),
                facts.selectorProblems().toString());
        assertTrue(facts.genericButtonResultDiagnosticProblems().stream()
                        .anyMatch(p -> p.contains("button click handler references `#result`")),
                facts.genericButtonResultDiagnosticProblems().toString());
        assertTrue(facts.renderInspection().contains("Observed in HTML:"), facts.renderInspection());
    }
}
