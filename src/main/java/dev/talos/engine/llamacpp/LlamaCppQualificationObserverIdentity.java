package dev.talos.engine.llamacpp;

import dev.talos.spi.types.ProviderProcessLaunchReceipt;

import java.io.IOException;
import java.util.Objects;

/** Path-free identity of the local endpoint observer used by managed qualification. */
public record LlamaCppQualificationObserverIdentity(
        ProviderProcessLaunchReceipt.EndpointObserverKind kind,
        String sha256) {

    public LlamaCppQualificationObserverIdentity {
        kind = Objects.requireNonNull(kind, "endpoint observer kind");
        sha256 = Objects.toString(sha256, "");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "endpoint observer identity SHA-256 is invalid");
        }
    }

    /** Reads and hashes the observer authority without executing its ownership query. */
    public static LlamaCppQualificationObserverIdentity current() throws IOException {
        LlamaCppEndpointOwnerProbe.ObserverIdentity identity =
                LlamaCppEndpointOwnerProbe.currentObserverIdentity();
        return new LlamaCppQualificationObserverIdentity(
                identity.kind(), identity.sha256());
    }
}
