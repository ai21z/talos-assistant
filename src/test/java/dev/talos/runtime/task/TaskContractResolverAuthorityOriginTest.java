package dev.talos.runtime.task;

import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** History inheritance binds a referent only for a current directive. Assistant prose never adds authority. */
class TaskContractResolverAuthorityOriginTest {
    private static final String PRIOR = "Create index.html, styles.css, and scripts.js for a BMI calculator.";
    private static final String INCOMPLETE = "[Task incomplete: Static verification failed - Expected targets were not "
            + "all mutated.]\n\nThe requested task is not verified complete.\n\nRemaining static verification problems:\n"
            + "- scripts.js was expected but was not created.";

    @ParameterizedTest
    @ValueSource(strings = {
            "it still doesn't work",
            "it does not work",
            "nothing changed",
            "but there is no css",
            "no, you never added any css",
            "the page looks better now",
            "I fixed it myself",
            "Look, there is no css.",
            "Do you see that there is no css?",
            "nothing changed, write me a summary of what happened"
    })
    void complaintsAndCommentsAfterAnIncompleteOutcomeStayReadOnly(String reply) {
        TaskContract contract = TaskContractResolver.fromMessages(history(reply));

        assertFalse(contract.mutationAllowed(), reply + " -> " + contract);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "nothing changed, try one more time",
            "try again",
            "fix the remaining problems",
            "it still doesn't work, please fix it"
    })
    void retryDirectivesInheritThePriorUserScope(String reply) {
        TaskContract contract = TaskContractResolver.fromMessages(history(reply));

        assertTrue(contract.mutationAllowed(), reply + " -> " + contract);
        assertEquals(Set.of("index.html", "styles.css", "scripts.js"), contract.expectedTargets(), reply);
    }

    @Test
    void aPageRequestDoesNotInventTheConventionalAssetTriplet() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("can you make a full page with modern dark synth style?"),
                ChatMessage.assistant("I updated index.html with a dark synthwave layout."),
                ChatMessage.user("yes! it's better still not a full web page tho. Make it a full page please!"));

        TaskContract contract = TaskContractResolver.fromMessages(messages);

        assertTrue(contract.mutationAllowed(), contract.toString());
        assertFalse(contract.expectedTargets().contains("style.css"), contract.toString());
        assertFalse(contract.expectedTargets().contains("script.js"), contract.toString());
    }

    @Test
    void namingTheAssetsStillAddsTheConventionalTargets() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("can you make a full page with modern dark synth style?"),
                ChatMessage.assistant("I updated index.html with a dark synthwave layout."),
                ChatMessage.user("now add the css and js files for it"));

        TaskContract contract = TaskContractResolver.fromMessages(messages);

        assertTrue(contract.mutationAllowed(), contract.toString());
        assertTrue(contract.expectedTargets().contains("style.css"), contract.toString());
        assertTrue(contract.expectedTargets().contains("script.js"), contract.toString());
    }

    @Test
    void theHistoryCeilingKeepsAHistoryContractOnlyForADirective() {
        // The history resolution is synthetic: it stands for any branch that would carry authority.
        TaskResolution history = TaskContractResolver.resolveFromUserRequest("rewrite notes.txt with a summary");
        TaskResolution comment = TaskContractResolver.resolveFromUserRequest("the page looks better");
        TaskResolution directive = TaskContractResolver.resolveFromUserRequest("make it better");

        assertTrue(history.contract().mutationAllowed());
        assertEquals(comment, TaskContractResolver.withinAuthorityOrigin("the page looks better", comment, history));
        assertEquals(history, TaskContractResolver.withinAuthorityOrigin("make it better", directive, history));
    }

    private static List<ChatMessage> history(String reply) {
        return List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(PRIOR),
                ChatMessage.assistant(INCOMPLETE),
                ChatMessage.user(reply));
    }
}
