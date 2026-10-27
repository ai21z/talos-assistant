package dev.talos.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Folders in which two names that differ only in case are two files. */
public final class CaseSensitiveFolders {
    private CaseSensitiveFolders() {}

    /** Marks an empty Windows folder case-sensitive. False when fsutil is missing, refuses or hangs. */
    public static boolean enable(Path emptyFolder) {
        try {
            Process process = new ProcessBuilder(
                    "fsutil.exe", "file", "setCaseSensitiveInfo", emptyFolder.toString(), "enable")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | RuntimeException unavailable) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Writes both spellings with different content. True only when the file system kept them apart. */
    public static boolean writeBothSpellings(Path folder, String lower, String upper) throws IOException {
        Files.writeString(folder.resolve(lower), "lower\n");
        Files.writeString(folder.resolve(upper), "upper\n");
        return "lower\n".equals(Files.readString(folder.resolve(lower)))
                && "upper\n".equals(Files.readString(folder.resolve(upper)));
    }
}
