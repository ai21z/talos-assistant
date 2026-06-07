package dev.talos.runtime.policy;

import dev.talos.safety.SafeLogFormatter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/** CLI wrapper for writing canary-safe workspace snapshots into audit packets. */
public final class RedactedAuditSnapshotCli {
    private RedactedAuditSnapshotCli() {}

    public static void main(String[] args) {
        int code = run(List.of(args), System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(List<String> args, PrintStream out, PrintStream err) {
        RedactedAuditSnapshotWriter.Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException ex) {
            err.println(ex.getMessage());
            usage(err);
            return 64;
        }

        try {
            RedactedAuditSnapshotWriter.Summary summary = RedactedAuditSnapshotWriter.write(options);
            out.println("Redacted audit snapshot written: " + summary.output().toAbsolutePath().normalize());
            out.println("label=" + summary.label()
                    + " totalFiles=" + summary.totalFiles()
                    + " safeTextFiles=" + summary.safeTextFiles()
                    + " omittedFiles=" + summary.omittedFiles());
            return 0;
        } catch (IOException | IllegalStateException ex) {
            err.println("Redacted audit snapshot failed: " + SafeLogFormatter.throwableMessage(ex));
            return 1;
        }
    }

    private static RedactedAuditSnapshotWriter.Options parse(List<String> args) {
        Path workspace = null;
        Path output = null;
        String label = "snapshot";
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--workspace" -> workspace = Path.of(next(args, ++i, "--workspace"));
                case "--output" -> output = Path.of(next(args, ++i, "--output"));
                case "--label" -> label = next(args, ++i, "--label");
                case "--help", "-h" -> throw new IllegalArgumentException("Redacted audit snapshot options");
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        if (workspace == null) {
            throw new IllegalArgumentException("--workspace is required");
        }
        if (output == null) {
            throw new IllegalArgumentException("--output is required");
        }
        return new RedactedAuditSnapshotWriter.Options(workspace, output, label);
    }

    private static String next(List<String> args, int index, String option) {
        if (index >= args.size() || args.get(index).startsWith("--")) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args.get(index);
    }

    private static void usage(PrintStream err) {
        err.println("Usage: writeRedactedAuditSnapshot --workspace <dir> --output <dir> [--label <name>]");
    }
}
