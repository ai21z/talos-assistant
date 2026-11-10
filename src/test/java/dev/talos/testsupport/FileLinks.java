package dev.talos.testsupport;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Link fixtures for tests. A test is aborted where the host cannot make the link. */
public final class FileLinks {
    private FileLinks() {}

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** A symbolic link whose target is stored exactly as given, relative or absolute. */
    public static Path symbolicLink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.abort("symbolic links are not available: " + unavailable.getClass().getSimpleName());
            return link;
        }
    }

    /** A directory alias: an NTFS junction on Windows, which needs no privilege, else a symbolic link. */
    public static Path directoryAlias(Path link, Path target) throws Exception {
        if (!windows()) return symbolicLink(link, target);
        return junction(link, target);
    }

    /** An NTFS junction. The test is aborted off Windows or where mklink fails. */
    public static Path junction(Path link, Path target) throws Exception {
        Assumptions.assumeTrue(windows(), "junctions exist only on Windows");
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                link.toString(), target.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) Assumptions.abort("mklink /J failed: " + output.strip());
        return link;
    }
}
