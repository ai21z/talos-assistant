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
}
