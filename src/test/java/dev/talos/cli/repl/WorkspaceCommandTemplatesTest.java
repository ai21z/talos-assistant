package dev.talos.cli.repl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T806: the workspace template catalog. Templates are workspace content
 * — untrusted — so the catalog only stores text; capability comes from
 * the prompt pipeline the router routes expansions through. Built-ins
 * always win: collisions are dropped at load.
 */
class WorkspaceCommandTemplatesTest {

    @TempDir Path workspace;

    private Path commandsDir() throws Exception {
        Path dir = workspace.resolve(".talos").resolve("commands");
        Files.createDirectories(dir);
        return dir;
    }

    private void template(String fileName, String body) throws Exception {
        Files.writeString(commandsDir().resolve(fileName), body);
    }

    @Test
    void loadsTemplatesAndSubstitutesArgs() throws Exception {
        template("review.md", "Review the current diff.\n\nFocus: $ARGS\n");

        var templates = WorkspaceCommandTemplates.load(workspace, Set.of());

        assertTrue(templates.has("review"));
        assertEquals(List.of("review"), templates.names());
        assertEquals("Review the current diff.\n\nFocus: error handling",
                templates.expand("review", "error handling"));
        assertEquals("Review the current diff.\n\nFocus: ",
                templates.expand("review", ""), "absent args leave the placeholder empty");
    }

    @Test
    void argsAppendAsAParagraphWhenThePlaceholderIsAbsent() throws Exception {
        template("plan.md", "Write an implementation plan.");

        var templates = WorkspaceCommandTemplates.load(workspace, Set.of());

        assertEquals("Write an implementation plan.\n\nfor the auth module",
                templates.expand("plan", "for the auth module"));
        assertEquals("Write an implementation plan.", templates.expand("plan", "  "));
    }

    @Test
    void builtinCollisionsAreDroppedAtLoad() throws Exception {
        template("help.md", "This must never shadow /help.");
        template("review.md", "Fine.");

        var templates = WorkspaceCommandTemplates.load(workspace, Set.of("help", "h"));

        assertFalse(templates.has("help"), "built-ins always win");
        assertTrue(templates.has("review"));
    }

    @Test
    void invalidNamesAreSkipped() throws Exception {
        template("Bad_Name.md", "x");
        template("UPPER.md", "x");
        template("-lead.md", "x");
        template("ok-name2.md", "x");

        var templates = WorkspaceCommandTemplates.load(workspace, Set.of());

        assertEquals(List.of("ok-name2"), templates.names());
    }

    @Test
    void oversizeAndEmptyTemplatesAreSkipped() throws Exception {
        template("big.md", "y".repeat((int) WorkspaceCommandTemplates.MAX_FILE_BYTES + 1));
        template("empty.md", "   \n  ");
        template("fits.md", "z");

        var templates = WorkspaceCommandTemplates.load(workspace, Set.of());

        assertEquals(List.of("fits"), templates.names());
    }

    @Test
    void catalogIsCappedAtTwentyFourTemplates() throws Exception {
        for (int i = 10; i < 36; i++) { // 26 candidates, stable sort order
            template("t" + i + ".md", "body " + i);
        }

        var templates = WorkspaceCommandTemplates.load(workspace, Set.of());

        assertEquals(WorkspaceCommandTemplates.MAX_TEMPLATES, templates.names().size());
        assertTrue(templates.has("t10"));
        assertFalse(templates.has("t35"), "files beyond the cap are dropped, not silently merged");
    }

    @Test
    void missingDirectoryMeansNoTemplates() {
        var templates = WorkspaceCommandTemplates.load(workspace, Set.of());

        assertTrue(templates.isEmpty());
        assertNull(templates.expand("anything", "args"));
    }
}
