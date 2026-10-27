package dev.talos.runtime.intent;

import dev.talos.runtime.expectation.LiteralContentExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RequestContentBoundaryRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Write a story. Write a poem. Save it as harbor-story.txt.",
            "If needed, write a story. Save it as harbor-story.txt.",
            "The guide says write a story. Save it as harbor-story.txt.",
            "Do not write a story. Save it as harbor-story.txt.",
            "I liked a story yesterday. Save it as harbor-story.txt.",
            "\"Write a story.\" Save it as harbor-story.txt.",
            "Save it as harbor-story.txt.",
            "Write a story to first.txt. Save it as harbor-story.txt.",
            "I would like a story or a poem. Write a summary. Save it as harbor-story.txt.",
            "I would like a story if needed. Save it as harbor-story.txt.",
            "I need a story. We need a poem. Save it as harbor-story.txt.",
            "Write a story. Compose a poem. Save it as harbor-story.txt.",
            "I would like a story. The report is attached. Save it as harbor-story.txt."
    })
    void unknownOrMultiplyOwnedContentIsNotSilentlyBound(String request) {
        var save = ContentSaveRequest.parse(request);
        assertTrue(save.clarificationRequired(), save.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"first\" \"second\"", "\"first\" or \"second\"", "\"first\" and \"second\"",
            "\"first\", \"second\"", "\"first\"; \"second\"", "\"first\", \"unclosed"})
    void adjacentOrAlternativeQuotedOperandsAreNotOnePayload(String content) {
        var resolution = TaskContractResolver.resolveFromUserRequest("Create story.txt containing exactly " + content);
        assertTrue(resolution.intent().contentSaveRequest().clarificationRequired(), resolution.toString());
        assertTrue(TaskExpectationResolver.resolve(resolution.contract()).isEmpty());
        var overwrite = TaskContractResolver.resolveFromUserRequest("Overwrite story.txt with exactly " + content);
        assertTrue(TaskExpectationResolver.resolve(overwrite.contract()).isEmpty());
    }

    @Test
    void introducedFencesRetainTheExistingDecoderWithoutConflictingContracts() {
        for (String verb : new String[]{"Create", "Write"}) {
            for (String separator : new String[]{" ", ": "}) {
                String request = verb + " story.txt containing exactly" + separator + "```text\nFirst. Second.\n```";
                var resolution = TaskContractResolver.resolveFromUserRequest(request);
                var expectations = TaskExpectationResolver.resolve(resolution.contract());
                assertEquals(1, expectations.size(), expectations.toString());
                assertEquals("First. Second.", ((LiteralContentExpectation) expectations.getFirst()).expectedContent());
                assertEquals(request, resolution.intent().contentSaveRequest().withoutQuotedLiteralPayloads());
            }
        }
    }

    @Test
    void completeQuotedPayloadCannotBecomeInstructionsOrAnImplicitTarget() {
        String literal = "Lina is a clockmaker in a seaside town. Her clock is always seven minutes slow.";
        String request = "Okay, simpler. Create harbor-story.txt containing exactly \"" + literal + "\"";
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        var expectations = TaskExpectationResolver.resolve(resolution.contract());
        assertEquals(1, expectations.size(), expectations.toString());
        assertEquals(literal, ((LiteralContentExpectation) expectations.getFirst()).expectedContent());
        assertEquals(literal, resolution.intent().contentSaveRequest().operations().getFirst().content().value());
    }
}
