package dev.talos.cli.modes;

import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.prompt.PromptInspector;
import dev.talos.cli.prompt.PromptRender;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.util.Sanitize;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnEnvelopePersistenceReceipt;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedAssistantModeRawSourceBoundaryTest {

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        LastPromptCapture.clear();
        PromptDebugCapture.clear();
    }

    @Test
    void crlfLexicalEvidenceSurvivesTraceWhilePreviewAndProviderUseLf(
            @TempDir Path workspace
    ) throws Exception {
        String request = "Edit index.html so maybe update style.css,\r\n"
                + "and then update script.js.";
        RunEvidence evidence = run(request, "script.js", workspace);
        String expectedSource = "maybe update style.css,\r\n"
                + "and then update script.js.";
        int expectedStart = request.indexOf("maybe");
        int expectedAuthorityEnd = request.indexOf("script.js")
                + "script.js".length();
        int expectedEnd = expectedStart + expectedSource.length();

        assertEquals(
                expectedSource,
                evidence.expectedTarget().derivation().sourceText());
        assertEquals(
                expectedStart,
                evidence.expectedTarget().derivation().startOffset());
        assertEquals(
                expectedAuthorityEnd,
                evidence.expectedTarget().derivation().authorityEndOffset());
        assertEquals(
                expectedEnd,
                evidence.expectedTarget().derivation().endOffset());
        assertEquals(TargetRole.MENTIONED_ONLY, evidence.expectedTarget().role());
        assertRawTraceEvidence(evidence);
        assertCanonicalProviderParity(evidence, request);
        assertPersistenceRoundTrip(
                evidence,
                workspace.resolve("session-store"));

        TaskResolution providerResolution = resolution(
                Sanitize.sanitizeMessageContent(request),
                workspace);
        TargetRef providerTarget = providerResolution.intent()
                .targets()
                .find("script.js")
                .orElseThrow();
        assertNotEquals(
                evidence.expectedTarget().derivation().sourceText(),
                providerTarget.derivation().sourceText());
        assertNotEquals(
                evidence.expectedTarget().derivation().authorityEndOffset(),
                providerTarget.derivation().authorityEndOffset());
    }

    @Test
    void controlSplitConnectorCannotBroadenCapturedOrRuntimeAuthority(
            @TempDir Path workspace
    ) throws Exception {
        String request = "Review index.html so maybe, th\u0000en update style.css.";
        TaskResolution rawResolution = resolution(request, workspace);
        TaskResolution providerResolution = resolution(
                Sanitize.sanitizeMessageContent(request),
                workspace);

        TargetRef rawStyle = rawResolution.intent()
                .targets()
                .find("style.css")
                .orElseThrow();
        TargetRef providerStyle = providerResolution.intent()
                .targets()
                .find("style.css")
                .orElseThrow();
        String expectedRawSource = "maybe, th\u0000en update style.css.";
        int expectedRawStart = request.indexOf("maybe");
        int expectedRawAuthorityEnd = request.indexOf("style.css")
                + "style.css".length();
        int expectedRawEnd = expectedRawStart + expectedRawSource.length();
        assertEquals(TargetRole.MENTIONED_ONLY, rawStyle.role());
        assertEquals(expectedRawSource, rawStyle.derivation().sourceText());
        assertEquals(expectedRawStart, rawStyle.derivation().startOffset());
        assertEquals(
                expectedRawAuthorityEnd,
                rawStyle.derivation().authorityEndOffset());
        assertEquals(expectedRawEnd, rawStyle.derivation().endOffset());
        assertEquals(TargetRole.MUST_MUTATE, providerStyle.role());
        assertTrue(rawResolution.blocksUnscopedMutation());
        assertFalse(providerResolution.blocksUnscopedMutation());
        assertTrue(providerResolution.contract().mutationAllowed());
        assertTrue(rawStyle.derivation().sourceText().indexOf('\u0000') >= 0);

        PromptRender directCapture = PromptInspector.fromMessages(
                "auto",
                "agent",
                workspace,
                inspectorContext(),
                true,
                0,
                List.of(
                        ChatMessage.system("system"),
                        ChatMessage.user(request)));
        assertFalse(directCapture.tools().contains("talos.write_file"));
        assertFalse(directCapture.tools().contains("talos.edit_file"));
        assertTrue(directCapture.messages().stream()
                .filter(message -> "user".equals(message.role()))
                .map(ChatMessage::content)
                .anyMatch(Sanitize.sanitizeMessageContent(request)::equals));

        RunEvidence evidence = run(request, "style.css", workspace);
        assertRawTraceEvidence(evidence);
        assertCanonicalProviderParity(evidence, request);
        assertTrue(evidence.preview().mutationAllowed());
        assertTrue(evidence.captured().mutationAllowed());
        assertFalse(evidence.providerTools().contains("talos.write_file"));
        assertFalse(evidence.providerTools().contains("talos.edit_file"));

        String diagnostic = PromptDebugCapture.latest()
                .orElseThrow()
                .diagnostics()
                .get(PromptDebugCapture.TASK_CONTRACT_TARGET_ROLES_DIAGNOSTIC);
        assertNotNull(diagnostic);
        assertTrue(
                diagnostic.contains("style.css = MENTIONED_ONLY"),
                diagnostic);
        assertTrue(
                diagnostic.contains("[sourceSpan="
                        + rawStyle.derivation().startOffset()
                        + ":"
                        + rawStyle.derivation().authorityEndOffset()
                        + ":"
                        + rawStyle.derivation().endOffset()
                        + "]"),
                diagnostic);
    }

    private static RunEvidence run(
            String request,
            String target,
            Path workspace
    ) throws Exception {
        LastPromptCapture.clear();
        PromptDebugCapture.clear();
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult(
                                "The requested inspection is incomplete.",
                                List.of()),
                        new LlmClient.StreamResult(
                                "The requested inspection is incomplete.",
                                List.of()),
                        new LlmClient.StreamResult(
                                "The requested inspection is incomplete.",
                                List.of())),
                16_384);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        Context context = Context.builder(new Config())
                .memory(new SessionMemory())
                .toolRegistry(registry)
                .llm(recorded.client())
                .build();
        PromptRender preview = PromptInspector.renderNext(
                "auto",
                request,
                workspace,
                context);
        TaskResolution expectedResolution = resolution(request, workspace);
        TargetRef expectedTarget = expectedResolution.intent()
                .targets()
                .find(target)
                .orElseThrow();

        LocalTurnTraceCapture.begin(
                "trace-t1196-raw-source",
                "session-t1196-raw-source",
                1,
                "2026-07-31T00:00:00Z",
                "workspace-t1196-raw-source",
                "agent",
                "scripted",
                "scripted",
                request);
        LocalTurnTrace trace;
        try {
            new UnifiedAssistantMode().handle(request, workspace, context);
            trace = LocalTurnTraceCapture.complete();
        } finally {
            LocalTurnTraceCapture.clear();
        }

        assertNotNull(trace);
        assertFalse(recorded.requests().isEmpty());
        ChatRequest providerRequest = recorded.requests().getFirst();
        PromptRender captured = LastPromptCapture.latest().orElseThrow();
        List<String> providerTools = providerRequest.tools.stream()
                .map(tool -> tool.name())
                .toList();
        return new RunEvidence(
                request,
                expectedTarget,
                trace,
                preview,
                captured,
                providerRequest,
                providerTools);
    }

    private static TaskResolution resolution(String request, Path workspace) {
        return WorkspaceTargetReconciler.reconcile(
                TaskContractResolver.resolveFromMessages(
                        List.of(ChatMessage.user(request))),
                workspace);
    }

    private static Context inspectorContext() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        return Context.builder(new Config())
                .memory(new SessionMemory())
                .toolRegistry(registry)
                .llm(LlmClient.scripted(List.of("unused")))
                .build();
    }

    private static void assertRawTraceEvidence(RunEvidence evidence) {
        var traceTarget = evidence.trace()
                .taskContract()
                .rolefulTargets()
                .stream()
                .filter(candidate -> candidate.path().equals(
                        evidence.expectedTarget().path()))
                .findFirst()
                .orElseThrow();
        var expected = evidence.expectedTarget().derivation();
        assertEquals(evidence.expectedTarget().role().name(), traceTarget.role());
        assertEquals(expected.sourceText(), traceTarget.sourceText());
        assertEquals(expected.startOffset(), traceTarget.startOffset());
        assertEquals(
                expected.authorityEndOffset(),
                traceTarget.authorityEndOffset());
        assertEquals(expected.endOffset(), traceTarget.endOffset());
        assertEquals(
                expected.sourceText(),
                evidence.rawRequest().substring(
                        expected.startOffset(),
                        expected.endOffset()));
    }

    private static void assertCanonicalProviderParity(
            RunEvidence evidence,
            String rawRequest
    ) {
        assertEquals(evidence.preview().messages(), evidence.provider().messages);
        assertEquals(evidence.captured().messages(), evidence.provider().messages);
        assertEquals(evidence.preview().tools(), evidence.providerTools());
        assertEquals(evidence.captured().tools(), evidence.providerTools());
        String canonicalRequest = Sanitize.sanitizeMessageContent(rawRequest);
        assertTrue(evidence.provider().messages.stream()
                .filter(message -> "user".equals(message.role()))
                .map(ChatMessage::content)
                .anyMatch(canonicalRequest::equals));
        assertTrue(evidence.provider().messages.stream()
                .map(ChatMessage::content)
                .filter(java.util.Objects::nonNull)
                .noneMatch(content -> content.indexOf('\r') >= 0
                        || content.indexOf('\u0000') >= 0
                        || content.indexOf('\u001b') >= 0));
    }

    private static void assertPersistenceRoundTrip(
            RunEvidence evidence,
            Path storeDirectory
    ) {
        String sessionId = evidence.trace().sessionId();
        TurnRecord record = new TurnRecord(
                1,
                Instant.parse("2026-07-31T00:00:00Z"),
                1,
                evidence.rawRequest(),
                "",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                TurnPolicyTrace.empty(),
                evidence.trace().traceId());
        JsonSessionStore store = new JsonSessionStore(storeDirectory);
        assertEquals(
                TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                store.persistTurnEnvelope(
                        sessionId,
                        record,
                        evidence.trace()).state());
        var loaded = store.loadTrace(
                        sessionId,
                        evidence.trace().traceId())
                .orElseThrow()
                .taskContract()
                .rolefulTargets()
                .stream()
                .filter(target -> target.path().equals(
                        evidence.expectedTarget().path()))
                .findFirst()
                .orElseThrow();
        var expected = evidence.expectedTarget().derivation();
        assertEquals(expected.sourceText(), loaded.sourceText());
        assertEquals(expected.startOffset(), loaded.startOffset());
        assertEquals(
                expected.authorityEndOffset(),
                loaded.authorityEndOffset());
        assertEquals(expected.endOffset(), loaded.endOffset());
    }

    private record RunEvidence(
            String rawRequest,
            TargetRef expectedTarget,
            LocalTurnTrace trace,
            PromptRender preview,
            PromptRender captured,
            ChatRequest provider,
            List<String> providerTools
    ) {}
}
