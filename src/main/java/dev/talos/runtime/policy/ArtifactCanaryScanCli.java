package dev.talos.runtime.policy;

import dev.talos.safety.SafeLogFormatter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI wrapper used by release tasks to scan generated runtime artifacts for raw canaries.
 *
 * <p>Security note (Qodana JvmTaintAnalysis, accepted via the Qodana baseline): the scan roots
 * flow from this CLI's own {@code args} into {@link Path} values read by the scanner. That argv
 * is supplied by the release task or the operator who runs the scan (a trusted source), and
 * scanning the directories you point it at is the entire purpose of the tool. There is no
 * untrusted or remote input path here, so the flagged args -&gt; path-traversal flow is not a
 * vulnerability; it is baselined rather than "fixed" (constraining the roots would break the tool).
 */
public final class ArtifactCanaryScanCli {
    private ArtifactCanaryScanCli() {}

    public static void main(String[] args) {
        int code = run(List.of(args), System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(List<String> args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException ex) {
            err.println(ex.getMessage());
            usage(err);
            return 64;
        }

        try {
            List<ArtifactCanaryScanner.Finding> findings = options.runtime
                    ? ArtifactCanaryScanner.scanRuntimeArtifacts(options.roots, options.allowlist)
                    : ArtifactCanaryScanner.scanExisting(options.roots, options.allowlist);
            if (findings.isEmpty()) {
                out.println("Artifact canary scan passed. Roots scanned: " + options.roots);
                return 0;
            }
            err.println("Artifact canary scan failed. Raw canary findings:");
            for (ArtifactCanaryScanner.Finding finding : findings) {
                err.println(finding.path().toAbsolutePath().normalize()
                        + ":" + finding.line()
                        + ": " + finding.snippet());
            }
            return 2;
        } catch (IOException ex) {
            err.println("Artifact canary scan failed to read artifacts: "
                    + SafeLogFormatter.throwableMessage(ex));
            return 1;
        }
    }

    private static Options parse(List<String> args) {
        List<Path> roots = new ArrayList<>();
        List<Path> allowlist = new ArrayList<>();
        boolean runtime = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--runtime" -> runtime = true;
                case "--broad" -> runtime = false;
                case "--root" -> roots.add(Path.of(next(args, ++i, "--root")));
                case "--roots" -> splitPaths(next(args, ++i, "--roots"), roots);
                case "--allow" -> allowlist.add(Path.of(next(args, ++i, "--allow")));
                case "--allowlist" -> splitPaths(next(args, ++i, "--allowlist"), allowlist);
                case "--help", "-h" -> throw new IllegalArgumentException("Artifact canary scan options");
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        if (roots.isEmpty()) {
            roots.add(Path.of("local/manual-testing"));
            roots.add(Path.of("local/manual-workspaces"));
        }
        return new Options(List.copyOf(roots), List.copyOf(allowlist), runtime);
    }

    private static String next(List<String> args, int index, String option) {
        if (index >= args.size() || args.get(index).startsWith("--")) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args.get(index);
    }

    private static void splitPaths(String raw, List<Path> out) {
        for (String part : raw.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                out.add(Path.of(trimmed));
            }
        }
    }

    private static void usage(PrintStream err) {
        err.println("Usage: checkRuntimeArtifactCanaries --runtime --root <dir> [--root <dir>] [--allow <file>]");
        err.println("       --roots and --allowlist accept comma- or semicolon-separated paths.");
    }

    private record Options(List<Path> roots, List<Path> allowlist, boolean runtime) {}
}
