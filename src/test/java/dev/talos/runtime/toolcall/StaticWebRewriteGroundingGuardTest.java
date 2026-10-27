package dev.talos.runtime.toolcall;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolResult;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebRewriteGroundingGuardTest {

    @Test
    void existingStaticWebRewriteRequiresSameTurnReadBeforeWrite(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        LoopState state = state(workspace);
        TaskContract contract = staticWebRedesignContract();
        ToolCall write = writeFile("style.css", "body { color: pink; }\n");

        String diagnostic = StaticWebRewriteGroundingGuard.diagnostic(write, state, contract, "style.css");

        assertNotNull(diagnostic);
        assertTrue(diagnostic.contains("read style.css before rewriting it"), diagnostic);
    }

    @Test
    void existingStaticWebRewriteClassifiedAsCreateStillRequiresSameTurnRead(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        LoopState state = state(workspace);
        TaskContract contract = new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("index.html", "style.css", "script.js"),
                Set.of(),
                Set.of(),
                "Rewrite the existing site to look better with Tailwind.",
                "test-static-web-create-redesign");

        String diagnostic = StaticWebRewriteGroundingGuard.diagnostic(
                writeFile("style.css", "body { color: pink; }\n"),
                state,
                contract,
                "style.css");

        assertNotNull(diagnostic);
        assertTrue(diagnostic.contains("read style.css before rewriting it"), diagnostic);
    }

    @Test
    void existingStaticWebRewritePassesAfterSameTurnRead(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        LoopState state = state(workspace);
        state.pathsReadThisTurn.add("style.css");

        assertNull(StaticWebRewriteGroundingGuard.diagnostic(
                writeFile("style.css", "body { color: pink; }\n"),
                state,
                staticWebRedesignContract(),
                "style.css"));
    }

    @Test
    void requiredStaticWebBlankWriteIsBlockedEvenAfterSameTurnRead(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        LoopState state = state(workspace);
        state.pathsReadThisTurn.add("style.css");

        String diagnostic = StaticWebRequiredAssetWriteGuard.diagnostic(
                writeFile("style.css", "   \n\t"),
                state,
                staticWebRedesignContract(),
                "style.css");

        assertNotNull(diagnostic);
        assertTrue(diagnostic.contains("blank required static-web asset"), diagnostic);
        assertTrue(diagnostic.contains("style.css"), diagnostic);
    }

    @Test
    void explicitStaticWebTruncationAllowsBlankWrite(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        LoopState state = state(workspace);
        state.pathsReadThisTurn.add("style.css");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("style.css"),
                Set.of(),
                Set.of(),
                "Clear style.css and leave it blank.",
                "test-static-web-explicit-clear");

        assertNull(StaticWebRequiredAssetWriteGuard.diagnostic(
                writeFile("style.css", ""),
                state,
                contract,
                "style.css"));
    }

    @Test
    void negativeBlankLanguageDoesNotAllowBlankRequiredAssetWrite(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        LoopState state = state(workspace);
        state.pathsReadThisTurn.add("style.css");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("style.css"),
                Set.of(),
                Set.of(),
                "Do not leave style.css blank.",
                "test-static-web-no-blank");

        assertNotNull(StaticWebRequiredAssetWriteGuard.diagnostic(
                writeFile("style.css", ""),
                state,
                contract,
                "style.css"));
    }

    @Test
    void clearUpStylingProblemsDoesNotAllowBlankRequiredAssetWrite(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        LoopState state = state(workspace);
        state.pathsReadThisTurn.add("style.css");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("style.css"),
                Set.of(),
                Set.of(),
                "Clear up the styling problems in style.css.",
                "test-static-web-clear-up");

        String diagnostic = StaticWebRequiredAssetWriteGuard.diagnostic(
                writeFile("style.css", ""),
                state,
                contract,
                "style.css");

        assertNotNull(diagnostic);
        assertTrue(diagnostic.contains("blank required static-web asset"), diagnostic);
    }

    @Test
    void emptyStatePageRequestDoesNotAllowBlankRequiredHtmlWrite(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<main>Existing page</main>\n");
        LoopState state = state(workspace);
        state.pathsReadThisTurn.add("index.html");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html"),
                Set.of(),
                Set.of(),
                "Create an empty-state page in index.html.",
                "test-static-web-empty-state");

        String diagnostic = StaticWebRequiredAssetWriteGuard.diagnostic(
                writeFile("index.html", ""),
                state,
                contract,
                "index.html");

        assertNotNull(diagnostic);
        assertTrue(diagnostic.contains("blank required static-web asset"), diagnostic);
    }

    @Test
    void nonRequiredStaticWebBlankWriteIsNotBlockedByRequiredAssetGuard(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("extra.css"), "body { color: white; }\n");
        LoopState state = state(workspace);
        state.pathsReadThisTurn.add("extra.css");

        assertNull(StaticWebRequiredAssetWriteGuard.diagnostic(
                writeFile("extra.css", ""),
                state,
                staticWebRedesignContract(),
                "extra.css"));
    }

    @Test
    void newStaticWebFileCreationDoesNotRequirePriorRead(@TempDir Path workspace) {
        assertNull(StaticWebRewriteGroundingGuard.diagnostic(
                writeFile("style.css", "body { color: pink; }\n"),
                state(workspace),
                staticWebRedesignContract(),
                "style.css"));
    }

    private static LoopState state(Path workspace) {
        return new LoopState(
                "",
                List.of(),
                List.of(ChatMessage.user("ok just edit the site to look better")),
                workspace,
                null,
                null,
                10,
                0);
    }

    private static TaskContract staticWebRedesignContract() {
        return new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html", "style.css", "script.js"),
                Set.of(),
                Set.of(),
                "ok just edit the site to look better",
                "test-static-web-redesign");
    }

    private static ToolCall writeFile(String path, String content) {
        return new ToolCall("talos.write_file", Map.of("path", path, "content", content));
    }

    @Test
    void absoluteSpellingOfAnExpectedStaticWebFileRequiresSameTurnReadLikeTheRelativeSpelling(
            @TempDir Path temporary) throws Exception {
        Path root = nestedSite(temporary);
        String absolute = root.resolve("site").resolve("style.css").toString();
        LoopState state = state(root);
        TaskContract contract = nestedRedesignContract();

        String relative = StaticWebRewriteGroundingGuard.diagnostic(
                writeFile("site/style.css", "body { color: pink; }\n"), state, contract, "site/style.css");
        String absoluteDiagnostic = StaticWebRewriteGroundingGuard.diagnostic(
                writeFile(absolute, "body { color: pink; }\n"), state, contract, absolute);

        assertNotNull(relative, "the relative spelling must be held, otherwise the fixture is wrong");
        assertNotNull(absoluteDiagnostic, "the absolute spelling of the same expected file must be held too");
        assertTrue(absoluteDiagnostic.contains("must be grounded before approval"), absoluteDiagnostic);
        assertTrue(absoluteDiagnostic.contains("site/style.css"), absoluteDiagnostic);
    }

    @Test
    void relativeReadGroundsAnAbsoluteSpelledRewriteOfTheSameFile(@TempDir Path temporary) throws Exception {
        Path root = nestedSite(temporary);
        String absolute = root.resolve("site").resolve("style.css").toString();
        LoopState state = state(root);
        recordSuccessfulRead(state, "site/style.css");

        assertNull(StaticWebRewriteGroundingGuard.diagnostic(
                writeFile(absolute, "body { color: pink; }\n"), state, nestedRedesignContract(), absolute));
    }

    @Test
    void absoluteReadGroundsARelativeSpelledRewriteOfTheSameFile(@TempDir Path temporary) throws Exception {
        Path root = nestedSite(temporary);
        String absolute = root.resolve("site").resolve("style.css").toString();
        LoopState state = state(root);
        recordSuccessfulRead(state, absolute);

        assertNull(StaticWebRewriteGroundingGuard.diagnostic(
                writeFile("site/style.css", "body { color: pink; }\n"),
                state, nestedRedesignContract(), "site/style.css"));
    }

    @Test
    void readOfASameNamedFileInAnotherFolderDoesNotGroundTheExpectedFile(@TempDir Path temporary)
            throws Exception {
        Path root = nestedSite(temporary);
        String absolute = root.resolve("site").resolve("style.css").toString();
        String siblingAbsolute = root.resolve("archive").resolve("style.css").toString();

        LoopState relativeWrite = state(root);
        recordSuccessfulRead(relativeWrite, siblingAbsolute);
        assertNotNull(StaticWebRewriteGroundingGuard.diagnostic(
                writeFile("site/style.css", "body { color: pink; }\n"),
                relativeWrite, nestedRedesignContract(), "site/style.css"));

        LoopState absoluteWrite = state(root);
        recordSuccessfulRead(absoluteWrite, "archive/style.css");
        assertNotNull(StaticWebRewriteGroundingGuard.diagnostic(
                writeFile(absolute, "body { color: pink; }\n"),
                absoluteWrite, nestedRedesignContract(), absolute));
    }

    @Test
    void failedReadUnderAnotherSpellingDoesNotGroundTheRewrite(@TempDir Path temporary) throws Exception {
        Path root = nestedSite(temporary);
        String absolute = root.resolve("site").resolve("style.css").toString();
        LoopState state = state(root);
        state.addToolOutcome(new ToolCallLoop.ToolOutcome(
                "talos.read_file", absolute, false, false, false, "", "read failed"));

        assertNotNull(StaticWebRewriteGroundingGuard.diagnostic(
                writeFile("site/style.css", "body { color: pink; }\n"),
                state, nestedRedesignContract(), "site/style.css"));
    }

    @Test
    void absoluteSpellingOfASameNamedFileInAnotherFolderIsNotAnExpectedTarget(@TempDir Path temporary)
            throws Exception {
        Path root = nestedSite(temporary);
        String sibling = root.resolve("archive").resolve("style.css").toString();

        assertNull(StaticWebRewriteGroundingGuard.diagnostic(
                writeFile(sibling, "body { color: pink; }\n"), state(root), nestedRedesignContract(), sibling));
    }

    private static Path nestedSite(Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.createDirectories(root.resolve("site"));
        Files.createDirectories(root.resolve("archive"));
        Files.writeString(root.resolve("site").resolve("style.css"), "body { color: white; }\n");
        Files.writeString(root.resolve("archive").resolve("style.css"), "body { color: gray; }\n");
        return root;
    }

    private static TaskContract nestedRedesignContract() {
        return new TaskContract(
                TaskType.FILE_EDIT, true, true, true,
                Set.of("site/index.html", "site/style.css", "site/script.js"),
                Set.of(), Set.of(),
                "ok just edit the site to look better",
                "test-static-web-nested-redesign");
    }

    /** Records a successful read as the loop does: normalized path set, ledger and raw-path outcome. */
    private static void recordSuccessfulRead(LoopState state, String rawPath) {
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", Map.of("path", rawPath)),
                rawPath,
                ToolResult.ok("1 | body { color: white; }\n"));
        state.addToolOutcome(new ToolCallLoop.ToolOutcome(
                "talos.read_file", rawPath, true, false, false, "Read " + rawPath, ""));
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aReadOfALowerCaseFileDoesNotGroundARewriteOfItsCaseVariantWhereCaseIsSignificant(@TempDir Path temporary)
            throws Exception {
        Path root = temporary.toRealPath();
        Files.createDirectories(root.resolve("site"));
        Files.writeString(root.resolve("site").resolve("style.css"), "body { color: gray; }\n");
        Files.writeString(root.resolve("site").resolve("Style.css"), "body { color: white; }\n");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                !Files.readString(root.resolve("site").resolve("style.css"))
                        .equals(Files.readString(root.resolve("site").resolve("Style.css"))),
                "this file system ignores case");
        LoopState state = state(root);
        recordSuccessfulRead(state, "site/style.css");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT, true, true, true,
                Set.of("site/index.html", "site/Style.css", "site/script.js"),
                Set.of(), Set.of(),
                "ok just edit the site to look better",
                "test-static-web-case-variant");

        assertNotNull(StaticWebRewriteGroundingGuard.diagnostic(
                writeFile("site/Style.css", "body { color: pink; }\n"), state, contract, "site/Style.css"));
    }
}
