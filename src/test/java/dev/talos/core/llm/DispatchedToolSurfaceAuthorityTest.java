package dev.talos.core.llm;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DispatchedToolSurfaceAuthorityTest {
    private static final String ADVERTISED = "talos.surface_advertised";
    private static final String OMITTED = "talos.surface_omitted";

    @TempDir
    Path workspace;

    @AfterEach
    void clearCaptures() {
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
        ProviderResponseCapture.clear();
    }

    @Test
    void nativeProposalOmittedFromDispatchedSurfaceIsRejectedBeforeApprovalOrIo() {
        ProbeOutcome outcome = runNativeCase(
                "omitted-surface",
                List.of(spec(ADVERTISED)),
                ChatRequestControls.defaults().withToolAuthorityMode(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE));

        assertEquals(List.of(ADVERTISED), outcome.request().tools.stream()
                .map(ToolSpec::name)
                .toList());
        assertBlockedBeforeEffects(outcome);
    }

    @Test
    void nativeProposalDifferentFromNamedToolIsRejectedBeforeApprovalOrIo() {
        ProbeOutcome outcome = runNativeCase(
                "named-mismatch",
                List.of(spec(ADVERTISED), spec(OMITTED)),
                new ChatRequestControls(
                        ToolChoiceMode.NAMED,
                        ADVERTISED,
                        ResponseFormatMode.TEXT,
                        "",
                        List.of())
                        .withToolAuthorityMode(
                                ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE));

        assertEquals(List.of(ADVERTISED, OMITTED), outcome.request().tools.stream()
                .map(ToolSpec::name)
                .toList());
        assertEquals(ToolChoiceMode.NAMED, outcome.request().controls.toolChoice());
        assertEquals(ADVERTISED, outcome.request().controls.namedTool());
        assertBlockedBeforeEffects(outcome);
    }

    @Test
    void textJsonProposalOmittedFromSelectedSurfaceIsRejectedBeforeApprovalOrIo() {
        ProbeOutcome outcome = runTextJsonCase("text-omitted-surface");

        assertTrue(outcome.request().tools.isEmpty(),
                "text-JSON authority must not manufacture a native provider surface");
        String dispatchedMessages = outcome.request().messages.stream()
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + '\n' + right);
        assertTrue(dispatchedMessages.contains(ADVERTISED));
        assertFalse(dispatchedMessages.contains(OMITTED));
        assertBlockedBeforeEffects(outcome);
    }

    private ProbeOutcome runNativeCase(
            String traceSuffix,
            List<ToolSpec> requestSurface,
            ChatRequestControls controls
    ) {
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger advertisedInvocations = new AtomicInteger();
        AtomicInteger omittedInvocations = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(false);
        registry.register(new ProbeTool(ADVERTISED, advertisedInvocations));
        registry.register(new ProbeTool(OMITTED, omittedInvocations));
        List<ToolSpec> broadTurnSurface = List.of(spec(ADVERTISED), spec(OMITTED));
        ChatMessage.NativeToolCall omittedProposal = new ChatMessage.NativeToolCall(
                "call-" + traceSuffix,
                OMITTED,
                Map.of("path", "omitted.txt", "content", "must not be written"));
        CapturingResolver resolver = new CapturingResolver(omittedProposal);
        Config config = engineConfig();

        LocalTurnTraceCapture.begin(
                "trace-t1193-" + traceSuffix,
                "session-t1193-surface",
                1,
                "2026-07-21T00:00:00Z",
                "workspace-t1193-surface",
                "test",
                "probe",
                "model",
                "T1193 exact provider tool surface authority");

        try (LlmClient client = new LlmClient(config, resolver)) {
            LlmClient.StreamResult first = client.chatFull(
                    List.of(ChatMessage.user("Use only the authorized tool.")),
                    requestSurface,
                    controls);
            assertEquals(List.of(omittedProposal), first.toolCalls());
            assertTrue(first.provenance().providerToolCallEligible(),
                    "the selected response remains mode/transport eligible; the proposal must be rejected by its surface");

            ApprovalGate gate = (description, detail) -> {
                approvals.incrementAndGet();
                return true;
            };
            Context context = Context.builder(config)
                    .llm(client)
                    .sandbox(new Sandbox(workspace, Map.of()))
                    .toolRegistry(registry)
                    .nativeToolSpecs(broadTurnSurface)
                    .build();
            TurnProcessor processor = new TurnProcessor(
                    null,
                    gate,
                    registry,
                    ApprovalPolicy.ALWAYS_ASK);
            List<ChatMessage> loopMessages = new ArrayList<>(List.of(
                    ChatMessage.system("system"),
                    ChatMessage.user("Use only the authorized tool.")));
            TurnTaskContractCapture.set(
                    TaskContractResolver.resolveFromMessages(loopMessages),
                    loopMessages);
            ToolCallLoop.LoopResult loop;
            try {
                loop = new ToolCallLoop(processor, 2).run(
                        first.text(),
                        first.toolCalls(),
                        first.provenance(),
                        loopMessages,
                        workspace,
                        context);
            } finally {
                TurnTaskContractCapture.clear();
            }
            return new ProbeOutcome(
                    resolver.firstRequest(),
                    first.provenance(),
                    loop,
                    LocalTurnTraceCapture.complete(),
                    approvals.get(),
                    advertisedInvocations.get(),
                    omittedInvocations.get());
        }
    }

    private ProbeOutcome runTextJsonCase(String traceSuffix) {
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger advertisedInvocations = new AtomicInteger();
        AtomicInteger omittedInvocations = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(false);
        registry.register(new ProbeTool(ADVERTISED, advertisedInvocations));
        registry.register(new ProbeTool(OMITTED, omittedInvocations));
        List<ToolSpec> broadTurnSurface = List.of(spec(ADVERTISED), spec(OMITTED));
        String proposal = "```json\n"
                + "{\"name\":\"" + OMITTED + "\",\"arguments\":{"
                + "\"path\":\"omitted.txt\","
                + "\"content\":\"must not be written\"}}\n"
                + "```";
        TextCapturingResolver resolver = new TextCapturingResolver(proposal);
        Config config = engineConfig();
        List<ChatMessage> messages = List.of(
                ChatMessage.system("Available Tools\n- " + ADVERTISED
                        + "\nEmit one fenced JSON object when calling this tool."),
                ChatMessage.user("Use only the authorized tool."));

        LocalTurnTraceCapture.begin(
                "trace-t1193-" + traceSuffix,
                "session-t1193-surface",
                1,
                "2026-07-21T00:00:00Z",
                "workspace-t1193-surface",
                "test",
                "probe",
                "model",
                "T1193 exact text provider tool surface authority");

        try (LlmClient client = new LlmClient(config, resolver)) {
            LlmClient.StreamResult first = client.chatFull(
                    messages,
                    List.of(spec(ADVERTISED)),
                    ChatRequestControls.defaults().withToolAuthorityMode(
                            ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON));
            assertTrue(first.toolCalls().isEmpty());
            assertTrue(first.provenance().providerToolCallEligible(),
                    "the selected response remains mode/transport eligible; the proposal must be rejected by its surface");

            ApprovalGate gate = (description, detail) -> {
                approvals.incrementAndGet();
                return true;
            };
            Context context = Context.builder(config)
                    .llm(client)
                    .sandbox(new Sandbox(workspace, Map.of()))
                    .toolRegistry(registry)
                    .nativeToolSpecs(broadTurnSurface)
                    .build();
            TurnProcessor processor = new TurnProcessor(
                    null,
                    gate,
                    registry,
                    ApprovalPolicy.ALWAYS_ASK);
            List<ChatMessage> loopMessages =
                    new ArrayList<>(messages);
            TurnTaskContractCapture.set(
                    TaskContractResolver.resolveFromMessages(loopMessages),
                    loopMessages);
            ToolCallLoop.LoopResult loop;
            try {
                loop = new ToolCallLoop(processor, 2).run(
                        first.text(),
                        first.toolCalls(),
                        first.provenance(),
                        loopMessages,
                        workspace,
                        context);
            } finally {
                TurnTaskContractCapture.clear();
            }
            return new ProbeOutcome(
                    resolver.firstRequest(),
                    first.provenance(),
                    loop,
                    LocalTurnTraceCapture.complete(),
                    approvals.get(),
                    advertisedInvocations.get(),
                    omittedInvocations.get());
        }
    }

    private void assertBlockedBeforeEffects(ProbeOutcome outcome) {
        assertEquals(0, outcome.approvals());
        assertEquals(0, outcome.advertisedInvocations());
        assertEquals(0, outcome.omittedInvocations());
        assertEquals(0, outcome.loop().toolsInvoked());
        assertFalse(outcome.loop().toolNames().contains(OMITTED));
        assertFalse(Files.exists(workspace.resolve("omitted.txt")));
        assertEquals(0, countEvents(outcome.trace(), "APPROVAL_REQUIRED"));
        assertEquals(0, countEvents(outcome.trace(), "APPROVAL_GRANTED"));
        assertEquals(0, countEvents(outcome.trace(), "CHECKPOINT_CAPTURED"));
        assertEquals(0, countEvents(outcome.trace(), "TOOL_EXECUTED"));
        assertTrue(outcome.trace().events().stream()
                        .filter(event -> "TOOL_CALL_AUTHORITY_REJECTED".equals(event.type()))
                        .map(event -> event.data().get("executionAuthority"))
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .anyMatch(authority -> "PROVIDER_TOOL_SURFACE_MISMATCH".equals(
                                authority.get("reasonCode"))),
                outcome.trace().events().toString());
    }

    private static long countEvents(LocalTurnTrace trace, String type) {
        return trace.events().stream().filter(event -> type.equals(event.type())).count();
    }

    private static ToolSpec spec(String name) {
        return new ToolSpec(name, "T1193 exact surface probe", "{\"type\":\"object\"}");
    }

    private static Config engineConfig() {
        Config config = new Config();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "test");
        config.data.put("llm", llm);
        config.data.put("limits", Map.of(
                "response_max_chars", 10_000,
                "llm_timeout_ms", 5_000L,
                "llm_idle_ms", 0L));
        config.data.put("tools", Map.of("native_calling", true));
        return config;
    }

    private static final class CapturingResolver implements LlmEngineResolver {
        private final ChatMessage.NativeToolCall proposal;
        private final List<ChatRequest> requests = new ArrayList<>();

        private CapturingResolver(ChatMessage.NativeToolCall proposal) {
            this.proposal = proposal;
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768, true);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            requests.add(request);
            if (requests.size() == 1) {
                return Stream.of(
                        TokenChunk.ofToolCalls(List.of(proposal)),
                        TokenChunk.eos("tool_calls"));
            }
            return Stream.of(TokenChunk.of("done"), TokenChunk.eos("stop"));
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff
        ) {
            return ObservedProviderTransportFixture.dispatch(
                    request, handoff, () -> chatStream(request));
        }

        private ChatRequest firstRequest() {
            return requests.getFirst();
        }

        @Override
        public void close() {
        }
    }

    private static final class TextCapturingResolver implements LlmEngineResolver {
        private final String proposal;
        private final List<ChatRequest> requests = new ArrayList<>();

        private TextCapturingResolver(String proposal) {
            this.proposal = proposal;
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768, false);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            requests.add(request);
            if (requests.size() == 1) {
                return Stream.of(TokenChunk.of(proposal), TokenChunk.eos("stop"));
            }
            return Stream.of(TokenChunk.of("done"), TokenChunk.eos("stop"));
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff
        ) {
            return ObservedProviderTransportFixture.dispatch(
                    request, handoff, () -> chatStream(request));
        }

        private ChatRequest firstRequest() {
            return requests.getFirst();
        }

        @Override
        public void close() {
        }
    }

    private record ProbeTool(String name, AtomicInteger invocations) implements TalosTool {
        @Override
        public String description() {
            return "T1193 exact surface probe";
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    name,
                    description(),
                    "{\"type\":\"object\"}",
                    ToolRiskLevel.WRITE);
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext ctx) {
            invocations.incrementAndGet();
            try {
                Files.writeString(ctx.workspace().resolve(call.param("path")), call.param("content"));
                return ToolResult.ok("executed " + name);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private record ProbeOutcome(
            ChatRequest request,
            ProviderResponseCapture.Receipt receipt,
            ToolCallLoop.LoopResult loop,
            LocalTurnTrace trace,
            int approvals,
            int advertisedInvocations,
            int omittedInvocations
    ) {}
}
