package dev.talos.app;
 
import dev.talos.app.ui.TerminalFirstRun;
import dev.talos.cli.launcher.RootCmd;
import dev.talos.cli.ui.ConsoleNoisePolicy;
import dev.talos.core.util.BuildInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
 
public class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        ConsoleNoisePolicy.install();

        // R7 - single build-identity line per process so transcripts and
        // log files can be traced to a specific build. Graceful "unknown"
        // fallbacks when metadata is absent (see BuildInfo).
        LOG.info("Talos startup - {}", BuildInfo.summary());

        boolean hasArgs = args != null && args.length > 0;
        if (!hasArgs && TerminalFirstRun.shouldRun()) {
            if (!TerminalFirstRun.run()) {
                System.exit(1);
                return;
            }
        }
        int ec = new CommandLine(new RootCmd()).execute(args);
        System.exit(ec);
    }
}
