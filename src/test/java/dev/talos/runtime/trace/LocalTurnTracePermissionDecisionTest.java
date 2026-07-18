package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTracePermissionDecisionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearTraceCapture() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsPermissionDecisionPayloadWithoutRawToolPayload() throws Exception {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", ".env",
                "content", "SECRET_TOKEN=raw-value"));

        beginTrace();
        LocalTurnTraceCapture.recordPermissionDecision(
                "APPLY",
                call,
                "ASK",
                "PROTECTED_PATH_ASK",
                ".env",
                true,
                "SECRET",
                false);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "PERMISSION_DECISION".equals(candidate.type()))
                .findFirst()
                .orElseThrow();

        assertEquals("APPLY", event.phase());
        assertEquals("talos.write_file", event.toolName());
        assertEquals("ASK", event.data().get("action"));
        assertEquals("PROTECTED_PATH_ASK", event.data().get("reasonCode"));
        assertEquals(false, event.data().get("rememberEligible"));
        assertEquals(true, event.data().get("protectedPath"));
        assertEquals("SECRET", event.data().get("protectedKind"));
        assertEquals("<protected-path>", event.data().get("pathHint"));
        assertFalse(MAPPER.writeValueAsString(trace).contains("SECRET_TOKEN=raw-value"), trace.toString());
    }

    @Test
    void permissionDecisionTraceEventConstructionIsOwnedByFactory() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path factoryPath = Path.of("src/main/java/dev/talos/runtime/trace/PermissionTraceEventFactory.java");

        assertTrue(Files.exists(factoryPath),
                "permission decision trace event construction should have a dedicated owner");

        String capture = Files.readString(capturePath);
        String factory = Files.readString(factoryPath);
        String permissionMethod = methodBody(capture, "recordPermissionDecision");
        assertTrue(permissionMethod.contains("PermissionTraceEventFactory."), permissionMethod);
        assertFalse(permissionMethod.contains("\"PERMISSION_DECISION\""), permissionMethod);
        assertFalse(permissionMethod.contains("data.put(\"action\""), permissionMethod);
        assertFalse(permissionMethod.contains("data.put(\"reasonCode\""), permissionMethod);
        assertFalse(permissionMethod.contains("data.put(\"rememberEligible\""), permissionMethod);
        assertFalse(permissionMethod.contains("data.put(\"protectedPath\""), permissionMethod);
        assertFalse(permissionMethod.contains("data.put(\"protectedKind\""), permissionMethod);
        assertFalse(permissionMethod.contains("TraceRedactor.pathHint(relativePath)"), permissionMethod);
        assertTrue(factory.contains("PERMISSION_DECISION"), factory);
        assertTrue(factory.contains("data.put(\"action\""), factory);
        assertTrue(factory.contains("data.put(\"reasonCode\""), factory);
        assertTrue(factory.contains("data.put(\"rememberEligible\""), factory);
        assertTrue(factory.contains("data.put(\"protectedPath\""), factory);
        assertTrue(factory.contains("data.put(\"protectedKind\""), factory);
        assertTrue(factory.contains("TraceRedactor.pathHint(relativePath)"), factory);
    }

    private static String methodBody(String source, String methodName) {
        String marker = "public static void " + methodName + "(";
        int start = source.indexOf(marker);
        assertTrue(start >= 0, "missing source method " + methodName);
        int end = source.indexOf("\n    public static void ", start + marker.length());
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }

    private static void beginTrace() {
        LocalTurnTraceCapture.begin(
                "trc-permission-decision",
                "sid-permission-decision",
                1,
                "2026-05-28T12:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "Write .env");
    }
}
