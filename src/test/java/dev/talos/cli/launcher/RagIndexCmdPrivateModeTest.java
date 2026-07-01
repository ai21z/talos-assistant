package dev.talos.cli.launcher;

import dev.talos.core.Config;
import dev.talos.core.index.Indexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagIndexCmdPrivateModeTest {

    @Test
    void rag_index_command_refuses_private_mode_when_rag_disabled(@TempDir Path tempDir) throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(home.resolve(".talos"));
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("README.md"), "public text that would normally be indexed\n", StandardCharsets.UTF_8);
        Files.writeString(home.resolve(".talos").resolve("config.yaml"), """
                privacy:
                  mode: private
                  rag:
                    enabled_in_private_mode: false
                rag:
                  vectors:
                    enabled: false
                """, StandardCharsets.UTF_8);

        String previousHome = System.getProperty("user.home");
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setProperty("user.home", home.toString());
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));

            RagIndexCmd cmd = new RagIndexCmd();
            cmd.root = workspace.toString();
            cmd.forceFull = true;
            cmd.run();
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
            System.setOut(previousOut);
            System.setErr(previousErr);
        }

        String combined = stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8);
        assertTrue(combined.contains("RAG indexing is disabled in private mode"), combined);
        Path metadata = new Indexer(new Config(home.resolve(".talos").resolve("config.yaml"))).policyMetadataFile(workspace);
        assertFalse(Files.exists(metadata),
                "top-level rag-index must not write index metadata when private-mode RAG is disabled");
    }
}
