package dev.talos.spi.types;

import java.util.Objects;
import java.util.stream.Stream;

/** Test-only foreground-provider transport with an observed terminal lifecycle. */
public final class ObservedProviderTransportFixture {
    private ObservedProviderTransportFixture() {}

    public static Stream<TokenChunk> dispatch(
            ChatRequest request,
            ProviderTransportHandoff handoff,
            ProviderTransportAttempt.SendOperation<Stream<TokenChunk>> send) {
        return dispatch(request, handoff, send, false);
    }

    public static Stream<TokenChunk> dispatchCompletedTerminal(
            ChatRequest request,
            ProviderTransportHandoff handoff,
            ProviderTransportAttempt.SendOperation<Stream<TokenChunk>> send) {
        return dispatch(request, handoff, send, true);
    }

    private static Stream<TokenChunk> dispatch(
            ChatRequest request,
            ProviderTransportHandoff handoff,
            ProviderTransportAttempt.SendOperation<Stream<TokenChunk>> send,
            boolean bufferedProviderTerminal) {
        ChatRequest exactRequest = Objects.requireNonNull(request, "provider request");
        String providerBody = String.join(
                "\n",
                exactRequest.backend,
                exactRequest.model,
                exactRequest.systemPrompt,
                exactRequest.userPrompt,
                exactRequest.snippets.toString(),
                exactRequest.messages.toString(),
                exactRequest.tools.toString(),
                exactRequest.controls.toString());
        ProviderTransportAttempt transport = ProviderTransportHandoff.admit(
                handoff,
                providerBody,
                exactRequest.tools);
        Stream<TokenChunk> stream;
        try {
            stream = Objects.requireNonNull(
                    transport.invokeSend(send),
                    "provider token stream");
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("scripted provider send failed", failure);
        }
        transport.responseHeadersObserved();
        transport.responseBodyOpened(stream::close);
        if (bufferedProviderTerminal) {
            transport.providerTerminalObserved();
            transport.responseBodyCompleted();
            return stream;
        }
        return ProviderTransportTokenStream.track(stream, transport);
    }

    public static void completeTerminal(
            ProviderResponseCapture.Attempt attempt,
            ProviderTransportHandoff.DispatchEvidence evidence) {
        complete(attempt, evidence, true);
    }

    public static void completeBodyEof(
            ProviderResponseCapture.Attempt attempt,
            ProviderTransportHandoff.DispatchEvidence evidence) {
        complete(attempt, evidence, false);
    }

    private static void complete(
            ProviderResponseCapture.Attempt attempt,
            ProviderTransportHandoff.DispatchEvidence evidence,
            boolean providerTerminal) {
        ProviderTransportAttempt transport = Objects.requireNonNull(
                        attempt, "provider response attempt")
                .admitTransport(Objects.requireNonNull(evidence, "dispatch evidence"));
        try {
            transport.invokeSend(() -> null);
        } catch (Exception impossible) {
            throw new IllegalStateException("scripted provider send failed", impossible);
        }
        transport.responseHeadersObserved();
        transport.responseBodyOpened(() -> { });
        if (providerTerminal) transport.providerTerminalObserved();
        transport.responseBodyCompleted();
    }
}
