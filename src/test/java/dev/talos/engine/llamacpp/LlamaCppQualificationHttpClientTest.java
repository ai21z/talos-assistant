package dev.talos.engine.llamacpp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppQualificationHttpClientTest {
    private static final Object PROXY_DEFAULT_LOCK = new Object();

    @Test
    void qualificationEpochIsDirectIpv4OnlyAndDoesNotFollowRedirects() throws Exception {
        AtomicInteger targetCalls = new AtomicInteger();
        AtomicInteger redirectedCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            byte[] body = "target".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            redirectedCalls.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add(
                    "Location",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpClient client = LlamaCppQualificationHttpClient.create(
                    "127.0.0.1", server.getAddress().getPort(), Duration.ofSeconds(1));
            URI target = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/target");
            List<Proxy> route = client.proxy().orElseThrow().select(target);
            assertEquals(List.of(Proxy.NO_PROXY), route);
            assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
            assertEquals(HttpClient.Version.HTTP_1_1, client.version());

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(target).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("target", response.body());
            assertEquals(1, targetCalls.get());

            HttpResponse<Void> redirect = client.send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(302, redirect.statusCode());
            assertEquals(0, redirectedCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void hostileJvmDefaultProxyCannotBecomeTheQualificationProvider() throws Exception {
        AtomicInteger targetCalls = new AtomicInteger();
        AtomicInteger proxyCalls = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/health", exchange -> {
            targetCalls.incrementAndGet();
            byte[] body = "provider-a".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            proxyCalls.incrementAndGet();
            byte[] body = "provider-b".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        target.start();
        proxy.start();
        synchronized (PROXY_DEFAULT_LOCK) {
            ProxySelector previous = ProxySelector.getDefault();
            try {
                ProxySelector.setDefault(ProxySelector.of(proxy.getAddress()));
                HttpClient client = LlamaCppQualificationHttpClient.create(
                        "127.0.0.1", target.getAddress().getPort(), Duration.ofSeconds(1));
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + target.getAddress().getPort() + "/health"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals("provider-a", response.body());
                assertEquals(1, targetCalls.get());
                assertEquals(0, proxyCalls.get());
            } finally {
                ProxySelector.setDefault(previous);
                target.stop(0);
                proxy.stop(0);
            }
        }
    }

    @Test
    void qualificationRouteRejectsEveryNonExactEndpointAuthority() {
        HttpClient client = LlamaCppQualificationHttpClient.create(
                "127.0.0.1", 8080, Duration.ofSeconds(1));
        var selector = client.proxy().orElseThrow();

        assertThrows(IllegalArgumentException.class,
                () -> selector.select(URI.create("http://localhost:8080/health")));
        assertThrows(IllegalArgumentException.class,
                () -> selector.select(URI.create("http://[::1]:8080/health")));
        assertThrows(IllegalArgumentException.class,
                () -> selector.select(URI.create("https://127.0.0.1:8080/health")));
        assertThrows(IllegalArgumentException.class,
                () -> selector.select(URI.create("http://127.0.0.2:8080/health")));
        assertThrows(IllegalArgumentException.class,
                () -> selector.select(URI.create("http://127.0.0.1:8081/health")));
    }

    @Test
    void qualificationConnectTimeoutIsPositiveAndCapped() {
        Duration shortTimeout = LlamaCppQualificationHttpClient.create(
                        "127.0.0.1", 8080, Duration.ofMillis(50))
                .connectTimeout().orElseThrow();
        Duration defaultTimeout = LlamaCppQualificationHttpClient.create(
                        "127.0.0.1", 8080, Duration.ZERO)
                .connectTimeout().orElseThrow();
        Duration cappedTimeout = LlamaCppQualificationHttpClient.create(
                        "127.0.0.1", 8080, Duration.ofMinutes(1))
                .connectTimeout().orElseThrow();

        assertTrue(shortTimeout.isPositive());
        assertEquals(Duration.ofSeconds(3), defaultTimeout);
        assertEquals(Duration.ofSeconds(5), cappedTimeout);
    }
}
