package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.context.RepairOffer;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.task.StaticWebRequirements;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authority origin (MICA invariant I1, decision D2): mutation authority comes only from the current
 * message or from a reply that binds a typed confirmation issued by the runtime. Carried context and
 * history may bind referents. They never originate authority or widen the required targets.
 */
class AssistantTurnPreparationAuthorityOriginTest {
    private static final List<String> SITE = List.of("index.html", "script.js", "style.css");
    private static final String PRIOR_REQUEST =
            "Make index.html a full landing page for my band with style.css and script.js.";
    private static final String FAILED_ANSWER = "[Task incomplete: Static verification failed - style.css: expected target "
            + "was not successfully mutated.; script.js: expected target was not successfully mutated.]\n\n"
            + "The requested task is not verified complete. Applied changes, if any, are workspace changes only; "
            + "unresolved static problems remain.";

    @Test
    void turnFourteenCommentAfterAFailedTurnIsReadOnly(@TempDir Path workspace) {
        SessionMemory memory = memoryWith(failedSite());
        AssistantTurnPreparation.PreparedTurn prepared = prepare(workspace, memory,
                "Hm... i dont know why you say failed. The page looks better this time.");

        assertReadOnly(prepared);
        assertEquals(failedSite(), memory.activeTaskContext(), "carried context stays available and unconsumed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "the page looks great now",
            "nice, that's better",
            "why did it fail?",
            "is it verified now?",
            "it still doesn't work",
            "no, you never added any css",
            "but there is no css",
            "I fixed it myself",
            "does it look better now?",
            "still bad",
            "nothing changed"
    })
    void nonDirectiveRepliesNeverMutate(String reply, @TempDir Path workspace) {
        SessionMemory memory = memoryWith(failedSite());
        AssistantTurnPreparation.PreparedTurn prepared = prepare(workspace, memory, reply);

        assertReadOnly(prepared);
        assertEquals(failedSite(), memory.activeTaskContext(), reply);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "fix the remaining problems",
            "make it better and more modern",
            "nothing changed, try one more time",
            "can you fix it?",
            "please make it look better"
    })
    void directivesBindTheCarriedReferent(String reply, @TempDir Path workspace) {
        SessionMemory memory = memoryWith(failedSite());
        AssistantTurnPreparation.PreparedTurn prepared = prepare(workspace, memory, reply);

        assertTrue(prepared.plan().taskContract().mutationAllowed(), reply);
        assertEquals(Set.copyOf(SITE), Set.copyOf(prepared.plan().taskContract().expectedTargets()), reply);
    }

    @Test
    void turnThirteenPageRequestRequiresOnlyTheExistingPage(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"),
                "<!doctype html><html><body><h1>Dead Synth</h1></body></html>");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("can you make a full page with modern dark synth style?"),
                ChatMessage.assistant("I updated index.html with a dark synthwave layout."),
                ChatMessage.user("can you fix it?"),
                ChatMessage.assistant("I rewrote index.html as a landing page."),
                ChatMessage.user("yes! it's better still not a full web page tho. Make it a full page please!")));

        AssistantTurnPreparation.PreparedTurn prepared = AssistantTurnPreparation.prepare(
                messages, workspace, context(new SessionMemory()), false, CapabilityPosture.AGENT);

        assertTrue(prepared.plan().taskContract().mutationAllowed());
        assertEquals(Set.of("index.html"), prepared.plan().taskContract().expectedTargets());
    }

    @Test
    void aVagueRedesignBindsTheStaticWebFilesThatExistOnDisk(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<!doctype html><html><head>"
                + "<link rel=\"stylesheet\" href=\"style.css\"></head><body><script src=\"script.js\"></script>"
                + "</body></html>");
        Files.writeString(workspace.resolve("style.css"), "body { color: #eee; }");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Create index.html, style.css, and script.js for Retrocats."),
                ChatMessage.assistant("Created index.html, style.css, and script.js."),
                ChatMessage.user("modify the files according to my intent, it is still bad")));

        AssistantTurnPreparation.PreparedTurn prepared = AssistantTurnPreparation.prepare(
                messages, workspace, context(new SessionMemory()), false, CapabilityPosture.AGENT);

        assertTrue(prepared.plan().taskContract().mutationAllowed());
        assertEquals(Set.copyOf(SITE), prepared.plan().taskContract().expectedTargets());
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "go ahead", "yes please", "do it"})
    void anAffirmationBindsARenderedRepairOffer(String reply, @TempDir Path workspace) {
        ActiveTaskContext offered = failedSite().withRepairOffer(FAILED_ANSWER + "\n\n" + RepairOffer.LINE);
        assertTrue(offered.hasBoundRepairOffer());
        SessionMemory memory = memoryWith(offered);

        AssistantTurnPreparation.PreparedTurn prepared = prepare(workspace, memory, reply);

        assertTrue(prepared.plan().taskContract().mutationAllowed(), reply);
        assertEquals(Set.copyOf(SITE), Set.copyOf(prepared.plan().taskContract().expectedTargets()), reply);
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "go ahead"})
    void anAffirmationWithoutAnOfferHasNoAuthority(String reply, @TempDir Path workspace) {
        SessionMemory memory = memoryWith(failedSite());
        AssistantTurnPreparation.PreparedTurn prepared = prepare(workspace, memory, reply);

        assertReadOnly(prepared);
    }

    @Test
    void aFreshExplicitRequestIsUnchanged(@TempDir Path workspace) {
        SessionMemory memory = memoryWith(failedSite());
        AssistantTurnPreparation.PreparedTurn prepared =
                prepare(workspace, memory, "rewrite notes.txt with a summary");

        assertTrue(prepared.plan().taskContract().mutationAllowed());
        assertEquals(Set.of("notes.txt"), prepared.plan().taskContract().expectedTargets());
    }

    private static ActiveTaskContext failedSite() {
        return ActiveTaskContext.verifierFindings(
                1,
                "trace-turn-13",
                SITE,
                List.of("style.css: expected target was not successfully mutated.",
                        "script.js: expected target was not successfully mutated."),
                "FAILED",
                List.of(),
                StaticWebRequirements.none());
    }

    private static SessionMemory memoryWith(ActiveTaskContext saved) {
        SessionMemory memory = new SessionMemory();
        memory.setActiveTaskContext(saved);
        memory.setArtifactGoal(ArtifactGoal.fromActiveContext(saved));
        return memory;
    }

    private static Context context(SessionMemory memory) {
        return Context.builder(new Config())
                .memory(memory)
                .toolRegistry(CanonicalToolDescriptors.registry())
                .build();
    }

    private static AssistantTurnPreparation.PreparedTurn prepare(Path workspace, SessionMemory memory, String reply) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(PRIOR_REQUEST),
                ChatMessage.assistant(FAILED_ANSWER),
                ChatMessage.user(reply)));
        return AssistantTurnPreparation.prepare(messages, workspace, context(memory), false, CapabilityPosture.AGENT);
    }

    private static void assertReadOnly(AssistantTurnPreparation.PreparedTurn prepared) {
        assertFalse(prepared.plan().taskContract().mutationAllowed(), prepared.plan().taskContract().toString());
        assertFalse(prepared.plan().promptTools().contains("talos.write_file"), prepared.plan().promptTools().toString());
        assertFalse(prepared.plan().promptTools().contains("talos.edit_file"), prepared.plan().promptTools().toString());
    }
}
