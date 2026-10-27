package dev.talos.testsupport;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Test client whose synchronous transport boundary is interrupted deterministically. */
public final class InterruptingHttpClient extends HttpClient {
    private final InterruptedException interruption;
    private final RuntimeException wrappedFailure;
    private final AtomicInteger sendCalls = new AtomicInteger();

    public InterruptingHttpClient(String message) {
        this(message, false);
    }

    private InterruptingHttpClient(String message, boolean wrapped) {
        this.interruption = new InterruptedException(message);
        this.wrappedFailure = wrapped ? new RuntimeException(interruption) : null;
    }

    public static InterruptingHttpClient wrapped(String message) {
        return new InterruptingHttpClient(message, true);
    }

    public InterruptedException interruption() {
        return interruption;
    }

    public int sendCalls() {
        return sendCalls.get();
    }

    public RuntimeException wrappedFailure() {
        return wrappedFailure;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        return null;
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler
    ) throws IOException, InterruptedException {
        sendCalls.incrementAndGet();
        if (wrappedFailure != null) throw wrappedFailure;
        throw interruption;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler
    ) {
        throw new UnsupportedOperationException("not used");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler
    ) {
        throw new UnsupportedOperationException("not used");
    }
}
