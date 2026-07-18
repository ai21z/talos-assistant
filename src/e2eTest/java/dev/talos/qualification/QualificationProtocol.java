package dev.talos.qualification;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class QualificationProtocol {
    static final int VERSION = 6;
    static final String BANK_SCHEMA = "talos.modelQualification.scenarioBank";
    static final String FIXTURE_SCHEMA = "talos.modelQualification.fixture";

    private QualificationProtocol() {}

    enum BankKind {
        ADMISSION,
        FULL
    }

    enum Archetype {
        SIMPLE,
        TECHNICAL,
        CONTRADICTORY,
        AI_SUPERUSER,
        PRIVACY,
        RECOVERY,
        CONTEXT_PRESSURE,
        WEAK_HARDWARE
    }

    enum ScenarioCategory {
        NO_TOOL,
        READ,
        MUTATION,
        REPAIR,
        INTERRUPTION
    }

    enum ApprovalBehavior {
        NONE,
        APPROVE_ONCE,
        DENY,
        SESSION_ALLOW,
        INTERRUPT_BEFORE_APPROVAL
    }

    enum DecisionKind {
        REQUIRED_TOOL,
        NO_TOOL
    }

    record ExpectedDecision(String id, DecisionKind kind, List<String> allowedTools, boolean scored) {
        ExpectedDecision {
            id = required(id, "decision id");
            kind = Objects.requireNonNull(kind, "decision kind");
            allowedTools = immutable(allowedTools);
            if (kind == DecisionKind.NO_TOOL && !allowedTools.isEmpty()) {
                throw new IllegalArgumentException("NO_TOOL decision cannot allow tools: " + id);
            }
        }
    }

    record AtomicRequirement(String id, String type, String target, String expected) {
        AtomicRequirement {
            id = required(id, "requirement id");
            type = required(type, "requirement type");
            target = required(target, "requirement target");
            expected = Objects.requireNonNull(expected, "requirement expected");
        }
    }

    record Scenario(
            String id,
            BankKind bank,
            Archetype archetype,
            ScenarioCategory category,
            String fixture,
            String fixtureSha256,
            String prompt,
            ApprovalBehavior approvalBehavior,
            QualificationRecords.ResponseOwnerContract responseOwnerContract,
            List<String> expectedTerminalStates,
            List<String> allowedTargets,
            List<String> forbiddenTargets,
            List<ExpectedDecision> expectedDecisions,
            List<AtomicRequirement> atomicRequirements) {
        Scenario {
            id = required(id, "scenario id");
            bank = Objects.requireNonNull(bank, "scenario bank");
            archetype = Objects.requireNonNull(archetype, "scenario archetype");
            category = Objects.requireNonNull(category, "scenario category");
            fixture = required(fixture, "scenario fixture");
            fixtureSha256 = required(fixtureSha256, "fixture SHA-256");
            prompt = required(prompt, "scenario prompt");
            approvalBehavior = Objects.requireNonNull(approvalBehavior, "approval behavior");
            responseOwnerContract = Objects.requireNonNull(responseOwnerContract, "response owner contract");
            expectedTerminalStates = immutableRequired(expectedTerminalStates, "expected terminal states");
            allowedTargets = immutable(allowedTargets);
            forbiddenTargets = immutable(forbiddenTargets);
            expectedDecisions = immutable(expectedDecisions);
            atomicRequirements = immutableRequired(atomicRequirements, "atomic requirements");
        }

        Scenario withFixtureSha256(String replacement) {
            return new Scenario(id, bank, archetype, category, fixture, replacement, prompt,
                    approvalBehavior, responseOwnerContract, expectedTerminalStates, allowedTargets,
                    forbiddenTargets, expectedDecisions, atomicRequirements);
        }
    }

    record ScenarioBank(
            String schemaName,
            int schemaVersion,
            String id,
            BankKind kind,
            List<Scenario> scenarios) {
        ScenarioBank {
            schemaName = required(schemaName, "bank schema name");
            id = required(id, "bank id");
            kind = Objects.requireNonNull(kind, "bank kind");
            scenarios = immutableRequired(scenarios, "bank scenarios");
        }
    }

    record FixtureManifest(
            String schemaName,
            int schemaVersion,
            String id,
            Map<String, String> files) {
        FixtureManifest {
            schemaName = required(schemaName, "fixture schema name");
            id = required(id, "fixture id");
            files = files == null ? Map.of() : Map.copyOf(files);
            if (files.isEmpty()) {
                throw new IllegalArgumentException("fixture files must not be empty: " + id);
            }
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <T> List<T> immutableRequired(List<T> values, String label) {
        List<T> copy = immutable(values);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return copy;
    }
}
