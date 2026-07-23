package dev.talos.engine.llamacpp;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/** Creates one direct, non-redirecting HTTP epoch for a managed qualification process. */
final class LlamaCppQualificationHttpClient {
    private LlamaCppQualificationHttpClient() {}

    static HttpClient create(String host, int port, Duration requestedTimeout) {
        if (!"127.0.0.1".equals(host) || port < 1 || port > 65_535) {
            throw new IllegalArgumentException(
                    "qualification HTTP epoch requires exact IPv4 loopback and port");
        }
        ProxySelector direct = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if (uri == null
                        || !"http".equalsIgnoreCase(uri.getScheme())
                        || !host.equals(uri.getHost())
                        || uri.getPort() != port
                        || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException(
                            "qualification HTTP escaped its exact process endpoint");
                }
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                // The request owner receives the typed transport failure. A direct
                // selector has no alternate route or mutable proxy state to update.
            }
        };
        Duration timeout = requestedTimeout == null
                || requestedTimeout.isZero()
                || requestedTimeout.isNegative()
                ? Duration.ofSeconds(3)
                : requestedTimeout.compareTo(Duration.ofSeconds(5)) > 0
                        ? Duration.ofSeconds(5)
                        : requestedTimeout;
        return HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(direct)
                .build();
    }
}
