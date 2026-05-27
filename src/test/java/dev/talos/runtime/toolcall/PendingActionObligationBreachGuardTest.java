package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PendingActionObligationBreachGuardTest {

    @Test
    void expectedTargetWrongMutationReturnsBreachDetail() {
        PendingActionObligation obligation =
                PendingActionObligation.expectedTargets(List.of("scripts.js"));
        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(
                        obligation,
                        List.of(call("talos.write_file", "script.js")));

        assertTrue(decision.breach());
        assertFalse(decision.deferToPolicy());
        assertTrue(decision.detail().contains("expected-target progress required mutation"),
                decision.detail());
        assertTrue(decision.detail().contains("scripts.js"), decision.detail());
        assertTrue(decision.detail().contains("talos.write_file(script.js)"), decision.detail());
    }

    @Test
    void expectedTargetStaticWebPolicyViolationCanDeferToNormalPolicy() {
        PendingActionObligation obligation =
                PendingActionObligation.expectedTargets(List.of("scripts.js"));
        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(
                        obligation,
                        List.of(call("talos.write_file", "src/script.js")));

        assertFalse(decision.breach());
        assertTrue(decision.deferToPolicy());
        assertEquals("", decision.detail());
    }

    @Test
    void staticRepairReadOnlyContinuationReturnsBreachDetail() {
        PendingActionObligation obligation =
                PendingActionObligation.staticRepairTargets(List.of("styles.css"));
        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(
                        obligation,
                        List.of(call("talos.read_file", "styles.css")));

        assertTrue(decision.breach());
        assertFalse(decision.deferToPolicy());
        assertTrue(decision.detail().contains("Static web repair requires talos.write_file"),
                decision.detail());
        assertTrue(decision.detail().contains("styles.css"), decision.detail());
        assertTrue(decision.detail().contains("talos.read_file(styles.css)"), decision.detail());
    }

    @Test
    void compactTargetRepairWrongToolReturnsBreachDetail() {
        PendingActionObligation obligation =
                PendingActionObligation.oldStringMissTargets(List.of("README.md"));
        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(
                        obligation,
                        List.of(call("talos.read_file", "README.md")));

        assertTrue(decision.breach());
        assertFalse(decision.deferToPolicy());
        assertTrue(decision.detail().contains("old-string miss compact repair required"),
                decision.detail());
        assertTrue(decision.detail().contains("README.md"), decision.detail());
        assertTrue(decision.detail().contains("talos.read_file(README.md)"), decision.detail());
    }

    @Test
    void loopStateDelegatesInvalidToolClassificationToGuard() throws Exception {
        String loopState = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/LoopState.java"));

        assertTrue(loopState.contains("PendingActionObligationBreachGuard.assess("), loopState);
        assertFalse(loopState.contains("private static String invalidExpectedTargetMutationDetail"),
                loopState);
        assertFalse(loopState.contains("private static boolean shouldPolicyHandleStaticWebExpectedTargetViolation"),
                loopState);
        assertFalse(loopState.contains("private static String targetRepairInvalidToolDetail"),
                loopState);
        assertFalse(loopState.contains("private static String staticRepairInvalidToolDetail"),
                loopState);
    }

    private static ToolCall call(String toolName, String path) {
        return new ToolCall(toolName, Map.of("path", path));
    }
}
