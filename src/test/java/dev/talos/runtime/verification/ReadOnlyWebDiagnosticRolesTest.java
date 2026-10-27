package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Role-bound preservation witnesses, not a JavaScript interpreter, semantic
 * grader, model evaluation or browser test. Facts represent inspected files.
 */
class ReadOnlyWebDiagnosticRolesTest {
    private static final List<String> READS = List.of("README.md", "index.html", "script.js", "scripts.js");
    private static final String BASE = "`#calc` does not exist in index.html. Use `#calculate`.\n\n";
    private static final String HTML = """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"><title>BMI fixture</title></head>
            <body>
            <label>Weight (kg)<input id="weight" value="72"></label>
            <label>Height (m)<input id="height" value="1.8"></label>
            <button id="calculate">Calculate</button>
            <p id="result"></p>
            <script src="script.js"></script>
            </body>
            </html>
            """;
    private static final String JS = """
            const button = document.querySelector('#calc');
            button.addEventListener('click', () => {
              const weight = Number(document.querySelector('#weight').value);
              const height = Number(document.querySelector('#height').value);
              document.querySelector('#result').textContent = (weight / (height * height)).toFixed(1);
            });
            """;

    @Test
    void completeRecordedAnswerIsPreservedWithoutDeletingOrRephrasingItsClaims() throws Exception {
        String answer = recordedAnswer();
        assertEquals(1906, answer.length());
        assertEquals("ff0c723ddafbb7161d1626852a6442309f608825ff4330cb472949a91bf101d6",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(answer.getBytes(StandardCharsets.UTF_8))));
        assertTrue(preserve(answer), "Exact raw provider answer from the synthetic four-file diagnosis");
    }

    @Test
    void completeSecondRecordedAnswerRetainsItsExplicitTableOwnerAcrossSentenceBoundaries() throws Exception {
        String answer = recordedAnswer("recorded-table-owner-guard-answer.txt");
        assertEquals(1312, answer.length());
        assertEquals(1338, answer.getBytes(StandardCharsets.UTF_8).length);
        assertEquals("d260ded2fd6a40c3332ec816f367a239e397d8e35e83bd856547db3e9be7abc6",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(answer.getBytes(StandardCharsets.UTF_8))));
        assertTrue(preserve(answer), "Exact second raw provider answer, including the three-column row");
    }

    @Test
    void explicitTableOwnerBindsTheCurrentQueryAfterAnEarlierCellEndsASentence() {
        for (String owner : List.of("script.js", "`script.js`", "**script.js**")) {
            for (String query : List.of(
                    "It looks for `id=\"calc\"` (`document.querySelector('#calc')`).",
                    "The current expression is `document.querySelector('#calc')`.")) {
                String answer = "| File | Description | Issue |\n|------|-------------|-------|\n"
                        + "| " + owner + " | Adds the listener and computes BMI. | " + query + " |\n\n" + BASE;
                assertTrue(preserve(answer), answer);
            }
        }
    }

    @Test
    void tableOwnerCannotBeBorrowedFromAnotherRowOrANonOwnerCell() {
        String query = "It looks for `id=\"calc\"` (`document.querySelector('#calc')`).";
        String header = "| File | Description | Issue |\n|------|-------------|-------|\n";
        for (String row : List.of(
                "| `scripts.js` | Contains a sentinel. | " + query + " |",
                "| `unread.js` | Contains unrelated code. | " + query + " |",
                "| `scripts.js` | Unlike `script.js`, this file is unrelated. | " + query + " |",
                "| `index.html` | It loads `script.js`. | " + query + " |",
                "| Current source | `script.js` supplies the active code. | " + query + " |",
                "| `script.js` | Supplies the active code. | No query quoted here. |\n"
                        + "| `scripts.js` | Another source. | " + query + " |")) {
            assertFalse(preserve(header + row + "\n\n" + BASE), row);
        }
    }

    @Test
    void explicitTableOwnerCannotOverrideContradictionOrRepairRolesInsideTheRow() {
        String header = "| File | Description | Issue |\n|------|-------------|-------|\n";
        for (String row : List.of(
                "| `script.js` | Contains the active query. | The HTML has `id=\"calc\"` (`document.querySelector('#calc')`). |",
                "| `script.js` | Contains the active query. | `scripts.js` looks for `id=\"calc\"` (`document.querySelector('#calc')`). |",
                "| `script.js` | Contains the active query. | It never uses `id=\"calc\"` (`document.querySelector('#calc')`). |",
                "| `script.js` | Recommended replacement. | Use `document.querySelector('#calc')`. |",
                "| `script.js` | Contains the active query. | The correct selector is `#calc`. |",
                "| `script.js` | Contains the active query. | It looks for `id=\"calc\"` (`document.querySelector('#ca**lc')`). |")) {
            assertFalse(preserve(header + row + "\n\n" + BASE), row);
        }
        assertFalse(preserve(BASE + "### Proposed replacement\n" + header
                + "| `script.js` | Contains the active query. | `document.querySelector('#calc')` |"));
    }

    @Test
    void tableOwnedAnswerDoesNotAuthorizeFabricatedExecutionOrWrongSelectors() throws IOException {
        String answer = recordedAnswer("recorded-table-owner-guard-answer.txt");
        for (String claim : List.of(
                "I ran the browser tests.", "I clicked the Calculate button.",
                "`#calc` exists.", "Use `#calc` instead.",
                "The correct selector is `#cal**culate`.")) {
            assertFalse(preserve(answer + "\n\n" + claim), claim);
        }
    }

    @Test
    void recordedAnswerStillRejectsContradictionsAndFabricatedExecution() throws IOException {
        String answer = recordedAnswer();
        for (String claim : List.of(
                "`#calc` exists in index.html.",
                "The HTML has id `calc`.",
                "`#calculate` is missing.",
                "Use `#calc` to fix the button.",
                "The correct selector is `#Calculate`.",
                "I tested this in a browser.",
                "I clicked the button and saw the result update.",
                "All tests passed.")) {
            assertFalse(preserve(answer + "\n\n" + claim), claim);
        }
    }

    @Test
    void proposedOrContradictoryColumnRolesCannotBorrowCurrentSourceAttribution() {
        for (String header : List.of("Correct selector", "Recommended replacement", "Proposed source",
                "Updated query", "Does not use", "Never references", "HTML elements", "HTML has")) {
            assertFalse(preserve("| File | Description | " + header + " |\n|------|-------------|------|\n"
                    + "| script.js | Current source. | `document.querySelector('#calc')` |\n\n" + BASE), header);
        }
        assertFalse(preserve("script.js never uses the following:\n\n"
                + "| File | Description | Current query |\n|------|-------------|------|\n"
                + "| script.js | Source summary. | `document.querySelector('#calc')` |\n\n" + BASE));
    }

    @Test
    void tableOwnerAndFramingAreExactRatherThanFilenameSearches() {
        String header = "| File | Description | Issue |\n|------|-------------|-------|\n";
        String query = "`id=\"calc\"` (`document.querySelector('#calc')`)";
        for (String row : List.of(
                "| Script.js | Current source. | " + query + " |",
                "| script.js and scripts.js | Current source. | " + query + " |",
                "| `script.js | Current source. | " + query + " |",
                "| script.js | Current source. | " + query,
                "| script.js | Current \\| source. | " + query + " |",
                "| script.js | Current source.\n| scripts.js | " + query + " |")) {
            assertFalse(preserve(header + row + "\n\n" + BASE), row);
        }
    }

    @Test
    void sourceRolesAcceptLocalQueryAndAttributeReferencesAcrossProseListsAndTables() {
        for (String source : List.of(
                "`script.js` tries to grab an element with `id=\"calc\"` (`document.querySelector('#calc')`).",
                "In `script.js`, the current lookup is `id=\"calc\"` (`document.querySelector('#calc')`).",
                "- `script.js` points at `id=\"calc\"` (`document.querySelector('#calc')`).",
                "| File | Current expression |\n|------|--------------------|\n"
                        + "| `script.js` | `id=\"calc\"` (`document.querySelector('#calc')`) |",
                "| File | Description |\n|------|-------------|\n"
                        + "| **script.js** | Attaches a click listener to `document.querySelector('#calc')`. |")) {
            assertTrue(preserve(source + "\n\n" + BASE), source);
        }
    }

    @Test
    void sourceBindingDoesNotLeakAcrossNeighboringTableRowsOrListItems() {
        for (String source : List.of(
                "| File | Current expression |\n|------|--------------------|\n"
                        + "| `script.js` | The active calculator source. |\n"
                        + "| `scripts.js` | `id=\"calc\"` (`document.querySelector('#calc')`) |",
                "- `script.js` is the active source.\n"
                        + "- `scripts.js` looks up `id=\"calc\"` (`document.querySelector('#calc')`).",
                "`scripts.js` looks up `id=\"calc\"` (`document.querySelector('#calc')`).",
                "`script.js` contains a query. The HTML has `id=\"calc\"` (`document.querySelector('#calc')`).",
                "`script.js` never uses `id=\"calc\"` (`document.querySelector('#calc')`).")) {
            assertFalse(preserve(source + "\n\n" + BASE), source);
        }
    }

    @Test
    void unreadSourceAndAmbiguousUnboundQueryCannotBorrowTheActiveSource() {
        for (String source : List.of(
                "`unread.js` uses `id=\"calc\"` (`document.querySelector('#calc')`).",
                "The script has `id=\"calc\"` (`document.querySelector('#calc')`).",
                "`document.querySelector('#calc')` refers to `id=\"calc\"`.")) {
            assertFalse(preserve(source + "\n\n" + BASE), source);
        }
        assertFalse(ReadOnlyWebDiagnosticAnswer.canPreserve(
                "`script.js` uses `id=\"calc\"` (`document.querySelector('#calc')`). " + BASE,
                List.of("index.html", "scripts.js"), facts(JS), facts(JS).selectorDiagnostics()));
    }

    @Test
    void observedBrokenQueryCannotBecomeARecommendedReplacementInNewSourceRoles() {
        for (String source : List.of(
                "### Correct script.js\n`script.js` looks up `id=\"calc\"` (`document.querySelector('#calc')`).",
                "Use this expression:\n- `script.js` looks up `id=\"calc\"` (`document.querySelector('#calc')`).",
                "| File | Recommended replacement |\n|------|-------------------------|\n"
                        + "| `script.js` | Use `document.querySelector('#calc')`. |",
                "`script.js` should use `id=\"calc\"` (`document.querySelector('#calc')`).")) {
            assertFalse(preserve(BASE + source), source);
        }
    }

    @Test
    void observedMemberCallsAreCodeRatherThanCssWithoutAMethodNameWhitelist() {
        for (String method : List.of("toFixed(1)", "toPrecision(3)", "trim()", "padStart(4, '0')")) {
            String js = JS.replace("toFixed(1)", method);
            assertTrue(preserve(BASE + "The expression in script.js uses `." + method + "`.", js), method);
            assertTrue(preserve(BASE + "The expression in script.js uses ``." + method + "``.", js), method);
        }
    }

    @Test
    void memberCallExemptionDoesNotTreatUnknownOrMalformedCodeAsObserved() {
        for (String claim : List.of(
                "The expression in script.js uses `.notObserved(1)`.",
                "The expression in script.js uses `.toFixed`.",
                "The expression in script.js uses .toFixed(1).",
                "The expression in script.js uses `.toFixed(1).",
                "The expression in script.js uses `.toFixed(1), .invented`.",
                "The expression in script.js uses `.toFixed(1); .invented()`.",
                "The expression in script.js uses `.toFixed(1)\n.invented`.",
                "The expression in script.js uses `.toFixed(1)``.")) {
            assertFalse(preserve(BASE + claim), claim);
        }
    }

    @Test
    void selectorRoleStillWinsWhenTheTokenAlsoSpellsAnObservedMethod() {
        for (String claim : List.of(
                "The correct selector is `.toFixed`.",
                "The correct selector is `.toFixed(1)`.",
                "Use `.toFixed(1)` to select the button.",
                "Use `document.querySelector('.toFixed')`.",
                "Use `document.querySelector('.toFixed(1)')`.",
                "Current script.js: `document.querySelector('.toFixed')`.",
                "### Correct selector\n`.toFixed(1)`",
                "Use this selector:\n- `.toFixed(1)`",
                "```css\n.toFixed(1) { color: red; }\n```")) {
            assertFalse(preserve(BASE + claim), claim);
        }
    }

    @Test
    void methodExemptionCannotMaskRealMissingClassesInInspectedJavaScript() {
        var facts = new StaticWebSelectorAnalyzer.Facts(
                "index.html", "", "script.js", Set.of("real-button"), Set.of(), List.of(),
                Set.of(), Set.of(), Set.of(), Set.of("toFixed"), Set.of(), Set.of(),
                Set.of(), Set.of("script.js"), List.of(), List.of("script.js"),
                "<button class=\"real-button\">Run</button><script src=\"script.js\"></script>", "",
                "document.querySelector('.toFixed'); const display = (22.22).toFixed(1);", Set.of("index.html", "script.js"));
        assertFalse(ReadOnlyWebDiagnosticAnswer.canPreserve(
                "The expression in script.js uses `.toFixed(1)`. Use `.real-button`.",
                READS, facts, facts.selectorDiagnostics()), "A method mention is not acknowledgement of the missing selector");
        assertTrue(ReadOnlyWebDiagnosticAnswer.canPreserve(
                "`.toFixed` does not exist in index.html. Use `.real-button`. "
                        + "The expression in script.js uses `.toFixed(1)`.",
                READS, facts, facts.selectorDiagnostics()));
    }

    @Test
    void wrongSelectorComparisonIsDirectionalAndCannotLicenseAnotherRecommendation() {
        assertTrue(preserve("**Failure**: wrong button selector (`#calc` vs `#calculate`).\n\n" + BASE));
        for (String comparison : List.of(
                "wrong button selector (`#calculate` vs `#calc`)",
                "wrong button selector (`#calc` vs `#calc`)",
                "wrong button selector (`#calc` vs `#Calculate`)",
                "wrong button selector (`#calc` vs `#invented`)",
                "correct button selector (`#calc` vs `#calculate`)",
                "not wrong button selector (`#calc` vs `#calculate`)",
                "wrong button selector (`#calc` vs `#calculate` or `#calc`)",
                "wrong button selector (`#calc` vs `#calculate`). Use `#calc` instead.")) {
            assertFalse(preserve(BASE + comparison), comparison);
        }
    }

    @Test
    void sourceRolesAndCodeLiteralsDoNotExcuseExecutionClaims() {
        String source = "`script.js` looks up `id=\"calc\"` (`document.querySelector('#calc')`). "
                + "The formatter uses `.toFixed(1)`. ";
        for (String claim : List.of(
                "I tested the page.", "I clicked the button.", "I observed the result update.",
                "Tests were run.", "No tests were run, but I verified the page in Chrome.",
                "`I tested the page`", "```text\nAll tests passed.\n```")) {
            assertFalse(preserve(BASE + source + claim), claim);
        }
        assertTrue(preserve(BASE + source + "No browser or tests were run."));
    }

    @Test
    void newRolesRemainBoundedAndDoNotUpgradeUnsupportedProblemFamilies() {
        String answer = BASE + "The expression in script.js uses `.toFixed(1)`.";
        var facts = facts(JS);
        assertFalse(ReadOnlyWebDiagnosticAnswer.canPreserve(answer, READS, facts, List.of()));
        assertFalse(ReadOnlyWebDiagnosticAnswer.canPreserve(answer, READS, facts,
                List.of(facts.selectorDiagnostics().getFirst(),
                        new StaticWebDiagnostic(StaticWebDiagnostic.Kind.OTHER, "script.js", "", List.of(),
                                "script.js: JavaScript syntax error."))));
        assertFalse(ReadOnlyWebDiagnosticAnswer.canPreserve(answer, READS, null, facts.selectorDiagnostics()));
        assertFalse(preserve(answer + " ".repeat(65_536)));
    }

    @Test
    void emphasisNormalizationCannotChangeLiteralSelectorValuesIntoObservedOnes() {
        assertFalse(preserve("`#ca**lc` does not exist. Use `#cal**culate`."));
        assertFalse(preserve(BASE + "The correct selector is `#cal**culate`."));
        var facts = new StaticWebSelectorAnalyzer.Facts(
                "index.html", "", "script.js", Set.of("real-button"), Set.of(), List.of(),
                Set.of(), Set.of(), Set.of(), Set.of("missing-button"), Set.of(), Set.of(),
                Set.of(), Set.of("script.js"), List.of(), List.of("script.js"),
                "<button class=\"real-button\">Run</button><script src=\"script.js\"></script>", "",
                "document.querySelector('.missing-button');", Set.of("index.html", "script.js"));
        for (String answer : List.of(
                "`.mis**sing-button` does not exist. Use `.real**-button`.",
                "`.missing-button` does not exist. Use `.real**-button`.")) {
            assertFalse(ReadOnlyWebDiagnosticAnswer.canPreserve(answer, READS, facts, facts.selectorDiagnostics()), answer);
        }
    }

    @Test
    void emphasisInSelectorRoleTextCannotLicenseAMethodAsASelector() {
        for (String role : List.of("sel**ector", "c**lass")) {
            for (String claim : List.of(
                    "The correct " + role + " is `.toFixed(1)`.",
                    "### Correct " + role + "\n`.toFixed(1)`",
                    "Use this " + role + ":\n- `.toFixed(1)`")) {
                assertFalse(preserve(BASE + claim), claim);
            }
        }
    }

    @Test
    void inlineRegionBudgetFallsBackWithoutPartiallySuppressingSelectorChecks() {
        // No backticks in the diagnostic prefix: every counted region below
        // is an observed member call, and every clause is independently small.
        String diagnosis = "#calc does not exist in index.html. Use #calculate.\n\n";
        String memberClause = "The formatter uses `.toFixed(1)`.\n\n";
        assertTrue(preserve(diagnosis + memberClause.repeat(256)), "Exactly 256 bounded inline regions");
        assertFalse(preserve(diagnosis + memberClause.repeat(257)),
                "Region overflow must retain the original conservative selector view, not a partial mask");
    }

    @Test
    void presentUseIsBoundToItsOwnSourcePredicateAndSelectorOperand() {
        for (String verb : List.of("uses", "currently uses", "now uses", "is using", "is currently using",
                "currently is using", "queries", "is querying", "references", "is referencing", "selects", "targets")) {
            for (String quote : List.of("", "`", "'", "\"")) {
                String source = quote + "script.js" + quote + " " + verb
                        + " the incorrect selector " + quote + "#calc" + quote
                        + " instead of " + quote + "#calculate" + quote + ".";
                assertTrue(preserve(source), source);
                assertTrue(preserve("The button fails because the code in " + source), source);
            }
        }
        assertTrue(preserve("script.js is using #calc. Use #calculate."));
        assertTrue(preserve("script.js is using the broken selector #calc rather than #calculate."));
    }

    @Test
    void presentUseWorksForOtherFilesIdAndClassSelectorsAndObservedLookupForms() {
        for (String file : List.of("widget.js", "assets/controls.mjs")) {
            for (boolean id : List.of(true, false)) {
                String source = id ? "#Old-control" : ".Old-control";
                String target = id ? "#Submit-now" : ".Submit-now";
                for (String query : List.of("document.querySelector('" + source + "')",
                        "document.querySelectorAll('" + source + "')",
                        id ? "document.getElementById('Old-control')" : "document.getElementsByClassName('Old-control')")) {
                    var facts = new StaticWebSelectorAnalyzer.Facts(
                            "index.html", "", file, id ? Set.of() : Set.of("Submit-now"),
                            id ? Set.of("Submit-now") : Set.of(), List.of(), Set.of(), Set.of(), Set.of(),
                            id ? Set.of() : Set.of("Old-control"), Set.of(), id ? Set.of("Old-control") : Set.of(),
                            Set.of(), Set.of(file), List.of(), List.of(file), "", "", query,
                            Set.of("index.html", file));
                    String answer = "The code in `" + file + "` is using the incorrect selector `" + source
                            + "` instead of `" + target + "`.";
                    assertTrue(ReadOnlyWebDiagnosticAnswer.canPreserve(answer, List.of("index.html", file),
                            facts, facts.selectorDiagnostics()), answer + " / " + query);
                }
            }
        }
    }

    @Test
    void sourceUseRejectsNegatedModalAndHypotheticalStatements() {
        for (String aspect : List.of("never uses", "does not use", "is not using", "isn't using",
                "should use", "should be using", "might be using", "will be using", "would use", "can use")) {
            assertFalse(preserve(BASE + "script.js " + aspect + " the incorrect selector #calc instead of #calculate."), aspect);
        }
        for (String prefix : List.of("If ", "Unless ", "Imagine ", "Suppose ", "Ensure ", "Make sure ",
                "Try a version where ", "Consider a version where ", "After the update, ", "The proposed ",
                "The corrected ", "The new ")) {
            assertFalse(preserve(BASE + prefix + "script.js is using the incorrect selector #calc instead of #calculate."), prefix);
        }
    }

    @Test
    void currentUseCannotBorrowAnotherSourceOrAnotherOccurrence() {
        for (String source : List.of("scripts.js uses", "SCRIPT.js uses", "unread.js uses",
                "scripts.js, unlike script.js, is using", "script.js is the source. It is using",
                "script.js is the source. scripts.js is using", "`script.js is using", "script.js` is using")) {
            assertFalse(preserve(BASE + source + " the incorrect selector #calc instead of #calculate."), source);
        }
        String valid = "script.js is using the incorrect selector #calc instead of #calculate";
        for (String suffix : List.of(" and use #calc to fix it.", ". Use #calc to fix it.",
                "; use #calc instead.", "\n- Use #calc instead.", ". #calc exists.",
                ". #calculate is missing.", ". I tested the page.", ". All tests passed.")) {
            assertFalse(preserve(valid + suffix), suffix);
        }
    }

    @Test
    void sourceUsePreservesProposalScopeAcrossFormattingAndNestedHeadings() {
        String description = "script.js is using the incorrect selector #calc instead of #calculate.";
        for (String framed : List.of(
                "### Proposed replacement\n" + description,
                "## Recommended change\n### Current code\n" + description,
                "# Recommended replacement\n\n```text\n# Current code\n```\n\n" + description,
                "# Recommended replacement\n\n~~~text\n# Current code\n~~~\n\n" + description,
                "Use this expression:\n- " + description,
                "### Current code\nUse this expression:\n- " + description,
                "```js\n" + description + "\n```",
                "| File | Recommended replacement |\n|------|-------------------------|\n"
                        + "| script.js | " + description + " |")) {
            assertFalse(preserve(BASE + framed), framed);
        }
        assertTrue(preserve("## Proposed change\nNo source here.\n## Current code\n" + description));
    }

    @Test
    void sourceUseContrastIsDirectionalAndCaseSensitive() {
        for (String operands : List.of("#calculate instead of #calc", "#calc instead of #calc",
                "#calc instead of #Calculate", "#calc instead of #unknown", "#calc instead of .calculate",
                "#Calc instead of #calculate")) {
            assertFalse(preserve(BASE + "script.js is using the incorrect selector " + operands + "."), operands);
        }
    }

    private static boolean preserve(String answer) {
        return preserve(answer, JS);
    }

    private static boolean preserve(String answer, String js) {
        var facts = facts(js);
        return ReadOnlyWebDiagnosticAnswer.canPreserve(answer, READS, facts, facts.selectorDiagnostics());
    }

    private static StaticWebSelectorAnalyzer.Facts facts(String js) {
        return new StaticWebSelectorAnalyzer.Facts(
                "index.html", "", "script.js", Set.of(), Set.of("weight", "height", "calculate", "result"),
                List.of("weight", "height", "calculate", "result"), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of("calc", "weight", "height", "result"), Set.of(), Set.of("script.js"),
                List.of(), List.of("script.js"), HTML, "", js, Set.copyOf(READS));
    }

    private static String recordedAnswer() throws IOException {
        return recordedAnswer("recorded-structural-guard-answer.txt");
    }

    private static String recordedAnswer(String resource) throws IOException {
        try (var stream = ReadOnlyWebDiagnosticRolesTest.class.getResourceAsStream(
                "/dev/talos/runtime/verification/" + resource)) {
            assertNotNull(stream, "Public synthetic answer fixture must be present");
            String fixture = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(fixture.endsWith("\n") && !fixture.endsWith("\n\n"));
            return fixture.substring(0, fixture.length() - 1);
        }
    }
}
