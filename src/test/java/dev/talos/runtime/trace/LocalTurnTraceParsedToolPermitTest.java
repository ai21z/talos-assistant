package dev.talos.runtime.trace;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTraceParsedToolPermitTest {
    @AfterEach
    void clearCapture() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void parsedPermitCanBeConsumedOnlyOnceAndSecondConsumptionRevokesIt() {
        beginTrace("trc-permit-double-consume", 1);
        ToolCall call = readCall("README.md");
        LocalTurnTraceCapture.ParsedToolCallPermit permit =
                LocalTurnTraceCapture.beginParsedToolCallForExecution(
                        "tool_loop", call, null, 0);

        assertTrue(LocalTurnTraceCapture.consumeToolCallParsedPermit(permit, call));
        assertFalse(LocalTurnTraceCapture.consumeToolCallParsedPermit(permit, call));
        assertThrows(IllegalStateException.class, () ->
                LocalTurnTraceCapture.recordToolExecuted(
                        "tool_loop", call, true, ""));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals(List.of("TOOL_CALL_PARSED"), toolLifecycle(trace));
    }

    @Test
    void wrongThreadCannotConsumeOrRevokeOwnerThreadsPermit() throws Exception {
        beginTrace("trc-permit-wrong-thread", 2);
        ToolCall call = readCall("docs/guide.md");
        LocalTurnTraceCapture.ParsedToolCallPermit permit =
                LocalTurnTraceCapture.beginParsedToolCallForExecution(
                        "tool_loop", call, null, 0);
        AtomicBoolean foreignConsumed = new AtomicBoolean(true);
        AtomicReference<Throwable> foreignFailure = new AtomicReference<>();

        Thread foreign = new Thread(() -> {
            try {
                foreignConsumed.set(
                        LocalTurnTraceCapture.consumeToolCallParsedPermit(permit, call));
            } catch (Throwable failure) {
                foreignFailure.set(failure);
            }
        }, "foreign-tool-permit-consumer");
        foreign.start();
        foreign.join();

        assertNull(foreignFailure.get());
        assertFalse(foreignConsumed.get());
        assertTrue(LocalTurnTraceCapture.consumeToolCallParsedPermit(permit, call));
        LocalTurnTraceCapture.recordToolExecuted("tool_loop", call, true, "");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals(
                List.of("TOOL_CALL_PARSED", "TOOL_EXECUTED"),
                toolLifecycle(trace));
        assertEquals(
                event(trace, "TOOL_CALL_PARSED").data().get("toolCallId"),
                event(trace, "TOOL_EXECUTED").data().get("toolCallId"));
    }

    @Test
    void permitFromReplacedTraceCannotAuthorizeTheNewTrace() {
        beginTrace("trc-permit-replaced-old", 3);
        ToolCall oldCall = readCall("old.txt");
        LocalTurnTraceCapture.ParsedToolCallPermit oldPermit =
                LocalTurnTraceCapture.beginParsedToolCallForExecution(
                        "tool_loop", oldCall, null, 0);

        beginTrace("trc-permit-replaced-new", 4);
        assertFalse(LocalTurnTraceCapture.consumeToolCallParsedPermit(
                oldPermit, oldCall));

        ToolCall newCall = readCall("new.txt");
        LocalTurnTraceCapture.ParsedToolCallPermit newPermit =
                LocalTurnTraceCapture.beginParsedToolCallForExecution(
                        "tool_loop", newCall, null, 0);
        assertTrue(LocalTurnTraceCapture.consumeToolCallParsedPermit(
                newPermit, newCall));
        LocalTurnTraceCapture.recordToolExecuted(
                "tool_loop", newCall, true, "");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals("trc-permit-replaced-new", trace.traceId());
        assertEquals(
                List.of("TOOL_CALL_PARSED", "TOOL_EXECUTED"),
                toolLifecycle(trace));
        assertEquals("call-4-1",
                event(trace, "TOOL_CALL_PARSED").data().get("toolCallId"));
    }

    @Test
    void identicalCallsReceiveDistinctIdsAndSettledPermitCannotCrossSettleNextCall() {
        beginTrace("trc-permit-identical-calls", 5);
        ToolCall call = readCall("same.txt");
        LocalTurnTraceCapture.ParsedToolCallPermit firstPermit =
                LocalTurnTraceCapture.beginParsedToolCallForExecution(
                        "tool_loop", call, null, 0);
        assertTrue(LocalTurnTraceCapture.consumeToolCallParsedPermit(firstPermit, call));
        LocalTurnTraceCapture.recordToolExecuted(
                "tool_loop", call, true, "first complete");

        LocalTurnTraceCapture.ParsedToolCallPermit secondPermit =
                LocalTurnTraceCapture.beginParsedToolCallForExecution(
                        "tool_loop", call, null, 0);
        assertFalse(LocalTurnTraceCapture.consumeToolCallParsedPermit(firstPermit, call),
                "the settled first permit must not authorize the identical second call");
        assertTrue(LocalTurnTraceCapture.consumeToolCallParsedPermit(secondPermit, call),
                "a rejected stale permit must not revoke the active second permit");
        LocalTurnTraceCapture.recordToolExecuted(
                "tool_loop", call, true, "second complete");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        List<TurnTraceEvent> parsed = events(trace, "TOOL_CALL_PARSED");
        List<TurnTraceEvent> executed = events(trace, "TOOL_EXECUTED");
        assertEquals(List.of("call-5-1", "call-5-2"),
                parsed.stream().map(candidate ->
                        String.valueOf(candidate.data().get("toolCallId"))).toList());
        assertEquals(List.of("call-5-1", "call-5-2"),
                executed.stream().map(candidate ->
                        String.valueOf(candidate.data().get("toolCallId"))).toList());
    }

    @Test
    void blockedAndExecutedFailureRemainMechanicallyDistinctByExactCallId() {
        beginTrace("trc-permit-terminal-kind", 6);
        ToolCall blockedCall = readCall("blocked.txt");
        LocalTurnTraceCapture.beginParsedToolCallForExecution(
                "tool_loop", blockedCall, null, 0);
        LocalTurnTraceCapture.recordToolCallBlocked(
                "tool_loop", blockedCall, "policy denied before execution");

        ToolCall failedCall = readCall("failed.txt");
        LocalTurnTraceCapture.ParsedToolCallPermit failedPermit =
                LocalTurnTraceCapture.beginParsedToolCallForExecution(
                        "tool_loop", failedCall, null, 0);
        assertTrue(LocalTurnTraceCapture.consumeToolCallParsedPermit(
                failedPermit, failedCall));
        LocalTurnTraceCapture.recordToolExecuted(
                "tool_loop", failedCall, false, "tool reached registry and failed");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        Map<String, String> reconstructed = reconstructSettlements(trace);
        List<TurnTraceEvent> parsed = events(trace, "TOOL_CALL_PARSED");
        String blockedId = String.valueOf(parsed.get(0).data().get("toolCallId"));
        String failedId = String.valueOf(parsed.get(1).data().get("toolCallId"));

        assertEquals("BLOCKED", reconstructed.get(blockedId));
        assertEquals("EXECUTED_FAILURE", reconstructed.get(failedId));
        TurnTraceEvent blocked = event(trace, "TOOL_CALL_BLOCKED");
        TurnTraceEvent failed = event(trace, "TOOL_EXECUTED");
        assertFalse(blocked.data().containsKey("success"));
        assertEquals(false, failed.data().get("success"));
        assertEquals(blockedId, blocked.data().get("toolCallId"));
        assertEquals(failedId, failed.data().get("toolCallId"));
    }

    private static void beginTrace(String traceId, int turnNumber) {
        LocalTurnTraceCapture.begin(
                traceId,
                "session-permit-tests",
                turnNumber,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "inspect exact parsed tool lineage");
    }

    private static ToolCall readCall(String path) {
        return new ToolCall("talos.read_file", Map.of("path", path));
    }

    private static List<String> toolLifecycle(LocalTurnTrace trace) {
        return trace.events().stream()
                .map(TurnTraceEvent::type)
                .filter(type -> type.equals("TOOL_CALL_PARSED")
                        || type.equals("TOOL_CALL_BLOCKED")
                        || type.equals("TOOL_EXECUTED"))
                .toList();
    }

    private static TurnTraceEvent event(LocalTurnTrace trace, String type) {
        return events(trace, type).getFirst();
    }

    private static List<TurnTraceEvent> events(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(candidate -> type.equals(candidate.type()))
                .toList();
    }

    private static Map<String, String> reconstructSettlements(LocalTurnTrace trace) {
        Map<String, String> settlements = new LinkedHashMap<>();
        List<String> parsedIds = new ArrayList<>();
        for (TurnTraceEvent event : trace.events()) {
            String id = String.valueOf(event.data().getOrDefault("toolCallId", ""));
            if ("TOOL_CALL_PARSED".equals(event.type())) {
                parsedIds.add(id);
                settlements.put(id, "UNSETTLED");
            } else if ("TOOL_CALL_BLOCKED".equals(event.type())) {
                settlements.put(id, "BLOCKED");
            } else if ("TOOL_EXECUTED".equals(event.type())) {
                settlements.put(id, Boolean.TRUE.equals(event.data().get("success"))
                        ? "EXECUTED_SUCCESS"
                        : "EXECUTED_FAILURE");
            }
        }
        assertEquals(parsedIds, new ArrayList<>(settlements.keySet()));
        return settlements;
    }
}
