package dev.talos.runtime.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveWorkspaceDetectorTest {

    @Test
    void sensitive_folder_detection_warns_for_tax_folder(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("tax-2026"));

        SensitiveWorkspaceDetector.Assessment assessment = SensitiveWorkspaceDetector.assess(workspace);

        assertTrue(assessment.sensitive(), assessment.toString());
        assertTrue(assessment.warning().contains("/privacy private on"), assessment.warning());
    }

    @Test
    void sensitive_folder_detection_warns_for_health_folder(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("health-records"));

        assertTrue(SensitiveWorkspaceDetector.assess(workspace).sensitive());
    }

    @Test
    void sensitive_folder_detection_warns_for_secrets_directory(@TempDir Path workspace) throws Exception {
        Files.createDirectory(workspace.resolve("secrets"));

        SensitiveWorkspaceDetector.Assessment assessment = SensitiveWorkspaceDetector.assess(workspace);

        assertTrue(assessment.sensitive(), assessment.toString());
        assertFalse(assessment.warning().contains("private-notes"), assessment.warning());
    }

    @Test
    void sensitive_folder_detection_warns_for_many_private_documents(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("tax-return.pdf"), "fake");
        Files.writeString(workspace.resolve("insurance-card.png"), "fake");
        Files.writeString(workspace.resolve("bank-statement.docx"), "fake");

        SensitiveWorkspaceDetector.Assessment assessment = SensitiveWorkspaceDetector.assess(workspace);

        assertTrue(assessment.sensitive(), assessment.toString());
        assertTrue(assessment.warning().contains("private documents"), assessment.warning());
    }

    @Test
    void sensitive_folder_detection_does_not_read_file_contents(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "tax FILE_DISCOVERED_CANARY_SHOULD_NOT_BE_READ");

        SensitiveWorkspaceDetector.Assessment assessment = SensitiveWorkspaceDetector.assess(workspace);

        assertFalse(assessment.sensitive(), assessment.toString());
        assertFalse(assessment.warning().contains("FILE_DISCOVERED_CANARY_SHOULD_NOT_BE_READ"), assessment.warning());
    }

    @Test
    void sensitive_folder_warning_recommends_privacy_command(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=do-not-read");

        String warning = SensitiveWorkspaceDetector.assess(workspace).warning();

        assertTrue(warning.contains("This workspace looks sensitive"), warning);
        assertTrue(warning.contains("/privacy private on"), warning);
    }

    @Test
    void non_sensitive_code_workspace_no_warning(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src").resolve("App.java"), "class App {}\n");
        Files.writeString(workspace.resolve("README.md"), "public project\n");

        SensitiveWorkspaceDetector.Assessment assessment = SensitiveWorkspaceDetector.assess(workspace);

        assertFalse(assessment.sensitive(), assessment.toString());
    }

    @Test
    void sensitive_folder_detection_does_not_warn_for_valid_project(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("valid-project"));

        SensitiveWorkspaceDetector.Assessment assessment = SensitiveWorkspaceDetector.assess(workspace);

        assertFalse(assessment.sensitive(), assessment.toString());
    }

    @Test
    void sensitive_folder_detection_does_not_warn_for_grid_ui(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("grid-ui"));

        SensitiveWorkspaceDetector.Assessment assessment = SensitiveWorkspaceDetector.assess(workspace);

        assertFalse(assessment.sensitive(), assessment.toString());
    }

    @Test
    void sensitive_folder_detection_warns_for_id_documents_when_tokenized(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("id-documents"));

        SensitiveWorkspaceDetector.Assessment assessment = SensitiveWorkspaceDetector.assess(workspace);

        assertTrue(assessment.sensitive(), assessment.toString());
    }

    @Test
    void sensitive_folder_detection_warns_for_passport_folder(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("passport-renewal"));

        SensitiveWorkspaceDetector.Assessment assessment = SensitiveWorkspaceDetector.assess(workspace);

        assertTrue(assessment.sensitive(), assessment.toString());
    }
}
