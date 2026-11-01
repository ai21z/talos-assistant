package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.toolcall.CanonicalToolDescriptors;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A directive that names nothing of its own to act on carries mutation authority only through what is pending
 * (MICA invariant I1). With nothing pending it changes nothing, and the runtime states that nothing is pending.
 */
class AssistantTurnPreparationReferentlessDirectiveTest {
    private static final String NOTHING_PENDING =
            "Nothing is pending, so Talos did not change any file. Name the file and the change you want.";
    private static final String DRAFT_REQUEST = "I'd like a short story about Lina, a clockmaker in a seaside town. "
            + "Her clock is always seven minutes slow. Please write it and save it as a text file, not notes.txt. "
            + "I'll give you the filename in a moment.";

    @ParameterizedTest
    @ValueSource(strings = {
            "apply it",
            "make the changes",
            "edit it",
            "Check it and edit it",
            "Apply that proposal now.",
            "Please implement those changes now.",
            "fix",
            "do it now",
            "ok do it",
            "yes, apply it"
    })
    void aReferentlessDirectiveWithNothingPendingIsAnsweredByTheRuntime(String reply, @TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<main>Main</main>\n");

        AssistantTurnPreparation.PreparedTurn prepared = prepare(workspace, new SessionMemory(), reply);

        assertReadOnly(prepared, reply);
        assertTrue(prepared.plan().taskContract().expectedTargets().isEmpty(), reply);
        assertEquals(NOTHING_PENDING, prepared.directAnswer(), reply);
    }

    @Test
    void aReferentlessDirectiveBesideAnUnboundCarriedContextChangesNothingAndIsLeftToTheModel(
            @TempDir Path workspace) {
        // A proposal without provenance is suppressed, so the carried context is pending but not bound.
        ActiveTaskContext unbound = ActiveTaskContext.proposedChanges(
                1, "trace-proposal", List.of("README.md"), "Add an install section.");
        SessionMemory memory = new SessionMemory();
        memory.setActiveTaskContext(unbound);
        memory.setArtifactGoal(ArtifactGoal.fromActiveContext(unbound));

        AssistantTurnPreparation.PreparedTurn prepared = prepare(workspace, memory, "apply it");

        assertReadOnly(prepared, "apply it");
        assertNull(prepared.directAnswer());
    }

    @ParameterizedTest
    @ValueSource(strings = {"save", "draft", "workspace"})
    void aReferentlessDirectiveBesidePendingWorkIsLeftToTheModel(String pending, @TempDir Path workspace) {
        // The save, the draft and the workspace confirmation are each pending, so nothing-is-pending would be false.
        SessionMemory memory = new SessionMemory();
        switch (pending) {
            case "save" -> memory.setPendingContentSave(dev.talos.runtime.context.ContentSaveContinuation.evaluate(
                    dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest(
                            "Save a story about a woman in medieval times."),
                    null, workspace, 1, CapabilityPosture.AGENT).nextPending());
            case "draft" -> {
                var resolution = dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest(DRAFT_REQUEST);
                memory.setPendingContentDraft(new dev.talos.runtime.context.PendingContentDraft(resolution,
                        dev.talos.runtime.context.ContentDraftContinuation.unresolvedOperation(resolution),
                        workspace.toAbsolutePath().normalize().toString(), 1, 6, "abcdef123456", "", "A story."));
            }
            default -> memory.recordPendingWorkspaceMutationConfirmation("Add a footer to index.html", "workspace");
        }
        assertTrue(memory.pendingContentSave() != null || memory.pendingContentDraft() != null
                || memory.pendingWorkspaceMutationConfirmation() != null, pending);

        AssistantTurnPreparation.PreparedTurn prepared = prepare(workspace, memory, "apply it");

        assertReadOnly(prepared, pending);
        assertNull(prepared.directAnswer(), pending);
    }

    @Test
    void aReferentlessDirectiveAfterAFailedWorkspaceSwitchIsNotKeptForAConfirmedReplay(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<main>Main</main>\n");
        SessionMemory memory = new SessionMemory();
        memory.recordFailedWorkspaceSwitch("other", "workspace");
        var recorded = dev.talos.core.llm.ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new dev.talos.core.llm.LlmClient.StreamResult("This should not be reached.", List.of())),
                16_384);
        Context ctx = Context.builder(new Config())
                .llm(recorded.client())
                .memory(memory)
                .toolRegistry(CanonicalToolDescriptors.registry())
                .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                .build();

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages("apply it"), workspace, ctx, new AssistantTurnExecutor.Options());

        assertEquals(NOTHING_PENDING, output.text());
        assertNull(memory.pendingWorkspaceMutationConfirmation());
        assertTrue(recorded.requests().isEmpty(), recorded.requests().toString());
    }

    @Test
    void aReferentlessDirectiveAfterAFailedWorkspaceSwitchKeepsTheConfirmationWhenSomethingIsPending(
            @TempDir Path workspace) {
        // The carried context is pending, so the confirmed replay may still bind it.
        SessionMemory memory = new SessionMemory();
        ActiveTaskContext carried = ActiveTaskContext.verifierFindings(1, "trace-carried", List.of("index.html"),
                List.of("index.html: heading missing"), "FAILED", List.of(),
                dev.talos.runtime.task.StaticWebRequirements.none());
        memory.setActiveTaskContext(carried);
        memory.recordFailedWorkspaceSwitch("other", "workspace");
        var recorded = dev.talos.core.llm.ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new dev.talos.core.llm.LlmClient.StreamResult("This should not be reached.", List.of())),
                16_384);
        Context ctx = Context.builder(new Config())
                .llm(recorded.client())
                .memory(memory)
                .toolRegistry(CanonicalToolDescriptors.registry())
                .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                .build();

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages("apply it"), workspace, ctx, new AssistantTurnExecutor.Options());

        assertTrue(output.text().contains("Confirm if you want this change applied in the current workspace"),
                output.text());
        assertTrue(memory.pendingWorkspaceMutationConfirmation() != null);
        assertTrue(recorded.requests().isEmpty(), recorded.requests().toString());
    }

    @Test
    void aWorkspaceConfirmationDroppedByTheReplyStillCountsAsPending(@TempDir Path workspace) throws Exception {
        // The preflight drops the confirmation before preparation, so the turn boundary keeps what was pending.
        Files.writeString(workspace.resolve("index.html"), "<main>Main</main>" + System.lineSeparator());
        SessionMemory memory = new SessionMemory();
        memory.recordPendingWorkspaceMutationConfirmation("Add a footer to index.html", "workspace");
        var recorded = dev.talos.core.llm.ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new dev.talos.core.llm.LlmClient.StreamResult("Name the file to change.", List.of())),
                16_384);
        Context ctx = Context.builder(new Config())
                .llm(recorded.client())
                .memory(memory)
                .toolRegistry(CanonicalToolDescriptors.registry())
                .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                .build();

        AssistantTurnExecutor.TurnOutput output = AssistantTurnExecutor.execute(
                messages("edit it"), workspace, ctx, new AssistantTurnExecutor.Options());

        assertNull(memory.pendingWorkspaceMutationConfirmation());
        assertFalse(output.text().contains(NOTHING_PENDING), output.text());
        assertFalse(recorded.requests().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"continue", "try again", "yes", "go ahead", "please do"})
    void aReplyThatRequestsNoChangeIsLeftToTheModel(String reply, @TempDir Path workspace) {
        AssistantTurnPreparation.PreparedTurn prepared = prepare(workspace, new SessionMemory(), reply);

        assertReadOnly(prepared, reply);
        assertNull(prepared.directAnswer(), reply);
    }

    @Test
    void aDirectiveThatNamesItsFileIsUnchanged(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<main>Main</main>\n");

        AssistantTurnPreparation.PreparedTurn prepared =
                prepare(workspace, new SessionMemory(), "Check index.html and edit it");

        assertTrue(prepared.plan().taskContract().mutationAllowed());
        assertEquals(Set.of("index.html"), prepared.plan().taskContract().expectedTargets());
        assertNull(prepared.directAnswer());
    }

    @Test
    void aReferentlessRepairDirectiveThatHistoryScopesIsUnchanged(@TempDir Path workspace) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Make index.html a full landing page for my band with style.css and script.js."),
                ChatMessage.assistant("[Task incomplete: Static verification failed - style.css: expected target "
                        + "was not successfully mutated.]\n\nThe requested task is not verified complete."),
                ChatMessage.user("fix it")));

        AssistantTurnPreparation.PreparedTurn prepared = AssistantTurnPreparation.prepare(
                messages, workspace, context(new SessionMemory()), false, CapabilityPosture.AGENT);

        assertTrue(prepared.plan().taskContract().mutationAllowed());
        assertFalse(prepared.plan().taskContract().expectedTargets().isEmpty());
        assertNull(prepared.directAnswer());
    }

    private static Context context(SessionMemory memory) {
        return Context.builder(new Config())
                .memory(memory)
                .toolRegistry(CanonicalToolDescriptors.registry())
                .build();
    }

    private static AssistantTurnPreparation.PreparedTurn prepare(Path workspace, SessionMemory memory, String reply) {
        return AssistantTurnPreparation.prepare(messages(reply), workspace, context(memory), false,
                CapabilityPosture.AGENT);
    }

    private static List<ChatMessage> messages(String reply) {
        return new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("what is in this folder?"),
                ChatMessage.assistant("The folder holds index.html."),
                ChatMessage.user(reply)));
    }

    private static void assertReadOnly(AssistantTurnPreparation.PreparedTurn prepared, String reply) {
        assertFalse(prepared.plan().taskContract().mutationAllowed(), reply + " " + prepared.plan().taskContract());
        assertFalse(prepared.plan().promptTools().contains("talos.write_file"), prepared.plan().promptTools().toString());
        assertFalse(prepared.plan().promptTools().contains("talos.edit_file"), prepared.plan().promptTools().toString());
    }
}
