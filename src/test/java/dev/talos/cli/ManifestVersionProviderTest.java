package dev.talos.cli;

import dev.talos.core.util.BuildInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ManifestVersionProvider")
class ManifestVersionProviderTest {

    @Test
    @DisplayName("uses BuildInfo version and keeps the public version numeric")
    void versionOutputUsesBuildInfoVersion() throws Exception {
        ManifestVersionProvider provider = new ManifestVersionProvider();

        String output = provider.getVersion()[0];

        assertTrue(output.contains(BuildInfo.version()),
                "Version output should contain the BuildInfo version: " + output);
        assertTrue(output.matches(".*\\b\\d+\\.\\d+\\.\\d+\\b.*"),
                "Public version should be numeric only: " + output);
        assertFalse(output.contains("beta"),
                "Public version output should not include beta suffixes: " + output);
    }
}
