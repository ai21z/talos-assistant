package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LocalTurnTraceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serializesStableSchemaWithoutFullPromptOrToolPayloadByDefault() throws Exception {
        ToolCall writeCall = new ToolCall("talos.write_file", Map.of(
                "path", "index.html",
                "content", "SECRET=abc\n<h1>Hello</h1>"));

        LocalTurnTrace trace = LocalTurnTrace.builder(
                        "trc-fixed",
                        "session-fixed",
                        7,
                        "2026-04-28T12:00:00Z")
                .workspaceHash("workspace-hash")
                .mode("auto")
                .model("ollama", "qwen2.5-coder:14b")
                .promptSummary("please write SECRET=abc into index.html")
                .assistantSummary("I wrote SECRET=abc into index.html")
                .taskContract(new TaskContract(
                        TaskType.FILE_CREATE,
                        true,
                        true,
                        true,
                        Set.of("index.html"),
                        Set.of(),
                        "please write SECRET=abc into index.html"))
                .phaseTransition("INSPECT", "APPLY", "mutationAllowed")
                .toolSurface(
                        List.of("talos.read_file", "talos.write_file"),
                        List.of("talos.read_file", "talos.write_file"),
                        "mutation task in APPLY phase")
                .promptAudit(new PromptAuditSnapshot(
                        1,
                        "FILE_CREATE",
                        true,
                        true,
                        "APPLY",
                        "APPLY",
                        "MUTATING_TOOL_REQUIRED",
                        "NONE_OR_NOT_DERIVED",
                        "NOT_DERIVED",
                        "NONE_OR_NOT_DERIVED",
                        "NONE_OR_NOT_DERIVED",
                        "NONE_OR_NOT_DERIVED",
                        "INCLUDED",
                        2,
                        true,
                        "AFTER_HISTORY_BEFORE_USER",
                        "frame-hash",
                        "[CurrentTurnCapability] SECRET=[redacted]",
                        2,
                        1,
                        5,
                        "prompt-hash",
                        List.of("talos.read_file", "talos.write_file"),
                        List.of("talos.read_file", "talos.write_file"),
                        List.of(),
                        TraceRedactionMode.DEFAULT))
                .event(TurnTraceEvent.toolCallParsed(
                        "2026-04-28T12:00:01Z",
                        "APPLY",
                        writeCall))
                .verification("FAILED", "Static verification failed", List.of("scripts.js missing"))
                .outcome("FAILED", "FAILED", "UNKNOWN", "PARTIAL", "TASK_INCOMPLETE")
                .warning("STATIC_VERIFICATION_FAILED", "Static post-apply verification failed.")
                .build();

        String json = MAPPER.writeValueAsString(trace);

        assertTrue(json.contains("\"schemaVersion\":2"));
        assertTrue(json.contains("\"traceId\":\"trc-fixed\""));
        assertTrue(json.contains("\"promptAudit\""));
        assertTrue(json.contains("\"contentHash\""));
        assertTrue(json.contains("\"contentBytes\""));
        assertTrue(json.contains("\"contentLines\""));
        assertTrue(json.contains("\"promptHash\""));
        assertTrue(json.contains("\"assistantHash\""));
        assertFalse(json.contains("SECRET=abc"), "default trace must not store raw prompt/answer/tool payload");
        assertFalse(json.contains("<h1>Hello</h1>"), "default trace must not store raw file content");

        LocalTurnTrace roundTrip = MAPPER.readValue(json, LocalTurnTrace.class);
        assertEquals(2, roundTrip.schemaVersion());
        assertEquals("trc-fixed", roundTrip.traceId());
        assertEquals("FILE_CREATE", roundTrip.taskContract().type());
        assertEquals("MUTATING_TOOL_REQUIRED", roundTrip.promptAudit().actionObligation());
        assertEquals("FAILED", roundTrip.verification().status());
        assertEquals(TraceRedactionMode.DEFAULT, roundTrip.redaction().mode());
    }

    @Test
    void redactsSecretLikePathsToProtectedPathHint() {
        ToolCall writeCall = new ToolCall("talos.write_file", Map.of(
                "path", ".env",
                "content", "TOKEN=ALPHA-742"));

        TurnTraceEvent event = TurnTraceEvent.toolCallParsed(
                "2026-04-28T12:00:02Z",
                "APPLY",
                writeCall);

        assertEquals("<protected-path>", event.data().get("pathHint"));
        assertTrue(event.data().containsKey("contentHash"));
        assertFalse(event.data().containsValue("TOKEN=ALPHA-742"));
    }
}
