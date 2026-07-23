package dev.talos.qualification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import dev.talos.core.security.NoFollowPathPolicy;

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
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, coercion -> {
                coercion.setCoercion(CoercionInputShape.Array, CoercionAction.Fail);
                coercion.setCoercion(CoercionInputShape.Object, CoercionAction.Fail);
                coercion.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                coercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                coercion.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                coercion.setCoercion(CoercionInputShape.Binary, CoercionAction.Fail);
                coercion.setCoercion(CoercionInputShape.EmptyArray, CoercionAction.Fail);
                coercion.setCoercion(CoercionInputShape.EmptyObject, CoercionAction.Fail);
            })
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private QualificationJson() {}

    static <T> T read(Path path, Class<T> type) throws IOException {
        return read(NoFollowPathPolicy.readAllBytes(path), type);
    }

    static <T> T read(byte[] bytes, Class<T> type) throws IOException {
        if (type == QualificationProtocol.ScenarioBank.class) {
            JsonNode tree = MAPPER.readTree(bytes);
            adaptLegacyScenarioOwners(tree);
            return bindStrict(tree, type);
        }
        if (type == QualificationRecords.RunResult.class) {
            JsonNode tree = MAPPER.readTree(bytes);
            adaptLegacyRunResult(tree);
            return bindStrict(tree, type);
        }
        if (type == QualificationRecords.EngineFacts.class
                || type == QualificationRecords.CalibrationRules.class) {
            JsonNode tree = MAPPER.readTree(bytes);
            adaptLegacyLaunchEnvironment(tree, type);
            return bindStrict(tree, type);
        }
        return MAPPER.readValue(bytes, type);
    }

    static JsonNode readTree(Path path) throws IOException {
        return readTree(NoFollowPathPolicy.readAllBytes(path));
    }

    static JsonNode readTree(byte[] bytes) throws IOException {
        return MAPPER.readTree(bytes);
    }

    static QualificationProtocol.Scenario readScenario(Path path, int protocolVersion)
            throws IOException {
        return readScenario(NoFollowPathPolicy.readAllBytes(path), protocolVersion);
    }

    static QualificationProtocol.Scenario readScenario(byte[] bytes, int protocolVersion)
            throws IOException {
        JsonNode tree = MAPPER.readTree(bytes);
        if (protocolVersion >= FIRST_LEGACY_PROTOCOL_VERSION
                && protocolVersion <= LAST_LEGACY_SCENARIO_SCHEMA_VERSION) {
            if (tree instanceof ObjectNode scenario) {
                adaptLegacyScenarioOwner(scenario);
            }
        } else if (protocolVersion != QualificationProtocol.VERSION) {
            throw new IllegalArgumentException(
                    "unsupported qualification protocol version: " + protocolVersion);
        }
        return bindStrict(tree, QualificationProtocol.Scenario.class);
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

    static int requiredInt(JsonNode value, int minimum, int maximum, String label) {
        int parsed = requiredInt(value, label);
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    label + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    static long requiredLong(JsonNode value, String label) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(label + " must be an exact JSON integer");
        }
        return value.longValue();
    }

    static long requiredLong(JsonNode value, long minimum, long maximum, String label) {
        long parsed = requiredLong(value, label);
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    label + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    static boolean requiredBoolean(JsonNode value, String label) {
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(label + " must be a JSON boolean");
        }
        return value.booleanValue();
    }

    static ObjectNode requiredObject(JsonNode value, String label) {
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
        return object;
    }

    static ArrayNode requiredArray(JsonNode value, String label) {
        if (!(value instanceof ArrayNode array)) {
            throw new IllegalArgumentException(label + " must be a JSON array");
        }
        return array;
    }

    static <E extends Enum<E>> E requiredEnum(
            JsonNode value,
            Class<E> enumType,
            String label) {
        String name = requiredText(value, label);
        try {
            return Enum.valueOf(enumType, name);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(label + " has an unsupported value", invalid);
        }
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
                || exactIntOrSentinel(object.get("schemaVersion"))
                != QualificationRecords.LEGACY_RUN_SCHEMA_VERSION
                || !QualificationRecords.RUN_SCHEMA.equals(
                        exactTextOrEmpty(object.get("schemaName")))) {
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

    private static void adaptLegacyLaunchEnvironment(JsonNode tree, Class<?> type) {
        if (!(tree instanceof ObjectNode object)) return;
        int schemaVersion = exactIntOrSentinel(object.get("schemaVersion"));
        if (schemaVersion != QualificationRecords.SCHEMA_VERSION) return;
        String expectedSchema = type == QualificationRecords.EngineFacts.class
                ? QualificationRecords.ENGINE_SCHEMA
                : QualificationRecords.CALIBRATION_SCHEMA;
        if (!expectedSchema.equals(exactTextOrEmpty(object.get("schemaName")))) return;
        if (object.has("launchEnvironmentSha256")) {
            throw new IllegalArgumentException(
                    "legacy engine/calibration schema must not declare launch environment identity");
        }
        object.put("launchEnvironmentSha256", "");
        if (object.has("launchEnvironmentContract")) {
            throw new IllegalArgumentException(
                    "legacy engine/calibration schema must not declare a launch environment contract");
        }
        object.put("launchEnvironmentContract", "");
        if (object.has("endpointObserverKind")
                || object.has("endpointObserverIdentitySha256")) {
            throw new IllegalArgumentException(
                    "legacy engine/calibration schema must not declare endpoint observer authority");
        }
        object.put("endpointObserverKind", "");
        object.put("endpointObserverIdentitySha256", "");
    }

    private static void adaptLegacyScenarioOwners(JsonNode tree) {
        if (!(tree instanceof ObjectNode object)) {
            return;
        }
        int version = exactIntOrSentinel(object.get("schemaVersion"));
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
        String approval = exactTextOrEmpty(scenarioObject.get("approvalBehavior"));
        QualificationRecords.ResponseOwnerContract owner =
                QualificationProtocol.ApprovalBehavior.INTERRUPT_BEFORE_APPROVAL.name().equals(approval)
                        ? QualificationRecords.ResponseOwnerContract.OPERATOR_OWNED
                        : QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED;
        scenarioObject.put("responseOwnerContract", owner.name());
    }

    private static int exactIntOrSentinel(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                ? value.intValue()
                : Integer.MIN_VALUE;
    }

    private static String exactTextOrEmpty(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static <T> T bindStrict(JsonNode tree, Class<T> type) throws IOException {
        // Legacy adaptation returns to the configured byte parser so every authority
        // record is bound through one coercion policy.
        return MAPPER.readValue(MAPPER.writeValueAsBytes(tree), type);
    }

}
