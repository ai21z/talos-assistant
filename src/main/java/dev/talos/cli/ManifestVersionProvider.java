package dev.talos.cli;

import dev.talos.core.util.BuildInfo;
import picocli.CommandLine;
import java.nio.charset.Charset;

/**
 * Version provider that reads from JAR manifest attributes with ASCII fallback for Windows.
 */
public class ManifestVersionProvider implements CommandLine.IVersionProvider {

    private static String getBulletChar() {
        try {
            // Check for Windows and non-UTF-8 console
            String osName = System.getProperty("os.name", "").toLowerCase();
            String consoleEncoding = System.getProperty("sun.stdout.encoding", "");

            // Use ASCII dash for Windows consoles that aren't UTF-8
            if (osName.contains("windows") && !consoleEncoding.toLowerCase().contains("utf")) {
                return "-";
            }

            // Try to detect if we can use UTF-8 bullet
            Charset defaultCharset = Charset.defaultCharset();
            if ("UTF-8".equalsIgnoreCase(defaultCharset.name()) ||
                "UTF8".equalsIgnoreCase(defaultCharset.name())) {
                return "•";
            }
        } catch (Exception ignore) {
            // Fall back to ASCII dash if detection fails
        }
        return "-"; // Use ASCII dash as safe fallback
    }

    @Override
    public String[] getVersion() throws Exception {
        Package pkg = getClass().getPackage();
        String title = pkg.getImplementationTitle();
        String version = BuildInfo.version();

        if (title == null) title = "talos";
        if (BuildInfo.UNKNOWN.equals(version)) version = "unknown";

        // Java runtime info
        String javaVersion = System.getProperty("java.runtime.version", "unknown");
        String osName = System.getProperty("os.name", "unknown");
        String osArch = System.getProperty("os.arch", "unknown");

        String bullet = getBulletChar();
        StringBuilder info = new StringBuilder();
        info.append(title).append(" ").append(version);
        info.append(" ").append(bullet).append(" Java ").append(javaVersion);
        info.append(" ").append(bullet).append(" ").append(osName).append(" ").append(osArch);

        // Optional build info from manifest
        String buildInfo = BuildInfo.buildTimestamp();
        if (!BuildInfo.UNKNOWN.equals(buildInfo)) {
            info.append(" ").append(bullet).append(" build ").append(buildInfo);
        }

        return new String[] { info.toString() };
    }
}
