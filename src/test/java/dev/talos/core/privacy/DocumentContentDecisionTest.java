package dev.talos.core.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void everyCombinationIntersectsEachPermissionIndependently() {
        for (int left = 0; left < 16; left++) {
            for (int right = 0; right < 16; right++) {
                var a = decision(left);
                var b = decision(right);
                var combined = DocumentContentDecision.forDerivedPair(a, b);
                assertEquals(a.privateDocumentContent() || b.privateDocumentContent(), combined.privateDocumentContent());
                assertEquals(a.modelHandoffAllowed() && b.modelHandoffAllowed(), combined.modelHandoffAllowed());
                assertEquals(a.rawArtifactPersistenceAllowed() && b.rawArtifactPersistenceAllowed(), combined.rawArtifactPersistenceAllowed());
                assertEquals(a.ragIndexAllowed() && b.ragIndexAllowed(), combined.ragIndexAllowed());
                assertEquals(combined, DocumentContentDecision.forDerivedPair(b, a));
                assertTrue(combined.allows(DocumentContentDecision.Use.LOCAL_DISPLAY));
                assertEquals(combined.modelHandoffAllowed(), combined.allows(DocumentContentDecision.Use.MODEL_CONTEXT));
                assertEquals(combined.rawArtifactPersistenceAllowed(), combined.allows(DocumentContentDecision.Use.RAW_ARTIFACT));
                assertEquals(combined.ragIndexAllowed(), combined.allows(DocumentContentDecision.Use.RAG_INDEX));
                assertFalse(combined.reason().contains("Unmarked private prose"));
            }
        }
    }

    @Test
    void absentSourceDecisionOrPurposeDoesNotGrantPermission() {
        assertThrows(NullPointerException.class, () -> DocumentContentDecision.forDerivedPair(null, decision(15)));
        assertThrows(NullPointerException.class, () -> DocumentContentDecision.forDerivedPair(decision(15), null));
        assertThrows(NullPointerException.class, () -> decision(15).allows(null));
    }

    @Test
    void handoffPermissionDoesNotImplyPersistenceOrIndexingPermission() {
        var privateSource = new DocumentContentDecision(true, true, false, false, "private source");
        var publicSource = new DocumentContentDecision(false, true, true, true, "public source");
        var result = DocumentContentDecision.forDerivedPair(privateSource, publicSource);
        assertTrue(result.privateDocumentContent());
        assertTrue(result.allows(DocumentContentDecision.Use.MODEL_CONTEXT));
        assertFalse(result.allows(DocumentContentDecision.Use.RAW_ARTIFACT));
        assertFalse(result.allows(DocumentContentDecision.Use.RAG_INDEX));
    }

    private static DocumentContentDecision decision(int bits) {
        return new DocumentContentDecision((bits & 1) != 0, (bits & 2) != 0,
                (bits & 4) != 0, (bits & 8) != 0, "Unmarked private prose");
    }
}
