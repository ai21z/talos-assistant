package dev.talos.runtime.trace;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ToolSpec;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderResponseTraceHardeningTest {
    private static final String CANARY = "PROVIDER_SECRET_CANARY_4c0b2c";

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void forgedReceiptCannotCreateAnswerOrToolProvenanceLink() {
        begin("trace-forged-receipt");
        String answer = "forged answer";
        ProviderResponseCapture.Receipt forged = new ProviderResponseCapture.Receipt(
                "trace-forged-receipt-generation-1",
                "trace-forged-receipt-generation-1-attempt-1",
                1,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                true,
                true,
                TraceRedactor.hash(answer));

        LocalTurnTraceCapture.recordProviderAnswerSelectedAfterTransformation(
                answer,
                forged,
                LocalTurnTraceCapture.AnswerTransformationReason
                        .PROVIDER_RESPONSE_NORMALIZATION);
        LocalTurnTraceCapture.recordToolCallParsed(
                "tool_loop",
                new ToolCall("talos.read_file", Map.of("path", "README.md")),
                forged,
                1);
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(answer);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        TurnTraceEvent terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        assertEquals("NONE", terminal.data().get("origin"));
        assertFalse(terminal.data().containsKey("sourceGenerationId"));
        assertEquals("PROVIDER_RESPONSE_NORMALIZATION",
                event(trace, "ANSWER_TRANSFORMED").data().get("reasonCode"));
        TurnTraceEvent parsed = event(trace, "TOOL_CALL_PARSED");
        assertFalse(parsed.data().containsKey("providerGenerationId"));
        assertFalse(parsed.data().containsKey("providerAttemptId"));
        assertFalse(parsed.data().containsKey("providerProposalOrdinal"));
    }

    @Test
    void legacyUnboundDispatchOmitsTypedReceiptFields() {
        begin("trace-unbound-dispatch");
        ProviderResponseCapture.Generation generation =
                ProviderResponseCapture.openGeneration(
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        ProviderResponseCapture.Receipt receipt = attempt.respond(
                new ProviderResponseCapture.Response(
                        "",
                        List.of(),
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false,
                        false,
                        false));
        generation.complete(receipt);

        TurnTraceEvent dispatch = event(
                LocalTurnTraceCapture.complete(), "GENERATION_REQUEST_DISPATCHED");

        assertFalse(dispatch.data().containsKey("providerBodyHash"));
        assertFalse(dispatch.data().containsKey("dispatchedNativeTools"));
    }

    @Test
    void dispatchEvidenceCannotBeHalfBound() {
        assertThrows(IllegalArgumentException.class, () ->
                new ProviderTransportHandoff.DispatchEvidence(
                        "", List.of("talos.read_file")));
    }

    @Test
    void typedDispatchEvidenceCrossesCaptureAndTraceRecorderWithoutReconstruction() {
        begin("trace-bound-dispatch");
        ProviderTransportHandoff.DispatchEvidence evidence =
                ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                        "{\"messages\":[{\"role\":\"user\",\"content\":\"inspect\"}]}",
                        List.of(new ToolSpec(
                                "talos.read_file", "read", "{\"type\":\"object\"}")));
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE,
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                        List.of("talos.read_file"),
                        ToolChoiceMode.AUTO,
                        ""));
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeTerminal(attempt, evidence);
        ProviderResponseCapture.Receipt receipt = attempt.respond(
                new ProviderResponseCapture.Response(
                        "answer",
                        List.of(),
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false,
                        false,
                        false));
        generation.complete(receipt);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent dispatch = event(trace, "GENERATION_REQUEST_DISPATCHED");
        TurnTraceEvent completed = event(trace, "GENERATION_COMPLETED");

        assertEquals(evidence.providerBodyHash(), dispatch.data().get("providerBodyHash"));
        assertEquals(evidence.nativeTools(), dispatch.data().get("dispatchedNativeTools"));
        assertEquals(completed.data().get("generationId"),
                dispatch.data().get("generationId"));
        assertEquals(completed.data().get("selectedAttemptId"),
                dispatch.data().get("attemptId"));
    }

    @Test
    void selectedNativeCallLinksToItsExactRawProposalHashes() {
        begin("trace-selected-proposal");
        ChatMessage.NativeToolCall nativeCall = new ChatMessage.NativeToolCall(
                "call-read", "talos.read_file", Map.of("path", "README.md"));
        ProviderResponseCapture.Receipt receipt = capture(List.of(nativeCall));

        LocalTurnTraceCapture.recordToolCallParsed(
                "tool_loop",
                new ToolCall("talos.read_file", Map.of("path", "README.md")),
                receipt,
                1);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        TurnTraceEvent raw = event(trace, "PROVIDER_TOOL_PROPOSAL_CAPTURED");
        TurnTraceEvent parsed = event(trace, "TOOL_CALL_PARSED");
        TurnTraceEvent completed = event(trace, "GENERATION_COMPLETED");
        assertEquals(completed.data().get("generationId"),
                parsed.data().get("providerGenerationId"));
        assertEquals(completed.data().get("selectedAttemptId"),
                parsed.data().get("providerAttemptId"));
        assertEquals(raw.data().get("proposalOrdinal"),
                parsed.data().get("providerProposalOrdinal"));
        assertEquals(raw.data().get("toolNameHash"),
                parsed.data().get("providerToolNameHash"));
        assertEquals(raw.data().get("argumentsHash"),
                parsed.data().get("providerArgumentsHash"));
        assertEquals(parsed.data().get("toolNameHash"),
                parsed.data().get("providerParsedToolNameHash"));
        assertEquals(parsed.data().get("argumentsHash"),
                parsed.data().get("providerParsedArgumentsHash"));
    }

    @Test
    void differentParsedCallCannotInheritSelectedNativeProposal() {
        begin("trace-mismatched-proposal");
        ChatMessage.NativeToolCall nativeCall = new ChatMessage.NativeToolCall(
                "call-read", "talos.read_file", Map.of("path", "README.md"));
        ProviderResponseCapture.Receipt receipt = capture(List.of(nativeCall));

        LocalTurnTraceCapture.recordToolCallParsed(
                "tool_loop",
                new ToolCall("talos.write_file", Map.of(
                        "path", "other.txt", "content", "not provider authored")),
                receipt,
                1);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        TurnTraceEvent parsed = event(trace, "TOOL_CALL_PARSED");
        assertFalse(parsed.data().containsKey("providerGenerationId"));
        assertFalse(parsed.data().containsKey("providerAttemptId"));
        assertFalse(parsed.data().containsKey("providerProposalOrdinal"));
        assertFalse(parsed.data().containsKey("providerToolNameHash"));
        assertFalse(parsed.data().containsKey("providerArgumentsHash"));
        assertFalse(parsed.data().containsKey("providerParsedToolNameHash"));
        assertFalse(parsed.data().containsKey("providerParsedArgumentsHash"));
    }

    @Test
    void nativeContainerAndScalarConversionMatchesRuntimeCallShape() {
        begin("trace-native-conversion-parity");
        ChatMessage.NativeToolCall nativeCall = new ChatMessage.NativeToolCall(
                "call-batch", "talos.batch_workspace_apply", Map.of(
                        "operations", List.of(Map.of("op", "mkdir", "path", "docs")),
                        "dry_run", true,
                        "attempt", 2));
        ProviderResponseCapture.Receipt receipt = capture(List.of(nativeCall));
        ToolCall converted = dev.talos.runtime.toolcall.ToolCallSupport
                .convertNativeToolCalls(List.of(nativeCall)).getFirst();

        LocalTurnTraceCapture.recordToolCallParsed(
                "tool_loop", converted, receipt, 1);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        TurnTraceEvent parsed = event(trace, "TOOL_CALL_PARSED");
        assertEquals(receipt.generationId(), parsed.data().get("providerGenerationId"));
        assertEquals(receipt.attemptId(), parsed.data().get("providerAttemptId"));
        assertEquals(parsed.data().get("argumentsHash"),
                parsed.data().get("providerParsedArgumentsHash"));
    }

    @Test
    void earlierSelectedReceiptBecomesStaleAfterLaterForegroundGeneration() {
        begin("trace-stale-selected-receipt");
        ProviderResponseCapture.Receipt earlier = capture(List.of(
                new ChatMessage.NativeToolCall(
                        "call-old", "talos.read_file", Map.of("path", "old.txt"))));
        ProviderResponseCapture.Receipt current = capture(List.of(
                new ChatMessage.NativeToolCall(
                        "call-current", "talos.read_file", Map.of("path", "current.txt"))));

        LocalTurnTraceCapture.recordToolCallParsed(
                "tool_loop",
                new ToolCall("talos.read_file", Map.of("path", "old.txt")),
                earlier,
                1);
        LocalTurnTraceCapture.recordToolCallParsed(
                "tool_loop",
                new ToolCall("talos.read_file", Map.of("path", "current.txt")),
                current,
                1);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        List<TurnTraceEvent> parsed = trace.events().stream()
                .filter(candidate -> "TOOL_CALL_PARSED".equals(candidate.type()))
                .toList();

        assertFalse(parsed.getFirst().data().containsKey("providerGenerationId"));
        assertEquals(current.generationId(),
                parsed.getLast().data().get("providerGenerationId"));
        assertEquals(current.attemptId(),
                parsed.getLast().data().get("providerAttemptId"));
    }

    @Test
    void providerNamesAndKeysAreHashedAndTraceEventCountsAreBounded() {
        begin("trace-provider-redaction");
        List<ChatMessage.NativeToolCall> calls = new ArrayList<>();
        for (int index = 0; index < ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS + 7; index++) {
            calls.add(new ChatMessage.NativeToolCall(
                    "call-" + CANARY + '-' + index,
                    "tool-" + CANARY + '-' + index,
                    Map.of("key-" + CANARY + '-' + index, "value-" + CANARY)));
        }
        capture(calls);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        List<TurnTraceEvent> proposals = trace.events().stream()
                .filter(candidate -> "PROVIDER_TOOL_PROPOSAL_CAPTURED".equals(candidate.type()))
                .toList();
        assertEquals(ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS, proposals.size());
        TurnTraceEvent response = event(trace, "GENERATION_RESPONSE_CAPTURED");
        assertEquals(calls.size(), response.data().get("nativeToolCallCount"));
        assertEquals(ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS,
                response.data().get("capturedNativeToolProposalCount"));
        assertEquals(true, response.data().get("nativeToolProposalsTruncated"));
        assertTrue(proposals.stream().allMatch(proposal ->
                "UNKNOWN".equals(proposal.data().get("toolNameClassification"))));
        assertTrue(proposals.stream().allMatch(proposal ->
                ((List<?>) proposal.data().get("argumentNameHashes")).size() == 1));
        assertFalse(trace.events().toString().contains(CANARY), trace.events().toString());
    }

    private static ProviderResponseCapture.Receipt capture(
            List<ChatMessage.NativeToolCall> calls) {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE,
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                        List.of("talos.read_file"),
                        ToolChoiceMode.AUTO,
                        ""));
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeTerminal(
                attempt,
                ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                        "provider-response-trace-hardening-body",
                        List.of(new ToolSpec(
                                "talos.read_file",
                                "fixture tool",
                                "{}"))));
        ProviderResponseCapture.Receipt receipt = attempt.respond(
                new ProviderResponseCapture.Response(
                        "",
                        calls,
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false,
                        false,
                        false));
        generation.complete(receipt);
        return receipt;
    }

    private static TurnTraceEvent event(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(candidate -> type.equals(candidate.type()))
                .findFirst()
                .orElseThrow();
    }

    private static void begin(String traceId) {
        LocalTurnTraceCapture.begin(
                traceId,
                "session",
                1,
                "2026-07-16T00:00:00Z",
                "workspace",
                "auto",
                "test",
                "model",
                "provider provenance hardening");
    }
}
