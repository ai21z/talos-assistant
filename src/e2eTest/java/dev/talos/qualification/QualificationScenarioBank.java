package dev.talos.qualification;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class QualificationScenarioBank {
    private static final Set<String> REQUIREMENT_TYPES = Set.of(
            "ANSWER_CONTAINS",
            "ANSWER_NOT_CONTAINS",
            "ANSWER_EQUALS",
            "ANSWER_MAX_SENTENCES",
            "ANSWER_BULLET_COUNT",
            "ANSWER_ITEM_COUNT",
            "FILE_ABSENT",
            "FILE_CONTAINS",
            "FILE_EQUALS",
            "FILE_UNCHANGED",
            "TRACE_CONTAINS");
    private static final String ADMISSION = "model-qualification/banks/admission-v2.json";
    private static final String FULL = "model-qualification/banks/full-v2.json";
    private static final List<String> FIXTURES = List.of(
            "model-qualification/fixtures/basic-workspace-v2.json",
            "model-qualification/fixtures/context-workspace-v2.json",
            "model-qualification/fixtures/privacy-workspace-v2.json",
            "model-qualification/fixtures/web-workspace-v2.json");

    private QualificationScenarioBank() {}

    record NamedBytes(String name, byte[] bytes) {
        NamedBytes {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("resource name is required");
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record FixtureResource(
            String name,
            byte[] bytes,
            QualificationProtocol.FixtureManifest manifest,
            String sha256) {
        FixtureResource {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record Catalog(
            QualificationProtocol.ScenarioBank admission,
            QualificationProtocol.ScenarioBank full,
            Map<String, FixtureResource> fixtures,
            String sha256) {
        Catalog {
            fixtures = Map.copyOf(fixtures);
        }

        QualificationProtocol.ScenarioBank bank(QualificationProtocol.BankKind kind) {
            return kind == QualificationProtocol.BankKind.ADMISSION ? admission : full;
        }

        QualificationProtocol.Scenario scenario(String id) {
            return java.util.stream.Stream.concat(admission.scenarios().stream(), full.scenarios().stream())
                    .filter(candidate -> candidate.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown qualification scenario: " + id));
        }
    }

    static Catalog loadDefault() {
        try {
            List<NamedBytes> resources = new ArrayList<>();
            NamedBytes admissionBytes = resource(ADMISSION);
            NamedBytes fullBytes = resource(FULL);
            resources.add(admissionBytes);
            resources.add(fullBytes);

            QualificationProtocol.ScenarioBank admission =
                    QualificationJson.read(admissionBytes.bytes(), QualificationProtocol.ScenarioBank.class);
            QualificationProtocol.ScenarioBank full =
                    QualificationJson.read(fullBytes.bytes(), QualificationProtocol.ScenarioBank.class);
            Map<String, FixtureResource> fixtures = new LinkedHashMap<>();
            for (String fixturePath : FIXTURES) {
                NamedBytes fixtureBytes = resource(fixturePath);
                resources.add(fixtureBytes);
                QualificationProtocol.FixtureManifest manifest = QualificationJson.read(
                        fixtureBytes.bytes(), QualificationProtocol.FixtureManifest.class);
                String fileName = Path.of(fixturePath).getFileName().toString();
                FixtureResource fixture = new FixtureResource(
                        fileName, fixtureBytes.bytes(), manifest, sha256(fixtureBytes.bytes()));
                if (fixtures.putIfAbsent(fileName, fixture) != null) {
                    throw new IllegalArgumentException("Duplicate fixture resource: " + fileName);
                }
                validateFixture(fixture);
            }
            validateBank(admission, QualificationProtocol.BankKind.ADMISSION, 12, fixtures);
            validateBank(full, QualificationProtocol.BankKind.FULL, 24, fixtures);
            validateCatalogShape(admission, full);
            return new Catalog(admission, full, fixtures, hash(resources));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load frozen qualification resources", e);
        }
    }

    static void validateScenario(QualificationProtocol.Scenario scenario, Map<String, FixtureResource> fixtures) {
        if (scenario == null) throw new IllegalArgumentException("scenario is required");
        FixtureResource fixture = fixtures.get(scenario.fixture());
        if (fixture == null) {
            throw new IllegalArgumentException("Unknown fixture for " + scenario.id() + ": " + scenario.fixture());
        }
        if (!fixture.sha256().equalsIgnoreCase(scenario.fixtureSha256())) {
            throw new IllegalArgumentException("Fixture SHA-256 mismatch for " + scenario.id());
        }
        requireSha256(scenario.fixtureSha256(), "fixture SHA-256 for " + scenario.id());
        validateTargetList(scenario.allowedTargets(), "allowed target", scenario.id());
        validateTargetList(scenario.forbiddenTargets(), "forbidden target", scenario.id());
        Set<String> decisionIds = new HashSet<>();
        for (QualificationProtocol.ExpectedDecision decision : scenario.expectedDecisions()) {
            if (!decisionIds.add(decision.id())) {
                throw new IllegalArgumentException("Duplicate decision id in " + scenario.id() + ": " + decision.id());
            }
        }
        Set<String> requirementIds = new HashSet<>();
        for (QualificationProtocol.AtomicRequirement requirement : scenario.atomicRequirements()) {
            if (!requirementIds.add(requirement.id())) {
                throw new IllegalArgumentException(
                        "Duplicate requirement id in " + scenario.id() + ": " + requirement.id());
            }
            if (!REQUIREMENT_TYPES.contains(requirement.type())) {
                throw new IllegalArgumentException(
                        "Unsupported requirement type in " + scenario.id() + ": " + requirement.type());
            }
        }
        Set<String> overlap = new HashSet<>(scenario.allowedTargets());
        overlap.retainAll(scenario.forbiddenTargets());
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("Targets are both allowed and forbidden in " + scenario.id());
        }
        if (scenario.category() == QualificationProtocol.ScenarioCategory.NO_TOOL
                && scenario.expectedDecisions().stream().noneMatch(decision ->
                decision.scored() && decision.kind() == QualificationProtocol.DecisionKind.NO_TOOL)) {
            throw new IllegalArgumentException("NO_TOOL scenario lacks a scored abstention: " + scenario.id());
        }
        if (scenario.category() == QualificationProtocol.ScenarioCategory.MUTATION
                && scenario.approvalBehavior() == QualificationProtocol.ApprovalBehavior.NONE) {
            throw new IllegalArgumentException("Mutation scenario lacks declared approval behavior: " + scenario.id());
        }
    }

    static String hash(List<NamedBytes> resources) {
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("Qualification hash input must not be empty");
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                for (NamedBytes resource : resources.stream()
                        .sorted(Comparator.comparing(NamedBytes::name))
                        .toList()) {
                    byte[] name = resource.name().getBytes(StandardCharsets.UTF_8);
                    byte[] content = resource.bytes();
                    output.writeInt(name.length);
                    output.write(name);
                    output.writeLong(content.length);
                    output.write(content);
                }
            }
            return sha256(buffer.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to hash qualification resources", e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format("%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static NamedBytes resource(String name) throws IOException {
        ClassLoader loader = QualificationScenarioBank.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing qualification resource: " + name);
            return new NamedBytes(name, input.readAllBytes());
        }
    }

    private static void validateFixture(FixtureResource fixture) {
        QualificationProtocol.FixtureManifest manifest = fixture.manifest();
        if (!QualificationProtocol.FIXTURE_SCHEMA.equals(manifest.schemaName())
                || manifest.schemaVersion() != QualificationProtocol.VERSION) {
            throw new IllegalArgumentException("Unsupported fixture schema: " + fixture.name());
        }
        if (!fixture.name().equals(manifest.id() + ".json")) {
            throw new IllegalArgumentException("Fixture id/file mismatch: " + fixture.name());
        }
        validateTargetList(new ArrayList<>(manifest.files().keySet()), "fixture path", manifest.id());
    }

    private static void validateBank(
            QualificationProtocol.ScenarioBank bank,
            QualificationProtocol.BankKind expectedKind,
            int expectedCount,
            Map<String, FixtureResource> fixtures) {
        if (!QualificationProtocol.BANK_SCHEMA.equals(bank.schemaName())
                || bank.schemaVersion() != QualificationProtocol.VERSION) {
            throw new IllegalArgumentException("Unsupported qualification bank schema: " + bank.id());
        }
        if (bank.kind() != expectedKind || bank.scenarios().size() != expectedCount) {
            throw new IllegalArgumentException("Unexpected frozen bank shape: " + bank.id());
        }
        Set<String> ids = new HashSet<>();
        for (QualificationProtocol.Scenario scenario : bank.scenarios()) {
            if (scenario.bank() != expectedKind) {
                throw new IllegalArgumentException("Scenario bank mismatch: " + scenario.id());
            }
            if (!ids.add(scenario.id())) {
                throw new IllegalArgumentException("Duplicate scenario id: " + scenario.id());
            }
            validateScenario(scenario, fixtures);
        }
    }

    private static void validateCatalogShape(
            QualificationProtocol.ScenarioBank admission,
            QualificationProtocol.ScenarioBank full) {
        long required = admission.scenarios().stream()
                .flatMap(scenario -> scenario.expectedDecisions().stream())
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .filter(decision -> decision.kind() == QualificationProtocol.DecisionKind.REQUIRED_TOOL)
                .count();
        long abstentions = admission.scenarios().stream()
                .flatMap(scenario -> scenario.expectedDecisions().stream())
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .filter(decision -> decision.kind() == QualificationProtocol.DecisionKind.NO_TOOL)
                .count();
        if (required != 6 || abstentions != 3) {
            throw new IllegalArgumentException("Admission decision denominator drift");
        }
        Map<QualificationProtocol.Archetype, Long> counts = new HashMap<>();
        full.scenarios().forEach(scenario -> counts.merge(scenario.archetype(), 1L, Long::sum));
        if (!counts.keySet().equals(EnumSet.allOf(QualificationProtocol.Archetype.class))) {
            throw new IllegalArgumentException("Full bank archetype coverage drift");
        }
        counts.forEach((archetype, count) -> {
            if (count != 3L) throw new IllegalArgumentException("Full bank count drift for " + archetype);
        });
    }

    private static void validateTargetList(List<String> targets, String label, String owner) {
        for (String target : targets) {
            if (!safeRelative(target)) {
                throw new IllegalArgumentException("Unsafe " + label + " in " + owner + ": " + target);
            }
        }
    }

    private static boolean safeRelative(String value) {
        if (value == null || value.isBlank()) return false;
        if (value.equals(".")) return true;
        String portable = value.replace('\\', '/');
        if (portable.startsWith("/") || portable.matches("(?i)^[a-z]:.*")) return false;
        for (String segment : portable.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) return false;
        }
        try {
            Path path = Path.of(value);
            if (path.isAbsolute()) return false;
            for (Path segment : path) {
                if (segment.toString().equals("..")) return false;
            }
            return !path.normalize().toString().isBlank() && !path.normalize().startsWith("..");
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static void requireSha256(String value, String label) {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }
}
