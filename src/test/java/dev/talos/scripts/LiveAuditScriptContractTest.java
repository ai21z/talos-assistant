package dev.talos.scripts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveAuditScriptContractTest {

    private static final Path SCRIPT = Path.of("scripts", "run-capability-live-audit.ps1");

    @Test
    void private_folder_bank_is_explicit_and_generates_manual_runbook() throws Exception {
        String script = Files.readString(SCRIPT);

        assertTrue(script.contains("[switch]$PrivateFolderBank"),
                "Capability live audit script must expose an explicit private-folder bank switch.");
        assertTrue(script.contains("PRIVATE-FOLDER-MANUAL-AUDIT-RUNBOOK.md"),
                "Private-folder audit runs must generate a manual runbook for approval-sensitive probes.");
        assertTrue(script.contains("Join-Path $ManualWorkspaceRoot \"gptoss\""),
                "Manual runbook must format the GPT-OSS fixture path without escaped-variable corruption.");
        assertTrue(script.contains("Join-Path $ManualWorkspaceRoot \"qwen\""),
                "Manual runbook must format the Qwen fixture path without escaped-variable corruption.");
        assertTrue(script.contains("16-private-show-pdf"),
                "Private-folder bank must exercise /show local-display PDF extraction.");
        assertTrue(script.contains("17-private-show-docx"),
                "Private-folder bank must exercise /show local-display DOCX extraction.");
        assertTrue(script.contains("18-private-show-xlsx"),
                "Private-folder bank must exercise /show local-display XLSX extraction.");
        assertTrue(script.contains("19-private-retrieve-disabled"),
                "Private-folder bank must prove retrieve is disabled in private mode by default.");
        assertTrue(script.contains("20-private-reindex-disabled"),
                "Private-folder bank must prove reindex is disabled in private mode by default.");
        assertTrue(script.contains("21-protected-read-denied"),
                "Private-folder bank must include a protected direct-read denial probe.");
    }
}
