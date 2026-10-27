package dev.talos.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Docs honesty for GPU verification attribution. `talos doctor --start`
 * verifies that the managed server starts, answers a smoke prompt, and
 * reports rate facts. GPU offload evidence is verified by `talos tune`
 * before it keeps a GPU-lane config. Public docs must not attribute
 * offload verification to doctor - the code does not do that.
 */
class DoctorOffloadDocsHonestyTest {

    @Test
    void publicDocsDoNotAttributeGpuOffloadVerificationToDoctor() throws IOException {
        try (var stream = Files.walk(Path.of("docs"))) {
            for (Path md : stream.filter(p -> p.toString().endsWith(".md")).toList()) {
                List<String> lines = Files.readAllLines(md);
                for (int i = 0; i < lines.size(); i++) {
                    // Sentence-level: a paragraph may honestly state both
                    // facts side by side; the dishonest shape is one
                    // sentence pairing offload with doctor.
                    for (String sentence : lines.get(i).split("(?<=[.!?])\\s+")) {
                        String lower = sentence.toLowerCase(Locale.ROOT);
                        if (lower.contains("offload") && lower.contains("doctor")) {
                            fail(md + ":" + (i + 1)
                                    + " attributes GPU offload to doctor; offload evidence is verified by"
                                    + " talos tune, doctor verifies start/smoke/rates: " + sentence);
                        }
                    }
                }
            }
        }
    }

    @Test
    void setupDocsAttributeOffloadVerificationToTune() throws IOException {
        String modelSetup = Files.readString(Path.of("docs/getting-started/model-setup.md"));
        String windowsSetup = Files.readString(Path.of("docs/getting-started/windows-setup.md"));

        assertTrue(modelSetup.contains("verified by `talos tune`"),
                "model-setup.md must attribute GPU offload verification to talos tune");
        assertTrue(windowsSetup.contains("verified by `talos tune`"),
                "windows-setup.md must attribute GPU offload verification to talos tune");
    }
}
