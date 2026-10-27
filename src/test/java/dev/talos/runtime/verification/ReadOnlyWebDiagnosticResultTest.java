package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyWebDiagnosticResultTest {
    @Test
    void resultMetadataIsNotRecoveredFromDisplayText() {
        var noAssessment = ReadOnlyWebDiagnosticResult.unaffected(ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX);
        assertFalse(noAssessment.assessment().limited());
        assertFalse(noAssessment.assessment().preserved());
        assertTrue(noAssessment.diagnostics().isEmpty());

        var limited = new ReadOnlyWebDiagnosticResult("A bounded diagnosis was retained.",
                ReadOnlyWebAnswerAssessment.limited(ReadOnlyWebAnswerAssessment.Reason.UNSUPPORTED_ASSERTION),
                List.of());
        assertTrue(limited.assessment().limited());
        assertFalse(limited.answer().startsWith(ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX));
    }

    @Test
    void defaultsAndDiagnosticCopiesAreStable() {
        var empty = new ReadOnlyWebDiagnosticResult(null, null, null);
        assertNull(empty.answer());
        assertEquals(ReadOnlyWebAnswerAssessment.notApplicable(), empty.assessment());
        assertTrue(empty.diagnostics().isEmpty());

        var diagnostics = new ArrayList<>(List.of(StaticWebDiagnostic.other("An unsupported issue was observed.")));
        var result = new ReadOnlyWebDiagnosticResult("answer", ReadOnlyWebAnswerAssessment.notApplicable(), diagnostics);
        diagnostics.clear();
        assertEquals(1, result.diagnostics().size());
        assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
    }
}
