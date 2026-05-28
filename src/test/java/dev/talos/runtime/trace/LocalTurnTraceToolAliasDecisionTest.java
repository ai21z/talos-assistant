package dev.talos.runtime.trace;

import dev.talos.tools.ToolAliasPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTraceToolAliasDecisionTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsTraceWorthyToolAliasDecisionPayload() {
        beginTrace();

        LocalTurnTraceCapture.recordToolAliasDecision(ToolAliasPolicy.resolve("  tool_use:write_file  "));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "TOOL_ALIAS_DECISION".equals(candidate.type()))
                .findFirst()
                .orElseThrow();

        assertEquals(Map.of(
                "status", "ACCEPTED_ALIAS",
                "rawName", "tool_use:write_file",
                "canonicalTool", "talos.write_file",
                "profile", "tool_use",
                "mutating", true,
                "readOnly", false), event.data());
    }

    @Test
    void canonicalToolAliasDecisionRemainsUntraced() {
        beginTrace();

        LocalTurnTraceCapture.recordToolAliasDecision(ToolAliasPolicy.resolve("talos.read_file"));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertFalse(trace.events().stream()
                .anyMatch(candidate -> "TOOL_ALIAS_DECISION".equals(candidate.type())));
    }

    @Test
    void toolAliasDecisionTraceEventConstructionHasDedicatedFactoryOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path factoryPath = Path.of("src/main/java/dev/talos/runtime/trace/ToolAliasDecisionTraceEventFactory.java");

        assertTrue(Files.exists(factoryPath),
                "tool alias decision trace event construction should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String methodBody = methodBody(captureSource, "recordToolAliasDecision");
        String factorySource = Files.readString(factoryPath);

        assertTrue(captureSource.contains("ToolAliasDecisionTraceEventFactory."), captureSource);
        assertTrue(methodBody.contains("decision.traceWorthy()"), methodBody);
        assertFalse(methodBody.contains("\"TOOL_ALIAS_DECISION\""), methodBody);
        assertFalse(methodBody.contains("data.put(\"status\""), methodBody);
        assertFalse(methodBody.contains("data.put(\"rawName\""), methodBody);
        assertFalse(methodBody.contains("data.put(\"canonicalTool\""), methodBody);
        assertFalse(methodBody.contains("data.put(\"profile\""), methodBody);
        assertFalse(methodBody.contains("data.put(\"mutating\""), methodBody);
        assertFalse(methodBody.contains("data.put(\"readOnly\""), methodBody);

        assertTrue(factorySource.contains("TOOL_ALIAS_DECISION"), factorySource);
        assertTrue(factorySource.contains("data.put(\"status\""), factorySource);
        assertTrue(factorySource.contains("data.put(\"rawName\""), factorySource);
        assertTrue(factorySource.contains("data.put(\"canonicalTool\""), factorySource);
        assertTrue(factorySource.contains("data.put(\"profile\""), factorySource);
        assertTrue(factorySource.contains("data.put(\"mutating\""), factorySource);
        assertTrue(factorySource.contains("data.put(\"readOnly\""), factorySource);
        assertFalse(factorySource.contains("traceWorthy()"), factorySource);
    }

    private static String methodBody(String source, String methodName) {
        int start = source.indexOf(methodName);
        assertTrue(start >= 0, "method not found: " + methodName);
        int brace = source.indexOf('{', start);
        assertTrue(brace >= 0, "method opening brace not found: " + methodName);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') depth++;
            if (ch == '}') depth--;
            if (depth == 0) {
                return source.substring(brace, i + 1);
            }
        }
        throw new AssertionError("method closing brace not found: " + methodName);
    }

    private static void beginTrace() {
        LocalTurnTraceCapture.begin(
                "trc-tool-alias-decision",
                "sid-tool-alias-decision",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "record tool alias decision");
    }
}
