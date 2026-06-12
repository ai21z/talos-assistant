package dev.talos.cli.repl;

import dev.talos.core.security.Sandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T802: the @-file pin resolver. Explicit paths only, visible notices
 * for everything skipped, and — the trust proof of this ticket — a
 * protected path can never enter pinned content, whether or not it
 * exists.
 */
class AtFilePinsTest {

    @TempDir Path workspace;

    private Sandbox sandbox() {
        return new Sandbox(workspace, Map.of());
    }

    private Path write(String relative, String content) throws Exception {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent() == null ? workspace : file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void bareTokenPinsAWorkspaceRelativeFile() throws Exception {
        write("src/Main.java", "public class Main {}");

        var resolution = AtFilePins.resolve(
                "explain @src/Main.java please", workspace, sandbox());

        assertEquals(1, resolution.pins().size());
        var pin = resolution.pins().get(0);
        assertEquals("src/Main.java", pin.path());
        assertEquals("public class Main {}", pin.content());
        assertFalse(pin.truncated());
        assertTrue(resolution.notices().isEmpty());
        assertEquals("public class Main {}".length(), resolution.pinnedChars());
    }

    @Test
    void quotedTokenSupportsSpacesInPaths() throws Exception {
        write("my docs/read me.txt", "hello");

        var resolution = AtFilePins.resolve(
                "summarize @\"my docs/read me.txt\"", workspace, sandbox());

        assertEquals(1, resolution.pins().size());
        assertEquals("my docs/read me.txt", resolution.pins().get(0).path());
    }

    @Test
    void trailingSentencePunctuationIsNotPartOfThePath() throws Exception {
        write("notes.txt", "n");

        var resolution = AtFilePins.resolve(
                "have a look at @notes.txt.", workspace, sandbox());

        assertEquals(1, resolution.pins().size());
        assertEquals("notes.txt", resolution.pins().get(0).path());
    }

    @Test
    void emailAddressesAndMidWordAtSignsAreNotPins() {
        var resolution = AtFilePins.resolve(
                "mail user@example.com about foo@bar", workspace, sandbox());

        assertTrue(resolution.pins().isEmpty());
        assertTrue(resolution.notices().isEmpty(), "no noise for non-pin @ usage");
    }

    @Test
    void repeatedTokensPinOnce() throws Exception {
        write("a.txt", "a");

        var resolution = AtFilePins.resolve("@a.txt and @a.txt", workspace, sandbox());

        assertEquals(1, resolution.pins().size());
    }

    @Test
    void missingFileGetsANotice() {
        var resolution = AtFilePins.resolve("@nope.txt", workspace, sandbox());

        assertTrue(resolution.pins().isEmpty());
        assertEquals(1, resolution.notices().size());
        assertEquals("@-pin 'nope.txt' skipped: no such file in the workspace.",
                resolution.notices().get(0));
    }

    @Test
    void directoriesAreNotPinnable() throws Exception {
        Files.createDirectories(workspace.resolve("src"));

        var resolution = AtFilePins.resolve("@src", workspace, sandbox());

        assertTrue(resolution.pins().isEmpty());
        assertEquals("@-pin 'src' skipped: it is a directory; pin individual files.",
                resolution.notices().get(0));
    }

    @Test
    void outsideWorkspacePathsAreSkippedWithoutRevealingAnything() {
        var resolution = AtFilePins.resolve("@../outside.txt", workspace, sandbox());

        assertTrue(resolution.pins().isEmpty());
        assertEquals("@-pin '../outside.txt' skipped: outside the workspace.",
                resolution.notices().get(0));
    }

    /**
     * The T802 trust proof: a protected path can never enter pinned
     * content — and the refusal is identical whether or not the file
     * exists, so the notice does not leak existence either.
     */
    @Test
    void protectedPathsAreRefusedAndTheirContentNeverLeaks() throws Exception {
        write(".env", "API_KEY=super-secret-value");

        var existing = AtFilePins.resolve("@.env", workspace, sandbox());
        var missing = AtFilePins.resolve("@.talos/profiles.yaml", workspace, sandbox());

        assertTrue(existing.pins().isEmpty());
        assertTrue(existing.notices().get(0).startsWith("@-pin '.env' refused: protected path."));
        assertTrue(existing.notices().get(0).contains("read_file"));
        assertFalse(existing.notices().get(0).contains("super-secret-value"));

        assertTrue(missing.pins().isEmpty());
        assertTrue(missing.notices().get(0)
                        .startsWith("@-pin '.talos/profiles.yaml' refused: protected path."),
                "a protected path that does not exist gets the same refusal, not a miss");
    }

    @Test
    void filesOverTwoMiBAreSkipped() throws Exception {
        byte[] big = new byte[(int) AtFilePins.MAX_FILE_BYTES + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        Files.write(workspace.resolve("big.txt"), big);

        var resolution = AtFilePins.resolve("@big.txt", workspace, sandbox());

        assertTrue(resolution.pins().isEmpty());
        assertEquals("@-pin 'big.txt' skipped: file exceeds 2 MiB.",
                resolution.notices().get(0));
    }

    @Test
    void binaryContentFailsClosed() throws Exception {
        Files.write(workspace.resolve("blob.bin"), new byte[] {0, 1, 2, (byte) 0xFF, (byte) 0xFE});

        var resolution = AtFilePins.resolve("@blob.bin", workspace, sandbox());

        assertTrue(resolution.pins().isEmpty());
        assertEquals("@-pin 'blob.bin' skipped: not readable as UTF-8 text.",
                resolution.notices().get(0));
    }

    @Test
    void perFileHeadIsCappedAtFourThousandChars() throws Exception {
        write("long.txt", "x".repeat(5_000));

        var resolution = AtFilePins.resolve("@long.txt", workspace, sandbox());

        var pin = resolution.pins().get(0);
        assertEquals(AtFilePins.PER_FILE_CHARS, pin.content().length());
        assertTrue(pin.truncated());
        assertEquals(5_000, pin.totalChars());
    }

    @Test
    void totalBudgetIsTwelveThousandChars() throws Exception {
        for (int i = 1; i <= 4; i++) {
            write("f" + i + ".txt", String.valueOf((char) ('a' + i - 1)).repeat(4_000));
        }

        var resolution = AtFilePins.resolve(
                "@f1.txt @f2.txt @f3.txt @f4.txt", workspace, sandbox());

        assertEquals(3, resolution.pins().size(), "3 x 4000 exhausts the 12000 budget");
        assertEquals(AtFilePins.TOTAL_CHARS, resolution.pinnedChars());
        assertEquals("@-pin 'f4.txt' skipped: 12000-character pin budget exhausted.",
                resolution.notices().get(0));
    }

    @Test
    void atMostFourFilesPinPerPrompt() throws Exception {
        for (int i = 1; i <= 5; i++) {
            write("s" + i + ".txt", "tiny");
        }

        var resolution = AtFilePins.resolve(
                "@s1.txt @s2.txt @s3.txt @s4.txt @s5.txt", workspace, sandbox());

        assertEquals(4, resolution.pins().size());
        assertEquals("@-pin limit is 4 files per prompt; ignored: @s5.txt",
                resolution.notices().get(0));
    }

    @Test
    void promptsWithoutTokensResolveToNothing() {
        assertTrue(AtFilePins.resolve("no pins here", workspace, sandbox()).pins().isEmpty());
        assertTrue(AtFilePins.resolve(null, workspace, sandbox()).pins().isEmpty());
    }
}
