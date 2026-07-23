package dev.talos.engine.llamacpp;

import java.util.Objects;

/** Frozen, path-free content identities required at a qualification launch boundary. */
record LlamaCppQualificationLaunchExpectation(
        String candidateDeclarationSha256,
        String engineExecutableSha256,
        String modelArtifactSha256,
        String effectiveEnvironmentSha256,
        dev.talos.spi.types.ProviderProcessLaunchReceipt.EndpointObserverKind
                endpointObserverKind,
        String endpointObserverIdentitySha256,
        dev.talos.spi.types.ProviderProcessLaunchReceipt.LaunchArgumentSet launchArgumentSet) {

    LlamaCppQualificationLaunchExpectation {
        candidateDeclarationSha256 = hash(candidateDeclarationSha256, "candidate declaration");
        engineExecutableSha256 = hash(engineExecutableSha256, "engine executable");
        modelArtifactSha256 = hash(modelArtifactSha256, "model artifact");
        effectiveEnvironmentSha256 = hash(
                effectiveEnvironmentSha256, "provider-relevant environment");
        endpointObserverKind = Objects.requireNonNull(
                endpointObserverKind, "endpoint observer kind");
        endpointObserverIdentitySha256 = hash(
                endpointObserverIdentitySha256, "endpoint observer identity");
        launchArgumentSet = Objects.requireNonNull(launchArgumentSet, "launch argument set");
    }

    private static String hash(String value, String label) {
        String exact = Objects.toString(value, "");
        if (!exact.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " SHA-256 is invalid");
        }
        return exact;
    }
}
