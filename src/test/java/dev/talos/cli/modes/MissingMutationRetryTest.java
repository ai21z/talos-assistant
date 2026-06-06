package dev.talos.cli.modes;

import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingMutationRetryTest {

    @Test
    void compactStaticRepairContextBelongsToMissingMutationRetry() {
        ChatMessage compact = MissingMutationRetry.compactStaticVerificationRepairInstructionForRetry(
                ChatMessage.system("""
                        [Static verification repair context]
                        The previous mutation task ended incomplete after static verification.

                        Expected targets: index.html, scripts.js, styles.css

                        Missing expected targets: scripts.js

                        Previous static verification problems:
                        - scripts.js: expected target was not successfully mutated.
                        - HTML does not link JavaScript file: `scripts.js`
                        - Calculator/form task is missing a submit/calculate button.

                        Repair plan:
                        Full-file replacement targets: index.html, scripts.js, styles.css
                        - index.html: You must use talos.write_file with complete corrected file content for index.html.
                        - scripts.js: You must use talos.write_file with complete corrected file content for scripts.js.
                        - styles.css: You must use talos.write_file with complete corrected file content for styles.css.

                        Cross-file coherence checklist:
                        - HTML must link every CSS and JavaScript file being written.
                        - Every JavaScript ID or selector must exist in HTML before the JavaScript uses it.
                        """
                        + "VERBOSE_REPAIR_PADDING ".repeat(200)));

        String content = compact.content();
        assertTrue(content.startsWith("[Static verification repair context]"), content);
        assertTrue(content.contains("Expected targets: index.html, scripts.js, styles.css"), content);
        assertTrue(content.contains("Missing expected targets: scripts.js"), content);
        assertTrue(content.contains("scripts.js: expected target was not successfully mutated."), content);
        assertTrue(content.contains("Full-file replacement targets: index.html, scripts.js, styles.css"), content);
        assertFalse(content.contains("VERBOSE_REPAIR_PADDING"), content);
        assertFalse(content.contains("Cross-file coherence checklist"), content);
    }

    @Test
    void compactStaticRepairContextPreservesRequirementsAndDropsNonControllingSelectorInventory() {
        ChatMessage compact = MissingMutationRetry.compactStaticVerificationRepairInstructionForRetry(
                ChatMessage.system("""
                        [Static verification repair context]
                        Previous mutation task ended incomplete after static verification.

                        Expected targets: index.html, style.css, script.js

                        [StaticWebRequirements]
                        requiredVisibleFacts: Retrocats, Costanza, Merri, Rome 15 July 2026
                        forbiddenArtifacts: tailwind.css, tailwind.min.css

                        Previous static verification problems:
                        - tailwind.css: local Tailwind artifact is unsupported without an explicit build/runtime path.
                        - style.css: expected target was not successfully mutated.

                        Repair plan:
                        Full-file replacement targets: index.html, style.css, script.js

                        [Current static selector facts]
                        HTML classes: %s
                        CSS classes: %s
                        JavaScript selectors: %s
                        """.formatted(
                        "class-token ".repeat(250),
                        "css-token ".repeat(250),
                        "js-token ".repeat(250))));

        String content = compact.content();
        assertTrue(content.contains("[StaticWebRequirements]"), content);
        assertTrue(content.contains("requiredVisibleFacts: Retrocats, Costanza, Merri, Rome 15 July 2026"),
                content);
        assertTrue(content.contains("forbiddenArtifacts: tailwind.css, tailwind.min.css"), content);
        assertTrue(content.contains("Full-file replacement targets: index.html, style.css, script.js"), content);
        assertFalse(content.contains("[Current static selector facts]"), content);
        assertFalse(content.contains("class-token"), content);
        assertTrue(content.length() < 1_800, "compact repair context too large: " + content.length());
    }
}
