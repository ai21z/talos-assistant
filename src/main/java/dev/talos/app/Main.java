package dev.talos.app;
 
import dev.talos.app.ui.TerminalFirstRun;
import dev.talos.cli.cmds.RootCmd;
import picocli.CommandLine;
 
public class Main {
    public static void main(String[] args) {
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
