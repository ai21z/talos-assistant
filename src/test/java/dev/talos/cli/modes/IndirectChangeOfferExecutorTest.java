package dev.talos.cli.modes;

import dev.talos.cli.repl.ActiveTaskContextUpdater;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An indirect change request stays read-only and ends with a runtime offer, and a plain acceptance on the next turn
 * applies the change to the recorded files under approval (MICA decision D6, P3b). The provider is scripted. The
 * executor, tools, approval-asking processor, trace capture, updater and workspace are real.
 */
class IndirectChangeOfferExecutorTest {
    private static final String T11 = "I dont like it ... a modern dark synthwave landing page is more appropriate "
            + "than this. Can you help me?";
    private static final List<String> SITE = List.of("index.html", "style.css", "script.js");
    private static final String OFFER = "Reply yes to let Talos apply this change to index.html, style.css and "
            + "script.js. File changes still follow your approval settings.";
    private static final String OFFER_EVENT = "CHANGE_OFFER_RENDERED";

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void theIndirectRequestEndsWithTheOfferAndExposesNoWriteTool(@TempDir Path workspace) throws Exception {
        site(workspace);

        var turn = turn(workspace, verifiedSite(), 2, List.of(ChatMessage.user(T11)),
                List.of("You could paste this dark design into index.html: <main class=\"dark\"></main>"),
                CapabilityPosture.AGENT, new AtomicInteger());

        assertTrue(turn.text().stripTrailing().endsWith("\n\n" + OFFER), turn.text());
        List<String> tools = offeredTools(turn);
        assertFalse(tools.contains("talos.write_file"), tools.toString());
        assertFalse(tools.contains("talos.edit_file"), tools.toString());
        assertTrue(turn.trace().events().stream()
                .anyMatch(event -> OFFER_EVENT.equals(event.type())));
        assertEquals("<main>Light</main>\n", Files.readString(workspace.resolve("index.html")));
    }

    @Test
    void aPlainYesOnTheNextTurnAppliesTheChangeToTheRecordedFilesUnderApproval(@TempDir Path workspace)
            throws Exception {
        site(workspace);
        SessionMemory memory = verifiedSite();
        var offered = turn(workspace, memory, 2, List.of(ChatMessage.user(T11)),
                List.of("A darker palette would suit a synthwave page."), CapabilityPosture.AGENT, new AtomicInteger());
        var update = new ActiveTaskContextUpdater().updateAfterTurn(
                turnResult(offered, 2), T11, memory.activeTaskContext(), memory.artifactGoal());
        memory.setActiveTaskContext(update.activeTaskContext());
        memory.setArtifactGoal(update.artifactGoal());
        AtomicInteger approvals = new AtomicInteger();

        var accepted = turn(workspace, memory, 3, List.of(
                        ChatMessage.user(T11), ChatMessage.assistant(offered.text()), ChatMessage.user("yes")),
                List.of("{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"index.html\","
                                + "\"content\":\"<main class=\\\"synthwave\\\">Dark</main>\\n\"}}",
                        "Applied the dark synthwave design to index.html."),
                CapabilityPosture.AGENT, approvals);

        assertEquals("<main class=\"synthwave\">Dark</main>\n", Files.readString(workspace.resolve("index.html")),
                accepted.text());
        assertTrue(approvals.get() >= 1, accepted.text());
        assertTrue(offeredTools(accepted).contains("talos.write_file"), offeredTools(accepted).toString());
        assertTrue(accepted.audit().policyTrace().expectedTargets().containsAll(SITE),
                accepted.audit().policyTrace().expectedTargets().toString());
    }

    @Test
    void aToolModeCorrectionKeepsTheUsersRequestForTheOffer(@TempDir Path workspace) throws Exception {
        site(workspace);

        var turn = turn(workspace, verifiedSite(), 2, List.of(ChatMessage.user(T11)),
                List.of("```json\n{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}\n```",
                        "A darker palette would suit a synthwave page."),
                CapabilityPosture.AGENT, new AtomicInteger(), true);

        // MICA D7: the runtime correction notice is not the user's request, so the offer still follows T11.
        assertTrue(turn.provider().requests().stream().anyMatch(request -> request.messages.stream()
                .anyMatch(message -> message.content() != null && message.content().startsWith("[Runtime notice]"))),
                "The text call earned one correction.");
        assertTrue(turn.text().stripTrailing().endsWith("\n\n" + OFFER), turn.text());
        assertEquals("<main>Light</main>\n", Files.readString(workspace.resolve("index.html")));
    }

    @Test
    void theAnswerItselfCannotCreateTheOffer(@TempDir Path workspace) throws Exception {
        site(workspace);
        SessionMemory memory = verifiedSite();

        // The message is not an indirect request, so the runtime renders no offer even though the model prints one.
        var turn = turn(workspace, memory, 2, List.of(ChatMessage.user("What is index.html for?")),
                List.of("It is the page.\n\n" + OFFER), CapabilityPosture.AGENT, new AtomicInteger());
        var update = new ActiveTaskContextUpdater().updateAfterTurn(
                turnResult(turn, 2), "What is index.html for?", memory.activeTaskContext(), memory.artifactGoal());

        assertFalse(offerBound(update));
        assertTrue(turn.trace().events().stream()
                .noneMatch(event -> OFFER_EVENT.equals(event.type())));
    }

    @Test
    void askAndPlanModesNeverOfferTheChangeEvenWhenTheModelPrintsTheLine(@TempDir Path workspace)
            throws Exception {
        site(workspace);

        for (CapabilityPosture posture : List.of(CapabilityPosture.ASK_READ_ONLY, CapabilityPosture.PLAN_READ_ONLY)) {
            SessionMemory memory = verifiedSite();
            var turn = turn(workspace, memory, 2, List.of(ChatMessage.user(T11)),
                    List.of("A darker palette would suit it.\n\n" + OFFER), posture,
                    new AtomicInteger());
            var update = new ActiveTaskContextUpdater().updateAfterTurn(
                    turnResult(turn, 2), T11, memory.activeTaskContext(), memory.artifactGoal());

            assertTrue(turn.trace().events().stream()
                    .noneMatch(event -> OFFER_EVENT.equals(event.type())), posture
                    + ": " + turn.text());
            assertFalse(offerBound(update), posture.toString());
        }
    }

    @Test
    void questionsAndFilesThatNoLongerExistGetNoOffer(@TempDir Path workspace) throws Exception {
        site(workspace);

        for (String request : List.of(
                "can you help me understand the css?",
                "I don't like it.",
                "is a dark theme more appropriate?")) {
            var turn = turn(workspace, verifiedSite(), 2, List.of(ChatMessage.user(request)),
                    List.of("Here is an explanation."), CapabilityPosture.AGENT, new AtomicInteger());

            assertFalse(turn.text().contains("Reply yes to let Talos apply"), request + ": " + turn.text());
        }

        Files.delete(workspace.resolve("script.js"));
        var gone = turn(workspace, verifiedSite(), 2, List.of(ChatMessage.user(T11)),
                List.of("A darker palette would suit it."), CapabilityPosture.AGENT, new AtomicInteger());
        assertFalse(gone.text().contains("Reply yes to let Talos apply"), gone.text());
    }

    @Test
    void anExpiredTaskContextGetsNoOffer(@TempDir Path workspace) throws Exception {
        site(workspace);

        // The verified context of turn 1 is active through turn 4.
        var turn = turn(workspace, verifiedSite(), 5, List.of(ChatMessage.user(T11)),
                List.of("A darker palette would suit it."), CapabilityPosture.AGENT, new AtomicInteger());

        assertFalse(turn.text().contains("Reply yes to let Talos apply"), turn.text());
    }

    @Test
    void aMessageNamingAnotherFileGetsNoOfferForTheTaskFiles(@TempDir Path workspace) throws Exception {
        site(workspace);
        Files.writeString(workspace.resolve("about.html"), "<main>About</main>\n");
        String request = "I'd prefer about.html to be dark. Can you help me?";

        var turn = turn(workspace, verifiedSite(), 2, List.of(ChatMessage.user(request)),
                List.of("A darker palette would suit it."), CapabilityPosture.AGENT, new AtomicInteger());

        assertFalse(turn.text().contains("Reply yes to let Talos apply"), turn.text());
        assertTrue(turn.trace().events().stream().noneMatch(event -> OFFER_EVENT.equals(event.type())));
    }

    @Test
    void aFileReachedThroughALinkedFolderOutsideTheWorkspaceGetsNoOffer(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("page.html"), "<main>Outside</main>\n");
        site(workspace);
        try {
            Files.createSymbolicLink(workspace.resolve("linked"), outside);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are not available: " + unsupported);
        }
        var memory = new SessionMemory();
        memory.setActiveTaskContext(ActiveTaskContext.verifiedMutation(
                1, "trc-1", List.of("index.html", "linked/page.html"), "VERIFIED"));

        var turn = turn(workspace, memory, 2, List.of(ChatMessage.user(T11)),
                List.of("A darker palette would suit it."), CapabilityPosture.AGENT, new AtomicInteger());

        assertFalse(turn.text().contains("Reply yes to let Talos apply"), turn.text());
    }

    private record Turn(
            AssistantTurnExecutor.TurnOutput output,
            ScriptedNativeLlmClient.RecordedClient provider,
            TurnAudit audit,
            LocalTurnTrace trace) {
        String text() {
            return output.text();
        }
    }

    private static Turn turn(
            Path workspace,
            SessionMemory memory,
            int turnNumber,
            List<ChatMessage> conversation,
            List<String> responses,
            CapabilityPosture posture,
            AtomicInteger approvals) {
        return turn(workspace, memory, turnNumber, conversation, responses, posture, approvals, false);
    }

    private static Turn turn(
            Path workspace,
            SessionMemory memory,
            int turnNumber,
            List<ChatMessage> conversation,
            List<String> responses,
            CapabilityPosture posture,
            AtomicInteger approvals,
            boolean nativeTools) {
        String request = conversation.getLast().content();
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.begin(
                "trc-offer-" + turnNumber, "sid", turnNumber, "2026-10-27T00:00:00Z", "workspace-hash", "test",
                "llama_cpp", "test-model", request);
        var registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());
        registry.register(new dev.talos.tools.impl.FileWriteTool());
        registry.register(new dev.talos.tools.impl.FileEditTool());
        var processor = new dev.talos.runtime.TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry,
                dev.talos.runtime.ApprovalPolicy.ALWAYS_ASK,
                new dev.talos.runtime.checkpoint.CheckpointService(
                        new dev.talos.runtime.checkpoint.FileBundleCheckpointStore(workspace.resolveSibling(
                                workspace.getFileName() + "-checkpoints"))));
        var loop = new dev.talos.runtime.ToolCallLoop(processor, 6);
        var provider = recordingProvider(registry, responses, nativeTools);
        var ctx = Context.builder(new Config())
                .llm(provider.client())
                .memory(memory)
                .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.addAll(conversation);
        dev.talos.runtime.TurnAuditCapture.begin();
        try {
            var output = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options().capabilityPosture(posture));
            TurnAudit audit = dev.talos.runtime.TurnAuditCapture.end();
            return new Turn(output, provider, audit, LocalTurnTraceCapture.complete());
        } finally {
            if (dev.talos.runtime.TurnAuditCapture.isActive()) dev.talos.runtime.TurnAuditCapture.end();
        }
    }

    private static boolean offerBound(ActiveTaskContextUpdater.Update update) {
        return update.activeTaskContext().kind() == ActiveTaskContext.Kind.PROPOSED_CHANGES;
    }

    private static TurnResult turnResult(Turn turn, int turnNumber) {
        return new TurnResult(
                new Result.Ok(turn.text()),
                null,
                turnNumber,
                Duration.ofMillis(25),
                new TurnAudit(List.of(), 0, 0, 0, turn.audit().policyTrace(), turn.trace()));
    }

    private static List<String> offeredTools(Turn turn) {
        var policy = turn.audit().policyTrace();
        assertTrue(policy != null, turn.text());
        var tools = new ArrayList<String>(policy.nativeTools());
        tools.addAll(policy.promptTools());
        return tools;
    }

    private static ScriptedNativeLlmClient.RecordedClient recordingProvider(
            ToolRegistry registry, List<String> responses, boolean nativeTools) {
        List<LlmClient.StreamResult> results = responses.stream()
                .map(response -> new LlmClient.StreamResult(response, List.of()))
                .toList();
        var recorded = nativeTools
                ? ScriptedNativeLlmClient.recordingWithContextWindow(results, 16_384)
                : ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(results, 16_384);
        recorded.client().setToolSpecs(registry.descriptors().stream()
                .map(descriptor -> new ToolSpec(
                        descriptor.name(),
                        descriptor.description(),
                        descriptor.parametersSchema() == null ? "{}" : descriptor.parametersSchema()))
                .toList());
        return recorded;
    }

    private static SessionMemory verifiedSite() {
        var memory = new SessionMemory();
        memory.setActiveTaskContext(ActiveTaskContext.verifiedMutation(1, "trc-1", SITE, "VERIFIED"));
        return memory;
    }

    private static void site(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<main>Light</main>\n");
        Files.writeString(workspace.resolve("style.css"), "body { color: black; }\n");
        Files.writeString(workspace.resolve("script.js"), "void 0;\n");
    }
}
