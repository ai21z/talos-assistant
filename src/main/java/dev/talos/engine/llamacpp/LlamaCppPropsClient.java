package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.HostLocalityPolicy;
import dev.talos.spi.types.ControlFlowFailure;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded read-only client for the pinned llama.cpp {@code GET /props} contract. */
public final class LlamaCppPropsClient {
    public enum State {
        AVAILABLE,
        UNAVAILABLE,
        REFUSED
    }

    public record Props(
            String modelPath,
            String chatTemplate,
            boolean supportsTools,
            boolean supportsToolCalls,
            boolean supportsParallelToolCalls,
            boolean vision,
            int contextTokens,
            String buildInfo,
            String responseSha256) {

        public Props {
            modelPath = clean(modelPath);
            chatTemplate = Objects.requireNonNullElse(chatTemplate, "");
            contextTokens = Math.max(0, contextTokens);
            buildInfo = clean(buildInfo);
            responseSha256 = clean(responseSha256).toLowerCase(java.util.Locale.ROOT);
        }

        public boolean supportsNativeTools() {
            return supportsTools && supportsToolCalls;
        }
    }

    public record Result(State state, Props props, String reason) {
        public Result {
            state = state == null ? State.UNAVAILABLE : state;
            props = props == null
                    ? new Props("", "", false, false, false, false, 0, "", "")
                    : props;
            reason = clean(reason);
        }

        static Result available(Props props) {
            return new Result(State.AVAILABLE, props, "");
        }

        static Result unavailable(String reason) {
            return new Result(State.UNAVAILABLE, null, reason);
        }

        static Result refused(String reason) {
            return new Result(State.REFUSED, null, reason);
        }
    }

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    private static final int DEFAULT_BODY_LIMIT = 128 * 1024;

    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration timeout;
    private final int bodyLimit;

    public LlamaCppPropsClient() {
        this(HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
                new ObjectMapper(), DEFAULT_TIMEOUT, DEFAULT_BODY_LIMIT);
    }

    LlamaCppPropsClient(Duration timeout, int bodyLimit) {
        this(HttpClient.newBuilder()
                        .connectTimeout(normalizeTimeout(timeout))
                        .build(),
                new ObjectMapper(), timeout, bodyLimit);
    }

    LlamaCppPropsClient(HttpClient http, ObjectMapper json, Duration timeout, int bodyLimit) {
        this.http = http == null ? HttpClient.newHttpClient() : http;
        this.json = (json == null ? new ObjectMapper() : json.copy())
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.timeout = normalizeTimeout(timeout);
        this.bodyLimit = Math.max(256, bodyLimit);
    }

    public Result fetch(URI baseUri) {
        URI safeBase;
        try {
            safeBase = Objects.requireNonNull(baseUri, "base URI is required");
            if (!HostLocalityPolicy.isLoopback(safeBase.toString())) {
                return Result.refused("llama.cpp /props is restricted to a loopback host");
            }
        } catch (RuntimeException error) {
            return Result.refused("invalid llama.cpp /props endpoint");
        }

        URI endpoint;
        try {
            endpoint = propsUri(safeBase);
        } catch (RuntimeException error) {
            return Result.refused("invalid llama.cpp /props endpoint");
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                closeQuietly(response.body());
                return Result.unavailable("llama.cpp /props returned HTTP " + response.statusCode());
            }
            byte[] body;
            try (InputStream in = response.body()) {
                body = readBodyBounded(in);
            }
            if (body == null) {
                return Result.refused("llama.cpp /props response exceeded the size limit");
            }
            return parse(body);
        } catch (java.net.http.HttpTimeoutException error) {
            return Result.unavailable("llama.cpp /props timed out");
        } catch (InterruptedException error) {
            throw ControlFlowFailure.propagate(error);
        } catch (Exception error) {
            if (ControlFlowFailure.classify(error) != ControlFlowFailure.Kind.NONE) {
                throw ControlFlowFailure.propagate(error);
            }
            return Result.unavailable("llama.cpp /props could not be read");
        }
    }

    private byte[] readBodyBounded(InputStream in) throws Exception {
        FutureTask<byte[]> read = new FutureTask<>(() -> readBounded(in, bodyLimit));
        Thread.ofVirtual().name("llama-cpp-props-body-read").start(read);
        try {
            return read.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            read.cancel(true);
            closeQuietly(in);
            throw new java.net.http.HttpTimeoutException(
                    "llama.cpp /props response body timed out");
        } catch (InterruptedException error) {
            read.cancel(true);
            closeQuietly(in);
            throw error;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException(cause);
        }
    }

    private Result parse(byte[] body) {
        try {
            JsonParser parser = json.getFactory().createParser(body);
            JsonNode root = json.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                return Result.refused("llama.cpp /props returned invalid JSON");
            }
            JsonNode settings = requiredObject(root, "default_generation_settings");
            int context = requiredPositiveInt(settings, "n_ctx");
            String modelPath = requiredText(root, "model_path", false);
            String chatTemplate = requiredText(root, "chat_template", true);
            JsonNode caps = requiredObject(root, "chat_template_caps");
            boolean supportsTools = requiredBoolean(caps, "supports_tools");
            boolean supportsToolCalls = requiredBoolean(caps, "supports_tool_calls");
            boolean supportsParallel = optionalBoolean(caps, "supports_parallel_tool_calls", false);
            JsonNode modalities = requiredObject(root, "modalities");
            boolean vision = requiredBoolean(modalities, "vision");
            String buildInfo = requiredText(root, "build_info", false);
            return Result.available(new Props(
                    modelPath,
                    chatTemplate,
                    supportsTools,
                    supportsToolCalls,
                    supportsParallel,
                    vision,
                    context,
                    buildInfo,
                    sha256(body)));
        } catch (IllegalArgumentException error) {
            return Result.refused(error.getMessage());
        } catch (Exception error) {
            return Result.refused("llama.cpp /props returned invalid JSON");
        }
    }

    private static URI propsUri(URI base) {
        String value = base.toString();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return URI.create(value + "/props");
    }

    private static byte[] readBounded(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        for (int read; (read = in.read(buffer)) >= 0;) {
            total += read;
            if (total > limit) return null;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static JsonNode requiredObject(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isObject()) {
            throw missing(name);
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String name, boolean allowBlank) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isTextual() || (!allowBlank && value.textValue().isBlank())) {
            throw missing(name);
        }
        return value.textValue();
    }

    private static boolean requiredBoolean(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isBoolean()) throw missing(name);
        return value.booleanValue();
    }

    private static boolean optionalBoolean(JsonNode parent, String name, boolean fallback) {
        JsonNode value = parent.get(name);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw missing(name);
        return value.booleanValue();
    }

    LlamaCppPropsClient(HttpClient http) {
        this(http, new ObjectMapper(), DEFAULT_TIMEOUT, DEFAULT_BODY_LIMIT);
    }

    private static int requiredPositiveInt(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt() || value.intValue() <= 0) {
            throw missing(name);
        }
        return value.intValue();
    }

    private static IllegalArgumentException missing(String name) {
        return new IllegalArgumentException("llama.cpp /props is missing required field " + name);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Duration normalizeTimeout(Duration timeout) {
        Duration safe = timeout == null || timeout.isNegative() || timeout.isZero()
                ? DEFAULT_TIMEOUT
                : timeout;
        return safe.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : safe;
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Exception ignored) {
        }
    }

    private static String clean(String value) {
        return Objects.toString(value, "").trim();
    }
}
