package dev.talos.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolMutationEffects;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LA-F7: the dispatch witness of a tool outcome comes from its producer and is never minted by deserialization. */
class ToolOutcomeDispatchTest {
    @Test
    void olderConstructorsCarryAnUnknownDispatch() {
        var outcome = new ToolCallLoop.ToolOutcome("talos.edit_file", "a.md", false, true, false, "", "boom");

        assertEquals(TurnRecord.ToolCallDisposition.UNKNOWN, outcome.dispatch().disposition());
        assertFalse(outcome.dispatch().neverRan());
    }

    @Test
    void aCallThatNeverRanCannotReportSuccessOrFileEffects() {
        var refused = new ToolCallLoop.ToolOutcome("talos.edit_file", "a.md", false, true, false, "", "refused");
        var succeeded = new ToolCallLoop.ToolOutcome("talos.edit_file", "a.md", true, true, false, "ok", "");

        assertThrows(IllegalArgumentException.class, () -> succeeded.withDispatch(ToolCallDispatch.blocked("a.md")));
        assertThrows(IllegalArgumentException.class, () -> refused
                .withMutationEffects(new ToolMutationEffects(List.of("a.md"), List.of()))
                .withDispatch(ToolCallDispatch.blocked("a.md")));
        assertThrows(IllegalArgumentException.class, () -> refused
                .withDispatch(ToolCallDispatch.blocked("a.md"))
                .withMutationEffects(new ToolMutationEffects(List.of(), List.of("a.md"))));
    }

    @Test
    void copiesKeepTheDispatchAndOnlyACallThatNeverRanKeepsItsProposedTarget() {
        var refused = new ToolCallLoop.ToolOutcome("talos.edit_file", "", false, true, false, "", "refused")
                .withDispatch(ToolCallDispatch.blocked(" docs\\a.md "));

        assertEquals("docs/a.md", refused.dispatch().proposedTarget());
        assertTrue(refused.withFailureReason(ToolFailureReason.EDIT_NO_CHANGE).dispatch().neverRan());
        assertTrue(refused.withMutationEffects(ToolMutationEffects.none()).dispatch().neverRan());
        assertEquals("", ToolCallDispatch.of(TurnRecord.ToolCallDisposition.EXECUTED, "a.md").proposedTarget());
        assertEquals("", ToolCallDispatch.of(TurnRecord.ToolCallDisposition.UNKNOWN, "a.md").proposedTarget());
        assertEquals("", ToolCallDispatch.blocked("a\u0007.md").proposedTarget());
        assertEquals("", ToolCallDispatch.blocked("x".repeat(241)).proposedTarget());
    }

    @Test
    void aJsonRoundTripNeverMintsANeverRanFact() throws Exception {
        var refused = new ToolCallLoop.ToolOutcome("talos.edit_file", "a.md", false, true, false, "", "refused")
                .withDispatch(ToolCallDispatch.blocked("a.md"));
        ObjectMapper json = new ObjectMapper();

        String text = json.writeValueAsString(refused);
        var restored = json.readValue(text, ToolCallLoop.ToolOutcome.class);

        assertFalse(text.contains("dispatch"), text);
        assertEquals(TurnRecord.ToolCallDisposition.UNKNOWN, restored.dispatch().disposition());
    }
}
