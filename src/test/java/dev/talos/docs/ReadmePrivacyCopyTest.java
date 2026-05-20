package dev.talos.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadmePrivacyCopyTest {

    @Test
    void readme_privacy_section_does_not_imply_persistent_config_if_not_persisted() throws Exception {
        String readme = Files.readString(Path.of("README.md"));

        assertTrue(readme.contains("current session/config state"), readme);
        assertTrue(readme.contains("does not write persistent defaults to `~/.talos/config.yaml`"), readme);
        assertFalse(readme.contains("switches the session/config state to private mode."), readme);
    }

    @Test
    void readme_has_explicit_file_capability_matrix_for_beta_claims() throws Exception {
        String readme = Files.readString(Path.of("README.md"));

        assertTrue(readme.contains("#### Capability Matrix"), readme);
        assertTrue(readme.contains("| Area | Beta claim | Boundary |"), readme);
        assertTrue(readme.contains("| PDF | Text extraction for text-bearing PDFs | Not PDF creation"), readme);
        assertTrue(readme.contains("| Word | Text extraction for `.docx` | Not `.doc`"), readme);
        assertTrue(readme.contains("| Excel | Visible-cell extraction for `.xls`/`.xlsx` | No formula recalculation"), readme);
        assertTrue(readme.contains("| Image/OCR | Frozen out of beta product claims |"), readme);
        assertTrue(readme.contains("| PowerPoint | Frozen out of beta product claims |"), readme);
        assertTrue(readme.contains("| Private paperwork | Not an approved beta product claim |"), readme);
        assertTrue(readme.contains("Talos cannot create valid PDF/DOCX/XLS/XLSX files"), readme);
    }
}
