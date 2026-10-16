package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop.ToolOutcome;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic carrier fixtures isolate association rules. No tool or provider is executed here. */
class ToolOutcomeResultsTest {
    private static final String PROVIDER_ID = "provider-read-17";
    private static final String BODY = "[tool_result: talos.read_file]\n[error] fixture read failed\n[/tool_result]";
    private static final ContentRestrictions MODEL_WITHHELD = new ContentRestrictions(false, true, true, true);

    private static ToolOutcome outcome(String traceId) {
        return new ToolOutcome("talos.read_file", "a.txt", false, false, false,
                "", "fixture read failed", null, "READ_FAILED", null, null, null, List.of(), traceId);
    }

    private static ChatMessage.NativeToolCall call(String id) {
        return new ChatMessage.NativeToolCall(id, "talos.read_file", Map.of("path", "a.txt"));
    }

    private static ChatMessage proposal(String id) {
        return ChatMessage.assistantWithToolCalls("", List.of(call(id)));
    }

    private static final class Fixture {
        final ToolOutcomeResults results = new ToolOutcomeResults();
        final ToolOutcome outcome;
        final ChatMessage original = ChatMessage.toolResult(PROVIDER_ID, BODY);
        final List<ToolOutcome> outcomes = new ArrayList<>();
        final List<ChatMessage> messages = new ArrayList<>(List.of(proposal(PROVIDER_ID), original));

        Fixture(String traceId, boolean bind) {
            outcome = outcome(traceId);
            outcomes.add(outcome);
            if (bind) results.bind(outcome, 1, original);
        }

        int lookup() { return results.resultIndex(outcome, outcomes, messages); }
    }

    private static Fixture bound() { return new Fixture("call-1-4", true); }

    @Test void distinctTraceAndProviderIdsResolveTheExplicitCarrier() {
        var fixture = bound();
        assertNotEquals(fixture.outcome.toolCallId(), fixture.original.toolCallId());
        assertEquals(1, fixture.lookup());
    }

    @Test void blankTraceIdentityDoesNotInvalidateAnExplicitCarrier() {
        var fixture = new Fixture("", true);
        assertEquals(1, fixture.lookup());
    }

    @Test void matchingIdStringsDoNotSubstituteForAMissingAssociation() {
        var fixture = new Fixture(PROVIDER_ID, false);
        assertEquals(-1, fixture.lookup());
    }

    @Test void nullOutcomeBindingDoesNotDisturbThePriorAssociation() {
        var fixture = bound();
        fixture.results.bind(null, 1, fixture.original);
        assertEquals(1, fixture.lookup());
        assertEquals(-1, fixture.results.resultIndex(null, fixture.outcomes, fixture.messages));
    }

    @Test void valueEqualDistinctOutcomesRemainSeparateOccurrences() {
        var fixture = bound();
        var equalOutcome = outcome(fixture.outcome.toolCallId());
        assertEquals(fixture.outcome, equalOutcome);
        assertNotSame(fixture.outcome, equalOutcome);
        fixture.outcomes.add(equalOutcome);
        fixture.messages.add(proposal("provider-read-18"));
        var second = ChatMessage.toolResult("provider-read-18", BODY);
        fixture.messages.add(second);
        fixture.results.bind(equalOutcome, 3, second);
        assertEquals(1, fixture.lookup());
        assertEquals(3, fixture.results.resultIndex(equalOutcome, fixture.outcomes, fixture.messages));
    }

    @Test void anEqualReplacementCannotBorrowTheBoundOutcomeIdentity() {
        var fixture = bound();
        var replacement = outcome(fixture.outcome.toolCallId());
        fixture.outcomes.set(0, replacement);
        assertEquals(-1, fixture.lookup());
        assertEquals(-1, fixture.results.resultIndex(replacement, fixture.outcomes, fixture.messages));
    }

    @Test void duplicateOutcomeOccurrenceIsAmbiguous() {
        var fixture = bound();
        fixture.outcomes.add(fixture.outcome);
        assertEquals(-1, fixture.lookup());
    }

    @Test void absentOutcomeOccurrenceIsRejected() {
        var fixture = bound();
        fixture.outcomes.clear();
        assertEquals(-1, fixture.lookup());
    }

    @Test void repeatedBindingPermanentlyInvalidatesThatOutcome() {
        var fixture = bound();
        fixture.results.bind(fixture.outcome, 1, fixture.original);
        assertEquals(-1, fixture.lookup());
        fixture.results.bind(fixture.outcome, 1, fixture.original);
        assertEquals(-1, fixture.lookup());
    }

    @Test void rebindingOneConflictingOutcomeCannotResurrectTheOther() {
        for (int reboundIndex : List.of(1, 3)) {
            var fixture = bound();
            var other = outcome("call-1-5");
            fixture.outcomes.add(other);
            fixture.results.bind(other, 1, fixture.original);
            assertEquals(-1, fixture.lookup());
            assertEquals(-1, fixture.results.resultIndex(other, fixture.outcomes, fixture.messages));
            fixture.messages.add(proposal("provider-read-18"));
            var replacement = ChatMessage.toolResult("provider-read-18", BODY);
            fixture.messages.add(replacement);
            fixture.results.bind(other, reboundIndex, fixture.messages.get(reboundIndex));
            assertEquals(-1, fixture.lookup(), "Rebinding must retain the earlier index conflict");
            assertEquals(-1, fixture.results.resultIndex(other, fixture.outcomes, fixture.messages));
        }
    }

    @Test void indexConflictRemainsWhenOneOutcomeIsRemovedFromTheOutcomeList() {
        var fixture = bound();
        var other = outcome("call-1-5");
        fixture.outcomes.add(other);
        fixture.results.bind(other, 1, fixture.original);
        fixture.outcomes.remove(other);
        assertEquals(-1, fixture.lookup());
        var replacement = outcome("call-1-6");
        fixture.outcomes.add(replacement);
        fixture.results.bind(replacement, 1, fixture.original);
        assertEquals(-1, fixture.results.resultIndex(replacement, fixture.outcomes, fixture.messages));
    }

    @Test void invalidIndicesAreRejected() {
        for (int index : List.of(-1, 2, Integer.MAX_VALUE)) {
            var fixture = new Fixture("call-1-4", false);
            fixture.results.bind(fixture.outcome, index, fixture.original);
            assertEquals(-1, fixture.lookup(), "Invalid result index " + index);
        }
    }

    private static List<ChatMessage> malformedCarriers() {
        return Arrays.asList(null, ChatMessage.user(BODY), ChatMessage.toolResult("", BODY),
                ChatMessage.toolResult(" ", BODY), ChatMessage.toolResult(null, BODY),
                new ChatMessage("tool", BODY, List.of(call(PROVIDER_ID)), PROVIDER_ID),
                new ChatMessage("tool", null, null, PROVIDER_ID),
                ChatMessage.toolResult(PROVIDER_ID, BODY).withContentRestrictions(MODEL_WITHHELD));
    }

    @Test void malformedOriginalCarriersAreRejectedEvenWhenCurrentCarrierIsValid() {
        for (var original : malformedCarriers()) {
            var fixture = new Fixture("call-1-4", false);
            fixture.results.bind(fixture.outcome, 1, original);
            assertEquals(-1, fixture.lookup(), "Malformed original carrier: " + original);
        }
    }

    @Test void malformedCurrentCarriersAreRejected() {
        for (var current : malformedCarriers()) {
            var fixture = bound();
            fixture.messages.set(1, current);
            assertEquals(-1, fixture.lookup(), "Malformed current carrier: " + current);
        }
    }

    @Test void changedProviderIdOrBodyCannotReuseTheAssociation() {
        for (var current : List.of(ChatMessage.toolResult("changed-provider-id", BODY),
                ChatMessage.toolResult(PROVIDER_ID, "different failure body"),
                ChatMessage.toolResult(PROVIDER_ID, "[compacted: invented summary]"))) {
            var fixture = bound();
            fixture.messages.set(1, current);
            assertEquals(-1, fixture.lookup());
        }
    }

    @Test void exactCompactionSummaryKeepsTheOriginalAssociation() {
        var fixture = bound();
        var summary = ToolCallSupport.summarizeToolResult(BODY);
        assertNotEquals(BODY, summary);
        fixture.messages.set(1, ChatMessage.toolResult(PROVIDER_ID, summary));
        assertEquals(1, fixture.lookup());
        fixture.messages.set(1, fixture.original);
        assertEquals(1, fixture.lookup());
    }

    @Test void duplicateToolResultIdIsRejected() {
        var fixture = bound();
        fixture.messages.add(ChatMessage.toolResult(PROVIDER_ID, BODY));
        assertEquals(-1, fixture.lookup());
    }

    @Test void duplicateProposalIdIsRejectedWithinOrAcrossAssistantMessages() {
        var fixture = bound();
        fixture.messages.set(0, ChatMessage.assistantWithToolCalls("", List.of(call(PROVIDER_ID), call(PROVIDER_ID))));
        assertEquals(-1, fixture.lookup());
        fixture.messages.set(0, proposal(PROVIDER_ID));
        fixture.messages.add(proposal(PROVIDER_ID));
        assertEquals(-1, fixture.lookup());
    }

    @Test void proposalAfterItsResultCannotEstablishTheAssociation() {
        var fixture = bound();
        fixture.messages.set(0, ChatMessage.assistant("no proposal here"));
        fixture.messages.add(proposal(PROVIDER_ID));
        assertEquals(-1, fixture.lookup());
    }

    @Test void missingProposalIsRejected() {
        var fixture = bound();
        fixture.messages.set(0, ChatMessage.assistant("no proposal"));
        assertEquals(-1, fixture.lookup());
    }

    @Test void nullProposalAndNullMessageAreRejected() {
        var fixture = bound();
        fixture.messages.set(0, ChatMessage.assistantWithToolCalls("", Arrays.asList((ChatMessage.NativeToolCall) null)));
        assertEquals(-1, fixture.lookup());
        fixture.messages.set(0, null);
        assertEquals(-1, fixture.lookup());
    }
}
