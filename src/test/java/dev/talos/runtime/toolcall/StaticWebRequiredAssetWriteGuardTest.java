package dev.talos.runtime.toolcall;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A blank write to a required static-web target is judged by file identity, whichever spelling names it. */
class StaticWebRequiredAssetWriteGuardTest {
    @TempDir Path temporary;
    Path root;

    @BeforeEach void useTheResolvedWorkspace() throws Exception {
        root = temporary.toRealPath();
        Files.createDirectories(root.resolve("site"));
        Files.createDirectories(root.resolve("archive"));
        Files.writeString(root.resolve("site").resolve("style.css"), "body { color: white; }\n");
        Files.writeString(root.resolve("archive").resolve("style.css"), "body { color: gray; }\n");
    }

    @Test
    void blankWriteUnderTheAbsoluteSpellingOfARequiredTargetIsRejectedLikeTheRelativeSpelling() {
        LoopState state = state(root);
        String absolute = root.resolve("site").resolve("style.css").toString();

        String relative = StaticWebRequiredAssetWriteGuard.diagnostic(
                writeFile("site/style.css", ""), state, redesignContract(), "site/style.css");
        String absoluteDiagnostic = StaticWebRequiredAssetWriteGuard.diagnostic(
                writeFile(absolute, "   \n\t"), state, redesignContract(), absolute);

        assertNotNull(relative, "the relative spelling must be rejected, otherwise the fixture is wrong");
        assertNotNull(absoluteDiagnostic, "the absolute spelling of the same required target must be rejected");
        assertTrue(absoluteDiagnostic.contains("blank required static-web asset"), absoluteDiagnostic);
    }

    @Test
    void blankWriteToASameNamedFileInAnotherFolderOrOutsideTheWorkspaceIsNotARequiredTarget() {
        LoopState state = state(root);
        String sibling = root.resolve("archive").resolve("style.css").toString();
        String outside = root.resolveSibling(root.getFileName() + "-outside")
                .resolve("site").resolve("style.css").toString();

        for (String path : List.of("archive/style.css", sibling, outside)) {
            assertNull(StaticWebRequiredAssetWriteGuard.diagnostic(
                    writeFile(path, ""), state, redesignContract(), path), path);
        }
    }

    @Test
    void explicitClearOfTheTargetAllowsABlankWriteUnderEitherSpelling() {
        LoopState state = state(root);
        String absolute = root.resolve("site").resolve("style.css").toString();
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT, true, true, true,
                Set.of("site/style.css"), Set.of(), Set.of(),
                "Clear site/style.css.",
                "test-static-web-explicit-clear-nested");

        assertNull(StaticWebRequiredAssetWriteGuard.diagnostic(
                writeFile("site/style.css", ""), state, contract, "site/style.css"));
        assertNull(StaticWebRequiredAssetWriteGuard.diagnostic(
                writeFile(absolute, ""), state, contract, absolute),
                "an explicit clear of the target must not depend on the spelling of the write");
    }

    private static LoopState state(Path workspace) {
        return new LoopState("", List.of(),
                List.of(ChatMessage.user("ok just edit the site to look better")),
                workspace, null, null, 10, 0);
    }

    private static TaskContract redesignContract() {
        return new TaskContract(
                TaskType.FILE_EDIT, true, true, true,
                Set.of("site/index.html", "site/style.css", "site/script.js"),
                Set.of(), Set.of(),
                "ok just edit the site to look better",
                "test-static-web-nested-redesign");
    }

    private static ToolCall writeFile(String path, String content) {
        return new ToolCall("talos.write_file", Map.of("path", path, "content", content));
    }
}
