package dev.talos.engine.llamacpp;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** A child-owned HTTP fixture. No model weights are loaded and no inference is performed. */
public final class ManagedModelServerFixture {
    private ManagedModelServerFixture() {}

    public static void main(String[] args) throws Exception {
        String alias = args[2];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/props", exchange -> {
            String body = "{\"model_path\":" + quoted(args[1])
                    + ",\"default_generation_settings\":{\"n_ctx\":8192},\"chat_template\":\"fixture\","
                    + "\"chat_template_caps\":{\"supports_tools\":false,\"supports_tool_calls\":false},"
                    + "\"modalities\":{\"vision\":false},\"build_info\":\"synthetic command fixture\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readNBytes(65537), StandardCharsets.UTF_8);
            if (request.length() > 65536 || !request.matches("(?s).*\"model\"\\s*:\\s*" + quoted(alias) + ".*")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            String text = "fixture answer from " + alias;
            boolean streaming = request.matches("(?s).*\"stream\"\\s*:\\s*true.*");
            String body = streaming
                    ? "data: {\"choices\":[{\"delta\":{\"content\":" + quoted(text)
                        + "},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + quoted(text)
                        + "},\"finish_reason\":\"stop\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
