package dev.talos.runtime.trace;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeTerminalTraceProjectorTest {

    @Test
    void completeProviderResponseWithTypedTextToolPresenceIsPreserved() {
        String traceId = "trc-projector-response";
        String generationId = traceId + "-generation-1";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("providerLedgerVersion", 1);
        data.put("ledgerSequence", 4);
        data.put("generationId", generationId);
        data.put("source", "FOREGROUND_PROVIDER");
        data.put("purpose", "TURN_RESPONSE");
        data.put("toolAuthorityMode", "PROVIDER_NATIVE");
        data.put("attemptId", generationId + "-attempt-1");
        data.put("attemptOrdinal", 1);
        data.put("attemptKind", "PRIMARY");
        data.put("retryReason", "NONE");
        data.put("requestDispatched", true);
        data.put("assistantHash", "sha256:" + "0".repeat(64));
        data.put("assistantChars", 0);
        data.put("nativeToolCallCount", 1);
        data.put("capturedNativeToolProposalCount", 1);
        data.put("nativeToolProposalsTruncated", false);
        data.put("textualToolCallPresent", false);
        data.put("completion", "PROVIDER_EOS");
        data.put("providerBacked", true);
        data.put("foreground", true);
        data.put("completedNormally", true);
        data.put("normalized", false);
        data.put("truncated", false);
        TurnTraceEvent response = TurnTraceEvent.simple(
                "GENERATION_RESPONSE_CAPTURED",
                "2026-07-21T00:00:00Z",
                data);

        SafeTerminalTraceProjector.Projection projection =
                SafeTerminalTraceProjector.project(List.of(response), traceId);

        assertTrue(projection.providerLifecycleObserved());
        assertTrue(projection.providerLifecyclePreserved());
        assertEquals(List.of(response), projection.events());
    }
}
