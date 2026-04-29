package dev.talos.runtime.expectation;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExpectationResolverTest {

    @Test
    void extractsOverwriteWithExactlyLiteral() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Overwrite index.html with exactly AFTER. Use talos.write_file.");

        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        assertEquals(1, expectations.size());
        LiteralContentExpectation literal = (LiteralContentExpectation) expectations.getFirst();
        assertEquals("index.html", literal.targetPath());
        assertEquals("AFTER", literal.expectedContent());
        assertEquals(LiteralContentExpectation.MatchMode.EXACT, literal.matchMode());
        assertEquals("literal-overwrite-exactly", literal.sourcePattern());
    }

    @Test
    void extractsEntireFileShouldBeLiteral() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Use talos.write_file to overwrite index.html. The entire file should be AFTER.");

        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        assertEquals(1, expectations.size());
        LiteralContentExpectation literal = (LiteralContentExpectation) expectations.getFirst();
        assertEquals("index.html", literal.targetPath());
        assertEquals("AFTER", literal.expectedContent());
        assertEquals("literal-entire-file", literal.sourcePattern());
    }

    @Test
    void extractsExactContentArgumentLiteralWithFormattingNegation() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Use talos.write_file to overwrite index.html. "
                        + "Set the content argument to the exact five letters AFTER. "
                        + "Do not use angle brackets. Do not use placeholders. "
                        + "The entire file should be AFTER.");

        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        assertEquals(1, expectations.size());
        LiteralContentExpectation literal = (LiteralContentExpectation) expectations.getFirst();
        assertEquals("index.html", literal.targetPath());
        assertEquals("AFTER", literal.expectedContent());
        assertTrue(contract.mutationAllowed(), "T40 formatting-negation behavior must remain mutation-capable");
    }

    @Test
    void ignoresAmbiguousPageAboutLiteralText() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Make index.html into a simple webpage that says AFTER.");

        assertTrue(TaskExpectationResolver.resolve(contract).isEmpty());
    }

    @Test
    void ignoresPromptWithoutExplicitTargetFile() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Write exactly this content: AFTER");

        assertTrue(TaskExpectationResolver.resolve(contract).isEmpty());
    }

    @Test
    void ignoresMultipleTargetLiteralPromptForV1() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Overwrite index.html and README.md with exactly AFTER.");

        assertTrue(TaskExpectationResolver.resolve(contract).isEmpty());
    }
}
