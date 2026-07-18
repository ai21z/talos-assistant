package dev.talos.qualification;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only command-line verifier for an existing protocol-v6 execution identity chain. */
public final class QualificationExecutionIdentityVerifierMain {
    private static final String ROOT = "--root";
    private static final String REPOSITORY_ROOT = "--repository-root";
    private static final String BUILT_TALOS = "--built-talos";
    private static final String INSTALLED_TALOS = "--installed-talos";
    private static final String MODEL_ARTIFACT = "--model-artifact";
    private static final String ENGINE_EXECUTABLE = "--engine-executable";
    private static final String CURRENT_COMMIT = "--current-commit";
    private static final String CURRENT_VERSION = "--current-version";

    private QualificationExecutionIdentityVerifierMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        QualificationExecutionIdentityWriter.VerificationResult result =
                QualificationExecutionIdentityWriter.verifyExisting(
                        new QualificationExecutionIdentityWriter.VerificationRequest(
                                Path.of(required(options, ROOT)),
                                Path.of(required(options, REPOSITORY_ROOT)),
                                Path.of(required(options, BUILT_TALOS)),
                                Path.of(required(options, INSTALLED_TALOS)),
                                Path.of(required(options, MODEL_ARTIFACT)),
                                Path.of(required(options, ENGINE_EXECUTABLE)),
                                required(options, CURRENT_COMMIT),
                                required(options, CURRENT_VERSION)));
        System.out.println("identityRootVerified=true");
        System.out.println("executionInputLedgerSha256="
                + result.executionInputLedgerSha256());
        System.out.println("harnessManifestSha256=" + result.harnessManifestSha256());
        System.out.println("runtimeIdentitySha256=" + result.runtimeIdentitySha256());
        System.out.println("builtTalosArtifactSha256=" + result.builtTalosArtifactSha256());
        System.out.println("installedTalosArtifactSha256="
                + result.installedTalosArtifactSha256());
        System.out.println("installedRuntimeClasspathSha256="
                + result.installedRuntimeClasspathSha256());
        System.out.println("modelArtifactObservedSha256="
                + result.modelArtifactObservedSha256());
        System.out.println("engineExecutableObservedSha256="
                + result.engineExecutableObservedSha256());
        System.out.println("externalArtifactVerificationScope="
                + result.externalArtifactVerificationScope());
        System.out.println("activeProcessLaunchReceipt="
                + result.activeProcessLaunchReceipt());
    }

    private static Map<String, String> parse(String[] args) {
        if (args == null || args.length == 0 || args.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "execution identity verification requires option/value pairs");
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
                    MODEL_ARTIFACT, ENGINE_EXECUTABLE, CURRENT_COMMIT, CURRENT_VERSION -> true;
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
