package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ContentSaveSourceBindingTest {
    @ParameterizedTest
    @ValueSource(strings = {"Write exactly this content: x", "A list with exactly two bullet points."})
    void quotedSavePayloadDoesNotBecomeAnEmbeddedFormattingInstruction(String payload) {
        String request = "Save \"" + payload + "\".";
        var pending = dev.talos.runtime.context.ContentSaveContinuation.evaluate(
                TaskContractResolver.resolveFromUserRequest(request), null, java.nio.file.Path.of("."), 1,
                dev.talos.runtime.policy.CapabilityPosture.AGENT).nextPending();
        assertNotNull(pending);
        var bound = dev.talos.runtime.context.ContentSaveContinuation.evaluate(
                TaskContractResolver.resolveFromUserRequest("story.txt"), pending, java.nio.file.Path.of("."), 2,
                dev.talos.runtime.policy.CapabilityPosture.AGENT);
        var expectations = dev.talos.runtime.expectation.TaskExpectationResolver.resolve(bound.resolution().contract());
        assertEquals(java.util.List.of(new dev.talos.runtime.expectation.LiteralContentExpectation("story.txt", payload,
                dev.talos.runtime.expectation.LiteralContentExpectation.MatchMode.EXACT, "source-owned-save-literal")), expectations);
    }

    @Test void interpretationCannotBeAttachedToAnotherRequest() {
        var save = ContentSaveRequest.parse("Save a short story.");
        assertThrows(IllegalArgumentException.class, () -> new TaskIntent(TaskType.FILE_CREATE,
                true, false, true, ArtifactTargetSet.empty(), "Do not write anything.", "fixture", save));
    }

    @Test void clarificationRetainsSeparateOriginAndReplyCoordinateBases() {
        String original = "Save a short story.";
        var save = ContentSaveRequest.parse(original).bindDestination(new ContentSaveRequest.ClarificationBinding(
                0, "story.txt", new ContentSaveRequest.SourceSpan(0, 9), "story.txt", 2, 3));
        var intent = new TaskIntent(TaskType.FILE_CREATE, true, true, true,
                ArtifactTargetSet.empty(), original, "fixture", save);
        assertEquals(original, intent.contentSaveRequest().originalRequest());
        assertEquals(original, TaskContractCompiler.compile(intent).originalUserRequest());
        assertThrows(IllegalArgumentException.class, () -> new TaskIntent(TaskType.FILE_CREATE, true, true, true,
                ArtifactTargetSet.empty(), "story.txt", "fixture", save));
        assertEquals("story.txt", intent.contentSaveRequest().clarificationBinding().replySpan().text("story.txt"));
        assertThrows(IllegalArgumentException.class, () -> new TaskIntent(TaskType.FILE_CREATE, true, true, true,
                ArtifactTargetSet.empty(), "different.txt", "fixture", save));
        assertThrows(IllegalArgumentException.class, () -> new ContentSaveRequest.ClarificationBinding(
                0, "story.txt", new ContentSaveRequest.SourceSpan(0, 9), "different.txt", 2, 3));
    }

    @Test void sameRequestProjectionPreservesOperationButNewRequestCannotInheritIt() {
        var resolved = TaskContractResolver.resolveFromUserRequest("Save a short story as story.txt.");
        var same = TaskIntentProjector.project(resolved.intent(), resolved.contract(),
                TargetSource.RUNTIME_DEFAULT, "fixture");
        assertSame(resolved.intent().contentSaveRequest(), same.contentSaveRequest());
        var different = TaskIntentProjector.project(resolved.intent(), TaskContract.unknown("hello"),
                TargetSource.RUNTIME_DEFAULT, "fixture");
        assertFalse(different.contentSaveRequest().present());
        assertEquals("hello", different.contentSaveRequest().originalRequest());
    }
}
