package dev.loqj.cli.cmds;

import dev.loqj.cli.ManifestVersionProvider;
import picocli.CommandLine;

@CommandLine.Command(name = "version", description = "Show version information")
public class VersionCmd implements Runnable {

    @Override
    public void run() {
        try {
            ManifestVersionProvider provider = new ManifestVersionProvider();
            String[] versions = provider.getVersion();
            for (String version : versions) {
                System.out.println(version);
            }
        } catch (Exception e) {
            // Use same ASCII fallback logic as ManifestVersionProvider
            String bullet = getAsciiSafeBullet();
            System.out.println("LOQ-J 0.9.0-beta " + bullet + " Java " +
                System.getProperty("java.runtime.version", "unknown") +
                " " + bullet + " " + System.getProperty("os.name", "unknown") +
                " " + System.getProperty("os.arch", "unknown"));
        }
    }

    private String getAsciiSafeBullet() {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase();
            String consoleEncoding = System.getProperty("sun.stdout.encoding", "");

            // Use ASCII dash for Windows consoles that aren't UTF-8
            if (osName.contains("windows") && !consoleEncoding.toLowerCase().contains("utf")) {
                return "-";
            }
        } catch (Exception ignore) {
            // Fall back to ASCII dash if detection fails
        }
        return "-"; // Use ASCII dash as safe fallback
    }
}
