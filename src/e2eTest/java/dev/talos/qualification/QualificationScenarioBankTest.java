package dev.talos.qualification;

import dev.talos.runtime.policy.ProtectedContentPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationScenarioBankTest {

    @Test
    void defaultBankHasFrozenAdmissionAndFullShapes() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();

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
        var files = catalog.fixtures().get("privacy-workspace-v2.json").manifest().files();

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
