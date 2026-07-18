package dev.talos.qualification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class QualificationJson {
    private static final int FIRST_LEGACY_PROTOCOL_VERSION = 1;
    private static final int LAST_LEGACY_SCENARIO_SCHEMA_VERSION = 5;
    private static final List<String> CAUSAL_V2_FIELDS = List.of(
            "answerOrigin",
            "responseOwnerContract",
            "causalOwner",
            "causalReasonCode",
            "comparativeScoreEligible",
            "causalLedger");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private QualificationJson() {}

    static <T> T read(Path path, Class<T> type) throws IOException {
        return read(Files.readAllBytes(path), type);
    }

    static <T> T read(byte[] bytes, Class<T> type) throws IOException {
        if (type == QualificationProtocol.ScenarioBank.class) {
            JsonNode tree = MAPPER.readTree(bytes);
            adaptLegacyScenarioOwners(tree);
            return MAPPER.treeToValue(tree, type);
        }
        if (type == QualificationRecords.RunResult.class) {
            JsonNode tree = MAPPER.readTree(bytes);
            adaptLegacyRunResult(tree);
            return MAPPER.treeToValue(tree, type);
        }
        return MAPPER.readValue(bytes, type);
    }

    static JsonNode readTree(Path path) throws IOException {
        return MAPPER.readTree(path.toFile());
    }

    static JsonNode readTree(byte[] bytes) throws IOException {
        return MAPPER.readTree(bytes);
    }

    static QualificationProtocol.Scenario readScenario(Path path, int protocolVersion)
            throws IOException {
        JsonNode tree = MAPPER.readTree(Files.readAllBytes(path));
        if (protocolVersion >= FIRST_LEGACY_PROTOCOL_VERSION
                && protocolVersion <= LAST_LEGACY_SCENARIO_SCHEMA_VERSION) {
            if (tree instanceof ObjectNode scenario) {
                adaptLegacyScenarioOwner(scenario);
            }
        } else if (protocolVersion != QualificationProtocol.VERSION) {
            throw new IllegalArgumentException(
                    "unsupported qualification protocol version: " + protocolVersion);
        }
        return MAPPER.treeToValue(tree, QualificationProtocol.Scenario.class);
    }

    static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    static String requiredText(JsonNode value, String label) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(label + " must be a JSON string");
        }
        return value.textValue();
    }

    static String optionalText(JsonNode value, String label) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        return requiredText(value, label);
    }

    static int requiredInt(JsonNode value, String label) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(label + " must be an exact JSON integer");
        }
        return value.intValue();
    }

    static long requiredLong(JsonNode value, String label) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(label + " must be an exact JSON integer");
        }
        return value.longValue();
    }

    static boolean requiredBoolean(JsonNode value, String label) {
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(label + " must be a JSON boolean");
        }
        return value.booleanValue();
    }

    static String write(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize qualification JSON", e);
        }
    }

    static void write(Path path, Object value) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, write(value), StandardCharsets.UTF_8);
    }

    static byte[] bytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    static int requiredRunSchemaVersion(int protocolVersion) {
        if (protocolVersion >= FIRST_LEGACY_PROTOCOL_VERSION
                && protocolVersion <= LAST_LEGACY_SCENARIO_SCHEMA_VERSION) {
            return QualificationRecords.LEGACY_RUN_SCHEMA_VERSION;
        }
        if (protocolVersion == QualificationProtocol.VERSION) {
            return QualificationRecords.RUN_SCHEMA_VERSION;
        }
        throw new IllegalArgumentException(
                "unsupported qualification protocol version: " + protocolVersion);
    }

    static void requireRunSchemaForProtocol(int protocolVersion, int runSchemaVersion) {
        int required = requiredRunSchemaVersion(protocolVersion);
        if (runSchemaVersion != required) {
            throw new IllegalArgumentException(
                    "qualification protocol v" + protocolVersion
                            + " requires run schema v" + required
                            + ", found v" + runSchemaVersion);
        }
    }

    private static void adaptLegacyRunResult(JsonNode tree) {
        if (!(tree instanceof ObjectNode object)
                || object.path("schemaVersion").asInt(-1)
                != QualificationRecords.LEGACY_RUN_SCHEMA_VERSION
                || !QualificationRecords.RUN_SCHEMA.equals(object.path("schemaName").asText(""))) {
            return;
        }
        if (CAUSAL_V2_FIELDS.stream().anyMatch(object::has)) {
            throw new IllegalArgumentException(
                    "legacy run schema v1 must not declare protocol-v6 causal fields");
        }
        if (!object.has("answerOrigin")) {
            object.put("answerOrigin", QualificationRecords.AnswerOrigin.NONE.name());
        }
        if (!object.has("responseOwnerContract")) {
            object.put("responseOwnerContract",
                    QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED.name());
        }
        if (!object.has("causalOwner")) {
            object.put("causalOwner", QualificationRecords.CausalOwner.UNKNOWN.name());
        }
        if (!object.has("causalReasonCode")) {
            object.put("causalReasonCode", "LEGACY_CAUSAL_EVIDENCE_UNAVAILABLE");
        }
        if (!object.has("comparativeScoreEligible")) {
            object.put("comparativeScoreEligible", false);
        }
        if (!object.has("causalLedger")) {
            object.putArray("causalLedger");
        }
    }

    private static void adaptLegacyScenarioOwners(JsonNode tree) {
        if (!(tree instanceof ObjectNode object)) {
            return;
        }
        int version = object.path("schemaVersion").asInt(-1);
        if (version < 1 || version > LAST_LEGACY_SCENARIO_SCHEMA_VERSION) {
            return;
        }
        JsonNode scenarios = object.path("scenarios");
        if (!scenarios.isArray()) {
            return;
        }
        for (JsonNode scenario : scenarios) {
            if (!(scenario instanceof ObjectNode scenarioObject)) {
                continue;
            }
            adaptLegacyScenarioOwner(scenarioObject);
        }
    }

    private static void adaptLegacyScenarioOwner(ObjectNode scenarioObject) {
        if (scenarioObject.has("responseOwnerContract")) {
            throw new IllegalArgumentException(
                    "legacy scenario schema must not declare responseOwnerContract");
        }
        String approval = scenarioObject.path("approvalBehavior").asText("");
        QualificationRecords.ResponseOwnerContract owner =
                QualificationProtocol.ApprovalBehavior.INTERRUPT_BEFORE_APPROVAL.name().equals(approval)
                        ? QualificationRecords.ResponseOwnerContract.OPERATOR_OWNED
                        : QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED;
        scenarioObject.put("responseOwnerContract", owner.name());
    }

}
