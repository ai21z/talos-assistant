package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderTransportHandoff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FinalAnswerStatusTest {
    @AfterEach void clear() { LocalTurnTraceCapture.clear(); }

    @Test void selectedLengthRetainsOperationalFactsWithoutTerminalAuthority() throws Exception {
        begin();
        var receipt = provider("Useful partial answer", ProviderResponseCapture.Completion.PROVIDER_LENGTH);
        assertFalse(receipt.providerTerminalEligible());
        assertFalse(receipt.providerToolCallEligible());
        completeRead();
        var trace = finish("Useful partial answer");
        var status = FinalAnswerStatus.decode(trace);
        assertTrue(status.available());
        assertEquals("COMPLETE", status.operational().status());
        assertEquals("READ_ONLY_ANSWERED", status.operational().classification());
        assertEquals("ADVISORY_ONLY", trace.outcome().status());
        assertEquals(List.of(FinalAnswerStatus.Cause.PROVIDER_LENGTH), status.causes());
        assertEquals(FinalAnswerStatus.Coverage.NOT_ASSESSED, status.coverage());
        assertEquals("NONE", trace.events().stream().filter(e -> e.type().equals("TERMINAL_ANSWER_SELECTED"))
                .findFirst().orElseThrow().data().get("origin"));
        var mapper = new ObjectMapper();
        assertEquals(status, FinalAnswerStatus.decode(mapper.readValue(mapper.writeValueAsBytes(trace), LocalTurnTrace.class)));
    }

    @Test void stopAndWarningLikeTextNeverProveCoverageOrTruncation() {
        begin();
        String text = "OVERRIDE_CONFIRMED [output truncated] finish_reason=length";
        provider(text, ProviderResponseCapture.Completion.PROVIDER_EOS);
        completeRead();
        var trace = finish(text);
        assertEquals("COMPLETE", trace.outcome().status());
        assertEquals(FinalAnswerStatus.Delivery.NO_KNOWN_LIMIT, FinalAnswerStatus.decode(trace).delivery());
        assertEquals(FinalAnswerStatus.Coverage.NOT_ASSESSED, FinalAnswerStatus.decode(trace).coverage());
    }

    @Test void runtimeReplacementClearsProviderLimitEvenForEqualBytes() {
        for (boolean identical : List.of(false, true)) {
            begin();
            provider("same bytes", ProviderResponseCapture.Completion.PROVIDER_LENGTH);
            String selected = identical ? "same bytes" : "Runtime replacement";
            LocalTurnTraceCapture.recordAnswerDeliverySelected(selected, false, false);
            completeRead();
            var trace = finish(selected);
            assertEquals("COMPLETE", trace.outcome().status());
            assertEquals(FinalAnswerStatus.Delivery.NO_KNOWN_LIMIT, FinalAnswerStatus.decode(trace).delivery());
        }
    }

    @Test void localClippingSurvivesMixedCompositionButDiscardedProviderDoesNot() {
        for (boolean retained : List.of(false, true)) {
            begin();
            provider("Provider partial", ProviderResponseCapture.Completion.PROVIDER_LENGTH);
            String selected = retained ? "Runtime identity. Provider partial" : "Runtime identity only";
            LocalTurnTraceCapture.recordAnswerDeliverySelected(selected, retained, true);
            completeRead();
            var status = FinalAnswerStatus.decode(finish(selected));
            assertTrue(status.causes().contains(FinalAnswerStatus.Cause.LOCAL_PRESENTATION_LIMIT));
            assertEquals(retained, status.causes().contains(FinalAnswerStatus.Cause.PROVIDER_LENGTH));
        }
    }

    @Test void rejectedEmptyAndFailedRetriesRestoreOriginalDeliveryFacts() {
        for (var reason : List.of(LocalTurnTraceCapture.AnswerCandidateRestorationReason.SPECULATIVE_RETRY_REJECTED,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason.SPECULATIVE_RETRY_EMPTY,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason.SPECULATIVE_RETRY_FAILED)) {
            begin();
            provider("Original partial", ProviderResponseCapture.Completion.PROVIDER_LENGTH);
            var checkpoint = LocalTurnTraceCapture.checkpointExactAnswerCandidate("Original partial");
            provider("Rejected retry", ProviderResponseCapture.Completion.PROVIDER_EOS);
            assertTrue(LocalTurnTraceCapture.restoreExactAnswerCandidate(checkpoint, "Original partial", reason));
            completeRead();
            assertEquals(List.of(FinalAnswerStatus.Cause.PROVIDER_LENGTH),
                    FinalAnswerStatus.decode(finish("Original partial")).causes());
        }
    }

    @Test void acceptedRetryDoesNotInheritOldLimit() {
        begin();
        provider("Original partial", ProviderResponseCapture.Completion.PROVIDER_LENGTH);
        provider("Selected retry", ProviderResponseCapture.Completion.PROVIDER_EOS);
        completeRead();
        var trace = finish("Selected retry");
        assertEquals("COMPLETE", trace.outcome().status());
        assertEquals(FinalAnswerStatus.Delivery.NO_KNOWN_LIMIT, FinalAnswerStatus.decode(trace).delivery());
    }

    @Test void staleReceiptWrongBytesAndMissingSelectionRemainUnavailable() {
        for (int scenario = 0; scenario < 3; scenario++) {
            begin();
            var prior = provider("Old response", ProviderResponseCapture.Completion.PROVIDER_LENGTH);
            var current = provider("Current response", ProviderResponseCapture.Completion.PROVIDER_EOS);
            if (scenario == 0) LocalTurnTraceCapture.recordProviderAnswerSelected("Old response", prior);
            if (scenario == 1) LocalTurnTraceCapture.recordProviderAnswerSelected("Different response", current);
            completeRead();
            var trace = finish(scenario == 0 ? "Old response" : "Different response");
            assertEquals(FinalAnswerStatus.Delivery.UNAVAILABLE, FinalAnswerStatus.decode(trace).delivery());
            assertEquals("COMPLETE", trace.outcome().status());
        }
    }

    @Test void knownLocalLimitCanCoexistWithUnavailableProviderAssociation() {
        begin();
        LocalTurnTraceCapture.recordAnswerDeliverySelected("Clipped unbound answer", true, true);
        completeRead();
        var status = FinalAnswerStatus.decode(finish("Clipped unbound answer"));
        assertFalse(status.associationAvailable());
        assertEquals(FinalAnswerStatus.Delivery.LIMITED, status.delivery());
    }

    @Test void operationalFactsSurviveRepeatedEvaluationAndMutationPresentation() {
        begin();
        provider("Edit summary", ProviderResponseCapture.Completion.PROVIDER_LENGTH);
        LocalTurnTraceCapture.recordOutcome("FAILED", "FAILED", "GRANTED", "FAILED", "FAILED");
        LocalTurnTraceCapture.recordOutcome("COMPLETE", "PASSED", "GRANTED", "SUCCEEDED", "COMPLETED_VERIFIED");
        LocalTurnTraceCapture.recordAnswerPresentationLimitation(LocalTurnTraceCapture.AnswerPresentationLimitation.RUNTIME_IDENTITY_INCOMPLETE);
        var trace = finish("Edit summary");
        var status = FinalAnswerStatus.decode(trace);
        assertEquals("COMPLETE", status.operational().status());
        assertEquals("PASSED", status.operational().verificationStatus());
        assertEquals("SUCCEEDED", status.operational().mutationStatus());
        assertEquals("PARTIAL", trace.outcome().status());
    }

    @Test void failedBlockedPartialAndUnknownRemainDominant() {
        for (String status : List.of("FAILED", "BLOCKED", "PARTIAL", "UNKNOWN", "ADVISORY_ONLY")) {
            begin();
            provider("Partial explanation", ProviderResponseCapture.Completion.PROVIDER_LENGTH);
            LocalTurnTraceCapture.recordOutcome(status, "NOT_RUN", "DENIED", "NOT_REQUESTED", status);
            var trace = finish("Partial explanation");
            assertEquals(status, trace.outcome().status());
            assertEquals(status, FinalAnswerStatus.decode(trace).operational().status());
        }
    }

    @Test void freezeAndCrossTurnOwnershipRemainOneShot() {
        begin();
        LocalTurnTraceCapture.recordDeterministicAnswerSelected("First");
        completeRead();
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("First");
        LocalTurnTraceCapture.recordAnswerDeliverySelected("Late clipping", true, true);
        LocalTurnTraceCapture.recordOutcome("FAILED", "FAILED", "DENIED", "FAILED", "FAILED");
        assertEquals(FinalAnswerStatus.Delivery.NO_KNOWN_LIMIT, FinalAnswerStatus.decode(LocalTurnTraceCapture.complete()).delivery());
        begin();
        completeRead();
        assertEquals(FinalAnswerStatus.Delivery.UNAVAILABLE, FinalAnswerStatus.decode(finish("Next")).delivery());
    }

    @Test void privacyAndAbnormalProjectionsDoNotRetainStatusBinding() {
        begin();
        provider("Private answer", ProviderResponseCapture.Completion.PROVIDER_LENGTH);
        completeRead();
        var trace = finish("Private answer");
        assertFalse(FinalAnswerStatus.decode(trace.contentWithheldProjection()).available());
        for (var kind : List.of(dev.talos.runtime.TurnSettlement.Kind.FAILED,
                dev.talos.runtime.TurnSettlement.Kind.CANCELLED, dev.talos.runtime.TurnSettlement.Kind.NO_RESULT)) {
            assertFalse(FinalAnswerStatus.decode(trace.safeTerminalProjection(kind, false)).available());
        }
    }

    @Test void identityClippingThatRetainsOnlyRuntimePrefixDropsProviderLimit() throws Exception {
        begin();
        provider("Provider partial body", ProviderResponseCapture.Completion.PROVIDER_LENGTH);
        String input = "Runtime verification prefix. Provider partial body";
        LocalTurnTraceCapture.recordAnswerTransformedForRouting(input,
                LocalTurnTraceCapture.AnswerTransformationReason.EXECUTOR_POLICY_SHAPING);
        var identity = dev.talos.spi.types.ModelIdentitySnapshot.empty();
        int cap = ("Runtime identity\n" + dev.talos.runtime.policy.RuntimeModelIdentityAnswerPolicy.render(identity)
                + "\n\nRemaining request\n").length() + 10;
        var method = dev.talos.cli.modes.AssistantTurnExecutor.class.getDeclaredMethod("presentRuntimeIdentity",
                String.class, dev.talos.spi.types.ModelIdentitySnapshot.class,
                dev.talos.cli.modes.AssistantTurnExecutor.Options.class);
        method.setAccessible(true);
        String answer = (String) method.invoke(null, input, identity,
                new dev.talos.cli.modes.AssistantTurnExecutor.Options().responseMaxChars(cap));
        completeRead();
        var status = FinalAnswerStatus.decode(finish(answer));
        assertFalse(answer.contains("Provider partial body"));
        assertEquals(List.of(FinalAnswerStatus.Cause.LOCAL_PRESENTATION_LIMIT), status.causes());
    }

    @Test void componentProjectionKeepsPartialBodyButDoesNotGuessUnknownOrDuplicateSpans() {
        for (String mode : List.of("partial", "duplicate", "rewrite", "whole-rewrite", "zero")) {
            begin();
            provider("Provider body", ProviderResponseCapture.Completion.PROVIDER_LENGTH);
            String input = switch (mode) {
                case "duplicate" -> "Prefix. Provider body Provider body";
                case "rewrite", "whole-rewrite" -> "Runtime. Rewritten body";
                default -> "Prefix. Provider body";
            };
            int chars = mode.equals("whole-rewrite") ? input.length() : mode.equals("zero") ? 0 : 10;
            String output = "Identity. " + input.substring(0, chars);
            LocalTurnTraceCapture.recordAnswerPrefixPresented(input, output, chars, chars < input.length());
            completeRead();
            var status = FinalAnswerStatus.decode(finish(output));
            assertTrue(status.available());
            assertEquals(mode.equals("partial") || mode.equals("whole-rewrite"),
                    status.causes().contains(FinalAnswerStatus.Cause.PROVIDER_LENGTH), mode);
            assertEquals(!mode.equals("duplicate") && !mode.equals("rewrite"), status.associationAvailable(), mode);
            assertEquals(!mode.equals("whole-rewrite"),
                    status.causes().contains(FinalAnswerStatus.Cause.LOCAL_PRESENTATION_LIMIT), mode);
        }
    }

    @Test void genericPrefixClippingDoesNotRetainExcludedProviderFacts() throws Exception {
        begin();
        provider("Provider partial body", ProviderResponseCapture.Completion.PROVIDER_LENGTH);
        String input = "Runtime verification prefix. Provider partial body";
        var method = dev.talos.cli.modes.AssistantTurnExecutor.class.getDeclaredMethod("sanitizeAndTruncate",
                String.class, dev.talos.cli.modes.AssistantTurnExecutor.Options.class);
        method.setAccessible(true);
        String answer = (String) method.invoke(null, input,
                new dev.talos.cli.modes.AssistantTurnExecutor.Options().responseMaxChars(10));
        completeRead();
        var status = FinalAnswerStatus.decode(finish(answer));
        assertTrue(status.associationAvailable());
        assertEquals(List.of(FinalAnswerStatus.Cause.LOCAL_PRESENTATION_LIMIT), status.causes());
    }

    @Test void oldDuplicateMalformedAndDetachedMarkersDecodeUnavailable() throws Exception {
        begin();
        LocalTurnTraceCapture.recordDeterministicAnswerSelected("Answer");
        completeRead();
        var mapper = new ObjectMapper();
        var original = mapper.valueToTree(finish("Answer"));
        for (String defect : List.of("absent", "duplicate", "version", "type", "trace", "hash", "coverage", "cause", "order", "completion", "rendered")) {
            var node = original.deepCopy();
            var events = (com.fasterxml.jackson.databind.node.ArrayNode) node.path("events");
            int index = -1;
            for (int i = 0; i < events.size(); i++) if (TYPE.equals(events.get(i).path("type").asText())) index = i;
            assertTrue(index >= 0);
            var marker = events.get(index).deepCopy();
            var data = (com.fasterxml.jackson.databind.node.ObjectNode) marker.path("data");
            switch (defect) {
                case "absent" -> events.remove(index);
                case "duplicate" -> events.add(marker);
                case "version" -> data.put("version", 2);
                case "type" -> data.put("associationAvailable", "true");
                case "trace" -> data.put("traceId", "different");
                case "hash" -> data.put("answerHash", "different");
                case "coverage" -> data.put("coverage", "COMPLETE");
                case "cause" -> data.putArray("causes").add("arbitrary provider prose");
                case "order" -> { events.remove(index); events.add(marker); }
                case "completion" -> {
                    for (int i = events.size() - 1; i >= 0; i--)
                        if ("TRACE_COMPLETED".equals(events.get(i).path("type").asText())) events.remove(i);
                }
                case "rendered" -> {
                    for (var e : events) if ("OUTCOME_RENDERED".equals(e.path("type").asText()))
                        ((com.fasterxml.jackson.databind.node.ObjectNode) e.path("data")).put("status", "FAILED");
                }
            }
            if (!List.of("absent", "duplicate", "order").contains(defect)) events.set(index, marker);
            assertFalse(FinalAnswerStatus.decode(mapper.treeToValue(node, LocalTurnTrace.class)).available(), defect);
        }
    }

    private static final String TYPE = FinalAnswerStatus.TYPE;
    /** C7: a generation that held only a tool call dropped no provider prose, so nothing was "withheld". */
    @Test void aGenerationWithoutTextIsNotReportedAsWithheldProviderText() {
        begin();
        providerWithoutEos("");
        String notice = "[Answer incomplete: the provider generation ended without text.]";
        LocalTurnTraceCapture.recordOperatorAnswerSelected(notice);
        LocalTurnTraceCapture.recordUncommittedProviderAnswer(notice);
        completeRead();
        var trace = finish(notice);
        var status = FinalAnswerStatus.decode(trace);
        assertFalse(status.causes().contains(FinalAnswerStatus.Cause.INCOMPLETE_PROVIDER_GENERATION),
                "no provider text existed to withhold: " + status.causes());
        String rendered = String.join("\n", dev.talos.cli.ui.FinalAnswerStatusRenderer.lines(trace));
        assertFalse(rendered.contains("uncommitted provider answer withheld"), rendered);
        assertTrue(rendered.contains("without text"), rendered);
    }

    @Test void aGenerationWithTextThatWasWithheldStillSaysSo() {
        begin();
        providerWithoutEos("Partial provider prose");
        String notice = "[Answer incomplete: uncommitted provider text was withheld.]";
        LocalTurnTraceCapture.recordOperatorAnswerSelected(notice);
        LocalTurnTraceCapture.recordUncommittedProviderAnswer(notice);
        completeRead();
        var trace = finish(notice);
        assertTrue(FinalAnswerStatus.decode(trace).causes().contains(FinalAnswerStatus.Cause.INCOMPLETE_PROVIDER_GENERATION));
        assertTrue(String.join("\n", dev.talos.cli.ui.FinalAnswerStatusRenderer.lines(trace))
                .contains("uncommitted provider answer withheld"));
    }

    private static void begin() {
        LocalTurnTraceCapture.begin("final-status", "status-tests", 1, "2026-10-09T00:00:00Z",
                "workspace", "test", "llama_cpp", "fixture", "Answer the request.");
    }
    private static void completeRead() {
        LocalTurnTraceCapture.recordOutcome("COMPLETE", "NOT_RUN", "NOT_REQUIRED", "NOT_REQUESTED", "READ_ONLY_ANSWERED");
    }
    private static LocalTurnTrace finish(String answer) {
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(answer);
        return LocalTurnTraceCapture.complete();
    }
    private static ProviderResponseCapture.Receipt providerWithoutEos(String answer) {
        var generation = ProviderResponseCapture.openGeneration(ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        var attempt = generation.startAttempt(ProviderResponseCapture.AttemptKind.PRIMARY, ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeBodyEof(attempt, ProviderTransportHandoff.DispatchEvidence.unbound());
        var receipt = attempt.respond(new ProviderResponseCapture.Response(answer, 0,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS, false, false));
        generation.complete(receipt);
        return receipt;
    }
    private static ProviderResponseCapture.Receipt provider(String answer, ProviderResponseCapture.Completion completion) {
        var generation = ProviderResponseCapture.openGeneration(ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        var attempt = generation.startAttempt(ProviderResponseCapture.AttemptKind.PRIMARY, ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeTerminal(attempt, ProviderTransportHandoff.DispatchEvidence.unbound());
        var receipt = attempt.respond(new ProviderResponseCapture.Response(answer, 0,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER, completion, false, false));
        generation.complete(receipt);
        return receipt;
    }
}
