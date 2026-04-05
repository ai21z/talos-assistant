package dev.loqj.cli.cmds;

import dev.loqj.cli.ManifestVersionProvider;
import picocli.CommandLine;

@CommandLine.Command(
        name = "loqs",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        description = "Loqs - Local Knowledge Engine",
        subcommands = {
                SetupCmd.class, RagIndexCmd.class, RagAskCmd.class, RunCmd.class,
                NetCmd.class, TopLevelStatusCmd.class, VersionCmd.class, DiagnoseCmd.class
        }
)
public class RootCmd implements Runnable {

    @CommandLine.Option(names = {"-v", "--version"}, versionHelp = true, description = "Show version information")
    boolean versionRequested;

    @CommandLine.Option(names = {"--no-logo"}, description = "Skip banner/logo display")
    boolean noLogo;

    @Override
    public void run() {
        // If no subcommand specified, default to interactive REPL (loqs run)
        RunCmd runCmd = new RunCmd();
        runCmd.noLogo = this.noLogo; // Pass the no-logo flag
        runCmd.run();
    }
}
