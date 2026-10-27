package dev.talos.runtime;

import dev.talos.runtime.intent.ContentSaveRequest;
import dev.talos.runtime.intent.MutationActionEvidence;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class MutationLegacyFallbackFrameTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "I want to edit README.md.",
            "We need to update style.css.",
            "I would like to change the page.",
            "I want to create a modern BMI calculator website."
    })
    void firstPersonDesireDoesNotWidenLegacyFallback(String request) {
        assertNotEquals(MutationIntent.Decision.EXECUTE_MUTATION,
                MutationIntent.classifyShape(request).decision(), request);
        assertFalse(TaskContractResolver.fromUserRequest(request).mutationAllowed(), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I want to edit README.md and update style.css.",
            "We need to edit README.md and update style.css.",
            "I would like to edit README.md and update style.css."
    })
    void coordinatedActionsRetainTheGoverningDesireFrame(String request) {
        var evidence = MutationActionEvidence.fromRequest(request);
        int edit = request.indexOf("edit");
        int update = request.indexOf("update");

        assertTrue(evidence.permits(edit, edit + "edit README.md".length()));
        assertTrue(evidence.permits(update, update + "update style.css".length()));
        assertFalse(evidence.permitsLegacyFallback(edit, edit + "edit README.md".length()));
        assertFalse(evidence.permitsLegacyFallback(update, update + "update style.css".length()));
        assertNotEquals(MutationIntent.Decision.EXECUTE_MUTATION,
                MutationIntent.classifyShape(request).decision());
        assertFalse(TaskContractResolver.fromUserRequest(request).mutationAllowed());
    }

    @Test
    void freshDirectiveSuppliesItsOwnLegacyFallbackEvidence() {
        String request = "I want to edit README.md, then please update style.css.";
        var shape = MutationIntent.classifyShape(request);

        assertEquals(MutationIntent.Decision.EXECUTE_MUTATION, shape.decision(), shape.toString());
        assertEquals("update style.css", shape.evidenceSpan());
        assertTrue(TaskContractResolver.fromUserRequest(request).mutationAllowed());
    }

    @Test
    void independentSentenceSuppliesItsOwnLegacyFallbackEvidence() {
        String request = "I want to edit README.md. Update style.css.";
        var shape = MutationIntent.classifyShape(request);

        assertEquals(MutationIntent.Decision.EXECUTE_MUTATION, shape.decision(), shape.toString());
        assertEquals("Update style.css", shape.evidenceSpan());
        assertTrue(TaskContractResolver.fromUserRequest(request).mutationAllowed());
    }

    @Test
    void firstPersonContentSaveRetainsTypedIntentAndNormalTargetBinding() {
        String request = "I want to write and save a txt file named story.txt with a short story.";
        var save = ContentSaveRequest.parse(request);
        var resolved = TaskContractResolver.resolveFromUserRequest(request);

        assertTrue(save.mutationRequested());
        assertFalse(save.clarificationRequired());
        assertTrue(save.resolvedTargets().contains("story.txt"));
        assertTrue(resolved.intent().contentSaveRequest().mutationRequested());
        assertTrue(resolved.contract().mutationRequested());
        assertTrue(resolved.contract().mutationAllowed());
        assertTrue(resolved.contract().expectedTargets().contains("story.txt"));
    }
}
