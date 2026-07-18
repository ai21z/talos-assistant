package dev.talos.qualification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;

class QualificationJsonStrictnessTest {

    private record IntegralValue(int value) {}

    private record BooleanValue(boolean value) {}

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
    void numericEnumOrdinalsAreNotAcceptedAsEvidenceValues() {
        byte[] nonCanonical = "1".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> QualificationJson.read(
                nonCanonical, QualificationRecords.FailureClass.class));
    }
}
