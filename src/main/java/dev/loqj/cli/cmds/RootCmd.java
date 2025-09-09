package dev.loqj.cli.cmds;

import picocli.CommandLine;

@CommandLine.Command(
        name = "loqj",
        mixinStandardHelpOptions = true,
        version = "loqj 0.1.0",
        description = "LOQ-J local RAG agent",
        subcommands = {
                SetupCmd.class, RagIndexCmd.class, RagAskCmd.class, RunCmd.class,
                NetCmd.class
        }
)
public class RootCmd implements Runnable {
    @Override public void run() {
        System.out.println("LOQ-J CLI. Use --help.");
    }
}
