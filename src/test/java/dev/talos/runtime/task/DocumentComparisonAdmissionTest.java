package dev.talos.runtime.task;

import dev.talos.core.diff.DocumentPairAdmission.RequestedPair;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.toolcall.DocumentComparisonTool;
import dev.talos.runtime.toolcall.ToolSurfacePlanner;
import dev.talos.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DocumentComparisonAdmissionTest {
    private static final String RETROSPECTIVE = "Have you actually run tests or changed files during this conversation, "
            + "or only inspected them? Separate observed file changes from work performed by you and from proposed fixes. "
            + "Don’t run anything now.";

    @ParameterizedTest
    @ValueSource(strings = {
            RETROSPECTIVE,
            "Have you changed files during this conversation?",
            "Which files have you changed?",
            "Describe the files you changed earlier.",
            "Did you compare a.md and b.md earlier?",
            "Have you actually compared these files?",
            "What files did you compare?",
            "Summarize your earlier comparison of these files.",
            "Summarize the comparison you performed earlier on a.md and b.md.",
            "Explain the differences you identified earlier between a.md and b.md.",
            "Summarize the earlier comparison of these files.",
            "The operator changed settings.json. Describe the work you performed.",
            "Explain why a.md changed."
    })
    void workHistoryAndSingleDocumentChangesDoNotRequireAnOrderedPair(String request) {
        assertFalse(DocumentComparisonRequest.from(request).requiresAssessment(), request);
        assertFalse(DocumentComparisonRequest.from(TaskContract.unknown(request)).requiresAssessment(), request);
        assertFalse(comparisonExposed(request), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Compare a.md and b.md.",
            "What changed between a.md and b.md?",
            "Explain how a.md differs from b.md.",
            "Please compare a.md and b.md.",
            "Compare `a.md` and `b.md`."
    })
    void realComparisonRetainsItsExactOrderedPair(String request) {
        var comparison = DocumentComparisonRequest.from(request);
        assertTrue(comparison.requiresAssessment(), request);
        assertEquals(1, comparison.requirements().size(), request);
        assertEquals(new RequestedPair("a.md", "b.md"), comparison.requirements().getFirst().pair());
        assertTrue(comparison.unresolved().isEmpty(), request);
        assertTrue(comparison.matching(new RequestedPair("b.md", "a.md")).isEmpty());
        assertTrue(comparison.matching(new RequestedPair("a.md", "other.md")).isEmpty());
        assertTrue(comparisonExposed(request), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Compare these files.",
            "Tell me the differences between these files.",
            "I need a comparison of these documents.",
            "I would like a comparison of these documents.",
            "Summarize the comparison of the current files.",
            "Explain how these files differ.",
            "What is different between these files?",
            "Show the differences between the files we made earlier.",
            "Explain the differences between a.md and b.md that you completed earlier.",
            "Give your analysis of the differences between these files.",
            // A trailing work modifier does not establish whether the comparison or its operands are historical.
            "Summarize the comparison of a.md and b.md that you performed earlier.",
            "Summarize the comparison of these files performed earlier.",
            "Compare a.md.",
            "Compare a.md with b.md.",
            "Compare a.md and b.md or c.md.",
            "Explain the differences between a.md and b.md.",
            "What changed between these files?",
            "Are these files different from what you inspected earlier?",
            "Compare \"first file.md\" with \"second file.md\".",
            "Compare a.md and b.md for security."
    })
    void realComparisonWithUnboundOperandsRemainsIncompleteWithoutToolAuthority(String request) {
        var comparison = DocumentComparisonRequest.from(request);
        assertTrue(comparison.requiresAssessment(), request);
        assertFalse(comparison.active(), request);
        assertFalse(comparison.unresolved().isEmpty(), request);
        assertFalse(comparisonExposed(request), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Read README.md and config.json, then compare them using current file evidence.",
            "Read a.md and b.md, then compare them.",
            "Inspect a.md and b.md, then distinguish the files.",
            "Read a.md, then compare it with b.md.",
            "Read a.md and b.md, and then compare these files.",
            "Read a.md and b.md then compare them."
    })
    void coordinatedComparisonRetainsAnUnresolvedDependencyWithoutPairAuthority(String request) {
        var comparison = DocumentComparisonRequest.from(request);
        assertTrue(comparison.requiresAssessment(), request);
        assertFalse(comparison.active(), request);
        assertEquals(1, comparison.unresolved().size(), request);
        assertEquals(ReadOnlyRequestMeaning.compile(request).requirements().slots().getLast().id(),
                comparison.unresolved().getFirst(), "The original unresolved result identity must be retained");
        assertFalse(comparisonExposed(request), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Read a.md and b.md, then do not compare them.",
            "If needed read a.md and b.md, then compare them.",
            "\"Read a.md and b.md, then compare them.\"",
            "Propose reading a.md and b.md, then comparing them.",
            "You read a.md and b.md, then compared them earlier.",
            "Read a.md and b.md, then describe the comparison you performed earlier.",
            "Read a.md and b.md. Then compare them.",
            "Read a.md and b.md, describe the project, then compare them.",
            "Do not read a.md and b.md, then compare them.",
            "Read a.md and b.md, then compare them if needed."
    })
    void coordinatedMentionsDoNotBecomeCurrentComparisonRequests(String request) {
        assertFalse(DocumentComparisonRequest.from(request).requiresAssessment(), request);
        assertFalse(comparisonExposed(request), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Do not compare a.md and b.md.",
            "If needed compare a.md and b.md.",
            "An example follows:\nCompare a.md and b.md.",
            "\"Compare a.md and b.md.\"",
            "`Compare a.md and b.md`",
            "> Compare a.md and b.md.",
            "Explain the difference between 12 and 30."
    })
    void excludedEnvelopesAndNonDocumentComparisonsStayExcluded(String request) {
        assertFalse(DocumentComparisonRequest.from(request).requiresAssessment(), request);
        assertFalse(comparisonExposed(request), request);
    }

    @Test
    void historicalQuestionDoesNotSuppressARealComparisonInAnotherRegion() {
        String request = RETROSPECTIVE + " Compare a.md and b.md.";
        var comparison = DocumentComparisonRequest.from(request);
        assertEquals(1, comparison.requirements().size());
        assertEquals(new RequestedPair("a.md", "b.md"), comparison.requirements().getFirst().pair());
        assertTrue(comparison.unresolved().isEmpty(), "Only the requested comparison may impose a pair obligation");
        assertTrue(comparisonExposed(request));
    }

    @Test
    void historicalQuestionDoesNotSuppressAnUnresolvedComparisonInAnotherRegion() {
        String request = "Which files have you changed? Compare these files.";
        var comparison = DocumentComparisonRequest.from(request);
        assertTrue(comparison.requiresAssessment());
        assertFalse(comparison.active());
        assertEquals(1, comparison.unresolved().size(), "Only the requested comparison may impose a pair obligation");
        assertFalse(comparisonExposed(request));
    }

    @Test
    void historicalComparisonSummaryDoesNotSuppressCurrentComparison() {
        String request = "Summarize the comparison you performed earlier on a.md and b.md. Compare a.md and b.md.";
        var comparison = DocumentComparisonRequest.from(request);
        assertEquals(1, comparison.requirements().size());
        assertTrue(comparison.unresolved().isEmpty());
        assertTrue(comparisonExposed(request));
    }

    private static boolean comparisonExposed(String request) {
        var registry = new ToolRegistry();
        var tool = new DocumentComparisonTool();
        registry.register(tool);
        return ToolSurfacePlanner.plan(TaskContract.unknown(request), ExecutionPhase.INSPECT, registry)
                .nativeToolNames().contains(tool.name());
    }
}
