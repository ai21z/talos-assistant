package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QualificationJsonStrictnessTest {
    private record IntegralValue(int value) {}

    private record BooleanValue(boolean value) {}

    private record TextValue(String value) {}

    @Test
    void duplicateObjectKeysAreRejectedBeforeEvidenceInterpretation() {
        byte[] ambiguous = "{\"schemaVersion\":1,\"schemaVersion\":6}"
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> QualificationJson.readTree(ambiguous));
    }

    @Test
    void textualNumbersAreNotCoercedIntoIntegralEvidenceFields() {
        byte[] nonCanonical = "{\"value\":\"6\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> QualificationJson.read(nonCanonical, IntegralValue.class));
    }

    @Test
    void fractionalNumbersAreNotTruncatedIntoIntegralEvidenceFields() {
        byte[] nonCanonical = "{\"value\":7.9}"
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> QualificationJson.read(nonCanonical, IntegralValue.class));
    }

    @Test
    void textualBooleansAreNotCoercedIntoBooleanEvidenceFields() {
        byte[] nonCanonical = "{\"value\":\"true\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> QualificationJson.read(nonCanonical, BooleanValue.class));
    }

    @Test
    void nonTextualScalarsAreNotCoercedIntoTextEvidenceFields() {
        for (String scalar : List.of("6", "6.5", "true", "false")) {
            byte[] nonCanonical = ("{\"value\":" + scalar + "}")
                    .getBytes(StandardCharsets.UTF_8);

            assertThrows(IOException.class,
                    () -> QualificationJson.read(nonCanonical, TextValue.class),
                    scalar);
        }
    }

    @Test
    void numericEnumOrdinalsAreNotAcceptedAsEvidenceValues() {
        byte[] nonCanonical = "1".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> QualificationJson.read(
                nonCanonical, QualificationRecords.FailureClass.class));
    }

    @Test
    void nullPrimitiveValuesAreRejectedInsteadOfBecomingJavaDefaults() {
        byte[] nonCanonical = "{\"value\":null}"
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> QualificationJson.read(nonCanonical, IntegralValue.class));
        assertThrows(IOException.class,
                () -> QualificationJson.read(nonCanonical, BooleanValue.class));
    }

    @Test
    void missingAndNullCreatorPropertiesAreRejected() {
        byte[] missing = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] explicitNull = "{\"value\":null}".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> QualificationJson.read(missing, TextValue.class));
        assertThrows(IOException.class,
                () -> QualificationJson.read(explicitNull, TextValue.class));
    }

    @Test
    void unknownPropertiesAndTrailingDocumentsAreRejected() {
        byte[] unknown = "{\"value\":1,\"unowned\":true}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] trailing = "{\"value\":1} {\"value\":2}"
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> QualificationJson.read(unknown, IntegralValue.class));
        assertThrows(IOException.class,
                () -> QualificationJson.read(trailing, IntegralValue.class));
        assertThrows(IOException.class, () -> QualificationJson.readTree(trailing));
    }

    @Test
    void authorityHelpersRequireExactContainersRangesAndEnumNames() throws Exception {
        var root = QualificationJson.readTree(("""
                {
                  "array": [],
                  "object": {},
                  "integer": 6,
                  "owner": "MODEL"
                }
                """).getBytes(StandardCharsets.UTF_8));

        assertEquals(0, QualificationJson.requiredArray(root.get("array"), "array").size());
        assertEquals(0, QualificationJson.requiredObject(root.get("object"), "object").size());
        assertEquals(6, QualificationJson.requiredInt(
                root.get("integer"), 1, 6, "integer"));
        assertEquals(QualificationRecords.CausalOwner.MODEL,
                QualificationJson.requiredEnum(
                        root.get("owner"), QualificationRecords.CausalOwner.class, "owner"));

        assertThrows(IllegalArgumentException.class,
                () -> QualificationJson.requiredArray(root.get("object"), "array"));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationJson.requiredObject(root.get("array"), "object"));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationJson.requiredInt(root.get("integer"), 1, 5, "integer"));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationJson.requiredEnum(
                        root.get("integer"), QualificationRecords.CausalOwner.class, "owner"));
    }

    @Test
    void scenarioBindingRejectsWrongScalarAndContainerTypesAfterAdaptation()
            throws Exception {
        QualificationProtocol.Scenario canonical =
                QualificationScenarioBank.loadDefault().admission().scenarios().getFirst();
        record Mutation(
                String id,
                java.util.function.Consumer<ObjectNode> apply) {}
        List<Mutation> mutations = List.of(
                new Mutation("numeric-prompt", scenario -> scenario.put("prompt", 7)),
                new Mutation("numeric-approval",
                        scenario -> scenario.put("approvalBehavior", 1)),
                new Mutation("object-terminal-states",
                        scenario -> scenario.set(
                                "expectedTerminalStates",
                                QualificationJson.tree(java.util.Map.of()))),
                new Mutation("scalar-decisions",
                        scenario -> scenario.put("expectedDecisions", "none")));

        for (Mutation mutation : mutations) {
            ObjectNode scenario = (ObjectNode) QualificationJson.tree(canonical);
            mutation.apply().accept(scenario);

            assertThrows(IOException.class,
                    () -> QualificationJson.readScenario(
                            QualificationJson.bytes(scenario),
                            QualificationProtocol.VERSION),
                    mutation.id());
        }
    }

    @Test
    void adaptedScenarioBankAndLegacyRunResultRemainStrictlyTyped() throws Exception {
        QualificationScenarioBank.Catalog current = QualificationScenarioBank.loadDefault();
        ObjectNode bank = (ObjectNode) QualificationJson.tree(current.admission());
        ObjectNode firstScenario =
                (ObjectNode) bank.path("scenarios").path(0);
        firstScenario.put("prompt", 7);

        assertThrows(IOException.class,
                () -> QualificationJson.read(
                        QualificationJson.bytes(bank),
                        QualificationProtocol.ScenarioBank.class));

        QualificationScenarioBank.Catalog legacy = QualificationScenarioBank.load(5);
        QualificationRecords.RunResult currentResult = QualificationTestPacket.runResult(
                legacy, legacy.admission().scenarios().getFirst(), 1, true, true);
        ObjectNode result = (ObjectNode) QualificationJson.tree(currentResult);
        result.put("schemaVersion", QualificationRecords.LEGACY_RUN_SCHEMA_VERSION);
        result.remove(List.of(
                "answerOrigin",
                "responseOwnerContract",
                "causalOwner",
                "causalReasonCode",
                "comparativeScoreEligible",
                "causalLedger"));
        result.put("valid", "true");

        assertThrows(IOException.class,
                () -> QualificationJson.read(
                        QualificationJson.bytes(result),
                        QualificationRecords.RunResult.class));
    }
}
