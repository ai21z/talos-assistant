package dev.loqj.app;
 
import dev.loqj.app.ui.FirstRunWizard;
import dev.loqj.cli.cmds.RootCmd;
import picocli.CommandLine;
 
public class Main {
    public static void main(String[] args) {
        boolean hasArgs = args != null && args.length > 0;
        if (!hasArgs && FirstRunWizard.shouldRunWizard()) {
            FirstRunWizard.launchWizard();
            return;
        }
        int ec = new CommandLine(new RootCmd()).execute(args);
        System.exit(ec);
    }
}
