package dev.talos.cli.prompt;

import dev.talos.core.context.ContextDecision;
import dev.talos.core.context.ContextItem;
import dev.talos.core.context.ContextItemSource;
import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.core.context.ExecutionBoundary;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.tools.ToolContentMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptDebugInspectorContextLedgerTest {

    @AfterEach
    void clear() {
        ContextLedgerCapture.clear();
    }

    @Test
    void promptDebugShowsContextLedgerBoundaryMetadataWithoutRawPrivateText() {
        ContextLedgerCapture.begin("trc-prompt-ledger", 11);
        ContextLedgerCapture.record(
                ContextItem.fromText(
                        ContextItemSource.TOOL_RESULT,
                        ExecutionBoundary.LOCAL_WORKSPACE,
                        ToolContentMetadata.ContentPrivacyClass.PRIVATE_DOCUMENT_EXTRACTED_TEXT,
                        "docs/private-report.pdf",
                        "Patient Name: Eleni Nikolaou",
                        64),
                ContextDecision.withheldFromModel("PRIVATE_DOCUMENT_LOCAL_DISPLAY_ONLY"));

        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "scripted",
                "model",
                false,
                Instant.parse("2026-05-19T12:00:00Z"),
                List.of(ChatMessage.system("sys"), ChatMessage.user("read docs/private-report.pdf")),
                List.of(),
                null,
                "");

        String formatted = PromptDebugInspector.format(snapshot);

        assertTrue(formatted.contains("## Context Ledger"));
        assertTrue(formatted.contains("LOCAL_WORKSPACE"));
        assertTrue(formatted.contains("WITHHELD_FROM_MODEL"));
        assertTrue(formatted.contains("PRIVATE_DOCUMENT_EXTRACTED_TEXT"));
        assertFalse(formatted.contains("Eleni Nikolaou"), formatted);
    }

    @Test
    void promptDebugShowsCompactionStatusDiagnosticWhenAvailable() {
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "llama_cpp",
                "qwen2.5-coder:14b",
                false,
                Instant.parse("2026-06-06T12:00:00Z"),
                List.of(),
                List.of(),
                null,
                "")
                .withDiagnostics(Map.of(
                        "compactionStatus",
                        "status=FAILED category=INTEGRITY_REJECT reason=critical-evidence-missing:index.html"));

        String formatted = PromptDebugInspector.format(snapshot);

        assertTrue(formatted.contains("- Compaction: status=FAILED category=INTEGRITY_REJECT"), formatted);
        assertTrue(formatted.contains("critical-evidence-missing:index.html"), formatted);
    }

    @Test
    void promptDebugShowsProjectMemoryDiagnosticsWithoutRawProtectedContent() {
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "llama_cpp",
                "qwen2.5-coder:14b",
                false,
                Instant.parse("2026-06-07T12:00:00Z"),
                List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.system("[ProjectMemory]\nPRIVATE_MARKER = [redacted-secret-like-value]"),
                        ChatMessage.user("Explain this project.")),
                List.of(),
                null,
                "")
                .withDiagnostics(Map.of(
                        "projectMemoryStatus",
                        "status=LOADED reason=WORKSPACE_EXPLAIN included=1 decisions=1 truncated=0 tiers=REPO_ROOT",
                        "projectMemoryDetails",
                        "tier=REPO_ROOT trust=WORKSPACE_PROVIDED path=TALOS.md action=INCLUDED_IN_MODEL_PROMPT reason=LOADED hash=sha256:abc chars=42 bytes=42 lines=1 tokens=11 truncated=false"));

        String formatted = PromptDebugInspector.format(snapshot);

        assertTrue(formatted.contains("- Project memory: status=LOADED"), formatted);
        assertTrue(formatted.contains("## Project Memory"));
        assertTrue(formatted.contains("tier=REPO_ROOT trust=WORKSPACE_PROVIDED path=TALOS.md"));
        assertFalse(formatted.contains("DO_NOT_LEAK_7F39"), formatted);
    }
}
