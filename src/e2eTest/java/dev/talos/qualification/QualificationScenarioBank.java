package dev.talos.qualification;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
    private static final Set<Integer> SUPPORTED_VERSIONS = Set.of(1, 2, 3, 4, 5);
    private static final List<String> FIXTURE_NAMES = List.of(
            "basic-workspace", "context-workspace", "privacy-workspace", "web-workspace");
    private static final String FULL_V5_PATH = "model-qualification/banks/full-v5.json";
    // The published v5 full bank contains these LF separators inside its otherwise CRLF body.
    // They are part of frozen packet identity, not a preferred formatting convention.
    private static final Set<Integer> FULL_V5_PUBLISHED_LF_SEPARATOR_ORDINALS = Set.of(
            780, 803, 804, 805, 806, 807, 808, 809, 810, 811, 812, 813,
            817, 818, 819, 820, 821, 822, 823, 824, 825, 826, 827, 828,
            829, 830, 831);
    // The normalized hash authenticates checkout-independent Git content. The published hash
    // authenticates the exact byte representation named by historical campaign manifests.
    private static final Map<String, ResourceIdentity> RESOURCE_IDENTITIES = Map.ofEntries(
            Map.entry("model-qualification/banks/admission-v1.json", new ResourceIdentity("54cd902f28399c09d9d52560db82d7dab7f5c6db7f3ebb30c43244ef99923a76", "54cd902f28399c09d9d52560db82d7dab7f5c6db7f3ebb30c43244ef99923a76")),
            Map.entry("model-qualification/banks/full-v1.json", new ResourceIdentity("9dab2f8f300e0e2a3987fcfff8e34800bcb2ff542b0d5cd0080263c01145496e", "9dab2f8f300e0e2a3987fcfff8e34800bcb2ff542b0d5cd0080263c01145496e")),
            Map.entry("model-qualification/fixtures/basic-workspace-v1.json", new ResourceIdentity("2f5d6a59bdcfd3021ea977908ba1b70b2ce1fc55e90e9c73b4dcae84dfbea689", "2f5d6a59bdcfd3021ea977908ba1b70b2ce1fc55e90e9c73b4dcae84dfbea689")),
            Map.entry("model-qualification/fixtures/context-workspace-v1.json", new ResourceIdentity("49424edf18c5d9944a7286ee445f48e54485cb605322e69aeba6551b0f18b7b4", "49424edf18c5d9944a7286ee445f48e54485cb605322e69aeba6551b0f18b7b4")),
            Map.entry("model-qualification/fixtures/privacy-workspace-v1.json", new ResourceIdentity("cc24ef90dc8e1174934651e95e98e79a7403e10e5d284965ea47c36ac373d86f", "cc24ef90dc8e1174934651e95e98e79a7403e10e5d284965ea47c36ac373d86f")),
            Map.entry("model-qualification/fixtures/web-workspace-v1.json", new ResourceIdentity("043a02de8972adb77e05bee448ffa80d3e0e868341b7b0cc1b3b8b4a64d7c666", "043a02de8972adb77e05bee448ffa80d3e0e868341b7b0cc1b3b8b4a64d7c666")),
            Map.entry("model-qualification/banks/admission-v2.json", new ResourceIdentity("d3bc6872542da7a99706f875a39dfc235f2da139c15f2dea8b1c2240069254b8", "d3bc6872542da7a99706f875a39dfc235f2da139c15f2dea8b1c2240069254b8")),
            Map.entry("model-qualification/banks/full-v2.json", new ResourceIdentity("acca037b0597c8c7b3c1c61e607d2cc46660c05949872f2b2944f2ef2011eb30", "acca037b0597c8c7b3c1c61e607d2cc46660c05949872f2b2944f2ef2011eb30")),
            Map.entry("model-qualification/fixtures/basic-workspace-v2.json", new ResourceIdentity("79d5fe7950f8931c51acec39efc208eb573658f5ee7700971289f472db9d3e96", "79d5fe7950f8931c51acec39efc208eb573658f5ee7700971289f472db9d3e96")),
            Map.entry("model-qualification/fixtures/context-workspace-v2.json", new ResourceIdentity("652ea2c65d4651342d17ba5389d0dae725329649ceca64abdd85ff1cff2a23c6", "652ea2c65d4651342d17ba5389d0dae725329649ceca64abdd85ff1cff2a23c6")),
            Map.entry("model-qualification/fixtures/privacy-workspace-v2.json", new ResourceIdentity("0b3aee1f0cca36823bd08f0adf05765e35f1a3a04ff0809fe90185be4ea97bf6", "0b3aee1f0cca36823bd08f0adf05765e35f1a3a04ff0809fe90185be4ea97bf6")),
            Map.entry("model-qualification/fixtures/web-workspace-v2.json", new ResourceIdentity("d66c74e4295af54a05eb81a422d3300ce7d1ca0bdbdfa4da15754004c6028592", "d66c74e4295af54a05eb81a422d3300ce7d1ca0bdbdfa4da15754004c6028592")),
            Map.entry("model-qualification/banks/admission-v3.json", new ResourceIdentity("71f3093f5d44c14b59ac758cfaaecdf7837769c31c87c3d49a56fe6da6d3ff7c", "71f3093f5d44c14b59ac758cfaaecdf7837769c31c87c3d49a56fe6da6d3ff7c")),
            Map.entry("model-qualification/banks/full-v3.json", new ResourceIdentity("956d89629b95dd63c7bfda61cea184fdb80f04c71a4c20284c01b71805fbd545", "956d89629b95dd63c7bfda61cea184fdb80f04c71a4c20284c01b71805fbd545")),
            Map.entry("model-qualification/fixtures/basic-workspace-v3.json", new ResourceIdentity("8a425ad1de1cdcd641407e6e00862744bcc7f11f819fab6f4af938a76ae2aaa7", "8a425ad1de1cdcd641407e6e00862744bcc7f11f819fab6f4af938a76ae2aaa7")),
            Map.entry("model-qualification/fixtures/context-workspace-v3.json", new ResourceIdentity("19852c9e8895827b5d9aead01df3367900ff41787d34f060ec47eeba6c86f39d", "19852c9e8895827b5d9aead01df3367900ff41787d34f060ec47eeba6c86f39d")),
            Map.entry("model-qualification/fixtures/privacy-workspace-v3.json", new ResourceIdentity("ccc9c8496c2278bcbf3c380df21a216b5765c702fa8c5dd5da1eea165b8dface", "ccc9c8496c2278bcbf3c380df21a216b5765c702fa8c5dd5da1eea165b8dface")),
            Map.entry("model-qualification/fixtures/web-workspace-v3.json", new ResourceIdentity("fb63451517715763426d6dc779f9b6165eb258ce4793fd64f009ca42f7884b87", "fb63451517715763426d6dc779f9b6165eb258ce4793fd64f009ca42f7884b87")),
            Map.entry("model-qualification/banks/admission-v4.json", new ResourceIdentity("c45e879022be0205b11cc150f63b3d5915c4231a01fd66f743ea2cdc2e1b5b02", "606726bf72f6e2a3f31d20cc46c8b41654d70aa42c06151c81f4a3be76909a6f")),
            Map.entry("model-qualification/banks/full-v4.json", new ResourceIdentity("a142a33738db4142f4ff56e2654f316ae7985c2e44f1780a0c5c1987d6b0348e", "ccdba9307ea8d07c5b4e54ece9a295da0963363222bd664e8f50e6173ea76020")),
            Map.entry("model-qualification/fixtures/basic-workspace-v4.json", new ResourceIdentity("81fd3801b1b7153a749fbbdab8cc8ccf445bbb919a35dde6ff36c5c80e24682b", "31bc4929dfd5865b801ba73f46c74fee198102aec4ce47e14c77f4c68fc6cdae")),
            Map.entry("model-qualification/fixtures/context-workspace-v4.json", new ResourceIdentity("23165a72110620887c530b4ec93d3c95d3f699835c01a8aa6ea98e1d0cc88c7b", "e4708699fb0aca99518a811503ee235a021f76c2d6a18fba00bb004f21ce2530")),
            Map.entry("model-qualification/fixtures/privacy-workspace-v4.json", new ResourceIdentity("324d12c0c129fcf20ead625066d1b2c4a887c4d08de7a7a68223cf5c58f52c1e", "ff1895c039041bc4b7e874cea643645a08ebe3366c25eee216572c68564e4bbf")),
            Map.entry("model-qualification/fixtures/web-workspace-v4.json", new ResourceIdentity("c5c7624e47bd722d614770eab3b0664d6c53727a4e9927fa78dce71060619393", "536612d8d076f7f2654328cf5d3fe88de6ae0518ca5265b8537094ad41c79bf4")),
            Map.entry("model-qualification/banks/admission-v5.json", new ResourceIdentity("73330fefe21aa7ed144b1b7b9c83fefc30f526c2dd59eaa3139bd903ea45c8ec", "9990a74ac870690e2c7ea97b6bfc60ce3e04f35d6d49bad41e3caf6975f0f741")),
            Map.entry(FULL_V5_PATH, new ResourceIdentity("13cdff2293d6080fcccb76b4bd5f33e69cc023c791a058a05cdd267131c59ffe", "e7136109e7066b64ff514f6a713c86d56ca3861b767720f90779c2b9047c9133")),
            Map.entry("model-qualification/fixtures/basic-workspace-v5.json", new ResourceIdentity("1f0f8c4bd3af28731dcf2ec6813fe495b8396ebace8357f47065fa193b141c37", "da1a0dcb09af384c40e44dabc5bb357f5ec47c2d56e0328ea7c17b736aea5e37")),
            Map.entry("model-qualification/fixtures/context-workspace-v5.json", new ResourceIdentity("5ea35fd54391f7f07ff5bd7c398320b14fb078d1ae6f0c9d695fd3f34afd480c", "2c27b3584fd4275517415fbc33074bd5bb93d81d0e7821a03fc98cc6eefe5e1e")),
            Map.entry("model-qualification/fixtures/privacy-workspace-v5.json", new ResourceIdentity("73440a10a20519b56c58440cdfb78ab7e1fb73c4d81d260230719db472e9b2ef", "3b885ceb6197b80442bb3b7b6bacb19a1dcfd7dac32fe37ad5e9e8673f4e0672")),
            Map.entry("model-qualification/fixtures/web-workspace-v5.json", new ResourceIdentity("e73f177ca821dc8aeb15a818f5549b90a34461b6193bb5fddc10778aa1cd901c", "47c5f342965550a01c1d7764ae3059088e8eda8cdf037e54c84f4364afb7fd10")));

    private QualificationScenarioBank() {}

    private record ResourceIdentity(String normalizedLfSha256, String publishedSha256) {}

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
            int protocolVersion,
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
        return load(QualificationProtocol.VERSION);
    }

    static Catalog load(int protocolVersion) {
        if (!SUPPORTED_VERSIONS.contains(protocolVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported qualification protocol version: " + protocolVersion);
        }
        try {
            List<NamedBytes> resources = new ArrayList<>();
            NamedBytes admissionBytes = resource(bankPath("admission", protocolVersion), protocolVersion);
            NamedBytes fullBytes = resource(bankPath("full", protocolVersion), protocolVersion);
            resources.add(admissionBytes);
            resources.add(fullBytes);

            QualificationProtocol.ScenarioBank admission =
                    QualificationJson.read(admissionBytes.bytes(), QualificationProtocol.ScenarioBank.class);
            QualificationProtocol.ScenarioBank full =
                    QualificationJson.read(fullBytes.bytes(), QualificationProtocol.ScenarioBank.class);
            Map<String, FixtureResource> fixtures = new LinkedHashMap<>();
            for (String fixtureName : FIXTURE_NAMES) {
                String fixturePath = fixturePath(fixtureName, protocolVersion);
                NamedBytes fixtureBytes = resource(fixturePath, protocolVersion);
                resources.add(fixtureBytes);
                QualificationProtocol.FixtureManifest manifest = QualificationJson.read(
                        fixtureBytes.bytes(), QualificationProtocol.FixtureManifest.class);
                String fileName = Path.of(fixturePath).getFileName().toString();
                FixtureResource fixture = new FixtureResource(
                        fileName, fixtureBytes.bytes(), manifest, sha256(fixtureBytes.bytes()));
                if (fixtures.putIfAbsent(fileName, fixture) != null) {
                    throw new IllegalArgumentException("Duplicate fixture resource: " + fileName);
                }
                validateFixture(fixture, protocolVersion);
            }
            validateBank(admission, QualificationProtocol.BankKind.ADMISSION, 12, fixtures, protocolVersion);
            validateBank(full, QualificationProtocol.BankKind.FULL, 24, fixtures, protocolVersion);
            validateCatalogShape(admission, full);
            return new Catalog(protocolVersion, admission, full, fixtures, hash(resources));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load frozen qualification resources", e);
        }
    }

    private static String bankPath(String kind, int protocolVersion) {
        return "model-qualification/banks/" + kind + "-v" + protocolVersion + ".json";
    }

    private static String fixturePath(String name, int protocolVersion) {
        return "model-qualification/fixtures/" + name + "-v" + protocolVersion + ".json";
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
            if (!QualificationRequirementEvaluator.supports(requirement.type())) {
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

    static byte[] canonicalResourceBytes(String name, byte[] sourceBytes, int protocolVersion) {
        if (!SUPPORTED_VERSIONS.contains(protocolVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported qualification protocol version: " + protocolVersion);
        }
        if (!expectedResourceNames(protocolVersion).contains(name)) {
            throw new IllegalArgumentException(
                    "Unexpected qualification resource for protocol v" + protocolVersion + ": " + name);
        }

        byte[] normalizedLf = normalizePhysicalLineEndings(name, sourceBytes);
        ResourceIdentity identity = RESOURCE_IDENTITIES.get(name);
        if (identity == null) {
            throw new IllegalArgumentException("Qualification resource has no pinned identity: " + name);
        }
        String normalizedHash = sha256(normalizedLf);
        if (!identity.normalizedLfSha256().equals(normalizedHash)) {
            throw new IllegalArgumentException(
                    "Qualification resource normalized content differs from its pinned identity: " + name);
        }

        byte[] canonical = protocolVersion <= 3
                ? normalizedLf
                : reconstructPublishedMixedLineEndings(name, normalizedLf);
        String publishedHash = sha256(canonical);
        if (!identity.publishedSha256().equals(publishedHash)) {
            throw new IllegalStateException(
                    "Qualification resource historical line-ending manifest is inconsistent: " + name);
        }
        return canonical;
    }

    private static NamedBytes resource(String name, int protocolVersion) throws IOException {
        ClassLoader loader = QualificationScenarioBank.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing qualification resource: " + name);
            return new NamedBytes(name,
                    canonicalResourceBytes(name, input.readAllBytes(), protocolVersion));
        }
    }

    private static Set<String> expectedResourceNames(int protocolVersion) {
        Set<String> names = new HashSet<>();
        names.add(bankPath("admission", protocolVersion));
        names.add(bankPath("full", protocolVersion));
        for (String fixtureName : FIXTURE_NAMES) {
            names.add(fixturePath(fixtureName, protocolVersion));
        }
        return Set.copyOf(names);
    }

    static byte[] normalizePhysicalLineEndings(String name, byte[] sourceBytes) {
        if (sourceBytes == null || sourceBytes.length == 0) {
            throw new IllegalArgumentException("Qualification resource is empty: " + name);
        }
        if (sourceBytes.length >= 3
                && sourceBytes[0] == (byte) 0xef
                && sourceBytes[1] == (byte) 0xbb
                && sourceBytes[2] == (byte) 0xbf) {
            throw new IllegalArgumentException("Qualification resource has an unsupported UTF-8 BOM: " + name);
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(sourceBytes));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Qualification resource is not valid UTF-8: " + name, e);
        }

        ByteArrayOutputStream normalized = new ByteArrayOutputStream(sourceBytes.length);
        for (int index = 0; index < sourceBytes.length; index++) {
            byte value = sourceBytes[index];
            if (value != '\r') {
                normalized.write(value);
                continue;
            }
            if (index + 1 >= sourceBytes.length || sourceBytes[index + 1] != '\n') {
                throw new IllegalArgumentException(
                        "Qualification resource contains a lone carriage return: " + name);
            }
            normalized.write('\n');
            index++;
        }
        return normalized.toByteArray();
    }

    private static byte[] reconstructPublishedMixedLineEndings(String name, byte[] normalizedLf) {
        ByteArrayOutputStream published = new ByteArrayOutputStream(normalizedLf.length);
        int separatorOrdinal = 0;
        for (int index = 0; index < normalizedLf.length; index++) {
            byte value = normalizedLf[index];
            if (value != '\n') {
                published.write(value);
                continue;
            }
            separatorOrdinal++;
            boolean finalSeparator = index == normalizedLf.length - 1;
            boolean historicallyLf = finalSeparator
                    || name.equals(FULL_V5_PATH)
                    && FULL_V5_PUBLISHED_LF_SEPARATOR_ORDINALS.contains(separatorOrdinal);
            if (!historicallyLf) published.write('\r');
            published.write('\n');
        }
        return published.toByteArray();
    }

    private static void validateFixture(FixtureResource fixture, int protocolVersion) {
        QualificationProtocol.FixtureManifest manifest = fixture.manifest();
        if (!QualificationProtocol.FIXTURE_SCHEMA.equals(manifest.schemaName())
                || manifest.schemaVersion() != protocolVersion) {
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
            Map<String, FixtureResource> fixtures,
            int protocolVersion) {
        if (!QualificationProtocol.BANK_SCHEMA.equals(bank.schemaName())
                || bank.schemaVersion() != protocolVersion) {
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
