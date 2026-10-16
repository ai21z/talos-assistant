package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.ContextDeliveryWireFixture;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.*;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.GrepTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real executor with authored provider proposals and synthetic transport. */
@Timeout(45)
class ProjectSummaryEvidenceExecutorTest {
    @TempDir Path temp;
    private static final String REQUEST = "Explain this project and state its configured port.";
    private static final String SOURCE = "Fictional Orchid is a local utility.\nConfigured port: 8200.\n";
    private static final String ANSWER = "According to README.md, Orchid is a local utility with configured port 8200.";

    @Test void partialReadMustNotCertifyUnseenConfiguredPort() throws Exception { run(false, false, false); }
    @Test void failedCurrentRefreshMustNotCertifyOldConfiguredPort() throws Exception { run(true, false, false); }
    @Test void completeReadPreservesUsefulAnswer() throws Exception { run(false, false, true); }
    @Test void fullReadAfterPartialEvidenceAllowsAnswer() throws Exception { run(false, true, false); }
    @Test void fullReadAfterFailedRefreshAllowsAnswer() throws Exception { run(true, true, false); }
    @Test void realStructuredSearchPreservesSourceInspection() throws Exception { run(false, false, true, true); }

    private void run(boolean refresh, boolean recover, boolean complete) throws Exception {
        run(refresh, recover, complete, false);
    }

    private void run(boolean refresh, boolean recover, boolean complete, boolean search) throws Exception {
        String id = "outer-" + refresh + "-" + recover + "-" + complete + "-" + search;
        Path workspace = Files.createDirectory(temp.resolve(id));
        Files.writeString(workspace.resolve("README.md"), SOURCE, StandardOpenOption.CREATE_NEW);
        var calls = new AtomicInteger();
        var proposals = new ArrayList<LlmClient.StreamResult>();
        var fixture = ScriptedNativeLlmClient.recordingResponder(request -> {
            int sequence = calls.incrementAndGet();
            if (sequence > 4) throw new AssertionError("Declared provider-call cap exceeded");
            LlmClient.StreamResult response;
            if (sequence == 1) {
                response = new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("outer-read-1",
                        search ? "talos.grep" : "talos.read_file", search
                                ? Map.of("pattern", ".", "regex", "true", "include", "README.md")
                                : refresh || complete ? Map.of("path", "README.md") : Map.of("path", "README.md", "max_lines", 1))));
            } else if (refresh && sequence == 2) {
                try { Files.move(workspace.resolve("README.md"), workspace.resolve("retained-README.md")); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                response = new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("outer-read-2",
                        "talos.read_file", Map.of("path", "README.md", "max_lines", 100))));
            } else if (recover && sequence == (refresh ? 3 : 2)) {
                if (refresh) {
                    try { Files.move(workspace.resolve("retained-README.md"), workspace.resolve("README.md")); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }
                response = new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("outer-read-3",
                        "talos.read_file", Map.of("path", "README.md", "max_lines", 101))));
            } else response = new LlmClient.StreamResult(ANSWER, List.of());
            proposals.add(response);
            return response;
        }, 8192);
        var record = new LinkedHashMap<String, Object>();
        record.put("transport", "SCRIPTED_OBSERVED_TRANSPORT_NOT_HTTP_OR_LIVE_MODEL");
        record.put("workspace", workspace.toString());
        record.put("source", SOURCE);
        record.put("authoredFinalAnswer", ANSWER);
        record.put("assertion", "Unseen or failed-refresh configured port must not receive complete current-evidence treatment");
        try (var client = fixture.client()) {
            var config = new Config(null);
            var approvals = new AtomicInteger();
            ApprovalGate gate = (description, detail) -> { approvals.incrementAndGet(); return false; };
            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            if (search) registry.register(new GrepTool());
            var tools = registry.descriptors().stream().map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema())).toList();
            client.setToolSpecs(tools);
            var context = Context.builder(config).llm(client).nativeToolSpecs(tools)
                    .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).approvalGate(gate)
                    .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, gate, registry), 4)).build();
            LocalTurnTraceCapture.begin("trc-" + id, "mica", 1, "2026-10-08T00:00:00Z",
                    "synthetic", "ask", "synthetic", "mica-synthetic", REQUEST);
            TurnAuditCapture.begin();
            var output = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                    ChatMessage.user(REQUEST))), workspace, context, new AssistantTurnExecutor.Options()
                    .capabilityPosture(CapabilityPosture.ASK_READ_ONLY).llmTimeoutMs(5_000));
            var audit = TurnAuditCapture.end();
            var trace = LocalTurnTraceCapture.complete();
            record.put("requests", fixture.requests().stream().map(request -> {
                var fields = new LinkedHashMap<String, Object>();
                fields.put("backend", request.backend);
                fields.put("model", request.model);
                fields.put("systemPrompt", request.systemPrompt);
                fields.put("userPrompt", request.userPrompt);
                fields.put("snippets", request.snippets);
                fields.put("timeout", request.timeout.toString());
                fields.put("messages", request.messages);
                fields.put("tools", request.tools);
                fields.put("controls", request.controls);
                fields.put("contentRestrictions", request.contentRestrictions);
                return fields;
            }).toList());
            record.put("output", output);
            record.put("trace", trace);
            record.put("toolCalls", audit.toolCalls());
            record.put("promptDebug", PromptDebugCapture.history());
            record.put("providerCalls", calls.get());
            record.put("scriptedProposals", proposals.stream().map(p -> Map.of("text", p.text(), "toolCalls", p.toolCalls())).toList());
            record.put("approvals", approvals.get());
            retain(id, record);
            assertTrue(calls.get() >= (refresh ? 3 : 2) && calls.get() <= 4);
            assertEquals((refresh ? 2 : 1) + (recover ? 1 : 0), audit.toolCalls().size());
            assertEquals(0, approvals.get());
            assertEquals(SOURCE, Files.readString(workspace.resolve(refresh && !recover ? "retained-README.md" : "README.md")));
            if (complete || recover) {
                assertEquals("COMPLETE", trace.outcome().status(), output.text());
                assertTrue(output.text().contains("configured port 8200"), output.text());
                return;
            }
            assertFalse("COMPLETE".equals(trace.outcome().status()) && output.text().contains("configured port 8200"),
                    "Unsupported complete answer: " + trace.outcome() + " " + output.text());
            assertTrue(output.text().contains("Fictional Orchid is a local utility."), output.text());
            assertFalse(output.text().contains(ANSWER), output.text());
            if (!refresh) assertFalse(output.text().contains("8200"), output.text());
            else assertTrue(output.text().contains("Earlier") && output.text().contains("8200"), output.text());
        } finally {
            LocalTurnTraceCapture.clear();
            ProviderResponseCapture.clear();
            TurnAuditCapture.end();
            TurnTaskContractCapture.clear();
            TurnUserRequestCapture.clear();
            TurnSourceEvidenceCapture.clear();
            PromptDebugCapture.clear();
        }
    }

    private static void retain(String id, Map<String, ?> data) throws Exception {
        String location = System.getProperty("mica.summaryEvidenceDir");
        if (location == null) return;
        Path root = Files.createDirectories(Path.of(location));
        Files.writeString(root.resolve(id + ".json"), ContextDeliveryWireFixture.JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(data) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
