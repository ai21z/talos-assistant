package dev.talos.qualification;

import dev.talos.runtime.policy.ProtectedContentPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationScenarioBankTest {

    @Test
    void historicalV1AndV2ResourceBytesRemainPinned() throws Exception {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("model-qualification/banks/admission-v1.json",
                        "54cd902f28399c09d9d52560db82d7dab7f5c6db7f3ebb30c43244ef99923a76"),
                Map.entry("model-qualification/banks/admission-v2.json",
                        "d3bc6872542da7a99706f875a39dfc235f2da139c15f2dea8b1c2240069254b8"),
                Map.entry("model-qualification/banks/full-v1.json",
                        "9dab2f8f300e0e2a3987fcfff8e34800bcb2ff542b0d5cd0080263c01145496e"),
                Map.entry("model-qualification/banks/full-v2.json",
                        "acca037b0597c8c7b3c1c61e607d2cc46660c05949872f2b2944f2ef2011eb30"),
                Map.entry("model-qualification/fixtures/basic-workspace-v1.json",
                        "2f5d6a59bdcfd3021ea977908ba1b70b2ce1fc55e90e9c73b4dcae84dfbea689"),
                Map.entry("model-qualification/fixtures/basic-workspace-v2.json",
                        "79d5fe7950f8931c51acec39efc208eb573658f5ee7700971289f472db9d3e96"),
                Map.entry("model-qualification/fixtures/context-workspace-v1.json",
                        "49424edf18c5d9944a7286ee445f48e54485cb605322e69aeba6551b0f18b7b4"),
                Map.entry("model-qualification/fixtures/context-workspace-v2.json",
                        "652ea2c65d4651342d17ba5389d0dae725329649ceca64abdd85ff1cff2a23c6"),
                Map.entry("model-qualification/fixtures/privacy-workspace-v1.json",
                        "cc24ef90dc8e1174934651e95e98e79a7403e10e5d284965ea47c36ac373d86f"),
                Map.entry("model-qualification/fixtures/privacy-workspace-v2.json",
                        "0b3aee1f0cca36823bd08f0adf05765e35f1a3a04ff0809fe90185be4ea97bf6"),
                Map.entry("model-qualification/fixtures/web-workspace-v1.json",
                        "043a02de8972adb77e05bee448ffa80d3e0e868341b7b0cc1b3b8b4a64d7c666"),
                Map.entry("model-qualification/fixtures/web-workspace-v2.json",
                        "d66c74e4295af54a05eb81a422d3300ce7d1ca0bdbdfa4da15754004c6028592"));

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            try (var input = getClass().getClassLoader().getResourceAsStream(entry.getKey())) {
                assertTrue(input != null, entry.getKey());
                assertEquals(entry.getValue(), QualificationScenarioBank.sha256(input.readAllBytes()), entry.getKey());
            }
        }
    }

    @Test
    void versionedCatalogsPreserveV2AndLimitV3ToTheF04DiscoveryTool() {
        QualificationScenarioBank.Catalog v1 = QualificationScenarioBank.load(1);
        QualificationScenarioBank.Catalog v2 = QualificationScenarioBank.load(2);
        QualificationScenarioBank.Catalog v3 = QualificationScenarioBank.loadDefault();

        assertEquals(1, v1.protocolVersion());
        assertEquals(12, v1.admission().scenarios().size());
        assertEquals(24, v1.full().scenarios().size());
        assertEquals(2, v2.protocolVersion());
        assertEquals(3, v3.protocolVersion());
        assertNotEquals(v2.sha256(), v3.sha256());

        QualificationProtocol.Scenario v2F04 = v2.scenario("F04-technical-trace-port");
        QualificationProtocol.Scenario v3F04 = v3.scenario("F04-technical-trace-port");
        assertEquals(List.of("talos.grep", "talos.read_file"),
                v2F04.expectedDecisions().getFirst().allowedTools());
        assertEquals(List.of("talos.grep", "talos.list_dir", "talos.read_file"),
                v3F04.expectedDecisions().getFirst().allowedTools());

        for (QualificationProtocol.Scenario before : Stream.concat(
                v2.admission().scenarios().stream(), v2.full().scenarios().stream()).toList()) {
            QualificationProtocol.Scenario after = v3.scenario(before.id());
            assertEquals(before.bank(), after.bank(), before.id());
            assertEquals(before.archetype(), after.archetype(), before.id());
            assertEquals(before.category(), after.category(), before.id());
            assertEquals(before.prompt(), after.prompt(), before.id());
            assertEquals(before.approvalBehavior(), after.approvalBehavior(), before.id());
            assertEquals(before.expectedTerminalStates(), after.expectedTerminalStates(), before.id());
            assertEquals(before.allowedTargets(), after.allowedTargets(), before.id());
            assertEquals(before.forbiddenTargets(), after.forbiddenTargets(), before.id());
            assertEquals(before.atomicRequirements(), after.atomicRequirements(), before.id());
            assertEquals(v2.fixtures().get(before.fixture()).manifest().files(),
                    v3.fixtures().get(after.fixture()).manifest().files(), before.id());

            List<QualificationProtocol.ExpectedDecision> normalized = after.expectedDecisions().stream()
                    .map(decision -> before.id().equals("F04-technical-trace-port")
                            ? new QualificationProtocol.ExpectedDecision(
                                    decision.id(), decision.kind(), decision.allowedTools().stream()
                                            .filter(tool -> !tool.equals("talos.list_dir"))
                                            .toList(), decision.scored())
                            : decision)
                    .toList();
            assertEquals(before.expectedDecisions(), normalized, before.id());
        }
    }

    @Test
    void defaultBankHasFrozenAdmissionAndFullShapes() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();

        assertEquals(3, catalog.protocolVersion());
        assertEquals(12, catalog.admission().scenarios().size());
        assertEquals(24, catalog.full().scenarios().size());
        assertEquals(8, catalog.full().scenarios().stream()
                .map(QualificationProtocol.Scenario::archetype)
                .distinct()
                .count());
        for (QualificationProtocol.Archetype archetype : QualificationProtocol.Archetype.values()) {
            assertEquals(3, catalog.full().scenarios().stream()
                    .filter(scenario -> scenario.archetype() == archetype)
                    .count(), archetype.name());
        }
        assertEquals(6, catalog.admission().scenarios().stream()
                .flatMap(scenario -> scenario.expectedDecisions().stream())
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .filter(decision -> decision.kind() == QualificationProtocol.DecisionKind.REQUIRED_TOOL)
                .count());
        assertEquals(3, catalog.admission().scenarios().stream()
                .flatMap(scenario -> scenario.expectedDecisions().stream())
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .filter(decision -> decision.kind() == QualificationProtocol.DecisionKind.NO_TOOL)
                .count());
        assertTrue(catalog.sha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void bankHashIsPathOrderedAndByteSensitive() {
        List<QualificationScenarioBank.NamedBytes> first = List.of(
                new QualificationScenarioBank.NamedBytes("b.json", "two".getBytes(StandardCharsets.UTF_8)),
                new QualificationScenarioBank.NamedBytes("a.json", "one".getBytes(StandardCharsets.UTF_8)));
        List<QualificationScenarioBank.NamedBytes> reordered = List.of(
                new QualificationScenarioBank.NamedBytes("a.json", "one".getBytes(StandardCharsets.UTF_8)),
                new QualificationScenarioBank.NamedBytes("b.json", "two".getBytes(StandardCharsets.UTF_8)));
        List<QualificationScenarioBank.NamedBytes> changed = List.of(
                new QualificationScenarioBank.NamedBytes("a.json", "one\n".getBytes(StandardCharsets.UTF_8)),
                new QualificationScenarioBank.NamedBytes("b.json", "two".getBytes(StandardCharsets.UTF_8)));

        assertEquals(QualificationScenarioBank.hash(first), QualificationScenarioBank.hash(reordered));
        assertNotEquals(QualificationScenarioBank.hash(first), QualificationScenarioBank.hash(changed));
    }

    @Test
    void invalidFixtureHashFailsClosed() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationProtocol.Scenario original = catalog.admission().scenarios().getFirst();
        QualificationProtocol.Scenario invalid = original.withFixtureSha256("0".repeat(64));

        assertThrows(IllegalArgumentException.class,
                () -> QualificationScenarioBank.validateScenario(invalid, catalog.fixtures()));
    }

    @Test
    void privacyFixtureUsesCanariesRecognizedByTheRuntimeScanner() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        var files = catalog.fixtures().get("privacy-workspace-v3.json").manifest().files();

        assertTrue(ProtectedContentPolicy.containsRawCanary(files.get(".env")),
                "the protected-file canary must use Talos's production canary vocabulary");
        assertTrue(ProtectedContentPolicy.containsRawCanary(files.get("protected/private-notes.md")),
                "the private-document canary must use Talos's production canary vocabulary");
    }

    @Test
    void frozenTerminalStatesMatchRuntimeTraceClassifications() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();

        assertTrue(catalog.scenario("A01-simple-arithmetic-abstention")
                .expectedTerminalStates().contains("READ_ONLY_ANSWERED"));
        assertTrue(catalog.scenario("A09-contradictory-denied-mutation")
                .expectedTerminalStates().contains("BLOCKED_BY_APPROVAL"));
    }
}
