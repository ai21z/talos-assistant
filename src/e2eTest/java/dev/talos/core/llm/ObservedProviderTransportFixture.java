package dev.talos.core.llm;

import dev.talos.spi.types.ProviderTransportAttempt;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** E2E-only observed provider transport for finite buffered responses. */
final class ObservedProviderTransportFixture {
    private ObservedProviderTransportFixture() {}

    static Stream<TokenChunk> dispatchCompletedTerminal(
            String providerBody,
            List<ToolSpec> nativeTools,
            ProviderTransportHandoff handoff,
            ProviderTransportAttempt.SendOperation<Stream<TokenChunk>> send) {
        ProviderTransportAttempt transport = ProviderTransportHandoff.admit(
                handoff,
                Objects.requireNonNull(providerBody, "provider body"),
                nativeTools);
        Stream<TokenChunk> stream;
        try {
            stream = Objects.requireNonNull(
                    transport.invokeSend(send),
                    "provider token stream");
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("provider fixture send failed", failure);
        }
        transport.responseHeadersObserved();
        transport.responseBodyOpened(stream::close);
        transport.providerTerminalObserved();
        transport.responseBodyCompleted();
        return stream;
    }
}
