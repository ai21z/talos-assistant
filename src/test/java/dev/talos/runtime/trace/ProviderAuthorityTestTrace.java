package dev.talos.runtime.trace;

import org.junit.jupiter.api.TestInfo;

/**
 * Test-only owner for the active turn trace required by provider tool authority.
 */
public final class ProviderAuthorityTestTrace {
    private ProviderAuthorityTestTrace() {}

    public static void begin(String suite, TestInfo testInfo) {
        LocalTurnTraceCapture.clear();
        String displayName = testInfo == null ? "test" : testInfo.getDisplayName();
        String traceId = "trc-" + suite + "-"
                + Integer.toUnsignedString(displayName.hashCode());
        LocalTurnTraceCapture.begin(
                traceId,
                suite,
                1,
                "2026-07-20T00:00:00Z",
                "workspace-hash",
                "test",
                "llama_cpp",
                "test-model",
                displayName);
    }

    public static void clear() {
        LocalTurnTraceCapture.clear();
    }
}
