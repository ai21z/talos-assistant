package dev.talos.core.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentContentDecisionTest {

    @Test
    void preserves_independent_private_document_decision_axes() {
        DocumentContentDecision decision = new DocumentContentDecision(
                true,
                false,
                true,
                false,
                "private mode treats extracted document text as local-display-only by default");

        assertTrue(decision.privateDocumentContent());
        assertFalse(decision.modelHandoffAllowed());
        assertTrue(decision.rawArtifactPersistenceAllowed());
        assertFalse(decision.ragIndexAllowed());
        assertEquals(
                "private mode treats extracted document text as local-display-only by default",
                decision.reason());
    }

    @Test
    void normalizes_null_reason_to_empty_string() {
        DocumentContentDecision decision = new DocumentContentDecision(
                false,
                true,
                false,
                true,
                null);

        assertEquals("", decision.reason());
    }
}
