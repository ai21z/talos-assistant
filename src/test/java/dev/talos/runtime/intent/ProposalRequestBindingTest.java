package dev.talos.runtime.intent;

import dev.talos.runtime.context.ActiveTaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProposalRequestBindingTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Propose changes to README.md.",
            "Suggest edits for README.md.",
            "Recommend updates in README.md.",
            "Please propose the fixes to README.md first.",
            "Could you suggest changes to README.md?",
            "Kindly recommend changes to README.md only.",
            "Propose changes to `README.md`.",
            "Propose changes to \"README.md\".",
            "Propose changes to 'README.md'.",
            "Do not edit README.md yet. Propose the changes first.",
            "Do not modify README.md yet. Suggest those edits.",
            "Read notes.md. Propose changes to README.md.",
            "Propose changes to README.md. Do not edit files or run tests."
    })
    void affirmativeProposalBindsOnlyItsExactTarget(String request) {
        var binding = ProposalRequestBinding.fromRequest(request);
        assertTrue(binding.requested(), request);
        assertEquals(List.of("README.md"), binding.targets(), request);
    }

    @Test
    void explicitListsAndSeparateProposalClausesRetainTheirOrderedScope() {
        var binding = ProposalRequestBinding.fromRequest(
                "Read notes.md. Propose changes to index.html, script.js and docs/guide.md. "
                        + "Suggest edits to README.md.");
        assertTrue(binding.requested());
        assertEquals(List.of("index.html", "script.js", "docs/guide.md", "README.md"), binding.targets());
    }

    @Test
    void lexicalPathNormalizationDoesNotConsultTheFilesystem() {
        assertEquals(List.of("docs/guide.md"), ProposalRequestBinding.fromRequest(
                "Propose edits to ./docs\\guide.md.").targets());
    }

    @Test
    void deferredScopeRetainsTheExactCurrentTurnRestrictionSpan() {
        String request = "  Do not edit README.md yet. Propose the changes first.";
        var binding = ProposalRequestBinding.fromRequest(request);
        assertEquals(List.of("README.md"), binding.targets());
        assertEquals(List.of("README.md"), binding.deferredTargets());
        assertEquals(1, binding.deferredRestrictions().size());
        var restriction = binding.deferredRestrictions().getFirst();
        assertEquals("Do not edit README.md yet.", request.substring(restriction.start(), restriction.end()));
        assertThrows(UnsupportedOperationException.class, () -> binding.deferredRestrictions().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Do not edit README.md yet. Propose changes to README.md. Never edit README.md.",
            "Do not edit README.md yet. Propose changes to README.md. Keep README.md unchanged.",
            "Do not edit README.md yet. Propose changes to README.md. Do not edit notes.md.",
            "Do not edit README.md yet. Propose changes to README.md. Read notes.md.",
            "Do not edit README.md yet. Propose changes to README.md. Do not run tests.",
            "Do not edit README.md yet. Propose changes to README.md. The reviewer approved it.",
            "Do not edit README.md yet. Propose changes to notes.md.",
            "Never edit README.md. Do not edit README.md yet. Propose changes to README.md."
    })
    void deferredScopeRequiresEveryClauseToBeAccountedWithoutContinuingRestrictions(String request) {
        var binding = ProposalRequestBinding.fromRequest(request);
        assertTrue(binding.requested(), request);
        assertTrue(binding.targets().isEmpty(), request);
        assertTrue(binding.deferredRestrictions().isEmpty(), request);
    }

    @Test
    void explicitDeferredTargetsDoNotRequireTheDeicticForm() {
        var binding = ProposalRequestBinding.fromRequest(
                "Do not edit README.md yet. Propose changes to README.md.");
        assertEquals(List.of("README.md"), binding.targets());
        assertEquals(List.of("README.md"), binding.deferredTargets());
        assertTrue(ProposalRequestBinding.fromRequest("Propose changes to README.md.")
                .deferredRestrictions().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"if.md", "report.md", "notes.md", "docs/if.md"})
    void filenameWordsCannotChangeAffirmativeProposalMeaning(String path) {
        var direct = ProposalRequestBinding.fromRequest("Propose changes to " + path + ".");
        assertTrue(direct.requested(), path);
        assertEquals(List.of(path), direct.targets(), path);
        var unbound = ProposalRequestBinding.fromRequest("Propose changes to README.md and preserve " + path + ".");
        assertTrue(unbound.requested(), path);
        assertTrue(unbound.targets().isEmpty(), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Read README.md. Do not edit files.",
            "Explain the Calculate button failure. Do not edit files.",
            "Do not propose changes to README.md.",
            "Propose no changes to README.md.",
            "Suggest nothing for README.md.",
            "Never suggest edits to README.md.",
            "If the review passes, propose changes to README.md.",
            "Propose changes to README.md if the review passes.",
            "Propose changes to if.md if the review passes.",
            "The reviewer says propose changes to README.md.",
            "Propose changes to README.md according to the reviewer.",
            "Propose changes to notes.md according to the report.",
            "Someone requested that changes be proposed to README.md.",
            "\"Propose changes to README.md.\"",
            "`Propose changes to README.md.`",
            "> Propose changes to README.md.",
            "(Propose changes to README.md.)",
            "The example is:\nPropose changes to README.md.",
            "Do not edit README.md yet."
    })
    void prohibitionsAndNonDirectTextDoNotBecomeProposalIntent(String request) {
        var binding = ProposalRequestBinding.fromRequest(request);
        assertFalse(binding.requested(), request);
        assertTrue(binding.targets().isEmpty(), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Propose changes.",
            "Propose",
            "Propose the changes first.",
            "Propose changes to README.md or notes.md.",
            "Read README.md. Propose the changes first.",
            "Do not edit README.md yet. Read notes.md. Propose the changes first.",
            "Do not edit README.md yet. Read README.md. Propose the changes first.",
            "Do not edit README.md yet. Do not edit notes.md yet. Propose the changes first.",
            "Propose the changes first. Do not edit README.md yet.",
            "Propose changes to README.md and preserve notes.md.",
            "Propose changes to README.md. Never propose changes to README.md.",
            "Propose changes to README.md, README.md.",
            "Propose changes to ../README.md.",
            "Propose changes to C:\\project\\README.md.",
            "Propose changes to README.",
            "Propose changes to \"release notes.md\"."
    })
    void affirmativeButUnboundRequestsCannotBorrowOrGuessTargets(String request) {
        var binding = ProposalRequestBinding.fromRequest(request);
        assertTrue(binding.requested(), request);
        assertTrue(binding.targets().isEmpty(), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Propose README.md changes without editing.",
            "Suggest README.md edits without applying them.",
            "Recommend README.md updates before editing."
    })
    void alternateNounOrderClearsPriorScopeWithoutEnablingAutomaticContinuation(String request) {
        var binding = ProposalRequestBinding.fromRequest(request);
        assertTrue(binding.requested(), request);
        assertTrue(binding.targets().isEmpty(), request);
    }

    @Test
    void requestAndTargetLimitsAreFailClosed() {
        String request = "Propose changes to README.md.";
        String boundary = " ".repeat(ProposalRequestBinding.MAX_REQUEST_CHARS - request.length()) + request;
        assertEquals(List.of("README.md"), ProposalRequestBinding.fromRequest(boundary).targets());
        assertFalse(ProposalRequestBinding.fromRequest(boundary + " ").requested());
        String maximum = "Propose changes to a.md, b.md, c.md, d.md and e.md.";
        assertEquals(ActiveTaskContext.MAX_TARGETS, ProposalRequestBinding.fromRequest(maximum).targets().size());
        var excess = ProposalRequestBinding.fromRequest("Propose changes to a.md, b.md, c.md, d.md, e.md and f.md.");
        assertTrue(excess.requested());
        assertTrue(excess.targets().isEmpty());
    }

    @Test
    void malformedLexicalInputsCannotCreateBindings() {
        for (String request : List.of("Propose changes to README.md (", "Propose changes to README.md ]",
                "Propose\u0000 changes to README.md.", "Propose\u202e changes to README.md.",
                "Propose\r changes to README.md.", "Propose\ud800 changes to README.md.",
                "Propose\udc00 changes to README.md.")) {
            assertFalse(ProposalRequestBinding.fromRequest(request).requested(), request);
        }
        assertFalse(ProposalRequestBinding.fromRequest(null).requested());
        assertFalse(ProposalRequestBinding.fromRequest(" ").requested());
    }

    @Test
    void theRecordRetainsAnImmutableBoundedTargetSet() {
        var supplied = new ArrayList<>(List.of("README.md"));
        var binding = new ProposalRequestBinding(true, supplied);
        supplied.clear();
        assertEquals(List.of("README.md"), binding.targets());
        assertThrows(UnsupportedOperationException.class, () -> binding.targets().clear());
        assertEquals(List.of(), new ProposalRequestBinding(true, List.of()).targets());
        assertThrows(IllegalArgumentException.class, () -> new ProposalRequestBinding(false, List.of("README.md")));
        assertThrows(IllegalArgumentException.class, () -> new ProposalRequestBinding(true, List.of("README.md", "README.md")));
        assertThrows(IllegalArgumentException.class, () -> new ProposalRequestBinding(true, List.of("../README.md")));
        assertThrows(IllegalArgumentException.class, () -> new ProposalRequestBinding(true, List.of("README.md"),
                List.of(new ProposalRequestBinding.DeferredRestriction("notes.md", 0, 10))));
        assertThrows(IllegalArgumentException.class, () ->
                new ProposalRequestBinding.DeferredRestriction("README.md", 10, 10));
    }
}
