package dev.talos.cli.launcher;

import dev.talos.cli.ManifestVersionProvider;
import picocli.CommandLine;

@CommandLine.Command(
        name = "talos",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        description = "Talos - local-first workspace operator",
        subcommands = {
                SetupCmd.class, RagIndexCmd.class, RagAskCmd.class, RunCmd.class,
                NetCmd.class, TopLevelStatusCmd.class, VersionCmd.class, DiagnoseCmd.class,
                DoctorCmd.class, PromptRenderCmd.class, TuneCmd.class
        }
)
public class RootCmd implements Runnable {

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit")
    boolean helpRequested;

    @CommandLine.Option(names = {"-v", "--version"}, versionHelp = true, description = "Show version information")
    boolean versionRequested;

    @CommandLine.Option(names = {"--no-logo"}, description = "Skip banner/logo display")
    boolean noLogo;

    @Override
    public void run() {
        // If no subcommand specified, default to interactive REPL (Talos run)
        RunCmd runCmd = new RunCmd();
        runCmd.noLogo = this.noLogo; // Pass the no-logo flag
        runCmd.run();
    }
}
