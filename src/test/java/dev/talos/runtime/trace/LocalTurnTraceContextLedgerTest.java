package dev.talos.runtime.trace;

import dev.talos.runtime.context.ContextDecision;
import dev.talos.runtime.context.ContextItem;
import dev.talos.runtime.context.ContextItemSource;
import dev.talos.runtime.context.ContextLedgerCapture;
import dev.talos.runtime.context.ExecutionBoundary;
import dev.talos.tools.ToolContentMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalTurnTraceContextLedgerTest {

    @AfterEach
    void clear() {
        ContextLedgerCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test
    void completedTraceIncludesContextLedgerSummaryWithoutRawText() {
        LocalTurnTraceCapture.begin(
                "trc-context-ledger",
                "session",
                3,
                "2026-05-19T12:00:00Z",
                "workspace-hash",
                "unified",
                "scripted",
                "model",
                "read private report.pdf");

        ContextLedgerCapture.record(
                ContextItem.fromText(
                        ContextItemSource.TOOL_RESULT,
                        ExecutionBoundary.LOCAL_WORKSPACE,
                        ToolContentMetadata.ContentPrivacyClass.PRIVATE_DOCUMENT_EXTRACTED_TEXT,
                        "report.pdf",
                        "Patient Name: Eleni Nikolaou",
                        32),
                ContextDecision.withheldFromModel("PRIVATE_DOCUMENT_LOCAL_DISPLAY_ONLY"));
        ContextLedgerCapture.record(
                ContextItem.fromText(
                        ContextItemSource.RAG_SNIPPET,
                        ExecutionBoundary.RAG_INDEX,
                        ToolContentMetadata.ContentPrivacyClass.NORMAL,
                        "src/App.java#0",
                        "class App {}",
                        9),
                ContextDecision.includedInModel("RAG_RETRIEVAL_RESULT_AVAILABLE"));
        ContextLedgerCapture.record(
                ContextItem.fromText(
                        ContextItemSource.SESSION_MEMORY,
                        ExecutionBoundary.SESSION_MEMORY,
                        ToolContentMetadata.ContentPrivacyClass.NORMAL,
                        "",
                        "previous verified change summary",
                        11),
                ContextDecision.includedInModel("SESSION_MEMORY_INCLUDED"));
        ContextLedgerCapture.record(
                ContextItem.fromText(
                        ContextItemSource.COMMAND_OUTPUT,
                        ExecutionBoundary.COMMAND_PROFILE_OUTPUT,
                        ToolContentMetadata.ContentPrivacyClass.COMMAND_OUTPUT,
                        "",
                        "BUILD SUCCESSFUL",
                        6),
                ContextDecision.persistedRedacted("COMMAND_OUTPUT_HASH_ONLY"));
        ContextLedgerCapture.record(
                ContextItem.fromText(
                        ContextItemSource.AUDIT_ARTIFACT,
                        ExecutionBoundary.AUDIT_WORKSPACE,
                        ToolContentMetadata.ContentPrivacyClass.NORMAL,
                        "local/manual-testing/audit/FINDINGS.md",
                        "audit finding summary",
                        7),
                ContextDecision.shownLocallyOnly("AUDIT_ARTIFACT_LOCAL_ONLY"));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertNotNull(trace.contextLedgerSummary());
        assertEquals(5, trace.contextLedgerSummary().totalItems());
        assertEquals(1, trace.contextLedgerSummary().byBoundary().get("LOCAL_WORKSPACE"));
        assertEquals(1, trace.contextLedgerSummary().byBoundary().get("RAG_INDEX"));
        assertEquals(1, trace.contextLedgerSummary().byBoundary().get("SESSION_MEMORY"));
        assertEquals(1, trace.contextLedgerSummary().byBoundary().get("COMMAND_PROFILE_OUTPUT"));
        assertEquals(1, trace.contextLedgerSummary().byBoundary().get("AUDIT_WORKSPACE"));
        assertEquals(1, trace.contextLedgerSummary().byDecision().get("WITHHELD_FROM_MODEL"));
        assertFalse(trace.toString().contains("Eleni Nikolaou"), trace.toString());
        assertFalse(trace.toString().contains("BUILD SUCCESSFUL"), trace.toString());
    }
}
