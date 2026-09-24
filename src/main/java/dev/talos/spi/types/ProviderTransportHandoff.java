package dev.talos.spi.types;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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
    record DispatchEvidence(
            String providerBodyHash,
            List<String> nativeTools,
            Optional<ProviderProcessLaunchReceipt> processReceipt,
            ProviderReadInputBinding.SerializedMembership readInputMembership) {
        public DispatchEvidence {
            providerBodyHash = Objects.toString(providerBodyHash, "").strip();
            nativeTools = nativeTools == null ? List.of() : nativeTools.stream()
                    .map(tool -> Objects.requireNonNull(tool, "native tool name"))
                    .toList();
            processReceipt = processReceipt == null ? Optional.empty() : processReceipt;
            readInputMembership = readInputMembership == null
                    ? ProviderReadInputBinding.noSerializedMembership() : readInputMembership;
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

        public DispatchEvidence(String providerBodyHash, List<String> nativeTools) {
            this(providerBodyHash, nativeTools, Optional.empty());
        }

        public DispatchEvidence(String providerBodyHash, List<String> nativeTools,
                                Optional<ProviderProcessLaunchReceipt> processReceipt) {
            this(providerBodyHash, nativeTools, processReceipt,
                    ProviderReadInputBinding.noSerializedMembership());
        }

        public static DispatchEvidence unbound() {
            return new DispatchEvidence("", List.of());
        }

        public DispatchEvidence withProcessReceipt(
                ProviderProcessLaunchReceipt receipt) {
            ProviderProcessLaunchReceipt exact = Objects.requireNonNull(
                    receipt, "provider process receipt");
            if (processReceipt.isPresent() && !processReceipt.get().equals(exact)) {
                throw new IllegalArgumentException(
                        "dispatch evidence already carries a different process receipt");
            }
            return new DispatchEvidence(providerBodyHash, nativeTools, Optional.of(exact), readInputMembership);
        }

        public static DispatchEvidence fromProviderBody(
                String providerBody,
                List<ToolSpec> nativeTools) {
            return fromProviderBody(providerBody, nativeTools, null);
        }

        public static DispatchEvidence fromProviderBody(
                String providerBody,
                List<ToolSpec> nativeTools,
                ChatRequest request) {
            String body = Objects.requireNonNull(providerBody, "provider body");
            List<String> names = nativeTools == null ? List.of() : nativeTools.stream()
                    .map(tool -> Objects.requireNonNull(tool, "native tool spec").name())
                    .toList();
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(body.getBytes(StandardCharsets.UTF_8));
                String bodyHash = "sha256:" + HexFormat.of().formatHex(digest);
                return new DispatchEvidence(bodyHash, names, Optional.empty(),
                        ProviderReadInputBinding.decode(body, request, bodyHash));
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

    /**
     * Typed runtime-owned failure raised when the managed process identity can
     * no longer authorize the selected transport operation.
     *
     * <p>The cause can be a provider connectivity exception, but the failed
     * invariant is local process/transport authority. Keeping that distinction
     * prevents a runtime binding failure from being reported as provider-owned.
     */
    final class ProcessBindingInvalidated extends IllegalStateException {
        public ProcessBindingInvalidated(String message) {
            super(message);
        }

        public ProcessBindingInvalidated(String message, Throwable cause) {
            super(message, cause);
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
     * Admits one exact transport lifecycle. Legacy implementations retain the
     * bounded dispatch callback but receive an explicitly unobserved handle.
     */
    default ProviderTransportAttempt admitTransport(DispatchEvidence evidence) {
        requestDispatched(evidence);
        return ProviderTransportAttempt.unobservedAttempt();
    }

    /**
     * Signals that a legacy engine path cannot expose its physical send boundary.
     * This preserves runtime compatibility without manufacturing a dispatch fact.
     */
    default void transportUnobserved() {}

    static ProviderTransportHandoff none() {
        return () -> { };
    }

    /**
     * Revalidates the managed process receipt at each exact transport
     * admission, after request assembly and immediately before the physical
     * transport owner can enter its send API.
     */
    static ProviderTransportHandoff bindProcessReceipt(
            ProviderTransportHandoff delegate,
            Supplier<ProviderProcessLaunchReceipt> receiptSupplier) {
        ProviderTransportHandoff target = Objects.requireNonNull(
                delegate, "provider transport handoff");
        Supplier<ProviderProcessLaunchReceipt> receipts = Objects.requireNonNull(
                receiptSupplier, "provider process receipt supplier");
        return new ProviderTransportHandoff() {
            private DispatchEvidence bind(DispatchEvidence evidence) {
                try {
                    ProviderProcessLaunchReceipt exact = Objects.requireNonNull(
                            receipts.get(), "active provider process receipt");
                    return (evidence == null ? DispatchEvidence.unbound() : evidence)
                            .withProcessReceipt(exact);
                } catch (ProcessBindingInvalidated invalid) {
                    throw invalid;
                } catch (RuntimeException invalid) {
                    throw new ProcessBindingInvalidated(
                            "active provider process receipt is unavailable", invalid);
                }
            }

            @Override
            public void requestDispatched() {
                target.requestDispatched(bind(DispatchEvidence.unbound()));
            }

            @Override
            public void requestDispatched(DispatchEvidence evidence) {
                target.requestDispatched(bind(evidence));
            }

            @Override
            public ProviderTransportAttempt admitTransport(DispatchEvidence evidence) {
                DispatchEvidence bound = bind(evidence);
                ProviderProcessLaunchReceipt admitted = bound.processReceipt().orElseThrow();
                ProviderTransportAttempt attempt = target.admitTransport(bound);
                return processValidatedAttempt(attempt, receipts, admitted);
            }

            @Override
            public void transportUnobserved() {
                target.transportUnobserved();
            }
        };
    }

    private static ProviderTransportAttempt processValidatedAttempt(
            ProviderTransportAttempt delegate,
            Supplier<ProviderProcessLaunchReceipt> receipts,
            ProviderProcessLaunchReceipt admitted) {
        ProviderTransportAttempt target = Objects.requireNonNull(
                delegate, "provider transport attempt");
        return new ProviderTransportAttempt() {
            private void validate() {
                try {
                    ProviderProcessLaunchReceipt current = Objects.requireNonNull(
                            receipts.get(), "active provider process receipt");
                    if (!admitted.equals(current)) {
                        throw new ProcessBindingInvalidated(
                                "provider process receipt changed during transport");
                    }
                } catch (ProcessBindingInvalidated invalid) {
                    target.processBindingInvalidated();
                    throw invalid;
                } catch (RuntimeException invalid) {
                    target.processBindingInvalidated();
                    throw new ProcessBindingInvalidated(
                            "active provider process receipt is unavailable", invalid);
                } catch (Error invalid) {
                    target.processBindingInvalidated();
                    throw invalid;
                }
            }

            private void validateWithoutMaskingFailure() {
                try {
                    validate();
                } catch (RuntimeException | Error ignored) {
                    // The exact invalidation is retained by the transport
                    // ledger; the original body/cancellation failure remains
                    // the control-flow owner.
                }
            }

            @Override
            public <T> T invokeSend(SendOperation<T> operation) throws Exception {
                validate();
                return target.invokeSend(Objects.requireNonNull(
                        operation, "provider send operation"));
            }

            @Override
            public void responseHeadersObserved() {
                validate();
                target.responseHeadersObserved();
            }

            @Override
            public void responseBodyOpened(AutoCloseable responseBody) {
                AutoCloseable exactBody = Objects.requireNonNull(
                        responseBody, "provider response body");
                try {
                    validate();
                    target.responseBodyOpened(exactBody);
                } catch (RuntimeException | Error rejected) {
                    try {
                        exactBody.close();
                    } catch (Exception closeFailure) {
                        rejected.addSuppressed(closeFailure);
                    }
                    throw rejected;
                }
            }

            @Override
            public void providerTerminalObserved() {
                validate();
                target.providerTerminalObserved();
            }

            @Override
            public void responseBodyCompleted() {
                validate();
                target.responseBodyCompleted();
            }

            @Override
            public void responseBodyFailed(Throwable failure) {
                validateWithoutMaskingFailure();
                target.responseBodyFailed(failure);
            }

            @Override
            public void responseBodyClosed() {
                validateWithoutMaskingFailure();
                target.responseBodyClosed();
            }

            @Override
            public void processBindingInvalidated() {
                target.processBindingInvalidated();
            }

            @Override
            public boolean unobserved() {
                return target.unobserved();
            }
        };
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

    static ProviderTransportAttempt admit(
            ProviderTransportHandoff handoff,
            String providerBody,
            List<ToolSpec> nativeTools) {
        return Objects.requireNonNull(handoff, "provider transport handoff")
                .admitTransport(DispatchEvidence.fromProviderBody(providerBody, nativeTools));
    }

    /** Dispatch is denied when artifact retention cannot be prevented. */
    final class ArtifactRetentionRejected extends IllegalStateException {
        public ArtifactRetentionRejected() {
            super("Provider content was withheld because denied artifact retention could not be enforced.");
        }
    }

    static ProviderTransportAttempt admit(
            ProviderTransportHandoff handoff,
            String providerBody,
            List<ToolSpec> nativeTools,
            ChatRequest request) {
        return Objects.requireNonNull(handoff, "provider transport handoff")
                .admitTransport(DispatchEvidence.fromProviderBody(providerBody, nativeTools, request));
    }

    static void markUnobserved(ProviderTransportHandoff handoff) {
        Objects.requireNonNull(handoff, "provider transport handoff")
                .transportUnobserved();
    }
}
