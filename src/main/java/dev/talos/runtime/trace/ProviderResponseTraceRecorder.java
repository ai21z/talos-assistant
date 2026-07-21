package dev.talos.runtime.trace;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.tools.ToolAliasPolicy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes the redacted provider-attempt ledger contract used by trace schemas v3/v4. */
final class ProviderResponseTraceRecorder {
    static final int LEDGER_VERSION = 1;

    private ProviderResponseTraceRecorder() {}

    static void generationStarted(
            LocalTurnTrace.Builder builder,
            ProviderResponseCapture.GenerationEvent event) {
        emit(builder, "GENERATION_STARTED", generationData(event));
    }

    static void attemptStarted(
            LocalTurnTrace.Builder builder,
            ProviderResponseCapture.AttemptEvent event) {
        emit(builder, "GENERATION_ATTEMPT_STARTED", attemptData(event));
    }

    static void requestDispatched(
            LocalTurnTrace.Builder builder,
            ProviderResponseCapture.RequestDispatch dispatched) {
        // Historical schema label: this proves dispatch commitment at the
        // engine handoff callback, not that physical I/O has started.
        if (dispatched == null) return;
        Map<String, Object> data = attemptData(dispatched.attempt());
        if (!dispatched.evidence().providerBodyHash().isBlank()) {
            data.put("providerBodyHash", dispatched.evidence().providerBodyHash());
            data.put("dispatchedNativeTools", dispatched.evidence().nativeTools());
        }
        data.put("toolAuthoritySurface", toolAuthoritySurface(
                dispatched.attempt().toolAuthorityContract()));
        emit(builder, "GENERATION_REQUEST_DISPATCHED", data);
    }

    static void transportUnobserved(
            LocalTurnTrace.Builder builder,
            ProviderResponseCapture.AttemptEvent event) {
        emit(builder, "GENERATION_TRANSPORT_UNOBSERVED", attemptData(event));
    }

    static void responseReturned(
            LocalTurnTrace.Builder builder,
            ProviderResponseCapture.AttemptResponse returned) {
        if (returned == null || returned.response() == null) return;
        Map<String, Object> data = attemptData(returned.attempt());
        ProviderResponseCapture.Response response = returned.response();
        data.put("assistantHash", TraceRedactor.hash(response.assistantText()));
        data.put("assistantChars", response.assistantText().length());
        data.put("nativeToolCallCount", response.nativeToolCallCount());
        int capturedProposalCount = Math.min(
                response.nativeToolCallCount(),
                ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS);
        data.put("capturedNativeToolProposalCount", capturedProposalCount);
        data.put("nativeToolProposalsTruncated",
                response.nativeToolCallCount() > capturedProposalCount);
        data.put("textualToolCallPresent", response.textualToolCallPresent());
        data.put("completion", response.completion().name());
        data.put("providerBacked", returned.attempt().requestDispatched()
                && response.providerBacked());
        data.put("foreground", response.foreground());
        data.put("completedNormally", returned.attempt().requestDispatched()
                && response.completedNormally());
        data.put("normalized", response.normalized());
        data.put("truncated", response.truncated());
        emit(builder, "GENERATION_RESPONSE_CAPTURED", data);

        List<ChatMessage.NativeToolCall> calls = response.nativeToolCalls();
        for (int index = 0; index < Math.min(
                calls.size(), ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS); index++) {
            recordRawToolProposal(builder, returned.attempt(), index + 1, calls.get(index));
        }
    }

    static void attemptFailed(
            LocalTurnTrace.Builder builder,
            ProviderResponseCapture.AttemptFailure failed) {
        Map<String, Object> data = attemptData(failed.attempt());
        data.put("failure", failed.failure().name());
        emit(builder, "GENERATION_ATTEMPT_FAILED", data);
    }

    static void attemptAbandoned(
            LocalTurnTrace.Builder builder,
            ProviderResponseCapture.AttemptAbandonment abandoned) {
        Map<String, Object> data = attemptData(abandoned.attempt());
        data.put("abandonment", abandoned.abandonment().name());
        emit(builder, "GENERATION_ATTEMPT_ABANDONED", data);
    }

    static void generationFailed(
            LocalTurnTrace.Builder builder,
            ProviderResponseCapture.GenerationFailure failed) {
        Map<String, Object> data = generationData(failed.generation());
        data.put("failure", failed.failure().name());
        emit(builder, "GENERATION_FAILED", data);
    }

    static void generationAbandoned(
            LocalTurnTrace.Builder builder,
            ProviderResponseCapture.GenerationAbandonment abandoned) {
        Map<String, Object> data = generationData(abandoned.generation());
        data.put("abandonment", abandoned.abandonment().name());
        emit(builder, "GENERATION_ABANDONED", data);
    }

    static void generationCompleted(
            LocalTurnTrace.Builder builder,
            ProviderResponseCapture.GenerationCompletion completed) {
        Map<String, Object> data = generationData(completed.generation());
        ProviderResponseCapture.Receipt receipt = completed.selectedResponse();
        data.put("selectedAttemptId", receipt.attemptId());
        data.put("selectedAttemptOrdinal", receipt.attemptOrdinal());
        data.put("completion", receipt.completion().name());
        data.put("assistantHash", receipt.assistantHash());
        data.put("providerTerminalEligible", receipt.providerTerminalEligible());
        data.put("providerToolCallEligible", receipt.providerToolCallEligible());
        emit(builder, "GENERATION_COMPLETED", data);
    }

    private static void recordRawToolProposal(
            LocalTurnTrace.Builder builder,
            ProviderResponseCapture.AttemptEvent attempt,
            int proposalOrdinal,
            ChatMessage.NativeToolCall call) {
        Map<String, Object> data = attemptData(attempt);
        data.put("proposalOrdinal", proposalOrdinal);
        String callId = call == null ? "" : Objects.toString(call.id(), "");
        data.put("providerCallIdHash", TraceRedactor.hash(callId));
        String rawToolName = call == null ? "" : Objects.toString(call.name(), "");
        ToolAliasPolicy.Decision toolDecision = ToolAliasPolicy.resolve(rawToolName);
        data.put("toolNameClassification",
                rawToolName.isBlank() ? "BLANK" : toolDecision.status().name());
        data.put("toolNameHash", ProviderResponseCapture.toolNameHash(rawToolName));
        data.put("toolNameChars", rawToolName.length());
        List<String> argumentNames = call == null || call.arguments() == null
                ? List.of()
                : call.arguments().keySet().stream().filter(Objects::nonNull)
                        .map(String::valueOf).sorted().toList();
        List<String> argumentNameHashes = argumentNames.stream()
                .map(ProviderResponseCapture::argumentNameHash)
                .sorted()
                .limit(ProviderResponseCapture.MAX_TRACE_ARGUMENT_NAME_HASHES)
                .toList();
        data.put("argumentNameHashes", argumentNameHashes);
        data.put("argumentCount", argumentNames.size());
        data.put("argumentNameHashesTruncated",
                argumentNames.size() > ProviderResponseCapture.MAX_TRACE_ARGUMENT_NAME_HASHES);
        data.put("argumentsHash", ProviderResponseCapture.canonicalArgumentsHash(
                call == null ? Map.of() : call.arguments()));
        emit(builder, "PROVIDER_TOOL_PROPOSAL_CAPTURED", data);
    }

    private static Map<String, Object> generationData(
            ProviderResponseCapture.GenerationEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("providerLedgerVersion", LEDGER_VERSION);
        data.put("ledgerSequence", event.ledgerSequence());
        data.put("generationId", event.generationId());
        data.put("source", event.source().name());
        data.put("purpose", event.purpose().name());
        data.put("toolAuthorityMode", event.toolAuthorityMode().name());
        return data;
    }

    private static Map<String, Object> attemptData(
            ProviderResponseCapture.AttemptEvent event) {
        Map<String, Object> data = generationData(new ProviderResponseCapture.GenerationEvent(
                event.ledgerSequence(), event.generationId(), event.source(), event.purpose(),
                event.toolAuthorityMode()));
        data.put("attemptId", event.attemptId());
        data.put("attemptOrdinal", event.attemptOrdinal());
        data.put("attemptKind", event.attemptKind().name());
        data.put("retryReason", event.retryReason().name());
        data.put("requestDispatched", event.requestDispatched());
        return data;
    }

    private static Map<String, Object> toolAuthoritySurface(
            ProviderResponseCapture.ToolAuthorityContract contract
    ) {
        ProviderResponseCapture.ToolAuthorityContract typed = contract == null
                ? ProviderResponseCapture.ToolAuthorityContract.none()
                : contract;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", 1);
        data.put("bound", typed.bound());
        data.put("toolChoiceMode", typed.toolChoice().name());
        data.put("namedToolNameHash", ProviderResponseCapture.toolNameHash(
                typed.namedTool()));
        data.put("selectedToolNameHashes", typed.selectedToolNameHashes());
        data.put("authorizedToolNameHashes", typed.authorizedToolNameHashes());
        return Map.copyOf(data);
    }

    private static void emit(
            LocalTurnTrace.Builder builder,
            String type,
            Map<String, Object> data) {
        if (builder == null || data == null) return;
        builder.event(TurnTraceEvent.simple(type, Instant.now().toString(), data));
    }
}
