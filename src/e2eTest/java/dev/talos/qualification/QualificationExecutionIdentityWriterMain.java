package dev.talos.qualification;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Command-line entry point for the protocol-v6 execution-identity producer. */
public final class QualificationExecutionIdentityWriterMain {
    private static final String ROOT = "--root";
    private static final String REPOSITORY_ROOT = "--repository-root";
    private static final String BUILT_TALOS = "--built-talos";
    private static final String INSTALLED_TALOS = "--installed-talos";
    private static final String MODEL_ARTIFACT = "--model-artifact";
    private static final String ENGINE_EXECUTABLE = "--engine-executable";
    private static final String HARNESS_INPUTS = "--harness-inputs";
    private static final String CURRENT_COMMIT = "--current-commit";
    private static final String CURRENT_VERSION = "--current-version";

    private QualificationExecutionIdentityWriterMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        QualificationExecutionIdentityWriter.Result result =
                QualificationExecutionIdentityWriter.write(
                        new QualificationExecutionIdentityWriter.Request(
                                Path.of(required(options, ROOT)),
                                Path.of(required(options, REPOSITORY_ROOT)),
                                Path.of(required(options, BUILT_TALOS)),
                                Path.of(required(options, INSTALLED_TALOS)),
                                Path.of(required(options, MODEL_ARTIFACT)),
                                Path.of(required(options, ENGINE_EXECUTABLE)),
                                Path.of(required(options, HARNESS_INPUTS)),
                                required(options, CURRENT_COMMIT),
                                required(options, CURRENT_VERSION)));
        System.out.println("executionInputLedger=" + result.executionInputLedger());
        System.out.println("executionInputLedgerSha256=" + result.executionInputLedgerSha256());
        System.out.println("executionInputLedgerCreated=" + result.executionInputLedgerCreated());
        System.out.println("harnessManifest=" + result.harnessManifest());
        System.out.println("harnessManifestSha256=" + result.harnessManifestSha256());
        System.out.println("harnessManifestCreated=" + result.harnessManifestCreated());
        System.out.println("runtimeIdentity=" + result.runtimeIdentity());
        System.out.println("runtimeIdentitySha256=" + result.runtimeIdentitySha256());
        System.out.println("runtimeIdentityCreated=" + result.runtimeIdentityCreated());
    }

    private static Map<String, String> parse(String[] args) {
        if (args == null || args.length == 0 || args.length % 2 != 0) {
            throw new IllegalArgumentException("execution identity command requires option/value pairs");
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String option = args[i];
            if (!allowed(option)) throw new IllegalArgumentException("unknown option: " + option);
            String value = args[i + 1];
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing value for " + option);
            }
            if (options.putIfAbsent(option, value) != null) {
                throw new IllegalArgumentException("duplicate option: " + option);
            }
        }
        return options;
    }

    private static boolean allowed(String option) {
        return switch (option) {
            case ROOT, REPOSITORY_ROOT, BUILT_TALOS, INSTALLED_TALOS,
                    MODEL_ARTIFACT, ENGINE_EXECUTABLE, HARNESS_INPUTS,
                    CURRENT_COMMIT, CURRENT_VERSION -> true;
            default -> false;
        };
    }

    private static String required(Map<String, String> options, String option) {
        String value = options.get(option);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(option + " is required");
        }
        return value;
    }
}
