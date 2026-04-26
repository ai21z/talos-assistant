package dev.talos.cli.repl;

import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ReplRouterTraceTest {

    @Test
    void formatsCurrentTurnPolicyTraceForDebugTraceMode() {
        TurnPolicyTrace policyTrace = new TurnPolicyTrace(
                "SMALL_TALK",
                false,
                false,
                List.of(),
                List.of(),
                "INSPECT",
                "INSPECT",
                List.of(),
                List.of(),
                List.of());
        TurnResult result = new TurnResult(
                new Result.Ok("hello"),
                null,
                1,
                Duration.ofMillis(10),
                new TurnAudit(List.of(), 0, 0, 0, policyTrace));

        String text = ReplRouter.formatCurrentTurnTrace(result);

        assertTrue(text.contains("Current Turn Trace"));
        assertTrue(text.contains("contract: SMALL_TALK mutationAllowed=false verificationRequired=false"));
        assertTrue(text.contains("phase: initial=INSPECT final=INSPECT"));
        assertTrue(text.contains("nativeTools: none"));
        assertTrue(text.contains("blocked: none"));
    }
}
