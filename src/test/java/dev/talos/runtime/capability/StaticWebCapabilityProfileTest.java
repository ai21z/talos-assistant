package dev.talos.runtime.capability;

import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebCapabilityProfileTest {

    @Test
    void scopedDoNotCreateExtraFilesDoesNotRequireSeparateAssetMutations(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html><head><link rel="stylesheet" href="styles.css"></head>
                <body><button id="pulse-button">Pulse</button><script src="scripts.js"></script></body></html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "body { color: white; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.addEventListener('DOMContentLoaded', () => {
                  document.getElementById('pulse-button').addEventListener('click', () => {});
                });
                """);

        var contract = TaskContractResolver.fromUserRequest(
                "Improve only styles.css. Do not create extra files. Do not modify index.html or scripts.js.");

        CapabilityProfile profile = StaticWebCapabilityProfile.select(contract, workspace, Set.of("styles.css"));

        assertTrue(profile.staticWeb());
        assertFalse(StaticWebCapabilityProfile.requiresSeparateAssetMutations(profile));
    }
}
