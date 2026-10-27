package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationPostfixOwnershipTest {
    @ParameterizedTest
    @ValueSource(strings = {
            " so #teaser-button updates #teaser-status when clicked.",
            " so that #launch changes #notice when pressed",
            " so the #launch shows the #notice when activated!",
            " so #launch hides #notice when focused",
            " so #launch enables #notice when selected",
            " so #launch disables #notice when #launch is clicked",
            " so #launch updates #notice when the #launch is pressed",
            " so `#launch` updates `#notice` when `#launch` is activated",
            " so #launch changes #notice when the user clicks #launch",
            " so #launch changes #notice when user presses #launch",
            " so #launch changes #notice when the user activates #launch",
            " so #launch changes #notice when the user focuses #launch",
            " so #launch changes #notice when the user selects #launch",
            " SO #Launch UPDATES #Notice WHEN #Launch IS CLICKED",
            " so #if updates #when when clicked"
    })
    void ownsOnlyDeclaredProductEvents(String suffix) {
        String request = "Update page.html" + suffix;
        var result = analyze(request);
        assertEquals(MutationPostfixOwnership.Kind.NONE, result.kind(), request);
        assertTrue(result.closedBehavior(), request);
        assertEquals("Update page.html".length(), result.behaviorSpan().start());
        assertTrue(request.substring(result.eventSpan().start(), result.eventSpan().end())
                .toLowerCase(java.util.Locale.ROOT).startsWith("when "));
        assertTrue(result.eventSpan().end() <= result.behaviorSpan().end());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " so #button updates #status when clicked, only if approved.",
            " so #button updates #status when clicked unless approval is denied.",
            " so #button updates #status when clicked, perhaps.",
            " so #button updates #status when clicked, provided approval is obtained.",
            " so #button updates #status when clicked, provided that approval is obtained.",
            " so #button updates #status when clicked, only after approval.",
            " so #button updates #status when clicked, until approval arrives.",
            " only if approved so #button updates #status when clicked.",
            " only after approval so it works.",
            " provided that approval is obtained so it works.",
            " until approval arrives so it works.",
            " so #button updates #status when approved.",
            " so #button updates #status when you approve.",
            " so #worker retries #job when the connection drops.",
            " so the cache clears if the response is stale.",
            " so #button updates #status until valid."
    })
    void leavesActualOrUnsupportedConditionsRestrictive(String suffix) {
        assertEquals(MutationPostfixOwnership.Kind.OPTIONAL, analyze("Update page.html" + suffix).kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " so #button updates #status when clicked according to the ticket.",
            " so #button updates #status when clicked only as a proposal.",
            " according to the report.",
            " so #button updates #status when clicked, if approved, as an example."
    })
    void reportedScopeIsNeverOwnedAsAnEvent(String suffix) {
        assertEquals(MutationPostfixOwnership.Kind.REPORTED, analyze("Update page.html" + suffix).kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " so #button updates #status when #other is clicked.",
            " so #Button updates #status when #button is clicked.",
            " so #button updates #status when the user clicks #other.",
            " so #button updates #status when clicked for deployment.",
            " so #button updates #status when clicked and update admin.js.",
            " so #button updates #status when clicked, do not edit yet.",
            " so #button updates #status when clicked `only if approved`.",
            " so #button updates #status when clicked\u0000.",
            " so #button updates #status when clicked\r.",
            " so #button updates #status when clicked (."
    })
    void unexplainedOrMismatchedBehaviorDoesNotBecomeAuthority(String suffix) {
        assertEquals(MutationPostfixOwnership.Kind.UNSUPPORTED, analyze("Update page.html" + suffix).kind());
    }

    @Test
    void malformedAndQuotedBehaviorCannotBeAccepted() {
        for (String suffix : List.of(
                " so `#button updates #status when clicked.",
                " so #button` updates #status when clicked.",
                " so `#button` updates #status when `#button is clicked.",
                " so #button updates #status wh\u0000en clicked.",
                " so #button updates #status when clicked`.")) {
            assertFalse(analyze("Update page.html" + suffix).kind() == MutationPostfixOwnership.Kind.NONE, suffix);
        }
        String quoted = "\"Update page.html so #button updates #status when clicked\".";
        var result = MutationPostfixOwnership.analyze(
                RequestLexicalView.of(quoted), quoted.indexOf(" so "), quoted.length());
        assertFalse(result.closedBehavior());
        assertEquals(MutationPostfixOwnership.Kind.UNSUPPORTED, result.kind());
    }

    @Test
    void residualAnalysisPreservesOriginalOffsets() {
        String request = "Update page.html so `#button` updates #status when `#button` is clicked, only if approved.";
        var result = analyze(request);
        assertTrue(result.closedBehavior());
        assertEquals("when `#button` is clicked", request.substring(
                result.eventSpan().start(), result.eventSpan().end()));
        assertEquals(", only if approved.", request.substring(result.behaviorSpan().end()));
        assertEquals(MutationPostfixOwnership.Kind.OPTIONAL, result.kind());
    }

    @Test
    void ordinaryRawPostfixScopeAndEmptySuffixRemainUnchanged() {
        assertEquals(MutationPostfixOwnership.Kind.NONE, analyze("Update page.html.").kind());
        assertEquals(MutationPostfixOwnership.Kind.OPTIONAL, analyze("Update page.html only if approved.").kind());
        assertEquals(MutationPostfixOwnership.Kind.REPORTED, analyze("Update page.html as a proposal.").kind());
        assertFalse(analyze("Update page.html.").closedBehavior());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " so it works.",
            " so index.html still works.",
            " so the page works.",
            " so it says 'update style.css'.",
            " so it says 'only if approved'.",
            " so \"#button updates #status when clicked\"."
    })
    void existingUnconditionedConstraintsDoNotInventBehaviorOwnership(String suffix) {
        var result = analyze("Update page.html" + suffix);
        assertEquals(MutationPostfixOwnership.Kind.NONE, result.kind());
        assertFalse(result.closedBehavior());
    }

    @Test
    void completeTargetReceiptKeepsEveryOccurrenceAndExactAction() {
        String request = "Keep styles.css unchanged. Update index.html, scripts.js and ./scripts.js so #x updates #y when clicked.";
        RequestLexicalView view = RequestLexicalView.of(request);
        var action = updateAction(view);
        var receipt = MutationPostfixOwnership.bindDirectTargets(
                view, action, request.indexOf("index.html") + "index.html".length(), request.length());
        assertNotNull(receipt);
        assertEquals(action.start(), receipt.actionStart());
        assertEquals(action.end(), receipt.actionEnd());
        assertEquals(List.of("index.html", "scripts.js", "scripts.js"),
                receipt.targets().stream().map(MutationPostfixOwnership.Target::path).toList());
        assertEquals(request.indexOf(" so "), receipt.outputEnd());
        assertEquals("./scripts.js", request.substring(receipt.targets().getLast().start(), receipt.outputEnd()));
        assertEquals(MutationPostfixOwnership.Kind.NONE,
                MutationPostfixOwnership.analyze(view, receipt.outputEnd(), request.length()).kind());
    }

    @Test
    void balancedInlineCodeAndParameterizedFileNamesAreBound() {
        String request = "Update `widgets/page two.html` and ./scripts/bridge.mjs so `#alpha` changes #omega when selected.";
        RequestLexicalView view = RequestLexicalView.of(request);
        var receipt = MutationPostfixOwnership.bindDirectTargets(view, updateAction(view),
                request.indexOf("` and") + 1, request.length());
        assertNotNull(receipt);
        assertEquals(List.of("widgets/page two.html", "scripts/bridge.mjs"),
                receipt.targets().stream().map(MutationPostfixOwnership.Target::path).toList());
        assertEquals(request.indexOf(" so "), receipt.outputEnd());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Update index.html or scripts.js so #x updates #y when clicked.",
            "Update index.html and/or scripts.js so #x updates #y when clicked.",
            "Update index.html and",
            "Update index.html and ",
            "Update index.html,",
            "Update index.html and unknown so #x updates #y when clicked.",
            "Update index.html` so #x updates #y when clicked.",
            "Update index.html and `scripts.js so #x updates #y when clicked.",
            "Update index.html and scr\u0000ipts.js so #x updates #y when clicked."
    })
    void incompleteOrUnsafeTargetListsHaveNoReceipt(String request) {
        RequestLexicalView view = RequestLexicalView.of(request);
        assertNull(MutationPostfixOwnership.bindDirectTargets(view, updateAction(view),
                request.indexOf("index.html") + "index.html".length(), request.length()), request);
    }

    @Test
    void receiptCannotBorrowAnotherActionOrPartialFileEnd() {
        String request = "Update index.html if approved. Update index.html so #x updates #y when clicked.";
        RequestLexicalView view = RequestLexicalView.of(request);
        var actions = CanonicalMutationClauseMatcher.actions(view, 0, request.length());
        var second = actions.stream().filter(action -> action.verb().equalsIgnoreCase("update")).skip(1).findFirst().orElseThrow();
        assertNull(MutationPostfixOwnership.bindDirectTargets(view, second,
                request.indexOf("index.html") + "index.html".length(), request.length()));
        assertNull(MutationPostfixOwnership.bindDirectTargets(view, second,
                request.lastIndexOf("index.html") + "index".length(), request.length()));
        var receipt = MutationPostfixOwnership.bindDirectTargets(view, second,
                request.lastIndexOf("index.html") + "index.html".length(), request.length());
        assertNotNull(receipt);
        assertEquals(second.start(), receipt.actionStart());
    }

    @Test
    void boundedWorkRejectsOversizedSuffixesAndTargetLists() {
        String largeSuffix = "Update page.html so " + "x".repeat(MutationPostfixOwnership.MAX_CHARACTERS);
        assertEquals(MutationPostfixOwnership.Kind.UNSUPPORTED, analyze(largeSuffix).kind());
        String manyTargets = "Update index.html" + ", next.js".repeat(MutationPostfixOwnership.MAX_TARGETS)
                + " so #x updates #y when clicked.";
        RequestLexicalView view = RequestLexicalView.of(manyTargets);
        assertNull(MutationPostfixOwnership.bindDirectTargets(view, updateAction(view),
                "Update index.html".length(), manyTargets.length()));
    }

    @Test
    void rawTaintOwnsPrimaryAndChildBeforeAnyHardReset() {
        for (String control : List.of("\u0000", "\r")) {
            for (String connector : List.of(" so ", "\tso\t", "  so ",
                    " without breaking ", " without changing ", " compatible with ",
                    " stay compatible with ", " stays compatible with ")) {
                String request = "Context is available. Update a.js" + connector
                        + "#x updates #y wh" + control + "en clicked, then update b.js.";
                RequestLexicalView view = RequestLexicalView.of(request);
                assertTrue(MutationPostfixOwnership.rawConstraintTainted(view, request.indexOf("Update a.js")), request);
                assertTrue(MutationPostfixOwnership.rawConstraintTainted(view, request.indexOf("update b.js")), request);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {". ", "; ", "\n", "\r\n", "\u2028", "\u2029"})
    void rawTaintCannotCrossAnIndependentHardReset(String reset) {
        String damaged = "Update a.js so #x updates #y wh\u0000en clicked";
        String prior = "Update clean.js so #a updates #b when clicked" + reset + damaged + ".";
        RequestLexicalView priorView = RequestLexicalView.of(prior);
        assertFalse(MutationPostfixOwnership.rawConstraintTainted(priorView, 0), prior);
        assertTrue(MutationPostfixOwnership.rawConstraintTainted(priorView, prior.indexOf("Update a.js")), prior);
        String next = damaged + reset + "Update clean.js so #a updates #b when clicked.";
        RequestLexicalView nextView = RequestLexicalView.of(next);
        assertTrue(MutationPostfixOwnership.rawConstraintTainted(nextView, 0), next);
        assertFalse(MutationPostfixOwnership.rawConstraintTainted(nextView, next.indexOf("Update clean.js")), next);
    }

    @Test
    void rawGuardDoesNotReclassifyOrdinaryControlsOrQuotedConnectors() {
        for (String request : List.of(
                "Update a.js, th\u0000en update b.js.",
                "Update a.js.\u0000 Update b.js.",
                "Update so.js wh\u0000en needed.",
                "Update a.js to say ' so it works' wh\u0000en needed.")) {
            assertFalse(MutationPostfixOwnership.rawConstraintTainted(RequestLexicalView.of(request), 0), request);
        }
    }

    @Test
    void quotedOrNestedPunctuationCannotEraseRawTaint() {
        for (String text : List.of("'(note; still one)'", "(note; still one)", "(note. still one)")) {
            String request = "Update a.js so #x updates #y " + text + " wh\u0000en clicked, then update b.js.";
            RequestLexicalView view = RequestLexicalView.of(request);
            assertTrue(MutationPostfixOwnership.rawConstraintTainted(view, 0), request);
            assertTrue(MutationPostfixOwnership.rawConstraintTainted(view, request.indexOf("update b.js")), request);
        }
    }

    @Test
    void oversizedTaintedOwnershipCannotGainAuthority() {
        String request = "Update a.js so " + "x".repeat(MutationPostfixOwnership.MAX_CHARACTERS)
                + "\u0000, then update b.js.";
        RequestLexicalView view = RequestLexicalView.of(request);
        assertTrue(MutationPostfixOwnership.rawConstraintTainted(view, 0));
        assertTrue(MutationPostfixOwnership.rawConstraintTainted(view, request.indexOf("update b.js")));
        String separate = request + " Update clean.js.";
        assertFalse(MutationPostfixOwnership.rawConstraintTainted(
                RequestLexicalView.of(separate), separate.indexOf("Update clean.js")));
        String untainted = "Update a.js so " + "x".repeat(MutationPostfixOwnership.MAX_CHARACTERS);
        assertFalse(MutationPostfixOwnership.rawConstraintTainted(RequestLexicalView.of(untainted), 0));
    }

    private static MutationPostfixOwnership.Analysis analyze(String request) {
        return MutationPostfixOwnership.analyze(RequestLexicalView.of(request),
                "Update page.html".length(), request.length());
    }

    private static CanonicalMutationClauseMatcher.Action updateAction(RequestLexicalView view) {
        return CanonicalMutationClauseMatcher.actions(view, 0, view.request().length()).stream()
                .filter(action -> action.verb().equalsIgnoreCase("update"))
                .findFirst().orElseThrow();
    }
}
