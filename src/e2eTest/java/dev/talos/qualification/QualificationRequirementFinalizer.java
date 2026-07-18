package dev.talos.qualification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            List<Requirement> requirements) {
        Document {
            requirements = List.copyOf(requirements);
        }

        List<QualificationRecords.RequirementResult> results() {
            return requirements.stream()
                    .map(requirement -> new QualificationRecords.RequirementResult(
                            requirement.id(), requirement.passed()))
                    .toList();
        }
    }

    static Path evaluateAndWrite(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        Path runRoot = containedRunRoot(packetRoot, requestedRunRoot, scenarioId, catalog);
        QualificationProtocol.Scenario scenario = catalog.scenario(scenarioId);
        Document document = evaluate(
                catalog.protocolVersion(), catalog.sha256(), scenario, runRoot);
        Path output = runRoot.resolve(OUTPUT_FILE);
        QualificationArtifactPublication.publishNewOrVerifyExact(
                output,
                QualificationJson.bytes(document),
                "mechanical requirements");
        return output;
    }

    static Document evaluate(
            int protocolVersion,
            String scenarioBankSha256,
            QualificationProtocol.Scenario scenario,
            Path runRoot) throws IOException {
        String answer = Files.readString(runRoot.resolve("answer.txt"), StandardCharsets.UTF_8);
        String trace = Files.readString(runRoot.resolve("trace.json"), StandardCharsets.UTF_8);
        Map<String, byte[]> before = readBytes(runRoot.resolve("before-files"));
        Map<String, byte[]> after = readBytes(runRoot.resolve("final-files"));
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
                SCHEMA, 1, protocolVersion, scenarioBankSha256, scenario.id(), requirements);
    }

    static Path containedRunRoot(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        return QualificationArtifactPublication.requireWritableDeclaredRun(
                packetRoot, requestedRunRoot, scenarioId, catalog).runRoot();
    }

    private static Map<String, byte[]> readBytes(Path root) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                files.put(relative, Files.readAllBytes(path));
            }
        }
        return Map.copyOf(files);
    }
}
