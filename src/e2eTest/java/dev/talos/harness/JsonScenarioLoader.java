package dev.talos.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small resource-backed JSON loader for deterministic E2E scenarios. */
public final class JsonScenarioLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonScenarioLoader() {}

    public static LoadedScenario load(String scenarioResource) {
        try {
            JsonNode root = readJson(scenarioResource);
            String fixture = text(root, "fixture");
            Map<String, String> files = fixture.isBlank() ? Map.of() : loadFixture(fixture);

            ScenarioDefinition.Builder builder = ScenarioDefinition.named(text(root, "name"));
            files.forEach(builder::withFile);
            builder.withUserPrompt(text(root, "userPrompt"));
            builder.withApprovalPolicy(parsePolicy(text(root, "approvalPolicy")));

            String scriptedResponse = text(root, "scriptedResponse");
            if (!scriptedResponse.isBlank()) {
                builder.withScriptedResponse(scriptedResponse);
            }

            List<String> scriptedResponses = new ArrayList<>();
            JsonNode arr = root.path("scriptedResponses");
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    scriptedResponses.add(node.asText(""));
                }
            }

            return new LoadedScenario(
                    builder.build(),
                    text(root, "runner"),
                    scriptedResponses,
                    root
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to load scenario resource: " + scenarioResource, e);
        }
    }

    public static final class LoadedScenario {
        private final ScenarioDefinition definition;
        private final String runner;
        private final List<String> scriptedResponses;
        private final JsonNode raw;

        LoadedScenario(ScenarioDefinition definition, String runner,
                       List<String> scriptedResponses, JsonNode raw) {
            this.definition = definition;
            this.runner = runner == null ? "" : runner;
            this.scriptedResponses = List.copyOf(scriptedResponses);
            this.raw = raw;
        }

        public ScenarioDefinition definition() { return definition; }
        public String runner() { return runner; }
        public List<String> scriptedResponses() { return scriptedResponses; }
        public JsonNode raw() { return raw; }
    }

    private static JsonNode readJson(String resource) throws Exception {
        var in = JsonScenarioLoader.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) throw new IllegalArgumentException("Missing resource: " + resource);
        try (in) {
            return MAPPER.readTree(in);
        }
    }

    private static Map<String, String> loadFixture(String fixtureName) throws Exception {
        var url = JsonScenarioLoader.class.getClassLoader().getResource("fixtures/" + fixtureName);
        if (url == null) throw new IllegalArgumentException("Missing fixture: " + fixtureName);
        URI uri = url.toURI();
        Path root = Path.of(uri);
        Map<String, String> files = new LinkedHashMap<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String rel = root.relativize(path).toString().replace('\\', '/');
                    files.put(rel, Files.readString(path));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return files;
    }

    private static ScenarioApprovalPolicy parsePolicy(String value) {
        if (value == null || value.isBlank()) return ScenarioApprovalPolicy.APPROVE_ALL;
        return ScenarioApprovalPolicy.valueOf(value);
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.path(field);
        return n.isMissingNode() ? "" : n.asText("");
    }
}
