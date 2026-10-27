package dev.talos.runtime.verification;

import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment.Reason;
import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedReadOnlyWebDiagnosticsTest {
    private static final String REQUEST = "Explain the selector mismatch without changing files.";
    private static final String ANSWER = "`#calc` is missing from index.html. The correct selector is `#calculate`.";
    private static final String SCRIPT = "document.querySelector('#calc');\n";

    @Test
    void completeObservationRetainsTheExistingSupportedAnswer() {
        var result = assess(sources("index.html", "script.js", html("script.js")), ANSWER);

        assertTrue(result.assessment().preserved());
        assertEquals(ANSWER, result.answer());
        assertEquals(List.of(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID),
                result.diagnostics().stream().map(StaticWebDiagnostic::kind).toList());
        assertEquals(List.of("calc"), result.diagnostics().getFirst().operands());
    }

    @Test
    void nestedSourcesKeepFullIdentityAndResolveParentSegments() {
        var result = assess(sources("pages/index.html", "assets/app.js", html("../assets/./app.js")),
                "`#calc` is missing. The correct selector is `#calculate`.");

        assertTrue(result.assessment().preserved());
        assertEquals("assets/app.js", result.diagnostics().getFirst().source());
        assertEquals("pages/index.html", result.diagnostics().getFirst().target());
    }

    @Test
    void identicalBasenamesCannotSubstituteAnUnobservedLinkedSource() {
        var result = assess(sources("pages/index.html", "other/app.js", html("../assets/app.js")), ANSWER);

        assertEquals(Reason.LINKED_SOURCE_UNOBSERVED, result.assessment().reason());
        assertTrue(result.answer().contains("`assets/app.js`"));
        assertTrue(result.answer().contains("not among the available source observations"));
        assertFalse(result.answer().contains("missing JavaScript file"));
    }

    @Test
    void unlinkedObservedScriptsDoNotBecomeTheAnalyzedScript() {
        List<Source> observations = new ArrayList<>(sources("index.html", "script.js", html("script.js")));
        observations.add(source("scripts.js", "document.querySelector('#unrelated');\n", 3));

        var result = assess(observations, ANSWER);

        assertTrue(result.assessment().preserved());
        assertEquals(List.of("calc"), result.diagnostics().getFirst().operands());
        assertFalse(result.answer().contains("unrelated"));
    }

    @Test
    void additionalCompleteLinkedCssKeepsItsExistingDiagnosticType() {
        List<Source> observations = new ArrayList<>(sources("pages/index.html", "assets/app.js",
                html("../assets/app.js").replace("</head>", "<link rel='stylesheet' href='../assets/site.css'></head>")));
        observations.add(source("assets/site.css", ".missing { color: red; }\n", 3));

        var result = assess(observations, ANSWER);

        assertEquals(Reason.UNSUPPORTED_DIAGNOSTIC, result.assessment().reason());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.kind() == StaticWebDiagnostic.Kind.CSS_MISSING_CLASS
                        && diagnostic.source().equals("assets/site.css")));
    }

    @Test
    void unobservedStylesheetIsNotAClaimOfNonexistence() {
        var result = assess(sources("index.html", "script.js",
                html("script.js").replace("</head>", "<link rel='stylesheet' href='unobserved.css'></head>")),
                "The correct selector is `#calc`.");

        assertTrue(result.assessment().limited());
        assertFalse(result.answer().contains("missing CSS file"));
        assertFalse(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("unobserved.css")));
    }

    @Test
    void absentOrAmbiguousHtmlAndScriptRelationshipsAreLimited() {
        assertEquals(Reason.SOURCE_EVIDENCE_UNAVAILABLE, assess(List.of(), ANSWER).assessment().reason());
        assertEquals(Reason.SOURCE_RELATION_UNSUPPORTED,
                assess(List.of(source("script.js", SCRIPT, 1)), ANSWER).assessment().reason());
        List<Source> documents = new ArrayList<>(sources("index.html", "script.js", html("script.js")));
        documents.add(source("another.html", html("script.js"), 3));
        assertEquals(Reason.SOURCE_RELATION_AMBIGUOUS, assess(documents, ANSWER).assessment().reason());
        assertEquals(Reason.SOURCE_RELATION_AMBIGUOUS, assess(sources("index.html", "script.js",
                html("script.js").replace("</body>", "<script src='other.js'></script></body>")), ANSWER).assessment().reason());
        assertEquals(Reason.SOURCE_RELATION_AMBIGUOUS, assess(sources("index.html", "script.js",
                html("script.js").replace("</body>", "<script src='script.js'></script></body>")), ANSWER).assessment().reason());
    }

    @Test
    void unsupportedLinkSemanticsNeverFallBackToAnObservedBasename() {
        for (String href : List.of("/script.js", "https://example.test/script.js", "//host/script.js",
                "script.js?v=1", "script.js#main", "%73cript.js", "../script.js", "assets//script.js")) {
            var result = assess(sources("index.html", "script.js", html(href)), ANSWER);
            assertEquals(Reason.SOURCE_RELATION_UNSUPPORTED, result.assessment().reason(), href);
            assertFalse(result.assessment().preserved(), href);
        }
    }

    @Test
    void baseInlineModulesAndMalformedAttributesAreExplicitlyUnsupported() {
        for (String document : List.of(
                html("script.js").replace("</head>", "<base href='assets/'></head>"),
                html("script.js").replace("<script src=", "<script type='module' src="),
                html("script.js").replace("<script src=", "<script type=module src="),
                html("script.js").replace("<script src=", "<script type='module' type='text/javascript' src="),
                html("script.js").replace("src='script.js'", "src=script.js"),
                html("script.js").replace("src='script.js'", "src='script.js' src='other.js'"),
                html("script.js").replace("src='script.js'", "data-example=\"src='script.js'\""),
                html("script.js").replace("<script src='script.js'></script>", "<script>document.querySelector('#calc');</script>"))) {
            assertEquals(Reason.SOURCE_RELATION_UNSUPPORTED,
                    assess(sources("index.html", "script.js", document), ANSWER).assessment().reason(), document);
        }
        for (String script : List.of("import './other.js';\n", "export const value = 1;\n", "require('./other.js');\n")) {
            assertEquals(Reason.SOURCE_RELATION_UNSUPPORTED, assess(List.of(
                    source("index.html", html("script.js"), 1), source("script.js", script, 2)), ANSWER).assessment().reason());
        }
    }

    @Test
    void inactiveOrEmbeddedMarkupCannotSupplyTheObservedScriptLink() {
        String linked = "<script src='script.js'></script>";
        List<String> inactive = new ArrayList<>(List.of(
                "<!-- " + linked + " -->",
                "<!-- " + linked,
                "<div data-example=\"" + linked + "\"></div>",
                "<div data-example=" + linked + "></div>",
                "<plaintext>" + linked));
        for (String tag : List.of("template", "noscript", "svg", "math", "textarea", "title",
                "style", "xmp", "iframe", "noembed", "noframes")) {
            inactive.add("<" + tag + ">" + linked + "</" + tag + ">");
        }
        for (String markup : inactive) {
            var result = assess(sources("index.html", "script.js", html("script.js").replace(linked, markup)), ANSWER);
            assertEquals(Reason.SOURCE_RELATION_UNSUPPORTED, result.assessment().reason(), markup);
            assertEquals(List.of(), result.diagnostics(), markup);
            assertFalse(result.assessment().preserved(), markup);
        }
    }

    @Test
    void unknownDeclarationsCannotSupplyTheObservedScriptLink() {
        String linked = "<script src='script.js'></script>";
        for (String declaration : List.of("<![CDATA[" + linked + "]]>", "<?example " + linked + " ?>",
                "<!example " + linked + ">", "<!DOCTYPE html PUBLIC \"" + linked + "\">")) {
            var result = assess(sources("index.html", "script.js",
                    html("script.js").replace(linked, declaration)), ANSWER);
            assertEquals(Reason.SOURCE_RELATION_UNSUPPORTED, result.assessment().reason(), declaration);
            assertEquals(List.of(), result.diagnostics(), declaration);
        }
    }

    @Test
    void ordinaryCommentsAndTitleTextDoNotRemoveSupportedLinkage() {
        var result = assess(sources("index.html", "script.js", html("script.js").replace("</head>",
                "<!-- The button is handled by the linked script. --></head>")), ANSWER);

        assertTrue(result.assessment().preserved());
        assertEquals(ANSWER, result.answer());
    }

    @Test
    void observedTargetsUseExactAttributesFromCompleteOpeningTags() {
        String document = html("script.js").replace("id='calculate'",
                "disabled data-id='pretend' data-class='ghost' ID='calculate' ClAsS='action selected'"
                        + " title=\"id='quoted' class='quotedClass'\""
                        + " data-example=\"<span id='nested' class='nestedClass'></span>\"");
        var selection = ObservedStaticWebSources.select(sources("index.html", "script.js", document));

        assertTrue(selection.available());
        assertEquals(Set.of("calculate"), selection.facts().htmlIds());
        assertEquals(Set.of("action", "selected"), selection.facts().htmlClasses());
        assertEquals(document, selection.facts().html());
    }

    @Test
    void rawTextBodiesAndCommentsCannotSupplyObservedHtmlTargets() {
        for (String tag : List.of("title", "textarea", "style", "xmp", "iframe", "noembed", "noframes")) {
            String document = html("script.js").replace("</body>",
                    "<!-- <div id='comment' class='commentClass'></div> -->"
                            + "<" + tag + " id='container' class='shell'><span id='body' class='bodyClass'></span>"
                            + "</" + tag + "></body>");
            var selection = ObservedStaticWebSources.select(sources("index.html", "script.js", document));

            assertTrue(selection.available(), tag);
            assertEquals(Set.of("calculate", "container"), selection.facts().htmlIds(), tag);
            assertEquals(Set.of("shell"), selection.facts().htmlClasses(), tag);
        }
    }

    @Test
    void unsupportedAttributeScopesNeverProduceObservedTargets() {
        for (String attributes : List.of("id=calculate", "id='calculate' id='other'",
                "id='calculate'class='action'", "id='calcu&#108;ate'", "class='one&#32;two'",
                "data-example=<span id='calculate'>")) {
            var selection = ObservedStaticWebSources.select(sources("index.html", "script.js",
                    html("script.js").replace("id='calculate'", attributes)));

            assertFalse(selection.available(), attributes);
            assertEquals(Reason.SOURCE_RELATION_UNSUPPORTED, selection.reason(), attributes);
        }
    }

    @Test
    void nonHtmlWhitespaceAndMalformedCommentsAreExplicitlyLimited() {
        for (String document : List.of(
                html("script.js").replace(" id='calculate'", "\u000bid='calculate'"),
                html("script.js").replace(" id='calculate'", " id\u000b='calculate'"),
                html("script.js").replace("<button", "<!--><span id='hidden'></span><!-- --><button"),
                html("script.js").replace("<button", "<!---><span id='hidden'></span><!-- --><button"),
                html("script.js").replace("<button", "<!-- malformed -- comment --><button"),
                html("script.js").replace("</title>", "</title\u000b>"))) {
            var result = assess(sources("index.html", "script.js", document), ANSWER);

            assertEquals(Reason.SOURCE_RELATION_UNSUPPORTED, result.assessment().reason(), document);
            assertEquals(List.of(), result.diagnostics(), document);
        }
    }

    @Test
    void misleadingStylesheetMarkupIsNotInterpretedAsAnActiveSource() {
        for (String markup : List.of(
                "<!-- <link rel='stylesheet' href='site.css'> -->",
                "<textarea><link rel='stylesheet' href='site.css'></textarea>",
                "<div data-example=\"<link rel='stylesheet' href='site.css'>\"></div>")) {
            List<Source> observations = new ArrayList<>(sources("index.html", "script.js",
                    html("script.js").replace("</body>", markup + "</body>")));
            observations.add(source("site.css", ".missing { color: red; }\n", 3));
            var result = assess(observations, ANSWER);
            assertEquals(Reason.SOURCE_RELATION_UNSUPPORTED, result.assessment().reason(), markup);
            assertEquals(List.of(), result.diagnostics(), markup);
        }
    }

    @Test
    void duplicatePathsAndObservationReferencesAreNotMerged() {
        List<Source> paths = new ArrayList<>(sources("index.html", "script.js", html("script.js")));
        paths.add(source("./script.js", "document.querySelector('#calculate');", 3));
        assertEquals(Reason.SOURCE_RELATION_AMBIGUOUS, assess(paths, ANSWER).assessment().reason());
        assertEquals(Reason.SOURCE_RELATION_AMBIGUOUS, assess(List.of(
                source("index.html", html("script.js"), 1), source("script.js", SCRIPT, 1)), ANSWER).assessment().reason());
    }

    @Test
    void diskReplacementAndDeletionDoNotChangeTheObservationAssessment(@TempDir Path directory) throws Exception {
        Path document = directory.resolve("index.html");
        Path script = directory.resolve("script.js");
        Files.writeString(document, html("script.js"));
        Files.writeString(script, SCRIPT);
        List<Source> observations = sources("index.html", "script.js", Files.readString(document));
        var before = assess(observations, ANSWER);
        Files.writeString(document, "<html><body id='calc'></body></html>");
        Files.writeString(script, "document.querySelector('#calculate');\n");
        assertEquals(before, assess(observations, ANSWER));
        Files.delete(document);
        Files.delete(script);
        assertEquals(before, assess(observations, ANSWER));
    }

    @Test
    void executionClaimsAndSelectorContradictionsRemainRejected() {
        List<Source> observations = sources("index.html", "script.js", html("script.js"));
        assertEquals(Reason.EXECUTION_CLAIM_WITHOUT_EVIDENCE,
                assess(observations, ANSWER + " I ran the tests.").assessment().reason());
        assertTrue(assess(observations, "The correct selector is `#calc`.").assessment().limited());
        assertEquals(Reason.UNSUPPORTED_ASSERTION,
                assess(observations, ANSWER + " The website works perfectly.").assessment().reason());
    }

    @Test
    void htmlOnlyResultDescribesUnobservedLinkWithoutInspectingIt() {
        var result = assess(List.of(source("pages/index.html", html("../assets/app.js"), 1)), ANSWER);

        assertEquals(Reason.LINKED_SOURCE_UNOBSERVED, result.assessment().reason());
        assertTrue(result.answer().contains("`assets/app.js`"));
        assertFalse(result.answer().contains("missing JavaScript file"));
    }

    @Test
    void finiteUnavailableFactoryNeverPreservesCandidateProse() {
        var result = StaticTaskVerifier.limitedReadOnlyWebDiagnostics(Reason.SOURCE_EVIDENCE_UNAVAILABLE);

        assertTrue(result.assessment().limited());
        assertEquals(Reason.SOURCE_EVIDENCE_UNAVAILABLE, result.assessment().reason());
        assertEquals(List.of(), result.diagnostics());
        assertTrue(result.answer().startsWith(ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX));
    }

    @Test
    void unknownProjectionAndOversizedSourceRemainUnavailable() {
        Source unsupported = new Source("index.html", html("script.js"), "read-origin", 1,
                "a".repeat(64), "unknown-projection");
        assertEquals(Reason.SOURCE_EVIDENCE_UNAVAILABLE,
                assess(List.of(unsupported, source("script.js", SCRIPT, 2)), ANSWER).assessment().reason());
        assertEquals(Reason.SOURCE_EVIDENCE_UNAVAILABLE,
                assess(List.of(source("index.html", "x".repeat(16_001), 1)), ANSWER).assessment().reason());
    }

    private static ReadOnlyWebDiagnosticResult assess(List<Source> observations, String answer) {
        return StaticTaskVerifier.assessReadOnlyWebDiagnostics(observations, REQUEST, answer);
    }

    private static List<Source> sources(String htmlPath, String scriptPath, String document) {
        return List.of(source(htmlPath, document, 1), source(scriptPath, SCRIPT, 2));
    }

    private static Source source(String path, String text, long ordinal) {
        return new Source(path, text, "read-origin", ordinal, "a".repeat(64), SOURCE_PROJECTION_VERSION);
    }

    private static String html(String script) {
        return "<!doctype html><html><head><title>Page</title></head><body>"
                + "<button id='calculate'>Calculate</button><script src='" + script + "'></script></body></html>\n";
    }
}
