package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.security.NoFollowPathPolicy;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContractResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Writes immutable finalizer results using the same requirement evaluator as packet validation. */
final class QualificationRequirementFinalizer {
    static final String OUTPUT_FILE = "mechanical-requirements.json";
    private static final String SCHEMA = "talos.modelQualification.mechanicalRequirements";

    private QualificationRequirementFinalizer() {}

    record Requirement(String id, String type, boolean passed) {}

    record Document(
            String schemaName,
            int schemaVersion,
            int protocolVersion,
            String scenarioBankSha256,
            String scenarioId,
            String requestObligationSha256,
            JsonNode requestObligation,
            List<Requirement> requirements) {
        Document {
            if (!SCHEMA.equals(schemaName)) {
                throw new IllegalArgumentException(
                        "mechanical requirements schema name is invalid");
            }
            if (requestObligationSha256 == null) {
                throw new IllegalArgumentException(
                        "request-obligation identity is required");
            }
            if (!requestObligationSha256.isEmpty()
                    && !requestObligationSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "request-obligation identity must be lowercase SHA-256");
            }
            if (requestObligation == null || !requestObligation.isObject()) {
                throw new IllegalArgumentException(
                        "request-obligation result evidence must be a JSON object");
            }
            requestObligation = requestObligation.deepCopy();
            requirements = List.copyOf(requirements);
            if (protocolVersion >= 1 && protocolVersion <= 5) {
                if (schemaVersion != 1
                        || !requestObligationSha256.isEmpty()
                        || !requestObligation.isEmpty()) {
                    throw new IllegalArgumentException(
                        "legacy mechanical requirements cannot claim a request obligation");
                }
            } else if (protocolVersion == 6) {
                if (schemaVersion == 1) {
                    if (!requestObligationSha256.isEmpty()
                            || !requestObligation.isEmpty()) {
                        throw new IllegalArgumentException(
                                "legacy protocol-v6 mechanical requirements "
                                        + "cannot claim a request obligation");
                    }
                } else {
                    if (schemaVersion != 2) {
                        throw new IllegalArgumentException(
                                "protocol-v6 mechanical requirements must use schema version 2");
                    }
                    boolean obligationRequired = requirements.stream()
                            .anyMatch(requirement ->
                                    "REQUEST_OBLIGATION".equals(requirement.type()));
                    if (obligationRequired != !requestObligationSha256.isEmpty()
                            || obligationRequired != !requestObligation.isEmpty()) {
                        throw new IllegalArgumentException(
                                "protocol-v6 migrated scenarios require their "
                                        + "request-obligation identity");
                    }
                    if (obligationRequired) {
                        validateRequestObligationProjection(
                                requestObligation,
                                requestObligationSha256);
                    }
                }
            } else {
                throw new IllegalArgumentException(
                        "unsupported mechanical requirements protocol version");
            }
        }

        @Override
        public JsonNode requestObligation() {
            return requestObligation.deepCopy();
        }

        List<QualificationRecords.RequirementResult> results() {
            return requirements.stream()
                    .map(requirement -> new QualificationRecords.RequirementResult(
                            requirement.id(), requirement.passed()))
                    .toList();
        }
    }

    private static final Set<String> REQUEST_OBLIGATION_PROJECTION_FIELDS = Set.of(
            "schemaVersion",
            "derivationStatus",
            "responseOwner",
            "actionKind",
            "mutationScope",
            "requiredMutationTargets",
            "requiredSourceTargets",
            "forbiddenTargets",
            "preservationConstraints",
            "outputShape",
            "verificationPostconditions",
            "canonicalSha256");
    private static final Set<String> TARGET_CONDITION_FIELDS = Set.of("kind", "target");
    private static final Set<String> OUTPUT_SHAPE_FIELDS = Set.of(
            "kind",
            "target",
            "labelSha256",
            "labelChars",
            "statementSha256",
            "statementChars");

    private static void validateRequestObligationProjection(
            JsonNode rawProjection,
            String declaredIdentity
    ) {
        ObjectNode projection = exactObject(
                rawProjection,
                REQUEST_OBLIGATION_PROJECTION_FIELDS,
                "request-obligation result projection");
        if (QualificationJson.requiredInt(
                projection.get("schemaVersion"),
                "request-obligation result schemaVersion")
                != RequestObligation.SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "request-obligation result schema version is unsupported");
        }
        if (QualificationJson.requiredEnum(
                projection.get("derivationStatus"),
                RequestObligation.DerivationStatus.class,
                "request-obligation result derivationStatus")
                != RequestObligation.DerivationStatus.COMPLETE) {
            throw new IllegalArgumentException(
                    "request-obligation result must be completely derived");
        }
        QualificationJson.requiredEnum(
                projection.get("responseOwner"),
                RequestObligation.ResponseOwner.class,
                "request-obligation result responseOwner");
        QualificationJson.requiredEnum(
                projection.get("actionKind"),
                RequestObligation.ActionKind.class,
                "request-obligation result actionKind");
        QualificationJson.requiredEnum(
                projection.get("mutationScope"),
                RequestObligation.MutationScope.class,
                "request-obligation result mutationScope");
        exactTextArray(
                projection.get("requiredMutationTargets"),
                "request-obligation result requiredMutationTargets");
        exactTextArray(
                projection.get("requiredSourceTargets"),
                "request-obligation result requiredSourceTargets");
        exactTextArray(
                projection.get("forbiddenTargets"),
                "request-obligation result forbiddenTargets");
        exactConditionArray(
                projection.get("preservationConstraints"),
                RequestObligation.PreservationKind.class,
                "request-obligation result preservationConstraints");
        validateOutputShape(projection.get("outputShape"));
        exactConditionArray(
                projection.get("verificationPostconditions"),
                RequestObligation.VerificationKind.class,
                "request-obligation result verificationPostconditions");
        String projectionIdentity = exactSha256(
                projection.get("canonicalSha256"),
                "request-obligation result canonicalSha256");
        if (!declaredIdentity.equals(projectionIdentity)) {
            throw new IllegalArgumentException(
                    "request-obligation result projection and identity disagree");
        }
    }

    private static ObjectNode exactObject(
            JsonNode value,
            Set<String> expectedFields,
            String label
    ) {
        ObjectNode object = QualificationJson.requiredObject(value, label);
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expectedFields)) {
            throw new IllegalArgumentException(label + " has an invalid field set");
        }
        return object;
    }

    private static void exactTextArray(JsonNode value, String label) {
        ArrayNode array = QualificationJson.requiredArray(value, label);
        for (int index = 0; index < array.size(); index++) {
            QualificationJson.requiredText(
                    array.get(index),
                    label + "[" + index + "]");
        }
    }

    private static <E extends Enum<E>> void exactConditionArray(
            JsonNode value,
            Class<E> kind,
            String label
    ) {
        ArrayNode array = QualificationJson.requiredArray(value, label);
        for (int index = 0; index < array.size(); index++) {
            ObjectNode condition = exactObject(
                    array.get(index),
                    TARGET_CONDITION_FIELDS,
                    label + "[" + index + "]");
            QualificationJson.requiredEnum(
                    condition.get("kind"),
                    kind,
                    label + "[" + index + "].kind");
            QualificationJson.requiredText(
                    condition.get("target"),
                    label + "[" + index + "].target");
        }
    }

    private static void validateOutputShape(JsonNode value) {
        ObjectNode output = exactObject(
                value,
                OUTPUT_SHAPE_FIELDS,
                "request-obligation result outputShape");
        QualificationJson.requiredEnum(
                output.get("kind"),
                RequestObligation.OutputKind.class,
                "request-obligation result outputShape.kind");
        QualificationJson.requiredText(
                output.get("target"),
                "request-obligation result outputShape.target");
        exactSha256(
                output.get("labelSha256"),
                "request-obligation result outputShape.labelSha256");
        QualificationJson.requiredInt(
                output.get("labelChars"),
                0,
                Integer.MAX_VALUE,
                "request-obligation result outputShape.labelChars");
        exactSha256(
                output.get("statementSha256"),
                "request-obligation result outputShape.statementSha256");
        QualificationJson.requiredInt(
                output.get("statementChars"),
                0,
                Integer.MAX_VALUE,
                "request-obligation result outputShape.statementChars");
    }

    private static String exactSha256(JsonNode value, String label) {
        String hash = QualificationJson.requiredText(value, label);
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be lowercase SHA-256");
        }
        return hash;
    }

    static Path evaluateAndWrite(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        return QualificationPacketPublisher.withLease(
                packetRoot,
                lease -> evaluateAndWriteUnderLease(
                        lease, requestedRunRoot, scenarioId, catalog));
    }

    static Path evaluateAndWriteUnderLease(
            QualificationPacketPublisher.Lease lease,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        QualificationArtifactPublication.DeclaredRun declared =
                QualificationArtifactPublication.requireWritableDeclaredRun(
                        lease.packetRoot(), requestedRunRoot, scenarioId, catalog);
        Path runRoot = declared.runRoot();
        QualificationProtocol.Scenario scenario = catalog.scenario(scenarioId);
        Document document = evaluate(
                catalog.protocolVersion(), catalog.sha256(), scenario,
                declared.runFiles(), runRoot);
        Path output = runRoot.resolve(OUTPUT_FILE);
        lease.publish(
                QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                List.of(new QualificationPacketPublisher.Member(
                        output, QualificationJson.bytes(document))));
        return output;
    }

    static Document evaluate(
            int protocolVersion,
            String scenarioBankSha256,
            QualificationProtocol.Scenario scenario,
            Path runRoot) throws IOException {
        NoFollowPathPolicy.GuardedRoot runFiles =
                NoFollowPathPolicy.bindExistingDirectory(runRoot);
        return evaluate(protocolVersion, scenarioBankSha256, scenario, runFiles, runRoot);
    }

    private static Document evaluate(
            int protocolVersion,
            String scenarioBankSha256,
            QualificationProtocol.Scenario scenario,
            NoFollowPathPolicy.GuardedRoot runFiles,
            Path runRoot
    ) throws IOException {
        String answer = runFiles.readString(
                runRoot.resolve("answer.txt"), StandardCharsets.UTF_8);
        String trace = runFiles.readString(
                runRoot.resolve("trace.json"), StandardCharsets.UTF_8);
        Map<String, byte[]> before = readBytes(
                runFiles, runRoot.resolve("before-files"));
        Map<String, byte[]> after = readBytes(
                runFiles, runRoot.resolve("final-files"));
        return evaluate(protocolVersion, scenarioBankSha256, scenario,
                new QualificationRequirementEvaluator.Evidence(
                        answer, trace, before, after));
    }

    static Document evaluate(
            int protocolVersion,
            String scenarioBankSha256,
            QualificationProtocol.Scenario scenario,
            QualificationRequirementEvaluator.Evidence evidence) {
        List<QualificationRecords.RequirementResult> results =
                QualificationRequirementEvaluator.evaluate(scenario, evidence);
        if (results.size() != scenario.atomicRequirements().size()) {
            throw new IllegalStateException("requirement evaluator result count mismatch");
        }
        List<Requirement> requirements = java.util.stream.IntStream.range(0, results.size())
                .mapToObj(index -> {
                    QualificationProtocol.AtomicRequirement requirement =
                            scenario.atomicRequirements().get(index);
                    QualificationRecords.RequirementResult result = results.get(index);
                    if (!requirement.id().equals(result.id())) {
                        throw new IllegalStateException("requirement evaluator identity mismatch");
                    }
                    return new Requirement(result.id(), requirement.type(), result.passed());
                })
                .toList();
        return new Document(
                SCHEMA,
                protocolVersion >= 6 ? 2 : 1,
                protocolVersion,
                scenarioBankSha256,
                scenario.id(),
                requestObligationIdentity(scenario),
                requestObligationProjection(scenario),
                requirements);
    }

    private static String requestObligationIdentity(
            QualificationProtocol.Scenario scenario
    ) {
        boolean required = scenario.atomicRequirements().stream()
                .anyMatch(requirement ->
                        "REQUEST_OBLIGATION".equals(requirement.type()));
        if (!required) return "";
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(scenario.prompt()));
        if (obligation.derivationStatus()
                != RequestObligation.DerivationStatus.COMPLETE) {
            throw new IllegalStateException(
                    "migrated scenario did not compile a complete request obligation");
        }
        return obligation.canonicalSha256();
    }

    private static JsonNode requestObligationProjection(
            QualificationProtocol.Scenario scenario
    ) {
        boolean required = scenario.atomicRequirements().stream()
                .anyMatch(requirement ->
                        "REQUEST_OBLIGATION".equals(requirement.type()));
        if (!required) return QualificationJson.tree(Map.of());
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(scenario.prompt()));
        if (obligation.derivationStatus()
                != RequestObligation.DerivationStatus.COMPLETE) {
            throw new IllegalStateException(
                    "migrated scenario did not compile a complete request obligation");
        }
        return QualificationJson.tree(obligation.redactedProjection());
    }

    static Path containedRunRoot(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        return QualificationArtifactPublication.requireWritableDeclaredRun(
                packetRoot, requestedRunRoot, scenarioId, catalog).runRoot();
    }

    private static Map<String, byte[]> readBytes(
            NoFollowPathPolicy.GuardedRoot runFiles,
            Path root
    ) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        for (NoFollowPathPolicy.TreeEntry entry : runFiles.walk(root)) {
            if (entry.type() != NoFollowPathPolicy.EntryType.REGULAR_FILE) continue;
            String relative = root.relativize(entry.path()).toString().replace('\\', '/');
            files.put(relative, runFiles.readAllBytes(entry.path()));
        }
        return Map.copyOf(files);
    }
}
