package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.core.util.RuntimeArtifactIdentity;
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
    void traceStartBindsTheExecutingRuntimeArtifactWithoutPersistingItsPath() {
        LocalTurnTraceCapture.begin(
                "trc-runtime-identity", "session-runtime-identity", 1,
                "2026-07-16T00:00:00Z", "workspace-hash", "auto",
                "llama_cpp", "test-model", "hello");
        try {
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            TurnTraceEvent started = trace.events().getFirst();
            RuntimeArtifactIdentity.Snapshot runtime = RuntimeArtifactIdentity.current();

            assertEquals("TRACE_STARTED", started.type());
            assertEquals(runtime.kind(), started.data().get("runtimeArtifactKind"));
            assertEquals(runtime.sha256(), started.data().get("runtimeArtifactSha256"));
            assertEquals(runtime.sizeBytes(), started.data().get("runtimeArtifactSizeBytes"));
            assertEquals(runtime.classpathSha256(),
                    started.data().get("runtimeClasspathSha256"));
            assertEquals(runtime.classpathSizeBytes(),
                    started.data().get("runtimeClasspathSizeBytes"));
            assertEquals(runtime.classpathEntryCount(),
                    started.data().get("runtimeClasspathEntryCount"));
            assertEquals(runtime.version(), started.data().get("runtimeVersion"));
            assertEquals(runtime.commitSha(), started.data().get("runtimeCommit"));
            assertFalse(started.data().containsKey("runtimeArtifactPath"));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

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
                .model("llama_cpp", "qwen2.5-coder:14b")
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

        assertTrue(json.contains("\"schemaVersion\":5"));
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
        assertEquals(5, roundTrip.schemaVersion());
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

    @Test
    void redactsPolicyProtectedPathsToProtectedPathHint() {
        ToolCall readProtected = new ToolCall("talos.read_file", Map.of(
                "path", "protected/private-notes.md"));
        ToolCall editWorkflow = new ToolCall("talos.read_file", Map.of(
                "path", ".github/workflows/deploy.yml"));

        TurnTraceEvent protectedEvent = TurnTraceEvent.toolCallParsed(
                "2026-04-28T12:00:02Z",
                "INSPECT",
                readProtected);
        TurnTraceEvent workflowEvent = TurnTraceEvent.toolCallParsed(
                "2026-04-28T12:00:03Z",
                "INSPECT",
                editWorkflow);

        assertEquals("<protected-path>", protectedEvent.data().get("pathHint"));
        assertEquals("<protected-path>", workflowEvent.data().get("pathHint"));
    }

    @Test
    void executedToolTraceKeepsAllStructuredSourcePathHints() {
        LocalTurnTraceCapture.begin(
                "trc-evidence-sources",
                "session-evidence-sources",
                1,
                "2026-07-12T12:00:00Z",
                "workspace-hash",
                "auto",
                "llama_cpp",
                "qwen3-8b",
                "locate the service port");
        TurnAuditCapture.begin();
        try {
            ToolCall tracedCall = new ToolCall(
                    "talos.retrieve", Map.of("path", "config.json"));
            LocalTurnTraceCapture.ParsedToolCallPermit permit =
                    LocalTurnTraceCapture.beginRuntimeToolCallForExecution(
                            "",
                            tracedCall,
                            LocalTurnTraceCapture.RuntimeToolAuthorityReason
                                    .DIRECT_RUNTIME_API)
                            .permit();
            assertTrue(LocalTurnTraceCapture.consumeToolCallParsedPermit(
                    permit, tracedCall));
            TurnAuditCapture.recordToolCall(
                    "talos.retrieve",
                    List.of("config.json", "docs/operations.md"),
                    true,
                    "");

            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            TurnTraceEvent executed = trace.events().stream()
                    .filter(event -> event.type().equals("TOOL_EXECUTED"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(
                    List.of("config.json", "docs/operations.md"),
                    executed.data().get("pathHints"));
        } finally {
            TurnAuditCapture.end();
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void executedToolTraceRedactsProtectedSourcePathHints() {
        TurnTraceEvent event = TurnTraceEvent.toolExecuted(
                "2026-07-12T12:00:00Z",
                "INSPECT",
                new ToolCall("talos.retrieve", Map.of("query", "secret")),
                true,
                "",
                List.of("README.md", ".env", "protected/private-notes.md"));

        assertEquals(
                List.of("README.md", "<protected-path>"),
                event.data().get("pathHints"));
        assertFalse(event.data().toString().contains(".env"));
        assertFalse(event.data().toString().contains("private-notes"));
    }
}
