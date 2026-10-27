package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebDiagnosticTest {

    @Test
    void displayMessagesAndOrderingArePreserved() {
        StaticWebSelectorAnalyzer.Facts facts = facts("pages/Home.html", "assets/Style.css", "assets/App.js");

        List<String> expected = List.of(
                "HTML defines duplicate IDs: `#output`",
                "HTML defines duplicate IDs: `#app`",
                "CSS references missing class selectors: `.acss`, `.zcss`",
                "CSS references missing ID selectors: `#aid`, `#zid`",
                "CSS likely uses bare element selectors where HTML defines classes: "
                        + "`button` should probably be `.button`, `panel` should probably be `.panel`",
                "JavaScript references missing class selectors: `.ajs`, `.zjs`",
                "JavaScript references missing IDs: `#ajsid`, `#zjsid`");

        assertEquals(expected, facts.selectorProblems());
        assertEquals(expected, facts.selectorDiagnostics().stream().map(StaticWebDiagnostic::message).toList());
    }

    @Test
    void diagnosticKindsAndOperandsAreRetainedSeparately() {
        List<StaticWebDiagnostic> diagnostics = facts("index.html", "styles.css", "script.js")
                .selectorDiagnostics();

        assertEquals(List.of(
                        StaticWebDiagnostic.Kind.HTML_DUPLICATE_ID,
                        StaticWebDiagnostic.Kind.HTML_DUPLICATE_ID,
                        StaticWebDiagnostic.Kind.CSS_MISSING_CLASS,
                        StaticWebDiagnostic.Kind.CSS_MISSING_ID,
                        StaticWebDiagnostic.Kind.CSS_BARE_CLASS,
                        StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_CLASS,
                        StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID),
                diagnostics.stream().map(StaticWebDiagnostic::kind).toList());
        assertEquals(List.of(
                        List.of("output"), List.of("app"), List.of("acss", "zcss"),
                        List.of("aid", "zid"), List.of("button", "panel"),
                        List.of("ajs", "zjs"), List.of("ajsid", "zjsid")),
                diagnostics.stream().map(StaticWebDiagnostic::operands).toList());
    }

    @Test
    void analyzedSourceAndTargetNamesArePreservedExactly() {
        List<StaticWebDiagnostic> diagnostics = facts("pages/Home.html", "assets/Style.css", "assets/App.js")
                .selectorDiagnostics();

        assertEquals(List.of("pages/Home.html", "pages/Home.html", "assets/Style.css",
                        "assets/Style.css", "assets/Style.css", "assets/App.js", "assets/App.js"),
                diagnostics.stream().map(StaticWebDiagnostic::source).toList());
        assertTrue(diagnostics.stream().allMatch(diagnostic -> diagnostic.target().equals("pages/Home.html")));
    }

    @Test
    void kindCapabilitiesAreRestrictedToTheirDeclaredCategories() {
        Set<StaticWebDiagnostic.Kind> cssKinds = Set.of(
                StaticWebDiagnostic.Kind.CSS_MISSING_CLASS,
                StaticWebDiagnostic.Kind.CSS_MISSING_ID,
                StaticWebDiagnostic.Kind.CSS_BARE_CLASS);
        Set<StaticWebDiagnostic.Kind> javaScriptKinds = Set.of(
                StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_CLASS,
                StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID);

        for (StaticWebDiagnostic.Kind kind : StaticWebDiagnostic.Kind.values()) {
            assertEquals(cssKinds.contains(kind), kind.isCss(), kind.name());
            assertEquals(javaScriptKinds.contains(kind), kind.isJavaScript(), kind.name());
            assertEquals(javaScriptKinds.contains(kind), kind.supportsAnswerPreservation(), kind.name());
        }
    }

    @Test
    void unsupportedMessagesAreNotClassifiedFromEnglishText() {
        StaticWebDiagnostic diagnostic = StaticWebDiagnostic.other("JavaScript references missing IDs: `#calc`");

        assertEquals(StaticWebDiagnostic.Kind.OTHER, diagnostic.kind());
        assertEquals("", diagnostic.source());
        assertEquals("", diagnostic.target());
        assertEquals(List.of(), diagnostic.operands());
        assertEquals("JavaScript references missing IDs: `#calc`", diagnostic.message());
        assertFalse(diagnostic.kind().supportsAnswerPreservation());
    }

    @Test
    void missingFieldsAreDefaultedWithoutPreservationAuthority() {
        StaticWebDiagnostic diagnostic = new StaticWebDiagnostic(null, null, null, null, null);

        assertEquals(StaticWebDiagnostic.other(null), diagnostic);
        assertEquals(StaticWebDiagnostic.Kind.OTHER, diagnostic.kind());
        assertEquals("", diagnostic.source());
        assertEquals("", diagnostic.target());
        assertEquals(List.of(), diagnostic.operands());
        assertEquals("", diagnostic.message());
        assertFalse(diagnostic.kind().supportsAnswerPreservation());
    }

    @Test
    void operandsAreDefensivelyCopiedAndImmutable() {
        List<String> operands = new ArrayList<>(List.of("calc"));
        StaticWebDiagnostic diagnostic = new StaticWebDiagnostic(
                StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID,
                "script.js", "index.html", operands, "An ID mismatch was found.");

        operands.set(0, "changed");
        operands.add("extra");

        assertEquals(List.of("calc"), diagnostic.operands());
        assertThrows(UnsupportedOperationException.class, () -> diagnostic.operands().add("extra"));
        assertThrows(NullPointerException.class, () -> new StaticWebDiagnostic(
                StaticWebDiagnostic.Kind.OTHER, "", "", Arrays.asList("calc", null), ""));
    }

    @Test
    void diagnosticCollectionsAreImmutable() {
        List<StaticWebDiagnostic> diagnostics = facts("index.html", "styles.css", "script.js")
                .selectorDiagnostics();

        assertThrows(UnsupportedOperationException.class, () -> diagnostics.add(StaticWebDiagnostic.other("")));
        assertThrows(UnsupportedOperationException.class, () -> diagnostics.get(2).operands().add("extra"));
    }

    @Test
    void emptySelectorSetsAreProjectedWithoutDiagnostics() {
        StaticWebSelectorAnalyzer.Facts facts = new StaticWebSelectorAnalyzer.Facts(
                "index.html", "styles.css", "script.js",
                Set.of(), Set.of(), List.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), List.of(), List.of(),
                "", "", "", Set.of());

        assertEquals(List.of(), facts.selectorDiagnostics());
        assertEquals(List.of(), facts.selectorProblems());
    }

    private static StaticWebSelectorAnalyzer.Facts facts(String htmlFile, String cssFile, String jsFile) {
        return new StaticWebSelectorAnalyzer.Facts(
                htmlFile, cssFile, jsFile,
                ordered("panel", "button"),
                ordered("output", "app"),
                List.of("output", "app", "output", "app"),
                ordered("zcss", "panel", "dynamic", "hidden", "acss", "error"),
                ordered("zid", "output", "aid"),
                ordered("panel", "button"),
                ordered("zjs", "panel", "ajs"),
                ordered("dynamic"),
                ordered("zjsid", "output", "ajsid"),
                Set.of(cssFile), Set.of(jsFile), List.of(cssFile), List.of(jsFile),
                "", "#output.error { color: red; }", "", Set.of(htmlFile, cssFile, jsFile));
    }

    private static Set<String> ordered(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }
}
