package dev.talos.spi.types;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Mechanically observed local dispatch commitment for one generation request.
 *
 * <p>Engine implementations must invoke {@link #requestDispatched()}
 * immediately before the transport's send operation. Request serialization,
 * provider-body capture, URI construction, and other local preparation happen
 * before this callback and therefore cannot be mistaken for a committed
 * provider handoff. The callback does not prove that the underlying HTTP or
 * process send has started: an engine can still be paused between this return
 * and its immediately following send.
 */
@FunctionalInterface
public interface ProviderTransportHandoff {

    /** Exact local provider-body identity admitted at the transport handoff. */
    record DispatchEvidence(String providerBodyHash, List<String> nativeTools) {
        public DispatchEvidence {
            providerBodyHash = Objects.toString(providerBodyHash, "").strip();
            nativeTools = nativeTools == null ? List.of() : nativeTools.stream()
                    .map(tool -> Objects.requireNonNull(tool, "native tool name"))
                    .toList();
            if (!providerBodyHash.isEmpty()
                    && !providerBodyHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "provider body hash must be blank or lowercase SHA-256");
            }
            if (nativeTools.stream().anyMatch(tool ->
                    tool.isBlank() || !tool.equals(tool.strip()) || tool.length() > 256)) {
                throw new IllegalArgumentException(
                        "native tool names must be exact, nonblank, and bounded");
            }
            if (new java.util.LinkedHashSet<>(nativeTools).size() != nativeTools.size()) {
                throw new IllegalArgumentException("native tool names must be unique");
            }
            if (providerBodyHash.isEmpty() && !nativeTools.isEmpty()) {
                throw new IllegalArgumentException(
                        "unbound dispatch evidence cannot carry native tool names");
            }
        }

        public static DispatchEvidence unbound() {
            return new DispatchEvidence("", List.of());
        }

        public static DispatchEvidence fromProviderBody(
                String providerBody,
                List<ToolSpec> nativeTools) {
            String body = Objects.requireNonNull(providerBody, "provider body");
            List<String> names = nativeTools == null ? List.of() : nativeTools.stream()
                    .map(tool -> Objects.requireNonNull(tool, "native tool spec").name())
                    .toList();
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(body.getBytes(StandardCharsets.UTF_8));
                return new DispatchEvidence(
                        "sha256:" + HexFormat.of().formatHex(digest), names);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable", impossible);
            }
        }
    }

    /**
     * Typed local cancellation raised when the capture owner has already made
     * the attempt terminal before this callback. Engine clients deliberately do not wrap
     * this as a provider failure; propagation prevents the immediately
     * following transport send. It is not a cancellable-transport guarantee
     * after an accepted callback returns.
     */
    final class DispatchRejected extends RuntimeException {
        public DispatchRejected() {
            super("provider transport dispatch was rejected by the terminal attempt owner");
        }
    }

    void requestDispatched();

    /**
     * Admits a handoff carrying the exact serialized provider-body identity and
     * native tool names embedded in that body. Legacy implementations remain
     * unbound through the no-argument callback.
     */
    default void requestDispatched(DispatchEvidence evidence) {
        requestDispatched();
    }

    /**
     * Signals that a legacy engine path cannot expose its physical send boundary.
     * This preserves runtime compatibility without manufacturing a dispatch fact.
     */
    default void transportUnobserved() {}

    static ProviderTransportHandoff none() {
        return () -> { };
    }

    static void mark(ProviderTransportHandoff handoff) {
        Objects.requireNonNull(handoff, "provider transport handoff")
                .requestDispatched();
    }

    static void mark(
            ProviderTransportHandoff handoff,
            String providerBody,
            List<ToolSpec> nativeTools) {
        Objects.requireNonNull(handoff, "provider transport handoff")
                .requestDispatched(DispatchEvidence.fromProviderBody(providerBody, nativeTools));
    }

    static void markUnobserved(ProviderTransportHandoff handoff) {
        Objects.requireNonNull(handoff, "provider transport handoff")
                .transportUnobserved();
    }
}
