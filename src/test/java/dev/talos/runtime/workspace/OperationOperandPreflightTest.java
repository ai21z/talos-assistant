package dev.talos.runtime.workspace;

import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.intent.TargetRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A removal keeps its operation and binds a real operand, or asks (MICA invariant I2, decision D3, P3). */
class OperationOperandPreflightTest {
    private static final int TURN = 2;

    @Test
    void aKindNounBindsTheOnlyPageOfTheWorkspace(@TempDir Path workspace) throws Exception {
        site(workspace);

        var result = evaluate("please remove the page", workspace, ActiveTaskContext.none());

        assertBound(result, OperationOperandPreflight.OperandClass.KIND_NOUN, "index.html");
    }

    @Test
    void theLiveDemonstrativePageRequestBindsThePageOfTheCarriedTargets(@TempDir Path workspace)
            throws Exception {
        site(workspace);
        Files.writeString(workspace.resolve("about.html"), "<p>About</p>");
        var context = ActiveTaskContext.verifiedMutation(
                1, "trc", List.of("index.html", "style.css", "script.js"), "VERIFIED");

        var result = evaluate(
                "ok can you delete this page now please? I want this workspace clean", workspace, context);

        assertBound(result, OperationOperandPreflight.OperandClass.KIND_NOUN, "index.html");
    }

    @Test
    void twoPagesAskAndNameBoth(@TempDir Path workspace) throws Exception {
        site(workspace);
        Files.writeString(workspace.resolve("about.html"), "<p>About</p>");

        var result = evaluate("please remove the page", workspace, ActiveTaskContext.none());

        assertAnswer(result, OperationOperandPreflight.Outcome.CLARIFIED);
        assertTrue(result.directAnswer().contains("about.html"), result.directAnswer());
        assertTrue(result.directAnswer().contains("index.html"), result.directAnswer());
        assertEquals(2, result.candidateCount());
    }

    @Test
    void anAnaphorAsksEvenWithOneCarriedTargetBecauseItMayMeanTheContent(@TempDir Path workspace)
            throws Exception {
        site(workspace);
        var context = ActiveTaskContext.verifiedMutation(1, "trc", List.of("index.html"), "VERIFIED");

        for (String request : List.of("delete it", "actually, remove it", "yes, remove it")) {
            var result = evaluate(request, workspace, context);

            assertAnswer(result, OperationOperandPreflight.Outcome.CLARIFIED);
            assertEquals(OperationOperandPreflight.OperandClass.ANAPHOR, result.operandClass(), request);
            assertTrue(result.directAnswer().contains("the file index.html or content in it"), result.directAnswer());
            assertTrue(result.directAnswer().contains("send: delete index.html"), result.directAnswer());
        }
    }

    @Test
    void aKindNounBindsTheSingleCarriedFile(@TempDir Path workspace) throws Exception {
        site(workspace);
        var context = ActiveTaskContext.verifiedMutation(1, "trc", List.of("style.css"), "VERIFIED");

        assertBound(evaluate("delete this file", workspace, context),
                OperationOperandPreflight.OperandClass.KIND_NOUN, "style.css");
    }

    @Test
    void anAnaphorWithSeveralCarriedTargetsAsks(@TempDir Path workspace) throws Exception {
        site(workspace);
        var context = ActiveTaskContext.verifiedMutation(
                1, "trc", List.of("index.html", "style.css"), "VERIFIED");

        var result = evaluate("delete it", workspace, context);

        assertAnswer(result, OperationOperandPreflight.Outcome.CLARIFIED);
        assertTrue(result.directAnswer().contains("index.html or style.css"), result.directAnswer());
    }

    @Test
    void anAnaphorWithoutAReferentAsks(@TempDir Path workspace) throws Exception {
        site(workspace);

        var result = evaluate("delete it", workspace, ActiveTaskContext.none());

        assertAnswer(result, OperationOperandPreflight.Outcome.CLARIFIED);
        assertTrue(result.directAnswer().startsWith("Talos did not delete anything."), result.directAnswer());
    }

    @Test
    void anExpiredContextSuppliesNoReferent(@TempDir Path workspace) throws Exception {
        site(workspace);
        var context = ActiveTaskContext.verifiedMutation(1, "trc", List.of("index.html"), "VERIFIED");

        var result = OperationOperandPreflight.evaluate("delete it", workspace, context, 9);
        var kind = OperationOperandPreflight.evaluate("delete this file", workspace, context, 9);

        assertAnswer(result, OperationOperandPreflight.Outcome.CLARIFIED);
        assertFalse(result.directAnswer().contains("could mean"), result.directAnswer());
        assertAnswer(kind, OperationOperandPreflight.Outcome.CLARIFIED);
        assertEquals(3, kind.candidateCount());
    }

    @Test
    void anAnaphorNeverFallsBackToTheOnlyWorkspaceFile(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "readme");
        Files.createDirectories(workspace.resolve("docs"));
        var directoryContext = ActiveTaskContext.verifiedMutation(1, "trc", List.of("docs"), "VERIFIED");

        for (ActiveTaskContext context : List.of(ActiveTaskContext.none(), directoryContext)) {
            var result = evaluate("delete it", workspace, context);

            assertAnswer(result, OperationOperandPreflight.Outcome.CLARIFIED);
            assertFalse(result.directAnswer().contains("could mean"), result.directAnswer());
        }
    }

    @Test
    void bulkRequestsAreNeverBound(@TempDir Path workspace) throws Exception {
        site(workspace);

        for (String request : List.of(
                "delete everything",
                "Can you completely DELETE all the files in this workspace?",
                "remove the whole workspace",
                "delete the site")) {
            var result = evaluate(request, workspace, ActiveTaskContext.none());

            assertAnswer(result, OperationOperandPreflight.Outcome.BULK_REFUSED);
            assertTrue(result.directAnswer().contains("one named path per request"), request);
            assertTrue(result.directAnswer().contains("index.html"), request);
        }
    }

    @Test
    void eraseGetRidOfAndRmUseTheDeleteOperation(@TempDir Path workspace) throws Exception {
        site(workspace);
        Files.writeString(workspace.resolve("notes.txt"), "notes");

        assertBound(evaluate("erase index.html", workspace, ActiveTaskContext.none()),
                OperationOperandPreflight.OperandClass.EXPLICIT_PATH, "index.html");
        assertBound(evaluate("get rid of index.html", workspace, ActiveTaskContext.none()),
                OperationOperandPreflight.OperandClass.EXPLICIT_PATH, "index.html");
        assertBound(evaluate("rm notes.txt", workspace, ActiveTaskContext.none()),
                OperationOperandPreflight.OperandClass.EXPLICIT_PATH, "notes.txt");
    }

    @Test
    void contentRemovalFromANamedFileBecomesAnEditOfThatFile(@TempDir Path workspace) throws Exception {
        site(workspace);

        var result = evaluate("remove the footer from index.html", workspace, ActiveTaskContext.none());

        assertEquals(OperationOperandPreflight.Outcome.CONTENT_EDIT, result.outcome());
        assertEquals("Edit \"index.html\" to remove the footer", result.effectiveRequest());
        assertNull(result.directAnswer());
    }

    @Test
    void contentRemovalKeepsTheRestOfTheMessage(@TempDir Path workspace) throws Exception {
        site(workspace);

        var result = evaluate(
                "Please remove the footer from index.html, but keep the copyright line.",
                workspace,
                ActiveTaskContext.none());

        assertEquals(OperationOperandPreflight.Outcome.CONTENT_EDIT, result.outcome());
        assertEquals(
                "Please edit \"index.html\" to remove the footer, but keep the copyright line.",
                result.effectiveRequest());
    }

    @Test
    void requestsTheParserAlreadyResolvesToExistingPathsPassUnchanged(@TempDir Path workspace)
            throws Exception {
        site(workspace);
        Files.createDirectories(workspace.resolve("assets"));
        Files.createDirectories(workspace.resolve("archive/source-dir"));
        Files.writeString(workspace.resolve("a.txt"), "a");

        for (String request : List.of(
                "delete index.html",
                "Delete archive/source-dir recursively.",
                "delete assets",
                "Create a directory named docs.",
                "rename a.txt to b.txt",
                "why did you delete it?",
                "how do I delete a file?",
                "don't delete it",
                "fix the header and delete it")) {
            var result = evaluate(request, workspace, ActiveTaskContext.none());

            assertFalse(result.applies(), request + " -> " + result);
        }
    }

    @Test
    void aMissingExplicitPathIsStatedAsNotFound(@TempDir Path workspace) throws Exception {
        site(workspace);

        var result = evaluate("delete missing.txt", workspace, ActiveTaskContext.none());

        assertAnswer(result, OperationOperandPreflight.Outcome.NOT_FOUND);
        assertTrue(result.directAnswer().contains("missing.txt does not exist"), result.directAnswer());
    }

    @Test
    void aBareWordIsNeverInventedAsAnOperand(@TempDir Path workspace) throws Exception {
        site(workspace);

        var result = evaluate("remove the footer", workspace, ActiveTaskContext.none());

        assertAnswer(result, OperationOperandPreflight.Outcome.NOT_FOUND);
        assertTrue(result.directAnswer().contains("remove the footer from index.html"), result.directAnswer());
    }

    @Test
    void aConditionOutsideTheRemovalAsksWithTheBoundPath(@TempDir Path workspace) throws Exception {
        site(workspace);

        for (String request : List.of("delete the page but keep a backup", "delete index.html but keep a backup")) {
            var result = evaluate(request, workspace, ActiveTaskContext.none());

            assertAnswer(result, OperationOperandPreflight.Outcome.CONSTRAINED);
            assertTrue(result.directAnswer().contains("send: delete index.html"), result.directAnswer());
        }
    }

    @Test
    void theClauseIsReadAtItsOwnPlaceAndARepeatedClauseIsLeftToTheParser(@TempDir Path workspace)
            throws Exception {
        site(workspace);

        var spelled = evaluate("Index.HTML looks odd. erase index.html", workspace, ActiveTaskContext.none());
        var repeated = evaluate(
                "I did not remove the footer from index.html. Now remove the footer from index.html.",
                workspace,
                ActiveTaskContext.none());

        assertBound(spelled, OperationOperandPreflight.OperandClass.EXPLICIT_PATH, "index.html");
        assertFalse(repeated.applies(), String.valueOf(repeated));
    }

    @Test
    void theLocationDecidesContentRemovalWhateverTheObjectLooksLike(@TempDir Path workspace) throws Exception {
        site(workspace);

        assertEquals("Edit \"script.js\" to remove console.log",
                evaluate("remove console.log from script.js", workspace, ActiveTaskContext.none())
                        .effectiveRequest());
        assertEquals("Edit \"index.html\" to remove it",
                evaluate("remove it from index.html", workspace, ActiveTaskContext.none()).effectiveRequest());
        assertEquals("Edit \"index.html\" to remove all the content",
                evaluate("remove all the content from index.html", workspace, ActiveTaskContext.none())
                        .effectiveRequest());
        assertEquals("Edit \"index.html\" to remove 'TODO'",
                evaluate("remove 'TODO' from the page", workspace, ActiveTaskContext.none()).effectiveRequest());
    }

    @Test
    void anUnresolvedContentLocationAsksAboutTheFileNotAboutADelete(@TempDir Path workspace) throws Exception {
        site(workspace);
        Files.writeString(workspace.resolve("about.html"), "<p>About</p>");

        var ambiguous = evaluate("remove the footer from the page", workspace, ActiveTaskContext.none());
        var missing = evaluate("remove the footer from missing.html", workspace, ActiveTaskContext.none());

        assertAnswer(ambiguous, OperationOperandPreflight.Outcome.CLARIFIED);
        assertEquals(OperationOperandPreflight.OperandClass.CONTENT_REMOVAL, ambiguous.operandClass());
        assertTrue(ambiguous.directAnswer().startsWith("Talos changed nothing."), ambiguous.directAnswer());
        assertTrue(ambiguous.directAnswer().contains("remove the footer from about.html"), ambiguous.directAnswer());
        assertAnswer(missing, OperationOperandPreflight.Outcome.NOT_FOUND);
        assertTrue(missing.directAnswer().contains("missing.html does not exist"), missing.directAnswer());
    }

    @Test
    void mentionsCommandsListsAndSeveralLocationsAreLeftToTheParser(@TempDir Path workspace) throws Exception {
        site(workspace);
        Files.writeString(workspace.resolve("about.html"), "<p>About</p>");
        Files.writeString(workspace.resolve("a.txt"), "a");
        Files.createDirectories(workspace.resolve("docs"));

        for (String request : List.of(
                "rm notes.txt gives permission denied. Why?",
                "Delete key does not work in the editor",
                "Remove from cart button misaligned in index.html",
                "Remove button is misaligned in index.html",
                "remove the footer from index.html and the header from about.html",
                "delete a.txt, b.txt",
                "erase a.txt, b.txt",
                "delete a.txt and delete b.txt",
                "remove notes.txt from docs",
                "remove a.txt from docs/",
                "remove nothing, just review the page")) {
            var result = evaluate(request, workspace, ActiveTaskContext.none());

            assertFalse(result.applies(), request + " -> " + result);
        }
    }

    @Test
    void dottedAndWrappedNamesAreExplicitPaths(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve(".env"), "A=1");
        Files.writeString(workspace.resolve("notes.txt"), "notes");

        assertBound(evaluate("erase .env", workspace, ActiveTaskContext.none()),
                OperationOperandPreflight.OperandClass.EXPLICIT_PATH, ".env");
        assertBound(evaluate("erase the file notes.txt", workspace, ActiveTaskContext.none()),
                OperationOperandPreflight.OperandClass.EXPLICIT_PATH, "notes.txt");
        assertBound(evaluate("get rid of the notes.txt file", workspace, ActiveTaskContext.none()),
                OperationOperandPreflight.OperandClass.EXPLICIT_PATH, "notes.txt");
    }

    @Test
    void indexIsNotAPageNoun(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("about.html"), "<p>About</p>");

        var result = evaluate("remove index", workspace, ActiveTaskContext.none());

        assertFalse(OperationOperandPreflight.Outcome.BOUND == result.outcome(), String.valueOf(result));
    }

    @Test
    void aRecursiveRemovalOfABoundDirectoryStaysRecursive(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("Build/out"));
        Files.writeString(workspace.resolve("Build/out/a.txt"), "a");

        var result = evaluate("please get rid of build recursively", workspace, ActiveTaskContext.none());

        assertBound(result, OperationOperandPreflight.OperandClass.BARE_WORD, "Build");
        assertEquals("Delete \"Build\" recursively.", result.effectiveRequest());
    }

    /**
     * The durable store reproduces every request this preflight composes from the typed text and the evidence path
     * alone, so the turn is persisted (P3c). The evidence path is the normalized path the parser records.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "please remove the page",
            "ok can you delete this page now please? I want this workspace clean",
            "erase index.html",
            "erase ./index.html",
            "erase \"index.html\"",
            "erase the file index.html",
            "erase index.html from the workspace",
            "erase docs/a.txt",
            "erase the stylesheet",
            "erase build",
            "please get rid of build recursively",
            "remove the footer from index.html",
            "remove the footer from ./index.html",
            "remove the footer from the page"})
    void everyCompositionIsReproducedFromTheTypedRequestAndItsEvidencePath(String request, @TempDir Path workspace)
            throws Exception {
        site(workspace);
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/a.txt"), "a");
        Files.createDirectories(workspace.resolve("Build/out"));
        Files.writeString(workspace.resolve("Build/out/a.txt"), "a");

        var result = evaluate(request, workspace, ActiveTaskContext.none());

        assertTrue(result.effectiveRequest() != null, String.valueOf(result));
        assertTrue(OperationOperandPreflight.compositions(request, TargetRef.normalizePath(result.boundPath()))
                .contains(result.effectiveRequest()), String.valueOf(result));
    }

    private static OperationOperandPreflight.Result evaluate(
            String request, Path workspace, ActiveTaskContext context) {
        return OperationOperandPreflight.evaluate(request, workspace, context, TURN);
    }

    private static void site(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<footer>Footer</footer>");
        Files.writeString(workspace.resolve("style.css"), "body {}");
        Files.writeString(workspace.resolve("script.js"), "void 0;");
    }

    private static void assertBound(
            OperationOperandPreflight.Result result,
            OperationOperandPreflight.OperandClass operandClass,
            String path) {
        assertEquals(OperationOperandPreflight.Outcome.BOUND, result.outcome(), String.valueOf(result));
        assertEquals(operandClass, result.operandClass(), String.valueOf(result));
        assertEquals(path, result.boundPath());
        assertTrue(result.effectiveRequest().startsWith("Delete \"" + path + "\""), result.effectiveRequest());
        assertNull(result.directAnswer());
    }

    private static void assertAnswer(OperationOperandPreflight.Result result, OperationOperandPreflight.Outcome outcome) {
        assertEquals(outcome, result.outcome(), String.valueOf(result));
        assertNull(result.effectiveRequest(), String.valueOf(result));
        assertTrue(result.directAnswer() != null && !result.directAnswer().isBlank(), String.valueOf(result));
    }
}
