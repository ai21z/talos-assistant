package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class T1204SequentialAuthorityScopeTest {

    @Test
    void advisoryScopeIsTypedAcrossDeclaredSequentialConnectors() {
        for (String lead : List.of("Consider ", "You should ")) {
            for (String connector : List.of("; ", "\n", "\r\n", "; then ")) {
                String request = lead + "update style.css" + connector
                        + "update script.js.";
                List<CanonicalMutationClauseMatcher.Action> actions =
                        indexedActions(request, "style.css", "script.js");

                assertEquals(2, actions.size(), request);
                assertEquals(
                        CanonicalMutationClauseMatcher.ScopeKind.REPORTED_ADVISORY,
                        actions.get(0).scopeKind(),
                        request);
                assertEquals(
                        CanonicalMutationClauseMatcher.ScopeKind.REPORTED_ADVISORY,
                        actions.get(1).scopeKind(),
                        request);
            }
        }
    }

    @Test
    void advisoryScopeResetsAtCommaButAndFreshSentenceOnly() {
        for (String request : List.of(
                "Consider update style.css, but update script.js.",
                "The report says update style.css, but update script.js.",
                "Consider update style.css. Update script.js.")) {
            List<CanonicalMutationClauseMatcher.Action> actions =
                    indexedActions(request, "style.css", "script.js");

            assertEquals(2, actions.size(), request);
            assertEquals(
                    CanonicalMutationClauseMatcher.ScopeKind.REPORTED_ADVISORY,
                    actions.get(0).scopeKind(),
                    request);
            assertFalse(actions.get(1).nonImperativeUse(), request);
            assertEquals(
                    CanonicalMutationClauseMatcher.ScopeKind.NONE,
                    actions.get(1).scopeKind(),
                    request);
        }
    }

    @Test
    void disjunctiveAmbiguityDoesNotCarryIntoIndependentLaterClause() {
        String request =
                "Update style.css or script.js; then update app.js.";
        List<CanonicalMutationClauseMatcher.Action> actions =
                indexedActions(request, "style.css", "script.js", "app.js");

        assertEquals(2, actions.size());
        assertFalse(actions.getLast().nonImperativeUse());
        assertEquals(
                CanonicalMutationClauseMatcher.ScopeKind.NONE,
                actions.getLast().scopeKind());
    }

    private static List<CanonicalMutationClauseMatcher.Action> indexedActions(
            String request,
            String... targets
    ) {
        RequestLexicalView view = RequestLexicalView.of(request);
        List<RequestLexicalView.Span> spans = new ArrayList<>();
        for (String target : targets) {
            int start = request.indexOf(target);
            if (start < 0) throw new AssertionError("missing target " + target);
            spans.add(new RequestLexicalView.Span(
                    start,
                    start + target.length()));
        }
        return CanonicalMutationClauseMatcher.indexedActions(
                view,
                0,
                request.length(),
                spans).actions();
    }
}
