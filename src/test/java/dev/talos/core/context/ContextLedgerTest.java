package dev.talos.core.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextLedgerTest {

    @Test
    void contextItemStoresBoundaryAndHashWithoutRawPrivateText() {
        ContextItem item = ContextItem.fromText(
                ContextItemSource.TOOL_RESULT,
                ExecutionBoundary.LOCAL_WORKSPACE,
                ContextPrivacyClass.PRIVATE_DOCUMENT_EXTRACTED_TEXT,
                "docs/private-tax.pdf",
                "Patient Name: Eleni Nikolaou\nTALOS_FAKE_SECRET=sk-test-DO-NOT-LEAK",
                128);

        assertEquals(ContextItemSource.TOOL_RESULT, item.source());
        assertEquals(ExecutionBoundary.LOCAL_WORKSPACE, item.executionBoundary());
        assertEquals(ContextPrivacyClass.PRIVATE_DOCUMENT_EXTRACTED_TEXT,
                item.privacyClass());
        assertEquals("docs/private-tax.pdf", item.pathHint());
        assertTrue(item.textHash().startsWith("sha256:"), item.textHash());
        assertEquals(128, item.estimatedTokens());

        String rendered = item.toString();
        assertFalse(rendered.contains("Eleni Nikolaou"), rendered);
        assertFalse(rendered.contains("sk-test-DO-NOT-LEAK"), rendered);
    }

    @Test
    void ledgerSummarySeparatesDecisionBoundarySourceAndPrivacyCounts() {
        ContextLedger ledger = new ContextLedger("trc-context", 7);
        ledger.record(
                ContextItem.fromText(
                        ContextItemSource.TOOL_RESULT,
                        ExecutionBoundary.LOCAL_WORKSPACE,
                        ContextPrivacyClass.NORMAL,
                        "README.md",
                        "safe project text",
                        10),
                ContextDecision.includedInModel("LOCAL_READ_INCLUDED"));
        ledger.record(
                ContextItem.fromText(
                        ContextItemSource.RAG_SNIPPET,
                        ExecutionBoundary.RAG_INDEX,
                        ContextPrivacyClass.NORMAL,
                        "src/App.java#0",
                        "class App {}",
                        8),
                ContextDecision.excludedByPrivacyOrTrustPolicy("PRIVATE_MODE_RAG_DISABLED"));

        ContextLedgerSummary summary = ledger.snapshot().summary();

        assertEquals(2, summary.totalItems());
        assertEquals(1, summary.byDecision().get("INCLUDED_IN_MODEL_PROMPT"));
        assertEquals(1, summary.byDecision().get("EXCLUDED_BY_PRIVACY_OR_TRUST_POLICY"));
        assertEquals(1, summary.byBoundary().get("LOCAL_WORKSPACE"));
        assertEquals(1, summary.byBoundary().get("RAG_INDEX"));
        assertEquals(1, summary.bySource().get("TOOL_RESULT"));
        assertEquals(1, summary.bySource().get("RAG_SNIPPET"));
        assertEquals(2, summary.byPrivacyClass().get("NORMAL"));
        assertEquals(1, summary.byReason().get("PRIVATE_MODE_RAG_DISABLED"));
    }

    @Test
    void ledgerSeparatesSessionCommandAuditAndExternalBoundaries() {
        ContextLedger ledger = new ContextLedger("trc-boundaries", 8);
        ledger.record(
                ContextItem.fromText(
                        ContextItemSource.SESSION_MEMORY,
                        ExecutionBoundary.SESSION_MEMORY,
                        ContextPrivacyClass.NORMAL,
                        "",
                        "last verified turn summary",
                        12),
                ContextDecision.includedInModel("SESSION_MEMORY_INCLUDED"));
        ledger.record(
                ContextItem.fromText(
                        ContextItemSource.COMMAND_OUTPUT,
                        ExecutionBoundary.COMMAND_PROFILE_OUTPUT,
                        ContextPrivacyClass.COMMAND_OUTPUT,
                        "",
                        "BUILD SUCCESSFUL",
                        9),
                ContextDecision.persistedRedacted("COMMAND_OUTPUT_HASH_ONLY"));
        ledger.record(
                ContextItem.fromText(
                        ContextItemSource.AUDIT_ARTIFACT,
                        ExecutionBoundary.AUDIT_WORKSPACE,
                        ContextPrivacyClass.NORMAL,
                        "local/manual-testing/audit/FINDINGS.md",
                        "finding summary",
                        7),
                ContextDecision.shownLocallyOnly("AUDIT_ARTIFACT_LOCAL_ONLY"));
        ledger.record(
                ContextItem.fromText(
                        ContextItemSource.EXTERNAL_REQUEST,
                        ExecutionBoundary.EXTERNAL_OR_CLOUD,
                        ContextPrivacyClass.NORMAL,
                        "",
                        "use a cloud agent",
                        5),
                ContextDecision.refusedUnsupportedBoundary("NO_CLOUD_AGENT_CAPABILITY"));

        ContextLedgerSummary summary = ledger.snapshot().summary();

        assertEquals(1, summary.byBoundary().get("SESSION_MEMORY"));
        assertEquals(1, summary.byBoundary().get("COMMAND_PROFILE_OUTPUT"));
        assertEquals(1, summary.byBoundary().get("AUDIT_WORKSPACE"));
        assertEquals(1, summary.byBoundary().get("EXTERNAL_OR_CLOUD"));
        assertEquals(1, summary.byDecision().get("REFUSED_UNSUPPORTED_BOUNDARY"));
        assertEquals(1, summary.byReason().get("NO_CLOUD_AGENT_CAPABILITY"));
    }
}
