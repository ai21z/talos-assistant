package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void cssFileNameInCommentIsNotTreatedAsMissingClassSelector() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <main class="hero">Neon Arcadia</main>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                /*
                  styles.css
                  Generated stylesheet header.
                */
                .hero {
                  color: #ff2bd6;
                }
                """);
        Files.writeString(workspace.resolve("scripts.js"), "console.log('ready');\n");

        StaticWebSelectorAnalyzer.Facts facts = StaticWebSelectorAnalyzer.analyze(
                workspace.toAbsolutePath().normalize(),
                List.of("index.html", "styles.css", "scripts.js"),
                List.of());

        assertNotNull(facts);
        assertFalse(facts.selectorProblems().stream()
                        .anyMatch(problem -> problem.contains("`.css`")),
                        facts.selectorProblems().toString());
    }

    @Test
    void cssStateAndUtilityClassesDoNotRequireInitialHtmlClassMarkup() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show Teaser</button>
                    <p id="teaser-status"></p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                #teaser-status.visible { opacity: 1; }
                .hidden { display: none; }
                .missing-card { padding: 1rem; }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'Ready.';
                });
                """);

        StaticWebSelectorAnalyzer.Facts facts = StaticWebSelectorAnalyzer.analyze(
                workspace.toAbsolutePath().normalize(),
                List.of("index.html", "styles.css", "scripts.js"),
                List.of());

        assertNotNull(facts);
        assertFalse(facts.selectorProblems().stream().anyMatch(problem -> problem.contains("`.visible`")),
                facts.selectorProblems().toString());
        assertFalse(facts.selectorProblems().stream().anyMatch(problem -> problem.contains("`.hidden`")),
                facts.selectorProblems().toString());
        assertTrue(facts.selectorProblems().stream().anyMatch(problem -> problem.contains("`.missing-card`")),
                facts.selectorProblems().toString());
    }

    @Test
    void jsCreatedClassesSatisfyCssSelectorsWithoutInventingInitialHtmlClasses() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <main id="app">Retrocats</main>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                .hero { min-height: 100vh; }
                .featured { color: #ff66cc; }
                .stage-card { border: 1px solid #ff7a18; }
                .unused-card { padding: 1rem; }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                const hero = document.createElement('section');
                hero.className = 'hero';
                hero.className += ' featured';
                const card = document.createElement('div');
                card.setAttribute('class', 'stage-card active');
                document.getElementById('app').append(hero, card);
                """);

        StaticWebSelectorAnalyzer.Facts facts = StaticWebSelectorAnalyzer.analyze(
                workspace.toAbsolutePath().normalize(),
                List.of("index.html", "styles.css", "scripts.js"),
                List.of());

        assertNotNull(facts);
        assertTrue(facts.jsDynamicClasses().contains("hero"), facts.jsDynamicClasses().toString());
        assertTrue(facts.jsDynamicClasses().contains("featured"), facts.jsDynamicClasses().toString());
        assertTrue(facts.jsDynamicClasses().contains("stage-card"), facts.jsDynamicClasses().toString());
        assertFalse(facts.htmlClasses().contains("hero"), facts.htmlClasses().toString());
        assertFalse(facts.htmlClasses().contains("stage-card"), facts.htmlClasses().toString());
        assertFalse(facts.selectorProblems().stream().anyMatch(problem -> problem.contains("`.hero`")),
                facts.selectorProblems().toString());
        assertFalse(facts.selectorProblems().stream().anyMatch(problem -> problem.contains("`.stage-card`")),
                facts.selectorProblems().toString());
        assertTrue(facts.selectorProblems().stream().anyMatch(problem -> problem.contains("`.unused-card`")),
                facts.selectorProblems().toString());
    }
}
