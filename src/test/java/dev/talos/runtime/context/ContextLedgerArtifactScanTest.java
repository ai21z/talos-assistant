package dev.talos.runtime.context;

import dev.talos.runtime.policy.ArtifactCanaryScanner;
import dev.talos.tools.ToolContentMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextLedgerArtifactScanTest {

    @Test
    void ledgerTraceArtifactWithOnlyBoundaryMetadataAndHashesPassesCanaryScan(@TempDir Path tempDir)
            throws Exception {
        ContextLedger ledger = new ContextLedger("trc-ledger-artifact", 9);
        ledger.record(
                ContextItem.fromText(
                        ContextItemSource.TOOL_RESULT,
                        ExecutionBoundary.LOCAL_WORKSPACE,
                        ToolContentMetadata.ContentPrivacyClass.PRIVATE_DOCUMENT_EXTRACTED_TEXT,
                        "private-report.pdf",
                        "Patient Name: Eleni Nikolaou",
                        20),
                ContextDecision.withheldFromModel("PRIVATE_DOCUMENT_LOCAL_DISPLAY_ONLY"));
        ContextLedgerSnapshot snapshot = ledger.snapshot();

        Path artifact = tempDir.resolve("trace.json");
        Files.writeString(artifact, snapshot.summary().toString() + "\n" + snapshot.entries().toString());

        assertTrue(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(tempDir), List.of()).isEmpty());
    }
}
