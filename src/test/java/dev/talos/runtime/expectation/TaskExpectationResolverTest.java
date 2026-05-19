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
    void extractsCompleteFileTwoLineExactLiteralForTextTargets() {
        for (String target : List.of(
                "README.md",
                "notes.txt",
                "index.html",
                "styles.css",
                "script.js",
                "README")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(
                    "Edit " + target + " now using talos.write_file. "
                            + "The complete file must contain exactly two lines: "
                            + "first line T71 exact literal; second line Line two; no other characters.");

            List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

            assertEquals(1, expectations.size(), target);
            LiteralContentExpectation literal = (LiteralContentExpectation) expectations.getFirst();
            assertEquals(target, literal.targetPath(), target);
            assertEquals("T71 exact literal\nLine two", literal.expectedContent(), target);
            assertEquals(LiteralContentExpectation.MatchMode.EXACT, literal.matchMode(), target);
            assertEquals("literal-complete-file-two-lines", literal.sourcePattern(), target);
            assertTrue(contract.mutationAllowed(), target);
        }
    }

    @Test
    void extractsCreateTargetContainingExactlyLiteral() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create a directory named workspace-notes and create workspace-notes/summary.txt "
                        + "containing exactly created by audit.");

        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        assertEquals(1, expectations.size());
        LiteralContentExpectation literal = (LiteralContentExpectation) expectations.getFirst();
        assertEquals("workspace-notes/summary.txt", literal.targetPath());
        assertEquals("created by audit", literal.expectedContent());
        assertEquals(LiteralContentExpectation.MatchMode.EXACT, literal.matchMode());
        assertEquals("literal-create-containing-exactly", literal.sourcePattern());
        assertTrue(contract.mutationAllowed());
    }

    @Test
    void extractsExactBulletCountForSingleTarget() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create notes/generated-summary.md with exactly three bullet points.");

        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        assertEquals(1, expectations.size());
        BulletListExpectation bullets = (BulletListExpectation) expectations.getFirst();
        assertEquals("notes/generated-summary.md", bullets.targetPath());
        assertEquals(3, bullets.expectedBulletCount());
        assertEquals("bullet-list-exact-count", bullets.sourcePattern());
        assertTrue(contract.mutationAllowed());
    }

    @Test
    void extractsAppendLineExpectationForSingleTarget() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Append exactly this line to README.md: Release gate note");

        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        assertEquals(1, expectations.size());
        TaskExpectation expectation = expectations.getFirst();
        assertEquals("APPEND_LINE", expectation.kind());
        assertEquals("README.md", expectation.targetPath());
        assertEquals("append-line-exact", expectation.sourcePattern());
        assertTrue(contract.mutationAllowed());
    }

    @Test
    void extractsReplacementExpectationForSingleTarget() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Replace .missing-button with #submit in script.js.");

        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        assertEquals(1, expectations.size());
        ReplacementExpectation replacement = (ReplacementExpectation) expectations.getFirst();
        assertEquals("script.js", replacement.targetPath());
        assertEquals(".missing-button", replacement.oldText());
        assertEquals("#submit", replacement.newText());
        assertEquals("replacement-replace-with-in-target", replacement.sourcePattern());
        assertTrue(contract.mutationAllowed());
    }

    @Test
    void extractsReplacementExpectationAfterApprovalSimilarTargetWording() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "After approval, edit only script.js, not scripts.js. "
                        + "Replace .missing-button with #submit in script.js.");

        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        assertEquals(1, expectations.size());
        ReplacementExpectation replacement = (ReplacementExpectation) expectations.getFirst();
        assertEquals("script.js", replacement.targetPath());
        assertEquals(".missing-button", replacement.oldText());
        assertEquals("#submit", replacement.newText());
        assertEquals("replacement-replace-with-in-target", replacement.sourcePattern());
        assertTrue(contract.mutationAllowed());
    }

    @Test
    void extractsChangeFromToReplacementExpectationForSingleTarget() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Change the page title from Old Portal to New Portal in index.html.");

        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        assertEquals(1, expectations.size());
        ReplacementExpectation replacement = (ReplacementExpectation) expectations.getFirst();
        assertEquals("index.html", replacement.targetPath());
        assertEquals("Old Portal", replacement.oldText());
        assertEquals("New Portal", replacement.newText());
        assertEquals("replacement-change-from-to-in-target", replacement.sourcePattern());
        assertTrue(contract.mutationAllowed());
    }

    @Test
    void extractsChangingLiteralToLiteralReplacementExpectationForExpectedTarget() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Read script.js, then fix the selector bug by changing .missing-button to .cta-button. "
                        + "Do not edit scripts.js.");

        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        assertEquals(1, expectations.size());
        ReplacementExpectation replacement = (ReplacementExpectation) expectations.getFirst();
        assertEquals("script.js", replacement.targetPath());
        assertEquals(".missing-button", replacement.oldText());
        assertEquals(".cta-button", replacement.newText());
        assertTrue(replacement.preserveRest());
        assertEquals("replacement-changing-to-expected-target", replacement.sourcePattern());
        assertTrue(contract.mutationAllowed());
    }

    @Test
    void extractsPreserveRestReplacementExpectationForSingleTarget() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Change the page title from Old Portal to New Portal in index.html and preserve the rest.");

        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        assertEquals(1, expectations.size());
        ReplacementExpectation replacement = (ReplacementExpectation) expectations.getFirst();
        assertEquals("index.html", replacement.targetPath());
        assertEquals("Old Portal", replacement.oldText());
        assertEquals("New Portal", replacement.newText());
        assertTrue(replacement.preserveRest());
        assertEquals("replacement-change-from-to-in-target", replacement.sourcePattern());
        assertTrue(contract.mutationAllowed());
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
