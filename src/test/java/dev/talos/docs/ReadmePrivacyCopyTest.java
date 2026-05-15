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
}
