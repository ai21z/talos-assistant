package dev.loqj.core.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.spi.LanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public class LlmClient implements LanguageModel {
    private static final Logger LOG = LoggerFactory.getLogger(LlmClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String host;
    private String model;

    public LlmClient(Config cfg) {
        Map<String,Object> oll = CfgUtil.map(cfg.data.get("ollama"));
        this.host  = Objects.toString(oll.getOrDefault("host", "http://127.0.0.1:11434"));
        Map<String,Object> models = CfgUtil.map(oll.get("models"));
        this.model = Objects.toString(models.getOrDefault("coder", "qwen3:8b"));
    }

    public void setModel(String m) { if (m != null && !m.isBlank()) this.model = m; }
    public String getModel() { return model; }

    /** Non-streaming: request strict JSON and parse {"answer": "..."}; fallback to plain text content. */
    @Override
    public String chat(String system, String question, List<Map<String,String>> snippets) {
        try {
            String user = buildUserPromptJson(question, snippets);
            Map<String,Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", List.of(
                    Map.of("role","system", "content", system),
                    Map.of("role","user",   "content", user)
            ));
            payload.put("format", "json");
            payload.put("stream", false);
            payload.put("options", Map.of("temperature", 0.1));

            String body = mapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/api/chat"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                LOG.warn("Ollama chat non-2xx: {} {}", resp.statusCode(), truncate(resp.body(), 200));
                return "Model error (" + resp.statusCode() + ").";
            }

            Map<String,Object> root = mapper.readValue(resp.body(), new TypeReference<>() {});
            Map<String,Object> msg  = CfgUtil.map(root.get("message"));
            String content = Objects.toString(msg.getOrDefault("content", ""));
            String answer = parseAnswerJson(content);
            if (answer == null || answer.isBlank()) {
                answer = stripThink(content).trim();
                if (answer.isBlank()) answer = "I'm not sure based on the provided context.";
            }
            return answer;
        } catch (Exception e) {
            LOG.warn("chat failed", e);
            return "Chat failed: " + e.getMessage();
        }
    }

    /** Streaming: request plain text (no JSON) so we can print tokens directly. */
    public String chatStream(String system, String question, List<Map<String,String>> snippets, java.util.function.Consumer<String> onChunk) {
        StringBuilder out = new StringBuilder();
        boolean[] inThink = { false }; // state across streamed chunks

        try {
            String user = buildUserPromptStreaming(question, snippets);
            Map<String,Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", List.of(
                    Map.of("role","system", "content", system),
                    Map.of("role","user",   "content", user)
            ));
            payload.put("stream", true); // no format=json for streaming
            payload.put("options", Map.of("temperature", 0.1));

            String body = mapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                LOG.warn("Ollama chat(stream) non-2xx: {}", resp.statusCode());
                return "";
            }

            try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    try {
                        Map<String,Object> evt = mapper.readValue(line, new TypeReference<>() {});
                        if (Boolean.TRUE.equals(evt.get("done"))) break;
                        Map<String,Object> msg = CfgUtil.map(evt.get("message"));
                        String content = Objects.toString(msg.getOrDefault("content",""));
                        if (!content.isEmpty()) {
                            String delta = filterThinkStreaming(content, inThink);
                            if (!delta.isEmpty()) {
                                onChunk.accept(delta);
                                out.append(delta);
                            }
                        }
                    } catch (Exception parse) {
                        LOG.debug("stream parse skip: {}", truncate(line, 120));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("chatStream failed", e);
            return "";
        }
        return out.toString();
    }

    /** Remove <think>…</think> across chunk boundaries. */
    private static String filterThinkStreaming(String s, boolean[] inThink) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            if (!inThink[0]) {
                int open = indexOfIgnoreCase(s, "<think>", i);
                if (open < 0) {
                    out.append(s, i, s.length());
                    break;
                }
                out.append(s, i, open);
                i = open + 7; // skip "<think>"
                inThink[0] = true;
            } else {
                int close = indexOfIgnoreCase(s, "</think>", i);
                if (close < 0) {
                    // still inside think; drop rest of chunk
                    i = s.length();
                } else {
                    // exit think block
                    i = close + 8; // skip "</think>"
                    inThink[0] = false;
                }
            }
        }
        return out.toString();
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        int n = needle.length();
        for (int i = Math.max(0, from); i + n <= s.length(); i++) {
            if (s.regionMatches(true, i, needle, 0, n)) return i;
        }
        return -1;
    }

    /** Plain, non-JSON request (non-stream). Used by memory prompts to avoid JSON schema conflicts. */
    public String chatPlain(String system, String userContent) {
        try {
            Map<String,Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", List.of(
                    Map.of("role","system", "content", system),
                    Map.of("role","user",   "content", userContent)
            ));
            payload.put("stream", false);
            payload.put("options", Map.of("temperature", 0.1));

            String body = mapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/api/chat"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                LOG.warn("Ollama chatPlain non-2xx: {} {}", resp.statusCode(), truncate(resp.body(), 200));
                return "Model error (" + resp.statusCode() + ").";
            }
            Map<String,Object> root = mapper.readValue(resp.body(), new TypeReference<>() {});
            Map<String,Object> msg  = CfgUtil.map(root.get("message"));
            String content = Objects.toString(msg.getOrDefault("content", ""));
            return stripThink(content).trim();
        } catch (Exception e) {
            LOG.warn("chatPlain failed", e);
            return "Chat failed: " + e.getMessage();
        }
    }

    // ---------- Prompt builders ----------

    private static String buildUserPromptJson(String question, List<Map<String,String>> snippets) {
        StringBuilder sb = new StringBuilder();
        sb.append("Question:\n").append(question).append("\n\n");
        if (snippets != null && !snippets.isEmpty()) {
            sb.append("Context (file excerpts):\n");
            for (var s : snippets) {
                String path = Objects.toString(s.getOrDefault("path","(unknown)"));
                String text = Objects.toString(s.getOrDefault("text",""));
                sb.append("File: ").append(path).append("\n---\n");
                if (text.length() > 1200) text = text.substring(0, 1200);
                sb.append(text).append("\n---\n\n");
            }
        }
        sb.append("Return ONLY JSON with a single key: {\"answer\": \"...\"}. No code fences. No thoughts.");
        return sb.toString();
    }

    private static String buildUserPromptStreaming(String question, List<Map<String,String>> snippets) {
        StringBuilder sb = new StringBuilder();
        sb.append("Question:\n").append(question).append("\n\n");
        if (snippets != null && !snippets.isEmpty()) {
            sb.append("Context (file excerpts):\n");
            for (var s : snippets) {
                String path = Objects.toString(s.getOrDefault("path","(unknown)"));
                String text = Objects.toString(s.getOrDefault("text",""));
                sb.append("File: ").append(path).append("\n---\n");
                if (text.length() > 1200) text = text.substring(0, 1200);
                sb.append(text).append("\n---\n\n");
            }
        }
        sb.append("Answer directly in plain text. No JSON. No code fences. No chain-of-thought.");
        return sb.toString();
    }

    // ---------- Helpers ----------

    private String parseAnswerJson(String content) {
        try {
            String c = content.strip();
            if (c.startsWith("```")) {
                int first = c.indexOf('{');
                int last  = c.lastIndexOf('}');
                if (first >= 0 && last > first) c = c.substring(first, last + 1);
            }
            Map<String,Object> obj = mapper.readValue(c, new TypeReference<>() {});
            Object ans = obj.get("answer");
            return (ans == null) ? null : ans.toString();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String stripThink(String s) {
        return s.replaceAll("(?is)<think>.*?</think>", "");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return (s.length() <= max) ? s : s.substring(0, max) + "…";
    }
}
